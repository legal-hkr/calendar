package sh.hnet.calendar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import sh.hnet.calendar.R
import sh.hnet.calendar.model.CalendarColor

/** The fill of tiles, bars and dots in this theme (SPEC §4.1). */
@Composable
fun CalendarColor.fill(): Color = colorResource(fillRes())

/** Text on [fill]. */
@Composable
fun CalendarColor.onFill(): Color = colorResource(onRes())

/** Text on a dimmed or outlined tile. */
@Composable
fun CalendarColor.text(): Color = colorResource(textRes())

/** The dashed outline of a tentative event. */
@Composable
fun CalendarColor.outline(): Color = colorResource(outlineRes())

/**
 * The fill of an event that is over (SPEC §4.2): the calendar color mixed half and half with the
 * theme's surface, channel by channel in sRGB, as an opaque color – so it follows dynamic color.
 */
@Composable
fun CalendarColor.dimmedFill(): Color {
    val fill = fill()
    val surface = MaterialTheme.colorScheme.surface
    return Color((fill.red + surface.red) / 2f, (fill.green + surface.green) / 2f, (fill.blue + surface.blue) / 2f)
}

/** The fill's color resource; the widgets set it by id, so it follows the theme on the home screen. */
fun CalendarColor.fillRes(): Int = when (this) {
    CalendarColor.RED -> R.color.calendar_red_fill
    CalendarColor.ORANGE -> R.color.calendar_orange_fill
    CalendarColor.AMBER -> R.color.calendar_amber_fill
    CalendarColor.OLIVE -> R.color.calendar_olive_fill
    CalendarColor.GREEN -> R.color.calendar_green_fill
    CalendarColor.TEAL -> R.color.calendar_teal_fill
    CalendarColor.SKY -> R.color.calendar_sky_fill
    CalendarColor.BLUE -> R.color.calendar_blue_fill
    CalendarColor.VIOLET -> R.color.calendar_violet_fill
    CalendarColor.MAGENTA -> R.color.calendar_magenta_fill
    CalendarColor.RASPBERRY -> R.color.calendar_raspberry_fill
    CalendarColor.GRAPHITE -> R.color.calendar_graphite_fill
}

/** Text on the fill, as a color resource. */
fun CalendarColor.onRes(): Int = when (this) {
    CalendarColor.RED -> R.color.calendar_red_on
    CalendarColor.ORANGE -> R.color.calendar_orange_on
    CalendarColor.AMBER -> R.color.calendar_amber_on
    CalendarColor.OLIVE -> R.color.calendar_olive_on
    CalendarColor.GREEN -> R.color.calendar_green_on
    CalendarColor.TEAL -> R.color.calendar_teal_on
    CalendarColor.SKY -> R.color.calendar_sky_on
    CalendarColor.BLUE -> R.color.calendar_blue_on
    CalendarColor.VIOLET -> R.color.calendar_violet_on
    CalendarColor.MAGENTA -> R.color.calendar_magenta_on
    CalendarColor.RASPBERRY -> R.color.calendar_raspberry_on
    CalendarColor.GRAPHITE -> R.color.calendar_graphite_on
}

private fun CalendarColor.textRes(): Int = when (this) {
    CalendarColor.RED -> R.color.calendar_red_text
    CalendarColor.ORANGE -> R.color.calendar_orange_text
    CalendarColor.AMBER -> R.color.calendar_amber_text
    CalendarColor.OLIVE -> R.color.calendar_olive_text
    CalendarColor.GREEN -> R.color.calendar_green_text
    CalendarColor.TEAL -> R.color.calendar_teal_text
    CalendarColor.SKY -> R.color.calendar_sky_text
    CalendarColor.BLUE -> R.color.calendar_blue_text
    CalendarColor.VIOLET -> R.color.calendar_violet_text
    CalendarColor.MAGENTA -> R.color.calendar_magenta_text
    CalendarColor.RASPBERRY -> R.color.calendar_raspberry_text
    CalendarColor.GRAPHITE -> R.color.calendar_graphite_text
}

private fun CalendarColor.outlineRes(): Int = when (this) {
    CalendarColor.RED -> R.color.calendar_red_outline
    CalendarColor.ORANGE -> R.color.calendar_orange_outline
    CalendarColor.AMBER -> R.color.calendar_amber_outline
    CalendarColor.OLIVE -> R.color.calendar_olive_outline
    CalendarColor.GREEN -> R.color.calendar_green_outline
    CalendarColor.TEAL -> R.color.calendar_teal_outline
    CalendarColor.SKY -> R.color.calendar_sky_outline
    CalendarColor.BLUE -> R.color.calendar_blue_outline
    CalendarColor.VIOLET -> R.color.calendar_violet_outline
    CalendarColor.MAGENTA -> R.color.calendar_magenta_outline
    CalendarColor.RASPBERRY -> R.color.calendar_raspberry_outline
    CalendarColor.GRAPHITE -> R.color.calendar_graphite_outline
}
