package dev.natsumi.wetype.markchanger

import java.io.File
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import org.junit.Test
import org.junit.Assert.*

class HookPlanRegressionTest {
    @Test fun compareMetadataToActualDexMembers() {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).first { it.isFile }
        val entries = DexIndex.read(apk)
        val relevant = entries.filter { it.source in setOf("ImeButton.kt", "MoreSymbolUtil.kt", "SymbolFloatView.kt", "ImeCandidateView.kt", "KeyboardView.kt") }
        var mismatches = 0
        for (entry in relevant) {
            val km = entry.kotlinClass() ?: continue
            for (property in km.properties) {
                property.fieldSignature?.let { signature ->
                    if (signature.name + "|" + signature.descriptor !in entry.declaredFields) {
                        println("MISSING FIELD ${entry.name}#${property.name}: $signature")
                        mismatches++
                    }
                }
                for (signature in listOfNotNull(property.getterSignature, property.setterSignature)) {
                    if (signature.name + "|" + signature.descriptor !in entry.declaredMethods) {
                        println("MISSING METHOD ${entry.name}#${property.name}: $signature")
                        mismatches++
                    }
                }
            }
            for (function in km.functions) function.signature?.let { signature ->
                if (signature.name + "|" + signature.descriptor !in entry.declaredMethods) {
                    println("MISSING FUNCTION ${entry.name}#${function.name}: $signature")
                    mismatches++
                }
            }
        }
        println("Metadata/declaration mismatches: $mismatches")
        assertTrue("The supplied APK reproduces the previous all-metadata validation failure", mismatches > 0)
        val button = entries.single { it.source == "ImeButton.kt" && it.kotlinClass()?.properties?.any { p -> p.name == "floatText" } == true }
        for (property in button.kotlinClass()!!.properties.filter { it.name in setOf("mainText", "floatText", "parent") }) {
            println("BUTTON ${property.name}: field=${property.fieldSignature}, getter=${property.getterSignature}, setter=${property.setterSignature}")
            property.fieldSignature?.let { assertTrue(it.name + "|" + it.descriptor in button.declaredFields) }
        }
        val plan = HookPlan.build(entries) { println(it) }
        assertTrue("Actual hook targets must still validate", HookPlan.validateCore(plan, button.declaredFields, button.declaredMethods))
        assertFalse(plan.stringPropertyNames().any { it.startsWith(button.name + "#") && it.contains("mainTextColor") })
        // An absent or invalid optional role must not veto the working swipe targets.
        plan["class.data"] = "missing.optional.SymbolData"
        plan["data.parameters"] = "invalid"
        assertTrue(HookPlan.validateCore(plan, button.declaredFields, button.declaredMethods))
        plan[button.name + "#get.floatText"] = "missingGetter|()Ljava/lang/String;"
        assertFalse("A missing core target must invalidate the plan", HookPlan.validateCore(plan, button.declaredFields, button.declaredMethods))
    }
}
