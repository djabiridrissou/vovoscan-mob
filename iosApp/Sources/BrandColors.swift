import SwiftUI

/// Couleurs de la marque (`brand/tokens.json`), seules autorisées dans l'interface.
extension Color {
    static let brandInk = Color(hex: 0x0B0B0E)
    static let brandInk2 = Color(hex: 0x15151B)
    static let brandLine = Color(hex: 0x2A2A33)
    static let brandPaper = Color(hex: 0xF3EEE4)
    static let brandPaper2 = Color(hex: 0xE6DFD1)
    static let brandMuted = Color(hex: 0xA39E94)
    static let brandRed = Color(hex: 0xFF2D55)
    static let brandYellow = Color(hex: 0xFFD60A)
    static let brandCyan = Color(hex: 0x2DE2E6)
    static let brandDanger = Color(hex: 0xFF6B6B)
    static let brandInputBg = Color(hex: 0x0B0C10)

    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Dela Gothic One (titres) et Space Grotesk (texte) ; sans les fichiers, la police du système prend le relais.
extension Font {
    static func brandDisplay(_ size: CGFloat) -> Font { .custom("DelaGothicOne-Regular", size: size) }
    static func brandBody(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("SpaceGrotesk-Regular", size: size).weight(weight)
    }
}
