package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.data.CalendarFormPayload
import com.zillit.zillitapp.core.calendar.data.CalendarFormPayload.endDate
import com.zillit.zillitapp.core.calendar.data.EventFormError
import com.zillit.zillitapp.core.calendar.model.CallType
import com.zillit.zillitapp.core.calendar.model.EventType
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.directory.ExternalUser
import com.zillit.zillitapp.core.directory.ProjectDepartment
import com.zillit.zillitapp.core.directory.ProjectDesignation
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.DropdownField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.zillit.zillitapp.core.ui.components.CountBar
import com.zillit.zillitapp.core.ui.components.FieldBox
import com.zillit.zillitapp.core.ui.components.FieldLabel
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.LabeledField
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.SectionCard
import com.zillit.zillitapp.core.ui.components.SegmentedButtons
import com.zillit.zillitapp.core.ui.location.LocationPickerScreen
import com.zillit.zillitapp.core.ui.location.PickedLocation
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.theme.toColorOrDefault
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * Create or edit an event.
 *
 * Laid out to match v2 field for field, because the order is what people have learned:
 * the type is the first decision and sits at the top as two tabs, then **EVENT** (title,
 * colour, dates, times, timezone, repeat), **Details** (description, location, reminder),
 * and **PEOPLE** (call type, invitees, external guests) with the organiser's opt-out last.
 *
 * Every label and hint here is v2's own text. None of it is rewritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventFormViewModel = hiltViewModel(),
    projectUsers: List<ProjectUser> = emptyList(),
    departments: List<ProjectDepartment> = emptyList(),
    externalUsers: List<ExternalUser> = emptyList(),
    countries: List<CountryCode> = emptyList(),
    designationsFor: (String) -> List<ProjectDesignation> = { emptyList() },
    currentUserId: String? = null,
    isAdmin: Boolean = false,
    onSaveExternalUser: suspend (ExternalUser) -> ExternalUser? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input = state.input
    val context = LocalContext.current

    var message by remember { mutableStateOf<String?>(null) }
    var scopePrompt by remember { mutableStateOf(false) }
    var inviteesOpen by remember { mutableStateOf(false) }
    var externalOpen by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    var timezoneOpen by remember { mutableStateOf(false) }
    var mapOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EventFormEvent.Invalid -> message = context.getString(
                    event.error.messageRes(),
                    CalendarFormPayload.MIN_DURATION_MINUTES,
                )

                is EventFormEvent.Adjusted -> message = context.getString(
                    R.string.calendar_adjusted_end,
                    CalendarFormPayload.MIN_DURATION_MINUTES,
                )

                is EventFormEvent.Saved -> onSaved()

                is EventFormEvent.Failed ->
                    message = event.message ?: context.getString(R.string.calendar_action_failed)
            }
        }
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(
                    if (state.isEditMode) {
                        R.string.calendar_edit_event_title
                    } else {
                        R.string.calendar_new_event_title
                    },
                ),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        // Pinned, as in v2: the form is long enough that a button at the end of the scroll
        // would be somewhere you have to go looking for.
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider(color = ZillitTheme.colors.divider)
                Column(modifier = Modifier.padding(ZillitTheme.spacing.md)) {
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = ZillitTheme.colors.danger,
                            modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
                        )
                    }
                    PrimaryButton(
                        text = stringResource(
                            if (state.isEditMode) {
                                R.string.calendar_update_event
                            } else {
                                R.string.calendar_create
                            },
                        ),
                        onClick = {
                            message = null
                            if (state.isEditMode && state.isRecurringSeries) {
                                scopePrompt = true
                            } else {
                                viewModel.submit()
                            }
                        },
                        loading = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // The type decision sits above the first card because it changes what the rest
            // of the form is. Create only: an event cannot change kind once it exists.
            if (!state.isEditMode) {
                SegmentedButtons(
                    options = listOf(
                        stringResource(R.string.calendar_type_members),
                        stringResource(R.string.calendar_type_personal),
                    ),
                    selectedIndex = if (input.type == EventType.MEMBERS) 0 else 1,
                    onSelect = {
                        viewModel.setEventType(
                            if (it == 0) EventType.MEMBERS else EventType.PERSONAL,
                        )
                    },
                )
            }

            // ── EVENT ────────────────────────────────────────────────────────
            SectionCard(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.calendar_section_event),
            ) {
                FormTextField(
                    label = stringResource(R.string.calendar_field_title),
                    value = input.title,
                    onValueChange = viewModel::setTitle,
                    placeholder = stringResource(R.string.calendar_title_hint),
                )

                // Colour and Full Day share a line: one is what the event looks like, the
                // other is how long it runs, and neither needs a row of its own.
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    LabeledField(
                        label = stringResource(R.string.calendar_field_color),
                        value = input.color,
                        onClick = { colorOpen = true },
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        input.color.toColorOrDefault(ZillitTheme.colors.brand),
                                        CircleShape,
                                    ),
                            )
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = ZillitTheme.colors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )

                    // The label toggles too: v2's SwitchMaterial draws its text as part of
                    // the control, and a label that looks attached but is not is a control
                    // people quietly fail to use.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(bottom = ZillitTheme.spacing.xs)
                            .clickable { viewModel.setFullDay(!input.isFullDay) },
                    ) {
                        Text(
                            text = stringResource(R.string.calendar_full_day),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textPrimary,
                        )
                        Switch(
                            checked = input.isFullDay,
                            onCheckedChange = null,
                        )
                    }
                }

                // Start and end dates side by side. The end is read-only — it follows the
                // start and the overnight rule — and is hidden entirely for a full day,
                // which is already one whole date.
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    DateField(
                        label = stringResource(R.string.calendar_date),
                        date = input.date,
                        placeholder = stringResource(R.string.calendar_select_date),
                        onPick = viewModel::setDate,
                        modifier = Modifier.weight(1f),
                    )

                    if (!input.isFullDay) {
                        LabeledField(
                            label = stringResource(R.string.calendar_end_date),
                            value = input.endDate()
                                .format(DateTime.formatter(DateTime.PATTERN_DATE)),
                            muted = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                }

                if (!input.isFullDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        TimeField(
                            label = stringResource(R.string.calendar_starts),
                            time = input.startTime,
                            onPick = viewModel::setStartTime,
                            modifier = Modifier.weight(1f),
                        )
                        TimeField(
                            label = stringResource(R.string.calendar_ends),
                            time = input.endTime,
                            onPick = viewModel::setEndTime,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                LabeledField(
                    label = stringResource(R.string.calendar_timezone),
                    value = state.timezones.firstOrNull { it.id == input.timezone }?.label
                        ?: input.timezone,
                    onClick = { if (state.timezones.isNotEmpty()) timezoneOpen = true },
                    trailing = {
                        // A chevron pointing on, not down: the timezone opens a screen of
                        // its own rather than a short menu.
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = ZillitTheme.colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )

                RecurrenceFields(
                    frequency = input.recurrenceFrequency,
                    selectedDays = input.selectedDays,
                    recurrenceEnd = input.recurrenceEnd,
                    onFrequencyChange = viewModel::setRecurrenceFrequency,
                    onToggleDay = viewModel::toggleWeekday,
                    onEndChange = viewModel::setRecurrenceEnd,
                )
            }

            // ── DETAILS ──────────────────────────────────────────────────────
            SectionCard(
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.calendar_section_details),
            ) {
                FormTextField(
                    label = stringResource(R.string.calendar_field_description),
                    value = input.description,
                    onValueChange = viewModel::setDescription,
                    placeholder = stringResource(R.string.calendar_description_hint),
                    singleLine = false,
                    minLines = 3,
                )

                // Not a text field: a location means a point on a map, and free text alone
                // cannot give the detail sheet somewhere to send people.
                FieldLabel(stringResource(R.string.calendar_field_location))
                FieldBox(onClick = { mapOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = input.locationDescription.ifBlank {
                            stringResource(R.string.calendar_location_hint)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (input.locationDescription.isBlank()) {
                            ZillitTheme.colors.textTertiary
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = ZillitTheme.spacing.sm),
                    )
                    if (input.locationDescription.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.calendar_change),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ZillitTheme.colors.brand,
                            modifier = Modifier
                                .clickable { mapOpen = true }
                                .padding(horizontal = ZillitTheme.spacing.sm),
                        )
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.calendar_clear),
                            tint = ZillitTheme.colors.textTertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { viewModel.setLocation("", null, null) },
                        )
                    }
                }

                ReminderField(selected = input.notifyMinutes, onPick = viewModel::setNotify)
            }

            // ── PEOPLE ───────────────────────────────────────────────────────
            // Skipped entirely for a personal event: it has no attendance flow at all.
            if (input.type == EventType.MEMBERS) {
                SectionCard(
                    icon = Icons.Outlined.People,
                    title = stringResource(R.string.calendar_section_people),
                ) {
                    CallTypeField(selected = input.callType, onPick = viewModel::setCallType)

                    FieldLabel(stringResource(R.string.calendar_field_invite_members))
                    SecondaryButton(
                        text = stringResource(R.string.calendar_add_invitees),
                        onClick = { inviteesOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (input.inviteeIds.isNotEmpty()) {
                        CountBar(
                            text = stringResource(
                                R.string.calendar_invitee_summary,
                                input.inviteeIds.size,
                            ),
                            clearLabel = stringResource(R.string.calendar_clear),
                            onClear = { viewModel.setInvitees(emptyList()) },
                        )
                    }

                    FieldLabel(stringResource(R.string.calendar_external_guests))
                    SecondaryButton(
                        text = stringResource(R.string.calendar_add_external_guests),
                        onClick = { externalOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (input.externalEmails.isNotEmpty()) {
                        CountBar(
                            text = stringResource(
                                R.string.calendar_invitee_summary,
                                input.externalEmails.size,
                            ),
                            clearLabel = stringResource(R.string.calendar_clear),
                            onClear = { viewModel.setExternalEmails(emptyList()) },
                        )
                    }

                    // Last, as in v2 — a footnote to the invitee list, not a field of its
                    // own, and a checkbox rather than a switch because it is a one-off
                    // choice about this event.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setExcludeCreator(!input.excludeCreator) },
                    ) {
                        Checkbox(
                            checked = input.excludeCreator,
                            onCheckedChange = null,
                        )
                        Text(
                            text = stringResource(R.string.calendar_exclude_me),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textPrimary,
                            modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
                        )
                    }
                }
            }
        }
    }

    if (colorOpen) {
        ColorPickerSheet(
            selected = input.color,
            onPick = {
                viewModel.setColor(it)
                colorOpen = false
            },
            onDismiss = { colorOpen = false },
        )
    }

    if (timezoneOpen) {
        TimezonePickerSheet(
            timezones = state.timezones,
            selected = input.timezone,
            onPick = {
                viewModel.setTimezone(it)
                timezoneOpen = false
            },
            onDismiss = { timezoneOpen = false },
        )
    }

    if (mapOpen) {
        // The shared picker, opened over the form: the same page every other tool uses to
        // choose a place.
        LocationPickerScreen(
            initial = PickedLocation(
                address = input.locationDescription,
                latitude = input.locationLat,
                longitude = input.locationLong,
            ),
            onConfirm = { picked ->
                mapOpen = false
                viewModel.setLocation(picked.address, picked.latitude, picked.longitude)
            },
            onBack = { mapOpen = false },
        )
    }

    if (inviteesOpen) {
        InviteePickerScreen(
            users = projectUsers,
            departments = departments,
            selectedIds = input.inviteeIds,
            excludeUserIds = setOfNotNull(currentUserId),
            isAdmin = isAdmin,
            onConfirm = {
                viewModel.setInvitees(it)
                inviteesOpen = false
            },
            onDismiss = { inviteesOpen = false },
        )
    }

    if (externalOpen) {
        ExternalGuestScreen(
            known = externalUsers,
            departments = departments,
            countries = countries,
            designationsFor = designationsFor,
            selected = input.externalEmails,
            onConfirm = {
                viewModel.setExternalEmails(it)
                externalOpen = false
            },
            onSaveNew = onSaveExternalUser,
            onDismiss = { externalOpen = false },
        )
    }

    if (scopePrompt) {
        EditScopeSheet(
            onConfirm = { scope ->
                scopePrompt = false
                viewModel.submit(scope)
            },
            onDismiss = { scopePrompt = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    LabeledField(
        label = label,
        value = date.format(DateTime.formatter(DateTime.PATTERN_DATE)),
        placeholder = placeholder,
        onClick = { open = true },
        modifier = modifier,
    )

    if (open) {
        // The picker works in UTC; this is a calendar date, so it is rebuilt from the day
        // that was tapped rather than converted through the device zone.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        open = false
                        pickerState.selectedDateMillis?.let {
                            onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    time: LocalTime,
    onPick: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    LabeledField(
        label = label,
        value = time.format(DateTime.formatter(DateTime.PATTERN_TIME_12)),
        onClick = { open = true },
        modifier = modifier,
    )

    if (open) {
        val pickerState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = false,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        open = false
                        onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = pickerState)
            }
        }
    }
}

/** Call type as a dropdown, matching v2 — four options is a list, not a row of chips. */
@Composable
private fun CallTypeField(selected: CallType, onPick: (CallType) -> Unit) {
    val options = remember { CallType.entries.filterNot { it == CallType.NONE } }

    DropdownField(
        label = stringResource(R.string.calendar_call_type),
        value = stringResource(selected.labelRes()),
        options = options,
        optionLabel = { stringResource(it.labelRes()) },
        onPick = onPick,
    )
}

@Composable
private fun ReminderField(selected: Int, onPick: (Int) -> Unit) {
    DropdownField(
        label = stringResource(R.string.calendar_reminder),
        value = reminderLabel(selected),
        options = CalendarFormPayload.NOTIFY_OPTIONS,
        optionLabel = { reminderLabel(it) },
        onPick = onPick,
    )
}

@Composable
private fun reminderLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.calendar_reminder_none)
    minutes == MINUTES_PER_HOUR -> stringResource(R.string.calendar_reminder_hour)
    else -> stringResource(R.string.calendar_reminder_minutes, minutes)
}

