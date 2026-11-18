package com.freezer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * 锁屏广播接收器（静态注册）。
 *
 * 关键：旧版把 SCREEN_OFF 动态注册在服务里，进程一被系统冻结就收不到广播，
 * 导致"锁屏冻结"完全不触发。改为 AndroidManifest 静态注册，系统会在锁屏时
 * 直接拉起本接收器（配合 WakeLock + AlarmManager），不依赖 App 进程存活。
 */
class ScreenOffReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        val prefs = ctx.getSharedPreferences("autofreeze", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("screenoff_enabled", false)) return

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freezer:screenoff")
        wl.acquire(10_000L)

        val delay = prefs.getLong("screenoff_delay", 0)
        val pending = goAsync()
        try {
            if (delay <= 0) {
                // 零延迟：直接执行（helper 内部会等待 Shizuku 重连）
                AutoFreezeHelper.appendLog(ctx, "锁屏事件已收到，立即执行冻结")
                AutoFreezeHelper.freeze(ctx, "锁屏冻结")
            } else {
                // 有延迟：用 AlarmManager 精确闹钟，交给广播执行
                AutoFreezeHelper.appendLog(ctx, "锁屏事件已收到，${delay}秒后冻结")
                AutoFreezeService.scheduleOneShot(ctx, delay * 1000, null, "锁屏冻结")
            }
        } finally {
            // goAsync 需要在后台线程 finish，这里用短延时避免过早结束
            Thread {
                try { Thread.sleep(800) } catch (_: Exception) {}
                if (wl.isHeld) wl.release()
                pending.finish()
            }.start()
        }
    }
}
