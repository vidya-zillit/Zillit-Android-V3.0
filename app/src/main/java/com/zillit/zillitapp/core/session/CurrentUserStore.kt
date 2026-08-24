package com.zillit.zillitapp.core.session

import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.preferences.AppPreferences
import com.zillit.zillitapp.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in user, held in memory and mirrored to preferences.
 *
 * Separate from [SessionStore], which holds the *project* context: this survives project
 * switches and is what "is this message mine?" is answered from.
 */
@Singleton
class CurrentUserStore @Inject constructor(
    private val preferences: AppPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _profile = MutableStateFlow<CurrentUser?>(null)
    val profile: StateFlow<CurrentUser?> = _profile.asStateFlow()

    val current: CurrentUser? get() = _profile.value

    val userId: String? get() = _profile.value?.userId

    val isAdmin: Boolean get() = _profile.value?.isAdmin ?: false

    /** True when [userId] is this device's user. Null-safe, so an unknown sender is not "me". */
    fun isMe(userId: String?): Boolean =
        userId != null && userId == _profile.value?.userId

    /** Restores the last known profile. Call before the first frame. */
    suspend fun restore() {
        val stored = preferences.getCurrentUserJson() ?: return
        _profile.value = runCatching { json.decodeFromString<CurrentUser>(stored) }
            .onFailure { ZillitLog.w(TAG, "Stored profile unreadable: ${it.message}") }
            .getOrNull()
    }

    fun update(user: ProjectUser) {
        val value = CurrentUser(
            userId = user.userId,
            projectId = user.projectId,
            fullName = user.fullName,
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            phone = user.phone,
            countryCode = user.countryCode,
            departmentId = user.departmentId,
            departmentName = user.departmentName,
            designationId = user.designationId,
            designationName = user.designationName,
            unitId = user.unitId,
            unitName = user.unitName,
            profilePictureUrl = user.profilePictureUrl,
            isAdmin = user.isAdmin,
            isOwner = user.isOwner,
            status = user.status,
        )
        _profile.value = value
        scope.launch {
            preferences.setCurrentUserJson(json.encodeToString(CurrentUser.serializer(), value))
        }
    }

    /** On sign-out, or when leaving the project the profile belongs to. */
    fun clear() {
        _profile.value = null
        scope.launch { preferences.setCurrentUserJson(null) }
    }

    private companion object {
        const val TAG = "CurrentUserStore"
    }
}

/**
 * The signed-in user, as persisted.
 *
 * A deliberately small subset of [ProjectUser]: this is written to preferences and read on
 * every cold start, so it holds identity and access, not the whole server object.
 */
@Serializable
data class CurrentUser(
    val userId: String,
    val projectId: String,
    val fullName: String,
    val firstName: String = "",
    val lastName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val countryCode: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val designationId: String? = null,
    val designationName: String? = null,
    val unitId: String? = null,
    val unitName: String? = null,
    val profilePictureUrl: String? = null,
    val isAdmin: Boolean = false,
    val isOwner: Boolean = false,
    val status: String? = null,
) {
    val displayName: String get() = fullName.ifBlank { "$firstName $lastName".trim() }

    val initials: String
        get() = displayName.trim().split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull { c -> c.isLetter() }?.uppercaseChar() }
            .take(2).joinToString("").ifEmpty { "?" }
}
