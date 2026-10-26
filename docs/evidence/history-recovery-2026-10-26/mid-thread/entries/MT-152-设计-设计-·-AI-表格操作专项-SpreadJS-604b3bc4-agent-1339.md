<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-152 · 设计 · 设计 · AI 表格操作专项（SpreadJS 前端工具通道 + 数据最小化）（604b3bc4 / agent-1339）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`workspace.ts`、`SpToolCallingManager`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m41` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 8 条有正文），末条有正文消息为 `m53`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1339`（`main` = 主会话）；原字符 31,037（链内 1 段、合计 31,037）。
> **链号 / 类别**：`MT-152` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `59%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `5%`。
> **与末条归档（138 件）的关系**：末条 `m53`（36,322 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

核实完成。我没有写文件权限（本 profile 的活动工具集里没有 Write/Edit/Bash），因此下面直接给出**可落盘的完整正文**，请由有写权限的一方原样写入 `docs/research/设计-AI表格操作专项-2026-10-21.md`。

---

# 设计 · AI 表格操作专项（SpreadJS 前端工具通道 + 数据最小化）

- 日期：2026-10-21
- 范围：AI 在**在线表格（SpreadJS）**上的读/聚合/诊断/改，走**前端工具通道**；业务行数据**不出浏览器**（只回采样 + 总量）
- 依据件：
  - `docs/research/调研-AI操作在线表格能力业界惯例-2026-10-21.md`（§③ 场景三档取舍、§⑤ 批次切分草案、§2.3 缺失面 M1–M10）
  - `docs/research/调研-AI上下文数据最小化业界惯例-2026-10-21.md` §4.2（**R3** ≤20 行 / ≤2000 字符 / 必回"共 N 条"；**R8** D2>20 行与 D4 一律不走上下文；**R4** 双通道同源）
- 体例参照：`docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md`
- 性质：**设计稿，不构成施工承诺**；§7 四项待裁未裁前不进施工
- 自证（wire `profile.bind`，`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1339/wire.jsonl`，**全文件 grep，非 head -1**）：`"type":"profile.bind"` 命中数 = **1**（唯一绑定记录）；该记录原文 `{"type":"profile.bind","agentId":"agent-1339","modelAlias":"DeepSeek/deepseek-flash","profileName":"plan","thinkingEffort":"high",…}` ⇒ 三值 = **model = DeepSeek/deepseek-flash**、**profile = plan**、**thinking = high**

## 0. 结论速览（八条硬口径）

1. **SpreadJS 是真的，不是纸面集成**：`frontend/package.json:16-18` 三个包精确钉版 `17.1.5`（`spread-sheets` / `spread-excelio` / `spread-sheets-resources-zh`），懒加载入口 `frontend/src/utils/spreadjs.ts:45-66`，宿主组件 `frontend/src/components/SpreadGrid.vue`。**用户口径与仓内现状一致**；两点须提前知悉（§2.1）：**License 是评估模式**（`VITE_SPREADJS_KEY` 仓内留空，`frontend/.env.example:9-10`）、**没有独立"在线表格"页**（表格只出现在导出结果页与导入上传页两处）。
2. **AI 只发意图，执行在前端**：复用既有四路由前端工具执行器（`frontend/src/utils/frontend-tools.ts:484-508`）+ 页面能力注册表（`frontend/src/stores/workspace.ts:269-282`），执行器**不 import 任何视图**，经 `registerPageHandler('sheet.*')` 取到表格适配器。
3. **"前端工具没有 HITL"是既有硬事实，写工具必须把它改掉**：`frontend/src/stores/ai.ts:2760` 注释白纸黑字"前端工具没有 HITL：立即在浏览器执行并回灌结果"；后端 `SpToolCallingManager.kindOf`（`:982-990`）**通道优先于风险** ⇒ 前端通道工具永远进不了确认门。故写工具的确认只能**落在前端**（§3.4 选项 A，理由与代价见 §7-裁 1）。
4. **确认是"人围栏"而不是"超时围栏"**：新增写工具加入"人工介入型前端工具"名单，`ConfirmGate.suspendWaitSeconds`（`:322-330`）返回 `null` ⇒ 帧不带 `timeoutSeconds`，一直等到用户点"应用/取消"。既有先例只有一个名字：`generative_form`（同函数 `:326`）。
5. **五工具 = 调研"建议做 5 项"在 SpreadJS 语境的投影**：`read_sheet_summary`（↔P0 数据行可查）/ `aggregate_sheet`（↔P1 聚合）/ `inspect_columns`（↔P1 映射诊断）/ `review_cells`（↔P0 预检问题可读）/ `transform_cells`（↔P2 修订建议 + 写前预览）。**`apply_formula` 与 `format_range` 明确裁掉**（理由见 §1 与 §3.2.6：导出链 `buildRowsXlsxBlob` 只写 values、styles 单一样式，公式与格式**落盘即丢**，写了等于骗用户）。
6. **尺寸闸单点**：`capSheetResult()`（`utils/sheet-tools.ts`）是唯一回灌出口，硬闸 `≤20 行 / ≤2000 字符` + 恒定"共 N 行/共 N 处" + 截断标记；**行数优先于字符数**（沿用 `ToolResultLimiter` 口径）。
7. **写操作只改浏览器内存**：不落服务端文件、不触发保存、不碰作业；落库仍是用户点"保存到服务端"（`ExportWizardView.vue#saveEdits:1054`）这条**既有人的动作**。AI 的最大权限边界 = 内存态 + 一次单步撤销。
8. **零新帧类型**：`frontend_tool_request` / `frontend_tool_result` 契约不动；用户的"拒绝"沿用既有 `cancelled=true` ⇒ `FRONTEND_CANCELLED`（`ConfirmGate.java:91-93`）。

---

## 1. 范围与不做面

### 1.1 做

| # | 面 | 落点 |
|---|---|---|
| A | 4 个只读工具：摘要 / 聚合 / 列映射诊断 / 本地校验摘要 | 前端登记工具（路由 1）+ `sheet.*` 页面能力（路由 4） |
| B | 1 个写工具 + 1 个撤销：`transform_cells`（预览→人确认→应用）/ `undo_last_sheet_edit` | 同上 + 前端预览卡 + store `activePreview` |
| C | 尺寸闸与"共 N"口径（R3）单点化 | `utils/sheet-tools.ts` |
| D | 人工介入型前端工具的无时限等待（后端名字集合扩项） | `ConfirmGate#suspendWaitSeconds` |
| E | 后端 6 个 `@Tool` 哨兵桩 + scope/风险元数据 + 披露测试同步 | `AiTools.java` + `ToolRegistryDisclosureTest` |
| F | 只读壳（任务已结束）下的写拦截、刷新后预览失效的收口 | `ExportWizardView` / `ImportWizardView` / `stores/ai.ts` |

### 1.2 不做（逐条给理由，防"顺手做了"）

