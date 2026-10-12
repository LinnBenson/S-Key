# S Key

一款面向 Android 的离线键盘，支持五笔86＋拼音混输、英文当前词补全与离线普通话语音输入。使用 Kotlin 与 Android InputMethodService 实现，输入内容在设备内处理，应用不声明 INTERNET 权限。

> 当前为私人测试版本，不是应用商店正式版。部分第三方补充词库的再分发许可尚未明确；提供源码不代表整个资源包可以直接公开发布。

当前源码为0.1.8（versionCode 9），包含固定浅色设置改版、HSV调色器、输入框首屏策略、双语手工词库及资源生命周期修复。用户已接管最终实机测试并取消余下模拟器检查；完整验证边界见 TEST_RESULTS.md。0.1.5是未单独交付的内部基线，使用相同签名的本版APK可覆盖更新已安装的 S Key。


## 项目信息

| 项目 | 当前值 |
|---|---|
| 应用名称 | S Key |
| 应用包名 | `io.secure.keyboard` |
| 版本 | `0.1.8`，versionCode `9`，构建53 |
| Android | 最低 Android 8.0 / API26，target/compile API35 |
| 实现 | Kotlin 2.0.21、原生 Android Views、InputMethodService |
| APK 架构 | 独立 `arm64-v8a` 与 `x86_64`；手机测试包为 ARM64 |

## 功能概览

- **中文混输**：五笔86优先，拼音作为同模式补充；独立模糊音开关、候选横向滚动与展开、可配置四码自动上屏。
- **英文输入**：25,000词静态当前词补全、独立手工英文词库、候选补空格；不预测下一词、不学习个人词频。
- **本地工具**：剪贴板、常用语、表情、光标/选区编辑；支持固定工具与排序、条目置顶和分类清除。
- **多形态布局**：外屏/内屏×横屏/竖屏四套配置、悬浮拖动缩放、内屏分离布局、独立高度与留白。
- **主题与反馈**：浅深主题各15项颜色、HSV调色器、系统按键音和震动；设置应用采用独立固定浅色界面。
- **离线语音**：用户导入固定普通话模型，工具栏直接听写或按住空格说话；最终片段经宿主守卫后上屏，不保存录音。

## 目录

