package com.xiaoaibrain

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/**
 * OkHttp 计时监听器：拆解 DNS / 连接+服务端(模型)生成 / 总耗时。
 * 用于「测试连接」和真实通话，定位 LLM 慢在哪一环（网络 or 模型）。
 */
private class LlmTimingListener : EventListener() {
    private val t0 = SystemClock.elapsedRealtime()
    private var dnsMs = -1L
    private var headMs = -1L
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = SystemClock.elapsedRealtime() - t0
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        headMs = SystemClock.elapsedRealtime() - t0
    }
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        val total = SystemClock.elapsedRealtime() - t0
        val model = if (headMs >= 0 && dnsMs >= 0) headMs - dnsMs else -1
        XLog.i("[LLM] 计时: DNS=${dnsMs}ms 连+服=${if (headMs >= 0) headMs else "?"}ms(模型生成≈${model}ms) 总=${total}ms")
    }
    override fun callFailed(call: Call, ioe: IOException) {
        val total = SystemClock.elapsedRealtime() - t0
        XLog.w("[LLM] 计时(失败): DNS=${dnsMs}ms 总=${total}ms err=${ioe.message}")
    }
}

/**
 * OpenAI 兼容 /chat/completions 客户端。
 *
 * 实测结论（2026-10-14 通话中实测日志）：
 *  - 被 hook 的 aiasst 通话进程【本身就能直连外网】（WIFI、无 VPN、DNS 秒解、LLM 1~5s 返回）。
 *    之前"被语音 VPN 隔离"是误判（首次超时是当时 WIFI 瞬时未就绪）。
 *  - 因此【不需要跨进程代理】，本进程直接发请求即可，反而延迟更低、更稳。
 *  - 超时策略：connect = 配置超时(默认5s)，read = 配置超时+1s（给模型约5s生成留余量）。
 *    超时即视为失败 → 上层回退"保留小爱原文"。
 *  - 保留网络绑定(WIFI/CELLULAR 非VPN)与完整诊断日志，便于日后排查。
 */
object LlmClient {

    data class Result(val ok: Boolean, val text: String?, val error: String?)

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 对外统一入口：本进程直连。 */
    fun chat(cfg: Config, history: List<Pair<String, String>>, userText: String): Result {
        return chatDirect(cfg, history, userText, null)
    }

