package com.vovoscan.core.common

/**
 * Message shown for an error. Brand tone: calm, precise, no "Oops".
 * An API rejection keeps its own message, which is the most precise.
 */
fun AppError.userMessage(): String =
    when (this) {
        AppError.Network -> "No connection. Showing what's saved on this device."
        AppError.Unauthorized -> "Your session has ended. Sign in again."
        AppError.Forbidden -> "Your role doesn't allow this action."
        AppError.RateLimited -> "Too many attempts. Try again in a few minutes."
        is AppError.SourceUnavailable -> "$sourceName isn't answering right now."
        is AppError.Rejected ->
            if (code == SOURCE_RULES) StaleReason.UNREADABLE_SOURCE else message.ifBlank { "Request refused." }
        is AppError.Unknown -> "Something went wrong. Try again."
    }
