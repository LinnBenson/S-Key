package io.secure.keyboard.ui

/** Pixel allocation for the mirrored 17 / 9 / 42 / 9 / 17 bottom row. */
object BottomRowGeometry {
    fun widths(availableWidth: Int): IntArray {
        require(availableWidth >= 0)
        // Round each pair once, rather than assigning the accumulated remainder to Enter.
        // Long arithmetic also keeps the calculation exact for every nonnegative Int width.
        val outer = ((availableWidth * 17L + 47L) / 94L).toInt()
        val inner = ((availableWidth * 9L + 47L) / 94L).toInt()
        val space = availableWidth - 2 * (outer + inner)
        return intArrayOf(outer, inner, space, inner, outer)
    }
}