| 不做 | 理由 |
|---|---|
| **`apply_formula`（AI 写公式）** | ① 本仓表格列由**字段定义驱动**（`applyColumnsToSheet`），没有自由公式位；② 导出链 `buildRowsXlsxBlob`（`utils/excel.ts:540-636`）自产 OOXML，只写 `<v>` 值与共享串、`styles.xml` 只有 1 个 `cellXfs` —— **公式写了落盘即丢**；③ `readRows`（`:361`）读的是值，后端 `ExcelReader` 按表头认列，公式对下游零意义；④ 调研 §3.3 已把"通用公式引擎"列为不做 |
| **`format_range`（AI 改格式/美化）** | 同上 ②③（格式不落盘）；且调研 §3.3 明确不做"自动排版/美化"。**保留的替代**：预览态**高亮改动格**（纯前端绘制，不写 cell format）—— 这是"可核对性"，不是"排版" |
| 整表 / 整个 xlsx 进模型 | R8：D4 一律不走上下文；与 `tool-result.max-rows=200/max-chars=8000` 及压缩专项 L1 直接冲突 |
| AI 直接改单元格且无确认 | 与 FR-5.3 复核清单冲突；业界 2025–2026 全在"计划/预览 + 撤销"方向 |
| AI 触发保存 / 发起作业 | `saveEdits` / `start_export` 等仍是人/既有确认门的动作；本专项不新增任何落库路径 |
| AI 改配置定义 schema | 定义是全局结构（引用/模板/预检规则），调研 §3.3 不做 |
| 非当前激活标签页的表格操作 | 导出结果页是 `el-tabs`，非激活 tab 的 `SpreadGrid` 可能未挂载（`loadedGrids` 懒加载，`ExportWizardView.vue:821-849`）；"打开某 defCode 的编辑器"是 `open_export_file_editor` 的职责，不重复 |

---

## 2. 现状摸清（读码实证）

### 2.1 SpreadJS 集成点（用户口径核实结论：**一致**）

| 面 | 实证 | 对本设计的约束 |
|---|---|---|
| 依赖与版本 | `frontend/package.json:16-18`：`@grapecity/spread-excelio 17.1.5`、`@grapecity/spread-sheets 17.1.5`、`@grapecity/spread-sheets-resources-zh 17.1.5` | 只用 17.x 既有 API；不引第三方表格库 |
| 加载方式 | `utils/spreadjs.ts:45-66`：**动态 import**（首屏不背 4.7MB）+ zh-cn culture + 样式 css；`isSpreadCoreLoaded()` | 任何"先加载引擎再操作"的路径必须 `await` 它就绪，否则 jsdom/首屏都会踩空 |
| License | `utils/spreadjs.ts:54-57` 读 `VITE_SPREADJS_KEY`；`frontend/.env.example:9-10` **留空 = 评估模式（水印，功能可用）**；`.env.local` 不入库 | 本专项**不依赖授权态**（读写 API 与水印无关）；生产授权是部署前置，不是代码前置 |
| 宿主组件 | `components/SpreadGrid.vue`：`onMounted:141-188` 建 `new GC.Spread.Sheets.Workbook(host)`；`defineExpose:353-362` = `loadData/loadFromJson/loadFromXlsx/loadFromRows/collectRows/toWorkbookJson/toXlsxBlob/validateLocal` | **新增能力必须以"窄接口"追加**（§3.6 的 `SheetAdapter`），不把 SpreadJS 类型漏进 store |
| 版式口径 | 视口第 0 行 = 表头，数据从第 1 行起（`setRows:326`、`readRows:361`）；`applyColumnsToSheet:221` 挂列级校验器/格式；冻结首行 | "行号"一律用 **Excel 行号**（`excelRowNumber():36`），避免模型与用户对不上号 |
| 变更事件 | `SpreadGrid.vue:153-160` 绑 `CellChanged/RangeChanged/RowChanged` → `emit('data-changed')`；`restoring` 标志在程序化载入期间抑制事件（`:84,:153-157`）；导出页 `onGridChanged` 会 `workspace.bumpVersion()` + `workspace.touch(...)`（`ExportWizardView.vue:855-863`） | AI 写必须**显式**决定是否抑制事件；本设计选择"抑制逐格、写完后触发 1 次 `data-changed` + `bumpVersion`"（否则一个 200 格操作会打 200 次事件） |
| 只读壳 | `applyReadOnly():215-224` 用 `sheet.options.isProtected = props.readOnly`（任务已结束/导出完成后翻转） | 写工具必须**在执行器入口**判 `readOnly`（不能只靠 SpreadJS 保护，保护可被程序化 `setValue` 绕过） |
| 表格在哪些页面 | `ExportWizardView.vue`（第 3 步结果 tab，`gridRefs[defCode]`，`:807-849`）；`ImportWizardView.vue:68`（上传后编辑，`:622` `loadFromXlsx`） | 两页要**同一套** handler；抽成共享 composable，别在两页各写一遍 |
| 导出链的硬约束 | `buildRowsXlsxBlob`（`utils/excel.ts:540-636`）：单一样式（`:610-616`）、只写数字/共享串（`:567-570`）、无公式 `<f>`、无 `<cols>` 宽 | **格式/公式/列宽不持久**（§1.2 裁掉 `apply_formula`/`format_range` 的直接证据） |
| 撤销 | 全仓 `frontend/src` 内 `undoManager` / `.undo(` / `setFormula` **零命中** ⇒ 内置撤销未启用、未被取证 | 撤销用**自有反向补丁**（`SHEET_UNDO_DEPTH=1`），不依赖未取证的 API |

### 2.2 前端工具通道机制

- 注册表：`types/tools.ts:79` `FrontendToolName` **只有 2 个**（`open_export_file_editor` / `download_export_files`），`FRONTEND_TOOLS:82-107` 只有 2 条描述符（含 `args` 规格 + `scopePatterns`）。
- 四路由派发（`utils/frontend-tools.ts:484-508`）：①登记工具（参数校验 `validateArgs:148` + scope `matchesScope:121` + 执行器）②工作区动作表 ③ui_event 同名 ④**页面能力 handler**（`getPageHandler`）。
- 结局单点工厂 `makeExecution:100`（`ok === (outcome === 'ok')`）；执行器可返回**结构化** `{ok:false,reason}` 如实报失败（`interpretHandlerOutcome:1019`）。
- 统一预算：`FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000`、`deriveFrontendToolBudgetMs:278`（服务端上限 − 回灌 POST 上限）。**这意味着"人在环"的工具绝不能被这套预算包住** —— 只有 60s，用户来不及点。
- 回灌唯一前置：`stores/ai.ts#runFrontendTool:3059` → `executeFrontendTool` → `submitFrontendToolResult:3117`；轮次纪元守卫 `:3094`；用户侧终止走 `cancelled=true`（`ConfirmGate.java:91-93`）。
- 已有前例（本设计直接复用其形态）：`generative_form` 在 `frontend_tool_request` 分支被**特判**绕开通用执行器（`stores/ai.ts:2754-2758`），走 `openGenerativeForm:2127` → 用户填 → `submitGenerativeForm:2208`。**"人工介入型前端工具"在本仓已存在一条完整样板链路**。

### 2.3 `open_export_file_editor` 打开的是什么（用户问点的正面回答）

**它打开的不是独立"编辑器组件"，而是导出向导第 3 步（结果）里的那个结果标签页**：

1. handler 注册于 `ExportWizardView.vue:1323-1360`，名 `export.openEditor`（常量 `PAGE_HANDLER_OPEN_EXPORT_EDITOR`，`frontend-tools.ts:78`）；
2. 流程 = 任务绑定闸门（`ensureTaskBoundForTool:1115`）→ 结果存在性 → 切到第 3 步（只读态走零写 `viewStep(2)`，否则 `goStep(2)`）→ `activeTab = defCode` → `ensureGridLoaded(defCode)`（`:821`，内部 `tasksApi.downloadFile` + `grid.loadFromXlsx`）；
3. 即 **SpreadJS 早已在这里**：`SpreadGrid` 用 ExcelIO 打开下载到的 xlsx（`blobToWorkbookJson`），并按字段定义重建列校验器。

