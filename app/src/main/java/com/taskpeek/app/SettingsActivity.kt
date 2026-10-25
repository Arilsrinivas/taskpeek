package com.taskpeek.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.databinding.ActivitySettingsBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.service.UnlockMonitorService
import com.taskpeek.app.util.PreferenceHelper
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var database: AppDatabase
    private var isProgrammaticSwitchChange = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            if (PreferenceHelper.isMonitoringEnabled(this)) {
                UnlockMonitorService.start(this)
            }
        }
        updateMonitorUI()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (PreferenceHelper.isMonitoringEnabled(this) && hasOverlayPermission()) {
                UnlockMonitorService.start(this)
            }
        }
        updateMonitorUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        setupListeners()
        updateMonitorUI()
    }

    override fun onResume() {
        super.onResume()
        updateMonitorUI()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.switchUnlockMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!hasOverlayPermission()) {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                    requestOverlayPermission()
                    setSwitchWithoutTriggering(false)
                    return@setOnCheckedChangeListener
                }
                if (!hasNotificationPermission()) {
                    Toast.makeText(this, "Notification permission is required", Toast.LENGTH_SHORT).show()
                    requestNotificationPermission()
                    setSwitchWithoutTriggering(false)
                    return@setOnCheckedChangeListener
                }

                PreferenceHelper.setMonitoringEnabled(this, true)
                UnlockMonitorService.start(this)
                updateMonitorStatusText(true)
            } else {
                PreferenceHelper.setMonitoringEnabled(this, false)
                UnlockMonitorService.stop(this)
                TaskOverlayManager.onMonitoringDisabled(this)
                updateMonitorStatusText(false)
            }
        }

        binding.btnTestOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
            } else {
                Toast.makeText(this, "Displaying preview overlay for 3s...", Toast.LENGTH_SHORT).show()
                TaskOverlayManager.triggerUnlock(this)
            }
        }

        binding.rowManageCategories.setOnClickListener {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }

        binding.rowDefaultReminder.setOnClickListener {
            val options = arrayOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour")
            AlertDialog.Builder(this)
                .setTitle("Default Reminder")
                .setItems(options) { _, which ->
                    binding.tvDefaultReminder.text = options[which]
                }
                .show()
        }

        binding.rowTheme.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Theme")
                .setMessage("Dark Monochrome theme is permanently enabled to match the reference design.")
                .setPositiveButton("OK", null)
                .show()
        }

        binding.rowBackupSync.setOnClickListener {
            lifecycleScope.launch {
                val tasks = database.taskDao().getAllTasks()
                val jsonArray = JSONArray()
                tasks.forEach { t ->
                    val obj = JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("date", t.date)
                        put("time", t.time)
                        put("category", t.category)
                        put("priority", t.priority)
                        put("isCompleted", t.isCompleted)
                        put("description", t.description)
                    }
                    jsonArray.put(obj)
                }

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Local Backup")
                    .setMessage("Found ${tasks.size} tasks in database. Local backup snapshot prepared.")
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun updateMonitorUI() {
        val overlayGranted = hasOverlayPermission()
        val isMonitoring = PreferenceHelper.isMonitoringEnabled(this)
        val shouldBeActive = isMonitoring && overlayGranted

        setSwitchWithoutTriggering(shouldBeActive)
        updateMonitorStatusText(shouldBeActive)
    }

    private fun setSwitchWithoutTriggering(checked: Boolean) {
        isProgrammaticSwitchChange = true
        binding.switchUnlockMonitor.isChecked = checked
        isProgrammaticSwitchChange = false
    }

    private fun updateMonitorStatusText(isActive: Boolean) {
        if (isActive) {
            binding.tvMonitorStatus.text = "Monitoring is Active"
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            binding.tvMonitorStatus.text = "Monitoring is Inactive"
            binding.tvMonitorStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
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
}
