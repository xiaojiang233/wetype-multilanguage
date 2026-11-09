<div align="center">

<img src="logo.svg" alt="WeType MultiLanguage Logo" width="100%" style="max-width: 820px;" />

<p align="center">
  <strong>基于 Rime 输入法引擎构建的微信输入法多语言扩展模块（Xposed / LSPosed）</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-GPLv3%2FApache--2.0-blue.svg" alt="License" />
  <img src="https://img.shields.io/badge/平台-Android%208.0+-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/核心引擎-librime-orange.svg" alt="Engine" />
  <img src="https://img.shields.io/badge/框架-LSPosed%20%2F%20LSPatch-blueviolet.svg" alt="Xposed" />
</p>

</div>

---

## 🌟 项目简介

**WeType MultiLanguage** 是一个专为微信输入法打造的开源多语言扩展 Xposed 模块。

通过将开源成熟的 **Rime（中州韻）输入法引擎**（`librime` 原生核心）与 Android 输入法体系结合，为微信输入法赋予原生级别的离线**日语（日本語）**罗马字输入能力，带来流畅的假名转换、智能接续联想和极致打字体验。

---

## ✨ 核心特性

- **🚀 原生离线 Rime 引擎 (`librime`)**：
  - 模块内嵌针对 Android arm64-v8a 优化的 `librime` C++ 核心，全部词库转换与编译均在设备本地完成，零网络请求，极速响应。
- **🗾 完备的日语罗马字输入方案**：
  - 支持标准赫本式（Hepburn）与训令式（Kunrei-shiki）拼写输入。
  - 自动处理促音（`っ`）、拨音（`ん`）、拗音（`きゃ/しゃ/ちゃ`）与长音（`ー`）。
  - 完美支持多重 `n` 派生输入习惯（输入 `konnnichiha` 准确识别为 `こんにちは` 而非生硬分词）。
- **💡 智能接续联想（2-gram Bigram）**：
  - 内置精选的 Gboard 风格高频日常口语接续联想库。
  - 上屏后候选栏自动推荐助词接续（如输入 `私` 立即联想 `は`、`の`、`が`、`も`、`たち`）与高频问候语后续（如 `こんにちは` → `！`、`初めまして` → `よろしくお願いします`）。
  - 点击联想词一键上屏并持续接续联想，打字行云流水。
- **📜 展开式候选网格无缝集成**：
  - 完美对接原生输入法候选栏右侧的下拉展开按键（`∨`）。
  - 展开后支持多行大面积候选词与联想词浏览，点击即上屏，且不会意外退回中文模式。
- **⌨️ 优雅的键盘融合交互**：
  - 键盘底部集成无缝语言切换键，直观标注当前输入语言（`中` / `英` / `あ`）。
  - 日语模式下自动将左侧 Shift 键转换为日语专用**长音键（`ー`）**，契合日常日文输入习惯。
- **📚 开放式词库扩充**：
  - 支持导入自定义 TSV（`读音<TAB>词<TAB>权重`）、Mozc 词典以及 Rime `*.dict.yaml` 格式词库。

---

## 🏗️ 架构设计

```
wetype-multilanguage/
├── common/                  # 纯 Kotlin / JVM 核心层
│   ├── engine/              # 输入引擎抽象定义与 Rime JNI 桥接接口
│   ├── language/            # 语言抽象与注册表
│   ├── layout/              # 键盘布局与标点符号映射规范
│   ├── mapping/             # 动态角色反射解析系统
│   ├── predict/             # 2-gram 智能联想与词级预测器
│   ├── romaji/              # 罗马字 ↔ 假名转换算法与预编辑格式化
│   └── sync/                # 用户词库增量同步框架
├── xposed/                  # Android Hook 与原生适配层
│   ├── cpp/                 # librime 的 JNI 本地绑定
│   └── kotlin/              # 现代 Xposed 生命周期挂钩、视图定位与候选栏适配
└── scripts/                 # 开源词库生成与本地构建脚本
```

- **`:common` 模块**：与平台无关的纯 Kotlin 跨平台逻辑，负责所有的拼写转换算法、Rime YAML 配置生成、联想大表管理与单元测试（单测覆盖率 100%）。
- **`:xposed` 模块**：基于 `io.github.libxposed` 现代 API 实现，仅通过动态反射和标准接口与宿主进行交互，零侵入、高稳定。

---

## 🛠️ 安装与使用

### 环境要求

- 系统版本：Android 8.0 及以上（API Level 26+），支持 arm64-v8a 架构。
- 运行环境：已安装 **LSPosed**（推荐）或使用 **LSPatch**（支持内嵌模式与 Manager 模式）。

### 编译构建

在配置好 Android SDK 环境后直接执行：

```bash
# 1. 执行单元测试
./gradlew :common:test

# 2. 编译模块 APK
./gradlew :xposed:assembleDebug
```

编译产物位于：
`xposed/build/outputs/apk/debug/xposed-debug.apk`

### 启用步骤

1. 安装生成的 `xposed-debug.apk`。
2. 打开 **LSPosed 管理器**，在模块列表中勾选 **WeType MultiLanguage**，并将宿主输入法加入作用域。
3. 强行停止输入法进程或软重启手机。
4. 打开任意输入框调出键盘，点击底栏切换键（地球图标）即可在 `中` → `英` → `あ`（日语）之间无缝循环切换。

---

## 🤝 贡献说明

欢迎提交 Issue 和 Pull Request！参与贡献时请注意：
1. 请务必严格阅读并遵守 [AGENTS.md](AGENTS.md) 规范。
2. **严禁**提交任何来自商业宿主软件的逆向原始反编译代码、专有资源或混淆私有类结构。
3. 提交 PR 前请确保 `./gradlew :common:test` 单测全部通过。

---

## ⚖️ 开源协议与免责声明

- 本项目源码采用 **GNU General Public License v3.0**（或各文件头部注明的兼容开源协议）授权分发。
- 词典数据与 Rime 基础配置源自开源项目 [rime-japanese](https://github.com/gkovacs/rime-japanese) 与 Google Mozc（遵循 BSD-3-Clause / Public Domain）。
- **免责声明**：本项目属于个人研究与无障碍辅助扩展的开源项目，与腾讯公司（Tencent Holdings Ltd.）不存在任何商业关联、官方合作或授权关系。文中所提及的各类商标、标识均归其各自权利人所有。
