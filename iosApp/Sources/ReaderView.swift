import ImageIO
import Shared
import SwiftUI
import UIKit
import WebKit

/// Opens a chapter; "next chapter" replaces the reader in place (no endless stacking).
struct ReaderRoute: Hashable {
    let sourceKey: String
    let titleId: String
    let chapterId: String
    let startPage: Int
    /// A source's chapter reopened on its translated copy (our catalog) shows this language at once.
    var language: String? = nil
    /// Opened from a chapter's "Translate": the language picker shows at once.
    var pickLanguage = false
}

struct ReaderContainer: View {
    @State var route: ReaderRoute

    var body: some View {
        ReaderView(route: route) { chapterId, startPage in
            route = ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: chapterId, startPage: startPage)
        } onOpenCopy: { copy in
            route = copy
        }
        .id(route)
    }
}

/// Reader. Comics: paged (right to left, left to right, vertical) or a long strip (with or without gaps), tap zones
/// (a single press pages or scrolls at once), chapter cards before the first and after the last page, a settings
/// sheet. No logic here: the view measures, draws, animates and reports taps; `ReaderViewModel` (shared Kotlin)
/// decides. `onOpenChapter(chapterId, startPage)` replaces this reader.
struct ReaderView: View {
    @StateObject private var model: ReaderModel
    @StateObject private var translation: TranslationModel
    @StateObject private var next: NextChapterModel
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss
    /// The tab's path: ← and Escape pop it (the reader is always pushed on one; `dismiss` only as a fallback).
    @Environment(\.pop) private var pop
    @State private var settingsOpen = false
    @State private var languagesOpen = false
    /// The push that opened this reader has finished (a sheet waits for it), and the "Translate" picker was shown once.
    @State private var pushSettled = false
    @State private var pickerShown = false
    @State private var keysOpen = false
    /// The bottom bar's height: the language dock sits above it while the bars show.
    @State private var bottomBarHeight: CGFloat = 0
    @State private var player: PlayerRoute?
    /// "Watch from here" on a source's chapter: its card first (pages to the server, the cost, then Prepare).
    @State private var sourceEpisode: SourceEpisode?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// A hardware keyboard (iPad, Magic Keyboard): arrows, space, page keys and Escape reach the reader.
    @FocusState private var keysFocused: Bool
    private let route: ReaderRoute
    let onOpenChapter: (String, Int) -> Void
    /// A source's chapter, translated: its copy on the server replaces this reader, in that language, at the same share.
    let onOpenCopy: (ReaderRoute) -> Void

    init(route: ReaderRoute, onOpenChapter: @escaping (String, Int) -> Void, onOpenCopy: @escaping (ReaderRoute) -> Void = { _ in }) {
        self.route = route
        _model = StateObject(wrappedValue: ReaderModel(route: route))
        _translation = StateObject(wrappedValue: TranslationModel(route: route))
        _next = StateObject(wrappedValue: NextChapterModel(route: route))
        self.onOpenChapter = onOpenChapter
        self.onOpenCopy = onOpenCopy
    }

    // The body is split into typed parts: as one expression it was more than the type checker would take.
    var body: some View {
        withSheets(withObservers(layers))
    }

