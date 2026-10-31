package com.grandpapager

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Runtime state of the maintenance supervisor, shared with the `:maintenance`
 * process.
 *
 * The supervisor deliberately runs in its own process so a wedged app main
 * thread (blocked looper, deadlock) cannot also block the alarm that is meant to
 * rescue it. That means SharedPreferences are not a safe channel between the two
 * processes, so the handful of values the supervisor needs live in small files:
 *
 *  - `maintenance_plan.json` — written by the app process (on config apply/start)
 *  - `maintenance_heartbeat` — written by the app process (on every broker ack)
 *  - `maintenance_state.json` — written by the supervisor process only
 *
 * One writer per file, atomic replace, so there is no read-modify-write race.
 */
data class SupervisorState(
    /** Restart triggered because the pager went silent. */
    val lastStaleRestartAt: Long = 0L,
    /** Reboot triggered because the pager went silent. */
    val lastStaleRebootAt: Long = 0L,
    /** Last scheduled (interval) app restart. */
    val lastScheduledRestartAt: Long = 0L,
    /** Last scheduled (interval) phone reboot. */
    val lastScheduledRebootAt: Long = 0L,
    /** Consecutive failed stale reboots (exponential cooldown). */
    val rebootBackoff: Int = 0,
    /** "scheduled" or "stale". */
    val lastRebootReason: String? = null
) {
    /**
     * A broker acknowledgement newer than the last stale reboot means the link
     * recovered, so the backoff is over. Derived here (instead of the app process
     * resetting the file) to keep exactly one writer for `state.json`.
     */
    fun backoffAfter(heartbeatAt: Long): Int =
        if (heartbeatAt > 0L && heartbeatAt > lastStaleRebootAt) 0 else rebootBackoff
}

/** Pure codec for [SupervisorState], so the contract is unit-tested. */
object SupervisorStateCodec {
    private val gson = Gson()
    const val MAX_BACKOFF = 8

    fun toJson(state: SupervisorState): String = gson.toJson(state)

    /** Never throws: a corrupt file must not disable the supervisor. */
    fun parse(json: String?): SupervisorState {
        if (json.isNullOrBlank()) return SupervisorState()
        val raw = runCatching { gson.fromJson(json, SupervisorState::class.java) }.getOrNull()
            ?: return SupervisorState()
        return SupervisorState(
            lastStaleRestartAt = raw.lastStaleRestartAt.coerceAtLeast(0L),
            lastStaleRebootAt = raw.lastStaleRebootAt.coerceAtLeast(0L),
            lastScheduledRestartAt = raw.lastScheduledRestartAt.coerceAtLeast(0L),
            lastScheduledRebootAt = raw.lastScheduledRebootAt.coerceAtLeast(0L),
            rebootBackoff = raw.rebootBackoff.coerceIn(0, MAX_BACKOFF),
            lastRebootReason = raw.lastRebootReason
        )
    }
}

object SupervisorStore {
    private const val TAG = "SupervisorStore"
    private const val PLAN = "maintenance_plan.json"
    private const val STATE = "maintenance_state.json"
    private const val HEARTBEAT = "maintenance_heartbeat"

    // ---------------------------------------------------------------- plan

    /** Persist the plan for the supervisor process (full RemoteConfig JSON, so
     *  RemoteConfigCodec applies its defaults/clamping on the way back in). */
    fun writePlan(context: Context, plan: RemoteConfig.Maintenance) {
        val json = RemoteConfigCodec.toJson(RemoteConfig(maintenance = plan))
        atomicWrite(File(context.filesDir, PLAN), json)
    }

    fun readPlan(context: Context, fallback: RemoteConfig.Maintenance): RemoteConfig.Maintenance =
        runCatching { File(context.filesDir, PLAN).takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let { RemoteConfigCodec.parse(it)?.maintenance }
            ?: fallback

    // ----------------------------------------------------------- heartbeat

    /** Called after every broker-acknowledged publish. */
    fun writeHeartbeat(context: Context, atMs: Long) {
        atomicWrite(File(context.filesDir, HEARTBEAT), atMs.toString())
    }

    fun readHeartbeat(context: Context, fallback: Long): Long =
        runCatching { File(context.filesDir, HEARTBEAT).takeIf { it.isFile }?.readText()?.trim()?.toLong() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: fallback

    // --------------------------------------------------------------- state

    fun readState(context: Context): SupervisorState =
        runCatching { File(context.filesDir, STATE).takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let { SupervisorStateCodec.parse(it) }
            ?: SupervisorState()

    fun writeState(context: Context, state: SupervisorState) {
        atomicWrite(File(context.filesDir, STATE), SupervisorStateCodec.toJson(state))
    }

    /** Write via a temp file + rename so a crash can never leave half a file. */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // rename can fail on some filesystems; fall back to copy+delete.
                target.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write ${target.name}", it) }
    }
}
