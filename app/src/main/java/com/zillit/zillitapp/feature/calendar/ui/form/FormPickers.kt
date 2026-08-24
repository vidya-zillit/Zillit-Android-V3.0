package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.data.CalendarFormPayload
import com.zillit.zillitapp.core.calendar.model.Timezone
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.asLabel
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.theme.toColorOrDefault

/**
 * Choosing an event's colour: the approved palette, or anything else.
 *
 * Presets first because they are what almost everyone wants and they keep a project's
 * calendar visually consistent. Custom exists for the cases a fixed palette cannot cover —
 * a production matching a client's brand, or simply needing two events to look different
 * when both landed on the same preset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customMode by rememberSaveable { mutableStateOf(false) }
    var customHex by rememberSaveable { mutableStateOf(selected) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
        ) {
            SheetTitle(stringResource(R.string.calendar_field_color))

            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
            ) {
                FilterChip(
                    selected = !customMode,
                    onClick = { customMode = false },
                    label = { Text(stringResource(R.string.calendar_color_presets)) },
                )
                FilterChip(
                    selected = customMode,
                    onClick = { customMode = true },
                    label = { Text(stringResource(R.string.calendar_color_custom)) },
                )
            }

            if (customMode) {
                CustomColorPicker(
                    hex = customHex,
                    onHexChange = { customHex = it },
                    onConfirm = { onPick(customHex) },
                )
            } else {
                PresetColors(selected = selected, onPick = onPick)
            }
        }
    }
}

@Composable
private fun PresetColors(selected: String, onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLOR_COLUMNS),
        modifier = Modifier.heightIn(max = 320.dp),
        contentPadding = PaddingValues(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(CalendarFormPayload.COLORS, key = { it.first }) { (hex, name) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onPick(hex) },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(hex.toColorOrDefault(ZillitTheme.colors.brand), CircleShape)
                        .then(
                            if (hex.equals(selected, ignoreCase = true)) {
                                Modifier.border(3.dp, ZillitTheme.colors.textPrimary, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hex.equals(selected, ignoreCase = true)) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A hex field with a live preview and three sliders.
 *
 * The sliders exist because typing hex is not how most people think about colour; the hex
 * field exists because it is exactly how someone matching a brand does. They edit the same
 * value, so either way in works.
 */
@Composable
private fun CustomColorPicker(
    hex: String,
    onHexChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = hex.toColorOrDefault(ZillitTheme.colors.brand)
    val red = (preview.red * COLOR_MAX).toInt()
    val green = (preview.green * COLOR_MAX).toInt()
    val blue = (preview.blue * COLOR_MAX).toInt()

    fun emit(r: Int, g: Int, b: Int) = onHexChange("#%02X%02X%02X".format(r, g, b))

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(modifier = Modifier.size(44.dp).background(preview, CircleShape))
            OutlinedTextField(
                value = hex,
                onValueChange = onHexChange,
                singleLine = true,
                label = { Text(stringResource(R.string.calendar_field_color)) },
                modifier = Modifier.weight(1f),
            )
        }

        ColorSlider("R", red) { emit(it, green, blue) }
        ColorSlider("G", green) { emit(red, it, blue) }
        ColorSlider("B", blue) { emit(red, green, it) }

        PrimaryButton(
            text = stringResource(R.string.action_done),
            onClick = onConfirm,
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        )
    }
}

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..COLOR_MAX,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )
    }
}

private const val COLOR_MAX = 255f

