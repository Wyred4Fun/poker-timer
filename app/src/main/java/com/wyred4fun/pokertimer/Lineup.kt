package com.wyred4fun.pokertimer

import android.content.Context

/** One step of the night: a blind level, or a break (no blinds). */
data class Level(val sb: Int, val bb: Int, val minutes: Int, val isBreak: Boolean = false) {
    val blindsText: String get() = "$sb – $bb"
    val blindsSpoken: String get() = "$sb and $bb"
}

object Lineup {
    /** Jeff's lineup: 8 PM start, three 15-minute breaks, ends about 12:35 AM. */
    val DEFAULT: List<Level> = listOf(
        Level(1, 2, 20),
        Level(2, 4, 20),
        Level(3, 6, 20),
        Level(0, 0, 15, isBreak = true),
        Level(4, 8, 20),
        Level(5, 10, 20),
        Level(6, 12, 20),
        Level(0, 0, 15, isBreak = true),
        Level(7, 14, 20),
        Level(8, 16, 20),
        Level(9, 18, 15)
    )

    private const val PREFS = "poker_timer"
    private const val KEY = "lineup"

    /** Stored as "1,2,20;B,15;..." */
    fun encode(levels: List<Level>): String =
        levels.joinToString(";") { if (it.isBreak) "B,${it.minutes}" else "${it.sb},${it.bb},${it.minutes}" }

    fun decode(text: String?): List<Level>? {
        if (text.isNullOrBlank()) return null
        val out = ArrayList<Level>()
        for (part in text.split(";")) {
            val f = part.split(",")
            try {
                if (f.size == 2 && f[0] == "B") {
                    out.add(Level(0, 0, f[1].toInt().coerceIn(1, 180), isBreak = true))
                } else if (f.size == 3) {
                    // the big blind is always twice the small blind
                    val sb = f[0].toInt().coerceAtLeast(1)
                    out.add(Level(sb, sb * 2, f[2].toInt().coerceIn(1, 180)))
                } else return null
            } catch (e: NumberFormatException) {
                return null
            }
        }
        return if (out.any { !it.isBreak }) out else null
    }

    /** The lineup the app used to start with; a box still on it moves to the new one. */
    private const val OLD_DEFAULT = "1,2,20;2,4,20;3,6,20;4,8,20;B,15;5,10,20;6,12,20;7,14,20;8,16,20;B,15;9,18,20;10,20,20;15,30,10;B,15;20,40,20"

    fun load(ctx: Context): List<Level> {
        val txt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (txt == OLD_DEFAULT) return DEFAULT
        return decode(txt) ?: DEFAULT
    }

    // ---------- saved lineups (Setup > Saved lineups) ----------
    const val SLOTS = 3
    fun slot(ctx: Context, n: Int): List<Level>? =
        decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lineup_slot_$n", null))
    fun saveSlot(ctx: Context, n: Int, levels: List<Level>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lineup_slot_$n", encode(levels)).apply()
    }
    /** "9 levels, 2 breaks · 1-2 to 9-18" */
    fun summary(levels: List<Level>): String {
        val plays = levels.filter { !it.isBreak }
        val breaks = levels.size - plays.size
        val range = if (plays.isEmpty()) "" else "  \u00B7  ${plays.first().sb}\u2013${plays.first().bb} to ${plays.last().sb}\u2013${plays.last().bb}"
        return "${plays.size} level${if (plays.size == 1) "" else "s"}, $breaks break${if (breaks == 1) "" else "s"}$range"
    }

    fun save(ctx: Context, levels: List<Level>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(levels)).apply()
    }
}

/** On/off switches from the Setup screen. */
object Prefs {
    private const val PREFS = "poker_timer"
    fun sounds(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sounds", true)
    /** Voice: 0 = off, 1 = blinds only (default), 2 = 1-minute warning + blinds. */
    fun voiceMode(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains("voiceMode")) return p.getInt("voiceMode", VOICE_BLINDS)
        return if (p.getBoolean("voice", true)) VOICE_BLINDS else VOICE_OFF   // older switch
    }
    fun setVoiceMode(ctx: Context, m: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("voiceMode", m).apply()
    }
    const val VOICE_OFF = 0
    const val VOICE_BLINDS = 1
    const val VOICE_ALL = 2
    fun openAtStartup(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("openAtStartup", false)
    fun chips(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("chips", true)
    fun testMode(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("testMode", false)
    fun knockSound(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("knockSound", Sounds.DEFAULT) ?: Sounds.DEFAULT
    fun setKnockSound(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("knockSound", id).apply()
    }
    fun theme(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", Theme.GREEN_BAY) ?: Theme.GREEN_BAY
    fun setTheme(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", id).apply()
    }
    fun set(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }
}
