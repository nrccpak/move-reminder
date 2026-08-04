package com.fiaz.movereminder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import android.os.Build
import android.os.IBinder

/**
 * The foreground service does almost no work. Its job is to stop One UI from
 * killing the process. The actual sensing happens on the 5 minute alarm tick.
 */
class SedentaryService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        Scheduler.scheduleNext(this)
        Engine.tick(this)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BoutLog.debug(this, "service destroyed")
    }

    private fun goForeground() {
        val n = Notifier.statusNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(Notifier.ID_STATUS, n)
        }
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, SedentaryService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            Prefs.get(ctx).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
            Scheduler.cancel(ctx)
            Notifier.cancelNudge(ctx)
            ctx.stopService(Intent(ctx, SedentaryService::class.java))
        }

        /** Update the ongoing notification text without restarting anything. */
        fun refreshStatus(ctx: Context) {
            if (!Prefs.isRunning(ctx)) return
            try {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(Notifier.ID_STATUS, Notifier.statusNotification(ctx))
            } catch (e: Exception) { }
        }
    }
}
