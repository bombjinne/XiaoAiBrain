package com.xiaoaibrain

import android.content.Intent
import java.util.Collections

/**
 * 聊天事件总线（v1.0.10 新增）：把「一通电话」抽象成微信式聊天流，供配置页的聊天框渲染。
 *
 * 复用 HookLogBus 的跨进程通道：
 *   - hook 进程（小爱通话）：ChatBus.emitPeer(...) / emitAi(...) / emitCaller(...)
 *     通过显式广播把消息发到本模块 App
 *   - UI 进程（配置页）：ChatReceiver 收到后 appendLocal() 进内存列表，MainActivity 轮询渲染
 *
 * 消息模型 ChatMsg：
 *   role=PEER 对方（来电方）说的话      —— 气泡在左 + 灰色 + 对方头像
 *   role=AI   本模块 LLM 生成的回复    —— 气泡在右 + 主色 + 小爱头像
 *   role=SYS  状态提示（如"新通话"）   —— 居中灰色小字
 *
 * 附带 callerName/callerNumber（对方号码与通讯录备注名，由 A5 hook 从端上明文读取），
 * 在聊天框顶部显示"正在和谁通话"。
 */
object ChatBus {

    const val ACTION_CHAT = "com.xiaoaibrain.CHAT_EVENT"

    const val EXTRA_ROLE = "role"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TIME = "time"
    const val EXTRA_CALLER_NAME = "callerName"
    const val EXTRA_CALLER_NUM = "callerNumber"

    /** 附加标记：本条事件代表「新一通电话接入」（v1.0.27 新增） */
    const val EXTRA_NEW_CALL = "newCall"

    const val ROLE_PEER = "PEER"
    const val ROLE_AI = "AI"
    const val ROLE_SYS = "SYS"

    /** 一条聊天消息 */
    data class ChatMsg(
        val role: String,
        val text: String,
        val time: Long,
        val callerName: String? = null,
        val callerNumber: String? = null
    )

    /** UI 进程内存缓冲（仅配置页进程有效） */
    val messages: MutableList<ChatMsg> = Collections.synchronizedList(mutableListOf())
    private const val MAX_MSGS = 200

    /** 对方号码 / 备注名（v1.0.10 由 A5 hook 从端上明文读出，供顶部展示） */
    @Volatile var callerName: String? = null
    @Volatile var callerNumber: String? = null

    /**
     * 「第几通电话」序号（v1.0.27）。
     *
     * 只在【UI 进程】有意义：hook 侧判断出新一通电话接入后广播 NEW_CALL，
     * ChatReceiver 收到即调 beginNewSession() 自增本序号；MainActivity 每轮轮询
     * 比对序号，发现变化就清空聊天面板（上一通的对话早已写进 CallLogDb 历史库，
     * 界面不该再留着）。用序号而不是布尔标志，是为了避免"清空动作"被连点或
     * 多次广播重复消费。
     */
    @Volatile var sessionSeq: Long = 0L
        private set

    /** 本通电话开始时刻（UI 进程内），用于过滤上一通的残留消息 */
    @Volatile var sessionStartedAt: Long = 0L
        private set

    // ───────────────────── hook 进程调用 ─────────────────────

    /** 对方（来电方）说了一句话 */
    fun emitPeer(text: String) = emit(ROLE_PEER, text)

    /** 本模块 AI 的回复 */
    fun emitAi(text: String) = emit(ROLE_AI, text)

    /** 状态提示（居中） */
    fun emitSys(text: String) = emit(ROLE_SYS, text)

    /**
     * 新一通电话接入（v1.0.27）。
     *
     * 与 emitSys("── 新通话 ──") 的区别：本条带 EXTRA_NEW_CALL 标记，
     * 接收方据此做三件事——① 清空界面上的上一通对话；② 重置通话对象；
     * ③ 在数据库里显式开一通新记录（上一通就此封存进历史通话）。
     *
     * 调用点有两处：A5 hook（InCallAiCallService.x，电话接入瞬间）和
     * A1 长时间静默兜底（captureUtterance，距上次说话 >30s）。
     */
    fun emitNewCall(number: String? = callerNumber, name: String? = callerName) {
        try {
            val ctx = SysBridge.currentApplication() ?: return
            ctx.sendBroadcast(
                Intent(ACTION_CHAT)
                    .putExtra(EXTRA_ROLE, ROLE_SYS)
                    .putExtra(EXTRA_TEXT, "── 新通话 ──")
                    .putExtra(EXTRA_TIME, System.currentTimeMillis())
                    .putExtra(EXTRA_NEW_CALL, true)
                    .putExtra(EXTRA_CALLER_NAME, name)
                    .putExtra(EXTRA_CALLER_NUM, number)
                    .setPackage(Constants.PKG)
            )
        } catch (_: Throwable) {
            // 广播失败不影响通话主流程
        }
    }

