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
/// - iOS 26+: downloads the user starts (a download control, "Download next", "Download all", Retry) also start a
///   `BGContinuedProcessingTask`, which the system shows with its own progress and lets run in the background until the
///   queue is empty (or the user stops it there). Its progress is the shared queue's (`observeActivity()`); it ends when
///   the queue is empty or waits for the network. Refused by the system, or on an earlier iOS, the above applies.
@MainActor
final class BackgroundDownloads {
    static let shared = BackgroundDownloads()

    private static let base = Bundle.main.bundleIdentifier ?? "com.vovoscan.app"
    /// Listed in Info.plist (`BGTaskSchedulerPermittedIdentifiers`, project.yml).
    static let processingId = base + ".downloads"
    static let refreshId = base + ".downloads.refresh"
    /// Continued processing (iOS 26+): `<bundle id>.downloads.continued.*` in Info.plist, one identifier per request.
    static let continuedPrefix = base + ".downloads.continued."

    private let log = Logger(subsystem: base, category: "downloads")
    private let queue = BackgroundQueue()
    private var activity = DownloadActivity(chapters: 0, books: 0, current: nil, block: nil)
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var draining: Task<Void, Never>?
    /// When the user last started downloads: a continued task is asked for when the queue has work soon after.
    private var userStartedAt: Date?
    /// The continued task (iOS 26+) running or being submitted, and its progress bookkeeping.
    private var continued: AnyObject?
    private var continuedPending = false
    private var continuedWork: Task<Void, Never>?
    private var progressTotal = 0
    private var progressFinished = 0
    private var progressRemaining = 0

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
                self.activityChanged()
            }
        }
    }

    /// The user started downloads (a control on a title page or in Downloads). On iOS 26+, once the queue has the work,
    /// a continued processing task keeps it going in the background with the system's progress UI.
    func userStarted() {
        guard #available(iOS 26.0, *) else { return }
        userStartedAt = Date()
        startContinuedIfAsked()
    }

    private func activityChanged() {
        startContinuedIfAsked()
        if #available(iOS 26.0, *) { reportContinued() }
    }

    private func startContinuedIfAsked() {
        guard #available(iOS 26.0, *), let asked = userStartedAt, Date().timeIntervalSince(asked) < 3 else { return }
        guard activity.busy, activity.block == nil, continued == nil, !continuedPending else { return }
        // Only from the foreground: the request is on behalf of the app on screen.
        guard UIApplication.shared.applicationState == .active else { return }
        userStartedAt = nil
        submitContinued()
    }

    /// The app left the screen: keep downloading for the time iOS gives, then pause; ask for background tasks.
    func enterBackground() {
        guard activity.busy else { return }
        submitTasks()
        // A continued task (iOS 26+) keeps the queue going: no 30-second pause.
        guard continued == nil, !continuedPending else { return }
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

    // MARK: - Continued processing (iOS 26+)

    @available(iOS 26.0, *)
    private func submitContinued() {
        let id = Self.continuedPrefix + UUID().uuidString.prefix(8)
        // A continued task's handler may be registered when it is needed (not only at launch), once per identifier.
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { task in
            MainActor.assumeIsolated {
                guard let task = task as? BGContinuedProcessingTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                BackgroundDownloads.shared.runContinued(task)
            }
        }
        guard registered else {
            log.info("continued task \(id) not registered")
            return
        }
        let notice = DownloadText.shared.notification(activity: activity)
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: notice.title, subtitle: notice.text)
        // Now or not at all: refused, the background time and the processing task above take over.
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            continuedPending = true
            log.info("continued task \(id) submitted (\(self.activity.remaining) left)")
        } catch {
            log.info("continued task \(id) not submitted: \(error.localizedDescription)")
        }
    }

    @available(iOS 26.0, *)
    private func runContinued(_ task: BGContinuedProcessingTask) {
        continuedPending = false
        continued = task
        progressFinished = 0
        progressRemaining = Int(activity.remaining)
        progressTotal = Int(activity.remaining)
        log.info("continued task \(task.identifier) started (\(self.activity.remaining) left)")
        reportContinued()
        task.expirationHandler = {
            // The system or the user (from its progress UI) stopped it: pause cleanly when out of sight.
            Task { @MainActor in
                let me = BackgroundDownloads.shared
                me.log.info("continued task expired (\(me.activity.remaining) left)")
                me.continuedWork?.cancel()
                await me.finishContinued(task, success: false)
            }
        }
        continuedWork = Task {
            let block = try? await queue.drain()
            guard !Task.isCancelled else { return }
            log.info("continued task: queue settled, waiting for \(String(describing: block))")
            await finishContinued(task, success: block == nil)
        }
    }

    /// Ends the continued task: the queue is empty, waits for the network, or the task was stopped. Out of sight with
    /// work left, the queue pauses and the processing tasks are asked for, as when the background time runs out.
    @available(iOS 26.0, *)
    private func finishContinued(_ task: BGContinuedProcessingTask, success: Bool) async {
        guard continued === task else { return }
        continued = nil
        continuedWork = nil
        if activity.busy {
            submitTasks()
            if UIApplication.shared.applicationState == .background { try? await queue.pause() }
        }
        task.setTaskCompleted(success: success)
    }

    /// The system's progress for the continued task, from the shared queue: one unit per chapter or book (100 steps,
    /// the one downloading counted by its pages). Work added meanwhile adds to the total.
    @available(iOS 26.0, *)
    private func reportContinued() {
        guard let task = continued as? BGContinuedProcessingTask else { return }
        let remaining = Int(activity.remaining)
        if remaining < progressRemaining { progressFinished += progressRemaining - remaining }
        progressRemaining = remaining
        progressTotal = max(progressTotal, progressFinished + remaining, 1)
        var done = Int64(progressFinished) * 100
        if let current = activity.current, activity.block == nil, current.pageCount > 0 {
            done += Int64(current.pagesDone) * 100 / Int64(current.pageCount)
        }
        task.progress.totalUnitCount = Int64(progressTotal) * 100
        task.progress.completedUnitCount = min(done, task.progress.totalUnitCount)
        let notice = DownloadText.shared.notification(activity: activity)
        task.updateTitle(notice.title, subtitle: notice.text)
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
