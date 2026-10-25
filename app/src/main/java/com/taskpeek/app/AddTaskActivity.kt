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
import com.taskpeek.app.databinding.ActivityAddTaskBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.util.DateUtils
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class AddTaskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddTaskBinding
    private lateinit var database: AppDatabase

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
        binding = ActivityAddTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        setupDefaultValues()
        setupListeners()
    }

    private fun setupDefaultValues() {
        binding.tvSelectedDate.text = selectedDate.format(dateFormatter)
        binding.tvSelectedTime.text = selectedTime.format(timeFormatter)
        binding.tvSelectedPriority.text = selectedPriority
        binding.tvSelectedCategory.text = selectedCategory
        binding.tvSelectedRepeat.text = selectedRepeat
        binding.tvSelectedReminder.text = selectedReminder
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
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
                AlertDialog.Builder(this@AddTaskActivity)
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

        // Save Task
        binding.btnSaveTask.setOnClickListener {
            saveTask()
        }
    }

    private fun saveTask() {
        val title = binding.etTaskTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a task title", Toast.LENGTH_SHORT).show()
            binding.etTaskTitle.requestFocus()
            return
        }

        val description = binding.etTaskDescription.text?.toString()?.trim().orEmpty()
        val dbDate = selectedDate.format(dbDateFormatter)
        val timeString = selectedTime.format(timeFormatter)

        val task = Task(
            title = title,
            description = description,
            date = dbDate,
            time = timeString,
            priority = selectedPriority.uppercase(Locale.US),
            category = selectedCategory,
            repeatSchedule = selectedRepeat,
            reminder = selectedReminder,
            isCompleted = false,
            createdAt = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            database.taskDao().insertTask(task)
            TaskOverlayManager.invalidateCache()
            TaskOverlayManager.warmCache(this@AddTaskActivity)
            Toast.makeText(this@AddTaskActivity, "Task saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
