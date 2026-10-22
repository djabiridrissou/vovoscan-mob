import Shared
import SwiftUI

struct CatalogView: View {
    @StateObject private var model = CatalogModel()
    @StateObject private var sources = SourcesModel()
    @State private var query = ""
    @State private var sheet: CatalogSheet?
    @Namespace private var zoom
    @Environment(\.openTab) private var openTab
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(kicker: "Catalog", title: "Pick a title.")
                // Two features side by side, unaware of each other: sources (the visible ones, in order) and catalog.
                filters
                    .onAppear { model.send(CatalogIntentSourcesChanged(sources: visibleSources)) }
                    .onChange(of: visibleSources) { _, list in model.send(CatalogIntentSourcesChanged(sources: list)) }
                if let note = model.state.categoryNote {
                    Text(note).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                IntentField(label: "Search", text: $query, value: model.state.query) { model.send(CatalogIntentQuery(value: $0)) }
                if let notice = model.state.offlineNotice {
                    OfflineNotice(text: notice, onRetry: model.state.canRetry ? { model.send(CatalogIntentRefresh.shared) } : nil)
                }
                if let error = model.state.error { ErrorText(text: error) }
                if sources.state.empty {
                    // A new account has no source yet: nothing to read until one is added.
                    EmptyStateCard(
                        sfx: "シーン",
                        title: sources.state.firstSourceTitle,
                        message: sources.state.firstSourceMessage,
                        actionLabel: sources.state.firstSourceAction
                    ) { openTab(.sources) }
                    .riseIn()
                } else if model.state.empty {
                    EmptyStateCard(sfx: "シーン", title: model.state.emptyMessage, message: "Pull down to check again.")
                }
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(Array(model.state.visible.enumerated()), id: \.element) { index, card in
                        let route = TitleRoute(sourceKey: card.sourceKey, titleId: card.id)
                        NavigationLink(value: route) { TitleTile(card: card) }
                            .buttonStyle(PressableCardStyle())
                            .matchedTransitionSource(id: route, in: zoom)
                            .riseIn(index)
                    }
                    // The shape of the grid while the first page of every source loads.
                    if model.state.visible.isEmpty && model.state.freshness == .refreshing && !sources.state.empty {
                        ForEach(0..<6, id: \.self) { _ in SkeletonTile() }
                    }
                }
                if model.state.canLoadMore && model.state.results == nil {
                    Button(model.state.loadingMore ? "Loading…" : "Load more") { model.send(CatalogIntentLoadMore.shared) }
                        .buttonStyle(GhostButtonStyle())
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(CatalogIntentRefresh.shared) }
        .sheet(item: $sheet) { which in
            FilterSheet(model: model, which: which)
                .presentationDetents([.medium, .large])
                .presentationBackground(Color.brandInk2)
        }
        .navigationDestination(for: TitleRoute.self) { route in
            TitleView(route: route).navigationTransition(.zoom(sourceID: route, in: zoom))
        }
        .navigationDestination(for: ReaderRoute.self) { ReaderContainer(route: $0) }
        .task { await model.observe() }
        .task { await sources.observe() }
    }

    private var visibleSources: [CatalogSource] {
        sources.state.chips.map { CatalogSource(key: $0.key, name: $0.name) }
    }

    @ViewBuilder private var filters: some View {
        if model.state.showSourceFilter || model.state.showCategoryFilter {
            HStack(spacing: 8) {
                if model.state.showSourceFilter {
                    FilterButton(label: model.state.sourceFilterLabel, active: !model.state.allSources) { sheet = .sources }
                }
                if model.state.showCategoryFilter {
                    FilterButton(label: model.state.categoryFilterLabel, active: model.state.category != nil) { sheet = .categories }
                }
            }
        }
    }
}

private enum CatalogSheet: String, Identifiable {
    case sources, categories
    var id: String { rawValue }
}

/// A filter of the catalog: its current value, red when it narrows the catalog.
private struct FilterButton: View {
    let label: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(label).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
            }
            .font(.brandBody(15, weight: .bold))
            .foregroundStyle(active ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(active ? Color.brandRed : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(active ? Color.brandRed : Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Sources: "All", then each visible source (tap to add or remove it). Categories: one choice, then it closes.
private struct FilterSheet: View {
    @ObservedObject var model: CatalogModel
    let which: CatalogSheet
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text(which == .sources ? "Sources" : "Categories")
                    .font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                    .padding(.bottom, 8)
                if which == .sources {
                    CheckRow(label: "All", checked: model.state.allSources) { model.send(CatalogIntentShowAllSources.shared) }
                    ForEach(model.state.sources, id: \.key) { source in
                        CheckRow(label: source.name, checked: source.selected) {
                            Haptics.select()
                            model.send(CatalogIntentToggleSource(key: source.key))
                        }
                    }
                } else {
                    CheckRow(label: "All categories", checked: model.state.category == nil) {
                        model.send(CatalogIntentSelectCategory(name: nil))
                        dismiss()
                    }
                    ForEach(model.state.categories, id: \.name) { category in
                        CheckRow(label: category.name, checked: category.selected) {
                            model.send(CatalogIntentSelectCategory(name: category.name))
                            dismiss()
                        }
                    }
                }
            }
            .padding(20)
        }
    }
}

private struct CheckRow: View {
    let label: String
    let checked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).font(.brandBody(16)).foregroundStyle(Color.brandPaper)
                Spacer()
                if checked { Image(systemName: "checkmark").foregroundStyle(Color.brandRed) }
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

/// Title tile: cover, format and source badges, name.
private struct TitleTile: View {
    let card: TitleCard

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topLeading) {
                Color.brandLine
                AsyncImage(url: card.coverUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.brandLine }
                VStack(alignment: .leading, spacing: 4) {
                    Text(card.formatLabel)
                        .font(.brandBody(12, weight: .bold))
                        .foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.brandYellow)
                    Text(card.sourceName)
                        .font(.brandBody(11, weight: .bold))
                        .foregroundStyle(Color.brandPaper)
                        .lineLimit(1)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.brandInk.opacity(0.85))
                }
                .padding(8)
            }
            .aspectRatio(3 / 4, contentMode: .fit)
            .clipped()
            VStack(alignment: .leading, spacing: 2) {
                Text(card.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(2)
                Text(card.status).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            }
            .padding(12)
        }
        .background(Color.brandInk2)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 2))
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}
