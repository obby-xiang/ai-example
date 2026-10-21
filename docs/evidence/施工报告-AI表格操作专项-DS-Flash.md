# 施工报告 · AI 表格操作专项（8 工具 + 预览三档 + 数据最小化）

## 0. DC-19 过程自证（首页）

| 项 | 值 |
|---|---|
| 施工笔 agentId | `agent-1365`（`profile.bind` 单行取自 `…/agents/agent-1365/wire.jsonl`） |
| modelAlias | `DeepSeek/deepseek-flash` |
| profileName | `coder` |
| thinkingEffort | `high` |
| 日期 | 2026-10-21 |
| 分支 / 基点 | `feature/table-ai` @ `a5539c2`（worktree `../wt-table-ai`，**未 commit**） |
| 设计依据 | `docs/research/设计-AI表格操作专项-2026-10-21.md`；`docs/research/调研-AI操作在线表格能力业界惯例-2026-10-21.md` |

范围红线（设计稿 §1.1/§1.2）：8 工具 + 预览三档 + 业务行数据不出浏览器 + 结构变换不做；
公式/格式/排序筛选/去重执行/跨 sheet 明确不做。批①（读侧）+ 批②（写侧 + 撤销 + 预览/确认）**同批落盘**。

## 1. 测试矩阵（先行登记）

维度：参数闸 / scope / 尺寸闸 / 投影脱敏 / 不越权 / 预览-确认-取消 / 指纹 / 幂等 / 失效恢复 / 契约终态。

### 1.1 前端（`frontend/tests/`）

| ID | 用例 | 维度 | 期望 | 落点 | 结果 |
|---|---|---|---|---|---|
| T-01 | 25 行 + `limit=20` | 尺寸闸 | 回 20 行 + `共 25 行，本次回 20 行` | `sheet-tools-read.spec.ts` | 绿 |
| T-02 | `limit=50` 越界 | 参数闸 | `{ok:false}` 人话（无 JSON） | 同上 | 绿 |
| T-03 | 20 行但 5,000 字符 | 尺寸闸 | 按字符截断，标记含 `原 20 行` / `字符`；正文 ≤2000 | 同上 | 绿 |
| T-04 | 未截断场景 | 口径 | 末尾仍带 `共 N 行` | 同上 | 绿 |
| T-05 | 3 列投影但表有 12 列 | 投影 | 只回 3 列 + `另有 9 列未显示` | 同上 | 绿 |
| T-06 | 第 21 行起的种子词 | 脱敏 | 回灌文本零命中 | 同上 | 绿 |
| T-07 | 表头 `名 称` | 诊断 | 疑似映射（空白差异） | 同上 | 绿 |
| T-08 | 5 处必填缺失 + 2 组主键重复 | 聚合口径 | 按字段聚合 + 前 N 条 + `共 7 处` | 同上 | 绿 |
| T-09 | 聚合 37 组 | 尺寸闸 | 20 组 + `共 37 组，本次回 20 组` | 同上 | 绿 |
| T-23 | predicate 11 算子 × 空值 × IN | 求值器 | 前端 `matchesCondition` 命中集合 == 后端同夹具 | `sheet-condition-parity.spec.ts` + 后端 `ConditionEvaluatorParityTest` | 绿 |
| T-27 | `normalize_column(commit=false)` | 只读档 | 写调用零次；回各规则影响格数 + 样例 | `sheet-tools-read.spec.ts` | 绿 |
| T-10 | 页面能力未注册 | 三级兜底 | `degraded` + “请先打开导出结果页或上传页” | `sheet-tools-registry.spec.ts` | 绿 |
| T-18 | 描述与常量同源 | 防漂移 | 8 个 description 均含“20”，常量=20 | 同上 | 绿 |
| T-19 | `FRONTEND_TOOLS` 名单 | 注册表 | 2 → 10；名字/风险/scope 齐全 | 同上 | 绿 |
| T-30 | `delete_rows` 调用 | 可用性 | `unavailable` + “当前不支持按条件删除行” | 同上 | 绿 |
| T-11 | `replace_values` 预演 | 不回灌 | 建预览、`submitFrontendToolResult` 零调用 | `sheet-tools-write.spec.ts` | 绿 |
| T-12 | 点“应用” | 写 | `writeCells` 被调用；回灌含“已应用…用户已确认…未保存…共 N 格” | 同上 | 绿 |
| T-13 | 点“取消” | 拒绝 | 写调用零次；`cancelled=true`；卡面“未改动任何单元格” | 同上 | 绿 |
| T-14 | 3 万行 / 3 万格 `preview=count` | 三档预览 | `level=large`；未勾选不写；勾选后分块写；回灌不含行值 | 同上 | 绿 |
| T-15 | 只读壳 | 不越权 | `任务已结束…`；写零次 | 同上 | 绿 |
| T-16 | 应用 → 撤销 | 可核对 | 写回原值；再撤一次 → “没有可撤销的 AI 修改” | 同上 | 绿 |
| T-17 | 刷新后无本地 plan 的 PENDING | 失效 | “预览已失效”卡 + 放弃按钮提交 `cancelled=true`；无“应用” | `sheet-preview-render.spec.ts` + `ai-panel-parts-render.spec.ts` | 绿 |
| T-24 | 无选区且未给 `scope` | 参数闸 | 拒绝并提示先选行；不默认整表 | `sheet-tools-write.spec.ts` | 绿 |
| T-25 | 预览后手改一格 | 指纹 | 整批拒绝 + 写零次 | 同上 | 绿 |
| T-26 | 非法正则 | 参数闸 | `{ok:false}` 人话，不建卡 | 同上 | 绿 |
| T-29 | `fillDown` | 补值警示 | 卡面含“用其他行的值补齐空单元格…请确认” | 同上 | 绿 |
| T-31 | 预览不落盘 | 不落盘 | 预览期 adapter 写调用零次（高亮不入 cell 样式） | 同上 | 绿 |
| T-32 | `wildcard` 与 `regex` 同给 | 互斥 | 参数拒绝 | 同上 | 绿 |
| — | 预览卡组件 | UI 语义 | 无 plan ⇒ 失效卡；大批量勾选前“应用”禁用 | `sheet-preview-render.spec.ts` | 绿 |

