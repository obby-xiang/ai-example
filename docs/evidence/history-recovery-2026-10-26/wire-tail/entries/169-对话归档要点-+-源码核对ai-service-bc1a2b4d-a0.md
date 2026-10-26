> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-0` 的 wire 末条 assistant 文本（消息序号 m=14；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-04 20:00 → 2026-10-04 22:20（wire 事件时间窗，UTC+8）；当时模型：`kimi-code/k3`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：9122 B / 5382 字符 / 64 行；行指纹覆盖率（本批复算）2.4%（最高单文件 2%：`docs/决策看板.md`）
> - 类别：**过程核验**
> - 与既有件关系：施工报告；报告口径覆盖率 0.02
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
所有材料已收集完毕。以下是结构化摘要。

---

# 调研摘要：ai-example 需求方案分析素材

## 任务 1：对话归档要点

### 归档一：`docs/conversation-archive-20260904-excel-capabilities.md`（1780 行）

文件名虽叫 "excel-capabilities"，实际是**主会话（6a89405a…）从立项到 Excel 能力补齐的全量高保真归档**：44 条用户消息逐字原文 + 28 条轮次级记录。要点：

1. **归档来源与保真度边界**：用户消息来自 Trae 本地 `state.vscdb` 输入历史（逐字）；AI 回答原文存在 SQLCipher 加密库中无法恢复，只能以轮次级记录（intent/actions/outcome/learned）替代。归档需求本身源于用户两次批评（消息 #43「信息压缩太严重」、#44「为啥我能看到完整历史」）——这是设计文档不会体现的「文档为什么存在」背景。
2. **原始 PRD 完整保留**（消息 #1/#2）：左右双栏、4 场景向导、SpreadJS、AI 双向交互、5 条不可协商约束（含 1万~10万行数据量级）。#2 直接贴了真实 api-key，为后续密钥泄露事件和 pre-commit 扫描 hook 埋下伏笔。
3. **Spring AI 版本是被用户纠正的**（#3「要用1.1.8，用不到alibaba」）；踩坑：starter 从 `spring-ai-openai-spring-boot-starter` 改名 `spring-ai-starter-model-openai`；华为云镜像未同步 1.1.8，必须阿里云 public 镜像。
4. **架构转向完全由用户质疑驱动**：#5「指令解析不靠谱，为什么不做成前端 tool」→ #6 用户自己提出两方案（前端 runtime+后端转发鉴权 vs 后端 runtime 暂停-恢复）→ #8 硬约束「工具定义都放后端，前端只传名称」。设计文档中「用户硬约束」的原始出处在此。
5. **核心理念的用户原话**（#11）：「不应该把大段业务数据发给 AI，告诉 AI 可以做什么」→ 催生动态工具发现/MCP 模式；#12 跨页面连贯性 → 状态图+Plan-then-Execute+Progressive Disclosure 三层；#14 工具依赖 → 串行执行决策。
6. **400/500 系列踩坑全记录**（#18–#20 + 轮次 2.18–2.21）：①assistant tool_calls 必须配足 tool messages（修复：串行只执行第一个）；②user 消息重复入列；③DeepSeek thinking 模式 `reasoning_content` 必须回传否则 400 → 关闭 thinking 根治；④`cleanupWaitingState` 误清理活跃 WAITING_TOOL → 二次确认回灌 500；⑤AI 卡在 SELECT_SCENARIO → system prompt 补流程推进规则。
7. **进度条 bug 与 collect_user_input 的诞生**（#21/轮次 2.22）：根因 scenario=null 跳步导致 steps 空数组；用户要求「输入框/单选/多选/按钮/组合表单」→ Schema-driven 表单工具（业界 Adaptive Cards 模式）。
8. **Excel 需求来自用户不满**（#27）：没有导入模板、在线编辑无表头、导出无在线预览。技术要点：ExcelIO 默认不导出 colHeader 区（表头须写 viewport 行 0）；离线 Workbook 须挂非可见 DOM 且非零尺寸；Excel List Validation 公式 255 字符上限；ComboBox CellType 不导出 xlsx 反而正好实现「Excel 用原生下拉」；`el-upload auto-upload=false` 时 `uploadFile.raw` 才是 File。
9. **Excel 4 bug 根因**（#32）：①②同源（`StepResult.openPreview` 硬编码 `allData(defId,100)` → 100000）；③④同根因（表头写在 colHeader 区，ExcelIO 不导出）→ template 模式表头改写 viewport 行 0、数据从行 1 起。
10. **会话生命周期四问与约束收敛**（#35/#36）：用户明确「会话随窗口、不改 URL、关窗即毁」（ephemeral session）；并要求超时取消、手工介入感知。#37 带来 ChatGPT 长文（Task-centric + Workspace 实时同步、CopilotKit Shared State/AG-UI 三件套），评估结论：架构方向已覆盖大半，**核心缺口只有「页面→AI 反向感知」（1月改2月问题）**，行动上是抬高 dataVersion 优先级。

### 归档二：`docs/conversation-archive-session-6a8abe78.md`（1073 行）

跨 2026-08-23（Excel 收尾/验证/4 bug 修复）与 2026-09-04（会话生命周期架构设计）两天，18 条用户消息 + 17 条轮次记录。要点：

1. **端到端验证的操作细节**：contenteditable 输入框须用 `.input-area` 类选择器；SpreadJS 为 **v17.1.10 商业版**，LicenseKey 经 `VITE_SPREADJS_KEY` 注入，未授权即评估模式（水印+功能限制）；select 长选项用隐藏列 colIdx+200 + DV 公式绕过；大文件 >5MB 警告 + `setTimeout(0)` 让帧防卡顿。
2. **Excel 4 bug 决策表 E1–E3**：两范式（ai-ui / ai-ui-vercel）对称修复，后端接口零改动——体现了「双范式代码对称」这一维护约束。
3. **会话生命周期设计的完整决策链**：初始四问 → 用户约束收敛（ephemeral session=Intercom Fin 同款、pending TTL 10min=AG-UI 超时默认拒绝、手工介入=版本号感知）→ ChatGPT 方案评估 → 改造方案 A–E → plan v1.0。
4. **代码探索三关键发现**（设计文档没有的实证）：`ChatReq` 已有 `workspaceState` 快照通道（等价 Microsoft AG-UI 请求携带 state 的 pull 模式）；`ToolResultReq` **缺 workspaceState 字段**——「1月改2月」问题的精确位置（挂起期间改数据，回灌拿到挂起时旧快照）；两范式一致性水位不同（vercel 直读 store 天然实时，ai-ui 靠请求时快照会冻结）。
5. **push vs pull 的关键取舍**：事件流划不清「哪些变更影响决策」的边界，会退化成全量推送、重回 token 膨胀老路；配置管理场景 pull 足够，唯一需要 push 语义的「pending 期间变更」用版本号对比等价解决。这是全项目最重要的取舍理由之一。
6. **dataVersion 方案的业界原型与如实交代**：「挂起记版本→恢复比版本→变了强制现查」是 Claude Code "File has been modified since read" 的逐字翻版；其著名缺陷（mtime 被 IDE autosave 污染的假阳性）也如实记录，升级路径=内容 hash。`store.$onAction` 白名单拦截（loadXxx 排除）被坦诚为非业界标准 API 的工程简化，与 CopilotKit 显式 setState 埋点语义等价。
7. **打断/穿插/上下文三问**（#48）：工具不需要中途打断，需要「轮次边界停止+保留现场、不回滚」（LangGraph interrupt / AG-UI RUN_CANCELLED）；流程抗穿插靠状态机与对话物理分离（Rasa slot 独立于 story）+ prompt 准则；上下文三层策略=预防（工具现查/结果截断/按需发现）→压缩→隔离，摘要压缩可后置。
8. **决策速查表 10 条**：含 workspaceState 契约化固定 schema（只放摘要，CopilotKit Shared State 契约化思想）、ToolResultReq 补 workspaceState、pending TTL 10min 过期拒绝回灌等——与最终落地的改造 A–H 一一对应。
9. **归档方法论沉淀**：Trae 会话恢复路径（复制 state.vscdb → Node 22 `node:sqlite` 读 input-history + session_memory jsonl 交叉比对圈定 session 边界）；#28 真实 API key 已脱敏为 `[REDACTED_KEY]`。

两份归档有部分重叠（ChatGPT 长文同时收录于两者）；归档二末尾注明 #41–#47 属归档一 session 的归档/密钥补救对话，两个 session 的边界已厘清。

---

## 任务 2：源码核对结果

### 2.1 `ai-service/src/main/resources/tools.yaml`（358 行）— ✅ 13 个工具齐全

`grep -c 'name:'` 确认为 13。分类与设计文档 §3.1 完全一致：

| 类别 | 工具 | 信任分级 | 适用步骤（scenarios/steps 过滤） |
|---|---|---|---|
| 路由/状态（FRONTEND） | `navigate_step` | autoExec | 全场景全步骤 |
| 只读查询（**BACKEND**） | `get_workspace_state`、`list_config_defs`、`get_config_def` | autoExec | 前者全步骤；后两者 SELECT_DEFS/VIEW_DEFS/QUERY_COND |
| 选择/确认（FRONTEND） | `select_definitions`（EXPORT·SELECT_DEFS）、`confirm_complete`（RESULT/PUBLISH） | autoExec | — |
| 破坏性表格（FRONTEND） | `table_batch_set_field`、`table_replace_values`（EXPORT/ADD/MODIFY·VIEW_DEFS/QUERY_COND）、`table_delete_rows`（仅 MODIFY·VIEW_DEFS） | needConfirm=true | — |
| 高危流程（FRONTEND） | `run_flow` | needConfirm + **requireDoubleConfirm** | RESULT/PUBLISH |
| 用户输入（FRONTEND） | `collect_user_input`（8 种控件）、`excel_import`（IMPORT/ADD/MODIFY·VIEW_DEFS） | INPUT 模式 | — |
| Excel 导出（FRONTEND） | `excel_export`（EXPORT·RESULT） | autoExec | — |

### 2.2 `ai-service/src/main/resources/scenarios.yaml`（108 行）— ✅ 4 个状态图齐全

EXPORT 4 步（SELECT_SCENARIO→SELECT_DEFS→QUERY_COND→RESULT→END）；IMPORT/ADD/MODIFY 各 5 步（SELECT_SCENARIO→VIEW_DEFS→PRECHECK→REVIEW→PUBLISH→END）。每个步骤声明 tools 子集（progressive disclosure），`navigate_step`/`get_workspace_state` 全步骤在列，与 tools.yaml 的 scenarios/steps 字段互相印证，无孤儿引用。

### 2.3 `ai-service/.../service/AiService.java`（1145 行）— ✅ Agent Loop 与 resumeToken 机制真实实现
