import Accelerate
import AVFoundation
import XCTest

/// The episode audio of the app (`Sources/Audio`, compiled into this bundle as it is) played through a real
/// `AVAudioEngine` rendered offline (`enableManualRenderingMode`), with Ogg Opus clips made the backend's way
/// (`make-fixtures.sh`): the voice line is mono, the loop stereo (440 Hz left, 400 Hz right, 2.025 s).
/// The test drives the mixer like the player screen: one `sync` per 60 fps display frame (800 frames at 48 kHz).
@MainActor
final class CueMixerTests: XCTestCase {
    nonisolated private static let rate = 48_000.0
    private static let frameStep: AVAudioFrameCount = 800
    private static let loopFrames = 97_200
    /// Render cycles a node attached while the engine runs may take to be heard (0 to 3 seen: up to 50 ms here).
    private static let startCycles = 3

    // MARK: Rig

    private struct Stereo {
        var left: [Float] = []
        var right: [Float] = []
    }

    @MainActor
    private final class Rig {
        let engine = AVAudioEngine()
        let store: ClipStore
        let mixer: CueMixer
        let directory: URL
        var nowMs = 0.0
        var failures: [String] = []

        init(sampleRate: Double = CueMixerTests.rate, memoryCap: Int = 96 << 20) throws {
            directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let format = AVAudioFormat(standardFormatWithSampleRate: CueMixerTests.rate, channels: 2)!
            try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
            store = ClipStore(directory: directory, sampleRate: sampleRate, memoryCap: memoryCap)
            mixer = CueMixer(engine: engine, clips: store)
            store.onFailed = { [unowned self] in self.failures.append($0) }
            try engine.start()
        }

        deinit { try? FileManager.default.removeItem(at: directory) }

