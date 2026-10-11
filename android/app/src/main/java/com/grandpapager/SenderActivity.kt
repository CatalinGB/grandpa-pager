package com.grandpapager

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Compose + monitoring surface for the admin and family senders. */
class SenderActivity : Activity() {

    private lateinit var cfg: Config
    private val busListener = Runnable { refreshStatus() }

    private lateinit var senderInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var statusOutput: TextView
    private lateinit var clearButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        setContentView(R.layout.activity_sender)

        senderInput = findViewById(R.id.senderInput)
        messageInput = findViewById(R.id.messageInput)
        durationInput = findViewById(R.id.durationInput)
        statusOutput = findViewById(R.id.statusOutput)
        clearButton = findViewById(R.id.clearButton)

        senderInput.setText(cfg.senderName)
        // Default the duration to the admin's configured default, if we have seen it.
        val remoteDefault = cfg.lastStatusJson
            ?.let { PagerJson.toDiagnostics(it)?.config?.defaults?.duration_minutes }
        durationInput.setText((remoteDefault ?: Expiry.DEFAULT_DURATION_MINUTES).toString())

        if (cfg.role == Role.SENDER_FAMILY) {
            senderInput.isEnabled = false
            clearButton.visibility = View.GONE
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener { send() }
        clearButton.setOnClickListener { PagerService.clear(this) }

        PagerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        PagerBus.addListener(busListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        PagerBus.removeListener(busListener)
    }

    private fun send() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            statusOutput.text = getString(R.string.err_type_message)
            return
        }
        if (senderInput.isEnabled) cfg.senderName = senderInput.text.toString().trim()
        val duration = durationInput.text.toString().trim().toIntOrNull()
            ?.coerceIn(1, 24 * 60) ?: Expiry.DEFAULT_DURATION_MINUTES
        PagerService.send(this, text, duration)
        messageInput.setText("")
        statusOutput.text = getString(R.string.sent_confirm)
    }

    private fun refreshStatus() {
        val json = cfg.lastStatusJson
        if (json.isNullOrBlank()) {
            statusOutput.text = getString(R.string.status_none)
            return
        }
        val d = PagerJson.toDiagnostics(json)
        if (d == null) {
            statusOutput.text = getString(R.string.status_unreadable)
            return
        }
        val lastSeen = TimeUtil.fromIso(d.last_seen) ?: 0L
        val ageSec = ((System.currentTimeMillis() - lastSeen) / 1000L).coerceAtLeast(0L)
        val online = d.state != "offline" && ageSec < 90
        val stale = if (online) "" else "  " + getString(R.string.stale_suffix, ageSec)
        val charging = if (d.charging) " " + getString(R.string.charging_suffix) else ""
        val temp = d.battery_temp_c?.let { "  " + getString(R.string.temp_suffix, it) } ?: ""
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.lbl_state) + " " + d.state + stale)
        sb.appendLine(getString(R.string.lbl_mqtt) + " " + d.mqtt)
        sb.appendLine(getString(R.string.lbl_battery) + " " + d.battery + "%" + charging + temp)
        sb.appendLine(getString(R.string.lbl_wifi) + " " + d.wifi)
        sb.appendLine(getString(R.string.lbl_message) + " " + (d.sender ?: "-"))
        sb.appendLine(getString(R.string.lbl_msg_id) + " " + (d.current_message_id ?: "-"))
        sb.appendLine(getString(R.string.lbl_expires) + " " + (d.expires_at ?: "-"))
        sb.append(getString(R.string.lbl_seen) + " " + getString(R.string.seen_ago, ageSec))
        statusOutput.text = sb.toString()
    }
}
