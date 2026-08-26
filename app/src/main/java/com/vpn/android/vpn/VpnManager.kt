package com.vpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.vpn.android.BuildConfig
import com.vpn.android.data.VpnRepository
import com.vpn.android.data.local.LocalSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val vpnState = wireGuardClient.state

    // BUG 8 FIX: Track the protocol used for the active tunnel in-memory.
    // DataStore writes are async — if the user switches protocol then disconnects
    // before the write flushes, protocolFlow.first() would read the wrong protocol
    // and stop the wrong VPN client (leaving the active tunnel up).
    @Volatile private var activeProtocol: String = "wireguard"

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
                    // BUG 8 FIX: Capture active protocol in-memory BEFORE async DataStore write
                    activeProtocol = protocol
                    localSettings.setSelectedServerId(serverLocation)
                    // Protocol also persisted to DataStore for crash recovery (survives process death)
                }
                success
            } catch (e: Exception) {
                // BUG 2 FIX: Always log errors in all build variants
                Log.e(TAG, "Failed to start VPN: ${e.javaClass.simpleName}: ${e.message}")
                
                // Remote error logging
                val stackTrace = Log.getStackTraceString(e)
                val deviceInfo = "OS: Android ${android.os.Build.VERSION.RELEASE}, Model: ${android.os.Build.MODEL}, AppVersion: ${BuildConfig.VERSION_NAME}"
                repository.logErrorToBackend(
                    errorType = "vpn_failure",
                    errorMessage = "${e.javaClass.simpleName}: ${e.message}",
                    stackTrace = stackTrace,
                    deviceInfo = deviceInfo
                )
                
                false
            }
        }

    /**
     * Stop the VPN tunnel.
     * BUG 8 FIX: Uses in-memory activeProtocol (set at startVpn() time) as the primary
     * source. Falls back to DataStore only if no tunnel was started in this process
     * lifetime (e.g., after a process death and restart).
     */
    suspend fun stopVpn() = withContext(Dispatchers.IO) {
        // In-memory value is authoritative for the current session
        val protocol = if (activeProtocol.isNotEmpty()) {
            activeProtocol
        } else {
            // Process restarted — fall back to persisted value
            localSettings.protocolFlow.first()
        }
        if (protocol == "openvpn") {
            openVpnClient.stop()
        } else {
            wireGuardClient.stop()
        }
        activeProtocol = "wireguard" // reset after stop
    }
}

