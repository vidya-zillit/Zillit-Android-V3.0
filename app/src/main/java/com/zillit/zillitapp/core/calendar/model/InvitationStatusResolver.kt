package com.zillit.zillitapp.core.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Decides what an invitation's status actually is for one occurrence.
 *
 * A recurring invitation can be answered at three levels, and they disagree on purpose:
 * accept the series, then decline every occurrence from April, then accept one Tuesday in
 * April anyway. Reading `invitation.status` alone would show "accepted" on a day the user
 * declined, so every status badge must come through here.
 *
 * Priority, highest first:
 *  1. a status answered for that exact date
 *  2. the newest override whose `from` is on or before that date
 *  3. the status answered for the series
 *
 * Dates are compared in **UTC**, because that is how the server keys them. Comparing in the
 * device zone would shift the boundary by a day for anyone east or west of it, and the
 * symptom — one occurrence at the edge of an override showing the wrong answer — is
 * exactly the kind of thing that reads as a backend bug.
 */
object InvitationStatusResolver {

    /**
     * The signed-in user's effective status for one occurrence.
     *
     * @param invitation this user's invitation on the master event; null when they are not
     *   an invitee.
     * @param occurrenceDate start of the occurrence being viewed, epoch millis.
     * @param occurrenceStatuses the per-occurrence answers, fetched only for recurring
     *   events. Null for a one-off.
     */
    fun resolve(
        invitation: Invitation?,
        occurrenceDate: Long?,
        occurrenceStatuses: OccurrenceStatuses? = null,
        isRecurring: Boolean = false,
    ): InvitationStatus? {
        if (invitation == null && occurrenceStatuses == null) return null

        if (isRecurring && occurrenceDate != null) {
            occurrenceStatuses?.let { return resolveWithOccurrences(it, occurrenceDate, invitation) }

            invitation?.let {
                return resolveFromOverrides(it.statusOverrides, occurrenceDate, it.status)
            }
        }

        return invitation?.status
    }

    /**
     * Another invitee's status for one occurrence — what an attendee row shows.
     *
     * Deliberately reads **only that person's own overrides**. The occurrence-statuses
     * payload belongs to whoever requested it, so feeding it in here would paint the
     * signed-in user's answers onto every other attendee. v2 shipped that bug and the
     * attendee list showed one person's timeline for the whole crew.
     */
    fun resolveForInvitation(invitation: Invitation, occurrenceDate: Long): InvitationStatus =
        resolveFromOverrides(invitation.statusOverrides, occurrenceDate, invitation.status)

    private fun resolveWithOccurrences(
        statuses: OccurrenceStatuses,
        occurrenceDate: Long,
        invitation: Invitation?,
    ): InvitationStatus? {
        val date = occurrenceDate.toUtcDate()

        statuses.occurrenceStatuses[date.toString()]?.let { return it }

        newestOverrideOnOrBefore(statuses.statusOverrides, date)?.let { return it.status }

        return statuses.masterStatus ?: invitation?.status
    }

    private fun resolveFromOverrides(
        overrides: List<StatusOverride>,
        occurrenceDate: Long,
        masterStatus: InvitationStatus,
    ): InvitationStatus {
        if (overrides.isEmpty()) return masterStatus
        return newestOverrideOnOrBefore(overrides, occurrenceDate.toUtcDate())?.status
            ?: masterStatus
    }

    /**
     * The override in force on [date] — the latest one starting on or before it.
     *
     * An unparseable `from` is skipped rather than thrown: one bad row should cost its own
     * override, not the whole status.
     */
    private fun newestOverrideOnOrBefore(
        overrides: List<StatusOverride>,
        date: LocalDate,
    ): StatusOverride? = overrides
        .mapNotNull { override ->
            runCatching { LocalDate.parse(override.from) }.getOrNull()?.let { it to override }
        }
        .filter { (from, _) -> !date.isBefore(from) }
        .maxByOrNull { (from, _) -> from }
        ?.second

    private fun Long.toUtcDate(): LocalDate =
        Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
}
