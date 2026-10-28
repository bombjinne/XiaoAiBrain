package com.xiaoaibrain

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全量追踪（Trace）Hook —— 定位「真实通话时，AI 通话(6.0.3) 在哪个方法里生成/组装应答文本」。
 *
 * ── 教训 ──
 * 上一版用 hook ClassLoader.loadClass，在【启动期】对每个混淆类同步挂上全部文本方法，
 * 最多 500 类 × 几十方法 = 启动瞬间挂上成千上万个 hook，直接把 com.xiaomi.aiasst.service
 * 拖崩（"打不开"、且进程在落盘前就死 → 无日志）。
 *
 * ── 本版安全模式 ──
 *   1) 只 hook 宿主 Application.onCreate：等 App 已经正常起来后，再【异步线程】枚举已加载的类；
 *   2) 通过 BaseDexClassLoader 反射枚举 dex 中的类名，只认「顶级包名 1~2 字母」的混淆宿主类，
 *      跳过所有框架/库类（android./androidx./okhttp3./com./...）；
 *   3) 只对「参数或返回值是 String/CharSequence/JSONObject」的方法挂追踪器，并设全局硬上限；
 *   4) 追踪器只在「调用携带>=4字文本」时才落日志，避免刷屏。
 *
 * 分析时找「通话时刻附近、参数/返回值带中文回复文本」的那条 a2/xxx#方法(...) 即为真入口。
 */
object TraceHook {

    private const val TRACE = false
    private const val MAX_LOGS = 2000          // 全局 TRC 行数上限（防刷屏）
    private const val MAX_PER_METHOD = 2       // 同一方法最多记几次
    private const val MAX_CLASSES = 600        // 最多枚举多少个宿主类
    private const val MAX_METHODS = 1000       // 全局最多挂多少个方法（防拖慢）

    private val logCount = AtomicInteger(0)
    private val perMethod = ConcurrentHashMap<String, AtomicInteger>()
    private val methodCount = AtomicInteger(0)

    /** 框架/库顶级包名黑名单（双保险，匹配到一律跳过） */
    private val FORBIDDEN_TOP = setOf(
        "android", "java", "javax", "kotlin", "kotlinx", "androidx", "dalvik",
        "miui", "com", "org", "okhttp3", "okio", "gson", "retrofit2", "rx",
        "io", "net", "okhttp", "firebase", "google", "play", "gms", "apache",
        "xml", "sun", "junit", "bouncycastle", "sql", "webkit", "w3c", "icu",
        "json", "typo", "support", "constraint", "arch", "lifecycle", "room",
        "nav", "preference", "recyclerview", "viewpager", "coordinator",
        "xiaomi"
    )

    private var appClassLoader: ClassLoader? = null

    fun hook(lpparam: LoadPackageParam) {
        if (!TRACE) return
        if (lpparam.packageName != Constants.TARGET_PKG) return
        appClassLoader = lpparam.classLoader
        hookApplicationOnCreate(lpparam)
        XLog.i("Ⓣ Trace 已注册（等宿主 Application.onCreate 后异步枚举通话类，避免启动期拖垮 App）")
    }

