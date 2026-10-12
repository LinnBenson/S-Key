package io.secure.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.settings.SettingsPalette
import io.secure.keyboard.ui.DictionaryEntryRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Bounded actual-Activity QA. All mutations use UUID-isolated stores; no global wm/night/font/IME
 * setting is changed here. The serial device owner chooses the native environment before running.
 * See validation/settings-redesign-0.1.6-qa.md for the two-night and responsive invocation matrix.
 */
@Suppress("DEPRECATION")
class SettingsRedesignScreensTest : InstrumentationTestCase() {
    private lateinit var fixture: SettingsUiFixture
    private lateinit var evidence: SettingsUiEvidence
    private val arguments get() = (instrumentation as InstrumentationTestRunner).arguments
    private val settingsRoutes = listOf("home", "behavior", "layout",
        "profile_outer_portrait", "profile_outer_landscape", "profile_inner_portrait", "profile_inner_landscape",
        "toolbar", "clipboard", "phrases", "dictionary", "english_dictionary", "emoji",
        "theme_light", "theme_dark", "recovery")
    private val captureEnabled get() = arguments.getString("settings_qa_capture") != "false"
    private val config get() = fixture.config
    private val content get() = fixture.content

    override fun setUp() {
        super.setUp()
        fixture = SettingsUiFixture(instrumentation)
        val label = (arguments.getString("settings_qa_label") ?: "native-default").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        evidence = SettingsUiEvidence(instrumentation, "$label/$name")
    }

    override fun tearDown() {
        try { fixture.close() } finally { super.tearDown() }
    }

    fun testAllEighteenRoutesRenderFixedLightAndCaptureNativeStates() {
        seedContent()
        openActivity(MainActivity::class.java)
        assertLightShell()
        onMain { assertFalse("Launcher trial text must not be persisted in view state", findTag<EditText>("launcher_test_input").isSaveEnabled) }
        capture("launcher", "initial", "01-launcher")
        for ((index, route) in settingsRoutes.withIndex()) {
            openSettings(route)
            assertRoute(route)
            assertLightShell()
            assertHorizontalBounds()
            onMain {
                when {
                    route.startsWith("theme_") -> assertEquals("Each palette exposes all 15 tokens", 15,
                        allViews().count { (it.tag as? String)?.startsWith("theme_token_") == true })
                    route.startsWith("profile_") -> assertEquals("All numeric profile controls remain present",
                        if (route.startsWith("profile_inner")) 8 else 7,
                        allViews().count { (it.tag as? String)?.startsWith("anchor:number:") == true })
                    route == "dictionary" || route == "english_dictionary" -> assertEquals(2,
                        allViews().filterIsInstance<DictionaryEntryRow>().size)
                }
            }
            capture(route, "populated-top", "%02d-%s-top".format(index + 2, route))
            if (route in setOf("behavior", "toolbar", "profile_inner_landscape", "theme_light", "dictionary")) {
                if (scrollBottom() > 0) capture(route, "populated-bottom", "%02d-%s-bottom".format(index + 2, route))
            }
        }
        openActivity(VoiceSetupActivity::class.java)
        assertLightShell()
        onMain { assertNotNull(findTag<TextView>("voice_status")) }
        capture("voice", "initial", "18-voice")
        content.clearAll()
        for (route in listOf("clipboard", "phrases", "dictionary", "english_dictionary", "emoji")) {
            openSettings(route)
            assertRoute(route)
            assertLightShell()
            onMain {
                assertTrue("Empty $route must retain an explanation", allViews().filterIsInstance<TextView>()
                    .any { it.text.contains("还没有") || (route == "emoji" && it.text.contains("最多保存 50")) })
                assertTrue(allViews().filterIsInstance<DictionaryEntryRow>().isEmpty())
            }
        }
        capture("emoji", "empty", "19-empty-emoji")
    }