### 1.2 后端（`backend/src/test/java/…`）

| ID | 用例 | 维度 | 期望 | 落点 | 结果 |
|---|---|---|---|---|---|
| T-19b | 工具数 18 → 26；前端通道 7 → 15；登记工具 2 → 10 | 注册表 | 读 READ / 写 WRITE；披露只在 EXPORT/EXPORT 与 IMPORT/UPLOAD | `ToolRegistryDisclosureTest`（+3 用例） | 绿 |
| — | 8 个哨兵桩 | 通道 | 任何直接调用只返回 `FRONTEND_STUB` | 同上 | 绿 |
| T-20 | 写工具帧不带 `timeoutSeconds` | 无时限 | 5 名返回 null；`navigate_to` 仍返回 120 | `ConfirmGateHumanWaitTest`（新） | 绿 |
| T-21 | `SheetWriteGuard` 拒绝非法入参 | 入参闸 | 不入卡、不下发；原因清单逐条 | `SheetWriteGuardTest`（新） | 绿 |
| T-28 | `normalize_column(commit=true, rules=['date'])` | 越权 | 拒绝（只允许 trim/case/width） | 同上 | 绿 |
| T-23b | 11 算子 × 空值 × IN × 日期 | 求值器 | 与前端同夹具命中集合一致 | `ConditionEvaluatorParityTest`（新） | 绿 |
| T-22 | E2E 主用例 29 → 31 | 端到端 | — | `scripts/verify-e2e.ps1` | **未执行**（施工纪律禁起服务/harness；脚本未改，见 §3-9） |

## 2. 红测留痕

### 2.1 前端（实现前，`<SCRATCH>/wt-table-ai-red1.log`）

```
FAIL  tests/sheet-condition-parity.spec.ts
Error: Failed to resolve import "@/utils/sheet-tools" from "tests/sheet-condition-parity.spec.ts". Does the file exist?
FAIL  tests/sheet-tools-read.spec.ts   （同上）
FAIL  tests/sheet-tools-registry.spec.ts （同上）
FAIL  tests/sheet-tools-write.spec.ts (12 tests | 12 failed)
TypeError: store.openWritePreview is not a function
```

同一次运行：`sheet-tools-write` 12 条全部因 `openWritePreview/confirmWritePreview/cancelWritePreview` 不存在而红；
`sheet-tools-read/registry/condition` 因纯函数模块不存在（import 解析失败）而红。

### 2.2 后端（实现前，临时回滚 `AiTools.java` / `ConfirmGate.java` 到 `a5539c2`，`<SCRATCH>/wt-table-ai-be-red4.log`）

```
[ERROR] ToolRegistryDisclosureTest.java:[126,27] 找不到符号
  符号:   方法 readSheetSummary(java.lang.String,java.lang.String,java.lang.String[],<nulltype>,<nulltype>,int)
[ERROR] ... aggregateSheet / inspectColumns / reviewCells / normalizeColumn / replaceValues ...
```

