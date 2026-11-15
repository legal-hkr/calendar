package sh.hnet.calendar.ui.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import sh.hnet.calendar.R
import sh.hnet.calendar.model.AgendaDay
import sh.hnet.calendar.model.AgendaRow
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.RowTime
import sh.hnet.calendar.ui.theme.dimmedFill
import sh.hnet.calendar.ui.theme.fill
import sh.hnet.calendar.ui.theme.outline

/** What the list is made of, flat: a day's header, then its rows. */
private sealed interface AgendaItem {
    val day: Int
    val key: String

    data class Header(override val day: Int) : AgendaItem {
        override val key get() = "day/$day"
    }

    /** @param first, last The row's place in its day's group, which gives it its corners */
    data class Event(val row: AgendaRow, val first: Boolean, val last: Boolean) : AgendaItem {
        override val day get() = row.day
        override val key get() = row.key
    }

    /** Today without events. */
    data class Nothing(override val day: Int) : AgendaItem {
        override val key get() = "nothing/$day"
    }
}

private fun items(days: List<AgendaDay>): List<AgendaItem> = buildList {
    for (day in days) {
        add(AgendaItem.Header(day.day))
        if (day.rows.isEmpty()) add(AgendaItem.Nothing(day.day))
        day.rows.forEachIndexed { index, row -> add(AgendaItem.Event(row, index == 0, index == day.rows.lastIndex)) }
    }
}

/** Dates and times in the app's language and the system's clock (SPEC §2.8). */
private class AgendaFormats(val locale: Locale, hours24: Boolean, val zone: ZoneId) {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (hours24) "Hm" else "hm"), locale)
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    val dayWithYear: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMdy"), locale)
    val month: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "LLLLy"), locale)

    fun time(millis: Long): String = time.format(Instant.ofEpochMilli(millis).atZone(zone))
}

/**
 * Agenda (SPEC §8, board 05): days with their events as segmented groups, today first on opening,
 * endless both ways – [onNearEnd] asks for more days (true: later ones) when the list nears an
 * end. [onPeriod] gets the month and year the list is at, for the top bar.
 */
@Composable
fun AgendaList(state: CalendarState.Ready, onPeriod: (String) -> Unit, onNearEnd: (later: Boolean) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val hours24 = DateFormat.is24HourFormat(context)
    val formats = remember(locale, hours24, state.zone) { AgendaFormats(locale, hours24, state.zone) }
    val items = remember(state.days) { items(state.days) }
    val list = rememberLazyListState()

    // Opens with today at the top; later the list keeps its place by the rows' keys
    var placed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!placed) {
            val today = items.indexOfFirst { it is AgendaItem.Header && it.day == state.today }
            if (today > 0) list.scrollToItem(today)
            placed = true
        }
    }

    val currentItems by rememberUpdatedState(items)
    val currentOnPeriod by rememberUpdatedState(onPeriod)
    LaunchedEffect(list, formats) {
        snapshotFlow { currentItems.getOrNull(list.firstVisibleItemIndex)?.day }.distinctUntilChanged().collect { day ->
            if (day != null) currentOnPeriod(formats.month.format(JulianDay.toDate(day)).replaceFirstChar { it.titlecase(formats.locale) })
        }
    }
    // Near an end: more days. The count is part of the value, so a load that changed little asks again
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(list, placed) {
        if (!placed) return@LaunchedEffect
        snapshotFlow { (list.firstVisibleItemIndex < NEAR) to currentItems.size }.distinctUntilChanged().filter { it.first }.collect { currentOnNearEnd(false) }
    }
    LaunchedEffect(list, placed) {
        if (!placed) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            (lastVisible >= currentItems.size - NEAR) to currentItems.size
        }.distinctUntilChanged().filter { it.first }.collect { currentOnNearEnd(true) }
    }

    val thisYear = remember(state.today) { JulianDay.toDate(state.today).year }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            // A tablet keeps the list as wide as a page (SPEC §19)
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            items(items.size, key = { items[it].key }, contentType = { items[it]::class }) { index ->
                when (val item = items[index]) {
                    is AgendaItem.Header -> {
                        val date = JulianDay.toDate(item.day)
                        val name = (if (date.year == thisYear) formats.day else formats.dayWithYear).format(date)
                        val text = when (item.day) {
                            state.today -> stringResource(R.string.label_day_header_today, name)
                            state.today + 1 -> stringResource(R.string.label_day_header_tomorrow, name)
                            else -> name.replaceFirstChar { it.titlecase(formats.locale) }
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            // 16 dp between day groups; the first header sits right under the top bar
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = if (index == 0) 4.dp else 16.dp, bottom = 8.dp).semantics { heading() },
                        )
                    }
                    is AgendaItem.Nothing -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(first = true, last = true), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(stringResource(R.string.label_nothing_planned), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is AgendaItem.Event -> EventRow(
                        row = item.row,
                        color = state.calendars[item.row.occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE,
                        past = item.row.occurrence.isPast(state.now, state.today),
                        formats = formats,
                        hours24 = hours24,
                        shape = groupShape(item.first, item.last),
                        modifier = Modifier.padding(top = if (item.first) 0.dp else 2.dp),
                    )
                }
            }
        }
    }
}

/** Segmented rows: 20 dp at the group's outer corners, 6 dp between rows (SPEC §8.3). */
private fun groupShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) 20.dp else 6.dp
    val bottom = if (last) 20.dp else 6.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * An event's row (SPEC §8.3): the time column, the bar in the calendar's color, the title and one
 * line under it – the state when it has one to tell, else which day of several, else the place.
 */
@Composable
private fun EventRow(row: AgendaRow, color: CalendarColor, past: Boolean, formats: AgendaFormats, hours24: Boolean, shape: Shape, modifier: Modifier = Modifier) {
    val occurrence = row.occurrence
    val supporting = when {
        occurrence.canceled -> stringResource(R.string.label_state_canceled)
        occurrence.tentative -> stringResource(R.string.label_state_tentative)
        row.multiDay -> stringResource(R.string.label_day_of_days, row.dayNumber, row.dayCount)
        else -> occurrence.location.lineSequence().firstOrNull().orEmpty()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).height(IntrinsicSize.Min).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (row.time) {
                    RowTime.ALL_DAY -> stringResource(R.string.label_all_day)
                    RowTime.START_END -> formats.time(occurrence.begin) + "\n" + formats.time(occurrence.end)
                    RowTime.START -> formats.time(occurrence.begin)
                    RowTime.END -> stringResource(R.string.label_until_time, formats.time(occurrence.end))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                // A 12-hour time with its marker needs more room than "09:30"
                modifier = Modifier.width(if (hours24) 40.dp else 60.dp),
            )
            ColorBar(color, past = past, outlined = occurrence.tentative, modifier = Modifier.padding(start = 10.dp, end = 12.dp).width(4.dp).fillMaxHeight())
            Column(Modifier.weight(1f)) {
                Text(
                    occurrence.title.ifEmpty { stringResource(R.string.label_no_title) },
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (occurrence.canceled) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting.isNotEmpty()) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The 4 dp bar of a row (SPEC §4.3): filled; dimmed for an event that is over; a dashed outline for a tentative one. */
@Composable
private fun ColorBar(color: CalendarColor, past: Boolean, outlined: Boolean, modifier: Modifier = Modifier) {
    val fill = if (past) color.dimmedFill() else color.fill()
    val outline = color.outline()
    Box(
        if (outlined) {
            modifier.drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            }
        } else {
            modifier.background(fill, RoundedCornerShape(2.dp))
        },
    )
}

/** How close to an end, in items, the list asks for more days. */
private const val NEAR = 6
