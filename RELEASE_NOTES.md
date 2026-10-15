# settings-app-freezer v1.0.2

界面文案现在**跟随系统语言**自动切换中文 / 英文。

---

## 本版内容

### 新增：界面文案跟随系统语言（中 / 英）

模块的全部界面此前固定为中文。现在会按宿主语言显示对应文案，覆盖：

- 操作栏按钮（冻结 / 解冻 / 已冻结列表入口）
- 「已冻结应用」列表弹窗（含空状态）
- 全部操作提示（冻结 / 解冻 / 撤销 / 各类失败原因）
- 长按诊断信息
- 读屏（TalkBack）朗读内容

| | 中文环境 | 英文环境 |
|---|---|---|
| 按钮 | `[分身] 冻结此应用` | `[Clone] ❄ Freeze this app` |
| 已冻结 | `❄ 已冻结 0` | `❄ Frozen 0` |
| 空列表 | `暂无已冻结应用` | `No frozen apps` |
| 操作结果 | `已冻结 微信` | `Frozen WeChat` |

**关于语言来源**：语言取自宿主进程的 `Locale.getDefault()`。Android 会把该进程所属应用的
语言配置写入进程默认值，所以在 Android 13+ 上**按应用设置语言**也有效 —— 例如系统是中文、
只把「设置」改成英文时，模块会跟着「设置」显示英文，与用户眼前的界面一致。

**刻意不翻译的部分**：用于识别系统界面的关键词（`强行停止` / `Force stop`）保持原样。
它们必须匹配系统实际显示的文案，翻译了反而会让页面识别失效 —— 这也是英文系统下
模块能正常工作的前提。

### 变更：说明文案改为中英对照

以下三处统一为 `中文 · English` 格式：

- **LSPosed 里显示的模块描述**（`AndroidManifest` 的 `xposeddescription`）
- **模块索引页简介**（`SUMMARY`）
- **GitHub 仓库描述**

模块显示名 `设置内冻结 · Settings App Freezer` 本就是双语，未改动。

---

## 真机验证（OnePlus PJZ110 / Android 16 / ColorOS）

| 场景 | 结果 |
|---|---|
| 中文（系统默认） | `[分身] 冻结此应用` / `❄ 已冻结 0` |
| 英文（仅「设置」应用设为 en-US） | `[Clone] ❄ Freeze this app` / `❄ Frozen 0` |
| 英文下页面识别 | 按钮正常出现（系统 UI 显示 `Force stop`） |
| 英文下冻结分身 | 仅目标分身 user 受影响，原版不受影响 |
| 英文下解冻分身 | 恢复正常，日志 `Unfrozen WeChat want=ENABLE readback=1` |
| 英文下空列表弹窗 | `No frozen apps` |

---

## 安装

1. 下载下方 `freezer-module.apk` 覆盖安装
2. LSPosed Manager → 模块 → 勾选 **settings-app-freezer**
3. 作用域**必须同时勾选**以下两项：

   | 作用域 | 缺失后果 |
   |---|---|
   | **系统框架**（`system`） | 模块开机时不注入，任何页面都不会有按钮 |
   | **设置**（`com.android.settings`） | 模块能加载，但不会 hook 任何设置进程 |

   > ⚠️ 缺任意一项都不生效，且**没有任何界面提示**。

4. **重启手机**

## 运行要求

- Android 8.0 (API 26) 及以上
- LSPosed（Zygisk / NeoZygisk 环境）+ Root
- 已验证：OnePlus PJZ110 / Android 16 (API 36) / ColorOS

## 相关链接

- 源码：<https://github.com/zhangdunlong/settings-app-freezer>
- 变更日志：<https://github.com/zhangdunlong/settings-app-freezer/blob/main/CHANGELOG.md>
- 许可：GPL-3.0

---

**完整变更**：<https://github.com/zhangdunlong/settings-app-freezer/compare/101-1.0.1...102-1.0.2>
