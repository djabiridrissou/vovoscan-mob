import AVFoundation
import QuartzCore

/// The player screen's sound: one `CueMixer` (made when the first clip must play), its `ClipStore` and the audio
/// session. `.playback` category: the episode plays with the silent switch on. An interruption (a call, Siri) pauses
/// the episode and resumes it when the system says it should, if it was playing; unplugging headphones pauses it; a
/// media-services reset rebuilds the engine. `close()` stops everything and lets other apps' audio resume.
@MainActor
final class PlayerAudio {
    var onFailed: (String) -> Void = { _ in } {
        didSet { clips.onFailed = onFailed }
    }

    /// Pause the episode (interruption, headphones unplugged): the screen sends `PlayerIntent.Pause`.
    var onPause: () -> Void = {}
    /// The interruption is over and the system says playback may resume: the screen sends `PlayerIntent.Play`.
    var onResume: () -> Void = {}

    private let clips = ClipStore()
    private var mixer: CueMixer?
    private var sessionActive = false
    private var playing = false
    private var resumeAfterInterruption = false
    private var closed = false
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            let info = note.userInfo ?? [:]
            let type = (info[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            let options = AVAudioSession.InterruptionOptions(rawValue: info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            Task { @MainActor in self?.interrupted(type, options) }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            Task { @MainActor in
                // Headphones or a Bluetooth device gone: never carry on through the speaker.
                if reason == .oldDeviceUnavailable { self?.onPause() }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.rebuild() }
        })
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    /// Every display frame, with the frame's cues and the player's state.
    func sync(_ cues: [CueState], playing: Bool, speed: Double) {
        guard !closed else { return }
        self.playing = playing
        if mixer == nil {
            guard cues.contains(where: { $0.url != nil }) else { return }
            mixer = makeMixer()
        }
        mixer?.sync(cues, playing: playing, speed: speed, nowMs: CACurrentMediaTime() * 1000)
    }

    /// When the play state changes (also in the background, where no frame comes).
    func setPlaying(_ playing: Bool) {
        guard !closed else { return }
        self.playing = playing
        mixer?.setPlaying(playing)
    }

    /// Warms a clip ahead of its cue.
    func prefetch(_ url: String) {
        guard !closed else { return }
        clips.request(url)
    }

    func close() {
        guard !closed else { return }
        closed = true
        mixer?.close()
        mixer = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        if sessionActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            sessionActive = false
        }
    }

    private func makeMixer() -> CueMixer {
        let mixer = CueMixer(clips: clips)
        mixer.willStartEngine = { [weak self] in self?.activateSession() ?? false }
        return mixer
    }

    private func activateSession() -> Bool {
        if sessionActive { return true }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            sessionActive = true
            return true
        } catch {
            return false
        }
    }

    private func interrupted(_ type: AVAudioSession.InterruptionType?, _ options: AVAudioSession.InterruptionOptions) {
        guard !closed else { return }
        switch type {
        case .began:
            // The system stopped the engine and deactivated the session.
            resumeAfterInterruption = playing
            sessionActive = false
            mixer?.setPlaying(false)
            mixer?.reset()
            onPause()
        case .ended:
            if resumeAfterInterruption, options.contains(.shouldResume) { onResume() }
            resumeAfterInterruption = false
        default:
            break
        }
    }

    private func rebuild() {
        guard !closed else { return }
        mixer?.close()
        // The next frame makes a new engine and sets the session up again.
        mixer = nil
        sessionActive = false
    }
}
