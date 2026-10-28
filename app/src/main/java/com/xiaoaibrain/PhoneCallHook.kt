package com.xiaoaibrain

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 主 Hook：小爱 AI 通话「端侧应答总构造点」。
 *
 * 逆向实证（详见 docs/逆向分析报告.md）：
 *   com.xiaomi.ai.domain.phonecall.PhoneCallSolver#provideInternalFor3_0
 *   签名：(JSONObject, String requestId, String isOnline, JSONObject, PhoneCallRequestEnv, Boolean) -> PhoneCallAnswer
 *   作用：本地话术工厂生成 PhoneCallContent(toSpeak) 并组装 PhoneCallAnswer 返回。
 *   全量 classes22.dex 交叉引用证实：new PhoneCallAnswer + setContent + setText 只出现在此方法。
 *
 * 策略：afterHookedMethod 里拿到原 Answer → 提取对端话术 → 调 LLM →
 *       改写 content.toSpeak / text / toDisplay → 返回给原流程念出来。
 *       任何一步失败都保留小爱原文，绝不破坏通话。
 */
object PhoneCallHook {

    private const val SOLVER_CLASS = "com.xiaomi.ai.domain.phonecall.PhoneCallSolver"
    private const val TARGET_METHOD = "provideInternalFor3_0"
    private const val ENV_CLASS = "com.xiaomi.ai.domain.phonecall.common.PhoneCallRequestEnv"

    /** 已被本模块处理过的 answer 对象，防止备用 hook 重复替换 */
    private val processedAnswers: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun hook(lpparam: LoadPackageParam) {
        val cl = lpparam.classLoader
        val solverCls = try {
            XposedHelpers.findClass(SOLVER_CLASS, cl)
        } catch (e: Throwable) {
            XLog.e("★L2 主hook失败：未找到 $SOLVER_CLASS（小爱版本可能不匹配）", e)
            return
        }
        XLog.i("★L2 主hook：类已找到 $SOLVER_CLASS")

        // 方案一：直接对声明方法逐个 hook（不依赖参数类型解析，最稳）
        var hooked = 0
        val declared: Array<Method> = try {
            solverCls.declaredMethods
        } catch (e: Throwable) {
            XLog.w("★L2 declaredMethods 枚举失败: ${e.message}")
            emptyArray()
        }
        XLog.i("★L2 PhoneCallSolver 共 ${declared.size} 个声明方法")

        for (m in declared) {
            if (m.name != TARGET_METHOD) {
                continue
            }
            val sig = m.parameterTypes.joinToString(",") { it.simpleName }
            try {
                XposedBridge.hookMethod(m, callback)
                hooked++
                XLog.i("★L2 主hook成功: $TARGET_METHOD($sig)")
            } catch (e: Throwable) {
                XLog.e("★L2 hook $TARGET_METHOD($sig) 失败", e)
            }
        }

        // 方案二：兜底，按实证的精确参数类型 hook
        if (hooked == 0) {
            XLog.w("★L2 声明方法里没匹配到 $TARGET_METHOD，类内方法清单: " +
                declared.joinToString(" | ") { it.name })
            try {
                val envCls = XposedHelpers.findClass(ENV_CLASS, cl)
                XposedHelpers.findAndHookMethod(
                    solverCls, TARGET_METHOD,
                    org.json.JSONObject::class.java,      // 意图 JSON
                    String::class.java,                   // requestId
                    String::class.java,                   // isOnline
                    org.json.JSONObject::class.java,      // context
                    envCls,                               // PhoneCallRequestEnv
                    java.lang.Boolean::class.java,        // 注意：描述符是 Ljava/lang/Boolean;（装箱）
                    callback
                )
                hooked = 1
                XLog.i("★L2 兜底 hook 成功（按实证参数类型）")
            } catch (e: Throwable) {
                XLog.e("★L2 兜底 hook 也失败", e)
            }
        }

        XLog.i("★L2 主 hook 挂载完成，命中方法数=$hooked（>0 才能拦截通话）")
    }

