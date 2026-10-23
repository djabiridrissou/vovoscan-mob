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
}

struct ReaderContainer: View {
    @State var route: ReaderRoute

    var body: some View {
        ReaderView(route: route) { chapterId, startPage in
            route = ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: chapterId, startPage: startPage)
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
    @Environment(\.dismiss) private var dismiss
    @State private var settingsOpen = false
    @State private var contentsOpen = false
    @State private var languagesOpen = false
    @State private var keysOpen = false
    /// The bottom bar's height: the language dock sits above it while the bars show.
    @State private var bottomBarHeight: CGFloat = 0
    @State private var player: PlayerRoute?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// A hardware keyboard (iPad, Magic Keyboard): arrows, space, page keys and Escape reach the reader.
    @FocusState private var keysFocused: Bool
    /// The page under the slider's thumb while it is dragged.
    @State private var dragged: Int?
    let onOpenChapter: (String, Int) -> Void

    init(route: ReaderRoute, onOpenChapter: @escaping (String, Int) -> Void) {
        _model = StateObject(wrappedValue: ReaderModel(route: route))
        _translation = StateObject(wrappedValue: TranslationModel(route: route))
        self.onOpenChapter = onOpenChapter
    }

    var body: some View {
        ZStack {
            Color.brandInk.ignoresSafeArea()
            if model.state.unavailable {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("reader.notSaved")).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    if let error = model.state.error { ErrorText(text: error) }
                    Button(L("common.tryAgain")) { model.send(ReaderIntentRetry.shared) }.buttonStyle(GhostButtonStyle())
                }
                .padding(24)
            } else if model.state.isBook {
                BookReader(model: model) { onOpenChapter($0, 0) }
            } else if !model.state.items.isEmpty {
                // The reader's language: each page draws its translated lettering, if any.
                ComicReader(model: model)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel(model.state.chapterLabel)
                    .accessibilityHint(model.state.tapsHint)
                    .environment(\.pageLettering, { [state = translation.state] index in state.letteringFor(index: Int32(index)) })
                    .environment(\.readerLanguageAction, translation.state.available
                        ? ReaderLanguageAction(label: translation.state.endCardLabel) { languagesOpen = true }
                        : nil)
            }
            // A language on its way: the page keeps its lettering, the dock shows the cost, then the progress.
            if let dock = translation.state.dock, !model.state.isBook {
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
            if let overlay = model.state.tapOverlay {
                TapZonesOverlay(overlay: overlay).transition(.opacity)
            }
            // While the bars are away: a 2 pt line of the share read, at the very top.
            if !model.state.chromeVisible && !model.state.isBook { progressLine }
            if let card = model.state.titleCard { titleCard(card) }
            // A book keeps its bars: the web view receives the taps (text scrolling).
            if model.state.chromeVisible || model.state.isBook { chrome.transition(.opacity) }
            // How to read, the first time a chapter of its kind opens: any tap closes it.
            if let coach = model.state.coach {
                ReaderCoachOverlay(coach: coach) { model.send(ReaderIntentDismissCoach.shared) }
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.state.tapOverlay == nil)
        .animation(model.state.reducedMotion ? nil : .easeOut(duration: 0.25), value: model.state.coach == nil)
        .animation(model.state.reducedMotion ? nil : .easeInOut(duration: 0.25), value: model.state.chromeVisible)
        .animation(model.state.reducedMotion ? nil : .easeInOut(duration: 0.4), value: model.state.titleCard)
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(!model.state.chromeVisible)
        .focusable()
        .focused($keysFocused)
        .focusEffectDisabled()
        .onAppear { keysFocused = true }
        .onKeyPress(phases: .down) { press in
            guard let key = ReaderKeyMap.key(press) else { return .ignored }
            model.send(ReaderIntentKey(key: key))
            return .handled
        }
        .task { await model.observe() }
        .task { await translation.observe() }
        .animation(.easeInOut(duration: 0.25), value: translation.state.translated)
        .animation(model.state.reducedMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: translation.state.dock == nil)
        // Opening another chapter (a chapter card, or a tap past the end), whatever the mode.
        .onChange(of: model.state.command?.seq) { _, _ in
            if let open = model.state.command as? ReaderCommandOpenChapter {
                model.send(ReaderIntentCommandHandled(seq: open.seq))
                onOpenChapter(open.chapterId, Int(open.startPage))
            }
            if let close = model.state.command as? ReaderCommandClose {
                model.send(ReaderIntentCommandHandled(seq: close.seq))
                dismiss()
            }
            // "Watch from here": the episode from the first beat of the page being read.
            if let episode = model.state.command as? ReaderCommandOpenEpisode {
                model.send(ReaderIntentCommandHandled(seq: episode.seq))
                player = PlayerRoute(chapterId: episode.chapterId, nextChapterId: nil, startPage: Int(episode.page))
            }
        }
        .fullScreenCover(item: $player) { route in PlayerView(route: route) { _, _ in player = nil } }
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
            // Books have their own settings (typography, page colours), applied to the page behind the sheet.
            Group {
                if model.state.isBook { BookSettingsSheetView(model: model) } else { ReaderSettingsSheetView(model: model) }
            }
            .presentationDetents([.medium, .large])
            .presentationBackground(Color.brandInk2)
        }
        .sheet(isPresented: $contentsOpen) {
            BookContentsSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(Color.brandInk2)
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

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Button { dismiss() } label: { Text("←").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper) }
                    .accessibilityLabel(L("common.back"))
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.state.titleName).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    Text(model.state.subtitle).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1)
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
                if model.state.isBook && !model.state.book.contents.isEmpty {
                    Button { contentsOpen = true } label: {
                        Image(systemName: "list.bullet").font(.system(size: 18)).foregroundStyle(Color.brandPaper)
                    }
                    .accessibilityLabel(BookText.shared.CONTENTS_BUTTON)
                }
                Button {
                    model.send(ReaderIntentSettingsOpened.shared)
                    settingsOpen = true
                } label: {
                    Image(systemName: "gearshape").font(.system(size: 18)).foregroundStyle(Color.brandPaper)
                }
                .accessibilityLabel(L("reader.settingsTitle"))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.brandInk.opacity(0.92))
            Spacer()
            if model.state.showsSlider {
                PageSlider(model: model, dragged: $dragged)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .background(Color.brandInk.opacity(0.92))
            }
            HStack(spacing: 20) {
                if model.state.isBook {
                    Button { model.send(ReaderIntentPrevious.shared) } label: {
                        Text("←").font(.brandDisplay(18)).foregroundStyle(model.state.canGoBack ? Color.brandPaper : Color.brandLine)
                    }
                    .disabled(!model.state.canGoBack)
                    .accessibilityLabel("Previous section")
                }
                // "5 / 19", or the page the slider is being dragged to, in yellow.
                Text(dragged.map { model.state.sliderLabel(page: Int32($0)) } ?? model.state.positionLabel)
                    .font(.brandBody(13))
                    .foregroundStyle(dragged == nil ? Color.brandMuted : Color.brandYellow)
                    .monospacedDigit()
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
                // A book with a section left offers that section alone (as on Android): the parts come at its end.
                if model.state.isBook && model.state.canGoForward {
                    Button(L("reader.nextSection")) { model.send(ReaderIntentNext.shared) }
                        .font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                } else {
                    // Chapters, not pages (pages turn with taps and swipes); right to left puts the next one on the left.
                    ForEach(model.state.chapterButtons, id: \.chapterId) { button in
                        Button(button.label) { onOpenChapter(button.chapterId, 0) }
                            .font(.brandBody(button.forward ? 16 : 13, weight: button.forward ? .bold : .regular))
                            .foregroundStyle(button.forward ? Color.brandRed : Color.brandMuted)
                    }
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

/// Comics: a slider over every page of the chapter, the first page on the right in right to left. Letting go jumps
/// there (`SeekTo`), in every mode; while dragged, the page under the thumb shows in the bar ("12 / 19").
private struct PageSlider: View {
    @ObservedObject var model: ReaderModel
    @Binding var dragged: Int?
    @State private var editing = false

    var body: some View {
        let last = Double(max(1, model.state.pages.count - 1))
        Slider(
            value: Binding(
                get: { Double(dragged ?? Int(model.state.currentIndex)) },
                set: { value in
                    let page = Int(value.rounded())
                    if editing {
                        dragged = page
                    } else {
                        // VoiceOver's adjustments (no drag): at once.
                        model.send(ReaderIntentSeekTo(page: Int32(page)))
                    }
                }
            ),
            in: 0 ... last,
            step: 1
        ) { started in
            editing = started
            if !started, let page = dragged {
                model.send(ReaderIntentSeekTo(page: Int32(page)))
                dragged = nil
            }
        }
        .tint(Color.brandRed)
        .environment(\.layoutDirection, model.state.rightToLeft ? .rightToLeft : .leftToRight)
        // Made again when the direction changes: a slider flipped in place drew its thumb apart from its fill.
        .id(model.state.rightToLeft)
        .accessibilityLabel("Page")
        .accessibilityValue(model.state.sliderLabel(page: Int32(dragged ?? Int(model.state.currentIndex))))
    }
}

/// Comic: the mode's layout under one tap gesture, and the zoom (`PageZoom`, shared: where it goes). A drag or fling
/// is the scroll view's at 1×, never a tap. Pinching zooms from 1× to 4× in every mode (the page on screen, or the
/// whole strip); zoomed, a drag pans and, past the zoomed view's edge, scrolls the strip or the tall page beneath, while
/// the pager or the strip stays still under the fingers. Double tap to zoom (off by default) makes single taps wait for
/// a possible second tap.
private struct ComicReader: View {
    @ObservedObject var model: ReaderModel
    @Environment(\.displayScale) private var displayScale
    @State private var zoom = PageZoom.shared.None
    @State private var pinchStart: ZoomTransform?
    @State private var lastDrag: CGSize?
    @State private var scroller = ZoomScroller()

    var body: some View {
        GeometryReader { area in
            let size = area.size
            Group {
                if model.state.isPaged {
                    PagedReader(model: model, size: size, scroller: scroller, zoomed: zoom.zoomed)
                } else {
                    FlowStrip(model: model, scroller: scroller)
                }
            }
            // Pages are decoded for the screen's pixels (never above their own).
            .environment(\.pageDecodeWidth, size.width * displayScale)
            .scrollDisabled(zoom.zoomed || pinchStart != nil)
            .scaleEffect(CGFloat(zoom.scale), anchor: .topLeading)
            .offset(x: CGFloat(zoom.x), y: CGFloat(zoom.y))
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .clipped()
            .contentShape(Rectangle())
            .gesture(taps(size: size))
            .gesture(pan(size: size), including: zoom.zoomed ? .all : .subviews)
            .simultaneousGesture(pinch(size: size))
        }
        .ignoresSafeArea()
        // Another page (a volume key), a jump or another mode: back to 1× at once.
        .onChange(of: model.state.zoomReset) { _, _ in zoom = PageZoom.shared.None }
        .onChange(of: zoom.zoomed) { _, zoomed in model.send(ReaderIntentZoomChanged(zoomed: zoomed)) }
    }

    /// Two fingers, at any scale: around where they started (SwiftUI gives no centre that moves with them).
    private func pinch(size: CGSize) -> some Gesture {
        MagnifyGesture(minimumScaleDelta: 0)
            .onChanged { value in
                let start = pinchStart ?? zoom
                if pinchStart == nil { pinchStart = start }
                zoom = PageZoom.shared.pinch(
                    transform: start,
                    factor: Float(value.magnification),
                    focusX: Float(value.startLocation.x),
                    focusY: Float(value.startLocation.y),
                    panX: 0,
                    panY: 0,
                    width: Float(size.width),
                    height: Float(size.height)
                )
            }
            .onEnded { _ in pinchStart = nil }
    }

    /// Zoomed: a drag pans the view, and what goes past its edge scrolls the content beneath; letting go flings it.
    private func pan(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard pinchStart == nil else { return }
                let last = lastDrag ?? .zero
                lastDrag = value.translation
                let moved = PageZoom.shared.pan(
                    transform: zoom,
                    dx: Float(value.translation.width - last.width),
                    dy: Float(value.translation.height - last.height),
                    width: Float(size.width),
                    height: Float(size.height)
                )
                zoom = moved.transform
                if moved.scroll != 0 { scroller.scrollBy?(CGFloat(moved.scroll)) }
            }
            .onEnded { value in
                lastDrag = nil
                guard zoom.zoomed, pinchStart == nil else { return }
                scroller.fling?(-value.velocity.height / CGFloat(zoom.scale))
            }
    }

    /// Single tap at once; with double tap to zoom on, the single tap waits for a possible second one.
    private func taps(size: CGSize) -> AnyGesture<Void> {
        let single = SpatialTapGesture(count: 1).onEnded { value in
            guard size.width > 0, size.height > 0 else { return }
            model.send(ReaderIntentTap(x: Float(value.location.x / size.width), y: Float(value.location.y / size.height)))
        }
        guard model.state.doubleTapZoom else { return AnyGesture(single.map { _ in () }) }
        let double = SpatialTapGesture(count: 2).onEnded { value in
            // Fit ↔ 2× around the finger (that point stays where it is).
            let target = PageZoom.shared.doubleTap(
                transform: zoom,
                x: Float(value.location.x),
                y: Float(value.location.y),
                width: Float(size.width),
                height: Float(size.height)
            )
            withAnimation(.easeOut(duration: 0.22)) { zoom = target }
        }
        return AnyGesture(double.exclusively(before: single).map { _ in () })
    }
}

/// What a zoomed pan scrolls past the view's edge (content points, positive forward), and the fling when it lets go
/// (content points per second): the strip, or the current tall page within its bounds. Set by whichever is on screen.
@MainActor
private final class ZoomScroller {
    var scrollBy: ((CGFloat) -> Void)?
    var fling: ((CGFloat) -> Void)?

    /// Where a fling from `from` at `speed` comes to rest (a scroll view's normal deceleration, about half a second's
    /// travel), within `range`.
    static func rest(from: CGFloat, speed: CGFloat, in range: ClosedRange<CGFloat>) -> CGFloat {
        min(max(range.lowerBound, from + speed * 0.4), range.upperBound)
    }
}

private struct PageDecodeWidthKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// The reading area's width in pixels: pages are decoded no wider (0: their own width).
    fileprivate var pageDecodeWidth: CGFloat {
        get { self[PageDecodeWidthKey.self] }
        set { self[PageDecodeWidthKey.self] = newValue }
    }
}

