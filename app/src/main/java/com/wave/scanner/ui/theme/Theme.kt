package com.wave.scanner.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun schemeFor(p: WavePalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = p.accent,
        secondary = p.bandBle,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.panel,
        onSurface = p.textPrimary,
        surfaceVariant = p.panelHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.hairline,
        error = p.threatCritical,
        onError = p.background
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = p.accent,
        secondary = p.bandBle,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.panel,
        onSurface = p.textPrimary,
        surfaceVariant = p.panelHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.hairline,
        error = p.threatCritical,
        onError = p.panel
    )
}

/**
 * @param palette which of the twenty themes to draw in. Defaults to the stored choice's
 *   fallback so previews and tests need not supply one.
 */
@Composable
fun WaveTheme(
    palette: WavePalette = WaveThemes.DEFAULT,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status-bar icons must invert for the light themes or they are white on
            // white and the clock disappears. This is the one place the theme reaches
            // outside Compose, and forgetting it is what makes a light theme look broken.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !palette.isDark
            controller.isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    val scheme = remember(palette.id) { schemeFor(palette) }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = WaveTypography,
            content = content
        )
    }
}
