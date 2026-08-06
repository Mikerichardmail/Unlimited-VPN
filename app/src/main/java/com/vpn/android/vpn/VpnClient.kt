package com.vpn.android.vpn

interface VpnClient {
    suspend fun start(serverLocation: String, killSwitchEnabled: Boolean, configData: Any): Boolean
    suspend fun stop()
}
