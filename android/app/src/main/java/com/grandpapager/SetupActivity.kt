package com.grandpapager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView

/** First-run configuration + role selection. */
class SetupActivity : Activity() {

    private lateinit var cfg: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        setContentView(R.layout.activity_setup)

        val roleReceiver = findViewById<RadioButton>(R.id.roleReceiver)
        val roleAdmin = findViewById<RadioButton>(R.id.roleAdmin)
        val roleFamily = findViewById<RadioButton>(R.id.roleFamily)
        val host = findViewById<EditText>(R.id.hostInput)
        val port = findViewById<EditText>(R.id.portInput)
        val user = findViewById<EditText>(R.id.userInput)
        val pass = findViewById<EditText>(R.id.passInput)
        val name = findViewById<EditText>(R.id.nameInput)
        val tls = findViewById<CheckBox>(R.id.tlsCheck)
        val status = findViewById<TextView>(R.id.setupStatus)

        // Prefill
        when (cfg.role) {
            Role.RECEIVER -> roleReceiver.isChecked = true
            Role.SENDER_ADMIN -> roleAdmin.isChecked = true
            Role.SENDER_FAMILY -> roleFamily.isChecked = true
        }
        if (cfg.isConfigured()) {
            host.setText(cfg.brokerHost)
            port.setText(cfg.brokerPort.toString())
            user.setText(cfg.username)
            pass.setText(cfg.password)
            name.setText(cfg.senderName)
            tls.isChecked = cfg.useTls
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val role = when {
                roleAdmin.isChecked -> Role.SENDER_ADMIN
                roleFamily.isChecked -> Role.SENDER_FAMILY
                else -> Role.RECEIVER
            }
            val hostText = host.text.toString().trim()
            if (hostText.isBlank()) {
                status.text = getString(R.string.err_host_required)
                return@setOnClickListener
            }
            apply(
                role, hostText,
                port.text.toString().trim().toIntOrNull() ?: 8883,
                user.text.toString().trim(), pass.text.toString(),
                name.text.toString().trim(), tls.isChecked, status
            )
        }

        // Magic link / QR: grandpapager://setup?… . First run applies directly
        // (that is the whole point of provisioning); an already configured pager
        // asks first, so a stray link cannot silently repoint a working device.
        if (savedInstanceState == null) {
            val link = ProvisionLink.parse(intent?.dataString)
            if (link != null) {
                when (link.role) {
                    Role.RECEIVER -> roleReceiver.isChecked = true
                    Role.SENDER_ADMIN -> roleAdmin.isChecked = true
                    Role.SENDER_FAMILY -> roleFamily.isChecked = true
                }
                host.setText(link.host)
                port.setText(link.port.toString())
                user.setText(link.username)
                pass.setText(link.password)
                name.setText(link.senderName.orEmpty())
                tls.isChecked = link.tls

                val replacesOther = cfg.isConfigured() &&
                    (cfg.brokerHost != link.host || cfg.username != link.username)
                if (replacesOther) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.provision_title)
                        .setMessage(getString(R.string.provision_confirm, link.host, link.username))
                        .setPositiveButton(R.string.provision_apply) { _, _ ->
                            apply(link.role, link.host, link.port, link.username, link.password,
                                link.senderName.orEmpty(), link.tls, status)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    apply(link.role, link.host, link.port, link.username, link.password,
                        link.senderName.orEmpty(), link.tls, status)
                }
            }
        }

        status.text = if (cfg.isConfigured()) {
            val d = getString(
                if (DeviceOwnerManager.isDeviceOwner(this)) R.string.device_owner
                else R.string.normal_app
            )
            getString(R.string.status_configured, d)
        } else {
            getString(R.string.status_configured_none)
        }
    }

    /** Persist the configuration, re-arm the pager and go to the role's screen. */
    private fun apply(
        role: Role,
        host: String,
        port: Int,
        user: String,
        pass: String,
        senderName: String,
        tls: Boolean,
        status: TextView
    ) {
        cfg.role = role
        cfg.brokerHost = host
        cfg.brokerPort = port
        cfg.username = user
        cfg.password = pass
        cfg.senderName = senderName
        cfg.useTls = tls

        requestBatteryExemption()
        if (role == Role.RECEIVER) DeviceOwnerManager.applyKiosk(this)

        PagerService.start(this)
        status.text = getString(R.string.status_saved, getString(role.labelRes()))
        route(role)
    }

    private fun route(role: Role) {
        val target = if (role == Role.RECEIVER) ReceiverActivity::class.java else SenderActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }
}
