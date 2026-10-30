package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status payload must stay small and safe even when a message is huge. */
class DiagFormatTest {

    @Test
    fun clipCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", DiagFormat.clip("  a\n\n b\t c  ", 40))
    }

    @Test
    fun clipReturnsNullForBlankInput() {
        assertNull(DiagFormat.clip(null, 40))
        assertNull(DiagFormat.clip("", 40))
        assertNull(DiagFormat.clip("   \n\t ", 40))
    }

    @Test
    fun clipTruncatesWithAnEllipsis() {
        val out = DiagFormat.clip("x".repeat(100), 10)!!
        assertEquals(10, out.length)
        assertEquals("…", out.substring(out.length - 1))
    }

    @Test
    fun describeIncludesTheExceptionType() {
        val out = DiagFormat.describe(IllegalStateException("boom"))!!
        assertEquals("IllegalStateException: boom", out)
    }

    @Test
    fun describeHandlesAnExceptionWithoutAMessage() {
        assertEquals("NullPointerException", DiagFormat.describe(NullPointerException()))
        assertNull(DiagFormat.describe(null))
    }

    @Test
    fun describeCapsALongMessage() {
        val out = DiagFormat.describe(RuntimeException("y".repeat(1000)))!!
        assertEquals(DiagFormat.MAX_MESSAGE, out.length)
    }

    @Test
    fun statusJsonOmitsEveryMissingDiagField() {
        val payload = DiagnosticsPayload(
            state = "idle", sender = null, text = null, battery = 80, charging = true,
            wifi = true, mqtt = true, current_message_id = null, expires_at = null,
            last_seen = "2026-10-30T00:00:00Z", app = "receiver"
        )
        val json = PagerJson.diagnostics(payload)
        assertEquals(false, json.contains("\"diag\""))
        // And a payload with only some metrics keeps the rest out.
        val withDiag = PagerJson.diagnostics(payload.copy(diag = DiagStatus(threads = 7)))
        assertEquals(true, withDiag.contains("\"threads\":7"))
        assertEquals(false, withDiag.contains("heap_used_kb"))
    }
}