    private val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
            try {
                // 每次方法被调用都记一条（无论 result 是否为空），
                // 这是判断"方法有没有被执行"的硬证据
                val resultDesc = if (param.result == null) "null" else param.result.javaClass.simpleName
                XLog.i("✳ provideInternalFor3_0 被调用 args=${param.args.size} result=$resultDesc")
                handleAnswer(param)
            } catch (e: Throwable) {
                XLog.e("处理应答异常（已保护，不影响小爱）", e)
            }
        }
    }

    private fun handleAnswer(param: XC_MethodHook.MethodHookParam) {
        val cfg = Config.load()
        if (!cfg.enabled) {
            XLog.i("✳ 模块开关已关闭(enabled=false)，本次跳过")
            return
        }

        val answer = param.result
        if (answer == null) {
            XLog.w("✳ result=null（本路径未产生应答，可能非AI通话分支）")
            return
        }
        val requestId = param.args.getOrNull(1)?.toString() ?: ""

        val intention = try {
            XposedHelpers.callMethod(answer, "getIntention")
        } catch (e: Throwable) {
            XLog.w("✳ getIntention 失败: ${e.message}"); null
        }
        val query = extractQuery(param, intention)
        if (query == null) {
            XLog.w("✳ 对端话术提取为空（intention=${intention?.javaClass?.name}），跳过")
            return
        }

        val content = try {
            XposedHelpers.callMethod(answer, "getContent")
        } catch (e: Throwable) {
            XLog.w("✳ getContent 失败: ${e.message}"); null
        }
        if (content == null) {
            XLog.w("✳ content=null，跳过")
            return
        }
        val original = (XposedHelpers.callMethod(content, "getToSpeak") as? String) ?: ""

        XLog.i("✳ hook触发 requestId=$requestId 对端=「$query」 原答=「${original.take(40)}」")

        val history = CallSession.history(requestId, cfg.contextTurns)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val r = LlmClient.chat(cfg, history, query)
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        if (!r.ok || r.text.isNullOrEmpty()) {
            XLog.w("✳ LLM失败(${r.error}, ${cost}ms)，保留小爱原文")
            return
        }

        try {
            XposedHelpers.callMethod(content, "setToSpeak", r.text)
            XposedHelpers.callMethod(content, "setToDisplay", r.text)
            XposedHelpers.callMethod(answer, "setText", r.text)
        } catch (e: Throwable) {
            XLog.e("✳ 改写文本失败", e)
            return
        }

        CallSession.push(requestId, query, r.text)
        markProcessed(answer)

        // 复读校验：确认写入真的生效
        val verify = try {
            XposedHelpers.callMethod(content, "getToSpeak") as? String
        } catch (e: Throwable) { "(校验失败: ${e.message})" }
        XLog.i("✓ 已替换(${cost}ms) → 「${r.text}」 复读=「$verify」")
    }

    /**
     * 提取对端话术（LLM 的输入）。依次尝试：
     *  1. intention.getFixQuery() —— ASR 归一化后的查询（主）
     *  2. intention.getMessageText() / getName()
     *  3. 原始意图 JSON 的 fixQuery/query/messageText 字段
     */
    private fun extractQuery(param: XC_MethodHook.MethodHookParam, intention: Any?): String? {
        if (intention != null) {
            val fixQuery = (XposedHelpers.callMethod(intention, "getFixQuery") as? String)?.trim()
            if (!fixQuery.isNullOrEmpty()) return fixQuery
            val messageText = (XposedHelpers.callMethod(intention, "getMessageText") as? String)?.trim()
            if (!messageText.isNullOrEmpty()) return messageText
            val name = (XposedHelpers.callMethod(intention, "getName") as? String)?.trim()
            if (!name.isNullOrEmpty()) return name
        }
        (param.args.getOrNull(0) as? org.json.JSONObject)?.let { j ->
            for (key in listOf("fixQuery", "query", "messageText")) {
                val v = j.optString(key)?.trim()
                if (!v.isNullOrEmpty()) return v
            }
        }
        return null
    }

    fun markProcessed(answer: Any?) {
        if (answer != null) processedAnswers[answer] = true
    }

    fun isProcessed(answer: Any?): Boolean =
        answer != null && processedAnswers.containsKey(answer)
}
