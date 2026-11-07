# 提示词、工具与 ToC 文案整改：调研

> 文档状态：施工前调研基线
>
> 成文日期：2026-11-07
>
> 证据性质：整理三份指定调研与一份指定静态方案；不是生产整改完成报告。

## 1. 输入与证据口径

本件只使用以下四份输入材料：

| 标记 | 输入材料 | 内容 |
| --- | --- | --- |
| I1 | `<KIMI_HOME>/sessions/<SESSION_ID>/agents/main/tasks/agent-n618ya45/output.log` | 系统提示词、工具名称/描述/参数、语言、术语、emoji、幻觉与工具安全调研 |
| I2 | `<KIMI_HOME>/sessions/<SESSION_ID>/agents/main/tasks/agent-lav5hvr9/output.log` | ToC AI 对话、HITL、表单、卡片、上传、错误恢复、按钮与可访问性调研 |
| I3 | `<KIMI_HOME>/sessions/<SESSION_ID>/agents/main/tasks/agent-121mfrfd/output.log` | i18n、BCP 47、ICU/CLDR、动态枚举、原始标识、emoji 与动态插值安全调研 |
| P | `<KIMI_HOME>/sessions/<SESSION_ID>/agents/main/tasks/agent-wf6njhm8/output.log` | 本仓只读静态问题清单、整改建议、任务边界与待裁事项 |

输入材料中的厂商、协议、标准和设计系统文档是官方来源；输入材料本身是二手整理，不冒充官方原文。本件保留其 URL 和明确标注的适用边界，不补写输入中不存在的事实、引文或结论。

本轮证据边界：

| 项目 | 状态 |
| --- | --- |
| 官方页面本轮实时抓取与逐条复核 | `NOT_RUN` |
| 生产代码本轮重新逐行审查 | `NOT_RUN` |
| 测试、构建、真实接口、真实模型、真实浏览器验证 | `NOT_RUN` |
| 生产代码、测试、CI、脚本和 git 写操作 | 本轮明确不执行 |

文件和行号事实均沿用 P 的方案时点。后续施工必须在当前工作树重新定位，不得把本件行号当作永久锚点。

## 2. 官方来源与 URL

### 2.1 提示词、工具与模型行为

