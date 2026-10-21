package com.vovoscan.feature.account.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.core.common.localDate
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.UnitType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Every user-facing string of the account screens (brand tone: calm, short, precise). */
object AccountText {
    const val KEYS_DISABLED = "Keys are turned off on this server."
    const val OFFLINE_KEYS = "You're offline. Connect to the internet to change your keys."
    const val OFFLINE_MODEL = "You're offline. Connect to the internet to change the model."
    const val OFFLINE_USAGE = "You're offline. Costs show once you're back online."
    const val SECRET_MISSING = "Paste your key."
    const val SECRET_INVALID = "This doesn't look like a key. Check that you copied all of it."
    const val LABEL_TOO_LONG = "Keep the label under 60 characters."
    const val KEY_SAVED = "Key saved."
    const val KEY_REMOVED = "Key removed."
    const val NEVER_USED = "Not used yet"
    const val ADD_KEY = "Add a key"
    const val REPLACE_KEY = "Replace"
    const val REMOVE_KEY = "Remove"
    const val KEEP_KEY = "Keep it"
    const val SAVE_KEY = "Save key"
    const val CHECKING_KEY = "Checking the key…"
    const val SECRET_PLACEHOLDER = "Paste the key"
    const val LABEL_PLACEHOLDER = "Label (optional)"
    const val KEY_PRIVACY = "Your key is sent once, checked with the provider and sealed on the server. It never stays on this phone."
    const val MODEL_FOOTNOTE = "The model reads the bubbles of the chapters you prepare, with your Anthropic key."
    const val MODEL_SAVED = "Model saved."
    const val USAGE_FOOTNOTE = "Estimated from each provider's public rates. Billed by the provider, on your own account."
    const val USAGE_EMPTY = "Nothing spent in this period."

    fun purpose(provider: Provider): String =
        when (provider) {
            Provider.Anthropic -> "Reads the bubbles (OCR) and helps cast the voices."
            Provider.ElevenLabs -> "Voices the lines, sounds and music (VOICE)."
        }

    fun formTitle(
        provider: Provider,
        replacing: Boolean,
    ): String = if (replacing) "Replace your ${provider.label} key" else "Add your ${provider.label} key"

    fun removeTitle(provider: Provider): String = "Remove your ${provider.label} key?"

    fun removeMessage(provider: Provider): String =
        when (provider) {
            Provider.Anthropic -> "Episodes you prepare next will keep their bubbles unread. You can add a key again anytime."
            Provider.ElevenLabs -> "Episodes you prepare next will stay silent. You can add a key again anytime."
        }

    /** "•••• abcd". */
    fun hint(hint: String): String = "•••• $hint"

    /** Saving or removing a key. */
    fun keyError(error: AppError): String =
        when (error) {
            AppError.Network -> OFFLINE_KEYS
            AppError.RateLimited -> "Too many keys added in the last hour. Try again later."
            is AppError.Rejected ->
                when (error.code) {
                    "KEY_REJECTED" -> "The provider refused this key. Check it and try again."
                    "PROVIDER_BLOCKED" -> "The provider accepts this key but refuses calls from our servers. Check your account with them."
                    "PROVIDER_UNAVAILABLE" -> "The provider couldn't check this key right now. Try again in a moment."
                    "BYOK_DISABLED" -> KEYS_DISABLED
                    "TOO_MANY_REQUESTS" -> "Too many keys added in the last hour. Try again later."
                    "PROVIDER_KEY_CHANGED" -> "Another key for this provider was just saved. Refresh and try again."
                    "VALIDATION" -> SECRET_INVALID
                    else -> error.userMessage()
                }
            else -> error.userMessage()
        }

    fun modelError(error: AppError): String =
        when (error) {
            AppError.Network -> OFFLINE_MODEL
            AppError.RateLimited -> "Too many changes in a minute. Try again shortly."
            else -> error.userMessage()
        }

    fun usageError(error: AppError): String = if (error == AppError.Network) OFFLINE_USAGE else error.userMessage()

    /** "$1.24", "<$0.01", "$0.00". */
    fun money(usd: Double): String =
        when {
            usd <= 0.0 -> "$0.00"
            usd < 0.01 -> "<$0.01"
            else -> "$" + grouped(usd, 2)
        }

    /** "182,400 tokens", "12.5 seconds", "1 request". */
    fun amount(
        unit: UnitType,
        value: Double,
    ): String {
        val whole = value.roundToLong()
        return when (unit) {
            UnitType.Chars -> "${grouped(whole.toDouble(), 0)} " + if (whole == 1L) "character" else "characters"
            UnitType.Tokens -> "${grouped(whole.toDouble(), 0)} " + if (whole == 1L) "token" else "tokens"
            UnitType.Requests -> "${grouped(whole.toDouble(), 0)} " + if (whole == 1L) "request" else "requests"
            UnitType.Seconds -> "${grouped(value, 1)} " + if (value == 1.0) "second" else "seconds"
        }
    }

    /** "Oct 20, 2026" in the device's time zone. */
    fun date(
        epochMillis: Long,
        offset: UtcOffset,
    ): String {
        val d = localDate(epochMillis, offset)
        return "${MONTHS[d.month - 1]} ${d.day}, ${d.year}"
    }

    /** "Oct 20 ✦ 14:05" in the device's time zone. */
    fun dateTime(
        epochMillis: Long,
        offset: UtcOffset,
    ): String {
        val local = epochMillis + offset.offsetMillis(epochMillis)
        val d = localDate(epochMillis, offset)
        val minutes = local.mod(86_400_000L) / 60_000
        val hh = (minutes / 60).toString().padStart(2, '0')
        val mm = (minutes % 60).toString().padStart(2, '0')
        return "${MONTHS[d.month - 1]} ${d.day} ✦ $hh:$mm"
    }

    /** A number with thousands separators and a fixed number of decimals ("1,234.50"). */
    fun grouped(
        value: Double,
        decimals: Int,
    ): String {
        val factor = 10.0.pow(decimals)
        val scaled = (abs(value) * factor).roundToLong()
        val whole =
            (scaled / factor.roundToLong())
                .toString()
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
        val fraction = if (decimals > 0) "." + (scaled % factor.roundToLong()).toString().padStart(decimals, '0') else ""
        return (if (value < 0 && scaled != 0L) "-" else "") + whole + fraction
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
