import Foundation

/// Localized-string lookup for the menu-bar app (frontend UX plan M-A6).
///
/// Strings resolve against the `HyphenApp` resource bundle (`Bundle.module`),
/// where `zh-Hans` is the default localization and `en.lproj` is the English
/// overlay. SwiftUI's `Text("literal")` would look in `Bundle.main`, which for
/// a SwiftPM target does not carry these tables — so every user-facing string
/// goes through `L(...)`, and SwiftUI call sites use `Text(L("key"))` (verbatim
/// of an already-localized string).
///
/// Auditable detail (fingerprints, SAS digits, IPs, byte sizes, timestamps,
/// protocol identifiers) stays unlocalized by design.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, bundle: .module, comment: "")
    guard !args.isEmpty else { return format }
    return String(format: format, locale: Locale.current, arguments: args)
}
