package io.secure.keyboard.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.test.InstrumentationTestCase
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tests packaged font loading and the final per-codepoint TextPaint, including API 26. */
@Suppress("DEPRECATION")
class CandidateFontTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val rare = "\uD855\uDF5A\uD856\uDF07" // U+2575A 𥝚, U+25B07 𥬇

    fun testPackagedFontDrawsBothTduCharacters() {
        val face = Typeface.createFromAsset(context.assets, "fonts/OfflineKeyboardCjkSupplement.ttf")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 24f }
        assertEquals(0x2575A, rare.codePointAt(0))
        assertEquals(0x25B07, rare.codePointAt(2))
        assertTrue("Bundled font must draw U+2575A", paint.hasGlyph(rare.substring(0, 2)))
        assertTrue("Bundled font must draw U+25B07", paint.hasGlyph(rare.substring(2, 4)))
    }

    fun testFinalCandidatePaintHasBothGlyphsAndPreservesText() {
        instrumentation.runOnMainSync {
            val view = TextView(context).apply { textSize = 24f; text = "笑 $rare" }
            val before = view.text.toString()
            val normalFace = view.typeface
            CandidateFont.apply(view)
            assertEquals(before, view.text.toString())
            assertEquals("Common text must retain its normal typeface", normalFace, view.typeface)
            for (index in listOf(2, 4)) {
                val finalPaint = TextPaint(view.paint)
                (view.text as? Spanned)?.getSpans(index, index + 2, MetricAffectingSpan::class.java)
                    ?.forEach { it.updateDrawState(finalPaint) }
                assertTrue("Final candidate paint must draw ${before.substring(index, index + 2)}",
                    finalPaint.hasGlyph(before.substring(index, index + 2)))
            }
        }
    }

    fun testWorkerPreparationLeavesNoNewFontLoadForMainThreadCandidateBinding() {
        lateinit var view: TextView
        lateinit var snapshot: TextPaint
        instrumentation.runOnMainSync {
            view = TextView(context).apply { textSize = 20f; text = rare }
            snapshot = TextPaint(view.paint)
        }
        fun loadedFaces(): List<Typeface> {
            val parts = CandidateFont::class.java.getDeclaredField("parts")
                .apply { isAccessible = true }.get(CandidateFont) as? List<*> ?: return emptyList()
            return parts.mapNotNull { part ->
                requireNotNull(part)::class.java.getDeclaredField("face")
                    .apply { isAccessible = true }.get(part) as? Typeface
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val prepared = executor.submit<List<Typeface>> {
                assertNotSame("Preparation must run away from the typing thread", Looper.getMainLooper(), Looper.myLooper())
                val before = loadedFaces()
                CandidateFont.prepare(context, listOf("中文 ABC"), snapshot)
                assertEquals("Ordinary results must not eagerly load bundled font parts", before, loadedFaces())
                CandidateFont.prepare(context, listOf(rare), snapshot)
                loadedFaces()
            }.get(20, TimeUnit.SECONDS)
            if (!snapshot.hasGlyph(rare.substring(0, 2)) || !snapshot.hasGlyph(rare.substring(2, 4)))
                assertTrue("Missing covered glyphs must be prepared before UI delivery", prepared.isNotEmpty())
            instrumentation.runOnMainSync {
                CandidateFont.apply(view)
                assertEquals("UI styling must not construct any additional font part", prepared, loadedFaces())
                assertEquals(rare, view.text.toString())
                for (index in listOf(0, 2)) {
                    val finalPaint = TextPaint(view.paint)
                    (view.text as? Spanned)?.getSpans(index, index + 2, MetricAffectingSpan::class.java)
                        ?.forEach { it.updateDrawState(finalPaint) }
                    assertTrue(finalPaint.hasGlyph(rare.substring(index, index + 2)))
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun testSupplementarySpansNeverSplitSurrogatesOrAccumulate() {
        val text = "A${rare}中\uD83D\uDE00"
        val paint = TextPaint().apply { typeface = Typeface.DEFAULT; textSize = 24f }
        val once = CandidateFont.styled(context, text, paint)
        val twice = CandidateFont.styled(context, once, paint)
        assertEquals(text, twice.toString())
        assertEquals("Emoji and all codepoints must be retained", 5, text.codePointCount(0, text.length))
        val spans = twice as? Spanned
        val firstSpans = (once as? Spanned)?.getSpans(0, once.length, MetricAffectingSpan::class.java)
        val secondSpans = spans?.getSpans(0, twice.length, MetricAffectingSpan::class.java)
        assertEquals(firstSpans?.size ?: 0, secondSpans?.size ?: 0)
        secondSpans?.forEach { span ->
            val spanned = requireNotNull(spans)
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            assertTrue("Only a full rare codepoint is spanned", start == 1 || start == 3)
            assertEquals(2, end - start)
            assertTrue(Character.isHighSurrogate(text[start]))
            assertTrue(Character.isLowSurrogate(text[end - 1]))
        }
        // Unsupported imported Extension G stays present even outside the bundled subset.
        val imported = "\uD880\uDC00"
        assertEquals(imported, CandidateFont.styled(context, imported, paint).toString())
        assertEquals("中文 ABC", CandidateFont.styled(context, "中文 ABC", paint).toString())
    }
}
