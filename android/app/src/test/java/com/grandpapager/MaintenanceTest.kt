package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The on-device supervisor is the only recovery path that still works when the
 * pager cannot be reached over MQTT, so its arithmetic and escalation rules are
 * covered here rather than being discovered on Grandpa's phone.
 */
class MaintenanceTest {

    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Bucharest")
    private val plan = RemoteConfig.Maintenance()

    /** Local wall-clock helper (month is 1-based, like a human writes it). */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun hourOf(ms: Long): Int =
        Calendar.getInstance(tz).apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

    // ------------------------------------------------------------ next run time

    @Test
    fun nextRun_usesTodayWhenTheHourIsStillAhead() {
        val now = at(2026, 10, 30, 2, 15)
        val next = MaintenancePolicy.nextRunAt(now, 0L, MaintenancePolicy.DAY_MS, 4, tz)
        assertEquals(at(2026, 10, 30, 4), next)
    }

    @Test
    fun nextRun_rollsToTomorrowWhenTheHourHasPassed() {
        val now = at(2026, 10, 30, 10, 0)
        val next = MaintenancePolicy.nextRunAt(now, 0L, MaintenancePolicy.DAY_MS, 4, tz)
        assertEquals(at(2026, 10, 31, 4), next)
    }

    @Test
    fun nextRun_isNeverInThePast() {
        val now = at(2026, 10, 30, 4, 0) + 1 // one millisecond after today's anchor
        val next = MaintenancePolicy.nextRunAt(now, 0L, MaintenancePolicy.DAY_MS, 4, tz)
        assertTrue(next > now)
        assertEquals(at(2026, 10, 31, 4), next)
    }

    @Test
    fun nextRun_respectsTheWholeInterval() {
        val last = at(2026, 10, 29, 4)
        val now = at(2026, 10, 30, 9)
        // Every 3 days: the next one is 3 days after the last run, at 04:00.
        val next = MaintenancePolicy.nextRunAt(now, last, 3 * MaintenancePolicy.DAY_MS, 4, tz)
        assertEquals(at(2026, 11, 1, 4), next)
    }

    @Test
    fun nextRun_shortIntervalStillAlignsToTheHour() {
        val last = at(2026, 10, 30, 4)
        val now = at(2026, 10, 30, 5, 30)
        // Every 6h with a 04:00 anchor: next slot is 04:00 + 6h = 10:00.
        val next = MaintenancePolicy.nextRunAt(now, last, 6 * MaintenancePolicy.HOUR_MS, 4, tz)
        assertTrue(next > now)
        assertTrue(next - last >= 6 * MaintenancePolicy.HOUR_MS)
        assertEquals(10, hourOf(next))
    }

    @Test
    fun nextRun_zeroIntervalMeansNever() {
        assertEquals(MaintenancePolicy.NEVER, MaintenancePolicy.nextRunAt(1_000L, 0L, 0L, 4, tz))
    }

    @Test
    fun nextRun_clampsTheHour() {
        val now = at(2026, 10, 30, 0, 5)
        val next = MaintenancePolicy.nextRunAt(now, 0L, MaintenancePolicy.DAY_MS, 99, tz)
        assertEquals(23, hourOf(next))
    }

    // --------------------------------------------------------------- escalation

    private val now = 1_800_000_000_000L

    private fun decide(
        staleMinutes: Long,
        lastRestartAfterPublish: Boolean = false,
        lastRebootAgoMin: Long? = null,
        battery: Int? = 80,
        p: RemoteConfig.Maintenance = plan
    ): MaintenanceAction {
        val lastPublish = now - staleMinutes * MaintenancePolicy.MINUTE_MS
        val lastRestart = if (lastRestartAfterPublish) now - 60_000L else 0L
        val lastReboot = lastRebootAgoMin?.let { now - it * MaintenancePolicy.MINUTE_MS } ?: 0L
        return MaintenancePolicy.decide(now, p, lastPublish, lastRestart, lastReboot, battery)
    }

    @Test
    fun freshPagerDoesNothing() {
        assertEquals(MaintenanceAction.NONE, decide(staleMinutes = 10))
        assertEquals(MaintenanceAction.NONE, decide(staleMinutes = 44))
    }

