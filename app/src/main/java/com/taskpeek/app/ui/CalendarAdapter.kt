package com.taskpeek.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.taskpeek.app.R
import com.taskpeek.app.databinding.ItemCalendarDayBinding
import java.time.LocalDate

class CalendarAdapter(
    private val onDateSelected: (LocalDate) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.DayViewHolder>() {

    data class DayModel(
        val date: LocalDate,
        val isCurrentMonth: Boolean,
        val isSelected: Boolean,
        val hasTasks: Boolean
    )

    private val days = mutableListOf<DayModel>()

    fun submitDays(newDays: List<DayModel>) {
        days.clear()
        days.addAll(newDays)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val binding = ItemCalendarDayBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount() = days.size

    inner class DayViewHolder(private val binding: ItemCalendarDayBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(day: DayModel) {
            val context = itemView.context
            binding.tvDayNumber.text = day.date.dayOfMonth.toString()

            if (day.isSelected) {
                // White rounded square background with black text
                binding.tvDayNumber.setBackgroundResource(R.drawable.bg_calendar_selected)
                binding.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.btn_primary_text))
            } else if (day.date == LocalDate.now()) {
                // Today indicator
                binding.tvDayNumber.setBackgroundResource(R.drawable.bg_calendar_today)
                binding.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else {
                binding.tvDayNumber.setBackgroundColor(Color.TRANSPARENT)
                if (day.isCurrentMonth) {
                    binding.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                } else {
                    binding.tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                }
            }

            // Task indicator dot
            binding.viewTaskDot.visibility = if (day.hasTasks && !day.isSelected) View.VISIBLE else View.INVISIBLE

            itemView.setOnClickListener {
                onDateSelected(day.date)
            }
        }
    }
}
