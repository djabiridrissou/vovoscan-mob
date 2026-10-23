package com.vovoscan.core.common.i18n

/**
 * Small values kept on the device across sign-outs (unlike the secure store, erased on sign-out): the UI language
 * chosen before signing in (i18n/SPEC.md §2, the mobile `vv_locale`). Android `SharedPreferences`, iOS `UserDefaults`.
 */
interface DevicePreferences {
    fun get(key: String): String?

    /** null removes the value. */
    fun put(
        key: String,
        value: String?,
    )

    class InMemory : DevicePreferences {
        private val values = mutableMapOf<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}

/**
 * Which language the app speaks, by the SPEC's chain: the signed-in account's, else the one chosen on this device, else
 * the first supported device language, else English. The region (formatting only) follows the same order, the
 * device's default being the region of its first language that has one.
 */
class UiLanguage(
    private val prefs: DevicePreferences,
    private val deviceLanguages: () -> List<String> = DeviceLocale::languages,
) {
    /** At launch, before the first screen. */
    fun start() {
        val device = deviceLanguages()
        I18n.use(UiLocales.resolve(null, prefs.get(LOCALE), device), prefs.get(REGION) ?: Regions.fromDevice(device))
    }

    /** Chosen on this device (sign-in screen, preferences, tutorial): kept for the next launch, applied at once. */
    fun choose(
        locale: String,
        region: String? = I18n.region,
    ) {
        val supported = UiLocales.supported(locale) ?: return
        prefs.put(LOCALE, supported)
        prefs.put(REGION, region?.uppercase()?.takeIf(Regions::valid))
        I18n.use(supported, region)
    }

    /** The account's own choice wins while signed in; it also becomes the device's, so the sign-in screen matches. */
    fun applyAccount(
        locale: String?,
        region: String?,
    ) {
        val device = deviceLanguages()
        val resolved = UiLocales.resolve(locale, prefs.get(LOCALE), device)
        val shownRegion = region?.uppercase()?.takeIf(Regions::valid) ?: prefs.get(REGION) ?: Regions.fromDevice(device)
        if (UiLocales.supported(locale) != null) prefs.put(LOCALE, resolved)
        if (Regions.valid(region?.uppercase())) prefs.put(REGION, region?.uppercase())
        if (resolved != I18n.locale.value || shownRegion != I18n.region) I18n.use(resolved, shownRegion)
    }

    /** The device's own region, for the region picker's default. */
    fun deviceRegion(): String? = Regions.fromDevice(deviceLanguages())

    private companion object {
        const val LOCALE = "ui_locale"
        const val REGION = "ui_region"
    }
}
