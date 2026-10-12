# 0.1.16 正式版打包验证（2026-10-13，Asia/Singapore）

- 版本0.1.16，versionCode17。删除偏好设置、外观、反馈、输入、词库和离线语音页标题下的介绍文案；保留操作提示和功能说明，键盘未改动。
- `testReleaseUnitTest assembleRelease`成功，192项单元测试通过，0失败/错误/跳过；`git diff --check`通过。本轮未重复设备回归。
- ARM64与x86_64正式APK沿用原发布密钥，与0.1.15签名证书一致。签名、对齐、版本和非debuggable检查通过，SHA256SUMS随包保存于被Git忽略的`releases/0.1.16/`。

# 0.1.15 正式版打包验证（2026-10-13，Asia/Singapore）

- 版本提升为0.1.15，versionCode16，包含下述设置设计与编辑生命周期修复；键盘服务、输入引擎和按键布局未改动。
- `testReleaseUnitTest assembleRelease`成功，192项正式构建单元测试全部通过，0失败/错误/跳过。设备与响应式检查沿用本次设置改动完成后的验证，见下一节。
- ARM64与x86_64正式APK均使用已有发布密钥签名，与0.1.14证书SHA-256一致；apksigner签名验证、zipalign校验、版本号检查通过，均非debuggable。
- 安装包及SHA256SUMS保存在被Git忽略的`releases/0.1.15/`，签名私钥和密码未纳入版本管理。

# 应用设置设计落地验证（2026-10-13，Asia/Singapore）