    /// Every layer of the reader, back to front (the order of the ZStack is the order on screen).
    private var layers: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            content
            floating
            overlays
        }
    }

    /// The chapter itself: a book, a comic, or why it isn't here.
    @ViewBuilder private var content: some View {
        if model.state.unavailable {
            unavailableNotice
        } else if model.state.isBook {
            BookReader(model: model) { onOpenChapter($0, 0) }
        } else if !model.state.items.isEmpty {
            comicReader
        }
    }

    private var unavailableNotice: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("reader.notSaved")).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
            if let error = model.state.error { ErrorText(text: error) }
            Button(L("common.tryAgain")) { model.send(ReaderIntentRetry.shared) }.buttonStyle(GhostButtonStyle())
        }
        .padding(24)
    }

    /// The reader's language: each page draws its translated lettering, if any.
    private var comicReader: some View {
        ComicReader(model: model)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(model.state.chapterLabel)
            .accessibilityHint(model.state.tapsHint)
            .environment(\.pageLettering, pageLettering)
            .environment(\.readerLanguageAction, languageAction)
            .environment(\.readerNextChapter, nextChapter)
    }

    private var pageLettering: (Int) -> [Lettering] {
        let state: TranslationUiState = translation.state
        return { index in state.letteringFor(index: Int32(index)) }
    }

    private var languageAction: ReaderLanguageAction? {
        guard translation.state.available else { return nil }
        return ReaderLanguageAction(label: translation.state.endCardLabel) { languagesOpen = true }
    }

    private var nextChapter: ReaderNextChapter? {
        guard let end: NextEndCard = next.state.endCard else { return nil }
        let model: NextChapterModel = next
        return ReaderNextChapter(
            end: end,
            auto: next.state.autoSwitch,
            tap: nextAction(NextChapterIntentPrepareNext.shared),
            setAuto: { (on: Bool) in model.send(NextChapterIntentSetAuto(on: on)) }
        )
    }

    /// Over the page: the language dock, the next-chapter bubble and the "ready" toast.
    @ViewBuilder private var floating: some View {
        if let dock = translation.state.dock, !model.state.isBook {
            languageDock(dock)
        }
        if let bubble = next.state.bubble, translation.state.dock == nil, !model.state.isBook {
            nextBubble(bubble)
        }
        if let toast = next.state.toast {
            nextToast(toast)
        }
    }

    /// A language on its way: the page keeps its lettering, the dock shows the cost, then the progress.
    private func languageDock(_ dock: TranslationDock) -> some View {
        LanguageDock(
            content: dock.content,
            confirm: { translation.send(TranslationIntentConfirmOffer.shared) },
            addKey: { keysOpen = true },
            retry: { translation.send(TranslationIntentRetry.shared) },
            close: { translation.send(TranslationIntentCloseDock.shared) }
        )
        // Above the page counter and the chapter buttons while they show: its buttons are never under the bar.
        .padding(.bottom, model.state.chromeVisible || model.state.isBook ? bottomBarHeight : 0)
        .zIndex(0.5)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// Past 40 % in a language: the next chapter, then the next 5, then 10, prepared while reading.
    private func nextBubble(_ bubble: NextBubble) -> some View {
        NextBubbleView(
            bubble: bubble,
            tap: nextAction(NextChapterIntentAcceptOffer.shared),
            cancel: { next.send(NextChapterIntentCancel.shared) },
            hide: { next.send(NextChapterIntentHideBubble.shared) }
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .padding(.bottom, model.state.chromeVisible ? bottomBarHeight : 0)
        .zIndex(0.5)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// A chapter prepared ahead is ready.
    private func nextToast(_ toast: NextToast) -> some View {
        NextToastView(toast: toast, tap: nextAction(NextChapterIntentPrepareNext.shared)) {
            next.send(NextChapterIntentDismissToast.shared)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, model.state.chromeVisible ? 64 : 8)
        .zIndex(0.6)
        .transition(.opacity)
    }

    /// The tap zones, the progress line, the title card, the bars, then the coach on top of everything.
    @ViewBuilder private var overlays: some View {
        if let overlay = model.state.tapOverlay {
            TapZonesOverlay(overlay: overlay).transition(.opacity)
        }
        // While the bars are away: a 2 pt line of the share read, at the very top.
        if !model.state.chromeVisible && !model.state.isBook { progressLine }
        if let card = model.state.titleCard { titleCard(card) }
        // A book keeps its bars: the web view receives the taps (text scrolling).
        // Over the dock, the bubble and the toast (their full-height frames), under the coach.
        if model.state.chromeVisible || model.state.isBook { chrome.transition(.opacity).zIndex(0.9) }
        // How to read, the first time a chapter of its kind opens: any tap closes it.
        if let coach = model.state.coach {
            ReaderCoachOverlay(coach: coach) { model.send(ReaderIntentDismissCoach.shared) }
                .transition(.opacity)
                .zIndex(1)
        }
    }

    /// Animations, keys, the models' streams and what the screen answers to their commands.
    private func withObservers(_ base: some View) -> some View {
        let reduced: Bool = model.state.reducedMotion
        return base
            .animation(.easeInOut(duration: 0.2), value: model.state.tapOverlay == nil)
            .animation(reduced ? nil : .easeOut(duration: 0.25), value: model.state.coach == nil)
            .animation(reduced ? nil : .easeInOut(duration: 0.25), value: model.state.chromeVisible)
            .animation(reduced ? nil : .easeInOut(duration: 0.4), value: model.state.titleCard)
            .toolbar(.hidden, for: .navigationBar)
            .toolbar(.hidden, for: .tabBar)
            .statusBarHidden(!model.state.chromeVisible)
            .focusable()
            .focused($keysFocused)
            .focusEffectDisabled()
            .onAppear { keysFocused = true }
            .onKeyPress(phases: .down) { (press: KeyPress) -> KeyPress.Result in
                guard let key = ReaderKeyMap.key(press) else { return .ignored }
                model.send(ReaderIntentKey(key: key))
                return .handled
            }
            .task { await model.observe() }
            .task { await model.observeFollow() }
            .task { await translation.observe() }
            .task { await next.observe() }
            .onChange(of: translation.state.shown, initial: true) { _, shown in next.send(NextChapterIntentLanguageShown(code: shown)) }
            .onChange(of: model.state.readShare, initial: true) { _, share in next.send(NextChapterIntentReached(share: share)) }
            .onChange(of: model.state.onEndCard, initial: true) { _, end in next.send(NextChapterIntentAtEnd(atEnd: end)) }
            .animation(reduced ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: next.state.bubble == nil)
            .animation(reduced ? nil : .easeInOut(duration: 0.25), value: next.state.toast == nil)
            .animation(.easeInOut(duration: 0.25), value: translation.state.translated)
            .animation(reduced ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: translation.state.dock == nil)
            // Opening another chapter (a chapter card, or a tap past the end), whatever the mode.
            .onChange(of: model.state.command?.seq) { _, _ in handleCommand() }
            .onChange(of: translation.state.openCopy) { _, copy in openCopy(copy) }
            // Reduce Motion: jumps instead of the spring, no spotlight glide, no fades.
            .onChange(of: reduceMotion, initial: true) { _, on in model.send(ReaderIntentReducedMotion(on: on)) }
            // VoiceOver: "Page 5 of 19" when the page changes, never on every frame.
            .onChange(of: model.state.pageAnnouncement) { _, text in
                if UIAccessibility.isVoiceOverRunning && !text.isEmpty {
                    UIAccessibility.post(notification: .announcement, argument: text)
                }
            }
            // Keep the screen awake while reading (setting, on by default).
            .onChange(of: model.state.keepScreenOn, initial: true) { _, on in UIApplication.shared.isIdleTimerDisabled = on }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
            // Opened from a chapter's "Translate": the picker once the push has settled and the languages are known. A
            // sheet presented during the push transition left an invisible layer over the app (no tap, no Back).
            .task {
                try? await Task.sleep(for: .milliseconds(600))
                pushSettled = true
            }
            .onChange(of: pushSettled && translation.state.available) { _, ready in
                if ready && route.pickLanguage && !pickerShown {
                    pickerShown = true
                    languagesOpen = true
                }
            }
    }

    /// The player, a source's episode card, the language picker, the keys and the settings, over the reader.
    private func withSheets(_ base: some View) -> some View {
        base
            .fullScreenCover(item: $player) { (route: PlayerRoute) in PlayerView(route: route) { _, _ in player = nil } }
            .sheet(item: $sourceEpisode) { (episode: SourceEpisode) in SourceEpisodeSheet(episode: episode) }
            .sheet(isPresented: $languagesOpen) {
                LanguageSheet(translation: translation)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(Color.brandInk2)
            }
            // "Add a key" from the dock: the keys over the reader, which stays where it was.
            .sheet(isPresented: $keysOpen) {
                NavigationStack { KeysScreen() }
                    .presentationBackground(Color.brandInk2)
            }
            .sheet(isPresented: $settingsOpen, onDismiss: { model.send(ReaderIntentSettingsClosed.shared) }) {
                ReaderSettingsSheetView(model: model, next: next)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(Color.brandInk2)
            }
    }

    /// A command of the shared reader that leaves this screen: another chapter, back, or the episode.
    private func handleCommand() {
        let command: ReaderCommand? = model.state.command
        if let open = command as? ReaderCommandOpenChapter {
            model.send(ReaderIntentCommandHandled(seq: open.seq))
            onOpenChapter(open.chapterId, Int(open.startPage))
        }
        if let back = command as? ReaderCommandClose {
            model.send(ReaderIntentCommandHandled(seq: back.seq))
            close()
        }
        // "Watch from here": the episode from the first beat of the page being read.
        if let episode = command as? ReaderCommandOpenEpisode {
            model.send(ReaderIntentCommandHandled(seq: episode.seq))
            if route.sourceKey == "vovoscan" {
                player = PlayerRoute(chapterId: episode.chapterId, nextChapterId: nil, startPage: Int(episode.page))
            } else {
                sourceEpisode = SourceEpisode(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: episode.chapterId)
            }
        }
    }

    /// A source's chapter, translated: its copy on the server replaces this reader, in that language, at the same share.
    private func openCopy(_ copy: TranslatedCopy?) {
        guard let copy else { return }
        translation.send(TranslationIntentCopyOpened.shared)
        let start: Int32 = copy.startPage(page: model.state.currentIndex, pages: Int32(model.state.pages.count))
        onOpenCopy(
            ReaderRoute(sourceKey: "vovoscan", titleId: copy.titleId, chapterId: copy.chapterId, startPage: Int(start), language: copy.language)
        )
    }

    /// A button of the next-chapter UI: open a chapter (in the language when it is translated), prepare, the keys, a link.
    private func nextAction(_ prepare: NextChapterIntent) -> (NextButton) -> Void {
        { button in
            switch button.kind {
            case .open:
                guard let id = button.chapterId else { return }
                if let language = button.language {
                    onOpenCopy(ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: id, startPage: 0, language: language))
                } else {
                    onOpenChapter(id, 0)
                }
            case .prepare: next.send(prepare)
            case .addKey: keysOpen = true
            case .openUrl: if let url = button.url.flatMap(URL.init(string:)) { openURL(url) }
            }
        }
    }

    private var progressLine: some View {
        GeometryReader { area in
            Rectangle()
                .fill(Color.brandRed)
                .frame(width: area.size.width * CGFloat(model.state.progress), height: 2)
        }
        .frame(height: 2)
        .frame(maxHeight: .infinity, alignment: .top)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityLabel(model.state.progressLabel)
    }

    /// "Series ✦ Chapter 3 · name" over the first page for a moment.
    private func titleCard(_ text: String) -> some View {
        Text(text)
            .font(.brandDisplay(20))
            .foregroundStyle(Color.brandPaper)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(Color.brandInk.opacity(0.85), in: RoundedRectangle(cornerRadius: 12))
            .padding(24)
            .allowsHitTesting(false)
            .transition(.opacity)
    }

    /// Back to the screen under the reader: the tab's path, else the presentation.
    private func close() {
        if let pop { pop() } else { dismiss() }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                // A full 44 pt target: a tap just off the glyph used to reach the page's tap zones (and turn chapters).
                Button { close() } label: {
                    Text("←").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .padding(.leading, -12)
                .accessibilityLabel(L("common.back"))
                .accessibilityIdentifier("reader.back")
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.state.titleName).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    Text(model.state.chapterLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        .accessibilityIdentifier("reader.chapter")
                }
                Spacer()
                if model.state.voiceAvailable {
                    Button { model.send(ReaderIntentReadWithVoice.shared) } label: {
                        Image(systemName: "play.fill").font(.system(size: 16)).foregroundStyle(Color.brandInk)
                            .frame(width: 34, height: 34)
                            .background(Color.brandRed, in: Circle())
                    }
                    .accessibilityLabel(model.state.voiceLabel)
                }
                if !model.state.isBook && translation.state.available {
                    LanguageButton(translation: translation) { languagesOpen = true }
                }
                Button { model.send(ReaderIntentShowCoach.shared) } label: {
                    Image(systemName: "questionmark.circle").font(.system(size: 18)).foregroundStyle(Color.brandMuted)
                }
                .accessibilityLabel(L("reader.coach.show"))
                if !model.state.isBook {
                    Button {
                        model.send(ReaderIntentSettingsOpened.shared)
                        settingsOpen = true
                    } label: {
                        Image(systemName: "gearshape").font(.system(size: 18)).foregroundStyle(Color.brandPaper)
                    }
                    .accessibilityLabel(L("reader.settingsTitle"))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.brandInk.opacity(0.92))
            Spacer()
            HStack(spacing: 20) {
                if model.state.isBook {
                    Button { model.send(ReaderIntentPrevious.shared) } label: {
                        Text("←").font(.brandDisplay(18)).foregroundStyle(model.state.canGoBack ? Color.brandPaper : Color.brandLine)
                    }
                    .disabled(!model.state.canGoBack)
                }
                Text(model.state.positionLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if model.state.canFocus {
                    Button { model.send(ReaderIntentToggleFocus.shared) } label: {
                        Image(systemName: model.state.focusOn ? "viewfinder.circle.fill" : "viewfinder.circle")
                            .font(.system(size: 20))
                            .foregroundStyle(model.state.focusOn ? Color.brandYellow : Color.brandPaper)
                    }
                    .accessibilityLabel(model.state.focusLabel)
                    .accessibilityHint(model.state.focusHelp)
                    .accessibilityAddTraits(model.state.focusOn ? .isSelected : [])
                }
                Spacer()
                if model.state.isBook && model.state.canGoForward {
                    Button(L("reader.nextSection")) { model.send(ReaderIntentNext.shared) }
                        .font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                }
                // Chapters, not pages (pages turn with taps and swipes); right to left puts the next one on the left.
                ForEach(model.state.chapterButtons, id: \.chapterId) { button in
                    Button(button.label) { onOpenChapter(button.chapterId, 0) }
                        .font(.brandBody(button.forward ? 16 : 13, weight: button.forward ? .bold : .regular))
                        .foregroundStyle(button.forward ? Color.brandRed : Color.brandMuted)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.brandInk.opacity(0.92))
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bottomBarHeight = $0 }
        }
    }
}

/// A key press as the shared reader names it (directions are decided there, from the reading mode).
enum ReaderKeyMap {
    static func key(_ press: KeyPress) -> ReaderKey? {
        switch press.key {
        case .leftArrow: return .left
        case .rightArrow: return .right
        case .upArrow: return .up
        case .downArrow: return .down
        case .pageUp: return .pageUp
        case .pageDown: return .pageDown
        case .escape: return .escape
        case .space: return press.modifiers.contains(.shift) ? .shiftSpace : .space
        default: return press.characters == "?" ? .help : nil
        }
    }
}

/// Comic: the mode's layout under one tap gesture. A drag or fling is the scroll view's, never a tap. Double tap to
/// zoom (off by default) makes single taps wait for a possible second tap.
private struct ComicReader: View {
    @ObservedObject var model: ReaderModel
    @State private var zoomed = false
    @State private var pan: CGSize = .zero
    @State private var panStart: CGSize = .zero
    private let zoom: CGFloat = 2

    var body: some View {
        GeometryReader { area in
            let size = area.size
            Group {
                if model.state.isPaged {
                    PagedReader(model: model, size: size)
                } else {
                    FlowStrip(model: model)
                }
            }
            .scrollDisabled(zoomed)
            .scaleEffect(zoomed ? zoom : 1, anchor: .topLeading)
            .offset(zoomed ? pan : .zero)
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .clipped()
            .contentShape(Rectangle())
            .gesture(taps(size: size))
            .gesture(
                DragGesture()
                    .onChanged { value in
                        pan = clamp(CGSize(width: panStart.width + value.translation.width, height: panStart.height + value.translation.height), size)
                    }
                    .onEnded { _ in panStart = pan },
                including: zoomed ? .all : .subviews
            )
            .animation(.easeOut(duration: 0.2), value: zoomed)
        }
        .ignoresSafeArea()
        // A new page or mode starts unzoomed.
        .onChange(of: model.state.isPaged ? model.state.currentItem : -1) { _, _ in zoomed = false }
        .onChange(of: model.state.mode) { _, _ in zoomed = false }
        .onChange(of: model.state.doubleTapZoom) { _, on in if !on { zoomed = false } }
    }

    /// Single tap at once; with double tap to zoom on, the single tap waits for a possible second one.
    private func taps(size: CGSize) -> AnyGesture<Void> {
        let single = SpatialTapGesture(count: 1).onEnded { value in
            guard size.width > 0, size.height > 0 else { return }
            model.send(ReaderIntentTap(x: Float(value.location.x / size.width), y: Float(value.location.y / size.height)))
        }
        guard model.state.doubleTapZoom else { return AnyGesture(single.map { _ in () }) }
        let double = SpatialTapGesture(count: 2).onEnded { value in
            if zoomed {
                zoomed = false
            } else {
                // Zoom in around the finger: that point stays where it is.
                pan = clamp(CGSize(width: value.location.x * (1 - zoom), height: value.location.y * (1 - zoom)), size)
                panStart = pan
                zoomed = true
            }
        }
        return AnyGesture(double.exclusively(before: single).map { _ in () })
    }

    private func clamp(_ offset: CGSize, _ size: CGSize) -> CGSize {
        CGSize(
            width: min(0, max(size.width * (1 - zoom), offset.width)),
            height: min(0, max(size.height * (1 - zoom), offset.height))
        )
    }
}

/// One page at a time. Horizontal (right to left: mirrored layout): a paging scroll view; a tall page (`TallPages`)
/// fits the width and scrolls vertically inside its page. Vertical: see `VerticalPages`.
private struct PagedReader: View {
    @ObservedObject var model: ReaderModel
    let size: CGSize
    @StateObject private var ratios = PageRatios()

    var body: some View {
        if model.state.pagedHorizontally {
            HorizontalPages(model: model, size: size, ratios: ratios)
        } else {
            VerticalPages(model: model, size: size, ratios: ratios)
        }
    }
}

/// The width / height of pages whose size the source didn't give, once their image has loaded (by URL).
@MainActor
private final class PageRatios: ObservableObject {
    @Published private(set) var byUrl: [String: CGFloat] = [:]

    func set(_ url: String, _ ratio: CGFloat) {
        if byUrl[url] != ratio { byUrl[url] = ratio }
    }

    /// The page's ratio: its own size, else its image's once loaded, else unknown.
    func ratio(_ page: ReaderPage) -> CGFloat? {
        page.hasSize ? CGFloat(page.aspectRatio) : byUrl[page.url]
    }

    /// Shown fit to width and scrolled inside its page (shared rule).
    func isTall(_ page: ReaderPage, in size: CGSize) -> Bool {
        guard let ratio = ratio(page) else { return false }
        return TallPages.shared.fitsWidth(pageRatio: Float(ratio), areaWidth: Float(size.width), areaHeight: Float(size.height))
    }
}

/// Horizontal pages: a paging scroll view. A tall page scrolls vertically inside its page (another axis: no conflict
/// with the paging).
private struct HorizontalPages: View {
    @ObservedObject var model: ReaderModel
    let size: CGSize
    @ObservedObject var ratios: PageRatios
    @State private var position: Int?

    init(model: ReaderModel, size: CGSize, ratios: PageRatios) {
        self.model = model
        self.size = size
        self.ratios = ratios
        _position = State(initialValue: Int(model.state.currentSpread))
    }

    var body: some View {
        let spreads = model.state.spreads
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(spreads.enumerated()), id: \.offset) { screen, spread in
                    Group {
                        if let second = spread.second?.intValue {
                            // Two facing pages, against the spine: the first on the right in a manga.
                            let first = Int(spread.first)
                            let (left, right) = model.state.rightToLeft ? (second, first) : (first, second)
                            HStack(spacing: 0) {
                                item(left, alignment: .trailing).frame(width: size.width / 2)
                                item(right, alignment: .leading).frame(width: size.width / 2)
                            }
                        } else {
                            item(Int(spread.first), alignment: .center)
                        }
                    }
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(width: size.width, height: size.height)
                    .id(screen)
                }
            }
            .scrollTargetLayout()
        }
        // Right to left: the first page is on the right, swiping right moves forward (manga direction).
        .environment(\.layoutDirection, model.state.rightToLeft ? .rightToLeft : .leftToRight)
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $position)
        .id("\(model.state.mode)-\(model.state.twoPages)")
        .onChange(of: position) { _, screen in
            if let screen, let spread = model.state.spreads[safe: screen] {
                model.send(ReaderIntentItemVisible(item: spread.first))
            }
        }
        // Turned sideways or back, a window resized: one or two pages per screen, the same page kept.
        .onChange(of: size, initial: true) { _, size in
            model.send(ReaderIntentViewport(width: Float(size.width), height: Float(size.height), verticalHinge: false))
        }
        .onChange(of: model.state.twoPages) { _, _ in position = Int(model.state.currentSpread) }
        .onChange(of: model.state.command?.seq) { _, _ in
            guard let go = model.state.command as? ReaderCommandGoToItem else { return }
            model.send(ReaderIntentCommandHandled(seq: go.seq))
            let screen = Int(model.state.spreadOf(item: go.item))
            if go.animated {
                withAnimation(.easeInOut(duration: 0.25)) { position = screen }
            } else {
                position = screen
            }
        }
    }

    /// One item of a screen: a page (a tall one scrolls inside itself, alone on its screen), or a chapter card.
    @ViewBuilder
    private func item(_ index: Int, alignment: Alignment) -> some View {
        if let page = model.state.items[safe: index] as? ReaderItemPage {
            if alignment == .center && ratios.isTall(page.page, in: size) {
                TallPageView(model: model, page: page.page, index: index, size: size)
            } else {
                PageImage(page: page.page, fit: true, alignment: alignment, onLoaded: { ratios.set(page.page.url, $0) })
            }
        } else if let transition = model.state.items[safe: index] as? ReaderItemTransition {
            TransitionCardView(card: transition.card, model: model)
        }
    }
}

