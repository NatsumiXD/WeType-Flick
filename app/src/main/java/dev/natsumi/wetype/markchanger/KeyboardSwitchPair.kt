package dev.natsumi.wetype.markchanger

/** Match capabilities rather than layout names: English, Wubi, stroke and handwriting also qualify. */
internal object KeyboardSwitchPair {
    fun find(buttons: Map<*, *>): Pair<Any, Any>? {
        val number = buttons["num"] ?: return null
        val language = buttons["chSwitch"] ?: buttons["enSwitch"] ?: buttons["specialToT26Switch"] ?: return null
        if (number === language) return null
        return number to language
    }
}
