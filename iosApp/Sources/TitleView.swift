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

    private let route: TitleRoute

    init(route: TitleRoute) {
        self.route = route
        _model = StateObject(wrappedValue: TitleModel(sourceKey: route.sourceKey, titleId: route.titleId))
    }

    private func read(_ chapterId: String, _ page: Int = 0) -> ReaderRoute {
        ReaderRoute(sourceKey: route.sourceKey, titleId: route.titleId, chapterId: chapterId, startPage: page)
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
                        Text("This title isn't saved on this device yet.").font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                    }
                    if let notice = model.state.notice {
                        OfflineNotice(text: notice, onRetry: model.state.canRetry ? { model.refresh() } : nil)
                    }
                    if let error = model.state.error { ErrorText(text: error) }
                    if model.state.unavailable && model.state.canRetry {
                        Button("Try again") { model.refresh() }.buttonStyle(GhostButtonStyle())
                    }
                    Text(model.state.name).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                    Text(model.state.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    // Resume where the reader left off, otherwise start at the first chapter (list runs newest to oldest).
                    if let resume = model.follow.resumeChapterId {
                        NavigationLink(value: read(resume, Int(model.follow.resumePage))) {
                            Text(model.follow.resumeLabel(book: model.state.isBook) ?? "Continue →")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    } else if let first = model.state.chapters.last {
                        NavigationLink(value: read(first.id)) { Text(model.state.isBook ? "Read →" : "Start reading →") }
                            .buttonStyle(PrimaryButtonStyle())
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
                        Text(model.state.isBook ? "Contents" : "Chapters").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                    }
                    ForEach(model.state.chapters, id: \.id) { chapter in
                        let download = model.downloads.row(chapterId: chapter.id)
                        HStack(spacing: 10) {
                            NavigationLink(value: read(chapter.id)) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(chapter.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                                    if !chapter.meta.isEmpty {
                                        Text(chapter.meta).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
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
                            // A comic of our catalog: the chapter's voiced episode, next to reading it.
                            if model.state.offersEpisodes {
                                NavigationLink(value: EpisodeRoute(chapterId: chapter.id, title: model.state)) {
                                    Text("Episode")
                                        .font(.brandBody(12, weight: .bold))
                                        .tracking(1.2)
                                        .foregroundStyle(Color.brandPaper)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 8)
                                        .overlay(Capsule().stroke(Color.brandLine, lineWidth: 1))
                                }
                                .buttonStyle(.plain)
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
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .background(Color.brandInk)
        .refreshable { model.refresh() }
        .navigationDestination(for: EpisodeRoute.self) { EpisodeContainer(route: $0) }
        .task { await model.observe() }
        .task { await model.observeFollow() }
        .task { await model.observeDownloads() }
        .alert(
            confirmDelete == "*" ? model.downloads.deleteAllTitle : "Delete this download?",
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
            presenting: confirmDelete
        ) { target in
            Button("Delete", role: .destructive) {
                model.send(target == "*" ? TitleDownloadsIntentDeleteAll.shared : TitleDownloadsIntentAct(chapterId: target))
            }
            Button("Cancel", role: .cancel) {}
        } message: { target in
            Text(
                target == "*"
                    ? model.downloads.deleteAllMessage
                    : "\(model.state.chapterLabel(chapterId: target)) will need the network again."
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
