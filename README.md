# WeType Symbol Changer

微信输入法符号自定义模块（Xposed/LSPosed），支持自定义上滑符号、长按弹出符号、长按延迟、Logo 显示/隐藏等。

Logo功能还没实现

日志文件文件导出

```bash
adb pull /sdcard/Android/data/com.tencent.wetype/files/WeTypeMarkChanger.log
```

[HOOK点](./hook.md)

## 功能

| 功能 | 说明 |
|------|------|
| 上滑符号自定义 | 自定义26个字母上滑弹出的符号 |
| 长按符号自定义 | 自定义长按弹窗中的符号列表（26个字母 × 多符号） |
| 仅同步上滑符号 | 长按弹窗使用上滑符号，无需单独配置长按 |
| 中英文独立配置 | 分别设置中文/英文键盘的符号 |
| 长按延迟调节 | 100ms ~ 1000ms（50ms步进），替代硬编码500ms |
| Logo 隐藏 | 隐藏键盘左上角微信输入法 Logo |
| 内置预设 | 搜狗输入法、Gboard、豆包输入法 |
| JSON 导入/导出 | 完整配置导入导出（v2格式，含长按符号、延迟、Logo） |
| WebDAV 同步 | 多设备配置同步 |

## 构建方法

### 环境要求

- Android Studio (推荐 latest stable)
- JDK 17+
- Gradle 8.x
- Android SDK 36

### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/NatsumiXD/WeType-Flick.git
cd WeType-Flick

# 构建 Debug APK
./gradlew assembleDebug

# 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

### 安装与激活

1. 安装 LSPosed 框架（需 Root 或 Magisk）
2. 安装本模块 APK
3. 在 LSPosed 中启用模块，勾选"微信输入法"
4. 重启微信输入法或重启手机
5. 打开模块 App 配置符号

## 项目架构

```
app/src/main/java/dev/natsumi/wetype/markchanger/
├── App.kt                    # Application，初始化日志
├── MainActivity.kt           # 配置界面（Compose + Miuix UI）
├── ModuleMain.kt             # Xposed Hook 主逻辑
├── SymbolConfig.kt           # 符号配置存储、预设、JSON 导入导出
├── WebDavClient.kt           # WebDAV 客户端
└── XposedServiceManager.kt   # Xposed Service 连接管理
```

### 核心文件说明

#### ModuleMain.kt — Hook 主逻辑

| Hook 点 | 方法 | 作用 |
|---------|------|------|
| `ImeButton.H0(String)` | `com.tencent.wetype.plugin.hld.keyboard.selfdraw.j` | 拦截上滑符号赋值 |
| `ImeButton.v()` | 同上 | 拦截上滑符号读取 |
| `MoreSymbolUtil.k()` | `com.tencent.wetype.plugin.hld.utils.j0` | 替换长按弹窗符号列表 |
| `MoreSymbolUtil.l()` | 同上 | 替换长按弹窗符号列表（大写） |
| `HighLightSymbol` (HashSet) | 同上 | 注入自定义符号到高亮集合 |
| `Handler.sendMessageDelayed()` | Android SDK | 替换长按延迟时间 |
| `ImeCandidateView.a2()` | `com.tencent.wetype.plugin.hld.candidate.ImeCandidateView` | 隐藏左上角 Logo |

#### SymbolConfig.kt — 配置管理

- **存储格式**: SharedPreferences (`swipe_symbols`)
- **键值格式**: `cn_a`/`en_a`（上滑），`cn_long_a`/`en_long_a`（长按），`longPressDelay`（延迟），`logoMode`（Logo）
- **JSON v2 格式**: 包含 `version`、`cn`、`en`、`cnLong`、`enLong`、`longPressDelay`、`logoMode`
- **预设**: `sogou`（搜狗）、`gboard`（Gboard）、`doubao`（豆包）

#### MainActivity.kt — 配置界面

- 基于 Jetpack Compose + Miuix UI 组件
- QWERTY 键盘布局，支持中英文 Tab 切换
- 上滑/长按模式切换
- 仅同步上滑符号开关
- 长按延迟滑块
- Logo 显示/隐藏
- JSON/WebDAV/预设 管理

## 技术细节

### Xposed API 102

使用 `io.github.libxposed:api:102.0.0`，继承 `XposedModule` 基类。

### 反混淆关键类

| 混淆名 | 原始类 | 说明 |
|--------|--------|------|
| `j` | `ImeButton.kt` | 输入法按钮，持有 floatText/mainText |
| `C` | `SymbolFloatData` | 长按弹窗符号数据（所有字段 `private final`，需通过构造函数重建） |
| `j0` | `MoreSymbolUtil` | 长按符号工具类，管理 highLightSymbol 集合 |
| `n` | `KeyboardView.kt` | 键盘视图，硬编码500ms长按延迟 |
| `x` | `ToolbarMgr` | 工具栏管理器 |

### 构造函数重建

`SymbolFloatData` 所有字段为 `private final`，`Field.set()` 在 ART 上静默失败。使用10参数合成构造函数重建实例：

```kotlin
val constructor = symbolFloatDataClass.constructors.find { it.parameterTypes.size == 11 }
val newData = constructor?.newInstance(text, "", false, "", -1, false, false, null, 252, null)
```

## 许可证

MIT License
