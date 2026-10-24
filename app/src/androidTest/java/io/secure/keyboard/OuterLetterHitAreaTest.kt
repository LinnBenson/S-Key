package io.secure.keyboard

import io.secure.keyboard.diagnostics.checkedMain

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.roundToInt
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.IndentedKeyRow
import io.secure.keyboard.ui.KeyContainer

/** Pixel-identical production key faces plus real root-dispatched edge/overlapping touches. */
@Suppress("DEPRECATION")
class OuterLetterHitAreaTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: ServiceHarness
    private lateinit var editor: EditText
    private lateinit var connection: ObservedConnection
    private var originalCompletion = true
    private var originalFourCode = false
    private var originalVibration = true
    private var originalLongPress = true
    private val context get() = instrumentation.targetContext
    private val root get() = field<ViewGroup>("root")
    private var eventTime = 0L
    private var downTime = 0L
    private val pointers = linkedMapOf<Int, PointF>()

    private class ServiceHarness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(context: Context): OfflineKeyboardService {
            setContext(context)
            setApplication(context.applicationContext as Application)
            setUp()
            startService(Intent(context, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }

    private class ObservedConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        val commits = mutableListOf<String>()
        val keyEvents = mutableListOf<KeyEvent>()
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents.add(KeyEvent(event))
            return super.sendKeyEvent(event)
        }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val accepted = super.commitText(text, newCursorPosition)
            if (accepted) commits.add(text.toString())
            return accepted
        }
    }

    private lateinit var explicitInputPreferences: ExplicitInputPreferences

    override fun setUp() {
        super.setUp()
        explicitInputPreferences = ExplicitInputPreferences(context)
        SettingsStore(context).let {
            originalCompletion = it.englishCompletion
            originalFourCode = it.fourCodeAutoCommit
            originalVibration = it.vibration
            originalLongPress = it.longPressSymbols
            it.englishCompletion = false
            it.fourCodeAutoCommit = false
            it.vibration = false
            it.longPressSymbols = false
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        instrumentation.checkedMain {
            harness = ServiceHarness()
            service = harness.create(context)
            editor = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                showSoftInputOnFocus = false
            }
            val keyboard = service.onCreateInputView()
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(-1, 100))
                addView(keyboard, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            editor.requestFocus()
            val info = EditorInfo()
            connection = ObservedConnection(requireNotNull(editor.onCreateInputConnection(info)))
            service.onCreateInputMethodInterface().startInput(connection, info)
            setField("active", true)
            eventTime = SystemClock.uptimeMillis()
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.checkedMain {
                ready = field<Boolean>("loaded") && root.isAttachedToWindow && key("a").width > 0
            }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue("Production keyboard and dictionary must be ready", ready)
    }

    override fun tearDown() {
        try {
            instrumentation.checkedMain {
                if (::harness.isInitialized) harness.close()
                if (::activity.isInitialized) activity.finish()
            }
            SettingsStore(context).let {
                it.englishCompletion = originalCompletion
                it.fourCodeAutoCommit = originalFourCode
                it.vibration = originalVibration
                it.longPressSymbols = originalLongPress
            }
        } finally {
            explicitInputPreferences.close()
            super.tearDown()
        }
    }

    fun testActualProductionRowHasExactlyUnchangedPixelsAndContentGeometry() {
        instrumentation.checkedMain {
            for (chinese in listOf(true, false)) for (upper in listOf(false, true)) {
                for (width in listOf(721, 1080, 1801)) for (height in listOf(dp(36), dp(64))) {
                    for (split in listOf(false, true)) {
                        invoke("clearPending")
                        setField("chinese", chinese)
                        setField("shifted", upper)
                        setField("symbols", false)
                        setField("panel", "keys")
                        setField("outer", false)
                        setField("profile", field<SettingsStore.LayoutProfile>("profile").copy(split = split))
                        // Rebuild synchronously, before this fresh row has been measured or
                        // expanded. Render its very same production children in the old layout.
                        invoke("renderBody")
                        val expanded = key(if (upper) "A" else "a").parent as IndentedKeyRow
                        val children = (0 until expanded.childCount).map { expanded.getChildAt(it) }
                        expanded.removeAllViews()
                        val legacy = LinearLayout(service).apply {
                            orientation = expanded.orientation
                            gravity = expanded.gravity
                            layoutDirection = expanded.layoutDirection
                        }
                        children.forEach { legacy.addView(it) }
                        measureAndLayout(legacy, width, height)
                        val baselineBounds = descendantBounds(legacy)
                        val baselineKeyBounds = keys(legacy).associateWith { Rect(it.left, it.top, it.right, it.bottom) }
                        val baseline = rasterize(legacy)
                        legacy.removeAllViews()
                        children.forEach { expanded.addView(it) }
                        measureAndLayout(expanded, width, height)
                        val result = rasterize(expanded)
                        try {
                            assertTrue(
                                "All key pixels stay identical: Chinese=$chinese upper=$upper width=$width height=$height split=$split",
                                baseline.sameAs(result),
                            )
                            assertEquals("Every label/hint stays at the exact same pixel bounds", baselineBounds, descendantBounds(expanded))
                            val rowKeys = keys(expanded).sortedBy { it.left }
                            assertEquals("A owns all of the left indent", 0, rowKeys.first().left)
                            assertEquals("L owns all of the right indent", width, rowKeys.last().right)
                            rowKeys.forEachIndexed { index, key ->
                                val old = requireNotNull(baselineKeyBounds[key])
                                assertEquals(old.top, key.top)
                                assertEquals(old.bottom, key.bottom)
                                if (index != 0) assertEquals("Only the outermost left edge changes", old.left, key.left)
                                if (index != rowKeys.lastIndex) assertEquals("Only the outermost right edge changes", old.right, key.right)
                            }
                            rowKeys.zipWithNext().forEach { (left, right) ->
                                assertTrue("Expanded keys must never overlap S/K or the middle split", left.right <= right.left)
                            }
                            // Exercise a layout-only pass as well as a fresh measure pass.
                            expanded.layout(0, 1, width, height + 1)
                            expanded.layout(0, 0, width, height)
                            expanded.requestLayout()
                            measureAndLayout(expanded, width + 17, height + 3)
                            expanded.requestLayout()
                            measureAndLayout(expanded, width, height)
                            val repeated = rasterize(expanded)
                            try {
                                assertTrue("Repeated layouts must not accumulate an inset or drift", baseline.sameAs(repeated))
                                assertEquals(baselineBounds, descendantBounds(expanded))
                            } finally {
                                repeated.recycle()
                            }
                        } finally {
                            baseline.recycle()
                            result.recycle()
                        }
                    }
                }
            }
        }
    }

    fun testEveryPartOfBothAddedZonesTypesChineseAndEnglish() {
        for (chinese in listOf(true, false)) {
            reset(chinese)
            instrumentation.checkedMain {
                val expected = StringBuilder()
                for (letter in listOf("a", "l")) {
                    val target = key(letter)
                    val added = addedZone(target, letter == "a")
                    assertTrue("The requested indent must have a useful width", added.width() > 1)
                    for (x in listOf(added.left + .5f, added.exactCenterX(), added.right - .5f)) {
                        for (y in listOf(added.top + .5f, added.exactCenterY(), added.bottom - .5f)) {
                            tap(PointF(x, y))
                            expected.append(letter)
                        }
                    }
                }
                assertText(chinese, expected.toString())
            }
        }
    }

    fun testOneFingerInIndentAndOneOnSameKeyPreserveBothTapsInEitherOrder() {
        for (chinese in listOf(true, false)) for (reverseRelease in listOf(false, true)) {
            reset(chinese)
            instrumentation.checkedMain {
                val expected = StringBuilder()
                for (letter in listOf("a", "l")) for (indentFirst in listOf(false, true)) {
                    val target = key(letter)
                    val indent = center(addedZone(target, letter == "a"))
                    val face = center(bounds(target.getChildAt(0)))
                    val targetBounds = bounds(target)
                    val label = target.getChildAt(0)
                    val unscrolledContent = Rect(0, 0, label.width, label.height).also {
                        root.offsetDescendantRectToMyCoords(label, it)
                    }
                    val coordinates = "letter=$letter Chinese=$chinese indentFirst=$indentFirst reverse=$reverseRelease key=$targetBounds child=${bounds(label)} indent=$indent face=$face labelScroll=${label.scrollX},${label.scrollY} oldContentRect=$unscrolledContent padding=${target.paddingLeft},${target.paddingRight}"
                    android.util.Log.i("OuterLetterHitAreaTest", coordinates)
                    assertTrue("Indent must really lie inside the expanded key: $coordinates",
                        targetBounds.contains(indent.x.toInt(), indent.y.toInt()))
                    assertTrue("The visible face must really lie inside the same key: $coordinates",
                        targetBounds.contains(face.x.toInt(), face.y.toInt()))
                    assertEquals("The tested face is the actual padded visual viewport",
                        Rect(targetBounds.left + target.paddingLeft, targetBounds.top + target.paddingTop,
                            targetBounds.right - target.paddingRight, targetBounds.bottom - target.paddingBottom),
                        bounds(label))
                    down(3, if (indentFirst) indent else face)
                    down(13, if (indentFirst) face else indent)
                    if (reverseRelease) { up(13); up(3) } else { up(3); up(13) }
                    expected.append(letter).append(letter)
                    android.util.Log.i("OuterLetterHitAreaTest", "$coordinates expected=$expected code=${field<String>("code")} host=${editor.text}")
                    assertSame("Expanded touch targets stay attached", target, key(letter))
                    assertEquals("Both releases restore key feedback", 1f, target.alpha)
                }
                assertText(chinese, expected.toString())
            }
        }
    }

    fun testIndentAndOtherKeysOrRowsPreserveSplitPointerReleaseOrder() {
        for (chinese in listOf(true, false)) for (reverse in listOf(false, true)) {
            reset(chinese)
            instrumentation.checkedMain {
                val expected = StringBuilder()
                for ((edge, other) in listOf("a" to "s", "l" to "k", "a" to "l", "a" to "q", "l" to "p", "a" to "m")) {
                    down(4, center(addedZone(key(edge), edge == "a")))
                    val otherPoint = if (other == "l") center(addedZone(key(other), false)) else center(bounds(key(other)))
                    down(12, otherPoint)
                    if (reverse) { up(12); up(4); expected.append(other).append(edge) }
                    else { up(4); up(12); expected.append(edge).append(other) }
                }
                assertText(chinese, expected.toString())
            }
        }
    }

    fun testAddedZonesDoNotStealAdjacentRowsAndRespectSharedBoundaries() {
        reset(false)
        instrumentation.checkedMain {
            val a = key("a")
            val l = key("l")
            val left = center(addedZone(a, true))
            val right = center(addedZone(l, false))
            val aBounds = bounds(a)
            val lBounds = bounds(l)
            tap(PointF(left.x, aBounds.top - .5f))
            tap(PointF(right.x, lBounds.top - .5f))
            assertText(false, "qp")
            // The exact A/S and K/L boundaries continue to belong to their original key.
            tap(PointF(aBounds.right - .5f, left.y))
            tap(PointF(aBounds.right + .5f, left.y))
            tap(PointF(lBounds.left - .5f, right.y))
            tap(PointF(lBounds.left + .5f, right.y))
            assertText(false, "qpaskl")
            tap(PointF(left.x, aBounds.bottom + .5f))
            assertTrue("The row below the left indent remains Shift", field("shifted"))
            tap(left)
            assertText(false, "qpasklA")
            tap(PointF(right.x, lBounds.bottom + .5f))
            assertEquals("The lower-right boundary dispatches exactly one Delete down/up pair",
                listOf(KeyEvent.KEYCODE_DEL to KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL to KeyEvent.ACTION_UP),
                connection.keyEvents.map { it.keyCode to it.action })
            assertFalse(field<Boolean>("shifted"))
        }
        // EditText's InputConnection posts key events to the host. Let that actual host work
        // execute before asserting its result; the synchronous assertion above proves routing.
        instrumentation.waitForIdleSync()
        instrumentation.checkedMain {
            assertEquals("The row below the right indent remains Delete", "qpaskl", editor.text.toString())
        }
    }

    fun testExpandedEdgesRespectOneShotShiftAndCapsInChineseAndEnglish() {
        for (chinese in listOf(true, false)) {
            reset(chinese)
            instrumentation.checkedMain {
                tap(center(bounds(key("大写"))))
                val a = key("A")
                val l = key("L")
                down(3, center(addedZone(a, true)))
                down(13, center(addedZone(l, false)))
                up(3)
                assertSame("One-shot Shift changes the face, not the target", l, key("l"))
                up(13)
                if (chinese) {
                    assertEquals("A", editor.text.toString())
                    assertEquals("l", field<String>("code"))
                } else assertText(false, "Al")
            }
            reset(chinese)
            instrumentation.checkedMain {
                tap(center(bounds(key("大写"))))
                tap(center(bounds(key("单次大写已开启"))))
                assertTrue("Rapid Shift double tap locks case", field("capsLocked"))
                tap(center(addedZone(key("A"), true)))
                tap(center(addedZone(key("L"), false)))
                assertEquals("AL", editor.text.toString())
                assertEquals("", field<String>("code"))
                assertTrue(field<Boolean>("capsLocked"))
            }
        }
    }

    fun testCancelOfOverlappingIndentTouchesLeavesNoPhantomOrStuckKey() {
        reset(false)
        instrumentation.checkedMain {
            val a = key("a")
            down(3, center(addedZone(a, true)))
            down(13, center(bounds(a.getChildAt(0))))
            dispatch(MotionEvent.ACTION_CANCEL)
            pointers.clear()
            assertText(false, "")
            assertEquals(1f, a.alpha)
            tap(center(addedZone(a, true)))
            assertText(false, "a")
        }
    }

    private fun reset(chinese: Boolean) {
        instrumentation.checkedMain {
            check(pointers.isEmpty())
            invoke("clearPending")
            editor.setText("")
            connection.commits.clear()
            connection.keyEvents.clear()
            setField("chinese", chinese)
            setField("shifted", false)
            setField("capsLocked", false)
            setField("symbols", false)
            setField("panel", "keys")
            invoke("renderHeader")
            invoke("renderBody")
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertText(chinese: Boolean, expected: String) {
        if (chinese) {
            assertEquals("Every edge touch reaches local Chinese code", expected, field<String>("code"))
            assertEquals("", editor.text.toString())
            assertTrue(connection.commits.isEmpty())
        } else {
            assertEquals("Every edge touch reaches the host in release order", expected, editor.text.toString())
            assertEquals(expected, connection.commits.joinToString(""))
        }
    }

    private fun keys(view: View): List<KeyContainer> = when (view) {
        is KeyContainer -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun key(description: String): KeyContainer = requireNotNull(keys(root).find {
        it.contentDescription?.toString() == description
    }) { "Key missing: $description" }

    private fun bounds(view: View): Rect = Rect().also {
        // A centered single-line TextView scrolls inside a VERY_WIDE text layout. Its
        // viewport starts at scrollX/scrollY, not at the offscreen content origin (0, 0).
        view.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(view, it)
    }

    private fun addedZone(view: KeyContainer, left: Boolean): Rect = bounds(view).apply {
        if (left) right = this.left + view.paddingLeft - dp(2)
        else this.left = right - view.paddingRight + dp(2)
    }

    private fun descendantBounds(row: LinearLayout): Map<View, Rect> = keys(row).flatMap { key ->
        (0 until key.childCount).map { key.getChildAt(it) }
    }.associateWith { child ->
        Rect().also {
            child.getDrawingRect(it)
            row.offsetDescendantRectToMyCoords(child, it)
        }
    }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun rasterize(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }

    private fun center(rect: Rect) = PointF(rect.exactCenterX(), rect.exactCenterY())
    private fun tap(point: PointF) { down(0, point); up(0) }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()

    private fun down(id: Int, point: PointF) {
        check(id !in pointers)
        if (pointers.isEmpty()) downTime = eventTime + 1
        pointers[id] = point
        val index = pointers.keys.indexOf(id)
        dispatch(if (pointers.size == 1) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun up(id: Int) {
        val index = pointers.keys.indexOf(id)
        check(index >= 0)
        dispatch(if (pointers.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        pointers.remove(id)
    }

    private fun dispatch(action: Int) {
        val properties = pointers.keys.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = pointers.values.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; pressure = 1f; size = .1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, ++eventTime, action, pointers.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.get(service) as T

    private fun setField(name: String, value: Any?) {
        io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.set(service, value)
    }

    private fun invoke(name: String) = io.secure.keyboard.SessionTestAccess.method(name).apply { isAccessible = true }.invoke(service)
}
