@preconcurrency import AVFAudio
import AVFoundation
import CryptoKit

/// The episode's clips (Ogg Opus from the API) ready for `AVAudioPlayerNode`: downloaded once to a cache file, decoded
/// whole with `AVAudioFile` (AVAudioPlayer refuses these clips, AVAudioFile reads them) into float PCM at the engine's
/// rate (voices stay mono, beds and music stereo; more channels are mixed down to two), kept in a small in-memory LRU.
/// Signed URLs change when refreshed, not their content, so clips are keyed by the URL without its query. A clip that
/// cannot be downloaded or decoded is reported once through `onFailed` (the player then fetches fresh URLs) and is not
/// tried again for 30 s, unless its URL changes.
@MainActor
final class ClipStore {
    var onFailed: (String) -> Void = { _ in }
    let sampleRate: Double
    private let directory: URL
    private let memoryCap: Int
    private var clips: [String: AVAudioPCMBuffer] = [:]
    private var order: [String] = []
    private var bytes = 0
    private var loading: [String: Task<AVAudioPCMBuffer?, Never>] = [:]
    private var failedAt: [String: Date] = [:]
    private static let retryAfter: TimeInterval = 30

    /// `memoryCap`: decoded bytes kept (float PCM is about 375 KB a second in stereo at 48 kHz). The cache folder is
    /// trimmed to `diskCap` bytes, oldest first.
    init(
        directory: URL = ClipStore.defaultDirectory,
        sampleRate: Double = 48_000,
        memoryCap: Int = 96 << 20,
        diskCap: Int = 200 << 20
    ) {
        self.directory = directory
        self.sampleRate = sampleRate
        self.memoryCap = memoryCap
        Task.detached(priority: .utility) { Self.trim(directory, to: diskCap) }
    }

    nonisolated static var defaultDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("EpisodeAudio", isDirectory: true)
    }

    /// The decoded clip if it is in memory; otherwise starts loading it and returns nil (ask again on a later frame).
    func clip(_ url: String) -> AVAudioPCMBuffer? {
        let key = Self.key(url)
        if let buffer = clips[key] {
            order.removeAll { $0 == key }
            order.append(key)
            return buffer
        }
        _ = start(url)
        return nil
    }

    /// Warms a clip ahead of its cue (the next beat's voice line).
    func request(_ url: String) {
        _ = clip(url)
    }

    /// Loads a clip and waits for it (nil when it failed).
    func load(_ url: String) async -> AVAudioPCMBuffer? {
        if let buffer = clip(url) { return buffer }
        return await loading[Self.key(url)]?.value
    }

    private func start(_ url: String) -> Task<AVAudioPCMBuffer?, Never>? {
        let key = Self.key(url)
        if let task = loading[key] { return task }
        if let at = failedAt[url], Date().timeIntervalSince(at) < Self.retryAfter { return nil }
        guard let address = URL(string: url) else {
            fail(url)
            return nil
        }
        let directory = directory
        let rate = sampleRate
        let task = Task { @MainActor [weak self] () -> AVAudioPCMBuffer? in
            let result = await Task.detached(priority: .userInitiated) {
                try? await Self.fetchAndDecode(address, key: key, directory: directory, sampleRate: rate)
            }.value
            self?.finish(url, key: key, buffer: result)
            return result
        }
        loading[key] = task
        return task
    }

    private func finish(_ url: String, key: String, buffer: AVAudioPCMBuffer?) {
        loading.removeValue(forKey: key)
        guard let buffer else {
            fail(url)
            return
        }
        failedAt.removeValue(forKey: url)
        clips[key] = buffer
        order.removeAll { $0 == key }
        order.append(key)
        bytes += Self.size(buffer)
        // A clip still playing stays alive with its player node; the store only forgets it.
        while bytes > memoryCap, order.count > 1 {
            let oldest = order.removeFirst()
            if let gone = clips.removeValue(forKey: oldest) { bytes -= Self.size(gone) }
        }
    }

    private func fail(_ url: String) {
        failedAt[url] = Date()
        onFailed(url)
    }

    // MARK: Download and decoding (off the main thread)

    nonisolated static func key(_ url: String) -> String { url.components(separatedBy: "?").first ?? url }

    private nonisolated static func size(_ buffer: AVAudioPCMBuffer) -> Int {
        Int(buffer.frameLength) * Int(buffer.format.channelCount) * MemoryLayout<Float>.size
    }

    private nonisolated static func fetchAndDecode(_ url: URL, key: String, directory: URL, sampleRate: Double) async throws -> AVAudioPCMBuffer {
        let files = FileManager.default
        let file = directory.appendingPathComponent(cacheName(key, url))
        if files.fileExists(atPath: file.path) {
            if let buffer = try? decode(file, sampleRate: sampleRate) {
                try? files.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
                return buffer
            }
            try? files.removeItem(at: file)
        }
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        let (temporary, response) = try await URLSession.shared.download(from: url)
        defer { try? files.removeItem(at: temporary) }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { throw ClipError.http(http.statusCode) }
        try? files.removeItem(at: file)
        try files.moveItem(at: temporary, to: file)
        do {
            return try decode(file, sampleRate: sampleRate)
        } catch {
            try? files.removeItem(at: file)
            throw error
        }
    }

    /// The whole clip as non-interleaved float PCM at `sampleRate`, one or two channels.
    nonisolated static func decode(_ file: URL, sampleRate: Double) throws -> AVAudioPCMBuffer {
        let audio = try AVAudioFile(forReading: file, commonFormat: .pcmFormatFloat32, interleaved: false)
        let source = audio.processingFormat
        guard audio.length > 0, source.channelCount > 0,
              let raw = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: AVAudioFrameCount(audio.length))
        else { throw ClipError.empty }
        try audio.read(into: raw)
        guard raw.frameLength > 0 else { throw ClipError.empty }
        let channels = min(2, source.channelCount)
        if source.sampleRate == sampleRate, source.channelCount == channels { return raw }
        guard let target = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: channels),
              let converter = AVAudioConverter(from: source, to: target),
              let out = AVAudioPCMBuffer(
                  pcmFormat: target,
                  frameCapacity: AVAudioFrameCount(Double(raw.frameLength) * sampleRate / source.sampleRate) + 4096
              )
        else { throw ClipError.convert }
        converter.downmix = source.channelCount > channels
        var fed = false
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, inputStatus in
            if fed {
                inputStatus.pointee = .endOfStream
                return nil
            }
            fed = true
            inputStatus.pointee = .haveData
            return raw
        }
        if status == .error || out.frameLength == 0 { throw error ?? ClipError.convert }
        return out
    }

    /// `<sha256 of the URL without its query>.<extension>`: AVAudioFile picks the container from the extension.
    private nonisolated static func cacheName(_ key: String, _ url: URL) -> String {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        let ext = url.pathExtension.lowercased()
        let known = ["ogg", "opus", "oga", "m4a", "mp4", "aac", "mp3", "wav", "caf", "flac"]
        return "\(digest).\(known.contains(ext) ? ext : "ogg")"
    }

    private nonisolated static func trim(_ directory: URL, to cap: Int) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let names = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else { return }
        var files = names.compactMap { url -> (URL, Int, Date)? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
        }
        var total = files.reduce(0) { $0 + $1.1 }
        files.sort { $0.2 < $1.2 }
        for (url, size, _) in files where total > cap {
            try? FileManager.default.removeItem(at: url)
            total -= size
        }
    }

    enum ClipError: Error {
        case http(Int)
        case empty
        case convert
    }
}
