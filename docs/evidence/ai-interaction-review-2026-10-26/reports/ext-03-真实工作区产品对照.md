> 来源=agent-2e59gpp4/output.log（2026-10-26 归档）· 外部研究（Excel/Notion/Airtable/Power Apps/Cursor/VS Code/OpenHands 官方交互）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

# 业务工作区 + AI 助手：一手交互对照

## 证据口径

- 覆盖 **7 个产品**：Microsoft 365 Copilot（Excel/Chat）、Notion AI/Agent、Airtable Omni/Field agents、Power Apps Copilot、Cursor、VS Code Copilot、OpenHands；另附 Retool AI 作为“组件型”边界。
- 只采用官方帮助、官方文档、官方仓库。营销页和第三方评测不作为功能证据。
- 只写页面明确呈现的“用户看得到、点得到”的交互；页面没写的标为“未说明”，不据此反推内部实现。
- 产品行为受许可、平台、版本影响；以下以抓取到的 2026-10 页面为准。
- 本轮未读项目代码；对照点按“配置管理/导入导出工作区”的产品形态抽象，具体落点需与代码审查结果合并。

**先给结论**：最值得对照的不是聊天框，而是四个控制面——**范围选择、变更预览/批准、完成后的产物/下一步、长任务与错误恢复**。按与本项目的相似度排序：Airtable Omni ＞ Power Apps Copilot ＞ VS Code/Cursor ＞ Notion ＞ Excel ＞ OpenHands ＞ Retool。

---

## 1. Microsoft 365 Copilot（Excel + Copilot Chat）

