import Shared
import SwiftUI
import UIKit

/// The reading circle next to a reader: a chapter of the server only (ours, or a source's copy), like the
/// margin. `TogetherViewModel` (shared Kotlin) decides everything; this file draws the button, the sheet, the dock, the
/// rail, the stickers, the lines and the toasts, and forwards the reader's place and gestures.
@MainActor
final class TogetherModel: ObservableObject {
    @Published private(set) var state: TogetherUiState
    private let owner = ViewModels()
    private let viewModel: TogetherViewModel?

    init(route: ReaderRoute) {
        viewModel = route.sourceKey == "vovoscan" ? owner.together(chapterId: route.chapterId) : nil
        state = viewModel?.state.value ?? TogetherUiState.companion.hidden
    }

    func observe() async {
        guard let viewModel else { return }
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: TogetherIntent) { viewModel?.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// Each reader's ink, by slot: the same eight as the web (`sync.ts`), the brand's accents first.
enum CircleInk {
    private static let slots: [Color] = [
        Color(hex: 0xFF2D55), Color(hex: 0x2DE2E6), Color(hex: 0xFFD60A), Color(hex: 0x4ADE80),
        Color(hex: 0xC084FC), Color(hex: 0xFB923C), Color(hex: 0x60A5FA), Color(hex: 0xF472B6),
    ]

    static func of(_ slot: Int32) -> Color { slots[Int(slot) % slots.count] }
}

/// "Together" in the reader's top bar: the sheet (read together, join with a code), or the ticket once in a circle.
struct TogetherButton: View {
    @ObservedObject var together: TogetherModel
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let inCircle = together.state.circle != nil
        Button {
            Haptics.select()
            together.send(TogetherIntentOpenSheet.shared)
        } label: {
            // The word on a wide bar (iPad, landscape), the icon alone on a phone's crowded one: never a label broken
            // one letter a line.
            pill(word: sizeClass == .regular)
            .foregroundStyle(inCircle ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .background(inCircle ? Color.brandCyan : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(Color.brandLine, lineWidth: inCircle ? 0 : 1))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(together.state.buttonLabel)
        .accessibilityIdentifier("reader.together")
    }

    private func pill(word: Bool) -> some View {
        HStack(spacing: 4) {
            if word {
                Text(together.state.buttonLabel).font(.brandDisplay(13)).lineLimit(1)
            } else {
                Image(systemName: "person.2.fill").font(.system(size: 13, weight: .bold))
            }
            if let count = together.state.circle?.stamps.count, count > 0 {
                Text("\(count)").font(.brandBody(11, weight: .bold)).lineLimit(1)
            }
        }
        .fixedSize()
    }
}

/// The sheet: the two ways in, the code with its preview, or the admission ticket.
struct TogetherSheetView: View {
    @ObservedObject var together: TogetherModel
    @State private var code = ""
    @State private var copied = false

    var body: some View {
        let state = together.state
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text(state.title).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                    Spacer()
                    Button(state.closeLabel) { together.send(TogetherIntentCloseSheet.shared) }
                        .font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandMuted)
                        .frame(minHeight: 44)
                }
                switch state.sheet {
                case .ticket: if let circle = state.circle { ticket(circle.ticket, state) }
                case .join: join(state)
                default: choose(state)
                }
                if let error = state.error { ErrorText(text: error) }
            }
            .padding(20)
        }
        .background(Color.brandInk2)
    }

    private func choose(_ state: TogetherUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(state.startHelp).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
            Button(state.startLabel) { together.send(TogetherIntentStartCircle.shared) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(state.busy)
                .accessibilityIdentifier("together.start")
            Button(state.joinLabel) { together.send(TogetherIntentShowJoin.shared) }
                .buttonStyle(GhostButtonStyle())
        }
    }

    private func join(_ state: TogetherUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            TextField("", text: $code, prompt: Text(state.codePlaceholder).foregroundColor(Color.brandMuted))
                .font(.system(size: 28, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brandPaper)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .textContentType(.oneTimeCode)
                .padding(14)
                .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
                .onChange(of: code) { _, text in together.send(TogetherIntentCodeChanged(text: text)) }
                .accessibilityLabel(state.joinLabel)
            if let preview = state.preview {
                VStack(alignment: .leading, spacing: 6) {
                    Text(preview.heading).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandCyan)
                    Text(preview.chapterLine).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                    Text(preview.hostLine).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    Text(preview.presentLine).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    if let blocked = preview.blocked { Text(blocked).font(.brandBody(14)).foregroundStyle(Color.brandDanger) }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.brandInk, in: RoundedRectangle(cornerRadius: 10))
            }
            Button(state.joinAction) { together.send(TogetherIntentJoin.shared) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!state.canJoin)
        }
    }

    /// The admission ticket: the code in large type in a dashed frame, the link to share or copy, and the host's door.
    private func ticket(_ ticket: TicketUi, _ state: TogetherUiState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(spacing: 10) {
                HStack {
                    Text(state.ticketTitle).font(.brandDisplay(14))
                    Spacer()
                    Text(ticket.admission).font(.brandBody(12, weight: .bold))
                }
                .foregroundStyle(Color.brandInk)
                Text(ticket.code)
                    .font(.system(size: 40, weight: .heavy, design: .monospaced))
                    .foregroundStyle(Color.brandInk)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .accessibilityIdentifier("together.code")
                Text(ticket.chapterLine).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk).multilineTextAlignment(.center)
                Text(ticket.closesLine).font(.brandBody(12)).foregroundStyle(Color.brandInk.opacity(0.7))
            }
            .padding(18)
            .frame(maxWidth: .infinity)
            .background(Color.brandPaper, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.brandInk, style: StrokeStyle(lineWidth: 2, dash: [7, 5])).padding(6))
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.brandRed).offset(x: 5, y: 5))
            let link = webLink(ticket)
            HStack(spacing: 10) {
                ShareLink(item: link) { Text(state.shareLabel) }.buttonStyle(PrimaryButtonStyle())
                Button(copied ? L("together.copied") : state.copyLabel) {
                    UIPasteboard.general.url = link
                    copied = true
                    Haptics.success()
                }
                .buttonStyle(GhostButtonStyle())
            }
            if ticket.isHost {
                HStack(spacing: 10) {
                    Button(state.lockLabel) { together.send(TogetherIntentToggleLock.shared) }.buttonStyle(GhostButtonStyle())
                    Button(state.renewLabel) { together.send(TogetherIntentRenewCode.shared) }.buttonStyle(GhostButtonStyle())
                }
                Button(state.endLabel) { together.send(TogetherIntentEndCircle.shared) }
                    .font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandRed).frame(minHeight: 44)
            } else {
                Button(state.leaveLabel) { together.send(TogetherIntentLeave.shared) }
                    .font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandRed).frame(minHeight: 44)
            }
        }
    }

    /// The website's page for the code: it opens the app when installed (`vovoscan://together/CODE`), the web reader otherwise.
    private func webLink(_ ticket: TicketUi) -> URL {
        URL(string: AppEnvironment.webBaseUrl)?.appending(path: String(ticket.webPath.drop(while: { $0 == "/" }))) ?? URL(string: ticket.appLink)!
    }
}

