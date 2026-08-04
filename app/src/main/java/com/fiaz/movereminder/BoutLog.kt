package com.fiaz.movereminder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flat append-only files. No database, no Room, no annotation processing.
 * bouts.csv  - one completed sitting bout per line
 * debug.log  - what the engine decided, readable in-app (no adb needed)
 */
object BoutLog {
    private const val BOUTS = "bouts.csv"
    private const val DEBUG = "debug.log"
    private const val MAX_LINES = 4000

    private fun stamp(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    fun bout(ctx: Context, startTs: Long, endTs: Long, mode: String, nudged: Boolean, endedBy: String) {
        val secs = (endTs - startTs) / 1000
        append(ctx, BOUTS, "${stamp(startTs)},${stamp(endTs)},$secs,$mode,$nudged,$endedBy")
    }

    fun debug(ctx: Context, msg: String) {
        append(ctx, DEBUG, "${stamp(System.currentTimeMillis())}  $msg")
    }

    fun readBouts(ctx: Context): List<String> = read(ctx, BOUTS)
    fun readDebug(ctx: Context): List<String> = read(ctx, DEBUG)

    fun boutsFile(ctx: Context): File = File(ctx.filesDir, BOUTS)

    private fun read(ctx: Context, name: String): List<String> {
        val f = File(ctx.filesDir, name)
        return try {
            if (f.exists()) f.readLines() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun append(ctx: Context, name: String, line: String) {
        val f = File(ctx.filesDir, name)
        try {
            f.appendText(line + "\n")
            trim(f)
        } catch (e: Exception) {
            // logging must never crash the service
        }
    }

    private fun trim(f: File) {
        try {
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES / 2).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
        }
    }
}
