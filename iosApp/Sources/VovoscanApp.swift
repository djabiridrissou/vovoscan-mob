import Shared
import SwiftUI

@main
struct VovoscanApp: App {
    init() {
        // Covers: kept by the shared HTTP cache that AsyncImage uses.
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 200 * 1024 * 1024, directory: nil)
        IosAppKt.doInitKoin(apiBaseUrl: AppEnvironment.apiBaseUrl)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Color.brandRed)
        }
    }
}

enum AppEnvironment {
    /// Read from Info.plist (API_BASE_URL, set per configuration in project.yml).
    static var apiBaseUrl: String {
        Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String ?? "http://localhost:8000/"
    }
}
