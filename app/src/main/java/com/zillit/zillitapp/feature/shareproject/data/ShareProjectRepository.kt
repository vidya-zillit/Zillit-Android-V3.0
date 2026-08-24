package com.zillit.zillitapp.feature.shareproject.data

import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RecoveryEmailRequest(
    @SerialName("email") val email: String,
)

@Serializable
data class ActionEnvelope(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
)

@Singleton
class ShareProjectRepository @Inject constructor(
    private val api: ZillitApi,
) {
    /**
     * Attaches a recovery email to the project.
     *
     * `WITH_PROJECT_USER_ID` because by this point the project exists and is active —
     * unlike everything in the create flow, which ran before it did.
     */
    suspend fun addRecoveryEmail(email: String): ApiResult<Unit> =
        when (
            val result = api.post<RecoveryEmailRequest, ActionEnvelope>(
                url = ApiEndpoints.Device.RECOVERY_EMAIL,
                body = RecoveryEmailRequest(email.trim()),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }
}
