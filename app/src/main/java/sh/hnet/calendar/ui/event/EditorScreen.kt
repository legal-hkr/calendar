@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.event

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventDraft
import sh.hnet.calendar.model.Scope
import sh.hnet.calendar.ui.Screen
import sh.hnet.calendar.ui.calendar.CalendarFormats
import sh.hnet.calendar.ui.calendar.rememberCalendarFormats
import sh.hnet.calendar.ui.components.ErrorDetails
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.ScopeDialog
import sh.hnet.calendar.ui.components.SettingsSwitchRow
import sh.hnet.calendar.ui.components.SingleChoiceDialog
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.groupShapes
import sh.hnet.calendar.ui.components.reportFailure
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.theme.fill

/** What the form shows in place of itself: the description's own page, or the list of time zones. */
private enum class Part { FORM, DESCRIPTION, ZONE }

/**
 * The editor (SPEC §10, board 09): a full-screen form for a new event, an existing one or a copy.
 * [onSaved] closes it after a save – with `true` when an existing event was changed, whose page
 * then shows what is no longer so. Repetition and reminders get their rows with their stage; until
 * then an edited event keeps the ones it has.
 */
@Composable
fun EditorScreen(screen: Screen.Editor, snackbars: SnackbarHostState, onClose: () -> Unit, onSaved: (edited: Boolean) -> Unit, onShowError: (ErrorDetails) -> Unit, viewModel: EditorViewModel = viewModel(key = "editor/$screen")) {
    LaunchedEffect(screen) { viewModel.open(screen) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == EditorState.Gone) onClose() }
    val ready = state as? EditorState.Ready ?: return

    val scope = rememberCoroutineScope()
    var part by rememberSaveable { mutableStateOf(Part.FORM) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<SaveResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.error_save_failed)
    val show = stringResource(R.string.button_show)

    fun save(scopeChosen: Scope? = null, force: Boolean = false, asNew: Boolean = false) {
        if (saving) return
        saving = true
        scope.launch {
            val result = viewModel.save(scopeChosen, force, asNew)
            saving = false
            when (result) {
                SaveResult.Saved -> onSaved(ready.editing && !asNew)
                SaveResult.AskScope, SaveResult.Changed, SaveResult.Vanished -> asking = result
                is SaveResult.Failed -> reportFailure(snackbars, failed, result.details, show, onShowError)
            }
        }
    }

    val leave = { if (ready.changed) discarding = true else onClose() }
    BackHandler { if (part != Part.FORM) part = Part.FORM else leave() }

    when (part) {
        Part.FORM -> Form(ready, saving, onChange = viewModel::change, onClose = leave, onSave = { save() }, onOpen = { part = it })
        Part.DESCRIPTION -> DescriptionPage(ready.draft.description, onDone = { text -> viewModel.change { it.copy(description = text) }; part = Part.FORM }, onClose = { part = Part.FORM })
        Part.ZONE -> ZonePage(ready.draft.zone, onChoose = { zone -> viewModel.change { it.copy(zone = zone) }; part = Part.FORM }, onClose = { part = Part.FORM })
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text(stringResource(R.string.title_discard_changes)) },
            confirmButton = { TextButton(onClick = { discarding = false; onClose() }) { Text(stringResource(R.string.button_discard)) } },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(R.string.button_keep_editing)) } },
        )
    }
    when (asking) {
        SaveResult.AskScope -> ScopeDialog(stringResource(R.string.title_which_events_change), onChoose = { asking = null; save(it, force = true) }, onDismiss = { asking = null })
        SaveResult.Changed -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.title_event_changed)) },
            text = { Text(stringResource(R.string.msg_event_changed)) },
            confirmButton = { TextButton(onClick = { asking = null; save(force = true) }) { Text(stringResource(R.string.button_save_my_version)) } },
            dismissButton = { TextButton(onClick = { asking = null; onClose() }) { Text(stringResource(R.string.button_discard_my_changes)) } },
        )
        SaveResult.Vanished -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.title_event_vanished)) },
            confirmButton = { TextButton(onClick = { asking = null; save(asNew = true) }) { Text(stringResource(R.string.button_save_as_new)) } },
            dismissButton = { TextButton(onClick = { asking = null; onSaved(true) }) { Text(stringResource(R.string.button_discard)) } },
        )
        else -> Unit
    }
}