- 按确认后的设计统一全部伴随应用设置界面：`#2b2b2b` 主色、暖白背景、卡片、标题、导航行和按钮；24dp 矢量箭头替代文本箭头。外观、反馈手势、输入补全、内容清理独立分组，保留深链接、返回层级与滚动恢复。
- 词库采用约56dp紧凑行，词语/编码上下排列，右侧20dp编辑/删除图标保留48dp触摸区域；导入导出并排，格式说明折叠，计数/分页/全量编辑/分类清理保留。常用语和剪贴板操作收紧，主题编辑保留交互预览与全部15项颜色，并改为颜色样本+名称/HEX导航行。
- 修复原生单行字段隐式5000字符截断；长文本保持完整，原有保存校验仍生效。恢复选区安排在窗口首次获得焦点之后，相关监听在执行、取消和销毁时移除；常用语编辑关闭系统拼写建议。没有修改键盘服务、输入引擎、按键布局或键盘主题定义。
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintRelease` 成功：192项JVM用例，0失败/错误/跳过；lint 0错误、24警告；`git diff --check`通过。
- BlueStacks ARM64 / Android13：最终分组验证30个不同设备用例通过：设置页面10、编辑生命周期9、内容分页1、双语词库7、实际键盘输入/弹出3。设置渲染覆盖19个路由及主页、离线语音页，检查浅色应用外观不随键盘配色改变。
- 额外运行320dp窄屏+1.5倍字体、640×320dp矮横屏的原生控件和弹窗可达性测试，两种场景均通过；显示尺寸和字体缩放恢复原值。
- 60万UTF-16代码单元的常用语连续10次旋转后完整文本/选区保留，较大编辑不进入保存状态Bundle。持有恢复对象时，旧快照和销毁页面的弱引用回收断言通过。
- 同进程键盘连续10次弹出/收起：Java已用堆63,541,376→57,220,368字节，PSS161,217→160,158KB；该有限场景未出现持续堆积，不代表对全部设备/无限运行时间的保证。
- 初轮失败已保留用于追溯：滚动测试读取了未结束的动画；旧词库测试需要适配富文本按钮/图标和可空无障碍文本；GC测试在循环判断中读取弱引用，导致测试自身临时持有对象。均修正测试时序/识别，不删除原功能与回收断言。实际截断及选区恢复问题已修复后复测。
- 证据位于`app/build/reports/settings-design-2026-10-13/`。UiAutomation截图在本模拟器存在已知驱动问题，因此禁用该截图通道，视觉检查使用普通adb原生截图。未验证新的Android版本或实体设备。
- 已覆盖安装0.1.14调试版到BlueStacks；本轮未提升版本号、未签发正式包、未提交或推送Git。

# 首页设计与候选字号验证（2026-10-12，Asia/Singapore）

- 首页以#2b2b2b为主色，使用浅色背景、炭黑主卡片、原生键帽插画、圆角按钮及设置/语音入口卡片；显示实际启用/当前输入法状态，保留试打不落盘和全部原有入口。插画复用画笔、字形及渐变，不在每次绘制时创建这些对象。
- 外观与按键新增候选栏和展开候选两项字号滑块，范围12–32sp，默认20/18sp，持久化并在重新打开键盘后生效。同步测量字体及候选栏/网格布局。
- 候选反馈延续触摸按下立即请求的行为；无障碍和程序化点击在候选验证后反馈，异步英文选择保留每次点击的反馈状态，避免重复震动。
- JVM187项通过；设备最终49项通过（39.541秒），包含字号持久化/边界/渲染、候选网格/候选栏、候选反馈及设置深链接返回导航。debug编译成功，lint无错误、28项原有警告，git diff --check通过。
- 首轮候选测试受模拟器此前保存的英文模式影响超时，已隔离测试语言并恢复原值。完整自动截图回归另在BlueStacks图形驱动lib_renderControl_enc.so中触发am instrument进程SIGSEGV，随后UiAutomation断连；最终49项不含该自动截图测试及依赖截图的全页面捕获测试，不宣称这些测试通过。普通adb截屏和手动首页/设置入口检查成功。
- 已安装debug测试版；未使用新建正式签名密钥。构建日志、初次失败记录、最终测试和界面截图见app/build/reports/home-candidate-size/。

# 悬浮键盘缩放验证（2026-10-12，Asia/Singapore）

- 拖动横条绘制长度约为原来的三倍，缩放图标改为左上—右下双向箭头，原触摸区域保留。
- 缩放使用按下时实际布局边界：向右下放大，向左上缩小；高度与底部距离之和固定，横向尺寸不超过左侧锚点到屏幕右边缘的距离。
- debug编译成功，JVM186项全部通过（新增4项锚点/边界测试）；BlueStacks的KeyboardBottomRowTest共8项通过，耗时9.479秒；git diff --check通过。
- BlueStacks手动验证放大及缩小：两次均保持屏幕左上锚点不变。截图及日志保存于app/build/reports/floating-resize/。已安装新版。

# 按下触感与面板间距验证（2026-10-12，Asia/Singapore）

- 工具面板和快捷编辑的横纵卡片间距由6dp改为10dp，已在BlueStacks安装并查看。
- 触摸震动请求提前到ACTION_DOWN；普通键、工具、表情、候选和内容操作在松手时不重复请求。输入/点击逻辑仍在确认点击后执行，保留系统震动开关、强度、长按功能反馈和无障碍点击反馈。同一按键多指重叠时新增手指也立即请求反馈。
- JVM测试182项全部通过（含新增4项反馈时序测试）。BlueStacks设备回归52项全部通过，耗时55.472秒：HiddenPanelLifecycleTest、RapidTypingTest、CandidateGridTest、CandidateStripPagingTest、KeyboardFeedbackRoutingTest、KeyboardHapticsRoutingTest、ContentEntryRowTest。
- 新增设备测试通过记录根视图的触觉请求，验证普通键、工具、快捷编辑、表情和剪贴板在DOWN时请求一次反馈且尚未执行操作，松手不重复请求；模拟器测试不代表真机马达启动延迟或主观震感。
- 完成debug编译、lint及git diff --check。详细构建/设备日志保存在app/build/reports/press-feedback/。

# 本地隐私与内存修复验证（2026-10-12，Asia/Singapore）

基于 Git 提交 `a250226afdf996f8e11a3565c5c2befc4695903f` 的工作区修复。主应用版本标识仍为0.1.8/versionCode 9；本节对应本地修复版，不是下方历史build53的重新背书。没有提交或推送远端。SOURCE_MANIFEST.sha256仍是原始源码清单，不代表修改后工作区。

## 修复内容

- 采集前检查 `android.content.extra.IS_SENSITIVE`，敏感内容不进入剪贴板历史；超长文本不截断保存，系统剪贴板保持可粘贴。
- 剪贴板单条最多65,536个UTF-16代码单元，历史合计最多1,000,000；待保存任务含执行中最多32个、合计262,144。溢出拒绝新任务，已接受的复制事件按顺序保存，保留重复复制和优先淘汰未置顶项的规则。
- 常用语最多10,000条、1,000,000代码单元；每种用户词库的词条与编码合计最多4,000,000。超限操作原子拒绝；旧超限数据不自动删除，仍可缩短、删除或修改置顶状态。
- 剪贴板采集代际读取改为原子读取，不在主线程等待内容I/O锁。键盘内容面板后台读取和修改、ListView复用可见行，过期结果不能恢复已关闭面板；管理页面每页最多100条。
- JSON流式写入，避免完整JSON字符串和UTF-8字节数组同时存在。保留原有落盘同步与替换机制。
- 测试APK补充版本名、版本号和应用元数据；BlueStacks Air 5.21.790.7505此前在无versionName的安装通知中触发原生SIGABRT，日志栈含 `xjsonGetString` / `plrOnAppInstalledHcall`。补齐元数据后安装及完整回归成功，未改变主应用权限。

## 本次实际验证

- `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`：成功。JDK17/Homebrew、SDK35、Gradle8.9。
- JVM：178项，0失败、0错误、0跳过（含新增4项有界队列测试）。
- Python：34项词库测试通过；`tests/privacy_audit.py`通过；`git diff --check`通过。
- lint：0错误、28警告。
- BlueStacks ARM64 / Android13（模拟型号SM-G998B）：最终一次完整运行 **86项全部通过**，45.945秒。覆盖敏感标记、超长文本、总容量、超限原子拒绝、旧超限数据兼容、Unicode/转义往返、双语词库、置顶/删除/完整插入、视图复用、管理页分页、隐藏面板、密码框及语言生命周期。
- 首轮85项通过、1项测试报错：旧同步测试未等待后台读取，出现空列表；补充异步等待后重跑整组86项通过，没有删除原有功能断言。
- 原始日志保存在 `app/build/reports/privacy-memory/`；JVM XML在 `app/build/test-results/testDebugUnitTest/`。

## 千条剪贴板场景内存采样

最终设备回归中，1,000条合成记录连续20轮打开、滚动到末尾、收起。每轮验证列表总记录数为1,000、挂载行数在1–30之间、收起后无内容行残留。第5/10/15/20轮自然PSS分别为107,291 / 116,184 / 109,243 / 115,119 KiB；native已分配字节分别为20,671,888 / 24,186,352 / 22,109,872 / 25,624,096。

这是同一回归进程中的短时采样，未强制GC、没有旧版本对照、没有堆转储归因。可确认该场景视图规模有界、PSS未逐次单调增长；不能据此宣称零泄漏或总体RAM下降。语音模型的长期native内存、真机长时间输入及旧超限数据的实际峰值未在本轮测量。

## 产物与兼容边界

- ARM64调试包：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- SHA-256：`6892e32222bf6f6718c2afa438439a3dda87a1b772d491c8bea498c648257c67`
- 修复版已安装到BlueStacks。设备测试主要使用隔离数据；原有密码框剪贴板测试临时写入合成内容并在finally恢复系统剪贴板。
- 未对已保存历史做敏感数据猜测或批量删除；旧版历史不含敏感标记，已有密码需用户自行删除。容量单位是UTF-16代码单元，普通输入、完整文本插入、词库查询和语音行为保持原有实现。

---

# S Key 0.1.8 验证报告

日期：2026-10-12 UTC。最终构建53，包名 `io.secure.keyboard`，versionName `0.1.8`、versionCode `9`。已交付0.1.7/build49冻结；未交付的0.1.8/build51由本产物替代。遵循用户要求，本次只编译、JVM、Python和静态检查，**没有模拟器/设备测试或新增内存实验**。

## 最终APK

- 文件：`S-Key-0.1.8-build53-arm64.apk`
- 大小：40,559,463字节
- SHA-256：`55eed67df06d144de0286edea5e29d7eb26b9865f939c35924fbe1e07d675966`
- 签名证书：`99be6d7dc8a3083946ec593fcca746c1446c119324b928c57e0cfe38990b96be`
- minAPI26、targetAPI35；仅RECORD_AUDIO/VIBRATE，无INTERNET。

build52已完整编译测试；build53使用同一源码重新打包，去除增量ZIP遗留空隙。两者所有ZIP条目解压内容逐字节相同，最终签名重新验证通过。测试不重复计数。

## 高优先级系统词库与来源顺序

独立词库 `third_party/wubi_supplements/wubi86_high_priority.dict.yaml` 包含原样8词：微信tmwy、安卓pvhj、北川uxkt、美团uglt、抖音ruuj、支付宝fwpg、滴滴iuiu、微博tmfg。旧普通本地补充文件已移除，不双维护。manifest使用通用supplement来源标记 `exact_priority: true`，生成器读取该文件全部词码，并将它们置于对应完整编码精确组首。支持合法1–4位编码、同码多词文件顺序、跨普通来源相同词码提权及去重，不使用运行时硬编码8词映射。

最新指定来源顺序为：

1. 基础五笔 `wubi86.dict.yaml`
2. 高优先级 `wubi86_high_priority.dict.yaml`
3. IT `wubi86_dict_it.dict.yaml`
4. 行政区划 `wubi86_dict_xzqh.dict.yaml`
5. 词语 `wubi86_dict_ciyu.dict.yaml`
6. 历史文化名城 `wubi86_dict_china_cultural_cities.dict.yaml`
7. 成语 `wubi86_dict_chengyu.dict.yaml`

普通候选仍先按静态权重降序，同权重再按上述来源及文件行序；高优先完整编码置顶额外保留。本次普通同权候选、前缀选择/顺序可以随来源改变，**不再承诺与旧前缀相同**。用户词库第二位、全文去重、中文扩展B置尾和四码自动触发条件保持。开启四码自动上屏时，当前8词均从同一精确索引首项提交。

## 数据与检查结果

- 索引441,796条、185,875个精确组、16,103个前缀组、305,579条前缀引用，8,624,375字节，数量与之前一致。
- 新索引SHA-256：`922caacc74bfdd0a41fdf13d322446889693e3a46bd27aac91ee479e896d25d1`。实际APK内哈希一致，仍ZIP_STORED只读映射。
- 高优先源583字节，SHA-256：`0a1f37db73a00f71577860b622fd8b175574e8dc6c0a6b6ce3542f3a232deee1`；数据8行未改，注释明确同权来源顺序。
- 对比未交付build51：12,166个精确组顺序变化；8,790个前缀组候选/顺序变化，其中3,704个前30成员集合变化。**所有精确组成员集合完全相同，无词条删减**；前缀变化来自既有前30选择规则及新的同权顺序。
- 34项Python全部通过，无跳过，含独立全量精确/前缀参考、同权来源/行序及权重优先、通用高优先、双次确定性生成和不同PYTHONHASHSEED下的verify-only。
- 174项JVM全部通过，0失败/错误/跳过。CompactWubiTable检查8词首位、用户第二位、全文去重、ExtB尾部、自动首选一致及独立当前来源解析的精确/前缀/UI期望；不继续沿用旧前缀固定哈希。
- 完整构建 `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` 成功。lint0错误、28警告；静态隐私审计通过，181项编译源码清单一致。
- 53个运行时Kotlin文件与冻结49逐项SHA相同；最终DEX和native库与49逐字节相同。变化在来源清单、离线生成资源、测试、版本与说明文件。
- 签名、版本、权限、新索引及重新打包内容核验通过。未运行本版设备测试，也无新RAM/延迟结论。

## 维护与边界

根README包含完整使用、构建、词库格式/路径、排序管线及维护流程。未来维护高优先词，只编辑该源（或独立本地副本），重新固定清单、生成索引及构建，无需改应用代码或维护另一份逐词映射。删高优先行若普通源仍有相同词码，只取消优先，不等于全局删词；其他拼音/用户词库入口独立。

源码包含全部词库来源、生成工具、README、报告及哈希清单，不含私钥、语音模型、录音、私人快照或本机路径。五份第三方补充词库许可未明确，私人测试/公开发布边界未改变。

旧benchmark测试参考已适配优先源派生，Python语法检查通过；新的Java benchmark参考未执行，不宣称历史内存数字已在本版重现。下面保留0.1.7与0.1.6历史验证记录，不能作为本版重新执行的设备/内存成绩。用户接管实机验收。

---

# 附录：0.1.7/build49历史验证报告


日期：2026-10-12 UTC。最终构建49，包名 `io.secure.keyboard`，versionName `0.1.7`，versionCode `8`。已交付0.1.6/build48产物保持冻结；同一签名的0.1.7可覆盖更新并保留配置/内容。继续遵守用户要求：只编译、JVM、Python及静态核验，**没有运行模拟器或本版设备测试**。

## 0.1.7 产物

- APK：`S-Key-0.1.7-build49-arm64.apk`
- 大小：40,558,423 字节
- SHA-256：`d8464e376d894648ef01d1e8c610068907356c5b1d25177c5c24895ba6ed97c5`
- 签名证书SHA-256：`99be6d7dc8a3083946ec593fcca746c1446c119324b928c57e0cfe38990b96be`
- 最低API26，目标API35；仅RECORD_AUDIO、VIBRATE，无INTERNET。

## 0.1.7 修改范围

新增独立内置五笔补充源 `third_party/wubi_supplements/wubi86_dict_local_builtin.dict.yaml`，8个词码原样保留：

| 词语 | 编码 |
|---|---|
| 微信 | tmwy |
| 安卓 | pvhj |
| 北川 | uxkt |
| 美团 | uglt |
| 抖音 | ruuj |
| 支付宝 | fwpg |
| 滴滴 | iuiu |
| 微博 | tmfg |

8对原来均不存在，全部新增。原基础库与五份上游补充的文件及source定义保持；已有441,788项及相对顺序保留。总计441,796项，185,875个精确编码组，16,103个前缀组、305,579条前缀引用。原按词语＋编码去重及权重排序保持，8词不承诺全部首选；清空用户词库不删除内置源。

新索引为8,624,375字节，SHA-256 `20110421260c6285ac0f81000a19d43244f1578e72bb3010047fbe2aa3a3fe9e`。APK实际打包的索引与此哈希一致，并保持ZIP_STORED，支持只读映射。

根README已完善中文项目说明、构建安装、模型导入、隐私、用户词库及内置词库维护。维护指南给出真实源位置、独立本地清单重固定与索引重建命令，并注明跨源删除、拼音组合、英文原素材外部获取、字体子集及许可边界。文档片段已在独立临时数据夹具验证，不改已交付APK。源码根目录包含README及本报告。

## 0.1.7 检查结果

- 同次构建 `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` 成功。
- **173项JVM通过**：0失败、0错误、0跳过；含实际CompactWubiTable加载8词、原候选策略及用户词库清空隔离检查。
- **26项Python词库检查通过**，无跳过。严格源哈希/大小/行数校验、全精确/前缀参考校验、两次确定性生成、已有资产逐字节核对及原数据保留均通过。
- 8对精确查询分别各命中1次，未自行修正编码。
- lint0错误、28警告，分类与0.1.6相同，详见下方历史报告；不是无警告。
- APK签名/版本/权限/新索引哈希检查通过，静态隐私审计通过，181项编译源码清单核对一致。
- 与48的ZIP条目比较无新增或删除文件；变化仅AndroidManifest版本、compact索引和两份词库NOTICE。DEX与native库保持原字节。
- 不重跑此前内存或设备场景，不把旧结果视为新增8词后的实机通过，也不从索引增加95字节推断RAM变化。

## 许可与测试边界

源、生成物、来源和哈希清单均在源码包中；不含签名私钥、语音模型、音频、私人快照或本机配置。文档不授予上游未明确授予的再分发权。五份第三方补充词库许可仍未明确，私人测试边界未因新增本地词改变。

下面保留0.1.6的真实设置、资源测试记录。它们是历史版本证据，**不是0.1.7重新运行的设备或内存检查**。用户接管实机验证，未完成的语音/外部输入对照仍未补跑。

---

# 附录：0.1.6/build48历史验证报告


日期：2026-10-12 UTC。最终构建48，包名 `io.secure.keyboard`，versionName `0.1.6`，versionCode `7`。这是 ARM64 调试签名私人测试版，可覆盖更新已安装的 S Key；签名身份保持一致。

用户在最后阶段明确接管实机测试，要求停止模拟器。已安全停止、恢复测试前数据与系统状态并关闭模拟器。最终追加的边框、浅色默认值、彩蛋和符号替换已完成编译、JVM及静态检查，**没有对最终构建48运行设备测试**。以下区分最终静态结果、早期设备证据及未完成项，不宣称全面通过或零泄漏。

## 最终产物

- APK：`S-Key-0.1.6-build48-arm64.apk`
- 大小：40,746,355 字节
- SHA-256：`382abb1bcc68def4e1e92ea23e221fb35784ee6cb89f4abfee7127435a2d9771`
- 签名证书 SHA-256：`99be6d7dc8a3083946ec593fcca746c1446c119324b928c57e0cfe38990b96be`
- 最低 API26，目标 API35。APK 实际权限表仅有 `RECORD_AUDIO`、`VIBRATE`，无 `INTERNET`。

源码 ZIP 不含签名密钥、语音模型、测试音频、私人数据快照、堆转储、构建缓存或本机 SDK 路径。构建依赖及第三方声明见 README 和 third_party；源码清单见 ZIP 内 `SOURCE_MANIFEST.sha256`。

## 本次功能与最后变更

1. 设置、应用入口及模型设置统一为独立固定浅色设计，不跟随键盘自定义或系统深浅。分组、导航、确认保存、返回及可滚动内容统一处理。
2. HSV 色盘、色相条及 HEX 精确输入，局部实时预览；保存才写入对应主题颜色，取消不改原值。浅色与深色独立保存。
3. 中文词库保留旧数据，新增独立英文手工词库，分别增删改、导入导出与清空，每类最多10,000条。内容清空保留配置。
4. 输入框首次可见帧按 EditorInfo 选择模式：文本密码、URL、邮箱、强制ASCII为英文；数字含数字密码为符号第一页。自动模式不覆盖记忆语言，显示后可手动切换。密码场景关闭候选、文字浮窗和剪贴板内容提示，保留显式粘贴入口。
5. 资源修改：派生查询最新任务有界；隐藏候选/非按键面板释放展示引用；模型导入单任务且退出页面解绑；完整未保存草稿用于同进程旋转，过大草稿不整体写入系统状态，不新增持久草稿历史。
6. 悬浮框沿原圆角绘制1dp当前主题分隔线色边框，不改变原布局参数。浅色默认功能键背景改为 `#D0D2D9`、分隔线改为 `#CFD8DB`；已保存自定义、深色和固定设置配色保留。
7. 中文精确编码 `useskey` 显示内置候选「由 Linn Benson 开发的输入法」。英文、部分编码、分隔编码不显示，不进入五笔自动上屏数据；清空用户词库不删除它。
8. 已实际查看用户标注图：中英文符号第2页首行第5、6键原√、π，分别改为中文《、》（U+300A/U+300B）和英文ASCII <、>（U+003C/U+003E）。其他字符及自动配对范围保持。
9. 用户撤回了“自定义全码第一位”的临时要求。最终保留原常规首候选、自定义精确词从第二位起、部分匹配末尾、中文扩展B整体置后的规则；四码自动上屏保持原精确五笔逻辑。相关排序文件与修改前哈希核对一致。

