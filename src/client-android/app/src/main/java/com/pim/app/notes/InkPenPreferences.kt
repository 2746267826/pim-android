package com.pim.app.notes

import android.content.SharedPreferences
import kotlin.math.abs

internal data class InkPenPreference(
    val color: String = INK_COLOR_INK,
    val width: Float = INK_WIDTH_MEDIUM,
)

internal fun defaultInkStylusOnly(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp >= 600

internal class InkPenPreferenceStore(private val preferences: SharedPreferences) {
    fun load(): InkPenPreference {
        val color = runCatching { preferences.getString(KEY_COLOR, null) }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it in InkColorOptions }
            ?: INK_COLOR_INK
        val width = runCatching { preferences.getFloat(KEY_WIDTH, Float.NaN) }
            .getOrDefault(Float.NaN)
            .takeIf { saved -> InkWidthOptions.any { abs(it - saved) <= 1e-6f } }
            ?: INK_WIDTH_MEDIUM
        return InkPenPreference(color, width)
    }

    fun save(preference: InkPenPreference) {
        require(preference.color in InkColorOptions)
        require(InkWidthOptions.any { abs(it - preference.width) <= 1e-6f })
        preferences.edit()
            .putString(KEY_COLOR, preference.color)
            .putFloat(KEY_WIDTH, preference.width)
            .apply()
    }

    companion object {
        const val PREFERENCES = "native_note_ink_preferences"
        const val KEY_COLOR = "pen_color"
        const val KEY_WIDTH = "pen_width"
    }
}

internal class InkUndoStack(private val limit: Int = 100) {
    private val states = ArrayDeque<InkData>()

    val canUndo: Boolean get() = states.isNotEmpty()
    val size: Int get() = states.size

    fun record(previous: InkData) {
        if (states.lastOrNull() == previous) return
        states.addLast(previous)
        while (states.size > limit) states.removeFirst()
    }

    fun undo(): InkData? = states.removeLastOrNull()
}
