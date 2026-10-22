package com.vovoscan.core.sources

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * The reader's first preferred language ("fr-FR" → "fr"), from the system settings. Not the C locale: an iOS app's
 * C locale stays "C" whatever the user's language, so `{{lang}}` was empty on every iPhone.
 */
internal actual fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase()
        ?.takeIf { code -> code.length in 2..3 && code.all { it in 'a'..'z' } }
        .orEmpty()
