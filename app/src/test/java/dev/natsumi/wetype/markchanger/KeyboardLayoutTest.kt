package dev.natsumi.wetype.markchanger

import java.io.File
import kotlin.metadata.jvm.fieldSignature
import org.junit.Assert.*
import org.junit.Test

class KeyboardLayoutTest {
    private data class Button(val id: String, var slot: KeyLayoutSwap.Slot, val action: String = id)
    private fun slot(left: Int, width: Int) = KeyLayoutSwap.Slot(
        KeyLayoutSwap.Bounds(left + 4, 500, left + width - 4, 620),
        KeyLayoutSwap.Bounds(left, 496, left + width, 624), width - 8, 120, 4f, 4f, left + width / 2f, 560f)
    private fun swap() = KeyLayoutSwap({ (it as Button).slot }, { button, value -> (button as Button).slot = value })

    @Test fun finalizedSlotsMoveWithoutChangingSpaceEmojiOrActions() {
        val number = Button("num", slot(0, 145))
        val language = Button("chSwitch", slot(700, 103))
        val emoji = Button("emoji", slot(145, 92))
        val space = Button("space", slot(330, 370))
        val enter = Button("enter", slot(803, 197))
        val originals = listOf(number, language, emoji, space, enter).map { it.slot }
        val swap = swap()
        assertTrue(swap.apply(number, language, true))
        assertEquals(originals[1], number.slot)
        assertEquals(originals[0], language.slot)
        assertEquals(originals.drop(2), listOf(emoji.slot, space.slot, enter.slot))
        assertEquals("num", number.action)
        assertEquals("chSwitch", language.action)
        assertFalse(swap.apply(number, language, true))
        assertTrue(swap.apply(number, language, false))
        assertEquals(originals, listOf(number, language, emoji, space, enter).map { it.slot })
        repeat(20) { swap.apply(number, language, true); swap.apply(number, language, false) }
        assertEquals(originals[0], number.slot)
        assertEquals(originals[1], language.slot)
    }

    @Test fun rebuildUsesFreshBoundsInsteadOfRestoringAnOldOrientation() {
        val number = Button("num", slot(0, 145))
        val language = Button("enSwitch", slot(700, 103))
        val swap = swap()
        swap.apply(number, language, true)
        number.slot = slot(50, 190)
        language.slot = slot(1400, 160)
        val rebuilt = listOf(number.slot, language.slot)
        assertTrue(swap.apply(number, language, true, layoutRebuilt = true))
        assertEquals(rebuilt.reversed(), listOf(number.slot, language.slot))
        assertTrue(swap.apply(number, language, false))
        assertEquals(rebuilt, listOf(number.slot, language.slot))
    }

    @Test fun incompleteGeometryRemainsUntouchedAndWriteFailureRollsBackBothButtons() {
        val number = Button("num", slot(0, 145))
        val language = Button("chSwitch", slot(700, 0))
        assertFalse(swap().apply(number, language, true))
        language.slot = slot(700, 103)
        val original = listOf(number.slot, language.slot)
        var first = true
        val swap = KeyLayoutSwap({ (it as Button).slot }, { button, value ->
            if (button === language && first) { first = false; error("write rejected") }
            (button as Button).slot = value
        })
        try { swap.apply(number, language, true); fail("Failure should propagate") } catch (_: IllegalStateException) { }
        assertEquals(original, listOf(number.slot, language.slot))
    }

    @Test fun t9GroupsUseIndependentKeysAndNeverConsumeOtherPunctuation() {
        assertEquals("2", KeyboardKeys.key(SymbolConfig.T9, "ABC", "S1_key_2"))
        assertEquals("7", KeyboardKeys.key(SymbolConfig.T9, "pqrs"))
        assertEquals("1", KeyboardKeys.key(SymbolConfig.T9, "@#", "S1_key_1"))
        assertNull(KeyboardKeys.key(SymbolConfig.T9, " ", "space"))
        assertNull(KeyboardKeys.key(SymbolConfig.CN, "ABC", "S1_key_2"))
        assertEquals("a", KeyboardKeys.key(SymbolConfig.EN, "A"))
    }

    @Test fun discoversActualLayoutBuilderAndCompleteSpatialSchema() {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).first { it.isFile }
        val entries = DexIndex.read(apk)
        val plan = HookPlan.build(entries, ::println)
        val keyboard = plan.getProperty("class.keyboard")
        assertNotNull(keyboard)
        assertEquals("a1|(Lcom/tencent/wetype/plugin/hld/keyboard/selfdraw/bean/KeyboardData;)V",
            plan.getProperty("$keyboard#layoutUpdate"))
        assertEquals(setOf("x2", "z2", "u2"), plan.stringPropertyNames().filter {
            it.startsWith("$keyboard#partialLayout.") }.map { plan.getProperty(it).substringBefore('|') }.toSet())
        val keyData = plan.getProperty("class.keyData")
        assertNotNull(keyData)
        assertNotNull(plan.getProperty("$keyData#field.id"))
        val layout = plan.getProperty("class.layoutData")
        assertNotNull(plan.getProperty("$layout#get.keys"))
        val button = plan.getProperty("class.button")
        for (name in KeyLayoutSwap.GEOMETRY_PROPERTIES)
            assertNotNull("Actual finalized geometry required for $name", plan.getProperty("$button#field.$name"))
        assertNotNull(plan.getProperty("$button#field.id"))
        // Log properties for evidence review, never load target code in this test.
        val entry = entries.single { it.name == keyboard }
        println(entry.kotlinClass()!!.properties.joinToString { "${it.name}=${it.fieldSignature}" })
        val renamed = entries.map { item -> if (item.name != keyboard) item else item.copy(
            methods = item.methods.map { method -> if (method.name == "a1") method.copy(name = "renamed_Z42") else method },
            declaredMethods = item.declaredMethods.map { member -> if (member.startsWith("a1|")) member.replaceFirst("a1|", "renamed_Z42|") else member }.toSet()) }
        assertTrue(HookPlan.build(renamed, ::println).getProperty("$keyboard#layoutUpdate").startsWith("renamed_Z42|"))
    }
}
