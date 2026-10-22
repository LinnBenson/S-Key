package io.secure.keyboard.settings

import android.app.Activity
import android.content.ClipData
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.ToolIcon

/** Drag changes only order; add/remove are explicit, independent actions. */
internal class ToolbarEditor(
    private val activity: Activity,
    private val store: SettingsStore,
    private val ui: SettingsScreen,
    private val name: (String) -> String,
) {
    private var dialog: android.app.AlertDialog? = null
    fun close() { dialog?.dismiss(); dialog = null }
    private lateinit var body: LinearLayout
    fun show(parent: LinearLayout) {
        body = ui.column()
        parent.addView(body)
        render()
    }
    private fun icon(id: String) = ToolIcon(activity, when (id) {
        "floating" -> "floating_enter"
        else -> id
    }, SettingsPalette.ink, artworkSizeDp = 23f).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun control(label: String, description: String, action: () -> Unit) = Button(activity).apply {
        text = label; contentDescription = description
        SettingsPalette.styleButton(this)
        textSize = 20f
        minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }
    private fun move(id: String, target: Int) {
        val pins = store.toolbarPins.toMutableList()
        if (!pins.remove(id)) return
        pins.add(target.coerceIn(0, pins.size), id)
        store.toolbarPins = pins
        render()
    }
    private fun render() {
        body.removeAllViews()
        val pins = store.toolbarPins
        val preview = ui.card(body)
        ui.text(preview, "你的工具栏", 18f, true)
        ui.text(preview, "自动保存", 12f)
        val strip = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(SettingsPalette.functional, 18)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
        preview.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        (listOf("more") + pins + "hide").forEach { id ->
            strip.addView(icon(id).apply { contentDescription = if (id == "more") "更多（固定）" else if (id == "hide") "收起（固定）" else name(id)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)))
        }
        ui.text(preview, "「更多」与「收起」始终保留。", 12f)
        val selected = ui.card(body)
        ui.text(selected, "已添加 · ${pins.size} 项", 17f, true)
        ui.text(selected, "长按 ≡ 拖动排序，点按选择位置。", 12f)
        if (pins.isEmpty()) ui.text(selected, "工具栏还没有快捷项，从下方添加。", 14f)
        pins.forEachIndexed { index, id ->
            val row = LinearLayout(activity).apply {
                tag = "anchor:tool:$id"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8))
                background = ui.shape(SettingsPalette.surface, 16)
            }
            selected.addView(row, LinearLayout.LayoutParams(-1, -2))
            row.addView(icon(id), LinearLayout.LayoutParams(ui.dp(40), ui.dp(48)))
            val title = ui.column()
            ui.text(title, name(id), 15f, true)
            ui.text(title, "第 ${index + 1} 位", 11f)
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
            row.addView(control("−", "移除${name(id)}") {
                store.toolbarPins = store.toolbarPins - id; render()
            }.apply { tag = "toolbar_remove_$id" }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            val handle = control("≡", "调整${name(id)}的位置") {
                val entries = pins.mapIndexed { i, other -> "第 ${i + 1} 位 · ${name(other)}" }
                close()
                dialog = SettingsDialogs.builder(activity).setTitle("移动${name(id)}")
                    .setSingleChoiceItems(entries.toTypedArray(), index) { dialog, which ->
                        dialog.dismiss(); move(id, which)
                    }.setNegativeButton("取消", null).create().also { owned ->
                        owned.setOnDismissListener { if (dialog === owned) dialog = null }
                        SettingsDialogs.show(owned)
                    }
            }.apply {
                tag = "toolbar_drag_$id"
                background = null
                setOnLongClickListener {
                    row.startDragAndDrop(ClipData.newPlainText("toolbar", id), View.DragShadowBuilder(row), id, 0)
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000001, "向前移动"))
                        if (index < pins.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000002, "向后移动"))
                    }
                    override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                        when (action) {
                            0x01000001 -> { move(id, index - 1); return true }
                            0x01000002 -> { move(id, index + 1); return true }
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            row.addView(handle, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            row.setOnDragListener { _, event ->
                val dragged = event.localState as? String
                if (dragged !in pins) return@setOnDragListener false
                when (event.action) {
                    DragEvent.ACTION_DRAG_ENTERED -> row.background = ui.shape(SettingsPalette.functional, 16)
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> row.background = ui.shape(SettingsPalette.surface, 16)
                    DragEvent.ACTION_DROP -> {
                        if (dragged != null && dragged != id) move(dragged, index)
                    }
                }
                true
            }
        }
        val available = SettingsStore.availablePins.filter { it !in pins }
        if (available.isNotEmpty()) {
            val choices = ui.card(body)
            ui.text(choices, "添加快捷工具", 17f, true)
            ui.text(choices, "其他工具可在“更多”中找到。", 12f)
            available.forEach { id ->
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; tag = "anchor:tool:$id" }
                choices.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
                row.addView(icon(id), LinearLayout.LayoutParams(ui.dp(40), ui.dp(48)))
                ui.text(row, name(id), 15f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) }
                row.addView(control("+", "添加${name(id)}") {
                    store.toolbarPins = store.toolbarPins + id; render()
                }.apply { tag = "toolbar_add_$id" }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            }
        }
    }
}
