package io.secure.keyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyboardIcons
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

@Composable
internal fun ToolbarEditor(settings: SettingsStore, modifier: Modifier, name: (String) -> String) {
    var order by remember { mutableStateOf(settings.toolOrder) }
    var pins by remember { mutableStateOf(settings.toolbarPins.toSet()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    val state = rememberLazyListState()
    fun reorder() {
        val id = dragging ?: return
        val visible = state.layoutInfo.visibleItemsInfo
        // A reorder schedules a new layout. Never reorder again using stale indices.
        if (visible.any { order.getOrNull(it.index) != it.key }) return
        val item = visible.firstOrNull { it.key == id } ?: return
        val center = dragTop + item.size / 2f
        val from = order.indexOf(id)
        val target = visible.firstOrNull {
            it.key != id && if (it.index > from)
                center > it.offset + it.size / 2f && center < it.offset + it.size
            else center < it.offset + it.size / 2f && center > it.offset
        } ?: return
        if (from >= 0) {
            // Preserve the viewport instead of following the first visible item's moving key.
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            order = order.toMutableList().apply { add(target.index, removeAt(from)) }
        }
    }
    fun finish() { settings.toolOrder = order; dragging = null }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == dragging }
            if (row != null) {
                val edge = row.size.toFloat()
                val speed = when {
                    dragTop < info.viewportStartOffset + edge / 2 -> -12f
                    dragTop + row.size > info.viewportEndOffset - edge / 2 -> 12f
                    else -> 0f
                }
                if (speed != 0f) { state.scrollBy(speed); reorder() }
            }
            delay(16)
        }
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            itemsIndexed(order, key = { _, id -> id }) { index, id ->
                val active = dragging == id
                val offset = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: 0
                Row(Modifier.fillMaxWidth().height(68.dp).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) dragTop - offset else 0f; shadowElevation = if (active) 8.dp.toPx() else 0f }
                    .background(if (active) MiuixTheme.colorScheme.primary.copy(alpha = .16f) else MiuixTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 4.dp).semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("上移${name(id)}") {
                                if (index == 0) false else { order = order.toMutableList().apply { add(index-1,removeAt(index)) }; settings.toolOrder = order; true }
                            },
                            CustomAccessibilityAction("下移${name(id)}") {
                                if (index == order.lastIndex) false else { order = order.toMutableList().apply { add(index+1,removeAt(index)) }; settings.toolOrder = order; true }
                            })
                    }, verticalAlignment = Alignment.CenterVertically) {
                    if (id == "script" || id == "input_mode") {
                        Text(if (id == "script") "繁" else settings.chineseInputMode.glyph, modifier = Modifier.width(24.dp))
                    } else Icon(painterResource(KeyboardIcons.resource(id)), null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurface)
                    Text(name(id), modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Switch(checked = id in pins, onCheckedChange = { checked ->
                        pins = if (checked) pins + id else pins - id
                        settings.toolOrder = order
                        settings.toolbarPins = order.filter { it in pins }
                    }, modifier = Modifier.semantics { contentDescription = "在工具栏显示${name(id)}" })
                    Box(Modifier.size(48.dp).semantics { contentDescription = "拖动排序：${name(id)}" }
                        .pointerInput(id) {
                            detectDragGestures(onDragStart = {
                                state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { item ->
                                    dragTop = item.offset.toFloat(); dragging = id
                                }
                            }, onDragEnd = { finish() }, onDragCancel = { dragging = null; order = settings.toolOrder },
                                onDrag = { change, amount -> change.consume(); dragTop += amount.y; reorder() })
                        }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(KeyboardIcons.resource("drag_handle")), null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
            }
        }
    }
}
