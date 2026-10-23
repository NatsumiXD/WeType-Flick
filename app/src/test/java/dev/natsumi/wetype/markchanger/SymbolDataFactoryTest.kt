package dev.natsumi.wetype.markchanger

import org.junit.Assert.*
import org.junit.Test

class SymbolDataFactoryTest {
    class Original(val text: String, val caption: String = "default", val flags: Int = 7)
    class Updated(val enabled: Boolean = true, val text: String, val caption: String = "new", val size: Long = 99)

    @Test fun preservesDefaultsWhenOptionalParametersAreAddedOrReordered() {
        val first = SymbolDataFactory.create(Original::class.java, listOf("text", "caption", "flags"))!!("!") as Original
        assertEquals("!", first.text)
        assertEquals("default", first.caption)
        assertEquals(7, first.flags)
        val next = SymbolDataFactory.create(Updated::class.java, listOf("enabled", "text", "caption", "size"))!!("？") as Updated
        assertEquals("？", next.text)
        assertTrue(next.enabled)
        assertEquals("new", next.caption)
        assertEquals(99L, next.size)
    }

    @Test fun rejectsUnidentifiedTextInsteadOfGuessingAConstructor() {
        assertNull(SymbolDataFactory.create(Original::class.java, listOf("unknown", "caption", "flags")))
    }
}
