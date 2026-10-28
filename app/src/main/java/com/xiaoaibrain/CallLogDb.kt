package com.xiaoaibrain

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一通电话的元信息（列表项） */
data class CallRecord(
    val id: Long,
    val number: String,
    val name: String?,
    val startTime: Long,
    val endTime: Long,
    val msgCount: Int
) {
    /** 展示用标题：优先通讯录备注名，其次号码，"未知号码"兜底 */
    fun title(): String = when {
        !name.isNullOrBlank() && number.isNotBlank() && name != number -> "$name（$number）"
        !name.isNullOrBlank() -> name!!
        number.isNotBlank() -> number
        else -> "未知号码"
    }
}

/** 一条消息 */
data class MsgRecord(val role: String, val text: String, val time: Long)

/**
 * 通话记录数据库（v1.0.26 新增）。
 *
 * 为什么要上数据库：v1.0.23~25 用 ChatStore（一个 JSON 文件）存聊天记录，
 * 只能存"最近一通"的对话，且无法按通话人/时间检索。通话记录是需要
 * **长期留存 + 可检索** 的数据（对方号码、通讯录备注名、通话时间、通话内容），
 * 因此改用 SQLite：两张表（通话 + 消息），支持按号码/备注名模糊筛选、按时间范围筛选。
 *
 * 表结构：
 *   calls     id / number / name / start_time / end_time
 *   messages  id / call_id / role / text / time
 *
 * 写入方：ChatReceiver（收到小爱进程广播时，goAsync 后台线程写入，
 *         因此配置页没打开也能落库）。
 * 读取方：配置页「历史通话」板块（按需查询，非 2 秒轮询路径）。
 */
class CallLogDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "xiaoaibrain_calls.db"
        private const val VERSION = 1
        private const val T_CALLS = "calls"
        private const val T_MSG = "messages"

        /** 同一通电话的判定窗口：5 分钟内的消息视为同一通 */
        private const val SAME_CALL_WINDOW_MS = 5 * 60 * 1000L

        @Volatile private var instance: CallLogDb? = null
        private val lock = Any()

        fun get(context: Context): CallLogDb {
            instance?.let { return it }
            return synchronized(lock) {
                instance ?: CallLogDb(context).also { instance = it }
            }
        }

        /** 时间范围筛选的预设 */
        val RANGES = listOf(
            0L to "全部时间",
            24L * 3600_000L to "最近 1 天",
            7L * 24 * 3600_000L to "最近 7 天",
            30L * 24 * 3600_000L to "最近 30 天"
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_CALLS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT NOT NULL DEFAULT '',
                name TEXT,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_MSG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                call_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                time INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_call ON $T_MSG(call_id)")
        db.execSQL("CREATE INDEX idx_call_time ON $T_CALLS(start_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_MSG")
        db.execSQL("DROP TABLE IF EXISTS $T_CALLS")
        onCreate(db)
    }

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 确保存在"当前通话"记录：若最近一通在 SAME_CALL_WINDOW_MS 内就复用，
     * 否则新建一通。返回 call_id。
     */
    fun ensureCall(number: String?, name: String?, time: Long): Long {
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, number, name FROM $T_CALLS ORDER BY start_time DESC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val lastStart = latestTimeOf(db, id)
                if (time - lastStart < SAME_CALL_WINDOW_MS) {
                    // 同一通：补齐通话对象信息（号码/备注名可能晚于首条消息到达）
                    if (!number.isNullOrBlank() || !name.isNullOrBlank()) {
                        updateCaller(db, id, number, name)
                    }
                    return id
                }
            }
        }
        return insertCall(db, number, name, time)
    }

    /** 显式新建一通电话（收到"新通话"分隔事件时调用） */
    fun startCall(number: String?, name: String?, time: Long): Long =
        insertCall(writableDatabase, number, name, time)

    private fun insertCall(db: SQLiteDatabase, number: String?, name: String?, time: Long): Long {
        val cv = ContentValues().apply {
            put("number", number ?: "")
            put("name", name)
            put("start_time", time)
            put("end_time", time)
        }
        return db.insert(T_CALLS, null, cv)
    }

    private fun updateCaller(db: SQLiteDatabase, id: Long, number: String?, name: String?) {
        val cv = ContentValues()
        if (!number.isNullOrBlank()) cv.put("number", number)
        if (!name.isNullOrBlank()) cv.put("name", name)
        if (cv.size() == 0) return
        db.update(T_CALLS, cv, "id=?", arrayOf(id.toString()))
    }

    /** 追加一条消息，并顺带刷新该通话的 end_time */
    fun appendMessage(callId: Long, role: String, text: String, time: Long) {
        val db = writableDatabase
        db.insert(T_MSG, null, ContentValues().apply {
            put("call_id", callId)
            put("role", role)
            put("text", text)
            put("time", time)
        })
        db.execSQL("UPDATE $T_CALLS SET end_time=? WHERE id=?", arrayOf<Any>(time, callId))
    }

    /** 该通话的最后一条消息时间（没有消息则回落到 start_time） */
    private fun latestTimeOf(db: SQLiteDatabase, callId: Long): Long {
        db.rawQuery("SELECT MAX(time) FROM $T_MSG WHERE call_id=?", arrayOf(callId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        db.rawQuery("SELECT start_time FROM $T_CALLS WHERE id=?", arrayOf(callId.toString())).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 按条件查询通话列表。
     * @param keyword  号码或备注名模糊匹配（空 = 全部）
     * @param sinceMs  只返回 start_time >= sinceMs 的（0 = 不限）
     * @param callerId 指定通话人号码精确匹配（null = 不限），用于下拉筛选
     */
    fun listCalls(
        keyword: String? = null,
        sinceMs: Long = 0L,
        callerId: String? = null,
        limit: Int = 200
    ): List<CallRecord> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (!keyword.isNullOrBlank()) {
            where.add("(number LIKE ? OR name LIKE ?)")
            args.add("%$keyword%")
            args.add("%$keyword%")
        }
        if (sinceMs > 0) {
            where.add("start_time >= ?")
            args.add(sinceMs.toString())
        }
        if (!callerId.isNullOrBlank()) {
            where.add("number = ?")
            args.add(callerId)
        }
        val sql = buildString {
            append("SELECT c.id, c.number, c.name, c.start_time, c.end_time, ")
            append("(SELECT COUNT(*) FROM $T_MSG m WHERE m.call_id = c.id) AS cnt ")
            append("FROM $T_CALLS c ")
            if (where.isNotEmpty()) append("WHERE ").append(where.joinToString(" AND ")) .append(' ')
            append("ORDER BY c.start_time DESC LIMIT $limit")
        }
        val out = ArrayList<CallRecord>()
        try {
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CallRecord(
                            id = c.getLong(0),
                            number = c.getString(1).orEmpty(),
                            name = c.getString(2),
                            startTime = c.getLong(3),
                            endTime = c.getLong(4),
                            msgCount = c.getInt(5)
                        )
                    )
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    /** 某通电话的全部消息（按时间正序） */
    fun messagesOf(callId: Long): List<MsgRecord> {
        val out = ArrayList<MsgRecord>()
        try {
            readableDatabase.rawQuery(
                "SELECT role, text, time FROM $T_MSG WHERE call_id=? ORDER BY time ASC",
                arrayOf(callId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(MsgRecord(c.getString(0), c.getString(1), c.getLong(2)))
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    /** 全部通话人（号码 + 备注名），去重后按最近通话时间排序，供下拉筛选 */
    fun allCallers(): List<CallRecord> = listCalls(limit = 500)
        .filter { it.number.isNotBlank() }
        .distinctBy { it.number }
        .sortedByDescending { it.endTime }

    /** 历史记录总条数（用于空态判断） */
    fun totalCalls(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_CALLS", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (_: Throwable) { 0 }

    /** 清空全部通话历史 */
    fun clearAll() {
        try {
            val db = writableDatabase
            db.delete(T_MSG, null, null)
            db.delete(T_CALLS, null, null)
        } catch (_: Throwable) { }
    }

    /** 删除某通电话及其消息 */
    fun deleteCall(callId: Long) {
        try {
            val db = writableDatabase
            db.delete(T_MSG, "call_id=?", arrayOf(callId.toString()))
            db.delete(T_CALLS, "id=?", arrayOf(callId.toString()))
        } catch (_: Throwable) { }
    }
}