/// The dock at the bottom of the reader: the stamps, the follow pill, the stickers, Wait, a line, the log, Leave.
struct TogetherDock: View {
    @ObservedObject var together: TogetherModel
    @FocusState private var lineFocused: Bool
    @State private var line = ""

    var body: some View {
        let state = together.state
        if let circle = state.circle {
            VStack(alignment: .leading, spacing: 8) {
                if state.logOpen { log(circle) }
                balloons(state, circle)
                stamps(circle, state)
                if !state.collapsed {
                    controls(circle, state)
                    stickers(state)
                    lineField(state)
                }
            }
            .padding(12)
            .background(Color.brandPaper, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandInk, lineWidth: 2))
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.brandInk).offset(x: 4, y: 4))
            .padding(.horizontal, 12)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(state.dockLabel)
            .accessibilityIdentifier("together.dock")
        }
    }

    private func stamps(_ circle: CircleUi, _ state: TogetherUiState) -> some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(circle.stamps, id: \.userId) { stamp in
                        Button { together.send(TogetherIntentSelectStamp(userId: stamp.userId)) } label: { Hanko(stamp: stamp) }
                            .accessibilityLabel(stamp.a11y)
                    }
                }
            }
            Button { together.send(TogetherIntentToggleCollapsed.shared) } label: {
                Image(systemName: state.collapsed ? "chevron.up" : "chevron.down")
                    .font(.system(size: 14, weight: .bold)).foregroundStyle(Color.brandInk)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(L(state.collapsed ? "together.expand" : "together.collapse"))
        }
    }

    @ViewBuilder private func controls(_ circle: CircleUi, _ state: TogetherUiState) -> some View {
        HStack(spacing: 8) {
            if let label = circle.followLabel {
                Button {
                    if !circle.following { together.send(TogetherIntentFollow.shared) }
                } label: {
                    Text(label).font(.brandBody(13, weight: .bold)).lineLimit(1)
                        .foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 12).frame(minHeight: 36)
                        .background(circle.following ? Color.brandCyan : Color.brandYellow, in: Capsule())
                }
                .accessibilityIdentifier("together.follow")
            }
            if let summary = circle.hostSummary {
                Text(summary).font(.brandBody(12)).foregroundStyle(Color.brandInk.opacity(0.75)).lineLimit(1)
            }
            Spacer(minLength: 0)
            if circle.canGather {
                Button(state.gatherLabel) { together.send(TogetherIntentGather.shared) }
                    .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandRed).frame(minHeight: 44)
            } else if !circle.isHost {
                Button(state.waitLabel) { together.send(TogetherIntentWait.shared) }
                    .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandInk).frame(minHeight: 44)
            }
            Button { together.send(TogetherIntentToggleLog.shared) } label: {
                Image(systemName: "text.bubble").foregroundStyle(Color.brandInk).frame(width: 44, height: 44)
            }
            .accessibilityLabel(state.logLabel)
            Button { together.send(TogetherIntentShowTicket.shared) } label: {
                Image(systemName: "ticket").foregroundStyle(Color.brandInk).frame(width: 44, height: 44)
            }
            .accessibilityLabel(state.ticketTitle)
        }
    }

    private func stickers(_ state: TogetherUiState) -> some View {
        HStack(spacing: 4) {
            ForEach(state.stickers, id: \.key) { sticker in
                Button {
                    Haptics.tap()
                    together.send(TogetherIntentSendSticker(key: sticker.key, page: -1, x: 0, y: 0))
                } label: {
                    Text(sticker.glyph).font(.brandDisplay(12)).foregroundStyle(Color.brandInk)
                        .lineLimit(1).minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 6))
                }
                .accessibilityLabel(sticker.label)
            }
        }
    }

    private func lineField(_ state: TogetherUiState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                TextField("", text: $line, prompt: Text(state.linePlaceholder).foregroundColor(Color.brandInk.opacity(0.5)))
                    .font(.brandBody(15)).foregroundStyle(Color.brandInk)
                    .focused($lineFocused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .onChange(of: line) { _, text in together.send(TogetherIntentLineChanged(text: text)) }
                    .padding(.horizontal, 10).frame(minHeight: 44)
                    .background(Color.brandPaper2, in: RoundedRectangle(cornerRadius: 8))
                Button(state.sendLabel, action: send)
                    .font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandRed).frame(minHeight: 44)
            }
            if let error = state.lineError { Text(error).font(.brandBody(12)).foregroundStyle(Color.brandRed) }
        }
    }

    private func send() {
        together.send(TogetherIntentLineChanged(text: line))
        together.send(TogetherIntentSendLine.shared)
        if together.state.lineError == nil { line = "" }
    }

    private func balloons(_ state: TogetherUiState, _ circle: CircleUi) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(state.balloons, id: \.id) { balloon in
                HStack(alignment: .top, spacing: 6) {
                    Circle().fill(CircleInk.of(balloon.slot)).frame(width: 10, height: 10).padding(.top, 5)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(balloon.name).font(.brandBody(11, weight: .bold)).foregroundStyle(Color.brandInk.opacity(0.7))
                        Text(balloon.text).font(.brandBody(15)).foregroundStyle(Color.brandInk)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.brandInk, lineWidth: 1.5))
                }
                .transition(.opacity)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func log(_ circle: CircleUi) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(circle.log, id: \.id) { entry in
                    HStack(alignment: .top, spacing: 6) {
                        Circle().fill(entry.slot.map { CircleInk.of($0.int32Value) } ?? Color.brandMuted).frame(width: 8, height: 8).padding(.top, 5)
                        Text(entry.text).font(.brandBody(13)).foregroundStyle(Color.brandInk)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: 160)
    }
}

