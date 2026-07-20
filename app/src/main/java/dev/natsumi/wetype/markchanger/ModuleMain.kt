package dev.natsumi.wetype.markchanger

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Message
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

class ModuleMain : XposedModule() {

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    private var getMainTextMethod: Method? = null
    private var setFloatTextMethod: Method? = null
    private var getFloatTextMethod: Method? = null
    private var floatTextField: Field? = null
    private var hookInstalled = false
    private var appHookInstalled = false

    // ---- 键盘类型追踪 ----
    @Volatile
    private var currentKeyboardType = SymbolConfig.CN

    // ---- 内存符号表（无 IPC） ----
    private val cnSymbols = HashMap<String, String>(52)
    private val enSymbols = HashMap<String, String>(52)

    // ---- 长按弹窗符号表 ----
    private val cnLongSymbols = HashMap<String, List<String>>(26)
    private val enLongSymbols = HashMap<String, List<String>>(26)

    // ---- 实例级缓存（避免反射） ----
    private val mainTextCache = WeakHashMap<Any, String>(64)
    private val symbolCache = WeakHashMap<Any, String?>(64)

    // ---- 按钮→键盘引用字段缓存 ----
    private var buttonKeyboardField: Field? = null
    private var buttonKeyboardFieldResolved = false

    // ---- 原始上滑符号缓存（用于仅同步模式查找功能码） ----
    private val originalFloatTextMap = HashMap<String, String>(52)

    // ---- 自定义 Logo Bitmap 缓存 ----
    @Volatile
    private var cachedLogoBitmap: android.graphics.Bitmap? = null
    private var cachedLogoImageHash: Int = 0

    private var fileWriter: FileWriter? = null
    private var logFilePath: String? = null
    private val logBuffer = mutableListOf<String>()
    private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun logw(priority: Int, msg: String, throwable: Throwable? = null) {
        log(priority, TAG, msg, throwable)
        val level = when (priority) {
            Log.VERBOSE -> "V"; Log.DEBUG -> "D"; Log.INFO -> "I"
            Log.WARN -> "W"; Log.ERROR -> "E"; else -> "?"
        }
        val entry = "[${dateFmt.format(Date())} $level] $msg\n" +
                (throwable?.let { it.stackTraceToString() + "\n" } ?: "")
        val w = fileWriter
        if (w != null) {
            try { w.write(entry); w.flush() } catch (_: Exception) {}
        } else {
            synchronized(logBuffer) {
                logBuffer.add(entry)
                if (logBuffer.size > 500) logBuffer.removeAt(0)
            }
        }
    }

