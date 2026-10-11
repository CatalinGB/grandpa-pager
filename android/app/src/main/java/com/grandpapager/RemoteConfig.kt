package com.grandpapager

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The admin's remote config (published to grandpa/config). v1 schema. */
data class RemoteConfig(
    val schema: Int = 1,
    val display: Display = Display(),
    val cue: Cue = Cue(),
    val defaults: Defaults = Defaults(),
    val kiosk: Kiosk = Kiosk(),
    val power: Power = Power(),
    val telemetry: Telemetry = Telemetry(),
    val device_owner: DeviceOwner = DeviceOwner()
) {
    data class Display(
        val name_size_sp: Int = 46,
        val countdown: Boolean = false,
        val orientation: String = "auto",
        val message_size_min_sp: Int = 10,
        val message_size_max_sp: Int = 64,
        val name_color: String = "#FFFAB005",
        val message_color: String = "#FFFFFFFF",
        val warn_color: String = "#FFFFB300",
        val countdown_color: String = "#FF9E9E9E",
        val idle_clock_scale: Float = 1.3f,
        val idle_date_format: String = "auto",
        val warn_text: String? = null
    )
    data class Cue(val glow_ms: Int = 9000, val chime: Boolean = true, val vibrate: Boolean = true, val torch: Torch = Torch())
    data class Torch(val enabled: Boolean = true, val on_ms: Int = 1500, val off_ms: Int = 1500, val pulses: Int = 3)
    data class Defaults(val duration_minutes: Int = 240)
    data class Kiosk(val locked: Boolean = true)

    /** Let the display sleep when idle; a new message wakes it via full-screen intent. */
    data class Power(val screen_off_when_idle: Boolean = true)

    /** How often the receiver publishes grandpa/status when nothing else happens. */
    data class Telemetry(val interval_minutes: Int = 15)

    /**
     * Device-owner hardening. Each flag maps to a user restriction / policy the
     * receiver (when it is device owner) should enforce. Defaults mirror the
     * shipping behaviour; absent keys keep those defaults.
     */
    data class DeviceOwner(
        val status_bar_disabled: Boolean = true,
        val lock_task: Boolean = true,
        val restrict_accounts: Boolean = true,
        val restrict_bluetooth: Boolean = true,
        val restrict_location: Boolean = true,
        val restrict_printing: Boolean = true,
        val restrict_sync: Boolean = true
    )
}

object RemoteConfigCodec {
    private val gson = Gson()
    private val ORIENTATIONS = setOf("auto", "portrait", "landscape")
    private val DATE_FORMATS = setOf("auto", "dmy", "mdy", "ymd")
    private val COLOR_RE = Regex("^#[0-9a-fA-F]{8}$")

    const val DEFAULT_NAME_COLOR = "#FFFAB005"
    const val DEFAULT_MESSAGE_COLOR = "#FFFFFFFF"
    const val DEFAULT_WARN_COLOR = "#FFFFB300"
    const val DEFAULT_COUNTDOWN_COLOR = "#FF9E9E9E"

    fun toJson(c: RemoteConfig): String = gson.toJson(c)

    fun defaultsJson(): String = gson.toJson(RemoteConfig())

    /** Parse + clamp. Returns null when the schema is missing/unsupported. */
    fun parse(json: String?): RemoteConfig? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.get("schema") ?: return null
            if (!schema.isJsonPrimitive || schema.asInt != 1) return null

            val d = root.getAsJsonObject("display")
            val c = root.getAsJsonObject("cue")
            val t = c?.getAsJsonObject("torch")
            val de = root.getAsJsonObject("defaults")
            val k = root.getAsJsonObject("kiosk")
            val p = root.getAsJsonObject("power")
            val te = root.getAsJsonObject("telemetry")
            val doo = root.getAsJsonObject("device_owner")

            // Keep the auto-size window ordered: min <= max.
            var sizeMin = int(d, "message_size_min_sp", 10, 8, 200)
            var sizeMax = int(d, "message_size_max_sp", 64, 8, 200)
            if (sizeMin > sizeMax) sizeMin = sizeMax

