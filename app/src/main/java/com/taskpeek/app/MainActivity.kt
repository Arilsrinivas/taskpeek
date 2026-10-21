package com.taskpeek.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ActivityMainBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.service.UnlockMonitorService
import com.taskpeek.app.ui.TaskAdapter
import com.taskpeek.app.util.DateUtils
import com.taskpeek.app.util.PreferenceHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var taskAdapter: TaskAdapter

    private var showOnlyToday = true
    private var isProgrammaticSwitchChange = false
    private var observeTasksJob: Job? = null

    // Permission launcher for overlay permission (Android 6.0+)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            if (PreferenceHelper.isMonitoringEnabled(this)) {
                UnlockMonitorService.start(this)
            }
        }
        updatePermissionAndServiceUI()
    }

    // Permission launcher for notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (PreferenceHelper.isMonitoringEnabled(this) && hasOverlayPermission()) {
                UnlockMonitorService.start(this)
            }
        } else {
            Toast.makeText(
                this,
                "Notification permission is needed for the background monitor.",
                Toast.LENGTH_LONG
            ).show()
        }
        updatePermissionAndServiceUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        setupRecyclerView()
        setupListeners()
        observeTasks()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndServiceUI()
        observeTasks()
        // Preload / warm cache for today's tasks
        TaskOverlayManager.warmCache(this)
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            onToggleComplete = { task, isCompleted ->
                lifecycleScope.launch {
                    database.taskDao().setTaskCompleted(task.id, isCompleted)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@MainActivity)
                }
            },
            onDelete = { task ->
                lifecycleScope.launch {
                    database.taskDao().deleteTask(task)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@MainActivity)
                }
            },
            onEdit = { task ->
                showEditTaskDialog(task)
            }
        )

        binding.rvTasks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = taskAdapter
        }
    }

    private fun showEditTaskDialog(task: Task) {
        val input = EditText(this).apply {
            setText(task.title)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Task")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    lifecycleScope.launch {
                        val updatedTask = task.copy(title = newTitle)
                        database.taskDao().updateTask(updatedTask)
                        TaskOverlayManager.invalidateCache()
                        TaskOverlayManager.warmCache(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Task updated!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupListeners() {
        // Monitoring switch with synchronized state handling
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!hasOverlayPermission()) {
                    Toast.makeText(
                        this,
                        "System Alert Window permission required to display task overlay.",
                        Toast.LENGTH_LONG
                    ).show()
                    requestOverlayPermission()
                    setSwitchCheckedWithoutTriggering(false)
                    return@setOnCheckedChangeListener
                }

                if (!hasNotificationPermission()) {
                    Toast.makeText(
                        this,
                        "Notification permission required for background monitoring.",
                        Toast.LENGTH_LONG
                    ).show()
                    requestNotificationPermission()
                    setSwitchCheckedWithoutTriggering(false)
                    return@setOnCheckedChangeListener
                }

                // Explicitly persist enabled state and start monitoring
                PreferenceHelper.setMonitoringEnabled(this, true)
                UnlockMonitorService.start(this)
                updateStatusText(true)
            } else {
                // Explicitly persist disabled state and stop monitoring immediately
                PreferenceHelper.setMonitoringEnabled(this, false)
                UnlockMonitorService.stop(this)
                TaskOverlayManager.onMonitoringDisabled(this)
                updateStatusText(false)
            }
        }

        // Grant Overlay Permission button in banner
        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        // Quick Test Overlay button (manual trigger of 3-second display)
        binding.btnTestOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
            } else {
                Toast.makeText(this, "Displaying preview overlay for 3s...", Toast.LENGTH_SHORT).show()
                TaskOverlayManager.triggerUnlock(this)
            }
        }

        // Add Task Button
        binding.btnAddTask.setOnClickListener {
            addNewTask()
        }

        // Filter Toggle (Today vs All)
        binding.toggleTaskFilter.check(R.id.btnFilterToday)
        binding.toggleTaskFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                showOnlyToday = (checkedId == R.id.btnFilterToday)
                observeTasks()
            }
        }
    }

    private fun setSwitchCheckedWithoutTriggering(checked: Boolean) {
        isProgrammaticSwitchChange = true
        binding.switchMonitoring.isChecked = checked
        isProgrammaticSwitchChange = false
    }

    private fun updateStatusText(isActive: Boolean) {
        if (isActive) {
            binding.tvMonitorStatus.text = getString(R.string.status_monitoring_active)
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.tvMonitorStatus.text = getString(R.string.status_monitoring_inactive)
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
    }

    private fun addNewTask() {
        val title = binding.etTaskTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            binding.tilTaskTitle.error = "Please enter a task title"
            return
        }
        binding.tilTaskTitle.error = null

        val isToday = binding.cbAssignToday.isChecked
        val taskDate = if (isToday) DateUtils.getTodayDateString() else DateUtils.getTomorrowDateString()

        val priority = when {
            binding.chipHigh.isChecked -> "HIGH"
            binding.chipLow.isChecked -> "LOW"
            else -> "MEDIUM"
        }

        val newTask = Task(
            title = title,
            date = taskDate,
            priority = priority
        )

        lifecycleScope.launch {
            database.taskDao().insertTask(newTask)
            TaskOverlayManager.invalidateCache()
            TaskOverlayManager.warmCache(this@MainActivity)
            binding.etTaskTitle.text?.clear()
            Toast.makeText(this@MainActivity, "Task added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeTasks() {
        observeTasksJob?.cancel()
        observeTasksJob = lifecycleScope.launch {
            val flow = if (showOnlyToday) {
                database.taskDao().getTodayTasksFlow(DateUtils.getTodayDateString())
            } else {
                database.taskDao().getAllTasksFlow()
            }

            flow.collectLatest { tasks ->
                taskAdapter.submitList(tasks)
                if (tasks.isEmpty()) {
                    binding.tvEmptyList.visibility = View.VISIBLE
                    binding.tvEmptyList.text = if (showOnlyToday) {
                        getString(R.string.empty_today_tasks)
                    } else {
                        getString(R.string.empty_tasks)
                    }
                } else {
                    binding.tvEmptyList.visibility = View.GONE
                }
            }
        }
    }

    private fun updatePermissionAndServiceUI() {
        val overlayGranted = hasOverlayPermission()
        binding.bannerOverlayPermission.visibility = if (overlayGranted) View.GONE else View.VISIBLE

        val isMonitoring = PreferenceHelper.isMonitoringEnabled(this)
        val shouldBeActive = isMonitoring && overlayGranted

        setSwitchCheckedWithoutTriggering(shouldBeActive)
        updateStatusText(shouldBeActive)
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
