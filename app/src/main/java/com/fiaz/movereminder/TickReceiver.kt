package com.fiaz.movereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.isRunning(ctx)) return
        Scheduler.scheduleNext(ctx)
        val pending = goAsync()
        Engine.tick(ctx) { pending.finish() }
    }
}
