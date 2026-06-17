import SwiftUI

// Typography roles from the design (Section A · 字体):
//   • Space Grotesk → wordmark / titles / labels
//   • System UI     → body
//   • JetBrains Mono → auditable detail (fingerprints, SAS, IP, sizes, time)
// The two brand faces are not bundled, so `Font.custom` falls back to the
// system face when they are absent; the mono role always renders monospaced
// via the SF Mono system design so auditable detail stays fixed-width.

// Dynamic Type (frontend UX plan M-A6): every role is built with
// `Font.custom(_:size:relativeTo:)` so the explicit design point size is the
// *base* and the text scales with the system Dynamic Type / accessibility text
// size relative to a text style. `Font.system(size:)` does not scale, so the
// roles are expressed as named faces (with the documented system fallback) plus
// `relativeTo:`. The fixed-width popover/windows clamp the upper range via
// `.hyphenDynamicTypeClamp()` so large sizes scale without breaking layout.
extension Font {
    /// Space Grotesk display/title face (falls back to system if unavailable),
    /// scaling relative to `.title3`.
    static func hyphenTitle(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        .custom("Space Grotesk", size: size, relativeTo: .title3).weight(weight)
    }

    /// JetBrains Mono role — guaranteed monospaced (system fixed-width fallback)
    /// for fingerprints, SAS codes, IPs, byte counts, and timestamps, scaling
    /// relative to `.body`.
    static func hyphenMono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("JetBrains Mono", size: size, relativeTo: .body).weight(weight).monospaced()
    }

    /// System body face at an explicit base size/weight, scaling relative to
    /// `.body`. "SF Pro Text" is the macOS system text face; an unavailable name
    /// falls back to the system font, which is the same face.
    static func hyphenBody(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("SF Pro Text", size: size, relativeTo: .body).weight(weight)
    }
}

extension View {
    /// Clamp Dynamic Type to a sane upper bound for the fixed-width menu-bar
    /// popover and borderless windows: text still scales for accessibility, but
    /// extreme sizes can't shatter a 344pt-wide fixed layout (M-A6).
    func hyphenDynamicTypeClamp() -> some View {
        dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}
