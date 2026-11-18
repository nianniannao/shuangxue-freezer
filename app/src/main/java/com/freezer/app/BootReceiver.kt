package com.freezer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后若开启了自动冻结，则拉起服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            val prefs = ctx.getSharedPreferences("autofreeze", Context.MODE_PRIVATE)
            val any = prefs.getBoolean("screenoff_enabled", false) ||
                      prefs.getBoolean("exit_enabled", false) ||
                      prefs.getBoolean("timer_enabled", false)
            if (any) try {
                AutoFreezeService.start(ctx)
            } catch (_: Exception) {}
        }
    }
}
