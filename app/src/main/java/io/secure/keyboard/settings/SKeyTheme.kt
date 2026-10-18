package io.secure.keyboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.secure.keyboard.data.SettingsStore
import top.yukonga.miuix.kmp.theme.*

internal fun hexColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

@Composable
internal fun SKeyTheme(tokens: SettingsStore.ThemeTokens, dark: Boolean, content: @Composable () -> Unit) {
    val p = hexColor(tokens.accent)
    val onP = hexColor(tokens.enterText)
    val surface = hexColor(tokens.normalKeyBackground)
    val secondary = hexColor(tokens.specialKeyBackground)
    val text = hexColor(tokens.text)
    val muted = hexColor(tokens.hint)
    val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p, onPrimary = onP, primaryVariant = p, onPrimaryVariant = onP,
        primaryContainer = p, onPrimaryContainer = onP,
        disabledPrimary = p.copy(alpha = .35f), disabledOnPrimary = onP.copy(alpha = .5f),
        disabledPrimaryButton = p.copy(alpha = .35f), disabledOnPrimaryButton = onP.copy(alpha = .5f),
        disabledPrimarySlider = p.copy(alpha = .35f),
        background = hexColor(tokens.keyboardBackground), onBackground = text, onBackgroundVariant = muted,
        surface = surface, onSurface = text, surfaceVariant = secondary,
        onSurfaceSecondary = muted, onSurfaceVariantSummary = muted, onSurfaceVariantActions = text,
        secondary = secondary, onSecondary = text, secondaryVariant = secondary, onSecondaryVariant = text,
        secondaryContainer = secondary, onSecondaryContainer = text,
        secondaryContainerVariant = secondary, onSecondaryContainerVariant = text,
        tertiaryContainer = secondary, onTertiaryContainer = text, tertiaryContainerVariant = secondary,
        surfaceContainer = surface, onSurfaceContainer = text, onSurfaceContainerVariant = muted,
        surfaceContainerHigh = secondary, onSurfaceContainerHigh = text,
        surfaceContainerHighest = secondary, onSurfaceContainerHighest = text,
        outline = muted, dividerLine = hexColor(tokens.divider), disabledOnSurface = muted,
    )
    MiuixTheme(colors = colors, content = content)
}
