package sh.hnet.calendar.ui.calendar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import sh.hnet.calendar.R
import sh.hnet.calendar.ViewSettings
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.DayLayout
import sh.hnet.calendar.model.Days
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Month
import sh.hnet.calendar.model.MonthWeek
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.PlacedSegment

/** Day's pager has a page per day of the years the date picker offers. */
object DayPages {
    val FIRST = JulianDay.of(LocalDate.of(1900, 1, 1))
    val COUNT = JulianDay.of(LocalDate.of(2100, 12, 31)) - FIRST + 1

    fun of(day: Int): Int = (day - FIRST).coerceIn(0, COUNT - 1)
    fun day(page: Int): Int = FIRST + page
}

/** Week's pager has a page per week of those years, as weeks start with [weekStart]. */
class WeekPages(weekStart: DayOfWeek) {
    private val base = Days.weekFirst(DayPages.FIRST + 6, weekStart)
    val count = (DayPages.COUNT - 13) / 7

    fun of(day: Int): Int = Math.floorDiv(day - base, 7).coerceIn(0, count - 1)
    fun first(page: Int): Int = base + page * 7
}

/**
 * What Week and Day share and keep while one changes into the other: the height of an hour in dp,
 * which a pinch changes (SPEC §7.9), and how far down the hours are scrolled, in pixels – below
 * zero when the view is to choose its opening position anew (SPEC §7.10).
 */
@Stable
class HoursState(hourHeight: Float) {
    val hourHeight = mutableFloatStateOf(hourHeight)
    val scroll = mutableIntStateOf(-1)
}

/**
 * Keeps a pager and the day the screen is at together: the pager goes to the page of [focus], with
 * motion to a neighbor; after a swipe [dayOf] names the day the screen is at on the new page.
 */
@Composable
internal fun PagerFocus(pager: PagerState, focus: Int, pageOf: (Int) -> Int, dayOf: (page: Int, focus: Int) -> Int, onFocus: (Int) -> Unit) {
    val currentFocus by rememberUpdatedState(focus)
    val currentPageOf by rememberUpdatedState(pageOf)
    val currentDayOf by rememberUpdatedState(dayOf)
    val currentOnFocus by rememberUpdatedState(onFocus)
    // Today, Go to date, a day chosen on another page
    LaunchedEffect(focus) {
        val page = currentPageOf(focus)
        if (pager.targetPage != page) {
            if (abs(page - pager.currentPage) == 1) pager.animateScrollToPage(page) else pager.scrollToPage(page)
        }
    }
    // A swipe. The first value is where the pager stood before the effect above moved it
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            if (currentPageOf(currentFocus) != page) currentOnFocus(currentDayOf(page, currentFocus))
        }
    }
}

/** The minute of the day it is now, in the state's time zone. */
private fun CalendarState.Ready.nowMinute(): Int = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

/**
 * Where the hours open (SPEC §7.10): with today on screen an hour before now at the top, else the
 * first hour of the day (07:00 until Settings › View has it).
 */
@Composable
private fun PlaceHours(hours: HoursState, state: CalendarState.Ready, todayShown: Boolean) {
    val unplaced by remember { derivedStateOf { hours.scroll.intValue < 0 } }
    if (!unplaced) return
    val minutes = if (todayShown) (state.nowMinute() - 60).coerceAtLeast(0) else FIRST_HOUR * 60
    hours.scroll.intValue = with(LocalDensity.current) { (minutes / 60f * hours.hourHeight.floatValue.dp.toPx()).roundToInt() }
}

/**
 * Week (SPEC §7, board 03): a page per week, swiped sideways – the days' header, the strip of
 * all-day events and the hours of the seven days side by side. A tap on a day's header, or on a
 * "+N", calls [onOpenDay].
 */