0.1.5 是未单独交付的内部基线，双词库功能随此版本一并交付；最后已交付版本为0.1.4/build34。

## 最终构建48的检查

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 同次构建成功。
- 172 项 JVM 测试：0失败、0错误、0跳过。包含5项新内置精确词测试、四页符号逐码点/顺序及浅深默认值检查。
- lint：0错误、28警告。分类为 SetTextI18n 14、DrawAllocation 4、ViewConstructor 4、MonochromeLauncherIcon 2，DiscouragedApi、ObsoleteSdkInt、UnusedResources、ClickableViewAccessibility 各1。不能称为无警告。
- 静态隐私审计通过：键盘内预编辑、无输入日志、无在线识别调用、语音最终段去重及编辑器/会话/光标守卫。
- 签名验证通过，打包权限与版本已实际检查；181项编译源码清单匹配。
- 测试APK仅编译，未安装或运行。静态检查不能替代系统实际行为、权限授予、布局像素或真实手机体验。

## 已有设备功能证据

设备环境为 Android10/API29、x86_64、无KVM软件模拟器，不是 Samsung Fold 实机。

设置与输入首屏基线：47项有界计划中46项有通过证据，其中41项在build40执行，5项沿用build38；真实系统深色1项未覆盖。范围含设置导航/保存、调色器、首屏策略、词库路由、稳定触摸目标及4项屏幕字体矩阵。矩阵包括320dp字体1.5、640×288dp横屏字体1.5、840dp字体1.5、320dp字体2关键弹窗。83张原生PNG已审阅，包含失败修复过程，不等于83个独立用例。早期5px按钮裁切已修复。短横屏大字体下内容区较浅，需滚动，控件不保证同时可见。

