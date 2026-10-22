import Shared
import SwiftUI

struct CatalogView: View {
    /// The catalog tab's navigation: a suggested title is pushed once its list is closed (see `openSuggested`).
    @Environment(\.push) private var push
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
    /// The search field at the top has the focus: its suggestions show under it.
    @FocusState private var fieldFocus: Bool
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
                        .focused($fieldFocus)
                        .submitLabel(.search)
                        // Over the catalog, never in its flow: closing it moves nothing, and it takes every tap on it
                        // (an inline list closed on the focus loss and let the tap through to the card under it).
                        .overlay(alignment: .bottom) {
                            if model.state.showSuggestions && !searchFloating {
                                // Its own height, not the field's (a list squeezed into the field's frame overflowed
                                // upwards over the field and the filters), hung 6 pt under the field.
                                SuggestionList(sections: model.state.suggestions, onSearch: useSearch, onTitle: openSuggested)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .alignmentGuide(.bottom) { $0[.top] - 6 }
                            }
                        }
                        .zIndex(1)
                    if let notice = model.state.offlineNotice {
                        OfflineNotice(text: notice, onRetry: model.state.canRetry ? { model.send(CatalogIntentRefresh.shared) } : nil)
                    }
                    if let error = model.state.error { ErrorText(text: error) }
                    // The last title read, opened where it was left.
                    if let resume = model.state.resume, model.state.results == nil {
                        NavigationLink(value: AppRoute.reader(ReaderRoute(sourceKey: resume.sourceKey, titleId: resume.titleId, chapterId: resume.chapterId, startPage: Int(resume.page)))) {
                            ResumeBanner(card: resume)
                        }
                        .buttonStyle(PressableCardStyle())
                    }
                    if sources.state.needsSource {
                        // A new account has no source yet: nothing to read until one is added.
                        EmptyStateCard(
                            sfx: "シーン",
                            title: sources.state.firstSourceTitle,
                            message: sources.state.firstSourceMessage,
                            actionLabel: sources.state.firstSourceAction
                        ) { openTab(.sources) }
                        .riseIn()
                    } else if model.state.empty {
                        EmptyStateCard(sfx: "シーン", title: model.state.emptyMessage, message: L("catalog.pullToRefresh"))
                    }
                    LazyVGrid(columns: columns, spacing: 14) {
                        // By title, never by the whole card: a card whose badges change (the title page saved its format or status)
                        // stays the same view, so the zoom transition's source survives under the open title page.
                        ForEach(Array(model.state.visible.enumerated()), id: \.element.key) { index, card in
                            let route = TitleRoute(sourceKey: card.sourceKey, titleId: card.id)
                            NavigationLink(value: AppRoute.title(route)) { TitleTile(card: card, popularLabel: model.state.popularLabel) }
                                .buttonStyle(PressableCardStyle())
                                // From search results, opening a title teaches the search (the view model decides).
                                .simultaneousGesture(TapGesture().onEnded { opened(card.sourceKey, card.id) })
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
                    VStack(spacing: 0) {
                        floatingSearch
                        if model.state.showSuggestions {
                            SuggestionList(sections: model.state.suggestions, onSearch: useSearch, onTitle: openSuggested)
                                .padding(.horizontal, 16)
                                .padding(.bottom, 8)
                                .background(Color.brandInk.opacity(0.92))
                        }
                    }
                    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                }
            }
            .onChange(of: fieldFocus || floatingFocus) { _, focused in
                model.send(CatalogIntentSearchFocused(focused: focused))
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
        // The stack's only destinations (pushed screens register none): a title zooms out of its tile.
        .navigationDestination(for: AppRoute.self) { AppRouteView(route: $0, zoom: zoom) }
        .task { await model.observe() }
        .task { await sources.observe() }
    }

    /// A search under the field (this account's or others'): fills the field and runs at once.
    private func useSearch(_ text: String) {
        query = text
        fieldFocus = false
        floatingFocus = false
        model.send(CatalogIntentUseSearch(text: text))
    }

    private func opened(_ sourceKey: String, _ titleId: String) {
        model.send(CatalogIntentTitleOpened(sourceKey: sourceKey, titleId: titleId))
    }

    /// A suggested title: the list and the keyboard go first, then one push (never a second one from the grid).
    private func openSuggested(_ sourceKey: String, _ titleId: String) {
        fieldFocus = false
        floatingFocus = false
        opened(sourceKey, titleId)
        searchFloating = false
        push(.title(TitleRoute(sourceKey: sourceKey, titleId: titleId)))
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

/// Under the search field while it has the focus: "Your searches" (↺), "Others searched", "Titles" (cover, name,
/// "Popular ✦ source"). A search fills the field and runs; a title opens its page.
private struct SuggestionList: View {
    let sections: [SuggestionSection]
    let onSearch: (String) -> Void
    let onTitle: (String, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(sections, id: \.title) { section in
                Text(section.title)
                    .font(.brandBody(12, weight: .bold))
                    .foregroundStyle(Color.brandMuted)
                    .padding(.horizontal, 14)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                ForEach(section.rows, id: \.key) { row in
                    if row.kind == .title, let sourceKey = row.sourceKey, let titleId = row.titleId {
                        Button { onTitle(sourceKey, titleId) } label: { line(row) }
                            .buttonStyle(.plain)
                    } else {
                        Button { onSearch(row.text) } label: { line(row) }
                            .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        // Opaque to touches, gaps between rows included.
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .shadow(color: .black.opacity(0.5), radius: 12, y: 6)
        .accessibilityIdentifier("search-suggestions")
    }

    private func line(_ row: SuggestionRow) -> some View {
        HStack(spacing: 12) {
            switch row.kind {
            case .title:
                AsyncImage(url: row.coverUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.brandLine }
                    .frame(width: 30, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            case .recent:
                Image(systemName: "arrow.counterclockwise").foregroundStyle(Color.brandMuted).frame(width: 30)
            default:
                Image(systemName: "magnifyingglass").foregroundStyle(Color.brandMuted).frame(width: 30)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.text).font(.brandBody(15)).foregroundStyle(Color.brandPaper).lineLimit(1)
                if let detail = row.detail, !detail.isEmpty {
                    Text(detail).font(.brandBody(12)).foregroundStyle(Color.brandMuted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/// Title tile: cover, format and source badges, name.
private struct TitleTile: View {
    let card: TitleCard
    let popularLabel: String

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
                    if card.popular {
                        Text(popularLabel)
                            .font(.brandBody(11, weight: .bold))
                            .foregroundStyle(Color.brandRed)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.brandInk.opacity(0.85))
                            .overlay(Rectangle().stroke(Color.brandRed, lineWidth: 1))
                    }
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
