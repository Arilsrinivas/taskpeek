package com.taskpeek.app.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskpeek.app.R
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskAdapter(
    private val onToggleComplete: (Task, Boolean) -> Unit,
    private val onTaskClick: (Task) -> Unit
) : ListAdapter<Task, TaskAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(task: Task) {
            val context = itemView.context

            binding.tvTaskTitle.text = task.title

            // Handle completion styling
            if (task.isCompleted) {
                binding.ivCompletionCircle.setImageResource(R.drawable.ic_circle_checked)
                binding.tvTaskTitle.paintFlags =
                    binding.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            } else {
                binding.ivCompletionCircle.setImageResource(R.drawable.ic_circle_outline)
                binding.tvTaskTitle.paintFlags =
                    binding.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }

            // Subtitle: Category or clean category label
            val categoryText = if (task.category.isNotBlank() && task.category != "Others") {
                task.category
            } else {
                // Infer category from title keywords if Others
                val lower = task.title.lowercase()
                when {
                    lower.contains("study") || lower.contains("book") || lower.contains("read") || lower.contains("exam") || lower.contains("notes") || lower.contains("revision") -> "Study"
                    lower.contains("work") || lower.contains("project") || lower.contains("taskpeek") || lower.contains("code") || lower.contains("pr") || lower.contains("dev") -> "Work"
                    lower.contains("gym") || lower.contains("run") || lower.contains("workout") || lower.contains("health") || lower.contains("walk") -> "Health"
                    lower.contains("plan") || lower.contains("call") || lower.contains("buy") || lower.contains("personal") -> "Personal"
                    else -> "Personal"
                }
            }
            binding.tvTaskSubtitle.text = categoryText
            binding.tvTaskSubtitle.visibility = View.VISIBLE

            // Scheduled time display
            val timeText = when {
                task.time.isNotBlank() -> task.time
                task.createdAt > 0L -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(task.createdAt))
                else -> "9:00 AM"
            }
            binding.tvTaskTime.text = timeText

            // Circular completion toggle
            binding.ivCompletionCircle.setOnClickListener {
                onToggleComplete(task, !task.isCompleted)
            }

            // Full row click to view task detail
            itemView.setOnClickListener {
                onTaskClick(task)
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean {
            return oldItem == newItem
        }
    }
}
