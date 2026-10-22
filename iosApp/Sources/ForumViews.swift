import Shared
import SwiftUI

/// The margin (余白) of the chapter in the reader: the switch in the bar, pins over the pages, the sheet (this chapter,
/// theories, salon) and the end of chapter. Only for a chapter of ours or a server copy (the server addresses the margin
/// through its chapters); nothing shows without the `forum` flag.
@MainActor
final class MarginModel: ObservableObject {
    @Published private(set) var state: MarginUiState
    private let owner = ViewModels()
    private let viewModel: ForumViewModel?

    init(route: ReaderRoute) {
        // A source's chapter read on the device has no margin until it is a chapter of the server.
        viewModel = route.sourceKey == "vovoscan" ? owner.forum(chapterId: route.chapterId) : nil
        state = viewModel?.state.value ?? MarginUiState(
            visible: false, open: false, tab: .chapter, order: .best, loading: false, offline: false, error: nil, count: 0,
            pins: [:], notes: [], theories: [], salon: nil, composer: ComposerUi(), thread: nil, endCard: nil, reporting: nil,
            toast: nil
        )
    }

    func observe() async {
        guard let viewModel else { return }
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: MarginIntent) { viewModel?.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// What a page needs to draw the margin over itself and take a mark: the pins, the mark being placed, and the intents.
struct PageMargin {
    let pins: (Int) -> [PinUi]
    let draft: (Int) -> PinUi?
    let placing: Bool
    let tool: MarkTool
    let send: (MarginIntent) -> Void
}

private struct PageMarginKey: EnvironmentKey {
    static let defaultValue: PageMargin? = nil
}

private struct ReaderMarginKey: EnvironmentKey {
    static let defaultValue: MarginModel? = nil
}

extension EnvironmentValues {
    var pageMargin: PageMargin? {
        get { self[PageMarginKey.self] }
        set { self[PageMarginKey.self] = newValue }
    }

    /// The end of chapter reads the margin too ("Read this far", the best notes).
    var readerMargin: MarginModel? {
        get { self[ReaderMarginKey.self] }
        set { self[ReaderMarginKey.self] = newValue }
    }
}

/// "余白 Margin" in the reader's bar, with the chapter's count; yellow while open.
struct MarginButton: View {
    @ObservedObject var margin: MarginModel

    var body: some View {
        let state = margin.state
        Button {
            Haptics.select()
            margin.send(MarginIntentToggle.shared)
        } label: {
            HStack(spacing: 6) {
                Text(state.switchLabel).lineLimit(1)
                if let count = state.countLabel {
                    Text(count).font(.brandBody(11, weight: .bold))
                        .foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 5)
                        .background(Color.brandYellow, in: Capsule())
                }
            }
            .font(.brandBody(13, weight: .bold))
            .foregroundStyle(state.open ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .background(state.open ? Color.brandYellow : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(Color.brandLine, lineWidth: state.open ? 0 : 1))
        }
        .accessibilityLabel([state.switchLabel, state.countLabel].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(state.open ? .isSelected : [])
        .accessibilityIdentifier("reader.margin")
    }
}

/// Over one page: its pins (sticker glyph on yellow, "？" on a spoiler, frames and strokes in red) and, while a tool is
/// chosen, the surface that takes the mark (a tap, a frame dragged, a stroke drawn), in fractions of the page.
struct MarginPinsOverlay: View {
    let pageIndex: Int
    let margin: PageMargin
    @State private var start: CGPoint?
    @State private var current: CGPoint?
    @State private var stroke: [CGPoint] = []

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack(alignment: .topLeading) {
                if margin.placing { capture(size) }
                ForEach(margin.pins(pageIndex), id: \.postId) { pin in pinView(pin, size, draft: false) }
                if let draft = margin.draft(pageIndex) { pinView(draft, size, draft: true) }
                // The frame or stroke under the finger, before it is let go.
                if let start, let current, margin.tool == .frame {
                    Rectangle().stroke(Color.brandRed, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
                        .frame(width: abs(current.x - start.x), height: abs(current.y - start.y))
                        .offset(x: min(start.x, current.x), y: min(start.y, current.y))
                }
                if stroke.count > 1 {
                    Path { path in path.addLines(stroke) }.stroke(Color.brandRed, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
    }

    private func capture(_ size: CGSize) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if start == nil { start = value.startLocation }
                        current = value.location
                        if margin.tool == .draw { stroke.append(value.location) }
                    }
                    .onEnded { value in
                        let a = fraction(value.startLocation, size)
                        let b = fraction(value.location, size)
                        let page = Int32(pageIndex)
                        switch margin.tool {
                        case .point:
                            margin.send(MarginIntentPlacePoint(pageIndex: page, x: b.x, y: b.y))
                        case .frame:
                            margin.send(MarginIntentPlaceFrame(pageIndex: page, x0: a.x, y0: a.y, x1: b.x, y1: b.y))
                        case .draw:
                            let xy = stroke.flatMap { point -> [KotlinDouble] in
                                let f = fraction(point, size)
                                return [KotlinDouble(value: f.x), KotlinDouble(value: f.y)]
                            }
                            margin.send(MarginIntentPlaceStroke(pageIndex: page, xy: xy))
                        default:
                            break
                        }
                        Haptics.tap()
                        start = nil
                        current = nil
                        stroke = []
                    }
            )
    }

    private func fraction(_ point: CGPoint, _ size: CGSize) -> (x: Double, y: Double) {
        (Double(min(max(point.x / max(size.width, 1), 0), 1)), Double(min(max(point.y / max(size.height, 1), 0), 1)))
    }

    @ViewBuilder
    private func pinView(_ pin: PinUi, _ size: CGSize, draft: Bool) -> some View {
        let at = CGPoint(x: size.width * pin.x, y: size.height * pin.y)
        if pin.shape == .frame {
            Rectangle().stroke(Color.brandRed, lineWidth: 2)
                .frame(width: size.width * pin.w, height: size.height * pin.h)
                .offset(x: at.x, y: at.y)
                .allowsHitTesting(false)
        }
        if pin.shape == .stroke {
            let points = stride(from: 0, to: pin.path.count - 1, by: 2).map { i in
                CGPoint(x: size.width * pin.path[i].doubleValue, y: size.height * pin.path[i + 1].doubleValue)
            }
            Path { path in path.addLines(points) }
                .stroke(Color.brandRed, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                .allowsHitTesting(false)
        }
        Button {
            if !draft { margin.send(MarginIntentPinTapped(postId: pin.postId)) }
        } label: {
            Text(pin.label)
                .font(.brandBody(13, weight: .bold))
                .foregroundStyle(Color.brandInk)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(pin.masked ? Color.brandMuted : Color.brandYellow)
                .overlay(Rectangle().stroke(Color.brandInk, lineWidth: 1.5))
                .rotationEffect(.degrees(-4))
                // 44 pt to tap, whatever the label's size.
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .position(x: at.x, y: at.y)
        .opacity(draft ? 0.85 : 1)
        .accessibilityLabel(pin.a11y)
    }
}

/// The sheet: three tabs (this chapter, theories, salon), the list, and the composer with its tools.
struct MarginSheet: View {
    @ObservedObject var margin: MarginModel
    @State private var draft = ""
    @State private var reply = ""

    var body: some View {
        let state = margin.state
        NavigationStack {
            VStack(spacing: 0) {
                header(state)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if let line = state.offlineLine { OfflineNotice(text: line) }
                        if let error = state.error { ErrorText(text: error) }
                        if state.tab == .salon, let salon = state.salon { weeks(salon) }
                        if let empty = state.empty {
                            Text(empty).font(.brandBody(15)).foregroundStyle(Color.brandMuted).padding(.vertical, 24)
                        }
                        ForEach(posts(state), id: \.id) { post in
                            MarginPostView(post: post, send: margin.send)
                        }
                    }
                    .padding(16)
                }
                if state.canWrite { composer(state) }
            }
            .background(Color.brandInk2)
            .navigationDestination(isPresented: Binding(
                get: { margin.state.thread != nil },
                set: { if !$0 { margin.send(MarginIntentCloseThread.shared) } }
            )) {
                thread
            }
            .confirmationDialog(
                state.reportTitle,
                isPresented: Binding(get: { margin.state.reporting != nil }, set: { if !$0 { margin.send(MarginIntentCancelReport.shared) } }),
                titleVisibility: .visible
            ) {
                ForEach(state.reportReasons, id: \.label) { reason in
                    Button(reason.label) { margin.send(MarginIntentSendReport(reason: reason.reason)) }
                }
                Button(L("common.cancel"), role: .cancel) { margin.send(MarginIntentCancelReport.shared) }
            }
            .overlay(alignment: .bottom) {
                if let toast = state.toast {
                    Text(toast).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk)
                        .padding(12)
                        .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 10))
                        .padding(.bottom, 90)
                        .task {
                            try? await Task.sleep(for: .seconds(3))
                            margin.send(MarginIntentToastShown.shared)
                        }
                }
            }
        }
    }

