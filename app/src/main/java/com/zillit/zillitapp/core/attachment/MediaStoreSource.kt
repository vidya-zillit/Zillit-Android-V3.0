package com.zillit.zillitapp.core.attachment

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One item as it appears in the gallery grid, before the user commits to it. */
data class GalleryEntry(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Only for entries backed by a real file; null for anything cloud-only. */
    val filePath: String?,
    val bucketId: Long = 0,
    val bucketName: String = "",
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/**
 * A device album — MediaStore's "bucket", which is really the containing folder
 * (Camera, Screenshots, WhatsApp Images, Download…).
 *
 * The gallery opens on these rather than on one flat stream: a working phone holds
 * thousands of images, and finding the screenshot you want by scrolling a single
 * chronological grid is not realistic. It is also how every gallery a user has already
 * learned behaves.
 */
data class MediaBucket(
    val id: Long,
    val name: String,
    val itemCount: Int,
    /** Newest item in the bucket, used as the folder's cover. */
    val coverUri: Uri?,
)

/** Which media the gallery is showing. */
enum class GalleryFilter { IMAGES, VIDEOS, IMAGES_AND_VIDEOS, AUDIO }

/**
 * Reads the device's media for the in-app gallery.
 *
 * The app has its own gallery rather than delegating to the system picker because the
 * flow needs things the system picker cannot do: multi-select **with a per-file caption**,
 * a preview of what is about to be sent, and the ability to keep the sheet open across
 * several picks. That is the same reason v2 ships `GalleryViewer`.
 *
 * A single `MediaStore.Files` query with a type selection, not one query per type: two
 * queries would have to be merged and re-sorted in memory, and paging them consistently
 * afterwards is not possible.
 */
@Singleton
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The device's albums for [filter], largest first.
     *
     * Built by walking the same media query and grouping, rather than a `GROUP BY`:
     * MediaProvider rejects grouping clauses smuggled into the selection on API 29+, the
     * same way it rejects `LIMIT` in the sort order.
     */
    suspend fun queryBuckets(filter: GalleryFilter): List<MediaBucket> =
        withContext(Dispatchers.IO) {
            val all = query(filter, limit = BUCKET_SCAN_LIMIT)
            all.groupBy { it.bucketId to it.bucketName }
                .map { (key, items) ->
                    MediaBucket(
                        id = key.first,
                        name = key.second,
                        itemCount = items.size,
                        // Already newest-first from the query, so the head is the cover.
                        coverUri = items.firstOrNull()?.uri,
                    )
                }
                .sortedByDescending { it.itemCount }
        }

    suspend fun query(
        filter: GalleryFilter,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        bucketId: Long? = null,
    ): List<GalleryEntry> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        )

        val mediaTypes = when (filter) {
            GalleryFilter.IMAGES -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            GalleryFilter.VIDEOS -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            GalleryFilter.AUDIO -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
            GalleryFilter.IMAGES_AND_VIDEOS -> listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            )
        }

        val typeClause = mediaTypes.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        }
        val selection = if (bucketId == null) {
            "($typeClause)"
        } else {
            "($typeClause) AND ${MediaStore.Files.FileColumns.BUCKET_ID}=?"
        }
        val selectionArgs = buildList {
            addAll(mediaTypes.map { it.toString() })
            bucketId?.let { add(it.toString()) }
        }.toTypedArray()

        val entries = mutableListOf<GalleryEntry>()

        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                selection,
                selectionArgs,
                // Newest first: the photo someone just took is what they came to send.
                //
                // No SQL LIMIT/OFFSET here. MediaProvider on API 30+ rejects a sort order
                // that carries them — the query silently returns nothing, which looks
                // exactly like "the device has no photos". Paging is applied while walking
                // the cursor instead.
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val widthCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol =
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

                var skipped = 0
                while (cursor.moveToNext()) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    if (entries.size >= limit) break

                    val id = cursor.getLong(idCol)
                    val mediaType = cursor.getInt(typeCol)

                    // The row's content URI must be built from the type-specific table,
                    // not the Files table, or opening it fails on some OEM builds.
                    val baseUri = when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ->
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

                        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    val path = dataCol.takeIf { it >= 0 }?.let { cursor.getString(it) }

                    entries += GalleryEntry(
                        id = id,
                        uri = ContentUris.withAppendedId(baseUri, id),
                        displayName = cursor.getString(nameCol) ?: "",
                        mimeType = cursor.getString(mimeCol) ?: "",
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        durationMs = durationCol.takeIf { it >= 0 }
                            ?.let { cursor.getLong(it) } ?: 0,
                        width = widthCol.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        height = heightCol.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        // Only kept when it actually resolves: DATA is deprecated and on
                        // scoped storage often points at something unreadable, so a path
                        // that does not exist is worse than none.
                        filePath = path?.takeIf { File(it).exists() },
                        bucketId = bucketIdCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0,
                        bucketName = bucketNameCol.takeIf { it >= 0 }
                            ?.let { cursor.getString(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?: UNKNOWN_BUCKET,
                    )
                }
            }
        }.onFailure {
            ZillitLog.w(TAG, "MediaStore query failed: ${it.message}")
        }

        entries
    }

    private companion object {
        const val TAG = "MediaStore"

        /** One screenful plus scroll headroom; the grid pages beyond this. */
        const val DEFAULT_LIMIT = 200

        /**
         * How deep to look when building the album list. Counting every item on a phone
         * with 40k photos would stall the sheet; this is enough to find every album that
         * matters and to give each a cover.
         */
        const val BUCKET_SCAN_LIMIT = 2_000

        const val UNKNOWN_BUCKET = "Other"
    }
}
