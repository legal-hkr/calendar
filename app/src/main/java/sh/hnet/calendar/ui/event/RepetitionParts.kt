@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import sh.hnet.calendar.R
import sh.hnet.calendar.model.Frequency
import sh.hnet.calendar.model.ReminderTimes
import sh.hnet.calendar.model.Repetition
import sh.hnet.calendar.model.Rule
import sh.hnet.calendar.model.RuleEnd
import sh.hnet.calendar.ui.components.SettingsGroupHeader
import sh.hnet.calendar.ui.components.SettingsRadioRow
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.groupShapes
import sh.hnet.calendar.ui.components.settingsRowColors

private const val DAY_MILLIS = 86_400_000L

/** One radio row of a dialog, as the family's SingleChoiceDialog has them. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "Repeat" (SPEC §10.5, board 13): Does not repeat, the four rules built from the day the event
 * starts on, the rule the event has when it is none of them, and Custom. A choice applies and closes.
 */
@Composable
fun RepetitionDialog(current: Repetition, start: LocalDate, words: EventWords, onChoose: (Repetition) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    val presets = remember(start) { Rule.presets(start) }
    val own = (current as? Repetition.Simple)?.rule?.takeIf { it !in presets }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_repetition)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                ChoiceRow(stringResource(R.string.label_repeat_none), current == Repetition.None) { onChoose(Repetition.None) }
                for (rule in presets) ChoiceRow(words.rule(rule, start), current == Repetition.Simple(rule)) { onChoose(Repetition.Simple(rule)) }
                if (own != null) ChoiceRow(words.ruleWithEnd(own, start), true, onDismiss)
                if (current is Repetition.Other) ChoiceRow(stringResource(R.string.label_repeat_other), true, onDismiss)
                ChoiceRow(stringResource(R.string.label_repeat_custom), false, onCustom)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}

/**
 * A field that opens a menu of [options]: the family's menu under an outlined field, which itself
 * takes no typing.
 */
@Composable
fun <T> DropdownField(label: String, value: String, options: List<T>, text: @Composable (T) -> String, onChoose: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The whole of the field opens the menu
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.DropdownList, onClickLabel = label) { open = true })
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(text(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = MenuDefaults.itemShape(index, options.size).shape, onClick = { open = false; onChoose(option) })
                }
            }
        }
    }
}

/** A whole number from 1 to 999 typed into [text], else null. */
private fun number(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..Rule.MAX_INTERVAL }

/**
 * The rule set by hand (SPEC §10.5, board 14), on a page of its own: the rule in words, every how
 * many of which unit, the weekdays of a weekly rule, the day of a monthly one, and the end. Done
 * takes it over; Close leaves the event as it was.
 */
