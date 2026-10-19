package com.taskpeek.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.taskpeek.app.service.UnlockMonitorService
import com.taskpeek.app.util.PreferenceHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "BootReceiver triggered with action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action ||
            "android.intent.action.QUICKBOOT_POWERON" == action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == action
        ) {
            val isEnabled = PreferenceHelper.isMonitoringEnabled(context)
            val hasOverlayPermission = Settings.canDrawOverlays(context)

            Log.d(TAG, "Boot conditions: enabled=$isEnabled, overlayPermission=$hasOverlayPermission")

            if (isEnabled && hasOverlayPermission) {
                Log.d(TAG, "Restarting UnlockMonitorService after boot")
                UnlockMonitorService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "TaskPeekBoot"
    }
}
