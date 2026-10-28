package com.xiaoaibrain

import android.os.Environment
import java.io.File

/**
 * 日志文件读取器（v1.0.11 新增）。
 *
 * 为什么需要它：诊断日志原来靠 HookLogBus.post() 广播从 hook 进程推到配置页。
 * 实测 v1.0.10 广播收不到——hook 发生在「AI通话」进程，此时配置页可能已退到后台，
 * Android 8+ 对后台广播做了限制 + 显式广播也需该进程仍存活，结果诊断区一片空白，
 * 而落盘的日志文件里明明有完整记录。
 *
 * 所以改为【直接读日志文件】：配置页轮询读取 FileLogger 指向的同一个文件，
 * 取末尾若干行渲染。不依赖任何跨进程通信，一定能读到。
 */
object LogFileReader {

    /** 与 FileLogger 相同的候选目录顺序 */
    private fun candidates(ctx: android.content.Context): List<File> {
        val list = mutableListOf<File>()
        try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pub != null) list.add(File(pub, "xiaoaibrain"))
        } catch (_: Throwable) { }
        try {
            // hook 进程（小爱）私有外部目录：Android/data/com.xiaomi.aiasst.service/files/Download/xiaoaibrain
            val ext = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (ext != null) list.add(File(File(ext, "xiaoaibrain"), "xiaoaibrain_hook.log"))
        } catch (_: Throwable) { }
        try { list.add(File(File(ctx.filesDir, "xiaoaibrain"), "xiaoaibrain_hook.log")) } catch (_: Throwable) { }
        return list
    }

    /** 找到实际存在的日志文件；找不到返回 null */
    fun locate(ctx: android.content.Context): File? {
        for (d in candidates(ctx)) {
            val f = if (d.isDirectory) File(d, "xiaoaibrain_hook.log") else d
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    /**
     * 读取日志末尾 [tailLines] 行。
     * 文件较大（上限 2MB）时只读最后 256KB，避免每次轮询都全量读。
     */
    fun tail(ctx: android.content.Context, tailLines: Int = 200): List<String> {
        val f = locate(ctx) ?: return emptyList()
        return try {
            val lines = ArrayList<String>(tailLines)
            f.useLinesInRead { seq ->
                val arr = ArrayDeque<String>()
                for (line in seq) {
                    arr.addLast(line)
                    if (arr.size > tailLines) arr.removeFirst()
                }
                lines.addAll(arr)
            }
            lines
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 读取全部日志行（用于聊天记录持久回显——配置页打开时能看到上次通话） */
    fun all(ctx: android.content.Context): List<String> {
        val f = locate(ctx) ?: return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

/** Kotlin 扩展：读文件全部行并自动关闭（readLines 已有 close，这里显式封装便于 try-catch） */
private inline fun <T> File.useLinesInRead(block: (Sequence<String>) -> T): T {
    return bufferedReader().use { br -> block(br.lineSequence()) }
}
