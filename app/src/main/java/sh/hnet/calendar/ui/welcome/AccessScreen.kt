package sh.hnet.calendar.ui.welcome

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.components.AppLogo
import sh.hnet.calendar.ui.components.FormPage
import sh.hnet.calendar.ui.components.rememberCalendarAccessAsk

/**
 * The first start (SPEC §15.1, board 27), in the frame of Music's and News's welcome pages: the
 * app's mark, what the access is for and who syncs, and one button that opens the system's
 * question. Whatever the answer, the page does not come back: a refusal is shown by the main screen.
 */
@Composable
fun AccessScreen(onAnswer: () -> Unit) {
    val ask = rememberCalendarAccessAsk(onAnswer)
    FormPage(title = null, confirmLabel = stringResource(R.string.button_allow_access), onConfirm = ask, contentSpacing = 0.dp) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(stringResource(R.string.title_wizard_access), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = TITLE_INSET).semantics { heading() })
        for (text in listOf(R.string.msg_wizard_access_1, R.string.msg_wizard_access_2)) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp),
            )
        }
    }
}

/** Start inset of the title and texts, which the logo lines up with. */
private val TITLE_INSET = 8.dp
