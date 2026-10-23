package dev.natsumi.wetype.markchanger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SettingEntryFactoryTest {
    class Config(val titleId: Int, val isVisible: Boolean, val id: Int, val iconId: Int,
                 val descId: Int, val redPoint: Boolean, val extra: String)

    @Test fun preservesNativeResourcesAndExtraFieldsWithoutChangingOriginalConfig() {
        val parameters = listOf("titleId", "isVisible", "id", "iconId", "descId", "redPoint", "extra")
        val fields = parameters.associateWith { Config::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val factory = SettingEntryFactory.create(Config::class.java, parameters, fields)!!
        val original = Config(500, false, 10, 600, 700, true, "theme-specific")
        val entry = factory(original, 99) as Config
        assertEquals(99, entry.id)
        assertEquals(500, entry.titleId)
        assertEquals(600, entry.iconId)
        assertEquals(700, entry.descId)
        assertEquals("theme-specific", entry.extra)
        assertFalse(entry.redPoint)
        assertTrue(entry.isVisible)
        assertEquals(10, original.id)
        assertTrue(original.redPoint)
        assertFalse(original.isVisible)
    }

    @Test fun realApkResolvesNativeSettingsProviderModelAndConstructor() {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).first { it.isFile }
        val entries = DexIndex.read(apk)
        val plan = HookPlan.build(entries, ::println)
        for (role in listOf("settingProvider", "settingConfig", "settingBaseModel", "settingModel"))
            assertNotNull(role, plan.getProperty("class.$role"))
        val provider = plan.getProperty("class.settingProvider")
        assertEquals("a|()Ljava/util/List;", plan.getProperty("$provider#settingsList"))
        val config = plan.getProperty("class.settingConfig")
        val parameters = plan.getProperty("settings.parameters").split(',')
        assertEquals(listOf("id", "iconId", "titleId", "descId", "redPoint", "isVisible"), parameters)
        for (name in parameters) assertNotNull(name, plan.getProperty("$config#field.$name"))
        val base = plan.getProperty("class.settingBaseModel")
        assertNotNull(plan.getProperty("$base#get.itemView"))
        assertNotNull(plan.getProperty("$base#get.config"))
        val model = plan.getProperty("class.settingModel")
        assertNotNull(plan.getProperty("$model#field.title"))
        assertEquals(listOf("a|()V"), entries.single { it.name == model }.declaredMethods.filter {
            it.endsWith("|()V") && !it.startsWith("<") })
    }
}
