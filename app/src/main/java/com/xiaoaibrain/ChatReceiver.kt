package com.xiaoaibrain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 hook 进程（小爱通话）广播来的两类事件：
 *
 *  ① CHAT_EVENT（com.xiaoaibrain.CHAT_EVENT）：聊天事件（对方说的话 / AI 回复 /
 *     通话对象信息）。三处落盘：
 *       · ChatBus  内存缓冲（配置页进程存活时实时渲染）
 *       · ChatStore 私有 JSON（无需权限的兜底回显）
 *       · CallLogDb SQLite（v1.0.26 新增：长期留存 + 可按通话人/时间检索）
 *
 *  ② HOOK_STATUS（com.xiaoaibrain.HOOK_STATUS）：hook 挂载结果（OK / ERROR），
 *     持久化到 SharedPreferences，配置页实时展示"hook 成功还是失败"。
 *
 * ★ 关键：写入必须在【配置页没打开时】也能完成——显式广播会把已死进程拉起来，
 *   所以通话全程即使没开 App，记录依然进数据库。
 *
 * 线程模型（v1.0.17 起）：goAsync() + 后台线程处理，主线程绝不被广播派发阻塞
 * （v1.0.17 之前广播跑在主线程，一次通话几百条广播直接把 UI 主线程淹死）。
 */
class ChatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext
        when (intent?.action) {
            ChatBus.ACTION_CHAT -> handleChat(app, intent)
            HookStatus.ACTION -> handleHookStatus(app, intent)
        }
    }

    // ───────────────────── 聊天事件 ─────────────────────

    private fun handleChat(app: Context?, intent: Intent) {
        // 先把 intent 里的字段取出（主线程、极轻），真正的写入放到后台
        val role = intent.getStringExtra(ChatBus.EXTRA_ROLE) ?: return
        val name = intent.getStringExtra(ChatBus.EXTRA_CALLER_NAME)
        val num = intent.getStringExtra(ChatBus.EXTRA_CALLER_NUM)
        val text = intent.getStringExtra(ChatBus.EXTRA_TEXT)
        val time = intent.getLongExtra(ChatBus.EXTRA_TIME, System.currentTimeMillis())
        val flagNewCall = intent.getBooleanExtra(ChatBus.EXTRA_NEW_CALL, false)

        val pending = goAsync()
        Thread({
            try {
                if (role == "CALLER") {
                    if (!name.isNullOrEmpty()) ChatBus.callerName = name
                    if (!num.isNullOrEmpty()) ChatBus.callerNumber = num
                    app?.let { ctx ->
                        ChatStore.setCaller(ctx, name, num)
                        // 通话对象信息回填到当前通话记录（可能晚于首条消息才到）
                        val db = CallLogDb.get(ctx)
                        db.ensureCall(num, name, time)
                    }
                } else if (text != null) {
                    // 新一通电话接入（v1.0.27）：界面上的上一通对话要立刻清掉，
                    // 但上一通早已逐条写进 CallLogDb —— 数据留在「历史通话」，不进"最新通话"。
                    // 两种来源：hook 侧 InCallAiCallService.x（电话接入瞬间，带 EXTRA_NEW_CALL 标记）
                    //           / A1 静默兜底（SYS 文本 "新通话"，兼容旧版本）
                    val isNewCall = flagNewCall ||
                        (role == ChatBus.ROLE_SYS && text.contains("新通话"))
                    if (isNewCall) {
                        ChatBus.beginNewSession(time)
                        // 通话对象换成这通电话的人（备注名留空，等 A5 查到再补）
                        ChatBus.callerName = name?.takeIf { it.isNotBlank() }
                        ChatBus.callerNumber = num?.takeIf { it.isNotBlank() }
                        app?.let { ctx ->
                            ChatStore.clear(ctx)
                            ChatStore.setCaller(ctx, ChatBus.callerName, ChatBus.callerNumber)
                        }
                    }
                    ChatBus.appendLocal(
                        ChatBus.ChatMsg(
                            role = role,
                            text = text,
                            time = time,
                            callerName = ChatBus.callerName,
                            callerNumber = ChatBus.callerNumber
                        )
                    )
                    app?.let { ctx ->
                        ChatStore.append(ctx, role, text, time)
                        // 写数据库：新通话显式开一通，否则复用 5 分钟窗口内的当前通话
                        val db = CallLogDb.get(ctx)
                        val callId = if (isNewCall) {
                            db.startCall(ChatBus.callerNumber, ChatBus.callerName, time)
                        } else {
                            db.ensureCall(ChatBus.callerNumber, ChatBus.callerName, time)
                        }
                        // 状态 chip（"新通话"）不入库为消息，只作为通话分隔
                        if (!isNewCall) {
                            db.appendMessage(callId, role, text, time)
                        }
                    }
                }
            } catch (_: Throwable) {
                // 落库失败绝不能影响通话主流程
            } finally {
                pending.finish()
            }
        }, "ChatReceiverWorker").apply { isDaemon = true; start() }
    }

    // ───────────────────── hook 状态事件（v1.0.24） ─────────────────────

    private fun handleHookStatus(app: Context?, intent: Intent) {
        val state = intent.getStringExtra("state") ?: return
        val version = intent.getStringExtra("version") ?: ""
        val time = intent.getLongExtra("time", System.currentTimeMillis())
        val msg = intent.getStringExtra("msg") ?: ""

        val pending = goAsync()
        Thread({
            try {
                app?.let { HookStatus.save(it, state, version, time, msg) }
            } finally {
                pending.finish()
            }
        }, "HookStatusWorker").apply { isDaemon = true; start() }
    }
}