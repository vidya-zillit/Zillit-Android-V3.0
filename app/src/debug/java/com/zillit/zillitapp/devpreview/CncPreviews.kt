package com.zillit.zillitapp.devpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import coil.compose.AsyncImage
import com.zillit.zillitapp.core.ui.chat.rememberAttachmentImage
import java.io.File
import java.io.FileOutputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.zillitapp.core.common.RelativeDay
import com.zillit.zillitapp.core.ui.chat.model.ChatAuthor
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.SendState
import com.zillit.zillitapp.core.ui.chat.ChatThread
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.cnc.ui.group.CreateGroupScreen
import com.zillit.zillitapp.feature.cnc.ui.list.ContactRow
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationKind
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadScreen
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadUiState
import com.zillit.zillitapp.feature.home.ui.HomePreviewData

/**
 * Debug-only screen renders, launched straight from adb.
 *
 * `ui-tooling` puts `androidx.compose.ui.tooling.PreviewActivity` in every debug build, and
 * it invokes a no-argument composable by name:
 *
 * ```
 * adb shell am start -n <applicationId>/androidx.compose.ui.tooling.PreviewActivity \
 *   -e composable com.zillit.zillitapp.devpreview.CncPreviewsKt.DirectThreadPreview
 * ```
 *
 * That is how a screen behind a signed-in project gets looked at without signing in — and
 * why this file lives in `src/debug`: none of it is compiled into a release build.
 */
/**
 * Pushes a preview below `PreviewActivity`'s own action bar.
 *
 * Only the host needs this. In the app the nav host sits under `MainActivity`, which
 * applies the top inset once for every screen and has no action bar at all — so a screen
 * whose first element is its own header (the thread) would otherwise be drawn underneath
 * the preview chrome and look as though the header were missing.
 */
@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    ZillitTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Status bar, then the action bar's own height: the host draws content
                // from the top of the window, under both.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 56.dp),
        ) {
            content()
        }
    }
}

@Composable
fun DirectThreadPreview() = PreviewHost {
    var state by remember { mutableStateOf(directThread()) }

    CncThreadScreen(
        state = state,
        onBack = {},
        onOpenDetails = {},
        onAttachments = {},
        onAudioCall = {},
        onVideoCall = {},
        onSend = { _, _ -> },
        onAttachmentPicked = {},
        onDraftChanged = { state = state.copy(draft = it) },
        onReply = {},
        onCancelReply = {},
        onLongPress = {},
        onOpenMedia = {},
        onReact = { _, _ -> },
        onOpenQuoted = {},
        jumpToId = null,
        onJumpHandled = {},
        onCancelUpload = {},
        onOpenLocation = {},
        onRetry = {},
        onLoadOlder = {},
        onReachedBottom = {},
        onUnblock = {},
    )
}

@Composable
fun GroupThreadPreview() = PreviewHost {
    var state by remember { mutableStateOf(groupThread()) }

    CncThreadScreen(
        state = state,
        onBack = {},
        onOpenDetails = {},
        onAttachments = {},
        onAudioCall = {},
        onVideoCall = {},
        onSend = { _, _ -> },
        onAttachmentPicked = {},
        onDraftChanged = { state = state.copy(draft = it) },
        onReply = {},
        onCancelReply = {},
        onLongPress = {},
        onOpenMedia = {},
        onReact = { _, _ -> },
        onOpenQuoted = {},
        jumpToId = null,
        onJumpHandled = {},
        onCancelUpload = {},
        onOpenLocation = {},
        onRetry = {},
        onLoadOlder = {},
        onReachedBottom = {},
        onUnblock = {},
    )
}

@Composable
fun CreateGroupPreview() = PreviewHost {
    var name by remember { mutableStateOf("Day 14 — unit") }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(previewContacts.take(3)) }

    CreateGroupScreen(
        name = name,
        onNameChange = { name = it },
        query = query,
        onQueryChange = { query = it },
        contacts = previewContacts.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        },
        selected = picked,
        onToggle = { contact ->
            picked = if (picked.any { it.id == contact.id }) {
                picked.filterNot { it.id == contact.id }
            } else {
                picked + contact
            }
        },
        onPickPhoto = {},
        onBack = {},
        onCreate = {},
    )
}

/**
 * A unit chat, for comparison.
 *
 * Here so a change to the shared [com.zillit.zillitapp.core.ui.chat.ChatThread] can be
 * checked against the surface it was NOT meant to change: every post left-aligned under
 * its author, no ticks, no sides.
 */
@Composable
fun UnitChatPreview() = ZillitTheme {
    ChatThread(
        feed = HomePreviewData.feedFor("main"),
        units = HomePreviewData.units,
        selectedUnitId = "main",
    )
}

private val me = ChatAuthor(id = "me", name = "You")

private fun post(message: ChatMessage) =
    ChatFeedItem.Post(root = message, rootServerId = message.id)

private fun directThread(): CncThreadUiState {
    val them = ChatAuthor(id = "u1", name = "Sahil Kashyap")

    return CncThreadUiState(
        id = "u1",
        kind = ConversationKind.DIRECT,
        title = "Sahil Kashyap",
        subtitle = "Sep 12, 2026 · 11:21 AM",
        initials = "SK",
        online = true,
        typing = listOf("Sahil"),
        feed = listOf(
            ChatFeedItem.Day(RelativeDay.Yesterday),
            post(
                ChatMessage.Text(
                    id = "d1", author = them, timestamp = "09:13 PM",
                    body = "Email aur production ho gaya?",
                ),
            ),
            post(
                ChatMessage.Text(
                    id = "d2", author = me, timestamp = "09:14 PM", isOwn = true,
                    sendState = SendState.Read,
                    body = "Ha sabka ho gaya hai but kisi ka testing nhe hua hai",
                ),
            ),
            post(
                ChatMessage.Text(
                    id = "d3", author = them, timestamp = "09:20 PM",
                    body = "Abhi to kal hi build daluga",
                ),
            ),
            post(
                ChatMessage.Text(
                    id = "d4", author = me, timestamp = "11:36 PM", isOwn = true,
                    sendState = SendState.Delivered,
                    body = "Product report and call sheet ye wale branch me hai",
                ),
            ),
        ),
    )
}

