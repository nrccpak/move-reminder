package com.fiaz.movereminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager

/** Live checks for everything the app needs to remind on time. */
object Setup {

    fun notificationsOk(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    } catch (e: Exception) {
        true
    }

    fun activityOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun sensorPresent(ctx: Context): Boolean =
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun batteryOk(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    fun exactOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun allOk(ctx: Context): Boolean =
        notificationsOk(ctx) && activityOk(ctx) && sensorPresent(ctx) && batteryOk(ctx) && exactOk(ctx)
}
