package sh.hnet.calendar.ui.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlinx.coroutines.flow.drop
import sh.hnet.calendar.model.MonthWeek
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.DayOfWeek
import kotlin.math.roundToInt
import sh.hnet.calendar.R
import sh.hnet.calendar.model.Agenda
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Month
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.TileLines
import sh.hnet.calendar.ui.theme.dimmedFill
import sh.hnet.calendar.ui.theme.fill
import sh.hnet.calendar.ui.theme.onFill
import sh.hnet.calendar.ui.theme.outline
import sh.hnet.calendar.ui.theme.text

/**
 * Where Month's grid is scrolled to. The weeks lie one under another without end and each is there
 * once – also the week two months share, which pages of months showed twice while they moved (the
 * owner, 16 Nov 2026). The grid comes to rest with a month's first week at its top; a swipe takes
 * it one month on.
 */
@Stable
class MonthScroll(month: Int, private val weekStart: DayOfWeek) {
    /** The Julian day at the grid's top edge; between two weeks while it moves. */
    private val topState = mutableDoubleStateOf(start(month.coerceIn(FIRST, LAST)).toDouble())
    val top: Double get() = topState.doubleValue

    /** The month whose first week is nearest to the top: the one the title names and the days are counted to. */
    val month: Int by derivedStateOf { nearest(topState.doubleValue) }

    /** The month the grid came to rest at. */
    var settled by mutableIntStateOf(month.coerceIn(FIRST, LAST))
        private set

    /** A row's height and the speed from which a release counts as a swipe, in pixels; the layout tells them. */
    var rowPx = 1f
    var swipe = 0f

    /** The Julian day the grid starts with when it rests at the month [number]. */
    fun start(number: Int): Int = Month.gridStart(number, weekStart)

    /** The month with its first week at or above [top]. */
    private fun floor(top: Double): Int {
        // The last day of the row that holds the top edge is in that month
        val anchor = start(FIRST)
        val row = anchor + Math.floorDiv(kotlin.math.floor(top).toInt() - anchor, 7) * 7
        return Month.number(JulianDay.toDate(row + 6)).coerceIn(FIRST, LAST)
    }

    private fun nearest(top: Double): Int {
        val below = floor(top)
        if (below >= LAST) return LAST
        return if (top - start(below) < start(below + 1) - top) below else below + 1
    }

    val scrollable = ScrollableState { delta ->
        val before = topState.doubleValue
        val next = (before + delta / rowPx * 7.0).coerceIn(start(FIRST).toDouble(), start(LAST).toDouble())
        topState.doubleValue = next
        ((next - before) / 7.0 * rowPx).toFloat()
    }

    /** A release: on with a swipe to the month ahead or back, else to the nearest. */
    val fling = object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            val below = floor(top)
            val target = when {
                initialVelocity > swipe -> (below + 1).coerceAtMost(LAST)
                // At rest on a month's first week a swipe back goes to the month before
                initialVelocity < -swipe -> if (top > start(below)) below else (below - 1).coerceAtLeast(FIRST)
                else -> nearest(top)
            }
            // One month a swipe, as a pager turns one page: however far the finger went
            glide(target.coerceIn(settled - 1, settled + 1).coerceIn(FIRST, LAST), initialVelocity)
            return 0f
        }
    }

    private suspend fun ScrollScope.glide(target: Int, velocity: Float) {
        val distance = ((start(target) - top) / 7.0 * rowPx).toFloat()
        var done = 0f
        // A spring thrown at its target faster than this runs past it; the grid should arrive, not bounce
        val limit = kotlin.math.sqrt(Spring.StiffnessMediumLow) * kotlin.math.abs(distance)
        animate(0f, distance, velocity.coerceIn(-limit, limit), spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
            scrollBy(value - done)
            done = value
        }
        // Exactly on the week, whatever the steps added up to
        topState.doubleValue = start(target).toDouble()
        settled = target
    }

    /** Scrolls to the month [number], as a swipe would. */
    suspend fun animateTo(number: Int) = scrollable.scroll { glide(number.coerceIn(FIRST, LAST), 0f) }

    /** Shows the month [number] at once. */
    fun jumpTo(number: Int) {
        val target = number.coerceIn(FIRST, LAST)
        topState.doubleValue = start(target).toDouble()
        settled = target
    }

    companion object {
        /** The months of the years the date picker offers. */
        const val FIRST = 1900 * 12
        const val LAST = 2100 * 12 + 11

        fun monthOf(day: Int): Int = Month.number(JulianDay.toDate(day)).coerceIn(FIRST, LAST)

        /** The day a month is entered on: today in the current month, else its first day. */
        fun entryDay(number: Int, today: Int): Int = if (monthOf(today) == number) today else JulianDay.of(Month.firstDate(number))
    }
}

