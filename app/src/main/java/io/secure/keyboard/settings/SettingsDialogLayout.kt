package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Shared width and screen-height budget for settings editing dialogs. */
@Composable
internal fun settingsDialogModifier(): Modifier = Modifier
    .widthIn(max = 600.dp).fillMaxWidth().padding(20.dp)
    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)

/** Shared interior, including IME handling, for every Compose settings dialog. */
@Composable
internal fun settingsDialogContentModifier(): Modifier = Modifier
    .imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp)

internal val SettingsDialogSpacing = 14.dp
internal val SettingsActionSpacing = 12.dp
internal val SettingsPagePadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp)

@Composable
internal fun SettingsDialogActions(cancel: () -> Unit, confirm: () -> Unit, label: String = "保存", enabled: Boolean = true, cancelEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SettingsActionSpacing)) {
        top.yukonga.miuix.kmp.basic.TextButton(text = "取消", onClick = cancel, enabled = cancelEnabled, modifier = Modifier.weight(1f))
        top.yukonga.miuix.kmp.basic.Button(onClick = confirm, enabled = enabled, modifier = Modifier.weight(1f),
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()) {
            top.yukonga.miuix.kmp.basic.Text(label, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary)
        }
    }
}