/// A tall page of the horizontal modes: fit to width, scrolled vertically inside its page. The current one reports its
/// scroll and runs the tap scrolls; pages before the current one wait at their bottom, pages after it at their top.
private struct TallPageView: View {
    @ObservedObject var model: ReaderModel
    let page: ReaderPage
    let index: Int
    let size: CGSize
    @State private var position = ScrollPosition(edge: .top)
    @State private var geometry = StripGeometry()
    @State private var tapTarget: (y: CGFloat, at: Date)?

    private var current: Bool { Int(model.state.currentItem) == index }

    var body: some View {
        ScrollView(.vertical) {
            PageImage(page: page)
        }
        .scrollIndicators(.hidden)
        .scrollPosition($position)
        .onScrollGeometryChange(for: StripGeometry.self) { g in
            StripGeometry(offset: g.contentOffset.y, viewport: g.containerSize.height, content: g.contentSize.height)
        } action: { _, new in
            geometry = new
            report()
        }
        .onAppear {
            if index < Int(model.state.currentItem) { position.scrollTo(edge: .bottom) }
            report()
        }
        .onChange(of: model.state.currentItem) { _, item in
            if index < Int(item) { position.scrollTo(edge: .bottom) }
            if index > Int(item) { position.scrollTo(edge: .top) }
            report()
        }
        .onChange(of: model.state.command?.seq) { _, _ in
            if let go = model.state.command as? ReaderCommandGoToItem, Int(go.item) == index, go.atBottom {
                position.scrollTo(edge: .bottom)
            }
            guard current, let by = model.state.command as? ReaderCommandScrollBy else { return }
            model.send(ReaderIntentCommandHandled(seq: by.seq))
            let now = Date()
            let from = tapTarget.flatMap { now.timeIntervalSince($0.at) < 0.35 ? $0.y : nil } ?? geometry.offset
            let target = min(max(0, from + CGFloat(by.fraction) * geometry.viewport), geometry.maxOffset)
            tapTarget = (target, now)
            withAnimation(.easeInOut(duration: 0.3)) { position.scrollTo(y: target) }
        }
    }

