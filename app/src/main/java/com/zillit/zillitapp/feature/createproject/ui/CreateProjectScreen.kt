package com.zillit.zillitapp.feature.createproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.zillit.zillitapp.core.common.ZillitUrls
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.rememberDictionaries
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.picker.CommonListItem
import com.zillit.zillitapp.core.ui.picker.CommonListPicker
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.window.contentMaxWidth
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding

@Composable
fun CreateProjectRoute(
    onBack: () -> Unit,
    onCreated: (projectName: String, projectCode: String) -> Unit,
    onHelpClick: () -> Unit,
    viewModel: CreateProjectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isCreated) {
        if (uiState.isCreated) {
            onCreated(
                uiState.createdProjectName.orEmpty(),
                uiState.createdProjectCode.orEmpty(),
            )
        }
    }

    CreateProjectScreen(
        uiState = uiState,
        onBack = onBack,
        onHelpClick = onHelpClick,
        onProjectNameChange = viewModel::onProjectNameChange,
        onFirstNameChange = viewModel::onFirstNameChange,
        onLastNameChange = viewModel::onLastNameChange,
        onEmailChange = viewModel::onEmailChange,
        onOtpChange = viewModel::onOtpChange,
        onTypeSelected = viewModel::onTypeSelected,
        onSubTypeSelected = viewModel::onSubTypeSelected,
        onLanguageSelected = viewModel::onLanguageSelected,
        onCountrySelected = viewModel::onCountrySelected,
        onPhoneChange = viewModel::onPhoneChange,
        onTermsChange = viewModel::onTermsChange,
        onSubmit = viewModel::submit,
        onVerifyEmail = viewModel::requestEmailVerification,
        onConfirmOtp = viewModel::confirmOtp,
        onDismissOtp = viewModel::dismissVerification,
        onResend = viewModel::resendOtp,
    )
}

