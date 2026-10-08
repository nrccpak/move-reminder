package com.fiaz.movereminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_STATUS = "status"
    /** First reminder: normal sound, one short vibration. */
    const val CH_NUDGE = "nudge"
    /** Second reminder: longer double vibration. */
    const val CH_NUDGE_REPEAT = "nudge_repeat"
    /** Third and later: strong long pattern. */
    const val CH_NUDGE_URGENT = "nudge_urgent"
    const val ID_STATUS = 1
    const val ID_NUDGE = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Silent, collapsed - shows the live state and keeps the service alive.
        val status = NotificationChannel(CH_STATUS, "Tracking status", NotificationManager.IMPORTANCE_MIN)
        status.setShowBadge(false)
        status.description = "Ongoing status: sitting time and next reminder"
        nm.createNotificationChannel(status)

        nm.createNotificationChannel(
            nudgeChannel(CH_NUDGE, "Move reminder", "First reminder when you have been sitting too long",
                longArrayOf(0, 400))
        )
        nm.createNotificationChannel(
            nudgeChannel(CH_NUDGE_REPEAT, "Move reminder · repeat", "Second reminder, stronger vibration",
                longArrayOf(0, 600, 250, 600))
        )
        nm.createNotificationChannel(
            nudgeChannel(CH_NUDGE_URGENT, "Move reminder · urgent", "Third and later reminders, strongest vibration",
                longArrayOf(0, 900, 300, 900, 300, 900))
        )
    }

    private fun nudgeChannel(id: String, name: String, desc: String, pattern: LongArray): NotificationChannel {
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.description = desc
        ch.enableVibration(true)
        ch.vibrationPattern = pattern
        return ch
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
        val snooze = PendingIntent.getBroadcast(
            ctx, 12,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel: String
        val title: String
        val text: String
        when {
            count <= 1 -> {
                channel = CH_NUDGE
                title = "Time to stand up"
                text = "You have been sitting for $sittingMin min. Take a 2-3 minute walk."
            }
            count == 2 -> {
                channel = CH_NUDGE_REPEAT
                title = "Still sitting · reminder 2"
                text = "$sittingMin min without a break. Stand up and move."
            }
            else -> {
                channel = CH_NUDGE_URGENT
                title = "Stand up now · reminder $count"
                text = "$sittingMin min without a break. Please take a walk now."
            }
        }
        val n = Notification.Builder(ctx, channel)
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
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10 min", snooze)
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_NUDGE)
        nm.notify(ID_NUDGE, n)
    }

    fun cancelNudge(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_NUDGE)
        } catch (e: Exception) { }
    }
}
