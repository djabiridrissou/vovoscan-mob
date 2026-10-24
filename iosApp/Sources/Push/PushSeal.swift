import CryptoKit
import Foundation
import Security

/// This install's sealed-push key (docs/NOTIFICATIONS.md §Push), in the Keychain shared by the app and its notification
/// service extension: the app registers the public half, the extension opens each push with the private half. The server
/// sees only references, sealed for this device: X25519 with the push's ephemeral key, HKDF-SHA256 (salt = ephemeral
/// public key ‖ device public key, info "vovoscan-push-v1"), AES-256-GCM with the device id as additional data. Compiled
/// into both targets (no Kotlin in the extension); Android's `PushCrypto.kt` is the same seal.
enum PushSeal {
    private static let service = "vovoscan.push"
    private static let info = Data("vovoscan-push-v1".utf8)

    /// `<team prefix>com.vovoscan.push`, from `AppIdentifierPrefix` in each target's Info.plist. Without it (a build
    /// signed without a team) the items stay the app's own and the extension shows the placeholder text.
    private static var accessGroup: String? {
        (Bundle.main.object(forInfoDictionaryKey: "AppIdentifierPrefix") as? String)
            .flatMap { $0.isEmpty || $0.contains("$") ? nil : $0 + "com.vovoscan.push" }
    }

    struct Key {
        let deviceId: String
        let keyId: String
        /// Base64url of the raw 32-byte public key.
        let publicKey: String
    }

    /// A push opened: its kind and references, the same as an inbox item.
    struct Opened {
        let id: String
        let kind: String
        let data: [String: String]
    }

    // MARK: - The key

    /// This install's key, made on first use.
    static func key() -> Key? {
        let device = deviceId()
        if let raw = read("private"), let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            return describe(device, priv.publicKey)
        }
        return rotate()
    }

    /// A new pair: the old private half is gone, pushes sealed for it can't be opened any more.
    static func rotate() -> Key? {
        let device = deviceId()
        let priv = Curve25519.KeyAgreement.PrivateKey()
        guard write("private", priv.rawRepresentation) else { return nil }
        return describe(device, priv.publicKey)
    }

    /// Signed out: the device id and the private half are erased.
    static func forget() {
        delete("private")
        delete("device")
    }

    /// The app's UI language, for the extension's sentences ("en" or "fr").
    static func setLocale(_ code: String) { _ = write("locale", Data(code.utf8)) }

    static var locale: String { read("locale").flatMap { String(data: $0, encoding: .utf8) } ?? "en" }

    private static func deviceId() -> String {
        if let data = read("device"), let id = String(data: data, encoding: .utf8) { return id }
        let id = UUID().uuidString.lowercased()
        _ = write("device", Data(id.utf8))
        return id
    }

    /// The key id: the first 8 bytes of SHA-256 of the public key, in hex (Android's rule).
    private static func describe(_ device: String, _ pub: Curve25519.KeyAgreement.PublicKey) -> Key {
        let raw = pub.rawRepresentation
        let id = SHA256.hash(data: raw).prefix(8).map { String(format: "%02x", $0) }.joined()
        return Key(deviceId: device, keyId: id, publicKey: b64(raw))
    }

    // MARK: - Opening

    /// The push's data keys (`v`, `k`, `e`, `n`, `c`, top level of the APNs payload as Firebase delivers it), opened;
    /// nil when it isn't for this key or doesn't open.
    static func open(_ payload: [AnyHashable: Any]) -> Opened? {
        guard let v = payload["v"] as? String, v == "1",
              let k = payload["k"] as? String,
              let e = (payload["e"] as? String).flatMap(unb64),
              let n = (payload["n"] as? String).flatMap(unb64),
              let c = (payload["c"] as? String).flatMap(unb64),
              let raw = read("private"),
              let device = read("device").flatMap({ String(data: $0, encoding: .utf8) }),
              let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
        else { return nil }
        guard describe(device, priv.publicKey).keyId == k else { return nil }
        return open(ephemeral: e, nonce: n, sealed: c, deviceId: device, privateKey: priv)
    }

    /// The seal itself (kept apart so the test vector shared with the backend and Android opens here too).
    static func open(ephemeral: Data, nonce: Data, sealed: Data, deviceId: String, privateKey: Curve25519.KeyAgreement.PrivateKey) -> Opened? {
        guard sealed.count > 16,
              let epk = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: ephemeral),
              let shared = try? privateKey.sharedSecretFromKeyAgreement(with: epk)
        else { return nil }
        let key = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: ephemeral + privateKey.publicKey.rawRepresentation,
            sharedInfo: info,
            outputByteCount: 32
        )
        guard let gcmNonce = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: sealed.dropLast(16), tag: sealed.suffix(16)),
              let plain = try? AES.GCM.open(box, using: key, authenticating: Data(deviceId.utf8)),
              let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let id = json["id"] as? String,
              let kind = json["kind"] as? String
        else { return nil }
        var data: [String: String] = [:]
        for (name, value) in json["data"] as? [String: Any] ?? [:] {
            switch value {
            case let text as String: data[name] = text
            case let number as NSNumber: data[name] = CFGetTypeID(number) == CFBooleanGetTypeID() ? (number.boolValue ? "true" : "false") : number.stringValue
            case let list as [Any]: data[name] = list.map { "\($0)" }.joined(separator: ", ")
            default: break
            }
        }
        return Opened(id: id, kind: kind, data: data)
    }

    static func b64(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func unb64(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }

    // MARK: - Keychain

    private static func query(_ account: String, group: String?) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    private static func read(_ account: String) -> Data? {
        for group in [accessGroup, nil] {
            var q = query(account, group: group)
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data { return data }
            if accessGroup == nil { break }
        }
        return nil
    }

    @discardableResult
    private static func write(_ account: String, _ value: Data) -> Bool {
        for group in [accessGroup, nil] {
            SecItemDelete(query(account, group: group) as CFDictionary)
            var q = query(account, group: group)
            q[kSecValueData as String] = value
            // The extension runs while the phone is locked: readable after the first unlock, never synced or backed up.
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(q as CFDictionary, nil)
            if status == errSecSuccess { return true }
            // Signed without the shared group: kept for the app alone.
            if status != errSecMissingEntitlement || group == nil { return false }
        }
        return false
    }

    private static func delete(_ account: String) {
        SecItemDelete(query(account, group: accessGroup) as CFDictionary)
        SecItemDelete(query(account, group: nil) as CFDictionary)
    }
}
