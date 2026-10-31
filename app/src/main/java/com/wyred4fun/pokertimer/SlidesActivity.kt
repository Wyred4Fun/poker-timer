package com.wyred4fun.pokertimer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Setup > Billboard images: every picture in the club billboard, four to a row.
 * OK on a picture switches it on or off. Pictures with dates (Fall Ball, Thanksgiving...) also come and go by themselves.
 */
class SlidesActivity : Activity() {

    private lateinit var ui: Ui
    private var themeShown = ""
    private class Tile(val name: String, val frame: FrameLayout, val pic: ImageView, val badge: TextView)
    private val tiles = ArrayList<Tile>()
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Theme.apply(Prefs.theme(this)); themeShown = Prefs.theme(this)
        ui = Ui(this)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.theme(this) != themeShown) recreate()
    }

    override fun onDestroy() { gone = true; super.onDestroy() }

    private fun frameBg(on: Boolean) =
        if (on) ui.pillBg(C.PANEL, C.GREEN, C.PANEL_HI, C.CREAM, 18f)
        else ui.pillBg(C.PANEL, C.EDGE, C.PANEL_HI, C.GOLD, 18f)

    private fun refresh(t: Tile) {
        val now = System.currentTimeMillis()
        val off = t.name in Billboard.switchedOff(this)
        val label = Billboard.status(this, t.name, now)
        val ended = label.startsWith("ENDED")
        t.badge.text = label
        t.badge.setTextColor(if (off || ended) C.ORANGE else C.GREEN)
        t.pic.alpha = if (off || ended) 0.28f else 1f
        t.frame.background = frameBg(!off && !ended)
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C.FELT) }
        val body = ui.col().apply { setPadding(ui.px(96), ui.px(48), ui.px(96), ui.px(32)) }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))

        val header = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = ui.col()
        titles.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "CLUB BILLBOARD" })
        titles.addView(ui.text(80f, C.GOLD, ui.bebas, 0.04f).apply { text = "BILLBOARD IMAGES" },
            ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })
        header.addView(titles, ui.lp(0, ui.WRAP, 1f))
        val countdown = ui.button("Show countdown now", height = 84f, textSize = 28f).apply {
            setPadding(ui.px(36), 0, ui.px(36), 0)
            setOnClickListener {
                // back to the timer screen with the Friday countdown up (any button puts it away again)
                startActivity(Intent(this@SlidesActivity, MainActivity::class.java)
                    .putExtra("showCountdown", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        header.addView(countdown, ui.lp(ui.WRAP, ui.px(84)).apply { rightMargin = ui.px(20) })
        val done = ui.button("Done", primary = true, height = 84f, textSize = 30f).apply {
            ui.icon(this, Icon.CHECK, C.GOLD_INK)
            setPadding(ui.px(44), 0, ui.px(44), 0)
            setOnClickListener { finish() }
        }
        header.addView(done)
        body.addView(header, ui.lp(ui.MATCH, ui.WRAP))

        body.addView(ui.text(26f, C.MUTED, ui.bold).apply {
            text = "Press OK on a picture to turn it on or off. Green is showing."
        }, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(14) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; clipToPadding = false }
        val list = ui.col().apply { setPadding(ui.px(6), ui.px(8), ui.px(6), ui.px(8)) }
        scroll.addView(list)
        body.addView(scroll, ui.lp(ui.MATCH, 0, 1f).apply { topMargin = ui.px(18) })

        val names = Billboard.all(this)
        var row: LinearLayout? = null
        for ((i, n) in names.withIndex()) {
            if (i % 4 == 0) {
                row = ui.row()
                list.addView(row, ui.lp(ui.MATCH, ui.px(250)).apply { if (i > 0) topMargin = ui.px(18) })
            }
            val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val title = ui.text(24f, C.CREAM, ui.bold).apply {
                text = Billboard.title(n); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.px(14), ui.px(6), ui.px(14), ui.px(6)); setBackgroundColor(0xCC000000.toInt())
            }
            val badge = ui.text(22f, C.GREEN, ui.bold, 0.06f).apply {
                setPadding(ui.px(12), ui.px(4), ui.px(12), ui.px(4)); setBackgroundColor(0xCC000000.toInt())
            }
            val frame = FrameLayout(this).apply {
                isFocusable = true; isClickable = true
                setPadding(ui.px(5), ui.px(5), ui.px(5), ui.px(5))
                addView(pic, FrameLayout.LayoutParams(-1, -1))
                addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = ui.px(5); rightMargin = ui.px(5) })
            }
            val t = Tile(n, frame, pic, badge)
            frame.setOnClickListener {
                Billboard.setOff(this, n, n !in Billboard.switchedOff(this))
                refresh(t)
            }
            row!!.addView(frame, ui.lp(0, ui.MATCH, 1f).apply { if (i % 4 > 0) leftMargin = ui.px(18) })
            tiles.add(t)
            refresh(t)
        }
        val rem = names.size % 4
        if (rem != 0) for (k in rem until 4) row!!.addView(View(this), ui.lp(0, ui.MATCH, 1f).apply { leftMargin = ui.px(18) })

        // the pictures come in one at a time so the screen opens at once
        Thread {
            for (t in tiles) {
                if (gone) return@Thread
                val bmp = try {
                    assets.open("slides/${t.name}").use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 })
                    }
                } catch (e: Exception) { null }
                if (bmp != null) runOnUiThread { if (!gone) t.pic.setImageBitmap(bmp) }
            }
        }.start()

        root.post { (tiles.firstOrNull()?.frame ?: done).requestFocus() }
        return root
    }
}
