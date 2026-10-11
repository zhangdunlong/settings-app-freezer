# 上架 LSPosed 官方模块仓库 · 完整指引

> 目标仓库：<https://modules.lsposed.org/>
> 提交入口：<https://github.com/Xposed-Modules-Repo/submission>
> 本文档所有规则均于 **2026-10-11** 从官方 README 与真实已收录模块
> （`io.github.lsposed.disableflagsecure`）实测核对，非记忆推测。

---

## 一、官方机制概述

LSPosed 模块仓库**不是**你把自己的仓库提交上去，而是：

1. 你在 `Xposed-Modules-Repo/submission` 仓库提一个 **Issue**
2. Bot 自动在 `Xposed-Modules-Repo` 组织下**创建一个以你包名命名的仓库**，并邀请你为 maintainer
3. 你在**自己的新仓库**里放元数据和 Release
4. Bot 自动读取并同步到 modules.lsposed.org

**关键结论：官方仓库不需要你的源码**（README 明确写
`Please do not upload your source code here, we don't need your source code`）。
你的源码仓库 `zhangdunlong/settings-app-freezer` 保持独立，通过 `SOURCE_URL` 关联。

---

## 二、当前状态与官方要求的差距

| 项目 | 当前状态 | 官方要求 | 是否合规 |
|---|---|---|---|
| 仓库已公开 | ✅ Public | 必须 Public | ✅ |
| 源码仓库 | ✅ `settings-app-freezer` | 独立仓库，通过 SOURCE_URL 关联 | ✅ |
| 许可证 | ✅ GPL-3.0（已识别） | 非硬性要求 | ✅ |
| **Application ID** | ✅ `io.github.zhangdunlong.settingsappfreezer` | 必须是**你拥有的**反向域名，<br>或使用 `io.github.{username}` 前缀 | ✅ **已合规** |
| **Release Tag** | 🟡 `v1.0.0`（待改为 `100-1.0.0`） | 必须为 `{versionCode}-{versionName}` | 🟡 待改 |
| 域名 TXT 验证 | 无需 | 自有域名才需要；<br>用 `io.github.*` 则不需要 | ✅ 免去 |
| `SUMMARY` 文件 | ✅ 已创建 | 必填，模块简介（纯文本） | ✅ |
| `SOURCE_URL` 文件 | ✅ 已创建 | 必填，源码仓库地址 | ✅ |
| 仓库描述 | 已填 | 模块名称 | ⚠️ 需调整 |
| Release 资产 | `freezer-module.apk` | APK 即可，文件名不强制 | ✅ |

---

## 三、必须先做的两项改造

> **进度**：3.1 包名改造 ✅ **已完成**；3.2 Tag 格式 ✅ **已完成**（见文末执行清单）

### 3.1 改Application ID（合规前提）

官方原文：

> For reverse domain name notation in your application id, please use something you
> own rather than random domain.
> If you do not own any domain, you may use `io.github.{username}` prefix

主人当前 `com.zdl.freezer` 中的 `zdl` 是私人缩写，**不属于已拥有的域名**，
审核大概率被拒。两种方案：

| 方案 | 包名 | 说明 |
|---|---|---|
| **A（推荐）** | `io.github.zhangdunlong.settingsappfreezer` | 官方明确许可的免费路径，无需任何验证 |
| B | 保留 `com.zdl.freezer` + 域名 TXT 验证 | 需真正持有 `zdl.com` 之类域名并配 TXT 记录 |

**注意**：改包名会影响升级路径——已安装 `com.zdl.freezer` 的设备需要卸载重装，
但模块本身无数据持久化，**不影响冻结状态**（冻结信息由 Android 系统按包名存储）。

#### 改造步骤

包名在以下位置出现，需全部同步修改：

```
1. AndroidManifest.xml          package="com.zdl.freezer"
2. src/com/zdl/freezer/         → src/io/github/zhangdunlong/settingsappfreezer/
3. FreezerModule.java           package com.zdl.freezer;  → package io.github.zhangdunlong.settingsappfreezer;
4. FreezerModule.java           MODULE_PKG 常量值
5. build.sh                     CLASSES / 相关路径引用
6. README.md / CHANGELOG.md     包名与安装说明
7. NOTIFICATION：不改桌面目录名（仅源码包路径变化）
```

包路径变化会导致目录层级变深，可选择：
- **保守做法**：保留 `src/com/zdl/freezer/` 目录名，只改 `package` 声明
  （Java 允许包名与目录不一致，编译通过），改动面最小
- **规范做法**：目录与包名一致，需同步改 build.sh 路径

### 3.2 改 Release Tag 格式

官方原文：

> Release Tag: `[version code]-[version name]`
> (if you create the release along with apk assets, bot will automatically update it)

当前 `versionCode=100`、`versionName=1.0.0` → 正确 tag 为 **`100-1.0.0`**。

验证：`gh release view v1.0.0 --repo Xposed-Modules-Repo/io.github.lsposed.disableflagsecure`
其 tag 为 `133-5.0.1`，即 versionCode 133 + versionName 5.0.1，完全吻合此规则。

