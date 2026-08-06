package com.vpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vpn.android.R
import com.vpn.android.ui.MainActivity

/**
 * Manages the persistent foreground VPN notification shown while the tunnel is active.
 *
 * WHY THIS MATTERS: Android's aggressive battery management (Doze mode, app standby)
 * will kill background processes without a foreground service notification.
 * VPN apps MUST show a persistent notification or risk the tunnel being destroyed
 * silently while the user thinks they're protected.
 *
 * The GoBackend's internal VpnService already handles the actual foreground declaration;
 * this manager keeps the notification content up-to-date (server, timer, status).
 */
object VpnNotificationManager {

    private const val CHANNEL_ID   = "vpn_status"
    private const val CHANNEL_NAME = "VPN Connection"
    const val NOTIFICATION_ID      = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW   // silent, no sound
            ).apply {
                description = "Shows your active VPN connection status"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun buildConnectedNotification(
        context: Context,
        serverCity: String,
        serverCountry: String,
        elapsedTime: String,         // e.g. "00:04:32"
        downloadSpeed: String = ""   // e.g. "12.4 MB/s"
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = if (downloadSpeed.isNotEmpty()) "  •  ↓ $downloadSpeed" else ""

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_vpn)
            .setContentTitle("🛡 VPN Connected — $serverCity, $serverCountry")
            .setContentText("🔒 Encrypted  •  $elapsedTime$speedText")
            .setContentIntent(openAppIntent)
            .setOngoing(true)         // Cannot be dismissed by user swipe
            .setOnlyAlertOnce(true)   // Don't buzz every update
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildDisconnectedNotification(context: Context): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_vpn)
            .setContentTitle("VPN Disconnected")
            .setContentText("Tap to reconnect and protect your connection")
            .setContentIntent(openAppIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun update(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }
}
