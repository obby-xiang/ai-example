# 提示词、工具与 ToC 文案整改：分析

> 文档状态：施工前静态分析基线
>
> 成文日期：2026-11-07
>
> 事实来源：指定静态方案 P；行号为方案时点锚点，后续施工须在当前工作树重新定位。

## 1. 分析口径与优先级

本件把方案中的问题按“位置 → 实际消费路径 → 问题 → 依据 → 优先级”展开。问题矩阵是静态阅读结论，不表示对应失败已在真实模型或真实浏览器中复现。

优先级含义：

- **P0**：可能造成高风险动作歧义、原始异常/技术内容上屏、不可逆决策误导，或违反现行安全与 ToC 硬约束，施工序列优先收口。
- **P1**：明确语言、工具选择、状态语义、i18n 或用户理解问题，纳入同一批整改。
- **P2**：一致性、渐进披露和表单易用性改进；在“一次整改到位”授权下仍纳入，但不借机改变业务流程。

依据分层：

- **现行**：根 `AGENTS.md` 与 `docs/delivery/development-standards.md`，是本仓当前硬约束。
- **调研转化**：官方标准、官方设计建议或框架指南，经 `03-decisions.md` 采纳后成为本项目决定。
- **内部一致性**：不同有效定义、能力包或展示镜像之间的冲突/漂移，不等同于外部标准违规。

## 2. 后端提示词与工具定义问题矩阵

| ID | 位置 | 模型/执行消费路径 | 问题与性质 | 优先级 |
| --- | --- | --- | --- | --- |
| B01 | `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:193-205` | 系统提示词 → 模型判断何时需要确认 → 既有 HITL 门 | “禁止二次确认”与创建、导出、导入、发布必须确认并存，可能被理解为已知信息可跳过风险确认；属内部冲突。 | P0 |
| B02 | `ResilientChatService.java:197`、`backend/src/main/java/com/example/configmgr/ai/run/ReplyLanguage.java:62-70` | 系统提示词语言段 → 助手生成正文/进度/错误 | 已有随用户语言能力，但语言优先级分散；标识保留未覆盖产品名、路径、URL、命令、版本号、逐字引文；emoji 与 Markdown 边界未写全。属覆盖缺口，不断言必然误翻译。 | P1 |
| B03 | `ReplyLanguage.java:17-24,67-70` | `Accept-Language`/本轮语言判定 → 后续助手回复 | “本轮请求语言”实际先受 `Accept-Language` 影响，不等同于当前消息主导语言；“后续沿用”未明确短指令继承与新的实质请求重判。只修表述和规则，不改语言检测架构。 | P1 |
| B04 | `backend/src/main/java/com/example/configmgr/ai/run/PromptRulePacks.java:56-58` 对照 `backend/src/main/resources/ai/tools.yaml:175-184` | 能力包规则 + 有效工具描述 → `confirm_step` 的步骤选择 | 能力包“禁止跳步”与工具允许用户点名目标步骤/明确要求跳过筛选并存；合法目标步骤仍有必经边界。属实际提示词冲突。 | P0 |
| B05 | `PromptRulePacks.java:85-90` 对照 `tools.yaml:131-142` | 能力包“下载/查看”意图 → `download_export_files` 或 `open_export_file_editor` | 能力包把查看和下载都导向下载，工具定义则把查看/编辑交给编辑器；可能产生错误工具选择。 | P1 |
| B06 | `tools.yaml:95-111` 对照 `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:412` | 有效 YAML 覆盖注解缺省 → `create_task` 模型描述 | YAML 只禁止用 `list_config_defs` 代替创建，注解还禁止把 `get_config_def` 当创建前置；有效定义与缺省定义漂移。 | P1 |
| B07 | `tools.yaml:131-142`、`AiTools.java:606-633`、`frontend/src/types/tools.ts:159-182` | YAML/注解模型定义 + 前端镜像 → 打开编辑器、下载及确认呈现 | 三处详略不同，前端镜像缺少“只有明确意图才调用、完成状态不是授权”等边界。不要求字数一致，要求语义边界一致。 | P1 |
| B08 | `AiTools.java:174,214,291,309,341` | 基础只读工具描述/参数 → 工具选择与参数生成 | 多个工具主要只有能力说明；`check_job_status` 的 `jobId` 等缺少精确来源，部分工具未说明何时不用、空结果或返回含义。只补有实际消歧价值的内容。 | P2 |

### 后端消费链必须保持的解释

1. `tools.yaml` 是方案确认的实际有效覆盖来源；`AiTools.java` 注解是缺省定义。二者不能用“以代码为准”或“以 YAML 为准”简单互相覆盖掉语义矛盾。
2. `frontend/src/types/tools.ts` 的镜像也要同步核对，但不因此把前端展示文案直接当作模型系统提示词。
3. 修改有效工具描述时，`tools.yaml` 的 `version` 按既有要求只升一次并追加变更记录；描述变化会影响 prompt 缓存和既有评测口径。
4. 本批不改工具名、参数 Schema、风险级别、披露范围、确认门、执行逻辑或 locale 解析架构。