/// A reader's round hanko stamp: the initial in their ink; the host ringed red with a star; away dashed and faded; a cyan
/// ring while they ask the host to wait.
struct Hanko: View {
    let stamp: StampUi

    var body: some View {
        let ink = CircleInk.of(stamp.slot)
        ZStack(alignment: .topTrailing) {
            Text(stamp.initial)
                .font(.brandDisplay(16))
                .foregroundStyle(ink)
                .frame(width: 38, height: 38)
                .background(Color.brandInk, in: Circle())
                .overlay(
                    Circle().stroke(
                        stamp.asking ? Color.brandCyan : (stamp.host ? Color.brandRed : ink),
                        style: StrokeStyle(lineWidth: stamp.host || stamp.asking ? 3 : 2, dash: stamp.away ? [3, 3] : [])
                    )
                )
                .opacity(stamp.away ? 0.45 : 1)
            if stamp.host {
                Image(systemName: "star.fill").font(.system(size: 8, weight: .heavy)).foregroundStyle(Color.brandInk)
                    .frame(width: 15, height: 15)
                    .background(Color.brandRed, in: Circle())
                    .offset(x: 3, y: -3)
            }
        }
        .frame(width: 44, height: 44)
    }
}

/// The rail at the right edge: one dot per reader at their place in the chapter; tap a dot to go there.
struct TogetherRail: View {
    @ObservedObject var together: TogetherModel
    /// With the bars only: hidden bars leave every tap to the page, so a tap near the edge meant to show them never
    /// lands on a reader's dot and moves the strip (a host was sent back to the top of page 1).
    var interactive = true

