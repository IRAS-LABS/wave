package com.wave.scanner.ui

import android.content.Context

/**
 * Whether the user has read and accepted the scope terms, and which version of them.
 *
 * Versioned rather than a bare boolean on purpose. If the terms are ever widened - a new
 * radio, a new capability, a change in what leaves the device - a stored "yes" against the
 * old text is not consent to the new text, and a plain boolean would silently treat it as
 * if it were. Bumping [VERSION] re-asks everybody, which is the only honest behaviour.
 *
 * Shares the same preferences file as the theme for the same reason: it is read once,
 * synchronously, before the first frame, and an asynchronous read would mean painting the
 * main UI for a moment before deciding the user was never supposed to see it.
 */
object ConsentStore {

    private const val PREFS = "wave_prefs"
    private const val KEY_VERSION = "consent_version"

    /** Bump when the scope terms in AUTHORIZATION.md or [ConsentScreen] change materially. */
    const val VERSION = 1

    fun accepted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VERSION, 0) >= VERSION

    fun accept(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, VERSION)
            .apply()
    }

    /** Used by the Data screen so the terms can be re-read and re-accepted deliberately. */
    fun revoke(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERSION)
            .apply()
    }
}
