package io.secure.keyboard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore

/**
 * End-to-end synthetic fixtures, run only on a dedicated test device/emulator. Uses the real
 * selected IME, editor connection, accessibility window bounds, and injected touchscreen events.
 * Does not persist or print any editor contents. A missing IME accessibility tree FAILS explicitly
 * instead of silently skipping. Does not cover vendor foldables, microphone recognition, or other
 * editors.
 */
@Suppress("DEPRECATION")
class KeyboardUiTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var editor: EditText
    private var previousIme = ""
    private var originalFourCode = false
    private var originalCompletion = true
    private var originalLongPressSymbols = true
    private var hadLanguagePreference = false
    private var originalChineseLanguage = SettingsStore.DEFAULT_CHINESE_LANGUAGE
    private val automation
        get() = instrumentation.uiAutomation

    private val context
        get() = instrumentation.targetContext

    private val lifecycle =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(current: Activity) {
                if (current is MainActivity) {
                    // Rotation recreates MainActivity: never retain its old editor connection.
                    activity = current
                    findEditor(current.window.decorView)?.let { editor = it }
                }
            }

            override fun onActivityCreated(a: Activity, state: Bundle?) {}

            override fun onActivityStarted(a: Activity) {}

            override fun onActivityPaused(a: Activity) {}

            override fun onActivityStopped(a: Activity) {}

            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}

            override fun onActivityDestroyed(a: Activity) {}
        }

    private val imeId = "io.secure.keyboard/.OfflineKeyboardService"

    override fun setUp() {
        super.setUp()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycle)
        previousIme = shell("settings get secure default_input_method").trim()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        originalFourCode = prefs.getBoolean("fourCodeAutoCommit", false)
        originalCompletion = prefs.getBoolean("englishCompletion", true)
        originalLongPressSymbols = prefs.getBoolean("longPressSymbols", true)
        hadLanguagePreference = prefs.contains("lastChineseLanguage")
        originalChineseLanguage = prefs.getBoolean("lastChineseLanguage", SettingsStore.DEFAULT_CHINESE_LANGUAGE)
        // Disable optional automatic commit to test explicit candidate selection.
        prefs
            .edit()
            .putBoolean("fourCodeAutoCommit", false)
            .putBoolean("englishCompletion", true)
            .commit()
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags =
                    flags or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        shell("ime enable $imeId")
        shell("ime set $imeId")
        waitFor("test IME is selected") {
            shell("settings get secure default_input_method").trim() == imeId
        }
        activity =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        instrumentation.runOnMainSync {
            editor =
                requireNotNull(findEditor(activity.window.decorView)) {
                    "MainActivity test EditText missing"
                }
            editor.setText("")
            editor.requestFocus()
        }
        waitFor("test activity window receives focus") {
            var focused = false
            instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
            focused
        }
        instrumentation.runOnMainSync { showKeyboard() }
        // Normalize the fixture if its initial attachment raced a previously visible session.
        waitFor("IME language control and current keyboard page appear") {
            hasImeDescription("切换中文和英文") && (hasImeDescription("ABC") || hasImeDescription("?123"))
        }
        if (hasImeDescription("ABC")) tapImeDescription("ABC")
        if (hasImeDescription("大写锁定已开启")) tapImeDescription("大写锁定已开启")
        if (hasImeDescription("单次大写已开启")) {
            tapImeDescription("单次大写已开启")
            waitFor("retained shift mode settles") {
                hasImeDescription("大写") || hasImeDescription("大写锁定已开启")
            }
            if (hasImeDescription("大写锁定已开启")) tapImeDescription("大写锁定已开启")
        }
        waitForKeyboard()
        ensureChinese()
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized)
                instrumentation.runOnMainSync {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                    activity.finish()
                }
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("fourCodeAutoCommit", originalFourCode)
                .putBoolean("englishCompletion", originalCompletion)
                .putBoolean("longPressSymbols", originalLongPressSymbols)
                .apply {
                    if (hadLanguagePreference) putBoolean("lastChineseLanguage", originalChineseLanguage)
                    else remove("lastChineseLanguage")
                }
                .commit()
            if (
                previousIme.isNotBlank() &&
                    previousIme != "null" &&
                    previousIme.all { it.isLetterOrDigit() || it in "._/$" }
            ) {
                shell("ime set $previousIme")
            }
        } finally {
            (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(
                lifecycle
            )
            super.tearDown()
        }
    }

    fun testChineseRawPreeditAndCandidateCommit() {
        val keyDescriptions = listOf("q", "a", "z", "五笔", "回车")
        val idleBounds = keyDescriptions.associateWith { imeBounds(it) }
        fun assertKeysUnmoved() {
            keyDescriptions.forEach { description ->
                assertTrue(
                    "Letter-key bounds must be identical across idle and composition",
                    idleBounds[description] == imeBounds(description),
                )
            }
        }
        typeChinese("nihao") { assertKeysUnmoved() }
        assertHostEquals("", "Chinese code must remain in keyboard-local preedit")
        waitFor("Chinese candidate ready for validation screenshot") { hasImeText("你好") }
        saveValidationScreenshot("01-chinese-local-preedit.png")
        tapImeText("你好")
        waitForHost("你好")
        assertFalse("Committed preedit should be cleared", hasImeText("nihao"))
        assertKeysUnmoved()
        typeChinese("tdu")
        assertHostEquals("你好", "Rare-character raw code must remain keyboard-local")
        waitFor("Ordinary tdu result and candidate expansion are ready") {
            hasImeText("矢") && hasImeDescription("展开候选")
        }
        // Supplementary/unsupported glyphs intentionally rank after ordinary suggestions.
        // Reach both exact reported characters through the actual expanded UI rather than
        // requiring that they all fit in the first narrow candidate-strip viewport.
        tapImeDescription("展开候选")
        waitFor("Expanded tdu candidates open") { hasImeDescription("全部候选") }
        revealExpandedCandidate("𥝚")
        assertHostEquals("你好", "Browsing the first rare glyph must not commit raw code")
        saveValidationScreenshot("17-tdu-exact-glyph-first.png")
        revealExpandedCandidate("𥬇")
        assertHostEquals("你好", "Browsing the second rare glyph must not commit raw code")
        saveValidationScreenshot("17-tdu-exact-glyph-candidates.png")
        revealExpandedCandidate("𥝚", forward = false)
        tapIme { isExpandedCandidate(it, "𥝚") }
        waitForHost("你好𥝚")
        waitForKeyboard()
        assertKeysUnmoved()
    }

    fun testClearPendingDoesNotDeleteCommittedHost() {
        typeChinese("nihao")
        tapImeText("你好")
        waitForHost("你好")
        typeChinese("zhong")
        assertHostEquals("你好", "Pending code must not alter committed host")
        tapImeDescription("取消待输入内容")
        waitFor("pending-code clear affordance disappears") { !hasImeDescription("取消待输入内容") }
        assertHostEquals("你好", "Clear must only cancel local composition")
    }

    fun testEnglishCurrentWordCompletionWithoutNextWord() {
        tapImeDescription("切换中文和英文")
        waitFor("English mode") { hasImeDescription("English") }
        tapImeDescription("h")
        waitForHost("h")
        tapImeDescription("e")
        waitForHost("he")
        tapImeDescription("l")
        waitForHost("hel")
        revealImeCandidate("hello")
        tapImeText("hello")
        waitForHost("hello ")
        waitFor("no candidate-clear control after a word boundary") {
            !hasImeDescription("取消待输入内容")
        }
        assertFalse("No next-word suggestion may retain hello", hasImeText("hello"))
        assertHostEquals("hello ", "English completion should append one space")
    }

    fun testCustomPaletteRendersIndividualRolesAndReset() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("theme", "system")
        val oldHints = prefs.getString("symbolHints", "all")
        val hadColors = prefs.contains("themeTokens_light")
        val oldColors = prefs.getString("themeTokens_light", null)
        val colors =
            mapOf(
                "keyboardBackground" to "#16324A",
                "normalKeyBackground" to "#CCDDAA",
                "specialKeyBackground" to "#AACCFF",
                "text" to "#661144",
                "hint" to "#224466",
                "toolbarIcon" to "#33DD99",
                "candidate" to "#FFCC44",
                "candidateSelected" to "#EE66DD",
                "accent" to "#448833",
                "enterText" to "#FFFFCC",
                "preedit" to "#66DDFF",
                "divider" to "#99AA11",
                "specialKeyText" to "#AA2255",
                "selectedKeyBackground" to "#773388",
                "selectedKeyText" to "#CCFF66",
            )
        fun expect(bitmap: Bitmap, bounds: Rect, vararg roles: String) {
            roles.forEach { role ->
                val target = Color.parseColor(requireNotNull(colors[role]))
                var hits = 0
                for (y in maxOf(0, bounds.top) until minOf(bitmap.height, bounds.bottom)) for (x in
                    maxOf(0, bounds.left) until minOf(bitmap.width, bounds.right)) if (
                    bitmap.getPixel(x, y) == target
                )
                    hits++
                assertTrue("Configured $role must be painted in its actual control", hits >= 2)
            }
        }
        try {
            prefs
                .edit()
                .putString("theme", "light")
                .putString("symbolHints", "all")
                .putString("themeTokens_light", JSONObject(colors).toString())
                .commit()
            waitFor("custom palette live rebuild paints normal key") {
                val key = imeBounds("q")
                val bitmap = automation.takeScreenshot() ?: return@waitFor false
                try {
                    bitmap.getPixel(key.centerX(), key.top + maxOf(2, key.height() / 8)) ==
                        Color.parseColor(colors.getValue("normalKeyBackground"))
                } finally {
                    bitmap.recycle()
                }
            }
            var bitmap = requireNotNull(automation.takeScreenshot())
            try {
                expect(bitmap, imeBounds("q"), "normalKeyBackground", "text", "hint")
                expect(bitmap, imeBounds("?123"), "specialKeyBackground", "specialKeyText")
                expect(bitmap, imeBounds("回车"), "accent", "enterText")
                expect(bitmap, imeBounds("更多工具"), "toolbarIcon", "keyboardBackground")
            } finally {
                bitmap.recycle()
            }
            saveValidationScreenshot("16-custom-palette-idle.png")
            tapImeDescription("大写")
            waitFor("selected shift for palette check") { hasImeDescription("单次大写已开启") }
            bitmap = requireNotNull(automation.takeScreenshot())
            try {
                expect(bitmap, imeBounds("单次大写已开启"), "selectedKeyBackground", "selectedKeyText")
            } finally {
                bitmap.recycle()
            }
            tapImeDescription("单次大写已开启")
            waitFor("shift toggle settles before composition palette check") {
                hasImeDescription("大写") || hasImeDescription("大写锁定已开启")
            }
            if (hasImeDescription("大写锁定已开启")) tapImeDescription("大写锁定已开启")
            waitFor("shift off before composition palette check") { hasImeDescription("大写") }
            openMoreTool("快捷编辑")
            tapImeDescription("选择")
            waitFor("selection selected for accent palette check") { quickSelectionEnabled() }
            bitmap = requireNotNull(automation.takeScreenshot())
            try { expect(bitmap, imeBounds("选择"), "accent", "enterText") }
            finally { bitmap.recycle() }
            saveValidationScreenshot("18-custom-palette-selection-active.png")
            tapImeDescription("选择")
            bitmap = requireNotNull(automation.takeScreenshot())
            try { expect(bitmap, imeBounds("选择"), "normalKeyBackground", "text") }
            finally { bitmap.recycle() }
            tapImeDescription("返回键盘")
            waitForKeyboard()
            typeChinese("ni")
            waitFor("candidate colors are available") { hasImeText("你") }
            val preedit = requireImeNode { it.text?.toString() == "ni" }
            val bounds = Rect()
            try {
                preedit.getBoundsInScreen(bounds)
            } finally {
                preedit.recycle()
            }
            bitmap = requireNotNull(automation.takeScreenshot())
            try {
                expect(bitmap, bounds, "preedit")
                expect(
                    bitmap,
                    Rect(0, bounds.bottom, bitmap.width, imeBounds("q").top),
                    "candidate",
                    "candidateSelected",
                )
            } finally {
                bitmap.recycle()
            }
            saveValidationScreenshot("16-custom-palette-composition.png")
            tapImeDescription("取消待输入内容")
            prefs.edit().remove("themeTokens_light").commit()
            waitFor("reset palette restores default light key") {
                val key = imeBounds("q")
                val image = automation.takeScreenshot() ?: return@waitFor false
                try {
                    image.getPixel(key.centerX(), key.top + maxOf(2, key.height() / 8)) ==
                        Color.WHITE
                } finally {
                    image.recycle()
                }
            }
        } finally {
            if (hasImeDescription("取消待输入内容")) tapImeDescription("取消待输入内容")
            prefs
                .edit()
                .apply {
                    putString("theme", oldTheme)
                    putString("symbolHints", oldHints)
                    if (hadColors) putString("themeTokens_light", oldColors)
                    else remove("themeTokens_light")
                }
                .commit()
        }
    }

    fun testChineseShiftAndCapsPreserveLocalComposition() {
        tapImeDescription("大写")
        waitFor("one-shot uppercase enabled") { hasImeDescription("单次大写已开启") }
        tapImeDescription("A")
        waitForHost("A")
        waitFor("one-shot uppercase releases") { hasImeDescription("大写") }
        typeChinese("q")
        assertHostEquals("A", "Lowercase returns to local Chinese code")
        tapImeDescription("取消待输入内容")
        waitFor("local code cleared before caps fixture") { !hasImeDescription("取消待输入内容") }
        val bounds = imeBounds("大写")
        // Actual event times, asynchronous injection, and a short real inter-tap interval.
        // Waiting on accessibility after the first tap can exceed the 400ms double-tap window.
        var firstTap = 0L
        repeat(2) { index ->
            val downAt = SystemClock.uptimeMillis()
            if (index == 0) firstTap = downAt
            else
                assertTrue(
                    "Injected double tap must fit the real 400ms gesture window",
                    downAt - firstTap < 400,
                )
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event =
                    MotionEvent.obtain(
                            downAt,
                            SystemClock.uptimeMillis(),
                            action,
                            bounds.exactCenterX(),
                            bounds.exactCenterY(),
                            0,
                        )
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(automation.injectInputEvent(event, false))
                } finally {
                    event.recycle()
                }
                SystemClock.sleep(20)
            }
        }
        instrumentation.waitForIdleSync()
        waitFor("caps lock enabled") { hasImeDescription("大写锁定已开启") }
        tapImeDescription("A")
        tapImeDescription("B")
        waitForHost("AAB")
        assertTrue("Caps stays locked after two letters", hasImeDescription("大写锁定已开启"))
        tapImeDescription("大写锁定已开启")
        waitFor("caps unlock restores lowercase") {
            hasImeDescription("大写") && hasImeDescription("q")
        }
        typeChinese("q")
        assertHostEquals("AAB", "Unlocked q stays local")
        tapImeDescription("取消待输入内容")
        waitFor("clear before pending-shift check") { !hasImeDescription("取消待输入内容") }
        typeChinese("ni")
        tapImeDescription("大写")
        assertTrue("Shift must preserve pending Chinese code", hasImeText("ni"))
        assertTrue("Shift remains off while local code is pending", hasImeDescription("大写"))
        assertHostEquals("AAB", "Pending Shift must not commit or delete host content")
        saveValidationScreenshot("15-chinese-shift-pending.png")
    }

    fun testEnglishCompletionInOfflineWebView() {
        lateinit var web: WebView
        val pageReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageReady.countDown()
                            }
                        }
                }
            activity.setContentView(web)
            web.loadDataWithBaseURL(
                null,
                "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><input id='fixture' aria-label='Offline English fixture' autocomplete='off' autocorrect='off' style='font-size:24px;width:90%;height:80px'></body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        assertTrue("Local WebView fixture loads", pageReady.await(60, TimeUnit.SECONDS))
        try {
            var hostNode: AccessibilityNodeInfo? = null
            waitFor("WebView editable accessibility node") {
                for (window in automation.windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    hostNode = findNode(root) { it.isEditable }
                    if (hostNode != null) break
                }
                hostNode != null
            }
            val editable = requireNotNull(hostNode)
            try {
                assertTrue(
                    "WebView host accepts focus click",
                    editable.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
            } finally {
                editable.recycle()
            }
            waitFor("WebView HTML input receives focus") {
                webValue(web, "document.activeElement.id") == "\"fixture\""
            }
            instrumentation.runOnMainSync {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
            }
            waitForKeyboard()
            if (!hasImeDescription("English")) tapImeDescription("切换中文和英文")
            waitFor("English WebView mode") { hasImeDescription("English") }
            for (letter in "hel") tapImeDescription(letter.toString())
            waitFor("WebView hel commits immediately") { webValue(web) == "\"hel\"" }
            revealImeCandidate("hello")
            tapImeText("hello")
            waitFor("WebView completion replaces prefix and adds space") { webValue(web) == "\"hello \"" }
            tapImeDescription("删除") // Remove automatic word-boundary space.
            tapImeDescription("删除")
            waitFor("WebView backspace changes current word") { webValue(web) == "\"hell\"" }
            waitFor("WebView completion refreshes after backspace") { hasImeText("hello") }
            tapImeText("hello")
            waitFor("WebView word boundary") { webValue(web) == "\"hello \"" }
            waitFor("WebView has no next-word prediction") { !hasImeDescription("取消待输入内容") }
            tapImeDescription("w")
            waitFor("One-letter current-word completion visible in WebView") {
                hasImeDescription("取消待输入内容")
            }
            assertEquals("\"hello w\"", webValue(web))
            tapImeDescription("删除")
            for (letter in "compu") tapImeDescription(letter.toString())
            waitFor("Common vocabulary beyond hello works") { hasImeText("computer") }
            saveValidationScreenshot("14-offline-webview-english-candidates.png")
            tapImeText("computer")
            waitFor("Common completion commits exact WebView value") {
                webValue(web) == "\"hello computer \""
            }
            tapImeDescription("大写")
            tapImeDescription("H")
            tapImeDescription("e")
            tapImeDescription("l")
            revealImeCandidate("Hello")
            tapImeText("Hello")
            waitFor("Title-case prefix is replaced exactly") {
                webValue(web) == "\"hello computer Hello \""
            }
            webValue(web, "document.getElementById('fixture').setSelectionRange(2,2); 'selected'")
            waitFor("Middle-of-word WebView suppresses completion") {
                !hasImeDescription("取消待输入内容") && hasImeDescription("更多工具")
            }
            assertEquals("\"hello computer Hello \"", webValue(web))
            saveValidationScreenshot("14-offline-webview-english.png")
        } finally {
            instrumentation.runOnMainSync {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(web.windowToken, 0)
                web.destroy()
            }
        }
    }

    private fun revealImeCandidate(text: String) {
        waitFor("current-word candidate strip is populated") { hasImeDescription("取消待输入内容") }
        repeat(12) {
            if (hasImeText(text)) return
            val scroller = requireImeNode {
                it.className?.toString() == "android.widget.HorizontalScrollView" && it.isScrollable
            }
            try {
                val event =
                    automation.executeAndWaitForEvent(
                        {
                            assertTrue(
                                "Candidate strip scrolls forward",
                                scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                            )
                        },
                        { it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED },
                        60000,
                    )
                event.recycle()
            } finally {
                scroller.recycle()
            }
            instrumentation.waitForIdleSync()
        }
        fail("Expected current-word candidate was not reachable in the candidate strip")
    }

    private fun isExpandedCandidate(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() != text) return false
        val parent = node.parent ?: return false
        try {
            if (parent.contentDescription?.toString() != "全部候选") return false
            val gridBounds = Rect().also { parent.getBoundsInScreen(it) }
            val cellBounds = Rect().also { node.getBoundsInScreen(it) }
            return !cellBounds.isEmpty && gridBounds.contains(cellBounds)
        } finally {
            parent.recycle()
        }
    }

    private fun revealExpandedCandidate(text: String, forward: Boolean = true) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        while (SystemClock.uptimeMillis() < deadline) {
            if (hasIme { isExpandedCandidate(it, text) }) return
            val grid = requireImeNode { it.contentDescription?.toString() == "全部候选" }
            try {
                val event = automation.executeAndWaitForEvent(
                    { assertTrue("The exact rare candidate must remain reachable in the expanded grid",
                        grid.performAction(action)) },
                    { it.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED },
                    10_000,
                )
                event.recycle()
            } finally {
                grid.recycle()
            }
            instrumentation.waitForIdleSync()
        }
        saveValidationScreenshot("failure-tdu-expanded-glyph.png")
        fail("Exact rare candidate was not reachable through expanded-grid scrolling")
    }

    private fun webValue(
        web: WebView,
        script: String = "document.getElementById('fixture').value",
    ): String {
        val completed = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { result ->
                value = result
                completed.countDown()
            }
        }
        assertTrue(
            "Local WebView JavaScript callback completes",
            completed.await(30, TimeUnit.SECONDS),
        )
        return value
    }

    fun testMoreAndQuickEditPanelsScreenshots() {
        assertHostEquals("", "Screenshot fixture starts with an empty synthetic editor")
        val idleSlotCount = toolbarSlotBounds("更多工具").size
        tapImeDescription("更多工具")
        waitFor("More tools panel visible") {
            hasImeDescription("布局调整") && hasImeDescription("悬浮键盘")
        }
        assertFalse("More panel must not show the toolbar editing button", hasImeText("编辑工具栏"))
        assertPanelToolbar(idleSlotCount)
        saveValidationScreenshot("02-more-tools.png")
        tapImeDescription("快捷编辑")
        waitFor("Quick Edit grid visible") {
            hasImeText("全选") &&
                hasImeText("剪切") &&
                hasImeText("复制") &&
                hasImeText("粘贴") &&
                hasImeDescription("向上移动") &&
                hasImeDescription("向下移动") &&
                hasImeDescription("向左移动") &&
                hasImeDescription("向右移动") &&
                hasImeDescription("选择")
        }
        assertPanelToolbar(idleSlotCount)
        saveValidationScreenshot("03-quick-edit.png")
        assertHostEquals("", "Opening panels must not insert editor text")
        assertFalse("Selection initially off", quickSelectionEnabled())
        tapImeDescription("选择")
        waitFor("selection mode activates") { quickSelectionEnabled() }
        saveValidationScreenshot("13-quick-edit-selection-active.png")
        tapImeDescription("选择")
        waitFor("selection mode exits") { !quickSelectionEnabled() }
        assertHostEquals("", "Selection toggle must never insert editor text")
        tapImeDescription("返回键盘")
        waitForKeyboard()
    }

    fun testQuickEditSelectionGraphemeAnchorShrinkAndExit() {
        val astronaut = "👩‍🚀"
        val combining = "e\u0301"
        val fixture = "A${astronaut}${combining}B"
        prepareQuickEditFixture(fixture, 1)
        val emojiEnd = 1 + astronaut.length
        val combiningEnd = emojiEnd + combining.length
        tapImeDescription("选择")
        tapImeDescription("向右移动")
        waitForSelection(1, emojiEnd)
        tapImeDescription("向右移动")
        waitForSelection(1, combiningEnd)
        tapImeDescription("向左移动")
        waitForSelection(1, emojiEnd)
        tapImeDescription("向左移动")
        waitForSelection(1, 1)
        tapImeDescription("向左移动")
        waitForSelection(1, 0)
        tapImeDescription("向右移动")
        waitForSelection(1, 1)
        tapImeDescription("向右移动")
        waitForSelection(1, emojiEnd)
        tapImeDescription("选择")
        assertFalse("Second tap releases mode", quickSelectionEnabled())
        waitForSelection(1, emojiEnd)
        tapImeDescription("向右移动")
        waitForSelection(emojiEnd, emojiEnd)
        tapImeDescription("向右移动")
        waitForSelection(combiningEnd, combiningEnd)
        assertHostEquals(fixture, "All arrows and toggles preserve host text")
        saveValidationScreenshot("18-quick-edit-grapheme-selection.png")
    }

    fun testQuickEditSelectionVisualLinesPanelHideAndEditorResets() {
        val fixture = "abcdefghij ".repeat(18) + "\n第二行👩‍🚀文字\nlast line"
        prepareQuickEditFixture(fixture, 3)
        var expectedDown = 0 to 0
        var expectedUp = 0 to 0
        instrumentation.runOnMainSync {
            val layout = requireNotNull(editor.layout)
            assertTrue("Fixture must contain soft-wrapped visual lines", layout.lineCount > 3)
            android.text.Selection.extendDown(editor.text, layout)
            expectedDown = editor.selectionStart to editor.selectionEnd
            assertTrue("Down must advance one host visual line",
                layout.getLineForOffset(expectedDown.second) > layout.getLineForOffset(3))
            android.text.Selection.extendUp(editor.text, layout)
            expectedUp = editor.selectionStart to editor.selectionEnd
            editor.setSelection(3)
        }
        tapImeDescription("选择")
        tapImeDescription("向下移动")
        waitForSelection(expectedDown.first, expectedDown.second)
        saveValidationScreenshot("18-quick-edit-visual-line-selection-active.png")
        tapImeDescription("向上移动")
        waitForSelection(expectedUp.first, expectedUp.second)
        assertHostEquals(fixture, "Visual-line selection does not rewrite host text")

        tapImeDescription("返回键盘")
        waitForKeyboard()
        openMoreTool("快捷编辑")
        assertFalse("Leaving the panel releases selection mode", quickSelectionEnabled())
        tapImeDescription("选择")
        tapImeDescription("收起键盘")
        waitFor("Selection keyboard hides") { !hasImeDescription("收起键盘") }
        instrumentation.runOnMainSync { editor.requestFocus(); showKeyboard() }
        // Showing the same input session preserves its panel. Verify Select is already off
        // in that retained panel, before navigation could itself reset the selection state.
        waitFor("The same editor reopens Quick Edit or the alphabet keyboard") {
            (hasImeDescription("选择") && hasImeDescription("向右移动") && hasImeDescription("返回键盘")) ||
                (hasImeDescription("q") && hasImeDescription("切换中文和英文"))
        }
        if (!hasImeDescription("选择")) {
            waitForKeyboard()
            openMoreTool("快捷编辑")
        }
        assertFalse("Hiding the keyboard releases selection mode", quickSelectionEnabled())

        tapImeDescription("选择")
        val previous = editor
        instrumentation.runOnMainSync {
            val replacement = EditText(activity).apply {
                setSingleLine(false)
                setText("replacement editor")
                setSelection(4)
            }
            (previous.parent as ViewGroup).addView(replacement)
            editor = replacement
            editor.requestFocus()
            showKeyboard()
        }
        // A different editor runs onStartInput, which deliberately resets the panel to keys.
        waitForKeyboard()
        waitForSelection(4, 4)
        openMoreTool("快捷编辑")
        assertFalse("Changing the input target releases selection mode", quickSelectionEnabled())
        tapImeDescription("向右移动")
        waitForSelection(5, 5)
        instrumentation.runOnMainSync {
            assertEquals("Original editor is never changed by the new target", fixture, previous.text.toString())
        }
    }

    private fun prepareQuickEditFixture(text: String, cursor: Int) {
        instrumentation.runOnMainSync {
            editor.setText(text)
            editor.setSelection(cursor)
            editor.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .restartInput(editor)
            showKeyboard()
        }
        waitForKeyboard()
        ensureChinese()
        openMoreTool("快捷编辑")
        waitForSelection(cursor, cursor)
    }

    private fun waitForSelection(start: Int, end: Int) =
        waitFor("native host selection reaches requested endpoints") {
            var matches = false
            instrumentation.runOnMainSync {
                matches = editor.selectionStart == start && editor.selectionEnd == end
            }
            matches
        }

    private fun quickSelectionEnabled(): Boolean {
        val node = requireImeNode { it.contentDescription?.toString() == "选择" }
        return try { node.isSelected } finally { node.recycle() }
    }

    fun testIdleToolbarEqualWidthsAndCenteredMergedPunctuationRow() {
        val slots = toolbarSlotBounds("更多工具")
        assertTrue("Toolbar must have at least More and Hide", slots.size >= 2)
        assertEquals("More must be the first toolbar slot", "更多工具", slots.first().first)
        assertEquals("Hide must be the last toolbar slot", "收起键盘", slots.last().first)
        val widths = slots.map { it.second.width() }
        assertTrue(
            "Idle toolbar slots must have equal widths within two pixels",
            widths.maxOrNull()!! - widths.minOrNull()!! <= 2,
        )
        assertTrue(
            "Toolbar slots must remain in left-to-right order",
            slots.zipWithNext().all { (left, right) -> left.second.right <= right.second.left + 2 },
        )

        val space = imeBounds("五笔")
        val punctuation = imeBounds("，。")
        val symbols = imeBounds("?123")
        val globe = imeBounds("切换中文和英文")
        val enter = imeBounds("回车")
        val window =
            requireNotNull(
                automation.windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
            ) {
                "IME window bounds unavailable"
            }
        val imeRect = Rect()
        window.getBoundsInScreen(imeRect)
        assertTrue(
            "Spacebar must be centered in the full-width IME within two pixels",
            kotlin.math.abs(space.exactCenterX() - imeRect.exactCenterX()) <= 2f,
        )
        assertTrue(
            "Merged punctuation must sit left of space",
            symbols.right <= punctuation.left && punctuation.right <= space.left,
        )
        assertTrue(
            "Only globe and enter follow space",
            space.right <= globe.left && globe.right <= enter.left,
        )
        val rowWidth = enter.right - symbols.left
        val bottomWeights = listOf(17f, 9f, 42f, 9f, 17f)
        listOf(symbols, punctuation, space, globe, enter)
            .zip(bottomWeights)
            .forEach { (bounds, weight) ->
                assertTrue(
                    "Bottom-row cell must use its balanced compact-control width",
                    kotlin.math.abs(bounds.width() - rowWidth * weight / bottomWeights.sum()) <= 2f,
                )
            }
        assertTrue("Punctuation is narrower than the outer mode key", punctuation.width() < symbols.width())
        assertTrue("Globe is narrower than Enter", globe.width() < enter.width())
        assertTrue("Mode and Enter match", kotlin.math.abs(symbols.width() - enter.width()) <= 1)
        assertTrue("Punctuation and globe match", kotlin.math.abs(punctuation.width() - globe.width()) <= 1)
        val rowCenter = space.centerY()
        assertTrue(
            "Bottom-row controls must share vertical center",
            listOf(punctuation, symbols, globe, enter).all {
                kotlin.math.abs(it.centerY() - rowCenter) <= 2
            },
        )
        assertFalse(
            "Alphabet page must not retain a separate Chinese period key",
            hasImeDescription("。"),
        )
        assertFalse(
            "Alphabet page must not retain a separate ASCII period key",
            hasImeDescription("."),
        )
    }

    fun testExactSymbolPagesInsertLiteralCharactersAndCenterShortRows() {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("longPressSymbols", false)
            .commit()
        val pages = listOf(
            listOf(
                listOf("1234567890", "@#${'$'}_&-+（）/", "、“”：；！？"),
                listOf("~`|•《》÷×*§", ".,€¥^°=「」\\", "%‘’™✓［］"),
            ),
            listOf(
                listOf("1234567890", "@#${'$'}_&-+()/", "*\"':;!?"),
                listOf("~`|•<>÷×§∆", ".,€¥^°={}\\", "%©®™✓[]"),
            ),
        )
        fun checkPage(rows: List<String>) {
            val bounds = rows.mapIndexed { index, row ->
                val expectedKeys = row.map { it.toString() }
                val first = requireImeNode { it.contentDescription?.toString() == expectedKeys.first() }
                val parent = try { requireNotNull(first.parent) } finally { first.recycle() }
                val actualKeys = mutableListOf<String>()
                try {
                    for (childIndex in 0 until parent.childCount) {
                        val child = parent.getChild(childIndex) ?: continue
                        try {
                            child.contentDescription?.toString()?.let { actualKeys.add(it) }
                        } finally { child.recycle() }
                    }
                } finally { parent.recycle() }
                val controls = if (index == 2) listOf("=\\<") + expectedKeys + "删除" else expectedKeys
                assertEquals("Exactly one separate key per required symbol, in row order", controls, actualKeys)
                expectedKeys.map { imeBounds(it) }.also { cells ->
                    assertTrue("All symbol cells have positive width", cells.all { it.width() > 0 })
                    cells.zipWithNext().forEach { (left, right) ->
                        assertTrue("Symbols must be ordered left to right without overlapping", left.right <= right.left)
                        assertEquals("Symbols stay on their requested row", left.top, right.top)
                    }
                }
            }
            val fullRow = bounds.first()
            val shortRow = bounds.last()
            val center = (fullRow.first().left + fullRow.last().right) / 2f
            assertTrue(
                "Seven-symbol row remains centered between page toggle and backspace",
                kotlin.math.abs((shortRow.first().left + shortRow.last().right) / 2f - center) <= 2f,
            )
            val referenceWidth = fullRow.first().width()
            assertTrue("First two symbol rows retain ten-key cell widths", bounds.take(2).flatten().all {
                kotlin.math.abs(it.width() - referenceWidth) <= 2
            })
            assertTrue("Third-row symbols expand like the seven-letter row", shortRow.all { it.width() >= referenceWidth })
            assertEquals("Page toggle remains at the left edge", fullRow.first().left, imeBounds("=\\<").left)
            assertEquals("Backspace remains at the right edge", fullRow.last().right, imeBounds("删除").right)
            assertTrue("Return-to-letters key remains available", hasImeDescription("ABC"))
            assertTrue("Enter remains available", hasImeDescription("回车"))
        }
        try {
            // A carried single Shift must never turn the requested π key into Π.
            tapImeDescription("大写")
            tapImeDescription("?123")
            if (!hasImeDescription("π")) tapImeDescription("=\\<")
            waitFor("Literal lowercase pi is available even with carried Shift") { hasImeDescription("π") }
            assertFalse("Symbol pages never apply letter case conversion", hasImeDescription("Π"))
            tapImeDescription("π")
            waitForHost("π")
            tapImeDescription("删除")
            waitForHost("")
            tapImeDescription("=\\<")
            waitFor("Chinese primary symbol page") { hasImeDescription("1") }
            var expectedHost = ""
            for ((languageIndex, languagePages) in pages.withIndex()) {
                if (languageIndex == 1) {
                    tapImeDescription("切换中文和英文")
                    waitFor("English alphabet page after language switch") {
                        hasImeDescription("English") && hasImeDescription("?123")
                    }
                    tapImeDescription("?123")
                    waitFor("English primary symbol page") { hasImeDescription("1") }
                }
                for ((pageIndex, rows) in languagePages.withIndex()) {
                    checkPage(rows)
                    saveValidationScreenshot("symbol-exact-${if (languageIndex == 0) "chinese" else "english"}-${pageIndex + 1}.png")
                    for (row in rows) {
                        for (symbol in row) {
                            // These four English keys now insert pairs and return to letters;
                            // KeyboardEnglishSymbolPairTest covers their host/cursor behavior.
                            if (languageIndex == 1 && symbol in "\"'{[") continue
                            tapImeDescription(symbol.toString())
                            expectedHost += symbol
                        }
                        waitForHost(expectedHost)
                    }
                    tapImeDescription("删除")
                    waitForHost(expectedHost.dropLast(1))
                    tapImeDescription(rows.last().last().toString())
                    waitForHost(expectedHost)
                    tapImeDescription("=\\<")
                    waitFor("Symbol page toggle changes the actual keys") {
                        hasImeDescription(if (pageIndex == 0) "~" else "1")
                    }
                }
                // Re-entering symbols must start on page one even after leaving page two.
                tapImeDescription("=\\<")
                waitFor("Second symbol page before returning to letters") { hasImeDescription("~") }
                tapImeDescription("ABC")
                waitForKeyboard()
                assertTrue("Return-to-letters restores the symbols entry key", hasImeDescription("?123"))
                tapImeDescription("?123")
                waitFor("Re-entering symbols resets to page one in either language") { hasImeDescription("1") }
                assertFalse("Re-entry must not retain the second symbol page", hasImeDescription("~"))
                checkPage(languagePages.first())
                tapImeDescription("ABC")
                waitForKeyboard()
                assertHostEquals(expectedHost, "Page and language controls must not insert text")
            }
        } finally {
            if (hasImeDescription("ABC")) {
                tapImeDescription("ABC")
                waitForKeyboard()
            }
        }
    }

    fun testAlphabetCornerHintModesPreserveLongPressAndPunctuation() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val hadHints = prefs.contains("symbolHints")
        val savedHints = prefs.getString("symbolHints", "all")
        val savedLongPress = prefs.getBoolean("longPressSymbols", true)
        try {
            prefs.edit().putBoolean("longPressSymbols", true).commit()
            for (mode in listOf("all", "digits", "hidden")) {
                prefs.edit().putString("symbolHints", mode).commit()
                reopenKeyboardForSettings()
                val qTexts = imeControlTexts("q")
                val aTexts = imeControlTexts("a")
                assertTrue("Alphabet q label must stay visible", "q" in qTexts)
                assertTrue("Alphabet a label must stay visible", "a" in aTexts)
                assertEquals(
                    "q number hint must follow selected mode",
                    mode != "hidden",
                    "1" in qTexts,
                )
                assertEquals(
                    "a punctuation hint must follow selected mode",
                    mode == "all",
                    "！" in aTexts,
                )
                val punctuationTexts = imeControlTexts("，。")
                assertTrue(
                    "Merged main punctuation must remain visible in every hint mode",
                    "，" in punctuationTexts,
                )
                assertTrue(
                    "Merged alternate punctuation must remain visible in every hint mode",
                    "。" in punctuationTexts,
                )
                saveValidationScreenshot("10-corner-hints-$mode.png")
            }
            // Hidden is a display preference, not a change to long-press mappings.
            val bounds = imeBounds("a")
            val downTime = SystemClock.uptimeMillis()
            fun gesture(action: Int) {
                val event =
                    MotionEvent.obtain(
                            downTime,
                            SystemClock.uptimeMillis(),
                            action,
                            bounds.exactCenterX(),
                            bounds.exactCenterY(),
                            0,
                        )
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(
                        "Hidden-hint long-press injection succeeds",
                        automation.injectInputEvent(event, true),
                    )
                } finally {
                    event.recycle()
                }
            }
            gesture(MotionEvent.ACTION_DOWN)
            try {
                // This non-focusable/non-touchable popup need not be present in
                // accessibility's interactive windows. Hold the configured gesture
                // duration, flush its main-thread callback, and assert the actual
                // committed result after release rather than requiring a popup node.
                val holdDuration = prefs.getInt("longPressDelay", 400).coerceIn(150, 1500) + 100L
                SystemClock.sleep(holdDuration)
                instrumentation.waitForIdleSync()
                saveValidationScreenshot("12-hidden-hold.png")
            } finally {
                gesture(MotionEvent.ACTION_UP)
            }
            waitForHost("！")
            assertHostEquals("！", "Hiding alphabet hints must not disable long-press punctuation")
        } finally {
            val edit = prefs.edit().putBoolean("longPressSymbols", savedLongPress)
            if (hadHints) edit.putString("symbolHints", savedHints) else edit.remove("symbolHints")
            edit.commit()
            if (hasImeDescription("收起键盘")) reopenKeyboardForSettings()
        }
    }

    private fun reopenKeyboardForSettings() {
        // During composition the same far-right arrow expands candidates. Cancel the pending
        // content first when this fixture specifically intends to hide/reopen the keyboard.
        if (hasImeDescription("取消待输入内容")) tapImeDescription("取消待输入内容")
        tapImeDescription("收起键盘")
        waitFor("keyboard hides before settings refresh") { !hasImeDescription("收起键盘") }
        instrumentation.runOnMainSync {
            editor.requestFocus()
            showKeyboard()
        }
        waitForKeyboard()
    }

    private fun imeControlTexts(description: String): Set<String> {
        val root = requireImeNode { it.contentDescription?.toString() == description }
        val texts = mutableSetOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            try {
                if (node.isVisibleToUser) node.text?.toString()?.let { texts.add(it) }
                for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it) }
            } finally {
                node.recycle()
            }
        }
        collect(root)
        return texts
    }

    fun testSpaceSwipeMovesByGraphemeAndCancelsLocalPreedit() {
        val fixture = "A😀B"
        tapImeDescription("收起键盘")
        waitFor("keyboard hides before cursor fixture installation") { !hasImeDescription("收起键盘") }
        instrumentation.runOnMainSync {
            // Install before reopening: TextView.setText can request input restart.
            editor.setText(fixture)
            editor.setSelection(fixture.length)
            editor.requestFocus()
            showKeyboard()
        }
        waitForKeyboard()
        ensureChinese()
        waitForHost(fixture)
        waitForSelection(4)
        val oneStep = 18f * context.resources.displayMetrics.density * 1.25f
        fun swipe(steps: Int, expected: Int) {
            dragImeControl("五笔", oneStep * steps, 0f) { assertSelectionAtGraphemeBoundary() }
            waitForSelection(expected)
            assertHostEquals(fixture, "Space swipe must preserve text and never add a space")
            assertSelectionAtGraphemeBoundary()
        }
        swipe(-1, 3) // Before B, after the complete supplementary character.
        swipe(-1, 1) // Across the emoji in one move, never its surrogate midpoint 2.
        swipe(1, 3)
        swipe(-2, 0)
        swipe(2, 3)
        typeChinese("ni")
        assertHostEquals(fixture, "Pending Chinese code stays local before cursor gesture")
        swipe(-1, 1)
        waitFor("space swipe cancels pending Chinese composition") {
            !hasImeDescription("取消待输入内容") && !hasImeText("ni")
        }
        assertHostEquals(
            fixture,
            "Cancelling local composition must not commit code, a candidate, or space",
        )
        saveValidationScreenshot("11-space-swipe-grapheme-cursor.png")
        typeChinese("nihao")
        waitFor("candidate ready for ordinary space tap") { hasImeText("你好") }
        tapImeDescription("五笔")
        waitForHost("A你好😀B")
        assertFalse(
            "Ordinary space tap commits and clears local composition",
            hasImeDescription("取消待输入内容"),
        )

        // Exercise host-native DPAD/DEL against multi-codepoint graphemes as well.
        val family = "👨‍👩‍👧‍👦"
        val combining = "e\u0301"
        val clusters = "A" + family + combining + "B"
        val familyEnd = 1 + family.length
        val combiningEnd = familyEnd + combining.length
        val boundaries = setOf(0, 1, familyEnd, combiningEnd, clusters.length)
        tapImeDescription("收起键盘")
        waitFor("keyboard hides before complex-grapheme fixture") { !hasImeDescription("收起键盘") }
        instrumentation.runOnMainSync {
            editor.setText(clusters)
            editor.setSelection(clusters.length)
            editor.requestFocus()
            showKeyboard()
        }
        waitForKeyboard()
        ensureChinese()
        waitForHost(clusters)
        waitForSelection(clusters.length)
        fun complexSwipe(step: Int, expected: Int) {
            dragImeControl("五笔", oneStep * step, 0f) {
                assertSelectionAtGraphemeBoundary(boundaries)
            }
            waitForSelection(expected)
            assertSelectionAtGraphemeBoundary(boundaries)
            assertHostEquals(clusters, "Cursor movement must preserve ZWJ and combining clusters")
        }
        for (expected in listOf(combiningEnd, familyEnd, 1, 0)) complexSwipe(-1, expected)
        for (expected in listOf(1, familyEnd, combiningEnd, clusters.length)) complexSwipe(1, expected)
        saveValidationScreenshot("11-space-swipe-family-combining.png")
        tapImeDescription("删除")
        waitForHost("A" + family + combining)
        // Android's native DEL removes the combining mark before its base letter.
        tapImeDescription("删除")
        waitForHost("A" + family + "e")
        tapImeDescription("删除")
        waitForHost("A" + family)
        tapImeDescription("删除")
        waitForHost("A")
        saveValidationScreenshot("11-host-delete-family-combining.png")
    }

    private fun waitForSelection(expected: Int) {
        waitFor("synthetic editor cursor reaches grapheme boundary $expected") {
            var correct = false
            instrumentation.runOnMainSync {
                correct = editor.selectionStart == expected && editor.selectionEnd == expected
            }
            correct
        }
    }

    private fun assertSelectionAtGraphemeBoundary(boundaries: Set<Int> = setOf(0, 1, 3, 4)) {
        var valid = false
        instrumentation.runOnMainSync {
            valid =
                editor.selectionStart == editor.selectionEnd &&
                    editor.selectionStart in boundaries
        }
        assertTrue("Cursor must never split the synthetic grapheme cluster", valid)
    }

    /** Real selected IME + installed model + AudioRecord; silence tests lifecycle, not accuracy. */
    fun testHeldSpaceRealMicrophoneReleaseAndCancelReturnToKeysWithoutSpace() {
        assertTrue(
            "Driver must install the verified model into no_backup/mandarin-voice-v1 first",
            io.secure.keyboard.voice.ModelRepository(context).isInstalled(),
        )
        assertEquals(
            "Driver must grant RECORD_AUDIO before the real held-space integration test",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO),
        )
        ensureChinese()
        assertHostEquals("", "Real held-voice fixture begins with an empty editor")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        waitFor("dedicated quiet fixture has no pre-existing microphone capture", 10_000) {
            audio.activeRecordingConfigurations.isEmpty()
        }
        var bounds = Rect()
        var downTime = 0L
        var pointerDown = false
        fun gesture(action: Int) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("Held-space touchscreen injection succeeds", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        try {
            for (cancel in listOf(false, true)) {
                waitForKeyboard()
                bounds = imeBounds("五笔")
                downTime = SystemClock.uptimeMillis()
                gesture(MotionEvent.ACTION_DOWN)
                pointerDown = true
                waitFor("held space starts the actual offline microphone without another tap") {
                    hasImeText("正在聆听 · 松手完成") && hasImeText("取消") &&
                        !hasImeDescription("五笔")
                }
                waitFor("LISTENING corresponds to a real active AudioRecord", 10_000) {
                    audio.activeRecordingConfigurations.isNotEmpty()
                }
                reportPhase(if (cancel) "held-space-real-mic-cancel-listening"
                    else "held-space-real-mic-release-listening")
                // The original space view has been removed. Its original pointer must still
                // deliver UP/CANCEL to the root owner, with no extra click on either panel.
                gesture(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
                pointerDown = false
                var processingObserved = false
                waitFor("held voice finishes/discards and restores the real keyboard") {
                    if (hasImeText("正在完成最后一段")) processingObserved = true
                    hasImeDescription("五笔") && hasImeDescription("q") && !hasImeText("取消")
                }
                waitFor("returning to keys leaves no active microphone recording", 10_000) {
                    audio.activeRecordingConfigurations.isEmpty()
                }
                assertHostEquals("", "Silent held release/cancel must not insert speech or an accidental space")
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("validation_phase", if (cancel) "held-space-real-cancel-finished"
                        else "held-space-real-release-finished")
                    // PROCESSING can finish between accessibility polls. Record observation
                    // honestly; final flush ordering has separate guarded-segment tests.
                    putBoolean("processing_label_observed", processingObserved)
                })
            }
            // A fresh ordinary tap must still be exactly one space after both native sessions.
            tapImeDescription("五笔")
            waitForHost(" ")
            assertHostEquals(" ", "Ordinary space remains unchanged after held voice release and cancel")
        } finally {
            if (pointerDown) gesture(MotionEvent.ACTION_CANCEL)
        }
    }

    fun testPanelThemeAndFloatingSmoke() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedProfiles =
            prefs.all.filterKeys { it.startsWith("profile_") }.mapValues { it.value as String }
        val hadTheme = prefs.contains("theme")
        val originalTheme = prefs.getString("theme", "system")
        try {
            // Force packaged dictionary/emoji initialization before opening category views.
            typeChinese("nihao")
            waitFor("bundled data initialized for panel smoke test") { hasImeText("你好") }
            tapImeDescription("取消待输入内容")
            openMoreTool("表情")
            waitFor("emoji panel defaults to the smiley grid") {
                hasImeDescription("笑脸与情感") && hasImeText("😀")
            }
            val recent = requireImeNode { it.contentDescription?.toString() == "最近使用" }
            try {
                assertTrue("Recent must be a text-free vector icon", recent.text.isNullOrEmpty())
                assertFalse(
                    "Recent must not use a font-glyph TextView",
                    recent.className?.toString() == "android.widget.TextView",
                )
            } finally {
                recent.recycle()
            }
            tapImeDescription("最近使用")
            tapImeDescription("返回键盘")
            openMoreTool("表情")
            waitFor("reopening emoji resets Recent to smiley grid") {
                hasImeText("😀") && hasImeText("😃")
            }
            saveValidationScreenshot("04-emoji-panel.png")
            tapImeDescription("返回键盘")
            val voiceReady = io.secure.keyboard.voice.ModelRepository(context).isInstalled() &&
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            openMoreTool("语音")
            if (voiceReady) {
                waitFor("voice toolbar starts listening without a second tap") {
                    hasImeText("正在聆听") && hasImeText("取消")
                }
                assertFalse(hasImeText("开始"))
                assertFalse(hasImeText("停止"))
                assertFalse(hasImeText("确认插入"))
                saveValidationScreenshot("05-voice-auto-listening.png")
                tapImeText("取消")
            } else {
                waitFor("first use routes to explicit model/microphone setup") {
                    val root = automation.rootInActiveWindow
                    val title = root?.let { findNode(it) { node -> node.text?.toString() == "离线普通话语音" } }
                    val setupVisible = title != null
                    title?.recycle()
                    setupVisible && !hasImeText("开始") && !hasImeText("确认插入") &&
                        !hasImeDescription("返回键盘")
                }
                saveValidationScreenshot("05-voice-setup-gating.png")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.runOnMainSync { editor.requestFocus(); showKeyboard() }
            }
            waitForKeyboard()
            repeat(2) {
                openMoreTool("主题", scrollToBottom = true)
                waitFor("theme toggle selects an explicit theme") {
                    prefs.getString("theme", "system") in listOf("dark", "light")
                }
                // Theme rebuild retains the More panel; return to alphabet keys to sample key fill.
                if (hasImeDescription("返回键盘")) tapImeDescription("返回键盘")
                waitForKeyboard()
                val theme = prefs.getString("theme", "system")!!
                assertKeyTheme(theme)
                saveValidationScreenshot("06-theme-$theme.png")
            }
            openMoreTool("悬浮键盘")
            waitFor("floating drag and resize handles") {
                hasImeDescription("拖动悬浮键盘") && hasImeDescription("调整悬浮键盘大小")
            }
            val bottomKeys = maxOf(imeBounds("五笔").bottom, imeBounds("回车").bottom)
            assertTrue("Drag handle is below key rows", imeBounds("拖动悬浮键盘").top >= bottomKeys)
            assertTrue("Resize handle is below key rows", imeBounds("调整悬浮键盘大小").top >= bottomKeys)
            val resizeBounds = imeBounds("调整悬浮键盘大小")
            val density = context.resources.displayMetrics.density
            assertTrue("Smaller resize artwork retains a 48dp-wide hit target", kotlin.math.abs(resizeBounds.width() - 48 * density) <= 2f)
            assertTrue("Smaller resize artwork retains a 32dp-high hit target", kotlin.math.abs(resizeBounds.height() - 32 * density) <= 2f)
            saveValidationScreenshot("07-floating-small-resize-arrow.png")
            openMoreTool("快捷编辑")
            waitFor("Floating handles remain on tool panels") {
                hasImeDescription("选择") &&
                    hasImeDescription("拖动悬浮键盘") &&
                    hasImeDescription("调整悬浮键盘大小")
            }
            saveValidationScreenshot("07-floating-tool-panel.png")
            tapImeDescription("返回键盘")
            waitForKeyboard()
            val beforeDrag = imeBounds("拖动悬浮键盘")
            val qBefore = imeBounds("q")
            val display =
                automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val displayBounds = Rect().also { display.getBoundsInScreen(it) }
            // Relative gesture distances derive from actual controls, never screenshot coordinates.
            dragImeControl("拖动悬浮键盘", qBefore.width() * 0.55f, -qBefore.height() * 0.55f)
            waitFor("floating keyboard position changes") {
                val now = imeBounds("拖动悬浮键盘")
                now.left != beforeDrag.left || now.top != beforeDrag.top
            }
            val qBeforeResize = imeBounds("q")
            dragImeControl("调整悬浮键盘大小", -displayBounds.width() * 0.07f, -qBefore.height() * 0.40f)
            waitFor("floating keyboard size changes") {
                val now = imeBounds("q")
                now.width() != qBeforeResize.width() || now.height() != qBeforeResize.height()
            }
            saveValidationScreenshot("07-floating-moved-resized.png")
            tapImeDescription("q")
            waitFor("floating q remains a keyboard-local composition") { hasImeText("q") }
            assertHostEquals("", "Floating keyboard composition must not enter the host")
            tapImeDescription("取消待输入内容")
            openMoreTool("悬浮键盘")
            waitFor("floating handles disappear after toggle off") { !hasImeDescription("拖动悬浮键盘") }
            waitForKeyboard()
            reportPhase("panels-themes-floating-smoke-complete")
        } finally {
            // Restore the exact prior settings, including absence of profile/theme keys.
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("profile_") }.forEach { edit.remove(it) }
            savedProfiles.forEach { (key, value) -> edit.putString(key, value) }
            if (hadTheme) edit.putString("theme", originalTheme) else edit.remove("theme")
            edit.commit()
            if (hasImeDescription("取消待输入内容")) tapImeDescription("取消待输入内容")
            if (hasImeDescription("返回键盘")) tapImeDescription("返回键盘")
            if (hasImeDescription("ABC")) tapImeDescription("ABC")
            if (hasImeDescription("English")) tapImeDescription("切换中文和英文")
            // Reopen through normal lifecycle so restored profiles/theme are visibly applied.
            if (hasImeDescription("收起键盘")) {
                tapImeDescription("收起键盘")
                waitFor("smoke cleanup hides keyboard") { !hasImeDescription("收起键盘") }
                instrumentation.runOnMainSync {
                    editor.requestFocus()
                    showKeyboard()
                }
                waitForKeyboard()
            }
        }
    }

    private fun openMoreTool(description: String, scrollToBottom: Boolean = false) {
        val idleSlots = toolbarSlotBounds("更多工具").size
        tapImeDescription("更多工具")
        waitFor("More tools grid opens") { hasImeDescription("布局调整") }
        assertPanelToolbar(idleSlots)
        if (scrollToBottom) {
            val scroll = findImeNode { it.className?.toString() == "android.widget.ScrollView" }
            if (scroll != null) {
                try {
                    scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } finally {
                    scroll.recycle()
                }
            }
        }
        tapImeDescription(description)
    }

    private fun toolbarSlotBounds(firstDescription: String): List<Pair<String, Rect>> {
        val rowBounds = imeBounds(firstDescription)
        val result = mutableListOf<Pair<String, Rect>>()
        fun collect(node: AccessibilityNodeInfo) {
            try {
                if (!node.refresh()) return
                val description = node.contentDescription?.toString().orEmpty()
                if (node.isVisibleToUser && node.isClickable && description.isNotEmpty()) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (!bounds.isEmpty && bounds.top == rowBounds.top && bounds.bottom == rowBounds.bottom)
                        result.add(description to bounds)
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it) }
            } finally {
                node.recycle()
            }
        }
        // More/Back and pins share an inner group; Hide is a sibling in the outer header.
        // Read visible clickable controls in their actual screen row, independent of nesting
        // or accessibility traversal order, then assert their physical left-to-right order.
        for (window in automation.windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { collect(it) }
        }
        return result.sortedBy { it.second.left }
    }

    private fun assertPanelToolbar(idleSlotCount: Int) {
        val slots = toolbarSlotBounds("返回键盘")
        assertEquals("Panel toolbar must retain idle slot count", idleSlotCount, slots.size)
        assertEquals("Back must replace the first More slot", "返回键盘", slots.first().first)
        assertEquals("Hide stays the final slot", "收起键盘", slots.last().first)
        assertTrue("Panels must not add a second More button", slots.none { it.first == "更多工具" })
        val widths = slots.map { it.second.width() }
        assertTrue(
            "Panel toolbar slots must be equal within two pixels",
            widths.maxOrNull()!! - widths.minOrNull()!! <= 2,
        )
        slots.zipWithNext().forEach { (left, right) ->
            assertTrue("Toolbar slots stay adjacent without overlap or a blank slot",
                kotlin.math.abs(left.second.right - right.second.left) <= 2)
        }
    }

    private fun assertKeyTheme(theme: String) {
        val key = imeBounds("q")
        val screenshot =
            requireNotNull(automation.takeScreenshot()) {
                "Theme validation screenshot unavailable"
            }
        try {
            val color = screenshot.getPixel(key.centerX(), key.top + maxOf(2, key.height() / 8))
            val luminance = Color.luminance(color)
            assertTrue(
                "Actual key fill must match selected theme",
                if (theme == "dark") luminance < 0.2f else luminance > 0.7f,
            )
        } finally {
            screenshot.recycle()
        }
    }

    private fun dragImeControl(
        description: String,
        dx: Float,
        dy: Float,
        observe: (() -> Unit)? = null,
    ) {
        val bounds = imeBounds(description)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        assertTrue(
            "Synthetic gesture coordinates must be finite",
            listOf(x, y, dx, dy).all { it.isFinite() },
        )
        val downTime = SystemClock.uptimeMillis()
        var lastEventTime = downTime - 16L
        fun event(action: Int, fraction: Float) {
            // Real cadence, not future-dated/burst events: Android's input resampler
            // must never receive zero-time movement segments from this fixture.
            val earliest = lastEventTime + 16L
            while (SystemClock.uptimeMillis() < earliest) {
                SystemClock.sleep((earliest - SystemClock.uptimeMillis()).coerceAtLeast(1L))
            }
            val eventTime = SystemClock.uptimeMillis()
            assertTrue(
                "Gesture timestamps must increase by at least one frame",
                eventTime - lastEventTime >= 16L,
            )
            lastEventTime = eventTime
            val motion =
                MotionEvent.obtain(
                        downTime,
                        eventTime,
                        action,
                        x + dx * fraction,
                        y + dy * fraction,
                        0,
                    )
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(
                    "Floating gesture injection succeeds",
                    automation.injectInputEvent(motion, true),
                )
            } finally {
                motion.recycle()
            }
            observe?.invoke()
        }
        event(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) event(MotionEvent.ACTION_MOVE, step / 12f)
        event(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    fun testTenShowHideCyclesRemainUsable() {
        // Warm dictionary lookup before the first sample; do not confuse first-load
        // allocations with repeated view lifecycle allocations.
        typeChinese("nihao")
        waitFor("dictionary warm-up candidate") { hasImeText("你好") }
        tapImeDescription("取消待输入内容")
        val before = memorySnapshot()
        repeat(10) {
            tapImeDescription("收起键盘")
            waitFor("IME hides") { !hasImeDescription("收起键盘") }
            instrumentation.runOnMainSync {
                editor.requestFocus()
                showKeyboard()
            }
            waitForKeyboard()
        }
        val after = memorySnapshot()
        reportMemory(before, after)
        ensureChinese()
        typeChinese("nihao")
        tapImeText("你好")
        waitForHost("你好")
    }

    fun testExpandedCandidatesKeepImeHeightAcrossDefaultLightDarkAndLowLayouts() {
        val settings = SettingsStore(context)
        val outer = context.resources.configuration.smallestScreenWidthDp < 600
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val originalProfile = settings.profile(outer, landscape)
        val originalTheme = settings.theme
        val store = ContentStore(context)
        val originalIds = store.dictionary().map { it.id }.toSet()
        val longText = "这是一条较长的常用候选，完整内容不会因显示省略而丢失。"
        val examples = listOf(longText, "您好", "你们好", "谢谢", "没问题", "稍后联系", "收到", "辛苦了") +
            List(48) { "自定义候选${it + 1}" }
        try {
            store.importDictionary(examples.joinToString("\n") { "$it\tni" })
            for ((theme, height, screenshot) in listOf(
                Triple("system", 260, "default"),
                Triple("light", 260, "light"),
                Triple("dark", 260, "dark"),
                Triple("light", 200, "low-height"),
            )) {
                settings.theme = theme
                settings.saveProfile(outer, landscape, originalProfile.copy(
                    heightDp = height, floating = false, split = false, sideGapDp = 0, bottomGapDp = 0,
                ))
                reopenKeyboardForSettings()
                ensureChinese()
                typeChinese("ni")
                waitFor("candidate-mode arrow changes from hide to expand") { hasImeDescription("展开候选") }
                assertFalse("Candidate mode must not label the expansion arrow as keyboard hide",
                    hasImeDescription("收起键盘"))
                val qBounds = imeBounds("q")
                val arrowBounds = imeBounds("展开候选")
                val windowBefore = inputMethodWindowBounds()
                tapImeDescription("展开候选")
                waitFor("expanded candidates replace the letter body") {
                    hasImeDescription("全部候选") && hasImeDescription("收起候选") && !hasImeDescription("q")
                }
                assertEquals("Expansion must never resize or overlay the host", windowBefore, inputMethodWindowBounds())
                assertEquals("The arrow keeps exactly the same hit target", arrowBounds, imeBounds("收起候选"))
                assertTrue("Cancel stays beside the candidate header", hasImeDescription("取消待输入内容"))
                assertTrue("Raw input code remains in its own strip", hasImeText("ni"))
                assertHostEquals("", "Browsing expanded candidates must not insert anything")
                saveValidationScreenshot("19-expanded-candidates-$screenshot.png")
                tapImeDescription("收起候选")
                waitForKeyboard()
                assertEquals("Collapsing restores the same letter geometry", qBounds, imeBounds("q"))
                assertTrue(hasImeDescription("展开候选"))
                assertTrue(hasImeText("ni"))
                tapImeDescription("展开候选")
                waitFor("grid opens repeatedly") { hasImeDescription("全部候选") }
                tapImeDescription("取消待输入内容")
                waitForKeyboard()
                assertTrue("Cancel restores ordinary keyboard hide behavior", hasImeDescription("收起键盘"))
                assertFalse(hasImeDescription("全部候选"))
                assertHostEquals("", "Cancel never commits an expanded candidate")
            }
            // An injected physical tap must target the grid cell, not the matching header label.
            typeChinese("ni")
            waitFor("grid commit query") { hasImeDescription("展开候选") }
            tapImeDescription("展开候选")
            tapIme { node ->
                if (node.text?.toString() != longText) false
                else {
                    val parent = node.parent
                    try { parent?.contentDescription?.toString() == "全部候选" }
                    finally { parent?.recycle() }
                }
            }
            waitForHost(longText)
            waitForKeyboard()
            assertTrue(hasImeDescription("收起键盘"))
            assertFalse(hasImeDescription("全部候选"))
        } finally {
            settings.theme = originalTheme
            settings.saveProfile(outer, landscape, originalProfile)
            store.dictionary().filter { it.id !in originalIds }.forEach { store.deleteDictionary(it.id) }
        }
    }

    private fun inputMethodWindowBounds(): Rect {
        val window = automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        return Rect().also { window.getBoundsInScreen(it) }
    }

    fun testRepeatedRotationKeepsKeyboardAndLayoutProfileUsable() {
        var originalRotation = UiAutomation.ROTATION_FREEZE_0
        instrumentation.runOnMainSync {
            originalRotation = activity.windowManager.defaultDisplay.rotation
        }
        try {
            // Changes actual device orientation and recreates the host Activity.
            // Does not pretend that an emulator rotation reproduces a foldable hinge.
            repeat(2) {
                rotateAndVerifyProfile(
                    UiAutomation.ROTATION_FREEZE_90,
                    Configuration.ORIENTATION_LANDSCAPE,
                )
                rotateAndVerifyProfile(
                    UiAutomation.ROTATION_FREEZE_0,
                    Configuration.ORIENTATION_PORTRAIT,
                )
            }
            ensureChinese()
            // The fixture stays blank throughout these rotations. Calling setText("")
            // here would itself ask API29 TextView to restartInput asynchronously,
            // racing the first synthetic keystroke rather than testing rotation.
            assertHostEquals("", "Profile rotations must preserve the empty synthetic host")
            reportPhase("rotation-final-synthetic-input")
            typeChinese("nihao")
            tapImeText("你好")
            waitForHost("你好")
        } finally {
            // Release the automation rotation lock even when a profile assertion fails.
            automation.setRotation(originalRotation)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun rotateAndVerifyProfile(rotation: Int, expectedOrientation: Int) {
        reportPhase("rotation-request-$rotation")
        assertTrue("Device rotation request failed", automation.setRotation(rotation))
        waitFor("recreated host reaches requested orientation and window focus") {
            var ready = false
            instrumentation.runOnMainSync {
                ready =
                    !activity.isDestroyed &&
                        activity.hasWindowFocus() &&
                        activity.resources.configuration.orientation == expectedOrientation
            }
            ready
        }
        instrumentation.runOnMainSync {
            editor.requestFocus()
            showKeyboard()
        }
        waitForKeyboard()
        tapImeDescription("更多工具")
        tapImeDescription("布局调整")
        var expectedProfile = ""
        instrumentation.runOnMainSync {
            val config = activity.resources.configuration
            expectedProfile =
                (if (config.smallestScreenWidthDp < 600) "外屏" else "内屏") +
                    " · " +
                    (if (expectedOrientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏")
        }
        waitFor("keyboard layout panel reports the active orientation profile") {
            hasImeText(expectedProfile)
        }
        tapImeDescription("返回键盘")
        waitForKeyboard()
        reportPhase("rotation-profile-verified-$rotation")
    }

    private fun reportPhase(phase: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("validation_phase", phase) })
    }

    private data class MemorySnapshot(val pssKb: Int, val javaUsedBytes: Long)

    private fun saveValidationScreenshot(name: String) {
        // Called only for synthetic fixtures in this dedicated emulator test. Never
        // used by production code; files remain in app-specific external storage.
        instrumentation.waitForIdleSync()
        val directory =
            File(
                requireNotNull(context.getExternalFilesDir(null)) {
                    "App-specific external storage unavailable for validation screenshots"
                },
                "validation",
            )
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create validation screenshot directory"
        }
        val screenshot =
            requireNotNull(automation.takeScreenshot()) { "UiAutomation screenshot unavailable" }
        val output = File(directory, name)
        try {
            output.outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    "Cannot encode validation screenshot"
                }
            }
        } finally {
            screenshot.recycle()
        }
        check(output.length() > 0L) { "Validation screenshot file is empty" }
        instrumentation.sendStatus(
            2,
            Bundle().apply { putString("validation_screenshot", output.absolutePath) },
        )
    }

    private fun memorySnapshot(): MemorySnapshot {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return MemorySnapshot(info.totalPss, runtime.totalMemory() - runtime.freeMemory())
    }

    private fun reportMemory(before: MemorySnapshot, after: MemorySnapshot) {
        // The host, IME, and instrumentation share this app process. These diagnostics
        // include all three and are observations, not a leak proof or a threshold test.
        // No forced GC: show the actual sampled allocation state and preserve comparability.
        instrumentation.sendStatus(
            2, // In-progress diagnostic, not an extra test completion event.
            Bundle().apply {
                putString("diagnostic", "ten_ime_show_hide_cycles_same_app_process")
                putInt("memory_pss_before_kb", before.pssKb)
                putInt("memory_pss_after_kb", after.pssKb)
                putInt("memory_pss_delta_kb", after.pssKb - before.pssKb)
                putLong("memory_java_used_before_bytes", before.javaUsedBytes)
                putLong("memory_java_used_after_bytes", after.javaUsedBytes)
                putLong("memory_java_used_delta_bytes", after.javaUsedBytes - before.javaUsedBytes)
            },
        )
    }

    private fun ensureChinese() {
        waitFor("language spacebar visible") {
            hasImeDescription("五笔") || hasImeDescription("English")
        }
        if (hasImeDescription("English")) tapImeDescription("切换中文和英文")
        waitFor("Chinese mode") { hasImeDescription("五笔") }
        if (hasImeDescription("取消待输入内容")) tapImeDescription("取消待输入内容")
    }

    private fun typeChinese(code: String, afterStep: (() -> Unit)? = null) {
        var pending = ""
        for ((index, letter) in code.withIndex()) {
            tapImeDescription(letter.toString())
            pending += letter
            val expected = pending
            waitFor("local preedit updates at synthetic step ${index + 1}") { hasImeText(expected) }
            afterStep?.invoke()
        }
    }

    private fun showKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun waitForKeyboard() =
        waitFor(
            "IME accessibility window and touch keys appear (automation must expose input-method windows)",
            60000,
        ) {
            hasImeDescription("切换中文和英文") && hasImeDescription("q")
        }

    private fun waitForHost(expected: String) =
        waitFor("synthetic host editor reaches expected state") { readHost() == expected }

    private fun readHost(): String {
        var text = ""
        instrumentation.runOnMainSync { text = editor.text.toString() }
        return text
    }

    private fun assertHostEquals(expected: String, reason: String) {
        // No fixture or potentially accidental editor content is included in failure messages.
        assertTrue(reason, readHost() == expected)
    }

    private fun waitFor(description: String, timeoutMs: Long = 60000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        // Capture the failed state before a test's finally/tearDown clears its UI.
        try {
            saveValidationScreenshot("failure-${name ?: "ui-test"}.png")
        } catch (_: Exception) {}
        fail("Timed out: $description")
    }

    private fun requireImeNode(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitFor("requested fresh IME accessibility node") {
            val node = findImeNode(predicate)
            if (node != null && node.refresh() && node.isVisibleToUser && predicate(node))
                found = node
            else node?.recycle()
            found != null
        }
        return requireNotNull(found)
    }

    private fun imeBounds(description: String): Rect {
        val node = requireImeNode { it.contentDescription?.toString() == description }
        try {
            return Rect().also { node.getBoundsInScreen(it) }
        } finally {
            node.recycle()
        }
    }

    private fun tapImeText(text: String) = tapIme { it.text?.toString() == text }

    private fun tapImeDescription(text: String) = tapIme {
        it.contentDescription?.toString() == text
    }

    private fun hasImeText(text: String) = hasIme { it.text?.toString() == text }

    private fun hasImeDescription(text: String) = hasIme {
        it.contentDescription?.toString() == text
    }

    private fun tapIme(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var bounds: Rect? = null
        var previousBounds: Rect? = null
        var stableSince = 0L
        waitFor("requested IME control has fresh stable touch bounds") {
            val node = findImeNode(predicate)
            var fresh: Rect? = null
            if (node != null) {
                try {
                    // Accessibility nodes may be cached across window/profile changes.
                    // Refresh and recheck identity before using their screen coordinates.
                    if (node.refresh() && node.isVisibleToUser && predicate(node)) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (!rect.isEmpty) fresh = rect
                    }
                } finally {
                    node.recycle()
                }
            }
            val now = SystemClock.uptimeMillis()
            if (fresh == null) {
                previousBounds = null
                stableSince = now
            } else if (fresh != previousBounds) {
                previousBounds = Rect(fresh)
                stableSince = now
            } else if (now - stableSince >= 160L) {
                // Only use the latest refreshed read, after unchanged geometry across polls.
                bounds = fresh
            }
            bounds != null
        }
        val rect = requireNotNull(bounds)
        val now = SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    rect.exactCenterX(),
                    rect.exactCenterY(),
                    0,
                )
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up =
            MotionEvent.obtain(
                    now,
                    now + 25,
                    MotionEvent.ACTION_UP,
                    rect.exactCenterX(),
                    rect.exactCenterY(),
                    0,
                )
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue("Touch down injection failed", automation.injectInputEvent(down, true))
            assertTrue("Touch up injection failed", automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun hasIme(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val node = findImeNode(predicate) ?: return false
        node.recycle()
        return true
    }

    private fun findImeNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            val match = findNode(root, predicate)
            if (match != null) return match
        }
        return null
    }

    /** Consumes the supplied node, returning one owned node or recycling all explored nodes. */
    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findNode(child, predicate)
            if (match != null) {
                node.recycle()
                return match
            }
        }
        node.recycle()
        return null
    }

    private fun findEditor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) {
                findEditor(view.getChildAt(i))?.let {
                    return it
                }
            }
        return null
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
