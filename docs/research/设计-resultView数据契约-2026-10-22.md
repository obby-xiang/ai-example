# 设计 · resultView 数据契约（后端确定性构造 + 前端白名单渲染）

- 日期：2026-10-22
- 范围：工具结果**展示层**的载荷契约 —— 后端如何把一次工具执行的产出，以**受控白名单视图**下发给前端；前端如何用**一个通用渲染器**替掉"每工具一张专用卡"。不含工具本身的语义、不含新帧类型、不含渲染视觉口径（视觉仍归 demo 定稿）
- 依据件：
  - 本批业界调研结论（2026-10-22，六家一手规范：A2UI / Adaptive Cards / JSON Forms / OpenAI Apps SDK / LangChain / Vercel AI SDK），结论要点见 §2
  - `docs/research/调研-AI对话组件-业界全景-2026-10.md`（§① C 生成式 UI 矩阵、§② 共识「宿主持可信组件目录、agent 只传数据」、源 S8 A2UI / S22 Apps SDK）
  - `docs/research/外部设计挖掘与借鉴报告.md` §2.1–§2.3（A2UI catalog 与逐组件 JSON Schema 注入的**源码取证**；R2-1「不采纳生成式 UI 渲染层」、R2-2「采纳 catalog 白名单思想」）
  - `docs/research/调研-AI上下文数据最小化业界惯例-2026-10-21.md` §4.2（**R3** 单次工具结果 ≤20 行 / ≤2000 字符 / 必回「共 N」；**R4** 双通道同源；**R8** 出域闸）
  - `docs/research/设计-AI表格操作专项-2026-10-21.md`（预览三档、尺寸闸单点、`FieldQueryCondition` 复用范式 —— 本稿"不新造 DSL、复用既有类型"的体例来源）
  - `docs/research/设计-上下文统计与压缩专项-2026-10-21.md` §3.5（`done` 帧 additive 键先例；15→16 帧扩张的同步面清单）
  - `docs/research/设计-i18n与工具可配置专项-2026-10-21.md`（`error.code` 稳定化、文案 key 化、双受众字符串必须拆 `key/args` 与 `modelSummary`）
- 体例参照：`docs/research/设计-AI表格操作专项-2026-10-21.md`、`docs/research/设计-AI可视可操作专项-2026-10-21.md`
- 性质：**设计稿，不构成施工承诺**。§8.2 八项开放问题未裁前不进施工；S6 批 2 仍待发令
- **裁决状态（2026-10-22）**：§8.2 开放问题经主会话按业界主流**代裁**（用户授权原话「你参考业界主流的，做最佳裁决」）⇒ 本稿由「草案待裁」转为 **S6 批 2 施工依据**；S6 批 2 自身的开工令仍待用户/指挥官发。逐条裁决行落在 §6.1（帧载体）、§7.1（迁移五步）、§8.2（O1/O2/O6 逐条 + 其余项维持「本稿倾向」）—— **原文一律保留、裁决行下加**
- 已定裁决（设计前提，不得在施工中推翻）：
  1. 用户 2026-10-22 裁「**按业界主流方案做**」（`docs/决策看板.md` 10-22「三项『按业界主流』立项口径裁决」行）；
  2. 契约口径 = **白名单 + 数据引用 + 版本降级**（同批落板：渲染面只认白名单视图键、工具产出走引用不回灌正文、视图版本不兼容时降级而非报错）；
  3. 本稿是 S6 批 2「后端 `resultView` 双通道 + 前端通用渲染器」的**开工前置件**（`docs/任务看板.md:80`、`:394`；`docs/决策看板.md:128`）。

## 0. 结论速览（十条硬口径）

1. **现状是"特判集合 + 文本反解"，不是"视图契约"**：`AiPanel.vue` 现存 6 条按 `kind2` / 工具名的 `v-if` 分支（已决 HITL 只读卡 `:376`、发布冲突表 `:492`、确认块 `:515`、GF 特判 `:570`、表格写预览 `:2528`、上传交接 `:2535`），再加 2 条**文本反解**（`blocks.ts:199 argRows` 猜 args 的键、`:556 parsePublishConflicts` 正则拆 reason）。**没有任何"数据 → 视图"的中间层**，工具一改文案即静默失配。
2. **`resultView` 在代码侧全仓零命中**（`grep resultView backend/src frontend/src` = 0）⇒ 本契约是**纯新增面**，不存在"改既有契约"的兼容包袱，但也意味着渲染器、类型、落段、恢复四条链路要一次建齐。
3. **契约骨架**（§3）：`{version, id, revision, kind: table|chart|form|text, title, description, status, data:{ref}|{inline}, columns[], chart{}, form{}, actions[], empty, error, a11y, fallback, meta.catalogId}`；列类型/图表类型/动作类型**三个封闭枚举**，未知值一律降级不报错。
4. **数据单份**：一份行数据在帧内**只出现一次** —— 要么在 `result`（`result` 本身是 JSON 文档，`data.ref` 用 RFC6901 指针指它，缺省 `/` = 整份），要么在 `data.inline`（`result` 是人话文本时）。`columns` 只描述、不携带值。**禁止两处都有**。
5. **禁自由 HTML / 禁任意代码**：渲染器不 `v-html`、不做表达式求值、不认 CSS 类名与模板串；`title`/`description`/`label` 一律按纯文本转义，且不走 markdown 渲染（否则等于给工具结果开第二条 HTML 注入面）。
6. **`status` 与 `outcome` 是两把尺**：`outcome`（八态，`types/sse.ts:32`）答"工具是否执行成功"；`status`（`ok|empty|partial|error`）答"这份视图的内容完整度"。截断 = `status:"partial"` + `meta.truncated{shownRows,totalRows}`，**不叫失败**。
7. **版本 major-only**：major 不同 = 不兼容 ⇒ 走 `fallback`（不白屏、不报错）；minor 只允许**新增可选字段**。而 `fallback` 是**必填**，不是可选 —— 这是 Adaptive Cards 与本契约唯一强制对齐的一条。
8. **规模上限 ≤200 行 / ≤50 列 / ≤256KB**（调研建议值），但**256KB 与现状单帧 64KB 是两把尺**（`application.yml:308 max-bytes: 65536`）⇒ v1 实际生效 = min(契约上限, 帧预算)，且 `resultView` **不可依赖帧级截断兜底**（`FrameSizeLimiter` 只截 `text`/`result`，见 §6.3-3）。该冲突**大概率已由 G5 双通道对齐批（在途、未提交）以 `app.ai.result-budget.*` 统一** —— 本契约须在该批合并后复核键名与截断词汇（§6.3-5、§8.2-O1）。
9. **发布冲突 reason 的承载方案 = 依赖倒置**（§5）：卡面**不再反解 reason**，明细改由 `resultView.kind=table` 承载；于是「reason 是否只留一句」（`docs/决策看板.md:127` 的「待裁」行）从"要同时改 UI 与模型"降为**纯模型侧决策** —— 这就是 P1-4「拆双通道」落进本契约的唯一方式。
10. **帧类型集仍是 16**：`resultView` 走 `tool_result` 帧的 **additive 可选键**（首选）或 `done` 帧的同族键（备选），不新增帧名（§6）。**`done.extra` 是 Java 侧容器名，线上不是嵌套对象** —— `SseChatEmitter#done` 用 `frame.putAll(extra)` 扁平化（`:907-909`），所以线上出的是 `done.resultView` 而不是 `done.extra.resultView`。

