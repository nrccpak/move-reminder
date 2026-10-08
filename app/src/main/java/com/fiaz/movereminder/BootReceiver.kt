package com.fiaz.movereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts tracking after a phone reboot and after the app is updated
 * (installing a new APK cancels the alarm and stops the service).
 * The sitting clock is not reset; the step counter restart after a reboot is
 * detected by Engine and re-baselined.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone restarted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        if (!Prefs.isRunning(ctx)) return
        BoutLog.debug(ctx, "$why -> tracking resumed")
        SedentaryService.start(ctx)
    }
}
