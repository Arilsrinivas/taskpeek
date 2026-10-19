package com.taskpeek.app.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskpeek.app.R
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ItemTaskBinding
import com.taskpeek.app.util.DateUtils

class TaskAdapter(
    private val onToggleComplete: (Task, Boolean) -> Unit,
    private val onDelete: (Task) -> Unit
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
            binding.cbCompleted.setOnCheckedChangeListener(null)
            binding.cbCompleted.isChecked = task.isCompleted

            if (task.isCompleted) {
                binding.tvTaskTitle.paintFlags =
                    binding.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            } else {
                binding.tvTaskTitle.paintFlags =
                    binding.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }

            binding.cbCompleted.setOnCheckedChangeListener { _, isChecked ->
                onToggleComplete(task, isChecked)
            }

            // Date display
            binding.tvTaskDate.text = DateUtils.formatDisplayDate(task.date)

            // Priority badge styling
            when (task.priority.uppercase()) {
                "HIGH" -> {
                    binding.tvTaskPriority.text = "HIGH"
                    binding.tvTaskPriority.setTextColor(
                        ContextCompat.getColor(context, R.color.priority_high)
                    )
                    binding.tvTaskPriority.background =
                        ContextCompat.getDrawable(context, R.drawable.bg_badge_high)
                }
                "LOW" -> {
                    binding.tvTaskPriority.text = "LOW"
                    binding.tvTaskPriority.setTextColor(
                        ContextCompat.getColor(context, R.color.priority_low)
                    )
                    binding.tvTaskPriority.background =
                        ContextCompat.getDrawable(context, R.drawable.bg_badge_low)
                }
                else -> {
                    binding.tvTaskPriority.text = "MED"
                    binding.tvTaskPriority.setTextColor(
                        ContextCompat.getColor(context, R.color.priority_medium)
                    )
                    binding.tvTaskPriority.background =
                        ContextCompat.getDrawable(context, R.drawable.bg_badge_medium)
                }
            }

            binding.btnDeleteTask.setOnClickListener {
                onDelete(task)
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
