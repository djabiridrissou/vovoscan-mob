import CoreImage
import ImageIO
import Shared
import SwiftUI
import UIKit

/// Opens the full-screen player for a chapter; `nextChapterId` gives "Next chapter →" at the end.
struct PlayerRoute: Hashable, Identifiable {
    let chapterId: String
    let nextChapterId: String?
    /// "" = the original; else the language of the episode the card showed.
    var language: String = ""
    /// From the reader ("Watch from here"): the first beat of this page.
    var startPage: Int? = nil
    var id: String { "\(chapterId)~\(language)@\(startPage ?? -1)" }
}

/// The full-screen episode player: a 9:16 stage (letterboxed on other screens) redrawn at the display's rate by a
/// `TimelineView(.animation)` + `Canvas`, with the controls over it (they fade while playing). Each frame asks the
/// shared `PlayerViewModel.frame` what to draw and play; this view only draws it and hands the frame's audio cues to
/// `PlayerAudio` (AVAudioEngine, see `Audio/`).
struct PlayerView: View {
    @StateObject private var model: PlayerModel
    @StateObject private var stage = StageDriver()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var controlsShown = true
    @State private var touches = 0
    @State private var started = false
    @State private var scrub: Double?
    @State private var languagesOpen = false
    @State private var keysOpen = false
    /// The next chapter's id and the language now playing (it may have changed in the player).
    let onNextChapter: (String, String) -> Void