    @Test
    fun silentPagerGetsAnAppRestartFirst() {
        assertEquals(MaintenanceAction.RESTART_APP, decide(staleMinutes = 45))
        assertEquals(MaintenanceAction.RESTART_APP, decide(staleMinutes = 179))
        // Even at the reboot threshold, a restart that has not been tried yet wins.
        assertEquals(MaintenanceAction.RESTART_APP, decide(staleMinutes = 180))
    }

    @Test
    fun stillSilentAfterTheRestartRebootsThePhone() {
        assertEquals(
            MaintenanceAction.REBOOT_DEVICE,
            decide(staleMinutes = 180, lastRestartAfterPublish = true)
        )
    }

    @Test
    fun rebootCooldownIsHonoured() {
        assertEquals(
            MaintenanceAction.RESTART_APP,
            decide(staleMinutes = 180, lastRestartAfterPublish = true, lastRebootAgoMin = 60)
        )
        // cooldown is 360 min by default
        assertEquals(
            MaintenanceAction.REBOOT_DEVICE,
            decide(staleMinutes = 180, lastRestartAfterPublish = true, lastRebootAgoMin = 361)
        )
    }

    @Test
    fun aNearlyFlatBatteryNeverReboots() {
        assertEquals(
            MaintenanceAction.RESTART_APP,
            decide(staleMinutes = 400, lastRestartAfterPublish = true, battery = 10)
        )
    }

    @Test
    fun unknownBatteryDoesNotBlockTheReboot() {
        assertEquals(
            MaintenanceAction.REBOOT_DEVICE,
            decide(staleMinutes = 400, lastRestartAfterPublish = true, battery = null)
        )
    }

    @Test
    fun disabledMaintenanceNeverActs() {
        assertEquals(
            MaintenanceAction.NONE,
            decide(staleMinutes = 10_000, lastRestartAfterPublish = true, p = plan.copy(enabled = false))
        )
    }

    @Test
    fun thresholdsOfZeroDisableThatStep() {
        val noRestart = plan.copy(stale_restart_minutes = 0)
        assertEquals(MaintenanceAction.NONE, decide(staleMinutes = 100, p = noRestart))

        val noReboot = plan.copy(stale_reboot_minutes = 0)
        assertEquals(
            MaintenanceAction.RESTART_APP,
            decide(staleMinutes = 10_000, lastRestartAfterPublish = true, p = noReboot)
        )
    }

    @Test
    fun aPagerThatNeverPublishedIsNotJudged() {
        assertEquals(
            MaintenanceAction.NONE,
            MaintenancePolicy.decide(now, plan, 0L, 0L, 0L, 80)
        )
    }

    @Test
    fun aClockThatJumpedBackwardsIsNotTreatedAsSilence() {
        assertEquals(
            MaintenanceAction.NONE,
            MaintenancePolicy.decide(now, plan, now + 60_000L, 0L, 0L, 80)
        )
    }

    @Test
    fun customThresholdsAreRespected() {
        val fast = plan.copy(stale_restart_minutes = 10, stale_reboot_minutes = 20, reboot_cooldown_minutes = 60)
        assertEquals(MaintenanceAction.NONE, decide(staleMinutes = 9, p = fast))
        assertEquals(MaintenanceAction.RESTART_APP, decide(staleMinutes = 10, p = fast))
        assertEquals(
            MaintenanceAction.REBOOT_DEVICE,
            decide(staleMinutes = 20, lastRestartAfterPublish = true, p = fast)
        )
    }

    // ------------------------------------------------------------------- config

    @Test
    fun maintenanceDefaultsWhenTheAdminHasNotSentIt() {
        val c = RemoteConfigCodec.parse("{\"schema\":1}")
        assertEquals(
            RemoteConfig.Maintenance(
                enabled = true,
                restart_every_hours = 24,
                reboot_every_days = 3,
                at_hour = 4,
                stale_restart_minutes = 45,
                stale_reboot_minutes = 180,
                reboot_cooldown_minutes = 360
            ),
            c!!.maintenance
        )
    }

    @Test
    fun maintenanceIsParsedAndClamped() {
        val c = RemoteConfigCodec.parse(
            """{"schema":1,"maintenance":{"enabled":false,"restart_every_hours":6,
               "reboot_every_days":1,"at_hour":99,"stale_restart_minutes":5,
               "stale_reboot_minutes":99999,"reboot_cooldown_minutes":-4}}"""
        )!!.maintenance
        assertEquals(false, c.enabled)
        assertEquals(6, c.restart_every_hours)
        assertEquals(1, c.reboot_every_days)
        assertEquals(23, c.at_hour)
        assertEquals(5, c.stale_restart_minutes)
        assertEquals(2880, c.stale_reboot_minutes)
        assertEquals(0, c.reboot_cooldown_minutes)
    }

