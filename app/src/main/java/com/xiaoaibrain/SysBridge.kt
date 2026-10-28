package com.xiaoaibrain

import android.content.Context
import de.robv.android.xposed.XposedHelpers

/**
 * 从小爱进程内获取 Application Context（用于发广播等）。
 * hook 代码运行在小爱进程里，没有自己的 Context，只能借宿主的。
 */
object SysBridge {

    fun currentApplication(): Context? {
        return try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread") ?: return null
            XposedHelpers.callMethod(at, "getApplication") as? Context
        } catch (e: Throwable) {
            null
        }
    }
}
