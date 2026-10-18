package io.secure.keyboard.ui

/** Bottom margins are measured upwards, so height + bottom stays constant at a fixed top. */
internal object FloatingResize {
    fun maxBottom(screenHeight: Int, keyboardHeight: Int): Int =
        (screenHeight - keyboardHeight).coerceAtLeast(0)

    data class Bounds(val width: Int, val height: Int, val left: Int, val bottom: Int)

    fun resize(start: Bounds, dx: Int, dy: Int, screenWidth: Int, maxHeight: Int, maxBottom: Int): Bounds {
        val topDistance = start.height + start.bottom
        val availableWidth = (screenWidth - start.left).coerceAtLeast(1)
        val upperHeight = minOf(maxHeight, topDistance).coerceAtLeast(1)
        val lowerHeight = maxOf(200.coerceAtMost(upperHeight), topDistance - maxBottom)
            .coerceAtMost(upperHeight)
        val height = (start.height + dy).coerceIn(lowerHeight, upperHeight)
        return Bounds(
            (start.width + dx).coerceIn(220.coerceAtMost(availableWidth), availableWidth),
            height, start.left, topDistance - height,
        )
    }
}
