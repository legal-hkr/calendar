@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.event

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import sh.hnet.calendar.R
import sh.hnet.calendar.data.EventDelete
import sh.hnet.calendar.model.Answer
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventDetails
import sh.hnet.calendar.model.EventDraft
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.model.Guest
import sh.hnet.calendar.model.Ics
import sh.hnet.calendar.model.Scope
import sh.hnet.calendar.ui.calendar.CalendarFormats
import sh.hnet.calendar.ui.calendar.rememberCalendarFormats
import sh.hnet.calendar.ui.components.MenuItem
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.ScopeDialog
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.groupShapes
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.theme.fill

/**
 * The event page (SPEC §9, boards 08 and 17): one occurrence of an event – its calendar and title,
 * then a row for each field that has content, and the description as a group of its own.
 */
@Composable
fun EventScreen(
    eventId: Long,
    begin: Long,
    end: Long,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: (EventDelete) -> Unit,
    viewModel: EventViewModel = viewModel(key = "event/$eventId/$begin"),
) {
    LaunchedEffect(eventId, begin, end) { viewModel.show(eventId, begin, end) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Deleted underneath, here or by a sync: there is nothing left to show
    LaunchedEffect(state) { if (state == EventState.Gone) onBack() }
    val zone = remember { ZoneId.systemDefault() }
    val formats = rememberCalendarFormats(zone)
    val scope = rememberCoroutineScope()
    val answered = stringResource(R.string.msg_answer_saved)
    Box(Modifier.fillMaxSize()) {
    SettingsPage(title = "", onBack = onBack) {
        val ready = state as? EventState.Ready ?: return@SettingsPage
        val event = ready.event
        val writable = ready.calendar?.writable == true
        Header(event, ready.calendar)
        if (!writable) ReadOnlyNote()
        if (writable && event.invited) {
            Answers(event.answer) { answer ->
                viewModel.answer(event.eventId, answer) {
                    scope.launch {
                        snackbars.currentSnackbarData?.dismiss()
                        snackbars.showSnackbar(answered, duration = SnackbarDuration.Short)
                    }
                }
            }
        }

        val words = rememberEventWords()
        val rows = buildList<@Composable (ListItemShapes) -> Unit> {
            add { TimeRow(it, event, formats) }
            if (event.repeating) add { RepetitionRow(it, event, words) }
            if (event.location.isNotEmpty()) add { PlaceRow(it, event.location, snackbars) }
            if (event.reminders.isNotEmpty()) add { DetailRow(it, R.drawable.ic_notifications, stringResource(R.string.label_reminders), event.reminders.joinToString(", ") { minutes -> words.reminder(minutes, event.allDay) }) }
            if (event.guests.isNotEmpty()) add { GuestsRow(it, event) }
            if (!event.busy || event.private || event.status != EventStatus.CONFIRMED) add { StateRow(it, event) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { index, row -> row(groupShapes(index, rows.size)) }
        }
        if (event.description.isNotEmpty()) Description(event.description)
        // Room for the toolbar that floats over the page's end
        Spacer(Modifier.height(72.dp))
    }
    (state as? EventState.Ready)?.let { ready ->
        Actions(
            event = ready.event,
            writable = ready.calendar?.writable == true,
            formats = formats,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).offset(y = -FloatingToolbarDefaults.ScreenOffset),
        )
    }
    }
}

/** "Going?" (SPEC §9.3): Yes, Maybe, No as connected toggle buttons; a tap saves the answer. */
@Composable
private fun Answers(answer: Answer, onAnswer: (Answer) -> Unit) {
    val choices = listOf(Answer.ACCEPTED to R.string.button_answer_yes, Answer.TENTATIVE to R.string.button_answer_maybe, Answer.DECLINED to R.string.button_answer_no)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.label_going), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            choices.forEachIndexed { index, (choice, label) ->
                ToggleButton(
                    checked = answer == choice,
                    onCheckedChange = { if (it) onAnswer(choice) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The page's actions (SPEC §9.5, §9.6) in a floating toolbar: Edit, Duplicate, Share, Delete. An
 * event of a read-only calendar can only be copied and shared; one the user is invited to is the
 * organizer's to change, so it can't be edited either, but taken out of the calendar it can.
 */
@Composable
private fun Actions(event: EventDetails, writable: Boolean, formats: CalendarFormats, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: (EventDelete) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val editable = writable && !event.invited
    val duplicate = stringResource(R.string.button_duplicate)
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        expandedShadowElevation = 3.dp,
        modifier = modifier,
    ) {
        if (editable) {
            Button(onClick = onEdit, contentPadding = PaddingValues(start = 16.dp, end = 20.dp)) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.button_edit))
            }
            TooltipIconButton(R.drawable.ic_content_copy, duplicate, onDuplicate)
        } else {
            FilledTonalButton(onClick = onDuplicate, contentPadding = PaddingValues(start = 16.dp, end = 20.dp)) {
                Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(duplicate)
            }
        }
        Box {
            TooltipIconButton(R.drawable.ic_share, stringResource(R.string.content_description_share), { sharing = true })
            DropdownMenuPopup(expanded = sharing, onDismissRequest = { sharing = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    MenuItem(stringResource(R.string.menu_share_file), R.drawable.ic_calendar_month, 0, 2) { sharing = false; shareFile(context, event) }
                    MenuItem(stringResource(R.string.menu_share_text), R.drawable.ic_subject, 1, 2) { sharing = false; shareText(context, event, formats) }
                }
            }
        }
        if (writable) {
            TooltipIconButton(
                R.drawable.ic_delete, stringResource(R.string.content_description_delete),
                // A repeating event asks first which of its occurrences (SPEC §10.7); any other goes at once, with Undo
                { if (event.repeating && !event.exception) asking = true else onDelete(EventDelete(event.eventId, event.begin, Scope.ALL)) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
            )
        }
    }
    if (asking) {
        ScopeDialog(stringResource(R.string.title_which_events_delete), onChoose = { asking = false; onDelete(EventDelete(event.eventId, event.begin, it)) }, onDismiss = { asking = false })
    }
}

/** The occurrence as an iCalendar file, handed to the app the user picks; one event, without its repetition. */
private fun shareFile(context: Context, event: EventDetails) {
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    directory.listFiles()?.forEach { it.delete() }
    val name = event.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifEmpty { "event" }
    val file = File(directory, "$name.ics")
    file.writeText(Ics.event("event-${event.eventId}-${event.begin}@sh.hnet.calendar", Instant.now(), event))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/** The occurrence in words: title, when, where, and the description. */
private fun shareText(context: Context, event: EventDetails, formats: CalendarFormats) {
    val (date, lines) = timeText(context, event, formats)
    val text = buildString {
        appendLine(event.title.ifEmpty { context.getString(R.string.label_no_title) })
        appendLine(date)
        for (line in lines) appendLine(line)
        if (event.location.isNotEmpty()) appendLine(event.location)
        if (event.description.isNotEmpty()) {
            appendLine()
            appendLine(event.description)
        }
    }.trim()
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
}

/** The calendar with its color and account (SPEC §9.2), and the title, which wraps. */
@Composable
private fun Header(event: EventDetails, calendar: CalendarInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (calendar != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(calendar.color.fill(), CircleShape))
                Text(
                    stringResource(R.string.label_calendar_and_account, calendar.name, if (calendar.local) stringResource(R.string.label_on_this_phone) else calendar.accountName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            event.title.ifEmpty { stringResource(R.string.label_no_title) },
            style = MaterialTheme.typography.headlineSmall,
            textDecoration = if (event.status == EventStatus.CANCELED) TextDecoration.LineThrough else null,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** The line of a read-only calendar's event (SPEC §9.6). */
@Composable
private fun ReadOnlyNote() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.msg_read_only_event), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRow(shapes: ListItemShapes, icon: Int, headline: String, supporting: String?, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val supportingContent: (@Composable () -> Unit)? = supporting?.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    if (onClick != null) {
        SegmentedListItem(onClick = onClick, shapes = shapes, colors = settingsRowColors(), leadingContent = { RowIcon(icon) }, trailingContent = trailing, supportingContent = supportingContent) { Text(headline) }
    } else {
        SegmentedListItem(shapes = shapes, colors = settingsRowColors(), leadingContent = { RowIcon(icon) }, trailingContent = trailing, supportingContent = supportingContent) { Text(headline) }
    }
}

/**
 * When (SPEC §9.4): the date, under it the times or "All day"; an event of several days names its
 * end on a line of its own; a third line has the event's time zone when it is not the phone's.
 */
@Composable
private fun TimeRow(shapes: ListItemShapes, event: EventDetails, formats: CalendarFormats) {
    val (date, lines) = timeText(LocalContext.current, event, formats)
    DetailRow(shapes, R.drawable.ic_schedule, date, lines.joinToString("\n"))
}

/** How it repeats (SPEC §9.4): the rule in words, under it where it ends. */
@Composable
private fun RepetitionRow(shapes: ListItemShapes, event: EventDetails, words: EventWords) {
    // Read as the editor reads it: from the day this occurrence starts on, in the event's own zone
    val draft = remember(event) { EventDraft.of(event, ZoneId.systemDefault()) }
    val (rule, end) = words.repetition(draft.repetition, draft.start.toLocalDate())
    DetailRow(shapes, R.drawable.ic_repeat, rule, end)
}

/** The date an occurrence starts on, and the lines under it; also what sharing as text says. */
private fun timeText(context: Context, event: EventDetails, formats: CalendarFormats): Pair<String, List<String>> {
    fun stringResource(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)
    fun date(date: LocalDate) = formats.capitalize(formats.day(date, Int.MIN_VALUE))
    val lines = mutableListOf<String>()
    val first: LocalDate
    if (event.allDay) {
        // An all-day event runs from midnight UTC of its first day to midnight UTC after its last
        first = Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate()
        val last = Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        lines += stringResource(R.string.label_all_day)
        if (last > first) lines += stringResource(R.string.label_event_ends, date(last))
    } else {
        first = Instant.ofEpochMilli(event.begin).atZone(formats.zone).toLocalDate()
        val last = Instant.ofEpochMilli(event.end).atZone(formats.zone).toLocalDate()
        when {
            last > first -> {
                lines += formats.time(event.begin)
                lines += stringResource(R.string.label_event_ends, stringResource(R.string.label_title_and_time, date(last), formats.time(event.end)))
            }
            event.end > event.begin -> lines += stringResource(R.string.label_time_range, formats.time(event.begin), formats.time(event.end))
            else -> lines += formats.time(event.begin)
        }
        val zone = event.timeZone
        if (zone != null && runCatching { ZoneId.of(zone).rules != formats.zone.rules }.getOrDefault(false)) lines += stringResource(R.string.label_time_zone_value, zone)
    }
    return date(first) to lines
}

/**
 * Where (SPEC §9.4): the first line of the place, the rest under it. A tap hands it to a maps
 * app; without one there is no Directions icon and a tap copies the place.
 */
@Composable
private fun PlaceRow(shapes: ListItemShapes, place: String, snackbars: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = place.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val intent = remember(place) { Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(place)}".toUri()) }
    val maps = remember(intent) { intent.resolveActivity(context.packageManager) != null }
    val copied = stringResource(R.string.msg_place_copied)
    val directions = stringResource(R.string.content_description_directions)
    DetailRow(
        shapes, R.drawable.ic_location_on, lines.firstOrNull().orEmpty(), lines.drop(1).joinToString("\n"),
        trailing = if (maps) ({ Icon(painterResource(R.drawable.ic_directions), contentDescription = directions) }) else null,
        onClick = {
            val opened = maps && runCatching { context.startActivity(intent) }.isSuccess
            if (!opened) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, place))
                scope.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(copied, duration = SnackbarDuration.Short)
                }
            }
        },
    )
}

