package com.fiaz.movereminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * One alarm, always replaced by the latest schedule. Exact + allow-while-idle so a
 * reminder lands on time even while the phone lies still on the desk (Doze).
 * The exact-alarm permission (USE_EXACT_ALARM) is granted automatically to this
 * app because it is not distributed through Play. Falls back to an inexact alarm
 * if exact alarms are not allowed.
 */
object Scheduler {

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 100, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleAt(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }
        Prefs.get(ctx).edit().putLong(Prefs.KEY_NEXT_WAKE, at).apply()
    }

    /** Safety net: a check in 5 minutes, replaced by the real schedule after each tick. */
    fun scheduleFallback(ctx: Context) {
        scheduleAt(ctx, System.currentTimeMillis() + Rules.CHECK_MS)
    }

    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx))
        } catch (e: Exception) { }
    }
}
