package com.grandpapager

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service owning the MQTT connection for both roles.
 *
 * Receiver: subscribes to grandpa/message + grandpa/command, persists the
 * active message, enforces expiry (wall clock, survives reboot), publishes
 * diagnostics + heartbeat.
 *
 * Sender: publishes retained messages, subscribes to the receiver status.
 */
class PagerService : Service(), MqttController.Listener {

    companion object {
        private const val TAG = "PagerService"
        const val CHANNEL_ID = "pager_status"
        const val NOTIF_ID = 1001
        const val ACTION_SEND = "com.grandpapager.action.SEND"
        const val ACTION_CLEAR = "com.grandpapager.action.CLEAR"
        const val ACTION_EXPIRY = "com.grandpapager.action.EXPIRY"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_EXPECTED_ID = "expectedId"
        private const val HEARTBEAT_MS = 30_000L
        private const val STATUS_STALE_MS = 90_000L
        fun start(ctx: Context) = launch(ctx, Intent(ctx, PagerService::class.java))

        fun send(ctx: Context, text: String, duration: Int) = launch(
            ctx,
            Intent(ctx, PagerService::class.java).setAction(ACTION_SEND)
                .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_DURATION, duration)
        )

        fun clear(ctx: Context) = launch(
            ctx,
            Intent(ctx, PagerService::class.java).setAction(ACTION_CLEAR)
        )