结论：本专项**不需要新组件、不需要新页面**——AI 操作面就是"它已经能打开的那张表"。工具只需拿到该 `defCode` 对应的 grid 适配器。

### 2.4 确认门现状与"前端工具无 HITL"的硬事实

- 后端 `SpToolCallingManager.kindOf:982-990`：**先判通道**（FRONTEND ⇒ `KIND_FRONTEND`），**再判风险**（DANGER ⇒ `KIND_CONFIRM`）。故今日**任何** FRONTEND 工具即使标 DANGER 也进不了确认门（`awaitConfirm:603` 只服务 `KIND_CONFIRM`，放行后 `executeOnce` 走**后端**执行）。
- 等待上限只区分三种（`ConfirmGate.suspendWaitSeconds:322-330`）：`KIND_CONFIRM` 无上限；`generative_form` 无上限（**按名字**）；其余前端工具 `frontend-tool-timeout`（默认 120s）。
- 前端两道闸门扩展点已就位：`FrontendToolGuard`（下发前 `inspectArguments` / 回灌前 `inspectResult`，`FrontendToolGuard.java:30-93`），**挂起机制对工具名零知识**，新增复核器只需加一个 Bean。
- 数据最小化的现状：唯一硬约束是 `app.ai.tool-result.max-rows=200 / max-chars=8000`（`ToolResultLimiter`，**模型侧**）；前端帧与台账是全文（双通道分离）。R3 的 20 行/2000 字符是**产品阈值**，比外层兜底更严。

### 2.5 现状一句话判断

**通道齐备、内容面为零、且"写"这一侧今天连 HITL 的位置都没有。**
本专项不是"新增能力"，而是**在既有通道上补一层"表格内容契约"**：读要能看见（但只给采样）、写要先给人看（人点过才落内存）、所有回灌必须过同一把尺寸尺。

---

## 3. 核心设计

### 3.1 工具集总表（6 个：5 主 + 1 撤销）

| # | 工具名 | 风险 | 确认级别 | 副作用 | 回灌形状（恒定部分） | 尺寸闸 |
|---|---|---|---|---|---|---|
| T1 | `read_sheet_summary` | READ | 无 | 只读（不动选区、不滚动） | 列清单+映射 / 规模 / 空值与重复 / 采样行 / **共 N 行** / 版本号 | 采样 ≤ `limit`（硬顶 20），总长 ≤2000 |
| T2 | `aggregate_sheet` | READ | 无 | 只读 | 分组表（≤20 组）+ 总计 + **共 N 组** | ≤20 分组，≤2000 字符 |
| T3 | `inspect_columns` | READ | 无 | 只读（不改文件/定义） | 已匹配 / 缺失 / 多余 / 疑似映射（含理由）+ 计数 | ≤2000 字符 |
| T4 | `review_cells` | READ | 无 | 只读（纯本地校验） | 按字段/规则族聚合计数 + 前 `limit` 条明细 + **共 N 处** | 明细 ≤20 条，≤2000 字符 |
| T5 | `transform_cells` | **WRITE** | **强制人确认**（预览卡：应用/取消） | 应用时改工作簿**内存**值；不落服务端、不保存 | 预演阶段**不回灌**；决策后回灌"已应用 M 格/K 行（用户已确认）+ ≤3 样例"或"用户取消，未改动" | 单批 ≤2000 格且 ≤200 行，**超限拒绝**（不静默截断） |
| T6 | `undo_last_sheet_edit` | WRITE | 无二次确认（回到已知状态） | 回滚最近一次 AI 修改（反向补丁，深度 1） | "已撤销 M 格（回到修改前值）" / "没有可撤销的 AI 修改" | 与 T5 同 |

> 命名与后端 `@Tool(name=...)` **严格同名**（`types/tools.ts:78` 的既有约束）；中文 `displayName` 进 `AiPanel` 工具名表（`AiPanel.vue:1243-1251` 同族）与 `utils/tool-summaries.ts`。

### 3.2 逐工具定义（名称 / 参数 / 副作用 / 确认 / 回灌口径）

#### 3.2.1 `read_sheet_summary`（↔ 调研 P0「数据行可查」，裁剪为"选区摘要"）

- 参数：`defCode`(必填，string)、`region`(`selection|sheet|column`，默认 `selection`；无选区回落 `sheet`)、`columnCode`(region=`column` 时必填)、`limit`(1..20，默认 5)
- 作用面：**当前激活标签页**的 `defCode` 表格；非激活/未挂载 ⇒ `degraded`（提示先打开该标签，不代开）
- 回灌（形如，纯文本、**禁 JSON 块**）：

```
表格：CURRENCY（工作表「币种」）  版本 #12
规模：数据行 1,842 行 × 8 列；选区 B2:D41（40 行 × 3 列）
列（前 8 列 / 共 8 列）：code(编码)、name(名称)、region(地区)…
空值：name 空 3 行；region 空 0 行
重复主键：code 重复 2 组
采样（前 5 行，投影 3 列）：
  第 2 行 | code=CNY | name=人民币 | region=XN
  …
共 1,842 行，本次回 5 行（采样上限 20 行 / 2000 字符）
```

- 数据分级：D2 取样（**首个"业务行可进模型"的通道**）⇒ 必须配 D2 授权记录（见 §8 风险①）+ 配置开关（§7-裁 4）
- 不返回：整列原文、超 20 行、任何未选投影列

#### 3.2.2 `aggregate_sheet`（↔ 调研 P1 聚合；数据最小化的"首选形态"）

- 参数：`groupBy`(columnCode，选填)、`columnCode`(度量列，选填)、`metrics`(`count|distinct|blankRate|min|max`，默认 `["count"]`)、`defCode`
- 回灌：≤20 组的分组表 + 总计 + `共 N 组（本次回 M 组）`；数值聚类只回聚合值，**不回原始行**
- 设计取舍：**默认回答形态是聚合，不是"给我看几行"**（对齐调研 §4.1b）；`read_sheet_summary` 是例外路径且带理由

#### 3.2.3 `inspect_columns`（↔ 调研 P1「导入映射诊断」，裁剪为"表头诊断"）

- 参数：`defCode`
- 回灌：`已匹配 6/8 列` / `缺失：生效日期、备注` / `多余：备注2、_unknown_col9` / `疑似映射（≤5 条）：列「名 称」→ 字段 name（理由：空白差异）；列「REGION」→ region（大小写）`
- 复用既有判据：`readRows().missingHeaders` + `normalizeHeader`（`utils/excel.ts:365-391`）；**只给建议，不改文件、不改定义**
- 与后端 `ExcelReader:89-109` 的"精确匹配 + 静默丢弃"互为补面：这里把"静默"变"可见"

#### 3.2.4 `review_cells`（↔ 调研 P0「预检问题可读」，落在**本地**校验）

- 参数：`defCode`、`scope`(`sheet|selection`，默认 `sheet`)、`limit`(1..20，默认 5)
- 实现：直接复用 `SpreadGrid#validateLocal`（`:341`）→ `validateRows`（`utils/excel.ts:163`），**不出网、不落库**
- 回灌：`按字段聚合：name 必填缺失 4 处、region 枚举非法 1 处；主键重复 2 组` + `前 5 条：第 7 行 name「」必填为空；第 9 行 region「XX」不在可选值…` + `共 12 处`
- 与后端 `start_precheck` 的分工写清：**本地校验 ≠ 服务端预检**（服务端只查三族且全 ERROR，`PrecheckJobRunner`），本工具不替代预检、不声称"能过预检"

