package com.fiaz.movereminder

import android.content.Context
import android.content.SharedPreferences

/**
 * Single owner of live state. Everything the service needs to survive a
 * process kill lives here - never in the UI.
 */
object Prefs {
    private const val FILE = "move_reminder"

    const val KEY_RUNNING = "running"
    const val KEY_ANCHOR_STEPS = "anchor_steps"
    const val KEY_ANCHOR_TIME = "anchor_time"
    const val KEY_LAST_STEPS = "last_steps"
    const val KEY_MODE = "mode"
    /** End of the current Meeting or Break. */
    const val KEY_MEETING_UNTIL = "meeting_until"
    const val KEY_NUDGED = "nudged"
    const val KEY_THRESHOLD_MIN = "threshold_min"
    const val KEY_MOVE_STEPS = "move_steps"
    /** Old whole-hour active window, only read to migrate to the schedule below. */
    const val KEY_WINDOW_START = "window_start"
    const val KEY_WINDOW_END = "window_end"
    const val KEY_REPEAT_MIN = "repeat_min"
    const val KEY_LAST_NUDGE_TIME = "last_nudge_time"
    const val KEY_NUDGE_COUNT = "nudge_count"

    /** Recent step readings "time:steps;time:steps" for the movement window. */
    const val KEY_STEP_HISTORY = "step_history"
    /** Whether the last tick was inside an active period. */
    const val KEY_WAS_ACTIVE = "was_active"
    /** Whether the last tick got a step reading. */
    const val KEY_SENSOR_OK = "sensor_ok"
    /** When the next check is scheduled (for debugging). */
    const val KEY_NEXT_WAKE = "next_wake"
    const val KEY_ASKED_ACTIVITY = "asked_activity"

    // weekly schedule
    const val KEY_WORK_DAYS = "work_days"
    const val KEY_START_MIN = "start_min"
    const val KEY_END_MIN = "end_min"
    const val KEY_LUNCH_ON = "lunch_on"
    const val KEY_LUNCH_START = "lunch_start_min"
    const val KEY_LUNCH_END = "lunch_end_min"
    const val KEY_OFF_UNTIL = "off_until"

    const val KEY_SNOOZE_UNTIL = "snooze_until"
    const val KEY_LAST_MEETING_MIN = "last_meeting_min"
    const val KEY_LAST_BREAK_MIN = "last_break_min"

    const val MODE_OFFICE = Rules.MODE_OFFICE
    const val MODE_MEETING = Rules.MODE_MEETING
    const val MODE_BREAK = Rules.MODE_BREAK

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun thresholdMin(ctx: Context): Int = get(ctx).getInt(KEY_THRESHOLD_MIN, 25)
    fun moveSteps(ctx: Context): Int = get(ctx).getInt(KEY_MOVE_STEPS, 25)
    /** Minutes between repeat reminders within the same sitting period. 0 = remind once only. */
    fun repeatMin(ctx: Context): Int = get(ctx).getInt(KEY_REPEAT_MIN, 10)
    fun isRunning(ctx: Context): Boolean = get(ctx).getBoolean(KEY_RUNNING, false)
    fun mode(ctx: Context): String = get(ctx).getString(KEY_MODE, MODE_OFFICE) ?: MODE_OFFICE
    fun modeUntil(ctx: Context): Long = get(ctx).getLong(KEY_MEETING_UNTIL, 0L)
    fun anchorTime(ctx: Context): Long =
        get(ctx).getLong(KEY_ANCHOR_TIME, System.currentTimeMillis())

    fun workDays(ctx: Context): Int = get(ctx).getInt(KEY_WORK_DAYS, Rules.DEFAULT_DAYS)
    fun startMin(ctx: Context): Int =
        get(ctx).getInt(KEY_START_MIN, get(ctx).getInt(KEY_WINDOW_START, 7) * 60)
    fun endMin(ctx: Context): Int =
        get(ctx).getInt(KEY_END_MIN, get(ctx).getInt(KEY_WINDOW_END, 17) * 60)
    fun lunchOn(ctx: Context): Boolean = get(ctx).getBoolean(KEY_LUNCH_ON, false)
    fun lunchStart(ctx: Context): Int = get(ctx).getInt(KEY_LUNCH_START, 12 * 60)
    fun lunchEnd(ctx: Context): Int = get(ctx).getInt(KEY_LUNCH_END, 13 * 60)
    fun offUntil(ctx: Context): Long = get(ctx).getLong(KEY_OFF_UNTIL, 0L)
    fun snoozeUntil(ctx: Context): Long = get(ctx).getLong(KEY_SNOOZE_UNTIL, 0L)

    fun schedule(ctx: Context): Schedule = Schedule(
        workDays(ctx), startMin(ctx), endMin(ctx),
        lunchOn(ctx), lunchStart(ctx), lunchEnd(ctx), offUntil(ctx)
    )

    fun saveSettings(ctx: Context, thresholdMin: Int, moveSteps: Int, repeatMin: Int) {
        get(ctx).edit()
            .putInt(KEY_THRESHOLD_MIN, thresholdMin)
            .putInt(KEY_MOVE_STEPS, moveSteps)
            .putInt(KEY_REPEAT_MIN, repeatMin)
            .commit()
    }
}