    init(route: PlayerRoute, onNextChapter: @escaping (String, String) -> Void) {
        _model = StateObject(wrappedValue: PlayerModel(chapterId: route.chapterId, nextChapterId: route.nextChapterId, language: route.language, startPage: route.startPage))
        self.onNextChapter = onNextChapter
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.state.ready {
                StageView(model: model, stage: stage, reduceMotion: reduceMotion, playing: model.state.playing)
            } else if model.state.loading {
                Text(EpisodeText.shared.PLAYER_LOADING).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    ErrorText(text: model.state.error ?? "")
                    Button(L("common.tryAgain")) { model.send(PlayerIntentRetry.shared) }.buttonStyle(PrimaryButtonStyle())
                    Button(L("common.close")) { dismiss() }.buttonStyle(GhostButtonStyle())
                }
                .padding(24)
            }
            if model.state.ready {
                if controlsShown || !model.state.playing {
                    controls.transition(.opacity)
                }
                if model.state.ended { endCard }
            }
            // Another language on its way: the episode plays on under the dock, and switches once it is ready.
            if let dock = model.state.languageDock {
                LanguageDock(
                    content: dock.content,
                    confirm: { model.send(PlayerIntentStartLanguage.shared) },
                    addKey: {
                        model.send(PlayerIntentPause.shared)
                        keysOpen = true
                    },
                    retry: { model.send(PlayerIntentRetryLanguage.shared) },
                    close: { model.send(PlayerIntentCloseLanguageDock.shared) }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: model.state.languageDock == nil)
        .sheet(isPresented: $languagesOpen) {
            EpisodeLanguageList(
                options: model.state.languageOptions,
                initialQuery: model.state.languageQuery,
                onQuery: { model.send(PlayerIntentLanguageQuery(text: $0)) },
                onChoose: { model.send(PlayerIntentChooseLanguage(code: $0)) }
            )
            .presentationDetents([.medium, .large])
            .presentationBackground(Color.brandInk2)
        }
        .sheet(isPresented: $keysOpen) {
            NavigationStack { KeysScreen() }
                .presentationBackground(Color.brandInk2)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.25)) { controlsShown.toggle() }
            touches += 1
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .task { await model.observe() }
        .task(id: "\(controlsShown)-\(model.state.playing)-\(touches)") {
            guard controlsShown, model.state.playing else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled { withAnimation(.easeInOut(duration: 0.25)) { controlsShown = false } }
        }
        .onAppear {
            let player = model
            stage.onFailed = { [weak player] url in player?.send(PlayerIntentMediaFailed(url: url)) }
            stage.audio.onPause = { [weak player] in player?.send(PlayerIntentPause.shared) }
            stage.audio.onResume = { [weak player] in player?.send(PlayerIntentPlay.shared) }
        }
        // Starts once, when the episode is loaded.
        .onChange(of: model.state.ready) { _, ready in
            if ready && !started {
                started = true
                model.send(PlayerIntentPlay.shared)
            }
        }
        // Warm what the next beat needs: its page image and its voice line.
        .onChange(of: model.state.beat) { _, beat in
            model.viewModel.plan?.prefetchUrls(beat: beat).forEach { url in
                if model.state.pageUrls.contains(url) { stage.images.request(url) } else { stage.audio.prefetch(url) }
            }
        }
        // The screen stays on while the episode plays; the sound follows the clock even when no frame comes.
        .onChange(of: model.state.playing) { _, playing in
            UIApplication.shared.isIdleTimerDisabled = playing
            stage.audio.setPlaying(playing)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.send(PlayerIntentPause.shared) }
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            stage.audio.close()
        }
    }

    private var controls: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                RoundButton(label: L("common.close"), action: { dismiss() }) {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .bold))
                }
                Text(model.state.pageLabel).font(.brandBody(13)).foregroundStyle(Color.brandPaper)
                Spacer()
                if model.state.offline { Text(L("common.offlineShort")).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                // Reader SPEC §11: dub or captions, one tap away while it plays.
                Pill(label: model.state.languageLabel, selected: !model.state.language.isEmpty) {
                    languagesOpen = true
                    touches += 1
                }
                .accessibilityLabel(L("translation.languageA11y", ["language": model.state.languageLabel]))
                Pill(label: model.state.soundLabel, selected: !model.state.muted && !model.state.silent) {
                    if !model.state.silent { model.send(PlayerIntentToggleMute.shared) }
                    touches += 1
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom))
            Spacer()
            HStack(spacing: 28) {
                RoundButton(label: L("episode.previousBubble"), enabled: model.state.canGoBack, action: {
                    model.send(PlayerIntentPreviousBeat.shared)
                    touches += 1
                }) { Image(systemName: "backward.end.fill").font(.system(size: 18)) }
                RoundButton(label: model.state.playLabel, large: true, action: {
                    model.send(PlayerIntentTogglePlay.shared)
                    touches += 1
                }) { Image(systemName: model.state.playing ? "pause.fill" : "play.fill").font(.system(size: 26)) }
                RoundButton(label: L("episode.nextBubble"), enabled: model.state.canGoForward, action: {
                    model.send(PlayerIntentNextBeat.shared)
                    touches += 1
                }) { Image(systemName: "forward.end.fill").font(.system(size: 18)) }
            }
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                Slider(
                    value: Binding(
                        get: { scrub ?? min(model.state.positionMs, max(1, model.state.durationMs)) },
                        set: { scrub = $0; touches += 1 }
                    ),
                    in: 0...max(1, model.state.durationMs),
                    onEditingChanged: { editing in
                        if !editing, let at = scrub {
                            model.send(PlayerIntentSeekTo(positionMs: at))
                            scrub = nil
                        }
                    }
                )
                .tint(Color.brandRed)
                HStack {
                    Text(model.state.timeLabel).font(.brandBody(13)).foregroundStyle(Color.brandPaper)
                    Spacer()
                    ForEach(model.state.speeds, id: \.label) { option in
                        Pill(label: option.label, selected: option.selected) {
                            model.send(PlayerIntentSetSpeed(speed: option.value))
                            touches += 1
                        }
                    }
                }
                if let hint = model.state.silentHint {
                    Text(hint).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
        }
    }

    private var endCard: some View {
        VStack(spacing: 14) {
            Text(model.state.endTitle).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            Button(model.state.replayLabel) { model.send(PlayerIntentReplay.shared) }.buttonStyle(PrimaryButtonStyle())
            if let label = model.state.nextLabel, let next = model.state.nextChapterId {
                Button(label) { onNextChapter(next, model.state.language) }.buttonStyle(GhostButtonStyle())
            }
            Button {
                languagesOpen = true
            } label: {
                Label(L("player.lang.title"), systemImage: "character.bubble")
            }
            .buttonStyle(GhostButtonStyle())
        }
        .padding(24)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        .padding(32)
    }
}

