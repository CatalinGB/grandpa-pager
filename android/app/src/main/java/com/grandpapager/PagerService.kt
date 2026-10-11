package com.grandpapager

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
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
        const val ALERT_CHANNEL_ID = "pager_alert"
        const val NOTIF_ID = 1001
        const val ALERT_NOTIF_ID = 1002
        const val ACTION_SEND = "com.grandpapager.action.SEND"
        const val ACTION_CLEAR = "com.grandpapager.action.CLEAR"
        const val ACTION_EXPIRY = "com.grandpapager.action.EXPIRY"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_EXPECTED_ID = "expectedId"

        /**
         * The reconnect watchdog and the status cadence are deliberately separate:
         * the socket is kept alive by the MQTT keepalive, so status can be slow
         * (admin-configurable, default 15 min) without delaying reconnection.
         */
        private const val WATCHDOG_MS = 30_000L

        /** Show a message that arrived (briefly) after its TTL, up to this late. */
        private const val LATE_GRACE_MS = 10 * 60_000L
        private const val LATE_MIN_SHOW_MS = 60_000L

        /** How often the connection guardrail re-evaluates a pending change. */
        private const val GUARD_TICK_MS = 5_000L
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
    private val guard = ConfigGuard()
    private var guardTimer: Runnable? = null
    private val gson = Gson()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    private var statusTimer: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
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
        if (cfg.role == Role.RECEIVER) restoreGuard()
        startForeground(NOTIF_ID, buildNotification())
        acquireLocks()

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
        m.connect(cfg, cfg.connection())
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
            startStatusTimer()
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
        stopStatusTimer()
        stopGuardTimer()
        releaseLocks()
        Link.connected = false
        runCatching { mqtt?.disconnect() }
        executor.shutdownNow()
        super.onDestroy()
    }

    // --------------------------------------------------------- MQTT callbacks

    override fun onConnected() {
        Log.i(TAG, "onConnected (reconnect complete)")
        Link.connected = true
        executor.execute {
            // A live link while a connection change is switched in starts (or
            // restarts) the confirm clock.
            if (guard.state.status == GuardStatus.SWITCHED) {
                guard.on(GuardEvent.Connected(System.currentTimeMillis()))
                persistGuard()
            }
            onReady()
        }
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
                handleCommand(text)
            }
            Topics.CONFIG -> if (cfg.role == Role.RECEIVER) {
                applyRemoteConfig(text)
            }
            Topics.STATUS -> if (cfg.role.isSender) {
                cfg.lastStatusJson = text
                PagerBus.notifyChanged()
            }
        }
    }

    /** Validate + store the admin remote config, then ack via status. */
    private fun applyRemoteConfig(text: String) {
        val parsed = RemoteConfigCodec.parse(text)
        if (parsed == null) {
            cfg.remoteConfigError = "schema invalid"
            Log.w(TAG, "config rejected (bad schema)")
        } else {
            cfg.remoteConfigJson = RemoteConfigCodec.toJson(parsed)
            cfg.remoteConfigAppliedAt = TimeUtil.nowIso()
            cfg.remoteConfigError = null
            Log.i(TAG, "config applied: ${cfg.remoteConfigJson}")
            // Switching the idle screen back on (clock mode) must wake the
            // activity, which is paused while the display was asleep.
            if (!parsed.power.screen_off_when_idle) wakeReceiverActivity()
        }
        if (started.get()) startStatusTimer() // cadence may have changed
        publishStatus()
        PagerBus.notifyChanged()
    }

    // ------------------------------------------------------------ commands

    private fun handleCommand(text: String) {
        val cmd = PagerJson.toCommand(text) ?: return
        val now = System.currentTimeMillis()
        when (cmd.action) {
            "clear" -> clearMessage("command")
            "set_connection" -> beginConnectionChange(cmd, now)
            "restart_app" -> {
                ackCommand("restart_app", "ok")
                restartApp()
            }
            "reboot_device" -> {
                if (!DeviceOwnerManager.isDeviceOwner(this)) {
                    ackCommand("reboot_device", "not device owner")
                    publishStatus()
                } else {
                    ackCommand("reboot_device", "ok")
                    publishStatus()
                    // Give the retained ack a moment to flush before the radio drops.
                    runCatching { Thread.sleep(1200) }
                    rebootDevice()
                }
            }
            else -> Log.w(TAG, "unknown command: ${cmd.action}")
        }
    }

    private fun ackCommand(action: String, result: String) {
        cfg.lastCommandJson = gson.toJson(CommandStatus(action, TimeUtil.nowIso(), result))
    }

    private fun restartApp() {
        Log.i(TAG, "restart_app requested")
        guard.reset()
        persistGuard()
        publishStatus()
        restartReceiverActivity()
        runCatching { scheduleServiceRestart() }
            .onFailure { Log.w(TAG, "restart_app scheduling failed", it) }
        stopSelf()
    }

    /**
     * Re-arm the foreground service for real. A service is NOT revived by
     * START_STICKY after an explicit `stopSelf()`, so `restart_app` schedules a
     * fresh start a moment ahead, then stops this instance. On O+ a
     * `getForegroundService` PendingIntent is exempt from the background-start
     * restriction, so the new instance may call `startForeground()` immediately.
     */
    private fun scheduleServiceRestart() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PagerService::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 4001, intent, flags)
        } else {
            PendingIntent.getService(this, 4001, intent, flags)
        }
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 400L, pi)
    }

    private fun restartReceiverActivity() {
        runCatching {
            startActivity(
                Intent(this, ReceiverActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        }.onFailure { Log.w(TAG, "restartReceiverActivity failed", it) }
    }

    /** Device-owner reboot; returns false when we lack the rights. */
    private fun rebootDevice(): Boolean {
        if (!DeviceOwnerManager.isDeviceOwner(this)) return false
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, PagerDeviceAdminReceiver::class.java)
        return runCatching { dpm.reboot(admin) }
            .onFailure { Log.w(TAG, "reboot failed", it) }
            .isSuccess
    }

    // ------------------------------------------------- connection guardrail

    /**
     * Begin a transactional broker/credential change: probe the candidate, switch
     * to it, and let [ConfigGuard] commit or auto-revert within the window. The
     * pending state is persisted so a reboot resumes (and, if unconfirmed, reverts).
     */
    private fun beginConnectionChange(cmd: CommandPayload, now: Long) {
        if (guard.state.isPending) {
            ackCommand("set_connection", "busy: a change is already pending")
            publishStatus()
            return
        }
        val candidate = cmd.candidate
        if (candidate == null) {
            ackCommand("set_connection", "invalid: missing candidate")
            publishStatus()
            return
        }
        val backup = cfg.connection()
        // Blank username/password mean "keep the current one" (the secret can't be
        // read back to the console, so an empty field must not wipe it).
        val merged = candidate.copy(
            username = candidate.username.ifBlank { backup.username },
            password = candidate.password.ifBlank { backup.password }
        )
        val revertMs = ConfigGuard.clampRevertSeconds(cmd.revert_after_s) * 1000L
        val confirmMs = ConfigGuard.clampConfirmSeconds(cmd.confirm_after_s) * 1000L
        ackCommand("set_connection", "pending")
        val effect = guard.on(GuardEvent.Request(merged, backup, now, revertMs, confirmMs))
        persistGuard()
        if (effect is GuardEffect.StartProbe) {
            val sameEndpoint = merged.host == backup.host &&
                merged.port == backup.port && merged.use_tls == backup.use_tls
            // Only a real broker move can be probed on a second connection; a
            // credential/tuning-only change is validated by the switch itself.
            val ok = if (sameEndpoint) true else MqttController.probe(this, merged)
            val next = guard.on(
                if (ok) GuardEvent.ProbeOk(System.currentTimeMillis())
                else GuardEvent.ProbeFail(System.currentTimeMillis())
            )
            persistGuard()
            handleGuardEffect(next)
        }
        startGuardTimer()
        publishStatus()
        PagerBus.notifyChanged()
    }

    private fun handleGuardEffect(effect: GuardEffect) {
        when (effect) {
            is GuardEffect.SwitchTo -> {
                cfg.setConnection(effect.candidate)
                ackCommand("set_connection", "switched; awaiting confirmation")
                reconnect()
            }
            is GuardEffect.Revert -> {
                effect.backup?.let { cfg.setConnection(it) }
                ackCommand("set_connection", "reverted: ${guard.state.lastError ?: "no link"}")
                reconnect()
            }
            GuardEffect.Commit -> {
                ackCommand("set_connection", "committed")
                Log.i(TAG, "connection change committed")
            }
            else -> {}
        }
        persistGuard()
    }

    private fun reconnect() {
        runCatching { mqtt?.disconnect() }
        mqtt = null
        runCatching { ensureConnected() }.onFailure { Log.w(TAG, "reconnect failed", it) }
        onReady()
    }

    private fun startGuardTimer() {
        stopGuardTimer()
        val r = object : Runnable {
            override fun run() {
                val self = this
                executor.execute {
                    if (!guard.state.isPending) return@execute
                    val effect = guard.on(GuardEvent.Tick(System.currentTimeMillis()))
                    handleGuardEffect(effect)
                    publishStatus()
                    if (guard.state.isPending) handler.postDelayed(self, GUARD_TICK_MS)
                }
            }
        }
        guardTimer = r
        handler.postDelayed(r, GUARD_TICK_MS)
    }

    private fun stopGuardTimer() {
        guardTimer?.let { handler.removeCallbacks(it) }
        guardTimer = null
    }

    private fun persistGuard() {
        cfg.guardStateJson = if (guard.state.isPending) gson.toJson(guard.state) else null
    }

    private fun restoreGuard() {
        val json = cfg.guardStateJson ?: return
        val persisted = runCatching { gson.fromJson(json, GuardState::class.java) }.getOrNull() ?: return
        guard.resumePersisted(persisted, System.currentTimeMillis())
        if (guard.state.isPending) {
            Log.i(TAG, "resuming pending connection change (${guard.state.status})")
            startGuardTimer()
        }
    }

    /**
     * Bring ReceiverActivity forward (and hence light the screen). Used when a
     * config change re-enables the idle clock while the display is asleep; the
     * activity is paused at that point and cannot re-evaluate on its own.
     */
    private fun wakeReceiverActivity() {
        if (cfg.role != Role.RECEIVER) return
        runCatching {
            startActivity(
                Intent(this, ReceiverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.w(TAG, "wakeReceiverActivity failed", it) }
    }

    // ------------------------------------------------------------ message ops
    private fun showMessage(msg: MessagePayload) {
        val now = System.currentTimeMillis()
        var expiresAt = TimeUtil.fromIso(msg.expires_at)
            ?: Expiry.expiresAt(now, msg.duration_minutes)
        if (Expiry.isExpired(expiresAt, now)) {
            // A brief offline blip can make a just-sent message arrive just after
            // its TTL. Rather than silently lose it, show a never-seen message
            // once, re-dated to a short readable window. Older messages are dropped.
            val lateBy = now - expiresAt
            val unseen = msg.id != cfg.lastCuedMessageId
            if (unseen && lateBy <= LATE_GRACE_MS) {
                expiresAt = now + LATE_MIN_SHOW_MS
                Log.i(TAG, "message ${msg.id} arrived ${lateBy / 1000}s late; showing briefly")
            } else {
                clearMessage("expired")
                return
            }
        }
        cfg.currentMessageId = msg.id
        cfg.currentMessageText = msg.text
        cfg.currentSender = msg.sender
        cfg.currentExpiresAt = expiresAt
        cfg.currentReceivedAt = now
        scheduleExpiry(msg.id, expiresAt)
        publishStatus()
        PagerBus.notifyChanged()
        showWakeNotification(msg)
        Log.i(TAG, "showing message ${msg.id} from ${msg.sender}")
    }

    private fun publishMessage(text: String, durationMinutes: Int) {
        if (text.isBlank()) return
        val sender = cfg.senderName.ifBlank { getString(cfg.role.labelRes()) }
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
        cancelWakeNotification()
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
            text = if (cfg.hasActiveMessage()) cfg.currentMessageText else null,
            battery = batteryLevel(),
            charging = isCharging(),
            wifi = isWifi(),
            mqtt = m.isConnected(),
            current_message_id = cfg.currentMessageId,
            expires_at = if (cfg.currentExpiresAt > 0) TimeUtil.toIso(cfg.currentExpiresAt) else null,
            last_seen = TimeUtil.nowIso(),
            app = "receiver",
            config = cfg.remoteConfig(),
            config_applied_at = cfg.remoteConfigAppliedAt,
            config_error = cfg.remoteConfigError,
            device_owner = DeviceOwnerManager.isDeviceOwner(this),
            connection = ConnectionStatus(
                state = guard.state.status.name.lowercase(),
                host = cfg.brokerHost,
                port = cfg.brokerPort,
                use_tls = cfg.useTls,
                password_set = cfg.password.isNotBlank(),
                last_error = guard.state.lastError
            ),
            command = cfg.lastCommandJson?.let {
                runCatching { gson.fromJson(it, CommandStatus::class.java) }.getOrNull()
            },
            battery_temp_c = batteryTempC()
        )
        m.publish(Topics.STATUS, PagerJson.diagnostics(d), retained = true)
    }

    /**
     * Reconnect watchdog for BOTH roles: verifies the link is alive and recreates
     * the client if Paho's automaticReconnect has given up. It does NOT publish
     * status — that is the separate, admin-configurable status timer below.
     */
    private fun startWatchdog() {
        stopWatchdog()
        val r = object : Runnable {
            override fun run() {
                executor.execute {
                    runCatching { ensureConnected() }
                        .onFailure { Log.w(TAG, "watchdog reconnect failed", it) }
                }
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
        watchdog = r
        handler.postDelayed(r, WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    /**
     * Publish diagnostics on the admin-configured cadence (default 15 min). The
     * MQTT keepalive — not this timer — is what keeps the socket alive, and
     * event-driven publishes (connect/show/clear/apply) still happen immediately.
     */
    private fun startStatusTimer() {
        stopStatusTimer()
        if (cfg.role != Role.RECEIVER) return
        val intervalMs = cfg.remoteConfig().telemetry.interval_minutes.coerceIn(1, 1440) * 60_000L
        val r = object : Runnable {
            override fun run() {
                executor.execute { runCatching { publishStatus() } }
                handler.postDelayed(this, intervalMs)
            }
        }
        statusTimer = r
        handler.postDelayed(r, intervalMs)
        Log.i(TAG, "status interval = ${intervalMs / 60_000L} min")
    }

    private fun stopStatusTimer() {
        statusTimer?.let { handler.removeCallbacks(it) }
        statusTimer = null
    }

    // --------------------------------------------------------------- power

    /**
     * Keep the CPU and Wi-Fi radio awake for the whole lifetime of the service so
     * the TLS socket stays up and a new message can be delivered immediately even
     * while the display is asleep. The display itself is allowed to sleep (see
     * ReceiverActivity); only the screen backlight is the big draw.
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grandpa:pager").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { Log.w(TAG, "wake lock failed", it) }
        }
        if (wifiLock == null) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "grandpa:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { Log.w(TAG, "wifi lock failed", it) }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
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

    /** Battery temperature in °C; BatteryManager reports tenths of a degree. */
    private fun batteryTempC(): Double? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
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
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                // HIGH is required for a full-screen intent to wake the screen.
                // Silent + no vibration: the app plays its own arrangement cue.
                val ch = NotificationChannel(ALERT_CHANNEL_ID, "Message alert", NotificationManager.IMPORTANCE_HIGH)
                ch.setShowBadge(false)
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * Post (or refresh) the full-screen-intent notification that wakes the screen
     * and brings ReceiverActivity to the front for a new message. This is the
     * system-sanctioned way to turn the display on from the background.
     */
    private fun showWakeNotification(msg: MessagePayload) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, ReceiverActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 3001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        val n = builder
            .setContentTitle(msg.sender.ifBlank { getString(R.string.app_name) })
            .setContentText(msg.text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ALERT_NOTIF_ID, n) }
            .onFailure { Log.w(TAG, "wake notification failed", it) }
    }

    private fun cancelWakeNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(ALERT_NOTIF_ID) }
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
