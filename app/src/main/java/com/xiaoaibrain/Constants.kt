package com.xiaoaibrain

/** 全局常量 */
object Constants {
    /** 模块自身包名 */
    const val PKG = "com.xiaoaibrain"

    /**
     * 目标 App：AI 通话（用户指定——hook 点只要这一个包）。
     * AutoJS 实证：AI 通话界面（小爱帮我回/通话转文字）所属包为 com.xiaomi.aiasst.service。
     */
    const val TARGET_PKG = "com.xiaomi.aiasst.service"

    /** 目标包集合（与 TARGET_PKG 一致，仅 AI 通话） */
    val TARGET_PKGS = setOf("com.xiaomi.aiasst.service")

    /** logcat 日志 TAG */
    const val TAG = "XiaoAiBrain"

    /** hook 日志广播 Action（显式定向本模块，LogReceiver 接收） */
    const val ACTION_HOOK_LOG = "com.xiaoaibrain.HOOK_LOG"
}
