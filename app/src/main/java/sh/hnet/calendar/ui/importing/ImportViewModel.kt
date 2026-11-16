package sh.hnet.calendar.ui.importing

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.EventDefaults
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.IcsEvent
import sh.hnet.calendar.model.IcsImport
import sh.hnet.calendar.model.IcsParser
import sh.hnet.calendar.model.IcsRead

/** Why a file can't be imported (SPEC §13.8). */
enum class ImportProblem { UNREADABLE, NO_EVENTS, NO_CALENDAR }

sealed interface ImportState {
    data object Loading : ImportState
    data class Failed(val problem: ImportProblem, val fileName: String) : ImportState

    /**
     * @param events The file's events, ready for the calendar
     * @param calendars The calendars that can be written to
     * @param fromFile Whether the events get the file's reminders in place of the user's usual ones
     * @param selected The events to add, by their place in [events]
     */
    data class Ready(
        val fileName: String,
        val events: List<IcsEvent>,
        val calendars: List<CalendarInfo>,
        val calendarId: Long,
        val defaults: EventDefaults,
        val fromFile: Boolean = false,
        val selected: Set<Int> = events.indices.toSet(),
        val adding: Boolean = false,
    ) : ImportState {
        /** "From the file" is offered only when the file has reminders at all (SPEC §13.3). */
        val fileHasReminders: Boolean get() = events.any { it.reminders.isNotEmpty() }
        fun reminders(event: IcsEvent): List<Int> = if (fromFile) event.reminders else defaults.reminders(event.allDay)
    }
}

/** What an import wrote: the ids of the new events and the first of them, to open afterwards. */
class Imported(val ids: List<Long>, val first: IcsEvent?)

/** The import's data (SPEC §13): the picked file read once, the choice of calendar, reminders and events, and adding them. */
class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val _state = MutableStateFlow<ImportState>(ImportState.Loading)
    val state: StateFlow<ImportState> = _state
    private var opened: Uri? = null
    private var read: IcsRead? = null
    private var fileName = ""

    /** Reads [uri]; the same one again only reads the calendars anew (one may have been created meanwhile). */
    fun open(uri: Uri) {
        viewModelScope.launch {
            if (uri != opened) {
                opened = uri
                _state.value = ImportState.Loading
                fileName = withContext(Dispatchers.IO) { displayName(uri) } ?: uri.lastPathSegment.orEmpty()
                read = withContext(Dispatchers.IO) { read(uri) }
            }
            show()
        }
    }

    /** Called when the page comes to the front again: a calendar created meanwhile lifts "No calendar to add to". */
    fun refresh() {
        if (opened != null && _state.value is ImportState.Failed) viewModelScope.launch { show() }
    }

    private suspend fun show() {
        val events = (read as? IcsRead.Events)?.events?.let(IcsImport::prepare).orEmpty()
        if (events.isEmpty()) {
            _state.value = ImportState.Failed(if (read == IcsRead.NoEvents) ImportProblem.NO_EVENTS else ImportProblem.UNREADABLE, fileName)
            return
        }
        val calendars = try {
            if (!app.calendars.hasAccess()) emptyList() else CalendarChoices.apply(app.calendars.calendars(), app.calendarChoices.choices.first()).filter { it.writable }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (calendars.isEmpty()) {
            _state.value = ImportState.Failed(ImportProblem.NO_CALENDAR, fileName)
            return
        }
        // The calendar new events go to: the one chosen in Settings › Events, else the last used, else the first shown
        val defaults = app.preferences.eventDefaults.first()
        val usual = defaults.calendar ?: app.preferences.lastCalendar.first()
        val calendar = calendars.firstOrNull { it.key == usual } ?: calendars.firstOrNull { it.shown } ?: calendars.first()
        _state.value = ImportState.Ready(fileName, events, calendars, calendar.id, defaults)
    }

    private fun read(uri: Uri): IcsRead = try {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes == null || bytes.size > MAX_BYTES) IcsRead.Unreadable else IcsParser.read(bytes.toString(Charsets.UTF_8).removePrefix(BOM), ZoneId.systemDefault())
    } catch (_: Exception) {
        IcsRead.Unreadable
    }

    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    fun change(transform: (ImportState.Ready) -> ImportState.Ready) {
        val ready = _state.value as? ImportState.Ready ?: return
        if (!ready.adding) _state.value = transform(ready)
    }

    /** Adds the selected events; null when the provider took none. */
    suspend fun add(): Imported? {
        val ready = _state.value as? ImportState.Ready ?: return null
        if (ready.adding) return null
        _state.value = ready.copy(adding = true)
        val chosen = ready.events.filterIndexed { index, _ -> index in ready.selected }
        val ids = try {
            app.writer.import(ready.calendarId, chosen, ZoneId.systemDefault(), ready::reminders)
        } catch (_: Exception) {
            emptyList()
        }
        _state.value = ready.copy(adding = false)
        if (ids.isEmpty()) return null
        ready.calendars.firstOrNull { it.id == ready.calendarId }?.let { app.preferences.setLastCalendar(it.key) }
        return Imported(ids, chosen.firstOrNull())
    }

    private companion object {
        /** The mark some programs put at a file's very start. */
        val BOM = Char(0xFEFF).toString()

        /** Far above any real calendar file: ten thousand events are a few megabytes. */
        const val MAX_BYTES = 16 * 1_048_576
    }
}
