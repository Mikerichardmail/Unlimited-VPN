package com.vpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.vpn.android.BuildConfig
import com.vpn.android.data.VpnRepository
import com.vpn.android.data.local.LocalSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VpnManager"

@Singleton
class VpnManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: VpnRepository,
    private val localSettings: LocalSettingsManager,
    private val wireGuardClient: WireGuardClient,
    private val openVpnClient: OpenVpnClient
) {
    // Current active client state could be tracked here, or just delegate to WG client state for now
    val vpnState = wireGuardClient.state
    
    private var activeProtocol = "wireguard"

    /**
     * Check if VPN permission is required
     */
    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start the VPN tunnel for the specified server location.
     *
     * @param serverLocation  The server ID to connect to (e.g. "in", "us", "sg")
     * @param killSwitchEnabled  When true, ALL traffic is routed through the VPN (0.0.0.0/0).
     *   This means if the VPN drops, no traffic flows at all — a true software kill switch.
     *   When false, only the server-specified allowedIps are routed through the tunnel.
     */
    suspend fun startVpn(serverLocation: String, killSwitchEnabled: Boolean = false, protocol: String = "wireguard"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                activeProtocol = protocol
                // 1. Register/Fetch device config from backend
                val response = repository.registerDevice(serverLocation, protocol)
                if (!response.success || response.config == null) {
                    return@withContext false
                }

                val remoteConfig = response.config

                // 2. Delegate to the correct client
                val success = if (protocol == "openvpn") {
                    openVpnClient.start(serverLocation, killSwitchEnabled, remoteConfig.ovpnConfig ?: "")
                } else {
                    wireGuardClient.start(serverLocation, killSwitchEnabled, remoteConfig)
                }

                if (success) {
                    localSettings.setSelectedServerId(serverLocation)
                }
                success
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to start VPN", e)
                }
                false
            }
        }

    /**
     * Stop the VPN tunnel
     */
    suspend fun stopVpn() = withContext(Dispatchers.IO) {
        if (activeProtocol == "openvpn") {
            openVpnClient.stop()
        } else {
            wireGuardClient.stop()
        }
    }
}
