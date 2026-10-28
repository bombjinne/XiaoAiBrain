package com.xiaoaibrain

import java.util.Collections

/**
 * hook 日志总线（v1.0.17 精简）：
 *  - 跨进程日志推送（广播）已在 v1.0.17 删除 —— 原 HookLogBus.post() 每打一条日志
 *    就向 UI 主线程发一次广播，是"UI 卡死/点不动按钮"的根因之一。
 *  - 诊断日志现在统一由 FileLogger 落盘，配置页通过 LogFileReader 轮询读取共享文件。
 *  - 本对象现在只保留 UI 进程本地缓冲（如"测试连接"结果），由 MainActivity 轮询渲染，
 *    不再依赖任何广播。LogReceiver 已从 Manifest 移除。
 */
object HookLogBus {

    const val EXTRA_LINE = "line"   // 保留常量，避免其它引用编译报错（现已无人发送）

    /** UI 进程内存缓冲（仅在配置页进程有效） */
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private const val MAX_LINES = 300

    /** 在 UI 进程调用：直接入缓冲（如测试连接结果），由轮询渲染 */
    fun appendLocal(line: String) {
        lines.add(line)
        if (lines.size > MAX_LINES) {
            lines.subList(0, lines.size - MAX_LINES).clear()
        }
    }
}
