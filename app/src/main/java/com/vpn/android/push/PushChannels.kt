package com.vpn.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.vpn.android.R

/**
 * Notification channels for push notifications.
 * Uses NEW channel IDs that never collide with the VPN's "vpn_status" channel.
 */
object PushChannels {
    const val GENERAL = "push_general"   // service status, announcements
    const val PROMO = "push_promo"       // offers (low importance, user can mute)

    fun ensure(context: Context) {
        // minSdk is 26, so no SDK_INT check needed
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                GENERAL,
                context.getString(R.string.push_channel_general),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                PROMO,
                context.getString(R.string.push_channel_promo),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
