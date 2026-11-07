package com.wyred4fun.pokertimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live sports for the billboard: schedules and scores for the Detroit and Michigan teams, from ESPN's public
 * schedule feed. The TV fetches them in the background (every 10 minutes, every minute while a game is on),
 * keeps the last answer so the slides still work when the internet drops, and draws the slides itself.
 * Slide names start with "@" (for example "@sports_pro") so they sit in the same deck as the picture files.
 */
object Sports {
    class Team(val key: String, val path: String, val id: String, val name: String, val short: String, val color: Int)

    val TEAMS = listOf(
        Team("lions", "football/nfl", "8", "LIONS", "Lions", 0xFF2E9BDB.toInt()),
        Team("wings", "hockey/nhl", "5", "RED WINGS", "Wings", 0xFFE0243B.toInt()),
        Team("pistons", "basketball/nba", "8", "PISTONS", "Pistons", 0xFF3D6BF0.toInt()),
        Team("msufb", "football/college-football", "127", "MSU FOOTBALL", "MSU", 0xFF2BB673.toInt()),
        Team("umfb", "football/college-football", "130", "MICHIGAN FOOTBALL", "Michigan", 0xFFFFCB05.toInt()),
        Team("msubb", "basketball/mens-college-basketball", "127", "MSU BASKETBALL", "MSU", 0xFF2BB673.toInt()),
        Team("umbb", "basketball/mens-college-basketball", "130", "MICHIGAN BASKETBALL", "Michigan", 0xFFFFCB05.toInt())
    )

    /** slide name -> title, team keys */
    val GROUPS = linkedMapOf(
        "@sports_pro" to Pair("DETROIT PRO SPORTS", listOf("lions", "wings", "pistons")),
        "@sports_football" to Pair("MICHIGAN COLLEGE FOOTBALL", listOf("msufb", "umfb")),
        "@sports_hoops" to Pair("MICHIGAN COLLEGE BASKETBALL", listOf("msubb", "umbb"))
    )

    val TITLES = mapOf(
        "@sports_pro" to "Scores: Lions, Wings, Pistons",
        "@sports_football" to "Scores: MSU & Michigan football",
        "@sports_hoops" to "Scores: MSU & Michigan basketball"
    )

    /** One game. [us] and [them] are -1 until there is a score. [state] is ESPN's: pre, in or post. */
    class Game(val at: Long, val home: Boolean, val opp: String, val us: Int, val them: Int, val state: String, val detail: String)
    class Snap(val last: Game?, val live: Game?, val next: List<Game>, val count: Int = 0)

    private const val PREFS = "poker_timer"
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

    // ---------- reading ESPN's answer ----------

    private fun scoreOf(c: JSONObject): Int {
        val s = c.opt("score") ?: return -1
        return when (s) {
            is JSONObject -> s.optString("displayValue", "").toIntOrNull() ?: s.optDouble("value", -1.0).toInt()
            is Number -> s.toInt()
            else -> s.toString().toIntOrNull() ?: -1
        }
    }

