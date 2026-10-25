> 来源=<仓外证据>/ai-full-audit/final-gap/FA1-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
未运行任何测试。以下为 FA1 最终逐行审计报告（只读，未改任何文件/测试/配置，未启动服务）。

# FA1-report｜后端工具契约层（定义 / 披露 / 守卫 / 截断 / 结果视图）

- **快照**：`<仓外证据>/ai-full-audit/tree`（`ae883fb` + `working.patch`）
- **范围**：FA1.md 点名的 10 个文件，**3,888 行**（`wc -l` 实测合计 3,888）
- **方法**：全文逐行读（非定点），每条分支映射到 FINAL-AUDIT §5 既有 canonical ID，或给出「代码可证」的新/更正当条目
- **声明**：本文不含任何"已修复/已通过"结论；需运行期条件的条目标 `R`

## 0. 行覆盖证明（逐文件，非抽样）

| # | 文件 | 行数 | 实读区间 | 覆盖 |
|---|---|---:|---|---|
| 1 | `ai/tools/AiTools.java` | 1,052 | 1–1,052 | 10/10 |
| 2 | `ai/tool/ToolRegistry.java` | 486 | 1–486 | 10/10 |
| 3 | `ai/tool/ContextBuilder.java` | 411 | 1–411 | 10/10 |
| 4 | `ai/tool/WizardActionGuard.java` | 393 | 1–393 | 10/10 |
| 5 | `ai/tool/SheetWriteGuard.java` | 361 | 1–361 | 10/10 |
| 6 | `ai/tool/FrontendToolGuard.java` | 226 | 1–226 | 10/10 |
| 7 | `ai/tool/ToolResultLimiter.java` | 315 | 1–315 | 10/10 |
| 8 | `ai/tool/ToolResultEnvelope.java` | 256 | 1–256 | 10/10 |
| 9 | `ai/tool/SheetAudit.java` | 269 | 1–269 | 10/10 |
| 10 | `ai/resultview/ListTasksResultViewProvider.java` | 119 | 1–119 | 10/10 |
| | **合计** | **3,888** | — | **3,888/3,888** |

为判定"效果"而额外只读的最小外围（不计入 FA1 行数）：`SheetSwitches`、`ToolScope/ToolMeta/ToolRisk/ToolChannel`、`PromptDelimiters`、`TruncationContract`、`ResultViews`、`OverriddenToolCallback` 调用面、`SpToolCallingManager:380-436/1240-1330/1520-1562/1770-1796`、`SseChatEmitter:1242-1294`、`AiController:600-718/1451-1475`、`AiProperties.verifyResultBudget:348-393`、前端 `types/condition.ts`、`views/ExportWizardView.vue:609-716/1454-1483/763-772`、`utils/frontend-tools.ts:1088-1099`。

## 1. 十个文件与既有条目的关系（结论先行）

| 文件 | 结论 | 与 canonical 的关系 |
|---|---|---|
| `AiTools` | 34 个 `@Tool` 均为桥接/哨兵桩；4 个 DANGER 走确认门，3 个向导写 + 表格/UI 写走 FRONTEND；参数非法一律抛异常回填（R-137） | 确认 PK-11（四维元数据全部来自注解）、PK-14、BZ-09；新见 **N3** |
| `ToolRegistry` | 披露/执行双出口共用 `scope AND sheetAllows`，但 `forContext` 与 `matchesContext` 在 **空 scope 数组** 上不等价；meta 缺失一律放行 + 最宽默认 | 确认 PK-11（逐行确认其 88/202-216/317-327/371-376/415-436 引证）；**补充**一处结构性分叉（当前无实例）；CI-14 确认 |
| `ContextBuilder` | 5 键白名单 + 单值 300/总额 800 + 定界中和；页面选中集以 `extra.pageId` 为准 | 确认 ID-06 / PK-17（FORMAT 缝隙）；无新 |
| `WizardActionGuard` | 未知键/空 codes/小写 mode/未知 defCode/未知算子均 fail-closed；但**不校验"空字符串值"与"同字段重复条件"** | **新见 N2**（S3/C） |
| `SheetWriteGuard` | 按真名分支（撤销/替换/更新/归一化），六项校验；`commit` 用 `asBoolean` 弱解析 | 确认 PK-11 家族之外的闸门有效；两处小缺口入 minors |
| `FrontendToolGuard` | 两道闸的接口面；`structuredResult` 按"解析成功"判结构化 | 无新（4 个实现中 2 个仍走 1 参形状推断，但均为单工具/无分支，无实害） |
| `ToolResultLimiter` | 行/字符双闸、标记计预算、结构化整体标记 | 确认 PK-17（notice↔returnedChars）；**第三条截断面** 见 N1 |
| `ToolResultEnvelope` | 五字段 + 定界外壳 + 等长中和（只中和 `</tool_result>`） | 确认 PK-17；N1 体现同一信封可报 `truncated=false` 而正文已被上游切 |
| `SheetAudit` | 白名单在**入口**（AiController:662 / ConfirmGate:1437）已施加；计数为进程级 | 确认 RT-16（D，已裁决边界）；无新 |
| `ListTasksResultViewProvider` | 只对成功结局出视图（`SpToolCallingManager:1543`）；行数 20 与工具同源；异常→null | 确认 BZ-09（两遍全量）；**新见 N4**（历史重建取实时库，S3/C） |

