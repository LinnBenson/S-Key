# 设置与键盘代码审查（2026-10-23）

范围：当前工作区（包含尚未提交的 UI 改动），设置导航、主题刷新、生命周期与资源所有权、生产引用、Android Lint、JVM 测试及 BlueStacks 回归。

## 已修复

- 设置页使用固定父页面返回，忽略真实入口。增加页面历史，保存到 Activity 状态；外部新 Intent 清空旧历史。覆盖主页直接进入关于、许可、输入方式及多层返回。
- 外部 Intent 切换页面时旧内容编辑草稿仍会显示。新请求先关闭旧草稿，再处理新增常用语快捷入口。
- 同一偏好编辑通知多个主题/UI 参数时，逐条安排完整键盘重建。改为移除待处理刷新并重新安排同一个 Runnable，合并当前消息批次；销毁时仍统一移除 Handler 回调。
- IndentedKeyRow 缓存持有已移除 KeyContainer。onViewRemoved 释放缓存并恢复背景及内边距，避免复用行时保留旧 View/监听器，也避免重新插入按键时累加内边距。

## 清理

删除无生产调用的旧原生设置实现：KeyboardScreen、SettingsScreen、ToolbarEditor、KeyboardThemeEditor、SettingsNavigation、SettingsEditableDraft/SettingsDraftBudget、SettingsDialogs、SettingsChevron、SettingsInputs。SettingsPalette 保留仍被颜色选择器使用的常量与 dp，删除无调用的原生控件样式方法。

删除依赖这些旧实现的 KeyboardThemeEditorTest、SettingsLifetimeDiagnosticTest、SettingsEditableDraftLifecycleTest、旧 SettingsNavigationTest 和 SettingsDraftBudgetTest。前面三套仪器测试依赖已不存在的 Activity 字段或方法，不能作为当前 Compose 界面的验证。补充当前 Activity 导航及按键缓存生命周期测试；现有 ThemePreviewPaletteTest 验证当前主题编辑器。

## 告警与边界

- Lint 的六处 Cursor 回收提示经源码核对，均在 use/?.use 中关闭，未据此判定泄漏。
- 检查服务销毁路径：偏好/剪贴板监听解绑、请求取消、工作线程关闭和 Handler 回调清理均存在。ViewModel 偏好监听在 onCleared 解绑。
- drawable/layout/xml/raw 的直接引用扫描未发现可确认的孤立资源；不依据文件名删除动态或系统引用资源。
- 仍有低优先级 Lint 提示，包括复杂矢量路径、无障碍点击、旧平台 API、单色启动图标及 Compose 布局约定。没有为消除提示盲目升级依赖或改动输入行为。
- 另有旧字典/设置 UI 测试仍绑定原生 View 层级（如 SettingsRedesignScreensTest、BilingualDictionaryScreensTest），需要单独迁移至 Compose 语义；本次不宣称整个 instrumentation 测试目录通过。DictionaryEntryRow、DictionaryTextReader 仅见测试引用，留待其测试迁移时一并处理。
- 本次生命周期验证不能证明没有任何内存泄漏，未覆盖真实麦克风、在线 API、大词库长时间压力及所有设备折叠/多窗口场景。

## 验证

- 最终构建：assembleDebug、assembleDebugAndroidTest、lintDebug、testDebugUnitTest 成功。
- JVM：255 项测试通过，0 failures/errors/skipped。
- Lint：0 errors，50 warnings；剩余类别见上文，未把警告全部当作缺陷或泄漏。
- tests/privacy_audit.py 通过；git diff --check 通过。
- BlueStacks（127.0.0.1:5555）：21 个不同设备测试用例通过。包括新增导航 3 项、按键缓存 1 项、HiddenPanelLifecycleTest 11 项、60 次内容面板循环和 60 次 AI 工具循环各 1 项、当前主题编辑与滑条 2 项、UI 外形 1 项、符号第四行 1 项。
- 首轮导航测试有 1 项失败（新 Intent 后立即返回）；增加两帧渲染同步后导航 3 项及主题/键盘 4 项复测通过。没有为该测试结果进一步修改生产导航逻辑。
- 两组循环验证隐藏后的内容/编辑器释放以及合成输入未持久化；不等于进程堆泄漏检测。BlueStacks 的 run-as 返回 setegid Operation not permitted，无法导出数值诊断文件，未对 PSS/堆长期趋势作结论。
- 最终 APK：外层 package/s-key-audit-debug.apk；测试包 package/s-key-audit-test.apk，均使用现有本地签名。已安装 BlueStacks。版本维持 1.0.84（91），未提交或推送。

## 1.0.85 发布补充

上述审查修复随 1.0.85（versionCode 92）发布。发布脚本完成三种架构 APK 的构建、对齐、现有签名和验签；GitHub 附件使用通用包。正式包已在 BlueStacks 覆盖安装并启动验证。

通用 APK SHA-256：`a7481bb0a934fcaa1f974d641264800797f6a58bd78a0ddcb2a92f3ce6beb4ff`。