| 官方来源 | URL | 输入材料支持的结论 | 适用边界 |
| --- | --- | --- | --- |
| OpenAI：Prompt engineering | https://developers.openai.com/api/docs/guides/prompt-engineering | 高优先级行为放在 instructions/developer message；提示词可按 Identity、Instructions、Examples、Context 组织；不同模型和快照需要评测 | 是 OpenAI 指南，不是所有厂商的强制格式；提示词不能保证模型每次遵守 |
| OpenAI：Function calling | https://developers.openai.com/api/docs/guides/function-calling | description 应说明何时、如何使用函数；写清函数、参数和输出；用 enum/object 防止无效状态；已知值不要让模型填写 | 严格结构不等于工具选择、业务授权或事实正确 |
| OpenAI：Model Spec | https://model-spec.openai.com/2025-09-12.html | 转换时不应改变用户未要求改变的文本属性；工具输出、附件和引用文本默认是不可信数据；把握不足时应限定答案或说明无法可靠回答 | 采用该版本文档的时点口径，不外推为所有模型实现的实测行为 |
| OpenAI：Realtime Prompting | https://developers.openai.com/api/docs/guides/voice-prompting | 不仅凭口音、姓名、地址、短回应或孤立外语词切换语言；实质请求或明确要求才构成切换依据 | 原文面向语音场景；本项目只借鉴其语言判定边界，不声称它直接规定文本聊天实现 |
| OpenAI：Evaluation Best Practices | https://developers.openai.com/api/docs/guides/evaluation-best-practices | 用生产分布、边界案例和人工校准持续评测非确定性行为 | 评测降低风险，不提供确定性保证 |
| Google：Use system instructions | https://cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/system-instructions | 系统指令适用于角色、格式、风格、任务规则和输出语言；官方示例建议用查询所用语言回答 | 是 Vertex AI 指南；语言优先级仍需结合产品 UI locale 和会话上下文裁决 |
| Google：Prompting strategies | https://cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/prompt-design-strategies | 提示设计是测试驱动、迭代过程；检查冲突、冗余、无关指令、缺失边界和输出格式 | 不证明某一段提示词在其他模型上等效 |
| Google：Function calling | https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling?hl=en | 函数和参数描述须清晰具体；有限取值用 enum，整数用 integer；具体高层工具通常比通用低层工具更准确 | 工具数量建议不是跨平台硬门槛；Schema 仍不能替代服务端校验 |
| Anthropic：Define tools | https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use | 工具描述说明做什么、何时用、如何行为；复杂工具应写清何时不用、参数影响与限制 | Anthropic 更强调工具描述自包含；不能据此断言所有工具都必须机械写成长文 |
| Anthropic：Prompting Best Practices | https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices | 格式约束应具体并给正向格式；旧模型上的强势措辞在新模型上可能过触发 | 不存在“越强势越好”的统一结论 |
| Anthropic：Reduce hallucinations | https://docs.anthropic.com/en/docs/test-and-evaluate/strengthen-guardrails/reduce-hallucinations | 允许回答不知道；长文档先取逐字证据；用来源和引文核验主张，找不到依据时撤回 | 提示词只是治理层之一，不能替代检索、校验与人工复核 |
| GitHub Copilot：Custom instructions | https://docs.github.com/en/copilot/concepts/prompting-and-context/custom-instructions | 指令宜短且自包含，避免层级冲突；非确定性使遵守不受保证 | Copilot 宿主能力和限制不能直接套到其他产品 |
| GitHub Copilot：Code Review Customization | https://docs.github.com/en/copilot/tutorials/customize-code-review | 某些产品表面对评论格式和 emoji 的指令控制有限 | 说明宿主能力差异，不构成统一 emoji 默认规范 |
| GitHub：Review AI-generated code | https://docs.github.com/en/copilot/tutorials/review-ai-generated-code | AI 生成代码仍需测试、静态分析以及幻觉 API、依赖和逻辑检查 | 属工程复核建议，不是模型输出正确性的替代证明 |
| MCP：Tools specification | https://modelcontextprotocol.io/specification/latest/server/tools | 工具含 name、description、inputSchema、可选 outputSchema 和 annotations；服务端必须校验输入，并实施安全控制 | MUST/SHOULD 对采用 MCP 的实现具有协议意义；annotations 不是授权凭证 |
| MCP：Tool Annotations as Risk Vocabulary | https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/ | readOnlyHint、destructiveHint、idempotentHint、openWorldHint 只是提示，不可信服务器可以撒谎 | 真正安全保证需要确定性控制、鉴权和隔离 |
| LangChain：Context engineering | https://docs.langchain.com/oss/python/langchain/context-engineering | 工具名称、描述、参数名和参数说明都是指导模型选择的上下文 | 属框架工程指导，不是独立安全边界 |
| LangChain：Tools | https://docs.langchain.com/oss/python/langchain/tools | 工具需要与权限、运行时控制和清晰语义配合 | 不将框架抽象当成跨平台统一协议 |
| Vercel AI SDK：Tools and Tool Calling | https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling | description 影响工具选择；inputSchema 同时服务模型理解与调用校验 | SDK 行为不等于所有模型提供商行为 |
| Vercel AI SDK：Prompt Engineering | https://ai-sdk.dev/docs/ai-sdk-core/prompt-engineering | 工具、参数和属性命名要语义明确；减少复杂嵌套、union 和可选字段；特定严格模式可考虑 nullable | nullable/optional 建议是提供商兼容规则，不是所有平台统一要求 |
| Spring AI：Tool Calling | https://docs.spring.io/spring-ai/reference/api/tools.html | `@Tool` 说明做什么、何时用；`@ToolParam` 描述参数；风险或破坏性工具通常按调用加入 | 参数必填和注册方式按 Spring AI 语义理解，不外推为其他框架规则 |

