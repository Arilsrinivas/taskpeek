package com.taskpeek.app

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ActivityTaskDetailBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.util.DateUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TaskDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskDetailBinding
    private lateinit var database: AppDatabase
    private var currentTask: Task? = null
    private var taskId: Long = -1L

    private val dbDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val displayDateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)

        if (taskId == -1L) {
            Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupListeners()
        observeTask()
    }

    private fun observeTask() {
        lifecycleScope.launch {
            database.taskDao().getTaskByIdFlow(taskId).collectLatest { task ->
                if (task == null) {
                    finish()
                    return@collectLatest
                }
                currentTask = task
                bindTask(task)
            }
        }
    }

    private fun bindTask(task: Task) {
        binding.tvDetailTitle.text = task.title

        // Category pill
        val categoryText = if (task.category.isNotBlank()) task.category else "Personal"
        binding.tvDetailCategoryPill.text = categoryText

        // Date format
        val formattedDate = try {
            val parsed = LocalDate.parse(task.date, dbDateFormatter)
            parsed.format(displayDateFormatter)
        } catch (e: Exception) {
            DateUtils.formatDisplayDate(task.date)
        }
        binding.tvDetailDate.text = formattedDate

        // Time
        binding.tvDetailTime.text = if (task.time.isNotBlank()) task.time else "9:00 AM"

        // Reminder & Repeat
        binding.tvDetailReminder.text = if (task.reminder.isNotBlank()) task.reminder else "10 minutes before"
        binding.tvDetailRepeat.text = if (task.repeatSchedule.isNotBlank()) task.repeatSchedule else "Does not repeat"

        // Description
        if (task.description.isNotBlank()) {
            binding.tvDetailDescription.text = task.description
            binding.tvDetailDescription.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            binding.tvDetailDescription.text = "No description provided."
            binding.tvDetailDescription.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }

        // Completion state
        if (task.isCompleted) {
            binding.ivDetailCompletion.setImageResource(R.drawable.ic_circle_checked)
            binding.tvDetailTitle.paintFlags = binding.tvDetailTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.tvDetailTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleCompleteAction.text = "Mark as Incomplete"
        } else {
            binding.ivDetailCompletion.setImageResource(R.drawable.ic_circle_outline)
            binding.tvDetailTitle.paintFlags = binding.tvDetailTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.tvDetailTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleCompleteAction.text = "Mark as Complete"
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnEditTask.setOnClickListener {
            currentTask?.let { task ->
                val intent = Intent(this, EditTaskActivity::class.java).apply {
                    putExtra(EditTaskActivity.EXTRA_TASK_ID, task.id)
                }
                startActivity(intent)
            }
        }

        binding.btnDeleteTask.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete this task?")
                .setPositiveButton("Delete") { _, _ ->
                    currentTask?.let { task ->
                        lifecycleScope.launch {
                            database.taskDao().deleteTask(task)
                            TaskOverlayManager.invalidateCache()
                            TaskOverlayManager.warmCache(this@TaskDetailActivity)
                            Toast.makeText(this@TaskDetailActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val toggleAction = View.OnClickListener {
            currentTask?.let { task ->
                val newStatus = !task.isCompleted
                val completedTime = if (newStatus) System.currentTimeMillis() else 0L
                lifecycleScope.launch {
                    database.taskDao().setTaskCompletedWithTime(task.id, newStatus, completedTime)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@TaskDetailActivity)
                }
            }
        }

        binding.ivDetailCompletion.setOnClickListener(toggleAction)
        binding.btnToggleCompleteAction.setOnClickListener(toggleAction)
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
