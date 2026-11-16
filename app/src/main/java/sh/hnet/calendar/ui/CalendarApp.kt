@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.R
import sh.hnet.calendar.about.WhatsNew
import sh.hnet.calendar.ui.calendar.CalendarScreen
import sh.hnet.calendar.ui.calendar.SearchScreen
import sh.hnet.calendar.ui.calendars.CalendarsScreen
import sh.hnet.calendar.ui.components.CalendarAccess
import sh.hnet.calendar.ui.components.ErrorDetails
import sh.hnet.calendar.ui.components.ErrorDetailsDialog
import sh.hnet.calendar.ui.event.EditorScreen
import sh.hnet.calendar.ui.event.EventScreen
import sh.hnet.calendar.ui.settings.AboutScreen
import sh.hnet.calendar.ui.settings.AppLicenseScreen
import sh.hnet.calendar.ui.settings.LanguageScreen
import sh.hnet.calendar.ui.settings.LicensesScreen
import sh.hnet.calendar.ui.settings.ResetScreen
import sh.hnet.calendar.ui.settings.SettingsRootScreen
import sh.hnet.calendar.ui.settings.WhatsNewScreen
import sh.hnet.calendar.reminders.ReminderNotifications
import sh.hnet.calendar.ui.components.NotificationAccess
import sh.hnet.calendar.ui.settings.EventsSettingsScreen
import sh.hnet.calendar.ui.settings.RemindersSettingsScreen
import sh.hnet.calendar.ui.settings.ViewSettingsScreen
import sh.hnet.calendar.ui.welcome.AccessScreen
import sh.hnet.calendar.ui.welcome.RemindersScreen

/**
 * The app's shell: one back stack with the calendar at its root; Settings and everything else
 * open over it as pages, with Back. On the first start the page asking for calendar access lies over it all.
 */
@Composable
fun CalendarApp(asked: Screen.Event? = null, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val stack = rememberNavBackStack(Screen.Calendar)
    val snackbars = remember { SnackbarHostState() }

    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair, Music, Clock and News)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = context.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(Screen.Settings)
            stack.add(Screen.SettingsAbout)
            stack.add(Screen.SettingsWhatsNew)
        }
    }

    val app = appContext as CalendarApplication
    // A tapped reminder: its event's page over the calendar, whatever was open
    LaunchedEffect(asked) {
        if (asked != null) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(asked)
            onOpened()
        }
    }
    val messages = rememberCoroutineScope()
    val deleted = stringResource(R.string.msg_event_deleted)
    val undo = stringResource(R.string.button_undo)
    val saved = stringResource(R.string.msg_event_saved)
    var error by remember { mutableStateOf<ErrorDetails?>(null) }
    var welcome by rememberSaveable { mutableStateOf(!CalendarAccess.welcomeSeen(appContext) && !app.calendars.hasAccess()) }
    // The second first-run page (SPEC §15.1): once, also for who started before the app reminded of anything, unless notifications are on already
    var askReminders by rememberSaveable { mutableStateOf(!NotificationAccess.pageSeen(appContext) && !ReminderNotifications.manager(appContext).areNotificationsEnabled()) }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = back,
                // Shared axis X between the calendar and the pages over it
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider<NavKey> {
                    entry<Screen.Calendar> {
                        CalendarScreen(
                            onOpenEvent = { open(Screen.Event(it.eventId, it.begin, it.end)) },
                            onNewEvent = { day, minute -> open(Screen.Editor(day = day, minute = minute, session = System.nanoTime())) },
                            onOpenSearch = { open(Screen.Search) },
                            onOpenCalendars = { open(Screen.Calendars) },
                            onOpenSettings = { open(Screen.Settings) },
                        )
                    }
                    entry<Screen.Search> { SearchScreen(onBack = back, onOpenEvent = { open(Screen.Event(it.eventId, it.begin, it.end)) }) }
                    entry<Screen.Event> { event ->
                        EventScreen(
                            event.eventId, event.begin, event.end, snackbars,
                            onBack = back,
                            onEdit = { open(Screen.Editor(event.eventId, event.begin, event.end, session = System.nanoTime())) },
                            onDuplicate = { open(Screen.Editor(event.eventId, event.begin, event.end, copy = true, session = System.nanoTime())) },
                            onDelete = { delete ->
                                back()
                                // Out of every view at once; the provider hears of it when the snackbar has gone (SPEC §2.5)
                                app.deletes.delete(delete)
                                messages.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    // Carried out elsewhere meanwhile (the app left the screen, another delete): Undo has nothing left to offer
                                    val watch = launch {
                                        app.deletes.waiting.first { it != delete }
                                        snackbars.currentSnackbarData?.dismiss()
                                    }
                                    val result = snackbars.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Short)
                                    watch.cancel()
                                    if (app.deletes.waiting.value == delete) {
                                        if (result == SnackbarResult.ActionPerformed) app.deletes.undo() else app.deletes.commit()
                                    }
                                }
                            },
                        )
                    }
                    entry<Screen.Editor> { editor ->
                        EditorScreen(
                            editor, snackbars,
                            onClose = back,
                            onSaved = { edited ->
                                back()
                                // The page of an event that was changed shows an occurrence that may be no more
                                if (edited) while (stack.lastOrNull() is Screen.Event) back()
                                messages.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    snackbars.showSnackbar(saved, duration = SnackbarDuration.Short)
                                }
                            },
                            onShowError = { error = it },
                        )
                    }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenCalendars = { open(Screen.Calendars) },
                            onOpenView = { open(Screen.SettingsView) },
                            onOpenEvents = { open(Screen.SettingsEvents) },
                            onOpenReminders = { open(Screen.SettingsReminders) },
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.Calendars> { CalendarsScreen(onBack = back) }
                    entry<Screen.SettingsView> { ViewSettingsScreen(onBack = back) }
                    entry<Screen.SettingsEvents> { EventsSettingsScreen(onBack = back) }
                    entry<Screen.SettingsReminders> { RemindersSettingsScreen(onBack = back) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(
                            onBack = back,
                            onOpenWhatsNew = { open(Screen.SettingsWhatsNew) },
                            onOpenLicense = { open(Screen.SettingsLicense) },
                            onOpenLicenses = { open(Screen.SettingsLicenses) },
                        )
                    }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                },
            )
            // The first start (SPEC §15.1): the page asking for access lies over the calendar until it is answered
            if (welcome) {
                AccessScreen(onAnswer = {
                    CalendarAccess.markWelcomeSeen(appContext)
                    welcome = false
                    // With access the reminders have something to read
                    app.reminders.request()
                })
            } else if (askReminders) {
                RemindersScreen(onAnswer = {
                    NotificationAccess.markPageSeen(appContext)
                    askReminders = false
                })
            }
            error?.let { ErrorDetailsDialog(it, onDismiss = { error = null }) }
            // One host for every page, above the system's navigation area
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
