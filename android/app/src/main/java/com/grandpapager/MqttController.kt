package com.grandpapager

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence

/**
 * Thin wrapper around Eclipse Paho's synchronous client. All calls happen on
 * the caller's background thread (PagerService owns a single-thread executor).
 */
class MqttController(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        /** [cause] is the Paho failure (null when the broker closed cleanly). */
        fun onConnectionLost(cause: Throwable? = null)
        fun onMessage(topic: String, payload: ByteArray)
        /** The broker acknowledged a publish; [topics] is null when unknown. */
        fun onDelivered(topics: Array<String>? = null)
    }

    @Volatile
    private var client: MqttClient? = null
    @Volatile
    private var qos: Int = 1

    fun connect(cfg: Config, conn: ConnectionConfig) {
        val serverUri = conn.serverUri
        val clientId = cfg.effectiveClientId()
        Log.i(TAG, "connecting to $serverUri as $clientId (cleanSession=false)")

        val persistence = MqttDefaultFilePersistence(context.filesDir.absolutePath)
        val c = MqttClient(serverUri, clientId, persistence)

        val opts = MqttConnectOptions().apply {
            isCleanSession = false
            isAutomaticReconnect = conn.auto_reconnect
            keepAliveInterval = conn.keepalive_s
            connectionTimeout = conn.connect_timeout_s
            maxInflight = conn.max_inflight
            if (conn.username.isNotBlank()) userName = conn.username
            if (conn.password.isNotBlank()) password = conn.password.toCharArray()
            if (conn.use_tls) socketFactory = TlsUtil.socketFactory(context)

            // NOTE: Flespi rejects a CONNECT that carries a Last Will when the
            // token is a scoped (ACL) token ("Not authorized"), so we do NOT set
            // one. Offline detection falls back to diagnostics staleness — the
            // senders flag a status older than the derived threshold
            // (2×telemetry interval + 60 s, min 90 s; decisions.md D13).
            // Re-enable here if a will-capable token is created.
        }
        qos = conn.qos

        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "connectComplete reconnect=$reconnect")
                subscribe(cfg)
                listener.onConnected()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connectionLost: ${cause?.message}")
                listener.onConnectionLost(cause)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic != null && message != null) {
                    listener.onMessage(topic, message.payload ?: ByteArray(0))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                listener.onDelivered(runCatching { token?.topics }.getOrNull())
            }
        })

        c.connect(opts)
        client = c
        subscribe(cfg)
    }

    private fun subscribe(cfg: Config) {
        val c = client ?: return
        val (topics, qos) = when (cfg.role) {
            Role.RECEIVER -> listOf(Topics.MESSAGE, Topics.COMMAND, Topics.CONFIG) to intArrayOf(1, 1, 1)
            else -> listOf(Topics.STATUS) to intArrayOf(1)
        }
        c.subscribe(topics.toTypedArray(), qos)
        Log.i(TAG, "subscribed to $topics")
    }

    /**
     * Returns false when nothing could even be queued (no client / not connected).
     * A true return only means "handed to Paho" — the delivery callback is the
     * real proof the broker accepted it, so never treat true as liveness.
     */
    fun publish(topic: String, payload: String, retained: Boolean): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return runCatching {
            c.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retained)
        }.onFailure { Log.w(TAG, "publish failed: ${it.message}") }.isSuccess
    }

    /** Publish an empty retained payload, clearing the retained message. */
    fun clearRetained(topic: String) {
        val c = client ?: return
        if (!c.isConnected) return
        c.publish(topic, ByteArray(0), qos, true)
    }

    fun isConnected(): Boolean = client?.isConnected == true

    fun disconnect() {
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private const val TAG = "MqttController"

        /**
         * Best-effort reachability probe of a candidate broker using a throwaway
         * client id, so a change can be tested before committing. Never throws.
         */
        fun probe(context: Context, candidate: ConnectionConfig): Boolean {
            val id = "grandpa-probe-" + System.currentTimeMillis()
            var c: MqttClient? = null
            return try {
                c = MqttClient(candidate.serverUri, id, null)
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = false
                    keepAliveInterval = candidate.keepalive_s
                    connectionTimeout = candidate.connect_timeout_s
                    if (candidate.username.isNotBlank()) userName = candidate.username
                    if (candidate.password.isNotBlank()) password = candidate.password.toCharArray()
                    if (candidate.use_tls) socketFactory = TlsUtil.socketFactory(context)
                }
                c.connect(opts)
                true
            } catch (e: Exception) {
                Log.w(TAG, "probe ${candidate.serverUri} failed: ${e.message}")
                false
            } finally {
                runCatching { c?.disconnect() }
                runCatching { c?.close() }
            }
        }
    }
}
