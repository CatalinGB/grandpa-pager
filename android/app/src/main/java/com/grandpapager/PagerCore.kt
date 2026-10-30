package com.grandpapager

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** MQTT topics for the grandpa channel. */
object Topics {
    const val MESSAGE = "grandpa/message"   // retained, QoS1 — latest message
    const val COMMAND = "grandpa/command"   // QoS1 — control
    const val CONFIG = "grandpa/config"     // retained, QoS1 — admin remote config
    const val STATUS = "grandpa/status"     // retained, QoS1 — receiver diagnostics
}

enum class Role {
    RECEIVER,
    SENDER_ADMIN,
    SENDER_FAMILY;

    val isSender: Boolean get() = this != RECEIVER
}

data class MessagePayload(
    val id: String,
    val sender: String,
    val text: String,
    val created_at: String,
    val expires_at: String,
    val duration_minutes: Int
)

data class CommandPayload(
    val action: String,
    /** `set_connection`: the candidate broker + tuning to switch to. */
    val candidate: ConnectionConfig? = null,
    /** `set_connection`: seconds to wait before reverting (clamped by ConfigGuard). */
    val revert_after_s: Int? = null,
    /** `set_connection`: seconds a connection must stay up before committing. */
    val confirm_after_s: Int? = null
)

/** Acknowledged result of the last device-control command. */
data class CommandStatus(val last: String?, val received_at: String?, val result: String?)

/** The broker connection as reported in status — never the password. */
data class ConnectionStatus(
    val state: String,
    val host: String,
    val port: Int,
    val use_tls: Boolean,
    val password_set: Boolean,
    val last_error: String? = null
)

data class DiagnosticsPayload(
    val state: String,
    val sender: String?,
    /** The text of the message currently on screen (null when idle). */
    val text: String? = null,
    val battery: Int,
    val charging: Boolean,
    val wifi: Boolean,
    val mqtt: Boolean,
    val current_message_id: String?,
    val expires_at: String?,
    val last_seen: String,
    val app: String,
    val config: RemoteConfig? = null,
    val config_applied_at: String? = null,
    val config_error: String? = null,
    /** Whether this app currently holds device-owner rights (restrictions/kiosk). */
    val device_owner: Boolean? = null,
    /** Broker connection + guardrail state (never the password). */
    val connection: ConnectionStatus? = null,
    /** Result of the last device-control command. */
    val command: CommandStatus? = null,
    /** Battery temperature in °C (tenths from BatteryManager), or null if unknown.
     *  Always-on devices run hot while charging — the single best early-warning
     *  signal for a degrading cell. A charge-controller can also gate on it. */
    val battery_temp_c: Double? = null,
    /** Device uptime since boot (SystemClock.elapsedRealtime()), or null on older builds.
     *  Additive field: older apps omit it and older clients simply ignore it. */
    val device_uptime_ms: Long? = null,
    /** App process uptime (now - Process.getStartElapsedRealtime()), or null if unknown. */
    val app_uptime_ms: Long? = null,
    /** Phone-local boot timestamp (now - device uptime) as ISO-8601 UTC. */
    val boot_time: String? = null,
    /** On-device maintenance plan state (additive; older clients ignore it). */
    val maintenance: MaintenanceStatus? = null
)

/**
 * What the phone's own supervisor is doing. Everything is nullable so the field
 * can be absent on older builds and ignored by older clients.
 */
data class MaintenanceStatus(
    val enabled: Boolean,
    /** Seconds since the last successful broker publish/connect. */
    val last_publish_age_s: Long? = null,
    /** Next scheduled app restart (ISO-8601 UTC). */
    val next_restart_at: String? = null,
    /** Next scheduled phone reboot (ISO-8601 UTC). */
    val next_reboot_at: String? = null,
    /** Last restart triggered because the pager went silent. */
    val last_stale_restart_at: String? = null,
    /** Last automatic reboot (scheduled or stale-triggered). */
    val last_reboot_at: String? = null,
    /** "scheduled" or "stale". */
    val last_reboot_reason: String? = null
)

