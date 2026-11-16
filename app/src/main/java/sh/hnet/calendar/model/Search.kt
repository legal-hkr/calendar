package sh.hnet.calendar.model

import java.text.Normalizer

/**
 * Finding events by what the user types (SPEC §11.2): every word of the query has to be somewhere
 * in the title, the place or the description. Case and accents make no difference, so "lodz"
 * finds "Łódź" – which the provider's own search, comparing bytes, would not.
 */
object Search {
    private val MARKS = Regex("\\p{Mn}+")

    /** [text] in lower case without its accents; "ł", which is a letter of its own and not an "l" with a mark, becomes "l". */
    fun normalize(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").replace('ł', 'l')

    /** The words of a query, ready to be looked for; empty for a blank one. */
    fun words(query: String): List<String> = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(words: List<String>, vararg texts: String?): Boolean {
        if (words.isEmpty()) return false
        val all = normalize(texts.filterNotNull().joinToString("\n"))
        return words.all { it in all }
    }
}