资源候选C44的68方法实际结果：**64个唯一方法完整通过、3项未闭环、1项取消未执行**。包含快速输入15、候选14、语音最终提交9、隐藏面板10、草稿7、模型导入3、首屏隐私2、英文过期候选2、文件路由1、调色器旋转1。重复运行不重复计数，不将通过片段拼成完整通过。

三项边界：
- 600,000 UTF-16字符、约100,000行草稿的十次真实旋转已保留完整内容/选区/焦点/错误提示。但另一个保存/永久退出方法虽已完整落库，末尾10秒销毁等待失败；实际 onDestroy 约67.7秒后到达，最后引用解绑断言未执行。极端渲染场景不是正常设置响应指标。
- 密码显式粘贴测试先遭系统拒绝无焦点剪贴板读取，补测又在真实窗口焦点前置等待超时，未证明原生粘贴通过。
- 旋转后保留的双词库传输测试等待导出对话框超时；同组文件选择目标路由另有完整通过，不能代替该方法成功/失败回报分支。

后续发现同一次模拟器启动存在 SystemUI ANR，时间覆盖上述焦点相关失败，且原生截图显示系统无响应弹窗。它是环境相关证据，不能直接证明每个失败都由此导致。两项轻量复验随用户停止指令取消；原失败保留。单独强制GC草稿归属检查从未启动。

