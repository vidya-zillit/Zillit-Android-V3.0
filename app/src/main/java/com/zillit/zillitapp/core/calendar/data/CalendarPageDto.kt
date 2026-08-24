package com.zillit.zillitapp.core.calendar.data

import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The cursor-paged envelope the event **list** endpoints answer with.
 *
 * Different from the window fetch, which returns a bare array: Received, Created and
 * Declined all page, so their payload is `{data: {items, cursor, hasMore}}`. Decoding one
 * as the other fails outright — the shapes have nothing in common.
 */
@Serializable
data class PagedResponse<T>(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: PageDto<T>? = null,
)

@Serializable
data class PageDto<T>(
    @SerialName("items") val items: List<T> = emptyList(),
    @SerialName("cursor")
    @Serializable(with = CursorAsString::class)
    val cursor: String? = null,
    @SerialName("hasMore") val hasMore: Boolean = false,
)

/**
 * Reads a cursor that may arrive as a string **or** a number.
 *
 * The backend sorts by a timestamp on some lists and by an id on others, and returns the
 * cursor in whichever type that field has. Declaring it `String` fails on the numeric
 * form, which breaks paging on exactly the lists that need it most.
 */
object CursorAsString : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CursorAsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = json.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return when {
            primitive.isString -> primitive.content
            else -> primitive.longOrNull?.toString()
                ?: primitive.doubleOrNull?.toLong()?.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/**
 * One row of the Received list: my invitation, with the event attached.
 *
 * The invitation and the event are separate records server-side, and the list needs both —
 * the status to show and the event to describe.
 */
@Serializable
data class InvitationWithEventDto(
    @SerialName("_id") val id: String = "",
    @SerialName("event_id") val eventId: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String = "pending",
    @SerialName("rejection_reason") val rejectionReason: String = "",
    @SerialName("invited_by") val invitedBy: String = "",
    @SerialName("type") val type: String = "project_user",
    @SerialName("email") val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("status_overrides") val statusOverrides: List<StatusOverrideDto> = emptyList(),
    @SerialName("is_expired") val isExpired: Boolean = false,
    @SerialName("event") val event: EventDto? = null,
    @SerialName("_name") val name: String? = null,
) {
    fun toDomain(): InvitationWithEvent? = event?.let {
        InvitationWithEvent(
            invitationId = id,
            eventId = eventId,
            status = InvitationStatus.fromApi(status),
            isExpired = isExpired,
            reason = rejectionReason.takeIf { r -> r.isNotBlank() },
            event = it.toDomain(),
        )
    }
}

/** An invitation paired with the event it is for. */
data class InvitationWithEvent(
    val invitationId: String,
    val eventId: String,
    val status: InvitationStatus,
    val isExpired: Boolean,
    val reason: String?,
    val event: CalendarEvent,
)

/** One page of anything, with the cursor needed to ask for the next. */
data class Page<T>(
    val items: List<T>,
    val cursor: String?,
    val hasMore: Boolean,
)
