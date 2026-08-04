package com.fiaz.movereminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_STATUS = "status"
    const val CH_NUDGE = "nudge"
    const val ID_STATUS = 1
    const val ID_NUDGE = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Silent, collapsed - this one only exists to keep the service alive.
        val status = NotificationChannel(CH_STATUS, "Tracking", NotificationManager.IMPORTANCE_MIN)
        status.setShowBadge(false)
        nm.createNotificationChannel(status)

        val nudge = NotificationChannel(CH_NUDGE, "Move reminder", NotificationManager.IMPORTANCE_HIGH)
        nudge.enableVibration(true)
        nudge.description = "Fires when you have been sitting too long"
        nm.createNotificationChannel(nudge)
    }

    fun statusNotification(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mode = Prefs.mode(ctx)
        val text = if (mode == Prefs.MODE_MEETING) {
            val left = (Prefs.meetingUntil(ctx) - System.currentTimeMillis()) / 60000L
            "Meeting mode - ${if (left > 0) left else 0} min left"
        } else {
            "Sitting ${Engine.sittingMinutes(ctx)} min"
        }
        return Notification.Builder(ctx, CH_STATUS)
            .setContentTitle("Move Reminder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    fun nudge(ctx: Context, sittingMin: Int, count: Int = 1) {
        val stood = PendingIntent.getBroadcast(
            ctx, 10,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_STOOD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val meeting = PendingIntent.getBroadcast(
            ctx, 11,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_MEETING_60),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (count > 1) "Still sitting - reminder #$count" else "Time to stand up"
        val n = Notification.Builder(ctx, CH_NUDGE)
            .setContentTitle(title)
            .setContentText("You have been sitting for $sittingMin minutes")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_directions, "I stood up", stood)
            .addAction(android.R.drawable.ic_menu_recent_history, "In a meeting (60m)", meeting)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_NUDGE, n)
    }

    fun cancelNudge(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_NUDGE)
        } catch (e: Exception) { }
    }
}