## 资源审计与直接B/C证据

B为设置改版后、资源修复前的build40；C为资源修复后build44。5项结构检查使用相同tests43：

| 检查 | B | C | 结论范围 |
|---|---:|---:|---|
| 剪贴板/常用语面板隐藏后的全文展示行 | 每类仍10行、40,960 UTF-16单位 | 每类0行、0全文字段引用 | 释放隐藏展示引用，不等于删除允许持久内容 |
| 结束输入后的候选字符串/标签 | 10,000 / 40 | 0 / 0 | 相同初始候选量下释放展示模型 |
| 1,000次派生更新的队列 | 底层队列无法直接检查 | 1运行+1待处理，解阻后0/0 | 无旧宿主读取/误提交；不虚构B排队数 |
| 12次重复模型导入请求 | 实际12任务 | 实际1任务 | C退出观察者为空；释放后双方工作线程0，模型完整 |
| 600,000字符草稿本地状态Parcel | 1,201,216字节 | 948字节 | 正文仍完整；不是Binder或杀进程恢复测试 |

按键、提交、剪贴板事件和语音最终片段不被查询任务合并。未通过任意删除词条、截断候选或降低数据上限替代优化。

## 自然内存测量：完成两组对照

最终自然插桩统一tests46（只修复设置关闭回调等待顺序），双方使用相同数据、诊断、采样与恢复时刻。每类一组完整B/C，不宣称统计显著或可重复性；没有强制GC。下面PSS为最后周期结束后60秒，单位KiB。

