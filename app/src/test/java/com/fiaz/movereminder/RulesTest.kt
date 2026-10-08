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
 * Engine.reschedule using only the pure rules, with no Android needed.
 */

private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TZ }
private fun t(s: String): Long = FMT.parse(s).time
private fun f(ms: Long): String = FMT.format(ms).substring(11, 16)
private const val MIN = 60_000L

/** Mirrors Engine.evaluate + reschedule, using only Rules. */
private class Sim(
    var anchor: Long, val threshold: Int, val repeat: Int, val moveSteps: Int,
    val ws: Int = 7, val we: Int = 17, var wasActive: Boolean = false
) {
    var nudged = false
    var lastNudge = 0L
    var count = 0
    var hist = mutableListOf<Pair<Long, Long>>()
    val nudges = mutableListOf<String>()
    val resets = mutableListOf<String>()
    val wakes = mutableListOf<String>()

    fun evaluate(now: Long, steps: Long) {
        var moved = false
        val last = hist.lastOrNull()
        if (last != null && steps < last.second) hist.clear()
        val recent = Rules.recentSteps(hist, now, steps)
        if (hist.isNotEmpty() && recent >= moveSteps) { moved = true; hist.clear() }
        hist.add(Pair(now, steps)); hist = Rules.prune(hist, now).toMutableList()
        val win = Rules.window(now, ws, we, TZ)
        if (moved) { anchor = now; nudged = false; count = 0; resets.add(f(now)) }
        if (!win.active) {
            anchor = now; nudged = false; count = 0; wasActive = false
        } else {
            if (!wasActive) { anchor = Rules.sitStart(anchor, win); wasActive = true }
            val ss = Rules.sitStart(anchor, win)
            if (Rules.nudgeDue(now, ss, nudged, lastNudge, threshold, repeat)) {
                count++; nudged = true; lastNudge = now
                nudges.add("${f(now)}#$count(${(now - ss) / MIN}m)")
            }
        }
    }

    fun next(now: Long): Long {
        val win = Rules.window(now, ws, we, TZ)
        return Rules.nextWake(now, win, false, 0L, if (win.active) Rules.sitStart(anchor, win) else now,
            nudged, lastNudge, threshold, repeat)
    }

    fun run(from: Long, to: Long, steps: (Long) -> Long) {
        var now = from
        while (now < to) {
            evaluate(now, steps(now))
            now = next(now)
            wakes.add(f(now))
        }
    }
}

class RulesTest {
    @Test
    fun window_day() {
        val w1 = Rules.window(t("2026-10-08 06:30:00"), 7, 17, TZ)
        assertFalse(w1.active); assertEquals(t("2026-10-08 07:00:00"), w1.nextStart)
        val w2 = Rules.window(t("2026-10-08 12:00:00"), 7, 17, TZ)
        assertTrue(w2.active); assertEquals(t("2026-10-08 07:00:00"), w2.periodStart)
        val w3 = Rules.window(t("2026-10-08 17:00:00"), 7, 17, TZ)
        assertFalse(w3.active); assertEquals(t("2026-10-09 07:00:00"), w3.nextStart)
    }

    @Test
    fun window_overnight_and_allday() {
        val a = Rules.window(t("2026-10-08 23:00:00"), 22, 6, TZ)
        assertTrue(a.active); assertEquals(t("2026-10-08 22:00:00"), a.periodStart)
        val b = Rules.window(t("2026-10-09 03:00:00"), 22, 6, TZ)
        assertTrue(b.active); assertEquals(t("2026-10-08 22:00:00"), b.periodStart)
        val c = Rules.window(t("2026-10-09 12:00:00"), 22, 6, TZ)
        assertFalse(c.active); assertEquals(t("2026-10-09 22:00:00"), c.nextStart)
        assertTrue(Rules.window(t("2026-10-09 03:00:00"), 8, 8, TZ).active)
    }

    @Test
    fun morning_no_spam_after_overnight() {
        // Phone lay still since 21:00 yesterday (the old bug: nudge at 07:05 "after 583 min").
        val s = Sim(anchor = t("2026-10-07 21:00:00"), threshold = 25, repeat = 10, moveSteps = 15)
        s.run(t("2026-10-07 21:05:00"), t("2026-10-08 07:40:00")) { 1000L }
        println("morning nudges: ${s.nudges}  wakes(first 6): ${s.wakes.take(6)}")
        assertEquals("07:25#1(25m)", s.nudges.first())
        assertEquals("07:35#2(35m)", s.nudges[1])
        // Overnight it slept until 07:00 instead of polling every 5 minutes.
        assertEquals("07:00", s.wakes.first())
    }

    @Test
    fun reminders_land_on_time_with_short_repeat() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 5, repeat = 2, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 09:12:00")) { 500L }
        println("5/2 nudges: ${s.nudges}")
        assertEquals(listOf("09:05#1(5m)", "09:07#2(7m)", "09:09#3(9m)", "09:11#4(11m)"), s.nudges)
    }

    @Test
    fun fidgets_do_not_reset_but_a_walk_does() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        // 6 steps every ~7 minutes (chair, door): 18+ in total but never 15 within 5 min.
        val fidget = { now: Long -> 100L + 6L * ((now - start) / (7 * MIN)) }
        s.run(start, t("2026-10-08 09:30:00"), fidget)
        println("fidget resets=${s.resets} nudges=${s.nudges}")
        assertTrue(s.resets.isEmpty())
        assertEquals("09:25#1(25m)", s.nudges.first())

        // A real walk: 60 steps between 09:12 and 09:14.
        val s2 = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        val walk = { now: Long -> if (now >= t("2026-10-08 09:14:00")) 160L else 100L }
        s2.run(start, t("2026-10-08 09:45:00"), walk)
        println("walk resets=${s2.resets} nudges=${s2.nudges}")
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
    fun meeting_wakes_at_meeting_end() {
        val now = t("2026-10-08 10:00:00")
        val win = Rules.window(now, 7, 17, TZ)
        assertEquals(now + 2 * MIN, Rules.nextWake(now, win, true, now + 2 * MIN, now, false, 0, 25, 10))
        assertEquals(now + 5 * MIN, Rules.nextWake(now, win, true, now + 60 * MIN, now, false, 0, 25, 10))
    }

    @Test
    fun repeat_off_reminds_once() {
        val start = t("2026-10-08 09:00:00")
        val s = Sim(anchor = start, threshold = 10, repeat = 0, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 10:00:00")) { 1L }
        assertEquals(listOf("09:10#1(10m)"), s.nudges)
    }

    @Test
    fun evening_end_of_hours() {
        val start = t("2026-10-08 16:40:00")
        val s = Sim(anchor = start, threshold = 25, repeat = 10, moveSteps = 15, wasActive = true)
        s.run(start, t("2026-10-08 18:00:00")) { 1L }
        println("evening nudges=${s.nudges} wakes=${s.wakes}")
        assertTrue(s.nudges.isEmpty()) // 16:40 + 25 = 17:05 is after hours
        assertEquals("07:00", s.wakes.last())
    }
}
