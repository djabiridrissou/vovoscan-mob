import Shared
import SwiftUI

@main
struct VovoscanApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Covers: kept by the shared HTTP cache that AsyncImage uses.
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 200 * 1024 * 1024, directory: nil)
        // The last registered protocol is asked first: offline simulation wins over the Referer.
        URLProtocol.registerClass(SourceRefererProtocol.self)
        if AppEnvironment.simulateOffline { URLProtocol.registerClass(SimulatedOffline.self) }
        IosAppKt.doInitKoin(apiBaseUrl: AppEnvironment.apiBaseUrl, simulateOffline: AppEnvironment.simulateOffline)
        // Background tasks must be registered before the app finishes launching.
        BackgroundDownloads.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Color.brandRed)
        }
        // Downloads: keep going for the time iOS gives in the background, then pause cleanly; carry on when back.
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background: BackgroundDownloads.shared.enterBackground()
            case .active: BackgroundDownloads.shared.becomeActive()
            default: break
            }
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

/// Page images (and covers) of a source that asks for a Referer (`content.comic.referer`, shared `ImageReferers`):
/// requests of the shared URL session (AsyncImage) to that source's hosts are sent again with `Referer`, through a
/// session of their own that carries no vovoscan session (no cookies, no credentials). Any other request is untouched.
final class SourceRefererProtocol: URLProtocol {
    private static let handled = "VovoscanSourceReferer"
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = URLCache.shared
        return URLSession(configuration: configuration, delegate: Forwarder.shared, delegateQueue: nil)
    }()

    private var dataTask: URLSessionDataTask?

    override class func canInit(with request: URLRequest) -> Bool {
        guard property(forKey: handled, in: request) == nil,
              request.value(forHTTPHeaderField: "Referer") == nil,
              let url = request.url?.absoluteString else { return false }
        return AppModulesKt.imageRefererFor(url: url) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url?.absoluteString, let referer = AppModulesKt.imageRefererFor(url: url),
              let mutable = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        mutable.setValue(referer, forHTTPHeaderField: "Referer")
        URLProtocol.setProperty(true, forKey: Self.handled, in: mutable)
        let task = Self.session.dataTask(with: mutable as URLRequest)
        Forwarder.shared.register(task, to: self)
        dataTask = task
        task.resume()
    }

    override func stopLoading() {
        dataTask?.cancel()
        dataTask = nil
    }

    /// One delegate for the shared session, handing each task's events to the protocol that started it.
    private final class Forwarder: NSObject, URLSessionDataDelegate {
        static let shared = Forwarder()
        private var owners: [Int: SourceRefererProtocol] = [:]
        private let lock = NSLock()

        func register(_ task: URLSessionTask, to owner: SourceRefererProtocol) {
            lock.withLock { owners[task.taskIdentifier] = owner }
        }

        private func owner(_ task: URLSessionTask) -> SourceRefererProtocol? {
            lock.withLock { owners[task.taskIdentifier] }
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            if let owner = owner(dataTask) {
                owner.client?.urlProtocol(owner, didReceive: response, cacheStoragePolicy: .allowed)
            }
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            if let owner = owner(dataTask) { owner.client?.urlProtocol(owner, didLoad: data) }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let owner = lock.withLock { owners.removeValue(forKey: task.taskIdentifier) }
            guard let owner else { return }
            if let error { owner.client?.urlProtocol(owner, didFailWithError: error) } else { owner.client?.urlProtocolDidFinishLoading(owner) }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            // A redirect leaves the source's hosts: the Referer goes only where the source allows.
            var next = request
            if let url = request.url?.absoluteString {
                next.setValue(AppModulesKt.imageRefererFor(url: url), forHTTPHeaderField: "Referer")
            }
            completionHandler(next)
        }
    }
}
