package com.wyred4fun.pokertimer

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The home run / goal special. While a Tigers or Red Wings game is on, the TV watches ESPN's play-by-play. A Tigers
 * home run or a Red Wings goal brings up a full-screen spinning red siren (with a siren sound) for 10 seconds, then a
 * "$1 OFF" banner with a 5-minute countdown stays along the bottom of the billboard. A new one restarts the clock.
 * It only ever shows on the billboard, never over the poker timer or the Friday countdown.
 */
object Specials {
    const val MINUTES = 5
    const val DELAY_MS = 30_000L          // the feed runs ahead of the TV picture, so wait a little
    const val TAKEOVER_MS = 10_000L
    /** The siren takes the first 10 seconds; the $1 off banner then counts down 5 minutes. */
    const val TOTAL_MS = TAKEOVER_MS + MINUTES * 60_000L
    private const val PREFS = "poker_timer"

    /** What happened: [kind] is "hr" (Tigers home run) or "goal" (Red Wings goal); [at] is when the feed says, or -1. */
    class Event(val kind: String, val at: Long)

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("specials", true)

    // ---------- the special that is on ----------

    class Active(val kind: String, val start: Long, val until: Long)

    fun active(ctx: Context, now: Long): Active? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = p.getLong("spUntil", 0L)
        if (until <= now) return null
        return Active(p.getString("spKind", "hr") ?: "hr", p.getLong("spStart", 0L), until)
    }

    fun activate(ctx: Context, kind: String, start: Long, lengthMs: Long = TOTAL_MS) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("spKind", kind).putLong("spStart", start).putLong("spUntil", start + lengthMs).apply()
    }

    // ---------- reading the play-by-play ----------

    /**
     * New events in ESPN's game summary, or null if the answer could not be read (so nothing is marked as seen).
     * [sport] is "hockey" or "baseball"; [ourHome] says whether our team is the home side.
     * [seen] holds what was already looked at; with [baseline] true everything is just marked as seen (the app joined a game in progress).
     */
    fun scan(json: String, sport: String, ourHome: Boolean, seen: MutableSet<String>, baseline: Boolean): List<Event>? {
        val plays = try { JSONObject(json).optJSONArray("plays") } catch (e: Exception) { null } ?: return null
        val out = ArrayList<Event>()
        var pa = 0; var ph = 0
        for (i in 0 until plays.length()) {
            val p = plays.optJSONObject(i) ?: continue
            val type = p.optJSONObject("type")
            if (sport == "hockey") {
                if (!p.optBoolean("scoringPlay", false)) continue
                val away = p.optInt("awayScore", pa); val home = p.optInt("homeScore", ph)
                val ours = if (ourHome) home > ph else away > pa
                pa = away; ph = home
                val id = "g" + p.optString("id", "$i")
                if (!seen.add(id) || baseline) continue
                val period = p.optJSONObject("period")
                val shootout = (type?.optString("text", "") ?: "").contains("shootout", ignoreCase = true) ||
                    (period?.optString("type", "") ?: "").contains("shoot", ignoreCase = true) || (period?.optInt("number", 0) ?: 0) >= 5
                if (ours && !shootout) out.add(Event("goal", Sports.parseDate(p.optString("wallclock", ""))))
            } else {
                if (type?.optString("type", "") != "home-run") continue
                val half = p.optJSONObject("period")?.optString("type", "")
                if (half != "Top" && half != "Bottom") continue                     // odd data: leave it unseen, look again next time
                val id = "h" + p.optString("atBatId", p.optString("id", "$i"))
                if (!seen.add(id) || baseline) continue
                val ourBat = if (ourHome) half == "Bottom" else half == "Top"
                if (ourBat) out.add(Event("hr", Sports.parseDate(p.optString("wallclock", ""))))
            }
        }
        return out
    }

    // ---------- watching live games ----------

    private val lock = Any()
    private val pending = ArrayList<Pair<Long, Event>>()      // when to show it, what
    private val seenByGame = HashMap<String, HashSet<String>>()
    @Volatile private var polling = false
    private var lastPoll = 0L

    /**
     * Called every few seconds by the billboard: shows anything that is due and, every 15 seconds while a Red Wings or
     * Tigers game is on, looks at the play-by-play. [onFire] gets the kind and when it started (now).
     */
    fun tick(ctx: Context, now: Long, onFire: (String, Long) -> Unit) {
        if (!enabled(ctx)) return
        val due = synchronized(lock) { pending.filter { it.first <= now }.also { pending.removeAll(it) } }
        // one that has waited far too long (the billboard was away) is dropped rather than shown late
        due.lastOrNull { now - it.first <= 30_000L }?.let { (_, e) ->
            activate(ctx, e.kind, now)
            onFire(e.kind, now)
        }
        if (!polling && now - lastPoll >= 15_000L) {
            lastPoll = now
            poll(ctx.applicationContext, now)
        }
    }

    private fun poll(app: Context, now: Long) {
        val live = Sports.SPECIAL_KEYS.mapNotNull { k ->
            val g = Sports.load(app, k)?.live ?: return@mapNotNull null
            if (g.id.isEmpty() || now - g.at > 5 * 3600_000L) null else Sports.TEAMS.first { it.key == k } to g
        }
        synchronized(lock) { seenByGame.keys.retainAll(live.map { it.first.key + it.second.id }.toSet()) }   // forget games that are over
        if (live.isEmpty()) return
        polling = true
        Thread {
            try {
                for ((t, g) in live) {
                    val json = fetch(Sports.summaryUrl(t, g.id)) ?: continue
                    synchronized(lock) {
                        val game = t.key + g.id
                        val first = game !in seenByGame
                        val seen = seenByGame.getOrPut(game) { HashSet() }
                        val events = scan(json, if (t.path.startsWith("hockey")) "hockey" else "baseball", g.home, seen, first)
                        if (events == null) { if (first) seenByGame.remove(game); return@synchronized }   // unreadable: try again next time
                        for (e in events) {
                            if (e.at > 0 && now - e.at > 5 * 60_000L) continue          // too old to matter
                            pending.add(maxOf(now, (if (e.at > 0) e.at else now) + DELAY_MS) to e)
                        }
                    }
                }
            } finally { polling = false }
        }.start()
    }

    private fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "PokerTimer")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (e: Exception) { null }
}

