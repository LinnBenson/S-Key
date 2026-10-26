package io.secure.keyboard.settings

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Dialog-local, platform-independent state. This class cannot read or write preferences. */
internal class ColorPickerState(original: String) {
    val originalHex: String = requireNotNull(normalizeHex(original)) { "Invalid original color" }
    var pendingHex: String = originalHex
        private set
    var rawHex: String = originalHex
        private set
    var hue: Float = 0f
        private set
    var saturation: Float = 0f
        private set
    var value: Float = 0f
        private set

    val originalColor: Int get() = parseOpaque(originalHex)
    val pendingColor: Int get() = parseOpaque(pendingHex)
    val isValid: Boolean get() = normalizeHex(rawHex) != null

    init { readRgb(originalHex) }

    /** Invalid/incomplete typing deliberately leaves the last valid visual color alone. */
    fun typeHex(text: String): Boolean {
        rawHex = text
        val normalized = normalizeHex(text) ?: return false
        pendingHex = normalized
        readRgb(normalized)
        return true
    }

    fun chooseHue(degrees: Float) = chooseHsv(degrees, saturation, value)

    fun chooseSaturationValue(saturation: Float, value: Float) = chooseHsv(hue, saturation, value)

    fun chooseHsv(hue: Float, saturation: Float, value: Float) {
        this.hue = wrapHue(hue)
        this.saturation = unit(saturation)
        this.value = unit(value)
        pendingHex = formatOpaque(hsvToColor(this.hue, this.saturation, this.value))
        rawHex = pendingHex
    }

    /** Confirm is explicit; callers still use SettingsStore.setThemeToken for validation. */
    fun confirmedHex(): String? = normalizeHex(rawHex)

    fun snapshot() = Snapshot(originalHex, pendingHex, rawHex, hue, saturation, value)

    private fun readRgb(hex: String) {
        val color = parseOpaque(hex)
        val red = ((color shr 16) and 255) / 255f
        val green = ((color shr 8) and 255) / 255f
        val blue = (color and 255) / 255f
        val max = maxOf(red, green, blue)
        val delta = max - minOf(red, green, blue)
        value = max
        // A black draft retains chroma and hue so increasing value is predictable.
        if (max > 0f) saturation = delta / max
        if (delta > 0f) {
            hue = wrapHue(60f * when (max) {
                red -> (green - blue) / delta
                green -> (blue - red) / delta + 2f
                else -> (red - green) / delta + 4f
            })
        }
        // Never round-trip the input through HSV here: exact stored/typed RGB is authoritative.
    }

    data class Snapshot(
        val originalHex: String,
        val pendingHex: String,
        val rawHex: String,
        val hue: Float,
        val saturation: Float,
        val value: Float,
    )

    companion object {
        private val opaqueHex = Regex("#[0-9A-Fa-f]{6}")

        fun normalizeHex(text: String): String? = text.trim()
            .takeIf { opaqueHex.matches(it) }?.uppercase(Locale.ROOT)

        fun restore(snapshot: Snapshot): ColorPickerState? {
            val original = normalizeHex(snapshot.originalHex) ?: return null
            val pending = normalizeHex(snapshot.pendingHex) ?: return null
            return ColorPickerState(original).apply {
                typeHex(pending)
                rawHex = snapshot.rawHex
                // Only accept finite primitive coordinates. RGB remains exact, including gray/black.
                if (snapshot.hue.isFinite()) hue = wrapHue(snapshot.hue)
                if (snapshot.saturation.isFinite()) saturation = unit(snapshot.saturation)
                if (snapshot.value.isFinite()) value = unit(snapshot.value)
            }
        }

        fun wrapHue(value: Float): Float =
            if (value.isFinite()) ((value % 360f) + 360f) % 360f else 0f

        fun unit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

        fun parseOpaque(hex: String): Int = 0xFF000000.toInt() or hex.substring(1).toInt(16)

        fun formatOpaque(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

        fun hsvToColor(hue: Float, saturation: Float, value: Float): Int {
            val h = wrapHue(hue) / 60f
            val s = unit(saturation)
            val v = unit(value)
            val sector = floor(h).toInt()
            val fraction = h - sector
            val p = v * (1f - s)
            val q = v * (1f - s * fraction)
            val t = v * (1f - s * (1f - fraction))
            val channels = when (sector) {
                0 -> floatArrayOf(v, t, p)
                1 -> floatArrayOf(q, v, p)
                2 -> floatArrayOf(p, v, t)
                3 -> floatArrayOf(p, q, v)
                4 -> floatArrayOf(t, p, v)
                else -> floatArrayOf(v, p, q)
            }
            return 0xFF000000.toInt() or ((channels[0] * 255).roundToInt() shl 16) or
                ((channels[1] * 255).roundToInt() shl 8) or (channels[2] * 255).roundToInt()
        }
    }
}
