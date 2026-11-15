package sh.hnet.calendar.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/** What is remembered about the first run and the questions asked in it; kept through Reset and out of the backup. */
private fun Context.firstRun() = getSharedPreferences("first_run", Context.MODE_PRIVATE)

object CalendarAccess {
    private const val WELCOME_SEEN = "welcome_seen"
    private const val REQUESTED = "calendar_requested"

    /** Whether the first-run page that asks for access was answered. */
    fun welcomeSeen(context: Context): Boolean = context.firstRun().getBoolean(WELCOME_SEEN, false)

    fun markWelcomeSeen(context: Context) = context.firstRun().edit { putBoolean(WELCOME_SEEN, true) }

    /**
     * Whether the system would still show its permission dialog: before the first request, and
     * after one refusal. After the second the system answers for the user, and only its settings
     * page helps (as Clock's notification question).
     */
    fun mayAsk(context: Context, activity: Activity?): Boolean =
        !context.firstRun().getBoolean(REQUESTED, false) || activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) == true

    internal fun markRequested(context: Context) = context.firstRun().edit { putBoolean(REQUESTED, true) }

    /** The app's page in the system settings, where Permissions › Calendar is. */
    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Asks for the calendar permission, reading and writing in one system dialog. [onAnswer] runs
 * after the user answered, whatever the answer.
 */
@Composable
fun rememberCalendarAccessAsk(onAnswer: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAnswer() }
    return {
        CalendarAccess.markRequested(context)
        launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }
}
