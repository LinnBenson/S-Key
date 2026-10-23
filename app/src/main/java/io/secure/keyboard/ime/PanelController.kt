package io.secure.keyboard.ime

import android.content.*
import android.content.Context.MODE_PRIVATE
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.data.ClipboardCapturePolicy
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.ui.FloatingResize
import io.secure.keyboard.ui.KeyboardFeedback
import io.secure.keyboard.ui.ContentEntryRow
import io.secure.keyboard.ui.EmojiGrid
import io.secure.keyboard.ui.ToolIcon

import io.secure.keyboard.*

/** Clipboard, phrases, emoji, tools and layout panels. */
internal object PanelController {
    internal fun KeyboardSession.openToolImpl(tool: String) {
        if (tool == "input_mode") {
            settings.chineseInputMethod = if (settings.chineseInputMethod == io.secure.keyboard.engine.ChineseInputMode.WUBI)
                io.secure.keyboard.engine.ChineseInputMode.PINYIN else io.secure.keyboard.engine.ChineseInputMode.WUBI
            toast(if (settings.chineseInputMethod == io.secure.keyboard.engine.ChineseInputMode.WUBI) "五笔" else "拼音")
            return
        }
        if (tool == "script") {
            settings.traditionalOutput = !settings.traditionalOutput
            renderHeader()
            if (panel == "more") renderBody()
            return
        }
        assistantBackspaceTapAt = 0L
        if(tool=="voice" && translation.enabled) { toggleAiCommandVoice(); return }
        if (translation.enabled && tool in assistantTools) {
            cancelTransient(); clearComposition()
            panel = if (panel == tool) "keys" else tool
            renderHeader(); renderBody(true)
            return
        }
        if (tool == "ai") {
            if (secret) { toast("密码输入框不支持 AI 助手"); return }
            val opening = !aiMode || !translation.enabled
            cancelTransient(); clearComposition(); stopAiRequest(); translation.close(); aiSelectionTarget.revoke()
            clearAiConversation(); aiMode = opening; aiCustom = ""; aiCustomReasoning = "low"
            aiCustomPresetName = ""; aiCustomModel = ""; aiPresetLoadGeneration++
            if (opening) {
                translation.enabled = true
                aiTask = getSharedPreferences("settings",MODE_PRIVATE).getString("aiTask","polish")?.takeIf { it in io.secure.keyboard.ai.AiWriting.tasks } ?: "polish"
                aiTone = getSharedPreferences("settings",MODE_PRIVATE).getString("aiTone","自然") ?: "自然"
                if(aiTask!="custom") restoreAiConversation()
            }
            panel="keys"; renderHeader(); renderBody()
            if(opening) { fillTranslationFromSelection(); fillDefaultCustomAiPreset() }
            return
        }
        if (aiMode) {
            stopAiRequest(); clearAiConversation(); aiMode=false; aiCustom=""; aiCustomPresetName=""; aiCustomModel=""; aiPresetLoadGeneration++; aiSelectionTarget.revoke(); translation.close()
        }
        if (tool == "translate") {
            if (secret) { toast("密码输入框不支持翻译"); return }
            cancelTransient(); clearComposition()
            val opening = !translation.enabled
            if (!opening) translation.close() else {
                translation.enabled = true
                translation.invalidate()
                translation.source = settings.translationSource.takeIf { it in translationLanguages } ?: "auto"
                translation.target = settings.translationTarget.takeIf { it in translationLanguages && it != "auto" } ?: "en"
            }
            panel = "keys"; renderHeader(); renderBody()
            if (opening) fillTranslationFromSelection()
            return
        }
        if (translation.enabled) translation.close()
        val previousPanel = panel
        cancelTransient()
        // Re-tapping an inline tool is the same safe exit as the left Back control.
        // Cancel before returning so voice cannot deliver a queued segment after closing.
        if (previousPanel == tool && tool in setOf("clipboard", "phrases", "voice", "quickedit", "emoji", "layout")) {
            panel = "keys"
            renderHeader()
            renderBody()
            return
        }
        when (tool) {
            "settings",
            "dictionary",
            "english_dictionary" -> {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("page", tool)
                )
                return
            }
            "floating" -> {
                saveProfile(profile.copy(floating = !profile.floating))
                panel = "keys"
                createView()
                setInputView(root)
                return
            }
            "split" -> {
                if (outer) {
                    toast("分离布局仅在内屏启用")
                    return
                }
                saveProfile(profile.copy(split = !profile.split))
                panel = "keys"
                renderHeader()
                renderBody()
                return
            }
            "theme" -> {
                settings.theme = if (dark) "light" else "dark"
                createView()
                setInputView(root)
                return
            }
        }
        if (tool == "emoji") {
            val smileys = emojiGroups.indexOfFirst { it.first == "Smileys & Emotion" }
            emojiCategory = if (smileys >= 0) smileys + 1 else 1
            emojiCategoryScroll = null
        }
        panel = tool
        renderHeader()
        renderBody()
        if (tool == "voice") startVoice()
    }

    internal fun KeyboardSession.renderMoreImpl() {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid =
            GridLayout(this).apply {
                columnCount = 4
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        scroll.addView(grid)
        body.addView(scroll)
        val pinnedTools = settings.toolbarPins.toSet()
        tools.filterKeys { it !in pinnedTools }.forEach { (id, name) ->
            val cell =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = shape(keyBg)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    KeyboardFeedback.disableAutomatic(this)
                    val feedback = installPressFeedback()
                    setOnClickListener {
                        feedback.click()
                        openTool(id)
                    }
                }
            cell.addView(icon(id, name) { openTool(id) }, LinearLayout.LayoutParams(dp(38), dp(38)))
            cell.addView(label(name, 12f), LinearLayout.LayoutParams(-1, dp(24)))
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(84)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                },
            )
        }
    }

    internal fun KeyboardSession.renderQuickEditImpl() {
        quickEditSelection.validate(quickEditConnection())
        val grid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(3), dp(4), dp(4))
            }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val labels = listOf("全选", "向上移动", "剪切", "向左移动", "选择", "向右移动", "复制", "向下移动", "粘贴")
        val actions =
            listOf<() -> Unit>(
                { quickEditAction(android.R.id.selectAll) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_UP) },
                { quickEditAction(android.R.id.cut) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_LEFT) },
                {
                    quickEditSelection.toggle(quickEditConnection())
                    renderBody()
                },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
                { quickEditAction(android.R.id.copy) },
                { quickEditArrow(KeyEvent.KEYCODE_DPAD_DOWN) },
                { quickEditAction(android.R.id.paste) },
            )
        for (r in 0..2) {
            val row = LinearLayout(this)
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            for (c in 0..2) {
                val i = r * 3 + c
                val glyph =
                    mapOf(
                        1 to "arrow_up",
                        3 to "arrow_left",
                        5 to "arrow_right",
                        7 to "arrow_down",
                    )[i]
                val v =
                    if (glyph == null) button(labels[i], actions[i]).apply {
                        if (i == 4) {
                            isSelected = quickEditSelection.enabled
                            background = shape(if (isSelected) accent else keyBg)
                            setTextColor(if (isSelected) enterText else fg)
                            contentDescription = "选择"
                            if (Build.VERSION.SDK_INT >= 30)
                                stateDescription = if (isSelected) "已开启，再点退出" else "已关闭"
                            isEnabled = quickEditConnection() != null
                            if (!isEnabled) alpha = .4f
                            val feedback = pressFeedback()
                            val swipe = io.secure.keyboard.ui.SelectionKeySwipe(dp(18).toFloat()) { direction ->
                                if (isAttachedToWindow && panel == "quickedit") quickEditArrow(direction)
                            }
                            setOnClickListener { feedback.click(); actions[i]() }
                            setOnTouchListener { view, event ->
                                feedback.onTouch(event)
                                swipe.onTouch(view, event)
                            }
                        }
                    }
                    else
                        icon(glyph, labels[i], actions[i]).apply {
                            background = shape(keyBg)
                        }
                row.addView(
                    v,
                    LinearLayout.LayoutParams(0, -1, 1f).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    },
                )
            }
        }
    }

    internal fun KeyboardSession.quickEditArrowImpl(key: Int) {
        val wasSelecting = quickEditSelection.enabled
        quickEditSelection.move(quickEditConnection(), key)
        if (wasSelecting != quickEditSelection.enabled) renderBody()
    }

    internal fun KeyboardSession.arrowImpl(key: Int) {
        activeAssistantEditor()?.let { editor ->
            editor.onKeyDown(key,KeyEvent(KeyEvent.ACTION_DOWN,key))
            editor.onKeyUp(key,KeyEvent(KeyEvent.ACTION_UP,key))
            return
        }
        // The platform IME helper marks both events SOFT_KEYBOARD and KEEP_TOUCH_MODE.
        // Cursor swipes/backspace must not put the host into hardware-keyboard focus mode.
        sendDownUpKeyEvents(key)
    }

    internal fun KeyboardSession.captureClipboardImpl(newCopyEvent: Boolean = false) {
        if (!clipsInitialized || (!active && !newCopyEvent) || secret) return
        if (!newCopyEvent && !worker.isShutdown) clipboardWrites.submit(0) {
            try { content.trimClipboard() } catch (_: Exception) { reportContentError() }
        }
        try {
            val clip = clips.primaryClip ?: return
            val text = ClipboardCapturePolicy.textForHistory(clip) ?: return
            val timestamp = clip.description.timestamp
            val epoch = content.clipboardGeneration()
            if (worker.isShutdown) return
            clipboardWrites.submit(text.length) {
                try {
                    val saved = content.captureClipboard(text, timestamp, newCopyEvent, epoch)
                    if (saved) main.post { if (active && panel == "clipboard") renderBody() }
                } catch (_: Exception) {
                    reportContentError()
                }
            }
        } catch (_: SecurityException) {
            /* Android blocks clipboard while this IME is not eligible. */
        } catch (_: Exception) {
            reportContentError()
        }
    }

    internal fun KeyboardSession.reportContentErrorImpl() {
        main.post {
            if (active && !contentErrorShown) {
                contentErrorShown = true
                toast("本机保存内容无法读取或写入，可在设置中清除全部内容后重试")
            }
        }
    }

    internal fun KeyboardSession.renderContentImpl() {
        val isClipboard = panel == "clipboard"
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        if (isClipboard && secret) {
            // Do not read or render saved/system clipboard contents in a password context.
            // Explicit paste stays available and is performed by the host editor itself.
            column.addView(label("密码输入框不显示剪贴板内容", 14f, muted).apply {
                setPadding(dp(16), dp(24), dp(16), dp(16))
            })
            column.addView(button("粘贴") {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }, LinearLayout.LayoutParams(-1, dp(52)).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
            return
        }
        val loading = label("正在读取…", 14f, muted)
        column.addView(loading)
        val expectedPanel = panel
        fun current() = !destroyed && active && panel == expectedPanel &&
            body.indexOfChild(column) >= 0 && !(isClipboard && secret)
        // Parsing and the shared storage lock never block the input/main thread.
        contentQueries.submit {
            val entries = readContent(emptyList<ContentStore.ClipboardItem>()) {
                if (isClipboard) content.clipboardForDisplay()
                else content.phrasesForDisplay().map { ContentStore.ClipboardItem(it.id, it.text, it.pinned) }
            }
            val webLinks = if (isClipboard) entries.associate {
                it.id to io.secure.keyboard.data.ClipboardWebLink.parse(it.text)
            } else emptyMap()
            main.post {
                if (!current()) return@post
                column.removeAllViews()
                if (!isClipboard) {
                    column.addView(button("＋ 添加常用语") {
                        if (current()) startActivity(
                            Intent(this, SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra("page", "phrases")
                                .putExtra("add_phrase", true)
                        )
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply {
                        setMargins(dp(10), dp(4), dp(10), dp(8))
                    })
                }
                if (entries.isEmpty()) {
                    if (isClipboard) column.addView(label("复制的文字会保存在这里", 14f, muted))
                    return@post
                }
                var changing = false
                fun changeContent(action: () -> Unit) {
                    if (!current() || changing) return
                    changing = true
                    column.cancelPendingInputEvents()
                    column.removeAllViews()
                    column.addView(label("正在保存…", 14f, muted))
                    worker.execute {
                        readContent(Unit) { action() }
                        main.post { if (current()) renderBody() }
                    }
                }
                val list = ListView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    divider = null
                    dividerHeight = dp(6)
                    setPadding(dp(10), 0, dp(10), 0)
                    clipToPadding = true
                }
                list.adapter = object : BaseAdapter() {
                    override fun getCount() = entries.size
                    override fun getItem(position: Int) = entries[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val entry = entries[position]
                        val webLink = webLinks[entry.id]
                        val openLink: (() -> Unit)? = webLink?.let { url -> {
                            if (current() && !changing) {
                                try {
                                    val browse = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    val browser = packageManager.resolveActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                                            .addCategory(Intent.CATEGORY_BROWSABLE),
                                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                                    )?.takeIf { it.match > 0 }?.activityInfo?.packageName
                                    if (browser != null && browser != "android") browse.setPackage(browser)
                                    startActivity(browse)
                                } catch (_: ActivityNotFoundException) { toast("未找到可用的浏览器") }
                                catch (_: SecurityException) { toast("无法打开浏览器") }
                            }
                        } }
                        val insert: (String) -> Unit = { if (current() && !changing) { commit(it) } }
                        val pin: (Boolean) -> Unit = { pinned -> changeContent {
                            if (isClipboard) content.setClipboardPinned(entry.id, pinned)
                            else content.setPhrasePinned(entry.id, pinned)
                        } }
                        val delete: () -> Unit = { changeContent {
                            if (isClipboard) content.deleteClipboard(entry.id)
                            else content.deletePhrase(entry.id)
                        } }
                        return (convertView as? ContentEntryRow)?.apply {
                            bind(entry.text, entry.pinned, insert, pin, delete, openLink)
                        } ?: ContentEntryRow(
                            this@renderContentImpl, entry.text, entry.pinned,
                            textColor = fg, surfaceColor = keyBg, actionColor = toolbarColor,
                            pinnedColor = accent,
                            deleteColor = if (dark) 0xFFFF7B83.toInt() else 0xFFC94A54.toInt(),
                            categoryLabel = if (isClipboard) "剪贴板记录" else "常用语",
                            onInsert = insert, onPin = pin, onDelete = delete, onOpenLink = openLink,
                            feedback = { pressFeedback() },
                        ).apply { layoutParams = AbsListView.LayoutParams(-1, dp(52)) }
                    }
                }
                column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }

    internal fun KeyboardSession.renderEmojiImpl() {
        val groups =
            listOf("Recent" to emptyList<String>()) +
                emojiGroups
        val selectedCategory = emojiCategory.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        val savedScroll = emojiCategoryScroll
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column, FrameLayout.LayoutParams(-1, -1))
        val cats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bar =
            object : HorizontalScrollView(this) {
                private var restoredScroll = false

                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)
                    if (restoredScroll) return
                    restoredScroll = true
                    scrollTo(savedScroll?.second ?: 0, 0)
                    // A category tap retains its viewport. A grid swipe reveals the newly
                    // selected shortcut only if it lies beyond that viewport.
                    if (savedScroll?.first != selectedCategory) {
                        val selected = cats.getChildAt(selectedCategory)
                        if (selected != null) {
                            when {
                                selected.left < scrollX -> scrollTo(selected.left, 0)
                                selected.right > scrollX + width ->
                                    scrollTo(selected.right - width, 0)
                            }
                        }
                    }
                    emojiCategoryScroll = selectedCategory to scrollX
                }

                override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                    super.onScrollChanged(l, t, oldl, oldt)
                    if (restoredScroll && column.parent === body)
                        emojiCategoryScroll = selectedCategory to l
                }
            }.apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                addView(cats, FrameLayout.LayoutParams(-2, -1))
            }
        column.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
        column.addView(
            View(this).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        groups.forEachIndexed { i, g ->
            val categoryView =
                if (i == 0) ToolIcon(this, "recent", if (i == selectedCategory) accent else muted)
                else
                    label(
                        emojiIcons.getOrElse(i) { "☺" },
                        23f,
                        if (i == selectedCategory) accent else muted,
                    )
            cats.addView(
                categoryView.apply {
                    KeyboardFeedback.disableAutomatic(this)
                    minimumWidth = dp(42)
                    contentDescription =
                        listOf(
                                "最近使用",
                                "笑脸与情感",
                                "人物与身体",
                                "动物与自然",
                                "食物与饮料",
                                "旅行与地点",
                                "活动",
                                "物品",
                                "符号",
                                "旗帜",
                            )
                            .getOrElse(i) { g.first }
                    val feedback = installPressFeedback()
                    setOnClickListener {
                        feedback.click()
                        emojiCategory = i
                        renderBody()
                    }
                },
                LinearLayout.LayoutParams(dp(42), -1, 1f),
            )
        }
        val grid =
            EmojiGrid(this).apply {
                KeyboardFeedback.disableAutomatic(this)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(46)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = dp(2)
            }
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        var items = groups.getOrNull(selectedCategory)?.second ?: emptyList()
        if (selectedCategory == 0 && !secret) {
            val epoch = content.emojiGeneration()
            contentQueries.submit {
                val recent = readContent(emptyList<String>()) { content.recentEmoji() }
                main.post {
                    if (!destroyed && !secret && panel == "emoji" && body.indexOfChild(column) >= 0 &&
                        epoch == content.emojiGeneration()) {
                        items = recent
                        (grid.adapter as BaseAdapter).notifyDataSetChanged()
                    }
                }
            }
        }
        grid.adapter =
            object : BaseAdapter() {
                override fun getCount() = items.size

                override fun getItem(position: Int) = items[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (convertView as? TextView
                            ?: label("", 27f).apply {
                                layoutParams = AbsListView.LayoutParams(-1, dp(46))
                            })
                        .apply { text = items[position] }
            }
        val emojiFeedback = pressFeedback()
        grid.setOnItemClickListener { _, _, position, _ ->
            val emoji = items.getOrNull(position) ?: return@setOnItemClickListener
            emojiFeedback.click()
            pinyinContext = ""
            val committed = commitInputText(emoji)
            refreshCandidates()
            if (committed && !secret) {
                val epoch = content.emojiGeneration()
                val store = content
                val owner = java.lang.ref.WeakReference(this)
                val handler = main
                try {
                    emojiWorker.execute {
                        val failed = runCatching { store.useEmoji(emoji, epoch) }.isFailure
                        handler.post {
                            val session = owner.get() ?: return@post
                            if (!session.destroyed) {
                                if (failed) session.reportContentError()
                                else if (!session.secret && session.panel == "emoji" && session.emojiCategory == 0 &&
                                    epoch == store.emojiGeneration()) session.renderBody()
                            }
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) { reportContentError() }
            }
        }
        var down = 0f
        var startY = 0f
        var swiping = false
        grid.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) emojiFeedback.onTouch(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (grid.pointToPosition(e.x.toInt(), e.y.toInt()) != AdapterView.INVALID_POSITION)
                        emojiFeedback.onTouch(e)
                    down = e.x
                    startY = e.y
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - down) > dp(40) && abs(e.x - down) > abs(e.y - startY) * 1.4f)
                        swiping = true
                    return@setOnTouchListener swiping
                }
                MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        emojiFeedback.cancel()
                        emojiCategory =
                            (emojiCategory + if (e.x < down) 1 else -1).coerceIn(
                                0,
                                groups.lastIndex,
                            )
                        renderBody()
                        return@setOnTouchListener true
                    }
                    grid.performClick()
                }
            }
            false
        }
        if (items.isEmpty())
            column.addView(label("还没有最近使用的表情", 14f, muted), LinearLayout.LayoutParams(-1, dp(40)))
    }

    internal fun KeyboardSession.renderLayoutImpl() {
        val scroll = ScrollView(this)
        val col =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(2), dp(12), dp(4))
            }
        scroll.addView(col)
        body.addView(scroll)
        col.addView(
            label("${if(outer)"外屏" else "内屏"} · ${if(landscape)"横屏" else "竖屏"}", 13f, muted)
        )
        fun slider(name: String, min: Int, max: Int, value: Int, change: (Int) -> Unit) {
            val title = label("$name  $value", 13f)
            col.addView(title)
            col.addView(
                SeekBar(this).apply {
                    this.max = max - min
                    progress = value - min
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(b: SeekBar) {}

                            override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                                if (user) {
                                    title.text = "$name  ${p+min}"
                                    change(p + min)
                                    applyLiveLayout()
                                }
                            }

                            override fun onStopTrackingTouch(b: SeekBar) {}
                        }
                    )
                },
                LinearLayout.LayoutParams(-1, dp(32)),
            )
        }
        if (profile.floating) {
            slider(
                "宽度",
                220,
                resources.configuration.screenWidthDp,
                profile.floatingWidthDp.coerceAtMost(resources.configuration.screenWidthDp),
            ) {
                saveProfile(profile.copy(floatingWidthDp = it))
            }
            slider("高度", 200, 500, profile.floatingHeightDp.coerceAtMost(500)) {
                saveProfile(profile.copy(floatingHeightDp = it))
            }
        } else {
            slider("高度", 200, 500, profile.heightDp.coerceAtMost(500)) {
                saveProfile(profile.copy(heightDp = it))
            }
            slider(
                "两侧留白",
                0,
                (resources.configuration.screenWidthDp - 220).coerceAtLeast(0) / 2,
                profile.sideGapDp,
            ) {
                saveProfile(profile.copy(sideGapDp = it))
            }
            slider("底部留白", 0, 160, profile.bottomGapDp.coerceAtMost(160)) {
                saveProfile(profile.copy(bottomGapDp = it))
            }
        }
    }

    /** Screen bounds independent of the IME height; exclude system bars, never the IME itself. */
    internal fun KeyboardSession.floatingScreenHeightPxImpl(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusId != 0) resources.getDimensionPixelSize(statusId) else 0
        return (resources.displayMetrics.heightPixels - statusHeight).coerceAtLeast(1)
    }

    internal fun KeyboardSession.baseKeyboardHeightPxImpl(): Int =
        dp(if (profile.floating) profile.floatingHeightDp else profile.heightDp)
            .coerceAtMost(resources.displayMetrics.heightPixels * 3 / 4)

    /** A temporary panel allowance, never written to the user's layout profile. */
    internal fun KeyboardSession.translationExtraHeightPxImpl(): Int =
        if (translation.enabled && (panel == "keys" || panel in assistantTools))
            minOf(dp(if (aiMode) 198 else 138), (floatingScreenHeightPx() - baseKeyboardHeightPx()).coerceAtLeast(0))
        else 0

    internal fun KeyboardSession.applyLiveLayoutImpl() {
        val lp = box.layoutParams as FrameLayout.LayoutParams
        val usable = resources.displayMetrics.widthPixels
        val h = baseKeyboardHeightPx() + translationExtraHeightPx()
        // Match createView bounds: stored profiles may target a much larger display.
        val side = dp(profile.sideGapDp).coerceIn(0, (usable - dp(220)).coerceAtLeast(0) / 2)
        val width =
            if (profile.floating)
                dp(profile.floatingWidthDp).coerceIn(dp(220).coerceAtMost(usable), usable)
            else usable - side * 2
        val left =
            if (profile.floating)
                dp(profile.floatingXDp).coerceIn(0, (usable - width).coerceAtLeast(0))
            else side
        val bottom =
            dp(if (profile.floating) profile.floatingYDp else profile.bottomGapDp)
                .coerceIn(0, if (profile.floating) FloatingResize.maxBottom(floatingScreenHeightPx(), h)
                    else resources.displayMetrics.heightPixels / 3)
        lp.height = h
        lp.width = width
        lp.leftMargin = left
        lp.bottomMargin = bottom
        box.layoutParams = lp
        // A rebuilt root can receive a theme/layout update before the IME window attaches it.
        root.layoutParams?.height = h + bottom
        root.requestLayout()
    }

}
