package io.secure.keyboard.engine

enum class ChineseInputMode(val id: String, val label: String, val glyph: String) {
    WUBI("wubi", "五笔", "五"), MIXED("mixed", "五笔拼音混输", "混"), PINYIN("pinyin", "拼音输入", "拼");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: MIXED }
}