/// One page at a time. Horizontal (right to left: mirrored layout): a paging scroll view; a tall page (`TallPages`)
/// fits the width and scrolls vertically inside its page. Vertical: see `VerticalPages`.
private struct PagedReader: View {
    @ObservedObject var model: ReaderModel
    let size: CGSize
    let scroller: ZoomScroller
    /// The page read is zoomed: it is decoded with all its pixels (sharp up to 4×).
    let zoomed: Bool
    @StateObject private var ratios = PageRatios()

    var body: some View {
        if model.state.pagedHorizontally {
            HorizontalPages(model: model, size: size, ratios: ratios, scroller: scroller, zoomed: zoomed)
        } else {
            VerticalPages(model: model, size: size, ratios: ratios, scroller: scroller, zoomed: zoomed)
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
    let scroller: ZoomScroller
    let zoomed: Bool
    @State private var position: Int?

    init(model: ReaderModel, size: CGSize, ratios: PageRatios, scroller: ZoomScroller, zoomed: Bool) {
        self.model = model
        self.size = size
        self.ratios = ratios
        self.scroller = scroller
        self.zoomed = zoomed
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
        // The screen shown comes from what is visible, not from the position binding alone: a pager made while the
        // chapter is open (Continuous → Right to left from the settings) could keep its first position and never
        // report a swipe, so the counter, the slider and the saved page stayed where the strip had left them.
        .onScrollTargetVisibilityChange(idType: Int.self, threshold: 0.6) { ids in
            guard let screen = ids.first else { return }
            if position != screen { position = screen }
            if screen != Int(model.state.currentSpread), let spread = model.state.spreads[safe: screen] {
                model.send(ReaderIntentItemVisible(item: spread.first))
            }
        }
        .id("\(model.state.mode)-\(model.state.twoPages)")
        .onAppear {
            // Placed once laid out: a lazy stack made mid-chapter may not have reached the current screen yet.
            let screen = Int(model.state.currentSpread)
            DispatchQueue.main.async { position = screen }
        }
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
            // The page slider: that page at once (a tall page shows its top, `TallPageView`).
            if let jump = model.state.command as? ReaderCommandJumpTo {
                model.send(ReaderIntentCommandHandled(seq: jump.seq))
                position = Int(model.state.spreadOf(item: jump.item))
            }
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

    /// One item of a screen: a page (a tall one scrolls inside itself, alone on its screen), or a chapter card. The
    /// page read, zoomed, is decoded with all its pixels.
    @ViewBuilder
    private func item(_ index: Int, alignment: Alignment) -> some View {
        if let page = model.state.items[safe: index] as? ReaderItemPage {
            let detail = zoomed && index == Int(model.state.currentItem)
            if alignment == .center && ratios.isTall(page.page, in: size) {
                TallPageView(model: model, page: page.page, index: index, size: size, scroller: scroller, detail: detail)
            } else {
                PageImage(page: page.page, fit: true, alignment: alignment, detail: detail, onLoaded: { ratios.set(page.page.url, $0) })
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
    let scroller: ZoomScroller
    let detail: Bool
    @State private var position = ScrollPosition(edge: .top)
    @State private var geometry = StripGeometry()
    @State private var tapTarget: (y: CGFloat, at: Date)?

    private var current: Bool { Int(model.state.currentItem) == index }

    var body: some View {
        ScrollView(.vertical) {
            PageImage(page: page, detail: detail)
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
            if current { takeZoomScroll() }
        }
        .onChange(of: model.state.currentItem) { _, item in
            if index < Int(item) { position.scrollTo(edge: .bottom) }
            if index > Int(item) { position.scrollTo(edge: .top) }
            report()
            if current { takeZoomScroll() }
        }
        .onChange(of: model.state.command?.seq) { _, _ in
            if let go = model.state.command as? ReaderCommandGoToItem, Int(go.item) == index, go.atBottom {
                position.scrollTo(edge: .bottom)
            }
            if let jump = model.state.command as? ReaderCommandJumpTo, Int(jump.item) == index {
                position.scrollTo(edge: .top)
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

    /// Zoomed, a pan past the view's edge scrolls this page inside itself.
    private func takeZoomScroll() {
        // A page that is no longer the one read (another page took over, or none is tall) scrolls nothing.
        scroller.scrollBy = { distance in
            guard current else { return }
            let target = min(max(0, geometry.offset + distance), geometry.maxOffset)
            geometry.offset = target
            position.scrollTo(y: target)
        }
        scroller.fling = { speed in
            guard current else { return }
            let target = ZoomScroller.rest(from: geometry.offset, speed: speed, in: 0 ... geometry.maxOffset)
            withAnimation(.easeOut(duration: 0.6)) { position.scrollTo(y: target) }
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
    let scroller: ZoomScroller
    let zoomed: Bool
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
                            let detail = zoomed && index == current
                            if ratios.isTall(page.page, in: size), let ratio = ratios.ratio(page.page) {
                                PageImage(page: page.page, active: active, detail: detail).frame(width: size.width, height: size.width / ratio)
                            } else {
                                PageImage(page: page.page, fit: true, active: active, detail: detail, onLoaded: { ratios.set(page.page.url, $0) })
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
            takeZoomScroll()
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

    /// Zoomed, a pan past the view's edge scrolls the page read inside itself (never into the next page).
    private func takeZoomScroll() {
        scroller.scrollBy = { distance in
            guard let target = zoomTarget(layout.offset + distance) else { return }
            layout.offset = target
            scroll.scrollTo(y: target)
            layout.anchorHere()
        }
        scroller.fling = { speed in
            guard let frame = layout.frames[Int(model.state.currentItem)] else { return }
            let range = frame.minY ... max(frame.minY, frame.maxY - layout.viewport)
            let target = ZoomScroller.rest(from: layout.offset, speed: speed, in: range)
            withAnimation(.easeOut(duration: 0.6)) { scroll.scrollTo(y: target) }
            layout.anchor = PagedLayout.Anchor(item: Int(model.state.currentItem), within: target - frame.minY, atBottom: false)
        }
    }

    private func zoomTarget(_ y: CGFloat) -> CGFloat? {
        guard let frame = layout.frames[Int(model.state.currentItem)] else { return nil }
        return min(max(frame.minY, y), max(frame.minY, frame.maxY - layout.viewport))
    }

    private func run(_ command: ReaderCommand?) {
        if let jump = command as? ReaderCommandJumpTo {
            // The page slider: that page's top at once.
            model.send(ReaderIntentCommandHandled(seq: jump.seq))
            layout.shown = Int(jump.item)
            layout.opening = false
            layout.anchor = PagedLayout.Anchor(item: Int(jump.item), within: 0, atBottom: false)
            if let y = layout.offset(of: Int(jump.item), atBottom: false) {
                scroll.scrollTo(y: y)
            } else {
                scroll.scrollTo(id: Int(jump.item), anchor: .top)
            }
            return
        }
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
    let scroller: ZoomScroller
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
    /// The items that hold their image: those within a screen above and two below what is on screen.
    @State private var window: ClosedRange<Int>?
    private static let above: CGFloat = 1
    private static let below: CGFloat = 2

    var body: some View {
        let items = model.state.items
        let current = Int(model.state.currentItem)
        let held = window ?? (current - 1) ... (current + 1)
        ScrollView(.vertical) {
            VStack(spacing: CGFloat(model.state.pageGap)) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    Group {
                        if let page = item as? ReaderItemPage {
                            PageImage(
                                page: page.page,
                                onSize: { size in
                                    model.send(
                                        ReaderIntentPageSized(page: page.page.index, width: Int32(size.width.rounded()), height: Int32(size.height.rounded()))
                                    )
                                },
                                active: held.contains(index)
                            )
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
            takeZoomScroll()
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
            updateWindow()
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
        updateWindow()
    }

    /// The items near the screen (by their frames) hold their image; the others let it go.
    private func updateWindow() {
        guard layout.viewport > 0 else { return }
        let top = layout.offset - layout.viewport * Self.above
        let bottom = layout.offset + layout.viewport * (1 + Self.below)
        let near = layout.frames.filter { $0.value.maxY >= top && $0.value.minY <= bottom }.keys
        guard let first = near.min(), let last = near.max() else { return }
        if window != first ... last { window = first ... last }
    }

    /// Zoomed, the whole strip is: a pan past the view's edge scrolls it, a fling carries on.
    private func takeZoomScroll() {
        scroller.scrollBy = { distance in
            let target = min(max(0, layout.offset + distance), geometry.maxOffset)
            spring.stop()
            layout.offset = target
            scroll.scrollTo(y: target)
            layout.anchorHere()
        }
        scroller.fling = { speed in
            let target = ZoomScroller.rest(from: layout.offset, speed: speed, in: 0 ... geometry.maxOffset)
            withAnimation(.easeOut(duration: 0.6)) { scroll.scrollTo(y: target) }
            layout.offset = target
            layout.anchorHere()
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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(card.heading).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            Text(card.target).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            if let label = card.actionLabel {
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
    /// The page read, zoomed: decoded with all its pixels (sharp up to 4×), else for the screen's width.
    var detail = false
    /// The image's width / height once loaded (a page whose size the source didn't give may turn out tall).
    var onLoaded: ((CGFloat) -> Void)?
    @Environment(\.pageDecodeWidth) private var decodeWidth
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
            } else if active {
                // Still loading: says so (a slow source left a blank page). Near the top of a tall page, where it is seen.
                ProgressView()
                    .tint(Color.brandCyan)
                    .padding(.top, fit ? 0 : 120)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: fit ? .center : .top)
                    .accessibilityLabel("Loading the page")
            }
        }
        .aspectRatio(ratio, contentMode: .fit)
        // Over the page's own bounds: the translated lettering, placed by fractions of the page.
        .overlay {
            let items = lettering(Int(page.index))
            if !items.isEmpty { LetteringOverlay(items: items) }
        }
        .frame(maxWidth: .infinity, maxHeight: fit ? .infinity : nil, alignment: alignment)
        .onAppear {
            loader.maxWidth = detail ? 0 : decodeWidth
            if active { loader.show(page.url) }
        }
        .onChange(of: active) { _, on in if on { loader.show(page.url) } else { loader.clear() } }
        // The saved copy of a chapter shows first; the fresh one may bring new signed links.
        .onChange(of: page.url) { _, url in if active { loader.show(url) } }
        .onChange(of: detail ? 0 : decodeWidth) { _, width in loader.redecode(maxWidth: width) }
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
/// `SourceRefererProtocol`, as AsyncImage had), so its real size is known once it has loaded. Decoded with ImageIO at
/// once, off the main thread, never wider than `maxWidth` pixels (the screen's; 0: its own width): `UIImage(data:)`
/// kept the file and decoded it full size on the main thread when first drawn.
@MainActor
private final class PageLoader: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var failed = false
    var maxWidth: CGFloat = 0
    private var task: Task<Void, Never>?
    private var address: String?
    /// The file, kept while the image is held: a zoom decodes it again with all its pixels, and back.
    private var data: Data?
    /// The file's own width in pixels (0: unknown).
    private var fullWidth: CGFloat = 0

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
        data = nil
        fullWidth = 0
        failed = false
    }

    /// Decodes the file again for another width (zoomed in, or back), the image on screen kept meanwhile.
    func redecode(maxWidth: CGFloat) {
        guard maxWidth != self.maxWidth else { return }
        let old = self.maxWidth
        self.maxWidth = maxWidth
        // A page no wider than both widths is already at its own size (a webtoon strip narrower than the screen).
        func full(_ width: CGFloat) -> Bool { width == 0 || (fullWidth > 0 && width >= fullWidth) }
        if full(old), full(maxWidth) { return }
        guard let data, image != nil else { return }
        let address = self.address
        task?.cancel()
        task = Task {
            let decoded = await Task.detached { Self.decode(data, maxWidth: maxWidth) }.value
            guard !Task.isCancelled, address == self.address, let decoded else { return }
            image = decoded
        }
    }

    nonisolated static func pixelWidth(_ data: Data) -> CGFloat {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return 0 }
        return properties[kCGImagePropertyPixelWidth] as? CGFloat ?? 0
    }

    /// The image at its own size, or `maxWidth` pixels wide when it is wider (0: its own size), decoded now.
    nonisolated static func decode(_ data: Data, maxWidth: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? CGFloat,
              let height = properties[kCGImagePropertyPixelHeight] as? CGFloat, width > 0, height > 0
        else { return UIImage(data: data) }
        // The thumbnail's size is its longest side.
        let scale = maxWidth > 0 && width > maxWidth ? maxWidth / width : 1
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int((max(width, height) * scale).rounded(.up)),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return UIImage(data: data) }
        return UIImage(cgImage: image)
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
                let maxWidth = maxWidth
                let decoded = (200 ..< 300).contains(status) ? await Task.detached { Self.decode(data, maxWidth: maxWidth) }.value : nil
                guard !Task.isCancelled else { return }
                if let decoded {
                    image = decoded
                    self.data = data
                    fullWidth = Self.pixelWidth(data)
                } else {
                    failed = true
                }
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ZStack(alignment: .bottom) {
            if let document = model.state.sectionDocument {
                BookWebView(
                    html: document,
                    section: Int(model.state.currentIndex),
                    opening: model.state.book.opening,
                    background: UInt32(truncatingIfNeeded: model.state.book.pageBackground),
                    pull: pull,
                    onScrolled: { section, fraction in
                        model.send(BookIntentSectionScrolled(section: Int32(section), fraction: Float(fraction)))
                    }
                ) { overscroll in
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
        // The device's text size (Dynamic Type, as body text scales): the page's text follows it.
        .onChange(of: dynamicTypeSize, initial: true) { _, size in
            let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(size))
            let scale = UIFontMetrics(forTextStyle: .body).scaledValue(for: 100, compatibleWith: traits) / 100
            model.send(BookIntentSystemTextScale(scale: Float(scale)))
        }
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
    /// The section in `html`: openings and reports are for it only.
    let section: Int
    let opening: SectionOpening?
    /// The page's colour (`0xRRGGBB`), shown while the section loads and past its edges.
    let background: UInt32
    let pull: PullTracker
    let onScrolled: (Int, CGFloat) -> Void
    let onRelease: (CGFloat) -> Void

    /// The document's address, never fetched (no network): a jump to an anchor is a navigation inside this same
    /// document, which WebKit does with the page's JavaScript off.
    static let baseURL = URL(string: "https://book.vovoscan.invalid/section")!

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
        context.coordinator.watch(view.scrollView)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.pull = pull
        coordinator.onRelease = onRelease
        coordinator.onScrolled = onScrolled
        let color = UIColor(
            red: CGFloat((background >> 16) & 0xFF) / 255,
            green: CGFloat((background >> 8) & 0xFF) / 255,
            blue: CGFloat(background & 0xFF) / 255,
            alpha: 1
        )
        if view.backgroundColor != color {
            view.backgroundColor = color
            view.scrollView.backgroundColor = color
        }
        let forHere = opening.flatMap { Int($0.section) == section && Int($0.seq) != coordinator.appliedSeq ? $0 : nil }
        if coordinator.loaded != html {
            coordinator.loaded = html
            coordinator.section = section
            coordinator.pending = forHere
            if let forHere { coordinator.appliedSeq = Int(forHere.seq) }
            coordinator.loading = true
            coordinator.placing = true
            view.loadHTMLString(html, baseURL: Self.baseURL)
        } else if let forHere, !coordinator.loading {
            coordinator.appliedSeq = Int(forHere.seq)
            coordinator.place(forHere, in: view)
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var loaded: String?
        var pull: PullTracker?
        var onRelease: ((CGFloat) -> Void)?
        var onScrolled: ((Int, CGFloat) -> Void)?

        /// The section in the page, its opening to apply once loaded, the last opening applied.
        var section = -1
        var pending: SectionOpening?
        var appliedSeq = -1
        var loading = false

        /// An opening is being applied (the page is not where it will be yet): nothing is reported meanwhile.
        var placing = false
        private var placeGeneration = 0
        private var offsetWatch: NSKeyValueObservation?

        /// Where the top of the page is, as a fraction of its height: the reader reopens there.
        func watch(_ scroll: UIScrollView) {
            offsetWatch = scroll.observe(\.contentOffset, options: [.new]) { [weak self] scroll, _ in
                MainActor.assumeIsolated { self?.report(scroll) }
            }
        }

        private func report(_ scroll: UIScrollView) {
            let height = scroll.contentSize.height
            guard !placing, !loading, section >= 0, height > 0 else { return }
            let top = scroll.contentOffset.y + scroll.adjustedContentInset.top
            onScrolled?(section, min(1, max(0, top / height)))
        }

        /// No link leaves the book: only the section's own load and jumps inside it are allowed.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            decisionHandler(action.navigationType == .other ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Once per load (a jump to an anchor inside the page is no new load).
            guard loading else { return }
            loading = false
            place(pending, in: webView)
        }

        /// Puts the page where `opening` says (its top when nil). A section of several MB keeps growing for a moment
        /// after it has loaded: placed again a few times, unless the reader touches it first. Then reports where it is.
        func place(_ opening: SectionOpening?, in webView: WKWebView) {
            placeGeneration += 1
            let generation = placeGeneration
            guard let opening, opening.fraction != nil || opening.anchor != nil || opening.fromEnd else {
                placing = false
                report(webView.scrollView)
                return
            }
            placing = true
            let steps = [0.0, 0.12, 0.4, 1.0]
            for (step, delay) in steps.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self, weak webView] in
                    guard let self, let webView, generation == self.placeGeneration else { return }
                    let scroll = webView.scrollView
                    // The reader took over: where the page is now is theirs.
                    if scroll.isTracking || scroll.isDecelerating {
                        self.placing = false
                        return
                    }
                    let top = -scroll.adjustedContentInset.top
                    if let anchor = opening.anchor {
                        var address = URLComponents(url: BookWebView.baseURL, resolvingAgainstBaseURL: false)!
                        address.fragment = anchor
                        if let url = address.url { webView.load(URLRequest(url: url)) }
                    } else if opening.fromEnd {
                        let bottom = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
                        scroll.setContentOffset(CGPoint(x: 0, y: max(top, bottom)), animated: false)
                    } else if let fraction = opening.fraction?.doubleValue {
                        let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                        scroll.setContentOffset(CGPoint(x: 0, y: min(bottom, max(top, fraction * scroll.contentSize.height + top))), animated: false)
                    }
                    if step == steps.count - 1 {
                        self.placing = false
                        self.report(scroll)
                    }
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
                    // The reader takes over: no opening moves the page any more.
                    if placing && !loading {
                        placeGeneration += 1
                        placing = false
                    }
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
