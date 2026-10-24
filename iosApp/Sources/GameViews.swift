import Shared
import SwiftUI

/// A game (shared `GameViewModel`), a group's settings (`GroupAdminViewModel`) and the inbox (`NotificationsViewModel`):
/// the same screens as Android (`ui/groups/GameScreen.kt`, `GroupAdminScreen.kt`, `ui/notifications`).

@MainActor
final class GameModel: ObservableObject {
    @Published private(set) var state: GameUiState
    private let owner = ViewModels()
    private let viewModel: GameViewModel

    init(gameId: String) {
        viewModel = owner.game(gameId: gameId)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: GameIntent) { viewModel.onIntent(intent: intent) }

    func canDraw(horizontal: Bool, row: Int, col: Int) -> Bool {
        viewModel.canDraw(horizontal: horizontal, row: Int32(row), col: Int32(col))
    }

    deinit { owner.clear() }
}

@MainActor
final class GroupAdminModel: ObservableObject {
    @Published private(set) var state: GroupAdminUiState
    private let owner = ViewModels()
    private let viewModel: GroupAdminViewModel

    init(groupId: String) {
        viewModel = owner.groupAdmin(groupId: groupId)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: GroupAdminIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// The seats' inks, the same on every board and in the web's games.
func seatInk(_ seat: Int32) -> Color {
    switch seat {
    case 0: Color.brandRed
    case 1: Color.brandCyan
    case 2: Color.brandYellow
    default: Color.brandMuted
    }
}

// MARK: - A game

struct GameView: View {
    @StateObject private var model: GameModel

    init(gameId: String) { _model = StateObject(wrappedValue: GameModel(gameId: gameId)) }

    var body: some View {
        let state = model.state
        Group {
            if state.loading && state.kind == nil {
                ProgressView().tint(Color.brandCyan).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(spacing: 14) {
                        Text(state.status).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper).multilineTextAlignment(.center)
                        GamePlayers(players: state.players)
                        board(state).frame(maxWidth: 520)
                        if let step = state.replayStep?.int32Value { GameReplay(step: step, state: state, send: model.send) }
                        if let error = state.error { ErrorText(text: error) }
                        if state.canJoin {
                            Button(L("games.take")) { model.send(GameIntentJoin.shared) }
                                .buttonStyle(PrimaryButtonStyle()).disabled(state.sending)
                        }
                        if state.canLeave {
                            Button(state.leaveLabel) { model.send(GameIntentLeave.shared) }.buttonStyle(GhostButtonStyle())
                        }
                        Text(state.rules).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        if !state.leaderboard.isEmpty { Leaderboard(rows: state.leaderboard) }
                    }
                    .padding(16)
                    .readableWidth()
                }
            }
        }
        .background(Color.brandInk)
        .navigationTitle(state.title.isEmpty ? L("games.title") : state.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let clock = state.clock {
                ToolbarItem(placement: .topBarTrailing) {
                    Text(clock).font(.brandBody(16, weight: .bold)).monospacedDigit()
                        .foregroundStyle(state.urgent ? Color.brandRed : Color.brandPaper)
                }
            }
        }
        .onAppear { model.send(GameIntentStart.shared) }
        .onDisappear { model.send(GameIntentStop.shared) }
        .task { await model.observe() }
    }

    @ViewBuilder
    private func board(_ state: GameUiState) -> some View {
        switch onEnum(of: state.board) {
        case let .four(four): FourBoard(board: four, state: state, send: model.send)
        case let .panels(panels): PanelsBoard(board: panels, state: state, model: model)
        case .clash: ClashBoard(state: state, send: model.send)
        case .unknown: Text(L("games.unknown")).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
        }
    }
}

private struct GamePlayers: View {
    let players: [PlayerUi]