| 场景 | 工作量 | B | C | C−B |
|---|---|---:|---:|---:|
| 双词库 | 5轮，每轮中文/英文各10,000条完整加载、修改、导出、清空 | 42,416 | 39,912 | −2,504 |
| 设置与调色器 | 30轮、20次真实方向变化 | 66,243 | 66,276 | +33 |

词库双方完整值、导出及末缓存清空检查通过。设置双方取消/保存/返回与回调解绑检查通过，FD保持58。设置最初暖机后PSS上升，后段约61–68MiB波动，没有逐轮单调增长；仅此样本不能证明没有泄漏。词库约−2.45MiB是单组该场景差值；设置没有观察到最终PSS下降，不能声称总体内存已明显减少。

B语音单边已完成12周期/14次begin，12次清理检查通过：末busy/recording/pendingBegin为false、AudioRecord字段为null，线程23、FD62稳定。末+60秒PSS103,060KiB。正常stop回调约53–83ms，后台native清理约20秒；加载中取消约2秒，均为软件模拟器测量，不是手机延迟承诺。C语音按用户要求在首次有效采样前停止，0有效样本、0完整清理；**没有语音B/C差值**。

外部真实输入只完成B的代表性小样本，C取消未执行；不存在50轮/200查询的完整PSS结论。15项快速输入功能检查不能替代长程内存实验。

