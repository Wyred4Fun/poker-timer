package com.wyred4fun.pokertimer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), TimerEngine.Listener {

    private lateinit var ui: Ui
    private var themeShown = ""
    private lateinit var engine: TimerEngine
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var topLabel: TextView
    private lateinit var levelLabel: TextView
    private lateinit var nextText: TextView
    private lateinit var clockText: TextView
    private lateinit var startedText: TextView
    /** Wall-clock time the night's first Start was pressed (0 = not started yet). */
    private var startedAt = 0L
    private lateinit var centerLabel: TextView
    private lateinit var bigText: TextView
    /** Under BREAK: the blinds when play starts again. */
    private lateinit var breakNextText: TextView
    private lateinit var timerText: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerNum: TextView
    private lateinit var progress: ProgressBarView
    private lateinit var playBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var resetAllBtn: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var sbChip: ChipView
    private lateinit var bbChip: ChipView
    private var useChips = true

    private var flashUntil = 0L
    private var confirmStopUntil = 0L
    private var backPressedAt = 0L
    /** Last time the remote was pressed on this screen. */
    private var lastKeyAt = 0L
    private var resumed = false

    private val ticker = object : Runnable {
        override fun run() {
            engine.update(SystemClock.elapsedRealtime())
            snapBackIfIdle()
            render()
            val now = SystemClock.elapsedRealtime()
            syncCountdown(now)
            // near the end, check every 20 ms so the numbers change right on time
            handler.postDelayed(this, if (engine.running && engine.msLeft(now) < 8000) 20 else 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Announcer.init(this)
        Theme.apply(Prefs.theme(this)); themeShown = Prefs.theme(this)
        useChips = Prefs.chips(this)
        ui = Ui(this)
        engine = TimerEngine(Lineup.load(this), this)
        live = engine
        engine.testMode = Prefs.testMode(this)
        restoreState()
        setContentView(build())
        playBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.theme(this) != themeShown) { recreate(); return }
        useChips = Prefs.chips(this)
        resumed = true
        lastKeyAt = SystemClock.elapsedRealtime()
        // pick up any changes made on the Setup screen
        val now = SystemClock.elapsedRealtime()
        val test = Prefs.testMode(this)
        if (test != engine.testMode) {
            engine.testMode = test
            engine.resetLevel(now)
        }
        engine.setLevels(Lineup.load(this), now)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // the clock (and its sounds) keep going while Setup is open
        saveState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Countdown.cancel()
        handler.removeCallbacks(ticker)
        saveState()
        if (live === engine) live = null
    }

    companion object {
        /** The running clock, so Setup can apply changes to it straight away. */
        var live: TimerEngine? = null
        /** When the night started (wall clock ms, 0 = not started), for Setup's game timer. */
        var gameStartedAt = 0L
        const val IDLE_SNAP_MS = 90_000L
    }

    // ---------- the clock's events ----------

    override fun onWarning(current: Level, next: Level) {
        Announcer.chime()
        val inTime = "in one minute"
        val msg = when {
            current.isBreak -> "Attention players. The break ends $inTime. Please head back to the table."
            next.isBreak -> "Attention players. Break starts $inTime."
            else -> "Attention players. Blinds will be going up $inTime."
        }
        // the chime always; the words only if Setup > Voice includes the 1-minute warning
        if (Announcer.warningWords) Announcer.say(msg, 1500)
    }

    override fun onBreakEnding(minutesLeft: Int) {
        Announcer.chime()
        Announcer.say("Attention players. The break is over in $minutesLeft minutes. Please return to your seats soon." + blindsSentence(engine.idx), 1500)
    }

    override fun onCountdown(secondsLeft: Int) {
        // the sound itself is the timed stream started by syncCountdown()
    }

    /**
     * Keep the countdown stream aimed at the running level's end: start it ~2 s before "5",
     * and stop it if the clock is paused, reset, skipped or changed.
     */
    private fun syncCountdown(now: Long) {
        val left = engine.msLeft(now)
        val nx = engine.next
        // get the "blinds have changed" sentence ready well ahead, so it can start right at 0:00
        if (engine.running && nx != null && left in 1..30_000L) Announcer.prepare(changeText(nx, engine.idx + 1))
        val want = if (engine.running && nx != null && Announcer.soundsOn() &&
            left in 1..(TimerEngine.COUNTDOWN * 1000L + 2000L)) engine.endTime else 0L
        if (want == Countdown.aimedAt) return
        if (want == 0L && Countdown.isPreview) return   // let Test sounds / a preview finish
        if (want == 0L && Countdown.aimedAt in 1..now) {
            // the level just changed on time: let the stream finish saying the new blinds
            Countdown.releaseAim(); return
        }
        Countdown.cancel()
        val wav = Announcer.knockPcm
        if (want != 0L && wav != null && left > 300 && nx != null) {
            val speech = Announcer.preparedSpeech(changeText(nx, engine.idx + 1), wav.second)
            Countdown.start(want, minOf(engine.knocks, ((left - 1) / 1000).toInt()), wav.first, wav.second, tail = speech)
        }
    }

    /** What is said when step [i] begins. */
    private fun changeText(lv: Level, i: Int): String = when {
        lv.isBreak -> "Attention players. Break time. ${lv.minutes} minutes."
        engine.isExtra(i) -> "Attention players. The blinds are continuing. They are now ${lv.blindsSpoken}."
        else -> "Attention players. The blinds have now changed to ${lv.blindsSpoken}."
    }

    /** The blinds play goes back to after the break at row [breakIdx] (skips any break right after it). */
    private fun blindsAfterBreak(breakIdx: Int): Level? {
        var j = breakIdx + 1
        while (true) {
            val lv = engine.levels.getOrNull(j)
                ?: return if (engine.carryOn) TimerEngine.extraAfter(engine.levels) else null
            if (!lv.isBreak) return lv
            j++
        }
    }

    /** " When we come back, the blinds will be 4 and 8." (or nothing if no level follows) */
    private fun blindsSentence(breakIdx: Int): String =
        blindsAfterBreak(breakIdx)?.let { " When we come back, the blinds will be ${it.blindsSpoken}." } ?: ""

    override fun onLevelChanged(now: Level, quiet: Boolean) {
        saveState()
        if (quiet) return
        val t = SystemClock.elapsedRealtime()
        flashUntil = t + 8000
        // already playing inside the countdown stream, right after the last hit?
        if (Countdown.speechAt != 0L && Math.abs(Countdown.speechAt - t) < 3000) return
        Announcer.say(changeText(now, engine.idx))
    }

    override fun onFinished(quiet: Boolean) {
        saveState()
        if (!quiet) Announcer.say("Time is up on the final level.", 350)
    }

    // ---------- buttons ----------

    private fun toggle() {
        val now = SystemClock.elapsedRealtime()
        if (engine.finished) {
            Toast.makeText(this, "The final level is over. Press Reset tournament twice to start a new night.", Toast.LENGTH_LONG).show()
            return
        }
        if (engine.running) engine.pause(now) else {
            engine.start(now)
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
        }
        saveState()
        render()
    }

    private fun jump(delta: Int) {
        val now = SystemClock.elapsedRealtime()
        if (engine.goTo(engine.idx + delta, now)) {
            Announcer.hush()
            flashUntil = 0
            saveState()
            render()
        }
    }

    /** +/- buttons beside the clock: put time on or take it off. */
    private fun nudge(deltaMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (engine.adjust(deltaMs, now)) {
            saveState()
            render()
            syncCountdown(now)
        }
    }

    private fun resetLevel() {
        Announcer.hush()
        engine.resetLevel(SystemClock.elapsedRealtime())
        flashUntil = 0
        saveState()
        render()
    }

    /** Stop: clock stopped and this level back to its full time (Start begins it again). */
    private fun stop() {
        val now = SystemClock.elapsedRealtime()
        Announcer.hush()
        engine.pause(now)
        engine.resetLevel(now)
        flashUntil = 0
        saveState()
        render()
    }

    /** Reset tournament (press twice): back to Level 1, clock stopped. */
    private fun resetTournament() {
        val now = SystemClock.elapsedRealtime()
        if (now > confirmStopUntil) {
            confirmStopUntil = now + 4000
            render()
            return
        }
        confirmStopUntil = 0
        Announcer.hush()
        engine.stopAll()
        startedAt = 0L
        flashUntil = 0
        saveState()
        render()
    }

    /** Any remote press counts as "touched" (checked before the buttons see it). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastKeyAt = SystemClock.elapsedRealtime()
        return super.dispatchKeyEvent(event)
    }

    /** After 90 seconds without the remote, put the highlight back on Start/Pause. */
    private fun snapBackIfIdle() {
        if (!resumed || playBtn.isFocused) return
        if (SystemClock.elapsedRealtime() - lastKeyAt >= IDLE_SNAP_MS) playBtn.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount > 0) return true
                val wantPlay = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                val wantPause = keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                if ((wantPlay && engine.running) || (wantPause && !engine.running)) return true
                toggle(); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                val now = SystemClock.elapsedRealtime()
                if (now - backPressedAt < 2500) return super.onKeyDown(keyCode, event)
                backPressedAt = now
                Toast.makeText(this, "Press Back again to leave the timer", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- saving, so closing the app never loses the night ----------

    private fun saveState() {
        val now = SystemClock.elapsedRealtime()
        getSharedPreferences("poker_timer", Context.MODE_PRIVATE).edit()
            .putInt("st_idx", engine.idx)
            .putLong("st_msLeft", engine.msLeft(now))
            .putBoolean("st_running", engine.running)
            .putBoolean("st_finished", engine.finished)
            .putLong("st_wall", System.currentTimeMillis())
            .putLong("st_started", startedAt)
            .apply()
    }

    private fun restoreState() {
        val p = getSharedPreferences("poker_timer", Context.MODE_PRIVATE)
        startedAt = p.getLong("st_started", 0L)
        if (!p.contains("st_idx")) return
        val since = System.currentTimeMillis() - p.getLong("st_wall", System.currentTimeMillis())
        engine.restore(
            p.getInt("st_idx", 0),
            p.getLong("st_msLeft", engine.durationMs(0)),
            since,
            p.getBoolean("st_running", false),
            p.getBoolean("st_finished", false),
            SystemClock.elapsedRealtime()
        )
    }

    // ---------- drawing ----------

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val cur = engine.current
        val nx = engine.next
        val s = engine.secondsLeft(now)
        val levelCount = engine.levels.take(engine.lineupSize).count { !it.isBreak }
        val levelNum = engine.levels.take(engine.idx + 1).count { !it.isBreak }

        set(topLabel, if (engine.testMode) "POKER NIGHT  ·  TEST MODE" else "POKER NIGHT")
        topLabel.setTextColor(if (engine.testMode) C.ORANGE else C.MUTED)
        set(levelLabel, when {
            cur.isBreak -> "BREAK"
            engine.isExtra() -> "LEVEL $levelNum  ·  BLINDS CONTINUING"
            else -> "LEVEL $levelNum OF $levelCount"
        })
        set(nextText, when {
            nx == null -> "FINAL LEVEL"
            nx.isBreak -> "${nx.minutes}-MIN BREAK"
            else -> "${nx.blindsText}  ·  ${nx.minutes} MIN"
        })
        if (cur.isBreak) {
            set(centerLabel, "STRETCH YOUR LEGS")
            set(bigText, "BREAK"); bigText.setTextColor(C.GOLD)
            val after = blindsAfterBreak(engine.idx)
            set(breakNextText, if (after == null) "" else "NEXT BLINDS  ${after.blindsText}")
        } else {
            set(centerLabel, "BLINDS")
            set(bigText, cur.blindsText); bigText.setTextColor(C.CREAM)
        }
        val showNext = cur.isBreak && breakNextText.text.isNotEmpty()
        breakNextText.visibility = if (showNext) View.VISIBLE else View.GONE
        // a smaller BREAK when the next blinds sit under it, so the clock keeps its room
        val bigPx = ui.px(if (showNext) 190 else 250).toFloat()
        if (bigText.textSize != bigPx) bigText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bigPx)
        // blinds drawn inside two poker chips (Setup > Blinds in chips)
        val chips = useChips && !cur.isBreak
        bigText.visibility = if (chips) View.GONE else View.VISIBLE
        chipsRow.visibility = if (chips) View.VISIBLE else View.GONE
        if (chips) { sbChip.value = cur.sb.toString(); bbChip.value = cur.bb.toString() }
        set(timerText, "%d:%02d".format(s / 60, s % 60))
        set(clockText, timeOfDay(System.currentTimeMillis()))
        set(startedText, if (startedAt > 0) "STARTED " + timeOfDay(startedAt) else "")
        gameStartedAt = startedAt

        val counting = nx != null && s in 1..engine.knocks
        val warning = nx != null && s in (engine.knocks + 1)..engine.warnAt && engine.durationMs(engine.idx) > engine.warnAt * 1000L
        // the last 3 minutes of a break (before the 1-minute banner takes over)
        val breakSoon = cur.isBreak && nx != null && s in (engine.warnAt + 1)..TimerEngine.BREAK_WARN &&
            engine.durationMs(engine.idx) > TimerEngine.BREAK_WARN * 1000L
        val flash = now < flashUntil && !warning && !counting && !breakSoon
        timerText.setTextColor(if (counting) C.ORANGE else if (warning) C.GOLD else C.CREAM)

        when {
            counting -> {
                showBanner(C.ORANGE, C.ORANGE_INK)
                set(bannerText, if (nx!!.isBreak) "BREAK IN" else if (cur.isBreak) "BACK IN" else "BLINDS GO UP IN")
                bannerText.typeface = ui.black; bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 46f * ui.scale)
                set(bannerNum, s.toString()); bannerNum.visibility = View.VISIBLE
            }
            warning -> {
                showBanner(C.GOLD, C.GOLD_INK)
                set(bannerText, when {
                    cur.isBreak -> "Break ends in 1 minute — back to the table"
                    nx!!.isBreak -> "Attention players — break starts in 1 minute"
                    else -> "Attention players — blinds go up to ${nx.blindsText} in 1 minute"
                })
                bannerText.typeface = ui.black; bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 44f * ui.scale)
                bannerNum.visibility = View.GONE
            }
            breakSoon -> {
                showBanner(C.GOLD, C.GOLD_INK)
                val mins = (s + 59) / 60
                set(bannerText, "Attention players — the break is over in $mins minute${if (mins == 1) "" else "s"}")
                bannerText.typeface = ui.black; bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 44f * ui.scale)
                bannerNum.visibility = View.GONE
            }
            flash || engine.finished -> {
                showBanner(C.GREEN, C.GREEN_INK)
                set(bannerText, when {
                    engine.finished -> "FINAL LEVEL COMPLETE"
                    cur.isBreak -> "BREAK TIME — ${cur.minutes} MINUTES"
                    else -> "BLINDS ARE NOW ${cur.blindsText}"
                })
                bannerText.typeface = ui.bebas; bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 84f * ui.scale)
                bannerNum.visibility = View.GONE
            }
            else -> banner.visibility = View.INVISIBLE
        }

        val dur = engine.durationMs(engine.idx).toFloat()
        progress.set(1f - engine.msLeft(now) / dur, if (counting) C.ORANGE else if (warning || cur.isBreak) C.GOLD else C.GREEN)

        set(playBtn, if (engine.running) "Pause" else "Start")
        if (playBtn.tag != engine.running) {
            playBtn.tag = engine.running
            ui.icon(playBtn, if (engine.running) Icon.PAUSE else Icon.PLAY, C.CREAM, 34f)
        }
        val confirming = now < confirmStopUntil
        set(resetAllBtn, if (confirming) "Press again to reset" else "Reset tournament")
        if (resetAllBtn.tag != confirming) {
            resetAllBtn.tag = confirming
            resetAllBtn.background = if (confirming) ui.pillBg(C.STOP_BG, C.ORANGE, C.STOP_BG, C.ORANGE)
            else ui.pillBg(C.PANEL, C.EDGE, C.PANEL_HI, C.GOLD)
        }
    }

    private fun showBanner(bg: Int, ink: Int) {
        if (banner.tag != bg) {
            banner.tag = bg
            banner.background = ui.round(bg, 24f)
        }
        bannerText.setTextColor(ink); bannerNum.setTextColor(ink)
        banner.visibility = View.VISIBLE
    }

    /** "11:43 PM", or "23:43" if the box is set to a 24-hour clock. */
    private fun timeOfDay(ms: Long): String {
        val f = if (android.text.format.DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"
        return java.text.SimpleDateFormat(f, java.util.Locale.US).format(java.util.Date(ms)).uppercase()
    }

    private fun set(t: TextView, s: String) { if (t.text.toString() != s) t.text = s }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C.FELT) }
        // the thin rounded "table edge" inside the screen
        root.addView(View(this).apply { background = ui.round(0, 48f, 3, C.RULE) },
            FrameLayout.LayoutParams(-1, -1).apply { setMargins(ui.px(24), ui.px(24), ui.px(24), ui.px(24)) })

        val body = ui.col().apply { setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(56)) }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))

        // header
        val header = ui.row().apply { gravity = Gravity.BOTTOM }
        val left = ui.col()
        topLabel = ui.text(26f, C.MUTED, ui.bold, 0.24f)
        levelLabel = ui.text(72f, C.GOLD, ui.bebas, 0.04f)
        left.addView(topLabel, ui.lp(ui.WRAP, ui.WRAP)); left.addView(levelLabel, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })
        // top right: the time of day, so everyone knows what time it is
        val right = ui.col().apply { gravity = Gravity.END }
        val timeLabel = ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "TIME" }
        clockText = ui.text(72f, C.CREAM, ui.bebas, 0.04f)
        right.addView(timeLabel, ui.lp(ui.WRAP, ui.WRAP)); right.addView(clockText, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })

        header.addView(left, ui.lp(0, ui.WRAP, 1f))
        header.addView(right, ui.lp(ui.WRAP, ui.WRAP))
        body.addView(header, ui.lp(ui.MATCH, ui.WRAP))
        // when the night started, right under the time of day (floats there, so nothing else moves)
        startedText = ui.text(26f, C.MUTED, ui.bold, 0.08f)
        root.addView(startedText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.px(182); rightMargin = ui.px(96)
        })

        // center: blinds and clock
        val center = ui.col().apply { gravity = Gravity.CENTER }
        centerLabel = ui.text(34f, C.MUTED, ui.bold, 0.3f)
        bigText = ui.text(250f, C.CREAM, ui.bebas, 0.02f)
        timerText = ui.text(176f, C.CREAM, ui.bebas, 0.06f)
        center.addView(centerLabel, ui.lp(ui.WRAP, ui.WRAP))
        center.addView(bigText, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(4) })
        breakNextText = ui.text(64f, C.CREAM, ui.bebas, 0.06f).apply { visibility = View.GONE }
        center.addView(breakNextText, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(-40); bottomMargin = ui.px(40) })
        chipsRow = ui.row().apply { gravity = Gravity.CENTER; visibility = View.GONE }
        fun chipCol(label: String, chip: ChipView): LinearLayout = ui.col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(chip, ui.lp(ui.px(270), ui.px(270)))
            addView(ui.text(24f, C.MUTED, ui.bold, 0.24f).apply { text = label }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(8) })
        }
        sbChip = ChipView(this, ui); bbChip = ChipView(this, ui)
        chipsRow.addView(chipCol("SMALL", sbChip))
        chipsRow.addView(chipCol("BIG", bbChip), ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(70) })
        center.addView(chipsRow, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6); bottomMargin = ui.px(40) })
        // the clock, with +1 min / +30 sec on the left and -30 sec / -1 min on the right
        val timerRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        fun nudgeBtn(label: String, ms: Long) = ui.button(label, height = 68f, textSize = 26f).apply {
            setPadding(0, 0, 0, 0)
            contentDescription = if (ms > 0) "Put $label on the clock" else "Take $label off the clock"
            setOnClickListener { nudge(ms) }
        }
        val nudges = listOf(nudgeBtn("+1 min", 60_000L), nudgeBtn("+30 sec", 30_000L),
            nudgeBtn("\u221230 sec", -30_000L), nudgeBtn("\u22121 min", -60_000L))
        timerText.gravity = Gravity.CENTER
        timerRow.addView(nudges[0], ui.lp(ui.px(150), ui.px(68)))
        timerRow.addView(nudges[1], ui.lp(ui.px(150), ui.px(68)).apply { leftMargin = ui.px(16) })
        // fixed width so the buttons don't shift as the digits change
        timerRow.addView(timerText, ui.lp(ui.px(520), ui.WRAP).apply { leftMargin = ui.px(36); rightMargin = ui.px(36) })
        timerRow.addView(nudges[2], ui.lp(ui.px(150), ui.px(68)))
        timerRow.addView(nudges[3], ui.lp(ui.px(150), ui.px(68)).apply { leftMargin = ui.px(16) })
        center.addView(timerRow, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(-34) })
        body.addView(center, ui.lp(ui.MATCH, 0, 1f))

        // banner (warning / countdown / blinds are now)
        banner = ui.row().apply {
            gravity = Gravity.CENTER
            setPadding(ui.px(40), 0, ui.px(40), 0)
            visibility = View.INVISIBLE
        }
        bannerText = ui.text(44f, C.GOLD_INK, ui.black)
        bannerNum = ui.text(120f, C.ORANGE_INK, ui.bebas)
        banner.addView(bannerText)
        banner.addView(bannerNum, ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(36) })
        body.addView(banner, ui.lp(ui.MATCH, ui.px(104)).apply { topMargin = ui.px(24) })

        // progress bar
        // progress bar, with "Up next" at the right end, just above Setup
        val barRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        progress = ProgressBarView(this, ui)
        barRow.addView(progress, ui.lp(0, ui.px(16), 1f))
        val upNext = ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "UP NEXT" }
        nextText = ui.text(52f, C.CREAM, ui.bebas, 0.04f)
        barRow.addView(upNext, ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(36) })
        barRow.addView(nextText, ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(16) })
        body.addView(barRow, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(14) })

        // controls
        val controls = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ui.button("Back").apply { ui.icon(this, Icon.BACK, C.CREAM); setOnClickListener { jump(-1) } }
        playBtn = ui.button("Start", textSize = 34f).apply {
            typeface = ui.black
            minWidth = ui.px(220)
            setOnClickListener { toggle() }
        }
        val reset = ui.button("Reset level").apply { ui.icon(this, Icon.RESET, C.CREAM); setOnClickListener { resetLevel() } }
        val skip = ui.button("Skip").apply { ui.icon(this, Icon.SKIP, C.CREAM); setOnClickListener { jump(1) } }
        stopBtn = ui.button("Stop").apply { ui.icon(this, Icon.STOP, C.CREAM, 26f); setOnClickListener { stop() } }
        val setup = ui.button("Setup").apply {
            ui.icon(this, Icon.SLIDERS, C.CREAM)
            setOnClickListener { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }
        }
        resetAllBtn = ui.button("Reset tournament").apply { ui.icon(this, Icon.RESET, C.CREAM, 26f); setOnClickListener { resetTournament() } }
        for ((i, b) in listOf(back, playBtn, reset, skip, stopBtn, resetAllBtn).withIndex()) {
            controls.addView(b, (b.layoutParams as LinearLayout.LayoutParams).apply { if (i > 0) leftMargin = ui.px(16) })
        }
        controls.addView(View(this), ui.lp(0, 1, 1f))
        controls.addView(setup)
        body.addView(controls, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(24) })

        render()
        return root
    }
}

/** The thin bar showing how much of the level has gone by. */
class ProgressBarView(ctx: Context, private val ui: Ui) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.PANEL_HI }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frac = 0f
    private val r = RectF()

    fun set(f: Float, color: Int) {
        val nf = f.coerceIn(0f, 1f)
        if (nf != frac || fill.color != color) {
            frac = nf; fill.color = color; invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val rad = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, rad, rad, track)
        if (frac > 0f) {
            r.set(0f, 0f, width * frac, height.toFloat())
            c.drawRoundRect(r, rad, rad, fill)
        }
    }
}
