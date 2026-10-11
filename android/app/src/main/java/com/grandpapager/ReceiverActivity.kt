package com.grandpapager

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.pm.ActivityInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.ArrayDeque

/**
 * The screen Grandpa sees (variant C):
 *  - active message: sender name centred above the message, countdown below;
 *    a NEW message fires a one-shot attention cue — a slow amber glow (≈6 s),
 *    a notification chime and a double vibration — then settles. The same
 *    message is never re-cued.
 *  - idle: one-row date (UPPERCASE weekday + dd/mm/yyyy) with a clock 30 %
 *    larger below; a calm "Probleme cu conexiunea" line appears when the link
 *    is down. Never interactive.
 */
class ReceiverActivity : Activity() {

    private lateinit var cfg: Config
    private val handler = Handler(Looper.getMainLooper())
    private val busListener = Runnable { refresh() }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var nameView: TextView
    private lateinit var messageView: TextView
    private lateinit var dateView: TextView
    private lateinit var clockView: TextView
    private lateinit var warnView: TextView
    private lateinit var countdownView: TextView
    private lateinit var glowOverlay: View
    private lateinit var area: View
    private lateinit var topRow: View
    private lateinit var scrollColumn: View
    private lateinit var scrollUp: View
    private lateinit var scrollDown: View

    /** True while the message is too tall for the screen and scrolls instead. */
    private var messageScrollEnabled = false
    private var configApplied = false
    private var lastConfigJson: String? = null

    private var clearRequested = false
    private var lastCuedId: String? = null
    private var screenSlept = false
    private var glowAnim: ValueAnimator? = null
    private var releaseReceiver: BroadcastReceiver? = null
    private val cornerTaps = ArrayDeque<Long>()
    private var torchCameraId: String? = null
    private var torchRequested = false
    private val torchHandler = Handler(Looper.getMainLooper())
    private var appliedOrientation: Int? = null
    private var appliedDeviceOwner: RemoteConfig.DeviceOwner? = null

    /**
     * Admin escape hatch: five taps in the bottom-left corner switch to the
     * original launcher (exits lock task so the admin can use the phone).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP) {
            val w = window.decorView.width
            val h = window.decorView.height
            if (w > 0 && h > 0 && ev.x < w * 0.22f && ev.y > h * 0.78f) {
                val now = SystemClock.elapsedRealtime()
                cornerTaps.addLast(now)
                while (cornerTaps.isNotEmpty() && now - cornerTaps.first() > 6000) cornerTaps.removeFirst()
                if (cornerTaps.size >= 5) {
                    cornerTaps.clear()
                    switchToLauncher()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun switchToLauncher() {
        Log.i(TAG, "admin gesture: switching to launcher")
        runCatching { stopLockTask() }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = packageManager.queryIntentActivities(homeIntent, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        val launch = if (target != null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(target.activityInfo.packageName, target.activityInfo.name))
        } else homeIntent
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        setContentView(R.layout.activity_receiver)
        nameView = findViewById(R.id.nameView)
        messageView = findViewById(R.id.messageView)
        dateView = findViewById(R.id.dateView)
        clockView = findViewById(R.id.clockView)
        warnView = findViewById(R.id.warnView)
        countdownView = findViewById(R.id.countdownView)
        glowOverlay = findViewById(R.id.glowOverlay)
        area = findViewById(R.id.area)
        topRow = findViewById(R.id.topRow)
        scrollColumn = findViewById(R.id.scrollColumn)
        scrollUp = findViewById(R.id.scrollUp)
        scrollDown = findViewById(R.id.scrollDown)
        // Arrow taps page the message; a layout change re-checks whether the
        // text still overflows (the readable floor changed with the text).
        scrollUp.setOnClickListener { messageView.scrollBy(0, -scrollStep()) }
        scrollDown.setOnClickListener { messageView.scrollBy(0, scrollStep()) }
        messageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refreshMessageScroll()
        }
        // Let a new message be shown and turn the display on over a sleeping /
        // locked screen. The manifest carries the API-27+ attributes; these are
        // the legacy window-flag equivalents for older devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        lastCuedId = cfg.lastCuedMessageId

        releaseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = releaseKiosk()
        }
        registerReceiver(releaseReceiver, IntentFilter(ACTION_RELEASE_KIOSK))

        if (cfg.role == Role.RECEIVER) {
            ensureBatteryExemption()
            if (DeviceOwnerManager.isDeviceOwner(this)) {
                DeviceOwnerManager.applyKiosk(this)
                DeviceOwnerManager.enterLockTask(this)
            }
            PagerService.start(this)
        }
    }

    /**
     * Battery-optimization exemption is what keeps the socket from being frozen
     * by Doze/App Standby when the phone is on battery. Stock Android 9 has no
     * device-owner API to grant it silently, so: warn loudly, and (outside kiosk
     * lock task) ask once via the system dialog. The deterministic path is the
     * documented `adb shell dumpsys deviceidle whitelist +<pkg>`.
     */
    private fun ensureBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Log.w(
            TAG,
            "battery exemption NOT granted — Doze may delay messages on battery; " +
                "grant via: adb shell dumpsys deviceidle whitelist +$packageName"
        )
        // In a device-owner kiosk, don't pop a system dialog; the operator grants
        // it per the runbook. As a normal app, ask once via the setup dialog.
        if (DeviceOwnerManager.isDeviceOwner(this)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        if (cfg.batteryExemptionAsked) return
        cfg.batteryExemptionAsked = true
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure { Log.w(TAG, "battery exemption request failed", it) }
    }