    private fun initFileLogger(context: Context) {
        if (fileWriter != null) return
        val candidates = listOf(
            { context.getExternalFilesDir(null) },
            { context.filesDir },
            { File("/sdcard/Android/data/com.tencent.wetype/files").apply { mkdirs() } }
        )
        for (dirSupplier in candidates) {
            try {
                val dir = dirSupplier.invoke()
                if (dir == null) continue
                val file = File(dir, "WeTypeMarkChanger.log")
                val fw = FileWriter(file, true)
                fw.write("\n========== SESSION ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ==========\n")
                synchronized(logBuffer) {
                    for (e in logBuffer) fw.write(e)
                    logBuffer.clear()
                }
                fw.flush()
                fileWriter = fw
                logFilePath = file.absolutePath
                logw(Log.INFO, "File logger ready: $logFilePath")
                return
            } catch (_: Exception) {
            }
        }
        log(Log.ERROR, TAG, "File logger: all paths failed")
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        logw(Log.INFO, "========== onModuleLoaded ==========")
        logw(Log.INFO, "Process: ${param.getProcessName()}")
        logw(Log.INFO, "Framework: ${getFrameworkName()} v${getFrameworkVersion()} (${getFrameworkVersionCode()})")
        logw(Log.INFO, "API: ${getApiVersion()}")
        try {
            remotePrefs = getRemotePreferences(SymbolConfig.PREFS_NAME)
            logw(Log.INFO, "Remote prefs OK: ${SymbolConfig.PREFS_NAME}")
            loadAllSymbols()
        } catch (e: Exception) {
            logw(Log.ERROR, "Remote prefs FAILED: ${e.message}", e)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        logw(Log.INFO, "onPackageLoaded: ${param.getPackageName()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        logw(Log.INFO, "========== onPackageReady ==========")
        logw(Log.INFO, "Package: ${param.getPackageName()}, isFirst=${param.isFirstPackage()}")
        if (param.getPackageName() != "com.tencent.wetype") return
        if (hookInstalled) { logw(Log.INFO, "Hooks already installed"); return }
        try {
            installHooks(param.getClassLoader())
        } catch (e: Throwable) {
            logw(Log.ERROR, "Hook setup FAILED: ${e.message}", e)
        }
    }

    // ========================================================================
    // 符号表加载
    // ========================================================================

    private fun loadAllSymbols() {
        cnSymbols.clear()
        enSymbols.clear()
        cnLongSymbols.clear()
        enLongSymbols.clear()
        val prefs = remotePrefs ?: return
        try {
            for ((key, value) in prefs.all) {
                if (value !is String || value.isEmpty()) continue
                when {
                    key.contains("_long_") -> {
                        val parts = key.split("_long_", limit = 2)
                        if (parts.size == 2) {
                            val keyboardType = parts[0]
                            val letter = parts[1]
                            val symbols = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (symbols.isNotEmpty()) {
                                when (keyboardType) {
                                    SymbolConfig.CN -> cnLongSymbols[letter] = symbols
                                    SymbolConfig.EN -> enLongSymbols[letter] = symbols
                                }
                            }
                        }
                    }
                    key.startsWith("${SymbolConfig.CN}_") ->
                        cnSymbols[key.removePrefix("${SymbolConfig.CN}_")] = value
                    key.startsWith("${SymbolConfig.EN}_") ->
                        enSymbols[key.removePrefix("${SymbolConfig.EN}_")] = value
                    else ->
                        cnSymbols[key] = value
                }
            }
        } catch (e: Exception) {
            logw(Log.ERROR, "loadAllSymbols failed: ${e.message}", e)
        }
        logw(Log.INFO, "Symbols loaded: cn=${cnSymbols.size}, en=${enSymbols.size}, cnLong=${cnLongSymbols.size}, enLong=${enLongSymbols.size}")
    }

    /** 获取当前键盘类型对应的自定义符号 */
    private fun getCustomSymbol(letter: String): String? {
        val key = letter.lowercase()
        val type = currentKeyboardType
        return if (type == SymbolConfig.EN) {
            enSymbols[key]
        } else {
            cnSymbols[key]
        }
    }

    /** 获取当前键盘类型对应的自定义长按符号列表 */
    private fun getCustomLongPressSymbols(letter: String): List<String>? {
        val key = letter.lowercase()
        val type = currentKeyboardType
        return if (type == SymbolConfig.EN) {
            enLongSymbols[key]
        } else {
            cnLongSymbols[key]
        }
    }

    /** 获取 button 实例的 mainText（带 WeakHashMap 缓存，避免反射） */
    private fun getMainTextCached(button: Any): String? {
        mainTextCache[button]?.let { return it }
        val mainText = try {
            getMainTextMethod?.invoke(button) as? String
        } catch (_: Exception) { null }
        if (mainText != null) mainTextCache[button] = mainText
        return mainText
    }

    /**
     * 从按钮实例反向获取其所属键盘的 KeyboardType，
     * 不依赖 f0() hook 中设置的 currentKeyboardType。
     */
    private fun getKeyboardTypeFromButton(button: Any): String {
        try {
            if (!buttonKeyboardFieldResolved) {
                buttonKeyboardFieldResolved = true
                // 按钮类 j 的 final 字段：类型为 keyboard.selfdraw.n（键盘基类）
                buttonKeyboardField = button.javaClass.declaredFields.find { f ->
                    java.lang.reflect.Modifier.isFinal(f.modifiers) &&
                        !f.type.isPrimitive &&
                        f.type != String::class.java &&
                        f.type.name.contains("selfdraw.")
                }
                buttonKeyboardField?.isAccessible = true
                logw(Log.INFO, "[OK] buttonKeyboardField: ${buttonKeyboardField?.name} (${buttonKeyboardField?.type?.name})")
            }
            val keyboard = buttonKeyboardField?.get(button) ?: return currentKeyboardType
            val typeName = try {
                val typeMethod = keyboard.javaClass.getMethod("getKeyboardType")
                val type = typeMethod.invoke(keyboard)
                type?.toString() ?: ""
            } catch (_: Exception) { "" }
            return if (typeName.contains("English", ignoreCase = true)) SymbolConfig.EN else SymbolConfig.CN
        } catch (_: Exception) {}
        return currentKeyboardType
    }

    // ========================================================================
    // Hook 安装
    // ========================================================================

    private fun installHooks(classLoader: ClassLoader) {
        logw(Log.INFO, "ClassLoader: $classLoader")

        if (!appHookInstalled) {
            try {
                val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
                attachMethod.isAccessible = true
                hook(attachMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            try {
                                val app = chain.getThisObject() as? Application
                                if (app != null && app.packageName == "com.tencent.wetype") {
                                    logw(Log.INFO, "Application.attach captured, initializing file logger...")
                                    initFileLogger(app)
                                }
                            } catch (e: Throwable) {
                                log(Log.ERROR, TAG, "Application.attach hook error: ${e.message}", e)
                            }
                            return result
                        }
                    })
                appHookInstalled = true
                logw(Log.INFO, "[OK] Application.attach hook installed")
            } catch (e: Throwable) {
                logw(Log.ERROR, "Application.attach hook failed: ${e.message}", e)
            }
        }

        val buttonClass = findClassByFeature(
            classLoader = classLoader,
            knownNames = arrayOf(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.j",
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.a",
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.b"
            ),
            packagePrefix = "com.tencent.wetype.plugin.hld.keyboard.selfdraw",
            featureCheck = ::isButtonClass,
            label = "Button"
        ) ?: run {
            logw(Log.ERROR, "Cannot find button class")
            return
        }
        logw(Log.INFO, "[OK] Button class (ImeButton): ${buttonClass.name}")

        getMainTextMethod = findMethod(buttonClass, "R", "getMainText")
            ?: findNoArgStringMethod(buttonClass)
            ?: run { logw(Log.ERROR, "Cannot find getMainText/R on button class"); return }

        setFloatTextMethod = findMethodWithStringParam(buttonClass, "H0", "setFloatText")
            ?: findMethodByParamAndReturn(buttonClass, String::class.java, Void.TYPE)
            ?: run { logw(Log.ERROR, "Cannot find H0/setFloatText on button class"); return }

        getFloatTextMethod = findMethod(buttonClass, "v", "getFloatText")
            ?: run {
                // 找到所有无参 String 返回方法，排除 getMainText 对应的那个
                val candidates = buttonClass.declaredMethods.filter {
                    it.parameterTypes.isEmpty() && it.returnType == String::class.java
                }.filter { it != getMainTextMethod }
                if (candidates.isNotEmpty()) {
                    val m = candidates.first()
                    m.isAccessible = true
                    m
                } else null
            }
            ?: run { logw(Log.ERROR, "Cannot find v/getFloatText on button class"); return }

        floatTextField = findFloatTextField(buttonClass)
        if (floatTextField == null) {
            logw(Log.WARN, "Cannot find floatText field directly, will rely on getter hook only")
        }

        logw(Log.INFO, "[OK] getMainText: ${getMainTextMethod!!.name}()")
        logw(Log.INFO, "[OK] setFloatText: ${setFloatTextMethod!!.name}(String)")
        logw(Log.INFO, "[OK] getFloatText: ${getFloatTextMethod!!.name}()")
        logw(Log.INFO, "[OK] floatTextField: ${floatTextField?.name ?: "not found"}")

        hookKeyboardTypeTracking(classLoader)
        hookSetFloatText()
        hookGetFloatText()
        hookLongPressSymbols(classLoader)
        hookLongPressDelay()
        hookLogoHide(classLoader)

        hookInstalled = true
        logw(Log.INFO, "========== ALL HOOKS INSTALLED ==========")
    }

    // ========================================================================
    // Class Discovery — 通用化混淆类/方法发现
    // ========================================================================

