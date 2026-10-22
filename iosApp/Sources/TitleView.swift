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
    /// Opened by a chapter's "Translate": the reader on its language picker.
    @State private var pickLanguage: ReaderRoute?
    /// Opened by a chapter's "Episode": its episode card.
    @State private var episode: EpisodeRoute?

    private let route: TitleRoute

    init(route: TitleRoute) {
        self.route = route
        _model = StateObject(wrappedValue: TitleModel(sourceKey: route.sourceKey, titleId: route.titleId))
    }

    /// `language`: open it translated (the language last read in, when this chapter is ready in it).
    private func read(_ chapterId: String, _ page: Int = 0, language: String? = nil) -> ReaderRoute {
        ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: chapterId, startPage: page, language: language)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
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
                    Text(model.state.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    // Where this account left the title (this device or another), otherwise chapter 1.
                    if let action = model.state.readAction {
                        NavigationLink(value: read(action.chapterId, Int(action.page), language: action.language)) {
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
                        Button(model.follow.label) { model.toggleFollow() }.buttonStyle(GhostButtonStyle())
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
                    }
                    ForEach(model.state.chapters, id: \.id) { chapter in
                        let download = model.downloads.row(chapterId: chapter.id)
                        HStack(spacing: 10) {
                            NavigationLink(value: read(chapter.id, language: chapter.openLanguage)) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(chapter.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                                    if !chapter.meta.isEmpty {
                                        Text(chapter.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                                    }
                                    // Its translation in the language last read in: ready (yellow), or on its way (cyan).
                                    if let translation = chapter.translation {
                                        Text(translation).font(.brandBody(13, weight: .bold))
                                            .foregroundStyle(chapter.translationReady ? Color.brandYellow : Color.brandCyan)
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
                            // Any comic: the chapter in another language (the reader opens on its picker).
                            if model.state.offersTranslation {
                                ChapterPill(text: model.state.translateChip) { model.translate(chapter.id) }
                            }
                            // Any comic: the chapter's voiced episode, next to reading it.
                            if model.state.offersEpisodes {
                                ChapterPill(text: L("episode.kickerChip")) { model.episode(chapter.id) }
                            }
                            DownloadControl(row: download) {
                                if download.action == .delete {
                                    confirmDelete = chapter.id
                                } else {
                                    model.send(TitleDownloadsIntentAct(chapterId: chapter.id))
                                }
                            }
                        }
                        .padding(.vertical, 16)
                        .padding(.leading, 16)
                        .padding(.trailing, download.action == .unavailable ? 16 : 6)
                        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                        // The chapter being read: a red edge, so the eye finds it in a long list.
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(chapter.current ? Color.brandRed : Color.brandLine, lineWidth: chapter.current ? 2 : 1))
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .readableWidth()
        .background(Color.brandInk)
        .refreshable { model.refresh() }
        // The pills open through the view model, with destinations of this page's own (never a value link: two
        // value links in a row next to the chapter's own link lost their taps).
        .onChange(of: model.state.open?.seq) { _, _ in
            guard let open = model.state.open else { return }
            switch open.target {
            case .translate:
                pickLanguage = ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: open.chapterId, startPage: 0, pickLanguage: true)
            case .episode:
                episode = EpisodeRoute(chapterId: open.chapterId, title: model.state)
            }
            model.openHandled(open.seq)
        }
        .navigationDestination(item: $pickLanguage) { ReaderContainer(route: $0) }
        .navigationDestination(item: $episode) { EpisodeContainer(route: $0) }
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
