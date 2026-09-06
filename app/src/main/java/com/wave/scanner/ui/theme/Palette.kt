package com.wave.scanner.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app can draw, as one swappable set.
 *
 * The screens do not name literal colours anywhere. They name roles - "hairline",
 * "threatHigh", "bandBle" - and a palette decides what those roles look like. That is what
 * makes twenty themes possible without twenty copies of the UI, and it is why adding a
 * theme below is a data change rather than a code change.
 *
 * The threat ladder is part of the palette rather than fixed, because a ladder tuned for a
 * near-black background is genuinely unreadable on a white one: #FF2D45 on white is a
 * vibrating mess, and #FFC400 on white is close to invisible. Each light theme therefore
 * carries a darkened ladder. What must NOT vary is the ORDER - critical reads hotter than
 * high, high hotter than medium - because that ordering is the thing the user learns.
 */
data class WavePalette(
    val id: String,
    val name: String,
    val isDark: Boolean,

    /** Page background. Pure black on the OLED-minded dark themes. */
    val background: Color,
    /** Cards and rows sitting on the background. */
    val panel: Color,
    /** A raised panel: input fields, selected rows, nested surfaces. */
    val panelHigh: Color,
    /** Dividers and card borders. Should be barely there. */
    val hairline: Color,

    /** The single "this is live / this is selected" colour. */
    val accent: Color,
    val accentDim: Color,
    val accentDeep: Color,
    /** Text drawn ON the accent - must survive against it. */
    val onAccent: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val threatCritical: Color,
    val threatHigh: Color,
    val threatMedium: Color,
    val threatLow: Color,
    val threatNone: Color,

    val bandWifi: Color,
    val bandBle: Color,
    val bandCell: Color,
    val bandSubGhz: Color
)

// The ladders and band colours below are the defaults each theme starts from. A theme
// overrides only what it actually needs to change, so a new theme is usually four lines.

private val DarkLadder = listOf(
    Color(0xFFFF2D45), Color(0xFFFF8A00), Color(0xFFFFC400), Color(0xFF4FC3F7), Color(0xFF5A6675)
)
private val LightLadder = listOf(
    Color(0xFFC62828), Color(0xFFE65100), Color(0xFF9A6B00), Color(0xFF0277BD), Color(0xFF90A4AE)
)
private val DarkBands = listOf(
    Color(0xFF4FC3F7), Color(0xFF9B7BFF), Color(0xFFFFD23F), Color(0xFFFF8A3D)
)
private val LightBands = listOf(
    Color(0xFF0277BD), Color(0xFF6A3FCF), Color(0xFFA67C00), Color(0xFFC75B12)
)

private fun dark(
    id: String,
    name: String,
    background: Color,
    panel: Color,
    panelHigh: Color,
    hairline: Color,
    accent: Color,
    accentDim: Color,
    accentDeep: Color,
    onAccent: Color = background,
    textPrimary: Color = Color(0xFFE6EDF3),
    textSecondary: Color = Color(0xFF8B97A6),
    textTertiary: Color = Color(0xFF5A6675),
    ladder: List<Color> = DarkLadder,
    bands: List<Color> = DarkBands
) = WavePalette(
    id, name, true, background, panel, panelHigh, hairline,
    accent, accentDim, accentDeep, onAccent,
    textPrimary, textSecondary, textTertiary,
    ladder[0], ladder[1], ladder[2], ladder[3], ladder[4],
    bands[0], bands[1], bands[2], bands[3]
)

private fun light(
    id: String,
    name: String,
    background: Color,
    panel: Color,
    panelHigh: Color,
    hairline: Color,
    accent: Color,
    accentDim: Color,
    accentDeep: Color,
    onAccent: Color = Color.White,
    textPrimary: Color = Color(0xFF101418),
    textSecondary: Color = Color(0xFF4A5560),
    textTertiary: Color = Color(0xFF7C8894),
    ladder: List<Color> = LightLadder,
    bands: List<Color> = LightBands
) = WavePalette(
    id, name, false, background, panel, panelHigh, hairline,
    accent, accentDim, accentDeep, onAccent,
    textPrimary, textSecondary, textTertiary,
    ladder[0], ladder[1], ladder[2], ladder[3], ladder[4],
    bands[0], bands[1], bands[2], bands[3]
)

object WaveThemes {

    // ------------------------------------------------------------------ dark (10)

    val DARK: List<WavePalette> = listOf(

        /** The original. Kept so the first build's look is still reachable. */
        dark(
            "phosphor", "Phosphor",
            background = Color(0xFF05070A), panel = Color(0xFF0B0F14),
            panelHigh = Color(0xFF121821), hairline = Color(0xFF1E2733),
            accent = Color(0xFF00E5A0), accentDim = Color(0xFF00B383),
            accentDeep = Color(0xFF1E6B57)
        ),

        /**
         * No accent colour at all: "live" is white, the band chips differ only by label.
         * The only colour that ever appears on screen is a threat, so colour means danger
         * and nothing else.
         */
        dark(
            "mono", "Monochrome",
            background = Color(0xFF000000), panel = Color(0xFF0A0A0A),
            panelHigh = Color(0xFF141414), hairline = Color(0xFF1F1F1F),
            accent = Color(0xFFFFFFFF), accentDim = Color(0xFFB0B0B0),
            accentDeep = Color(0xFF3A3A3A), onAccent = Color(0xFF000000),
            textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0xFF8A8A8A),
            textTertiary = Color(0xFF4A4A4A),
            bands = listOf(
                Color(0xFFDADADA), Color(0xFFA8A8A8), Color(0xFF7C7C7C), Color(0xFF5A5A5A)
            )
        ),

