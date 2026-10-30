package com.grandpapager

import android.app.Application

/**
 * Records the last uncaught exception before the process dies. Without this a
 * crash and a power cut look identical from the broker: both are silence.
 */
class PagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagCollector.recordCrash(this, error) }
            // Never swallow the crash: the platform handler must still run
            // (it shows the force-close/tombstone behaviour the OS expects).
            previous?.uncaughtException(thread, error)
        }
    }
}
