package com.vpn.android.vpn

import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VpnTunnel : Tunnel {

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state.asStateFlow()

    override fun getName(): String {
        return "ShieldVPN"
    }

    override fun onStateChange(newState: Tunnel.State) {
        _state.value = newState
    }
}
