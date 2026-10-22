import Shared
import SwiftUI

/// Sign in, or join with an invite (invite-only platform). A printed-manga hero over the form; no logic here
/// (SignInViewModel decides labels, errors and the mode).
struct SignInView: View {
    @StateObject private var model = SignInModel()
    @State private var email = ""
    @State private var password = ""
    @State private var inviteCode = ""
    @State private var displayName = ""
    @State private var appeared = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                form
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
                    .padding(.bottom, 40)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.brandInk.ignoresSafeArea())
        .sensoryFeedback(.error, trigger: model.state.error)
        .task { await model.observe() }
        .onAppear { withAnimation(reduceMotion ? nil : .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.6)) { appeared = true } }
    }

    private var hero: some View {
        ZStack(alignment: .bottomLeading) {
            Color.brandInk2
            SpeedLines(center: UnitPoint(x: 0.82, y: 0.25))
            HalftoneBackground(color: .brandRed, spacing: 11, maxOpacity: 0.22)
            // Sound effects in the margin, as in a panel.
            Text("ドン!")
                .font(.brandDisplay(64))
                .foregroundStyle(Color.brandYellow)
                .rotationEffect(.degrees(10))
                .offset(x: appeared ? 0 : 40)
                .opacity(appeared ? 1 : 0)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(.top, 70)
                .padding(.trailing, 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    LogoMark(size: 40)
                    Text("vovoscan").font(.brandDisplay(20)).foregroundStyle(Color.brandPaper)
                }
                Text(model.state.title)
                    .font(.brandDisplay(40))
                    .foregroundStyle(Color.brandPaper)
                    .lineSpacing(-4)
                    .contentTransition(.opacity)
                HStack(spacing: 8) {
                    Kicker(text: L("auth.inviteOnly"))
                    Text(L("auth.tagline")).font(.brandBody(13)).foregroundStyle(Color.brandPaper.opacity(0.76))
                }
            }
            .padding(24)
            .offset(y: appeared ? 0 : 16)
            .opacity(appeared ? 1 : 0)
        }
        .frame(height: 340)
        .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 0, bottomTrailingRadius: 28))
        .overlay(alignment: .bottom) { Rectangle().fill(Color.brandRed).frame(height: 3) }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 16) {
            if model.state.mode == .invite {
                IntentField(label: L("auth.inviteCode"), text: $inviteCode, value: model.state.inviteCode, error: model.state.inviteError,
                            placeholder: "VOVO-XXXX-XXXX") {
                    model.send(SignInIntentInviteCode(value: $0))
                }
                .textContentType(.oneTimeCode)
                IntentField(label: L("auth.displayName"), text: $displayName, value: model.state.displayName) {
                    model.send(SignInIntentDisplayName(value: $0))
                }
                .textContentType(.nickname)
            }
            IntentField(label: L("auth.email"), text: $email, value: model.state.email, error: model.state.emailError, keyboard: .emailAddress) {
                model.send(SignInIntentEmail(value: $0))
            }
            .textContentType(.username)
            IntentField(label: L("auth.password"), text: $password, value: model.state.password, error: model.state.passwordError, secret: true) {
                model.send(SignInIntentPassword(value: $0))
            }
            .textContentType(model.state.mode == .invite ? .newPassword : .password)
            if let error = model.state.error { ErrorText(text: error).transition(.opacity) }
            Button {
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
            } label: {
                HStack(spacing: 10) {
                    if model.state.submitting { ProgressView().tint(Color.brandInk) }
                    Text(model.state.submitLabel)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.state.submitting)
            .padding(.top, 4)
            Button(model.state.switchLabel) {
                Haptics.select()
                withAnimation(.snappy) { model.send(SignInIntentSwitchMode.shared) }
            }
            .buttonStyle(GhostButtonStyle())
            HStack(spacing: 6) {
                Image(systemName: "lock.fill").font(.system(size: 11, weight: .bold))
                Text(L("auth.vault"))
            }
            .font(.brandBody(12))
            .foregroundStyle(Color.brandMuted)
            .padding(.top, 8)
        }
        .animation(.snappy, value: model.state.mode)
    }
}
