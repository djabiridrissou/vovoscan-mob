package com.vovoscan.core.sources

/**
 * Language the device runs in, as an ISO 639-1 code ("fr", "en"…), lower case. Feeds the `{{lang}}` template
 * variable so sources can filter by the reader's language. Empty when the platform can't tell.
 */
internal expect fun deviceLanguage(): String

/** The `{{lang}}` the device reads its sources in, for a server that reads the same source for us (imports). */
fun sourceLanguage(): String = deviceLanguage()
