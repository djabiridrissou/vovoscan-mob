import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI
import UIKit

/// Groups (shared `GroupsViewModel`, `GroupViewModel`): the tab with the caller's groups, search or a pasted link,
/// Discover and "New group"; a group's card for visitors, its live chat for members, sharing with a QR code, and a new
/// game. The same screens as Android (`ui/groups`); games, settings and the inbox are in `GameViews.swift`.

@MainActor
final class GroupsModel: ObservableObject {
    @Published private(set) var state: GroupsUiState
    private let owner = ViewModels()
    private let viewModel: GroupsViewModel

    init() {
        viewModel = owner.groups()
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: GroupsIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

@MainActor
final class GroupModel: ObservableObject {
    @Published private(set) var state: GroupUiState
    private let owner = ViewModels()
    private let viewModel: GroupViewModel

    init(ref: String) {
        viewModel = owner.group(ref: ref)
        state = viewModel.state.value
    }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: GroupIntent) { viewModel.onIntent(intent: intent) }

    deinit { owner.clear() }
}

/// The inbox, one for the app: the bell, the screen and the root share it (never cleared by a screen).
@MainActor
final class NotificationsModel: ObservableObject {
    @Published private(set) var state: NotificationsUiState
    private let viewModel = ViewModels().notifications()

    init() { state = viewModel.state.value }

    func observe() async {
        for await next in viewModel.state { state = next }
    }

    func send(_ intent: NotificationsIntent) { viewModel.onIntent(intent: intent) }
}

// MARK: - The tab

struct GroupsView: View {
    /// The root's: it also decides whether the tab shows.
    @ObservedObject var model: GroupsModel
    @StateObject private var inbox = NotificationsModel()
    @Environment(\.push) private var push
    @State private var query = ""

    var body: some View {
        let state = model.state
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                HStack {
                    Kicker(text: L("groups.title"))
                    Spacer()
                    Bell(badge: inbox.state.badge, unread: Int(inbox.state.unread)) { push(.notifications) }
                }
                BrandField(label: L("common.search"), text: $query, placeholder: L("groups.searchPlaceholder"))
                    .onChange(of: query) { _, text in model.send(GroupsIntentQuery(text: text)) }
                if let notice = state.notice { OfflineNotice(text: notice) { model.send(GroupsIntentRefresh.shared) } }
                if let error = state.error { ErrorText(text: error) }
                Button(L("groups.create.open")) { model.send(GroupsIntentShowCreate.shared) }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.bottom, 6)
                if state.discovering {
                    SectionTitle(text: L("groups.mine"))
                    if !state.loading && state.mine.isEmpty {
                        Text(state.emptyMine).font(.brandBody(14)).foregroundStyle(Color.brandMuted)
                    }
                    ForEach(state.mine, id: \.id) { row in card(row) }
                }
                SectionTitle(text: state.resultsTitle)
                ForEach(state.results, id: \.id) { row in card(row) }
                if let empty = state.emptyResults { Text(empty).font(.brandBody(14)).foregroundStyle(Color.brandMuted) }
                if state.searching {
                    ProgressView().tint(Color.brandCyan).frame(maxWidth: .infinity).padding(12)
                } else if !state.resultsEnd && !state.results.isEmpty {
                    Color.clear.frame(height: 1).onAppear { model.send(GroupsIntentLoadMore.shared) }
                }
            }
            .padding(20)
            .readableWidth()
        }
        .background(Color.brandInk)
        .navigationBarHidden(true)
        .sheet(isPresented: Binding(get: { state.create != nil }, set: { if !$0 { model.send(GroupsIntentCancelCreate.shared) } })) {
            if let form = state.create { CreateGroupSheet(form: form, send: model.send) }
        }
        .onChange(of: state.open?.seq) { _, _ in
            guard let open = state.open else { return }
            push(.group(open.groupId))
            model.send(GroupsIntentOpenHandled(seq: open.seq))
        }
        .onAppear {
            model.send(GroupsIntentStart.shared)
            AppDelegate.askOnce()
        }
        .onDisappear { model.send(GroupsIntentStop.shared) }
        .task { await inbox.observe() }
    }

    private func card(_ row: GroupRow) -> some View {
        Button { push(.group(row.id)) } label: { GroupCard(row: row) }
            .buttonStyle(PressableCardStyle())
            .accessibilityIdentifier("groups.row.\(row.id)")
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.brandBody(11, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandMuted).padding(.top, 10)
    }
}

