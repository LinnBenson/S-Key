package io.secure.keyboard.ui

/** Literal user-specified symbols. Never normalize, combine pairs, or apply letter casing. */
object SymbolKeyLayout {
    private val chinesePrimary = listOf(
        "1234567890",
        "@#${'$'}_&-+（）/",
        ".,、：；！？",
    )
    private val chineseSecondary = listOf(
        "~`|•《》÷×*§",
        "“”€¥^°=「」\\",
        "%‘’™✓［］",
    )
    private val englishPrimary = listOf(
        "1234567890",
        "@#${'$'}_&-+()/",
        ".,*:;!?",
    )
    private val englishSecondary = listOf(
        "~`|•<>÷×§∆",
        "\"'€¥^°={}\\",
        "%©®™✓[]",
    )

    fun rows(chinese: Boolean, secondary: Boolean): List<String> = when {
        chinese && secondary -> chineseSecondary
        chinese -> chinesePrimary
        secondary -> englishSecondary
        else -> englishPrimary
    }

    /** Control rows use the same 1.3 : seven unit keys : 1.3 layout as the letter row. */
    fun sideSpacerWeight(symbolCount: Int, hasSideControls: Boolean): Float =
        if (hasSideControls) 0f else ((10f - symbolCount) / 2f).coerceAtLeast(0f)
}
