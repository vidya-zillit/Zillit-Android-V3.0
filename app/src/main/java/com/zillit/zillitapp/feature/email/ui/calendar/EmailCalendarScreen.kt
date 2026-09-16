package com.zillit.zillitapp.feature.email.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.ZillitTopBar

/**
 * The calendar, reached from the email drawer — v2's `activity_email_calendar`.
 *
 * A header and the project calendar, and nothing else: v2's version is nineteen lines of XML
 * and a thirty-nine-line Activity that drops the shared `CalendarFragment` into a container.
 * There is no email-specific calendar behaviour and there never was.
 *
 * It exists because the email drawer's section nav has four entries and the calendar is one
 * of them. The content is a slot so this file stays free of the calendar's own wiring — the
 * caller hands in the same calendar screen the dashboard uses.
 *
 * (v2 also carries `fragment_email_calendar_wrapper.xml`, a near-identical orphan no code
 * references. Not ported.)
 */
@Composable
fun EmailCalendarScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ZillitTopBar(
            title = stringResource(R.string.email_calendar_title),
            onBackClick = onBack,
            onHelpClick = null,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
