package com.vovoscan.core.network

import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * The current path, from `NWPathMonitor`. Unmetered: not "expensive" (mobile data, personal hotspot) and not
 * "constrained" (Low Data Mode). In the simulator it follows the Mac's network.
 */
class IosNetworkMonitor : NetworkMonitor {
    private val mutable = MutableStateFlow(NetworkState(online = true, unmetered = true))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            mutable.value = NetworkState(online, online && !nw_path_is_expensive(path) && !nw_path_is_constrained(path))
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.vovoscan.network", null))
        nw_path_monitor_start(monitor)
    }
}
