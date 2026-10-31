package com.wyred4fun.pokertimer

/**
 * The notification sounds Jeff kept. The chosen one plays once a second for the last
 * 5 seconds (5, 4, 3, 2, 1) as the blinds go up (Setup > Notification sound).
 */
object Sounds {
    data class Sound(val id: String, val name: String, val res: Int)

    const val DEFAULT = "p09"

    val ALL: List<Sound> = listOf(
        Sound("c24", "Desk bell ding", R.raw.knock_c24),
        Sound("c23", "Cowbell, short", R.raw.knock_c23),
        Sound("p09", "Woodblock, high", R.raw.knock_p09),
        Sound("p16", "Temple block, warm", R.raw.knock_p16),
        Sound("boat", "Boat horn", R.raw.knock_boat),
        Sound("loon", "Loon call", R.raw.knock_loon),
        Sound("tri", "Triangle, bright", R.raw.knock_tri),
        Sound("whistle", "Steam whistle", R.raw.knock_whistle)
    )

    /** A saved choice that no longer exists falls back to the default. */
    fun byId(id: String?): Sound = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT }
}
