package sh.hnet.calendar.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarColorTest {
    private fun colors(path: String): Map<String, Int> =
        Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""").findAll(File(path).readText()).associate { it.groupValues[1] to it.groupValues[2].toInt(16) }

    @Test
    fun referencesAreTheDarkFills() {
        val night = colors("src/main/res/values-night/calendar_colors.xml")
        for (color in CalendarColor.entries) assertEquals(color.key, night["calendar_${color.key}_fill"], color.reference)
    }

    @Test
    fun bareDotsAreTheDarkFillsInBothThemes() {
        val night = colors("src/main/res/values-night/calendar_colors.xml")
        val bare = colors("src/main/res/values/calendar_colors_bare.xml")
        assertEquals(CalendarColor.entries.size, bare.size)
        for (color in CalendarColor.entries) assertEquals(color.key, night["calendar_${color.key}_fill"], bare["calendar_${color.key}_bare"])
    }

    @Test
    fun bothThemesDefineEveryColor() {
        val light = colors("src/main/res/values/calendar_colors.xml")
        val night = colors("src/main/res/values-night/calendar_colors.xml")
        assertEquals(light.keys, night.keys)
        for (color in CalendarColor.entries) for (part in listOf("fill", "on", "text", "outline")) assertTrue("${color.key} $part", "calendar_${color.key}_$part" in light)
        assertEquals(CalendarColor.entries.size * 4, light.size)
    }

    @Test
    fun lightFillsAreDarkenedByFifteenPercent() {
        val light = colors("src/main/res/values/calendar_colors.xml")
        for (color in CalendarColor.entries) {
            val on = light.getValue("calendar_${color.key}_on")
            val expected = listOf(16, 8, 0).fold(0) { mixed, shift ->
                val fill = color.reference shr shift and 0xFF
                val target = on shr shift and 0xFF
                mixed or (Math.round(fill * 0.85 + target * 0.15).toInt() shl shift)
            }
            assertEquals(color.key, expected, light.getValue("calendar_${color.key}_fill"))
        }
    }

    @Test
    fun paletteColorsAreTheirOwnNearest() {
        for (color in CalendarColor.entries) assertEquals(color, CalendarColor.nearest(color.reference))
    }

    @Test
    fun accountColorsMapByHue() {
        assertEquals(CalendarColor.RED, CalendarColor.nearest(0xFF0000))
        assertEquals(CalendarColor.GREEN, CalendarColor.nearest(0x00FF00))
        assertEquals(CalendarColor.BLUE, CalendarColor.nearest(0x0000FF))
        assertEquals(CalendarColor.AMBER, CalendarColor.nearest(0xF4B400))
        assertEquals(CalendarColor.TEAL, CalendarColor.nearest(0x009688))
        assertEquals(CalendarColor.VIOLET, CalendarColor.nearest(0x7E57C2))
        // Nextcloud's default blue
        assertEquals(CalendarColor.SKY, CalendarColor.nearest(0x0082C9))
        // The provider's colors carry an alpha byte
        assertEquals(CalendarColor.RED, CalendarColor.nearest(0xFFFF0000.toInt()))
    }

    @Test
    fun colorsWithoutHueAreGraphite() {
        assertEquals(CalendarColor.GRAPHITE, CalendarColor.nearest(0x000000))
        assertEquals(CalendarColor.GRAPHITE, CalendarColor.nearest(0xFFFFFF))
        assertEquals(CalendarColor.GRAPHITE, CalendarColor.nearest(0x808080))
        assertEquals(CalendarColor.GRAPHITE, CalendarColor.nearest(0x616161))
    }
}
