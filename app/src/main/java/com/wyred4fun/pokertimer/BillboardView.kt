package com.wyred4fun.pokertimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Calendar

/**
 * The club billboard: full-screen slides (the JPEGs in assets/slides) in random order, each up for 60 to 120 seconds,
 * fading from one to the next. Shown whenever poker isn't on (see MainActivity.renderOverlays).
 */
class BillboardView(ctx: Context) : FrameLayout(ctx) {
    private val a = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val b = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private var front = a
    private val main = Handler(Looper.getMainLooper())
    private var deck = ArrayList<String>()
    private var last: String? = null
    private var running = false
    private var serial = 0

    init {
        setBackgroundColor(0xFF000000.toInt())
        isClickable = true
        addView(a, LayoutParams(-1, -1))
        addView(b, LayoutParams(-1, -1))
    }

    fun start() {
        if (running) return
        running = true
        next()
    }

    fun stop() {
        running = false
        serial++
        main.removeCallbacksAndMessages(null)
    }

    private fun next() {
        if (!running) return
        val name = pick() ?: return
        val my = ++serial
        Thread {
            val bmp = try {
                context.assets.open("slides/$name").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                }
            } catch (e: Exception) { null }
            main.post {
                if (my != serial || !running) return@post
                if (bmp != null) show(bmp)
                main.postDelayed({ next() }, Billboard.holdMs())
            }
        }.start()
    }

    /** Cross-fade the new slide in over the old one. */
    private fun show(bmp: Bitmap) {
        val back = if (front === a) b else a
        back.setImageBitmap(bmp)
        back.animate().cancel(); front.animate().cancel()
        back.bringToFront()
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(1200).start()
        val old = front
        front = back
        old.postDelayed({ if (front !== old) old.setImageDrawable(null) }, 1400)
    }

    /** Next slide: a shuffled deck of the current slides, never the same one twice in a row. */
    private fun pick(): String? {
        if (deck.isEmpty()) {
            val all = Billboard.current(context, System.currentTimeMillis())
            if (all.isEmpty()) return null
            deck = ArrayList(all.shuffled())
            if (deck.size > 1 && deck.first() == last) deck.add(deck.removeAt(0))
        }
        val n = deck.removeAt(0)
        last = n
        return n
    }
}

object Billboard {
    /** Slides that belong to one event come off after it (local time). Name -> year, month (1-12), day, hour. */
    private val UNTIL = mapOf(
        "06_fall.jpg" to intArrayOf(2026, 11, 1, 3),      // Fall Y'all Ball, Saturday October 31
        "11_drinks.jpg" to intArrayOf(2026, 11, 1, 3),    // its specialty drinks
        "09_wrap.jpg" to intArrayOf(2026, 11, 7, 0),      // The Grilled Wrap, Friday November 6
        "10_menu.jpg" to intArrayOf(2026, 11, 7, 0),
        "19_veterans.jpg" to intArrayOf(2026, 11, 12, 0), // Veterans Day, November 11
        "20_thanks_giving.jpg" to intArrayOf(2026, 11, 27, 0) // Thanksgiving, November 26
    )

    /** Seasonal slides that only start showing a few days before their day. */
    private val FROM = mapOf(
        "19_veterans.jpg" to intArrayOf(2026, 11, 4, 0),
        "20_thanks_giving.jpg" to intArrayOf(2026, 11, 16, 0)
    )

    fun startsAt(name: String): Long? = FROM[name]?.let { (y, m, d, h) ->
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    }

    fun expiresAt(name: String): Long? = UNTIL[name]?.let { (y, m, d, h) ->
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    }

    /** The slides to show right now. */
    fun current(ctx: Context, now: Long): List<String> =
        (ctx.assets.list("slides") ?: emptyArray()).filter { it.endsWith(".jpg") }.sorted()
            .filter { n -> (expiresAt(n)?.let { now < it } ?: true) && (startsAt(n)?.let { now >= it } ?: true) }

    /** Each slide stays up a random 60 to 120 seconds. */
    fun holdMs(): Long = 60_000L + (Math.random() * 60_000L).toLong()

    /** Only the Catalpa Club Poker Timer version has slides. */
    fun enabled(ctx: Context) = BuildConfig.CLUB &&
        ctx.getSharedPreferences("poker_timer", Context.MODE_PRIVATE).getBoolean("billboard", true)

    /** The billboard shows when the poker night isn't under way. */
    fun wanted(wall: Long, startedAt: Long, running: Boolean, finished: Boolean): Boolean {
        if (running) return false
        if (startedAt == 0L || finished) return true
        return wall - startedAt > 10 * 3600_000L   // a night started long ago and left paused
    }
}
