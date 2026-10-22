package com.vovoscan.core.database

/**
 * A local database left by a newer build of the app (a schema version this build does not know) is a cache to rebuild,
 * never a reason to stop: the drivers refuse a downgrade (SQLiter: "Database version 4 newer than config version 3",
 * thrown when the driver opens, which killed the iOS app at launch; Android: "Can't downgrade database"). The server is
 * the source of truth; what only the device held (queued library writes, reading positions) is lost with it.
 */
object DowngradeGuard {
    /** Whether [error] is a driver refusing a database newer than the schema it was built with. */
    fun isDowngrade(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { e ->
            val message = e.message.orEmpty()
            "newer than config version" in message || "downgrade database" in message
        }

    /**
     * Opens with [open]; on a downgrade, [reset] deletes the database, [onReset] is told once, and [open] runs again.
     * Any other error is the caller's.
     */
    fun <T> open(
        open: () -> T,
        reset: () -> Unit,
        onReset: (Throwable) -> Unit = {},
    ): T =
        try {
            open()
        } catch (e: Exception) {
            if (!isDowngrade(e)) throw e
            onReset(e)
            reset()
            open()
        }
}
