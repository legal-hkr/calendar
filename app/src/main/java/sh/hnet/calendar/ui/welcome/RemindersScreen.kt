package sh.hnet.calendar.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.components.FormPage
import sh.hnet.calendar.ui.components.rememberNotificationAsk

/**
 * The second first-run page (SPEC §15.1, board 28): what reminders are and that they need
 * notifications; one button opens the system's question, "Not now" goes on without. Whatever the
 * answer, the page does not come back: Settings › Reminders says when reminders can't appear.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemindersScreen(onAnswer: () -> Unit) {
    val ask = rememberNotificationAsk(onAnswer)
    FormPage(title = null, confirmLabel = stringResource(R.string.button_turn_on_notifications), onConfirm = ask, contentSpacing = 0.dp) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.padding(start = TITLE_INSET, bottom = 16.dp).size(144.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(64.dp))
        }
        Text(stringResource(R.string.title_wizard_reminders), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = TITLE_INSET).semantics { heading() })
        for (text in listOf(R.string.msg_wizard_reminders_1, R.string.msg_wizard_reminders_2)) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp),
            )
        }
        TextButton(onClick = onAnswer, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_not_now)) }
    }
}

private val TITLE_INSET = 8.dp
