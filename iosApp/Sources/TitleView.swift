import Shared
import SwiftUI

/// Opens the page of a title from a source.
struct TitleRoute: Hashable {
    let sourceKey: String
    let titleId: String
}

/// Title page. Details (catalog), following (library) and downloads (reader) share it, unaware of each other.
struct TitleView: View {
    @StateObject private var model: TitleModel
    /// A delete waiting for its confirmation: a chapter id, or "*" for every download of the title.
    @State private var confirmDelete: String?
    /// The jump field's text (kept here, sent on Go).
    @State private var jumpText = ""
    /// The tab's stack: the pills push onto it (this page registers no destination of its own).
    @Environment(\.push) private var push

    private let route: TitleRoute

    init(route: TitleRoute) {
        self.route = route
        _model = StateObject(wrappedValue: TitleModel(sourceKey: route.sourceKey, titleId: route.titleId))
    }

    /// `language`: open it translated (the language last read in, when this chapter is ready in it). `sourceKey`,
    /// `titleId`: where the chapter is read (another source's title of the same series); empty: this page's.
    private func read(_ chapterId: String, _ page: Int = 0, language: String? = nil, sourceKey: String = "", titleId: String = "") -> ReaderRoute {
        ReaderRoute(
            sourceKey: sourceKey.isEmpty ? route.sourceKey : sourceKey,
            titleId: titleId.isEmpty ? route.titleId : titleId,
            chapterId: chapterId,
            startPage: page,
            language: language
        )
    }