        dark(
            "ice", "Ice",
            background = Color(0xFF04060A), panel = Color(0xFF0A0E15),
            panelHigh = Color(0xFF131A24), hairline = Color(0xFF18202B),
            accent = Color(0xFF8AB4FF), accentDim = Color(0xFF4A7DD6),
            accentDeep = Color(0xFF1E3A5F),
            textPrimary = Color(0xFFE8EDF5), textSecondary = Color(0xFF7D8899)
        ),

        dark(
            "amber", "Amber",
            background = Color(0xFF060505), panel = Color(0xFF0F0D0B),
            panelHigh = Color(0xFF1A1611), hairline = Color(0xFF241E17),
            accent = Color(0xFFFFB020), accentDim = Color(0xFFC77F0A),
            accentDeep = Color(0xFF5C3D08),
            textPrimary = Color(0xFFF5EDE2), textSecondary = Color(0xFF9A8F80),
            textTertiary = Color(0xFF6B6157),
            // Cool ladder so severity never blends into the warm accent.
            ladder = listOf(
                Color(0xFFFF3B58), Color(0xFFFF6B9D), Color(0xFFB48AFF),
                Color(0xFF4FC3F7), Color(0xFF5A6675)
            )
        ),

        dark(
            "ember", "Ember",
            background = Color(0xFF0A0505), panel = Color(0xFF140A0A),
            panelHigh = Color(0xFF1E1010), hairline = Color(0xFF2B1818),
            accent = Color(0xFFFF6B3D), accentDim = Color(0xFFC44A24),
            accentDeep = Color(0xFF5E2413),
            textPrimary = Color(0xFFF5E8E2), textSecondary = Color(0xFF9E8880),
            ladder = listOf(
                Color(0xFFFF2D45), Color(0xFFFFB020), Color(0xFFFFE08A),
                Color(0xFF7DD3FC), Color(0xFF6B5A55)
            )
        ),

        dark(
            "ultraviolet", "Ultraviolet",
            background = Color(0xFF07050D), panel = Color(0xFF0E0A18),
            panelHigh = Color(0xFF171024), hairline = Color(0xFF241934),
            accent = Color(0xFFB388FF), accentDim = Color(0xFF7E57C2),
            accentDeep = Color(0xFF3D2A5C),
            textPrimary = Color(0xFFEDE7F6), textSecondary = Color(0xFF9086A8),
            bands = listOf(
                Color(0xFF4FC3F7), Color(0xFFE1BEE7), Color(0xFFFFD23F), Color(0xFFFF8A3D)
            )
        ),

        dark(
            "abyss", "Abyss",
            background = Color(0xFF00080A), panel = Color(0xFF021317),
            panelHigh = Color(0xFF042026), hairline = Color(0xFF0A2E36),
            accent = Color(0xFF26D9D9), accentDim = Color(0xFF149C9C),
            accentDeep = Color(0xFF0A4A4A),
            textPrimary = Color(0xFFDFF5F5), textSecondary = Color(0xFF7A9A9C)
        ),

        dark(
            "dusk", "Dusk",
            background = Color(0xFF0B0906), panel = Color(0xFF14110C),
            panelHigh = Color(0xFF1F1A12), hairline = Color(0xFF2C251A),
            accent = Color(0xFFD9C08A), accentDim = Color(0xFFA68A55),
            accentDeep = Color(0xFF4F412A),
            textPrimary = Color(0xFFF0E9DC), textSecondary = Color(0xFF9C9280),
            textTertiary = Color(0xFF6B6355)
        ),

        dark(
            "neon", "Neon",
            background = Color(0xFF08040A), panel = Color(0xFF120818),
            panelHigh = Color(0xFF1C0F24), hairline = Color(0xFF2B1836),
            accent = Color(0xFFFF4FD8), accentDim = Color(0xFFC22FA4),
            accentDeep = Color(0xFF5C144C),
            textPrimary = Color(0xFFFBE9F7), textSecondary = Color(0xFFA285A0),
            bands = listOf(
                Color(0xFF4FE8FF), Color(0xFFB388FF), Color(0xFFFFE95C), Color(0xFFFF8A3D)
            )
        ),