    /** 等 App 已经正常 onCreate，再异步枚举并 hook，绝不阻塞启动 */
    private fun hookApplicationOnCreate(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpparam.classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            XLog.i("Ⓣ 宿主 Application.onCreate 触发，将异步枚举已加载类…")
                            val loader = appClassLoader
                            Thread {
                                try {
                                    enumerateAndHook(loader)
                                } catch (e: Throwable) {
                                    XLog.e("Ⓣ 枚举失败", e)
                                }
                            }.start()
                        } catch (e: Throwable) {
                            XLog.e("Ⓣ onCreate 处理异常", e)
                        }
                    }
                })
            XLog.i("Ⓣ Application.onCreate 追踪已挂载")
        } catch (e: Throwable) {
            XLog.w("Ⓣ hook Application.onCreate 失败: ${e.message}")
        }
    }

    private fun enumerateAndHook(cl: ClassLoader?) {
        val loader = cl ?: return
        val classNames = listDexEntries(loader).filter { isAppClass(it) }.take(MAX_CLASSES)
        XLog.i("Ⓣ 枚举到疑似宿主混淆类 ${classNames.size} 个（上限 $MAX_CLASSES），开始选择性 hook 文本方法…")
        var hooked = 0
        for (cn in classNames) {
            if (methodCount.get() >= MAX_METHODS) break
            try {
                // initialize=false：只加载不执行 <clinit>，避免触发宿主静态初始化副作用
                val cls = Class.forName(cn, false, loader)
                hooked += hookTextMethods(cls, cn)
            } catch (e: Throwable) {
                // 个别类加载失败忽略
            }
        }
        XLog.i("Ⓣ Trace 完成：共挂上 $hooked 个文本方法（全局上限 $MAX_METHODS）")
    }

    /** 通过 BaseDexClassLoader.pathList.dexElements[].dexFile.entries() 枚举 dex 类名 */
    private fun listDexEntries(cl: ClassLoader): List<String> {
        val out = mutableListOf<String>()
        try {
            val pathList = XposedHelpers.getObjectField(cl, "pathList")
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*> ?: return out
            for (el in elements) {
                if (el == null) continue
                var dexFile: Any? = try { XposedHelpers.getObjectField(el, "dexFile") } catch (e: Throwable) { null }
                if (dexFile == null) {
                    dexFile = try { XposedHelpers.callMethod(el, "getDexFile") } catch (e: Throwable) { null }
                }
                if (dexFile == null) continue
                val entries = try {
                    XposedHelpers.callMethod(dexFile, "entries") as? Enumeration<*>
                } catch (e: Throwable) { null } ?: continue
                while (entries.hasMoreElements()) {
                    val cn = entries.nextElement() as? String ?: continue
                    out.add(cn)
                }
            }
        } catch (e: Throwable) {
            XLog.w("Ⓣ 枚举 dex 失败: ${e.message}")
        }
        return out
    }

    /** 只认宿主混淆类：顶级包名 1~2 字母且不在框架黑名单（AI通话被全面混淆为 a/a0/a2… 这种） */
    private fun isAppClass(name: String): Boolean {
        val top = name.substringBefore('.')
        if (top in FORBIDDEN_TOP) return false
        return top.length in 1..2
    }

    private fun hookTextMethods(cls: Class<*>, cn: String): Int {
        val methods = try { cls.declaredMethods } catch (e: Throwable) { emptyArray<Method>() }
        var count = 0
        for (m in methods) {
            if (methodCount.get() >= MAX_METHODS) break
            if (!carriesText(m)) continue
            try {
                XposedBridge.hookMethod(m, makeTracer(cn, m.name, m.parameterTypes))
                count++
                methodCount.incrementAndGet()
            } catch (e: Throwable) {
                // 个别方法 hook 失败忽略
            }
        }
        return count
    }

    /** 只 hook 文本载体方法（回复文本必然流经 String/CharSequence/JSONObject 参数或返回值） */
    private fun carriesText(m: Method): Boolean {
        val ret = m.returnType
        val params = m.parameterTypes
        return ret == String::class.java || ret == CharSequence::class.java ||
            ret.name == "org.json.JSONObject" || ret.name == "org.json.JSONArray" ||
            params.any { it == String::class.java || it == CharSequence::class.java ||
                it.name == "org.json.JSONObject" || it.name == "org.json.JSONArray" }
    }

    private fun makeTracer(className: String, methodName: String, paramTypes: Array<Class<*>>): XC_MethodHook {
        val key = "$className#$methodName(${paramTypes.joinToString(",") { it.simpleName }})"
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                // 只在「参数携带文本」时才记，避免淹没日志
                if (!hasText(param.args, null)) return
                if (!allow("$key#b")) return
                try {
                    XLog.i("Ⓣ⮕ $key args=[${previewArgs(param.args)}]")
                } catch (e: Throwable) { }
            }

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                // 只在「返回值携带文本」时才记
                if (!hasText(null, param.result)) return
                if (!allow("$key#a")) return
                try {
                    val ret = param.result
                    val retDesc = if (ret == null) "null" else ret.javaClass.name
                    XLog.i("Ⓣ⮕ $key => $retDesc:${preview(ret, 160)}")
                } catch (e: Throwable) { }
            }
        }
    }

    /** 只看「携带文本」的调用：参数或返回值里有长度>=4 的字符串才算 */
    private fun hasText(args: Array<Any?>?, ret: Any?): Boolean {
        if (ret is String && ret.length >= 4) return true
        args?.forEach { a ->
            if (a is String && a.length >= 4) return true
        }
        return false
    }

    private fun allow(key: String): Boolean {
        if (logCount.get() >= MAX_LOGS) return false
        val c = perMethod.computeIfAbsent(key) { AtomicInteger(0) }
        if (c.getAndIncrement() >= MAX_PER_METHOD) return false
        logCount.incrementAndGet()
        return true
    }

    private fun previewArgs(args: Array<Any?>?): String {
        if (args == null) return ""
        return args.joinToString(" , ") { preview(it, 80) }
    }

    private fun preview(v: Any?, limit: Int): String {
        if (v == null) return "null"
        val cn = v.javaClass.name
        return when {
            v is String -> "「${v.take(limit)}」"
            cn == "org.json.JSONObject" || cn == "org.json.JSONArray" -> {
                val s = v.toString()
                "«${s.take(limit)}»"
            }
            else -> v.javaClass.simpleName
        }
    }
}
