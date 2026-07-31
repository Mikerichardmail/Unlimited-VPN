package com.vpn.android.data

import android.util.Base64
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
    private val secretKey = "VPN_API_HMAC_SECRET_KEY"

    // Helper to generate HMAC-SHA256 signature
    private fun getSignature(data: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKeySpec)
            val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun getServers(): List<Server> {
        return try {
            val response = apiService.getServers()
            response.servers
        } catch (e: Exception) {
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

    suspend fun registerDevice(serverLocation: String): RegisterDeviceResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val (privateKey, publicKey) = localSettings.getOrCreateWireGuardKeys()
        val signature = getSignature("$installationId:$publicKey")
        val request = RegisterDeviceRequest(
            installationId = installationId,
            wireguardPubkey = publicKey,
            serverLocation = serverLocation
        )
        return apiService.registerDevice(request, signature)
    }

    suspend fun deregisterDevice(pubKey: String? = null): DeregisterDeviceResponse {
        val installationId = localSettings.getOrCreateInstallationId()
        val targetPubKey = pubKey ?: localSettings.getOrCreateWireGuardKeys().second
        val signature = getSignature("$installationId:$targetPubKey")
        val request = DeregisterDeviceRequest(
            installationId = installationId,
            wireguardPubkey = targetPubKey
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
}