    var body: some View {
        if let circle = together.state.circle, !circle.rail.isEmpty {
            GeometryReader { area in
                ZStack(alignment: .top) {
                    Capsule().fill(Color.brandPaper.opacity(0.18)).frame(width: 3)
                    ForEach(circle.rail, id: \.userId) { dot in
                        let size: CGFloat = dot.host ? 14 : 10
                        Button { together.send(TogetherIntentJumpTo(userId: dot.userId)) } label: {
                            Circle().fill(CircleInk.of(dot.slot))
                                .overlay(Circle().stroke(dot.me ? Color.brandPaper : Color.brandInk, lineWidth: 2))
                                .frame(width: size, height: size)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .offset(y: CGFloat(dot.share) * max(area.size.height - 44, 0))
                        .allowsHitTesting(interactive)
                        .accessibilityHidden(true)
                    }
                }
                .frame(width: 44)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.vertical, 80)
            .accessibilityLabel(L("together.rail"))
        }
    }
}

/// Stickers bursting at the reading line (offset by the sender's ink), or at the point given across the page; 0.6 →
/// 1.15 → 1 with speed lines, gone within 1.6 s; with Reduce Motion they only fade.
struct TogetherBursts: View {
    @ObservedObject var together: TogetherModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { area in
            ForEach(together.state.bursts, id: \.id) { burst in
                let x = burst.page >= 0 ? CGFloat(burst.x) : 0.22 + 0.08 * CGFloat(burst.slot)
                BurstGlyph(burst: burst, reduceMotion: reduceMotion)
                    .position(x: min(max(x, 0.08), 0.92) * area.size.width, y: area.size.height / 3)
            }
        }
        .allowsHitTesting(false)
    }
}

private struct BurstGlyph: View {
    let burst: BurstUi
    let reduceMotion: Bool
    @State private var scale: CGFloat = 0.6
    @State private var opacity: Double = 0

