package dev.natsumi.wetype.markchanger

import java.lang.reflect.Field

/** Copy an original configuration with its native resources, changing only entry identity/state. */
internal object SettingEntryFactory {
    fun create(type: Class<*>, parameters: List<String>, fields: Map<String, Field>): ((Any, Int) -> Any)? {
        if (!parameters.containsAll(listOf("id", "iconId", "titleId", "descId", "redPoint", "isVisible")) ||
            parameters.any { it !in fields }) return null
        val constructor = type.declaredConstructors.singleOrNull { ctor ->
            ctor.parameterCount == parameters.size && parameters.indices.all { index -> ctor.parameterTypes[index] == fields.getValue(parameters[index]).type }
        }?.apply { isAccessible = true } ?: return null
        return { template, id ->
            val args = parameters.map { name -> when (name) {
                "id" -> id; "redPoint" -> false; "isVisible" -> true; else -> fields.getValue(name).get(template)
            } }.toTypedArray()
            constructor.newInstance(*args)
        }
    }
}