恢复实现后 `mvn -o test` 全绿（§4）。红测与绿测为同一批断言，未删改任何期望。

## 3. 自决清单

1. **确认落点 = 前端预览卡（设计 §7-裁 1 选项 A）**：写工具在 `stores/ai.ts` 的 `frontend_tool_request` 分支特判，不进 `runFrontendTool`（不受 60s 预算），用户点“应用/取消”后才回灌；取消沿用既有 `cancelled=true` ⇒ `FRONTEND_CANCELLED`，零新帧类型。
2. **`normalize_column` 同名双档**：`needsSheetPreview(name,args)` 只在 `commit===true` 时进预览；`commit=false` 与读工具同路（执行器直接出建议），未拆第二个工具名（设计 §7-裁 8 方案 i）。
3. **`case` 是 Java 关键字**：后端工具参数名用 `caseMode`（record/方法签名无法使用 `case`）；前端同时接受 `caseMode` 与 `case`（`args.caseMode ?? args.case`），Schema 与实现一致，避免模型猜键名。
4. **`undo_last_sheet_edit`**：按设计列入 `SHEET_WRITE_TOOLS` 与后端 `HUMAN_WAIT_FRONTEND_TOOLS`（等待无上限），但**不进预览**（§3.1 U1 “无二次确认”），由执行器立即应用反向补丁。
5. **写侧分层上限按 §7-裁 6 方案 i**：`>200 行` 或 `>2000 格` ⇒ `level=large` + 勾选“我了解影响范围”；`>100,000 格` 硬拒绝（不静默截断、不自动拆批）；应用按 500 行分块 `writeCells`。
6. **行指纹**：取主键列（无主键取首列）前 2 列拼接；任一目标行不符 ⇒ 整批拒绝并回“表格在预览期间已变动…请重新发起”。
7. **`delete_rows` 未上线**：显式返回 `unavailable`（不落“未知前端工具”模糊文案）；批③ 待裁。
8. **预览高亮未做屏幕态绘制**：可核对性由卡内三档 diff 承担；`toXlsxBlob` 字节不受影响由“预览期零写”保证（T-31）。视觉高亮属 UI 验收面，未在本批承诺。
9. **T-22 E2E 未执行**：施工纪律禁起服务/harness，`scripts/verify-e2e.ps1` 与汇总文案未改（29 仍为现值）；留待主会话 UI/E2E 验收批。
10. **D2 授权记录**：`read_sheet_summary` 是本仓首个“业务行进模型”的通道（硬闸 20 行/2000 字符 + 投影 + 版本号）。按设计 §8 风险 1，合并前需一份 D2 授权记录；本笔未产出（治理前置，如实挂账）。
11. **`SheetWriteGuard` 的工具名推断**：`FrontendToolGuard#inspectArguments` 只收原文，故按入参形状（`search`/`set`/`rules`）推断细分工具；三者必填字段互斥，且事实源仍是逐项校验；另留 `inspectArguments(toolName, json)` 显式入口供测试。
12. **注释同批订正**（设计 §9.7 三处）：`stores/ai.ts` 的“前端工具没有 HITL”、`frontend-tools.ts` 的“7 个 FRONTEND / 2 个登记”、`ConfirmGate` 的“只有 generative_form 无上限”均已改为新口径。

## 4. 门禁数字（最终态）

| 门禁 | 基线 | 最终 | 结论 |
|---|---|---|---|
| 后端 `mvn -o test` | 939 tests / 147 suites | **961 tests / 150 suites，0 failures / 0 errors** | 绿（+22） |
| 前端 `vitest run` | 545 tests / 29 files | **584 tests / 34 files，0 failed** | 绿（+39） |
| 前端 `vue-tsc --noEmit` | 0 | **0** | 绿 |
| 前端 `vite build` | ✓ | **✓ built** | 绿 |
| `node scripts/ci/check-docs.mjs` | 6/6 | **6/6 断言绿** | 绿 |
| 断言只升不降 | — | 新增 61 条，未删改任何既有断言 | 合规 |

计数口径：后端 = `backend/target/surefire-reports/TEST-*.xml` 逐文件 `tests` 求和；前端 = vitest 汇总行。

## 5. 文件清单（worktree `<WORKTREE>`，均未 commit）

