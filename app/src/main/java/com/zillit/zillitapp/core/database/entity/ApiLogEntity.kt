package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One recorded API call.
 *
 * Persisted so a failure can be inspected after the fact — the usual report is "it failed
 * on my phone an hour ago", which Logcat cannot answer once the app has been restarted.
 *
 * **Deliberately does not store the encrypted `moduledata` or `bodyhash` values.** v2's
 * `ApiLogDbModel` kept `moduleData` verbatim, which means the request credential sat in a
 * readable database on the device and in anything that exported it. Only the *variant
 * name* (`WITH_PROJECT_USER_ID`) is kept here — that is what you actually need when
 * diagnosing a rejected request, and it is not a secret.
 */
class ApiLogEntity : RealmObject {
    @PrimaryKey
    var id: String = ""

    @Index
    var timestamp: Long = 0L

    var method: String = ""

    /** Full URL including query. */
    @Index
    var url: String = ""

    /** Which [com.zillit.zillitapp.core.network.ModuleData] variant signed the request. */
    var moduleDataVariant: String = ""

    var requestBody: String? = null
    var responseBody: String? = null

    /** HTTP status, or 0 when the request never reached the server. */
    @Index
    var statusCode: Int = 0

    /** Round-trip time in milliseconds. */
    var durationMs: Long = 0L

    /** Populated only on failure — the [com.zillit.zillitapp.core.network.ApiError] type. */
    var errorType: String? = null
    var errorMessage: String? = null

    var networkType: String? = null
    var isSuccess: Boolean = false
}
