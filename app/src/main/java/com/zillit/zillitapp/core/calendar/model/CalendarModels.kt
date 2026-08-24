package com.zillit.zillitapp.core.calendar.model

/**
 * Who an event is for.
 *
 * The wire value is an Int, not a string, and the backend treats an unknown value as a
 * members event — so an unrecognised number must not become an error.
 */
enum class EventType(val value: Int) {
    MEMBERS(0),
    PERSONAL(1),
    ;

    companion object {
        fun fromValue(value: Int?): EventType =
            entries.firstOrNull { it.value == value } ?: MEMBERS
    }
}

/**
 * How people attend.
 *
 * [isJoinable] is what decides whether a card offers "Join Call". Plain [MEET_IN_PERSON] is
 * deliberately excluded — there is nothing to join — while the in-person **and** call
 * variant is joinable. v2 carries the same exception, matching the web client.
 */
enum class CallType(val apiValue: String) {
    AUDIO("audio"),
    VIDEO("video"),
    MEET_IN_PERSON("meet_in_person"),
    MEET_IN_PERSON_CALL("meet_in_person_call"),
    NONE("none"),
    ;

    val isJoinable: Boolean
        get() = this == AUDIO || this == VIDEO || this == MEET_IN_PERSON_CALL

    companion object {
        fun fromApi(value: String?): CallType =
            entries.firstOrNull { it.apiValue == value } ?: NONE
    }
}

/**
 * How much of a recurring series an edit or delete applies to.
 *
 * Always asked before touching a repeating event — changing one occurrence and changing
 * the series are very different outcomes and cannot be inferred.
 */
enum class EditScope(val apiValue: String) {
    SINGLE("single"),
    THIS_AND_FUTURE("thisAndFuture"),
    ALL("all"),
}

enum class InvitationStatus(val apiValue: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    ;

    companion object {
        fun fromApi(value: String?): InvitationStatus =
            entries.firstOrNull { it.apiValue == value } ?: PENDING
    }
}

enum class RecurrenceFrequency(val value: Int) {
    NONE(0),
    DAILY(1),
    WEEKLY(2),
    MONTHLY(3),
    YEARLY(4),
    CUSTOM(5),
    ;

    companion object {
        fun fromValue(value: Int?): RecurrenceFrequency =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** How an event repeats. `NONE` means it does not. */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    /** Weekdays for a weekly rule, 0 = Sunday, as the backend numbers them. */
    val byDay: List<Int> = emptyList(),
    /** First day of the series. */
    val from: Long? = null,
    /** Last day of the series. Null means it repeats indefinitely. */
    val until: Long? = null,
    val count: Int? = null,
) {
    val isRecurring: Boolean get() = frequency != RecurrenceFrequency.NONE
}

/**
 * A status answered from one occurrence onwards.
 *
 * "Decline every Monday standup from March" is one of these: it applies to [from] and
 * everything after it, until a later override supersedes it.
 *
 * [from] is a date string as the server writes it (`yyyy-MM-dd`), kept as text rather than
 * parsed at the boundary so a malformed value from the server cannot fail a whole response.
 */
data class StatusOverride(
    val status: InvitationStatus,
    val from: String,
)

/** One person's invitation to an event. */
data class Invitation(
    val userId: String,
    val name: String = "",
    val avatarUrl: String? = null,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val reason: String? = null,
    val isExternal: Boolean = false,
    val email: String? = null,
    /** Answers this person gave for part of a series. Empty for a one-off event. */
    val statusOverrides: List<StatusOverride> = emptyList(),
)

/**
 * How **the signed-in user** answered a recurring series, occurrence by occurrence.
 *
 * Scoped to the caller — the server builds it for whoever asks. It must never be applied
 * to another invitee's row; doing so shows the logged-in user's answers as if they were
 * everyone's. [InvitationStatusResolver] enforces that split.
 */
data class OccurrenceStatuses(
    val masterStatus: InvitationStatus? = null,
    val statusOverrides: List<StatusOverride> = emptyList(),
    /** Occurrence date (`yyyy-MM-dd`, UTC) to the status answered for that one date. */
    val occurrenceStatuses: Map<String, InvitationStatus> = emptyMap(),
)

/**
 * One date a person declined, with the reason they gave for that date.
 *
 * A recurring series can be declined date by date, each with its own reason, so a single
 * reason string on the person would lose most of what they said.
 */
data class DeclinedOccurrence(
    /** `yyyy-MM-dd`. */
    val date: String,
    val reason: String? = null,
)

/**
 * Someone who turned down an event this user created.
 *
 * [occurrences] is empty when they declined the whole series rather than particular dates
 * — which is how the scope is told apart without the server stating it.
 */
data class Decliner(
    val userId: String,
    val name: String = "",
    val avatarUrl: String? = null,
    val reason: String? = null,
    val occurrences: List<DeclinedOccurrence> = emptyList(),
) {
    /** True when they declined specific dates rather than the whole series. */
    val isPerOccurrence: Boolean get() = occurrences.isNotEmpty()
}

/** A timezone as the picker lists it. */
data class Timezone(
    /** IANA id, e.g. `Asia/Kolkata` — what an event stores. */
    val id: String,
    /** Display text, e.g. `(GMT+05:30) India Standard Time`. */
    val label: String,
)

/**
 * An event as the app uses it.
 *
 * Times are epoch millis with a separate [timezone] because an event belongs to the zone it
 * was scheduled in — a 9am call set in Mumbai stays 9am Mumbai when read in London, and
 * rendering it in the device's zone would quietly move it.
 *
 * Occurrences of a recurring series arrive **expanded** by the backend: each is a full event
 * carrying [masterEventId] and its own date. [isVirtual] marks the ones with no row of their
 * own, which matters because they cannot be addressed by id until they are materialised.
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String = "",
    val startDatetime: Long,
    val endDatetime: Long,
    val timezone: String = "",
    val type: EventType = EventType.MEMBERS,
    val isFullDay: Boolean = false,
    val recurrence: RecurrenceRule = RecurrenceRule(),
    val locationDescription: String = "",
    val locationLat: Double? = null,
    val locationLong: Double? = null,
    val callType: CallType = CallType.NONE,
    val cncGroupId: String? = null,
    /** Hex, e.g. `#fc9404`. The backend's default is Zillit orange. */
    val color: String = DEFAULT_COLOR,
    /** Minutes before the start to remind; 0 means no reminder. */
    val notifyMinutes: Int = 0,
    val creatorId: String,
    val projectId: String,
    val invitations: List<Invitation> = emptyList(),
    val inviteeCount: Int? = null,

