import Foundation
import UserNotifications

/// Opens each sealed push on the device (`PushSeal`) and writes its title and body in the app's language, the same
/// sentences as the inbox (`NotificationText.kt`). The server only ever sends a placeholder ("New activity") with the
/// sealed references: a push that doesn't open (another key, signed out) keeps it.
final class NotificationService: UNNotificationServiceExtension {
    private var handler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        handler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.content = content
        if let opened = PushSeal.open(request.content.userInfo) {
            let phrased = NotificationPhrases(locale: PushSeal.locale).of(kind: opened.kind, data: opened.data)
            content.title = phrased.title
            content.body = phrased.body
            content.threadIdentifier = opened.data["groupId"] ?? opened.kind
            var info = content.userInfo
            // Only references the app opens: never the sealed fields once read.
            for key in ["v", "k", "e", "n", "c"] { info.removeValue(forKey: key) }
            if let handle = opened.data["handle"] {
                info["link"] = "vovoscan://g/\(handle)" + (opened.data["gameId"].map { "?game=\($0)" } ?? "")
            }
            content.userInfo = info
        }
        contentHandler(content)
        handler = nil
    }

    override func serviceExtensionTimeWillExpire() {
        if let handler, let content { handler(content) }
        handler = nil
    }
}

/// `NotificationText.of`, in Swift.
struct NotificationPhrases {
    let locale: String

    private var lines: [String: String] { NotificationCatalog.lines[locale] ?? NotificationCatalog.lines["en"] ?? [:] }

    func of(kind: String, data: [String: String]) -> (title: String, body: String) {
        let group = data["groupName"] ?? ""
        let name = data["name"] ?? text("notifications.someone")
        let count = Int(data["count"] ?? "") ?? 1
        let game = data["game"].flatMap { lines["games.\($0).name"] } ?? text("games.aGame")
        let gameTitle = group.isEmpty ? game : group
        switch kind {
        case "group.request": return (group, text("notifications.groupRequest", ["name": name, "count": "\(count)", "group": group], count: count))
        case "group.approved": return (group, text("notifications.groupApproved", ["group": group]))
        case "group.declined": return (group, text("notifications.groupDeclined", ["group": group]))
        case "group.reply":
            if count > 1 { return (group, text("notifications.groupReplies", ["count": "\(count)"], count: count)) }
            return (group, text("notifications.groupReply", ["name": name, "excerpt": data["excerpt"] ?? ""]))
        case "group.badge":
            if data["approved"] == "true" { return (group, text("notifications.badgeApproved", ["group": group])) }
            return (group, text("notifications.badgeDenied", ["group": group, "note": data["note"] ?? ""]))
        case "game.invite": return (gameTitle, text("notifications.gameInvite", ["name": name, "game": game]))
        case "game.turn": return (gameTitle, text("notifications.gameTurn", ["game": game]))
        case "game.over":
            let winners = data["winners"] ?? ""
            if winners.isEmpty { return (gameTitle, text("notifications.gameOverDraw", ["game": game])) }
            return (gameTitle, text("notifications.gameOver", ["game": game, "names": winners]))
        default: return (text("notifications.title"), text("notifications.generic"))
        }
    }

    /// The catalogue's ICU subset these lines use: `{name}` and one `{count, plural, one {…} other {…}}` (`#` = count).
    private func text(_ key: String, _ args: [String: String] = [:], count: Int = 1) -> String {
        var message = lines[key] ?? key
        if message.hasPrefix("{count, plural,") {
            let one = locale == "fr" ? (count == 0 || count == 1) : count == 1
            message = branch(of: message, one ? "one" : "other") ?? branch(of: message, "other") ?? message
            message = message.replacingOccurrences(of: "#", with: "\(count)")
        }
        for (name, value) in args { message = message.replacingOccurrences(of: "{\(name)}", with: value) }
        return message
    }

    /// The text of one plural branch: from `one {` to its matching `}`.
    private func branch(of message: String, _ label: String) -> String? {
        guard let start = message.range(of: "\(label) {") else { return nil }
        var depth = 1
        var index = start.upperBound
        while index < message.endIndex {
            switch message[index] {
            case "{": depth += 1
            case "}":
                depth -= 1
                if depth == 0 { return String(message[start.upperBound ..< index]) }
            default: break
            }
            index = message.index(after: index)
        }
        return nil
    }
}
