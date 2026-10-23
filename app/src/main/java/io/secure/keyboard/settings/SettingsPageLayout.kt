package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val SettingsCardVerticalPadding = 12.dp
internal val SettingsSectionSpacing = 16.dp

/** Section owns its title/card spacing; lazy lists may supply the outer gap themselves. */
@Composable
internal fun SettingsSection(title: String, spacedAfter: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (spacedAfter) SettingsSectionSpacing else 0.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = SettingsCardVerticalPadding), content = content)
        }
    }
}

@Composable
internal fun HorizontalChoices(options: List<Pair<String, String>>, selectedId: String, modifier: Modifier = Modifier.padding(12.dp), onSelect: (String) -> Unit) {
        Row(
            modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (id, label) ->
                val selected = selectedId == id
                Box(
                    Modifier.weight(if (label.length > 4) 1.5f else 1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                        .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton) {
                            onSelect(id)
                        }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 14.sp, textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