        private fun launch(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private lateinit var cfg: Config
    @Volatile private var mqtt: MqttController? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cfg = Config(this)
        if (!cfg.isConfigured()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_SEND -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val dur = intent.getIntExtra(EXTRA_DURATION, Expiry.DEFAULT_DURATION_MINUTES)
                withConnection { publishMessage(text, dur) }
            }
            ACTION_CLEAR -> withConnection { clearMessage("manual") }
            ACTION_EXPIRY -> {
                val expected = intent.getStringExtra(EXTRA_EXPECTED_ID)
                withConnection { checkExpiry(expected) }
            }
            else -> withConnection { onReady() }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- lifecycle

    private fun withConnection(after: () -> Unit) {
        executor.execute {
            try {
                ensureConnected()
                after()
            } catch (t: Throwable) {
                Log.e(TAG, "operation failed; will retry connect", t)
                scheduleReconnect()
            }
        }
    }

    /**
     * Guarantees a live connection. Paho's automaticReconnect can stop retrying
     * after a long outage, leaving a non-null but dead client; only creating a
     * client when it is null would then strand the service forever (ticket 17).
     * So when the existing client is not connected it is torn down and rebuilt —
     * the old client must be closed first, otherwise its (still retrying) socket
     * would share this clientId and the two would kick each other off.
     */
    private fun ensureConnected() {
        val existing = mqtt
        if (existing != null && existing.isConnected()) return
        if (existing != null) {
            runCatching { existing.disconnect() }
            mqtt = null
            Log.w(TAG, "connection was dead; reconnecting")
        }
        val m = MqttController(this, this)
        m.connect(cfg)
        mqtt = m
        Log.i(TAG, "connected")
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            executor.execute {
                runCatching {
                    ensureConnected()
                    onReady()
                }.onFailure { Log.e(TAG, "reconnect failed", it); scheduleReconnect() }
            }
        }, 5_000L)
    }

    /** Runs once the connection is (re)established: restore state, publish status. */
    private fun onReady() {
        if (cfg.role == Role.RECEIVER) {
            restoreReceiverState()
            publishStatus()
        }
        startWatchdog()
        PagerBus.notifyChanged()
        started.set(true)
    }

    private fun restoreReceiverState() {
        when {
            cfg.hasActiveMessage() -> {
                scheduleExpiry(cfg.currentMessageId!!, cfg.currentExpiresAt)
                Log.i(TAG, "restored active message ${cfg.currentMessageId}")
            }
            cfg.currentMessageId != null -> {
                Log.i(TAG, "stored message already expired; clearing")
                clearMessage("expired-on-boot")
            }
        }
    }

    override fun onDestroy() {
        stopWatchdog()
        Link.connected = false
        runCatching { mqtt?.disconnect() }
        executor.shutdownNow()
        super.onDestroy()
    }

    // --------------------------------------------------------- MQTT callbacks

    override fun onConnected() {
        Log.i(TAG, "onConnected (reconnect complete)")
        Link.connected = true
        executor.execute { onReady() }
    }

    override fun onConnectionLost() {
        Log.w(TAG, "onConnectionLost")
        Link.connected = false
        PagerBus.notifyChanged()
    }

    override fun onMessage(topic: String, payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        executor.execute { handleMessage(topic, text) }
    }

    private fun handleMessage(topic: String, text: String) {
        when (topic) {
            Topics.MESSAGE -> if (cfg.role == Role.RECEIVER) {
                if (text.isBlank() || text == "{}") clearMessage("remote-clear", republish = false)
                else PagerJson.toMessage(text)?.let { showMessage(it) }
            }
            Topics.COMMAND -> if (cfg.role == Role.RECEIVER) {
                if (PagerJson.toCommand(text)?.action == "clear") clearMessage("command")
            }
            Topics.STATUS -> if (cfg.role.isSender) {
                cfg.lastStatusJson = text
                PagerBus.notifyChanged()
            }
        }
    }

    // ------------------------------------------------------------ message ops

    private fun showMessage(msg: MessagePayload) {
        val expiresAt = TimeUtil.fromIso(msg.expires_at)
            ?: Expiry.expiresAt(System.currentTimeMillis(), msg.duration_minutes)
        if (Expiry.isExpired(expiresAt, System.currentTimeMillis())) {
            clearMessage("expired")
            return
        }
        cfg.currentMessageId = msg.id
        cfg.currentMessageText = msg.text
        cfg.currentSender = msg.sender
        cfg.currentExpiresAt = expiresAt
        scheduleExpiry(msg.id, expiresAt)
        publishStatus()
        PagerBus.notifyChanged()
        Log.i(TAG, "showing message ${msg.id} from ${msg.sender}")
    }

    private fun publishMessage(text: String, durationMinutes: Int) {
        if (text.isBlank()) return
        val sender = cfg.senderName.ifBlank { cfg.role.name }
        val msg = PagerJson.newMessage(sender, text, durationMinutes)
        mqtt?.publish(Topics.MESSAGE, PagerJson.message(msg), retained = true)
        PagerBus.notifyChanged()
        Log.i(TAG, "published message ${msg.id} from $sender")
    }

    private fun clearMessage(reason: String, republish: Boolean = true) {
        val hadMessage = cfg.currentMessageId != null
        Log.i(TAG, "clearMessage ($reason)")
        cfg.clearActiveMessage()
        cancelExpiry()
        // Republish an empty retained message only if we actually held one, and
        // never in response to receiving an empty message (avoids an echo loop).
        if (republish && hadMessage) mqtt?.clearRetained(Topics.MESSAGE)
        publishStatus()
        PagerBus.notifyChanged()
    }

    private fun checkExpiry(expectedId: String?) {
        val now = System.currentTimeMillis()
        if (expectedId != null && expectedId == cfg.currentMessageId &&
            Expiry.isExpired(cfg.currentExpiresAt, now)
        ) {
            clearMessage("expired")
        } else if (cfg.hasActiveMessage()) {
            scheduleExpiry(cfg.currentMessageId!!, cfg.currentExpiresAt)
        }
    }

    // ------------------------------------------------------------- diagnostics

    private fun publishStatus() {
        if (cfg.role != Role.RECEIVER) return
        val m = mqtt ?: return
        val d = DiagnosticsPayload(
            state = if (cfg.hasActiveMessage()) "showing" else "idle",
            sender = cfg.currentSender,
            battery = batteryLevel(),
            charging = isCharging(),
            wifi = isWifi(),
            mqtt = m.isConnected(),
            current_message_id = cfg.currentMessageId,
            expires_at = if (cfg.currentExpiresAt > 0) TimeUtil.toIso(cfg.currentExpiresAt) else null,
            last_seen = TimeUtil.nowIso(),
            app = "receiver"
        )
        m.publish(Topics.STATUS, PagerJson.diagnostics(d), retained = true)
    }

    /**
     * Periodic self-healing check for BOTH roles: verifies the link is alive
     * (recreating the client if Paho's automaticReconnect has given up) and, for
     * the receiver, keeps the diagnostics heartbeat flowing so senders can detect
     * it. This is what makes the service recover from a long broker outage.
     */
    private fun startWatchdog() {
        stopWatchdog()
        val r = object : Runnable {
            override fun run() {
                executor.execute {
                    runCatching {
                        ensureConnected()
                        if (cfg.role == Role.RECEIVER) publishStatus()
                    }.onFailure { Log.w(TAG, "watchdog reconnect failed", it) }
                }
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        watchdog = r
        handler.postDelayed(r, HEARTBEAT_MS)
    }

    private fun stopWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    private fun batteryLevel(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) 0 else level * 100 / scale
    }

    private fun isCharging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    @Suppress("DEPRECATION")
    private fun isWifi(): Boolean {
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val info: NetworkInfo? = cm.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }.getOrDefault(false)
    }

    // --------------------------------------------------------------- alarms

    private fun expiryPendingIntent(id: String): PendingIntent {
        val i = Intent(this, PagerService::class.java).setAction(ACTION_EXPIRY)
            .putExtra(EXTRA_EXPECTED_ID, id)
        return PendingIntent.getService(
            this, 2001, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExpiry(id: String, expiresAt: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = expiryPendingIntent(id)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, expiresAt, pi)
            }
        }.onFailure {
            Log.w(TAG, "exact alarm failed, falling back to inexact", it)
            am.set(AlarmManager.RTC_WAKEUP, expiresAt, pi)
        }
    }

    private fun cancelExpiry() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(expiryPendingIntent(cfg.currentMessageId ?: "none"))
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Pager", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(
                    if (cfg.role == Role.RECEIVER) R.string.notif_listening
                    else R.string.notif_connected
                )
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
