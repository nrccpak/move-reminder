package com.fiaz.movereminder

import android.content.Context

/**
 * All decision logic. Runs on every tick (every 5 min, or exactly when a
 * reminder falls due). The pure timing rules live in Rules.kt.
 *
 *  - Sitting time only counts inside active hours and restarts when they begin.
 *  - "Moved" means at least moveSteps steps within the last ~5 minutes.
 *  - First reminder at the threshold, then every repeatMin minutes until you move,
 *    tap "I stood up", or start a meeting.
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

    private fun window(ctx: Context, now: Long): ActiveHours =
        Rules.window(now, Prefs.windowStart(ctx), Prefs.windowEnd(ctx))

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
        val e = p.edit()

        // 1. Meeting timer ran out: back to office, sitting clock restarts now.
        if (mode == Prefs.MODE_MEETING && now >= Prefs.meetingUntil(ctx)) {
            BoutLog.bout(ctx, anchor, now, Prefs.MODE_MEETING, false, "meeting_end")
            mode = Prefs.MODE_OFFICE
            anchor = now
            nudged = false
            count = 0
            e.putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE).putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            BoutLog.debug(ctx, "meeting time over -> office mode")
        }

        // 2. Movement: steps within the last ~5 minutes.
        var moved = false
        if (steps == null) {
            e.putBoolean(Prefs.KEY_SENSOR_OK, false)
            BoutLog.debug(ctx, "no step reading - reminders are time-only")
        } else {
            e.putBoolean(Prefs.KEY_SENSOR_OK, true)
            val hist = Rules.parseHistory(p.getString(Prefs.KEY_STEP_HISTORY, "") ?: "")
            val last = hist.lastOrNull()
            if (last != null && steps < last.second) {
                hist.clear()
                BoutLog.debug(ctx, "step counter restarted (phone reboot) - re-baselined")
            }
            val recent = Rules.recentSteps(hist, now, steps)
            if (hist.isNotEmpty() && recent >= Prefs.moveSteps(ctx)) {
                moved = true
                hist.clear()
                BoutLog.debug(ctx, "movement: $recent steps in last ~5 min -> timer reset")
            }
            hist.add(Pair(now, steps))
            e.putString(Prefs.KEY_STEP_HISTORY, Rules.encodeHistory(Rules.prune(hist, now)))
            e.putLong(Prefs.KEY_LAST_STEPS, steps)
        }

        val win = window(ctx, now)
        if (moved) {
            BoutLog.bout(ctx, if (win.active) Rules.sitStart(anchor, win) else anchor, now, mode, nudged, "movement")
            anchor = now
            nudged = false
            count = 0
            Notifier.cancelNudge(ctx)
        }

        // 3. Active hours, then the reminder itself.
        if (!win.active) {
            if (wasActive) {
                BoutLog.bout(ctx, anchor, now, mode, nudged, "hours_end")
                BoutLog.debug(ctx, "active hours over - paused until ${Rules.hhmm(win.nextStart)}")
                Notifier.cancelNudge(ctx)
            }
            anchor = now
            nudged = false
            count = 0
            wasActive = false
        } else {
            if (!wasActive) {
                anchor = Rules.sitStart(anchor, win)
                wasActive = true
                BoutLog.debug(ctx, "active hours started - sitting clock from ${Rules.hhmm(anchor)}")
            }
            val sitStart = Rules.sitStart(anchor, win)
            if (mode == Prefs.MODE_OFFICE &&
                Rules.nudgeDue(now, sitStart, nudged, lastNudge, Prefs.thresholdMin(ctx), Prefs.repeatMin(ctx))
            ) {
                count += 1
                val sittingMin = ((now - sitStart) / 60_000L).toInt()
                Notifier.nudge(ctx, sittingMin, count)
                nudged = true
                lastNudge = now
                BoutLog.debug(ctx, "reminder #$count after $sittingMin min sitting")
            }
        }

        e.putLong(Prefs.KEY_ANCHOR_TIME, anchor)
            .putBoolean(Prefs.KEY_NUDGED, nudged)
            .putInt(Prefs.KEY_NUDGE_COUNT, count)
            .putLong(Prefs.KEY_LAST_NUDGE_TIME, lastNudge)
            .putBoolean(Prefs.KEY_WAS_ACTIVE, wasActive)
            .commit()

        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** Set the next wake-up from the current state. Safe to call any time. */
    fun reschedule(ctx: Context) {
        if (!Prefs.isRunning(ctx)) {
            Scheduler.cancel(ctx)
            return
        }
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        val win = window(ctx, now)
        val anchor = p.getLong(Prefs.KEY_ANCHOR_TIME, now)
        val next = Rules.nextWake(
            now, win,
            Prefs.mode(ctx) == Prefs.MODE_MEETING, Prefs.meetingUntil(ctx),
            if (win.active) Rules.sitStart(anchor, win) else now,
            p.getBoolean(Prefs.KEY_NUDGED, false), p.getLong(Prefs.KEY_LAST_NUDGE_TIME, 0L),
            Prefs.thresholdMin(ctx), Prefs.repeatMin(ctx)
        )
        Scheduler.scheduleAt(ctx, next)
    }

    // ------------------------------------------------------------- actions

    /** Start button: always begin a fresh sitting period from zero. */
    fun startFresh(ctx: Context) {
        val now = System.currentTimeMillis()
        val win = window(ctx, now)
        Prefs.get(ctx).edit()
            .putBoolean(Prefs.KEY_RUNNING, true)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putLong(Prefs.KEY_LAST_NUDGE_TIME, 0L)
            .putString(Prefs.KEY_STEP_HISTORY, "")
            .putBoolean(Prefs.KEY_WAS_ACTIVE, win.active)
            .commit()
        BoutLog.debug(ctx, "tracking started")
    }

    fun stopTracking(ctx: Context) {
        if (Prefs.isRunning(ctx)) {
            val now = System.currentTimeMillis()
            val p = Prefs.get(ctx)
            currentStart(ctx, now)?.let {
                BoutLog.bout(ctx, it, now, Prefs.mode(ctx), p.getBoolean(Prefs.KEY_NUDGED, false), "stopped")
            }
        }
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_RUNNING, false).commit()
        BoutLog.debug(ctx, "tracking stopped")
    }

    /** "I stood up" - covers the case where the phone stayed on the desk. */
    @Synchronized
    fun manualStood(ctx: Context) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        currentStart(ctx, now)?.let {
            BoutLog.bout(ctx, it, now, Prefs.mode(ctx), p.getBoolean(Prefs.KEY_NUDGED, false), "manual")
        }
        p.edit()
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putString(Prefs.KEY_STEP_HISTORY, freshHistory(p, now))
            .commit()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "manual: marked as stood up")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    @Synchronized
    fun startMeeting(ctx: Context, minutes: Int) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        val e = p.edit()
        if (Prefs.mode(ctx) == Prefs.MODE_OFFICE) {
            currentStart(ctx, now)?.let {
                BoutLog.bout(ctx, it, now, Prefs.MODE_OFFICE, p.getBoolean(Prefs.KEY_NUDGED, false), "meeting")
            }
            e.putLong(Prefs.KEY_ANCHOR_TIME, now)
        }
        e.putString(Prefs.KEY_MODE, Prefs.MODE_MEETING)
            .putLong(Prefs.KEY_MEETING_UNTIL, now + minutes * 60_000L)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .commit()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "meeting mode on for $minutes min")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    @Synchronized
    fun endMeeting(ctx: Context) {
        if (Prefs.mode(ctx) != Prefs.MODE_MEETING) return
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        BoutLog.bout(ctx, p.getLong(Prefs.KEY_ANCHOR_TIME, now), now, Prefs.MODE_MEETING, false, "meeting_end")
        p.edit()
            .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
            .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .putString(Prefs.KEY_STEP_HISTORY, freshHistory(p, now))
            .commit()
        BoutLog.debug(ctx, "meeting ended manually")
        reschedule(ctx)
        SedentaryService.refreshStatus(ctx)
    }

    /** Restart the movement window from the last known count (avoids a false "moved"). */
    private fun freshHistory(p: android.content.SharedPreferences, now: Long): String {
        val last = p.getLong(Prefs.KEY_LAST_STEPS, -1L)
        return if (last >= 0L) Rules.encodeHistory(listOf(Pair(now, last))) else ""
    }

    // ---------------------------------------------------------- read-only

    /** Start of the sitting period being counted now, or null when nothing is counting. */
    private fun currentStart(ctx: Context, now: Long): Long? {
        if (!Prefs.isRunning(ctx)) return null
        val win = window(ctx, now)
        if (!win.active) return null
        return Rules.sitStart(Prefs.get(ctx).getLong(Prefs.KEY_ANCHOR_TIME, now), win)
    }

    fun sittingMinutes(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val s = currentStart(ctx, now) ?: return 0L
        return ((now - s) / 60_000L).coerceAtLeast(0L)
    }

    fun isActiveNow(ctx: Context): Boolean = window(ctx, System.currentTimeMillis()).active

    fun resumesAt(ctx: Context): String = Rules.hhmm(window(ctx, System.currentTimeMillis()).nextStart)

    fun meetingMinutesLeft(ctx: Context): Long =
        ((Prefs.meetingUntil(ctx) - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0L)

    /** One line for the ongoing notification. */
    fun statusLine(ctx: Context): String {
        if (Prefs.mode(ctx) == Prefs.MODE_MEETING) {
            return "Meeting · ${meetingMinutesLeft(ctx)} min left · reminders paused"
        }
        if (!isActiveNow(ctx)) return "Outside active hours · resumes ${resumesAt(ctx)}"
        val sitting = sittingMinutes(ctx)
        val threshold = Prefs.thresholdMin(ctx)
        return if (sitting >= threshold) "Sitting $sitting min · time to stand up"
        else "Sitting $sitting min · reminder at $threshold min"
    }
}
