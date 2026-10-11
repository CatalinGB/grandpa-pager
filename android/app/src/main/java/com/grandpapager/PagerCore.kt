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

data class CommandPayload(val action: String)

data class DiagnosticsPayload(
    val state: String,
    val sender: String?,
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
    /** Battery temperature in °C (tenths from BatteryManager), or null if unknown.
     *  Always-on devices run hot while charging — the single best early-warning
     *  signal for a degrading cell. A charge-controller can also gate on it. */
    val battery_temp_c: Double? = null
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

/** JSON (de)serialisation for all wire payloads. */
object PagerJson {
    private val gson = Gson()

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