/** ISO-8601 UTC helpers that work down to API 24 (no java.time). */
object TimeUtil {
    private fun fmt(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun toIso(millis: Long): String = fmt().format(Date(millis))

    fun fromIso(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            fmt().parse(iso)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun nowIso(): String = toIso(System.currentTimeMillis())
}

/** Application-level expiry — never trust broker TTL. */
object Expiry {
    const val DEFAULT_DURATION_MINUTES = 240

    fun expiresAt(nowMillis: Long, durationMinutes: Int): Long =
        nowMillis + durationMinutes.toLong() * 60_000L

    fun remaining(expiresAtMillis: Long, nowMillis: Long): Long =
        (expiresAtMillis - nowMillis).coerceAtLeast(0L)

    fun isExpired(expiresAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= expiresAtMillis

    /** "3h 12m" / "47m" / "<1m". */
    fun format(remainingMillis: Long): String {
        val totalMinutes = remainingMillis / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
}

/**
 * Pure mapping from the admin's device-owner config to the set of hardening
 * restriction keys the receiver should enforce. The Android translation to
 * `UserManager` constants lives in `DeviceOwnerManager`; this stays testable.
 */
object RestrictionPlan {
    const val ACCOUNTS = "accounts"
    const val BLUETOOTH = "bluetooth"
    const val LOCATION = "location"
    const val PRINTING = "printing"
    const val MOBILE_NETWORKS = "mobile_networks"
    const val TETHERING = "tethering"
    const val ROAMING = "roaming"

    /** Every key the app knows how to enforce (used to clear relaxed ones). */
    val ALL: Set<String> = setOf(ACCOUNTS, BLUETOOTH, LOCATION, PRINTING, MOBILE_NETWORKS, TETHERING, ROAMING)

    /**
     * The restrictions to enforce for [c]. Every restriction follows its own
     * toggle so relaxing one remotely takes effect — matching architecture.md
     * and D14 ("each toggle adds **or clears** its restriction"). The network
     * ones (mobile config, tethering, roaming) default to on but are configurable.
     */
    fun desired(c: RemoteConfig.DeviceOwner): Set<String> = buildSet {
        if (c.restrict_accounts) add(ACCOUNTS)
        if (c.restrict_bluetooth) add(BLUETOOTH)
        if (c.restrict_location) add(LOCATION)
        if (c.restrict_printing) add(PRINTING)
        if (c.restrict_mobile_networks) add(MOBILE_NETWORKS)
        if (c.restrict_tethering) add(TETHERING)
        if (c.restrict_roaming) add(ROAMING)
    }
}

/**
 * Status-staleness threshold, shared by the Android sender and mirrored by the
 * web clients: a receiver is considered offline when its status is older than
 * `2 × telemetry interval + 60 s`, and never below 90 s (decisions.md D13).
 */
object Stale {
    fun afterSeconds(intervalMinutes: Int?): Int {
        val m = intervalMinutes ?: 15
        return maxOf(90, m * 60 * 2 + 60)
    }
}

/**
 * Per-sender name colours for the receiver display. A sender's name is hashed to
 * a stable index into [PALETTE]; every palette entry clears the WCAG **AAA**
 * contrast ratio (>= 7:1) against the receiver's black background, so the name is
 * always clearly readable and Grandpa can tell who sent a message at a glance.
 * The same name always maps to the same colour, across reboots and senders.
 */
object SenderColors {
    /** 15 vivid, light colours — every one >= 7:1 on black (#AARRGGBB). */
    val PALETTE: List<String> = listOf(
        "#FFFF6B6B", // red
        "#FFFF9E80", // coral
        "#FFFFAB91", // deep orange
        "#FFFFCC80", // orange
        "#FFFFEB3B", // yellow
        "#FFEEFF41", // lime
        "#FFB2FF59", // light green
        "#FF76FF03", // green
        "#FF8AFFC1", // mint
        "#FF64FFDA", // teal
        "#FF84FFFF", // cyan
        "#FF90CAF9", // blue
        "#FF9FA8DA", // indigo
        "#FFB39DDB", // lavender
        "#FFF48FB1"  // pink
    )

    /** The colour for [sender]'s preferred (hashed) slot, or null when blank. */
    fun colorFor(sender: String?): String? {
        val k = key(sender)
        if (k.isEmpty()) return null
        return colorAt(hashIndex(k))
    }

    /** Normalised map key for a sender name (trim + lowercase). */
    fun key(sender: String?): String = sender?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /** The palette colour at [index], wrapped into range. */
    fun colorAt(index: Int): String = PALETTE[((index % PALETTE.size) + PALETTE.size) % PALETTE.size]

    private fun hashIndex(k: String): Int = (fnv1a(k) % PALETTE.size).toInt()

    /**
     * A colour for [sender] that no sender in [taken] already owns, preferring
     * the hashed slot and probing forward on a collision, so **distinct senders
     * get distinct colours** (up to [PALETTE] size) instead of two names landing
     * on the same hue by chance. Falls back to the hashed slot when the palette
     * is full. [sender] must be non-blank.
     */
    fun assign(sender: String, taken: Set<String>): String {
        val start = hashIndex(key(sender))
        for (i in PALETTE.indices) {
            val c = colorAt(start + i)
            if (c !in taken) return c
        }
        return colorAt(start)
    }

    /**
     * The colour to draw a sender's name from the deterministic hash alone (no
     * registry) — the single-colour fallback path. Prefer the persisted [assign].
     */
    fun nameColor(perSenderColors: Boolean, sender: String?, fallback: String): String =
        (if (perSenderColors) colorFor(sender) else null) ?: fallback

    /** 32-bit FNV-1a over UTF-8 bytes — stable across platforms and versions. */
    private fun fnv1a(s: String): Long {
        var h = 0x811c9dc5L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xffL)
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return h
    }
}

/** JSON (de)serialisation for all wire payloads. */
object PagerJson {    private val gson = Gson()

    fun newMessage(sender: String, text: String, durationMinutes: Int, nowMillis: Long = System.currentTimeMillis()): MessagePayload =
        MessagePayload(
            id = UUID.randomUUID().toString(),
            sender = sender,
            text = text,
            created_at = TimeUtil.toIso(nowMillis),
            expires_at = TimeUtil.toIso(Expiry.expiresAt(nowMillis, durationMinutes)),
            duration_minutes = durationMinutes
        )

    fun message(p: MessagePayload): String = gson.toJson(p)
    fun command(p: CommandPayload): String = gson.toJson(p)
    fun diagnostics(p: DiagnosticsPayload): String = gson.toJson(p)

    fun toMessage(json: String): MessagePayload? =
        try {
            val t = json.trim()
            if (t.isEmpty() || t == "{}") null else gson.fromJson(t, MessagePayload::class.java)
        } catch (e: Exception) { null }

    fun toCommand(json: String): CommandPayload? =
        try { gson.fromJson(json, CommandPayload::class.java) } catch (e: Exception) { null }

    fun toDiagnostics(json: String): DiagnosticsPayload? =
        try { gson.fromJson(json, DiagnosticsPayload::class.java) } catch (e: Exception) { null }
}

/** Live link state, readable by the UI (not persisted). */
object Link {
    @Volatile var connected: Boolean = false
}

/** In-process change notification between the service and the activities. */
object PagerBus {    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(r: Runnable) { listeners.add(r) }
    fun removeListener(r: Runnable) { listeners.remove(r) }
    fun notifyChanged() {
        for (l in listeners) main.post(l)
    }
}
