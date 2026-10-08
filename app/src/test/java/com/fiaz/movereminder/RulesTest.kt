package com.fiaz.movereminder

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulated days for the timing rules (Rules.kt). Sim mirrors Engine.evaluate and
 * Engine.reschedule for Office mode using only the pure rules, with no Android needed.
 *
 * Calendar used: 2026-10-07 Wed, 08 Thu, 09 Fri, 10 Sat, 11 Sun.
 */
private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TZ }
private fun t(s: String): Long = FMT.parse(s)!!.time
private fun f(ms: Long): String = FMT.format(ms).substring(11, 16)
private fun fd(ms: Long): String = FMT.format(ms).substring(0, 16)
private const val MIN = 60_000L
private const val ALL_DAYS = 0b1111111

/** Sun-Thu 07:00-17:00, like the default. */
private val WORK = Schedule(Rules.DEFAULT_DAYS, 7 * 60, 17 * 60)

private class Sim(
    var anchor: Long, val threshold: Int, val repeat: Int, val moveSteps: Int,
    val sched: Schedule = WORK, var wasActive: Boolean = false
) {
    var nudged = false
    var lastNudge = 0L
    var count = 0
    var snooze = 0L
    var hist = mutableListOf<Pair<Long, Long>>()
    val nudges = mutableListOf<String>()
    val resets = mutableListOf<String>()
    val wakes = mutableListOf<Long>()
    /** Press Snooze right after the reminder with this number. */
    var snoozeAfter = -1

    fun evaluate(now: Long, steps: Long) {
        var moved = false
        val last = hist.lastOrNull()
        if (last != null && steps < last.second) hist.clear()
        val recent = Rules.recentSteps(hist, now, steps)
        if (hist.isNotEmpty() && recent >= moveSteps) { moved = true; hist.clear() }
        hist.add(Pair(now, steps)); hist = Rules.prune(hist, now).toMutableList()
        val win = Rules.window(now, sched, TZ)
        if (moved && win.active) { anchor = now; nudged = false; count = 0; snooze = 0; resets.add(f(now)) }
        if (!win.active) {
            anchor = now; nudged = false; count = 0; snooze = 0; wasActive = false
        } else {
            if (!wasActive) { anchor = Rules.sitStart(anchor, win); wasActive = true }
            val ss = Rules.sitStart(anchor, win)
            if (Rules.nudgeDue(now, ss, nudged, lastNudge, threshold, repeat, snooze)) {
                count++; nudged = true; lastNudge = now; snooze = 0
                nudges.add("${f(now)}#$count(${(now - ss) / MIN}m)")
                if (count == snoozeAfter) snooze = now + Rules.SNOOZE_MS
            }
        }
    }

    fun next(now: Long): Long {
        val win = Rules.window(now, sched, TZ)
        return Rules.nextWake(now, win, false, 0L, if (win.active) Rules.sitStart(anchor, win) else now,
            nudged, lastNudge, threshold, repeat, snooze)
    }

    fun run(from: Long, to: Long, steps: (Long) -> Long) {
        var now = from
        while (now < to) {
            evaluate(now, steps(now))
            now = next(now)
            wakes.add(now)
        }
    }
}

class RulesTest {

    // ---------------------------------------------------------------- schedule

    @Test
    fun workday_hours_and_weekend() {
        val a = Rules.window(t("2026-10-08 06:30:00"), WORK, TZ)
        assertFalse(a.active)
        assertEquals(t("2026-10-08 07:00:00"), a.nextStart)
        val b = Rules.window(t("2026-10-08 12:00:00"), WORK, TZ)
        assertTrue(b.active)
        assertEquals(t("2026-10-08 07:00:00"), b.periodStart)
        // Thursday evening: Friday and Saturday are off, so the next start is Sunday 07:00.
        val c = Rules.window(t("2026-10-08 17:00:00"), WORK, TZ)
        assertFalse(c.active)
        assertEquals(t("2026-10-11 07:00:00"), c.nextStart)
        val fri = Rules.window(t("2026-10-09 12:00:00"), WORK, TZ)
        assertFalse(fri.active)
        assertEquals(Rules.REASON_OUTSIDE, fri.reason)
        assertEquals("Sun 07:00", Rules.resumeText(fri.nextStart, t("2026-10-09 12:00:00"), TZ))
    }

