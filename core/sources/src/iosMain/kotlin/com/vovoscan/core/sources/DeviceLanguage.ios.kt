package com.vovoscan.core.sources

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.LC_CTYPE
import platform.posix.setlocale

/** The app's C locale ("en_US.UTF-8" style); null when the platform doesn't report one. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun deviceLanguage(): String =
    setlocale(LC_CTYPE, null)
        ?.toKString()
        ?.substringBefore('_')
        ?.lowercase()
        ?.takeUnless { it == "c" || it == "posix" }
        .orEmpty()