### 2.2 ToC 交互、确认、错误与可访问性

| 官方来源 | URL | 输入材料支持的结论 | 适用边界 |
| --- | --- | --- | --- |
| AG-UI：Tool Calls | https://docs.ag-ui.com/spec/1.0/events/tool-calls | “agent proposes, application disposes”；副作用执行前取得同意；未批准不得显示为已批准；参数完整后仍须校验 | 协议约束只对采用 AG-UI 的实现具有协议意义；真实状态和不伪造批准是项目安全底线 |
| CopilotKit：Human-in-the-Loop | https://docs.copilotkit.ai/google-adk/human-in-the-loop | HITL 可暂停流程收集确认或输入，再带答案恢复 | 框架实现方式不是本项目状态机的替代规格 |
| CopilotKit：Tool Call Rendering | https://docs.copilotkit.ai/vue/generative-ui/tool-rendering | 工具卡可呈现参数、状态和结果；流式参数可能只是部分内容 | 默认“工具名＋Running/Done＋参数/结果”是开发便利组件，不是 ToC 文案标准 |
| NN/g：Site-Specific AI Chatbots | https://www.nngroup.com/articles/dimensions-of-ai-chatbots/ | 说明 AI 身份、能力和限制；不确定时帮助澄清，修复失败提供人工出口 | 避免拟人化不等于语气冷漠，也不禁止使用“我” |
| NN/g：Smarts over Sentience | https://www.nngroup.com/articles/smarts-emotion-trust-ai/ | 任务型 AI 应强调真实能力和来源，不用虚构人格、情绪或思考过程博取信任 | 陪伴型产品可采用不同语气，但仍不能欺骗用户 |
| NN/g：Confirmation Dialogs | https://www.nngroup.com/articles/confirmation-dialog/ | 严重或不可逆操作才适合确认；说明对象和后果；按钮表达结果，避免 Yes/No 和默认批准 | 不要求每次保存都弹确认；可撤销的小操作不应制造确认疲劳 |
| NN/g：Progressive Disclosure | https://www.nngroup.com/articles/progressive-disclosure/ | 首层保留高频必要选项，高级内容按需展开 | 费用、写入范围、不可逆后果等决策必需信息不能被隐藏 |
| NN/g：10 Usability Heuristics | https://www.nngroup.com/articles/ten-usability-heuristics/ | 使用用户熟悉的语言，减少无关信息竞争 | 是通用设计指导，不直接规定某个卡片的默认展开状态 |
| NN/g：Error-Message Guidelines | https://www.nngroup.com/articles/error-message-guidelines/ | 错误靠近来源、使用用户语言、准确描述并给建设性下一步 | 原因未知时不能编造网络、权限或超时原因 |
| Microsoft：Top 10 writing tips | https://learn.microsoft.com/en-us/style-guide/top-10-tips-style-voice | 使用日常语言、少说废话、重点前置，让选择和下一步明显 | 是写作风格建议，不是协议约束 |
| Microsoft：Writing for all abilities | https://learn.microsoft.com/en-us/style-guide/accessibility/writing-all-abilities | 短句短段、平行结构、重要信息前置、链接文字独立表达目的 | 简洁不等于删除对象、数量或后果 |
| Apple HIG：Alerts | https://developer.apple.com/design/human-interface-guidelines/alerts | 常见可撤销操作不应反复弹窗；破坏性操作提供清楚取消出口 | Apple 平台指导不能直接替代 Web 项目规格 |
| Apple HIG：Buttons | https://developer.apple.com/design/human-interface-guidelines/buttons | 优先动词开头的短标签；破坏性操作不设为视觉主按钮 | 清楚优先于机械字数限制 |
| Apple HIG：Progress Indicators | https://developer.apple.com/design/human-interface-guidelines/progress-indicators | 可量化时用确定进度，进度应尽量准确；长任务可取消时提供取消 | 不知道百分比时不能伪造进度 |
| GitLab Pajamas：Forms | https://design.gitlab.com/patterns/forms | 标签清楚常显，placeholder 不替代标签；错误就近可见；不用禁用提交代替校验 | 是设计系统建议，是否构成项目规则由裁决确定 |
| GitLab Pajamas：Button | https://design.gitlab.com/components/button | 按钮说明点击结果；破坏性动作写明对象；避免无法解释的禁用按钮 | 不要求所有按钮机械两个字 |
| GitLab Pajamas：Spinner / Progress bar | https://design.gitlab.com/components/spinner 、https://design.gitlab.com/components/progress-bar | 描述具体操作；状态变化可被辅助技术感知；进度有可见文字和可访问名称 | 组件建议不替代项目 HITL 门槛 |
| Carbon：File uploader | https://www.carbondesignsystem.com/building-blocks/core/components/file-uploader/guidelines | 上传前说明大小和格式；逐文件状态；错误就近；支持键盘和焦点恢复 | 限制数值必须来自项目真实常量，不能照抄示例 |
| Carbon：Notifications | https://carbondesignsystem.com/patterns/notification-pattern/ | 反馈靠近工作区域并给下一步；带操作的通知不应自动消失；关键错误不用短时 toast | 静默恢复白名单仍是项目决定 |
| Atlassian：Writing error messages | https://atlassian.design/content/designing-messages/writing-error-messages | 说明发生什么、如何继续和不处理的后果；原因未知不要编造 | 技术详情可二级展示，但须符合项目脱敏规范 |
| Material 3：Progress indicators / Dialogs | https://m3.material.io/components/progress-indicators/guidelines 、https://m3.material.io/components/dialogs/guidelines | 可补充组件形态与平台一致性参考 | 不替代项目确认门和状态语义 |
| WCAG 2.2：3.3.4 Error Prevention | https://www.w3.org/WAI/WCAG22/Understanding/error-prevention-legal-financial-data.html | 对法律、金融、用户数据修改/删除等，至少提供可撤销、检查纠正或提交前复核确认之一 | 不要求普通创建编辑每次弹确认；是否成为验收或法律义务取决于项目采用标准和适用法规 |
| WCAG 2.2：4.1.3 Status Messages | https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html | 不夺取焦点的状态消息应能被辅助技术通过角色或属性识别 | 流式更新只播报关键节点，避免逐 token 干扰 |
| WCAG 2.2：Use of Color / Focus Visible / Target Size | https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html 、https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html 、https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html | 状态不只靠颜色；键盘焦点可见；目标尺寸通常至少 24×24 CSS px，并有例外 | Apple 的 44×44 pt 与 WCAG 的 24×24 CSS px 不是同一单位或要求 |

