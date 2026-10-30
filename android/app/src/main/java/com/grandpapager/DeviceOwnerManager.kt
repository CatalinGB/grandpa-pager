package com.grandpapager

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Kiosk configuration that requires device-owner rights. Everything here is a
 * no-op when the app is not device owner, so the app also runs as a normal app
 * for testing.
 */
object DeviceOwnerManager {

    private const val TAG = "DeviceOwnerManager"

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context): ComponentName =
        ComponentName(context, PagerDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /**
     * Reboot the phone. Requires device owner (API 24+). Returns false instead of
     * throwing when the app is not device owner, so callers can log it.
     *
     * Deliberately network-free: the maintenance supervisor uses this so a pager
     * that can no longer be reached over MQTT can still reboot itself.
     */
    fun reboot(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return runCatching { dpm(context).reboot(admin(context)) }
            .onFailure { Log.w(TAG, "reboot failed", it) }
            .isSuccess
    }

    /** Map a pure restriction key to the Android constants that enforce it. */
    private val RESTRICTION_CONSTS: Map<String, List<String>> = mapOf(
        RestrictionPlan.ACCOUNTS to listOf(UserManager.DISALLOW_MODIFY_ACCOUNTS),
        RestrictionPlan.BLUETOOTH to listOf(UserManager.DISALLOW_BLUETOOTH),
        RestrictionPlan.LOCATION to listOf(
            UserManager.DISALLOW_SHARE_LOCATION,
            UserManager.DISALLOW_CONFIG_LOCATION
        ),
        RestrictionPlan.PRINTING to listOf(UserManager.DISALLOW_PRINTING),
        RestrictionPlan.MOBILE_NETWORKS to listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS),
        RestrictionPlan.TETHERING to listOf(UserManager.DISALLOW_CONFIG_TETHERING),
        RestrictionPlan.ROAMING to listOf(UserManager.DISALLOW_DATA_ROAMING)
    )

    /**
     * One-time setup: make the receiver the persistent HOME, then apply the
     * config-driven policy. Safe to call repeatedly; no-op without device-owner.
     */
    fun applyKiosk(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.i(TAG, "not device owner; skipping kiosk configuration")
            return
        }
        val d = dpm(context)
        val a = admin(context)
        runCatching {
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(
                a, homeFilter, ComponentName(context, ReceiverActivity::class.java)
            )
            Log.i(TAG, "kiosk configured (persistent home)")
        }.onFailure { Log.e(TAG, "kiosk configuration failed", it) }
        applyPolicy(context, Config(context).remoteConfig().device_owner)
    }

    /**
     * Apply the admin's device-owner policy: lock-task packages/features, the
     * status bar, and the user restrictions. Configuration-driven so a remote
     * change tightens or relaxes the device without physical access.
     */
    fun applyPolicy(context: Context, doo: RemoteConfig.DeviceOwner) {
        if (!isDeviceOwner(context)) return
        val d = dpm(context)
        val a = admin(context)
        runCatching {
            if (doo.lock_task) {
                d.setLockTaskPackages(a, arrayOf(context.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Remove power menu / status bar / notifications / recents.
                    d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                }
            } else {
                d.setLockTaskPackages(a, emptyArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.setStatusBarDisabled(a, doo.status_bar_disabled)
            }
        }.onFailure { Log.e(TAG, "device policy apply failed", it) }
        applyRestrictions(context, doo)
    }

    /** Enter lock task (true kiosk when device owner, screen pinning otherwise). */
    fun enterLockTask(activity: Activity) {
        runCatching {
            activity.startLockTask()
            Log.i(TAG, "startLockTask() called")
        }.onFailure { Log.w(TAG, "startLockTask failed", it) }
    }

    /**
     * Enforce exactly the configured restriction set: add the desired ones and
     * clear the rest, so relaxing a toggle remotely actually takes effect.
     * Purely reversible — see [clearRestrictions]. Safe to call repeatedly.
     */
    fun applyRestrictions(context: Context, doo: RemoteConfig.DeviceOwner) {
        if (!isDeviceOwner(context)) return
        val desired = RestrictionPlan.desired(doo)
        val d = dpm(context)
        val a = admin(context)
        for ((key, consts) in RESTRICTION_CONSTS) {
            val enforce = key in desired
            for (c in consts) runCatching {
                if (enforce) d.addUserRestriction(a, c) else d.clearUserRestriction(a, c)
            }
        }
        runCatching { ContentResolver.setMasterSyncAutomatically(!doo.restrict_sync) }
        setBluetoothRadio(context, enabled = !doo.restrict_bluetooth)
        Log.i(TAG, "device restrictions applied: $desired")
    }

    /** Undo [applyRestrictions] (used by the RELEASE_KIOSK escape hatch). */
    fun clearRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        val d = dpm(context)
        val a = admin(context)
        for (consts in RESTRICTION_CONSTS.values) {
            for (c in consts) runCatching { d.clearUserRestriction(a, c) }
        }
        runCatching { ContentResolver.setMasterSyncAutomatically(true) }
        setBluetoothRadio(context, enabled = true)
        Log.i(TAG, "device restrictions cleared")
    }

    /**
     * Bring the Bluetooth radio in line with the `restrict_bluetooth` toggle, in
     * both directions — enforcing it disables the radio, relaxing it re-enables
     * it, so a remote toggle-off actually takes effect (architecture.md, D14).
     * `enable()`/`disable()` are deprecated on API 33+ but are the supported,
     * permission-gated path on this device's Android 9; best-effort either way.
     */
    private fun setBluetoothRadio(context: Context, enabled: Boolean) {
        runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            @Suppress("DEPRECATION", "MissingPermission")
            val adapter = bm.adapter ?: return@runCatching
            if (enabled) adapter.enable() else adapter.disable()
        }.onFailure { Log.w(TAG, "bluetooth ${if (enabled) "enable" else "disable"} failed", it) }
    }
}