            RemoteConfig(
                schema = 1,
                display = RemoteConfig.Display(
                    name_size_sp = int(d, "name_size_sp", 46, 20, 120),
                    countdown = bool(d, "countdown", false),
                    orientation = orient(d, "orientation"),
                    message_size_min_sp = sizeMin,
                    message_size_max_sp = sizeMax,
                    name_color = color(d, "name_color", DEFAULT_NAME_COLOR),
                    message_color = color(d, "message_color", DEFAULT_MESSAGE_COLOR),
                    warn_color = color(d, "warn_color", DEFAULT_WARN_COLOR),
                    countdown_color = color(d, "countdown_color", DEFAULT_COUNTDOWN_COLOR),
                    idle_clock_scale = float(d, "idle_clock_scale", 1.3f, 1.0f, 3.0f),
                    idle_date_format = dateFormat(d, "idle_date_format"),
                    warn_text = text(d, "warn_text")
                ),
                cue = RemoteConfig.Cue(
                    glow_ms = int(c, "glow_ms", 9000, 0, 20000),
                    chime = bool(c, "chime", true),
                    vibrate = bool(c, "vibrate", true),
                    torch = RemoteConfig.Torch(
                        enabled = bool(t, "enabled", true),
                        on_ms = int(t, "on_ms", 1500, 200, 5000),
                        off_ms = int(t, "off_ms", 1500, 200, 5000),
                        pulses = int(t, "pulses", 3, 1, 10)
                    )
                ),
                defaults = RemoteConfig.Defaults(duration_minutes = int(de, "duration_minutes", 240, 5, 1440)),
                kiosk = RemoteConfig.Kiosk(locked = bool(k, "locked", true)),
                power = RemoteConfig.Power(screen_off_when_idle = bool(p, "screen_off_when_idle", true)),
                telemetry = RemoteConfig.Telemetry(interval_minutes = int(te, "interval_minutes", 15, 1, 1440)),
                device_owner = RemoteConfig.DeviceOwner(
                    status_bar_disabled = bool(doo, "status_bar_disabled", true),
                    lock_task = bool(doo, "lock_task", true),
                    restrict_accounts = bool(doo, "restrict_accounts", true),
                    restrict_bluetooth = bool(doo, "restrict_bluetooth", true),
                    restrict_location = bool(doo, "restrict_location", true),
                    restrict_printing = bool(doo, "restrict_printing", true),
                    restrict_sync = bool(doo, "restrict_sync", true)
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun int(o: JsonObject?, key: String, def: Int, min: Int, max: Int): Int {
        val p = o?.get(key) ?: return def
        if (!p.isJsonPrimitive) return def
        val v = runCatching { p.asInt }.getOrNull() ?: return def
        return v.coerceIn(min, max)
    }

    private fun float(o: JsonObject?, key: String, def: Float, min: Float, max: Float): Float {
        val p = o?.get(key) ?: return def
        if (!p.isJsonPrimitive) return def
        val v = runCatching { p.asFloat }.getOrNull() ?: return def
        return v.coerceIn(min, max)
    }

    private fun bool(o: JsonObject?, key: String, def: Boolean): Boolean {
        val p = o?.get(key) ?: return def
        if (!p.isJsonPrimitive) return def
        return runCatching { p.asBoolean }.getOrDefault(def)
    }

    private fun orient(o: JsonObject?, key: String): String {
        val p = o?.get(key) ?: return "auto"
        if (!p.isJsonPrimitive) return "auto"
        val s = runCatching { p.asString }.getOrDefault("auto")
        return if (s in ORIENTATIONS) s else "auto"
    }

    private fun dateFormat(o: JsonObject?, key: String): String {
        val p = o?.get(key) ?: return "auto"
        if (!p.isJsonPrimitive) return "auto"
        val s = runCatching { p.asString }.getOrDefault("auto")
        return if (s in DATE_FORMATS) s else "auto"
    }

    /** A `#AARRGGBB` colour, or the default when missing/invalid. */
    private fun color(o: JsonObject?, key: String, def: String): String {
        val p = o?.get(key) ?: return def
        if (!p.isJsonPrimitive) return def
        val s = runCatching { p.asString }.getOrDefault(def)
        return if (COLOR_RE.matches(s)) s else def
    }

    /** Optional free-text override; blank/null => null (use the localized string). */
    private fun text(o: JsonObject?, key: String): String? {
        val p = o?.get(key) ?: return null
        if (!p.isJsonPrimitive) return null
        val s = runCatching { p.asString }.getOrDefault("")
        return s.ifBlank { null }
    }
}
