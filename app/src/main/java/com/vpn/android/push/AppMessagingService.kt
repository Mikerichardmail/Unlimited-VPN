package com.vpn.android.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vpn.android.R

/**
 * Handles incoming FCM messages.
 *
 * - For notification messages when app is in background: the system shows them automatically.
 * - For data messages (always) and notification messages in foreground: onMessageReceived runs.
 *
 * Uses notification IDs >= 10_000 to never collide with the VPN's foreground notification (ID 1001).
 */
class AppMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Topics survive token refresh. Nothing required for topic-based push.
        // Optional: upload `token` to your backend for per-user targeting.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        val channel = if (message.data["channel"] == PushChannels.PROMO) PushChannels.PROMO
                      else PushChannels.GENERAL
        show(title, body, channel)
    }

    @SuppressLint("MissingPermission") // guarded by PushPermission.isGranted
    private fun show(title: String, body: String, channel: String) {
        if (!PushPermission.isGranted(this)) return
        PushChannels.ensure(this)

        // Opens whatever the launcher Activity is. No hard-coded Activity names.
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from_push", true)
        }
        val pi = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(ContextCompat.getColor(this, R.color.push_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        // ID >= 10_000 derived from time: cannot collide with the VPN's fixed foreground ID (1001).
        NotificationManagerCompat.from(this)
            .notify(10_000 + (System.currentTimeMillis() % 1_000_000).toInt(), n)
    }
}
