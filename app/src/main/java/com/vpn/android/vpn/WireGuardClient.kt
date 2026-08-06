package com.vpn.android.vpn

import android.content.Context
import com.vpn.android.data.local.LocalSettingsManager
import com.vpn.android.data.models.VpnConfig
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.config.InetNetwork
import com.wireguard.config.InetEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import android.util.Log
import com.vpn.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "WireGuardClient"

// Hardcoded safe DNS servers — used as mandatory fallback if server provides none.
// Prevents DNS leak when API omits DNS field.
private val SAFE_DNS_SERVERS = listOf(
    "1.1.1.1",   // Cloudflare primary (fastest, privacy-respecting)
    "1.0.0.1"    // Cloudflare secondary backup
)

class WireGuardClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localSettings: LocalSettingsManager
) : VpnClient {
    private val goBackend = GoBackend(context)
    val vpnTunnel = VpnTunnel()

    val state: Flow<Tunnel.State> = vpnTunnel.state

    override suspend fun start(serverLocation: String, killSwitchEnabled: Boolean, configData: Any): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val remoteConfig = configData as VpnConfig

                // The VPNResellers API generates the WireGuard keypair server-side and returns
                // the private key in the config. We MUST use this key to connect successfully.
                val privateKey = remoteConfig.clientPrivateKey ?: localSettings.getOrCreateWireGuardKeys().first

                val interfaceBuilder = Interface.Builder()
                    .parsePrivateKey(privateKey)
                    .addAddress(InetNetwork.parse(remoteConfig.clientIp!!))

                // SECURITY FIX [HIGH-2]: DNS is MANDATORY — prevents DNS leak.
                // If the server omits DNS, use safe hardcoded fallbacks (Cloudflare 1.1.1.1).
                // Without this, the device would use ISP DNS even while VPN is active.
                val dnsServers = remoteConfig.dns
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: SAFE_DNS_SERVERS

                dnsServers.forEach { dns ->
                    interfaceBuilder.addDnsServer(InetAddress.getByName(dns))
                }

                val peerBuilder = Peer.Builder()
                    .parsePublicKey(remoteConfig.serverPubkey!!)
                    .setEndpoint(InetEndpoint.parse(remoteConfig.serverEndpoint!!))

                // SECURITY FIX [HIGH-2 + HIGH-3]: Always force full-tunnel routing.
                // Both IPv4 (0.0.0.0/0) AND IPv6 (::/0) must be in AllowedIPs to prevent
                // IPv6 leak. Kill switch mode enforces this unconditionally.
                val allowedIpv4 = InetNetwork.parse("0.0.0.0/0")
                val allowedIpv6 = InetNetwork.parse("::/0")
                peerBuilder
                    .addAllowedIp(allowedIpv4)
                    .addAllowedIp(allowedIpv6)

                if ((remoteConfig.keepalive ?: 0) > 0) {
                    peerBuilder.setPersistentKeepalive(remoteConfig.keepalive!!)
                }

                val config = Config.Builder()
                    .setInterface(interfaceBuilder.build())
                    .addPeer(peerBuilder.build())
                    .build()

                goBackend.setState(vpnTunnel, Tunnel.State.UP, config)
                true
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to start WireGuard tunnel", e)
                }
                false
            }
        }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                goBackend.setState(vpnTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to stop WireGuard tunnel", e)
                }
            }
            Unit
        }
    }
}