    /** Run once in system light and once in system dark; covers 2 × 3 without 18 × 6 screenshots. */
    fun testThreeKeyboardModesAndCustomTokensNeverRecolorCompanionShells() {
        seedContent()
        for (dark in listOf(false, true)) {
            config.setThemeToken(dark, "keyboardBackground", if (dark) "#FF00FF" else "#000000")
            config.setThemeToken(dark, "normalKeyBackground", if (dark) "#00FF00" else "#FFFFFF")
            config.setThemeToken(dark, "text", if (dark) "#000000" else "#FF0000")
        }
        val palettes = listOf(config.themeTokens(false), config.themeTokens(true))
        for (mode in listOf("system", "light", "dark")) {
            config.theme = mode
            openActivity(MainActivity::class.java)
            assertLightShell()
            openActivity(VoiceSetupActivity::class.java)
            assertLightShell()
            for (route in listOf("home", "behavior", "dictionary", "theme_light", "theme_dark")) {
                openSettings(route)
                assertLightShell()
                assertEquals("Entering $route must not change keyboard mode", mode, config.theme)
                if (route == "theme_dark") capture(route, "system-keyboard-matrix", "matrix-$mode-editor")
            }
            openSettings("profile_outer_portrait")
            clickTag("anchor:number:profile_outer_portrait:键盘高度")
            assertLightDialog(settingsDialog())
            capture("profile_outer_portrait", "numeric-dialog-keyboard-$mode", "matrix-$mode-dialog")
            clickDialog(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(palettes, listOf(config.themeTokens(false), config.themeTokens(true)))
        }
    }

    fun testDeepLinkHeaderAndAndroidBackUseLogicalParents() {
        for (route in settingsRoutes.filter { it != "home" }) {
            val expectedParent = when {
                route.startsWith("profile_") -> "layout"
                route.startsWith("theme_") -> "behavior"
                else -> "home"
            }
            openSettings(route)
            if (route == "recovery") clickButton("重试读取") else clickTag("settings_back")
            assertRoute(expectedParent)
            openSettings(route)
            instrumentation.runOnMainSync { (current() as SettingsActivity).onBackPressed() }
            settle()
            assertRoute(expectedParent)
        }
        openSettings("home")
        val root = current()
        instrumentation.runOnMainSync { root.onBackPressed() }
        await("Root Back finishes") { root.isFinishing || root.isDestroyed }
    }

    fun testScrollAndNestedRouteSurviveRecreationAndNewIntentClearsOldDialog() {
        repeat(18) { content.addPhrase("Synthetic phrase $it\nSecond line 𠀀 e\u0301 👨‍👩‍👧‍👦") }
        openSettings("phrases")
        val before = scrollBottom()
        assertTrue("Phrase fixture must genuinely scroll", before > 0)
        recreate()
        assertRoute("phrases")
        await("Restored phrase scroll") { abs(scrollY() - before) <= dp(8) }
        openSettings("profile_inner_landscape")
        recreate()
        assertRoute("profile_inner_landscape")
        clickTag("anchor:number:profile_inner_landscape:键盘高度")
        val oldDialog = settingsDialog()
        setDialogText(oldDialog, "377")
        openSettings("emoji")
        assertFalse("onNewIntent must dismiss unrelated editable state", oldDialog.isShowing)
        assertRoute("emoji")
        assertEquals(0, scrollY())
        clickTag("settings_back")
        assertRoute("home")
        assertTrue("Navigation must not commit an abandoned draft", config.profile(false, true).heightDp != 377)
    }

    fun testNumericValidationCancelAndSavePreserveOtherProfiles() {
        val profiles = listOf(true to false, true to true, false to false, false to true)
        val originals = profiles.associateWith { (outer, landscape) -> config.profile(outer, landscape) }
        openSettings("profile_inner_landscape")
        clickTag("anchor:number:profile_inner_landscape:键盘高度")
        var dialog = settingsDialog()
        setDialogText(dialog, "199")
        clickDialog(AlertDialog.BUTTON_POSITIVE)
        assertTrue("Invalid save remains open", dialog.isShowing)
        assertEquals(originals[false to true], config.profile(false, true))
        onMain { assertNotNull(descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single().error) }
        setDialogText(dialog, "322")
        clickDialog(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(originals[false to true], config.profile(false, true))
        clickTag("anchor:number:profile_inner_landscape:键盘高度")
        dialog = settingsDialog()
        setDialogText(dialog, "322")
        clickDialog(AlertDialog.BUTTON_POSITIVE)
        assertFalse(dialog.isShowing)
        val expected = originals.getValue(false to true).copy(heightDp = 322)
        assertEquals(expected, config.profile(false, true))
        assertEquals(expected, SettingsStore(fixture.context).profile(false, true))
        for ((outer, landscape) in profiles.filter { it != (false to true) }) {
            assertEquals(originals[outer to landscape], config.profile(outer, landscape))
        }
        recreate()
        assertRoute("profile_inner_landscape")
        onMain { assertEquals("键盘高度: 322", findTag<Button>("anchor:number:profile_inner_landscape:键盘高度").text.toString()) }
    }

    fun testNativeSwitchesPersistAndToolbarReorderKeepsBothDictionaries() {
        config.keySound = false
        config.toolbarPins = listOf("dictionary", "english_dictionary", "settings")
        openSettings("behavior")
        val previous = current()
        onMain {
            val sound = allViews().filterIsInstance<Switch>().single { it.text == "按键声音" }
            sound.performClick() // CompoundButton return value is not its checked or persisted state.
            assertTrue(sound.isChecked)
        }
        settle()
        assertSame("Immediate feedback toggle must not recreate the activity", previous, current())
        assertTrue(SettingsStore(fixture.context).keySound)
        openSettings("toolbar")
        onMain {
            assertTrue(allViews().single { it.tag == "toolbar_drag_english_dictionary" }
                .performAccessibilityAction(0x01000001, null))
        }
        settle()
        assertEquals(listOf("english_dictionary", "dictionary", "settings"), config.toolbarPins)
        clickTag("toolbar_remove_dictionary")
        assertEquals(listOf("english_dictionary", "settings"), SettingsStore(fixture.context).toolbarPins)
        clickTag("toolbar_add_dictionary")
        assertEquals(listOf("english_dictionary", "settings", "dictionary"), SettingsStore(fixture.context).toolbarPins)
        assertLightShell()
    }

    /** Re-run only this bounded set at native 320dp, short landscape, 840dp and large font. */
    fun testResponsiveRepresentativeRowsAndDialogsInCurrentNativeWindow() {
        seedContent()
        val criticalOnly = arguments.getString("settings_qa_critical_only") == "true"
        if (!criticalOnly) {
            for (route in listOf("behavior", "toolbar")) {
                openSettings(route)
                assertLightShell()
                assertHorizontalBounds()
                scrollBottom()
                assertHeaderVisible()
                capture(route, "responsive-bottom", "responsive-$route")
            }
        }
        openSettings("english_dictionary")
        scrollBottom()
        // The page footer follows the dictionary rows. In a short native window,
        // page-bottom is not the same position as the last row's action targets.
        capture("english_dictionary", "responsive-page-bottom-before-target-scroll", "responsive-dictionary-row-before-target-scroll")
        captureViewBounds(current().window.decorView, "responsive-dictionary-row-before-target-scroll")
        onMain {
            val row = allViews().filterIsInstance<DictionaryEntryRow>().last()
            row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
        }
        settle()
        capture("english_dictionary", "responsive-target-row-before-actions-check", "responsive-dictionary-row-before-actions-check")
        captureViewBounds(current().window.decorView, "responsive-dictionary-row-before-actions-check")
        onMain {
            val row = allViews().filterIsInstance<DictionaryEntryRow>().last()
            assertEquals(LONG_WORD, row.wordView.text.toString())
            assertHorizontalTarget(row.editAction)
            assertHorizontalTarget(row.deleteAction)
            assertTrue(row.editAction.right <= row.deleteAction.left)
            row.editAction.performClick()
        }
        settle()
        val dictionaryDialog = settingsDialog()
        assertLightDialog(dictionaryDialog)
        capture("english_dictionary", "responsive-full-value-edit-before-actions-check", "responsive-dictionary-edit-before-actions-check")
        captureDialogBounds(dictionaryDialog, "responsive-dictionary-edit-before-actions-check")
        assertDialogActionsReachable(dictionaryDialog)
        onMain {
            val fields = descendants(dictionaryDialog.window!!.decorView).filterIsInstance<EditText>()
            assertEquals(listOf(LONG_WORD, LONG_CODE), fields.map { it.text.toString() })
            fields.last().requestRectangleOnScreen(Rect(0, 0, fields.last().width, fields.last().height), true)
        }
        settle()
        capture("english_dictionary", "responsive-full-value-edit", "responsive-dictionary-edit")
        clickDialog(AlertDialog.BUTTON_NEGATIVE)
        openSettings("theme_dark")
        clickTag("theme_token_keyboardBackground")
        val picker = pickerDialog()
        assertLightDialog(picker)
        assertDialogActionsReachable(picker)
        onMain {
            val root = picker.window!!.decorView
            assertNotNull(root.findViewWithTag<EditText>("color_hex"))
            assertNotNull(root.findViewWithTag<View>("color_sv"))
            assertNotNull(root.findViewWithTag<View>("color_hue"))
        }
        capture("theme_dark", "responsive-hsv-picker", "responsive-picker")
        onMain { picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        settle()
        openSettings("profile_inner_landscape")
        clickTag("anchor:number:profile_inner_landscape:悬浮纵向位置（距底）")
        assertLightDialog(settingsDialog())
        assertDialogActionsReachable(settingsDialog())
        capture("profile_inner_landscape", "responsive-long-label-number", "responsive-number")
        clickDialog(AlertDialog.BUTTON_NEGATIVE)
        if (!criticalOnly) {
            openActivity(VoiceSetupActivity::class.java)
            clickTag("voice_licenses")
            // License content/action visibility is checked through the real foreground window.
            assertTrue("License dialog must expose its close action", activeWindowContains("关闭"))
            capture("voice", "responsive-license", "responsive-license")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
        }
    }

    fun testRepeatedDialogCancelBackAndRecreationReleaseOwnedWindows() {
        val original = config.profile(true, false)
        repeat(3) {
            openSettings("profile_outer_portrait")
            clickTag("anchor:number:profile_outer_portrait:键盘高度")
            val dialog = settingsDialog()
            setDialogText(dialog, (390 + it).toString())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            // An IME may consume the first Back. A second Back must close the dialog, not the page.
            if (dialog.isShowing) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
            assertFalse("Back dismisses the owned dialog", dialog.isShowing)
            assertRoute("profile_outer_portrait")
            recreate()
            assertRoute("profile_outer_portrait")
            assertEquals(original, config.profile(true, false))
        }
        assertTrue("Real recreation callbacks must have been observed", fixture.destroyedCount >= 3)
        // Observational lifecycle smoke only. Weak references are available to the later memory
        // audit; this test deliberately does not force GC or claim collectible == leak-free.
        fixture.assertActualDataUnchanged()
    }

    private fun seedContent() {
        content.addClipboard("Synthetic clipboard only\nUnicode 𠀀 e\u0301 👨‍👩‍👧‍👦")
        content.addPhrase("Synthetic phrase\nSecond line")
        content.addDictionary("合成测试词", "ceshi")
        content.addDictionary(LONG_WORD, LONG_CODE)
        content.addDictionary("Synthetic English", "test", DictionaryKind.ENGLISH)
        content.addDictionary(LONG_WORD, LONG_CODE, DictionaryKind.ENGLISH)
        content.useEmoji("🙂")
    }

    private fun openSettings(route: String) {
        if (fixture.current is SettingsActivity && !current().isFinishing) {
            onMain {
                SettingsActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }.invoke(current(), Intent(instrumentation.targetContext, SettingsActivity::class.java)
                        .putExtra("page", route))
            }
            settle()
        } else openActivity(SettingsActivity::class.java, route)
        verifyEnvironment()
    }

    private fun openActivity(type: Class<out Activity>, route: String? = null) {
        fixture.current?.let { old -> onMain { old.finish() }; settle() }
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, type).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            route?.let { putExtra("page", it) }
        })
        await("Focused ${type.simpleName}") { fixture.current?.let { type.isInstance(it) && it.hasWindowFocus() } == true }
        settle()
        verifyEnvironment()
    }

    private fun verifyEnvironment() {
        val actual = current().resources.configuration
        arguments.getString("settings_qa_expected_width_dp")?.toInt()?.let {
            assertTrue("Expected native width near $it dp, actual ${actual.screenWidthDp}", abs(actual.screenWidthDp - it) <= 24)
        }
        arguments.getString("settings_qa_expected_font_scale")?.toFloat()?.let {
            assertTrue("Expected fontScale $it, actual ${actual.fontScale}", abs(actual.fontScale - it) < .05f)
        }
        arguments.getString("settings_qa_expected_night")?.let {
            val night = actual.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertEquals("System-night fixture mismatch", it == "true", night)
        }
    }

    private fun recreate() {
        val old = current()
        onMain { old.recreate() }
        await("Activity recreation") { fixture.current?.let { it !== old && it.hasWindowFocus() } == true }
        settle()
    }

    private fun assertRoute(route: String) = onMain {
        assertNotNull("Missing rendered route $route", current().window.decorView.findViewWithTag<View>(route))
    }

    private fun assertLightShell() = onMain {
        val root = findTag<View>("settings_shell")
        val launcher = current() is MainActivity
        assertEquals(if (launcher) 0xFFF6F6F3.toInt() else SettingsPalette.background, (root.background as ColorDrawable).color)
        assertEquals(if (launcher) SettingsPalette.accent else SettingsPalette.ink, findTag<TextView>("settings_title").currentTextColor)
        if (Build.VERSION.SDK_INT >= 29) assertFalse(root.isForceDarkAllowed)
        for (button in descendants(root).filterIsInstance<Button>()) {
            assertTrue("Unexpected chrome text color for ${button.text}", button.currentTextColor in setOf(
                SettingsPalette.ink, SettingsPalette.accent, SettingsPalette.secondaryText, SettingsPalette.onAccent, SettingsPalette.error))
        }
        assertHeaderVisible()
    }

    private fun assertLightDialog(dialog: AlertDialog) = onMain {
        assertTrue(dialog.isShowing)
        val root = requireNotNull(dialog.window).decorView
        assertEquals("Owned dialog background", SettingsPalette.surface, (root.background as GradientDrawable).color!!.defaultColor)
        if (Build.VERSION.SDK_INT >= 29) assertFalse(root.isForceDarkAllowed)
        for (field in descendants(root).filterIsInstance<EditText>()) {
            assertEquals(SettingsPalette.ink, field.currentTextColor)
            assertEquals(SettingsPalette.secondaryText, field.currentHintTextColor)
        }
    }

    private fun assertHeaderVisible() = onMain {
        val title = findTag<TextView>("settings_title")
        val bounds = Rect()
        assertTrue("Header remains visible above the scrolling body", title.getGlobalVisibleRect(bounds))
        assertEquals(title.height, bounds.height())
    }

    private fun assertHorizontalBounds() = onMain {
        val shell = findTag<View>("settings_shell")
        val shellAt = IntArray(2).also { shell.getLocationInWindow(it) }
        for (view in descendants(shell).filter { it is Button || it is Switch || it is EditText }) {
            val at = IntArray(2).also { view.getLocationInWindow(it) }
            assertTrue("Control starts outside native window: ${view.contentDescription}", at[0] >= shellAt[0])
            assertTrue("Control exceeds native window: ${(view as? TextView)?.text}", at[0] + view.width <= shellAt[0] + shell.width)
        }
    }

    private fun assertHorizontalTarget(view: View) {
        assertTrue("Interactive target width >=48dp", view.width >= dp(48) - 1)
        assertTrue("Interactive target height >=48dp", view.height >= dp(48) - 1)
        val bounds = Rect()
        assertTrue("Trailing row action visible", view.getGlobalVisibleRect(bounds))
        assertEquals(view.width, bounds.width())
    }

    private fun assertDialogActionsReachable(dialog: AlertDialog) = onMain {
        val nativePanelId = current().resources.getIdentifier("buttonPanel", "id", "android")
        if (nativePanelId != 0) {
            val actions = requireNotNull(dialog.window).decorView.findViewWithTag<View>("settings_dialog_actions")
            assertNotNull("Adaptive actions must retain a native button panel", actions)
            // API29 retains buttonPanel on a ScrollView around the inner action row.
            var nativePanel: View? = actions
            while (nativePanel != null && nativePanel.id != nativePanelId) nativePanel = nativePanel.parent as? View
            assertNotNull("Native AlertDialogLayout must recognize an action-panel ancestor", nativePanel)
        }
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
            val button = dialog.getButton(which)
            assertHorizontalTarget(button)
            val bounds = Rect()
            button.getGlobalVisibleRect(bounds)
            assertEquals("Dialog actions must not clip vertically", button.height, bounds.height())
        }
    }

    private fun settingsDialog(): AlertDialog = SettingsActivity::class.java.getDeclaredField("activeDialog")
        .apply { isAccessible = true }.get(current()) as AlertDialog

    private fun pickerDialog(): AlertDialog {
        val editor = SettingsActivity::class.java.getDeclaredField("editor").apply { isAccessible = true }.get(current())
        return editor.javaClass.getDeclaredField("activeDialog").apply { isAccessible = true }.get(editor) as AlertDialog
    }

    private fun setDialogText(dialog: AlertDialog, value: String) {
        onMain { descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(value) }
        settle()
    }

    private fun clickDialog(which: Int) {
        onMain { settingsDialog().getButton(which).performClick() }
        settle()
    }

    private fun clickTag(tag: String) {
        onMain { findTag<View>(tag).performClick() }
        settle()
    }

    private fun clickButton(label: String) {
        onMain { allViews().filterIsInstance<Button>().first { it.text == label }.performClick() }
        settle()
    }

    private fun scrollBottom(): Int {
        onMain { findTag<ScrollView>("settings_scroll").fullScroll(View.FOCUS_DOWN) }
        settle()
        return scrollY()
    }

    private fun scrollY(): Int { var value = 0; onMain { value = findTag<ScrollView>("settings_scroll").scrollY }; return value }
    private fun current() = requireNotNull(fixture.current)
    private fun allViews() = descendants(current().window.decorView)
    private fun <T : View> findTag(tag: String): T = requireNotNull(current().window.decorView.findViewWithTag<T>(tag)) { "Missing view tag $tag" }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun dp(value: Int) = (current().resources.displayMetrics.density * value + .5f).toInt()
    private fun onMain(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else {
            // The framework runner must receive failures on its test thread. An assertion thrown
            // from SyncRunnable otherwise terminates the app before evidence/tearDown can run.
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try { action() } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        }
    }
    private fun captureDialogBounds(dialog: AlertDialog, label: String) =
        captureViewBounds(requireNotNull(dialog.window).decorView, label, isDialog = true)

    private fun captureViewBounds(root: View, label: String, isDialog: Boolean = false) {
        if (!captureEnabled) return
        var report = ""
        onMain {
            val frame = Rect().also(root::getWindowVisibleDisplayFrame)
            val views = descendants(root)
            fun bounds(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            val records = views.map { view ->
                val parent = view.parent as? View
                val visible = Rect()
                val globallyVisible = view.getGlobalVisibleRect(visible)
                val windowAt = IntArray(2).also(view::getLocationInWindow)
                val screenAt = IntArray(2).also(view::getLocationOnScreen)
                val params = view.layoutParams
                JSONObject().apply {
                    put("index", views.indexOf(view))
                    put("parentIndex", if (parent == null) -1 else views.indexOf(parent))
                    put("class", view.javaClass.name)
                    put("id", view.id)
                    put("resourceId", if (view.id == View.NO_ID) "NO_ID" else try {
                        view.resources.getResourceName(view.id)
                    } catch (_: android.content.res.Resources.NotFoundException) { "unresolved" })
                    put("tag", view.tag?.toString())
                    put("textPrefix", (view as? TextView)?.text?.toString()?.take(120))
                    put("visibility", view.visibility)
                    put("isShown", view.isShown)
                    put("globallyVisible", globallyVisible)
                    put("visibleRect", bounds(visible))
                    put("localBounds", bounds(Rect(view.left, view.top, view.right, view.bottom)))
                    put("windowLocation", JSONArray(windowAt.toList()))
                    put("screenLocation", JSONArray(screenAt.toList()))
                    put("width", view.width)
                    put("height", view.height)
                    put("measuredWidth", view.measuredWidth)
                    put("measuredHeight", view.measuredHeight)
                    put("minimumHeight", view.minimumHeight)
                    put("padding", JSONArray(listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)))
                    put("scrollY", view.scrollY)
                    put("layoutParamsClass", params?.javaClass?.name)
                    put("layoutWidth", params?.width)
                    put("layoutHeight", params?.height)
                    if (params is ViewGroup.MarginLayoutParams) {
                        put("margins", JSONArray(listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin)))
                    }
                }
            }
            report = JSONObject().apply {
                put("nativeDialogBoundsBeforeAssertion", isDialog)
                put("nativeViewBoundsBeforeAssertion", true)
                put("syntheticContentAndSettings", true)
                put("testMethod", name)
                put("sdk", Build.VERSION.SDK_INT)
                put("fontScale", current().resources.configuration.fontScale)
                put("visibleDisplayFrame", bounds(frame))
                put("views", JSONArray(records))
            }.toString(2)
        }
        val environment = (arguments.getString("settings_qa_label") ?: "native-default")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "validation/settings-redesign-0.1.6/$environment/$name").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "$label-bounds.json")
        file.writeText(report)
        instrumentation.sendStatus(2, Bundle().apply { putString("validation_bounds", file.absolutePath) })
    }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(120) }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(50) }
        fail("Timed out: $label")
    }
    private fun capture(route: String, state: String, name: String) {
        if (captureEnabled) evidence.capture(current(), route, state, config.theme, name)
    }
    private fun activeWindowContains(text: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.text?.toString() == text) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (contains(child)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { contains(root) } finally { root.recycle() }
    }

    companion object {
        private val LONG_WORD = "OpenAI e\u0301 𠀀 👨‍👩‍👧‍👦 ".repeat(20).trimEnd()
        private val LONG_CODE = "AbCd".repeat(10)
    }
}
