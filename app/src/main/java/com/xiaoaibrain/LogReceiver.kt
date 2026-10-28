package com.xiaoaibrain

// v1.0.17 起已禁用并移出 Manifest：
//   - 原职责（接收 hook 进程日志广播）已被删除 —— XLog.emit 不再广播，诊断日志改由
//     FileLogger 落盘、LogFileReader 轮询读取共享文件，LogReceiver 跑在主线程反而
//     是"广播风暴淹没主线程 → 点不动按钮"的根因之一。
//   - 现在无人发送 ACTION_HOOK_LOG，本类不会再被实例化，保留文件仅为说明，不影响编译。
