# 第三方组件与补充声明

## 本项目自身的许可

本项目源码采用 [GNU General Public License v3.0](LICENSE) 发布。

```
Copyright (C) 2026 zhangdunlong

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

## Xposed API（独立授权）

本项目编译期链接 **Xposed API**（`de.robv.android.xposed.*`），该组件由其各自作者
单独以 **Apache License 2.0** 发布。

- 上游项目：<https://github.com/rovo89/XposedBridge>
- 许可：Apache License 2.0

使用这些接口受该许可证约束，**不改变本项目自身源码的授权条款**（仍为 GPL-3.0）。

本项目**不包含** Xposed API 的任何源代码，仅在编译期引用其公开接口。
运行时所需的框架由用户在设备上自行安装（LSPosed / Zygisk 等），本项目不分发任何框架组件。

## 构建期工具链

构建脚本 `build.sh` 调用以下工具，均**不随本仓库分发**，需由使用者自行安装：

| 工具 | 用途 | 许可 |
|---|---|---|
| JDK 17 | 编译 Java 源码 | OpenJDK GPLv2+CE |
| Android SDK（`android.jar` / `aapt2` / `d8` / `apksigner` / `zipalign`） | 构建与打包 APK | Android SDK Terms |
| LSPosed Xposed API stubs（`api-82.jar`） | 编译期桩代码 | Apache 2.0 |
| Python 3 | 运行 `lib/add_dex.py` | PSF License |
This project links against the Xposed API (de.robv.android.xposed.*), which is
distributed separately under the Apache License 2.0 by its respective authors.
Use of those interfaces is governed by that license and does not alter the terms
above for this project's own source code.
