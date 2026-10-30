package com.grandpapager

import java.net.URLDecoder

/** A complete pager configuration carried by a setup link / QR code. */
data class ProvisionConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    val password: String,
    val role: Role,
    val senderName: String?
)

/**
 * Parser for the `grandpapager://setup?…` magic link.
 *
 * Deliberately free of Android APIs so the contract is unit-tested. The web side
 * (`setup.html`) only forwards the fragment, so both ends must agree on these
 * parameter names:
 *
 *   host (alias broker) · port · tls · user (alias username) · pass (alias
 *   password) · role (receiver|admin|family) · name
 *
 * Unknown parameters are ignored, so the link can grow without breaking older
 * builds. A malformed link returns null and the setup screen is shown as usual.
 */
object ProvisionLink {
    const val SCHEME = "grandpapager"
    const val AUTHORITY = "setup"

    fun parse(raw: String?): ProvisionConfig? {
        if (raw.isNullOrBlank()) return null
        val link = raw.trim()
        val prefix = "$SCHEME://$AUTHORITY"
        if (link.length < prefix.length || !link.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            return null
        }
        val rest = link.substring(prefix.length)
        val query = when {
            rest.isEmpty() -> ""
            rest.startsWith("?") -> rest.substring(1)
            else -> return null // a path segment is not part of the contract
        }
        val q = parseQuery(query)

        var host = (q["host"] ?: q["broker"] ?: "").trim()
        for (prefix in listOf("mqtts://", "mqtt://", "wss://", "ws://")) {
            if (host.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                host = host.substring(prefix.length)
                break
            }
        }
        host = host.trimEnd('/').trim()

        // Accept "host:port" (e.g. copied from a connection string); an explicit
        // `port` parameter wins. IPv6 literals are left untouched.
        var embeddedPort: Int? = null
        val firstColon = host.indexOf(':')
        if (firstColon > 0 && firstColon == host.lastIndexOf(':')) {
            val candidate = host.substring(firstColon + 1).toIntOrNull()
            if (candidate != null) {
                embeddedPort = candidate
                host = host.substring(0, firstColon)
            }
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null

        val portParam = q["port"]?.trim()
        val port = when {
            portParam.isNullOrEmpty() -> embeddedPort ?: 8883
            // An explicit but unparsable port is a broken link, not a fallback.
            else -> portParam.toIntOrNull() ?: return null
        }.takeIf { it in 1..65535 } ?: return null
        val tls = parseTls(q["tls"])
        val role = when (q["role"]?.trim()?.lowercase()) {
            null, "", "receiver", "rx" -> Role.RECEIVER
            "admin", "sender_admin" -> Role.SENDER_ADMIN
            "family", "sender_family" -> Role.SENDER_FAMILY
            else -> return null
        }
        return ProvisionConfig(
            host = host,
            port = port,
            tls = tls,
            username = q["user"] ?: q["username"] ?: "",
            password = q["pass"] ?: q["password"] ?: "",
            role = role,
            senderName = q["name"]?.trim()?.ifBlank { null }
        )
    }

    private fun parseTls(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
        "0", "false", "no", "off" -> false
        else -> true
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isBlank()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            out[decode(key).lowercase()] = decode(value)
        }
        return out
    }

    /**
     * Percent-decode UTF-8. A literal `+` stays a `+` (tokens may contain one),
     * unlike the form-encoding rule where `+` means a space.
     */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
