# 推送到 GitHub — 操作手册

本地仓库已就绪（`C:\android-build\FreezerModule`），**还差远程建仓与推送**。
本文档是你需要执行的全部命令，按顺序照做即可。

---

## 当前本地状态

| 项 | 值 |
|---|---|
| 仓库路径 | `C:\android-build\FreezerModule` |
| 分支 | `main` |
| 提交数 | 2 |
| 标签 | `v1.0.0`（已创建，指向首个提交） |
| 目标仓库 | `https://github.com/zhangdunlong/settings-app-freezer` |
| 可见性 | Public |
| `.gitignore` 已排除 | `out/`、`*.apk`、`*.idsig`、`*.keystore`、`keystore.properties`、`local.properties`、IDE 目录 |

提交列表：

```
c1cad0a  fix(ui): 按钮改用 TextView 自绘，修复主题吃掉文字只显示色框
d15846f  feat: settings-app-freezer v1.0.0 — 在设置应用信息页注入冻结/解冻按钮
```

> ⚠️ 标签 `v1.0.0` 目前指向**首个提交** `d15846f`。因为第二个提交修复了按钮文字
> 的问题，正式发布时建议把标签移到 `c1cad0a`（见下方步骤 6）。

---

## 前置：安装 gh 命令行（推荐）

未安装 `gh`。推荐用它完成建仓与 Release，可避免手动处理令牌。

```bash
winget install --id GitHub.cli
```

安装后重新打开终端，验证：

```bash
gh --version
```

---

## 步骤 1：登录 GitHub

```bash
gh auth login
```

交互选项依次选：

| 问�� | 选什么 |
|---|---|
| GitHub host | `GitHub.com` |
| Authentication protocol | `HTTPS` |
| Authenticate Git with your GitHub credentials? | `Y` |
| Login method | `Login with a web browser` |

复制它给出的一次性验证码，浏览器会自动打开，粘贴后授权完成。

验证：

```bash
gh auth status
```

> 备选方案：若不想装 `gh`，可用 Personal Access Token。
> 在 <https://github.com/settings/tokens> 生成，勾选 `repo` 权限，
> 推送时输入用户名 + Token（Token 输入时终端不回显，属正常）。

---

## 步骤 2：创建远程仓库并关联

```bash
cd C:\android-build\FreezerModule

gh repo create settings-app-freezer \
  --public \
  --description "在系统设置的应用信息页注入冻结/解冻按钮的 Xposed 模块（LSPosed + ColorOS/RealmeUI）" \
  --source=. \
  --remote=origin
```

这条命令会**自动完成三件事**：在 GitHub 建仓、添加 `origin` 远程、推送当前分支。

验证：

```bash
git remote -v          # 应显示 origin 指向 zhangdunlong/settings-app-freezer
gh repo view --web     # 浏览器打开仓库页面
```

---

## 步骤 3：确认推送结果

```bash
git status              # 应显示 working tree clean
git log --oneline -2
```

打开 <https://github.com/zhangdunlong/settings-app-freezer> 确认文件都在。

---

## 步骤 4：补齐仓库元信息（可选但推荐）

### 4.1 Topics（让项目能被搜索到）

```bash
gh repo edit --add-topic xposed \
             --add-topic lsposed \
             --add-topic android \
             --add-topic coloros \
             --add-topic realmeui \
             --add-topic app-freezer \
             --add-topic chinese
```

### 4.2 About / Description

```bash
gh repo edit --homepage "https://github.com/zhangdunlong/settings-app-freezer" \
             --enable-issues \
             --enable-wiki
```

---

## 步骤 5：构建产物是否随 Release 发布

`.gitignore` 已排除 `freezer-module.apk`，所以**仓库里没有 APK**，需要作为
Release 附件上传。

先在本地构建（产物已在 `C:\android-build\FreezerModule\freezer-module.apk`）：

```bash
cd C:\android-build\FreezerModule
bash build.sh
```

> 若 `build.sh` 报找不到 `PYTHON`，先执行：
> `export PYTHON="C:/Users/zdl/.workbuddy/binaries/python/versions/3.13.12/python.exe"`

---

## 步骤 6：把标签移到最新提交

因为 UI 修复在第二个提交，正式发布的 `v1.0.0` 应对齐最新代码。

```bash
cd C:\android-build\FreezerModule

git tag -d v1.0.0
git tag -a v1.0.0 -m "v1.0.0 — 设置内冻结应用按钮"
git push origin main
git push origin v1.0.0
```

---

## 步骤 7：创建 Release

仓库已备好发布正文 `RELEASE_NOTES.md`，直接用：

```bash
cd C:\android-build\FreezerModule

gh release create v1.0.0 \
  --title "v1.0.0 — 设置内冻结应用" \
  --notes-file RELEASE_NOTES.md \
  freezer-module.apk
```

这条命令会创建 Release 并把 APK 作为附件上传（Asset 名为 `freezer-module.apk`）。

可选：加 `--prerelease` 标记为预发布，或 `--latest=false` 不标记为 Latest。

验证：

```bash
gh release view v1.0.0 --web
```

---

## 步骤 8：验证完整发布链路

```bash
# 远程分支与标签
git ls-remote --heads --tags origin

# Release 列表
gh release list
```

应看到 `v1.0.0` 且带 APK 附件。

---

## 常见问题

### 推送报 `Authentication failed`

```bash
gh auth status                 # 检查是否已登录
gh auth refresh -h github.com  # 刷新令牌
```

或改用 SSH：

```bash
ssh-keygen -t ed25519 -C "zhangdunlong@users.noreply.github.com"
cat ~/.ssh/id_ed25519.pub      # 复制内容，粘贴到 GitHub → Settings → SSH keys
git remote set-url origin git@github.com:zhangdunlong/settings-app-freezer.git
git push -u origin main
```

### 推送报 `repository not found`

远程仓库未创建成功。回到步骤 2 重跑 `gh repo create`，或先在
<https://github.com/new> 手动创建空仓库（**不要**勾选
"Add a README"，避免与本地历史冲突）。

### 标签冲突

```
error: tag 'v1.0.0' already exists
```

按步骤 6 先 `git tag -d v1.0.0` 再重建。

---

## 附：不需要 `gh` 的纯 git 方式

若你不想安装 `gh`，可手动建仓后用 git 推送：

```bash
cd C:\android-build\FreezerModule
git remote add origin https://github.com/zhangdunlong/settings-app-freezer.git
git push -u origin main
git push origin v1.0.0
```

建仓请到 <https://github.com/new>，仓库名填 `settings-app-freezer`，
勾选 Public，**不要**初始化 README / .gitignore / license。

Release 则需在网页端 <https://github.com/zhangdunlong/settings-app-freezer/releases/new>
手动创建：选 `v1.0.0` 标签，标题填 `v1.0.0 — 设置内冻结应用`，
正文粘贴 `RELEASE_NOTES.md` 内容，附件上传 `freezer-module.apk`。