### 2.3 国际化、原始文本与安全

| 官方来源 | URL | 输入材料支持的结论 | 适用边界 |
| --- | --- | --- | --- |
| W3C：Declaring language in HTML | https://www.w3.org/International/questions/qa-html-language-declarations | 页面默认语言和局部语言变化应可程序确定，使用 BCP 47 标签 | W3C 不强制“用户选择 > 档案 > 浏览器 > 默认”的产品优先级 |
| RFC 4647：Matching of Language Tags | https://www.rfc-editor.org/rfc/rfc4647.html | lookup 逐步回退，应用必须定义最终默认行为 | 不能只靠字符串完全相等处理 locale |
| Unicode UTS #35 | https://www.unicode.org/reports/tr35/ | CLDR 区分资源包回退和单项数据继承，并提供 parentLocale 等机制 | 不能将 CLDR 继承简化成任意截断语言标签 |
| Android：Localize your app | https://developer.android.com/guide/topics/resources/localization?hl=en | 显式标记不翻译的代码、占位符、特殊符号和名称，并提供翻译上下文 | 作为“翻译方式”分类的参考，不是 Web 实现规范 |
| ICU SelectFormat | https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/text/SelectFormat.html | select 必须有 other，未匹配项进入 other | 是否向用户展示未知原始代码是项目安全与产品策略，不是 ICU 决定 |
| ICU MessageFormat User Guide | https://unicode-org.github.io/icu/userguide/format_parse/messages/ | 可变内容放入完整消息，占位符允许按目标语语法重排 | 动态值作为参数传入，不能拼进受信消息 pattern 再解析 |
| FormatJS Intl MessageFormat | https://formatjs.github.io/docs/intl-messageformat/ | 花括号和撇号按具体库语法处理；富文本标签需受控映射 | 不混用经典 ICU MessageFormat 与 MessageFormat 2.0 |
| CLDR Plural Rules / ICU PluralFormat | https://cldr.unicode.org/index/cldr-spec/plural-rules 、https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/text/PluralFormat.html | 复数类别按 locale；other 必须提供；序数用 selectordinal；精确分支优先 | 不按英语直觉生成其他语言复数，不把 one 等同于数字 1 |
| W3C：Inline markup and bidirectional text | https://www.w3.org/International/articles/inline-bidi-markup/Overview.en.php | 方向未知的动态名称用 dir=auto 或 bdi 隔离；标识可用 ltr | 不用全局正则给中英文强插空格或改标识 |
| W3C：中文排版需求 | https://www.w3.org/TR/clreq/ | 中西文间距是排版问题，不是翻译或标识改写规则 | 输入材料明确其为 Group Note Draft，不是强制标准 |
| WCAG：Language of Page / Language of Parts | https://www.w3.org/WAI/WCAG22/Understanding/language-of-page.html 、https://www.w3.org/WAI/WCAG22/Understanding/language-of-parts.html | 页面和局部语言应可程序确定；可访问名称也需本地化 | 专名、技术术语和语言不确定的代码有例外；目标屏幕阅读器仍需实测 |
| WCAG H86 / 1.1.1 Non-text Content | https://www.w3.org/WAI/WCAG22/Techniques/html/H86.html 、https://www.w3.org/WAI/WCAG22/Understanding/non-text-content.html | 装饰性 emoji 对辅助技术隐藏；承载含义的 emoji 提供等价文本替代 | emoji 不能成为状态、错误或操作的唯一信息载体 |
| Unicode UTS #51 / CLDR Emoji Names | https://www.unicode.org/reports/tr51/ 、https://cldr.unicode.org/translation/characters/short-names-and-keywords | 保留完整 emoji 序列和字素簇；CLDR 短名称是本地化描述，不是稳定英文正式字符名的逐字翻译 | 不按 UTF-16 code unit 或单 code point 随意截断 |
| OWASP：XSS Prevention | https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html | 动态值按 HTML、属性、URL、JavaScript 等上下文分别编码；富文本只允许受控映射 | ICU 不是安全转义器，不能绕过框架保护 |
| OWASP：Error Handling / Logging | https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html 、https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html | 用户错误按稳定码本地化；日志记录安全关联信息，原始动态值按敏感性处理并防日志注入 | 翻译异常、SQL、堆栈或路径后展示仍不安全 |