/// The inbox's bell, with its count in red.
struct Bell: View {
    let badge: String?
    let unread: Int
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "bell").font(.system(size: 20, weight: .semibold)).foregroundStyle(Color.brandPaper)
                    .frame(width: 44, height: 44)
                if let badge {
                    Text(badge).font(.brandBody(10, weight: .bold)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Color.brandRed, in: Capsule())
                        .offset(x: -2, y: 4)
                }
            }
        }
        .accessibilityLabel(unread > 0 ? L("notifications.unreadA11y", ["count": unread]) : L("notifications.title"))
        .accessibilityIdentifier("groups.bell")
    }
}

/// A group's emblem: its first letter on its colour, a hard paper offset like the brand's buttons.
struct GroupEmblem: View {
    let name: String
    let accent: Int32
    var size: CGFloat = 48

    var body: some View {
        Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
            .font(.brandDisplay(size * 0.42))
            .foregroundStyle(Color.brandInk)
            .frame(width: size - 3, height: size - 3)
            .background(CircleInk.of(accent), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.brandInk, lineWidth: 2))
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.brandPaper).offset(x: 3, y: 3))
            .frame(width: size, height: size, alignment: .topLeading)
            .accessibilityHidden(true)
    }
}

/// The blue badge: a verified group.
struct VerifiedMark: View {
    let label: String
    var body: some View {
        Image(systemName: "checkmark.seal.fill").font(.system(size: 14)).foregroundStyle(Color(hex: 0x3B82F6))
            .accessibilityLabel(label)
    }
}

private struct GroupCard: View {
    let row: GroupRow

    var body: some View {
        HStack(spacing: 12) {
            GroupEmblem(name: row.name, accent: row.accent)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.name).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper).lineLimit(1)
                    if row.verified { VerifiedMark(label: L("groups.verified")) }
                }
                Text(row.meta).font(.brandBody(12)).foregroundStyle(Color.brandMuted).lineLimit(1)
                Text(row.line).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.8)).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 6) {
                if let unread = row.unread {
                    Text(unread).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 7).padding(.vertical, 2).background(Color.brandRed, in: Capsule())
                }
                if let pending = row.pending { Text(pending).font(.brandBody(11, weight: .bold)).foregroundStyle(Color.brandYellow) }
            }
        }
        .padding(14)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(row.unread != nil ? Color.brandRed : Color.brandLine, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.a11y)
    }
}

private struct CreateGroupSheet: View {
    let form: CreateForm
    let send: (GroupsIntent) -> Void
    @State private var name = ""
    @State private var handle = ""
    @State private var about = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(L("groups.create.title")).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                IntentField(label: L("groups.create.name"), text: $name, value: form.name) { edit(name: $0) }
                IntentField(label: L("groups.create.handle"), text: $handle, value: form.handle, help: form.handleHint) { edit(handle: $0) }
                IntentField(label: L("groups.create.about"), text: $about, value: form.about) { edit(about: $0) }
                EntryChoice(entry: form.entry) { send(GroupsIntentEditCreate(form: form.doCopy(entry: $0))) }
                SwitchRow(label: L("groups.create.listed"), help: L("groups.create.listedHint"), isOn: form.listed) {
                    send(GroupsIntentEditCreate(form: form.doCopy(listed: !form.listed)))
                }
                if let error = form.error { ErrorText(text: error) }
                Button(L("groups.create.submit")) {
                    submitAfterCommittingInput {
                        send(GroupsIntentEditCreate(form: form.doCopy(name: name, handle: handle, about: about)))
                        send(GroupsIntentSubmitCreate.shared)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!form.canSubmit)
                Button(L("common.cancel")) { send(GroupsIntentCancelCreate.shared) }.buttonStyle(GhostButtonStyle())
            }
            .padding(20)
        }
        .background(Color.brandInk2)
        .presentationDetents([.large])
        .presentationBackground(Color.brandInk2)
    }

    private func edit(name: String? = nil, handle: String? = nil, about: String? = nil) {
        send(GroupsIntentEditCreate(form: form.doCopy(name: name ?? form.name, handle: handle ?? form.handle, about: about ?? form.about)))
    }
}

