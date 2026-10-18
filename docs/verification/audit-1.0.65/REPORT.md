# 1.0.65 深度检查记录

日期：2026-10-19（Asia/Singapore）。工作目录：S Key For AI。模拟器：emulator-5554 / BlueStacks。

## 已修复的问题

1. **中文退格次序错误**：AI/翻译原文或补充要求已经有正文时，旧逻辑先删除编辑框正文，再处理尚未确认的中文编码。现在优先删除编码，不误删已确认文本。新增用例覆盖翻译原文、AI 原文和补充要求。
2. **历史结果的提示词模式丢失**：切换任务标签后选回旧结果，再生成/继续修改时，是否使用共用提示词原来取决于当前标签。现在历史条目保存 custom 标志，恢复结果时一并恢复，避免给自定义任务误加共用提示词，或让普通任务意外失去共用提示词。
3. **API 密钥字段缺少密码输入声明**：原来只有视觉遮挡。现在三个 API 密钥字段始终使用 KeyboardType.Password，并关闭自动纠正；显示密钥只改变视觉变换，不改变输入类型。模拟器实际聚焦 Google 密钥框后，系统报告 inputType=0x20081（文本密码）。没有打印或保存真实密钥内容。

## 验证

- assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 成功。
- 204 项 JVM 单元测试：0 失败、0 错误。
- 101 项不同的设备测试最终通过：前五个批次每批 20 项，最后一批 1 项；其中旧主题默认色断言修正后单独复跑通过。覆盖完整 CandidateRenderingTest（系统截图用例除外）、AiWritingTest、AiVoiceSessionTest、ClipboardCapturePolicyTest、SettingsDefaultsPersistenceTest 和 SpaceHoldTouchTrackerTest 的显式公共测试。
- 设备测试覆盖：输入框切换、中文候选、英文补全、过期查询与点击、千次慢宿主刷新、有界排队、原文与补充要求编辑、双击退格关闭、拖动宽度保存、历史结果、结果插入、流式格式/取消/异常、模拟语音上传与鉴权、敏感剪贴板拒绝等。
- 60 次面板循环：baseline PSS 136411 KiB，cycle 60 为 138417 KiB，稳定后 136933 KiB；Java 堆 23990096 → 19677976 字节，native 堆 10684896 → 10630816 字节。旧视图保留数均为 0。未观察到这条测试路径上的持续堆积；强制 GC 下可回收不等同于全部真机场景无泄漏。
- 合成输入标记、合成语音密钥和合成转写标记在所读取 logcat 中匹配数均为 0。
- 生产源码常见凭据模式扫描未发现硬编码凭据。检查未发现生产 Log/println/printStackTrace 调用。

## 隐私与资源审查

- API 配置使用 Android Keystore AES/GCM 加密存入私有偏好。
- 禁用系统备份/设备迁移数据提取、明文 HTTP；网络连接禁用自动重定向，未发现绕过 TLS 验证的 TrustManager/HostnameVerifier。
- 网络路径为 Google 翻译、AI 文本生成、AI 语音及模型列表获取。服务商会接收用户主动提交的文本/音频；本检查没有验证服务商的留存行为。
- 密码输入场景限制候选、助手和剪贴板读取。系统 IS_SENSITIVE 剪贴板内容被拒绝保存。未带敏感标记的普通剪贴板内容仍按功能保存；不具备自动识别所有口令/个人信息的保证。
- 剪贴板、词库、常用语、自定义提示词保存在应用私有 noBackupFilesDir，内容文件并非全量加密；API 配置单独加密。未发现将普通输入或 AI 原文/结果作为历史落盘的路径，合成标记文件扫描测试通过。
- AI 历史最多 8 版，流式响应、音频和本地内容有大小限制；流式 UI 使用单个待处理通知。检查了退出/销毁后的任务取消、监听器解绑与视图释放。
- 离线模型导入 Cursor 使用 use 自动关闭；Lint Recycle 警告是该调用链的误报。

## 测试环境问题与限制

- 旧 Android 测试运行器把 Kotlin 私有合成函数误当测试，改用显式公共测试列表。
- 旧测试查找已移动的助手容器/已移除的插入按钮，以及过时的默认强调色；已修正测试定位/断言。测试配置隔离补上自动中文空格的保存恢复，主线程断言改为交回 JUnit 报告，避免测试失败直接杀死进程。
- 系统截图用例 testToolbarRetainsFocusSemanticsWithoutGrayBlockAfterDpadInBothPalettes 触发 BlueStacks app_process 的 lib_renderControl_enc.so / OpenGL 原生崩溃，随后 UiAutomation DeadObjectException；该用例未算通过。其余测试分批完成。
- 没有使用真实麦克风录音、调用真实云端模型或执行多小时真机压力测试；语音上传测试使用内存模拟连接。没有通过堆转储全面证明所有 Activity/原生识别器都无泄漏，也没有进行第三方依赖漏洞数据库审计。
- 1.0.65 Debug 已覆盖安装到模拟器，未清除应用数据；审查完成时尚未发布 GitHub 或生成正式签名包；随后发布构建 assembleRelease 成功，正式包签名校验通过，沿用既有发布证书。

Lint 严重级别统计：{'Warning': 81}。剩余警告主要为矢量路径、未使用资源、自定义 View 构造、绘制时分配及无障碍提示，未声称已全部消除。
