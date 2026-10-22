package com.vovoscan.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The default network, from `ConnectivityManager` (needs `ACCESS_NETWORK_STATE`). */
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(stateOf(manager.getNetworkCapabilities(manager.activeNetwork)))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    mutable.value = stateOf(capabilities)
                }

                override fun onLost(network: Network) {
                    mutable.value = NetworkState(online = false, unmetered = false)
                }
            },
        )
    }

    private fun stateOf(capabilities: NetworkCapabilities?): NetworkState {
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = online && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(online, unmetered)
    }
}
