import Shared
import SwiftUI

struct SignInView: View {
    @StateObject private var model = SignInModel()
    @State private var email = ""
    @State private var password = ""
    @State private var inviteCode = ""
    @State private var displayName = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    LogoMark()
                    Text("vovoscan").font(.brandDisplay(18)).foregroundStyle(Color.brandPaper)
                }
                .padding(.bottom, 16)
                Text(model.state.title).font(.brandDisplay(34)).foregroundStyle(Color.brandPaper)
                Text("Your scans, fully voiced.").font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                if model.state.mode == .invite {
                    IntentField(label: "Invite code", text: $inviteCode, value: model.state.inviteCode, error: model.state.inviteError) {
                        model.send(SignInIntentInviteCode(value: $0))
                    }
                    IntentField(label: "Display name", text: $displayName, value: model.state.displayName) {
                        model.send(SignInIntentDisplayName(value: $0))
                    }
                }
                IntentField(label: "Email", text: $email, value: model.state.email, error: model.state.emailError, keyboard: .emailAddress) {
                    model.send(SignInIntentEmail(value: $0))
                }
                IntentField(label: "Password", text: $password, value: model.state.password, error: model.state.passwordError, secret: true) {
                    model.send(SignInIntentPassword(value: $0))
                }
                if let error = model.state.error { ErrorText(text: error) }
                Button(model.state.submitLabel) {
                    submitAfterCommittingInput {
                        // The exact value of each field, then the submit: the ViewModel handles intents in order.
                        model.send(SignInIntentEmail(value: email))
                        model.send(SignInIntentPassword(value: password))
                        if model.state.mode == .invite {
                            model.send(SignInIntentInviteCode(value: inviteCode))
                            model.send(SignInIntentDisplayName(value: displayName))
                        }
                        model.send(SignInIntentSubmit.shared)
                    }
                }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.state.submitting)
                Button(model.state.switchLabel) { model.send(SignInIntentSwitchMode.shared) }
                    .buttonStyle(GhostButtonStyle())
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 48)
        }
        .background(Color.brandInk)
        .task { await model.observe() }
    }
}
