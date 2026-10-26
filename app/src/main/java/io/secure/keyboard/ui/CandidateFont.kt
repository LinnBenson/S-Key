package io.secure.keyboard.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView

/**
 * Offline glyph fallback for dictionary candidates, including supplementary Hanzi.
 *
 * Keep the device font whenever it can draw a character. A missing covered ideograph
 * gets one font span over the complete Unicode codepoint, never a surrogate half.
 * Text, candidate ordering, accessibility text and committed text remain unchanged.
 *
 * The bundled, renamed Hanazono subsets cover all rare CJK characters in the stock
 * dictionaries. Imported characters outside those subsets still use system fallback.
 * This does not install a system font or change the receiving app's rendering.
 */
object CandidateFont {
    private val lock = Any()

    @Volatile private var parts: List<FontPart>? = null

    /**
     * Warm only fallback parts needed by the next result, on the dictionary worker. The paint
     * must be a main-thread snapshot of the candidate label's normal paint, not a live View.
     * This keeps first-use coverage/asset reads and font construction out of the typing loop.
     */
    fun prepare(context: Context, texts: Iterable<String>, normalPaint: Paint) {
        val seen = HashSet<Int>()
        for (text in texts) {
            var index = 0
            while (index < text.length) {
                val codepoint = Character.codePointAt(text, index)
                val end = index + Character.charCount(codepoint)
                if (isRareCjk(codepoint) && seen.add(codepoint) &&
                    !normalPaint.hasGlyph(text.substring(index, end)))
                    fontParts(context).firstOrNull { it.covers(codepoint) }?.load(context)
                index = end
            }
        }
    }

    /** Call after setting the candidate TextView's text, text size and normal typeface. */
    fun apply(view: TextView) {
        val original = view.text
        val styled = styled(view.context, original, view.paint)
        if (styled !== original) view.text = styled
    }

    /** Returns the original text when no font span is needed; preserves other spans. */
    fun styled(context: Context, text: CharSequence, normalPaint: Paint): CharSequence {
        var result: SpannableString? = null
        if (text is Spanned) {
            val previous = text.getSpans(0, text.length, FallbackSpan::class.java)
            if (previous.isNotEmpty()) {
                val target = SpannableString(text)
                previous.forEach { target.removeSpan(it) }
                result = target
            }
        }
        var index = 0
        while (index < text.length) {
            val codepoint = Character.codePointAt(text, index)
            val end = index + Character.charCount(codepoint)
            // Leave basic Hanzi, emoji, punctuation and UI symbols completely alone.
            if (isRareCjk(codepoint)) {
                val character = text.subSequence(index, end).toString()
                if (!normalPaint.hasGlyph(character)) {
                    val part = fontParts(context).firstOrNull { it.covers(codepoint) }
                    val face = part?.load(context)
                    if (face != null) {
                        val target = result ?: SpannableString(text).also { result = it }
                        target.setSpan(FallbackSpan(face), index, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            index = end
        }
        return result ?: text
    }

    private fun isRareCjk(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x9FA6..0x9FFF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x323AF

    private fun fontParts(context: Context): List<FontPart> =
        parts ?: synchronized(lock) {
            parts ?: run {
                val loaded = try {
                    listOf(
                        FontPart("OfflineKeyboardCjkBmp.ttf", readRanges(context, "bmp.ranges")),
                        FontPart("OfflineKeyboardCjkSupplement.ttf", readRanges(context, "supplement.ranges")),
                    )
                } catch (_: Exception) {
                    // A corrupt install must not crash the IME or hide candidate text.
                    // Fail silently: never forward exception payloads to system logs.
                    emptyList()
                }
                parts = loaded
                loaded
            }
        }

    private fun readRanges(context: Context, filename: String): IntArray =
        context.assets.open("fonts/$filename").bufferedReader().use { reader ->
            reader.lineSequence().filter { it.isNotBlank() }.flatMap { line ->
                val bounds = line.split('-')
                require(bounds.size == 2)
                sequenceOf(bounds[0].toInt(16), bounds[1].toInt(16))
            }.toList().toIntArray()
        }

    private class FontPart(private val filename: String, private val ranges: IntArray) {
        @Volatile private var face: Typeface? = null
        @Volatile private var loadFailed = false

        fun covers(codepoint: Int): Boolean {
            var low = 0
            var high = ranges.size / 2 - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                when {
                    codepoint < ranges[middle * 2] -> high = middle - 1
                    codepoint > ranges[middle * 2 + 1] -> low = middle + 1
                    else -> return true
                }
            }
            return false
        }

        fun load(context: Context): Typeface? {
            if (loadFailed) return null
            return face ?: synchronized(this) {
                face ?: if (loadFailed) null else try {
                    Typeface.createFromAsset(context.assets, "fonts/$filename").also { face = it }
                } catch (_: Exception) {
                    loadFailed = true
                    // Fail silently: never forward exception payloads to system logs.
                    null
                }
            }
        }
    }

    /** Custom span keeps this compatible with API 26 (TypefaceSpan(Typeface) is API 28). */
    private class FallbackSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) {
            paint.typeface = face
        }

        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = face
        }
    }
}