    private func report() {
        guard current, geometry.viewport > 0 else { return }
        model.send(ReaderIntentPageScroll(
            item: Int32(index),
            tall: true,
            atTop: geometry.offset <= 1,
            atBottom: geometry.offset >= geometry.maxOffset - 1
        ))
    }
}

/// Vertical pages: one scroll view in which a tall page takes its full fit-to-width height and the next page starts
/// after its bottom (UIKit does not chain two vertical scroll views, so a page's own scroll view would never hand the
/// swipe over to the pager). Where a drag comes to rest is the shared rule (`TallPages.restingOffset`): inside a tall
/// page the scroll stays in it, past its edge it is a page turn, as a pager's.
private struct VerticalPages: View {
    @ObservedObject var model: ReaderModel
    let size: CGSize
    @ObservedObject var ratios: PageRatios
    @State private var scroll = ScrollPosition(idType: Int.self)
    @State private var layout = PagedLayout()
    @State private var tapTarget: (y: CGFloat, at: Date)?
    @State private var reported: ReaderIntentPageScroll?
    /// Pages on each side of the current one that keep their image.
    private static let window = 2

    var body: some View {
        let items = model.state.items
        let current = Int(model.state.currentItem)
        ScrollView(.vertical) {
            // Not lazy: a lazy stack estimates the place of what it hasn't laid out yet, and a tall page makes that
            // estimate wrong (a scroll to the next page then stopped short, or never moved). Every item is laid out
            // at its size; only the pages near the current one hold their image.
            VStack(spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    Group {
                        if let page = item as? ReaderItemPage {
                            let active = abs(index - current) <= Self.window
                            if ratios.isTall(page.page, in: size), let ratio = ratios.ratio(page.page) {
                                PageImage(page: page.page, active: active).frame(width: size.width, height: size.width / ratio)
                            } else {
                                PageImage(page: page.page, fit: true, active: active, onLoaded: { ratios.set(page.page.url, $0) })
                                    .frame(width: size.width, height: size.height)
                            }
                        } else if let transition = item as? ReaderItemTransition {
                            TransitionCardView(card: transition.card, model: model).frame(width: size.width, height: size.height)
                        }
                    }
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(PagedLayout.space)) } action: { frame in
                        measured(index, frame)
                    }
                    .id(index)
                }
            }
            .coordinateSpace(.named(PagedLayout.space))
            .scrollTargetLayout()
        }
        .scrollIndicators(.hidden)
        .scrollPosition($scroll)
        .scrollTargetBehavior(TallPaging(layout: layout))
        .id(model.state.mode)
        .onAppear {
            layout.count = items.count
            layout.shown = Int(model.state.currentItem)
            // The resume position (or the page kept across a change of mode), held until the reader moves.
            layout.anchor = PagedLayout.Anchor(item: Int(model.state.currentItem), within: 0, atBottom: false)
            layout.opening = true
            scroll.scrollTo(id: Int(model.state.currentItem), anchor: .top)
        }
        .onChange(of: items.count) { _, count in layout.count = count }
        .onScrollGeometryChange(for: StripGeometry.self) { g in
            StripGeometry(offset: g.contentOffset.y, viewport: g.containerSize.height, content: g.contentSize.height)
        } action: { _, new in
            layout.offset = new.offset
            layout.viewport = new.viewport
            // Until the reader moves, the opening position holds: `scrollTo(id:anchor: .top)` towards a page taller
            // than the screen came to rest at its bottom (the resume page then opened at its end).
            if layout.idle, layout.opening { holdAnchor() }
            if layout.idle { scheduleSettle() }
        }
        .onScrollPhaseChange { old, phase in
            layout.idle = phase == .idle
            if phase == .interacting { layout.opening = false }
            if phase == .interacting, layout.gestureItem == nil { layout.gestureItem = layout.item(at: layout.offset) }
            if phase == .idle {
                layout.gestureItem = nil
                // The finger (or an animated page turn) moved the reading: what is read now is the new anchor.
                if old != .idle { layout.anchorHere() }
                scheduleSettle()
            }
        }
        .onChange(of: model.state.command?.seq) { _, _ in run(model.state.command) }
    }

    /// A page whose size the source didn't give takes its real height once its image has loaded (the placeholder's
    /// 0.7, then a tall page's full height): pages above the one read push it down. At rest, the scroll follows the
    /// page read (`PagedLayout.anchor`: the item and the offset inside it), so the reader stays where it was, the
    /// resume position included. Several pages growing in one layout pass each move the anchor's frame: the target
    /// comes from that frame, never from the last offset plus a difference.
    private func measured(_ index: Int, _ frame: CGRect) {
        layout.measured(index, frame)
        if layout.idle { holdAnchor() }
        if layout.idle { scheduleSettle() }
    }

    /// Puts the page read back where the anchor says (frames or the offset moved under it).
    private func holdAnchor() {
        guard let target = layout.anchorOffset(), abs(target - layout.offset) > 0.5 else { return }
        layout.offset = target
        scroll.scrollTo(y: target)
    }

    /// Settles once the layout pass is over: frames are reported one item at a time, and a page read from frames half
    /// updated would be the wrong one (and saved as the progress).
    private func scheduleSettle() {
        guard !layout.settleScheduled else { return }
        layout.settleScheduled = true
        DispatchQueue.main.async {
            layout.settleScheduled = false
            if layout.idle { settle() }
        }
    }

    /// At rest: the page at the top of the screen is the current one; it reports its scroll inside the page.
    private func settle() {
        guard let item = layout.item(at: layout.offset), let frame = layout.frames[item] else { return }
        // Until the scroll view has reached the item it opens at, the top of the content is no position.
        if !layout.started {
            guard item == layout.shown else { return }
            layout.started = true
        }
        if item != layout.shown {
            layout.shown = item
            model.send(ReaderIntentItemVisible(item: Int32(item)))
        }
        let tall = frame.height > layout.viewport + 1
        let edges = ReaderIntentPageScroll(
            item: Int32(item),
            tall: tall,
            atTop: !tall || layout.offset <= frame.minY + 1,
            atBottom: !tall || layout.offset >= frame.maxY - layout.viewport - 1
        )
        if edges != reported {
            reported = edges
            model.send(edges)
        }
    }

    private func run(_ command: ReaderCommand?) {
        if let go = command as? ReaderCommandGoToItem {
            model.send(ReaderIntentCommandHandled(seq: go.seq))
            layout.shown = Int(go.item)
            layout.opening = false
            layout.anchor = PagedLayout.Anchor(item: Int(go.item), within: 0, atBottom: go.atBottom)
            let animation: Animation? = go.animated ? .easeInOut(duration: 0.25) : nil
            // A tall page shows its bottom when asked (going back), else its top.
            if let y = layout.offset(of: Int(go.item), atBottom: go.atBottom) {
                withAnimation(animation) { scroll.scrollTo(y: y) }
            } else {
                withAnimation(animation) { scroll.scrollTo(id: Int(go.item), anchor: go.atBottom ? .bottom : .top) }
            }
        } else if let by = command as? ReaderCommandScrollBy {
            model.send(ReaderIntentCommandHandled(seq: by.seq))
            layout.opening = false
            // Inside the current page, never past its edges; taps faster than the animation add up.
            guard let frame = layout.frames[Int(model.state.currentItem)] else { return }
            let now = Date()
            let from = tapTarget.flatMap { now.timeIntervalSince($0.at) < 0.35 ? $0.y : nil } ?? layout.offset
            let target = min(max(frame.minY, from + CGFloat(by.fraction) * layout.viewport), max(frame.minY, frame.maxY - layout.viewport))
            tapTarget = (target, now)
            withAnimation(.easeInOut(duration: 0.3)) { scroll.scrollTo(y: target) }
        }
    }
}

