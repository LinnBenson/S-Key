package io.secure.keyboard.data

import android.content.Context
import io.secure.keyboard.ui.SymbolKeyLayout
import org.json.JSONArray

internal class SymbolKeyboardStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE)
    private fun key(chinese:Boolean,second:Boolean)="symbol_keyboard_${if(chinese) "zh" else "en"}_${if(second) 2 else 1}"
    fun defaults(chinese:Boolean,second:Boolean):List<List<String>> = SymbolKeyLayout.rows(chinese,second).map { row ->
        row.codePoints().toArray().map { String(Character.toChars(it)) }
    }
    fun rows(chinese:Boolean,second:Boolean):List<List<String>> {
        val fallback=defaults(chinese,second)
        val json=prefs.getString(key(chinese,second),null) ?: return fallback
        return runCatching {
            val array=JSONArray(json);require(array.length()==3)
            List(3) { r -> val row=array.getJSONArray(r);require(row.length()==fallback[r].size)
                List(row.length()) { c -> row.getString(c).also { validate(it) } }
            }
        }.getOrElse { fallback }
    }
    fun save(chinese:Boolean,second:Boolean,row:Int,column:Int,symbol:String) {
        validate(symbol)
        val rows=rows(chinese,second).map { it.toMutableList() }
        require(row in rows.indices && column in rows[row].indices)
        rows[row][column]=symbol
        val json=JSONArray();rows.forEach { line -> json.put(JSONArray(line)) }
        prefs.edit().putString(key(chinese,second),json.toString()).apply()
    }
    fun restore(chinese:Boolean,second:Boolean) { prefs.edit().remove(key(chinese,second)).apply() }
    private fun validate(symbol:String) {
        require(symbol.codePointCount(0,symbol.length)==1 && symbol.isNotBlank() && symbol.none { it.isISOControl() } && symbol.codePointAt(0) !in 0xD800..0xDFFF) { "请输入一个符号，不能留空或换行" }
    }
}
