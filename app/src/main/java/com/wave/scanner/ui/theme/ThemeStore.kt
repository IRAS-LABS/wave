package com.wave.scanner.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which theme the user picked.
 *
 * SharedPreferences rather than DataStore on purpose: this is one short string, it is
 * needed synchronously in Activity.onCreate before the first frame, and DataStore's
 * asynchronous read would mean drawing one frame in the wrong theme and then flipping -
 * which is exactly the flash a theme picker must not have. The file is tiny and read once,
 * so the usual "SharedPreferences blocks" objection does not bite here.
 *
 * The in-memory StateFlow is what the UI observes, so picking a theme repaints
 * immediately rather than on the next launch.
 */
object ThemeStore {

    private const val PREFS = "wave_prefs"
    private const val KEY_THEME = "theme_id"

    private val _current = MutableStateFlow(WaveThemes.DEFAULT)
    val current: StateFlow<WavePalette> = _current.asStateFlow()

    /** Call once, early, before the first composition. */
    fun load(context: Context) {
        val id = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        _current.value = WaveThemes.byId(id)
    }

    fun select(context: Context, palette: WavePalette) {
        _current.value = palette
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, palette.id)
            .apply()
    }
}