    /**
     * 扫描指定包下所有短名称类，覆盖 R8 混淆命名规则。
     * 扫描范围：a-z, 0-9, aa-zz, a0-z9, 0a-9z, 00-99
     */
    private fun scanPackageClasses(
        classLoader: ClassLoader,
        packagePrefix: String
    ): Sequence<Class<*>> = sequence {
        val chars = ('a'..'z') + ('0'..'9')
        for (c in chars) {
            try {
                yield(Class.forName("$packagePrefix.$c", false, classLoader))
            } catch (_: ClassNotFoundException) {}
        }
        for (c1 in chars) {
            for (c2 in chars) {
                try {
                    yield(Class.forName("$packagePrefix.$c1$c2", false, classLoader))
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * 通过特征在包中查找类：先尝试已知全名（快速路径），再扫描包（兼容新版本）。
     */
    private fun findClassByFeature(
        classLoader: ClassLoader,
        knownNames: Array<String>,
        packagePrefix: String,
        featureCheck: (Class<*>) -> Boolean,
        label: String
    ): Class<*>? {
        for (name in knownNames) {
            try {
                val clazz = Class.forName(name, false, classLoader)
                if (featureCheck(clazz)) {
                    logw(Log.INFO, "[Discovery:$label] Known name hit: $name")
                    return clazz
                }
            } catch (_: ClassNotFoundException) {}
        }
        logw(Log.INFO, "[Discovery:$label] Scanning $packagePrefix ...")
        for (clazz in scanPackageClasses(classLoader, packagePrefix)) {
            if (featureCheck(clazz)) {
                logw(Log.INFO, "[Discovery:$label] Feature match: ${clazz.name}")
                return clazz
            }
        }
        logw(Log.WARN, "[Discovery:$label] NOT FOUND in $packagePrefix")
        return null
    }

    // ---- Feature checks ----

    /**
     * 按钮类特征：2+ 个无参返回 String 的方法 + 1 个接收 String 返回 void 的方法
     * 对应 getMainText / getFloatText / setFloatText
     */
    private fun isButtonClass(clazz: Class<*>): Boolean {
        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return false
        val methods = clazz.declaredMethods
        var noParamStringReturnCount = 0
        var hasStringParamVoidReturn = false
        for (m in methods) {
            if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                noParamStringReturnCount++
            }
            if (m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                m.returnType == Void.TYPE) {
                hasStringParamVoidReturn = true
            }
        }
        return noParamStringReturnCount >= 2 && hasStringParamVoidReturn
    }

    /**
     * 键盘类特征：拥有 getKeyboardType() 方法（返回非 void 类型）
     */
    private fun isKeyboardClass(clazz: Class<*>): Boolean {
        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return false
        return try {
            clazz.getDeclaredMethod("getKeyboardType").returnType != Void.TYPE
        } catch (_: NoSuchMethodException) {
            false
        }
    }

    /**
     * MoreSymbolUtil 特征：静态自身类型字段（单例）+ 2 个同参数类型返回 List 的方法
     */
    private fun isMoreSymbolUtilClass(clazz: Class<*>): Boolean {
        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return false
        val hasSingleton = clazz.declaredFields.any {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == clazz
        }
        if (!hasSingleton) return false
        val listMethods = clazz.declaredMethods.filter {
            it.returnType == java.util.List::class.java && it.parameterCount == 1
        }
        if (listMethods.size < 2) return false
        val paramType = listMethods[0].parameterTypes[0]
        return listMethods.all { it.parameterTypes[0] == paramType }
    }

    /**
     * SymbolFloatData 特征：10 参数构造器 + String 类型字段
     */
    private fun isSymbolFloatDataClass(clazz: Class<*>): Boolean {
        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return false
        val has10ParamCtor = clazz.declaredConstructors.any { it.parameterCount == 10 }
        if (!has10ParamCtor) return false
        return clazz.declaredFields.any { it.type == String::class.java }
    }

    /**
     * ImeCandidateView 特征：拥有双 boolean 参数的方法（a2 方法）
     */
    private fun isCandidateViewClass(clazz: Class<*>): Boolean {
        return clazz.declaredMethods.any {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
    }

    // ---- 签名匹配的通用方法查找 ----

    /** 按返回类型 + 无参数查找方法 */
    private fun findNoArgStringMethod(clazz: Class<*>): Method? {
        for (m in clazz.declaredMethods) {
            if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    /** 按返回类型 + 参数类型查找方法（排除标准方法名如 toString 等） */
    private fun findMethodBySignature(
        clazz: Class<*>,
        returnType: Class<*>,
        paramTypes: Array<Class<*>> = emptyArray(),
        excludeNames: Set<String> = setOf("toString", "hashCode", "equals", "getClass", "notify", "wait")
    ): Method? {
        for (m in clazz.declaredMethods) {
            if (m.name in excludeNames) continue
            if (m.returnType == returnType && m.parameterTypes.contentEquals(paramTypes)) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    /** 按参数类型和返回类型查找方法（单参数版本） */
    private fun findMethodByParamAndReturn(
        clazz: Class<*>,
        paramType: Class<*>,
        returnType: Class<*>
    ): Method? {
        for (m in clazz.declaredMethods) {
            if (m.returnType == returnType &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == paramType) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    // ========================================================================
    // 键盘类型追踪 — hook n.f0()
    // ========================================================================

    private fun hookKeyboardTypeTracking(classLoader: ClassLoader) {
        try {
            val keyboardBaseClass = findClassByFeature(
                classLoader = classLoader,
                knownNames = arrayOf(
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.n",
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.a",
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.b"
                ),
                packagePrefix = "com.tencent.wetype.plugin.hld.keyboard.selfdraw",
                featureCheck = ::isKeyboardClass,
                label = "Keyboard"
            ) ?: run {
                logw(Log.WARN, "Cannot find keyboard base class")
                return
            }
            val f0Method = findMethod(keyboardBaseClass, "f0", "initKeyboard")
                ?: run { logw(Log.WARN, "Cannot find f0/initKeyboard on keyboard base class"); return }

            hook(f0Method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        // 先检测键盘类型（H0 在 f0 内部调用，必须在此之前设置好 currentKeyboardType）
                        val previousType = currentKeyboardType
                        try {
                            val keyboard = chain.getThisObject()
                            val typeMethod = keyboard.javaClass.getMethod("getKeyboardType")
                            val type = typeMethod.invoke(keyboard)
                            val typeName = type?.toString() ?: ""
                            currentKeyboardType = if (typeName.contains("English", ignoreCase = true)) {
                                SymbolConfig.EN
                            } else {
                                SymbolConfig.CN
                            }
                            logw(Log.DEBUG, "Keyboard type (pre-init): $currentKeyboardType ($typeName)")
                        } catch (e: Exception) {
                            logw(Log.WARN, "getKeyboardType (pre-init) failed: ${e.message}")
                        }

                        val result = chain.proceed()

                        // proceed 后再次检测 + 类型切换时清理缓存
                        try {
                            val keyboard = chain.getThisObject()
                            val typeMethod = keyboard.javaClass.getMethod("getKeyboardType")
                            val type = typeMethod.invoke(keyboard)
                            val typeName = type?.toString() ?: ""
                            currentKeyboardType = if (typeName.contains("English", ignoreCase = true)) {
                                SymbolConfig.EN
                            } else {
                                SymbolConfig.CN
                            }
                            if (previousType != currentKeyboardType) {
                                symbolCache.clear()
                                mainTextCache.clear()
                                logw(Log.INFO, "Keyboard type changed: $previousType -> $currentKeyboardType, caches cleared")
                            }
                        } catch (e: Exception) {
                            logw(Log.WARN, "getKeyboardType (post-init) failed: ${e.message}")
                        }

                        return result
                    }
                })
            logw(Log.INFO, "[OK] Hooked keyboard type tracking (f0)")
        } catch (e: Throwable) {
            logw(Log.WARN, "Keyboard type tracking hook failed: ${e.message}")
        }
    }

    // ========================================================================
    // H0 (setFloatText) hook — 拦截符号设置
    // ========================================================================

    private var h0CallCount = 0

    private fun hookSetFloatText() {
        val method = setFloatTextMethod ?: return
        hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val button = chain.getThisObject()
                    val originalFloatText = chain.getArg(0) as? String ?: ""

                    chain.proceed()

                    // 从按钮实例实时检测键盘类型，不依赖 f0() hook
                    currentKeyboardType = getKeyboardTypeFromButton(button)

                    val mainText = getMainTextCached(button) ?: return null

                    if (mainText.length == 1) {
                        val ch = mainText[0]
                        if (ch in 'a'..'z' || ch in 'A'..'Z') {
                            // 缓存原始上滑符号（用于仅同步模式查找功能码）
                            originalFloatTextMap[mainText.lowercase()] = originalFloatText

                            val custom = getCustomSymbol(mainText)
                            if (custom != null && custom.isNotEmpty()) {
                                symbolCache[button] = custom
                                if (floatTextField != null) {
                                    try {
                                        floatTextField!!.set(button, custom)
                                    } catch (_: Exception) {}
                                }
                                val count = ++h0CallCount
                                if (count <= 50) {
                                    logw(Log.INFO, "[H0] #$count '$mainText' '$originalFloatText' -> '$custom' ($currentKeyboardType)")
                                }
                            } else if (custom != null && custom.isEmpty()) {
                                symbolCache[button] = ""
                                if (floatTextField != null) {
                                    try { floatTextField!!.set(button, "") } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    return null
                }
            })
        logw(Log.INFO, "[OK] Hooked setFloatText (${method.name})")
    }

    // ========================================================================
    // v() (getFloatText) hook — 拦截符号读取（热路径，只用缓存）
    // ========================================================================

    private var vCallCount = 0

    private fun hookGetFloatText() {
        val method = getFloatTextMethod ?: return
        hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val button = chain.getThisObject()

                    // 从按钮实例实时检测键盘类型，不依赖 f0() hook
                    currentKeyboardType = getKeyboardTypeFromButton(button)

                    // 快速路径：缓存命中直接返回
                    val cached = symbolCache[button]
                    if (cached != null) return cached

                    // 慢速路径：首次查自定义符号
                    val original = chain.proceed() as? String ?: return null
                    val mainText = getMainTextCached(button) ?: return original
                    if (mainText.length != 1) return original
                    val ch = mainText[0]
                    if (ch !in 'a'..'z' && ch !in 'A'..'Z') return original

                    val custom = getCustomSymbol(mainText) ?: run {
                        symbolCache[button] = null
                        return original
                    }

                    symbolCache[button] = custom
                    val count = ++vCallCount
                    if (count <= 30) {
                        logw(Log.DEBUG, "[v] '$mainText' '$original' -> '$custom' ($currentKeyboardType)")
                    }
                    return custom
                }
            })
        logw(Log.INFO, "[OK] Hooked getFloatText (${method.name})")
    }

    // ========================================================================
    // C2410j0.k()/l() hook — 拦截长按弹窗符号列表
    // ========================================================================

    private var longPressHookCount = 0
    private var highLightSet: java.util.HashSet<String>? = null

    private fun hookLongPressSymbols(classLoader: ClassLoader) {
        try {
            val moreSymbolUtilClass = findClassByFeature(
                classLoader = classLoader,
                knownNames = arrayOf(
                    "com.tencent.wetype.plugin.hld.utils.j0",
                    "com.tencent.wetype.plugin.hld.utils.a",
                    "com.tencent.wetype.plugin.hld.utils.b"
                ),
                packagePrefix = "com.tencent.wetype.plugin.hld.utils",
                featureCheck = ::isMoreSymbolUtilClass,
                label = "MoreSymbolUtil"
            ) ?: run {
                logw(Log.WARN, "Cannot find MoreSymbolUtil class")
                return
            }

            val buttonClass = findClassByFeature(
                classLoader = classLoader,
                knownNames = arrayOf(
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.j",
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.a",
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.b"
                ),
                packagePrefix = "com.tencent.wetype.plugin.hld.keyboard.selfdraw",
                featureCheck = ::isButtonClass,
                label = "Button(for LongPress)"
            ) ?: run {
                logw(Log.WARN, "Cannot find button class for long-press hook")
                return
            }

            val sfDataClass = findClassByFeature(
                classLoader = classLoader,
                knownNames = arrayOf(
                    "com.tencent.wetype.plugin.hld.floatview.C",
                    "com.tencent.wetype.plugin.hld.floatview.a",
                    "com.tencent.wetype.plugin.hld.floatview.b"
                ),
                packagePrefix = "com.tencent.wetype.plugin.hld.floatview",
                featureCheck = ::isSymbolFloatDataClass,
                label = "SymbolFloatData"
            ) ?: run {
                logw(Log.WARN, "Cannot find SymbolFloatData class")
                return
            }

            // text 字段 — 按字段类型和上下文查找
            val textField = sfDataClass.declaredFields.find {
                it.type == String::class.java
            }?.apply { isAccessible = true }
                ?: run {
                    logw(Log.WARN, "Cannot find text field on SymbolFloatData")
                    return
                }

            // 10参数 synthetic 构造器
            val sfCtor = sfDataClass.declaredConstructors.find { it.parameterCount == 10 }
                ?: throw IllegalStateException("Cannot find 10-param SymbolFloatData constructor")
            sfCtor.isAccessible = true

            // 获取 highLightSymbol HashSet（用于默认选中位置）
            // 找到单例实例和 j() 方法，调用 j() 初始化集合
            try {
                val singletonField = moreSymbolUtilClass.declaredFields.find {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == moreSymbolUtilClass
                }
                singletonField?.isAccessible = true
                val singleton = singletonField?.get(null)
                val jMethod = moreSymbolUtilClass.declaredMethods.find {
                    it.parameterCount == 0 && it.returnType == java.util.HashSet::class.java
                }
                jMethod?.isAccessible = true
                if (singleton != null && jMethod != null) {
                    highLightSet = jMethod.invoke(singleton) as? java.util.HashSet<String>
                    logw(Log.INFO, "[OK] highLightSymbol set initialized: ${highLightSet?.size} items")
                }
            } catch (e: Exception) {
                logw(Log.WARN, "highLightSymbol init failed: ${e.message}")
            }

            logw(Log.INFO, "[OK] SymbolFloatData: class=${sfDataClass.name}, ctor=${sfCtor.parameterCount}p")

            // Hook k() — 小写 QWERTY 长按符号
            val kMethod = moreSymbolUtilClass.getDeclaredMethod("k", buttonClass)
            kMethod.isAccessible = true
            hook(kMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed() as? List<*> ?: return null
                        val button = chain.getArg(0) as? Any ?: return result
                        return modifyLongPressList(button, result, textField, sfCtor)
                    }
                })
            logw(Log.INFO, "[OK] Hooked MoreSymbolUtil.k()")

            // Hook l() — 大写 QWERTY 长按符号
            val lMethod = moreSymbolUtilClass.getDeclaredMethod("l", buttonClass)
            lMethod.isAccessible = true
            hook(lMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed() as? List<*> ?: return null
                        val button = chain.getArg(0) as? Any ?: return result
                        return modifyLongPressList(button, result, textField, sfCtor)
                    }
                })
            logw(Log.INFO, "[OK] Hooked MoreSymbolUtil.l()")

        } catch (e: Throwable) {
            logw(Log.WARN, "Long-press symbol hook failed: ${e.message}", e)
        }
    }

    private fun modifyLongPressList(
        button: Any,
        originalList: List<*>,
        textField: Field,
        ctor: java.lang.reflect.Constructor<*>
    ): List<*> {
        // 从按钮实例实时检测键盘类型
        currentKeyboardType = getKeyboardTypeFromButton(button)

        val prefs = remotePrefs
        val syncOnly = prefs?.getBoolean(SymbolConfig.KEY_SYNC_SWIPE_ONLY, false) ?: false
        val mainText = getMainTextCached(button)
        val customSymbols = if (mainText != null) getCustomLongPressSymbols(mainText) else null

        val count = ++longPressHookCount
        if (count <= 20) {
            logw(Log.DEBUG, "[LongPress] called: main='$mainText', custom=${customSymbols?.size ?: "null"}, syncOnly=$syncOnly, listSize=${originalList.size}, type=$currentKeyboardType")
        }

        if (mainText == null) return originalList

        // ===== 仅同步上滑符号模式 =====
        // 用自定义上滑符号替换长按弹窗中的原始上滑符号（功能码），长按配置不生效
        if (syncOnly) {
            val customSwipe = getCustomSymbol(mainText) ?: return originalList
            val originalSwipe = originalFloatTextMap[mainText.lowercase()] ?: return originalList
            if (customSwipe == originalSwipe) return originalList

            // 在长按列表中找到原始上滑符号并替换
            val modifiedList = originalList.toMutableList()
            for (i in modifiedList.indices) {
                val item = modifiedList[i] ?: continue
                val currentText = try { textField.get(item) as? String } catch (_: Exception) { null } ?: continue
                if (currentText == originalSwipe) {
                    try {
                        val newItem = ctor.newInstance(customSwipe, "", false, "", 0, false, false, null, 252, null)
                        modifiedList[i] = newItem
                        // 将自定义符号加入高亮集合，确保 b() 能找到默认选中位置
                        highLightSet?.add(customSwipe)
                        if (count <= 20) {
                            logw(Log.DEBUG, "[LongPress] syncOnly: '$mainText'[$i] '$originalSwipe' -> '$customSwipe'")
                        }
                        return modifiedList
                    } catch (e: Exception) {
                        logw(Log.WARN, "[LongPress] syncOnly FAILED: ${e.message}")
                        return originalList
                    }
                }
            }
            if (count <= 20) {
                logw(Log.DEBUG, "[LongPress] syncOnly: '$mainText' originalSwipe='$originalSwipe' not found in list")
            }
            return originalList
        }

        // ===== 自定义长按符号模式 =====
        if (customSymbols == null) return originalList

        // 用自定义符号重建整个列表（与原始代码创建普通文本项方式一致）
        val newList = ArrayList<Any>(customSymbols.size)
        for (symbol in customSymbols) {
            // 将自定义符号加入高亮集合，确保 b() 能找到默认选中位置
            highLightSet?.add(symbol)
            try {
                val item = ctor.newInstance(symbol, "", false, "", 0, false, false, null, 252, null)
                newList.add(item)
            } catch (e: Exception) {
                logw(Log.WARN, "[LongPress] FAILED to create '$symbol': ${e.message}", e)
                return originalList
            }
        }

        if (count <= 20) {
            logw(Log.DEBUG, "[LongPress] replaced '$mainText': ${originalList.size}items -> ${newList.size}items [${customSymbols.joinToString(",")}]")
        }
        return newList
    }

    // ========================================================================
    // 长按延迟 — hook Handler.sendMessageDelayed
    // ========================================================================

    private fun hookLongPressDelay() {
        val inLongPressDelayHook = object : ThreadLocal<Boolean>() {
            override fun initialValue() = false
        }
        try {
            hook(Handler::class.java.getMethod("sendMessageDelayed", Message::class.java, Long::class.javaPrimitiveType!!))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        if (inLongPressDelayHook.get() == true) return chain.proceed()
                        val msg = chain.getArg(0) as? Message ?: return chain.proceed()
                        val originalDelay = chain.getArg(1) as? Long ?: return chain.proceed()
                        if (msg.what == 1 && originalDelay == SymbolConfig.DEFAULT_LONG_PRESS_DELAY) {
                            val customDelay = remotePrefs?.getLong(SymbolConfig.KEY_LONG_PRESS_DELAY, SymbolConfig.DEFAULT_LONG_PRESS_DELAY)
                                ?: SymbolConfig.DEFAULT_LONG_PRESS_DELAY
                            if (customDelay != SymbolConfig.DEFAULT_LONG_PRESS_DELAY) {
                                val handler = chain.getThisObject() as? Handler ?: return chain.proceed()
                                inLongPressDelayHook.set(true)
                                try {
                                    handler.removeMessages(1)
                                    handler.sendMessageDelayed(msg, customDelay)
                                } finally {
                                    inLongPressDelayHook.set(false)
                                }
                                return true
                            }
                        }
                        return chain.proceed()
                    }
                })
            logw(Log.INFO, "[OK] Hooked Handler.sendMessageDelayed for long-press delay")
        } catch (e: Throwable) {
            logw(Log.WARN, "Long-press delay hook failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // Logo hide/replace - hook ImeCandidateView, hide or replace logo image
    // ========================================================================

    /** 解码自定义 Logo Bitmap（带缓存，Base64 hash 变化时重新解码） */
    private fun getCachedLogoBitmap(): android.graphics.Bitmap? {
        val base64 = remotePrefs?.getString(SymbolConfig.KEY_LOGO_IMAGE, null) ?: run {
            logw(Log.WARN, "[Logo] getCachedLogoBitmap: logoImage key is null in prefs (prefs=${remotePrefs?.all?.keys?.joinToString(",")})")
            cachedLogoBitmap = null
            cachedLogoImageHash = 0
            return null
        }
        logw(Log.INFO, "[Logo] getCachedLogoBitmap: base64 length=${base64.length}")
        val hash = base64.hashCode()
        if (hash == cachedLogoImageHash && cachedLogoBitmap != null) {
            logw(Log.DEBUG, "[Logo] getCachedLogoBitmap: cache hit")
            return cachedLogoBitmap
        }
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            cachedLogoBitmap = bitmap
            cachedLogoImageHash = hash
            logw(Log.INFO, "[Logo] Bitmap decoded: ${bitmap?.width}x${bitmap?.height}")
            bitmap
        } catch (e: Exception) {
            logw(Log.WARN, "[Logo] Bitmap decode failed: ${e.message}", e)
            null
        }
    }

    private fun hookLogoHide(classLoader: ClassLoader) {
        try {
            val candidateViewClass = findClassByFeature(
                classLoader = classLoader,
                knownNames = arrayOf(
                    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
                ),
                packagePrefix = "com.tencent.wetype.plugin.hld.candidate",
                featureCheck = ::isCandidateViewClass,
                label = "CandidateView"
            ) ?: run {
                logw(Log.WARN, "Cannot find ImeCandidateView class")
                return
            }

            fun findViewByRes(view: android.view.View, resName: String): android.view.View? {
                val res = view.resources
                var id = res.getIdentifier(resName, "id", view.context.packageName)
                if (id == 0) id = res.getIdentifier(resName, "id", "com.tencent.wetype")
                if (id == 0) {
                    logw(Log.WARN, "[Logo] findViewByRes: getIdentifier('$resName','id') returned 0! pkg=${view.context.packageName}")
                    return null
                }
                val found = view.findViewById<android.view.View>(id)
                if (found == null) {
                    logw(Log.WARN, "[Logo] findViewByRes: id=$id found but findViewById returned null! view=${view.javaClass.simpleName}, childCount=${(view as? android.view.ViewGroup)?.childCount}")
                }
                return found
            }

            /** 通过反射调用 getLogoIv() 获取 logo ImageView */
            fun getLogoIvViaReflection(view: android.view.View): android.widget.ImageView? {
                return try {
                    val method = candidateViewClass.getDeclaredMethod("getLogoIv")
                    method.isAccessible = true
                    val iv = method.invoke(view) as? android.widget.ImageView
                    logw(Log.INFO, "[Logo] getLogoIvViaReflection: $iv")
                    iv
                } catch (e: Exception) {
                    logw(Log.WARN, "[Logo] getLogoIvViaReflection failed: ${e.message}")
                    null
                }
            }

            /** 递归遍历 View 树查找 ImageView（调试用） */
            fun dumpViewHierarchy(view: android.view.View?, depth: Int = 0, maxDepth: Int = 5): String {
                if (view == null || depth > maxDepth) return ""
                val sb = StringBuilder()
                val indent = "  ".repeat(depth)
                val id = view.id
                val idName = if (id != android.view.View.NO_ID) {
                    try { view.resources.getResourceEntryName(id) } catch (_: Exception) { "id=$id" }
                } else "no_id"
                sb.appendLine("$indent[${view.javaClass.simpleName}] $idName vis=${view.visibility}")
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) {
                        sb.append(dumpViewHierarchy(view.getChildAt(i), depth + 1, maxDepth))
                    }
                }
                return sb.toString()
            }

            fun isLogoActionEnabled(): Boolean {
                val mode = remotePrefs?.getInt(SymbolConfig.KEY_LOGO_MODE, SymbolConfig.LOGO_SHOW)
                    ?: SymbolConfig.LOGO_SHOW
                return mode != SymbolConfig.LOGO_SHOW
            }

            fun getLogoMode(): Int {
                return remotePrefs?.getInt(SymbolConfig.KEY_LOGO_MODE, SymbolConfig.LOGO_SHOW)
                    ?: SymbolConfig.LOGO_SHOW
            }

            /** 通过反射直接设置 ImageView 的 Drawable，绕过 setImageDrawable hook
             *  解决与 WeType_UI_Enhanced 模块的兼容性问题（该模块 hook 了 setImageDrawable/setImageResource） */
            fun setLogoBitmapDirectly(imageView: android.widget.ImageView, bitmap: android.graphics.Bitmap) {
                val drawable = android.graphics.drawable.BitmapDrawable(imageView.resources, bitmap)
                try {
                    // 方式1：调用 private updateDrawable(Drawable) 方法
                    val updateDrawableMethod = android.widget.ImageView::class.java
                        .getDeclaredMethod("updateDrawable", android.graphics.drawable.Drawable::class.java)
                    updateDrawableMethod.isAccessible = true

                    // 清除 mResource，防止资源 ID 覆盖
                    val mResourceField = android.widget.ImageView::class.java.getDeclaredField("mResource")
                    mResourceField.isAccessible = true
                    mResourceField.setInt(imageView, 0)

                    // 清除 mUri
                    try {
                        val mUriField = android.widget.ImageView::class.java.getDeclaredField("mUri")
                        mUriField.isAccessible = true
                        mUriField.set(imageView, null)
                    } catch (_: Exception) {}

                    updateDrawableMethod.invoke(imageView, drawable)
                    imageView.requestLayout()
                    imageView.invalidate()
                    logw(Log.INFO, "[Logo] setLogoBitmapDirectly: SUCCESS (via updateDrawable)")
                } catch (e1: Exception) {
                    logw(Log.WARN, "[Logo] updateDrawable reflection failed: ${e1.message}, trying field approach")
                    // 方式2：直接设置 mDrawable 字段
                    try {
                        val mDrawableField = android.widget.ImageView::class.java.getDeclaredField("mDrawable")
                        mDrawableField.isAccessible = true

                        val oldDrawable = mDrawableField.get(imageView) as? android.graphics.drawable.Drawable
                        oldDrawable?.setCallback(null)

                        drawable.setCallback(imageView)
                        drawable.setLayoutDirection(imageView.layoutDirection)
                        drawable.setState(imageView.drawableState)
                        drawable.setLevel(imageView.drawable?.level ?: 0)
                        drawable.setVisible(imageView.visibility == android.view.View.VISIBLE, true)
                        mDrawableField.set(imageView, drawable)

                        val mResourceField = android.widget.ImageView::class.java.getDeclaredField("mResource")
                        mResourceField.isAccessible = true
                        mResourceField.setInt(imageView, 0)

                        try {
                            val mDrawableWidthField = android.widget.ImageView::class.java.getDeclaredField("mDrawableWidth")
                            mDrawableWidthField.isAccessible = true
                            mDrawableWidthField.setInt(imageView, drawable.intrinsicWidth)
                            val mDrawableHeightField = android.widget.ImageView::class.java.getDeclaredField("mDrawableHeight")
                            mDrawableHeightField.isAccessible = true
                            mDrawableHeightField.setInt(imageView, drawable.intrinsicHeight)
                        } catch (_: Exception) {}

                        try {
                            val configureBoundsMethod = android.widget.ImageView::class.java.getDeclaredMethod("configureBounds")
                            configureBoundsMethod.isAccessible = true
                            configureBoundsMethod.invoke(imageView)
                        } catch (_: Exception) {}

                        imageView.requestLayout()
                        imageView.invalidate()
                        logw(Log.INFO, "[Logo] setLogoBitmapDirectly: SUCCESS (via field)")
                    } catch (e2: Exception) {
                        logw(Log.WARN, "[Logo] field approach also failed: ${e2.message}, falling back to setImageBitmap")
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }

            /** 在 replace 模式下设置自定义 Logo 图片 */
            fun applyLogoReplace(view: android.view.View) {
                if (getLogoMode() != SymbolConfig.LOGO_REPLACE) return
                // 方式1：通过资源名查找
                var logoIv = findViewByRes(view, "logo_iv")
                // 方式2：通过反射调用 getLogoIv()
                if (logoIv == null) {
                    logw(Log.INFO, "[Logo] findViewByRes failed, trying getLogoIv() reflection...")
                    logoIv = getLogoIvViaReflection(view)
                }
                logw(Log.INFO, "[Logo] applyLogoReplace: logoIv=$logoIv, mode=${getLogoMode()}")
                if (logoIv == null) {
                    logw(Log.WARN, "[Logo] applyLogoReplace: logo_iv not found! Dumping view hierarchy:")
                    logw(Log.WARN, dumpViewHierarchy(view))
                    return
                }
                val bitmap = getCachedLogoBitmap()
                logw(Log.INFO, "[Logo] applyLogoReplace: bitmap=$bitmap")
                if (bitmap == null) {
                    logw(Log.WARN, "[Logo] applyLogoReplace: bitmap is null, cannot replace!")
                    return
                }
                if (logoIv.visibility != android.view.View.VISIBLE) {
                    logoIv.visibility = android.view.View.VISIBLE
                }
                try {
                    setLogoBitmapDirectly(logoIv as android.widget.ImageView, bitmap)
                    logw(Log.INFO, "[Logo] applyLogoReplace: setLogoBitmapDirectly done")
                } catch (e: Exception) {
                    logw(Log.WARN, "[Logo] setLogoBitmapDirectly failed: ${e.message}")
                }
            }

            fun applyLogoAction(view: android.view.View) {
                val mode = getLogoMode()
                logw(Log.INFO, "[Logo] applyLogoAction: mode=$mode")
                if (mode == SymbolConfig.LOGO_SHOW) return
                // 隐藏红点（hide 和 replace 模式都隐藏）
                val redPoint = findViewByRes(view, "logo_red_point")
                if (redPoint != null && redPoint.visibility != android.view.View.GONE) {
                    redPoint.visibility = android.view.View.GONE
                }
                if (mode == SymbolConfig.LOGO_REPLACE) {
                    applyLogoReplace(view)
                } else {
                    // LOGO_HIDE 模式
                    val logoIv = findViewByRes(view, "logo_iv")
                    if (logoIv != null && logoIv.visibility != android.view.View.INVISIBLE) {
                        logoIv.visibility = android.view.View.INVISIBLE
                    }
                }
            }

            // 列出所有 (boolean, boolean) 方法以诊断
            val allBoolBoolMethods = candidateViewClass.declaredMethods.filter { m ->
                m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            logw(Log.INFO, "[Logo] Found ${allBoolBoolMethods.size} (boolean,boolean) methods: ${allBoolBoolMethods.joinToString { it.name }}}")

            val a2Method = allBoolBoolMethods.firstOrNull()
            if (a2Method != null) {
                a2Method.isAccessible = true
                logw(Log.INFO, "[Logo] Hooking method: ${a2Method.name}(boolean,boolean)")
                // 诊断：打印 prefs 状态
                val modeAtInstall = remotePrefs?.getInt(SymbolConfig.KEY_LOGO_MODE, SymbolConfig.LOGO_SHOW) ?: SymbolConfig.LOGO_SHOW
                val imgAtInstall = remotePrefs?.getString(SymbolConfig.KEY_LOGO_IMAGE, null)
                logw(Log.INFO, "[Logo] Prefs at install: logoMode=$modeAtInstall, logoImage=${if (imgAtInstall != null) "present(len=${imgAtInstall.length})" else "null"}, allKeys=${remotePrefs?.all?.keys?.joinToString(",")}")
                hook(a2Method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            logw(Log.INFO, "[Logo] a2 hook triggered! mode=${getLogoMode()}")
                            val result = chain.proceed()
                            val view = chain.getThisObject() as? android.view.View ?: run {
                                logw(Log.WARN, "[Logo] a2: thisObject is not a View")
                                return result
                            }
                            applyLogoAction(view)
                            view.post { applyLogoAction(view) }
                            return result
                        }
                    })
                logw(Log.INFO, "[OK] Hooked init(boolean,boolean) for logo hide/replace")
            } else {
                logw(Log.WARN, "Cannot find a2(boolean,boolean) on ImeCandidateView")
            }

            // Hook getLogoIv — 按名称或返回类型特征查找
            try {
                val getLogoIvMethod = candidateViewClass.declaredMethods.find { m ->
                    (m.name == "getLogoIv" ||
                        (m.parameterTypes.isEmpty() &&
                            m.returnType == android.view.View::class.java &&
                            m.name.contains("logo", ignoreCase = true)))
                }?.apply { isAccessible = true }
                if (getLogoIvMethod != null) {
                    hook(getLogoIvMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                val mode = getLogoMode()
                                if (mode == SymbolConfig.LOGO_SHOW) return result
                                val iv = result as? android.view.View ?: return result
                                if (mode == SymbolConfig.LOGO_HIDE) {
                                    if (iv.visibility != android.view.View.INVISIBLE) {
                                        iv.visibility = android.view.View.INVISIBLE
                                    }
                                } else if (mode == SymbolConfig.LOGO_REPLACE) {
                                    logw(Log.INFO, "[Logo] getLogoIv hook: REPLACE mode, setting bitmap")
                                    if (iv.visibility != android.view.View.VISIBLE) {
                                        iv.visibility = android.view.View.VISIBLE
                                    }
                                    val bitmap = getCachedLogoBitmap()
                                    if (bitmap != null) {
                                        try {
                                            setLogoBitmapDirectly(iv as android.widget.ImageView, bitmap)
                                            logw(Log.INFO, "[Logo] getLogoIv hook: setLogoBitmapDirectly done")
                                        } catch (e: Exception) {
                                            logw(Log.WARN, "[Logo] getLogoIv hook: setLogoBitmapDirectly failed: ${e.message}")
                                        }
                                    } else {
                                        logw(Log.WARN, "[Logo] getLogoIv hook: bitmap is null!")
                                    }
                                }
                                return result
                            }
                        })
                    logw(Log.INFO, "[OK] Hooked ${getLogoIvMethod.name}() for logo (${if (getLogoMode() == SymbolConfig.LOGO_REPLACE) "REPLACE" else "HIDE"})")
                } else {
                    logw(Log.WARN, "getLogoIv() not found by name or feature")
                }
            } catch (e: Throwable) {
                logw(Log.WARN, "getLogoIv hook failed: ${e.message}")
            }