    var body: some View {
        // Lazy: a series of thousands of chapters builds only the rows on screen; the reader scrolls to a jump's row.
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    AsyncImage(url: model.state.coverUrl.flatMap(URL.init(string:))) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else if phase.error != nil || model.state.coverUrl == nil {
                            CoverArtPlaceholder(name: model.state.name)
                        } else {
                            Color.brandInk2
                        }
                    }
                    .frame(height: 260)
                    .frame(maxWidth: .infinity)
                    .clipped()
                    VStack(alignment: .leading, spacing: 12) {
                        if model.state.unavailable {
                            Text(L("catalog.titleNotSaved")).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        }
                        if let notice = model.state.notice {
                            OfflineNotice(text: notice, onRetry: model.state.canRetry ? { model.refresh() } : nil)
                        }
                        if let error = model.state.error { ErrorText(text: error) }
                        if model.state.unavailable && model.state.canRetry {
                            Button(L("common.tryAgain")) { model.refresh() }.buttonStyle(GhostButtonStyle())
                        }
                        Text(model.state.name).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                        HStack(spacing: 8) {
                            Text(model.state.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                            Spacer(minLength: 0)
                            CreditChip()
                        }
                        if model.state.ratingLine != nil || model.state.favoriteLine != nil {
                            MarkLines(rating: model.state.ratingLine, favorite: model.state.favoriteLine)
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel([model.state.ratedBy, model.state.favoriteLine].compactMap { $0 }.joined(separator: ", "))
                        }
                        // Where this account left the title (this device or another), otherwise chapter 1.
                        if let action = model.state.readAction {
                            NavigationLink(value: AppRoute.reader(read(action.chapterId, Int(action.page), language: action.language, sourceKey: action.sourceKey, titleId: action.titleId))) {
                                VStack(spacing: 2) {
                                    Text(action.label)
                                    Text(action.detail).font(.brandBody(12, weight: .bold)).opacity(0.75).lineLimit(1)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .accessibilityLabel("\(action.label) \(action.detail)")
                        }
                        if !model.state.name.isEmpty {
                            HStack(spacing: 10) {
                                Button(model.follow.label) { model.toggleFollow() }.buttonStyle(GhostButtonStyle())
                                // The heart, labelled: "Add to favourites" / "Favourite" (it follows the title too).
                                Button {
                                    Haptics.tap()
                                    model.toggleFavorite()
                                } label: {
                                    Label(model.follow.favoriteLabel, systemImage: model.follow.favorite ? "heart.fill" : "heart")
                                        .foregroundStyle(model.follow.favorite ? Color.brandRed : Color.brandPaper)
                                }
                                .buttonStyle(GhostButtonStyle())
                                .accessibilityLabel(model.follow.favoriteAction)
                                .accessibilityAddTraits(model.follow.favorite ? .isSelected : [])
                                .accessibilityIdentifier("title.heart")
                            }
                            if let error = model.follow.error { ErrorText(text: error) }
                        }
                        if model.state.showStars {
                            StarRating(
                                fills: model.state.starFills.map { $0.doubleValue },
                                preview: { model.state.previewFills(x: $0).map { $0.doubleValue } },
                                value: model.state.ratingValue,
                                title: model.state.ratingTitle,
                                hint: model.state.rateHint,
                                clear: model.state.showClear ? model.state.clearLabel : nil,
                                onRate: { model.rate(at: $0) },
                                onStep: { model.stepRating(up: $0) },
                                onClear: { model.clearRating() }
                            )
                            if let error = model.state.ratingError { ErrorText(text: error) }
                        }
                        Text(model.state.synopsis).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        if !model.state.genres.isEmpty {
                            Text(model.state.genres.joined(separator: " ✦ ")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        downloadsBlock
                        if !model.state.chapters.isEmpty {
                            HStack(alignment: .firstTextBaseline) {
                                Text(model.state.isBook ? L("catalog.contents") : L("catalog.chapters")).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                                Spacer()
                                // Chapter 1 on top by default; one tap flips the list, remembered for every title.
                                Button {
                                    Haptics.select()
                                    withAnimation(.snappy) { model.toggleOrder() }
                                } label: {
                                    Label(model.state.orderLabel, systemImage: "arrow.up.arrow.down")
                                        .font(.brandBody(13, weight: .bold))
                                        .foregroundStyle(Color.brandMuted)
                                }
                                .buttonStyle(.plain)
                            }
                            // Only when a chapter has an episode (and the account has `video`): those chapters alone.
                            if model.state.showEpisodesFilter {
                                HStack {
                                    ToggleChip(label: model.state.episodesChip, on: model.state.episodesOnly) {
                                        Haptics.select()
                                        withAnimation(.snappy) { model.toggleEpisodes() }
                                    }
                                    .accessibilityIdentifier("title.episodes")
                                    Spacer()
                                }
                            }
                            if model.state.showJump { jumpBar }
                        }
                        // A long series' chapters coming in by pages: the list grows below while this shows.
                        if let loading = model.state.chapterLoading {
                            HStack(spacing: 8) {
                                ProgressView().tint(Color.brandCyan)
                                Text(loading).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandCyan)
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    .padding(.horizontal, 20)
                    ForEach(model.state.chapters, id: \.id) { chapter in
                            let download = model.downloads.row(chapterId: chapter.id)
                            HStack(spacing: 10) {
                                NavigationLink(value: AppRoute.reader(read(chapter.id, language: chapter.openLanguage, sourceKey: chapter.sourceKey, titleId: chapter.titleId))) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(chapter.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                                        // A chapter this source doesn't have, read at another source of the same series.
                                        if let badge = chapter.sourceBadge {
                                            Text(badge).font(.brandBody(11, weight: .bold))
                                                .foregroundStyle(Color.brandPaper)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .overlay(Rectangle().stroke(Color.brandLine, lineWidth: 1))
                                        }
                                        if !chapter.meta.isEmpty {
                                            Text(chapter.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                                        }
                                        // Its translation in the language last read in: ready (yellow), or on its way (cyan).
                                        if let translation = chapter.translation {
                                            Text(translation).font(.brandBody(13, weight: .bold))
                                                .foregroundStyle(chapter.translationReady ? Color.brandYellow : Color.brandCyan)
                                        }
                                        // The languages its episode can be watched in: "▶ Original", "▶ Français", "+2".
                                        if !chapter.episodes.isEmpty {
                                            HStack(spacing: 4) {
                                                ForEach(chapter.episodes, id: \.self) { badge in EpisodeBadge(label: badge) }
                                            }
                                            .accessibilityElement(children: .ignore)
                                            .accessibilityLabel(chapter.episodesA11y ?? "")
                                            .padding(.top, 2)
                                        }
                                        if let text = download.text {
                                            Text(text).font(.brandBody(13))
                                                .foregroundStyle(download.status == .failed ? Color.brandDanger : Color.brandMuted)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("chapter.\(chapter.id).read")
                                // Any comic: the chapter in another language (the reader opens on its picker).
                                if model.state.offersTranslation && chapter.ownChapter {
                                    ChapterPill(text: model.state.translateChip) { model.translate(chapter.id) }
                                        .accessibilityIdentifier("chapter.\(chapter.id).translate")
                                }
                                // Any comic: the chapter's voiced episode, next to reading it.
                                if model.state.offersEpisodes && chapter.ownChapter {
                                    ChapterPill(text: L("episode.kickerChip")) { model.episode(chapter.id) }
                                        .accessibilityIdentifier("chapter.\(chapter.id).episode")
                                }
                                if chapter.ownChapter {
                                    DownloadControl(row: download) {
                                        if download.action == .delete {
                                            confirmDelete = chapter.id
                                        } else {
                                            model.send(TitleDownloadsIntentAct(chapterId: chapter.id))
                                        }
                                    }
                                }
                            }
                            .padding(.vertical, 16)
                            .padding(.leading, 16)
                            .padding(.trailing, chapter.ownChapter && download.action != .unavailable ? 6 : 16)
                            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                            // The chapter being read: a red edge, so the eye finds it in a long list.
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(chapter.current ? Color.brandRed : Color.brandLine, lineWidth: chapter.current ? 2 : 1))
                            .padding(.horizontal, 20)
                            .id(chapter.id)
                    }
                    if !model.state.alsoOn.isEmpty {
                        VStack(alignment: .leading, spacing: 12) { alsoOnBlock }
                            .padding(.horizontal, 20)
                    }
                }
            }
            // A jump: rows not laid out yet sit at an estimated place, so it lands once, then again once they are.
            .onChange(of: model.state.scroll?.seq) { _, _ in
                guard let scroll = model.state.scroll else { return }
                proxy.scrollTo(scroll.chapterId, anchor: UnitPoint(x: 0.5, y: 0.2))
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                    withAnimation(.snappy) { proxy.scrollTo(scroll.chapterId, anchor: UnitPoint(x: 0.5, y: 0.2)) }
                }
                model.scrollHandled(scroll.seq)
            }
        }
        .readableWidth()
        .background(Color.brandInk)
        .refreshable { model.refresh() }
        // The pills open through the view model: each command is pushed on the tab's path once, then answered.
        .onChange(of: model.state.open?.seq) { _, _ in
            guard let open = model.state.open else { return }
            switch open.target {
            case .translate:
                push(.reader(ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: open.chapterId, startPage: 0, pickLanguage: true)))
            case .episode:
                push(.episode(EpisodeRoute(chapterId: open.chapterId, title: model.state)))
            }
            model.openHandled(open.seq)
        }
        .task { await model.observe() }
        .task { await model.observeFollow() }
        .task { await model.observeDownloads() }
        .alert(
            confirmDelete == "*" ? model.downloads.deleteAllTitle : L("downloads.deleteOneTitle"),
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
            presenting: confirmDelete
        ) { target in
            Button(L("common.delete"), role: .destructive) {
                model.send(target == "*" ? TitleDownloadsIntentDeleteAll.shared : TitleDownloadsIntentAct(chapterId: target))
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: { target in
            Text(
                target == "*"
                    ? model.downloads.deleteAllMessage
                    : L("downloads.chapterNeedsNetwork", ["chapter": model.state.chapterLabel(chapterId: target)])
            )
        }
    }

    /// A long list: a chapter number (the nearest one when it isn't listed) and the chapter being read.
    @ViewBuilder private var jumpBar: some View {
        HStack(spacing: 10) {
            TextField(model.state.jumpPlaceholder, text: $jumpText)
                .keyboardType(.decimalPad)
                .font(.brandBody(15))
                .foregroundStyle(Color.brandPaper)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.brandLine, lineWidth: 1))
                .accessibilityIdentifier("title.jump")
            Button(model.state.jumpGo) {
                submitAfterCommittingInput { model.jump(jumpText) }
            }
            .buttonStyle(GhostButtonStyle())
            .accessibilityIdentifier("title.jumpGo")
            if let current = model.state.jumpCurrent {
                Button(current) { model.jumpToCurrent() }
                    .buttonStyle(GhostButtonStyle())
                    .accessibilityIdentifier("title.jumpCurrent")
            }
        }
        if let error = model.state.jumpError { ErrorText(text: error) }
    }

    /// The same series at other sources (proven by its pages): open it there.
    @ViewBuilder private var alsoOnBlock: some View {
        if !model.state.alsoOn.isEmpty {
            Text(model.state.alsoOnTitle).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper).padding(.top, 8)
            ForEach(model.state.alsoOn, id: \.self) { other in
                NavigationLink(value: AppRoute.title(TitleRoute(sourceKey: other.sourceKey, titleId: other.titleId))) {
                    HStack {
                        Text(other.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Color.brandMuted)
                    }
                    .padding(16)
                    .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("title.alsoOn.\(other.sourceKey)")
            }
        }
    }

    /// Downloads of the title: what is on the device, why the queue waits, "Download next 5", "Download all" (a book:
    /// "Download the book"), delete.
    @ViewBuilder private var downloadsBlock: some View {
        let downloads = model.downloads
        if let summary = downloads.summary {
            Text(summary).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
        }
        if let notice = downloads.notice { OfflineNotice(text: notice) }
        if downloads.offersDownloads {
            if downloads.downloadNextLabel != nil || downloads.downloadAllLabel != nil {
                HStack(spacing: 10) {
                    if let next = downloads.downloadNextLabel {
                        Button(next) { model.send(TitleDownloadsIntentDownloadNext.shared) }.buttonStyle(GhostButtonStyle())
                    }
                    if let all = downloads.downloadAllLabel {
                        Button(all) { model.send(TitleDownloadsIntentDownloadAll.shared) }.buttonStyle(GhostButtonStyle())
                    }
                }
            }
            if downloads.canDeleteAll {
                Button(downloads.deleteAllLabel) { confirmDelete = "*" }
                    .font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
        }
    }
}

/// "TRANSLATE" / "EPISODE" on a chapter row: a real button with its whole capsule as the target.
private struct ChapterPill: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.select()
            action()
        } label: {
            Text(text)
                .font(.brandBody(12, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(Color.brandPaper)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .overlay(Capsule().stroke(Color.brandLine, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .fixedSize()
    }
}

/// A title without a cover (or one that didn't load): its initial stamped over speed lines and red halftone, never
/// an empty grey band.
private struct CoverArtPlaceholder: View {
    let name: String

    var body: some View {
        ZStack {
            Color.brandInk2
            SpeedLines(center: UnitPoint(x: 0.5, y: 0.5), count: 48, color: .brandPaper.opacity(0.35))
            HalftoneBackground(color: .brandRed, spacing: 11, maxOpacity: 0.35)
            Text(name.first.map { String($0).uppercased() } ?? "")
                .font(.brandDisplay(120))
                .foregroundStyle(Color.brandPaper)
                .shadow(color: .brandRed, radius: 0, x: 6, y: 6)
        }
        .accessibilityHidden(true)
    }
}

/// Five stars: a tap sets the half under the finger (left half = .5), a drag previews and sets where it is let go,
/// "Clear" takes the rating back. For VoiceOver one adjustable element ("4.5 out of 5 stars", swipe up or down by half
/// a star).
private struct StarRating: View {
    let fills: [Double]
    let preview: (Double) -> [Double]
    let value: String
    let title: String
    let hint: String?
    let clear: String?
    let onRate: (Double) -> Void
    let onStep: (Bool) -> Void
    let onClear: () -> Void
    @State private var dragging: [Double]?

    var body: some View {
        HStack(spacing: 12) {
            GeometryReader { geo in
                HStack(spacing: 4) {
                    ForEach(0..<5, id: \.self) { i in
                        star((dragging ?? fills)[i]).frame(maxWidth: .infinity)
                    }
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { g in
                            let next = preview(Double(g.location.x / geo.size.width))
                            if next != dragging { Haptics.select() }
                            dragging = next
                        }
                        .onEnded { g in
                            dragging = nil
                            onRate(Double(g.location.x / geo.size.width))
                        }
                )
            }
            .frame(width: 190, height: 36)
            .accessibilityElement()
            .accessibilityLabel(title)
            .accessibilityValue(value)
            .accessibilityAddTraits(.allowsDirectInteraction)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: onStep(true)
                case .decrement: onStep(false)
                @unknown default: break
                }
            }
            .accessibilityIdentifier("title.stars")
            if let hint {
                Text(hint).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            }
            if let clear {
                Button(clear, action: onClear)
                    .font(.brandBody(13, weight: .bold))
                    .foregroundStyle(Color.brandMuted)
                    .buttonStyle(.plain)
            }
        }
    }

    private func star(_ fill: Double) -> some View {
        Image(systemName: fill >= 1 ? "star.fill" : fill >= 0.5 ? "star.leadinghalf.filled" : "star")
            .font(.system(size: 26, weight: .bold))
            .foregroundStyle(fill > 0 ? Color.brandYellow : Color.brandMuted)
    }
}

