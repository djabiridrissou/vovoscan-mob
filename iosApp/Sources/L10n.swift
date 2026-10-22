import Shared

/// The app's own words in the UI locale (`core/common/i18n/<locale>.json`, shared with the web): never an English
/// literal in a view. Views are rebuilt when the locale changes (`RootView`), so a plain call is enough.
func L(_ key: String) -> String { I18n.shared.text(key: key) }

/// With named arguments; numbers go as Kotlin numbers so plurals pick the right form.
func L(_ key: String, _ args: [String: Any]) -> String {
    var bridged: [String: Any] = [:]
    for (name, value) in args {
        switch value {
        case let int as Int: bridged[name] = KotlinLong(value: Int64(int))
        case let double as Double: bridged[name] = KotlinDouble(value: double)
        default: bridged[name] = value
        }
    }
    return I18n.shared.textWith(key: key, args: bridged)
}
