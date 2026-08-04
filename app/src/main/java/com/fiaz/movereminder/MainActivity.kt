package com.fiaz.movereminder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var startStop: Button
    private lateinit var eThreshold: EditText
    private lateinit var eSteps: EditText
    private lateinit var eStart: EditText
    private lateinit var eEnd: EditText

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannels(this)
        setContentView(buildUi())
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this)
        status.textSize = 18f
        status.setTypeface(Typeface.MONOSPACE)
        status.setPadding(0, 0, 0, pad)
        root.addView(status)

        startStop = button("Start tracking") {
            if (Prefs.isRunning(this)) {
                SedentaryService.stop(this)
            } else {
                saveSettings()
                SedentaryService.start(this)
            }
            refresh()
        }
        root.addView(startStop)

        root.addView(button("I stood up") {
            Engine.manualStood(this)
            toast("Marked. Timer reset.")
            refresh()
        })

        root.addView(header("Meeting mode"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        listOf(30, 60, 90, 120).forEach { m ->
            val b = Button(this)
            b.text = "${m}m"
            b.setOnClickListener {
                Engine.startMeeting(this, m)
                toast("Meeting mode for $m min")
                refresh()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(b, lp)
        }
        root.addView(row)
        root.addView(button("End meeting now") {
            Engine.endMeeting(this)
            refresh()
        })

        root.addView(header("Settings"))
        eThreshold = field("Sit threshold (minutes)", Prefs.thresholdMin(this).toString(), root)
        eSteps = field("Steps that count as movement", Prefs.moveSteps(this).toString(), root)
        eStart = field("Active from (hour 0-23)", Prefs.windowStart(this).toString(), root)
        eEnd = field("Active until (hour 0-23)", Prefs.windowEnd(this).toString(), root)
        root.addView(button("Save settings") {
            saveSettings()
            toast("Saved")
        })

        root.addView(header("Battery setup (required)"))
        root.addView(note("One UI will kill this service unless you exempt it. Open the settings below and set battery usage to Unrestricted, and add the app to Never sleeping apps."))
        root.addView(button("Open app settings") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("Battery optimisation list") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Not available on this device")
            }
        })

        root.addView(header("History and log"))
        root.addView(button("Refresh log") { refresh() })
        logView = TextView(this)
        logView.textSize = 11f
        logView.setTypeface(Typeface.MONOSPACE)
        logView.setTextColor(Color.DKGRAY)
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun header(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 15f
        tv.setTypeface(null, Typeface.BOLD)
        val p = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(0, p * 2, 0, p / 2)
        return tv
    }

    private fun note(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 12f
        tv.setTextColor(Color.DKGRAY)
        return tv
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun field(label: String, value: String, parent: LinearLayout): EditText {
        parent.addView(note(label))
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value)
        e.gravity = Gravity.START
        parent.addView(e)
        return e
    }

    // ------------------------------------------------------------ actions

    private fun saveSettings() {
        val th = eThreshold.text.toString().toIntOrNull() ?: 25
        val st = eSteps.text.toString().toIntOrNull() ?: 25
        val ws = eStart.text.toString().toIntOrNull() ?: 7
        val we = eEnd.text.toString().toIntOrNull() ?: 17
        Prefs.saveSettings(this, th.coerceIn(5, 240), st.coerceIn(5, 500), ws.coerceIn(0, 23), we.coerceIn(0, 23))
    }

    private fun refresh() {
        val running = Prefs.isRunning(this)
        startStop.text = if (running) "Stop tracking" else "Start tracking"

        val sb = StringBuilder()
        sb.append(if (running) "TRACKING\n" else "STOPPED\n")
        sb.append("Mode      : ${Prefs.mode(this)}\n")
        if (Prefs.mode(this) == Prefs.MODE_MEETING) {
            val left = (Prefs.meetingUntil(this) - System.currentTimeMillis()) / 60000L
            sb.append("Meeting   : ${if (left > 0) left else 0} min left\n")
        }
        sb.append("Sitting   : ${Engine.sittingMinutes(this)} min\n")
        sb.append("Threshold : ${Prefs.thresholdMin(this)} min\n")
        status.text = sb.toString()

        val bouts = BoutLog.readBouts(this).takeLast(15).reversed()
        val log = BoutLog.readDebug(this).takeLast(25).reversed()
        logView.text = "--- last bouts (start,end,secs,mode,nudged,endedBy) ---\n" +
                bouts.joinToString("\n") +
                "\n\n--- engine log ---\n" +
                log.joinToString("\n")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACTIVITY_RECOGNITION)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
}
