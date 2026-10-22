import Shared
import SwiftUI
import UIKit

/// Opens a chapter's episode card from a title page. `title` is the page's state when it was opened: chapter label
/// and the next chapter in reading order come from it (shared Kotlin), never computed here.
struct EpisodeRoute: Hashable {
    let chapterId: String
    let title: TitleDetailUiState
    /// The episode's language when the card opens: "" = the original; the next chapter keeps the one just watched.
    var language: String = ""
}

/// "Next chapter →" at the end of the player swaps the card for the next chapter's, like the reader does.
struct EpisodeContainer: View {
    @State var route: EpisodeRoute

    var body: some View {
        EpisodeView(route: route) { route = EpisodeRoute(chapterId: $0, title: route.title, language: $1) }
            .id(route.chapterId)
    }
}

/// A chapter's episode card: whether it exists, its live progress (followed only while on screen), what the user may
/// do with their own keys, and the MP4 once ready. No logic here: `EpisodeViewModel` (shared Kotlin) decides.
struct EpisodeView: View {
    @StateObject private var model: EpisodeModel
    @State private var player: PlayerRoute?
    @State private var share: SharedVideo?
    @State private var choosingLanguage = false
    private let route: EpisodeRoute
    /// The next chapter's id and the language to open it in.
    let onOpenChapter: (String, String) -> Void

