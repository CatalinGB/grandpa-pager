package com.grandpapager

import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-aware idle labels for the one-row date and the clock, e.g.
 *   "SÂMBĂTĂ 10/10/2026"  (uppercase weekday + numeric date, date-first order)
 *   "20:34"               (device locale time format)
 * The weekday name is localised; the numeric date is fixed dd/MM/yyyy.
 */
object DateLabels {

    private fun locale(context: Context): Locale =
        context.resources.configuration.locales[0]

    /** Numeric date patterns for the admin-configurable idle date format. */
    fun pattern(format: String): String = when (format) {
        "mdy" -> "MM/dd/yyyy"
        "ymd" -> "yyyy-MM-dd"
        else -> "dd/MM/yyyy" // "auto" keeps the date-first order
    }

    fun dateLine(context: Context, millis: Long, format: String = "auto"): String {
        val loc = locale(context)
        val weekday = SimpleDateFormat("EEEE", loc).format(Date(millis)).uppercase(loc)
        val numeric = SimpleDateFormat(pattern(format), loc).format(Date(millis))
        return "$weekday $numeric"
    }

    fun timeLine(context: Context, millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale(context)).format(Date(millis))
}
