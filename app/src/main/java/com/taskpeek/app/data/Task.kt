package com.taskpeek.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val date: String, // Format: YYYY-MM-DD
    val priority: String = "MEDIUM", // "HIGH", "MEDIUM", "LOW"
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val time: String = "", // e.g. "9:00 AM"
    val category: String = "Others", // "Study", "Work", "Personal", "Health", "Others"
    val repeatSchedule: String = "Does not repeat", // "Does not repeat", "Daily", "Weekly", "Monthly"
    val reminder: String = "10 minutes before",
    val completedAt: Long = 0L
)
