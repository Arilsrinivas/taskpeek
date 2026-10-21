package com.taskpeek.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateUtils {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val REFERENCE_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

    fun getTodayReferenceDateString(): String {
        return LocalDate.now().format(REFERENCE_DATE_FORMATTER)
    }

    fun getTodayDateString(): String {
        return LocalDate.now().format(DATE_FORMATTER)
    }

    fun getTomorrowDateString(): String {
        return LocalDate.now().plusDays(1).format(DATE_FORMATTER)
    }

    fun isToday(dateStr: String): Boolean {
        return dateStr == getTodayDateString()
    }

    fun isYesterday(dateStr: String): Boolean {
        return try {
            val taskDate = LocalDate.parse(dateStr, DATE_FORMATTER)
            taskDate == LocalDate.now().minusDays(1)
        } catch (e: Exception) {
            false
        }
    }

    fun formatDisplayDate(dateStr: String): String {
        return when {
            isToday(dateStr) -> "Today"
            isYesterday(dateStr) -> "Yesterday"
            else -> {
                try {
                    val parsed = LocalDate.parse(dateStr, DATE_FORMATTER)
                    parsed.format(DISPLAY_FORMATTER)
                } catch (e: Exception) {
                    dateStr
                }
            }
        }
    }
}
