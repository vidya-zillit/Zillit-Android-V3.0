package com.zillit.zillitapp.core.ui.externaluser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.directory.ExternalUser
import com.zillit.zillitapp.core.directory.ProjectDepartment
import com.zillit.zillitapp.core.directory.ProjectDesignation
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.core.ui.components.DropdownField
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * What kind of outsider someone is.
 *
 * The server stores this as one string: a label key for the two known kinds, or whatever
 * was typed for anything else. v2 does the same, and the value is what the API keys off, so
 * the shape is fixed rather than a choice.
 */
enum class ExternalUserType(val key: String) {
    CREW_MEMBER("crew_member_label"),
    VENDOR("vender_label"),
    OTHERS("others"),
    ;

    companion object {
        /** Reads a stored value back: anything unrecognised was somebody's own wording. */
        fun of(value: String?): ExternalUserType = entries.firstOrNull { it.key == value } ?: OTHERS
    }
}

/**
 * Adding or editing someone outside the project.
 *
 * The same fields and the same rules as v2's Add/Update External User page, in one place so
 * the calendar's guest picker and the settings list cannot drift apart. Which fields are
 * even shown depends on the type: a crew member belongs to a department, a vendor does not,
 * and "Others" has to say what it is.
 *
 * @param existing the person being edited, or null to add someone new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalUserFormSheet(
    departments: List<ProjectDepartment>,
    countries: List<CountryCode>,
    designationsFor: (departmentId: String) -> List<ProjectDesignation>,
    onSave: (ExternalUser) -> Unit,
    onDismiss: () -> Unit,
    existing: ExternalUser? = null,
    initialEmail: String = "",
    initialName: String = "",
    saving: Boolean = false,
) {
    val context = LocalContext.current
    val labels = LocalLabels.current

    var fullName by rememberSaveable { mutableStateOf(existing?.fullName ?: initialName) }
    var email by rememberSaveable { mutableStateOf(existing?.email ?: initialEmail) }
    var gender by rememberSaveable { mutableStateOf(existing?.gender.orEmpty()) }
    var type by rememberSaveable {
        mutableStateOf(existing?.let { ExternalUserType.of(it.externalUserType) } ?: ExternalUserType.CREW_MEMBER)
    }
    var otherType by rememberSaveable {
        mutableStateOf(
            existing?.externalUserType
                ?.takeIf { ExternalUserType.of(it) == ExternalUserType.OTHERS }
                .orEmpty(),
        )
    }
    var departmentId by rememberSaveable { mutableStateOf(existing?.departmentId.orEmpty()) }
    var designationId by rememberSaveable { mutableStateOf(existing?.designationId.orEmpty()) }
    var dialCode by rememberSaveable { mutableStateOf(existing?.countryCode.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(existing?.phone.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val genders = stringArrayResource(R.array.gender_type2).toList()
    // Only a crew member sits inside the project's structure; a vendor or a one-off has no
    // department to belong to, so asking for one would be asking for a wrong answer.
    val needsDepartment = type == ExternalUserType.CREW_MEMBER
    val designations = remember(departmentId) {
        departmentId.takeIf { it.isNotBlank() }?.let(designationsFor).orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(
                    if (existing == null) R.string.add_external_user else R.string.update_external_user,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
            )

            FormTextField(
                label = stringResource(R.string.full_name),
                value = fullName,
                onValueChange = { fullName = it },
                placeholder = stringResource(R.string.full_name),
            )

            FormTextField(
                label = stringResource(R.string.email),
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.email),
                // Editing keeps the address fixed: it is how the server identifies someone,
                // and changing it would mean a different person, not a corrected one.
                enabled = existing == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            DropdownField(
                label = stringResource(R.string.gender),
                value = gender.replaceFirstChar { it.uppercase() },
                placeholder = stringResource(R.string.gender),
                options = genders,
                optionLabel = { it },
                onPick = { gender = it },
            )

            DropdownField(
                label = stringResource(R.string.user_type),
                value = when (type) {
                    ExternalUserType.OTHERS -> stringResource(R.string.others)
                    else -> labels.resolveLabel(type.key)
                },
                options = ExternalUserType.entries,
                optionLabel = {
                    when (it) {
                        ExternalUserType.OTHERS -> stringResource(R.string.others)
                        else -> labels.resolveLabel(it.key)
                    }
                },
                onPick = {
                    type = it
                    if (it != ExternalUserType.CREW_MEMBER) {
                        departmentId = ""
                        designationId = ""
                    }
                },
            )

            if (type == ExternalUserType.OTHERS) {
                FormTextField(
                    label = stringResource(R.string.enter_other_type),
                    value = otherType,
                    onValueChange = { otherType = it },
                    placeholder = stringResource(R.string.enter_other_type),
                )
            }

            if (needsDepartment) {
                DropdownField(
                    label = stringResource(R.string.select_department),
                    value = departments.firstOrNull { it.departmentId == departmentId }
                        ?.let { labels.resolveLabel(it.name) }
                        .orEmpty(),
                    placeholder = stringResource(R.string.select_department),
                    options = departments,
                    optionLabel = { labels.resolveLabel(it.name) },
                    onPick = {
                        departmentId = it.departmentId
                        // The old designation belonged to the old department.
                        designationId = ""
                    },
                )

                DropdownField(
                    label = stringResource(R.string.select_designation),
                    value = designations.firstOrNull { it.designationId == designationId }
                        ?.let { labels.resolveLabel(it.name) }
                        .orEmpty(),
                    placeholder = stringResource(R.string.select_designation),
                    options = designations,
                    optionLabel = { labels.resolveLabel(it.name) },
                    onPick = { designationId = it.designationId },
                    enabled = departmentId.isNotBlank(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = androidx.compose.ui.Alignment.Top,
            ) {
                DropdownField(
                    label = stringResource(R.string.country),
                    value = dialCode,
                    placeholder = stringResource(R.string.country),
                    options = countries,
                    optionLabel = { "${it.name} (${it.dialCode})" },
                    onPick = { dialCode = it.dialCode },
                    modifier = Modifier.weight(0.45f),
                )
                FormTextField(
                    label = stringResource(R.string.phone),
                    value = phone,
                    onValueChange = { phone = it },
                    placeholder = stringResource(R.string.phone),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(0.55f),
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                )
            }

            PrimaryButton(
                text = stringResource(if (existing == null) R.string.submit else R.string.update),
                onClick = {
                    val input = ExternalUserInput(
                        fullName = fullName.trim(),
                        email = email.trim(),
                        gender = gender.trim().lowercase(),
                        type = type,
                        otherType = otherType.trim(),
                        departmentId = departmentId,
                        designationId = designationId,
                        dialCode = dialCode.trim(),
                        phone = phone.trim(),
                    )

                    val problem = input.validate(needsDepartment)
                    error = problem?.let { context.getString(it) }
                    if (problem == null) {
                        onSave(input.toExternalUser(existing))
                    }
                },
                enabled = !saving,
                loading = saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ZillitTheme.spacing.sm),
            )
        }
    }
}

/** What the sheet collected, before it is known to be valid. */
private data class ExternalUserInput(
    val fullName: String,
    val email: String,
    val gender: String,
    val type: ExternalUserType,
    val otherType: String,
    val departmentId: String,
    val designationId: String,
    val dialCode: String,
    val phone: String,
)

