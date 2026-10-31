package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The supervisor state is the only thing linking the app process (publisher)
 * with the `:maintenance` process (supervisor), so its parsing must never throw
 * or produce nonsense: a corrupt file has to degrade to "no history", not to a
 * reboot loop.
 */
class SupervisorStateTest {

    @Test
    fun blankOrCorruptJsonDegradesToDefaults() {
        assertEquals(SupervisorState(), SupervisorStateCodec.parse(null))
        assertEquals(SupervisorState(), SupervisorStateCodec.parse(""))
        assertEquals(SupervisorState(), SupervisorStateCodec.parse("{not json"))
        assertEquals(SupervisorState(), SupervisorStateCodec.parse("[]"))
    }

    @Test
    fun roundTrips() {
        val s = SupervisorState(
            lastStaleRestartAt = 1000L,
            lastStaleRebootAt = 2000L,
            lastScheduledRestartAt = 3000L,
            lastScheduledRebootAt = 4000L,
            rebootBackoff = 2,
            lastRebootReason = "stale"
        )
        assertEquals(s, SupervisorStateCodec.parse(SupervisorStateCodec.toJson(s)))
    }

    @Test
    fun clampsNegativeTimestampsAndBackoff() {
        val parsed = SupervisorStateCodec.parse(
            """{"lastStaleRestartAt":-5,"lastStaleRebootAt":-1,"lastScheduledRestartAt":-7,
               "lastScheduledRebootAt":-9,"rebootBackoff":99}"""
        )
        assertEquals(0L, parsed.lastStaleRestartAt)
        assertEquals(0L, parsed.lastStaleRebootAt)
        assertEquals(0L, parsed.lastScheduledRestartAt)
        assertEquals(0L, parsed.lastScheduledRebootAt)
        assertEquals(SupervisorStateCodec.MAX_BACKOFF, parsed.rebootBackoff)
        assertNull(parsed.lastRebootReason)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val parsed = SupervisorStateCodec.parse("""{"lastStaleRestartAt":42,"future":1}""")
        assertEquals(42L, parsed.lastStaleRestartAt)
    }

    @Test
    fun aPublishAfterTheLastStaleRebootClearsTheBackoff() {
        val s = SupervisorState(lastStaleRebootAt = 1000L, rebootBackoff = 3)
        assertEquals(3, s.backoffAfter(500L))
        assertEquals(0, s.backoffAfter(1001L))
        // Unknown heartbeat: keep the backoff (it never published at all).
        assertEquals(3, s.backoffAfter(0L))
    }
}
