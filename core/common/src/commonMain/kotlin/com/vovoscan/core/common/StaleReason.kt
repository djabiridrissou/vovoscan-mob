package com.vovoscan.core.common

import com.vovoscan.core.common.i18n.t

/** Rule errors of a source read on the device: its rules no longer match what the site answers. */
const val SOURCE_RULES = "SOURCE_RULES"

/**
 * Why the last refresh failed while the local copy stays on screen: no network at all, a source that isn't
 * answering, or a source whose rules can't read the site any more. Anything else is an ordinary error.
 */
sealed interface StaleReason {
    data object Offline : StaleReason

    data class SourceDown(
        val sourceName: String,
    ) : StaleReason

    data object SourceUnreadable : StaleReason

    /** The discreet banner, or the line under "not saved yet". `hasSaved`: something saved on this device is on screen. */
    fun notice(hasSaved: Boolean): String =
        when (this) {
            Offline -> if (hasSaved) OFFLINE else t("common.offline")
            is SourceDown -> t(if (hasSaved) "common.sourceDownSaved" else "common.sourceDown", "source" to sourceName)
            SourceUnreadable -> UNREADABLE_SOURCE
        }

    /** A source that didn't answer may answer a moment later; rules that don't match won't. */
    val canRetry: Boolean get() = this is SourceDown

    companion object {
        val OFFLINE: String get() = t("common.offlineSaved")
        val UNREADABLE_SOURCE: String get() = t("common.sourceUnreadable")

        fun of(error: AppError): StaleReason? =
            when {
                error == AppError.Network -> Offline
                error is AppError.SourceUnavailable -> SourceDown(error.sourceName)
                error is AppError.Rejected && error.code == SOURCE_RULES -> SourceUnreadable
                else -> null
            }
    }
}
