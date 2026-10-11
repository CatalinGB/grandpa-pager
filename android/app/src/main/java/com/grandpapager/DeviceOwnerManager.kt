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

    /** Configure lock task + persistent home. Safe to call repeatedly. */
    fun applyKiosk(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.i(TAG, "not device owner; skipping kiosk configuration")
            return
        }
        val d = dpm(context)
        val a = admin(context)
        runCatching {
            d.setLockTaskPackages(a, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Remove power menu / status bar / notifications / recents from lock task.
                d.setLockTaskFeatures(a, 0)
            }
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(
                a, homeFilter, ComponentName(context, ReceiverActivity::class.java)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.setStatusBarDisabled(a, true)
            }
            Log.i(TAG, "kiosk configured (lock task + persistent home)")
        }.onFailure { Log.e(TAG, "kiosk configuration failed", it) }
        applyRestrictions(context)
    }

    /** Enter lock task (true kiosk when device owner, screen pinning otherwise). */
    fun enterLockTask(activity: Activity) {
        runCatching {
            activity.startLockTask()
            Log.i(TAG, "startLockTask() called")
        }.onFailure { Log.w(TAG, "startLockTask failed", it) }
    }

    /** The restrictions we add, so apply/clear stay in sync. */
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_MODIFY_ACCOUNTS,        // no adding/removing accounts
        UserManager.DISALLOW_BLUETOOTH,              // no Bluetooth (fewer radios/attack surface)
        UserManager.DISALLOW_SHARE_LOCATION,         // no location sharing
        UserManager.DISALLOW_CONFIG_LOCATION,        // cannot toggle location providers
        UserManager.DISALLOW_PRINTING,               // no printing services
        UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, // no mobile-network config
        UserManager.DISALLOW_CONFIG_TETHERING,       // no tethering
        UserManager.DISALLOW_DATA_ROAMING            // no roaming
    )

    /**
     * Strip non-essential functionality on an always-on pager: no account changes,
     * Bluetooth, location, printing or mobile-network config, and auto-sync off.
     * Purely reversible — see [clearRestrictions]. Safe to call repeatedly.
     */
    fun applyRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        val d = dpm(context)
        val a = admin(context)
        for (r in RESTRICTIONS) runCatching { d.addUserRestriction(a, r) }
        runCatching { ContentResolver.setMasterSyncAutomatically(false) }
        runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            @Suppress("DEPRECATION", "MissingPermission") bm.adapter?.disable()
        }
        Log.i(TAG, "device restrictions applied (${RESTRICTIONS.size})")
    }

    /** Undo [applyRestrictions] (used by the RELEASE_KIOSK escape hatch). */
    fun clearRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        val d = dpm(context)
        val a = admin(context)
        for (r in RESTRICTIONS) runCatching { d.clearUserRestriction(a, r) }
        runCatching { ContentResolver.setMasterSyncAutomatically(true) }
        Log.i(TAG, "device restrictions cleared")
    }
}