/** The timezone list, searchable — it is long enough that scrolling it is not an option. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimezonePickerSheet(
    timezones: List<Timezone>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(timezones, query) {
        if (query.isBlank()) {
            timezones
        } else {
            timezones.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg),
        ) {
            SheetTitle(stringResource(R.string.calendar_timezone))
            SearchField(query = query, onQueryChange = { query = it })

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = ZillitTheme.spacing.lg),
            ) {
                items(filtered, key = { it.id }) { zone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(zone.id) }
                            .padding(vertical = ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = zone.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (zone.id == selected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = ZillitTheme.colors.brand,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Who to invite.
 *
 * Reads the project directory rather than a calendar endpoint — v2's
 * `calendar/project-users` is a dead stub and its own picker reads the local store too.
 *
 * The signed-in user is not offered: they are the creator, and inviting yourself to your
 * own event is meaningless. The same goes for the original creator when an admin is
 * editing someone else's event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteePickerSheet(
    users: List<ProjectUser>,
    selectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    excludeUserIds: Set<String> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Saved through a list: a Set is not something the saved-state bundle can hold, and
    // losing a half-made invitee selection to a screen rotation is the kind of thing that
    // makes people stop trusting a form.
    var selection by rememberSaveable(
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(selectedIds.toSet()) }
    val labels = LocalLabels.current

    val candidates = remember(users, excludeUserIds) {
        users.filterNot { it.userId in excludeUserIds }
    }

    val filtered = remember(candidates, query) {
        if (query.isBlank()) {
            candidates
        } else {
            candidates.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.departmentName?.contains(query, ignoreCase = true) == true ||
                    it.designationName?.contains(query, ignoreCase = true) == true ||
                    // Also matched on the resolved text, so searching "Director" finds
                    // someone whose stored designation is `director_label`.
                    it.designationName?.let { key -> labels.resolveLabel(key) }
                        ?.contains(query, ignoreCase = true) == true
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg),
        ) {
            SheetTitle(stringResource(R.string.calendar_select_invitees))
            SearchField(query = query, onQueryChange = { query = it })

            // Select-all applies to what is currently filtered, not the whole project —
            // searching "camera" then tapping it should invite the camera department.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val ids = filtered.map { it.userId }
                        selection = if (ids.all { it in selection }) {
                            selection - ids.toSet()
                        } else {
                            selection + ids
                        }
                    }
                    .padding(vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = filtered.isNotEmpty() && filtered.all { it.userId in selection },
                    onCheckedChange = null,
                )
                Text(
                    text = stringResource(R.string.calendar_select_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
                )
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                contentPadding = PaddingValues(bottom = ZillitTheme.spacing.sm),
            ) {
                items(filtered, key = { it.userId }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selection = if (user.userId in selection) {
                                    selection - user.userId
                                } else {
                                    selection + user.userId
                                }
                            }
                            .padding(vertical = ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = user.userId in selection, onCheckedChange = null)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = ZillitTheme.spacing.sm),
                        ) {
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Resolved, not raw: the server sends designations as label
                            // keys (`director_label`), which is not something to show a user.
                            user.designationName?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it.asLabel(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ZillitTheme.colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            PrimaryButton(
                text = stringResource(R.string.calendar_done_count, selection.size),
                onClick = { onConfirm(selection.toList()) },
                modifier = Modifier.padding(vertical = ZillitTheme.spacing.md),
            )
        }
    }
}

/** External guests, by email — people who are not on the project at all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalEmailSheet(
    emails: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by rememberSaveable(
        saver = listSaver<MutableState<List<String>>, String>(
            save = { it.value },
            restore = { mutableStateOf(it) },
        ),
    ) { mutableStateOf(emails) }
    var draft by rememberSaveable { mutableStateOf("") }

    val isValid = remember(draft) {
        draft.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(draft.trim()).matches()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            SheetTitle(stringResource(R.string.calendar_external_guests))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.calendar_email_hint)) },
                    singleLine = true,
                    isError = draft.isNotBlank() && !isValid,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.calendar_add),
                    onClick = {
                        val value = draft.trim()
                        // Duplicates are dropped rather than rejected — adding the same
                        // address twice is a slip, not an error worth a message.
                        if (value !in entries) entries = entries + value
                        draft = ""
                    },
                    enabled = isValid,
                    modifier = Modifier.width(96.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(entries, key = { it }) { email ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { entries = entries - email }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.delete),
                                tint = ZillitTheme.colors.textTertiary,
                            )
                        }
                    }
                }
            }

            PrimaryButton(
                text = stringResource(R.string.action_done),
                onClick = { onConfirm(entries) },
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = ZillitTheme.colors.textPrimary,
        modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = ZillitTheme.colors.textTertiary,
            )
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ZillitTheme.spacing.sm),
    )
}

private const val COLOR_COLUMNS = 4
