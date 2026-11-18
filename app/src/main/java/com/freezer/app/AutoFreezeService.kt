package com.freezer.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * 自动冻结前台服务：
 * - 定时冻结 -> AlarmManager 周期闹钟
 * - 退出应用 -> UsageStats 轮询（需前台服务保活）
 * - 锁屏冻结 -> 已改由静态注册的 ScreenOffReceiver 处理（不再依赖本服务存活）
 */
class AutoFreezeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var usageStats: UsageStatsManager? = null
    private var lastForeground = ""
    private var running = false
    // SCREEN_OFF 广播：Android 8+ 静态注册收不到，必须动态注册（服务保活是前提）
    private var screenOffReceiver: BroadcastReceiver? = null
    // SCREEN_ON：解锁时取消"待执行的延迟锁屏冻结"，避免用户已解锁并开始使用
    // 应用后，闹钟仍到点把正在用的应用冻掉（这是"不连贯"的主因）
    private var screenOnReceiver: BroadcastReceiver? = null

    private val pollTask = object : Runnable {
        override fun run() {
            try { checkForeground() } catch (e: Exception) { Log.e("AutoFreeze", "poll", e) }
            if (running) handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 防御：前台化失败（如权限异常）时绝不崩进程，只停服并记日志
        try {
            startForeground(NOTI_ID, buildNotification())
        } catch (e: Exception) {
            AutoFreezeHelper.appendLog(this, "✗ 前台服务启动失败：${e.message}")
            stopSelf()
            return
        }
        usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        // 动态注册锁屏广播：Android 8+ 静态注册收不到 SCREEN_OFF，
        // 必须由常驻前台服务动态注册。服务由前台通知 + 电池优化白名单保活。
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
                if (!prefs.getBoolean("screenoff_enabled", false)) return
                val delay = prefs.getLong("screenoff_delay", 0)
                if (delay <= 0) {
                    AutoFreezeHelper.appendLog(this@AutoFreezeService, "锁屏事件已收到，立即执行冻结")
                    AutoFreezeHelper.freeze(this@AutoFreezeService, "锁屏冻结")
                } else {
                    AutoFreezeHelper.appendLog(this@AutoFreezeService, "锁屏事件已收到，${delay}秒后冻结（解锁则取消）")
                    scheduleOneShot(this@AutoFreezeService, delay * 1000, null, "锁屏冻结")
                }
            }
        }
        // 解锁广播：取消尚未触发的"锁屏延迟冻结"闹钟
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_ON) return
                cancelOneShot(ctx, null, "锁屏冻结")
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
        // EXPORTED：RECEIVER_NOT_EXPORTED 在部分系统上收不到系统广播（已知兼容性问题）
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_EXPORTED)
            registerReceiver(screenOnReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
            registerReceiver(screenOnReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
        }

        running = true
        handler.post(pollTask)
        scheduleTimer()
        AutoFreezeHelper.appendLog(this, "自动冻结服务已启动")
    }

    /** 轮询前台应用，检测白名单应用退到后台 -> 安排闹钟延迟冻结 */
    private fun checkForeground() {
        val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
        if (!prefs.getBoolean("exit_enabled", false)) return
        val fm = usageStats ?: return

        val end = System.currentTimeMillis()
        val stats = fm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 3000, end)
        if (stats.isNullOrEmpty()) return
        val current = stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: return

        val whitelist = prefs.getStringSet("whitelist", emptySet()) ?: emptySet()
        if (lastForeground in whitelist && current != lastForeground) {
            val target = lastForeground
            val delay = prefs.getLong("exit_delay", 10)
            scheduleOneShot(this, delay * 1000, target, "退出冻结")
        }
        lastForeground = current
    }

    /** 安排周期性定时冻结 */
    private fun scheduleTimer() {
        val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
        if (!prefs.getBoolean("timer_enabled", false)) return
        val interval = maxOf(1L, prefs.getLong("timer_interval", 30)) * 60 * 1000
        val intent = Intent(this, FreezeAlarmReceiver::class.java).apply {
            action = FreezeAlarmReceiver.ACTION_FREEZE
            putExtra(FreezeAlarmReceiver.EXTRA_REASON, "定时冻结")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, REQ_TIMER, intent, flags)
        val trigger = System.currentTimeMillis() + interval
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (e: Exception) { Log.e("AutoFreeze", "scheduleTimer", e) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "自动冻结服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("霜雪 · 自动冻结运行中")
            .setContentText("锁屏 / 退出应用后自动冻结已启用")
            .setSmallIcon(R.mipmap.ic_stat_freeze)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollTask)
        try { screenOffReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { screenOnReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        AutoFreezeHelper.appendLog(this, "自动冻结服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "autofreeze"
        const val NOTI_ID = 1001
        const val POLL_INTERVAL = 4000L
        const val REQ_TIMER = 2003

        /** 安排一次性精确冻结闹钟（供退出冻结 / 锁屏延迟冻结调用） */
        fun scheduleOneShot(ctx: Context, delayMs: Long, pkg: String?, reason: String) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, FreezeAlarmReceiver::class.java).apply {
                action = FreezeAlarmReceiver.ACTION_FREEZE
                putExtra(FreezeAlarmReceiver.EXTRA_PKG, pkg)
                putExtra(FreezeAlarmReceiver.EXTRA_REASON, reason)
            }
            val reqCode = if (pkg.isNullOrBlank()) 2001 else (2002 + pkg.hashCode())
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, reqCode, intent, flags)
            val trigger = System.currentTimeMillis() + maxOf(0L, delayMs)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                else
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (e: Exception) { Log.e("AutoFreeze", "scheduleOneShot", e) }
        }

        /** 取消一个尚未触发的一次性冻结闹钟（与 scheduleOneShot 的 reqCode 规则一致） */
        fun cancelOneShot(ctx: Context, pkg: String?, reason: String) {
            val reqCode = if (pkg.isNullOrBlank()) 2001 else (2002 + pkg.hashCode())
            val intent = Intent(ctx, FreezeAlarmReceiver::class.java).apply {
                action = FreezeAlarmReceiver.ACTION_FREEZE
                putExtra(FreezeAlarmReceiver.EXTRA_PKG, pkg)
                putExtra(FreezeAlarmReceiver.EXTRA_REASON, reason)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, reqCode, intent, flags)
            try {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            } catch (_: Exception) {}
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, AutoFreezeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AutoFreezeService::class.java))
    }
}
