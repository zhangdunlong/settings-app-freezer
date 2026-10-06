# settings-app-freezer

> 在系统「设置 → 应用信息」页注入 **冻结 / 解冻** 按钮，一键停用任意用户应用。
> 为 **LSPosed + ColorOS / RealmeUI / 现代 Android** 重新实现。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed-9E4B00.svg)](https://github.com/LSPosed/LSPosed)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com/)
[![Release](https://img.shields.io/github/release/zhangdunlong/settings-app-freezer.svg)](https://github.com/zhangdunlong/settings-app-freezer/releases)

---

## 简介

Android 8 之后，官方设置里的「停用」入口被移除，想冻结应用通常得靠 `pm disable-user`、
Titanium Backup 这类外部工具，操作繁琐且容易误伤系统组件。

本项目是一个 **Xposed / LSPosed 模块**：它会在每个应用的「应用信息」页面右下角
注入一个浮动按钮，点一下即可冻结（`DISABLED_USER`）或解冻该应用。冻结后应用会从
桌面与启动器消失，数据保留，随时可解冻恢复。

与老一代冻结类模块的关键差异：

| 维度 | 本项目 |
|---|---|
| 识别方式 | 纯 Intent 判定 + **视图布局信号**，不 hook 任何厂商内部类名 |
| 厂商适配 | ColorOS / RealmeUI / OPPO / OnePlus 两段式页面已处理 |
| 安全性 | 动态识别并**拒绝冻结**桌面、输入法、系统关键包与前台应用 |
| 构建 | 纯命令行链路，**无需 Android Studio / Gradle** |

---

## 功能特性

- **一键冻结 / 解冻** —— 应用信息页底部浮动按钮，状态一目了然
- **已冻结应用列表** —— 底部并排的「❄ 已冻结 N」入口，一眼看清冻结了哪些应用，点击列表行即解冻
- **ROM 无关的页面识别** —— 通过 `ACTION_APPLICATION_DETAILS_SETTINGS` 的 Intent
  与窗口布局完成信号定位页面，不依赖 `com.android.settings` 内部类名
- **深层安全保护** —— 自动拒绝冻结系统关键包、当前桌面启动器、当前输入法、
  前台运行中的应用，避免手滑变砖
- **冻结状态可视化** —— 未冻结为青色 `#1AA6C4`，已冻结为薄荷绿 `#3FBFA8`
- **长按诊断** —— 长按按钮显示目标包名、启用状态、是否系统应用、是否被保护
- **自适应注入** —— 视图树布局完成 + 20 轮短延迟重试，适配各家 ROM 的加载时序
- **零 Gradle 构建** —— `bash build.sh` 即可产出已签名 APK

---

## 运行要求

| 项目 | 要求 |
|---|---|
| Android | 8.0 (API 26) 及以上 |
| Xposed 框架 | **LSPosed**（含 Zygisk / NeoZygisk 环境） |
| 权限 | Root（LSPosed 依赖 Zygisk 注入） |
| 已验证设备 | OnePlus PJZ110 / Android 16 (API 36) / ColorOS |
| 兼容范围 | 已覆盖 `com.android.settings` 及 ColorOS / OPPO / Realme / OnePlus 设置包 |

> ⚠️ 本项目需要 Root 与 Xposed 框架。仅在你自己的设备上使用，并遵守当地法律法规。

---

## 安装与使用

### 1. 安装 APK

从 [Releases](https://github.com/zhangdunlong/settings-app-freezer/releases) 下载
`freezer-module.apk`，传到手机安装（需允许「安装未知来源应用」）。

或用 adb：

```bash
adb install -r freezer-module.apk
```

### 2. 在 LSPosed 中启用

1. 打开 **LSPosed Manager → 模块**，勾选 **settings-app-freezer**
2. 进入模块 → **作用域**，勾选你的设置应用（通常至少包含 `com.android.settings`）
3. **重启手机** —— LSPosed 在开机时把作用域读入内存，仅强停设置进程不生效

### 3. 使用

打开 **设置 → 应用 → 任意应用 → 应用信息**，页面右下角出现浮动按钮：

| 应用状态 | 按钮显示 | 颜色 |
|---|---|---|
| 未冻结 | ❄ 冻结此应用 | 青色 `#1AA6C4` |
| 已冻结 | ❄ 已冻结 · 点此解冻 | 薄荷绿 `#3FBFA8` |

- **点击** —— 冻结 / 解冻，Toast 显示结果
- **长按** —— 显示诊断信息（包名 / 状态 / 是否系统应用 / 保护原因）

#### 查看已冻结的应用

设置里应用很多时，不必逐个进应用信息页翻找。应用信息页底部并排的
**「❄ 已冻结 N」** 按钮即为入口：

- 角标 `N` 是当前已冻结应用数量实时计数，冻结/解冻后自动刷新
- 数量为 `0` 时按钮置灰，点击提示「当前没有已冻结的应用」
- 点击弹出列表：每行显示 **应用图标 + 应用名 + 包名**
- **点击任意一行即解冻该应用**，并从列表中移除
- 只统计 `DISABLED_USER`（用户主动冻结），不含系统出厂禁用的应用

### 排查问题

优先查看 LSPosed 持久化日志（比 `logcat` 可靠，buffer 循环太快）：

```bash
adb shell su -c 'cat /data/adb/lspd/log/modules_*.log | grep Freezer | tail -50'
```

或实时抓取：

```bash
adb logcat -v threadtime | grep FreezerModule
```

关键日志含义：

| 日志 | 含义 |
|---|---|
| `initZygote, module loaded` | 模块已加载。**缺失**说明作用域未生效或类初始化失败 |
| `hooked ViewRootImpl.performTraversals` | 布局监听已注册（主力注入路径） |
| `button injected for xxx` | **注入成功** |
| `GAVE UP injecting button` | 20 轮重试仍失败，请提交 Issue 并附日志 |
| `blocked freeze of xxx (桌面启动器)` | 安全保护生效，属预期行为 |

---

## 从源码构建

### 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 (Temurin) | 提供 `javac` / `jar` / `keytool` |
| Android SDK Platform | `android-36` | 提供 `android.jar` |
| Android Build-Tools | 36.x | 提供 `aapt2` / `d8` / `zipalign` / `apksigner` |
| Xposed API | `api-82.jar` | LSPosed 的 Xposed API 桩，仅编译期使用 |
| Python | 3.x | 用于把 `classes.dex` 注入 APK |

`api-82.jar` 可从 Maven 镜像获取（`de.robv.android.xposed` 构件），或从
[Xposed-API](https://github.com/libxposed/api) 仓库获取。

### 目录布局

`build.sh` 按以下约定查找工具链（也可用环境变量覆盖）：

```
<任意目录>/
├── jdk/                    # JDK 17
├── android-sdk/            # Android SDK
│   ├── platforms/android-36/
│   └── build-tools/36.0.0/
├── lib/api-82.jar          # Xposed API 桩
├── debug.keystore          # 签名密钥（自动生成，已被 .gitignore 排除）
└── FreezerModule/          # ← 本项目
```

或直接指定环境变量：

```bash
JDK_HOME=/path/to/jdk17 \
ANDROID_SDK_ROOT=/path/to/android-sdk \
XPOSED_API_JAR=/path/to/api-82.jar \
bash build.sh
```

### 构建

```bash
bash build.sh
```

输出：`freezer-module.apk`（已用 debug 密钥签名，v1/v2/v3 签名方案均通过）。

正式发布请提供自己的签名密钥（**不要提交到仓库**）：

```properties
# keystore.properties（已被 .gitignore 排除）
KEYSTORE_PATH=/path/to/release.keystore
KEYSTORE_ALIAS=myalias
KEYSTORE_PASS=yourpassword
```

---

## 目录结构

```
settings-app-freezer/
├── src/com/zdl/freezer/
│   └── FreezerModule.java      # 模块全部逻辑（hook / 注入 / 冻结 / 保护）
├── assets/
│   └── xposed_init             # LSPosed 入口声明
├── lib/
│   └── add_dex.py              # 构建辅助：把 classes.dex 注入 base.apk
├── AndroidManifest.xml         # 包名、版本号、Xposed 元数据
├── build.sh                    # 命令行构建流水线
├── version.properties          # 版本号唯一来源（语义化版本）
├── CHANGELOG.md                # 版本变更记录
├── RELEASE_NOTES.md            # Release 正文
├── LICENSE                     # GPL-3.0
├── README.md
└── .gitignore
```

---

## 版本号约定

采用 [语义化版本](https://semver.org/lang/zh-CN/)：

```
MAJOR.MINOR.PATCH    →  version.properties: VERSION_NAME
                      →  AndroidManifest.xml: android:versionName
MAJOR×10000+...      →  AndroidManifest.xml: android:versionCode
```

`version.properties` 是唯一来源，`build.sh` 读取后回显；构建产物文件名不含版本号
（保持 `freezer-module.apk` 便于 LSPosed 识别）。

---

## 已知限制

- **需要 Root + LSPosed**，且 LSPosed 作用域改动后必须重启手机
- **系统应用通常无法冻结** —— `DISABLED_USER` 对系统应用需要更高权限，会被
  `SecurityException` 拦截
- **加固应用可能自我拉活** —— 部分应用带自保护机制，冻结后仍可能被重新拉起
- **冻结不等于删除** —— 采用框架级「停用」语义，应用数据完整保留
- **ColorOS 特定适配** —— 已处理两段式页面（`InstalledAppDetailsTop` 为空壳，
  真正渲染的是父页 `InstalledAppDetails`）；其他 ROM 的首次适配请提交 Issue
  并附 LSPosed 日志
- **分身（App Twin）需页面携带 user 标识** —— 分身与原版是**同一包名、
  不同 Android user**。本模块按应用信息页的 Intent / 字段解析目标 user，
  但若厂商的详情页完全不带 user 线索，则无法自动区分，需手动指定作用域
- **部分 ROM 的应用列表页可能无法识别** —— 「设置 → 应用 → 应用列表 → 点应用」
  这条路径的包名传递方式依赖厂商实现，少数机型可能不显示按钮
- **无法视觉验证的场景** —— 若设备处于锁屏状态，模块无法自证按钮已渲染，
  需解锁后人工确认

---

## 贡献

欢迎提交 Issue 与 Pull Request。

**提交 Issue 请附：**

- 设备型号、Android 版本、ROM 名称与版本
- Xposed 框架版本（LSPosed 版本号）
- LSPosed 日志中包含 `FreezerModule` 的部分：

  ```bash
  adb shell su -c 'grep Freezer /data/adb/lspd/log/modules_*.log | tail -50'
  ```

**开发约定：**

- 保持 ROM 无关性 —— 不要 hook 任何厂商内部类名
- 静态字段初始化器**禁止**调用 Android 运行时 API（`Handler` / `Looper` /
  `Context` / 系统服务），否则会在 zygote 阶段 NPE 导致模块静默失效
- 新增日志请用 `XposedBridge.log` 并统一 `TAG` 前缀
- 改动后请同步更新 `version.properties`、`CHANGELOG.md`

---

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) —— 现代 Xposed 框架
- [Xposed API](https://github.com/libxposed/api) —— 模块 API 定义
- 原版思路参考早期社区冻结类模块（其 hook 点在 Android 8+ 已失效，本项目为完全重写）

---

## 联系方式

- 作者：**zhangdunlong**
- GitHub：<https://github.com/zhangdunlong>
- Issue：<https://github.com/zhangdunlong/settings-app-freezer/issues>

## 许可证

[GNU General Public License v3.0](LICENSE)

模块链接的 Xposed API（`de.robv.android.xposed.*`）由其作者按 Apache License 2.0
独立分发，使用该接口不影响本项目源码的 GPL-3.0 条款。
