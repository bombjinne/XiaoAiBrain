package com.xiaoaibrain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）申请助手（v1.0.26 新增）。
 *
 * 背景：Android 11 起启用分区存储，模块 App 想把配置写到公共
 * `/sdcard/Download/xiaoaibrain/xiaoaibrain_config.json` 让小爱进程
 * （另一个 uid）读取，必须持有「所有文件访问」权限，否则一律 EACCES。
 * v1.0.25 日志里持续出现 `ConfigFile 写入失败 EACCES` 就是这个原因——
 * 结果是机主在界面上设的模型/Key 根本没传给 hook 侧。
 *
 * 为什么不用运行时权限弹窗：MANAGE_EXTERNAL_STORAGE 是
 * **特殊权限（AppOps）**，系统不提供 requestPermissions 弹窗，
 * 唯一方式是跳转到系统设置页让用户手动开启。
 *
 * 版本差异：
 *   Android 11+ (R, API 30) → MANAGE_EXTERNAL_STORAGE + ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 *   Android 10  (Q, API 29) → 无需此权限，靠 manifest 的 requestLegacyExternalStorage=true
 *   Android 9 及以下        → READ/WRITE_EXTERNAL_STORAGE 运行时权限即可
 */
object StoragePerm {

    /** 是否已持有「所有文件访问」权限（低版本恒为 true） */
    fun granted(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true
    } catch (_: Throwable) {
        false
    }

    /**
     * 构造跳转系统「所有文件访问」设置页的 Intent。
     * 返回 null 表示当前系统版本不需要/无法跳（低版本自动视为已授权）。
     */
    fun settingsIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            // 直接跳本应用的权限页（体验最好，用户只需点一个开关）
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", ctx.packageName, null)
            )
        } catch (_: Throwable) {
            try {
                // 兜底：跳系统级的"所有文件访问"列表页
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            } catch (_: Throwable) { null }
        }
    }

    /** 低版本（≤ Android 10）需要的普通存储权限；高版本返回空数组 */
    fun legacyRuntimePermissions(): Array<String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) emptyArray()
        else arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } catch (_: Throwable) { emptyArray() }
}