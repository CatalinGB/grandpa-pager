package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
