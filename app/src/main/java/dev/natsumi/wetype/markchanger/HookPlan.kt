package dev.natsumi.wetype.markchanger

import java.util.Properties
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature

/** Metadata describes potential members; only actual DEX declarations can become hook targets. */
internal object HookPlan {
    fun build(entries: List<DexIndex.Entry>, report: (String) -> Unit): Properties {
        val plan = Properties()
        val classes = entries.asSequence().filter { DexIndex.relevant(it.source, it.metadata) }
            .mapNotNull { entry -> entry.kotlinClass()?.let { Triple(entry, it, it.properties.map { p -> p.name }.toSet()) } }.toList()
        fun select(role: String, matches: List<Triple<DexIndex.Entry, kotlin.metadata.KmClass, Set<String>>>) {
            val match = matches.singleOrNull()
            if (match == null) report("$role: ${matches.size} candidates; feature skipped")
            else plan["class.$role"] = match.first.name
        }
        select("button", classes.filter { "mainText" in it.third && "floatText" in it.third && "parent" in it.third })
        select("symbols", classes.filter { "highLightSymbol" in it.third })
        select("data", classes.filter { "text" in it.third && it.first.source == "SymbolFloatView.kt" &&
            it.second.constructors.any { ctor -> ctor.valueParameters.any { p -> p.name == "text" } } })
        select("candidate", classes.filter { "logoIv" in it.third && "logoRedPoint" in it.third })
        select("keyboard", classes.filter { "imeButtonMap" in it.third && "originData" in it.third })
        select("keyData", classes.filter { "alignLeft" in it.third && "parent" !in it.third &&
            it.second.properties.any { p -> p.name == "mainText" && p.fieldSignature?.descriptor == "Ljava/lang/String;" } })
        val keyDescriptor = plan.getProperty("class.keyData")?.let { "L${it.replace('.', '/')};" }
        select("layoutData", classes.filter { "keys" in it.third && it.second.properties.any { p ->
            p.name == "keyDefaultConfig" && keyDescriptor != null && p.fieldSignature?.descriptor == keyDescriptor
        } })
        select("settingConfig", classes.filter { it.third.containsAll(setOf("id", "iconId", "titleId", "descId", "redPoint", "isVisible")) })
        val configDescriptor = plan.getProperty("class.settingConfig")?.let { "L${it.replace('.', '/')};" }
        select("settingBaseModel", classes.filter { "itemView" in it.third && it.second.properties.any { p ->
            p.name == "config" && configDescriptor != null && p.fieldSignature?.descriptor == configDescriptor
        } })
        select("settingModel", classes.filter { it.third.containsAll(setOf("title", "readPoint", "recorderIcon")) })
        val providers = entries.filter { it.metadata?.data2?.contains("SETTINGS_CONFIG_MAP") == true }
        providers.singleOrNull()?.let { provider ->
            plan["class.settingProvider"] = provider.name
            provider.declaredMethods.singleOrNull { it.endsWith("|()Ljava/util/List;") }?.let {
                plan[provider.name + "#settingsList"] = it
            }
        } ?: report("Native settings provider: ${providers.size} candidates; entry skipped")
        val selected = listOf("button", "symbols", "data", "candidate", "keyboard", "layoutData", "keyData",
            "settingConfig", "settingBaseModel", "settingModel").mapNotNull { role ->
            plan.getProperty("class.$role")?.let { it to role }
        }.toMap()
        for ((entry, km, _) in classes) {
            val role = selected[entry.name]
            val allowed = when (role) {
                "button" -> setOf("mainText", "floatText", "parent", "id") + KeyLayoutSwap.GEOMETRY_PROPERTIES
                "symbols" -> setOf("highLightSymbol")
                "data" -> setOf("text")
                "candidate" -> setOf("logoIv", "logoRedPoint")
                "keyboard" -> setOf("imeButtonMap", "originData", "keyboardType")
                "layoutData" -> setOf("keys")
                "keyData" -> setOf("id")
                "settingConfig" -> km.properties.map { it.name }.toSet()
                "settingBaseModel" -> setOf("itemView", "config")
                "settingModel" -> setOf("title")
                else -> setOf("keyboardType")
            }
            val prefix = entry.name + "#"
            for (property in km.properties.filter { it.name in allowed }) {
                property.fieldSignature?.let {
                    val value = it.name + "|" + it.descriptor
                    if (value in entry.declaredFields) plan[prefix + "field." + property.name] = value
                }
                property.getterSignature?.let {
                    val value = it.name + "|" + it.descriptor
                    if (value in entry.declaredMethods) plan[prefix + "get." + property.name] = value
                }
                property.setterSignature?.let {
                    val value = it.name + "|" + it.descriptor
                    if (value in entry.declaredMethods) plan[prefix + "set." + property.name] = value
                }
            }
            if (role == "data") km.constructors.singleOrNull { ctor -> ctor.valueParameters.any { it.name == "text" } }
                ?.let { plan["data.parameters"] = it.valueParameters.joinToString(",") { p -> p.name } }
            if (role == "settingConfig") km.constructors.singleOrNull { ctor -> ctor.valueParameters.any { it.name == "id" } }
                ?.let { plan["settings.parameters"] = it.valueParameters.joinToString(",") { p -> p.name } }
            if (role == "keyboard") {
                val layout = plan.getProperty("class.layoutData")
                val updates = entry.methods.filter { method ->
                    method.descriptor == "(L${layout?.replace('.', '/')};)V" &&
                        method.invokes.any { it.owner == layout && it.descriptor == "()Ljava/util/List;" } &&
                        method.invokes.any { it.owner in setOf("java.util.Map", "java.util.HashMap") && it.name == "put" }
                }
                if (updates.size == 1) updates.single().let { plan[prefix + "layoutUpdate"] = it.name + "|" + it.descriptor }
                else report("Keyboard layout: ${updates.size} semantic matches; swap skipped")
                val rowUpdates = entry.methods.filter { method ->
                    method.descriptor.startsWith("(IL") && method.descriptor.endsWith(";)V") &&
                        method.descriptor.count { it == ';' } == 1 &&
                        method.invokes.any { it.owner == layout && it.descriptor == "()Ljava/util/List;" } &&
                        method.invokes.any { it.owner == "java.util.Map" && it.name == "put" }
                }
                val row = rowUpdates.singleOrNull()
                val bottom = row?.let { line -> entry.methods.singleOrNull { method ->
                    method.descriptor.endsWith("Ljava/util/Map;)V") &&
                        method.invokes.any { it.owner == entry.name && it.name == line.name && it.descriptor == line.descriptor }
                } }
                val align = bottom?.invokes?.singleOrNull { it.owner == entry.name && it.descriptor == "(Ljava/util/Map;)V" }
                    ?.let { ref -> entry.methods.singleOrNull { it.name == ref.name && it.descriptor == ref.descriptor } }
                for (method in listOfNotNull(row, bottom, align))
                    plan[prefix + "partialLayout." + method.name] = method.name + "|" + method.descriptor
                report("Partial keyboard layouts: ${listOfNotNull(row, bottom, align).size} methods")
            }
            if (role == "candidate") {
                val logo = plan.getProperty(prefix + "get.logoIv")
                val badge = plan.getProperty(prefix + "get.logoRedPoint")
                val updates = entry.methods.filter { method ->
                    method.invokes.any { it.owner == entry.name && it.name + "|" + it.descriptor == logo } &&
                        method.invokes.any { it.owner == entry.name && it.name + "|" + it.descriptor == badge } &&
                        method.invokes.any { it.name == "setImageResource" && it.descriptor == "(I)V" }
                }
                if (updates.size == 1) updates.single().let { plan[prefix + "logoUpdate." + it.name] = it.name + "|" + it.descriptor }
                else report("Logo update: ${updates.size} call-graph matches; lifecycle fallback only")
            }
        }
        return plan
    }

    /** Optional features and unused metadata never gate the core swipe hooks. */
    fun validateCore(plan: Properties, fields: Set<String>, methods: Set<String>): Boolean {
        val button = plan.getProperty("class.button") ?: return false
        val prefix = "$button#"
        fun method(key: String, descriptor: String): Boolean {
            val member = plan.getProperty(prefix + key) ?: return false
            return member in methods && member.substringAfter('|') == descriptor
        }
        fun field(key: String, descriptor: String? = null): Boolean {
            val member = plan.getProperty(prefix + "field." + key) ?: return false
            return member in fields && (descriptor == null || member.substringAfter('|') == descriptor)
        }
        return method("get.mainText", "()Ljava/lang/String;") && method("get.floatText", "()Ljava/lang/String;") &&
            method("set.floatText", "(Ljava/lang/String;)V") && field("mainText", "Ljava/lang/String;") &&
            field("floatText", "Ljava/lang/String;") && field("parent") &&
            plan.getProperty(prefix + "get.mainText") != plan.getProperty(prefix + "get.floatText")
    }
}
