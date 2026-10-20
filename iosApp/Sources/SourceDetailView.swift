import Shared
import SwiftUI

/// Detail of a source: what it is, where it connects, update, settings, show/hide, remove. No logic here
/// (SourceDetailViewModel).
struct SourceDetailView: View {
    @StateObject private var model: SourceDetailModel
    @Environment(\.dismiss) private var dismiss
    /// Text of each setting field, by name (IntentField).
    @State private var values: [String: String] = [:]

    init(route: SourceDetailRoute) {
        _model = StateObject(wrappedValue: SourceDetailModel(key: route.key))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if model.state.pendingSync { OfflineNotice(text: SourceLabels.shared.PENDING) }
                if let error = model.state.error {
                    MessageBanner(text: error, error: true) { model.send(SourceDetailIntentDismissMessage.shared) }
                }
                if let notice = model.state.notice {
                    MessageBanner(text: notice, error: false) { model.send(SourceDetailIntentDismissMessage.shared) }
                }
                if let info = model.state.info {
                    SourceInfoBlock(info: info)
                    if let update = model.state.update { updateCard(update) }
                    settingsSection
                    Button(model.state.visibilityLabel) { model.send(SourceDetailIntentToggleVisibility.shared) }
                        .buttonStyle(GhostButtonStyle())
                    if model.state.canRemove {
                        Button("Remove") { model.send(SourceDetailIntentRemove.shared) }.buttonStyle(GhostButtonStyle())
                    }
                }
            }
            .padding(20)
            .alert(
                model.state.removalTitle,
                isPresented: Binding(get: { model.state.confirmingRemoval }, set: { _ in })
            ) {
                Button("Remove", role: .destructive) { model.send(SourceDetailIntentConfirmRemove.shared) }
                Button("Cancel", role: .cancel) { model.send(SourceDetailIntentCancelRemove.shared) }
            } message: {
                Text(model.state.removalMessage)
            }
        }
        .background(Color.brandInk)
        .alert(
            "New connections",
            isPresented: Binding(get: { model.state.hostsConfirmation != nil }, set: { _ in })
        ) {
            Button("Continue") { model.send(SourceDetailIntentConfirmHosts.shared) }
            Button("Cancel", role: .cancel) { model.send(SourceDetailIntentCancelHosts.shared) }
        } message: {
            Text(model.state.hostsConfirmation ?? "")
        }
        .onChange(of: model.state.gone) { gone in if gone { dismiss() } }
        .task { await model.observe() }
    }

    private func updateCard(_ update: SourceUpdateUi) -> some View {
        SourceCard {
            Text("Update available").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            Text(update.versionLabel).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
            if let changes = update.changes { Text(changes).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76)) }
            if let hosts = update.newHostsLabel { Text(hosts).font(.brandBody(13)).foregroundStyle(Color.brandYellow) }
            Button(model.state.working ? "Updating…" : "\(update.actionLabel) →") { model.send(SourceDetailIntentUpdate.shared) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.state.working)
                .padding(.top, 4)
                .padding(.bottom, 6)
        }
    }

    @ViewBuilder private var settingsSection: some View {
        if !model.state.settings.isEmpty {
            Text("Settings").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            if let notice = model.state.settingsNotice { Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandYellow) }
            if model.state.settingsEditable {
                ForEach(model.state.settings, id: \.name) { field in
                    SettingInput(field: field, record: { values[field.name] = $0 }) {
                        model.send(SourceDetailIntentSettingChanged(name: field.name, value: $0))
                    }
                }
                Button("Save →") {
                    submitAfterCommittingInput {
                        // The exact value of each field, then the save: the ViewModel handles intents in order.
                        for (name, value) in values { model.send(SourceDetailIntentSettingChanged(name: name, value: value)) }
                        model.send(SourceDetailIntentSaveSettings.shared)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!model.state.canSaveSettings)
                .opacity(model.state.canSaveSettings ? 1 : 0.5)
                .padding(.bottom, 6)
            } else {
                ForEach(model.state.settings, id: \.name) { SettingValue(field: $0) }
            }
        }
    }
}
