package com.fiaz.movereminder

import android.content.Context
import android.content.SharedPreferences

/**
 * All decision logic. Runs on every tick (every 5 min, or exactly when a
 * reminder or a Meeting/Break end falls due). The pure rules live in Rules.kt.
 *
 *  - Sitting time only counts on work days, inside work hours, outside lunch.
 *    It restarts at the start of each active period.
 *  - "Moved" means at least moveSteps steps within the last ~5 minutes.
 *  - Office: first reminder at the threshold, then every repeatMin until you move.
 *  - Meeting: no reminders, sitting time still logged.
 *  - Break: everything paused; the sitting clock restarts when it ends.
 */
object Engine {

    fun tick(ctx: Context, done: (() -> Unit)? = null) {
        StepReader.read(ctx) { steps ->
            try {
                evaluate(ctx, steps)
            } catch (e: Exception) {
                BoutLog.debug(ctx, "tick error: ${e.message}")
                try { reschedule(ctx) } catch (x: Exception) { }
            }
            done?.invoke()
        }
    }

    fun windowNow(ctx: Context): ActiveHours =
        Rules.window(System.currentTimeMillis(), Prefs.schedule(ctx))

    @Synchronized
    private fun evaluate(ctx: Context, steps: Long?) {
        if (!Prefs.isRunning(ctx)) return
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()

        var anchor = p.getLong(Prefs.KEY_ANCHOR_TIME, now)
        var mode = Prefs.mode(ctx)
        var nudged = p.getBoolean(Prefs.KEY_NUDGED, false)
        var count = p.getInt(Prefs.KEY_NUDGE_COUNT, 0)
        var lastNudge = p.getLong(Prefs.KEY_LAST_NUDGE_TIME, 0L)
        var wasActive = p.getBoolean(Prefs.KEY_WAS_ACTIVE, false)
        var snooze = p.getLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
        val e = p.edit()

        if (Prefs.offUntil(ctx) in 1..now) e.putLong(Prefs.KEY_OFF_UNTIL, 0L)

        // 1. Meeting or Break time is over: back to office, sitting clock restarts now.
        var modeEnded = false
        if (mode != Prefs.MODE_OFFICE && now >= Prefs.modeUntil(ctx)) {
            logModeEnd(ctx, mode, anchor, now)
            BoutLog.debug(ctx, "${label(mode)} time over -> office mode")
            mode = Prefs.MODE_OFFICE
            anchor = now
            nudged = false
            count = 0
            snooze = 0L
            modeEnded = true
            e.putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE).putLong(Prefs.KEY_MEETING_UNTIL, 0L)
        }

        // 2. Movement: steps within the last ~5 minutes.
        var moved = false
        if (steps == null) {
            e.putBoolean(Prefs.KEY_SENSOR_OK, false)
            BoutLog.debug(ctx, "no step reading - reminders are time-only")
        } else {
            e.putBoolean(Prefs.KEY_SENSOR_OK, true)
            val hist = Rules.parseHistory(p.getString(Prefs.KEY_STEP_HISTORY, "") ?: "")
            if (modeEnded) hist.clear()
            val last = hist.lastOrNull()
            if (last != null && steps < last.second) {
                hist.clear()
                BoutLog.debug(ctx, "step counter restarted (phone reboot) - re-baselined")
            }
            val recent = Rules.recentSteps(hist, now, steps)
            if (hist.isNotEmpty() && recent >= Prefs.moveSteps(ctx)) {
                moved = true
                hist.clear()
                if (mode != Prefs.MODE_BREAK) BoutLog.debug(ctx, "movement: $recent steps in last ~5 min -> timer reset")
            }
            hist.add(Pair(now, steps))
            e.putString(Prefs.KEY_STEP_HISTORY, Rules.encodeHistory(Rules.prune(hist, now)))
            e.putLong(Prefs.KEY_LAST_STEPS, steps)
        }

        val win = Rules.window(now, Prefs.schedule(ctx))