/**
 * The siren and the banner, drawn by hand on a 1920 x 1080 canvas scaled to the screen.
 * Sits on top of the billboard; shows nothing when no special is on.
 */
class AlertView(ctx: Context) : View(ctx) {
    private var kind = ""
    private var start = 0L
    private var until = 0L
    private val bebas: Typeface = Sports.bebas(ctx)
    private val sans: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    init { isClickable = false; isFocusable = false }

    /** Start (or restart) a special that began at [startMs] and runs [lengthMs]. */
    fun show(kind: String, startMs: Long, lengthMs: Long = Specials.TOTAL_MS) {
        this.kind = kind; start = startMs; until = startMs + lengthMs
        removeCallbacks(redraw)
        invalidate()
    }

    /** Pick up a special that is still on (the billboard just came back). */
    fun restore(a: Specials.Active?) {
        if (a != null) show(a.kind, a.start, a.until - a.start)
    }

    private val redraw = Runnable { invalidate() }

    override fun onDetachedFromWindow() { removeCallbacks(redraw); super.onDetachedFromWindow() }

    private fun paint(size: Float, color: Int, tf: Typeface, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; textAlign = align; letterSpacing = spacing }

    // everything that is drawn every frame is made once
    private val bgPaint = Paint()
    private val barPaint = Paint().apply { color = 0xF2A8101C.toInt() }
    private val rulePaint = Paint().apply { color = 0xFFFFD24A.toInt() }
    private val pHead = paint(250f, Color.WHITE, bebas, Paint.Align.CENTER, 0.03f)
    private val pTeam = paint(100f, 0xFFFF9C8F.toInt(), bebas, Paint.Align.CENTER, 0.1f)
    private val pOff = paint(330f, 0xFFFFD24A.toInt(), bebas, Paint.Align.CENTER, 0.02f)
    private val pSub = paint(60f, Color.WHITE, sans, Paint.Align.CENTER)
    private val pBar = paint(124f, Color.WHITE, bebas, Paint.Align.LEFT, 0.03f)
    private val pTime = paint(140f, 0xFFFFD24A.toInt(), bebas, Paint.Align.RIGHT, 0.02f)
    private val pLeft = paint(46f, Color.WHITE, sans, Paint.Align.RIGHT, 0.1f)
    private val big = Beacon(190f)
    private val small = Beacon(52f)
    private var shownLeft = -1
    private var leftText = ""