---

## 1. 背景与问题

### 1.1 现状 = 每工具一张专用卡（读码实证）

| # | 形态 | 落点 | 判据来源 |
|---|---|---|---|
| 1 | 普通工具卡（参数表 + 结果 + 原因行） | `AiPanel.vue:428-540` | `blocks.ts:199 argRows`（args 按字段名反推 label）、`:149 readableValue`（值可读化） |
| 2 | 已决 HITL 只读卡（CONFIRM / GF 原地切只读） | `AiPanel.vue:376-427` | `blocks.ts:315 isReadOnlyCard` |
| 3 | 发布冲突明细表（3 列：配置 / 行键 / 原因） | `AiPanel.vue:492-512` | `blocks.ts:556 parsePublishConflicts` **正则反解 reason** |
| 4 | 确认门待批块 | `AiPanel.vue:515-570` | `kind2 === 'CONFIRM' && status === 'pending'` |
| 5 | 生成式表单（FormRenderer 宿主） | `AiPanel.vue:570` | `name === GENERATIVE_FORM_TOOL` 特判 |
| 6 | 表格写操作预览卡 | `AiPanel.vue:2528` `isSheetPreviewPart` | `SHEET_PREVIEW_TOOLS` 名单 |
| 7 | 上传交接卡 | `AiPanel.vue:2535` `isUploadHandoffPart` | `UI_HANDOFF_TOOLS` 名单 |

后端工具数已到 **33 个 `@Tool`**（`AiTools.java`：基础 18 + 表格 8 + 可视可操作 7）。每新增一种结果形态，代价是"一个 `v-if` 分支 + 一套 Tailwind 类 + 一组断言"，而**分支之间没有任何共享的载荷约定** —— 这是本契约要收口的东西。

### 1.2 三处硬缺口

- **缺口 A（载荷无语义）**：工具结果的真值是 `result: string`（`types/sse.ts:224`）。卡面要靠正则或字段名去"猜"里面是什么（`parsePublishConflicts` 的 `^\s*\d+\.\s*(.+?)#(.+?)[：:](.+)$`）。**文案一改、冒号由全角变半角、`message` 里出现 `#` 或换行，解析即静默退化成纯文本** —— 这不是实现 bug，是"借文本当协议"的必然结果。
- **缺口 B（形态不可迁移）**：没有任何"数据 → 视图"的中间层。所以"给某工具加一列""把某卡从表改成图"这类需求，在今天是**改渲染代码**而不是**改载荷**；也没有"同一个结果在窄栏/宽栏/深色下换形态"的余地。
- **缺口 C（上限与需求对冲）**：展示想多放，模型要少看，而两者今天**挤在同一个 `result` 字符串里**。仓内其实已经承认了这个张力 —— `application.yml:281-286` 白纸黑字："只约束**给模型看的**那一份：前端 `tool_result` 帧与 Redis 台账始终是全文"。**双通道架构早已存在，缺的是"给 UI 的那一份"自己没有结构化载体**。

### 1.3 为什么现在做（S6 批 2 开工前置）

- S6 批 1 已闭环（`36efa84`），十项纯前端应用落地，明确**不含**后端 `resultView`（`docs/evidence/施工报告-S6批1.md` §范围）。
- 批 1 的遗留 §4-1「只读卡不含工具输出」与 §4-3「`attachPublishConflict` 仍把摘要 + 明细整串写回段 reason」两条，**都是"没有视图载荷"的直接后果**，无法在批 1 范围内解决。
- 用户 2026-10-22 已裁「按业界主流方案做」（白名单 + 数据引用 + 版本降级），口径已定，缺的只是契约文本。

---

## 2. 业界依据

**前提：业界没有统一的"工具结果视图"标准。** 六家各做各的（有的在协议层、有的在 SDK 层、有的只是产品内约定），能对齐的只是**取向**而非字段名。所以本契约的定位是"按主流共识合成一份本仓可用的最小契约"，不是"照抄某一家"。三条可对齐的共识见 §2.2，逐家机制见 §2.1。

### 2.1 六家对照表

| 家 | 机制（一手要点） | 本契约**取**什么 | 本契约**不取**什么 |
|---|---|---|---|
| **A2UI**（Google） | 声明式 JSON 蓝图：宿主维护**组件 catalog**，agent 只能引用 catalog 里的组件名与**逐组件 JSON Schema**；数据用 `@path` 引用共享数据模型，不重复内联；提示词明文 "ONLY use component names from the Available Components schema — do NOT invent"（仓内源码取证：`外部设计挖掘与借鉴报告.md` §2.1） | `kind` 封闭枚举 + `meta.catalogId`（宿主组件目录标识）+ 提示词级"只准选枚举"约束 | 自由组件树 / 流式 JSONL 增量（Vue 无 A2UI 渲染器，仓内已裁不采纳，同报告 R2-1） |
| **Adaptive Cards**（Microsoft） | 封闭 `type` 枚举（元素卡集合，无任意控件）+ `version` 版本协商 + `fallbackText`（旧宿主读不了就给纯文本） | `version` major-only + **`fallback` 必填** + 未知 `kind` 不报错只降级 | 元素级布局语义（`ColumnSet`/`Container` 一类的排版语言）—— 排版归前端，不归载荷 |
| **JSON Forms** | UI schema 与 data schema **分离**；`scope` 用 JSON Pointer 指向数据；控件只声明"渲哪一段数据" | `data.ref`（RFC6901，缺省 `/`）+ `columns[]` 只描述不携带 | 由 JSON Schema 反推表单控件（GF 已有独立表单契约，不重复造） |
| **OpenAI Apps SDK** | 工具结果 `structuredContent`（宿主渲染用）与 `_meta`（元数据）**分层**；模型侧另有文本 | `result`（模型侧人话）与 `resultView`（宿主侧结构）**同源两投影**；`meta` 单独取 | widget iframe / `ui://` 资源（引入沙箱与 postMessage 协议，超出本仓范围） |
| **LangChain** | 工具可同时返回 `content`（给模型）与 `artifact`（原始结构化产物，**默认不进上下文**） | "结构化产物不自动进模型上下文"这一姿态 —— 与本仓 `tool-result.max-chars: 8000` 同一取向 | artifact 的自由形态（无白名单，等于把渲染交给工具作者） |
| **Vercel AI SDK** | `toModelOutput`：同一份工具结果可分别产出"模型视图"与"UI 视图"，两者是**同一真值的两个纯函数投影** | 渲染器取值的**唯一入口是投影函数**（断言 §7.2-6 同源） | 数据 part 的 `data-*` 开放式类型集合（`type` 由业务方自定义，无封闭枚举） |

### 2.2 三条共识与本仓取法

1. **受控白名单**（六家全有，形态各异）：这是**唯一**被所有方案共享的一条 ⇒ 本契约把它落成**四个封闭枚举**（`kind` / `columns[].type` / `chart.type` / `actions[].action.type`）。
2. **数据引用而非数据内嵌**（A2UI 的 `@path`、JSON Forms 的 `scope`、Apps SDK 的 `structuredContent` 分层同向）⇒ 本契约落成 `data.ref` 优先、`data.inline` 为逃生门，且**一份数据只出现一次**。
3. **版本降级而非报错**（Adaptive Cards 的 `version` + `fallbackText` 最直白）⇒ 本契约落成 `version` major-only + `fallback` 必填 + "未知一律降级"。

