import SwiftUI

/// Bouton principal : fond rouge, texte encre, bordure encre, ombre dure papier (sans flou).
/// Appuyé, il s'enfonce de 2 pt et son ombre rétrécit (150 ms), avec un retour haptique.
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let shadow: CGFloat = configuration.isPressed ? 2 : 6
        configuration.label
            .font(.brandBody(16, weight: .bold))
            .foregroundStyle(Color.brandInk)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(Color.brandRed, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.brandInk, lineWidth: 2))
            .background(
                RoundedRectangle(cornerRadius: 8).fill(Color.brandPaper).offset(x: shadow, y: shadow)
            )
            .offset(x: 6 - shadow, y: 6 - shadow)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { pressed in
                if pressed { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
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

/// Champ de l'application : libellé 13 pt au-dessus, fond `inputBg`, bordure `line`.
struct BrandField: View {
    let label: String
    @Binding var text: String
    var error: String?
    var secret = false
    var keyboard: UIKeyboardType = .default

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
            Group {
                if secret { SecureField("", text: $text) } else { TextField("", text: $text).keyboardType(keyboard) }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.brandBody(15))
            .foregroundStyle(Color.brandPaper)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.brandInputBg, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(error == nil ? Color.brandLine : Color.brandDanger, lineWidth: 1))
            if let error { ErrorText(text: error) }
        }
    }
}

struct ErrorText: View {
    let text: String
    var body: some View { Text(text).font(.brandBody(13)).foregroundStyle(Color.brandDanger) }
}

/// Bandeau hors ligne : discret, la copie locale reste au centre.
struct OfflineNotice: View {
    let text: String
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color.brandMuted).frame(width: 8, height: 8)
            Text(text).font(.brandBody(13)).foregroundStyle(Color.brandMuted)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandInk2, in: Capsule())
        .overlay(Capsule().stroke(Color.brandLine, lineWidth: 1))
    }
}

/// Pastille « kicker » : fond papier, texte encre, majuscules, inclinée de -2°.
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

/// Logo : carré rouge incliné de -6°, bordure papier, ombre dure papier, « ヴ » en encre.
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

/// Champ branché sur un ViewModel Kotlin. Le texte vit ici, chaque frappe part en intention : l'état Kotlin arrive
/// en asynchrone et, relu directement, il écraserait la saisie en cours (caractères perdus en tapant vite).
/// Une valeur vidée par le ViewModel (mot de passe après connexion, changement de mode) est reprise.
struct IntentField: View {
    let label: String
    let value: String
    var error: String?
    var secret = false
    var keyboard: UIKeyboardType = .default
    let onChange: (String) -> Void
    @State private var text = ""

    var body: some View {
        BrandField(label: label, text: $text, error: error, secret: secret, keyboard: keyboard)
            .onAppear { text = value }
            .onChange(of: text) { onChange($0) }
            .onChange(of: value) { if $0.isEmpty && !text.isEmpty { text = "" } }
    }
}

/// Valide un formulaire après avoir rendu le clavier : iOS ne remet la frappe en cours (dernier caractère composé)
/// au champ qu'à la perte du focus. Sans ce détour, le dernier caractère tapé partirait après l'envoi.
@MainActor
func submitAfterCommittingInput(_ submit: @escaping () -> Void) {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { submit() }
}
