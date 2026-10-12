package io.secure.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.Executors
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.DictionaryTextReader
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.DictionaryEntryRow
import io.secure.keyboard.ui.KeyboardThemeEditor
import io.secure.keyboard.settings.SettingsScreen
import io.secure.keyboard.settings.SettingsPalette
import io.secure.keyboard.settings.SettingsDialogs
import io.secure.keyboard.settings.SettingsInputs
import io.secure.keyboard.settings.SettingsNavigation
import io.secure.keyboard.settings.SettingsEditableDraft
import io.secure.keyboard.settings.SettingsDraftBudget
import io.secure.keyboard.settings.applySettingsActivityTheme

/**
 * Offline settings and manual content management. No analytics, cloud, or background collection.
 */
class SettingsActivity : Activity() {
    private lateinit var config: SettingsStore
    private lateinit var content: ContentStore
    private lateinit var body: LinearLayout
    private lateinit var ui: SettingsScreen
    private var group: LinearLayout? = null
    private val navigation = SettingsNavigation()
    private val contentPageIndices = mutableMapOf<String, Int>()
    private val dictionaryPageIndices = mutableMapOf<DictionaryKind, Int>()
    private var currentDictionaryKind: DictionaryKind? = null
    private lateinit var dictionaryTransfer: DictionaryTransfer
    private lateinit var lifetimeState: SettingsLifetimeState
    private var transferDialog: AlertDialog? = null
    private var editor: KeyboardThemeEditor? = null
    private var restoredEditorDraft: Bundle? = null
    private var toolbarEditor: io.secure.keyboard.settings.ToolbarEditor? = null
    private var activeDialog: AlertDialog? = null
    private var activeDraft: Bundle? = null
    private var draftFields = emptyList<EditText>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private val numberEditors = mutableMapOf<String, (Bundle?) -> Unit>()
    private var pendingLayout: Pair<View, ViewTreeObserver.OnGlobalLayoutListener>? = null
    private var recoveryMessage: String? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        config = SettingsStore(this)
        content = ContentStore(this)
        applySettingsActivityTheme()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        lifetimeState = lastNonConfigurationInstance as? SettingsLifetimeState ?: SettingsLifetimeState()
        dictionaryTransfer = lifetimeState.dictionaryTransfer
        // Consume before render() closes prior UI. The current fields become the only edit owner.
        val retainedDraft = lifetimeState.editableDraft
        lifetimeState.editableDraft = null
        DictionaryKind.values().forEach { kind ->
            dictionaryPageIndices[kind] = savedInstanceState?.getInt("dictionary_page_${kind.name}") ?: 0
        }
        for (route in listOf("clipboard", "phrases")) {
            contentPageIndices[route] = savedInstanceState?.getInt("content_page_$route") ?: 0
        }
        recoveryMessage = savedInstanceState?.getString("recovery_message")
        if (savedInstanceState?.containsKey("settings_route") == true) {
            navigation.restore(savedInstanceState.getString("settings_route"),
                savedInstanceState.getStringArrayList("settings_history").orEmpty())
            savedInstanceState.getBundle("settings_positions")?.let { positions ->
                positions.keySet().filter { it in SettingsNavigation.routes }.forEach { route ->
                    positions.getBundle(route)?.let {
                        navigation.positions[route] = SettingsNavigation.Position(
                            it.getInt("offset").coerceAtLeast(0), it.getString("anchor"), it.getInt("anchor_offset"), it.getString("focus"))
                    }
                }
            }
            navigation.expandedHelp.addAll(savedInstanceState.getStringArrayList("settings_help").orEmpty())
        } else {
            val restoredKind = savedInstanceState?.getString("dictionary_kind")
            navigation.reset(when (restoredKind) {
                DictionaryKind.CHINESE.name -> "dictionary"
                DictionaryKind.ENGLISH.name -> "english_dictionary"
                else -> intent?.getStringExtra("page") ?: "home"
            })
        }
        restoredEditorDraft = savedInstanceState?.getBundle("editor_draft")
        safely { render(navigation.current) }
        val editableDraft = retainedDraft?.toEditorBundle() ?: savedInstanceState?.getBundle("editable_draft")
        if (editableDraft != null) safely { restoreEditableDraft(editableDraft) }
        else if (savedInstanceState?.getBoolean("oversized_editable_draft") == true) {
            showDialog(SettingsDialogs.builder(this)
                .setTitle("未保存的编辑未恢复")
                .setMessage("应用进程结束，较大的未保存编辑无法恢复。已保存内容不受影响。")
                .setPositiveButton("知道了", null).create())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeDialogs()
        toolbarEditor?.close()
        toolbarEditor = null
        editor?.close()
        editor = null
        restoredEditorDraft = null
        if (::ui.isInitialized) ui.dispose()
        cancelLayoutRestore()
        navigation.reset(intent.getStringExtra("page") ?: "home")
        // A fresh destination must not capture the unrelated old page's position.
        if (::body.isInitialized) body.tag = null
        safely { render(navigation.current, fresh = true) }
        showTransferProgress()
    }

    private fun render(route: String, fresh: Boolean = false) {
        if (fresh) suppressPositionCapture = true
        when (route) {
            "clipboard" -> clipboard()
            "phrases" -> phrases()
            "dictionary" -> dictionary(DictionaryKind.CHINESE)
            "english_dictionary" -> dictionary(DictionaryKind.ENGLISH)
            "toolbar" -> toolbar()
            "emoji" -> emoji()
            "layout" -> profiles()
            "behavior" -> behavior()
            "theme_light" -> themeEditor(false)
            "theme_dark" -> themeEditor(true)
            "profile_outer_portrait" -> profile(true, false)
            "profile_outer_landscape" -> profile(true, true)
            "profile_inner_portrait" -> profile(false, false)
            "profile_inner_landscape" -> profile(false, true)
            "recovery" -> recoveryPage()
            else -> home()
        }
    }

    private var suppressPositionCapture = false

