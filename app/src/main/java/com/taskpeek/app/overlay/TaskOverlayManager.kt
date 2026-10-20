package com.taskpeek.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.taskpeek.app.R
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.util.DateUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TaskOverlayManager {

    private const val TAG = "TaskPeekUnlock"
    private const val DISPLAY_DURATION_MS = 3000L
    private const val DUPLICATE_DEBOUNCE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: View? = null

    @Volatile
    private var isShowing = false

    @Volatile
    private var isUnlockSessionActive = false

    private var currentLoadingJob: Job? = null
    private var dismissRunnable: Runnable? = null
    private var hardDismissRunnable: Runnable? = null
    private var lastUnlockTime = 0L

    private fun getTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    /**
     * Called when the screen turns off. Resets active unlock session and cleans up any active overlay.
     */
    fun onScreenOff(context: Context) {
        val appContext = context.applicationContext
        val now = getTimestamp()

        // Cancel any pending background task loading job
        currentLoadingJob?.cancel()
        currentLoadingJob = null

        // Reset unlock session and debounce state so subsequent unlock is immediately accepted
        isUnlockSessionActive = false

        // Immediately dismiss and remove any active overlay
        mainHandler.post {
            dismissOverlay(appContext, reason = "SCREEN_OFF (screen turned off)")
        }
    }

    /**
     * Called when the screen turns on.
     */
    fun onScreenOn(context: Context) {
        // Supplementary diagnostic signal; overlay is not shown until keyguard is dismissed
    }

    /**
     * Called when the user presents/dismisses the keyguard.
     */
    fun onUserPresent(context: Context) {
        triggerUnlock(context)
    }

    /**
     * Triggered when a phone unlock event is detected.
     */
    fun triggerUnlock(context: Context) {
        val appContext = context.applicationContext
        val currentTime = System.currentTimeMillis()
        val now = getTimestamp()

        // 1. Prevent duplicate broadcasts within the exact same unlock session
        if (isUnlockSessionActive && (currentTime - lastUnlockTime < DUPLICATE_DEBOUNCE_MS)) {
            Log.d(TAG, "[$now] UNLOCK_EVENT_IGNORED: reason=duplicate event within same unlock session (delta=${currentTime - lastUnlockTime}ms < ${DUPLICATE_DEBOUNCE_MS}ms)")
            return
        }

        // 2. Verify overlay permission
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "[$now] UNLOCK_EVENT_IGNORED: reason=SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        isUnlockSessionActive = true
        lastUnlockTime = currentTime
        Log.d(TAG, "[$now] UNLOCK_EVENT_ACCEPTED: proceeding to load tasks for display")

        // Cancel any previous pending load job
        currentLoadingJob?.cancel()

        currentLoadingJob = scope.launch {
            try {
                val todayStr = DateUtils.getTodayDateString()
                val db = AppDatabase.getInstance(appContext)
                val pendingTasks = db.taskDao().getTodayPendingTasks(todayStr)
                val fetchTime = getTimestamp()
                Log.d(TAG, "[$fetchTime] Tasks loaded: count=${pendingTasks.size} for date=$todayStr")

                withContext(Dispatchers.Main) {
                    // Check if display is still interactive (user did not immediately turn screen off)
                    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isInteractive = powerManager?.isInteractive ?: true
                    if (!isInteractive) {
                        val rejectTime = getTimestamp()
                        Log.d(TAG, "[$rejectTime] OVERLAY_ATTACH_FAILED: reason=screen is no longer interactive (device re-locked before display)")
                        return@withContext
                    }

                    showOverlay(appContext, pendingTasks)
                }
            } catch (e: CancellationException) {
                val cancelTime = getTimestamp()
                Log.d(TAG, "[$cancelTime] Task loading cancelled: reason=superseded by newer event or screen off")
            } catch (e: Exception) {
                val errTime = getTimestamp()
                Log.e(TAG, "[$errTime] OVERLAY_ATTACH_FAILED: reason=database query exception: ${e.message}", e)
            }
        }
    }

    /**
     * Show overlay directly on the main thread for 3 seconds.
     */
    @Synchronized
    fun showOverlay(context: Context, tasks: List<Task>) {
        val appContext = context.applicationContext
        val now = getTimestamp()
        Log.d(TAG, "[$now] OVERLAY_ATTACH_REQUESTED: taskCount=${tasks.size}")

        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "[$now] OVERLAY_ATTACH_FAILED: reason=permission denied for drawing overlay")
            return
        }

        // Clean up any existing overlay view or timer
        dismissOverlay(appContext, reason = "superseded by new overlay")

        try {
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(appContext)
            val view = inflater.inflate(R.layout.view_task_overlay, null)

            // Bind Views
            val tvOverlayDate = view.findViewById<TextView>(R.id.tvOverlayDate)
            val tasksContainer = view.findViewById<LinearLayout>(R.id.tasksContainer)
            val tvEmptyState = view.findViewById<TextView>(R.id.tvEmptyState)

            // Dynamic current local date in reference format (e.g., Sun, 19 Oct 2026)
            tvOverlayDate.text = DateUtils.getTodayReferenceDateString()

            if (tasks.isEmpty()) {
                tvEmptyState.visibility = View.VISIBLE
                tasksContainer.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.GONE
                tasksContainer.visibility = View.VISIBLE

                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

                tasks.take(3).forEach { task ->
                    val taskRow = inflater.inflate(R.layout.item_overlay_task, tasksContainer, false)
                    val tvTitle = taskRow.findViewById<TextView>(R.id.tvTaskTitle)
                    val tvSubtitle = taskRow.findViewById<TextView>(R.id.tvTaskSubtitle)
                    val tvTime = taskRow.findViewById<TextView>(R.id.tvTaskTime)
                    val ivIcon = taskRow.findViewById<ImageView>(R.id.ivCategoryIcon)

                    // Parse title and optional subtitle if delimited, or display title directly
                    val parts = task.title.split(Regex(" - |: "), limit = 2)
                    if (parts.size == 2) {
                        tvTitle.text = parts[0].trim()
                        tvSubtitle.text = parts[1].trim()
                        tvSubtitle.visibility = View.VISIBLE
                    } else {
                        tvTitle.text = task.title
                        tvSubtitle.visibility = View.GONE
                    }

                    // Format scheduled/created time for far-right alignment
                    if (task.createdAt > 0L) {
                        tvTime.text = timeFormat.format(Date(task.createdAt))
                        tvTime.visibility = View.VISIBLE
                    } else {
                        tvTime.visibility = View.GONE
                    }

                    // Select clean monochrome line icon matching the task category
                    val lower = task.title.lowercase()
                    val iconRes = when {
                        lower.contains("dsa") || lower.contains("code") || lower.contains("pr") ||
                        lower.contains("git") || lower.contains("bug") || lower.contains("dev") -> R.drawable.ic_cat_code

                        lower.contains("comm") || lower.contains("read") || lower.contains("book") ||
                        lower.contains("notes") || lower.contains("revision") || lower.contains("study") -> R.drawable.ic_cat_book

                        lower.contains("project") || lower.contains("work") || lower.contains("report") ||
                        lower.contains("ui") || lower.contains("laptop") || lower.contains("sync") -> R.drawable.ic_cat_laptop

                        lower.contains("workout") || lower.contains("gym") || lower.contains("run") ||
                        lower.contains("exercise") || lower.contains("fitness") -> R.drawable.ic_cat_workout

                        else -> R.drawable.ic_cat_task
                    }
                    ivIcon.setImageResource(iconRes)

                    tasksContainer.addView(taskRow)
                }
            }

            // Consume ordinary touch input for its entire visible area so underlying apps receive no taps
            view.setOnTouchListener { _, _ -> true }

            // Apply immersive fullscreen system bar hiding
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        try {
                            v.windowInsetsController?.let { controller ->
                                controller.hide(
                                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                                )
                                controller.systemBarsBehavior =
                                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to hide system bars via insets controller: ${e.message}")
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            } else {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }

            // WindowManager layout params with PixelFormat.TRANSLUCENT so wallpaper shows through 80% black
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                windowFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // Start transparent for smooth fade in
            view.alpha = 0f
            windowManager.addView(view, params)
            overlayView = view
            isShowing = true

            val attachTime = getTimestamp()
            Log.d(TAG, "[$attachTime] OVERLAY_ATTACHED: view added to WindowManager, starting 200ms fade in")

            // Fade in over 200 milliseconds
            view.animate()
                .alpha(1f)
                .setDuration(200L)
                .start()

            // Retain reference to this specific view instance to avoid race condition with subsequent views
            val targetView = view

            // Schedule smooth 250ms fade out before 3-second automatic dismissal
            val fadeOutRunnable = Runnable {
                val fadeTime = getTimestamp()
                Log.d(TAG, "[$fadeTime] Starting 250ms fade out before dismissal")
                targetView.animate()
                    .alpha(0f)
                    .setDuration(250L)
                    .withEndAction {
                        if (overlayView === targetView) {
                            dismissOverlay(appContext, reason = "normal 3.0s fade-out complete")
                        }
                    }
                    .start()
            }
            dismissRunnable = fadeOutRunnable
            mainHandler.postDelayed(fadeOutRunnable, DISPLAY_DURATION_MS - 250L)

            // Hard timeout fallback at 3,000ms
            val hardDismissRunnable = Runnable {
                if (overlayView === targetView) {
                    dismissOverlay(appContext, reason = "normal 3.0s timeout completion")
                }
            }
            this.hardDismissRunnable = hardDismissRunnable
            mainHandler.postDelayed(hardDismissRunnable, DISPLAY_DURATION_MS)

        } catch (e: Exception) {
            val failTime = getTimestamp()
            Log.e(TAG, "[$failTime] OVERLAY_ATTACH_FAILED: reason=${e.message}", e)
            dismissOverlay(appContext, reason = "attach exception")
        }
    }

    /**
     * Safely dismiss and remove the overlay view, cancelling timers and ongoing animations.
     */
    @Synchronized
    fun dismissOverlay(context: Context, reason: String = "unspecified") {
        val appContext = context.applicationContext
        val now = getTimestamp()

        dismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            dismissRunnable = null
        }
        hardDismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            hardDismissRunnable = null
        }

        overlayView?.let { view ->
            try {
                view.animate().cancel()
                val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (view.parent != null || view.isAttachedToWindow) {
                    windowManager.removeView(view)
                    Log.d(TAG, "[$now] OVERLAY_REMOVED: reason=$reason")
                } else {
                    Log.d(TAG, "[$now] OVERLAY_REMOVED: view already detached (reason=$reason)")
                }
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "[$now] OVERLAY_REMOVED: view not attached to window manager (reason=$reason)")
            } catch (e: Exception) {
                Log.w(TAG, "[$now] Exception while removing overlay view: ${e.message}")
            }
            overlayView = null
        }

        isShowing = false
    }
}
