package dev.natsumi.wetype.markchanger

import java.lang.ref.WeakReference

/** Exchanges finalized slots, after WeType has applied formulas and ID-specific hit corrections. */
internal class KeyLayoutSwap(private val read: (Any) -> Slot, private val write: (Any, Slot) -> Unit) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val valid: Boolean get() = right > left && bottom > top
    }
    data class Slot(val draw: Bounds, val hit: Bounds, val width: Int, val height: Int,
                    val leftPadding: Float, val rightPadding: Float, val centerX: Float, val centerY: Float) {
        val valid: Boolean get() = draw.valid && hit.valid && width > 0 && height > 0
    }
    private data class State(val number: WeakReference<Any>, val language: WeakReference<Any>,
                             val numberSlot: Slot, val languageSlot: Slot, var enabled: Boolean)
    private val states = ArrayList<State>()

    @Synchronized
    fun apply(number: Any, language: Any, enabled: Boolean, layoutRebuilt: Boolean = false): Boolean {
        states.removeAll { it.number.get() == null || it.language.get() == null ||
            (layoutRebuilt && it.number.get() === number && it.language.get() === language) }
        val state = states.firstOrNull { it.number.get() === number && it.language.get() === language }
        if ((state?.enabled ?: false) == enabled) return false
        val beforeNumber = read(number)
        val beforeLanguage = read(language)
        if (!beforeNumber.valid || !beforeLanguage.valid) return false
        val originalNumber = state?.numberSlot ?: beforeNumber
        val originalLanguage = state?.languageSlot ?: beforeLanguage
        try {
            write(number, if (enabled) originalLanguage else originalNumber)
            write(language, if (enabled) originalNumber else originalLanguage)
        } catch (failure: Throwable) {
            write(number, beforeNumber)
            write(language, beforeLanguage)
            throw failure
        }
        if (state == null) states += State(WeakReference(number), WeakReference(language), originalNumber, originalLanguage, enabled)
        else state.enabled = enabled
        return true
    }

    companion object {
        val GEOMETRY_PROPERTIES = listOf("drawRect", "hitRect", "width", "height", "leftPadding", "rightPadding", "centerX", "centerY")
    }
}
