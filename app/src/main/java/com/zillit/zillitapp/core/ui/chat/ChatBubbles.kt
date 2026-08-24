package com.zillit.zillitapp.core.ui.chat

import com.zillit.zillitapp.core.common.RelativeDay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import coil.compose.AsyncImage
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.chat.model.ChatAuthor
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.SendState
import kotlin.math.roundToInt

/**
 * One post: the root message, then every reply to it, inside a single card.
 *
 * Replies are **not** separate list rows. v2 hangs them off the parent as `comments`, and
 * a production thread depends on that — a reply three screens below the message it answers
 * is unreadable when several conversations are interleaved in one unit.
 *
 * Avatar and name are shown on every message, root and reply alike, rather than collapsed
 * for consecutive posts by the same person: the same author often posts a run of items and
 * it must stay obvious who each one is.
 */
@Composable
fun ChatPostRow(
    post: ChatFeedItem.Post,
    onRetry: (ChatMessage) -> Unit = {},
    /** Parent server id. Absent means the message has not been confirmed yet. */
    onReply: (String) -> Unit = {},
    /** Long press — the host decides which options apply and shows the sheet. */
    onLongPress: (ChatMessage) -> Unit = {},
    /** Tap on an attachment — opens the media viewer. */
    onOpenMedia: (ChatMessage) -> Unit = {},
    audio: AudioBubbleState = AudioBubbleState(),
    /** Non-null while a Delete or Share selection is running. */
    selection: ChatSelectionState? = null,
    onToggleSelection: (ChatMessage) -> Unit = {},
    /** Long press or the row's menu button on a **reply**. */
    onReplyOptions: ((ChatMessage) -> Unit)? = null,
    onOpenLocation: (ChatMessage.Location) -> Unit = {},
    /** Read-only surfaces (history) hide the Reply action. */
    isReadOnly: Boolean = false,
    /** Highlighted inside message text while an in-thread search is running. */
    searchQuery: String = "",
    /** True for the match currently stepped to, which gets a stronger tint. */
    isSearchHit: Boolean = false,
) {
    val selecting = selection != null
    val isSelected = selection?.contains(post.root.id) == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the hit target while selecting — asking for a small tick to
            // be hit precisely, repeatedly, is what makes a multi-select tedious.
            .then(
                if (selecting) {
                    Modifier.clickable { onToggleSelection(post.root) }
                } else {
                    Modifier
                },
            )
            .background(
                when {
                    isSelected -> ZillitTheme.colors.brandSoft
                    // The stepped-to match is tinted as a whole row, so it is findable
                    // after the scroll even when the matched word is off screen.
                    isSearchHit -> ZillitTheme.colors.accentSoft
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // Avatar and name form their own centred row above the bubble rather than the
        // avatar hanging off the bubble's top-left. Optically aligning a circle with the
        // cap-height of a single line of text is what makes the header read as one unit.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (selecting) {
                Icon(
                    imageVector = if (isSelected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (isSelected) {
                        ZillitTheme.colors.brand
                    } else {
                        ZillitTheme.colors.textTertiary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            AuthorHeader(author = post.root.author, avatarSize = ROOT_AVATAR_SIZE)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ROOT_AVATAR_SIZE + ZillitTheme.spacing.sm)
                .clip(RoundedCornerShape(ZillitTheme.shapes.large))
                .background(ZillitTheme.colors.surface)
                .border(
                    1.dp,
                    ZillitTheme.colors.border,
                    RoundedCornerShape(ZillitTheme.shapes.large),
                )
                // The whole bubble is the long-press target — v2 anchors its popup to the
                // row too, and hunting for a specific sub-view to press is not something
                // anyone should have to learn.
                .pointerInput(post.root.id, selecting) {
                    detectTapGestures(
                        // While selecting, both gestures mean the same thing: a long press
                        // that opened a second options sheet on top of a running selection
                        // would leave two conflicting actions in flight.
                        onLongPress = {
                            if (selecting) onToggleSelection(post.root) else onLongPress(post.root)
                        },
                        onTap = {
                            when {
                                selecting -> onToggleSelection(post.root)
                                // Tapping only means something for media; a text bubble
                                // has nothing to open.
                                post.root.hasOpenableMedia -> onOpenMedia(post.root)
                                // A pin opens in a maps app, which is the only thing you
                                // can usefully do with someone else's location.
                                post.root is ChatMessage.Location ->
                                    onOpenLocation(post.root as ChatMessage.Location)
                            }
                        },
                    )
                },
        ) {
            MessageBody(post.root, audio, searchQuery)

            // The rest of the batch, as a grid under the first file. Tapping one opens the
            // viewer on that file, so a batch behaves like an album rather than a stack
            // you have to expand first.
            if (post.batch.isNotEmpty()) {
                BatchGrid(
                    items = post.batch,
                    onOpen = { if (!selecting) onOpenMedia(it) },
                )
            }

            MessageFooter(post.root, onRetry)

            if (post.replies.isNotEmpty()) {
                ReplySection(
                    replies = post.replies,
                    onRetry = onRetry,
                    audio = audio,
                    // Suppressed while selecting: a selection acts on whole posts, and a
                    // menu opening over it would offer actions on a different target.
                    onReplyOptions = if (selecting) null else onReplyOptions,
                    onOpenMedia = onOpenMedia,
                )
            }

            // Reply is offered once the server has acknowledged the message and given
            // it an id — a reply needs something to attach to. Never on a read-only
            // surface: a replaced message is not a conversation you can continue.
            if (!isReadOnly) {
                post.rootServerId?.let { ReplyAction(onClick = { onReply(it) }) }
            }
        }
    }
}

/** The "Reply" affordance under a message. */
/**
 * Playback state as a bubble needs it.
 *
 * Passed down rather than each bubble reaching for the player: only one clip plays at a
 * time, so the state is inherently shared, and a per-bubble subscription would have every
 * row in the thread recomposing on every playback tick.
 */
data class AudioBubbleState(
    val playingId: String? = null,
    val progress: Float = 0f,
    val onToggle: (ChatMessage) -> Unit = {},
    val onSeek: (Float) -> Unit = {},
) {
    fun isPlaying(messageId: String) = playingId == messageId
    fun progressFor(messageId: String) = if (playingId == messageId) progress else 0f
}

/** Circular avatar and author name on one baseline, vertically centred against each other. */
@Composable
private fun AuthorHeader(
    author: ChatAuthor,
    avatarSize: androidx.compose.ui.unit.Dp,
    nameStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    modifier: Modifier = Modifier,
) {
    // The designation is a server label KEY (`director_label`), resolved here rather than
    // when the message was stored — otherwise a language change would need every cached
    // message rewritten.
    val role = author.role?.takeIf { it.isNotBlank() }?.asServerText()?.resolve()
    val label = if (role.isNullOrBlank()) author.name else "${author.name} ($role)"

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Avatar(author = author, size = avatarSize)
        Text(
            text = label,
            style = nameStyle,
            color = ZillitTheme.colors.brand,
        )
    }
}

@Composable
private fun MessageBody(
    message: ChatMessage,
    audio: AudioBubbleState = AudioBubbleState(),
    searchQuery: String = "",
) {
    when (message) {
        is ChatMessage.Text -> TextBody(message, searchQuery)
        is ChatMessage.Image -> ImageBody(message, searchQuery)
        is ChatMessage.Video -> VideoBody(message)
        is ChatMessage.Voice -> VoiceBody(
            message = message,
            isPlaying = audio.isPlaying(message.id),
            progress = audio.progressFor(message.id),
            onTogglePlay = { audio.onToggle(message) },
            onSeek = audio.onSeek,
        )
        is ChatMessage.Document -> DocumentBody(message)
        is ChatMessage.Location -> LocationBody(message)
    }
}

/**
 * The replies hanging off a post, drawn inside the parent card.
 *
 * A sunken background plus the count header is what separates them from the root without
 * a second card outline, which at this nesting depth reads as clutter.
 */
@Composable
private fun ReplySection(
    replies: List<ChatMessage>,
    onRetry: (ChatMessage) -> Unit,
    audio: AudioBubbleState = AudioBubbleState(),
    onReplyOptions: ((ChatMessage) -> Unit)? = null,
    onOpenMedia: (ChatMessage) -> Unit = {},
) {
    HorizontalDivider(color = ZillitTheme.colors.divider)

    Column(
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken),
    ) {
        Text(
            text = pluralStringResource(R.plurals.chat_reply_count, replies.size, replies.size),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.md,
                top = ZillitTheme.spacing.sm,
            ),
        )

        replies.forEachIndexed { index, reply ->
            if (index > 0) {
                HorizontalDivider(
                    color = ZillitTheme.colors.divider,
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md),
                )
            }
            ReplyRow(reply, onRetry, audio, onReplyOptions, onOpenMedia)
        }
    }
}

