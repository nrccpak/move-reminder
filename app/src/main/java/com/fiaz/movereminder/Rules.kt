package com.fiaz.movereminder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The weekly work schedule.
 *  days      - bit 0 = Sunday ... bit 6 = Saturday
 *  startMin  - minutes after midnight (07:30 = 450); endMin likewise.
 *              start == end means all day; start > end means an overnight shift.
 *  lunch*    - optional quiet window inside the work day
 *  offUntil  - "Off today": nothing is active before this moment
 */
data class Schedule(
    val days: Int,
    val startMin: Int,
    val endMin: Int,
    val lunchOn: Boolean = false,
    val lunchStartMin: Int = 720,
    val lunchEndMin: Int = 780,
    val offUntil: Long = 0L
)

/**
 * Where the schedule stands at a moment.
 *  active      - reminders may run right now
 *  periodStart - start of the current active period (the sitting clock never counts from before it)
 *  nextStart   - start of the next active period (Long.MAX_VALUE if no work days are set)
 *  reason      - why it is not active: outside / lunch / off_today
 */
data class ActiveHours(val active: Boolean, val periodStart: Long, val nextStart: Long, val reason: String = "")

data class DayStats(val sittingSecs: Long, val breaks: Int, val longestSecs: Long)

/**
 * Pure timing and movement rules. No Android imports, so they are unit-tested on
 * a plain JVM (app/src/test). Engine.kt does the I/O and calls into these.
 */
object Rules {
    /** Regular step check while sitting. */
    const val CHECK_MS = 5 * 60_000L
    /** Steps are counted over roughly this much recent time to decide "you moved". */
    const val MOVE_WINDOW_MS = 6 * 60_000L
    /** Never schedule the next check sooner than this. */
    const val MIN_GAP_MS = 30_000L
    /** A reminder this close to due is fired now instead of waiting for another tick. */
    const val DUE_TOLERANCE_MS = 20_000L
    /** Longest sleep between checks outside work hours. */
    const val MAX_SLEEP_MS = 12 * 60 * 60_000L
    /** Snooze button on the reminder. */
    const val SNOOZE_MS = 10 * 60_000L
    /** How long step readings are kept. */
    private const val KEEP_MS = 15 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L

    const val MODE_OFFICE = "OFFICE"
    const val MODE_MEETING = "MEETING"
    const val MODE_BREAK = "BREAK"

    const val REASON_OUTSIDE = "outside"
    const val REASON_LUNCH = "lunch"
    const val REASON_OFF_TODAY = "off_today"

    /** Sun-Thu */
    const val DEFAULT_DAYS = 0b0011111
    val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    // -------------------------------------------------------------- calendar

    fun isWorkDay(days: Int, dayOfWeek: Int): Boolean = (days shr (dayOfWeek - 1)) and 1 == 1

