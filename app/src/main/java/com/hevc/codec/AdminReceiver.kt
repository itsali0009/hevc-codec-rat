package com.hevc.codec

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun isActive(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            return dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
        }

        fun requestActivation(ctx: Context) {
            if (isActive(ctx)) return
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(ctx, AdminReceiver::class.java))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }
}