    private func posts(_ state: MarginUiState) -> [PostUi] {
        switch state.tab {
        case .theories: return state.theories
        case .salon: return state.salon?.posts ?? []
        default: return state.notes
        }
    }

    private func header(_ state: MarginUiState) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                ForEach(state.tabs, id: \.label) { tab in
                    ToggleChip(label: tab.label, on: state.tab == tab.tab) {
                        Haptics.select()
                        margin.send(MarginIntentSelectTab(tab: tab.tab))
                    }
                }
                Spacer()
                Button { margin.send(MarginIntentClose.shared) } label: {
                    Image(systemName: "xmark").font(.system(size: 15, weight: .bold)).foregroundStyle(Color.brandMuted)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("common.close"))
            }
            if state.tab != .salon {
                HStack(spacing: 8) {
                    ForEach(state.orders, id: \.label) { order in
                        Button(order.label) { margin.send(MarginIntentSetOrder(order: order.order)) }
                            .font(.brandBody(13, weight: .bold))
                            .foregroundStyle(state.order == order.order ? Color.brandPaper : Color.brandMuted)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }

    private func weeks(_ salon: SalonUi) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(salon.weeks, id: \.key) { week in
                    ToggleChip(label: week.label, on: week.key == salon.weekKey) { margin.send(MarginIntentSelectWeek(week: week.key)) }
                }
            }
        }
    }

    private func composer(_ state: MarginUiState) -> some View {
        let c = state.composer
        return VStack(alignment: .leading, spacing: 8) {
            if c.kind == .note {
                HStack(spacing: 8) {
                    ForEach(state.tools, id: \.label) { tool in
                        ToggleChip(label: tool.label, on: c.tool == tool.tool) { margin.send(MarginIntentChooseTool(tool: tool.tool)) }
                    }
                    if c.anchor != nil {
                        Button(state.clearMarkLabel) { margin.send(MarginIntentClearMark.shared) }
                            .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
                    }
                }
                if let hint = c.toolHint { Text(hint).font(.brandBody(13)).foregroundStyle(Color.brandCyan) }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(state.stickers, id: \.key) { sticker in
                            Button {
                                Haptics.tap()
                                margin.send(MarginIntentPickSticker(key: c.sticker == sticker.key ? nil : sticker.key))
                            } label: {
                                Text(sticker.glyph).font(.brandBody(14, weight: .bold))
                                    .foregroundStyle(Color.brandInk)
                                    .padding(.horizontal, 8)
                                    .frame(minHeight: 36)
                                    .background(c.sticker == sticker.key ? Color.brandYellow : Color.brandPaper.opacity(0.85))
                            }
                            .accessibilityLabel("\(state.stickerLabel) \(sticker.glyph)")
                            .accessibilityAddTraits(c.sticker == sticker.key ? .isSelected : [])
                        }
                    }
                }
            }
            TextField(c.placeholder, text: $draft, axis: .vertical)
                .lineLimit(1 ... 5)
                .font(.brandBody(15))
                .foregroundStyle(Color.brandPaper)
                .padding(10)
                .background(Color.brandInk, in: RoundedRectangle(cornerRadius: 10))
                .onChange(of: draft) { _, text in margin.send(MarginIntentType(text: text)) }
                .onChange(of: c.body) { _, body in if body.isEmpty && !draft.isEmpty && !c.sending { draft = "" } }
                .onChange(of: c.editingId) { _, editing in if editing != nil { draft = c.body } }
            HStack {
                Toggle(isOn: Binding(get: { c.spoiler }, set: { margin.send(MarginIntentSetSpoiler(on: $0)) })) {
                    Text(state.spoilerLabel).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                }
                .toggleStyle(.switch)
                .tint(Color.brandRed)
                Text(c.counter).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                Button(state.sendLabel) {
                    submitAfterCommittingInput {
                        margin.send(MarginIntentType(text: draft))
                        margin.send(MarginIntentSend.shared)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .frame(width: 120)
                .disabled(c.sending)
            }
            if let problem = c.problem { ErrorText(text: problem) }
        }
        .padding(16)
        .background(Color.brandInk2)
        .overlay(alignment: .top) { Rectangle().fill(Color.brandLine).frame(height: 1) }
    }

    @ViewBuilder private var thread: some View {
        if let thread = margin.state.thread {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    MarginPostView(post: thread.post, send: margin.send, inThread: true)
                    ForEach(thread.replies, id: \.id) { post in
                        MarginPostView(post: post, send: margin.send, inThread: true).padding(.leading, 20)
                    }
                    if thread.post.canReply {
                        TextField(margin.state.replyPlaceholder, text: $reply, axis: .vertical)
                            .lineLimit(1 ... 4)
                            .font(.brandBody(15))
                            .foregroundStyle(Color.brandPaper)
                            .padding(10)
                            .background(Color.brandInk, in: RoundedRectangle(cornerRadius: 10))
                            .onChange(of: reply) { _, text in margin.send(MarginIntentTypeReply(text: text)) }
                            .onChange(of: thread.draft) { _, text in if text.isEmpty && !thread.sending { reply = "" } }
                        HStack {
                            Text(thread.counter).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                            Spacer()
                            Button(L("forum.reply")) {
                                submitAfterCommittingInput {
                                    margin.send(MarginIntentTypeReply(text: reply))
                                    margin.send(MarginIntentSendReply.shared)
                                }
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .frame(width: 140)
                        }
                    }
                }
                .padding(16)
            }
            .background(Color.brandInk2)
            .navigationTitle(margin.state.repliesTitle)
        }
    }
}

