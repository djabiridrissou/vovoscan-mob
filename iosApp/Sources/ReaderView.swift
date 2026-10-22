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
                Spacer()
                if model.state.isBook && model.state.canGoForward {
                    Button(L("reader.nextSection")) { model.send(ReaderIntentNext.shared) }
                        .font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                }
                if let previous = model.state.previousChapterId {
                    Button(L("reader.previousArrow")) { onOpenChapter(previous, 0) }.font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                if let next = model.state.nextChapterId {
                    Button(L("reader.nextArrow")) { onOpenChapter(next, 0) }.font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
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

/// One page at a time, a paging scroll view: horizontal (right to left: mirrored layout) or vertical.
private struct PagedReader: View {
    @ObservedObject var model: ReaderModel
    let size: CGSize
    @State private var position: Int?

    init(model: ReaderModel, size: CGSize) {
        self.model = model
        self.size = size
        _position = State(initialValue: Int(model.state.currentItem))
    }

    var body: some View {
        let items = model.state.items
        Group {
            if model.state.pagedHorizontally {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 0) { pages(items) }.scrollTargetLayout()
                }
                // Right to left: the first page is on the right, swiping right moves forward (manga direction).
                .environment(\.layoutDirection, model.state.rightToLeft ? .rightToLeft : .leftToRight)
            } else {
                ScrollView(.vertical) {
                    LazyVStack(spacing: 0) { pages(items) }.scrollTargetLayout()
                }
            }
        }
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

    private func pages(_ items: [ReaderItem]) -> some View {
        ForEach(Array(items.enumerated()), id: \.offset) { index, item in
            Group {
                if let page = item as? ReaderItemPage {
                    PageImage(page: page.page, fit: true)
                } else if let transition = item as? ReaderItemTransition {
                    TransitionCardView(card: transition.card, model: model)
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            .frame(width: size.width, height: size.height)
            .id(index)
        }
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
    @StateObject private var loader = PageLoader()

    var body: some View {
        ZStack {
            Color.brandInk2
            if let image = loader.image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
            } else if loader.failed {
                VStack(spacing: 12) {
                    Text(L("reader.pageFailed")).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    Button(L("common.tryAgain")) { loader.load(page.url) }.buttonStyle(GhostButtonStyle()).frame(width: 160)
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
        .onAppear { loader.show(page.url) }
        // The saved copy of a chapter shows first; the fresh one may bring new signed links.
        .onChange(of: page.url) { _, url in loader.show(url) }
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
/// and styled (BookStyle, shared Kotlin); images are embedded, nothing is loaded from the web.
private struct BookReader: View {
    @ObservedObject var model: ReaderModel
    let onOpenChapter: (String) -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            if let document = model.state.sectionDocument {
                BookWebView(html: document)
            } else {
                Text(model.state.preparingBook ? L("reader.openingBook") : "")
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if model.state.atEnd, let next = model.state.nextChapterId, model.state.sectionDocument != nil {
                Button(model.state.nextLabel ?? L("reader.nextArrow")) { onOpenChapter(next) }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(16)
            }
        }
        .padding(.top, 72)
        .padding(.bottom, 56)
    }
}

private struct BookWebView: UIViewRepresentable {
    let html: String

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0E / 255, alpha: 1)
        view.scrollView.backgroundColor = view.backgroundColor
        view.navigationDelegate = context.coordinator
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        guard context.coordinator.loaded != html else { return }
        context.coordinator.loaded = html
        view.loadHTMLString(html, baseURL: nil)
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loaded: String?

        /// No link leaves the book: only the initial load of the section is allowed.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            decisionHandler(action.navigationType == .other ? .allow : .cancel)
        }
    }
}
