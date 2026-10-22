import Shared
import SwiftUI

/// Opens the "Add a source" screen; with a repository, browses it.
struct AddSourceRoute: Hashable {
    let repositoryId: String?
}

/// Opens the detail of a source.
struct SourceDetailRoute: Hashable {
    let key: String
}

/// "Sources" screen: show, hide and reorder each source, see updates, open a source, add one, manage repositories.
/// No logic here (SourcesViewModel).
struct SourcesView: View {
    @StateObject private var model = SourcesModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Kicker(text: L("nav.sources"))
                    Spacer()
                    NavigationLink(value: AddSourceRoute(repositoryId: nil)) { Text(L("sources.addShort")) }
                        .buttonStyle(SmallActionStyle())
                }
                Text(L("sources.headline")).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                Text(L("sources.onDeviceNote"))
                    .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if let updates = model.state.updatesLabel { UpdatesBanner(text: updates) }
                if model.state.checkingUpdates {
                    HStack(spacing: 8) {
                        Circle().fill(Color.brandCyan).frame(width: 8, height: 8)
                        Text(L("sources.checkingUpdates")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                }
                if let notice = model.state.pendingNotice { OfflineNotice(text: notice) }
                if let error = model.state.error {
                    MessageBanner(text: error, error: true) { model.send(SourcesIntentDismissMessage.shared) }
                }
                if let notice = model.state.notice {
                    MessageBanner(text: notice, error: false) { model.send(SourcesIntentDismissMessage.shared) }
                }
                ForEach(model.state.rows, id: \.key) { row in sourceRow(row) }
                if !model.state.repositories.isEmpty {
                    Text(L("sources.repositories")).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper).padding(.top, 12)
                    ForEach(model.state.repositories, id: \.id) { repo in repositoryRow(repo) }
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(SourcesIntentRefresh.shared) }
        .navigationDestination(for: AddSourceRoute.self) { AddSourceView(route: $0) }
        .navigationDestination(for: SourceDetailRoute.self) { SourceDetailView(route: $0) }
        .onAppear { model.send(SourcesIntentCheckUpdates.shared) }
        .task { await model.observe() }
    }

    private func sourceRow(_ row: SourceRow) -> some View {
        HStack(spacing: 8) {
            NavigationLink(value: SourceDetailRoute(key: row.key)) {
                HStack(spacing: 12) {
                    SourceIcon(iconUrl: row.iconUrl, initial: row.initial)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        HStack(spacing: 6) {
                            if !row.versionLabel.isEmpty {
                                Text(row.versionLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                            }
                            OriginChip(label: row.originLabel, thirdParty: row.thirdParty)
                            if row.updateAvailable { UpdateBadge() }
                            // Cyan: change waiting to be sent (the brand reserves cyan for work in progress).
                            if row.pendingSync { Circle().fill(Color.brandCyan).frame(width: 8, height: 8) }
                        }
                        // Kinds and engine on their own lines: a narrow row never breaks "Read on your device" in two.
                        Text(row.kindsLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1)
                        Text(row.engineLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1).minimumScaleFactor(0.85)
                        if row.needsSettings {
                            Text(L("sources.settingsNeededShort")).font(.brandBody(13)).foregroundStyle(Color.brandYellow)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            arrow("arrow.up", enabled: row.canMoveUp) { model.send(SourcesIntentMoveUp(key: row.key)) }
            arrow("arrow.down", enabled: row.canMoveDown) { model.send(SourcesIntentMoveDown(key: row.key)) }
            BrandSwitch(isOn: row.enabled, label: row.name) { model.send(SourcesIntentToggle(key: row.key)) }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
    }

    private func repositoryRow(_ repo: RepositoryRow) -> some View {
        HStack(spacing: 8) {
            NavigationLink(value: AddSourceRoute(repositoryId: repo.id)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(repo.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    Text(repo.url).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1).truncationMode(.middle)
                    if let count = repo.sourceCountLabel { Text(count).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if repo.pendingSync { Circle().fill(Color.brandCyan).frame(width: 8, height: 8) }
            Menu {
                Button(L("common.remove"), role: .destructive) { model.send(SourcesIntentRemoveRepository(id: repo.id)) }
            } label: {
                Image(systemName: "ellipsis").foregroundStyle(Color.brandPaper).padding(10)
            }
        }
        .padding(.leading, 16)
        .padding(.vertical, 12)
        .padding(.trailing, 6)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        .contextMenu {
            Button(L("common.remove"), role: .destructive) { model.send(SourcesIntentRemoveRepository(id: repo.id)) }
        }
    }

    private func arrow(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).foregroundStyle(enabled ? Color.brandPaper : Color.brandLine) }
            .disabled(!enabled)
            .padding(4)
    }
}

/// Yellow highlight: updates found.
private struct UpdatesBanner: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color.brandYellow).frame(width: 8, height: 8)
            Text(text).font(.brandBody(13)).foregroundStyle(Color.brandPaper)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: Capsule())
        .overlay(Capsule().stroke(Color.brandYellow, lineWidth: 1))
    }
}
