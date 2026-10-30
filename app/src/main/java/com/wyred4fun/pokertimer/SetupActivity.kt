package com.wyred4fun.pokertimer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
    private var themeShown = ""
    private val levels = ArrayList<Level>()
    private lateinit var listCol: LinearLayout
    private lateinit var addLevelBtn: TextView
    private var restoreConfirmUntil = 0L
    private lateinit var restoreBtn: TextView
    private lateinit var startupBtn: TextView
    private lateinit var knockBtn: TextView
    private lateinit var updateBtn: TextView
    private var available: Updater.Release? = null
    private lateinit var startupNote: TextView

    private companion object {
        // column widths on the 1920-wide drawing
        const val COL_LABEL = 180
        const val COL_GROUP = 250
        const val COL_BB = 150
        const val GAP = 20
        /** After changing the theme the screen is redrawn; keep the highlight on Theme. */
        var focusThemeAfterRedraw = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Announcer.init(this)
        Theme.apply(Prefs.theme(this)); themeShown = Prefs.theme(this)
        ui = Ui(this)
        levels.addAll(Lineup.load(this))
        Updater.cleanUp(this)
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
            row.addView(label, ui.lp(ui.px(COL_LABEL), ui.WRAP))
            if (lv.isBreak) {
                val none = ui.text(28f, C.MUTED, ui.bold).apply { text = "No blinds" }
                row.addView(none, ui.lp(ui.px(COL_GROUP + GAP + COL_BB), ui.WRAP).apply { leftMargin = ui.px(GAP) })
            } else {
                // big blind is always twice the small blind, shown shaded (not selectable)
                val bb = valueBox(lv.bb, shaded = true)
                row.addView(stepper(i, isMinutes = false, lv.sb) { bb.text = levels[i].bb.toString() }, groupLp())
                row.addView(bb, ui.lp(ui.px(COL_BB), ui.px(68)).apply { leftMargin = ui.px(GAP) })
            }
            row.addView(stepper(i, isMinutes = true, lv.minutes) {}, groupLp())
            row.addView(View(this), ui.lp(0, 1, 1f))
            val del = ui.button("").apply {
                setPadding(0, 0, 0, 0)
                background = ui.pillBg(Color.TRANSPARENT, C.EDGE, C.PANEL_HI, C.GOLD, 30f)
                val d = IconDrawable(Icon.CROSS, C.MUTED)
                d.setBounds(0, 0, ui.px(26), ui.px(26))
                setCompoundDrawables(d, null, null, null)
                setPadding(ui.px(17), 0, 0, 0)
                contentDescription = "Remove this row"
                setOnClickListener { deletePressed(i, this) }
            }
            row.addView(del, ui.lp(ui.px(60), ui.px(60)))
            listCol.addView(row, ui.lp(ui.MATCH, ui.px(84)).apply { topMargin = if (i == 0) 0 else ui.px(10) })
            if (i == focusRow) focusTarget = del
        }
        focusTarget?.requestFocus()
    }

    private fun groupLp() = ui.lp(ui.px(COL_GROUP), ui.px(68)).apply { leftMargin = ui.px(GAP) }

    /** A number shown in a box; shaded = read-only (the big blind). */
    private fun valueBox(v: Int, shaded: Boolean): TextView = ui.text(34f, if (shaded) C.MUTED else C.CREAM, ui.bold).apply {
        text = v.toString()
        gravity = Gravity.CENTER
        isFocusable = false
        background = if (shaded) ui.round(C.PANEL_HI, 16f) else ui.round(C.FELT, 16f, 2, C.EDGE)
    }

    /** [-] value [+] for the small blind or the minutes. */
    private fun stepper(i: Int, isMinutes: Boolean, start: Int, after: () -> Unit): LinearLayout {
        val g = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val box = valueBox(start, shaded = false)
        fun btn(icon: Icon, dir: Int, what: String) = ui.button("").apply {
            setPadding(0, 0, 0, 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 16f)
            val d = IconDrawable(icon, C.CREAM)
            d.setBounds(0, 0, ui.px(26), ui.px(26))
            setCompoundDrawables(d, null, null, null)
            setPadding(ui.px(17), 0, 0, 0)
            contentDescription = what
            setOnClickListener {
                box.text = change(i, isMinutes, dir).toString()
                after()
            }
        }
        val label = if (isMinutes) "minutes" else "small blind"
        g.addView(btn(Icon.MINUS, -1, "Lower the $label"), ui.lp(ui.px(60), ui.px(60)))
        g.addView(box, ui.lp(0, ui.px(68), 1f).apply { leftMargin = ui.px(6); rightMargin = ui.px(6) })
        g.addView(btn(Icon.PLUS, +1, "Raise the $label"), ui.lp(ui.px(60), ui.px(60)))
        return g
    }

    private fun step(v: Int, dir: Int): Int {
        // small blind: by 1 up to 20, by 5 up to 100, then by 25
        val base = if (dir > 0) v else v - 1
        val s = if (base < 20) 1 else if (base < 100) 5 else 25
        return v + dir * s
    }

    /** Change the small blind (big blind follows at 2x) or the minutes; returns the new value. */
    private fun change(i: Int, isMinutes: Boolean, dir: Int): Int {
        val lv = levels[i]
        val nv: Int
        if (isMinutes) {
            nv = (lv.minutes + dir).coerceIn(1, 180)
            levels[i] = lv.copy(minutes = nv)
        } else {
            nv = step(lv.sb, dir).coerceIn(1, 50000)
            levels[i] = lv.copy(sb = nv, bb = nv * 2)
        }
        save()
        return nv
    }

    /** × must be pressed twice (within 3 s) so a stray OK never deletes a level. */
    private var deleteArmedRow = -1
    private var deleteArmedUntil = 0L
    private fun deletePressed(i: Int, btn: TextView) {
        val now = SystemClock.elapsedRealtime()
        if (deleteArmedRow == i && now < deleteArmedUntil) {
            deleteArmedRow = -1
            removeRow(i)
            return
        }
        deleteArmedRow = i
        deleteArmedUntil = now + 3000
        btn.background = ui.pillBg(C.STOP_BG, C.ORANGE, C.STOP_BG, C.ORANGE, 30f)
        Toast.makeText(this, "Press \u00D7 again to remove this row", Toast.LENGTH_SHORT).show()
        btn.postDelayed({
            if (deleteArmedRow == i && SystemClock.elapsedRealtime() >= deleteArmedUntil) deleteArmedRow = -1
            if (deleteArmedRow != i) btn.background = ui.pillBg(Color.TRANSPARENT, C.EDGE, C.PANEL_HI, C.GOLD, 30f)
        }, 3100)
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

    // ---------- open at startup ----------

    /** Android 10 and later only let an app open itself at startup with "Display over other apps" allowed. */
    private fun startupAllowed() = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)

    private fun refreshStartup() {
        val on = Prefs.openAtStartup(this)
        toggleText(startupBtn, if (on) "Open at startup:  On" else "Open at startup:  Off", on)
        startupNote.visibility = if (on && !startupAllowed()) View.VISIBLE else View.GONE
        if (on && !startupAllowed()) {
            startupNote.text = if (hasOverlayScreen())
                "Allow \u201CDisplay over other apps\u201D for Poker Timer: Settings \u203A Apps \u203A Special app access"
            else "This box doesn't offer \u201CDisplay over other apps\u201D, so it can't open the timer at startup"
            startupNote.setTextColor(C.ORANGE)
        } else {
            startupNote.text = ""
            startupNote.setTextColor(C.MUTED)
        }
    }

    /** True when this box has a screen for "Display over other apps" (some Google TV boxes don't). */
    private fun hasOverlayScreen(): Boolean =
        listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        ).any { it.resolveActivity(packageManager) != null }

    private fun askStartupPermission() {
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        for (i in tries) {
            if (i.resolveActivity(packageManager) == null) continue
            try { startActivity(i); return } catch (e: Exception) {}
        }
        Toast.makeText(this, "This box has no \u201CDisplay over other apps\u201D setting, so it can't open the timer by itself at startup", Toast.LENGTH_LONG).show()
    }

    // ---------- update app ----------

    /** The button's own label shows where things stand; problems also pop up as a message. */
    private fun setUpdate(button: String, note: String, noteColor: Int = C.MUTED) {
        updateBtn.text = button
        if (noteColor == C.ORANGE) Toast.makeText(this, note, Toast.LENGTH_LONG).show()
    }

    /** Opens Android's "Install unknown apps" switch for Poker Timer. */
    private fun askInstallPermission() {
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        )
        Toast.makeText(this, "Allow Poker Timer to install apps, then press Update again", Toast.LENGTH_LONG).show()
        for (i in tries) {
            if (i.resolveActivity(packageManager) == null) continue
            try { startActivity(i); return } catch (e: Exception) {}
        }
        Toast.makeText(this, "Open Settings \u203A Apps \u203A Special app access \u203A Install unknown apps, and allow Poker Timer", Toast.LENGTH_LONG).show()
    }

    private fun updatePressed() {
        val rel = available
        if (rel != null && !Updater.canInstall(this)) { askInstallPermission(); return }
        if (!Updater.busy.compareAndSet(false, true)) {
            Toast.makeText(this, "An update is already downloading", Toast.LENGTH_SHORT).show()
            return
        }
        if (rel == null) {
            setUpdate("Checking\u2026", "Looking for a newer version on GitHub")
            Thread {
                val result = try { Result.success(Updater.newest()) } catch (e: Exception) { Result.failure(e) }
                Updater.busy.set(false)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val mine = Updater.currentNumber(this)
                    result.fold({ r ->
                        if (r != null && r.number > mine) {
                            available = r
                            setUpdate("Update to 1.0.${r.number}", "You have ${Updater.currentName(this)}. Press to download and install.", C.GOLD)
                        } else {
                            setUpdate("Up to date  \u00B7  ${Updater.currentName(this)}", "")
                        }
                    }, { e ->
                        setUpdate("Check for update", "Couldn't reach GitHub (${e.message ?: "no connection"})", C.ORANGE)
                    })
                }
            }.start()
        } else {
            setUpdate("Downloading 0%", "Getting Poker Timer 1.0.${rel.number}")
            Thread {
                val result = try {
                    val f = Updater.download(this, rel) { p -> runOnUiThread { if (!isDestroyed) updateBtn.text = "Downloading $p%" } }
                    runOnUiThread { if (!isDestroyed) setUpdate("Installing\u2026", "Press Install on the next screen") }
                    Updater.install(this, f)
                    Result.success(Unit)
                } catch (e: Exception) { Result.failure(e) }
                Updater.busy.set(false)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // installer is open (or it failed): if he cancels, the button is ready to try again
                    result.onSuccess { setUpdate("Update to 1.0.${rel.number}", "") }
                    result.onFailure { e ->
                        setUpdate("Update to 1.0.${rel.number}", "Update failed (${e.message ?: "unknown"}). Press to try again.", C.ORANGE)
                    }
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.theme(this) != themeShown) { recreate(); return }
        if (::startupBtn.isInitialized) refreshStartup()
        if (::knockBtn.isInitialized) refreshKnock()
    }

    private fun refreshKnock() {
        val i = Sounds.ALL.indexOf(Sounds.byId(Prefs.knockSound(this)))
        knockBtn.text = "Sound:  " + Sounds.ALL[i].name
        ui.icon(knockBtn, Icon.SLIDERS, C.CREAM, 28f)
    }

    private fun switchRow(label: String, key: String, read: () -> Boolean): TextView =
        ui.button(label, height = 56f, textSize = 28f).apply {
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
            restoreBtn.text = "Press again"
            restoreBtn.postDelayed({
                if (SystemClock.elapsedRealtime() >= restoreConfirmUntil) restoreBtn.text = "Reset"
            }, 4100)
            return
        }
        restoreConfirmUntil = 0
        restoreBtn.text = "Reset"
        levels.clear(); levels.addAll(Lineup.DEFAULT)
        save()
        rebuildList()
        Toast.makeText(this, "Lineup reset", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        Announcer.hushPreview()
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
        // top right: Theme, then Update app
        val themeBtn = ui.button("Theme:  " + Theme.name(Prefs.theme(this)), height = 84f, textSize = 28f).apply {
            setPadding(ui.px(36), 0, ui.px(36), 0)
            setOnClickListener {
                val ids = Theme.ALL.map { it.first }
                val next = ids[(ids.indexOf(Prefs.theme(this@SetupActivity)) + 1) % ids.size]
                Prefs.setTheme(this@SetupActivity, next)
                focusThemeAfterRedraw = true
                recreate()
            }
        }
        updateBtn = ui.button("Update app  \u00B7  ${Updater.currentName(this)}", height = 84f, textSize = 28f).apply {
            ui.icon(this, Icon.DOWNLOAD, C.CREAM, 28f)
            setPadding(ui.px(36), 0, ui.px(36), 0)
            setOnClickListener { updatePressed() }
        }
        header.addView(themeBtn)
        header.addView(updateBtn, (updateBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = ui.px(20) })
        body.addView(header, ui.lp(ui.MATCH, ui.WRAP))

        body.addView(ui.text(26f, C.MUTED, ui.bold).apply {
            text = "Use \u2212 and + to change the small blind and the minutes  \u00B7  the big blind is always twice the small blind"
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
        fun head(s: String, w: Int, ml: Int) = heads.addView(ui.text(22f, C.MUTED, ui.bold, 0.1f).apply { text = s; if (s != "LEVEL") gravity = Gravity.CENTER_HORIZONTAL },
            ui.lp(ui.px(w), ui.WRAP).apply { leftMargin = ui.px(ml) })
        head("LEVEL", COL_LABEL, 0); head("SMALL BLIND", COL_GROUP, GAP); head("BIG BLIND", COL_BB, GAP); head("MINUTES", COL_GROUP, GAP)
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
        // lower right of the lineup panel: Reset (back to my lineup), then Done
        adds.addView(View(this), ui.lp(0, 1, 1f))
        restoreBtn = ui.button("Reset", height = 68f).apply {
            ui.icon(this, Icon.RESET, C.CREAM, 26f)
            setOnClickListener { restoreDefault() }
        }
        // fixed width so "Press again" doesn't push Done around
        adds.addView(restoreBtn, ui.lp(ui.px(250), ui.px(68)))
        left.addView(adds, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(16) })

        // right: switches (scrolls if they don't all fit)
        val rightPanel = ui.col().apply {
            background = ui.round(C.PANEL, 32f)
            setPadding(ui.px(40), ui.px(16), ui.px(40), ui.px(16))
        }
        val rightSide = ui.col()
        main.addView(rightSide, ui.lp(ui.px(600), ui.MATCH).apply { leftMargin = ui.px(56) })
        rightSide.addView(rightPanel, ui.lp(ui.MATCH, 0, 1f))
        val done = ui.button("Done", height = 76f, textSize = 34f).apply {
            typeface = ui.black
            ui.icon(this, Icon.CHECK, C.CREAM, 32f)
            setPadding(ui.px(48), 0, ui.px(48), 0)
            setOnClickListener { finish() }
        }
        val doneRow = ui.row().apply { gravity = Gravity.END }
        doneRow.addView(done)
        rightSide.addView(doneRow, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(18) })
        val rightScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val right = ui.col().apply { setPadding(0, ui.px(12), 0, ui.px(8)) }
        rightScroll.addView(right)
        rightPanel.addView(rightScroll, ui.lp(ui.MATCH, ui.MATCH))
        right.addView(ui.text(24f, C.MUTED, ui.bold, 0.16f).apply { text = "SOUND & VOICE" })
        val gap = { v: View, top: Int -> right.addView(v, ui.lp(ui.MATCH, (v.layoutParams as? LinearLayout.LayoutParams)?.height ?: ui.WRAP).apply { topMargin = ui.px(top) }) }
        gap(switchRow("Chime & notification", "sounds") { Prefs.sounds(this) }, 10)
        gap(ui.button("Voice", height = 56f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            fun show() {
                val m = Prefs.voiceMode(this@SetupActivity)
                toggleText(this, "Voice:  " + when (m) {
                    Prefs.VOICE_OFF -> "Off"
                    Prefs.VOICE_ALL -> "1-min warning + blinds"
                    else -> "Blinds only"
                }, m != Prefs.VOICE_OFF)
            }
            show()
            // OK steps through Blinds only -> 1-min warning + blinds -> Off
            setOnClickListener {
                val m = Prefs.voiceMode(this@SetupActivity)
                Prefs.setVoiceMode(this@SetupActivity, when (m) {
                    Prefs.VOICE_BLINDS -> Prefs.VOICE_ALL
                    Prefs.VOICE_ALL -> Prefs.VOICE_OFF
                    else -> Prefs.VOICE_BLINDS
                })
                show()
            }
        }, 10)
        knockBtn = ui.button("Notification sound", height = 56f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { startActivity(Intent(this@SetupActivity, SoundsActivity::class.java)) }
        }
        gap(knockBtn, 10)
        refreshKnock()
        gap(ui.button("Test sounds", height = 56f).apply {
            ui.icon(this, Icon.PLAY, C.CREAM, 26f)
            setOnClickListener { Announcer.demo() }
        }, 10)
        right.addView(ui.text(24f, C.MUTED, ui.bold, 0.16f).apply { text = "OTHER" }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(22) })
        startupBtn = ui.button("Open at startup", height = 56f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            setOnClickListener {
                val nv = !Prefs.openAtStartup(this@SetupActivity)
                Prefs.set(this@SetupActivity, "openAtStartup", nv)
                refreshStartup()
                if (nv && !startupAllowed()) askStartupPermission()
            }
        }
        gap(startupBtn, 10)
        startupNote = ui.text(22f, C.MUTED, ui.bold).apply { setSingleLine(false) }
        right.addView(startupNote, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(8) })
        refreshStartup()
        gap(switchRow("Blinds in chips", "chips") { Prefs.chips(this) }, 10)
        gap(switchRow("Test mode (75 s levels)", "testMode") { Prefs.testMode(this) }, 10)

        rebuildList()
        root.post {
            if (focusThemeAfterRedraw) { focusThemeAfterRedraw = false; themeBtn.requestFocus() } else done.requestFocus()
        }
        return root
    }
}