### 2.3 与既有调研件的一处口径对账

`docs/research/调研-AI对话组件-业界全景-2026-10.md` §③ 把 GenUI 的分歧记为"MCP Apps iframe 资源 vs A2UI 原生声明式蓝图"。本契约**两边都不选**：只取 A2UI 的 **catalog 白名单思想**（该思想在 `外部设计挖掘与借鉴报告.md` §2.3 的 R2-2 已被建议为「当前微调」），**不取**其组件树与流式蓝图能力 —— 因为本仓的诉求不是"让模型生成界面"，而是"让后端把**已经确定**的结果按受控形状描述出来"。这一点必须写清，否则容易被误读成"要上生成式 UI"。

---

## 3. 契约字段规范

### 3.1 顶层骨架（示例：发布冲突明细）

```json
{
  "version": "1.0",
  "id": "rv-call_7f3a91",
  "revision": 1,
  "kind": "table",
  "title": "i18n:resultview.publishConflict.title",
  "description": "共 7 处，按配置与行键定位",
  "status": "partial",
  "data": { "inline": { "rows": [
    { "defCode": "CFG_A", "rowKey": "XN-001", "message": "快照版本 3，当前版本 4" }
  ] } },
  "columns": [
    { "key": "defCode", "type": "string", "width": 4 },
    { "key": "rowKey", "type": "string", "width": 3 },
    { "key": "message", "type": "string", "width": 5 }
  ],
  "actions": [],
  "a11y": { "label": "发布冲突明细", "summary": "共 7 处冲突，本次展示前 5 处" },
  "fallback": { "text": "发布冲突 7 处，请在任务中心查看明细" },
  "meta": {
    "catalogId": "configmgr/resultview/v1",
    "truncated": { "shownRows": 5, "totalRows": 7 }
  }
}
```

### 3.2 字段逐条

**必填列语义**：`必` = 必填；`条` = 按 `kind` / `status` 条件必填；`选` = 选填。

| 字段 | 类型 | 必填 | 语义 | 示例 |
|---|---|---|---|---|
| `version` | `string`，`"<major>.<minor>"` | 必 | 契约版本。major 不同 = 不兼容（走 `fallback`）；minor 只允许新增可选字段 | `"1.0"` |
| `id` | `string` | 必 | 视图实例稳定 id，约定 `rv-<toolCallId>`。同 id 的后到 `revision` **覆盖**先到（这是覆盖式更新，不是冲突检测） | `"rv-call_7f3a91"` |
| `revision` | `integer ≥ 1` | 必 | 同 `id` 单调递增的覆盖版本。仅用于"同一视图被重新下发"时去旧留新 | `1` |
| `kind` | `enum`：`table`/`chart`/`form`/`text` | 必 | 视图种类，**封闭枚举**，同时是渲染器注册表的查表键。未知值 ⇒ 走 `fallback` | `"table"` |
| `title` | `string`，≤80 字符 | 选 | 一句话标题，落在卡头恒显行。允许 i18n key 形态（§4-规则 9），**不得是拼接出来的本地化句子** | `"i18n:resultview.publishConflict.title"` |
| `description` | `string`，≤200 字符 | 选 | 补充说明（一行内截断展示）。同 `title` 的文本纪律 | `"共 7 处，按配置与行键定位"` |
| `status` | `enum`：`ok`/`empty`/`partial`/`error` | 必 | **内容完整度**（≠ 工具 `outcome`）。`partial` = 有截断或部分字段缺失；`empty` = 查询成立但零行；`error` = 视图构造失败但工具本身可能成功 | `"partial"` |
| `data` | `object`，`{ref}` 或 `{inline}` | 条（`kind ≠ text` 必填） | 数据来源。`ref` = RFC6901 指针，在"帧内唯一数据文档"内求值，缺省 `/`（整份）；`inline` = 内联文档。**二者互斥** | `{"ref":"/"}` / `{"inline":{"rows":[…]}}` |
| `columns` | `array<Column>` | 条（`kind = table` 必填，≥1 且 ≤50） | 列定义，数组顺序即渲染顺序。只描述不携带值 | 见 §3.4 |
| `chart` | `object` | 条（`kind = chart` 必填） | 图表参数 | 见 §3.5 |
| `form` | `object` | 条（`kind = form` 必填） | **只读**表单（展示既有提交值），不是采集入口 | 见 §3.6 |
| `actions` | `array<Action>` | 选（≤3） | 卡内动作。v1 只允许 `type: "tool"` | 见 §3.7 |
| `empty` | `object`：`{text, hint?, icon?}` | 条（`status = empty` 必填） | 空态文案与出口提示。`icon` 只允许白名单名，不接受路径/URL | `{"text":"没有匹配行","hint":"放宽条件后重试"}` |
| `error` | `object`：`{code, message, retryable?}` | 条（`status = error` 必填） | `code` 用既有 `ErrorCodes` 稳定码；`message` 是人话，**不得内嵌 JSON**（R-166 裁决②） | `{"code":"FRAME_PAYLOAD_INVALID","message":"视图数据无法解析"}` |
| `a11y` | `object`：`{label?, summary?, role?}` | 选（`table`/`chart` 建议必填） | 无障碍补充：`label` = 区域名（落到 `aria-label`），`summary` = 一段文本替代 | `{"label":"发布冲突明细","summary":"共 7 处冲突"}` |
| `fallback` | `object`：`{text, format: "plain"}` | **必** | 降级载荷。触发条件：未知 `kind` / 未知 major / 列类型不认识 / `data` 解不出。**`text` 必须有内容**（禁止空串） | `{"text":"发布冲突 7 处，请在任务中心查看","format":"plain"}` |
| `meta` | `object`：`{catalogId?, truncated?, source?}` | 选 | `catalogId` = 宿主组件目录标识；`truncated` = `{shownRows,totalRows}`（行数口径；字符口径复用 G5 的 `TruncationContract`，见 §6.3-5）；`source` = 数据来源标记（如 `"validation_issue"`），用于审计 | `{"catalogId":"configmgr/resultview/v1","truncated":{"shown":5,"total":7}}` |

### 3.3 `kind` 四态的分工

| kind | 必带 | 说明 | 本稿 v1 状态 |
|---|---|---|---|
| `table` | `columns` + `data` | 唯一的"多行明细"形态。**发布冲突、校验问题、表格预览的计数/采样都归它** | **实现** |
| `text` | 无（用 `fallback.text` 或 `description`） | 只有一句话的结果。用于替换"把 JSON 塞进 reason"的历史做法 | **实现** |
| `form` | `form.fields` | 只读回执（GF 已决态的展示形态） | **实现** |
| `chart` | `chart` + `data` | 图表 | **枚举占位、v1 不实现**（仓内无图表库，见 §8.2-O2） |

### 3.4 `Column` 与列类型白名单