## 2. 新增条目（代码可证，不新开 canonical 编号，用 FA1-N*）

### FA1-N1｜回灌闸的字符截断是"第三个截断面"，不接统一截断字段；`cancelled=true` 时还会按字符切结构化 JSON
- **Sev S4 / Cert C**（可达性窄）
- **path:line**
  - 截断与字段：`ai/web/AiController.java:638-655`（把 `result` 切成 `callbackMaxChars` 以内并内嵌中文标记）、`:710-716`（`truncated/originalChars/returnedChars/notice` **只写进 HTTP 响应体**）
  - 结构化豁免只覆盖"拒绝"不覆盖"截断"：`:622`（`!cancelled` 才做 `structuredOversize`）vs `:638`（无 `cancelled`、无结构化判定）
  - 模型侧：`ai/gate/SpToolCallingManager.java:1793-1795` → `ToolResultEnvelope.toModelJson`
  - 帧侧：`ai/run/SseChatEmitter.java:1279-1281`（对**已截短**的 `result` 重跑 limiter ⇒ `truncated=false`）
- **触发**：任一 FRONTEND 通道工具回灌**非 JSON 文本 > `app.ai.result-budget.callback.max-chars`（默认 8192，`application.yml:379`）**；或 `cancelled=true` 且回灌 JSON > 8192。
- **效果**：①信封与 `tool_result` 帧的 `truncated/originalChars/returnedChars` 全部报"未截断"（`originalChars == returnedChars == 截断后长度`），唯一信号是正文里的中文标记；`ToolResultLimiter:14-20` 承诺的"帧侧必须覆盖模型侧"对这条路径不成立。②`cancelled=true` 时 JSON 会被按字符切坏，与同端点"结构化结果绝不按字符截"（`:615-618`、`FrontendToolGuard:107-133`）自相矛盾。③前端卡面拿到的 `result` 就是截断后的文本，`ToolResultLimiter:17-20` 的"帧里的 result 仍是全文"对前端工具不成立。
- **可达性**：当前 handler 的文本结果普遍远小于 8192（`ui_snapshot` 2000 字符上限、向导 handler 为短句）；需超长错误文案/码清单才命中 ⇒ 与 BZ-20 同类的"窄可达"。
- **测试**：`ToolResultDualChannelContractTest` 只覆盖后端工具（`out.toolResult`）；无任何用例走 `AiController` 回灌截断后再断言信封/帧字段（`AiToolsToolResultPlainTextTest` 不含超限）。

### FA1-N2｜向导动作闸只判"有没有值/有没有键"，不判"值是否为空、键是否重复"⇒ 向导回写时条件被静默弱化到"无条件 = 导出全部"
- **Sev S3 / Cert C**（触发=模型产出该类入参；落地=一次步进/发起导出）
- **path:line**
  - 闸门放行：`ai/tool/WizardActionGuard.java:327-341`（只拒 `null/缺失`：`value` 为空串 `""` 直接通过）、`:277-325`（对 `conditions.fields` **不做 fieldCode 去重/唯一性**；`codes` 反而有去重 `:179-182`）
  - 前端把它压成"每字段一条"：`frontend/src/types/condition.ts:413-441`（`model.fields[fieldCode] = …` 覆盖式写入；GTE+LTE 合并要求**大小写精确** `:422-423`）、`:166-196`（`value.trim()===''` 的条件被丢弃 `:187-189`）
  - 回写：`frontend/src/views/ExportWizardView.vue:1464`（AI handler 用压平后的草案覆盖 `draftModels`）、`:686-700`（`persistStepData` **从草案重建**再 `PUT setCondition`）、`:772`（`startExport()` 在创建 EXPORT 作业前必调 `persistStepData`）、`:614`（`goStep` 同样先持久化）
