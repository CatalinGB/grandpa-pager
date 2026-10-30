package com.grandpapager

import java.util.Calendar
import java.util.TimeZone

/** What the on-device supervisor decided to do. */
enum class MaintenanceAction { NONE, RESTART_APP, REBOOT_DEVICE }

/**
 * Pure decision logic for the on-device maintenance supervisor.
 *
 * Everything here is deliberately free of Android APIs so it can be unit-tested
 * on the JVM. The scheduling/execution lives in [MaintenanceScheduler] and
 * [MaintenanceReceiver]; this object only answers two questions:
 *
 *  1. When is the next scheduled maintenance run?
 *  2. Given the age of the last successful broker contact, should we restart the
 *     app, reboot the phone, or do nothing?
 *
 * The important property is that none of it needs the MQTT link: the plan is
 * already on the device (persisted remote config), so a dead broker or a dead
 * socket can never stop a restart/reboot from happening.
 */
object MaintenancePolicy {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val NEVER = Long.MAX_VALUE

    /** Never reboot a phone that is running out of battery — a reboot may not come back. */
    const val BATTERY_FLOOR_PCT = 15

    /** Keep the reboot threshold meaningfully above the restart threshold. */
    const val MIN_GAP_MINUTES = 15

    /**
     * The next moment a scheduled run is due: never in the past, at least
     * [intervalMs] after [lastRunMs], and — for daily-or-longer cycles — at
     * `atHour` local time so maintenance never lands in the middle of the day.
     *
     * A `lastRunMs` of 0 means "never ran": daily cycles wait for the next
     * `atHour`, shorter cycles wait one full interval (no surprise restart right
     * after the admin saves a config).
     */
    fun nextRunAt(
        nowMs: Long,
        lastRunMs: Long,
        intervalMs: Long,
        atHour: Int,
        tz: TimeZone = TimeZone.getDefault()
    ): Long {
        if (intervalMs <= 0L) return NEVER
        if (intervalMs < DAY_MS) {
            return if (lastRunMs > 0L) maxOf(lastRunMs + intervalMs, nowMs) else nowMs + intervalMs
        }
        val hour = atHour.coerceIn(0, 23)
        val earliest = if (lastRunMs > 0L) maxOf(lastRunMs + intervalMs, nowMs) else nowMs
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = earliest
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
        }
        if (c.timeInMillis < earliest) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /**
     * Decide what to do about a silent pager.
     *
     * Escalation order: restart the app first (cheap, fixes a hung socket or a
     * killed service), and only reboot the phone once that restart has already
     * been tried since the last successful publish, the longer threshold has
     * passed, the reboot cooldown is over and the battery is not critically low.
     *
     * @param lastPublishMs epoch millis of the last *successful* publish/connect,
     *   0 when the app has never talked to the broker.
     * @param batteryPct current battery level, null when unknown.
     */
    fun decide(
        nowMs: Long,
        plan: RemoteConfig.Maintenance,
        lastPublishMs: Long,
        lastRestartMs: Long,
        lastRebootMs: Long,
        batteryPct: Int?
    ): MaintenanceAction {
        if (!plan.enabled) return MaintenanceAction.NONE
        // Never talked, or the clock moved backwards (NTP): no verdict.
        if (lastPublishMs <= 0L || lastPublishMs > nowMs) return MaintenanceAction.NONE

        val staleMinutes = (nowMs - lastPublishMs) / MINUTE_MS
        val restartAfter = plan.stale_restart_minutes
        val rebootAfter = plan.stale_reboot_minutes

        val restartAlreadyTried = lastRestartMs > lastPublishMs
        val cooldownOver = plan.reboot_cooldown_minutes <= 0 ||
            lastRebootMs <= 0L ||
            nowMs - lastRebootMs >= plan.reboot_cooldown_minutes * MINUTE_MS
        val batteryOk = batteryPct == null || batteryPct >= BATTERY_FLOOR_PCT

        if (rebootAfter > 0 && staleMinutes >= rebootAfter &&
            restartAlreadyTried && cooldownOver && batteryOk
        ) {
            return MaintenanceAction.REBOOT_DEVICE
        }
        if (restartAfter > 0 && staleMinutes >= restartAfter) {
            return MaintenanceAction.RESTART_APP
        }
        return MaintenanceAction.NONE
    }

    /** True when a status payload age means "the device is not really online". */
    fun isStale(nowMs: Long, lastPublishMs: Long, plan: RemoteConfig.Maintenance): Boolean {
        if (lastPublishMs <= 0L) return true
        if (!plan.enabled || plan.stale_restart_minutes <= 0) {
            // Without maintenance the caller still wants a staleness read.
            return nowMs - lastPublishMs > 45 * MINUTE_MS
        }
        return nowMs - lastPublishMs >= plan.stale_restart_minutes * MINUTE_MS
    }
}
