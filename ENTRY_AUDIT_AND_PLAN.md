# 冻结入口全面梳理、问题分析与整合方案

> 基线版本：v1.0.2（commit `c49f2a5`），源码 `src/io/github/zhangdunlong/settingsappfreezer/FreezerModule.java`（3199 行）
> 文中行号均指该基线。本次为**静态审计 + 本地构建验证**，未连接任何真机（一加 13 正用于其他调试）。

---

## 一、入口清单

「入口」在本软件中有三层含义，分别列出：① 用户可直接触发冻结/解冻动作的 UI 控件；② 会把用户带到应用信息页、从而间接触发冻结栏的系统路径；③ 代码内部真正发起「注入按钮」或「写入冻结状态」的触发点。三者叠加，才是「入口过多且分散」的完整图景。

### A. 用户可见的 UI 入口（冻结 / 解冻动作）

| # | 入口 | 所在位置（基线行号） | 进入路径 | 出现条件 | 触发动作 |
|---|---|---|---|---|---|
| A1 | **主按钮**「❄ 冻结此应用」/「❄ 已冻结 · 点此解冻」 | 应用信息页底部浮动操作栏左药丸，`FROZEN_TAG`；`injectButton()` L1926–2059，点击监听 L2018 → `toggle()` L2579 | 任意打开到应用信息页后即可见，单击 | 任一注入链路判定当前窗口为应用信息页 + 成功解析到已安装包名 + 未命中窗口级去重 | **冻结 / 解冻**（唯一主写入口）。中文/英文文案由 `tr()` 决定；分身页加 `[分身]` / `[Clone]` 前缀 |
| A2 | 主按钮**长按** | L2024 → `showDiagnostics()` L2959 | 长按 A1 | 与 A1 同时存在 | 显示诊断（包名 / 状态 / 是否系统应用 / 保护原因），**不触发冻结**；属关联入口 |
| A3 | **「❄ 已冻结 N」计数徽标** | 同栏右药丸，`FROZEN_LIST_TAG`，`buildListButton()` L2084；点击 L2031 → `showFrozenList()` L2253 | 单击 A3 | 与 A1 同栏，**始终常驻**；`N=0` 时仅置灰（α=0.6）不隐藏 | 打开已冻结列表（解冻入口） |
| A4 | **已冻结列表行** | `buildFrozenRow()` L2452，点击 L2498 → `unfreezeFromList()` L2514 | 打开 A3 后点击任一行 | 列表非空（`DISABLED_USER` 状态包 ≥1）且该行未被防抖禁用 | **解冻**（冻结功能的反向触发） |
| A5 | 列表空态弹窗 | `showEmptyState()` L2337 | A3 且 `N=0` | 无任何已冻结应用 | 仅提示，不改动状态 |
| A6 | **Toast「撤销」** | `toastWithUndo()` L2395 → `undoFreeze()` L2422 | 从 A4 解冻成功后，点击 Toast 上的「撤销」 | 需 ROM 暴露隐藏 API `Toast.setAction(OnClickListener, CharSequence)`；多数 ROM 不满足，此时只是带无效文字的普通 Toast | **重新冻结**。⚠ 基线中该路径**未调用 `criticalReason()`**，是唯一绕过安全校验的冻结入口（v1.0.3 已纳入门面修复） |
| A7 | 弹窗「关闭 / 知道了 / 取消」 | L2299 / L2372 等 | — | 对应弹窗打开时 | 仅关闭 |

> 关键结论：**能真正写入 `DISABLED_USER` 的路径有两条**（A1 单击、A6 撤销），能解除的有两条（A1 单击、A4 行点击）；视觉上却是同一屏上 2 个常驻药丸 + 1 个长按 + 1 个弹窗列表 + 1 个 Toast。

### B. 到达应用信息页的系统路径（每条都会触发 A1/A3 注入）

