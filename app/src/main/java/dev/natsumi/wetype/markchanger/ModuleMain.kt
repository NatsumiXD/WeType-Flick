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
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ModuleMain : XposedModule() {

    @Volatile
    private var remotePrefs: SharedPreferences? = null
    @Volatile private var localPrefs = PreferenceSnapshot(emptyMap<String, Any?>())
    private lateinit var discovery: HookDiscovery
    private var mainTextField: Field? = null
    private var buttonIdField: Field? = null
    private val buttonTypes = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val keyboardTypeMethods = ConcurrentHashMap<Class<*>, Method>()
    private val delayHandlers = Collections.synchronizedMap(WeakHashMap<Handler, Boolean>())
    private val delayHandlerFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val keyboardViews = Collections.synchronizedMap(WeakHashMap<android.view.View, Boolean>())
    private val candidateViews = Collections.synchronizedMap(WeakHashMap<android.view.View, Boolean>())
    private var updateKeyboardPosition: ((android.view.View, Boolean) -> Unit)? = null
    private var updateCandidateAppearance: ((Any) -> Unit)? = null
    private val refreshQueued = AtomicBoolean(false)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ConfigurationStore.REVISION || key == null) requestPreferenceRefresh()
    }

    private fun requestPreferenceRefresh() {
        val looper = android.os.Looper.getMainLooper() ?: return
        if (!refreshQueued.compareAndSet(false, true)) return
        Handler(looper).post {
            refreshQueued.set(false)
            refreshPreferences()
        }
    }

    private fun refreshPreferences() {
        localPrefs = PreferenceSnapshot(remotePrefs?.all ?: emptyMap<String, Any?>())
        loadAllSymbols()
        symbolCache.clear()
        mainTextCache.clear()
        val mainLooper = android.os.Looper.getMainLooper() ?: return
        Handler(mainLooper).post {
            updateHighlights()
            val redraw = synchronized(keyboardViews) { keyboardViews.keys.toMutableSet() }
            synchronized(originalButtonSymbols) {
                for ((button, original) in originalButtonSymbols) {
                    try {
                        val type = getKeyboardTypeFromButton(button)
                        val key = configKey(button, type)
                        floatTextField?.set(button, key?.let { getCustomSymbol(it, type) } ?: original)
                        (buttonKeyboardField?.get(button) as? android.view.View)?.let { redraw.add(it) }
                    } catch (failure: Throwable) { if (::discovery.isInitialized) invalidateOnce("Preference refresh", failure) }
                }
            }
            for (view in redraw) {
                try { updateKeyboardPosition?.invoke(view, false); view.invalidate() }
                catch (failure: Throwable) { invalidateOnce("Live layout refresh", failure) }
            }
            for (view in synchronized(candidateViews) { candidateViews.keys.toList() }) {
                try { updateCandidateAppearance?.invoke(view); view.invalidate() }
                catch (failure: Throwable) { invalidateOnce("Live Logo refresh", failure) }
            }
        }
    }


    private var getMainTextMethod: Method? = null
    private var setFloatTextMethod: Method? = null
    private var getFloatTextMethod: Method? = null
    private var floatTextField: Field? = null
    private var hookInstalled = false
    @Volatile private var swipeEnabled = true
    private var processName = ""
    @Volatile private var targetContext: Context? = null

    // ---- 内存符号表（无 IPC） ----
    @Volatile private var cnSymbols = HashMap<String, String>(52)
    @Volatile private var enSymbols = HashMap<String, String>(52)

    @Volatile private var t9Symbols = HashMap<String, String>()

    // ---- 长按弹窗符号表 ----
    @Volatile private var cnLongSymbols = HashMap<String, List<String>>(26)
    @Volatile private var enLongSymbols = HashMap<String, List<String>>(26)

    @Volatile private var t9LongSymbols = HashMap<String, List<String>>()

    // ---- 实例级缓存（避免反射） ----
    private val mainTextCache = Collections.synchronizedMap(WeakHashMap<Any, String>(64))
    private data class SymbolResult(val value: String?)
    private val symbolCache = Collections.synchronizedMap(WeakHashMap<Any, SymbolResult>(64))

    // ---- 按钮→键盘引用字段缓存 ----
    private var buttonKeyboardField: Field? = null

    // ---- 原始上滑符号缓存（用于仅同步模式查找功能码） ----
    private val originalButtonSymbols = Collections.synchronizedMap(WeakHashMap<Any, String>())

    // ---- 自定义 Logo Bitmap 缓存 ----
    @Volatile
    private var cachedLogoBitmap: android.graphics.Bitmap? = null
    private var cachedLogoImageSource: String? = null

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
            { context.filesDir }
        )
        for (dirSupplier in candidates) {
            try {
                val dir = dirSupplier.invoke()
                if (dir == null) continue
                val file = File(dir, "WeTypeMarkChanger.log")
                if (file.length() > 256 * 1024) file.writeText("")
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
        processName = param.getProcessName()
        logw(Log.INFO, "Module loaded in $processName")
        // Preserve the original module's working initialization order. A preference observer is
        // optional; a framework that does not support it must still get the initial snapshot.
        try {
            remotePrefs = getRemotePreferences(SymbolConfig.PREFS_NAME)
            refreshPreferences()
            try { remotePrefs?.registerOnSharedPreferenceChangeListener(preferenceListener) }
            catch (error: Throwable) { logw(Log.WARN, "Live preference listener unavailable; initial snapshot retained", error) }
        } catch (error: Throwable) { logw(Log.WARN, "Initial preferences unavailable", error) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.getPackageName() != "com.tencent.wetype" || hookInstalled) return
        val loader = param.getClassLoader()
        logw(Log.INFO, "onPackageReady: ${param.getPackageName()}, process=$processName")
        // Application.attach only attaches the file logger. Core hooks, like the original
        // module, are installed immediately when the framework supplies the final classloader.
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
            hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    (chain.getArg(0) as? Context)?.takeIf { it.packageName == "com.tencent.wetype" }?.let { context -> targetContext = context.applicationContext; initFileLogger(context) }
                    return result
                }
            })
        } catch (error: Throwable) { logw(Log.WARN, "File logger attachment unavailable; framework logging remains active", error) }
        try {
            discovery = HookDiscovery(loader) { logw(Log.INFO, it) }
            discovery.prepare(param.getApplicationInfo())
            installHooks(loader)
        } catch (error: Throwable) {
            if (::discovery.isInitialized) discovery.invalidate()
            logw(Log.ERROR, "Core hook installation failed", error)
        }
    }

    // ========================================================================
    // 符号表加载
    // ========================================================================

    private fun loadAllSymbols() {
        val cnSymbols = HashMap<String, String>(52)
        val enSymbols = HashMap<String, String>(52)
        val cnLongSymbols = HashMap<String, List<String>>(26)
        val enLongSymbols = HashMap<String, List<String>>(26)
        val t9Symbols = HashMap<String, String>()
        val t9LongSymbols = HashMap<String, List<String>>()
        val prefs = localPrefs
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
                                    SymbolConfig.T9 -> t9LongSymbols[letter] = symbols
                                }
                            }
                        }
                    }
                    key.startsWith("${SymbolConfig.CN}_") ->
                        cnSymbols[key.removePrefix("${SymbolConfig.CN}_")] = value
                    key.startsWith("${SymbolConfig.EN}_") ->
                        enSymbols[key.removePrefix("${SymbolConfig.EN}_")] = value
                    key.startsWith("${SymbolConfig.T9}_") ->
                        t9Symbols[key.removePrefix("${SymbolConfig.T9}_")] = value
                    else ->
                        cnSymbols[key] = value
                }
            }
        } catch (e: Exception) {
            logw(Log.ERROR, "loadAllSymbols failed: ${e.message}", e)
        }
        this.t9Symbols = t9Symbols
        this.t9LongSymbols = t9LongSymbols
        this.cnSymbols = cnSymbols
        this.enSymbols = enSymbols
        this.cnLongSymbols = cnLongSymbols
        this.enLongSymbols = enLongSymbols
        logw(Log.INFO, "Symbols loaded: cn=${cnSymbols.size}, en=${enSymbols.size}, cnLong=${cnLongSymbols.size}, enLong=${enLongSymbols.size}, t9=${t9Symbols.size}, t9Long=${t9LongSymbols.size}")
    }

    private fun symbolsFor(type: String): Map<String, String> = when (type) {
        SymbolConfig.EN -> enSymbols; SymbolConfig.T9 -> t9Symbols; else -> cnSymbols
    }
    private fun longSymbolsFor(type: String): Map<String, List<String>> = when (type) {
        SymbolConfig.EN -> enLongSymbols; SymbolConfig.T9 -> t9LongSymbols; else -> cnLongSymbols
    }
    private fun getCustomSymbol(key: String, type: String): String? = symbolsFor(type)[key]
    private fun configKey(button: Any, type: String): String? = KeyboardKeys.key(type, getMainTextCached(button),
        if (type == SymbolConfig.T9) buttonIdField?.get(button) as? String else null)

    /** 获取 button 实例的 mainText（带 WeakHashMap 缓存，避免反射） */
    private fun getMainTextCached(button: Any): String? =
        mainTextCache[button] ?: (mainTextField?.get(button) as? String)?.also { mainTextCache[button] = it }


    /**
     * 从按钮实例反向获取其所属键盘的 KeyboardType，
     * 按钮实例分别缓存，键盘类型不在不同布局间共享。
     */
    private fun getKeyboardTypeFromButton(button: Any): String {
        buttonTypes[button]?.let { return it }
        val keyboard = buttonKeyboardField?.get(button) ?: return SymbolConfig.CN
        (keyboard as? android.view.View)?.let { keyboardViews[it] = true }
        val clazz = keyboard.javaClass
        val getter = keyboardTypeMethods[clazz] ?: (discovery.getter(clazz, "keyboardType")
            ?: clazz.methods.singleOrNull { it.name == "getKeyboardType" && it.parameterCount == 0 })
            ?.apply { isAccessible = true }?.also { keyboardTypeMethods[clazz] = it }
        val name = getter?.invoke(keyboard)?.toString() ?: clazz.simpleName
        val type = when {
            name.contains("T9", ignoreCase = true) && !name.contains("Stroke", ignoreCase = true) &&
                !name.contains("Handwrite", ignoreCase = true) -> SymbolConfig.T9
            name.contains("English", ignoreCase = true) -> SymbolConfig.EN
            else -> SymbolConfig.CN
        }
        buttonTypes[button] = type
        val fields = delayHandlerFields.getOrPut(clazz) {
            generateSequence(clazz) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .filter { Handler::class.java.isAssignableFrom(it.type) && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .onEach { it.isAccessible = true }.toList()
        }
        fields.forEach { field -> (field.get(keyboard) as? Handler)?.let { delayHandlers[it] = true } }
        return type
    }


    // ========================================================================
    // Hook 安装
    // ========================================================================

    private fun installHooks(classLoader: ClassLoader) {
        val button = checkNotNull(discovery.clazz("button"))
        getMainTextMethod = checkNotNull(discovery.getter(button, "mainText"))
        mainTextField = checkNotNull(discovery.field(button, "mainText"))
        setFloatTextMethod = checkNotNull(discovery.setter(button, "floatText"))
        getFloatTextMethod = checkNotNull(discovery.getter(button, "floatText"))
        floatTextField = checkNotNull(discovery.field(button, "floatText"))
        buttonKeyboardField = checkNotNull(discovery.field(button, "parent"))
        buttonIdField = discovery.field(button, "id")
        val handles = ArrayList<HookHandle>()
        try {
            handles += hookSetFloatText()
            handles += hookGetFloatText()
            hookInstalled = true
        } catch (failure: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw failure
        }
        logw(Log.INFO, "Core swipe hooks installed: ${button.name}, setter=${setFloatTextMethod?.name}, getter=${getFloatTextMethod?.name}")
        fun optional(name: String, action: () -> Unit) {
            try { action() } catch (failure: Throwable) { invalidateOnce("Optional $name", failure) }
        }
        optional("main label invalidation") {
            discovery.setter(button, "mainText")?.let { setMain ->
                hook(setMain).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        mainTextCache.remove(chain.getThisObject())
                        symbolCache.remove(chain.getThisObject())
                        return result
                    }
                })
            }
        }
        optional("long press") { hookLongPressSymbols(classLoader) }
        optional("long press delay") { hookLongPressDelay() }
        optional("logo") { hookLogoHide(classLoader) }
        optional("language/number layout") { hookKeyLayoutSwap() }
        optional("native module settings entry") { hookNativeSettingsEntry() }
    }


    private fun hookSetFloatText(): HookHandle =
        hook(checkNotNull(setFloatTextMethod)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                if (!swipeEnabled) return result
                try {
                val button = chain.getThisObject()
                val type = getKeyboardTypeFromButton(button)
                val letter = configKey(button, type) ?: return result
                originalButtonSymbols[button] = chain.getArg(0) as? String ?: ""
                // Reused buttons/config changes must not keep an earlier custom value.
                symbolCache.remove(button)
                val custom = getCustomSymbol(letter, type)
                if (custom != null) {
                    symbolCache[button] = SymbolResult(custom)
                    floatTextField?.set(button, custom)
                }
                } catch (failure: Throwable) { invalidateOnce("Swipe setter", failure) }
                return result
            }
        })


    // ========================================================================
    // v() (getFloatText) hook — 拦截符号读取（热路径，只用缓存）
    // ========================================================================


    private fun hookGetFloatText(): HookHandle =
        hook(checkNotNull(getFloatTextMethod)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!swipeEnabled) return chain.proceed()
                val replacement = try {
                    val button = chain.getThisObject()
                    val cached = symbolCache[button]
                    if (cached != null) cached.value else {
                        val type = getKeyboardTypeFromButton(button)
                        val key = configKey(button, type)
                        val custom = key?.let { getCustomSymbol(it, type) }
                        if (key != null && !originalButtonSymbols.containsKey(button))
                            originalButtonSymbols[button] = floatTextField?.get(button) as? String ?: ""
                        symbolCache[button] = SymbolResult(custom)
                        custom
                    }
                } catch (failure: Throwable) { invalidateOnce("Swipe getter", failure); null }
                return replacement ?: chain.proceed()
            }
        })


    // ========================================================================
    // C2410j0.k()/l() hook — 拦截长按弹窗符号列表
    // ========================================================================

    private var highLightSet: java.util.HashSet<String>? = null
    private val injectedHighlights = HashSet<String>()

    private fun updateHighlights() {
        val target = highLightSet ?: return
        target.removeAll(injectedHighlights)
        injectedHighlights.clear()
        val custom = cnSymbols.values.asSequence() + enSymbols.values.asSequence() +
            cnLongSymbols.values.asSequence().flatten() + enLongSymbols.values.asSequence().flatten() +
            t9Symbols.values.asSequence() + t9LongSymbols.values.asSequence().flatten()
        custom.forEach { if (target.add(it)) injectedHighlights.add(it) }
    }

    private fun hookLongPressSymbols(classLoader: ClassLoader) {
        try {
            val utility = discovery.clazz("symbols") ?: return
            val button = discovery.clazz("button") ?: return
            val data = discovery.clazz("data") ?: return
            val textField = discovery.field(data, "text") ?: return
            val create = SymbolDataFactory.create(data, discovery.dataParameters()) ?: return
            val instance = utility.declaredFields.singleOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == utility }
                ?.apply { isAccessible = true }?.get(null)
            @Suppress("UNCHECKED_CAST")
            highLightSet = discovery.getter(utility, "highLightSymbol")?.invoke(instance) as? java.util.HashSet<String>
            updateHighlights()
            val methods = utility.declaredMethods.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(button)) &&
                    it.returnType == List::class.java
            }
            for (method in methods) {
                method.isAccessible = true
                hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val original = chain.proceed() as? List<*> ?: return null
                        if (original.any { !data.isInstance(it) }) return original
                        return try { modifyLongPressList(chain.getArg(0), original, textField, create) }
                        catch (failure: Throwable) { invalidateOnce("Long press result", failure); original }
                    }
                })
            }
            logw(Log.INFO, "Long press: ${methods.size} validated methods")
        } catch (error: Throwable) { invalidateOnce("Long press installation", error) }
    }

    private fun modifyLongPressList(button: Any, originalList: List<*>, textField: Field,
                                    item: (String) -> Any): List<*> {
        val type = getKeyboardTypeFromButton(button)
        val letter = configKey(button, type) ?: return originalList
        if (localPrefs.getBoolean(SymbolConfig.KEY_SYNC_SWIPE_ONLY, false)) {
            val swipe = symbolsFor(type)[letter] ?: return originalList
            val original = originalButtonSymbols[button] ?: return originalList
            if (swipe == original) return originalList
            val index = originalList.indexOfFirst { it != null && textField.get(it) == original }
            if (index < 0) return originalList
            return originalList.toMutableList().apply { this[index] = item(swipe) }
        }
        val custom = longSymbolsFor(type)[letter] ?: return originalList
        val result = ArrayList<Any>(custom.size)
        for (symbol in custom) result.add(item(symbol))
        return result
    }

    private fun hookNativeSettingsEntry() {
        val provider = checkNotNull(discovery.clazz("settingProvider")) { "Settings provider unavailable" }
        val config = checkNotNull(discovery.clazz("settingConfig")) { "Settings config unavailable" }
        val model = checkNotNull(discovery.clazz("settingModel")) { "Native settings row unavailable" }
        val listMethod = checkNotNull(discovery.settingsList(provider)) { "Settings list method unavailable" }
        val parameters = discovery.settingParameters()
        val fields = parameters.associateWith { checkNotNull(discovery.field(config, it)) }
        val create = checkNotNull(SettingEntryFactory.create(config, parameters, fields))
        val id = fields.getValue("id")
        val getView = checkNotNull(discovery.getter(model, "itemView"))
        val getConfig = checkNotNull(discovery.getter(model, "config"))
        val title = checkNotNull(discovery.field(model, "title"))
        val bind = model.declaredMethods.single { it.parameterCount == 0 && it.returnType == Void.TYPE &&
            !java.lang.reflect.Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
        // Native rows reserve child IDs 1..5; the row root must not shadow their lookups.
        var entryId = android.view.View.generateViewId()
        while (entryId <= 5) entryId = android.view.View.generateViewId()
        fun open(context: Context) {
            try {
                context.startActivity(android.content.Intent().setClassName(SymbolConfig.MODULE_PACKAGE,
                    SymbolConfig.MODULE_PACKAGE + ".MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (failure: Throwable) {
                logw(Log.WARN, "Cannot open module settings", failure)
                android.widget.Toast.makeText(context, "无法打开模块设置", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Bind the extra row through the original row class, retaining native theme/press behavior.
        val bindHandle = hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                try {
                    val owner = chain.getThisObject()
                    if (id.getInt(getConfig.invoke(owner)) == entryId) {
                        val view = getView.invoke(owner) as android.view.View
                        (title.get(owner) as android.widget.TextView).text = "模块设置"
                        view.contentDescription = "WeType Flick 模块设置"
                        // Native TouchDown dispatch only knows original IDs. View pressed states still work.
                        view.setOnTouchListener(null)
                        view.setOnClickListener { open(it.context) }
                    }
                } catch (failure: Throwable) { invalidateOnce("Native settings row bind", failure) }
                return result
            }
        })
        try {
            hook(listMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val original = chain.proceed() as? List<*> ?: return null
                    return try {
                        if (original.isEmpty() || original.any { !config.isInstance(it) } ||
                            original.any { id.getInt(it) == entryId }) original
                        else {
                            val resources = targetContext?.resources
                            val template = original.firstOrNull { item ->
                                val name = runCatching { resources?.getResourceEntryName(id.getInt(item)) }.getOrNull().orEmpty()
                                name.contains("setting", true) || name.contains("more", true)
                            } ?: original.last()!!
                            original.toMutableList().apply { add(create(template, entryId)) }
                        }
                    } catch (failure: Throwable) { invalidateOnce("Native settings list", failure); original }
                }
            })
        } catch (failure: Throwable) { bindHandle.unhook(); throw failure }
        logw(Log.INFO, "Native module settings entry installed: ${provider.name}.${listMethod.name}")
    }

    private fun hookKeyLayoutSwap() {
        val keyboard = discovery.clazz("keyboard") ?: return
        val button = discovery.clazz("button") ?: return
        val updates = discovery.layoutUpdates(keyboard)
        check(updates.isNotEmpty()) { "No keyboard layout update methods" }
        val buttons = discovery.field(keyboard, "imeButtonMap") ?: return
        val geometry = ButtonGeometry(KeyLayoutSwap.GEOMETRY_PROPERTIES.associateWith { name ->
            checkNotNull(discovery.field(button, name)) { "Missing finalized geometry: $name" }
        })
        val swap = KeyLayoutSwap(geometry::read, geometry::write)
        val gate = LayoutUpdateGate()
        fun restore(view: android.view.View) {
            val pair = (buttons.get(view) as? Map<*, *>)?.let(KeyboardSwitchPair::find) ?: return
            swap.apply(pair.first, pair.second, false)
        }
        updateKeyboardPosition = { view, rebuilt ->
            val map = buttons.get(view) as? Map<*, *>
            val pair = map?.let(KeyboardSwitchPair::find)
            if (pair != null) swap.apply(pair.first, pair.second,
                localPrefs.getBoolean(SymbolConfig.KEY_SWAP_LANGUAGE_NUMBER, false), rebuilt)
        }
        val installed = ArrayList<HookHandle>()
        try {
            for (update in updates) installed += hook(update).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val view = chain.getThisObject() as? android.view.View ?: return chain.proceed()
                    keyboardViews[view] = true
                    return gate.run(view,
                        before = { try { restore(view) } catch (failure: Throwable) { invalidateOnce("Layout restore", failure) } },
                        operation = { chain.proceed() },
                        after = { try { updateKeyboardPosition?.invoke(view, true) }
                            catch (failure: Throwable) { invalidateOnce("Finalized key layout", failure) } })
                }
            })
        } catch (failure: Throwable) { installed.asReversed().forEach { it.unhook() }; throw failure }
        logw(Log.INFO, "Complete and partial key layout updates installed: ${updates.joinToString { it.name }}")
    }


    private fun hookLongPressDelay() {
        val inLongPressDelayHook = object : ThreadLocal<Boolean>() {
            override fun initialValue() = false
        }
        try {
            hook(Handler::class.java.getMethod("sendMessageDelayed", Message::class.java, Long::class.javaPrimitiveType!!))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val customDelay = localPrefs.getLong(SymbolConfig.KEY_LONG_PRESS_DELAY, SymbolConfig.DEFAULT_LONG_PRESS_DELAY)
                        if (customDelay == SymbolConfig.DEFAULT_LONG_PRESS_DELAY || inLongPressDelayHook.get() == true) return chain.proceed()
                        val owner = chain.getThisObject() as? Handler ?: return chain.proceed()
                        if (!delayHandlers.containsKey(owner)) return chain.proceed()
                        val msg = chain.getArg(0) as? Message ?: return chain.proceed()
                        val originalDelay = chain.getArg(1) as? Long ?: return chain.proceed()
                        if (msg.what == 1 && originalDelay == SymbolConfig.DEFAULT_LONG_PRESS_DELAY) {
                            if (customDelay != SymbolConfig.DEFAULT_LONG_PRESS_DELAY) {
                                val handler = chain.getThisObject() as? Handler ?: return chain.proceed()
                                inLongPressDelayHook.set(true)
                                try {
                                        handler.sendMessageDelayed(msg, customDelay.coerceIn(100L, 1000L))
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
        val source = localPrefs.getString(SymbolConfig.KEY_LOGO_IMAGE, null)
        if (source === cachedLogoImageSource) return cachedLogoBitmap
        cachedLogoImageSource = source
        cachedLogoBitmap = null
        if (source == null) return null
        return try {
            val bytes = android.util.Base64.decode(source, android.util.Base64.DEFAULT)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }).also { cachedLogoBitmap = it }
        } catch (error: Exception) { invalidateOnce("Logo decoding", error); null }
    }

    private fun hookLogoHide(classLoader: ClassLoader) {
        try {
            val candidate = discovery.clazz("candidate") ?: return
            val logoGetter = discovery.getter(candidate, "logoIv") ?: return
            val badgeGetter = discovery.getter(candidate, "logoRedPoint")
            val appearance = LogoAppearance()
            fun apply(view: Any) {
                (view as? android.view.View)?.let { candidateViews[it] = true }
                val mode = localPrefs.getInt(SymbolConfig.KEY_LOGO_MODE, SymbolConfig.LOGO_SHOW)
                val logo = logoGetter.invoke(view) as? android.widget.ImageView ?: return
                appearance.apply(logo, badgeGetter?.invoke(view) as? android.view.View, mode,
                    if (mode == SymbolConfig.LOGO_REPLACE) getCachedLogoBitmap() else null)
            }
            updateCandidateAppearance = ::apply
            val lifecycle = candidate.declaredMethods.filter { it.name in setOf("onAttachedToWindow", "onWindowShown") && it.parameterCount == 0 }
            val updates = discovery.logoUpdates(candidate)
            for (method in (lifecycle + updates).distinct()) {
                method.isAccessible = true
                hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        try { apply(chain.getThisObject()) }
                        catch (error: Throwable) { invalidateOnce("Logo update", error) }
                        return result
                    }
                })
            }
            logw(Log.INFO, "Logo: ${updates.size} semantic methods + ${lifecycle.size} lifecycle methods")
        } catch (error: Throwable) { invalidateOnce("Logo installation", error) }
    }

    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()
    private fun invalidateOnce(feature: String, failure: Throwable) {
        if (reportedFailures.add(feature)) {
            if (feature.startsWith("Swipe")) swipeEnabled = false
            discovery.invalidate()
            logw(Log.WARN, "$feature failed; hook cache will be rediscovered on next launch", failure)
        }
    }

    companion object {
        private const val TAG = "WeTypeMarkChanger"
    }
}


/** Volatile snapshots isolate high-frequency hooks from Xposed preference IPC. */
private class PreferenceSnapshot(val all: Map<String, *>) {
    fun getLong(key: String, default: Long) = (all[key] as? Long) ?: default
    fun getInt(key: String, default: Int) = (all[key] as? Int) ?: default
    fun getBoolean(key: String, default: Boolean) = (all[key] as? Boolean) ?: default
    fun getString(key: String, default: String?) = (all[key] as? String) ?: default
}
