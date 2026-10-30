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
        Level(4, 8, 20),
        Level(0, 0, 15, isBreak = true),
        Level(5, 10, 20),
        Level(6, 12, 20),
        Level(7, 14, 20),
        Level(8, 16, 20),
        Level(0, 0, 15, isBreak = true),
        Level(9, 18, 20),
        Level(10, 20, 20),
        Level(15, 30, 10),
        Level(0, 0, 15, isBreak = true),
        Level(20, 40, 20)
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
                    out.add(Level(f[0].toInt().coerceAtLeast(1), f[1].toInt().coerceAtLeast(1), f[2].toInt().coerceIn(1, 180)))
                } else return null
            } catch (e: NumberFormatException) {
                return null
            }
        }
        return if (out.any { !it.isBreak }) out else null
    }

    fun load(ctx: Context): List<Level> =
        decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) ?: DEFAULT

    fun save(ctx: Context, levels: List<Level>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(levels)).apply()
    }
}

/** On/off switches from the Setup screen. */
object Prefs {
    private const val PREFS = "poker_timer"
    fun sounds(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sounds", true)
    fun voice(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("voice", true)
    fun openAtStartup(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("openAtStartup", false)
    fun testMode(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("testMode", false)
    fun knockSound(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("knockSound", Sounds.DEFAULT) ?: Sounds.DEFAULT
    fun setKnockSound(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("knockSound", id).apply()
    }
    fun set(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }
}
