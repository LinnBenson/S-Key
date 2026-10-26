package io.secure.keyboard.data

import android.content.Context
import org.json.JSONObject

/**
 * Configuration is deliberately separate from user content and is never cleared by content erasure.
 */
class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lastEmojiCategory: Int
        get() = prefs.getInt("lastEmojiCategory", 1).coerceIn(0, 9)
        set(value) { prefs.edit().putInt("lastEmojiCategory", value.coerceIn(0, 9)).apply() }

    var screenshotProtection: Boolean
        get() = prefs.getBoolean("screenshotProtection", false)
        set(value) { prefs.edit().putBoolean("screenshotProtection", value).apply() }

    var traditionalOutput: Boolean
        get() = prefs.getBoolean("traditionalOutput", false)
        set(value) { prefs.edit().putBoolean("traditionalOutput", value).apply() }

    var includeNavigationBar: Boolean
        get() = prefs.getBoolean("includeNavigationBar", true)
        set(value) { prefs.edit().putBoolean("includeNavigationBar", value).apply() }

    var backgroundClipboard: Boolean
        get() = prefs.getBoolean("backgroundClipboard", true)
        set(value) { prefs.edit().putBoolean("backgroundClipboard", value).apply() }

    var quickPaste: Boolean
        get() = prefs.getBoolean("quickPaste", true)
        set(value) { prefs.edit().putBoolean("quickPaste", value).apply() }

    var quickPasteConsumedToken: Long
        get() = prefs.getLong("quickPasteConsumedToken", 0L)
        set(value) { prefs.edit().putLong("quickPasteConsumedToken", value).apply() }

    var lastCustomAiPresetId: String?
        get() = prefs.getString("lastCustomAiPresetId", null)
        set(value) { prefs.edit().putString("lastCustomAiPresetId", value).apply() }

    val aiConversationHours: Int
        get() = prefs.getInt("aiConversationHours", 4).coerceIn(0, 720)

    var aiAutoApply: Boolean
        get() = prefs.getBoolean("aiAutoApply", false)
        set(value) { prefs.edit().putBoolean("aiAutoApply", value).apply() }

    var voiceAutoTranslate: Boolean
        get() = prefs.getBoolean("voiceAutoTranslate", true)
        set(value) { prefs.edit().putBoolean("voiceAutoTranslate", value).apply() }

    var editorPreview: Boolean
        get() = prefs.getBoolean("editorPreview", true)
        set(value) { prefs.edit().putBoolean("editorPreview", value).apply() }

    var autoChineseSpacing: Boolean
        get() = prefs.getBoolean("autoChineseSpacing", true)
        set(value) { prefs.edit().putBoolean("autoChineseSpacing", value).apply() }

    var voiceMode: String
        get() = prefs.getString("voiceMode", "offline")?.takeIf { it == "ai" } ?: "offline"
        set(value) { prefs.edit().putString("voiceMode", if(value == "ai") "ai" else "offline").apply() }

    var translationSource: String
        get() = prefs.getString("translationSource", "auto") ?: "auto"
        set(value) { prefs.edit().putString("translationSource", value).apply() }
    var translationTarget: String
        get() = prefs.getString("translationTarget", "en") ?: "en"
        set(value) { prefs.edit().putString("translationTarget", value).apply() }

    /** Shared keyboard geometry, in dp; shared by the light and dark themes. */
    var keyBorderWidth: Int
        get() = prefs.getInt("keyDesign_border", 0).coerceIn(0, 4)
        set(value) { prefs.edit().putInt("keyDesign_border", value.coerceIn(0, 4)).apply() }
    var keyHorizontalGap: Int
        get() = prefs.getInt("keyDesign_horizontal", 4).coerceIn(0, 16)
        set(value) { prefs.edit().putInt("keyDesign_horizontal", value.coerceIn(0, 16)).apply() }
    var keyVerticalGap: Int
        get() = prefs.getInt("keyDesign_vertical", 5).coerceIn(0, 16)
        set(value) { prefs.edit().putInt("keyDesign_vertical", value.coerceIn(0, 16)).apply() }

    var keyCornerRadius: Int
        get() = prefs.getInt("keyDesign_radius", 7).coerceIn(0, 30)
        set(value) { prefs.edit().putInt("keyDesign_radius", value.coerceIn(0, 30)).apply() }
    var enterCornerRadius: Int
        get() = prefs.getInt("keyDesign_enterRadius", 30).coerceIn(0, 30)
        set(value) { prefs.edit().putInt("keyDesign_enterRadius", value.coerceIn(0, 30)).apply() }

    fun resetUiDesign() {
        prefs.edit()
            .remove("keyDesign_border")
            .remove("keyDesign_horizontal")
            .remove("keyDesign_vertical")
            .remove("keyDesign_radius")
            .remove("keyDesign_enterRadius")
            .apply()
    }

    data class LayoutProfile(
        val heightDp: Int = 260,
        val sideGapDp: Int = 0,
        val bottomGapDp: Int = 0,
        val floating: Boolean = false,
        val floatingXDp: Int = 0,
        val floatingYDp: Int = 0,
        val floatingWidthDp: Int = 340,
        val floatingHeightDp: Int = 260,
        val split: Boolean = false,
        val splitGapDp: Int = 60,
        val popupHeightDp: Int = 145,
    )

    /** Opaque foreground/background tokens. Light and dark themes have independent values. */
    data class ThemeTokens(
        val keyboardBackground: String,
        val normalKeyBackground: String,
        val specialKeyBackground: String,
        val text: String,
        val hint: String,
        val toolbarIcon: String,
        val candidate: String,
        val candidateSelected: String,
        val accent: String,
        val enterText: String,
        val preedit: String,
        val divider: String,
        val specialKeyText: String,
        val selectedKeyBackground: String,
        val selectedKeyText: String,
    ) {
        fun asMap(): Map<String, String> = linkedMapOf(
            "keyboardBackground" to keyboardBackground,
            "normalKeyBackground" to normalKeyBackground,
            "specialKeyBackground" to specialKeyBackground,
            "text" to text,
            "hint" to hint,
            "toolbarIcon" to toolbarIcon,
            "candidate" to candidate,
            "candidateSelected" to candidateSelected,
            "accent" to accent,
            "enterText" to enterText,
            "preedit" to preedit,
            "divider" to divider,
            "specialKeyText" to specialKeyText,
            "selectedKeyBackground" to selectedKeyBackground,
            "selectedKeyText" to selectedKeyText,
        )
    }

    fun themeTokens(dark: Boolean): ThemeTokens {
        val json = try {
            JSONObject(prefs.getString(themeTokensKey(dark), "{}") ?: "{}")
        } catch (_: Exception) { JSONObject() }
        return decodeThemeTokens(json, dark)
    }

    fun saveThemeTokens(dark: Boolean, tokens: ThemeTokens) {
        val json = JSONObject()
        // Validate the entire palette before changing any preferences.
        tokens.asMap().forEach { (id, color) -> json.put(id, validatedColor(color)) }
        prefs.edit().putString(themeTokensKey(dark), json.toString()).apply()
    }

    fun setThemeToken(dark: Boolean, id: String, colorHex: String) {
        require(id in themeTokenIds) { "未知的主题颜色项目" }
        val color = validatedColor(colorHex)
        val values = themeTokens(dark).asMap().toMutableMap()
        values[id] = color
        val json = JSONObject().apply { values.forEach { (token, hex) -> put(token, hex) } }
        saveThemeTokens(dark, decodeThemeTokens(json, dark))
    }

    fun resetThemeTokens(dark: Boolean) {
        prefs.edit().remove(themeTokensKey(dark)).apply()
    }

    private fun decodeThemeTokens(json: JSONObject, dark: Boolean): ThemeTokens {
        val defaults = defaultThemeTokens(dark).asMap()
        fun color(id: String): String = normalizedColor(json.optString(id, "")) ?: defaults.getValue(id)
        return ThemeTokens(
            keyboardBackground = color("keyboardBackground"),
            normalKeyBackground = color("normalKeyBackground"),
            specialKeyBackground = color("specialKeyBackground"),
            text = color("text"),
            hint = color("hint"),
            toolbarIcon = color("toolbarIcon"),
            candidate = color("candidate"),
            candidateSelected = color("candidateSelected"),
            accent = color("accent"),
            enterText = color("enterText"),
            preedit = color("preedit"),
            divider = color("divider"),
            specialKeyText = color("specialKeyText"),
            selectedKeyBackground = color("selectedKeyBackground"),
            selectedKeyText = color("selectedKeyText"),
        )
    }

    private fun themeTokensKey(dark: Boolean) = if (dark) "themeTokens_dark" else "themeTokens_light"
    private fun normalizedColor(value: String): String? = value.trim().takeIf { opaqueHex.matches(it) }?.uppercase()
    private fun validatedColor(value: String): String = normalizedColor(value)
        ?: throw IllegalArgumentException("请输入不透明的六位十六进制颜色，例如 #336699；不支持透明度。")

    var clipboardRetention: String
        get() = prefs.getString("clipboardRetention", "timed")?.takeIf { it in setOf("none", "timed", "forever") } ?: "timed"
        set(value) { require(value in setOf("none", "timed", "forever")); prefs.edit().putString("clipboardRetention", value).apply() }

    var clipboardRetentionHours: Int
        get() = prefs.getInt("clipboardRetentionHours", 24).coerceIn(1, 168)
        set(value) { prefs.edit().putInt("clipboardRetentionHours", value.coerceIn(1, 168)).apply() }

    var clipboardLimit: Int
        get() = prefs.getInt("clipboardLimit", 10).coerceIn(1, 300)
        set(value) {
            prefs.edit().putInt("clipboardLimit", value.coerceIn(1, 300)).apply()
        }

    internal var lastClipboardToken: Long
        get() = prefs.getLong("lastClipboardToken", 0L)
        set(value) {
            check(prefs.edit().putLong("lastClipboardToken", value).commit()) {
                "无法保存剪贴板清空保护，操作尚未完成。请检查可用空间后重试。"
            }
        }

    /** system, light, or dark */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) {
            prefs
                .edit()
                .putString(
                    "theme",
                    value.takeIf { it in listOf("system", "light", "dark") } ?: "system",
                )
                .putBoolean("manualDark", if (value == "system") manualDark else value == "dark")
                .apply()
        }

    var manualDark: Boolean
        get() = prefs.getBoolean("manualDark", false)
        set(value) { prefs.edit().putBoolean("manualDark", value).apply() }

    fun isDark(systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    /** Independent opt-in for platform keyboard sounds; existing installs stay silent. */
    var keySound: Boolean
        get() = prefs.getBoolean("keySound", false)
        set(value) {
            prefs.edit().putBoolean("keySound", value).apply()
        }

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) {
            prefs.edit().putBoolean("vibration", value).apply()
        }

    /** Last explicit language choice only: symbol pages and editor text are never stored. */
    var lastChineseLanguage: Boolean
        get() = prefs.getBoolean("lastChineseLanguage", DEFAULT_CHINESE_LANGUAGE)
        set(value) {
            prefs.edit().putBoolean("lastChineseLanguage", value).apply()
        }

    /**
     * Strength as percentage, 1–100; uses amplitude when supported, short duration fallback
     * otherwise.
     */
    var candidateTextSize: Int
        get() = prefs.getInt("candidateTextSize", 20).coerceIn(12, 32)
        set(value) { prefs.edit().putInt("candidateTextSize", value.coerceIn(12, 32)).apply() }

    var expandedCandidateTextSize: Int
        get() = prefs.getInt("expandedCandidateTextSize", 18).coerceIn(12, 32)
        set(value) { prefs.edit().putInt("expandedCandidateTextSize", value.coerceIn(12, 32)).apply() }

    var vibrationStrength: Int
        get() = prefs.getInt("vibrationStrength", DEFAULT_VIBRATION_STRENGTH)
        set(value) {
            prefs.edit().putInt("vibrationStrength", value.coerceIn(1, 100)).apply()
        }

    /** Visual hints on alphabet keys only; long-press mappings are independent. */
    var symbolHints: String
        get() = prefs.getString("symbolHints", DEFAULT_SYMBOL_HINTS)
            ?.takeIf { it in symbolHintModes } ?: DEFAULT_SYMBOL_HINTS
        set(value) {
            prefs
                .edit()
                .putString("symbolHints", value.takeIf { it in symbolHintModes } ?: DEFAULT_SYMBOL_HINTS)
                .apply()
        }

    var swipeUpSymbols: Boolean
        get() = prefs.getBoolean("swipeUpSymbols", false)
        set(value) { prefs.edit().putBoolean("swipeUpSymbols", value).apply() }

    var longPressSymbols: Boolean
        get() = prefs.getBoolean("longPressSymbols", true)
        set(value) {
            prefs.edit().putBoolean("longPressSymbols", value).apply()
        }

    var longPressDelay: Int
        get() = prefs.getInt("longPressDelay", 400)
        set(value) {
            prefs.edit().putInt("longPressDelay", value.coerceIn(150, 1500)).apply()
        }

    var swipeDelete: Boolean
        get() = prefs.getBoolean("swipeDelete", true)
        set(value) {
            prefs.edit().putBoolean("swipeDelete", value).apply()
        }

    var chineseInputMode: io.secure.keyboard.engine.ChineseInputMode
        get() = io.secure.keyboard.engine.ChineseInputMode.fromId(prefs.getString("chineseInputMode", "mixed"))
        set(value) {
            prefs.edit().putString("chineseInputMode", value.id).apply {
                if (value != io.secure.keyboard.engine.ChineseInputMode.PINYIN)
                    putBoolean("wubiPinyinInput", value == io.secure.keyboard.engine.ChineseInputMode.MIXED)
            }.apply()
        }

    /** Public selector has two methods; MIXED remains an engine mode under Wubi. */
    var chineseInputMethod: io.secure.keyboard.engine.ChineseInputMode
        get() = if (chineseInputMode == io.secure.keyboard.engine.ChineseInputMode.PINYIN)
            io.secure.keyboard.engine.ChineseInputMode.PINYIN else io.secure.keyboard.engine.ChineseInputMode.WUBI
        set(value) {
            chineseInputMode = if (value == io.secure.keyboard.engine.ChineseInputMode.PINYIN) value
                else if (wubiPinyinInput) io.secure.keyboard.engine.ChineseInputMode.MIXED
                else io.secure.keyboard.engine.ChineseInputMode.WUBI
        }

    var wubiPinyinInput: Boolean
        get() = prefs.getBoolean("wubiPinyinInput", chineseInputMode != io.secure.keyboard.engine.ChineseInputMode.WUBI)
        set(value) {
            prefs.edit().putBoolean("wubiPinyinInput", value).apply {
                if (chineseInputMethod == io.secure.keyboard.engine.ChineseInputMode.WUBI)
                    putString("chineseInputMode", if (value) "mixed" else "wubi")
            }.apply()
        }

    var spaceEnglishGloss: Boolean
        get() = prefs.getBoolean("spaceEnglishGloss", false)
        set(value) { prefs.edit().putBoolean("spaceEnglishGloss", value).apply() }

    var fourCodeAutoCommit: Boolean
        get() = prefs.getBoolean("fourCodeAutoCommit", false)
        set(value) {
            prefs.edit().putBoolean("fourCodeAutoCommit", value).apply()
        }

    var englishSpaceSelectsFirst: Boolean
        get() = prefs.getBoolean("englishSpaceSelectsFirst", false)
        set(value) { prefs.edit().putBoolean("englishSpaceSelectsFirst", value).apply() }

    var chineseAssociation: Boolean
        get() = prefs.getBoolean("chineseAssociation", true)
        set(value) { prefs.edit().putBoolean("chineseAssociation", value).apply() }

    var englishAssociation: Boolean
        // Preserve the effective value of both old switches until the user changes it.
        get() = prefs.getBoolean("englishAssociation", true) && prefs.getBoolean("englishCompletion", true)
        set(value) { prefs.edit().putBoolean("englishAssociation", value).remove("englishCompletion").apply() }

    var fuzzyPinyin: Boolean
        get() = prefs.getBoolean("fuzzyPinyin", true)
        set(value) {
            prefs.edit().putBoolean("fuzzyPinyin", value).apply()
        }

    /** The master switch does not discard the user's independently selected fuzzy pairs. */
    var fuzzyPairs: Set<String>
        get() = (prefs.getStringSet("fuzzyPairs", defaultFuzzyPairIds) ?: defaultFuzzyPairIds)
            .filter { it in fuzzyPairIds }.toSet()
        set(value) {
            prefs.edit().putStringSet("fuzzyPairs", value.filter { it in fuzzyPairIds }.toSet()).apply()
        }

    var toolOrder: List<String>
        get() {
            val saved = prefs.getString("toolOrder", null)?.split(',')
                ?: (prefs.getString("toolbarPins", null)?.split(',')?.plus(availablePins) ?: availablePins)
            return (saved.filter { it in availablePins } + availablePins).distinct()
        }
        set(value) { prefs.edit().putString("toolOrder", (value.filter { it in availablePins } + availablePins).distinct().joinToString(",")).apply() }

    var toolbarPins: List<String>
        get() =
            (prefs.getString("toolbarPins", defaultPins.joinToString(",")) ?: "")
                .split(',')
                .filter { it in availablePins }
                .distinct().sortedBy { toolOrder.indexOf(it) }
        set(value) {
            prefs
                .edit()
                .putString(
                    "toolbarPins",
                    value.filter { it in availablePins }.distinct().joinToString(","),
                )
                .apply()
        }

    fun profile(outer: Boolean, landscape: Boolean): LayoutProfile {
        val defaults = defaultLayoutProfile(outer, landscape)
        val j =
            try {
                JSONObject(prefs.getString(profileKey(outer, landscape), "{}") ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
        return LayoutProfile(
            j.optInt("heightDp", defaults.heightDp),
            j.optInt("sideGapDp", defaults.sideGapDp),
            j.optInt("bottomGapDp", defaults.bottomGapDp),
            j.optBoolean("floating", false),
            j.optInt("floatingXDp", 0),
            j.optInt("floatingYDp", 0),
            j.optInt("floatingWidthDp", 340),
            j.optInt("floatingHeightDp", 260),
            j.optBoolean("split", false),
            j.optInt("splitGapDp", 60),
            j.optInt("popupHeightDp", defaults.popupHeightDp).coerceIn(110, 450),
        )
    }

    fun saveProfile(outer: Boolean, landscape: Boolean, profile: LayoutProfile) {
        val j =
            JSONObject()
                .put("heightDp", profile.heightDp.coerceIn(200, 700))
                .put("sideGapDp", profile.sideGapDp.coerceIn(0, 300))
                .put("bottomGapDp", profile.bottomGapDp.coerceIn(0, 300))
                .put("floating", profile.floating)
                .put("floatingXDp", profile.floatingXDp.coerceIn(0, 2000))
                .put("floatingYDp", profile.floatingYDp.coerceIn(0, 2000))
                .put("floatingWidthDp", profile.floatingWidthDp.coerceIn(220, 1600))
                .put("floatingHeightDp", profile.floatingHeightDp.coerceIn(200, 700))
                .put("split", profile.split)
                .put("splitGapDp", profile.splitGapDp.coerceIn(0, 180))
                .put("popupHeightDp", profile.popupHeightDp.coerceIn(110, 450))
        prefs.edit().putString(profileKey(outer, landscape), j.toString()).apply()
    }

    private fun profileKey(outer: Boolean, landscape: Boolean) =
        "profile_${if (outer) "outer" else "inner"}_${if (landscape) "landscape" else "portrait"}"

    companion object {
        internal const val DEFAULT_CHINESE_LANGUAGE = true
        internal const val DEFAULT_VIBRATION_STRENGTH = 10
        internal const val DEFAULT_SYMBOL_HINTS = "digits"

        /** Read-time fallbacks only: never migrate or replace explicitly saved settings. */
        internal fun defaultLayoutProfile(outer: Boolean, landscape: Boolean): LayoutProfile =
            when {
                outer && landscape -> LayoutProfile(heightDp = 210)
                outer -> LayoutProfile(heightDp = 275)
                landscape -> LayoutProfile(heightDp = 240, sideGapDp = 120)
                else -> LayoutProfile(heightDp = 270, sideGapDp = 20)
            }

        private val opaqueHex = Regex("#[0-9a-fA-F]{6}")
        val fuzzyInitialIds = listOf("zh_z", "ch_c", "sh_s", "n_l", "r_l", "f_h", "l_h", "r_y", "g_k")
        val fuzzyFinalIds = listOf("an_ang", "en_eng", "in_ing", "ai_ei", "ao_ou", "an_ian", "en_in", "eng_ing")
        val fuzzyPairIds = fuzzyInitialIds + fuzzyFinalIds
        // Defaults apply only when no pair selection has been saved.
        val defaultFuzzyPairIds = setOf("r_l", "en_eng", "in_ing")
        val themeTokenIds = listOf(
            "keyboardBackground", "normalKeyBackground", "specialKeyBackground", "text", "hint",
            "toolbarIcon", "candidate", "candidateSelected", "accent", "enterText", "preedit",
            "divider", "specialKeyText", "selectedKeyBackground", "selectedKeyText",
        )

        fun defaultThemeTokens(dark: Boolean): ThemeTokens {
            val text = if (dark) "#F0F4FC" else "#17283E"
            val accent = if (dark) "#88BFFF" else "#007AFF"
            val special = if (dark) "#3D5164" else "#D0D2D9"
            // Dark accent is pale: a dark foreground keeps accent/selected keys legible.
            val accentText = if (dark) "#17283E" else "#FFFFFF"
            return ThemeTokens(
                keyboardBackground = if (dark) "#172029" else "#EFEFF1",
                normalKeyBackground = if (dark) "#303D49" else "#FFFFFF",
                specialKeyBackground = special,
                text = text,
                hint = if (dark) "#AFBED0" else "#65788E",
                toolbarIcon = text,
                candidate = text,
                candidateSelected = accent,
                accent = accent,
                enterText = accentText,
                preedit = accent,
                divider = if (dark) special else "#CFD8DB",
                specialKeyText = text,
                selectedKeyBackground = accent,
                selectedKeyText = accentText,
            )
        }
        internal val symbolHintModes = setOf("all", "digits", "hidden")
        val defaultPins = listOf("ai", "floating", "quickedit", "clipboard", "emoji", "translate")
        // Keep the complete tool catalog independent of which tools are pinned by default.
        val availablePins = listOf(
            "ai", "floating", "quickedit", "clipboard", "emoji", "translate", "phrases", "split",
            "script", "input_mode", "voice", "dictionary", "english_dictionary", "association", "theme", "layout", "settings",
        )
    }
}