        if (mode == Prefs.MODE_BREAK) {
            // Paused: nothing is counted, no reminders.
            wasActive = win.active
        } else {
            if (moved && win.active) {
                BoutLog.bout(ctx, Rules.sitStart(anchor, win), now, mode, nudged, "movement")
                anchor = now
                nudged = false
                count = 0
                snooze = 0L
                Notifier.cancelNudge(ctx)
            }
            if (!win.active) {
                if (wasActive) {
                    BoutLog.bout(ctx, anchor, now, mode, nudged, endedByFor(win.reason))
                    BoutLog.debug(ctx, "${reasonText(win.reason)} - paused until ${Rules.resumeText(win.nextStart, now)}")
                    Notifier.cancelNudge(ctx)
                }
                anchor = now
                nudged = false
                count = 0
                snooze = 0L
                wasActive = false
            } else {
                if (!wasActive) {
                    anchor = Rules.sitStart(anchor, win)
                    wasActive = true
                    BoutLog.debug(ctx, "work period started - sitting clock from ${Rules.hhmm(anchor)}")
                }
                val sitStart = Rules.sitStart(anchor, win)
                if (mode == Prefs.MODE_OFFICE && Rules.nudgeDue(
                        now, sitStart, nudged, lastNudge,
                        Prefs.thresholdMin(ctx), Prefs.repeatMin(ctx), snooze
                    )
                ) {
                    count += 1
                    val sittingMin = ((now - sitStart) / 60_000L).toInt()
                    Notifier.nudge(ctx, sittingMin, count)
                    nudged = true
                    lastNudge = now
                    snooze = 0L
                    BoutLog.debug(ctx, "reminder #$count after $sittingMin min sitting")
                }
            }
        }

