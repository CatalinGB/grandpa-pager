package com.grandpapager

import android.animation.ValueAnimator
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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

    private var clearRequested = false
    private var lastCuedId: String? = null
    private var glowAnim: ValueAnimator? = null
    private var releaseReceiver: BroadcastReceiver? = null
    private val cornerTaps = ArrayDeque<Long>()

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastCuedId = cfg.lastCuedMessageId

        releaseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = releaseKiosk()
        }
        registerReceiver(releaseReceiver, IntentFilter(ACTION_RELEASE_KIOSK))

        if (cfg.role == Role.RECEIVER) {
            if (DeviceOwnerManager.isDeviceOwner(this)) {
                DeviceOwnerManager.applyKiosk(this)
                DeviceOwnerManager.enterLockTask(this)
            }
            PagerService.start(this)
        }
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
        releaseReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun refresh() {
        if (cfg.hasActiveMessage()) {
            val id = cfg.currentMessageId
            if (id != null && id != lastCuedId) {
                lastCuedId = id
                cfg.lastCuedMessageId = id
                playArrivalCue()
            }
            clearRequested = false
            dateView.visibility = View.GONE
            clockView.visibility = View.GONE
            nameView.visibility = View.VISIBLE
            nameView.text = cfg.currentSender ?: ""
            messageView.visibility = View.VISIBLE
            messageView.gravity = Gravity.CENTER
            messageView.text = cfg.currentMessageText ?: ""
            warnView.visibility = View.GONE
            // The "clears in …" countdown is a dev-only aid; production hides it.
            countdownView.text = getString(R.string.clears_in, remainingLabel())
            countdownView.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        } else {
            if (cfg.currentMessageId != null && !clearRequested) {
                clearRequested = true
                PagerService.clear(this)
            }
            nameView.visibility = View.GONE
            messageView.visibility = View.GONE
            dateView.visibility = View.VISIBLE
            clockView.visibility = View.VISIBLE
            sizeIdleViews(System.currentTimeMillis())
            if (Link.connected) {
                warnView.visibility = View.GONE
            } else {
                warnView.visibility = View.VISIBLE
                warnView.text = getString(R.string.conn_problem)
            }
            countdownView.text = ""
            countdownView.visibility = View.GONE
        }
    }

    /**
     * Size the idle date to fit on ONE row, then set the clock 30 % larger.
     * Runs after layout so the available width is known.
     */
    private fun sizeIdleViews(now: Long) {
        area.post {
            val availW = (area.width - area.paddingLeft - area.paddingRight).toFloat()
            val availH = (area.height - area.paddingTop - area.paddingBottom).toFloat()
            if (availW <= 0f) return@post
            val date = DateLabels.dateLine(this, now)
            val time = DateLabels.timeLine(this, now)
            val paint = Paint(dateView.paint)
            // Date and clock must share: date(1.0) + clock(1.3) with line spacing.
            val maxByHeight = availH / 3.0f
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
            clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, datePx * 1.3f)
        }
    }

    /** One-shot tri-modal cue: slow glow, a chime, a double vibration. */
    private fun playArrivalCue() {
        glowAnim?.cancel()
        glowOverlay.alpha = 0f
        glowAnim = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f, 1f, 0f).apply {
            duration = 6000L
            addUpdateListener { glowOverlay.alpha = it.animatedValue as Float }
            start()
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        }
        runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0L, 150L, 120L, 150L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        }
    }

    private fun remainingLabel(): String {
        val rem = Expiry.remaining(cfg.currentExpiresAt, System.currentTimeMillis())
        val hours = rem / 3_600_000L
        val minutes = (rem % 3_600_000L) / 60_000L
        return when {
            hours > 0 -> getString(R.string.duration_hm, hours, minutes)
            minutes > 0 -> getString(R.string.duration_m, minutes)
            else -> getString(R.string.duration_lt_min)
        }
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
            runCatching { dpm.clearDeviceOwnerApp(packageName) }
        }
    }

    companion object {
        const val ACTION_RELEASE_KIOSK = "com.grandpapager.RELEASE_KIOSK"
        private const val TAG = "ReceiverActivity"
    }
}
