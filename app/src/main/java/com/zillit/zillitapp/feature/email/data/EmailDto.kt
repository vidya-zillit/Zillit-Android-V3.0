package com.zillit.zillitapp.feature.email.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * The mail service's wire shapes.
 *
 * `status == 1` is success and **the HTTP code is not what decides it** — a 200 carrying
 * `status: 0` is a failure, and `message` is a label key rather than a sentence. That is the
 * service's contract, not a choice, so [EmailEnvelope] is threaded through every call.
 */
@Serializable
data class EmailEnvelope<T>(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String = "",
    @SerialName("data") val data: T? = null,
) {
    /**
     * Success is `status == 1`, and the HTTP code does not decide it — a 200 carrying
     * `status: 0` is a failure whose `message` is a label key.
     *
     * Deliberately not backed by a `private companion object`: the serialization plugin
     * generates `serializer()` into the companion, and a private one makes it unreachable
     * from every inline call site — which compiles cleanly and then throws
     * `IllegalAccessError` on the first request.
     */
    val isSuccess: Boolean get() = status == STATUS_SUCCESS
}

private const val STATUS_SUCCESS = 1

// ── Folders ──────────────────────────────────────────────────────────────────

@Serializable
data class FolderDto(
    @SerialName("folder_id") val folderId: String = "",
    /**
     * May come back **fully qualified** — asking for `Vendors` can return `INBOX.Vendors`.
     * Always store what the server returned, never what was requested.
     */
    @SerialName("folder_name") val folderName: String = "",
    @SerialName("deleted") val deleted: Int = 0,
    @SerialName("system_defined") val systemDefined: Boolean = false,
)

@Serializable
data class FolderRequest(
    @SerialName("folder_name") val folderName: String,
    @SerialName("new_folder_name") val newFolderName: String? = null,
)

// ── Messages ─────────────────────────────────────────────────────────────────

@Serializable
data class FolderUidDto(@SerialName("uid") val uid: Int = 0)

@Serializable
data class FolderUidsRequest(@SerialName("folder_name") val folderName: String)

@Serializable
data class EmailIndexRequest(
    @SerialName("uids") val uids: List<Int>,
    @SerialName("folder_name") val folderName: String,
)

@Serializable
data class EmailIndexResponse(@SerialName("emails") val emails: List<EmailDto> = emptyList())

@Serializable
data class EmailTrailRequest(
    @SerialName("folder_name") val folderName: String,
    @SerialName("message_ids") val messageIds: List<String>,
)

/**
 * One message.
 *
 * Two wire quirks that are the service's, not a mistake to clean up:
 *  - **`has_attachments` is a string**, `"true"` or `"false"`.
 *  - **`to`/`cc`/`bcc`/`references` are sometimes an array and sometimes one
 *    comma-or-space-separated string**, which is what [FlexibleStringList] exists for.
 *
 * From `get-email-index` the `body` and `text` are empty — only `get-emails` fills them.
 */
@Serializable
data class EmailDto(
    @SerialName("id") val id: String = "",
    @SerialName("uid") val uid: Int = 0,
    @SerialName("trail_reference_id") val trailReferenceId: String = "",
    /** Parsed and then ignored: the thread id is computed locally. */
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("folder_name") val folderName: String = "",
    @SerialName("subject") val subject: String = "",
    @SerialName("from") val from: String = "",
    @Serializable(with = FlexibleStringList::class)
    @SerialName("to") val to: List<String> = emptyList(),
    @Serializable(with = FlexibleStringList::class)
    @SerialName("cc") val cc: List<String> = emptyList(),
    @Serializable(with = FlexibleStringList::class)
    @SerialName("bcc") val bcc: List<String> = emptyList(),
    @SerialName("body") val body: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("html_email") val htmlEmail: Boolean = false,
    @SerialName("read") val read: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("attachment_count") val attachmentCount: Int = 0,
    /** A string on the wire. Read through [hasAttachmentsFlag], never directly. */
    @SerialName("has_attachments") val hasAttachments: String = "false",
    @Serializable(with = FlexibleStringList::class)
    @SerialName("references") val references: List<String> = emptyList(),
    @SerialName("in_reply_to") val inReplyTo: String = "",
    @SerialName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    /** Inline images, referenced from the body by `cid:`. */
    @SerialName("ingrained_attachment") val inlineAttachments: List<AttachmentDto> = emptyList(),
) {
    val hasAttachmentsFlag: Boolean get() = hasAttachments.equals("true", ignoreCase = true)
}

