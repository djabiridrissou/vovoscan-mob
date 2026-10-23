import Shared
import SwiftUI

struct LibraryView: View {
    let onSignOut: () -> Void
    @StateObject private var model = LibraryModel()
    @StateObject private var sources = SourcesModel()
    // Three features side by side: the library, sources ("Source removed") and downloads (reader).
    @StateObject private var downloads = DownloadsModel()
    @Environment(\.openTab) private var openTab

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(kicker: L("nav.library"), title: L("library.headline"))
                NavigationLink(value: AppRoute.downloads) { Text(downloads.state.entryLabel + " →") }
                    .buttonStyle(GhostButtonStyle())
                if let notice = model.state.pendingNotice { OfflineNotice(text: notice) }
                if let error = model.state.error { ErrorText(text: error) }
                if model.state.empty {
                    if sources.state.needsSource {
                        EmptyStateCard(
                            sfx: "FLIP",
                            title: sources.state.firstSourceTitle,
                            message: sources.state.firstSourceMessage,
                            actionLabel: sources.state.firstSourceAction
                        ) { openTab(.sources) }
                    } else {
                        EmptyStateCard(sfx: "FLIP", title: model.state.emptyMessage, message: L("library.emptyMessage"),
                                       actionLabel: L("library.browse")) { openTab(.catalog) }
                    }
                }
                // Favourites first (most recently favourited first), then Following.
                ForEach(model.state.sections, id: \.title) { section in
                    Text(section.title.uppercased())
                        .font(.brandBody(12, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(Color.brandMuted)
                        .padding(.top, 8)
                    // By title, never by position or by the whole row (its progress changes): a tap opens the row tapped.
                    ForEach(Array(section.rows.enumerated()), id: \.element.rowKey) { index, row in
                        LibraryRowView(row: row) {
                            model.send(LibraryIntentToggleFavorite(sourceKey: row.sourceKey, titleId: row.titleId))
                        }
                        .riseIn(index)
                    }
                }
                Button(L("library.syncNow")) { model.send(LibraryIntentSync.shared) }.buttonStyle(GhostButtonStyle())
                Button(L("common.signOut"), action: onSignOut).buttonStyle(GhostButtonStyle())
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(LibraryIntentSync.shared) }
        // The stack's only destinations (pushed screens register none).
        .navigationDestination(for: AppRoute.self) { AppRouteView(route: $0) }
        // Two features side by side, unaware of each other: titles of a source no longer listed show "Source removed".
        .onAppear { model.send(LibraryIntentSourcesChanged(keys: sources.state.sourceKeys)) }
        .onChange(of: sources.state.sourceKeys) { _, keys in model.send(LibraryIntentSourcesChanged(keys: keys)) }
        .task { await model.observe() }
        .task { await sources.observe() }
        .task { await downloads.observe() }
    }
}

/// A library row: cover (with "+3 new" when chapters came out after the last one read), name, progress, the heart.
private struct LibraryRowView: View {
    let row: LibraryRow
    let onHeart: () -> Void

    var body: some View {
        NavigationLink(value: AppRoute.title(TitleRoute(sourceKey: row.sourceKey, titleId: row.titleId))) {
            HStack(spacing: 14) {
                AsyncImage(url: row.coverUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.brandLine }
                .frame(width: 64, height: 86)
                .clipped()
                .overlay(alignment: .topLeading) {
                    if let badge = row.newBadge { NewChaptersBadge(label: badge).offset(x: -4, y: 4) }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    Text(row.progress).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    if let notice = row.sourceNotice {
                        Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandDanger)
                    }
                }
                Spacer()
                // Cyan: action waiting to be sent (the brand reserves cyan for work in progress).
                if row.pendingSync { Circle().fill(Color.brandCyan).frame(width: 10, height: 10) }
                // Room for the heart, drawn over the row (outside the link: it never opens the title).
                Color.clear.frame(width: 40, height: 40)
            }
            .padding(.trailing, 14)
            .background(Color.brandInk2)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
            .opacity(row.sourceRemoved ? 0.5 : 1)
        }
        .buttonStyle(PressableCardStyle())
        .disabled(row.sourceRemoved)
        // UI tests and checks tap rows by their title, never by a screen position.
        .accessibilityIdentifier("library.row.\(row.rowKey)")
        .overlay(alignment: .trailing) {
            HeartButton(on: row.favorite, label: row.favoriteAction, action: onHeart)
                .padding(.trailing, 14)
                .accessibilityIdentifier("library.heart.\(row.rowKey)")
        }
    }
}

/// "+3 new": yellow, tilted, on a cover's corner (text on yellow: ink).
struct NewChaptersBadge: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.brandBody(11, weight: .bold))
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(Color.brandYellow)
            .rotationEffect(.degrees(-6))
    }
}

private extension LibraryRow {
    /// One row per title.
    var rowKey: String { "\(sourceKey)/\(titleId)" }
}
