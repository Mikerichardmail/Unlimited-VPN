package com.vpn.android

import android.app.Application
import com.vpn.android.vpn.VpnNotificationManager
import com.vpn.android.utils.GlobalCrashHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VpnApplication : Application() {

    @Inject
    lateinit var globalCrashHandler: GlobalCrashHandler

    override fun onCreate() {
        super.onCreate()
        
        // Initialize global crash reporter
        globalCrashHandler.initialize()
        
        // Register the VPN notification channel once at startup.
        // Must run before any foreground notification is posted.
        VpnNotificationManager.createChannel(this)
    }
}