### 新增
- `frontend/src/utils/sheet-tools.ts` —— 纯函数内核：尺寸闸、11 算子求值器、scope 解析、读侧五档、三个 planner、应用/撤销、行指纹。
- `frontend/src/components/AiPanel/SheetPreviewCard.vue` —— 三档预览卡（计数 / 采样 / 全量翻页）+ 大批量二次确认 + 失效收口。
- `frontend/tests/sheet-tools-read.spec.ts`、`sheet-tools-registry.spec.ts`、`sheet-tools-write.spec.ts`、`sheet-condition-parity.spec.ts`、`sheet-preview-render.spec.ts`、`sheet-fixtures.ts`（假适配器）。
- `backend/src/main/java/com/example/configmgr/ai/tool/SheetWriteGuard.java` —— 六项入参校验 + 回灌四态复核。
- `backend/src/test/java/com/example/configmgr/ai/tool/SheetWriteGuardTest.java`、`ai/gate/ConfirmGateHumanWaitTest.java`、`data/service/ConditionEvaluatorParityTest.java`。
- 本报告。

### 修改
- `frontend/src/types/tools.ts` —— `FrontendToolName` +8、`FRONTEND_TOOLS` 2→10、`SHEET_TOOL_NAMES` / `SHEET_WRITE_TOOLS` / `SHEET_PREVIEW_TOOLS`、`ToolArgSpec` 增 object 类型。
- `frontend/src/types/ai.ts` —— `SheetPreviewLevel` / `SheetChange` / `SheetWritePlan`。
- `frontend/src/utils/frontend-tools.ts` —— `resolveSheetContext`、读工具/撤销执行器、`delete_rows` 明确不可用、对象参数校验、头注订正。
- `frontend/src/utils/tool-summaries.ts` —— 8 条中文摘要模板。
- `frontend/src/stores/ai.ts` —— `activePreview` / `stalePreviewToolCallId`、`openWritePreview` / `confirmWritePreview` / `cancelWritePreview` / `markPreviewStale` / `abandonStalePreview` / `finishSheetTool` / `previewText` / `setPreviewLevel`、帧分支特判、恢复路径 stale 收口、HITL 注释订正。
- `frontend/src/components/SpreadGrid.vue` —— `getSheetAdapter()`（批量读写、单次 `data-changed`、选区/表头/行数窄接口）。
- `frontend/src/components/AiPanel/AiPanel.vue` —— 预览段渲染分支、8 个中文名、stale/放弃接线。
- `frontend/src/views/ExportWizardView.vue`、`views/ImportWizardView.vue` —— `sheet.current` 页面能力（当前激活标签页、只读态、版本号）+ 卸载注销。
- `frontend/tests/ai-panel-parts-render.spec.ts` —— T-17 真挂载用例（+1）。
- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java` —— +8 `@Tool` 哨兵桩 + `SheetSetItem`；类注订正为 26 工具。
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` —— `HUMAN_WAIT_FRONTEND_TOOLS`（5 名）+ 注释订正。
- `backend/src/test/java/com/example/configmgr/ai/tools/ToolRegistryDisclosureTest.java` —— 前端名单 7→15、表格 8 工具的风险/披露/桩断言、scope 兜底全量对账扩到 26。
- `backend/src/test/java/com/example/configmgr/ai/tools/ToolScopeTagSpaceGuardTest.java` —— 扫描下限 15→26。

## 6. 契约与红线核对

- SSE 帧：**零新增/零字段变更**（`frontend_tool_request` / `frontend_tool_result` 原样；`timeoutSeconds` 缺省 = 无上限）。
- 回灌体：`POST /api/ai/frontend-tool-result` 请求体不变（取消仍走 `cancelled=true`）。
- 业务行数据不出浏览器：读侧只有 ≤20 行投影采样 + “共 N”；写侧回灌只有计数/规则/≤3 样例；`full-diff` 的 `changes` 只在浏览器卡渲染。
- 不越权：只读壳双守卫（执行器入口 `ctx.readOnly` + 应用前 `applySheetPlan` 再判）；拒绝即零写（`writeCells` 零调用）。
- 不落盘：AI 写只改内存；回灌固定带“未保存…可撤销”。
- 界面：无 emoji、无手写 style、未覆写 element-plus、未改 `tailwind.config`；未动 `docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`；未 commit / 未 push。

## 7. 遗留与待裁

1. **E2E**：T-22 两条主用例（聚合卡 / 条件更新卡）未执行，脚本计数仍 29；需主会话 UI/E2E 验收批补。
2. **D2 授权记录**：见 §3-10，合并前需治理件。
3. **预览高亮绘制**：卡内 diff 已可核对；把改动格在 SpreadJS 上临时上色（屏幕态、应用后清屏、不写 cell 样式）未做，属视觉验收面。
4. **刷新型 stale 与多实例**：stale 收口覆盖“刷新后无本地 plan”；同一页签多窗口并发预览未纳入（本仓 AI 面板为单页签会话）。
