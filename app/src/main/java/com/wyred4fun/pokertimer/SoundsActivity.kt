package com.wyred4fun.pokertimer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Knock sound picker: every sound made so far, two per row.
 * OK plays it three times (like the countdown) and makes it the knock.
 */
class SoundsActivity : Activity() {

    private lateinit var ui: Ui
    private var themeShown = ""
    private val tiles = ArrayList<Pair<Sounds.Sound, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Announcer.init(this)
        Theme.apply(Prefs.theme(this)); themeShown = Prefs.theme(this)
        ui = Ui(this)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.theme(this) != themeShown) recreate()
    }

    override fun onPause() {
        super.onPause()
        Announcer.hushPreview()
    }

    private fun tileBg(selected: Boolean) =
        // chosen: normal fill with a green edge (and the check); highlighted by the remote: bright fill, thick ring
        if (selected) ui.pillBg(C.PANEL, C.GREEN, C.PANEL_HI, C.CREAM, 20f)
        else ui.pillBg(C.PANEL, C.EDGE, C.PANEL_HI, C.GOLD, 20f)

    private fun refresh() {
        val cur = Sounds.byId(Prefs.knockSound(this)).id
        for ((s, t) in tiles) {
            val sel = s.id == cur
            t.background = tileBg(sel)
            if (sel) {
                val d = IconDrawable(Icon.CHECK, C.GREEN)
                d.setBounds(0, 0, ui.px(30), ui.px(30))
                t.setCompoundDrawables(null, null, d, null)
            } else {
                t.setCompoundDrawables(null, null, null, null)
            }
        }
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C.FELT) }
        val body = ui.col().apply { setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(40)) }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))

        val header = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = ui.col()
        titles.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "POKER NIGHT" })
        titles.addView(ui.text(80f, C.GOLD, ui.bebas, 0.04f).apply { text = "NOTIFICATION SOUND" },
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
            text = "Press OK to hear a sound. The one with the check plays as the blinds go up."
        }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(18) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; clipToPadding = false }
        val list = ui.col().apply { setPadding(0, ui.px(8), 0, ui.px(8)) }
        scroll.addView(list)
        body.addView(scroll, ui.lp(ui.MATCH, 0, 1f).apply { topMargin = ui.px(24) })

        var row: LinearLayout? = null
        for ((i, s) in Sounds.ALL.withIndex()) {
            if (i % 2 == 0) {
                row = ui.row()
                list.addView(row, ui.lp(ui.MATCH, ui.px(84)).apply { if (i > 0) topMargin = ui.px(14) })
            }
            val t = ui.text(30f, C.CREAM, ui.bold).apply {
                text = s.name
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(28), 0, ui.px(28), 0)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    Announcer.pickKnock(this@SoundsActivity, s.id)
                    refresh()
                }
            }
            row!!.addView(t, ui.lp(0, ui.MATCH, 1f).apply { if (i % 2 == 1) leftMargin = ui.px(24) })
            tiles.add(s to t)
        }
        if (Sounds.ALL.size % 2 == 1) row!!.addView(View(this), ui.lp(0, ui.MATCH, 1f).apply { leftMargin = ui.px(24) })
        refresh()

        // start on the sound in use
        val cur = Sounds.byId(Prefs.knockSound(this)).id
        root.post { (tiles.firstOrNull { it.first.id == cur }?.second ?: done).requestFocus() }
        return root
    }
}
