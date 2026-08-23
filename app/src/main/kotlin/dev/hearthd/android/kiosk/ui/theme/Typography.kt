package dev.hearthd.android.kiosk.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified

// Roboto Flex is a variable font (single .ttf, OFL). The Nix build fetches it
// content-addressed and stages it into assets/fonts/ — the same pattern as the
// wake-word models, so no font binary lives in git (see flake.nix + .gitignore).
private const val ROBOTO_FLEX_PATH = "fonts/roboto_flex.ttf"

// A kiosk is a wall/counter panel read at arm's length or further, so the
// stock Material type scale (tuned for a phone at ~30cm) runs small. Lift the
// whole scale uniformly rather than resizing widgets one at a time.
private const val KIOSK_SCALE = 1.15f

/**
 * The Roboto Flex family, drawn straight from the staged asset. Each weight is
 * one instance of the variable font pinned to a `wght` axis value (the default
 * variationSettings applies the weight axis), covering the weights the Material
 * type scale asks for.
 *
 * A local/IDE Gradle build doesn't run the Nix staging step, so the asset may be
 * absent; fall back to the system font family instead of throwing at first draw.
 */
fun robotoFlexFamily(assets: AssetManager): FontFamily {
    val present = runCatching { assets.open(ROBOTO_FLEX_PATH).close() }.isSuccess
    if (!present) return FontFamily.Default

    fun weight(value: Int) = Font(ROBOTO_FLEX_PATH, assets, FontWeight(value))
    return FontFamily(weight(400), weight(500), weight(700))
}

private fun TextStyle.forKiosk(family: FontFamily): TextStyle =
    copy(
        fontFamily = family,
        fontSize = if (fontSize.isSpecified) fontSize * KIOSK_SCALE else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * KIOSK_SCALE else lineHeight,
    )

/**
 * The Material 3 type scale, restyled onto [family] and scaled up for viewing
 * distance. Applied once at [androidx.compose.material3.MaterialTheme] so every
 * `MaterialTheme.typography.*` call across the app picks it up.
 */
fun kioskTypography(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.forKiosk(family),
        displayMedium = base.displayMedium.forKiosk(family),
        displaySmall = base.displaySmall.forKiosk(family),
        headlineLarge = base.headlineLarge.forKiosk(family),
        headlineMedium = base.headlineMedium.forKiosk(family),
        headlineSmall = base.headlineSmall.forKiosk(family),
        titleLarge = base.titleLarge.forKiosk(family),
        titleMedium = base.titleMedium.forKiosk(family),
        titleSmall = base.titleSmall.forKiosk(family),
        bodyLarge = base.bodyLarge.forKiosk(family),
        bodyMedium = base.bodyMedium.forKiosk(family),
        bodySmall = base.bodySmall.forKiosk(family),
        labelLarge = base.labelLarge.forKiosk(family),
        labelMedium = base.labelMedium.forKiosk(family),
        labelSmall = base.labelSmall.forKiosk(family),
    )
}
