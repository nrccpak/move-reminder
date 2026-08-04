package com.fiaz.movereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isRunning(ctx)) return
        // Step counter resets to zero on reboot - Engine detects this and re-anchors.
        BoutLog.debug(ctx, "boot completed -> restarting service")
        SedentaryService.start(ctx)
    }
}
