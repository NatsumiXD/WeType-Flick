package dev.natsumi.wetype.markchanger

import java.io.File
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.JvmFieldSignature
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DexIndexTest {
    private fun entries(): List<DexIndex.Entry> {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).firstOrNull { it.isFile }
        assumeTrue("Place the supplied WeType 4.0.0 APK at repository root to run artifact regression tests", apk != null)
        return DexIndex.read(apk!!)
    }

    @Test fun resolvesShippedApkBySemanticProperties() {
        val entries = entries()
        val button = entries.filter { it.source == "ImeButton.kt" }.mapNotNull { it.kotlinClass() }
            .single { km -> km.properties.any { it.name == "floatText" } }
        val main = button.properties.single { it.name == "mainText" }
        val float = button.properties.single { it.name == "floatText" }
        assertEquals("R", main.getterSignature?.name)
        assertEquals("v", float.getterSignature?.name)
        assertEquals("H0", float.setterSignature?.name)
        assertEquals("Ljava/lang/String;", float.fieldSignature?.descriptor)
        assertNotEquals(main.fieldSignature, float.fieldSignature)
        val utility = entries.filter { it.source == "MoreSymbolUtil.kt" }
            .single { it.kotlinClass()?.properties?.any { p -> p.name == "highLightSymbol" } == true }
        assertTrue(utility.name.endsWith(".k0"))
        val candidateEntry = entries.single { it.name.endsWith(".ImeCandidateView") }
        val candidate = candidateEntry.kotlinClass()!!
        val logoUpdates = candidateEntry.methods.filter { method ->
            method.invokes.any { it.name == "getLogoIv" } && method.invokes.any { it.name == "getLogoRedPoint" } &&
                method.invokes.any { it.name == "setImageResource" }
        }
        assertEquals(listOf("c2"), logoUpdates.map { it.name })
        assertTrue(candidate.properties.any { it.name == "logoIv" })
        assertTrue(entries.any { it.source == "SymbolFloatView.kt" && it.kotlinClass()?.constructors?.any { c -> c.valueParameters.size == 8 } == true })
    }

    @Test fun propertyMappingSurvivesArbitraryClassAndMemberRenaming() {
        val entry = entries().first { it.source == "ImeButton.kt" && it.kotlinClass()?.properties?.any { p -> p.name == "floatText" } == true }
        val parsed = KotlinClassMetadata.readLenient(entry.metadata!!) as KotlinClassMetadata.Class
        val km = parsed.kmClass
        km.name = "com/tencent/wetype/newpackage/ABC123"
        km.properties.single { it.name == "floatText" }.apply {
            getterSignature = JvmMethodSignature("Z9", "()Ljava/lang/String;")
            setterSignature = JvmMethodSignature("AA123", "(Ljava/lang/String;)V")
            fieldSignature = JvmFieldSignature("Q42", "Ljava/lang/String;")
        }
        val rewritten = KotlinClassMetadata.Class(km, parsed.version, parsed.flags).write()
        val changed = DexIndex.Entry("com.tencent.wetype.newpackage.ABC123", null, rewritten).kotlinClass()!!
        val float = changed.properties.single { it.name == "floatText" }
        assertEquals("Z9", float.getterSignature?.name)
        assertEquals("AA123", float.setterSignature?.name)
        assertEquals("Q42", float.fieldSignature?.name)
    }
}
