package com.taskpeek.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val DISPLAY_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val REFERENCE_DATE_FORMAT = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)

    fun getTodayReferenceDateString(): String {
        return REFERENCE_DATE_FORMAT.format(Date())
    }

    fun getTodayDateString(): String {
        return DATE_FORMAT.format(Date())
    }

    fun isToday(dateStr: String): Boolean {
        return dateStr == getTodayDateString()
    }

    fun formatDisplayDate(dateStr: String): String {
        return if (isToday(dateStr)) {
            "Today"
        } else {
            try {
                val date = DATE_FORMAT.parse(dateStr)
                if (date != null) DISPLAY_FORMAT.format(date) else dateStr
            } catch (e: Exception) {
                dateStr
            }
        }
    }
}
