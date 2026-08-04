package com.fiaz.movereminder

import android.content.Context
import java.util.Calendar

/**
 * The whole decision logic. Runs once every tick (5 min).
 *
 *   delta = currentSteps - anchorSteps
 *   delta >= moveSteps            -> he moved, close the bout, re-anchor
 *   else if sitting >= threshold  -> nudge, then repeat every repeatMin
 *                                    minutes until he moves or dismisses it
 */
object Engine {

    fun tick(ctx: Context, done: (() -> Unit)? = null) {
        StepReader.read(ctx) { steps ->
            try {
                evaluate(ctx, steps)
            } catch (e: Exception) {
                BoutLog.debug(ctx, "tick error: ${e.message}")
            }
            done?.invoke()
        }
    }

    private fun evaluate(ctx: Context, stepsOrNull: Long?) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()

        if (stepsOrNull == null) {
            BoutLog.debug(ctx, "tick: no step reading (sensor missing or timed out)")
            return
        }
        val steps = stepsOrNull

        var anchorSteps = p.getLong(Prefs.KEY_ANCHOR_STEPS, -1L)
        var anchorTime = p.getLong(Prefs.KEY_ANCHOR_TIME, now)

        // First run, or the counter reset because the phone rebooted.
        if (anchorSteps < 0L || steps < anchorSteps) {
            p.edit()
                .putLong(Prefs.KEY_ANCHOR_STEPS, steps)
                .putLong(Prefs.KEY_ANCHOR_TIME, now)
                .putLong(Prefs.KEY_LAST_STEPS, steps)
                .putBoolean(Prefs.KEY_NUDGED, false)
                .putInt(Prefs.KEY_NUDGE_COUNT, 0)
                .apply()
            BoutLog.debug(ctx, "anchor set (first run or reboot), steps=$steps")
            return
        }

        // Meeting mode auto-expiry. Re-anchor to NOW so he does not get an
        // instant nudge for the meeting he just sat through.
        var mode = Prefs.mode(ctx)
        if (mode == Prefs.MODE_MEETING && now >= Prefs.meetingUntil(ctx)) {
            BoutLog.bout(ctx, anchorTime, now, Prefs.MODE_MEETING, false, "meeting_end")
            mode = Prefs.MODE_OFFICE
            anchorSteps = steps
            anchorTime = now
            p.edit()
                .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
                .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
                .putLong(Prefs.KEY_ANCHOR_STEPS, steps)
                .putLong(Prefs.KEY_ANCHOR_TIME, now)
                .putBoolean(Prefs.KEY_NUDGED, false)
                .putInt(Prefs.KEY_NUDGE_COUNT, 0)
                .apply()
            BoutLog.debug(ctx, "meeting expired -> OFFICE, re-anchored")
        }

        val delta = steps - anchorSteps
        val nudged = p.getBoolean(Prefs.KEY_NUDGED, false)

        if (delta >= Prefs.moveSteps(ctx)) {
            BoutLog.bout(ctx, anchorTime, now, mode, nudged, "movement")
            p.edit()
                .putLong(Prefs.KEY_ANCHOR_STEPS, steps)
                .putLong(Prefs.KEY_ANCHOR_TIME, now)
                .putLong(Prefs.KEY_LAST_STEPS, steps)
                .putBoolean(Prefs.KEY_NUDGED, false)
                .putInt(Prefs.KEY_NUDGE_COUNT, 0)
                .apply()
            Notifier.cancelNudge(ctx)
            BoutLog.debug(ctx, "movement: delta=$delta steps -> bout closed")
        } else {
            val sittingMin = (now - anchorTime) / 60000L
            val repeatMin = Prefs.repeatMin(ctx)
            val lastNudgeTime = p.getLong(Prefs.KEY_LAST_NUDGE_TIME, 0L)
            val sinceLastNudgeMin = (now - lastNudgeTime) / 60000L

            val dueForFirstNudge = !nudged && sittingMin >= Prefs.thresholdMin(ctx)
            val dueForRepeatNudge = nudged && repeatMin > 0 && sinceLastNudgeMin >= repeatMin

            if (mode == Prefs.MODE_OFFICE &&
                (dueForFirstNudge || dueForRepeatNudge) &&
                inActiveWindow(ctx)
            ) {
                val count = p.getInt(Prefs.KEY_NUDGE_COUNT, 0) + 1
                Notifier.nudge(ctx, sittingMin.toInt(), count)
                p.edit()
                    .putBoolean(Prefs.KEY_NUDGED, true)
                    .putLong(Prefs.KEY_LAST_NUDGE_TIME, now)
                    .putInt(Prefs.KEY_NUDGE_COUNT, count)
                    .apply()
                BoutLog.debug(ctx, "nudge #$count fired after ${sittingMin} min")
            }
            p.edit().putLong(Prefs.KEY_LAST_STEPS, steps).apply()
        }
        SedentaryService.refreshStatus(ctx)
    }

    /** "I stood up" button - covers the case where the phone stayed on the desk. */
    fun manualStood(ctx: Context) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        BoutLog.bout(
            ctx, Prefs.anchorTime(ctx), now, Prefs.mode(ctx),
            p.getBoolean(Prefs.KEY_NUDGED, false), "manual"
        )
        p.edit()
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putLong(Prefs.KEY_ANCHOR_STEPS, p.getLong(Prefs.KEY_LAST_STEPS, 0L))
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .apply()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "manual: marked as stood up")
        SedentaryService.refreshStatus(ctx)
    }

    fun startMeeting(ctx: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        Prefs.get(ctx).edit()
            .putString(Prefs.KEY_MODE, Prefs.MODE_MEETING)
            .putLong(Prefs.KEY_MEETING_UNTIL, now + minutes * 60_000L)
            .apply()
        Notifier.cancelNudge(ctx)
        BoutLog.debug(ctx, "meeting mode ON for $minutes min")
        SedentaryService.refreshStatus(ctx)
    }

    fun endMeeting(ctx: Context) {
        val now = System.currentTimeMillis()
        Prefs.get(ctx).edit()
            .putString(Prefs.KEY_MODE, Prefs.MODE_OFFICE)
            .putLong(Prefs.KEY_MEETING_UNTIL, 0L)
            .putLong(Prefs.KEY_ANCHOR_TIME, now)
            .putBoolean(Prefs.KEY_NUDGED, false)
            .putInt(Prefs.KEY_NUDGE_COUNT, 0)
            .apply()
        BoutLog.debug(ctx, "meeting ended manually")
        SedentaryService.refreshStatus(ctx)
    }

    fun sittingMinutes(ctx: Context): Long =
        (System.currentTimeMillis() - Prefs.anchorTime(ctx)) / 60000L

    private fun inActiveWindow(ctx: Context): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = Prefs.windowStart(ctx)
        val e = Prefs.windowEnd(ctx)
        return if (s <= e) hour in s until e else (hour >= s || hour < e)
    }
}
