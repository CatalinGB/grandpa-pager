package com.grandpapager

/** The guardrail lifecycle of a remote connection change. */
enum class GuardStatus { ACTIVE, PROBING, SWITCHED, COMMITTED, REVERTED, FAILED }

/** Persistable guardrail state. */
data class GuardState(
    val status: GuardStatus = GuardStatus.ACTIVE,
    val candidate: ConnectionConfig? = null,
    val backup: ConnectionConfig? = null,
    val sinceMs: Long = 0L,
    val revertAfterMs: Long = 0L,
    val confirmAfterMs: Long = 0L,
    val connectedAtMs: Long? = null,
    val lastError: String? = null
) {
    val isPending: Boolean get() = status == GuardStatus.PROBING || status == GuardStatus.SWITCHED
}

sealed class GuardEvent {
    data class Request(
        val candidate: ConnectionConfig,
        val backup: ConnectionConfig,
        val nowMs: Long,
        val revertAfterMs: Long,
        val confirmAfterMs: Long
    ) : GuardEvent()
    data class ProbeOk(val nowMs: Long) : GuardEvent()
    data class ProbeFail(val nowMs: Long) : GuardEvent()
    data class Connected(val nowMs: Long) : GuardEvent()
    data class Tick(val nowMs: Long) : GuardEvent()
}

sealed class GuardEffect {
    object None : GuardEffect()
    object StartProbe : GuardEffect()
    data class SwitchTo(val candidate: ConnectionConfig) : GuardEffect()
    object Commit : GuardEffect()
    data class Revert(val backup: ConnectionConfig?) : GuardEffect()
}

/**
 * Pure, JVM-testable state machine that makes a remote broker/credential change
 * safe: probe the candidate, switch, commit once the link is stable, and revert
 * to the last working connection if it never connects within the window. The
 * Android layer feeds it events and executes the emitted effects.
 */
class ConfigGuard(initial: GuardState = GuardState()) {

    var state: GuardState = initial
        private set

    fun on(event: GuardEvent): GuardEffect = when (event) {
        is GuardEvent.Request -> {
            state = GuardState(
                status = GuardStatus.PROBING,
                candidate = event.candidate,
                backup = event.backup,
                sinceMs = event.nowMs,
                revertAfterMs = event.revertAfterMs,
                confirmAfterMs = event.confirmAfterMs
            )
            GuardEffect.StartProbe
        }

        is GuardEvent.ProbeOk -> if (state.status == GuardStatus.PROBING) {
            state = state.copy(status = GuardStatus.SWITCHED)
            GuardEffect.SwitchTo(state.candidate!!)
        } else GuardEffect.None

        is GuardEvent.ProbeFail -> if (state.isPending) {
            val backup = state.backup
            state = GuardState(status = GuardStatus.REVERTED, lastError = "candidate unreachable")
            GuardEffect.Revert(backup)
        } else GuardEffect.None

        is GuardEvent.Connected -> if (state.status == GuardStatus.SWITCHED && state.connectedAtMs == null) {
            state = state.copy(connectedAtMs = event.nowMs)
            GuardEffect.None
        } else GuardEffect.None

        is GuardEvent.Tick -> tick(event.nowMs)
    }

    private fun tick(nowMs: Long): GuardEffect {
        if (!state.isPending) return GuardEffect.None
        val connectedAt = state.connectedAtMs
        if (connectedAt != null && nowMs - connectedAt >= state.confirmAfterMs) {
            state = GuardState(status = GuardStatus.COMMITTED)
            return GuardEffect.Commit
        }
        if (nowMs - state.sinceMs >= state.revertAfterMs) {
            val backup = state.backup
            state = GuardState(status = GuardStatus.REVERTED, lastError = "no connection within window")
            return GuardEffect.Revert(backup)
        }
        return GuardEffect.None
    }

    /**
     * Resume a persisted pending state after a process restart / reboot. The
     * candidate may already be the active connection, so we do not re-probe; we
     * keep the timer running, and an unconfirmed change will revert.
     */
    fun resumePersisted(persisted: GuardState, @Suppress("UNUSED_PARAMETER") nowMs: Long): GuardState {
        state = if (persisted.isPending) persisted.copy(status = GuardStatus.SWITCHED) else persisted
        return state
    }

    /** Clear the last outcome back to a clean, healthy [GuardStatus.ACTIVE]. */
    fun reset() {
        state = GuardState()
    }

    companion object {
        const val MIN_REVERT_S = 30
        const val MAX_REVERT_S = 900
        const val MIN_CONFIRM_S = 5
        const val MAX_CONFIRM_S = 120

        fun clampRevertSeconds(s: Int?): Int = (s ?: 90).coerceIn(MIN_REVERT_S, MAX_REVERT_S)
        fun clampConfirmSeconds(s: Int?): Int = (s ?: 15).coerceIn(MIN_CONFIRM_S, MAX_CONFIRM_S)
    }
}
