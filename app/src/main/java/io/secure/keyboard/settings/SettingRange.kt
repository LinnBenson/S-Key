package io.secure.keyboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.secure.keyboard.ui.KeyboardIcons
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun SettingRange(title: String, value: Int, minimum: Int, maximum: Int, unit: String,
    step: Int = 1, reset: (() -> Unit)? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp), save: (Int) -> Unit) {
    val current = value.coerceIn(minimum, maximum)
    val onSave by rememberUpdatedState(save)
    val colors = MiuixTheme.colorScheme
    fun snap(raw: Int) = (minimum + ((raw - minimum).toFloat() / step).roundToInt() * step).coerceIn(minimum, maximum)
    Column(Modifier.fillMaxWidth().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            Spacer(Modifier.width(8.dp))
            Text("$current $unit", color = colors.onSurfaceSecondary)
            Spacer(Modifier.weight(1f))
            if (reset != null) IconButton(onClick = reset) {
                Icon(painterResource(KeyboardIcons.resource("undo")), "恢复默认：$title", modifier = Modifier.size(20.dp), tint = colors.onSurfaceSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(modifier = Modifier.size(48.dp), enabled = current > minimum,
                onClick = { onSave((current - step).coerceAtLeast(minimum)) }) {
                Icon(painterResource(KeyboardIcons.resource("previous")), "减小$title", modifier = Modifier.size(20.dp),
                    tint = if (current > minimum) colors.primary else colors.onSurface.copy(alpha = .25f))
            }
            Slider(progress = current.toFloat(), minValue = minimum.toFloat(), maxValue = maximum.toFloat(),
                decimalPlaces = 0, onProgressChange = { onSave(snap(it.roundToInt())) },
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), minimum.toFloat()..maximum.toFloat(), ((maximum-minimum)/step-1).coerceAtLeast(0))
                    setProgress { onSave(snap(it.roundToInt())); true }
                })
            IconButton(modifier = Modifier.size(48.dp), enabled = current < maximum,
                onClick = { onSave((current + step).coerceAtMost(maximum)) }) {
                Icon(painterResource(KeyboardIcons.resource("forward")), "增大$title", modifier = Modifier.size(20.dp),
                    tint = if (current < maximum) colors.primary else colors.onSurface.copy(alpha = .25f))
            }
        }
    }
}