    override fun onResume() {
        super.onResume()
        PagerBus.addListener(busListener)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        PagerBus.removeListener(busListener)
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        glowAnim?.cancel()
        torchHandler.removeCallbacksAndMessages(null)
        setTorch(false)
        releaseReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun refresh() {
        // Re-applying the config rebuilds the text layout (and setting the same
        // text resets scroll), which fights scrolling. Only apply on a change.
        val configJson = cfg.remoteConfigJson
        if (!configApplied || configJson != lastConfigJson) {
            configApplied = true
            lastConfigJson = configJson
            applyRemoteConfig()
        }
        val rc = cfg.remoteConfig()
        if (cfg.hasActiveMessage()) {
            screenSlept = false
            keepScreenOn(true)
            val id = cfg.currentMessageId
            if (id != null && id != lastCuedId) {
                lastCuedId = id
                cfg.lastCuedMessageId = id
                playArrivalCue()
            }
            clearRequested = false
            dateView.visibility = View.GONE
            clockView.visibility = View.GONE
            topRow.visibility = View.VISIBLE
            nameView.visibility = View.VISIBLE
            val sender = cfg.currentSender ?: ""
            if (nameView.text.toString() != sender) nameView.text = sender
            messageView.visibility = View.VISIBLE
            // Only touch the text when it changed: setText() resets the scroll,
            // so a periodic refresh must not clobber a scrolled position.
            val text = cfg.currentMessageText ?: ""
            if (messageView.text.toString() != text) {
                messageView.text = text
                messageView.scrollTo(0, 0)
            }
            // Re-evaluate after the pending layout: the view's bounds may not
            // change when only its text does, so onLayoutChange alone misses it.
            messageView.post { refreshMessageScroll() }
            warnView.visibility = View.GONE
            // "Received at … , expires at …" — shown only when the admin turns it on.
            val receivedAt = if (cfg.currentReceivedAt > 0) cfg.currentReceivedAt else System.currentTimeMillis()
            countdownView.text = getString(
                R.string.msg_received_expires,
                DateLabels.timeLine(this, receivedAt),
                DateLabels.timeLine(this, cfg.currentExpiresAt)
            )
            countdownView.visibility = if (rc.display.countdown) View.VISIBLE else View.GONE
        } else {
            if (cfg.currentMessageId != null && !clearRequested) {
                clearRequested = true
                PagerService.clear(this)
            }
            topRow.visibility = View.GONE
            nameView.visibility = View.GONE
            messageView.visibility = View.GONE
            messageView.setMovementMethod(null)
            messageScrollEnabled = false
            scrollColumn.visibility = View.GONE
            countdownView.text = ""
            countdownView.visibility = View.GONE
            // Only sleep the display when we can actually do it (device owner);
            // otherwise fall back to the idle clock, never a stuck black screen.
            val canSleep = rc.power.screen_off_when_idle && DeviceOwnerManager.isDeviceOwner(this)
            if (canSleep) {
                dateView.visibility = View.GONE
                clockView.visibility = View.GONE
                warnView.visibility = View.GONE
                keepScreenOn(false)
                if (!screenSlept) {
                    screenSlept = true
                    sleepDisplay()
                }
            } else {
                // Clock mode: keep the display lit showing date + clock.
                keepScreenOn(true)
                dateView.visibility = View.VISIBLE
                clockView.visibility = View.VISIBLE
                sizeIdleViews(System.currentTimeMillis(), rc.display.idle_clock_scale, rc.display.idle_date_format)
                if (Link.connected) {
                    warnView.visibility = View.GONE
                } else {
                    warnView.visibility = View.VISIBLE
                    warnView.text = rc.display.warn_text ?: getString(R.string.conn_problem)
                }
            }
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Device owner: put the display to sleep (no keyguard; the kiosk stays front). */
    private fun sleepDisplay() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        runCatching { dpm.lockNow() }
            .onFailure { Log.w(TAG, "lockNow failed", it) }
    }

    /**
     * Size the idle date to fit on ONE row, then set the clock 30 % larger.
     * Runs after layout so the available width is known.
     */
    private fun sizeIdleViews(now: Long, clockScale: Float, dateFormat: String) {
        area.post {
            val availW = (area.width - area.paddingLeft - area.paddingRight).toFloat()
            val availH = (area.height - area.paddingTop - area.paddingBottom).toFloat()
            if (availW <= 0f) return@post
            val date = DateLabels.dateLine(this, now, dateFormat)
            val time = DateLabels.timeLine(this, now)
            val paint = Paint(dateView.paint)
            // Date and clock must share: date(1.0) + clock(scale) with line spacing.
            val scale = clockScale.coerceIn(1.0f, 3.0f)
            val maxByHeight = availH / (2.0f + scale)
            var lo = 12f
            var hi = maxByHeight
            repeat(24) {
                val mid = (lo + hi) / 2f
                paint.textSize = mid
                if (paint.measureText(date) <= availW) lo = mid else hi = mid
            }
            val datePx = lo
            dateView.text = date
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, datePx)
            clockView.text = time
            clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, datePx * scale)
        }
    }