@Composable
private fun RecurrenceFields(
    frequency: Int,
    selectedDays: List<Int>,
    recurrenceEnd: LocalDate?,
    onFrequencyChange: (Int) -> Unit,
    onToggleDay: (Int) -> Unit,
    onEndChange: (LocalDate?) -> Unit,
) {
    DropdownField(
        label = stringResource(R.string.calendar_repeat),
        value = stringResource(frequency.frequencyLabelRes()),
        options = FREQUENCIES,
        optionLabel = { stringResource(it.frequencyLabelRes()) },
        onPick = onFrequencyChange,
    )

    if (frequency == CalendarFormPayload.FREQ_WEEKLY) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            modifier = Modifier.padding(vertical = ZillitTheme.spacing.xs),
        ) {
            // 0 = Sunday, as the backend numbers them.
            (0..6).forEach { day ->
                val label = java.time.DayOfWeek.SUNDAY.plus(day.toLong())
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                FilterChip(
                    selected = day in selectedDays,
                    onClick = { onToggleDay(day) },
                    label = { Text(label) },
                )
            }
        }
    }

    if (frequency != CalendarFormPayload.FREQ_NONE) {
        DateField(
            label = stringResource(R.string.calendar_repeat_ends),
            date = recurrenceEnd ?: LocalDate.now(),
            onPick = onEndChange,
        )
    }
}