- [安装与使用](#安装与使用)
- [离线普通话](#离线普通话)
- [保存什么](#保存什么)
- [内置五笔词库与内存](#内置五笔词库与内存)
- [中文与英文手工词库格式](#中文与英文手工词库格式)
- [维护内置系统词库](#维护内置系统词库开发者)
- [模糊音与配色](#模糊音与配色)
- [布局](#布局)
- [开发与构建](#开发与构建)
- [项目结构](#项目结构)
- [验证范围](#验证范围)
- [第三方组件](#第三方组件)

## 安装与使用

1. 在 Android 手机上安装交付的对应版本 arm64 测试 APK。这是调试签名测试版，不是应用商店正式版。包名 io.secure.keyboard，已安装使用相同签名的 S Key 0.1.3/0.1.4 可直接更新并保留配置/内容；相对于旧 org.offlinekeyboard，它仍是独立应用，不覆盖、迁移或删除旧应用内容。首次安装 S Key 需重新启用，并按需导入词库/模型。
2. 打开「S Key」，点「启用」，在系统输入法设置中启用它；再点「切换输入法」。Android 会显示第三方输入法通用风险提示。
3. 中文编码仅显示在键盘内，候选汉字在下面一行。点击候选或按空格插入首选词；有待输入编码时，回车直接插入原始编码，不换行或发送。开启「四码自动上屏」后，完整四码五笔首选项会自动插入。
4. 地球键切换中文/English，并将最后一次明确选择保存为独立配置。再次打开键盘或重新创建服务时恢复该语言的字母键盘；无保存值时默认中文。符号页不保存，收起后再打开回到对应语言的字母页。密码、链接、邮箱及强制ASCII输入框首屏临时显示英文；数字输入框首屏显示符号第一页，数字密码优先数字页。自动切换不覆盖已保存的语言，显示后仍可手动切换。密码框隐藏候选、按键文字浮窗及剪切板内容提示。英文使用25,000词的固定常用词表补全当前词，从首字母起提供候选，不预测下一个词。点击英文补全词会自动留一个空格；后方已有空格时复用并移动到其后。单击上箭头启用一次大写，连续双击锁定大写，再点一次取消。中文模式的大写也直接输入大写英文字母；已有待输入编码时须先选择或取消，避免误提交。长按键帽显示符号，保持按住左右滑动后松手选择。
5. 底行左侧标点键：中文主符号「，」在下、备用「。」在上；英文主符号「.」在下、备用「,」在上。两个符号同字号、横向居中、上下排列，背景及两符号颜色与地球功能键共用主题颜色。轻点主符号，开启长按符号时按住选择上方备用符号。符号页采用指定的四页字符；第二页首行原√、π现已按最新要求替换为中文《、》与英文ASCII <、>，各自独立输入，不新增自动配对。英文逗号可在第二页直接输入；中文句号「。」不在这些符号页里，需开启长按后从空格旁的合并标点键选择。底行从左到右按17∶9∶42∶9∶17分配宽度：?123/ABC与回车等宽，标点与地球键等宽，剩余空格居中；保留原回车17/94和原标点9/94宽度比例，所有布局及悬浮模式均适用。仅?123、ABC和符号翻页键「=\<」的文字改为15sp上限、同字体加粗；空格仍为14sp常规字重。每次从字母键盘重新进入符号页都从第一页开始；当前可见输入框的普通重启不打断正在使用的符号页。
英文符号页点ASCII双引号、ASCII单引号、左花括号或左方括号会插入完整一对，光标停在中间，并立即回到英文的字母键盘；已有选区时替换选区。圆括号、右括号和中文符号页仍按原字符输入。

6. 在空格键左右滑动可逐字符移动光标（含 emoji 字素边界），松手不会输入空格。开始滑动会取消尚未上屏的中文编码，不会自动提交。轻点空格仍选择首候选或输入空格。中文键面文字为“五笔”，仍支持混合拼音；长按空格启动语音，松手结束收音，最后一段安全插入后自动返回键盘，不额外输入空格。移动手指开始横向滑动会取消语音长按。
7. 设置中的「按键角标」默认仅数字，可选全部、仅数字或隐藏，只改变三排字母键右上角提示，不改变长按功能。底行标点键仍显示两个符号。
8. 键盘空闲时工具栏首项是更多，子面板在同一位置替换为返回键，空闲和工具面板最右项用于收起键盘；显示候选时，最右箭头改为展开/折叠候选网格，网格占用原按键区域，键盘总高度不变。已打开的内联工具再点同一图标会返回键盘，点其它工具则切换面板。更多面板可进入剪贴板、常用语、语音、快捷编辑、表情、布局、悬浮、分离、主题、中文词库、英文词库、设置。

默认固定项左到右为悬浮、剪贴板、快捷编辑、表情、语音；更多/返回和右侧控制键另外保留。已有自定义顺序不覆盖，常用语仍可从更多打开。
更多面板隐藏右侧滚动条并移除底部「编辑工具栏」按钮，仍可上下滚动；快捷编辑入口保留，固定工具及顺序仍可在设置的「工具栏固定项」中调整。

9. 表情每次打开默认进入笑脸分类，最近使用按钮是时钟图标；表情点按也遵循同一震动开关。
10. 快捷编辑中央「选择」是开关：开启后显示主题强调色，四方向箭头调整选区；再次点击退出选择模式。左上角「全选」保持独立。方向与字素边界由当前编辑器处理，部分应用可能不支持。
11. 震动强度10%及以下采用系统键盘触感；更高强度在设备支持时缩放系统触觉基元，否则使用系统键盘触感。具体马达与触感由设备决定，并尊重系统触感开关。

## 离线普通话

语音模型不内置在 APK 中。首次使用：

1. 打开应用中的「安装离线语音模型」，通过下载入口在外部浏览器查看官方分发地址。
2. 获取 `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`，解压后保留 `model.int8.onnx` 与 `tokens.txt`。
3. 在应用中选择包含这两个文件的文件夹，完成校验与导入，再按系统提示授予麦克风权限。
4. 回到键盘，点语音图标开始，或长按空格说话。

模型按固定大小和SHA-256校验，不能仅将其他模型重命名导入。准确下载来源、固定修订及两个文件哈希见 [VOICE_DEPENDENCIES.md](third_party/VOICE_DEPENDENCIES.md)。下载由外部浏览器进行；模型就绪后的识别不需要网络。

[固定模型下载](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2)，下载前请阅读上游说明及适用许可。

识别使用 sherpa-onnx 与约25 MB的 Mandarin Zipformer CTC int8 模型。录音只保留在内存里，不生成音频文件。模型和权限就绪后，点工具栏语音图标立即开始；停顿后确定的完整片段自动插入当前输入框，中间识别变化只在语音面板显示。底部只有「取消」，它停止录音并丢弃未完成片段，已经插入的文字保留。切换输入框、改变光标/选区或收起也停止会话。无法确认安全光标位置的编辑器会停止，不猜测插入位置。单次最长60秒，结束后先点语音图标返回键盘，再点即可重新开始。每个非空最终提交片段，若末尾还没有标点，则补一个中文逗号「，」；已有句号、逗号、问号、引号或括号等结尾标点不重复追加。临时预览不加逗号，这是固定段尾规则，不是语义标点恢复模型。长按空格也可按住说话、松手完成；取消手势、切换编辑器等安全中断不提交未完成片段。模型/权限尚未就绪时进入设置，返回后不会自动启动录音。

模型具体版本、官方出处、原始权重与转换后文件的许可区分见 `third_party/VOICE_DEPENDENCIES.md`。测试音频/模型不包含在源码或 APK 交付包里。

## 保存什么

只保存你明确允许的内容：剪贴板、手动常用语、手动中文/英文词库、最近使用的50个表情，以及独立的设置（含最后选择的中文/英文语言）。没有输入日志、打字历史、自学习或个人词频。系统密码框关闭候选与中文编码模式。

- 剪贴板默认30条，可调上限；每次新的复制事件保留，重复文字不合并，不自动过期。同一复制事件的重复系统通知或重开读取只记录一次。置顶仅改变顺序；超出上限优先删除最旧未置顶，全部置顶时删除最旧记录。置顶不会突破容量，也随清除内容一起删除。
- 键盘内剪贴板和常用语每条单行省略显示，点击文字插入完整内容；尾部固定图标用于置顶/取消置顶和删除；面板没有顶部管理按钮，编辑管理在应用设置中。常用语分组展示置顶项，组内保持手动排列顺序。
- Android 限制读取剪贴板的时机。本版接收系统允许的真实复制事件，重新打开时读取当前可用剪贴板；默认输入法在部分系统中可在隐藏时收到回调，但无法承诺捕获系统限制期间的每次复制。密码输入框上下文不自动采集。
- 清空历史不会修改系统剪贴板，并阻止旧内容因重新打开面板再次写回历史；新复制事件仍可保存。
- 清除全部内容保留设置及最后选择的中文/英文语言，并删除应用自己的内容文件、恢复副本与缓存。系统云备份/设备迁移被排除。
- 无法保证物理闪存不可恢复，也不会删除你导出到其他位置的文件。已导入的只读语音模型不是输入内容，单独管理。

## 内置五笔词库与内存

基础五笔86字库加上指定的成语、通用词语、IT、行政区划、历史文化名城五份补充库，以及独立高优先级系统五笔源。0.1.7新增的8项指定词码在0.1.8完整编码精确组中优先；具体合并数量、源哈希及逐项统计见 `third_party/wubi_supplements/BUILD_REPORT.json`。运行时使用预编译只读映射索引，按需解码，不把全部词条转换为常驻对象。完整四码可展开选择整组候选，短码前缀仍有轻量限制；四码自动首选规则不变。

补充库仓库未提供明确LICENSE，来源、哈希和转换规则见 `third_party/wubi_supplements/` 及字典NOTICE。本包仅用于已请求的私人测试，不代表取得公开再分发许可。先前提供的拼音JSON没有加入。内存测量口径和实际结果以 `TEST_RESULTS.md` 为准，索引文件大小不等于运行RAM。

从0.1.6起释放隐藏面板/候选展示引用，派生查询队列有界，模型导入单任务且页面退出解绑。完整草稿仅用于同进程旋转保留，过大的未保存草稿不会整体塞入系统状态；进程结束后可能无法恢复，应用会提示。实际两组自然B/C中，词库末次PSS差−2,504KiB，设置差+33KiB；这不是总体RAM下降或零泄漏结论。语音与外部输入的C对照被用户取消，详见验证报告。

## 中文与英文手工词库格式

UTF-8 TSV，一行一个「词语＋Tab＋编码/拼音」。可有首行 `word<TAB>reading`。例子：

```
word	reading
离线键盘	lixianjianpan
你好	wqvb
```

中文词库保留既有数据和格式。中文编码建议使用不带声调的 a–z 拼音或五笔编码；拼音可用空格/英文单引号分隔。英文词库使用1–40个ASCII英文字母作为编码，匹配不区分大小写，词条内容按输入原样保留（例如 `Best regards` 配编码 `br`）。英文内容中间可以有空格，开头/末尾不能有空白；内容不能含Tab或换行。两种词库独立管理、导入、导出、分类清除；全部清除包含两者。

英文词库示例（`Best regards` 与 `br` 之间为实际Tab）：

```tsv
word	reading
Best regards	br
Thank you	ty
```

常用语不支持自动收集、分类或导入。中文和英文词库各最多10,000条，每页显示100条；导入解码上限为4,000,000个UTF-16代码单元。词库导入先完整验证，再一起保存，格式错误不会部分写入。

候选先保留首个常规候选，完整编码匹配的自定义词随后优先，部分编码匹配追加在常规候选末尾。中文候选最终再将含 Unicode 扩展 B（U+20000–U+2A6DF）字符的条目稳定移到全部普通候选之后，词条和字体不删除。四码自动上屏仍按原精确五笔规则。英文自定义词只在英文当前词补全中出现，沿用精确优先/部分匹配末尾和同文本去重，保留自定义输出大小写；选词后仍补一个空格，后方已有空格则复用。不记录个人频率。

中文模式完整输入 `useskey` 会显示系统内置候选「由 Linn Benson 开发的输入法」，点击候选可插入。部分编码和英文模式不显示，清空用户词库不删除该内置项；密码保护和自动上屏规则不变。

## 维护内置系统词库（开发者）

应用设置里的「中文词库 / 英文词库」管理用户手工词条，不能修改 APK 内置系统词库。以下步骤供以后修改源码、重新构建版本时使用；下面命令只在维护者实际执行时产生文件变更。

### 文件位置与实际运行方式

| 词库 | 可编辑来源 | 应用运行时读取 |
| --- | --- | --- |
| 五笔86基础库 | `app/src/main/assets/dictionaries/wubi86.dict.yaml` | **不直接读取这个 YAML**；读取下行合并后的二进制索引 |
| 五笔五份补充库 | `third_party/wubi_supplements/wubi86_dict_{chengyu,china_cultural_cities,ciyu,it,xzqh}.dict.yaml` | 与基础库一起生成 `app/src/main/assets/dictionaries/compact-wubi.skwb`，运行时只读映射 |
| 高优先级系统五笔 | `third_party/wubi_supplements/wubi86_high_priority.dict.yaml`（0.1.8，现有8条） | 与上面六份来源一并生成同一个只读索引 |
| 简体拼音 | `app/src/main/assets/dictionaries/pinyin_simp.dict.yaml` | 启动时直接解析此只读文本 |
| 英文补全 | `app/src/main/assets/dictionaries/english_frequency.tsv` | 启动时直接解析此只读 TSV |
| 精确内置词 | `app/src/main/java/io/secure/keyboard/engine/BuiltinExactDictionary.kt` | Kotlin 内置映射，目前为 `useskey` 对应词 |

0.1.7本地内置词码按原指定保留，不自动纠正编码：

| 词语 | 编码 | 词语 | 编码 |
|---|---|---|---|
| 微信 | `tmwy` | 安卓 | `pvhj` |
| 北川 | `uxkt` | 美团 | `uglt` |
| 抖音 | `ruuj` | 支付宝 | `fwpg` |
| 滴滴 | `iuiu` | 微博 | `tmfg` |

这8项在对应完整四码输入时作为首候选；开启四码自动上屏时读取同一索引首项。清空用户词库不影响内置源。短码前缀仍先按静态权重，再按本版指定来源顺序；同权重顺序可与旧版不同。

本版manifest来源顺序为：基础五笔→高优先级词库→IT→行政区划→词语→历史文化名城→成语。静态权重仍优先，同权重按此来源顺序及文件行序；高优先全码置顶额外保留，运行时不对8词单独判断。来源说明在 `third_party/wubi_supplements/provenance/LOCAL-BUILTIN-NOTICE.txt`。它来自明确请求的本地编辑，没有上游仓库 revision，不应伪造下载地址或 commit。

上述运行表不会自学习。仅五笔需要预先生成 `.skwb`；不要把所有词库都描述为二进制索引。

源码已含基础五笔、五份上游补充五笔、本地内置补充、拼音原始 YAML、英文成品 TSV、转换脚本、来源清单、许可与现有生成物；这些目录均纳入源码 ZIP 打包范围。英文重建所需的 `en_50k.txt` 与完整 SCOWL 原始压缩包**没有随包附带**，但固定下载地址及 SHA-256 均已写入 `third_party/english/rebuild.py` 和 `manifest.json`。

### 五笔格式与合并规则

YAML 文件以 UTF-8 保存，保留原来源/许可注释以及 `---`、`...` 头部界线；词条写在独立的 `...` 行之后。一行 2～4 列，以真正的 Tab 分隔：

```text
词语<TAB>五笔编码<TAB>权重
```

这里的 `<TAB>` 是说明，实际应按 Tab 或写入制表符，不要输入这五个可见字符。五笔编码只接受 1～4 个小写 ASCII 字母。基础库权重可为 64 位有符号整数，缺省为 0；补充库权重必须为 0，省略权重也按 0。第4列可保留上游 stem，但本引擎不执行其编码规则，也不执行 YAML 配置、`import_tables` 或 Rime schema。

合并按「基础库 → manifest 中列出的各补充库」顺序，以「完整词语＋编码」去重，首次出现的记录生效。普通顺序按有效权重稳定降序排列，同权重保持来源/行序。高优先级源在 `source-manifest.json` 的对应 `sources` 项上标记 `"exact_priority": true`；编译器直接从被标记文件中的词码派生完整编码的优先项，不另维护一份逐词映射。该标记是 JSON 布尔值 `true`，只能用于 `role: "supplement"`；缺省或 `false` 为普通来源，字符串 `"true"`、数字或给基础库设置 `true` 都会报错。

写入二进制时，仅调整这些完整编码的精确候选组；其他精确候选保持本版静态权重与来源顺序生成的相对次序。若同一完整编码在高优先级文件中有多个不同词，它们按文件行序组成组首，后接原普通候选；多个高优先来源按 manifest 来源顺序处理。同一「词语＋编码」重复出现只保留一次。普通源已存在的相同词码也可以通过高优先级源提升，不必删除原普通记录。短码前缀的候选选择和顺序按静态权重及本版来源顺序生成；本次来源顺序变化会改变部分同权重前缀候选。因此，不要通过给所有补充词提高权重来代替这个标记，也不要把其他普通词库整体误标为高优先级。

需要删除一个五笔词时，应先决定是删某一编码，还是删此词的所有编码，再检查所有参与合并的文件。只从基础库删除而补充库仍有同一「词语＋编码」，重建后它仍会存在。修改后必须重新生成索引；仅编辑 YAML 或仅运行 Gradle 不会更新已有 `.skwb`。

### 高优先级系统词库的增删

高优先级词库仍使用与补充五笔一致的 UTF-8 YAML/Tab 行格式。文件保留 `---`、`...` 头部，词条写在 `...` 后；优先来源中的编码与普通五笔源一致，接受1～4位小写 ASCII 字母；这里的「完整编码」指该词条写入的全部编码，不是限定必须4位。当前8项均为4位，权重省略或写 `0`。例如：

```text
微信<TAB>tmwy
```

`<TAB>` 仅表示真实制表符，不应原样输入。维护当前发行版时，编辑 `third_party/wubi_supplements/wubi86_high_priority.dict.yaml`；使用下方「独立本地基线」流程时，只编辑复制后的 `third_party/wubi_local/sources/wubi86_high_priority.dict.yaml`。

- 新增：把词语及完整编码加入该源，再重新固定来源哈希/字节数/行数和生成索引。无需增加 Kotlin 分支或另一份优先词映射。
- 改词或改码：直接修改该源的行，再重新固定并重建。优先项随实际源行派生，不会遗留旧的独立 descriptor。
- 删除：从该源删除目标行，再重新固定并重建。若普通源仍有同一词码，它会按普通规则保留；若要彻底移除该五笔词码，还需检查全部参与合并的来源。拼音组合、用户手工词库等入口仍独立存在。
- 保留普通词、只取消优先：将该行放到适当普通补充源，并从高优先级源删除；同步重新固定两份来源与生成物。

不要手工编辑 `.skwb`，也不要只改 YAML 后直接打包而跳过索引重建。当前版本仅对该源中的8项启用此规则；以后扩大高优先级词库的内容，属于新的源码修改，应有相应审核及验证。

现有维护脚本先 `copy.deepcopy(pinned)`，随后逐份更新 `bytes`、`sha256`、`valid_rows` 及兼容权重行。只要保持这一写法，原 `sources` 项上的 `exact_priority` 标记会被保留，不应把每项重新拼成只含哈希和行数的新字典。

`builder.compile_sources(base, source_dir, manifest)` 会使用保留的来源标记，从修改后的高优先级 YAML 派生优先项并校验，然后生成新的计数/报告。`BUILD_REPORT.json` 中列出的优先词是自动生成的结果，不能当成另一份需要手工同步维护的来源。删除或改码只需修改词库行并重新固定，不需要编辑一个独立优先列表，也无需额外的 priority JSON 文件。

初始化副本仍按 `manifest['sources']` 循环，因此会使用本版本实际列出的 `wubi86_high_priority.dict.yaml`，不硬编码旧普通本地文件名。后续 `--base`、`--supplements`、`--manifest`、`--output` 与 `--verify-only` 命令保持通用形式。

如自行改变的是 manifest 的来源结构或优先标记本身，须明确维护那份本地输入配置；现有重固定片段每次从当前发行版清单深拷贝，不会自动继承上一次生成的本地清单中的结构改动。普通增删高优先级文件内的词条不需要改结构。

### 复现当前版本的固定五笔索引

项目根目录执行：

```sh
python3 scripts/build_compact_wubi.py --source-root . --report third_party/wubi_supplements/BUILD_REPORT.json
python3 scripts/build_compact_wubi.py --source-root . --verify-only
python3 -m unittest discover -s tests -p 'test_compact_wubi_builder.py' -v
```

首条生成索引及报告；`--verify-only` 在内存重新生成并与已有索引逐字节比较，不写文件、不联网。现有源 SHA-256、字节数、行数及输出计数都被 `source-manifest.json` 固定。这组命令用于复现当前版本固定的数据（包括 manifest 中已有的本地内置补充），不能把「直接修改来源后仍能通过」当成预期。

### 有意增删五笔：保留版本来源，建立独立本地基线

推荐先复制文件到独立目录，保留当前版本的原文件与原 manifest，便于审查差异。以下目录置于 `third_party/` 下，现有源码打包脚本会把本地来源和本地 manifest 一起带入后续源码包。

第一次初始化，在项目根目录执行下列 Python 片段。它拒绝覆盖已有工作目录，避免误丢后续修改：

```sh
python3 - <<'PY'
from pathlib import Path
import json
import shutil

root = Path.cwd()
local = root / 'third_party/wubi_local'
if local.exists():
    raise SystemExit('third_party/wubi_local 已存在；不要重新初始化，请直接编辑现有副本。')
source_dir = local / 'sources'
source_dir.mkdir(parents=True)
pinned_path = root / 'third_party/wubi_supplements/source-manifest.json'
manifest = json.loads(pinned_path.read_text(encoding='utf-8'))
for index, spec in enumerate(manifest['sources']):
    original = (root / 'app/src/main/assets/dictionaries/wubi86.dict.yaml'
                if index == 0 else pinned_path.parent / spec['filename'])
    shutil.copy2(original, source_dir / spec['filename'])
print('已准备 third_party/wubi_local/sources；现在只编辑这里的 YAML 副本。')
PY
```

在 `third_party/wubi_local/sources/` 完成增删后，运行下面片段固定**自己修改后的本地数据**。它不是更新上游 commit，也不证明修改后内容仍等于上游或当前发行版；原版本的锁定来源完整保留。

脚本会严格解析每份本地词库，重新计算 SHA-256、字节数和有效行数；对基础库原有 `χ / zzxx / 450.5` 的兼容规则，按内容重新定位行号，若已删除则移除该规则，若存在多处模糊匹配则报错。任何新出现的非法小数权重仍会报错。旧输出计数只在计算新基线的内存步骤中清空，再使用经过逐项参考算法校验的新计数重新锁定，**不会留下无计数约束的最终 manifest**。

```sh
python3 - <<'PY'
from pathlib import Path
import copy
import hashlib
import json
import sys

root = Path.cwd()
sys.dont_write_bytecode = True
sys.path.insert(0, str(root / 'scripts'))
import build_compact_wubi as builder

pinned_manifest_path = root / 'third_party/wubi_supplements/source-manifest.json'
pinned = json.loads(pinned_manifest_path.read_text(encoding='utf-8'))
manifest = copy.deepcopy(pinned)
local = root / 'third_party/wubi_local'
source_dir = local / 'sources'
expected_keys = tuple(pinned['expected'])
manifest['local_change_note'] = (
    'Locally maintained derivative. Upstream URLs/revisions are provenance only; '
    'current hashes/counts describe the edited local copies.'
)
manifest['original_manifest_sha256'] = hashlib.sha256(pinned_manifest_path.read_bytes()).hexdigest()

for index, spec in enumerate(manifest['sources']):
    path = source_dir / spec['filename']
    data = path.read_bytes()
    lines = data.decode('utf-8-sig', errors='strict').split('\n')
    body = []
    started = False
    for number, original_line in enumerate(lines, 1):
        line = original_line[:-1] if original_line.endswith('\r') else original_line
        if not started:
            if line.strip() == '...':
                started = True
            continue
        if line.startswith('#') or not line.strip():
            continue
        body.append((number, line.split('\t')))
    fallbacks = []
    for old in spec.get('weight_zero_fallbacks', []):
        identity = (old['text'], old['code'], old['raw_weight'])
        matches = [number for number, parts in body
                   if len(parts) >= 3 and tuple(parts[:3]) == identity]
        if len(matches) > 1:
            raise SystemExit(f'{path}: 原兼容词条出现多次，请先消除歧义：{identity}')
        if matches:
            updated = dict(old)
            updated['line'] = matches[0]
            fallbacks.append(updated)
    entries, counts = builder.parse_dictionary(
        data, spec['filename'], supplement=index != 0,
        weight_zero_fallbacks=fallbacks,
    )
    spec['source_lock_before_local_edit'] = {
        key: spec[key] for key in ('revision', 'sha256', 'bytes', 'valid_rows') if key in spec
    }
    spec['bytes'] = len(data)
    spec['sha256'] = hashlib.sha256(data).hexdigest()
    spec['valid_rows'] = counts['valid_rows']
    spec['weight_zero_fallbacks'] = fallbacks
    spec['locally_modified'] = spec['sha256'] != spec['source_lock_before_local_edit']['sha256']

# 只在这里计算新基线；compile_sources 仍严格检查新来源锁、格式、
# 二进制结构和所有精确候选/前缀候选相对参考算法的一致性。
manifest['expected'] = {}
base = source_dir / manifest['sources'][0]['filename']
_, report = builder.compile_sources(base, source_dir, manifest)
manifest['expected'] = {key: report['counts'][key] for key in expected_keys}
output = local / 'source-manifest.local.json'
output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('已重新固定本地来源：', output)
print(json.dumps(manifest['expected'], ensure_ascii=False, indent=2))
print('本步骤未改写 app 资源；下一步使用此 manifest 生成索引。')
PY
```

检查源文件差异、本地 manifest 及计数是否符合预期后，才执行实际索引生成。下列命令会更新下一次 APK 使用的五笔资源：

```sh
python3 scripts/build_compact_wubi.py \
  --base third_party/wubi_local/sources/wubi86.dict.yaml \
  --supplements third_party/wubi_local/sources \
  --manifest third_party/wubi_local/source-manifest.local.json \
  --output app/src/main/assets/dictionaries/compact-wubi.skwb \
  --report third_party/wubi_local/BUILD_REPORT.json

python3 scripts/build_compact_wubi.py \
  --base third_party/wubi_local/sources/wubi86.dict.yaml \
  --supplements third_party/wubi_local/sources \
  --manifest third_party/wubi_local/source-manifest.local.json \
  --output app/src/main/assets/dictionaries/compact-wubi.skwb \
  --verify-only
```

后续编辑本地副本后须重新固定本地 manifest，再生成、验证索引。不能拿原 manifest 校验自改数据，也不能继续用默认命令把定制索引覆盖回原版。以上流程按现有 manifest 的 `sources` 列表复制与处理所有来源，不限定数量。若要增加列表之外的新文件，需在本地清单中添加合法 `supplement` 项并扩展初始化/重固定步骤；仅把文件丢进目录不会自动加载。

### 拼音增删

直接编辑 `app/src/main/assets/dictionaries/pinyin_simp.dict.yaml` 的 `...` 后词条。推荐三列：

```text
词语<TAB>小写拼音、音节间空格<TAB>整数静态权重
离线键盘<TAB>li xian jian pan<TAB>0
```

实际字段分隔仍用 Tab。系统拼音源的编码字段只接受小写 `a-z` 和空格；不要在源编码里写声调数字、带调字母或英文单引号，`ü` 写成 `v`。输入时允许单引号分音节，与源文件格式不同。权重越高越靠前；相同权重保留文件行序。运行解析器会跳过部分格式错误行，故应审查修改并添加对应候选测试，不能仅以「成功打包」证明词条有效。

拼音不经过五笔转换脚本，修改后随 APK 重新打包生效。删除词条时检查所有同词不同读音记录。即使删去一个完整短语，拼音分段组合仍可能从剩余的字/短语重新组合出同样文本；这不是全局屏蔽词功能。

### 英文补全增删

`app/src/main/assets/dictionaries/english_frequency.tsv` 格式与设置中的英文手工词库不同：

```text
lowercaseword<TAB>12345
```

系统英文词必须是 **2～40 个小写 ASCII 字母**，第二列是 **1～2147483647 的正整数静态频率**。空行和 `#` 开头注释不参与；同词多次出现时保留最高频率，频率相同按单词字母顺序排序。不支持系统多词短语、空格、撇号、连字符、重音字符；这些不应与可自定义任意输出文本的用户英文词库混淆。只补全当前单词的严格延伸，不回显与输入完全相同的词。

可直接增删成品 TSV 后重建 APK，但要保留许可/归属，记录本地修改，并更新自己的词条数量、成品哈希及测试预期。**原始重建脚本会覆盖成品 TSV**，不会自动保留手工修改。需要长期维护时，将自己的增删规则保存为单独、可审查的后处理步骤，明确在每次原始重建之后应用；当前仓库未提供自动本地覆盖层。

复现原始 25,000 词，而非应用本地修改时：

```sh
python3 third_party/english/rebuild.py --cache /path/to/source-cache
```

首次会下载并校验固定数据；缓存中两份源都存在时不联网。原始来源如下，勿替换为未固定的 `main` 分支：

- FrequencyWords：commit `525f9b560de45753a5ea01069454e72e9aa541c6`，`https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt`，SHA-256 `5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458`。
- SCOWL：版本 `2020.12.07`，`https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz`，SHA-256 `5587667caa20c4891390c2d42dbb4d5c4c3f41bee77af1457ece3ba23fb859cc`。

### 修改后的验证与边界

- 改的是系统词库，不会自动清理用户手工词库、常用语或剪贴板；同样文本还可能从这些入口出现。
- 中文是五笔与拼音混合候选；只删五笔不代表拼音也不能显示。内置 `useskey` 在独立 Kotlin 映射中，删除 YAML 记录不影响它。
- 保留上游原文件用于溯源的本地流程只改变运行索引，不承诺词语字节从源码或 APK 的所有附带原始文件中消失，也不提供内容屏蔽保证。
- 新增生僻字可能超出现有候选字体子集。`scripts/build_candidate_fonts.py` 当前只扫描 `app/src/main/assets/dictionaries/*.dict.yaml`，不会扫描 `third_party/wubi_local`、五笔补充目录或 `.skwb`。不能直接声称运行原字体脚本就覆盖所有新本地词；应先让字体维护流程明确纳入实际词源并检查覆盖，详见 `third_party/CANDIDATE_FONTS.md`。目标应用上屏后的字体仍由目标应用决定。
- 现有回归测试固定原始数据：`tests/test_compact_wubi_builder.py` 的 `FullPinnedSourceTests`、`CompactWubiTableTest.kt`、`DictionaryEngineTest.kt`、`EnglishLexiconTest.kt` 包含原始行数、哈希、候选与顺序断言。自改后应根据审核过的新数据更新对应预期、增加增删回归，保留原始来源测试的用途，不要通过删除测试或笼统放宽断言来掩盖问题。
- 审核字典/manifest/生成资源/许可变更后，按本 README 的构建步骤运行 `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` 和 `python3 -X utf8 tests/privacy_audit.py`。用户选择何时进行设备验收；本文没有执行这些命令，也不把未来自改版继承为任何已交付版本的测试结论。
- 给自改构建使用新版本标识和独立验证记录，保留可复现来源；签名不同不能直接覆盖原安装，勿以卸载作为默认修复方式，卸载会删除应用私有内容。
- 基础五笔与拼音的固定 revision 分别为 `152a0d3f3efe40cae216d1e3b338242446848d07` 和 `0c6861ef7420ee780270ca6d993d18d4101049d0`；五份补充来自 `xianghongai/rime-wubi-dict` 的 `1e6afaaa8c7bd605b604d9dc1314d496a8220b92`。详见原 `NOTICE.txt` 和 `source-manifest.json`。保留上游许可证；五份补充的未明确授权状态不会因本地修改或重新固定哈希而改变。

### 中文候选的实际显示顺序

内置引擎按以下阶段合并：五笔完整编码 → 精确拼音 → 已启用的模糊拼音 → 没有精确拼音且输入长度至少4时的本地拼音组合 → 五笔前缀补全。`useskey` 是仅此编码生效的独立内置彩蛋，先于普通查询。每阶段按完整显示文本去重，先出现的位置保留。

普通五笔/拼音按文件静态权重降序，同权重保持文件/来源顺序；五笔来源顺序是基础、高优先级、IT、行政区划、词语、历史文化名城、成语。补充库权重固定0。高优先源只在离线生成完整编码组时提升，不给所有前缀加权。五笔前缀每组最多保留按当前静态权重与来源顺序排列的前30项。

随后合并用户中文词：保留首个内置 → 用户精确编码匹配（保存顺序）→ 其余内置 → 用户部分编码匹配（保存顺序），全文去重；没有内置时用户精确可第一。最后将含扩展B的字符串稳定移至末尾。英文使用自己的静态频率排序（同分字母序）及用户词合并，不经过中文扩展B阶段。

## 模糊音与配色

模糊拼音总开关下面可独立启用 zh/z、ch/c、sh/s、n/l、r/l（默认关闭）、f/h、an/ang、en/eng、in/ing。关闭总开关会保留各组选择。五笔精确候选仍优先。

浅色与深色分别保存15项颜色。普通键、功能键、回车/强调键、选中/锁定键分别有背景和文字/图标色；工具图标、编码、候选、角标、分隔线也可独立设置。HSV色盘、色相条及不透明的 #RRGGBB 可实时预览；只有确认保存才写入当前颜色，取消、返回或点外部不会保存草稿。低对比度会提醒但允许保存。恢复默认只影响当前编辑的那套颜色，清空内容不会删除配色。浅色默认功能键背景为 #D0D2D9、分隔线为 #CFD8DB，已保存的自定义值不覆盖。

设置应用与模型设置固定使用独立浅色设计（#EFEFF1背景、#FFFFFF卡片、#DFE2E9功能面等），不随键盘或系统主题变化。开关直接保存，数值和颜色编辑确认后保存。页面和弹窗内容可滚动，短横屏大字体下字段与色盘不保证同时全部显示。

## 布局

外屏/内屏 × 竖屏/横屏分别保存。内外屏以当前 Android 配置的600dp阈值识别，需要在实际折叠设备验证。高度、左右对称留白和底部留白可在键盘内调整，也可在设置中修改。悬浮模式底部横条可拖动、右侧矢量图标可缩放，整块键盘采用小圆角，并沿圆角绘制1dp当前主题分隔线色边框；边框不改变布局与点击区域。分离布局用于内屏。非悬浮模式两侧与底部留白填充键盘背景，悬浮框外保持透明。空闲工具栏与编码＋候选区总高度相同，输入时字母键不会被压缩。

默认布局（dp）：外屏竖屏高度285／两侧各0／底部0；内屏横屏240／各120／0；内屏竖屏270／各20／0。外屏横屏保持210／0／0。震动强度默认10%。这些是未保存配置时的默认值，不覆盖已保存的用户设置。

## 开发与构建

脚本使用 Python 标准库；本次验证环境为 Python 3.12.14。构建需要 JDK 17、Android SDK 35（含 AGP 默认使用的 Build Tools 34.0.0、Build Tools 35.0.0 和 platform-tools）。项目固定使用 Gradle 8.9、Android Gradle Plugin 8.7.3、Kotlin 2.0.21；Gradle Wrapper 已附，并校验发行包 SHA-256。最低 Android 8.0（API26），目标 Android15（API35）。

源码 ZIP 不包含约48 MiB的 sherpa-onnx AAR、语音模型、测试音频、签名密钥、构建缓存或本机 SDK 路径。解压后，在项目根目录按顺序运行：

1. 设置 JAVA_HOME 指向 JDK 17，并设置 ANDROID_HOME 指向 Android SDK；也可在本机创建 local.properties，填写 sdk.dir。
2. 安装所需 SDK：`sdkmanager "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0" "platform-tools"`，自行阅读并接受 SDK 许可。
3. 运行 `python3 scripts/fetch_voice_dependency.py`。脚本只下载官方 sherpa-onnx 1.13.8 固定版本，校验50,129,134字节大小和下列 SHA-256 后才写入 app/libs；已有正确文件时不会重新下载。
4. 运行 `python3 scripts/fetch_voice_dependency.py --verify-only` 再次核验依赖；此命令不访问网络。
5. 运行 `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`。
6. 运行 `python3 tests/privacy_audit.py`。设备测试见 tests/VOICE_DEVICE_TESTS.md；测试模型与音频需另外准备，不能把测试素材打包进应用。

### Linux / macOS 命令示例

在解压后的项目根目录（与本 README、`gradlew` 同级）执行。请将前两项路径替换为本机实际安装路径；接受 SDK 许可前先阅读其条款。

```bash
export JAVA_HOME="/absolute/path/to/jdk-17"
export ANDROID_HOME="/absolute/path/to/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
python3 scripts/fetch_voice_dependency.py
python3 scripts/fetch_voice_dependency.py --verify-only
chmod +x gradlew
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
python3 tests/privacy_audit.py
```

Windows 使用 JDK17 和 Android SDK 设置对应环境变量，`gradlew.bat` 替代 `./gradlew`，`py -3` 替代 `python3`；隐私审计建议运行 `py -3 -X utf8 tests/privacy_audit.py` 以正确读取中文源码。使用已启用USB调试并授权的ARM64设备安装本机构建包：

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

本机首次构建会生成自己的调试签名，通常不能覆盖安装使用另一签名的已交付APK；不要为了覆盖安装而盲目卸载，卸载会删除应用私有内容。签名密钥不在源码包内。

固定 AAR 的 SHA-256：`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`。官方下载地址、许可与模型版本见 third_party/VOICE_DEPENDENCIES.md。Windows 可用 `py -3` 替代 python3，并用 gradlew.bat 替代 ./gradlew。

生成独立 arm64-v8a 与 x86_64 调试 APK，位于 app/build/outputs/apk/debug；测试 APK 位于 app/build/outputs/apk/androidTest/debug。首次准备源码依赖需要访问官方 GitHub、Gradle、Google/Maven 仓库；安装后的键盘不联网。该流程可复现源码构建，但每台开发机的调试签名、构建环境和 APK 字节可能不同，不承诺逐字节相同。

源码包包含 SOURCE_MANIFEST.sha256，可在解压目录运行 `sha256sum -c SOURCE_MANIFEST.sha256` 校验包内文件。维护者重新打包时，先更新 TEST_RESULTS.md，再运行 `python3 scripts/package_source.py --version 0.1.8 --final-results`。不加 --final-results 会明确生成验证结果待完成的预览包，不会误带旧版结论。

## 项目结构

```text
offline-keyboard/
├── README.md                      # 使用、构建与项目说明（项目根目录）
├── TEST_RESULTS.md                # 最终验证报告与未覆盖项
├── REQUIREMENTS.md                # 功能约定及变更记录
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── gradle/wrapper/                # 固定版本 Gradle Wrapper
├── app/
│   ├── build.gradle.kts
│   ├── libs/                      # 构建前下载的语音AAR，源码包不含
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/            # 静态词库、字体与许可
│       │   ├── res/               # Android资源、图标与样式
│       │   └── java/io/secure/keyboard/
│       │       ├── OfflineKeyboardService.kt
│       │       ├── data/          # 内容及独立配置
│       │       ├── engine/        # 五笔/拼音/英文与候选策略
│       │       ├── ui/            # 键帽、候选、工具和手势
│       │       ├── settings/      # 固定浅色设置组件与色盘
│       │       ├── voice/         # 模型导入、离线识别及提交守卫
│       │       └── concurrent/    # 有界派生任务调度
│       ├── test/                  # JVM测试
│       └── androidTest/           # Android设备测试源码
├── scripts/                       # 固定依赖获取、词库转换与源码打包
├── tests/                         # 隐私审计与专项检查工具
└── third_party/                   # 依赖来源、哈希、许可与资源归属
```

源码ZIP另含 `SOURCE_PACKAGE.json` 与 `SOURCE_MANIFEST.sha256`。解压后的 `offline-keyboard/` 即项目根目录，可直接作为仓库根目录使用；仓库公开访问不代表第三方资源的许可已全部厘清，请保留并审阅下方许可说明。

## 验证范围

最终0.1.8/build53：174项JVM、34项Python词库检查通过，lint0错误/28警告，APK签名、权限、打包词库哈希和静态隐私审计通过。8条内置词码全码首位；同权来源序采用基础→高优先级→IT→行政区划→词语→历史文化名城→成语，普通同权及前缀顺序可随此改变，全部精确词条保留。运行时Kotlin/DEX/native未改变。此版未运行模拟器，按要求由用户实机测试；0.1.6最后追加的边框、两项浅色值、中文彩蛋及符号替换同样没有补做设备测试。

此前设置/首屏基线46项有通过证据；资源候选阶段64个方法完整通过、3项未闭环、1项取消。自然内存仅完成词库与设置两组B/C；C语音及外部输入对照取消，不能称四组全部完成或没有泄漏。具体构建身份、数字与限制见 [TEST_RESULTS.md](TEST_RESULTS.md)。

实机重点检查：常用输入框快速输入、密码/URL/邮箱/数字首屏、折叠与横竖屏、悬浮边框/拖动缩放、浅深自定义、设置色盘保存取消、双词库导入导出与清除、语音开始停止/取消/切换编辑器。软件模拟器不代表Samsung实机的触感、功耗、内存或折叠适配表现。

## 第三方组件

- 官方 Rime 五笔86原始词库（LGPL-3.0），简体拼音词库（Apache-2.0）；字典文件及许可完整保留在 assets/dictionaries。实际运行的是自定义 Kotlin 查询引擎，不是嵌入式 librime。文件见 [dictionaries](app/src/main/assets/dictionaries)。
- sherpa-onnx 1.13.8、ONNX Runtime；许可见 `third_party` 与 APK 的语音许可查看页。
- Kotlin 2.0.21 标准库、JetBrains annotations 及其适用第三方声明随 APK 保存在 assets/runtime-licenses；准确版本、来源与 Gradle wrapper 许可见 third_party/RUNTIME_NOTICES.md。
- 英文 FrequencyWords/OpenSubtitles 2018频率数据（CC BY-SA 4.0）经 SCOWL 普通词过滤，许可证与完整归属见 assets/dictionaries/ENGLISH-NOTICE.txt。
- Hanazono开源字体子集覆盖词库中49,484个生僻汉字，仅在系统字体缺字时用于候选。提交后目标应用仍使用自己的字体；详见 third_party/CANDIDATE_FONTS.md。
- Unicode Emoji 16.0 全限定序列；字符显示取决于设备安装的 emoji 字体，旧系统可能缺少新字符。


### 许可与公开发布边界

当前项目根目录没有统一的LICENSE文件；本源码包用于本次私人测试与审阅，不授予未明确拥有的第三方权利。代码、词库、语音运行时、模型、字体和表情数据有各自许可，不能将它们统一宣称为MIT或“全部可开源再分发”。特别是五份补充五笔词库缺少明确上游LICENSE，转换后ONNX模型卡也未重复声明许可证；模型因此不随源码或APK分发。计划公开仓库或发布APK前，应逐项确认权利并保留适用归属、许可与源代码义务。