    var body: some View {
        HStack(spacing: 8) {
            ForEach(players, id: \.userId) { p in
                VStack(spacing: 4) {
                    Circle().fill(seatInk(p.seat)).frame(width: 14, height: 14).overlay(Circle().stroke(Color.brandInk, lineWidth: 2))
                    Text(p.name).font(.brandBody(13, weight: p.you ? .bold : .regular)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    if let score = p.score { Text(score).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper) }
                    if let rank = p.rank {
                        Text([rank, p.ratingChange].compactMap { $0 }.joined(separator: " ✦ "))
                            .font(.brandBody(12)).foregroundStyle(Color.brandYellow)
                    }
                }
                .padding(10)
                .frame(maxWidth: .infinity)
                .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(p.toPlay ? seatInk(p.seat) : Color.brandLine, lineWidth: p.toPlay ? 2 : 1))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// Ink Four: 7 columns, a tap on a column drops a disc where it lands.
private struct FourBoard: View {
    let board: GameStateFour
    let state: GameUiState
    let send: (GameIntent) -> Void

    var body: some View {
        let cols = Int(GameStateFour.companion.COLS)
        let rows = Int(GameStateFour.companion.ROWS)
        let line = Set(board.line.map { Cell(row: Int($0.first?.int32Value ?? -1), col: Int($0.second?.int32Value ?? -1)) })
        let last = board.last.map { Cell(row: Int($0.first?.int32Value ?? -1), col: Int($0.second?.int32Value ?? -1)) }
        let drops = Set(state.dropColumns.map { Int($0.int32Value) })
        GeometryReader { geo in
            let cell = geo.size.width / CGFloat(cols)
            Canvas { context, _ in
                let r = cell * 0.38
                for row in 0 ..< rows {
                    for col in 0 ..< cols {
                        let seat = board.board[safe: row]?[safe: col]?.int32Value ?? -1
                        let center = CGPoint(x: CGFloat(col) * cell + cell / 2, y: CGFloat(row) * cell + cell / 2)
                        context.fill(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)),
                                     with: .color(seat < 0 ? Color.brandInk : seatInk(seat)))
                        if line.contains(Cell(row: row, col: col)) {
                            let o = r + 3
                            context.stroke(Path(ellipseIn: CGRect(x: center.x - o, y: center.y - o, width: 2 * o, height: 2 * o)),
                                           with: .color(Color.brandPaper), lineWidth: 3)
                        }
                        if last == Cell(row: row, col: col) {
                            let d = r * 0.25
                            context.fill(Path(ellipseIn: CGRect(x: center.x - d, y: center.y - d, width: 2 * d, height: 2 * d)), with: .color(Color.brandInk))
                        }
                    }
                }
                // The columns a tap may drop in now, marked at the top.
                for col in drops {
                    var mark = Path()
                    mark.move(to: CGPoint(x: CGFloat(col) * cell + cell * 0.3, y: 3))
                    mark.addLine(to: CGPoint(x: CGFloat(col) * cell + cell * 0.7, y: 3))
                    context.stroke(mark, with: .color(Color.brandPaper.opacity(0.5)), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { at in
                let col = Int(floor(at.x / cell))
                guard drops.contains(col), !state.sending else { return }
                Haptics.tap()
                send(GameIntentDrop(col: Int32(col)))
            }
        }
        .aspectRatio(CGFloat(cols) / CGFloat(rows), contentMode: .fit)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandPaper, lineWidth: 2))
        .accessibilityLabel(state.status)
    }

    private struct Cell: Hashable {
        let row: Int
        let col: Int
    }
}

/// Panels: dots on a grid, a tap draws the edge nearest to it, closed boxes take their player's ink.
private struct PanelsBoard: View {
    let board: GameStatePanels
    let state: GameUiState
    let model: GameModel
    private let padShare: CGFloat = 0.06

    var body: some View {
        let n = Int(board.size)
        GeometryReader { geo in
            let pad = geo.size.width * padShare
            let step = (geo.size.width - 2 * pad) / CGFloat(max(n, 1))
            Canvas { context, _ in
                let stroke: CGFloat = 5
                for row in 0 ..< n {
                    for col in 0 ..< n {
                        let owner = board.boxes[safe: row]?[safe: col]?.int32Value ?? -1
                        guard owner >= 0 else { continue }
                        let rect = CGRect(x: pad + CGFloat(col) * step + stroke, y: pad + CGFloat(row) * step + stroke,
                                          width: step - 2 * stroke, height: step - 2 * stroke)
                        context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(seatInk(owner).opacity(0.75)))
                    }
                }
                func edge(_ from: CGPoint, _ to: CGPoint, seat: Int32?, last: Bool) {
                    var p = Path()
                    p.move(to: from)
                    p.addLine(to: to)
                    let drawn = (seat ?? -1) >= 0
                    let color = !drawn ? Color.brandLine : last ? Color.brandPaper : seatInk(seat ?? -1)
                    context.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: drawn ? stroke : stroke * 0.4, lineCap: .round))
                }
                for row in 0 ... n {
                    for col in 0 ..< n {
                        let isLast = board.last.map { $0.horizontal && Int($0.row) == row && Int($0.col) == col } ?? false
                        edge(CGPoint(x: pad + CGFloat(col) * step, y: pad + CGFloat(row) * step),
                             CGPoint(x: pad + CGFloat(col + 1) * step, y: pad + CGFloat(row) * step),
                             seat: board.h[safe: row]?[safe: col]?.int32Value, last: isLast)
                    }
                }
                for row in 0 ..< n {
                    for col in 0 ... n {
                        let isLast = board.last.map { !$0.horizontal && Int($0.row) == row && Int($0.col) == col } ?? false
                        edge(CGPoint(x: pad + CGFloat(col) * step, y: pad + CGFloat(row) * step),
                             CGPoint(x: pad + CGFloat(col) * step, y: pad + CGFloat(row + 1) * step),
                             seat: board.v[safe: row]?[safe: col]?.int32Value, last: isLast)
                    }
                }
                let dot = stroke * 0.8
                for row in 0 ... n {
                    for col in 0 ... n {
                        let c = CGPoint(x: pad + CGFloat(col) * step, y: pad + CGFloat(row) * step)
                        context.fill(Path(ellipseIn: CGRect(x: c.x - dot, y: c.y - dot, width: 2 * dot, height: 2 * dot)), with: .color(Color.brandPaper))
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { at in
                guard state.canDraw, !state.sending else { return }
                let x = (at.x - pad) / step
                let y = (at.y - pad) / step
                // The nearer grid line wins: a horizontal edge when the tap is closer to a row of dots.
                let horizontal = abs(y - y.rounded()) <= abs(x - x.rounded())
                let row = horizontal ? Int(y.rounded()) : Int(floor(y))
                let col = horizontal ? Int(floor(x)) : Int(x.rounded())
                guard model.canDraw(horizontal: horizontal, row: row, col: col) else { return }
                Haptics.tap()
                model.send(GameIntentDraw(horizontal: horizontal, row: Int32(row), col: Int32(col)))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel(state.status)
    }
}

/// Sticker Clash: the three picks, each seat's state this round, the rounds so far.
private struct ClashBoard: View {
    let state: GameUiState
    let send: (GameIntent) -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                ForEach(Array(state.seatPicks.enumerated()), id: \.offset) { seat, pick in
                    Text(pick).font(.brandBody(16, weight: .black)).foregroundStyle(Color.brandPaper)
                        .padding(12).frame(maxWidth: .infinity)
                        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(seatInk(Int32(seat)), lineWidth: 1))
                }
            }
            if !state.picks.isEmpty {
                HStack(spacing: 8) {
                    ForEach(state.picks, id: \.glyph) { p in
                        Button {
                            Haptics.press()
                            send(GameIntentPick(pick: p.pick))
                        } label: {
                            VStack(spacing: 2) {
                                Text(p.glyph).font(.brandDisplay(22))
                                Text(p.label).font(.brandBody(11))
                            }
                            .foregroundStyle(p.enabled ? Color.brandInk : Color.brandMuted)
                            .padding(.vertical, 16).frame(maxWidth: .infinity)
                            .background(p.enabled ? Color.brandPaper : Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandInk, lineWidth: 2))
                        }
                        .buttonStyle(.plain)
                        .disabled(!p.enabled || state.sending)
                        .accessibilityLabel("\(p.glyph), \(p.label)")
                    }
                }
            }
            ForEach(state.rounds.reversed(), id: \.number) { round in
                HStack(spacing: 10) {
                    Text("\(round.number)").font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
                    Text(round.picks.joined(separator: " · ")).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
                    Spacer()
                    Text(round.line).font(.brandBody(12)).foregroundStyle(Color.brandPaper)
                }
            }
        }
    }
}