private fun EventType.labelRes(): Int = when (this) {
    EventType.MEMBERS -> R.string.calendar_type_members
    EventType.PERSONAL -> R.string.calendar_type_personal
}

private fun CallType.labelRes(): Int = when (this) {
    CallType.AUDIO -> R.string.calendar_call_audio
    CallType.VIDEO -> R.string.calendar_call_video
    CallType.MEET_IN_PERSON -> R.string.calendar_call_in_person
    CallType.MEET_IN_PERSON_CALL -> R.string.calendar_call_in_person_call
    CallType.NONE -> R.string.calendar_call_none
}

private fun Int.frequencyLabelRes(): Int = when (this) {
    CalendarFormPayload.FREQ_DAILY -> R.string.calendar_repeat_daily
    CalendarFormPayload.FREQ_WEEKLY -> R.string.calendar_repeat_weekly
    CalendarFormPayload.FREQ_MONTHLY -> R.string.calendar_repeat_monthly
    CalendarFormPayload.FREQ_YEARLY -> R.string.calendar_repeat_yearly
    CalendarFormPayload.FREQ_CUSTOM -> R.string.calendar_repeat_custom
    else -> R.string.calendar_repeat_none
}

private fun EventFormError.messageRes(): Int = when (this) {
    EventFormError.TITLE_TOO_SHORT -> R.string.calendar_error_title
    EventFormError.START_IN_PAST -> R.string.calendar_error_start_past
    EventFormError.DURATION_TOO_SHORT -> R.string.calendar_error_duration
    EventFormError.RECURRENCE_END_REQUIRED -> R.string.calendar_error_recurrence_end
    EventFormError.RECURRENCE_END_BEFORE_START -> R.string.calendar_error_recurrence_before
    EventFormError.INVITEES_REQUIRED -> R.string.calendar_error_invitees
    EventFormError.CALL_TYPE_REQUIRED -> R.string.calendar_error_call_type
    EventFormError.LOCATION_REQUIRED -> R.string.calendar_error_location
}

private val FREQUENCIES = listOf(
    CalendarFormPayload.FREQ_NONE,
    CalendarFormPayload.FREQ_DAILY,
    CalendarFormPayload.FREQ_WEEKLY,
    CalendarFormPayload.FREQ_MONTHLY,
    CalendarFormPayload.FREQ_YEARLY,
)

private const val MINUTES_PER_HOUR = 60
