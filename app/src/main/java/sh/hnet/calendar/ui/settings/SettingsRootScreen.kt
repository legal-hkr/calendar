package sh.hnet.calendar.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.calendar.BuildConfig
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.components.SettingsGroup
import sh.hnet.calendar.ui.components.SettingsNavigationRow
import sh.hnet.calendar.ui.components.SettingsPage

/**
 * The Settings root, a page over the calendar. Grouped as Music's, Clock's, News's and Calculator's: App
 * (language), Data (reset) and About on its own, after the app's own groups: View, Events and Reminders, then Calendars;
 * Import from a file and Backup join with their stages.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenCalendars: () -> Unit, onOpenView: () -> Unit, onOpenEvents: () -> Unit, onOpenReminders: () -> Unit, onOpenLanguage: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_calendar_view_month, stringResource(R.string.label_settings_view), stringResource(R.string.desc_settings_view), onClick = onOpenView)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_edit, stringResource(R.string.label_settings_events), stringResource(R.string.desc_settings_events), onClick = onOpenEvents)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_notifications, stringResource(R.string.label_settings_reminders), stringResource(R.string.desc_settings_reminders), onClick = onOpenReminders)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_calendar_month, stringResource(R.string.title_calendars), onClick = onOpenCalendars)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}
