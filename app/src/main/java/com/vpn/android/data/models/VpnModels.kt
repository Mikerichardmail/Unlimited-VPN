package com.vpn.android.data.models

import com.google.gson.annotations.SerializedName

// 1. Server information
data class Server(
    val id: String = "",
    val country: String = "",
    val city: String = "",
    val endpoint: String = "",
    val pubkey: String = "",
    @SerializedName("ping_ip") val pingIp: String = "",
    @SerializedName("latency_ms") val latencyMs: Int = 0,
    @SerializedName("load_percent") val loadPercent: Int = 0,
    val icon: String? = null
)

data class ServersResponse(
    val servers: List<Server>
)

// 2. Subscription Verification
data class VerifyRequest(
    val installationId: String,
    val googlePurchaseToken: String,
    val planType: String,
    val email: String?
)

data class Subscription(
    val id: String = "",
    @SerializedName("installation_id") val installationId: String = "",
    val email: String? = null,
    @SerializedName("google_purchase_token") val googlePurchaseToken: String = "",
    @SerializedName("plan_type") val planType: String = "",
    val status: String = "",
    @SerializedName("started_at") val startedAt: String = "",
    @SerializedName("expires_at") val expiresAt: String = ""
)

data class VerifyResponse(
    val success: Boolean = false,
    val message: String = "",
    val subscription: Subscription? = null
)

// 3. Device Registration & VPN Configurations
data class RegisterDeviceRequest(
    val installationId: String,
    val wireguardPubkey: String,
    val serverLocation: String,
    val protocol: String = "wireguard"
)

data class VpnConfig(
    @SerializedName("client_private_key") val clientPrivateKey: String? = null, // VPNResellers-issued key (preferred)
    @SerializedName("client_ip") val clientIp: String?,
    val dns: String?,
    @SerializedName("server_pubkey") val serverPubkey: String?,
    @SerializedName("server_endpoint") val serverEndpoint: String?,
    @SerializedName("allowed_ips") val allowedIps: String?,
    val keepalive: Int?,
    @SerializedName("ovpn_config") val ovpnConfig: String? = null
)

data class RegisterDeviceResponse(
    val success: Boolean = false,
    @SerializedName("device_limit_reached") val deviceLimitReached: Boolean = false,
    val config: VpnConfig? = null
)

// 4. Status details
data class StatusResponse(
    @SerializedName("subscription_active") val subscriptionActive: Boolean = false,
    @SerializedName("plan_type") val planType: String = "",
    @SerializedName("expires_at") val expiresAt: String = "",
    @SerializedName("devices_count") val devicesCount: Int = 0,
    @SerializedName("bandwidth_used_bytes") val bandwidthUsedBytes: Long = 0L,
    @SerializedName("bandwidth_limit_bytes") val bandwidthLimitBytes: Long = 0L
)

// 5. Deregister Device
data class DeregisterDeviceRequest(
    val installationId: String,
    val wireguardPubkey: String,
    val protocol: String = "wireguard"
)

data class DeregisterDeviceResponse(
    val success: Boolean = false,
    val message: String = ""
)

// 6. Rotate Key
data class RotateKeyRequest(
    val installationId: String,
    val oldWireguardPubkey: String,
    val newWireguardPubkey: String
)

data class RotateKeyResponse(
    val success: Boolean = false,
    val message: String = ""
)

// 7. Delete Account
data class DeleteAccountRequest(
    val installationId: String
)

data class DeleteAccountResponse(
    val success: Boolean = false,
    val message: String = ""
)

// 8. Error Logging
data class LogErrorRequest(
    val installationId: String,
    val errorType: String,
    val errorMessage: String,
    val stackTrace: String?,
    val deviceInfo: String?
)

data class LogErrorResponse(
    val success: Boolean,
    val error: String? = null
)

// 9. Bandwidth Sync
data class BandwidthSyncRequest(
    val installationId: String,
    @SerializedName("bytes_used") val bytesUsed: Long
)

data class BandwidthSyncResponse(
    val success: Boolean = false,
    @SerializedName("bandwidth_limit_reached") val bandwidthLimitReached: Boolean = false,
    val message: String = ""
)