@Composable
private fun ReplyRow(
    reply: ChatMessage,
    onRetry: (ChatMessage) -> Unit,
    audio: AudioBubbleState = AudioBubbleState(),
    onReplyOptions: ((ChatMessage) -> Unit)? = null,
    onOpenMedia: (ChatMessage) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // A thin accent rail marks the nesting; the smaller avatar keeps a reply from
        // competing visually with the message it answers.
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = REPLY_AVATAR_SIZE)
                .background(ZillitTheme.colors.brand.copy(alpha = 0.4f), RoundedCornerShape(1.dp)),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            // v2 puts a small control on each reply row rather than relying on a long
            // press: the row is short and sits inside a card that already handles long
            // press for the parent, so an explicit target is the only unambiguous one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorHeader(
                    author = reply.author,
                    avatarSize = REPLY_AVATAR_SIZE,
                    nameStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                onReplyOptions?.let { open ->
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.chat_reply_options),
                        tint = ZillitTheme.colors.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { open(reply) },
                    )
                }
            }

            // Replies carry the same media types as roots — v2's reply adapter switches on
            // the identical set — so they reuse the same bodies rather than a text-only path.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = REPLY_AVATAR_SIZE + ZillitTheme.spacing.sm)
                    .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                    .background(ZillitTheme.colors.surface)
                    .pointerInput(reply.id) {
                        detectTapGestures(
                            onLongPress = { onReplyOptions?.invoke(reply) },
                            onTap = { if (reply.hasOpenableMedia) onOpenMedia(reply) },
                        )
                    },
            ) {
                MessageBody(reply, audio)
                MessageFooter(reply, onRetry)
            }
        }
    }
}