    var body: some View {
        let ink = CircleInk.of(burst.slot)
        ZStack {
            if !reduceMotion {
                ForEach(0..<8, id: \.self) { index in
                    Rectangle().fill(ink).frame(width: 2, height: 18)
                        .offset(y: -42)
                        .rotationEffect(.degrees(Double(index) * 45))
                }
            }
            Text(burst.glyph)
                .font(.brandDisplay(30))
                .foregroundStyle(Color.brandInk)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink, lineWidth: 3))
            if let count = burst.countLabel {
                Text(count).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandPaper)
                    .padding(4).background(Color.brandRed, in: Capsule())
                    .offset(x: 36, y: -24)
            }
        }
        .scaleEffect(reduceMotion ? 1 : scale)
        .opacity(opacity)
        .onAppear {
            if reduceMotion {
                withAnimation(.easeIn(duration: 0.2)) { opacity = 1 }
            } else {
                opacity = 1
                withAnimation(.spring(response: 0.18, dampingFraction: 0.5)) { scale = 1.15 }
                withAnimation(.easeOut(duration: 0.2).delay(0.18)) { scale = 1 }
            }
            withAnimation(.easeIn(duration: 0.4).delay(1.2)) { opacity = 0 }
        }
        .accessibilityHidden(true)
    }
}

/// One calm line at the top (joined, left, new host, wait, gather, moved on), read out politely by VoiceOver.
struct TogetherToast: View {
    @ObservedObject var together: TogetherModel

    var body: some View {
        VStack(spacing: 6) {
            if let toast = together.state.toast {
                HStack(spacing: 10) {
                    Text(toast.text).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk)
                    if let label = toast.actionLabel {
                        Button(label) { together.send(TogetherIntentToastTapped(id: toast.id)) }
                            .font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandRed).frame(minHeight: 44)
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Color.brandPaper, in: Capsule())
                .overlay(Capsule().stroke(Color.brandInk, lineWidth: 2))
                .transition(.move(edge: .top).combined(with: .opacity))
                .onAppear { UIAccessibility.post(notification: .announcement, argument: toast.text) }
                .id(toast.id)
            }
            if let offline = together.state.offline {
                Text(offline).font(.brandBody(13)).foregroundStyle(Color.brandPaper)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color.brandInk2, in: Capsule())
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

/// An invite link opened from outside: the circle's preview card, then Join (the reader opens there, following).
struct TogetherInviteView: View {
    @StateObject private var model: InviteModel
    let onJoined: (JoinedChapter) -> Void
    let onClose: () -> Void

    init(link: String, onJoined: @escaping (JoinedChapter) -> Void, onClose: @escaping () -> Void) {
        _model = StateObject(wrappedValue: InviteModel(link: link))
        self.onJoined = onJoined
        self.onClose = onClose
    }

    var body: some View {
        let state = model.state
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(state.title).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                Spacer()
                Button(state.closeLabel, action: onClose).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandMuted)
                    .frame(minHeight: 44)
            }
            if state.loading { ProgressView().tint(Color.brandCyan) }
            if let preview = state.preview {
                VStack(alignment: .leading, spacing: 6) {
                    Text(preview.code).font(.system(size: 30, weight: .heavy, design: .monospaced)).foregroundStyle(Color.brandInk)
                    Text(preview.chapterLine).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandInk)
                    Text(preview.hostLine).font(.brandBody(14)).foregroundStyle(Color.brandInk.opacity(0.75))
                    Text(preview.presentLine).font(.brandBody(14)).foregroundStyle(Color.brandInk.opacity(0.75))
                    if let blocked = preview.blocked { Text(blocked).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandRed) }
                }
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.brandPaper, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.brandInk, style: StrokeStyle(lineWidth: 2, dash: [7, 5])).padding(6))
            }
            if let error = state.error { ErrorText(text: error) }
            Button(state.joinLabel) { model.send(InviteIntentJoin.shared) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!state.canJoin)
            Spacer()
        }
        .padding(20)
        .background(Color.brandInk2)
        .task { await model.observe() }
        .onChange(of: state.joined) { _, joined in
            guard let joined else { return }
            model.send(InviteIntentOpened.shared)
            onJoined(joined)
        }
    }
}

@MainActor
final class InviteModel: ObservableObject {
    @Published private(set) var state: InviteUiState
    private let owner = ViewModels()
    private let viewModel: TogetherInviteViewModel

    init(link: String) {
        viewModel = owner.togetherInvite(link: link)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: InviteIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// An invite link to show (`vovoscan://together/CODE`): `Identifiable` for `.sheet(item:)`.
struct InviteLink: Identifiable {
    let url: String
    var id: String { url }

    /// Only the app's own scheme and its `together` path.
    init?(_ url: URL) {
        guard url.scheme == "vovoscan", url.host() == "together" else { return nil }
        self.url = url.absoluteString
    }
}