@Composable
fun CreateProjectScreen(
    uiState: CreateProjectUiState,
    onBack: () -> Unit,
    onHelpClick: () -> Unit,
    onProjectNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onOtpChange: (String) -> Unit,
    onTypeSelected: (com.zillit.zillitapp.feature.createproject.data.ProjectType) -> Unit,
    onSubTypeSelected: (String) -> Unit,
    onLanguageSelected: (com.zillit.zillitapp.feature.createproject.data.Language) -> Unit,
    onCountrySelected: (com.zillit.zillitapp.core.preset.CountryCode) -> Unit,
    onPhoneChange: (String) -> Unit,
    onTermsChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onVerifyEmail: () -> Unit,
    onConfirmOtp: () -> Unit,
    onDismissOtp: () -> Unit,
    onResend: () -> Unit,
) {
    val windowSize = currentWindowSize()
    val maxWidth = windowSize.contentMaxWidth()
    // Every dictionary, not just labels: a key can live in any of the three.
    val labels = rememberDictionaries()

    var showTypePicker by remember { mutableStateOf(false) }
    var showSubTypePicker by remember { mutableStateOf(false) }

    if (showTypePicker) {
        CommonListPicker(
            title = stringResource(R.string.create_project_select_type),
            items = uiState.availableTypes.map {
                CommonListItem(id = it.id, title = it.nameKey, isLabelKey = true)
            },
            selectedIds = setOfNotNull(uiState.selectedType?.id),
            onSingleSelected = { item ->
                uiState.availableTypes.firstOrNull { it.id == item.id }?.let(onTypeSelected)
            },
            onDismiss = { showTypePicker = false },
        )
    }

    if (showSubTypePicker) {
        CommonListPicker(
            title = stringResource(R.string.create_project_select_sub_type),
            items = uiState.availableSubTypeKeys.map {
                CommonListItem(id = it, title = it, isLabelKey = true)
            },
            selectedIds = setOfNotNull(uiState.selectedSubTypeKey),
            onSingleSelected = { onSubTypeSelected(it.id) },
            onDismiss = { showSubTypePicker = false },
        )
    }

    var showLanguagePicker by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }

    if (showLanguagePicker) {
        CommonListPicker(
            title = stringResource(R.string.create_project_select_language),
            items = uiState.availableLanguages.map {
                CommonListItem(id = it.code, title = it.name)
            },
            selectedIds = setOfNotNull(uiState.selectedLanguage?.code),
            onSingleSelected = { item ->
                uiState.availableLanguages.firstOrNull { it.code == item.id }?.let(onLanguageSelected)
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (showCountryPicker) {
        CommonListPicker(
            title = stringResource(R.string.create_project_country),
            items = uiState.availableCountries.map {
                CommonListItem(id = it.dialCode, title = it.name, subtitle = it.dialCode)
            },
            selectedIds = setOfNotNull(uiState.selectedCountry?.dialCode),
            onSingleSelected = { item ->
                uiState.availableCountries.firstOrNull { it.dialCode == item.id }?.let(onCountrySelected)
            },
            onDismiss = { showCountryPicker = false },
        )
    }

    if (uiState.isVerifyingEmail) {
        OtpBottomSheet(
            email = uiState.email,
            otp = uiState.otp,
            onOtpChange = onOtpChange,
            onConfirm = onConfirmOtp,
            onResend = onResend,
            onDismiss = onDismissOtp,
            canConfirm = uiState.canConfirmOtp,
            isSubmitting = uiState.isSubmitting,
            error = uiState.error,
            canResend = uiState.canResend,
            resendCooldownSeconds = uiState.resendCooldownSeconds,
        )
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.create_project_title),
                onBackClick = onBack,
                onHelpClick = onHelpClick,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier,
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = windowSize.horizontalPadding(),
                        vertical = ZillitTheme.spacing.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                // Suppressed while the sheet is open: the sheet shows its own copy, and
                // duplicating it on the form leaves an error stranded behind the overlay.
                uiState.error?.takeIf { !uiState.isVerifyingEmail }?.let { error ->
                    Text(
                        text = error.toUiText().resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                ZillitTheme.colors.dangerSoft,
                                RoundedCornerShape(ZillitTheme.shapes.medium),
                            )
                            .padding(ZillitTheme.spacing.md),
                    )
                }

                    // Names side by side, matching v2 — they are short and pairing
                    // them keeps the form from reading as a long column of boxes.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        LabelledField(
                            label = stringResource(R.string.create_project_first_name),
                            value = uiState.firstName,
                            onValueChange = onFirstNameChange,
                            required = true,
                            modifier = Modifier.weight(1f),
                        )
                        LabelledField(
                            label = stringResource(R.string.create_project_last_name),
                            value = uiState.lastName,
                            onValueChange = onLastNameChange,
                            required = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    LabelledSelector(
                        label = stringResource(R.string.project_type),
                        value = uiState.selectedType?.nameKey?.let { labels.resolve(it) },
                        hint = stringResource(R.string.create_project_select_type),
                        required = true,
                        enabled = uiState.availableTypes.isNotEmpty(),
                        isLoading = uiState.isLoadingTypes,
                        onClick = { showTypePicker = true },
                    )

                    if (uiState.availableSubTypeKeys.isNotEmpty()) {
                        LabelledSelector(
                            label = stringResource(R.string.project_sub_type),
                            value = uiState.selectedSubTypeKey?.let { labels.resolve(it) },
                            hint = stringResource(R.string.create_project_select_sub_type),
                            required = false,
                            enabled = true,
                            onClick = { showSubTypePicker = true },
                        )
                    }

                    LabelledSelector(
                        label = stringResource(R.string.create_project_select_language),
                        value = uiState.selectedLanguage?.name,
                        hint = stringResource(R.string.create_project_select_language),
                        required = true,
                        enabled = uiState.availableLanguages.isNotEmpty(),
                        onClick = { showLanguagePicker = true },
                    )

                    LabelledField(
                        label = stringResource(R.string.create_project_name_hint),
                        value = uiState.projectName,
                        onValueChange = onProjectNameChange,
                        required = true,
                    )

                    // Country and phone are one logical input, so they share a row.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        LabelledSelector(
                            label = stringResource(R.string.create_project_country),
                            value = uiState.selectedCountry?.dialCode,
                            hint = stringResource(R.string.create_project_country),
                            required = false,
                            enabled = uiState.availableCountries.isNotEmpty(),
                            isLoading = uiState.isLoadingTypes,
                            onClick = { showCountryPicker = true },
                            modifier = Modifier.weight(0.9f),
                        )
                        LabelledField(
                            label = stringResource(R.string.create_project_phone),
                            value = uiState.phone,
                            onValueChange = onPhoneChange,
                            required = false,
                            keyboardType = KeyboardType.Phone,
                            modifier = Modifier.weight(1.1f),
                        )
                    }

                    LabelledField(
                        label = stringResource(R.string.create_project_email),
                        value = uiState.email,
                        onValueChange = onEmailChange,
                        required = true,
                        keyboardType = KeyboardType.Email,

                        // Inline action, matching v2: verification happens without leaving the

                        // form, so a user never loses what they have typed.

                        trailing = {

                            if (uiState.isEmailVerified) {

                                Icon(

                                    imageVector = Icons.Filled.CheckCircle,

                                    contentDescription = null,

                                    tint = ZillitTheme.colors.success,

                                    modifier = Modifier.size(20.dp),

                                )

                            } else {

                                Text(

                                    text = stringResource(R.string.create_project_verify),

                                    style = MaterialTheme.typography.labelLarge,

                                    color = if (uiState.canVerifyEmail) {

                                        ZillitTheme.colors.brand

                                    } else {

                                        ZillitTheme.colors.textTertiary

                                    },

                                    modifier = Modifier.clickable(

                                        enabled = uiState.canVerifyEmail,

                                        onClick = onVerifyEmail,

                                    ),

                                )

                            }

                        },
                    )

                    Text(
                        text = stringResource(R.string.create_project_email_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                    )

                    TermsRow(
                        accepted = uiState.termsAccepted,
                        onAcceptedChange = onTermsChange,
                    )

                    PrimaryButton(
                        text = stringResource(R.string.create_project_continue),
                        onClick = onSubmit,
                        enabled = uiState.canSubmitDetails,
                        loading = uiState.isSubmitting,
                        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
                    )
            }
        }
    }
}

