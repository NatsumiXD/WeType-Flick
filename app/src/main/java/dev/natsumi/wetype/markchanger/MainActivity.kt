package dev.natsumi.wetype.markchanger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.io.InputStreamReader
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    companion object {
        private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val KEYBOARD_LABELS = listOf("中文 26 键", "英文 26 键", "中文九键")
        private val KEYBOARD_TYPES = listOf(SymbolConfig.CN, SymbolConfig.EN, SymbolConfig.T9)
        private val LONG_PRESS_KEY = "longPress"

        private val NAV_ITEMS = listOf(
            Triple("符号配置", Icons.Filled.Star, "自定义上滑和长按符号"),
            Triple("数据管理", Icons.Filled.Build, "导入导出与云端同步"),
            Triple("关于", Icons.Filled.Info, "模块信息")
        )
    }

    private var pendingExportJson: String? = null
    private val logoImageVersion = mutableStateOf(0)
    private val configurationVersion = mutableIntStateOf(0)
    private val draftListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { configurationVersion.intValue++ }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val json = pendingExportJson ?: return@let
            contentResolver.openOutputStream(it)?.use { os ->
                os.write(json.toByteArray())
            }
            pendingExportJson = null
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val json = contentResolver.openInputStream(it)?.use { isr ->
                    InputStreamReader(isr, Charsets.UTF_8).readText()
                } ?: return@let
                val count = SymbolConfig.importFromJson(this, json).getOrThrow()
                Toast.makeText(this, "已导入草稿，共 $count 条符号，请点击保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val logoImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { processLogoImage(it) }
    }

    private fun processLogoImage(uri: android.net.Uri) {
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            } ?: run {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
                return
            }

            // 缩放至最大 256x256，保持宽高比
            val maxDim = 256
            val w = bitmap.width
            val h = bitmap.height
            val scaled = if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true
                ).also { if (it != bitmap) bitmap.recycle() }
            } else {
                bitmap
            }

            // 压缩为 PNG 并 Base64 编码
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            scaled.recycle()
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)

            // 检查大小限制（~200KB Base64，约 150KB 图片）
            if (base64.length > 300_000) {
                Toast.makeText(this, "图片过大，请选择更小的图标", Toast.LENGTH_SHORT).show()
                return
            }

            SymbolConfig.setLogoImage(this, base64)
            logoImageVersion.value++
            Toast.makeText(this, "Logo 图标已暂存，点击保存后生效", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "处理图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SymbolConfig.initialize(this)
        SymbolConfig.getPrefs(this).registerOnSharedPreferenceChangeListener(draftListener)

        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalAppleMotion provides rememberMotionEnabled()) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        SymbolConfig.getPrefs(this).unregisterOnSharedPreferenceChangeListener(draftListener)
        super.onDestroy()
    }

    // ========================================================================
    // 主屏幕 — Scaffold + NavigationBar
    // ========================================================================

    @Composable
    private fun MainScreen() {
        val configVersion = configurationVersion.intValue
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        val pending = remember(configVersion, saving) { SymbolConfig.hasUnsavedChanges(this@MainActivity) }
        var selectedNav by rememberSaveable { mutableIntStateOf(0) }
        val pageState = rememberSaveableStateHolder()
        val motion = LocalAppleMotion.current

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                SmallTopAppBar(title = "微信输入法上滑符号")
            },
            bottomBar = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (pending) "有未保存修改" else "已持久化保存", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        ActionButton(if (saving) "正在保存…" else "保存并热重载", MiuixTheme.colorScheme.primary,
                            MiuixTheme.colorScheme.onPrimary) {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { SymbolConfig.saveAndReload(this@MainActivity) }
                                    saving = false
                                    configurationVersion.intValue++
                                    val message = result.fold(
                                        onSuccess = { published -> if (published) "已持久化保存并热重载" else "已本地保存，框架连接后应用" },
                                        onFailure = { "保存失败：${it.message}" })
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    NavigationBar {
                        NAV_ITEMS.forEachIndexed { index, (label, icon, _) ->
                            NavigationBarItem(selected = selectedNav == index, onClick = { selectedNav = index }, icon = icon, label = label)
                        }
                    }
                }
            }
        ) { paddingValues ->
            AnimatedContent(
                targetState = selectedNav,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(tween(if (motion) 180 else 0)).togetherWith(fadeOut(tween(if (motion) 120 else 0)))
                },
                label = "page"
            ) { page ->
                pageState.SaveableStateProvider(page) {
                    when (page) {
                        0 -> SymbolsPage(paddingValues)
                        1 -> DataManagePage(paddingValues)
                        2 -> AboutPage(paddingValues)
                    }
                }
            }
        }
    }

    // ========================================================================
    // 页面 0：符号配置
    // ========================================================================

    @Composable
    private fun SymbolsPage(padding: PaddingValues) {
        val context = LocalContext.current
        val configVersion = configurationVersion.intValue
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var selectedMode by rememberSaveable { mutableIntStateOf(0) }
        var syncSwipeOnly by remember(configVersion) { mutableStateOf(SymbolConfig.isSyncSwipeOnly(context)) }
        var longPressDelay by remember(configVersion) { mutableLongStateOf(SymbolConfig.getLongPressDelay(context)) }
        val keyboardType = KEYBOARD_TYPES[selectedTab]
        val defaults = SymbolConfig.getDefaultSymbols(keyboardType)
        val longPressDefaults = SymbolConfig.getDefaultLongPressSymbols(keyboardType)

        val symbols = remember { mutableStateMapOf<String, String>() }
        val longPressSymbols = remember { mutableStateMapOf<String, String>() }
        var editingKey by remember { mutableStateOf<String?>(null) }
        var editingValue by remember { mutableStateOf("") }
        var editingMode by remember { mutableIntStateOf(0) }

        LaunchedEffect(selectedTab, selectedMode, syncSwipeOnly, configVersion) {
            symbols.clear()
            longPressSymbols.clear()
            SymbolConfig.getAll(context, keyboardType).forEach { (k, v) -> symbols[k] = v }
            SymbolConfig.getAllLongPress(context, keyboardType).forEach { (k, v) -> longPressSymbols[k] = v }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // 框架状态
                item {
                    SmallTitle(text = "框架状态")
                    Card {
                        val service = XposedServiceManager.service
                        if (service != null) {
                            BasicComponent(title = "框架名称", summary = service.getFrameworkName())
                            BasicComponent(title = "框架版本", summary = "${service.getFrameworkVersion()} (${service.getFrameworkVersionCode()})")
                            BasicComponent(title = "API 版本", summary = service.getApiVersion().toString())
                            BasicComponent(title = "作用域", summary = service.getScope().joinToString(", "))
                        } else {
                            BasicComponent(title = "未连接", summary = "请确保 LSPosed 已启用本模块")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    SmallTitle(text = "输入法测试")
                    Card {
                        var testText by rememberSaveable { mutableStateOf("") }
                        val focus = remember { FocusRequester() }
                        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasicTextField(
                                value = testText,
                                onValueChange = { testText = it },
                                modifier = Modifier.fillMaxWidth().height(88.dp).focusRequester(focus)
                                    .semantics { contentDescription = "输入法测试输入框" },
                                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                decorationBox = { field ->
                                    Box {
                                        if (testText.isEmpty()) Text("点此输入，测试上滑、长按和按键布局", fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                        field()
                                    }
                                }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("唤起输入法", MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.onPrimary) {
                                    focus.requestFocus()
                                    keyboard?.show()
                                }
                                ActionButton("清空", Color.Transparent, MiuixTheme.colorScheme.onSurface) { testText = "" }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 键盘切换
                item {
                    TabRow(
                        tabs = KEYBOARD_LABELS,
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 仅同步上滑模式开关
                item {
                    Card {
                        SwitchPreference(
                            title = "仅同步上滑符号",
                            summary = if (syncSwipeOnly) "已开启 · 长按沿用上滑符号" else "关闭 · 单独设置长按符号",
                            checked = syncSwipeOnly,
                            onCheckedChange = { checked ->
                                syncSwipeOnly = checked
                                SymbolConfig.setSyncSwipeOnly(context, syncSwipeOnly)
                                if (syncSwipeOnly) selectedMode = 0
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 符号模式切换
                if (!syncSwipeOnly) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val modeLabels = listOf("上滑符号", "长按符号")
                            modeLabels.forEachIndexed { index, label ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val isPressed by interactionSource.collectIsPressedAsState()
                                val scale = applePressScale(isPressed)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer { scaleX = scale; scaleY = scale }
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selectedMode == index) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surface
                                        )
                                        .clickable(interactionSource = interactionSource, indication = null) { selectedMode = index }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selectedMode == index) MiuixTheme.colorScheme.onPrimary
                                        else MiuixTheme.colorScheme.onSurface,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 符号键盘
                item {
                    if (selectedMode == 0) {
                        SmallTitle(text = "上滑符号自定义 · 确认后暂存，保存后应用")
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyboardPreview(
                            keyboardType = keyboardType,
                            symbols = symbols,
                            defaults = defaults,
                            onKeyClick = { key ->
                                editingKey = key
                                editingValue = symbols[key] ?: ""
                                editingMode = 0
                            }
                        )
                    } else {
                        SmallTitle(text = "长按符号自定义 · 确认后暂存，保存后应用")
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyboardPreview(
                            keyboardType = keyboardType,
                            symbols = longPressSymbols,
                            defaults = longPressDefaults,
                            onKeyClick = { key ->
                                editingKey = key
                                editingValue = longPressSymbols[key] ?: longPressDefaults[key] ?: ""
                                editingMode = 1
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "逗号分隔多个符号，如: A,a,ā,á,ǎ,à",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 26 键预设不改动九键配置。
                if (keyboardType != SymbolConfig.T9) item {
                    SmallTitle(text = "预设方案")
                    Spacer(modifier = Modifier.height(8.dp))
                    var showPresetDropdown by remember { mutableStateOf(false) }
                    var selectedPresetName by remember { mutableStateOf<String?>(null) }
                    Card {
                        BasicComponent(
                            title = "选择预设方案",
                            summary = selectedPresetName ?: "点击展开选择",
                            onClick = { showPresetDropdown = true }
                        )
                    }
                    if (showPresetDropdown) {
                        val presetDescs = listOf(
                            "上滑: 1-0, 标点符号",
                            "上滑: 1-0, 常用标点",
                            "上滑: 1-0, 简约符号"
                        )
                        Dialog(onDismissRequest = { showPresetDropdown = false }) {
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "选择预设方案",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    SymbolConfig.PRESETS.forEachIndexed { index, preset ->
                                        Box {
                                            BasicComponent(
                                                title = preset.name,
                                                summary = presetDescs.getOrElse(index) { "" },
                                                onClick = {
                                                    selectedPresetName = preset.name
                                                    showPresetDropdown = false
                                                    SymbolConfig.applyPreset(context, preset)
                                                    symbols.clear()
                                                    longPressSymbols.clear()
                                                    SymbolConfig.getAll(context, keyboardType).forEach { (k, v) -> symbols[k] = v }
                                                    SymbolConfig.getAllLongPress(context, keyboardType).forEach { (k, v) -> longPressSymbols[k] = v }
                                                    Toast.makeText(context, "已暂存「${preset.name}」预设，请点击保存", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 长按延迟
                item {
                    SmallTitle(text = "长按延迟")
                    Spacer(modifier = Modifier.height(8.dp))
                    Card {
                        var sliderValue by remember(configVersion) { mutableStateOf(longPressDelay.toFloat()) }
                        var inputText by remember(configVersion) { mutableStateOf(longPressDelay.toString()) }

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${sliderValue.toInt()} ms",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
                                )
                                if (longPressDelay != SymbolConfig.DEFAULT_LONG_PRESS_DELAY) {
                                    Text(
                                        text = "恢复默认",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            longPressDelay = SymbolConfig.DEFAULT_LONG_PRESS_DELAY
                                            sliderValue = SymbolConfig.DEFAULT_LONG_PRESS_DELAY.toFloat()
                                            inputText = longPressDelay.toString()
                                            SymbolConfig.setLongPressDelay(context, longPressDelay)
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Slider(
                                value = sliderValue,
                                onValueChange = { value ->
                                    val snapped = ((value / 50f).roundToLong() * 50L).coerceIn(100L, 1000L)
                                    sliderValue = snapped.toFloat()
                                    longPressDelay = snapped
                                    inputText = snapped.toString()
                                },
                                valueRange = 100f..1000f,
                                steps = 17,
                                onValueChangeFinished = { SymbolConfig.setLongPressDelay(context, longPressDelay) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "100ms", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text(text = "1000ms", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            // 快捷预设
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(200L, 300L, 500L, 700L).forEach { preset ->
                                    val isSelected = sliderValue.toLong() == preset
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MiuixTheme.colorScheme.surface
                                            )
                                            .border(
                                                width = 0.5.dp,
                                                color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
                                                else MiuixTheme.colorScheme.outline.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                sliderValue = preset.toFloat()
                                                longPressDelay = preset
                                                inputText = preset.toString()
                                                SymbolConfig.setLongPressDelay(context, longPressDelay)
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${preset}ms",
                                            fontSize = 12.sp,
                                            color = if (isSelected) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            // 精确输入
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = "自定义", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { v ->
                                        inputText = v.filter { it.isDigit() }
                                        val parsed = inputText.toLongOrNull()
                                        if (parsed != null && parsed in 100..1000) {
                                            longPressDelay = parsed
                                            sliderValue = parsed.toFloat()
                                            SymbolConfig.setLongPressDelay(context, longPressDelay)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                        .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    singleLine = true,
                                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                                    decorationBox = { inner ->
                                        Box {
                                            if (inputText.isEmpty()) {
                                                Text(text = "100-1000", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f), fontSize = 14.sp)
                                            }
                                            inner()
                                        }
                                    }
                                )
                                Text(text = "ms", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = "按键布局")
                    Card {
                        var swapKeys by remember(configVersion) { mutableStateOf(SymbolConfig.isSwapLanguageNumber(context)) }
                        SwitchPreference(
                            title = "互换中英与 123",
                            summary = "应用于所有包含数字与中英切换键的布局，包括英文、五笔、笔画与手写，点击保存后热重载",
                            checked = swapKeys,
                            onCheckedChange = { checked ->
                                swapKeys = checked
                                SymbolConfig.setSwapLanguageNumber(context, checked)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Logo 显示/隐藏/替换
                item {
                    SmallTitle(text = "左上角 Logo")
                    Spacer(modifier = Modifier.height(8.dp))
                    Card {
                        val currentLogoMode = remember(configVersion) { mutableIntStateOf(SymbolConfig.getLogoMode(this@MainActivity)) }
                        var replaceLogo by remember(configVersion) { mutableStateOf(currentLogoMode.intValue == SymbolConfig.LOGO_REPLACE) }
                        val logoVer = this@MainActivity.logoImageVersion.value
                        val hasLogoImage = remember(logoVer, configVersion) { mutableStateOf(SymbolConfig.getLogoImage(this@MainActivity) != null) }
                        Column {
                            SwitchPreference(
                                title = "显示 Logo",
                                summary = "关闭后只隐藏图案，按钮仍可点击",
                                checked = currentLogoMode.intValue != SymbolConfig.LOGO_HIDE,
                                onCheckedChange = { checked ->
                                    currentLogoMode.intValue = if (!checked) SymbolConfig.LOGO_HIDE
                                        else if (replaceLogo) SymbolConfig.LOGO_REPLACE else SymbolConfig.LOGO_SHOW
                                    SymbolConfig.setLogoMode(context, currentLogoMode.intValue)
                                }
                            )
                            if (currentLogoMode.intValue != SymbolConfig.LOGO_HIDE) SwitchPreference(
                                title = "使用自定义图标",
                                summary = "替换左上角 Logo 图案",
                                checked = replaceLogo,
                                onCheckedChange = { checked ->
                                    replaceLogo = checked
                                    currentLogoMode.intValue = if (checked) SymbolConfig.LOGO_REPLACE else SymbolConfig.LOGO_SHOW
                                    SymbolConfig.setLogoMode(context, currentLogoMode.intValue)
                                }
                            )
                        }
                        // 替换模式：显示图标选择/预览/清除
                        if (currentLogoMode.intValue == SymbolConfig.LOGO_REPLACE) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 预览
                                val logoBase64 = SymbolConfig.getLogoImage(this@MainActivity)
                                if (logoBase64 != null) {
                                    val previewBitmap = remember(logoBase64, logoVer) {
                                        try {
                                            val bytes = android.util.Base64.decode(logoBase64, android.util.Base64.DEFAULT)
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        } catch (_: Exception) { null }
                                    }
                                    previewBitmap?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = "Logo 预览",
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    }
                                }
                                ActionButton(
                                    text = if (hasLogoImage.value) "更换图标" else "选择图标",
                                    bgColor = MiuixTheme.colorScheme.primary,
                                    textColor = MiuixTheme.colorScheme.onPrimary,
                                    onClick = {
                                        logoImageLauncher.launch(arrayOf("image/*"))
                                    }
                                )
                                if (hasLogoImage.value) {
                                    ActionButton(
                                        text = "清除",
                                        bgColor = MiuixTheme.colorScheme.surface,
                                        textColor = MiuixTheme.colorScheme.onSurface,
                                        onClick = {
                                            SymbolConfig.removeLogoImage(this@MainActivity)
                                            hasLogoImage.value = false
                                            this@MainActivity.logoImageVersion.value++
                                            Toast.makeText(this@MainActivity, "已清除自定义图标", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                            Text(
                                text = "建议使用正方形透明 PNG 图标，点击保存后生效",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 编辑和恢复只更改草稿；底部保存按钮统一提交并热重载。
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("恢复默认", Color.Transparent, MiuixTheme.colorScheme.onSurface) {
                        if (selectedMode == 0) {
                            SymbolConfig.resetKeyboard(context, keyboardType)
                            symbols.clear()
                        } else {
                            SymbolConfig.resetLongPressKeyboard(context, keyboardType)
                            longPressSymbols.clear()
                        }
                        Toast.makeText(context, "已恢复为默认草稿，请点击保存", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 编辑弹窗
        editingKey?.let { key ->
            SymbolEditDialog(
                letter = key,
                value = editingValue,
                defaultSymbol = if (editingMode == 0) defaults[key] ?: ""
                else longPressDefaults[key] ?: "",
                isLongPress = editingMode == 1,
                onValueChange = { editingValue = it },
                onConfirm = {
                    if (editingMode == 0) {
                        symbols[key] = editingValue
                        if (editingValue.isNotEmpty()) SymbolConfig.setSymbol(context, keyboardType, key, editingValue)
                        else SymbolConfig.removeSymbol(context, keyboardType, key)
                    } else {
                        longPressSymbols[key] = editingValue
                        if (editingValue.isNotEmpty()) SymbolConfig.setLongPressSymbols(context, keyboardType, key, editingValue)
                        else SymbolConfig.removeLongPressSymbols(context, keyboardType, key)
                    }
                    editingKey = null
                },
                onClear = {
                    if (editingMode == 0) {
                        symbols.remove(key)
                        SymbolConfig.removeSymbol(context, keyboardType, key)
                    } else {
                        longPressSymbols.remove(key)
                        SymbolConfig.removeLongPressSymbols(context, keyboardType, key)
                    }
                    editingValue = ""
                    editingKey = null
                },
                onDismiss = { editingKey = null }
            )
        }
    }

    // ========================================================================
    // 页面 1：数据管理
    // ========================================================================

    @Composable
    private fun DataManagePage(padding: PaddingValues) {
        val context = LocalContext.current
        var showWebdavDialog by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // JSON 导入导出
            item {
                SmallTitle(text = "JSON 导入导出")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("导出 JSON", MiuixTheme.colorScheme.surface, MiuixTheme.colorScheme.onSurface) {
                        val json = SymbolConfig.exportToJson(context)
                        pendingExportJson = json
                        exportLauncher.launch("wetype-symbols.json")
                    }
                    ActionButton("导入 JSON", MiuixTheme.colorScheme.surface, MiuixTheme.colorScheme.onSurface) {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // WebDAV 同步
            item {
                SmallTitle(text = "WebDAV 同步")
                Spacer(modifier = Modifier.height(8.dp))
                val config = remember { mutableStateOf(WebDavClient.loadConfig(context)) }
                Card {
                    BasicComponent(
                        title = "服务器地址",
                        summary = config.value.url.ifEmpty { "未配置" }
                    )
                    BasicComponent(
                        title = "用户名",
                        summary = config.value.username.ifEmpty { "未配置" }
                    )
                    BasicComponent(
                        title = "设备角色",
                        summary = if (config.value.isPrimary) "主设备（上传）" else "子设备（下载）"
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("配置", MiuixTheme.colorScheme.surface, MiuixTheme.colorScheme.onSurface) {
                        showWebdavDialog = true
                    }
                    val syncScope = rememberCoroutineScope()
                    ActionButton(
                        text = if (config.value.isPrimary) "上传到服务器" else "从服务器下载",
                        bgColor = MiuixTheme.colorScheme.primary,
                        textColor = MiuixTheme.colorScheme.onPrimary,
                        onClick = {
                            val cfg = config.value
                            if (cfg.url.isEmpty()) {
                                Toast.makeText(context, "请先配置 WebDAV", Toast.LENGTH_SHORT).show()
                                return@ActionButton
                            }
                            syncScope.launch {
                                try {
                                    val client = WebDavClient(cfg.url, cfg.username, cfg.password)
                                    withContext(Dispatchers.IO) {
                                        if (cfg.isPrimary) {
                                            val json = SymbolConfig.exportToJson(context)
                                            client.upload(json).getOrThrow()
                                        } else {
                                            val json = client.download().getOrThrow()
                                            SymbolConfig.importFromJson(context, json).getOrThrow()
                                        }
                                    }
                                    val msg = if (cfg.isPrimary) "上传成功" else "下载成功"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (showWebdavDialog) {
            WebDavConfigDialog(
                onDismiss = { showWebdavDialog = false },
                onSaved = { showWebdavDialog = false }
            )
        }
    }

    // ========================================================================
    // 页面 2：关于
    // ========================================================================

    @Composable
    private fun AboutPage(padding: PaddingValues) {
        val context = LocalContext.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                SmallTitle(text = "关于")
                Spacer(modifier = Modifier.height(8.dp))
                Card {
                    BasicComponent(title = "模块名称", summary = "微信输入法上滑符号自定义")
                    BasicComponent(title = "版本", summary = "1.2.0")
                    BasicComponent(title = "作者", summary = "Rakurin Natsumi")
                    BasicComponent(
                        title = "GitHub",
                        summary = "NatsumiXD/WeType-Flick",
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/NatsumiXD/WeType-Flick")
                            )
                            context.startActivity(intent)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ========================================================================
    // 通用组件
    // ========================================================================

    @Composable
    private fun RowScope.ActionButton(text: String, bgColor: Color, textColor: Color, onClick: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale = applePressScale(isPressed)

        Box(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, color = textColor)
        }
    }

    @Composable
    private fun WebDavConfigDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val config = remember { mutableStateOf(WebDavClient.loadConfig(context)) }
        var url by remember { mutableStateOf(config.value.url) }
        var username by remember { mutableStateOf(config.value.username) }
        var password by remember { mutableStateOf(config.value.password) }
        var isPrimary by remember { mutableStateOf(config.value.isPrimary) }
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "WebDAV 配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    DialogTextField("服务器地址", url, { url = it }, "https://dav.example.com")
                    DialogTextField("用户名", username, { username = it })
                    DialogTextField("密码", password, { password = it }, isPassword = true)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isPrimary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface)
                                .clickable { isPrimary = true }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("主设备（上传）", color = if (isPrimary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (!isPrimary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface)
                                .clickable { isPrimary = false }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("子设备（下载）", color = if (!isPrimary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    }

                    testResult?.let {
                        Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.surface)
                                .clickable {
                                    testing = true
                                    testResult = null
                                    scope.launch {
                                        try {
                                            val client = WebDavClient(url, username, password)
                                            val result = withContext(Dispatchers.IO) { client.testConnection() }
                                            testResult = result.getOrThrow()
                                        } catch (e: Exception) {
                                            testResult = "失败: ${e.message}"
                                        } finally {
                                            testing = false
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (testing) "测试中..." else "测试连接",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.primary)
                                .clickable {
                                    val newConfig = WebDavClient.Config(url, username, password, isPrimary)
                                    WebDavClient.saveConfig(context, newConfig)
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                    onSaved()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "保存配置", color = MiuixTheme.colorScheme.onPrimary, fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "主设备：将本地符号上传到服务器\n子设备：从服务器下载符号到本地",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    @Composable
    private fun DialogTextField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String = "", isPassword: Boolean = false) {
        Column {
            Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(text = placeholder, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f), fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            )
        }
    }

    @Composable
    private fun KeyboardPreview(
        keyboardType: String,
        symbols: Map<String, String>,
        defaults: Map<String, String>,
        onKeyClick: (String) -> Unit
    ) {
        val keyShape = RoundedCornerShape(6.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val rows = if (keyboardType == SymbolConfig.T9) SymbolConfig.keys(keyboardType).chunked(3)
                else QWERTY_ROWS.map { row -> row.map { it.toString() } }
            rows.forEach { rowLetters ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowLetters.forEach { letter ->
                        val key = letter
                        val keyLabel = if (keyboardType == SymbolConfig.T9) "$key ${KeyboardKeys.T9_LABELS[key]}" else letter.uppercase()
                        val customSymbol = symbols[key]
                        val defaultSymbol = defaults[key] ?: ""
                        val displaySymbol = customSymbol?.takeIf { it.isNotEmpty() } ?: defaultSymbol
                        val isCustom = customSymbol != null && customSymbol.isNotEmpty()

                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale = applePressScale(isPressed)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "$keyLabel，符号 $displaySymbol" }
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(keyShape)
                                .background(
                                    if (isCustom) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else MiuixTheme.colorScheme.surface
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (isCustom) MiuixTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = keyShape
                                )
                                .clickable(interactionSource = interactionSource, indication = null, role = Role.Button) { onKeyClick(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = displaySymbol,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isCustom) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                                Text(
                                    text = keyLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SymbolEditDialog(
        letter: String,
        value: String,
        defaultSymbol: String,
        isLongPress: Boolean = false,
        onValueChange: (String) -> Unit,
        onConfirm: () -> Unit,
        onClear: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val focus = remember { FocusRequester() }
        Dialog(onDismissRequest = onDismiss) {
            LaunchedEffect(letter) { focus.requestFocus() }
            Card {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (isLongPress) "设置长按符号 - ${letter.uppercase()}"
                        else "设置上滑符号 - ${letter.uppercase()}",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLongPress) "默认: $defaultSymbol\n逗号分隔多个符号"
                        else "默认符号: $defaultSymbol",
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .focusRequester(focus)
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                        singleLine = !isLongPress,
                        textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "取消", modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp), color = MiuixTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "清除", modifier = Modifier.clickable(onClick = onClear).padding(8.dp), color = MiuixTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "确定", modifier = Modifier.clickable(onClick = onConfirm).padding(8.dp), color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
