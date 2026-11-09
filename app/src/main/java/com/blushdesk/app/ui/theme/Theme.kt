package com.blushdesk.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Material 3 color scheme built from the brand palette. Text uses the specification's dark
 * (#212121) and muted (#757575) grays; containers and tints are pinks.
 */
private val ShowroomLightColors = lightColorScheme(
    // Rose Deep, not Vibrant Rose: white 14 sp text on Vibrant Rose is 4.3:1, under the 4.5:1 small
    // text needs, so filled buttons and pink text use the darker rose (5.9:1). Vibrant Rose stays
    // for accents, the receipt and the logo.
    primary = ShowroomColors.RoseDeep,
    onPrimary = ShowroomColors.White,
    primaryContainer = ShowroomColors.SoftPink,
    onPrimaryContainer = ShowroomColors.DeepMagenta,
    inversePrimary = Color(0xFFF48FB1),

    secondary = ShowroomColors.DeepMagenta,
    onSecondary = ShowroomColors.White,
    secondaryContainer = ShowroomColors.RoseTint,
    onSecondaryContainer = Color(0xFF4A0828),

    tertiary = Color(0xFFAD1457),
    onTertiary = ShowroomColors.White,
    tertiaryContainer = Color(0xFFFFD9E6),
    onTertiaryContainer = Color(0xFF5B0F32),

    background = ShowroomColors.LavenderBlush,
    onBackground = ShowroomColors.DarkText,

    surface = ShowroomColors.White,
    onSurface = ShowroomColors.DarkText,
    surfaceVariant = Color(0xFFF8E1EA),
    onSurfaceVariant = ShowroomColors.MutedText,
    surfaceTint = ShowroomColors.VibrantRose,
    surfaceContainerLowest = ShowroomColors.White,
    surfaceContainerLow = Color(0xFFFFF7FA),
    surfaceContainer = ShowroomColors.LavenderBlush,
    surfaceContainerHigh = ShowroomColors.SoftPink,
    surfaceContainerHighest = Color(0xFFF9D5E3),

    outline = ShowroomColors.Outline,
    outlineVariant = ShowroomColors.OutlineSoft,

    error = ShowroomColors.Error,
    onError = ShowroomColors.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    inverseSurface = Color(0xFF3B2530),
    inverseOnSurface = ShowroomColors.LavenderBlush,
    scrim = Color(0xFF000000),
)

private val LocalShowroomColors = staticCompositionLocalOf { DefaultExtendedColors }

/** Access to the brand colors Material's scheme has no slot for: `ShowroomTheme.colors.mutedOnTint`. */
object ShowroomTheme {
    val colors: ShowroomExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalShowroomColors.current
}

/**
 * The app's Material 3 design system. Light only, on purpose: the pink palette is the brand and
 * a showroom tablet is used in a lit room.
 */
@Composable
fun ShowroomPinkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShowroomColors provides DefaultExtendedColors) {
        MaterialTheme(
            colorScheme = ShowroomLightColors,
            typography = ShowroomTypography,
            shapes = ShowroomShapes,
            content = content,
        )
    }
}
