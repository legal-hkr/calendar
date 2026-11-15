@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.calendar.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.ui.theme.fill
import sh.hnet.calendar.ui.theme.onFill

/** The name of a palette color (SPEC §4.1). */
fun CalendarColor.nameRes(): Int = when (this) {
    CalendarColor.RED -> R.string.color_red
    CalendarColor.ORANGE -> R.string.color_orange
    CalendarColor.AMBER -> R.string.color_amber
    CalendarColor.OLIVE -> R.string.color_olive
    CalendarColor.GREEN -> R.string.color_green
    CalendarColor.TEAL -> R.string.color_teal
    CalendarColor.SKY -> R.string.color_sky
    CalendarColor.BLUE -> R.string.color_blue
    CalendarColor.VIOLET -> R.string.color_violet
    CalendarColor.MAGENTA -> R.string.color_magenta
    CalendarColor.RASPBERRY -> R.string.color_raspberry
    CalendarColor.GRAPHITE -> R.string.color_graphite
}

/**
 * The color sheet (SPEC §4.6, board 11): the palette as circles with their names, four in a row,
 * three at a large font. A tap chooses, and the sheet closes.
 *
 * @param calendarName The calendar the color is for, in the title
 */
@Composable
fun ColorSheet(calendarName: String, selected: CalendarColor, onPick: (CalendarColor) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Opens whole, as the family's sheets: nothing to scroll to at half height
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val columns = if (LocalDensity.current.fontScale >= 1.3f) 3 else 4
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.title_color_of, calendarName),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp).semantics { heading() },
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (line in CalendarColor.entries.chunked(columns)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (color in line) {
                            Swatch(color, selected = color == selected, modifier = Modifier.weight(1f)) {
                                onPick(color)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            }
                        }
                        // A short last line keeps the columns of the lines above
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A color: a 48 dp circle with its name under it; the chosen one has a ring in its own color and a check. */
@Composable
private fun Swatch(color: CalendarColor, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fill = color.fill()
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The ring's room is always there, so choosing moves nothing
        Box(Modifier.size(56.dp).then(if (selected) Modifier.border(2.dp, fill, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = color.onFill())
            }
        }
        Text(
            stringResource(color.nameRes()),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
