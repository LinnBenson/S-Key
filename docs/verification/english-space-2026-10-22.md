# 英文空格选首选候选（2026-10-22）

“输入方式 → 英文输入偏好”增加“空格选择首选词”，位于“英文单词补全”上方，默认关闭。关闭时保留已输入的英文并插入空格。开启后，英文模式有可用候选时，空格复用 choose 选择第一项，替换当前前缀并插入一个词后空格；后方已有空格时只越过该空格。不具备有效候选时正常输入空格。沿用词库版本、输入连接、前缀及候选世代校验。中文空格逻辑不变。

Debug 应用和测试构建成功。设备通过以下 4 项用例：

- CandidateRenderingTest#testEnglishSpaceSelectsFirstCandidateAndKeepsSingleSpace：真实 EditText，分别验证词尾、已有空格、无候选。
- CandidateRenderingTest#testEnglishSpaceCompletesOnlyAssistantDraft：助手原文中空格选首项，只修改草稿、不写入宿主输入框。

- CandidateRenderingTest#testEnglishSpaceDisabledPreservesTypedWordDespiteCandidates：关闭开关时，即使已有候选，也保留 hel 原文并插入空格。
- CandidateRenderingTest#testEnglishSpacePreservesRapidFollowingKeys：开启后，快速输入、退格不会越过选词任务。
