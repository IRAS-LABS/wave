package com.wave.scanner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The colour vocabulary the screens use, resolved against whichever theme is active.
 *
 * These were plain top-level `val`s holding literal colours until themes arrived. They are
 * now composable property getters that read [LocalPalette] - the same mechanism behind
 * MaterialTheme.colorScheme. Keeping the ORIGINAL NAMES is the whole point: every one of
 * the ~330 usages across the screens kept working untouched, so twenty themes cost no
 * churn in the UI code and there is no half-migrated state where some screens follow the
 * theme and others are stuck on the old green.
 *
 * The names are historical and a couple now read oddly - `Void` is the page background, so
 * on a light theme it is nearly white. Renaming them would have meant editing every call
 * site for no behavioural gain.
 *
 * These only resolve inside a composable. Anything outside composition (a notification, a
 * widget, a canvas built off-thread) must take a [WavePalette] as a parameter instead.
 */

val Void: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.background

val Panel: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.panel

val PanelHigh: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.panelHigh

val Hairline: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.hairline

/** The accent. Named for the original phosphor green; it is whatever the theme says now. */
val Phosphor: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.accent

val PhosphorDim: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.accentDim

val PhosphorDeep: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.accentDeep

val ThreatCritical: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.threatCritical

val ThreatHigh: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.threatHigh

val ThreatMedium: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.threatMedium

val ThreatLow: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.threatLow

val ThreatNone: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.threatNone

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary

val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary

val BandWifi: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bandWifi

val BandBle: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bandBle

val BandCell: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bandCell

val BandSubGhz: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bandSubGhz
