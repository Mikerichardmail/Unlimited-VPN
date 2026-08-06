package com.vpn.android.vpn

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OpenVpnClient @Inject constructor(
    @param:ApplicationContext private val context: Context
) : VpnClient {
    override suspend fun start(serverLocation: String, killSwitchEnabled: Boolean, configData: Any): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // TODO: Integrate ics-openvpn AIDL
                val ovpnConfigString = configData as String
                Log.d("OpenVpnClient", "Starting OpenVPN with config: \n$ovpnConfigString")
                // Throwing exception because ics-openvpn is not fully integrated in gradle yet
                throw UnsupportedOperationException("OpenVPN is not fully integrated yet")
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            Log.d("OpenVpnClient", "Stopping OpenVPN")
            // TODO: Stop ics-openvpn via AIDL
        }
    }
}