        e.putLong(Prefs.KEY_ANCHOR_TIME, anchor)
            .putBoolean(Prefs.KEY_NUDGED, nudged)
            .putInt(Prefs.KEY_NUDGE_COUNT, count)
            .putLong(Prefs.KEY_LAST_NUDGE_TIME, lastNudge)
            .putBoolean(Prefs.KEY_WAS_ACTIVE, wasActive)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, snooze)
            .commit()

        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    private fun endedByFor(reason: String): String = when (reason) {
        Rules.REASON_LUNCH -> "lunch"
        Rules.REASON_OFF_TODAY -> "off_today"
        else -> "hours_end"
    }

    private fun reasonText(reason: String): String = when (reason) {
        Rules.REASON_LUNCH -> "lunch break"
        Rules.REASON_OFF_TODAY -> "off today"
        else -> "work hours over"
    }

    private fun label(mode: String): String = when (mode) {
        Prefs.MODE_MEETING -> "meeting"
        Prefs.MODE_BREAK -> "break"
        else -> "office"
    }

    private fun logModeEnd(ctx: Context, mode: String, start: Long, now: Long) {
        when (mode) {
            Prefs.MODE_MEETING -> BoutLog.bout(ctx, start, now, Prefs.MODE_MEETING, false, "meeting_end")
            Prefs.MODE_BREAK -> BoutLog.bout(ctx, start, now, Prefs.MODE_BREAK, false, "break_end")
        }
    }

    /** Set the next wake-up from the current state. Safe to call any time. */
    fun reschedule(ctx: Context) {
        if (!Prefs.isRunning(ctx)) {
            Scheduler.cancel(ctx)
            return
        }
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        val win = Rules.window(now, Prefs.schedule(ctx))
        val anchor = p.getLong(Prefs.KEY_ANCHOR_TIME, now)
        val next = Rules.nextWake(
            now, win,
            Prefs.mode(ctx) != Prefs.MODE_OFFICE, Prefs.modeUntil(ctx),
            if (win.active) Rules.sitStart(anchor, win) else now,
            p.getBoolean(Prefs.KEY_NUDGED, false), p.getLong(Prefs.KEY_LAST_NUDGE_TIME, 0L),
            Prefs.thresholdMin(ctx), Prefs.repeatMin(ctx), p.getLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
        )
        Scheduler.scheduleAt(ctx, next)
    }

    // ------------------------------------------------------------- actions

    /** Start button: always begin a fresh sitting period from zero, in Office mode. */
    fun startFresh(ctx: Context) {
        val now = System.currentTimeMillis()
        val win = Rules.window(now, Prefs.schedule(ctx))
        Prefs.get(ctx).edit()
            .putBoolean(Prefs.KEY_RUNNING, true)
            .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
            .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putLong(Prefs.KEY_LAST_NUDGE_TIME, 0L)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
            .putString(Prefs.KEY_STEP_HISTORY, "")
            .putBoolean(Prefs.KEY_WAS_ACTIVE, win.active)
            .commit()
        BoutLog.debug(ctx, "tracking started")
    }

    fun stopTracking(ctx: Context) {
        val now = System.currentTimeMillis()
        val p = Prefs.get(ctx)
        if (Prefs.isRunning(ctx)) {
            val mode = Prefs.mode(ctx)
            if (mode != Prefs.MODE_OFFICE) {
                logModeEnd(ctx, mode, p.getLong(Prefs.KEY_ANCHOR_TIME, now), now)
            } else {
                currentStart(ctx, now)?.let {
                    BoutLog.bout(ctx, it, now, mode, p.getBoolean(Prefs.KEY_NUDGED, false), "stopped")
                }
            }
        }
        p.edit()
            .putBoolean(Prefs.KEY_RUNNING, false)
            .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
            .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
            .commit()
        BoutLog.debug(ctx, "tracking stopped")
    }

    /** "I stood up" - covers the case where the phone stayed on the desk. */
    @Synchronized
    fun manualStood(ctx: Context) {
        if (Prefs.mode(ctx) == Prefs.MODE_BREAK) return
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        currentStart(ctx, now)?.let {
            BoutLog.bout(ctx, it, now, Prefs.mode(ctx), p.getBoolean(Prefs.KEY_NUDGED, false), "manual")
        }
        p.edit()
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
            .putString(Prefs.KEY_STEP_HISTORY, freshHistory(p, now))
            .commit()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "manual: marked as stood up")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** Snooze button: hold the next reminder for 10 minutes. The sitting clock keeps running. */
    @Synchronized
    fun snooze(ctx: Context) {
        if (Prefs.mode(ctx) != Prefs.MODE_OFFICE) return
        val until = System.currentTimeMillis() + Rules.SNOOZE_MS
        Prefs.get(ctx).edit().putLong(Prefs.KEY_SNOOZE_UNTIL, until).commit()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "snoozed until ${Rules.hhmm(until)}")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** Start Meeting or Break until the given time (also switches between the two). */
    @Synchronized
    fun startMode(ctx: Context, newMode: String, until: Long) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        val cur = Prefs.mode(ctx)
        if (cur != Prefs.MODE_OFFICE) {
            logModeEnd(ctx, cur, p.getLong(Prefs.KEY_ANCHOR_TIME, now), now)
        } else {
            currentStart(ctx, now)?.let {
                BoutLog.bout(
                    ctx, it, now, Prefs.MODE_OFFICE, p.getBoolean(Prefs.KEY_NUDGED, false),
                    if (newMode == Prefs.MODE_MEETING) "meeting" else "break"
                )
            }
        }
        p.edit()
            .putString(Prefs.KEY_MODE, newMode)
            .putLong(Prefs.KEY_MEETING_UNTIL, until)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
            .putString(Prefs.KEY_STEP_HISTORY, freshHistory(p, now))
            .commit()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "${label(newMode)} on until ${Rules.hhmm(until)}")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    @Synchronized
    fun extendMode(ctx: Context, minutes: Int) {
        if (Prefs.mode(ctx) == Prefs.MODE_OFFICE) return
        val now = System.currentTimeMillis()
        val until = maxOf(Prefs.modeUntil(ctx), now) + minutes * 60_000L
        Prefs.get(ctx).edit().putLong(Prefs.KEY_MEETING_UNTIL, until).commit()
        BoutLog.debug(ctx, "${label(Prefs.mode(ctx))} extended to ${Rules.hhmm(until)}")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** End Meeting or Break now; back to office with the sitting clock at zero. */
    @Synchronized
    fun endMode(ctx: Context) {
        val mode = Prefs.mode(ctx)
        if (mode == Prefs.MODE_OFFICE) return
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        logModeEnd(ctx, mode, p.getLong(Prefs.KEY_ANCHOR_TIME, now), now)
        p.edit()
            .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
            .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putLong(Prefs.KEY_SNOOZE_UNTIL, 0L)
            .putString(Prefs.KEY_STEP_HISTORY, freshHistory(p, now))
            .commit()
        BoutLog.debug(ctx, "${label(mode)} ended manually")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** "Off today": quiet until midnight. Turning it off again resumes right away. */
    fun setOffToday(ctx: Context, on: Boolean) {
        val now = System.currentTimeMillis()
        Prefs.get(ctx).edit()
            .putLong(Prefs.KEY_OFF_UNTIL, if (on) Rules.nextMidnight(now) else 0L)
            .commit()
        BoutLog.debug(ctx, if (on) "off today - quiet until midnight" else "off today cancelled")
        if (on) Notifier.cancelNudge(ctx)
        if (Prefs.isRunning(ctx)) tick(ctx)
    }

    /** Restart the movement window from the last known count (avoids a false "moved"). */
    private fun freshHistory(p: SharedPreferences, now: Long): String {
        val last = p.getLong(Prefs.KEY_LAST_STEPS, -1L)
        return if (last >= 0L) Rules.encodeHistory(listOf(Pair(now, last))) else ""
    }

    // ---------------------------------------------------------- read-only

    /** Start of the sitting period being counted now, or null when nothing is counting. */
    private fun currentStart(ctx: Context, now: Long): Long? {
        if (!Prefs.isRunning(ctx)) return null
        if (Prefs.mode(ctx) == Prefs.MODE_BREAK) return null
        val win = Rules.window(now, Prefs.schedule(ctx))
        if (!win.active) return null
        return Rules.sitStart(Prefs.get(ctx).getLong(Prefs.KEY_ANCHOR_TIME, now), win)
    }

    fun sittingMinutes(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val s = currentStart(ctx, now) ?: return 0L
        return ((now - s) / 60_000L).coerceAtLeast(0L)
    }

    fun modeMinutesLeft(ctx: Context): Long =
        ((Prefs.modeUntil(ctx) - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0L)

    fun scheduleText(ctx: Context): String = Rules.scheduleText(Prefs.schedule(ctx))

    fun todayStats(ctx: Context): DayStats {
        val now = System.currentTimeMillis()
        val start = currentStart(ctx, now)
        val ongoing = if (start != null) (now - maxOf(start, Rules.midnight(now))) / 1000L else 0L
        return Rules.dayStats(BoutLog.readBouts(ctx), Rules.dayKey(now), ongoing.coerceAtLeast(0L))
    }

    /** Sitting seconds for the last 7 days, oldest first, today last. */
    fun weekSitting(ctx: Context): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        val lines = BoutLog.readBouts(ctx)
        val today = todayStats(ctx).sittingSecs
        return (6 downTo 0).map { d ->
            val t = Rules.dayAt(now, java.util.TimeZone.getDefault(), -d, 12 * 60)
            val secs = if (d == 0) today else Rules.dayStats(lines, Rules.dayKey(t)).sittingSecs
            Pair(Rules.dayName(t), secs)
        }
    }

    /** One line for the ongoing notification. */
    fun statusLine(ctx: Context): String {
        val now = System.currentTimeMillis()
        when (Prefs.mode(ctx)) {
            Prefs.MODE_MEETING ->
                return "Meeting · ${modeMinutesLeft(ctx)} min left · reminders paused"
            Prefs.MODE_BREAK ->
                return "Break · ${modeMinutesLeft(ctx)} min left · everything paused"
        }
        val win = Rules.window(now, Prefs.schedule(ctx))
        if (!win.active) {
            val resume = Rules.resumeText(win.nextStart, now)
            return when (win.reason) {
                Rules.REASON_LUNCH -> "Lunch break · resumes $resume"
                Rules.REASON_OFF_TODAY -> "Off today · resumes $resume"
                else -> "Off hours · resumes $resume"
            }
        }
        val sitting = sittingMinutes(ctx)
        val threshold = Prefs.thresholdMin(ctx)
        val snooze = Prefs.snoozeUntil(ctx)
        return when {
            snooze > now -> "Sitting $sitting min · snoozed until ${Rules.hhmm(snooze)}"
            sitting >= threshold -> "Sitting $sitting min · time to stand up"
            else -> "Sitting $sitting min · reminder at $threshold min"
        }
    }
}
