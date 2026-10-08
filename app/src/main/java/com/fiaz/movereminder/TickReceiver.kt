package com.fiaz.movereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.isRunning(ctx)) return
        // Safety net first, so the chain never breaks even if this tick fails.
        Scheduler.scheduleFallback(ctx)
        // If One UI killed the service, bring it back (allowed from an exact alarm).
        if (!SedentaryService.alive) SedentaryService.revive(ctx)
        val pending = goAsync()
        Engine.tick(ctx) { pending.finish() }
    }
}
