package io.secure.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.settings.SettingsDraftBudget
import io.secure.keyboard.settings.SettingsEditableDraft
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Post-fix tests only. Never run the oversized recreation tests on the baseline.
 * The fallback cases remove only the retained edit before real onCreate() consumes saved state.
 * They prove the Bundle-only branch, not an OS process kill; the host kill check is in README.
 * No payload is logged and all user stores are isolated by SettingsUiFixture.
 */
@Suppress("DEPRECATION")
class SettingsEditableDraftLifecycleTest : InstrumentationTestCase() {
    private lateinit var fixture: SettingsUiFixture
    private lateinit var application: Application
    private var dropNextRetainedEdit = false
    private var rewriteNextSavedState: ((Bundle) -> Unit)? = null
    private var fallbackRecreations = 0
    @Volatile private var lifecycleFailure: Throwable? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is SettingsActivity || !dropNextRetainedEdit) return
            // This callback runs in Activity.super.onCreate(), before SettingsActivity consumes
            // lastNonConfigurationInstance. There is no hidden-API or Activity field mutation.
            try {
                val retained = activity.lastNonConfigurationInstance
                assertNotNull("Real recreation must supply the retained holder", retained)
                set(retained!!, "editableDraft", null)
                assertNotNull("Real recreation must supply the system saved-state Bundle", state)
                rewriteNextSavedState?.invoke(state!!)
                fallbackRecreations++
            } catch (failure: Throwable) {
                // Report on the instrumentation/JUnit thread rather than crash Android's main.
                lifecycleFailure = failure
            } finally {
                dropNextRetainedEdit = false
                rewriteNextSavedState = null
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun setUp() {
        super.setUp()
        fixture = SettingsUiFixture(instrumentation)
        application = instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(lifecycle) }
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(lifecycle) }
            fixture.close()
        } finally { super.tearDown() }
    }

    fun testCompleteLargePhraseSurvivesTenActualOrientationChangesWithoutPersistedPayload() {
        open("phrases")
        val text = unicode(600_000)
        var originalTransfer: Any? = null
        onMain {
            editPhrase()
            fields().single().apply {
                setText(text)
                setSelection(length() - 7, 3)
                error = "Synthetic validation error"
                requestFocus()
            }
            originalTransfer = get(activity(), "dictionaryTransfer")
        }
        repeat(10) {
            onMain { assertLargeStateBounded() }
            rotateActual()
            onMain {
                val field = fields().single()
                assertTrue("All 600,000 UTF-16 units must survive rotation $it", text == field.text.toString())
                assertEquals(text.length - 7, field.selectionStart)
                assertEquals(3, field.selectionEnd)
                assertEquals("Synthetic validation error", field.error?.toString())
                assertTrue("Focused field must survive", field.hasFocus())
                assertNull("Retained snapshot is consumed, not a second ongoing copy", get(holder(), "editableDraft"))
                assertSame(originalTransfer, get(activity(), "dictionaryTransfer"))
                assertEquals("phrases", get(activity(), "body").let { it as View }.tag)
            }
        }
        onMain { dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        settle()
        assertTrue("Rotation/Cancel never commits text", fixture.content.phrases().isEmpty())
    }

    fun testFullSavedStateStaysUnder64KiBForAllLargeEditorKinds() {
        val routes = listOf("phrases", "dictionary", "english_dictionary", "profile_outer_portrait")
        for (route in routes) {
            open(route)
            onMain {
                openEditor(route)
                fields().forEachIndexed { index, field ->
                    field.setText(if (route.startsWith("profile")) "7".repeat(600_000) else unicode(600_000))
                    field.error = "Synthetic error $index"
                }
                assertLargeStateBounded()
                val hierarchy = SparseArray<Parcelable>()
                dialog().window!!.decorView.saveHierarchyState(hierarchy)
                fields().forEach { field ->
                    assertFalse(field.isSaveEnabled)
                    assertFalse(field.isSaveFromParentEnabled)
                    assertNull("Manual field must not have hierarchy state", hierarchy[field.id])
                    assertEquals("No input cap or truncation is allowed", 600_000, field.length())
                }
                val dialogParcel = Parcel.obtain()
                try {
                    dialogParcel.writeBundle(Bundle().apply { putSparseParcelableArray("hierarchy", hierarchy) })
                    assertTrue("Even explicit hierarchy saving stays bounded", dialogParcel.dataSize() < 65_536)
                } finally { dialogParcel.recycle() }
                dialog().cancel()
            }
            settle()
        }
    }

    fun testBudgetBoundaryCountsBothFieldsErrorsAndMetadataInActualBundle() {
        open("english_dictionary")
        onMain {
            openEditor("english_dictionary")
            val metadataUnits = "english_dictionary".length + "dictionary".length + "ENGLISH".length
            val errors = listOf("Synthetic word error", "Synthetic code error")
            val textUnits = SettingsDraftBudget.MAX_SAVED_TEXT_CODE_UNITS - metadataUnits - errors.sumOf { it.length }
            fields()[0].setText("w".repeat(8_000))
            fields()[1].setText("r".repeat(textUnits - 8_000))
            fields().forEachIndexed { i, field -> field.error = errors[i] }
            val boundary = savedState()
            assertNotNull("Exact aggregate boundary must persist completely", boundary.getBundle("editable_draft"))
            assertFalse(boundary.getBoolean("oversized_editable_draft"))
            assertTrue(parcelSize(boundary) <= 65_536)
            assertTrue(boundary.getBundle("editable_draft")!!.getString("field_1") == fields()[1].text.toString())
            fields()[1].append("x")
            // TextView may clear its old error after an edit; keep metadata constant at boundary.
            fields().forEachIndexed { i, field -> field.error = errors[i] }
            assertLargeStateBounded()
        }
    }

    fun testSmallCompleteEditsUseActualBundleFallbackWithNoRetainedEdit() {
        for (route in listOf("phrases", "dictionary", "english_dictionary", "profile_outer_portrait")) {
            open(route)
            var expected = emptyList<String>()
            onMain {
                openEditor(route)
                expected = fields().mapIndexed { index, field ->
                    val text = if (route.startsWith("profile")) "000377" else "  Full 𠀀 e\u0301 $index\nText  "
                    field.setText(text)
                    field.setSelection(text.length - 1, 1)
                    field.error = "Synthetic error $index"
                    text
                }
                fields().last().requestFocus()
                val saved = savedState()
                assertNotNull(saved.getBundle("editable_draft"))
                assertFalse(saved.getBoolean("oversized_editable_draft"))
                assertTrue(parcelSize(saved) <= 65_536)
                dropNextRetainedEdit = true
            }
            recreate()
            onMain {
                assertEquals(route, (get(activity(), "body") as View).tag)
                assertEquals(expected.size, fields().size)
                fields().forEachIndexed { index, field ->
                    assertTrue("Bundle fallback preserves all whitespace/Unicode", expected[index] == field.text.toString())
                    assertEquals(expected[index].length - 1, field.selectionStart)
                    assertEquals(1, field.selectionEnd)
                    assertEquals("Synthetic error $index", field.error?.toString())
                }
                assertTrue(fields().last().hasFocus())
                assertNull(get(holder(), "editableDraft"))
                dialog().cancel()
            }
            settle()
        }
        assertEquals(4, fallbackRecreations)
    }

    fun testOversizedBundleFallbackExplainsLossWithoutPartialTextOrStoreChanges() {
        fixture.content.addPhrase("Existing saved phrase")
        val id = fixture.content.phrases().single().id
        val filesBefore = fixtureFileHashes()
        val preferencesBefore = fixture.context.getSharedPreferences("settings", 0).all.toMap()
        open("phrases")
        onMain {
            editPhrase(id)
            fields().single().setText(unicode(600_000))
            assertLargeStateBounded()
            dropNextRetainedEdit = true
        }
        recreate()
        onMain {
            assertTrue("No partial editor may be reopened", fields().isEmpty())
            assertNull(get(activity(), "activeDraft"))
            assertNull(get(holder(), "editableDraft"))
            val messages = ownedDialogs().flatMap { descendants(it.window!!.decorView) }.filterIsInstance<TextView>()
            assertTrue("Loss must be explicit", messages.any { it.text.toString() ==
                "应用进程结束，较大的未保存编辑无法恢复。已保存内容不受影响。" })
            assertTrue("The marker must not become a persistent history", !savedState().getBoolean("oversized_editable_draft"))
        }
        assertEquals(1, fallbackRecreations)
        assertEquals("Existing saved phrase", fixture.content.phrases().single().text)
        assertEquals("Editing/rotation must not write any fixture file", filesBefore, fixtureFileHashes())
        assertEquals("Editing/rotation must not write preferences", preferencesBefore,
            fixture.context.getSharedPreferences("settings", 0).all)
        fixture.assertActualDataUnchanged()
    }

    fun testCancelBackReplacementNewIntentSaveAndPermanentFinishClearRetainedSnapshot() {
        open("phrases")
        for (mode in 0..4) {
            onMain {
                editPhrase()
                fields().single().setText(if (mode == 4) "Saved once" else unicode(600_000))
            }
            // Dialog.show dispatches OnShow through the main queue. Let the real Save listener
            // install before clicking; a same-turn click can invoke only Android's default dismiss.
            settle()
            onMain {
                activity().onRetainNonConfigurationInstance()
                assertNotNull(get(holder(), "editableDraft"))
                when (mode) {
                    0 -> dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    1 -> activity().onBackPressed()
                    2 -> editPhrase() // A new logical editor drops the previous complete snapshot.
                    3 -> newIntent("emoji")
                    4 -> dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
            }
            settle()
            onMain {
                assertNull("Dismissal/replacement must drop snapshot for mode $mode", get(holder(), "editableDraft"))
                if (mode == 2) {
                    assertEquals(0, fields().single().length())
                    dialog().cancel()
                }
            }
            settle()
            if (mode != 4) assertTrue(fixture.content.phrases().isEmpty())
            if (mode == 3) open("phrases")
        }
        assertEquals("Saved once", fixture.content.phrases().single().text)
        var retained: Any? = null
        onMain {
            editPhrase()
            fields().single().setText(unicode(600_000))
            retained = activity().onRetainNonConfigurationInstance()
            activity().finish()
        }
        await("Permanent destroy") { fixture.current == null }
        onMain {
            assertNull(get(retained!!, "editableDraft"))
            assertNull(get(get(retained!!, "dictionaryTransfer")!!, "observer"))
        }
    }

    fun testDeletedItemsAndMismatchedRouteNeverResurrectDrafts() {
        fixture.content.addPhrase("Saved phrase")
        val phrase = fixture.content.phrases().single()
        open("phrases")
        onMain { editPhrase(phrase.id); fields().single().setText(unicode(600_000)) }
        fixture.content.deletePhrase(phrase.id)
        recreate()
        onMain { assertTrue(fields().isEmpty()); assertNull(get(holder(), "editableDraft")) }
        for (kind in DictionaryKind.values()) {
            fixture.content.addDictionary("Saved word", "test", kind)
            val item = fixture.content.dictionary(kind).single()
            open(if (kind == DictionaryKind.ENGLISH) "english_dictionary" else "dictionary")
            onMain { editDictionary(kind, item); fields()[0].setText(unicode(600_000)) }
            fixture.content.deleteDictionary(item.id, kind)
            recreate()
            onMain { assertTrue(fields().isEmpty()); assertNull(get(holder(), "editableDraft")) }
        }
        open("phrases")
        onMain {
            editPhrase(); fields().single().setText("Small draft")
            dropNextRetainedEdit = true
            rewriteNextSavedState = { it.putString("settings_route", "home") }
        }
        recreate()
        onMain {
            assertEquals("home", (get(activity(), "body") as View).tag)
            assertTrue(fields().isEmpty())
        }
    }

    fun testSelectionOffsetsClampAfterCompleteRestoreAndPreserveValidDirection() {
        open("phrases")
        onMain {
            for ((text, start, end) in listOf(Triple("abc𠀀", -1, Int.MAX_VALUE),
                Triple("abc𠀀", 4, 1), Triple("", -1, 7))) {
                editPhrase(draft = Bundle().apply {
                    putString("field_0", text)
                    putInt("selection_start_0", start)
                    putInt("selection_end_0", end)
                    putInt("focused", 0)
                })
                val field = fields().single()
                assertTrue(text == field.text.toString())
                assertEquals(start.coerceIn(0, text.length), field.selectionStart)
                assertEquals(end.coerceIn(0, text.length), field.selectionEnd)
            }
        }
    }

    /** Explicit GC attribution check; do not confuse this with natural PSS/recovery evidence. */
    fun testRetainedHolderDoesNotRootDestroyedActivitiesAfterReplacementAndExitForcedGc() {
        open("phrases")
        var retained: Any? = null
        val snapshots = mutableListOf<WeakReference<Any>>()
        onMain {
            editPhrase(); fields().single().setText(unicode(600_000))
            retained = activity().onRetainNonConfigurationInstance()
            snapshots.add(WeakReference(get(retained!!, "editableDraft")!!))
            val instanceFields = retained!!.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertEquals(setOf("dictionaryTransfer", "editableDraft"), instanceFields.map { it.name }.toSet())
            assertFalse(SettingsEditableDraft::class.java.declaredFields.any { it.name == "this\$0" })
        }
        repeat(3) { recreate() }
        onMain {
            assertSame(retained, holder())
            assertNull(get(retained!!, "editableDraft"))
            activity().finish()
        }
        await("Permanent destroy") { fixture.current == null }
        settle()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline &&
            (fixture.destroyedActivities.any { it.get() != null } || snapshots.any { it.get() != null })) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            settle()
            SystemClock.sleep(100)
        }
        // Keep the holder strongly alive through this assertion: collectibility with the owner
        // retained is the relevant invariant, not collection after dropping the owner itself.
        assertNull(get(retained!!, "editableDraft"))
        assertNull(get(get(retained!!, "dictionaryTransfer")!!, "observer"))
        assertTrue("Old snapshot retained; inspect heap owners", snapshots.all { it.get() == null })
        assertTrue("Destroyed Activity remains reachable; inspect root before classifying leak",
            fixture.destroyedActivities.all { it.get() == null })
    }

    private fun open(route: String) {
        if (fixture.current is SettingsActivity && !activity().isFinishing) {
            onMain { newIntent(route) }
        } else {
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .putExtra("page", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        await("Opened settings") { fixture.current is SettingsActivity }
        settle()
    }

    private fun newIntent(route: String) {
        SettingsActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(activity(), Intent(instrumentation.targetContext, SettingsActivity::class.java).putExtra("page", route))
    }

    private fun openEditor(route: String) {
        when (route) {
            "phrases" -> editPhrase()
            "dictionary" -> editDictionary(DictionaryKind.CHINESE)
            "english_dictionary" -> editDictionary(DictionaryKind.ENGLISH)
            else -> assertTrue(descendants(activity().window.decorView).filterIsInstance<Button>().single {
                it.tag == "anchor:number:profile_outer_portrait:键盘高度"
            }.performClick())
        }
    }

    private fun editPhrase(id: String? = null, draft: Bundle? = null) {
        SettingsActivity::class.java.getDeclaredMethod("editPhrase", String::class.java, Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity(), id, draft)
    }

    private fun editDictionary(kind: DictionaryKind, item: ContentStore.DictionaryEntry? = null) {
        SettingsActivity::class.java.getDeclaredMethod("editDictionary", ContentStore.DictionaryEntry::class.java,
            DictionaryKind::class.java, Bundle::class.java).apply { isAccessible = true }.invoke(activity(), item, kind, null)
    }

    private fun assertLargeStateBounded() {
        val state = savedState()
        assertNull("Oversized text must never enter saved state", state.getBundle("editable_draft"))
        assertTrue(state.getBoolean("oversized_editable_draft"))
        val size = parcelSize(state)
        assertTrue("Complete Activity state exceeds app diagnostic budget: $size bytes", size <= 65_536)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("draft_check", "post_fix_complete_state")
            putInt("completeActivityStateParcelBytes", size)
            putInt("fieldCount", fields().size)
        })
    }

    private fun savedState() = Bundle().also { instrumentation.callActivityOnSaveInstanceState(activity(), it) }
    private fun parcelSize(state: Bundle): Int {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(state); parcel.dataSize() } finally { parcel.recycle() }
    }

    private fun fixtureFileHashes() = fixture.context.root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(fixture.context.root).path to settingsQaSha256(it) }

    private fun rotateActual() {
        val previous = WeakReference(activity())
        val destroyed = fixture.destroyedCount
        val expected = if (activity().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        onMain {
            activity().requestedOrientation = if (expected == Configuration.ORIENTATION_PORTRAIT)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        await("Actual orientation transition", 30_000) {
            fixture.destroyedCount > destroyed && fixture.current is SettingsActivity &&
                fixture.current !== previous.get() && activity().resources.configuration.orientation == expected
        }
        settle()
    }

    private fun recreate() {
        val previous = WeakReference(activity())
        val destroyed = fixture.destroyedCount
        onMain { activity().recreate() }
        await("Settings recreation", 30_000) { fixture.destroyedCount > destroyed &&
            fixture.current is SettingsActivity && fixture.current !== previous.get() }
        settle()
    }

    private fun unicode(length: Int) = buildString(length) {
        while (this.length + 6 <= length) append("𠀀e\u0301\n中")
        while (this.length < length) append('x')
    }
    private fun activity() = fixture.current as SettingsActivity
    private fun holder() = get(activity(), "lifetimeState")!!
    @Suppress("UNCHECKED_CAST") private fun fields() = get(activity(), "draftFields") as List<EditText>
    private fun dialog() = get(activity(), "activeDialog") as AlertDialog
    @Suppress("UNCHECKED_CAST") private fun ownedDialogs() = get(activity(), "dialogs") as Set<AlertDialog>
    private fun get(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        lifecycleFailure?.let { throw it }
    }
    private fun settle() {
        instrumentation.waitForIdleSync()
        lifecycleFailure?.let { throw it }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            lifecycleFailure?.let { throw it }
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $message")
    }
}
