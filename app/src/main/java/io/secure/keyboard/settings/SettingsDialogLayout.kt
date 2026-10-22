package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Shared width and screen-height budget for settings editing dialogs. */
@Composable
internal fun settingsDialogModifier(): Modifier = Modifier
    .widthIn(max = 600.dp).fillMaxWidth().padding(20.dp)
    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
