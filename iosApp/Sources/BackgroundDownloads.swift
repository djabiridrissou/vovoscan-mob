import BackgroundTasks
import os
import Shared
import UIKit

/// iOS side of background downloads. The shared queue downloads (one chapter at a time, resumed at the first missing
/// page); this only asks iOS for time and stops the queue cleanly before the app is suspended:
/// - Leaving the app with work in the queue: `beginBackgroundTask` (about 30 seconds on current iOS) lets the queue go
///   on; when the time runs out, or the queue waits for the network, it pauses (the chapter goes back to the queue).
/// - Work left: a `BGProcessingTask` (network required) and a `BGAppRefreshTask` are submitted; when iOS runs them
///   (its own schedule: often charging and idle, never after a force quit, never in the simulator) the queue runs
///   until it is empty or the task expires, then they are submitted again if work is left.
/// - Back on screen: the queue carries on (`resume`).
@MainActor
final class BackgroundDownloads {
    static let shared = BackgroundDownloads()

    private static let base = Bundle.main.bundleIdentifier ?? "com.vovoscan.app"
    /// Listed in Info.plist (`BGTaskSchedulerPermittedIdentifiers`, project.yml).
    static let processingId = base + ".downloads"
    static let refreshId = base + ".downloads.refresh"

    private let log = Logger(subsystem: base, category: "downloads")
    private let queue = BackgroundQueue()
    private var activity = DownloadActivity(chapters: 0, books: 0, current: nil, block: nil)
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var draining: Task<Void, Never>?

    private init() {}

    /// At launch, before the app finishes launching (iOS requires the handlers registered by then).
    func register() {
        for id in [Self.processingId, Self.refreshId] {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
                Task { @MainActor in BackgroundDownloads.shared.run(task) }
            }
        }
        Task {
            for await activity in queue.activity() {
                self.activity = activity
            }
        }
    }

    /// The app left the screen: keep downloading for the time iOS gives, then pause; ask for background tasks.
    func enterBackground() {
        guard activity.busy else { return }
        submitTasks()
        guard backgroundTask == .invalid else { return }
        log.info("background: \(self.activity.remaining) left, asking for time")
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Downloads") {
            // Time is up: stop the requests before iOS suspends the app, so nothing is marked failed.
            Task { @MainActor in
                let me = BackgroundDownloads.shared
                me.log.info("background time expired (\(me.activity.remaining) left): pausing")
                me.draining?.cancel()
                // The pause waits at most 2 s for the chapter to stop; never hold iOS's time past that.
                let deadline = Task { @MainActor in
                    try? await Task.sleep(for: .seconds(3))
                    me.endBackgroundTask()
                }
                try? await me.queue.pause()
                deadline.cancel()
                me.log.info("paused")
                me.endBackgroundTask()
            }
        }
        draining = Task {
            let block = try? await queue.drain()
            guard !Task.isCancelled else { return }
            log.info("background: queue settled, waiting for \(String(describing: block))")
            // Waiting for the network: nothing to do until a background task or the next launch.
            if block != nil { try? await queue.pause() }
            endBackgroundTask()
        }
    }

    /// Back on screen: what was paused carries on.
    func becomeActive() {
        draining?.cancel()
        draining = nil
        endBackgroundTask()
        queue.resume()
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// A background task iOS started: run the queue until it is empty, the network is missing, or time runs out.
    private func run(_ task: BGTask) {
        log.info("background task \(task.identifier) started")
        let work = Task {
            let block = try? await queue.drain()
            if Task.isCancelled { return }
            try? await queue.pause()
            log.info("background task done, waiting for \(String(describing: block))")
            if activity.busy || block != nil { submitTasks() }
            task.setTaskCompleted(success: block == nil)
        }
        task.expirationHandler = {
            Task { @MainActor in
                let me = BackgroundDownloads.shared
                work.cancel()
                try? await me.queue.pause()
                me.submitTasks()
                me.log.info("background task expired")
                task.setTaskCompleted(success: false)
            }
        }
    }

    private func submitTasks() {
        let processing = BGProcessingTaskRequest(identifier: Self.processingId)
        processing.requiresNetworkConnectivity = true
        processing.requiresExternalPower = false
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshId)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        for request in [processing, refresh] as [BGTaskRequest] {
            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                // The simulator answers "unavailable": background tasks only run on a device.
                log.info("background task \(request.identifier) not submitted: \(error.localizedDescription)")
            }
        }
    }
}
