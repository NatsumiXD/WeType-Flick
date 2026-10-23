package dev.natsumi.wetype.markchanger

import java.io.File
import java.util.zip.ZipFile
import kotlin.metadata.KmClass
import kotlin.metadata.jvm.KotlinClassMetadata

/** Reads packaged DEX metadata without loading or initializing target classes. No native scanner. */
internal object DexIndex {
    private val discoverySources = setOf("ImeButton.kt", "MoreSymbolUtil.kt", "SymbolFloatView.kt", "ImeCandidateView.kt", "KeyboardView.kt", "KeyData.kt", "KeyboardData.kt")
    private val discoveryMarkers = setOf("mainText", "floatText", "highLightSymbol", "logoIv", "keyboardType", "keyDefaultConfig",
        "SETTINGS_CONFIG_MAP", "titleId", "recorderIcon", "itemView")
    fun relevant(source: String?, metadata: Metadata?): Boolean =
        source in discoverySources || metadata?.data2?.any { it in discoveryMarkers } == true
    data class MethodRef(val owner: String, val name: String, val descriptor: String)
    data class MethodCode(val name: String, val descriptor: String, val invokes: Set<MethodRef>)
    data class Entry(val name: String, val source: String?, val metadata: Metadata?, val methods: List<MethodCode> = emptyList(),
                     val declaredFields: Set<String> = emptySet(), val declaredMethods: Set<String> = emptySet()) {
        fun kotlinClass(): KmClass? = runCatching {
            metadata?.let { (KotlinClassMetadata.readLenient(it) as? KotlinClassMetadata.Class)?.kmClass }
        }.getOrNull()
    }