    /**
     * 设置通话对象信息（号码 + 备注名）。
     * 由 A5 hook 在读到端上明文号码/联系人时调用；只广播一次，避免刷屏。
     * name 若像小爱内部枚举（MODE_SUBTITLES / FROM_xxx 等）或非中文/字母人名，则忽略只留号码。
     */
    fun emitCaller(name: String?, number: String?) {
        val n = number?.trim().orEmpty()
        val raw = name?.trim().orEmpty()
        // 过滤内部枚举（实测 v1.0.11 顶部误显示 MODE_SUBTITLES）
        val nm = if (raw.isNotEmpty() && !looksLikeEnum(raw)) raw else ""
        if (n.isEmpty() && nm.isEmpty()) return
        if (n == callerNumber && nm == callerName) return
        callerNumber = n.ifEmpty { callerNumber }
        callerName = nm.ifEmpty { callerName }
        try {
            val ctx = SysBridge.currentApplication() ?: return
            ctx.sendBroadcast(
                Intent(ACTION_CHAT)
                    .putExtra(EXTRA_ROLE, "CALLER")
                    .putExtra(EXTRA_CALLER_NAME, callerName)
                    .putExtra(EXTRA_CALLER_NUM, callerNumber)
                    .setPackage(Constants.PKG)
            )
        } catch (_: Throwable) { }
    }

    /** 判断是否小爱内部枚举/常量而非真人名 */
    private fun looksLikeEnum(s: String): Boolean {
        if (s.contains(" ")) return false                      // 含空格可能是「中国移动 客服」
        if (s.length > 12) return false
        if (s.all { it.isUpperCase() || it == '_' }) return true
        val up = s.uppercase()
        val enums = listOf("MODE_", "FROM_", "TYPE_", "CALL_", "DIAL_", "AUTO_", "AI_", "UNKNOWN")
        return enums.any { up.startsWith(it) }
    }

    private fun emit(role: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        try {
            val ctx = SysBridge.currentApplication() ?: return
            ctx.sendBroadcast(
                Intent(ACTION_CHAT)
                    .putExtra(EXTRA_ROLE, role)
                    .putExtra(EXTRA_TEXT, t)
                    .putExtra(EXTRA_TIME, System.currentTimeMillis())
                    .putExtra(EXTRA_CALLER_NAME, callerName)
                    .putExtra(EXTRA_CALLER_NUM, callerNumber)
                    .setPackage(Constants.PKG)
            )
        } catch (_: Throwable) {
            // 广播失败不影响通话主流程
        }
    }

    // ───────────────────── UI 进程调用 ─────────────────────

    /** 配置页直接本地追加（测试连接等场景） */
    fun appendLocal(m: ChatMsg) {
        messages.add(m)
        if (messages.size > MAX_MSGS) {
            synchronized(messages) { messages.subList(0, messages.size - MAX_MSGS).clear() }
        }
    }

    /** 清空当前通话的聊天（界面上的"清空"按钮） */
    fun clearLocal() {
        synchronized(messages) { messages.clear() }
    }

    /**
     * 标记「新一通电话开始」（UI 进程，由 ChatReceiver 调用）。
     *
     * 做两件事：清空界面内存缓冲（上一通的对话已经写进 CallLogDb，不需要也不应该
     * 继续显示在"最新通话"里）、序号 +1 供 MainActivity 感知并重置气泡容器。
     */
    fun beginNewSession(at: Long = System.currentTimeMillis()) {
        synchronized(messages) { messages.clear() }
        sessionStartedAt = at
        sessionSeq += 1
    }
}