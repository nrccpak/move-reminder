package com.fiaz.movereminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOOD = "com.fiaz.movereminder.STOOD"
        const val ACTION_MEETING_60 = "com.fiaz.movereminder.MEETING_60"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOOD -> Engine.manualStood(ctx)
            ACTION_MEETING_60 -> {
                Engine.startMeeting(ctx, 60)
                Notifier.cancelNudge(ctx)
            }
        }
    }
}
