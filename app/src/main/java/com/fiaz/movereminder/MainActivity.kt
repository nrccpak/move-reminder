package com.fiaz.movereminder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale

// ------------------------------------------------------------------ palette

private val BG = Color.parseColor("#F2F5F3")
private val CARD = Color.parseColor("#FFFFFF")
private val LINE = Color.parseColor("#E1E8E5")
private val INK = Color.parseColor("#0E1B18")
private val MUTED = Color.parseColor("#5F716C")
private val ACCENT = Color.parseColor("#17886B")
private val AMBER = Color.parseColor("#E39A1E")
private val RED = Color.parseColor("#B8452F")
private val BLUE = Color.parseColor("#2F6FDB")
private val TRACK = Color.parseColor("#E6EEEA")
private val FIELD = Color.parseColor("#F7FAF8")
private val FIELD_LINE = Color.parseColor("#D5E0DB")
private val SOFT = Color.parseColor("#E4ECE8")
private val SOFT_TEXT = Color.parseColor("#3B4D48")
private val OUTLINE = Color.parseColor("#CBD8D3")
private val AMBER_BG = Color.parseColor("#FFF4DE")
private val AMBER_LINE = Color.parseColor("#EBD39C")
private val AMBER_INK = Color.parseColor("#4A3000")
private val BLUE_BG = Color.parseColor("#E8F0FD")
private val BLUE_INK = Color.parseColor("#1B3F85")
private val LOG_BG = Color.parseColor("#14211E")
private val LOG_FG = Color.parseColor("#CFE3DC")
private val LOG_TS = Color.parseColor("#7FA395")

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ------------------------------------------------------------ custom views

/** Progress ring: a track circle with a rounded arc on top. */
private class RingView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    var ringColor = ACCENT
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        track.style = Paint.Style.STROKE
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val sw = w * 10f / 140f
        track.strokeWidth = sw
        arc.strokeWidth = sw
        track.color = TRACK
        arc.color = ringColor
        val r = RectF(sw / 2f, sw / 2f, w - sw / 2f, w - sw / 2f)
        c.drawArc(r, 0f, 360f, false, track)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) c.drawArc(r, -90f, 360f * p, false, arc)
    }
}