- **触发**：模型对 `set_condition` 发出 ①同一 `fieldCode` 的两条条件（如 `EQ` + `NE`，或小写 `gte`+`lte`），或 ②非无值算子的 `"value": ""`；随后用户/模型推进步骤或发起导出。
- **效果**：`set_condition` 回执说"已设置并落库"（`:1466-1477`，摘要用的是**未压平**的条件），但 `startExport` 前的重写只落第一条 / 或把空值条件整条丢掉 ⇒ `conditionJson` 变 `{}` ⇒ **导出全量**。这是闸门自身明文要堵的"空条件 = 导出全部"（`WizardActionGuard:39-42`）从另一个入口复发；用户批准的是"按 A 过滤"的语义，作业执行的是"全表"。
- **测试**：`WizardActionGuardTest` 只覆盖 `value` 缺失（`:120-122`）与 `IN` 非数组（`:123-125`），**无空串值、无同字段重复、无 gte/lte 小写**；前端 `draftModelFromCondition`/`persistStepData` 无"重复字段条件"往返用例。

### FA1-N3｜`list_tasks` 的"前 20 条"承诺与 `model.max-rows` 之间**没有启动不变量**
- **Sev S4 / Cert R**（需把 `app.ai.result-budget.model.max-rows` 配到 1..19）
- **path:line**：`ai/tools/AiTools.java:165-169`（javadoc 明写"必须 < max-rows(200)，否则告知成了假话"）、`:182-183`（"共 N 条，仅显示前 20 条"）；`ToolResultLimiter:154-160`（行闸在读取前施加）；`AiProperties:357-360`（只校验 `>= 0`，无下界）；`application.yml:352`（200）
- **触发**：运维把 `max-rows` 调到 < 20（合法配置）。
- **效果**：工具自述与工具描述里的"最多前 20 条"永远到不了模型，模型看到的是裁剪器的"仅前 N 行"；`LIST_TASKS_MAX_ROWS` 是编译期常量，无配置面联动、无校验、无用例。
- **测试**：`AiToolsListTasksTruncationTest` 锁 20/21 边界，但**不**断言与 `model.max-rows` 的关系（`ResultBudgetPropertiesTest` 只断言默认 200）。

### FA1-N4｜`/history` 的 `list_tasks` 结果视图**每次重建都重新取实时库** ⇒ 归档卡面 ≠ 当时工具结果
- **Sev S3 / Cert C**
- **path:line**：`ai/resultview/ListTasksResultViewProvider.java:69-83`（`build(toolCallId, toolName, resultText)` **完全不看 `resultText`**，`taskService.findAll()` 重取；`:77` 前 20 行、`:83` 传 `tasks.size()`）；`ai/web/AiController.java:1451-1467`（`historyResultView` 在**每次 `/history`** 对每个历史 `list_tasks` 响应重新 `provider.build`）；对照帧路径 `SpToolCallingManager:1543-1553`（只在成功结局、执行后即刻构建）
- **触发**：一次 `list_tasks` 之后任务被新建/改名/推进状态（或删），随后刷新/重开对话读 `/history`。
- **效果**：归档表格显示**今天**的任务行与状态，而同一轮的 assistant 文本与台账记录指的是旧清单 → "刷新看到另一个答案"（与 RT-20 同类体验，但根因是展示层重投影，非 fencing）；`meta.truncated{shownRows,totalRows}` 也随之重算，"共 N 条"可与模型当时所说不同。附带：每次 `/history` 对每个历史 `list_tasks` 各跑一次全量 `findAll()`（BZ-09 的放大器）。
- **测试**：`ListTasksResultViewProviderTest` 全部是单次 `build` 的静态 mock，**无**"两次 build 之间库变化"或"history 与当时 resultText 对账"用例。

## 3. 对既有条目的更正

