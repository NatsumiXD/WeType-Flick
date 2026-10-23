package dev.natsumi.wetype.markchanger

import java.io.File
import java.net.URLClassLoader
import java.util.Properties
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HookDiscoveryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualDiscoveryResolvesInvokableMembersAndRecoversStaleCache() {
        val apk = sequenceOf(File("../base.apk"), File("base.apk")).first { it.isFile }
        val plan = HookPlan.build(DexIndex.read(apk)) {}
        val buttonName = plan.getProperty("class.button")
        fun raw(key: String) = plan.getProperty("$buttonName#$key").substringBefore('|')
        val parentName = plan.getProperty("$buttonName#field.parent").substringAfter('|')
            .removePrefix("L").removeSuffix(";").replace('/', '.')
        val root = temporary.newFolder("fixtures")
        val output = temporary.newFolder("classes")
        // These are inert Java fixtures with only real DEX member names/signatures. No target
        // method body is translated, loaded, or executed.
        fun source(name: String, body: String): File {
            require(name.split('.').all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) })
            val file = File(root, name.replace('.', '/') + ".java")
            checkNotNull(file.parentFile).mkdirs()
            file.writeText("package ${name.substringBeforeLast('.')}; public class ${name.substringAfterLast('.')} { $body }")
            return file
        }
        for (key in listOf("field.parent", "field.mainText", "field.floatText", "get.mainText", "get.floatText", "set.floatText")) {
            require(raw(key).matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")))
        }
        val parent = source(parentName, "")
        val button = source(buttonName, """
            public $parentName ${raw("field.parent")};
            public String ${raw("field.mainText")} = "a";
            public String ${raw("field.floatText")} = "!";
            public String ${raw("get.mainText")}() { return ${raw("field.mainText")}; }
            public String ${raw("get.floatText")}() { return ${raw("field.floatText")}; }
            public void ${raw("set.floatText")}(String value) { ${raw("field.floatText")} = value; }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", output.absolutePath, parent.absolutePath, button.absolutePath))
        URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val cache = temporary.newFolder("cache")
            val coldLog = mutableListOf<String>()
            val cold = HookDiscovery(loader, coldLog::add)
            cold.prepareFiles(listOf(apk), cache)
            assertTrue(coldLog.toString(), coldLog.any { it.startsWith("Discovered ") })
            val clazz = cold.clazz("button")!!
            val instance = clazz.getDeclaredConstructor().newInstance()
            assertEquals("a", cold.getter(clazz, "mainText")!!.invoke(instance))
            cold.setter(clazz, "floatText")!!.invoke(instance, "？")
            assertEquals("？", cold.getter(clazz, "floatText")!!.invoke(instance))
            assertEquals("？", cold.field(clazz, "floatText")!!.get(instance))
            // Missing optional target classes do not prevent core resolution.
            assertNull(cold.clazz("data"))
            val warmLog = mutableListOf<String>()
            HookDiscovery(loader, warmLog::add).prepareFiles(listOf(apk), cache)
            assertTrue(warmLog.toString(), "Validated cached hook plan" in warmLog)
            assertFalse(warmLog.any { it.startsWith("Discovered ") })
            val cacheFile = File(cache, "wetype-flick-hooks.properties")
            val stored = Properties().apply { cacheFile.inputStream().use { load(it) } }
            stored["$buttonName#get.floatText"] = "removed|()Ljava/lang/String;"
            cacheFile.outputStream().use { stored.store(it, "stale member") }
            val recoveredLog = mutableListOf<String>()
            val recovered = HookDiscovery(loader, recoveredLog::add)
            recovered.prepareFiles(listOf(apk), cache)
            assertTrue(recoveredLog.toString(), recoveredLog.any { it.contains("Cached signatures invalid") })
            assertTrue(recoveredLog.any { it.startsWith("Discovered ") })
            assertEquals("v", recovered.getter(clazz, "floatText")?.name)
            val afterRecoveryLog = mutableListOf<String>()
            HookDiscovery(loader, afterRecoveryLog::add).prepareFiles(listOf(apk), cache)
            assertTrue("Rebuilt cache must replace the stale file: $afterRecoveryLog", "Validated cached hook plan" in afterRecoveryLog)
        }
    }
}
