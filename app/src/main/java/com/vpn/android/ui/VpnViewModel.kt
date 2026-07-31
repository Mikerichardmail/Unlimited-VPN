package com.vpn.android.ui
import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.vpn.android.data.VpnRepository
import com.wireguard.crypto.KeyPair
import com.vpn.android.data.billing.BillingManager
import com.vpn.android.data.local.LocalSettingsManager
import com.vpn.android.data.models.Server
import com.vpn.android.vpn.VpnManager
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val repository: VpnRepository,
    private val localSettings: LocalSettingsManager,
    private val billingManager: BillingManager
) : ViewModel() {

    // Connection states
    val vpnState = vpnManager.vpnState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Tunnel.State.DOWN
    )

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<Server?>(null)
    val selectedServer: StateFlow<Server?> = _selectedServer.asStateFlow()

    // Local states synced from preferences
    val isSubscriptionActive = localSettings.isSubscriptionActiveFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val isTrialActive = localSettings.isTrialActiveFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val trialTimeRemainingMillis = localSettings.trialTimeRemainingMillisFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 3L * 24L * 60L * 60L * 1000L
    )

    val effectiveSubscriptionActive = combine(isSubscriptionActive, isTrialActive) { subActive, trialActive ->
        subActive || trialActive
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val subscriptionExpiry = localSettings.subscriptionExpiryFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    val isKillSwitchEnabled = localSettings.isKillSwitchEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val userEmail = localSettings.emailFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    // Bandwidth usage
    private val _bandwidthUsed = MutableStateFlow(0L)
    val bandwidthUsed: StateFlow<Long> = _bandwidthUsed.asStateFlow()

    private val _bandwidthLimit = MutableStateFlow(53687091200L) // 50 GB default
    val bandwidthLimit: StateFlow<Long> = _bandwidthLimit.asStateFlow()

    private val _isConnectedIp = MutableStateFlow("")
    val isConnectedIp: StateFlow<String> = _isConnectedIp.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _devicesCount = MutableStateFlow(1)
    val devicesCount: StateFlow<Int> = _devicesCount.asStateFlow()

    init {
        viewModelScope.launch {
            // Load and cache initial data
            localSettings.getOrCreateInstallationId()
            localSettings.getOrCreateWireGuardKeys()
            localSettings.getOrCreateTrialStartedAt()
            
            loadServers()
            syncStatus()
            checkAndRotateKeys()
            
            // Listen to selected server pref
            localSettings.selectedServerIdFlow.collect { serverId ->
                _selectedServer.value = _servers.value.find { it.id == serverId }
            }
        }

        viewModelScope.launch {
            billingManager.purchaseSuccessFlow.collect { (purchaseToken, planType) ->
                repository.verifySubscription(purchaseToken, planType, userEmail.value.ifEmpty { null })
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            val list = repository.getServers()
            if (list.isNotEmpty()) {
                _servers.value = list
                val selectedId = localSettings.selectedServerIdFlow.first()
                _selectedServer.value = list.find { it.id == selectedId } ?: list.firstOrNull()
            } else {
                // Mock list if API fails or offline
                val mockServers = listOf(
                    Server("in", "India", "Mumbai", "bom.vpnapp.in:51820", "IN_PUB_KEY", "10.0.0.1", 20, 15),
                    Server("us", "USA", "Ashburn", "iad.vpnapp.in:51820", "US_PUB_KEY", "10.0.1.1", 120, 30),
                    Server("sg", "Singapore", "Singapore", "sin.vpnapp.in:51820", "SG_PUB_KEY", "10.0.2.1", 45, 10)
                )
                _servers.value = mockServers
                _selectedServer.value = mockServers.first()
            }
        }
    }

    fun syncStatus() {
        viewModelScope.launch {
            val status = repository.getStatus()
            if (status != null) {
                _bandwidthUsed.value = status.bandwidthUsedBytes
                _bandwidthLimit.value = status.bandwidthLimitBytes
                _devicesCount.value = status.devicesCount
            }
        }
    }

    fun toggleVpn(onPermissionRequired: () -> Unit) {
        viewModelScope.launch {
            if (vpnState.value == Tunnel.State.UP) {
                _isConnecting.value = false
                vpnManager.stopVpn()
                _isConnectedIp.value = ""
            } else {
                val permissionIntent = vpnManager.prepareVpnIntent()
                if (permissionIntent != null) {
                    onPermissionRequired()
                } else {
                    connectVpn()
                }
            }
        }
    }

    fun getBestServerId(): String {
        return _servers.value.minByOrNull { it.latencyMs }?.id ?: "in"
    }

    fun connectVpn() {
        viewModelScope.launch {
            _isConnecting.value = true
            val target = _selectedServer.value?.id ?: getBestServerId()
            val success = vpnManager.startVpn(target)
            _isConnecting.value = false
            if (success) {
                _isConnectedIp.value = _selectedServer.value?.pingIp 
                    ?: _servers.value.find { it.id == target }?.pingIp 
                    ?: "10.0.0.2"
            }
        }
    }

    fun selectServer(server: Server) {
        viewModelScope.launch {
            _selectedServer.value = server
            localSettings.setSelectedServerId(server.id)
            if (vpnState.value == Tunnel.State.UP) {
                // Reconnect with new server
                vpnManager.stopVpn()
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
                connectVpn()
            }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            localSettings.setKillSwitchEnabled(enabled)
            // In a production app, update the VPN engine configuration
        }
    }

    fun saveEmail(email: String) {
        viewModelScope.launch {
            localSettings.setEmail(email)
        }
    }

    fun buySubscription(activity: Activity, planType: String) {
        viewModelScope.launch {
            billingManager.launchPurchaseFlow(activity, planType)
        }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            billingManager.queryActivePurchases { purchases ->
                val purchase = purchases.firstOrNull()
                if (purchase != null && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val purchaseToken = purchase.purchaseToken
                    val planType = purchase.products.firstOrNull() ?: "yearly"
                    viewModelScope.launch {
                        repository.verifySubscription(purchaseToken, planType, null)
                    }
                }
            }
        }
    }

    fun deregisterCurrentDevice(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val response = repository.deregisterDevice()
            if (response.success) {
                vpnManager.stopVpn()
                _isConnectedIp.value = ""
                syncStatus()
                onComplete(true)
            } else {
                onComplete(false)
            }
        }
    }

    fun getVpnSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun checkAndRotateKeys() {
        viewModelScope.launch {
            val lastRotation = localSettings.lastRotationTimeFlow.first()
            val thirtyDaysInMillis = 30L * 24L * 60L * 60L * 1000L
            if (System.currentTimeMillis() - lastRotation >= thirtyDaysInMillis) {
                val keyPair = KeyPair()
                val newPrivateKey = keyPair.privateKey.toBase64()
                val newPublicKey = keyPair.publicKey.toBase64()
                try {
                    repository.rotateKeys(newPrivateKey, newPublicKey)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun prepareVpnIntent() = vpnManager.prepareVpnIntent()
}
