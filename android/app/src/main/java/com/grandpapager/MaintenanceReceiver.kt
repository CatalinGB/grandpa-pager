package com.grandpapager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Executes the maintenance plan. Runs from an AlarmManager broadcast, so it works
 * with the process dead, the socket dead and the broker unreachable.
 */
class MaintenanceReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "MaintenanceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val cfg = Config(app)
        val now = System.currentTimeMillis()
        val action = intent?.action

        try {
            when (action) {
                MaintenanceScheduler.ACTION_CHECK -> runSelfCheck(app, cfg, now)
                MaintenanceScheduler.ACTION_SCHEDULED_RESTART -> {
                    Log.i(TAG, "scheduled app restart")
                    cfg.lastScheduledRestartAt = now
                    restartService(app, "scheduled")
                }
                MaintenanceScheduler.ACTION_SCHEDULED_REBOOT -> {
                    Log.i(TAG, "scheduled phone reboot")
                    cfg.lastScheduledRebootAt = now
                    cfg.lastRebootReason = "scheduled"
                    if (!DeviceOwnerManager.reboot(app)) {
                        Log.w(TAG, "scheduled reboot refused (not device owner?)")
                    }
                }
                else -> Log.w(TAG, "unknown maintenance action: $action")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "maintenance action failed: $action", t)
        } finally {
            // Always re-arm: the alarm that fired has just been consumed.
            MaintenanceScheduler.reschedule(app)
        }
    }

    /**
     * The silence check. Restart the app first; reboot the phone only when that
     * restart already happened after the last good publish and the longer
     * threshold and cooldown have passed (see [MaintenancePolicy.decide]).
     */
    private fun runSelfCheck(app: Context, cfg: Config, now: Long) {
        val plan = cfg.remoteConfig().maintenance
        val battery = batteryPercent(app)
        val action = MaintenancePolicy.decide(
            nowMs = now,
            plan = plan,
            lastPublishMs = cfg.lastPublishAt,
            lastRestartMs = cfg.lastStaleRestartAt,
            lastRebootMs = cfg.lastStaleRebootAt,
            batteryPct = battery
        )
        val silentMin = if (cfg.lastPublishAt > 0L) (now - cfg.lastPublishAt) / MaintenancePolicy.MINUTE_MS else -1

        when (action) {
            MaintenanceAction.RESTART_APP -> {
                Log.w(TAG, "no broker contact for ${silentMin}min; restarting the app")
                cfg.lastStaleRestartAt = now
                restartService(app, "stale")
            }
            MaintenanceAction.REBOOT_DEVICE -> {
                Log.w(TAG, "still silent after a restart (${silentMin}min); rebooting the phone")
                cfg.lastStaleRebootAt = now
                cfg.lastRebootReason = "stale"
                if (!DeviceOwnerManager.reboot(app)) {
                    Log.w(TAG, "stale reboot refused (not device owner?)")
                }
            }
            MaintenanceAction.NONE -> Log.d(TAG, "self-check ok (silent ${silentMin}min, battery ${battery ?: "?"}%)")
        }
    }

    private fun restartService(app: Context, reason: String) {
        runCatching { PagerService.start(app, forceRestart = true, reason = reason) }
            .onFailure { Log.w(TAG, "could not start the pager service", it) }
    }

    private fun batteryPercent(context: Context): Int? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).toInt()
    }
}