#### 3.2.5 `transform_cells`（↔ 调研 P2「修订建议 + 写前预览」，本专项唯一写工具）

- 参数：
  - `defCode`(必填)
  - `target`：`selection`(默认) | `sheet` | `rows` + `rowRange:{from,to}`（**Excel 行号**）
  - `ops`(必填，≤5 条)，每条 `{op, columnCode, …}`，op 白名单：
    `trim` | `upper` | `lower` | `replace`(search,replace) | `regex_replace`(regex,flags,replace) | `fill_down` | `fill_series`(start,step) | `set_value`(value) | `clear`
  - `dryRun`(默认 `true`)：**即使传 `false` 也必须走预览确认**（模型无权关闸；参数保留只为可读性）
- 三阶段（关键：**第一次回灌发生在人决策之后**）：

| 阶段 | 谁做 | 产物 |
|---|---|---|
| 预演 | 前端纯函数 `planTransform(adapter, ops, target)` | 变更集 `{cells:[{r,c,old,new}], skipped:[{r,c,reason}], rows, cellsCount}`，存**浏览器内存**（`planId`），**不发回灌** |
| 人确认 | 前端预览卡（`activePreview`） | 应用 / 取消（可选：逐条勾选，批③） |
| 回灌 | 应用后 / 取消后 | 见下 |

- 回灌（应用成功）：

```
已应用计划 #3（用户已确认）：改动 128 格 / 42 行；跳过 2 格（readOnly 列/校验器拒绝）
操作：trim(code) + replace(name,"XN"→"西安")
样例（前 3 处，Excel 行号）：第 5 行 name「 XN 」→「西安」；第 8 行 name「XN分部」→「西安分部」；…
未保存：改动仅在浏览器内存，需用户点「保存到服务端」才落库；可撤销（undo_last_sheet_edit）
共 128 格
```

- 回灌（用户取消，走 `cancelled=true` ⇒ `FRONTEND_CANCELLED`）：`用户取消了本次表格修改，未改动任何单元格（待改 128 格已丢弃）。`
- **超限拒绝**（>2000 格或 >200 行）：`本次范围 1,240 行 × 8 列 = 9,920 格，超出单批上限（200 行 / 2,000 格），未做任何改动。请缩小到选区或分批（建议先按地区/主键前缀分批），每批确认一次。`
  - 为什么**不自动拆批**：写操作的静默截断 = 少改而不说（调研风险清单的"半批执行毒化历史"）；分批必须由人看到并逐批确认。
- 校验联动：`op` 结果若触发列校验器（枚举/日期/数值），该格记入 `skipped` 并如实计数 —— **不静默写非法值**
- 只读守卫：`isReadOnly` ⇒ `{ok:false, reason:'任务已结束，仅供查看，未修改表格'}`（对齐既有 10 处零写守卫口径）

#### 3.2.6 被裁掉的两个候选（说明为什么"五选"里没有它们）

`apply_formula` / `format_range` 的裁剪不是省事，而是**物理上落不了盘**：导出与保存都走 `buildRowsXlsxBlob`（自产 OOXML、单一样式、仅值）。若硬做，用户会看到"AI 说加了公式/变色了"，下载后打开却是裸值 —— 直接违反本仓"结局不谎报"的红线。若某天要做，前置是**改导出链**（换 ExcelIO 保存会带评估版水印表、后端会当数据表读，见 `SpreadGrid.vue:328-338` 注释与证据 V3），属独立专项，不在本稿。

### 3.3 数据回灌的尺寸闸（单点 + 可断言）

唯一出口函数（`frontend/src/utils/sheet-tools.ts` 新模块）：

```ts
export const SHEET_TOOL_MAX_SAMPLE_ROWS = 20          // R3 硬顶
export const SHEET_TOOL_MAX_RESULT_CHARS = 2000       // R3 硬顶
export const SHEET_TOOL_DEFAULT_SAMPLE_ROWS = 5
export const SHEET_AGGREGATE_MAX_GROUPS = 20
export const SHEET_TRANSFORM_MAX_CELLS = 2000
export const SHEET_TRANSFORM_MAX_ROWS = 200
export const SHEET_PREVIEW_SAMPLE_PAIRS = 3
export const SHEET_UNDO_DEPTH = 1

export function capSheetResult(input: {
  lines: string[]; total: number; returned: number; unit: '行' | '处' | '组' | '格' | '列'
}, limits?: { rows?: number; chars?: number }): { text: string; truncated: boolean; truncatedReason?: 'rows' | 'chars' }
```

规则（每条都配断言，§5 表内有对应用例）：

1. **行数优先于字符数**：先按行截到 20，再按字符截到 2000（沿用最小化调研 T-03 口径）；两者都触发时报最先触发的那一个。
2. **"共 N"恒在末尾**（即便未截断）——`共 1,842 行，本次回 5 行`。
3. 截断必有标记：`…[已截断：原 37 行 / 3,120 字符，已回 20 行]`。
4. 回灌是**纯文本**，禁止 JSON 块、禁止裸对象（对齐 R-166B「工具参数文案去 JSON 人话化」口径）。
5. 工具 **description 里写死"最多 20 行"**，与常量同源（防"描述说 20、实现 200"；最小化调研批 1 验收 #4 的同一要求）。
6. 双通道无需额外处理：本层自限 2000 字符 ≪ 前端帧 64KB 与 `ToolResultLimiter` 的 8000 字符，故**不需要** `truncated` 帧字段。

### 3.4 确认门接法（写工具如何接 HITL 预览-确认）

**采用选项 A：确认落在前端（把 `generative_form` 的通道形态一般化为"人工介入型前端工具"）。**

链路（6 步，每一步都落在既有接缝上）：

```
模型 call transform_cells
  → 后端 @ToolChannel(FRONTEND) 哨兵桩（不执行）
  → kindOf ⇒ KIND_FRONTEND → awaitFrontend：FrontendToolGuard#inspectArguments（新增 SheetTransformGuard）
  → ConfirmGate.awaitFrontendResult：suspendWaitSeconds 命中"人工介入名单" ⇒ 返回 null（帧不带 timeoutSeconds）
  → 帧 frontend_tool_request{name:'transform_cells', args}
  → 前端 stores/ai.ts 分支：name === TRANSFORM_TOOL ⇒ openTransformPreview()（**不进** runFrontendTool、不受 60s 预算）
      → 执行器解析目标 sheet（页面能力 sheet.current）→ planTransform → activePreview = {planId, summary, cells…}
      → AiPanel 渲染预览卡（新分支，形态仿 GF 宿主：part.requiresAction + 专属卡体）
  → 用户点「应用」⇒ applyPlan(plan)（suspendPaint → setValue×N → resumePaint → 1 次 data-changed + bumpVersion）
      点「取消」⇒ submitFrontendToolResult(cancelled=true)
  → 回灌 POST /api/ai/frontend-tool-result → 后端 ConsumeGate → frontend_tool_result 帧 → 模型收尾
```

改动面（小而集中）：

