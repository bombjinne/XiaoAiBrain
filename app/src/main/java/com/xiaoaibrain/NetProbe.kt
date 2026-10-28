package com.xiaoaibrain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.InetAddress
import java.net.URL

/**
 * 网络诊断探针：打印当前进程可见的网络拓扑、DNS 解析、以及对目标主机的真实 TCP 连接测试。
 * 用于回答「为什么某进程访问外网会超时」——DNS 通 ≠ TCP 通 ≠ HTTPS 通。
 */
object NetProbe {

    private val NET_INTERNET = NetworkCapabilities.NET_CAPABILITY_INTERNET
    private val NET_VALIDATED = NetworkCapabilities.NET_CAPABILITY_VALIDATED
    private val TRANSPORT_VPN = NetworkCapabilities.TRANSPORT_VPN
    private val TRANSPORT_WIFI = NetworkCapabilities.TRANSPORT_WIFI
    private val TRANSPORT_CELLULAR = NetworkCapabilities.TRANSPORT_CELLULAR
    private val TRANSPORT_ETHERNET = NetworkCapabilities.TRANSPORT_ETHERNET

    fun probe(ctx: Context?, baseUrl: String, tag: String = "[NET]") {
        if (ctx == null) { XLog.w("$tag probe: 无 Context，跳过"); return }
        val cm = try {
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Throwable) { null }
        if (cm == null) { XLog.w("$tag 取 ConnectivityManager 失败"); return }

        try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            XLog.i("$tag active=$active transports=${transports(caps)} vpn=${caps?.hasTransport(TRANSPORT_VPN)}")
            val nets = cm.allNetworks
            XLog.i("$tag 共 ${nets.size} 个网络:")
            nets.forEachIndexed { i, n ->
                val c = cm.getNetworkCapabilities(n)
                XLog.i("$tag   [$i] $n internet=${c?.hasCapability(NET_INTERNET)} validated=${c?.hasCapability(NET_VALIDATED)} vpn=${c?.hasTransport(TRANSPORT_VPN)} transports=${transports(c)}")
            }
        } catch (e: Throwable) { XLog.w("$tag 枚举网络异常: ${e.message}") }

        val host = try { URL(baseUrl).host } catch (e: Throwable) { null }
        if (host == null) { XLog.w("$tag baseUrl 无法解析 host: $baseUrl"); return }

        try {
            val t0 = SystemClock.elapsedRealtime()
            val addrs = InetAddress.getAllByName(host)
            val cost = SystemClock.elapsedRealtime() - t0
            XLog.i("$tag DNS[$host] → ${addrs.size} 个IP (${cost}ms): ${addrs.joinToString { it.hostAddress ?: "?" }}")
        } catch (e: Throwable) {
            XLog.e("$tag DNS 解析失败[$host]: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        try {
            val t0 = SystemClock.elapsedRealtime()
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, 443), 5000) }
            val cost = SystemClock.elapsedRealtime() - t0
            XLog.i("$tag TCP connect $host:443 成功 (${cost}ms)")
        } catch (e: Throwable) {
            XLog.e("$tag TCP connect $host:443 失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun transports(c: NetworkCapabilities?): String {
        if (c == null) return "null"
        val list = mutableListOf<String>()
        if (c.hasTransport(TRANSPORT_WIFI)) list.add("WIFI")
        if (c.hasTransport(TRANSPORT_CELLULAR)) list.add("CELLULAR")
        if (c.hasTransport(TRANSPORT_VPN)) list.add("VPN")
        if (c.hasTransport(TRANSPORT_ETHERNET)) list.add("ETHERNET")
        return list.joinToString(",")
    }
}
