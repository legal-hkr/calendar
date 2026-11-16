package sh.hnet.calendar.ui.calendar

import androidx.compose.runtime.staticCompositionLocalOf
import sh.hnet.calendar.ViewSettings
import sh.hnet.calendar.model.Occurrence

/** What Settings › View says about how the views look (SPEC §16.2), handed down to the tiles. */
data class ViewOptions(val dimPast: Boolean = true, val firstHour: Int = ViewSettings.FIRST_HOUR, val weekNumbers: Boolean = false)

val LocalViewOptions = staticCompositionLocalOf { ViewOptions() }

/**
 * How a tile shows its event's state (SPEC §5.3): [outlined] in place of a fill – dashed for an
 * invitation answered "maybe", [solid] for a declined one –, [dimmed] for a canceled event and,
 * unless "Dim past events" is off, one that is over; [struck] for canceled and declined.
 */
internal data class TileState(val outlined: Boolean, val solid: Boolean, val dimmed: Boolean, val struck: Boolean)

internal fun Occurrence.tileState(now: Long, today: Int, dimPast: Boolean): TileState {
    val solid = declined && !canceled
    return TileState(
        outlined = solid || (tentative && !canceled),
        solid = solid,
        dimmed = canceled || (dimPast && isPast(now, today)),
        struck = canceled || declined,
    )
}