/**
 * Who (SPEC §9.4): the number of guests, who organizes and how many answered; a tap opens the
 * list in place, a line per guest as "name – state".
 */
@Composable
private fun GuestsRow(shapes: ListItemShapes, event: EventDetails) {
    var open by rememberSaveable { mutableStateOf(false) }
    val summary = buildList {
        if (event.organizerLabel.isNotEmpty()) add(stringResource(R.string.msg_guests_organizer, event.organizerLabel))
        add(stringResource(R.string.msg_guests_summary, event.accepted, event.unanswered))
    }.joinToString(" ")
    val guests = event.guests.map { guestLine(it) }
    SegmentedListItem(
        onClick = { open = !open },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(R.drawable.ic_group) },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f }) },
        supportingContent = {
            Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                Text(summary, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                if (open) for (guest in guests) Text(guest)
            }
        },
    ) {
        Text(stringResource(R.string.label_guests_count, event.guests.size))
    }
}

@Composable
private fun guestLine(guest: Guest): String {
    val state = when {
        guest.organizer -> R.string.label_guest_organizer
        guest.answer == Answer.ACCEPTED -> R.string.label_guest_accepted
        guest.answer == Answer.TENTATIVE -> R.string.label_guest_tentative
        guest.answer == Answer.DECLINED -> R.string.label_guest_declined
        else -> R.string.label_guest_no_answer
    }
    return stringResource(R.string.label_guest_state, guest.label, stringResource(state))
}