@Serializable
data class AttachmentDto(
    @SerialName("id") val id: String = "",
    @SerialName("attachment_id") val attachmentId: String? = null,
    @SerialName("name") val name: String = "",
    /** The S3 object key. */
    @SerialName("media") val media: String = "",
    @SerialName("bucket") val bucket: String = "",
    @SerialName("region") val region: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("content_length") val contentLength: Long = 0,
    @SerialName("content_disposition") val contentDisposition: String = "attachment",
    @SerialName("size") val size: Long = 0,
)

@Serializable
data class MoveEmailsRequest(
    @SerialName("message_ids") val messageIds: List<String>,
    @SerialName("source_folder") val sourceFolder: String,
    @SerialName("target_folder") val targetFolder: String,
)

@Serializable
data class DeleteEmailsRequest(@SerialName("message_ids") val messageIds: List<String>)

@Serializable
data class AttachmentRequest(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("folder_name") val folderName: String,
)

@Serializable
data class AttachmentDataDto(
    @SerialName("base64_file_content") val base64: String = "",
)

// ── Sending and drafts ───────────────────────────────────────────────────────

@Serializable
data class RecipientDto(@SerialName("email_address") val emailAddress: String)

@Serializable
data class SendEmailRequest(
    @SerialName("to") val to: List<RecipientDto>,
    @SerialName("cc") val cc: List<RecipientDto> = emptyList(),
    @SerialName("bcc") val bcc: List<RecipientDto> = emptyList(),
    /** `Name <address>` form. */
    @SerialName("from") val from: String,
    @SerialName("subject") val subject: String,
    @SerialName("body") val body: String,
    @SerialName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    @SerialName("ingrained_attachment") val inlineAttachments: List<AttachmentDto> = emptyList(),
    /** Empty unless this send is finishing a draft, which the server then deletes. */
    @SerialName("email_draft_id") val draftId: String = "",
    /** **Space**-separated message ids, not an array. Threads the reply. */
    @SerialName("references") val references: String = "",
)

@Serializable
data class SaveDraftRequest(
    @SerialName("to") val to: List<RecipientDto> = emptyList(),
    @SerialName("cc") val cc: List<RecipientDto> = emptyList(),
    @SerialName("bcc") val bcc: List<RecipientDto> = emptyList(),
    @SerialName("from") val from: String = "",
    @SerialName("subject") val subject: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    @SerialName("ingrained_attachment") val inlineAttachments: List<AttachmentDto> = emptyList(),
    @SerialName("references") val references: String = "",
    /**
     * Idempotency key for `POST email-draft`, generated once per compose session.
     *
     * The server answers a replayed key with the draft the first accepted POST created
     * instead of creating a second one, which is what stops a lost response from leaving
     * the user with duplicates. Null on `PUT`, where the draft id already identifies the
     * record — v2 omits it there too.
     */
    @SerialName("unique_id") val uniqueId: String? = null,
)

@Serializable
data class DeleteDraftsRequest(@SerialName("draft_ids") val draftIds: List<String>)

@Serializable
data class DraftDto(
    @SerialName("_id") val id: String = "",
    @SerialName("to") val to: List<RecipientDto> = emptyList(),
    @SerialName("cc") val cc: List<RecipientDto> = emptyList(),
    @SerialName("bcc") val bcc: List<RecipientDto> = emptyList(),
    @SerialName("from") val from: String = "",
    @SerialName("subject") val subject: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    @SerialName("ingrained_attachment") val inlineAttachments: List<AttachmentDto> = emptyList(),
    /**
     * Timestamps, under both spellings the service uses.
     *
     * The wire carries bare `created` / `updated`; `created_at` / `updated_at` appear on
     * other shapes of the same record. Reading only the `_at` pair left every draft at
     * zero, so the folder had no order at all and the rows shuffled on each refresh — v2
     * declares both for the same reason.
     */
    @SerialName("created") val created: Long = 0,
    @SerialName("updated") val updated: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** The key the draft was created with. Empty on drafts made before keys existed. */
    @SerialName("unique_id") val uniqueId: String = "",
)

@Serializable
data class DraftListDto(@SerialName("drafts") val drafts: List<DraftDto> = emptyList())

// ── Contacts, signatures, groups, forwarding ─────────────────────────────────

@Serializable
data class ContactDto(
    @SerialName("_id") val id: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("contact_name") val contactName: String = "",
    @SerialName("email_address") val emailAddress: String = "",
    @SerialName("company_name") val companyName: String = "",
    @SerialName("phone_number") val phoneNumber: String = "",
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("address") val address: String = "",
    @SerialName("city") val city: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("zip_code") val zipCode: String = "",
    @SerialName("country") val country: String = "",
    @SerialName("notes") val notes: String = "",
)

/** Creating takes a **list**, even for one contact; updating takes a bare object. */
@Serializable
data class CreateContactsRequest(@SerialName("contacts") val contacts: List<ContactDto>)

