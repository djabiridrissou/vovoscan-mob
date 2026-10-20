package com.vovoscan.core.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain generic passwords, bound to this device (not synced to iCloud). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecureStore(
    private val service: String = "com.vovoscan.app",
) : SecureStore {
    override suspend fun get(key: String): String? =
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status =
                withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
                    SecItemCopyMatching(it, result.ptr)
                }
            if (status != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }

    override suspend fun put(
        key: String,
        value: String,
    ) {
        remove(key)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val bridged = CFBridgingRetain(data)
        try {
            withQuery(key, kSecValueData to bridged, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                SecItemAdd(it, null)
            }
        } finally {
            CFRelease(bridged)
        }
    }

    override suspend fun remove(key: String) {
        withQuery(key) { SecItemDelete(it) }
    }

    override suspend fun clear() {
        withQuery(account = null) { SecItemDelete(it) }
    }

    /** Keychain query (class, service, account) plus extra attributes, released after use. */
    private inline fun <T> withQuery(
        account: String?,
        vararg extra: Pair<CFTypeRef?, CFTypeRef?>,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val owned = mutableListOf<CFTypeRef?>()
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFBridgingRetain(service).also(owned::add).let { CFDictionaryAddValue(query, kSecAttrService, it) }
            if (account != null) CFBridgingRetain(account).also(owned::add).let { CFDictionaryAddValue(query, kSecAttrAccount, it) }
            extra.forEach { (k, v) -> CFDictionaryAddValue(query, k, v) }
            return block(query)
        } finally {
            owned.forEach { CFRelease(it) }
            CFRelease(query)
        }
    }
}
