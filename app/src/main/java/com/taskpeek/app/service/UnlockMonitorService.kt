package com.taskpeek.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taskpeek.app.MainActivity
import com.taskpeek.app.R
import com.taskpeek.app.TaskPeekApp
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.receiver.UnlockReceiver
import com.taskpeek.app.util.PreferenceHelper

class UnlockMonitorService : Service() {

    private val unlockReceiver = UnlockReceiver()
    private var isReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UnlockMonitorService created")
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "UnlockMonitorService started")

        // Handle stop action if requested
        if (intent?.action == ACTION_STOP_MONITORING) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        // Build persistent notification
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, TaskPeekApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TaskPeekApp.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TaskPeekApp.NOTIFICATION_ID,
                notification,
                0
            )
        } else {
            startForeground(TaskPeekApp.NOTIFICATION_ID, notification)
        }

        PreferenceHelper.setMonitoringEnabled(this, true)
        return START_STICKY
    }

    private fun registerUnlockReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
            Log.d(TAG, "UnlockReceiver registered dynamically")
        }
    }

    private fun unregisterUnlockReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
                Log.d(TAG, "UnlockReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Exception unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
    }

    private fun stopMonitoring() {
        PreferenceHelper.setMonitoringEnabled(this, false)
        TaskOverlayManager.dismissOverlay(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "UnlockMonitorService destroyed")
        unregisterUnlockReceiver()
        TaskOverlayManager.dismissOverlay(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TaskPeekService"
        const val ACTION_START_MONITORING = "com.taskpeek.app.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.taskpeek.app.STOP_MONITORING"

        fun start(context: Context) {
            val intent = Intent(context, UnlockMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UnlockMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
}
