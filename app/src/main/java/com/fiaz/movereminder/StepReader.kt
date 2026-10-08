package com.fiaz.movereminder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Hardware step counter (TYPE_STEP_COUNTER), counted by the low-power sensor hub.
 *
 *  - keepAlive: the service keeps one listener registered with heavy batching, as
 *    Android recommends, so the hub keeps counting even when nothing is reading.
 *  - read(): a fresh reading for each tick. The first event after registering can
 *    be a cached, slightly stale value, so we ask for a flush and take the highest
 *    value seen during a short settle period.
 */
object StepReader {
    private const val SETTLE_MS = 1500L
    private const val TIMEOUT_MS = 4000L
    private const val KEEPALIVE_LATENCY_US = 5 * 60 * 1_000_000

    private var keepAlive: SensorEventListener? = null

    private fun sensor(ctx: Context): Pair<SensorManager, Sensor>? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return Pair(sm, s)
    }

    fun startKeepAlive(ctx: Context) {
        if (keepAlive != null) return
        val (sm, s) = sensor(ctx) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { }
        }
        try {
            if (sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, KEEPALIVE_LATENCY_US)) {
                keepAlive = l
            }
        } catch (e: Exception) { }
    }

    fun stopKeepAlive(ctx: Context) {
        val l = keepAlive ?: return
        keepAlive = null
        val (sm, _) = sensor(ctx) ?: return
        try { sm.unregisterListener(l) } catch (e: Exception) { }
    }

    fun read(ctx: Context, cb: (Long?) -> Unit) {
        val pair = sensor(ctx)
        if (pair == null) {
            cb(null)
            return
        }
        val (sm, s) = pair
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var best: Long? = null
        var settling = false
        var listener: SensorEventListener? = null

        fun finish() {
            if (finished) return
            finished = true
            listener?.let { try { sm.unregisterListener(it) } catch (e: Exception) { } }
            cb(best)
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values[0].toLong()
                handler.post {
                    val b = best
                    if (b == null || v > b) best = v
                    if (!settling) {
                        settling = true
                        handler.postDelayed({ finish() }, SETTLE_MS)
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { }
        }

        val ok = try {
            sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST, 0)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            finish()
            return
        }
        try { sm.flush(listener) } catch (e: Exception) { }
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }
}
