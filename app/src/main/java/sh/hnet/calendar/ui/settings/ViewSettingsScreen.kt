@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.EventDefaults
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.CalendarView
import sh.hnet.calendar.model.ReminderTimes
import sh.hnet.calendar.ui.calendar.rememberSystemWeekStart
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsNavigationRow
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.SettingsSwitchRow
import sh.hnet.calendar.ui.components.SingleChoiceDialog
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.settingsRowColors
import sh.hnet.calendar.ui.event.ReminderDialog
import sh.hnet.calendar.ui.event.rememberEventWords

@Composable
private fun viewName(view: CalendarView?): String = stringResource(
    when (view) {
        null -> R.string.label_view_last_used
        CalendarView.MONTH -> R.string.menu_view_month
        CalendarView.WEEK -> R.string.menu_view_week
        CalendarView.DAY -> R.string.menu_view_day
        CalendarView.AGENDA -> R.string.menu_view_agenda
    },
)

@Composable
private fun dayName(day: DayOfWeek): String {
    val locale = LocalConfiguration.current.locales[0]
    return day.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
}

/** "07:00", or "7 AM" on a 12-hour clock. */
@Composable
private fun hourName(hour: Int): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, context) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm"), locale) }
    return formatter.format(LocalTime.of(hour, 0))
}

/** Settings › View (SPEC §16.2, board 33): how the calendar opens and what its views show. Each choice holds at once. */
@Composable
fun ViewSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = (context.applicationContext as CalendarApplication).preferences
    val scope = rememberCoroutineScope()
    val read by preferences.viewSettings.collectAsStateWithLifecycle(preferences.latest)
    val settings = read ?: return
    val system = rememberSystemWeekStart()
    // 1 the view on start, 2 the first day of the week, 3 the first hour
    var choosing by rememberSaveable { mutableIntStateOf(0) }

    SettingsPage(title = stringResource(R.string.label_settings_view), onBack = onBack) {
        SettingsGroup {
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_calendar_view_month, stringResource(R.string.label_view_on_start), viewName(settings.startView), onClick = { choosing = 1 }) }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_calendar_view_week, stringResource(R.string.label_first_day_of_week),
                    settings.weekStart?.let { dayName(it) } ?: stringResource(R.string.label_first_day_system, dayName(system)), onClick = { choosing = 2 },
                )
            }
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_schedule, stringResource(R.string.label_first_hour), hourName(settings.firstHour), onClick = { choosing = 3 }) }
        }
        SettingsGroup {
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_numbers, stringResource(R.string.label_week_numbers), settings.weekNumbers, { on -> scope.launch { preferences.setWeekNumbers(on) } }) }
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_opacity, stringResource(R.string.label_dim_past), settings.dimPast, { on -> scope.launch { preferences.setDimPast(on) } }) }
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_event_busy, stringResource(R.string.label_show_declined), settings.showDeclined, { on -> scope.launch { preferences.setShowDeclined(on) } }) }
        }
    }

    when (choosing) {
        1 -> SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_view_on_start)), options = listOf(null) + CalendarView.entries, initial = settings.startView,
            label = { viewName(it) }, confirmLabel = stringResource(R.string.button_save),
            onConfirm = { view -> choosing = 0; scope.launch { preferences.setStartView(view) } }, onDismiss = { choosing = 0 },
        )
        2 -> SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_first_day_of_week)), options = listOf(null, DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), initial = settings.weekStart,
            label = { day -> if (day == null) stringResource(R.string.label_first_day_system, dayName(system)) else dayName(day) }, confirmLabel = stringResource(R.string.button_save),
            onConfirm = { day -> choosing = 0; scope.launch { preferences.setWeekStart(day) } }, onDismiss = { choosing = 0 },
        )
        3 -> {
            // The hour alone counts: the minutes the dial may be turned to are left out
            val picker = rememberTimePickerState(initialHour = settings.firstHour, initialMinute = 0, is24Hour = DateFormat.is24HourFormat(context))
            TimePickerDialog(
                onDismissRequest = { choosing = 0 },
                confirmButton = { TextButton(onClick = { choosing = 0; scope.launch { preferences.setFirstHour(picker.hour) } }) { Text(stringResource(R.string.button_save)) } },
                dismissButton = { TextButton(onClick = { choosing = 0 }) { Text(stringResource(R.string.button_cancel)) } },
                title = { TimePickerDialogDefaults.Title(displayMode = TimePickerDisplayMode.Picker) },
            ) {
                TimePicker(picker)
            }
        }
    }
}

