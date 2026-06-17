package dev.hyphen.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Ported design tokens (frontend UX plan B / "port, don't redesign"). These are
// the same dark palette the classic-View MainActivity used as companion
// constants and the macOS `HyphenPalette` mirrors — Compose is only the new
// rendering host. Mono stays reserved for auditable detail (fingerprints, SAS,
// IPs, sizes, timestamps).

@Immutable
data class HyphenPalette(
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val hair: Color,
    val hair2: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val amber: Color,
    val red: Color,
    val blue: Color,
)

val HyphenDarkPalette = HyphenPalette(
    canvas = Color(0xFF101318),
    surface = Color(0xFF1A1D23),
    surface2 = Color(0xFF23262E),
    surface3 = Color(0xFF2B3038),
    hair = Color(0x24FFFFFF),
    hair2 = Color(0x33FFFFFF),
    text = Color(0xFFE8EAEF),
    dim = Color(0xFF9298A3),
    faint = Color(0xFF646A75),
    accent = Color(0xFF2BC48F),
    accentInk = Color(0xFF04140D),
    accentSoft = Color(0x332BC48F),
    amber = Color(0xFFF4BF4F),
    red = Color(0xFFEC6A5E),
    blue = Color(0xFF5B9BD5),
)

// Light parity (frontend UX plan §5 / M-A6 cross-platform): a high-contrast
// light variant of the same tokens so the app is usable in light mode.
val HyphenLightPalette = HyphenPalette(
    canvas = Color(0xFFE9EBEF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1F3F6),
    surface3 = Color(0xFFE3E6EB),
    hair = Color(0x14000000),
    hair2 = Color(0x24000000),
    text = Color(0xFF15171C),
    dim = Color(0xFF5A616C),
    faint = Color(0xFF8A909B),
    accent = Color(0xFF12996B),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x2212996B),
    amber = Color(0xFFB8860B),
    red = Color(0xFFC0392B),
    blue = Color(0xFF2F6FB0),
)

val LocalHyphenPalette = staticCompositionLocalOf { HyphenDarkPalette }

object HyphenTheme {
    val palette: HyphenPalette
        @Composable @ReadOnlyComposable get() = LocalHyphenPalette.current

    /** Monospace text style — reserved for auditable detail. */
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = weight)
}

private val HyphenTypography = Typography()

@Composable
fun HyphenTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (dark) HyphenDarkPalette else HyphenLightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            background = palette.canvas,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            error = palette.red,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            background = palette.canvas,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            error = palette.red,
        )
    }
    CompositionLocalProvider(LocalHyphenPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HyphenTypography, content = content)
    }
}
