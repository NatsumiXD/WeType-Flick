# Hook 点汇总

本模块所有 Hook 点均在 `ModuleMain.kt` 中实现，使用 Xposed API 102。

## 版本兼容策略

本模块采用**特征发现机制**，不再硬编码混淆类名/方法名，确保跨版本兼容：

| 目标 | 已知全名（快速路径） | 特征匹配规则 |
|------|----------------------|-------------|
| 按钮类 (ImeButton) | `selfdraw.j` | 2+ 个 `()→String` 方法 + 1 个 `(String)→void` 方法 |
| 键盘类 (KeyboardView) | `selfdraw.n` | 拥有 `getKeyboardType()` 方法（返回非 void） |
| MoreSymbolUtil | `utils.j0` | 静态自身类型字段（单例）+ 2 个同参数类型返回 List 的方法 |
| SymbolFloatData | `floatview.C` | 10 参数构造器 + String 类型字段 |
| ImeCandidateView | `ImeCandidateView` | 拥有 `(boolean, boolean)→?` 方法 |

**查找流程**：先尝试已知全名（快速路径）→ 失败则扫描包下所有短名称类（a-z, 0-9, aa-zz, a0-z9）→ 按特征签名匹配。

## Hook 列表

| # | Hook 目标 | 方法 | 作用 | 行号 |
|---|-----------|------|------|------|
| 1 | `android.app.Application` | `attach(Context)` | 捕获应用初始化，初始化文件日志 | 228 |
| 2 | `KeyboardView` (selfdraw) | `f0()` / `initKeyboard()` | 追踪键盘类型（中文/英文） | 448 |
| 3 | `ImeButton` (selfdraw) | `H0(String)` / `setFloatText(String)` | 拦截上滑符号赋值，替换为自定义符号 | 490 |
| 4 | `ImeButton` (selfdraw) | `v()` / `getFloatText()` | 拦截上滑符号读取，返回自定义符号 | 540 |
| 5 | `MoreSymbolUtil` (utils) | `k(ImeButton)` | 替换长按弹窗符号列表（小写 QWERTY） | 636 |
| 6 | `MoreSymbolUtil` (utils) | `l(ImeButton)` | 替换长按弹窗符号列表（大写 QWERTY） | 650 |
| 7 | `Handler` (Android SDK) | `sendMessageDelayed(Message, long)` | 替换长按延迟时间（100ms~1000ms） | 840 |
| 8 | `ImeCandidateView` | `(boolean, boolean)` | 隐藏/替换左上角 Logo | 915 |

## 详细说明

### 1. Application.attach()

```
类: android.app.Application
方法: attach(Context)
时机: 应用初始化时
作用: 初始化文件日志记录器
```

### 2. 键盘类型追踪

```
类: 通过 getKeyboardType() 方法特征发现（原 selfdraw.n）
方法: f0() (混淆名) / initKeyboard()
时机: 键盘初始化时
作用: 通过 getKeyboardType() 判断当前是中文还是英文键盘
      结果存入 currentKeyboardType，用于选择对应符号表
```

### 3. H0 / setFloatText（上滑符号设置）

```
类: 通过 2 个无参 String 方法 + 1 个 String 参数 void 方法特征发现（原 selfdraw.j）
方法: H0(String) (混淆名) / setFloatText(String)
参数: String — 原始上滑符号
时机: 每次设置按钮上滑符号时
作用:
  1. 缓存原始上滑符号到 originalFloatTextMap（用于仅同步模式）
  2. 查找自定义符号并设置到 floatText 字段
  3. 更新 symbolCache 缓存
流程: proceed() → 获取 mainText → 查自定义符号 → Field.set(floatText, custom)
```

### 4. v() / getFloatText（上滑符号读取）

```
类: 通过 2 个无参 String 方法 + 1 个 String 参数 void 方法特征发现（原 selfdraw.j）
方法: v() (混淆名) / getFloatText()
返回: String — 上滑符号
时机: 每次读取按钮上滑符号时（热路径）
作用:
  快速路径: symbolCache 命中直接返回
  慢速路径: 调用原方法 → 查自定义符号 → 缓存并返回
```

### 5-6. k() / l()（长按弹窗符号列表）

```
类: 通过单例字段 + 同参数类型 List 方法特征发现（原 utils.j0）
方法: k(ImeButton) — 小写 QWERTY 长按符号
      l(ImeButton) — 大写 QWERTY 长按符号
返回: List<SymbolFloatData>
时机: 长按字母键弹出符号选择时
作用:
  仅同步模式: 找到原始上滑符号并替换为自定义符号
  自定义模式: 用自定义符号重建整个列表
流程:
  1. 获取自定义长按符号列表
  2. 通过 10 参数构造函数创建新 SymbolFloatData 实例
  3. 将自定义符号加入 highLightSymbol 集合（确保默认选中位置正确）
  4. 返回修改后的列表
关键约束:
  SymbolFloatData 所有字段 private final，Field.set() 静默失败
  必须使用10参数合成构造函数重建实例
  mask 252: oriText="", hasSuperScript=false, superScript="", iconId=-1, hasDivider=false, isIconWithText=false, action=null
```

### 7. Handler.sendMessageDelayed（长按延迟）

```
类: android.os.Handler
方法: sendMessageDelayed(Message, long)
时机: 任何 Handler 发送延迟消息时
条件: msg.what == 1 && originalDelay == 500ms
作用: 替换500ms为用户自定义延迟（100ms~1000ms）
机制:
  1. 检测 ThreadLocal 递归标志避免死循环
  2. 匹配 msg.what==1 且原始延迟 500ms
  3. 移除原消息，用自定义延迟重新发送
  4. 返回 true 阻止原调用
```

### 8. ImeCandidateView（Logo 隐藏）

```
类: 通过 (boolean, boolean) 方法特征发现（原 ImeCandidateView）
方法: a2(boolean isDarkMode, boolean standaloneMode)
时机: 候选栏视图初始化/更新时
作用: 原方法设置 Logo 图标，Hook 后根据设置隐藏 Logo
流程: proceed() → 检查 logoMode → 找到 logo_container_rl → 设 GONE
```

## 内存数据结构

```
cnSymbols:           HashMap<String, String>    — 中文上滑符号表 (a→@)
enSymbols:           HashMap<String, String>    — 英文上滑符号表 (a→@)
cnLongSymbols:       HashMap<String, List<String>> — 中文长按符号表 (a→[, A, à, ...])
enLongSymbols:       HashMap<String, List<String>> — 英文长按符号表
originalFloatTextMap: HashMap<String, String>   — 原始上滑符号 (a→a)
symbolCache:         WeakHashMap<Any, String?>   — 按钮实例→自定义符号缓存
mainTextCache:       WeakHashMap<Any, String>    — 按钮实例→mainText 缓存
highLightSet:        HashSet<String>             — 长按弹窗默认选中符号集合
```