    @Test
    fun rebootThresholdAlwaysStaysAboveTheRestartThreshold() {
        val c = RemoteConfigCodec.parse(
            """{"schema":1,"maintenance":{"stale_restart_minutes":45,"stale_reboot_minutes":30}}"""
        )!!.maintenance
        assertEquals(45, c.stale_restart_minutes)
        assertEquals(60, c.stale_reboot_minutes)
    }

    @Test
    fun maintenanceSurvivesAJsonRoundTrip() {
        val c = RemoteConfigCodec.parse(
            """{"schema":1,"maintenance":{"enabled":true,"restart_every_hours":12,
               "reboot_every_days":2,"at_hour":3,"stale_restart_minutes":30,
               "stale_reboot_minutes":90,"reboot_cooldown_minutes":240}}"""
        )!!
        assertEquals(c, RemoteConfigCodec.parse(RemoteConfigCodec.toJson(c)))
    }

    @Test
    fun unknownMaintenanceKeysAreIgnored() {
        val c = RemoteConfigCodec.parse(
            """{"schema":1,"maintenance":{"at_hour":5,"quantum_mode":true}}"""
        )!!.maintenance
        assertEquals(5, c.at_hour)
        assertEquals(24, c.restart_every_hours)
    }

    @Test
    fun rebootCooldownDoublesWithEachFailedRebootAndIsCapped() {
        val base = MaintenancePolicy.effectiveCooldownMs(plan, 0)
        assertEquals(6 * 3_600_000L, base)
        assertEquals(base * 2, MaintenancePolicy.effectiveCooldownMs(plan, 1))
        assertEquals(base * 4, MaintenancePolicy.effectiveCooldownMs(plan, 2))
        // capped at a day, and 0 still means "no cooldown"
        assertEquals(MaintenancePolicy.MAX_COOLDOWN_MS, MaintenancePolicy.effectiveCooldownMs(plan, 4))
        assertEquals(MaintenancePolicy.MAX_COOLDOWN_MS, MaintenancePolicy.effectiveCooldownMs(plan, 99))
        assertEquals(0L, MaintenancePolicy.effectiveCooldownMs(plan.copy(reboot_cooldown_minutes = 0), 3))
    }

    @Test
    fun backoffActuallyDelaysTheNextStaleReboot() {
        // 6h base cooldown: after 3h a reboot is still refused, even though the
        // silence threshold has long passed.
        assertEquals(
            MaintenanceAction.RESTART_APP,
            decide(staleMinutes = 400, lastRestartAfterPublish = true, lastRebootAgoMin = 180)
        )
        // With one failed reboot behind it the cooldown is 12h, so 8h is not enough.
        val backoff1 = MaintenancePolicy.decide(
            now, plan,
            lastPublishMs = now - 400 * MaintenancePolicy.MINUTE_MS,
            lastRestartMs = now - 60_000L,
            lastRebootMs = now - 8 * 60 * MaintenancePolicy.MINUTE_MS,
            batteryPct = 80,
            rebootBackoff = 1
        )
        assertEquals(MaintenanceAction.RESTART_APP, backoff1)
        val backoff1Ready = MaintenancePolicy.decide(
            now, plan,
            lastPublishMs = now - 400 * MaintenancePolicy.MINUTE_MS,
            lastRestartMs = now - 60_000L,
            lastRebootMs = now - 13 * 60 * MaintenancePolicy.MINUTE_MS,
            batteryPct = 80,
            rebootBackoff = 1
        )
        assertEquals(MaintenanceAction.REBOOT_DEVICE, backoff1Ready)
    }

    @Test
    fun isStaleUsesTheConfiguredThreshold() {
        val last = now - 50 * MaintenancePolicy.MINUTE_MS
        assertTrue(MaintenancePolicy.isStale(now, last, plan))
        assertEquals(false, MaintenancePolicy.isStale(now, now - 10 * MaintenancePolicy.MINUTE_MS, plan))
        // A plan with no stale-restart threshold still reports "very old" as stale.
        assertTrue(MaintenancePolicy.isStale(now, last, plan.copy(stale_restart_minutes = 0)))
    }
}