/// The vertical pages' layout as measured (item frames in content coordinates), the scroll offset, and the page a
/// drag began on. A reference shared with the scroll target behaviour; not observed (nothing redraws from it).
private final class PagedLayout {
    static let space = "pagedContent"
    /// Paged: an item fills the screen at least, the top of the screen never rests past its last screen. Strip: it may
    /// rest anywhere in an item, the next one showing below.
    let pagesFillScreen: Bool

    init(pagesFillScreen: Bool = true) {
        self.pagesFillScreen = pagesFillScreen
    }

    var frames: [Int: CGRect] = [:]
    var offset: CGFloat = 0
    var viewport: CGFloat = 0
    var count = 0
    var idle = true
    var gestureItem: Int?
    /// The item last reported on screen; `started` once the scroll view has reached the one it opened at.
    var shown = -1
    var started = false
    var settleScheduled = false
    /// From the opening (or a change of mode) until the reader moves: the opening position is held.
    var opening = false

    /// What is being read: an item and how far into it the top of the screen is (or its bottom, reached going back).
    struct Anchor {
        let item: Int
        let within: CGFloat
        let atBottom: Bool
    }

    var anchor: Anchor?

    func measured(_ index: Int, _ frame: CGRect) {
        frames[index] = frame
    }

    /// The reading moved (a drag, a page turn, a tap scroll): anchor at the item at the top of the screen.
    func anchorHere() {
        // The item at the top of the screen; in a strip's gap, the one above it.
        guard let item = frames.filter({ $0.value.minY - 1 <= offset }).max(by: { $0.value.minY < $1.value.minY })?.key,
              let frame = frames[item] else { return }
        let bottom = frame.height > viewport + 1 && offset >= frame.maxY - viewport - 1
        anchor = Anchor(item: item, within: offset - frame.minY, atBottom: bottom)
    }

    /// Where the scroll must be for the anchor to stay on screen as measured now (inside its item's bounds).
    func anchorOffset() -> CGFloat? {
        guard let anchor, let frame = frames[anchor.item] else { return nil }
        guard pagesFillScreen else { return frame.minY + min(anchor.within, max(0, frame.height - 1)) }
        let last = max(frame.minY, frame.maxY - viewport)
        return anchor.atBottom ? last : min(frame.minY + anchor.within, last)
    }

    /// Where an item shows from its top, or its bottom: from its own frame, or a neighbour's (an item not laid out
    /// yet starts where the one before it ends and ends where the next starts; the stack has no spacing).
    func offset(of index: Int, atBottom: Bool) -> CGFloat? {
        if let frame = frames[index] { return atBottom ? max(frame.minY, frame.maxY - viewport) : frame.minY }
        if !atBottom, let before = frames[index - 1] { return before.maxY }
        if atBottom, let after = frames[index + 1] { return after.minY - viewport }
        return nil
    }

    /// The item at this offset (the top of the screen).
    func item(at y: CGFloat) -> Int? {
        frames.filter { y >= $0.value.minY - 1 && y < $0.value.maxY - 1 }.keys.min()
    }
}

/// Where a drag on the vertical pages comes to rest: `TallPages.restingOffset` for the page it began on.
private struct TallPaging: ScrollTargetBehavior {
    let layout: PagedLayout

