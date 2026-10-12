package io.secure.keyboard.ui

/** Literal user-specified symbols. Never normalize, combine pairs, or apply letter casing. */
object SymbolKeyLayout {
    private val chinesePrimary = listOf(
        "1234567890",
        "@#${'$'}_&-+（）/",
        "、“”：；！？",
    )
    private val chineseSecondary = listOf(
        "~`|•《》÷×*§",
        ".,€¥^°=「」\\",
        "%‘’™✓［］",
    )
    private val englishPrimary = listOf(
        "1234567890",
        "@#${'$'}_&-+()/",
        "*\"':;!?",
    )
    private val englishSecondary = listOf(
        "~`|•<>÷×§∆",
        ".,€¥^°={}\\",
        "%©®™✓[]",
    )

    fun rows(chinese: Boolean, secondary: Boolean): List<String> = when {
        chinese && secondary -> chineseSecondary
        chinese -> chinesePrimary
        secondary -> englishSecondary
        else -> englishPrimary
    }

    /** Keep ten-key widths; equal inner gaps center a short row between its two controls. */
    fun sideSpacerWeight(symbolCount: Int, hasSideControls: Boolean): Float =
        ((10f - symbolCount - (if (hasSideControls) 2.6f else 0f)) / 2f).coerceAtLeast(0f)
}