/// The 9:16 stage: the Canvas redrawn every display frame (12 a second while paused, enough to follow a seek), and
/// the caption over it (a SwiftUI text that changes only when the card does).
private struct StageView: View {
    @ObservedObject var model: PlayerModel
    @ObservedObject var stage: StageDriver
    let reduceMotion: Bool
    let playing: Bool

    var body: some View {
        GeometryReader { outer in
            let size = stageSize(in: outer.size)
            ZStack(alignment: .top) {
                TimelineView(.animation(minimumInterval: playing ? nil : 1.0 / 12, paused: false)) { timeline in
                    // The Canvas must read the timeline's date, or SwiftUI does not redraw it on each tick.
                    let tick = timeline.date
                    Canvas { context, canvasSize in
                        stage.draw(in: &context, size: canvasSize, at: tick, viewModel: model.viewModel, reduceMotion: reduceMotion)
                    }
                }
                if let caption = stage.caption {
                    (caption.italic ? Text(caption.text).italic() : Text(caption.text))
                        .font(.brandBody(caption.fontSize, weight: caption.bold ? .bold : .medium))
                        .foregroundStyle(Color.brandPaper)
                        .shadow(color: .black.opacity(0.8), radius: 3, y: 1)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .padding(.vertical, caption.padding)
                        .padding(.horizontal, caption.padding)
                        .background(caption.overArt ? Color.black.opacity(0.6) : Color.clear, in: RoundedRectangle(cornerRadius: caption.padding))
                        .frame(maxWidth: size.width * StageDriver.captionWidth)
                        .offset(y: caption.top)
                        .accessibilityHidden(true)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipped()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(stage.spoken)
            .position(x: outer.size.width / 2, y: outer.size.height / 2)
        }
        .ignoresSafeArea()
    }

    private func stageSize(in box: CGSize) -> CGSize {
        let aspect: CGFloat = 9.0 / 16.0
        if box.width / max(1, box.height) > aspect {
            return CGSize(width: box.height * aspect, height: box.height)
        }
        return CGSize(width: box.width, height: box.width / aspect)
    }
}

/// Asks the shared player for the frame, draws it and plays its sound: backdrop (blurred once per page, darkened), the
/// page clipped to the panel's outline, the bubble's discreet outline, the fading previous shot, the opening veil; the
/// audio cues go to `PlayerAudio`. Publishes the caption only when it changes.
@MainActor
final class StageDriver: ObservableObject {
    struct Caption: Equatable {
        let text: String
        let top: CGFloat
        let overArt: Bool
        let fontSize: CGFloat
        let padding: CGFloat
        let bold: Bool
        let italic: Bool
    }

    static let captionWidth: CGFloat = 0.86
    @Published private(set) var caption: Caption?
    @Published private(set) var spoken = ""
    let images = StageImages()
    let audio = PlayerAudio()
    var onFailed: (String) -> Void = { _ in } {
        didSet {
            images.onFailed = onFailed
            audio.onFailed = onFailed
        }
    }
    private var placements: [String: CaptionPlacement] = [:]

    func draw(in context: inout GraphicsContext, size: CGSize, at _: Date, viewModel: PlayerViewModel, reduceMotion: Bool) {
        guard size.width > 0, size.height > 0,
              let frame = viewModel.frame(
                  frameTimeMs: CACurrentMediaTime() * 1000,
                  stageWidth: Double(size.width),
                  stageHeight: Double(size.height),
                  reducedMotion: reduceMotion
              )
        else { return }
        images.request(frame.shot.pageUrl)
        if let ghost = frame.ghost { images.request(ghost.pageUrl) }
        let ui = viewModel.state.value
        audio.sync(frame.audio.map(CueState.init), playing: ui.playing, speed: ui.speed)
        drawShot(frame.shot, in: &context, size: size)
        if let ghost = frame.ghost {
            let alpha = max(0, min(1, 1 - frame.shot.framing.mix))
            if alpha > 0.001 {
                context.drawLayer { layer in
                    layer.opacity = alpha
                    drawShot(ghost, in: &layer, size: size)
                }
            }
        }
        if frame.veil > 0 {
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black.opacity(min(1, frame.veil))))
        }
        updateCaption(frame, size: size, viewModel: viewModel)
    }