/** A calendar color's values in this theme, read once for drawing. */
internal data class TileColors(val fill: Color, val on: Color, val text: Color, val outline: Color, val dimmed: Color)

/** The same map until a color changes (the theme, the wallpaper's colors), so the pages are not drawn anew for nothing. */
@Composable
internal fun tilePalette(): Map<CalendarColor, TileColors> {
    val colors = CalendarColor.entries.associateWith { TileColors(it.fill(), it.onFill(), it.text(), it.outline(), it.dimmedFill()) }
    return remember(colors) { colors }
}

/**
 * Month (SPEC §6, boards 01 and 02): the weeks scrolled up and down (the owner's choice over the SPEC's sideways swipe),
 * a month at a time. With [eventList] off the
 * full grid with tiles, where a tap on a day calls [onOpenDay]; with it on the compact grid with
 * marks, where a tap chooses the day ([onFocus]) whose events are listed below. [focus] is the day
 * the screen is at: the grid follows it, and scrolling to another month moves it there.
 */
@Composable
fun MonthView(
    state: CalendarState.Ready,
    formats: CalendarFormats,
    weekStart: DayOfWeek,
    scroll: MonthScroll,
    eventList: Boolean,
    focus: Int,
    onFocus: (Int) -> Unit,
    onOpenDay: (Int) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    viewModel: RangeViewModel = viewModel(key = "month"),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val currentFocus by rememberUpdatedState(focus)
    val currentOnFocus by rememberUpdatedState(onFocus)
    val today by rememberUpdatedState(state.today)
    // Today, Go to date, a day chosen in another month: the month next door is scrolled to, any other shown at once
    LaunchedEffect(focus, scroll) {
        val month = MonthScroll.monthOf(focus)
        if (scroll.settled != month) {
            if (kotlin.math.abs(month - scroll.settled) == 1) scroll.animateTo(month) else scroll.jumpTo(month)
        }
    }
    // A swipe puts the screen on today in the current month, else on the month's first day
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.settled }.drop(1).collect { month ->
            if (MonthScroll.monthOf(currentFocus) != month) currentOnFocus(MonthScroll.entryDay(month, today))
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.month }.collect { month ->
            // The month and both its neighbors, whichever day their weeks start with
            viewModel.show(JulianDay.of(Month.firstDate(month - 1)) - MONTH_LEAD, JulianDay.of(Month.firstDate(month + 1)) + MONTH_SPAN)
        }
    }

    val palette = tilePalette()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(7) { column ->
                Text(
                    formats.weekday(weekStart.plus(column.toLong())),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 0 is the full grid, 1 the compact one with the list; turning "Event list" on or off is one motion between them:
        // the rows change their height, tiles fade into marks and the list comes up from under the grid
        val list = animateFloatAsState(if (eventList) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "eventList")
        val showFull by remember { derivedStateOf { list.value < 1f } }
        val showList by remember { derivedStateOf { list.value > 0f } }
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val compactHeight = COMPACT_ROW * Month.WEEKS
        val swipe = with(LocalDensity.current) { SWIPE_SPEED.toPx() }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // The full grid shares all the height above the system's navigation area; the compact one has rows of 48 dp
            val fullHeight = (maxHeight - navigationBar).coerceAtLeast(compactHeight)
            val fullSize = with(LocalDensity.current) { Size(maxWidth.toPx(), fullHeight.toPx()) }
            Column(Modifier.fillMaxSize()) {
                // The weeks of the month the grid is at and of both its neighbors are ready before a swipe starts
                val month = scroll.month
                val weeks = remember(month, scroll) { (scroll.start(month - 1) until scroll.start(month + 1) + Month.WEEKS * 7 step 7).toList() }
                val shownMonth = remember(scroll) { { scroll.month } }
                Layout(
                    content = {
                        for (weekFirst in weeks) {
                            key(weekFirst) {
                                val week = remember(events, weekFirst) { Month.week(if (events.covers(weekFirst, weekFirst + 6)) events.occurrences else emptyList(), weekFirst) }
                                Box {
                                    if (showFull) FullWeek(week, shownMonth, state, palette, formats, fullSize, Modifier.graphicsLayer { alpha = 1f - list.value.coerceIn(0f, 1f) })
                                    if (showList) CompactWeek(week, shownMonth, focus, state, palette, Modifier.graphicsLayer { alpha = list.value.coerceIn(0f, 1f) })
                                    DayTargets(weekFirst, formats, if (eventList) onFocus else onOpenDay)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clipToBounds().scrollable(scroll.scrollable, Orientation.Vertical, reverseDirection = true, flingBehavior = scroll.fling),
                ) { measurables, constraints ->
                    // The height is read while laying out and the place of the rows while placing them, so neither motion composes anything anew
                    val height = lerp(fullHeight.toPx(), compactHeight.toPx(), list.value.coerceIn(0f, 1f)).roundToInt().coerceAtMost(constraints.maxHeight)
                    val row = height / Month.WEEKS.toFloat()
                    scroll.rowPx = row.coerceAtLeast(1f)
                    scroll.swipe = swipe
                    val rowHeight = kotlin.math.ceil(row).toInt()
                    val placeables = measurables.map { it.measure(Constraints.fixed(constraints.maxWidth, rowHeight)) }
                    layout(constraints.maxWidth, height) {
                        val top = scroll.top
                        placeables.forEachIndexed { index, placeable ->
                            val y = ((weeks[index] - top) / 7.0 * row).roundToInt()
                            // A row out of sight is not drawn
                            if (y > -rowHeight && y < height) placeable.placeWithLayer(0, y)
                        }
                    }
                }
                if (showList) {
                    Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = list.value.coerceIn(0f, 1f) }) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DayList(focus, events, state, formats, onOpenEvent)
                    }
                }
            }
        }
    }
}

