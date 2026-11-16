@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.importing

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.data.EventDelete
import sh.hnet.calendar.model.IcsImport
import sh.hnet.calendar.model.Scope
import sh.hnet.calendar.model.IcsEvent
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Repetition
import sh.hnet.calendar.ui.Screen
import sh.hnet.calendar.ui.calendar.CalendarFormats
import sh.hnet.calendar.ui.calendar.rememberCalendarFormats
import sh.hnet.calendar.ui.calendars.CalendarsViewModel
import sh.hnet.calendar.ui.calendars.CreateCalendarDialog
import sh.hnet.calendar.ui.components.FormPage
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.SettingsRadioRow
import sh.hnet.calendar.ui.components.SingleChoiceDialog
import sh.hnet.calendar.ui.components.boldNamesResource
import sh.hnet.calendar.ui.components.groupShapes
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.event.EventWords
import sh.hnet.calendar.ui.event.rememberEventWords
import sh.hnet.calendar.ui.theme.fill

/** What the system's picker is asked for: calendar files, and what such files often arrive as. */
val ICS_MIME_TYPES = arrayOf("text/calendar", "application/ics", "text/x-vcalendar", "application/octet-stream", "text/plain")

private const val DAY = 86_400_000L

/** The day an event starts on, on this phone. */
private fun IcsEvent.startDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(start).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

/** When the event's first occurrence ends. */
private fun IcsEvent.firstEnd(): Long = IcsImport.firstEnd(this)

/** "Wednesday, November 18, 14:00–16:00"; an all-day event its day, or its first and last. */
private fun whenText(context: Context, event: IcsEvent, formats: CalendarFormats, thisYear: Int): String {
    fun day(date: LocalDate) = formats.capitalize(formats.day(date, thisYear))
    val first = event.startDate(formats.zone)
    val end = event.firstEnd()
    if (event.allDay) {
        val last = Instant.ofEpochMilli(maxOf(end, event.start + DAY)).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return if (last > first) context.getString(R.string.label_time_range, day(first), day(last)) else day(first)
    }
    val time = if (end > event.start) context.getString(R.string.label_time_range, formats.time(event.start), formats.time(end)) else formats.time(event.start)
    return context.getString(R.string.notification_day_and_time, day(first), time)
}

/** The repetition in words, as the editor says it; null for an event that doesn't repeat. */
private fun repetitionText(event: IcsEvent, words: EventWords, zone: ZoneId): String? {
    val rule = event.rrule ?: return null
    val start = event.startDate(event.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone)
    val (text, end) = words.repetition(Repetition.of(rule, start, zone), start)
    return listOfNotNull(text, end).joinToString(", ")
}

/**
 * "Import from a file" (SPEC §13, boards 12, 23, 26): what the file holds – one event as on its
 * page, several as a list to choose from –, the calendar they go to and their reminders, and one
 * button at the foot. A file that can't be imported says why, with the one way on.
 */