/// A finished game, move by move.
private struct GameReplay: View {
    let step: Int32
    let state: GameUiState
    let send: (GameIntent) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let label = state.replayLabel { Text(label).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper) }
            Slider(
                value: Binding(get: { Double(step) }, set: { send(GameIntentReplayTo(step: Int32($0.rounded()))) }),
                in: 0 ... Double(max(state.replaySteps, 1)),
                step: 1
            )
            .tint(Color.brandRed)
        }
    }
}

private struct Leaderboard: View {
    let rows: [StandingUi]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("games.leaderboard").uppercased()).font(.brandBody(11, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandMuted)
            ForEach(Array(rows.enumerated()), id: \.offset) { i, s in
                HStack(spacing: 10) {
                    Text("\(i + 1)").font(.brandBody(13, weight: .bold)).foregroundStyle(i == 0 ? Color.brandYellow : Color.brandMuted)
                    Text(s.name).font(.brandBody(14)).foregroundStyle(Color.brandPaper).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    Text(s.record).font(.brandBody(12)).foregroundStyle(Color.brandPaper)
                    Text(s.rating).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
                }
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(s.you ? Color.brandInk2 : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

// MARK: - A group's settings

struct GroupAdminView: View {
    @StateObject private var model: GroupAdminModel
    @Environment(\.pop) private var pop
    @State private var confirm: (userId: String, action: MemberActionUi)?

    init(groupId: String) { _model = StateObject(wrappedValue: GroupAdminModel(groupId: groupId)) }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(state.tabs.enumerated()), id: \.offset) { _, pair in
                        if let tab = pair.first as AdminTab? {
                            Choice(label: pair.second.map { $0 as String } ?? "", chosen: state.tab == tab) {
                                model.send(GroupAdminIntentSelectTab(tab: tab))
                            }
                            .fixedSize()
                        }
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
            if state.loading {
                ProgressView().tint(Color.brandCyan).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if let error = state.error { ErrorText(text: error) }
                        switch state.tab {
                        case .settings: AdminSettings(state: state, send: model.send)
                        case .members, .requests, .banned: AdminMembers(state: state, send: model.send) { confirm = ($0, $1) }
                        case .reports: AdminReports(state: state, send: model.send)
                        case .badge: AdminBadge(state: state, send: model.send)
                        }
                    }
                    .padding(16)
                    .readableWidth()
                }
            }
        }
        .background(Color.brandInk)
        .navigationTitle(L("groups.admin.title"))
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            if let toast = state.toast {
                Text(toast).font(.brandBody(14)).foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 16).padding(.vertical, 10).background(Color.brandPaper, in: Capsule())
                    .padding(.bottom, 48)
                    .task {
                        try? await Task.sleep(for: .seconds(2.5))
                        model.send(GroupAdminIntentToastShown.shared)
                    }
            }
        }
        .alert(confirm?.action.label ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })) {
            if let confirm {
                Button(confirm.action.label, role: .destructive) {
                    model.send(GroupAdminIntentAct(userId: confirm.userId, action: confirm.action.action))
                    self.confirm = nil
                }
            }
            Button(L("common.cancel"), role: .cancel) { confirm = nil }
        } message: { Text(confirm?.action.confirm ?? "") }
        .alert(L("groups.admin.deleteGroup"), isPresented: Binding(get: { state.deleteConfirm }, set: { if !$0 { model.send(GroupAdminIntentCancelDelete.shared) } })) {
            Button(L("common.delete"), role: .destructive) { model.send(GroupAdminIntentConfirmDelete.shared) }
            Button(L("common.cancel"), role: .cancel) { model.send(GroupAdminIntentCancelDelete.shared) }
        } message: { Text(L("groups.admin.deleteConfirm")) }
        .onChange(of: state.closed) { _, closed in if closed { pop?() } }
        .task { await model.observe() }
    }
}

