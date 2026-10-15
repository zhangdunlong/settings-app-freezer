# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

版本号唯一来源为 [`version.properties`](version.properties)，
`AndroidManifest.xml` 的 `versionName` / `versionCode` 与其保持一致。

LSPosed 官方模块仓库要求 Release 标签格式为 `{versionCode}-{versionName}`，
因此本仓库的发布标签也遵循该格式（例如 `100-1.0.0`）。

---

## [Unreleased]

### 修复

**冻结分身应用时误冻结原版（严重）**

公开的 `PackageManager.setApplicationEnabledSetting` 重载是
`(String packageName, int newState, int flags)` —— **第三个参数是 flags，不是 userId**。
原实现把它当作用户 id 传入：

```java
pm.setApplicationEnabledSetting(pkg, state, cloneUserId);   // 错
```

该调用能正常编译，用户 id 被静默塞进 flags 槽位，实际仍作用于**调用方所在用户（0）**。
后果是最坏的一种：点击「冻结分身」时，模块报告分身操作失败，
而**原版应用被真的冻结了**。

实测证据（OnePlus PJZ110 / Android 16 / ColorOS，微信分身 user 999）：

| 用户 | 操作前 | 操作后（修复前） | 操作后（修复后） |
|---|---|---|---|
| 0（原版） | `enabled=1` | **`enabled=3`（被误冻结）** | `enabled=1` |
| 998 | `enabled=1` | `enabled=1` | `enabled=1` |
| 999（目标分身） | `enabled=1` | `enabled=1`（未冻上） | **`enabled=3`** |

读取路径同样失效：公开 API 没有 `getApplicationEnabledSetting(String, int)`，
原反射调用抛 `NoSuchMethodException`，导致状态回读恒为 DEFAULT(0)，
界面无法反映分身的真实冻结状态。

修复方式：新增 `pmForUser(Context, int)`，通过
`Context.createPackageContextAsUser(pkg, 0, UserHandle.of(userId))` 取得**绑定到目标用户**
的 `PackageManager`，之后读写都用普通的公开 API —— 用户由 Context 决定，不靠额外参数。

- `pmForUser()`：用户域 PackageManager 工厂。非 0 用户且无法创建时**返回 null**，
  绝不回退到调用方作用域的实例
- `pmsSetEnabled()`：改为接收 `Context`；拿不到用户域实例时**抛异常拒绝执行**，
  而不是写入错误的用户（宁可失败，不可改错）
- `enabledStateForUser()`：改用用户域实例的公开读取；无法创建时返回 DEFAULT，
  避免把原版状态当成分身状态显示
- `safeEnabledState()` / `readEnabledState()` / `readEnabledStateFor()` 全部改为
  以页面 Context 为准
- `undoFreeze()` 修正为使用 `targetUserFor(activity)`（原为 `currentProcessUser()`，
  在分身页面会指回原版）

**真机验证（修复后，全链路）**

| 验证项 | 结果 |
|---|---|
| 分身识别 | `target user for com.tencent.mm = 999 (extra:userId)` |
| 分身页按钮前缀 | 显示 `[分身] 冻结此应用` |
| 分身状态读取 | 正常（不再抛 NoSuchMethodException） |
| 冻结分身 | 999 → `enabled=3`；0 与 998 **保持不变** |
| 回读校验 | `已冻结 微信 want=FREEZE readback=3` |
| 解冻分身 | 999 → `enabled=1`；`已解冻 微信 want=ENABLE readback=1` |
| 原版页面 | 无 `[分身]` 前缀，状态正确 |
| 原版冻结/解冻 | 0 → `enabled=3` → `enabled=1`，分身旁支不受影响 |
| 原版应用可启动 | 正常 |

> **升级建议**：使用过分身冻结功能的用户，请检查原版对应应用是否被误冻结
> （系统设置 → 应用 → 已停用应用，或本模块的「❄ 已冻结 N」列表），
> 必要时手动解冻。

---

## [1.0.0] — 2026-10-11

首个公开版本。全部功能与修复均经真机验证
（OnePlus PJZ110 / Android 16 / ColorOS，以及 Redmi K40 / Android 13 / MIUI 的兼容性冒烟）。

### 变更

**Application ID 变更为 `io.github.zhangdunlong.settingsappfreezer`**

