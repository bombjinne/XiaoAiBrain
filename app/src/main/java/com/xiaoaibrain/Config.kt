package com.xiaoaibrain

import de.robv.android.xposed.XSharedPreferences

/**
 * 配置缓存（v1.0.12）。
 *
 * v1.0.11 实测"界面非常卡、按钮点不动"的根因之一：Config.load() 被高频调用
 * （日志里 L4 出现 37 次），每次都【读文件 + 解析 JSON + 写一条日志】，
 * 而 hook 大多发生在主线程 → 主线程被 IO 反复占住。
 *
 * 现在：① 结果缓存 3 秒（够实时，又不重复读盘）；② 配置来源日志只在首次或变化时打一条。
 */
private object ConfigCache {
    private val lock = Any()
    private var cached: Config? = null
    private var cachedAt: Long = 0L
    private var lastSource: String = ""

    const val TTL_MS = 3000L

    /** 取配置（带缓存）。fileRead 返回 null 表示没读到 */
    fun get(fileRead: () -> Config?, source: (Config?) -> String): Config? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val c = cached
            if (c != null && now - cachedAt < TTL_MS) return c
        }
        val fresh = fileRead()
        synchronized(lock) {
            // 双检：避免并发时重复读盘
            val c2 = cached
            if (c2 != null && now - cachedAt < TTL_MS) return c2
            cached = fresh
            cachedAt = System.currentTimeMillis()
        }
        if (fresh != null) {
            val src = source(fresh)
            if (src != lastSource) {
                lastSource = src
                XLog.i("★L4 配置来源=$src")
            }
        }
        return fresh
    }
}

/**
 * hook 配置。UI 进程写 SharedPreferences("xiaoaibrain_config")，
 * hook 进程（小爱进程）用 XSharedPreferences 读同一文件。
 */
data class Config(
    val enabled: Boolean = true,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val timeoutSec: Int = 10,
    val contextTurns: Int = 50,
    val maxTokens: Int = 200
) {
    /** 配置是否足够发起一次 LLM 请求 */
    val ready: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val PREFS_FILE = "xiaoaibrain_config"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /**
         * 默认人设（v1.0.23 按机主要求更新）：
         * 电话场景、口语化、短回复；推销场景转为"一直追问产品"的拖字策略。
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是机主的AI助理，正在电话中帮机主应付来电。要求：" +
                "1) 用口语化中文，像真人接电话一样自然；" +
                "2) 每次回复不超过40个字，直接说重点，不要解释你是AI；" +
                "3) 不要透露机主任何隐私信息（位置、日程、关系等）；" +
                "4) 对方如果是推销贷款或其他任何东西，你要尽可能的跟他聊，" +
                "但是绝对不当面承诺要买，就是一直问他的推销的产品；"

        /**
         * 读取配置（带 3 秒缓存，见 ConfigCache）。
         * 顺序：外部共享文件 → XSharedPreferences → 默认值。
         */
        fun load(): Config {
            // 1) 优先外部共享配置文件（hook 进程可读，绕开 XSharedPreferences 权限限制）
            ConfigCache.get({ ConfigFile.read() }) { f ->
                "外部文件(${f?.baseUrl?.take(20)}..) model=${f?.model} keySet=${f?.apiKey?.isNotBlank()}"
            }?.let { return it }

            // 2) 兜底 XSharedPreferences（部分 ROM 仍可用）
            return try {
                val p = XSharedPreferences(Constants.PKG, PREFS_FILE)
                try { p.reload() } catch (_: Throwable) { }
                val cfg = Config(
                    enabled = p.getBoolean("enabled", true),
                    baseUrl = p.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                    apiKey = p.getString("api_key", "") ?: "",
                    model = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                    systemPrompt = p.getString("system_prompt", DEFAULT_SYSTEM_PROMPT)
                        ?: DEFAULT_SYSTEM_PROMPT,
                    timeoutSec = p.getInt("timeout_sec", 10),
                    contextTurns = p.getInt("context_turns", 50),
                    maxTokens = p.getInt("max_tokens", 200)
                )
                cfg
            } catch (e: Throwable) {
                // 读不到配置时用默认值，保证 hook 不崩
                XLog.w("★L4 配置读取失败，用默认值: ${e.message}")
                Config()
            }
        }

        /**
         * 诊断 XSharedPreferences 是否真的读到了 UI 写的配置。
         * 读不到时 hook 会用默认配置（apiKey 为空 → LLM 必然报"配置不完整"）。
         */
        fun diagnostics(): String {
            return try {
                val p = XSharedPreferences(Constants.PKG, PREFS_FILE)
                val f = try { p.file } catch (e: Throwable) { null }
                val hasKey = try {
                    p.getString("api_key", "")?.isNotBlank() == true
                } catch (e: Throwable) { false }
                val hasUrl = try {
                    p.getString("base_url", "")?.isNotBlank() == true
                } catch (e: Throwable) { false }
                "文件=${f?.absolutePath ?: "?"} 存在=${f?.exists()} 可读=${f?.canRead()} " +
                    "读到apiKey=$hasKey 读到baseUrl=$hasUrl"
            } catch (e: Throwable) {
                "XSharedPreferences异常: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
}
