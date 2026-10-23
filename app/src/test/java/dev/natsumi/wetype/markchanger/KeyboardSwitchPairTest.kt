package dev.natsumi.wetype.markchanger

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class KeyboardSwitchPairTest {
    @Test fun pairsEnglishAndSpecialSwitchesWithoutAnyLetterGridRequirement() {
        for (languageId in listOf("chSwitch", "enSwitch", "specialToT26Switch")) {
            val number = Any()
            val language = Any()
            assertEquals(number to language, KeyboardSwitchPair.find(mapOf("num" to number, languageId to language)))
        }
        assertNull(KeyboardSwitchPair.find(mapOf("num" to Any(), "return" to Any())))
        assertNull(KeyboardSwitchPair.find(mapOf("enSwitch" to Any())))
    }

    @Test fun coversAllEighteenShippedLanguageLayoutsIncludingEnglishWubiStrokeAndHandwriting() {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).first { it.isFile }
        val layouts = listOf("S1ChineseT9Keyboard", "S2ChineseQwertyKeyboard", "S3EnglishQwertyKeyboard",
            "S8DoublePinT9Keyboard", "S9DoublePinQwertyKeyboard", "S17WuBiQwertyKeyboard", "S7StrokeT9Keyboard")
            .flatMap { listOf(it, it + "Land") } + listOf("S2ChineseQwertyKeyboardSplit",
                "S2ChineseQwertyKeyboardLargeSplit", "HandWriteKeyboard", "HandWriteFullscreenKeyboard")
        ZipFile(apk).use { zip ->
            for (layout in layouts) {
                val json = zip.getInputStream(zip.getEntry("assets/keyboard/output/$layout.json")).bufferedReader().readText()
                val ids = Regex("\"id\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
                val buttons = ids.associateWith { Any() }
                val pair = KeyboardSwitchPair.find(buttons)
                assertNotNull("Layout must swap: $layout", pair)
                assertSame(buttons["num"], pair!!.first)
                assertSame(buttons[if (layout.startsWith("S3")) "enSwitch" else if (layout.startsWith("Hand") || layout.startsWith("S7"))
                    "specialToT26Switch" else "chSwitch"], pair.second)
            }
        }
        assertEquals(18, layouts.size)
    }
}
