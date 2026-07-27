package com.xtv.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

/**
 * App theme. TV apps are dark-first (rendered on large displays in dim rooms), so the scheme is
 * dark with no light counterpart.
 *
 * Every slot is given a value on purpose. `androidx.tv.material3.Card` dresses itself from
 * `surfaceVariant` and `border`; `Button` from `surfaceVariant`, `onSurface`, `onSurfaceVariant`
 * and `inverseOnSurface`. Leaving the scheme at its defaults — as this app did — meant the
 * components painted themselves from one palette while every background and label used literal hex
 * from another, and the two were never reconciled.
 *
 * `border` is the *focused* border: `CardDefaults.border()` defaults the resting state to
 * `Border.None` and only reaches for the scheme when focus arrives. Pointing it at the accent gives
 * the whole app one focus signal, produced by the theme rather than restated at each call site.
 *
 * Typography stays at the tv-material defaults, which are already scaled for a three-metre viewing
 * distance; overriding them would be a regression dressed as a decision.
 *
 * No `@OptIn` needed: androidx.tv:tv-material is stable as of 1.1.0. Only `Carousel` and the
 * `*Chip` family still carry `ExperimentalTvMaterial3Api`.
 */
@Composable
fun XtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = XtvPalette.Accent,
            onPrimary = XtvPalette.OnAccent,
            primaryContainer = XtvPalette.Accent,
            onPrimaryContainer = XtvPalette.OnAccent,
            inversePrimary = XtvPalette.Accent,

            // Warning is the app's second voice: budget, money, "slow down".
            secondary = XtvPalette.Warning,
            onSecondary = XtvPalette.OnWarning,
            secondaryContainer = XtvPalette.Warning,
            onSecondaryContainer = XtvPalette.OnWarning,

            // Nothing in XTV is a third voice. Kept neutral so an accidental use is invisible
            // rather than a surprise colour.
            tertiary = XtvPalette.SurfaceVariant,
            onTertiary = XtvText.Primary,
            tertiaryContainer = XtvPalette.SurfaceVariant,
            onTertiaryContainer = XtvText.Primary,

            background = XtvPalette.Background,
            onBackground = XtvText.Primary,
            surface = XtvPalette.Surface,
            onSurface = XtvText.Primary,

            // What Card paints itself with, so it must read as raised above the background.
            surfaceVariant = XtvPalette.SurfaceVariant,
            onSurfaceVariant = XtvText.Secondary,
            surfaceTint = XtvPalette.SurfaceVariant,

            inverseSurface = XtvText.Primary,
            inverseOnSurface = XtvPalette.Background,

            // Reserved for irreversible actions, never for "something went wrong" — most failures
            // in this app are recoverable and are told in words, not in red.
            error = XtvPalette.Danger,
            onError = XtvPalette.OnDanger,
            errorContainer = XtvPalette.Danger,
            onErrorContainer = XtvPalette.OnDanger,

            border = XtvPalette.Accent,
            borderVariant = XtvPalette.Border,
            scrim = XtvPalette.Scrim,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
