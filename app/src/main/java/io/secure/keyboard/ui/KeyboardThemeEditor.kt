package io.secure.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.settings.ColorPickerState
import io.secure.keyboard.settings.ColorPickerView
import io.secure.keyboard.settings.SettingsDialogs
import io.secure.keyboard.settings.SettingsInputs
import io.secure.keyboard.settings.SettingsPalette
import io.secure.keyboard.settings.SettingsScreen
import kotlin.math.pow
import kotlin.math.roundToInt

/** A fixed-light companion page. Only an explicit Save changes one keyboard color token. */
internal class KeyboardThemeEditor(
    private val activity: Activity,
    private val store: SettingsStore,
    private val ui: SettingsScreen,
    private val dark: Boolean,
) {
    private val labels = linkedMapOf(
        "keyboardBackground" to "键盘背景", "normalKeyBackground" to "普通按键背景",
        "specialKeyBackground" to "功能按键背景", "text" to "按键文字", "hint" to "右上角角标",
        "toolbarIcon" to "工具栏图标", "candidate" to "普通候选文字", "candidateSelected" to "首选候选文字",
        "accent" to "强调色与回车背景", "enterText" to "回车图标", "preedit" to "待输入编码",
        "divider" to "分隔线", "specialKeyText" to "功能键文字", "selectedKeyBackground" to "选中按键背景",
        "selectedKeyText" to "选中按键文字",
    )
    private val groups = linkedMapOf(
        "键盘与按键底色" to listOf("keyboardBackground", "normalKeyBackground", "specialKeyBackground", "divider"),
        "文字与候选" to listOf("text", "hint", "toolbarIcon", "candidate", "candidateSelected", "preedit"),
        "功能与选中状态" to listOf("accent", "enterText", "specialKeyText", "selectedKeyBackground", "selectedKeyText"),
    )
    private var container: LinearLayout? = null
    private var pagePreview: PalettePreview? = null
    private var pageAdvice: TextView? = null
    private val tokenRows = linkedMapOf<String, Pair<View, Button>>()
    private val pageActions = mutableListOf<View>()
    private var activeDialog: AlertDialog? = null
    private var picker: PickerSession? = null
    private var resetOpen = false

    fun show(parent: LinearLayout, initialDraft: Bundle? = null) {
        close()
        container = ui.column().also { parent.addView(it) }
        render()
        if (initialDraft?.getBoolean("dark") == dark) {
            when (initialDraft.getString("kind")) {
                "color" -> {
                    val id = initialDraft.getString("token")
                    if (id != null && id in labels) edit(id, initialDraft)
                }
                "reset" -> confirmReset()
            }
        }
    }

    /** Only primitive values are returned. No View/Dialog/Activity enters saved instance state. */
    fun saveDraft(): Bundle? {
        val session = picker
        if (session != null) return session.save().apply { putBoolean("dark", dark); putString("kind", "color") }
        return if (resetOpen) Bundle().apply { putBoolean("dark", dark); putString("kind", "reset") } else null
    }

    /** Allows the Activity Back dispatcher to cancel a dialog before navigating. */
    fun dismissDialog(): Boolean {
        if (activeDialog == null) return false
        dismissActive()
        return true
    }

    fun close() {
        dismissActive()
        pageActions.forEach { it.setOnClickListener(null) }
        pageActions.clear()
        tokenRows.clear()
        pagePreview = null
        pageAdvice = null
        container?.let { (it.parent as? LinearLayout)?.removeView(it) }
        container = null
    }

    private fun render() {
        val body = container ?: return
        pageActions.forEach { it.setOnClickListener(null) }
        pageActions.clear()
        tokenRows.clear()
        body.removeAllViews()
        val values = store.themeTokens(dark).asMap()
        val overview = ui.card(body)
        ui.text(overview, "${if (dark) "深色" else "浅色"}键盘配色", 17f, true)
        ui.text(overview, "点选颜色项目后拖动调色，或输入精确 HEX。预览仅在本页变化，点“保存”后才用于键盘。", 14f)
        pagePreview = PalettePreview(activity, values).also {
            overview.addView(it, LinearLayout.LayoutParams(-1, ui.dp(222)).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        }
        pageAdvice = ui.text(overview, contrastAdvice(values), 13f)
        ui.text(overview, "浅色和深色配色独立保存，不改变当前键盘主题模式。清空用户内容不会清除配色。", 13f)
        groups.forEach { (heading, ids) ->
            val card = ui.card(body)
            ui.text(card, heading, 17f, true)
            ids.forEach { id ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                card.addView(row, LinearLayout.LayoutParams(-1, -2))
                val swatch = View(activity).apply {
                    background = SettingsPalette.shape(context, Color.parseColor(values.getValue(id)), 10, SettingsPalette.outline)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                row.addView(swatch, LinearLayout.LayoutParams(ui.dp(32), ui.dp(40)).apply { marginEnd = ui.dp(12) })
                val button = ui.button(row, "${labels.getValue(id)}\n${values.getValue(id)}") { edit(id) }.apply {
                    tag = "theme_token_$id"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }
                    contentDescription = "${labels.getValue(id)}，${values.getValue(id)}，编辑颜色"
                }
                tokenRows[id] = swatch to button
                pageActions.add(button)
            }
        }
        pageActions.add(ui.button(body, "恢复${if (dark) "深色" else "浅色"}默认配色") { confirmReset() })
    }

    /** A single token update never tears down the page or loses its scroll position. */
    private fun updatePage() {
        val values = store.themeTokens(dark).asMap()
        pagePreview?.colors = values
        pageAdvice?.text = contrastAdvice(values)
        tokenRows.forEach { (id, views) ->
            views.first.background = SettingsPalette.shape(activity, Color.parseColor(values.getValue(id)), 10, SettingsPalette.outline)
            views.second.text = "${labels.getValue(id)}\n${values.getValue(id)}"
            views.second.contentDescription = "${labels.getValue(id)}，${values.getValue(id)}，编辑颜色"
        }
    }

    private fun confirmReset() {
        dismissActive()
        resetOpen = true
        val dialog = SettingsDialogs.builder(activity)
            .setTitle("恢复默认配色？")
            .setMessage("只恢复${if (dark) "深色" else "浅色"}键盘的颜色，另一套配色和其他设置不变。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复", null)
            .create()
        activeDialog = dialog
        dialog.setOnDismissListener { releaseDialog(dialog) }
        SettingsDialogs.show(dialog) {
            it.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                store.resetThemeTokens(dark)
                updatePage()
                dialog.dismiss()
            }
        }
    }

    private fun edit(id: String, restored: Bundle? = null) {
        dismissActive()
        val existing = store.themeTokens(dark).asMap()
        val state = restored?.let {
            ColorPickerState.restore(ColorPickerState.Snapshot(
                it.getString("original").orEmpty(), it.getString("pending").orEmpty(), it.getString("raw").orEmpty(),
                it.getFloat("hue"), it.getFloat("saturation"), it.getFloat("value"),
            ))
        } ?: ColorPickerState(existing.getValue(id))
        val dialogContext = SettingsDialogs.builder(activity).context
        val group = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12))
            isFocusableInTouchMode = true
        }
        val session = PickerSession(id, state, group, existing)
        picker = session
        session.build(restored)
        val scroll = SettingsDialogs.scrollable(dialogContext, group)
        session.scroll = scroll
        val dialog = SettingsDialogs.builder(activity)
            .setTitle(labels.getValue(id))
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        activeDialog = dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener { releaseDialog(dialog) }
        SettingsDialogs.show(dialog) {
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            it.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hex = state.confirmedHex()
                if (hex == null) SettingsInputs.error(session.input, "请输入不透明颜色：# 加 6 位十六进制数字")
                else try {
                    // Read-modify-write one role through the existing store path; other roles stay current.
                    store.setThemeToken(dark, id, hex)
                    updatePage()
                    dialog.dismiss()
                    tokenRows[id]?.second?.announceForAccessibility("${labels.getValue(id)}已保存，$hex")
                } catch (error: IllegalArgumentException) {
                    SettingsInputs.error(session.input, error.message ?: "颜色格式无效")
                }
            }
            session.restorePosition(restored)
        }
    }

    private fun dismissActive() {
        val dialog = activeDialog ?: return
        releaseDialog(dialog)
        dialog.dismiss()
    }

    private fun releaseDialog(dialog: AlertDialog) {
        dialog.setOnDismissListener(null)
        dialog.setOnShowListener(null)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
            dialog.getButton(it)?.setOnClickListener(null)
        }
        if (activeDialog === dialog) {
            picker?.close()
            picker = null
            activeDialog = null
            resetOpen = false
        }
    }

    private inner class PickerSession(
        val id: String,
        val state: ColorPickerState,
        private val group: LinearLayout,
        private val originalPalette: Map<String, String>,
    ) {
        lateinit var input: EditText
        var scroll: ScrollView? = null
        private lateinit var plane: ColorPickerView
        private lateinit var hue: ColorPickerView
        private lateinit var preview: PalettePreview
        private lateinit var advice: TextView
        private lateinit var newLabel: TextView
        private lateinit var newSwatch: View
        private lateinit var details: LinearLayout
        private lateinit var expand: Button
        private lateinit var watcher: TextWatcher
        private val sliders = mutableListOf<Pair<SeekBar, TextView>>()
        private var syncing = false
        private var restoreRunnable: Runnable? = null

        fun build(restored: Bundle?) {
            // Labels are outside the colored swatches and remain readable for every chosen color.
            val swatches = LinearLayout(group.context).apply { orientation = LinearLayout.HORIZONTAL }
            group.addView(swatches, LinearLayout.LayoutParams(-1, -2))
            fun swatch(label: String, color: Int): Pair<View, TextView> {
                val column = LinearLayout(group.context).apply { orientation = LinearLayout.VERTICAL }
                swatches.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
                val text = text(column, label, 13f)
                val sample = View(group.context).apply {
                    background = SettingsPalette.shape(context, color, 10, SettingsPalette.outline)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                column.addView(sample, LinearLayout.LayoutParams(-1, ui.dp(32)))
                return sample to text
            }
            swatch("原色\n${state.originalHex}", state.originalColor)
            val next = swatch("新颜色\n${state.pendingHex}", state.pendingColor)
            newSwatch = next.first
            newLabel = next.second
            text(group, "饱和度与明度", 14f)
            plane = ColorPickerView(group.context, ColorPickerView.Component.SATURATION_VALUE).apply { tag = "color_sv" }
            val planeHeight = if (group.resources.configuration.screenHeightDp < 480) 160 else 204
            group.addView(plane, LinearLayout.LayoutParams(-1, ui.dp(planeHeight)))
            text(group, "色相", 14f)
            hue = ColorPickerView(group.context, ColorPickerView.Component.HUE).apply { tag = "color_hue" }
            group.addView(hue, LinearLayout.LayoutParams(-1, ui.dp(56)))
            input = SettingsInputs.field(group.context, "#RRGGBB，例如 #336699", true).apply {
                tag = "color_hex"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_FORCE_ASCII
                filters = arrayOf(InputFilter.LengthFilter(9))
                setText(state.rawHex)
                textDirection = View.TEXT_DIRECTION_LTR
            }
            SettingsInputs.labeled(group, "HEX · #RRGGBB", input)
            expand = Button(group.context).apply {
                tag = "color_precision"
                SettingsPalette.styleButton(this)
            }
            group.addView(expand, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            details = LinearLayout(group.context).apply { orientation = LinearLayout.VERTICAL }
            group.addView(details)
            addSlider("色相", 359) { progress -> state.chooseHue(progress.toFloat()) }
            addSlider("饱和度", 100) { progress -> state.chooseSaturationValue(progress / 100f, state.value) }
            addSlider("明度", 100) { progress -> state.chooseSaturationValue(state.saturation, progress / 100f) }
            setExpanded(restored?.getBoolean("expanded") == true)
            expand.setOnClickListener { setExpanded(details.visibility != View.VISIBLE) }
            text(group, "键盘实时预览", 14f)
            preview = PalettePreview(activity, originalPalette).apply { tag = "color_preview" }
            group.addView(preview, LinearLayout.LayoutParams(-1, ui.dp(174)))
            advice = text(group, "", 13f)
            plane.onChange = { h, s, v, _ -> state.chooseHsv(h, s, v); sync(true) }
            hue.onChange = { h, _, _, _ -> state.chooseHue(h); sync(true) }
            watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!syncing && state.typeHex(s?.toString().orEmpty())) sync(false)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            input.addTextChangedListener(watcher)
            sync(false)
            group.requestFocus()
        }

        private fun addSlider(label: String, maximum: Int, changed: (Int) -> Unit) {
            val readout = text(details, label, 14f)
            val slider = SeekBar(group.context).apply {
                tag = when (label) { "色相" -> "color_hue_exact"; "饱和度" -> "color_saturation_exact"; else -> "color_value_exact" }
                this.id = View.generateViewId()
                max = maximum
                keyProgressIncrement = 1
                SettingsPalette.styleSlider(this)
            }
            readout.labelFor = slider.id
            details.addView(slider, LinearLayout.LayoutParams(-1, ui.dp(48)))
            sliders.add(slider to readout)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncing) { changed(progress); sync(true) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        private fun setExpanded(expanded: Boolean) {
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            expand.text = if (expanded) "收起精确调整" else "精确调整 · 色相、饱和度、明度"
            expand.contentDescription = "${expand.text}，${if (expanded) "已展开" else "已收起"}"
        }

        private fun sync(updateInput: Boolean) {
            syncing = true
            try {
                if (updateInput) {
                    input.setText(state.rawHex)
                    input.setSelection(input.length())
                }
                if (state.isValid) SettingsInputs.error(input, null)
                plane.setSelection(state.hue, state.saturation, state.value)
                hue.setSelection(state.hue, state.saturation, state.value)
                val progress = listOf(state.hue.roundToInt().coerceIn(0, 359), (state.saturation * 100).roundToInt(), (state.value * 100).roundToInt())
                val names = listOf("色相", "饱和度", "明度")
                sliders.forEachIndexed { index, (slider, readout) ->
                    slider.progress = progress[index]
                    val suffix = if (index == 0) "°" else "%"
                    readout.text = "${names[index]} ${progress[index]}$suffix"
                    slider.contentDescription = names[index]
                }
                newLabel.text = "新颜色\n${state.pendingHex}"
                newSwatch.background = SettingsPalette.shape(group.context, state.pendingColor, 10, SettingsPalette.outline)
                val palette = originalPalette + (id to state.pendingHex)
                preview.colors = palette
                advice.text = contrastAdvice(palette)
            } finally { syncing = false }
        }

        fun save(): Bundle = Bundle().apply {
            val snapshot = state.snapshot()
            putString("token", id)
            putString("original", snapshot.originalHex)
            putString("pending", snapshot.pendingHex)
            putString("raw", snapshot.rawHex)
            putFloat("hue", snapshot.hue)
            putFloat("saturation", snapshot.saturation)
            putFloat("value", snapshot.value)
            putBoolean("expanded", details.visibility == View.VISIBLE)
            putBoolean("focus", input.hasFocus())
            putInt("selectionStart", input.selectionStart)
            putInt("selectionEnd", input.selectionEnd)
            putInt("scroll", scroll?.scrollY ?: 0)
        }

        fun restorePosition(restored: Bundle?) {
            if (restored == null) return
            restoreRunnable = Runnable {
                if (restored.getBoolean("focus")) {
                    input.requestFocus()
                    input.setSelection(restored.getInt("selectionStart").coerceIn(0, input.length()), restored.getInt("selectionEnd").coerceIn(0, input.length()))
                }
                scroll?.scrollTo(0, restored.getInt("scroll").coerceAtLeast(0))
                restoreRunnable = null
            }.also { scroll?.post(it) }
        }

        fun close() {
            restoreRunnable?.let { scroll?.removeCallbacks(it) }
            restoreRunnable = null
            input.removeTextChangedListener(watcher)
            input.onFocusChangeListener = null
            input.setOnEditorActionListener(null)
            plane.close()
            hue.close()
            expand.setOnClickListener(null)
            sliders.forEach { it.first.setOnSeekBarChangeListener(null) }
            sliders.clear()
            scroll = null
        }

        private fun text(parent: LinearLayout, value: String, size: Float): TextView = TextView(parent.context).apply {
            text = value
            textSize = size
            setTextColor(SettingsPalette.ink)
            setPadding(0, ui.dp(8), 0, ui.dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun contrastAdvice(values: Map<String, String>): String {
        val pairs = listOf(
            Triple("按键文字", "text", "normalKeyBackground"), Triple("功能键文字", "specialKeyText", "specialKeyBackground"),
            Triple("选中按键文字", "selectedKeyText", "selectedKeyBackground"), Triple("待输入编码", "preedit", "keyboardBackground"),
            Triple("角标", "hint", "normalKeyBackground"), Triple("工具栏", "toolbarIcon", "keyboardBackground"),
            Triple("候选文字", "candidate", "keyboardBackground"), Triple("首选候选", "candidateSelected", "keyboardBackground"),
            Triple("回车图标", "enterText", "accent"),
        )
        val low = pairs.filter { (_, fg, bg) -> contrast(Color.parseColor(values.getValue(fg)), Color.parseColor(values.getValue(bg))) < 3.0 }.map { it.first }
        return if (low.isEmpty()) "预览会随有效颜色实时更新；保存后用于键盘。"
        else "可读性提醒：${low.joinToString("、")}对比度偏低，可能不易辨认。仍可保存此配色。"
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(component: Int): Double {
                val value = component / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
        }
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}

/** Small non-interactive preview showing all editable color roles, never an input collector. */
private class PalettePreview(activity: Activity, initial: Map<String, String>) : View(activity) {
    var colors = initial.toMap()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "键盘配色预览：工具栏、候选文字、按键角标、普通键、功能键与回车"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val sx = w / 340f
        val sy = h / 222f
        canvas.save()
        canvas.scale(sx, sy)
        fun color(id: String) = Color.parseColor(colors.getValue(id))
        fun rectangle(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            id: String,
            radius: Float = 8f,
        ) {
            paint.color = color(id)
            canvas.drawRoundRect(RectF(x, y, x + width, y + height), radius, radius, paint)
        }
        fun text(value: String, x: Float, y: Float, size: Float, id: String) {
            paint.color = color(id)
            paint.textSize = size
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(value, x, y, paint)
        }
        rectangle(0f, 0f, 340f, 222f, "keyboardBackground", 16f)
        text("☷    ▤    ☺", 84f, 25f, 20f, "toolbarIcon")
        text("⌄", 312f, 24f, 22f, "toolbarIcon")
        text("nihao", 240f, 24f, 14f, "preedit")
        text("你好", 40f, 52f, 17f, "candidateSelected")
        text("你号     拟好", 130f, 52f, 16f, "candidate")
        rectangle(12f, 58f, 316f, 1f, "divider", 0f)
        listOf("qwertyuiop", "asdfghjkl").forEachIndexed { row, letters ->
            val gap = 4f
            val keyWidth = 28f
            val start = (340f - (letters.length * (keyWidth + gap) - gap)) / 2
            letters.forEachIndexed { index, letter ->
                val x = start + index * (keyWidth + gap)
                val y = 64f + row * 49f
                val selected = row == 1 && index == 0
                rectangle(
                    x,
                    y,
                    keyWidth,
                    44f,
                    if (selected) "selectedKeyBackground" else "normalKeyBackground",
                )
                text(
                    letter.toString(),
                    x + 14f,
                    y + 31f,
                    20f,
                    if (selected) "selectedKeyText" else "text",
                )
                text(
                    if (row == 0) ((index + 1) % 10).toString() else "·",
                    x + 23f,
                    y + 11f,
                    8f,
                    "hint",
                )
            }
        }
        rectangle(10f, 166f, 44f, 43f, "specialKeyBackground")
        text("?123", 32f, 193f, 13f, "specialKeyText")
        rectangle(60f, 166f, 210f, 43f, "normalKeyBackground")
        text("五笔 · 拼音", 165f, 193f, 15f, "text")
        rectangle(276f, 166f, 54f, 43f, "accent")
        text("↵", 303f, 195f, 23f, "enterText")
        canvas.restore()
    }
}