            // Hook getLogoRedPoint — 按名称或返回类型特征查找
            try {
                val getLogoRedPointMethod = candidateViewClass.declaredMethods.find { m ->
                    (m.name == "getLogoRedPoint" ||
                        (m.parameterTypes.isEmpty() &&
                            m.returnType == android.view.View::class.java &&
                            (m.name.contains("red", ignoreCase = true) ||
                                m.name.contains("point", ignoreCase = true))))
                }?.apply { isAccessible = true }
                if (getLogoRedPointMethod != null) {
                    hook(getLogoRedPointMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                if (!isLogoActionEnabled()) return result
                                val rp = result as? android.view.View ?: return result
                                if (rp.visibility != android.view.View.GONE) {
                                    rp.visibility = android.view.View.GONE
                                }
                                return result
                            }
                        })
                    logw(Log.INFO, "[OK] Hooked ${getLogoRedPointMethod.name}() for logo hide/replace")
                } else {
                    logw(Log.WARN, "getLogoRedPoint() not found by name or feature")
                }
            } catch (e: Throwable) {
                logw(Log.WARN, "getLogoRedPoint hook failed: ${e.message}")
            }

            // 兜底：Hook onAttachedToWindow - 键盘显示时确保替换 Logo
            // 处理 a2 在 hook 安装前已调用的情况（视图已缓存）
            try {
                val onAttachedMethod = candidateViewClass.getDeclaredMethod("onAttachedToWindow")
                onAttachedMethod.isAccessible = true
                hook(onAttachedMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            val view = chain.getThisObject() as? android.view.View ?: return result
                            val mode = getLogoMode()
                            if (mode == SymbolConfig.LOGO_SHOW) return result
                            logw(Log.INFO, "[Logo] onAttachedToWindow: mode=$mode")
                            view.postDelayed({
                                logw(Log.INFO, "[Logo] onAttachedToWindow postDelayed: applying logo action")
                                applyLogoAction(view)
                            }, 100)
                            view.postDelayed({ applyLogoAction(view) }, 500)
                            return result
                        }
                    })
                logw(Log.INFO, "[OK] Hooked ImeCandidateView.onAttachedToWindow for logo fallback")
            } catch (e: Throwable) {
                logw(Log.WARN, "onAttachedToWindow hook failed: ${e.message}")
            }

            // 兜底：Hook onWindowShown - 每次键盘显示时替换 Logo
            try {
                val onWindowShownMethod = candidateViewClass.declaredMethods.find { it.name == "onWindowShown" }
                if (onWindowShownMethod != null) {
                    onWindowShownMethod.isAccessible = true
                    hook(onWindowShownMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                val view = chain.getThisObject() as? android.view.View ?: return result
                                val mode = getLogoMode()
                                if (mode == SymbolConfig.LOGO_SHOW) return result
                                logw(Log.INFO, "[Logo] onWindowShown: mode=$mode")
                                view.postDelayed({
                                    logw(Log.INFO, "[Logo] onWindowShown postDelayed: applying logo action")
                                    applyLogoAction(view)
                                }, 100)
                                view.postDelayed({ applyLogoAction(view) }, 500)
                                view.postDelayed({ applyLogoAction(view) }, 2000)
                                return result
                            }
                        })
                    logw(Log.INFO, "[OK] Hooked ImeCandidateView.onWindowShown for logo fallback")
                } else {
                    logw(Log.WARN, "onWindowShown method not found on ImeCandidateView")
                }
            } catch (e: Throwable) {
                logw(Log.WARN, "onWindowShown hook failed: ${e.message}")
            }

        } catch (e: Throwable) {
            logw(Log.WARN, "Logo hook failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private fun findMethod(clazz: Class<*>, vararg names: String): Method? {
        for (name in names) {
            try {
                val m = clazz.getDeclaredMethod(name)
                m.isAccessible = true
                logw(Log.INFO, "[OK] Found method $name() on ${clazz.simpleName}")
                return m
            } catch (_: NoSuchMethodException) {}
        }
        return null
    }

    private fun findMethodWithStringParam(clazz: Class<*>, vararg names: String): Method? {
        for (name in names) {
            try {
                val m = clazz.getDeclaredMethod(name, String::class.java)
                m.isAccessible = true
                logw(Log.INFO, "[OK] Found method $name(String) on ${clazz.simpleName}")
                return m
            } catch (_: NoSuchMethodException) {}
        }
        return null
    }

    private fun findFloatTextField(clazz: Class<*>): Field? {
        // 先尝试已知字段名
        for (name in listOf("v", "floatText")) {
            try {
                val f = clazz.getDeclaredField(name)
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    logw(Log.INFO, "[OK] Found floatText field by name: $name")
                    return f
                }
            } catch (_: NoSuchFieldException) {}
        }
        // 按名称特征匹配
        for (f in clazz.declaredFields) {
            if (f.type == String::class.java) {
                val fieldName = f.name
                if (fieldName.contains("float", ignoreCase = true) ||
                    fieldName.contains("super", ignoreCase = true)) {
                    f.isAccessible = true
                    logw(Log.WARN, "[OK] Fallback floatText field: $fieldName")
                    return f
                }
            }
        }
        // 兜底：排除已知不是的字段名，取第一个 String 字段
        val excludedNames = setOf("p", "mainText")
        for (f in clazz.declaredFields) {
            if (f.type == String::class.java && f.name !in excludedNames) {
                f.isAccessible = true
                logw(Log.WARN, "[OK] Fallback floatText field (first String): ${f.name}")
                return f
            }
        }
        return null
    }

    companion object {
        private const val TAG = "WeTypeMarkChanger"
    }
}
