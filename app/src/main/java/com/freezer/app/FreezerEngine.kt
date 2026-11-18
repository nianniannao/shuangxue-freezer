package com.freezer.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 冻结引擎：支持 3 种工作模式，自动按优先级选择。
 *  1) 设备所有者 (Device Owner) —— 一次激活，永久有效，重启不失活。强烈推荐。
 *  2) Root —— 永久有效。
 *  3) Shizuku —— 免 root，但每次重启需重新激活（兜底）。
 *
 * "彻底冻结" = 先强制停止进程（force-stop），再隐藏/停用组件，
 * 这样既从桌面消失、无法被相互唤醒，也清掉了正在运行的后台进程。
 */
object FreezerEngine {

    const val MODE_DEVICE_OWNER = 0
    const val MODE_ROOT = 1
    const val MODE_SHIZUKU = 2
    const val MODE_NONE = -1

    private const val PKG = "com.freezer.app"
    private val admin by lazy { ComponentName(PKG, DeviceAdminReceiver::class.java.name) }

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(ctx: Context): Boolean = try {
        dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    } catch (e: Exception) { false }

    fun isRootAvailable(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
    } catch (e: Exception) { false }

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) { false }

    fun isShizukuGranted(): Boolean = try {
        isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun bestMode(ctx: Context): Int = when {
        isDeviceOwner(ctx) -> MODE_DEVICE_OWNER
        isRootAvailable() -> MODE_ROOT
        isShizukuGranted() -> MODE_SHIZUKU
        else -> MODE_NONE
    }

    fun modeName(mode: Int): String = when (mode) {
        MODE_DEVICE_OWNER -> "设备所有者（永久有效）"
        MODE_ROOT -> "Root"
        MODE_SHIZUKU -> if (isShizukuGranted()) "Shizuku（已连接·正常）" else "Shizuku（未连接·需重新激活）"
        else -> "未激活"
    }

    /** 判断应用是否已冻结 */
    fun isFrozen(ctx: Context, pkg: String, mode: Int): Boolean = try {
        when (mode) {
            MODE_DEVICE_OWNER -> dpm(ctx).isApplicationHidden(admin, pkg)
            MODE_ROOT, MODE_SHIZUKU -> {
                // 必须用 getApplicationEnabledSetting 判断：pm disable-user 改的是 enabledSetting，
                // 而 ApplicationInfo.enabled 反映的是 manifest 里的 android:enabled，两者不同，
                // 用后者会「冻结成功却显示未冻结」。
                val setting = ctx.packageManager.getApplicationEnabledSetting(pkg)
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 彻底冻结：先强制停止进程，再隐藏/停用组件。
     */
    fun freeze(ctx: Context, pkg: String, mode: Int): Result<Unit> = runCatching {
        if (mode == MODE_NONE) error("未激活任何工作模式")
        // 前置校验：Shizuku 模式必须确认授权仍有效，否则直接给出明确错误
        if (mode == MODE_SHIZUKU && !isShizukuGranted())
            error("Shizuku 授权已失效，请重新激活 Shizuku（无线调试）后再冻结")

        forceStop(ctx, pkg, mode)
        when (mode) {
            MODE_DEVICE_OWNER -> {
                dpm(ctx).setApplicationHidden(admin, pkg, true)
                try { dpm(ctx).setPackagesSuspended(admin, arrayOf(pkg), true) } catch (_: Exception) {}
            }
            MODE_ROOT -> runShell("pm disable-user --user 0 $pkg")
            MODE_SHIZUKU -> {
                val r = runShizuku(arrayOf("pm", "disable-user", "--user", "0", pkg))
                if (r.startsWith("ERR:")) error(r)
            }
            else -> error("未激活任何工作模式")
        }
        // 后置校验：确认真的冻结成功，避免「执行了没效果却不报错」
        if (!isFrozen(ctx, pkg, mode))
            error("冻结命令已执行但未生效，可能是系统保护了该应用或授权异常")
    }

    /** 解冻 */
    fun unfreeze(ctx: Context, pkg: String, mode: Int): Result<Unit> = runCatching {
        if (mode == MODE_NONE) error("未激活任何工作模式")
        if (mode == MODE_SHIZUKU && !isShizukuGranted())
            error("Shizuku 授权已失效，请重新激活 Shizuku 后再解冻")
        when (mode) {
            MODE_DEVICE_OWNER -> {
                try { dpm(ctx).setPackagesSuspended(admin, arrayOf(pkg), false) } catch (_: Exception) {}
                dpm(ctx).setApplicationHidden(admin, pkg, false)
            }
            MODE_ROOT -> runShell("pm enable $pkg")
            MODE_SHIZUKU -> {
                val r = runShizuku(arrayOf("pm", "enable", pkg))
                if (r.startsWith("ERR:")) error(r)
            }
            else -> error("未激活任何工作模式")
        }
    }

    /** 强制停止应用进程（彻底冻结的第一步） */
    fun forceStop(ctx: Context, pkg: String, mode: Int) {
        when (mode) {
            MODE_DEVICE_OWNER -> {
                try {
                    when {
                        isShizukuGranted() -> runShizuku(arrayOf("am", "force-stop", pkg))
                        isRootAvailable() -> runShell("am force-stop $pkg")
                        else -> {
                            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                            am.killBackgroundProcesses(pkg)
                        }
                    }
                } catch (_: Exception) {}
            }
            MODE_ROOT -> try { runShell("am force-stop $pkg") } catch (_: Exception) {}
            MODE_SHIZUKU -> try { runShizuku(arrayOf("am", "force-stop", pkg)) } catch (_: Exception) {}
        }
    }

    fun runShizuku(command: Array<String>): String {
        return try {
            if (!isShizukuGranted()) return "ERR:Shizuku 未授权"
            // newProcess 在 Shizuku API 13.x 中是 private，反射调用（返回 ShizukuRemoteProcess，继承 Process）
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, command, null, null) as Process
            // 先读完输出再 waitFor，避免缓冲区写满导致子进程阻塞
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code == 0) {
                // pm 命令失败时可能仍返回 0，检查输出中的 Error/Failure 关键字
                if (output.contains("Error", true) || output.contains("Failure", true) || output.contains("not found", true))
                    "ERR:${output.ifBlank { error }.trim()}"
                else output.ifBlank { "OK" }
            } else "ERR:${error.ifBlank { output }.ifBlank { "退出码 $code" }.trim()}"
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException?.message ?: e.message ?: "未知错误"
            "ERR:Shizuku 执行失败：$cause"
        } catch (e: Exception) {
            "ERR:${e.message ?: "执行失败"}"
        }
    }

    private fun runShell(command: String) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.waitFor()
    }

    /** 用 Shizuku 的 shell 权限把本 App 设为设备所有者。返回 null 表示成功，否则返回错误说明 */
    fun activateDeviceOwner(): String? {
        return try {
            if (!isShizukuGranted()) return@activateDeviceOwner "Shizuku 未授权，请先激活 Shizuku"
            val result = runShizuku(arrayOf("dpm", "set-device-owner", "$PKG/.DeviceAdminReceiver"))
            val ok = result == "OK" || result.contains("Success", true) || result.contains("Device owner set", true)
            if (ok) null
            else friendlyOwnerError(result)
        } catch (e: Exception) { friendlyOwnerError(e.message ?: "激活失败") }
    }

    /** 列出设备上 AccountManager 仍记录的账号（可能是隐藏的系统/云账号） */
    fun listAccounts(): String {
        if (!isShizukuGranted()) return "（需要 Shizuku 授权才能读取账号列表）"
        val raw = runShizuku(arrayOf("dumpsys", "account"))
        if (raw.startsWith("ERR:")) return "读取失败：$raw"
        // 抽取 "Account {name=xxx, type=yyy" 行
        val lines = raw.lines().map { it.trim() }
            .filter { it.contains("Account {", true) && (it.contains("name=", true) || it.contains("type=", true)) }
            .map { it.replace(Regex(".*Account \\{"), "• ").replace(Regex("\\}.*"), "").take(120) }
            .distinct()
        return if (lines.isEmpty()) "未读取到账号明细（可能已清空，或系统未返回）"
        else lines.joinToString("\n")
    }

    /** 把冗长的英文堆栈转成可操作的中文提示 */
    private fun friendlyOwnerError(raw: String): String {
        val r = raw.trim()
        return when {
            r.contains("already some accounts", true) || r.contains("accounts on the device", true) ->
                "设备上存在账号，Android 禁止在有账号时设置设备所有者。\n\n" +
                "这是系统硬性限制，无法绕过。可选方案：\n" +
                "① 退掉设备上的账号后重试（最彻底，重启不失活）；\n" +
                "② 直接使用 Shizuku 模式（重启后需重新激活一次）。"
            r.contains("already a device owner", true) || r.contains("device owner is set", true) ->
                "设备上已经存在设备所有者（可能是系统或别的 App），无法重复设置。"
            r.contains("does not have a device admin", true) || r.contains("DeviceAdmin", true) ->
                "设备管理员组件异常，请重装本 App 后重试。"
            r.startsWith("ERR:") -> r.removePrefix("ERR:")
            r.length > 300 -> r.take(300) + "…"
            else -> r.ifBlank { "激活失败，请改用 Shizuku 模式" }
        }
    }
}