@Composable
fun WeekView(
    state: CalendarState.Ready,
    formats: CalendarFormats,
    weeks: WeekPages,
    pager: PagerState,
    hours: HoursState,
    focus: Int,
    onFocus: (Int) -> Unit,
    onOpenDay: (Int) -> Unit,
    onZoomed: (Float) -> Unit,
    viewModel: RangeViewModel = viewModel(key = "week"),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    // A swipe keeps the day of the week
    PagerFocus(pager, focus, weeks::of, { page, day -> day + (page - weeks.of(day)) * 7 }, onFocus)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            val first = weeks.first(it)
            viewModel.show(first - 7, first + 13, margin = 14)
        }
    }
    PlaceHours(hours, state, todayShown = weeks.of(state.today) == weeks.of(focus))

    val palette = tilePalette()
    // Tiles change with the hour's height only when it has moved a whole dp
    val hourDp by remember { derivedStateOf { hours.hourHeight.floatValue.roundToInt() } }
    HorizontalPager(pager, key = { it }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
        val first = weeks.first(page)
        val loaded = events.covers(first, first + 6)
        val strip = remember(events, first) { Days.weekStrip(if (loaded) events.occurrences else emptyList(), first) }
        val days = remember(events, first, state.zone, hourDp) {
            List(7) { column ->
                val segments = if (loaded) Days.segments(events.occurrences, first + column, state.zone) else emptyList()
                Days.layout(segments, WEEK_MIN_HEIGHT * 60f / hourDp, maxColumns = 2, moreMinutes = (MORE_HEIGHT + TILE_GAP) * 60f / hourDp)
            }
        }
        Column(Modifier.fillMaxSize()) {
            DaysHeader(first, chosen = null, today = state.today, formats = formats, gutter = WEEK_GUTTER, onDay = onOpenDay)
            WeekStrip(strip, state, palette, onMore = { onOpenDay(first + it) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HoursPane(hours, current = pager.currentPage == page, onZoomed = onZoomed) {
                Row(Modifier.fillMaxWidth()) {
                    HourLabels(hours, formats, WEEK_GUTTER)
                    Row(Modifier.weight(1f).hourLines(hours)) {
                        days.forEachIndexed { column, layout ->
                            val day = first + column
                            DayColumn(hours, layout, Modifier.weight(1f).nowLine(hours, state, day == state.today)) {
                                for (tile in layout.tiles) WeekTile(tile, state, palette, formats)
                                for (mark in layout.more) MoreChip(mark.count, onClick = { onOpenDay(day) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Day (SPEC §7, board 04): the week's days on top – a tap shows another –, and a page per day,
 * swiped sideways, with its strip of all-day events and its hours. Events at one time stand side
 * by side; with more than four the hours scroll sideways.
 */
@Composable
fun DayView(
    state: CalendarState.Ready,
    formats: CalendarFormats,
    weekStart: DayOfWeek,
    pager: PagerState,
    hours: HoursState,
    focus: Int,
    onFocus: (Int) -> Unit,
    onZoomed: (Float) -> Unit,
    viewModel: RangeViewModel = viewModel(key = "day"),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    PagerFocus(pager, focus, DayPages::of, { page, _ -> DayPages.day(page) }, onFocus)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            val day = DayPages.day(it)
            viewModel.show(day - 1, day + 1, margin = 7)
        }
    }
    PlaceHours(hours, state, todayShown = focus == state.today)

    val palette = tilePalette()
    val hourDp by remember { derivedStateOf { hours.hourHeight.floatValue.roundToInt() } }
    Column(Modifier.fillMaxSize()) {
        // The header stays while the days below are swiped
        val shown = DayPages.day(pager.currentPage)
        DaysHeader(Days.weekFirst(shown, weekStart), chosen = shown, today = state.today, formats = formats, gutter = DAY_GUTTER, onDay = onFocus)
        HorizontalPager(pager, key = { it }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val day = DayPages.day(page)
            val loaded = events.covers(day, day)
            val strip = remember(events, day) { if (loaded) Days.dayStrip(events.occurrences, day) else emptyList() }
            val layout = remember(events, day, state.zone, hourDp) {
                Days.layout(if (loaded) Days.segments(events.occurrences, day, state.zone) else emptyList(), DAY_MIN_HEIGHT * 60f / hourDp)
            }
            Column(Modifier.fillMaxSize()) {
                DayStrip(day, strip, state, palette)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HoursPane(hours, current = pager.currentPage == page, onZoomed = onZoomed) {
                    Row(Modifier.fillMaxWidth()) {
                        HourLabels(hours, formats, DAY_GUTTER)
                        // The lines and the time stay in place when many events at once make the day wider than the screen
                        BoxWithConstraints(Modifier.weight(1f).hourLines(hours).nowLine(hours, state, day == state.today)) {
                            // Up to four columns share the screen; with more, four fit and the rest is scrolled to (SPEC §7.7).
                            // Half of the next one shows at the edge, so it is plain that there is more (the owner, 15 Nov 2026)
                            val width = if (layout.columns <= DAY_COLUMNS) maxWidth else maxOf(maxWidth / (DAY_COLUMNS + DAY_PEEK), DAY_MIN_COLUMN) * layout.columns
                            // Only then, so a swipe in an ordinary day goes straight to the pager
                            Box(if (width > maxWidth) Modifier.nestedScroll(remember { KeepToItself() }).horizontalScroll(rememberScrollState()) else Modifier) {
                                DayColumn(hours, layout, Modifier.width(width)) {
                                    for (tile in layout.tiles) DayTile(tile, state, palette, formats)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Between a wide day's sideways scroll and the pager of days: once a gesture has scrolled the day,
 * what the day can't use at its end is not passed on, so the pager stays put – it would otherwise
 * take the rest of the gesture and drag the whole day along. A swipe that the day can't follow at
 * all (outward from an end) still goes to the pager and changes the day.
 */
private class KeepToItself : NestedScrollConnection {
    private var scrolled = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.x != 0f) scrolled = true
        return if (scrolled) Offset(available.x, 0f) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val keep = scrolled
        scrolled = false
        return if (keep) Velocity(available.x, 0f) else Velocity.Zero
    }
}

/**
 * The days' header (SPEC §7.1): the short weekday over the date in a circle, today in primary;
 * in Day the shown day is marked. Seven columns after the hours' gutter.
 */
@Composable
private fun DaysHeader(first: Int, chosen: Int?, today: Int, formats: CalendarFormats, gutter: Dp, onDay: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = gutter)) {
        repeat(7) { column ->
            val day = first + column
            val date = JulianDay.toDate(day)
            val isToday = day == today
            Column(
                Modifier.weight(1f).height(56.dp).clickable(role = Role.Button) { onDay(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formats.weekday(date.dayOfWeek),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Box(
                    Modifier.padding(top = 2.dp).size(28.dp).background(if (isToday) scheme.primary else if (day == chosen) scheme.secondaryContainer else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isToday) FontWeight.Medium else null,
                        color = if (isToday) scheme.onPrimary else if (day == chosen) scheme.onSecondaryContainer else scheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The hours of a page: scrolled up and down together with the other pages – the page on screen
 * tells [hours] where it is, the others follow –, and pinched to change the hour's height, around
 * the place the fingers hold.
 */
@Composable
private fun HoursPane(hours: HoursState, current: Boolean, onZoomed: (Float) -> Unit, content: @Composable () -> Unit) {
    // Read without watching: every pixel scrolled would compose the page anew
    val scroll = rememberScrollState(Snapshot.withoutReadObservation { hours.scroll.intValue }.coerceAtLeast(0))
    val isCurrent by rememberUpdatedState(current)
    val currentOnZoomed by rememberUpdatedState(onZoomed)
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collect { if (isCurrent) hours.scroll.intValue = it }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { hours.scroll.intValue }.collect { if (it >= 0 && scroll.value != it) scroll.scrollTo(it) }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier.fillMaxSize()
            // Before the scrolling below sees the fingers: two of them are a pinch
            .pointerInput(hours, scroll) { detectPinch(hours, scroll) { currentOnZoomed(it) } }
            .verticalScroll(scroll)
            .padding(bottom = bottom),
    ) { content() }
}

private suspend fun PointerInputScope.detectPinch(hours: HoursState, scroll: ScrollState, onZoomed: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                val old = hours.hourHeight.floatValue
                val new = (old * zoom).coerceIn(ViewSettings.HOUR_HEIGHT_MIN, ViewSettings.HOUR_HEIGHT_MAX)
                if (new != old) {
                    zoomed = true
                    // The time under the fingers stays under them
                    val hold = event.calculateCentroid(useCurrent = true).y
                    hours.hourHeight.floatValue = new
                    scroll.dispatchRawDelta((scroll.value + hold) * (new / old) - hold - scroll.value)
                }
                for (change in event.changes) change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (zoomed) onZoomed(hours.hourHeight.floatValue)
    }
}

/** The hours' labels in the gutter (SPEC §7.3), each centered on its line. Midnight has none: it would be cut in half. */
@Composable
private fun HourLabels(hours: HoursState, formats: CalendarFormats, gutter: Dp) {
    val labels = remember(formats) { List(23) { formats.hour(it + 1) } }
    Layout(
        content = {
            for (label in labels) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            }
        },
        modifier = Modifier.width(gutter),
    ) { measurables, constraints ->
        val hour = hours.hourHeight.floatValue.dp.toPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        layout(constraints.maxWidth, (hour * 24).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place((constraints.maxWidth - placeable.width) / 2, ((index + 1) * hour - placeable.height / 2f).roundToInt())
            }
        }
    }
}

/** The hour lines (SPEC §7.3). */
@Composable
private fun Modifier.hourLines(hours: HoursState): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val hour = hours.hourHeight.floatValue.dp.toPx()
        val stroke = 1.dp.toPx()
        for (index in 1 until 24) drawLine(color, Offset(0f, index * hour), Offset(size.width, index * hour), stroke)
    }
}

/** The current time (SPEC §7.4): a line across today, with a dot where it starts. */
@Composable
private fun Modifier.nowLine(hours: HoursState, state: CalendarState.Ready, today: Boolean): Modifier {
    if (!today) return this
    val color = MaterialTheme.colorScheme.primary
    val minute = state.nowMinute()
    return drawWithContent {
        drawContent()
        val y = minute / 60f * hours.hourHeight.floatValue.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        drawCircle(color, 4.dp.toPx(), Offset(0f, y))
    }
}

/**
 * A day's column of hours: the children – one per tile of [layout], then one per "+N" mark, in
 * that order – are placed by their minutes and columns (SPEC §7.5): 2 dp less than the slot, 1 dp at the sides.
 */
@Composable
private fun DayColumn(hours: HoursState, layout: DayLayout, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val hour = hours.hourHeight.floatValue.dp.toPx()
        val side = 1.dp.roundToPx()
        val gap = TILE_GAP.dp.roundToPx()
        val width = constraints.maxWidth
        class Slot(val top: Float, val bottom: Float, val column: Int, val columns: Int)
        val slots = layout.tiles.map { Slot(it.top, it.bottom, it.column, it.columns) } + layout.more.map { Slot(it.top, it.bottom, it.column, it.columns) }
        val placed = measurables.mapIndexed { index, measurable ->
            val slot = slots[index]
            val left = width * slot.column / slot.columns + side
            val right = width * (slot.column + 1) / slot.columns - side
            val top = (slot.top / 60f * hour).roundToInt()
            val bottom = (slot.bottom / 60f * hour).roundToInt() - gap
            val placeable = measurable.measure(Constraints.fixed((right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0)))
            Triple(placeable, left, top)
        }
        layout(width, (hour * 24).roundToInt()) {
            for ((placeable, left, top) in placed) placeable.place(left, top)
        }
    }
}

/** The ground of a tile in its state (SPEC §5.3): the fill, dimmed when the event is over or canceled; a dashed outline for a tentative one. */
private fun Modifier.tileGround(colors: TileColors, outlined: Boolean, dimmed: Boolean, radius: Dp, stroke: Dp, flatStart: Boolean = false, flatEnd: Boolean = false): Modifier = drawBehind {
    val inset = if (outlined) stroke.toPx() / 2 else 0f
    val corner = CornerRadius(radius.toPx())
    val start = if (flatStart) CornerRadius.Zero else corner
    val end = if (flatEnd) CornerRadius.Zero else corner
    val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, start, end, end, start)) }
    if (outlined) {
        drawPath(path, colors.outline, style = Stroke(stroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
    } else {
        drawPath(path, if (dimmed) colors.dimmed else colors.fill)
    }
}

private fun TileColors.ink(outlined: Boolean, dimmed: Boolean): Color = if (outlined) outline else if (dimmed) text else on

private fun colors(occurrence: Occurrence, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>): TileColors =
    palette.getValue(state.calendars[occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE)

/**
 * Week's tile, the shared one of SPEC §5: with two lines or more the first is the start time –
 * only on the day the event starts –, the rest the title, wrapped over up to three lines; with one
 * line the title alone, clipped. The lines follow from the height the column gives it.
 */
@Composable
private fun WeekTile(placed: PlacedSegment, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, formats: CalendarFormats) {
    val occurrence = placed.segment.occurrence
    val colors = colors(occurrence, state, palette)
    val outlined = occurrence.tentative && !occurrence.canceled
    val dimmed = occurrence.canceled || occurrence.isPast(state.now, state.today)
    val titleStyle = tileTitleStyle().copy(color = colors.ink(outlined, dimmed), textDecoration = if (occurrence.canceled) TextDecoration.LineThrough else null)
    val title = occurrence.title.ifEmpty { stringResource(R.string.label_no_title) }
    val timed = placed.segment.startsHere
    Layout(
        content = {
            Text(formats.tileTime(occurrence.begin), style = titleStyle.copy(fontWeight = FontWeight.Normal, hyphens = Hyphens.None, textDecoration = null), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text(title, style = titleStyle, overflow = TextOverflow.Clip)
            Text(title, style = titleStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        },
        modifier = Modifier.clipToBounds().tileGround(colors, outlined, dimmed, radius = 4.dp, stroke = 1.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val horizontal = 3.dp.roundToPx()
        val vertical = 1.dp.roundToPx()
        val inner = (width - 2 * horizontal).coerceAtLeast(0)
        val time = measurables[0].measure(Constraints(maxWidth = inner))
        val line = time.height.coerceAtLeast(1)
        val lines = (height - 2 * vertical) / line
        val showTime = timed && lines >= 2
        val titleLines = minOf(Month.MAX_TITLE_LINES, lines - if (showTime) 1 else 0)
        val text = if (titleLines >= 2) {
            measurables[1].measure(Constraints(maxWidth = inner, maxHeight = titleLines * line))
        } else {
            measurables[2].measure(Constraints(maxWidth = inner, maxHeight = minOf(line, (height - vertical).coerceAtLeast(0))))
        }
        layout(width, height) {
            if (showTime) time.place(horizontal, vertical)
            text.place(horizontal, vertical + if (showTime) line else 0)
        }
    }
}

/**
 * Day's tile (SPEC §7.5, §7.6): the title on one line and, when the tile is at least 40 dp high,
 * a line under it – the state when it has one to tell, else the place. An event of a quarter of an
 * hour or less has its time after the title.
 */
@Composable
private fun DayTile(placed: PlacedSegment, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, formats: CalendarFormats) {
    val occurrence = placed.segment.occurrence
    val colors = colors(occurrence, state, palette)
    val outlined = occurrence.tentative && !occurrence.canceled
    val dimmed = occurrence.canceled || occurrence.isPast(state.now, state.today)
    val ink = colors.ink(outlined, dimmed)
    val decoration = if (occurrence.canceled) TextDecoration.LineThrough else null
    val name = occurrence.title.ifEmpty { stringResource(R.string.label_no_title) }
    val short = placed.segment.startsHere && occurrence.end - occurrence.begin <= SHORT_MILLIS
    val supporting = when {
        occurrence.canceled -> stringResource(R.string.label_state_canceled)
        occurrence.tentative -> stringResource(R.string.label_state_tentative)
        else -> occurrence.location.lineSequence().firstOrNull().orEmpty()
    }
    Layout(
        content = {
            Text(
                if (short) stringResource(R.string.label_title_and_time, name, formats.time(occurrence.begin)) else name,
                style = MaterialTheme.typography.labelLarge, color = ink, textDecoration = decoration, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = ink, textDecoration = decoration, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.clipToBounds().tileGround(colors, outlined, dimmed, radius = 8.dp, stroke = 1.5.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val horizontal = 8.dp.roundToPx()
        val vertical = 2.dp.roundToPx()
        val inner = Constraints(maxWidth = (width - 2 * horizontal).coerceAtLeast(0))
        val title = measurables[0].measure(inner)
        val second = if (supporting.isNotEmpty() && height >= 40.dp.roundToPx()) measurables[1].measure(inner) else null
        layout(width, height) {
            title.place(horizontal, vertical)
            second?.place(horizontal, vertical + title.height)
        }
    }
}

/** "+N" in Week's hours: the events at this time that got no column. A tap opens the day. */
@Composable
private fun MoreChip(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(stringResource(R.string.label_more_events, count), style = tileTitleStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** A tile of the all-day strip (SPEC §7.2): 18 dp, the title on one line. */
@Composable
private fun StripTile(occurrence: Occurrence, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, modifier: Modifier = Modifier, flatStart: Boolean = false, flatEnd: Boolean = false) {
    val colors = colors(occurrence, state, palette)
    val outlined = occurrence.tentative && !occurrence.canceled
    val dimmed = occurrence.canceled || occurrence.isPast(state.now, state.today)
    Box(
        modifier.height(STRIP_TILE).clipToBounds().tileGround(colors, outlined, dimmed, radius = 4.dp, stroke = 1.dp, flatStart = flatStart, flatEnd = flatEnd).padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            occurrence.title.ifEmpty { stringResource(R.string.label_no_title) },
            style = tileTitleStyle(),
            color = colors.ink(outlined, dimmed),
            textDecoration = if (occurrence.canceled) TextDecoration.LineThrough else null,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun StripMore(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(STRIP_TILE).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(stringResource(R.string.label_more_events, count), style = tileTitleStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * Week's all-day strip (SPEC §7.2): up to two rows of bars across the days they cover; where a day
 * has more, the last row's place there says "+N", and a tap ([onMore], with the column) opens the day.
 */
@Composable
private fun WeekStrip(week: MonthWeek, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>, onMore: (Int) -> Unit) {
    val fill = remember(week) { Month.fill(week, WEEK_STRIP_ROWS) }
    val more = (0 until 7).filter { fill.cells[it].more > 0 }
    val rows = if (more.isNotEmpty()) WEEK_STRIP_ROWS else (fill.bars.maxOfOrNull { it.lane } ?: -1) + 1
    Layout(
        content = {
            for (bar in fill.bars) StripTile(bar.occurrence, state, palette, flatStart = bar.startsBefore, flatEnd = bar.endsAfter)
            for (column in more) StripMore(fill.cells[column].more, onClick = { onMore(column) })
        },
        modifier = Modifier.fillMaxWidth().padding(start = WEEK_GUTTER),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val side = 1.dp.roundToPx()
        val row = (STRIP_TILE + TILE_GAP.dp).roundToPx()
        val placed = measurables.mapIndexed { index, measurable ->
            val bar = fill.bars.getOrNull(index)
            val first = bar?.firstColumn ?: more[index - fill.bars.size]
            val last = bar?.lastColumn ?: first
            val left = width * first / 7 + if (bar?.startsBefore == true) 0 else side
            val right = width * (last + 1) / 7 - if (bar?.endsAfter == true) 0 else side
            Triple(measurable.measure(Constraints.fixedWidth((right - left).coerceAtLeast(0))), left, (bar?.lane ?: (WEEK_STRIP_ROWS - 1)) * row)
        }
        layout(width, if (rows == 0) 0 else rows * row + TILE_GAP.dp.roundToPx()) {
            for ((placeable, left, top) in placed) placeable.place(left, top)
        }
    }
}

/**
 * Day's all-day strip (SPEC §7.2): up to three rows; with more events the third says "+N", and a
 * tap opens the strip to all of them.
 */
@Composable
private fun DayStrip(day: Int, events: List<Occurrence>, state: CalendarState.Ready, palette: Map<CalendarColor, TileColors>) {
    if (events.isEmpty()) return
    var open by rememberSaveable(day) { mutableStateOf(false) }
    val all = open || events.size <= DAY_STRIP_ROWS
    val shown = if (all) events else events.take(DAY_STRIP_ROWS - 1)
    Column(Modifier.fillMaxWidth().padding(start = DAY_GUTTER, end = 1.dp, bottom = TILE_GAP.dp)) {
        for (event in shown) {
            StripTile(event, state, palette, Modifier.fillMaxWidth())
            Spacer(Modifier.height(TILE_GAP.dp))
        }
        if (!all) StripMore(events.size - shown.size, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
    }
}

private val WEEK_GUTTER = 40.dp
private val DAY_GUTTER = 48.dp
private val STRIP_TILE = 18.dp
private val DAY_MIN_COLUMN = 72.dp
private const val DAY_COLUMNS = 4
private const val DAY_PEEK = 0.5f
private const val WEEK_STRIP_ROWS = 2
private const val DAY_STRIP_ROWS = 3

/** In dp: the smallest tile in Week and in Day (SPEC §7.6), the "+N" mark, and the room under a tile. */
private const val WEEK_MIN_HEIGHT = 20
private const val DAY_MIN_HEIGHT = 24
private const val MORE_HEIGHT = 15
private const val TILE_GAP = 2

/** An event this short has its time on the title's line in Day (SPEC §7.6). */
private const val SHORT_MILLIS = 15 * 60_000L

/** The first hour of the day, until Settings › View has it (SPEC §16.2). */
private const val FIRST_HOUR = 7