    /** A beacon drawn around (0, 0): glow, base, red dome and two sweeping beams. Paints and shaders are built once. */
    private class Beacon(val r: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(0f, 0f, r * 1.9f, intArrayOf(Color.argb(110, 255, 40, 30), Color.argb(0, 255, 40, 30)), null, Shader.TileMode.CLAMP) }
        val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(0f, 0f, r * 3.2f, intArrayOf(Color.argb(190, 255, 90, 70), Color.argb(0, 255, 40, 30)), null, Shader.TileMode.CLAMP) }
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3F4A.toInt() }
        val dome = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(-r * 0.2f, -r * 0.25f, r, intArrayOf(0xFFFF7A6B.toInt(), 0xFFE0201A.toInt(), 0xFF7A0A0A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
        val box = RectF(-r * 3.2f, -r * 3.2f, r * 3.2f, r * 3.2f)
        val plate = RectF(-r * 0.95f, r * 0.45f, r * 0.95f, r * 0.85f)

        fun draw(cv: Canvas, cx: Float, cy: Float, angle: Float, pulse: Float) {
            cv.save(); cv.translate(cx, cy)
            glow.alpha = (255 * pulse).toInt().coerceIn(0, 255)
            cv.drawCircle(0f, 0f, r * 1.9f, glow)
            cv.save(); cv.rotate(angle)
            cv.drawArc(box, -20f, 40f, true, beam)
            cv.drawArc(box, 160f, 40f, true, beam)
            cv.restore()
            cv.drawRoundRect(plate, r * 0.1f, r * 0.1f, base)
            cv.drawCircle(0f, 0f, r * 0.75f, dome)
            cv.restore()
        }
    }

    private fun headline() = when (kind) { "goal" -> "GOAL!"; "hr" -> "HOME RUN!"; else -> "TEST" }
    private fun team() = when (kind) { "goal" -> "RED WINGS"; "hr" -> "TIGERS"; else -> "SIREN TEST" }

    override fun onDraw(cv: Canvas) {
        val now = System.currentTimeMillis()
        if (now >= until || until == 0L) return
        val el = (now - start).coerceAtLeast(0L)
        cv.save()
        cv.scale(width / 1920f, height / 1080f)
        val angle = (el % 900L) / 900f * 360f
        val pulse = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(el / 300.0)).toFloat()
        val takeover = el < Specials.TAKEOVER_MS
        if (takeover) {
            // full-screen: dark red, flashing, a siren each side
            bgPaint.color = if ((el / 450L) % 2L == 0L) 0xFF3A0508.toInt() else 0xFF1A0306.toInt()
            cv.drawRect(0f, 0f, 1920f, 1080f, bgPaint)
            big.draw(cv, 380f, 470f, angle, pulse)
            big.draw(cv, 1540f, 470f, 360f - angle, pulse)
            cv.drawText(headline(), 960f, 400f, pHead)
            cv.drawText(team(), 960f, 500f, pTeam)
            cv.drawText("\$1 OFF", 960f, 790f, pOff)
            cv.drawText("for the next ${Specials.MINUTES} minutes", 960f, 920f, pSub)
        } else {
            // the banner along the bottom, the slides stay up above it; counts the 5 minutes after the siren
            val left = ((until - now + 999L) / 1000L).toInt()
            if (left != shownLeft) { shownLeft = left; leftText = String.format("%d:%02d", left / 60, left % 60) }
            cv.drawRect(0f, 890f, 1920f, 1080f, barPaint)
            cv.drawRect(0f, 886f, 1920f, 892f, rulePaint)
            small.draw(cv, 150f, 985f, angle, pulse)
            cv.drawText("${headline()}  \$1 OFF", 290f, 1032f, pBar)
            cv.drawText(leftText, 1700f, 1032f, pTime)
            cv.drawText("LEFT", 1880f, 1032f, pLeft)
        }
        cv.restore()
        // one redraw chain: the whole screen during the siren, only the banner after it
        if (takeover) postInvalidateDelayed(16L)
        else postInvalidateDelayed(100L, 0, (880f * height / 1080f).toInt(), width, height)
    }
}

/** Setup > "Test special": the siren and banner full screen for about 20 seconds (any key closes it). */
class AlertTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var alert: AlertView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        alert = AlertView(this)
        root.addView(alert, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        alert.show("hr", System.currentTimeMillis(), 20_000L)
        Announcer.init(this)
        Announcer.siren()
        main.postDelayed({ finish() }, 21_000L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { finish(); return true }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
