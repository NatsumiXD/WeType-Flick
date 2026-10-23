package dev.natsumi.wetype.markchanger

/** Kotlin default constructor ABI, resolved once; supports added/reordered optional fields. */
internal object SymbolDataFactory {
    fun create(data: Class<*>, parameters: List<String>): ((String) -> Any)? {
        val textIndex = parameters.indexOf("text")
        if (textIndex < 0) return null
        val masks = (parameters.size + 31) / 32
        val ctor = data.declaredConstructors.singleOrNull {
            val types = it.parameterTypes
            types.size == parameters.size + masks + 1 && types[textIndex] == String::class.java &&
                types.last().name == "kotlin.jvm.internal.DefaultConstructorMarker" &&
                (parameters.size until parameters.size + masks).all { index -> types[index] == Int::class.javaPrimitiveType }
        }?.apply { isAccessible = true } ?: return null
        val template = arrayOfNulls<Any>(ctor.parameterCount)
        for (index in parameters.indices) template[index] = when (ctor.parameterTypes[index]) {
            String::class.java -> ""; Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f; Double::class.javaPrimitiveType -> 0.0
            Byte::class.javaPrimitiveType -> 0.toByte(); Short::class.javaPrimitiveType -> 0.toShort()
            Char::class.javaPrimitiveType -> '\u0000'; else -> null
        }
        for (mask in 0 until masks) template[parameters.size + mask] =
            if (textIndex / 32 == mask) -1 xor (1 shl (textIndex % 32)) else -1
        return { text ->
            val args = template.clone()
            args[textIndex] = text
            ctor.newInstance(*args)
        }
    }
}
