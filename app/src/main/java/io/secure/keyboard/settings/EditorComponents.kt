package io.secure.keyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.secure.keyboard.ui.KeyboardIcons
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun EditorIcon(name: String, description: String? = null) {
    Icon(painterResource(KeyboardIcons.resource(name)), description,
        modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable internal fun EditorBadge(text: String, compact: Boolean = false) {
    Box(Modifier.size(52.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .04f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center) { Text(text, fontSize = if (compact) 22.sp else 28.sp, maxLines = 1) }
}
