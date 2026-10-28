package com.xiaoaibrain

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 文件日志：把诊断日志落盘，方便无 adb 环境抓取。
 *
 * 写入位置优先级（取第一个可写的目录）：
 *   1. 公共 Download：/sdcard/Download/xiaoaibrain/xiaoaibrain_hook.log  ← 用户要求的位置
 *   2. 本进程外部私有 Download：/sdcard/Android/data/<当前进程包名>/files/Download/xiaoaibrain/
 *      （App 进程 / 小爱进程各自可写自己的目录，root 文件管理器可访问）
 *   3. 内部私有：/data/data/<包名>/files/xiaoaibrain/
 *
 * 说明：
 *  - 在 hook 进程（小爱）与 UI 进程（本模块 App）中均可调用，各自写到自己能写的目录。
 *  - 未初始化前日志先进内存队列，init 后一次性落盘。
 *  - 文件超过 2MB 自动轮转为 .old.log。
 *  - 同时写入 logcat 与 LSPosed 管理器日志（XposedBridge.log）。
 *  - 全链路 try-catch，日志故障绝不影响主流程。
 */
object FileLogger {

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val SUBDIR = "xiaoaibrain"
    private const val NAME = "xiaoaibrain_hook.log"
    private const val NAME_OLD = "xiaoaibrain_hook.old.log"

    private val lock = Any()
    private val pending = mutableListOf<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 专用单线程：把 XposedBridge.log（跨进程 Binder 到 LSPosed 管理器）挪到这里执行。
     * 原实现每次 FileLogger.write 都在调用线程同步调 XposedBridge.log —— 在 hook 进程
     * 主线程（一次通话几百条日志）会触发 Binder 风暴 → 小爱通话界面卡顿；在 UI 进程
     * 主线程同样危险。v1.0.17 起改为后台执行，调用线程不再被 Binder 阻塞。
     */
    private val logExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XposedLogExec").apply { isDaemon = true }
    }

    @Volatile private var file: File? = null
    @Volatile private var ready = false

    /** 候选目录：优先公共 Download，失败退到进程外部私有 Download，再退内部私有 */
    private fun candidates(ctx: Context): List<File> {
        val list = mutableListOf<File>()
        // 1. 公共 Download（用户要求的位置）
        try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pub != null) list.add(File(pub, SUBDIR))
        } catch (_: Throwable) { }
        // 2. 进程外部私有 Download（App / 小爱各自可写）
        try {
            val ext = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (ext != null) list.add(File(ext, SUBDIR))
        } catch (_: Throwable) { }
        // 3. 内部私有兜底
        try { list.add(File(ctx.filesDir, SUBDIR)) } catch (_: Throwable) { }
        return list
    }

    /** 在进程启动时调用一次，确定日志文件并落盘积压日志 */
    fun init(ctx: Context) {
        val (chosenFile, headerText, flushLines) = synchronized(lock) {
            if (ready) return@synchronized Triple(null as File?, "", emptyList<String>())
            var chosen: File? = null
            for (dir in candidates(ctx)) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (!dir.canWrite()) continue
                    val f = File(dir, NAME)
                    if (!f.exists()) f.createNewFile()
                    chosen = f
                    break
                } catch (_: Throwable) { }
            }
            if (chosen == null) {
                Log.e(Constants.TAG, "文件日志初始化失败：所有候选目录均不可写")
                return@synchronized Triple(null as File?, "", emptyList<String>())
            }
            file = chosen
            ready = true

            // 会话头：进程 / 宿主版本 / 模块版本，方便对比分析
            val header = buildString {
                append("\n===== XiaoAiBrain 会话 ${fmt.format(Date())} =====\n")
                try {
                    val label = ctx.applicationInfo?.loadLabel(ctx.packageManager) ?: ctx.packageName
                    append("进程: $label (${ctx.packageName}) / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                } catch (_: Throwable) {
                    append("进程: ${ctx.packageName}\n")
                }
                try {
                    val me = ctx.packageManager.getPackageInfo(Constants.PKG, 0)
                    append("模块: ${Constants.PKG} v${me.versionName}\n")
                } catch (_: Throwable) { }
                append("日志路径: ${chosen.absolutePath}\n")
            }
            val flush = if (pending.isNotEmpty()) {
                val copy = pending.toList()
                pending.clear()
                copy
            } else emptyList()

            Triple(chosen, header, flush)
        }

        // 会话头 + 积压日志落盘放到后台线程：避免 init 在主线程调用时被 FUSE 写阻塞
        if (chosenFile != null) {
            val path = chosenFile.absolutePath
            logExec.execute {
                try {
                    if (headerText.isNotEmpty()) chosenFile.appendText(headerText)
                    if (flushLines.isNotEmpty()) chosenFile.appendText(flushLines.joinToString(""))
                } catch (_: Throwable) { }
            }
            Log.i(Constants.TAG, "文件日志已启用: $path")
        }
    }

    fun path(): String = file?.absolutePath ?: "(文件日志尚未初始化)"

    /** 每条日志统一出口：logcat + LSPosed 日志(后台) + 文件(后台) */
    fun write(level: String, msg: String) {
        when (level) {
            "E" -> Log.e(Constants.TAG, msg)
            "W" -> Log.w(Constants.TAG, msg)
            else -> Log.i(Constants.TAG, msg)
        }
        // LSPosed 管理器日志：挪到专用后台线程，调用线程（含 hook 进程主线程）不被 Binder 阻塞
        logExec.execute {
            try { XposedBridge.log("XiaoAiBrain[$level] $msg") } catch (_: Throwable) { }
        }

        val line = "${fmt.format(Date())} [$level] $msg\n"
        synchronized(lock) {
            val f = file
            if (!ready || f == null) {
                pending.add(line)
                if (pending.size > 800) pending.removeAt(0)
                return
            }
            // ★ 关键修复：文件写入（FUSE append / 轮转 rename）全部交给后台单线程，
            // 调用线程（UI 主线程 / hook 主线程）绝不阻塞在 FUSE I/O 上 —— 这是卡顿根因之一。
            if (pending.isNotEmpty()) {
                val old = pending.toList()
                pending.clear()
                logExec.execute { appendLines(f, old) }
            }
            logExec.execute { appendLine(f, line) }
        }
    }

    private fun appendLine(f: File, line: String) {
        try {
            if (f.length() > MAX_BYTES) rotate(f)
            f.appendText(line)
        } catch (e: Throwable) {
            Log.e(Constants.TAG, "写日志文件失败: ${e.message}")
        }
    }

    private fun appendLines(f: File, lines: List<String>) {
        try {
            if (f.length() > MAX_BYTES) rotate(f)
            f.appendText(lines.joinToString(""))
        } catch (e: Throwable) {
            Log.e(Constants.TAG, "写日志文件失败: ${e.message}")
        }
    }

    private fun rotate(f: File) {
        try {
            val old = File(f.parentFile, NAME_OLD)
            old.delete()
            f.renameTo(old)
            f.createNewFile()
        } catch (_: Throwable) { }
    }
}
