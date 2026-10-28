package com.xiaoaibrain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 聊天记录持久化（v1.0.23 新增）。
 *
 * 背景：v1.0.22 实测"通话后聊天框空白"——hook 侧日志与广播都有内容，
 * 但配置页进程若是通话【结束后】才打开（全新进程），
 *   ① 内存 ChatBus.messages 为空（广播只进当时存活的进程）；
 *   ② 日志文件在小爱进程自己的目录 / 公共 Download，本模块 App 在
 *      Android 13 无存储权限时读不到 → 日志兜底解析也拿不到。
 *
 * 解决：ChatReceiver 收到广播时【同步落盘】到本模块 App 私有目录
 * （无需任何权限，本进程随时可读）。配置页刷新时合并三个来源：
 *   广播内存（最新） → 持久化（含重启前的完整历史） → 日志文件（兜底）
 *
 * 显式广播（setPackage）即使配置页进程已死，框架也会把它拉起来投递，
 * 所以通话中 App 完全没打开，历史同样能落盘、下次打开即可回显。
 */
object ChatStore {

    private const val NAME = "chat_history.json"
    private const val MAX_MSGS = 200

    private val lock = Any()

    private fun file(ctx: Context): File = File(ctx.filesDir, NAME)

    /** 一条持久化消息 */
    data class Msg(val role: String, val text: String, val time: Long)

    /** 读取全部消息（无则空列表） */
    fun load(ctx: Context): List<Msg> = synchronized(lock) {
        return try {
            val f = file(ctx)
            if (!f.isFile || f.length() == 0L) return emptyList()
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("msgs") ?: return emptyList()
            val out = ArrayList<Msg>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role")
                val text = m.optString("text")
                if (role.isEmpty() || text.isEmpty()) continue
                out.add(Msg(role, text, m.optLong("time")))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 读取通话对象（号码/备注名），没有返回 null */
    fun callerName(ctx: Context): String? = synchronized(lock) { readCaller(ctx).first }
    fun callerNumber(ctx: Context): String? = synchronized(lock) { readCaller(ctx).second }

    private fun readCaller(ctx: Context): Pair<String?, String?> {
        return try {
            val f = file(ctx)
            if (!f.isFile || f.length() == 0L) return null to null
            val obj = JSONObject(f.readText())
            val n = obj.optString("callerName").takeIf { it.isNotEmpty() && it != "null" }
            val p = obj.optString("callerNumber").takeIf { it.isNotEmpty() && it != "null" }
            n to p
        } catch (_: Throwable) {
            null to null
        }
    }

    /** 追加一条消息（超量丢弃最旧） */
    fun append(ctx: Context, role: String, text: String, time: Long) {
        synchronized(lock) {
            try {
                val f = file(ctx)
                val obj = if (f.isFile && f.length() > 0) {
                    try { JSONObject(f.readText()) } catch (_: Throwable) { JSONObject() }
                } else JSONObject()
                val arr = obj.optJSONArray("msgs") ?: JSONArray()
                arr.put(JSONObject().put("role", role).put("text", text).put("time", time))
                while (arr.length() > MAX_MSGS) arr.remove(0)
                obj.put("msgs", arr)
                f.writeText(obj.toString())
            } catch (_: Throwable) { }
        }
    }

    /** 记录通话对象 */
    fun setCaller(ctx: Context, name: String?, number: String?) {
        synchronized(lock) {
            try {
                val f = file(ctx)
                val obj = if (f.isFile && f.length() > 0) {
                    try { JSONObject(f.readText()) } catch (_: Throwable) { JSONObject() }
                } else JSONObject()
                if (!name.isNullOrEmpty()) obj.put("callerName", name)
                if (!number.isNullOrEmpty()) obj.put("callerNumber", number)
                if (!obj.has("msgs")) obj.put("msgs", JSONArray())
                f.writeText(obj.toString())
            } catch (_: Throwable) { }
        }
    }

    /** 清空（界面"清空"按钮 / 新通话分隔） */
    fun clear(ctx: Context) {
        synchronized(lock) {
            try {
                file(ctx).writeText(JSONObject().put("msgs", JSONArray()).toString())
            } catch (_: Throwable) { }
        }
    }
}
