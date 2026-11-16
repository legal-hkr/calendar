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
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.theme.fill

/**
 * Calendars (SPEC §12.1, board 10): every calendar on the phone, grouped by account with the
 * local ones last, each with its color – a tap on the square opens the color sheet – and a switch
 * for showing it in the app. Both are the app's own choices; nothing is written to the provider.
 * A local calendar's row opens its page (§12.3); "Create calendar" is the last row of the local
 * group, which is there for it also when the phone has no local calendar yet.
 */
@Composable
fun CalendarsScreen(onBack: () -> Unit, onOpenLocal: (Long) -> Unit, viewModel: CalendarsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    // The calendar whose color is being chosen, by its key: the row may change underneath
    var coloring by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? CalendarsState.Ready
    var creating by rememberSaveable { mutableStateOf(false) }
    val locals = ready?.groups?.filter { it.local }?.flatMap { it.calendars }.orEmpty()

    SettingsPage(title = stringResource(R.string.title_calendars), onBack = onBack) {
        when {
            state is CalendarsState.NoAccess -> EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_no_access))
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size
            ready != null -> for (group in ready.groups + if (locals.isEmpty()) listOf(CalendarGroup("", null, true, emptyList())) else emptyList()) {
                val title = when {
                    group.local -> stringResource(R.string.label_on_this_phone)
                    group.app != null -> stringResource(R.string.label_account_and_app, group.accountName, group.app)
                    else -> group.accountName
                }
                SettingsGroup(title) {
                    for (calendar in group.calendars) {
                        row { shapes ->
                            CalendarRow(shapes, calendar, onShown = { viewModel.setShown(calendar, it) }, onColor = { coloring = calendar.key }, onOpen = if (calendar.local) ({ onOpenLocal(calendar.id) }) else null)
                        }
                    }
                    if (group.local) {
                        row { shapes ->
                            SegmentedListItem(onClick = { creating = true }, shapes = shapes, colors = settingsRowColors(), leadingContent = {
                                // In the same 48 dp box as the color squares of the rows above, so the icon and the text line up with theirs
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { RowIcon(R.drawable.ic_add) }
                            }) {
                                Text(stringResource(R.string.button_create_calendar))
                            }
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
    if (creating) {
        CreateCalendarDialog(locals.mapTo(HashSet()) { it.name.trim().lowercase() }, onCreate = { name, color -> creating = false; viewModel.create(name, color) }, onDismiss = { creating = false })
    }
}

/**
 * A calendar's row: the whole row switches it on and off, the color square is a button of its own.
 * A local calendar's row opens its page instead ([onOpen]), and the switch alone switches.
 */
@Composable
private fun CalendarRow(shapes: ListItemShapes, calendar: CalendarInfo, onShown: (Boolean) -> Unit, onColor: () -> Unit, onOpen: (() -> Unit)? = null) {
    val colorLabel = stringResource(R.string.title_color_of, stringResource(calendar.color.nameRes()))
    val supporting = when {
        !calendar.writable -> stringResource(R.string.label_read_only)
        !calendar.synced && !calendar.local -> stringResource(R.string.label_not_synced)
        else -> null
    }
    val leading: @Composable () -> Unit = {
        // A 48 dp target around the 40 dp square, which stands where a settings row has its icon box
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onColor).semantics { contentDescription = colorLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(calendar.color.fill(), RoundedCornerShape(12.dp)))
        }
    }
    val supportingContent: (@Composable () -> Unit)? = supporting?.let { { Text(it) } }
    if (onOpen != null) {
        SegmentedListItem(onClick = onOpen, shapes = shapes, colors = settingsRowColors(), leadingContent = leading, trailingContent = { Switch(checked = calendar.shown, onCheckedChange = onShown) }, supportingContent = supportingContent) {
            Text(calendar.name)
        }
        return
    }
    SegmentedListItem(
        checked = calendar.shown,
        onCheckedChange = onShown,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = leading,
        trailingContent = { Switch(checked = calendar.shown, onCheckedChange = null) },
        supportingContent = supportingContent,
    ) {
        Text(calendar.name)
    }
}
