package com.zillit.zillitapp.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zillit.zillitapp.MainActivity
import com.zillit.zillitapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts system notifications.
 *
 * Only ever called for non-silent messages — see [ZillitMessagingService]. Kept separate
 * from the messaging service so the "should this be shown" decision and the "how does it
 * look" concern do not sit in the same class, and so a socket-delivered event can reuse
 * the same presentation.
 */
@Singleton
class NotificationPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textBuilder: NotificationTextBuilder,
) {

    private val manager = NotificationManagerCompat.from(context)

    fun show(notification: IncomingNotification, destination: NotificationDestination? = null) {
        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PROJECT_ID, notification.projectId)
            putExtra(EXTRA_SCOPE_ID, notification.scopeId)
            putExtra(EXTRA_MODULE, notification.module.key)
            putExtra(EXTRA_DESTINATION, destination?.let { it::class.java.simpleName })
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.uuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // Decrypted, label-resolved and element-substituted — never the raw
            // ciphertext or a bare label key.
            .setContentTitle(textBuilder.title(notification) ?: context.getString(R.string.app_name))
            .setContentText(textBuilder.body(notification))
            .setStyle(NotificationCompat.BigTextStyle().bigText(textBuilder.body(notification)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            // Grouped per scope so a busy chat collapses into one stack rather than
            // filling the shade with one entry per message.
            .setGroup(notification.scopeId)

        runCatching {
            // Throws without POST_NOTIFICATIONS on API 33+. The badge is already stored,
            // so failing to post is a degraded experience, not lost state.
            manager.notify(notification.uuid.hashCode(), builder.build())
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_general),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_general_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "zillit_general"
        const val EXTRA_PROJECT_ID = "notification_project_id"
        const val EXTRA_SCOPE_ID = "notification_scope_id"
        const val EXTRA_MODULE = "notification_module"
        const val EXTRA_DESTINATION = "notification_destination"
    }
}