@Serializable
data class SignatureDto(
    @SerialName("_id") val id: String = "",
    @SerialName("signature_title") val title: String = "",
    @SerialName("signature_body") val body: String = "",
    @SerialName("use_for_new_email") val useForNew: Boolean = false,
    @SerialName("use_for_reply_and_forward") val useForReply: Boolean = false,
    @SerialName("deleted") val deleted: Long = 0,
)

@Serializable
data class CreateSignatureRequest(
    @SerialName("signature_title") val title: String,
    @SerialName("signature_body") val body: String,
    @SerialName("use_for_new_email") val useForNew: Boolean = true,
    @SerialName("use_for_reply_and_forward") val useForReply: Boolean = true,
)

@Serializable
data class UpdateSignatureRequest(
    @SerialName("_id") val id: String,
    @SerialName("signature_title") val title: String,
    @SerialName("signature_body") val body: String,
)

/** Members come back as objects and go out as bare strings. The service's asymmetry. */
@Serializable
data class GroupMemberDto(
    @SerialName("email_address") val emailAddress: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
)

@Serializable
data class GroupMailboxDto(
    @SerialName("email_address") val emailAddress: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
data class EmailGroupDto(
    @SerialName("_id") val id: String = "",
    @SerialName("group_name") val groupName: String = "",
    @SerialName("members_email") val members: List<GroupMemberDto> = emptyList(),
    /** The address the group itself receives on — what a send to it is actually addressed to. */
    @SerialName("mail_box_detail") val mailbox: GroupMailboxDto? = null,
    @SerialName("deleted") val deleted: Long = 0,
)

@Serializable
data class CreateGroupRequest(
    @SerialName("group_name") val groupName: String,
    @SerialName("members_email") val members: List<String>,
)

@Serializable
data class UpdateGroupRequest(
    @SerialName("_id") val id: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("members_email") val members: List<String>,
)

@Serializable
data class ForwardingDto(
    /** Null or empty means forwarding is not set up, which is a normal state. */
    @SerialName("forward_to_email") val forwardTo: String? = null,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("created") val created: Long = 0,
    @SerialName("updated") val updated: Long = 0,
)

@Serializable
data class SaveForwardingRequest(
    @SerialName("forward_to_email") val forwardTo: String,
    @SerialName("enabled") val enabled: Boolean = true,
)

// ── Read receipts ────────────────────────────────────────────────────────────

@Serializable
data class ReadByRequest(@SerialName("message_id") val messageId: String)

@Serializable
data class ReadByEntryDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("read_at") val readAt: Long = 0,
)

@Serializable
data class ReadByDto(
    @SerialName("message_read_by") val read: List<ReadByEntryDto> = emptyList(),
    @SerialName("message_unread_by") val unread: List<ReadByEntryDto> = emptyList(),
)

// ── Credentials ──────────────────────────────────────────────────────────────

@Serializable
data class ConnectionDto(
    @SerialName("host") val host: String = "",
    @SerialName("port") val port: Int = 0,
    @SerialName("username") val username: String = "",
    /** Plaintext. Held only for the life of the sheet, never persisted. */
    @SerialName("password") val password: String = "",
)

@Serializable
data class RevealedCredentialsDto(
    @SerialName("email_address") val emailAddress: String = "",
    @SerialName("mailbox_vendor") val vendor: String = "",
    @SerialName("is_accounts_mailbox") val isAccountsMailbox: Boolean = false,
    @SerialName("smtp") val smtp: ConnectionDto? = null,
    @SerialName("imap") val imap: ConnectionDto? = null,
)

@Serializable
data class UpdatePasswordRequest(@SerialName("password") val password: String)

/**
 * BCC presets, as both endpoints take them.
 *
 * The personal one is a PATCH on the user and the shared one a PATCH on the project's
 * mailbox, but the body is the same shape — a complete replacement list, not a delta.
 */
@Serializable
data class BccPresetsRequest(
    @SerialName("bcc") val bcc: List<RecipientDto>,
)

@Serializable
data class ConversationViewRequest(
    @SerialName("conversation_view") val conversationView: Boolean,
)

/**
 * Reads a field that is either a JSON array of strings or one delimited string.
 *
 * `to`, `cc`, `bcc` and `references` all arrive both ways depending on the message, and a
 * strict list serializer fails the whole response on the second shape — so a single mail
 * with an odd header would blank an entire folder.
 */
object FlexibleStringList : KSerializer<List<String>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlexibleStringList")

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return emptyList()

        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> element.jsonArray.mapNotNull { item ->
                (item as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            }

            is JsonPrimitive -> element.content
                .split(',', ' ', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        val output = encoder as? JsonEncoder
            ?: return ListSerializer(String.serializer()).serialize(encoder, value)

        output.encodeJsonElement(JsonArray(value.map(::JsonPrimitive)))
    }
}