private fun groupThread(): CncThreadUiState {
    val shamsher = ChatAuthor(id = "u8", name = "Shamsher Singh", role = "Transportation")
    val randeep = ChatAuthor(id = "u9", name = "Randeep Singh", role = "Director")

    return CncThreadUiState(
        id = "g1",
        kind = ConversationKind.GROUP,
        title = "Zillit Team",
        subtitle = "22 members · View users",
        initials = "ZT",
        typing = listOf("Rudra"),
        readCounts = mapOf("g3" to 11),
        feed = listOf(
            ChatFeedItem.Day(RelativeDay.Yesterday),
            post(
                ChatMessage.Text(
                    id = "g1m", author = shamsher, timestamp = "02:59 PM",
                    body = "Make sure all are available on video call",
                ),
            ),
            post(
                ChatMessage.Document(
                    id = "g2", author = randeep, timestamp = "03:42 PM",
                    fileName = "Day 14 — shooting schedule.pdf",
                    fileSize = "1.2 MB", fileKind = "pdf",
                ),
            ),
            post(
                ChatMessage.Text(
                    id = "g3", author = me, timestamp = "04:06 PM", isOwn = true,
                    sendState = SendState.Read,
                    body = "Wrap at 7, transport from base camp",
                ),
            ),
        ),
    )
}

private val previewContacts = listOf(
    ContactRow("u1", "Sahil Kashyap", "Transportation manager", "SK", online = true),
    ContactRow("u6", "Sahil Samsung", "Transportation coordinator", "SS", isProjectAdmin = true),
    ContactRow("u2", "Sanjeev Khanna", "Production accountant", "SK", isProjectAdmin = true),
    ContactRow("u7", "Shagun Sharma", "Art director", "SS"),
    ContactRow("u8", "Shamsher Singh", "Transportation coordinator", "SS", isProjectAdmin = true),
    ContactRow("u5", "Shubham Singh", "Screen writer", "SS"),
)

/**
 * A scrolling list of avatars, each with its own colour, as a regression guard.
 *
 * Forty rows, each colour written to the cache as a real file and handed to the avatar as a
 * local path — the same code path a picture takes while it uploads. Every row must keep its
 * own colour however far it is scrolled.
 *
 * This one **passes without** the stale-value reset in `rememberAttachmentImage`, and that
 * is worth knowing: a lazy list recycling a row does not carry the previous person's photo
 * over, because Compose resets remembered state when it reuses a slot. The v2 symptom was a
 * RecyclerView problem and does not transfer. [AvatarInPlaceSwapPreview] covers the case
 * that does go wrong here.
 */
@Composable
fun AvatarRecyclingPreview() = ZillitTheme {
    val context = LocalContext.current
    val swatches = remember { context.writeSwatches() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = swatches.size, key = { it }) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val picture by rememberAttachmentImage(
                    remoteKey = null,
                    thumbnailKey = null,
                    fileName = "",
                    localPath = swatches[index],
                )
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = picture,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text("Row $index — swatch $index")
            }
        }
    }
}

/** One solid-colour PNG per row, written once into the cache. */
private fun Context.writeSwatches(): List<String> = (0 until 40).map { index ->
    val file = File(cacheDir, "swatch_$index.png")
    if (!file.exists()) {
        val hue = (index * 360f / 40f)
        val bitmap = createBitmap(48, 48)
        Canvas(bitmap).drawColor(AndroidColor.HSVToColor(floatArrayOf(hue, 0.75f, 0.9f)))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    file.absolutePath
}

/**
 * The case the `rememberAttachmentImage` reset actually fixes: the picture changing **in
 * place**, without the composable being disposed.
 *
 * One avatar whose local path is swapped every second. A lazy list recycling a row does not
 * hit this — Compose resets remembered state when it reuses a slot — but a row that stays
 * alive while its data changes does: the recent list refreshing with a new photo for the
 * same person, the thread header switching conversation, a profile after an upload.
 *
 * Without the reset this circle never changes after its first load, because `produceState`
 * keeps its previous value across a key change and the producer returns early on it.
 */
@Composable
fun AvatarInPlaceSwapPreview() = ZillitTheme {
    val context = LocalContext.current
    val swatches = remember { context.writeSwatches() }
    var index by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            index = (index + 7) % swatches.size
        }
    }

    val picture by rememberAttachmentImage(
        remoteKey = null,
        thumbnailKey = null,
        fileName = "",
        localPath = swatches[index],
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left: what the state says. Right: what the avatar actually resolved to.
            SwatchTile(label = "expected", path = swatches[index])
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp),
                )
            }
        }
        Text("Swatch $index — the two circles must match.")
        Text(
            "If the right circle stays on its first colour while the left one changes, " +
                "the stale-value reset is missing.",
        )
    }
}

@Composable
private fun SwatchTile(label: String, path: String) {
    Box(
        modifier = Modifier.size(96.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp),
        )
    }
}
