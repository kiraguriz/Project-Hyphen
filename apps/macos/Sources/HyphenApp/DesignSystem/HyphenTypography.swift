import SwiftUI

// Typography roles from the design (Section A · 字体):
//   • Space Grotesk → wordmark / titles / labels
//   • System UI     → body
//   • JetBrains Mono → auditable detail (fingerprints, SAS, IP, sizes, time)
// The two brand faces are not bundled, so `Font.custom` falls back to the
// system face when they are absent; the mono role always renders monospaced
// via the SF Mono system design so auditable detail stays fixed-width.

extension Font {
    /// Space Grotesk display/title face (falls back to system if unavailable).
    static func hyphenTitle(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        .custom("Space Grotesk", size: size).weight(weight)
    }

    /// JetBrains Mono role — guaranteed monospaced (SF Mono fallback) for
    /// fingerprints, SAS codes, IPs, byte counts, and timestamps.
    static func hyphenMono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    /// System body face at an explicit size/weight.
    static func hyphenBody(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }
}