    private func drawShot(_ shot: PageShot, in context: inout GraphicsContext, size: CGSize) {
        guard let page = images.peek(shot.pageUrl) else { return }
        let backdrop = shot.backdropTransform
        context.draw(Image(decorative: page.backdrop, scale: 1), in: rect(shot, backdrop))
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black.opacity(0.55)))
        let t = shot.transform
        let quad = shot.framing.shape
        var outline = Path()
        outline.move(to: point(quad.p0.x, quad.p0.y, t))
        outline.addLine(to: point(quad.p1.x, quad.p1.y, t))
        outline.addLine(to: point(quad.p2.x, quad.p2.y, t))
        outline.addLine(to: point(quad.p3.x, quad.p3.y, t))
        outline.closeSubpath()
        var clipped = context
        clipped.clip(to: outline)
        clipped.draw(Image(decorative: page.image, scale: 1), in: rect(shot, t))
        if let box = shot.bubbleBox {
            let r = CGRect(x: box.x * t.scale + t.translateX, y: box.y * t.scale + t.translateY, width: box.w * t.scale, height: box.h * t.scale)
            context.stroke(Path(roundedRect: r, cornerRadius: 8), with: .color(Color.brandYellow.opacity(0.55)), lineWidth: 1.5)
        }
    }

    private func rect(_ shot: PageShot, _ t: StageTransform) -> CGRect {
        CGRect(x: t.translateX, y: t.translateY, width: shot.pageWidth * t.scale, height: shot.pageHeight * t.scale)
    }

    private func point(_ x: Double, _ y: Double, _ t: StageTransform) -> CGPoint {
        CGPoint(x: x * t.scale + t.translateX, y: y * t.scale + t.translateY)
    }

    /// The caption block is measured with its first card (two lines at most, half the font size of padding above
    /// and below) and placed by the ViewModel; drawn only when the balloon is not legible in frame.
    private func updateCaption(_ frame: PlayerFrame, size: CGSize, viewModel: PlayerViewModel) {
        var next: Caption?
        if let c = frame.caption, c.needed, let first = c.cards.first {
            let fontSize = size.height * (c.small ? 0.024 : 0.028)
            let pad = fontSize * CGFloat(PlaybackPlan.companion.CAPTION_PAD)
            let key = "\(frame.beat):\(size.width):\(size.height)"
            let placement: CaptionPlacement
            if let known = placements[key] {
                placement = known
            } else {
                let block = Self.measure(first, fontSize: fontSize, bold: c.tone == "shout", maxWidth: size.width * Self.captionWidth - 2 * pad)
                placement = viewModel.captionPlacement(
                    beat: frame.beat,
                    stageWidth: Double(size.width),
                    stageHeight: Double(size.height),
                    blockHeight: Double(block.height + 2 * pad),
                    blockWidth: Double(block.width + 2 * pad)
                )
                placements[key] = placement
            }
            if placement.visible, !c.text.isEmpty {
                next = Caption(
                    text: c.text,
                    top: CGFloat(placement.top) * size.height,
                    overArt: placement.overArt,
                    fontSize: fontSize,
                    padding: pad,
                    bold: c.tone == "shout",
                    italic: c.tone == "whisper" || c.tone == "thought"
                )
            }
        }
        let spokenNow = frame.caption?.text ?? ""
        // Published after this draw pass: SwiftUI state must not change while a view renders.
        if next != caption || spokenNow != spoken {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if self.caption != next { self.caption = next }
                if self.spoken != spokenNow { self.spoken = spokenNow }
            }
        }
    }

    private static func measure(_ text: String, fontSize: CGFloat, bold: Bool, maxWidth: CGFloat) -> CGSize {
        let font = UIFont(name: "SpaceGrotesk-Regular", size: fontSize) ?? UIFont.systemFont(ofSize: fontSize, weight: bold ? .bold : .medium)
        let bounds = (text as NSString).boundingRect(
            with: CGSize(width: maxWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font],
            context: nil
        )
        return CGSize(width: ceil(bounds.width), height: ceil(min(bounds.height, font.lineHeight * 2)))
    }
}

