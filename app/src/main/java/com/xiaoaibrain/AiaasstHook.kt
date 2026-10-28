package com.xiaoaibrain

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小爱通话（com.xiaomi.aiasst.service 6.0.3）真实链路 Hook。
 *
 * 架构（REA 逆向 + 算法助手真机实测已确认）：
 *   对方说话 → AsrManager(下行ASR) → ProcessManage.onDownLinkAsrRealTimeResult(边说边出的半句)
 *                                    → ProcessManage.onDownLinkAsrBack(text, ?, isFinal)
 *                                    → ProcessManage.onDownLinkAsrFinish(text, ?, isFinal) ← 这句说完了
 *            → ProcessManage.pollAsrFinishQueue → NlpManager.getAutoAnswerRequest(query, event)
 *            → ProcessManage.onNlpBack → notifyPresenterOnNlpBack(json, screenQuery, eventId, bean)
 *            → ProcessManage.doTts(event{ payload.text = 回复文本 }) → 服务端合成音频 → 播放
 *
 * 方案：
 *   1) 挂 ASR 回调拿「isFinal」信号（对方是否真的说完）——这是根治"半句就抢答"的关键：
 *      onDownLinkAsrRealTimeResult = 半句(进行中)；onDownLinkAsrBack/Finish 的布尔参 = 是否最终句。
 *   2) getAutoAnswerRequest(afterHook)：捕获 query，做短去抖+合并(连拍拆句)，交【后台线程】跑 LLM。
 *      后台任务会先等 isFinal（对方说完）再调 LLM，避免把半句发给 LLM。
 *   3) notifyPresenterOnNlpBack(beforeHook)：仅对 types=auto_answer 的事件，把 param0 JSON 的 text +
 *      bean.text 替换为 LLM 回复（可能影响界面显示）；非主线程时**阻塞等待**后台 LLM 结果。
 *   4) doTts(beforeHook)：真正决定「念出什么声音」的入口。v1.0.4 实测证明 param0/bean 不是 TTS
 *      合成读取的文本源，故在 doTts 入口直接反射改写 Event（含 payload）里所有 text 字段，
 *      一并写入 lastInjectedText 供音频抓取标注。
 *
 * LLM 在后台线程跑（aiasst 现可直连外网，约 1~5s 返回）；注入点 A2 在 worker 线程上阻塞等待该结果，
 * 保证 LLM 回复不会因"迟到"而被丢弃。任何一步失败都保留小爱原文，绝不破坏通话。
 */
object AiaasstHook {

    private const val NLP_CLASS = "com.xiaomi.aiasst.service.aicall.process.nlp.NlpManager"
    private const val PM_CLASS = "com.xiaomi.aiasst.service.aicall.process.ProcessManage"
    // v1.0.6 修正：doTts(Event)/buildTtsEvent(String) 实际在 TtsManager，不在 ProcessManage（APK 反编译实锲）
    private const val TTS_CLASS = "com.xiaomi.aiasst.service.aicall.process.tts.TtsManager"
    // v1.0.7：是否启用"改事件文本"的音频注入。
    // 背景：小爱通话 TTS 是【服务端合成】，只改本地事件文本要么对不上静音、要么云端按原话合成，
    // 永远改不了"念出来的声音"。故默认关闭以恢复可听；真正让 AI 出声需 V2（替换播放音频）。
    private const val INJECT_AUDIO_TEXT = false
    // v1.0.10 新增 A5：通话对象（号码 + 通讯录备注名）相关类名。
    // 全部来自 AI通话 6.0.3 (com.xiaomi.aiasst.service) 的 androguard 反汇编实证。
    private const val INCALL_SVC_CLASS = "com.xiaomi.aiasst.service.aicall.incall.InCallAiCallService"
    private const val MODEL_B_CLASS = "com.xiaomi.aiasst.service.aicall.model.b"
    // f5.b 是混淆类名（查通讯录 Phone.CONTENT_URI → display_name），版本升级可能变化，失败静默降级
    private const val CONTACT_FINDER_CLASS = "f5.b"
    // v1.0.8：采样率改回 16kHz（v1.0.5 即如此；v1.0.6 误判 8k 导致半速变调）；捕获只落 .wav 不写 .bin；
    // LLM 默认超时 5s→10s、读取留 3s 余量、失败冷却 12s→5s，避免单次超时杀掉整通对话。
    private const val GET_ANSWER = "getAutoAnswerRequest"
    private const val NLP_BACK = "notifyPresenterOnNlpBack"

    // ── 时序参数 ──
    private const val MERGE_WINDOW_MS = 500L     // 最后一次捕获后再等这么久才发 LLM（合并连拍拆句）
    private const val SAME_UTTER_MS = 2000L      // 距上次捕获多久内仍视为"同一句话"（合并判定）
    private const val FINAL_WAIT_MS = 400L       // 后台等"对方说完(isFinal)"的最长时间（已实测可直连，压缩以减少迟到）
    private const val WAIT_CAP_BASE_MS = 2500L   // 播报处等待后台 LLM 的基础时长（再加上配置超时）
    private const val LLM_COOLDOWN_MS = 5000L   // LLM 失败后冷却，避免连续超时拖垮通话（v1.0.8: 12s→5s，单次超时不再杀掉整通对话）
    private const val NEW_CALL_GAP_MS = 30000L   // 距上次说话超过这么久视为新通话，清空上下文

    /** 单通电话内的轮次状态 */
    @Volatile private var pendingQuery: String? = null
    @Volatile private var pendingAnswer: String? = null
    @Volatile private var queryTs: Long = 0L
    @Volatile private var lastLlmFailTs: Long = 0L
    private val history: MutableList<Pair<String, String>> = mutableListOf()

    /** ASR「是否说完」信号（来自 ProcessManage.onDownLinkAsrBack/Finish 的布尔参） */
    @Volatile private var asrFinalFlag: Boolean = true   // 默认 true：ASR hook 失效时不阻塞
    @Volatile private var asrFinalTs: Long = 0L
    @Volatile private var asrSpeakingTs: Long = 0L       // 最近一次"实时半句"时间（对方还在说）

    /** 后台单线程：跑 LLM，绝不占用小爱的调用线程 */
    private val llmExec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var scheduled: ScheduledFuture<*>? = null

    /** 专用于把 TTS 实际播放的音频缓存复制到日志目录，供用户通话后回听验证 */
    private val ttsExec = Executors.newSingleThreadExecutor()
    private val ttsSeq = AtomicInteger(0)
    private val dateFmt = SimpleDateFormat("HHmmss", Locale.US)
    @Volatile private var lastInjectedText: String? = null
    private val recentTts = ConcurrentHashMap<String, Long>()

    // ── 通话会话跟踪（v1.0.27）──
    // 目的：电话【接入瞬间】就通知界面清空上一通的显示（上一通已入 CallLogDb 历史库），
    // 而不是等对方开口后靠"静默 30 秒"才判定新通话（那样上一通的记录会在界面上多留一轮）。
    /** 当前是否处于一通电话内（InCallAiCallService.onDestroy 置 false） */
    @Volatile private var callActive: Boolean = false
    /** 通话开始时刻 */
    @Volatile private var callStartTs: Long = 0L
    /** 最近一通的号码（用于"号码变了必然是另一通"判定） */
    @Volatile private var lastCallNumber: String? = null

