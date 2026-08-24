package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.feature.calendar.ui.form.EditScopeSheet

/**
 * Asks before deleting, and asks the right question.
 *
 * A one-off gets a plain confirm. A recurring series gets the scope choice instead —
 * "delete this event" and "delete every one of them" are wildly different outcomes, and a
 * yes/no dialog cannot tell them apart. v2 splits the same way.
 *
 * The scope defaults to the single occurrence, the least destructive of the three.
 */
@Composable
fun DeleteEventPrompt(
    event: CalendarEvent,
    onConfirm: (EditScope) -> Unit,
    onDismiss: () -> Unit,
) {
    if (event.isRecurring) {
        EditScopeSheet(
            title = stringResource(R.string.calendar_delete_scope_title),
            confirmLabel = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    } else {
        ZillitConfirmDialog(
            message = stringResource(R.string.calendar_delete_confirm),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            // A one-off has nothing to scope; the server expects `all` for it.
            onConfirm = { onConfirm(EditScope.ALL) },
            onDismiss = onDismiss,
        )
    }
}