@Composable
private fun lengthName(minutes: Int): String = when {
    minutes == 90 -> stringResource(R.string.label_length_hour_and_half)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.label_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.label_minutes, minutes, minutes)
}

/**
 * Settings › Events (SPEC §16.3): what a new event starts with – its calendar, its length, and
 * the reminders of a timed and of an all-day one, each a list as in the editor.
 */
@Composable
fun EventsSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CalendarApplication
    val preferences = app.preferences
    val scope = rememberCoroutineScope()
    val defaults by preferences.eventDefaults.collectAsStateWithLifecycle(EventDefaults())
    val words = rememberEventWords()
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    LaunchedEffect(Unit) {
        calendars = runCatching { CalendarChoices.apply(app.calendars.calendars(), app.calendarChoices.choices.first()).filter { it.writable } }.getOrDefault(emptyList())
    }
    // 1 the calendar, 2 the length, 3 a reminder of timed events, 4 of all-day ones
    var choosing by rememberSaveable { mutableIntStateOf(0) }
    val chosen = calendars.firstOrNull { it.key == defaults.calendar }

    SettingsPage(title = stringResource(R.string.label_settings_events), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_calendar_month, stringResource(R.string.label_default_calendar), chosen?.name ?: stringResource(R.string.label_calendar_last_used), onClick = { choosing = 1 })
            }
            row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_timelapse, stringResource(R.string.label_default_length), lengthName(defaults.length), onClick = { choosing = 2 }) }
        }
        for (allDay in listOf(false, true)) {
            val reminders = defaults.reminders(allDay)
            SettingsGroup(stringResource(if (allDay) R.string.label_reminders_all_day else R.string.label_reminders_timed)) {
                for (minutes in reminders) {
                    row { shapes ->
                        SegmentedListItem(
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RowIcon(R.drawable.ic_notifications) },
                            trailingContent = {
                                TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_remove_reminder), { scope.launch { preferences.setDefaultReminders(allDay, reminders - minutes) } })
                            },
                        ) {
                            Text(words.reminder(minutes, allDay), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // At most five, as in the editor
                if (reminders.size < ReminderTimes.MAX) {
                    row { shapes ->
                        SegmentedListItem(onClick = { choosing = if (allDay) 4 else 3 }, shapes = shapes, colors = settingsRowColors(), leadingContent = { Spacer(Modifier.size(40.dp)) }) {
                            Text(stringResource(R.string.button_add_reminder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    when (choosing) {
        1 -> SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_default_calendar)), options = listOf<CalendarInfo?>(null) + calendars, initial = chosen,
            label = { it?.name ?: stringResource(R.string.label_calendar_last_used) }, confirmLabel = stringResource(R.string.button_save),
            onConfirm = { calendar -> choosing = 0; scope.launch { preferences.setDefaultCalendar(calendar?.key) } }, onDismiss = { choosing = 0 },
        )
        2 -> SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_default_length)), options = EventDefaults.LENGTHS, initial = defaults.length,
            label = { lengthName(it) }, confirmLabel = stringResource(R.string.button_save),
            onConfirm = { minutes -> choosing = 0; scope.launch { preferences.setDefaultLength(minutes) } }, onDismiss = { choosing = 0 },
        )
        3, 4 -> {
            val allDay = choosing == 4
            val reminders = defaults.reminders(allDay)
            ReminderDialog(allDay, reminders, words, onAdd = { minutes -> choosing = 0; scope.launch { preferences.setDefaultReminders(allDay, ReminderTimes.add(reminders, minutes)) } }, onDismiss = { choosing = 0 })
        }
    }
}