| # | 既有条目 | 更正 | 依据 |
|---|---|---|---|
| CORR-1 | **PK-17** 子句"`ResultViews` 未消毒"（`ResultViews:119-136`） | **部分否证**：行值/列标签/options 在出端已消毒（`ResultViews:109-114, 226-256`，禁形 `:67-68`）；残留未消毒的只有 `id`（provider 拼 `"rv-"+toolCallId`）与 `meta.source`（常量），均非内容面。另有一处**语义选择**而非缺陷：`title` 走 `optionalText:212-217`（命中禁形即置 null，不 sanitize）⇒ 标题/i18n key 会静默消失而非被替换 | 逐行 |
| CORR-2 | **PK-17** 子句"`notice` 与 `returnedChars` 不一致" | **确认，并给出数值关系**：`ToolResultLimiter:162-176`，`notice` 里的"返回 X 字符"传的是**正文**长度 `kept.length()`，而记录/信封的 `returnedChars = visited.length()`（正文 + 标记）⇒ 差值恒 ≈ 标记长度。测试只断言 `returnedChars == text.length()`（`ToolResultLimiterTest:135`），**从不**断言 notice 里的数字 | 逐行 |
| CORR-3 | **PK-17** 子句"截断前全文 `readTree`"（`ToolResultLimiter:146`） | **确认**：`TruncationContract.looksStructured:107-109` 对**整段原文** `readTree`；超大结构化结果会先全文解析再判档 | 逐行 |
| CORR-4 | **CI-14**"`ToolScopeTagSpaceGuard` 下限 26 vs 实际 37" | **确认**：`ToolScopeTagSpaceGuardTest:46` `MIN_DECLARATIONS = 26`、`:75` `hasSizeGreaterThanOrEqualTo`；注释 `:45` 亦写"当前 26 个"（陈旧）。删掉 11 个 `@ToolScope` 仍绿 | 逐行 |
| CORR-5 | **PK-11**"元数据缺失 ⇒ 四维最宽默认" | **确认（逐行）**：`metaMap` 只扫 `cls.getDeclaredMethods()`（`ToolRegistry:88-113`），`callbackMap` 来自 `MethodToolCallbackProvider`（`:116-119`）⇒ 继承的 `@Tool` 有回调无 meta；此时 `forContext` 直通（`:321-322`）**并跳过 sheet 开关**、`matchesContext` 放行（`:372-375`）、`riskOf→READ`（`:415-418`）、`channelOf→BACKEND`（`:424-427`） | 逐行 |

## 4. 分支映射（按文件，可执行分支 → 归属）

> 计数为"可执行判定点"；每项都落到 ①既有 canonical ②FA1-N* ③"良性/无用户效果"。

