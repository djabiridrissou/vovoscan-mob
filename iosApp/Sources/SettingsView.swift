import Shared
import SwiftUI

/// Settings tab: credits, the creator studio (on the web), the user's own provider keys, the reading model, what the
/// keys were charged, and signing out.
struct SettingsView: View {
    let session: Session
    let onSignOut: () -> Void
    let onReplayTutorial: () -> Void
    @StateObject private var wallet = WalletModel()
    @StateObject private var keys = ProviderKeysModel()
    @StateObject private var reading = ReadingSettingsModel()
    @StateObject private var usage = UsageModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(kicker: L("nav.settings"), title: L("settings.headline"), subtitle: session.email)
                if wallet.state.shown { WalletCard(state: wallet.state).riseIn() }
                StudioCard(isCreator: session.isCreator).riseIn(1)
                VStack(spacing: 8) {
                    NavigationLink(value: SettingsRoute.account) {
                        SettingsRow(icon: "person.crop.circle", title: L("settings.tabs.account"), detail: session.displayName)
                    }
                    NavigationLink(value: SettingsRoute.preferences) {
                        SettingsRow(icon: "globe", title: L("settings.tabs.preferences"), detail: L("prefs.lead"))
                    }
                }
                .buttonStyle(PressableCardStyle())
                .riseIn(2)
                KeysSection(keys: keys)
                readingSection
                costsSection
                Button(L("common.signOut"), action: onSignOut).buttonStyle(GhostButtonStyle()).padding(.top, 8)
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .refreshable {
            wallet.send(WalletIntentRefresh.shared)
            keys.send(ProviderKeysIntentRefresh.shared)
            reading.send(ReadingSettingsIntentRefresh.shared)
            usage.send(UsageIntentRefresh.shared)
        }
        .task { await wallet.observe() }
        .task { await keys.observe() }
        .task { await reading.observe() }
        .task { await usage.observe() }
        .keyRemovalAlert(keys)
        .navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .account: AccountView()
            case .preferences: PreferencesView(onReplayTutorial: onReplayTutorial)
            }
        }
    }

    // Reading model.

    private var readingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: L("settings.readingModel"))
            if let notice = reading.state.offlineNotice { OfflineNotice(text: notice) }
            ForEach(reading.state.options, id: \.label) { option in
                Button {
                    reading.send(ReadingSettingsIntentSelect(model: option.model))
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .stroke(option.selected ? Color.brandRed : Color.brandMuted, lineWidth: 2)
                            .frame(width: 18, height: 18)
                            .overlay(Circle().fill(option.selected ? Color.brandRed : Color.clear).padding(4))
                        Text(option.label).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        Spacer()
                    }
                    .padding(16)
                    .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(option.selected ? Color.brandRed : Color.brandLine, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option.selected ? .isSelected : [])
            }
            Text(reading.state.footnote).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            if let notice = reading.state.notice { Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
            if let error = reading.state.error { ErrorText(text: error) }
        }
    }

    // Costs.

    private var costsSection: some View {
        let state = usage.state
        return VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: L("settings.costs"))
            HStack(spacing: 8) {
                ForEach(state.periods, id: \.label) { chip in
                    Button(chip.label) { usage.send(UsageIntentSelectPeriod(period: chip.period)) }
                        .buttonStyle(ChipStyle(selected: chip.selected))
                }
            }
            if state.loading {
                Text(L("usage.loading")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            } else if let error = state.error {
                ErrorText(text: error)
                Button(L("common.tryAgain")) { usage.send(UsageIntentRefresh.shared) }.buttonStyle(GhostButtonStyle())
            } else {
                if state.empty { Text(state.emptyLabel).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
                if !state.empty, let total = state.totalCostLabel { Text(total).font(.brandDisplay(26)).foregroundStyle(Color.brandPaper) }
                ForEach(state.totals, id: \.providerLabel) { total in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(total.providerLabel).font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                            Text(total.unitsLabel).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        Spacer()
                        Text(total.costLabel).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                    }
                }
                ForEach(state.entries, id: \.id) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text([entry.providerLabel, entry.modelLabel].compactMap { $0 }.joined(separator: " ✦ "))
                                .font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                            Text("\(entry.amountLabel) ✦ \(entry.dateLabel)").font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        Spacer()
                        Text(entry.costLabel).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                    }
                    .padding(14)
                    .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
                }
            }
            Text(state.footnote).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
        }
    }
}

