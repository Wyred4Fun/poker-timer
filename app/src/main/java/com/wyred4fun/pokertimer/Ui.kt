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

/** The colors on screen; a Theme fills them in before a screen is built. */
object C {
    var FELT = 0; var PANEL = 0; var PANEL_HI = 0; var RULE = 0; var EDGE = 0
    var CREAM = 0; var MUTED = 0
    /** accent: level label, Start/Done buttons, 1-minute banner */
    var GOLD = 0; var GOLD_HI = 0; var GOLD_INK = 0
    var ORANGE = 0; var ORANGE_INK = 0
    /** progress bar and the "Blinds are now" banner */
    var GREEN = 0; var GREEN_INK = 0
    var STOP_BG = 0
    init { Theme.apply(Theme.GREEN_BAY) }
}

/** Color themes picked in Setup > Theme. The first is the default. */
object Theme {
    const val GREEN_BAY = "greenbay"
    val ALL = listOf(
        GREEN_BAY to "Green & gold",
        "detroit" to "Honolulu blue & silver",
        "casino" to "Casino red & gold",
        "midnight" to "Midnight & gold",
        "teal" to "Teal & sand",
        "slate" to "Slate & ice blue"
    )

    fun name(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: ALL[0].second

    fun apply(id: String) {
        fun c(s: String) = Color.parseColor(s)
        // felt, panel, panel-hi, rule, edge, text, muted, accent, accent-hi, accent-ink, bar/banner, banner-ink
        val p = when (id) {
            "detroit" -> listOf("#071E33", "#0C2C4A", "#123B62", "#1D4C78", "#2E6496", "#F4F7FA", "#AEB9C4", "#B0B7BC", "#D3D8DC", "#06182A", "#0076B6", "#FFFFFF")
            "casino" -> listOf("#3A0B10", "#4C1218", "#631C23", "#7A2A31", "#934049", "#F7EFE6", "#D9B9B2", "#E3B341", "#F3CB66", "#1F1404", "#E3B341", "#1F1404")
            "midnight" -> listOf("#0B0B0E", "#17171C", "#24242B", "#33333C", "#4A4A55", "#F2EFE8", "#B4B0A6", "#D4AF37", "#E8C75A", "#151003", "#D4AF37", "#151003")
            "teal" -> listOf("#0A2729", "#103538", "#17454A", "#215659", "#2F6F72", "#F6F1E7", "#B4C9C6", "#E6C79C", "#F2DCBA", "#1E160A", "#2FB7A8", "#04201E")
            "slate" -> listOf("#15191E", "#1E242B", "#29313A", "#36404B", "#4B5765", "#F1F4F7", "#AAB5C0", "#8FD3FF", "#B5E2FF", "#06202F", "#8FD3FF", "#06202F")
            else -> listOf("#0B2E24", "#12392D", "#1D4D3E", "#2C5E4C", "#3A6E5B", "#F4EFE3", "#A9C4B7", "#E9B949", "#F6CF6A", "#1C1503", "#5FBF94", "#06251A")
        }.map { c(it) }
        C.FELT = p[0]; C.PANEL = p[1]; C.PANEL_HI = p[2]; C.RULE = p[3]; C.EDGE = p[4]
        C.CREAM = p[5]; C.MUTED = p[6]; C.GOLD = p[7]; C.GOLD_HI = p[8]; C.GOLD_INK = p[9]
        C.GREEN = p[10]; C.GREEN_INK = p[11]
        C.ORANGE = c("#F08A3C"); C.ORANGE_INK = c("#1C0B02"); C.STOP_BG = c("#5A2A12")
    }
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
            background = if (primary) pillBg(C.GOLD, C.GOLD, C.GOLD_HI, C.CREAM)
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

enum class Icon { PLAY, PAUSE, BACK, SKIP, RESET, STOP, SLIDERS, PLUS, CROSS, CHECK, DOWNLOAD, MINUS }

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
            Icon.MINUS -> c.drawLine(x(5f), y(12f), x(19f), y(12f), lineP)
            Icon.PLUS -> { c.drawLine(x(12f), y(5f), x(12f), y(19f), lineP); c.drawLine(x(5f), y(12f), x(19f), y(12f), lineP) }
            Icon.CROSS -> { c.drawLine(x(6f), y(6f), x(18f), y(18f), lineP); c.drawLine(x(18f), y(6f), x(6f), y(18f), lineP) }
            Icon.DOWNLOAD -> {
                c.drawLine(x(12f), y(3.5f), x(12f), y(15f), lineP)
                p.moveTo(x(7f), y(10.5f)); p.lineTo(x(12f), y(15.5f)); p.lineTo(x(17f), y(10.5f)); c.drawPath(p, lineP)
                c.drawLine(x(4.5f), y(20f), x(19.5f), y(20f), lineP)
            }
            Icon.CHECK -> { p.moveTo(x(5f), y(12.5f)); p.lineTo(x(10f), y(17.5f)); p.lineTo(x(19f), y(7f)); c.drawPath(p, lineP) }
        }
    }

    override fun setAlpha(alpha: Int) { fillP.alpha = alpha; lineP.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fillP.colorFilter = cf; lineP.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
