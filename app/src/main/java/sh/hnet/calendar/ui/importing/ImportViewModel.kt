package sh.hnet.calendar.ui.importing

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import sh.hnet.calendar.ui.Screen
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.data.StoredEvent
import sh.hnet.calendar.data.EventWriter
import java.util.UUID
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

/** Why a file can't be imported (SPEC §13.6, §13.8). */
enum class ImportProblem { UNREADABLE, NO_EVENTS, NO_CALENDAR, CANCELS_UNKNOWN }

/** What a file's event is to the calendar (SPEC §13.5, §13.6). */
enum class Standing {
    /** Not in any calendar: it is added. */
    NEW,
    /** Saved, and the file says something else: it is updated – or added as a copy. */
    CHANGED,
    /** Saved as the file has it, or saved in a calendar that can't be written to: nothing to do but a copy. */
    SAME,
    /** Saved, and the file cancels it. */
    CANCELED,
}

/** What the one button does for a file with a single event. */
enum class ImportAction { ADD, UPDATE, COPY, MARK_CANCELED, REMOVE }

/**
 * A file's event the calendar already has, known by its id.
 *
 * @param calendar The calendar it is saved in
 * @param changes In what the file's version differs
 */
class Known(val stored: StoredEvent, val calendar: CalendarInfo?, val standing: Standing, val changes: Set<IcsImport.Field>)

sealed interface ImportState {
    data object Loading : ImportState
    data class Failed(val problem: ImportProblem, val fileName: String) : ImportState

    /**
     * @param events The file's events, ready for the calendar
     * @param known Those of them that are saved already, by their place in [events]
     * @param calendars The calendars that can be written to
     * @param fromFile Whether new events get the file's reminders in place of the user's usual ones
     * @param selected The events to act on, by their place in [events]
     * @param action For a file with one event: what the button does
     */
    data class Ready(
        val fileName: String,
        val events: List<IcsEvent>,
        val known: Map<Int, Known>,
        val calendars: List<CalendarInfo>,
        val calendarId: Long,
        val defaults: EventDefaults,
        val fromFile: Boolean = false,
        val selected: Set<Int> = events.indices.filterTo(HashSet()) { known[it]?.standing != Standing.SAME },
        val action: ImportAction = ImportAction.ADD,
        val adding: Boolean = false,
    ) : ImportState {
        /** "From the file" is offered only when the file has reminders at all (SPEC §13.3). */
        val fileHasReminders: Boolean get() = events.any { it.reminders.isNotEmpty() }
        fun reminders(event: IcsEvent): List<Int> = if (fromFile) event.reminders else defaults.reminders(event.allDay)
        fun standing(index: Int): Standing = known[index]?.standing ?: Standing.NEW

        /** In a list of several, an event that is saved as the file has it can't be chosen: there is nothing to do with it. */
        val selectable: Set<Int> get() = events.indices.filterTo(HashSet()) { standing(it) != Standing.SAME }
    }
}

/**
 * What an import did.
 *
 * @param added The ids of the new events, which Undo takes back
 * @param open The event to show afterwards
 * @param removed A saved event the user chose to remove after its cancellation: the caller deletes it with Undo
 */
