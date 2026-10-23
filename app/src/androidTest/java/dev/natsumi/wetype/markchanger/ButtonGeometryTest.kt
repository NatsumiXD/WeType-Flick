package dev.natsumi.wetype.markchanger

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ButtonGeometryTest {
    private class Button(val id: String, left: Int, size: Int) {
        val drawRect = Rect(left + 4, 500, left + size - 4, 620)
        val hitRect = Rect(left, 496, left + size, 624)
        var width = size - 8
        var height = 120
        var leftPadding = 4f
        var rightPadding = 4f
        var centerX = left + size / 2f
        var centerY = 560f
    }

    @Test fun swappingUpdatesHitTestsCentersAndDrawingButPreservesRectReferences() {
        val geometry = ButtonGeometry(KeyLayoutSwap.GEOMETRY_PROPERTIES.associateWith {
            Button::class.java.getDeclaredField(it).apply { isAccessible = true }
        })
        val number = Button("num", 0, 145)
        val language = Button("chSwitch", 700, 103)
        val space = Button("space", 330, 370)
        val numberDraw = number.drawRect
        val numberHit = number.hitRect
        val originalSpace = geometry.read(space)
        val originalNumber = geometry.read(number)
        val originalLanguage = geometry.read(language)
        val swap = KeyLayoutSwap(geometry::read, geometry::write)
        swap.apply(number, language, true)
        assertSame(numberDraw, number.drawRect)
        assertSame(numberHit, number.hitRect)
        assertEquals(originalLanguage, geometry.read(number))
        assertEquals(originalNumber, geometry.read(language))
        assertTrue(language.hitRect.contains(70, 560))
        assertFalse(number.hitRect.contains(70, 560))
        assertTrue(number.hitRect.contains(750, 560))
        assertEquals(originalSpace, geometry.read(space))
        assertEquals("num", number.id)
        assertEquals("chSwitch", language.id)
        swap.apply(number, language, false)
        assertEquals(originalNumber, geometry.read(number))
        assertTrue(number.hitRect.contains(70, 560))
    }
}
