package dev.natsumi.wetype.markchanger

import android.graphics.Rect
import java.lang.reflect.Field

/** Fields are resolved once from actual DEX declarations, never by obfuscated names. */
internal class ButtonGeometry(private val fields: Map<String, Field>) {
    init { require(KeyLayoutSwap.GEOMETRY_PROPERTIES.all { it in fields }) }
    private fun rect(button: Any, name: String) = fields.getValue(name).get(button) as Rect
    private fun snapshot(rect: Rect) = KeyLayoutSwap.Bounds(rect.left, rect.top, rect.right, rect.bottom)
    private fun number(button: Any, name: String) = fields.getValue(name).get(button) as Number
    fun read(button: Any) = KeyLayoutSwap.Slot(snapshot(rect(button, "drawRect")), snapshot(rect(button, "hitRect")),
        number(button, "width").toInt(), number(button, "height").toInt(), number(button, "leftPadding").toFloat(),
        number(button, "rightPadding").toFloat(), number(button, "centerX").toFloat(), number(button, "centerY").toFloat())

    fun write(button: Any, slot: KeyLayoutSwap.Slot) {
        fun set(name: String, value: Any) { fields.getValue(name).set(button, value) }
        // Keep Rect identity: other original components can already hold references to these Rects.
        slot.draw.let { rect(button, "drawRect").set(it.left, it.top, it.right, it.bottom) }
        slot.hit.let { rect(button, "hitRect").set(it.left, it.top, it.right, it.bottom) }
        set("width", slot.width); set("height", slot.height)
        set("leftPadding", slot.leftPadding); set("rightPadding", slot.rightPadding)
        set("centerX", slot.centerX); set("centerY", slot.centerY)
    }
}