/** The "Reply" affordance under a message. */
@Composable
private fun ReplyAction(onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.chat_reply),
        style = MaterialTheme.typography.labelMedium,
        color = ZillitTheme.colors.brand,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.md,
                bottom = ZillitTheme.spacing.sm,
            ),
    )
}

@Composable
private fun Avatar(author: ChatAuthor, size: androidx.compose.ui.unit.Dp = 36.dp) {
    // The app's one avatar. This used to be a private initials-only copy, which is why a
    // crew member's photo never appeared on their messages.
    com.zillit.zillitapp.core.ui.components.UserAvatar(
        initials = author.initials,
        pictureKey = author.avatarUrl,
        thumbnailKey = author.avatarThumbnailKey,
        size = size,
    )
}

@Composable
private fun TextBody(message: ChatMessage.Text, searchQuery: String = "") {
    var expanded by remember(message.id) { mutableStateOf(false) }
    // v2 truncates long messages behind a "Read more" (`TEXT_VIEW_CHAR_LIMIT_SHOW`): a
    // pasted call sheet or a schedule dump otherwise fills the screen and buries every
    // message around it.
    val isLong = message.body.length > READ_MORE_LIMIT
    val shown = if (isLong && !expanded) message.body.take(READ_MORE_LIMIT) else message.body

    // Highlights (project codes, links) are styled inline so a code stays copyable as
    // part of the sentence rather than being split into a separate element.
    val annotated = buildAnnotatedString {
        val spans = buildList {
            addAll(message.highlights.map { it to false })
            // A search term wins over a code highlight where they overlap — you are
            // looking for it right now.
            if (searchQuery.isNotBlank()) add(searchQuery to true)
        }

        if (spans.isEmpty()) {
            append(shown)
        } else {
            val pattern = spans.joinToString("|") { Regex.escape(it.first) }
            val searchTerms = spans.filter { it.second }.map { it.first.lowercase() }.toSet()
            var lastEnd = 0
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(shown).forEach { match ->
                append(shown.substring(lastEnd, match.range.first))
                val isSearch = match.value.lowercase() in searchTerms
                withStyle(
                    if (isSearch) {
                        SpanStyle(
                            background = ZillitTheme.colors.brandSoft,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        SpanStyle(
                            color = ZillitTheme.colors.brand,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                ) { append(match.value) }
                lastEnd = match.range.last + 1
            }
            append(shown.substring(lastEnd))
        }
    }

    Column(
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            end = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.md,
        ),
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
        if (isLong) {
            Text(
                text = stringResource(
                    if (expanded) R.string.chat_read_less else R.string.chat_read_more,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.brand,
                modifier = Modifier
                    .padding(top = ZillitTheme.spacing.xxs)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** v2's `Constants.TEXT_VIEW_CHAR_LIMIT_SHOW`. */
private const val READ_MORE_LIMIT = 1000

@Composable
private fun ImageBody(message: ChatMessage.Image, searchQuery: String = "") {
    Column {
        // Local copy first, then the thumbnail, then the full file once downloaded —
        // see rememberAttachmentImage. A bubble is never blank while something is
        // available to show.
        val model by rememberAttachmentImage(
            remoteKey = message.remoteKey,
            thumbnailKey = message.thumbnail,
            fileName = message.remoteKey?.substringAfterLast('/').orEmpty(),
            localPath = message.localPath,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(ZillitTheme.colors.surfaceSunken),
        ) {
            AsyncImage(
                model = model,
                contentDescription = message.caption,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            UploadOverlay(message.sendState, Modifier.align(Alignment.Center))
        }
        // A caption is text the search has to be able to find, so it goes through the same
        // renderer as a text message rather than a plain Text.
        message.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            TextBody(
                message = ChatMessage.Text(
                    id = message.id,
                    author = message.author,
                    timestamp = message.timestamp,
                    body = caption,
                ),
                searchQuery = searchQuery,
            )
        }
    }
}

@Composable
private fun VideoBody(message: ChatMessage.Video) {
    if (message.thumbnail == null) {
        // Falls back to the file-row treatment, same as a document with no preview.
        FileRow(
            kind = "MP4",
            title = message.duration,
            subtitle = message.size,
            tint = ZillitTheme.colors.brandSoft,
            tintContent = ZillitTheme.colors.brand,
        )
        return
    }

    val model by rememberAttachmentImage(
        remoteKey = message.remoteKey,
        thumbnailKey = message.thumbnail,
        fileName = message.remoteKey?.substringAfterLast('/').orEmpty(),
        localPath = message.localPath,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .background(Color(0xFF13202E)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier.size(56.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(32.dp),
            )
        }

        UploadOverlay(message.sendState, Modifier.align(Alignment.Center))

        Text(
            text = "${message.duration} · ${message.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(ZillitTheme.spacing.sm)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun VoiceBody(
    message: ChatMessage.Voice,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(ZillitTheme.colors.brand, CircleShape)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.audio_pause else R.string.audio_play,
                ),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        val playedColor = ZillitTheme.colors.brand
        val remainingColor = ZillitTheme.colors.textTertiary
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                // Tapping the waveform seeks — the bar is the scrubber, which is what the
                // shape invites and what every voice-note UI does.
                .pointerInput(message.id) {
                    detectTapGestures { offset -> onSeek(offset.x / size.width) }
                },
        ) {
            val barWidth = 3.dp.toPx()
            val gap = size.width / message.waveform.size
            // Bars left of the playhead are filled, so the waveform doubles as the
            // progress indicator instead of needing a separate bar.
            val playedBars = (message.waveform.size * progress).toInt()
            message.waveform.forEachIndexed { index, amplitude ->
                val barHeight = size.height * amplitude
                val x = index * gap + gap / 2
                drawLine(
                    color = if (index < playedBars) playedColor else remainingColor,
                    start = Offset(x, (size.height - barHeight) / 2),
                    end = Offset(x, (size.height + barHeight) / 2),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        Text(
            text = message.duration,
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun DocumentBody(message: ChatMessage.Document) {
    val isPdf = message.fileKind.equals("PDF", ignoreCase = true)

    Column {
        // Only rendered when the backend actually has a page thumbnail. Types that can't
        // be previewed — spreadsheets, archives — collapse to the file row alone.
        message.thumbnail?.takeIf { it.isNotBlank() }?.let {
            DocumentPreview(message)
        }

        FileRow(
            kind = message.fileKind,
            title = message.fileName,
            subtitle = message.fileSize,
            tint = if (isPdf) ZillitTheme.colors.dangerSoft else ZillitTheme.colors.successSoft,
            tintContent = if (isPdf) ZillitTheme.colors.danger else ZillitTheme.colors.success,
        )
    }
}

/**
 * A document's page preview, fetched like any other attachment image.
 *
 * Fixed height so the file row never scrolls off the bubble.
 */
@Composable
private fun DocumentPreview(message: ChatMessage.Document) {
    val model by rememberAttachmentImage(
        remoteKey = null,
        thumbnailKey = message.thumbnail,
        fileName = message.fileName,
        localPath = message.localPath,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.sm)
            .clip(RoundedCornerShape(ZillitTheme.shapes.small))
            .background(Color.White)
            .height(PAGE_PREVIEW_HEIGHT),
    ) {
        AsyncImage(
            model = model,
            contentDescription = message.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A first-page thumbnail. Fixed height so the file row never scrolls off the bubble. */
@Composable
private fun PagePreview(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.sm)
            .clip(RoundedCornerShape(ZillitTheme.shapes.small))
            .background(Color.White)
            .height(PAGE_PREVIEW_HEIGHT)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        // Suggested body text, replaced by the real rendered page once PDF thumbnailing
        // is wired. The block keeps its shape either way.
        listOf(1f, 0.92f, 0.97f, 0.6f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(Color(0xFFE3E6EB), RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun FileRow(
    kind: String,
    title: String,
    subtitle: String,
    tint: Color,
    tintContent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(tint, RoundedCornerShape(ZillitTheme.shapes.small)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = kind,
                style = MaterialTheme.typography.labelSmall,
                color = tintContent,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        // No download button. Tapping the row already fetches the file and opens it, and
        // Save is in the long-press menu — a third affordance for the same thing only
        // made the row busier.
    }
}

@Composable
private fun LocationBody(message: ChatMessage.Location) {
    Column {
        // Map thumbnail placeholder: a pin over a flat tint reads as a map without
        // pulling in a tile SDK before the data layer exists.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    if (ZillitTheme.colors.isLight) Color(0xFFDCE7D5) else Color(0xFF24332A),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = ZillitTheme.colors.danger,
                modifier = Modifier.size(36.dp),
            )
        }
        Column(
            modifier = Modifier.padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.md,
                top = ZillitTheme.spacing.sm,
            ),
        ) {
            Text(
                text = message.placeName,
                style = MaterialTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
            )
            Text(
                text = message.address,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Timestamp plus send state.
 *
 * Three distinct presentations, because they mean different things to the sender:
 * an upload in flight shows a determinate ring and its percentage, a failure shows a
 * tappable retry, and everything else is the familiar tick marks.
 */
@Composable
private fun MessageFooter(
    message: ChatMessage,
    onRetry: (ChatMessage) -> Unit,
    showDeliveryReceipts: Boolean = false,
) {
    val state = message.sendState

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.md,
                top = ZillitTheme.spacing.xs,
                bottom = ZillitTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        // The percentage and the retry prompt are left-aligned against the timestamp, so
        // they read as a status line rather than crowding the corner.
    ) {
        when (state) {
            is SendState.Uploading -> UploadStatus(state.progress, Modifier.weight(1f))
            SendState.Failed -> RetryPrompt(
                modifier = Modifier.weight(1f),
                onClick = { onRetry(message) },
            )
            else -> Box(Modifier.weight(1f))
        }

        // v2 prefixes the timestamp with a red "Edited" for a changed message; keeping the
        // marker beside the time is what makes it read as a property of the message rather
        // than a separate line of chrome.
        if (message.isEdited) {
            Text(
                text = stringResource(R.string.edited),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.padding(end = ZillitTheme.spacing.xs),
            )
        }

        Text(
            text = message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )

        // Delivery ticks are deliberately absent here.
        //
        // A unit chat is a broadcast to everyone on the unit, so "delivered" and "read"
        // have no single meaning — they belong to C&C, where a message has one recipient.
        // Uploading and Failed still render above, because those are about *this* device.
        if (showDeliveryReceipts) {
            when (state) {
                null, is SendState.Uploading, SendState.Failed -> Unit
                SendState.Sending -> Tick(" ○", ZillitTheme.colors.textTertiary)
                SendState.Sent -> Tick(" ✓", ZillitTheme.colors.textTertiary)
                SendState.Delivered -> Tick(" ✓✓", ZillitTheme.colors.textTertiary)
                SendState.Read -> Tick(" ✓✓", ZillitTheme.colors.accent)
            }
        }
    }
}

/**
 * Shown over media while it uploads.
 *
 * A scrim plus a determinate ring, because a full-width photo makes the footer's small
 * indicator easy to miss — and the point of the progress is that the user can see the send
 * is still in flight rather than wondering whether it worked.
 */
@Composable
private fun UploadOverlay(state: SendState?, modifier: Modifier = Modifier) {
    val fraction = when (state) {
        is SendState.Uploading -> state.progress
        SendState.Sending -> null
        else -> return
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction == null) {
            // Queued but not yet transferring — indeterminate, because there is no
            // percentage to report and a stuck 0% reads as broken.
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        } else {
            CircularProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
            )
            Text(
                text = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun Tick(glyph: String, color: Color) {
    Text(text = glyph, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Paperclip, a determinate ring and the percentage — shown while a file is going up. */
@Composable
private fun UploadStatus(progress: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(14.dp),
        )
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(14.dp),
            color = ZillitTheme.colors.brand,
            trackColor = ZillitTheme.colors.border,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(
                R.string.chat_upload_percent,
                (progress.coerceIn(0f, 1f) * 100).roundToInt(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.brand,
        )
    }
}

/** Tappable failure state. The whole row is the target, not just the icon. */
@Composable
private fun RetryPrompt(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
            tint = ZillitTheme.colors.danger,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.chat_send_failed_retry),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
        )
    }
}

/**
 * The day separator's words, in the current locale.
 *
 * Lives beside the row that renders it rather than in the mapper: "Today"/"Yesterday" are
 * shipped copy, and resolving them in a ViewModel would freeze them in whatever language
 * was active when the feed was built.
 */
@Composable
fun RelativeDay.label(): String = when (this) {
    RelativeDay.Today -> stringResource(R.string.today)
    RelativeDay.Yesterday -> stringResource(R.string.yesterday)
    RelativeDay.Tomorrow -> stringResource(R.string.tomorrow)
    is RelativeDay.Other -> label
    RelativeDay.None -> ""
}

@Composable
fun DateSeparatorRow(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier
                .background(
                    ZillitTheme.colors.surfaceSunken,
                    RoundedCornerShape(ZillitTheme.shapes.pill),
                )
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        )
    }
}

/** Enough to read as a page without the file row scrolling off the bubble. */
private val PAGE_PREVIEW_HEIGHT = 150.dp

private val ROOT_AVATAR_SIZE = 36.dp

private val REPLY_AVATAR_SIZE = 26.dp

/**
 * The extra files of a multi-file post.
 *
 * A fixed three-column grid rather than a `LazyVerticalGrid`: this sits inside a
 * `LazyColumn`, which forbids nesting a lazy list in the same direction, and a batch is a
 * handful of items — laying them all out costs nothing.
 */
@Composable
private fun BatchGrid(
    items: List<ChatMessage>,
    onOpen: (ChatMessage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZillitTheme.spacing.sm,
                end = ZillitTheme.spacing.sm,
                bottom = ZillitTheme.spacing.xs,
            ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        items.chunked(BATCH_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                row.forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                            .background(ZillitTheme.colors.surfaceSunken)
                            .clickable { onOpen(item) },
                    ) {
                        BatchTile(item)
                    }
                }
                // Keeps the last row's tiles the same size as a full row's rather than
                // stretching two items across three columns.
                repeat(BATCH_COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BatchTile(item: ChatMessage) {
    val remoteKey = when (item) {
        is ChatMessage.Image -> item.remoteKey
        is ChatMessage.Video -> item.remoteKey
        is ChatMessage.Document -> item.remoteKey
        else -> null
    }
    val thumbnail = when (item) {
        is ChatMessage.Image -> item.thumbnail
        is ChatMessage.Video -> item.thumbnail
        is ChatMessage.Document -> item.thumbnail
        else -> null
    }
    val local = when (item) {
        is ChatMessage.Image -> item.localPath
        is ChatMessage.Video -> item.localPath
        is ChatMessage.Document -> item.localPath
        else -> null
    }
    val name = (item as? ChatMessage.Document)?.fileName
        ?: remoteKey?.substringAfterLast('/').orEmpty()

    val model by rememberAttachmentImage(
        remoteKey = remoteKey,
        thumbnailKey = thumbnail,
        fileName = name,
        localPath = local,
    )

    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // No preview available — a document without a rendered thumbnail. Its extension is
        // more useful than a generic file glyph.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = (item as? ChatMessage.Document)?.fileKind.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }

    if (item is ChatMessage.Video) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = ZillitTheme.colors.textOnBrand,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private const val BATCH_COLUMNS = 3
