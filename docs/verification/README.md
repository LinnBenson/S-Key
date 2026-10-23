# 验证记录索引

当前发布：[1.0.98 功能、隐私与内存复查](release-1.0.98-2026-10-24.md)。

历史记录描述当时版本，不代表当前词库或构建方式。

新增专项：[纯拼音模糊音与 emoji](pinyin-fuzzy-emoji-2026-10-22.md)。

新增专项：[AI 语音网络失败重试](ai-voice-network-retry-2026-10-22.md)。

最新复查：[Bug、隐私与内存](bug-privacy-memory-followup-2026-10-22.md)。

当前功能验证：[内置词库与核心职责拆分（2026-10-22）](builtin-dictionaries-2026-10-22/REPORT.md)。

最新增量：[内置 AI 提示词（2026-10-22）](builtin-ai-prompts-2026-10-22.md)。

新增专项：[可编辑符号闭合规则](symbol-closure-2026-10-22.md)。

新增专项：[可视化符号键盘](symbol-keyboard-2026-10-22.md)。

新增专项：[一次性快捷粘贴](quick-paste-2026-10-22.md)。

新增专项：[简繁输出](traditional-output-2026-10-22.md)。

新增专项：[英文空格选词](english-space-2026-10-22.md)。

新增专项：[Bug、隐私、内存与残留审计及弹窗统一](audit-2026-10-22.md)。

新增专项：[三种输入模式与词库格式校验](input-modes-2026-10-22.md)。

新增专项：[词库查询优化](dictionary-search-2026-10-22.md)。

新增专项：[拼音联想显示与高优先级词库](pinyin-prediction-priority-2026-10-22.md)。

## 常用检查入口

在源码根目录运行：

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
python3 tests/test_dictionary_catalog.py
python3 tests/test_compact_wubi_builder.py
python3 tests/privacy_audit.py
```

设备回归通过 `android.test.InstrumentationTestRunner` 执行。新词库回归类为 `io.secure.keyboard.dictionaries.BuiltinDictionaryTest`；输入、候选、生命周期及助手行为沿用原有测试类。

## 历史材料

[TEST_RESULTS.md](../../TEST_RESULTS.md) 保存逐版本记录。本目录中按版本或功能命名的子目录是历史专项报告，不代表所有用例均在最新版本重新执行。

外层 `diagnostics/` 是本地原始日志和诊断输出；需要随源码保留的结论整理进本目录，避免把设备日志直接作为当前版本验证结论。

- [2026-10-22 全模块检查、剪贴板联动删除与长按符号](full-audit-2026-10-22.md)

- [候选分页、剪贴板保留与设置备份](settings-policy-pagination-2026-10-22.md)
