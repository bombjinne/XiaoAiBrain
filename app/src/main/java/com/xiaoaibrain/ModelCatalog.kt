package com.xiaoaibrain

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型清单（v1.0.28 新增）。
 *
 * 背景：模型名原来只能手打，千问（DashScope 兼容模式）模型名多且改版频繁
 * （qwen-max / qwen-plus / qwen-turbo / qwen3-max / qwen-long…），手打容易打错，
 * 一错就是通话里 LLM 报 404。
 *
 * 做法：向配置的 Base URL 发 GET {base}/models（OpenAI 兼容接口，DashScope 的
 * /compatible-mode/v1/models 也遵循同一格式），取 data[].id 作为候选清单，
 * 落盘缓存到 SharedPreferences —— 下次打开配置页即使没网也能下拉选择。
 *
 * 拉取失败不阻塞使用：界面上仍然可以手动输入任意模型名（下拉是可编辑的）。
 */
object ModelCatalog {

    private const val PREF = "model_catalog"
    private const val KEY_LIST = "models"

    /**
     * 拉不到服务器清单时的兜底候选（千问系常用模型）。
     * 只是"建议列表"，不影响手动输入任何其他模型名。
     */
    val QWEN_FALLBACK = listOf(
        "qwen-max",
        "qwen-max-latest",
        "qwen-plus",
        "qwen-plus-latest",
        "qwen-turbo",
        "qwen-turbo-latest",
        "qwen-long",
        "qwen3-max",
        "qwen3-coder-plus",
        "qwen-vl-max",
        "qwen-vl-plus",
        "qwen2.5-72b-instruct",
        "qwen2.5-32b-instruct",
        "qwen2.5-14b-instruct",
        "qwen2.5-7b-instruct"
    )

    data class Result(val ok: Boolean, val models: List<String>, val error: String?)

    /**
     * 推导模型清单地址：
     *   .../v1            → .../v1/models
     *   .../compatible-mode/v1 → .../compatible-mode/v1/models
     *   已带 /models      → 原样返回
     *   其他（只填域名）  → 追加 /v1/models
     */
    fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (b.endsWith("/models")) return b
        return if (b.endsWith("/v1") || b.contains("/v1/")) "$b/models" else "$b/v1/models"
    }

    /** 读取缓存的模型清单（没有缓存则返回千问兜底列表） */
    fun cached(ctx: Context): List<String> {
        return try {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_LIST, null) ?: return QWEN_FALLBACK
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) out.add(s)
            }
            if (out.isEmpty()) QWEN_FALLBACK else out
        } catch (_: Throwable) {
            QWEN_FALLBACK
        }
    }

    private fun save(ctx: Context, models: List<String>) {
        try {
            val arr = JSONArray()
            models.forEach { arr.put(it) }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (_: Throwable) { }
    }

    /** 从服务器拉取模型清单（阻塞调用，必须在后台线程执行） */
    fun fetch(ctx: Context, baseUrl: String, apiKey: String, timeoutSec: Int = 10): Result {
        val url = endpoint(baseUrl)
        if (url.isEmpty()) return Result(false, emptyList(), "Base URL 为空")
        if (apiKey.isBlank()) return Result(false, emptyList(), "API Key 为空")
        val t = timeoutSec.coerceIn(3, 30).toLong()
        val client = OkHttpClient.Builder()
            .connectTimeout(t, TimeUnit.SECONDS)
            .readTimeout(t, TimeUnit.SECONDS)
            .build()
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = try { resp.body?.string().orEmpty() } catch (_: Throwable) { "" }
                if (!resp.isSuccessful) {
                    XLog.w("[模型清单] HTTP ${resp.code} $url")
                    return Result(false, emptyList(), "HTTP ${resp.code} ${body.take(80)}")
                }
                val ids = parse(body)
                if (ids.isEmpty()) return Result(false, emptyList(), "返回里没有模型清单")
                save(ctx, ids)
                XLog.i("[模型清单] 获取成功 ${ids.size} 个：${ids.take(3)}…")
                Result(true, ids, null)
            }
        } catch (e: Throwable) {
            XLog.w("[模型清单] 获取失败 $url: ${e.message}")
            Result(false, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 解析模型清单。兼容三种常见返回：
     *   { "data": [ { "id": "qwen-plus", ... } ] }   ← OpenAI / DashScope 兼容模式
     *   { "models": [ { "name": "..." } ] }          ← 少数自建服务
     *   [ "qwen-plus", "qwen-max" ]                  ← 纯字符串数组
     */
    private fun parse(body: String): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("data") ?: obj.optJSONArray("models")
            if (arr != null) collectObjects(arr, out)
        } catch (_: Throwable) {
            try {
                collectObjects(JSONArray(body), out)
            } catch (_: Throwable) { }
        }
        return out.sorted()
    }

    private fun collectObjects(arr: JSONArray, out: MutableSet<String>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val id = (o?.optString("id")?.takeIf { it.isNotBlank() }
                ?: o?.optString("name")?.takeIf { it.isNotBlank() }
                ?: arr.optString(i).takeIf { it.isNotBlank() && o == null })
            if (id != null) out.add(id)
        }
    }
}
