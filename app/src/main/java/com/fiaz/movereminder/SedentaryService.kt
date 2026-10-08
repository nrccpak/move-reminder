package com.fiaz.movereminder

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * The foreground service keeps the process alive and keeps the step counter
 * registered. The decisions happen on alarm ticks (Engine).
 */
class SedentaryService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        Notifier.createChannels(this)
        StepReader.startKeepAlive(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            BoutLog.debug(this, "foreground start failed: ${e.message}")
        }
        if (!Prefs.isRunning(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val doTick = intent?.getBooleanExtra(EXTRA_TICK, true) ?: true
        if (doTick) {
            Scheduler.scheduleFallback(this)
            Engine.tick(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        StepReader.stopKeepAlive(this)
        BoutLog.debug(this, "service stopped")
        super.onDestroy()
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
        const val EXTRA_TICK = "tick"

        @Volatile
        var alive = false

        /**
         * Start (or restart) tracking without resetting the sitting clock.
         * The health-type foreground service needs the Physical activity
         * permission; without it the app still runs on alarms alone.
         */
        fun start(ctx: Context) {
            Prefs.get(ctx).edit().putBoolean(Prefs.KEY_RUNNING, true).commit()
            if (Setup.activityOk(ctx)) {
                try {
                    ctx.startForegroundService(Intent(ctx, SedentaryService::class.java))
                    return
                } catch (e: Exception) {
                    BoutLog.debug(ctx, "service start failed: ${e.message}")
                }
            }
            Scheduler.scheduleFallback(ctx)
            Engine.tick(ctx)
        }

        /** Bring back a killed service without an extra tick. */
        fun revive(ctx: Context) {
            if (!Setup.activityOk(ctx)) return
            try {
                ctx.startForegroundService(
                    Intent(ctx, SedentaryService::class.java).putExtra(EXTRA_TICK, false)
                )
                BoutLog.debug(ctx, "service was not running - restarted")
            } catch (e: Exception) {
                BoutLog.debug(ctx, "service restart not allowed: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            Engine.stopTracking(ctx)
            Scheduler.cancel(ctx)
            Notifier.cancelNudge(ctx)
            ctx.stopService(Intent(ctx, SedentaryService::class.java))
            try {
                ctx.getSystemService(NotificationManager::class.java).cancel(Notifier.ID_STATUS)
            } catch (e: Exception) { }
        }

        /** Update the ongoing notification text without restarting anything. */
        fun refreshStatus(ctx: Context) {
            if (!Prefs.isRunning(ctx) || !alive) return
            try {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(Notifier.ID_STATUS, Notifier.statusNotification(ctx))
            } catch (e: Exception) { }
        }
    }
}
