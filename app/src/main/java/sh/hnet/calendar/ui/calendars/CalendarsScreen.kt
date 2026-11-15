@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.ui.components.EmptyState
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.theme.fill

/**
 * Calendars (SPEC §12.1, board 10): every calendar on the phone, grouped by account with the
 * local ones last, each with its color – a tap on the square opens the color sheet – and a switch
 * for showing it in the app. Both are the app's own choices; nothing is written to the provider.
 * Creating and managing local calendars comes with its stage.
 */
@Composable
fun CalendarsScreen(onBack: () -> Unit, viewModel: CalendarsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    // The calendar whose color is being chosen, by its key: the row may change underneath
    var coloring by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? CalendarsState.Ready

    SettingsPage(title = stringResource(R.string.title_calendars), onBack = onBack) {
        when {
            state is CalendarsState.NoAccess -> EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_no_access))
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size
            ready != null && ready.groups.isEmpty() -> EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_no_calendars), iconSize = 108.dp)
            ready != null -> for (group in ready.groups) {
                val title = when {
                    group.local -> stringResource(R.string.label_on_this_phone)
                    group.app != null -> stringResource(R.string.label_account_and_app, group.accountName, group.app)
                    else -> group.accountName
                }
                SettingsGroup(title) {
                    for (calendar in group.calendars) {
                        row { shapes ->
                            CalendarRow(shapes, calendar, onShown = { viewModel.setShown(calendar, it) }, onColor = { coloring = calendar.key })
                        }
                    }
                }
            }
        }
    }

    val calendar = coloring?.let { key -> ready?.groups?.firstNotNullOfOrNull { group -> group.calendars.firstOrNull { it.key == key } } }
    if (calendar != null) {
        ColorSheet(calendar.name, calendar.color, onPick = { viewModel.setColor(calendar, it) }, onDismiss = { coloring = null })
    }
}

/** A calendar's row: the whole row switches it on and off, the color square is a button of its own. */
@Composable
private fun CalendarRow(shapes: ListItemShapes, calendar: CalendarInfo, onShown: (Boolean) -> Unit, onColor: () -> Unit) {
    val colorLabel = stringResource(R.string.title_color_of, stringResource(calendar.color.nameRes()))
    val supporting = when {
        !calendar.writable -> stringResource(R.string.label_read_only)
        !calendar.synced && !calendar.local -> stringResource(R.string.label_not_synced)
        else -> null
    }
    SegmentedListItem(
        checked = calendar.shown,
        onCheckedChange = onShown,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            // A 48 dp target around the 40 dp square, which stands where a settings row has its icon box
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onColor).semantics { contentDescription = colorLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).background(calendar.color.fill(), RoundedCornerShape(12.dp)))
            }
        },
        trailingContent = { Switch(checked = calendar.shown, onCheckedChange = null) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(calendar.name)
    }
}