    @Test
    fun half_hour_start_and_six_day_week() {
        val s = Schedule(0b0111111, 7 * 60 + 30, 16 * 60 + 45) // Sun-Fri 07:30-16:45
        assertFalse(Rules.window(t("2026-10-08 07:20:00"), s, TZ).active)
        assertEquals(t("2026-10-08 07:30:00"), Rules.window(t("2026-10-08 07:20:00"), s, TZ).nextStart)
        assertTrue(Rules.window(t("2026-10-09 10:00:00"), s, TZ).active) // Friday is on
        assertFalse(Rules.window(t("2026-10-08 16:50:00"), s, TZ).active)
    }

    @Test
    fun overnight_and_all_day() {
        val night = Schedule(ALL_DAYS, 22 * 60, 6 * 60)
        val a = Rules.window(t("2026-10-08 23:00:00"), night, TZ)
        assertTrue(a.active); assertEquals(t("2026-10-08 22:00:00"), a.periodStart)
        val b = Rules.window(t("2026-10-09 03:00:00"), night, TZ)
        assertTrue(b.active); assertEquals(t("2026-10-08 22:00:00"), b.periodStart)
        val c = Rules.window(t("2026-10-09 12:00:00"), night, TZ)
        assertFalse(c.active); assertEquals(t("2026-10-09 22:00:00"), c.nextStart)
        assertTrue(Rules.window(t("2026-10-09 03:00:00"), Schedule(ALL_DAYS, 8 * 60, 8 * 60), TZ).active)
    }

    @Test
    fun lunch_break_is_quiet_and_restarts_the_clock() {
        val s = WORK.copy(lunchOn = true, lunchStartMin = 12 * 60, lunchEndMin = 13 * 60)
        val l = Rules.window(t("2026-10-08 12:30:00"), s, TZ)
        assertFalse(l.active)
        assertEquals(Rules.REASON_LUNCH, l.reason)
        assertEquals(t("2026-10-08 13:00:00"), l.nextStart)
        val after = Rules.window(t("2026-10-08 13:10:00"), s, TZ)
        assertTrue(after.active)
        assertEquals(t("2026-10-08 13:00:00"), after.periodStart)

        // Sitting all morning: reminders stop for lunch; first one after lunch at 13:25.
        val sim = Sim(anchor = t("2026-10-08 11:30:00"), threshold = 25, repeat = 10, moveSteps = 15,
            sched = s, wasActive = true)
        sim.run(t("2026-10-08 11:30:00"), t("2026-10-08 13:30:00")) { 100L }
        println("lunch nudges: ${sim.nudges}")
        assertEquals("11:55#1(25m)", sim.nudges.first())
        assertTrue(sim.nudges.none { it.startsWith("12:") })
        assertTrue(sim.nudges.contains("13:25#1(25m)"))
    }

    @Test
    fun off_today_is_quiet_until_the_next_work_day() {
        val now = t("2026-10-08 10:00:00")
        val s = WORK.copy(offUntil = Rules.nextMidnight(now, TZ))
        val w = Rules.window(now, s, TZ)
        assertFalse(w.active)
        assertEquals(Rules.REASON_OFF_TODAY, w.reason)
        assertEquals(t("2026-10-11 07:00:00"), w.nextStart)
    }

    @Test
    fun days_text() {
        assertEquals("Sun–Thu", Rules.daysText(Rules.DEFAULT_DAYS))
        assertEquals("Every day", Rules.daysText(ALL_DAYS))
        assertEquals("Sat–Wed", Rules.daysText(0b1001111))
        assertEquals("Sun, Tue, Thu", Rules.daysText(0b0010101))
        assertEquals("No work days", Rules.daysText(0))
        assertEquals("Sun–Thu · 07:00–17:00", Rules.scheduleText(WORK))
    }

    // --------------------------------------------------------------- reminders

    @Test
    fun morning_no_spam_after_overnight() {
        // Phone lay still since 21:00 Wednesday (the old bug: reminder at 07:05 "after 583 min").
        val s = Sim(anchor = t("2026-10-07 21:00:00"), threshold = 25, repeat = 10, moveSteps = 15)
        s.run(t("2026-10-07 21:05:00"), t("2026-10-08 07:40:00")) { 1000L }
        println("morning nudges: ${s.nudges}")
        assertEquals("07:25#1(25m)", s.nudges.first())
        assertEquals("07:35#2(35m)", s.nudges[1])
        assertEquals("2026-10-08 07:00", fd(s.wakes.first()))
    }

