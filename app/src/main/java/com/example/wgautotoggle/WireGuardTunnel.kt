package com.example.wgautotoggle

import com.wireguard.android.backend.Tunnel

typealias TunnelStateCallback = (Tunnel.State) -> Unit

/**
 * Implementazione minima dell'interfaccia Tunnel richiesta dalla libreria
 * "com.wireguard.android:tunnel". Rappresenta il nostro unico tunnel WireGuard.
 */
class WireGuardTunnel(
    private val tunnelName: String,
    private val onStateChanged: TunnelStateCallback? = null
) : Tunnel {

    @Volatile
    private var state: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    fun currentState(): Tunnel.State = state
}
