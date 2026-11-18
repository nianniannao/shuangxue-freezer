package com.freezer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * 接收 AlarmManager 的精确闹钟，在 WakeLock 保护下执行自动冻结。
 * 实际逻辑全部委托给 AutoFreezeHelper（含 Shizuku 冷启动等待 + 日志）。
 */
class FreezeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_FREEZE) return
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "定时冻结"

        // 锁屏延迟冻结的闹钟触发时，若屏幕已点亮（用户中途解锁并开始使用），
        // 则跳过本次冻结——避免把用户正在用的应用冻掉，保证操作连贯。
        if (reason == "锁屏冻结" && isScreenOn(ctx)) {
            AutoFreezeHelper.appendLog(ctx, "· 锁屏冻结已取消：检测到屏幕已点亮")
            return
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freezer:alarm")
        wl.acquire(10_000L)

        AutoFreezeHelper.freeze(ctx, reason, pkg)

        Thread {
            try { Thread.sleep(800) } catch (_: Exception) {}
            if (wl.isHeld) wl.release()
        }.start()
    }

    companion object {
        const val ACTION_FREEZE = "com.freezer.app.FREEZE_ALARM"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_REASON = "reason"

        private fun isScreenOn(ctx: Context): Boolean = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Exception) { false }
    }
}
