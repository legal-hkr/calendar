@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.calendar.ui.calendar

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.components.CalendarAccess
import sh.hnet.calendar.ui.components.EmptyState
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.rememberCalendarAccessAsk

/**
 * The app's root screen (SPEC §3, §18). So far it is Agenda alone, with Settings in the top bar;
 * the other views, the view menu, Today, search and the new-event button come with their stages.
 * Without access or without any calendar it says so instead (SPEC §15.2, §12.6).
 */
@Composable
fun CalendarScreen(onOpenSettings: () -> Unit, viewModel: AgendaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from the system's settings or its permission dialog: the answer may have changed
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    // The period the list is at; the app's name where there is no list
    var period by remember { mutableStateOf<String?>(null) }
    val ready = state as? CalendarState.Ready
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    period?.takeIf { ready != null } ?: stringResource(R.string.app_name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        when (val current = state) {
            CalendarState.Loading -> Unit
            CalendarState.NoAccess -> NoAccess(onAnswer = viewModel::refreshAccess)
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size
            CalendarState.NoCalendars -> EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_no_calendars), iconSize = 108.dp)
            is CalendarState.Ready -> AgendaList(current, onPeriod = { period = it }, onNearEnd = viewModel::extend)
        }
    }
}

/**
 * Access refused (SPEC §15.2, board 30). While the system still shows its dialog the button asks
 * again; after that only the system's settings can turn the access on, and the note says where.
 */
@Composable
private fun NoAccess(onAnswer: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ask = rememberCalendarAccessAsk(onAnswer)
    // Read on every composition: it changes with each answer, after which the screen is composed again
    val mayAsk = CalendarAccess.mayAsk(context, activity)
    EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_no_access)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (mayAsk) {
                Button(onClick = ask) { Text(stringResource(R.string.button_allow_access)) }
            } else {
                Button(onClick = { CalendarAccess.openAppSettings(context) }) { Text(stringResource(R.string.button_open_app_settings)) }
                Text(
                    stringResource(R.string.msg_no_access_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