| 文件 | 判定点数 | 映射摘要 |
|---|---:|---|
| `AiTools` | 47 | 4 个 `start_*` 失败重抛 → R-137/`SpToolCallingManager.executeOnce`（既有，`AiToolsThrowPathTest`）；4 个枚举/`valueOf` 非法 → `ConflictException`（良性，测试未覆盖但文案确定）；`findByCode/findById` 不存在 → `ResourceNotFoundException`（`DefinitionService:46-49`、`TaskService:81-86`、`JobService:268-271`，无 NPE）；`get_workspace_state(taskId=null)` → 固定文案（良性）；`list_tasks` 0/≤20/>20 → BZ-09 + **N3**；26 个哨兵桩 → `ToolRegistryDisclosureTest:187-264` |
| `ToolRegistry` | 22 | meta 命中/缺失 → **PK-11**；`sheetAllows` 开/关 → `ToolRegistrySheetGate*`（既有）；`uiRegionCount` 缺失/0/正/非数字 → `ToolRegistryUiScopeTest`（既有）；YAML 覆盖：指向未注册工具→启动失败 `:266`、`status!=published`→跳过 `:269-271`、schema 漂移→`ToolSchemaReconciler` 抛（`:285`）、无覆盖源→return（`:257-259`）；**空 scope 数组**：`forContext` 不披露 vs `matchesContext` 放行（`:319-326` vs `:373-375`）→ 当前**无实例**，见 §6-①；`normalizeSchema` null→原回调（`:235-237`，`normalize` 实测不返回 null） |
| `ContextBuilder` | 31 | 任务加载异常→"(加载失败)"`:194-196`（良性，但**吞掉**了因果，仅提示）；`pageDefs` 三态→`ContextBuilderSelectedDefsTest`；extra 白名单/截断/省略→`ContextBuilderExtraTest`；`safeField` 控制/代理/空白→`ContextBuilderDelimiterInjectionTest`；未知 page 不回显→同左；定界中和 → **PK-17 FORMAT 缝隙**（见下） |
| `WizardActionGuard` | 29 | 形状/未知键/空 codes/小写 mode/未知 defCode/未知字段/未知算子/值缺失/IN 非数组 → `WizardActionGuardTest`（既有）；**空串值、同字段重复** → **N2**（无用例）；`inspectResult` 恒 accept（`:127-131`，设计） |
| `SheetWriteGuard` | 33 | 撤销额外键 `:124-137`（**无用例**）；scope/match/rules/preview/commit/predicate 各支 → `SheetWriteGuardTest`（既有，缺 `match` 为对象、`commit` 为字符串/数字、重复列）；`inspectResult` 共 N 尾行 → 有用例 |
| `FrontendToolGuard` | 8 | 2 参/4 参默认转发 `:59-61,83-85`（良性）；`structuredResult` 表格四件/JSON 对象/数组/前导空白/解析失败 → `AiController.structuredOversize`（既有）；`retryable()` 仅 `_RESULT_REJECTED`（`AiController:1885` 唯一消费，良性） |
| `ToolResultLimiter` | 26 | 结构化不切 `:146-151`→`ToolResultDualChannelContractTest`；行/字符/标记预算 `:154-176`→`ToolResultLimiterTest`；`maxCharsFor` 五档 `:189-202`（per-tool/注册表缺失/未知工具/默认/兜底）→ 部分有用例；`truncated()` 只认 `TRUNCATION_MARKER`（`:185-187`）——结构化截断时恒 false（**无生产消费**，`KbTools:284` 除外，良性）；notice 数字 → **CORR-2** |
| `ToolResultEnvelope` | 15 | 工厂 7 个 → 既有；`targetOf` 空/非对象/解析失败 → null（良性）；`toModelJson` 中和/外壳 → `ToolResultEnvelopeDelimiterInjectionTest`；`fromJson` 剥壳/解析失败 → `CodedIllegalArgumentException`（既有）；`truncated=false` 的上游失真 → **N1** |
| `SheetAudit` | 17 | `sanitize` 数值/字符串/NaN/Inf/负/planId 64/previewLevel → `SheetAuditTest`；`record` 缺 meta 直接 return `:155-157`（`pendingWithoutMetaIsIgnored` 已把该行为写成期望）；D2 才写台账 `:185-187`；缺 sessionId 跳过 `:220-224` → RT-16（D） |
| `ListTasksResultViewProvider` | 8 | 非 `list_tasks`→null、0 行→empty、>20→partial、异常→null → `ListTasksResultViewProviderTest`；预算 `:92-99` → §6-④；忽略 `resultText` → **N4** |

## 5. 测试真实性（本组 10 文件相关）

1. **把"数字自洽"当成"告知自洽"**：`ToolResultLimiterTest:53,135,187,213` 只断言 `returnedChars == text.length()`，因此 PK-17 的 notice 数字偏差**结构上不可能被这些用例发现**。
2. **FORMAT 缝隙无用例**：`ContextBuilderDelimiterInjectionTest:245-263`、`ToolResultEnvelopeDelimiterInjectionTest:82-95` 覆盖 ASCII 空白 / NBSP / U+2007 / U+3000 / 控制字符 / 插在标签中间的控制字符，但**没有** `\p{Cf}`（U+200B/U+00AD/U+FEFF）。`PromptDelimiters:33` 的 `GAP = [\s\p{Z}]*` 恰好**不含** `Cf` ⇒ `</tool_\u200Bresult>` 不被中和。属 PK-17 已登记项，此处补"测试缺口"证据。
3. **披露/执行"逐工具一致"只对 9 个表格工具 + 7 个上下文成立**（`ToolRegistrySheetGateTest:98-120`）；对"空 scope 数组"这一结构性分叉**无覆盖**，也无 `callbackMap ⊆ metaMap` 断言（与 PK-11 同）。
4. **注释与实现不符**（不影响断言有效性，但会误导后续审计）：`ToolRegistryDisclosureTest:60` 写"仅 confirm_step = WRITE"，实为 3 个（`WIZARD_WRITE_TOOLS:99-100`）；同文件 `:180` 写"scope 一律 `*`"，而 7 个 `ui_*` 的 scope 是 `{"ui:on"}`（其断言为真只因为 `AiContext.empty()` 缺 `uiRegionCount` 而 fail-open）。
5. **闸门用例集合只覆盖"形状"**：`WizardActionGuardTest` / `SheetWriteGuardTest` 无"空串值""同字段重复""`commit` 为非布尔""`match` 为对象"等语义空值用例 —— N2 与 minors 的触发面全部无锁。
6. **结果视图用例是单次静态快照**：`ListTasksResultViewProviderTest` 的 mock 只喂一次 `findAll`，无法暴露 N4（跨时间重投影）；注释里的"kill 清单"只覆盖"改成解析文本"和"异常穿出"，不覆盖"重取库"。

