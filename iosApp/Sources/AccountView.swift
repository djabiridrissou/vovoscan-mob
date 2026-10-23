import Shared
import SwiftUI

/// Settings › Account: profile, email, password, devices, deletion. Passwords live only in this screen's fields and
/// go out once, inside the intent that sends them; the fields empty themselves whenever `secretFieldResets` changes.
struct AccountView: View {
    @StateObject private var model = AccountModel()
    @State private var name = ""
    @State private var emailDraft = ""
    @State private var emailPassword = ""
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var deletePassword = ""
    @State private var confirmingSignOutOthers = false

    var body: some View {
        let state = model.state
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(kicker: L("settings.tabs.account"), title: state.name.isEmpty ? state.email : state.name, subtitle: state.lead)
                if let notice = state.notice { Notice(text: notice) }
                if let error = state.error { ErrorText(text: error) }
                profile(state)
                email(state)
                password(state)
                devices(state)
                deletion(state)
            }
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.brandInk)
        .refreshable { model.send(AccountIntentRefreshDevices.shared) }
        .sensoryFeedback(.error, trigger: state.error)
        .sensoryFeedback(.success, trigger: state.notice)
        .onChange(of: state.secretFieldResets) { _, _ in
            emailPassword = ""
            currentPassword = ""
            newPassword = ""
            deletePassword = ""
        }
        .confirmationDialog(state.signOutOthersLabel, isPresented: $confirmingSignOutOthers, titleVisibility: .visible) {
            Button(state.signOutOthersLabel, role: .destructive) { model.send(AccountIntentSignOutOthers.shared) }
            Button(L("common.cancel"), role: .cancel) {}
        }
        .task { await model.observe() }
        .onDisappear { model.send(AccountIntentDismissMessage.shared) }
    }

    private func profile(_ state: AccountUiState) -> some View {
        Card {
            SectionTitle(text: state.profileTitle)
            IntentField(label: state.nameLabel, text: $name, value: state.name, help: state.nameHint) {
                model.send(AccountIntentName(value: $0))
            }
            .textContentType(.nickname)
            Button(state.saveLabel) {
                submitAfterCommittingInput {
                    model.send(AccountIntentName(value: name))
                    model.send(AccountIntentSaveName.shared)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!state.canSaveName)
        }
    }

    private func email(_ state: AccountUiState) -> some View {
        Card {
            SectionTitle(text: state.emailTitle)
            HStack(spacing: 8) {
                Text(state.email).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                Text(state.emailStatus)
                    .font(.brandBody(12, weight: .bold))
                    .foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(state.emailVerified ? Color.brandCyan : Color.brandYellow, in: Capsule())
            }
            if let sent = state.emailSent { Notice(text: sent) }
            if state.emailFormOpen {
                IntentField(label: state.emailFieldLabel, text: $emailDraft, value: state.emailDraft, keyboard: .emailAddress) {
                    model.send(AccountIntentNewEmail(value: $0))
                }
                .textContentType(.emailAddress)
                BrandField(label: state.passwordLabel, text: $emailPassword, secret: true, help: state.emailPasswordHint)
                    .textContentType(.password)
                HStack(spacing: 10) {
                    Button(L("common.cancel")) { model.send(AccountIntentCloseEmailForm.shared) }.buttonStyle(GhostButtonStyle())
                    Button {
                        submitAfterCommittingInput {
                            model.send(AccountIntentNewEmail(value: emailDraft))
                            model.send(AccountIntentSubmitEmail(password: emailPassword))
                        }
                    } label: {
                        HStack(spacing: 8) {
                            if state.emailSending { ProgressView().tint(Color.brandInk) }
                            Text(state.changeEmailLabel)
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(state.emailSending)
                }
            } else {
                Button(state.changeEmailLabel) { model.send(AccountIntentOpenEmailForm.shared) }.buttonStyle(GhostButtonStyle())
            }
        }
    }

    private func password(_ state: AccountUiState) -> some View {
        Card {
            SectionTitle(text: state.passwordTitle)
            if let none = state.noPasswordNote {
                Text(none).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            } else {
                BrandField(label: state.currentPasswordLabel, text: $currentPassword, secret: true).textContentType(.password)
                BrandField(label: state.replacementPasswordLabel, text: $newPassword, secret: true, help: state.passwordHint).textContentType(.newPassword)
                if let done = state.passwordDone { Notice(text: done) }
                Button(state.changePasswordLabel) {
                    submitAfterCommittingInput { model.send(AccountIntentSubmitPassword(current: currentPassword, replacement: newPassword)) }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(state.passwordSaving)
            }
        }
    }

    private func devices(_ state: AccountUiState) -> some View {
        Card {
            SectionTitle(text: state.devicesTitle)
            Text(state.devicesLead).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            if state.devicesLoading && state.devices.isEmpty {
                ProgressView().tint(Color.brandCyan)
            } else if let error = state.devicesError {
                ErrorText(text: error)
                Button(L("common.tryAgain")) { model.send(AccountIntentRefreshDevices.shared) }.buttonStyle(GhostButtonStyle())
            }
            ForEach(state.devices, id: \.id) { row in
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: row.current ? "iphone.gen3" : "desktopcomputer")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(row.current ? Color.brandRed : Color.brandMuted)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.title).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandPaper)
                        Text(row.detail).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
                    }
                    Spacer()
                    if !row.current {
                        Button {
                            Haptics.tap()
                            model.send(AccountIntentSignOutDevice(id: row.id))
                        } label: {
                            if row.signingOut {
                                ProgressView().tint(Color.brandRed)
                            } else {
                                Text(state.signOutDeviceLabel).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandRed)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(row.signingOut)
                    }
                }
                .padding(.vertical, 6)
            }
            if state.hasOtherDevices {
                Button(state.signOutOthersLabel) { confirmingSignOutOthers = true }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(state.signingOutOthers)
            }
        }
    }

    private func deletion(_ state: AccountUiState) -> some View {
        Card(danger: true) {
            SectionTitle(text: state.deleteTitle)
            if let scheduled = state.deletionScheduled {
                Text(scheduled).font(.brandBody(15, weight: .bold)).foregroundStyle(Color.brandYellow)
                Button(state.keepLabel) { model.send(AccountIntentKeepAccount.shared) }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(state.deleting)
            } else if let owner = state.ownerNote {
                Text(owner).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            } else {
                Text(state.deleteLead).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                BrandField(label: state.deleteConfirm, text: $deletePassword, secret: true).textContentType(.password)
                Button {
                    Haptics.warning()
                    submitAfterCommittingInput { model.send(AccountIntentDelete(password: deletePassword)) }
                } label: {
                    HStack(spacing: 8) {
                        if state.deleting { ProgressView().tint(Color.brandDanger) }
                        Text(state.deleteLabel)
                    }
                    .font(.brandBody(16, weight: .bold))
                    .foregroundStyle(Color.brandDanger)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandDanger, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(state.deleting)
            }
        }
    }
}

