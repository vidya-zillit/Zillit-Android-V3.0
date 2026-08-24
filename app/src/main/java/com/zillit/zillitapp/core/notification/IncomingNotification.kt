package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.badge.BadgeModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A notification as it arrives, from either transport.
 *
 * FCM and the socket deliver the same events in slightly different envelopes, so both are
 * normalised into this before anything acts on them. That keeps the badge rules in one
 * place instead of being duplicated per transport, which is how the two drift apart.
 */
data class IncomingNotification(
    val uuid: String,
    val projectId: String,
    val module: BadgeModule,
    val scopeId: String,
    val section: String? = null,
    val tool: String? = null,
    val unit: String? = null,
    val action: String? = null,
    val level1: String? = null,
    val level2: String? = null,
    val level3: String? = null,
    val referenceId: String? = null,
    val chatRoomId: String? = null,
    val senderId: String? = null,
    val message: String? = null,
    /** True when [message] is AES-encrypted hex and must be decrypted before display. */
    val isMessageEncrypted: Boolean = false,
    /** `{search}` → `replacer` substitutions for the body. */
    val messageElements: List<TextElement> = emptyList(),
    /** Same, for the title. */
    val actionElements: List<TextElement> = emptyList(),
    /** Data-only push: update state, post nothing to the tray. */
    val isSilent: Boolean,
    /**
     * End of the event a calendar invitation refers to, or 0.
     *
     * Kept because the calendar's pending and expired counters are one set of unread
     * invitations split by whether their event has already happened.
     */
    val eventEndAt: Long = 0,
    val createdAt: Long,
    /**
     * Whether the server already considers this read.
     *
     * A notification read on web, or on the user's other phone, comes back from the badge
     * sync flagged read. Ignoring it — which this used to do — means every device the user
     * owns keeps its own private idea of what is unread, and the counts never agree.
     */
    val isRead: Boolean = false,
    /**
     * The server's `updated` stamp, which moves when the notification is read anywhere.
     * Falls back to [createdAt] for a payload that does not carry one.
     */
    val updatedAt: Long = 0,
    /** True when this came from the badge-sync feed rather than a push or the socket. */
    val fromSync: Boolean = false,

    /**
     * The control fields that decide whether this is news or an instruction.
     *
     * Carried through rather than flattened because the rules read better together — see
     * [ReferenceData.isInstruction] and [shouldStore].
     */
    val control: ReferenceData? = null,
) {
    /**
     * Whether this belongs in the notification store, and therefore in a badge.
     *
     * Mirrors v2's `insertOrUpdate` guards exactly, and all three matter:
     *
     *  - `ignore` — the backend's explicit "this is plumbing, not news".
     *  - `self` — the user's own action echoed back; nobody is notified of their own work.
     *  - an instruction payload — it carries ids to mark read or delete, so storing it as
     *    a notification makes the badge go **up** at the precise moment the user cleared
     *    it on another device.
     */
    val shouldStore: Boolean
        get() = control?.ignore != true &&
            control?.self != true &&
            control?.isInstruction != true
}

/**
 * A placeholder substitution, v2's `PathElements`.
 *
 * Server text is templated — `"Vidya Pixel{message_says}"` — and these fill the slots.
 * Sent separately so the template itself can be a translatable label key.
 */
@Serializable
data class TextElement(
    @SerialName("search") val search: String? = null,
    @SerialName("replacer") val replacer: String? = null,
)

