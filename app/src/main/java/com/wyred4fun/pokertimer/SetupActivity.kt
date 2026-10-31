package com.wyred4fun.pokertimer

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var preGameBtn: TextView
    private lateinit var knockBtn: TextView
    private lateinit var updateBtn: TextView
    private var available: Updater.Release? = null
    private lateinit var startupNote: TextView
    private lateinit var nowLabel: TextView
    private lateinit var nowClock: TextView
    private lateinit var nowPaused: TextView
    private lateinit var gameTime: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() { showNow(); handler.postDelayed(this, 250) }
    }

    private companion object {
        // column widths on the 1920-wide drawing
        const val COL_LABEL = 160
        const val COL_GROUP = 230
        const val COL_BB = 130
        const val GAP = 16
        /** The round buttons at the end of each row: move up, move down, remove. */
        const val ROW_BTN = 52
        const val ROW_BTN_GAP = 6
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
    private fun save(removedAt: Int = -1, moved: Pair<Int, Int>? = null) {
        Lineup.save(this, levels)
        MainActivity.live?.let { e ->
            val now = SystemClock.elapsedRealtime()
            e.update(now)
            // keep the clock on the same level when rows in front of it are removed or moved
            var shift = if (removedAt in 0 until e.idx) 1 else 0
            if (moved != null) {
                val (from, to) = moved
                if (e.idx == from) shift = from - to else if (e.idx == to) shift = to - from
            }
            e.setLevels(ArrayList(levels), now, shift)
        }
    }

    /** Swap row [i] with the one above (dir -1) or below (dir +1); the highlight follows the row. */
    private fun moveRow(i: Int, dir: Int) {
        val j = i + dir
        if (j < 0 || j >= levels.size) return
        val a = levels[i]; levels[i] = levels[j]; levels[j] = a
        deleteArmedRow = -1
        save(moved = i to j)
        rebuildList(j, if (dir < 0) 0 else 1)
    }

    // ---------- the list of levels ----------

    /** [focusBtn]: 0 = move up, 1 = move down, 2 = remove. */
    private fun rebuildList(focusRow: Int = -1, focusBtn: Int = 2) {
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
            fun roundBtn(icon: Icon, what: String, click: (TextView) -> Unit) = ui.button("").apply {
                background = ui.pillBg(Color.TRANSPARENT, C.EDGE, C.PANEL_HI, C.GOLD, 30f)
                val d = IconDrawable(icon, C.MUTED)
                d.setBounds(0, 0, ui.px(28), ui.px(28))
                setCompoundDrawables(d, null, null, null)
                setPadding(ui.px(12), 0, 0, 0)
                contentDescription = what
                setOnClickListener { click(this) }
            }
            val up = roundBtn(Icon.UP, "Move this row up") { moveRow(i, -1) }
            val down = roundBtn(Icon.DOWN, "Move this row down") { moveRow(i, +1) }
            val del = roundBtn(Icon.CROSS, "Remove this row") { deletePressed(i, it) }
            row.addView(up, ui.lp(ui.px(ROW_BTN), ui.px(ROW_BTN)).apply { leftMargin = ui.px(12) })
            row.addView(down, ui.lp(ui.px(ROW_BTN), ui.px(ROW_BTN)).apply { leftMargin = ui.px(ROW_BTN_GAP) })
            row.addView(del, ui.lp(ui.px(ROW_BTN), ui.px(ROW_BTN)).apply { leftMargin = ui.px(12) })
            listCol.addView(row, ui.lp(ui.MATCH, ui.px(84)).apply { topMargin = if (i == 0) 0 else ui.px(10) })
            if (i == focusRow) focusTarget = when (focusBtn) { 0 -> up; 1 -> down; else -> del }
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
        val pre = PreGame.enabled(this)
        if (::preGameBtn.isInitialized) toggleText(preGameBtn, if (pre) "Friday countdown:  On" else "Friday countdown:  Off", pre)
        // both open the app by themselves, which needs "Display over other apps" on Android 10 and later
        val needs = (on || pre) && !startupAllowed()
        startupNote.visibility = if (needs) View.VISIBLE else View.GONE
        if (needs) {
            startupNote.text = if (hasOverlayScreen())
                "Allow it: Settings \u203A Apps \u203A Special app access"
            else "This box can't open the timer by itself"
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
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    /** The small live clock at the top: where the night is while changes are being made. */
    private fun showNow() {
        if (!::nowClock.isInitialized) return
        val e = MainActivity.live
        // how long the night has been going, since Start was first pressed
        val started = if (e != null) MainActivity.gameStartedAt else 0L
        val g = if (started > 0) ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0) else -1L
        val gt = if (g < 0) "" else "GAME TIME  %d:%02d:%02d".format(g / 3600, g / 60 % 60, g % 60)
        if (gameTime.text.toString() != gt) gameTime.text = gt
        if (e == null) { nowLabel.text = ""; nowClock.text = ""; nowPaused.text = ""; return }
        val now = SystemClock.elapsedRealtime()
        val s = e.secondsLeft(now)
        val cur = e.current
        val nx = e.next
        val num = e.levels.take(e.idx + 1).count { !it.isBreak }
        val where = if (cur.isBreak) "BREAK" else "LEVEL $num  \u00B7  ${cur.blindsText}"
        nowLabel.text = if (e.finished) "FINAL LEVEL COMPLETE" else where
        nowPaused.text = if (!e.running && !e.finished) "PAUSED" else ""
        val t = "%d:%02d".format(s / 60, s % 60)
        if (nowClock.text.toString() != t) nowClock.text = t
        val counting = e.running && nx != null && s in 1..e.knocks
        val warning = e.running && nx != null && s in (e.knocks + 1)..e.warnAt && e.durationMs(e.idx) > e.warnAt * 1000L
        nowClock.setTextColor(if (counting) C.ORANGE else if (warning || cur.isBreak) C.GOLD else C.CREAM)
    }

    private fun refreshKnock() {
        val i = Sounds.ALL.indexOf(Sounds.byId(Prefs.knockSound(this)))
        knockBtn.text = "Sound:  " + Sounds.ALL[i].name
        ui.icon(knockBtn, Icon.SLIDERS, C.CREAM, 28f)
    }

    private fun switchRow(label: String, key: String, read: () -> Boolean): TextView =
        ui.button(label, height = 48f, textSize = 28f).apply {
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

    // ---------- saved lineups ----------

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** Pick one of the saved spots, then save the lineup there or load it. */
    private fun savedLineups() {
        val names = (1..Lineup.SLOTS).map { n ->
            val l = Lineup.slot(this, n)
            "Lineup $n   " + (if (l == null) "(empty)" else Lineup.summary(l))
        }.toTypedArray()
        dialog().setTitle("Saved lineups").setItems(names) { _, which -> slotChosen(which + 1) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun slotChosen(n: Int) {
        val saved = Lineup.slot(this, n)
        val choices = ArrayList<String>()
        if (saved != null) choices.add("Load lineup $n  (${Lineup.summary(saved)})")
        choices.add(if (saved == null) "Save the lineup on screen as lineup $n" else "Replace lineup $n with the lineup on screen")
        dialog().setTitle("Lineup $n").setItems(choices.toTypedArray()) { _, which ->
            if (saved != null && which == 0) {
                levels.clear(); levels.addAll(saved)
                save(); rebuildList()
                Toast.makeText(this, "Lineup $n loaded", Toast.LENGTH_SHORT).show()
            } else {
                Lineup.saveSlot(this, n, levels)
                Toast.makeText(this, "Saved as lineup $n", Toast.LENGTH_SHORT).show()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
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
        header.addView(titles, ui.lp(ui.WRAP, ui.WRAP))
        // live clock in the empty space between the title and Theme
        val nowCol = ui.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
        nowLabel = ui.text(22f, C.MUTED, ui.bold, 0.12f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nowClock = ui.text(72f, C.CREAM, ui.bebas, 0.04f)
        nowPaused = ui.text(22f, C.MUTED, ui.bold, 0.12f)
        val clockRow = ui.row().apply { gravity = Gravity.BOTTOM }
        clockRow.addView(nowClock, ui.lp(ui.WRAP, ui.WRAP))
        clockRow.addView(nowPaused, ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(14); bottomMargin = ui.px(12) })
        nowCol.addView(nowLabel, ui.lp(ui.WRAP, ui.WRAP))
        nowCol.addView(clockRow, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(4) })
        header.addView(nowCol, ui.lp(0, ui.WRAP, 1f).apply { leftMargin = ui.px(30); rightMargin = ui.px(30) })
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

        // hint on the left, the game timer on the right
        val hintRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        hintRow.addView(ui.text(26f, C.MUTED, ui.bold).apply {
            text = "Use \u2212 and + to change the small blind and the minutes  \u00B7  big blind is always twice the small  \u00B7  \u25B2 \u25BC move a row"
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, ui.lp(0, ui.WRAP, 1f))
        gameTime = ui.text(30f, C.CREAM, ui.bold, 0.06f)
        hintRow.addView(gameTime, ui.lp(ui.WRAP, ui.WRAP).apply { leftMargin = ui.px(24) })
        body.addView(hintRow, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(18) })

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
        val savedBtn = ui.button("Saved lineups", height = 68f).apply { setOnClickListener { savedLineups() } }
        adds.addView(savedBtn, (savedBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = ui.px(16) })
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
        gap(switchRow("Chime & notification", "sounds") { Prefs.sounds(this) }, 6)
        gap(ui.button("Voice", height = 48f, textSize = 28f).apply {
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
        }, 6)
        knockBtn = ui.button("Notification sound", height = 48f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { startActivity(Intent(this@SetupActivity, SoundsActivity::class.java)) }
        }
        gap(knockBtn, 6)
        refreshKnock()
        gap(ui.button("Test sounds", height = 48f).apply {
            ui.icon(this, Icon.PLAY, C.CREAM, 26f)
            setOnClickListener { Announcer.demo() }
        }, 6)
        right.addView(ui.text(24f, C.MUTED, ui.bold, 0.16f).apply { text = "OTHER" }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(14) })
        startupBtn = ui.button("Open at startup", height = 48f, textSize = 28f).apply {
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
        gap(startupBtn, 6)
        // Thursday 5 PM to Friday 7:30 PM: the "Poker starts sharp at 7:30" countdown, and the app opens itself Thursday at 5
        preGameBtn = ui.button("Friday countdown", height = 48f, textSize = 28f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(26), 0, ui.px(26), 0)
            background = ui.pillBg(C.FELT, C.EDGE, C.PANEL_HI, C.GOLD, 20f)
            setOnClickListener {
                val nv = !PreGame.enabled(this@SetupActivity)
                Prefs.set(this@SetupActivity, "preGame", nv)
                PreGame.schedule(this@SetupActivity)
                refreshStartup()
                if (nv && !startupAllowed()) askStartupPermission()
            }
        }
        // the Friday countdown and the slides are only in the Catalpa Club Poker Timer version
        if (BuildConfig.CLUB) {
            gap(preGameBtn, 6)
            gap(switchRow("Club billboard slides", "billboard") { Billboard.enabled(this) }, 6)
        }
        startupNote = ui.text(20f, C.MUTED, ui.bold).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        right.addView(startupNote, ui.lp(ui.MATCH, ui.WRAP).apply { topMargin = ui.px(8) })
        refreshStartup()
        gap(switchRow("Blinds in chips", "chips") { Prefs.chips(this) }, 6)
        gap(switchRow("Test mode (75 s levels)", "testMode") { Prefs.testMode(this) }, 6)

        rebuildList()
        root.post {
            if (focusThemeAfterRedraw) { focusThemeAfterRedraw = false; themeBtn.requestFocus() } else done.requestFocus()
        }
        return root
    }
}
