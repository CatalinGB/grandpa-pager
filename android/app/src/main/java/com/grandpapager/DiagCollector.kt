package com.grandpapager

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * Collects runtime health for the status payload, so a pager that cannot be
 * touched can still be diagnosed from the dashboard.
 *
 * Everything is best-effort: a metric the platform refuses to give is simply
 * null (and omitted from the JSON), never an exception that could break the
 * status publish.
 */
object DiagCollector {
    private const val TAG = "DiagCollector"
    private const val CRASH_FILE = "last_crash.txt"

    /** Called from the uncaught-exception handler: must not throw, must be quick. */
    fun recordCrash(context: Context, t: Throwable) {
        val text = TimeUtil.nowIso() + "\n" + t.javaClass.name + "\n" + (t.message ?: "")
        runCatching { File(context.filesDir, CRASH_FILE).writeText(text) }
            .onFailure { Log.w(TAG, "could not persist the crash", it) }
    }

    fun lastCrash(context: Context): CrashInfo? {
        val f = File(context.filesDir, CRASH_FILE)
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        return CrashInfo(
            at = lines[0].ifBlank { TimeUtil.nowIso() },
            type = DiagFormat.clip(lines.getOrNull(1), 80),
            message = DiagFormat.clip(lines.getOrNull(2), DiagFormat.MAX_MESSAGE)
        )
    }

    fun collect(context: Context, cfg: Config, nowMs: Long): DiagStatus {
        val rt = Runtime.getRuntime()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val wifiRssi = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return@runCatching null
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo ?: return@runCatching null
            if (info.networkId == -1) null else info.rssi
        }.getOrNull()?.takeIf { it in -120..0 }

        return DiagStatus(
            heap_used_kb = (rt.totalMemory() - rt.freeMemory()) / 1024,
            heap_max_kb = rt.maxMemory() / 1024,
            native_heap_kb = runCatching { Debug.getNativeHeapAllocatedSize() / 1024 }.getOrNull(),
            threads = runCatching { Thread.activeCount() }.getOrNull(),
            cpu_ms = runCatching { android.os.Process.getElapsedCpuTime() }.getOrNull(),
            free_storage_kb = runCatching {
                val fs = StatFs(context.filesDir.absolutePath)
                fs.availableBytes / 1024
            }.getOrNull(),
            ignoring_battery_optimizations = runCatching {
                pm?.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrNull(),
            device_idle = runCatching { pm?.isDeviceIdleMode }.getOrNull(),
            power_save = runCatching { pm?.isPowerSaveMode }.getOrNull(),
            interactive = runCatching { pm?.isInteractive }.getOrNull(),
            lock_task = runCatching {
                am != null && am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            }.getOrNull(),
            wifi_rssi = wifiRssi,
            last_crash = lastCrash(context),
            last_disconnect = if (cfg.lastDisconnectAt > 0L)
                DisconnectInfo(TimeUtil.toIso(cfg.lastDisconnectAt), cfg.lastDisconnectReason) else null,
            connect_count = cfg.connectCount,
            disconnect_count = cfg.disconnectCount,
            last_publish_ok_at = if (cfg.lastPublishOkAt > 0L) TimeUtil.toIso(cfg.lastPublishOkAt) else null,
            service_start_count = cfg.serviceStartCount,
            last_start_reason = cfg.lastStartReason,
            reboot_backoff = SupervisorStore.readState(context).backoffAfter(cfg.lastPublishAt)
        )
    }
}
