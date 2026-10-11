# settings-app-freezer v1.0.0

在系统「设置 → 应用信息」页注入 **冻结 / 解冻** 按钮，一键停用任意用户应用。
为 **LSPosed + ColorOS / RealmeUI / 现代 Android** 重新实现。

---

## 这是什么

Android 8 之后官方设置里的「停用」入口被移除，冻结应用通常得靠 `pm disable-user`
或 Titanium Backup 这类外部工具。本模块把冻结能力直接放回你每天都会经过的
「应用信息」页面 —— 打开页面，右下角点一下，应用即冻结；从桌面消失，数据保留。

## 核心功能

- **一键冻结 / 解冻** —— 应用信息页右下角浮动按钮，冻结后应用从桌面消失，数据完整保留
- **状态一目了然** —— 未冻结青色，已冻结薄荷绿
- **已冻结应用列表** —— 底部「❄ 已冻结 N」入口带实时计数角标，弹窗列出全部已冻结应用（图标 + 名称 + 包名），点击任意行即解冻
- **长按诊断** —— 显示目标包名、启用状态、是否系统应用、是否被保护

## 深层安全保护

误冻系统组件会导致设备无法使用。本模块**动态识别**（非硬编码）并拒绝冻结：

- 系统关键包 —— 设置、SystemUI、权限控制器、应用商店、GMS 等
- 当前桌面启动器 —— 冻结它会让你没有桌面可用
- 当前启用的输入法 —— 冻结它会让全局无法输入
- 正在前台运行的应用

被拦截时会 Toast 明确告知原因，不会静默失败。

## 技术要点

| | |
|---|---|
| **ROM 无关** | 不 hook 任何厂商内部类名，改用 Intent 判定 + 视图布局信号 |
| **多层兜底识别** | 8 级包名解析路径 + `normalizePackage()` 归一化，最后用 `getApplicationInfo()` 硬验证 |
| **五层生命周期 hook** | `onCreate` / `onResume` / `onPostResume` / `onNewIntent` / `onWindowFocusChanged` / `ViewRootImpl.performTraversals` |
| **跨Activity 交接** | 宿主销毁时把注入转交给持焦点窗口，独立 12 秒重试预算 |
| **自适应注入** | 20 轮短延迟重试 + 一次性 `OnGlobalLayoutListener` |
| **零 Gradle 构建** | 纯命令行链路，无需 Android Studio |

### 关于 ColorOS 的两段式页面（v1.0.0 重点修复）

旧版冻结类模块在现代 ROM 上失效，主因是 ColorOS 应用信息页的真实结构：

```
com.android.settings.applications.InstalledAppDetailsTop   ← 瞬时 Activity，启动即销毁
                    ↓ 交接
com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity   ← 真正渲染，单 Activity 内换页
```

在 `onCreate` 注入时布局尚未测量（`w=0 h=0`），而 120ms 后的首次重试时宿主已经销毁 ——
**重试窗口与页面存活窗口完全错开**，按钮因此永不出现。

本版本新增 `handOverToFocusedWindow()`：宿主销毁时把注入动作转交给当前持有焦点的窗口，
并配置独立的重试预算（实测交接目标在数秒后才真正创建）。同时新增
`tryDirectInjectOnFocus()`：窗口获得焦点时内容已完成测量，直接注入，无需重试。

关键细节：换页过程中壳 Activity 会先停留在设置主页，应用信息内容需数百毫秒后才绘出。
因此「尚非应用信息页」这一判断**必须允许重试**，否则交接会在第一跳即中止 ——
这正是该 bug 反复出现的深层原因。

## 运行要求

- Android 8.0 (API 26) 及以上
- **LSPosed** 框架（Zygisk / NeoZygisk 环境）
- Root 权限
- 已验证：OnePlus PJZ110 / Android 16 (API 36) / ColorOS

## 安装

1. 下载下方 `freezer-module.apk` 安装到手机
2. LSPosed Manager → 模块 → 勾选 **settings-app-freezer**
3. 进入模块 → 作用域 → 勾选 **系统框架（system）** 与 **设置（com.android.settings）**
   —— **两项都必须勾**。缺 `system` 时模块不会在开机时注入，缺 `com.android.settings`
   时模块能加载但不会 hook 任何设置进程，**且没有任何界面提示**
4. **重启手机**（LSPosed 在开机时读入作用域，仅强停设置进程不够）
5. 打开任意应用信息页，右下角即出现冻结按钮

> 排查提示：模块装了却毫无反应，先确认作用域两项齐全，再看日志
> `adb shell su -c 'grep Freezer /data/adb/lspd/log/modules_*.log | tail -50'`。
> 完全没有 `FreezerModule` 日志 = 作用域未生效；有 hook 日志但无
> `button bar injected` = 包名未解析到。

## 已知限制

- **需要 Root + LSPosed**，作用域改动后必须重启手机
- **系统应用通常无法冻结** —— `DISABLED_USER` 对系统应用需更高权限，会被拒绝
- **加固应用可能自我拉活** —— 部分应用带自保护机制
- **冻结不等于删除** —— 采用框架级「停用」语义，数据完整保留
- **其他 ROM 首次适配** —— 需提供 LSPosed 日志协助排查
- **锁屏状态无法自证** —— 需解锁后人工确认按钮渲染

## 反馈

遇到问题请提交 Issue，并附上：

```bash
adb shell su -c 'grep Freezer /data/adb/lspd/log/modules_*.log | tail -50'
```

同时请说明设备型号、Android 版本、ROM 与 LSPosed 版本。

---

**许可**：[GPL-3.0](LICENSE) · **源码**：<https://github.com/zhangdunlong/settings-app-freezer>
