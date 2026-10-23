package dev.natsumi.wetype.markchanger

internal object KeyboardKeys {
    private val T9_ID = Regex("S[0-9]+_key_[1-9]")
    val T9_LABELS = linkedMapOf("1" to "@#", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
        "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ")

    fun key(type: String, text: String?, id: String? = null): String? {
        if (type == SymbolConfig.T9) {
            // IDs disambiguate T9's @# key from punctuation outside the letter grid.
            if (id?.matches(T9_ID) == true) return id.takeLast(1)
            return T9_LABELS.entries.firstOrNull { it.value.equals(text, ignoreCase = true) }?.key
        }
        return text?.lowercase(java.util.Locale.ROOT)?.takeIf { it.length == 1 && it[0] in 'a'..'z' }
    }
}