@Serializable
data class ReferenceData(
    @SerialName("encrypted") val encrypted: Boolean? = null,
    @SerialName("messageElements") val messageElements: List<TextElement> = emptyList(),
    @SerialName("actionElements") val actionElements: List<TextElement> = emptyList(),

    /**
     * The backend's own "do not store this" marker.
     *
     * Set on payloads that exist to instruct the client rather than to tell the user
     * something. v2 returns immediately on it, before any badge work.
     */
    @SerialName("ignore") val ignore: Boolean? = null,

    /**
     * True when the signed-in user caused this themselves.
     *
     * You do not get notified about your own actions, so storing these inflates every
     * count by the user's own activity.
     */
    @SerialName("self") val self: Boolean? = null,

    /** The device that performed the action, for the self-sync echo. */
    @SerialName("self_device_id") val selfDeviceId: String? = null,

    /** Notifications another of this user's devices has read — mark these read here too. */
    @SerialName("read_notification_ids") val readNotificationIds: List<String> = emptyList(),

    /** Messages deleted elsewhere; the payload is an instruction, not a notification. */
    @SerialName("deleted_chat_ids") val deletedChatIds: List<String> = emptyList(),
    @SerialName("deleted_comment_ids") val deletedCommentIds: List<String> = emptyList(),
    /**
     * When the event a calendar notification is about ends.
     *
     * Confirmed against a live payload: it arrives as a top-level `expired`, with the same
     * value repeated inside `calendar_data.end_datetime`. It is **not** a bare
     * `end_datetime` at this level — reading that found nothing, so every calendar
     * notification looked like it had no event attached and never reached the pending or
     * expired counters.
     */
    @SerialName("expired") val expiresAt: Long? = null,

    /** The event itself, as much of it as the notification carries. */
    @SerialName("calendar_data") val calendarData: CalendarNotificationData? = null,

    /** Access revocations, same instruction-not-notification rule. */
    @SerialName("toolsNoViewAccess") val toolsNoViewAccess: List<String> = emptyList(),
    @SerialName("unitNoViewAccess") val unitNoViewAccess: List<String> = emptyList(),
    @SerialName("adminSettingsNoAccess") val adminSettingsNoAccess: List<String> = emptyList(),
) {
    /**
     * Whether this payload tells the client to **do** something rather than reporting news.
     *
     * v2 stores a notification only when every one of these arrays is empty. Anything
     * carrying one is a sync instruction — a read that happened on another device, a
     * delete, an access change — and storing it as a notification is what makes a badge
     * go **up** when the user has just cleared it somewhere else.
     */
    val isInstruction: Boolean
        get() = readNotificationIds.isNotEmpty() ||
            deletedChatIds.isNotEmpty() ||
            deletedCommentIds.isNotEmpty() ||
            toolsNoViewAccess.isNotEmpty() ||
            unitNoViewAccess.isNotEmpty() ||
            adminSettingsNoAccess.isNotEmpty()

    /**
     * When the event ends, from wherever this payload put it.
     *
     * Top-level `expired` in practice, with the same value repeated inside `calendar_data`.
     */
    val eventEndsAt: Long? get() = expiresAt ?: calendarData?.endDatetime
}

/**
 * The slice of an event a calendar notification carries inline.
 *
 * Enough to know when the event ends without fetching it, which is what lets the pending
 * and expired counters work offline.
 */
