package com.xiaoaibrain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志：logcat + 文件落盘（FileLogger）+ 广播推送到配置页日志区。
 * 在 hook 进程（小爱进程）与 UI 进程（本模块进程）中均可调用。
 */
object XLog {

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun emit(level: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        // 落盘（logcat + 文件；LSPosed 管理器日志已挪到后台线程，见 FileLogger）。
        // ⚠️ v1.0.17：移除原本的 HookLogBus.post() 广播推送。
        //   原因：被 hook 的小爱进程一次通话会打几百条日志，每条都向 UI 进程发一次
        //   广播，LogReceiver 默认跑在 UI 主线程 → 主线程被广播派发风暴淹没 →
        //   "完全点不动按钮/光标闪"。而诊断日志本就通过 FileLogger 落盘、由
        //   LogFileReader 轮询读取（两进程共享同一文件），广播是多余且致命的遗留逻辑。
        FileLogger.write(level, full)
    }

    fun i(msg: String) = emit("I", msg)
    fun w(msg: String) = emit("W", msg)
    fun e(msg: String, t: Throwable? = null) = emit("E", msg, t)

    fun stamp(): String = fmt.format(Date())
}
