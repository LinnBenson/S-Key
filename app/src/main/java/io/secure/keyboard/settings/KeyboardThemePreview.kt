package io.secure.keyboard.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.ToolIcon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Read-only 26-key preview: production icons, theme tokens, stagger and 17/9/42/9/17 bottom row. */
@Composable
internal fun KeyboardThemePreview(tokens: SettingsStore.ThemeTokens, pins: List<String>, hints: String,
    initialCandidates: Boolean = false, initialShifted: Boolean = false,
    borderWidth: Int = 0, horizontalGap: Int = 4, verticalGap: Int = 6, cornerRadius: Int = 9, enterCornerRadius: Int = 9) {
    var candidates by remember { mutableStateOf(initialCandidates) }
    var shifted by remember { mutableStateOf(initialShifted) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        HorizontalChoices(listOf("toolbar" to "工具栏", "candidates" to "候选栏"),
            if (candidates) "candidates" else "toolbar", modifier = Modifier) {
            candidates = it == "candidates"
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(hexColor(tokens.keyboardBackground)).semantics { contentDescription = "完整26键主题预览" }) {
            val scale = maxWidth.value / 500f
            @Composable fun icon(kind: String, color: String, modifier: Modifier = Modifier) {
                key(kind, scale) {
                    AndroidView(factory = { context -> ToolIcon(context, kind, hexColor(color).toArgb(), 24f * scale) },
                        update = { it.tint = hexColor(color).toArgb() }, modifier = modifier)
                }
            }
            @Composable fun RowScope.keyFace(label: String, weight: Float = 1f, symbol: String? = null,
                hint: String = "", special: Boolean = false, selected: Boolean = false, enter: Boolean = false,
                onClick: (() -> Unit)? = null) {
                val background = when { enter -> tokens.accent; selected -> tokens.selectedKeyBackground
                    special -> tokens.specialKeyBackground; else -> tokens.normalKeyBackground }
                val foreground = when { enter -> tokens.enterText; selected -> tokens.selectedKeyText
                    special -> tokens.specialKeyText; else -> tokens.text }
                val radius = if (enter) enterCornerRadius else cornerRadius
                Box(Modifier.weight(weight).fillMaxHeight().padding(horizontal = (horizontalGap / 2f * scale).dp, vertical = (verticalGap / 2f * scale).dp)
                    .clip(RoundedCornerShape((radius * scale).dp)).background(hexColor(background))
                    .then(if (borderWidth > 0) Modifier.border((borderWidth * scale).dp, hexColor(tokens.hint), RoundedCornerShape((radius * scale).dp)) else Modifier)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .semantics { contentDescription = "预览按键：$label" }, contentAlignment = Alignment.Center) {
                    if (symbol != null) icon(symbol, foreground, Modifier.fillMaxSize())
                    else if (label == "，。") {
                        listOf("。" to 1f / 3f, "，" to 2f / 3f).forEach { (glyph, center) ->
                            AndroidView(factory = { context -> io.secure.keyboard.ui.PunctuationKeyLabel(context).apply {
                                text = glyph; inkCenterYFraction = center; textSize = 23f * scale
                            } }, update = { it.setTextColor(hexColor(foreground).toArgb()) }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    else Text(label, color = hexColor(foreground), fontSize = ((if (label.length > 1) 15 else 23) * scale).sp)
                    if (hint.isNotEmpty()) Text(hint, color = hexColor(tokens.hint), fontSize = (10 * scale).sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = (4 * scale).dp, top = (2 * scale).dp))
                }
            }
            Column(Modifier.padding((3 * scale).dp)) {
                if (candidates) {
                    Text("nihao", color = hexColor(tokens.preedit), fontSize = (12 * scale).sp,
                        modifier = Modifier.padding(start = (8 * scale).dp).height((17 * scale).dp))
                    Row(Modifier.fillMaxWidth().height((30 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("你好", "您好", "你", "好").forEachIndexed { index, word ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(word, color = hexColor(if (index == 0) tokens.candidateSelected else tokens.candidate), fontSize = (20 * scale).sp)
                            }
                        }
                        Box(Modifier.width((1 * scale).dp).height((18 * scale).dp).background(hexColor(tokens.divider)))
                        icon("clear", tokens.toolbarIcon, Modifier.width((42 * scale).dp).fillMaxHeight())
                        icon("hide", tokens.toolbarIcon, Modifier.width((42 * scale).dp).fillMaxHeight())
                    }
                } else {
                    Row(Modifier.fillMaxWidth().height((47 * scale).dp)) {
                        (listOf("more") + pins + "hide").forEach { kind ->
                            icon(if (kind == "floating") "floating_enter" else kind, tokens.toolbarIcon, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height((48 * scale).dp)) {
                    "qwertyuiop".forEachIndexed { i, c -> keyFace(if (shifted) c.uppercase() else "$c",
                        hint = if (hints != "hidden") ((i + 1) % 10).toString() else "") }
                }
                Row(Modifier.fillMaxWidth().height((48 * scale).dp)) {
                    Spacer(Modifier.weight(.45f))
                    "asdfghjkl".forEachIndexed { i, c -> keyFace(if (shifted) c.uppercase() else "$c",
                        hint = if (hints == "all") listOf("@", "#", "$", "%", "&", "*", "(", ")", "'")[i] else "") }
                    Spacer(Modifier.weight(.45f))
                }
                Row(Modifier.fillMaxWidth().height((48 * scale).dp)) {
                    keyFace("大写", weight = 1.3f, symbol = "shift", special = true, selected = shifted, onClick = { shifted = !shifted })
                    "zxcvbnm".forEachIndexed { i, c -> keyFace(if (shifted) c.uppercase() else "$c",
                        hint = if (hints == "all") listOf("-", "_", "/", ":", ";", "!", "?")[i] else "") }
                    keyFace("退格", weight = 1.3f, symbol = "backspace", special = true)
                }
                Row(Modifier.fillMaxWidth().height((48 * scale).dp)) {
                    keyFace("?123", weight = 17f, special = true)
                    keyFace("，。", weight = 9f, special = true)
                    keyFace("五笔", weight = 42f)
                    keyFace("语言", weight = 9f, symbol = "globe", special = true)
                    keyFace("回车", weight = 17f, symbol = "enter", enter = true)
                }
            }
        }
    }
}

/** Preview drafts never write to SharedPreferences. */
internal fun SettingsStore.ThemeTokens.previewColor(id: String, color: String): SettingsStore.ThemeTokens = when (id) {
    "keyboardBackground" -> copy(keyboardBackground = color)
    "normalKeyBackground" -> copy(normalKeyBackground = color)
    "specialKeyBackground" -> copy(specialKeyBackground = color)
    "text" -> copy(text = color)
    "hint" -> copy(hint = color)
    "toolbarIcon" -> copy(toolbarIcon = color)
    "candidate" -> copy(candidate = color)
    "candidateSelected" -> copy(candidateSelected = color)
    "accent" -> copy(accent = color)
    "enterText" -> copy(enterText = color)
    "preedit" -> copy(preedit = color)
    "divider" -> copy(divider = color)
    "specialKeyText" -> copy(specialKeyText = color)
    "selectedKeyBackground" -> copy(selectedKeyBackground = color)
    "selectedKeyText" -> copy(selectedKeyText = color)
    else -> this
}
