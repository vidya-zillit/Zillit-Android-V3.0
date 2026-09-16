package com.zillit.zillitapp.feature.email.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailContact

/**
 * Saved contacts — v2's `fragment_contact_list` and `item_contact`.
 *
 * Server-side only; the device address book is never read, so nothing here needs the
 * contacts permission.
 *
 * v2 has **no search and no sorting** on this screen, which is fine at ten contacts and
 * unusable at two hundred. The shared [SearchField] costs one line here, so it is in.
 */
@Composable
fun ContactListScreen(
    contacts: List<EmailContact>,
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (EmailContact) -> Unit,
    onDelete: (EmailContact) -> Unit,
    onEmail: (EmailContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            Column {
                ZillitTopBar(
                    title = stringResource(R.string.email_contacts_title),
                    onBackClick = onBack,
                    onHelpClick = null,
                )
                if (contacts.isNotEmpty() || query.isNotEmpty()) {
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        modifier = Modifier.padding(ZillitTheme.spacing.lg),
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = ZillitTheme.colors.brand,
                contentColor = ZillitTheme.colors.textOnBrand,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonAdd,
                    contentDescription = stringResource(R.string.add_contact),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                loading -> LoadingState()

                contacts.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Contacts,
                    title = stringResource(R.string.email_contact_empty_title),
                    description = stringResource(R.string.email_contact_empty_subtitle),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = ZillitTheme.spacing.lg,
                        end = ZillitTheme.spacing.lg,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactCard(
                            contact = contact,
                            onEdit = { onEdit(contact) },
                            onDelete = { onDelete(contact) },
                            onEmail = { onEmail(contact) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(
    contact: EmailContact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEmail: () -> Unit,
) {
    SettingsGroup {
        Row(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            UserAvatar(initials = contact.initials(), size = 44.dp)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Underlined and tappable, because it is: it opens a new mail to them.
                Text(
                    text = contact.emailAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.brand,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onEmail),
                )

                ContactDetail(contact.companyName)
                ContactDetail(contact.formattedPhone)
                ContactDetail(contact.formattedAddress, maxLines = 2)
                ContactDetail(contact.notes, maxLines = 2)
            }

            Column(horizontalAlignment = Alignment.End) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onEdit),
                )
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = ZillitTheme.colors.danger,
                    modifier = Modifier
                        .padding(top = ZillitTheme.spacing.sm)
                        .size(22.dp)
                        .clickable(onClick = onDelete),
                )
            }
        }
    }
}

/** A detail line that simply is not there when the contact has no value for it. */
@Composable
private fun ContactDetail(value: String, maxLines: Int = 1) {
    if (value.isBlank()) return

    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = ZillitTheme.colors.textTertiary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun EmailContact.initials(): String = displayName.trim()
    .split(' ', '.', '_', '-')
    .filter { it.isNotBlank() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
    .joinToString("")
    .ifBlank { "?" }
