# 项目结构

外层是本地工作区：`source/` 是 Git 仓库，`key/` 保存私有签名材料，`package/` 保存最终安装包，`diagnostics/` 保存本地诊断输出。不要把外层初始化为源码仓库。

## 应用职责

| 位置 | 职责 |
| --- | --- |
| `OfflineKeyboardService` | Android 生命周期、系统输入连接与 session 的连接 |
| `ime/KeyboardSession` | 每个 Service 实例的组件组装、生命周期清理与控制器连接 |
| `ime/InputState`、`InputController` | 输入状态、按键队列、提交顺序和编辑器交互 |
| `ime/CandidateController` | 候选请求、异步结果版本校验 |
| `ime/AssistantController` | AI、翻译请求与原文编辑协调 |
| `ime/KeyboardViewController` | 按键、候选栏和键盘布局 |
| `ime/PanelController` | 剪贴板、常用语、表情、快捷编辑等面板 |
| `ime/VoiceController` | 语音生命周期及提交目标校验 |
| `dictionaries/` | 内置词库草稿、手机端编译、不可变版本发布及索引读者 |
| `engine/` | 五笔、拼音和英文查询规则，保留文本参考实现供对照测试 |
| `settings/` | 设置界面、状态、主题编辑与内置词库管理界面 |
| `data/` | 原有设置和用户内容持久化；自定义词库继续即时生效 |

控制器不保存静态 Activity、编辑器文字或 View；可变状态由 session 持有并在结束输入或销毁时清理。控制器之间仍通过 session 协作，本次先保持既有行为，再逐步收紧接口；不增加多个 Gradle 模块。

Android 的 Activity 入口类继续放在根包，设置组件统一归属 `settings/`。

## 工具和文档

- `scripts/`：可版本管理的构建、资源生成和发布工具。发布入口为 `python3 scripts/build_release.py`，默认读取外层 `key/`，可通过 `SKEY_SIGNING_DIR` 指定已有签名目录。
- 外层 `key/build_release.py` 是兼容入口，不再承载发布实现。
- `tests/`：Python 与离线基准；`app/src/test/`：JVM 单元测试；`app/src/androidTest/`：设备回归。
- [验证索引](../verification/README.md) 区分当前验证与历史材料。
- [内置词库设计](dictionaries.md) 说明数据格式、发布语义和重建方式。
