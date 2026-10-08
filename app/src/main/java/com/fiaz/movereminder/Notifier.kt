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

        // Silent, collapsed - shows the live state and keeps the service alive.
        val status = NotificationChannel(CH_STATUS, "Tracking status", NotificationManager.IMPORTANCE_MIN)
        status.setShowBadge(false)
        status.description = "Ongoing status: sitting time and next reminder"
        nm.createNotificationChannel(status)

        val nudge = NotificationChannel(CH_NUDGE, "Move reminder", NotificationManager.IMPORTANCE_HIGH)
        nudge.enableVibration(true)
        nudge.description = "Fires when you have been sitting too long"
        nm.createNotificationChannel(nudge)
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun statusNotification(ctx: Context): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setContentTitle("Move Reminder")
            .setContentText(Engine.statusLine(ctx))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

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
        val title: String
        val text: String
        if (count <= 1) {
            title = "Time to stand up"
            text = "You have been sitting for $sittingMin min. Take a 2-3 minute walk."
        } else {
            title = "Still sitting · reminder $count"
            text = "$sittingMin min without a break. Stand up and move now."
        }
        val n = Notification.Builder(ctx, CH_NUDGE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_directions, "I stood up", stood)
            .addAction(android.R.drawable.ic_menu_recent_history, "In a meeting · 60 min", meeting)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_NUDGE, n)
    }

    fun cancelNudge(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_NUDGE)
        } catch (e: Exception) { }
    }
}
