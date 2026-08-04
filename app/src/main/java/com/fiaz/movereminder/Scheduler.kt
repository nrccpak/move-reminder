package com.fiaz.movereminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * setAndAllowWhileIdle is inexact and needs no special permission. In Doze it
 * may slip to roughly 9 minutes - which is fine for a 25 minute threshold and
 * costs far less battery than an exact alarm.
 */
object Scheduler {
    const val INTERVAL_MS = 5 * 60 * 1000L

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 100, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + INTERVAL_MS,
            pi(ctx)
        )
    }

    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx))
        } catch (e: Exception) { }
    }
}