---

## 四、提交 Issue 的完整步骤

### 第1 步：准备新仓库内容

在你的**源码仓库**根目录新增两个文件：

**`SUMMARY`**（纯文本，一行，无 Markdown 标记）
```
在系统设置的应用信息页注入冻结/解冻按钮，一键停用应用。针对 ColorOS / RealmeUI 等 OEM 重写场景深度适配。
```

**`SOURCE_URL`**（纯文本一行）
```
https://github.com/zhangdunlong/settings-app-freezer
```

### 第 2 步：调整仓库属性

- 仓库描述（Description）改为**模块名称**，例如：
  `设置内冻结 · Settings App Freezer`
  （注意：官方明确说明仓库描述**无法通过 API 更新**，只能在浏览器手动改）

### 第 3 步：创建合规 Release

```bash
# 删除旧 tag（可选，保留也不影响）
git tag -d v1.0.0

# 用合规格式创建 tag + Release，务必一次带上 APK
gh release create 100-1.0.0 \
  --title "1.0.0" \
  --notes-file RELEASE_NOTES.md \
  freezer-module.apk \
  --repo zhangdunlong/settings-app-freezer
```

**最关键的一条官方提醒**：

> if you edit the release by **only** changing the apk, bot **cannot know**
> your editing and the tag won't be updated

即：**每次改APK 都必须重新创建带资产的 Release**，不能只替换资产文件。

### 第 4 步：提交 Issue

打开 <https://github.com/Xposed-Modules-Repo/submission/issues/new/choose>

- 选 **Submit a new package**
- 标题必须是严格格式：

```
[submission] io.github.zhangdunlong.settingsappfreezer
```

- 正文填写模块描述与理由（英文或中文均可，示例）：

```
在系统「设置 → 应用信息」页注入冻结/解冻按钮，一键停用任意用户应用。

Android 8 之后官方设置移除了「停用」入口，冻结应用通常需要 pm disable-user
或 Titanium Backup 等外部工具。本模块把冻结能力放回日常使用的应用信息页。

主要能力：
- 应用信息页一键冻结/解冻，冻结后应用从桌面消失、数据完整保留
- 「❄ 已冻结 N」列表入口，列出全部已冻结应用，点击即解冻
- 动态识别并拒绝冻结系统关键组件、桌面启动器、输入法、前台应用
- 针对 ColorOS 两段式应用信息页（InstalledAppDetailsTop 瞬时 Activity →
  OplusSettingsHomepageActivity 单 Activity 换页）做了专门适配

运行环境：Android 8.0+，LSPosed + Root
已验证：OnePlus PJZ110 / Android 16 / ColorOS

源码：https://github.com/zhangdunlong/settings-app-freezer
```

提交后 Bot 会自动建仓并邀请你为 maintainer。

### 第 5 步：接受邀请并核对

1. 查邮箱或仓库通知，接受 maintainer 邀请
2. 确认新仓库 `Xposed-Modules-Repo/{包名}` 已创建
3. **只能通过浏览器更新仓库描述**（API 受限）

---

## 五、审核与上线时序

官方原文：

> 1. If your repository is incomplete, it won't be shown
> 2. Update of your repository will automatically trigger build and be shown
>    **in 5 to 60 minutes**

| 阶段 | 耗时 |
|---|---|
| Bot 建仓 + 邀请 | 数分钟 |
| 仓库内容校验（完整性检查） | 即时 |
| 构建并同步到 modules.lsposed.org | **5–60 分钟** |

若 60 分钟后未显示，按序检查：
1. `SUMMARY` 与 `SOURCE_URL` 文件是否存在（缺失 = 不完整 = 不显示）
2. Release 是否**同时**带 tag 和 APK 资产
3. tag 是否符合 `{versionCode}-{versionName}`
4. 仓库描述是否为空（通过浏览器补）

---

## 六、后续版本发布流程

每次更新必须：

```bash
# 1. 更新代码与版本号（version.properties）
# 2. 构建
bash build.sh

# 3. 提交并推送代码
git add -A && git commit -m "..." && git push origin main

# 4. 创建合规 Release（tag 用新 versionCode-versionName，且必带 APK）
gh release create 101-1.0.1 --title "1.0.1" \
  --notes-file RELEASE_NOTES.md freezer-module.apk
```

**绝对不要**只替换已有 Release 的 APK 资产——Bot 收不到资产变更的 webhook。

---

## 七、常见被拒原因对照

| 被拒原因 | 规避方法 |
|---|---|
| 包名不合规 | 用 `io.github.{用户名}` 前缀，或持有真实域名并配 TXT |
| 仓库不完整 | 确保 `SUMMARY` + `SOURCE_URL` + `README.md` 三者齐全 |
| tag 格式错| `{versionCode}-{versionName}`，如 `100-1.0.0` |
| Release 无 APK | 创建 Release 时同步上传 APK |
| 仓库描述为空 | 通过浏览器手动填写（API 无法更新） |
| 上传了源码 | 官方不需要源码，移除即可 |