/// The user's own provider keys (add, replace, remove): in Settings and opened from the tutorial's credits step.
struct KeysSection: View {
    @ObservedObject var keys: ProviderKeysModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: L("settings.keys"))
            if let notice = keys.state.offlineNotice { OfflineNotice(text: notice) }
            Text(keys.state.privacyNote).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            if let disabled = keys.state.disabledNotice { Text(disabled).font(.brandBody(15)).foregroundStyle(Color.brandPaper) }
            if let notice = keys.state.notice { Text(notice).font(.brandBody(13)).foregroundStyle(Color.brandPaper) }
            if let error = keys.state.error { ErrorText(text: error) }
            ForEach(keys.state.slots, id: \.providerLabel) { slot in
                VStack(alignment: .leading, spacing: 6) {
                    Text(slot.providerLabel).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                    Text(slot.purpose).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    if let key = slot.key {
                        Text([key.hintLabel, key.label].compactMap { $0 }.joined(separator: " ✦ "))
                            .font(.brandBody(15)).foregroundStyle(Color.brandPaper)
                        Text("\(key.addedLabel) ✦ \(key.lastUsedLabel)").font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    }
                    if keys.state.enabled {
                        HStack(spacing: 10) {
                            Button(slot.actionLabel) { keys.send(ProviderKeysIntentOpenForm(provider: slot.provider)) }
                                .buttonStyle(GhostButtonStyle())
                            if let key = slot.key {
                                Button(L("common.remove")) { keys.send(ProviderKeysIntentAskRemove(keyId: key.id)) }
                                    .buttonStyle(GhostButtonStyle())
                            }
                        }
                        .padding(.top, 4)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
            }
            if keys.state.formOpen { KeyForm(model: keys) }
        }
    }
}

/// The keys alone, pushed from the tutorial ("Add my keys").
struct KeysScreen: View {
    @StateObject private var keys = ProviderKeysModel()

    var body: some View {
        ScrollView {
            KeysSection(keys: keys).padding(20)
        }
        .background(Color.brandInk)
        .task { await keys.observe() }
        .keyRemovalAlert(keys)
    }
}

extension View {
    /// Asks before removing a key (the ViewModel's prompt).
    func keyRemovalAlert(_ keys: ProviderKeysModel) -> some View {
        alert(
            keys.state.removal?.title ?? "",
            isPresented: Binding(get: { keys.state.removal != nil }, set: { if !$0 { keys.send(ProviderKeysIntentCancelRemove.shared) } }),
            presenting: keys.state.removal
        ) { prompt in
            Button(prompt.confirmLabel, role: .destructive) { keys.send(ProviderKeysIntentConfirmRemove.shared) }
            Button(prompt.cancelLabel, role: .cancel) { keys.send(ProviderKeysIntentCancelRemove.shared) }
        } message: { prompt in
            Text(prompt.message)
        }
    }
}

/// Add or replace a key. The secret lives only in this field's `@State` (never in the ViewModel's state): every
/// keystroke goes out as `SecretChanged`, the field empties itself whenever `secretFieldResets` changes, and Save
/// resends both fields before submitting (iOS pitfalls in CLAUDE.md). A secure field with no autocorrection, no
/// capitalisation and no password AutoFill: it is not a password manager entry.
private struct KeyForm: View {
    @ObservedObject var model: ProviderKeysModel
    @State private var secret = ""
    @State private var label = ""