/// Page images as the stage draws them: decoded once (ImageIO, at most 2400 px) and a backdrop blurred once per page
/// with Core Image (a 96 px copy, Gaussian blur): nothing is blurred per frame. Signed URLs change when refreshed, not
/// their content, so pages are keyed by the URL without its query. A few pages are kept.
@MainActor
final class StageImages {
    struct Page {
        let image: CGImage
        let backdrop: CGImage
    }

    var onFailed: (String) -> Void = { _ in }
    private var pages: [String: Page] = [:]
    private var order: [String] = []
    private var loading: Set<String> = []
    private static let keep = 4

    func peek(_ url: String?) -> Page? {
        guard let url else { return nil }
        return pages[Self.key(url)]
    }

    func request(_ url: String?) {
        guard let url, let address = URL(string: url) else { return }
        let key = Self.key(url)
        if pages[key] != nil || loading.contains(key) { return }
        loading.insert(key)
        Task.detached(priority: .userInitiated) {
            let page = await Self.load(address)
            await MainActor.run {
                self.loading.remove(key)
                guard let page else {
                    self.onFailed(url)
                    return
                }
                self.pages[key] = page
                self.order.removeAll { $0 == key }
                self.order.append(key)
                while self.order.count > Self.keep { self.pages.removeValue(forKey: self.order.removeFirst()) }
            }
        }
    }

    private nonisolated static func key(_ url: String) -> String { url.components(separatedBy: "?").first ?? url }

    private nonisolated static func load(_ url: URL) async -> Page? {
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = thumbnail(source, 2400),
              let small = thumbnail(source, 96)
        else { return nil }
        let input = CIImage(cgImage: small)
        let blurred = input.clampedToExtent().applyingGaussianBlur(sigma: 4).cropped(to: input.extent)
        guard let backdrop = blurContext.createCGImage(blurred, from: input.extent) else { return nil }
        return Page(image: image, backdrop: backdrop)
    }

    private nonisolated static func thumbnail(_ source: CGImageSource, _ side: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: side,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

extension CueState {
    init(_ cue: AudioCue) {
        self.init(id: cue.id, url: cue.url, offsetMs: cue.offsetMs, gain: cue.gain, pan: cue.pan, loop: cue.loop)
    }
}

/// Blurs the backdrops (one small image per page).
private let blurContext = CIContext()

private struct RoundButton<Glyph: View>: View {
    let label: String
    var enabled = true
    var large = false
    let action: () -> Void
    @ViewBuilder let glyph: () -> Glyph

    var body: some View {
        Button(action: action) {
            glyph()
                .foregroundStyle(large ? Color.brandInk : Color.brandPaper)
                .frame(width: large ? 72 : 48, height: large ? 72 : 48)
                .background(large ? Color.brandRed : Color.black.opacity(0.45), in: Circle())
                .overlay(Circle().stroke(large ? Color.brandInk : Color.brandLine, lineWidth: large ? 2 : 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

private struct Pill: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.brandBody(13))
                .foregroundStyle(selected ? Color.brandInk : Color.brandPaper)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(selected ? Color.brandPaper : Color.black.opacity(0.4), in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.brandPaper : Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

