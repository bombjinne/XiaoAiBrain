package com.xiaoaibrain

import android.os.Environment
import org.json.JSONObject
import java.io.File

private var warnedWrite = false

/**
 * 外部存储共享配置：把配置写成 JSON 文件，放在公共 Download 目录，
 * 并设为全局可读，使 hook 进程（小爱，另一个 uid）也能读取。
 *
 * 为什么不用 XSharedPreferences：Android 12+ 把应用 SharedPreferences
 * 存成 0600 属主文件，hook 进程（小爱）无权限读取，导致配置永远读到默认值。
 * 而小爱进程已能往 /sdcard/Download/xiaoaibrain/ 写日志，说明它对这个
 * 目录有读写权限，因此把配置也放这里最稳。
 */
object ConfigFile {

    private const val SUBDIR = "xiaoaibrain"
    private const val NAME = "xiaoaibrain_config.json"

    private fun file(): File? {
        return try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(pub, SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, NAME)
        } catch (e: Throwable) {
            null
        }
    }

    /** 把配置写入外部存储（带掩码日志由调用方输出） */
    fun write(cfg: Config) {
        try {
            val f = file() ?: return
            val firstTime = !f.exists()
            if (firstTime) {
                try { f.parentFile?.mkdirs() } catch (_: Throwable) { }
            }
            val obj = JSONObject()
            obj.put("enabled", cfg.enabled)
            obj.put("base_url", cfg.baseUrl)
            obj.put("api_key", cfg.apiKey)
            obj.put("model", cfg.model)
            obj.put("system_prompt", cfg.systemPrompt)
            obj.put("timeout_sec", cfg.timeoutSec)
            obj.put("context_turns", cfg.contextTurns)
            obj.put("max_tokens", cfg.maxTokens)
            try {
                f.writeText(obj.toString())
            } catch (e: Throwable) {
                // Android 13 作用域存储：已存在文件可能属主/权限不同导致 EACCES。
                // 删除后重建一次再写（新建文件在目录可写时通常被允许）。
                try { f.delete() } catch (_: Throwable) { }
                try { f.parentFile?.mkdirs() } catch (_: Throwable) { }
                f.writeText(obj.toString())
            }
            // 仅在【文件首次创建】时 chmod：chmod 是 FUSE 上最重的 inode 操作，
            // 旧实现每次保存都做 → 打字期间 IO 线程被持续压满、进程整体迟钝。
            // 现在仅首次创建设置一次全局可读，后续写入只 writeText（hook 仍可读）。
            if (firstTime) {
                try { f.setReadable(true, false) } catch (_: Throwable) { }
            }
        } catch (e: Throwable) {
            // 仅在首次失败时告警一次，避免每 2 秒刷屏（Android 13 作用域存储下往
            // /Download 直接写常被 EACCES 拒绝，属环境权限问题，不影响主流程）。
            if (!warnedWrite) {
                warnedWrite = true
                XLog.w("[ConfigFile] 外部配置写入失败（已忽略后续同类错误）。可能需授予「所有文件访问」权限：${e.message}")
            }
        }
    }

    /** 读取外部配置；文件不存在或损坏返回 null */
    fun read(): Config? {
        return try {
            val f = file() ?: return null
            if (!f.exists() || !f.canRead()) {
                XLog.w("[ConfigFile] 外部配置不可读 exists=${f.exists()} canRead=${f.canRead()}")
                return null
            }
            val obj = JSONObject(f.readText())
            Config(
                enabled = obj.optBoolean("enabled", true),
                baseUrl = obj.optString("base_url", ""),
                apiKey = obj.optString("api_key", ""),
                model = obj.optString("model", ""),
                systemPrompt = obj.optString("system_prompt", Config.DEFAULT_SYSTEM_PROMPT),
                timeoutSec = obj.optInt("timeout_sec", 10),
                contextTurns = obj.optInt("context_turns", 50),
                maxTokens = obj.optInt("max_tokens", 200)
            )
        } catch (e: Throwable) {
            XLog.e("[ConfigFile] 读取失败", e)
            null
        }
    }
}
