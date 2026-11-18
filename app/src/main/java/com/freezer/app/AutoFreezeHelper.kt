package com.freezer.app

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/**
 * 自动冻结统一执行器。
 * 解决的核心 bug：AlarmManager 冷启动 Receiver 时，Shizuku 的 binder 由
 * ShizukuProvider 异步初始化，立刻 pingBinder() 往往是 false，导致 bestMode()
 * 误判为"未激活(MODE_NONE)"而静默 return —— 这正是"锁屏不冻结"的元凶。
 *
 * 这里在冻结前主动等待 binder 就绪，并把每次执行结果写入持久化日志，便于自查。
 */
object AutoFreezeHelper {

    private const val TAG = "AutoFreeze"
    private const val PREF_DIAG = "diag"
    private const val KEY_LOG = "freeze_log"

    /** 等待 Shizuku binder 就绪（冷启动时最多等 3 秒） */
    private fun waitForShizuku(): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (FreezerEngine.isShizukuGranted()) return true
            Thread.sleep(150)
        }
        return FreezerEngine.isShizukuGranted()
    }

    /** 解析当前模式：冷启动时对 Shizuku 做等待重连，避免误判为未激活 */
    private fun resolveMode(ctx: Context): Int {
        // 已存的模式优先（用户明确选的），避免 bestMode 在冷启动时误判
        val saved = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("mode", FreezerEngine.MODE_NONE)

        if (FreezerEngine.isDeviceOwner(ctx)) return FreezerEngine.MODE_DEVICE_OWNER
        if (FreezerEngine.isRootAvailable()) return FreezerEngine.MODE_ROOT

        // Shizuku：无论保存的是什么模式，都尝试等待 binder 就绪
        if (waitForShizuku()) return FreezerEngine.MODE_SHIZUKU

        // 兜底再走一次 bestMode
        val m = FreezerEngine.bestMode(ctx)
        return if (m != FreezerEngine.MODE_NONE) m else saved
    }

    /**
     * 冻结冻结列表（白名单）。
     * @param pkg 为空表示冻结整个列表；非空表示只冻结单个
     */
    fun freeze(ctx: Context, reason: String, pkg: String? = null) {
        thread {
            val wl = try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freezer:auto")
                w.acquire(50_000L)
                w
            } catch (e: Exception) {
                null
            }

            try {
                val mode = resolveMode(ctx)
                if (mode == FreezerEngine.MODE_NONE) {
                    appendLog(ctx, "✗ $reason｜失败：没有可用授权模式（Shizuku 未就绪/未授权）")
                    Log.w(TAG, "$reason: MODE_NONE")
                    return@thread
                }

                val whitelist = ctx.getSharedPreferences("autofreeze", Context.MODE_PRIVATE)
                    .getStringSet("whitelist", emptySet()) ?: emptySet()
                val targets = if (pkg.isNullOrBlank())
                    whitelist.filter { it != ctx.packageName }
                else listOf(pkg)

                if (targets.isEmpty()) {
                    appendLog(ctx, "· $reason｜跳过：冻结列表为空")
                    return@thread
                }

                var ok = 0
                val failed = mutableListOf<String>()
                for (p in targets) {
                    val r = FreezerEngine.freeze(ctx, p, mode)
                    if (r.isSuccess) ok++ else failed.add(p)
                }
                val modeName = FreezerEngine.modeName(mode)

                // 复查：命令成功 ≠ 真的冻上了（应用更新中/系统改回等都会让状态落空）。
                // 逐个反查真实冻结状态，未冻上的自动重试一次，仍失败则记名。
                val verifiedFailed = mutableListOf<String>()
                for (p in targets) {
                    if (!FreezerEngine.isFrozen(ctx, p, mode)) {
                        if (FreezerEngine.freeze(ctx, p, mode).isSuccess && !FreezerEngine.isFrozen(ctx, p, mode)) {
                            verifiedFailed.add(p)
                        }
                    }
                }
                if (verifiedFailed.isNotEmpty()) {
                    // 全量重试后仍未冻上的，把应用名列出来
                    val names = verifiedFailed.joinToString("、") { appLabel(ctx, it) }
                    appendLog(ctx, "复查｜${targets.size - verifiedFailed.size}/${targets.size} 已确认冻结，未冻上：$names（可能正在更新或被系统改回）")
                }
                if (failed.isNotEmpty()) {
                    val names = failed.joinToString("、") { appLabel(ctx, it) }
                    appendLog(ctx, "△ 执行报错 ${failed.size} 个：$names")
                }

                val result = if (failed.isEmpty())
                    "✓ $reason｜成功 $ok/${targets.size}（$modeName）"
                else
                    "△ $reason｜成功 $ok/${targets.size}，失败 ${failed.size}（$modeName）"
                appendLog(ctx, result)
                Log.d(TAG, result)
            } catch (e: Throwable) {
                appendLog(ctx, "✗ $reason｜异常：${e.message}")
                Log.e(TAG, "$reason 异常", e)
            } finally {
                if (wl?.isHeld == true) wl.release()
            }
        }
    }

    /** 包名转应用名（查不到时原样返回包名） */
    private fun appLabel(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** 追加一条诊断日志（最多保留 30 条） */
    fun appendLog(ctx: Context, line: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREF_DIAG, Context.MODE_PRIVATE)
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val existing = prefs.getString(KEY_LOG, "") ?: ""
            val lines = existing.lines().filter { it.isNotBlank() }.toMutableList()
            lines.add(0, "$time $line")
            val kept = lines.take(30).joinToString("\n")
            prefs.edit().putString(KEY_LOG, kept).apply()
        } catch (_: Exception) {}
    }

    fun readLog(ctx: Context): String {
        val s = ctx.getSharedPreferences(PREF_DIAG, Context.MODE_PRIVATE).getString(KEY_LOG, "") ?: ""
        return if (s.isBlank()) "暂无自动冻结记录。\n开启开关后锁屏一次，或点「测试自动冻结」查看结果。"
               else s
    }

    fun clearLog(ctx: Context) {
        ctx.getSharedPreferences(PREF_DIAG, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply()
    }
}
