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
    val kiosk: Kiosk = Kiosk()
) {
    data class Display(val name_size_sp: Int = 46, val countdown: Boolean = false, val orientation: String = "auto")
    data class Cue(val glow_ms: Int = 9000, val chime: Boolean = true, val vibrate: Boolean = true, val torch: Torch = Torch())
    data class Torch(val enabled: Boolean = true, val on_ms: Int = 1500, val off_ms: Int = 1500, val pulses: Int = 3)
    data class Defaults(val duration_minutes: Int = 240)
    data class Kiosk(val locked: Boolean = true)
}

object RemoteConfigCodec {
    private val gson = Gson()
    private val ORIENTATIONS = setOf("auto", "portrait", "landscape")

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

            RemoteConfig(
                schema = 1,
                display = RemoteConfig.Display(
                    name_size_sp = int(d, "name_size_sp", 46, 20, 120),
                    countdown = bool(d, "countdown", false),
                    orientation = orient(d, "orientation")
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
                kiosk = RemoteConfig.Kiosk(locked = bool(k, "locked", true))
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
}