    func updateTarget(_ target: inout ScrollTarget, context: TargetContext) {
        // Only where a finger left it: the reader's own scrolls (a page, a tap scroll, the opening position) go exactly
        // where they were sent.
        guard let item = layout.gestureItem, let frame = layout.frames[item] else { return }
        let rest = TallPages.shared.restingOffset(
            top: Float(frame.minY),
            height: Float(frame.height),
            viewport: Float(context.containerSize.height),
            hasPrevious: item > 0,
            hasNext: item < layout.count - 1,
            release: Float(layout.offset),
            proposed: Float(target.rect.minY)
        )
        // The screen's rect at that offset, from its top (a scroll to an item proposes the item's whole rect).
        target.rect = CGRect(origin: CGPoint(x: target.rect.minX, y: CGFloat(rest)), size: context.containerSize)
        target.anchor = .topLeading
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// The continuous strip (`flow`, reader/SPEC.md): every page stacked at the screen's width, no gap. It reports its
/// width, height and scroll (from the first page's top, in points) and plays the view model's `GlideTo` with the shared
/// spring, solved from each display frame's own time (60 or 120 Hz alike). A finger stops the spring at once and the
/// scroll view's own momentum takes over. Focus dims everything but the panel being read.
private struct FlowStrip: View {
    @ObservedObject var model: ReaderModel
    @State private var scroll = ScrollPosition(idType: Int.self)
    @State private var geometry = StripGeometry()
    @State private var phase: ScrollPhase = .idle
    @State private var reportedEdges: [Bool] = []
    @State private var spring = SpringDriver()
    @State private var measured: CGSize = .zero

    /// The previous-chapter card has a fixed height, so the first page's top is known without measuring it.
    private static let cardHeight: CGFloat = 280
    private var lead: CGFloat { Self.cardHeight + CGFloat(model.state.pageGap) }
    /// Pages of unknown size grow from the placeholder to their image's height: the strip keeps what is read in place.
    @State private var layout = PagedLayout(pagesFillScreen: false)

    var body: some View {
        let items = model.state.items
        ScrollView(.vertical) {
            LazyVStack(spacing: CGFloat(model.state.pageGap)) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    Group {
                        if let page = item as? ReaderItemPage {
                            PageImage(page: page.page, onSize: { size in
                                model.send(ReaderIntentPageSized(page: page.page.index, width: Int32(size.width.rounded()), height: Int32(size.height.rounded())))
                            })
                            .task(id: page.page.url) { await luminance(page.page) }
                        } else if let transition = item as? ReaderItemTransition {
                            if transition.card.forward {
                                TransitionCardView(card: transition.card, model: model).frame(minHeight: Self.cardHeight)
                            } else {
                                TransitionCardView(card: transition.card, model: model).frame(height: Self.cardHeight)
                            }
                        }
                    }
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(PagedLayout.space)) } action: { frame in
                        frameMeasured(index, frame)
                    }
                    .id(index)
                }
            }
            .coordinateSpace(.named(PagedLayout.space))
            .scrollTargetLayout()
        }
        .scrollIndicators(.hidden)
        .scrollPosition($scroll)
        .background {
            GeometryReader { area in
                Color.clear.onAppear { measure(area.size) }.onChange(of: area.size) { _, size in measure(size) }
            }
        }
        // Opening at the current item, held there as pages above and the page itself take their real height; a saved
        // position comes as a `GlideTo` once the strip is measured.
        .onAppear {
            layout.anchor = PagedLayout.Anchor(item: Int(model.state.currentItem), within: 0, atBottom: false)
            scroll.scrollTo(id: Int(model.state.currentItem), anchor: .top)
            spring.apply = { [self] y in scroll.scrollTo(y: min(max(0, y + lead), geometry.maxOffset)) }
            spring.onStop = { [self] in
                layout.idle = phase == .idle
                layout.anchorHere()
                report(settled: phase == .idle)
            }
        }
        .onDisappear { spring.stop() }
        .onScrollGeometryChange(for: StripGeometry.self) { g in
            StripGeometry(offset: g.contentOffset.y, viewport: g.containerSize.height, content: g.contentSize.height)
        } action: { _, new in
            geometry = new
            layout.offset = new.offset
            layout.viewport = new.viewport
            // A scroll the reader played (a jump, a tap scroll): what is at the top of the screen now is what to keep.
            if layout.anchor == nil { layout.anchorHere() }
            report(settled: phase == .idle && !spring.running)
            let edges = [new.offset <= 1, new.offset >= new.maxOffset - 1]
            if edges != reportedEdges {
                reportedEdges = edges
                model.send(ReaderIntentStripEdges(atTop: edges[0], atBottom: edges[1]))
            }
        }
        .onScrollPhaseChange { old, new in
            phase = new
            // A finger always wins: the spring stops where it is and the scroll view's momentum takes over.
            if new == .tracking || new == .interacting { spring.stop() }
            layout.idle = new == .idle && !spring.running
            // The finger or a tap scroll moved the strip: what is at the top of the screen now is what to keep.
            if new == .idle, old != .idle { layout.anchorHere() }
            if new == .idle { report(settled: !spring.running) }
        }
        .overlay { focusDim }
        .onChange(of: model.state.prefetch) { _, urls in Prefetch.warm(urls) }
        .onChange(of: model.state.command?.seq) { _, _ in play(model.state.command) }
    }

    private func measure(_ size: CGSize) {
        guard size.width > 0, size.height > 0, size != measured else { return }
        measured = size
        model.send(ReaderIntentStripMeasured(width: Float(size.width), viewport: Float(size.height)))
    }

    /// Scroll from the first page's top: negative over the previous-chapter card, past the strip on the end card.
    private var offset: CGFloat { geometry.offset - lead }

    private func report(settled: Bool) {
        guard measured.width > 0 else { return }
        let byUser = !spring.running && (phase == .tracking || phase == .interacting || phase == .decelerating)
        model.send(ReaderIntentStripScrolled(offset: Float(offset), byUser: byUser, settled: settled))
    }

    private func play(_ command: ReaderCommand?) {
        switch command {
        case let glide as ReaderCommandGlideTo:
            model.send(ReaderIntentCommandHandled(seq: glide.seq))
            let target = CGFloat(glide.offset)
            if !glide.animated {
                spring.stop()
                layout.anchor = nil
                scroll.scrollTo(y: max(0, target + lead))
            } else {
                Haptics.select()
                layout.idle = false
                // Taps add up: from where the spring is, at its speed, towards the new stop.
                spring.glide(from: spring.running ? spring.position : offset, to: target)
            }
        case let go as ReaderCommandGoToItem:
            model.send(ReaderIntentCommandHandled(seq: go.seq))
            spring.stop()
            layout.anchor = PagedLayout.Anchor(item: Int(go.item), within: 0, atBottom: false)
            if go.animated && !model.state.reducedMotion {
                withAnimation(.easeInOut(duration: 0.35)) { scroll.scrollTo(id: Int(go.item), anchor: .top) }
            } else {
                scroll.scrollTo(id: Int(go.item), anchor: .top)
            }
        case let by as ReaderCommandScrollBy:
            // Before the strip is measured: a plain share of the screen.
            model.send(ReaderIntentCommandHandled(seq: by.seq))
            let target = min(max(0, geometry.offset + CGFloat(by.fraction) * geometry.viewport), geometry.maxOffset)
            layout.anchor = nil
            withAnimation(.easeInOut(duration: 0.3)) { scroll.scrollTo(y: target) }
        default:
            break
        }
    }

    /// Focus: the panel being read at full light, the rest at `otherLight`. The spotlight glides in strip
    /// coordinates (`focusMs`); the scroll moves it at once.
    @ViewBuilder private var focusDim: some View {
        if let focus = model.state.focus, measured.width > 0 {
            let pages = model.state.pages
            let width = measured.width
            let index = Int(focus.page)
            if index >= 0 && index < pages.count {
                let top = pages.prefix(index).reduce(CGFloat(0)) { $0 + width / CGFloat($1.aspectRatio) + CGFloat(model.state.pageGap) }
                let height = width / CGFloat(pages[index].aspectRatio)
                let rect = CGRect(
                    x: CGFloat(focus.x) * width,
                    y: top + CGFloat(focus.y) * height,
                    width: CGFloat(focus.width) * width,
                    height: CGFloat(focus.height) * height
                )
                SpotlightShape(spot: rect, scroll: offset)
                    .fill(Color.black.opacity(Double(1 - model.state.otherLight)), style: FillStyle(eoFill: true))
                    .animation(.easeInOut(duration: Double(model.state.focusMs) / 1000), value: rect)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
    }

    /// The page's luminance at `Gutters.COLUMNS` columns (gutter bands when the chapter has no analysis).
    private func luminance(_ page: ReaderPage) async {
        guard page.width > 0, page.height > 0, let url = URL(string: page.url) else { return }
        let columns = Int(Gutters.shared.COLUMNS)
        let rows = min(4096, max(1, Int((CGFloat(columns) * CGFloat(page.height) / CGFloat(page.width)).rounded())))
        guard let values = await Task.detached(priority: .utility, operation: { await Luminance.rows(url: url, columns: columns, rows: rows) }).value
        else { return }
        let array = KotlinIntArray(size: Int32(values.count))
        for (i, v) in values.enumerated() { array.set(index: Int32(i), value: Int32(v)) }
        model.send(ReaderIntentPageLuminance(page: page.index, values: array))
    }

    /// At rest, a page that changed height above (or at) the one read moves the scroll with it (see `VerticalPages`).
    private func frameMeasured(_ index: Int, _ frame: CGRect) {
        layout.measured(index, frame)
        if layout.idle, let target = layout.anchorOffset(), abs(target - layout.offset) > 0.5 {
            layout.offset = target
            scroll.scrollTo(y: target)
        }
    }
}

private struct StripGeometry: Equatable {
    var offset: CGFloat = 0
    var viewport: CGFloat = 0
    var content: CGFloat = 0
    var maxOffset: CGFloat { max(0, content - viewport) }
}

/// The whole screen minus the spotlight (even-odd fill). Only the spotlight animates; the scroll is applied as is.
private struct SpotlightShape: Shape {
    var spot: CGRect
    let scroll: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(AnimatablePair(spot.minX, spot.minY), AnimatablePair(spot.width, spot.height)) }
        set { spot = CGRect(x: newValue.first.first, y: newValue.first.second, width: newValue.second.first, height: newValue.second.second) }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        path.addRect(spot.offsetBy(dx: 0, dy: -scroll))
        return path
    }
}

/// The tap scroll (SPEC §3): a critically damped spring on the scroll position, `Glide.OMEGA`, solved from each
/// display frame's target time (the same curve at 60 and 120 Hz). A new target while moving keeps the velocity.
@MainActor
private final class SpringDriver {
    var apply: ((CGFloat) -> Void)?
    var onStop: (() -> Void)?
    private(set) var position: CGFloat = 0
    private var velocity: CGFloat = 0
    private var from: CGFloat = 0
    private var startVelocity: CGFloat = 0
    private var target: CGFloat = 0
    private var start: CFTimeInterval = 0
    private var link: CADisplayLink?
    private var proxy: LinkProxy?

    var running: Bool { link != nil }

    func glide(from: CGFloat, to target: CGFloat) {
        self.from = from
        startVelocity = running ? velocity : 0
        self.target = target
        position = from
        start = CACurrentMediaTime()
        guard link == nil else { return }
        let proxy = LinkProxy { [weak self] link in MainActor.assumeIsolated { self?.step(link) } }
        let link = CADisplayLink(target: proxy, selector: #selector(LinkProxy.tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.proxy = proxy
        self.link = link
    }

    func stop() {
        guard link != nil else { return }
        link?.invalidate()
        link = nil
        proxy = nil
        velocity = 0
        onStop?()
    }

    private func step(_ link: CADisplayLink) {
        let omega = CGFloat(Glide.shared.OMEGA)
        let t = max(0, link.targetTimestamp - start)
        let x0 = from - target
        let c = startVelocity + omega * x0
        let e = exp(-omega * t)
        position = target + (x0 + c * t) * e
        velocity = (c - omega * (x0 + c * t)) * e
        if Glide.shared.settled(position: Float(position), velocity: Float(velocity), target: Float(target)) {
            position = target
            apply?(target)
            stop()
        } else {
            apply?(position)
        }
    }
}

/// CADisplayLink keeps its target: a small proxy so the driver is never retained by the run loop.
private final class LinkProxy: NSObject {
    let onTick: (CADisplayLink) -> Void
    init(_ onTick: @escaping (CADisplayLink) -> Void) { self.onTick = onTick }
    @objc func tick(_ link: CADisplayLink) { onTick(link) }
}

/// The next pages, fetched ahead into the shared URL cache that AsyncImage reads.
private enum Prefetch {
    static func warm(_ urls: [String]) {
        for case let url? in urls.map(URL.init(string:)) {
            URLSession.shared.dataTask(with: URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad)).resume()
        }
    }
}

/// A page's luminance, downscaled to `columns` x `rows` (one byte per pixel, row after row).
private enum Luminance {
    static func rows(url: URL, columns: Int, rows: Int) async -> [UInt8]? {
        let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad)
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceThumbnailMaxPixelSize: max(columns, rows) * 2,
              ] as CFDictionary)
        else { return nil }
        var pixels = [UInt8](repeating: 0, count: columns * rows)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: columns, height: rows, bitsPerComponent: 8, bytesPerRow: columns,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: columns, height: rows))
            return true
        }
        return drawn ? pixels : nil
    }
}

