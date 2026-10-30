package com.grandpapager

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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

    // --- MQTT tuning (device setup; changed via the guarded connection command) ---
    var keepaliveS: Int
        get() = p.getInt("keepalive", 30)
        set(v) = p.edit().putInt("keepalive", v).apply()

    var connectTimeoutS: Int
        get() = p.getInt("connTimeout", 15)
        set(v) = p.edit().putInt("connTimeout", v).apply()

    var autoReconnect: Boolean
        get() = p.getBoolean("autoReconnect", true)
        set(v) = p.edit().putBoolean("autoReconnect", v).apply()

    var mqttQos: Int
        get() = p.getInt("mqttQos", 1)
        set(v) = p.edit().putInt("mqttQos", v).apply()

    var maxInflight: Int
        get() = p.getInt("maxInflight", 20)
        set(v) = p.edit().putInt("maxInflight", v).apply()

    /** The full broker connection (host/credentials + tuning). */
    fun connection(): ConnectionConfig = ConnectionConfig(
        host = brokerHost, port = brokerPort, use_tls = useTls,
        username = username, password = password,
        keepalive_s = keepaliveS, connect_timeout_s = connectTimeoutS,
        auto_reconnect = autoReconnect, qos = mqttQos, max_inflight = maxInflight
    )

    fun setConnection(c: ConnectionConfig) {
        brokerHost = c.host
        brokerPort = c.port
        useTls = c.use_tls
        username = c.username
        password = c.password
        keepaliveS = c.keepalive_s
        connectTimeoutS = c.connect_timeout_s
        autoReconnect = c.auto_reconnect
        mqttQos = c.qos
        maxInflight = c.max_inflight
    }

    /** Persisted guardrail state for a pending connection change (survives reboot). */
    var guardStateJson: String?
        get() = p.getString("guardState", null)
        set(v) = p.edit().putString("guardState", v).apply()

    /** Result of the last device-control command, echoed in status. */
    var lastCommandJson: String?
        get() = p.getString("lastCommand", null)
        set(v) = p.edit().putString("lastCommand", v).apply()

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

    /** Wall-clock time the current message was received/shown (epoch millis). */
    var currentReceivedAt: Long
        get() = p.getLong("curReceived", 0L)
        set(v) = p.edit().putLong("curReceived", v).apply()

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

    // --- on-device maintenance supervisor (survives reboot) ---
    /** Epoch millis of the last successful broker publish/connect (0 = never). */
    var lastPublishAt: Long
        get() = p.getLong("lastPublish", 0L)
        set(v) = p.edit().putLong("lastPublish", v).apply()

    /** Last *stale-triggered* app restart (epoch millis). */
    var lastStaleRestartAt: Long
        get() = p.getLong("lastStaleRestart", 0L)
        set(v) = p.edit().putLong("lastStaleRestart", v).apply()

    /** Last *stale-triggered* reboot (epoch millis). */
    var lastStaleRebootAt: Long
        get() = p.getLong("lastStaleReboot", 0L)
        set(v) = p.edit().putLong("lastStaleReboot", v).apply()

    /** Last scheduled (interval) app restart (epoch millis). */
    var lastScheduledRestartAt: Long
        get() = p.getLong("lastSchedRestart", 0L)
        set(v) = p.edit().putLong("lastSchedRestart", v).apply()

    /** Last scheduled (interval) reboot (epoch millis). */
    var lastScheduledRebootAt: Long
        get() = p.getLong("lastSchedReboot", 0L)
        set(v) = p.edit().putLong("lastSchedReboot", v).apply()

    /** Why the last automatic reboot happened: "scheduled" or "stale". */
    var lastRebootReason: String?
        get() = p.getString("lastRebootReason", null)
        set(v) = p.edit().putString("lastRebootReason", v).apply()

    // --- diagnostics (all additive; persisted so a reboot keeps the story) ---
    /** Last publish the broker acknowledged (epoch millis, 0 = never). */
    var lastPublishOkAt: Long
        get() = p.getLong("lastPublishOk", 0L)
        set(v) = p.edit().putLong("lastPublishOk", v).apply()

    var connectCount: Long
        get() = p.getLong("connectCount", 0L)
        set(v) = p.edit().putLong("connectCount", v).apply()

    var disconnectCount: Long
        get() = p.getLong("disconnectCount", 0L)
        set(v) = p.edit().putLong("disconnectCount", v).apply()

    var lastDisconnectAt: Long
        get() = p.getLong("lastDisconnectAt", 0L)
        set(v) = p.edit().putLong("lastDisconnectAt", v).apply()

    var lastDisconnectReason: String?
        get() = p.getString("lastDisconnectReason", null)
        set(v) = p.edit().putString("lastDisconnectReason", v).apply()

    var serviceStartCount: Long
        get() = p.getLong("serviceStartCount", 0L)
        set(v) = p.edit().putLong("serviceStartCount", v).apply()

    /** boot / command / stale / scheduled / start */
    var lastStartReason: String?
        get() = p.getString("lastStartReason", null)
        set(v) = p.edit().putString("lastStartReason", v).apply()

    /** Consecutive stale reboots that did not restore the link (exponential backoff). */
    var rebootBackoff: Int
        get() = p.getInt("rebootBackoff", 0)
        set(v) = p.edit().putInt("rebootBackoff", v).apply()

    /** The currently applied remote config (defaults if none). */
    fun remoteConfig(): RemoteConfig =
        RemoteConfigCodec.parse(remoteConfigJson) ?: RemoteConfig()

    // --- per-sender name colours (assigned once, persisted, kept unique) ---
    private val gson = Gson()

    /** The persisted sender→colour assignments (name key → #AARRGGBB). */
    @Suppress("UNCHECKED_CAST")
    fun senderColors(): Map<String, String> {
        val json = p.getString("senderColors", null) ?: return emptyMap()
        return runCatching {
            val t = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, t)
        }.getOrDefault(emptyMap())
    }

    fun setSenderColor(key: String, color: String) {
        val next = senderColors().toMutableMap()
        next[key] = color
        p.edit().putString("senderColors", gson.toJson(next)).apply()
    }

    // --- sender-side cached receiver view ---
    var lastStatusJson: String?
        get() = p.getString("lastStatus", null)
        set(v) = p.edit().putString("lastStatus", v).apply()

    fun isConfigured(): Boolean = brokerHost.isNotBlank()

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
            .remove("curId").remove("curText").remove("curSender").remove("curExpires").remove("curReceived")
            .apply()
    }
}
