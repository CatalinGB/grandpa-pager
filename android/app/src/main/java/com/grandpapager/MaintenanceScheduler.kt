package com.grandpapager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.TimeZone

/**
 * Arms the on-device maintenance alarms.
 *
 * These are real AlarmManager alarms, not Handler callbacks: they survive a
 * killed process, they are re-armed after a reboot, and they do not depend on
 * the MQTT link in any way. That is the whole point — the pager must be able to
 * restart/reboot itself even when it can no longer be reached over the broker.
 *
 * Three alarms:
 *  - self-check: every [CHECK_MINUTES] minutes, asks [MaintenancePolicy] whether
 *    the pager has been silent for too long;
 *  - scheduled restart: every `restart_every_hours`, anchored at `at_hour`;
 *  - scheduled reboot: every `reboot_every_days`, anchored at `at_hour`.
 */
object MaintenanceScheduler {
    private const val TAG = "MaintenanceScheduler"

    const val ACTION_CHECK = "com.grandpapager.action.MAINTENANCE_CHECK"
    const val ACTION_SCHEDULED_RESTART = "com.grandpapager.action.MAINTENANCE_RESTART"
    const val ACTION_SCHEDULED_REBOOT = "com.grandpapager.action.MAINTENANCE_REBOOT"

    private const val REQ_CHECK = 4101
    private const val REQ_RESTART = 4102
    private const val REQ_REBOOT = 4103

    /** How often the supervisor wakes up. Kept well below the shortest threshold. */
    private const val CHECK_MINUTES = 10L

    /** Re-arm everything. Cheap and idempotent; safe to call on every start/apply. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val cfg = Config(app)
        val plan = cfg.remoteConfig().maintenance
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        cancel(app, am, REQ_CHECK, ACTION_CHECK)
        cancel(app, am, REQ_RESTART, ACTION_SCHEDULED_RESTART)
        cancel(app, am, REQ_REBOOT, ACTION_SCHEDULED_REBOOT)

        // Receiver only: the sender build is a normal phone and must never reboot
        // itself just because it shares the retained config.
        if (!cfg.isConfigured() || cfg.role != Role.RECEIVER || !plan.enabled) {
            Log.i(TAG, "maintenance not armed (role=${cfg.role}, enabled=${plan.enabled})")
            return
        }

        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()

        setAt(app, am, REQ_CHECK, now + CHECK_MINUTES * MaintenancePolicy.MINUTE_MS, ACTION_CHECK)

        if (plan.restart_every_hours > 0) {
            val at = MaintenancePolicy.nextRunAt(
                now, cfg.lastScheduledRestartAt,
                plan.restart_every_hours * MaintenancePolicy.HOUR_MS, plan.at_hour, tz
            )
            setAt(app, am, REQ_RESTART, at, ACTION_SCHEDULED_RESTART)
        }
        if (plan.reboot_every_days > 0) {
            val at = MaintenancePolicy.nextRunAt(
                now, cfg.lastScheduledRebootAt,
                plan.reboot_every_days * MaintenancePolicy.DAY_MS, plan.at_hour, tz
            )
            setAt(app, am, REQ_REBOOT, at, ACTION_SCHEDULED_REBOOT)
        }
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(app, am, REQ_CHECK, ACTION_CHECK)
        cancel(app, am, REQ_RESTART, ACTION_SCHEDULED_RESTART)
        cancel(app, am, REQ_REBOOT, ACTION_SCHEDULED_REBOOT)
    }

    private fun setAt(context: Context, am: AlarmManager, req: Int, atMs: Long, action: String) {
        if (atMs <= 0L || atMs == MaintenancePolicy.NEVER) return
        val pi = pending(context, req, action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }.onFailure { Log.w(TAG, "could not arm $action", it) }
    }

    private fun cancel(context: Context, am: AlarmManager, req: Int, action: String) {
        runCatching { am.cancel(pending(context, req, action)) }
    }

    private fun pending(context: Context, req: Int, action: String): PendingIntent {
        val i = Intent(context, MaintenanceReceiver::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, req, i, flags)
    }
}
