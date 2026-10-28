package com.xiaoaibrain

/**
 * 从日志文件重建聊天记录（v1.0.12 兜底方案）。
 *
 * 问题背景：ChatBus/LogReceiver 靠广播把消息送到配置页，但 hook 发生在「AI通话」进程，
 * 通话时配置页多在后台 → Android 8+ 后台广播受限，消息收不到，
 * 结果"挂了电话很久才显示出聊天记录"（甚至要回到前台才刷新）。
 *
 * 但 hook 侧每一步都写了日志，且日志一定落盘（FileLogger），所以：
 * **直接解析日志文件里这几类行，即可完整还原聊天记录，不依赖任何跨进程通信。**
 *
 * 识别的日志行（hook 侧稳定格式）：
 *   ★A1 检测到新通话，已清空聊天上下文                → 分隔一通电话
 *   ★A1 对方已说完(final=…)，对「…」调 LLM           → 对方说的话
 *   ★A1 LLM 生成完成(携带上下文N轮): 「…」           → AI 的回复
 *   ★A5 通话对象: 号码=「…」备注名=「…」            → 通话对象
 */
object ChatLogParser {

    private const val TAG_NEW_CALL = "★A1 检测到新通话"
    private const val TAG_PEER = "★A1 对方已说完"
    private const val TAG_AI = "★A1 LLM 生成完成"
    private const val TAG_CALLER = "★A5 通话对象: 号码="
    private const val TAG_SYS = "── 新通话 ──"

    // 注意：必须 5 个捕获组（MM DD HH MM SS），与下方 destructured 的 5 个变量一一对应。
    // 旧实现写成 (\d{2}-\d{2}) 合在 1 组（仅 4 组），解构成 5 个变量会抛
    // IndexOutOfBoundsException: No group 5，且 buildChatItems 在 dataIo 上 execute()
    // 未捕获异常 → Android 直接杀进程（闪退）。
    private val RE_TIME = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")
    private val RE_PEER = Regex("""对「(.*)」调 LLM""")
    private val RE_AI = Regex("""\):\s*「(.*)」$""")
    private val RE_NUM = Regex("""号码=「(.*?)」""")
    private val RE_NAME = Regex("""备注名=「(.*?)」""")

    /** 解析结果 */
    data class Result(
        val messages: List<ChatBus.ChatMsg>,
        val callerName: String?,
        val callerNumber: String?
    )

    /**
     * 解析日志行列表 → 聊天消息列表。
     * 只取【最后一通电话】的内容（遇到"检测到新通话"就清空，符合界面预期）。
     */
    fun parse(lines: List<String>): Result {
        val msgs = ArrayList<ChatBus.ChatMsg>()
        var name: String? = null
        var number: String? = null

        for (line in lines) {
            // 时间戳：MM-dd HH:mm:ss.SSS
            var ts = System.currentTimeMillis()
            RE_TIME.find(line)?.let { m ->
                try {
                    val (mm, dd, hh, mi, ss) = m.destructured
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.MONTH, mm.toInt() - 1)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, dd.toInt())
                    cal.set(java.util.Calendar.HOUR_OF_DAY, hh.toInt())
                    cal.set(java.util.Calendar.MINUTE, mi.toInt())
                    cal.set(java.util.Calendar.SECOND, ss.toInt())
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    ts = cal.timeInMillis
                } catch (_: Throwable) { }
            }

            when {
                // 新通话 → 清空，重新开始
                line.contains(TAG_NEW_CALL) -> {
                    msgs.clear()
                    msgs.add(ChatBus.ChatMsg(ChatBus.ROLE_SYS, "新通话", ts))
                    // 通话对象在新通话之后才出现，不在此清name/number（沿用同一通的值）
                }

                line.contains(TAG_CALLER) -> {
                    val n = RE_NUM.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" }
                    val nm = RE_NAME.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" }
                    if (n != null) number = n
                    if (nm != null) name = nm
                }

                line.contains(TAG_PEER) -> {
                    val t = RE_PEER.find(line)?.groupValues?.getOrNull(1)?.trim()
                    if (!t.isNullOrEmpty()) {
                        msgs.add(ChatBus.ChatMsg(ChatBus.ROLE_PEER, t, ts, name, number))
                    }
                }

                line.contains(TAG_AI) -> {
                    val t = RE_AI.find(line.trim())?.groupValues?.getOrNull(1)?.trim()
                    if (!t.isNullOrEmpty()) {
                        msgs.add(ChatBus.ChatMsg(ChatBus.ROLE_AI, t, ts, name, number))
                    }
                }
            }
        }
        return Result(msgs, name, number)
    }
}
