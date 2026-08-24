package com.zillit.zillitapp.core.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/**
 * Receives pushes and turns them into stored notifications and badge updates.
 *
 * The rule, carried over from v2, is that **storage and badging are unconditional** while
 * the tray notification is not:
 *
 *  - Every data message is recorded and bumps its badge — silent included. A silent push
 *    is the mechanism that keeps badges live while the app is backgrounded; if it were
 *    dropped, the badge would stay stale until the next full API refresh.
 *  - Only a non-silent message additionally posts to the notification tray, and even then
 *    not while the user has notifications muted.
 *
 * Work runs on an application-scoped coroutine rather than the service's own lifetime,
 * because Android may tear the service down as soon as `onMessageReceived` returns and a
 * Realm write started here would be cancelled mid-flight.
 */
@AndroidEntryPoint
class ZillitMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var presenter: NotificationPresenter

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var json: Json

    @Inject
    lateinit var router: NotificationRouter

    @Inject
    lateinit var deviceRegistrar: DeviceRegistrar

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        ZillitLog.firebase("onMessageReceived from=${message.from} keys=${data.keys}")
        ZillitLog.payload(ZillitLog.TAG_FIREBASE, "FCM data", data.toString())

        if (data.isEmpty()) {
            ZillitLog.firebaseWarn("Message had no data payload — ignored")
            return
        }

        // The backend nests everything under a single `payload` key whose value is the
        // notification JSON as a STRING — not one map entry per field. Treating the map
        // itself as the object yields {"payload":"{...}"}, which has no project_id and
        // gets dropped. Parse the inner string when that key is present.
        //
        // The flat-map branch is kept as a fallback for any sender that does put fields
        // at the top level; there, values arrive quoted (an FCM data map is
        // Map<String,String>) and lenient parsing coerces `silent`/`created` back to
        // Boolean/Long.
        val element = data[PAYLOAD_KEY]
            ?.let { raw -> runCatching { json.parseToJsonElement(raw) }.getOrNull() }
            ?: JsonObject(data.mapValues { (_, value) -> JsonPrimitive(value) })

        val payload = runCatching {
            json.decodeFromJsonElement(NotificationPayload.serializer(), element)
        }.getOrNull() ?: run {
            ZillitLog.firebaseWarn("Could not decode FCM payload")
            return
        }

        val incoming = payload.toIncoming(json) ?: run {
            ZillitLog.firebaseWarn("Payload had no project_id — ignored")
            return
        }

        scope.launch {
            val isNew = notificationRepository.record(incoming)
            ZillitLog.firebase(
                "Stored=${isNew} silent=${incoming.isSilent} module=${incoming.module.key} scope=${incoming.scopeId}",
            )

            // A redelivery must not re-post to the tray either — the user already saw it.
            if (!isNew) return@launch

            // Resolved even for silent messages so the routing decision is logged and
            // testable, not only computed at tap time.
            val destination = router.destinationFor(incoming)

            if (incoming.isSilent) {
                ZillitLog.firebase("Silent — badge updated, no tray notification")
            } else if (preferences.isNotificationMuted.first()) {
                ZillitLog.firebase("Muted — badge updated, tray suppressed")
            } else {
                presenter.show(incoming, destination)
            }
        }
    }

    private companion object {
        /** Backend wraps the notification JSON under this single data key. */
        const val PAYLOAD_KEY = "payload"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ZillitLog.firebase("onNewToken — re-registering device")
        // Registering here (not just storing) is essential: Firebase rotates tokens, and
        // a rotated token the backend never learns about silently stops all pushes.
        scope.launch { deviceRegistrar.register(token) }
    }
}
