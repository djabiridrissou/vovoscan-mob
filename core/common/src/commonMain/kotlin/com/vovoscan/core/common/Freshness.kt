package com.vovoscan.core.common

/**
 * Sync state of an offline-first screen: local data is always shown,
 * [Freshness] only says whether it was just refreshed from the server.
 */
enum class Freshness {
    /** Refresh in progress; the cache stays on screen. */
    Refreshing,

    /** Refreshed from the server. */
    Fresh,

    /** Last refresh failed (offline, server unreachable): the local copy is shown. */
    Offline,
}