extension CreateForm {
    func doCopy(name: String? = nil, handle: String? = nil, about: String? = nil, entry: Entry? = nil, listed: Bool? = nil) -> CreateForm {
        doCopy(name: name ?? self.name, handle: handle ?? self.handle, about: about ?? self.about, entry: entry ?? self.entry,
               listed: listed ?? self.listed, working: working, error: nil)
    }
}

/// Open to all, or on approval.
struct EntryChoice: View {
    let entry: Entry
    let choose: (Entry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("groups.create.entry")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            HStack(spacing: 8) {
                Choice(label: L("groups.entry.open"), chosen: entry == .open) { choose(.open) }
                Choice(label: L("groups.entry.approval"), chosen: entry == .approval) { choose(.approval) }
            }
        }
    }
}

/// A chip in a row of choices: red when chosen.
struct Choice: View {
    let label: String
    let chosen: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.select()
            action()
        } label: {
            Text(label).font(.brandBody(14, weight: .bold)).lineLimit(1)
                .foregroundStyle(chosen ? Color.brandInk : enabled ? Color.brandPaper : Color.brandMuted)
                .padding(.horizontal, 12).padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(chosen ? Color.brandRed : Color.brandInk, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(chosen ? Color.brandInk : Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}

struct SwitchRow: View {
    let label: String
    let help: String?
    let isOn: Bool
    let toggle: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                if let help { Text(help).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            BrandSwitch(isOn: isOn, label: label, action: toggle)
        }
    }
}

// MARK: - One group

struct GroupView: View {
    @StateObject private var model: GroupModel
    @Environment(\.push) private var push
    @Environment(\.pop) private var pop
    @State private var sharing = false

    init(ref: String) { _model = StateObject(wrappedValue: GroupModel(ref: ref)) }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            if let notice = state.notice { OfflineNotice(text: notice) { model.send(GroupIntentRefresh.shared) }.padding(.horizontal, 16).padding(.vertical, 6) }
            if let error = state.error { ErrorText(text: error).padding(.horizontal, 16) }
            if state.member {
                ChatList(state: state, send: model.send)
                ComposerBar(state: state, send: model.send)
            } else {
                VisitorCard(state: state, send: model.send)
            }
        }
        .background(Color.brandInk)
        .overlay(alignment: .bottom) {
            if let toast = state.toast {
                Text(toast).font(.brandBody(14)).foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 16).padding(.vertical, 10).background(Color.brandPaper, in: Capsule())
                    .padding(.bottom, 96)
                    .task {
                        try? await Task.sleep(for: .seconds(3))
                        model.send(GroupIntentToastShown.shared)
                    }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar(state) }
        .sheet(isPresented: $sharing) { if let header = state.header { ShareGroupSheet(header: header) } }
        .sheet(isPresented: Binding(get: { state.actions != nil }, set: { if !$0 { model.send(GroupIntentCloseActions.shared) } })) {
            if let actions = state.actions { MessageActionsSheet(actions: actions, send: model.send) }
        }
        .sheet(isPresented: Binding(get: { state.newGame != nil }, set: { if !$0 { model.send(GroupIntentCancelNewGame.shared) } })) {
            if let form = state.newGame { NewGameSheet(form: form, send: model.send) }
        }
        .alert(state.leaveLabel, isPresented: Binding(get: { state.leaveConfirm }, set: { if !$0 { model.send(GroupIntentCancelLeave.shared) } })) {
            Button(state.leaveLabel, role: .destructive) { model.send(GroupIntentConfirmLeave.shared) }
            Button(L("common.cancel"), role: .cancel) { model.send(GroupIntentCancelLeave.shared) }
        } message: { Text(state.leaveQuestion) }
        .onChange(of: state.open?.seq) { _, _ in
            guard let open = state.open else { return }
            switch onEnum(of: open) {
            case let .gameScreen(game): push(.game(game.gameId))
            case .back: pop?()
            }
            model.send(GroupIntentHandled(seq: open.seq))
        }
        .onAppear { model.send(GroupIntentStart.shared) }
        .onDisappear { model.send(GroupIntentStop.shared) }
        .task { await model.observe() }
    }

    @ToolbarContentBuilder
    private func toolbar(_ state: GroupUiState) -> some ToolbarContent {
        ToolbarItem(placement: .principal) {
            if let header = state.header {
                HStack(spacing: 8) {
                    GroupEmblem(name: header.name, accent: header.accent, size: 30)
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 4) {
                            Text(header.name).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandPaper).lineLimit(1)
                            if header.verified { VerifiedMark(label: header.verifiedLabel) }
                        }
                        Text(header.meta).font(.brandBody(11)).foregroundStyle(Color.brandMuted).lineLimit(1)
                    }
                }
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if state.header != nil {
                Button { sharing = true } label: { Image(systemName: "square.and.arrow.up") }
                    .accessibilityLabel(L("groups.share"))
            }
            if state.admin, let id = state.header?.id {
                Button { push(.groupAdmin(id)) } label: {
                    Image(systemName: "gearshape").overlay(alignment: .topTrailing) {
                        if state.pendingLabel != nil { Circle().fill(Color.brandYellow).frame(width: 8, height: 8).offset(x: 4, y: -4) }
                    }
                }
                .accessibilityLabel(state.pendingLabel ?? L("groups.admin.title"))
            }
            if state.member {
                Menu {
                    Button(state.notifyLabel) { model.send(GroupIntentToggleNotify.shared) }
                    Button(state.leaveLabel, role: .destructive) { model.send(GroupIntentAskLeave.shared) }
                } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel(L("groups.admin.settings"))
            }
        }
    }
}

