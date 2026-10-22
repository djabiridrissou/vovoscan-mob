import Shared
import SwiftUI
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
    @State private var languagesOpen = false
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
                    Text("This chapter isn't saved on this device yet.").font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    if let error = model.state.error { ErrorText(text: error) }
                    Button("Try again") { model.send(ReaderIntentRetry.shared) }.buttonStyle(GhostButtonStyle())
                }
                .padding(24)
            } else if model.state.isBook {
                BookReader(model: model) { onOpenChapter($0, 0) }
            } else if !model.state.items.isEmpty {
                // The reader's language: each page draws its translated lettering, if any.
                ComicReader(model: model)
                    .environment(\.pageLettering, { [state = translation.state] index in state.letteringFor(index: Int32(index)) })
            }
            if let overlay = model.state.tapOverlay {
                TapZonesOverlay(overlay: overlay).transition(.opacity)
            }
            // A book keeps its bars: the web view receives the taps (text scrolling).
            if model.state.chromeVisible || model.state.isBook { chrome }
        }
        .animation(.easeInOut(duration: 0.2), value: model.state.tapOverlay == nil)
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(!model.state.chromeVisible)
        .task { await model.observe() }
        .task { await translation.observe() }
        .animation(.easeInOut(duration: 0.25), value: translation.state.translated)
        // Opening another chapter (a chapter card, or a tap past the end), whatever the mode.
        .onChange(of: model.state.command?.seq) { _, _ in
            if let open = model.state.command as? ReaderCommandOpenChapter {
                model.send(ReaderIntentCommandHandled(seq: open.seq))
                onOpenChapter(open.chapterId, Int(open.startPage))
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
        .sheet(isPresented: $settingsOpen, onDismiss: { model.send(ReaderIntentSettingsClosed.shared) }) {
            ReaderSettingsSheetView(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(Color.brandInk2)
        }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Button { dismiss() } label: { Text("←").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.state.titleName).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    Text(model.state.chapterLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                Spacer()
                if !model.state.isBook && translation.state.available {
                    LanguageButton(translation: translation) { languagesOpen = true }
                }
                if !model.state.isBook {
                    Button {
                        model.send(ReaderIntentSettingsOpened.shared)
                        settingsOpen = true
                    } label: {
                        Image(systemName: "gearshape").font(.system(size: 18)).foregroundStyle(Color.brandPaper)
                    }
                    .accessibilityLabel("Reader settings")
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
                Spacer()
                if model.state.isBook && model.state.canGoForward {
                    Button("Next section →") { model.send(ReaderIntentNext.shared) }
                        .font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                }
                if let previous = model.state.previousChapterId {
                    Button("← Previous") { onOpenChapter(previous, 0) }.font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                if let next = model.state.nextChapterId {
                    Button("Next →") { onOpenChapter(next, 0) }.font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.brandInk.opacity(0.92))
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
                    StripReader(model: model)
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
        _position = State(initialValue: Int(model.state.currentItem))
    }

    var body: some View {
        let items = model.state.items
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    Group {
                        if let page = item as? ReaderItemPage {
                            if ratios.isTall(page.page, in: size) {
                                TallPageView(model: model, page: page.page, index: index, size: size)
                            } else {
                                PageImage(page: page.page, fit: true) { ratios.set(page.page.url, $0) }
                            }
                        } else if let transition = item as? ReaderItemTransition {
                            TransitionCardView(card: transition.card, model: model)
                        }
                    }
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(width: size.width, height: size.height)
                    .id(index)
                }
            }
            .scrollTargetLayout()
        }
        // Right to left: the first page is on the right, swiping right moves forward (manga direction).
        .environment(\.layoutDirection, model.state.rightToLeft ? .rightToLeft : .leftToRight)
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $position)
        .id(model.state.mode)
        .onChange(of: position) { _, item in
            if let item { model.send(ReaderIntentItemVisible(item: Int32(item))) }
        }
        .onChange(of: model.state.command?.seq) { _, _ in
            guard let go = model.state.command as? ReaderCommandGoToItem else { return }
            model.send(ReaderIntentCommandHandled(seq: go.seq))
            if go.animated {
                withAnimation(.easeInOut(duration: 0.25)) { position = Int(go.item) }
            } else {
                position = Int(go.item)
            }
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
                                PageImage(page: page.page, fit: true, active: active) { ratios.set(page.page.url, $0) }
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
            scroll.scrollTo(id: Int(model.state.currentItem), anchor: .top)
        }
        .onChange(of: items.count) { _, count in layout.count = count }
        .onScrollGeometryChange(for: StripGeometry.self) { g in
            StripGeometry(offset: g.contentOffset.y, viewport: g.containerSize.height, content: g.contentSize.height)
        } action: { _, new in
            layout.offset = new.offset
            layout.viewport = new.viewport
            if layout.idle { settle() }
        }
        .onScrollPhaseChange { _, phase in
            layout.idle = phase == .idle
            if phase == .interacting, layout.gestureItem == nil { layout.gestureItem = layout.item(at: layout.offset) }
            if phase == .idle {
                layout.gestureItem = nil
                settle()
            }
        }
        .onChange(of: model.state.command?.seq) { _, _ in run(model.state.command) }
    }

    /// A page whose size the source didn't give takes its real height once its image has loaded: one above the page
    /// on screen pushes it down, so the scroll follows (what is read stays in place).
    private func measured(_ index: Int, _ frame: CGRect) {
        let before = layout.frames[index]
        let reading = layout.item(at: layout.offset)
        layout.measured(index, frame)
        if let before, let reading, index < reading, layout.idle, before.height != frame.height {
            scroll.scrollTo(y: layout.offset + frame.height - before.height)
        }
        if layout.idle { settle() }
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
            let animation: Animation? = go.animated ? .easeInOut(duration: 0.25) : nil
            // A tall page shows its bottom when asked (going back), else its top.
            if let y = layout.offset(of: Int(go.item), atBottom: go.atBottom) {
                withAnimation(animation) { scroll.scrollTo(y: y) }
            } else {
                withAnimation(animation) { scroll.scrollTo(id: Int(go.item), anchor: go.atBottom ? .bottom : .top) }
            }
        } else if let by = command as? ReaderCommandScrollBy {
            model.send(ReaderIntentCommandHandled(seq: by.seq))
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
    var frames: [Int: CGRect] = [:]
    var offset: CGFloat = 0
    var viewport: CGFloat = 0
    var count = 0
    var idle = true
    var gestureItem: Int?
    /// The item last reported on screen; `started` once the scroll view has reached the one it opened at.
    var shown = -1
    var started = false

    func measured(_ index: Int, _ frame: CGRect) {
        frames[index] = frame
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

/// Continuous vertical strip (webtoon), with the mode's gap between pages. Taps scroll it by a fraction of the screen.
private struct StripReader: View {
    @ObservedObject var model: ReaderModel
    @State private var scroll = ScrollPosition(idType: Int.self)
    @State private var geometry = StripGeometry()
    @State private var reportedEdges: [Bool] = []
    /// The strip moved (finger or tap scroll): until then, positioning at the current item (clamped at the end of
    /// the chapter) must not change the page.
    @State private var moved = false
    @State private var tapTarget: (y: CGFloat, at: Date)?

    var body: some View {
        let items = model.state.items
        ScrollView(.vertical) {
            LazyVStack(spacing: CGFloat(model.state.pageGap)) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    Group {
                        if let page = item as? ReaderItemPage {
                            PageImage(page: page.page)
                        } else if let transition = item as? ReaderItemTransition {
                            TransitionCardView(card: transition.card, model: model).frame(minHeight: 280)
                        }
                    }
                    .id(index)
                }
            }
            .scrollTargetLayout()
        }
        .scrollIndicators(.hidden)
        .scrollPosition($scroll)
        // Resume (and a mode change) at the current item, once the strip is laid out.
        .onAppear { scroll.scrollTo(id: Int(model.state.currentItem), anchor: .top) }
        .onScrollGeometryChange(for: StripGeometry.self) { g in
            StripGeometry(offset: g.contentOffset.y, viewport: g.containerSize.height, content: g.contentSize.height)
        } action: { _, new in
            geometry = new
            let edges = [new.offset <= 1, new.offset >= new.maxOffset - 1]
            if edges != reportedEdges {
                reportedEdges = edges
                model.send(ReaderIntentStripEdges(atTop: edges[0], atBottom: edges[1]))
            }
        }
        .onScrollPhaseChange { _, phase in if phase != .idle { moved = true } }
        // The current item is the first one on screen (any part of it).
        .onScrollTargetVisibilityChange(idType: Int.self, threshold: 0.001) { visible in
            if moved, let first = visible.min() { model.send(ReaderIntentItemVisible(item: Int32(first))) }
        }
        .onChange(of: model.state.command?.seq) { _, _ in
            guard let by = model.state.command as? ReaderCommandScrollBy else { return }
            model.send(ReaderIntentCommandHandled(seq: by.seq))
            // Taps faster than the animation add up (the touch itself stops the animation): within the animation's
            // time, the next one starts from where the last one was going.
            let now = Date()
            let from = tapTarget.flatMap { now.timeIntervalSince($0.at) < 0.35 ? $0.y : nil } ?? geometry.offset
            let target = min(max(0, from + CGFloat(by.fraction) * geometry.viewport), geometry.maxOffset)
            tapTarget = (target, now)
            withAnimation(.easeInOut(duration: 0.3)) { scroll.scrollTo(y: target) }
        }
    }
}

private struct StripGeometry: Equatable {
    var offset: CGFloat = 0
    var viewport: CGFloat = 0
    var content: CGFloat = 0
    var maxOffset: CGFloat { max(0, content - viewport) }
}

/// "Finished: Chapter 3" / "Next: Chapter 4 · The last gate" and "Next chapter →".
private struct TransitionCardView: View {
    let card: TransitionCard
    @ObservedObject var model: ReaderModel

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
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                    Text("This page didn't load.").font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    Button("Retry") { loader.load(page.url) }.buttonStyle(GhostButtonStyle()).frame(width: 160)
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
        .frame(maxWidth: .infinity, maxHeight: fit ? .infinity : nil)
        .onAppear { if active { loader.show(page.url) } }
        .onChange(of: active) { _, on in if on { loader.show(page.url) } else { loader.clear() } }
        // The saved copy of a chapter shows first; the fresh one may bring new signed links.
        .onChange(of: page.url) { _, url in if active { loader.show(url) } }
        .onChange(of: loader.image) { _, image in
            if let size = image?.size, size.width > 0, size.height > 0 { onLoaded?(size.width / size.height) }
        }
        .accessibilityLabel("Page \(page.index + 1)")
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
                Text(model.state.preparingBook ? "Opening the book…" : "")
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if model.state.atEnd, let next = model.state.nextChapterId, model.state.sectionDocument != nil, pull.overscroll == 0 {
                Button(model.state.nextLabel ?? "Next →") { onOpenChapter(next) }
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
        view.scrollView.delegate = context.coordinator
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
    final class Coordinator: NSObject, WKNavigationDelegate, UIScrollViewDelegate {
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

        /// How far the content is pulled past an edge, as a fraction of the visible height (positive: past the bottom).
        private func overscroll(_ scroll: UIScrollView) -> CGFloat {
            let height = scroll.bounds.height
            guard height > 0 else { return 0 }
            let top = -scroll.adjustedContentInset.top
            let bottom = max(top, scroll.contentSize.height - height + scroll.adjustedContentInset.bottom)
            let y = scroll.contentOffset.y
            if y > bottom { return (y - bottom) / height }
            if y < top { return (y - top) / height }
            return 0
        }

        func scrollViewDidScroll(_ scroll: UIScrollView) {
            // Only while the finger pulls: the bounce back after letting go is not a pull.
            pull?.set(scroll.isTracking ? overscroll(scroll) : 0)
        }

        func scrollViewWillEndDragging(
            _ scroll: UIScrollView,
            withVelocity velocity: CGPoint,
            targetContentOffset: UnsafeMutablePointer<CGPoint>
        ) {
            let value = overscroll(scroll)
            pull?.set(0)
            if value != 0 { onRelease?(value) }
        }
    }
}
