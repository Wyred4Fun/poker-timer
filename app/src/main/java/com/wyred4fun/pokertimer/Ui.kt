package com.wyred4fun.pokertimer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Colors from the design. */
object C {
    val FELT = Color.parseColor("#0B2E24")
    val PANEL = Color.parseColor("#12392D")
    val PANEL_HI = Color.parseColor("#1D4D3E")
    val RULE = Color.parseColor("#2C5E4C")
    val EDGE = Color.parseColor("#3A6E5B")
    val CREAM = Color.parseColor("#F4EFE3")
    val MUTED = Color.parseColor("#A9C4B7")
    val GOLD = Color.parseColor("#E9B949")
    val GOLD_INK = Color.parseColor("#1C1503")
    val ORANGE = Color.parseColor("#F08A3C")
    val ORANGE_INK = Color.parseColor("#1C0B02")
    val GREEN = Color.parseColor("#5FBF94")
    val GREEN_INK = Color.parseColor("#06251A")
    val STOP_BG = Color.parseColor("#5A2A12")
}

/**
 * Everything is laid out on a 1920 x 1080 drawing and scaled to the TV,
 * so the screen looks the same on every box whatever its resolution.
 */
class Ui(private val act: Activity) {
    val scale: Float
    val bebas: Typeface = try {
        Typeface.createFromAsset(act.assets, "fonts/BebasNeue-Regular.ttf")
    } catch (e: Exception) {
        Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    init {
        val dm = act.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val h = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        scale = minOf(w / 1920f, h / 1080f)
    }

    fun px(v: Float): Int = Math.round(v * scale)
    fun px(v: Int): Int = px(v.toFloat())

    fun text(size: Float, color: Int, face: Typeface, spacing: Float = 0f): TextView =
        TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            setTextColor(color)
            typeface = face
            letterSpacing = spacing
            includeFontPadding = false
            setSingleLine(true)
        }

    fun round(fill: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius * scale
            setColor(fill)
            if (stroke > 0) setStroke(px(stroke), strokeColor)
        }

    /** Pill button background: normal / focused (gold ring) states. */
    fun pillBg(fill: Int, edge: Int, focusFill: Int, focusEdge: Int, radius: Float = 36f): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(focusFill, radius, 5, focusEdge))
            addState(intArrayOf(), round(fill, radius, 2, edge))
        }

    fun button(label: String, primary: Boolean = false, height: Float = 72f, textSize: Float = 26f): TextView =
        text(textSize, if (primary) C.GOLD_INK else C.CREAM, if (primary) black else bold).apply {
            this.text = label
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(px(30), 0, px(30), 0)
            background = if (primary) pillBg(C.GOLD, C.GOLD, Color.parseColor("#F6CF6A"), C.CREAM)
            else pillBg(C.PANEL, C.EDGE, C.PANEL_HI, C.GOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(height))
        }

    /** Put a drawn icon in front of a button's text (no font symbols, which some boxes show as boxes or emoji). */
    fun icon(t: TextView, kind: Icon, color: Int, size: Float = 30f) {
        val d = IconDrawable(kind, color)
        d.setBounds(0, 0, px(size), px(size))
        t.setCompoundDrawables(d, null, null, null)
        t.compoundDrawablePadding = px(12)
    }

    fun row(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    fun col(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
}

enum class Icon { PLAY, PAUSE, BACK, SKIP, RESET, STOP, SLIDERS, PLUS, CROSS, CHECK, UPDOWN }

/** Simple line icons drawn in code, matching the design's stroke icons. */
class IconDrawable(private val kind: Icon, color: Int) : Drawable() {
    private val fillP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = android.graphics.Paint.Style.FILL
    }
    private val lineP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val u = b.width() / 24f // icons are drawn on a 24 x 24 grid
        lineP.strokeWidth = 2.4f * u
        fun x(v: Float) = b.left + v * u
        fun y(v: Float) = b.top + v * u
        val p = android.graphics.Path()
        when (kind) {
            Icon.PLAY -> { p.moveTo(x(7f), y(4.5f)); p.lineTo(x(20f), y(12f)); p.lineTo(x(7f), y(19.5f)); p.close(); c.drawPath(p, fillP) }
            Icon.PAUSE -> { c.drawLine(x(8f), y(5f), x(8f), y(19f), lineP.apply { strokeWidth = 3f * u }); c.drawLine(x(16f), y(5f), x(16f), y(19f), lineP) }
            Icon.BACK -> { p.moveTo(x(19f), y(5f)); p.lineTo(x(9f), y(12f)); p.lineTo(x(19f), y(19f)); p.close(); c.drawPath(p, fillP); c.drawLine(x(5f), y(5f), x(5f), y(19f), lineP) }
            Icon.SKIP -> { p.moveTo(x(5f), y(5f)); p.lineTo(x(15f), y(12f)); p.lineTo(x(5f), y(19f)); p.close(); c.drawPath(p, fillP); c.drawLine(x(19f), y(5f), x(19f), y(19f), lineP) }
            Icon.RESET -> {
                c.drawArc(android.graphics.RectF(x(4f), y(4f), x(20f), y(20f)), 200f, 290f, false, lineP)
                p.moveTo(x(3.2f), y(4.5f)); p.lineTo(x(4.6f), y(10.2f)); p.lineTo(x(10f), y(8.4f)); c.drawPath(p, lineP)
            }
            Icon.STOP -> c.drawRoundRect(android.graphics.RectF(x(5f), y(5f), x(19f), y(19f)), 2f * u, 2f * u, fillP)
            Icon.SLIDERS -> {
                lineP.strokeWidth = 2.2f * u
                c.drawLine(x(4f), y(6f), x(20f), y(6f), lineP); c.drawLine(x(4f), y(12f), x(20f), y(12f), lineP); c.drawLine(x(4f), y(18f), x(20f), y(18f), lineP)
                c.drawCircle(x(15f), y(6f), 2.4f * u, fillP); c.drawCircle(x(9f), y(12f), 2.4f * u, fillP); c.drawCircle(x(17f), y(18f), 2.4f * u, fillP)
            }
            Icon.PLUS -> { c.drawLine(x(12f), y(5f), x(12f), y(19f), lineP); c.drawLine(x(5f), y(12f), x(19f), y(12f), lineP) }
            Icon.CROSS -> { c.drawLine(x(6f), y(6f), x(18f), y(18f), lineP); c.drawLine(x(18f), y(6f), x(6f), y(18f), lineP) }
            Icon.UPDOWN -> {
                p.moveTo(x(12f), y(2.5f)); p.lineTo(x(18.5f), y(9.5f)); p.lineTo(x(5.5f), y(9.5f)); p.close()
                p.moveTo(x(12f), y(21.5f)); p.lineTo(x(18.5f), y(14.5f)); p.lineTo(x(5.5f), y(14.5f)); p.close()
                c.drawPath(p, fillP)
            }
            Icon.CHECK -> { p.moveTo(x(5f), y(12.5f)); p.lineTo(x(10f), y(17.5f)); p.lineTo(x(19f), y(7f)); c.drawPath(p, lineP) }
        }
    }

    override fun setAlpha(alpha: Int) { fillP.alpha = alpha; lineP.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fillP.colorFilter = cf; lineP.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
