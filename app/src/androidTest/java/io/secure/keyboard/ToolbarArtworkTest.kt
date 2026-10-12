package io.secure.keyboard

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.ToolIcon
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the selected A floating pair and C edit artwork; no full keyboard regression. */
@Suppress("DEPRECATION")
class ToolbarArtworkTest : InstrumentationTestCase() {
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: Harness
    private lateinit var sandbox: IsolatedContext
    private lateinit var settings: SettingsStore
    private val context get() = instrumentation.targetContext
    private val toolbar get() = field<LinearLayout>("toolbarGroup")

    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(context: Context): OfflineKeyboardService {
            setContext(context)
            setApplication(context.applicationContext as Application)
            setUp()
            startService(Intent(context, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }

    override fun setUp() {
        super.setUp()
        sandbox = IsolatedContext(context)
        sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsStore(sandbox).apply { vibration = false }
        instrumentation.runOnMainSync {
            harness = Harness()
            service = harness.create(context)
            OfflineKeyboardService::class.java.getDeclaredField("settings")
                .apply { isAccessible = true }.set(service, settings)
            service.onCreateInputMethodInterface().startInput(
                BaseInputConnection(View(context), true),
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
            )
        }
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync { if (::harness.isInitialized) harness.close() }
            sandbox.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            super.tearDown()
        }
    }

    fun testServiceTogglesTheFloatingArtworkTwiceWithoutChangingToolbarContracts() {
        instrumentation.runOnMainSync {
            assertEquals(listOf("floating", "clipboard", "quickedit", "emoji", "voice"), settings.toolbarPins)
            val host = service.currentInputConnection
            assertNotNull(host)
            for (dark in listOf(false, true)) {
                settings.theme = if (dark) "dark" else "light"
                for (outer in listOf(false, true)) for (landscape in listOf(false, true))
                    settings.saveProfile(outer, landscape, SettingsStore.LayoutProfile(
                        heightDp = 285, floatingWidthDp = 280, floatingHeightDp = 260,
                    ))
                service.onCreateInputView()
                layoutRoot()
                val originalProfile = field<SettingsStore.LayoutProfile>("profile")
                val dockedBounds = assertToolbarContract("floating_enter", dark)
                assertTrue(toolbar.getChildAt(1).performClick())
                layoutRoot()
                assertEquals(originalProfile.copy(floating = true), field<SettingsStore.LayoutProfile>("profile"))
                assertToolbarContract("floating_exit", dark)
                assertSame("Toggling artwork must retain the editor connection", host, service.currentInputConnection)
                assertTrue(toolbar.getChildAt(1).performClick())
                layoutRoot()
                assertEquals(originalProfile, field<SettingsStore.LayoutProfile>("profile"))
                assertEquals("Returning to docked mode restores every toolbar hit bound", dockedBounds,
                    assertToolbarContract("floating_enter", dark))
                assertSame(host, service.currentInputConnection)

                val pinnedEdit = toolbar.getChildAt(3) as ToolIcon
                assertEquals("quickedit", kind(pinnedEdit))
                assertEquals("快捷编辑", pinnedEdit.contentDescription.toString())
                assertTrue(toolbar.getChildAt(0).performClick())
                layoutRoot()
                val moreScroll = descendants(field<FrameLayout>("body")).filterIsInstance<ScrollView>().single()
                assertFalse("More panel hides its vertical scroll indicator", moreScroll.isVerticalScrollBarEnabled)
                assertTrue("More tools remain vertically scrollable", moreScroll.canScrollVertically(1))
                val moreEdit = descendants(field<FrameLayout>("body"))
                    .filterIsInstance<ToolIcon>().single { it.contentDescription == "快捷编辑" }
                assertEquals("More and toolbar use the same C artwork", kind(pinnedEdit), kind(moreEdit))
                assertEquals(dp(38), moreEdit.layoutParams.width)
                assertEquals(dp(38), moreEdit.layoutParams.height)
                assertEquals(pinnedEdit.tint, moreEdit.tint)
                assertTrue(moreEdit.performClick())
                assertEquals("quickedit", field<String>("panel"))
                assertTrue(toolbar.getChildAt(3).performClick())
                assertEquals("keys", field<String>("panel"))
                assertSame(host, service.currentInputConnection)
            }
        }
    }

    fun testSelectedSvgGeometryIsDistinctAndUsesLightDarkAndCustomTints() {
        instrumentation.runOnMainSync {
            val kinds = listOf("floating_enter", "floating_exit", "quickedit")
            val goldenNames = listOf("floating-enter", "floating-exit", "quickedit")
            val tints = listOf(
                Color.parseColor(settings.themeTokens(false).toolbarIcon),
                Color.parseColor(settings.themeTokens(true).toolbarIcon),
                Color.rgb(199, 37, 123),
            )
            assertTrue("Light and dark palette tokens differ", tints[0] != tints[1])
            val firstMasks = mutableMapOf<String, List<Int>>()
            for (tint in tints) for ((index, kind) in kinds.withIndex()) {
                val bitmap = raster(kind, tint)
                val golden = instrumentation.context.assets.open("toolbar/${goldenNames[index]}.png")
                    .use { requireNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    val theme = when (tint) {
                        tints[0] -> "light"
                        tints[1] -> "dark"
                        else -> "custom"
                    }
                    saveBitmap(bitmap, "toolbar-artwork-$kind-$theme.png")
                    assertEquals(golden.width, bitmap.width)
                    assertEquals(golden.height, bitmap.height)
                    val actual = pixels(bitmap)
                    val expected = pixels(golden)
                    val mask = actual.map { Color.alpha(it) }
                    assertTrue("$kind must contain visible artwork", mask.count { it > 200 } > 100)
                    assertTrue("Every opaque $kind pixel must use its supplied theme tint",
                        actual.filter { Color.alpha(it) == 255 }.all { it == tint })
                    assertTrue("$kind has enough opaque pixels to verify the tint",
                        actual.count { Color.alpha(it) == 255 } > 50)
                    val averageAlphaError = actual.indices.sumOf {
                        abs(Color.alpha(actual[it]) - Color.alpha(expected[it])).toLong()
                    }.toDouble() / actual.size
                    // Inkscape (approved SVG previews) and Android use different antialiasers.
                    assertTrue("$kind must match the chosen SVG silhouette, error=$averageAlphaError",
                        averageAlphaError < 10.0)
                    val actualBounds = inkBounds(actual, bitmap.width)
                    val expectedBounds = inkBounds(expected, golden.width)
                    assertTrue("$kind keeps the selected artwork inside the same 24-unit canvas",
                        abs(actualBounds.left - expectedBounds.left) <= 1 &&
                            abs(actualBounds.top - expectedBounds.top) <= 1 &&
                            abs(actualBounds.right - expectedBounds.right) <= 1 &&
                            abs(actualBounds.bottom - expectedBounds.bottom) <= 1)
                    if (tint == tints.first()) firstMasks[kind] = mask
                    else assertEquals("Theme changes must not change $kind geometry", firstMasks[kind], mask)
                } finally {
                    bitmap.recycle()
                    golden.recycle()
                }
            }
            assertFalse("Enter and exit must have distinct pixels",
                firstMasks.getValue("floating_enter") == firstMasks.getValue("floating_exit"))
            assertFalse("C edit must not reuse the floating artwork",
                firstMasks.getValue("quickedit") == firstMasks.getValue("floating_enter"))
        }
    }

    private fun assertToolbarContract(expectedKind: String, dark: Boolean): List<Rect> {
        val header = field<LinearLayout>("header")
        assertSame("Icons remain direct children of the existing toolbar host", header, toolbar.parent)
        assertEquals(LinearLayout.HORIZONTAL, toolbar.orientation)
        assertEquals(settings.toolbarPins.size + 1, toolbar.childCount)
        assertEquals(dp(18) + dp(40), toolbar.height)
        assertEquals("keys", field<String>("panel"))
        val floating = toolbar.getChildAt(1) as ToolIcon
        assertEquals(expectedKind, kind(floating))
        assertEquals("悬浮键盘", floating.contentDescription.toString())
        assertEquals(Color.parseColor(settings.themeTokens(dark).toolbarIcon), floating.tint)
        val capture = Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888)
        try {
            header.draw(Canvas(capture))
            saveBitmap(capture, "toolbar-service-$expectedKind-${if (dark) "dark" else "light"}.png")
        } finally {
            capture.recycle()
        }
        val expectedSlotWidth = header.width.toFloat() / (settings.toolbarPins.size + 2)
        return (0 until toolbar.childCount).map { index ->
            val icon = toolbar.getChildAt(index)
            assertTrue(icon is ToolIcon)
            assertTrue(icon.isClickable && icon.isFocusable && icon.hasOnClickListeners())
            assertEquals(listOf(0, 0, 0, 0),
                listOf(icon.paddingLeft, icon.paddingTop, icon.paddingRight, icon.paddingBottom))
            val params = icon.layoutParams as LinearLayout.LayoutParams
            assertEquals(0, params.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
            assertEquals(1f, params.weight, 0f)
            val hit = Rect().also { icon.getHitRect(it) }
            assertEquals(Rect(icon.left, 0, icon.right, toolbar.height), hit)
            assertTrue("Each mode retains the original evenly weighted touch slots",
                abs(hit.width() - expectedSlotWidth) <= 2f)
            assertTrue("Slot positions stay proportional within each mode",
                abs(hit.left - index * expectedSlotWidth) <= 2f)
            hit
        }
    }

    private fun layoutRoot() {
        val root = field<FrameLayout>("root")
        val box = field<LinearLayout>("box")
        val params = box.layoutParams as FrameLayout.LayoutParams
        root.measure(
            View.MeasureSpec.makeMeasureSpec(service.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height + params.bottomMargin, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun raster(kind: String, tint: Int): Bitmap {
        // Exactly 3 pixels per SVG unit, independent of the emulator density. The production
        // default artwork size is untouched; only this golden comparison explicitly requests 72px.
        val inset = dp(8)
        val size = 72 + inset * 2
        val view = ToolIcon(context, kind, tint, artworkSizeDp = 72f / context.resources.displayMetrics.density)
        view.layout(0, 0, size, size)
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        return Bitmap.createBitmap(full, inset, inset, 72, 72).also { full.recycle() }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun inkBounds(pixels: IntArray, width: Int): Rect {
        val points = pixels.indices.filter { Color.alpha(pixels[it]) > 32 }
        return Rect(points.minOf { it % width }, points.minOf { it / width },
            points.maxOf { it % width } + 1, points.maxOf { it / width } + 1)
    }

    private fun kind(icon: ToolIcon): String = ToolIcon::class.java.getDeclaredField("kind")
        .apply { isAccessible = true }.get(icon) as String

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OfflineKeyboardService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service) as T

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("toolbar-artwork-test-$name", mode)
    }
}