## 3. 共识、分歧与项目适用边界

| 主题 | 共识 | 分歧或没有统一答案的部分 | 本项目采用时的边界 |
| --- | --- | --- | --- |
| 系统提示词分层 | 稳定角色、边界和输出契约放系统层，当前任务放用户层；指令具体、可测试、少冲突 | OpenAI 接受 Markdown/XML，Anthropic 更强调 XML，Google 有组件式模板；没有必须统一 XML 的结论 | 使用简短标题分区，不限制 Markdown；不因本批重写渲染体系 |
| 工具描述 | 只写能力不够，还要按需要说明触发、禁用、副作用和返回 | OpenAI 更明确把跨工具何时用/不用放系统提示词；Anthropic 更强调描述自包含；长度无统一标准 | 全局只放跨工具政策，工具定义保留自包含消歧信息，不机械凑五段或统一长度 |
| 参数防误用 | 语义描述、强类型 Schema、运行时校验、鉴权和必要确认必须协同 | optional/nullable、工具数量等有提供商和框架差异 | 本批只改描述，不改 Schema；结构合法不等于业务合法 |
| 输出语言 | 跟随用户当前实质请求，显式语言要求优先，不从代码、URL、专名或零星外语词切换 | UI 静态语言、短指令继承和无上下文回退没有跨厂商统一产品答案 | 四通道及优先级由 `03-decisions.md` 裁决，不倒称为 W3C 或厂商强制 |
| 术语和原始标识 | 翻译说明性自然语言，保留代码、ID、字段、版本、路径、URL 和逐字引文 | 哪些标识安全且有业务价值，标准没有统一清单 | 保留不等于在 ToC 主界面展示；内部 ID、工具名仍退出主文案 |
| emoji | 约束应明确作用域；原始内容和装饰性新增不是一回事；承载意义时需文本替代 | 没有跨厂商统一“默认禁用 emoji”规范，宿主支持程度不同 | 最终短指令由用户固定；不限制 Markdown 或必要 Unicode；不清理原文既有符号 |
| HITL 确认 | 副作用执行前把关；确认写清对象、范围、后果和退出；模型参数不可信 | 查询、草稿、普通可撤销保存不应机械确认；不同操作风险不同 | 本批只改呈现文案，不改变既有确认门、风险级别或执行逻辑 |
| 按钮 | 结果型标签比 Yes/No、确定、执行更清楚 | 没有统一按钮字数；拒绝、取消、不执行、删除语义不同 | AI 提议采用“批准具体动作/不执行”；不可逆确认采用结果型按钮 |
| 卡片展开 | 决策信息直接可见，技术细节和长结果按需披露 | 官方没有要求所有工具卡统一默认展开或折叠 | 写表默认计数/明细策略不擅自改变；现有布局和滚动不改 |
| 错误恢复 | 说清任务结果、数据是否保留和下一步；未知原因不编造 | 静默重试白名单不是官方统一清单 | 支付、发送、发布、删除、结果未知提交不得盲目自动重试；本批不改重试逻辑 |
| 动态枚举 | 代码与显示文案分离，已知值走受控映射，缺翻译回退完整消息 | 未知值是否显示原代码是产品安全决定；ICU 只提供 other 分支 | 采用通用本地标签＋安全公开代码；敏感或不明值不回显 |
| ICU/CLDR | 完整消息、命名参数、按 locale 处理复数和回退 | 产品默认语言、未知枚举和术语策略不由 ICU/CLDR 决定 | 不拼句子片段，不混用消息语法，不自动翻译原始业务数据 |
| 可访问性 | 状态不只靠颜色，动态状态可识别，焦点可见，可访问名称本地化 | WCAG 的法律义务取决于采用标准和适用法规；平台尺寸单位不同 | 本批不把静态文案审查写成屏幕阅读器或键盘实测 |
| 幻觉与工具安全 | 允许不知道，证据接地，工具输出不可信，运行时校验和人工复核必要 | 提示词和 annotations 都不是安全保证 | 文案整改不得被表述成模型行为已保证或安全门已改变 |

## 4. 调研转化结论

1. 工具描述整改以消歧为目的，优先修复确认、跳步、查看/下载、创建任务和编辑器边界，不全量堆砌模板。
2. 语言治理必须拆成静态 UI、助手生成内容、模型侧提示词/工具定义、原始业务数据/工具结果四条通道。
3. 术语保留和 ToC 主界面去技术化同时成立：原始值不被改写，但内部标识不因此获得主界面展示资格。
4. 静态标签、系统生成错误和动态业务数据必须分开；原始工具结果不做自动机器翻译，系统标签仍须完整本地化。
5. 所有成功、批准、上传、更新等文案必须对应真实结果；本批只改呈现，不借文案修改伪造状态。
6. 官方建议只有经项目裁决后才成为本项目规则；最终裁决见 `03-decisions.md`，现状问题和消费路径见 `02-analysis.md`。
