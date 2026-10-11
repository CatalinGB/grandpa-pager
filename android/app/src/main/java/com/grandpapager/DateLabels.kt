package com.grandpapager

import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-aware idle labels for the one-row date and the clock, e.g.
 *   "SATURDAY 10/10/2026"  (uppercase weekday + numeric date, date-first order)
 *   "20:34"               (device locale time format)
 * The weekday name is localised; the numeric date follows the admin's format
 * choice, and "auto" follows the device locale's short date order.
 */
object DateLabels {

    private fun locale(context: Context): Locale =
        context.resources.configuration.locales[0]

    /** Numeric date patterns for the admin-configurable idle date format. */
    fun pattern(format: String, loc: Locale): String = when (format) {
        "dmy" -> "dd/MM/yyyy"
        "mdy" -> "MM/dd/yyyy"
        "ymd" -> "yyyy-MM-dd"
        else -> localeDatePattern(loc) // "auto" follows the device locale's order
    }

    /**
     * The device locale's short numeric date pattern (e.g. `M/d/yy` in the US,
     * `dd/MM/y` in the UK), so `auto` honours the locale instead of a fixed
     * date-first order. Falls back to `dd/MM/yyyy` if unavailable.
     */
    private fun localeDatePattern(loc: Locale): String {
        val df = DateFormat.getDateInstance(DateFormat.SHORT, loc)
        return if (df is SimpleDateFormat) df.toPattern() else "dd/MM/yyyy"
    }

    fun dateLine(context: Context, millis: Long, format: String = "auto"): String {
        val loc = locale(context)
        val weekday = SimpleDateFormat("EEEE", loc).format(Date(millis)).uppercase(loc)
        val numeric = SimpleDateFormat(pattern(format, loc), loc).format(Date(millis))
        return "$weekday $numeric"
    }

    fun timeLine(context: Context, millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale(context)).format(Date(millis))
}
