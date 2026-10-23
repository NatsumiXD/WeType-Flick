# 动态 Hook、性能与体积验证

## 1.1.6：隐藏图案并保留 Logo 按钮

修复 1.1.5 隐藏 Logo 时把图标及父容器设为 GONE、使按钮消失且不能点击的问题。现在仅用 ImageView 的 imageAlpha 隐藏图案，红点通过绘制透明度隐藏；不修改父容器、visibility、布局尺寸、enabled、clickable 或点击监听器。切回显示/替换模式时恢复原始透明度，保留按钮背景和按压反馈。设置页文案改为“只隐藏图案，保留按钮点击”。

补充 Android 点击回归用例，覆盖图标自身和父容器上的点击监听器保留，以及反复隐藏、显示、替换时的透明度恢复。用例已编译；无连接设备时不声称已完成设备运行。安装包为 `dist/WeType-Flick-1.1.6-logo-click-fix.apk`，验证记录为 `dist/verification-1.1.6.json`。

## 1.1.5：修复上一优化版的 Hook 安装回归

用户反馈原版能 Hook、上一优化版无效后，用真实 APK 的 DEX 成员表复现了确定的错误：旧定位器把元数据中的所有成员都设为安装前置条件，但目标的元数据仍保留已裁剪成员。例如 ImeButton 的 `getRightPadding()`、`setHitRect(Rect)` 等实际不存在，旧版的全量校验必然返回 false，因而根本不安装核心 Hook。

修复版先核对 DEX 中实际声明的成员，只保存使用到的目标；缓存校验仅检查核心上滑所需的方法和字段。长按、Logo 及其元数据失效不会阻断核心上滑。恢复原版的初始化顺序：`onModuleLoaded` 读取配置，`onPackageReady` 立即使用框架提供的最终 ClassLoader 安装核心 Hook。Application.attach 仅用于文件日志，不再依赖 Context、PackageManager 服务枚举或监听器注册成功才能安装。

新增回归覆盖真实 APK 中残留元数据与实际声明的差异；另用只含真实成员名称/签名的无行为 Java 测试桩，执行实际 HookDiscovery 的冷启动、反射调用、缓存命中、坏缓存重定位及重建缓存再次命中。测试桩没有加载或执行微信输入法的方法体。这些测试可复现并防止上一版的安装阻断，但仍不代替 LSPosed 真机运行验证。

## APK 分析证据

使用 REA 的 JADX 工具对用户提供的 `base.apk` 做静态分析，没有运行该 APK。

- 包名：`com.tencent.wetype`，版本：4.0.0 / 58201。
- SHA-256：`0ae20c6c665eda1218f337f2b70176fb26860fa3ae9a4dfeccb02e32a1eee69e`。
- `j.R()` / `j.v()` / `j.H0(String)` 分别是 mainText getter、floatText getter、floatText setter。
- 长按工具类已是 `utils.k0`；旧代码的 `utils.j0` 在此版本只是字符串映射工具。
- 长按列表构造函数实际为 8 个业务参数，另有带默认参数掩码的合成构造函数。
- 候选栏共有多个 `(boolean, boolean)` 方法。实际更新 Logo 的 `c2` 同时调用 logoIv、logoRedPoint getter 和 `ImageView.setImageResource`，不能直接选择第一个同签名方法。
- 输入法服务在 `:hld` 进程。模块从已安装包的服务声明读取实际进程名，不写死 `:hld`。

对应 REA Evidence：

| 观察 | Evidence ID |
| --- | --- |
| 输入包 | `ev_3a4936a96c3be80a3e8e94364c0201b57c1e626c977bb59c94a311de76025b49` |
| mainText getter | `ev_c0566d441dc8fa1d623ea085e2dca29365f6266e94e4a2240efbadd65dc96749` |
| floatText getter | `ev_3fcc1b8f6081ec0b2c9f7bd2322fede3723d38291a16675d70e9b5e07d9d4470` |
| floatText setter | `ev_78a1d109130aa705e302d6847a71aae0dca1bc65412a8640a9c60b4e7a1e5c14` |
| 新长按工具类 | `ev_a04181fc50ba9e9842acd611ff2cfe1d87a3f315553e49477429822dd06b52ed` |
| 符号数据构造函数 | `ev_d5d9cb79b812a74ee9f58d47c9f4cce514aa2ecddd97882fcc1de504d2fd97b8` |
| Logo 更新方法 | `ev_c2baa244946e2955dadf4504939313779b985d1f64063fd0f3647b16ac7b3904` |

## 定位与缓存

