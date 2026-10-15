# settings-app-freezer v1.0.1

> **强烈建议升级** —— 本版修复了一个会**误冻结原版应用**的严重缺陷。

---

## 本版修复

### 冻结分身应用时误冻结原版（严重）

在「设置 → 应用信息」页对**分身应用**点击冻结时，模块会：

- ❌ 把**原版**应用冻结（而不是分身）
- ❌ 同时报告「操作未生效」，让人以为什么都没发生

也就是说，你以为只是冻结了分身，实际被停用的是主力账号里的那个应用。

#### 根因

Android 公开 API 的重载签名是：

```java
PackageManager.setApplicationEnabledSetting(String packageName, int newState, int flags)
//                                                                        ↑ 不是 userId
```

第三个参数是 **flags**。旧实现按「用户 id」传入：

```java
pm.setApplicationEnabledSetting(pkg, state, cloneUserId);   // 语法合法，语义全错
```

用户 id 被静默塞进 flags 槽位，调用依然作用于**调用方所在的用户（0，即原版）**。

读取路径同样失效：公开 API 没有 `getApplicationEnabledSetting(String, int)`，
旧代码的反射调用抛 `NoSuchMethodException`，状态回读恒为默认值 ——
所以界面完全无法反映分身的真实状态，也就无法及时发现写错了目标。

#### 修复方式

改为通过**用户域 Context** 取得绑定到目标用户的 `PackageManager`：

```java
Context userCtx = ctx.createPackageContextAsUser(ctx.getPackageName(), 0, UserHandle.of(userId));
userCtx.getPackageManager().setApplicationEnabledSetting(pkg, state, 0);
```

用户由 Context 决定，之后读写都用普通公开 API，不再依赖易变的隐藏接口签名。

并且加了硬性保护：**拿不到目标用户的 PackageManager 时直接抛异常拒绝执行**，
绝不回退到调用方作用域 —— 宁可操作失败，也不能改动错误的用户。

#### 实测对比（OnePlus PJZ110 / Android 16 / ColorOS，微信分身 user 999）

| 用户 | 操作前 | v1.0.0 | v1.0.1 |
|---|---|---|---|
| 0（原版） | `enabled=1` | **`enabled=3` 被误冻结** | `enabled=1` ✅ |
| 998 | `enabled=1` | `enabled=1` | `enabled=1` ✅ |
| 999（目标分身） | `enabled=1` | `enabled=1` 没冻上 | **`enabled=3`** ✅ |

### 其他

- `undoFreeze()`（Toast 撤销）改为使用页面所属用户，原实现固定用当前进程用户，
  在分身页面会指回原版
- `build.sh` 增加版本一致性校验：`version.properties` 与 `AndroidManifest.xml`
  不一致时直接构建失败（APK 版本实际取自 Manifest，此前无任何机制防止两者漂移）

---

## 升级前请自查

如果你在 **v1.0.0** 上使用过「分身冻结」，请检查对应**原版应用**是否被误冻结：

- 系统设置 → 应用 → **已停用应用**
- 或本模块应用信息页底部的 **「❄ 已冻结 N」** 列表（点击任意行即可解冻）

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

**完整变更**：<https://github.com/zhangdunlong/settings-app-freezer/compare/100-1.0.0...101-1.0.1>
