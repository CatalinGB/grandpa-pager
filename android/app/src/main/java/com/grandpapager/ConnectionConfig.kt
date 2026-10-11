package com.grandpapager

import com.google.gson.Gson

/**
 * The broker connection the receiver uses, including the MQTT tuning knobs the
 * admin can change remotely. Held as device setup (never in retained config);
 * a change always travels as a transactional `set_connection` command because it
 * carries a secret and needs a commit-or-revert lifecycle.
 */
data class ConnectionConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8883,
    val use_tls: Boolean = true,
    val username: String = "",
    val password: String = "",
    val keepalive_s: Int = 30,
    val connect_timeout_s: Int = 15,
    val auto_reconnect: Boolean = true,
    val qos: Int = 1,
    val max_inflight: Int = 20
) {
    val serverUri: String get() = (if (use_tls) "ssl://" else "tcp://") + host + ":" + port
    fun passwordSet(): Boolean = password.isNotBlank()
}

object ConnectionCodec {
    private val gson = Gson()

    fun toJson(c: ConnectionConfig): String = gson.toJson(c)

    /**
     * Parse + clamp a connection candidate. Returns null when the host is
     * missing/blank (nothing to connect to).
     */
    fun parse(json: String?): ConnectionConfig? {
        if (json.isNullOrBlank()) return null
        return try {
            val c = gson.fromJson(json, ConnectionConfig::class.java) ?: return null
            val host = c.host.trim()
            if (host.isBlank()) return null
            c.copy(
                host = host,
                port = c.port.coerceIn(1, 65535),
                keepalive_s = c.keepalive_s.coerceIn(5, 3600),
                connect_timeout_s = c.connect_timeout_s.coerceIn(5, 120),
                qos = if (c.qos in 0..2) c.qos else 1,
                max_inflight = c.max_inflight.coerceIn(1, 1000)
            )
        } catch (e: Exception) {
            null
        }
    }
}
