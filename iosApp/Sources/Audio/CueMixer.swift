import AVFoundation

/// One clip the shared player wants playing now (`AudioCue` from `PlayerViewModel.frame`, without the Kotlin types).
struct CueState: Equatable {
    let id: String
    /// Signed URL; nil: nothing to play.
    let url: String?
    /// Where in the clip it should be now, ms (looping clips already wrapped by the shared core).
    let offsetMs: Double
    /// 0…1, 0 when muted.
    let gain: Double
    /// −1 left … +1 right.
    let pan: Double
    let loop: Bool
}

/// Plays the cues of each frame on one `AVAudioEngine`:
///
///     AVAudioPlayerNode (one per cue id, volume = gain, pan = pan)
///       → cue mixer (AVAudioMixerNode) → [AVAudioUnitTimePitch, rate = speed, pitch kept] → main mixer → output
///
/// A node is created when its cue id appears and starts at the cue's offset; a loop schedules the rest of the clip
/// from the offset, then the whole clip with `.loops` (back to back, sample-accurate: no gap at the seam). Volume and
/// pan follow every frame; a node more than 250 ms away from its cue's offset (a seek, a stall) is scheduled again
/// from the offset (checked every 300 ms, the rule of the Android player); a node whose id is gone is stopped and
/// detached. Speed is one time-pitch stage for the whole mix, so voices keep their natural pitch; it is in the graph
/// only away from 1× (it reads about 80 ms ahead of what is heard, which would delay gain, pan and pause by as much,
/// and it costs CPU), and is put in or taken out with the engine paused for a moment. No logic here: the
/// shared core decides what plays, when, how loud and where.
///
/// In manual rendering mode (tests), the engine is started and rendered by its owner; otherwise the mixer starts it
/// when a node must play (after `willStartEngine`, which activates the audio session) and pauses it on pause.
@MainActor
final class CueMixer {
    static let driftMs = 250.0
    static let checkEveryMs = 300.0

    let engine: AVAudioEngine
    let clips: ClipStore
    /// Called before the engine starts; false keeps it stopped (the audio session could not be activated).
    var willStartEngine: () -> Bool = { true }
    private let cueMix = AVAudioMixerNode()
    private let speedStage = AVAudioUnitTimePitch()
    private let mixFormat: AVAudioFormat?
    private var stretching = false
    private var voices: [String: Voice] = [:]
    private(set) var playing = false
    private(set) var speed = 1.0
    /// How many nodes were scheduled again after drifting (diagnostics and tests).
    private(set) var resyncs = 0
    private var lastStartAttemptMs = -Double.infinity
    private var configurationObserver: NSObjectProtocol?

