import SwiftUI
import HyphenNotifications

/**
 * Builds the settings catalog from packages actually observed in this process
 * plus packages that already have an explicit saved mode. Observed inventory is
 * intentionally not persisted: only the user's per-package mode dictionary is.
 */
enum NotificationPrivacyCatalog {
    private struct Metadata {
        let name: String
        let glyph: String
        let tint: Color
    }

    private static let known: [String: Metadata] = [
        "com.google.android.gm": Metadata(name: "Gmail", glyph: "G", tint: .hex(0xd6453f)),
        "com.tencent.mm": Metadata(name: "微信", glyph: "微", tint: .hex(0x1f9d57)),
        "org.telegram.messenger": Metadata(name: "Telegram", glyph: "T", tint: .hex(0x2f6fe0)),
    ]

    static func rows(
        observedPackages: Set<String>,
        storedModes: [String: NotificationPrivacyMode],
        defaultMode: NotificationPrivacyMode
    ) -> [SettingsAppPrivacyRow] {
        observedPackages
            .union(storedModes.keys)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .sorted()
            .map { packageName in
                let metadata = known[packageName] ?? fallbackMetadata(packageName)
                return SettingsAppPrivacyRow(
                    name: metadata.name,
                    packageName: packageName,
                    glyph: metadata.glyph,
                    tint: metadata.tint,
                    mode: storedModes[packageName] ?? defaultMode,
                    isExplicit: storedModes[packageName] != nil
                )
            }
    }

    private static func fallbackMetadata(_ packageName: String) -> Metadata {
        let tail = packageName.split(separator: ".").last.map(String.init) ?? packageName
        let glyph = tail.first.map { String($0).uppercased() } ?? "?"
        return Metadata(name: packageName, glyph: glyph, tint: .hex(0x5b626d))
    }
}