| # | 系统路径 | 承载页面 / 传递方式 | 出现条件与备注 |
|---|---|---|---|
| B1 | 设置 → 应用 → **应用管理** → 应用列表 → 点某应用 | ColorOS 两段式：`InstalledAppDetailsTop`（瞬时）→ `InstalledAppDetails` / 长驻壳 | 依赖 `handOverToFocusedWindow()` + 焦点快路径；CHANGELOG L242 记录过该路径按钮不显示的缺陷 |
| B2 | 设置 → 应用 → **应用列表** → 点某应用 | OEM 的 `AppListActivity` / `ApplicationsSettings` | `isAppInfoHost()` L441 明确拒列表页；包名传递依厂商实现，README L263 注明少数机型不显示 |
| B3 | 设置 → 应用 → 任意应用 → **应用信息**（README 主路径 L107） | `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:` URI | 最稳定路径 |
| B4 | **系统设置二级菜单 / 深层链接**：通知、权限、电池、存储、默认应用、流量管理等页面内的「应用信息」跳转 | 通用 `SubSettings` 壳；`:settings:show_fragment_args` 嵌套 Bundle / `EXTRA_PACKAGE_NAME` / content:// 末段（`resolveTargetPackage()` L1394–1464、`findPackageInExtras()` L1246） | ⚠ `isAppInfoHost()` 硬拒 `subsettings`（L442），但 `looksLikeAppInfoContent()` 只检查页面含「强行停止 / Force stop」文本（L505–527）即可放行 → **冻结栏会出现在这些二级菜单页**，正是「入口分散」的直接观感来源 |
| B5 | 桌面长按应用图标 → 应用信息；第三方启动器 / LSPosed / 应用管理器发起的 `APPLICATION_DETAILS_SETTINGS` | 直接 Intent | 与 B3 等价 |
| B6 | 设置内搜索结果 → 应用详情 | OEM 搜索壳 | 视壳 Activity 是否命中内容特征 |
| B7 | **分身 / 多用户**应用信息页（实测 user 998/999） | `extra:userId` 等 user 线索，`resolveTargetUser()` L2678 → `targetUserFor()` L2660 | 同一包名不同 user；按钮加 `[分身]` / `[Clone]` 前缀，作用用户由页面决定 |
| B8 | MIUI 应用管理器详情页 | `com.miui.securitycenter` 作用域，`applicationsdetails` 宿主（L452） | Manifest `xposedscope` 已声明该包 |
| B9 | 通知栏长按通知 → 应用信息（部分 ROM） | 厂商实现 | 与 B4 同类壳页面 |

### C. 代码级触发点（技术入口）

