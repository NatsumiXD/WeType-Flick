package dev.natsumi.wetype.markchanger

import android.content.pm.ApplicationInfo
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** All expensive DEX/metadata work is confined to installation, never a key event. */
internal class HookDiscovery(private val loader: ClassLoader, private val report: (String) -> Unit) {
    private var symbols = Properties()
    private var cache: File? = null
    private var fingerprint = ""

    fun prepare(info: ApplicationInfo) {
        prepareFiles((listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File), File(info.dataDir, "code_cache"))
    }

    internal fun prepareFiles(apks: List<File>, cacheDirectory: File) {
        val paths = apks.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
        fingerprint = "schema=9;" + paths.joinToString(";") { file ->
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        }
        cache = File(cacheDirectory, "wetype-flick-hooks.properties")
        val cached = runCatching { Properties().apply { cache!!.inputStream().use { load(it) } } }.getOrNull()
        if (cached?.getProperty("fingerprint") == fingerprint) {
            symbols = cached
            if (validate()) { report("Validated cached hook plan"); return }
            report("Cached signatures invalid; discovering again")
        }
        val started = System.nanoTime()
        val entries = paths.flatMap(DexIndex::read).distinctBy { it.name }
        symbols = HookPlan.build(entries, report)
        check(validate()) { "Required swipe signatures unavailable; original keyboard kept intact" }
        symbols["fingerprint"] = fingerprint
        val output = cache!!
        runCatching {
            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
            val temporary = File.createTempFile("wetype-hooks-", ".tmp", cacheDirectory)
            temporary.outputStream().use { symbols.store(it, "WeType-Flick dynamic hook cache") }
            try {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { report("Cache write skipped: ${it.message}") }
        report("Discovered ${entries.size} classes in ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    fun clazz(role: String): Class<*>? = symbols.getProperty("class.$role")?.let {
        runCatching { Class.forName(it, false, loader) }.getOrNull()
    }
    fun dataParameters(): List<String> = symbols.getProperty("data.parameters")?.split(',') ?: emptyList()
    fun settingParameters(): List<String> = symbols.getProperty("settings.parameters")?.split(',') ?: emptyList()
    fun settingsList(clazz: Class<*>): Method? = member(clazz, "settingsList")
    fun field(clazz: Class<*>, property: String): Field? = symbols.getProperty(clazz.name + "#field." + property)?.let { stored ->
        runCatching { clazz.getDeclaredField(stored.substringBefore('|')).apply {
            check(descriptor(type) == stored.substringAfter('|')); isAccessible = true
        } }.getOrNull()
    }
    fun getter(clazz: Class<*>, property: String): Method? = member(clazz, "get.$property")
    fun setter(clazz: Class<*>, property: String): Method? = member(clazz, "set.$property")
    fun layoutUpdate(clazz: Class<*>): Method? = member(clazz, "layoutUpdate")
    fun layoutUpdates(clazz: Class<*>): List<Method> = (listOfNotNull(layoutUpdate(clazz)) +
        symbols.stringPropertyNames().asSequence().filter { it.startsWith(clazz.name + "#partialLayout.") }
            .mapNotNull { resolve(clazz, symbols.getProperty(it)) }.toList()).distinct()
    private fun member(clazz: Class<*>, key: String): Method? = generateSequence(clazz) { it.superclass }.firstNotNullOfOrNull { owner ->
        symbols.getProperty(owner.name + "#" + key)?.let { resolve(owner, it) }
    }
    fun logoUpdates(clazz: Class<*>): List<Method> = symbols.stringPropertyNames().asSequence()
        .filter { it.startsWith(clazz.name + "#logoUpdate.") }
        .mapNotNull { resolve(clazz, symbols.getProperty(it)) }
        .filter { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) }
        .toList()
    private fun resolve(clazz: Class<*>, stored: String): Method? = clazz.declaredMethods.singleOrNull {
        it.name == stored.substringBefore('|') && descriptor(it) == stored.substringAfter('|') && !Modifier.isAbstract(it.modifiers)
    }?.apply { isAccessible = true }
    private fun validate(): Boolean = runCatching {
        val button = clazz("button") ?: return false
        HookPlan.validateCore(symbols,
            button.declaredFields.map { it.name + "|" + descriptor(it.type) }.toSet(),
            button.declaredMethods.map { it.name + "|" + descriptor(it) }.toSet())
    }.onFailure { report("Core hook validation failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)

    fun invalidate() { cache?.delete() }

    companion object {
        internal fun descriptor(method: Method): String = method.parameterTypes.joinToString("", "(", ")") { descriptor(it) } + descriptor(method.returnType)
        internal fun descriptor(type: Class<*>): String = when (type) {
            Void.TYPE -> "V"; java.lang.Boolean.TYPE -> "Z"; java.lang.Byte.TYPE -> "B"; java.lang.Short.TYPE -> "S"
            java.lang.Character.TYPE -> "C"; java.lang.Integer.TYPE -> "I"; java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"; java.lang.Double.TYPE -> "D"
            else -> if (type.isArray) type.name.replace('.', '/') else "L${type.name.replace('.', '/')};"
        }
    }
}
