package com.grandpapager

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Persistent configuration + receiver state, backed by SharedPreferences.
 * Mutable state is deliberately simple so it survives reboots.
 */
class Config(context: Context) {

    private val app = context.applicationContext
    private val p = app.getSharedPreferences("pager", Context.MODE_PRIVATE)

    private val androidId: String
        @SuppressLint("HardwareIds")
        get() = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    var role: Role
        get() = runCatching { Role.valueOf(p.getString("role", Role.RECEIVER.name)!!) }.getOrDefault(Role.RECEIVER)
        set(v) = p.edit().putString("role", v.name).apply()

    var brokerHost: String
        get() = p.getString("brokerHost", "127.0.0.1")!!
        set(v) = p.edit().putString("brokerHost", v).apply()

    var brokerPort: Int
        get() = p.getInt("brokerPort", 8883)
        set(v) = p.edit().putInt("brokerPort", v).apply()

    var username: String
        get() = p.getString("username", "")!!
        set(v) = p.edit().putString("username", v).apply()

    var password: String
        get() = p.getString("password", "")!!
        set(v) = p.edit().putString("password", v).apply()

    var senderName: String
        get() = p.getString("senderName", "")!!
        set(v) = p.edit().putString("senderName", v).apply()

    var useTls: Boolean
        get() = p.getBoolean("useTls", true)
        set(v) = p.edit().putBoolean("useTls", v).apply()

    /** Whether we have already shown the battery-optimization request dialog. */
    var batteryExemptionAsked: Boolean
        get() = p.getBoolean("battAsked", false)
        set(v) = p.edit().putBoolean("battAsked", v).apply()

    // --- receiver state (persisted so expiry survives reboot) ---
    var currentMessageId: String?
        get() = p.getString("curId", null)
        set(v) = p.edit().putString("curId", v).apply()

    var currentMessageText: String?
        get() = p.getString("curText", null)
        set(v) = p.edit().putString("curText", v).apply()

    var currentSender: String?
        get() = p.getString("curSender", null)
        set(v) = p.edit().putString("curSender", v).apply()

    var currentExpiresAt: Long
        get() = p.getLong("curExpires", 0L)
        set(v) = p.edit().putLong("curExpires", v).apply()

    /** Id of the last message we drew attention to, so we never re-alert it. */
    var lastCuedMessageId: String?
        get() = p.getString("lastCued", null)
        set(v) = p.edit().putString("lastCued", v).apply()

    // --- remote config (admin → grandpa/config) ---
    var remoteConfigJson: String?
        get() = p.getString("remoteConfig", null)
        set(v) = p.edit().putString("remoteConfig", v).apply()

    var remoteConfigAppliedAt: String?
        get() = p.getString("remoteAppliedAt", null)
        set(v) = p.edit().putString("remoteAppliedAt", v).apply()

    var remoteConfigError: String?
        get() = p.getString("remoteError", null)
        set(v) = p.edit().putString("remoteError", v).apply()

    /** The currently applied remote config (defaults if none). */
    fun remoteConfig(): RemoteConfig =
        RemoteConfigCodec.parse(remoteConfigJson) ?: RemoteConfig()

    // --- sender-side cached receiver view ---
    var lastStatusJson: String?
        get() = p.getString("lastStatus", null)
        set(v) = p.edit().putString("lastStatus", v).apply()

    fun isConfigured(): Boolean = brokerHost.isNotBlank()

    fun serverUri(): String =
        (if (useTls) "ssl://" else "tcp://") + brokerHost + ":" + brokerPort

    fun effectiveClientId(): String {
        val r = when (role) {
            Role.RECEIVER -> "rx"
            Role.SENDER_ADMIN -> "admin"
            Role.SENDER_FAMILY -> "fam"
        }
        return "grandpa-$r-$androidId"
    }

    fun hasActiveMessage(): Boolean =
        !currentMessageId.isNullOrBlank() && currentExpiresAt > 0L &&
            !Expiry.isExpired(currentExpiresAt, System.currentTimeMillis())

    fun clearActiveMessage() {
        p.edit()
            .remove("curId").remove("curText").remove("curSender").remove("curExpires")
            .apply()
    }
}
