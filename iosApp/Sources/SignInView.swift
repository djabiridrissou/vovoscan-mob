import Shared
import SwiftUI

struct SignInView: View {
    @StateObject private var model = SignInModel()

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
                    IntentField(label: "Invite code", value: model.state.inviteCode, error: model.state.inviteError) {
                        model.send(SignInIntentInviteCode(value: $0))
                    }
                    IntentField(label: "Display name", value: model.state.displayName) { model.send(SignInIntentDisplayName(value: $0)) }
                }
                IntentField(label: "Email", value: model.state.email, error: model.state.emailError, keyboard: .emailAddress) {
                    model.send(SignInIntentEmail(value: $0))
                }
                IntentField(label: "Password", value: model.state.password, error: model.state.passwordError, secret: true) {
                    model.send(SignInIntentPassword(value: $0))
                }
                if let error = model.state.error { ErrorText(text: error) }
                Button(model.state.submitLabel) { submitAfterCommittingInput { model.send(SignInIntentSubmit.shared) } }
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