Java已用堆、native分配、PSS/RSS、映射虚拟大小及对象归属含义不同；自然弱引用存活不是永久泄漏证据。每秒采样峰值不是绝对峰值；没有直接JNI活对象计数。历史版本内存数据和旧采样器高开销校准未混入本轮比较。

## 停止、恢复与隐私边界

用户要求停止后，没有新增设备测试。已恢复实验前53,007,360字节、76项目录/文件内容，逐路径、类型、大小与SHA核对，捕获的系统设置及麦克风grant/flags一致；无活跃目标进程/instrumentation，模拟器已关闭。

本版不保存输入日志、打字历史、个人频率或录音。只保存获准的内容与独立配置；备份排除应用数据。清除不能保证物理闪存不可恢复，也不会删除外部导出。语音模型独立安装，源码/APK不含测试模型或音频。

未覆盖：真实OS深色设置矩阵、杀进程后的大草稿恢复、真实SAF阻塞取消/流关闭、10,000候选全量滚动、近400万字符累积极限、独立60秒语音上限、额外强制GC归因、Samsung马达/折叠/功耗/长期资源表现。最终边框/两默认色/彩蛋/符号页由用户实机验证。

五份补充五笔词库上游未给出明确LICENSE，已披露且仅用于请求的私人测试，不作公开发布许可承诺。语音转换模型与上游权重许可区分见第三方说明，模型不随包分发。