```ts
interface Column {
  key: string            // 必。数据文档里的字段名（或相对该行的 JSON Pointer）
  type: ColumnType       // 必。九种封闭枚举
  label?: string         // 选。缺省 = key 原样展示（业务字段码本就不翻译）
  format?: string        // 选。**语义**格式，见 §4-规则 9
  options?: Option[]     // 条。type=enum / badge 必填
  align?: 'left'|'right'|'center'  // 选。缺省按 type 推导
  width?: number         // 选。相对权重 1..12
  hidden?: boolean       // 选。默认 false（保留列定义便于切换，不参与渲染）
}
interface Option { value: string; label: string; tone?: 'ok'|'warn'|'danger'|'muted' }
```

| `type` | 值域要求 | 渲染规则 | 缺省 `format` |
|---|---|---|---|
| `string` | 任意标量，渲染前 `String()` | 纯文本，超长省略 + `title` 回读 | — |
| `number` | 数值 | 按 locale 分组与小数位 | `number:2` |
| `integer` | 整数 | 同上，无小数 | `number:0` |
| `boolean` | 布尔 | 是 / 否（走 i18n，不硬编码） | — |
| `date` | ISO 日期串 | 按 locale 出日期 | `date` |
| `datetime` | ISO 时刻串 | 按 locale 出日期 + 时间 | `datetime` |
| `enum` | 命中 `options[].value` | 渲 `options[].label`；未命中渲原值（**不报错**） | — |
| `link` | 站内路由（白名单前缀）或 `http(s)` | 站内可点、外链**只渲纯文本 + 域名**（沿用输出防护批的外链收敛口径，§4-规则 2） | — |
| `badge` | 命中 `options[].value` | 渲 `options[].label` + `tone` 色族（复用既有五档→三族色） | — |

### 3.5 `chart`

```ts
interface ChartSpec {
  type: 'bar'|'line'|'area'|'pie'|'scatter'   // 必。**五值封闭枚举**
  x: string                                   // 必。必须是 columns[].key
  series: { key: string; label?: string }[]   // 必。≥1，key 必须是 columns[].key
  valueFormat?: string                        // 选。同 Column.format 语义
  stacked?: boolean                            // 选。仅 bar/area 有效
}
```

约束：`x` 与 `series[].key` 必须是 `columns[].key` 的子集（**先有列定义才有图**，不允许图表引用未声明字段）；数据行数同受 §4-规则 8 上限；`chart` 形态**必须同时给 `fallback`**（图表渲不出时降级为文字摘要，绝不空白）。

### 3.6 `form`（只读）

```ts
interface ReadonlyForm {
  fields: { key: string; label: string; type: FormFieldType; value: unknown; options?: Option[] }[]
  submittedAt?: string   // ISO 时刻
}
```

- `FormFieldType` **直接复用** `frontend/src/types/form-schema.ts:14` 的六值：`text|number|boolean|date|enum|multi_select`。理由与表格专项复用 `FieldQueryCondition` 同款：**不新造第二套字段类型**。
- **没有 `required` / `placeholder` / `defaultValue` / 任何提交回调** —— 这是"展示"，不是"采集"。采集入口**只有** `generative_form`（`types/sse.ts` 既有帧）。这一条是硬边界：`resultView.form` 不得演化成第二个采集通道，否则等于多开一个注入面。

### 3.7 `Action`

```ts
interface Action {
  id: string                     // 必。稳定标识（幂等键，重复点击按 id 去重）
  label: string                  // 必。按钮文案（i18n key 或业务原文）
  style?: 'default'|'primary'|'danger'   // 选。默认 default
  scope: 'view'|'row'            // 必。动作作用于整卡还是某一行
  action: { type: 'tool'; tool: string; args?: Record<string, unknown> }  // 必。type 封闭为 "tool"
}
```

- `action.type` **只有 `"tool"` 一个值**。没有 `url` / `open` / `script` / `js` / `handler` —— 动作**只能**回到既有工具通道（前端工具四路由或后端 `@Tool`），因此天然继承工具面的白名单、scope 判定与风险分级。
- `scope: "row"` 时，渲染器只允许把该行的**行标识**（`rowKey` 或 `meta` 声明的键）填进 `args` 模板；**服务端必须二次校验** `args` 与该行绑定（§4-规则 4）。
- v1 **只允许非写工具**（`start_*` 一类写动作仍走既有确认门，不由 `resultView` 直发，§8.2-O4）。

### 3.8 `data` 单份规则的具体取值

| 形态 | 什么时候用 | 指针求值域 |
|---|---|---|
| `{"ref": "/"}` | `result` 本身是 JSON 文档（结构化工具） | 帧内 `result` 文本解析出的 JSON，`/` = 整份 |
| `{"ref": "/rows"}` | 同上，只要其中一段 | 同上 |
| `{"inline": {...}}` | `result` 是人话文本（如"作业已启动"），结构化数据无处可放 | 该对象自身 |

**禁止**：同一条 `resultView` 既 `inline` 又在 `result` 里重复同一批行；`columns` 里塞 `value`（列只描述不携带）。

---

## 4. 契约规则十条

| # | 规则 | 可断言的落地口径 |
|---|---|---|
| 1 | **白名单** | 四个封闭枚举：`kind`(4) / `columns[].type`(9) / `chart.type`(5) / `actions[].action.type`(1)。**未知值一律降级，不抛错、不白屏**（渲 `fallback`） |
| 2 | **禁任意代码** | 渲染器不 `v-html`、不 `new Function`、不做表达式求值、不认 CSS 类名与模板串；`title`/`description`/`label`/`message` 全按纯文本转义；**不渲 markdown**（否则工具结果获得第二条 HTML 注入面，与输出防护批口径冲突）；`link` 外链只渲纯文本 + 域名 |
| 3 | **数据单份** | 一份行数据在帧内只出现一次（`ref` 指 `result`，或 `inline` 自带）；`columns`/`chart`/`actions` 只描述不携带值；同一 `resultView.id` 不得出现两份数据文档 |
| 4 | **动作服务端校验** | 构造期：`action.tool` 必须在工具注册表内且非写/非高危（v1）；`scope:"row"` 必须带行标识且 `args` 与之绑定；运行期：点击后按既有工具通道重新过 scope + 风险分级 + 前端 Guard（`FrontendToolGuard`），**`resultView` 不构成任何授权** |
| 5 | **渐进渲染** | ① 字段级可选：缺 `columns` 降 `text`、缺 `data` 降 `fallback`；② 同 `id` 后到 `revision` **覆盖式更新**（不重排、不叠加）；③ 渲染**不阻塞**对话流（同帧内同步渲，无二次请求）；④ **v1 不做流式增量**（`tool_result` 是终态帧，逐行追加须新帧，见 §6.4） |
| 6 | **a11y** | 表格：`role="table"` + `caption`（取 `a11y.label`/`title`）+ 表头 `scope="col"`；状态**不单靠颜色**（徽标字形 + 文案 + 色三重冗余，WCAG 1.4.1）；图表：必须给 `a11y.summary` 作文本替代；动效遵守 `prefers-reduced-motion`（既有豁免口径）；动作钮键盘可达、`aria-label` 取 `label` |
| 7 | **版本策略** | `version` major-only：major 不同 ⇒ **立即**走 `fallback`，不尝试兼容解析；minor 只允许新增可选字段（不得改语义、不得改必填性）。`fallback` 必填 ⇒ 任何版本都有可读出口 |
| 8 | **规模上限** | `≤200 行` / `≤50 列` / `≤256KB`（UTF-8 字节，按**数据单份**计）。超限 = **后端截断 + `status:"partial"` + `meta.truncated{shownRows,totalRows}` 三件同时为真**（静默截断禁止）。v1 实际生效上限 = min(256KB, 帧预算剩余)，见 §6.3-3 |
| 9 | **i18n** | 服务端**不下发本地化自然语言**。文案字段只允许三种取值来源：① i18n key（`i18n:` 前缀或既有 `tool.<name>.<slot>` 形态，前端 `t()` 渲）；② 用户数据原样值（配置码/行键/字段码，**不翻译**）；③ 封闭枚举（`status`/`type`/`tone`）。数值与日期走 `format` **语义**（`date`/`datetime`/`number:2`/`percent`/`currency:CNY`/`duration`），**禁 pattern 串与占位符模板**（那是自由格式化语言）；分隔符/货币符号位置/日月顺序由前端 locale 决定 |
| 10 | **构造方式** | v1 **只允许后端确定性构造**：同一输入 ⇒ 同一字节载荷（可快照对账）。模型若参与，**只能从 `kind` 枚举里选一个值**，且必须过 §3 的 schema 校验；**模型产出的自由 JSON 永不直接作为 `resultView`**（这是本契约的安全性质，与"上传不经手文件路径""consequential 拒执"同族） |

