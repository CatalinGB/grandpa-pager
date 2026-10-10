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
        fun onConnectionLost()
        fun onMessage(topic: String, payload: ByteArray)
    }

    @Volatile
    private var client: MqttClient? = null

    fun connect(cfg: Config) {
        val serverUri = cfg.serverUri()
        val clientId = cfg.effectiveClientId()
        Log.i(TAG, "connecting to $serverUri as $clientId (cleanSession=false)")

        val persistence = MqttDefaultFilePersistence(context.filesDir.absolutePath)
        val c = MqttClient(serverUri, clientId, persistence)

        val opts = MqttConnectOptions().apply {
            isCleanSession = false
            isAutomaticReconnect = true
            keepAliveInterval = 30
            connectionTimeout = 15
            maxInflight = 20
            if (cfg.username.isNotBlank()) userName = cfg.username
            if (cfg.password.isNotBlank()) password = cfg.password.toCharArray()
            if (cfg.useTls) socketFactory = TlsUtil.socketFactory(context)

            // NOTE: Flespi rejects a CONNECT that carries a Last Will when the
            // token is a scoped (ACL) token ("Not authorized"), so we do NOT set
            // one. Offline detection falls back to diagnostics staleness (the
            // senders flag a status older than ~90 s). Re-enable here if a
            // will-capable token is created.
        }

        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "connectComplete reconnect=$reconnect")
                subscribe(cfg)
                listener.onConnected()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connectionLost: ${cause?.message}")
                listener.onConnectionLost()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic != null && message != null) {
                    listener.onMessage(topic, message.payload ?: ByteArray(0))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
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

    fun publish(topic: String, payload: String, retained: Boolean) {
        val c = client ?: return
        if (!c.isConnected) return
        c.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retained)
    }

    /** Publish an empty retained payload, clearing the retained message. */
    fun clearRetained(topic: String) {
        val c = client ?: return
        if (!c.isConnected) return
        c.publish(topic, ByteArray(0), 1, true)
    }

    fun isConnected(): Boolean = client?.isConnected == true

    fun disconnect() {
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private const val TAG = "MqttController"
    }
}