## 2026-10-12 — Unified settings design and direct theme editing
- Shared settings palette, cards, headers, dialogs and inputs now use neutral paper backgrounds and #2B2B2B ink/accent, consistent with the launcher.
- Theme editor supports tapping preview regions, two-column color swatches and quick colors, retaining explicit save/cancel and independent light/dark palettes.
- Toolbar editor provides a real icon-order preview, explicit add/remove, long-press drag handles, position chooser and accessibility reorder actions. Owned dialogs are dismissed on navigation/destruction.
- Validation: assembleDebug, assembleDebugAndroidTest, 187 JVM tests passed; lintDebug: 0 errors, 28 warnings.
- BlueStacks instrumentation: KeyboardThemeEditorTest (9), toolbar persistence/add/remove/accessibility reorder (1), logical navigation across settings routes (1): OK (11 tests). Includes cancelled/scroll preview gestures and real tap opening.
- Installed debug APK on BlueStacks; ADB screenshots reviewed for settings home, appearance, theme palette, picker and toolbar. Drag gesture itself was not automated; reorder persistence exercised through accessibility actions.
- Evidence: app/build/reports/settings-unified/. Avoided vendor-unstable UiAutomation screenshot API; used adb screencap.

## 2026-10-12 — Privacy/logging and memory follow-up (0.1.10)
- Preserved user-authorized clipboard, phrases, custom dictionaries, recent emoji and settings. Removed the remaining two production font exception log calls.
- 187 JVM tests passed; Debug/Release lint completed with zero errors.
- 60 isolated panel cycles verified presentation cleanup and no typed sentinel in files/preferences; process logcat sentinel matches: zero.
- 30 picker cycles / 20 orientation transitions and separate Activity/Dialog/Picker collectibility assertions passed.
- Current candidate 1000-refresh stress, privacy cancellation, sensitive clipboard and 100 canceled voice-start checks passed.
- 12 pinned native recognizer load/silence/decode/release cycles passed without microphone access; PSS 103172–105516 KiB, FD count 64 throughout. Temporary model removed.
- Limitations include an older slow-host diagnostic timeout, one manually interrupted test sequence (rerun passed), unavailable nativeAllocatedBytes metrics on BlueStacks, and no live microphone stress. Full evidence/limitations: docs/privacy-memory-review-2026-10-12.txt.
- Release APK signature, no-debug/test-code checks and packaged dictionary byte equality verified.

## 2026-10-12 — Release 0.1.11
- versionCode 12; consolidates the prior UI, privacy, clipboard, haptics, layout and dictionary updates.
- Release APKs and private signing material are excluded from Git.
