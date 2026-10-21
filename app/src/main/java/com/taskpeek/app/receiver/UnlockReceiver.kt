package com.taskpeek.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.util.PreferenceHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        if (!PreferenceHelper.isMonitoringEnabled(context)) {
            Log.d(TAG, "[$now] Broadcast ignored ($action): monitoring is disabled in preferences")
            return
        }

        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "[$now] SCREEN_OFF_RECEIVED: display turned off, resetting active overlay and unlock session")
                TaskOverlayManager.onScreenOff(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "[$now] SCREEN_ON_RECEIVED: display turned on, awaiting keyguard dismissal")
                TaskOverlayManager.onScreenOn(context)
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "[$now] USER_PRESENT_RECEIVED: keyguard dismissed by user")
                TaskOverlayManager.onUserPresent(context)
            }
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d(TAG, "[$now] DATE/TIME_CHANGED_RECEIVED ($action): invalidating and warming cache for new date")
                TaskOverlayManager.invalidateCache()
                TaskOverlayManager.warmCache(context)
            }
            else -> {
                Log.d(TAG, "[$now] Unexpected broadcast received: $action")
            }
        }
    }

    companion object {
        private const val TAG = "TaskPeekUnlock"
    }
}