@Composable
private fun Form(ready: EditorState.Ready, saving: Boolean, onChange: ((EventDraft) -> EventDraft) -> Unit, onClose: () -> Unit, onSave: () -> Unit, onOpen: (Part) -> Unit) {
    val draft = ready.draft
    val formats = rememberCalendarFormats(remember(draft.zone) { ZoneId.of(draft.zone) })
    val phone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(phone) }
    // An event kept in another zone than the phone's shows its zone at once: its times are that zone's
    var more by rememberSaveable { mutableStateOf(draft.zone != phone.id && !draft.allDay) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (ready.event == null) focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(if (ready.editing) R.string.title_edit_event else R.string.title_new_event), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), onClose) },
            actions = { Button(onClick = onSave, enabled = !saving, modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.button_save)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { text -> onChange { it.copy(title = text) } },
                label = { Text(stringResource(R.string.label_title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            // When
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchRow(groupShapes(0, 4), R.drawable.ic_schedule, stringResource(R.string.label_all_day), draft.allDay, { on -> onChange { it.withAllDay(on) } })
                TimeRow(groupShapes(1, 4), stringResource(R.string.label_start), draft.start.toLocalDate(), if (draft.allDay) null else draft.start.toLocalTime(), today, formats, { date -> onChange { it.withStartDate(date) } }, { time -> onChange { it.withStartTime(time) } })
                TimeRow(groupShapes(2, 4), stringResource(R.string.label_end), draft.end.toLocalDate(), if (draft.allDay) null else draft.end.toLocalTime(), today, formats, { date -> onChange { it.withEndDate(date) } }, { time -> onChange { it.withEndTime(time) } })
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShapes(3, 4).shape, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val day = draft.start.toLocalDate()
                        for ((label, date) in listOf(R.string.label_today to today, R.string.label_tomorrow to today.plusDays(1), R.string.label_in_a_week to today.plusDays(7))) {
                            FilterChip(selected = day == date, onClick = { onChange { it.withStartDate(date) } }, label = { Text(stringResource(label)) })
                        }
                    }
                }
            }

            // Where it is kept, and where it takes place
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CalendarRow(groupShapes(0, 2), ready, onChoose = { calendar -> onChange { it.copy(calendarId = calendar.id) } })
                SegmentedListItem(shapes = groupShapes(1, 2), colors = settingsRowColors(), leadingContent = { RowIcon(R.drawable.ic_location_on) }) {
                    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                    BasicTextField(
                        value = draft.location,
                        onValueChange = { text -> onChange { it.copy(location = text) } },
                        textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { field ->
                            if (draft.location.isEmpty()) Text(stringResource(R.string.hint_add_place), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        },
                    )
                }
            }

            // The rest
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val rows = if (more) 5 else 2
                SegmentedListItem(
                    onClick = { onOpen(Part.DESCRIPTION) },
                    shapes = groupShapes(0, rows),
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_subject) },
                    supportingContent = if (draft.description.isEmpty()) null else ({ Text(draft.description, maxLines = 2, overflow = TextOverflow.Ellipsis) }),
                ) {
                    Text(stringResource(if (draft.description.isEmpty()) R.string.hint_add_description else R.string.label_description), color = if (draft.description.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                SegmentedListItem(
                    onClick = { more = !more },
                    shapes = groupShapes(1, rows),
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_tune) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = if (more) 180f else 0f }) },
                    supportingContent = { Text(stringResource(R.string.desc_more_options)) },
                ) {
                    Text(stringResource(R.string.label_more_options))
                }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        var choosing by rememberSaveable { mutableStateOf(0) }
                        val busy = stringResource(if (draft.busy) R.string.label_busy else R.string.label_free)
                        val visibility = stringResource(if (draft.private) R.string.label_visibility_private else R.string.label_visibility_default)
                        // An all-day event belongs to no zone
                        OptionRow(groupShapes(2, 5), R.drawable.ic_public, stringResource(R.string.label_time_zone), zoneLabel(draft.zone), enabled = !draft.allDay) { onOpen(Part.ZONE) }
                        OptionRow(groupShapes(3, 5), R.drawable.ic_event_available, stringResource(R.string.label_availability), busy) { choosing = 1 }
                        OptionRow(groupShapes(4, 5), R.drawable.ic_visibility, stringResource(R.string.label_visibility), visibility) { choosing = 2 }
                        if (choosing == 1) {
                            SingleChoiceDialog(
                                title = AnnotatedString(stringResource(R.string.label_availability)), options = listOf(true, false), initial = draft.busy,
                                label = { stringResource(if (it) R.string.label_busy else R.string.label_free) }, confirmLabel = stringResource(R.string.button_save),
                                onConfirm = { value -> onChange { it.copy(busy = value) }; choosing = 0 }, onDismiss = { choosing = 0 },
                            )
                        }
                        if (choosing == 2) {
                            SingleChoiceDialog(
                                title = AnnotatedString(stringResource(R.string.label_visibility)), options = listOf(false, true), initial = draft.private,
                                label = { stringResource(if (it) R.string.label_visibility_private else R.string.label_visibility_default) }, confirmLabel = stringResource(R.string.button_save),
                                onConfirm = { value -> onChange { it.copy(private = value) }; choosing = 0 }, onDismiss = { choosing = 0 },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(shapes: ListItemShapes, icon: Int, title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, shapes = shapes, enabled = enabled, colors = settingsRowColors(), leadingContent = { RowIcon(icon) }, supportingContent = { Text(value) }) { Text(title) }
}

/** "Europe/Warsaw (UTC+01:00)": the zone by its place, with what it is ahead of or behind UTC now. */
private fun zoneLabel(id: String): String {
    val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return id
    val offset = zone.rules.getOffset(Instant.now())
    return "${id.replace('_', ' ')} (UTC${if (offset.totalSeconds == 0) "" else offset.id})"
}

/**
 * Start or End (SPEC §10.2): the label and two tonal pills, the date and – unless the event is all
 * day – the time, each opening Material's picker.
 */
@Composable
private fun TimeRow(shapes: ListItemShapes, label: String, date: LocalDate, time: LocalTime?, today: LocalDate, formats: CalendarFormats, onDate: (LocalDate) -> Unit, onTime: (LocalTime) -> Unit) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val pattern = remember(formats.locale, date.year == today.year) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(formats.locale, if (date.year == today.year) "EEEMMMd" else "EEEMMMdy"), formats.locale) }
    val clock = remember(formats.locale, formats.hours24) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(formats.locale, if (formats.hours24) "Hm" else "hm"), formats.locale) }
    SegmentedListItem(
        shapes = shapes,
        colors = settingsRowColors(),
        // The label stands where the rows above and below have their text
        leadingContent = { Spacer(Modifier.size(40.dp)) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { pickingDate = true }, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text(pattern.format(date), maxLines = 1) }
                if (time != null) {
                    FilledTonalButton(onClick = { pickingTime = true }, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text(clock.format(time), maxLines = 1) }
                }
            }
        },
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (pickingDate) {
        // The picker counts in UTC days
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * DAY_MILLIS)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    pickingDate = false
                    picker.selectedDateMillis?.let { onDate(LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MILLIS))) }
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.button_cancel)) } },
        ) {
            DatePicker(picker)
        }
    }
    if (pickingTime && time != null) {
        val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = DateFormat.is24HourFormat(context))
        var input by rememberSaveable { mutableStateOf(false) }
        val mode = if (input) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
        TimePickerDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = { TextButton(onClick = { pickingTime = false; onTime(LocalTime.of(picker.hour, picker.minute)) }) { Text(stringResource(R.string.button_save)) } },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.button_cancel)) } },
            title = { TimePickerDialogDefaults.Title(displayMode = mode) },
            modeToggleButton = { TimePickerDialogDefaults.DisplayModeToggle(onDisplayModeChange = { input = !input }, displayMode = mode) },
        ) {
            if (input) TimeInput(picker) else TimePicker(picker)
        }
    }
}

