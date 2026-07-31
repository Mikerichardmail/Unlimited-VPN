package com.vpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vpn.android.data.VpnRepository
import com.vpn.android.data.local.LocalSettingsManager
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.config.InetNetwork
import com.wireguard.config.InetEndpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VpnRepository,
    private val localSettings: LocalSettingsManager
) {
    private val goBackend = GoBackend(context)
    private val vpnTunnel = VpnTunnel()

    val vpnState: Flow<Tunnel.State> = vpnTunnel.state

    /**
     * Check if VPN permission is required
     */
    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start the VPN tunnel for the specified server location
     */
    suspend fun startVpn(serverLocation: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Get or create keys
            val (privateKey, _) = localSettings.getOrCreateWireGuardKeys()

            // 2. Fetch device wireguard config from backend
            val response = repository.registerDevice(serverLocation)
            if (!response.success || response.config == null) {
                return@withContext false
            }

            val remoteConfig = response.config

            // 3. Build Interface
            val interfaceBuilder = Interface.Builder()
                .parsePrivateKey(privateKey)
                .addAddress(InetNetwork.parse(remoteConfig.clientIp))

            // Add DNS servers
            remoteConfig.dns.split(",").map { it.trim() }.forEach { dns ->
                interfaceBuilder.addDnsServer(InetAddress.getByName(dns))
            }

            // 4. Build Peer
            val peerBuilder = Peer.Builder()
                .parsePublicKey(remoteConfig.serverPubkey)
                .setEndpoint(InetEndpoint.parse(remoteConfig.serverEndpoint))
                .addAllowedIp(InetNetwork.parse(remoteConfig.allowedIps))

            if (remoteConfig.keepalive > 0) {
                peerBuilder.setPersistentKeepalive(remoteConfig.keepalive)
            }

            // 5. Build Config
            val config = Config.Builder()
                .setInterface(interfaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            // 6. Set Tunnel State to UP
            goBackend.setState(vpnTunnel, Tunnel.State.UP, config)
            localSettings.setSelectedServerId(serverLocation)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Stop the VPN tunnel
     */
    suspend fun stopVpn() = withContext(Dispatchers.IO) {
        try {
            goBackend.setState(vpnTunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
