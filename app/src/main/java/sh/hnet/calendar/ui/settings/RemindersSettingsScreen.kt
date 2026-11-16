@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.settings

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.Preferences
import sh.hnet.calendar.R
import sh.hnet.calendar.reminders.ReminderNotifications
import sh.hnet.calendar.ui.components.NotificationAccess
import sh.hnet.calendar.ui.components.RowIcon
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsNavigationRow
import sh.hnet.calendar.ui.components.SettingsPage
import sh.hnet.calendar.ui.components.SingleChoiceDialog
import sh.hnet.calendar.ui.components.rememberNotificationAsk
import sh.hnet.calendar.ui.components.settingsRowColors

/** What the phone lets the reminders do, as read at one moment. Exact alarms are not among it: with `USE_EXACT_ALARM` they are granted at install. */
private data class Checks(val notifications: Boolean, val restricted: Boolean)

private fun read(context: Context) = Checks(
    notifications = ReminderNotifications.allowed(context),
    restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
)

/** Opens a page of the system's settings; on a phone that lacks it, the app's own page there. */
private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No settings app at all: nothing to open
        }
    }
}

/**
 * Settings › Reminders (SPEC §16.4, board 34), from the family's parts as Clock's alarm
 * reliability: on top, only when something is wrong, the problem card with the one action that
 * fixes it – the worst first, one at a time; then what reminders depend on, a row each with its
 * state, leading to where the system decides it; then how long Snooze puts off, and the system's
 * page for the sound.
 */
@Composable
fun RemindersSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val app = context.applicationContext as CalendarApplication
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf(read(context)) }
    // Read again on every return: the user may have been to the system's settings
    LifecycleResumeEffect(context) {
        checks = read(context)
        onPauseOrDispose { }
    }
    val ask = rememberNotificationAsk {
        checks = read(context)
        app.reminders.request()
    }
    val snooze by app.preferences.snoozeMinutes.collectAsStateWithLifecycle(Preferences.SNOOZE_DEFAULT)
    var choosing by rememberSaveable { mutableStateOf(false) }

    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val channelSettings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotifications.CHANNEL)
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val fixNotifications = {
        when {
            // Allowed as a whole: it is the reminders' channel that was blocked
            ReminderNotifications.manager(context).areNotificationsEnabled() -> context.openSettings(channelSettings)
            NotificationAccess.mayAsk(context, activity) -> ask()
            else -> context.openSettings(notificationSettings)
        }
    }

    SettingsPage(title = stringResource(R.string.label_settings_reminders), onBack = onBack) {
        when {
            !checks.notifications -> ProblemCard(R.drawable.ic_notifications_off, stringResource(R.string.title_problem_notifications), stringResource(R.string.msg_problem_notifications_off), stringResource(R.string.button_turn_on_notifications), fixNotifications)
            // The system has no page of its own for an app's battery use: the app's page leads to it
            checks.restricted -> ProblemCard(R.drawable.ic_battery_alert, stringResource(R.string.title_problem_reminders), stringResource(R.string.msg_problem_background), stringResource(R.string.button_remove_restriction)) { context.openSettings(appSettings) }
        }
        SettingsGroup {
            row { shapes ->
                CheckRow(
                    shapes, if (checks.notifications) R.drawable.ic_notifications else R.drawable.ic_notifications_off, stringResource(R.string.label_check_notifications),
                    stringResource(R.string.label_check_on), stringResource(R.string.label_check_off), !checks.notifications,
                ) { if (checks.notifications) context.openSettings(notificationSettings) else fixNotifications() }
            }
            row { shapes ->
                CheckRow(
                    shapes, R.drawable.ic_battery_alert, stringResource(R.string.label_check_background),
                    stringResource(R.string.label_check_unrestricted), stringResource(R.string.label_check_restricted), checks.restricted,
                ) { context.openSettings(appSettings) }
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_snooze, stringResource(R.string.label_snooze), snoozeLabel(snooze), onClick = { choosing = true })
            }
            row { shapes ->
                SegmentedListItem(
                    onClick = { context.openSettings(channelSettings) },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_volume_up) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.label_system_settings)) },
                ) {
                    Text(stringResource(R.string.label_sound_vibration))
                }
            }
        }
    }
    if (choosing) {
        SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.label_snooze)), options = Preferences.SNOOZE_CHOICES, initial = snooze,
            label = { snoozeLabel(it) }, confirmLabel = stringResource(R.string.button_save),
            onConfirm = { minutes -> choosing = false; scope.launch { app.preferences.setSnoozeMinutes(minutes) } }, onDismiss = { choosing = false },
        )
    }
}

/** "10 minutes", "1 hour". */
@Composable
private fun snoozeLabel(minutes: Int): String =
    if (minutes % 60 == 0) pluralStringResource(R.plurals.label_hours, minutes / 60, minutes / 60) else pluralStringResource(R.plurals.label_minutes, minutes, minutes)

/**
 * The problem card, as Clock's: what keeps reminders from appearing or makes them late, said where
 * reminders are set up, with the single action that fixes it. Announced once when it appears.
 */
@Composable
private fun ProblemCard(@DrawableRes icon: Int, title: String, message: String, button: String, onFix: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(icon), contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = onFix, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), modifier = Modifier.align(Alignment.End)) {
                Text(button)
            }
        }
    }
}

/** One thing reminders depend on: in order, its state in plain words; as a problem, icon box, headline and state in the error roles. */
@Composable
private fun CheckRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, fine: String, wrong: String, problem: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon, error = problem) },
        trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
        supportingContent = { Text(if (problem) wrong else fine, color = if (problem) MaterialTheme.colorScheme.error else Color.Unspecified) },
    ) {
        Text(title, color = if (problem) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}
