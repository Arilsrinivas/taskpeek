package com.taskpeek.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.databinding.ActivityCompletedBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.ui.TaskAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CompletedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompletedBinding
    private lateinit var database: AppDatabase
    private lateinit var taskAdapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompletedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        setupRecyclerView()
        setupListeners()
        observeCompletedTasks()
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            onToggleComplete = { task, isCompleted ->
                lifecycleScope.launch {
                    val completedTime = if (isCompleted) System.currentTimeMillis() else 0L
                    database.taskDao().setTaskCompletedWithTime(task.id, isCompleted, completedTime)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@CompletedActivity)
                }
            },
            onTaskClick = { task ->
                val intent = Intent(this, TaskDetailActivity::class.java).apply {
                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.id)
                }
                startActivity(intent)
            }
        )

        binding.rvCompletedTasks.layoutManager = LinearLayoutManager(this)
        binding.rvCompletedTasks.adapter = taskAdapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun observeCompletedTasks() {
        lifecycleScope.launch {
            database.taskDao().getCompletedTasksFlow().collectLatest { tasks ->
                taskAdapter.submitList(tasks)
                binding.tvEmptyCompleted.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
