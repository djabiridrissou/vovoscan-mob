package com.vovoscan.core.common

/**
 * Message affiché pour une erreur. Ton de la marque : calme, précis, sans « Oops ».
 * Un refus de l'API garde son propre message, le plus précis.
 */
fun AppError.userMessage(): String =
    when (this) {
        AppError.Network -> "No connection. Showing what's saved on this device."
        AppError.Unauthorized -> "Your session has ended. Sign in again."
        AppError.Forbidden -> "Your role doesn't allow this action."
        AppError.RateLimited -> "Too many attempts. Try again in a few minutes."
        is AppError.Rejected -> message.ifBlank { "Request refused." }
        is AppError.Unknown -> "Something went wrong. Try again."
    }
