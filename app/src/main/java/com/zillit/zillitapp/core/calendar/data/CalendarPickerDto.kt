package com.zillit.zillitapp.core.calendar.data

import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.calendar.model.OccurrenceStatuses
import com.zillit.zillitapp.core.calendar.model.StatusOverride
import com.zillit.zillitapp.core.calendar.model.Timezone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supporting wire shapes: the timezone list, per-occurrence invitation statuses, and the
 * standalone invitation list.
 *
 * Same rule as [EventDto] — every field defaults, because these endpoints trim their
 * responses and a required field turns a successful 200 into a decode failure.
 */

@Serializable
data class TimezoneListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<TimezoneDto> = emptyList(),
)

@Serializable
data class TimezoneDto(
    /** IANA zone id, e.g. `Asia/Kolkata`. This is what an event stores. */
    @SerialName("identifier") val identifier: String = "",
    /** What the picker shows, e.g. `(GMT+05:30) India Standard Time`. */
    @SerialName("value") val value: String = "",
) {
    fun toDomain(): Timezone = Timezone(id = identifier, label = value.ifBlank { identifier })
}

@Serializable
data class OccurrenceStatusesResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: OccurrenceStatusesDto? = null,
)

@Serializable
data class OccurrenceStatusesDto(
    /** The answer given for the series as a whole, if any. */
    @SerialName("masterStatus") val masterStatus: String? = null,
    @SerialName("statusOverrides") val statusOverrides: List<StatusOverrideDto> = emptyList(),
    /** Occurrence date (as the server keys it) to the status answered for that date. */
    @SerialName("occurrenceStatuses") val occurrenceStatuses: Map<String, String> = emptyMap(),
) {
    fun toDomain(): OccurrenceStatuses = OccurrenceStatuses(
        masterStatus = masterStatus?.let { InvitationStatus.fromApi(it) },
        statusOverrides = statusOverrides.map { it.toDomain() },
        occurrenceStatuses = occurrenceStatuses.mapValues { InvitationStatus.fromApi(it.value) },
    )
}

@Serializable
data class StatusOverrideDto(
    @SerialName("status") val status: String = "",
    /** The occurrence the override applies from. */
    @SerialName("from") val from: String = "",
) {
    fun toDomain(): StatusOverride = StatusOverride(
        status = InvitationStatus.fromApi(status),
        from = from,
    )
}

/** The `invitationlist` endpoint — every invitee on one event, with their status. */
@Serializable
data class InvitationListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<InvitationDto> = emptyList(),
)