## 6. 未解分支（无现发实例 / 读不出来的边界）

| # | 分支 | 状态 | 为什么保留 |
|---|---|---|---|
| ① | `@ToolScope({})`（空数组）：`forContext` 不披露、`matchesContext` 放行（`ToolRegistry:323` vs `:373-375`） | **潜在**，当前 0 实例（全仓无空 `@ToolScope`） | 注释自述"两个出口结构上无法分叉"（`:313-315`）在此形态下不成立；新增 `@ToolScope({})` 即得"未披露但可执行"。无测试 |
| ② | `L-94` 的残余漏判：`isDeclaredToolBean:152-163` 对"手工注册单例/工厂声明类型解析不出"的 bean 返回 false ⇒ 工具 bean 创建失败仍被当噪音跳过（`:69-79`） | **潜在**，当前全部为 `@Component` 直接声明 | 与 L-94 的"少工具必须启动失败"承诺存在缝；无实例、无测试 |
| ③ | `ListTasksResultViewProvider.viewBudget:92-99`：`max(4096, frameBytes-4096)` | **配置条件**（`ui.max-frame-bytes` ∈ [1KiB, 8KiB)，`verifyResultBudget:378` 允许） | 此时"不可截字段"预算可**超过**单帧预算，与 `ResultViews:28-31` 的自述相反；`FrameSizeLimiter` 只能报 `frame_limit_unreached` |
| ④ | Java 默认 locale 参与大小写：`AiTools:107,270`、`WizardActionGuard:313,319`、`ConditionEvaluator:48,89` 均裸 `toUpperCase()` | **条件**（JVM `user.language=tr/az` + 小写输入） | 表现为"合法参数被判非法"（`import`→`İMPORT`、`in`→`İN`）；无测试 |
| ⑤ | 表格开关是**启动期本实例配置**（`SheetSwitches` 持有 `AiProperties.Sheet`），无跨实例一致性 | **多实例边界** | 无粘滞 LB + 滚动发布期间，同一工具在不同实例的披露/执行结论不同；`ToolOutcome:69-70` 的"重试同一调用只会得到同一个结论"在该窗口内不成立。FINAL 未登记；无 CI 拓扑可测 |
| ⑥ | `SheetWriteGuard.checkSet:301-304` 不要求 `mode=literal` 带 `value`、不校验 `columnCode` 属该定义、不拒重复列 | **潜在** | 唯一补偿是前端预览卡；与 N2 同族（"只判有无，不判语义"），未单列条目以免重复计数 |
| ⑦ | `ContextBuilder.pageSelectedDefs:281-295` 以 `extra.pageId` 而非 `ctx.page` 判"是否拥有选中集" | **客户端可控** | 同一请求里 `page=tasks` + `extra.pageId=export` ⇒ 提示词"页面：任务中心"却写"已选配置项（来源：页面实时勾选）"；属于数据面自述不一致（客户端就是本用户），无越权，未升级为条目 |

读不出来的边界（不以静态结论代替）：真实前端 `setConditions` 的**并发**改写（用户同时手改表单 vs AI 回灌）、`el.click()` 对隐藏控件的真机语义（决定 FE-32 强度）、真模型是否稳定产出 N2 的触发形态、真 Cluster 下 `/history` 重投影的时序。

## 7. 一句话总结

FA1 的 3,888 行**全部逐行读完**：工具定义/披露/守卫/截断/结果视图在单实例 happy path 上与既有 canonical 一致（PK-11、PK-17、RT-16、BZ-09、CI-14 均**确认**，其中 PK-17 的"`ResultViews` 未消毒"子句被**部分否证**）；真正新增的代码可证问题共 4 条 —— **N2（向导条件闸的"存在性 vs 语义空值/重复字段"⇒ 导出范围静默放大，S3/C）** 最值得派工，**N4（历史卡面重取实时库，S3/C）** 次之，**N1（回灌闸第三截断面，S4/C，窄可达）** 与 **N3（`list_tasks` 20 行无启动不变量，S4/R）** 属一致性/可观测性。另有 7 处未解/潜在分支（§6），其中"空 scope 数组双出口分叉"与"L-94 残余漏判"是注释承诺与实现之间**当前无实例**的缝。**未运行任何测试，未做任何修复，也不给出"已修/可用"保证。**
