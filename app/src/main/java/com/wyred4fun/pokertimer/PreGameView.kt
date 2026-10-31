package com.wyred4fun.pokertimer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen Friday countdown ("Poker starts sharp at 7:30 PM") laid over the timer.
 * Cards and chips pop up around the edges one at a time.
 */
class PreGameView(ctx: Context, private val ui: Ui) : FrameLayout(ctx) {
    private val clockText: TextView
    private val boxes: LinearLayout
    private val hh: TextView
    private val mm: TextView
    private val ss: TextView
    private val ssBox: View
    private val colons = ArrayList<TextView>()
    private val dealText: TextView
    private val tipText: TextView
    private val pops = ArrayList<View>()
    private var popIdx = 0
    private var tips: List<String> = emptyList()
    private var tipIdx = 0
    private var tipAt = 0L

    init {
        setBackgroundColor(C.FELT)
        isClickable = true
        // the table edge, as on the timer screen
        addView(View(ctx).apply { background = ui.round(0, 48f, 3, C.RULE) },
            LayoutParams(-1, -1).apply { setMargins(ui.px(24), ui.px(24), ui.px(24), ui.px(24)) })

        // things that pop up around the edges (drawn behind the words)
        fun pop(v: View, w: Int, h: Int, g: Int, x: Int, y: Int, rot: Float) {
            v.alpha = 0f; v.rotation = rot
            addView(v, LayoutParams(ui.px(w), ui.px(h), g).apply {
                if (g and Gravity.END == Gravity.END) rightMargin = ui.px(x) else leftMargin = ui.px(x)
                if (g and Gravity.BOTTOM == Gravity.BOTTOM) bottomMargin = ui.px(y) else topMargin = ui.px(y)
            })
            pops.add(v)
        }
        val tl = Gravity.TOP or Gravity.START; val tr = Gravity.TOP or Gravity.END
        val bl = Gravity.BOTTOM or Gravity.START; val br = Gravity.BOTTOM or Gravity.END
        pop(CardsView(ctx, listOf("A" to 0, "K" to 1)), 230, 230, tl, 140, 290, -14f)
        pop(ChipView(ctx, ui).apply { value = "25" }, 160, 160, tr, 170, 320, 12f)
        pop(ChipStackView(ctx), 150, 170, bl, 220, 170, 6f)
        pop(CardsView(ctx, listOf("Q" to 2, "J" to 3, "10" to 0)), 300, 240, br, 180, 180, -8f)
        pop(ui.text(30f, C.GOLD_INK, ui.black).apply {
            text = "ALL IN"; gravity = Gravity.CENTER; background = ui.round(C.GOLD, 40f)
        }, 190, 74, tl, 130, 590, -6f)
        pop(ChipView(ctx, ui).apply { value = "100" }, 140, 140, tr, 210, 640, -9f)

        // header, matching the timer screen
        val header = ui.row().apply { gravity = Gravity.BOTTOM }
        val left = ui.col()
        left.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "POKER NIGHT" })
        left.addView(ui.text(72f, C.GOLD, ui.bebas, 0.04f).apply { text = PreGame.TITLE },
            ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })
        val right = ui.col().apply { gravity = Gravity.END }
        right.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = "TIME" })
        clockText = ui.text(72f, C.CREAM, ui.bebas, 0.04f)
        right.addView(clockText, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(6) })
        header.addView(left, ui.lp(0, ui.WRAP, 1f))
        header.addView(right)
        addView(header, LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = ui.px(96); rightMargin = ui.px(96); topMargin = ui.px(56)
        })

        // the message and the big countdown
        val center = ui.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
        center.addView(ui.text(40f, C.MUTED, ui.bold, 0.3f).apply { text = "POKER STARTS SHARP AT"; gravity = Gravity.CENTER },
            ui.lp(ui.MATCH, ui.WRAP))
        center.addView(ui.text(250f, C.GOLD, ui.bebas, 0.02f).apply { text = PreGame.STARTS_AT },
            ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(14) })
        boxes = ui.row().apply { gravity = Gravity.TOP }
        fun box(label: String): Pair<TextView, View> {
            val col = ui.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val num = ui.text(210f, C.CREAM, ui.bebas).apply {
                gravity = Gravity.CENTER
                background = ui.round(C.PANEL, 32f, 3, C.EDGE)
                setPadding(0, ui.px(14), 0, 0)
            }
            col.addView(num, ui.lp(ui.px(260), ui.px(230)))
            col.addView(ui.text(26f, C.MUTED, ui.bold, 0.24f).apply { text = label },
                ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(12) })
            boxes.addView(col)
            return num to num
        }
        fun colon() {
            val t = ui.text(180f, C.GOLD, ui.bebas).apply { text = ":"; gravity = Gravity.CENTER }
            boxes.addView(t, ui.lp(ui.WRAP, ui.px(230)).apply { leftMargin = ui.px(20); rightMargin = ui.px(20) })
            colons.add(t)
        }
        hh = box("HOURS").first; colon(); mm = box("MINUTES").first; colon()
        val s = box("SECONDS"); ss = s.first; ssBox = s.second
        center.addView(boxes, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(22) })
        dealText = ui.text(170f, C.GOLD_INK, ui.bebas, 0.03f).apply {
            text = "SHUFFLE UP AND DEAL"; gravity = Gravity.CENTER
            background = ui.round(C.GOLD, 40f)
            setPadding(ui.px(80), ui.px(36), ui.px(80), ui.px(30))
            visibility = View.GONE
        }
        center.addView(dealText, ui.lp(ui.WRAP, ui.WRAP).apply { topMargin = ui.px(40) })
        addView(center, LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = ui.px(210) })

        // bottom banner
        val banner = ui.row().apply {
            gravity = Gravity.CENTER
            background = ui.round(C.GOLD, 52f)
        }
        banner.addView(ui.text(44f, C.GOLD_INK, ui.black).apply { text = "Doors open — grab a seat and your chips" })
        banner.addView(View(ctx).apply { background = ui.round(C.GOLD_INK, 6f) },
            ui.lp(ui.px(10), ui.px(10)).apply { leftMargin = ui.px(28); rightMargin = ui.px(28) })
        tipText = ui.text(44f, C.GOLD_INK, ui.black)
        banner.addView(tipText)
        addView(banner, LayoutParams(-1, ui.px(104), Gravity.BOTTOM).apply {
            leftMargin = ui.px(96); rightMargin = ui.px(96); bottomMargin = ui.px(72)
        })
    }

    /** The rotating line in the banner, taken from the lineup itself. */
    fun setLineup(levels: List<Level>) {
        val t = ArrayList<String>()
        val first = levels.firstOrNull { !it.isBreak }
        if (first != null) {
            t.add("Blinds start at ${first.sb}–${first.bb}")
            t.add("${first.minutes}-minute levels to start")
        }
        val b = levels.indexOfFirst { it.isBreak }
        if (b > 0) t.add("First break after level ${levels.take(b).count { !it.isBreak }}")
        if (t != tips) { tips = t; tipIdx = 0; tipText.text = t.firstOrNull() ?: "" }
    }

    /** Call a few times a second while showing. [wall] = System.currentTimeMillis(). */
    fun update(wall: Long, clock: String, uptime: Long) {
        if (clockText.text.toString() != clock) clockText.text = clock
        val w = PreGame.window(wall)
        var left = Math.ceil((w.gameAt - wall) / 1000.0).toLong()
        val counting = left > 0
        if (left < 0) left = 0
        boxes.visibility = if (counting) View.VISIBLE else View.GONE
        dealText.visibility = if (counting) View.GONE else View.VISIBLE
        fun two(n: Long) = if (n < 10) "0$n" else n.toString()
        set(hh, two(left / 3600)); set(mm, two(left / 60 % 60)); set(ss, two(left % 60))
        val last = counting && left <= 60
        ss.setTextColor(if (last) C.GOLD_HI else C.CREAM)
        ssBox.background = ui.round(C.PANEL, 32f, 3, if (last) C.GOLD else C.EDGE)
        val on = (wall / 500) % 2 == 0L
        for (c in colons) c.alpha = if (on) 1f else 0.35f
        // a new line in the banner every 6 seconds
        if (tips.size > 1 && uptime - tipAt >= 6000) {
            if (tipAt != 0L) { tipIdx = (tipIdx + 1) % tips.size; tipText.text = tips[tipIdx] }
            tipAt = uptime
        }
    }

    private fun set(t: TextView, s: String) { if (t.text.toString() != s) t.text = s }

    // ---------- the pop-ups: one every 3 seconds, each stays about 4 seconds ----------

    private val popper = object : Runnable {
        override fun run() {
            val v = pops[popIdx % pops.size]; popIdx++
            v.animate().cancel()
            v.alpha = 0f; v.scaleX = 0.55f; v.scaleY = 0.55f; v.translationY = ui.px(40).toFloat()
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(450)
                .setInterpolator(OvershootInterpolator(1.6f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        v.animate().setListener(null).alpha(0f).translationY(-ui.px(24).toFloat())
                            .scaleX(0.92f).scaleY(0.92f).setStartDelay(3600).setDuration(700)
                            .setInterpolator(null).start()
                    }
                }).setStartDelay(0).start()
            postDelayed(this, 3000)
        }
    }

    fun startPops() { removeCallbacks(popper); if (animatePops) post(popper) }

    companion object {
        /** The screen tests turn the never-ending pop-ups off (the test runner plays every animation frame). */
        var animatePops = true
    }
    fun stopPops() {
        removeCallbacks(popper)
        for (v in pops) { v.animate().setListener(null).cancel(); v.alpha = 0f }
    }
}