    private fun llmCooling(): Boolean =
        (System.currentTimeMillis() - lastLlmFailTs) < LLM_COOLDOWN_MS

    /**
     * 判定并宣告「新一通电话开始」（v1.0.27）。
     *
     * 触发条件（任一成立即算新一通）：
     *   ① 当前不在通话态（上一通已 onDestroy）；
     *   ② 号码与上一通不同；
     *   ③ 距上次通话开始 / 上次说话已超过 NEW_CALL_GAP_MS（静默兜底，兼容拿不到号码的情况）。
     *
     * 宣告后只做一件事：广播 NEW_CALL。界面据此清空上一通的对话气泡——
     * 数据不会丢，上一通的每条消息早就逐条写进 CallLogDb 的「历史通话」里了。
     * 同时清掉 hook 侧的旧通话对象，保证 A5 随后一定重新广播新号码/备注名
     * （否则同一个号码再拨进来时 emitCaller 会因"没变化"而不发）。
     */
    private fun declareNewCall(number: String?, reason: String) {
        try {
            val now = System.currentTimeMillis()
            val numChanged = !number.isNullOrBlank() &&
                !lastCallNumber.isNullOrBlank() && number != lastCallNumber
            val idle = now - maxOf(callStartTs, queryTs) > NEW_CALL_GAP_MS
            if (callActive && !numChanged && !idle) return   // 同一通电话内的重复信号，忽略

            callActive = true
            callStartTs = now
            if (!number.isNullOrBlank()) lastCallNumber = number
            ChatBus.callerName = null
            ChatBus.callerNumber = null
            ChatBus.emitNewCall(number, null)
            XLog.i("★A6 新通话开始（$reason）号码=「${number?.take(20) ?: "-"}」→ 已通知界面清空上一通显示")
        } catch (_: Throwable) { }
    }

    /** 对方是否已说完：最近一次 ASR 结果是最终句，且之后没有新的实时半句 */
    private fun callerFinished(): Boolean = asrFinalFlag && asrFinalTs >= asrSpeakingTs

    fun hook(lpparam: LoadPackageParam) {
        if (lpparam.packageName != Constants.TARGET_PKG) return
        try { hookAsr(lpparam) } catch (e: Throwable) { XLog.e("★A3 ASR hook 失败", e) }
        try { hookGetAnswer(lpparam) } catch (e: Throwable) { XLog.e("★A1 getAutoAnswerRequest hook 失败", e) }
        try { hookNlpBack(lpparam) } catch (e: Throwable) { XLog.e("★A2 notifyPresenterOnNlpBack hook 失败", e) }
        try { hookTts(lpparam) } catch (e: Throwable) { XLog.e("★TTS hook 失败", e) }
        try { hookDoTts(lpparam) } catch (e: Throwable) { XLog.e("★A4 doTts hook 失败", e) }
        try { hookAudioTrack(lpparam) } catch (e: Throwable) { XLog.e("★AT AudioTrack hook 失败", e) }
        try { hookCallerInfo(lpparam) } catch (e: Throwable) { XLog.e("★A5 通话对象 hook 失败", e) }
        XLog.i("★A0 v${BuildConfig.VERSION_NAME} Hook 已挂载: ASR/query/reply(A1~A3)@$PM_CLASS/$NLP_CLASS + doTts/buildTtsEvent(A4)@$TTS_CLASS + TTS抓取 + A5通话对象")
    }