---

## 5. 与既有「发布冲突 reason」问题的承载方案

> 派工单编号 **P1-4（拆双通道）**；仓内同一问题的登记 = `docs/决策看板.md:127`「发布冲突 `reason` 字段模型侧口径」（待裁）+ `docs/任务看板.md:393` 挂账行 + `施工报告-S6批1.md` §4-3。本节把这条既有欠账**落进本契约**，并说明为什么这是唯一可行的拆法。

### 5.1 现状：一份字符串、三个消费者

| 环节 | 落点 | 事实 |
|---|---|---|
| 生产 | `frontend/src/stores/task.ts:100 formatPublishConflictSummary` | 组串 = **首行摘要**（`发布作业 #N 校验冲突明细（共 M 处）`）+ 至多 **5 条**编号明细 `N. defCode#rowKey：message` |
| 搬运 | `stores/task.ts:362` → `handlers.onConflict(summary)` → `stores/ai.ts:3734 attachPublishConflict` | 整串写进段 `reason`（`:3755`）。**注意**：`docs/决策看板.md:127` 与 `docs/任务看板.md:393` 登记的 `ai.ts:3002` 已漂移，实测当前为 `:3734` |
| 消费者 ①（卡面红字行） | `AiPanel.vue:488` 渲 `reasonText(part)`；冲突时只取首行（`:2729` → `blocks.ts:571 reasonHeadline`） | 展示 |
| 消费者 ②（明细表格） | `AiPanel.vue:492` 渲 `conflictRows(part)` ← `blocks.ts:556 parsePublishConflicts` | **正则反解** |
| 消费者 ③（模型） | `stores/ai.ts:3723 reportUiEventResult('start_publish', summary)` | 同一串作 ui_event 结果回灌 |

### 5.2 三个结构性问题（为什么"改一句文案"改不动）

1. **正则反解是脆的**：`^\s*\d+\.\s*(.+?)#(.+?)[：:](.+)$` 对 `defCode` / `rowKey` / `message` 里出现 `#`、全角/半角冒号混用、`message` 换行一律错拆。而改格式又必须同时改生产端字符串 —— **两个文件被一条隐式协议绑死**。
2. **截断对模型是信息损失**：`slice(0, 5)` 对展示合理，但模型拿到的也是 5 条，**无法知道"还有 M−5 处"**，追问会答"只有 5 处"。`status:"partial"` + `meta.truncated{shownRows,totalRows}` 是契约能表达、文本表达不了的东西。
3. **`reason` 的语义被污染**：`ToolPart.reason` 的定义是"拒绝原因 / 失败原因"（`frontend/src/types/ai.ts:326-327`），却承载了明细数据。于是「reason 是否只留一句」这个裁决**卡住**了 —— 因为卡面**还依赖这个字段做反解**，改它就是"同时改 UI 与模型"。这正是 P1-4 要拆的那根绳。

### 5.3 承载方案：按受众拆通道，让裁决解耦

| 通道 | 字段 | 内容 | 消费者 |
|---|---|---|---|
| **A 模型通道** | `result`（既有）+ `reason` | 人话摘要与计数；明细若仍需给模型，留在 `result` 文本里。**本契约不改 `reason` 的现有内容** —— 是否缩为一句由 P1-4 本体裁（§8.2-O6），不裁也照常工作 | 模型 |
| **B 视图通道** | `resultView`（additive 可选键） | 结构化行 + 列定义 + 截断元数据 + 无障碍补充 | 卡面渲染器 |

落地四条：

1. **卡面不再反解 `reason`**：删 `AiPanel.vue:2729` 的冲突分支与 `conflictRows`，`reason` 回归"一句原因"原样渲染（`blocks.ts:571 reasonHeadline` 只在需要取首行时保留，不再参与表格构造）。
2. **明细表格由 `resultView.kind="table"` 承载**（形如 §3.1 示例）：`columns = defCode/rowKey/message`；`status: "partial"`；`meta.truncated = {shownRows: 5, totalRows: M}`；`fallback.text` 兜住"渲不出表格"的情形。
3. **依赖倒置（本方案的核心）**：「`reason` 是否只留一句」从此是**纯模型侧决策** —— 改它不影响卡面，不改它卡面也照常正确。裁决空间从"UI + 模型一起改、风险叠加"缩到"只改一段文本"。**P1-4 的"拆"就拆在这里**：不是拆字符串，是拆**消费者**。
4. **同屏不重复**：`resultView` 存在时，`parsePublishConflicts` **必须零调用**（负断言，§7.2-6）。这条与 S6 批 1 的 N8 判据（"两种形态不同屏重复报同一份明细"）同源 —— 拆通道不能变成双份报。

### 5.4 迁移与回退

- **双写期**：后端/前端同时给 `reason`（现状整串，一字不改）与 `resultView`。此期间**行为零变化**（渲染优先级：有 `resultView` 用渲染器，无则走旧反解）。
- **删旧路径条件**：`start_publish` 的冲突结果 100% 带 `resultView`，且跨一个发布版本无回退（回退判据 = 有 `resultView` 但渲不出表格的告警计数为 0）。达成后删 `parsePublishConflicts` 与 `ConflictRow` 类型。
- **与 G5 的交点（写清以免重复计账）**：明细行属于**给 UI 的那一份**（帧侧，64KB 量级），**不占**模型侧 8000 字符预算；但 `result` 文本里那 5 条**仍然占**模型预算。故 P1-4 若最终裁"reason 只留一句并停止把明细回灌模型"，模型侧预算同时下降 —— **两件事同批做，收益叠加、改动互不阻塞**。
- **历史消息**：旧会话（镜像/归档）里的段没有 `resultView`，走旧反解路径；`resultView` **必须随段持久化**，否则刷新后表格消失只剩文本（§6.3-2、§8.1-R4）。

---

## 6. SSE 帧契约 16 的 additive 兼容性

### 6.1 载体选择

