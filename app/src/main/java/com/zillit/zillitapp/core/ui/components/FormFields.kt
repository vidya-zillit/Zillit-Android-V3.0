package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/*
 * The form vocabulary, shared by every form in the app.
 *
 * The shapes come from v2's event form, which is what the client approved: sections are
 * bordered cards introduced by a tinted icon chip, and every value — typed, picked or
 * chosen from a list — sits in the same white 44dp box. Keeping that in one place is what
 * stops the next form from inventing a second look for the same control.
 */

/** The height v2 gives every field, so a row of them lines up whatever it contains. */
private val FIELD_HEIGHT = 44.dp
private val FIELD_RADIUS = 12.dp
private val CARD_RADIUS = 12.dp
private val ICON_CHIP = 28.dp
private val ICON_SIZE = 14.dp
private val TOGGLE_HEIGHT = 44.dp
private val TOGGLE_RADIUS = 6.dp
private val LINE_HEIGHT = 20.dp

/**
 * A bordered card holding one group of fields, introduced by a tinted icon.
 *
 * The icon is what makes a long form skimmable — people find "the people bit" by its
 * silhouette long before they read the heading.
 */
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(CARD_RADIUS),
        color = ZillitTheme.colors.surface,
        border = BorderStroke(1.dp, ZillitTheme.colors.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ICON_CHIP)
                        .background(ZillitTheme.colors.brandSoft, RoundedCornerShape(6.dp)),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ZillitTheme.colors.brand,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                }
                FieldLabel(
                    text = title,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
                )
            }

            content()
        }
    }
}

/**
 * The small capitalised caption above a field.
 *
 * Upper-cased here rather than in the strings, exactly as v2's `TextLabel` style does, so
 * the same string can be read out in a sentence elsewhere without shouting.
 */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
        fontWeight = FontWeight.Bold,
        color = ZillitTheme.colors.textTertiary,
        modifier = modifier,
    )
}

/**
 * The white box a value sits in.
 *
 * One height and one corner for typed text, picked dates and chosen list values alike:
 * v2 makes no visual distinction between them, and a form where some fields look editable
 * and others do not invites people to try typing into the ones that are not.
 */
@Composable
fun FieldBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(FIELD_RADIUS),
        color = ZillitTheme.colors.surface,
        border = BorderStroke(1.dp, ZillitTheme.colors.border),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .defaultMinSize(minHeight = FIELD_HEIGHT)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            content = content,
        )
    }
}

/**
 * A labelled, read-only field: the caption above, the value in its box below.
 *
 * @param muted for a value the form works out rather than asks for — v2 dims its read-only
 *   End Date this way so it does not look like something waiting to be tapped.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onClick: (() -> Unit)? = null,
    muted: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel(label)

        FieldBox(onClick = onClick) {
            leading?.let {
                it()
                Spacer(Modifier.size(ZillitTheme.spacing.sm))
            }

            Text(
                text = value.ifBlank { placeholder.orEmpty() },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    value.isBlank() || muted -> ZillitTheme.colors.textTertiary
                    else -> ZillitTheme.colors.textPrimary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            trailing?.invoke()
        }
    }
}

/**
 * A typed field wearing the same box as every picked one.
 *
 * Material's own text fields bring their own container and floating label, which would make
 * the fields either side of one look like a different kind of thing. This keeps a form
 * looking like a form rather than a pile of widgets.
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        label?.let { FieldLabel(it) }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) {
                    ZillitTheme.colors.textPrimary
                } else {
                    ZillitTheme.colors.textTertiary
                },
            ),
            cursorBrush = SolidColor(ZillitTheme.colors.brand),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field ->
                FieldBox {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textTertiary,
                            )
                        }
                        Column(
                            modifier = Modifier.defaultMinSize(
                                minHeight = LINE_HEIGHT * (minLines - 1),
                            ),
                        ) {
                            field()
                        }
                    }
                }
            },
        )
    }
}

/**
 * A dropdown that looks like every other field until it is tapped.
 *
 * The menu is anchored to the field rather than opening a sheet: these lists are short —
 * call types, genders, a handful of departments — and a sheet for four options is a lot of
 * screen for a small decision.
 */
@Composable
fun <T> DropdownField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    error: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column {
            LabeledField(
                label = label,
                value = value,
                placeholder = placeholder,
                onClick = if (enabled) {
                    { open = true }
                } else {
                    null
                },
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                )
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * Two or more full-width choices, the selected one filled.
 *
 * v2 uses this for the decision that reshapes the form beneath it, and gives it far more
 * weight than a tab strip would — which is right, because it is not a view of the same
 * thing, it is a different thing.
 */
@Composable
fun SegmentedButtons(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(TOGGLE_RADIUS),
                color = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.surface,
                border = if (selected) null else BorderStroke(1.dp, ZillitTheme.colors.border),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.defaultMinSize(minHeight = TOGGLE_HEIGHT),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            ZillitTheme.colors.textOnBrand
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * How many are chosen, with a way to undo it — the bar v2 shows under a picker button.
 *
 * Sits below the button rather than replacing its label so the way back into the picker
 * never moves.
 */
@Composable
fun CountBar(
    text: String,
    clearLabel: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FieldBox(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = clearLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.accentWarm,
            modifier = Modifier.clickable(onClick = onClear),
        )
    }
}
