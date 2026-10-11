package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerCoreTest {

    @Test
    fun expiry_isComputedFromWallClock() {
        val now = 1_000_000_000_000L
        val at = Expiry.expiresAt(now, 240)
        assertEquals(240L * 60_000L, at - now)
        assertEquals(240L * 60_000L, Expiry.remaining(at, now))
        assertFalse(Expiry.isExpired(at, now))
        assertTrue(Expiry.isExpired(at, at + 1))
        assertEquals(0L, Expiry.remaining(at, at + 10_000))
    }

    @Test
    fun expiry_format_isHumanReadable() {
        assertEquals("4h 0m", Expiry.format(240L * 60_000L))
        assertEquals("3h 12m", Expiry.format((3 * 60 + 12) * 60_000L))
        assertEquals("47m", Expiry.format(47L * 60_000L))
        assertEquals("<1m", Expiry.format(30_000L))
    }

    @Test
    fun isoRoundTrips() {
        val now = 1_700_000_000_000L
        val iso = TimeUtil.toIso(now)
        // second precision only
        val back = TimeUtil.fromIso(iso)
        assertNotNull(back)
        assertEquals(now / 1000, back!! / 1000)
        assertTrue(iso.endsWith("Z"))
    }

    @Test
    fun newMessage_carriesSenderAndExpiry() {
        val now = 1_700_000_000_000L
        val msg = PagerJson.newMessage("Maria", "Lunch is ready", 240, now)
        assertEquals("Maria", msg.sender)
        assertEquals("Lunch is ready", msg.text)
        assertEquals(240, msg.duration_minutes)
        assertNotNull(msg.id)
        assertEquals(now, TimeUtil.fromIso(msg.created_at))
        assertEquals(now + 240L * 60_000L, TimeUtil.fromIso(msg.expires_at))
    }

    @Test
    fun messageJson_roundTripsIncludingSender() {
        val msg = PagerJson.newMessage("Andrei", "Pills at 8pm", 60, 1_700_000_000_000L)
        val json = PagerJson.message(msg)
        assertTrue(json.contains("\"sender\":\"Andrei\""))
        val parsed = PagerJson.toMessage(json)
        assertNotNull(parsed)
        assertEquals(msg, parsed)
    }

    @Test
    fun diagnostics_roundTripsAndReportsOfflineState() {
        val d = DiagnosticsPayload(
            state = "showing", sender = "Maria", battery = 63, charging = true,
            wifi = true, mqtt = true, current_message_id = "abc",
            expires_at = "2026-01-01T00:00:00Z", last_seen = "2026-01-01T00:00:00Z",
            app = "receiver"
        )
        val json = PagerJson.diagnostics(d)
        assertEquals(d, PagerJson.toDiagnostics(json))
    }

    @Test
    fun diagnostics_carriesBatteryTemperature() {
        val d = DiagnosticsPayload(
            state = "idle", sender = null, battery = 68, charging = true,
            wifi = true, mqtt = true, current_message_id = null,
            expires_at = null, last_seen = "2026-01-01T00:00:00Z",
            app = "receiver", battery_temp_c = 34.5
        )
        val json = PagerJson.diagnostics(d)
        assertTrue(json.contains("\"battery_temp_c\":34.5"))
        val back = PagerJson.toDiagnostics(json)
        assertNotNull(back)
        assertEquals(34.5, back!!.battery_temp_c!!, 0.001)
    }

    @Test
    fun diagnostics_carriesCurrentMessageTextAndSender() {
        val d = DiagnosticsPayload(
            state = "showing", sender = "Maria", text = "Lunch is ready", battery = 60,
            charging = true, wifi = true, mqtt = true, current_message_id = "m1",
            expires_at = "2026-01-01T00:10:00Z", last_seen = "2026-01-01T00:00:00Z", app = "receiver"
        )
        val json = PagerJson.diagnostics(d)
        assertTrue(json.contains("\"text\":\"Lunch is ready\""))
        assertEquals("Lunch is ready", PagerJson.toDiagnostics(json)!!.text)
        assertEquals("Maria", PagerJson.toDiagnostics(json)!!.sender)
    }

    @Test
    fun topics_areStable() {
        assertEquals("grandpa/message", Topics.MESSAGE)
        assertEquals("grandpa/command", Topics.COMMAND)
        assertEquals("grandpa/status", Topics.STATUS)
    }

    @Test
    fun blankMessageJson_isNull() {
        assertEquals(null, PagerJson.toMessage(""))
        assertEquals(null, PagerJson.toMessage("{}"))
    }

    @Test
    fun remoteConfig_defaultsIncludePowerAndTelemetry() {
        val c = RemoteConfigCodec.parse("{\"schema\":1}")
        assertNotNull(c)
        assertEquals(15, c!!.telemetry.interval_minutes)
        assertTrue(c.power.screen_off_when_idle)
    }

    @Test
    fun remoteConfig_clampsTelemetryInterval() {
        assertEquals(1440, RemoteConfigCodec.parse("{\"schema\":1,\"telemetry\":{\"interval_minutes\":999999}}")!!.telemetry.interval_minutes)
        assertEquals(1, RemoteConfigCodec.parse("{\"schema\":1,\"telemetry\":{\"interval_minutes\":0}}")!!.telemetry.interval_minutes)
        assertEquals(30, RemoteConfigCodec.parse("{\"schema\":1,\"telemetry\":{\"interval_minutes\":30}}")!!.telemetry.interval_minutes)
    }

    @Test
    fun remoteConfig_roundTripsPowerAndTelemetry() {
        val c = RemoteConfigCodec.parse("{\"schema\":1,\"power\":{\"screen_off_when_idle\":false},\"telemetry\":{\"interval_minutes\":5}}")!!
        assertFalse(c.power.screen_off_when_idle)
        assertEquals(5, c.telemetry.interval_minutes)
        assertEquals(c, RemoteConfigCodec.parse(RemoteConfigCodec.toJson(c)))
    }

    @Test
    fun remoteConfig_rejectsUnsupportedSchema() {
        assertEquals(null, RemoteConfigCodec.parse("{\"schema\":2}"))
        assertEquals(null, RemoteConfigCodec.parse("{}"))
    }

    @Test
    fun remoteConfig_extendedLayoutDefaults() {
        val c = RemoteConfigCodec.parse("{\"schema\":1}")!!
        assertEquals(10, c.display.message_size_min_sp)
        assertEquals(64, c.display.message_size_max_sp)
        assertEquals("#FFFAB005", c.display.name_color)
        assertEquals("#FFFFFFFF", c.display.message_color)
        assertEquals("#FFFFB300", c.display.warn_color)
        assertEquals("#FF9E9E9E", c.display.countdown_color)
        assertEquals(1.3f, c.display.idle_clock_scale, 0.001f)
        assertEquals("auto", c.display.idle_date_format)
        assertEquals(null, c.display.warn_text)
    }

    @Test
    fun remoteConfig_cueDefaultsVibrateOffButHonoursExplicitTrue() {
        val c = RemoteConfigCodec.parse("{\"schema\":1}")!!
        assertFalse("vibration must default off", c.cue.vibrate)
        assertTrue(c.cue.chime)
        assertTrue(c.cue.torch.enabled)
        assertTrue(RemoteConfigCodec.parse("{\"schema\":1,\"cue\":{\"vibrate\":true}}")!!.cue.vibrate)
    }

    @Test
    fun remoteConfig_clampsMessageSizesAndKeepsOrder() {
        val min = RemoteConfigCodec.parse("{\"schema\":1,\"display\":{\"message_size_min_sp\":1}}")!!.display.message_size_min_sp
        assertEquals(8, min)
        val max = RemoteConfigCodec.parse("{\"schema\":1,\"display\":{\"message_size_max_sp\":500}}")!!.display.message_size_max_sp
        assertEquals(200, max)
        // min above max collapses to max.
        val c = RemoteConfigCodec.parse("{\"schema\":1,\"display\":{\"message_size_min_sp\":70,\"message_size_max_sp\":20}}")!!
        assertEquals(20, c.display.message_size_min_sp)
        assertEquals(20, c.display.message_size_max_sp)
    }

    @Test
    fun remoteConfig_invalidColourFallsBack() {
        val c = RemoteConfigCodec.parse(
            "{\"schema\":1,\"display\":{\"name_color\":\"red\",\"message_color\":\"#12\"}}"
        )!!
        assertEquals("#FFFAB005", c.display.name_color)
        assertEquals("#FFFFFFFF", c.display.message_color)
    }

    @Test
    fun remoteConfig_dateFormatAndClockScale() {
        val c = RemoteConfigCodec.parse(
            "{\"schema\":1,\"display\":{\"idle_date_format\":\"mdy\",\"idle_clock_scale\":9}}"
        )!!
        assertEquals("mdy", c.display.idle_date_format)
        assertEquals(3.0f, c.display.idle_clock_scale, 0.001f)
        val bad = RemoteConfigCodec.parse("{\"schema\":1,\"display\":{\"idle_date_format\":\"nope\"}}")!!
        assertEquals("auto", bad.display.idle_date_format)
    }

    @Test
    fun remoteConfig_warnTextOverrideAndRoundTrip() {
        val c = RemoteConfigCodec.parse("{\"schema\":1,\"display\":{\"warn_text\":\"No signal\"}}")!!
        assertEquals("No signal", c.display.warn_text)
        assertEquals(c, RemoteConfigCodec.parse(RemoteConfigCodec.toJson(c)))
    }

    @Test
    fun restrictionPlan_followsToggles() {
        val doo = RemoteConfigCodec.parse("{\"schema\":1}")!!.device_owner
        val d = RestrictionPlan.desired(doo)
        assertTrue(d.containsAll(RestrictionPlan.ALL))
    }

    @Test
    fun restrictionPlan_relaxingRemovesButKeepsHardening() {
        val doo = RemoteConfigCodec.parse(
            "{\"schema\":1,\"device_owner\":{\"restrict_accounts\":false,\"restrict_bluetooth\":false," +
                "\"restrict_location\":false,\"restrict_printing\":false}}"
        )!!.device_owner
        val d = RestrictionPlan.desired(doo)
        assertFalse(d.contains(RestrictionPlan.ACCOUNTS))
        assertFalse(d.contains(RestrictionPlan.BLUETOOTH))
        assertFalse(d.contains(RestrictionPlan.LOCATION))
        assertFalse(d.contains(RestrictionPlan.PRINTING))
        // Untouched toggles keep their default-on hardening.
        assertTrue(d.contains(RestrictionPlan.MOBILE_NETWORKS))
        assertTrue(d.contains(RestrictionPlan.TETHERING))
        assertTrue(d.contains(RestrictionPlan.ROAMING))
    }

    @Test
    fun restrictionPlan_networkTogglesCanBeRelaxed() {
        val doo = RemoteConfigCodec.parse(
            "{\"schema\":1,\"device_owner\":{\"restrict_mobile_networks\":false," +
                "\"restrict_tethering\":false,\"restrict_roaming\":false}}"
        )!!.device_owner
        val d = RestrictionPlan.desired(doo)
        assertFalse(d.contains(RestrictionPlan.MOBILE_NETWORKS))
        assertFalse(d.contains(RestrictionPlan.TETHERING))
        assertFalse(d.contains(RestrictionPlan.ROAMING))
        assertTrue(d.contains(RestrictionPlan.ACCOUNTS))
    }

    @Test
    fun staleThreshold_isDerivedFromTelemetryCadence() {
        // 2 × interval + 60 s, never below 90 s (decisions.md D13).
        assertEquals(1860, Stale.afterSeconds(null)) // default cadence 15 min
        assertEquals(90, Stale.afterSeconds(0))
        assertEquals(180, Stale.afterSeconds(1))
        assertEquals(1860, Stale.afterSeconds(15))
        assertEquals(7260, Stale.afterSeconds(60))
    }

    @Test
    fun senderColors_areStableAndNormalised() {
        assertEquals(SenderColors.colorFor("Maria"), SenderColors.colorFor("  maria  "))
        assertNull(SenderColors.colorFor(null))
        assertNull(SenderColors.colorFor("   "))
        assertTrue(SenderColors.PALETTE.contains(SenderColors.colorFor("Andrei")!!))
    }

    @Test
    fun senderColors_paletteIsFifteenAndEveryColourClearsAaaOnBlack() {
        assertEquals(15, SenderColors.PALETTE.size)
        for (c in SenderColors.PALETTE) {
            assertTrue("$c contrast=${contrastOnBlack(c)}", contrastOnBlack(c) >= 7.0)
        }
    }

    @Test
    fun remoteConfig_defaultNameSizeIsFiftyEight() {
        assertEquals(58, RemoteConfig().display.name_size_sp)
        assertEquals(58, RemoteConfigCodec.parse("{\"schema\":1}")!!.display.name_size_sp)
    }

    @Test
    fun senderColors_spreadAcrossTypicalNames() {
        val names = listOf("Maria", "Andrei", "Ion", "Elena", "Vasile", "Ana", "Bunica")
        val distinct = names.mapNotNull { SenderColors.colorFor(it) }.toSet()
        assertTrue("only ${distinct.size} distinct colours", distinct.size >= 3)
    }

    @Test
    fun senderColors_nameColorRespectsToggleAndFallback() {
        val fallback = "#FFFAB005"
        // Toggle off -> always the configured fallback.
        assertEquals(fallback, SenderColors.nameColor(false, "Maria", fallback))
        // Toggle on with a name -> the per-sender palette colour.
        assertEquals(SenderColors.colorFor("Maria"), SenderColors.nameColor(true, "Maria", fallback))
        // Toggle on but no name -> the fallback.
        assertEquals(fallback, SenderColors.nameColor(true, null, fallback))
        assertEquals(fallback, SenderColors.nameColor(true, "   ", fallback))
    }

    @Test
    fun senderColors_assignAvoidsCollisions() {
        // Adelina and Catalin hash to the same slot; the second must probe to a
        // different, free colour rather than reusing it.
        val first = SenderColors.colorFor("Adelina")!!
        assertEquals(first, SenderColors.colorFor("Catalin"))
        val second = SenderColors.assign("Catalin", setOf(first))
        assertTrue("collision: $second == $first", second != first)
        assertTrue(SenderColors.PALETTE.contains(second))
    }

    @Test
    fun senderColors_assignPrefersHashWhenFreeAndDegradesWhenFull() {
        assertEquals(SenderColors.colorFor("Vasile"), SenderColors.assign("Vasile", emptySet()))
        // Full palette -> cannot avoid; returns the hashed colour.
        assertEquals(SenderColors.colorFor("Vasile"), SenderColors.assign("Vasile", SenderColors.PALETTE.toSet()))
    }

    @Test
    fun senderColors_assignGivesEverySenderADistinctColour() {
        val names = listOf(
            "Adelina", "Catalin", "Vasile", "Maria", "Ion", "Elena", "Ana", "Bogdan",
            "Cristina", "Mihai", "Ioana", "Daniel", "Georgiana", "Alexandru", "Gheorghe"
        )
        val taken = mutableSetOf<String>()
        val assigned = names.map { val c = SenderColors.assign(it, taken); taken.add(c); c }
        assertEquals(names.size, assigned.toSet().size) // 15 names, 15 colours -> all distinct
    }

    /** WCAG relative-luminance contrast of a #AARRGGBB colour against black. */
    private fun contrastOnBlack(hex: String): Double {
        fun lin(c: Int): Double {
            val x = c / 255.0
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        val r = hex.substring(3, 5).toInt(16)
        val g = hex.substring(5, 7).toInt(16)
        val b = hex.substring(7, 9).toInt(16)
        val l = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        return (l + 0.05) / 0.05
    }

    @Test
    fun deviceOwnerConfig_defaultsAndRoundTrip() {
        val c = RemoteConfigCodec.parse("{\"schema\":1}")!!.device_owner
        assertTrue(c.lock_task)
        assertTrue(c.status_bar_disabled)
        assertTrue(c.restrict_sync)
        val round = RemoteConfigCodec.parse(
            RemoteConfigCodec.toJson(
                RemoteConfigCodec.parse("{\"schema\":1,\"device_owner\":{\"restrict_sync\":false}}")!!
            )
        )!!.device_owner
        assertFalse(round.restrict_sync)
    }

    @Test
    fun diagnostics_carriesDeviceOwnerFlag() {
        val d = DiagnosticsPayload(
            state = "idle", sender = null, battery = 1, charging = false,
            wifi = true, mqtt = true, current_message_id = null,
            expires_at = null, last_seen = "2026-01-01T00:00:00Z",
            app = "receiver", device_owner = true
        )
        assertTrue(PagerJson.diagnostics(d).contains("\"device_owner\":true"))
    }

    @Test
    fun connectionCodec_rejectsBlankHostAndClamps() {
        assertNull(ConnectionCodec.parse(null))
        assertNull(ConnectionCodec.parse("{\"host\":\"  \"}"))
        val c = ConnectionCodec.parse(
            "{\"host\":\"h\",\"port\":0,\"keepalive_s\":1,\"connect_timeout_s\":1," +
                "\"qos\":9,\"max_inflight\":99999}"
        )!!
        assertEquals(1, c.port)
        assertEquals(5, c.keepalive_s)
        assertEquals(5, c.connect_timeout_s)
        assertEquals(1, c.qos)
        assertEquals(1000, c.max_inflight)
    }

    @Test
    fun connectionCodec_roundTripKeepsSecret() {
        val r = ConnectionConfig(host = "h", password = "secret", keepalive_s = 45)
        assertEquals(r, ConnectionCodec.parse(ConnectionCodec.toJson(r)))
    }

    @Test
    fun configGuard_commitsAfterConfirmWindow() {
        val g = ConfigGuard()
        val backup = ConnectionConfig(host = "old")
        val cand = ConnectionConfig(host = "new")
        assertTrue(g.on(GuardEvent.Request(cand, backup, 0L, 60_000L, 5_000L)) is GuardEffect.StartProbe)
        assertTrue(g.state.isPending)
        assertTrue(g.on(GuardEvent.ProbeOk(1_000L)) is GuardEffect.SwitchTo)
        assertEquals(GuardEffect.None, g.on(GuardEvent.Connected(2_000L)))
        assertEquals(GuardEffect.None, g.on(GuardEvent.Tick(6_000L))) // 4 s up < 5 s
        assertTrue(g.on(GuardEvent.Tick(7_100L)) is GuardEffect.Commit) // 5.1 s up
        assertEquals(GuardStatus.COMMITTED, g.state.status)
    }

    @Test
    fun configGuard_probeFailRevertsToBackup() {
        val g = ConfigGuard()
        val backup = ConnectionConfig(host = "old")
        g.on(GuardEvent.Request(ConnectionConfig(host = "new"), backup, 0L, 60_000L, 5_000L))
        val e = g.on(GuardEvent.ProbeFail(500L))
        assertTrue(e is GuardEffect.Revert)
        assertEquals(backup, (e as GuardEffect.Revert).backup)
        assertEquals(GuardStatus.REVERTED, g.state.status)
    }

    @Test
    fun configGuard_timeoutRevertsWithoutConnection() {
        val g = ConfigGuard()
        val backup = ConnectionConfig(host = "old")
        g.on(GuardEvent.Request(ConnectionConfig(host = "new"), backup, 0L, 30_000L, 5_000L))
        g.on(GuardEvent.ProbeOk(0L))
        val e = g.on(GuardEvent.Tick(31_000L))
        assertTrue(e is GuardEffect.Revert)
        assertEquals(GuardStatus.REVERTED, g.state.status)
    }

    @Test
    fun configGuard_resumeUnconfirmedPendingReverts() {
        val cand = ConnectionConfig(host = "new")
        val backup = ConnectionConfig(host = "old")
        val persisted = GuardState(
            status = GuardStatus.SWITCHED, candidate = cand, backup = backup,
            sinceMs = 0L, revertAfterMs = 30_000L, confirmAfterMs = 5_000L
        )
        val g = ConfigGuard()
        g.resumePersisted(persisted, 5_000L)
        assertTrue(g.state.isPending)
        assertTrue(g.on(GuardEvent.Tick(31_000L)) is GuardEffect.Revert)
    }

    @Test
    fun configGuard_clampsWindows() {
        assertEquals(90, ConfigGuard.clampRevertSeconds(null))
        assertEquals(30, ConfigGuard.clampRevertSeconds(1))
        assertEquals(900, ConfigGuard.clampRevertSeconds(99999))
        assertEquals(15, ConfigGuard.clampConfirmSeconds(null))
        assertEquals(5, ConfigGuard.clampConfirmSeconds(0))
        assertEquals(120, ConfigGuard.clampConfirmSeconds(9999))
    }

    @Test
    fun command_parsesDeviceControlActions() {
        assertEquals("restart_app", PagerJson.toCommand("{\"action\":\"restart_app\"}")!!.action)
        assertEquals("reboot_device", PagerJson.toCommand("{\"action\":\"reboot_device\"}")!!.action)
        // Unknown actions still parse; the dispatcher is what ignores them.
        assertEquals("nope", PagerJson.toCommand("{\"action\":\"nope\"}")!!.action)
    }

    @Test
    fun command_parsesConnectionCandidate() {
        val c = PagerJson.toCommand(
            "{\"action\":\"set_connection\",\"candidate\":{\"host\":\"h\",\"port\":8883},\"revert_after_s\":60}"
        )!!
        assertEquals("set_connection", c.action)
        assertEquals("h", c.candidate!!.host)
        assertEquals(8883, c.candidate!!.port)
        assertEquals(60, c.revert_after_s)
    }

    @Test
    fun diagnostics_carriesConnectionAndCommand() {
        val d = DiagnosticsPayload(
            state = "idle", sender = null, battery = 1, charging = false,
            wifi = true, mqtt = true, current_message_id = null,
            expires_at = null, last_seen = "2026-01-01T00:00:00Z", app = "receiver",
            connection = ConnectionStatus("pending", "h", 8883, true, true, null),
            command = CommandStatus("restart_app", "2026-01-01T00:00:00Z", "ok")
        )
        val back = PagerJson.toDiagnostics(PagerJson.diagnostics(d))!!
        assertEquals(d.connection, back.connection)
        assertEquals(d.command, back.command)
    }
}
