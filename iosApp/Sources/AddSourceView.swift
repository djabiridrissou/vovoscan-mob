import Shared
import SwiftUI
import UniformTypeIdentifiers

/// "Add a source": a link (single source or repository) or a `.vovo` file, then a preview or the repository's
/// sources. With a repository in the route, browses it. No logic here (AddSourceViewModel).
struct AddSourceView: View {
    let route: AddSourceRoute
    @StateObject private var model = AddSourceModel()
    @State private var link = ""
    @State private var importing = false
    @State private var started = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Kicker(text: L("nav.sources"))
                Text(L("sources.addHeadline")).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper)
                ForEach(model.state.officialOffers, id: \.key) { offer in officialCard(offer) }
                Text(model.state.warning)
                    .font(.brandBody(13))
                    .foregroundStyle(Color.brandPaper.opacity(0.76))
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandYellow, lineWidth: 1))
                if let error = model.state.error {
                    MessageBanner(text: error, error: true) { model.send(AddSourceIntentDismissMessage.shared) }
                }
                if let notice = model.state.notice {
                    MessageBanner(text: notice, error: false) { model.send(AddSourceIntentDismissMessage.shared) }
                }
                switch model.state.step {
                case .input, .loading: linkInput
                case .preview: if let preview = model.state.preview { previewCard(preview) }
                case .repository: if let repo = model.state.repository { repositoryListing(repo) }
                }
                if model.state.step == .preview || model.state.step == .repository {
                    Button(L("sources.anotherLink")) { model.send(AddSourceIntentReset.shared) }.buttonStyle(GhostButtonStyle())
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .json, .data]) { result in
            guard case let .success(url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { return }
            model.send(AddSourceIntentImportFile(bytes: IosAppKt.bytesOf(data: data), name: url.lastPathComponent))
        }
        .alert(
            L("sources.newConnections"),
            isPresented: Binding(get: { model.state.hostsConfirmation != nil }, set: { _ in })
        ) {
            Button(L("common.continue")) { model.send(AddSourceIntentConfirmHosts.shared) }
            Button(L("common.cancel"), role: .cancel) { model.send(AddSourceIntentCancelHosts.shared) }
        } message: {
            Text(model.state.hostsConfirmation ?? "")
        }
        .sheet(
            isPresented: Binding(
                get: { model.state.showsSettingsForm },
                // Swiped down: the form is cancelled (a form closed by the ViewModel does not come through here).
                set: { if !$0 && model.state.showsSettingsForm { model.send(AddSourceIntentCancelSettings.shared) } }
            )
        ) {
            AddSourceSettingsSheet(model: model)
        }
        .onAppear {
            // Once per opening (not when coming back from a source's detail).
            guard !started else { return }
            started = true
            if let id = route.repositoryId { model.send(AddSourceIntentBrowse(repositoryId: id)) }
        }
        .task { await model.observe() }
    }

    /// vovoscan's own catalog (and any official source left off), first: one tap turns it on.
    private func officialCard(_ offer: OfficialOfferUi) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                SourceIcon(iconUrl: offer.iconUrl, initial: offer.initial)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(offer.name).font(.brandBody(17, weight: .bold)).foregroundStyle(Color.brandPaper)
                        Sticker(text: L("sources.official"))
                    }
                    Text(offer.kindsLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
            }
            if !offer.summary.isEmpty {
                Text(offer.summary).font(.brandBody(15)).foregroundStyle(Color.brandPaper.opacity(0.86))
            }
            Button(offer.actionLabel) { model.send(AddSourceIntentEnableOfficial(key: offer.key)) }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.brandRed, lineWidth: 1.5))
    }

    private var linkInput: some View {
        let loading = model.state.step == .loading
        return VStack(alignment: .leading, spacing: 12) {
            IntentField(
                label: L("sources.linkLabel"),
                text: $link,
                value: model.state.link,
                keyboard: .URL,
                placeholder: "https://"
            ) { model.send(AddSourceIntentLinkChanged(value: $0)) }
            Button(loading ? L("common.checking") : L("sources.addArrow")) {
                submitAfterCommittingInput {
                    model.send(AddSourceIntentLinkChanged(value: link))
                    model.send(AddSourceIntentSubmitLink.shared)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!model.state.canSubmit)
            .padding(.bottom, 6)
            Button(L("sources.chooseFile")) { importing = true }
                .buttonStyle(GhostButtonStyle())
                .disabled(loading)
        }
    }

    private func previewCard(_ preview: SourcePreviewUi) -> some View {
        SourceCard {
            SourceInfoBlock(info: preview.info)
            if let hosts = preview.newHostsLabel { Text(hosts).font(.brandBody(13)).foregroundStyle(Color.brandYellow) }
            if let status = preview.statusLabel { Text(status).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
            if let action = preview.actionLabel {
                Button(model.state.installing ? L("sources.installing") : "\(action) →") { model.send(AddSourceIntentInstall.shared) }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.state.installing)
                    .padding(.top, 4)
                    .padding(.bottom, 6)
            }
            if model.state.installedKey == preview.info.key {
                NavigationLink(value: SourceDetailRoute(key: preview.info.key)) { Text(L("sources.open")) }
                    .buttonStyle(GhostButtonStyle())
            }
        }
    }

    private func repositoryListing(_ repo: RepositoryListingUi) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(repo.name).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                Text(repo.url).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if let homepage = repo.homepage {
                    Text(homepage).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76))
                }
                Text(repo.countLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            }
            ForEach(repo.entries, id: \.key) { entry in entryRow(entry) }
        }
    }

    private func entryRow(_ entry: RepositoryEntryRow) -> some View {
        HStack(spacing: 12) {
            SourceIcon(iconUrl: entry.iconUrl, initial: entry.initial)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                let meta = [entry.versionLabel, entry.kindsLabel, entry.langsLabel].filter { !$0.isEmpty }
                Text(meta.joined(separator: " ✦ ")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if let rights = entry.rightsLabel { Text(rights).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
                if let changes = entry.changes { Text(changes).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76)) }
                if let status = entry.statusLabel { Text(status).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
            }
            Spacer(minLength: 0)
            if entry.busy {
                ProgressView().tint(Color.brandCyan)
            } else if let action = entry.actionLabel {
                Button(action) { model.send(AddSourceIntentInstallEntry(key: entry.key)) }
                    .buttonStyle(SmallActionStyle())
                    .disabled(model.state.installing)
            }
        }
        .padding(12)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
    }
}

/// Settings to fill before the source is enabled. The text of each field lives here (IntentField); Confirm resends
/// every value, then confirms.
private struct AddSourceSettingsSheet: View {
    @ObservedObject var model: AddSourceModel
    @State private var values: [String: String] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(model.state.settingsTitle ?? "Settings").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                ForEach(model.state.settingsForm, id: \.name) { field in
                    SettingInput(field: field, record: { values[field.name] = $0 }) {
                        model.send(AddSourceIntentSettingChanged(name: field.name, value: $0))
                    }
                }
                Button(model.state.installing ? L("sources.installing") : L("common.confirmArrow")) {
                    submitAfterCommittingInput {
                        for (name, value) in values { model.send(AddSourceIntentSettingChanged(name: name, value: value)) }
                        model.send(AddSourceIntentConfirmSettings.shared)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.state.installing)
                .padding(.bottom, 6)
                Button(L("common.cancel")) { model.send(AddSourceIntentCancelSettings.shared) }.buttonStyle(GhostButtonStyle())
            }
            .padding(20)
        }
        .background(Color.brandInk2)
    }
}