        dark(
            "graphite", "Graphite",
            background = Color(0xFF0D0F11), panel = Color(0xFF15181C),
            panelHigh = Color(0xFF1E2328), hairline = Color(0xFF2A3037),
            accent = Color(0xFFB8C4D0), accentDim = Color(0xFF7D8894),
            accentDeep = Color(0xFF3A434D), onAccent = Color(0xFF0D0F11),
            textPrimary = Color(0xFFE8ECF0), textSecondary = Color(0xFF98A2AD)
        )
    )

    // ----------------------------------------------------------------- light (10)

    val LIGHT: List<WavePalette> = listOf(

        light(
            "paper", "Paper",
            background = Color(0xFFFAF9F7), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFF0EEE9), hairline = Color(0xFFE0DDD6),
            accent = Color(0xFF1A1A1A), accentDim = Color(0xFF5A5A5A),
            accentDeep = Color(0xFFD8D5CE)
        ),

        light(
            "daylight", "Daylight",
            background = Color(0xFFF5F7FA), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFEAEEF4), hairline = Color(0xFFD6DEE8),
            accent = Color(0xFF1565C0), accentDim = Color(0xFF5E92D0),
            accentDeep = Color(0xFFD3E3F7)
        ),

        light(
            "linen", "Linen",
            background = Color(0xFFFBF6EC), panel = Color(0xFFFFFCF6),
            panelHigh = Color(0xFFF3EADA), hairline = Color(0xFFE3D7C2),
            accent = Color(0xFF9A6B1F), accentDim = Color(0xFFC49A5A),
            accentDeep = Color(0xFFEEDFC5),
            textPrimary = Color(0xFF241E14), textSecondary = Color(0xFF5C5142)
        ),

        light(
            "mint", "Mint",
            background = Color(0xFFF3FAF6), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFE6F4EC), hairline = Color(0xFFCCE5D8),
            accent = Color(0xFF00795C), accentDim = Color(0xFF4FA98F),
            accentDeep = Color(0xFFCFEBE0)
        ),

        light(
            "sky", "Sky",
            background = Color(0xFFF2F8FD), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFE3F1FB), hairline = Color(0xFFC7E0F2),
            accent = Color(0xFF0277BD), accentDim = Color(0xFF58A5D8),
            accentDeep = Color(0xFFCDE7F7)
        ),

        light(
            "coral", "Coral",
            background = Color(0xFFFFF6F3), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFFDE9E2), hairline = Color(0xFFF3D3C7),
            accent = Color(0xFFC0432A), accentDim = Color(0xFFDD8064),
            accentDeep = Color(0xFFF8DDD3),
            // The default ladder's critical is too close to this accent to read as an
            // alarm, so it goes deeper still.
            ladder = listOf(
                Color(0xFF8E0000), Color(0xFFB8500A), Color(0xFF8A6100),
                Color(0xFF0277BD), Color(0xFF9E8880)
            )
        ),

        light(
            "lavender", "Lavender",
            background = Color(0xFFF8F5FD), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFEEE7F9), hairline = Color(0xFFDCD0EE),
            accent = Color(0xFF5E35B1), accentDim = Color(0xFF9575CD),
            accentDeep = Color(0xFFE3D8F5)
        ),

        light(
            "steel", "Steel",
            background = Color(0xFFEEF1F4), panel = Color(0xFFFAFBFC),
            panelHigh = Color(0xFFE2E7EC), hairline = Color(0xFFCBD3DB),
            accent = Color(0xFF37474F), accentDim = Color(0xFF6E8391),
            accentDeep = Color(0xFFD5DDE3)
        ),

        light(
            "sun", "Sun",
            background = Color(0xFFFFFBF0), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFFFF3D6), hairline = Color(0xFFF0DFB4),
            accent = Color(0xFFB77500), accentDim = Color(0xFFDCA53C),
            accentDeep = Color(0xFFFAE7BE)
        ),

        /** Maximum contrast: for reading the screen in direct sun through a windshield. */
        light(
            "ink", "Ink",
            background = Color(0xFFFFFFFF), panel = Color(0xFFFFFFFF),
            panelHigh = Color(0xFFF2F2F2), hairline = Color(0xFF9A9A9A),
            accent = Color(0xFF000000), accentDim = Color(0xFF444444),
            accentDeep = Color(0xFFDDDDDD),
            textPrimary = Color(0xFF000000), textSecondary = Color(0xFF333333),
            textTertiary = Color(0xFF666666),
            ladder = listOf(
                Color(0xFFB00020), Color(0xFF9A4500), Color(0xFF6B4E00),
                Color(0xFF01579B), Color(0xFF555555)
            ),
            bands = listOf(
                Color(0xFF01579B), Color(0xFF4A148C), Color(0xFF6B4E00), Color(0xFF9A4500)
            )
        )
    )

    val ALL: List<WavePalette> = DARK + LIGHT

    val DEFAULT: WavePalette = DARK[1] // Monochrome

    fun byId(id: String?): WavePalette = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * staticCompositionLocalOf rather than compositionLocalOf: the palette changes only when
 * the user picks a new theme, and when it does every screen should recompose anyway. The
 * static variant skips tracking reads individually, which is the cheaper trade here.
 */
val LocalPalette = staticCompositionLocalOf { WaveThemes.DEFAULT }
