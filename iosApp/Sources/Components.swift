import SwiftUI

/// Primary button: red background, ink text, ink border, hard paper shadow (no blur).
/// When pressed, it sinks by 2 pt and its shadow shrinks (150 ms), with haptic feedback.
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PrimaryButton(configuration: configuration)
    }

    /// Disabled: flat ink with a hairline and muted text, no offset shadow, so it never reads as a faded action.
    private struct PrimaryButton: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let shadow: CGFloat = configuration.isPressed ? 2 : 6
            configuration.label
                .font(.brandBody(16, weight: .bold))
                .foregroundStyle(enabled ? Color.brandInk : Color.brandMuted)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(enabled ? Color.brandRed : Color.brandInk2, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(enabled ? Color.brandInk : Color.brandLine, lineWidth: enabled ? 2 : 1))
                .background(
                    RoundedRectangle(cornerRadius: 8).fill(enabled ? Color.brandPaper : Color.clear).offset(x: shadow, y: shadow)
                )
                .offset(x: 6 - shadow, y: 6 - shadow)
                .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
                .onChange(of: configuration.isPressed) { _, pressed in
                    if pressed && enabled { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
                }
        }
    }
}

struct GhostButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.brandBody(16, weight: .bold))
            .foregroundStyle(Color.brandPaper)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandLine, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// App text field: 13 pt label above, `inputBg` background, `line` border.
struct BrandField: View {
    let label: String
    @Binding var text: String
    var error: String?
    var secret = false
    var keyboard: UIKeyboardType = .default
    var placeholder: String?
    var help: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            Group {
                if secret {
                    SecureField("", text: $text)
                } else {
                    TextField("", text: $text, prompt: placeholder.map { Text($0).foregroundColor(Color.brandMuted) })
                        .keyboardType(keyboard)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.brandBody(15))
            .foregroundStyle(Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(error == nil ? Color.brandLine : Color.brandDanger, lineWidth: 1))
            if let help { Text(help).font(.brandBody(13)).foregroundStyle(Color.brandMuted) }
            if let error { ErrorText(text: error) }
        }
    }
}

struct ErrorText: View {
    let text: String
    var body: some View { Text(text).font(.brandBody(13)).foregroundStyle(Color.brandDanger) }
}

/// Offline banner: discreet, the local copy stays in focus. `onRetry`: a source that may answer again.
struct OfflineNotice: View {
    let text: String
    var onRetry: (() -> Void)?
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color.brandMuted).frame(width: 8, height: 8)
            Text(text).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let onRetry {
                Button(L("common.retry"), action: onRetry).font(.brandBody(13, weight: .bold)).foregroundStyle(Color.brandRed)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: Capsule())
        .overlay(Capsule().stroke(Color.brandLine, lineWidth: 1))
    }
}

/// "Kicker" badge: paper background, ink text, uppercase, tilted -2°.
struct Kicker: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.brandBody(12, weight: .bold))
            .tracking(1.4)
            .foregroundStyle(Color.brandInk)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.brandPaper)
            .rotationEffect(.degrees(-2))
    }
}

/// Logo: red square tilted -6°, paper border, hard paper shadow, "ヴ" in ink.
struct LogoMark: View {
    var size: CGFloat = 32
    var body: some View {
        ZStack {
            Rectangle().fill(Color.brandPaper).offset(x: 3, y: 3)
            Rectangle().fill(Color.brandRed).overlay(Rectangle().stroke(Color.brandPaper, lineWidth: 2))
            Text("ヴ").font(.brandDisplay(size * 0.55)).foregroundStyle(Color.brandInk)
        }
        .frame(width: size, height: size)
        .rotationEffect(.degrees(-6))
    }
}

/// Field wired to a Kotlin ViewModel. The text lives on the Swift side (`text`) and each keystroke is sent as an
/// intent: Kotlin state arrives asynchronously and, read back directly, would overwrite the input in progress
/// (characters lost when typing fast). A value cleared by the ViewModel (password after sign-in, mode switch) is applied.
struct IntentField: View {
    let label: String
    @Binding var text: String
    let value: String
    var error: String?
    var secret = false
    var keyboard: UIKeyboardType = .default
    var placeholder: String?
    var help: String?
    let onChange: (String) -> Void

    var body: some View {
        BrandField(label: label, text: $text, error: error, secret: secret, keyboard: keyboard, placeholder: placeholder, help: help)
            .onAppear { if text.isEmpty { text = value } }
            .onChange(of: text) { _, typed in onChange(typed) }
            .onChange(of: value) { _, latest in if latest.isEmpty && !text.isEmpty { text = "" } }
    }
}

/// Submits a form after dismissing the keyboard: iOS only commits the pending input (last composed character)
/// to the field when it loses focus. Without this detour, the last typed character would arrive after the submit.
/// The form then resends the exact value of each field before submitting: no race is possible.
/// It waits for the keyboard to be gone (the field has handed over its text by then), with a safety delay: a fixed
/// 0.1 s was sometimes too short and the last characters were lost.
@MainActor
func submitAfterCommittingInput(_ submit: @escaping () -> Void) {
    let center = NotificationCenter.default
    var token: NSObjectProtocol?
    var done = false
    let fire = {
        guard !done else { return }
        done = true
        if let token { center.removeObserver(token) }
        // One more turn of the run loop: the field's last onChange runs before the submit.
        DispatchQueue.main.async { submit() }
    }
    token = center.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { _ in fire() }
    let hadFocus = UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    DispatchQueue.main.asyncAfter(deadline: .now() + (hadFocus ? 0.8 : 0.05)) { fire() }
}