- **首选：`tool_result.resultView`**（additive 可选键）。理由：视图天然属于"某一次工具结果"，自带 `toolCallId` 归属；`tool_result` 已是"一次执行的全部结局字段"的落点（`ok`/`outcome`/`code`/`retryable`/`target`/`message`/`preflight` 全在这帧上，`SseChatEmitter.java:763-784`）。
- **备选（仅当 S6 批 2 只做"轮末一次性汇总"）：`done.resultView`**。可复用既有 `extra` map 管道（`ResilientChatService.java:782-789` → `SseChatEmitter#done` 的 `frame.putAll(extra)`，`:907-909`）。代价：一轮可能有多个工具多张视图，放 `done` 要额外造数组与归属映射 —— **不推荐**，除非明确只做汇总。

> **2026-10-22 已裁：帧载体定 `tool_result.resultView`**（备选 `done.resultView` 不采用）。依据：MCP 的 `structuredContent` 与 AG-UI 的 `ToolCallResult` 都把宿主侧结构挂在**工具结果**上（视图归属 = 某次执行，与 §2.1 Apps SDK 行同向）；且该键是 `tool_result` 的 **additive 可选键**，帧类型集仍是 16、判别联合与 conformance 白名单零改动（§6.2）。`done.resultView` 需自造"多视图数组 + 归属映射"，与"数据单份"（§4-规则 3）冲突。

> **一字之差须写明**：`done.extra` 是 **Java 侧容器名**，线上**不是**嵌套对象。`putAll` 之后出的是 `done.safety` / `done.context` / `done.paramsLocked` 这样的**平铺键**（前端 `types/sse.ts:390-427` 已按平铺建模）。任何"读 `frame.extra.xxx`"的实现都是错的。

### 6.2 三条"不破"的论证

| 不破 | 依据 |
|---|---|
| **帧类型集仍是 16** | `frontend/src/types/sse.ts:119-145` 的 `SseFrameTypes` **零改动**；后端不新增帧名。additive 键不改枚举 |
| **判别联合不变** | `parseSseFrame`（`types/sse.ts:511`）只按 `type` 判别；新键不参与判别。JSON 无严格模式，旧客户端遇未知键**忽略**（TS 结构类型下亦然） |
| **conformance 白名单不改** | 契约用例按帧类型枚举断言；本契约只需给 `tool_result` 补一条"`resultView` 可选且自洽"的正例，**不动**既有白名单 |

**与 15→16 那次扩张的区别（关键分野）**：`context` 帧是**新增帧名**，必须同步三处（后端 `FrameContract`、前端 `SseFrameTypes` + 判别联合、conformance 白名单，见 `设计-上下文统计与压缩专项-2026-10-21.md:225`）。本契约是**新增可选键**，只动 `SseToolResultFrame` 一个接口 + 落段。**冻结点在"帧名"，不在"键"** —— 这条要写进 S6 批 2 的施工卡，否则容易被当成"破冻结"而卡住。

### 6.3 同批必须改的五个面（少一处就半残）

1. **类型 + 落段**：`SseToolResultFrame` 加 `resultView?: ResultView`；`types/ai.ts#ToolPart` / `ToolPartPatch`（`:316`/`:429`）加同名字段并落段。**只改帧不落段 = 刷新即丢卡**。
2. **历史与镜像恢复**：`loadHistory` 与 Redis 镜像必须带 `resultView`（与 `submittedValues` 同族，`types/ai.ts:331-340` 已示范"随段持久化"）。这是"通用渲染器能用于历史消息"的前提。
3. **帧预算**：`FrameSizeLimiter.TRUNCATABLE_FIELDS` 现只有 `text` / `result`（`FrameSizeLimiter.java:36`）⇒ `resultView` 是**不可截字段**。一旦它超预算，代码会走 `unreachable-limit` 分支（`:104-110`）：**挂 `truncated` 标记但照发超预算帧**。故 v1 必须在**构造期**做 `resultView` 自身规模闸（§4-规则 8），**不放宽帧截断面**（放宽等于让截断把 JSON 切坏）。
4. **输出脱敏/防护面**：`resultView` 会承载 `defCode`/`rowKey`/`message` 等业务文本（来源 `ValidationIssue`）。既有 `FrontendToolGuard.inspectResult` 只复核 `result` 文本（`FrontendToolGuard.java:30-93`）⇒ 必须把 `resultView` 纳入同一复核面，否则**换一个字段就绕过了输出脱敏**。
5. **与 G5 截断词汇对齐（在途批，须在施工前复核）**：G5 双通道对齐批正在把 `app.ai.tool-result.*` 与 `app.ai.frame.*` 两组键并成 `app.ai.result-budget.*`，并引入统一截断字段契约 `TruncationContract`（`truncated` / `strategy` / `originalChars` / `returnedChars` / `notice`，`truncatedReason` 为 `notice` 的兼容别名；**结构化 JSON 不走字符截断**，改为整体标记 + `preview` 头部分片）。本契约的截断表达**必须复用这套词汇**：
   - 字符口径（`originalChars`/`returnedChars`）**由 `TruncationContract` 提供**，本契约不重复定义；
   - `meta.truncated` 只补**行数口径** `{shownRows,totalRows}`（行数 ≠ 字符数，两者并存不冲突）；
   - `status:"partial"` 与 `truncated:true` 必须同时为真（一个说"内容不全"，一个说"怎么不全"）。
   落 `resultView` 之前须确认该批已合并到基线，否则上表的键名会指向旧名（`application.yml:281-286` / `:303-308`）。**2026-10-22 复核：该批已合流 `2ca2ceb`，`app.ai.result-budget.*` 键名与 `TruncationContract` 词汇均已在基线** ⇒ 施工按新键名取，"在途"表述作废（原文保留留痕）

### 6.4 什么时候才需要破冻结

只有一种情形：S6 批 2 要求"表格**逐行流式追加**"（渐进渲染的强形态）。那时才需要新帧（或帧内分片），帧类型集 16→17，须按上下文专项同款三处同步 + conformance 白名单更新。**本稿 v1 明确不做流式增量**：`tool_result` 是终态帧，逐行追加既无收益（结果已完整）又引入"半张表"状态。

---

## 7. 迁移路径与验收判据

### 7.1 五步迁移（每步可独立回滚）

| 步 | 内容 | 关键约束 | 回滚点 |
|---|---|---|---|
| **① 契约落地（零行为变化）** | `types/resultview.ts` 类型 + 后端 `ResultView` 值对象 + schema 构造器/校验器 + `tool_result` 可选键（**默认不出**） | 既有用例**零改动**全绿；新增"缺键 = 旧行为"用例 | 删构造器调用 |
| **② 渲染器骨架** | `components/ResultView/ResultViewRenderer.vue` + `kind → 组件` 注册表 | **注册表缺失 ⇒ 回退现有专用卡**（不是空白）；未知一切 ⇒ `fallback` | 不挂载渲染器 |
| **③ 第一个工具：发布冲突** | 按 §5 落 `kind=table`；卡面停止反解 | `parsePublishConflicts` 零调用（负断言）；同屏不双份 | 恢复旧反解分支 |
| **④ 逐工具迁移** | 排序按"文本反解依赖度"：冲突卡 → GF 只读回执 → 表格写预览 → 普通工具卡 | 每个工具一条"有/无 `resultView` 双形态"用例 | 单工具开关 |
| **⑤ 按覆盖率删旧卡** | 删 `v-if` 分支与解析函数 | 删除条件 = 该工具 100% 带 `resultView` 且跨一版无回退；**`fallback` 路径不删** | 恢复分支（git revert） |

