package sh.hnet.calendar.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import sh.hnet.calendar.model.CalendarChoice
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarColor

private val Context.calendarChoices by preferencesDataStore("calendars")

/**
 * Where the user's color and visibility per calendar are kept (SPEC §4.5, §12.1). They are data of
 * the user rather than settings: Reset leaves them, the backup will hold them.
 */
class CalendarChoiceStore(private val context: Context) {
    private val key = stringPreferencesKey("choices")

    val choices: Flow<Map<String, CalendarChoice>> = context.calendarChoices.data.map { CalendarChoices.decode(it[key]) }.distinctUntilChanged()

    suspend fun setColor(calendar: String, color: CalendarColor) = change(calendar) { it.copy(color = color.key) }

    suspend fun setShown(calendar: String, shown: Boolean) = change(calendar) { it.copy(shown = shown) }

    /** A restored backup: the choices become these, and calendars it says nothing of are the provider's again. */
    suspend fun replace(choices: Map<String, CalendarChoice>) {
        context.calendarChoices.edit { it[key] = CalendarChoices.encode(choices) }
    }

    private suspend fun change(calendar: String, change: (CalendarChoice) -> CalendarChoice) {
        context.calendarChoices.edit { stored ->
            val choices = CalendarChoices.decode(stored[key])
            stored[key] = CalendarChoices.encode(choices + (calendar to change(choices[calendar] ?: CalendarChoice())))
        }
    }
}
