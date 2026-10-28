package com.xiaoaibrain

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 模块入口：仅在目标包 com.miui.aiasst.service（AI 通话）加载时注入 hook。
 *
 * 诊断链路：
 *   L1 handleLoadPackage 是否触发（模块激活/作用域是否生效）
 *   L3 宿主 Application.attach → 启用文件日志
 *   L4 配置读取快照（外部共享配置是否读得到）
 *   L5 hook 挂载结果上报（v1.0.24：广播给配置页，版本号上方显示成功/失败）
 *
 * Trace：全量追踪宿主混淆类的文本相关方法，定位真实通话应答构造点。
 *
 * ⚠️ 每个 hook 调用都用 try-catch 隔离，任何一个失败都不会拖垮宿主 App 启动。
 */
class HookEntry : de.robv.android.xposed.IXposedHookLoadPackage {

    /**
     * v1.0.24：hook 安装结果。handleLoadPackage 同步阶段填写，
     * 等宿主 Application.attach 回调触发（已拿到 Context）时上报给配置页。
     */
    @Volatile
    private var hookError: String? = null
    private var installDone = false

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 只注入 AI 通话包（用户指定：hook 点只要 com.miui.aiasst.service）
        if (lpparam.packageName != Constants.TARGET_PKG) return

        XLog.i("★L1 模块注入触发 pkg=${lpparam.packageName} proc=${lpparam.processName}")

        try { hookAppAttach(lpparam) } catch (e: Throwable) {
            hookError = joinErr(hookError, "attach: ${e.message}")
            XLog.e("★L3 attach 异常", e)
        }

        // 全量 Trace：仅用于诊断，默认关闭（常量 TRACE=false）。需要定位新 hook 点时改 true。
        try { TraceHook.hook(lpparam) } catch (e: Throwable) {
            hookError = joinErr(hookError, "TraceHook: ${e.message}")
            XLog.e("TraceHook 异常", e)
        }

        // 小爱通话真实链路 Hook：捕获对端话术→LLM→替换被念出的回复文本
        try { AiaasstHook.hook(lpparam) } catch (e: Throwable) {
            hookError = joinErr(hookError, "AiaasstHook: ${e.message}")
            XLog.e("AiaasstHook 异常", e)
        }

        installDone = true
    }

    private fun joinErr(prev: String?, add: String): String =
        if (prev.isNullOrBlank()) add else "$prev；$add"

    /** L3：捕获宿主 Application 上下文 → 启用文件日志 → 打配置快照 → 上报 hook 状态 */
    private fun hookAppAttach(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpparam.classLoader, "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val ctx = param.args.getOrNull(0) as? Context ?: return
                            HostCtx.ctx = ctx
                            FileLogger.init(ctx)
                            XLog.i("★L3 宿主 Application 就绪，文件日志: ${FileLogger.path()}")
                            logConfigSnapshot()
                            reportHookStatus(ctx)
                        } catch (e: Throwable) {
                            XLog.w("★L3 attach 处理异常: ${e.message}")
                        }
                    }
                })
            XLog.i("★L3 Application.attach hook 已挂载")
        } catch (e: Throwable) {
            XLog.w("★L3 hook Application.attach 失败: ${e.message}")
        }
    }

    /**
     * L5（v1.0.24）：把 hook 挂载结果广播给模块配置页。
     * 版本号取自已安装的模块包信息（不依赖 BuildConfig 类加载，最稳）。
     * 广播在 HookStatus 的后台线程发送，不占宿主主线程。
     */
    private fun reportHookStatus(ctx: Context) {
        if (!installDone) return // handleLoadPackage 尚未跑完（防呆）
        val ver = try {
            ctx.packageManager.getPackageInfo(Constants.PKG, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }
        val err = hookError
        if (err.isNullOrBlank()) {
            HookStatus.broadcast(ctx, HookStatus.STATE_OK, ver)
            XLog.i("★L5 hook 状态上报: OK (模块 v$ver)")
        } else {
            HookStatus.broadcast(ctx, HookStatus.STATE_ERROR, ver, err)
            XLog.w("★L5 hook 状态上报: ERROR ($err)")
        }
    }

    /** L4：把实际加载到的配置打到日志（API Key 打码）。配置优先来自外部共享文件。 */
    private fun logConfigSnapshot() {
        try {
            val cfg = Config.load()
            val key = cfg.apiKey
            val masked = when {
                key.isBlank() -> "(空!)"
                key.length <= 8 -> "****"
                else -> key.take(4) + "****" + key.takeLast(4)
            }
            XLog.i(
                "★L4 配置快照 enabled=${cfg.enabled} model=${cfg.model} " +
                    "baseUrl=${cfg.baseUrl} key=$masked timeout=${cfg.timeoutSec}s"
            )
            XLog.i(
                "★L4 外部配置文件: " +
                    (ConfigFile.read()?.let { "已读取(${it.baseUrl.take(20)})" } ?: "未读取(将用默认)")
            )
        } catch (e: Throwable) {
            XLog.w("★L4 配置快照异常: ${e.message}")
        }
    }
}
