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

    fun dateLine(context: Context, millis: Long): String {
        val loc = locale(context)
        val weekday = SimpleDateFormat("EEEE", loc).format(Date(millis)).uppercase(loc)
        val numeric = SimpleDateFormat("dd/MM/yyyy", loc).format(Date(millis))
        return "$weekday $numeric"
    }

    fun timeLine(context: Context, millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale(context)).format(Date(millis))
}