原 `com.zdl.freezer` 不满足 LSPosed 官方模块仓库对反向域名的要求
（必须是作者**实际拥有**的域名，否则须使用 `io.github.{username}` 前缀）。

- `AndroidManifest.xml` 的 `package` 与 `xposedscope` 同步更新
- `assets/xposed_init` 入口类全限定名同步更新
- `FreezerModule.MODULE_PKG` 常量同步更新（自我保护逻辑依赖该常量）
- 源码目录迁移：`src/com/zdl/freezer/` → `src/io/github/zhangdunlong/settingsappfreezer/`
- `xposedscope` 新增 `com.miui.securitycenter`（模块已具备 MIUI 应用管理器适配逻辑）

> **升级注意**：包名变更后属于新应用，已安装 `com.zdl.freezer` 的设备需先卸载旧版再安装。
> 冻结状态由 Android 系统按**目标应用包名**存储，与本模块包名无关，**不受影响**。

### 新增

**应用信息页冻结按钮**

- 在系统设置的应用信息页右下角注入「冻结 / 解冻」药丸按钮，点击即通过框架级
  `PackageManager.setApplicationEnabledSetting(..., DISABLED_USER)` 停用或恢复应用
- 状态可视化：未冻结青色 `#1AA6C4`，已冻结薄荷绿 `#3FBFA8`，含按压反馈
- 长按显示诊断信息（目标包名 / 启用状态 / 是否系统应用 / 是否被保护）

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

**兼容性机制**

- 双入口实现 `IXposedHookLoadPackage` 与 `IXposedHookZygoteInit`
- 六层生命周期 hook：`onCreate` / `onResume` / `onPostResume` / `onNewIntent` /
  `onWindowFocusChanged` / `ViewRootImpl.performTraversals`（窗口完成首次真实布局的权威信号）
- 包名多级兜底解析 + `normalizePackage()` 归一化，最后用 `getApplicationInfo()` 硬验证
- 状态读写均走 user-aware 隐藏 API 反射，规避 Android 16 签名校验

**安全保护**（动态识别，非硬编码）

- 拒绝冻结系统关键包、当前桌面启动器、当前启用的输入法、前台运行中的应用
- 被拦截时 Toast 明确告知原因，不静默失败

**无障碍**

- 全量 `contentDescription`（含状态与拦截原因），读屏朗读整行而非割裂碎片
- 药丸按钮增加 `focused` 态，键盘/导航设备可达
- 列表行触控目标 ≥56dp，文字对比度全部达 WCAG AA（≥4.5:1）
- 状态不单靠颜色编码（WCAG 1.4.1）

**构建与发布**

- 命令行构建流水线：`javac → jar → d8 → aapt2 link → dex 注入 →
  zipalign → apksigner`，无需 Android Studio 与 Gradle
- 提供 `SUMMARY` / `SOURCE_URL` 元数据文件以适配 LSPosed 官方模块仓库收录规范

### 修复

**「设置 → 应用 → 应用管理 → 点击应用」详情页不显示冻结按钮**

ColorOS 的详情页是**两段式 Activity 交接**：`InstalledAppDetailsTop` 瞬时 Activity
启动后立即销毁，用户实际看到的页面由长驻的 `Settings` / `SubSettings` 承载
（单 Activity 内换页）。原实现只在 `onCreate` 注入，此时布局尚未测量（`w=0 h=0`），
而 120ms 后的第一次重试时 Activity 已经销毁 —— 重试窗口与页面存活窗口完全错开，
按钮因此永远不出现。

- 新增 `handOverToFocusedWindow()`：宿主 Activity 销毁时，把注入动作转交给当前持有焦点的
  Activity，而不是直接放弃
- 新增 `scheduleDelayedHandOver()`：交接目标可能数秒后才真正渲染，故设独立重试预算
  `MAX_HANDOVER_ATTEMPTS = 60` × `HANDOVER_RETRY_MS = 200ms`（12 秒），
  不复用注入重试常量（实测 3 秒不足）
- 交接目标的内容校验**必须允许重试**：ColorOS 单 Activity 换页时，宿主销毁的瞬间
  壳 Activity 仍可能停留在设置主页，应用信息内容需数百毫秒后才绘出；
  若把「尚非应用信息页」视为终态，交接会在第一跳即中止
- 新增 `tryDirectInjectOnFocus()`：窗口获得焦点时内容已完成测量，直接注入，无需重试阶梯；
  已挂 `onWindowFocusChanged` 与 `onResume`