/** A week's days as targets over the drawn row (SPEC §6.1, §20): the whole cell is one. */
@Composable
private fun DayTargets(firstDay: Int, formats: CalendarFormats, onDay: (Int) -> Unit) {
    val names = remember(firstDay, formats) { List(7) { formats.capitalize(formats.day(JulianDay.toDate(firstDay + it), Int.MIN_VALUE)) } }
    Row(Modifier.fillMaxSize()) {
        repeat(7) { column ->
            Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) { onDay(firstDay + column) }.semantics { contentDescription = names[column] })
        }
    }
}

/**
 * A week of the full grid (SPEC §6.1) with the tiles of §5, drawn in one pass: the lines a tile
 * gets depend on how its title wraps, so the text is measured here anyway. The row is laid out for
 * a sixth of [fullSize], the grid's size at rest, and cut where the row ends – so while the grid
 * changes into the compact one its rows only move closer and are cut short, and nothing is laid
 * out anew. [shownMonth] is read while drawing: the days of other months are paler.
 */
@Composable
private fun FullWeek(week: MonthWeek, shownMonth: () -> Int, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, formats: CalendarFormats, fullSize: Size, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val scheme = MaterialTheme.colorScheme
    val noTitle = stringResource(R.string.label_no_title)
    val moreText = stringResource(R.string.label_more_events)
    val numberStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
    val titleStyle = tileTitleStyle()
    val timeStyle = titleStyle.copy(fontWeight = FontWeight.Normal, hyphens = Hyphens.None)
    val ops = remember(week, state, palette, formats, fullSize, density, scheme, numberStyle, titleStyle, noTitle, moreText) {
        with(density) {
            val painter = TilePainter(measurer, palette, state.calendars, state.now, state.today, formats, noTitle, titleStyle, timeStyle, this)
            val rowHeight = fullSize.height / Month.WEEKS
            val columnWidth = fullSize.width / 7
            val line = painter.line
            val gap = 2.dp.toPx()
            val oneLine = TILE_PADDING_VERTICAL.toPx() * 2 + line
            val top = 30.dp.toPx()
            val side = 1.dp.toPx()
            val room = rowHeight - top - gap
            // How many one-line tiles a cell holds: four at the board's size (SPEC §6.1)
            val slots = ((room + gap) / (oneLine + gap)).toInt().coerceAtLeast(1)
            val tileWidth = columnWidth - 2 * side
            val titleWidth = (tileWidth - TILE_PADDING_HORIZONTAL.toPx() * 2).toInt().coerceAtLeast(1)
            val circle = 12.dp.toPx()
            val stroke = 1.dp.toPx()

            val ops = ArrayList<DrawScope.() -> Unit>()
            ops += { drawLine(scheme.outlineVariant, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke) }
            for (column in 0 until 7) {
                val day = week.firstDay + column
                val date = JulianDay.toDate(day)
                val month = Month.number(date)
                val isToday = day == state.today
                val center = Offset((column + 0.5f) * columnWidth, 3.dp.toPx() + circle)
                val text = measurer.measure(date.dayOfMonth.toString(), if (isToday) numberStyle.copy(fontWeight = FontWeight.Medium) else numberStyle, maxLines = 1)
                ops += {
                    if (isToday) drawCircle(scheme.primary, circle, center)
                    val color = if (isToday) scheme.onPrimary else if (month != shownMonth()) scheme.outline else scheme.onSurface
                    drawText(text, color, Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
                }
            }
            val fill = Month.fill(week, slots)
            for (bar in fill.bars) {
                val left = bar.firstColumn * columnWidth + if (bar.startsBefore) 0f else side
                val right = (bar.lastColumn + 1) * columnWidth - if (bar.endsAfter) 0f else side
                val title = painter.title(bar.occurrence, (right - left - TILE_PADDING_HORIZONTAL.toPx() * 2).toInt().coerceAtLeast(1), wrap = false)
                ops += painter.tile(bar.occurrence, left, top + bar.lane * (oneLine + gap), right - left, oneLine, TileLines(false, 1), title, bar.startsBefore, bar.endsAfter)
            }
            for (column in 0 until 7) {
                val cell = fill.cells[column]
                val own = week.days[column].take(cell.shown)
                val left = column * columnWidth + side
                var y = top + cell.base * (oneLine + gap)
                // The lines left once every tile has one (SPEC §6.1): first for the times, then for the titles
                val spare = if (cell.more == 0 && own.isNotEmpty()) ((room - cell.base * (oneLine + gap) - (own.size * oneLine + (own.size - 1) * gap)) / line).toInt() else 0
                val wrapped = if (spare > 0) own.map { painter.title(it, titleWidth, wrap = true) } else emptyList()
                val lines = if (spare > 0) Month.lines(spare, own.map { !it.allDay }, wrapped.map { it.lineCount }) else own.map { TileLines(false, 1) }
                own.forEachIndexed { tile, occurrence ->
                    val height = TILE_PADDING_VERTICAL.toPx() * 2 + lines[tile].count * line
                    // A title on one line is clipped where the tile ends, not broken at a syllable
                    val title = if (lines[tile].title > 1) wrapped[tile] else painter.title(occurrence, titleWidth, wrap = false)
                    ops += painter.tile(occurrence, left, y, tileWidth, height, lines[tile], title)
                    y += height + gap
                }
                if (cell.more > 0) {
                    val more = measurer.measure(moreText.format(cell.more), timeStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                    val moreTop = top + (slots - 1) * (oneLine + gap) + TILE_PADDING_VERTICAL.toPx()
                    ops += { drawText(more, scheme.onSurfaceVariant, Offset(left + TILE_PADDING_HORIZONTAL.toPx(), moreTop)) }
                }
            }
            ops
        }
    }
    Spacer(modifier.fillMaxSize().drawBehind { clipRect(0f, 0f, size.width, size.height) { for (op in ops) op() } })
}

/**
 * A week of the compact grid (SPEC §6.3): the day's number – today and the chosen day in circles –, a dot
 * per calendar under it, and a thin bar for an event of several days.
 */
@Composable
private fun CompactWeek(week: MonthWeek, shownMonth: () -> Int, focus: Int, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scheme = MaterialTheme.colorScheme
    val numberStyle = MaterialTheme.typography.bodyMedium
    val plusStyle = tileTitleStyle()
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val ops = ArrayList<DrawScope.() -> Unit>()
            val number = shownMonth()
            val columnWidth = size.width / 7
            val circle = 14.dp.toPx()
            val dot = 6.dp.toPx()
            val gap = 2.dp.toPx()
            val plus = measurer.measure("+", plusStyle, maxLines = 1)
            fun colors(occurrence: Occurrence) = palette.getValue(state.calendars[occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE)

            run {
                val rowTop = 0f
                for (column in 0 until 7) {
                    val day = week.firstDay + column
                    val date = JulianDay.toDate(day)
                    val isToday = day == state.today
                    val chosen = day == focus
                    val center = Offset((column + 0.5f) * columnWidth, rowTop + 2.dp.toPx() + circle)
                    val color = when {
                        isToday -> scheme.onPrimary
                        chosen -> scheme.onSecondaryContainer
                        Month.number(date) != number -> scheme.outline
                        else -> scheme.onSurface
                    }
                    val text = measurer.measure(date.dayOfMonth.toString(), if (isToday) numberStyle.copy(fontWeight = FontWeight.Medium) else numberStyle, maxLines = 1)
                    val marks = Month.marks(week, column, state.now, state.today)
                    val dots = marks.dots.map { mark -> palette.getValue(state.calendars[mark.calendarId]?.color ?: CalendarColor.GRAPHITE).let { if (mark.past) it.dimmed else it.fill } }
                    val width = dots.size * dot + (dots.size - 1).coerceAtLeast(0) * gap + if (marks.more) plus.size.width + (if (dots.isEmpty()) 0f else gap) else 0f
                    val marksTop = rowTop + 32.dp.toPx()
                    ops += {
                        if (isToday) drawCircle(scheme.primary, circle, center) else if (chosen) drawCircle(scheme.secondaryContainer, circle, center)
                        drawText(text, color, Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
                        var x = center.x - width / 2
                        for (fill in dots) {
                            drawCircle(fill, dot / 2, Offset(x + dot / 2, marksTop + dot / 2))
                            x += dot + gap
                        }
                        if (marks.more) drawText(plus, scheme.onSurfaceVariant, Offset(x, marksTop + dot / 2 - plus.size.height / 2f))
                    }
                }
                // One line of bars fits under the dots; longer events below it are dots (Month.marks)
                for (bar in week.bars) {
                    if (bar.lane != 0) continue
                    val inset = 6.dp.toPx()
                    val left = bar.firstColumn * columnWidth + if (bar.startsBefore) 0f else inset
                    val right = (bar.lastColumn + 1) * columnWidth - if (bar.endsAfter) 0f else inset
                    val fill = colors(bar.occurrence).let { if (bar.occurrence.isPast(state.now, state.today) || bar.occurrence.canceled) it.dimmed else it.fill }
                    ops += { drawRoundRect(fill, Offset(left, rowTop + 41.dp.toPx()), Size(right - left, 4.dp.toPx()), CornerRadius(2.dp.toPx())) }
                }
            }
            onDrawBehind { for (op in ops) op() }
        },
    )
}

/** The chosen day under the compact grid (SPEC §6.3): its header and rows as in Agenda. The list scrolls, the grid stays. */
@Composable
private fun DayList(day: Int, events: LoadedEvents, state: CalendarState.Ready, formats: CalendarFormats, onOpenEvent: (Occurrence) -> Unit) {
    val loaded = events.covers(day, day)
    val rows = remember(events, day, state.today) {
        if (loaded) Agenda.build(events.occurrences, day, day, state.today).firstOrNull()?.rows.orEmpty() else emptyList()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Another day starts at its top
        key(day) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                item(key = "header", contentType = "header") { DayHeader(day, state.today, formats) }
                if (rows.isEmpty()) {
                    if (loaded) item(key = "nothing", contentType = "nothing") { NothingPlanned() }
                } else {
                    itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "event" }) { index, row ->
                        EventRow(
                            row = row,
                            color = state.calendars[row.occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE,
                            past = row.occurrence.isPast(state.now, state.today),
                            formats = formats,
                            shape = groupShape(index == 0, index == rows.lastIndex),
                            onClick = { onOpenEvent(row.occurrence) },
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The tile's text (SPEC §5.1): 11 sp on lines of 13 sp, the title in medium weight, wrapped with the language's hyphenation. */
@Composable
internal fun tileTitleStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 11.sp,
    lineHeight = 13.sp,
    letterSpacing = 0.sp,
    fontWeight = FontWeight.Medium,
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Paragraph,
)

/** A month's first week starts up to six days before its first day; six weeks are in sight. */
private const val MONTH_LEAD = 6
private const val MONTH_SPAN = Month.WEEKS * 7 - 1

private val TILE_PADDING_VERTICAL = 1.dp
private val TILE_PADDING_HORIZONTAL = 3.dp
private val COMPACT_ROW = 48.dp
/** The speed from which a release counts as a swipe to the next month, as a pager's. */
private val SWIPE_SPEED = 400.dp

/**
 * Draws the shared event tile (SPEC §5): radius 4 dp, the start time on a line of its own when it
 * was given one, then the title on its lines, clipped without an ellipsis; the states of §5.3.
 */
private class TilePainter(
    private val measurer: TextMeasurer,
    private val palette: Map<CalendarColor, TileColors>,
    private val calendars: Map<Long, CalendarInfo>,
    private val now: Long,
    private val today: Int,
    private val formats: CalendarFormats,
    private val noTitle: String,
    private val titleStyle: TextStyle,
    private val timeStyle: TextStyle,
    density: Density,
) {
    /** The height of a line of tile text, as the text engine lays it out. */
    val line: Float = measurer.measure("0", titleStyle, maxLines = 1).size.height.toFloat()
    private val radius = with(density) { CornerRadius(4.dp.toPx()) }
    private val paddingHorizontal = with(density) { TILE_PADDING_HORIZONTAL.toPx() }
    private val paddingVertical = with(density) { TILE_PADDING_VERTICAL.toPx() }
    private val stroke = with(density) { 1.dp.toPx() }
    private val dashes = with(density) { PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) }

    /** The title laid out in [width]: over up to three lines for a tile, on one line for a bar. */
    fun title(occurrence: Occurrence, width: Int, wrap: Boolean): TextLayoutResult = measurer.measure(
        occurrence.title.ifEmpty { noTitle },
        titleStyle,
        overflow = TextOverflow.Clip,
        softWrap = wrap,
        maxLines = if (wrap) Month.MAX_TITLE_LINES else 1,
        constraints = Constraints(maxWidth = width),
    )

    fun tile(occurrence: Occurrence, left: Float, top: Float, width: Float, height: Float, lines: TileLines, title: TextLayoutResult, flatStart: Boolean = false, flatEnd: Boolean = false): DrawScope.() -> Unit {
        val colors = palette.getValue(calendars[occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE)
        val canceled = occurrence.canceled
        val outlined = occurrence.tentative && !canceled
        val dimmed = canceled || occurrence.isPast(now, today)
        val ink = if (outlined) colors.outline else if (dimmed) colors.text else colors.on
        // The outline lies inside the tile's bounds
        val inset = if (outlined) stroke / 2 else 0f
        val start = if (flatStart) CornerRadius.Zero else radius
        val end = if (flatEnd) CornerRadius.Zero else radius
        val path = Path().apply { addRoundRect(RoundRect(left + inset, top + inset, left + width - inset, top + height - inset, start, end, end, start)) }
        val time = if (lines.time) measurer.measure(formats.tileTime(occurrence.begin), timeStyle, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1) else null
        val textLeft = left + paddingHorizontal
        val textRight = left + width - paddingHorizontal
        val titleTop = top + paddingVertical + if (time != null) line else 0f
        val titleBottom = titleTop + title.getLineBottom(minOf(lines.title, title.lineCount) - 1)
        return {
            if (outlined) drawPath(path, colors.outline, style = Stroke(stroke, pathEffect = dashes)) else drawPath(path, if (dimmed) colors.dimmed else colors.fill)
            if (time != null) clipRect(textLeft, top, textRight, top + height) { drawText(time, ink, Offset(textLeft, top + paddingVertical)) }
            clipRect(textLeft, titleTop, textRight, minOf(titleBottom, top + height)) {
                drawText(title, ink, Offset(textLeft, titleTop), textDecoration = if (canceled) TextDecoration.LineThrough else null)
            }
        }
    }
}