        /// Renders `seconds`; before each display frame, `cues(t)` gives the cues at `t` seconds into this run.
        func run(_ seconds: Double, playing: Bool = true, speed: Double = 1, cues: (Double) -> [CueState]) throws -> Stereo {
            var out = Stereo()
            let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: CueMixerTests.frameStep)!
            let total = Int(seconds * CueMixerTests.rate)
            var done = 0
            while done < total {
                mixer.sync(cues(Double(done) / CueMixerTests.rate), playing: playing, speed: speed, nowMs: nowMs)
                let status = try engine.renderOffline(CueMixerTests.frameStep, to: buffer)
                XCTAssertEqual(status, .success)
                let n = Int(buffer.frameLength)
                out.left += UnsafeBufferPointer(start: buffer.floatChannelData![0], count: n)
                out.right += UnsafeBufferPointer(start: buffer.floatChannelData![1], count: n)
                done += n
                nowMs += Double(n) * 1000 / CueMixerTests.rate
            }
            return out
        }
    }

    private func fixture(_ name: String) throws -> String {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "ogg")).absoluteString
    }

    private func loaded(_ rig: Rig, _ url: String) async throws -> AVAudioPCMBuffer {
        let buffer = await rig.store.load(url)
        return try XCTUnwrap(buffer)
    }

    private func samples(_ buffer: AVAudioPCMBuffer, channel: Int = 0) -> [Float] {
        Array(UnsafeBufferPointer(start: buffer.floatChannelData![channel], count: Int(buffer.frameLength)))
    }

    private func cue(_ id: String, _ url: String, at ms: Double, gain: Double = 1, pan: Double = 0, loop: Bool = false) -> CueState {
        CueState(id: id, url: url, offsetMs: ms, gain: gain, pan: pan, loop: loop)
    }

    // MARK: Signal helpers

    private func rms(_ x: [Float], _ range: Range<Int>) -> Double {
        let slice = x[range]
        return (slice.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, slice.count))).squareRoot()
    }

    /// The shift (frames) of `source` that best matches `rendered[window]` around `expected`, and its normalised
    /// correlation.
    private func bestLag(_ rendered: [Float], window: Range<Int>, source: [Float], expected: Int, search: Int = 2400) -> (lag: Int, score: Double) {
        var best = (lag: Int.min, score: -2.0)
        let a = Array(rendered[window])
        let n = vDSP_Length(a.count)
        var ea: Float = 0
        vDSP_svesq(a, 1, &ea, n)
        source.withUnsafeBufferPointer { src in
            for lag in -search...search {
                let start = expected + lag
                guard start >= 0, start + a.count <= src.count else { continue }
                var dot: Float = 0
                var eb: Float = 0
                vDSP_dotpr(a, 1, src.baseAddress! + start, 1, &dot, n)
                vDSP_svesq(src.baseAddress! + start, 1, &eb, n)
                let score = Double(dot) / max(1e-12, (Double(ea) * Double(eb)).squareRoot())
                if score > best.score { best = (lag, score) }
            }
        }
        return best
    }

    /// Frequency (Hz) from rising zero crossings, interpolated.
    private func frequency(_ x: [Float], _ range: Range<Int>) -> Double {
        var crossings: [Double] = []
        for i in (range.lowerBound + 1)..<range.upperBound where x[i - 1] < 0 && x[i] >= 0 {
            let fraction = Double(-x[i - 1]) / Double(x[i] - x[i - 1])
            crossings.append(Double(i - 1) + fraction)
        }
        guard let first = crossings.first, let last = crossings.last, crossings.count > 1 else { return 0 }
        return Double(crossings.count - 1) / ((last - first) / Self.rate)
    }

    // MARK: Decoding and caching

    func testDecodesTheBackendsOggOpusClipsAtTheEngineRate() async throws {
        let rig = try Rig()
        let voice = try await loaded(rig, try fixture("voice"))
        XCTAssertEqual(voice.format.channelCount, 1)
        XCTAssertEqual(voice.format.sampleRate, 48_000)
        XCTAssertEqual(voice.format.commonFormat, .pcmFormatFloat32)
        XCTAssertGreaterThan(voice.frameLength, 140_000)
        XCTAssertGreaterThan(rms(samples(voice), 0..<Int(voice.frameLength)), 0.01)
        let loop = try await loaded(rig, try fixture("loop"))
        XCTAssertEqual(loop.format.channelCount, 2)
        // Exactly the encoded PCM: Opus pre-skip and end trimming are applied, so the loop is the right length.
        XCTAssertEqual(Int(loop.frameLength), Self.loopFrames)
        XCTAssertTrue(rig.failures.isEmpty)
        // One file per clip in the cache folder.
        let files = try FileManager.default.contentsOfDirectory(atPath: rig.directory.path)
        XCTAssertEqual(files.count, 2)
        XCTAssertTrue(files.allSatisfy { $0.hasSuffix(".ogg") })
    }

    func testConvertsToAnotherEngineRate() async throws {
        let rig = try Rig(sampleRate: 44_100)
        let loop = try await loaded(rig, try fixture("loop"))
        XCTAssertEqual(loop.format.sampleRate, 44_100)
        XCTAssertEqual(loop.format.channelCount, 2)
        XCTAssertEqual(Double(loop.frameLength), Double(Self.loopFrames) * 44_100 / 48_000, accuracy: 4)
    }

    func testSignedUrlsOfTheSameClipShareOneDecodedCopy() async throws {
        let rig = try Rig()
        let url = try fixture("voice")
        let first = try await loaded(rig, url)
        XCTAssertTrue(rig.store.clip(url + "?sig=refreshed") === first)
    }

    func testMemoryCacheForgetsTheOldestClipPastItsCap() async throws {
        // loop ≈ 778 KB, voice ≈ 590 KB of float PCM.
        let rig = try Rig(memoryCap: 1_000_000)
        let loopUrl = try fixture("loop")
        let voiceUrl = try fixture("voice")
        _ = try await loaded(rig, loopUrl)
        _ = try await loaded(rig, voiceUrl)
        XCTAssertNotNil(rig.store.clip(voiceUrl))
        XCTAssertNil(rig.store.clip(loopUrl))
        // Decoded again from the cache file.
        let again = await rig.store.load(loopUrl)
        XCTAssertNotNil(again)
    }

    func testFailedDownloadOrDecodeIsReportedOnce() async throws {
        let rig = try Rig()
        let missing = rig.directory.appendingPathComponent("nope/missing.ogg").absoluteString
        let nothing = await rig.store.load(missing)
        XCTAssertNil(nothing)
        XCTAssertEqual(rig.failures, [missing])
        // Not retried every frame.
        XCTAssertNil(rig.store.clip(missing))
        XCTAssertEqual(rig.failures, [missing])

        let junkFolder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: junkFolder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: junkFolder) }
        let junk = junkFolder.appendingPathComponent("junk.ogg")
        try Data((0..<4096).map { UInt8($0 % 251) }).write(to: junk)
        let decoded = await rig.store.load(junk.absoluteString)
        XCTAssertNil(decoded)
        XCTAssertEqual(rig.failures, [missing, junk.absoluteString])
        // A clip that failed to decode is not kept on disk.
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: rig.directory.path), [])
    }

    func testCueWithAFailedClipPlaysNothingAndReportsIt() async throws {
        let rig = try Rig()
        let missing = rig.directory.appendingPathComponent("gone.ogg").absoluteString
        _ = try rig.run(0.1) { [self] t in [cue("voice:x", missing, at: t * 1000)] }
        _ = await rig.store.load(missing)
        let out = try rig.run(0.2) { [self] t in [cue("voice:x", missing, at: 100 + t * 1000)] }
        XCTAssertEqual(rig.failures, [missing])
        XCTAssertEqual(rig.mixer.nodeCount, 0)
        XCTAssertEqual(rms(out.left, 0..<out.left.count), 0)
    }

    // MARK: Playback

    func testVoiceStartsAtTheCuesOffsetCentred() async throws {
        let rig = try Rig()
        let url = try fixture("voice")
        let voice = samples(try await loaded(rig, url))
        let out = try rig.run(0.5) { [self] t in [cue("voice:b1:0", url, at: 1000 + t * 1000)] }
        // Rendered frame 2400 is clip frame 48 000 + 2400, give or take the render cycles a new node (attached and
        // connected while the engine runs) takes to start: it starts at the offset, the sound reaches the output a
        // little later.
        let match = bestLag(out.left, window: 2400..<12_000, source: voice, expected: 48_000 + 2400)
        print("offset start: lag \(match.lag) frames (\(Double(match.lag) / 48) ms), correlation \(match.score)")
        XCTAssertLessThanOrEqual(match.lag, 0)
        XCTAssertGreaterThanOrEqual(match.lag, -Self.startCycles * Int(Self.frameStep))
        XCTAssertGreaterThan(match.score, 0.999)
        // Pan 0: the mono line is in the middle.
        XCTAssertEqual(rms(out.left, 0..<24_000), rms(out.right, 0..<24_000), accuracy: 1e-6)
        XCTAssertEqual(rig.mixer.nodeCount, 1)
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testGainFollowsEveryFrame() async throws {
        // The steady stereo tone: its level is the gain alone.
        let rig = try Rig()
        let url = try fixture("loop")
        _ = try await loaded(rig, url)
        let lengthMs = Double(Self.loopFrames) / 48
        var levels: [Double] = []
        var clock = 0.0
        for gain in [1.0, 0.5, 0.25, 0.8] {
            let out = try rig.run(0.3) { [self] t in
                [cue("bed:0", url, at: (clock + t * 1000).truncatingRemainder(dividingBy: lengthMs), gain: gain, loop: true)]
            }
            clock += 300
            // The mixer smooths a volume change over a few ms: measured after 100 ms.
            levels.append(rms(out.left, 4800..<out.left.count))
        }
        let ratios = levels.map { $0 / levels[0] }
        print("gain 1, 0.5, 0.25, 0.8 → levels relative to gain 1: \(ratios)")
        XCTAssertEqual(ratios[1], 0.5, accuracy: 0.005)
        XCTAssertEqual(ratios[2], 0.25, accuracy: 0.005)
        XCTAssertEqual(ratios[3], 0.8, accuracy: 0.005)
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testPanFollowsEveryFrame() async throws {
        // The mono voice line: −1 all left, +1 all right, 0 in the middle (constant power: −3 dB a side).
        let rig = try Rig()
        let url = try fixture("voice")
        _ = try await loaded(rig, url)
        let id = "voice:b1:0"
        var clock = 300.0
        var sides: [(left: Double, right: Double)] = []
        for pan in [0.0, -1.0, 1.0, -0.5] {
            let out = try rig.run(0.4) { [self] t in [cue(id, url, at: clock + t * 1000, pan: pan)] }
            clock += 400
            sides.append((rms(out.left, 4800..<out.left.count), rms(out.right, 4800..<out.right.count)))
        }
        print("pan 0, −1, +1, −0.5 → (left, right) levels: \(sides)")
        XCTAssertEqual(sides[0].left, sides[0].right, accuracy: 1e-6)
        XCTAssertLessThan(sides[1].right, 0.001 * sides[1].left)
        XCTAssertLessThan(sides[2].left, 0.001 * sides[2].right)
        XCTAssertGreaterThan(sides[3].left, 2 * sides[3].right)
        XCTAssertGreaterThan(sides[3].right, 0.1 * sides[3].left)
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testLoopIsSeamless() async throws {
        let rig = try Rig()
        let url = try fixture("loop")
        let buffer = try await loaded(rig, url)
        let clipLeft = samples(buffer, channel: 0)
        let clipRight = samples(buffer, channel: 1)
        let length = Self.loopFrames
        let lengthMs = Double(length) / 48
        // Starts half a second before the end: seams at rendered frames 24 000 and 121 200.
        let out = try rig.run(3.0) { [self] t in
            [cue("bed:0", url, at: (lengthMs - 500 + t * 1000).truncatingRemainder(dividingBy: lengthMs), gain: 1, loop: true)]
        }
        let start = length - 24_000
        // A new node is heard after up to three render cycles (its connection is made while the engine runs).
        let lead = try XCTUnwrap(out.left.firstIndex { abs($0) > 1e-6 })
        print("loop: first sound at rendered frame \(lead)")
        XCTAssertLessThanOrEqual(lead, Self.startCycles * Int(Self.frameStep))
        // 1. The engine plays the clip back to back: rendered frame lead + n is the clip's frame (start + n) mod length.
        var worst: Float = 0
        for n in 0..<(out.left.count - lead) {
            let i = (start + n) % length
            worst = max(worst, abs(out.left[lead + n] - clipLeft[i]), abs(out.right[lead + n] - clipRight[i]))
        }
        print("loop: largest difference from the clip played back to back: \(worst)")
        XCTAssertLessThan(worst, 1e-5)
        // 2. No silence once it started: no 1 ms window below half the level of the whole render.
        let level = rms(out.left, lead..<out.left.count)
        var quietest = Double.infinity
        for w in stride(from: lead, to: out.left.count - 48, by: 48) { quietest = min(quietest, rms(out.left, w..<w + 48)) }
        print("loop: level \(level), quietest 1 ms window \(quietest)")
        XCTAssertGreaterThan(quietest, 0.5 * level)
        // 3. The seam itself: a click shows in the second difference (a pure tone's is tiny, a jump of d shows as d).
        // The PCM was seamless; what is left is the Opus encoding of the clip's two ends, not the playback.
        func curvature(_ x: [Float], _ range: Range<Int>) -> Float {
            var m: Float = 0
            for i in range.lowerBound + 1..<range.upperBound - 1 { m = max(m, abs(x[i + 1] - 2 * x[i] + x[i - 1])) }
            return m
        }
        for seam in [lead + 24_000, lead + 24_000 + length] {
            for (name, channel) in [("left", out.left), ("right", out.right)] {
                let peak = channel[seam - 2400..<seam + 2400].map { abs($0) }.max() ?? 0
                let atSeam = curvature(channel, seam - 4..<seam + 4)
                let elsewhere = curvature(channel, seam + 1000..<seam + 20_000)
                print("loop seam at \(seam) \(name): jump \(atSeam) (\(20 * log10(Double(atSeam / peak))) dB under the tone), elsewhere \(elsewhere), peak \(peak)")
                XCTAssertLessThan(atSeam, 0.05 * peak)
            }
        }
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testLoopFollowsTheTimelinesPeriodWithoutJumping() async throws {
        // The timeline's loop length is ffprobe's duration (2031.5 ms, Opus pre-skip included), 6.5 ms more than the
        // decoded clip: the bed slowly drifts from the clock and is re-synced only once it is 250 ms away.
        let rig = try Rig()
        let url = try fixture("loop")
        _ = try await loaded(rig, url)
        _ = try rig.run(6.0) { [self] t in [cue("bed:0", url, at: (t * 1000).truncatingRemainder(dividingBy: 2032), loop: true)] }
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testSpeedKeepsThePitch() async throws {
        for speed in [1.5, 0.75] {
            let rig = try Rig()
            let url = try fixture("loop")
            let lengthMs = Double(Self.loopFrames) / 48
            _ = try await loaded(rig, url)
            let out = try rig.run(3.0, speed: speed) { [self] t in
                [cue("music:0", url, at: (t * speed * 1000).truncatingRemainder(dividingBy: lengthMs), loop: true)]
            }
            let left = frequency(out.left, 24_000..<out.left.count - 4800)
            let right = frequency(out.right, 24_000..<out.right.count - 4800)
            print("speed \(speed): left \(left) Hz (440), right \(right) Hz (400)")
            XCTAssertEqual(left, 440, accuracy: 440 * 0.01)
            XCTAssertEqual(right, 400, accuracy: 400 * 0.01)
            // The clip is played at the episode clock's pace: never re-synced, where the clock says.
            XCTAssertEqual(rig.mixer.resyncs, 0)
            let expected = (3000 * speed).truncatingRemainder(dividingBy: lengthMs)
            let position = try XCTUnwrap(rig.mixer.positionMs("music:0"))
            print("speed \(speed): node at \(position) ms, clock at \(expected) ms")
            XCTAssertEqual(position, expected, accuracy: 120)
        }
    }

    func testVoiceSpeedUpKeepsItsPitch() async throws {
        // The same words of the voice line at 1× and 1.5×: the voice's fundamental stays put (a resampled line would
        // go up by 1.5×, a fifth).
        let rig = try Rig()
        let url = try fixture("voice")
        _ = try await loaded(rig, url)
        let normal = try rig.run(1.5, speed: 1) { [self] t in [cue("voice:a", url, at: 300 + t * 1000)] }
        let fast = try rig.run(1.0, speed: 1.5) { [self] t in [cue("voice:b", url, at: 300 + t * 1500)] }
        let f1 = pitch(normal.left)
        let f2 = pitch(fast.left)
        print("voice fundamental: \(f1) Hz at 1×, \(f2) Hz at 1.5× (ratio \(f2 / f1))")
        XCTAssertGreaterThan(f1, 60)
        XCTAssertEqual(f2 / f1, 1, accuracy: 0.08)
    }

    /// Median fundamental (Hz) of the clearly voiced 40 ms frames (every 10 ms): normalised autocorrelation between
    /// 90 and 400 Hz, taking the shortest period that scores within 5 % of the best (no octave-down errors).
    private func pitch(_ x: [Float]) -> Double {
        let size = 1920
        let level = rms(x, 0..<x.count)
        var found: [Double] = []
        x.withUnsafeBufferPointer { p in
            for start in stride(from: 0, to: x.count - 2 * size, by: 480) where rms(x, start..<start + size) > level {
                var scores: [Float] = []
                let lags = 120...533
                for lag in lags {
                    var dot: Float = 0
                    var e1: Float = 0
                    var e2: Float = 0
                    vDSP_dotpr(p.baseAddress! + start, 1, p.baseAddress! + start + lag, 1, &dot, vDSP_Length(size))
                    vDSP_svesq(p.baseAddress! + start, 1, &e1, vDSP_Length(size))
                    vDSP_svesq(p.baseAddress! + start + lag, 1, &e2, vDSP_Length(size))
                    scores.append(dot / max(1e-9, (e1 * e2).squareRoot()))
                }
                guard let best = scores.max(), best > 0.8 else { continue }
                // The first local peak close to the best one.
                for i in 1..<scores.count - 1 where scores[i] >= 0.95 * best && scores[i] >= scores[i - 1] && scores[i] >= scores[i + 1] {
                    found.append(Self.rate / Double(lags.lowerBound + i))
                    break
                }
            }
        }
        guard !found.isEmpty else { return 0 }
        return found.sorted()[found.count / 2]
    }

    func testPauseHoldsThePlaceAndResumeCarriesOn() async throws {
        let rig = try Rig()
        let url = try fixture("voice")
        let voice = samples(try await loaded(rig, url))
        let id = "voice:b1:0"
        _ = try rig.run(0.5) { [self] t in [cue(id, url, at: t * 1000)] }
        let paused = try rig.run(0.5, playing: false) { [self] _ in [cue(id, url, at: 500)] }
        XCTAssertEqual(rms(paused.left, 0..<paused.left.count), 0, accuracy: 1e-6)
        let held = try XCTUnwrap(rig.mixer.positionMs(id))
        XCTAssertEqual(held, 500, accuracy: 1)
        let resumed = try rig.run(0.5) { [self] t in [cue(id, url, at: 500 + t * 1000)] }
        // Rendered frame 2400 is clip frame 24 000 + 2400 (the place held), later by whole render cycles only: play()
        // on a node of a running engine takes effect at the start of a render cycle, the first or one of the next
        // (the start latency every node has, `startCycles`). A lag that is not a whole number of cycles would be the
        // place lost (frames skipped or played twice); the window starts after the latest start.
        let match = bestLag(resumed.left, window: 2400..<12_000, source: voice, expected: 24_000 + 2400)
        print("resume: lag \(match.lag) frames, correlation \(match.score)")
        XCTAssertEqual(match.lag % Int(Self.frameStep), 0)
        XCTAssertLessThanOrEqual(match.lag, 0)
        XCTAssertGreaterThanOrEqual(match.lag, -Self.startCycles * Int(Self.frameStep))
        XCTAssertGreaterThan(match.score, 0.999)
        XCTAssertEqual(rig.mixer.resyncs, 0)
    }

    func testDriftOver250MsIsResynced() async throws {
        let rig = try Rig()
        let url = try fixture("voice")
        let voice = samples(try await loaded(rig, url))
        let id = "voice:b1:0"
        _ = try rig.run(0.5) { [self] t in [cue(id, url, at: t * 1000)] }
        // 150 ms off: left alone.
        _ = try rig.run(0.5) { [self] t in [cue(id, url, at: 500 + 150 + t * 1000)] }
        XCTAssertEqual(rig.mixer.resyncs, 0)
        // A seek to 2 s: back in place within one check (300 ms).
        let out = try rig.run(0.6) { [self] t in [cue(id, url, at: 2000 + t * 1000)] }
        XCTAssertEqual(rig.mixer.resyncs, 1)
        let position = try XCTUnwrap(rig.mixer.positionMs(id))
        XCTAssertEqual(position, 2600, accuracy: 1)
        // The last 0.2 s rendered is the clip from 2.4 s.
        let match = bestLag(out.left, window: 19_200..<28_800, source: voice, expected: 2400 * 48)
        print("resync: lag \(match.lag) frames (\(Double(match.lag) / 48) ms), correlation \(match.score)")
        // play() after stop() starts within a render cycle or two.
        XCTAssertLessThanOrEqual(match.lag, 0)
        XCTAssertGreaterThanOrEqual(match.lag, -2 * Int(Self.frameStep))
        XCTAssertGreaterThan(match.score, 0.999)
    }

    func testGoneCueIsStoppedAndDetached() async throws {
        let rig = try Rig()
        let voiceUrl = try fixture("voice")
        let loopUrl = try fixture("loop")
        _ = await rig.store.load(voiceUrl)
        _ = await rig.store.load(loopUrl)
        let base = rig.engine.attachedNodes.count
        _ = try rig.run(0.2) { [self] t in
            [cue("voice:a", voiceUrl, at: t * 1000), cue("bed:0", loopUrl, at: t * 1000, gain: 0.3, loop: true)]
        }
        XCTAssertEqual(rig.mixer.nodeCount, 2)
        XCTAssertEqual(rig.engine.attachedNodes.count, base + 2)
        let out = try rig.run(0.2) { _ in [] }
        XCTAssertEqual(rig.mixer.nodeCount, 0)
        XCTAssertEqual(rig.engine.attachedNodes.count, base)
        XCTAssertEqual(rms(out.left, 4800..<out.left.count), 0)
        XCTAssertNil(rig.mixer.positionMs("voice:a"))
    }

    func testMutedCuesKeepPlayingSilently() async throws {
        let rig = try Rig()
        let url = try fixture("loop")
        _ = await rig.store.load(url)
        let out = try rig.run(0.5) { [self] t in [cue("music:0", url, at: t * 1000, gain: 0, loop: true)] }
        XCTAssertEqual(rms(out.left, 0..<out.left.count), 0)
        XCTAssertEqual(try XCTUnwrap(rig.mixer.positionMs("music:0")), 500, accuracy: 1)
    }
}