    /** ESPN's date ("2026-09-13T17:00Z", sometimes with seconds) in milliseconds, or -1 if it can't be read. */
    private fun parseDate(s: String): Long {
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time } catch (e: Exception) { }
        }
        return -1L
    }

    private fun str(o: JSONObject?, key: String): String? = if (o == null || o.isNull(key)) null else o.optString(key, "").ifEmpty { null }

    /** The team's season from ESPN's schedule answer: the last finished game, a game on right now, and the next three. */
    fun parse(json: String, teamId: String): Snap {
        val events = JSONObject(json).optJSONArray("events") ?: return Snap(null, null, emptyList())
        val games = ArrayList<Game>()
        for (i in 0 until events.length()) {
            try {
                val e = events.getJSONObject(i)
                val comp = e.getJSONArray("competitions").getJSONObject(0)
                val cs = comp.getJSONArray("competitors")
                var us: JSONObject? = null; var them: JSONObject? = null
                for (k in 0 until cs.length()) {
                    val c = cs.getJSONObject(k)
                    if (c.optJSONObject("team")?.optString("id") == teamId) us = c else them = c
                }
                if (us == null || them == null) continue
                val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
                val t = them.optJSONObject("team")
                val opp = str(t, "shortDisplayName") ?: str(t, "displayName") ?: "?"
                val at = parseDate(e.optString("date"))
                val state = str(st, "state") ?: "pre"
                val su = scoreOf(us); val sv = scoreOf(them)
                if (at < 0) continue
                if (state == "post" && (su < 0 || sv < 0)) continue      // postponed or canceled: not a result
                games.add(Game(at, us.optString("homeAway") == "home", opp, su, sv, state, str(st, "shortDetail") ?: ""))
            } catch (x: Exception) { /* skip an odd entry */ }
        }
        games.sortBy { it.at }
        return Snap(games.lastOrNull { it.state == "post" }, games.firstOrNull { it.state == "in" },
            games.filter { it.state == "pre" }.take(3), games.size)
    }

    // ---------- keeping the last answer ----------

    private fun gameJson(g: Game) = JSONObject().put("a", g.at).put("h", g.home).put("o", g.opp).put("u", g.us)
        .put("v", g.them).put("s", g.state).put("d", g.detail)

    private fun gameFrom(o: JSONObject) = Game(o.getLong("a"), o.getBoolean("h"), o.getString("o"), o.getInt("u"),
        o.getInt("v"), o.getString("s"), o.optString("d", ""))

    fun toJson(s: Snap): String = JSONObject().apply {
        s.last?.let { put("l", gameJson(it)) }
        s.live?.let { put("v", gameJson(it)) }
        put("n", JSONArray().apply { s.next.forEach { put(gameJson(it)) } })
    }.toString()

    fun fromJson(j: String): Snap? = try {
        val o = JSONObject(j)
        val n = o.optJSONArray("n")
        Snap(o.optJSONObject("l")?.let { gameFrom(it) }, o.optJSONObject("v")?.let { gameFrom(it) },
            (0 until (n?.length() ?: 0)).map { gameFrom(n!!.getJSONObject(it)) })
    } catch (e: Exception) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context, key: String): Snap? = prefs(ctx).getString("sports_$key", null)?.let { fromJson(it) }

    fun save(ctx: Context, key: String, s: Snap) { prefs(ctx).edit().putString("sports_$key", toJson(s)).apply() }

    // ---------- fetching ----------

    @Volatile private var busy = false

    /** Is a game on right now (as of the last answer)? Then ask more often. */
    private fun anyLive(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        return TEAMS.any { load(ctx, it.key)?.live?.let { g -> liveFresh(g, now) } == true }
    }

    /** A game still marked "live" more than 5 hours after it started is old news (the TV was offline). */
    private fun liveFresh(g: Game, now: Long) = now - g.at < 5 * 3600_000L

    /**
     * Ask ESPN for fresh schedules if the last answer is old (10 minutes, or 1 minute while a game is on).
     * Cheap to call as often as you like; does the work on a background thread. A failed try waits for the next interval.
     */
    fun refresh(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        if (busy) return
        val app = ctx.applicationContext
        val p = prefs(app)
        val every = if (anyLive(app)) 60_000L else 600_000L
        if (nowMs - p.getLong("sports_at", 0L) < every) return
        p.edit().putLong("sports_at", nowMs).apply()
        busy = true
        Thread {
            try {
                for (t in TEAMS) {
                    try {
                        val c = URL(BASE + t.path + "/teams/" + t.id + "/schedule").openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 15_000
                        c.setRequestProperty("User-Agent", "PokerTimer")
                        try {
                            if (c.responseCode == 200) {
                                val snap = parse(c.inputStream.bufferedReader().use { it.readText() }, t.id)
                                if (snap.count > 0) save(app, t.key, snap)      // an empty or odd answer never wipes good data
                            }
                        } finally { c.disconnect() }
                    } catch (e: Exception) { /* offline or a bad answer: keep what we have */ }
                }
            } finally { busy = false }
        }.start()
    }

    // ---------- which teams get a card ----------

    private const val DAY = 24L * 3600_000L

    /** Teams in a slide that have something worth showing: a game on now, one coming within a month, or one finished in the last 10 days. */
    fun cards(ctx: Context, name: String, now: Long): List<Pair<Team, Snap>> {
        val keys = GROUPS[name]?.second ?: return emptyList()
        return keys.mapNotNull { k ->
            val t = TEAMS.first { it.key == k }
            val raw = load(ctx, k) ?: return@mapNotNull null
            // drop what has gone stale while the TV was offline
            val s = Snap(raw.last, raw.live?.takeIf { liveFresh(it, now) }, raw.next.filter { it.at > now - 3 * 3600_000L }, raw.count)
            val ok = s.live != null || s.next.firstOrNull()?.let { it.at - now < 30 * DAY } == true ||
                s.last?.let { now - it.at < 10 * DAY } == true
            if (ok) t to s else null
        }
    }

    /** The sports slides that have anything to show right now. */
    fun slideNames(ctx: Context, now: Long): List<String> = GROUPS.keys.filter { cards(ctx, it, now).isNotEmpty() }

    // ---------- drawing ----------

    private var font: Typeface? = null
    private fun bebas(ctx: Context): Typeface = font ?: (try {
        Typeface.createFromAsset(ctx.assets, "fonts/BebasNeue-Regular.ttf")
    } catch (e: Exception) { Typeface.create("sans-serif-condensed", Typeface.BOLD) }).also { font = it }

    private val DETROIT = TimeZone.getTimeZone("America/Detroit")
    private fun day(ms: Long) = SimpleDateFormat("EEE MMM d", Locale.US).apply { timeZone = DETROIT }.format(Date(ms))
    private fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = DETROIT }.format(Date(ms))

    /** "W 31-30 vs Saints" */
    fun lastText(g: Game): String {
        val r = if (g.us > g.them) "W" else if (g.us < g.them) "L" else "T"
        return "$r ${g.us}–${g.them}  ${if (g.home) "vs" else "at"} ${g.opp}"
    }

    fun nextText(g: Game): String = "${day(g.at)} · ${time(g.at)}   ${if (g.home) "vs" else "at"} ${g.opp}"

    fun liveText(t: Team, g: Game): String {
        fun sc(v: Int) = if (v < 0) "0" else v.toString()
        return "${g.detail}   ${t.short} ${sc(g.us)}  \u2013  ${g.opp} ${sc(g.them)}"
    }

    /**
     * Draws a sports slide on a 1920 x 1080 canvas, scaled to [w] x [h]. Null when nothing is known yet.
     * Call from a background thread.
     */
    fun render(ctx: Context, name: String, w: Int, h: Int, now: Long = System.currentTimeMillis()): Bitmap? {
        val cards = cards(ctx, name, now)
        if (cards.isEmpty()) return null
        val title = GROUPS[name]!!.first
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(w / 1920f, h / 1080f)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1080f, 0xFF0C1B33.toInt(), 0xFF04070D.toInt(), Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, 1920f, 1080f, bg)
        val bb = bebas(ctx)
        val sans = Typeface.create("sans-serif", Typeface.BOLD)
        fun paint(size: Float, color: Int, tf: Typeface, spacing: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = tf; letterSpacing = spacing }
        // heading
        cv.drawText("LIVE SCORES & SCHEDULES", 110f, 96f, paint(34f, 0xFFB8C4D6.toInt(), sans, 0.3f))
        cv.drawText(title, 110f, 235f, paint(150f, 0xFFFFFFFF.toInt(), bb, 0.03f))
        // team cards
        val top = 290f; val bottom = 985f; val gap = 22f
        val n = cards.size
        val ch = minOf(360f, (bottom - top - gap * (n - 1)) / n)
        val total = ch * n + gap * (n - 1)
        var y = top + ((bottom - top) - total) / 2f
        val lines = if (n >= 3) 2 else 3
        for ((t, s) in cards) {
            cv.drawRoundRect(RectF(110f, y, 1810f, y + ch), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
            cv.drawRoundRect(RectF(110f, y, 134f, y + ch), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color })
            // team name, shrunk to fit
            val np = paint(104f, 0xFFFFFFFF.toInt(), bb, 0.03f)
            while (np.measureText(t.name) > 540f && np.textSize > 40f) np.textSize -= 4f
            cv.drawText(t.name, 170f, y + ch / 2f + np.textSize * 0.34f, np)
            // right side lines
            val rows = ArrayList<Triple<String, String, Int>>()   // label, text, colour
            if (s.live != null) rows.add(Triple("LIVE", liveText(t, s.live), 0xFFFFD24A.toInt()))
            else s.last?.let { g ->
                rows.add(Triple("LAST", lastText(g) + "  ·  " + day(g.at), if (g.us > g.them) 0xFF6FE08F.toInt() else if (g.us < g.them) 0xFFFF8A7A.toInt() else 0xFFFFFFFF.toInt()))
            }
            val left = lines - rows.size
            s.next.take(left).forEachIndexed { i, g -> rows.add(Triple(if (i == 0) "NEXT" else "", nextText(g), 0xFFFFFFFF.toInt())) }
            if (rows.isEmpty()) rows.add(Triple("", "No games scheduled", 0xFFB8C4D6.toInt()))
            val step = if (n >= 3) 66f else 78f
            var ry = y + ch / 2f - (rows.size - 1) * step / 2f + 15f
            for ((label, text, col) in rows) {
                cv.drawText(label, 760f, ry, paint(30f, 0xFFB8C4D6.toInt(), sans, 0.2f))
                val tp = paint(if (n >= 3) 44f else 50f, col, sans)
                while (tp.measureText(text) > 940f && tp.textSize > 24f) tp.textSize -= 2f
                cv.drawText(text, 900f, ry, tp)
                ry += step
            }
            y += ch + gap
        }
        cv.drawText("Updated ${time(now)} · data from ESPN", 110f, 1042f, paint(28f, 0xFF8FA0B8.toInt(), sans))
        return bmp
    }
}
