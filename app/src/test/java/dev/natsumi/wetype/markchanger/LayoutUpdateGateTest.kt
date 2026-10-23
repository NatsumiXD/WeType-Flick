package dev.natsumi.wetype.markchanger

import org.junit.Assert.*
import org.junit.Test

class LayoutUpdateGateTest {
    private class Button(var slot: KeyLayoutSwap.Slot)
    private fun slot(x: Int, width: Int) = KeyLayoutSwap.Slot(KeyLayoutSwap.Bounds(x, 0, x + width, 100),
        KeyLayoutSwap.Bounds(x, 0, x + width, 100), width, 100, 0f, 0f, x + width / 2f, 50f)

    @Test fun fullThenEnglishRowRebuildThenRepeatedCaseUpdatesKeepNumbersOnTheRight() {
        val gate = LayoutUpdateGate()
        val swap = KeyLayoutSwap({ (it as Button).slot }, { button, value -> (button as Button).slot = value })
        val keyboard = Any()
        var number = Button(slot(0, 145))
        var language = Button(slot(700, 103))
        val restore = { swap.apply(number, language, false); Unit }
        val apply = { swap.apply(number, language, true, layoutRebuilt = true); Unit }
        gate.run(keyboard, restore, {}, apply)
        assertEquals(700, number.slot.draw.left)
        // S3.e3 -> x2 -> z2 creates fresh bottom-row buttons, then u2 applies alignment.
        gate.run(keyboard, restore, {
            gate.run(keyboard, restore, {
                number = Button(slot(0, 150)); language = Button(slot(710, 110))
            }, apply)
            gate.run(keyboard, restore, { language.slot = slot(720, 110) }, apply)
        }, apply)
        assertEquals(720, number.slot.draw.left)
        assertEquals(0, language.slot.draw.left)
        repeat(10) { gate.run(keyboard, restore, {}, apply) }
        assertEquals(720, number.slot.draw.left)
        assertEquals(0, language.slot.draw.left)
        swap.apply(number, language, false)
        assertEquals(0, number.slot.draw.left)
        assertEquals(720, language.slot.draw.left)
    }

    @Test fun englishPartialRebuildAndNestedAlignmentSwapOnlyOnceAfterFinalBounds() {
        val gate = LayoutUpdateGate()
        val owner = Any()
        val events = ArrayList<String>()
        gate.run(owner, { events += "restore" }, {
            events += "bottom"
            gate.run(owner, { events += "BAD-restore" }, { events += "row" }, { events += "BAD-swap" })
            gate.run(owner, { events += "BAD-restore" }, { events += "alignment" }, { events += "BAD-swap" })
        }, { events += "swap" })
        assertEquals(listOf("restore", "bottom", "row", "alignment", "swap"), events)
        repeat(10) { gate.run(owner, { events += "restore" }, { events += "mode" }, { events += "swap" }) }
        assertEquals(11, events.count { it == "restore" })
        assertEquals(11, events.count { it == "swap" })
    }

    @Test fun thrownNativeUpdateDoesNotLeaveLaterUpdatesNested() {
        val gate = LayoutUpdateGate()
        val owner = Any()
        var after = 0
        assertTrue(runCatching { gate.run(owner, {}, { error("native failure") }, { after++ }) }.isFailure)
        var before = 0
        gate.run(owner, { before++ }, {}, { after++ })
        assertEquals(1, before)
        assertEquals(2, after)
    }
}