@Serializable
data class CalendarNotificationData(
    @SerialName("_id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("start_datetime") val startDatetime: Long? = null,
    @SerialName("end_datetime") val endDatetime: Long? = null,
    @SerialName("full_day") val fullDay: Boolean? = null,
)

/** Wire shape of the FCM `data` payload. Field names match v2's `NotificationDataModel`. */
@Serializable
data class NotificationPayload(
    @SerialName("notification_uuid") val notificationUuid: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("sender") val sender: String? = null,
    @SerialName("section") val section: String? = null,
    @SerialName("tool") val tool: String? = null,
    @SerialName("unit") val unit: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("chatRoomId") val chatRoomId: String? = null,
    @SerialName("reference_data") val referenceData: ReferenceData? = null,
    @SerialName("level_1") val level1: String? = null,
    @SerialName("level_2") val level2: String? = null,
    @SerialName("level_3") val level3: String? = null,
    @SerialName("created") val created: Long? = null,
    /**
     * Arrives as a real boolean over the socket but as the *string* "true"/"false" over
     * FCM, because an FCM data payload is `Map<String, String>`. Parsed leniently below.
     */
    @SerialName("silent") val silent: Boolean? = null,
    /** Set by the badge sync for anything already read elsewhere. */
    @SerialName("message_read") val messageRead: Boolean? = null,
    /** Bumped by the server whenever the notification changes — read included. */
    @SerialName("updated") val updated: Long? = null,
)

/**
 * Maps a payload onto the badge taxonomy.
 *
 * The `section`/`tool` pair is what the backend uses to say where a notification belongs;
 * this turns that into the module and scope the badge store is keyed on. Anything
 * unrecognised falls into [BadgeModule.NOTIFICATION] rather than being dropped — an
 * unknown notification should still tell the user something happened.
 */
fun NotificationPayload.toIncoming(json: Json): IncomingNotification? {
    val project = projectId?.takeIf { it.isNotBlank() } ?: return null

    val module = when {
        section.equals(SECTION_CHAT, ignoreCase = true) && !chatRoomId.isNullOrBlank() ->
            BadgeModule.GROUP_CHAT

        section.equals(SECTION_HOME, ignoreCase = true) -> BadgeModule.HOME_CHAT
        section.equals(SECTION_CALENDAR, ignoreCase = true) -> BadgeModule.CALENDAR
        section.equals(SECTION_EMAIL, ignoreCase = true) -> BadgeModule.EMAIL
        tool.equals(TOOL_DOC_DISTRIBUTION, ignoreCase = true) -> BadgeModule.DOC_DISTRIBUTION

        // A home unit change arrives as `section = global_label` with a `unit` id, so it
        // has to be recognised here rather than by section alone.
        action in ACTIONS_HOME_UNIT_LIFECYCLE -> BadgeModule.HOME_CHAT

        // `default_tool` is the backend's placeholder for "no tool", not a tool. Treating
        // it as one routed every global notification to the Tools tab.
        !tool.isNullOrBlank() && !tool.equals(TOOL_NONE, ignoreCase = true) -> BadgeModule.TOOLS
        else -> BadgeModule.NOTIFICATION
    }

    // Scope from the most specific identifier available, so counts land on the row the
    // user actually opens rather than all collapsing onto the project.
    val scope = chatRoomId?.takeIf { it.isNotBlank() }
        ?: unit?.takeIf { it.isNotBlank() }
        ?: tool?.takeIf { it.isNotBlank() }
        ?: project

    return IncomingNotification(
        // A missing uuid would break redelivery de-duplication, so synthesise a stable-ish
        // one from the ids we do have rather than dropping the notification.
        uuid = notificationUuid?.takeIf { it.isNotBlank() }
            ?: notificationId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString(),
        projectId = project,
        module = module,
        scopeId = scope,
        section = section,
        tool = tool,
        unit = unit,
        action = action,
        level1 = level1,
        level2 = level2,
        level3 = level3,
        referenceId = referenceId,
        chatRoomId = chatRoomId,
        senderId = sender,
        message = message,
        isMessageEncrypted = referenceData?.encrypted == true,
        messageElements = referenceData?.messageElements.orEmpty(),
        actionElements = referenceData?.actionElements.orEmpty(),
        isSilent = silent ?: false,
        eventEndAt = referenceData?.eventEndsAt ?: 0,
        createdAt = created ?: System.currentTimeMillis(),
        isRead = messageRead ?: false,
        updatedAt = updated ?: created ?: 0L,
        control = referenceData,
    )
}

// Section and tool values are label KEYS and carry a `_label` suffix — the payload sends
// "home_label", not "home". Matching the bare word silently matched nothing, so every
// Home-chat notification fell through to the tools branch and routed to the wrong screen.
private const val SECTION_CHAT = "cnc_label"
private const val SECTION_HOME = "home_label"
private const val SECTION_CALENDAR = "calendar_label"
private const val SECTION_EMAIL = "email_label"
private const val SECTION_TOOLS = "tools_label"
private const val TOOL_DOC_DISTRIBUTION = "document_distribution_label"

/** The backend's stand-in when a notification belongs to no tool. */
private const val TOOL_NONE = "default_tool"

/**
 * A unit's own lifecycle — created, renamed, removed.
 *
 * Deliberately not a `home_new_unit` prefix match: `home_new_unit_chat` shares it but means
 * "a message arrived", so a prefix fired a units refetch on every incoming message.
 */
internal val ACTIONS_HOME_UNIT_LIFECYCLE = setOf(
    "home_new_unit_created",
    "home_new_unit_updated",
    "home_new_unit_deleted",
)
