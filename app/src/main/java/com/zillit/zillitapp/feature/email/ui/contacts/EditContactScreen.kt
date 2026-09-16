package com.zillit.zillitapp.feature.email.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.FieldBox
import com.zillit.zillitapp.core.ui.components.FieldLabel
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** The contact editor's form, field for field. */
data class ContactFormState(
    val firstName: String = "",
    val lastName: String = "",
    val company: String = "",
    val email: String = "",
    /** The country's display name. */
    val country: String = "",
    /** Its dial code — what actually gets saved alongside the phone number. */
    val countryCode: String = "",
    /**
     * Its ISO code.
     *
     * Separate from the dial code because the postcode lookup is keyed by country, not by
     * telephone prefix — and `+1` is the United States and Canada both.
     */
    val countryIso: String = "",
    val phone: String = "",
    val zipCode: String = "",
    val state: String = "",
    val city: String = "",
    val address: String = "",
    val notes: String = "",
    val emailError: String? = null,
    val phoneError: String? = null,
    val countryError: String? = null,
    val saving: Boolean = false,
) {
    /** The zip lookup needs a country to look the zip up *in*. */
    val canLookUpPostcode: Boolean get() = countryIso.isNotBlank() && zipCode.isNotBlank()
}

/**
 * Add or edit a contact — v2's `fragment_edit_contact`.
 *
 * Eleven fields in v2's order, paired the way v2 pairs them, because the pairing is what
 * makes the form one screen instead of two.
 *
 * Two things v2 gets wrong that are fixed here:
 *
 *  - **The zip lookup crashes.** `ContactListViewModel.countryId` is `lateinit` with no
 *    initialiser and is only ever assigned by the country picker's callback, while the
 *    lookup fires on the zip field losing focus and checks only that the zip is non-empty.
 *    Typing a postcode before picking a country throws `UninitializedPropertyAccessException`.
 *    Here the lookup is gated on [ContactFormState.canLookUpPostcode].
 *  - **Email validation is `contains("@")`**, so `a@` saves. See `isPlausibleEmail`.
 */
@Composable
fun EditContactScreen(
    form: ContactFormState,
    isNew: Boolean,
    onFormChange: (ContactFormState) -> Unit,
    onPickCountry: () -> Unit,
    onLookUpPostcode: () -> Unit,
    onCountryRequired: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ZillitTheme.colors.surface,
        topBar = {
            ZillitTopBar(
                title = stringResource(if (isNew) R.string.add_contact else R.string.edit_contact),
                onBackClick = onBack,
                onHelpClick = null,
                actions = {
                    Text(
                        text = stringResource(R.string.save),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (form.saving) {
                            ZillitTheme.colors.textTertiary
                        } else {
                            ZillitTheme.colors.brand
                        },
                        modifier = Modifier
                            .clickable(enabled = !form.saving, onClick = onSave)
                            .padding(ZillitTheme.spacing.sm),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                FormTextField(
                    value = form.firstName,
                    onValueChange = { onFormChange(form.copy(firstName = it)) },
                    label = stringResource(R.string.email_contact_first_name),
                    placeholder = stringResource(R.string.email_contact_first_name),
                    modifier = Modifier.weight(1f),
                )
                FormTextField(
                    value = form.lastName,
                    onValueChange = { onFormChange(form.copy(lastName = it)) },
                    label = stringResource(R.string.email_contact_last_name),
                    placeholder = stringResource(R.string.email_contact_last_name),
                    modifier = Modifier.weight(1f),
                )
            }

            FormTextField(
                value = form.company,
                onValueChange = { onFormChange(form.copy(company = it)) },
                label = stringResource(R.string.email_contact_company),
                placeholder = stringResource(R.string.email_contact_company),
            )

            FormTextField(
                value = form.email,
                onValueChange = { onFormChange(form.copy(email = it, emailError = null)) },
                label = stringResource(R.string.email_contact_email),
                placeholder = stringResource(R.string.email_contact_email),
                errorText = form.emailError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                CountryPickerField(
                    country = form.country,
                    error = form.countryError,
                    onClick = onPickCountry,
                    modifier = Modifier.width(140.dp),
                )
                FormTextField(
                    value = form.phone,
                    onValueChange = { onFormChange(form.copy(phone = it, phoneError = null)) },
                    label = stringResource(R.string.email_contact_phone),
                    placeholder = stringResource(R.string.email_contact_phone),
                    errorText = form.phoneError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                )
            }

            FormTextField(
                value = form.zipCode,
                onValueChange = { onFormChange(form.copy(zipCode = it)) },
                label = stringResource(R.string.email_contact_zip),
                placeholder = stringResource(R.string.email_contact_zip),
                // Looks the postcode up when the field is left, and only once there is a
                // country to look it up in.
                modifier = Modifier.onFocusChanged { focus ->
                    when {
                        focus.isFocused && form.countryIso.isBlank() -> onCountryRequired()
                        !focus.isFocused && form.canLookUpPostcode -> onLookUpPostcode()
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                FormTextField(
                    value = form.state,
                    onValueChange = { onFormChange(form.copy(state = it)) },
                    label = stringResource(R.string.email_contact_state),
                    placeholder = stringResource(R.string.email_contact_state),
                    modifier = Modifier.weight(1f),
                )
                FormTextField(
                    value = form.city,
                    onValueChange = { onFormChange(form.copy(city = it)) },
                    label = stringResource(R.string.email_contact_city),
                    placeholder = stringResource(R.string.email_contact_city),
                    modifier = Modifier.weight(1f),
                )
            }

            FormTextField(
                value = form.address,
                onValueChange = { onFormChange(form.copy(address = it)) },
                label = stringResource(R.string.email_contact_address),
                placeholder = stringResource(R.string.email_contact_address),
                singleLine = false,
                minLines = 3,
            )

            FormTextField(
                value = form.notes,
                onValueChange = { onFormChange(form.copy(notes = it)) },
                label = stringResource(R.string.email_contact_notes),
                placeholder = stringResource(R.string.email_contact_notes),
                singleLine = false,
                minLines = 3,
            )
        }
    }
}

/**
 * The country, which is also the dial code.
 *
 * One control, as in v2 — its layout has a second, commented-out country picker, and its
 * code binds both `setupCountryPicker()` and `setupCountryCodePicker()` to the same view, so
 * the second listener wins and there has only ever been one picker.
 */
@Composable
private fun CountryPickerField(
    country: String,
    error: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel(stringResource(R.string.country))

        FieldBox(
            onClick = onClick,
            borderColor = if (error != null) ZillitTheme.colors.danger else null,
        ) {
            Text(
                text = country.ifBlank { stringResource(R.string.country) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (country.isBlank()) {
                    ZillitTheme.colors.textTertiary
                } else {
                    ZillitTheme.colors.textPrimary
                },
                maxLines = 1,
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}
