package com.wyred4fun.pokertimer

/**
 * The notification sounds Jeff kept. Each plays a set number of times, one per second,
 * finishing as the blinds go up (Setup > Notification sound).
 */
object Sounds {
    data class Sound(val id: String, val name: String, val res: Int, val hits: Int)

    const val DEFAULT = "p09"
    /** The most hits any sound has (used to keep previews off a live countdown). */
    val MAX_HITS: Int get() = ALL.maxOf { it.hits }

    val ALL: List<Sound> = listOf(
        Sound("c24", "Desk bell ding", R.raw.knock_c24, 1),
        Sound("c23", "Cowbell, short", R.raw.knock_c23, 2),
        Sound("p09", "Woodblock, high", R.raw.knock_p09, 5),
        Sound("p16", "Temple block, warm", R.raw.knock_p16, 3)
    )

    /** A saved choice that no longer exists falls back to the default. */
    fun byId(id: String?): Sound = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT }
}
