package com.vovoscan.core.common

/** Errors seen by use cases and screens, independent of the network or the database. */
sealed interface AppError {
    /** No network or server unreachable: the local cache stays on screen. */
    data object Network : AppError

    /** Session missing, expired or revoked: the user must sign in again. */
    data object Unauthorized : AppError

    /** Missing permission (403): the action is not offered to this role. */
    data object Forbidden : AppError

    /**
     * A source read on the device did not answer while the device is online: site down, 5xx, timeout. The local
     * copy stays on screen, with the source's name ("Project Gutenberg isn't answering right now.").
     */
    data class SourceUnavailable(
        val sourceName: String,
    ) : AppError

    /** Too many requests (429). */
    data object RateLimited : AppError

    /** Business rejection from the API: stable `code` (tested, translated), `message` for humans. */
    data class Rejected(
        val code: String,
        val message: String,
    ) : AppError

    data class Unknown(
        val detail: String,
    ) : AppError
}
