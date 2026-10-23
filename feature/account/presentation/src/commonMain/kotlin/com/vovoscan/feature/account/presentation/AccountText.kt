package com.vovoscan.feature.account.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.UnitType
import kotlin.math.roundToLong

/** Every user-facing string of the account screens (brand tone: calm, short, precise). */
object AccountText {
    val KEYS_DISABLED: String get() = t("keys.disabled")
    val OFFLINE_KEYS: String get() = t("keys.offline")
    val OFFLINE_MODEL: String get() = t("keys.offlineModel")
    val OFFLINE_COPY: String get() = t("common.offlineSaved")
    val OFFLINE_USAGE: String get() = t("usage.offline")
    val SECRET_MISSING: String get() = t("keys.secretMissing")
    val SECRET_INVALID: String get() = t("keys.secretInvalid")
    val LABEL_TOO_LONG: String get() = t("keys.labelTooLong")
    val KEY_SAVED: String get() = t("keys.saved")
    val KEY_REMOVED: String get() = t("keys.removed")
    val NEVER_USED: String get() = t("keys.neverUsed")
    val ADD_KEY: String get() = t("keys.add")
    val REPLACE_KEY: String get() = t("keys.replace")
    val REMOVE_KEY: String get() = t("keys.remove")
    val KEEP_KEY: String get() = t("keys.keep")
    val SAVE_KEY: String get() = t("keys.save")
    val CHECKING_KEY: String get() = t("keys.checking")
    val SECRET_PLACEHOLDER: String get() = t("keys.secretPlaceholder")
    val LABEL_PLACEHOLDER: String get() = t("keys.labelPlaceholder")
    val KEY_PRIVACY: String get() = t("keys.privacy")
    val MODEL_FOOTNOTE: String get() = t("keys.modelFootnote")
    val MODEL_SAVED: String get() = t("keys.modelSaved")
    val USAGE_FOOTNOTE: String get() = t("usage.footnote")
    val USAGE_EMPTY: String get() = t("usage.empty")

    fun purpose(provider: Provider): String =
        when (provider) {
            Provider.Anthropic -> t("keys.purposeAnthropic")
            Provider.Google -> t("keys.purposeGoogle")
            Provider.Moonshot -> t("keys.purposeMoonshot")
            Provider.ElevenLabs -> t("keys.purposeElevenLabs")
        }

    fun formTitle(
        provider: Provider,
        replacing: Boolean,
    ): String = t(if (replacing) "keys.replaceTitle" else "keys.addTitle", "provider" to provider.label)

    fun removeTitle(provider: Provider): String = t("keys.removeTitle", "provider" to provider.label)

    fun removeMessage(provider: Provider): String =
        when (provider) {
            Provider.Anthropic, Provider.Google, Provider.Moonshot -> t("keys.removeText")
            Provider.ElevenLabs -> t("keys.removeElevenLabs")
        }

    /** "•••• abcd". */
    fun hint(hint: String): String = "•••• $hint"

    /** "Added Oct 20, 2026". */
    fun added(date: String): String = t("keys.added", "date" to date)

    /** "Last used Oct 20, 2026". */
    fun lastUsed(date: String): String = t("keys.lastUsed", "date" to date)

    /** Saving or removing a key. */
    fun keyError(error: AppError): String =
        when (error) {
            AppError.Network -> OFFLINE_KEYS
            AppError.RateLimited -> t("keys.tooMany")
            is AppError.Rejected ->
                when (error.code) {
                    "KEY_REJECTED" -> t("errors.KEY_REJECTED")
                    "PROVIDER_BLOCKED" -> t("errors.PROVIDER_BLOCKED")
                    "PROVIDER_UNAVAILABLE" -> t("errors.PROVIDER_UNAVAILABLE")
                    "BYOK_DISABLED" -> KEYS_DISABLED
                    "TOO_MANY_REQUESTS" -> t("keys.tooMany")
                    "PROVIDER_KEY_CHANGED" -> t("errors.PROVIDER_KEY_CHANGED")
                    "VALIDATION" -> SECRET_INVALID
                    else -> error.userMessage()
                }
            else -> error.userMessage()
        }

    fun modelError(error: AppError): String =
        when (error) {
            AppError.Network -> OFFLINE_MODEL
            AppError.RateLimited -> t("keys.modelTooMany")
            else -> error.userMessage()
        }

    fun usageError(error: AppError): String = if (error == AppError.Network) OFFLINE_USAGE else error.userMessage()

    /** "$1.24", "under $0.01", "$0.00" (as the UI locale writes dollars). */
    fun money(usd: Double): String = LocalFormat.usd(usd)

    /** "182,400 tokens", "12.5 seconds", "1 request". */
    fun amount(
        unit: UnitType,
        value: Double,
    ): String {
        val whole = value.roundToLong()
        return when (unit) {
            UnitType.Chars -> t("usage.chars", "count" to whole)
            UnitType.Tokens -> t("usage.tokens", "count" to whole)
            UnitType.Requests -> t("usage.requests", "count" to whole)
            UnitType.Seconds -> t("usage.seconds", "count" to value, "amount" to LocalFormat.decimal(value, 1))
        }
    }

    /** "Oct 20, 2026" (the UI locale's medium date) in the device's time zone. */
    fun date(
        epochMillis: Long,
        offset: UtcOffset,
    ): String = LocalFormat.date(epochMillis + offset.offsetMillis(epochMillis))

    /** "Oct 20, 2026 ✦ 4:05 PM" in the device's time zone. */
    fun dateTime(
        epochMillis: Long,
        offset: UtcOffset,
    ): String {
        val local = epochMillis + offset.offsetMillis(epochMillis)
        return "${LocalFormat.date(local)} ✦ ${LocalFormat.time(local)}"
    }

    /** A number with the locale's grouping and a fixed number of decimals ("1,234.50"). */
    fun grouped(
        value: Double,
        decimals: Int,
    ): String = LocalFormat.decimal(value, decimals)
}