| # | 触发点 | 基线行号 | 触发条件 | 去向 |
|---|---|---|---|---|
| C1 | `IXposedHookZygoteInit.initZygote` | L158 | 模块加载 | 仅日志 |
| C2 | `IXposedHookLoadPackage.handleLoadPackage` | L165 | 进程名过滤 L172–180 | `hookActivityLifecycle()` |
| C3 | `Activity.onCreate(Bundle)` | L258（fallback L262） | 每个 Settings Activity 创建 | `scheduleInject()` 或 `probeForAppInfoPage()`（L231–249） |
| C4 | `Activity.onResume()` | L271（fallback L301） | 每次恢复 | `tryDirectInjectOnFocus()` + `scheduleInject()` |
| C5 | `Activity.onPostResume()` | L333 | 每次 resume / fragment 事务后 | `probe("postResume")` |
| C6 | `Activity.onNewIntent(Intent)` | L351 | 复用 Activity 收到新 Intent | `clearBarHost()` + `probe("newIntent")` |
| C7 | `Activity.onWindowFocusChanged(true)` | L378 | 窗口获得焦点 | `tryDirectInjectOnFocus()` + `probe("focus")` |
| C8 | `ViewRootImpl.performTraversals()` | L1147–1193 | **每次布局遍历**（滚动 / 动画 / 绘制） | `resolvePkgForLifecycle()` → `injectButton()`；失败再 `dumpUnknownActivity()` |
| C9 | `ViewRootImpl.doTraversal()` | 同上 for 循环第 2 个方法名 | 同 C8 | ⚠ 与 C8 **注册同一个 hook 两次**，开销翻倍 |
| C10 | `ViewTreeObserver.OnGlobalLayoutListener` | L1868–1888 | 容器首次完成布局 | `injectButton()` |
| C11 | `scheduleInject()` 20 轮重试阶梯 | L1541（`MAX_ATTEMPTS=20`，延迟递增至 800ms） | 注入未成功 | `injectOrListen()` → `handOverToFocusedWindow()` |
| C12 | `handOverToFocusedWindow()` + `scheduleDelayedHandOver()` | L1633 / L1609 | 瞬时宿主销毁、20 轮失败 | 独立预算 **60 × 200ms = 12s**，反复 `findFocusedActivity()` 反射 |
| C13 | `probeForAppInfoPage()` + `scheduleProbe()` | L824 / L857 | 多来源调用，含 2 × 220ms 重投 | `resolvePkgFromContent()` → `injectButton()` |
| C14 | `tryDirectInjectOnFocus()` | L772 | 焦点 / onResume 快路径 | `injectOrListen()` → 失败转 `probe("fastpath")` |
| C15 | `Activity.onDestroy()` | L714 | Activity 销毁 | `dismissListDialog()` + `clearBarHost()`（清理，非注入） |
| C16 | `injectButton()` | L1926 | 上述任一链路到达 | **唯一真正落视图的出口** |
| C17 | 冻结状态写入出口 | `toggle()` L2579 / `undoFreeze()` L2422 | 用户动作 | `pmsSetEnabled()` L2876 |
| C18 | 解冻状态写入出口 | `unfreezeFromList()` L2514 / `toggle()` L2579 | 用户动作 | `pmsSetEnabled(..., ENABLED)` |

**辅助机制同样呈分散状态：**

- 去重 **4 套**：`FROZEN_TAG` L81、`FROZEN_BAR_TAG` L82、`BAR_HOSTS` WeakHashMap L3075、`isBarAlreadyInWindow()` L3045。
- 页面判定 **3 套**：`isAppInfoHost()` 类名白名单 L437、`containsForceStopLabel()` 视图树文本扫描 L505、`recallPkg()` 记忆包名 L530（60s；`tryDirectInjectOnFocus()` L785 与 `resolvePkgFromContent()` L933 放宽到 **300s**）。
- 包名解析 **8+ 处**级联：`resolveTargetPackage` L1394、`findPackageInExtras` L1246、`extractPkgFromFields` L599、`scanFragmentForPkg` L672、`describeFragment` L1083、`describeViewForPackage` L1109、`resolvePkgFromContent` L895、URI 分支 L1400–1416。

---

## 二、入口过多且分散带来的问题

1. **重复注入与误注入已被历史反复验证。** CHANGELOG 记录了三次同源回归：L278「设置首页误显示冻结栏」、L290「按钮在设置所有页面误显示」、L300「操作栏重复注入」。根因分别是：交接目标误取设置首页、记忆包名被任意页面借用、DecorView 与 `android.R.id.content` 两条注入路径互不可见。现有 4 套去重是逐次打补丁的结果，任何新增链路漏检其一即复发。
2. **二级菜单误入的结构性成因未消除。** `isAppInfoHost()` 明确拒绝 `SubSettings`，但内容兜底只凭「强行停止」文本即可放行；叠加 300 秒记忆窗口，用户离开详情页后在其他设置二级页仍可能看到引用旧包名的冻结栏。**看到的页面与将要被冻结的包可能不是同一个**，这是最危险的一类认知错误。
3. **高频回调上的重负载。** `performTraversals` 被 hook 两份（C8/C9），每次布局都执行深度 12、每层最多 200 子节点的视图树文本扫描，再叠加反射字段扫描；失败时 20 轮重试 + 60 轮交接可占满约 12 秒持续轮询。滚动应用信息页即触发，属于可感知的功耗与卡顿来源。
4. **安全校验不统一。** `criticalReason()`（L2900，拦截关键包 / 桌面 / 输入法 / 前台）**只在 `toggle()` 中被调用**；`undoFreeze()` 直接写 `DISABLED_USER`，绕过全部校验。新增「冻结系统应用」开关后，若保留该缺口，所有启用前警示与二次确认都可被绕过。这是本次必须优先处理的一致性问题。
5. **用户认知负担。** 同一动作分散在 4 个控件（A1/A3/A4/A6）与 9 条系统路径（B1–B9），且栏在应用信息页、应用管理、二级菜单都可能出现；分身场景仅靠 `[分身]` 前缀区分作用用户。用户难以回答「我现在冻的是哪个包、哪个用户」。
6. **维护与验证成本。** 任一改动需核对 10+ 调用点与 3 套页面判定；`TRACE onCreate`（L228）无条件打印造成日志洪水；每次注入链调整都依赖真机按「中文 / 英文 / 分身 / 多路径」逐项回归。当前一加 13 不可用，正是这一成本的具体体现。