    /** How far one arrow tap pages: roughly half a screen. */
    private fun scrollStep(): Int = (messageView.height / 2).coerceAtLeast(1)

    /**
     * A message that fits is drawn centred and non-interactive. When even the
     * smallest readable size (half the name size) cannot fit, flip to a
     * top-aligned, vertically scrollable TextView and reveal the arrow gutter.
     * Idempotent: only acts on a state change, so it is safe from layout.
     */
    private fun refreshMessageScroll() {
        if (messageView.visibility != View.VISIBLE) return
        val layout = messageView.layout ?: return
        val contentH = messageView.height - messageView.paddingTop - messageView.paddingBottom
        if (contentH <= 0) return
        val overflows = layout.height > contentH + 1
        if (overflows == messageScrollEnabled) return
        messageScrollEnabled = overflows
        if (overflows) {
            messageView.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            messageView.setMovementMethod(ScrollingMovementMethod.getInstance())
            messageView.isVerticalScrollBarEnabled = true
            messageView.scrollTo(0, 0)
            scrollColumn.visibility = View.VISIBLE
        } else {
            messageView.scrollTo(0, 0)
            messageView.setMovementMethod(null)
            messageView.isVerticalScrollBarEnabled = false
            messageView.gravity = Gravity.CENTER
            scrollColumn.visibility = View.GONE
        }
    }

