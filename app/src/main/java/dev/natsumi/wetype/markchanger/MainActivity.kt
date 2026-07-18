package dev.natsumi.wetype.markchanger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.InputStreamReader
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    companion object {
        private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val KEYBOARD_LABELS = listOf("中文键盘", "英文键盘")
        private val KEYBOARD_TYPES = listOf(SymbolConfig.CN, SymbolConfig.EN)
        private val LONG_PRESS_KEY = "longPress"

        private val NAV_ITEMS = listOf(
            Triple("符号配置", Icons.Filled.Star, "自定义上滑和长按符号"),
            Triple("数据管理", Icons.Filled.Build, "导入导出与云端同步"),
            Triple("关于", Icons.Filled.Info, "模块信息")
        )
    }

    private var pendingExportJson: String? = null

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
                Toast.makeText(this, "导入成功，共 $count 条符号", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SymbolConfig.syncLocalToRemote(this)

        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                MainScreen()
            }
        }
    }

    // ========================================================================
    // 主屏幕 — Scaffold + NavigationBar
    // ========================================================================

    @Composable
    private fun MainScreen() {
        var selectedNav by remember { mutableIntStateOf(0) }

        Scaffold(
            topBar = {
                SmallTopAppBar(title = "微信输入法上滑符号")
            },
            bottomBar = {
                NavigationBar {
                    NAV_ITEMS.forEachIndexed { index, (label, icon, _) ->
                        NavigationBarItem(
                            selected = selectedNav == index,
                            onClick = { selectedNav = index },
                            icon = icon,
                            label = label
                        )
                    }
                }
            }
        ) { paddingValues ->
            AnimatedContent(
                targetState = selectedNav,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it / 4 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally { it / 4 } + fadeOut(tween(200)))
                    }
                },
                label = "page"
            ) { page ->
                when (page) {
                    0 -> SymbolsPage(paddingValues)
                    1 -> DataManagePage(paddingValues)
                    2 -> AboutPage(paddingValues)
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
        var selectedTab by remember { mutableIntStateOf(0) }
        var selectedMode by remember { mutableIntStateOf(0) }
        var syncSwipeOnly by remember { mutableStateOf(SymbolConfig.isSyncSwipeOnly(context)) }
        var longPressDelay by remember { mutableLongStateOf(SymbolConfig.getLongPressDelay(context)) }
        val keyboardType = KEYBOARD_TYPES[selectedTab]
        val defaults = SymbolConfig.getDefaultSymbols(keyboardType)
        val longPressDefaults = SymbolConfig.getDefaultLongPressSymbols(keyboardType)

        val symbols = remember { mutableStateMapOf<String, String>() }
        val longPressSymbols = remember { mutableStateMapOf<String, String>() }
        var editingKey by remember { mutableStateOf<String?>(null) }
        var editingValue by remember { mutableStateOf("") }
        var editingMode by remember { mutableIntStateOf(0) }

        LaunchedEffect(selectedTab, selectedMode, syncSwipeOnly) {
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
                        BasicComponent(
                            title = "仅同步上滑符号",
                            summary = if (syncSwipeOnly) "已开启 — 长按符号不修改" else "关闭 — 可自定义长按符号",
                            onClick = {
                                syncSwipeOnly = !syncSwipeOnly
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
                                val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "mode")

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
                        SmallTitle(text = "上滑符号自定义")
                        Spacer(modifier = Modifier.height(8.dp))
                        QwertyKeyboard(
                            symbols = symbols,
                            defaults = defaults,
                            onKeyClick = { key ->
                                editingKey = key
                                editingValue = symbols[key] ?: ""
                                editingMode = 0
                            }
                        )
                    } else {
                        SmallTitle(text = "长按符号自定义")
                        Spacer(modifier = Modifier.height(8.dp))
                        QwertyKeyboard(
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

                // 预设方案 — 下拉框选择
                item {
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
                                        var itemVisible by remember { mutableStateOf(false) }
                                        LaunchedEffect(showPresetDropdown) {
                                            itemVisible = false
                                            kotlinx.coroutines.delay(index * 60L)
                                            itemVisible = true
                                        }
                                        val alpha by animateFloatAsState(
                                            targetValue = if (itemVisible) 1f else 0f,
                                            animationSpec = tween(durationMillis = 200),
                                            label = "preset_alpha"
                                        )
                                        val offsetY by animateFloatAsState(
                                            targetValue = if (itemVisible) 0f else 12f,
                                            animationSpec = tween(durationMillis = 200),
                                            label = "preset_offset"
                                        )
                                        Box(modifier = Modifier.graphicsLayer { this.alpha = alpha; translationY = offsetY }) {
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
                                                    Toast.makeText(context, "已应用「${preset.name}」预设", Toast.LENGTH_SHORT).show()
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
                        var sliderValue by remember { mutableStateOf(longPressDelay.toFloat()) }
                        var inputText by remember { mutableStateOf(longPressDelay.toString()) }

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
                            // 自定义 Slider
                            var sliderWidth by remember { mutableIntStateOf(1) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .onSizeChanged { sliderWidth = it.width.coerceAtLeast(1) }
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, _ ->
                                            change.consume()
                                            val width = size.width.toFloat()
                                            val fraction = (change.position.x / width).coerceIn(0f, 1f)
                                            val newValue = (100f + fraction * 900f).roundToLong().coerceIn(100L, 1000L)
                                            sliderValue = newValue.toFloat()
                                            longPressDelay = newValue
                                            inputText = newValue.toString()
                                            SymbolConfig.setLongPressDelay(context, longPressDelay)
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .align(Alignment.Center)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))
                                )
                                val fraction = (sliderValue - 100f) / 900f
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MiuixTheme.colorScheme.primary)
                                )
                                val thumbOffsetPx = fraction * sliderWidth - sliderWidth / 2f
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(thumbOffsetPx.toInt(), 0) }
                                        .size(20.dp)
                                        .align(Alignment.Center)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.primary)
                                )
                            }
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

                // Logo 显示/隐藏
                item {
                    SmallTitle(text = "左上角 Logo")
                    Spacer(modifier = Modifier.height(8.dp))
                    Card {
                        val logoModeOptions = listOf("显示" to "保留微信输入法 Logo", "隐藏" to "隐藏 Logo，节省空间")
                        val currentLogoMode = remember { mutableIntStateOf(SymbolConfig.getLogoMode(this@MainActivity)) }
                        Column {
                            logoModeOptions.forEachIndexed { index, (label, desc) ->
                                BasicComponent(
                                    title = label,
                                    summary = desc,
                                    onClick = {
                                        if (currentLogoMode.intValue != index) {
                                            currentLogoMode.intValue = index
                                            SymbolConfig.setLogoMode(this@MainActivity, index)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 底部保存/恢复
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("保存", MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.onPrimary) {
                        if (selectedMode == 0) {
                            for (letter in SymbolConfig.LETTERS) {
                                val key = letter.toString()
                                val v = symbols[key] ?: ""
                                if (v.isNotEmpty()) SymbolConfig.setSymbol(context, keyboardType, key, v)
                                else SymbolConfig.removeSymbol(context, keyboardType, key)
                            }
                        } else {
                            for (letter in SymbolConfig.LETTERS) {
                                val key = letter.toString()
                                val v = longPressSymbols[key] ?: ""
                                if (v.isNotEmpty()) SymbolConfig.setLongPressSymbols(context, keyboardType, key, v)
                                else SymbolConfig.removeLongPressSymbols(context, keyboardType, key)
                            }
                        }
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }
                    ActionButton("恢复默认", Color.Transparent, MiuixTheme.colorScheme.onSurface) {
                        if (selectedMode == 0) {
                            SymbolConfig.resetKeyboard(context, keyboardType)
                            symbols.clear()
                        } else {
                            SymbolConfig.resetLongPressKeyboard(context, keyboardType)
                            longPressSymbols.clear()
                        }
                        Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
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
                    if (editingMode == 0) symbols[key] = editingValue
                    else longPressSymbols[key] = editingValue
                    editingKey = null
                },
                onClear = {
                    if (editingMode == 0) symbols.remove(key)
                    else longPressSymbols.remove(key)
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
                    BasicComponent(title = "版本", summary = "1.1.3")
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
        val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "btn")

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
    private fun QwertyKeyboard(
        symbols: Map<String, String>,
        defaults: Map<String, String>,
        onKeyClick: (String) -> Unit
    ) {
        val keyShape = RoundedCornerShape(6.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QWERTY_ROWS.forEachIndexed { _, rowLetters ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowLetters.forEach { letter ->
                        val key = letter.toString()
                        val customSymbol = symbols[key]
                        val defaultSymbol = defaults[key] ?: ""
                        val displaySymbol = customSymbol?.takeIf { it.isNotEmpty() } ?: defaultSymbol
                        val isCustom = customSymbol != null && customSymbol.isNotEmpty()

                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, label = "key")

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
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
                                .clickable(interactionSource = interactionSource, indication = null) { onKeyClick(key) },
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
                                    text = letter.uppercase(),
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
        Dialog(onDismissRequest = onDismiss) {
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
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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