**官方 URL**
[Get started with Copilot in Excel](https://support.microsoft.com/en-us/excel/copilot/get-started-with-copilot-in-excel) · [Copilot in Excel tips](https://support.microsoft.com/en-us/excel/copilot/copilot-in-excel-tips) · [Copilot in Excel data sources](https://support.microsoft.com/en-us/excel/copilot/copilot-in-excel-data-sources) · [Copilot in Excel FAQ](https://support.microsoft.com/en-us/excel/copilot/frequently-asked-questions-about-copilot-in-excel) · [What information does Copilot use](https://support.microsoft.com/en-us/microsoft-365-copilot/what-information-does-copilot-use-to-answer-my-prompt)

**公开交互事实**

- Excel 提供 **edit / plan / chat** 三种模式：edit 默认，直接改工作簿；plan 先生成步骤化方案，供用户 **review and confirm the approach before starting**；chat 只分析、不改工作簿。
- edit 模式提交后会显示步骤化计划，在侧栏展示推理过程，并让用户 **watch changes live in your workbook**；随时可点 **Stop**。
- 变更直接写入工作簿，不进入逐单元格待批准队列；恢复依赖 **Undo 或历史版本**，也可关闭自动保存来试做。官方 FAQ 明确提醒共享文件中的直接变更会被协作者看到。
- 范围与上下文：提示词可点名 **columns or ranges**；数据源可通过 **Add → Change data sources** 开关；Web 来源会列 citations；Work 来源要求用户 **显式选择并确认**，批准只对当前聊天有效，新聊天重新确认；外部连接器需显式同意，并引用来源。
- Copilot Chat 的 grounding 包括工作数据、Web、附件；有 Work IQ 开关，权限沿用 Microsoft 365 权限，回答带 citations。
- 错误路径：遇到 “Unsupported file state”，官方要求用户手工处理，例如在 SharePoint 中 check in，或把 Strict Open XML 另存为 `.xlsx`。

**本项目应审视的对照点**

- 是否像 Excel 一样，把“当前配置包/导入批次/选中配置项”做成显式范围，而不是让用户靠自然语言猜？
- 是否提供 **“只出方案”与“直接执行”分离** 的模式？配置变更比改单元格风险高，plan 模式值得保留，但还应升级为可审阅的 diff。
- 是否有“聊天不改数据”的只读分析模式，用于解释配置、定位差异，而不触发写入？
- 是否显示来源与引用：引用了哪些配置文件、字段、记录、外部连接器？Work 来源那种“本聊天内一次性授权”是否适用？
- 是否对锁定/签出/格式不支持等状态给出明确的手工恢复路径，而不是泛化报错？

**适用边界**

Excel 是单文档、直接操纵型工作区；plan mode 批准的是“做法”，不是逐项业务变更；没有多人审批流。它适合参考“方案先行、直接编辑、Undo/版本兜底”，不适合直接照搬为配置变更审批。另需注意平台差异：iPad/iPhone 主要是 chat/plan，Android 只有 chat，edit 仍在滚动推出。

---

## 2. Notion AI / Notion Agent

**官方 URL**
[What is Notion AI? FAQs](https://www.notion.com/help/notion-ai-faqs) · [Notion Agent](https://www.notion.com/help/notion-agent)

**公开交互事实**

- 内联编辑：选中文本 → **Edit with AI** → 结果可 **accept、discard、try again**；也可在新行按空格直接生成，AI 直接写在页面上，并可用 `@` 提及页面、人物、日期补充上下文。
- Notion Agent 默认读取 **当前页面**；如果用户选中了特定 block，Agent 会聚焦这些 block；可用 `@` 指定页面/人物，也可在 **All sources** 中选择一个或多个来源，包括已连接的 MCP servers；支持上传文件。
- Agent 的权限与用户一致：用户看不到或改不了的内容，Agent 也不能。
- 可执行多步任务，创建/编辑页面和数据库；数据结果会以聊天内的交互式表格展示；可通过安全 workspace 读取上传文件、做计算，并生成 **可下载的 XLSX、PDF、幻灯片、文档**。
- 对外部写动作有确认：例如 Gmail 的发送类动作会在执行前要求确认。
- **未说明**：对页面/数据库的多步修改是否逐步确认、如何整体回滚，该页没有给出机制；不能把内联编辑的 accept/discard 直接套到 Agent 上。

**本项目应审视的对照点**

- 上下文是否可见、可改：当前工作区/页面/选中项 + “所有来源”选择器 + `@` 指定对象，这套组合很适合配置工作区。
- 对“建议型变更”是否提供 **接受/丢弃/重试** 三态，而不是只有“执行/取消”？
- 对跨对象、多步变更，是否需要独立的确认与回滚说明？Notion 在这里只给了内联编辑的答案，Agent 层是空白，正好是本项目要补的。
- 完成后是否能直接产出可下载的导入报告、导出包、差异清单？Notion 的“生成可下载文件”是明确可参考的交付动作。
- 外部副作用（发信、调用第三方）是否单独确认，并与内部配置修改分开？

**适用边界**

Notion 是文档/知识型工作区，强在上下文选择、内联建议和下载产物；它的 accept/discard 是内容级，不是 schema/配置级。多步 Agent 的批准与回滚公开信息不足，不能假设它已有完整的变更治理。

---

## 3. Airtable Omni / Field agents（含 Cobuilder 方向）

**官方 URL**
[Using Omni AI in Airtable](https://support.airtable.com/articles/1744327578-using-omni-ai-in-airtable) · [Using Airtable AI in fields](https://support.airtable.com/articles/8052242094-using-airtable-ai-in-fields)

**公开交互事实**

- Omni 在 base、interface、home 都能打开；用自然语言规划并完成多步任务，并 **tells you what it changed**。在 base 内聊天只作用于当前 base；不能从 base 内聊天新建 base。
- 保护性交互很明确：删除 records/tables/pages 前 **always asks you to confirm**；新建 automation 默认 **turned off**，发布 interface、开启 automation 留给用户；读取外部网页/文件后，修改 base 前会要求确认。
- 完成后有 **Undo** 图标，但范围有限：可撤销它新增/删除的 records、创建/修改/删除的 fields、删除的 table、页面和 automation 变更；**不能撤销对已有 record 值的编辑，也不能撤销创建 table/interface/page**；只撤销当前对话中它自己的动作。
- 还有 **Checklist** 图标展示执行步骤、**Follow-ups** 建议后续请求、反馈入口；权限镜像当前用户，只能看/改用户有权限的内容。
- 明确限制：**Cannot export data**。官方还建议：如果只是把数据原样迁入 Airtable，**importing is more reliable than asking Omni to do it**；超大数据迁移应联系 Airtable 团队。
- Field agents 是单元格级 AI 字段：自定义指令可用 `{字段}` token 引用其他字段；可单单元格 **Run agent**，也可批量跑 **All cells in the view / Cells modified by agent / Outdated cells / Cells with errors / Cells never modified**。
- 可开启 **Run automatically**；修改 prompt 后保存会询问是否覆盖已有记录；**永远不会自动覆盖人工编辑过的单元格**；prompt/model 变更可通过 base snapshot 恢复。
- “Turn files into tables”：上传 spreadsheet，读取结构、建议组织方式，并建立 tables/fields——这就是 Cobuilder 方向的公开交互。

**本项目应审视的对照点**

- 破坏性配置操作是否有独立确认？发布/启用是否像 Airtable 一样 **留给人**，而不是 AI 一键生效？
- 字段映射/批量导入是否支持 **按范围重跑**：全部、已修改、过期、出错、从未生成？其中 “Cells with errors” 尤其适合导入失败行。
- 是否保护人工修正过的值，避免 AI 重跑覆盖？是否有“修改 prompt 后是否覆盖”的显式选择？
- 是否展示“这次改了什么”的 checklist，并在完成后给 follow-ups，而不是只回一句“已完成”？
- 是否提供可撤销范围说明？配置管理里最危险的是让用户以为“撤销”能回滚一切。
- 既然 Airtable 都明确“导入比让 AI 做更可靠”且 AI 不能导出，本项目更应把确定性导入/导出引擎保留为核心，AI 只做解释、映射建议、差异定位和修复建议。

**适用边界**

Airtable 是业务数据库/应用工作区，和“配置管理 + 导入导出”最接近：有对象级删除确认、发布/启用门控、字段级批量重跑、人工编辑保护。但它没有完整 diff 审批，Undo 范围有限，且 **AI 不做导出**；不能把它当成通用配置迁移工具。

---

## 4. Power Apps Copilot

**官方 URL**
[Build apps through conversation with Copilot](https://learn.microsoft.com/en-us/power-apps/maker/canvas-apps/ai-conversations-create-app)

**公开交互事实**

- 入口是结构化向导：**Start with data → Create new data**，打开带 Copilot 面板的表编辑器；输入描述后，Copilot 生成一个或多个 Dataverse tables。
- 生成后不是直接建应用，而是先进入 **Create new tables workspace**：可查看 tables 和 relationships，新增/移除 table、查看数据、创建关系，再点 **Save and open app**；官方明确说 “Review them and make any changes you need before you create your app.”
- Copilot 面板提供三个结构化动作：**Create**（描述表、列、行、关系）、**Import data**（Excel/CSV/SharePoint 列表）、**Change**（描述修改）。
- 权限/环境不满足时，会提示用户在自己的环境创建应用；这是手工处理路径。
- **未说明**：每次 schema 变更的逐项 diff、批准、撤销机制；该页只说明“先审表结构，再打开应用”。

**本项目应审视的对照点**

- 复杂输入是否先进入一个 **结构化对象/关系工作区**，让用户看清“要建哪些配置对象、它们怎么关联”，再让 AI 执行？这比纯聊天更适合导入映射。
- 是否把“生成/修改”与“保存/发布”分开？Power Apps 的 “Save and open app” 是明确的分段动作。
- 是否同时提供 Create / Import / Change 三类入口，而不是让用户在一个空聊天框里描述所有事？
- 对权限、区域、环境不满足的情况，是否有明确的手工切换路径？

**适用边界**

Power Apps 是应用/schema 生成器，不是配置 diff/审批工具；它的价值在“向导 + 自然语言修改”的混合输入，而不是变更治理。生成结果仍需人工审表，且公开页没有回滚说明。

---

## 5. Cursor Agent / Cloud Agents

**官方 URL**
[Cursor Agent](https://cursor.com/docs/agent/overview) · [What are background agents?](https://cursor.com/help/ai-features/background-agents)

**公开交互事实**

- Agent 可搜索代码、编辑文件、运行终端、调用工具；文件编辑是 **apply them automatically**，不是逐次弹窗批准。
- 自动创建 **Checkpoints**：在重大修改前保存代码库快照；点时间线中的 checkpoint 可预览当时的文件，再 **Restore** 回滚所有文件。恢复只回滚文件，**不移除对话消息**。
- 运行中可 **Queue** 消息，或 **Send now / steer** 在下一个工具调用处改向；Agent 可在等待回答时继续读文件/改代码/跑命令，回答到达后纳入。还有 `/goal` 长目标、side chats、无工具调用次数上限。
- Cloud/Background Agents：任务通常运行很久，搬到云端 VM，**close your laptop and check the result later**；会测试自己的改动，把 **videos、screenshots、logs** 附到 PR；完成后自动开 PR；用户可接管远程桌面自己改；可从 web/mobile/Slack/GitHub/Linear 启动，也可定时/事件触发。
- **未说明**：本地 Agent 的逐次批准机制、失败时是否要求用户手工处理；公开页的控制点主要是 checkpoint、diff 和 PR。

**本项目应审视的对照点**

- 长任务能否离开再回来？是否有独立任务状态、进度、产物（报告/日志/差异）和通知？
- 配置变更是否需要 checkpoint？回滚范围是“文件”还是“业务对象/导入批次”？Cursor 的“只回文件、不回消息”提示我们：恢复语义必须写清楚。
- 运行中是否允许用户补充指令/改向，而不是只能取消重来？
- 完成后的“proof of work”是否可借鉴：导入报告、失败行清单、前后对比、截图/日志，直接附在任务结果上。
- Cursor 的自动应用 + Git/PR 审查适用于代码；配置管理若自动应用，应至少落到 staging/预览环境，或要求显式批准后再进入生产。

**适用边界**

Cursor 是代码工作区，强在异步长任务、checkpoint、PR 产物；它的审批是“事后 diff/PR”，不是业务字段级事前批准。不能把它的自动应用模式直接用于高风险配置生产变更。

---

## 6. VS Code Copilot agents

**官方 URL**
[Understand trust and safety](https://code.visualstudio.com/docs/agents/concepts/trust-and-safety) · [Review and revert agent changes](https://code.visualstudio.com/docs/agents/run/review-code-edits) · [Use the Agents window](https://code.visualstudio.com/docs/agents/run/agents-window)

**公开交互事实**

- 信任模型明确分层：**Review before integrating**（提交/合并/PR 前看 diff）；**Approve sensitive actions**（手动权限、敏感文件批准）；**Constrain autonomy**（权限级别）；**sandboxing**（OS 级文件/网络限制）。
- 重要限制：停止请求或恢复文件，**不会撤销已完成的终端命令、网络请求、部署或外部服务变更**。
- 审查行为因 harness 而异：Copilot/Claude/Codex 的编辑直接写入 session folder/worktree，不进入 pending 队列；Local harness 支持逐项 **Keep / Undo**；Cloud session 通过分支和 PR 返回。
- 恢复能力很强：Checkpoint 恢复文件并删除后续对话轮次，可 **Redo**，可 **Fork Conversation**；编辑之前的请求会回滚该请求及之后的所有文件变更并重发；Git/Source Control 另行处理。
- Agents window 用 **active session** 决定当前工作区和上下文；可附加 folder/repository、issue、PR；可 `Alt+Enter` 后台启动新 session；可选 worktree 隔离或直接改当前 folder；重载后恢复对话、草稿、布局；cloud automations 在关机后继续运行。
- 完成后可 Commit、Create PR、配置 **Agent merge**：让 agent 处理 review comments、CI 失败、分支落后/冲突，再合并；也可在 PR diff 上留评论。
- 错误处理不是纯手工：可跑测试、debugger、diagnostics；agent merge 可修 CI/review/conflict；但外部副作用仍要用外部系统自己的恢复手段。

**本项目应审视的对照点**

- 是否把“变更审查”做成独立面板：每个导入/配置任务的 diff、影响文件/对象、行级统计？
- 是否支持逐项 Keep/Undo 与整体 checkpoint/redo？恢复范围是否明确区分“配置数据、任务状态、外部副作用”？
- 是否显示当前活动工作区/批次/会话，并允许附加额外上下文（配置文件、字段字典、错误报告、相关批次）？
- 后台任务是否有 session 列表、状态、恢复、通知？VS Code 的“关机后 cloud automations 继续”是长任务体验的强参考。
- 是否把“发布/合并”与“修改”分开，并允许人工在最后一步审查？Agent merge 这种“自动修 CI 后再合并”只适合有强测试与回滚的场景。

**适用边界**

VS Code 是开发者/Git 中心的工作区，审查与恢复语义最完整，但 worktree、PR、checkpoint 对业务用户偏重；它是“文件级”而非“配置对象级”。外部系统变更不可逆这一点，对配置管理尤其重要。

---

## 7. OpenHands Agent Canvas（开源工作区代理）

**官方 URL**
[Key Features](https://docs.openhands.dev/openhands/usage/key-features) · [OpenHands repository](https://github.com/OpenHands/OpenHands)

**公开交互事实**

- 公开 UI 能力包括：**Chat Panel**（对话与动作解释）、**Changes Tab**（显示文件变更）、**embedded VS Code**（浏览/修改文件，也可上传下载）、**Terminal**、**App**（运行中的应用可交互）、**Browser**（非交互浏览）。
- 支持多种后端：本地、Docker、VM、云；Agent Canvas 可在不同 backend 间切换；每个 conversation 有自己的 workspace；可按计划或 webhook 运行 automations。
- 官方 README 说明：部署在服务器/云上时，**agents continue running even when your laptop is shut**；多个 Docker sandbox 场景下，workspace 文件和 conversation history 在容器替换后仍保留。
- **未说明**：UI 层的逐项批准/拒绝、变更回滚、失败恢复流程。Key Features 只说明有 Changes tab；不能把 SDK 的 confirmation policy 当成默认 GUI 行为。

**本项目应审视的对照点**

- 工作区抽象是否完整：一个任务是否同时具备对话、变更列表、文件浏览/上传下载、执行终端/应用预览？
- 长任务是否真正与前端解耦，用户关掉页面/电脑后任务仍继续？恢复时是否保留任务状态与产物？
- “Changes tab” 只是展示，还是能逐项审阅/批准/回滚？OpenHands 没有给出公开答案，本项目应补上。
- 是否支持多后端/隔离环境，让导入预览、生产应用、导出任务互不污染？

**适用边界**

OpenHands 是开源编码代理工作区，适合参考 workspace、持久化、长任务后端；但它的 UI 治理公开信息不足，且面向开发者，不是业务配置管理。它是“工作区形态”的参考，不是“业务审批语义”的参考。

---

## 8. Retool AI：组件型边界（不计入 7 个同类产品）

**官方 URL**
[Retool AI](https://docs.retool.com/ai) · [AI text actions](https://docs.retool.com/queries/guides/ai/text)

**公开交互事实**

- AI 被组织为 app/workflow 里的 **AI Action query/block**：Generate/Summarize/Classify/Extract 等；结果在 `{{ query.data }}`，在组件中可流式显示；可用 Vectors 提供上下文；模型可动态选择。
- 该文档只描述“查询/区块 + 由搭建者决定 UI”；**看不到面向终端用户的统一预览、批准、回滚机制**。审批必须由搭建者在应用里自行设计。

**适用边界**

Retool 是组件/查询层，不是“业务工作区 + AI 助手”的完整产品；它适合参考“AI 输出如何嵌进现有业务 UI”，不适合作为交互治理的标杆。

---

## 八个维度速查

| 维度 | Excel | Notion | Airtable | Power Apps | Cursor | VS Code | OpenHands |
|---|---|---|---|---|---|---|---|
| 当前工作区/范围 | 当前工作簿；列/区域需点名 | 当前页；选中块；All sources | 当前 base；字段 token | 当前表工作区/环境 | 当前仓库 | 活动 session 的工作区 | 挂载的项目 workspace |
| 预览/批准 | Plan 先审方案；edit 直改 | 内联接受/丢弃/重试；外写确认 | 删除确认；发布/启用留给人 | 先审表/关系再建应用 | Checkpoint 预览/恢复 | Diff、Keep/Undo、敏感文件批准 | Changes tab；批准未说明 |
| 完成后的下一步 | 工作簿已更新；Undo/版本 | 下载 XLSX/PDF/幻灯片 | Undo、Checklist、Follow-ups；不能导出 | Save and open app | PR、视频/截图/日志产物 | Commit/PR/Agent merge | Changes、VS Code 上传下载 |
| 上下文可见性 | 数据源开关；Web/Work citations | 当前页/块/All sources/@ | 当前 base；权限镜像；Checklist | 生成的表/关系可见 | 仓库/VM 环境 | 活动 session + 附件 | 文件/终端/浏览器/应用分栏 |
| 后台执行/恢复 | 未说明 | 未说明 | 字段可自动运行，但不是对话后台 | 未说明 | Cloud VM；关笔记本 | Cloud sessions/automations；重载恢复 | 服务器运行；容器替换后保留 |
| 长任务是否要盯着 | 要；可 Stop | 要等；未说明通知 | 要等；未说明通知 | 交互式 | 可离开 | 可离开 | 可离开 |
| 出错是否手工处理 | 文件状态需手工修复 | 内联可重试；Agent 失败未说明 | 可批量重跑错误单元格 | 权限/环境需手工切换 | 自测；失败升级未说明 | 测试/诊断；Agent merge 修 CI | 未说明 |
| 复杂输入 | 对话 + Plan | 对话 + 来源选择 | 对话 + 字段配置表单 | 向导 + 对话 | 对话/代理 | 对话 + 配置/上下文 | 对话 + 工作区 |

---

## 对本项目的最小对照清单

1. **范围**：用户能否一眼看到当前工作区、配置包、导入批次、选中字段/记录？能否像 Notion 的“当前页 + 选中块 + All sources”一样切换？
2. **方案**：是否提供“只出方案/只读分析”和“直接执行”两种模式？复杂变更能否先看到计划再启动？
3. **预览**：变更前是否有配置级 diff、字段映射预览、影响对象列表？不是只有一句“将修改 37 项”。
4. **批准**：能否逐项/批量接受、拒绝、重试？破坏性操作是否二次确认？发布/启用是否留给人？
5. **恢复**：撤销范围是什么？是只回滚配置数据，还是也回滚任务状态/外部副作用？是否明确告知“哪些不能撤销”？
6. **完成后的下一步**：是否给导入报告、失败行 CSV、导出包、差异清单、继续修复/重试入口？Notion 的下载产物、Cursor 的 proof artifacts 都可参考。
7. **长任务**：能否离开页面/关电脑后继续？是否有任务中心、进度、取消、恢复、通知？Excel/Notion/Airtable 的同步等待模式不适合大数据导入导出。
8. **错误**：能否定位到行/字段/单元格，批量重跑“仅失败项”，并保护人工修正过的值？Airtable 的 “Cells with errors” 是很具体的参考。
9. **复杂输入**：是否先用向导收集工作区、对象、字段映射、冲突策略，再用对话处理意图和例外？Power Apps 的表工作区 + Copilot 面板是最接近的混合模式。
10. **AI 的边界**：是否坚持确定性导入/导出引擎为核心，AI 只做解释、建议、差异定位和修复？Airtable 官方明确说“导入比让 AI 做更可靠”，且 Omni 不能导出——这正是本项目应避免的反面教材。

**一句话取舍**：把 AI 放在“范围选择 + 方案/差异预览 + 可撤销执行 + 结果报告”这四个环节里；不要让聊天框成为唯一入口，也不要让 AI 取代导入导出引擎。
