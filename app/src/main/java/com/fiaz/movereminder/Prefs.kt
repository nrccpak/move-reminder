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
    const val KEY_MEETING_UNTIL = "meeting_until"
    const val KEY_NUDGED = "nudged"
    const val KEY_THRESHOLD_MIN = "threshold_min"
    const val KEY_MOVE_STEPS = "move_steps"
    const val KEY_WINDOW_START = "window_start"
    const val KEY_WINDOW_END = "window_end"
    const val KEY_REPEAT_MIN = "repeat_min"
    const val KEY_LAST_NUDGE_TIME = "last_nudge_time"
    const val KEY_NUDGE_COUNT = "nudge_count"

    const val MODE_OFFICE = "OFFICE"
    const val MODE_MEETING = "MEETING"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun thresholdMin(ctx: Context): Int = get(ctx).getInt(KEY_THRESHOLD_MIN, 25)
    fun moveSteps(ctx: Context): Int = get(ctx).getInt(KEY_MOVE_STEPS, 25)
    fun windowStart(ctx: Context): Int = get(ctx).getInt(KEY_WINDOW_START, 7)
    fun windowEnd(ctx: Context): Int = get(ctx).getInt(KEY_WINDOW_END, 17)
    /** Minutes between repeat nudges within the same bout. 0 = fire once only (old behaviour). */
    fun repeatMin(ctx: Context): Int = get(ctx).getInt(KEY_REPEAT_MIN, 10)
    fun isRunning(ctx: Context): Boolean = get(ctx).getBoolean(KEY_RUNNING, false)
    fun mode(ctx: Context): String = get(ctx).getString(KEY_MODE, MODE_OFFICE) ?: MODE_OFFICE
    fun meetingUntil(ctx: Context): Long = get(ctx).getLong(KEY_MEETING_UNTIL, 0L)
    fun anchorTime(ctx: Context): Long =
        get(ctx).getLong(KEY_ANCHOR_TIME, System.currentTimeMillis())

    fun saveSettings(
        ctx: Context, thresholdMin: Int, moveSteps: Int, wStart: Int, wEnd: Int, repeatMin: Int
    ) {
        get(ctx).edit()
            .putInt(KEY_THRESHOLD_MIN, thresholdMin)
            .putInt(KEY_MOVE_STEPS, moveSteps)
            .putInt(KEY_WINDOW_START, wStart)
            .putInt(KEY_WINDOW_END, wEnd)
            .putInt(KEY_REPEAT_MIN, repeatMin)
            .apply()
    }
}
