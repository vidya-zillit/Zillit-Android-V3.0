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

        /**
         * Every tool the project *could* have, enabled or not — the customization screen.
         *
         * Deliberately a different list from [TOOLS], and a longer one: it is finer grained
         * (Budget appears as Department, Full and Builder) and it includes what is switched
         * off, which by definition cannot be on the Tools tab.
         */
        const val TOOLS_ADMIN = "${V2}project/tools/admin"

        /** Switches tools on and off for the whole project. Admin only. */
        const val TOOLS_ENABLE = "${V2}project/enable-tools"

        /** The project's tool groups — the six defaults plus any an admin has created. */
        const val TOOL_GROUPS = "${V2}project/tools/groups"

        /** Moves one tool into a group, or out of every group with a blank identifier. */
        const val TOOL_GROUP_MOVE = "${V2}project/tools/group"

        /**
         * The order the sections appear in, **per user**.
         *
         * Not a project setting: one person reordering their Tools page must not reorder
         * everybody else's, which is what the sheet's own copy promises.
         */
        const val TOOL_GROUP_ORDER = "${V2}project/tools/group/order"
    }

    /**
     * Home units. These live on a **separate service** from the main API, so the base
     * URL is its own BuildConfig field rather than a path under [V2].
     */
    /**
     * The tutorial videos and the help site.
     *
     * Plain public URLs, not API calls — they open in the browser and carry no credentials,
     * which is why they sit here as constants rather than behind the signed client.
     */
    object Help {
        const val VIDEO_BASE =
            "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/Android/"

        private const val SITE = "https://help.zillit.com/"

        /** The help page for one area. Admins get the admin variant, as v2 does. */
        fun page(anchor: String, isAdmin: Boolean): String =
            if (isAdmin) "$SITE?for=admin#$anchor" else "$SITE#$anchor"
    }

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
         * `PATCH` with `{ user_id }` — asks the backend to provision this user's Zillit
         * mailbox in the project named by the header.
         *
         * Until it has been called the profile's `mail_box_detail` is empty and every mail
         * route answers `email_credentials_not_available`. v2 calls it right after a
         * project is created or joined; nothing else creates the mailbox.
         */
        const val MAIL_BOX = "${V2}user/mail-box"

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

        /**
         * Postcode → area / city / state.
         *
         * Path parameters, not a query: `…/postalcode/{countryCode}/{postalCode}`. Used by
         * the contact editor to fill in the address from a postcode the user typed.
         */
        fun postalCode(countryCode: String, postalCode: String) =
            "${PRESET}geonames/postalcode/$countryCode/$postalCode"
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

    /**
     * Drive, as far as the rest of the app needs it.
     *
     * Only the folder list and folder creation — enough for a rule to say where attachments
     * should be filed. The Drive tool itself will own the rest when it is built.
     */
    object Drive {
        private const val DRIVE = "${BuildConfig.DRIVE_BASE_URL}api/v2/drive/"

        /** GET lists folders; POST creates one. */
        const val FOLDERS = "${DRIVE}folders"
    }

    /**
     * Mail.
     *
     * Its own service — `EMAIL_BASE_URL`, not a path under the main API — and deliberately
     * **not** routed through the consolidated-hosts switch, because v2 addresses it as a
     * static host and the backend has not moved it.
     *
     * A handful of email-adjacent calls live on the *main* host instead; they are grouped
     * under [Main] below so the split is visible rather than discovered.
     */
    object Email {
        private const val MAIL = "${BuildConfig.EMAIL_BASE_URL}api/v2/"

        /**
         * Reads the project's shared "Accounts" mailbox instead of the user's own.
         *
         * A **query parameter on every verb**, including POST and PUT, so request bodies —
         * and therefore the body-hash signature — stay untouched. Only ever sent as
         * `true`; absent means personal. Two exceptions, both noted where they apply:
         * rule writes put it in the body, and email groups never carry it at all.
         */
        const val ACCOUNTS_MAILBOX_PARAM = "use_project_account_mailbox"

        /** GET lists, POST creates, PUT renames, DELETE removes — all on one path. */
        const val FOLDERS = "${MAIL}imap-folders"

        /** The folder's **complete** uid list. There is no pagination anywhere in mail. */
        const val FOLDER_UIDS = "${MAIL}imap-emails/get-folder-uids"

        /** Headers only, in batches of 50. Bodies arrive from [EMAIL_TRAIL]. */
        const val EMAIL_INDEX = "${MAIL}imap-emails/get-email-index"

        /** Full messages by id — what opening a mail fetches. */
        const val EMAIL_TRAIL = "${MAIL}imap-emails/get-emails"

        /** PUT moves between folders; DELETE removes. */
        const val EMAILS = "${MAIL}imap-emails"

        const val EMPTY_TRASH = "${MAIL}imap-emails/empty-trash"

        /** The whole file, base64 inside the JSON response. */
        const val ATTACHMENT = "${MAIL}imap-emails/get-attachment"

        /**
         * Sending.
         *
         * v2 defines this and never calls it — its composer goes through the legacy
         * mailing module's queue instead. v3 uses it.
         */
        const val SEND = "${MAIL}imap-send"

        const val DRAFTS = "${MAIL}email-draft"

        fun draft(draftId: String) = "${MAIL}email-draft/$draftId"

        /** Drafts are API-only — never cached — so the list is fetched by timestamp. */
        fun draftsBefore(timestampMs: Long) = "${MAIL}email-draft/$timestampMs/previous"

        /**
         * Groups are **project-scoped**, so they never carry the accounts-mailbox flag.
         */
        const val GROUPS = "${MAIL}imap-email-group"

        fun group(groupId: String) = "${MAIL}imap-email-group/$groupId"

        const val CONTACTS = "${MAIL}email-contact"

        fun contact(contactId: String) = "${MAIL}email-contact/$contactId"

        const val SIGNATURES = "${MAIL}email-signature"
        const val SIGNATURE_CREATE = "${MAIL}email-signature/create"
        const val SIGNATURE_UPDATE = "${MAIL}email-signature/update"

        fun signature(signatureId: String) = "${MAIL}email-signature/$signatureId"

        /** GET reads, POST saves, DELETE removes. */
        const val FORWARDING = "${MAIL}email-forwarding-setting"

        const val RULES = "${MAIL}email-rules"
        const val RULES_REORDER = "${MAIL}email-rules/reorder"

        fun rule(ruleId: String) = "${MAIL}email-rules/$ruleId"

        fun ruleExecutions(ruleId: String) = "${MAIL}email-rules/$ruleId/executions"

        /**
         * Who has opened a mail this user sent.
         *
         * Its own endpoint, unrelated to the chat modules' `…/readby` — mail read receipts
         * come from the send log rather than from a chat thread.
         */
        const val READ_BY = "${MAIL}email-sent-log/read-by"

        /** Plaintext mailbox password. Every call is audit-logged server-side. */
        const val CREDENTIALS_REVEAL = "${MAIL}imap-credentials/reveal"
        const val CREDENTIALS_UPDATE = "${MAIL}imap-credentials/update"

        /**
         * The email settings that are **not** on the mail service.
         *
         * Conversation view is a property of the user (or of the project's shared mailbox),
         * not of a mailbox connection, so it lives with the rest of the profile.
         */
        object Main {
            const val CONVERSATION_VIEW = "${V2}user/update-conversation-view"
            const val ACCOUNTS_CONVERSATION_VIEW =
                "${V2}project/accounts-mail-box/conversation-view"
            const val ACCOUNTS_BCC = "${V2}project/accounts-mail-box/bcc"

            /** Personal BCC presets live on the user, not on the mailbox. */
            const val BCC_PRESETS = "${V2}user/update-bcc-preset"
        }
    }
}
