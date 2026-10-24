import Foundation
import Shared
import SwiftUI
import UIKit
import UserNotifications
#if canImport(FirebaseCore) && canImport(FirebaseMessaging)
    import FirebaseCore
    import FirebaseMessaging
#endif

/// This install's sealed-push key for the shared registrar (`PushRegistrar`): the Keychain item the extension reads.
final class IosPushDevice: NSObject, PushDevice {
    var platform: String { "ios" }

    func key() -> DeviceKey? { PushSeal.key().map(Self.bridge) }

    func rotate() -> DeviceKey? { PushSeal.rotate().map(Self.bridge) }

    func forget() { PushSeal.forget() }

    private static func bridge(_ key: PushSeal.Key) -> DeviceKey {
        DeviceKey(deviceId: key.deviceId, keyId: key.keyId, publicKey: key.publicKey)
    }
}

/// A group's link to open (a notification tapped, `vovoscan://g/<handle>`): `RootView` takes it to the Groups tab.
@MainActor
final class GroupLinkCenter: ObservableObject {
    static let shared = GroupLinkCenter()
    @Published var link: String?
}

/// Pushes: Firebase only when this build carries its `GoogleService-Info.plist` (git-ignored; without it everything stays
/// in the app's inbox), notifications asked the first time the Groups tab opens, and a tapped notification opens its group
/// or game. No method swizzling (`FirebaseAppDelegateProxyEnabled` off): the APNs token is handed over here.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let bridge = PushBridge()

    static var configured: Bool { Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        #if canImport(FirebaseCore) && canImport(FirebaseMessaging)
            if Self.configured {
                FirebaseApp.configure()
                Messaging.messaging().delegate = self
                Messaging.messaging().isAutoInitEnabled = true
                UNUserNotificationCenter.current().getNotificationSettings { settings in
                    guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
                    DispatchQueue.main.async { application.registerForRemoteNotifications() }
                }
            }
        #endif
        return true
    }

    /// The Groups tab opened: asks once (iOS remembers the answer), then registers with APNs.
    static func askOnce() {
        guard configured else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func application(_: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        #if canImport(FirebaseMessaging)
            if Self.configured { Messaging.messaging().apnsToken = deviceToken }
        #endif
    }

    func application(_: UIApplication, didFailToRegisterForRemoteNotificationsWithError _: Error) {
        // No pushes on this device (simulator, no entitlement): the inbox still works in the app.
        bridge.tokenChanged(token: nil)
    }

    /// On screen: the inbox moves live already, so the banner is only listed.
    func userNotificationCenter(_: UNUserNotificationCenter, willPresent _: UNNotification) async -> UNNotificationPresentationOptions {
        [.list]
    }

    func userNotificationCenter(_: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let link = info["link"] as? String, link.hasPrefix("vovoscan://g/") else { return }
        await MainActor.run { GroupLinkCenter.shared.link = link }
    }
}

#if canImport(FirebaseMessaging)
    extension AppDelegate: MessagingDelegate {
        func messaging(_: Messaging, didReceiveRegistrationToken fcmToken: String?) {
            bridge.tokenChanged(token: fcmToken)
        }
    }
#endif