/**
 * Field with its label above it.
 *
 * A label that only exists as placeholder text disappears the moment the user types, so
 * a half-filled form gives no clue what each box holds. Keeping it visible costs a line
 * and removes that problem.
 */
@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    required: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Optional inline action rendered at the field's trailing edge. */
    trailing: (@Composable () -> Unit)? = null,
    /** Shown beneath the field once it has content — see [FieldError]. */
    error: FieldError? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        FieldLabel(label = label, required = required)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    ZillitTheme.colors.surface,
                    RoundedCornerShape(ZillitTheme.shapes.medium),
                )
                .border(
                    // The border thickens and takes the brand colour on focus, so the
                    // active field is obvious without moving anything.
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) ZillitTheme.colors.brand else ZillitTheme.colors.border,
                    shape = RoundedCornerShape(ZillitTheme.shapes.medium),
                )
                .padding(horizontal = ZillitTheme.spacing.md, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = ZillitTheme.colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(ZillitTheme.colors.brand),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused },
                )
                trailing?.invoke()
            }
        }

        // Only after the user has typed: flagging an untouched field is noise.
        if (error != null && value.isNotEmpty()) {
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun FieldError.message(): String = stringResource(
    when (this) {
        FieldError.Required -> R.string.validation_required
        FieldError.NameLength -> R.string.validation_name_length
        FieldError.ProjectNameLength -> R.string.validation_project_name_length
        FieldError.PhoneRequired -> R.string.validation_phone_required
        FieldError.PhoneLength -> R.string.validation_phone_length
        FieldError.CountryRequired -> R.string.validation_country_required
    },
)

/** Read-only field that opens a picker — never a free-text substitute for a choice. */
@Composable
private fun LabelledSelector(
    label: String,
    value: String?,
    hint: String,
    required: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shows "Loading…" and blocks taps while the source list is in flight. */
    isLoading: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        FieldLabel(label = label, required = required)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    ZillitTheme.colors.surface,
                    RoundedCornerShape(ZillitTheme.shapes.medium),
                )
                .border(
                    1.dp,
                    ZillitTheme.colors.border,
                    RoundedCornerShape(ZillitTheme.shapes.medium),
                )
                .clickable(enabled = enabled && !isLoading, onClick = onClick)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = 14.dp),
        ) {
            // Only the label is shown above; repeating it inside printed
            // "Select Language*" twice. Empty until something is chosen.
            Text(
                text = when {
                    isLoading -> stringResource(R.string.loading)
                    else -> value.orEmpty()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
            )
        }
    }
}

/**
 * Field label.
 *
 * The `required` flag intentionally renders nothing: the approved copy already carries
 * its own asterisk ("First Name*"), so adding a second marker would print "First Name**".
 * The flag is kept because it documents which fields are mandatory at each call site and
 * is what `canSubmitDetails` mirrors.
 */