/// Not a member: the group's card and its way in.
private struct VisitorCard: View {
    let state: GroupUiState
    let send: (GroupIntent) -> Void
    @State private var note = ""

    var body: some View {
        ScrollView {
            if let header = state.header {
                VStack(spacing: 14) {
                    GroupEmblem(name: header.name, accent: header.accent, size: 88).padding(.top, 24)
                    Text(header.name).font(.brandDisplay(24)).foregroundStyle(Color.brandPaper).multilineTextAlignment(.center)
                    Text(header.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    if !header.about.isEmpty { Text(header.about).font(.brandBody(15)).foregroundStyle(Color.brandPaper).multilineTextAlignment(.center) }
                    if let join = state.join {
                        if join.action == .ask {
                            TextField("", text: $note, prompt: Text(join.notePlaceholder).foregroundColor(Color.brandMuted), axis: .vertical)
                                .lineLimit(3 ... 6)
                                .font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                                .padding(12)
                                .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
                                .onChange(of: note) { _, text in send(GroupIntentNote(text: text)) }
                        }
                        switch join.action {
                        case .join, .ask:
                            Button(join.label) {
                                submitAfterCommittingInput {
                                    send(GroupIntentNote(text: note))
                                    send(GroupIntentJoinAct.shared)
                                }
                            }
                            .buttonStyle(PrimaryButtonStyle()).disabled(join.working)
                        case .withdraw:
                            Button(join.label) { send(GroupIntentJoinAct.shared) }.buttonStyle(GhostButtonStyle())
                        default:
                            Text(join.label).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandMuted)
                        }
                        if let hint = join.hint { Text(hint).font(.brandBody(13)).foregroundStyle(Color.brandMuted).multilineTextAlignment(.center) }
                    }
                }
                .padding(20)
                .readableWidth()
            } else if state.loading {
                ProgressView().tint(Color.brandCyan).padding(40)
            }
        }
    }
}

private struct ChatList: View {
    let state: GroupUiState
    let send: (GroupIntent) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 4) {
                    if state.hasOlder && !state.messages.isEmpty {
                        Button(L("groups.chat.older")) { send(GroupIntentLoadOlder.shared) }
                            .font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandCyan)
                            .disabled(state.loadingOlder)
                            .padding(10)
                    } else if !state.loading && state.messages.isEmpty {
                        Text(state.emptyChat).font(.brandBody(14)).foregroundStyle(Color.brandMuted).multilineTextAlignment(.center).padding(24)
                    }
                    ForEach(state.messages, id: \.id) { row in
                        MessageItem(row: row, card: row.gameId.flatMap { state.games[$0] }, send: send).id(row.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .readableWidth()
            }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: state.scroll?.seq) { _, _ in
                guard let scroll = state.scroll else { return }
                if scroll.animated {
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(scroll.messageId, anchor: .bottom) }
                } else {
                    proxy.scrollTo(scroll.messageId, anchor: .bottom)
                }
            }
        }
    }
}

