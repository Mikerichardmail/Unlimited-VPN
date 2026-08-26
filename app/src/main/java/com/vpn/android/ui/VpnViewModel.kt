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

    // ── Init guard — true once all async startup tasks have completed ────────
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

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

    // ── Purchase confirmed signal ────────────────────────────────────────
    // Fires immediately when Google Play confirms PURCHASED — before server verify.
    // Used by MainActivity to close the Paywall without waiting for the backend.
    private val _purchaseJustConfirmed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val purchaseJustConfirmed: Flow<Unit> = _purchaseJustConfirmed.receiveAsFlow()

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

            // ✅ FIX: Signal that all async init tasks are done so the UI can
            // safely read subscription/consent state for navigation decisions.
            _isInitialized.value = true

            localSettings.selectedServerIdFlow.collect { serverId ->
                _selectedServer.value = _servers.value.find { it.id == serverId }
            }
        }

        viewModelScope.launch {
            // ✅ FIX ❶: Collect PendingPurchase and only acknowledge AFTER the server
            //    confirms the token. This prevents the user being charged without getting
            //    VPN access (e.g. if the backend is temporarily down at purchase time).
            billingManager.purchaseSuccessFlow.collect { pending ->
                // Signal the UI to close the Paywall immediately — don't make the
                // user wait for server verification to see the Home screen.
                _purchaseJustConfirmed.send(Unit)

                try {
                    val response = repository.verifySubscription(
                        pending.purchaseToken,
                        pending.productId,
                        userEmail.value.ifEmpty { null }
                    )
                    if (response.success) {
                        pending.acknowledgeIfVerified()
                    } else {
                        // Server rejected the purchase token — tell the user
                        _errorMessage.value = "Purchase could not be verified: ${response.message}. " +
                            "Contact support if you were charged."
                    }
                } catch (e: retrofit2.HttpException) {
                    Log.e("VpnViewModel", "Verify purchase HTTP ${e.code()}", e)
                    _errorMessage.value = when (e.code()) {
                        503 -> "VPN access is being activated — your payment was received. " +
                            "Please tap \"Restore Purchases\" in a few minutes."
                        500 -> "Server error verifying purchase (HTTP 500). " +
                            "Tap \"Restore Purchases\" to retry, or contact support."
                        401, 403 -> "Purchase verification rejected (HTTP ${e.code()}). " +
                            "Contact support if you were charged."
                        else -> "Purchase verification failed (HTTP ${e.code()}). " +
                            "Tap \"Restore Purchases\" to retry."
                    }
                } catch (e: java.net.UnknownHostException) {
                    Log.e("VpnViewModel", "Verify purchase: no internet", e)
                    _errorMessage.value = "No internet connection. " +
                        "Tap \"Restore Purchases\" once you're connected."
                } catch (e: java.net.SocketTimeoutException) {
                    Log.e("VpnViewModel", "Verify purchase: timeout", e)
                    _errorMessage.value = "Purchase verification timed out. " +
                        "Tap \"Restore Purchases\" to retry."
                } catch (e: Exception) {
                    Log.e("VpnViewModel", "Verify purchase failed", e)
                    _errorMessage.value = "Purchase verification failed: ${e.message ?: "unknown error"}. " +
                        "Tap \"Restore Purchases\" to retry."
                }
            }
        }

        // #6 Auto-reconnect: watch for unexpected VPN drop (exponential backoff, max 5 attempts)
        viewModelScope.launch {
            var previousState = Tunnel.State.DOWN
            var reconnectAttempts = 0
            vpnState.collect { newState ->
                // Reset counter on successful connection
                if (newState == Tunnel.State.UP) reconnectAttempts = 0

                if (previousState == Tunnel.State.UP && newState == Tunnel.State.DOWN) {
                    if (isUserInitiatedDisconnect) {
                        // Expected drop because user tapped disconnect
                        isUserInitiatedDisconnect = false
                    } else {
                        // Unexpected drop, try to reconnect
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
                }
                previousState = newState
            }
        }
    }

    // ── Server loading ───────────────────────────────────────────────────

    fun loadServers() {
        viewModelScope.launch {
            _isLoadingServers.value = true
            try {
                val list = repository.getServers()
                if (list.isNotEmpty()) {
                    _servers.value = list
                    val selectedId = localSettings.selectedServerIdFlow.first()
                    _selectedServer.value = list.find { it.id == selectedId } ?: list.firstOrNull()
                    pingAllServers()
                } else {
                    _errorMessage.value = "Server list is empty. The backend returned no servers. " +
                        "Please try again later or contact support."
                }
            } catch (e: retrofit2.HttpException) {
                val code = e.code()
                _errorMessage.value = when (code) {
                    401, 403 -> "Authentication failed (HTTP $code). " +
                        "The app signature may be misconfigured."
                    429      -> "Too many requests. Please wait a moment and try again."
                    503      -> "Server is temporarily unavailable (HTTP 503). Try again later."
                    else     -> "Failed to load servers (HTTP $code): ${e.message()}"
                }
            } catch (e: java.net.UnknownHostException) {
                _errorMessage.value = "No internet connection. Connect to WiFi or mobile data and try again."
            } catch (e: java.net.SocketTimeoutException) {
                _errorMessage.value = "Connection timed out loading servers. " +
                    "Check your internet connection."
            } catch (e: Exception) {
                _errorMessage.value = "Unexpected error loading servers: ${e.message ?: e.javaClass.simpleName}"
                Log.e("VpnViewModel", "loadServers failed", e)
            } finally {
                _isLoadingServers.value = false
            }
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

    @Volatile
    private var isUserInitiatedDisconnect = false

    fun toggleVpn(onPermissionRequired: () -> Unit) {
        viewModelScope.launch {
            if (vpnState.value == Tunnel.State.UP) {
                isUserInitiatedDisconnect = true
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
            _isConnecting.value = true
            _errorMessage.value = null

            try {
                // SECURITY: Verify subscription is still active before connecting.
                // BUG 4 FIX: If the network is down (getStatus returns null), fall back
                // to the locally-cached encrypted subscription status rather than silently
                // skipping the check. This prevents unsubscribed users from connecting
                // while offline, while still allowing valid subscribers to connect.
                val status = repository.getStatus()
                val subscriptionActive = if (status != null) {
                    status.subscriptionActive
                } else {
                    // Network unavailable — use encrypted local cache as authoritative fallback
                    isSubscriptionActive.value
                }
                if (!subscriptionActive) {
                    _errorMessage.value = "Your subscription has expired or was cancelled. " +
                        "Please renew in Google Play to continue."
                    return@launch
                }

                val target     = _selectedServer.value?.id ?: getBestServerId()
                val killSwitch = isKillSwitchEnabled.value
                val protocol   = currentProtocol.value
                val success    = vpnManager.startVpn(target, killSwitch, protocol)

                if (success) {
                    val server = _selectedServer.value ?: _servers.value.find { it.id == target }
                    _isConnectedIp.value = server?.pingIp ?: ""
                    viewModelScope.launch {
                        val publicIp = fetchPublicIp()
                        if (publicIp.isNotEmpty()) _isConnectedIp.value = publicIp
                    }
                    startTimer()
                    VpnNotificationManager.update(
                        appContext,
                        VpnNotificationManager.buildConnectedNotification(
                            appContext,
                            serverCity    = server?.city    ?: "Unknown",
                            serverCountry = server?.country ?: "",
                            elapsedTime   = "00:00:00"
                        )
                    )
                    requestReviewIfEligible()
                } else {
                    _errorMessage.value = "VPN tunnel failed to start. " +
                        "The server may be unreachable. Try a different server."
                }

            } catch (e: retrofit2.HttpException) {
                _errorMessage.value = "Server check failed (HTTP ${e.code()}). " +
                    "Your connection may be blocked or the server is down."
                Log.e("VpnViewModel", "connectVpn status check failed", e)
            } catch (e: java.net.UnknownHostException) {
                _errorMessage.value = "No internet connection. Connect to WiFi or mobile data first."
            } catch (e: java.net.SocketTimeoutException) {
                _errorMessage.value = "Connection timed out. Check your internet and try again."
            } catch (e: Exception) {
                _errorMessage.value = "Connection error: ${e.message ?: e.javaClass.simpleName}"
                Log.e("VpnViewModel", "connectVpn failed", e)
            } finally {
                _isConnecting.value = false
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
                // BUG 9 FIX: Pick the most recently purchased item, not just the first.
                // A user upgrading from monthly → annual has two purchase records;
                // we must restore the higher-tier / most recent one.
                val purchase = purchases
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .maxByOrNull { it.purchaseTime }
                if (purchase != null) {
                    viewModelScope.launch {
                        try {
                            val response = repository.verifySubscription(
                                purchase.purchaseToken,
                                purchase.products.firstOrNull() ?: "vpn_annual",
                                null
                            )
                            if (response.success && !purchase.isAcknowledged) {
                                billingManager.acknowledgePurchase(purchase.purchaseToken)
                            }
                        } catch (e: Exception) {
                            Log.e("VpnViewModel", "Silent restore failed", e)
                        }
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

    fun redeemPromoCode(code: String) {
        viewModelScope.launch {
            try {
                val response = repository.verifySubscription(
                    purchaseToken = code,
                    planType = "vpn_monthly",
                    email = userEmail.value.ifEmpty { null }
                )
                if (response.success) {
                    _errorMessage.value = "Promo code redeemed successfully! You now have full access."
                    syncStatus()
                } else {
                    _errorMessage.value = "Invalid or expired promo code"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to redeem code: ${e.message}"
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    // ── Billing ───────────────────────────────────────────────────────────

    fun buySubscription(activity: Activity, planType: String) {
        viewModelScope.launch { billingManager.launchPurchaseFlow(activity, planType) }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            billingManager.queryActivePurchases { purchases ->
                val purchase = purchases
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .maxByOrNull { it.purchaseTime }

                if (purchase == null) {
                    _errorMessage.value = "No active Google Play subscription found for this account. " +
                        "Make sure you are signed in with the same Google account you used to subscribe."
                    return@queryActivePurchases
                }

                viewModelScope.launch {
                    try {
                        val response = repository.verifySubscription(
                            purchase.purchaseToken,
                            purchase.products.firstOrNull() ?: "vpn_annual",
                            null
                        )
                        if (response.success) {
                            if (!purchase.isAcknowledged) {
                                billingManager.acknowledgePurchase(purchase.purchaseToken)
                            }
                            // Success — no message needed, UI will update automatically
                        } else {
                            _errorMessage.value = "Restore failed: ${response.message}. " +
                                "If you believe this is an error, contact support."
                        }
                    } catch (e: retrofit2.HttpException) {
                        _errorMessage.value = "Restore failed — server error (HTTP ${e.code()}). Try again later."
                    } catch (e: java.net.UnknownHostException) {
                        _errorMessage.value = "No internet. Connect and tap \"Restore Purchases\" again."
                    } catch (e: Exception) {
                        _errorMessage.value = "Restore error: ${e.message ?: "unknown error"}. Try again."
                        Log.e("VpnViewModel", "restorePurchases failed", e)
                    }
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
                } catch (e: retrofit2.HttpException) {
                    // Key rotation failed — warn the user so they know their next
                    // connection may fail due to a key mismatch with the server.
                    _errorMessage.value = "Security key rotation failed (HTTP ${e.code()}). " +
                        "Your VPN connection may stop working. Please reconnect to fix this."
                    Log.e("VpnViewModel", "Key rotation failed", e)
                } catch (e: Exception) {
                    _errorMessage.value = "Security key rotation failed: ${e.message ?: "network error"}. " +
                        "Please reconnect your VPN to retry."
                    Log.e("VpnViewModel", "Key rotation failed", e)
                }
            }
        }
    }
}
