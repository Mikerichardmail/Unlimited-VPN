package com.vpn.android.ui

import android.app.Activity
import android.content.Context
import android.net.TrafficStats
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.google.android.play.core.review.ReviewManagerFactory
import com.vpn.android.BuildConfig
import com.vpn.android.data.VpnRepository
import com.wireguard.crypto.KeyPair
import com.vpn.android.data.billing.BillingManager
import com.vpn.android.data.local.LocalSettingsManager
import com.vpn.android.data.models.Server
import com.vpn.android.vpn.VpnManager
import com.vpn.android.vpn.VpnNotificationManager
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val repository: VpnRepository,
    private val localSettings: LocalSettingsManager,
    private val billingManager: BillingManager,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    // ── VPN connection state ─────────────────────────────────────────────
    val vpnState = vpnManager.vpnState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), Tunnel.State.DOWN
    )

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<Server?>(null)
    val selectedServer: StateFlow<Server?> = _selectedServer.asStateFlow()

    // ── Subscription ─────────────────────────────────────────────────────
    val isSubscriptionActive = localSettings.isSubscriptionActiveFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    // effectiveSubscriptionActive is now simply whether the user has a paid subscription.
    val effectiveSubscriptionActive = isSubscriptionActive

    val subscriptionExpiry = localSettings.subscriptionExpiryFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )
    val isKillSwitchEnabled = localSettings.isKillSwitchEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val userEmail = localSettings.emailFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )
    val currentProtocol = localSettings.protocolFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "wireguard"
    )
    val consentAccepted = localSettings.consentAcceptedFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    fun acceptConsent() {
        viewModelScope.launch { localSettings.setConsentAccepted() }
    }

    // ── Live prices from Google Play (local currency per country) ────────────
    private val _productPrices = MutableStateFlow<Map<String, String>>(emptyMap())
    val productPrices: StateFlow<Map<String, String>> = _productPrices.asStateFlow()

    fun fetchProductPrices() {
        billingManager.queryProductPrices { prices ->
            if (prices.isNotEmpty()) {
                viewModelScope.launch { _productPrices.emit(prices) }
            }
        }
    }

    // ── Bandwidth ────────────────────────────────────────────────────────
    private val _bandwidthUsed  = MutableStateFlow(0L)
    val bandwidthUsed: StateFlow<Long> = _bandwidthUsed.asStateFlow()

    private val _bandwidthLimit = MutableStateFlow(53687091200L) // 50 GB
    val bandwidthLimit: StateFlow<Long> = _bandwidthLimit.asStateFlow()

    private val _isConnectedIp = MutableStateFlow("")
    val isConnectedIp: StateFlow<String> = _isConnectedIp.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _devicesCount = MutableStateFlow(1)
    val devicesCount: StateFlow<Int> = _devicesCount.asStateFlow()

    // ── #1 Connection timer ──────────────────────────────────────────────
    // Counts seconds since VPN connected. Resets on disconnect.
    private val _connectionTimerSeconds = MutableStateFlow(0L)
    val connectionTimerSeconds: StateFlow<Long> = _connectionTimerSeconds.asStateFlow()
    private var timerJob: Job? = null

    // ── Real-time speed meter ────────────────────────────────────────────
    private val _downloadSpeed = MutableStateFlow(0L) // bytes/sec
    val downloadSpeed: StateFlow<Long> = _downloadSpeed.asStateFlow()
    private val _uploadSpeed = MutableStateFlow(0L)   // bytes/sec
    val uploadSpeed: StateFlow<Long> = _uploadSpeed.asStateFlow()
    private var speedTrackerJob: Job? = null

    // ── #2 Error state ───────────────────────────────────────────────────
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── #7 Live latency ──────────────────────────────────────────────────
    private val _serverLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
    val serverLatencies: StateFlow<Map<String, Int>> = _serverLatencies.asStateFlow()

    // ── #3 Servers loading state ─────────────────────────────────────────
    private val _isLoadingServers = MutableStateFlow(true)
    val isLoadingServers: StateFlow<Boolean> = _isLoadingServers.asStateFlow()

    init {
        viewModelScope.launch {
            localSettings.getOrCreateInstallationId()
            localSettings.getOrCreateWireGuardKeys()

            loadServers()
            syncStatus()
            checkAndRotateKeys()

            // Silent restore on launch — check Google Play for existing subscription
            silentRestoreOnLaunch()

            localSettings.selectedServerIdFlow.collect { serverId ->
                _selectedServer.value = _servers.value.find { it.id == serverId }
            }
        }

        viewModelScope.launch {
            // ✅ FIX ❶: Collect PendingPurchase and only acknowledge AFTER the server
            //    confirms the token. This prevents the user being charged without getting
            //    VPN access (e.g. if the backend is temporarily down at purchase time).
            billingManager.purchaseSuccessFlow.collect { pending ->
                val response = repository.verifySubscription(
                    pending.purchaseToken,
                    pending.productId,
                    userEmail.value.ifEmpty { null }
                )
                if (response.success) {
                    // Server confirmed — now safe to acknowledge to Google
                    pending.acknowledgeIfVerified()
                }
                // If verify fails, we do NOT acknowledge. Google will retry the
                // purchase flow and the user can contact support. The token is
                // NOT consumed, so no double-charge occurs.
            }
        }

        // #6 Auto-reconnect: watch for unexpected VPN drop (exponential backoff, max 5 attempts)
        viewModelScope.launch {
            var previousState = Tunnel.State.DOWN
            var reconnectAttempts = 0
            vpnState.collect { newState ->
                // Reset counter on successful connection
                if (newState == Tunnel.State.UP) reconnectAttempts = 0

                // Tunnel went DOWN but we didn't trigger it (not from toggleVpn)
                if (previousState == Tunnel.State.UP && newState == Tunnel.State.DOWN && !_isConnecting.value) {
                    stopTimer()
                    VpnNotificationManager.update(appContext, VpnNotificationManager.buildDisconnectedNotification(appContext))
                    if (reconnectAttempts < 5) {
                        // Exponential backoff: 2s, 4s, 8s, 16s, 32s
                        val backoffMs = minOf(2000L * (1L shl reconnectAttempts), 32_000L)
                        delay(backoffMs)
                        if (vpnState.value == Tunnel.State.DOWN) {
                            reconnectAttempts++
                            _errorMessage.value = "VPN dropped — reconnecting… (attempt $reconnectAttempts/5)"
                            connectVpn()
                        }
                    } else {
                        _errorMessage.value = "Connection lost. Please tap to reconnect."
                    }
                }
                previousState = newState
            }
        }
    }

    // ── Server loading ───────────────────────────────────────────────────

    private fun loadServers() {
        viewModelScope.launch {
            _isLoadingServers.value = true
            val list = repository.getServers()
            if (list.isNotEmpty()) {
                _servers.value = list
                val selectedId = localSettings.selectedServerIdFlow.first()
                _selectedServer.value = list.find { it.id == selectedId } ?: list.firstOrNull()
            } else {
                // ✅ FIX ❹: DO NOT fall back to mock servers with fake placeholder pubkeys
                //    ("IN_PUB_KEY" etc). WireGuard would silently fail to connect while
                //    showing the UI as if servers are available. Show an error instead.
                _errorMessage.value = "Could not load server list. Please check your internet connection and try again."
            }
            _isLoadingServers.value = false
            // Start latency ping after list is loaded
            pingAllServers()
        }
    }

    // ── #7 Live latency ping ─────────────────────────────────────────────

    private fun pingAllServers() {
        _servers.value.forEach { server ->
            viewModelScope.launch(Dispatchers.IO) {
                val ms = measureLatencyMs(server.pingIp)
                if (ms > 0) {
                    _serverLatencies.update { it + (server.id to ms) }
                }
            }
        }
    }

    private suspend fun measureLatencyMs(host: String): Int {
        // Use TCP socket to port 443 — InetAddress.isReachable() requires root on Android
        // and silently returns wrong values on non-rooted devices.
        return try {
            val start = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, 443), 2000)
                }
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) { -1 }
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    fun syncStatus() {
        viewModelScope.launch {
            val status = repository.getStatus()
            if (status != null) {
                _bandwidthUsed.value  = status.bandwidthUsedBytes
                _bandwidthLimit.value = status.bandwidthLimitBytes
                _devicesCount.value   = status.devicesCount
            }
        }
    }

    // ── VPN connect / disconnect ─────────────────────────────────────────

    fun toggleVpn(onPermissionRequired: () -> Unit) {
        viewModelScope.launch {
            if (vpnState.value == Tunnel.State.UP) {
                _isConnecting.value = false
                vpnManager.stopVpn()
                _isConnectedIp.value = ""
                stopTimer()
                VpnNotificationManager.cancel(appContext)
            } else {
                val permissionIntent = vpnManager.prepareVpnIntent()
                if (permissionIntent != null) onPermissionRequired()
                else connectVpn()
            }
        }
    }

    fun getBestServerId(): String =
        // Prefer live-measured latency; fall back to static API value if ping hasn't run yet
        _servers.value.minByOrNull { _serverLatencies.value[it.id] ?: it.latencyMs }
            ?.id ?: _servers.value.firstOrNull()?.id ?: "in"

    fun connectVpn() {
        viewModelScope.launch {
            _isConnecting.value  = true
            _errorMessage.value  = null

            // SECURITY: Sync subscription status from server before connecting.
            // This catches cancellations/expirations that happened since last app open
            // (e.g. refund processed, subscription expired). Without this, a user who
            // cancelled could keep using the VPN until the next app restart.
            val status = repository.getStatus()
            if (status != null && !status.subscriptionActive) {
                _isConnecting.value = false
                _errorMessage.value = "Your subscription is no longer active. Please renew to continue."
                return@launch
            }

            val target = _selectedServer.value?.id ?: getBestServerId()
            val killSwitch = isKillSwitchEnabled.value
            val protocol = currentProtocol.value
            val success = vpnManager.startVpn(target, killSwitch, protocol)
            _isConnecting.value = false

            if (success) {
                val server = _selectedServer.value ?: _servers.value.find { it.id == target }
                // Fetch real public VPN IP in background; show server IP instantly as placeholder
                _isConnectedIp.value = server?.pingIp ?: ""
                viewModelScope.launch {
                    val publicIp = fetchPublicIp()
                    if (publicIp.isNotEmpty()) _isConnectedIp.value = publicIp
                }
                startTimer()

                // Update persistent notification
                VpnNotificationManager.update(
                    appContext,
                    VpnNotificationManager.buildConnectedNotification(
                        appContext,
                        serverCity    = server?.city    ?: "Unknown",
                        serverCountry = server?.country ?: "",
                        elapsedTime   = "00:00:00"
                    )
                )

                // #5 In-app review prompt after first successful connection
                requestReviewIfEligible()
            } else {
                // #2 Show error feedback
                _errorMessage.value = "Connection failed. Check your network and try again."
            }
        }
    }

    // ── #1 Connection timer ───────────────────────────────────────────────

    private fun startTimer() {
        timerJob?.cancel()
        _connectionTimerSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _connectionTimerSeconds.value += 1
                // Only push a notification update every 60 seconds to prevent
                // Binder IPC battery drain. The on-screen timer still ticks every second.
                if (_connectionTimerSeconds.value % 60L == 0L) {
                    val server  = _selectedServer.value
                    val elapsed = formatTimer(_connectionTimerSeconds.value)
                    VpnNotificationManager.update(
                        appContext,
                        VpnNotificationManager.buildConnectedNotification(
                            appContext,
                            serverCity     = server?.city    ?: "Unknown",
                            serverCountry  = server?.country ?: "",
                            elapsedTime    = elapsed,
                            downloadSpeed  = formatSpeed(_downloadSpeed.value)
                        )
                    )
                }
            }
        }
        startSpeedTracker()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _connectionTimerSeconds.value = 0L
        stopSpeedTracker()
    }

    fun formatTimer(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ── Real-time speed tracker ───────────────────────────────────────────

    private fun startSpeedTracker() {
        speedTrackerJob?.cancel()
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        speedTrackerJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                val deltaRx = (currentRx - lastRx).coerceAtLeast(0)
                val deltaTx = (currentTx - lastTx).coerceAtLeast(0)
                _downloadSpeed.value = deltaRx
                _uploadSpeed.value   = deltaTx
                lastRx = currentRx
                lastTx = currentTx
            }
        }
    }

    private fun stopSpeedTracker() {
        speedTrackerJob?.cancel()
        speedTrackerJob = null
        _downloadSpeed.value = 0L
        _uploadSpeed.value   = 0L
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000     -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
            else                     -> "$bytesPerSec B/s"
        }
    }

    // ── #5 In-app review ─────────────────────────────────────────────────

    private var hasRequestedReview = false

    private fun requestReviewIfEligible() {
        if (hasRequestedReview) return
        // Only prompt after user has a real subscription
        if (!isSubscriptionActive.value) return
        hasRequestedReview = true
        // Actual launch happens from Activity — expose a trigger flow
        _triggerReview.value = true
    }

    private val _triggerReview = MutableStateFlow(false)
    val triggerReview: StateFlow<Boolean> = _triggerReview.asStateFlow()

    fun onReviewLaunched() { _triggerReview.value = false }

    // ── #9 Silent restore on launch ───────────────────────────────────────

    private fun silentRestoreOnLaunch() {
        viewModelScope.launch {
            if (isSubscriptionActive.value) return@launch
            billingManager.queryActivePurchases { purchases ->
                val purchase = purchases.firstOrNull()
                if (purchase != null && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    viewModelScope.launch {
                        repository.verifySubscription(
                            purchase.purchaseToken,
                            purchase.products.firstOrNull() ?: "vpn_annual",
                            null
                        )
                    }
                }
            }
        }
    }

    // ── Server selection ──────────────────────────────────────────────────

    fun selectServer(server: Server) {
        viewModelScope.launch {
            _selectedServer.value = server
            localSettings.setSelectedServerId(server.id)
            if (vpnState.value == Tunnel.State.UP) {
                vpnManager.stopVpn()
                stopTimer()
                connectVpn()
            }
        }
    }

    fun selectAutoServer() {
        viewModelScope.launch {
            _selectedServer.value = null
            localSettings.setSelectedServerId("")
            if (vpnState.value == Tunnel.State.UP) {
                vpnManager.stopVpn()
                stopTimer()
                connectVpn()
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch { localSettings.setKillSwitchEnabled(enabled) }
    }

    fun setProtocol(protocol: String) {
        viewModelScope.launch {
            localSettings.setProtocol(protocol)
            if (vpnState.value == Tunnel.State.UP) {
                vpnManager.stopVpn()
                stopTimer()
                connectVpn()
            }
        }
    }

    fun saveEmail(email: String) {
        viewModelScope.launch { localSettings.setEmail(email) }
    }

    fun clearError() { _errorMessage.value = null }

    // ── Billing ───────────────────────────────────────────────────────────

    fun buySubscription(activity: Activity, planType: String) {
        viewModelScope.launch { billingManager.launchPurchaseFlow(activity, planType) }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            billingManager.queryActivePurchases { purchases ->
                // Pick the most recent PURCHASED item (not just the first in the list)
                val purchase = purchases
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .maxByOrNull { it.purchaseTime }
                if (purchase != null) {
                    viewModelScope.launch {
                        repository.verifySubscription(
                            purchase.purchaseToken,
                            purchase.products.firstOrNull() ?: "vpn_annual",
                            null
                        )
                    }
                } else {
                    _errorMessage.value = "No active subscription found."
                }
            }
        }
    }

    // ── Fetch real public IP after connecting ──────────────────────────────

    private suspend fun fetchPublicIp(): String {
        return try {
            withContext(Dispatchers.IO) {
                val url = java.net.URL("https://api.ipify.org")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout    = 3000
                conn.inputStream.bufferedReader().readText().trim()
            }
        } catch (_: Exception) { "" }
    }

    fun deregisterCurrentDevice(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val response = repository.deregisterDevice(protocol = currentProtocol.value)
            if (response.success) {
                vpnManager.stopVpn()
                stopTimer()
                VpnNotificationManager.cancel(appContext)
                _isConnectedIp.value = ""
                syncStatus()
                onComplete(true)
            } else {
                _errorMessage.value = "Failed to deregister device. Try again."
                onComplete(false)
            }
        }
    }

    fun deleteAccount(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val response = repository.deleteAccount()
            if (response.success) {
                vpnManager.stopVpn()
                stopTimer()
                VpnNotificationManager.cancel(appContext)
                onComplete(true)
            } else {
                _errorMessage.value = "Failed to delete account. Try again."
                onComplete(false)
            }
        }
    }

    fun getVpnSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    fun prepareVpnIntent() = vpnManager.prepareVpnIntent()

    private fun checkAndRotateKeys() {
        viewModelScope.launch {
            val lastRotation = localSettings.lastRotationTimeFlow.first()
            val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L
            if (System.currentTimeMillis() - lastRotation >= thirtyDaysMs) {
                val keyPair = KeyPair()
                try {
                    repository.rotateKeys(keyPair.privateKey.toBase64(), keyPair.publicKey.toBase64())
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("VpnViewModel", "Key rotation failed", e)
                }
            }
        }
    }
}
