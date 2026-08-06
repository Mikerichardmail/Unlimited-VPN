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
    val id: String,
    @SerializedName("installation_id") val installationId: String,
    val email: String?,
    @SerializedName("google_purchase_token") val googlePurchaseToken: String,
    @SerializedName("plan_type") val planType: String,
    val status: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class VerifyResponse(
    val success: Boolean,
    val message: String,
    val subscription: Subscription?
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
    val success: Boolean,
    @SerializedName("device_limit_reached") val deviceLimitReached: Boolean,
    val config: VpnConfig?
)

// 4. Status details
data class StatusResponse(
    @SerializedName("subscription_active") val subscriptionActive: Boolean,
    @SerializedName("plan_type") val planType: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("devices_count") val devicesCount: Int,
    @SerializedName("bandwidth_used_bytes") val bandwidthUsedBytes: Long,
    @SerializedName("bandwidth_limit_bytes") val bandwidthLimitBytes: Long
)

// 5. Deregister Device
data class DeregisterDeviceRequest(
    val installationId: String,
    val wireguardPubkey: String,
    val protocol: String = "wireguard"
)

data class DeregisterDeviceResponse(
    val success: Boolean,
    val message: String
)

// 6. Rotate Key
data class RotateKeyRequest(
    val installationId: String,
    val oldWireguardPubkey: String,
    val newWireguardPubkey: String
)

data class RotateKeyResponse(
    val success: Boolean,
    val message: String
)

// 7. Delete Account
data class DeleteAccountRequest(
    val installationId: String
)

data class DeleteAccountResponse(
    val success: Boolean,
    val message: String
)