/// Settings › Preferences: the app's language and region (with a live sample), the reading language, the tutorial.
struct PreferencesView: View {
    let onReplayTutorial: () -> Void
    @StateObject private var model = PreferencesModel()
    @StateObject private var reading = ReadingSettingsModel()
    @State private var regionsOpen = false
    @State private var readingOpen = false

    var body: some View {
        let state = model.state
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(kicker: L("settings.tabs.preferences"), title: state.language.title, subtitle: state.lead)
                if let error = state.error { ErrorText(text: error) }
                LanguageChoiceCard(
                    choice: state.language,
                    choose: { model.send(PreferencesIntentChooseLocale(code: $0)) },
                    openRegions: { regionsOpen = true }
                )
                Card {
                    SectionTitle(text: state.readingTitle)
                    Text(reading.state.languageHint).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    PickerRow(label: reading.state.languageLabel, working: reading.state.savingLanguage != nil) { readingOpen = true }
                    if let notice = reading.state.notice { Notice(text: notice) }
                    if let error = reading.state.error { ErrorText(text: error) }
                }
                Card {
                    SectionTitle(text: state.tourTitle)
                    Text(state.tourLead).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                    Button(state.tourAction) {
                        Haptics.press()
                        onReplayTutorial()
                    }
                    .buttonStyle(GhostButtonStyle())
                }
            }
            .padding(20)
        }
        .background(Color.brandInk)
        .sheet(isPresented: $regionsOpen) {
            RegionSheet(choice: state.language,
                        query: { model.send(PreferencesIntentRegionQuery(text: $0)) },
                        choose: { model.send(PreferencesIntentChooseRegion(code: $0)) })
        }
        .sheet(isPresented: $readingOpen) {
            ContentLanguageSheet(
                title: L("onboarding.reading.label"),
                rows: reading.state.languageRows,
                query: { reading.send(ReadingSettingsIntentLanguageQuery(text: $0)) },
                choose: { reading.send(ReadingSettingsIntentChooseLanguage(code: $0)) }
            )
        }
        .task { await model.observe() }
        .task { await reading.observe() }
    }
}

// Shared parts (account, preferences, tutorial).

