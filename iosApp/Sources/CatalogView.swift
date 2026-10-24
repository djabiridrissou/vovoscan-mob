import Shared
import SwiftUI

struct CatalogView: View {
    @StateObject private var model = CatalogModel()
    @StateObject private var sources = SourcesModel()
    @State private var query = ""
    @State private var sheet: CatalogSheet?
    @Namespace private var zoom
    @Environment(\.openTab) private var openTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Scrolled past the search field: it folds into a floating magnifier.
    @State private var searchFolded = false
    /// The magnifier was tapped: the search field floats over the top of the catalog.
    @State private var searchFloating = false
    @FocusState private var floatingFocus: Bool
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    var body: some View {
        ScrollViewReader { scroller in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ScreenHeader(kicker: L("nav.catalog"), title: L("catalog.headline"))
                        .id(Self.top)
                    // Two features side by side, unaware of each other: sources (the visible ones, in order) and catalog.
                    filters
                        .onAppear { model.send(CatalogIntentSourcesChanged(sources: visibleSources)) }
                        .onChange(of: visibleSources) { _, list in model.send(CatalogIntentSourcesChanged(sources: list)) }
                    if let note = model.state.categoryNote {
                        Text(note).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                    if let note = model.state.safeNote {
                        Text(note).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                    IntentField(label: L("common.search"), text: $query, value: model.state.query) { model.send(CatalogIntentQuery(value: $0)) }
                    if let notice = model.state.offlineNotice {
                        OfflineNotice(text: notice, onRetry: model.state.canRetry ? { model.send(CatalogIntentRefresh.shared) } : nil)
                    }
                    if let error = model.state.error { ErrorText(text: error) }
                    // The last title read, opened where it was left.
                    if let resume = model.state.resume, model.state.results == nil {
                        NavigationLink(value: ReaderRoute(sourceKey: resume.sourceKey, titleId: resume.titleId, chapterId: resume.chapterId, startPage: Int(resume.page))) {
                            ResumeBanner(card: resume)
                        }
                        .buttonStyle(PressableCardStyle())
                    }
                    if sources.state.needsSource {
                        // A new account has no source yet: nothing to read until one is added.
                        EmptyStateCard(
                            sfx: "SHHH",
                            title: sources.state.firstSourceTitle,
                            message: sources.state.firstSourceMessage,
                            actionLabel: sources.state.firstSourceAction
                        ) { openTab(.sources) }
                        .riseIn()
                    } else if model.state.empty {
                        EmptyStateCard(sfx: "SHHH", title: model.state.emptyMessage, message: L("catalog.pullToRefresh"))
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
                        if model.state.visible.isEmpty && model.state.freshness == .refreshing && !sources.state.needsSource {
                            ForEach(0..<6, id: \.self) { _ in SkeletonTile() }
                        }
                    }
                    if model.state.canLoadMore && model.state.results == nil {
                        Button(model.state.loadingMore ? L("common.loading") : L("catalog.loadMore")) { model.send(CatalogIntentLoadMore.shared) }
                            .buttonStyle(GhostButtonStyle())
                    }
                }
                .padding(20)
            }
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > Self.foldAt
            } action: { _, folded in
                withAnimation(reduceMotion ? nil : .snappy) {
                    searchFolded = folded
                    if !folded { searchFloating = false }
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if searchFloating {
                    floatingSearch
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if searchFolded && !searchFloating {
                    Button {
                        Haptics.tap()
                        withAnimation(reduceMotion ? nil : .snappy) { searchFloating = true }
                        floatingFocus = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(Color.brandInk)
                            .frame(width: 56, height: 56)
                            .background(Color.brandRed, in: Circle())
                            .background(Color.brandInk, in: Circle().offset(x: 3, y: 3))
                            // A search already typed: a yellow dot on the magnifier.
                            .overlay(alignment: .topTrailing) {
                                if !query.isEmpty { Circle().fill(Color.brandYellow).frame(width: 12, height: 12).offset(x: -4, y: 4) }
                            }
                    }
                    .buttonStyle(.plain)
                    .padding(20)
                    .accessibilityLabel(L("catalog.search"))
                    .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
                }
            }
            // A search typed in the floating field shows its results from the top.
            .onChange(of: model.state.query) { _, _ in
                if searchFloating { withAnimation(reduceMotion ? nil : .snappy) { scroller.scrollTo(Self.top, anchor: .top) } }
            }
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

    private static let top = "catalog-top"
    private static let foldAt: CGFloat = 220

    /// The search field, floating over the catalog once it has scrolled away: no trip back to the top.
    private var floatingSearch: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.brandMuted)
            TextField(L("common.search"), text: $query)
                .font(.brandBody(16))
                .foregroundStyle(Color.brandPaper)
                .focused($floatingFocus)
                .submitLabel(.search)
                .autocorrectionDisabled()
                // Shares `query` with the field at the top, which sends each change.
            Button {
                floatingFocus = false
                withAnimation(reduceMotion ? nil : .snappy) { searchFloating = false }
            } label: {
                Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.brandPaper)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("common.close"))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandRed, lineWidth: 2))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.brandInk.opacity(0.92))
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
                Text(which == .sources ? L("nav.sources") : L("catalog.categories"))
                    .font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                    .padding(.bottom, 8)
                if which == .sources {
                    CheckRow(label: L("catalog.all"), checked: model.state.allSources) { model.send(CatalogIntentShowAllSources.shared) }
                    ForEach(model.state.sources, id: \.key) { source in
                        CheckRow(label: source.name, checked: source.selected) {
                            Haptics.select()
                            model.send(CatalogIntentToggleSource(key: source.key))
                        }
                    }
                } else {
                    CheckRow(label: L("catalog.allCategories"), checked: model.state.category == nil) {
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

/// "Continue reading": the last title read, its cover, and where it was left.
private struct ResumeBanner: View {
    let card: ResumeCard

    var body: some View {
        HStack(spacing: 14) {
            AsyncImage(url: card.coverUrl.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: { Color.brandLine }
            .frame(width: 56, height: 76)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(card.label).font(.brandBody(12, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandRed)
                Text(card.titleName).font(.brandDisplay(17)).foregroundStyle(Color.brandPaper).lineLimit(1)
                Text(card.detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "play.fill").font(.system(size: 16, weight: .bold)).foregroundStyle(Color.brandInk)
                .frame(width: 40, height: 40)
                .background(Color.brandRed, in: Circle())
        }
        .padding(12)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandRed, lineWidth: 2))
        .accessibilityElement(children: .combine)
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
                    if card.started {
                        Text(L("catalog.started"))
                            .font(.brandBody(11, weight: .bold))
                            .foregroundStyle(Color.brandInk)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.brandRed)
                    }
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
