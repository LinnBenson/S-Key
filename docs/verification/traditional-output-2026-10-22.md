# 简繁输出（2026-10-22）

“输入方式 / 中文输入偏好”顶部新增简繁转换开关，默认简体（保留原文）；开启后最终上屏时简转繁。“更多”面板新增简繁转换，可在“键盘操作 / 定制工具栏”固定，按钮随状态显示简或繁，点击立即切换并持久保存。保留已有工具栏排列。

离线数据使用 OpenCC ver.1.1.9 的 STCharacters / STPhrases，按最长词组匹配及首选映射转换。来源、许可证与校验值见 third_party/opencc/README.md，应用开源声明展示许可。词库、剪贴板历史和助手编辑草稿不改写，简体模式不进行反向转换。密码框保持原文。

已接入候选及普通输入、直接粘贴/短语/表情文本、快捷粘贴、快捷编辑粘贴、符号对、语音上屏、翻译结果及 AI 结果的主输入框写入。空格自动插入及语音提交保护使用转换后的 UTF-16 长度，避免转换后长度变化导致光标偏移。

验证：

- TraditionalConverterTest：3 项通过（词义区分、混合文字与换行、最长匹配及补充平面字符）。
- KeyboardEnglishSymbolPairTest#testTraditionalOutputToggleAndHostInsertion：真实 EditText 与生产工具栏验证默认简体、切换/保存、简繁输出通过。
- KeyboardEnglishSymbolPairTest#testTraditionalClipboardAndAssistantOutput：快捷粘贴转换、助手草稿不转换、翻译最终输出转换通过。
- Debug 应用与测试构建通过，安装后设备 2 项测试通过。
