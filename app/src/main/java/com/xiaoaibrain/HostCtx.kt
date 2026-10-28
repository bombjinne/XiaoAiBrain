package com.xiaoaibrain

import android.content.Context

/**
 * 宿主进程全局 Context 持有者。
 * 在 Application.attach 时注入（见 HookEntry.hookAppAttach）。
 * 用途：给 LlmClient 提供 ConnectivityManager，把 LLM 请求强制绑到底层蜂窝/Wi-Fi 网卡，
 *      绕开小爱通话为传语音而建立的「按应用 VPN/专线网络」——该 VPN 会把我们发往
 *      maas.qianwenaiapi.com 的请求吞掉导致 5s 超时（同一 URL 在 XiaoAiBrain App 进程却 1s 成功）。
 */
object HostCtx {
    @Volatile
    var ctx: Context? = null
}
