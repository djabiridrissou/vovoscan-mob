import Shared
import SwiftUI

@main
struct VovoscanApp: App {
    init() {
        // Covers: kept by the shared HTTP cache that AsyncImage uses.
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 200 * 1024 * 1024, directory: nil)
        if AppEnvironment.simulateOffline { URLProtocol.registerClass(SimulatedOffline.self) }
        IosAppKt.doInitKoin(apiBaseUrl: AppEnvironment.apiBaseUrl, simulateOffline: AppEnvironment.simulateOffline)
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

    /// Debug builds only: `VOVOSCAN_OFFLINE=1` in the launch environment (`SIMCTL_CHILD_VOVOSCAN_OFFLINE=1 xcrun simctl
    /// launch …`) makes the app behave as with no network. The simulator shares the Mac's network and has no switch.
    static var simulateOffline: Bool {
        #if DEBUG
            ProcessInfo.processInfo.environment["VOVOSCAN_OFFLINE"] == "1"
        #else
            false
        #endif
    }
}

/// Debug: every http(s) request of the shared URL session (covers, pages through AsyncImage) fails as with no
/// network; `file://` pages (downloads) still load. The Kotlin clients fail on their side (`simulateOffline`).
final class SimulatedOffline: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        let scheme = request.url?.scheme?.lowercased()
        return scheme == "http" || scheme == "https"
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
    }

    override func stopLoading() {}
}
