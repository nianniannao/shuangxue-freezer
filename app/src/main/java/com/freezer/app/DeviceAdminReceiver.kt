package com.freezer.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 设备管理员 / 设备所有者接收器。
 * 注意：被设为"设备管理员"≠"设备所有者"，只有后者才能用 setApplicationHidden 冻结。
 * 这里区分两种情况给出准确提示，避免用户误以为激活成功。
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isOwner = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) { false }
        val msg = if (isOwner)
            "霜雪已设为设备所有者（永久有效，重启不失活）"
        else
            "霜雪已获得设备管理员权限，但还不是设备所有者；冻结仍走当前授权模式"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "霜雪设备管理员权限已移除", Toast.LENGTH_SHORT).show()
    }
}
