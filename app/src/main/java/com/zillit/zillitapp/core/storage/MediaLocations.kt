package com.zillit.zillitapp.core.storage

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a given object key actually lives, when it is not the project's own bucket.
 *
 * ### Why this exists rather than a parameter
 * Every media object the server returns names its `bucket` and `region`, and they are not
 * always the project's: a file uploaded before the project changed region stays where it
 * was, and a person who joined from another region keeps their photo there. Asking the
 * project's bucket for one of those keys returns `NoSuchKey` — indistinguishable from a
 * deleted file, which is how one user's avatar came back 404 while everyone else's loaded.
 *
 * The alternative was threading two more parameters from every DTO, through every entity,
 * every UI model and every composable that can show a picture. That is a wide change for a
 * fact that belongs to the key itself: one key is in exactly one place, forever. So the
 * fact is recorded once where the server states it, and read once where a transfer starts.
 *
 * Only non-default locations are worth recording, but this deliberately does not try to
 * know which those are — [StorageCredentials] can change under it, and a registry that
 * second-guessed the default would go stale. It records what it is told and lets
 * [S3Client] decide.
 *
 * Bounded, because a long session scrolling a large project would otherwise accumulate an
 * entry per file seen. Eviction is harmless: the entry is re-recorded the next time that
 * key is mapped, and until then the download falls back to the project bucket exactly as
 * it did before this class existed.
 */
@Singleton
class MediaLocations @Inject constructor() {

    private val lock = Any()

    /**
     * Access-ordered so eviction drops the key nobody has looked at recently, which for a
     * chat is reliably the oldest message on screen.
     */
    private val entries = object : LinkedHashMap<String, MediaLocation>(
        INITIAL_CAPACITY,
        LOAD_FACTOR,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaLocation>) =
            size > MAX_ENTRIES
    }

    /** Records where [key] lives. Blank values are ignored rather than stored as empty. */
    fun remember(key: String?, bucket: String?, region: String?) {
        val objectKey = key?.trim().orEmpty()
        if (objectKey.isEmpty()) return

        val home = MediaLocation(
            bucket = bucket?.trim()?.takeIf { it.isNotEmpty() },
            region = region?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (home.bucket == null && home.region == null) return

        synchronized(lock) { entries[objectKey] = home }
    }

    /** Convenience for the common pair of keys on one media object. */
    fun remember(media: String?, thumbnail: String?, bucket: String?, region: String?) {
        remember(media, bucket, region)
        remember(thumbnail, bucket, region)
    }

    /** Null when nothing is known, which means "the project's own bucket". */
    fun locationFor(key: String?): MediaLocation? {
        val objectKey = key?.trim().orEmpty()
        if (objectKey.isEmpty()) return null
        return synchronized(lock) { entries[objectKey] }
    }

    private companion object {
        const val MAX_ENTRIES = 4_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}

/** Either half may be null: a file can name a bucket without a region, and the reverse. */
data class MediaLocation(
    val bucket: String?,
    val region: String?,
)