> **2026-10-22 已裁：迁移五步路径按上表定稿**（①契约落地 ②渲染器骨架 ③发布冲突首个工具 ④逐工具迁移 ⑤按覆盖率删旧卡）。依据：每步有独立回滚点（删构造器调用 / 不挂载渲染器 / 恢复旧反解分支 / 单工具开关 / git revert），与 Adaptive Cards 的 `fallbackText` 退路同姿态 —— **任一步失败都退回"有 fallback 的旧行为"，不出现空白卡**；⑤ 的删卡闸"跨一版无回退"不放松。

### 7.2 验收判据（逐条可断言）

1. **帧零变更**：`SseFrameTypes` 字面量**逐字符不变**（16 项）；`tool_result` **无** `resultView` 时帧载荷与基线**逐字节一致**（快照对账）。
2. **零 JSON / 零 HTML**：卡面与展开体无 `{` / `"` / `key=value` 形态（沿用 S6 批 1 断言）；全仓 `v-html` 计数**不增**；渲染器内无 `innerHTML` / `new Function`。
3. **降级齐备**：未知 `kind` / 未知 major / 未知列类型 / `data` 解不出 / `chart` 渲不出 —— 五类种子各一条用例，**一律渲 `fallback` 或旧卡，不白屏、不抛错、不渲空卡**（空卡判据沿用 19-e：0 行不给展开入口）。
4. **上限三件同真**：> 200 行 / > 50 列 / 超字节预算 → `截断` + `status:"partial"` + `meta.truncated{shownRows,totalRows}` 必须同时成立（单测逐项）。
5. **动作拒绝在构造期**：`action.tool` 不在白名单 / `scope:"row"` 缺行标识 / `args` 与行不绑定 ⇒ **构造即失败**（fail-fast，不静默丢动作、不渲死按钮）。
6. **同源（双通道同源的可断言化）**：`resultView` 的行值与 `data.ref` 指向的文档**逐行相等**；`resultView` 存在时旧的文本反解函数**调用计数为 0**（负断言）。
7. **a11y**：表格有 `caption` 且表头有 `scope="col"`；状态徽标"字形 + 文案 + 色"三重冗余；图表有 `a11y.summary` 文本替代。
8. **门禁**：`node scripts/ci/check-docs.mjs` 6/6；前端 vitest 全绿 + `typecheck` 0 + `build` 通过；后端 `mvn test` 全绿；脱敏扫描 0 命中。

---

## 8. 风险与开放问题

### 8.1 风险（诚实登记）

| # | 风险 | 说明与缓解 |
|---|---|---|
| R1 | **256KB 与 64KB 是两把尺** | 契约上限 256KB（调研建议）vs 单帧 65536 字节（`application.yml:308`）。缓解：v1 生效上限取 min，且 `resultView` 构造期自查；**根治大概率已由 G5 批（在途、未提交）以 `app.ai.result-budget.ui.max-frame-bytes` 统一**，本契约须在该批合并后复核键名与取值（§8.2-O1）。**不得**靠帧级截断兜底（见 R3）。**2026-10-22 复核已收口**：G5 已合流（`2ca2ceb`），键名以 `app.ai.result-budget.*` 为准、`TruncationContract` 截断词汇就位 ⇒ "在途、未提交"的前提已消除（原文保留留痕） |
| R2 | **双份真相漂移** | `result` 文本与 `resultView` 数据若各自构造，迟早在某工具上分叉。缓解：§4-规则 3 数据单份 + §7.2-6 同源断言 + 构造器**单入口**（禁止两个方法各自拼） |
| R3 | **`resultView` 不在可截断面** | `FrameSizeLimiter` 只截 `text`/`result`；`resultView` 超预算会走 `unreachable-limit` 照发超限帧（`:104-110`）。缓解：构造期规模闸；**不放宽** `TRUNCATABLE_FIELDS`（截 JSON 会把结构切坏） |
| R4 | **落段/镜像遗漏 ⇒ 刷新丢卡** | 用户会读成"AI 忘了刚做的事"。缓解：§6.3-1/2 同批落 `ToolPart` + 镜像 + `loadHistory`；验收加"刷新后表格仍在"用例 |
| R5 | **输出脱敏被绕过** | 新字段不在 `FrontendToolGuard.inspectResult` 面内（`:30-93`）⇒ 业务文本换个字段就出去了。缓解：§6.3-4 同批纳入 |
| R6 | **标题注入** | `title`/`description` 若直接取工具结果文本，则成为新的展示面注入点（本仓第一注入面 = 工具结果，见安全防护调研 §4.1）。缓解：纯文本转义 + 长度闸 + 不渲 markdown；`title` 优先走 i18n key |
| R7 | **a11y 倒退** | 自绘表格若缺 `caption`/`scope`，会比现有 Element Plus 表格**更差**。缓解：§7.2-7 单列断言；不做"先上后补" |
| R8 | **删旧卡过早** | 旧镜像/旧档案里的段没有 `resultView` ⇒ 白屏。缓解：删分支条件加"跨一版无回退"，且 `fallback` 与旧反解路径**最后删** |
| R9 | **与上下文压缩 L1 冲突** | L1「清工具结果」清的是**进模型**的那份；`resultView` 是 UI 投影、随段持久化。二者若不显式对齐，会出现"压缩后历史卡片表格消失"或"压缩不省内存"两难。缓解：须在压缩专项 §L1 明确"只清模型侧副本"，本稿登记为跨专项交点 |
| R10 | **`meta.catalogId` 空转** | v1 只有一个目录（`configmgr/resultview/v1`），该字段无实际判别力。缓解：文档明写"未实现多目录前不消费"，避免被当作已生效的能力 |
| R11 | **`tool_result` 正在变成万能袋子** | 该帧已叠 `preflight`/`outcome`/`code`/`retryable`/`target`/`message`，再加 `resultView` ⇒ 需要纪律。缓解：登记"可选键预算"——每个键有 owner、有上限、有"缺键不报错"断言；新增键须在此表登记 |
| R12 | **`chart` 无依赖** | 仓内**无图表库**（`frontend/package.json` 无 echarts/chart.js/d3）。若 v1 就实现 `chart`，等于本批引入一个新依赖 + 一套 a11y/降级面。缓解：v1 **只占位不实现**（§3.3） |

### 8.2 开放问题（待裁，未裁前不进施工）

> **2026-10-22 裁决总注**：本表经主会话按业界主流代裁（用户授权「你参考业界主流的，做最佳裁决」——原话见 `docs/决策看板.md` 10-22 行）⇒ **O1 / O2 / O6 逐条给裁决行**（见各行末），**O3 / O4 / O5 / O7 / O8 维持「本稿倾向」列**（代裁不改倾向，倾向本身即业界主流取向，施工遇冲突回本表）。原「待裁」表述保留不删。

