package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.DictionaryEntryRow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real SettingsActivity flows with an isolated content/configuration store and synthetic TSVs. */
@Suppress("DEPRECATION")
class BilingualDictionaryScreensTest : InstrumentationTestCase() {
    private lateinit var sandbox: IsolatedContext
    private lateinit var store: ContentStore
    private lateinit var config: SettingsStore
    private lateinit var application: Application
    @Volatile private var activity: SettingsActivity? = null

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(current: Activity, state: Bundle?) {
            if (current !is SettingsActivity) return
            // Activity's super.onCreate callback runs before the restored dictionary is rendered.
            // Replace only the stores so real production route/page restoration uses test data.
            field("content").set(current, store)
            field("config").set(current, config)
        }
        override fun onActivityStarted(current: Activity) {
            if (current is SettingsActivity) activity = current
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(instrumentation.targetContext)
        sandbox.root.deleteRecursively()
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = ContentStore(sandbox)
        config = SettingsStore(sandbox)
        application = instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(lifecycle) }
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync {
                activity?.finish()
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
            instrumentation.waitForIdleSync()
            sandbox.root.deleteRecursively()
            sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            super.tearDown()
        }
    }

    fun testHomeCountsIndependentScreensAndBothToolbarPins() {
        store.addDictionary("中文测试", "shared")
        store.addDictionary("English One", "shared", DictionaryKind.ENGLISH)
        store.addDictionary("English Two", "more", DictionaryKind.ENGLISH)
        config.toolbarPins = listOf("dictionary")
        openPage()
        assertButton("中文词库（1）")
        assertButton("英文词库（2）")
        scrollToBottom()
        saveScreenshot("35-bilingual-settings-home.png")
        clickButton("中文词库（1）")
        assertTitle("中文词库")
        assertEquals(listOf("中文测试"), visibleRows().map { it.wordView.text.toString() })
        clickButton("‹ 返回设置")
        clickButton("英文词库（2）")
        assertTitle("英文词库")
        assertEquals(listOf("English One", "English Two"), visibleRows().map { it.wordView.text.toString() })
        openPage("toolbar")
        instrumentation.runOnMainSync {
            val toggles = descendants(requireActivity().window.decorView).filterIsInstance<Switch>()
            assertTrue(toggles.single { it.text == "中文词库" }.isChecked)
            val english = toggles.single { it.text == "英文词库" }
            assertFalse(english.isChecked)
            // CompoundButton toggles before returning View's OnClickListener-handled flag.
            // This switch intentionally saves through OnCheckedChangeListener instead.
            english.performClick()
            assertTrue("The clicked switch must become checked", english.isChecked)
            val refreshedEnglish = descendants(requireActivity().window.decorView)
                .filterIsInstance<Switch>().single { it.text == "英文词库" }
            assertTrue("The rebuilt toolbar must reflect the saved pin", refreshedEnglish.isChecked)
        }
        assertEquals(listOf("dictionary", "english_dictionary"), config.toolbarPins)
        assertEquals(listOf("dictionary", "english_dictionary"), SettingsStore(sandbox).toolbarPins)
    }

    fun testIndependentPagesSurviveRotationAndFullEnglishValuesRemainEditable() {
        store.importDictionary((0..100).joinToString("\n") { "中文 $it\tlegacy-code-$it" })
        store.importDictionary((0 until 200).joinToString("\n") { "English $it\tcode" }, kind = DictionaryKind.ENGLISH)
        val fullWord = "OpenAI e\u0301 𠀀 👨‍👩‍👧‍👦 ".repeat(20).trimEnd()
        val fullCode = "AbCd".repeat(10)
        store.addDictionary(fullWord, fullCode, DictionaryKind.ENGLISH)
        openPage("dictionary")
        clickButton("下一页")
        assertPage(2, 2)
        clickButton("‹ 返回设置")
        clickButton("英文词库（201）")
        clickButton("下一页")
        clickButton("下一页")
        assertPage(3, 3)
        recreateActivity()
        assertTitle("英文词库")
        assertPage(3, 3)
        clickButton("‹ 返回设置")
        clickButton("中文词库（101）")
        assertPage(2, 2)
        clickButton("‹ 返回设置")
        clickButton("英文词库（201）")
        assertPage(3, 3)
        scrollToBottom()
        saveScreenshot("35-english-dictionary-compact.png")
        instrumentation.runOnMainSync {
            val row = visibleRows().single()
            val edit = Rect()
            val delete = Rect()
            assertTrue(row.editAction.getGlobalVisibleRect(edit))
            assertTrue(row.deleteAction.getGlobalVisibleRect(delete))
            assertTrue(edit.right <= delete.left)
            val targetSize = (48 * requireActivity().resources.displayMetrics.density).toInt()
            assertEquals(targetSize, row.editAction.width)
            assertEquals(targetSize, row.deleteAction.width)
            assertTrue(row.codeView.left < row.wordView.left)
            assertEquals(1, row.codeView.lineCount)
            assertEquals(1, row.wordView.lineCount)
            assertEquals(TextUtils.TruncateAt.END, row.wordView.ellipsize)
            assertTrue(row.wordView.layout.getEllipsisCount(0) > 0)
            assertTrue(row.editAction.performClick())
        }
        awaitDialog("编辑英文词库词条")
        assertEquals(listOf(fullWord, fullCode), editorValues())
        saveScreenshot("35-english-dictionary-full-edit.png")
        setEditorValues("Updated " + fullWord, fullCode)
        clickDialog("保存")
        awaitCondition("English full-value edit") {
            store.dictionary(DictionaryKind.ENGLISH).last().word == "Updated " + fullWord
        }
        assertEquals(101, store.dictionary().size)
        assertPage(3, 3)
    }

    fun testEnglishManualValidationLiteralSavingAndScopedDelete() {
        val chinese = "中文保留 𠀀"
        val legacyCode = "long legacy code 123".repeat(6)
        store.addDictionary(chinese, legacyCode)
        openPage("english_dictionary")
        clickButton("添加词条")
        awaitDialog("添加英文词库词条")
        setEditorValues(" leading", "a".repeat(41))
        clickDialog("保存")
        assertTrue(store.dictionary(DictionaryKind.ENGLISH).isEmpty())
        awaitCondition("English output and code validation errors") {
            editorErrors().any { it.contains("首尾") } && editorErrors().any { it.contains("1–40") }
        }
        setEditorValues("Literal OpenAI α e\u0301!", "bad-code")
        clickDialog("保存")
        assertTrue(store.dictionary(DictionaryKind.ENGLISH).isEmpty())
        awaitCondition("English ASCII validation error") { editorErrors().any { it.contains("英文字母") } }
        setEditorValues("Literal OpenAI α e\u0301!", "CuStOm")
        clickDialog("保存")
        awaitCondition("English literal manual save") { store.dictionary(DictionaryKind.ENGLISH).size == 1 }
        val saved = store.dictionary(DictionaryKind.ENGLISH).single()
        assertEquals("Literal OpenAI α e\u0301!", saved.word)
        assertEquals("CuStOm", saved.reading)
        instrumentation.runOnMainSync { assertTrue(visibleRows().single().deleteAction.performClick()) }
        awaitDialog("删除词条？")
        assertTrue(activeTexts().any { it.contains("英文词库") })
        clickDialog("删除")
        awaitCondition("English scoped delete") { store.dictionary(DictionaryKind.ENGLISH).isEmpty() }
        assertEquals(chinese, store.dictionary().single().word)
        assertEquals(legacyCode, store.dictionary().single().reading)
        openPage("dictionary")
        instrumentation.runOnMainSync { visibleRows().single().editAction.performClick() }
        awaitDialog("编辑中文词库词条")
        assertEquals(listOf(chinese, legacyCode), editorValues())
        clickDialog("保存")
        assertEquals(legacyCode, store.dictionary().single().reading)
    }

    fun testLanguageClearAndClearAllPreserveOtherContentThenConfigurationAndExternalExport() {
        config.theme = "dark"
        config.englishCompletion = false
        config.toolbarPins = listOf("english_dictionary", "dictionary", "settings")
        val profile = config.profile(true, false).copy(heightDp = 321)
        config.saveProfile(true, false, profile)
        store.addDictionary("中文", "code")
        store.addDictionary("English", "code", DictionaryKind.ENGLISH)
        store.addPhrase("Keep until clear all")
        val externalExport = File(sandbox.root, "selected-location/previous.tsv").apply {
            parentFile!!.mkdirs()
            writeText("previous export\tcode\n")
        }
        openPage("english_dictionary")
        clickButton("清空英文词库")
        awaitDialog("清空英文词库？")
        assertTrue(activeTexts().any { it.contains("另一个词库") })
        clickDialog("删除")
        awaitCondition("English category clear") { store.dictionary(DictionaryKind.ENGLISH).isEmpty() }
        assertEquals(1, store.dictionary().size)
        assertEquals(1, store.phrases().size)
        store.addDictionary("Again", "again", DictionaryKind.ENGLISH)
        clickButton("‹ 返回设置")
        clickButton("清空全部用户内容")
        awaitDialog("清空用户内容？")
        assertTrue(activeTexts().any { it.contains("中文词库、英文词库") && it.contains("布局与设置会保留") })
        clickDialog("删除")
        awaitCondition("Both dictionaries cleared") { store.dictionary().isEmpty() && store.dictionary(DictionaryKind.ENGLISH).isEmpty() }
        assertTrue(store.phrases().isEmpty())
        assertEquals("dark", config.theme)
        assertFalse(config.englishCompletion)
        assertEquals(listOf("english_dictionary", "dictionary", "settings"), config.toolbarPins)
        assertEquals(profile, config.profile(true, false))
        assertEquals("previous export\tcode\n", externalExport.readText())
    }

    fun testFourPickerActionsUseIndependentKindsAndDistinctFilenames() {
        for (kind in DictionaryKind.values()) {
            val language = if (kind == DictionaryKind.CHINESE) "chinese" else "english"
            val name = if (kind == DictionaryKind.CHINESE) "中文词库" else "英文词库"
            val route = if (kind == DictionaryKind.CHINESE) "dictionary" else "english_dictionary"
            val input = File(sandbox.root, "$language-input.tsv").apply {
                parentFile!!.mkdirs()
                writeText("word\treading\n$language literal e\u0301!\tAbCd\n")
            }
            val importedBefore = store.dictionary(kind).size
            val other = if (kind == DictionaryKind.CHINESE) DictionaryKind.ENGLISH else DictionaryKind.CHINESE
            val otherBefore = store.dictionary(other)
            openPage(route)
            withPickerResult(Intent.ACTION_OPEN_DOCUMENT, input) { captured ->
                clickButton("导入 TSV")
                awaitCondition("$language import picker interception") { captured.isNotEmpty() }
                assertEquals("*/*", captured.single().type)
                assertTrue(captured.single().hasCategory(Intent.CATEGORY_OPENABLE))
                awaitCondition("$language import result") { store.dictionary(kind).size == importedBefore + 1 }
                awaitTransferIdle()
            }
            assertTitle(name)
            assertEquals(otherBefore, store.dictionary(other))
            assertEquals("$language literal e\u0301!", store.dictionary(kind).last().word)
            val output = File(sandbox.root, "$language-output.tsv")
            withPickerResult(Intent.ACTION_CREATE_DOCUMENT, output) { captured ->
                clickButton("导出 TSV")
                awaitCondition("$language export picker interception") { captured.isNotEmpty() }
                assertEquals("offline-keyboard-$language-dictionary.tsv", captured.single().getStringExtra(Intent.EXTRA_TITLE))
                awaitCondition("$language export result") { output.isFile && output.length() > 0L }
                awaitTransferIdle()
            }
            assertEquals(store.exportDictionary(kind), output.readText())
            assertTitle(name)
        }
    }

    fun testDelayedPickerResultsKeepTheirKindAfterNavigationAndRotation() {
        openPage("english_dictionary")
        // Result request codes are deliberately explicit and remain stable across recreation.
        val input = File(sandbox.root, "delayed.tsv").apply {
            parentFile!!.mkdirs()
            writeText("Delayed Literal\tDeLaYeD\n")
        }
        openPage("dictionary")
        recreateActivity()
        instrumentation.runOnMainSync {
            deliverPickerResult("IMPORT_ENGLISH", input)
        }
        awaitCondition("Delayed English result") { store.dictionary(DictionaryKind.ENGLISH).size == 1 }
        awaitTransferIdle()
        assertTrue(store.dictionary().isEmpty())
        assertTitle("英文词库")
        store.addDictionary("中文导出", "legacy code")
        val output = File(sandbox.root, "delayed-export.tsv")
        openPage("english_dictionary")
        recreateActivity()
        instrumentation.runOnMainSync {
            deliverPickerResult("EXPORT_CHINESE", output)
        }
        awaitCondition("Delayed Chinese export") { output.isFile && output.length() > 0 }
        awaitTransferIdle()
        assertEquals(store.exportDictionary(), output.readText())
        assertTitle("中文词库")
        assertEquals(1, store.dictionary(DictionaryKind.ENGLISH).size)
    }

    fun testRetainedTransferSuccessAndFailureReturnToTheirOwnKindAfterRotation() {
        openPage("english_dictionary")
        for (failWork in listOf(false, true)) {
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val transfer = transfer()
            try {
                instrumentation.runOnMainSync {
                    val start = transfer.javaClass.getDeclaredMethod(
                        "start", DictionaryKind::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java,
                    ).apply { isAccessible = true }
                    val work: () -> String = {
                        started.countDown()
                        check(release.await(20, TimeUnit.SECONDS)) { "Synthetic transfer timeout" }
                        if (failWork) error("Synthetic invalid English TSV")
                        "英文词库已导出"
                    }
                    start.invoke(transfer, DictionaryKind.ENGLISH, "导出英文词库", work)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                openPage("dictionary")
                recreateActivity()
                assertSame("The transfer must survive the actual Activity recreation", transfer, transfer())
                assertTitle("中文词库")
                awaitDialog("导出英文词库")
                release.countDown()
                awaitTransferIdle()
                assertTitle("英文词库")
                if (failWork) {
                    awaitDialog("英文词库操作未完成")
                    assertTrue(activeTexts().contains("Synthetic invalid English TSV"))
                    clickDialog("知道了")
                }
            } finally {
                release.countDown()
                awaitTransferIdle()
            }
        }
    }

    private fun openPage(route: String? = null) {
        val next = Intent(instrumentation.targetContext, SettingsActivity::class.java).apply {
            if (route != null) putExtra("page", route)
        }
        if (activity == null) {
            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            instrumentation.startActivitySync(next)
        } else instrumentation.runOnMainSync { routeActivity(requireActivity(), next) }
        instrumentation.waitForIdleSync()
    }

    private fun routeActivity(current: SettingsActivity, next: Intent) {
        SettingsActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(current, next)
    }

    private fun deliverPickerResult(code: String, file: File) {
        SettingsActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
        ).apply { isAccessible = true }.invoke(
            requireActivity(), requestCode(code), Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)),
        )
    }

    private fun recreateActivity() {
        val previous = requireActivity()
        instrumentation.runOnMainSync { previous.recreate() }
        awaitCondition("Activity recreation") { activity != null && activity !== previous }
        instrumentation.waitForIdleSync()
    }

    private fun scrollToBottom() {
        instrumentation.runOnMainSync {
            descendants(requireActivity().window.decorView).filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun saveScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "validation")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(directory, name)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        assertTrue(file.length() > 0)
        instrumentation.sendStatus(2, Bundle().apply { putString("validation_screenshot", file.absolutePath) })
    }

    private fun assertTitle(expected: String) {
        instrumentation.runOnMainSync { assertEquals(expected, requireActivity().title.toString()) }
    }

    private fun assertPage(page: Int, total: Int) {
        instrumentation.runOnMainSync {
            assertTrue(descendants(requireActivity().window.decorView).filterIsInstance<TextView>().any {
                it.text.contains("第 $page / $total 页")
            })
        }
    }

    private fun assertButton(text: String) {
        instrumentation.runOnMainSync {
            assertTrue(descendants(requireActivity().window.decorView).filterIsInstance<Button>().any { it.text == text })
        }
    }

    private fun clickButton(text: String) {
        instrumentation.runOnMainSync {
            val button = descendants(requireActivity().window.decorView).filterIsInstance<Button>().firstOrNull { it.text == text }
            assertNotNull("Missing button: $text", button)
            assertTrue(button!!.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun visibleRows(): List<DictionaryEntryRow> =
        descendants(requireActivity().window.decorView).filterIsInstance<DictionaryEntryRow>()

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun awaitDialog(title: String) = awaitCondition("Dialog: $title") { activeTexts().contains(title) }

    private fun activeTexts(): List<String> = withActiveNodes { nodes -> nodes.mapNotNull { it.text?.toString() } }

    private fun editorValues(): List<String> = withActiveNodes { nodes ->
        nodes.filter { it.className == "android.widget.EditText" }.map { it.text?.toString().orEmpty() }
    }

    private fun editorErrors(): List<String> = withActiveNodes { nodes ->
        nodes.filter { it.className == "android.widget.EditText" }.mapNotNull { it.error?.toString() }
    }

    private fun setEditorValues(word: String, code: String) {
        withActiveNodes { nodes ->
            val editors = nodes.filter { it.className == "android.widget.EditText" }
            assertEquals(2, editors.size)
            listOf(word, code).forEachIndexed { index, value ->
                assertTrue(editors[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }
        }
        instrumentation.waitForIdleSync()
        awaitCondition("Editor text update") { editorValues() == listOf(word, code) }
    }

    private fun clickDialog(text: String) {
        withActiveNodes { nodes ->
            val action = nodes.firstOrNull { it.text == text && it.isClickable }
            assertNotNull("Missing dialog action: $text", action)
            assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T> withActiveNodes(read: (List<AccessibilityNodeInfo>) -> T): T {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root == null) return read(emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            nodes.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        return try { read(nodes) } finally { nodes.forEach { it.recycle() } }
    }

    private fun withPickerResult(action: String, file: File, check: (List<Intent>) -> Unit) {
        val captured = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != action) return null
                captured.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
        }
        instrumentation.addMonitor(monitor)
        try { check(captured) } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun awaitTransferIdle() {
        awaitCondition("Transfer completion") {
            var running = true
            instrumentation.runOnMainSync {
                val value = transfer()
                running = value.javaClass.getDeclaredMethod("getRunning").apply { isAccessible = true }.invoke(value) as Boolean
            }
            !running
        }
        instrumentation.waitForIdleSync()
    }

    private fun transfer(): Any = field("dictionaryTransfer").get(requireActivity())

    private fun requestCode(name: String): Int = field(name).getInt(null)

    private fun field(name: String) = SettingsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun requireActivity() = requireNotNull(activity)

    private fun awaitCondition(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description")
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "bilingual-dictionary-settings-tests")
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDir(): File = File(root, "external_cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("bilingual-dictionary-settings-tests-$name", mode)
    }
}
