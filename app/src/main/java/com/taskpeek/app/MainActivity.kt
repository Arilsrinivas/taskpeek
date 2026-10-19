package com.taskpeek.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var taskAdapter: TaskAdapter

    private var showOnlyToday = true

    // Permission launcher for overlay permission (Android 6.0+)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionAndServiceUI()
    }

    // Permission launcher for notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkAndStartServiceIfRequested()
        } else {
            Toast.makeText(
                this,
                "Notification permission is needed for the background monitor.",
                Toast.LENGTH_LONG
            ).show()
        }
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
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            onToggleComplete = { task, isCompleted ->
                lifecycleScope.launch {
                    database.taskDao().setTaskCompleted(task.id, isCompleted)
                }
            },
            onDelete = { task ->
                lifecycleScope.launch {
                    database.taskDao().deleteTask(task)
                }
            }
        )

        binding.rvTasks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = taskAdapter
        }
    }

    private fun setupListeners() {
        // Monitoring switch
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasOverlayPermission()) {
                    requestOverlayPermission()
                    binding.switchMonitoring.isChecked = false
                } else if (!hasNotificationPermission()) {
                    requestNotificationPermission()
                    binding.switchMonitoring.isChecked = false
                } else {
                    UnlockMonitorService.start(this)
                    updatePermissionAndServiceUI()
                }
            } else {
                UnlockMonitorService.stop(this)
                updatePermissionAndServiceUI()
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

    private fun addNewTask() {
        val title = binding.etTaskTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            binding.tilTaskTitle.error = "Please enter a task title"
            return
        }
        binding.tilTaskTitle.error = null

        val isToday = binding.cbAssignToday.isChecked
        val taskDate = if (isToday) DateUtils.getTodayDateString() else DateUtils.getTodayDateString()

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
            binding.etTaskTitle.text?.clear()
            Toast.makeText(this@MainActivity, "Task added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeTasks() {
        lifecycleScope.launch {
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
        binding.switchMonitoring.isChecked = isMonitoring && overlayGranted

        if (isMonitoring && overlayGranted) {
            binding.tvMonitorStatus.text = getString(R.string.status_monitoring_active)
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            if (hasNotificationPermission()) {
                UnlockMonitorService.start(this)
            }
        } else {
            binding.tvMonitorStatus.text = getString(R.string.status_monitoring_inactive)
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
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

    private fun checkAndStartServiceIfRequested() {
        if (hasOverlayPermission()) {
            UnlockMonitorService.start(this)
            updatePermissionAndServiceUI()
        }
    }
}
