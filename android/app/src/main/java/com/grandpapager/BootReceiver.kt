package com.grandpapager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-arms the pager after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cfg = Config(context)
        if (!cfg.isConfigured()) return
        Log.i("BootReceiver", "boot event ${intent?.action}; starting PagerService")
        runCatching { PagerService.start(context) }
    }
}
