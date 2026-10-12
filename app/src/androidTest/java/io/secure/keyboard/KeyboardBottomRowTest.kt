package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.secure.keyboard.data.SettingsStore
import io.secure.keyboard.ui.KeyContainer
import io.secure.keyboard.ui.PunctuationKeyLabel
import io.secure.keyboard.ui.ToolIcon

/** Real production key views, measured and rasterized at normal and minimum keyboard heights. */
@Suppress("DEPRECATION")
class KeyboardBottomRowTest : InstrumentationTestCase() {
    private lateinit var service: OfflineKeyboardService
    private lateinit var harness: ServiceHarness
    private var savedProfiles = emptyMap<String, String>()
    private var originalTheme = "system"
    private var attachedActivity: Activity? = null
    private val context
        get() = instrumentation.targetContext

    private val prefs
        get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private class ServiceHarness :
        ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
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
        savedProfiles =
            prefs.all.filterKeys { it.startsWith("profile_") }.mapValues { it.value as String }
        originalTheme = SettingsStore(context).theme
        instrumentation.runOnMainSync {
            harness = ServiceHarness()
            service = harness.create(context)
        }
    }

    override fun tearDown() {
        try {
            instrumentation.runOnMainSync {
                if (::harness.isInitialized) harness.close()
                attachedActivity?.finish()
                attachedActivity = null
            }
            SettingsStore(context).theme = originalTheme
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("profile_") }.forEach { edit.remove(it) }
            savedProfiles.forEach { (key, value) -> edit.putString(key, value) }
            edit.commit()
        } finally {
            super.tearDown()
        }
    }

    fun testCombinedPunctuationUsesGlobeThemeRolesInBothLanguagesAndPalettes() {
        instrumentation.runOnMainSync {
            val settings = SettingsStore(context)
            val savedColors = listOf("themeTokens_light", "themeTokens_dark").associateWith {
                prefs.getString(it, null)
            }
            try {
                for (dark in listOf(false, true)) for (custom in listOf(false, true)) {
                    settings.theme = if (dark) "dark" else "light"
                    settings.resetThemeTokens(dark)
                    if (custom) {
                        settings.setThemeToken(dark, "specialKeyBackground", "#314159")
                        settings.setThemeToken(dark, "specialKeyText", "#F2DABC")
                        settings.setThemeToken(dark, "normalKeyBackground", "#123456")
                        settings.setThemeToken(dark, "text", "#AABBCC")
                        settings.setThemeToken(dark, "hint", "#778899")
                    }
                    val colors = settings.themeTokens(dark)
                    for (chinese in listOf(true, false)) {
                        setSymbols(false)
                        val row = bottomRow(chinese, 260)
                        val punctuation = row.getChildAt(1) as KeyContainer
                        val globe = row.getChildAt(3) as KeyContainer
                        fun face(view: View): Int =
                            ((view.background as InsetDrawable).drawable as
                                android.graphics.drawable.GradientDrawable).color!!.defaultColor
                        assertEquals(Color.parseColor(colors.specialKeyBackground), face(globe))
                        assertEquals("Punctuation shares globe background role", face(globe), face(punctuation))
                        val icon = globe.getChildAt(0) as ToolIcon
                        assertEquals(Color.parseColor(colors.specialKeyText), icon.tint)
                        assertEquals(2, punctuation.childCount)
                        val main = punctuation.getChildAt(0) as PunctuationKeyLabel
                        val alternate = punctuation.getChildAt(1) as PunctuationKeyLabel
                        assertEquals(icon.tint, main.currentTextColor)
                        assertEquals(icon.tint, alternate.currentTextColor)
                        assertEquals(main.textSize, alternate.textSize, .01f)
                        assertEquals(2f / 3f, main.inkCenterYFraction, .0001f)
                        assertEquals(1f / 3f, alternate.inkCenterYFraction, .0001f)
                        assertEquals(if (chinese) "，" else ".", main.text.toString())
                        assertEquals(if (chinese) "。" else ",", alternate.text.toString())
                        assertEquals(globe.width, punctuation.width)
                        assertEquals(globe.height, punctuation.height)
                    }
                }
            } finally {
                val edit = prefs.edit()
                savedColors.forEach { (key, value) ->
                    if (value == null) edit.remove(key) else edit.putString(key, value)
                }
                edit.commit()
                settings.theme = originalTheme
            }
        }
    }

    fun testChineseMainPunctuationInkIsCenteredAtNormalAndMinimumHeights() {
        assertPunctuationInk(chinese = true)
    }

    fun testEnglishMainPunctuationInkIsCenteredAtNormalAndMinimumHeights() {
        assertPunctuationInk(chinese = false)
    }

    fun testOnlyThreeMarkedControlLabelsUseFifteenSpAndSameBoldFont() {
        instrumentation.runOnMainSync {
            for (chinese in listOf(true, false)) for (height in listOf(260, 200)) {
                for (theme in listOf("light", "dark")) {
                    SettingsStore(context).theme = theme
                    setSymbols(false)
                    val row = bottomRow(chinese, height)
                    val root = row.rootView
                    val mode = row.getChildAt(0) as KeyContainer
                    val space = row.getChildAt(2) as KeyContainer
                    val modeLabel = mode.getChildAt(0) as TextView
                    val spaceLabel = space.getChildAt(0) as TextView
                    val density = service.resources.displayMetrics.scaledDensity
                    val expectedBold = Typeface.create(spaceLabel.typeface, Typeface.BOLD)
                    fun assertEmphasized(label: TextView) {
                        assertEquals(15f * density, label.textSize, 1f)
                        assertEquals(15f * density, label.autoSizeMaxTextSize.toFloat(), 1f)
                        assertEquals(Typeface.BOLD, label.typeface.style)
                        assertEquals(expectedBold, label.typeface)
                    }
                    assertEmphasized(modeLabel)
                    assertEquals("?123", modeLabel.text.toString())
                    assertEquals("Space keeps 14sp regular", 14f * density, spaceLabel.textSize, 1f)
                    assertEquals(Typeface.NORMAL, spaceLabel.typeface.style)
                    val letter = requireNotNull(find(root, "q")) as KeyContainer
                    val letterLabel = letter.getChildAt(0) as TextView
                    assertEquals(23f * density, letterLabel.autoSizeMaxTextSize.toFloat(), 1f)
                    assertEquals(Typeface.NORMAL, letterLabel.typeface.style)
                    val modeWidth = mode.width
                    assertTrue(mode.performClick())
                    measureKeyboard(root)
                    val abc = requireNotNull(find(root, "ABC")) as KeyContainer
                    val page = requireNotNull(find(root, "=\\<")) as KeyContainer
                    assertEmphasized(abc.getChildAt(0) as TextView)
                    assertEmphasized(page.getChildAt(0) as TextView)
                    assertEquals("ABC uses the same bottom-row slot", modeWidth, abc.width)
                    val digit = requireNotNull(find(root, "1")) as KeyContainer
                    val digitLabel = digit.getChildAt(0) as TextView
                    assertEquals(23f * density, digitLabel.autoSizeMaxTextSize.toFloat(), 1f)
                    assertEquals(Typeface.NORMAL, digitLabel.typeface.style)
                    assertTrue(abc.performClick())
                }
            }
        }
    }

    fun testPairedBottomWidthsRemainProportionalAcrossProfileShapesAndFloating() {
        instrumentation.runOnMainSync {
            val profiles = listOf(true to false, true to true, false to false, false to true)
                .map { (outer, landscape) -> SettingsStore.defaultLayoutProfile(outer, landscape) }
            for (profile in profiles) for (floating in listOf(false, true)) {
                for (chinese in listOf(true, false)) {
                    setSymbols(false)
                    val row = bottomRow(chinese, profile.heightDp, profileOverride = profile.copy(
                        floating = floating, floatingHeightDp = profile.heightDp,
                    ))
                    val root = row.rootView
                    val keys = (0 until row.childCount).map { row.getChildAt(it) }
                    val weights = listOf(17f, 9f, 42f, 9f, 17f)
                    val sampleWidths = listOf(719, 720, 721, 1079, 1080, 1081, 1801,
                        service.resources.displayMetrics.widthPixels)
                    for (sampleWidth in sampleWidths) {
                        measureKeyboard(root, sampleWidth)
                        val description = "root=$sampleWidth row=${row.width} profile=$profile floating=$floating chinese=$chinese"
                        keys.zip(weights).forEach { (key, weight) ->
                            assertTrue("Each saved profile uses the same 94-unit row proportions: $description",
                                abs(key.width - row.width * weight / 94f) <= 2f)
                            assertEquals(row.height, key.height)
                        }
                        assertEquals("?123 exactly matches the 17/94 Enter width: $description",
                            keys[0].width, keys[4].width)
                        assertEquals("Globe exactly matches the 9/94 punctuation width: $description",
                            keys[1].width, keys[3].width)
                        assertEquals("Space center is exact, including odd pixel widths: $description",
                            row.width, keys[2].left + keys[2].right)
                        assertEquals("Full-width coverage starts at the left edge", 0, keys.first().left)
                        assertEquals("Full-width coverage ends at the right edge", row.width, keys.last().right)
                        keys.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
                        val originalBounds = keys.map { Rect(it.left, it.top, it.right, it.bottom) }
                        row.layout(row.left, row.top, row.right, row.bottom)
                        assertEquals("Repeated layout does not accumulate rounding", originalBounds,
                            keys.map { Rect(it.left, it.top, it.right, it.bottom) })
                    }
                    assertTrue(keys[0].performClick())
                    measureKeyboard(root)
                    val abc = requireNotNull(find(root, "ABC"))
                    assertEquals("ABC uses the same mode-key width", keys[0].width, abc.width)
                }
            }
        }
    }

    fun testAttachedPunctuationSurvivesRealPreDrawScrollingInBothLanguagesAndThemes() {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        attachedActivity = activity
        var observedScrolledLabel = false
        for (chinese in listOf(true, false)) for (theme in listOf("light", "dark")) {
            for (height in listOf(260, 200)) {
                lateinit var keyboard: View
                lateinit var key: KeyContainer
                lateinit var main: TextView
                lateinit var hint: TextView
                val firstDraw = CountDownLatch(1)
                val name = "${if (chinese) "chinese" else "english"}-$theme-${height}dp"
                instrumentation.runOnMainSync {
                    SettingsStore(context).theme = theme
                    val row = bottomRow(chinese, height)
                    keyboard = row.rootView
                    key = row.getChildAt(1) as KeyContainer
                    main = key.getChildAt(0) as TextView
                    hint = key.getChildAt(1) as TextView
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(keyboard, FrameLayout.LayoutParams(-1, keyboard.layoutParams.height, Gravity.BOTTOM))
                    })
                    afterNextDraw(keyboard, firstDraw)
                }
                assertTrue("The production keyboard must really draw while attached: $name", firstDraw.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                lateinit var keyBounds: Rect
                lateinit var keyboardBounds: Rect
                var labelScroll = 0
                var nativeHintColor = 0
                instrumentation.runOnMainSync {
                    check(main.isAttachedToWindow && main.isShown)
                    keyBounds = screenBounds(key)
                    keyboardBounds = screenBounds(keyboard)
                    labelScroll = main.scrollX
                    nativeHintColor = hint.currentTextColor
                    assertEquals("Both punctuation symbols use the same effective font size: $name",
                        main.textSize, hint.textSize, .01f)
                }
                observedScrolledLabel = observedScrolledLabel || labelScroll != 0
                android.util.Log.i("KeyboardBottomRowTest", "$name attached main scrollX=$labelScroll key=$keyBounds")
                val productionScreen = awaitRenderedHintColor(keyBounds, nativeHintColor, "production-$name")
                try {
                    saveBitmap(productionScreen, "punctuation-attached-full-$name.png")
                    saveScreenshotCrop(productionScreen, keyboardBounds, "punctuation-attached-$name.png")
                } finally {
                    productionScreen.recycle()
                }

                // Inspect actual compositor pixels only after native TextView pre-draw and
                // scroll handling. Standalone key.draw() before attachment missed this bug.
                val coloredDraw = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    main.setTextColor(Color.BLUE)
                    hint.setTextColor(Color.RED)
                    afterNextDraw(keyboard, coloredDraw)
                }
                assertTrue("Diagnostic colors must reach a rendered frame: $name", coloredDraw.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                // A UI-thread draw callback is not a compositor fence. Wait for the upper
                // alternate's red marker in actual screen pixels. A missing blue main glyph
                // must still fail after that known-new frame has reached the screen.
                val screenshot = awaitRenderedHintColor(keyBounds, Color.RED, "diagnostic-$name")
                val pixels = Bitmap.createBitmap(screenshot, keyBounds.left, keyBounds.top, keyBounds.width(), keyBounds.height())
                try {
                    saveBitmap(pixels, "punctuation-attached-ink-$name.png")
                    val ink = coloredInk(pixels, blue = true)
                    val alternate = coloredInk(pixels, blue = false)
                    assertFalse("Attached main punctuation must be visible after pre-draw: $name scrollX=$labelScroll", ink.isEmpty)
                    assertFalse("Attached alternate must remain visible: $name", alternate.isEmpty)
                    assertStackedPunctuationInk(key, ink, alternate, name)
                } finally {
                    pixels.recycle()
                    screenshot.recycle()
                }
            }
        }
        assertTrue("Regression must exercise real nonzero single-line TextView scrolling", observedScrolledLabel)
    }

    private fun afterNextDraw(view: View, drawn: CountDownLatch) {
        val observer = view.viewTreeObserver
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            view.post {
                if (observer.isAlive) observer.removeOnDrawListener(listener)
                drawn.countDown()
            }
        }
        observer.addOnDrawListener(listener)
        view.invalidate()
    }

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun saveScreenshotCrop(screen: Bitmap, bounds: Rect, name: String) {
        val pixels = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
        try { saveBitmap(pixels, name) } finally { pixels.recycle() }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun awaitRenderedHintColor(key: Rect, color: Int, description: String): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var stale: Bitmap? = null
        while (SystemClock.uptimeMillis() < deadline) {
            stale?.recycle()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            stale = screenshot
            // Inspect only the upper alternate's half, excluding the lower main glyph.
            for (y in key.top until key.centerY()) for (x in key.left until key.right) {
                val pixel = screenshot.getPixel(x, y)
                if (abs(Color.red(pixel) - Color.red(color)) <= 12 &&
                    abs(Color.green(pixel) - Color.green(color)) <= 12 &&
                    abs(Color.blue(pixel) - Color.blue(color)) <= 12) {
                    return screenshot
                }
            }
            instrumentation.waitForIdleSync()
        }
        stale?.let {
            saveBitmap(it, "punctuation-frame-timeout-$description.png")
            it.recycle()
        }
        fail("Compositor did not present the upper alternate's expected color: $description")
        error("Unreachable")
    }

    private fun assertPunctuationInk(chinese: Boolean) {
        instrumentation.runOnMainSync {
            for (height in listOf(260, 200)) {
                val row = bottomRow(chinese, height)
                val key = row.getChildAt(1) as KeyContainer
                assertEquals("Only the two stacked punctuation symbols are drawn", 2, key.childCount)
                val main = key.getChildAt(0) as TextView
                val hint = key.getChildAt(1) as TextView
                assertTrue(
                    "Production main glyph uses ink-centered rendering",
                    main is PunctuationKeyLabel,
                )
                assertTrue("The alternate also uses ink-centered rendering", hint is PunctuationKeyLabel)
                assertEquals("Both punctuation symbols use the same effective font size",
                    main.textSize, hint.textSize, .01f)
                assertEquals(if (chinese) "，" else ".", main.text.toString())
                assertEquals(if (chinese) "。" else ",", hint.text.toString())
                assertEquals(if (chinese) "，。" else ". ,", key.contentDescription.toString())
                assertTrue(
                    "Main glyph remains exposed as TextView text",
                    main.createAccessibilityNodeInfo().let {
                        try {
                            it.text?.toString() == main.text.toString()
                        } finally {
                            it.recycle()
                        }
                    },
                )

                // Render the complete production key, including both labels and its inset
                // background. Distinct test-only colors identify the two actual painted glyphs.
                main.setTextColor(Color.BLUE)
                hint.setTextColor(Color.RED)
                val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                try {
                    key.draw(Canvas(bitmap))
                    val ink = coloredInk(bitmap, blue = true)
                    val alternate = coloredInk(bitmap, blue = false)
                    assertFalse(
                        "Main punctuation must paint visible ink at ${height}dp",
                        ink.isEmpty,
                    )
                    assertFalse(
                        "Alternate punctuation must remain visible at ${height}dp",
                        alternate.isEmpty,
                    )
                    assertStackedPunctuationInk(key, ink, alternate, "${height}dp")
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun assertStackedPunctuationInk(key: KeyContainer, main: Rect, alternate: Rect, name: String) {
        val faceHeight = key.height - key.paddingTop - key.paddingBottom
        assertTrue("Both punctuation symbols share the horizontal center: $name main=$main alternate=$alternate",
            abs(main.exactCenterX() - key.width / 2f) <= 1.5f &&
                abs(alternate.exactCenterX() - key.width / 2f) <= 1.5f)
        assertTrue("Main punctuation is centered in the lower stack position: $name $main",
            abs(main.exactCenterY() - (key.paddingTop + faceHeight * 2f / 3f)) <= 1.5f)
        assertTrue("Alternate punctuation is centered in the upper stack position: $name $alternate",
            abs(alternate.exactCenterY() - (key.paddingTop + faceHeight / 3f)) <= 1.5f)
        assertTrue("Stacked punctuation must not overlap: $name", alternate.bottom < main.top)
        for (ink in listOf(main, alternate)) {
            assertTrue("Both symbols stay fully inside the unchanged inset key face: $name $ink",
                ink.left >= key.paddingLeft && ink.right <= key.width - key.paddingRight &&
                    ink.top >= key.paddingTop && ink.bottom <= key.height - key.paddingBottom)
        }
    }

    fun testBottomRowWeightsStayBalancedAndHitTargetsStayContinuousAtMinimumHeight() {
        instrumentation.runOnMainSync {
            for (chinese in listOf(true, false)) for (height in listOf(260, 200)) {
                val row = bottomRow(chinese, height)
                val keys = (0 until row.childCount).map { row.getChildAt(it) }
                assertEquals(5, keys.size)
                val expectedSpace = if (chinese) "五笔" else "English"
                assertEquals(expectedSpace, keys[2].contentDescription.toString())
                assertEquals(expectedSpace, ((keys[2] as KeyContainer).getChildAt(0) as TextView).text.toString())
                val expectedWeights = listOf(17f, 9f, 42f, 9f, 17f)
                keys.forEachIndexed { index, key ->
                    val lp = key.layoutParams as LinearLayout.LayoutParams
                    assertEquals(
                        "Bottom-key width weight $index",
                        expectedWeights[index],
                        lp.weight,
                        .0001f,
                    )
                    assertEquals("No horizontal dead margins", 0, lp.leftMargin + lp.rightMargin)
                    assertEquals("No vertical dead margins", 0, lp.topMargin + lp.bottomMargin)
                    assertTrue(
                        "Visual gaps remain inside the hit target",
                        key.background is InsetDrawable,
                    )
                    assertEquals("Full row-height touch target", row.height, key.height)
                    assertTrue(
                        "Low-height key has a nonempty visible face",
                        key.height > key.paddingTop + key.paddingBottom,
                    )
                }
                assertEquals(
                    "Left side has total weight 26",
                    26f,
                    expectedWeights[0] + expectedWeights[1],
                    .0001f,
                )
                assertEquals(
                    "Right side has total weight 26",
                    26f,
                    expectedWeights[3] + expectedWeights[4],
                    .0001f,
                )
                assertEquals(0, keys.first().left)
                assertEquals(row.width, keys.last().right)
                keys.zipWithNext().forEach { (left, right) ->
                    assertEquals(
                        "Adjacent hit cells have no untouchable gap",
                        left.right,
                        right.left,
                    )
                }
                assertTrue(
                    "Space remains centered after integer pixel rounding",
                    abs((keys[2].left + keys[2].right) / 2f - row.width / 2f) <= 1f,
                )
                assertTrue(
                    "Punctuation is narrower than the outer mode key",
                    keys[1].width < keys[0].width,
                )
                assertTrue(
                    "Globe is narrower than the outer Enter key",
                    keys[3].width < keys[4].width,
                )
                assertEquals("Mode and Enter have exactly equal pixel widths",
                    keys[0].width, keys[4].width)
                assertEquals("Punctuation and globe have exactly equal pixel widths",
                    keys[1].width, keys[3].width)
            }
        }
    }

    fun testFloatingResizeArtworkIsSmallerInsideUnchangedHitTarget() {
        instrumentation.runOnMainSync {
            val row = bottomRow(chinese = true, heightDp = 260, floating = true)
            val resize = requireNotNull(find(row.rootView, "调整悬浮键盘大小")) as ToolIcon
            val density = service.resources.displayMetrics.density
            assertTrue(
                "Resize hit target remains 48dp wide",
                abs(resize.width - 48 * density) <= 1f,
            )
            assertTrue(
                "Resize hit target remains 32dp high",
                abs(resize.height - 32 * density) <= 1f,
            )
            resize.tint = Color.BLUE
            val original =
                ToolIcon(context, "resize", Color.BLUE).apply {
                    layout(0, 0, resize.width, resize.height)
                }
            val actualPixels =
                Bitmap.createBitmap(resize.width, resize.height, Bitmap.Config.ARGB_8888)
            val originalPixels =
                Bitmap.createBitmap(resize.width, resize.height, Bitmap.Config.ARGB_8888)
            try {
                resize.draw(Canvas(actualPixels))
                original.draw(Canvas(originalPixels))
                val actual = coloredInk(actualPixels, blue = true)
                val old = coloredInk(originalPixels, blue = true)
                assertFalse("Resize arrow is still visible", actual.isEmpty)
                assertTrue(
                    "Resize artwork is at most 18dp wide",
                    actual.width() <= 18 * density + 2,
                )
                assertTrue(
                    "Resize artwork is at most 18dp high",
                    actual.height() <= 18 * density + 2,
                )
                assertTrue(
                    "Resize arrow is substantially smaller than the default toolbar-sized art",
                    actual.width() <= old.width() * .8f && actual.height() <= old.height() * .8f,
                )
                assertTrue(
                    "Resize arrow remains horizontally centered",
                    abs(actual.exactCenterX() - resize.width / 2f) <= 1f,
                )
                assertTrue(
                    "Resize arrow remains vertically centered",
                    abs(actual.exactCenterY() - resize.height / 2f) <= 1f,
                )
            } finally {
                actualPixels.recycle()
                originalPixels.recycle()
            }
        }
    }

    private fun bottomRow(
        chinese: Boolean,
        heightDp: Int,
        floating: Boolean = false,
        profileOverride: SettingsStore.LayoutProfile? = null,
    ): LinearLayout {
        val config = service.resources.configuration
        SettingsStore(context)
            .saveProfile(
                config.smallestScreenWidthDp < 600,
                config.orientation == Configuration.ORIENTATION_LANDSCAPE,
                profileOverride ?: SettingsStore.LayoutProfile(heightDp = heightDp, floating = floating),
            )
        OfflineKeyboardService::class
            .java
            .getDeclaredField("chinese")
            .apply { isAccessible = true }
            .setBoolean(service, chinese)
        val root = service.onCreateInputView()
        measureKeyboard(root)
        val space = requireNotNull(find(root, if (chinese) "五笔" else "English"))
        return space.parent as LinearLayout
    }

    private fun setSymbols(symbols: Boolean) {
        OfflineKeyboardService::class.java.getDeclaredField("symbols")
            .apply { isAccessible = true }.setBoolean(service, symbols)
    }

    private fun measureKeyboard(root: View, width: Int = service.resources.displayMetrics.widthPixels) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(
                width,
                View.MeasureSpec.EXACTLY,
            ),
            View.MeasureSpec.makeMeasureSpec(root.layoutParams.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun find(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) {
                find(view.getChildAt(index), description)?.let {
                    return it
                }
            }
        return null
    }

    private fun coloredInk(bitmap: Bitmap, blue: Boolean): Rect {
        val bounds = Rect()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            val main = if (blue) Color.blue(color) else Color.red(color)
            val other = if (blue) Color.red(color) else Color.blue(color)
            if (main - other > 100 && main - Color.green(color) > 100)
                bounds.union(x, y, x + 1, y + 1)
        }
        return bounds
    }
}
