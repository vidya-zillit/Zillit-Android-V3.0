package com.zillit.zillitapp.core.network

import com.zillit.zillitapp.BuildConfig

/**
 * Endpoint URLs, grouped by the feature that owns them.
 *
 * v2 addressed endpoints indirectly through ~400 integer request codes in
 * `ApiConstants` (`REQ_GET_PROJECT_LIST = 1006`), which callers then matched on in
 * large `when` blocks. That scheme had genuine duplicate values — `1114`, `2027`,
 * `2043`, `4029` and `4069`-`4071` were each assigned to two different requests —
 * so two unrelated responses could land in the same branch. There are no request
 * codes here: a call site names the URL it wants and gets a typed result back.
 */
object ApiEndpoints {

    private const val BASE = BuildConfig.BASE_URL
    private const val V2 = "${BASE}api/v2/"
    private const val PRESET = "${V2}preset/"

    /**
     * Bearer-token session. These three are the only endpoints that still present
     * `moduledata` (or a refresh token) rather than a project token — they are what mints
     * one.
     */
    object ErrorLog {
        /** The shared error-log sink — every platform posts the schema-v1 envelope here. */
        const val POST = "${BuildConfig.LOCATION_BASE_URL}api/v2/location/log"
    }

    object Session {
        const val DEVICE = "${V2}session/device"
        const val PROJECT = "${V2}session/project"
        const val REFRESH = "${V2}session/device/refresh"
    }

    object Project {
        /** GET returns the caller's projects; POST creates one. Same path, as in v2. */
        const val LIST = "${V2}project"
        const val CREATE = "${V2}project"
        const val UPDATE = "${V2}project"
        const val BY_CODE = "${V2}project"
        const val FAVOURITE = "${V2}project/favourite-project"
        const val JOIN = "${V2}project/join"
        const val JOIN_STATUS = "${V2}project/join-status"

        /** GET one project. v2 appends the id directly: `project/<projectId>`. */
        fun details(projectId: String) = "${V2}project/$projectId"

        /** Everyone on the project — the source for the local user directory. */
        const val USERS = "${V2}project/users"

        /**
         * Which tools this project has enabled. v2 keeps a second endpoint for users
         * whose join is still pending, because the approved list would 403 for them.
         */
        const val TOOLS = "${V2}project/tools"
        const val TOOLS_PENDING = "${V2}project/pendingtools"
    }

    /**
     * Home units. These live on a **separate service** from the main API, so the base
     * URL is its own BuildConfig field rather than a path under [V2].
     */
    object Units {
        private const val UNITS = "${BuildConfig.UNITS_BASE_URL}api/v2/home/"

        /** The units the signed-in user may see, each one its own chat thread. */
        const val USER_LIST = "${UNITS}unit/user"
    }

    object User {
        /** The signed-in user's own profile for the active project. */
        const val PROFILE = "${V2}user/profile"

        /** Read access per unit — drives which units appear and whether posting is on. */
        const val UNIT_ACCESS = "${V2}user/unit-access"

        /**
         * People outside the project who can still be invited to things.
         *
         * One route for all three verbs: GET syncs them down by timestamp cursor, POST
         * adds one, PUT updates one.
         */
        const val EXTERNAL_USER = "${V2}user/external-user"
    }

    object Preset {
        const val LANGUAGES = "${PRESET}languages"
        const val LABELS = "${PRESET}labels"
        const val MESSAGES = "${PRESET}messages"
        const val IDENTIFIERS = "${PRESET}identifiers"
        const val PROJECT_TYPES = "${PRESET}project-types"
        const val TIMEZONES = "${PRESET}timezones"
        const val SUITABLE_REGION = "${PRESET}suitable-region"
        const val ISD_CODES = "${PRESET}isd-codes"
    }

    object Device {
        private const val DEVICE = "${V2}device/"

        /** PUT registers this install (incl. the FCM token) with the backend. */
        const val DETAILS = "${V2}device"

        const val OTP = "${V2}device-otp"
        const val VERIFY_OTP = "${V2}device-otp/verify"

        /** Devices currently linked to this account. */
        const val LINKED = "${DEVICE}linked"

        /** Logs a linked device out. Body: `{ "device_id": ... }`. */
        const val UNLINK = "${DEVICE}unlink"

        /** Links a device from a scanned QR payload. Body: `{ "code", "file_name" }`. */
        const val SCAN_QR = "${DEVICE}qrcode/scan"

        /** Adds a recovery email so a project survives losing this device. */
        const val RECOVERY_EMAIL = "${DEVICE}recovery-email"
    }

    object Notification {
        // v2's BASE_NOTIFICATION_URL is `.../api/v2/` — there is NO `notification/`
        // segment. Adding one returned route_not_found.
        // Public: the notification feed builds its own paths off the same root.
        const val BASE_URL = "${BuildConfig.NOTIFICATION_BASE_URL}api/v2/"
        private const val BASE = BASE_URL

        /** Paginated badge/notification sync: `.../{timestamp}/{next|previous}`. */
        const val ALL_FOR_BADGE = "${BASE}project/all/notifications/"
        const val MARK_READ = "${BASE}levelmarkread/"

        /**
         * Unread per project, for the whole device — v2's `PROJECT_UNREAD_URL`.
         *
         * Device-scoped rather than project-scoped, so one call answers for every project
         * in the list. Signed with [ModuleData.DEFAULT] because there is no active project
         * when the list is on screen.
         */
        const val DEVICE_UNREAD = "${BASE}device/unread"
        const val MARK_DELETE = "${BASE}markdelete/"
    }

    /**
     * The calendar service — its own host, like units and notifications.
     *
     * Mirrors v2's new-calendar paths exactly; the old calendar's routes are deliberately
     * not carried over, since v2 ships two implementations and only this one is current.
     */
    object Calendar {
        private const val BASE = "${BuildConfig.CALENDAR_BASE_URL}api/v2/calendar"

        /** POST — create. The bare base doubles as the create route, as in v2. */
        const val CREATE_EVENT = BASE

        /** GET — every event in a window, for the grid and agenda. */
        const val EVENTS = "$BASE/events"

        /** GET — one event by id. What a socket `event_id` is resolved through. */
        const val EVENT = "$BASE/event/"

        const val EDIT_EVENT = "$BASE/edit/"

        /** DELETE — `{base}/{eventId}`. */
        const val DELETE_EVENT = "$BASE/"

        /** PUT — moves one occurrence of a recurring series. */
        const val MOVE_OCCURRENCE = "$BASE/move/"

        const val INVITE_ACCEPT = "$BASE/accept/"
        const val INVITE_REJECT = "$BASE/reject/"

        /** GET — invitations addressed to me: the Events / pending list. */
        const val INVITES = "$BASE/invite"
        const val INVITATION_LIST = "$BASE/invitationlist"
        const val OCCURRENCE_STATUSES = "$BASE/invite/occurrence-statuses"

        const val CREATED_EVENTS = "$BASE/created-events"
        const val DECLINED_CREATED = "$BASE/declined-created"
        const val JOIN_CALL = "$BASE/join-call"
        const val TIMEZONES = "$BASE/timezones"
    }

    object Configuration {
        const val CONFIG = "${V2}configuration"
        const val DEPARTMENTS = "${V2}departments"
    }
}
