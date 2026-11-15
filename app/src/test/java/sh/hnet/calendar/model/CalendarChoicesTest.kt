package sh.hnet.calendar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarChoicesTest {
    private fun calendar(id: Long, name: String, color: CalendarColor = CalendarColor.TEAL, shown: Boolean = true) =
        CalendarInfo(id, CalendarInfo.key("type", "account", name), name, "account", "type", color, writable = true, shown = shown)

    @Test
    fun withoutChoicesTheProvidersValuesStand() {
        val calendars = listOf(calendar(1, "Work"), calendar(2, "Hidden", shown = false))
        assertEquals(calendars, CalendarChoices.apply(calendars, emptyMap()))
    }

    @Test
    fun choicesAreLaidOverEachOnItsOwn() {
        val work = calendar(1, "Work")
        val home = calendar(2, "Home", shown = false)
        val applied = CalendarChoices.apply(listOf(work, home), mapOf(work.key to CalendarChoice(color = "red"), home.key to CalendarChoice(shown = true)))
        assertEquals(CalendarColor.RED, applied[0].color)
        assertTrue(applied[0].shown)
        assertEquals(CalendarColor.TEAL, applied[1].color)
        assertTrue(applied[1].shown)
    }

    @Test
    fun choiceFollowsTheKeyNotTheRowId() {
        val before = calendar(1, "Work")
        val after = calendar(57, "Work")
        assertFalse(CalendarChoices.apply(listOf(after), mapOf(before.key to CalendarChoice(shown = false))).single().shown)
    }

    @Test
    fun unknownColorIsIgnored() {
        val work = calendar(1, "Work")
        assertEquals(CalendarColor.TEAL, CalendarChoices.apply(listOf(work), mapOf(work.key to CalendarChoice(color = "chartreuse"))).single().color)
    }

    @Test
    fun choicesSurviveStorage() {
        val choices = mapOf("a|b|c" to CalendarChoice("sky", false), "LOCAL|x|Żółty \"kalendarz\"" to CalendarChoice(shown = true))
        assertEquals(choices, CalendarChoices.decode(CalendarChoices.encode(choices)))
    }

    @Test
    fun unreadableStorageIsEmpty() {
        assertTrue(CalendarChoices.decode(null).isEmpty())
        assertTrue(CalendarChoices.decode("").isEmpty())
        assertTrue(CalendarChoices.decode("{broken").isEmpty())
        // A field a later version added
        assertEquals(mapOf("k" to CalendarChoice("red")), CalendarChoices.decode("""{"k":{"color":"red","order":3}}"""))
    }

    @Test
    fun localCalendarsAreToldByTheirAccountType() {
        assertTrue(CalendarInfo(1, "k", "n", "a", "LOCAL", CalendarColor.RED, true).local)
        assertFalse(calendar(1, "Work").local)
    }
}