/// One post: author, badge, age, the sticker and words (or the spoiler line with "Show anyway"), the eight reactions,
/// the verdict of a theory, replies, translate, and its menu (edit, delete, report, mute).
struct MarginPostView: View {
    let post: PostUi
    let send: (MarginIntent) -> Void
    var inThread = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(post.author).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
                if let badge = post.badge {
                    Text(badge).font(.brandBody(11, weight: .bold)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 5).background(Color.brandRed)
                }
                Text([post.age, post.place].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ✦ "))
                    .font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                Spacer()
                menu
            }
            if let masked = post.maskedLine {
                Button {
                    Haptics.tap()
                    send(MarginIntentReveal(postId: post.id))
                } label: {
                    HStack {
                        Text("？").font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk)
                            .padding(.horizontal, 6).background(Color.brandMuted)
                        Text(masked).font(.brandBody(14)).foregroundStyle(Color.brandMuted).multilineTextAlignment(.leading)
                    }
                }
                .buttonStyle(.plain)
            } else {
                if let sticker = post.sticker {
                    Text(sticker).font(.brandDisplay(18)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 8).background(Color.brandYellow).rotationEffect(.degrees(-3))
                }
                if let body = post.body, !body.isEmpty {
                    Text(body).font(.brandBody(15)).foregroundStyle(Color.brandPaper).fixedSize(horizontal: false, vertical: true)
                }
                if let translation = post.translation {
                    Text(translation).font(.brandBody(15)).foregroundStyle(Color.brandCyan).fixedSize(horizontal: false, vertical: true)
                }
            }
            if let note = post.note { Text(note).font(.brandBody(12)).foregroundStyle(Color.brandMuted) }
            if let verdict = post.verdict { verdictView(verdict) }
            if post.maskedLine == nil { reactions }
            HStack(spacing: 16) {
                if !inThread, let replies = post.replies {
                    Button(replies) { send(MarginIntentOpenThread(postId: post.id)) }
                } else if !inThread, post.canReply {
                    Button(L("forum.reply")) { send(MarginIntentOpenThread(postId: post.id)) }
                }
                if post.translating {
                    Text(L("forum.translating")).foregroundStyle(Color.brandCyan)
                } else if post.canTranslate {
                    Button(L("forum.translate")) { send(MarginIntentTranslate(postId: post.id)) }
                }
            }
            .font(.brandBody(13, weight: .bold))
            .foregroundStyle(Color.brandMuted)
        }
        .padding(14)
        .background(Color.brandInk, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(post.highlighted ? Color.brandYellow : Color.brandLine, lineWidth: post.highlighted ? 2 : 1))
    }

    private var reactions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(post.reactions, id: \.key) { reaction in
                    Button {
                        Haptics.tap()
                        send(MarginIntentReact(postId: post.id, key: reaction.key))
                    } label: {
                        HStack(spacing: 3) {
                            Text(reaction.glyph)
                            if let count = reaction.count { Text("\(count.intValue)") }
                        }
                        .font(.brandBody(12, weight: .bold))
                        .foregroundStyle(reaction.mine ? Color.brandInk : Color.brandPaper)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 32)
                        .background(reaction.mine ? Color.brandYellow : Color.brandInk2, in: Capsule())
                        .opacity(reaction.count == nil && !reaction.mine ? 0.6 : 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(reaction.mine ? .isSelected : [])
                }
            }
        }
    }

    private func verdictView(_ verdict: VerdictUi) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verdict.state).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandYellow)
            if let counts = verdict.counts { Text(counts).font(.brandBody(12)).foregroundStyle(Color.brandMuted) }
            if verdict.canVote {
                HStack(spacing: 8) {
                    ToggleChip(label: L("forum.happened"), on: verdict.mine?.boolValue == true) {
                        send(MarginIntentVote(postId: post.id, happened: true))
                    }
                    ToggleChip(label: L("forum.didntHappen"), on: verdict.mine?.boolValue == false) {
                        send(MarginIntentVote(postId: post.id, happened: false))
                    }
                }
            }
        }
    }

    private var menu: some View {
        Menu {
            if post.editable { Button(L("forum.edit")) { send(MarginIntentStartEdit(postId: post.id)) } }
            if post.mine {
                Button(L("common.delete"), role: .destructive) { send(MarginIntentDelete(postId: post.id)) }
            } else {
                Button(L("forum.report")) { send(MarginIntentReport(postId: post.id)) }
                Button(L("forum.mute")) { send(MarginIntentMute(authorId: post.authorId)) }
            }
        } label: {
            Image(systemName: "ellipsis").foregroundStyle(Color.brandMuted).frame(width: 44, height: 32)
        }
    }
}

/// Under the last page: "ここまで読んだ Read this far", how many readers got here, the best notes, and the way in.
struct MarginEndCard: View {
    @ObservedObject var margin: MarginModel

    var body: some View {
        if margin.state.visible, let end = margin.state.endCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(end.title).font(.brandDisplay(16)).foregroundStyle(Color.brandPaper)
                Text(end.readers).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                ForEach(end.best, id: \.id) { post in
                    HStack(alignment: .top, spacing: 6) {
                        if let sticker = post.sticker {
                            Text(sticker).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandInk)
                                .padding(.horizontal, 4).background(Color.brandYellow)
                        }
                        Text(post.body ?? "").font(.brandBody(14)).foregroundStyle(Color.brandPaper).lineLimit(2)
                    }
                }
                HStack(spacing: 8) {
                    ForEach(margin.state.tabs, id: \.label) { tab in
                        Button(tab.tab == .chapter ? margin.state.leaveMarkLabel : tab.label) {
                            if !margin.state.open { margin.send(MarginIntentToggle.shared) }
                            margin.send(MarginIntentSelectTab(tab: tab.tab))
                        }
                        .buttonStyle(GhostButtonStyle())
                    }
                }
            }
            .padding(.top, 8)
        }
    }
}
