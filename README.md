# S Key

面向 Android 的五笔键盘：以本地输入为基础，将翻译、AI 文字处理和语音输入放进键盘。

[下载最新版](https://github.com/LinnBenson/S-Key/releases/latest) · [版本记录](https://github.com/LinnBenson/S-Key/releases) · [反馈问题](https://github.com/LinnBenson/S-Key/issues)

## 功能

- **中文与英文输入**：五笔 86 与拼音混输、英文当前词补全、候选展开、中文和英文自定义词库。保留原版混输方式，不提供独立拼音或纯五笔模式。
- **键盘工具**：剪贴板、常用语、表情、快捷编辑，以及可配置的工具栏。
- **布局调整**：横竖屏、折叠屏、悬浮拖动与缩放、分离键盘和高度调整。
- **主题与外观**：Miuix 设置界面；黑白默认主题、手动切换、跟随系统、自定义配色、调色盘和完整键盘预览。白色主题默认强调色及首选候选颜色为 `#007AFF`。
- **翻译**：使用自行配置的 Google 翻译 API，支持语言选择、交换及记忆；进入时可读取选中文本，预览译文后插入。翻译和 AI 的原文区域均支持长按系统菜单粘贴当前剪贴板文字。面板展开时增加临时高度，保留按键大小。
- **AI 助手**：使用自行配置的 Grok API，提供润色、纠错、语气调整、翻译和自定义指令。先预览结果，再插入或替换选中文本。
- **自定义 AI**：为提示词命名，在设置中统一排序、编辑、删除；键盘按名称选择并自动填充。进入自定义模式时，空提示词默认使用列表第一项。
- **语音输入**：可选择本地普通话识别或 AI 语音识别。AI 语音直接上传录音，无需本地模型；识别后自动上屏、清除面板记录并继续聆听。英文键盘下会先通过已配置的 Google 翻译接口自动译成英文，再上屏；中文键盘保留原识别结果。

## 安装与启用

1. 从 [Releases](https://github.com/LinnBenson/S-Key/releases/latest) 下载 APK。本版通用包支持 `arm64-v8a` 与 `x86_64`。
2. 安装后打开 S Key，按页面提示启用并切换输入法。
3. 在偏好设置中调整布局、主题和工具栏；语音功能需授予麦克风权限。

最低支持 **Android 8.0（API 26）**。当前版本为 **1.0.13**（versionCode 20），包名为 `io.secure.keyboard`。

### 升级与数据

- 从旧版 S Key 覆盖升级，需要包名和签名均一致。本版沿用 AI 分支的发布签名，不能保证与早期 0.1.x 安装包兼容；请勿为了绕过签名错误直接卸载，先备份需要保留的内容。
- 之前的 S Key For AI 使用 `io.secure.keyboard.ai`，与本版属于两个应用，设置和本机内容不会自动迁移。
- 本版发布证书 SHA-256：`b5941587f82bbafcb946183f750147df3acd184ed7002026a8eb322182d6363e`。
- 发布附件提供 SHA-256 校验文件。

## 配置翻译与 AI

进入 **偏好设置 → 接口 API 配置**，填写自己的接口地址、API Key 和模型。

| 功能 | 默认接口 | 触发方式 |
| --- | --- | --- |
| Google 翻译 | `translation.googleapis.com/language/translate/v2` | 提交翻译 |
| Grok 文字处理 | `api.x.ai/v1/chat/completions` | 点击生成 |
| Grok 模型列表 | `api.x.ai/v1/models` | 点击获取可用模型 |
| AI 语音 | `api.x.ai/v1/stt` | 使用 AI 语音并提交识别 |

API 服务需要自行开通，服务可用性、费用和模型权限由对应提供商决定。地址支持自定义 HTTPS 接口；使用代理时，内容和密钥会发送到所配置的服务器。

英文键盘使用 AI 语音时，需同时配置语音接口和 Google 翻译密钥。识别后自动检测原语言并翻译成英文；翻译失败或取消时不会将原语言直接上屏。中文键盘及本地语音保持原有行为。

**自定义提示词**在“偏好设置 → 本机内容 → 自定义 AI”中管理。键盘中的选择列表只负责选择，不提供删除操作。

## 离线语音

在“语音输入”中选择离线语音，下载并解压指定普通话模型，再选择包含 `model.int8.onnx` 和 `tokens.txt` 的文件夹。应用会校验模型后导入，模型不随 APK 提供。

本地识别无需调用 AI API；选择 AI 语音则无需下载本地模型。模型来源与校验信息见 [语音依赖说明](third_party/VOICE_DEPENDENCIES.md)。

## 数据与联网说明

普通中文、英文输入在设备内处理。应用不自动学习完整输入历史，不内置广告、统计或崩溃上报 SDK。

- 剪贴板、常用语、自定义词库、最近表情和手动保存的自定义 AI 提示词保存在本机。清空全部用户内容会一并清除自定义 AI。
- API 配置使用 Android Keystore 加密保存；应用备份关闭。
- 翻译及 AI 文字处理会将面板内容发送到配置的接口；AI 语音会上传录音；英文键盘下，转写文字还会发送给配置的 Google 翻译接口。本地语音不上传录音，应用不将录音保存为文件。
- AI 原文、结果和临时指令不自动写入历史；手动保存的命名提示词属于持久化配置。
- “浏览器下载模型”会打开 GitHub 下载页，由外部浏览器联网。
- **当前依赖中的 AndroidX Emoji 自动初始化器会请求系统字体提供者；提供者可能下载字体，具体取决于设备和缓存。因此当前版本不能承诺除 API 外完全没有间接联网。**

以上来自源码、依赖与正式版清单检查，不是全场景抓包结论。输入到其他应用的文字由目标应用自行处理。

## 从源码构建

准备 JDK 17、Android SDK 35 与 Python 3。在项目根目录执行：

```bash
# 从仓库内的 XZ 文件还原字体，不联网
python3 scripts/restore_source_assets.py

# 下载并校验固定版本的 sherpa-onnx Android 依赖
python3 scripts/fetch_voice_dependency.py

# 将实际 Android SDK 路径写入 local.properties
printf 'sdk.dir=/your/path/to/Android/sdk\n' > local.properties

./gradlew assembleDebug
```

Gradle 首次构建需要下载依赖。输出位于 `app/build/outputs/apk/debug/`。`assembleRelease` 生成未签名 Release APK，发布时需要自行签名；仓库不包含发布私钥、密码或 API Key。

验证命令：

```bash
./gradlew testDebugUnitTest assembleDebugAndroidTest lintDebug
python3 tests/privacy_audit.py
```

设备测试需要连接 Android 设备或模拟器。[验证记录](TEST_RESULTS.md) 区分当前构建检查和历史功能回归，不能视为所有设备均已验证。

## 项目结构与第三方资源

- `app/src/main/java/`：输入法、设置、翻译、AI、语音和本地存储。
- `app/src/main/assets/`：词库、字体和许可证声明。
- `app/src/test/`、`app/src/androidTest/`：单元测试与设备测试。
- `third_party/`：第三方组件来源、图标及许可说明。
- `scripts/`：资源还原、依赖获取和资源生成脚本。

设置界面使用 Miuix；专用键盘图标由 Material Symbols 补充；本地语音基于 sherpa-onnx。各组件、词库和字体遵循各自许可，详见随资源保留的声明。本仓库没有为所有内容统一授予同一许可证，部分补充资源的再分发许可仍需单独核实。

## 反馈

提交 [Issue](https://github.com/LinnBenson/S-Key/issues) 时，请提供应用版本、Android 版本、设备型号及复现步骤。截图和日志请先移除 API Key、私人文字及录音内容。