private const val DAY_MILLIS = 86_400_000L

/**
 * The calendar the event is kept in (SPEC §10.2, §10.10): its color, name and account; a tap opens
 * the calendars that can be written to. Those an existing event can't go to are listed, but not to be chosen, and say why.
 */
@Composable
private fun CalendarRow(shapes: ListItemShapes, ready: EditorState.Ready, onChoose: (CalendarInfo) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val current = ready.calendars.firstOrNull { it.id == ready.draft.calendarId }
    Box {
        SegmentedListItem(
            onClick = { menu = true },
            shapes = shapes,
            colors = settingsRowColors(),
            leadingContent = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { if (current != null) Box(Modifier.size(16.dp).background(current.color.fill(), CircleShape)) } },
            trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            supportingContent = { Text(account(current), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        ) {
            Text(current?.name ?: stringResource(R.string.label_calendar), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                ready.calendars.forEachIndexed { index, calendar ->
                    val allowed = ready.mayUse(calendar)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(calendar.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (allowed) account(calendar) else stringResource(if (ready.sameAccount(calendar)) R.string.desc_calendar_stays else R.string.desc_calendar_other_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = { Box(Modifier.size(12.dp).background(calendar.color.fill(), CircleShape)) },
                        enabled = allowed,
                        shape = MenuDefaults.itemShape(index, ready.calendars.size).shape,
                        onClick = { menu = false; onChoose(calendar) },
                    )
                }
            }
        }
    }
}

@Composable
private fun account(calendar: CalendarInfo?): String = when {
    calendar == null -> ""
    calendar.local -> stringResource(R.string.label_on_this_phone)
    else -> calendar.accountName
}

/** The description on a page of its own (SPEC §10.2): room for a long text; Done takes it over, Close leaves it as it was. */
@Composable
private fun DescriptionPage(text: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.label_description), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), onClose) },
            actions = { Button(onClick = { onDone(value.trim()) }, modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.button_done)) } },
        )
        TextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(stringResource(R.string.hint_add_description)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).focusRequester(focus),
        )
    }
}

/** The time zones, to search by place; the phone's own first. */
@Composable
private fun ZonePage(current: String, onChoose: (String) -> Unit, onClose: () -> Unit) {
    val phone = remember { ZoneId.systemDefault().id }
    val zones = remember {
        ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted().let { listOf(phone, "UTC") + it }.distinct().map { it to zoneLabel(it) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(zones, query) { if (query.isBlank()) zones else zones.filter { it.second.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.label_time_zone), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onClose) },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            placeholder = { Text(stringResource(R.string.hint_search_time_zone)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(found, key = { _, zone -> zone.first }) { index, (id, label) ->
                SegmentedListItem(
                    selected = id == current,
                    onClick = { onChoose(id) },
                    shapes = groupShapes(index, found.size),
                    colors = settingsRowColors(),
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
