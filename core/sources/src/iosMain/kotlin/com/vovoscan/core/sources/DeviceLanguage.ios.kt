package com.vovoscan.core.sources

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** The user's first preferred language ("fr-FR" → "fr"); the C locale is no guide on iOS (it stays "C"). */
internal actual fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase()
        .orEmpty()
