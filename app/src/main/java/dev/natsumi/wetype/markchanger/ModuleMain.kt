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

    // ---- 原始上滑符号缓存（用于仅同步模式查找功能码） ----
    private val originalFloatTextMap = HashMap<String, String>(52)

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
        val result = if (type == SymbolConfig.EN) {
            enSymbols[key] ?: cnSymbols[key]
        } else {
            cnSymbols[key]
        }
        return result
    }

    /** 获取当前键盘类型对应的自定义长按符号列表 */
    private fun getCustomLongPressSymbols(letter: String): List<String>? {
        val key = letter.lowercase()
        val type = currentKeyboardType
        return if (type == SymbolConfig.EN) {
            enLongSymbols[key] ?: cnLongSymbols[key]
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

        val buttonClass = try {
            Class.forName("com.tencent.wetype.plugin.hld.keyboard.selfdraw.j", true, classLoader)
        } catch (e: ClassNotFoundException) {
            logw(Log.ERROR, "CLASS NOT FOUND: com.tencent.wetype.plugin.hld.keyboard.selfdraw.j", e)
            return
        }
        logw(Log.INFO, "[OK] Button class (ImeButton): $buttonClass")

        getMainTextMethod = findMethod(buttonClass, "R", "getMainText")
            ?: run { logw(Log.ERROR, "Cannot find getMainText/R on button class"); return }

        setFloatTextMethod = findMethodWithStringParam(buttonClass, "H0", "setFloatText")
            ?: run { logw(Log.ERROR, "Cannot find H0/setFloatText on button class"); return }

        getFloatTextMethod = findMethod(buttonClass, "v", "getFloatText")
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
    // 键盘类型追踪 — hook n.f0()
    // ========================================================================

    private fun hookKeyboardTypeTracking(classLoader: ClassLoader) {
        try {
            val keyboardBaseClass = Class.forName(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.n", true, classLoader
            )
            val f0Method = findMethod(keyboardBaseClass, "f0", "initKeyboard")
                ?: run { logw(Log.WARN, "Cannot find f0 on keyboard base class"); return }

            hook(f0Method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
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
                            logw(Log.DEBUG, "Keyboard type: $currentKeyboardType ($typeName)")
                        } catch (e: Exception) {
                            logw(Log.WARN, "getKeyboardType failed: ${e.message}")
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
            val moreSymbolUtilClass = Class.forName(
                "com.tencent.wetype.plugin.hld.utils.j0", true, classLoader
            )
            val buttonClass = Class.forName(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.j", true, classLoader
            )

            // SymbolFloatData 运行时 obfuscated name: C
            val sfDataClass = Class.forName(
                "com.tencent.wetype.plugin.hld.floatview.C", true, classLoader
            )

            // text 字段
            val textField = sfDataClass.getDeclaredField("a").apply { isAccessible = true }

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
    // Logo 隐藏/替换 — hook ImeCandidateView.a2()
    // ========================================================================

    private fun hookLogoHide(classLoader: ClassLoader) {
        try {
            val candidateViewClass = Class.forName(
                "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", true, classLoader
            )
            // a2(boolean isDarkMode, boolean standaloneMode) — 设置 logo 图标
            val a2Method = candidateViewClass.declaredMethods.find { m ->
                m.name == "a2" && m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (a2Method == null) {
                logw(Log.WARN, "Cannot find a2(boolean,boolean) on ImeCandidateView")
                return
            }
            a2Method.isAccessible = true
            logw(Log.INFO, "[OK] Found a2(boolean,boolean) on ImeCandidateView")

            // 找到 logo_container_rl 字段
            val logoContainerField = candidateViewClass.declaredFields.find { f ->
                f.name == "logoContainerRl" || f.name == "logo_container_rl"
            } ?: run {
                // 遍历所有字段，找 RelativeLayout 类型
                candidateViewClass.declaredFields.firstOrNull { f ->
                    f.type.name == "android.widget.RelativeLayout"
                }
            }

            hook(a2Method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        val logoMode = remotePrefs?.getInt(SymbolConfig.KEY_LOGO_MODE, SymbolConfig.LOGO_SHOW)
                            ?: SymbolConfig.LOGO_SHOW
                        if (logoMode == SymbolConfig.LOGO_SHOW) return result

                        val view = chain.getThisObject() as? android.view.View ?: return result

                        // 通过反射找到 logo_container_rl
                        val logoContainer = try {
                            var field = logoContainerField
                            if (field == null) {
                                // 尝试通过 ID 找
                                val id = view.resources.getIdentifier("logo_container_rl", "id", view.context.packageName)
                                if (id != 0) view.findViewById<android.view.View>(id) else null
                            } else {
                                field.isAccessible = true
                                field.get(view) as? android.view.View
                            }
                        } catch (e: Exception) {
                            null
                        }

                        if (logoContainer != null) {
                            when (logoMode) {
                                SymbolConfig.LOGO_HIDE -> {
                                    logoContainer.visibility = android.view.View.GONE
                                    logw(Log.DEBUG, "[Logo] Hidden")
                                }
                                SymbolConfig.LOGO_REPLACE -> {
                                    logoContainer.visibility = android.view.View.GONE
                                    logw(Log.DEBUG, "[Logo] Hidden (replace mode)")
                                }
                            }
                        } else {
                            logw(Log.WARN, "[Logo] Cannot find logo_container_rl")
                        }
                        return result
                    }
                })
            logw(Log.INFO, "[OK] Hooked ImeCandidateView.a2() for logo hide/replace")
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
        for (name in listOf("v", "floatText")) {
            try {
                val f = clazz.getDeclaredField(name)
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    logw(Log.INFO, "[OK] Found floatText field: $name")
                    return f
                }
            } catch (_: NoSuchFieldException) {}
        }
        for (f in clazz.declaredFields) {
            if (f.type == String::class.java && f.name != "p" && f.name != "mainText") {
                val fieldName = f.name
                if (fieldName == "v" || fieldName == "floatText" ||
                    fieldName.contains("float", ignoreCase = true)) {
                    f.isAccessible = true
                    logw(Log.WARN, "[OK] Fallback floatText field: $fieldName")
                    return f
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "WeTypeMarkChanger"
    }
}
