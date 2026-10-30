package com.wyred4fun.pokertimer

/** Every knock sound made so far; Setup > Knock sound lets you hear them and pick one. */
object Sounds {
    data class Sound(val id: String, val name: String, val res: Int)

    const val DEFAULT = "b3"

    val ALL: List<Sound> = listOf(
        Sound("b3", "Bamboo on a tree: hardest, longer", R.raw.knock_b3),
        Sound("b2", "Bamboo on a tree: harder", R.raw.knock_b2),
        Sound("b1", "Bamboo on a tree: crisp", R.raw.knock_b1),
        Sound("b0", "Bamboo on a tree: as recorded", R.raw.knock_b0),
        Sound("p01", "Bamboo on a 2x4", R.raw.knock_p01),
        Sound("p02", "Deeper 2x4 dunk", R.raw.knock_p02),
        Sound("p03", "Sharper 2x4 smack", R.raw.knock_p03),
        Sound("p04", "Thick bamboo tube, low", R.raw.knock_p04),
        Sound("p05", "Thick bamboo tube, mid", R.raw.knock_p05),
        Sound("p06", "Thin bamboo tube", R.raw.knock_p06),
        Sound("p07", "Hollow bamboo tok", R.raw.knock_p07),
        Sound("p08", "Woodblock, low", R.raw.knock_p08),
        Sound("p09", "Woodblock, high", R.raw.knock_p09),
        Sound("p10", "Drumstick rim-click", R.raw.knock_p10),
        Sound("p11", "Stick on stick", R.raw.knock_p11),
        Sound("p12", "Dull thud on a beam", R.raw.knock_p12),
        Sound("p13", "Knock on a wooden door", R.raw.knock_p13),
        Sound("p14", "Cajon-style slap", R.raw.knock_p14),
        Sound("p15", "Bamboo split stick", R.raw.knock_p15),
        Sound("p16", "Temple block, warm", R.raw.knock_p16),
        Sound("p17", "Short dry tick", R.raw.knock_p17),
        Sound("p18", "Heavy mallet on 2x4", R.raw.knock_p18),
        Sound("t1", "Bamboo ting", R.raw.knock_t1),
        Sound("t2", "Bright ting", R.raw.knock_t2),
        Sound("t3", "Hollow ting", R.raw.knock_t3),
        Sound("o1", "First timer knock", R.raw.knock_o1)
    )

    fun byId(id: String?): Sound = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT }
}
