package com.zillit.zillitapp.core.docdist

import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.common.toApiDate
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Document Distribution folder a tool publishes into.
 *
 * v2 keeps the same list as `DocDistTool`; only the ones a chat surface can publish from
 * are here, and more are added when their tool lands rather than up front.
 */
enum class DocDistFolder(val folderName: String) {
    CALL_SHEET("Call Sheet"),
    INFO("Info"),
    CONFIDENTIAL_INFO("Confidential Info"),
}

/**
 * Publishes a file from a tool into Document Distribution.
 *
 * **Shared, not per-tool.** v2 arrived at the same conclusion the hard way: the flow was
 * copied into each tool and drifted, and ZL-19693 pulled it back into one
 * `publishFromTool`. Call Sheet is the only chat surface that offers it today, but Info,
 * Confidential Info and the report tools use exactly this call.
 */
@Singleton
class DocDistributionPublisher @Inject constructor(
    private val api: ZillitApi,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    private val currentUser: CurrentUserStore,
) {

    /**
     * Whether this user may publish.
     *
     * The `document_distribution_tool` entry in the project's tool rights, or admin. An
     * absent entry means no rights were granted at all, which is a **no** — v2 treats a
     * missing entry the same way.
     */
    fun canPublish(): Boolean {
        if (currentUser.isAdmin) return true
        val projectId = session.activeProject.value?.projectId ?: return false
        return directory.findTool(DOC_DIST_TOOL_IDENTIFIER, projectId)?.postingAccess == true
    }

    /**
     * Registers an already-uploaded file as a Document Distribution document.
     *
     * Nothing is re-uploaded: the file is on storage, and this hands DD its object key.
     *
     * @param createdAt when the message was posted; becomes `folder_date`, so a call sheet
     *   published late still files under the day it belongs to rather than today.
     * @return true on success. The reason for a failure is logged, not returned — every
     *   caller shows the same "couldn't publish" line either way.
     */
    suspend fun publishFromTool(
        folderName: DocDistFolder,
        createdAt: Long,
        fileName: String,
        media: String,
        thumbnail: String? = null,
        fileSize: Long? = null,
        caption: String? = null,
        mediaType: String = "document",
    ): Boolean {
        if (fileName.isBlank() || media.isBlank()) {
            ZillitLog.w(TAG, "Refusing to publish without a name and a file reference")
            return false
        }

        // Built with **no null values**: a JsonNull entry breaks the body-hash builder that
        // signs the request, so every optional field is added only when it has a value.
        val body = buildJsonObject {
            putJsonArray("folder_path") { add(folderName.folderName) }
            put("original_name", fileName)
            // The backend's attachment shape carries both, with the same value.
            put("name", fileName)
            put("media", media)
            put("media_type", mediaType)
            put("folder_date", folderDate(createdAt))
            thumbnail?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
            caption?.takeIf { it.isNotBlank() }?.let { put("caption", it) }
            // Never "0" or blank — DD rejects a library row with no real size.
            fileSize?.takeIf { it > 0 }?.let { put("file_size", it.toString()) }
            fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
                ?.let { put("content_subtype", it) }
        }

        val result = api.post<JsonObject, JsonObject>(
            url = "$BASE${"documents/from-tool"}",
            body = body,
            module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
        )

        return when (result) {
            is ApiResult.Success -> true
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Publish failed for $fileName: ${result.error.message}")
                false
            }
        }
    }

    /** `yyyy-MM-dd`, the shape DD files documents by. */
    private fun folderDate(epochMillis: Long): String {
        val millis = epochMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        return millis.toApiDate()
    }

    private companion object {
        const val TAG = "DocDistPublisher"

        val BASE = "${BuildConfig.DOC_DISTRIBUTION_BASE_URL}/api/v2/document-distribution/"

        /** The tool identifier posting rights are keyed by. */
        const val DOC_DIST_TOOL_IDENTIFIER = "document_distribution_tool"
    }
}
