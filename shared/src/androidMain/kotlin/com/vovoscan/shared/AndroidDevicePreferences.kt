package com.vovoscan.shared

import android.content.Context
import com.vovoscan.core.common.i18n.DevicePreferences

/** Plain `SharedPreferences` (not the secure store): kept across sign-outs, never a secret. */
class AndroidDevicePreferences(
    context: Context,
) : DevicePreferences {
    private val prefs = context.getSharedPreferences("vovoscan_device", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(
        key: String,
        value: String?,
    ) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
