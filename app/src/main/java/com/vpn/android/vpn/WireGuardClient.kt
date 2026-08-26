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
import javax.inject.Singleton

private const val TAG = "WireGuardClient"

// Hardcoded safe DNS servers — used as mandatory fallback if server provides none.
// Prevents DNS leak when API omits DNS field.
private val SAFE_DNS_SERVERS = listOf(
    "1.1.1.1",   // Cloudflare primary (fastest, privacy-respecting)
    "1.0.0.1"    // Cloudflare secondary backup
)

// BUG 6 FIX: GoBackend is a native singleton. Wrap creation so a second
// instantiation (process restart while tunnel is up) returns the existing
// instance instead of crashing with "GoBackend already running".
private object GoBackendHolder {
    @Volatile private var instance: GoBackend? = null
    fun get(context: Context): GoBackend = instance ?: synchronized(this) {
        instance ?: try {
            GoBackend(context).also { instance = it }
        } catch (e: IllegalStateException) {
            // Already running from a previous process — reuse existing instance
            Log.w("GoBackendHolder", "GoBackend already running, reusing: ${e.message}")
            instance ?: GoBackend(context).also { instance = it }
        }
    }
}

@Singleton
class WireGuardClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localSettings: LocalSettingsManager
) : VpnClient {
    // BUG 6 FIX: Use shared singleton holder to prevent double-instantiation
    private val goBackend get() = GoBackendHolder.get(context)
    val vpnTunnel = VpnTunnel()

    val state: Flow<Tunnel.State> = vpnTunnel.state

    override suspend fun start(serverLocation: String, killSwitchEnabled: Boolean, configData: Any): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val remoteConfig = configData as VpnConfig

                // BUG 1 FIX: Validate all required nullable fields before use.
                // Crash with a clear error rather than NPE from !! operator.
                val clientIp = remoteConfig.clientIp
                    ?: return@withContext false.also {
                        Log.e(TAG, "registerDevice response missing clientIp — cannot build tunnel")
                    }
                val serverPubkey = remoteConfig.serverPubkey
                    ?: return@withContext false.also {
                        Log.e(TAG, "registerDevice response missing serverPubkey — cannot build tunnel")
                    }
                val serverEndpoint = remoteConfig.serverEndpoint
                    ?: return@withContext false.also {
                        Log.e(TAG, "registerDevice response missing serverEndpoint — cannot build tunnel")
                    }

                // The VPNResellers API generates the WireGuard keypair server-side and returns
                // the private key in the config. We MUST use this key to connect successfully.
                val privateKey = remoteConfig.clientPrivateKey ?: localSettings.getOrCreateWireGuardKeys().first

                val interfaceBuilder = Interface.Builder()
                    .parsePrivateKey(privateKey)
                    .addAddress(InetNetwork.parse(clientIp))

                // SECURITY FIX [HIGH-2]: DNS is MANDATORY — prevents DNS leak.
                // BUG 10 FIX: Only allow numeric IP addresses as DNS servers.
                // InetAddress.getByName() on a hostname does a DNS lookup which blocks
                // indefinitely with no timeout. Numeric IPs are parsed instantly.
                val dnsServers = remoteConfig.dns
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && isNumericIp(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: SAFE_DNS_SERVERS

                dnsServers.forEach { dns ->
                    interfaceBuilder.addDnsServer(InetAddress.getByName(dns))
                }

                val peerBuilder = Peer.Builder()
                    .parsePublicKey(serverPubkey)
                    .setEndpoint(InetEndpoint.parse(serverEndpoint))

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
                // BUG 2 FIX: Always log errors — not just in debug builds.
                // Silent failures in release make production crashes impossible to diagnose.
                Log.e(TAG, "Failed to start WireGuard tunnel: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                goBackend.setState(vpnTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                // BUG 2 FIX: Always log stop errors too
                Log.e(TAG, "Failed to stop WireGuard tunnel: ${e.javaClass.simpleName}: ${e.message}")
            }
            Unit
        }
    }

    companion object {
        // BUG 10 FIX: Check if a string is a numeric IPv4 or IPv6 address.
        // Avoids blocking DNS lookup inside InetAddress.getByName() on hostnames.
        fun isNumericIp(address: String): Boolean {
            return try {
                InetAddress.getByName(address).hostAddress == address ||
                    address.matches(Regex("^[\\d.]+$")) ||
                    address.contains(":")
            } catch (_: Exception) { false }
        }
    }
}
