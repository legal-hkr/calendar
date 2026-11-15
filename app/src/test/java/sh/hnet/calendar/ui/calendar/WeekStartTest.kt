package sh.hnet.calendar.ui.calendar

import java.time.DayOfWeek
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekStartTest {
    private val poland = Locale.forLanguageTag("pl-PL")
    private val states = Locale.forLanguageTag("en-US")

    @Test
    fun theRegionsCustomDecides() {
        assertEquals(DayOfWeek.MONDAY, weekStart(poland, poland))
        assertEquals(DayOfWeek.SUNDAY, weekStart(states, states))
    }

    @Test
    fun aLanguageWithoutRegionTakesTheSystems() {
        // English chosen in the app on a Polish phone: weeks still start on Monday
        assertEquals(DayOfWeek.MONDAY, weekStart(Locale.forLanguageTag("en"), poland))
    }

    @Test
    fun theSystemsRegionalPreferenceWins() {
        assertEquals(DayOfWeek.SATURDAY, weekStart(Locale.forLanguageTag("pl-PL-u-fw-sat"), poland))
        assertEquals(DayOfWeek.SUNDAY, weekStart(Locale.forLanguageTag("pl"), Locale.forLanguageTag("pl-PL-u-fw-sun")))
    }
}