---

## 三、精简与整合方案

**目标架构：** 多信号源 → **单一 `InjectionController` 仲裁** → 唯一出口 `injectButton()`；动作侧 **单一 `performFreeze()` 门面** → `pmsSetEnabled()`。

| 处置 | 入口 | 具体动作 | 理由 |
|---|---|---|---|
| **保留** | C7 `onWindowFocusChanged(true)` | 定为唯一主触发信号 | 触发时内容已测量，跨 OEM 最可靠（L373–376 已论证） |
| **保留** | C8 `performTraversals` | 定为唯一兜底，且仅在 decor 尺寸变化或首次布局时仲裁 | 覆盖没有焦点回调的 ROM；删掉 C9 `doTraversal` 重复注册 |
| **保留（降级）** | C6 `onNewIntent` | 只负责令当前页失效（generation++）、清 dedup，**不再直接注入** | 换页语义需要，但注入应交由控制器 |
| **保留** | C15 `onDestroy`、C1/C2 双 Xposed 框架入口 | 清理与框架兼容 | 不同 LSPosed 构建依赖不同入口（L73–75） |
| **合并** | C3/C4/C5/C7/C10/C11/C12/C13/C14 | 全部改为投递一次 `requestInject(activity, reason)`；控制器统一执行「内容门 → 包解析 → 去重 → 单一重试预算（建议 8 × 150ms，信号到达即取消）→ 一次注入」；12 秒 handover 与 20 轮阶梯合并为一个预算 | 消除 8 条并发链路与 4 套去重，杜绝重复/误注入的结构性来源 |
| **合并** | 8+ 处包名解析 | 收敛为 `PackageResolver.resolve(activity, decor)`，内部按优先级级联，外部只调用一次 | 现为各链路各自实现一套子集，行为不一致且难以验证 |
| **合并** | 4 套去重 | 收敛为 per-Activity `InjectionState`（generation / pkg / userId / IDLE·PENDING·INJECTED），保留 1 个视图 tag 供外部识别 | 状态单一、可观测、可测试 |
| **下沉** | A3「❄ 已冻结 N」常驻徽标 | `N=0` 时隐藏；「查看已冻结列表」下沉到长按菜单 | 页面只留一个常驻主按钮；计数信息在 `N>0` 时仍保留 |
| **下沉 / 合并** | A2 长按诊断 | 升级为**统一次级菜单**：诊断信息 / 已冻结应用列表 / 冻结设置（含系统应用开关） | 一个二级入口承载全部辅助能力，与 A3 下沉对齐 |
| **移除** | C9 `doTraversal` | 删除重复 hook | 与 C8 完全等价，纯开销 |
| **合并（本次已落地）** | A6 Toast「撤销」 | 保留入口，但强制改走 `performFreeze()` 门面；是否彻底移除可作为后续产品简化项 | 依赖隐藏 API、多数 ROM 无效，且曾是唯一绕过安全校验的冻结路径；纳入门面即可消除风险，不必在本次改动中删除行为 |
| **移除** | C12 独立 12s 预算、300s 记忆窗口 | 并入控制器；记忆窗口收紧到 **8s 且绑定同一 decor/window 身份** | 消除「离开详情页仍借用旧包名」的误注入 |
| **移除（调试）** | C3 无条件 `TRACE`、`dumpUnknownActivity()` 全量 dump | 改为 debug 开关，默认关闭 | 降低日志洪水与反射开销 |

