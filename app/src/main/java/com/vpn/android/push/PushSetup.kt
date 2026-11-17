package com.vpn.android.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Initializes push notification channels and subscribes to FCM topics.
 * Fail-open: if Firebase is unavailable (sideloaded, no Play Services), nothing happens.
 */
object PushSetup {
    fun start(context: Context) {
        PushChannels.ensure(context)
        runCatching {
            FirebaseMessaging.getInstance().apply {
                subscribeToTopic("all")
                subscribeToTopic("android")
            }
        } // fail open
    }
}
