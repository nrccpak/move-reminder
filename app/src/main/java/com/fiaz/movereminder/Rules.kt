package com.fiaz.movereminder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where the active-hours window stands at a given moment.
 *  active      - reminders may fire right now
 *  periodStart - when the current active period began (only meaningful when active)
 *  nextStart   - when the next active period begins (only meaningful when not active)
 */
data class ActiveHours(val active: Boolean, val periodStart: Long, val nextStart: Long)

/**
 * Pure timing and movement rules. No Android imports, so they can be unit-tested
 * on a plain JVM. Engine.kt does the I/O and calls into these.
 */
object Rules {
    /** Regular step check while sitting. */
    const val CHECK_MS = 5 * 60_000L
    /** Steps are counted over roughly this much recent time to decide "you moved". */
    const val MOVE_WINDOW_MS = 6 * 60_000L
    /** Never schedule the next check sooner than this. */
    const val MIN_GAP_MS = 30_000L
    /** A nudge this close to due is fired now instead of waiting for another tick. */
    const val DUE_TOLERANCE_MS = 20_000L
    /** How long step readings are kept. */
    private const val KEEP_MS = 15 * 60_000L

    // ------------------------------------------------------------ active hours

    /** Same start and end hour means "all day". Start > end means an overnight window. */
    fun window(now: Long, startHour: Int, endHour: Int, tz: TimeZone = TimeZone.getDefault()): ActiveHours {
        if (startHour == endHour) return ActiveHours(true, Long.MIN_VALUE, Long.MAX_VALUE)
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        val hour = c.get(Calendar.HOUR_OF_DAY)
        return if (startHour < endHour) {
            when {
                hour < startHour -> ActiveHours(false, 0L, at(now, tz, 0, startHour))
                hour >= endHour -> ActiveHours(false, 0L, at(now, tz, 1, startHour))
                else -> ActiveHours(true, at(now, tz, 0, startHour), at(now, tz, 1, startHour))
            }
        } else {
            when {
                hour >= startHour -> ActiveHours(true, at(now, tz, 0, startHour), at(now, tz, 1, startHour))
                hour < endHour -> ActiveHours(true, at(now, tz, -1, startHour), at(now, tz, 0, startHour))
                else -> ActiveHours(false, 0L, at(now, tz, 0, startHour))
            }
        }
    }

    private fun at(now: Long, tz: TimeZone, dayOffset: Int, hour: Int): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** The moment the current sitting period started counting. */
    fun sitStart(anchor: Long, win: ActiveHours): Long = maxOf(anchor, win.periodStart)

    // ------------------------------------------------------------- reminders

    fun nudgeDue(
        now: Long, sitStart: Long, nudged: Boolean, lastNudge: Long,
        thresholdMin: Int, repeatMin: Int
    ): Boolean {
        return if (!nudged) {
            now + DUE_TOLERANCE_MS >= sitStart + thresholdMin * 60_000L
        } else {
            repeatMin > 0 && now + DUE_TOLERANCE_MS >= lastNudge + repeatMin * 60_000L
        }
    }

    /**
     * When to wake next: the regular step check, or earlier if a reminder (or the
     * end of a meeting) falls due sooner. Outside active hours: sleep until they start.
     */
    fun nextWake(
        now: Long, win: ActiveHours, inMeeting: Boolean, meetingUntil: Long,
        sitStart: Long, nudged: Boolean, lastNudge: Long, thresholdMin: Int, repeatMin: Int
    ): Long {
        var next = now + CHECK_MS
        if (!win.active) {
            next = win.nextStart
        } else if (inMeeting) {
            if (meetingUntil in (now + 1) until next) next = meetingUntil
        } else {
            val due = when {
                !nudged -> sitStart + thresholdMin * 60_000L
                repeatMin > 0 -> lastNudge + repeatMin * 60_000L
                else -> Long.MAX_VALUE
            }
            if (due < next) next = due
        }
        return maxOf(next, now + MIN_GAP_MS)
    }

    // -------------------------------------------------------------- movement

    fun parseHistory(s: String): MutableList<Pair<Long, Long>> =
        s.split(';').mapNotNull { item ->
            val parts = item.split(':')
            if (parts.size != 2) return@mapNotNull null
            val t = parts[0].toLongOrNull()
            val v = parts[1].toLongOrNull()
            if (t == null || v == null) null else Pair(t, v)
        }.toMutableList()

    fun encodeHistory(h: List<Pair<Long, Long>>): String =
        h.joinToString(";") { "${it.first}:${it.second}" }

    fun prune(h: List<Pair<Long, Long>>, now: Long): List<Pair<Long, Long>> {
        val kept = h.filter { now - it.first <= KEEP_MS }
        return if (kept.isEmpty() && h.isNotEmpty()) listOf(h.last()) else kept
    }

    /**
     * Steps taken recently: current count minus the oldest reading from the last
     * ~6 minutes (or the previous reading if checks were further apart). Small
     * fidgets spread over a long sitting period no longer add up to a false reset.
     */
    fun recentSteps(h: List<Pair<Long, Long>>, now: Long, steps: Long): Long {
        if (h.isEmpty()) return 0L
        val base = h.firstOrNull { now - it.first <= MOVE_WINDOW_MS } ?: h.last()
        return maxOf(0L, steps - base.second)
    }

    fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
}
