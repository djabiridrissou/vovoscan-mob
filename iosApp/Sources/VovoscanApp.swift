import Shared
import SwiftUI

@main
struct VovoscanApp: App {
    init() {
        // Couvertures : gardées par le cache HTTP partagé qu'utilise AsyncImage.
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
    /// Lue dans Info.plist (API_BASE_URL, fixée par configuration dans project.yml).
    static var apiBaseUrl: String {
        Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String ?? "http://localhost:8000/"
    }
}