**页面判定收紧（防二级菜单误入）：** 必须同时满足 ① 宿主类名白名单 **或** 页面含「强行停止」动作；② 包名来自**当前页面**的 Intent / 字段 / 视图（记忆值仅在同窗口身份、8s 内允许）；③ 通用 `SubSettings` / `homepage` / `applist` / `dashboard` 默认不放行，仅在用户显式开启「兼容模式」时才允许内容兜底。

**动作侧统一：** 冻结唯一门面 `performFreeze(activity, pkg, btn)`，强制依次执行 `criticalReason()` → 系统应用开关校验 →（系统应用）二次确认 → `pmsSetEnabled()` → 回读校验 → 刷新标签与角标。A1、A6（若保留）、未来任何入口都必须经过该门面；解冻保持单一 `performUnfreeze()`。

**建议落地顺序与验收：** ① 先做动作侧统一（低风险，本次已随新功能落地）→ ② 引入控制器但保留旧链路为可切换开关 → ③ 真机逐项回归后移除旧链路。验收标准：设置首页 / 应用列表 / 任意二级菜单不出现栏；详情页 100% 出现且仅一条栏；布局遍历回调次数与日志量显著下降；中文 / 英文 / 分身 / B1–B4 四条路径冒烟通过。

> ⚠ **本次不落地注入链重构。** 该重构属于高风险行为变更，必须真机按上述用例回归；用户已明确一加 13 正用于其他调试，故只输出方案，待设备可用后执行。

---

## 四、新增功能：冻结系统应用

### 4.1 现状与安全缺口

基线版本 `criticalReason()` **未区分系统应用**。README L253 只说明「`DISABLED_USER` 对系统应用通常会被 `SecurityException` 拦截」，但模块运行在**系统设置进程**（持有系统签名权限）内，实际上部分系统应用可能被成功停用。也就是说：**现有版本可能在毫无警示的情况下冻结系统应用**。新增功能的首要目标是「默认收紧 + 显式授权」，其次才是「在有能力冻结的 ROM 上开放能力」。

### 4.2 功能范围

- **系统应用定义**：`ApplicationInfo.FLAG_SYSTEM` 或 `FLAG_UPDATED_SYSTEM_APP`（`/system` 分区或厂商预置应用）。
- **默认状态：关闭。** 关闭时系统应用一律拒绝冻结，主按钮置灰并在无障碍描述中说明原因。
- **开启后允许的冻结范围**，需同时满足全部条件：
  1. 不在 `CRITICAL_PKGS` **硬白名单**（模块自身、`android`、Settings、SystemUI、shell、安装器、SettingsProvider、下载/媒体提供器、权限控制器、Play 商店、GMS 等，并补充电话/通信/蓝牙/NFC/网络栈等核心组件）——**该名单永不可通过开关解禁**；
  2. 不是当前桌面启动器、不是已启用输入法、不是前台运行中应用（沿用 `criticalReason()` 动态检查）；
  3. 不是 `ApplicationInfo.FLAG_PERSISTENT` 常驻系统进程；
  4. 不是当前页面的其他用户副本——仅作用于 `targetUserFor(activity)` 解析出的 Android user，不跨分身；
  5. 每次冻结均需单独二次确认（不提供批量冻结、不提供开机自动冻结）。

### 4.3 限制与风险