/// "Finished: Chapter 3" / "Next: Chapter 4 · The last gate" and "Next chapter →".
private struct TransitionCardView: View {
    let card: TransitionCard
    @ObservedObject var model: ReaderModel
    @Environment(\.readerLanguageAction) private var language
    @Environment(\.readerNextChapter) private var nextChapter

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(card.heading).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            HStack(alignment: .top) {
                Text(card.target).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                Spacer(minLength: 8)
                // "Finished: Chapter 3": the heart next to the way on.
                if card.forward, !model.state.titleName.isEmpty {
                    HeartButton(on: model.follow.favorite, label: model.follow.favoriteAction) { model.toggleFavorite() }
                        .accessibilityIdentifier("reader.heart")
                }
            }
            // Reading in a language: the way on in it (prepare, follow, the original), never a dead end.
            if card.forward, let nextChapter {
                NextEndContent(next: nextChapter)
            } else if let label = card.actionLabel {
                if card.forward {
                    Button(label) { model.send(ReaderIntentOpenNextChapter.shared) }.buttonStyle(PrimaryButtonStyle()).padding(.top, 4)
                } else {
                    Button(label) { model.send(ReaderIntentOpenPreviousChapter.shared) }.buttonStyle(GhostButtonStyle()).padding(.top, 4)
                }
            }
            // Reader SPEC §11: the language is one tap away here too.
            if card.forward, let language {
                Button {
                    Haptics.select()
                    language.open()
                } label: {
                    Label(language.label, systemImage: "character.bubble")
                }
                .buttonStyle(GhostButtonStyle())
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// "How to read": the reader's real tap zones under a card of gesture lines. Static, no timer: the first tap anywhere
/// closes it (and is not a page turn); the "?" in the bars brings it back.
private struct ReaderCoachOverlay: View {
    let coach: ReaderCoach
    let dismiss: () -> Void

    var body: some View {
        ZStack {
            Color.brandInk.opacity(coach.zones == nil ? 0.82 : 0.5).ignoresSafeArea()
            if let zones = coach.zones { TapZonesOverlay(overlay: zones) }
            VStack(alignment: .leading, spacing: 14) {
                Text(coach.kicker.uppercased())
                    .font(.brandBody(12, weight: .bold))
                    .tracking(1.6)
                    .foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.brandYellow)
                Text(coach.title).font(.brandDisplay(24)).foregroundStyle(Color.brandPaper)
                ForEach(Array(coach.hints.enumerated()), id: \.offset) { _, hint in
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: symbol(hint.gesture))
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(hint.gesture == .help ? Color.brandMuted : Color.brandRed)
                            .frame(width: 26)
                        Text(hint.text)
                            .font(.brandBody(15))
                            .foregroundStyle(hint.gesture == .help ? Color.brandMuted : Color.brandPaper)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text(coach.dismiss)
                    .font(.brandBody(15, weight: .bold))
                    .foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.brandRed)
                    .background(Rectangle().fill(Color.brandPaper).offset(x: 3, y: 3))
                    .padding(.top, 4)
            }
            .padding(22)
            .frame(maxWidth: 420, alignment: .leading)
            .background(Color.brandInk.opacity(0.94), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.brandPaper.opacity(0.85), lineWidth: 2))
            .padding(24)
        }
        .contentShape(Rectangle())
        .onTapGesture { dismiss() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { dismiss() }
    }

    private func symbol(_ gesture: CoachGesture) -> String {
        switch gesture {
        case .tap: return "hand.tap"
        case .swipe: return "hand.draw"
        case .doubleTap: return "plus.magnifyingglass"
        case .pull: return "arrow.down.to.line"
        default: return "questionmark.circle"
        }
    }
}

/// The tap zones for a moment: red goes forward, cyan goes back, ink opens the menu. Taps go through.
private struct TapZonesOverlay: View {
    let overlay: TapOverlay

