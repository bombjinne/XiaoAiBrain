package com.xiaoaibrain

/**
 * 多轮对话上下文：按 requestId（hook 参数）维护同一通电话的问答历史。
 * 数据只存在 hook 进程内存里，重启小爱即清空——对 V1 够用。
 */
object CallSession {

    private val sessions = HashMap<String, ArrayDeque<Pair<String, String>>>()
    private const val MAX_PER_SESSION = 8

    @Synchronized
    fun push(requestId: String, q: String, a: String) {
        val key = requestId.ifEmpty { "default" }
        val dq = sessions.getOrPut(key) { ArrayDeque<Pair<String, String>>() }
        dq.addLast(q.take(120) to a.take(200))
        while (dq.size > MAX_PER_SESSION) {
            dq.removeFirst()
        }
    }

    @Synchronized
    fun history(requestId: String, turns: Int): List<Pair<String, String>> {
        val dq = sessions[requestId.ifEmpty { "default" }] ?: return emptyList()
        if (turns <= 0) return emptyList()
        return dq.toList().takeLast(turns.coerceAtMost(MAX_PER_SESSION))
    }

    @Synchronized
    fun clear(requestId: String) {
        sessions.remove(requestId.ifEmpty { "default" })
    }

    @Synchronized
    fun clearAll() {
        sessions.clear()
    }
}
