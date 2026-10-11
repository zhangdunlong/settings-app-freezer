# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

版本号唯一来源为 [`version.properties`](version.properties)，
`AndroidManifest.xml` 的 `versionName` / `versionCode` 与其保持一致。

---

## [Unreleased]

### 修复

**「设置 → 应用 → 应用管理 → 点击应用」详情页不显示冻结按钮**

根因是 ColorOS 的详情页采用**两段式 Activity 交接**：`InstalledAppDetailsTop` 瞬时 Activity
启动后立即销毁，真正承载页面的是长驻的 `OplusSettingsHomepageActivity`（单 Activity 内换页）。
原有实现只在 `onCreate` 注入，此时布局尚未测量（`w=0 h=0`），而 120ms 后的第一次重试时
Activity 已经销毁 —— 重试窗口与页面存活窗口完全错开，按钮因此永远不出现。

- 新增 `handOverToFocusedWindow()`：宿主 Activity 销毁时，把注入动作转交给当前持有焦点的
  Activity，而不是直接放弃
- 新增 `scheduleDelayedHandOver()`：交接目标在数秒后才真正创建，故设独立重试预算
  `MAX_HANDOVER_ATTEMPTS = 60` × `HANDOVER_RETRY_MS = 200ms`（12 秒），
  不再复用注入重试常量（实测 3 秒不足）
- 新增 `tryDirectInjectOnFocus()`：窗口获得焦点时内容已完成测量，直接注入，无需重试阶梯；
  已挂 `onWindowFocusChanged` 与 `onResume`
- 新增 `findFocusedActivity()` / `readActivities()` / `activityOf()`：
  Android 9+ 限制 `ActivityThread.getActivities()` 仅返回调用方 PID 的记录，
  故回退直接反射 `mActivities` 字段
- 新增 `resolvePkgFromContent()` 级联第2/3 级：调用 `resolveTargetPackage()` 与
  `extractPkgFromFields()`，使递归解析嵌套 Bundle 的能力在此路径上真正可达

**包名未归一化导致解析失败**

部分 OEM 传递的包名带前导斜杠（MIUI 的 `package_name=//com.android.chrome`），
旧校验因 `contains("/")` 直接判否，好包名被静默丢弃。

- 新增 `normalizePackage()` / `stripSlashes()` / `looksLikePackageRaw()`
- 接入**全部 8 处**包名解析点：`resolveTargetPackage`、`findPackageInExtras`、
  `extractPkgFromFields`、`scanFragmentForPkg`、`describeFragment`、
  `describeViewForPackage`、`resolvePkgFromContent`、URI 路径分支
- `findPackageInExtras()` 扩展载体识别：新增 `CharSequence`（SpannedString）分支、
  `Parcelable` 包裹的 `mBundle` 解包

**设置首页误显示冻结栏（回归）**

交接目标是「当前持有焦点的窗口」，而在 ColorOS 上这恰好也是用户看到的设置首页。
交接路径必须与探针路径一样受内容特征守卫约束。

- `handOverToFocusedWindow()` 注入前强制 `looksLikeAppInfoContent()` 校验，
  非应用信息页则放弃交接

**其他**

- `onCreate` 未解析出包名时不再直接返回，改为调度探针，避免页面稍后才可读时彻底放弃
- `isAppInfoHost()` 接受 `applicationsdetails`（MIUI 应用管理器详情页宿主）
- 新增一次性深度诊断 `NESTED-EXTRAS`：递归打印 Intent extras 至 6 层、值截断 220 字符，
  便于直接从日志读出 OEM 实际使用的 key 名

### 部署说明

模块需在 LSPosed 中同时勾选 `com.android.settings` 与 `system` 两个作用域方可生效。
作用域记录位于 `/data/adb/lspd/config/modules_config.db`（`scope` 表），
缺少 `com.android.settings` 时模块不会加载任何 Hook，且无任何界面提示。

---

## [1.0.0] — 2026-10-06

首个公开版本。以下功能全部经过真机验证（OnePlus PJZ110 / Android 16 / ColorOS）。

### 新增

**已冻结应用列表**

- 应用信息页底部并排「❄ 已冻结 N」入口，带实时计数角标，无冻结项时置灰
- 弹窗列出全部已冻结应用的图标、应用名与包名，点击任意行即解冻
- 列表按**最近冻结优先**排序，无记录的按包名排列
- 弹窗内**空状态**：图标 + 「暂无已冻结应用」+ 引导文案
- 解冻 Toast 带**撤销**动作，一点即恢复冻结
- 仅统计 `DISABLED_USER`（用户主动冻结），排除系统出厂禁用项

**分身（App Twin / MultiApp）作用域**

- 冻结/解冻**精确到 Android user**，可在分身内单独冻结而不影响本机原版
- 目标 user 按 Activity 逐页解析：Intent extras（`USER_ID` 等 5 种键名）
  → Activity 实例字段扫描 → 进程 user 三级探测
- 分身环境下按钮显示 `[分身]` 前缀，读屏播报「分身应用」

**兼容性**

- 双入口实现 `IXposedHookLoadPackage` 与 `IXposedHookZygoteInit`
- 四层生命周期 hook：`onCreate` / `onResume` / `onWindowFocusChanged` /
  `ViewRootImpl.performTraversals`（窗口完成首次真实布局的权威信号）
- 包名 6 级兜底解析：Intent data → `EXTRA_PACKAGE_NAME` →
  `EXTRA_COMPONENT_NAME` → Activity 私有字段反射 → 记忆复用 → 视图内容特征