    init(route: EpisodeRoute, onOpenChapter: @escaping (String, String) -> Void) {
        self.route = route
        self.onOpenChapter = onOpenChapter
        _model = StateObject(wrappedValue: EpisodeModel(chapterId: route.chapterId, language: route.language))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: "Episode")
                if !route.title.name.isEmpty {
                    Text(route.title.name).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                let label = route.title.chapterLabel(chapterId: route.chapterId)
                if !label.isEmpty { Text(label).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper) }
                if let notice = model.state.offlineNotice { OfflineNotice(text: notice) }
                if model.state.visible { card }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable { model.send(EpisodeIntentRefresh.shared) }
        .task { await model.observe() }
        // Live progress only while the card is on screen.
        .onAppear { model.send(EpisodeIntentStart.shared) }
        .onDisappear { model.send(EpisodeIntentStop.shared) }
        // A fresh download link from the ViewModel: download it, then the share sheet (Save Video, Save to Files…).
        .onChange(of: model.state.videoDownload?.id) {
            guard let download = model.state.videoDownload else { return }
            Task {
                if let file = await VideoFile.download(download.url, as: download.fileName) {
                    share = SharedVideo(url: file)
                    model.send(EpisodeIntentVideoHandled.shared)
                } else {
                    model.send(EpisodeIntentVideoFailed.shared)
                }
            }
        }
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
        .fullScreenCover(item: $player) { route in
            PlayerView(route: route) { next in
                player = nil
                onOpenChapter(next, route.language)
            }
        }
        .sheet(isPresented: $choosingLanguage) {
            EpisodeLanguageSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationBackground(Color.brandInk2)
        }
    }

    private var card: some View {
        let state = model.state
        return VStack(alignment: .leading, spacing: 12) {
            if state.comingSoon { Sticker(text: "Coming soon", angle: -4).padding(.bottom, 4) }
            if !state.languageOptions.isEmpty { languageButton(state) }
            HStack(alignment: .firstTextBaseline) {
                Text(state.headline).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                Spacer()
                if state.live { LiveBadge() }
            }
            if let detail = state.detail { Text(detail).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
            if !state.stages.isEmpty {
                HStack(spacing: 8) { ForEach(state.stages, id: \.label) { StageChipView(chip: $0) } }
            }
            if state.phase == .running {
                if let fraction = state.progressFraction {
                    ProgressView(value: fraction.doubleValue).tint(Color.brandCyan)
                } else {
                    ProgressView().progressViewStyle(.linear).tint(Color.brandCyan)
                }
                if let progress = state.progressLabel {
                    Text(progress).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
            }
            if !state.badges.isEmpty {
                HStack(spacing: 8) { ForEach(state.badges, id: \.self) { BadgeView(text: $0) } }
            }
            ForEach(state.notes, id: \.self) { Text($0).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }

            if state.canWatch {
                Button(state.watchLabel) {
                    player = PlayerRoute(
                        chapterId: route.chapterId,
                        nextChapterId: route.title.nextChapterId(chapterId: route.chapterId),
                        language: state.language
                    )
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            if state.canPrepare {
                if state.canWatch {
                    Button(state.prepareLabel) { model.send(EpisodeIntentPrepare.shared) }
                        .buttonStyle(GhostButtonStyle())
                        .disabled(state.working)
                } else {
                    Button(state.prepareLabel) { model.send(EpisodeIntentPrepare.shared) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(state.working)
                        .opacity(state.working ? 0.5 : 1)
                }
            }
            if state.canReread {
                Button(state.rereadLabel) { model.send(EpisodeIntentReread.shared) }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(state.working)
            }
            if let cost = state.costLine {
                HStack(spacing: 6) {
                    Circle().fill(Color.brandCyan).frame(width: 6, height: 6)
                    Text(cost).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandPaper)
                }
            }
            if state.showsKeyNotice {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(state.keyLines, id: \.self) { Text($0).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
                    if let warning = state.keyWarning { Text(warning).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                    Text(state.costNote).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
            }
            if state.exportShown { video(state) }
            if let notice = state.notice { Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
            if let error = state.error { ErrorText(text: error) }
            if state.phase == .unavailable {
                Button("Try again") { model.send(EpisodeIntentRefresh.shared) }.buttonStyle(GhostButtonStyle())
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(state.phase == .running ? Color.brandCyan : Color.brandLine, lineWidth: 1))
    }

    /// The episode's language: the original, or another one (dubbed when ElevenLabs speaks it, else subtitled).
    private func languageButton(_ state: EpisodeUiState) -> some View {
        Button {
            Haptics.tap()
            choosingLanguage = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "globe").font(.system(size: 14, weight: .bold))
                Text(state.languageLabel).font(.brandBody(15, weight: .bold))
                if let mode = state.languageMode {
                    Text(mode.uppercased()).font(.brandBody(11, weight: .bold)).tracking(1.2).foregroundStyle(Color.brandCyan)
                }
                Spacer()
                Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
            .foregroundStyle(Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(state.working)
        .accessibilityLabel("Episode language: \(state.languageLabel)")
    }

    /// The MP4: make it (CPU only, no key), follow its progress, then save or share it.
    private func video(_ state: EpisodeUiState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Rectangle().fill(Color.brandLine).frame(height: 1).padding(.vertical, 4)
            Text("VIDEO").font(.brandBody(12, weight: .bold)).tracking(1.4).foregroundStyle(Color.brandMuted)
            if let label = state.exportLabel { Text(label).font(.brandBody(16)).foregroundStyle(Color.brandPaper) }
            if let detail = state.exportDetail { Text(detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            if let progress = state.exportProgress {
                ProgressView(value: progress.doubleValue).tint(Color.brandCyan)
            }
            if state.canRequestExport {
                Button(state.exportActionLabel) { model.send(EpisodeIntentRequestExport.shared) }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(state.exportWorking)
            }
            if state.canSaveVideo {
                Button(state.saveVideoLabel) { model.send(EpisodeIntentSaveVideo.shared) }
                    .buttonStyle(GhostButtonStyle())
            }
        }
    }
}

/// The episode's languages, searchable (own name, English name or code); the rows come from `EpisodeViewModel`.
private struct EpisodeLanguageSheet: View {
    @ObservedObject var model: EpisodeModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let state = model.state
        NavigationStack {
            List {
                if state.languageOptions.isEmpty {
                    Text("No language matches.").font(.brandBody(13)).foregroundStyle(Color.brandMuted).listRowBackground(Color.brandInk2)
                }
                ForEach(state.languageOptions, id: \.self) { option in
                    Button {
                        Haptics.select()
                        model.send(EpisodeIntentChooseLanguage(code: option.code))
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.label).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                                Text(option.detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                            }
                            Spacer()
                            if option.selected { Image(systemName: "checkmark").foregroundStyle(Color.brandRed) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(option.selected ? Color.brandLine : Color.brandInk2)
                    .accessibilityAddTraits(option.selected ? .isSelected : [])
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.brandInk2)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search a language")
            .onChange(of: query) { _, text in model.send(EpisodeIntentLanguageQuery(text: text)) }
            .navigationTitle("Episode language")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .onAppear { query = state.languageQuery }
        .onDisappear { model.send(EpisodeIntentLanguageQuery(text: "")) }
    }
}

/// Pipeline chip: done (paper outline, ✓), active (cyan: work in progress), waiting (muted).
private struct StageChipView: View {
    let chip: StageChip

    var body: some View {
        let (fill, text, border): (Color, Color, Color) = {
            switch chip.state {
            case .done: return (.clear, .brandPaper, .brandPaper)
            case .active: return (.brandCyan, .brandInk, .brandCyan)
            default: return (.clear, .brandMuted, .brandLine)
            }
        }()
        Text(chip.state == .done ? "✓ \(chip.label)" : chip.label)
            .font(.brandBody(12, weight: .bold))
            .tracking(1.2)
            .foregroundStyle(text)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(fill, in: RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(border, lineWidth: 1))
    }
}

private struct BadgeView: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.brandBody(12, weight: .bold))
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 4))
    }
}

private struct LiveBadge: View {
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Color.brandCyan).frame(width: 8, height: 8)
            Text("LIVE").font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandCyan)
        }
    }
}

struct SharedVideo: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// Downloads a signed MP4 link to a temporary file named as the server signed it, for the share sheet.
enum VideoFile {
    static func download(_ link: String, as name: String) async -> URL? {
        guard let url = URL(string: link),
              let (file, response) = try? await URLSession.shared.download(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200
        else { return nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("videos", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: target)
        do {
            try FileManager.default.moveItem(at: file, to: target)
            return target
        } catch {
            return nil
        }
    }
}

/// The system share sheet: Save Video (Photos), Save to Files, AirDrop…
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
