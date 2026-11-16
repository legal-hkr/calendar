@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.calendars

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.IcsCalendar
import sh.hnet.calendar.ui.components.ConfirmDestructiveDialog
import sh.hnet.calendar.ui.components.DestructiveButton
import sh.hnet.calendar.ui.components.ErrorDetails
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsNavigationRow
import sh.hnet.calendar.ui.components.SettingsNote
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.SettingsSwitchRow
import sh.hnet.calendar.ui.components.boldNamesResource
import sh.hnet.calendar.ui.components.reportFailure
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.theme.fill

/** A local calendar's name, as it is checked before it is taken: not empty, and not another local calendar's (SPEC §12.5). */
@Composable
private fun nameError(name: String, taken: Set<String>): String? = when {
    name.isBlank() -> stringResource(R.string.error_calendar_name_empty)
    name.trim().lowercase() in taken -> stringResource(R.string.error_calendar_name_taken)
    else -> null
}

/**
 * "Create calendar" (SPEC §12.5, board 20): the name and one of the twelve colors. [taken] are the
 * names of the local calendars there are, in lower case. The error shows once Create was tried.
 */
@Composable
fun CreateCalendarDialog(taken: Set<String>, onCreate: (name: String, color: CalendarColor) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(CalendarColor.BLUE) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val error = nameError(name, taken)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_create_calendar)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    isError = tried && error != null,
                    supportingText = if (tried && error != null) ({ Text(error) }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(stringResource(R.string.title_color_of, stringResource(color.nameRes())), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (line in CalendarColor.entries.chunked(6)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (one in line) {
                                val label = stringResource(one.nameRes())
                                // 36 dp, the chosen one with a ring in the theme's outline around it
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).selectable(selected = one == color, role = Role.RadioButton) { color = one }
                                        .then(if (one == color) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.size(if (one == color) 30.dp else 36.dp).background(one.fill(), CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = { if (error == null) onCreate(name.trim(), color) else tried = true }) { Text(stringResource(R.string.button_create)) } },
    )
}

/** "Rename calendar": the name alone, checked as on creating. */
@Composable
private fun RenameDialog(current: String, taken: Set<String>, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val error = nameError(name, taken - current.trim().lowercase())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_rename_calendar)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_name)) },
                singleLine = true,
                isError = tried && error != null,
                supportingText = if (tried && error != null) ({ Text(error) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = { if (error == null) onRename(name.trim()) else tried = true }) { Text(stringResource(R.string.button_save)) } },
    )
}

/** "praca_20261116.ics": the calendar's name without what a file name can't hold, and the day. */
private fun exportFileName(name: String): String {
    val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(' ', '_').take(40).ifEmpty { "calendar" }
    return "${safe}_${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.ics"
}

/**
 * A local calendar's page (SPEC §12.3, board 19): its name, color and whether it is shown; the
 * export to an ICS file, which is the only copy of its events; and Delete, which asks first and
 * names the number of events (§12.4, board 21) – there is nothing to undo it from.
 */
@Composable
fun LocalCalendarScreen(calendarId: Long, snackbars: SnackbarHostState, messageScope: CoroutineScope, onBack: () -> Unit, onShowError: (ErrorDetails) -> Unit, viewModel: CalendarsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    val context = LocalContext.current
    val app = context.applicationContext as CalendarApplication
    val scope = rememberCoroutineScope()
    val ready = state as? CalendarsState.Ready
    val locals = ready?.groups?.filter { it.local }?.flatMap { it.calendars }.orEmpty()
    val calendar = locals.firstOrNull { it.id == calendarId }
    // Deleted here or elsewhere, or access taken away: nothing left to show
    LaunchedEffect(state, calendar) { if (state is CalendarsState.NoAccess || (ready != null && calendar == null)) onBack() }
    calendar ?: return

    var renaming by rememberSaveable { mutableStateOf(false) }
    var coloring by rememberSaveable { mutableStateOf(false) }
    // The number of events the confirmation names; below zero while nobody asked
    var deleting by rememberSaveable { mutableIntStateOf(-1) }
    val exported = stringResource(R.string.msg_calendar_exported)
    val failed = stringResource(R.string.error_export_failed)
    val deleted = stringResource(R.string.msg_calendar_deleted)
    val show = stringResource(R.string.button_show)

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = IcsCalendar.write(calendar.name, Instant.now(), app.localCalendars.events(calendar.id))
                    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
                }
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(exported, duration = SnackbarDuration.Short)
            } catch (failure: Exception) {
                reportFailure(snackbars, failed, failure.javaClass.simpleName + (failure.message?.let { ": $it" } ?: ""), show, onShowError)
            }
        }
    }

    SettingsPage(title = calendar.name, onBack = onBack) {
        SettingsNote(stringResource(R.string.msg_local_calendar))
        SettingsGroup {
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_edit, stringResource(R.string.label_name), calendar.name, onClick = { renaming = true }) }
            row { shapes ->
                SegmentedListItem(
                    onClick = { coloring = true },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_palette) },
                    trailingContent = { Box(Modifier.size(24.dp).background(calendar.color.fill(), RoundedCornerShape(8.dp))) },
                    supportingContent = { Text(stringResource(calendar.color.nameRes())) },
                ) {
                    Text(stringResource(R.string.label_color))
                }
            }
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_visibility, stringResource(R.string.label_show_in_calendar), calendar.shown, { viewModel.setShown(calendar, it) }) }
        }
        SettingsGroup(stringResource(R.string.label_backup_group)) {
            row { shapes ->
                SegmentedListItem(
                    onClick = { export.launch(exportFileName(calendar.name)) },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_upload_file) },
                    supportingContent = { Text(stringResource(R.string.desc_export_ics)) },
                ) {
                    Text(stringResource(R.string.label_export_ics))
                }
            }
        }
        DestructiveButton(stringResource(R.string.button_delete_calendar), R.drawable.ic_delete, onClick = { scope.launch { deleting = runCatching { app.localCalendars.eventCount(calendar.id) }.getOrDefault(0) } })
    }

    if (renaming) {
        RenameDialog(calendar.name, locals.mapTo(HashSet()) { it.name.trim().lowercase() }, onRename = { name -> renaming = false; viewModel.rename(calendar, name) }, onDismiss = { renaming = false })
    }
    if (coloring) {
        ColorSheet(calendar.name, calendar.color, onPick = { viewModel.setColor(calendar, it) }, onDismiss = { coloring = false })
    }
    if (deleting >= 0) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_delete,
            title = boldNamesResource(R.string.title_delete_calendar, calendar.name),
            confirmLabel = stringResource(R.string.button_delete),
            onConfirm = {
                deleting = -1
                viewModel.delete(calendar) {
                    messageScope.launch {
                        snackbars.currentSnackbarData?.dismiss()
                        snackbars.showSnackbar(deleted, duration = SnackbarDuration.Short)
                    }
                }
            },
            onDismiss = { deleting = -1 },
        ) {
            Text(pluralStringResource(R.plurals.msg_delete_calendar, deleting, deleting))
        }
    }
}
