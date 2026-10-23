package com.vovoscan.core.common

import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.t

/**
 * Message shown for an error. Brand tone: calm, precise, no "Oops".
 * An API rejection with a code the catalogue knows (`errors.<CODE>`) reads in the app's words; another keeps the
 * server's message (sent in the app's locale through Accept-Language).
 */
fun AppError.userMessage(): String =
    when (this) {
        AppError.Network -> t("errors.network")
        AppError.Unauthorized -> t("errors.unauthorized")
        AppError.Forbidden -> t("errors.forbidden")
        AppError.RateLimited -> t("errors.rateLimited")
        is AppError.SourceUnavailable -> t("common.sourceDown", "source" to sourceName)
        is AppError.Rejected ->
            when {
                code == SOURCE_RULES -> StaleReason.UNREADABLE_SOURCE
                // Known codes read in the app's own words; others keep the server's message (in the same locale).
                I18n.has("errors.$code") -> t("errors.$code")
                else -> message.ifBlank { t("errors.refused") }
            }
        is AppError.Unknown -> t("errors.unknown")
    }
