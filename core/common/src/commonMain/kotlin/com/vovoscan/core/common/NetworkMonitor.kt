package com.vovoscan.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the device's network allows right now. */
data class NetworkState(
    /** A network that reaches the internet (or the platform can't tell). */
    val online: Boolean,
    /** Wi-Fi, Ethernet or another network that isn't billed by use (not mobile data, not a personal hotspot). */
    val unmetered: Boolean,
)

/**
 * The device's network, as the platform reports it (Android `ConnectivityManager`, iOS `NWPathMonitor`). Best
 * effort: it tells a real lack of network ("Offline") from a source that doesn't answer, and holds downloads set to
 * Wi-Fi only while on mobile data.
 */
interface NetworkMonitor {
    val state: StateFlow<NetworkState>

    /** Fixed state: tests, and a debug build that simulates having no network. */
    class Fixed(
        state: NetworkState,
    ) : NetworkMonitor {
        private val mutable = MutableStateFlow(state)
        override val state: StateFlow<NetworkState> = mutable.asStateFlow()

        fun set(next: NetworkState) {
            mutable.value = next
        }
    }

    companion object {
        /** When nothing says otherwise: online, unmetered. */
        val AssumeOnline: NetworkMonitor = Fixed(NetworkState(online = true, unmetered = true))
    }
}
