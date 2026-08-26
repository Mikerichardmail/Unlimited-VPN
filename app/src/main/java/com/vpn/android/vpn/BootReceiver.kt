package com.vpn.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vpn.android.data.local.LocalSettingsManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// BUG 11 FIX: Auto-reconnect VPN after device reboot.
// Uses a Hilt EntryPoint to access LocalSettingsManager (a singleton) from a
// BroadcastReceiver, which cannot use constructor injection.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootReceiverEntryPoint {
    fun localSettingsManager(): LocalSettingsManager
    fun vpnManager(): VpnManager
}

class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d("BootReceiver", "Device booted — checking if VPN auto-reconnect is needed")

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootReceiverEntryPoint::class.java
        )
        val localSettings = entryPoint.localSettingsManager()
        val vpnManager    = entryPoint.vpnManager()

        scope.launch {
            try {
                // Only reconnect if the user had an active subscription at last launch
                val subscriptionActive = localSettings.isSubscriptionActiveFlow.first()
                if (!subscriptionActive) {
                    Log.d("BootReceiver", "No active subscription — skipping auto-reconnect")
                    return@launch
                }

                val lastServerId = localSettings.selectedServerIdFlow.first()
                if (lastServerId.isEmpty()) {
                    Log.d("BootReceiver", "No last server saved — skipping auto-reconnect")
                    return@launch
                }

                val killSwitch = localSettings.isKillSwitchEnabledFlow.first()
                val protocol   = localSettings.protocolFlow.first()

                Log.d("BootReceiver", "Auto-reconnecting to $lastServerId via $protocol (killSwitch=$killSwitch)")
                val success = vpnManager.startVpn(lastServerId, killSwitch, protocol)
                Log.d("BootReceiver", "Auto-reconnect result: $success")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Auto-reconnect failed: ${e.message}")
            }
        }
    }
}
