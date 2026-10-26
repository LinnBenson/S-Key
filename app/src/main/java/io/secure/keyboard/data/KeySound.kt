package io.secure.keyboard.data

object KeySound {
    val options = listOf(
        "none" to "无", "system" to "系统音", "bong_001" to "音效 1",
        "click_001" to "音效 2", "click_002" to "音效 3", "click_003" to "音效 4",
        "click_004" to "音效 5", "click_005" to "音效 6",
    )
    fun normalize(value: String) = value.takeIf { id -> options.any { it.first == id } } ?: "none"
    fun label(value: String) = options.first { it.first == normalize(value) }.second
}
