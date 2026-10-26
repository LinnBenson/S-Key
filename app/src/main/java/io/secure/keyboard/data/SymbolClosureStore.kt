package io.secure.keyboard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class SymbolClosureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("symbol_closure", Context.MODE_PRIVATE)
    data class Rule(val opening: String, val closing: String, val moveBack: Int) {
        val text get() = opening + closing
        val cursorIndex get() = text.offsetByCodePoints(text.length, -moveBack)
        val moveBackUnits get() = text.length - cursorIndex
        fun validate() {
            require(opening.codePointCount(0, opening.length) == 1 && opening.none { it.isISOControl() }) { "输入符号须为一个字符" }
            require(closing.codePointCount(0, closing.length) in 1..16 && closing.none { it.isISOControl() }) { "闭合符号须为 1–16 个字符" }
            require(moveBack in 0..text.codePointCount(0, text.length)) { "回移位数须在 0 到插入字符总数之间" }
        }
    }
    fun rules(): List<Rule> {
        val saved = prefs.getString("rules", null) ?: return defaults
        return runCatching {
            val array = JSONArray(saved)
            require(array.length() <= 256)
            List(array.length()) { i -> array.getJSONObject(i).let { Rule(it.getString("opening"), it.getString("closing"), it.getInt("moveBack")).also { r -> r.validate() } } }
        }.getOrElse { defaults }
    }
    fun save(original: String?, rule: Rule) {
        rule.validate()
        val rows = rules().toMutableList()
        require(rows.none { it.opening == rule.opening && it.opening != original }) { "此输入符号已有规则" }
        val index = rows.indexOfFirst { it.opening == original }
        if (index >= 0) rows[index] = rule else rows.add(rule)
        require(rows.size <= 256) { "最多添加 256 条规则" }
        write(rows)
    }
    fun delete(opening: String) = write(rules().filterNot { it.opening == opening })
    private fun write(rows: List<Rule>) {
        val json = JSONArray()
        rows.forEach { json.put(JSONObject().put("opening", it.opening).put("closing", it.closing).put("moveBack", it.moveBack)) }
        prefs.edit().putString("rules", json.toString()).apply()
    }
    companion object {
        val defaults = listOf(
            Rule("\"", "\"", 1), Rule("'", "'", 1), Rule("{", "}", 1), Rule("[", "]", 1),
            Rule("<", ">", 1), Rule("(", ")", 1), Rule("“", "”", 1), Rule("‘", "’", 1),
            Rule("「", "」", 1), Rule("［", "］", 1), Rule("《", "》", 1), Rule("（", "）", 1)
        )
    }
}
