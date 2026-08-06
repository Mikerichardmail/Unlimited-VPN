package com.vpn.android

import android.app.Application
import com.vpn.android.vpn.VpnNotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register the VPN notification channel once at startup.
        // Must run before any foreground notification is posted.
        VpnNotificationManager.createChannel(this)
    }
}