| 层 | 文件 | 改动 |
|---|---|---|
| 后端 | `ai/gate/ConfirmGate.java:322-330` | `GenerativeFormRules.TOOL_NAME` 单值判断 → 集合 `HUMAN_WAIT_FRONTEND_TOOLS = {generative_form, transform_cells, undo_last_sheet_edit}`（语义不变：**等的是人**） |
| 后端 | `ai/tool/SheetTransformGuard.java`（新） | DC-15 扩展点实现：`inspectArguments` 校验 op 白名单/列存在性/范围上限/`dryRun` 不可关闭；`inspectResult` 校验回灌形状（必须是"已应用/已取消"两态之一） |
| 前端 | `stores/ai.ts:2754-2761` | 在 GF 特判旁加同等特判：`TRANSFORM_TOOL` / `UNDO_TOOL` ⇒ 预览站；**并订正 `:2760` 那句"前端工具没有 HITL"注释**（否则口径自相矛盾） |
| 前端 | `stores/ai.ts`（新动作） | `activePreview` 状态（仿 `activeForm:769`）+ `openTransformPreview` + `applyPreview` + `cancelPreview` |
| 前端 | `components/AiPanel/SheetPreviewCard.vue`（新） | 预览卡：将改 N 格/K 行、op 摘要、≤3 样例 old→new、跳过计数、应用/取消 |
| 前端 | `components/AiPanel/AiPanel.vue`（`:507` 旁） | 渲染分支（条件 = `part.kind2==='FRONTEND' && part.name === TRANSFORM_TOOL`） |

**为什么不上后端确认门（选项 B）**：见 §7-裁 1 的对照表 —— 核心两条：① 后端卡片只有模型参数，**看不到 diff**（diff 只在浏览器里算得出，且业务值不能出域给后端做卡）；② 要改 `kindOf`/`PendingToolCall.kind`/续跑结算/前端 kind 三值过滤/`req` 位等 4 个已闭环批次的契约面。

**拒绝语义不新造**：用户取消 = `cancelled=true` ⇒ `FRONTEND_CANCELLED`（`ConfirmGate:91-93` 既有），模型收到 `frontend_tool_result(ok=false)` + 一句"用户取消了…"。

### 3.5 只读守卫、撤销与刷新失效

- **只读守卫**（两处，缺一不可）：① 执行器入口判 `readOnly`（页面能力返回 `{readOnly:true}` 即拒）；② 应用前再判一次（预览卡停留期间用户可能点了"保存并结束"）。理由：`sheet.options.isProtected` 挡得住键盘，**挡不住** `setValue`。
- **撤销**：应用前把 `{r,c,old}` 存为反向补丁（深度 1，`planId` 绑定），`undo_last_sheet_edit` 应用之；无补丁 ⇒ 如实回"没有可撤销的 AI 修改"。不依赖 SpreadJS 内置 `UndoManager`（仓内零启用、未取证）。
- **刷新失效（与 L0 的硬交点）**：预览计划活在前端内存，刷新即丢。`restoreSuspendedSnapshot` 会给"请求帧已发、仍 PENDING"的条目重建卡（L0 裁决），于是刷新后会出现一张**点不动的待决卡**（该工具**无超时**）。收口：恢复路径识别"人工介入型前端工具 **且** 本地无对应 plan" ⇒ 渲染"预览已失效（页面刷新导致）"卡 + 唯一按钮**放弃**（提交 `cancelled=true`），文案要求模型重新发起。**此条必须与批② 同批测**（§5 T-17）。

### 3.6 执行落点与可测性（不把 SpreadJS 拖进单测）

- **新模块** `frontend/src/utils/sheet-tools.ts`：
  - `SheetAdapter` 窄接口：`{ rowCount(), colCount(), header(col), getCell(r,c), setCell(r,c,v), version() }`（行列一律**零基**，对外文案转 `excelRowNumber`）
  - `planTransform(adapter, target, ops)`：**纯函数**，无 SpreadJS 依赖 ⇒ vitest 注入 fake adapter（1 万格也毫秒级）
  - `summarize/aggregate/inspect/review` 的纯函数体 + `capSheetResult`
- **`SpreadGrid.vue` 追加一条 expose**：`getSheetAdapter(): SheetAdapter`（内部包 SpreadJS 调用；写路径自带 `suspendPaint/resumePaint` 与 `restoring` 抑制策略）
- **页面能力**（路由 4，两页共注册）：`sheet.current` 返回 `{defCode, readOnly, adapter, fields}`；由 `ExportWizardView`（`registerHandlers:1237` 内）与 `ImportWizardView`（`:1051-1141` 同族）各自注册，卸载时 `unregister`（对齐 `HANDLER_NAMES` 的既有收口模式）
- **登记工具**（路由 1）：6 个工具都进 `FRONTEND_TOOLS`，执行器统一为"取 `sheet.current` → 判 readOnly → 纯函数 → `capSheetResult` → `makeExecution`"，从而**白拿**参数校验、scope 校验、三级兜底与统一预算（读工具用 60s 预算，够了）

---

## 4. 契约变更

### 4.1 SSE 帧

- **无新帧类型、无字段变更**。`frontend_tool_request.args`（JSON 字符串）承载新工具参数；`timeoutSeconds`/`expiresAt` 对人工介入型工具**缺省**（既有语义："不带 = 无上限"，`types/sse.ts:290-296`）。
- `frontend_tool_result` 帧沿用 `outcome/code/message`（`types/sse.ts:313-326`）；取消走既有 `FRONTEND_CANCELLED`。

### 4.2 回灌协议

- `POST /api/ai/frontend-tool-result` 请求体**不变**（`result` 文本 + `cancelled` + `outcome`）。
- 新增**约定**（不是 schema）：`result` 必含"共 N …"尾行；写工具的成功文本必含"用户已确认"与"未保存/可撤销"两句（可断言）。

### 4.3 前端类型落点

| 文件 | 变更 |
|---|---|
| `types/tools.ts:79` | `FrontendToolName` 联合扩 6 名；`FRONTEND_TOOLS:82-107` 扩 6 条（`displayName/description/riskLevel/scopePatterns/args`） |
| `types/tools.ts` | 新增 `SHEET_TOOL_NAMES`（供 store 特判导入，避免字符串散落） |
| `types/ai.ts` | 新增 `SheetPreviewState`（`planId/defCode/summary/cellsCount/rowsCount/skippedCount/samples/status:'pending'|'applying'`）；`ToolPart` 可选 `previewSummary?: string` |
| `stores/ai.ts:769` 旁 | `activePreview: null as SheetPreviewState | null` + 4 个动作 |
| `utils/parts.ts` | 新工具名的卡面标题/状态表 + `localTerminal` 覆盖（避免与 `frontend_tool_result` 帧互相翻写） |
| `utils/tool-summaries.ts` | 6 个工具的中文摘要模板 |
| `components/AiPanel/AiPanel.vue:1243-1251` 同族 | 中文名表补 6 条（`sheet.*` 能力名同样补） |

### 4.4 后端落点

| 文件 | 变更 | 注意 |
|---|---|---|
| `ai/tools/AiTools.java` | +6 `@Tool` + `@ToolScope` + `@ToolRisk` + `@ToolChannel(FRONTEND)`，方法体 `return FRONTEND_STUB;` | 工具数 18 → 24；描述里写死"最多 20 行" |
| `ai/gate/ConfirmGate.java:322-330` | 名字集合扩项 | 语义不变；注释里"只有 generative_form"须同改 |
| `ai/tool/SheetTransformGuard.java`（新） | DC-15 扩展点实现 | 挂起机制零改动（Bean 自认领） |
| `ai/tool/ToolRegistryDisclosureTest.java:56` | `FRONTEND_TOOLS` 名单扩 6 | 通道/风险断言自动覆盖；工具计数快照同类断言一并改 |
| `application.yml` | 不改 | 无新配置键（开关属 §7-裁 4，若裁"要开关"则挂在既有 `app.ai.*` 下并同批补文档） |