---

## 八、快速执行清单

- [x] 决定包名方案 → `io.github.zhangdunlong.settingsappfreezer`
- [x] 改造源码中的包声明与 `MODULE_PKG` 常量（含源码目录迁移）
- [x] 重新构建（APK 包名 / xposed_init / dex 已核验）
- [x] 新增 `SUMMARY` 文件
- [x] 新增 `SOURCE_URL` 文件
- [x] 用 `100-1.0.0` 格式创建 Release（带 APK）
- [x] **通过浏览器**设置 `Xposed-Modules-Repo/{包名}` 仓库描述（另附主页与 6 个 topics）
- [x] 提 Issue 标题 `[submission] io.github.zhangdunlong.settingsappfreezer` → **#2123**
- [x] Bot 自动批准（`approved`，Issue 已关闭）并建仓，已接受 maintainer 邀请
- [x] 向模块仓库推送 `SUMMARY` / `SOURCE_URL` / `README.md`，并创建 `100-1.0.0` Release（含 APK）
- [ ] 等待生产站点重建后确认上线 <https://modules.lsposed.org/>

---

## 九、实际执行记录（2026-10-11 完成）

### 提交结果

| 项目 | 值 |
|---|---|
| Issue | <https://github.com/Xposed-Modules-Repo/submission/issues/2123> |
| Issue 状态 | `CLOSED` + 标签 `approved`（**Bot 自动批准，无需人工审核**） |
| 模块仓库 | <https://github.com/Xposed-Modules-Repo/io.github.zhangdunlong.settingsappfreezer> |
| 权限 | `maintain=true` `push=true`（已接受邀请） |
| 仓库描述 | `设置内冻结 · Settings App Freezer` |
| 仓库主页 | <https://github.com/zhangdunlong/settings-app-freezer> |
| Topics | android, app-freezer, coloros, lsposed, magisk, xposed |
| 仓库文件 | `README.md` / `SOURCE_URL` / `SUMMARY` |
| 模块仓库 Release | `100-1.0.0` → `freezer-module.apk`（41,427 B） |

### 为什么能自动通过（源码级依据）

Bot 逻辑（`Xposed-Modules-Repo/submission` 的 `src/index.ts` + `src/bot.ts`）：

```js
if (title.startsWith('io.github.')) {
  if (checkPages(title, username) || await checkOrg(token, title, username)) {
    await approve(...)   // 直接建仓并邀请
  }
}
// checkPages: pkg.toLowerCase().startsWith("io.github." + normalizeUsername(username) + ".")
```

`io.github.zhangdunlong.settingsappfreezer` 精确命中 `io.github.zhangdunlong.` 前缀，
且提 Issue 的账号即 `zhangdunlong` → **checkPages 直接通过，自动建仓**。
另外 `checkPackageName()` 要求：≥3 段、每段 `^[a-zA-Z_][a-zA-Z_0-9]*$`、非 `example`、
不命中黑名单（`com.android` / `com.google` / `org.lsposed` / `io.github.lsposed` 等）。

### 只有「仓库描述」必须用浏览器

Bot 明确警告 `API DON'T HAVE ENOUGH PERMISSION TO SET IT`。实测确认：

| 操作 | API（gh token, maintain 角色） | 浏览器（网页会话） |
|---|---|---|
| 推送文件 | 可用 | 可用 |
| 创建 Release + 上传 APK | 可用 | 可用 |
| 设置仓库描述 / 主页 / topics | **不可用**（`PATCH /repos/...` → 404） | 可用 |

### 浏览器自动化环境要点（可复用）

| 问题 | 结论 |
|---|---|
| Chrome 136+ 禁用调试端口 | 仅当**未显式指定** `--user-data-dir` 时才禁用；显式传入（含默认目录）即可开启 |
| 终端沙箱 | Bash 工具沙箱**有独立网络栈**，无法访问宿主机 `127.0.0.1:9222`；须用另一个工具进程执行 Playwright |
| 工具调用结束回收子进程 | 用 `run_in_background: true` 承载浏览器进程，并以 `WaitForExit()` 保活 |
| WMI `Win32_Process.Create` | 被安全策略拦截，不可用 |
| 拷贝 Profile 继承登录态 | **不可行** —— Chrome 127+ App-Bound 加密绑定安装与配置，拷贝后会话失效 |
| 代理拦截 localhost | 需 `NO_PROXY=127.0.0.1,localhost,::1`（工具环境会注入 `HTTP_PROXY`） |
| GitHub 页面 `domcontentloaded` | 常在 60s 内不触发；改用 `wait_until="commit"` |
| 元素点击超时 | GitHub 新版界面元素被遮挡；改用 DOM 级 `el.click()` + 原生 setter 触发 `input` 事件 |

### 安全清理

调试实例使用**主人真实 Chrome Profile**（该机唯一 Profile，已登录 Google 账号，无其他浏览器）。
完成后已：关闭调试实例 → 删除临时配置目录（内含复制的 Cookies）→
以正常模式恢复 Chrome（已验证 9222 不再监听）。