@Composable
fun RepeatPage(initial: Rule?, start: LocalDate, words: EventWords, onDone: (Rule) -> Unit, onClose: () -> Unit) {
    val first = initial ?: Rule(Frequency.WEEKLY, weekdays = setOf(start.dayOfWeek))
    var frequency by rememberSaveable { mutableStateOf(first.frequency) }
    var every by rememberSaveable { mutableStateOf(first.interval.toString()) }
    // The weekdays as bits, Monday the lowest
    var days by rememberSaveable { mutableIntStateOf(first.weekdays.ifEmpty { setOf(start.dayOfWeek) }.sumOf { 1 shl (it.value - 1) }) }
    // 0 = on the day of the month
    var nth by rememberSaveable { mutableIntStateOf(first.nth ?: 0) }
    var ends by rememberSaveable { mutableIntStateOf(when (first.end) { RuleEnd.Never -> 0; is RuleEnd.Until -> 1; is RuleEnd.Count -> 2 }) }
    var until by rememberSaveable { mutableLongStateOf(((first.end as? RuleEnd.Until)?.date ?: start.plusMonths(3)).toEpochDay()) }
    var count by rememberSaveable { mutableIntStateOf((first.end as? RuleEnd.Count)?.times ?: 10) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingCount by rememberSaveable { mutableStateOf(false) }

    val interval = number(every)
    val weekdays = DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }.toSet()
    val rule = if (interval == null || (frequency == Frequency.WEEKLY && weekdays.isEmpty())) null else Rule(
        frequency, interval,
        weekdays = if (frequency == Frequency.WEEKLY) weekdays else emptySet(),
        nth = if (frequency == Frequency.MONTHLY && nth != 0) nth else null,
        end = when (ends) { 1 -> RuleEnd.Until(LocalDate.ofEpochDay(until)); 2 -> RuleEnd.Count(count); else -> RuleEnd.Never },
    )
    // A weekly rule that leaves out the start's weekday moves the start to the first day it names; the words are for that day
    val shown = rule?.let { named -> generateSequence(start) { it.plusDays(1) }.take(7).firstOrNull { named.names(it, start) } } ?: start

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_repetition), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), onClose) },
            actions = { Button(onClick = { rule?.let(onDone) }, enabled = rule != null, modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.button_done)) } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (rule != null) words.ruleWithEnd(rule, shown) else stringResource(if (interval == null) R.string.error_interval_range else R.string.error_pick_weekday),
                style = MaterialTheme.typography.bodyMedium,
                color = if (rule != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = every,
                    onValueChange = { text -> every = text.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.label_every), maxLines = 1) },
                    singleLine = true,
                    isError = interval == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
                val units = listOf(Frequency.DAILY to R.plurals.label_unit_days, Frequency.WEEKLY to R.plurals.label_unit_weeks, Frequency.MONTHLY to R.plurals.label_unit_months, Frequency.YEARLY to R.plurals.label_unit_years)
                DropdownField(
                    label = stringResource(R.string.label_unit),
                    value = words.unit(units.first { it.first == frequency }.second, interval ?: 1),
                    options = units,
                    text = { words.unit(it.second, interval ?: 1) },
                    onChoose = { frequency = it.first },
                    modifier = Modifier.weight(1f),
                )
            }

            if (frequency == Frequency.WEEKLY) {
                Column {
                    SettingsGroupHeader(stringResource(R.string.label_weekdays))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (day in words.week) {
                            val bit = 1 shl (day.value - 1)
                            val name = words.weekdayName(day)
                            ToggleButton(
                                checked = days and bit != 0,
                                onCheckedChange = { days = days xor bit },
                                shapes = ToggleButtonShapes(RoundedCornerShape(12.dp), RoundedCornerShape(8.dp), CircleShape),
                                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(40.dp).semantics { contentDescription = name },
                            ) {
                                Text(words.weekdayLetter(day), maxLines = 1)
                            }
                        }
                    }
                }
            }

            if (frequency == Frequency.MONTHLY) {
                // On the day of the month, on its weekday by number, and – for the last one of the month – on the last
                val choices = buildList {
                    add(0)
                    if (Rule.ordinal(start) <= 4) add(Rule.ordinal(start))
                    if (Rule.isLast(start)) add(-1)
                }
                val chosen = if (nth in choices) nth else 0
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    choices.forEachIndexed { index, choice ->
                        SettingsRadioRow(groupShapes(index, choices.size), if (choice == 0) words.onDay(start) else words.onNth(choice, start), chosen == choice, { nth = choice })
                    }
                }
            }

            Column {
                SettingsGroupHeader(stringResource(R.string.label_ends))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EndRow(groupShapes(0, 3), stringResource(R.string.label_ends_never), ends == 0, { ends = 0 })
                    EndRow(groupShapes(1, 3), stringResource(R.string.label_ends_on_date), ends == 1, { ends = 1 }, words.shortDate(LocalDate.ofEpochDay(until))) { ends = 1; pickingDate = true }
                    EndRow(groupShapes(2, 3), stringResource(R.string.label_ends_after_count), ends == 2, { ends = 2 }, count.toString()) { ends = 2; pickingCount = true }
                }
            }
        }
    }

    if (pickingDate) {
        // The picker counts in UTC days; a series can't end before it starts
        val earliest = start.toEpochDay() * DAY_MILLIS
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = until * DAY_MILLIS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= earliest
                override fun isSelectableYear(year: Int): Boolean = year >= start.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    pickingDate = false
                    picker.selectedDateMillis?.let { until = Math.floorDiv(it, DAY_MILLIS) }
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.button_cancel)) } },
        ) {
            DatePicker(picker)
        }
    }
    if (pickingCount) {
        var text by rememberSaveable { mutableStateOf(count.toString()) }
        val value = number(text)
        AlertDialog(
            onDismissRequest = { pickingCount = false },
            title = { Text(stringResource(R.string.label_occurrences)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { typed -> text = typed.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    isError = value == null,
                    supportingText = if (value == null) ({ Text(stringResource(R.string.error_interval_range)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(enabled = value != null, onClick = { pickingCount = false; value?.let { count = it } }) { Text(stringResource(R.string.button_save)) } },
            dismissButton = { TextButton(onClick = { pickingCount = false }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
}

/** One answer of "Ends": a radio row; a date or a number stands in a pill at its end, which opens its picker. */
@Composable
private fun EndRow(shapes: ListItemShapes, title: String, selected: Boolean, onSelect: () -> Unit, value: String? = null, onValue: () -> Unit = {}) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = value?.let { { FilledTonalButton(onClick = onValue, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text(it, maxLines = 1) } } },
    ) {
        Text(title)
    }
}

/**
 * "Reminder" (SPEC §10.6, board 15): the usual times as radio rows – those of an all-day event
 * when it is one – and Custom with a number and a unit under it. Add takes the choice over.
 */
@Composable
fun ReminderDialog(allDay: Boolean, taken: List<Int>, words: EventWords, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    val presets = if (allDay) ReminderTimes.allDayPresets else ReminderTimes.timedPresets
    // The first time the event doesn't have yet
    var chosen by rememberSaveable { mutableIntStateOf(presets.indexOfFirst { it !in taken }.let { if (it < 0) presets.size else it }) }
    var amount by rememberSaveable { mutableStateOf("2") }
    // An all-day event is reminded of days before, at 9:00
    val units = if (allDay) {
        listOf(R.plurals.label_unit_days to ReminderTimes.DAY, R.plurals.label_unit_weeks to ReminderTimes.WEEK)
    } else {
        listOf(R.plurals.label_unit_minutes to 1, R.plurals.label_unit_hours to ReminderTimes.HOUR, R.plurals.label_unit_days to ReminderTimes.DAY, R.plurals.label_unit_weeks to ReminderTimes.WEEK)
    }
    var unit by rememberSaveable { mutableIntStateOf(if (allDay) 0 else 1) }
    val value = number(amount)
    val custom = chosen == presets.size
    val minutes = when {
        !custom -> presets[chosen]
        value == null -> null
        allDay -> ReminderTimes.allDay(value * units[unit].second / ReminderTimes.DAY)
        else -> value * units[unit].second
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_reminder)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    presets.forEachIndexed { index, preset -> ChoiceRow(words.reminder(preset, allDay), chosen == index) { chosen = index } }
                    ChoiceRow(stringResource(R.string.label_custom), custom) { chosen = presets.size }
                }
                if (custom) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { text -> amount = text.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.label_how_long_before), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            isError = value == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        DropdownField(
                            label = stringResource(R.string.label_unit),
                            value = words.unit(units[unit].first, value ?: 1),
                            options = units.indices.toList(),
                            text = { words.unit(units[it].first, value ?: 1) },
                            onChoose = { unit = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(enabled = minutes != null, onClick = { minutes?.let(onAdd) }) { Text(stringResource(R.string.button_add)) } },
    )
}