    private fun page(route: String, title: String, back: Boolean = true) {
        if (!suppressPositionCapture) rememberPosition()
        suppressPositionCapture = false
        cancelLayoutRestore()
        closeDialogs()
        toolbarEditor?.close()
        toolbarEditor = null
        editor?.close()
        editor = null
        if (::ui.isInitialized) ui.dispose()
        navigation.navigate(route)
        currentDictionaryKind = null
        numberEditors.clear()
        group = null
        this.title = title
        ui = SettingsScreen(this)
        val backLabel = when {
            route.startsWith("profile_") -> "‹ 返回布局配置"
            route.startsWith("theme_") -> "‹ 返回外观与按键"
            else -> "‹ 返回设置"
        }
        body = ui.root(title, if (back) backLabel else null, if (back) ({ navigateBack() }) else null)
        body.tag = route
        val position = navigation.positions[route] ?: SettingsNavigation.Position()
        val targetBody = body
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val observer = targetBody.viewTreeObserver
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                pendingLayout = null
                if (isDestroyed || body !== targetBody || body.tag != route) return
                position.focus?.let { body.findViewWithTag<View>(it)?.requestFocus() }
                val anchor = position.anchor?.let { body.findViewWithTag<View>(it) }
                val target = if (anchor == null) position.offset else topInBody(anchor) + position.anchorOffset
                ui.restoreScroll(target.coerceAtLeast(0))
            }
        }
        pendingLayout = targetBody to listener
        targetBody.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun topInBody(view: View): Int {
        val bounds = Rect()
        view.getDrawingRect(bounds)
        body.offsetDescendantRectToMyCoords(view, bounds)
        return bounds.top
    }

    private fun rememberPosition() {
        if (!::ui.isInitialized || !::body.isInitialized || !body.isLaidOut) return
        val route = body.tag as? String ?: return
        val y = ui.scrollY()
        val candidates = mutableListOf<View>()
        fun visit(view: View) {
            if (view !== body && (view.tag as? String)?.startsWith("anchor:") == true) candidates.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(body)
        val anchor = candidates.firstOrNull { topInBody(it) + it.height > y }
        navigation.positions[route] = SettingsNavigation.Position(y, anchor?.tag as? String,
            if (anchor == null) 0 else y - topInBody(anchor), currentFocus?.tag as? String)
    }

    private fun keepItemVisible(anchor: String) {
        rememberPosition()
        val current = navigation.positions[navigation.current] ?: SettingsNavigation.Position()
        navigation.positions[navigation.current] = current.copy(anchor = anchor, anchorOffset = 0)
        suppressPositionCapture = true
    }

    private fun cancelLayoutRestore() {
        pendingLayout?.let { (view, listener) ->
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
        pendingLayout = null
    }

    private fun navigateBack() {
        if (editor?.dismissDialog() == true) return
        dialogs.lastOrNull { it.isShowing }?.let {
            if (it !== transferDialog) it.cancel()
            return
        }
        val focused = currentFocus
        if (focused is EditText) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
            return
        }
        rememberPosition()
        val destination = navigation.back() ?: run { finish(); return }
        suppressPositionCapture = true
        safely { render(destination) }
    }

    @Deprecated("Uses platform back dispatch for dependency-free API 26 compatibility")
    override fun onBackPressed() = navigateBack()

    private fun target(): LinearLayout = group ?: body

    private fun label(text: String, size: Float = 14f): TextView = ui.text(target(), text, size)

    private fun button(text: String, primary: Boolean = false, action: () -> Unit): Button =
        ui.button(target(), text, primary) { safely(action) }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            tag = "action:${target().tag ?: navigation.current}:$text"
            if (text.startsWith("删除") || text.startsWith("清空")) setTextColor(SettingsPalette.error)
        }

    private fun toggle(text: String, value: Boolean, change: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            tag = "toggle:${target().tag ?: navigation.current}:$text"
            isChecked = value
            textSize = 15f
            setTextColor(ui.ink)
            minHeight = dp(56)
            minimumHeight = dp(56)
            switchPadding = dp(16)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(SettingsPalette.accent, SettingsPalette.outline))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(SettingsPalette.outline, SettingsPalette.functional))
            target().addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked -> safely { change(checked) } }
        }

    private fun number(text: String, value: Int, min: Int, max: Int, save: (Int) -> Unit) {
        var current = value
        val key = "${navigation.current}:$text"
        lateinit var row: Button
        val open: (Bundle?) -> Unit = { draft ->
            val input = SettingsInputs.field(this).apply {
                id = R.id.settings_numeric_input
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                contentDescription = text
            }
            val container = ui.column()
            SettingsInputs.labeled(container, "$text ($min–$max)", input)
            val dialog = SettingsDialogs.builder(this)
                .setTitle(text)
                .setView(SettingsDialogs.scrollable(this, container))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val v = input.text.toString().toIntOrNull()
                    if (v == null || v !in min..max) SettingsInputs.error(input, "请输入 $min–$max 之间的数值")
                    else safely {
                        save(v)
                        current = v
                        row.text = "$text: $v"
                        dialog.dismiss()
                    }
                }
            }
            showEditable(dialog, Bundle().apply { putString("type", "number"); putString("key", key) }, listOf(input), draft)
        }
        row = button("$text: $value") { open(null) }
        row.tag = "anchor:number:$key"
        numberEditors[key] = open
    }

    private fun themeName(value: String) =
        when (value) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }

    private fun toolName(value: String) =
        when (value) {
            "clipboard" -> "剪贴板"
            "phrases" -> "常用语"
            "voice" -> "语音"
            "quickedit" -> "快捷编辑"
            "emoji" -> "表情"
            "layout" -> "布局"
            "floating" -> "悬浮"
            "split" -> "分离"
            "theme" -> "主题"
            "dictionary" -> "中文词库"
            "english_dictionary" -> "英文词库"
            "settings" -> "设置"
            else -> value
        }

    private fun slider(title: String, value: Int, minValue: Int, maxValue: Int, step: Int,
        unit: String, save: (Int) -> Unit) {
        val parent = target()
        val caption = ui.text(parent, "$title：$value $unit", 15f, true)
        parent.addView(SeekBar(this).apply {
            max = (maxValue - minValue) / step
            progress = (value.coerceIn(minValue, maxValue) - minValue) / step
            contentDescription = title
            minimumHeight = dp(48)
            thumbTintList = ColorStateList.valueOf(SettingsPalette.accent)
            progressTintList = ColorStateList.valueOf(SettingsPalette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(SettingsPalette.functional)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = minValue + progress * step
                    caption.text = "$title：$current $unit"
                    if (fromUser) safely { save(current) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun home() {
        intent.removeExtra("page")
        page("home", "键盘设置", false)
        ui.card(body, ui.tint).also {
            ui.text(it, "数据仅保存在本机", 17f, true)
            ui.text(it, "无联网权限，不学习输入内容、不保存输入历史、不自动收集常用语。", 13f)
        }
        section("键盘与输入法")
        button("在 Android 设置中启用键盘") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("切换输入法") {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        section("输入体验")
        button("外观与按键") { behavior() }
        button("布局配置") { profiles() }
        button("工具栏固定项") { toolbar() }
        section("本机内容")
        button("剪贴板（${content.clipboard().size}）") { clipboard() }
        button("常用语（${content.phrases().size}）") { phrases() }
        button("中文词库（${content.dictionary(DictionaryKind.CHINESE).size}）") {
            dictionary(DictionaryKind.CHINESE)
        }
        button("英文词库（${content.dictionary(DictionaryKind.ENGLISH).size}）") {
            dictionary(DictionaryKind.ENGLISH)
        }
        button("最近表情（${content.recentEmoji().size}/50）") { emoji() }
        section("内容清理")
        label("仅删除本机内容，布局、配色与输入设置保留。")
        button("清空全部用户内容") {
            confirm("清空剪贴板、常用语、中文词库、英文词库和最近表情？布局与设置会保留，Android 系统剪贴板和已导出的文件不会被删除。") {
                content.clearAll()
                home()
            }
        }
    }

    private fun section(title: String) {
        group = ui.card(body).apply { tag = "anchor:section:$title" }
        ui.text(requireNotNull(group), title, 17f, true).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    private fun notice(text: String) {
        group = null
        ui.card(body, ui.tint).also { ui.text(it, text, 14f) }
    }

    private fun choice(title: String, labels: Array<String>, selected: Int, change: (Int) -> Unit) {
        showDialog(SettingsDialogs.builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                safely { change(which) }
                dialog.dismiss()
            }.setNegativeButton("取消", null).create())
    }

    private fun behavior() {
        page("behavior", "外观与按键")
        notice("开关、选择与滑块会立即保存。数字和配色编辑在点击「保存」后生效。这里的主题只影响键盘，设置界面始终为浅色。")
        section("键盘主题")
        lateinit var fixedMode: Button
        toggle("跟随系统主题", config.theme == "system") { follow ->
            val systemDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            config.theme = if (follow) "system" else if (systemDark) "dark" else "light"
            fixedMode.text = "固定主题：${themeName(config.theme)}"
            fixedMode.visibility = if (follow) View.GONE else View.VISIBLE
        }
        fixedMode = button("固定主题：${themeName(config.theme)}") {
            val values = arrayOf("light", "dark")
            choice("固定主题", arrayOf("浅色", "深色"), values.indexOf(config.theme)) {
                config.theme = values[it]
                fixedMode.text = "固定主题：${themeName(config.theme)}"
            }
        }.apply { visibility = if (config.theme == "system") View.GONE else View.VISIBLE }
        button("自定义浅色主题") { themeEditor(false) }
        button("自定义深色主题") { themeEditor(true) }
        section("候选词字体")
        slider("候选栏字号", config.candidateTextSize, 12, 32, 1, "sp") { config.candidateTextSize = it }
        slider("展开候选字号", config.expandedCandidateTextSize, 12, 32, 1, "sp") { config.expandedCandidateTextSize = it }
        label("默认候选栏为20 sp，展开候选为18 sp；修改后重新打开键盘生效，并跟随系统字体缩放。")
        section("按键角标")
        val hintModes = arrayOf("all", "digits", "hidden")
        val hintLabels = arrayOf("全部", "仅数字", "隐藏")
        lateinit var hintButton: Button
        hintButton = button("按键角标：${hintLabels[hintModes.indexOf(config.symbolHints).coerceAtLeast(0)]}") {
            choice("按键角标", hintLabels, hintModes.indexOf(config.symbolHints)) { which ->
                config.symbolHints = hintModes[which]
                hintButton.text = "按键角标：${hintLabels[which]}"
            }
        }
        label("仅调整三行字母键右上角的提示显示，不改变长按输入。")
        section("按键与手势")
        toggle("按键声音", config.keySound) { config.keySound = it }
        label("默认关闭。开启后使用系统键盘音效，遵循系统触摸音效、静音与系统音量设置。")
        toggle("按键振动", config.vibration) { config.vibration = it }
        slider("振动强度", config.vibrationStrength, 1, 100, 1, "%") { config.vibrationStrength = it }
        label("10% 及以下使用系统键盘触感；更高强度在设备支持时调节短促触感，否则仍使用系统触感。遵循系统振动设置，实际力度因机型而异，无法指定物理马达。")
        toggle("长按输入符号", config.longPressSymbols) { config.longPressSymbols = it }
        slider("长按延迟", config.longPressDelay, 150, 1500, 10, "毫秒") { config.longPressDelay = it }
        toggle("滑动退格键删除", config.swipeDelete) { config.swipeDelete = it }
        section("输入与补全")
        toggle("四码自动上屏", config.fourCodeAutoCommit) { config.fourCodeAutoCommit = it }
        toggle("英文补全", config.englishCompletion) { config.englishCompletion = it }
        section("模糊拼音")
        toggle("模糊拼音", config.fuzzyPinyin) { config.fuzzyPinyin = it }
        label("逐项选择需要混用的声母和韵母。关闭总开关会暂停全部模糊匹配，保留以下选择。")
        val pairs =
            linkedMapOf(
                "zh_z" to "zh ↔ z",
                "ch_c" to "ch ↔ c",
                "sh_s" to "sh ↔ s",
                "n_l" to "n ↔ l",
                "r_l" to "r ↔ l",
                "f_h" to "f ↔ h",
                "an_ang" to "an ↔ ang",
                "en_eng" to "en ↔ eng",
                "in_ing" to "in ↔ ing",
            )
        pairs.forEach { (id, label) ->
            toggle(label, id in config.fuzzyPairs) { enabled ->
                config.fuzzyPairs = if (enabled) config.fuzzyPairs + id else config.fuzzyPairs - id
            }
        }
    }

    private fun themeEditor(dark: Boolean) {
        page(if (dark) "theme_dark" else "theme_light", if (dark) "深色主题配色" else "浅色主题配色")
        val draft = restoredEditorDraft
        restoredEditorDraft = null
        editor = KeyboardThemeEditor(this, config, ui, dark).also { it.show(body, draft) }
    }

    private fun profiles() {
        page("layout", "四套独立布局")
        notice("外屏、内屏分别保存竖屏和横屏配置。尺寸单位为 dp，每套配置独立生效。")
        val wide = resources.configuration.screenWidthDp >= 600 && resources.configuration.fontScale <= 1.25f
        val profiles = listOf(true to false, true to true, false to false, false to true)
        profiles.chunked(if (wide) 2 else 1).forEach { row ->
            val rowParent = if (wide) LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            } else body
            row.forEachIndexed { index, (outer, landscape) ->
                val cardParent = if (wide) ui.column().apply {
                    rowParent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index > 0) marginStart = dp(12)
                    })
                } else body
                group = ui.card(cardParent).apply { tag = "anchor:${SettingsNavigation.profile(outer, landscape)}" }
                val p = config.profile(outer, landscape)
                button(profileName(outer, landscape)) { profile(outer, landscape) }
                label("高度 ${p.heightDp} dp · 两侧 ${p.sideGapDp} dp · 底部 ${p.bottomGapDp} dp")
                label(if (p.floating) "悬浮模式已开启" else if (!outer && p.split) "分离布局已开启" else "标准布局")
            }
        }
        group = null
    }

    private fun profileName(outer: Boolean, landscape: Boolean) =
        "${if (outer) "外屏" else "内屏"} · ${if (landscape) "横屏" else "竖屏"}"

    private fun profile(outer: Boolean, landscape: Boolean) {
        page(SettingsNavigation.profile(outer, landscape), profileName(outer, landscape))
        notice("数字点击后编辑并保存；开关立即保存。仅影响这套布局，单位为 dp。")
        val p = config.profile(outer, landscape)
        fun save(change: (SettingsStore.LayoutProfile) -> SettingsStore.LayoutProfile) {
            // Every edit merges into the latest profile, never a stale page snapshot.
            config.saveProfile(outer, landscape, change(config.profile(outer, landscape)))
        }
        section("尺寸与留白")
        number("键盘高度", p.heightDp, 200, 700) { value -> save { it.copy(heightDp = value) } }
        number("两侧对称留白", p.sideGapDp, 0, 300) { value -> save { it.copy(sideGapDp = value) } }
        number("底部留白", p.bottomGapDp, 0, 300) { value -> save { it.copy(bottomGapDp = value) } }
        section("悬浮窗口")
        toggle("悬浮模式", p.floating) { value -> save { it.copy(floating = value) } }
        number("悬浮横向位置（距左）", p.floatingXDp, 0, 2000) { value -> save { it.copy(floatingXDp = value) } }
        number("悬浮纵向位置（距底）", p.floatingYDp, 0, 2000) { value -> save { it.copy(floatingYDp = value) } }
        number("悬浮宽度", p.floatingWidthDp, 220, 1600) { value -> save { it.copy(floatingWidthDp = value) } }
        number("悬浮高度", p.floatingHeightDp, 200, 700) { value -> save { it.copy(floatingHeightDp = value) } }
        if (!outer) {
            section("分离布局")
            toggle("分离布局", p.split) { value -> save { it.copy(split = value) } }
            number("分离中央间距", p.splitGapDp, 0, 180) { value -> save { it.copy(splitGapDp = value) } }
        }
    }

    private fun toolbar() {
        page("toolbar", "定制工具栏")
        toolbarEditor = io.secure.keyboard.settings.ToolbarEditor(this, config, ui, ::toolName).also { it.show(body) }
    }

    private fun clipboard() {
        page("clipboard", "剪贴板")
        notice("置顶仅改变顺序；超出上限优先删除最旧未置顶，全部置顶时删除最旧记录。不自动过期，重复复制也会保留。置顶记录也会随内容清空。仅在 Android 允许访问时响应复制事件，不在后台轮询，不收集密码输入场景或带敏感标记的内容。单条最多65536字符，历史合计最多100万字符；超长或短时大量复制可能跳过自动保存，系统剪贴板仍可粘贴。清空不会修改 Android 系统剪贴板，旧内容也不会再次收集，直到您重新复制。")
        section("保存设置")
        number("保存条数上限", config.clipboardLimit, 1, 1000) {
            config.clipboardLimit = it
            content.trimClipboard()
            clipboard()
        }
        val records = content.clipboard().asReversed()
        group = null
        ui.text(body, "已保存 ${records.size} 条", 16f, true)
        if (records.isEmpty()) notice("还没有保存的剪贴板记录。")
        contentPage(records, "clipboard", ::clipboard).forEach { (index, item) ->
            group = ui.card(body).apply { tag = "anchor:clipboard:${item.id}" }
            label(item.text, 15f).setTextColor(ui.ink)
            button("删除这条记录") {
                content.deleteClipboard(item.id)
                (records.getOrNull(index + 1) ?: records.getOrNull(index - 1))?.let {
                    keepItemVisible("anchor:clipboard:${it.id}")
                }
                clipboard()
            }
        }
        section("内容清理")
        button("清空已保存的剪贴板") {
            confirm("删除本应用保存的全部剪贴板记录？") {
                content.clearClipboard()
                clipboard()
            }
        }
    }

    /** Bound management-page view creation while retaining global move/delete indices. */
    private fun <T> contentPage(values: List<T>, route: String, refresh: () -> Unit): List<IndexedValue<T>> {
        val lastPage = ((values.size - 1) / 100).coerceAtLeast(0)
        val pageIndex = (contentPageIndices[route] ?: 0).coerceIn(0, lastPage)
        contentPageIndices[route] = pageIndex
        if (lastPage > 0) {
            section("第 ${pageIndex + 1} / ${lastPage + 1} 页 · 每页最多100条")
            if (pageIndex > 0) button("上一页") {
                contentPageIndices[route] = pageIndex - 1
                refresh()
            }
            if (pageIndex < lastPage) button("下一页") {
                contentPageIndices[route] = pageIndex + 1
                refresh()
            }
        }
        val start = pageIndex * 100
        return values.subList(start, minOf(start + 100, values.size)).mapIndexed { offset, value ->
            IndexedValue(start + offset, value)
        }
    }

    private fun phrases() {
        page("phrases", "常用语")
        notice("只保存您手动添加的常用语。不自动收集，不分类，不导入。编辑点击「保存」后生效；移动顺序立即保存。")
        button("添加常用语", true) { editPhrase(null) }
        val phrases = content.phrases()
        if (phrases.isEmpty()) notice("还没有常用语，添加一条常用内容即可开始。")
        contentPage(phrases, "phrases", ::phrases).forEach { (index, item) ->
            group = ui.card(body).apply { tag = "anchor:phrase:${item.id}" }
            label(item.text, 15f).setTextColor(ui.ink)
            button("编辑") { editPhrase(item.id) }
            if (index > 0) button("上移") {
                content.movePhrase(item.id, index - 1)
                contentPageIndices["phrases"] = (index - 1) / 100
                keepItemVisible("anchor:phrase:${item.id}")
                phrases()
            }
            if (index < phrases.lastIndex) button("下移") {
                content.movePhrase(item.id, index + 1)
                contentPageIndices["phrases"] = (index + 1) / 100
                keepItemVisible("anchor:phrase:${item.id}")
                phrases()
            }
            button("删除") {
                content.deletePhrase(item.id)
                (phrases.getOrNull(index + 1) ?: phrases.getOrNull(index - 1))?.let {
                    keepItemVisible("anchor:phrase:${it.id}")
                }
                phrases()
            }
        }
        section("内容清理")
        button("清空常用语") {
            confirm("删除全部常用语？") { content.clearPhrases(); phrases() }
        }
    }

    private fun dictionaryName(kind: DictionaryKind) =
        if (kind == DictionaryKind.CHINESE) "中文词库" else "英文词库"

    private fun dictionary(
        kind: DictionaryKind = DictionaryKind.CHINESE,
        requestedPage: Int = dictionaryPageIndices[kind] ?: 0,
    ) {
        val name = dictionaryName(kind)
        val route = if (kind == DictionaryKind.CHINESE) "dictionary" else "english_dictionary"
        if (currentDictionaryKind == kind && requestedPage != dictionaryPageIndices[kind]) {
            keepItemVisible("anchor:dictionary:page")
        }
        page(route, name)
        currentDictionaryKind = kind
        intent.putExtra("page", route)
        section("词库管理")
        label("UTF-8 TSV 文件：词语 + 制表符 + 编码。编辑需要保存，导入前会完整校验。")
        val completeHelp = label(
            "导入导出格式为 UTF-8 TSV：每行「词语 + 制表符 + 编码」，可选首行为 word<TAB>reading。字段内不能有制表符或换行。每个词库最多 10,000 条，单次导入最多 400 万字符，先校验完整文件，再追加到$name。导出文件保存在您选择的位置，清空应用内容不会删除这些文件。\n" +
            if (kind == DictionaryKind.ENGLISH)
                "编码须为 1–40 个英文字母，匹配时不区分大小写。词语按原样输出，可含空格和标点，但首尾不能有空白。开启「英文补全」后，仅在英文输入中使用。"
            else "拼音／编码与词语按原样保存，仅用于中文输入。"
        ).apply { visibility = if (route in navigation.expandedHelp) View.VISIBLE else View.GONE }
        lateinit var helpButton: Button
        helpButton = button(if (route in navigation.expandedHelp) "收起格式说明" else "展开完整格式说明") {
            val expand = completeHelp.visibility != View.VISIBLE
            completeHelp.visibility = if (expand) View.VISIBLE else View.GONE
            if (expand) navigation.expandedHelp.add(route) else navigation.expandedHelp.remove(route)
            helpButton.text = if (expand) "收起格式说明" else "展开完整格式说明"
        }
        button("添加词条") { editDictionary(null, kind) }
        button("导入 TSV") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream"))
                },
                if (kind == DictionaryKind.CHINESE) IMPORT_CHINESE else IMPORT_ENGLISH,
            )
        }
        button("导出 TSV") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/tab-separated-values"
                    putExtra(
                        Intent.EXTRA_TITLE,
                        if (kind == DictionaryKind.CHINESE) "offline-keyboard-chinese-dictionary.tsv"
                        else "offline-keyboard-english-dictionary.tsv",
                    )
                },
                if (kind == DictionaryKind.CHINESE) EXPORT_CHINESE else EXPORT_ENGLISH,
            )
        }
        val entries = content.dictionary(kind)
        val lastPage = ((entries.size - 1).coerceAtLeast(0)) / DICTIONARY_PAGE_SIZE
        val pageIndex = requestedPage.coerceIn(0, lastPage)
        dictionaryPageIndices[kind] = pageIndex
        group = null
        fun pageControls(first: Boolean = false) {
            group = ui.card(body, ui.tint).apply { if (first) tag = "anchor:dictionary:page" }
            if (entries.isEmpty()) {
                label("还没有词条，添加或导入后即可在这里管理。")
                group = null
                return
            }
            label("共 ${entries.size} 条 · 第 ${pageIndex + 1} / ${lastPage + 1} 页")
            if (pageIndex > 0) button("上一页") { dictionary(kind, pageIndex - 1) }
            if (pageIndex < lastPage) button("下一页") { dictionary(kind, pageIndex + 1) }
            group = null
        }
        pageControls(true)
        val visibleEntries = entries.drop(pageIndex * DICTIONARY_PAGE_SIZE).take(DICTIONARY_PAGE_SIZE)
        visibleEntries.forEachIndexed { index, item ->
            body.addView(
                DictionaryEntryRow(
                    this,
                    item,
                    ui.ink,
                    ui.muted,
                    ui.surface,
                    ui.blue,
                    SettingsPalette.error,
                    onEdit = { entry -> safely { editDictionary(entry, kind) } },
                    onDelete = { entry ->
                        confirm("删除$name 中的该词条？不会影响其他词条。", "删除词条？") {
                            content.deleteDictionary(entry.id, kind)
                            (visibleEntries.getOrNull(index + 1) ?: visibleEntries.getOrNull(index - 1))?.let {
                                keepItemVisible("anchor:dictionary:${kind.name}:${it.id}")
                            }
                            dictionary(kind)
                        }
                    },
                ).apply { tag = "anchor:dictionary:${kind.name}:${item.id}" },
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) },
            )
        }
        if (entries.size > DICTIONARY_PAGE_SIZE) pageControls()
        section("内容清理")
        button("清空$name") {
            confirm("删除$name 的全部词条？另一个词库和已导出的文件不会被删除。", "清空$name？") {
                content.clearDictionary(kind)
                dictionary(kind)
            }
        }
    }

    private fun editDictionary(item: ContentStore.DictionaryEntry?, kind: DictionaryKind, draft: Bundle? = null) {
        val group = ui.column()
        val word = SettingsInputs.field(this).apply {
            id = R.id.settings_dictionary_word
            hint = if (kind == DictionaryKind.ENGLISH) "词语／短语（按原样输出）" else "词语"
            setText(item?.word ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val reading = SettingsInputs.field(this).apply {
            id = R.id.settings_dictionary_reading
            hint = if (kind == DictionaryKind.ENGLISH) "编码（1–40 个英文字母）" else "拼音／编码"
            setText(item?.reading ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
        }
        SettingsInputs.labeled(group, word.hint.toString(), word)
        SettingsInputs.labeled(group, reading.hint.toString(), reading)
        ui.text(group, "完整内容按原样保存。点击「取消」不会修改词条。", 13f)
        val dialog = SettingsDialogs.builder(this)
            .setTitle("${if (item == null) "添加" else "编辑"}${dictionaryName(kind)}词条")
            .setView(SettingsDialogs.scrollable(this, group))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val output = word.text.toString()
                val code = reading.text.toString()
                SettingsInputs.error(word, null)
                SettingsInputs.error(reading, null)
                if (kind == DictionaryKind.ENGLISH) {
                    var valid = true
                    if (output.isBlank() || output.any { it == '\t' || it == '\r' || it == '\n' }) {
                        SettingsInputs.error(word, "请输入词语或短语，不能含制表符或换行")
                        valid = false
                    } else if (output.first().isWhitespace() || output.last().isWhitespace()) {
                        SettingsInputs.error(word, "词语首尾不能有空白；内部空格会按原样保留")
                        valid = false
                    }
                    if (code.length !in 1..40 || code.any { it !in 'a'..'z' && it !in 'A'..'Z' }) {
                        SettingsInputs.error(reading, "编码须为 1–40 个英文字母（A–Z），不区分大小写")
                        valid = false
                    }
                    if (!valid) {
                        (if (word.error != null) word else reading).requestFocus()
                        return@setOnClickListener
                    }
                }
                safely {
                    try {
                        if (item == null) content.addDictionary(output, code, kind)
                        else content.updateDictionary(item.id, output, code, kind)
                        dialog.dismiss()
                        dictionary(kind)
                    } catch (error: IllegalArgumentException) {
                        SettingsInputs.error(if (output.isBlank()) word else reading, error.message ?: "请检查词语与编码")
                    }
                }
            }
        }
        showEditable(dialog, Bundle().apply {
            putString("type", "dictionary")
            putString("kind", kind.name)
            putString("item_id", item?.id)
        }, listOf(word, reading), draft)
    }

    private fun emoji() {
        page("emoji", "最近表情")
        notice("最多保存 50 个最近使用的表情，最新的排在前面，不统计使用次数。")
        section("最近使用")
        val recent = content.recentEmoji()
        label(if (recent.isEmpty()) "还没有最近使用的表情。" else recent.joinToString("  "), 24f)
        section("内容清理")
        button("清空最近表情") {
            confirm("删除最近使用的表情记录？") { content.clearEmoji(); emoji() }
        }
    }

    private fun editPhrase(itemId: String?, draft: Bundle? = null) {
        val item = itemId?.let { id -> content.phrases().firstOrNull { it.id == id } }
        if (itemId != null && item == null) return
        val input = SettingsInputs.field(this).apply {
            id = R.id.settings_phrase_input
            setText(item?.text.orEmpty())
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
        }
        val parent = ui.column()
        SettingsInputs.labeled(parent, "常用语内容", input)
        ui.text(parent, "保留原始换行与文字。点击「保存」后生效。", 13f)
        val dialog = SettingsDialogs.builder(this)
            .setTitle(if (itemId == null) "添加常用语" else "编辑常用语")
            .setView(SettingsDialogs.scrollable(this, parent))
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isBlank()) SettingsInputs.error(input, "请输入内容")
                else safely {
                    if (itemId == null) {
                        content.addPhrase(input.text.toString())
                        contentPageIndices["phrases"] = (content.phrases().size - 1) / 100
                    }
                    else content.updatePhrase(itemId, input.text.toString())
                    dialog.dismiss()
                    phrases()
                }
            }
        }
        showEditable(dialog, Bundle().apply {
            putString("type", "phrase"); putString("item_id", itemId)
        }, listOf(input), draft)
    }

    private fun confirm(message: String, title: String = "清空用户内容？", action: () -> Unit) {
        showDialog(SettingsDialogs.builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> safely(action) }.create())
    }

    private fun showDialog(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (transferDialog === dialog) transferDialog = null
            if (activeDialog === dialog) {
                activeDialog = null
                activeDraft = null
                draftFields = emptyList()
                lifetimeState.editableDraft = null
            }
            dialog.setOnShowListener(null)
            dialog.setOnDismissListener(null)
            for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                dialog.getButton(which)?.setOnClickListener(null)
            }
        }
        return SettingsDialogs.show(dialog)
    }

    private fun showEditable(dialog: AlertDialog, descriptor: Bundle, fields: List<EditText>, restored: Bundle?) {
        closeDialogs()
        fields.forEach {
            // These fields have a complete manual restore path. Never duplicate their text in
            // the hierarchy state, even if a future parent starts saving the dialog hierarchy.
            it.isSaveEnabled = false
            it.isSaveFromParentEnabled = false
        }
        restored?.let { state ->
            fields.forEachIndexed { index, field ->
                if (state.containsKey("field_$index")) field.setText(state.getString("field_$index").orEmpty())
                val start = state.getInt("selection_start_$index", field.text.length).coerceIn(0, field.text.length)
                val end = state.getInt("selection_end_$index", start).coerceIn(0, field.text.length)
                // Keep a valid reversed anchor/focus range; clamp only invalid offsets.
                field.setSelection(start, end)
                state.getString("error_$index")?.let { SettingsInputs.error(field, it) }
            }
        }
        activeDialog = dialog
        activeDraft = descriptor
        draftFields = fields
        showDialog(dialog)
        val focus = restored?.getInt("focused", -1) ?: -1
        if (focus in fields.indices) fields[focus].requestFocus()
    }

    private fun captureEditableDraft(): SettingsEditableDraft? = activeDraft?.let { descriptor ->
        SettingsEditableDraft(
            route = navigation.current,
            type = descriptor.getString("type").orEmpty(),
            key = descriptor.getString("key"),
            kind = descriptor.getString("kind"),
            itemId = descriptor.getString("item_id"),
            fields = draftFields.map { field ->
                SettingsEditableDraft.Field(field.text.toString(), field.selectionStart,
                    field.selectionEnd, field.error?.toString())
            },
            focused = draftFields.indexOfFirst { it.hasFocus() },
        )
    }

    private fun editableDraftFitsSavedStateBudget(): Boolean = SettingsDraftBudget.accepts(sequence {
        yield(navigation.current)
        yield(activeDraft?.getString("type"))
        yield(activeDraft?.getString("key"))
        yield(activeDraft?.getString("kind"))
        yield(activeDraft?.getString("item_id"))
        draftFields.forEach { field ->
            yield(field.text)
            yield(field.error)
        }
    })

    private fun restoreEditableDraft(draft: Bundle) {
        if (draft.getString("route") != navigation.current) return
        when (draft.getString("type")) {
            "number" -> numberEditors[draft.getString("key")]?.invoke(draft)
            "phrase" -> editPhrase(draft.getString("item_id"), draft)
            "dictionary" -> {
                val kind = DictionaryKind.values().firstOrNull { it.name == draft.getString("kind") } ?: return
                val id = draft.getString("item_id")
                val item = id?.let { content.dictionary(kind).firstOrNull { entry -> entry.id == it } }
                if (id != null && item == null) return
                editDictionary(item, kind, draft)
            }
        }
    }

    private fun closeDialogs() = dismissOwnedDialogs(keepRetainedDraft = false)

    private fun dismissOwnedDialogs(keepRetainedDraft: Boolean) {
        if (!keepRetainedDraft && ::lifetimeState.isInitialized) lifetimeState.editableDraft = null
        // Clear before dismiss: a queued callback from the old Activity must not erase the
        // snapshot retained for its replacement (or a new edit owned by that replacement).
        activeDialog = null
        activeDraft = null
        draftFields = emptyList()
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    override fun onStart() {
        super.onStart()
        dictionaryTransfer.observer = { completion ->
            transferDialog?.dismiss()
            transferDialog = null
            safely { dictionary(completion.kind) }
            completion.result.fold(
                onSuccess = { message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                },
                onFailure = { error ->
                    if (error is ContentStore.ContentReadException) storageRecovery(error)
                    else
                        showDialog(SettingsDialogs.builder(this)
                            .setTitle("${dictionaryName(completion.kind)}操作未完成")
                            .setMessage(error.message ?: "请检查文件和可用空间后重试。")
                            .setPositiveButton("知道了", null)
                            .create())
                },
            )
        }
        dictionaryTransfer.deliver()
        showTransferProgress()
    }

    override fun onStop() {
        dictionaryTransfer.observer = null
        transferDialog?.dismiss()
        transferDialog = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberPosition()
        outState.putString("settings_route", navigation.current)
        outState.putStringArrayList("settings_history", ArrayList(navigation.history))
        outState.putBundle("settings_positions", Bundle().apply {
            navigation.positions.forEach { (route, position) ->
                putBundle(route, Bundle().apply {
                    putInt("offset", position.offset)
                    putString("anchor", position.anchor)
                    putInt("anchor_offset", position.anchorOffset)
                    putString("focus", position.focus)
                })
            }
        })
        outState.putStringArrayList("settings_help", ArrayList(navigation.expandedHelp))
        outState.remove("editable_draft")
        outState.remove("oversized_editable_draft")
        if (activeDraft != null) {
            if (editableDraftFitsSavedStateBudget()) {
                outState.putBundle("editable_draft", captureEditableDraft()?.toEditorBundle())
            } else {
                // No prefix, history file, or large temporary serialization. The complete edit
                // is retained only if Android later requests configuration-instance retention.
                outState.putBoolean("oversized_editable_draft", true)
            }
        }
        outState.putBundle("editor_draft", editor?.saveDraft())
        outState.putString("recovery_message", recoveryMessage)
        for (route in listOf("clipboard", "phrases")) {
            outState.putInt("content_page_$route", contentPageIndices[route] ?: 0)
        }
        currentDictionaryKind?.let { outState.putString("dictionary_kind", it.name) }
        DictionaryKind.values().forEach { kind ->
            outState.putInt("dictionary_page_${kind.name}", dictionaryPageIndices[kind] ?: 0)
        }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Retains the transfer and one complete immutable edit; never Activity/View state")
    override fun onRetainNonConfigurationInstance(): Any {
        lifetimeState.editableDraft = captureEditableDraft()
        return lifetimeState
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        dictionaryTransfer.observer = null
        transferDialog?.dismiss()
        transferDialog = null
        dismissOwnedDialogs(keepRetainedDraft = isChangingConfigurations)
        toolbarEditor?.close()
        toolbarEditor = null
        editor?.close()
        editor = null
        numberEditors.clear()
        cancelLayoutRestore()
        if (::ui.isInitialized) ui.dispose()
        if (!isChangingConfigurations) dictionaryTransfer.finishPermanently(applicationContext)
        super.onDestroy()
    }

    private fun showTransferProgress() {
        if (!dictionaryTransfer.running || isFinishing || isDestroyed || transferDialog != null) return
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(ProgressBar(this@SettingsActivity).apply {
                indeterminateTintList = ColorStateList.valueOf(SettingsPalette.accent)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(TextView(this@SettingsActivity).apply {
                text = "正在处理${dictionaryName(dictionaryTransfer.kind)}，请稍候…"
                setTextColor(SettingsPalette.ink)
                textSize = 15f
                setPadding(dp(16), 0, 0, 0)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        transferDialog = showDialog(SettingsDialogs.builder(this)
            .setTitle(dictionaryTransfer.title).setView(SettingsDialogs.scrollable(this, progress))
            .setCancelable(false).create())
    }

    @Deprecated("Platform activity result API is used for a dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        // The request code is the durable picker destination, independent of navigation/rotation.
        val kind = when (requestCode) {
            IMPORT_CHINESE, EXPORT_CHINESE -> DictionaryKind.CHINESE
            IMPORT_ENGLISH, EXPORT_ENGLISH -> DictionaryKind.ENGLISH
            else -> return
        }
        val uri = data?.data ?: return
        if (dictionaryTransfer.running) {
            Toast.makeText(this, "词库操作仍在进行，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        // The worker holds application-scoped objects only. No persistent URI grant is requested.
        val resolver = applicationContext.contentResolver
        val store = content
        val importing = requestCode == IMPORT_CHINESE || requestCode == IMPORT_ENGLISH
        val expectedEpoch = if (importing) store.captureDictionaryEpoch(kind) else null
        val name = dictionaryName(kind)
        dictionaryTransfer.start(kind, if (importing) "导入$name" else "导出$name") {
            if (importing) {
                val tsv =
                    resolver.openInputStream(uri)?.use { DictionaryTextReader.read(it) }
                        ?: error("无法打开所选文件")
                // ContentStore validates every row and the cumulative 10,000-entry limit before
                // writing.
                val count = store.importDictionary(tsv, expectedEpoch, kind)
                "已向$name 导入 $count 条词条"
            } else {
                // Read and validate local content before opening (and truncating) the destination.
                val exported = store.exportDictionary(kind)
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(exported)
                } ?: error("无法写入所选文件")
                "$name 已导出"
            }
        }
        showTransferProgress()
    }

    /** Non-inner owner. The snapshot is consumed once; subsequent edits never form a history. */
    private class SettingsLifetimeState {
        val dictionaryTransfer = DictionaryTransfer()
        var editableDraft: SettingsEditableDraft? = null
    }

    /** Kept across rotation; completion is delivered only to the currently started Activity. */
    private class DictionaryTransfer {
        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        var running = false
            private set

        var title = "词库操作"
            private set

        var kind = DictionaryKind.CHINESE
            private set

        data class Completion(val kind: DictionaryKind, val result: Result<String>)

        var observer: ((Completion) -> Unit)? = null
        private var pending: Completion? = null
        private var finishedContext: android.content.Context? = null

        fun start(kind: DictionaryKind, title: String, work: () -> String) {
            check(!running)
            check(pending == null)
            this.kind = kind
            this.title = title
            running = true
            worker.execute {
                val result = runCatching(work)
                main.post {
                    running = false
                    pending = Completion(kind, result)
                    deliver()
                }
            }
        }

        fun deliver() {
            val result = pending ?: return
            val callback = observer
            if (callback != null) {
                pending = null
                callback(result)
            } else
                finishedContext?.let { context ->
                    pending = null
                    Toast.makeText(
                            context,
                            result.result.getOrElse { it.message ?: "${title}未完成" },
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
        }

        fun finishPermanently(context: android.content.Context) {
            finishedContext = context.applicationContext
            deliver()
            worker.shutdown()
        }
    }

    private fun storageRecovery(error: ContentStore.ContentReadException) {
        recoveryMessage = error.message ?: "本机内容无法读取，已停止保存以免覆盖原数据。"
        recoveryPage()
    }

    private fun recoveryPage() {
        page("recovery", "本机内容读取失败", false)
        notice(recoveryMessage ?: "本机内容无法读取，已停止保存以免覆盖原数据。")
        section("恢复读取")
        label("可以先重试。布局与键盘设置仍可正常调整，清空内容前会再次确认。")
        button("重试读取", true) { home() }
        button("外观与按键") { behavior() }
        button("布局配置") { profiles() }
        section("重新开始")
        button("清空全部用户内容并重新开始") {
            confirm("删除无法读取的内容，以及全部剪贴板、常用语、中文词库、英文词库和最近表情？布局与设置会保留；已导出的文件不会被删除。") {
                content.clearAll()
                home()
            }
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: ContentStore.ContentReadException) {
            storageRecovery(e)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "操作未完成", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DICTIONARY_PAGE_SIZE = 100
        private const val IMPORT_CHINESE = 100
        private const val EXPORT_CHINESE = 101
        private const val IMPORT_ENGLISH = 102
        private const val EXPORT_ENGLISH = 103
    }
}
