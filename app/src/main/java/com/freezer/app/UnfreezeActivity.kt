package com.freezer.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 透明中转 Activity。桌面快捷方式点击后进入这里：
 * 先解冻目标应用，再启动它，最后结束自己（用户无感知）。
 */
class UnfreezeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg.isNullOrBlank()) {
            finish()
            return
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val mode = prefs.getInt("mode", FreezerEngine.bestMode(this))

        // 先解冻（无论之前是否冻结，重复解冻是安全的），再轮询等 PackageManager
        // 就绪后启动。统一走 AppLauncher 后台流水线：解冻 → 轮询启动 → 自愈补解冻，
        // 最多 3 秒；启动已发起或最终放弃时结束自己（用户无感知）。
        AppLauncher.launch(
            activity = this,
            pkg = pkg,
            mode = mode,
            needUnfreeze = true,
            onLaunched = { finish() },
            onFail = { finish() }
        )
    }

    companion object {
        const val EXTRA_PKG = "target_pkg"
    }
}
