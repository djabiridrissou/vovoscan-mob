package com.vovoscan.feature.notifications.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.notifications.domain.NotificationsRepository
import com.vovoscan.feature.notifications.domain.PushDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registers this device for sealed pushes (docs/NOTIFICATIONS.md §Push) while an account is signed in: its public key,
 * key id, Firebase token and UI language (`PUT /notifications/devices/{deviceId}`), again whenever the token or the
 * language changes. Before signing out the device is unregistered and its private key erased. Without a Firebase token
 * (no Firebase set up on this build, or the user refused notifications) nothing is sent and notifications stay in app.
 * App-scoped: the platform hands it the token ([tokenChanged]) from its messaging service.
 */
class PushRegistrar(
    private val repository: NotificationsRepository,
    private val device: PushDevice,
    private val signedIn: Flow<Boolean>,
    private val locale: Flow<String>,
    private val scope: CoroutineScope,
) {
    private val token = MutableStateFlow<String?>(null)
    private val lock = Mutex()

    /** What was registered last: nothing is sent again for the same token, key and language. */
    private var registered: String? = null

    fun start() {
        combine(signedIn, token, locale) { on, t, l -> Triple(on, t, l) }
            .distinctUntilChanged()
            .onEach { (on, t, l) -> if (on && t != null) register(t, l) }
            .launchIn(scope)
    }

    /** From Firebase: a new registration token (null: none on this build). */
    fun tokenChanged(value: String?) {
        token.value = value?.takeIf { it.isNotBlank() }
    }

    /** Before signing out: the server forgets this device, the key is erased. */
    suspend fun signingOut() =
        lock.withLock {
            device.key()?.let { repository.unregister(it.deviceId) }
            device.forget()
            registered = null
        }

    private suspend fun register(
        token: String,
        locale: String,
    ) = lock.withLock {
        val key = device.key() ?: return@withLock
        val lang = locale.substringBefore('-').takeIf { it == "en" || it == "fr" }
        val stamp = "${key.deviceId}|${key.keyId}|$token|$lang"
        if (stamp == registered) return@withLock
        if (repository.register(key, device.platform, token, lang) is AppResult.Ok) registered = stamp
    }
}