    var body: some View {
        let state = model.state
        VStack(alignment: .leading, spacing: 12) {
            Text(state.formTitle).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
            VStack(alignment: .leading, spacing: 6) {
                Text(L("keys.key")).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                SecureField("", text: $secret, prompt: Text(state.secretPlaceholder).foregroundColor(Color.brandMuted))
                    .textContentType(.oneTimeCode)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .privacySensitive()
                    .font(.brandBody(15))
                    .foregroundStyle(Color.brandPaper)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(state.secretError == nil ? Color.brandLine : Color.brandDanger, lineWidth: 1))
                    .onChange(of: secret) { _, typed in model.send(ProviderKeysIntentSecretChanged(value: typed)) }
                if let error = state.secretError { ErrorText(text: error) }
            }
            IntentField(label: L("keys.label"), text: $label, value: state.formLabel, error: state.labelError, placeholder: state.labelPlaceholder) {
                model.send(ProviderKeysIntentLabelChanged(value: $0))
            }
            if let error = state.formError { ErrorText(text: error) }
            Button(state.submitLabel) {
                submitAfterCommittingInput {
                    model.send(ProviderKeysIntentSecretChanged(value: secret))
                    model.send(ProviderKeysIntentLabelChanged(value: label))
                    model.send(ProviderKeysIntentSubmit.shared)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!state.canSubmit)
            Button(L("common.cancel")) { model.send(ProviderKeysIntentCloseForm.shared) }.buttonStyle(GhostButtonStyle())
        }
        .padding(16)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandRed, lineWidth: 1))
        // Emptied right after sending (whatever the answer) and when the form closes.
        .onChange(of: state.secretFieldResets) { secret = "" }
    }
}

/// Settings' own screens.
enum SettingsRoute: Hashable {
    case account, preferences
}

/// A row that opens a settings screen: icon, title, one muted line, chevron.
struct SettingsRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 20, weight: .semibold)).foregroundStyle(Color.brandRed).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                Text(detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .bold)).foregroundStyle(Color.brandMuted)
        }
        .padding(16)
        .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        .contentShape(Rectangle())
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text).font(.brandDisplay(18)).foregroundStyle(Color.brandPaper).padding(.top, 12)
    }
}

struct ChipStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.brandBody(13))
            .foregroundStyle(selected ? Color.brandInk : Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(selected ? Color.brandPaper : Color.brandInk2, in: Capsule())
            .overlay(Capsule().stroke(selected ? Color.brandPaper : Color.brandLine, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// Credits for runs on the platform's keys: the balance in the display font, why a run would wait.
private struct WalletCard: View {
    let state: WalletUiState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(state.balanceLabel)
                    .font(.brandDisplay(44))
                    .foregroundStyle(state.empty ? Color.brandDanger : Color.brandPaper)
                    .contentTransition(.numericText())
                Text(state.unitLabel).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandMuted)
                Spacer()
                Sticker(text: "クレ", color: .brandCyan, angle: 6)
            }
            if let warning = state.warning {
                Text(warning).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandYellow)
            }
            Text(state.explainer).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            if let error = state.error { ErrorText(text: error) }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack {
                Color.brandInk2
                HalftoneBackground(color: .brandCyan, spacing: 10, maxOpacity: 0.14)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.brandLine, lineWidth: 1))
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.brandCyan).offset(x: 5, y: 5))
        .padding(.trailing, 5)
        .accessibilityElement(children: .combine)
    }
}

/// The creator studio lives on the web only (uploads, translation tools, releases): the app links to it.
private struct StudioCard: View {
    let isCreator: Bool
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            Haptics.tap()
            if let url = AppEnvironment.studioUrl { openURL(url) }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "paintbrush.pointed.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color.brandInk)
                    .frame(width: 40, height: 40)
                    .background(Color.brandYellow, in: RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 2) {
                    Text(isCreator ? L("settings.studio") : L("settings.publish"))
                        .font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandPaper)
                    Text(isCreator ? L("settings.studioDetail") : L("settings.publishDetail"))
                        .font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                }
                Spacer()
                Image(systemName: "arrow.up.right").font(.system(size: 13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
            .padding(14)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(PressableCardStyle())
        .accessibilityHint(L("settings.opensWebsite"))
    }
}