## 3. AiPanel、卡片与动态文案问题矩阵

| ID | 位置/键名 | 用户可见消费路径 | 问题与性质 | 优先级 |
| --- | --- | --- | --- | --- |
| F01 | `frontend/src/utils/tool-summaries.ts:246-253`、`frontend/src/components/AiPanel/AiPanel.vue:4346-4353`、`tool.fallback` | 未识别工具 → 摘要/卡头兜底 → ToC 主界面 | 直接显示 `{name} · {status}`，标题回退原始函数名；违反现行去技术话术和原始标识要求。应使用通用业务兜底，原始名只进受控技术详情。 | P1 |
| F02 | `frontend/src/utils/display-text.ts:208-212` | `read_tool_result` 页脚 → 中英界面 | 硬编码“已读完/未读完、已显示……字符”，英文界面仍出现中文；违反现行 i18n 要求。 | P1 |
| F03 | `AiTools.java:231-245,327-351`、`AiPanel.vue:574-583` | 普通工具结果中的系统生成中文标签 → 普通结果卡原文展示 | 英文界面可能中英混杂，真实展示路径由方案确认。系统生成静态标签应本地化；原始业务数据和工具结果不得被简单自动机器翻译。 | P1 |
| F04 | `zh-CN.json` / `en-US.json` 的 `ai.card.status.succeeded/rejected/cancelled`、`tool.status.*` | 工具/卡片状态映射 → 卡头或状态区 | “成功/已完成”“拒绝/已拒绝”“取消/已取消”等近义状态并存；孤立“成功”“拒绝”缺少动作结果或语义不清。 | P1 |
| F05 | `frontend/src/utils/card-content.ts:10-127` | 未知任务类型、步骤、状态、层级 → 动态资源键/原值回显 | 未知值直接上屏，部分动态值直接拼资源键，缺少统一闭集映射和安全未知值兜底；属调研转化项。 | P1 |
| F06 | `AiPanel.vue:684-695`、`ai.panel.approve/reject` | AI 工具确认卡 → 用户批准或否决提议 | 统一“批准/拒绝”未说明批准什么，“拒绝”易与业务审批决定混淆；高风险动作的后果表达不清。 | P0 |
| F07 | `frontend/src/utils/ui-error.ts:18-23` | 普通 `Error` → `Error.message` → 任务中心、数据浏览、向导等用户提示 | 非 `ApiError` 的原始消息可直接成为用户文案，绕过安全白名单和完整 i18n；违反现行异常与 ToC 约束。 | P0 |
| F08 | `frontend/src/components/AiPanel/FormRenderer.vue:941-954,972-984` | 未识别表单错误码/卡级错误 → `issue.message` 或拼接消息 | 未知码可能绕过本地化与安全白名单；应使用完整通用文案，只把批准的安全字段作为参数保留。 | P0 |
| F09 | `frontend/src/components/AiPanel/UploadHandoffCard.vue:150-156`、`ai.uploadHandoff.acceptHint` | 上传交接卡 → 用户选择文件前 | 前置说明只有格式，没有大小和数量限制；限制值应引用现有上传常量，不另造数值。 | P2 |

### 卡片整改的共同约束

- 标题使用业务动作和对象，一个组件只保留一个真实主状态。
- “不执行”表示否决 AI 提议；“取消”表示退出流程；“拒绝申请”才是业务审批决定；删除和移除也分别命名。
- 决策必需信息默认可见，原始 JSON、内部 ID、异常堆栈、协议事件和工具函数名退出主文案。
- 原始业务文本可保留，但不能借“保留原文”为系统生成的中文硬编码开脱。
- 不改卡片布局、滚动、Markdown/Mermaid 渲染、预览档位、上传协议、表单校验或附件协议。

## 4. 其他主要 ToC 页面问题矩阵