| 限制 | 说明 |
|---|---|
| 框架可能直接拒绝 | 部分 ROM 的 PMS 对系统应用拒绝 `DISABLED_USER`；已有回读校验会提示「操作未生效」，不谎报成功 |
| 依赖链风险 | 系统应用常被其他系统组件依赖，冻结后可引发频繁 FC、开机卡住、通知/推送/同步失效、账号与 OTA 更新校验失败 |
| 更新与卸载 | 系统应用有冻结状态时可能无法安装更新或卸载更新；升级前需先解冻 |
| 恢复路径 | ① 已冻结列表一键解冻；② 极端情况 `adb shell pm enable --user <userId> <包名>`；③ 最后手段恢复出厂设置 |
| 作用域 | 开关持久化在宿主 Settings 进程的 `SharedPreferences` 中，**按 Android user 独立**，分身用户需各自开启；卸载模块残留项无害 |
| 状态语义 | 仍为框架级「停用」，不删除数据、不卸载；重启后保持 |

### 4.4 启用方式

模块无独立 Activity / 启动图标，因此不新增桌面入口，而是复用并按方案**合并后的长按照次菜单**：

> 长按主按钮 →「冻结设置」菜单 → 点击「冻结系统应用：已关闭（点击开启）」开关项

- 开关默认关闭，且**不能通过单击主按钮等方式直接开启**，避免误触。
- 关闭开关时**不自动解冻**已冻结的系统应用（不擅自改动系统状态），但在已冻结列表中以 `[系统]` 标记并提示可一键解冻。

### 4.5 风险提醒机制（三层）

1. **启用前 —— 强制风险确认对话框**：标题「⚠ 高风险功能」，正文逐条列明依赖链风险、OTA/通知影响、不可解禁清单、恢复命令 `adb shell pm enable --user <u> <包名>`；**必须勾选「我已阅读并了解上述风险」**，「启用」按钮才可点击（初始 disabled）。中英双语。
2. **冻结前 —— 每次逐包二次确认**：显示应用名、包名、系统应用标记、目标 user、影响说明与恢复命令；默认操作为「取消」，需显式点「仍要冻结」。不设「本次会话不再提醒」，因为该操作低频且高危。
3. **冻结后 —— 可见与可追溯**：系统应用按钮使用**橙色**背景与无障碍前缀「系统应用」（不增加按钮文字长度，避免已修复的英文溢出回归）；列表行加 `[系统]` 前缀；Toast 明确提示「可在已冻结列表一键解冻」；事件写入 LSPosed 日志。

此外，所有冻结动作统一进入 `performFreeze()` 门面，硬性执行 `criticalReason()` + 系统应用开关 + 二次确认，**堵住 A6 撤销路径的绕过缺口**。

---

## 五、本次落地范围与验证口径

| 项目 | 状态 |
|---|---|
| 入口清单、问题分析、精简整合方案 | 本文档（设计产出，**不改动注入链**） |
| 冻结系统应用（默认关闭 + 开关 + 三层警示 + 视觉区分） | **已实现**，见 `CHANGELOG.md` v1.0.3 |
| 动作侧统一（A6 撤销纳入门面；解冻不再被保护误拦） | **已实现**；两处解冻写入点尚未合并为 `performUnfreeze()`，属方案后续项 |
| 注入链整合（保留 / 合并 / 下沉 / 移除） | **仅方案，未落地** —— 需真机回归，当前设备不可用 |
| 版本号 / Manifest / SUMMARY 元数据 | 已同步至 1.0.3 (103) |
| 本地验证 | `bash build.sh` 编译 / d8 / aapt2 / 签名全链路 + 版本一致性 + `xposeddescription` + dex 校验 |
| 真机验证 | **未执行**。用户明确一加 13 正在调试其他软件，本次不连接任何 adb 设备 |
| 待真机回归项 | 中文 / 英文 / 分身三种语言与用户路径；禁用→启用开关流程；系统应用二次确认；已冻结列表 `[系统]` 标记；B1–B4 四条进入路径是否仍只出现一条栏 |