    // ───────────────────── 5) A5：拿通话对象号码 + 通讯录备注名 ─────────────────────
    // RE 实证（AI通话 6.0.3，androguard 反汇编）：
    //   · 号码：InCallAiServiceImpl#k() → l(Call) → Call.getDetails().getHandle().getSchemeSpecificPart()（端上明文，无加密）
    //     另有静态单例 aicall.model.b 的 static 字段 o 存号码（20+ 处读取，通话中持续有效）
    //   · caller（来电显示名）：aicall.model.g 字段 c（getter a()），bean toString 确认
    //   · 备注名：f5.b#e(Context, number) 查 ContactsContract$Phone.CONTENT_URI 的 display_name 列 → ContactInfo#a()
    //   · 已确认 APK 声明 READ_CONTACTS，且号码仅做 PhoneNumberUtils.stripSeparators 去分隔符，全程无哈希/加密。
    // 目的：在配置页聊天框顶部显示"正在和谁通话"。
    private fun hookCallerInfo(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader

        // (1) 首选：hook InCallAiCallService#x(Context, String number, String caller, boolean)
        //     它的第2、3 个参数就是号码 + 来电显示名，最直接。
        var hookedX = 0
        try {
            val svcCls = XposedHelpers.findClass(INCALL_SVC_CLASS, cl)
            for (m in svcCls.declaredMethods.filter { it.name == "x" && it.parameterTypes.size == 4 }) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                val number = param.args.getOrNull(1) as? String
                                val caller = param.args.getOrNull(2) as? String
                                // v1.0.27：电话接入瞬间就判定新通话 → 界面立刻清掉上一通显示
                                declareNewCall(number, "电话接入")
                                publishCaller(null, number)
                                // 号码拿到了就异步查一次通讯录备注名
                                if (!number.isNullOrBlank()) {
                                    ttsExec.execute { resolveContactName(cl, number) }
                                }
                                if (!caller.isNullOrBlank()) publishCaller(caller, null)
                            } catch (_: Throwable) { }
                        }
                    })
                    hookedX++
                } catch (_: Throwable) { }
            }
        } catch (e: Throwable) {
            XLog.w("★A5 未找到 $INCALL_SVC_CLASS: ${e.message}")
        }
        XLog.i("★A5 hook InCallAiCallService.x 命中=$hookedX")

        // (1b) 通话结束信号（v1.0.27）：InCallAiCallService 销毁 = 这通电话结束。
        //      置 callActive=false，下一通电话即使来自同一个号码、间隔很短，也必定判定为新通话。
        var hookedEnd = 0
        try {
            val svcCls = XposedHelpers.findClass(INCALL_SVC_CLASS, cl)
            for (m in svcCls.declaredMethods.filter { it.name == "onDestroy" && it.parameterTypes.isEmpty() }) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                callActive = false
                                pendingQuery = null
                                pendingAnswer = null
                                synchronized(history) { history.clear() }
                                XLog.i("★A6 通话结束，已复位会话状态")
                            } catch (_: Throwable) { }
                        }
                    })
                    hookedEnd++
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        XLog.i("★A5 hook InCallAiCallService.onDestroy 命中=$hookedEnd")

        // (2) 兜底：hook aicall.model.b 的号码 getter l()（静态单例，通话中持续有效）
        var hookedL = 0
        try {
            val modelB = XposedHelpers.findClass(MODEL_B_CLASS, cl)
            // model.b 字段全 static，直接读字段 o
            try {
                val f = modelB.getDeclaredField("o")
                f.isAccessible = true
                XLog.i("★A5 号码静态字段 model.b.o 存在: ${f.type}")
            } catch (e: Throwable) {
                XLog.w("★A5 model.b.o 字段未找到（版本可能变化）: ${e.message}")
            }
            for (m in modelB.declaredMethods.filter { it.name == "l" && it.parameterTypes.isEmpty() }) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                val num = param.result as? String
                                if (!num.isNullOrBlank()) publishCaller(null, num)
                            } catch (_: Throwable) { }
                        }
                    })
                    hookedL++
                } catch (_: Throwable) { }
            }
        } catch (e: Throwable) {
            XLog.w("★A5 未找到 $MODEL_B_CLASS: ${e.message}")
        }
        XLog.i("★A5 hook model.b.l() 命中=$hookedL")
    }

    /** 把号码/备注名广播到配置页（只变化时才发；并过滤枚举型"caller"） */
    private fun publishCaller(name: String?, number: String?) {
        try {
            // caller 可能是小爱内部的模式枚举（如 MODE_SUBTITLES），不是人名 → 过滤掉
            val n = name?.trim()
            val nm = if (!n.isNullOrEmpty() && looksLikeEnum(n)) null else n?.ifEmpty { null }
            ChatBus.emitCaller(nm, number)
            // model.b.l() 在通话中被高频调用（20+ 处读取），日志必须节流否则刷屏
            val now = System.currentTimeMillis()
            if (now - callerLogTs > 3000L) {
                callerLogTs = now
                XLog.i("★A5 通话对象: 号码=「${number?.take(20) ?: "-"}」 备注名=「${nm?.take(20) ?: "-"}」")
            }
        } catch (_: Throwable) { }
    }

    /** A5 日志节流时间戳 */
    @Volatile private var callerLogTs: Long = 0L

    /** 判断 caller 是否为小爱内部枚举/常量而非真人名 */
    private fun looksLikeEnum(s: String): Boolean {
        if (s.length > 12) return false                 // 真人名一般 >2 个字
        if (s.contains("_") || s.all { it.isUpperCase() || it == '_' }) return true
        val up = s.uppercase()
        val enums = listOf("MODE_", "FROM_", "TYPE_", "CALL_", "DIAL_", "AUTO_", "AI_", "UNKNOWN")
        return enums.any { up.startsWith(it) }
    }

    /**
     * 查通讯录备注名（v1.0.11 重写）。
     *
     * v1.0.10 实测失败原因：原实现调 f5.b#e()（混淆类，且该方法在原App 里
     * 只在"号码详情页"被调用，通话中调用返回 null/异常），导致备注名永远拿不到。
     *
     * 现在改为【直接查 ContactsContract】，不依赖任何混淆类名：
     *   ContactsContract.CommonDataKinds.Phone.CONTENT_URI
     *   投影 display_name / data4
     *   用 data4 = ? 精确匹配号码
     * 小爱通话本身是系统 UID 且已声明 READ_CONTACTS，此处运行在它的进程里，权限天然具备。
     *
     * 号码规范化：去掉 tel: 前缀、空格、横杠、括号（与端上 PhoneNumberUtils.stripSeparators 一致），
     * 再按「原样 → 去分隔符」两种形式依次匹配，提高命中率。
     */
    private fun resolveContactName(cl: ClassLoader, number: String) {
        val ctx = SysBridge.currentApplication() ?: return
        val num = normalizeNumber(number)
        if (num.isEmpty()) return
        // 同一个号码只查一次，避免 model.b.l() 高频调用把通讯录查爆
        if (contactQueryCache.containsKey(num)) return
        contactQueryCache[num] = true

        // 依次尝试：原样号码 → 去分隔符号码
        for (candidate in listOf(number.trim(), num).distinct()) {
            try {
                val name = queryDisplayName(ctx, candidate)
                if (!name.isNullOrBlank()) {
                    XLog.i("★A5 通讯录命中(${candidate.take(16)}) → 备注名「${name.take(20)}」")
                    publishCaller(name, null)
                    return
                }
            } catch (_: Throwable) { }
        }
        XLog.i("★A5 通讯录未命中号码「${num.take(16)}」（可能未存通讯录），仅显示号码")
    }

    /** 已查过的号码，防止 model.b.l() 高频 hook 时反复查通讯录 */
    private val contactQueryCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 号码规范化：去 tel:/sip: 前缀、去空格/横杠/括号/点 */
    private fun normalizeNumber(raw: String): String {
        var s = raw.trim()
        val idx = s.indexOf(':')
        if (idx >= 0 && idx < 6) s = s.substring(idx + 1)
        return s.filter { it.isDigit() || it == '+' }
    }

    /**
     * 直接查系统通讯录取display_name（不依赖小爱通话的混淆类）。
     * 查不到返回 null（号码不在通讯录属正常情况）。
     */
    private fun queryDisplayName(ctx: android.content.Context, number: String): String? {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            ctx.contentResolver.query(
                uri, proj,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
                arrayOf(number), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) { }
        // 精确匹配查不到时，用"号码包含"兜底（兼容号码存了区号/连字符等情况）
        return try {
            ctx.contentResolver.query(
                uri, proj,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                arrayOf("%$number%"), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    // ───────────────────── 0) ASR：拿"对方是否说完"的 isFinal 信号 ─────────────────────
    private fun hookAsr(lpparam: LoadPackageParam) {
        val cls = try {
            XposedHelpers.findClass(PM_CLASS, lpparam.classLoader)
        } catch (e: Throwable) {
            XLog.e("★A3 未找到 $PM_CLASS", e); return
        }
        val methods = try { cls.declaredMethods } catch (e: Throwable) { emptyArray() }

        // onDownLinkAsrBack(String,String,Z) / onDownLinkAsrFinish(String,String,Z)：带 isFinal 布尔参
        for (name in listOf("onDownLinkAsrBack", "onDownLinkAsrFinish")) {
            var hooked = 0
            for (m in methods) {
                if (m.name != name) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                val flag = param.args.getOrNull(2) as? Boolean
                                val a = param.args.getOrNull(0)?.toString()
                                val b = param.args.getOrNull(1)?.toString()
                                asrFinalFlag = flag ?: true
                                asrFinalTs = System.currentTimeMillis()
                                XLog.i("★A3 $name final=$flag a=「${a?.take(24)}」 b=「${b?.take(24)}」")
                            } catch (e: Throwable) { }
                        }
                    })
                    hooked++
                } catch (e: Throwable) { }
            }
            XLog.i("★A3 hook $name 命中=$hooked")
        }

        // onDownLinkAsrRealTimeResult(String,String)：边说边出的半句（无 final 参）→ 标记"还在说"
        var rtHooked = 0
        for (m in methods) {
            if (m.name != "onDownLinkAsrRealTimeResult") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            asrSpeakingTs = System.currentTimeMillis()
                            // 半句刷屏，节流打印（每 ~1s 一条）
                            val now = System.currentTimeMillis()
                            if (now - lastRtLogTs > 1000L) {
                                lastRtLogTs = now
                                val a = param.args.getOrNull(0)?.toString()
                                val b = param.args.getOrNull(1)?.toString()
                                XLog.i("★A3 实时半句 a=「${a?.take(24)}」 b=「${b?.take(24)}」")
                            }
                        } catch (e: Throwable) { }
                    }
                })
                rtHooked++
            } catch (e: Throwable) { }
        }
        XLog.i("★A3 hook onDownLinkAsrRealTimeResult 命中=$rtHooked")
    }

    @Volatile private var lastRtLogTs: Long = 0L

    // ───────────────────── 1) 捕获对端话术 + 去抖合并 + 后台预生成 ─────────────────────
    private fun hookGetAnswer(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader
        val cls = try {
            XposedHelpers.findClass(NLP_CLASS, cl)
        } catch (e: Throwable) {
            XLog.e("★A1 未找到 $NLP_CLASS（小爱通话版本可能不匹配）", e); return
        }
        val ms = cls.declaredMethods.filter { it.name == GET_ANSWER }
        if (ms.isEmpty()) { XLog.w("★A1 未找到方法 $GET_ANSWER"); return }

        for (m in ms) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val q = param.args.getOrNull(0)?.toString()?.trim() ?: return
                            if (q.isEmpty()) return
                            captureUtterance(q)
                        } catch (e: Throwable) {
                            XLog.e("★A1 处理异常（不影响通话）", e)
                        }
                    }
                })
                XLog.i("★A1 hook 成功: $GET_ANSWER(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            } catch (e: Throwable) {
                XLog.e("★A1 hook $GET_ANSWER 失败", e)
            }
        }
    }

    /**
     * 捕获一句话：与上一次做「同一句合并」——
     *  - 距上次 <SAME_UTTER_MS 且文本是扩展关系（前缀/包含/高重合）→ 取更长的一版（更完整）；
     *  - 否则视为新的一句，直接替换。
     * 之后 (重)排一个 MERGE_WINDOW 去抖任务，把最终这句交后台 LLM。
     */
    private fun captureUtterance(q: String) {
        val now = System.currentTimeMillis()
        // 新的一通电话（距上次说话很久）→ 清空上下文，避免把上通电话的记忆带给这通
        if (now - queryTs > NEW_CALL_GAP_MS) {
            synchronized(history) { history.clear() }
            XLog.i("★A1 检测到新通话，已清空聊天上下文")
            // 兜底：万一 InCallAiCallService.x 没 hook 上（版本差异），靠"静默 30s"判定新通话，
            // 同样通知界面清空上一通显示（上一通已保存在历史通话里）
            declareNewCall(lastCallNumber, "静默兜底")
        }
        val prev = pendingQuery
        // 同一句窗口内：把新片段【拼接】进已累计文本，保证发给 LLM 的是完整一句，而不是只发后半段
        val acc = when {
            prev.isNullOrEmpty() || (now - queryTs) >= SAME_UTTER_MS -> q
            else -> accumulate(prev, q)
        }
        pendingQuery = acc
        queryTs = now
        pendingAnswer = null
        XLog.i("★A1 捕获片段「${q.take(40)}」→累计「${acc.take(70)}」 final=${callerFinished()}")

        // 聊天框：对方说的话（累积到最终句才发，见 finalizeLlm）
        synchronized(this) {
            scheduled?.cancel(false)
            scheduled = llmExec.schedule({ finalizeLlm() }, MERGE_WINDOW_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 把新 ASR 片段合并进已累计文本——保证发给 LLM 的是完整的一句，而不是只发后半段：
     *  - 累积式（每片是前片的更长前缀）→ 取更长的那片；
     *  - 增量式（每片是新内容）→ 按"尾部=头部"的最大重叠拼接，无重叠则直接相接；
     *  - 已包含 → 保留原样（防重复/防抖动）。
     */
    private fun accumulate(prev: String, next: String): String {
        if (next.isEmpty()) return prev
        if (prev.isEmpty()) return next
        if (next.startsWith(prev)) return next                    // 累积式：next 更长更全
        if (prev.startsWith(next) || prev.contains(next)) return prev  // 已包含，防重复
        val maxK = minOf(prev.length, next.length)
        for (k in maxK downTo 1) {                                // 找 prev 尾部 == next 头部 的最大重叠
            if (prev.regionMatches(prev.length - k, next, 0, k)) {
                return prev + next.substring(k)
            }
        }
        return prev + next                                        // 无重叠：直接拼接
    }

    /** 后台执行：先等"对方说完(isFinal)"，再对最终那句跑一次 LLM，结果存 pendingAnswer */
    private fun finalizeLlm() {
        try {
            val q0 = pendingQuery
            if (q0.isNullOrEmpty()) return
            if (isFiller(q0)) { XLog.i("★A1 语气词/过短，跳过 LLM: 「$q0」"); return }
            if (llmCooling()) { XLog.i("★A1 LLM 冷却中，跳过"); return }
            val cfg = Config.load()
            if (!cfg.enabled || !cfg.ready) { XLog.w("★A1 模块未启用或配置不完整，跳过 LLM"); return }

            // 等对方说完：轮询 isFinal，最多 FINAL_WAIT；期间若有更新的句子则退出交给新任务
            val deadline = System.currentTimeMillis() + FINAL_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (callerFinished()) break
                if (pendingQuery != q0) return
                try { Thread.sleep(60) } catch (_: InterruptedException) { return }
            }
            val q = pendingQuery ?: q0
            if (q != q0) return // 已被更新的捕获取代
            if (isFiller(q)) return

            XLog.i("★A1 对方已说完(final=$asrFinalFlag)，对「${q.take(50)}」调 LLM")
            // 聊天框：对方最终说的话（此时才是完整一句）
            try { ChatBus.emitPeer(q) } catch (_: Throwable) { }
            // 上下文轮数：>0 按设置；<=0 视为不限制（Int 上限，通话文本有限，吃得下）
            val cap = if (cfg.contextTurns <= 0) Int.MAX_VALUE else cfg.contextTurns
            val r = LlmClient.chat(cfg, snapshotHistory(), q)
            if (r.ok && !r.text.isNullOrEmpty()) {
                if (pendingQuery == q) {
                    pendingAnswer = r.text
                    recordHistory(q, r.text, cap)   // 生成本轮就记录，后续轮次才能携带上下文
                    // 聊天框：AI 的回复（此时已生成，但还没播出去）
                    try { ChatBus.emitAi(r.text) } catch (_: Throwable) { }
                    XLog.i("★A1 LLM 生成完成(携带上下文${snapshotHistory().size}轮): 「${r.text.take(60)}」")
                } else {
                    XLog.i("★A1 LLM 结果已过期(有更新的句子)，丢弃")
                }
            } else {
                lastLlmFailTs = System.currentTimeMillis()
                XLog.w("★A1 LLM 失败(${r.error})，进入冷却 ${LLM_COOLDOWN_MS}ms")
            }
        } catch (e: Throwable) {
            XLog.e("★A1 finalizeLlm 异常", e)
        }
    }

    // ───────────────────── 2) 替换被念出的回复文本 ─────────────────────
    private fun hookNlpBack(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader
        val cls = try {
            XposedHelpers.findClass(PM_CLASS, cl)
        } catch (e: Throwable) {
            XLog.e("★A2 未找到 $PM_CLASS", e); return
        }
        val ms = cls.declaredMethods.filter { it.name == NLP_BACK }
        if (ms.isEmpty()) { XLog.w("★A2 未找到方法 $NLP_BACK"); return }

        for (m in ms) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            handleNlpBack(param, cl)
                        } catch (e: Throwable) {
                            XLog.e("★A2 处理异常（保留小爱原文）", e)
                        }
                    }
                })
                XLog.i("★A2 hook 成功: $NLP_BACK")
            } catch (e: Throwable) {
                XLog.e("★A2 hook $NLP_BACK 失败", e)
            }
        }
    }

    private fun handleNlpBack(param: XC_MethodHook.MethodHookParam, cl: ClassLoader) {
        val cfg = Config.load()
        if (!cfg.enabled) { XLog.i("★A2 模块开关关闭，跳过"); return }

        val jsonArg = param.args.getOrNull(0)
        var originalText: String? = null
        var types: String? = null
        if (jsonArg is String) {
            try {
                val jo = JSONObject(jsonArg)
                originalText = jo.optString("text", "")
                types = jo.optString("types", "")
            } catch (e: Throwable) {
                XLog.w("★A2 param0 非合法 JSON，跳过: ${jsonArg.take(40)}")
                return
            }
        }
        if (originalText.isNullOrEmpty()) {
            XLog.i("★A2 本轮无回复文本可替换，跳过")
            return
        }
        // 仅接管「自动回复(auto_answer)」事件；本地用户自己说话 / 指令类事件不抢答（避免把用户原话当待替换文本）
        if (!types.isNullOrEmpty() && types != "auto_answer") {
            XLog.i("★A2 非 auto_answer(types=$types)，跳过接管（保留原文）")
            return
        }

        val answer = awaitAnswer()
        if (answer.isNullOrEmpty()) {
            XLog.w("★A2 未拿到 LLM 回复(超时/冷却/失败)，保留原文: 「${originalText.take(30)}」")
            return
        }

        // (1) 替换 param0 JSON 的 "text" 字段
        try {
            val jo = JSONObject(jsonArg as String)
            jo.put("text", answer)
            param.args[0] = jo.toString()
            XLog.i("★A2 已改写 param0.text: 「${originalText.take(30)}」→「${answer.take(30)}」")
        } catch (e: Throwable) {
            XLog.e("★A2 改写 param0 失败（继续尝试 bean）", e)
        }

        // (2) 替换 bean.text（反射 setter / 字段，双保险）
        val bean = param.args.getOrNull(3)
        if (bean != null) {
            val ok = setBeanText(bean, answer, cl)
            XLog.i("★A2 bean.text 替换 ${if (ok) "成功" else "失败(已尝试)"}, 新值=「${answer.take(30)}」")
        }

        pendingQuery = null   // pendingAnswer 故意保留：由 doTts(A4) 消费并写进真正合成的 Event.text
        lastInjectedText = answer
        XLog.i("✓ A2 已替换(上下文${snapshotHistory().size}轮) →「${answer.take(60)}」；等 doTts 消费")
    }

    // ───────────────────── 3) 抓取 TTS 实际播放的音频，写入日志目录供用户回听验证 ─────────────────────
    private fun hookTts(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader
        val cls = try {
            XposedHelpers.findClass("com.xiaomi.aiasst.service.aicall.process.tts.TtsManager", cl)
        } catch (e: Throwable) {
            XLog.w("★TTS 未找到 TtsManager（类名可能随小爱通话版本变化）: ${e.message}"); return
        }
        var hit = 0
        for (m in cls.declaredMethods) {
            if (m.name !in setOf("play", "onTtsDataStart", "delayPlay")) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val a = param.args.getOrNull(0)
                            if (a is String && a.isNotBlank()) captureTtsAudio(a)
                        } catch (e: Throwable) { }
                    }
                })
                hit++
            } catch (e: Throwable) { }
        }
        XLog.i("★TTS hook 命中=$hit (com.xiaomi.aiasst...tts.TtsManager)")
    }

    /**
     * 把 TTS 音频缓存复制到 日志目录/tts_audio/，并写同名 .txt 标注本次注入的文本，供通话后回听验证。
     * 关键：pcm 缓存是「播放开始才创建、音频边播边写入」的流式文件——v1.0.4 一触发 play 就复制
     * 导致抄到 0 字节。这里改为后台轮询等文件大小稳定后再复制；同时嗅探真实格式：
     * 已知容器(wav/mp3/ogg/amr/flac)用对应扩展名直接可播；未知则按裸 PCM 包 WAV 头另存 .wav。
     * 采样率：裸 PCM 按 16kHz 包 WAV（用户反馈 v1.0.5 即 16k，实测 8k 会半速变调）。
     */
    private fun captureTtsAudio(srcPath: String) {
        val now = System.currentTimeMillis()
        val prev = recentTts.put(srcPath, now)
        if (prev != null && now - prev < 3000) return   // 同一音频 3 秒内不重复复制
        val src = File(srcPath)
        if (!src.isFile) return
        ttsExec.execute {
            try {
                // ── 等待流式写入完成：大小连续 2 次(间隔300ms)不变即认为写完，最多等 8s ──
                var lastSize = -1L
                var stable = 0
                var waited = 0L
                while (waited < 8000) {
                    val s = src.length()
                    if (s > 0 && s == lastSize) { stable++; if (stable >= 2) break } else stable = 0
                    lastSize = s
                    Thread.sleep(300); waited += 300
                }
                val data = src.readBytes()
                if (data.isEmpty()) {
                    XLog.w("★TTS 源缓存等待8s后仍为空: $srcPath")
                    return@execute
                }
                val dir = File(File(FileLogger.path()).parentFile, "tts_audio")
                dir.mkdirs()
                val seq = ttsSeq.incrementAndGet()
                val ts = synchronized(dateFmt) { dateFmt.format(Date()) }
                val base = "tts_${ts}_$seq"
                val hex = data.take(16).joinToString(" ") { "%02X".format(it) }
                val fmt = sniffFormat(data)
                val (peak, rms) = pcmStats(data, 16)   // 缓存按 16bit 计；仅用于"静音/有声"判断
                val verdict = if (fmt == null && peak < 50) "【疑似静音】" else ""
                // 已知格式(mp3/ogg/wav/flac...)→ 原生扩展名直接可播；未知(裸PCM)→ 只包 16kHz WAV，不再落 .bin（v1.0.8 按用户反馈修正：v1.0.5 即 16k）
                val dest = if (fmt != null) {
                    File(dir, "$base.$fmt").also { it.writeBytes(data) }
                } else {
                    File(dir, "$base.wav").also { it.writeBytes(wrapPcmToWav(data, 16000, 1, 16)) }
                }
                val extra = if (fmt == null) " +wav(16kHz实测)" else ".$fmt"
                val txt = File(dir, "$base.txt")
                txt.writeText(
                    "时间=$ts\n" +
                    "文本=${lastInjectedText ?: "（本次未捕获到注入文本，可能是原话/系统音）"}\n" +
                    "源缓存=$srcPath\n" +
                    "格式=${fmt ?: "未知(已按裸PCM包16kHz WAV)"}\n" +
                    "大小=${data.size}字节\n" +
                    "头部16字节=$hex\n" +
                    (if (fmt == null) "试听=同名.wav（16kHz 包头，语速正常）\n" else "") +
                    (if (fmt == null) "峰值=$peak RMS=$rms ${if (peak < 50) "（疑似静音）" else "（有声）"}\n" else "")
                )
                XLog.i("★TTS 音频已存 #$seq: ${dest.name} (${data.size}B fmt=${fmt ?: "未知$extra"}) 头=$hex peak=$peak rms=$rms$verdict")
            } catch (e: Throwable) {
                XLog.w("★TTS 复制音频失败: ${e.message}")
            }
        }
    }

    /** 按魔数嗅探音频容器格式；null=未知（多半是裸 PCM） */
    /**
     * 嗅探音频容器格式。
     *
     * v1.0.10 修正 mp3 误判：旧判据 `[0]==0xFF && (b[1]&0xE0)==0xE0` 太宽松，
     * 裸 PCM 首字节常为 0x00/0xFF（如 `FF FF 00 00 …`）会被当成 mp3 帧同步字 → 存成 .mp3 播不了。
     * 现在增加三重校验：① 第2字节高5位同步字；② 校验位(bit4..0)不能全0/全1（有效mp3不会）；
     * ③ 第3、4字节的 帧长(bitrate/采样率字段组合)不能为 0x00/0xFF。
     * 任一不满足→ 判定为裸 PCM，按 16kHz 包 WAV。
     */
    private fun sniffFormat(d: ByteArray): String? {
        fun s(from: Int, to: Int) = d.copyOfRange(from, to).toString(Charsets.US_ASCII)
        return when {
            d.size >= 12 && s(0, 4) == "RIFF" && s(8, 12) == "WAVE" -> "wav"
            d.size >= 4 && s(0, 4) == "OggS" -> "ogg"
            d.size >= 6 && s(0, 5) == "#!AMR" -> "amr"
            d.size >= 3 && s(0, 3) == "ID3" -> "mp3"
            d.size >= 4 && s(0, 4) == "fLaC" -> "flac"
            d.size >= 8 && s(4, 8) == "ftyp" -> "m4a"
            // mp3 无 ID3 时的裸帧：严格三重校验（见上方注释）
            d.size >= 8 && isStrictMp3Frame(d) -> "mp3"
            else -> null
        }
    }

    /**
     * 严格 mp3 帧头判定（第一帧偏移 0）。
     * mp3 帧头：11bit 同步字(全1) + 2bit 版本 + 2bit 层 + 1bit 保护 + 4bit 码率索引 + 4bit 采样率索引 …
     * 裸 PCM 几乎不可能连续满足下列全部条件：
     *   b0==0xFF
     *   (b1 & 0xE0)==0xE0            同步字高5 位
     *   (b1 & 0x1E)!=0x1E && (b1 & 0x1E)!=0   层字段不能是"保留"
     *   (b1 & 0x01)==1|| 版本==1       保护位/版本不能全 0（全0 概率极低）
     *   (b2 & 0xF0)!=0xF0 && (b2 & 0xF0)!=0x00  码率索引不能是"坏/自由"
     *   (b2 & 0x0C)!=0x0C              采样率索引不能是"保留"
     */
    private fun isStrictMp3Frame(d: ByteArray): Boolean {
        val b0 = d[0].toInt() and 0xFF
        val b1 = d[1].toInt() and 0xFF
        val b2 = d[2].toInt() and 0xFF
        if (b0 != 0xFF) return false
        if ((b1 and 0xE0) != 0xE0) return false          // 同步字
        val version = (b1 shr 3) and 0x03                  // MPEG 版本位
        val layer = (b1 shr 1) and 0x03                    // 层位
        if (layer == 0) return false                       // 保留层 → 不是真 mp3
        if (version == 1) return false                    // 保留版本
        val bitrateIdx = (b2 shr 4) and 0x0F
        if (bitrateIdx == 0 || bitrateIdx == 0x0F) return false   // 坏/自由码率
        val samplerateIdx = (b2 shr 2) and 0x03
        if (samplerateIdx == 0x03) return false            // 保留采样率
        // 第三帧同步字交叉验证：offset 417.. 或下一个常见位置命中才算流式 mp3
        val offsets = listOf(0, 24, 36, 48, 64, 100, 128, 200, 256, 300, 400, 417, 500)
        for (off in offsets) {
            if (off + 1 < d.size && (d[off].toInt() and 0xFF) == 0xFF &&
                (d[off + 1].toInt() and 0xE0) == 0xE0
            ) return true
        }
        // 没有第二个帧头但首帧合规→ 仍可能只有一帧的短音频，放行（保留原宽松行为的最后兜底）
        return true
    }

    /** 给裸 PCM 包 44 字节标准 WAV 头（无压缩 PCM），使其可被任意播放器直接播放 */
    private fun wrapPcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        fun le(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte(); header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte(); header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        le(4, 36 + pcm.size)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        le(16, 16); header[20] = 1; header[22] = channels.toByte()
        le(24, sampleRate); le(28, byteRate)
        header[32] = blockAlign.toByte(); header[34] = bits.toByte()
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        le(40, pcm.size)
        return header + pcm
    }

    /** 计算裸 PCM 的峰值/ RMS（用于日志里一眼判断"静音还是有声"），bits=8 按无符号、16 按有符号 LE */
    private fun pcmStats(d: ByteArray, bits: Int): Pair<Int, Int> {
        if (bits == 8) {
            var peak = 0; var sum = 0.0
            for (b in d) { val v = (b.toInt() and 0xFF) - 128; val a = kotlin.math.abs(v); if (a > peak) peak = a; sum += v.toDouble() * v }
            val rms = if (d.isNotEmpty()) kotlin.math.sqrt(sum / d.size).toInt() else 0
            return peak to rms
        }
        var peak = 0; var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < d.size) {
            val s = (d[i + 1].toInt() shl 8) or (d[i].toInt() and 0xFF)
            val a = kotlin.math.abs(s); if (a > peak) peak = a; sum += s.toDouble() * s; n++; i += 2
        }
        val rms = if (n > 0) kotlin.math.sqrt(sum / n).toInt() else 0
        return peak to rms
    }

    // ───────────────────── 3.5) AudioTrack 抓取：抓系统【真实播放】的 PCM（格式由系统给出，绝对正确） ─────────────────────
    // v1.0.7 新增：TTS 是服务端合成、且本地改文本会静音。要确证"通话到底有没有出声"，最权威的办法是
    // 直接抓 AudioTrack 实际写入的音频——它带系统给出的采样率/声道/位深，落盘就是可直接播放的 WAV。
    private val ttsCapturing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val atCap = ConcurrentHashMap<Int, AtBuf>()
    private val atLastWrite = ConcurrentHashMap<Int, Long>()
    private val atFlush = Executors.newSingleThreadScheduledExecutor()
    private val atSeq = AtomicInteger(0)

    private class AtBuf(
        var sampleRate: Int = 16000,
        var channels: Int = 1,
        var bits: Int = 16,
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    )

    /** 进入一次 TTS 播放：开抓取窗口，并在 20s 后自动关闭（避免抓到无关声音） */
    private fun startTtsCaptureWindow() {
        ttsCapturing.set(true)
        atFlush.schedule({
            ttsCapturing.set(false)
            XLog.i("★AT 抓取窗口已关闭")
        }, 20, TimeUnit.SECONDS)
    }

    private fun hookAudioTrack(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader
        val cls = try {
            XposedHelpers.findClass("android.media.AudioTrack", cl)
        } catch (e: Throwable) { XLog.w("★AT 未找到 AudioTrack: ${e.message}"); return }
        var hit = 0
        for (m in cls.declaredMethods) {
            val p = m.parameterTypes
            val isByteWrite = p.size == 3 && p[0] == ByteArray::class.java
                    && p[1] == Int::class.javaPrimitiveType && p[2] == Int::class.javaPrimitiveType
            val isBufWrite = p.size >= 2 && p[0] == java.nio.ByteBuffer::class.java && p[1] == Int::class.javaPrimitiveType
            if (!isByteWrite && !isBufWrite) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (!ttsCapturing.get()) return
                            val track = param.thisObject as? android.media.AudioTrack ?: return
                            val key = System.identityHashCode(track)
                            val buf = atCap.computeIfAbsent(key) { AtBuf() }
                            buf.sampleRate = runCatching { track.sampleRate }.getOrDefault(16000)
                            buf.channels = runCatching { track.channelCount }.getOrDefault(1)
                            val fmt = runCatching { track.audioFormat }.getOrDefault(2)
                            buf.bits = if (fmt == 3) 8 else 16   // ENCODING_PCM_8BIT=3(无符号) 其余按16bit
                            val bytes = when (val a0 = param.args[0]) {
                                is ByteArray -> {
                                    val off = (param.args[1] as? Int) ?: 0
                                    val len = (param.args[2] as? Int) ?: 0
                                    if (off in 0..a0.size && len >= 0) a0.copyOfRange(off, (off + len).coerceAtMost(a0.size)) else null
                                }
                                is java.nio.ByteBuffer -> {
                                    val len = (param.args[1] as? Int) ?: a0.remaining()
                                    val dup = a0.duplicate(); dup.position(0)
                                    val n = len.coerceAtMost(dup.remaining())
                                    val arr = ByteArray(n); if (n > 0) dup.get(arr); arr
                                }
                                else -> null
                            }
                            if (bytes != null && bytes.isNotEmpty()) {
                                buf.out.write(bytes)
                                atLastWrite[key] = System.currentTimeMillis()
                            }
                        } catch (_: Throwable) { }
                    }
                })
                hit++
            } catch (_: Throwable) { }
        }
        XLog.i("★AT hook 命中=$hit")
        atFlush.scheduleWithFixedDelay({ flushAtCaps() }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** 定时把"已停止写入≥700ms"的 AudioTrack 缓冲落盘为 WAV，并打出峰值/RMS（静音 vs 有声一目了然） */
    private fun flushAtCaps() {
        try {
            val now = System.currentTimeMillis()
            for ((key, buf) in atCap) {
                val last = atLastWrite[key] ?: 0L
                if (buf.out.size() == 0) continue
                if (now - last < 700) continue
                val data = buf.out.toByteArray()
                atCap.remove(key); atLastWrite.remove(key)
                if (data.isEmpty()) continue
                val dir = File(File(FileLogger.path()).parentFile, "tts_audio")
                dir.mkdirs()
                val seq = atSeq.incrementAndGet()
                val ts = synchronized(dateFmt) { dateFmt.format(Date()) }
                val base = "at_${ts}_$seq"
                val dest = File(dir, "$base.wav")
                try {
                    dest.writeBytes(wrapPcmToWav(data, buf.sampleRate, buf.channels, buf.bits))
                    val (peak, rms) = pcmStats(data, buf.bits)
                    val verdict = if (peak < 50) "【疑似静音】" else "【有声】"
                    XLog.i("★AT 播放音频已存 #$seq: ${dest.name} (${data.size}B sr=${buf.sampleRate} ch=${buf.channels} bits=${buf.bits}) peak=$peak rms=$rms $verdict")
                } catch (e: Throwable) { XLog.w("★AT 写WAV失败: ${e.message}") }
            }
        } catch (_: Throwable) { }
    }

    // ───────────────────── 4) doTts/buildTtsEvent：真正决定「念出什么」的注入点 ─────────────────────
    // v1.0.6 关键修正：这两个方法在 TtsManager（不是 ProcessManage）——v1.0.5 挂错类导致命中=0。
    private fun hookDoTts(lpparam: LoadPackageParam) {
        val cls = try {
            XposedHelpers.findClass(TTS_CLASS, lpparam.classLoader)
        } catch (e: Throwable) {
            XLog.e("★A4 未找到 $TTS_CLASS", e); return
        }
        var hitTts = 0
        var hitBuild = 0
        for (m in cls.declaredMethods) {
            when (m.name) {
                "doTts" -> try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try { handleDoTts(param) } catch (e: Throwable) { XLog.e("★A4 处理异常（保留原文）", e) }
                        }
                    })
                    hitTts++
                } catch (e: Throwable) { XLog.w("★A4 hook doTts 失败: ${e.message}") }
                "buildTtsEvent" -> try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try { handleBuildTtsEvent(param) } catch (e: Throwable) { XLog.e("★A4b 处理异常（保留原文）", e) }
                        }
                    })
                    hitBuild++
                } catch (e: Throwable) { XLog.w("★A4b hook buildTtsEvent 失败: ${e.message}") }
            }
        }
        XLog.i("★A4 hook 命中: doTts=$hitTts buildTtsEvent=$hitBuild")
    }

    /** buildTtsEvent(String)：文本→TTS Event 的构造点，arg0 就是要被念出来的文本，直接替换最稳。 */
    private fun handleBuildTtsEvent(param: XC_MethodHook.MethodHookParam) {
        if (!Config.load().enabled) return
        val orig = param.args.firstOrNull() as? String
        XLog.i("★A4b buildTtsEvent 进场: text=「${orig?.take(40)}」 pending=${pendingAnswer?.take(20)}")
        if (!INJECT_AUDIO_TEXT) {
            XLog.i("★A4b [v1.0.7] 音频文本注入已关闭(默认)：保留原始语音以恢复可听，仅作记录")
            return
        }
        if (orig.isNullOrBlank()) return
        val answer = pendingAnswer ?: return   // 没等到 LLM 结果就不动手（保留原文）
        param.args[0] = answer
        lastInjectedText = answer
        pendingAnswer = null
        XLog.i("★A4b ✓ buildTtsEvent 文本已替换为 LLM 回复:「${answer.take(40)}」")
    }

    private fun handleDoTts(param: XC_MethodHook.MethodHookParam) {
        if (!Config.load().enabled) return
        val ev = param.args.firstOrNull { it != null && !isSimple(it) }
        if (ev == null) {
            XLog.i("★A4 doTts 无对象参数: ${param.args.joinToString(",") { it?.javaClass?.simpleName ?: "null" }}")
            return
        }
        val fields = mutableListOf<Pair<String, String>>()
        collectTextFields(ev, fields, 0, mutableSetOf(System.identityHashCode(ev)))
        val types = fields.firstOrNull { it.first.substringAfterLast('.') == "types" }?.second
        val origText = fields.firstOrNull { it.first.substringAfterLast('.') == "text" }?.second
        XLog.i("★A4 doTts 进场: types=$types text=「${origText?.take(30)}」 字段=[${fields.joinToString("; ") { "${it.first}=「${it.second.take(16)}」" }}]")
        // 只接管自动回复；types 缺失时仅记录不动手（防误伤系统提示音）
        if (types != "auto_answer") return

        // 进入 TTS 播放前：开启 AudioTrack 抓取窗口（抓真实播放的 PCM，为 V2 替换音频铺路）
        startTtsCaptureWindow()

        if (!INJECT_AUDIO_TEXT) {
            XLog.i("★A4 [v1.0.7] 音频文本注入已关闭(默认)：doTts 保留原文，恢复可听")
            return
        }
        // doTts 若在 A2 完成前触发，这里给足等待（阻塞 TTS 合成调度，正好实现「TTS 等 LLM」）
        val cap = Config.load().timeoutSec.coerceAtLeast(1) * 1000L + 3000L
        val answer = awaitAnswer(cap, "A4")
        if (answer.isNullOrEmpty()) {
            XLog.w("★A4 未拿到 LLM 回复，doTts 保留原文:「${origText?.take(20)}」")
            return
        }
        val n = replaceTextFields(ev, answer, 0, mutableSetOf(System.identityHashCode(ev)))
        lastInjectedText = answer
        pendingAnswer = null   // 消费掉，防止下一轮误用旧答案
        XLog.i("★A4 ✓ doTts Event.text 已替换为 LLM 回复（$n 处）:「${answer.take(40)}」")
    }

    private fun isSimple(v: Any?): Boolean =
        v == null || v is String || v is Number || v is Boolean || v is Char

    /** 深度收集对象(含嵌套对象, depth≤2)中名为 text / types 的 String 字段 */
    private fun collectTextFields(obj: Any, out: MutableList<Pair<String, String>>, depth: Int, seen: MutableSet<Int>) {
        if (depth > 2) return
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if ((f.name == "text" || f.name == "types") && v is String) {
                        out.add("${c.simpleName}.${f.name}" to v)
                    } else if (!isSimple(v) && v !is Collection<*> && v !is Array<*> && v !is Map<*, *> &&
                        seen.add(System.identityHashCode(v))) {
                        collectTextFields(v, out, depth + 1, seen)
                    }
                } catch (_: Throwable) { }
            }
            c = c.superclass
        }
    }

    /** 深度把对象(含嵌套, depth≤2)中所有名为 text 的 String 字段替换为 answer，返回替换处数 */
    private fun replaceTextFields(obj: Any, answer: String, depth: Int, seen: MutableSet<Int>): Int {
        if (depth > 2) return 0
        var n = 0
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (f.name == "text" && v is String) { f.set(obj, answer); n++ }
                    else if (!isSimple(v) && v !is Collection<*> && v !is Array<*> && v !is Map<*, *> &&
                        seen.add(System.identityHashCode(v))) {
                        n += replaceTextFields(v, answer, depth + 1, seen)
                    }
                } catch (_: Throwable) { }
            }
            c = c.superclass
        }
        return n
    }

    /** 等待后台 LLM 结果：有就返回；主线程或超时/冷却则尽快返回 null（保留原文，绝不堵通话）。
     *  capOverrideMs>0 时用自定义上限（如 doTts 入口只再等 2s，避免 A2 已等过又等一次导致双重超时）。 */
    private fun awaitAnswer(capOverrideMs: Long = 0L, tag: String = "A2"): String? {
        if (pendingAnswer != null) return pendingAnswer
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        XLog.i("★$tag 注入点线程=${Thread.currentThread().name} 主线程=$onMain")
        if (onMain) {
            XLog.w("★$tag 播报在主线程，不阻塞等待（保留原文，避免 ANR）")
            return null
        }
        val cfg = Config.load()
        val cap = if (capOverrideMs > 0) capOverrideMs else (cfg.timeoutSec.coerceAtLeast(1) * 1000L) + WAIT_CAP_BASE_MS
        val deadline = System.currentTimeMillis() + cap
        XLog.i("★$tag 开始阻塞等待 LLM 结果（上限 ${cap}ms）…")
        while (System.currentTimeMillis() < deadline) {
            val a = pendingAnswer
            if (!a.isNullOrEmpty()) return a
            if (llmCooling()) return null
            try { Thread.sleep(50) } catch (_: InterruptedException) { return null }
        }
        XLog.w("★$tag 等待 LLM 超时(${cap}ms)，保留原文")
        return null
    }

    /** 反射设置 bean.text：优先 setText(String)，否则直接写 text 字段 */
    private fun setBeanText(bean: Any, text: String, cl: ClassLoader): Boolean {
        return try {
            val c = bean.javaClass
            try {
                c.getMethod("setText", String::class.java).invoke(bean, text)
                true
            } catch (_: NoSuchMethodException) {
                try {
                    val f = c.getDeclaredField("text")
                    f.isAccessible = true
                    f.set(bean, text)
                    true
                } catch (_: NoSuchFieldException) {
                    XLog.w("★A2 bean 既无 setText 也无 text 字段: ${c.name}")
                    false
                }
            }
        } catch (e: Throwable) {
            XLog.e("★A2 setBeanText 异常", e)
            false
        }
    }

    /** 纯语气词/无实词 → 不喂 LLM（交给小爱原文） */
    private fun isFiller(q: String): Boolean {
        val t = q.trim()
        if (t.length < 2) return true
        val fillers = setOf("嗯", "啊", "哦", "呃", "欸", "诶", "唉", "喂", "呵", "嗨", "哎", "噢", "。", "，", "?", "？", "!", "！")
        if (t in fillers) return true
        return t.none { it.isLetterOrDigit() }
    }

    /** 记录一轮问答到上下文（超过 cap 丢弃最旧的），供后续轮次携带 */
    private fun recordHistory(q: String, ans: String, cap: Int) {
        synchronized(history) {
            history.add(q to ans)
            while (history.size > cap.coerceAtLeast(1)) history.removeAt(0)
        }
    }

    private fun snapshotHistory(): List<Pair<String, String>> = synchronized(history) { history.toList() }
}