private struct AdminSettings: View {
    let state: GroupAdminUiState
    let send: (GroupAdminIntent) -> Void
    @State private var name = ""
    @State private var handle = ""
    @State private var about = ""

    var body: some View {
        let form = state.settings
        IntentField(label: L("groups.create.name"), text: $name, value: form.name) { edit(form.with(name: $0)) }
        if state.owner {
            IntentField(label: L("groups.create.handle"), text: $handle, value: form.handle, help: L("groups.create.handleRule")) { edit(form.with(handle: $0)) }
        }
        IntentField(label: L("groups.create.about"), text: $about, value: form.about) { edit(form.with(about: $0)) }
        EntryChoice(entry: form.entry) { edit(form.with(entry: $0)) }
        SwitchRow(label: L("groups.create.listed"), help: L("groups.create.listedHint"), isOn: form.listed) { edit(form.with(listed: !form.listed)) }
        Text(L("groups.admin.accent")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
        HStack(spacing: 10) {
            ForEach(state.accents.map(\.int32Value), id: \.self) { accent in
                Button {
                    Haptics.select()
                    edit(form.with(accent: accent))
                } label: {
                    Circle().fill(CircleInk.of(accent)).frame(width: 34, height: 34)
                        .overlay(Circle().stroke(form.accent == accent ? Color.brandPaper : Color.brandInk, lineWidth: form.accent == accent ? 3 : 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(L("groups.admin.accent")) \(accent + 1)")
                .accessibilityAddTraits(form.accent == accent ? .isSelected : [])
            }
        }
        if let error = form.error { ErrorText(text: error) }
        Button(L("common.save")) {
            submitAfterCommittingInput {
                edit(form.with(name: name, handle: state.owner ? handle : form.handle, about: about))
                send(GroupAdminIntentSaveSettings.shared)
            }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(!form.canSave)
        if state.owner {
            Button { send(GroupAdminIntentAskDelete.shared) } label: {
                Text(L("groups.admin.deleteGroup")).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandDanger)
                    .frame(maxWidth: .infinity).padding(14)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandDanger, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(.top, 12)
        }
    }

    private func edit(_ form: SettingsForm) { send(GroupAdminIntentEditSettings(form: form)) }
}

extension SettingsForm {
    func with(name: String? = nil, handle: String? = nil, about: String? = nil, entry: Entry? = nil, listed: Bool? = nil, accent: Int32? = nil) -> SettingsForm {
        doCopy(name: name ?? self.name, handle: handle ?? self.handle, about: about ?? self.about, entry: entry ?? self.entry,
               listed: listed ?? self.listed, accent: accent ?? self.accent, saving: saving, saved: false, error: nil)
    }
}

private struct AdminMembers: View {
    let state: GroupAdminUiState
    let send: (GroupAdminIntent) -> Void
    let onConfirm: (String, MemberActionUi) -> Void
    @State private var search = ""

    var body: some View {
        if state.tab == .members {
            IntentField(label: L("groups.admin.searchMembers"), text: $search, value: state.search) { send(GroupAdminIntentSearch(text: $0)) }
        }
        if state.members.isEmpty {
            Text(L(emptyKey)).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
        }
        ForEach(state.members, id: \.userId) { m in AdminMemberRow(member: m, send: send, onConfirm: onConfirm) }
        if state.hasMore {
            Button(L("groups.chat.older")) { send(GroupAdminIntentLoadMore.shared) }.buttonStyle(GhostButtonStyle())
        }
    }

    private var emptyKey: String {
        switch state.tab {
        case .requests: "groups.admin.noRequests"
        case .banned: "groups.admin.noBanned"
        default: "groups.admin.noMembers"
        }
    }
}

private struct AdminMemberRow: View {
    let member: MemberRowUi
    let send: (GroupAdminIntent) -> Void
    let onConfirm: (String, MemberActionUi) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Circle().fill(CircleInk.of(member.slot)).frame(width: 28, height: 28)
                    .overlay(Text(member.name.prefix(1).uppercased()).font(.brandBody(13, weight: .black)).foregroundStyle(Color.brandInk))
                    .overlay(Circle().stroke(Color.brandInk, lineWidth: 2))
                VStack(alignment: .leading, spacing: 2) {
                    Text(member.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    Text([member.detail, member.since].compactMap { $0 }.joined(separator: " ✦ "))
                        .font(.brandBody(12)).foregroundStyle(Color.brandMuted).lineLimit(2)
                }
            }
            .accessibilityElement(children: .combine)
            if !member.actions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(member.actions, id: \.label) { action in
                            Button {
                                if action.confirm != nil {
                                    onConfirm(member.userId, action)
                                } else {
                                    send(GroupAdminIntentAct(userId: member.userId, action: action.action))
                                }
                            } label: {
                                Text(action.label).font(.brandBody(13, weight: .bold))
                                    .foregroundStyle(action.destructive ? Color.brandDanger : Color.brandPaper)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(action.destructive ? Color.brandDanger : Color.brandLine, lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.brandLine, lineWidth: 1))
    }
}

private struct AdminReports: View {
    let state: GroupAdminUiState
    let send: (GroupAdminIntent) -> Void

    var body: some View {
        if state.reports.isEmpty {
            Text(L("groups.admin.noReports")).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
        }
        ForEach(state.reports, id: \.messageId) { r in
            VStack(alignment: .leading, spacing: 8) {
                Text(r.excerpt).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(4)
                Text(r.held ? "\(r.detail) ✦ \(L("groups.admin.held"))" : r.detail).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                HStack(spacing: 8) {
                    Choice(label: L("groups.actions.remove"), chosen: false) { send(GroupAdminIntentRemoveReported(messageId: r.messageId)) }
                    Choice(label: L("groups.actions.restore"), chosen: false) { send(GroupAdminIntentRestoreReported(messageId: r.messageId)) }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(r.held ? Color.brandYellow : Color.brandLine, lineWidth: 1))
        }
    }
}

private struct AdminBadge: View {
    let state: GroupAdminUiState
    let send: (GroupAdminIntent) -> Void
    @State private var reason = ""
    @State private var links = ""

    var body: some View {
        let badge = state.badge
        HStack(spacing: 8) {
            VerifiedMark(label: L("groups.badge.title"))
            Text(L("groups.badge.title")).font(.brandBody(17, weight: .bold)).foregroundStyle(Color.brandPaper)
        }
        Text(L("groups.badge.lead")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
        if let latest = badge.latest { Text(latest).font(.brandBody(14)).foregroundStyle(Color.brandYellow) }
        ForEach(badge.checks, id: \.label) { c in
            HStack(spacing: 10) {
                Text(c.passed ? "✓" : "✕").font(.brandBody(14, weight: .bold)).foregroundStyle(c.passed ? Color.brandCyan : Color.brandDanger)
                Text(c.label).font(.brandBody(13)).foregroundStyle(Color.brandPaper).frame(maxWidth: .infinity, alignment: .leading)
                Text(c.value).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(c.label): \(c.value)")
        }
        if badge.pending {
            Button(L("groups.badge.withdraw")) { send(GroupAdminIntentWithdrawBadge.shared) }.buttonStyle(GhostButtonStyle())
        } else if badge.eligible {
            IntentField(label: L("groups.badge.reason"), text: $reason, value: badge.reason, help: badge.reasonHint) { send(GroupAdminIntentBadgeReason(text: $0)) }
            IntentField(label: L("groups.badge.links"), text: $links, value: badge.links) { send(GroupAdminIntentBadgeLinks(text: $0)) }
            Button(L("groups.badge.ask")) {
                submitAfterCommittingInput {
                    send(GroupAdminIntentBadgeReason(text: reason))
                    send(GroupAdminIntentBadgeLinks(text: links))
                    send(GroupAdminIntentAskBadge.shared)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!badge.canAsk)
        }
    }
}

// MARK: - The inbox

struct NotificationsView: View {
    @StateObject private var model = NotificationsModel()
    @Environment(\.push) private var push
    @State private var settings = false

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            if let notice = state.notice { OfflineNotice(text: notice) { model.send(NotificationsIntentRefresh.shared) }.padding(.horizontal, 16).padding(.vertical, 6) }
            if let error = state.error { ErrorText(text: error).padding(.horizontal, 16) }
            if state.loading && state.rows.isEmpty {
                ProgressView().tint(Color.brandCyan).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if state.rows.isEmpty {
                Text(state.empty).font(.brandBody(14)).foregroundStyle(Color.brandMuted).padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(state.rows, id: \.id) { row in
                            Button { model.send(NotificationsIntentOpen(id: row.id)) } label: { NotificationItem(row: row) }
                                .buttonStyle(PressableCardStyle())
                                .accessibilityIdentifier("notifications.row.\(row.id)")
                        }
                        if state.hasMore {
                            ProgressView().tint(Color.brandCyan).padding(12)
                                .onAppear { model.send(NotificationsIntentLoadMore.shared) }
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .readableWidth()
                }
            }
        }
        .background(Color.brandInk)
        .navigationTitle(L("notifications.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if state.unread > 0 {
                    Button(L("notifications.readAll")) { model.send(NotificationsIntentReadAll.shared) }.foregroundStyle(Color.brandCyan)
                }
                Button {
                    model.send(NotificationsIntentLoadPreferences.shared)
                    settings = true
                } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel(L("notifications.settings"))
            }
        }
        .sheet(isPresented: $settings) { NotificationSettingsSheet(model: model) }
        .onChange(of: state.open?.seq) { _, _ in
            guard let open = state.open else { return }
            let target = open.target
            if let group = target.groupId ?? target.handle.map({ "@\($0)" }) {
                push(.group(group))
                if let game = target.gameId { push(.game(game)) }
            }
            model.send(NotificationsIntentOpenHandled(seq: open.seq))
        }
        .onAppear { model.send(NotificationsIntentRefresh.shared) }
        .task { await model.observe() }
    }
}

private struct NotificationItem: View {
    let row: NotificationRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 8).fill(iconInk).frame(width: 34, height: 34)
                .overlay {
                    if row.icon == "badge" {
                        Text("✓").font(.brandBody(15, weight: .black)).foregroundStyle(Color.brandInk)
                    } else {
                        Image(systemName: row.icon == "game" ? "gamecontroller" : "person.3").font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Color.brandInk)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandPaper).lineLimit(1)
                Text(row.body).font(.brandBody(14)).foregroundStyle(Color.brandPaper).lineLimit(3).multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 6) {
                Text(row.age).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                if row.unread { Circle().fill(Color.brandRed).frame(width: 8, height: 8) }
            }
        }
        .padding(12)
        .background(row.unread ? Color.brandInk2 : Color.brandInk, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(row.unread ? Color.brandRed : Color.brandLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private var iconInk: Color {
        switch row.icon {
        case "game": Color.brandCyan
        case "badge": Color.brandYellow
        default: Color.brandPaper
        }
    }
}

private struct NotificationSettingsSheet: View {
    @ObservedObject var model: NotificationsModel

    var body: some View {
        let state = model.state
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("notifications.settings")).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                SwitchRow(label: L("notifications.push"), help: state.pushLine, isOn: state.prefs.push) {
                    model.send(NotificationsIntentSetPush(on: !state.prefs.push))
                }
                ForEach(state.prefs.kinds, id: \.kind) { k in
                    SwitchRow(label: k.label, help: nil, isOn: k.on) { model.send(NotificationsIntentSetKind(kind: k.kind, on: !k.on)) }
                }
            }
            .padding(20)
        }
        .background(Color.brandInk2)
        .presentationDetents([.medium, .large])
        .presentationBackground(Color.brandInk2)
    }
}
