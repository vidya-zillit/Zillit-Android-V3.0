package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.database.entity.EmailAttachmentEntity
import com.zillit.zillitapp.core.database.entity.EmailEntity
import com.zillit.zillitapp.core.database.entity.EmailFolderEntity
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailAttachment
import com.zillit.zillitapp.feature.email.domain.EmailContact
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.EmailGroup
import com.zillit.zillitapp.feature.email.domain.EmailSignature
import com.zillit.zillitapp.feature.email.domain.ForwardingSetting
import com.zillit.zillitapp.feature.email.domain.MailCredentials
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.domain.calculateThreadId
import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.ext.toRealmList

/**
 * Wire ↔ storage ↔ domain.
 *
 * Three representations rather than two because they answer different questions: the DTO is
 * the service's shape and cannot be changed, the entity is what Realm can index and query,
 * and the domain model is what the UI reasons about. Collapsing any pair would put a wire
 * quirk — `has_attachments` as a string, `to` as either an array or a delimited string —
 * into code that has no business knowing about it.
 */

// ── Wire → storage ───────────────────────────────────────────────────────────

fun EmailDto.toEntity(
    projectId: String,
    scope: MailboxScope,
    /** True only for `get-emails`, whose payload carries the body. */
    bodyLoaded: Boolean = false,
): EmailEntity = EmailEntity().also { entity ->
    val sender = EmailAddress.parse(from)

    entity.id = EmailEntity.primaryKey(projectId, scope.key, folderName, id)
    entity.projectId = projectId
    entity.mailboxScope = scope.key
    entity.folderName = folderName
    entity.messageId = id
    entity.uid = uid
    // Computed here, not read from `thread_id`: the server's value and this one disagree,
    // and the list groups by this one.
    entity.threadId = calculateThreadId(references, inReplyTo, id)
    entity.trailReferenceId = trailReferenceId
    entity.subject = subject
    entity.fromAddress = sender.address
    entity.fromName = sender.name
    entity.to = to.toRealmList()
    entity.cc = cc.toRealmList()
    entity.bcc = bcc.toRealmList()
    entity.body = body
    entity.text = text
    entity.isHtml = htmlEmail
    entity.read = read
    entity.createdAt = createdAt
    entity.attachmentCount = attachmentCount
    entity.references = references.toRealmList()
    entity.inReplyTo = inReplyTo
    entity.bodyLoaded = bodyLoaded

    // Inline parts are kept alongside the rest: the body's `cid:` references resolve
    // against them, and separating the two lists would mean carrying both everywhere.
    entity.attachments = (attachments + inlineAttachments)
        .map { it.toEntity() }
        .toRealmList()
}

internal fun AttachmentDto.toEntity(): EmailAttachmentEntity = EmailAttachmentEntity().also {
    it.attachmentId = attachmentId ?: id
    it.name = name
    it.media = media
    it.bucket = bucket
    it.region = region
    it.contentType = contentType
    it.contentId = contentId.orEmpty()
    // `size` is the honest one when present; some payloads only fill `content_length`.
    it.size = if (size > 0) size else contentLength
    it.contentDisposition = contentDisposition
}

/** A draft's attachments are mirrored from the domain model, which is what `drafts()` holds. */
internal fun EmailAttachment.toEntity(): EmailAttachmentEntity = EmailAttachmentEntity().also {
    it.attachmentId = attachmentId.ifBlank { id }
    it.name = name
    it.media = media
    it.bucket = bucket
    it.region = region
    it.contentType = contentType
    it.contentId = contentId
    it.size = size
    it.contentDisposition = contentDisposition
}

fun FolderDto.toEntity(projectId: String, scope: MailboxScope, order: Int): EmailFolderEntity =
    EmailFolderEntity().also {
        it.id = EmailFolderEntity.primaryKey(projectId, scope.key, folderName)
        it.projectId = projectId
        it.mailboxScope = scope.key
        it.folderId = folderId
        it.folderName = folderName
        it.systemDefined = systemDefined || folderName in EmailFolders.SYSTEM
        it.sortOrder = order
    }

// ── Storage → domain ─────────────────────────────────────────────────────────

/**
 * @param unread the badge tree's answer, which overrides the stored flag everywhere except
 *   Sent, Trash and Junk. Passed in rather than looked up so a list of two hundred rows
 *   resolves read state once instead of querying per row.
 */
fun EmailEntity.toDomain(unread: Boolean = !read): Email = Email(
    id = messageId,
    uid = uid,
    folderName = folderName,
    threadId = threadId,
    trailReferenceId = trailReferenceId,
    subject = subject,
    from = EmailAddress(address = fromAddress, name = fromName),
    to = EmailAddress.parseAll(to),
    cc = EmailAddress.parseAll(cc),
    bcc = EmailAddress.parseAll(bcc),
    body = body,
    text = text,
    isHtml = isHtml,
    read = !unread,
    createdAt = createdAt,
    attachments = attachments.map { it.toDomain() },
    attachmentCount = attachmentCount,
    references = references.toList(),
    inReplyTo = inReplyTo,
    pending = pending,
    sendError = sendError.takeIf { it.isNotBlank() },
    draftUniqueId = draftUniqueId,
)

private fun EmailAttachmentEntity.toDomain(): EmailAttachment = EmailAttachment(
    id = attachmentId,
    attachmentId = attachmentId,
    name = name,
    media = media,
    bucket = bucket,
    region = region,
    contentType = contentType,
    contentId = contentId,
    size = size,
    contentDisposition = contentDisposition,
)

fun EmailFolderEntity.toDomain(unreadCount: Int = 0): EmailFolder = EmailFolder(
    folderId = folderId,
    folderName = folderName,
    unreadCount = unreadCount,
    systemDefined = systemDefined,
)