| ID | 位置/键名 | 用户可见消费路径 | 问题与性质 | 优先级 |
| --- | --- | --- | --- | --- |
| T01 | `frontend/src/views/TaskCenterView.vue:80-82`、`frontend/src/utils/format.ts:93-110` | 任务中心当前步骤 → 页面文案 | 固定中文 `stepLabel` 使英文页面出现中文；已有双语 `taskStepText` 可复用。只替换 UI 调用点，不全局翻译模型词表。 | P1 |
| T02 | `TaskCenterView.vue:294-306` | 未知任务状态 → `String(status)` → 页面 | 未知值没有通用本地化说明，属未知枚举调研转化项。 | P2 |
| T03 | `frontend/src/views/ExportWizardView.vue:2203,2237`、`frontend/src/views/ImportWizardView.vue:1196,1231,1302,1315-1316` | 向导直接 `ElMessage`/用户提示 → 中英页面 | 多条直接用户提示硬编码中文；只处理用户提示，严禁批量翻译同文件中的模型回灌常量。 | P1 |
| T04 | `frontend/src/utils/publish-mode.ts:35,48,58-59` | 发布模式读取失败 → 用户提示 | 原因硬编码中文，并包含 `settingsJson/importMode` 等技术字段；违反现行 ToC 与 i18n 要求。 | P1 |
| T05 | `views.definitions.subtitle1/subtitle2/backendRules`、`views.data.subtitle/conditionNone/conditionApplied`、`views.tasks.refreshHint` | 配置定义、数据浏览、任务中心说明 → 页面主提示 | 包含 JSON 字段、`isKey`、后端约束、服务端求值、事件轮询等开发/实现话术；必要字段只能放二级技术说明。 | P1 |
| T06 | `frontend/src/views/DefinitionsView.vue:6-9` | `subtitle1` + 原始 `key` + `subtitle2` → 页面句子 | 片段拼句使译者无法按完整语序调整；违反现行完整 i18n 要求。 | P1 |
| T07 | `frontend/src/views/DataBrowserView.vue:77,96` | `row.scopeType` → 范围类型显示 | `GLOBAL/REGION/PROJECT` 已知值直接显示，未使用现有本地化映射。 | P1 |
| T08 | `frontend/src/views/AiSettingsView.vue:582-590`、`ai.settings.copy.probeOk/probeFailed` | 探测结果 → 字符串拼接 → 设置页提示 | 英文沿用中文括号、冒号；“连通，模型数”是句子片段。应改为带参数的完整中英文消息。 | P1 |
| T09 | `ai.settings.copy.envFallbackBody/defaultsHint`、`AiSettingsView.vue:930-943,1019-1024` | 设置说明、新建端点、手动模型区域 → 页面说明和表单 | “provider 行/env”等属开发口吻；部分区域主要依赖 placeholder 充当标签。前者违反现行约束，后者经一次整改授权纳入表单配套。 | P2 |
| T10 | `frontend/src/views/AiFeedbackView.vue:77-86`、`views.aiFeedback.sessionMessage` | 无正文摘要 → session/message 原始 ID 截断显示 | 主摘要暴露原始标识；复盘关联信息应放二级技术详情。 | P1 |
| T11 | `frontend/src/utils/confirm-irreversible.ts:11-13,54-62` | 删除、取消作业、放弃编辑等不可逆确认 → 主按钮 | 通用“确定/取消”未表达动作结果；属官方建议转化，并构成明确呈现规格变化。 | P0 |

## 5. 消费路径总图

```text
系统提示词/能力包 ─┐
                  ├─→ 模型选择工具、生成参数和助手文本
有效 tools.yaml ──┤          │
注解缺省定义 ─────┘          ├─→ 既有校验/确认门/执行逻辑（本批不改）
                            └─→ 工具结果与业务数据

前端工具镜像 ─────→ 前端工具语义与展示边界

原始工具结果 ─────→ 普通结果卡（原文保留，不自动机器翻译）
系统生成标签 ─────→ 必须走完整 i18n（不得混入硬编码中文）
动态枚举/字段 ────→ 闭集映射；未知值走安全通用兜底
卡片/页面/确认框 ─→ ToC 主文案（业务动作、真实状态、结果型按钮）
技术详情 ────────→ 受控二级展示（不作为主文案逃生口）
```

## 6. 非目标

### 6.1 本轮文档交付的非目标

- 不修改任何生产代码、测试、压测代码、脚本或 CI。
- 不执行 git 写操作，不创建提交或暂存审查记录。
- 不起停服务，不调用真实模型、真实接口或真实浏览器。
- 不把静态方案中的“命中即整改”检查项扩张成未经验证的运行故障断言。

### 6.2 后续一次整改的非目标

- 不改工具名、参数 Schema、API/SSE/Redis 契约、状态机、风险级别、披露范围、确认门或执行逻辑。
- 不改语言检测架构、线程传播、KB 检索、表单校验、上传流程、附件协议或业务逻辑。
- 不改变 Markdown、Mermaid、代码块、净化、滚动和布局行为。
- 不把写表预览默认计数改成默认明细；该交互另立决策。
- 不对原始工具结果或原始业务数据做自动机器翻译；完整英文结构化业务视图另列任务。
- 不全仓清理 emoji、既有符号、功能性图标或必要技术术语。
- 不为了形式统一机械拉齐所有工具描述字数，也不引入新依赖。

## 7. 当前验证状态

| 验证项 | 状态 |
| --- | --- |
| 方案消费路径本轮代码复核 | `NOT_RUN` |
| 提示词契约与工具定义对账测试 | `NOT_RUN` |
| locale 键奇偶、插值与前端测试 | `NOT_RUN` |
| 后端/前端构建 | `NOT_RUN` |
| 真实模型评测 | `NOT_RUN` |
| 真实浏览器、真实接口与端到端验证 | `NOT_RUN` |
