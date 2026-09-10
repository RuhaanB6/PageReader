package com.pagereader.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * One deliberate dark scheme, always. `dynamicColor` is gone -- the OS
 * wallpaper-derived accent has no business recolouring a scanner whose whole
 * job is to be legible in the same way every time, and there is no light
 * variant to fall back to because there is no state in this app where a
 * bright chrome around the camera or the page projection is correct.
 *
 * [Paper] is deliberately not part of this scheme's `surface` role: it is
 * used explicitly, only behind the page projection in `PageScreen`, never
 * inherited implicitly by a `Surface` elsewhere. Baking it into
 * `colorScheme.surface` would make every card and dialog in the app
 * accidentally light.
 */
private val PageReaderColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = OnEmber,
    primaryContainer = EmberContainer,
    onPrimaryContainer = InkOnBackground,
    secondary = GuidanceAmber,
    onSecondary = OnEmber,
    tertiary = GuidanceGreen,
    onTertiary = OnEmber,
    background = InkBackground,
    onBackground = InkOnBackground,
    surface = InkSurface,
    onSurface = InkOnBackground,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkOnBackground,
    outline = InkOutline,
)

@Composable
fun PageReaderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PageReaderColorScheme,
        typography = Typography,
        content = content,
    )
}