    // ── Recurrence bookkeeping ────────────────────────────────────────────
    val masterEventId: String? = null,
    val parentEventId: String? = null,
    /**
     * `yyyy-MM-dd` — which occurrence of a series this is.
     *
     * Every per-occurrence call needs it: accepting one date rather than the series,
     * deleting one date, moving one date. Without it those requests address the series.
     */
    val occurrenceDate: String? = null,
    val isVirtual: Boolean = false,
    val isCancelledOccurrence: Boolean = false,
    /** Other masters of the same series, produced when it was split by an edit. */
    val siblingIds: List<String> = emptyList(),

    /**
     * Created by the box-schedule module, which keeps ownership of it — the calendar
     * shows it but must not offer edit or delete.
     */
    val isFromBoxSchedule: Boolean = false,

    /**
     * The creator excluded themselves from their own event — v2 tags such a card
     * "Excluded", because otherwise it looks like they simply forgot to attend.
     */
    val createUserExclude: Boolean = false,

    /** My status when the endpoint states it directly instead of embedding invitations. */
    val invitationStatus: InvitationStatus? = null,

    /** Who turned this event down, for the creator's Declined list. */
    val decliners: List<Decliner> = emptyList(),

    /**
     * How many distinct people declined.
     *
     * Sent separately because [decliners] can be capped — the count is the honest total.
     */
    val distinctDeclinerCount: Int? = null,

    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val isDeclined: Boolean = false,
) {
    /** My own status, once resolved against the signed-in user. */
    fun statusFor(userId: String?): InvitationStatus =
        invitations.firstOrNull { it.userId == userId }?.status ?: InvitationStatus.PENDING

    /**
     * Part of a series — either because it carries a rule, or because it is an occurrence
     * of one. An expanded occurrence has no rule of its own, only a parent.
     */
    val isRecurring: Boolean get() = recurrence.isRecurring || parentEventId != null

    /**
     * Called off — whether the whole event was deleted or this one occurrence cancelled.
     *
     * Cancelled events stay on the calendar, struck through, rather than vanishing: a
     * call that disappeared without a trace is worse than one visibly called off.
     */
    val isCancelled: Boolean get() = deletedAt != null || isCancelledOccurrence

    /**
     * Whether the thing as a whole is over — what gates editing and the Expired tag.
     *
     * A series stays live until its **last** occurrence, not its first: a daily standup
     * that began in March is not expired in June. A series with no end date never expires,
     * which is why this returns false rather than comparing against a missing bound.
     */
    fun isSeriesExpired(now: Long = System.currentTimeMillis()): Boolean = if (isRecurring) {
        recurrence.until?.let { it < now } ?: false
    } else {
        endDatetime < now
    }

    /**
     * The id that addresses the series this belongs to.
     *
     * An occurrence must be acted on through its master — accepting or deleting it by its
     * own id either fails or, worse, hits only that one row when the user meant the series.
     */
    val effectiveMasterEventId: String get() = masterEventId ?: parentEventId ?: id

    /** Past events are shown differently rather than hidden — v2 tags them "Expired". */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = endDatetime in 1 until now

    companion object {
        const val DEFAULT_COLOR = "#fc9404"
    }
}
