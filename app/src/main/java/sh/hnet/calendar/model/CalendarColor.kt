package sh.hnet.calendar.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The palette of calendar colors (SPEC §4.1). The colors themselves are resources
 * (`calendar_<key>_fill` and the others, in values and values-night); [reference] repeats the
 * dark-theme fill only to find the color nearest to one an account gave its calendar.
 */
enum class CalendarColor(val key: String, val reference: Int) {
    RED("red", 0xFFB4A9),
    ORANGE("orange", 0xFFB787),
    AMBER("amber", 0xF5BF48),
    OLIVE("olive", 0xC8CC6A),
    GREEN("green", 0x96D68F),
    TEAL("teal", 0x6FD6D8),
    SKY("sky", 0x86CEFF),
    BLUE("blue", 0xB0C6FF),
    VIOLET("violet", 0xD3BBFF),
    MAGENTA("magenta", 0xFFADE4),
    RASPBERRY("raspberry", 0xFFB1C2),
    GRAPHITE("graphite", 0xC5C7CF),
    ;

    companion object {
        /**
         * The palette color nearest in hue to [rgb] (SPEC §4.5), which a calendar starts with. A color
         * with hardly any hue of its own (gray, near black or white) is Graphite.
         */
        fun nearest(rgb: Int): CalendarColor {
            val (hue, saturation, value) = hsv(rgb)
            if (saturation < 0.15f || value < 0.15f) return GRAPHITE
            return entries.filter { it != GRAPHITE }.minBy { hueDistance(hue, hsv(it.reference).first) }
        }

        private fun hueDistance(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

        /** Hue in degrees, saturation and value in 0..1. */
        private fun hsv(rgb: Int): Triple<Float, Float, Float> {
            val r = (rgb shr 16 and 0xFF) / 255f
            val g = (rgb shr 8 and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val delta = high - low
            val hue = when {
                delta == 0f -> 0f
                high == r -> 60f * ((g - b) / delta).mod(6f)
                high == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return Triple(hue, if (high == 0f) 0f else delta / high, high)
        }
    }
}
