package com.wyred4fun.pokertimer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Setup: change blinds and minutes with the remote, add or remove levels and breaks,
 * and switch sounds / voice / test mode. Every change is saved straight away.
 */
class SetupActivity : Activity() {

    private lateinit var ui: Ui
    private val levels = ArrayList<Level>()
    private lateinit var listCol: LinearLayout
    private lateinit var addLevelBtn: TextView
    private var editing: TextView? = null
    private var restoreConfirmUntil = 0L
    private lateinit var restoreBtn: TextView

    private enum class Field { SB, BB, MIN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Announcer.init(this)
        ui = Ui(this)
        levels.addAll(Lineup.load(this))
        setContentView(build())
    }

    /** Save, and hand the new lineup to the running clock right away. */
    private fun save(removedAt: Int = -1) {
        Lineup.save(this, levels)
        MainActivity.live?.let { e ->
            val now = SystemClock.elapsedRealtime()
            e.update(now)
            e.setLevels(ArrayList(levels), now, if (removedAt in 0 until e.idx) 1 else 0)
        }
    }

    // ---------- the list of levels ----------

    private fun rebuildList(focusRow: Int = -1) {
        listCol.removeAllViews()
        var n = 0
        var focusTarget: View? = null
        for ((i, lv) in levels.withIndex()) {
            if (!lv.isBreak) n += 1
            val row = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            val label = ui.text(48f, if (lv.isBreak) C.GOLD else C.CREAM, ui.bebas, 0.04f).apply {
                text = if (lv.isBreak) "BREAK" else "LEVEL $n"
            }
            row.addView(label, ui.lp(ui.px(220), ui.WRAP))
            if (lv.isBreak) {
                val none = ui.text(28f, C.MUTED, ui.bold).apply { text = "No blinds" }
                row.addView(none, ui.lp(ui.px(200 * 2 + 24), ui.WRAP).apply { leftMargin = ui.px(24) })
            } else {
                row.addView(cell(i, Field.SB), cellLp())
                row.addView(cell(i, Field.BB), cellLp())
            }
            row.addView(cell(i, Field.MIN), cellLp())
            row.addView(View(this), ui.lp(0, 1, 1f))
            val del = ui.button("").apply {
                setPadding(0, 0, 0, 0)
                background = ui.pillBg(Color.TRANSPARENT, C.EDGE, C.PANEL_HI, C.GOLD, 30f)
                val d = IconDrawable(Icon.CROSS, C.MUTED)
                d.setBounds(0, 0, ui.px(26), ui.px(26))
                setCompoundDrawables(d, null, null, null)
                setPadding(ui.px(17), 0, 0, 0)
                contentDescription = "Remove this row"
                setOnClickListener { removeRow(i) }
            }
            row.addView(del, ui.lp(ui.px(60), ui.px(60)))
            listCol.addView(row, ui.lp(ui.MATCH, ui.px(84)).apply { topMargin = if (i == 0) 0 else ui.px(10) })
            if (i == focusRow) focusTarget = del
        }
        focusTarget?.requestFocus()
    }

    private fun cellLp() = ui.lp(ui.px(200), ui.px(68)).apply { leftMargin = ui.px(24) }

    private fun valueOf(i: Int, f: Field): Int = when (f) {
        Field.SB -> levels[i].sb
        Field.BB -> levels[i].bb
        Field.MIN -> levels[i].minutes
    }

    private fun cell(i: Int, f: Field): TextView = ui.text(34f, C.CREAM, ui.bold).apply {
        text = valueOf(i, f).toString()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.px(22), 0, ui.px(16), 0)
        isFocusable = true
        background = cellBg(false)
        contentDescription = when (f) { Field.SB -> "Small blind"; Field.BB -> "Big blind"; Field.MIN -> "Minutes" }
        setOnFocusChangeListener { v, has -> if (!has && editing == v) stopEditing() }
        setOnKeyListener { v, code, ev ->
            val t = v as TextView
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener editing == t
            if (editing == t) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { change(i, f, +1, t); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { change(i, f, -1, t); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BACK -> { stopEditing(); true }
                    else -> false
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                startEditing(t); true
            } else false
        }
    }