    /** Timestamp of minuteOfDay on the day dayOffset days from now. */
    fun dayAt(now: Long, tz: TimeZone, dayOffset: Int, minuteOfDay: Int): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(Calendar.MINUTE, minuteOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun midnight(now: Long, tz: TimeZone = TimeZone.getDefault()): Long = dayAt(now, tz, 0, 0)
    fun nextMidnight(now: Long, tz: TimeZone = TimeZone.getDefault()): Long = dayAt(now, tz, 1, 0)

    private fun dayOfWeek(now: Long, tz: TimeZone, dayOffset: Int): Int {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.get(Calendar.DAY_OF_WEEK)
    }

    // ---------------------------------------------------------- active hours

    /** Active periods around now (yesterday to next week), with lunch and "off today" cut out. */
    fun periods(now: Long, s: Schedule, tz: TimeZone = TimeZone.getDefault()): List<Pair<Long, Long>> {
        val raw = ArrayList<Pair<Long, Long>>()
        for (d in -1..8) {
            if (!isWorkDay(s.days, dayOfWeek(now, tz, d))) continue
            val start = dayAt(now, tz, d, s.startMin)
            var dur = ((s.endMin - s.startMin) % 1440 + 1440) % 1440
            if (dur == 0) dur = 1440
            var pieces = listOf(Pair(start, start + dur * 60_000L))
            if (s.lunchOn && s.lunchStartMin < s.lunchEndMin) {
                val ls = dayAt(now, tz, d, s.lunchStartMin)
                val le = dayAt(now, tz, d, s.lunchEndMin)
                pieces = pieces.flatMap { cut(it, ls, le) }
            }
            raw.addAll(pieces)
        }
        raw.sortBy { it.first }
        // join periods that touch (all-day schedules on consecutive days)
        val merged = ArrayList<Pair<Long, Long>>()
        for (p in raw) {
            val last = merged.lastOrNull()
            if (last != null && p.first <= last.second) {
                merged[merged.size - 1] = Pair(last.first, maxOf(last.second, p.second))
            } else {
                merged.add(p)
            }
        }
        return if (s.offUntil > 0L) merged.flatMap { cut(it, Long.MIN_VALUE, s.offUntil) } else merged
    }

    private fun cut(p: Pair<Long, Long>, a: Long, b: Long): List<Pair<Long, Long>> {
        if (b <= p.first || a >= p.second) return listOf(p)
        val r = ArrayList<Pair<Long, Long>>()
        if (a > p.first) r.add(Pair(p.first, a))
        if (b < p.second) r.add(Pair(b, p.second))
        return r
    }

    fun window(now: Long, s: Schedule, tz: TimeZone = TimeZone.getDefault()): ActiveHours {
        val ps = periods(now, s, tz)
        val next = ps.firstOrNull { it.first > now }?.first ?: Long.MAX_VALUE
        val cur = ps.firstOrNull { now >= it.first && now < it.second }
        if (cur != null) return ActiveHours(true, cur.first, next)
        val reason = when {
            s.offUntil > now -> REASON_OFF_TODAY
            s.lunchOn && inLunch(now, s, tz) -> REASON_LUNCH
            else -> REASON_OUTSIDE
        }
        return ActiveHours(false, 0L, next, reason)
    }

    private fun inLunch(now: Long, s: Schedule, tz: TimeZone): Boolean {
        if (s.lunchStartMin >= s.lunchEndMin) return false
        if (!isWorkDay(s.days, dayOfWeek(now, tz, 0))) return false
        return now >= dayAt(now, tz, 0, s.lunchStartMin) && now < dayAt(now, tz, 0, s.lunchEndMin)
    }

    /** The moment the current sitting period started counting. */
    fun sitStart(anchor: Long, win: ActiveHours): Long = maxOf(anchor, win.periodStart)

    // ------------------------------------------------------------- reminders

    /** When the next reminder is due (Long.MAX_VALUE = none). A snooze overrides the normal schedule. */
    fun dueAt(
        sitStart: Long, nudged: Boolean, lastNudge: Long,
        thresholdMin: Int, repeatMin: Int, snoozeUntil: Long = 0L
    ): Long = when {
        snoozeUntil > 0L -> snoozeUntil
        !nudged -> sitStart + thresholdMin * 60_000L
        repeatMin > 0 -> lastNudge + repeatMin * 60_000L
        else -> Long.MAX_VALUE
    }

    fun nudgeDue(
        now: Long, sitStart: Long, nudged: Boolean, lastNudge: Long,
        thresholdMin: Int, repeatMin: Int, snoozeUntil: Long = 0L
    ): Boolean {
        val due = dueAt(sitStart, nudged, lastNudge, thresholdMin, repeatMin, snoozeUntil)
        return due != Long.MAX_VALUE && now + DUE_TOLERANCE_MS >= due
    }

    /**
     * When to wake next: the regular step check, or earlier if a reminder (or the end
     * of a Meeting/Break) falls due sooner. Outside work hours: sleep until they start.
     */
    fun nextWake(
        now: Long, win: ActiveHours, paused: Boolean, modeUntil: Long,
        sitStart: Long, nudged: Boolean, lastNudge: Long,
        thresholdMin: Int, repeatMin: Int, snoozeUntil: Long = 0L
    ): Long {
        var next = if (win.active) now + CHECK_MS else minOf(win.nextStart, now + MAX_SLEEP_MS)
        if (paused) {
            if (modeUntil in (now + 1) until next) next = modeUntil
        } else if (win.active) {
            val due = dueAt(sitStart, nudged, lastNudge, thresholdMin, repeatMin, snoozeUntil)
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
     * fidgets spread over a long sitting period do not add up to a false reset.
     */
    fun recentSteps(h: List<Pair<Long, Long>>, now: Long, steps: Long): Long {
        if (h.isEmpty()) return 0L
        val base = h.firstOrNull { now - it.first <= MOVE_WINDOW_MS } ?: h.last()
        return maxOf(0L, steps - base.second)
    }

    // ------------------------------------------------------------- statistics

    fun dayKey(t: Long, tz: TimeZone = TimeZone.getDefault()): String = fmt("yyyy-MM-dd", t, tz)

    /**
     * Totals for one day from bouts.csv lines ("start,end,secs,mode,nudged,endedBy").
     * Break records are not sitting. "Breaks" = times you moved (movement or I stood up).
     */
    fun dayStats(lines: List<String>, day: String, ongoingSecs: Long = 0L): DayStats {
        var sitting = 0L
        var breaks = 0
        var longest = 0L
        for (line in lines) {
            val p = line.split(',')
            if (p.size < 6 || !p[0].startsWith(day)) continue
            if (p[3] == MODE_BREAK) continue
            val secs = p[2].toLongOrNull() ?: continue
            sitting += secs
            if (secs > longest) longest = secs
            if (p[5] == "movement" || p[5] == "manual") breaks++
        }
        sitting += ongoingSecs
        if (ongoingSecs > longest) longest = ongoingSecs
        return DayStats(sitting, breaks, longest)
    }

    // ------------------------------------------------------------- formatting

    private fun fmt(pattern: String, t: Long, tz: TimeZone): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = tz
        return f.format(Date(t))
    }

    fun hhmm(t: Long, tz: TimeZone = TimeZone.getDefault()): String = fmt("HH:mm", t, tz)

    fun dayName(t: Long, tz: TimeZone = TimeZone.getDefault()): String = fmt("EEE", t, tz)

    fun minText(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /** "13:00", "tomorrow 07:00" or "Sun 07:00". */
    fun resumeText(next: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        if (next == Long.MAX_VALUE) return "when work days are set"
        val time = hhmm(next, tz)
        return when (dayKey(next, tz)) {
            dayKey(now, tz) -> time
            dayKey(dayAt(now, tz, 1, 0), tz) -> "tomorrow $time"
            else -> "${dayName(next, tz)} $time"
        }
    }

    /** "Sun–Thu", "Every day", "Sun, Tue, Thu". */
    fun daysText(days: Int): String {
        val on = (0..6).filter { (days shr it) and 1 == 1 }
        if (on.isEmpty()) return "No work days"
        if (on.size == 7) return "Every day"
        if (on.size == 1) return DAY_NAMES[on[0]]
        val starts = on.filter { (days shr ((it + 6) % 7)) and 1 == 0 }
        if (starts.size == 1) {
            val first = starts[0]
            val last = (first + on.size - 1) % 7
            return "${DAY_NAMES[first]}–${DAY_NAMES[last]}"
        }
        return on.joinToString(", ") { DAY_NAMES[it] }
    }

    fun scheduleText(s: Schedule): String {
        val hours = if (s.startMin == s.endMin) "all day" else "${minText(s.startMin)}–${minText(s.endMin)}"
        return "${daysText(s.days)} · $hours"
    }
}