@Composable
private fun FieldLabel(label: String, required: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = ZillitTheme.colors.textSecondary,
    )
}

/**
 * Terms consent. Submit is disabled until this is ticked — v2 gates on it, and creating a
 * project having agreed to nothing is not something to ship.
 */
/**
 * Terms consent, with the two policy names as working links.
 *
 * The links are separate tap targets from the checkbox: tapping "Terms of Service" must
 * open the page, not silently toggle consent. Built with an annotated string so only the
 * policy names are tappable, rather than making the whole row a link and losing the
 * checkbox affordance.
 *
 * Submit stays disabled until the box is ticked — v2 gates on it, and creating a project
 * having agreed to nothing is not something to ship.
 */
@Composable
private fun TermsRow(accepted: Boolean, onAcceptedChange: (Boolean) -> Unit) {
    val uriHandler = LocalUriHandler.current

    val prefix = stringResource(R.string.create_project_terms_prefix)
    val terms = stringResource(R.string.create_project_terms)
    val and = stringResource(R.string.create_project_terms_and)
    val privacy = stringResource(R.string.create_project_privacy)

    val annotated = buildAnnotatedString {
        append("$prefix ")

        pushStringAnnotation(tag = TAG_URL, annotation = ZillitUrls.TERMS_OF_SERVICE)
        withStyle(
            SpanStyle(
                color = ZillitTheme.colors.accent,
                textDecoration = TextDecoration.Underline,
            ),
        ) { append(terms) }
        pop()

        append(" $and ")

        pushStringAnnotation(tag = TAG_URL, annotation = ZillitUrls.PRIVACY_POLICY)
        withStyle(
            SpanStyle(
                color = ZillitTheme.colors.accent,
                textDecoration = TextDecoration.Underline,
            ),
        ) { append(privacy) }
        pop()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = accepted,
            onCheckedChange = onAcceptedChange,
            colors = CheckboxDefaults.colors(checkedColor = ZillitTheme.colors.brand),
        )
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(
                color = ZillitTheme.colors.textSecondary,
            ),
            onClick = { offset ->
                val link = annotated
                    .getStringAnnotations(TAG_URL, offset, offset)
                    .firstOrNull()

                if (link != null) {
                    uriHandler.openUri(link.item)
                } else {
                    // Tapping the non-link words still toggles, so the whole row stays a
                    // usable target rather than only the small checkbox.
                    onAcceptedChange(!accepted)
                }
            },
        )
    }
}

private const val TAG_URL = "url"

/**
 * Verification code prompt.
 *
 * A bottom sheet rather than a dialog: it matches the app's other pickers, gives the
 * keyboard somewhere to push against, and is reachable one-handed. Dismissing keeps
 * everything already typed on the form.
 *
 * Errors raised here render *inside* the sheet — an error placed on the form behind it
 * is invisible until the sheet closes, which is exactly when it stops being relevant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpBottomSheet(
    email: String,
    otp: String,
    onOtpChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
    canConfirm: Boolean,
    isSubmitting: Boolean,
    error: com.zillit.zillitapp.core.network.ApiError?,
    canResend: Boolean,
    resendCooldownSeconds: Int,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.create_project_otp_title),
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
            )

            Text(
                text = stringResource(R.string.create_project_otp_description, email),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            error?.let {
                Text(
                    text = it.toUiText().resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            ZillitTheme.colors.dangerSoft,
                            RoundedCornerShape(ZillitTheme.shapes.medium),
                        )
                        .padding(ZillitTheme.spacing.md),
                )
            }

            LabelledField(
                label = stringResource(R.string.create_project_otp_hint),
                value = otp,
                onValueChange = onOtpChange,
                required = true,
                keyboardType = KeyboardType.NumberPassword,
            )

            // Greys out and shows the remaining seconds while cooling down, so the
            // control explains why it is unavailable rather than just ignoring taps.
            Text(
                text = if (canResend) {
                    stringResource(R.string.create_project_resend)
                } else {
                    stringResource(R.string.create_project_resend_in, resendCooldownSeconds)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (canResend) {
                    ZillitTheme.colors.accent
                } else {
                    ZillitTheme.colors.textTertiary
                },
                modifier = Modifier.clickable(enabled = canResend, onClick = onResend),
            )

            PrimaryButton(
                text = stringResource(R.string.create_project_verify),
                onClick = onConfirm,
                enabled = canConfirm,
                loading = isSubmitting,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        }
    }
}
