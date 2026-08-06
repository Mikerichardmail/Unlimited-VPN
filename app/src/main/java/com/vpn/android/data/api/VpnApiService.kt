package com.vpn.android.data.api

import com.vpn.android.data.models.*
import retrofit2.http.*

interface VpnApiService {

    @GET("api/servers")
    suspend fun getServers(
        @Query("installationId") installationId: String,
        @Header("X-App-Signature") signature: String
    ): ServersResponse

    @POST("api/verify")
    suspend fun verifySubscription(
        @Body request: VerifyRequest,
        @Header("X-App-Signature") signature: String
    ): VerifyResponse

    @POST("api/register-device")
    suspend fun registerDevice(
        @Body request: RegisterDeviceRequest,
        @Header("X-App-Signature") signature: String
    ): RegisterDeviceResponse

    @POST("api/deregister-device")
    suspend fun deregisterDevice(
        @Body request: DeregisterDeviceRequest,
        @Header("X-App-Signature") signature: String
    ): DeregisterDeviceResponse

    @GET("api/status")
    suspend fun getStatus(
        @Query("installationId") installationId: String,
        @Header("X-App-Signature") signature: String
    ): StatusResponse

    @POST("api/rotate-key")
    suspend fun rotateKey(
        @Body request: RotateKeyRequest,
        @Header("X-App-Signature") signature: String
    ): RotateKeyResponse

    @HTTP(method = "DELETE", path = "api/delete-account", hasBody = true)
    suspend fun deleteAccount(
        @Body request: DeleteAccountRequest,
        @Header("X-App-Signature") signature: String
    ): DeleteAccountResponse
}