/**
 * v2's rules, in v2's order, so the same entry is rejected for the same reason.
 *
 * Phone and country are checked as a pair: one without the other is not a number anyone can
 * dial, and the server stores them separately.
 */
private fun ExternalUserInput.validate(needsDepartment: Boolean): Int? = when {
    fullName.isBlank() -> R.string.p_enter_full_name
    fullName.any { it in SPECIAL_CHARACTERS } -> R.string.p_enter_valid_input_special_char_msg
    email.isBlank() -> R.string.p_enter_email
    !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.p_enter_valid_email
    type == ExternalUserType.OTHERS && otherType.isBlank() -> R.string.user_type_required
    needsDepartment && departmentId.isBlank() -> R.string.please_select_department
    needsDepartment && designationId.isBlank() -> R.string.please_select_designation
    phone.isNotBlank() && dialCode.isBlank() -> R.string.p_choose_country
    dialCode.isNotBlank() && phone.isBlank() -> R.string.p_enter_valid_phone_number
    else -> null
}

private fun ExternalUserInput.toExternalUser(existing: ExternalUser?) = ExternalUser(
    id = existing?.id.orEmpty(),
    fullName = fullName,
    email = email,
    phone = phone.takeIf { it.isNotBlank() },
    countryCode = dialCode.takeIf { it.isNotBlank() },
    gender = gender.takeIf { it.isNotBlank() },
    departmentId = departmentId.takeIf { it.isNotBlank() },
    designationId = designationId.takeIf { it.isNotBlank() },
    // "Others" is stored as the words themselves — the server has no key for it.
    externalUserType = if (type == ExternalUserType.OTHERS) otherType else type.key,
)

private const val SPECIAL_CHARACTERS = "!@#$%^&*()_+=[]{}|\\;:\"<>/?~`"