- 状态读写均走 user-aware 隐藏 API 反射，规避 Android 16 签名校验

**安全保护**（动态识别，非硬编码）

- 拒绝冻结系统关键包、当前桌面启动器、当前启用的输入法、前台运行中的应用

**无障碍**

- 全量 `contentDescription`（含状态与拦截原因），读屏朗读整行而非割裂碎片
- 药丸按钮增加 `focused` 态，键盘/导航设备可达
- 列表行触控目标 ≥56dp，文字对比度全部达 WCAG AA（≥4.5:1）
- 状态不单靠颜色编码（WCAG 1.4.1）

### 修复

- **按钮只显示色框无文字** —— ColorOS 主题重写 `Button` 背景与内边距导致文字不可见；
  改用 `TextView` + 自绘圆角背景，完全不依赖 OEM 主题
- **按钮在设置所有页面误显示** —— 记忆包名机制被任意页面借用；
  改为严格页面白名单（`InstalledAppDetails` / `AppInfo` / `AppDetails`）
- **列表弹窗层层叠加** —— `dialog` 是局部变量无实例管理；
  改为字段持有 + 重建前先 `dismiss()` + 行点击防抖 + `onDestroy` 清理
- **操作栏重复注入** —— 两条注入路径（DecorView / ContentFrameLayout）互不可见导致
  各注入一次；改为从 `DecorView` 根节点做窗口级去重
- **解冻成功误报** —— 原用目标枚举值推导成败，而
  `setApplicationEnabledSetting` 无权限时会静默 no-op；改为回读状态与意图比对
- **性能卡顿** —— 角标每次刷新全量扫描已安装应用（约 100ms，UI 线程）；
  新增 1.5s TTL 计数缓存 + 四处失效点，列表渲染始终使用新鲜数据

### 已知限制

- 需 Root + LSPosed；作用域改动后必须重启手机
- 系统应用通常无法冻结（`DISABLED_USER` 对系统应用需更高权限）
- 部分加固应用带自保护机制，冻结后可能被重新拉活
- **ColorOS 分身**：分身应用详情页由主用户 settings 进程渲染，
  若该页 Intent 未携带任何 user 标识，则分身作用域需手动指定
- 「设置 → 应用 → 应用列表」点击进入的详情页，包名解析依赖厂商实现，
  部分 ROM 可能无法识别

---

## [1.0.0] — 2026-10-05

首个公开版本。基于早期社区冻结类模块的思路，针对现代 Android 与厂商 ROM 完全重写。

### 新增

- **应用信息页浮动按钮** —— 在系统设置的应用信息页右下角注入「冻结 / 解冻」药丸按钮，
  点击即通过框架级 `PackageManager.setApplicationEnabledSetting(..., DISABLED_USER)`
  停用或恢复应用
- **ROM 无关的页面识别** —— 采用多级兜底解析：
  1. Intent `data` 的 `package:` scheme
  2. `getLastPathSegment()`
  3. `Intent.EXTRA_PACKAGE_NAME`
  4. `Intent.EXTRA_COMPONENT_NAME`（Parcelable 与 String 两种形式）
  5. Activity 私有字段反射（`mPackageName` / `mAppPackageName` 等）
  6. 短时记忆上次解析结果（60 秒 TTL）
  最后用 `getApplicationInfo()` 硬验证候选包确实已安装
- **四层生命周期 hook** —— `onCreate(Bundle)`、`onResume()`、
  `onWindowFocusChanged(boolean)`，以及关键的 `ViewRootImpl.performTraversals`
  / `doTraversal` 布局完成信号
- **动态安全保护** —— 拒绝冻结以下目标，并 Toast 说明原因：
  - 系统关键包（设置、SystemUI、权限控制器、应用商店、GMS、本模块自身等）
  - 当前桌面启动器（通过 `CATEGORY_HOME` 动态解析）
  - 当前启用的输入法（通过 `InputMethodManager` 动态查询）
  - 正在前台运行的应用（通过 `ActivityManager` 动态查询）
- **自适应注入重试** —— 20 轮短延迟退避（120ms → 800ms 封顶），
  配合一次性 `OnGlobalLayoutListener`
- **状态可视化** —— 未冻结青色 `#1AA6C4`，已冻结薄荷绿 `#3FBFA8`，
  含按压反馈与圆角药丸背景
- **长按诊断** —— 显示目标包名、启用状态、是否系统应用、是否被保护
- **命令行构建流水线** —— `javac → jar → d8 → aapt2 link → dex 注入 →
  zipalign → apksigner`，无需 Android Studio 与 Gradle
- **adb 作用域配置脚本说明** —— README 提供设备无 sqlite3 时直写 LSPosed 配置库的方法

### 兼容性

- 最低 Android 8.0（API 26），`d8 --min-api 26`
- Xposed API 82（现代 API，同时实现 `IXposedHookLoadPackage` 与
  `IXposedHookZygoteInit` 双入口）
- 已在 OnePlus PJZ110 / Android 16（API 36）/ ColorOS 上验证
- 作用域覆盖 `com.android.settings` 及 ColorOS / OPPO / Realme / OnePlus 设置包

### 安全

- 签名密钥与 `keystore.properties` 均已加入 `.gitignore`
- 构建产物（`out/`、`*.apk`、`*.idsig`）不入库

### 已知限制

见 [README 的已知限制](README.md#已知限制) 章节。

[1.0.0]: https://github.com/zhangdunlong/settings-app-freezer/releases/tag/v1.0.0
