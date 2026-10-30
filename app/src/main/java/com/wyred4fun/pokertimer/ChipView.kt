package com.wyred4fun.pokertimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A poker chip with a number in the middle (for the blinds). */
class ChipView(ctx: Context, private val ui: Ui) : View(ctx) {
    var value: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = ui.bebas }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rad = minOf(width, height) / 2f - 2f
        body.color = C.PANEL_HI; spot.color = C.GOLD; ring.color = C.GOLD; face.color = C.FELT; text.color = C.CREAM
        c.drawCircle(cx, cy, rad, body)
        // the edge spots of a casino chip
        for (k in 0 until 8) {
            c.save()
            c.rotate(k * 45f, cx, cy)
            r.set(cx - rad * 0.13f, cy - rad, cx + rad * 0.13f, cy - rad * 0.78f)
            c.drawRoundRect(r, rad * 0.03f, rad * 0.03f, spot)
            c.restore()
        }
        // dashed inner ring and the center face
        ring.strokeWidth = rad * 0.035f
        ring.pathEffect = DashPathEffect(floatArrayOf(rad * 0.12f, rad * 0.07f), 0f)
        c.drawCircle(cx, cy, rad * 0.70f, ring)
        c.drawCircle(cx, cy, rad * 0.62f, face)
        // the number, shrunk to fit big values
        var size = rad * 0.78f
        text.textSize = size
        val maxW = rad * 1.05f
        val w = text.measureText(value)
        if (w > maxW) { size *= maxW / w; text.textSize = size }
        val fm = text.fontMetrics
        c.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }
}