class Imported(val added: List<Long>, val updated: Int, val canceled: Int, val open: Screen.Event?, val first: IcsEvent?, val removed: StoredEvent? = null)

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
        // Which of the events are saved already, known by their ids (SPEC §13.5)
        val all = try {
            CalendarChoices.apply(app.calendars.calendars(), app.calendarChoices.choices.first()).associateBy { it.id }
        } catch (_: SecurityException) {
            emptyMap()
        }
        val saved = try {
            app.calendars.byUid(events.mapNotNullTo(HashSet()) { it.uid.takeIf(String::isNotEmpty) })
        } catch (_: SecurityException) {
            emptyMap()
        }
        val known = HashMap<Int, Known>()
        val kept = ArrayList<IcsEvent>()
        for (event in events) {
            val stored = saved[event.uid]
            val canceled = event.status == EventStatus.CANCELED
            // A cancellation of something that isn't saved has nothing to cancel
            if (stored == null && canceled) continue
            if (stored != null) {
                val home = all[stored.calendarId]
                val changes = IcsImport.changes(event, stored.event)
                val standing = when {
                    changes.isEmpty() || home?.writable != true -> Standing.SAME
                    canceled && stored.event.status != EventStatus.CANCELED -> Standing.CANCELED
                    else -> Standing.CHANGED
                }
                known[kept.size] = Known(stored, home, standing, changes)
            }
            kept += event
        }
        if (kept.isEmpty()) {
            _state.value = ImportState.Failed(ImportProblem.CANCELS_UNKNOWN, fileName)
            return
        }
        val action = when (known[0]?.standing.takeIf { kept.size == 1 }) {
            Standing.CHANGED -> ImportAction.UPDATE
            Standing.CANCELED -> ImportAction.MARK_CANCELED
            Standing.SAME -> ImportAction.COPY
            else -> ImportAction.ADD
        }
        _state.value = ImportState.Ready(fileName, kept, known, calendars, calendar.id, defaults, action = action)
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

    /**
     * Does what the page shows (SPEC §13): adds the new events among the selected ones, updates
     * those the file changes, marks those it cancels. With one event [ImportState.Ready.action]
     * decides. Null when nothing could be done.
     */
    suspend fun run(): Imported? {
        val ready = _state.value as? ImportState.Ready ?: return null
        if (ready.adding) return null
        _state.value = ready.copy(adding = true)
        val phone = ZoneId.systemDefault()
        val result = try {
            val single = ready.events.singleOrNull()
            val one = ready.known[0].takeIf { single != null }
            when {
                single != null && one != null && ready.action == ImportAction.UPDATE -> {
                    app.writer.updateFrom(one.stored.id, single, phone)
                    Imported(emptyList(), 1, 0, Screen.Event(one.stored.id, single.start, IcsImport.firstEnd(single)), single)
                }
                single != null && one != null && ready.action == ImportAction.MARK_CANCELED -> {
                    app.writer.markCanceled(one.stored.id)
                    Imported(emptyList(), 0, 1, null, single)
                }
                single != null && one != null && ready.action == ImportAction.REMOVE -> Imported(emptyList(), 0, 0, null, single, removed = one.stored)
                single != null -> {
                    // A copy gets an id of its own: two events may not share one
                    val event = if (one != null) single.copy(uid = UUID.randomUUID().toString() + EventWriter.UID_SUFFIX) else single
                    val ids = app.writer.import(ready.calendarId, listOf(event), phone, ready::reminders)
                    if (ids.isEmpty()) null else Imported(ids, 0, 0, Screen.Event(ids.first(), event.start, IcsImport.firstEnd(event)), event)
                }
                else -> {
                    val chosen = ready.events.indices.filter { it in ready.selected }
                    val fresh = chosen.filter { ready.standing(it) == Standing.NEW }.map { ready.events[it] }
                    val ids = app.writer.import(ready.calendarId, fresh, phone, ready::reminders)
                    var updated = 0
                    var canceled = 0
                    for (index in chosen) {
                        val known = ready.known[index] ?: continue
                        when (known.standing) {
                            Standing.CHANGED -> { app.writer.updateFrom(known.stored.id, ready.events[index], phone); updated++ }
                            Standing.CANCELED -> { app.writer.markCanceled(known.stored.id); canceled++ }
                            else -> Unit
                        }
                    }
                    if (ids.isEmpty() && updated == 0 && canceled == 0) null else Imported(ids, updated, canceled, null, chosen.firstOrNull()?.let { ready.events[it] })
                }
            }
        } catch (_: Exception) {
            null
        }
        _state.value = ready.copy(adding = false)
        if (result != null && result.added.isNotEmpty()) ready.calendars.firstOrNull { it.id == ready.calendarId }?.let { app.preferences.setLastCalendar(it.key) }
        return result
    }

    private companion object {
        /** The mark some programs put at a file's very start. */
        val BOM = Char(0xFEFF).toString()

        /** Far above any real calendar file: ten thousand events are a few megabytes. */
        const val MAX_BYTES = 16 * 1_048_576
    }
}
