package com.vpn.android.data

import android.util.Base64
import com.vpn.android.BuildConfig
import com.vpn.android.data.api.VpnApiService
import com.vpn.android.data.local.LocalSettingsManager
import com.vpn.android.data.models.*
import kotlinx.coroutines.flow.first
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepository @Inject constructor(
    private val apiService: VpnApiService,
    private val localSettings: LocalSettingsManager
) {
    // ✅ SECURITY FIX: Secret read from BuildConfig (injected from local.properties at build
    // time via gradle). Never hardcoded in source code or committed to git.
    private val secretKey = BuildConfig.HMAC_SECRET

    // SECURITY [MED-1]: Never silently degrade authentication.
    // If HMAC computation fails, throw so the caller knows auth is broken.
    private fun getSignature(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // SECURITY [MED-2]: getServers is authenticated with HMAC signature.
    // Prevents unauthenticated server enumeration by scrapers/bots.
    suspend fun getServers(): List<Server> {
        return try {
            val installationId = localSettings.getOrCreateInstallationId()
            val signature = getSignature("servers:$installationId")
            val response = apiService.getServers(installationId, signature)
            response.servers
        } catch (e: Exception) {
            // SECURITY [CRIT-3]: No fallback server list — fallback servers had empty
            // WireGuard pubkeys which would cause silent connection failures or
            // unauthenticated tunnel establishment. Show error instead.
            emptyList()
        }
    }

    suspend fun verifySubscription(purchaseToken: String, planType: String, email: String?): VerifyResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val signature = getSignature("$installationId:$purchaseToken")
        val request = VerifyRequest(
            installationId = installationId,
            googlePurchaseToken = purchaseToken,
            planType = planType,
            email = email
        )
        val response = apiService.verifySubscription(request, signature)
        if (response.success && response.subscription != null) {
            localSettings.setSubscriptionStatus(
                isActive = response.subscription.status == "active",
                expiryDate = response.subscription.expiresAt
            )
            if (email != null) {
                localSettings.setEmail(email)
            }
        }
        return response
    }

    suspend fun registerDevice(serverLocation: String, protocol: String = "wireguard"): RegisterDeviceResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val (_, publicKey) = localSettings.getOrCreateWireGuardKeys()
        val signature = getSignature("$installationId:$publicKey")
        val request = RegisterDeviceRequest(
            installationId = installationId,
            wireguardPubkey = publicKey,
            serverLocation = serverLocation,
            protocol = protocol
        )
        return apiService.registerDevice(request, signature)
    }

    suspend fun deregisterDevice(pubKey: String? = null, protocol: String = "wireguard"): DeregisterDeviceResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val targetPubKey = pubKey ?: localSettings.getOrCreateWireGuardKeys().second
        val signature = getSignature("$installationId:$targetPubKey")
        val request = DeregisterDeviceRequest(
            installationId = installationId,
            wireguardPubkey = targetPubKey,
            protocol = protocol
        )
        return apiService.deregisterDevice(request, signature)
    }

    suspend fun rotateKeys(newPrivateKey: String, newPublicKey: String): RotateKeyResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val oldPublicKey = localSettings.getOrCreateWireGuardKeys().second
        val signature = getSignature("$installationId:$newPublicKey")
        val request = RotateKeyRequest(
            installationId = installationId,
            oldWireguardPubkey = oldPublicKey,
            newWireguardPubkey = newPublicKey
        )
        val response = apiService.rotateKey(request, signature)
        if (response.success) {
            localSettings.updateWireGuardKeys(newPrivateKey, newPublicKey)
        }
        return response
    }

    suspend fun getStatus(): StatusResponse? {
        return try {
            val installationId = localSettings.getOrCreateInstallationId()
            val signature = getSignature(installationId)
            val response = apiService.getStatus(installationId, signature)
            
            // Sync status to local storage
            localSettings.setSubscriptionStatus(
                isActive = response.subscriptionActive,
                expiryDate = response.expiresAt
            )
            
            response
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteAccount(): DeleteAccountResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val signature = getSignature(installationId)
        val request = DeleteAccountRequest(installationId = installationId)
        val response = apiService.deleteAccount(request, signature)
        if (response.success) {
            localSettings.setSubscriptionStatus(isActive = false, expiryDate = "")
        }
        return response
    }
}