    @Test
    fun weekend_is_silent() {
        val s = Sim(anchor = t("2026-10-08 16:40:00"), threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        s.run(t("2026-10-08 16:40:00"), t("2026-10-11 07:30:00")) { 1L }
        println("weekend nudges=${s.nudges} wakes=${s.wakes.map { fd(it) }}")
        assertEquals(listOf("07:25#1(25m)"), s.nudges) // only Sunday morning
        // Slept in 12-hour steps over the weekend instead of polling every 5 minutes.
        assertTrue(s.wakes.size < 20)
    }

    @Test
    fun reminders_land_on_time_with_short_repeat() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 5, repeat = 2, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 09:12:00")) { 500L }
        assertEquals(listOf("09:05#1(5m)", "09:07#2(7m)", "09:09#3(9m)", "09:11#4(11m)"), s.nudges)
    }

    @Test
    fun snooze_holds_the_next_reminder_ten_minutes() {
        val start = t("2026-10-08 09:00:00")
        // repeat 2 min, but Snooze pressed on reminder 1: next comes 10 min later, then repeats resume.
        val s = Sim(anchor = start, threshold = 25, repeat = 2, moveSteps = 15, wasActive = true)
        s.snoozeAfter = 1
        s.run(start, t("2026-10-08 09:38:00")) { 1L }
        println("snooze nudges: ${s.nudges}")
        assertEquals(listOf("09:25#1(25m)", "09:35#2(35m)", "09:37#3(37m)"), s.nudges)
        // Snooze also works when repeat is off.
        val s2 = Sim(anchor = start, threshold = 25, repeat = 0, moveSteps = 15, wasActive = true)
        s2.snoozeAfter = 1
        s2.run(start, t("2026-10-08 10:00:00")) { 1L }
        assertEquals(listOf("09:25#1(25m)", "09:35#2(35m)"), s2.nudges)
    }

    @Test
    fun fidgets_do_not_reset_but_a_walk_does() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        val fidget = { now: Long -> 100L + 6L * ((now - start) / (7 * MIN)) }
        s.run(start, t("2026-10-08 09:30:00"), fidget)
        assertTrue(s.resets.isEmpty())
        assertEquals("09:25#1(25m)", s.nudges.first())

        val s2 = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        val walk = { now: Long -> if (now >= t("2026-10-08 09:14:00")) 160L else 100L }
        s2.run(start, t("2026-10-08 09:45:00"), walk)
        assertEquals(listOf("09:15"), s2.resets)
        assertEquals("09:40#1(25m)", s2.nudges.first())
    }

    @Test
    fun reboot_counter_drop_is_not_movement() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 09:30:00")) { now -> if (now < t("2026-10-08 09:12:00")) 5000L else 3L }
        assertTrue(s.resets.isEmpty())
        assertEquals("09:25#1(25m)", s.nudges.first())
    }

    @Test
    fun meeting_or_break_wakes_at_its_end() {
        val now = t("2026-10-08 10:00:00")
        val win = Rules.window(now, WORK, TZ)
        assertEquals(now + 2 * MIN, Rules.nextWake(now, win, true, now + 2 * MIN, now, false, 0, 25, 10))
        assertEquals(now + 5 * MIN, Rules.nextWake(now, win, true, now + 60 * MIN, now, false, 0, 25, 10))
        // Break running past work hours: still wakes when it ends.
        val eve = t("2026-10-08 17:10:00")
        val off = Rules.window(eve, WORK, TZ)
        assertEquals(eve + 20 * MIN, Rules.nextWake(eve, off, true, eve + 20 * MIN, eve, false, 0, 25, 10))
    }

    @Test
    fun repeat_off_reminds_once() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 10, repeat = 0, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 10:00:00")) { 1L }
        assertEquals(listOf("09:10#1(10m)"), s.nudges)
    }

    // -------------------------------------------------------------- statistics

    @Test
    fun day_stats_count_sitting_breaks_and_longest() {
        val lines = listOf(
            "2026-10-08 07:00:00,2026-10-08 07:40:00,2400,OFFICE,true,movement",
            "2026-10-08 07:45:00,2026-10-08 08:05:00,1200,OFFICE,false,manual",
            "2026-10-08 08:05:00,2026-10-08 09:05:00,3600,MEETING,false,meeting_end",
            "2026-10-08 12:00:00,2026-10-08 12:30:00,1800,BREAK,false,break_end",
            "2026-10-07 09:00:00,2026-10-07 10:00:00,3600,OFFICE,true,movement",
            "bad line"
        )
        val d = Rules.dayStats(lines, "2026-10-08", 300)
        assertEquals(2400L + 1200 + 3600 + 300, d.sittingSecs)
        assertEquals(2, d.breaks)
        assertEquals(3600L, d.longestSecs)
        assertEquals(3600L, Rules.dayStats(lines, "2026-10-07").sittingSecs)
    }
}
