package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.badge.BadgeModule
import com.zillit.zillitapp.core.logging.ZillitLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a notification should take the user when tapped.
 *
 * A closed set rather than a raw string, so a tap can never land on a route that does not
 * exist — the compiler forces the navigation layer to handle each case as screens land.
 */
sealed interface NotificationDestination {
    data class ProjectHome(val projectId: String) : NotificationDestination
    data class HomeChat(val projectId: String, val unitId: String) : NotificationDestination
    data class GroupChat(val projectId: String, val chatRoomId: String) : NotificationDestination
    data class PrivateChat(val projectId: String, val chatRoomId: String) : NotificationDestination
    data class Tool(val projectId: String, val toolId: String, val unitId: String?) :
        NotificationDestination
    data class DocDistribution(val projectId: String, val referenceId: String?) :
        NotificationDestination
    data class Calendar(val projectId: String, val eventId: String?) : NotificationDestination
    data class Email(val projectId: String, val messageId: String?) : NotificationDestination

    /** Recognised as a notification but with no specific screen — open the project. */
    data class Unmapped(val projectId: String, val reason: String) : NotificationDestination
}

/**
 * The single place a notification is turned into a destination.
 *
 * v2 spread this logic across `FirebaseCloudMessagingService`, `BaseSocketListener` and
 * several Activities, each re-deriving the target from `section`/`tool`/`unit` in slightly
 * different ways — which is why the same event could open different screens depending on
 * whether it arrived by push or by socket.
 *
 * Here both transports normalise into [IncomingNotification] and route through this class,
 * so a push and a socket event for the same thing always land in the same place.
 */
@Singleton
class NotificationRouter @Inject constructor() {

    fun destinationFor(notification: IncomingNotification): NotificationDestination {
        val projectId = notification.projectId

        val destination = when (notification.module) {
            BadgeModule.HOME_CHAT -> notification.unit
                ?.takeIf { it.isNotBlank() }
                ?.let { NotificationDestination.HomeChat(projectId, it) }
                ?: NotificationDestination.Unmapped(projectId, "home chat without unit")

            BadgeModule.GROUP_CHAT -> notification.chatRoomId
                ?.takeIf { it.isNotBlank() }
                ?.let { NotificationDestination.GroupChat(projectId, it) }
                ?: NotificationDestination.Unmapped(projectId, "group chat without room id")

            BadgeModule.PRIVATE_CHAT -> notification.chatRoomId
                ?.takeIf { it.isNotBlank() }
                ?.let { NotificationDestination.PrivateChat(projectId, it) }
                ?: NotificationDestination.Unmapped(projectId, "private chat without room id")

            BadgeModule.DOC_DISTRIBUTION ->
                NotificationDestination.DocDistribution(projectId, notification.referenceId)

            BadgeModule.CALENDAR ->
                NotificationDestination.Calendar(projectId, notification.referenceId)

            BadgeModule.EMAIL ->
                NotificationDestination.Email(projectId, notification.referenceId)

            BadgeModule.TOOLS -> notification.tool
                ?.takeIf { it.isNotBlank() }
                ?.let { NotificationDestination.Tool(projectId, it, notification.unit) }
                ?: NotificationDestination.Unmapped(projectId, "tool notification without tool id")

            BadgeModule.PROJECT,
            BadgeModule.NOTIFICATION,
                -> NotificationDestination.ProjectHome(projectId)
        }

        if (destination is NotificationDestination.Unmapped) {
            // Worth surfacing: it means the backend sent a shape this build does not
            // understand, which is a real integration gap rather than a user error.
            ZillitLog.firebaseWarn(
                "Unmapped notification (${destination.reason}) " +
                    "section=${notification.section} tool=${notification.tool}",
            )
        } else {
            ZillitLog.firebase("Routing ${notification.module.key} -> ${destination::class.java.simpleName}")
        }

        return destination
    }
}
