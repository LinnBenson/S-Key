package io.secure.keyboard.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.test.InstrumentationTestCase
import android.test.ServiceTestCase
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.secure.keyboard.MainActivity
import io.secure.keyboard.OfflineKeyboardService
import java.io.File
import kotlin.math.roundToInt

/** Native attached TextView pixels, including ink below the baseline; no custom text renderer. */
@Suppress("DEPRECATION")
class PreeditCodeViewTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private val context get() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }

    fun testFullDescenderInkAtNormalAndLargeFontScalesWithoutWidthAutosizing() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            val scaled = context.createConfigurationContext(Configuration(context.resources.configuration)
                .apply { fontScale = scale })
            fun dp(value: Int) = (value * scaled.resources.displayMetrics.density).roundToInt()
            lateinit var view: PreeditCodeView
            lateinit var referenceView: TextView
            lateinit var host: FrameLayout
            instrumentation.runOnMainSync {
                view = PreeditCodeView(scaled).apply {
                    text = "gojy"
                    setTextColor(Color.BLACK)
                    setPadding(dp(12), 0, dp(12), 0)
                }
                host = FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(dp(180), dp(18)))
                }
                activity.setContentView(host)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                // Same native renderer, padding and font, with ample vertical room. Raw drawText
                // is not a valid reference for TextView's horizontal clipping (notably leading j).
                referenceView = TextView(scaled).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    includeFontPadding = true
                    setSingleLine(true)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize)
                    setTextColor(Color.BLACK)
                    setPadding(dp(12), 0, dp(12), 0)
                }
                host.addView(referenceView, FrameLayout.LayoutParams(dp(180), dp(18) * 3)
                    .apply { topMargin = dp(26) })
            }
            for (code in listOf("gojy", "gjypq", "g", "j", "y", "p", "q")) {
                instrumentation.runOnMainSync { view.text = code; referenceView.text = code }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue(view.isAttachedToWindow)
                    assertEquals(dp(18), view.height)
                    assertEquals(1, requireNotNull(view.layout).lineCount)
                    assertEquals(0, view.scrollX)
                    assertEquals(0, view.scrollY)
                    val metrics = view.paint.fontMetricsInt
                    assertTrue("Full font top at $scale: $code", view.baseline + metrics.top >= 0)
                    assertTrue("Full font bottom at $scale: $code",
                        view.baseline + metrics.bottom <= view.height)

                    // Draw through the attached parent so native scrolling/clip transforms apply.
                    val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    host.draw(Canvas(actual))
                    val reference = Bitmap.createBitmap(referenceView.width, referenceView.height,
                        Bitmap.Config.ARGB_8888)
                    host.draw(Canvas(reference).apply { translate(0f, -referenceView.top.toFloat()) })
                    val expectedInk = ink(reference, referenceView.baseline, reference.height)
                    val actualInk = ink(actual, view.baseline, actual.height)
                    if (code in listOf("gojy", "gjypq", "j")) {
                        saveCapture(actual, "preedit-$code-$scale", white = true)
                        saveCapture(reference, "preedit-$code-$scale-native-reference", white = true)
                        if (code == "gjypq") saveCapture(actual, "preedit-gjypq-$scale", white = false)
                    }
                    if (code == "j") {
                        // Preserve evidence of any native horizontal clip independently from the
                        // vertical-fit assertion; never loosen its descender-ink threshold.
                        val raw = Bitmap.createBitmap(reference.width, reference.height,
                            Bitmap.Config.ARGB_8888)
                        Canvas(raw).drawText(code, referenceView.paddingLeft.toFloat(),
                            referenceView.baseline.toFloat(), Paint(referenceView.paint)
                                .apply { color = Color.BLACK })
                        saveCapture(raw, "preedit-j-$scale-raw-reference", white = true)
                        val diagnostics = File(context.filesDir, "validation/preedit-j-$scale-pixels.txt")
                        diagnostics.writeText("actualBaseline=${view.baseline}\n" +
                            "referenceBaseline=${referenceView.baseline}\n" +
                            "textSize=${view.textSize}\nleftClip=${view.compoundPaddingLeft}\n" +
                            "actualDescender=$actualInk\nnativeDescender=$expectedInk\n" +
                            "rawDescender=${ink(raw, referenceView.baseline, raw.height)}\n" +
                            (0 until raw.width).filter { x ->
                                columnInk(raw, x, referenceView.baseline) !=
                                    columnInk(reference, x, referenceView.baseline)
                            }.joinToString("\n") { x ->
                                "x=$x raw=${columnInk(raw, x, referenceView.baseline)} " +
                                    "native=${columnInk(reference, x, referenceView.baseline)}"
                            })
                        raw.recycle()
                    }
                    assertTrue("Reference must contain descender ink: $code", expectedInk > 0)
                    assertTrue("Descender ink must survive native clipping at $scale: $code " +
                        "($actualInk / $expectedInk)", actualInk >= expectedInk * 0.97)
                    assertTrue("All glyph ink must survive at $scale: $code",
                        ink(actual, 0, actual.height) >= ink(reference, 0, reference.height) * 0.97)
                    assertEquals("Top ink must not touch clipping edge", 0L, ink(actual, 0, 1))
                    assertEquals("Bottom ink must not touch clipping edge", 0L,
                        ink(actual, actual.height - 1, actual.height))
                    actual.recycle()
                    reference.recycle()
                }
            }
            var shortSize = 0f
            val longCode = "gjypq".repeat(60)
            instrumentation.runOnMainSync { shortSize = view.textSize; view.text = longCode }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("Long code cannot trigger width-based font shrinking", shortSize, view.textSize)
                assertEquals(dp(18), view.height)
                val node = view.createAccessibilityNodeInfo()
                assertEquals("Full raw code remains accessible", longCode, node.text.toString())
                node.recycle()
            }
        }
    }

    fun testProductionHeaderAndEveryKeyKeepTheirGeometryWhenCodeAppearsAndClears() {
        val harness = ServiceHarness()
        lateinit var service: OfflineKeyboardService
        instrumentation.runOnMainSync {
            service = harness.create(context)
            activity.setContentView(service.onCreateInputView())
        }
        try {
            instrumentation.waitForIdleSync()
            var before = emptyList<List<Int>>()
            instrumentation.runOnMainSync { before = geometry(field(service, "body")) }
            for (code in listOf("gojy", "", "gjypq", "")) {
                instrumentation.runOnMainSync {
                    io.secure.keyboard.SessionTestAccess.field("code")
                        .apply { isAccessible = true }.set(service, code)
                    io.secure.keyboard.SessionTestAccess.method("renderHeader")
                        .apply { isAccessible = true }.invoke(service)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val preedit = field<TextView>(service, "preedit")
                    val header = field<View>(service, "header")
                    fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
                    assertTrue("Production service uses the native fitted view", preedit is PreeditCodeView)
                    assertEquals(dp(18), preedit.layoutParams.height)
                    assertEquals(if (code.isEmpty()) dp(18) + dp(40) else dp(40), header.height)
                    assertEquals(dp(18) + dp(40), header.height +
                        if (preedit.visibility == View.VISIBLE) preedit.height else 0)
                    assertEquals("All key rectangles must stay fixed for '$code'", before,
                        geometry(field(service, "body")))
                }
            }
        } finally {
            instrumentation.runOnMainSync { harness.close() }
        }
    }

    private fun ink(bitmap: Bitmap, fromY: Int, toY: Int): Long {
        var alpha = 0L
        for (y in fromY until toY) for (x in 0 until bitmap.width)
            alpha += Color.alpha(bitmap.getPixel(x, y))
        return alpha
    }

    private fun columnInk(bitmap: Bitmap, x: Int, fromY: Int): Long {
        var alpha = 0L
        for (y in fromY until bitmap.height) alpha += Color.alpha(bitmap.getPixel(x, y))
        return alpha
    }

    private fun saveCapture(bitmap: Bitmap, name: String, white: Boolean) {
        val output = if (white) Bitmap.createBitmap(bitmap.width, bitmap.height,
            Bitmap.Config.ARGB_8888).also { shown ->
            Canvas(shown).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        } else bitmap
        val file = File(context.filesDir, "validation/$name${if (white) "-white" else ""}.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (white) output.recycle()
    }

    private fun geometry(view: View): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        fun visit(child: View) {
            val xy = IntArray(2)
            child.getLocationOnScreen(xy)
            result.add(listOf(xy[0], xy[1], child.width, child.height))
            if (child is ViewGroup) for (index in 0 until child.childCount) visit(child.getChildAt(index))
        }
        visit(view)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(service: OfflineKeyboardService, name: String): T =
        io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }
            .get(service) as T

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
}
