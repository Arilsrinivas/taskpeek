package com.taskpeek.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ActivityEditTaskBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class EditTaskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditTaskBinding
    private lateinit var database: AppDatabase
    private var taskId: Long = -1L
    private var originalTask: Task? = null

    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedTime: LocalTime = LocalTime.of(9, 0)
    private var selectedPriority: String = "Medium"
    private var selectedCategory: String = "Study"
    private var selectedRepeat: String = "Does not repeat"
    private var selectedReminder: String = "10 minutes before"

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US)
    private val dbDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)

        if (taskId == -1L) {
            Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadTaskData()
        setupListeners()
    }

    private fun loadTaskData() {
        lifecycleScope.launch {
            val task = database.taskDao().getTaskById(taskId)
            if (task == null) {
                Toast.makeText(this@EditTaskActivity, "Task not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            originalTask = task

            binding.etTaskTitle.setText(task.title)
            binding.etTaskDescription.setText(task.description)

            try {
                selectedDate = LocalDate.parse(task.date, dbDateFormatter)
            } catch (e: Exception) {
                selectedDate = LocalDate.now()
            }
            binding.tvSelectedDate.text = selectedDate.format(dateFormatter)

            if (task.time.isNotBlank()) {
                try {
                    selectedTime = LocalTime.parse(task.time, timeFormatter)
                } catch (e: Exception) {
                    selectedTime = LocalTime.of(9, 0)
                }
            }
            binding.tvSelectedTime.text = selectedTime.format(timeFormatter)

            selectedPriority = when (task.priority.uppercase(Locale.US)) {
                "HIGH" -> "High"
                "LOW" -> "Low"
                else -> "Medium"
            }
            binding.tvSelectedPriority.text = selectedPriority

            selectedCategory = if (task.category.isNotBlank()) task.category else "Study"
            binding.tvSelectedCategory.text = selectedCategory

            selectedRepeat = if (task.repeatSchedule.isNotBlank()) task.repeatSchedule else "Does not repeat"
            binding.tvSelectedRepeat.text = selectedRepeat

            selectedReminder = if (task.reminder.isNotBlank()) task.reminder else "10 minutes before"
            binding.tvSelectedReminder.text = selectedReminder
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnDeleteTask.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete this task?")
                .setPositiveButton("Delete") { _, _ ->
                    originalTask?.let { task ->
                        lifecycleScope.launch {
                            database.taskDao().deleteTask(task)
                            TaskOverlayManager.invalidateCache()
                            TaskOverlayManager.warmCache(this@EditTaskActivity)
                            Toast.makeText(this@EditTaskActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Date selection
        binding.rowDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.set(selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth)

            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                    binding.tvSelectedDate.text = selectedDate.format(dateFormatter)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Time selection
        binding.rowTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedTime = LocalTime.of(hourOfDay, minute)
                    binding.tvSelectedTime.text = selectedTime.format(timeFormatter)
                },
                selectedTime.hour,
                selectedTime.minute,
                false
            ).show()
        }

        // Priority selection
        binding.rowPriority.setOnClickListener {
            val priorities = arrayOf("High", "Medium", "Low")
            AlertDialog.Builder(this)
                .setTitle("Select Priority")
                .setItems(priorities) { _, which ->
                    selectedPriority = priorities[which]
                    binding.tvSelectedPriority.text = selectedPriority
                }
                .show()
        }

        // Category selection
        binding.rowCategory.setOnClickListener {
            lifecycleScope.launch {
                val dbCategories = database.categoryDao().getAllCategories().map { it.name }
                val categories = if (dbCategories.isNotEmpty()) dbCategories.toTypedArray() else arrayOf("Study", "Work", "Personal", "Health", "Others")
                AlertDialog.Builder(this@EditTaskActivity)
                    .setTitle("Select Category")
                    .setItems(categories) { _, which ->
                        selectedCategory = categories[which]
                        binding.tvSelectedCategory.text = selectedCategory
                    }
                    .show()
            }
        }

        // Repeat selection
        binding.rowRepeat.setOnClickListener {
            val options = arrayOf("Does not repeat", "Daily", "Weekly", "Monthly")
            AlertDialog.Builder(this)
                .setTitle("Repeat Schedule")
                .setItems(options) { _, which ->
                    selectedRepeat = options[which]
                    binding.tvSelectedRepeat.text = selectedRepeat
                }
                .show()
        }

        // Reminder selection
        binding.rowReminder.setOnClickListener {
            val options = arrayOf(
                "None",
                "At time of task",
                "10 minutes before",
                "30 minutes before",
                "1 hour before",
                "1 day before"
            )
            AlertDialog.Builder(this)
                .setTitle("Reminder")
                .setItems(options) { _, which ->
                    selectedReminder = options[which]
                    binding.tvSelectedReminder.text = selectedReminder
                }
                .show()
        }

        // Save Changes
        binding.btnSaveTask.setOnClickListener {
            saveTaskChanges()
        }
    }

    private fun saveTaskChanges() {
        val title = binding.etTaskTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a task title", Toast.LENGTH_SHORT).show()
            binding.etTaskTitle.requestFocus()
            return
        }

        val taskToUpdate = originalTask ?: return
        val description = binding.etTaskDescription.text?.toString()?.trim().orEmpty()
        val dbDate = selectedDate.format(dbDateFormatter)
        val timeString = selectedTime.format(timeFormatter)

        val updatedTask = taskToUpdate.copy(
            title = title,
            description = description,
            date = dbDate,
            time = timeString,
            priority = selectedPriority.uppercase(Locale.US),
            category = selectedCategory,
            repeatSchedule = selectedRepeat,
            reminder = selectedReminder
        )

        lifecycleScope.launch {
            database.taskDao().updateTask(updatedTask)
            TaskOverlayManager.invalidateCache()
            TaskOverlayManager.warmCache(this@EditTaskActivity)
            Toast.makeText(this@EditTaskActivity, "Task updated", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
