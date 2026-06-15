import SwiftUI

// Shared brand layer for the Hyphen macOS UI (design handoff "Hyphen Apps.dc.html",
// Section A · 品牌系统). Color tokens are a 1:1 port of the design's dark/light
// palettes. The accent (emerald) is reserved for the "connected" state and
// primary actions; monospace is reserved for auditable detail (fingerprints,
// SAS codes, IPs). See HyphenTypography.swift.

/// One resolved palette (dark or light). Token names mirror the design CSS vars.
struct HyphenPalette {
    let canvas: Color
    let surface: Color
    let surface2: Color
    let surface3: Color
    let hair: Color
    let hair2: Color
    let text: Color
    let dim: Color
    let faint: Color
    let accent: Color
    let accent2: Color
    let accentInk: Color
    let accentSoft: Color
    let amber: Color
    let amberSoft: Color
    let red: Color
    let redSoft: Color
    let blue: Color

    static let dark = HyphenPalette(
        canvas: .hex(0x0b0c0f),
        surface: .hex(0x16181d),
        surface2: .hex(0x1c1f25),
        surface3: .hex(0x23262e),
        hair: .white.opacity(0.07),
        hair2: .white.opacity(0.13),
        text: .hex(0xe8eaef),
        dim: .hex(0x9298a3),
        faint: .hex(0x646a75),
        accent: .hex(0x2bc48f),
        accent2: .hex(0x36d6a0),
        accentInk: .hex(0x04140d),
        accentSoft: .rgba(43, 196, 143, 0.15),
        amber: .hex(0xe0a23a),
        amberSoft: .rgba(224, 162, 58, 0.15),
        red: .hex(0xe5645e),
        redSoft: .rgba(229, 100, 94, 0.15),
        blue: .hex(0x5b9cf6)
    )

    static let light = HyphenPalette(
        canvas: .hex(0xe9ebef),
        surface: .hex(0xffffff),
        surface2: .hex(0xf5f6f8),
        surface3: .hex(0xeceef2),
        hair: .rgba(16, 22, 32, 0.09),
        hair2: .rgba(16, 22, 32, 0.15),
        text: .hex(0x191c22),
        dim: .hex(0x5a616c),
        faint: .hex(0x878d98),
        accent: .hex(0x0c9266),
        accent2: .hex(0x0a7d57),
        accentInk: .hex(0xffffff),
        accentSoft: .rgba(12, 146, 102, 0.12),
        amber: .hex(0xb9791a),
        amberSoft: .rgba(185, 121, 26, 0.13),
        red: .hex(0xcf3f39),
        redSoft: .rgba(207, 63, 57, 0.12),
        blue: .hex(0x2f6fe0)
    )

    static func forScheme(_ scheme: ColorScheme) -> HyphenPalette {
        scheme == .light ? .light : .dark
    }
}

private struct HyphenPaletteKey: EnvironmentKey {
    static let defaultValue = HyphenPalette.dark
}

extension EnvironmentValues {
    /// Active Hyphen palette. Injected by `.hyphenThemed()`; defaults to dark.
    var hyphenPalette: HyphenPalette {
        get { self[HyphenPaletteKey.self] }
        set { self[HyphenPaletteKey.self] = newValue }
    }
}

extension View {
    /// Resolves the palette from the current color scheme and publishes it on
    /// the environment so every nested `@Environment(\.hyphenPalette)` matches
    /// the system appearance. Apply once at each surface root.
    func hyphenThemed() -> some View {
        modifier(HyphenThemedModifier())
    }
}

private struct HyphenThemedModifier: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content.environment(\.hyphenPalette, .forScheme(scheme))
    }
}

extension Color {
    /// 0xRRGGBB literal → Color (sRGB).
    static func hex(_ value: UInt32) -> Color {
        Color(
            .sRGB,
            red: Double((value >> 16) & 0xff) / 255,
            green: Double((value >> 8) & 0xff) / 255,
            blue: Double(value & 0xff) / 255,
            opacity: 1
        )
    }

    /// CSS rgba(r,g,b,a) with 0–255 channels.
    static func rgba(_ r: Double, _ g: Double, _ b: Double, _ a: Double) -> Color {
        Color(.sRGB, red: r / 255, green: g / 255, blue: b / 255, opacity: a)
    }
}
