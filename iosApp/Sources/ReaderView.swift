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
        ReaderView(route: route) { route = ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: $0, startPage: 0) }
            .id(route)
    }
}

/// Reader: continuous vertical strip (webtoon) or right-to-left pages (manga). Tapping hides the bars.
/// No logic here: current page, resume and progress live in `ReaderViewModel` (shared Kotlin).
struct ReaderView: View {
    @StateObject private var model: ReaderModel
    @Environment(\.dismiss) private var dismiss
    let onOpenChapter: (String) -> Void

    init(route: ReaderRoute, onOpenChapter: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: ReaderModel(route: route))
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
                BookReader(model: model, onOpenChapter: onOpenChapter)
            } else if !model.state.pages.isEmpty {
                if model.state.mode == .vertical { VerticalStrip(model: model, onOpenChapter: onOpenChapter) } else { RightToLeftPages(model: model) }
            }
            // A book keeps its bars: the web view receives the taps (text scrolling).
            if model.state.chromeVisible || model.state.isBook { chrome }
        }
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(!model.state.chromeVisible)
        .task { await model.observe() }
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
                    Button("← Previous") { onOpenChapter(previous) }.font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                if let next = model.state.nextChapterId {
                    Button("Next →") { onOpenChapter(next) }.font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandRed)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.brandInk.opacity(0.92))
        }
    }
}

/// Vertical strip: each page reports its position; the current page is the one covering the top third of the screen.
private struct VerticalStrip: View {
    @ObservedObject var model: ReaderModel
    let onOpenChapter: (String) -> Void
    @State private var positioned = false

    var body: some View {
        GeometryReader { viewport in
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(model.state.pages, id: \.index) { page in
                            PageImage(page: page)
                                .id(Int(page.index))
                                .background(GeometryReader { frame in
                                    Color.clear.preference(key: PageTops.self, value: [Int(page.index): frame.frame(in: .named("strip")).minY])
                                })
                        }
                        VStack(alignment: .leading, spacing: 12) {
                            Text("End of \(model.state.chapterLabel).").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                            if let next = model.state.nextChapterId {
                                Button("Next chapter →") { onOpenChapter(next) }.buttonStyle(PrimaryButtonStyle())
                            }
                        }
                        .padding(24)
                        .padding(.bottom, 64)
                    }
                }
                .coordinateSpace(name: "strip")
                .onTapGesture { model.send(ReaderIntentToggleChrome.shared) }
                .onPreferenceChange(PageTops.self) { tops in
                    let line = viewport.size.height / 3
                    if let current = tops.filter({ $0.value <= line }).max(by: { $0.key < $1.key })?.key {
                        model.send(ReaderIntentPageVisible(index: Int32(current)))
                    }
                }
                .onAppear {
                    // Resume: once, at the saved page.
                    guard !positioned else { return }
                    positioned = true
                    proxy.scrollTo(Int(model.state.currentIndex), anchor: .top)
                }
            }
        }
    }
}

private struct PageTops: PreferenceKey {
    static var defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

/// Right-to-left pages: the RTL layout reverses the swipe direction, as in a manga.
private struct RightToLeftPages: View {
    @ObservedObject var model: ReaderModel

    var body: some View {
        TabView(selection: Binding(
            get: { Int(model.state.currentIndex) },
            set: { model.send(ReaderIntentPageVisible(index: Int32($0))) }
        )) {
            ForEach(model.state.pages, id: \.index) { page in
                PageImage(page: page, fit: true)
                    .environment(\.layoutDirection, .leftToRight)
                    .tag(Int(page.index))
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .environment(\.layoutDirection, .rightToLeft)
        .onTapGesture { model.send(ReaderIntentToggleChrome.shared) }
        .ignoresSafeArea()
    }
}

/// Space is reserved from the known dimensions: no jump when the image arrives.
private struct PageImage: View {
    let page: ReaderPage
    var fit = false

    var body: some View {
        AsyncImage(url: URL(string: page.url)) { image in
            image.resizable().aspectRatio(contentMode: .fit)
        } placeholder: {
            Color.brandInk2
        }
        .aspectRatio(CGFloat(page.aspectRatio), contentMode: .fit)
        .frame(maxWidth: .infinity, maxHeight: fit ? .infinity : nil)
        .accessibilityLabel("Page \(page.index + 1)")
    }
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
                Text(model.state.preparingBook ? "Opening the book…" : "")
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if model.state.atEnd, let next = model.state.nextChapterId, model.state.sectionDocument != nil {
                Button(model.state.nextLabel ?? "Next →") { onOpenChapter(next) }
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
