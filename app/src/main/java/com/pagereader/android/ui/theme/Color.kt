package com.pagereader.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's own palette, replacing the Android Studio template's
 * Purple80/Pink40 constants (which were never touched and shipped as the
 * literal wizard defaults with `dynamicColor = true`, so the OS accent color
 * -- whatever wallpaper happened to produce -- recoloured a document scanner).
 *
 * Three roles, chosen for what this app actually is: a camera held at
 * arm's length in daylight or a dim room, and a page read back afterwards.
 *
 *  - **Ink** -- near-black background. A camera screen is mostly the live
 *    preview or a photo; a bright chrome around it competes with both and
 *    (per CLAUDE.md) wastes battery on an OLED-adjacent panel for a user who
 *    is not looking at the chrome anyway.
 *  - **Ember** -- the one warm accent, reserved for the shutter and for
 *    "framed and about to capture". A single accent used sparingly reads as
 *    a deliberate signal; scattering Material's default tonal palette across
 *    every surface would bury it.
 *  - **Paper** -- a warm off-white, used only behind the dewarped page
 *    projection in READING. It is deliberately not pure white: the photo it
 *    sits behind is a photograph of paper, not a rendered document, and a
 *    stark #FFFFFF frame made that mismatch obvious on device.
 */

// -- Ink: dark surfaces --
val InkBackground = Color(0xFF0B0B0E)
val InkSurface = Color(0xFF17171B)
val InkSurfaceVariant = Color(0xFF232328)
val InkOnBackground = Color(0xFFECECEE)
val InkOutline = Color(0xFF3A3A40)

// -- Ember: the one warm accent (shutter, FRAMED / active state) --
val Ember = Color(0xFFFF6B35)
val EmberContainer = Color(0xFF4A2515)
val OnEmber = Color(0xFF2A0E00)

// -- Guidance state colours, keyed to FramingState (CaptureScreen / QuadOverlay) --
/** SEARCHING / ADJUSTING: something still needs correcting. */
val GuidanceAmber = Color(0xFFFFC24B)
/** STEADY: framed, held still, capture is about to fire. */
val GuidanceGreen = Color(0xFF5BD98A)

// -- Paper: light surface behind the dewarped page projection --
val Paper = Color(0xFFF6F1E7)
val OnPaper = Color(0xFF1B1A17)
val PaperVariant = Color(0xFFE9E2D3)

/**
 * Block outline/fill colours, one per [com.pagereader.android.ocr.BlockKind].
 *
 * Unchanged from the values `ExploreScreen.colourFor` used to define: they
 * are already WCAG-AA against [Paper]'s light ground and already paired
 * one-to-one with the spoken labels in `BlockLabels.title`, so a sighted
 * helper and a listener are told the same thing. `PageProjection.colourFor`
 * restyles how they are drawn (soft fills, rounded corners) -- never the
 * mapping itself.
 */
val BlockHeading = Color(0xFF1B5E20)
val BlockBody = Color(0xFF0D47A1)
val BlockCaption = Color(0xFF4A148C)
val BlockSidebar = Color(0xFF880E4F)
val BlockFigure = Color(0xFFE65100)
val BlockHeaderFooter = Color(0xFF424242)
val BlockSeparator = Color(0xFF616161)