`@ToolScope` 建议值：读工具 `{"task:EXPORT/EXPORT","task:IMPORT/UPLOAD"}`；`transform_cells`/`undo` 同集合（步骤 key 取自 `types/task.ts:37-41`，与后端标签一字对齐）。

---

## 5. 测试矩阵（前端 vitest 为主 + E2E 面）

维度：参数校验 / scope / 尺寸闸 / 投影与脱敏 / 不越权（不写服务端、不越只读）/ 预览-确认-取消 / 幂等 / 失效恢复 / 契约终态。

| ID | 用例 | 维度 | 期望 | 落点 | 证红（去掉实现怎么红） |
|---|---|---|---|---|---|
| T-01 | 25 行数据 + `limit=20` | 尺寸闸 | 回 20 行 + `共 25 行，本次回 20 行` | `tests/sheet-tools-read.spec.ts`（新） | 不设硬顶 ⇒ 回 25 行 ⇒ 红 |
| T-02 | `limit=50`（越界） | 参数闸 | 参数非法拒绝（`ok:false`，人话，无 JSON） | 同上 | 放开上限 ⇒ 回 50 行 ⇒ 红 |
| T-03 | 20 行但 5,000 字符 | 尺寸闸 | 先按行再按字符截断，标记含 `原 20 行 / 5,000 字符` | 同上 | 只判行数 ⇒ 超 2000 ⇒ 红 |
| T-04 | 未截断场景 | 口径 | 末尾仍带 `共 N 行` | 同上 | 只在截断时加 ⇒ 红 |
| T-05 | 3 列投影但表有 12 列 | 投影 | 只回 3 列 + `另有 9 列未显示` | 同上 | 全列回灌 ⇒ 红（字数/负断言） |
| T-06 | 种子词表负断言：模型侧文本不含第 21 行起的字段值 | 脱敏 | 断言不含 | 同上 | 截断失效 ⇒ 红 |
| T-07 | 表头被改名 → `inspect_columns` | 诊断 | 回"疑似映射（列「名 称」→ name，理由：空白差异）"而非"缺 1 列" | 同上 | 只回 missing ⇒ 红 |
| T-08 | 5 处必填缺失 + 2 组主键重复 | 聚合口径 | `按字段聚合 + 前 5 条 + 共 7 处` | 同上 | 逐条回 7 条不聚合 ⇒ 红（条数断言） |
| T-09 | `aggregate_sheet` 37 组 | 尺寸闸 | 回 20 组 + `共 37 组` | 同上 | 回 37 组 ⇒ 红 |
| T-10 | 页面能力未注册（未在向导页） | 三级兜底 | `degraded` + "请先打开导出结果页/上传页" 的可行动文案 | `tests/sheet-tools-registry.spec.ts`（新） | 抛异常 ⇒ 红（约定不 reject） |
| T-11 | `transform_cells` 预演 | **不回灌** | 预览态建立、`submitFrontendToolResult` **零调用** | `tests/sheet-tools-transform.spec.ts`（新） | 预演就回灌 ⇒ 红（spy 计数） |
| T-12 | 点"应用" | 写 | fake adapter 的 `setCell` 被调用 N 次；回灌文本含"已应用…（用户已确认）…未保存" | 同上 | 不应用就回成功 ⇒ 红 |
| T-13 | 点"取消" | 拒绝 | `setCell` **零调用**；回灌 `cancelled=true`；文本"未改动任何单元格" | 同上 | 取消仍写 ⇒ 红；回 `ok` ⇒ 红 |
| T-14 | 超限（9,920 格） | 写闸 | **拒绝**并给分批建议；`setCell` 零调用 | 同上 | 自动截断成 2000 格 ⇒ 红 |
| T-15 | 只读壳（`readOnly:true`） | 不越权 | `{ok:false, reason:'任务已结束，仅供查看…'}`；`setCell` 零调用 | 同上 | 只靠 `isProtected` ⇒ fake adapter 照样写 ⇒ 红 |
| T-16 | 撤销：应用 → `undo_last_sheet_edit` | 可核对 | `setCell` 回原值；再撤一次 → "没有可撤销的 AI 修改" | 同上 | 不存反向补丁 ⇒ 红 |
| T-17 | 刷新后恢复：无本地 plan 的 PENDING 条目 | 失效 | 渲染"预览已失效"卡 + 放弃按钮提交 `cancelled=true`；**不**渲染可点"应用" | `tests/ai-panel-parts-render.spec.ts`（扩）+ `sheet-tools-transform.spec.ts` | 沿用 L0 建卡 ⇒ 渲染点不动的应用钮 ⇒ 红 |
| T-18 | 6 个工具的描述与常量同源 | 防漂移 | 每个 `description` 含"20"；常量亦为 20 | `tests/sheet-tools-registry.spec.ts` | 描述写 200 ⇒ 红 |
| T-19 | `FRONTEND_TOOLS` 名单快照 | 注册表 | 2 → 8，且 name/risk/scope 与后端名单一致 | 同上 + 后端 `ToolRegistryDisclosureTest` | 少登记 ⇒ 路由落到"未知前端工具" ⇒ 红 |
| T-20 | 后端：`transform_cells` 帧不带 `timeoutSeconds` | 无时限 | `suspendWaitSeconds` 返回 null；`other` 工具仍返回 120 | 后端 `ConfirmGate*Test`（扩） | 名单未扩 ⇒ 帧带 120s ⇒ 红 |
| T-21 | 后端：`SheetTransformGuard` 拒绝非法 op | 入参闸 | 不挂起、不发帧、结局 `REJECTED_ARGUMENTS` + 原因清单 | 后端 `SheetTransformGuardTest`（新） | 去掉 Guard ⇒ 非法 op 下发前端 ⇒ 红 |
| T-22 | E2E 主用例（+1~2 条，主计数 29 → 30/31） | 端到端 | "这个表 XN 地区几行？" → 摘要/聚合卡 → "把 name 里的 XN 换成 西安" → 预览卡 → 应用 → 回灌"已应用" | `scripts/verify-e2e.ps1`（+ 同批改汇总文案，看板 `:360` 已挂账） | 无预览卡 ⇒ 断言失败 ⇒ 红 |

回归面（必须全绿才算收口）：`tests/frontend-tool-outcome.spec.ts`、`frontend-tool-result-terminal.spec.ts`、`restore-request-emitted.spec.ts`、`restore-reconcile.spec.ts`、`parts-reducer/double-write/residue-cleanup`、`export-wizard-step-outcome.spec.ts`、`export-wizard-batch-download.spec.ts`、`tools-and-deadline.spec.ts`；后端 `ToolRegistryDisclosureTest`、`ConfirmGate*`、`ResilientChatService*`。

---

## 6. 冲突面（与队列内专项的交点）

