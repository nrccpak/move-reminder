package com.fiaz.movereminder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * One-shot read of the hardware step counter.
 *
 * TYPE_STEP_COUNTER is an on-change sensor: registering delivers the current
 * cumulative value immediately, so we register, take one sample, unregister.
 * No continuous listening, no wake lock, effectively zero battery cost.
 */
object StepReader {

    fun read(ctx: Context, timeoutMs: Long = 4000L, cb: (Long?) -> Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            cb(null)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var listener: SensorEventListener? = null

        fun finish(value: Long?) {
            if (finished) return
            finished = true
            listener?.let {
                try { sm.unregisterListener(it) } catch (e: Exception) { }
            }
            cb(value)
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                finish(event.values[0].toLong())
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { }
        }

        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        } catch (e: Exception) {
            finish(null)
            return
        }
        handler.postDelayed({ finish(null) }, timeoutMs)
    }
}