    /** Apply the admin remote config (display, orientation, kiosk). */
    private fun applyRemoteConfig() {
        val rc = cfg.remoteConfig()
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, rc.display.name_size_sp.toFloat())
        nameView.setTextColor(Color.parseColor(rc.display.name_color))
        messageView.setTextColor(Color.parseColor(rc.display.message_color))
        warnView.setTextColor(Color.parseColor(rc.display.warn_color))
        countdownView.setTextColor(Color.parseColor(rc.display.countdown_color))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Never shrink a message below a readable floor: half the name size.
            // Below that we scroll instead (see refreshMessageScroll).
            val readableMin = (rc.display.name_size_sp * 0.5f).toInt()
            val minSp = maxOf(rc.display.message_size_min_sp, readableMin).coerceAtLeast(8)
            val maxSp = maxOf(rc.display.message_size_max_sp, minSp)
            messageView.setAutoSizeTextTypeUniformWithConfiguration(
                minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        val orient = when (rc.display.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (orient != appliedOrientation) {
            appliedOrientation = orient
            runCatching { requestedOrientation = orient }
        }
        if (DeviceOwnerManager.isDeviceOwner(this)) {
            if (appliedDeviceOwner != rc.device_owner) {
                appliedDeviceOwner = rc.device_owner
                DeviceOwnerManager.applyPolicy(this, rc.device_owner)
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val inLock = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            when {
                rc.device_owner.lock_task && rc.kiosk.locked && !inLock -> runCatching { startLockTask() }
                (!rc.kiosk.locked || !rc.device_owner.lock_task) && inLock -> switchToLauncher()
            }
        }
    }

    /** One-shot cue: glow, chime, vibration, torch — per the remote config. */
    private fun playArrivalCue() {
        val rc = cfg.remoteConfig()
        glowAnim?.cancel()
        glowOverlay.alpha = 0f
        if (rc.cue.glow_ms > 0) {
            glowAnim = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f, 1f, 0f).apply {
                duration = rc.cue.glow_ms.toLong()
                addUpdateListener { glowOverlay.alpha = it.animatedValue as Float }
                start()
            }
        }
        if (rc.cue.chime) runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        }
        if (rc.cue.vibrate) runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0L, 150L, 120L, 150L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        }
        if (rc.cue.torch.enabled) playTorchCue(rc.cue.torch)
    }

    /* ---- Torch/flash signal (binary on/off — a smooth fade isn't possible
       before API 33). Slow 3× pulse as an across-the-room cue. No permission. ---- */
    private fun cameraManager(): CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun torchId(): String? {
        torchCameraId?.let { return it }
        return runCatching {
            val cm = cameraManager()
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it }
        }.getOrNull()
    }

    private fun setTorch(on: Boolean) {
        val id = torchId() ?: run { Log.w(TAG, "no torch camera"); return }
        runCatching {
            cameraManager().setTorchMode(id, on)
            Log.i(TAG, "torch($on) ok on camera $id")
        }.onFailure { Log.w(TAG, "torch($on) failed: ${it.message}") }
    }

    private fun playTorchCue(t: RemoteConfig.Torch) {
        torchHandler.removeCallbacksAndMessages(null)
        val onMs = t.on_ms.toLong(); val offMs = t.off_ms.toLong(); val pulses = t.pulses
        val run = object : Runnable {
            var count = 0
            override fun run() {
                setTorch(true)
                torchHandler.postDelayed({
                    setTorch(false)
                    count++
                    if (count < pulses) torchHandler.postDelayed(this, offMs)
                }, onMs)
            }
        }
        torchRequested = true
        torchHandler.post(run)
    }

    /** Un-whitelist, restore the status bar, drop persistent home, clear DO. */
    private fun releaseKiosk() {
        runCatching { stopLockTask() }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, PagerDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, packageName) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { dpm.setStatusBarDisabled(admin, false) }
            }
            DeviceOwnerManager.clearRestrictions(this)
            runCatching { dpm.clearDeviceOwnerApp(packageName) }
        }
    }

    companion object {
        const val ACTION_RELEASE_KIOSK = "com.grandpapager.RELEASE_KIOSK"
        private const val TAG = "ReceiverActivity"
    }
}
