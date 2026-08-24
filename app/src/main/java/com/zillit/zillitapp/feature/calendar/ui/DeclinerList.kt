package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.Decliner
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import java.time.LocalDate

/**
 * Who turned an event down, and what they said about it.
 *
 * Only shown to the person who created the event — it is the answer to "why is nobody
 * coming", and a count alone does not answer it.
 *
 * A recurring event can be declined date by date, each with its own reason, so those are
 * listed individually rather than collapsed into one line.
 *
 * @param onRequestReason offered per person when they gave no reason. Sends them a message
 *   asking; null while the messaging module it needs does not exist.
 */
@Composable
fun DeclinerSection(
    decliners: List<Decliner>,
    totalCount: Int,
    modifier: Modifier = Modifier,
    onRequestReason: ((Decliner) -> Unit)? = null,
) {
    if (decliners.isEmpty() && totalCount <= 0) return

    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = ZillitTheme.colors.dangerSoft) {
                Text(
                    text = pluralStringResource(
                        R.plurals.calendar_declined_count,
                        totalCount,
                        totalCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Text(
                text = stringResource(
                    if (expanded) {
                        R.string.calendar_hide_decliners
                    } else {
                        R.string.calendar_show_decliners
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.brand,
                modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
            )
        }

        if (!expanded) return@Column

        decliners.forEach { decliner ->
            HorizontalDivider(color = ZillitTheme.colors.divider)
            DeclinerRow(decliner = decliner, onRequestReason = onRequestReason)
        }
    }
}

@Composable
private fun DeclinerRow(decliner: Decliner, onRequestReason: ((Decliner) -> Unit)?) {
    Column(modifier = Modifier.padding(vertical = ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(ZillitTheme.colors.dangerSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = decliner.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                )
            }

            Text(
                text = decliner.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ZillitTheme.spacing.sm),
            )

            // Only worth offering when they actually left nothing to read.
            if (decliner.reason.isNullOrBlank() && !decliner.hasAnyReason()) {
                onRequestReason?.let { request ->
                    Text(
                        text = stringResource(R.string.calendar_request_reason),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZillitTheme.colors.brand,
                        modifier = Modifier.clickable { request(decliner) },
                    )
                }
            }
        }

        decliner.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = "“$reason”",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 36.dp, top = ZillitTheme.spacing.xs),
            )
        }

        if (decliner.isPerOccurrence) {
            Column(
                modifier = Modifier.padding(start = 36.dp, top = ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                decliner.occurrences.forEach { occurrence ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        Text(
                            text = occurrence.date.asReadableDate(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textTertiary,
                        )
                        occurrence.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                            Text(
                                text = "“$reason”",
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = FontStyle.Italic,
                                color = ZillitTheme.colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Decliner.hasAnyReason(): Boolean =
    occurrences.any { !it.reason.isNullOrBlank() }

/**
 * `2026-08-22` → `22 Aug 2026`.
 *
 * Falls back to the raw value rather than dropping the row: an unparseable date still tells
 * the reader which day was declined.
 */
private fun String.asReadableDate(): String = runCatching {
    LocalDate.parse(this).format(DateTime.formatter(DateTime.PATTERN_DATE))
}.getOrDefault(this)
