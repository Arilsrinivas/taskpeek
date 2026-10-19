package com.taskpeek.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.taskpeek.app.R
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TaskOverlayManager {

    private const val TAG = "TaskPeekOverlay"
    private const val DISPLAY_DURATION_MS = 3000L
    private const val DEBOUNCE_THRESHOLD_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: View? = null

    @Volatile
    private var isShowing = false

    private var dismissRunnable: Runnable? = null
    private var lastUnlockTime = 0L

    /**
     * Triggered when a phone unlock event is detected.
     */
    fun triggerUnlock(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUnlockTime < DEBOUNCE_THRESHOLD_MS) {
            Log.d(TAG, "Duplicate unlock event ignored by debounce (${currentTime - lastUnlockTime}ms)")
            return
        }
        lastUnlockTime = currentTime

        // Verify overlay permission
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        // Query database on background thread, then display on main thread
        scope.launch {
            try {
                val todayStr = DateUtils.getTodayDateString()
                val db = AppDatabase.getInstance(context)
                val pendingTasks = db.taskDao().getTodayPendingTasks(todayStr)
                Log.d(TAG, "Retrieved ${pendingTasks.size} pending tasks for today ($todayStr)")

                withContext(Dispatchers.Main) {
                    showOverlay(context, pendingTasks)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tasks for overlay", e)
            }
        }
    }

    /**
     * Show overlay directly on the main thread for 3 seconds.
     */
    @Synchronized
    fun showOverlay(context: Context, tasks: List<Task>) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Permission denied for drawing overlay")
            return
        }

        // Clean up any existing overlay view or timer
        dismissOverlay(context)

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_task_overlay, null)

            // Bind Views
            val tvTaskCount = view.findViewById<TextView>(R.id.tvTaskCount)
            val btnClose = view.findViewById<ImageView>(R.id.btnCloseOverlay)
            val tasksContainer = view.findViewById<LinearLayout>(R.id.tasksContainer)
            val tvEmptyState = view.findViewById<TextView>(R.id.tvEmptyState)

            // Manual dismiss button
            btnClose.setOnClickListener {
                dismissOverlay(context)
            }

            if (tasks.isEmpty()) {
                tvTaskCount.text = "0 pending"
                tvEmptyState.visibility = View.VISIBLE
                tasksContainer.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.GONE
                tasksContainer.visibility = View.VISIBLE
                tvTaskCount.text = "${tasks.size} pending"

                // Populate up to 3 task rows
                tasks.take(3).forEach { task ->
                    val taskRow = inflater.inflate(R.layout.item_overlay_task, tasksContainer, false)
                    val tvTitle = taskRow.findViewById<TextView>(R.id.tvTaskTitle)
                    val tvBadge = taskRow.findViewById<TextView>(R.id.tvPriorityBadge)
                    val viewPriorityDot = taskRow.findViewById<View>(R.id.viewPriorityDot)

                    tvTitle.text = task.title

                    when (task.priority.uppercase()) {
                        "HIGH" -> {
                            tvBadge.text = "HIGH"
                            tvBadge.setTextColor(ContextCompat.getColor(context, R.color.priority_high))
                            tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_high)
                            viewPriorityDot.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_high)
                        }
                        "LOW" -> {
                            tvBadge.text = "LOW"
                            tvBadge.setTextColor(ContextCompat.getColor(context, R.color.priority_low))
                            tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_low)
                            viewPriorityDot.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_low)
                        }
                        else -> {
                            tvBadge.text = "MED"
                            tvBadge.setTextColor(ContextCompat.getColor(context, R.color.priority_medium))
                            tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_medium)
                            viewPriorityDot.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_medium)
                        }
                    }
                    tasksContainer.addView(taskRow)
                }
            }

            // WindowManager layout params
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = (context.resources.displayMetrics.density * 36).toInt() // Margin below status bar
            }

            windowManager.addView(view, params)
            overlayView = view
            isShowing = true
            Log.d(TAG, "Task overlay displayed successfully")

            // Schedule automatic removal after exactly 3 seconds
            val runnable = Runnable {
                Log.d(TAG, "3 seconds elapsed: automatically dismissing overlay")
                dismissOverlay(context)
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, DISPLAY_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying task overlay", e)
            dismissOverlay(context)
        }
    }

    /**
     * Safely dismiss and remove the overlay view, cancelling timers.
     */
    @Synchronized
    fun dismissOverlay(context: Context) {
        dismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            dismissRunnable = null
        }

        overlayView?.let { view ->
            try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                    Log.d(TAG, "Overlay view detached from WindowManager")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while removing overlay view: ${e.message}")
            }
            overlayView = null
        }

        isShowing = false
    }
}