@Composable
fun ImportScreen(
    screen: Screen.Import,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onClose: () -> Unit,
    onOpenEvent: (Screen.Event) -> Unit,
    onDelete: (EventDelete) -> Unit,
    viewModel: ImportViewModel = viewModel(key = "import/${screen.session}"),
    calendars: CalendarsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as CalendarApplication
    LaunchedEffect(screen) { viewModel.open(screen.uri.toUri()) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formats = rememberCalendarFormats(remember { ZoneId.systemDefault() })
    val words = rememberEventWords()
    val thisYear = remember { LocalDate.now().year }
    var creating by rememberSaveable { mutableStateOf(false) }
    // 1 the calendar, 2 the reminders
    var choosing by rememberSaveable { mutableIntStateOf(0) }
    val another = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(viewModel::open) }
    val close = stringResource(R.string.button_close)
    val noTitle = stringResource(R.string.label_no_title)
    val wasValue = stringResource(R.string.label_was_value)
    val added = stringResource(R.string.msg_event_added)
    val nothing = stringResource(R.string.error_import_nothing_added)
    val addedMany = stringResource(R.string.msg_events_added)
    val updated = stringResource(R.string.msg_event_updated)
    val marked = stringResource(R.string.msg_event_marked_canceled)
    val done = stringResource(R.string.msg_import_done)
    val undo = stringResource(R.string.button_undo)
    BackHandler(onBack = onClose)

    fun run() {
        messageScope.launch {
            val imported = viewModel.run()
            if (imported == null) {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(nothing, duration = SnackbarDuration.Short)
                return@launch
            }
            onClose()
            // A canceled event the user chose to remove: deleted as any event, with Undo (SPEC §13.6)
            imported.removed?.let { stored ->
                onDelete(EventDelete(stored.id, stored.event.start, Scope.ALL))
                return@launch
            }
            val first = imported.first
            val several = imported.added.size + imported.updated + imported.canceled > 1
            if (!several) {
                // One event: its page (SPEC §13.9)
                imported.open?.let(onOpenEvent)
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(if (imported.updated == 1) updated else if (imported.canceled == 1) marked else added, duration = SnackbarDuration.Short)
            } else {
                // Several: Day on the first one's date; Undo takes back what was added – when nothing was changed besides (§13.7)
                first?.let { app.goTo.value = JulianDay.of(it.startDate(ZoneId.systemDefault())) }
                snackbars.currentSnackbarData?.dismiss()
                if (imported.updated + imported.canceled == 0) {
                    val result = snackbars.showSnackbar(addedMany.format(imported.added.size), actionLabel = undo, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) runCatching { app.writer.deleteAll(imported.added) }
                } else {
                    snackbars.showSnackbar(done.format(imported.added.size, imported.updated + imported.canceled), duration = SnackbarDuration.Short)
                }
            }
        }
    }

    when (val current = state) {
        ImportState.Loading -> FormPage(stringResource(R.string.title_import), stringResource(R.string.button_add_to_calendar), {}, onBack = onClose, confirmEnabled = false, navigationIcon = R.drawable.ic_close, navigationLabel = close) {}
        is ImportState.Failed -> {
            val noCalendar = current.problem == ImportProblem.NO_CALENDAR
            FormPage(
                stringResource(R.string.title_import), stringResource(if (noCalendar) R.string.button_create_calendar else R.string.button_choose_another_file),
                onConfirm = { if (noCalendar) creating = true else another.launch(ICS_MIME_TYPES) },
                onBack = onClose, navigationIcon = R.drawable.ic_close, navigationLabel = close, contentSpacing = 16.dp,
            ) {
                if (current.fileName.isNotEmpty()) Text(boldNamesResource(R.string.label_file, current.fileName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val (title, text) = when (current.problem) {
                    ImportProblem.UNREADABLE -> R.string.error_import_unreadable_title to R.string.error_import_unreadable
                    ImportProblem.NO_EVENTS -> R.string.error_import_no_events_title to R.string.error_import_no_events
                    ImportProblem.NO_CALENDAR -> R.string.error_import_no_calendar_title to R.string.error_import_no_calendar
                    ImportProblem.CANCELS_UNKNOWN -> R.string.error_import_cancel_unknown_title to R.string.error_import_cancel_unknown
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_error), contentDescription = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        is ImportState.Ready -> {
            val calendar = current.calendars.firstOrNull { it.id == current.calendarId }
            val count = current.events.size
            val single = current.events.singleOrNull()
            val known = current.known[0].takeIf { single != null }
            // Whether the button adds something to the chosen calendar: then the calendar and the reminders are to choose
            val adds = single == null || current.action == ImportAction.ADD || current.action == ImportAction.COPY
            val button = when (current.action.takeIf { single != null }) {
                ImportAction.UPDATE -> R.string.button_update_event
                ImportAction.MARK_CANCELED -> R.string.label_mark_canceled
                ImportAction.REMOVE -> R.string.button_remove_event
                ImportAction.COPY -> R.string.label_add_as_copy
                else -> R.string.button_add_to_calendar
            }
            FormPage(
                stringResource(R.string.title_import), stringResource(button), ::run,
                onBack = onClose, confirmEnabled = current.selected.isNotEmpty() || single != null, busy = current.adding, navigationIcon = R.drawable.ic_close, navigationLabel = close, contentSpacing = 16.dp,
            ) {
                Text(
                    boldNamesResource(R.string.label_file_events, current.fileName.ifEmpty { "ICS" }, pluralStringResource(R.plurals.label_events_count, count, count)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (single != null) {
                    if (known != null) {
                        val name = known.calendar?.name.orEmpty()
                        when (known.standing) {
                            Standing.CANCELED -> InfoCard(R.drawable.ic_event_busy, boldNamesResource(R.string.msg_import_canceled, name))
                            Standing.CHANGED -> InfoCard(R.drawable.ic_info, boldNamesResource(R.string.msg_import_exists, name))
                            else -> InfoCard(R.drawable.ic_info, boldNamesResource(R.string.msg_import_exists_same, name))
                        }
                    }
                    // What the file changes shows the saved value after it (SPEC §13.5)
                    val before = known?.takeIf { it.standing == Standing.CHANGED }?.stored?.event
                    val changes = known?.changes.orEmpty()
                    fun was(now: String, field: IcsImport.Field, old: String?): String =
                        if (before != null && field in changes && !old.isNullOrEmpty() && old != now) wasValue.format(now, old) else now
                    EventCard(
                        single.copy(
                            title = was(single.title.ifEmpty { noTitle }, IcsImport.Field.TITLE, before?.title),
                            location = was(single.location, IcsImport.Field.PLACE, before?.location),
                            status = if (known?.standing == Standing.CANCELED) EventStatus.CANCELED else single.status,
                        ),
                        was(whenText(context, single, formats, thisYear), IcsImport.Field.TIME, before?.let { whenText(context, it, formats, thisYear) }),
                        repetitionText(single, words, formats.zone),
                    )
                    if (known != null && known.standing != Standing.SAME) {
                        val choices = if (known.standing == Standing.CANCELED) {
                            listOf(Triple(ImportAction.MARK_CANCELED, R.string.label_mark_canceled, R.string.desc_mark_canceled), Triple(ImportAction.REMOVE, R.string.label_remove_from_calendar, R.string.desc_remove_from_calendar))
                        } else {
                            listOf(Triple(ImportAction.UPDATE, R.string.label_update_saved_event, R.string.desc_update_saved_event), Triple(ImportAction.COPY, R.string.label_add_as_copy, R.string.desc_add_as_copy))
                        }
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            choices.forEachIndexed { index, (action, title, description) ->
                                SettingsRadioRow(groupShapes(index, choices.size), stringResource(title), current.action == action, { viewModel.change { it.copy(action = action) } }, stringResource(description))
                            }
                        }
                    }
                }
                // Where new events go and what reminds of them
                if (adds) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val rows = if (current.fileHasReminders || single != null) 2 else 1
                        SegmentedListItem(
                            onClick = { choosing = 1 },
                            shapes = groupShapes(0, rows),
                            colors = settingsRowColors(),
                            leadingContent = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { if (calendar != null) Box(Modifier.size(16.dp).background(calendar.color.fill(), CircleShape)) } },
                            trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
                            supportingContent = { Text(calendar?.let { account(it) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        ) {
                            Text(calendar?.name ?: stringResource(R.string.label_calendar), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (rows == 2) {
                            val usual = single?.let { event -> current.defaults.reminders(event.allDay).joinToString(", ") { words.reminder(it, event.allDay) }.ifEmpty { stringResource(R.string.label_reminders_none) } }
                            val supporting = when {
                                current.fromFile -> stringResource(R.string.label_reminders_from_file)
                                usual != null -> stringResource(R.string.label_reminders_default, usual)
                                else -> stringResource(R.string.label_reminders_usual)
                            }
                            if (current.fileHasReminders) {
                                SegmentedListItem(onClick = { choosing = 2 }, shapes = groupShapes(1, rows), colors = settingsRowColors(), leadingContent = { RowIcon(R.drawable.ic_notifications) }, supportingContent = { Text(supporting) }) {
                                    Text(stringResource(R.string.label_reminders))
                                }
                            } else {
                                SegmentedListItem(shapes = groupShapes(1, rows), colors = settingsRowColors(), leadingContent = { RowIcon(R.drawable.ic_notifications) }, supportingContent = { Text(supporting) }) {
                                    Text(stringResource(R.string.label_reminders))
                                }
                            }
                        }
                    }
                }
                if (single == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val selectable = current.selectable
                        val all = selectable.isNotEmpty() && current.selected.containsAll(selectable)
                        SelectRow(groupShapes(0, count + 1), stringResource(R.string.label_select_all), stringResource(R.string.label_selected_of, current.selected.size, count), all, struck = false, enabled = selectable.isNotEmpty()) {
                            viewModel.change { it.copy(selected = if (all) emptySet() else it.selectable) }
                        }
                        current.events.forEachIndexed { index, event ->
                            // What will happen to an event the calendar has already; a new one says when it is
                            val line = when (current.standing(index)) {
                                Standing.CHANGED -> stringResource(R.string.label_import_will_update)
                                Standing.SAME -> stringResource(R.string.label_import_already)
                                Standing.CANCELED -> stringResource(R.string.label_import_will_cancel)
                                Standing.NEW -> repetitionText(event, words, formats.zone) ?: whenText(context, event, formats, thisYear)
                            }
                            SelectRow(
                                groupShapes(index + 1, count + 1), event.title.ifEmpty { noTitle }, line, index in current.selected,
                                struck = event.status == EventStatus.CANCELED, enabled = index in selectable,
                            ) {
                                viewModel.change { it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index) }
                            }
                        }
                    }
                }
            }
            when (choosing) {
                1 -> SingleChoiceDialog(
                    title = AnnotatedString(stringResource(R.string.label_calendar)), options = current.calendars, initial = calendar ?: current.calendars.first(),
                    label = { it.name }, confirmLabel = stringResource(R.string.button_save),
                    onConfirm = { chosen -> choosing = 0; viewModel.change { it.copy(calendarId = chosen.id) } }, onDismiss = { choosing = 0 },
                )
                2 -> SingleChoiceDialog(
                    title = AnnotatedString(stringResource(R.string.label_reminders)), options = listOf(false, true), initial = current.fromFile,
                    label = { stringResource(if (it) R.string.label_reminders_from_file else R.string.label_reminders_usual) }, confirmLabel = stringResource(R.string.button_save),
                    onConfirm = { fromFile -> choosing = 0; viewModel.change { it.copy(fromFile = fromFile) } }, onDismiss = { choosing = 0 },
                )
            }
        }
    }
    if (creating) {
        // The page reads the calendars again when the new one is there
        CreateCalendarDialog(emptySet(), onCreate = { name, color -> creating = false; calendars.create(name, color) { viewModel.open(screen.uri.toUri()) } }, onDismiss = { creating = false })
    }
}

@Composable
private fun account(calendar: CalendarInfo): String = if (calendar.local) stringResource(R.string.label_on_this_phone) else calendar.accountName

/** The one event of a file, as its page would show it (SPEC §13.2): title, when, how it repeats, where, and the description. */
@Composable
private fun EventCard(event: IcsEvent, time: String, repetition: String?) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                event.title.ifEmpty { stringResource(R.string.label_no_title) },
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (event.status == EventStatus.CANCELED) TextDecoration.LineThrough else null,
            )
            Text(time, style = MaterialTheme.typography.bodyLarge)
            if (repetition != null) Text(repetition, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.location.isNotEmpty()) Text(event.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.description.isNotEmpty()) Text(event.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What the calendar already knows of the file's event (SPEC §13.5, §13.6). */
@Composable
private fun InfoCard(icon: Int, text: AnnotatedString) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** A row with a checkbox (SPEC §13.4): "All" with the count, or one event with when it is – or what will happen to it. */
@Composable
private fun SelectRow(shapes: ListItemShapes, title: String, supporting: String, checked: Boolean, struck: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (struck) TextDecoration.LineThrough else null)
    }
}
