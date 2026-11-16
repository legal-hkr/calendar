package sh.hnet.calendar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun caseAndAccentsMakeNoDifference() {
        assertEquals("lodz zolc gesla", Search.normalize("Łódź ŻÓŁĆ gęślą"))
        assertTrue(Search.matches(Search.words("lodz"), "Wyjazd do Łodzi", null, "Spotkanie w Łódź Fabryczna"))
        assertTrue(Search.matches(Search.words("ŁÓDŹ"), "łódź"))
        assertTrue(Search.matches(Search.words("cafe"), "Café Bristol"))
    }

    @Test
    fun everyWordHasToBeFoundSomewhere() {
        val words = Search.words("  basen  polonez ")
        assertEquals(listOf("basen", "polonez"), words)
        // One in the title, the other in the place
        assertTrue(Search.matches(words, "Basen", "Pływalnia Polonez", ""))
        assertFalse(Search.matches(words, "Basen", "Pływalnia Wodnik", ""))
        // In the description alone
        assertTrue(Search.matches(Search.words("identyfikatory"), "Warsztaty", "Sala A", "Proszę zabrać identyfikatory."))
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertEquals(emptyList<String>(), Search.words("   "))
        assertFalse(Search.matches(emptyList(), "Basen"))
    }
}