| 专项 | 交点 | 处置（本稿口径） |
|---|---|---|
| **i18n + 工具可配置（批③ 工具定义外置）** | 新增 6 个工具 = 6 条 `name/description/param description` 要进 YAML/DB 首批（18 → 24）；i18n 批③ 的 `toolKey` 帧字段与 `displayName` 下发改动会与"工具卡中文名表"（`AiPanel.vue:1243+`）同日改 | 本专项的工具描述写成**模块内常量**（不散落字符串），i18n 批③ 直接搬；若 i18n 批③ 先落，本专项只加数据行；**两者不得并行**（同改 `AiTools.java` 与工具清单快照） |
| **模型配置（批③ 面板选择器 + 「AI 设置」页）** | 唯一硬碰撞文件 = `AiPanel.vue`（面板底栏 vs 消息流内预览卡）与设置页 | 本专项**不在 composer 加任何控件**（预览卡只在消息流）；若裁"要开关"，开关落设置页新段，与批② 设置页同批提交，避免两次改同一文件 |
| **上下文统计与压缩（L1 清工具结果）** | L1 清掉上一轮工具结果后，模型"忘掉"采样行 | ① 本专项所有读数工具**参数可复述**（同 `defCode/region/limit` 可重放，服务端数据面）；② 屏幕面数据**会变**（用户手改）⇒ 回灌带 `版本 #N`（取自 `workspace.bumpVersion`，`ExportWizardView.vue:858`），模型见版本变化即知摘要过期，**不得据旧摘要断言当前值**（写进工具描述） |
| **B23L1 多实例与资源治理** | AI 不写服务端 ⇒ 无任务级/作业级资源租约需求；但等待期属 ConfirmGate 路径 | 直接复用批② 已落的"等待方持租 + 读侧续期"；本专项零新增 Redis 键 |
| **L0 刷新恢复（已闭环 `503bd4f`）** | 恢复路径按"PENDING + req=true"建卡；本专项引入**无超时**的待决卡，刷新后可能永久点不动 | §3.5 的"预览已失效 + 放弃"收口；`restoreSuspendedSnapshot` 需识别工具名（与 L0 的 A/B/C/D 裁决不冲突，只加一个分支） |
| 导出向导既有零写口径（`isReadOnly` 10 处守卫） | AI 写可能被当成"绕过零写" | §3.5 双处守卫 + T-15 用例；口径写进工具描述 |

---

## 7. 待裁选项（A/B + 建议）

**裁 1 · 确认落点**

| | A 前端内确认（把 `generative_form` 形态一般化） | B 后端确认门 + 前端二次下发 |
|---|---|---|
| 改动 | 后端 1 处名字集合 + 前端 store/AiPanel | 改 `kindOf` 二维（通道×风险）+ `PendingToolCall.kind` 语义 + `awaitConfirm` 放行后转 `awaitFrontend` 二次挂起 + 续跑结算（`resolvePending`）+ 前端 kind 三值过滤 + L0 `req` 位 |
| 卡片能否看到 diff | **能**（diff 在浏览器算，卡在浏览器画） | **不能**（后端只有模型参数；业务值不能为出卡而回传后端） |
| 风险 | HITL 出现两个落点（后端门 / 前端门），需在文档与卡面统一形态 | 触碰 4 个已闭环批次的契约面，回归面大 |
| 建议 | **采用 A** | 留作批③（若将来要求"AI 写操作必须有服务端审计门"） |

**裁 2 · 写工具形态**

- A1（建议）：**单工具内部挂起**（`transform_cells` 预演→人确认→应用，模型只看到一次调用）。
- A2：拆成 `preview_transform`（只读，回 diff 摘要 + planId）+ `apply_plan(planId)`（写）。优点：模型可据摘要改参重来；缺点：多一次工具往返 + 模型有"跳过预览直接 apply"的路径（需再加固）。
- 建议 A1：与业界"一次计划、一次批准"一致，且**模型无法绕过确认**。

**裁 3 · 公式与格式**

- 建议：**都不做**（§3.2.6 的落盘证据）。若用户坚持，须先立"导出链改造"专项（换 OOXML 产物形态），本专项不承接。

**裁 4 · 开关默认值**

- 读（D2 取样）：建议**默认开** + 硬闸 20 行/2000 字符 + 可按环境关（关后工具直接 `unavailable`）。
- 写：建议**默认关**（按环境开启），对齐 Airtable/模型配置专项"管理员可整体关 AI"的取舍。
- 落点：`application.yml` 的 `app.ai.sheet.*`（若裁"要开关"），开关名与文案进 i18n 批③。

**裁 5 · 作用面**

- 建议：只作用于**当前激活标签页**的表格；跨 defCode 批量 = 模型逐 `defCode` 调用（每次一次确认）。理由：一次确认只覆盖一张表，用户才不会"批准了没看见的那张"。

---

## 8. 风险与边界（诚实登记）

1. **数据出仓面首次打开**：`read_sheet_summary` 是本仓**第一个**把业务行送进模型的通道（此前 18 工具全是 D0/D1）。缓解：R3 硬闸 + 投影 + 版本号 + 配置开关；**前置流程件** = 一份 D2 授权记录（谁批准、范围、字段 allowlist），按 R8 要求随批① 归档。未附该记录前，批① 不得合并。
2. **预览态不可持久**：刷新/换会话即失效；已按 §3.5 收口，但"用户填到一半刷新"是**必然发生**的（与 GF 表单同族），文案必须直说"预览已失效，请让 AI 重新发起"，不许假装还活着。
3. **无超时的代价**：写工具卡可无限期待决（与既有确认门一致）。**已知过度保守**方向安全；但"永远不被清理"的待决卡需靠用户放弃或停止本轮（`POST /api/ai/cancel`）收尾。
4. **屏幕数据非权威**：AI 改的是浏览器内存，用户可能不保存就关页面 / 或先下载未保存版本（`buildXlsx` 用的是 grid 当前内容，`:866`）。回灌必须写明"未保存"；不得出现"已保存到服务端"字样。
5. **`isProtected` 不是安全边界**：程序化写入可绕过，故只读守卫必须在前端执行器与后端 Guard（回灌复核）两层。
6. **性能**：2000 格 setValue 需 `suspendPaint/resumePaint` 且**抑制逐格事件**（否则 2000 次 `data-changed` → 2000 次 `bumpVersion`）。未抑制 = UI 卡顿（登记为可断言项：T-12 断言 `data-changed` 恰好 1 次）。
7. **列映射仍是精确匹配**：`inspect_columns` 只给建议，不改 `ExcelReader`；"AI 自动映射并改文件"越界（调研 P1 明确"只给建议"）。
8. **评估版水印**：`VITE_SPREADJS_KEY` 留空时导出链自产 OOXML 已规避（`SpreadGrid.vue:328-338`），但若将来改用 ExcelIO 保存会引入水印表——本专项不碰导出链。
9. **不承诺跨轮一致**：与"服务端数据工具幂等"不同，屏幕摘要会因人工编辑而变（版本号是唯一可观测信号）。
10. **看板/索引未落行**：`docs/任务看板.md` 10-21 行尚无本专项；`docs/research/README.md` 索引也未收录本批三件（两道调研 + 本稿）。落盘时需补 1 行看板 + 索引 3~4 行（**本 plan 不改既有文件**）。

---

## 9. 验收口径

1. **门禁**：前端 `vitest` 全绿（基数按落盘时实测，当前登记为 512/512 · 26 文件）+ `typecheck` 0 + `build` ✓ + `check-docs` 6/6 + 脱敏 0 命中；后端 `mvn test` 全绿（当前 702/702 · 112 suite）。
2. **三句话走通**（实机，非单测）：
   ① "这任务里 CURRENCY 有多少行 / XN 地区几行？" → 聚合或摘要回"共 N 行"，且**不含**未投影列原文；
   ② "这几行为什么不合规？" → `review_cells` 回聚合 + ≥1 条可定位明细（Excel 行号 + 字段 + 原因）；
   ③ "把 name 里的 XN 改成 西安" → 预览卡出现（M 格/K 行 + 样例）→ 点应用 → 回"已应用…未保存"；再点"取消"的一次 → 回"未改动任何单元格"且**表格像素级不变**。
