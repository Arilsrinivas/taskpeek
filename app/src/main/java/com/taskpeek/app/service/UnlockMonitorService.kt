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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UnlockMonitorService : Service() {

    private val unlockReceiver = UnlockReceiver()
    private var isReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$now] SERVICE_STARTED: startId=$startId, flags=$flags, action=${intent?.action}")

        // Handle stop action if requested
        if (intent?.action == ACTION_STOP_MONITORING) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        // Verify that monitoring is enabled or explicitly requested
        val isExplicitStart = (intent?.action == ACTION_START_MONITORING)
        val isPrefEnabled = PreferenceHelper.isMonitoringEnabled(this)

        if (!isPrefEnabled && !isExplicitStart) {
            Log.d(TAG, "[$now] Monitoring not enabled in preferences; stopping service")
            stopSelf()
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

        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }

        // Ensure receiver is registered
        registerUnlockReceiver()

        PreferenceHelper.setMonitoringEnabled(this, true)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$now] onTaskRemoved: TaskPeek activity swiped from recents; service remains active")
    }

    private fun registerUnlockReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            Log.d(TAG, "[$now] RECEIVER_REGISTERED: actions=[ACTION_USER_PRESENT, ACTION_SCREEN_OFF, ACTION_SCREEN_ON]")
        }
    }

    private fun unregisterUnlockReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                Log.d(TAG, "[$now] RECEIVER_UNREGISTERED: receiver detached from context")
            } catch (e: Exception) {
                Log.w(TAG, "Exception unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
    }

    private fun stopMonitoring() {
        PreferenceHelper.setMonitoringEnabled(this, false)
        TaskOverlayManager.onMonitoringDisabled(this)
        unregisterUnlockReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$now] SERVICE_DESTROYED: monitoring service stopped")
        unregisterUnlockReceiver()
        TaskOverlayManager.dismissOverlay(this, "service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TaskPeekUnlock"
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