| # | 问题 | 选项 | 本稿倾向 |
|---|---|---|---|
| O1 | **帧预算是否统一**：`resultView` 上限 256KB 与单帧 64KB 如何共存？ | (a) v1 取 min、按需拆帧（本稿）；(b) 抬高 `max-bytes`（与 G5 同批）；(c) `resultView` 只留小载荷，大结果走引用端点 | **(a)**。**注**：G5 批（在途、未提交）已在做 (b) 的上位动作 —— 把 `tool-result` 与 `frame` 两组键并成 `app.ai.result-budget.*` 并统一截断词汇（`TruncationContract`）⇒ 本项待该批合并后**复核并收口**，不再单独裁。**2026-10-22 已裁并关闭**：G5 双通道对齐批**已合流**（`2ca2ceb`，`feat(ai-result-budget)`），复核收口通过 —— `app.ai.result-budget.*` 键名与 `TruncationContract` 截断词汇（`truncated`/`strategy`/`originalChars`/`returnedChars`/`notice`）均已就位，上表 §6.3-5 与本条 R1 的"在途"表述自此作废（原文保留）；v1 生效上限仍 = min(契约上限, 帧预算)，字符口径一律复用该契约 |
| O2 | **`chart` v1 是否实现** | (a) 只占位、渲染走 `fallback`；(b) 引 echarts；(c) 用 CSS/SVG 自绘最小柱/线图 | **(a)**：契约先立、能力后补；无图表库时硬做会拖垮 a11y 与降级面。**2026-10-22 已裁：取 (a) 只占位不实现** —— `kind: "chart"` 与 `ChartSpec` 五值枚举保留在契约内，v1 渲染走 `fallback`。依据：Adaptive Cards / JSON Forms 均以**小集合起步、能力后补**（先立封闭枚举再扩元素）；且仓内无图表库（`frontend/package.json` 无 echarts / chart.js / d3），v1 硬做等于同批引入新依赖 + 一套 a11y 与降级面，收益不抵风险 |
| O3 | **`data.ref` 是否允许指向服务端资源** | (a) 只允许同帧（零二次请求，离线安全）；(b) 允许 `ref` + `source:"endpoint"`（大结果） | **(a)**：v1 的规模上限内同帧足够；跨端点会引入鉴权/缓存/断网三个新面 |
| O4 | **`actions` v1 是否启用** | (a) 只渲"非写工具"动作；(b) 完全不给 `actions`；(c) 写动作也走 `resultView` 直发 | **(a)**；(c) 明确否 —— 那等于绕开确认门 |
| O5 | **`empty`/`error` 是否并入 `status`** | (a) 保留两个子对象（冗余但显式）；(b) 只留 `status` + `fallback.text` | **(a)**：错误码与提示文案需要独立承载，`fallback` 是最后一道不是第一道 |
| O6 | **P1-4 本体仍待裁**：`reason` 是否只留一句 | 本契约**不替它裁**，只把它降为模型侧单点决策 | 由用户/指挥官在 S6 批 2 发令时裁。**2026-10-22 已裁：P1-4 的承载方式按 §5.3「依赖倒置」定稿** —— 卡面**停止反解 `reason`**、明细改由 `resultView.kind="table"` 承载（`meta.truncated{shownRows,totalRows}` + 必填 `fallback.text`），于是「`reason` 是否只留一句」**降为纯模型侧决策**（改它不影响卡面、不改它也正确），随 S6 批 2 施工时定，**不阻塞本契约开工**。依据：业界同源两投影（Apps SDK 的 `structuredContent` 与文本分层、Vercel AI SDK 的 `toModelOutput`）—— 拆的是**消费者**而不是字符串；卡面与模型共用一条串必然互相绑死（§5.2-3）。本条与 `docs/决策看板.md` 的「发布冲突 `reason` 字段模型侧口径」销记行同源（该行已于 2026-10-22 销记、状态 ✅） |
| O7 | **逐工具迁移顺序与"删旧卡"的版本闸** | 按反解依赖度排序（§7.1-④）；删卡是否要求跨一个 minor 版本 | 建议跨一版 |
| O8 | **`resultView` 是否进可观测面** | (a) 不进（只随段持久化）；(b) 进事件日志（帧体积与日志体积同时上升） | **(a)**：可观测性专项已定「逐帧存 span 无主流做法」，载荷不必二次落盘 |

---

## 附 A · 本稿读过的仓内锚点（便于复核）

> **行号基准与漂移声明**：下表行号取自**本稿成文时的工作树**（`HEAD 0b425de` + 当时在途改动）。成文期间 G5 双通道对齐批正在并发改写 `FrameSizeLimiter` / `SseChatEmitter` / `ToolResultLimiter` / `ToolResultEnvelope` / `AiProperties` 五个后端文件并改配置键名 ⇒ 上述文件的**行号与键名**在该批合并后必然漂移，复核时请按**类名 + 方法名**定位，不要按行号。前端锚点（`types/sse.ts` / `types/ai.ts` / `blocks.ts` / `stores/*.ts` / `AiPanel.vue`）不受该批影响。

| 面 | 文件:行 |
|---|---|
| 帧类型集 16（`SseFrameTypes`） | `frontend/src/types/sse.ts:119-145` |
| `done` 帧 additive 键先例（`context` / `safety` / `paramsLocked`） | `frontend/src/types/sse.ts:390-427`；后端 `ResilientChatService.java:782-789`、`SseChatEmitter.java:898-910` |
| `tool_result` 帧字段清单（additive 落点） | `backend/.../ai/run/SseChatEmitter.java:763-784`；`frontend/src/types/sse.ts:209-247` |
| 帧判别与未知帧容忍 | `frontend/src/types/sse.ts:511` |
| 单帧 64KB 上限与字段级截断 | `backend/src/main/resources/application.yml:303-308`；`backend/.../ai/run/FrameSizeLimiter.java:36, 52-110` |
| 工具结果双通道（模型侧 8000 字符 / 帧侧全文） | `backend/src/main/resources/application.yml:281-286` |
| `ToolPart` 落段与随段持久化范式（`submittedValues`） | `frontend/src/types/ai.ts:316-360, 429-440` |
| 卡面 7 条专用分支 | `frontend/src/components/AiPanel/AiPanel.vue:376, 492, 515, 570, 2528, 2535` |
| 文本反解（args / 发布冲突） | `frontend/src/utils/blocks.ts:149, 199, 556, 571` |
| 发布冲突组串与搬运 | `frontend/src/stores/task.ts:100-110, 355-362`；`frontend/src/stores/ai.ts:3723, 3734-3763` |
| 只读卡判据（S6 批 1 迁入） | `frontend/src/utils/blocks.ts:315` |
| 表单字段类型六值（本契约 `form` 复用） | `frontend/src/types/form-schema.ts:14-31` |
| 前端工具 guard 面（输出复核） | `backend/.../ai/tool/FrontendToolGuard.java:30-93` |
| 工具注册面规模（33 个 `@Tool`） | `backend/.../ai/tools/AiTools.java`（基础 18 + 表格 8 + 可视可操作 7） |
| 前端依赖（无图表库） | `frontend/package.json:15-33` |
| S6 批 1 遗留两条（§4-1 不含工具输出 / §4-3 reason 整串） | `docs/evidence/施工报告-S6批1.md` §4 |
| 待裁与立项登记 | `docs/决策看板.md:127-128`、`docs/任务看板.md:79-80, 393-394` |