    /**
     * 真实 LLM 调用。
     * @param diagCtx 用于网络诊断/网卡绑定的 Context（传宿主 App Context，可拿到更准确网络视图）。
     */
    fun chatDirect(cfg: Config, history: List<Pair<String, String>>, userText: String, diagCtx: Context? = null): Result {
        val proc = procLabel(currentApplication())
        return try {
            if (!cfg.ready) return Result(false, null, "配置不完整（Base URL 或 API Key 为空）")
            if (userText.isBlank()) return Result(false, null, "对端话术为空")
            XLog.i("[LLM] 来自进程=$proc 线程=${Thread.currentThread().name}")
            try { NetProbe.probe(diagCtx ?: HostCtx.ctx, cfg.baseUrl, "[LLM-NET]") } catch (_: Throwable) { }
            logNetworkDiagnosis(cfg.baseUrl, diagCtx)

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", cfg.systemPrompt))
            for ((q, a) in history) {
                messages.put(JSONObject().put("role", "user").put("content", q))
                messages.put(JSONObject().put("role", "assistant").put("content", a))
            }
            messages.put(JSONObject().put("role", "user").put("content", userText))

            val payload = JSONObject()
                .put("model", cfg.model)
                .put("messages", messages)
                .put("max_tokens", cfg.maxTokens)
                .put("temperature", 0.7)
                .toString()

            val client = buildClient(cfg, diagCtx)
            val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
            XLog.i("[LLM] 请求开始 url=$url model=${cfg.model} 历史轮=${history.size} 输入=「${userText.take(50)}」")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            val t0 = SystemClock.elapsedRealtime()
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string()
                val cost = SystemClock.elapsedRealtime() - t0
                if (!resp.isSuccessful) {
                    XLog.w("[LLM] HTTP ${resp.code} (${cost}ms) body=${bodyStr?.take(200)}")
                    return Result(false, null, "HTTP ${resp.code} ${bodyStr?.take(120) ?: ""}")
                }
                if (bodyStr.isNullOrEmpty()) {
                    XLog.w("[LLM] 响应体为空 (${cost}ms)")
                    return Result(false, null, "响应体为空")
                }
                val content = JSONObject(bodyStr)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (content.isEmpty()) {
                    XLog.w("[LLM] 模型返回空内容 (${cost}ms)")
                    Result(false, null, "模型返回空内容")
                } else {
                    XLog.i("[LLM] 成功 (${cost}ms) →「${content.take(60)}」")
                    Result(true, content, null)
                }
            }
        } catch (e: Throwable) {
            XLog.e("[LLM] 请求异常 | ${e.javaClass.simpleName}: ${e.message}")
            XLog.e("[LLM] 异常堆栈:\n${e.stackTraceToString()}")
            Result(false, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 打印当前进程的网络拓扑 + DNS + 真实 TCP 连接，定位「为什么某进程访问外网超时」 */
    private fun logNetworkDiagnosis(baseUrl: String, ctx: Context?) {
        val c = ctx ?: HostCtx.ctx ?: run {
            XLog.w("[LLM] 诊断: 无 Context，无法查网络"); return
        }
        val cm = try {
            c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Throwable) { null } ?: return
        try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            XLog.i("[LLM] 诊断: activeNetwork=$active isVpn=${caps?.hasTransport(NET_VPN)} transports=${capsTransports(caps)}")
            val nets = cm.allNetworks
            XLog.i("[LLM] 诊断: 共 ${nets.size} 个网络:")
            nets.forEachIndexed { i, n ->
                val nc = cm.getNetworkCapabilities(n)
                XLog.i("[LLM]    [$i] $n internet=${nc?.hasCapability(NET_INTERNET)} validated=${nc?.hasCapability(NET_VALIDATED)} vpn=${nc?.hasTransport(NET_VPN)} transports=${capsTransports(nc)}")
            }
        } catch (e: Throwable) { XLog.w("[LLM] 诊断: 枚举网络异常: ${e.message}") }
        try {
            val host = try { java.net.URL(baseUrl).host } catch (e: Throwable) { null }
            if (host != null) {
                val t0 = SystemClock.elapsedRealtime()
                val addrs = java.net.InetAddress.getAllByName(host)
                val cost = SystemClock.elapsedRealtime() - t0
                XLog.i("[LLM] 诊断: DNS[$host] 解析到 ${addrs.size} 个IP (${cost}ms): ${addrs.joinToString { it.hostAddress ?: it.toString() }}")
            }
        } catch (e: Throwable) {
            XLog.e("[LLM] 诊断: DNS 解析失败[$baseUrl]: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun capsTransports(c: NetworkCapabilities?): String {
        if (c == null) return "null"
        val list = mutableListOf<String>()
        if (c.hasTransport(TRANSPORT_WIFI)) list.add("WIFI")
        if (c.hasTransport(TRANSPORT_CELLULAR)) list.add("CELLULAR")
        if (c.hasTransport(NET_VPN)) list.add("VPN")
        if (c.hasTransport(TRANSPORT_ETHERNET)) list.add("ETHERNET")
        if (c.hasTransport(TRANSPORT_BLUETOOTH)) list.add("BT")
        if (c.hasTransport(TRANSPORT_WIFI_AWARE)) list.add("WIFI_AWARE")
        if (c.hasTransport(TRANSPORT_LOWPAN)) list.add("LOWPAN")
        return list.joinToString(",")
    }

    /**
     * 构造 OkHttpClient：
     *  connect = 配置超时(默认10s)；read/write = 配置超时+3s（模型生成实测 3~10s，留足余量，避免闪烁式超时）。
     *  绑定非 VPN 的底层 WIFI/CELLULAR 网卡（实测 aiasst 进程里能取到正常 WIFI，绑定后秒通）。
     */
    private fun buildClient(cfg: Config, ctx: Context?): OkHttpClient {
        val to = cfg.timeoutSec.coerceAtLeast(1)
        val connectSec = to.toLong()
        val ioSec = (to + 3).toLong()
        val b = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(ioSec, TimeUnit.SECONDS)
            .writeTimeout(ioSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .eventListenerFactory { LlmTimingListener() }
        try {
            val net = pickUnderlyingNetwork(ctx)
            if (net != null) {
                val c = (ctx ?: HostCtx.ctx)?.let {
                    it.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                }?.getNetworkCapabilities(net)
                b.socketFactory(net.socketFactory)
                XLog.i("[LLM] 已绑定网络(绕开VPN): $net transports=${capsTransports(c)} vpn=${c?.hasTransport(NET_VPN)}")
            } else {
                XLog.w("[LLM] 未取到非VPN底层网，用默认网络")
            }
        } catch (e: Throwable) {
            XLog.w("[LLM] 绑定底层网络失败，用默认网络: ${e.message}")
        }
        return b.build()
    }

    /** 挑选「真能上公网」的底层网：已验证、有 INTERNET、WIFI/CELLULAR、且非 VPN。 */
    private fun pickUnderlyingNetwork(ctx: Context?): Network? {
        val c = ctx ?: HostCtx.ctx ?: return null
        val cm = try { c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (e: Throwable) { null } ?: return null
        return try {
            val all = cm.allNetworks ?: return null
            all.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasCapability(NET_INTERNET) && caps.hasCapability(NET_VALIDATED) &&
                    (caps.hasTransport(TRANSPORT_WIFI) || caps.hasTransport(TRANSPORT_CELLULAR)) &&
                    !caps.hasTransport(NET_VPN)
            } ?: cm.activeNetwork
        } catch (e: Throwable) { null }
    }

    // ── 进程标识 ──

    private fun currentApplication(): Application? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Application
    } catch (e: Throwable) { null }

    private fun procLabel(app: Application?): String = try {
        val uid = android.os.Process.myUid()
        "${app?.packageName ?: "?"} uid=$uid pid=${android.os.Process.myPid()}"
    } catch (e: Throwable) { "?" }

    // NetworkCapabilities 常量（避免反复写全限定名）
    private val NET_INTERNET = NetworkCapabilities.NET_CAPABILITY_INTERNET
    private val NET_VALIDATED = NetworkCapabilities.NET_CAPABILITY_VALIDATED
    private val NET_VPN = NetworkCapabilities.TRANSPORT_VPN
    private val TRANSPORT_WIFI = NetworkCapabilities.TRANSPORT_WIFI
    private val TRANSPORT_CELLULAR = NetworkCapabilities.TRANSPORT_CELLULAR
    private val TRANSPORT_ETHERNET = NetworkCapabilities.TRANSPORT_ETHERNET
    private val TRANSPORT_BLUETOOTH = NetworkCapabilities.TRANSPORT_BLUETOOTH
    private val TRANSPORT_WIFI_AWARE = NetworkCapabilities.TRANSPORT_WIFI_AWARE
    private val TRANSPORT_LOWPAN = NetworkCapabilities.TRANSPORT_LOWPAN
}