    fun read(apk: File): List<Entry> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
            .flatMap { entry ->
                require(entry.size in 1..128L * 1024 * 1024) { "Unsupported DEX size" }
                Reader(zip.getInputStream(entry).use { it.readBytes() }).classes().asSequence()
            }.toList()
    }

    private class Reader(private val bytes: ByteArray) {
        private fun byte(offset: Int) = bytes[offset].toInt() and 255
        private fun int(offset: Int): Int = byte(offset) or (byte(offset + 1) shl 8) or
            (byte(offset + 2) shl 16) or (byte(offset + 3) shl 24)
        private fun short(offset: Int) = byte(offset) or (byte(offset + 1) shl 8)
        private inner class Cursor(var offset: Int) {
            fun uleb(): Int {
                var result = 0
                for (shift in 0..28 step 7) {
                    val b = byte(offset++)
                    result = result or ((b and 127) shl shift)
                    if (b < 128) return result
                }
                error("Invalid ULEB128")
            }
            fun value(depth: Int = 0): Any? {
                require(depth < 32)
                val header = byte(offset++)
                val type = header and 31
                val count = (header ushr 5) + 1
                if (type == 28) return List(uleb()) { value(depth + 1) }
                if (type == 29) {
                    uleb()
                    repeat(uleb()) { uleb(); value(depth + 1) }
                    return null
                }
                if (type == 30 || type == 31) return null
                var number = 0L
                repeat(count) { number = number or (byte(offset++).toLong() shl (it * 8)) }
                return if (type == 23) strings[number.toInt()] else number.toInt()
            }
        }

        private val strings: Array<String> by lazy {
            Array(int(56)) { index ->
                val cursor = Cursor(int(int(60) + index * 4))
                val length = cursor.uleb()
                // DEX uses modified UTF-8, including encoded NULs in Kotlin protobuf strings.
                val chars = CharArray(length)
                repeat(length) { i ->
                    val a = byte(cursor.offset++)
                    chars[i] = when {
                        a < 128 -> a
                        a < 224 -> ((a and 31) shl 6) or (byte(cursor.offset++) and 63)
                        else -> ((a and 15) shl 12) or ((byte(cursor.offset++) and 63) shl 6) or
                            (byte(cursor.offset++) and 63)
                    }.toChar()
                }
                String(chars)
            }
        }
        private val types: Array<String> by lazy { Array(int(64)) { strings[int(int(68) + it * 4)] } }

        fun classes(): List<Entry> {
            require(bytes.size >= 112 && String(bytes, 0, 4, Charsets.US_ASCII) == "dex\n")
            require(int(40) == 0x12345678) { "Unsupported DEX endianness" }
            return buildList {
                repeat(int(96)) { i ->
                    val offset = int(100) + i * 32
                    val descriptor = types[int(offset)]
                    if (!descriptor.startsWith("Lcom/tencent/wetype/")) return@repeat
                    val sourceIndex = int(offset + 16)
                    val meta = metadata(int(offset + 20))
                    val source = strings.getOrNull(sourceIndex)
                    val members = if (relevant(source, meta)) members(int(offset + 24)) else emptySet<String>() to emptySet()
                    add(Entry(descriptor.substring(1, descriptor.length - 1).replace('/', '.'), source, meta,
                        if (source == "ImeCandidateView.kt" || meta?.data2?.contains("logoIv") == true || meta?.data2?.contains("imeButtonMap") == true)
                            methods(int(offset + 24), meta?.data2?.contains("imeButtonMap") == true) else emptyList(), members.first, members.second))
                }
            }
        }

        private fun members(data: Int): Pair<Set<String>, Set<String>> {
            if (data == 0) return emptySet<String>() to emptySet()
            val cursor = Cursor(data)
            val staticFields = cursor.uleb(); val instanceFields = cursor.uleb()
            val directMethods = cursor.uleb(); val virtualMethods = cursor.uleb()
            val fields = buildSet {
                for (count in listOf(staticFields, instanceFields)) {
                    var index = 0
                    repeat(count) {
                        index += cursor.uleb(); cursor.uleb()
                        val offset = int(84) + index * 8
                        add(strings[int(offset + 4)] + "|" + types[short(offset + 2)])
                    }
                }
            }
            val methods = buildSet {
                for (count in listOf(directMethods, virtualMethods)) {
                    var index = 0
                    repeat(count) {
                        index += cursor.uleb(); cursor.uleb(); cursor.uleb()
                        val ref = method(index)
                        add(ref.name + "|" + ref.descriptor)
                    }
                }
            }
            return fields to methods
        }

        private fun method(index: Int): MethodRef {
            val offset = int(92) + index * 8
            val proto = int(76) + short(offset + 2) * 12
            val parameters = int(proto + 8)
            val args = if (parameters == 0) "" else (0 until int(parameters)).joinToString("") {
                types[short(parameters + 4 + it * 2)]
            }
            return MethodRef(types[short(offset)].removePrefix("L").removeSuffix(";").replace('/', '.'),
                strings[int(offset + 4)], "($args)" + types[int(proto + 4)])
        }

        private fun methods(data: Int, includeKeyboardUpdates: Boolean = false): List<MethodCode> {
            if (data == 0) return emptyList()
            val cursor = Cursor(data)
            val staticFields = cursor.uleb(); val instanceFields = cursor.uleb()
            val directMethods = cursor.uleb(); val virtualMethods = cursor.uleb()
            repeat(staticFields + instanceFields) { cursor.uleb(); cursor.uleb() }
            return buildList {
                for (count in listOf(directMethods, virtualMethods)) {
                    var index = 0
                    repeat(count) {
                        index += cursor.uleb(); cursor.uleb()
                        val code = cursor.uleb()
                        val ref = method(index)
                        if (code != 0 && (includeKeyboardUpdates || ref.descriptor == "(ZZ)V" ||
                                (ref.descriptor.startsWith("(Lcom/tencent/wetype/") && ref.descriptor.endsWith(";)V") &&
                                    ref.descriptor.count { it == ';' } == 1))) add(MethodCode(ref.name, ref.descriptor, invokes(code)))
                    }
                }
            }
        }

        /** Walk instruction boundaries; operands/payload words are never mistaken for invokes. */
        private fun invokes(code: Int): Set<MethodRef> = buildSet {
            val size = int(code + 12)
            var unit = 0
            while (unit < size) {
                val offset = code + 16 + unit * 2
                val opcode = byte(offset)
                if (opcode in 0x6e..0x72 || opcode in 0x74..0x78) add(method(short(offset + 2)))
                val width = when (opcode) {
                    0x00 -> when (short(offset)) {
                        0x0100 -> 4 + short(offset + 2) * 2
                        0x0200 -> 2 + short(offset + 2) * 4
                        0x0300 -> 4 + ((short(offset + 2).toLong() * int(offset + 4) + 1) / 2).toInt()
                        0 -> 1
                        else -> error("Invalid DEX payload")
                    }
                    0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29 -> 2
                    0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c -> 3
                    0x18 -> 5
                    in 0x2d..0x3d, in 0x44..0x6d, in 0x90..0xaf, in 0xd0..0xe2, 0xfe, 0xff -> 2
                    in 0x6e..0x72, in 0x74..0x78, 0xfc, 0xfd -> 3
                    0xfa, 0xfb -> 4
                    in 0x01..0x12, 0x1d, 0x1e, 0x21, 0x27, 0x28, in 0x7b..0x8f, in 0xb0..0xcf -> 1
                    else -> error("Unsupported DEX opcode: $opcode")
                }
                require(width > 0 && unit + width <= size) { "Invalid DEX instruction boundary" }
                unit += width
            }
        }

        private fun metadata(directory: Int): Metadata? {
            if (directory == 0) return null
            val set = int(directory)
            if (set == 0) return null
            repeat(int(set)) { i ->
                val cursor = Cursor(int(set + 4 + i * 4) + 1) // annotation visibility
                val annotationType = types[cursor.uleb()]
                if (annotationType != "Lkotlin/Metadata;") return@repeat
                val values = buildMap { repeat(cursor.uleb()) { put(strings[cursor.uleb()], cursor.value()) } }
                fun ints(key: String) = (values[key] as? List<*>)?.map { it as Int }?.toIntArray() ?: intArrayOf()
                fun texts(key: String) = (values[key] as? List<*>)?.map { it as String }?.toTypedArray() ?: emptyArray()
                return Metadata(kind = values["k"] as? Int ?: 1, metadataVersion = ints("mv"),
                    data1 = texts("d1"), data2 = texts("d2"), extraInt = values["xi"] as? Int ?: 0,
                    extraString = values["xs"] as? String ?: "", packageName = values["pn"] as? String ?: "")
            }
            return null
        }
    }
}
