package com.vovoscan.core.sources

import java.util.Locale

internal actual fun deviceLanguage(): String = Locale.getDefault().language