/** A few playing cards fanned out; suits drawn as shapes (no font symbols). 0 spade, 1 heart, 2 diamond, 3 club. */
class CardsView(ctx: Context, private val cards: List<Pair<String, Int>>) : View(ctx) {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBF8F1.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val r = RectF()
    private val p = Path()

    override fun onDraw(c: Canvas) {
        val cw = height * 0.52f; val ch = cw * 1.4f
        val n = cards.size
        val step = cw * 0.5f
        val total = cw + step * (n - 1)
        val x0 = (width - total) / 2f; val y0 = (height - ch) / 2f
        for ((i, card) in cards.withIndex()) {
            val red = card.second == 1 || card.second == 2
            val ang = (i - (n - 1) / 2f) * 10f
            c.save()
            c.rotate(ang, x0 + step * i + cw / 2f, y0 + ch)
            val x = x0 + step * i
            r.set(x + 4f, y0 + 8f, x + cw + 4f, y0 + ch + 8f); c.drawRoundRect(r, cw * 0.11f, cw * 0.11f, shadow)
            r.set(x, y0, x + cw, y0 + ch); c.drawRoundRect(r, cw * 0.11f, cw * 0.11f, face)
            ink.color = if (red) 0xFFC8382F.toInt() else 0xFF141414.toInt()
            text.color = ink.color; text.textSize = cw * 0.3f
            c.drawText(card.first, x + cw * 0.1f, y0 + cw * 0.34f, text)
            suit(c, card.second, x + cw / 2f, y0 + ch * 0.58f, cw * 0.27f)
            c.restore()
        }
    }