private struct MessageItem: View {
    let row: MessageRow
    let card: GameCardUi?
    let send: (GroupIntent) -> Void

    var body: some View {
        VStack(spacing: 2) {
            if let day = row.day {
                Text(day.uppercased()).font(.brandBody(11, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandMuted).padding(.vertical, 10)
            }
            switch row.kind {
            case .system:
                Text(row.text).font(.brandBody(12)).foregroundStyle(Color.brandMuted).multilineTextAlignment(.center)
                    .padding(.vertical, 4).accessibilityLabel(row.a11y)
            case .game:
                if let card { GameCardView(card: card, send: send) } else { Text(row.text).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            default:
                bubble
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var bubble: some View {
        let ink = row.own ? Color.brandInk : Color.brandPaper
        let gone = row.kind == .gone
        return VStack(alignment: row.own ? .trailing : .leading, spacing: 2) {
            if let author = row.author {
                Text(author).font(.brandBody(12, weight: .bold)).foregroundStyle(CircleInk.of(row.authorSlot))
                    .padding(.horizontal, 6).padding(.top, 8)
            }
            VStack(alignment: .leading, spacing: 4) {
                if let reply = row.reply {
                    Text(reply).font(.brandBody(12)).foregroundStyle(ink.opacity(0.7)).lineLimit(2)
                        .padding(.leading, 8)
                        .overlay(alignment: .leading) { Rectangle().fill(Color.brandCyan).frame(width: 2) }
                }
                if let sticker = row.sticker {
                    Text(sticker.glyph).font(.brandDisplay(24)).foregroundStyle(Color.brandRed).accessibilityLabel(sticker.label)
                } else {
                    Text(row.text).font(.brandBody(gone ? 14 : 16)).foregroundStyle(gone ? Color.brandMuted : ink)
                }
                Text(row.time).font(.brandBody(10)).foregroundStyle(ink.opacity(0.55)).frame(maxWidth: .infinity, alignment: .trailing)
                if let status = row.status { Text(status).font(.brandBody(11)).foregroundStyle(Color.brandYellow) }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(row.own ? Color.brandPaper : Color.brandInk2, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(row.own ? Color.brandInk : Color.brandLine, lineWidth: 1))
            .frame(maxWidth: 300, alignment: row.own ? .trailing : .leading)
            .contentShape(RoundedRectangle(cornerRadius: 14))
            .onLongPressGesture {
                guard !gone else { return }
                Haptics.press()
                send(GroupIntentOpenActions(messageId: row.id))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.a11y)
            .accessibilityAction(named: L("groups.actions.reply")) { send(GroupIntentOpenActions(messageId: row.id)) }
            if !row.reactions.isEmpty {
                HStack(spacing: 4) {
                    ForEach(row.reactions, id: \.sticker.key) { reaction in
                        Button { send(GroupIntentReact(messageId: row.id, key: reaction.sticker.key)) } label: {
                            Text("\(reaction.sticker.glyph) \(reaction.count)").font(.brandBody(11, weight: .bold))
                                .foregroundStyle(reaction.mine ? Color.brandInk : Color.brandPaper)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(reaction.mine ? Color.brandYellow : Color.brandInk2, in: Capsule())
                                .overlay(Capsule().stroke(Color.brandLine, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(reaction.a11y)
                    }
                }
                .padding(.horizontal, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: row.own ? .trailing : .leading)
    }
}

private struct GameCardView: View {
    let card: GameCardUi
    let send: (GroupIntent) -> Void

    var body: some View {
        Button {
            Haptics.tap()
            send(card.canJoin ? GroupIntentJoinGame(gameId: card.gameId) : GroupIntentOpenGame(gameId: card.gameId))
        } label: {
            HStack(spacing: 12) {
                GameGlyph(kind: card.kind)
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.title).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandPaper)
                    Text(card.line).font(.brandBody(13)).foregroundStyle(card.myTurn ? Color.brandRed : Color.brandMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(card.action).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(card.canJoin || card.myTurn ? Color.brandRed : Color.brandPaper, in: RoundedRectangle(cornerRadius: 8))
            }
            .padding(14)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(card.myTurn ? Color.brandRed : Color.brandLine, lineWidth: 2))
        }
        .buttonStyle(PressableCardStyle())
        .padding(.vertical, 6)
        .accessibilityIdentifier("group.game.\(card.gameId)")
    }
}

/// A game's small picture: discs, a grid, a clash.
struct GameGlyph: View {
    let kind: GameKind?

    var body: some View {
        Canvas { context, size in
            let s = size.width
            switch kind {
            case .four:
                for (x, y, color) in [(0.3, 0.62, Color.brandRed), (0.7, 0.62, Color.brandCyan), (0.5, 0.28, Color.brandRed)] {
                    context.fill(Path(ellipseIn: CGRect(x: s * x - s / 4, y: s * y - s / 4, width: s / 2, height: s / 2)), with: .color(color))
                }
            case .panels:
                let step = s / 3
                var grid = Path()
                for i in 0 ... 3 {
                    grid.move(to: CGPoint(x: CGFloat(i) * step, y: 0)); grid.addLine(to: CGPoint(x: CGFloat(i) * step, y: s))
                    grid.move(to: CGPoint(x: 0, y: CGFloat(i) * step)); grid.addLine(to: CGPoint(x: s, y: CGFloat(i) * step))
                }
                context.fill(Path(CGRect(x: step, y: step, width: step, height: step)), with: .color(.brandRed))
                context.stroke(grid, with: .color(.brandPaper), lineWidth: 2)
            default:
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.brandYellow))
                var cross = Path()
                cross.move(to: CGPoint(x: s * 0.2, y: s * 0.8)); cross.addLine(to: CGPoint(x: s * 0.8, y: s * 0.2))
                cross.move(to: CGPoint(x: s * 0.2, y: s * 0.2)); cross.addLine(to: CGPoint(x: s * 0.8, y: s * 0.8))
                context.stroke(cross, with: .color(.brandInk), lineWidth: 4)
            }
        }
        .frame(width: 34, height: 34)
        .accessibilityHidden(true)
    }
}

private struct ComposerBar: View {
    let state: GroupUiState
    let send: (GroupIntent) -> Void
    @State private var text = ""
    @State private var stickers = false

    var body: some View {
        let composer = state.composer
        VStack(spacing: 6) {
            if let readOnly = state.readOnly {
                Text(readOnly).font(.brandBody(13)).foregroundStyle(Color.brandMuted).multilineTextAlignment(.center).padding(8)
            } else {
                if let banner = composer.replyTo ?? (composer.editingId != nil ? composer.placeholder : nil) {
                    HStack {
                        Text(banner).font(.brandBody(13)).foregroundStyle(Color.brandCyan).lineLimit(1)
                        Spacer()
                        Button { send(composer.editingId != nil ? GroupIntentCancelEdit.shared : GroupIntentCancelReply.shared) } label: {
                            Image(systemName: "xmark").foregroundStyle(Color.brandMuted).frame(width: 36, height: 36)
                        }
                        .accessibilityLabel(L("common.cancel"))
                    }
                }
                if stickers {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(state.stickers, id: \.key) { sticker in
                                Button {
                                    stickers = false
                                    Haptics.press()
                                    send(GroupIntentSendSticker(key: sticker.key))
                                } label: {
                                    Text(sticker.glyph).font(.brandDisplay(14)).foregroundStyle(Color.brandInk)
                                        .padding(.horizontal, 10).padding(.vertical, 8)
                                        .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 8))
                                }
                                .accessibilityLabel(sticker.label)
                            }
                        }
                    }
                }
                HStack(spacing: 8) {
                    Button { stickers.toggle() } label: {
                        Text("BOOM").font(.brandDisplay(11)).foregroundStyle(stickers ? Color.brandInk : Color.brandYellow)
                            .padding(.horizontal, 8).padding(.vertical, 10)
                            .background(stickers ? Color.brandYellow : Color.brandInk, in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandYellow, lineWidth: 1))
                    }
                    .accessibilityLabel(L("groups.chat.stickers"))
                    Button { send(GroupIntentShowNewGame.shared) } label: {
                        Image(systemName: "gamecontroller").foregroundStyle(Color.brandCyan).frame(width: 40, height: 40)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
                    }
                    .accessibilityLabel(state.playLabel)
                    TextField("", text: $text, prompt: Text(composer.placeholder).foregroundColor(Color.brandMuted), axis: .vertical)
                        .lineLimit(1 ... 5)
                        .font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        .padding(.horizontal, 12).padding(.vertical, 10)
                        .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.brandLine, lineWidth: 1))
                        .onChange(of: text) { _, typed in send(GroupIntentText(text: typed)) }
                        // Editing fills the field with the message; sent, the shared composer empties it.
                        .onChange(of: composer.text) { _, latest in if latest != text { text = latest } }
                        .accessibilityIdentifier("group.composer")
                    Button {
                        send(GroupIntentText(text: text))
                        send(GroupIntentSend.shared)
                    } label: {
                        Image(systemName: "arrow.up").font(.system(size: 17, weight: .bold))
                            .foregroundStyle(composer.canSend ? Color.brandInk : Color.brandMuted)
                            .frame(width: 42, height: 40)
                            .background(composer.canSend ? Color.brandRed : Color.brandInk, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(composer.canSend ? Color.brandInk : Color.brandLine, lineWidth: 1))
                    }
                    .disabled(!composer.canSend)
                    .accessibilityLabel(L("groups.chat.send"))
                }
                if let counter = composer.counter { Text(counter).font(.brandBody(11)).foregroundStyle(Color.brandMuted).frame(maxWidth: .infinity, alignment: .trailing) }
                if let error = composer.error { ErrorText(text: error) }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.brandInk2)
    }
}

private struct MessageActionsSheet: View {
    let actions: ActionsUi
    let send: (GroupIntent) -> Void
    @State private var reporting = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(actions.excerpt).font(.brandBody(14)).foregroundStyle(Color.brandMuted).lineLimit(2)
                if reporting {
                    Text(L("groups.report.title")).font(.brandBody(17, weight: .bold)).foregroundStyle(Color.brandPaper)
                    ForEach(actions.reasons, id: \.label) { reason in
                        Button(reason.label) { send(GroupIntentReport(messageId: actions.messageId, reason: reason.reason)) }
                            .buttonStyle(GhostButtonStyle())
                    }
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(actions.reactions, id: \.sticker.key) { reaction in
                                Button { send(GroupIntentReact(messageId: actions.messageId, key: reaction.sticker.key)) } label: {
                                    Text(reaction.sticker.glyph).font(.brandDisplay(14)).foregroundStyle(Color.brandInk)
                                        .padding(.horizontal, 10).padding(.vertical, 8)
                                        .background(reaction.mine ? Color.brandRed : Color.brandYellow, in: RoundedRectangle(cornerRadius: 8))
                                }
                                .accessibilityLabel(reaction.a11y)
                            }
                        }
                    }
                    if actions.canReply { action("groups.actions.reply") { send(GroupIntentReply(messageId: actions.messageId)) } }
                    if actions.canEdit { action("groups.actions.edit") { send(GroupIntentEdit(messageId: actions.messageId)) } }
                    if actions.canRestore { action("groups.actions.restore") { send(GroupIntentRestore(messageId: actions.messageId)) } }
                    if actions.canDelete { action("groups.actions.delete", destructive: true) { send(GroupIntentDelete(messageId: actions.messageId)) } }
                    if actions.canRemove { action("groups.actions.remove", destructive: true) { send(GroupIntentRemove(messageId: actions.messageId)) } }
                    if actions.canReport { action("groups.actions.report", destructive: true) { reporting = true } }
                }
            }
            .padding(20)
        }
        .background(Color.brandInk2)
        .presentationDetents([.medium, .large])
        .presentationBackground(Color.brandInk2)
    }

    private func action(_ key: String, destructive: Bool = false, run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(L(key)).font(.brandBody(16, weight: .bold)).foregroundStyle(destructive ? Color.brandDanger : Color.brandPaper)
                .frame(maxWidth: .infinity).padding(.vertical, 13)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(destructive ? Color.brandDanger : Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// The group's link: a QR code to scan, the link itself, the system share sheet and Copy.
private struct ShareGroupSheet: View {
    let header: GroupHeader
    @State private var copied = false

    private var link: String { GroupLinks.shared.shareUrl(webBaseUrl: AppEnvironment.webBaseUrl, handle: header.handle) }

    var body: some View {
        VStack(spacing: 16) {
            Text(L("groups.share.title")).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
            QrCode(text: link).frame(width: 220, height: 220)
                .padding(14).background(Color.brandPaper, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel(L("groups.share.qrA11y"))
            Text(link).font(.brandBody(14, weight: .bold)).foregroundStyle(Color.brandCyan).textSelection(.enabled).multilineTextAlignment(.center)
            ShareLink(item: URL(string: link) ?? URL(string: AppEnvironment.webBaseUrl)!) { Text(L("groups.share")) }
                .buttonStyle(PrimaryButtonStyle())
            Button(copied ? L("groups.share.copied") : L("groups.share.copy")) {
                UIPasteboard.general.string = link
                Haptics.success()
                copied = true
            }
            .buttonStyle(GhostButtonStyle())
        }
        .padding(24)
        .presentationDetents([.large])
        .presentationBackground(Color.brandInk2)
    }
}

/// A QR code drawn with Core Image (no library), crisp at any size.
struct QrCode: View {
    let text: String

    var body: some View {
        if let image = Self.image(text) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
        } else {
            Color.brandPaper
        }
    }

    private static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private struct NewGameSheet: View {
    let form: NewGameForm
    let send: (GroupIntent) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("games.new.title")).font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                HStack(spacing: 8) {
                    ForEach(Array(form.kinds.enumerated()), id: \.offset) { _, pair in
                        if let kind = pair.first as GameKind? {
                            Choice(label: pair.second.map { $0 as String } ?? "", chosen: form.kind == kind) {
                                send(GroupIntentEditNewGame(form: form.with(kind: kind, seats: (kind.seats.first?.int32Value) ?? 2)))
                            }
                        }
                    }
                }
                Text(form.rules).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if form.seatChoices.count > 1 {
                    Text(L("games.new.seats")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    HStack(spacing: 8) {
                        ForEach(form.seatChoices.map(\.int32Value), id: \.self) { seats in
                            Choice(label: "\(seats)", chosen: form.seats == seats) { send(GroupIntentEditNewGame(form: form.with(seats: seats))) }
                        }
                    }
                }
                SwitchRow(label: L("games.new.ranked"), help: nil, isOn: form.ranked) { send(GroupIntentEditNewGame(form: form.with(ranked: !form.ranked))) }
                if !form.members.isEmpty {
                    Text(L("games.new.invite")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(form.members, id: \.userId) { member in
                                Choice(label: member.name, chosen: member.chosen, enabled: member.chosen || form.canInviteMore) {
                                    send(GroupIntentToggleInvite(userId: member.userId))
                                }
                                .fixedSize()
                            }
                        }
                    }
                    if !form.invited.isEmpty {
                        SwitchRow(label: L("games.new.inviteOnly"), help: nil, isOn: form.inviteOnly) {
                            send(GroupIntentEditNewGame(form: form.with(inviteOnly: !form.inviteOnly)))
                        }
                    }
                }
                if let error = form.error { ErrorText(text: error) }
                Button(L("games.new.start")) { send(GroupIntentStartGame.shared) }.buttonStyle(PrimaryButtonStyle()).disabled(form.working)
            }
            .padding(20)
        }
        .background(Color.brandInk2)
        .presentationDetents([.medium, .large])
        .presentationBackground(Color.brandInk2)
    }
}

extension NewGameForm {
    func with(kind: GameKind? = nil, seats: Int32? = nil, ranked: Bool? = nil, inviteOnly: Bool? = nil) -> NewGameForm {
        doCopy(kind: kind ?? self.kind, seats: seats ?? self.seats, ranked: ranked ?? self.ranked, inviteOnly: inviteOnly ?? self.inviteOnly,
               members: members, working: working, error: nil)
    }
}
