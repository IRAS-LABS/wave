package com.wave.scanner.ui

import android.content.Context
import com.wave.scanner.data.db.Threat
import com.wave.scanner.data.db.ThreatEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which alerts are allowed to interrupt you.
 *
 * The cutoff used to be a constant - HIGH and above, decided in the service and not
 * visible anywhere - and that is wrong in both directions at once. Somebody sweeping a
 * hotel room wants to be told about a MEDIUM they would otherwise scroll past; somebody
 * driving through a city wants the cell-anomaly noise to stop without losing the tracker
 * alert that is the reason the app is running. One hardcoded number cannot serve both, and
 * a person who mutes the whole app to escape the noise has lost the alert that mattered.
 *
 * Categories are coarse on purpose. There are only three things Wave alerts about, and a
 * settings screen with one switch per internal event type would be a list of jargon.
 *
 * Everything is read on a notification path that runs inside a coalescing flush, so the
 * values are cached in memory and only the writes touch disk.
 */
object AlertPrefs {

    private const val PREFS = "wave_prefs"
    private const val KEY_MIN = "alert_min_severity"
    private const val KEY_FOLLOWING = "alert_following"
    private const val KEY_IDENTIFIED = "alert_identified"
    private const val KEY_CELL = "alert_cell"

    /**
     * The three things Wave raises an alert about, mapped from the event `type` strings the
     * repository writes.
     */
    enum class Category(val key: String, val label: String, val detail: String) {
        FOLLOWING(
            KEY_FOLLOWING,
            "Something is following you",
            "A device that has stayed with you across enough separate positions to rule " +
                "out coincidence. This is the alert the app exists for."
        ),
        IDENTIFIED(
            KEY_IDENTIFIED,
            "Known equipment in range",
            "A device matched a signature - a tracker, a body camera, an ALPR reader, " +
                "surveillance hardware."
        ),
        CELL(
            KEY_CELL,
            "Cell network anomalies",
            "Possible cell-site simulators: 2G downgrades, unknown towers, impossible " +
                "timing advance. Noisy in dense cities and in poor coverage."
        );

        companion object {
            /** Anything unrecognised is treated as IDENTIFIED so a new type is never silent. */
            fun of(type: String): Category = when {
                type == "following" -> FOLLOWING
                type.startsWith("cell_") -> CELL
                else -> IDENTIFIED
            }
        }
    }

    data class Settings(
        /** Alerts below this never post a notification. They are still recorded and listed. */
        val minSeverity: Threat = Threat.HIGH,
        val enabled: Set<Category> = Category.entries.toSet()
    ) {
        fun allows(event: ThreatEventEntity): Boolean =
            event.severity >= minSeverity && Category.of(event.type) in enabled
    }

    private val _current = MutableStateFlow(Settings())
    val current: StateFlow<Settings> = _current.asStateFlow()

    /** Call once, early. [ScanService] reads through [current] and never blocks on disk. */
    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val min = p.getString(KEY_MIN, null)
            ?.let { name -> Threat.entries.firstOrNull { it.name == name } }
            ?: Threat.HIGH
        _current.value = Settings(
            minSeverity = min,
            enabled = Category.entries.filterTo(mutableSetOf()) { p.getBoolean(it.key, true) }
        )
    }

    fun setMinSeverity(context: Context, threat: Threat) {
        _current.value = _current.value.copy(minSeverity = threat)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MIN, threat.name).apply()
    }

    fun setEnabled(context: Context, category: Category, on: Boolean) {
        val next = _current.value.enabled.toMutableSet()
        if (on) next += category else next -= category
        _current.value = _current.value.copy(enabled = next)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(category.key, on).apply()
    }
}
