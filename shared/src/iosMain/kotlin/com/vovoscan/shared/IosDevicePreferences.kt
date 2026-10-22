package com.vovoscan.shared

import com.vovoscan.core.common.i18n.DevicePreferences
import platform.Foundation.NSUserDefaults

/** `UserDefaults` under a `vovoscan.` prefix (not the Keychain): kept across sign-outs, never a secret. */
class IosDevicePreferences : DevicePreferences {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(PREFIX + key)

    override fun put(
        key: String,
        value: String?,
    ) {
        if (value == null) defaults.removeObjectForKey(PREFIX + key) else defaults.setObject(value, PREFIX + key)
    }

    private companion object {
        const val PREFIX = "vovoscan."
    }
}
