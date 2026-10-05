# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

版本号唯一来源为 [`version.properties`](version.properties)，
`AndroidManifest.xml` 的 `versionName` / `versionCode` 与其保持一致。

---

## [Unreleased]

### 新增

- **已冻结应用列表** —— 应用信息页底部并排「❄ 已冻结 N」入口
  - 实时计数角标，冻结/解冻后自动刷新，无冻结项时置灰
  - 弹窗列出全部已冻结应用的图标、名称与包名
  - 点击列表行即解冻，并自动重建列表
  - 仅统计 `DISABLED_USER`（用户主动冻结），排除系统出厂禁用项

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
