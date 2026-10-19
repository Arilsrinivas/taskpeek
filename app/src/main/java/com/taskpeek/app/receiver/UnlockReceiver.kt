package com.taskpeek.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskpeek.app.overlay.TaskOverlayManager

class UnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "UnlockReceiver received action: $action")
        if (Intent.ACTION_USER_PRESENT == action) {
            TaskOverlayManager.triggerUnlock(context)
        }
    }

    companion object {
        private const val TAG = "TaskPeekReceiver"
    }
}