/** Three simple stroke icons for the bottom bar: 0 home, 1 sliders, 2 clock. */
private class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    var tint = MUTED
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 2f
    }

    override fun onDraw(c: Canvas) {
        p.color = tint
        val s = width / 24f
        c.save()
        c.scale(s, s)
        when (kind) {
            0 -> {
                val path = Path()
                path.moveTo(4f, 11f); path.lineTo(12f, 4f); path.lineTo(20f, 11f)
                path.moveTo(6f, 10f); path.lineTo(6f, 20f); path.lineTo(18f, 20f); path.lineTo(18f, 10f)
                c.drawPath(path, p)
            }
            1 -> {
                c.drawLine(4f, 7f, 14f, 7f, p)
                c.drawLine(18f, 7f, 20f, 7f, p)
                c.drawCircle(16f, 7f, 2f, p)
                c.drawLine(4f, 17f, 6f, 17f, p)
                c.drawLine(10f, 17f, 20f, 17f, p)
                c.drawCircle(8f, 17f, 2f, p)
            }
            else -> {
                c.drawCircle(12f, 12f, 8f, p)
                val path = Path()
                path.moveTo(12f, 8f); path.lineTo(12f, 12f); path.lineTo(15f, 14f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}

/** Lays children out left to right and wraps to a new row when out of width. */
private class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else size - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            x += c.measuredWidth + hGap
            widest = maxOf(widest, x - hGap)
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val w = if (mode == MeasureSpec.EXACTLY) size else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

// ----------------------------------------------------------------- activity

class MainActivity : Activity() {

    private val REG: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val MONO: Typeface = Typeface.MONOSPACE

    // home
    private lateinit var tvSub: TextView
    private lateinit var chipText: TextView
    private lateinit var chipDot: View
    private lateinit var ring: RingView
    private lateinit var ringNum: TextView
    private lateinit var ringLabel: TextView
    private lateinit var vMode: TextView
    private lateinit var vThreshold: TextView
    private lateinit var vRepeat: TextView
    private lateinit var banner: TextView
    private lateinit var startStop: TextView
    private lateinit var ringCaption: TextView
    private lateinit var endMeetingBtn: TextView
    private lateinit var setupWarn: TextView
    private lateinit var setupBox: LinearLayout
    private var setupSig = ""

    // settings
    private lateinit var eThreshold: EditText
    private lateinit var eSteps: EditText
    private lateinit var eStart: EditText
    private lateinit var eEnd: EditText
    private lateinit var eRepeat: EditText

    // history
    private lateinit var boutList: LinearLayout
    private lateinit var logBox: LinearLayout
    private var historySig = 0

    // navigation
    private lateinit var screens: List<View>
    private val navIcons = ArrayList<IconView>()
    private val navLabels = ArrayList<TextView>()
    private var tab = 0

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

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = BG
            window.navigationBarColor = CARD
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 27) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }

        setContentView(buildUi())
        showTab(0)
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        selfHeal()
        setupSig = ""
        ui.post(ticker)
    }

    /** If tracking should be on but Android stopped it (update, kill), start it again. */
    private fun selfHeal() {
        if (!Prefs.isRunning(this)) return
        if (!SedentaryService.alive) SedentaryService.start(this) else Engine.reschedule(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setupSig = ""
        if (Prefs.isRunning(this) && !SedentaryService.alive && Setup.activityOk(this)) {
            SedentaryService.start(this)
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    private fun ripple(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(0x22000000),
            shape(fill, radiusDp, strokeColor, strokeDp),
            shape(Color.WHITE, radiusDp)
        )

    private fun lp(
        w: Int = MATCH, h: Int = WRAP,
        top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0,
        weight: Float = 0f
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom))
        return p
    }

    private fun text(s: String, sp: Float, color: Int, tf: Typeface = REG): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        t.typeface = tf
        return t
    }

    /** Small uppercase section label. */
    private fun label(s: String): TextView {
        val t = text(s.uppercase(Locale.US), 12f, MUTED, BOLD)
        t.letterSpacing = 0.08f
        return t
    }

    private fun card(radiusDp: Int = 28, fill: Int = CARD, stroke: Int = LINE): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = shape(fill, radiusDp, stroke, 1)
        c.setPadding(dp(22), dp(22), dp(22), dp(22))
        return c
    }

    private fun button(
        label: String, fill: Int, textColor: Int, heightDp: Int, radiusDp: Int,
        stroke: Int = 0, strokeDp: Int = 0, onClick: () -> Unit
    ): TextView {
        val t = text(label, 16f, textColor, BOLD)
        t.gravity = Gravity.CENTER
        t.background = ripple(fill, radiusDp, stroke, strokeDp)
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        t.layoutParams = lp(h = dp(heightDp))
        return t
    }

    private fun column(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(20), dp(24), dp(20), dp(24))
        return c
    }

    private fun scroller(content: View): ScrollView {
        val s = ScrollView(this)
        s.isVerticalScrollBarEnabled = false
        s.addView(content)
        return s
    }

    private fun titleBlock(title: String, sub: String?): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 24f, INK, BOLD))
        if (sub != null) {
            val s = text(sub, 14f, MUTED)
            s.setPadding(0, dp(2), 0, 0)
            col.addView(s)
        }
        return col
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val frame = FrameLayout(this)
        screens = listOf(buildHome(), buildSettings(), buildHistory())
        screens.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(frame, lp(h = 0, weight = 1f))

        val line = View(this)
        line.setBackgroundColor(LINE)
        root.addView(line, lp(h = dp(1)))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(CARD)
        val names = listOf("Home", "Settings", "History")
        for (i in 0..2) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.isClickable = true
            item.isFocusable = true
            item.contentDescription = names[i]
            item.setOnClickListener { showTab(i) }
            val icon = IconView(this, i)
            val lbl = text(names[i], 12f, MUTED, MED)
            lbl.setPadding(0, dp(4), 0, 0)
            lbl.gravity = Gravity.CENTER
            lbl.setSingleLine(true)
            item.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(lbl)
            navIcons.add(icon)
            navLabels.add(lbl)
            nav.addView(item, lp(w = 0, h = dp(64), weight = 1f))
        }
        root.addView(nav, lp())

        // Edge-to-edge on Android 15: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            root.setPadding(0, top, 0, 0)
            nav.setPadding(0, 0, 0, bottom)
            insets
        }
        return root
    }

    private fun showTab(i: Int) {
        tab = i
        screens.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        for (idx in 0..2) {
            val on = idx == i
            navIcons[idx].tint = if (on) ACCENT else MUTED
            navLabels[idx].setTextColor(if (on) ACCENT else MUTED)
            navLabels[idx].typeface = if (on) BOLD else MED
        }
        historySig = 0
        refresh()
    }

    // ---------------------------------------------------------------- home

    private fun stat(name: String, value: TextView): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        val l = label(name)
        l.textSize = 11f
        l.gravity = Gravity.CENTER
        l.setSingleLine(true)
        c.addView(l, lp())
        value.gravity = Gravity.CENTER
        value.setSingleLine(true)
        value.ellipsize = TextUtils.TruncateAt.END
        c.addView(value, lp(top = 4))
        return c
    }

    private fun buildHome(): View {
        val col = column()

        // header: title + status chip on one row, subtitle full width below
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = text("Move Reminder", 24f, INK, BOLD)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        head.addView(title, lp(w = 0, weight = 1f, right = 12))

        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.background = shape(CARD, 999, LINE, 1)
        chip.setPadding(dp(12), dp(7), dp(14), dp(7))
        chipDot = View(this)
        chip.addView(chipDot, lp(w = dp(8), h = dp(8), right = 7))
        chipText = text("Stopped", 13f, INK, BOLD)
        chipText.setSingleLine(true)
        chip.addView(chipText, lp(w = WRAP))
        head.addView(chip, lp(w = WRAP))
        col.addView(head, lp())

        tvSub = text("", 14f, MUTED)
        tvSub.setSingleLine(true)
        tvSub.ellipsize = TextUtils.TruncateAt.END
        col.addView(tvSub, lp(top = 4))

        // hero card: big centred ring, caption, then three stats in a row
        val hero = card()
        hero.gravity = Gravity.CENTER_HORIZONTAL
        hero.setPadding(dp(20), dp(26), dp(20), dp(20))

        val ringBox = FrameLayout(this)
        ring = RingView(this)
        ringBox.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER
        ringNum = text("0", 52f, INK, BOLD)
        ringNum.gravity = Gravity.CENTER
        ringNum.setSingleLine(true)
        ringNum.includeFontPadding = false
        ringLabel = text("min sitting", 14f, MUTED, MED)
        ringLabel.gravity = Gravity.CENTER
        ringLabel.setSingleLine(true)
        center.addView(ringNum, lp(w = WRAP))
        center.addView(ringLabel, lp(w = WRAP, top = 4))
        ringBox.addView(center, FrameLayout.LayoutParams(MATCH, MATCH))
        hero.addView(ringBox, lp(w = dp(196), h = dp(196)))

        ringCaption = text("", 14f, MUTED, MED)
        ringCaption.gravity = Gravity.CENTER
        hero.addView(ringCaption, lp(top = 14))

        val divider = View(this)
        divider.setBackgroundColor(LINE)
        hero.addView(divider, lp(h = dp(1), top = 20))

        vMode = text("", 17f, INK, BOLD)
        vThreshold = text("", 17f, INK, BOLD)
        vRepeat = text("", 17f, INK, BOLD)
        val stats = LinearLayout(this)
        stats.orientation = LinearLayout.HORIZONTAL
        stats.addView(stat("Mode", vMode), lp(w = 0, weight = 1f))
        stats.addView(stat("Remind at", vThreshold), lp(w = 0, weight = 1f))
        stats.addView(stat("Repeat", vRepeat), lp(w = 0, weight = 1f))
        hero.addView(stats, lp(top = 16))
        col.addView(hero, lp(top = 18))

        // meeting banner (only visible while a meeting is running)
        banner = text("", 14f, BLUE_INK, MED)
        banner.background = shape(BLUE_BG, 16)
        banner.setPadding(dp(16), dp(12), dp(16), dp(12))
        banner.visibility = View.GONE
        col.addView(banner, lp(top = 12))

        // setup warning (only when something needed for on-time reminders is missing)
        setupWarn = text("Setup incomplete \u00B7 reminders may be late or missing. Tap to fix.", 14f, AMBER_INK, MED)
        setupWarn.background = ripple(AMBER_BG, 16, AMBER_LINE, 1)
        setupWarn.setPadding(dp(16), dp(12), dp(16), dp(12))
        setupWarn.isClickable = true
        setupWarn.setOnClickListener { showTab(1) }
        setupWarn.visibility = View.GONE
        col.addView(setupWarn, lp(top = 12))

        // main actions
        startStop = button("Start tracking", ACCENT, Color.WHITE, 56, 18) {
            if (Prefs.isRunning(this)) {
                SedentaryService.stop(this)
            } else {
                saveSettings()
                Engine.startFresh(this)
                SedentaryService.start(this)
            }
            refresh()
        }
        col.addView(startStop, lp(h = dp(56), top = 16))

        col.addView(
            button("I stood up", CARD, INK, 52, 18, OUTLINE, 2) {
                Engine.manualStood(this)
                toast("Marked. Timer reset.")
                refresh()
            },
            lp(h = dp(52), top = 10)
        )

        // meeting mode
        col.addView(label("Meeting mode"), lp(top = 28))
        col.addView(text("Pause reminders while you sit in a meeting", 13f, MUTED), lp(top = 4, bottom = 12))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val mins = listOf(30, 60, 90, 120)
        mins.forEachIndexed { idx, m ->
            val b = button("${m}m", CARD, INK, 48, 14, LINE, 1) {
                Engine.startMeeting(this, m)
                toast("Meeting mode for $m min")
                refresh()
            }
            b.textSize = 15f
            b.setSingleLine(true)
            row.addView(
                b,
                lp(w = 0, h = dp(48), weight = 1f,
                    left = if (idx == 0) 0 else 4, right = if (idx == mins.size - 1) 0 else 4)
            )
        }
        col.addView(row, lp())
        endMeetingBtn = button("End meeting now", BLUE_BG, BLUE_INK, 48, 14) {
            Engine.endMeeting(this)
            refresh()
        }
        endMeetingBtn.visibility = View.GONE
        col.addView(endMeetingBtn, lp(h = dp(48), top = 10))

        return scroller(col)
    }

    // ------------------------------------------------------------ settings

    private fun numField(value: String, desc: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value)
        e.textSize = 20f
        e.typeface = MED
        e.setTextColor(INK)
        e.gravity = Gravity.CENTER_VERTICAL
        e.setPadding(dp(16), 0, dp(16), 0)
        e.setSingleLine(true)
        e.contentDescription = desc
        e.background = shape(FIELD, 16, FIELD_LINE, 1)
        e.setOnFocusChangeListener { v, focused ->
            v.background = if (focused) shape(FIELD, 16, ACCENT, 2) else shape(FIELD, 16, FIELD_LINE, 1)
        }
        return e
    }

    /** Label on top, field below, optional unit or hint. */
    private fun fieldBlock(title: String, edit: EditText, unit: String?, hint: String?): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.addView(text(title, 15f, INK, BOLD), lp(bottom = 8))
        if (unit != null) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(edit, lp(w = 0, h = dp(56), weight = 1f, right = 12))
            r.addView(text(unit, 14f, MUTED), lp(w = WRAP))
            b.addView(r, lp())
        } else {
            b.addView(edit, lp(h = dp(56)))
        }
        if (hint != null) b.addView(text(hint, 13f, MUTED), lp(top = 6))
        return b
    }

    private fun buildSettings(): View {
        val col = column()
        col.addView(titleBlock("Settings", "When and how Move Reminder nudges you"), lp())

        eThreshold = numField(Prefs.thresholdMin(this).toString(), "Sit threshold in minutes")
        eSteps = numField(Prefs.moveSteps(this).toString(), "Steps that count as movement")
        eStart = numField(Prefs.windowStart(this).toString(), "Active from hour")
        eEnd = numField(Prefs.windowEnd(this).toString(), "Active until hour")
        eRepeat = numField(Prefs.repeatMin(this).toString(), "Repeat reminder every minutes")

        val fields = card()
        fields.addView(fieldBlock("Sit threshold", eThreshold, "minutes", "First reminder after this much sitting"), lp())
        fields.addView(
            fieldBlock("Steps that count as a break", eSteps, "steps", "Steps within about 5 minutes. 25 to 40 works well."),
            lp(top = 20)
        )

        val hours = LinearLayout(this)
        hours.orientation = LinearLayout.HORIZONTAL
        hours.addView(fieldBlock("Active from", eStart, null, "Hour, 0 to 23"), lp(w = 0, weight = 1f, right = 7))
        hours.addView(fieldBlock("Active until", eEnd, null, "Hour, 0 to 23"), lp(w = 0, weight = 1f, left = 7))
        fields.addView(hours, lp(top = 20))
        fields.addView(text("Reminders only run between these hours. Same hour in both = all day.", 13f, MUTED), lp(top = 8))

        fields.addView(fieldBlock("Repeat reminder every", eRepeat, "minutes", "Repeats until you move. 0 = remind once only."), lp(top = 20))
        col.addView(fields, lp(top = 16))

        col.addView(
            button("Save settings", ACCENT, Color.WHITE, 58, 18) {
                saveSettings()
                toast("Saved")
            },
            lp(h = dp(58), top = 16)
        )

        // setup check (live status, one fix button per missing item)
        val setup = card()
        setup.addView(text("Setup check", 18f, INK, BOLD), lp())
        setup.addView(text("Every item should show OK for on-time reminders.", 14f, MUTED), lp(top = 4))
        setupBox = LinearLayout(this)
        setupBox.orientation = LinearLayout.VERTICAL
        setup.addView(setupBox, lp(top = 6))
        col.addView(setup, lp(top = 24))

        return scroller(col)
    }

    private fun setupRow(title: String, detail: String, ok: Boolean, badge: String?, fixLabel: String, fix: () -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(12), 0, dp(12))
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(text(title, 15f, INK, BOLD), lp())
        left.addView(text(detail, 13f, if (ok) MUTED else AMBER_INK), lp(top = 2))
        r.addView(left, lp(w = 0, weight = 1f, right = 12))
        when {
            ok -> r.addView(tag("OK", Color.parseColor("#DDF1E8"), Color.parseColor("#0F5A45")), lp(w = WRAP))
            badge != null -> r.addView(tag(badge, AMBER_BG, AMBER_INK), lp(w = WRAP))
            else -> {
                val b = button(fixLabel, ACCENT, Color.WHITE, 40, 999) { fix() }
                b.textSize = 14f
                b.setPadding(dp(18), 0, dp(18), 0)
                r.addView(b, lp(w = WRAP, h = dp(40)))
            }
        }
        return r
    }

    private fun refreshSetup() {
        val n = Setup.notificationsOk(this)
        val a = Setup.activityOk(this)
        val sensor = Setup.sensorPresent(this)
        val b = Setup.batteryOk(this)
        val x = Setup.exactOk(this)
        setupWarn.visibility = if (n && a && sensor && b && x) View.GONE else View.VISIBLE
        val sig = "$n$a$sensor$b$x"
        if (sig == setupSig) return
        setupSig = sig

        setupBox.removeAllViews()
        val rows = ArrayList<View>()
        rows.add(setupRow(
            "Notifications",
            if (n) "Reminders can alert you" else "Reminders cannot appear",
            n, null, "Allow"
        ) { fixNotifications() })
        rows.add(
            if (!sensor) setupRow("Step counter", "No step sensor on this phone. Reminders are time-only.", false, "Missing", "") { }
            else setupRow(
                "Physical activity",
                if (a) "Steps are counted to detect breaks" else "Needed to notice when you walk",
                a, null, "Allow"
            ) { fixActivity() }
        )
        rows.add(setupRow(
            "Battery: Unrestricted",
            if (b) "App can run all day" else "Android may stop the app and delay reminders",
            b, null, "Set"
        ) { fixBattery() })
        rows.add(setupRow(
            "Exact timing",
            if (x) "Reminders arrive on time" else "Reminders may arrive late",
            x, null, "Allow"
        ) { fixExact() })
        rows.forEachIndexed { i, v ->
            if (i > 0) {
                val d = View(this)
                d.setBackgroundColor(LINE)
                setupBox.addView(d, lp(h = dp(1)))
            }
            setupBox.addView(v, lp())
        }
    }

    private fun openSafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (x: Exception) {
                toast("Not available on this device")
            }
        }
    }

    private fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /** Ask with the system prompt the first time; afterwards (or if blocked) open settings. */
    private fun requestOrSettings(perm: String, settings: Intent) {
        val key = "asked_$perm"
        val asked = Prefs.get(this).getBoolean(key, false)
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED &&
            (!asked || shouldShowRequestPermissionRationale(perm))
        ) {
            Prefs.get(this).edit().putBoolean(key, true).apply()
            requestPermissions(arrayOf(perm), 2)
        } else {
            openSafe(settings)
        }
    }

    private fun fixNotifications() {
        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (Build.VERSION.SDK_INT >= 33) requestOrSettings(Manifest.permission.POST_NOTIFICATIONS, settings)
        else openSafe(settings)
    }

    private fun fixActivity() {
        if (Build.VERSION.SDK_INT >= 29) requestOrSettings(Manifest.permission.ACTIVITY_RECOGNITION, appDetails())
    }

    private fun fixBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            openSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun fixExact() {
        if (Build.VERSION.SDK_INT >= 31) {
            openSafe(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    // ------------------------------------------------------------- history

    private fun buildHistory(): View {
        val col = column()

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(titleBlock("History", null), lp(w = 0, weight = 1f, right = 12))
        val refreshBtn = button("Refresh", CARD, INK, 40, 999, OUTLINE, 2) {
            historySig = 0
            refresh()
        }
        refreshBtn.textSize = 14f
        refreshBtn.setPadding(dp(16), 0, dp(16), 0)
        head.addView(refreshBtn, lp(w = WRAP, h = dp(40)))
        col.addView(head, lp())
        col.addView(text("Last 15 sitting bouts and engine decisions", 14f, MUTED), lp(top = 4))

        boutList = LinearLayout(this)
        boutList.orientation = LinearLayout.VERTICAL
        col.addView(boutList, lp(top = 16))

        col.addView(label("Engine log"), lp(top = 24, bottom = 10))
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.background = shape(LOG_BG, 20)
        logBox.setPadding(dp(18), dp(18), dp(18), dp(10))
        col.addView(logBox, lp())

        return scroller(col)
    }

    private fun tag(s: String, bg: Int, fg: Int): TextView {
        val t = text(s, 12f, fg, BOLD)
        t.background = shape(bg, 999)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        return t
    }

    private fun fmtDur(secs: Long): String {
        val m = secs / 60
        return when {
            m < 1 -> "<1 min"
            m < 120 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    private fun fmtDay(ts: String): String {
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ts.substring(0, 10))
            if (d == null) ts else SimpleDateFormat("d MMM yyyy", Locale.US).format(d)
        } catch (e: Exception) {
            ts
        }
    }

    private fun hm(ts: String): String = if (ts.length >= 16) ts.substring(11, 16) else ts

    private fun boutCard(p: List<String>, maxSecs: Long): View {
        val start = p[0]
        val end = p[1]
        val secs = p[2].toLongOrNull() ?: 0L
        val mode = p[3]
        val nudged = p[4] == "true"
        val endedBy = p[5]

        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = shape(CARD, 20, LINE, 1)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(text("${hm(start)} to ${hm(end)}", 17f, INK, BOLD))
        left.addView(text(fmtDay(start), 13f, MUTED))
        top.addView(left, lp(w = 0, weight = 1f))
        top.addView(text(fmtDur(secs), 17f, INK, BOLD), lp(w = WRAP))
        c.addView(top, lp())

        // duration bar
        val frac = (secs.toFloat() / maxSecs.toFloat()).coerceIn(0.05f, 1f)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = shape(Color.parseColor("#EDF2EF"), 4)
        val fill = View(this)
        fill.background = shape(if (nudged) AMBER else ACCENT, 4)
        bar.addView(fill, LinearLayout.LayoutParams(0, MATCH, frac))
        bar.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f - frac))
        c.addView(bar, lp(h = dp(8), top = 12))

        // tags
        val tags = FlowLayout(this, dp(8), dp(8))
        if (nudged) tags.addView(tag("Nudged", Color.parseColor("#FFF1D1"), Color.parseColor("#6B4500")))
        val endText = when (endedBy) {
            "movement" -> "Ended by movement"
            "manual" -> "Ended manually"
            "meeting_end" -> "Meeting ended"
            "meeting" -> "Meeting started"
            "hours_end" -> "Active hours ended"
            "stopped" -> "Tracking stopped"
            else -> endedBy
        }
        val endBg = if (endedBy == "movement") Color.parseColor("#DDF1E8") else SOFT
        val endFg = if (endedBy == "movement") Color.parseColor("#0F5A45") else SOFT_TEXT
        tags.addView(tag(endText, endBg, endFg))
        tags.addView(tag(if (mode == Prefs.MODE_MEETING) "Meeting" else "Office", SOFT, SOFT_TEXT))
        c.addView(tags, lp(top = 12))
        return c
    }

    private fun rebuildHistory() {
        val bouts = BoutLog.readBouts(this).takeLast(15).reversed()
        val log = BoutLog.readDebug(this).takeLast(25).reversed()
        val sig = bouts.hashCode() * 31 + log.hashCode()
        if (sig == historySig) return
        historySig = sig

        boutList.removeAllViews()
        val parsed = bouts.map { it.split(",") }.filter { it.size >= 6 && it[0].length >= 16 && it[1].length >= 16 }
        if (parsed.isEmpty()) {
            val empty = text("No sitting bouts recorded yet.", 15f, MUTED)
            empty.background = shape(CARD, 20, LINE, 1)
            empty.setPadding(dp(18), dp(18), dp(18), dp(18))
            boutList.addView(empty, lp())
        } else {
            val maxSecs = parsed.maxOf { it[2].toLongOrNull() ?: 0L }.coerceAtLeast(1L)
            parsed.forEachIndexed { i, p ->
                boutList.addView(boutCard(p, maxSecs), lp(top = if (i == 0) 0 else 10))
            }
        }

        logBox.removeAllViews()
        if (log.isEmpty()) {
            logBox.addView(text("Log is empty.", 12f, LOG_FG, MONO), lp(bottom = 8))
        }
        for (line in log) {
            // "yyyy-MM-dd HH:mm:ss  message"
            val shown = if (line.length > 21) line.substring(5, 19) + "  " + line.substring(21) else line
            val tsLen = if (line.length > 21) 14 else 0
            val s = SpannableString(shown)
            if (tsLen > 0) s.setSpan(ForegroundColorSpan(LOG_TS), 0, tsLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val t = text("", 12f, LOG_FG, MONO)
            t.text = s
            t.setLineSpacing(0f, 1.15f)
            logBox.addView(t, lp(bottom = 8))
        }
    }

    // ------------------------------------------------------------ actions

    private fun saveSettings() {
        val th = eThreshold.text.toString().toIntOrNull() ?: 25
        val st = eSteps.text.toString().toIntOrNull() ?: 25
        val ws = eStart.text.toString().toIntOrNull() ?: 7
        val we = eEnd.text.toString().toIntOrNull() ?: 17
        val rp = eRepeat.text.toString().toIntOrNull() ?: 10
        Prefs.saveSettings(
            this,
            th.coerceIn(5, 240),
            st.coerceIn(5, 500),
            ws.coerceIn(0, 23),
            we.coerceIn(0, 23),
            rp.coerceIn(0, 120)
        )
        // show the values that were actually stored (after limits)
        eThreshold.setText(Prefs.thresholdMin(this).toString())
        eSteps.setText(Prefs.moveSteps(this).toString())
        eStart.setText(Prefs.windowStart(this).toString())
        eEnd.setText(Prefs.windowEnd(this).toString())
        eRepeat.setText(Prefs.repeatMin(this).toString())
        Engine.reschedule(this)
        SedentaryService.refreshStatus(this)
        refresh()
    }

    private fun refresh() {
        val running = Prefs.isRunning(this)
        val meeting = Prefs.mode(this) == Prefs.MODE_MEETING
        val threshold = Prefs.thresholdMin(this)
        val repeat = Prefs.repeatMin(this)
        val left = Engine.meetingMinutesLeft(this)
        val active = Engine.isActiveNow(this)
        val sitting = if (running) Engine.sittingMinutes(this) else 0L

        // header
        val ws = Prefs.windowStart(this)
        val we = Prefs.windowEnd(this)
        val window = if (ws == we) "all day" else String.format(Locale.US, "%02d:00\u2013%02d:00", ws, we)
        tvSub.text = (if (meeting) "Meeting mode" else "Office mode") + " \u00B7 active " + window
        val dot: Int
        if (!running) {
            chipText.text = "Stopped"; dot = RED
        } else if (meeting) {
            chipText.text = "Meeting"; dot = BLUE
        } else if (!active) {
            chipText.text = "Off hours"; dot = MUTED
        } else {
            chipText.text = "Tracking"; dot = ACCENT
        }
        chipDot.background = shape(dot, 4)

        // ring
        if (running && meeting) {
            ringNum.text = left.toString()
            ringLabel.text = "min left"
            ring.ringColor = BLUE
            ring.progress = 1f
        } else {
            ringNum.text = sitting.toString()
            ringLabel.text = "min sitting"
            val over = running && sitting >= threshold
            ring.ringColor = if (over) AMBER else ACCENT
            ring.progress = if (running && threshold > 0) sitting.toFloat() / threshold.toFloat() else 0f
        }

        ringCaption.text = when {
            !running -> "Tap Start tracking to begin"
            meeting -> "Reminders paused"
            !active -> "Outside active hours \u00B7 resumes ${Engine.resumesAt(this)}"
            sitting >= threshold -> "Time to stand up"
            else -> "Reminder at $threshold min"
        }
        ringCaption.setTextColor(if (running && !meeting && active && sitting >= threshold) Color.parseColor("#8A5A00") else MUTED)

        vMode.text = if (meeting) "Meeting" else "Office"
        vThreshold.text = "$threshold min"
        vRepeat.text = if (repeat > 0) "$repeat min" else "Off"

        if (meeting) {
            banner.text = "Meeting mode, $left min left. Reminders are paused."
            banner.visibility = View.VISIBLE
            endMeetingBtn.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
            endMeetingBtn.visibility = View.GONE
        }

        startStop.text = if (running) "Stop tracking" else "Start tracking"
        startStop.background = ripple(if (running) Color.parseColor("#1F2A27") else ACCENT, 18)

        refreshSetup()
        if (tab == 2) rebuildHistory()
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

        if (need.isNotEmpty()) {
            val e = Prefs.get(this).edit()
            need.forEach { e.putBoolean("asked_$it", true) }
            e.apply()
            requestPermissions(need.toTypedArray(), 1)
        }
    }
}