/** Availability, visibility and status in one row, only the values that are not the usual ones (SPEC §9.4). */
@Composable
private fun StateRow(shapes: ListItemShapes, event: EventDetails) {
    val values = buildList {
        if (!event.busy) add(stringResource(R.string.label_availability_value, stringResource(R.string.label_free)))
        if (event.private) add(stringResource(R.string.label_visibility_value, stringResource(R.string.label_visibility_private)))
        when (event.status) {
            EventStatus.TENTATIVE -> add(stringResource(R.string.label_status_value, stringResource(R.string.label_status_tentative)))
            EventStatus.CANCELED -> add(stringResource(R.string.label_status_value, stringResource(R.string.label_state_canceled)))
            EventStatus.CONFIRMED -> Unit
        }
    }
    DetailRow(shapes, R.drawable.ic_info, values.first(), values.drop(1).joinToString(", ").ifEmpty { null })
}

/** The description (SPEC §9.4): a group of its own, links open; six lines, then "Show more" opens the rest in place. */
@Composable
private fun Description(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    var long by remember { mutableStateOf(false) }
    val linked = linked(text)
    SegmentedListItem(
        shapes = groupShapes(0, 1),
        colors = settingsRowColors(),
        leadingContent = { RowIcon(R.drawable.ic_subject) },
        supportingContent = {
            Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                Text(
                    linked,
                    maxLines = if (open) Int.MAX_VALUE else DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!open) long = it.hasVisualOverflow },
                )
                if (long) {
                    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(if (open) R.string.button_show_less else R.string.button_show_more))
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.label_description))
    }
}

/** [text] with its web addresses as links, in the theme's primary and underlined. */
@Composable
private fun linked(text: String): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(text, styles) {
        buildAnnotatedString {
            append(text)
            val matcher = Patterns.WEB_URL.matcher(text)
            while (matcher.find()) {
                val found = matcher.group()
                // The pattern also takes bare words with a dot; only what names its scheme or starts with www is a link
                val address = when {
                    found.startsWith("http://", ignoreCase = true) || found.startsWith("https://", ignoreCase = true) -> found
                    found.startsWith("www.", ignoreCase = true) -> "https://$found"
                    else -> continue
                }
                addLink(LinkAnnotation.Url(address, styles), matcher.start(), matcher.end())
            }
        }
    }
}

private const val DESCRIPTION_LINES = 6