/// A panel of the account screens; [danger] outlines it in red (deletion).
struct Card<Content: View>: View {
    var danger = false
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.brandInk2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(danger ? Color.brandDanger.opacity(0.6) : Color.brandLine, lineWidth: 1))
    }
}

/// A short confirmation in paper on a cyan rule.
struct Notice: View {
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            Rectangle().fill(Color.brandCyan).frame(width: 3)
            Text(text).font(.brandBody(13)).foregroundStyle(Color.brandPaper)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// A value that opens a picker: the choice, a chevron, a spinner while it saves.
struct PickerRow: View {
    let label: String
    var working = false
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.select()
            action()
        } label: {
            HStack {
                Text(label).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                Spacer()
                if working { ProgressView().tint(Color.brandCyan) }
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 13, weight: .bold)).foregroundStyle(Color.brandMuted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// The UI language (each in its own words), the region and the live sample of dates and numbers.
struct LanguageChoiceCard: View {
    let choice: LanguageChoiceUi
    let choose: (String) -> Void
    let openRegions: () -> Void

    var body: some View {
        Card {
            SectionTitle(text: choice.interfaceLabel)
            Text(choice.interfaceHint).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            HStack(spacing: 10) {
                ForEach(choice.locales, id: \.code) { option in
                    Button {
                        Haptics.select()
                        choose(option.code)
                    } label: {
                        Text(option.label)
                            .font(.brandBody(16, weight: .bold))
                            .foregroundStyle(option.selected ? Color.brandInk : Color.brandPaper)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(option.selected ? Color.brandRed : Color.brandInputBg, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(option.selected ? Color.brandInk : Color.brandLine, lineWidth: option.selected ? 2 : 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(option.selected ? .isSelected : [])
                }
            }
            Text(choice.regionTitle).font(.brandBody(13)).foregroundStyle(Color.brandMuted).padding(.top, 6)
            PickerRow(label: choice.regionLabel, action: openRegions)
            Text(choice.regionHint).font(.brandBody(12)).foregroundStyle(Color.brandMuted)
            Text(choice.preview)
                .font(.brandBody(13, weight: .bold))
                .foregroundStyle(Color.brandYellow)
                .contentTransition(.numericText())
            Text("\(choice.timeZoneTitle) ✦ \(choice.timeZone)").font(.brandBody(12)).foregroundStyle(Color.brandMuted)
        }
    }
}

/// Searchable regions, named in the UI language; "Not set" first.
struct RegionSheet: View {
    let choice: LanguageChoiceUi
    let query: (String) -> Void
    let choose: (String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            List(choice.regions, id: \.label) { row in
                Button {
                    Haptics.select()
                    choose(row.code)
                    dismiss()
                } label: {
                    HStack {
                        Text(row.label).font(.brandBody(16)).foregroundStyle(Color.brandPaper)
                        Spacer()
                        if let code = row.code { Text(code).font(.brandBody(12, weight: .bold)).foregroundStyle(Color.brandMuted) }
                        if row.selected { Image(systemName: "checkmark").foregroundStyle(Color.brandRed) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(row.selected ? Color.brandLine : Color.brandInk2)
                .accessibilityAddTraits(row.selected ? .isSelected : [])
            }
            .scrollContentBackground(.hidden)
            .background(Color.brandInk2)
            .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always), prompt: L("common.search"))
            .onChange(of: text) { _, typed in query(typed) }
            .navigationTitle(choice.regionTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
        .onDisappear { query("") }
    }
}

/// The one searchable picker of content languages: own name, then the name in the UI language.
struct ContentLanguageSheet: View {
    let title: String
    let rows: [LanguageRow]
    let query: (String) -> Void
    let choose: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            List(rows, id: \.code) { row in
                Button {
                    Haptics.select()
                    choose(row.code)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.title).font(.brandBody(16, weight: .bold)).foregroundStyle(Color.brandPaper)
                            Text(row.detail).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                        }
                        Spacer()
                        if row.selected { Image(systemName: "checkmark").foregroundStyle(Color.brandRed) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(row.selected ? Color.brandLine : Color.brandInk2)
                .accessibilityAddTraits(row.selected ? .isSelected : [])
            }
            .scrollContentBackground(.hidden)
            .background(Color.brandInk2)
            .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always), prompt: L("common.searchLanguage"))
            .onChange(of: text) { _, typed in query(typed) }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
        .onDisappear { query("") }
    }
}

/// Catalogue text with `<b>` runs drawn in bold.
func richText(_ runs: [RichRun], size: CGFloat = 14) -> Text {
    var text = AttributedString()
    for run in runs {
        var part = AttributedString(run.text)
        part.font = Font.brandBody(size, weight: run.bold ? .bold : .regular)
        text += part
    }
    return Text(text)
}