    private fun cellBg(edit: Boolean) =
        if (edit) ui.round(C.ORANGE, 16f, 5, C.CREAM)
        else ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 16f)

    private fun startEditing(t: TextView) {
        editing?.let { stopEditing() }
        editing = t
        t.background = cellBg(true)
        t.setTextColor(C.ORANGE_INK)
        val up = IconDrawable(Icon.UPDOWN, C.ORANGE_INK)
        up.setBounds(0, 0, ui.px(30), ui.px(30))
        t.setCompoundDrawables(null, null, up, null)
    }

    private fun stopEditing() {
        val t = editing ?: return
        editing = null
        t.background = cellBg(false)
        t.setTextColor(C.CREAM)
        t.setCompoundDrawables(null, null, null, null)
    }

    private fun step(v: Int, dir: Int): Int {
        // blinds: by 1 up to 20, by 5 up to 100, then by 25
        val base = if (dir > 0) v else v - 1
        val s = if (base < 20) 1 else if (base < 100) 5 else 25
        return v + dir * s
    }

    private fun change(i: Int, f: Field, dir: Int, t: TextView) {
        val lv = levels[i]
        val nv = when (f) {
            Field.MIN -> (lv.minutes + dir).coerceIn(1, 180)
            else -> step(valueOf(i, f), dir).coerceIn(1, 100000)
        }
        levels[i] = when (f) {
            Field.SB -> lv.copy(sb = nv)
            Field.BB -> lv.copy(bb = nv)
            Field.MIN -> lv.copy(minutes = nv)
        }
        t.text = nv.toString()
        save()
    }

    private fun removeRow(i: Int) {
        if (!levels[i].isBreak && levels.count { !it.isBreak } <= 1) {
            Toast.makeText(this, "You need at least one level", Toast.LENGTH_SHORT).show()
            return
        }
        levels.removeAt(i)
        save(removedAt = i)
        if (levels.isEmpty()) return
        rebuildList(minOf(i, levels.size - 1))
    }

    private fun addLevel() {
        val last = levels.lastOrNull { !it.isBreak } ?: Level(1, 2, 20)
        levels.add(Level(last.sb, last.bb, last.minutes))
        save()
        rebuildList(levels.size - 1)
    }

    private fun addBreak() {
        levels.add(Level(0, 0, 15, isBreak = true))
        save()
        rebuildList(levels.size - 1)
    }

    // ---------- switches ----------

    private fun toggleText(t: TextView, label: String, on: Boolean) {
        t.text = label
        val d = IconDrawable(if (on) Icon.CHECK else Icon.CROSS, if (on) C.GREEN else C.MUTED)
        d.setBounds(0, 0, ui.px(30), ui.px(30))
        t.setCompoundDrawables(d, null, null, null)
        t.compoundDrawablePadding = ui.px(16)
    }

    private fun switchRow(label: String, key: String, read: () -> Boolean): TextView =
        ui.button(label, height = 76f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            toggleText(this, label + if (read()) ":  On" else ":  Off", read())
            setOnClickListener {
                val nv = !read()
                Prefs.set(this@SetupActivity, key, nv)
                toggleText(this, label + if (nv) ":  On" else ":  Off", nv)
            }
        }

    private fun restoreDefault() {
        val now = SystemClock.elapsedRealtime()
        if (now > restoreConfirmUntil) {
            restoreConfirmUntil = now + 4000
            restoreBtn.text = "Press again to restore"
            restoreBtn.postDelayed({
                if (SystemClock.elapsedRealtime() >= restoreConfirmUntil) restoreBtn.text = "Restore my lineup"
            }, 4100)
            return
        }
        restoreConfirmUntil = 0
        restoreBtn.text = "Restore my lineup"
        levels.clear(); levels.addAll(Lineup.DEFAULT)
        save()
        rebuildList()
        Toast.makeText(this, "Lineup restored", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && editing != null) { stopEditing(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        Announcer.hush()
    }

    // ---------- layout ----------

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C.FELT) }
        val body = ui.col().apply { setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(56)) }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))

        val header = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = ui.col()
        titles.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "POKER NIGHT" })
        titles.addView(ui.text(80f, C.GOLD, ui.bebas, 0.04f).apply { text = "TOURNAMENT SETUP" },
            ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })
        header.addView(titles, ui.lp(0, ui.WRAP, 1f))
        val done = ui.button("Done", primary = true, height = 84f, textSize = 30f).apply {
            ui.icon(this, Icon.CHECK, C.GOLD_INK)
            setPadding(ui.px(44), 0, ui.px(44), 0)
            setOnClickListener { finish() }
        }
        header.addView(done)
        body.addView(header, ui.lp(ui.MATCH, ui.WRAP))

        body.addView(ui.text(26f, C.MUTED, ui.bold).apply {
            text = "Pick a number and press OK  ·  Up / Down to change it  ·  OK when done"
        }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(18) })

        val main = ui.row()
        body.addView(main, ui.lp(ui.MATCH, 0, 1f).apply { topMargin = ui.px(28) })

        // left: the lineup
        val left = ui.col().apply {
            background = ui.round(C.PANEL, 32f)
            setPadding(ui.px(40), ui.px(30), ui.px(40), ui.px(30))
        }
        main.addView(left, ui.lp(0, ui.MATCH, 1f))
        val heads = ui.row()
        fun head(s: String, w: Int, ml: Int) = heads.addView(ui.text(22f, C.MUTED, ui.bold, 0.1f).apply { text = s },
            ui.lp(ui.px(w), ui.WRAP).apply { leftMargin = ui.px(ml) })
        head("LEVEL", 220, 0); head("SMALL BLIND", 200, 24); head("BIG BLIND", 200, 24); head("MINUTES", 200, 24)
        left.addView(heads, ui.lp(ui.MATCH, ui.WRAP))
        left.addView(View(this).apply { setBackgroundColor(C.RULE) }, ui.lp(ui.MATCH, ui.px(2)).apply {
            topMargin = ui.px(10); bottomMargin = ui.px(12)
        })
        val scroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
        listCol = ui.col()
        scroll.addView(listCol)
        left.addView(scroll, ui.lp(ui.MATCH, 0, 1f))
        val adds = ui.row()
        addLevelBtn = ui.button("Add level", height = 68f).apply { ui.icon(this, Icon.PLUS, C.CREAM, 26f); setOnClickListener { addLevel() } }
        val addBreakBtn = ui.button("Add break", height = 68f).apply { ui.icon(this, Icon.PLUS, C.CREAM, 26f); setOnClickListener { addBreak() } }
        adds.addView(addLevelBtn)
        adds.addView(addBreakBtn, (addBreakBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = ui.px(16) })
        left.addView(adds, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(16) })

        // right: switches
        val right = ui.col().apply {
            background = ui.round(C.PANEL, 32f)
            setPadding(ui.px(40), ui.px(36), ui.px(40), ui.px(36))
        }
        main.addView(right, ui.lp(ui.px(600), ui.MATCH).apply { leftMargin = ui.px(56) })
        right.addView(ui.text(24f, C.MUTED, ui.bold, 0.16f).apply { text = "SOUND & VOICE" })
        val gap = { v: View, top: Int -> right.addView(v, ui.lp(ui.MATCH, (v.layoutParams as? LinearLayout.LayoutParams)?.height ?: ui.WRAP).apply { topMargin = ui.px(top) }) }
        gap(switchRow("Chime & knocks", "sounds") { Prefs.sounds(this) }, 20)
        gap(switchRow("Voice", "voice") { Prefs.voice(this) }, 14)
        gap(ui.button("Test sounds", height = 72f).apply {
            ui.icon(this, Icon.PLAY, C.CREAM, 26f)
            setOnClickListener { Announcer.demo() }
        }, 14)
        right.addView(ui.text(22f, C.MUTED, ui.bold).apply {
            text = "Plays the 1-minute warning, then the 5 knocks"
            setSingleLine(false)
        }, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(10) })
        right.addView(ui.text(24f, C.MUTED, ui.bold, 0.16f).apply { text = "OTHER" }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(40) })
        gap(switchRow("Test mode", "testMode") { Prefs.testMode(this) }, 20)
        right.addView(ui.text(22f, C.MUTED, ui.bold).apply {
            text = "Every level lasts 75 seconds, to try it out"
            setSingleLine(false)
        }, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(10) })
        restoreBtn = ui.button("Restore my lineup", height = 72f).apply {
            ui.icon(this, Icon.RESET, C.CREAM, 26f)
            setOnClickListener { restoreDefault() }
        }
        gap(restoreBtn, 28)

        rebuildList()
        root.post { done.requestFocus() }
        return root
    }
}