// ── Wire → domain, for the things that are never cached ──────────────────────

/**
 * A draft, as an [Email].
 *
 * Drafts are API-only — no uids, so nothing for the folder sync to diff — which is why they
 * never reach Realm and why they are invisible offline and unsearchable. Marked read
 * because a draft you wrote is not news.
 */
fun DraftDto.toDomain(): Email = Email(
    id = id,
    folderName = EmailFolders.DRAFTS,
    threadId = id,
    subject = subject,
    from = EmailAddress.parse(from),
    to = to.map { EmailAddress.parse(it.emailAddress) },
    cc = cc.map { EmailAddress.parse(it.emailAddress) },
    bcc = bcc.map { EmailAddress.parse(it.emailAddress) },
    body = body,
    text = body.replace(HTML_TAG, " ").trim(),
    isHtml = true,
    read = true,
    // Last touched, under whichever name the payload used — a draft sorts by when it was
    // last written, not by when it was first started.
    createdAt = listOf(updated, updatedAt, created, createdAt).firstOrNull { it > 0 } ?: 0,
    attachments = (attachments + inlineAttachments).map {
        EmailAttachment(
            id = it.attachmentId ?: it.id,
            attachmentId = it.attachmentId ?: it.id,
            name = it.name,
            media = it.media,
            bucket = it.bucket,
            region = it.region,
            contentType = it.contentType,
            contentId = it.contentId.orEmpty(),
            size = if (it.size > 0) it.size else it.contentLength,
            contentDisposition = it.contentDisposition,
        )
    },
    draftUniqueId = uniqueId,
)

fun EmailDto.toDomain(): Email {
    val sender = EmailAddress.parse(from)

    return Email(
        id = id,
        uid = uid,
        folderName = folderName,
        threadId = calculateThreadId(references, inReplyTo, id),
        trailReferenceId = trailReferenceId,
        subject = subject,
        from = sender,
        to = EmailAddress.parseAll(to),
        cc = EmailAddress.parseAll(cc),
        bcc = EmailAddress.parseAll(bcc),
        body = body,
        text = text,
        isHtml = htmlEmail,
        read = read,
        createdAt = createdAt,
        attachmentCount = attachmentCount,
        references = references,
        inReplyTo = inReplyTo,
        attachments = (attachments + inlineAttachments).map {
            EmailAttachment(
                id = it.attachmentId ?: it.id,
                attachmentId = it.attachmentId ?: it.id,
                name = it.name,
                media = it.media,
                bucket = it.bucket,
                region = it.region,
                contentType = it.contentType,
                contentId = it.contentId.orEmpty(),
                size = if (it.size > 0) it.size else it.contentLength,
                contentDisposition = it.contentDisposition,
            )
        },
    )
}

fun ContactDto.toDomain(): EmailContact = EmailContact(
    id = id,
    firstName = firstName,
    lastName = lastName,
    contactName = contactName,
    emailAddress = emailAddress,
    companyName = companyName,
    phoneNumber = phoneNumber,
    countryCode = countryCode,
    address = address,
    city = city,
    state = state,
    zipCode = zipCode,
    country = country,
    notes = notes,
)

fun EmailContact.toDto(): ContactDto = ContactDto(
    id = id,
    firstName = firstName,
    lastName = lastName,
    contactName = contactName,
    emailAddress = emailAddress,
    companyName = companyName,
    phoneNumber = phoneNumber,
    countryCode = countryCode,
    address = address,
    city = city,
    state = state,
    zipCode = zipCode,
    country = country,
    notes = notes,
)

fun SignatureDto.toDomain(): EmailSignature = EmailSignature(
    id = id,
    name = title,
    content = body,
    useForNew = useForNew,
    useForReply = useForReply,
)

/**
 * A group.
 *
 * Disabled members are dropped — a member whose project access was revoked is no longer on
 * the group, and showing them would invite an edit that silently fails.
 */
fun EmailGroupDto.toDomain(): EmailGroup = EmailGroup(
    id = id,
    groupName = groupName,
    memberEmails = members.filter { it.enabled }.map { it.emailAddress },
    groupEmail = mailbox?.emailAddress.orEmpty(),
)

fun ForwardingDto.toDomain(): ForwardingSetting = ForwardingSetting(
    forwardTo = forwardTo.orEmpty(),
    enabled = enabled,
)

fun RevealedCredentialsDto.toDomain(): MailCredentials = MailCredentials(
    emailAddress = emailAddress,
    imapHost = imap?.host.orEmpty(),
    imapPort = imap?.port ?: 0,
    smtpHost = smtp?.host.orEmpty(),
    smtpPort = smtp?.port ?: 0,
    username = smtp?.username?.takeIf { it.isNotBlank() } ?: imap?.username.orEmpty(),
)

/** The plaintext password, preferring SMTP's — they are the same credential. */
val RevealedCredentialsDto.password: String
    get() = smtp?.password?.takeIf { it.isNotBlank() } ?: imap?.password.orEmpty()

internal val HTML_TAG = Regex("<[^>]*>")

/**
 * An attachment already on the server, as the request that references it.
 *
 * Nothing is uploaded: a draft's parts and a forwarded mail's are already stored, so the
 * send only has to point at them.
 */
fun EmailAttachment.toDto(): AttachmentDto = AttachmentDto(
    id = id,
    attachmentId = attachmentId,
    name = name,
    media = media,
    bucket = bucket,
    region = region,
    contentType = contentType,
    contentId = contentId,
    size = size,
    contentLength = size,
    contentDisposition = contentDisposition,
)
