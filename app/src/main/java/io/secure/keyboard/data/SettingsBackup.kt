package io.secure.keyboard.data

import android.content.Context
import io.secure.keyboard.ai.AiPromptStore
import org.json.JSONArray
import org.json.JSONObject

/** Export an explicit allowlist. Credentials, clipboard, session state and revision tokens never enter it. */
internal class SettingsBackup(private val context: Context) {
    private val groups = linkedMapOf(
        "theme" to ("settings" to setOf("theme", "manualDark", "themeTokens_light", "themeTokens_dark", "symbolHints", "candidateTextSize", "expandedCandidateTextSize")),
        "layout" to ("settings" to setOf("profile_inner_portrait", "profile_inner_landscape", "profile_outer_portrait", "profile_outer_landscape")),
        "toolbar" to ("settings" to setOf("toolbarPins")),
        "symbols" to ("symbol_closure" to setOf("rules")),
        "symbolKeyboard" to ("settings" to setOf("symbol_keyboard_zh_1", "symbol_keyboard_zh_2", "symbol_keyboard_en_1", "symbol_keyboard_en_2")),
        "longPress" to ("settings" to (setOf("longPressSymbols", "longPressDelay") + listOf("zh", "en").flatMap { lang -> ('a'..'z').map { "letter_symbols_${lang}_$it" } })),
        "prompts" to ("ai_builtin_prompts" to AiPromptStore.prompts.map { it.id }.toSet()),
    )
    fun snapshot(): JSONObject = JSONObject().also { root -> groups.forEach { (group, spec) ->
        val all = context.getSharedPreferences(spec.first, 0).all
        root.put(group, JSONObject().also { values -> spec.second.forEach { key -> all[key]?.let { values.put(key, it) } } })
    } }
    fun validate(root: JSONObject) {
        require(root.keys().asSequence().toSet() == groups.keys)
        groups.forEach { (group, spec) ->
            val values = root.getJSONObject(group)
            require(values.keys().asSequence().all { it in spec.second })
            values.keys().forEach { key ->
                val value = values.get(key)
                when {
                    key in setOf("manualDark", "longPressSymbols") -> require(value is Boolean)
                    key in setOf("candidateTextSize", "expandedCandidateTextSize") -> integer(value, 12..32)
                    key == "longPressDelay" -> integer(value, 150..1500)
                    else -> {
                        require(value is String && value.length <= 100_000)
                        when {
                            group == "prompts" -> require(value.length <= 12000)
                            key == "theme" -> require(value in setOf("light", "dark", "system"))
                            key == "symbolHints" -> require(value in SettingsStore.symbolHintModes)
                            key == "toolbarPins" -> {
                                val pins = value.split(',').filter(String::isNotEmpty)
                                require(pins.distinct().size == pins.size && pins.all { it in SettingsStore.availablePins })
                            }
                            key.startsWith("themeTokens_") -> {
                                checkDepth(value); val colors = JSONObject(value)
                                require(colors.keys().asSequence().all { it in SettingsStore.themeTokenIds })
                                colors.keys().forEach { require(colors.get(it) is String && colors.getString(it).matches(Regex("#[0-9a-fA-F]{6}"))) }
                            }
                            key.startsWith("profile_") -> {
                                checkDepth(value); val profile = JSONObject(value)
                                val ranges = mapOf("heightDp" to 200..700, "sideGapDp" to 0..300, "bottomGapDp" to 0..300,
                                    "floatingXDp" to 0..2000, "floatingYDp" to 0..2000, "floatingWidthDp" to 220..1600,
                                    "floatingHeightDp" to 200..700, "splitGapDp" to 0..180)
                                require(profile.keys().asSequence().all { it in ranges || it in setOf("floating", "split") })
                                profile.keys().forEach { field -> ranges[field]?.let { integer(profile.get(field), it) }
                                    ?: require(profile.get(field) is Boolean) }
                            }
                            key == "rules" -> {
                                checkDepth(value); val rules = JSONArray(value); require(rules.length() <= 256)
                                val openings = hashSetOf<String>()
                                repeat(rules.length()) { i -> val row = rules.getJSONObject(i)
                                    require(row.keys().asSequence().toSet() == setOf("opening", "closing", "moveBack"))
                                    require(row.get("opening") is String && row.get("closing") is String)
                                    integer(row.get("moveBack"), 0..17)
                                    val rule = SymbolClosureStore.Rule(row.getString("opening"), row.getString("closing"), row.getInt("moveBack"))
                                    rule.validate(); require(openings.add(rule.opening))
                                }
                            }
                            key.startsWith("letter_symbols_") -> require(value.codePointCount(0,value.length) <= 8 && value.none { it.isWhitespace() || it.isISOControl() } && value.codePoints().toArray().none { it in 0xD800..0xDFFF })
                            key.startsWith("symbol_keyboard_") -> {
                                checkDepth(value); val rows = JSONArray(value); require(rows.length() == 3)
                                repeat(3) { i -> val row = rows.getJSONArray(i); require(row.length() == if(i==2) 7 else 10)
                                    repeat(row.length()) { j -> require(row.get(j) is String); val symbol = row.getString(j)
                                        require(symbol.codePointCount(0,symbol.length)==1 && symbol.isNotBlank() && symbol.none { it.isISOControl() } && symbol.codePointAt(0) !in 0xD800..0xDFFF)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Embedded preference JSON has at most two structural levels. Reject hostile depth before
    // JSONObject's recursive parser sees it; quoted prompt text is never parsed as JSON.
    private fun checkDepth(value: String) {
        var depth=0; var quoted=false; var escaped=false
        for(c in value) {
            if(quoted) {
                if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false
            } else when(c) {
                '"' -> quoted=true
                '{','[' -> { depth++; require(depth<=3) }
                '}',']' -> { depth--; require(depth>=0) }
            }
        }
        require(depth==0 && !quoted)
    }
    private fun integer(value: Any, range: IntRange) { require(value is Int || value is Long); require((value as Number).toLong() in range.first.toLong()..range.last.toLong()) }
    fun changes(next: JSONObject): List<String> {
        val current = snapshot()
        return groups.keys.mapNotNull { group ->
            val spec = groups.getValue(group)
            val before = current.getJSONObject(group); val after = next.getJSONObject(group)
            val count = spec.second.count { before.opt(it) != after.opt(it) }
            if (count == 0) null else "${labels.getValue(group)}：$count 项设置变更"
        }
    }
    fun apply(root: JSONObject) {
        validate(root)
        // Commit each preference file once. Caller retains the previous snapshot for rollback.
        groups.values.map { it.first }.distinct().forEach { file ->
            val edit = context.getSharedPreferences(file,0).edit()
            groups.filterValues { it.first == file }.forEach { (group,spec) ->
                spec.second.forEach(edit::remove)
                val values = root.getJSONObject(group)
                values.keys().forEach { key -> when(val value=values.get(key)) {
                    is Boolean -> edit.putBoolean(key,value)
                    is Number -> edit.putInt(key,value.toInt())
                    is String -> edit.putString(key,value)
                } }
            }
            check(edit.commit()) { "设置保存失败，请重试" }
        }
    }
    companion object {
        val labels = linkedMapOf("theme" to "主题与候选字号", "layout" to "横竖屏与内外屏布局", "toolbar" to "工具栏", "symbols" to "符号自动闭合", "symbolKeyboard" to "中英文符号键盘", "longPress" to "字母长按符号", "prompts" to "内置 AI 提示词")
    }
}