    private fun suit(c: Canvas, s: Int, cx: Float, cy: Float, k: Float) {
        p.reset()
        when (s) {
            1 -> { // heart
                p.moveTo(cx, cy + k)
                p.cubicTo(cx - k * 1.6f, cy - k * 0.1f, cx - k * 0.9f, cy - k * 1.3f, cx, cy - k * 0.5f)
                p.cubicTo(cx + k * 0.9f, cy - k * 1.3f, cx + k * 1.6f, cy - k * 0.1f, cx, cy + k)
                c.drawPath(p, ink)
            }
            2 -> { // diamond
                p.moveTo(cx, cy - k * 1.15f); p.lineTo(cx + k * 0.8f, cy); p.lineTo(cx, cy + k * 1.15f); p.lineTo(cx - k * 0.8f, cy); p.close()
                c.drawPath(p, ink)
            }
            3 -> { // club
                c.drawCircle(cx, cy - k * 0.5f, k * 0.45f, ink)
                c.drawCircle(cx - k * 0.5f, cy + k * 0.15f, k * 0.45f, ink)
                c.drawCircle(cx + k * 0.5f, cy + k * 0.15f, k * 0.45f, ink)
                p.moveTo(cx, cy); p.lineTo(cx + k * 0.3f, cy + k * 1.05f); p.lineTo(cx - k * 0.3f, cy + k * 1.05f); p.close()
                c.drawPath(p, ink)
            }
            else -> { // spade
                p.moveTo(cx, cy - k * 1.1f)
                p.cubicTo(cx - k * 1.6f, cy - k * 0.1f, cx - k * 0.9f, cy + k * 1.1f, cx, cy + k * 0.35f)
                p.cubicTo(cx + k * 0.9f, cy + k * 1.1f, cx + k * 1.6f, cy - k * 0.1f, cx, cy - k * 1.1f)
                c.drawPath(p, ink)
                p.reset()
                p.moveTo(cx, cy + k * 0.2f); p.lineTo(cx + k * 0.3f, cy + k * 1.1f); p.lineTo(cx - k * 0.3f, cy + k * 1.1f); p.close()
                c.drawPath(p, ink)
            }
        }
    }
}

/** A short stack of chips seen from the side. */
class ChipStackView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val colors = intArrayOf(C.GOLD, 0xFFC8382F.toInt(), C.CREAM, C.PANEL_HI, C.GOLD)
        val w = width * 0.9f; val h = w * 0.27f
        val x = (width - w) / 2f
        edge.strokeWidth = h * 0.09f; edge.color = C.GOLD_INK
        for (i in colors.indices) {
            val y = height - h - i * h * 0.47f - 4f
            r.set(x, y, x + w, y + h)
            fill.color = colors[i]
            c.drawOval(r, fill); c.drawOval(r, edge)
        }
    }
}
