package com.zillit.zillitapp.feature.home.ui

import com.zillit.zillitapp.core.common.RelativeDay
import com.zillit.zillitapp.core.ui.chat.model.ChatAuthor
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.ChatUnit
import com.zillit.zillitapp.core.ui.chat.model.SendState

/**
 * Sample content for the UI review pass — replaced wholesale once the chat API and
 * Realm store land. Kept in one file so deleting it later is a single removal rather
 * than a hunt through the screen code.
 */
object HomePreviewData {

    private val ad = ChatAuthor(id = "u1", name = "Aarav Desai", role = "AD")
    private val vidya = ChatAuthor(id = "u2", name = "Vidya Pixel", role = "Driver")
    private val riya = ChatAuthor(id = "u3", name = "Riya Sharma", role = "Producer")

    val units = listOf(
        ChatUnit(id = "notices", name = "Notices", badgeCount = 0, canPost = false),
        ChatUnit(id = "main", name = "Main Unit", badgeCount = 3),
        ChatUnit(id = "second", name = "Second Unit", badgeCount = 0),
        ChatUnit(id = "art", name = "Art Department", badgeCount = 12),
        ChatUnit(id = "camera", name = "Camera", badgeCount = 0),
        ChatUnit(id = "vfx", name = "VFX", badgeCount = 5, isPrivate = true),
    )

    fun feedFor(unitId: String): List<ChatFeedItem> = when (unitId) {
        "notices" -> noticeFeed
        else -> mainFeed
    }

    private val noticeFeed: List<ChatFeedItem> = listOf(
        ChatFeedItem.Day(RelativeDay.Today),
        ChatFeedItem.Post(
            root = ChatMessage.Text(
                id = "n1",
                author = riya,
                timestamp = "08:15",
                body = "Call time moves to 06:30 tomorrow. Transport leaves the hotel at 05:45.",
            ),
        ),
        ChatFeedItem.Post(
            root = ChatMessage.Document(
                id = "n2",
                author = riya,
                timestamp = "08:17",
                fileName = "Callsheet_Day14.pdf",
                fileSize = "717.79 KB · 2 pages",
                fileKind = "PDF",
                thumbnail = "preview://callsheet-14",
                thumbnailTitle = "CALL SHEET — DAY 14",
            ),
        ),
    )

    private val mainFeed: List<ChatFeedItem> = listOf(
        ChatFeedItem.Day(RelativeDay.Today),

        // A post with two replies hanging off it — the common shape in a busy unit.
        ChatFeedItem.Post(
            root = ChatMessage.Text(
                id = "m1",
                author = ad,
                timestamp = "09:04",
                body = "Morning all. Project code ZL-4471 is live — schedule is at " +
                    "https://web.zillit.com/schedule",
                highlights = listOf("ZL-4471", "https://web.zillit.com/schedule"),
            ),
            replies = listOf(
                ChatMessage.Text(
                    id = "m1-r1",
                    author = vidya,
                    timestamp = "09:06",
                    body = "Got it. Two vans are already at the location gate.",
                    isOwn = true,
                    sendState = SendState.Read,
                ),
                ChatMessage.Text(
                    id = "m1-r2",
                    author = riya,
                    timestamp = "09:08",
                    body = "Thanks — I'll confirm the second unit call separately.",
                ),
            ),
        ),

        // Image post whose reply is itself an attachment, to show replies carry every type.
        ChatFeedItem.Post(
            root = ChatMessage.Image(
                id = "m3",
                author = vidya,
                timestamp = "09:11",
                thumbnail = "preview://gate",
                caption = "Gate access is on the north side.",
                isOwn = true,
                sendState = SendState.Delivered,
            ),
            replies = listOf(
                ChatMessage.Document(
                    id = "m3-r1",
                    author = ad,
                    timestamp = "09:14",
                    fileName = "Gate_Pass_List.xlsx",
                    fileSize = "38.2 KB",
                    fileKind = "XLS",
                ),
            ),
        ),

        ChatFeedItem.Post(
            root = ChatMessage.Location(
                id = "m4",
                author = vidya,
                timestamp = "09:12",
                placeName = "Unit Base — North Gate",
                address = "Film City Rd, Goregaon East, Mumbai 400065",
                isOwn = true,
                sendState = SendState.Sent,
            ),
        ),

        // Mid-upload: clip icon, ring and percentage instead of a tick.
        ChatFeedItem.Post(
            root = ChatMessage.Video(
                id = "m5",
                author = vidya,
                timestamp = "09:20",
                thumbnail = "preview://take-04",
                duration = "0:42",
                size = "8.4 MB",
                isOwn = true,
                sendState = SendState.Uploading(0.42f),
            ),
        ),

        ChatFeedItem.Post(
            root = ChatMessage.Voice(
                id = "m6",
                author = ad,
                timestamp = "09:24",
                duration = "0:18",
                waveform = listOf(
                    0.2f, 0.45f, 0.7f, 0.9f, 0.6f, 0.35f, 0.55f, 0.8f, 1f, 0.7f,
                    0.4f, 0.25f, 0.5f, 0.75f, 0.6f, 0.3f, 0.45f, 0.65f, 0.35f, 0.2f,
                ),
            ),
        ),

        ChatFeedItem.Post(
            root = ChatMessage.Document(
                id = "m7",
                author = riya,
                timestamp = "09:31",
                fileName = "Callsheet_Day13.pdf",
                fileSize = "717.79 KB · 2 pages",
                fileKind = "PDF",
                thumbnail = "preview://callsheet-13",
                thumbnailTitle = "CALL SHEET — DAY 13",
            ),
        ),

        // No thumbnail: the bubble collapses to the file row alone.
        ChatFeedItem.Post(
            root = ChatMessage.Document(
                id = "m8",
                author = riya,
                timestamp = "09:33",
                fileName = "Budget_Tracker_Week3.xlsx",
                fileSize = "142.6 KB",
                fileKind = "XLS",
            ),
        ),

        // Failed send: tappable retry in place of the tick.
        ChatFeedItem.Post(
            root = ChatMessage.Document(
                id = "m10",
                author = vidya,
                timestamp = "09:42",
                fileName = "Fuel_Receipts_Day13.pdf",
                fileSize = "2.1 MB",
                fileKind = "PDF",
                isOwn = true,
                sendState = SendState.Failed,
            ),
        ),
    )
}