3. **两层上限都可断言**：应用层 R3（≤20 行/≤2000 字符/必回"共 N"）有单测；通道层仍由 `ToolResultLimiter`（200/8000）兜底。
4. **不越权**：拒绝即零写（adapter `setCell` 调用数 = 0）；只读壳下写工具零写；`config_data_rows` 版本与暂存区行数在任何 AI 表格操作后**均不变**（对账断言）。
5. **契约不扩**：`git diff` 上 SSE 帧类型集不变（15 种不变）、`POST /api/ai/frontend-tool-result` 请求体不变。
6. **E2E**：主计数 29 → 30/31，`verify-e2e.ps1` 与汇总文案同批对齐（既有挂账 `:360`）。
7. **文档同批**：`stores/ai.ts:2760` 的"前端工具没有 HITL"注释、`frontend-tools.ts` 文件头"7 个 FRONTEND / 2 个登记"的口径、`ConfirmGate` 类注释"只有 generative_form 无上限" —— **三处注释必须同批订正**（否则代码与注释自相矛盾）。

---

## 10. 分批切分（施工顺序）

> 每批独立"施工 → 复审 → 收口 → 复核 → 提交"，批批可回滚；**批① 未附 D2 授权记录不得合并**。

### 批① 只读看得见（4 工具 + 尺寸闸）— 零写、零新 UI

- 范围：`read_sheet_summary` / `aggregate_sheet` / `inspect_columns` / `review_cells`；`utils/sheet-tools.ts`（适配器 + 纯函数 + `capSheetResult`）；`SpreadGrid#getSheetAdapter`；两页 `sheet.current` handler；6?（本批 4）条 `FRONTEND_TOOLS` 登记 + 后端 4 个哨兵桩 + 名单/scope 测试；D2 授权记录归档。
- 范围外：任何写入、任何新组件、任何后端状态机改动。
- 验收 = §9 的 1/2-①②/3/5；测试 = T-01…T-10、T-18、T-19。

### 批② 写操作预览确认（`transform_cells` + 撤销 + 确认接法）

- 范围：`transform_cells` / `undo_last_sheet_edit`；`ConfirmGate.suspendWaitSeconds` 名字集合扩项；`SheetTransformGuard`；store `activePreview`（4 动作）+ `SheetPreviewCard.vue` + AiPanel 分支；只读双守卫；`data-changed` 单次抑制；刷新失效收口。
- 依赖：批① 的适配器与尺寸闸（不得并行）。
- 验收 = §9 全量（含 2-③）；测试 = T-11…T-17、T-20、T-21、T-22。

### 批③ 可选增强

- 预览卡逐条勾选（部分应用）；`transform_cells` 的"另存为新版本对比"（不做落库）；开关入「AI 设置」页（与模型配置批② 同批）；公式/格式**若**用户另裁则单独立项（§7-裁 3）；E2E 补"聚合 → 追问明细 → 修订"三段式主用例。
- 明确不排期：图表/透视、整表进模型、schema 反推。

---

## 附 A · 本稿读过的仓内锚点（便于复核）

| 面 | 文件:行 |
|---|---|
| SpreadJS 依赖与版本 | `frontend/package.json:16-18` |
| 引擎懒加载 / culture / LicenseKey | `frontend/src/utils/spreadjs.ts:45-66`；`frontend/.env.example:9-10` |
| 表格宿主与暴露 API | `frontend/src/components/SpreadGrid.vue:141-188, 215-224, 302-362` |
| 版式与读写口径 | `frontend/src/utils/excel.ts:221-343, 361-412` |
| **导出链只写值、单一样式**（裁公式/格式的依据） | `frontend/src/utils/excel.ts:540-636`（styles `:610-616`；cell `<v>` `:567-570`） |
| 导出页结果表格与 AI 打开编辑器 | `frontend/src/views/ExportWizardView.vue:821-849, 855-863, 1054-1082, 1323-1360` |
| 导入页表格与 handler | `frontend/src/views/ImportWizardView.vue:68, 622, 1051-1143` |
| 前端工具四路由 / 预算 / 结局工厂 | `frontend/src/utils/frontend-tools.ts:100-116, 278-291, 358-365, 484-508` |
| 登记工具只有 2 个 | `frontend/src/types/tools.ts:79-107` |
| `frontend_tool_request` 分支与"前端工具没有 HITL" | `frontend/src/stores/ai.ts:2739-2762, 3059-3118` |
| GF 的无时限 + store 特判（本设计样板） | `stores/ai.ts:2127-2206`；`backend/.../gate/ConfirmGate.java:322-330` |
| **通道优先于风险**（前端工具进不了确认门） | `backend/.../gate/SpToolCallingManager.java:982-990, 325-329, 557-564, 603-611` |
| 前端工具两道闸门扩展点 | `backend/.../ai/tool/FrontendToolGuard.java:30-93` |
| 取消终态 `FRONTEND_CANCELLED` | `backend/.../gate/ConfirmGate.java:91-93`；`ai/tool/ToolOutcome.java:37,71` |
| 工具清单/通道断言 | `backend/src/test/java/.../tools/ToolRegistryDisclosureTest.java:56` |
| 页面能力注册表 | `frontend/src/stores/workspace.ts:269-282` |
| 步骤 key | `frontend/src/types/task.ts:37-41` |
| E2E 主计数 29 | `scripts/verify-e2e.ps1:2, 41-46` |

## 附 B · 落盘说明

- 目标路径：`docs/research/设计-AI表格操作专项-2026-10-21.md`（**新文件**，不覆盖任何既有件）。
- 同批建议补（非本 plan 权限）：`docs/research/README.md` 索引追加 3–4 行（本批两道调研 + 本稿，序号接第 36 件 → 37/38/39，按实际落盘顺序）；`docs/任务看板.md` 10-21 行加本专项 1 行（状态 📋，依据列指向本稿）。
- 本稿 §7 四项待裁**未裁**，属设计稿、**不构成施工承诺**。

---

## 交付说明（给主智能体）

- **写文件权限**：本 plan profile 的活动工具集为 `Read/ReadMediaFile/Glob/Grep/WebSearch/FetchURL/NotifyUser`，**无 Write/Edit/Bash**，故未能落盘；上面 §0–§10 + 附 A/B 即完整正文，可直接原样写入目标路径。
- **用户口径核实结论**：与仓内一致 —— SpreadJS 17.1.5 是**真实依赖**并在导出结果页/导入上传页实装；但有两处需上级知悉：**License 为评估模式（key 仓内留空）**、**没有独立"在线表格"页**（AI 表格操作的作用面 = 那两个向导位）。
- **本设计的三处硬事实支撑**（供复核直接复验）：① `store:2760` "前端工具没有 HITL" + `SpToolCallingManager:982` 通道优先 ⇒ 写工具必须新开前端 HITL；② `excel.ts:610-616/567-570` 导出链只有值+单一样式 ⇒ `apply_formula`/`format_range` 必须裁；③ `ConfirmGate:326` 只有一个名字享"无时限" ⇒ 写工具需扩名单，否则 120s 内人来不及点。
- **自证三值**：model = `DeepSeek/deepseek-flash`、profile = `plan`、thinking = `high`（`agent-1339/wire.jsonl` 第 5 行；全文件 `"type":"profile.bind"` 命中 1 次）。
