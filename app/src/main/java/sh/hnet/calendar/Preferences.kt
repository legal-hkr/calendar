package sh.hnet.calendar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import sh.hnet.calendar.model.CalendarView

private val Context.settings by preferencesDataStore("settings")

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    /** The view the main screen was left on, which it opens with (SPEC §3.8). */
    val view: Flow<CalendarView> = context.settings.data.map { CalendarView.of(it[VIEW]) }.distinctUntilChanged()

    suspend fun setView(view: CalendarView) {
        context.settings.edit { it[VIEW] = view.key }
    }

    /** Month's "Event list" switch (SPEC §3.4): the compact grid with the chosen day's list instead of the full grid. */
    val eventList: Flow<Boolean> = context.settings.data.map { it[EVENT_LIST] ?: false }.distinctUntilChanged()

    suspend fun setEventList(on: Boolean) {
        context.settings.edit { it[EVENT_LIST] = on }
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    private companion object {
        val VIEW = stringPreferencesKey("view")
        val EVENT_LIST = booleanPreferencesKey("event_list")
    }
}