- 新增 `findFocusedActivity()` / `readActivities()` / `activityOf()`：
  Android 9+ 限制 `ActivityThread.getActivities()` 仅返回调用方 PID 的记录，
  故回退直接反射 `mActivities` 字段
- 新增 `resolvePkgFromContent()` 级联第 2/3 级：调用 `resolveTargetPackage()` 与
  `extractPkgFromFields()`，使递归解析嵌套 Bundle 的能力在该路径上真正可达

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

- `handOverToFocusedWindow()` 注入前强制 `looksLikeAppInfoContent()` 校验（页面须含
  「强行停止」动作），非应用信息页则不予注入 —— 该校验现已与重试机制正确配合

**按钮只显示色框无文字**

- ColorOS 主题重写 `Button` 背景与内边距导致文字不可见；改用 `TextView` + 自绘圆角背景，
  完全不依赖 OEM 主题

**按钮在设置所有页面误显示**

- 记忆包名机制被任意页面借用；改为严格页面白名单（`InstalledAppDetails` / `AppInfo` /
  `AppDetails`）+ 内容特征守卫

**列表弹窗层层叠加**

- `dialog` 是局部变量，无实例管理导致重复打开；改为字段持有 + 重建前先 `dismiss()` +
  行点击防抖 + `onDestroy` 清理

**操作栏重复注入**

- 两条注入路径（DecorView / ContentFrameLayout）互不可见导致各注入一次；
  改为从 `DecorView` 根节点做窗口级去重，并以 `WeakHashMap` 兜底

**解冻成功误报**

- 原用目标枚举值推导成败，而 `setApplicationEnabledSetting` 无权限时会静默 no-op；
  改为回读状态与实际意图比对

**性能卡顿**

- 角标每次刷新全量扫描已安装应用（约 100ms，UI 线程）；
  新增 1.5s TTL 计数缓存 + 四处失效点，列表渲染始终使用新鲜数据

**其他**

- `onCreate` 未解析出包名时不再直接返回，改为调度探针，避免页面稍后才可读时彻底放弃
- `isAppInfoHost()` 接受 `applicationsdetails`（MIUI 应用管理器详情页宿主）
- 新增一次性深度诊断 `NESTED-EXTRAS`：递归打印 Intent extras 至 6 层、值截断 220 字符，
  便于直接从日志读出 OEM 实际使用的 key 名

### 兼容性

- 最低 Android 8.0（API 26），`d8 --min-api 26`
- Xposed API 82（现代 API，同时实现 `IXposedHookLoadPackage` 与
  `IXposedHookZygoteInit` 双入口）
- 已在 OnePlus PJZ110 / Android 16（API 36）/ ColorOS 上验证；
  Redmi K40 / Android 13 / MIUI 完成安装与加载冒烟
- 作用域覆盖 `com.android.settings` 及 ColorOS / OPPO / Realme / OnePlus / MIUI 设置包

### 安全

- 签名密钥与 `keystore.properties` 均已加入 `.gitignore`
- 构建产物（`out/`、`*.apk`、`*.idsig`）不入库

### 部署说明

模块需在 LSPosed 中**同时勾选** `com.android.settings` 与 `system` 两个作用域方可生效：

- 缺 `system` → 模块开机时不注入
- 缺 `com.android.settings` → 模块能加载但不会 hook 任何设置进程

作用域记录位于 `/data/adb/lspd/config/modules_config.db`（`scope` 表）。
**两种情况均无任何界面提示**，是本项目最容易踩的坑。

### 已知限制

- 需 Root + LSPosed；作用域改动后必须重启手机
- 系统应用通常无法冻结（`DISABLED_USER` 对系统应用需更高权限，会被拒绝）
- 部分加固应用带自保护机制，冻结后可能被重新拉活
- **ColorOS 分身**：分身应用详情页由主用户 settings 进程渲染，
  若该页 Intent 未携带任何 user 标识，则分身作用域需手动指定
- 通过 `am start` 从 adb 冷启动应用详情页时，ColorOS 可能不渲染该页面
  （属于系统行为，与模块无关）；正常用户操作路径不受影响
- 锁屏状态下通知面板会占据窗口焦点，自动化验证前需先解锁

[1.0.0]: https://github.com/zhangdunlong/settings-app-freezer/releases/tag/100-1.0.0
