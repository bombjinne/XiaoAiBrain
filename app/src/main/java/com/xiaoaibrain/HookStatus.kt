package com.xiaoaibrain

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Hook 状态跨进程通道（v1.0.24 新增）。
 *
 * 需求：配置页版本号上方显示「hook 成功还是失败」。
 *
 * 为什么用广播而不是读文件：
 *  hook 跑在小爱进程（com.miui.aiasst.service，另一个 uid），它写的文件在
 *  Android 13 作用域存储下模块 App 读不到（公共 Download 属他人文件、
 *  Android/data/<小爱> 是它的私有目录）。所以 hook 侧【主动广播】状态到
 *  模块进程（显式广播 + setPackage，后台也能送达 manifest 接收器），
 *  ChatReceiver 收到后持久化进 SharedPreferences，配置页随时可读、重启不丢。
 *
 * 线程模型：广播发送走专用后台线程——hook 进程的主线程（小爱通话界面）
 * 绝不被 IPC 阻塞（v1.0.17 广播风暴教训的镜像防护）。
 */
object HookStatus {

    const val ACTION = "com.xiaoaibrain.HOOK_STATUS"

    const val STATE_OK = "OK"
    const val STATE_ERROR = "ERROR"

    private const val PREFS = "xiaoaibrain_hook_status"
    private const val KEY_STATE = "state"
    private const val KEY_VERSION = "version"
    private const val KEY_TIME = "time"
    private const val KEY_MSG = "msg"

    data class Snap(val state: String, val version: String, val time: Long, val msg: String)

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HookStatusExec").apply { isDaemon = true }
    }

    /** hook 进程调用：后台线程发送状态广播到模块 App */
    fun broadcast(ctx: Context, state: String, version: String, msg: String = "") {
        exec.execute {
            try {
                ctx.sendBroadcast(
                    Intent(ACTION)
                        .putExtra("state", state)
                        .putExtra("version", version)
                        .putExtra("time", System.currentTimeMillis())
                        .putExtra("msg", msg)
                        .setPackage(Constants.PKG)
                )
            } catch (_: Throwable) {
                // 广播失败不影响 hook 主流程（配置页会显示"未检测到"）
            }
        }
    }

    /** UI 进程：收到广播后持久化，供下次打开立即可见 */
    fun save(ctx: Context, state: String, version: String, time: Long, msg: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATE, state)
                .putString(KEY_VERSION, version)
                .putLong(KEY_TIME, time)
                .putString(KEY_MSG, msg)
                .apply()
        } catch (_: Throwable) { }
    }

    /** UI 进程：读取上次已知状态；从未收到过广播返回 null（= 未检测到 hook） */
    fun load(ctx: Context): Snap? {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val st = p.getString(KEY_STATE, null) ?: return null
            Snap(
                state = st,
                version = p.getString(KEY_VERSION, "") ?: "",
                time = p.getLong(KEY_TIME, 0L),
                msg = p.getString(KEY_MSG, "") ?: ""
            )
        } catch (_: Throwable) {
            null
        }
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun formatTime(t: Long): String = if (t <= 0L) "未知时间" else fmt.format(Date(t))
}