    init(engine: AVAudioEngine = AVAudioEngine(), clips: ClipStore) {
        self.engine = engine
        self.clips = clips
        mixFormat = AVAudioFormat(standardFormatWithSampleRate: clips.sampleRate, channels: 2)
        engine.attach(cueMix)
        engine.attach(speedStage)
        engine.connect(cueMix, to: engine.mainMixerNode, format: mixFormat)
        // The output changed (headphones, sample rate): the engine stopped; nodes are made again on the next frame.
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.reset() }
        }
    }

    deinit {
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
    }

    /// Call once per display frame with the frame's cues; `nowMs` is a monotonic clock (drift checks).
    func sync(_ cues: [CueState], playing: Bool, speed: Double, nowMs: Double) {
        setSpeed(speed)
        setPlaying(playing, nowMs: nowMs)
        var seen = Set<String>()
        for cue in cues {
            guard let url = cue.url else { continue }
            seen.insert(cue.id)
            if let voice = voices[cue.id] {
                apply(cue, to: voice)
                if nowMs - voice.checkedAt >= Self.checkEveryMs {
                    voice.checkedAt = nowMs
                    if drift(voice, cue) > frames(Self.driftMs, voice) { resync(voice, cue, nowMs: nowMs) }
                }
            } else if let buffer = clips.clip(url) {
                start(cue, buffer, nowMs: nowMs)
            }
        }
        if voices.count > seen.count {
            for id in voices.keys where !seen.contains(id) { remove(id) }
        }
    }

    /// Pauses (or resumes) every node; outside manual rendering, a paused mixer also pauses its engine.
    func setPlaying(_ playing: Bool, nowMs: Double = CACurrentMediaTime() * 1000) {
        if playing == self.playing {
            // Playing but the engine could not start (or was stopped): try again, at most once a second.
            if playing, !voices.isEmpty, !engine.isRunning, nowMs - lastStartAttemptMs >= 1000, ensureRunning(nowMs) {
                voices.values.forEach { $0.node.play() }
            }
            return
        }
        self.playing = playing
        if playing {
            guard !voices.isEmpty, ensureRunning(nowMs) else { return }
            voices.values.forEach { $0.node.play() }
        } else {
            for voice in voices.values {
                _ = position(voice) // holds the place it paused at
                voice.node.pause()
            }
            if !engine.isInManualRenderingMode, engine.isRunning { engine.pause() }
        }
    }

    /// Stops and detaches every node (the engine stopped under them); the next frame makes them again at their offsets.
    func reset() {
        for id in Array(voices.keys) { remove(id) }
    }

    /// Stops everything and the engine (the player screen closes).
    func close() {
        reset()
        if !engine.isInManualRenderingMode { engine.stop() }
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
    }

    /// Where the node of cue `id` is in its clip, ms (tests and diagnostics).
    func positionMs(_ id: String) -> Double? {
        guard let voice = voices[id] else { return nil }
        return Double(position(voice)) * 1000 / voice.buffer.format.sampleRate
    }

    var nodeCount: Int { voices.count }

    // MARK: Nodes

    private final class Voice {
        let node = AVAudioPlayerNode()
        let buffer: AVAudioPCMBuffer
        let loop: Bool
        /// Clip frame the node was scheduled from.
        var base: AVAudioFramePosition = 0
        /// Frames played since then (the node's own clock; kept while paused).
        var rendered: AVAudioFramePosition = 0
        var checkedAt: Double
        var gain: Float = -1
        var pan: Float = .nan

        init(buffer: AVAudioPCMBuffer, loop: Bool, nowMs: Double) {
            self.buffer = buffer
            self.loop = loop
            checkedAt = nowMs
        }

        var length: AVAudioFramePosition { AVAudioFramePosition(buffer.frameLength) }
    }

    private func start(_ cue: CueState, _ buffer: AVAudioPCMBuffer, nowMs: Double) {
        let voice = Voice(buffer: buffer, loop: cue.loop, nowMs: nowMs)
        engine.attach(voice.node)
        engine.connect(voice.node, to: cueMix, fromBus: 0, toBus: cueMix.nextAvailableInputBus, format: buffer.format)
        apply(cue, to: voice)
        schedule(voice, from: frames(cue.offsetMs, voice))
        voices[cue.id] = voice
        if playing, ensureRunning(nowMs) { voice.node.play() }
    }

    private func remove(_ id: String) {
        guard let voice = voices.removeValue(forKey: id) else { return }
        voice.node.stop()
        engine.detach(voice.node)
    }

    private func apply(_ cue: CueState, to voice: Voice) {
        let gain = Float(max(0, min(1, cue.gain)))
        if abs(gain - voice.gain) > 0.0005 {
            voice.node.volume = gain
            voice.gain = gain
        }
        let pan = Float(max(-1, min(1, cue.pan)))
        if pan != voice.pan {
            voice.node.pan = pan
            voice.pan = pan
        }
    }

    /// From clip frame `at`: a clip plays to its end; a loop plays the rest of the clip, then the whole clip forever.
    private func schedule(_ voice: Voice, from at: AVAudioFramePosition) {
        let length = voice.length
        guard length > 0 else { return }
        if voice.loop {
            let from = ((at % length) + length) % length
            voice.base = from
            if from > 0, let rest = Self.slice(voice.buffer, from: AVAudioFrameCount(from)) {
                voice.node.scheduleBuffer(rest, at: nil, options: [], completionHandler: nil)
            }
            voice.node.scheduleBuffer(voice.buffer, at: nil, options: .loops, completionHandler: nil)
        } else {
            let from = max(0, at)
            voice.base = from
            if from == 0 {
                voice.node.scheduleBuffer(voice.buffer, at: nil, options: [], completionHandler: nil)
            } else if from < length, let rest = Self.slice(voice.buffer, from: AVAudioFrameCount(from)) {
                voice.node.scheduleBuffer(rest, at: nil, options: [], completionHandler: nil)
            }
        }
        voice.rendered = 0
    }

    private func resync(_ voice: Voice, _ cue: CueState, nowMs: Double) {
        resyncs += 1
        voice.node.stop()
        schedule(voice, from: frames(cue.offsetMs, voice))
        if playing, ensureRunning(nowMs) { voice.node.play() }
    }

    /// The node's place in its clip, in clip frames (wrapped for a loop; past the end once a clip has finished).
    private func position(_ voice: Voice) -> AVAudioFramePosition {
        if let now = voice.node.lastRenderTime, now.isSampleTimeValid,
           let played = voice.node.playerTime(forNodeTime: now) {
            voice.rendered = max(0, played.sampleTime)
        }
        let raw = voice.base + voice.rendered
        return voice.loop ? raw % max(1, voice.length) : raw
    }

    private func drift(_ voice: Voice, _ cue: CueState) -> AVAudioFramePosition {
        let length = voice.length
        let at = position(voice)
        let expected = frames(cue.offsetMs, voice)
        if voice.loop {
            let wrapped = ((expected % length) + length) % length
            let d = abs(at - wrapped)
            return min(d, length - d)
        }
        return abs(min(at, length) - min(expected, length))
    }

    private func frames(_ ms: Double, _ voice: Voice) -> AVAudioFramePosition {
        AVAudioFramePosition((ms * voice.buffer.format.sampleRate / 1000).rounded())
    }

    private func setSpeed(_ speed: Double) {
        guard speed != self.speed else { return }
        self.speed = speed
        speedStage.rate = Float(speed)
        let stretch = abs(speed - 1) > 0.001
        guard stretch != stretching else { return }
        stretching = stretch
        // Rewired with the engine paused (manual rendering: between two renders); the nodes keep their place.
        let resume = engine.isRunning && !engine.isInManualRenderingMode
        if resume { engine.pause() }
        engine.disconnectNodeOutput(cueMix)
        engine.disconnectNodeOutput(speedStage)
        if stretch {
            engine.connect(speedStage, to: engine.mainMixerNode, format: mixFormat)
            engine.connect(cueMix, to: speedStage, format: mixFormat)
        } else {
            engine.connect(cueMix, to: engine.mainMixerNode, format: mixFormat)
        }
        if resume { try? engine.start() }
    }

    private func ensureRunning(_ nowMs: Double) -> Bool {
        if engine.isRunning { return true }
        if engine.isInManualRenderingMode { return false }
        lastStartAttemptMs = nowMs
        guard willStartEngine() else { return false }
        do {
            engine.prepare()
            try engine.start()
            return true
        } catch {
            return false
        }
    }

    /// The clip from frame `from` to its end, sharing the clip's memory (no copy; keeps the clip alive).
    private static func slice(_ buffer: AVAudioPCMBuffer, from: AVAudioFrameCount) -> AVAudioPCMBuffer? {
        guard from < buffer.frameLength, let data = buffer.floatChannelData else { return nil }
        let frames = buffer.frameLength - from
        let channels = Int(buffer.format.channelCount)
        let list = AudioBufferList.allocate(maximumBuffers: channels)
        for channel in 0..<channels {
            list[channel] = AudioBuffer(
                mNumberChannels: 1,
                mDataByteSize: frames * UInt32(MemoryLayout<Float>.size),
                mData: UnsafeMutableRawPointer(data[channel] + Int(from))
            )
        }
        return AVAudioPCMBuffer(pcmFormat: buffer.format, bufferListNoCopy: list.unsafePointer) { _ in
            withExtendedLifetime(buffer) {}
            free(list.unsafeMutablePointer)
        }
    }
}