    var body: some View {
        GeometryReader { area in
            let w = area.size.width
            let h = area.size.height
            ZStack(alignment: .topLeading) {
                ForEach(Array(overlay.rects.enumerated()), id: \.offset) { _, rect in
                    let width = CGFloat(rect.right - rect.left) * w
                    let height = CGFloat(rect.bottom - rect.top) * h
                    Rectangle()
                        .fill(color(rect.action).opacity(rect.action == .menu ? 0.55 : 0.35))
                        .overlay(Rectangle().stroke(Color.brandInk.opacity(0.6), lineWidth: 1))
                        .frame(width: width, height: height)
                        .position(x: CGFloat(rect.left) * w + width / 2, y: CGFloat(rect.top) * h + height / 2)
                }
                ForEach(Array(overlay.labels.enumerated()), id: \.offset) { _, label in
                    Text(label.text)
                        .font(.brandBody(15, weight: .bold))
                        .foregroundStyle(Color.brandPaper)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(Color.brandInk.opacity(0.7), in: Capsule())
                        .position(x: CGFloat(label.x) * w, y: CGFloat(label.y) * h)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private func color(_ action: TapAction) -> Color {
        switch action {
        case .next: return .brandRed
        case .previous: return .brandCyan
        default: return .brandInk
        }
    }
}

/// Known size (our pages): space reserved from it, no jump when the image arrives. Unknown (a source read on the
/// device usually doesn't say): a placeholder's shape until the image has loaded, then the image's own shape, never
/// the placeholder's (a webtoon strip squeezed into it would be a thin unreadable band). A page that fails says so and
/// can be tried again. The shape is always a frame of known ratio at full width: in the strip's lazy stack, a view that
/// sizes itself from an image or a colour with no height proposed collapses.
private struct PageImage: View {
    let page: ReaderPage
    var fit = false
    /// Two facing pages: each against the spine (the page on the left aligned trailing, on the right leading).
    var alignment: Alignment = .center
    /// A page the source gave no size for: the image's own size once loaded (the strip lays out from it).
    var onSize: ((CGSize) -> Void)? = nil
    @Environment(\.pageLettering) private var lettering
    /// Holds its image (false: a page far from the one read lets its image go).
    var active = true
    /// The image's width / height once loaded (a page whose size the source didn't give may turn out tall).
    var onLoaded: ((CGFloat) -> Void)?
    @StateObject private var loader = PageLoader()

    var body: some View {
        ZStack {
            Color.brandInk2
            if let image = loader.image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
            } else if loader.failed {
                VStack(spacing: 12) {
                    Text(L("reader.pageFailed")).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    Button(L("common.retry")) { loader.load(page.url) }.buttonStyle(GhostButtonStyle()).frame(width: 160)
                }
                .padding(24)
            }
        }
        .aspectRatio(ratio, contentMode: .fit)
        // Over the page's own bounds: the translated lettering, placed by fractions of the page.
        .overlay {
            let items = lettering(Int(page.index))
            if !items.isEmpty { LetteringOverlay(items: items) }
        }
        .frame(maxWidth: .infinity, maxHeight: fit ? .infinity : nil, alignment: alignment)
        .onAppear { if active { loader.show(page.url) } }
        .onChange(of: active) { _, on in if on { loader.show(page.url) } else { loader.clear() } }
        // The saved copy of a chapter shows first; the fresh one may bring new signed links.
        .onChange(of: page.url) { _, url in if active { loader.show(url) } }
        .onChange(of: loader.image) { _, image in
            if let size = image?.size, size.width > 0, size.height > 0 {
                onLoaded?(size.width / size.height)
                if !page.hasSize { onSize?(size) }
            }
        }
        .accessibilityLabel(L("library.page", ["n": Int(page.index) + 1]))
    }

    private var ratio: CGFloat {
        if page.hasSize { return CGFloat(page.aspectRatio) }
        if let size = loader.image?.size, size.width > 0, size.height > 0 { return size.width / size.height }
        return CGFloat(page.aspectRatio)
    }
}

/// One page's image, fetched through the shared session (its cache, and the source's Referer from
/// `SourceRefererProtocol`, as AsyncImage had), so its real size is known once it has loaded.
@MainActor
private final class PageLoader: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var failed = false
    private var task: Task<Void, Never>?
    private var address: String?

    /// Loads `url` unless it is already the one shown (or loading).
    func show(_ url: String) {
        guard url != address else { return }
        image = nil
        load(url)
    }

    /// Lets the image go (the page is far from the one read).
    func clear() {
        task?.cancel()
        address = nil
        image = nil
        failed = false
    }

    func load(_ address: String) {
        self.address = address
        task?.cancel()
        failed = false
        guard let url = URL(string: address) else {
            failed = true
            return
        }
        task = Task {
            do {
                let (data, response) = try await URLSession.shared.data(from: url)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                let decoded = (200 ..< 300).contains(status) ? await Task.detached { UIImage(data: data) }.value : nil
                guard !Task.isCancelled else { return }
                if let decoded { image = decoded } else { failed = true }
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }

    deinit { task?.cancel() }
}

/// Book: one section at a time, in a web view with JavaScript off and no navigation. The HTML is already sanitised
/// and styled (BookStyle, shared Kotlin); images are embedded, nothing is loaded from the web. Pulling past the bottom
/// (or the top) of the section shows a hint that grows with the pull and turns the section on release past the
/// threshold: the web view only measures the pull, `ReaderUiState.pullHint` and `ReaderIntentPullReleased` decide.
private struct BookReader: View {
    @ObservedObject var model: ReaderModel
    let onOpenChapter: (String) -> Void
    @StateObject private var pull = PullTracker()

    var body: some View {
        ZStack(alignment: .bottom) {
            if let document = model.state.sectionDocument {
                BookWebView(html: document, fromEnd: model.state.sectionFromEnd, pull: pull) { overscroll in
                    model.send(ReaderIntentPullReleased(overscroll: Float(overscroll)))
                }
            } else {
                Text(model.state.preparingBook ? L("reader.openingBook") : "")
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if model.state.atEnd, let next = model.state.nextChapterId, model.state.sectionDocument != nil, pull.overscroll == 0 {
                Button(model.state.nextLabel ?? L("reader.nextArrow")) { onOpenChapter(next) }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(16)
            }
            PullHintOverlay(model: model, pull: pull)
        }
        .padding(.top, 72)
        .padding(.bottom, 56)
    }
}

/// The pull being measured (fraction of the reading area's height, positive past the bottom). Only the hint observes
/// it: the web view is not redrawn on every frame of a pull.
@MainActor
private final class PullTracker: ObservableObject {
    @Published private(set) var overscroll: CGFloat = 0

    func set(_ value: CGFloat) {
        if overscroll != value { overscroll = value }
    }
}

/// "Pull for the next section" → "Release for the next section": slides in from its edge and grows with the pull, a
/// bar fills up to the threshold, red once letting go turns. Reduce Motion: no slide, no growth, no colour fade.
private struct PullHintOverlay: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var pull: PullTracker
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let hint = model.state.pullHint(overscroll: Float(pull.overscroll))
        VStack {
            if let hint, !hint.forward { pill(hint) }
            Spacer()
            if let hint, hint.forward { pill(hint) }
        }
        .padding(16)
        .allowsHitTesting(false)
        // A tick when the pull crosses the threshold (going back under it is silent).
        .sensoryFeedback(.impact(weight: .light), trigger: hint?.armed ?? false) { _, armed in armed }
    }

    private func pill(_ hint: PullHint) -> some View {
        let progress = CGFloat(hint.progress)
        let text: Color = hint.armed ? .brandInk : (hint.enabled ? .brandPaper : .brandMuted)
        let arrow = !hint.enabled ? "" : (hint.forward ? "↑  " : "↓  ")
        return VStack(spacing: 6) {
            Text(arrow + hint.label).font(.brandBody(15, weight: .bold)).foregroundStyle(text)
            if hint.enabled {
                Capsule().fill(hint.armed ? Color.brandInk.opacity(0.3) : Color.brandLine)
                    .frame(width: 120, height: 3)
                    .overlay(alignment: .leading) {
                        Capsule().fill(hint.armed ? Color.brandInk : Color.brandRed).frame(width: 120 * progress, height: 3)
                    }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(hint.armed ? Color.brandRed : Color.brandInk2, in: Capsule())
        .overlay(Capsule().stroke(hint.armed ? Color.brandInk : Color.brandLine, lineWidth: 1))
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: hint.armed)
        .scaleEffect(reduceMotion ? 1 : 0.85 + 0.15 * progress)
        .offset(y: reduceMotion ? 0 : (1 - progress) * 24 * (hint.forward ? 1 : -1))
        .opacity(reduceMotion ? 1 : 0.4 + 0.6 * progress)
    }
}

private struct BookWebView: UIViewRepresentable {
    let html: String
    let fromEnd: Bool
    let pull: PullTracker
    let onRelease: (CGFloat) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0E / 255, alpha: 1)
        view.scrollView.backgroundColor = view.backgroundColor
        // Pulls past the edges, short sections included.
        view.scrollView.bounces = true
        view.scrollView.alwaysBounceVertical = true
        // The pull is the finger's travel past the edge (as on Android), read from the scroll view's own pan: the
        // web view still scrolls and rubber-bands as usual.
        view.scrollView.panGestureRecognizer.addTarget(context.coordinator, action: #selector(Coordinator.panned(_:)))
        view.navigationDelegate = context.coordinator
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.pull = pull
        context.coordinator.onRelease = onRelease
        guard context.coordinator.loaded != html else { return }
        context.coordinator.loaded = html
        context.coordinator.fromEnd = fromEnd
        view.loadHTMLString(html, baseURL: nil)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var loaded: String?
        var fromEnd = false
        var pull: PullTracker?
        var onRelease: ((CGFloat) -> Void)?

        /// No link leaves the book: only the initial load of the section is allowed.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            decisionHandler(action.navigationType == .other ? .allow : .cancel)
        }

        /// A section reached going back opens at its end (its height settles over the first frames: tried again).
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard fromEnd else { return }
            for delay in [0.0, 0.12, 0.4] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak webView] in
                    guard let scroll = webView?.scrollView, !scroll.isTracking, !scroll.isDecelerating else { return }
                    let bottom = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
                    scroll.setContentOffset(CGPoint(x: 0, y: max(-scroll.adjustedContentInset.top, bottom)), animated: false)
                }
            }
        }

        /// The finger's travel past an edge since it reached it, in points: positive past the bottom (the finger going
        /// up). Counted once the content can't scroll further that way, as Android's `PullWebView` does.
        private var drag: CGFloat = 0
        private var lastY: CGFloat = 0

        /// Half the finger's travel (a rubber band's feel, Android's `RESISTANCE`), as a fraction of the visible
        /// height: the same threshold is the same gesture on both platforms. The content offset is not used: iOS
        /// rubber-bands it harder than half, so the threshold took about 250 pt of travel here against 240 dp there.
        static let resistance: CGFloat = 0.5

        @objc func panned(_ pan: UIPanGestureRecognizer) {
            guard let scroll = pan.view as? UIScrollView else { return }
            let y = pan.translation(in: scroll).y
            switch pan.state {
            case .began, .changed:
                // From touch-down, as Android counts: the travel before the pan was recognised counts too.
                if pan.state == .began {
                    drag = 0
                    lastY = 0
                }
                // Positive: the finger goes up (towards what comes next).
                let dy = lastY - y
                lastY = y
                let top = -scroll.adjustedContentInset.top
                let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                let offset = scroll.contentOffset.y
                if drag > 0 || (drag == 0 && dy > 0 && offset >= bottom - 0.5) {
                    drag = max(0, drag + dy)
                } else if drag < 0 || (drag == 0 && dy < 0 && offset <= top + 0.5) {
                    drag = min(0, drag + dy)
                } else {
                    drag = 0
                }
                pull?.set(fraction(drag, scroll))
            case .ended:
                let value = fraction(drag, scroll)
                drag = 0
                pull?.set(0)
                if value != 0 { onRelease?(value) }
            default:
                drag = 0
                pull?.set(0)
            }
        }

        private func fraction(_ drag: CGFloat, _ scroll: UIScrollView) -> CGFloat {
            let height = scroll.bounds.height
            return height > 0 ? drag * Self.resistance / height : 0
        }
    }
}

/// A source's chapter whose episode was asked from the reader.
struct SourceEpisode: Identifiable {
    let sourceKey: String
    let titleId: String
    let chapterId: String
    var id: String { "\(sourceKey)/\(chapterId)" }
}

/// The episode card of a source's chapter over the reader: the title page's state gives its label and next chapter.
private struct SourceEpisodeSheet: View {
    @StateObject private var title: TitleModel
    @State private var chapterId: String
    @State private var language = ""
    private let episode: SourceEpisode

    init(episode: SourceEpisode) {
        self.episode = episode
        _title = StateObject(wrappedValue: TitleModel(sourceKey: episode.sourceKey, titleId: episode.titleId))
        _chapterId = State(initialValue: episode.chapterId)
    }

    var body: some View {
        NavigationStack {
            EpisodeView(
                route: EpisodeRoute(
                    chapterId: chapterId,
                    title: title.state,
                    language: language,
                    sourceKey: episode.sourceKey,
                    titleId: episode.titleId
                ),
                inSheet: true
            ) { next, lang in
                chapterId = next
                language = lang
            }
            .id("\(chapterId)~\(language)")
        }
        .task { await title.observe() }
    }
}
