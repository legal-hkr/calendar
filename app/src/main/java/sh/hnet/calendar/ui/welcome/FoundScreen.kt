package sh.hnet.calendar.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.calendars.CalendarsState
import sh.hnet.calendar.ui.calendars.CalendarsViewModel
import sh.hnet.calendar.ui.calendars.CreateCalendarDialog
import sh.hnet.calendar.ui.components.FormPage
import sh.hnet.calendar.ui.components.groupShapes
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.settings.rememberRestoreAction
import sh.hnet.calendar.ui.theme.fill

/**
 * The last first-run page (SPEC §15.1, board 29): the calendars found on the phone, grouped by
 * account as on the Calendars page but only to look at, Start, and "Restore from a backup" – the
 * family's restore, which brings the colors back before the first look at the calendar. A phone
 * without any calendar gets the content of §12.6 instead: "Create calendar", or Skip.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FoundScreen(snackbars: SnackbarHostState, messageScope: CoroutineScope, onDone: () -> Unit, viewModel: CalendarsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    val restore = rememberRestoreAction(snackbars, messageScope)
    var creating by rememberSaveable { mutableStateOf(false) }
    val groups = (state as? CalendarsState.Ready)?.groups.orEmpty()
    val none = state is CalendarsState.Ready && groups.isEmpty()

    FormPage(
        title = null,
        confirmLabel = stringResource(if (none) R.string.button_create_calendar else R.string.button_start),
        onConfirm = { if (none) creating = true else onDone() },
        contentSpacing = 0.dp,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.title_wizard_found), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = TITLE_INSET).semantics { heading() })
        if (none) {
            for (text in listOf(R.string.msg_no_calendars, R.string.msg_no_calendars_note)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp))
            }
            TextButton(onClick = onDone, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_skip)) }
        } else {
            Text(stringResource(R.string.msg_wizard_found), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp))
            for (group in groups) {
                val title = when {
                    group.local -> stringResource(R.string.label_on_this_phone)
                    group.app != null -> stringResource(R.string.label_account_and_app, group.accountName, group.app)
                    else -> group.accountName
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() })
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    group.calendars.forEachIndexed { index, calendar ->
                        SegmentedListItem(
                            shapes = groupShapes(index, group.calendars.size),
                            colors = settingsRowColors(),
                            leadingContent = { Box(Modifier.size(40.dp).background(calendar.color.fill(), RoundedCornerShape(12.dp))) },
                            supportingContent = if (calendar.writable) null else ({ Text(stringResource(R.string.label_read_only)) }),
                        ) {
                            Text(calendar.name)
                        }
                    }
                }
            }
        }
        TextButton(onClick = restore.pick, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_restore_backup)) }
    }
    if (creating) {
        CreateCalendarDialog(emptySet(), onCreate = { name, color -> creating = false; viewModel.create(name, color); onDone() }, onDismiss = { creating = false })
    }
}

private val TITLE_INSET = 8.dp