1. 按原版在作用域目标包的 `onPackageReady` 安装，不把进程服务查询结果作为安装门槛。
2. 指纹包含规则版本、主 APK / split 绝对路径、文件大小和修改时间；修复版提升为 schema 5，上一版缓存自动失效。
3. 指纹不变时只读取私有目录中的小型缓存，校验核心上滑实际需要的方法和字段。校验失败后读取实际 DEX 重新定位；非核心成员按各功能独立解析。
4. 更新后遍历全部 `classes*.dex`，使用 Kotlin 属性名映射到当前混淆的 JVM 成员签名，覆盖大写、多位数字及长类名。
5. Logo 更新方法用 DEX 指令边界上的调用关系定位。普通函数名在这个 APK 的 Kotlin 元数据里也已混淆，不能把函数名当作语义锚点。
6. 符号列表按参数、返回类型和实际列表元素类型验证。符号数据通过元数据中的 `text` 参数及 Kotlin 默认参数 ABI 构造，支持新增或重新排列的可选参数。
7. 模块自身的运行错误会使缓存失效；下次输入法进程启动时重新定位。上滑功能失败后会停止重复处理该功能，保留原始行为。

定位不依赖随模块打包的微信输入法，也不需要在设备上运行 REA 或加载额外的本地扫描库。`base.apk` 只用于开发回归验证。

兼容边界：依赖目标继续保留可识别的 Kotlin 属性元数据，以及符号数据相关结构。未来版本若移除这些信息或重写键盘架构，会跳过无法确认的功能，需要添加新定位规则；不承诺对所有未来版本自动适配。

## 热路径

- 已命中的上滑 getter 使用一次弱引用缓存查询；未自定义的结果也缓存，不重复反射、大小写转换或查符号表。
- 配置变更才读取远程 preferences；按键、长按和 Logo 路径读取本进程快照。
- 按钮主文字、父键盘、键盘类型方法和 Handler 字段缓存；主文字 setter 使复用按键的结果失效。
- 清除自定义配置会把已存在按钮的字段恢复成原值，避免 getter 与直接绘制字段不同步。
- 长按仅在真正替换时分配新列表。高亮集合在配置变化时更新，移除模块先前注入的旧符号。
- 延迟 Hook 先快速排除默认设置，再检查实际键盘持有的 Handler 身份和对应消息。保持原 `sendMessageDelayed` 返回值，不删除其他排队消息。
- Logo 更新只跟随已验证的方法和视图生命周期；没有 100/500/2000ms 重复重试，没有私有 ImageView 字段反射，没有成功路径写盘日志。

这些是代码路径和静态检查结论，不是设备延迟、CPU 或帧率的实测数据。当前没有连接 Android 测试设备，仍需 LSPosed 真机验证中英文切换、上滑提交、长按菜单、主题切换和设置实时刷新。

## UI

保留 miuix 的 Scaffold、导航、卡片和控件。设置改用 miuix SwitchPreference / Slider；按压缩放为 0.97 的无过冲弹簧，页面使用短淡入淡出；去掉预设列表逐项延时入场。系统关闭动画时取消位移/缩放。保存页面和键盘模式状态，编辑符号时自动聚焦并支持 IME 完成，滑块在交互结束时写配置。

## Release 体积

启用 R8 与资源裁剪，保护 Xposed 入口和元信息；替换完整扩展图标包，移除 AppCompat、Material View 依赖及未使用的 XML 界面。

`assembleRelease` 自动执行 `verifyReleaseApkSize`，任何 Release APK 达到 10,000,000 字节即构建失败。此限制比 10 MiB 更严格。

原始 HEAD 在独立目录以同一工具链构建，未签名 Release 为 14,537,169 字节。1.1.5 修复版体积、签名验证和测试结果见 `dist/verification-1.1.5.json`，安装包为 `dist/WeType-Flick-1.1.5-hook-fix.apk`。交付安装包使用与上一优化版相同的本机 Android 测试证书，正式发布需要项目自己的签名证书。

开发验证：`testDebugUnitTest` 包括真实 APK 的元数据/Logo 调用关系回归、任意类与成员改名，以及构造器参数变化测试；另运行 Debug、Release 和 Lint。真实 APK 回归需要把用户提供的 `base.apk` 放在仓库根目录，文件不加入版本控制。

实现参考：[Kotlin Metadata JVM](https://kotlinlang.org/docs/metadata-jvm.html)、[Android R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[miuix Slider](https://compose-miuix-ui.github.io/miuix/components/slider)、[miuix SwitchPreference](https://compose-miuix-ui.github.io/miuix/components/switchpreference)。
