> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1472` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：10451 B / 7019 字符 / 105 行；行指纹覆盖率（仓内 + 落盘语料）20.5%
> - 类别：**过程核验**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# S6 批2 · resultView 双通道 —— 施工交付

**profile.bind 自证**（读自己的 `wire.jsonl`）：`agentId=agent-1472, profileName=coder, modelAlias=DeepSeek/deepseek-flash, thinkingEffort=high, cwd=<LOCAL_WORK>`。基线 `HEAD=ea5dacc`，开工时工作树干净（仅既有 `?? data/`）。

## 一、改造点清单

### 后端（新增包 `com.example.configmgr.ai.resultview`，8 个类）
| 文件 | 作用 |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultView.java` | 契约值对象；`toMap()` 固定键序（确定性，无时间戳/随机 id） |
| `.../ResultViewKind.java`、`ResultViewStatus.java`、`ResultViewColumnType.java` | 三个封闭枚举 + `fromWire()`（未知返回空 = 降级不抛错） |
| `.../ResultViewColumn.java` | 列定义（只描述不携带值；`label` 走 i18n key） |
| `.../ResultViews.java` | **唯一造口 + 字段级校验**：200 行 / 50 列 / 256KB（或注入预算）三把尺、截断三件套（`partial` + `meta.truncated{shownRows,totalRows}` + 实际行数一致）、HTML/事件串/`javascript:` 出端消毒、`degraded()` 降级出口、必填 fallback |
| `.../ToolResultViewProvider.java` | 按工具名自认领的投影扩展点（与 `FrontendToolGuard`/`ToolPreflightService` 同体例） |
| `.../ListTasksResultViewProvider.java` | 第二个迁移对象：`list_tasks` → `kind=table`，行取自 `TaskService.findAll()` **结构化数据**（不反解结果文本），预算 = `min(256KB, app.ai.result-budget.ui.max-frame-bytes − 4KB)` |

改动（3 个既有文件）：
- `backend/.../ai/run/SseChatEmitter.java:791` —— `toolResult(...)` 加 5 参重载，`resultView` 走 `tool_result` **additive 键**；4 参调用面保留（帧调用面与既有用例逐字不变）。
- `backend/.../ai/gate/SpToolCallingManager.java` —— `@Autowired(required=false) setResultViewProviders(...)`；成功结局前 `resultViewOf(...)`（异常吞掉 + WARN；**无视图时走原 4 参调用**，保住既有 Mockito 断言）。
- `backend/.../ai/tools/AiTools.java:161` —— `LIST_TASKS_MAX_ROWS` package→public（单一事实源共享，零行为变化）。

### 前端（新增 6 个文件）
- `frontend/src/types/resultview.ts` —— §3 全字段契约类型（含 chart/form 占位）
- `frontend/src/utils/resultview.ts` —— 确定性构造器、`normalizeResultView`（白名单/规模收敛/降级）、`resolveJsonPointer`（RFC6901）、`resolveViewRows`、消毒；上限与占位符与后端**同值**
- `frontend/src/components/AiPanel/resultview/{ResultViewRenderer.vue, ResultViewTable.vue, ResultViewText.vue, registry.ts}` —— `kind→组件` 注册表（table/text 起步）、未知 kind/占位 kind → fallback 文案、`status=error` → `error.message`、`status=empty` → `empty.text`、原生 `<table>`（`caption` + `th scope="col"` + `aria-label`）、纯 Tailwind 无手写 style、无 emoji

改动（7 个既有文件）：`types/sse.ts`（`SseToolResultFrame.resultView?`，**帧类型集 16 零改动**）、`types/ai.ts`（`ToolPart/ToolPartPatch.resultView?`）、`utils/parts.ts`（帧→段落段，缺键不产生 undefined）、`stores/task.ts`（`onConflict(summary, issues?)`）、`stores/ai.ts`（`attachPublishConflict(jobId, summary, issues?)` 双写）、`stores/workspace.ts`、`AiPanel.vue:492`（渲染器接管 + 旧反解表降为 `v-else-if` 回退）、`locales/{zh-CN,en-US}.json`（`resultview.*` 双语 35+35 行）。

## 二、红→绿证据

| 阶段 | 红 | 绿 |
|---|---|---|
| 后端契约层 | `mvn -Dtest='…resultview.**' test` → `COMPILATION ERROR：找不到符号 ResultView / ToolResultViewProvider`（新 API 未落地） | 18/18：`ResultViewTest` 10、`ResultViewFrameTest` 3、`ListTasksResultViewProviderTest` 5 |
| 后端与既有用例冲突 | 全量 `ai.**` 首跑 3 红（`ResumeSkipCascadeTest` ×2、`ToolOutcomeExitTest`）—— Mockito 4 参 `toolResult` 校验 vs 5 参调用 | 改"无视图走原 4 参"后 → `1353/1353` 全绿 |
| 前端 | `Failed to resolve import "@/utils/resultview"` / `"…/ResultViewRenderer.vue"`；store 侧 2 红（`resultView` 未落段） | 新增 4 个 spec 共 **46** 断言全绿 |
| 前端回归 | — | `npm test` **979/979（68 文件）**，含既有 190 条 `ai-panel-parts-render` 原样全绿 + 新增 2 条 |

## 三、测试数字（最终态）

- 后端 `-Dtest='com.example.configmgr.ai.**'`：**1353 tests, 0 failures**（含 `FrameTypeSetFrozenTest` 2、7 个 conformance 套件全绿 ⇒ 16 帧冻结未破）
- 前端 `npm test`：**979 passed / 68 files**；`vue-tsc --noEmit`：**0**；`npm run build`：通过（`✓ built in 33.62s`）
- `node scripts/ci/check-docs.mjs`：**6/6 PASS**
- 全仓 `v-html` 计数 **21 → 21（不增）**；`innerHTML` 1 → 1；新组件零手写 style、零 emoji

## 四、迁移进度（§7.1 五步）

| 步 | 状态 |
|---|---|
| ① 契约落地（零行为变化，键默认不出） | **完成**：后端造口/校验、`tool_result` additive 键、缺键 = 旧帧逐字节一致（帧测试） |
| ② 渲染器骨架 | **完成**：`ResultViewRenderer` + 注册表；未知一切 ⇒ fallback，不白屏不抛错 |
| ③ 首个工具 = 发布冲突 | **完成**：`kind=table`（列=配置/行键/原因）、卡面停反解（`reasonText` 有视图时不再调 `conflictRows`）、旧反解表保留 `v-else-if` 回退、reason 整串一字未动 |
| ④ 逐工具迁移 | **部分**：`list_tasks` 的**载荷侧完成**（帧上真实携带 `resultView`，provider 单测钉住 20 行截断/空态/异常不出视图）；**卡面渲染未接**（归"普通工具卡"迁移，按 §7.1 顺序排在 GF/表格预览之后） |
| ⑤ 按覆盖率删旧卡 | 未做（按设计"跨一版无回退"才删） |

## 五、语义变更清单（单列）

1. **发布冲突卡明细表来源**：`reason` 文本正则反解 → `part.resultView` 渲染器（同卡位、同三列）。有视图时旧反解**零参与**（`conflictRows` 不被调用，且 `conflict-table` 零出现）。
2. **红字行文案判定**：有 `resultView` 时无条件取 `reasonHeadline`（首行）；无视图时维持原"能解析出明细才取首行"。
3. **`ToolPart` 新增可选字段 `resultView`**，随段持久化进 sessionStorage 镜像（刷新后表格仍在，有镜像往返用例）；旧段不产生该键。
4. **`tool_result` 帧**：`list_tasks` 成功结局多一个 `resultView` 键（additive；帧名、帧类型集、判别联合、conformance 白名单零改动）。
5. **`onConflict(summary, issues?)`**：加法传递第二参数（既有 1 参调用面与断言不受影响）。
6. `AiTools.LIST_TASKS_MAX_ROWS` 可见性 package→public（无行为变化）。

## 六、偏差与挂账（需裁决/后续）

1. **发布冲突视图由前端确定性构造**（`buildPublishConflictView`）。理由：发布是**异步作业**，冲突在作业终态后由前端拉 `/issues` 才发现，该时刻**没有 `tool_result` 帧**可挂视图。故设计稿 §4-规则 10「v1 只允许后端确定性构造」在此对象上是按字面未满足 —— 已用双端**同值同口径**（200/50/256KB、同一消毒正则、同一截断三件套）+ 两侧独立用例钉同一契约来兜。若要求严格后端构造，正解是 `/api/jobs/{id}/issues` 响应内由后端附带 view（新增端点字段，本批未做）。
2. `list_tasks` 视图行值来自 `TaskService.findAll()` 的**二次取数**（刻意不反解结果文本）；毫秒级窗口内 DB 变化会让"卡面行"与"结果文本"非同一次快照，但与 `data.inline` 自洽（§7.2-6 满足）。
3. **§6.3-4 未落**：`FrontendToolGuard.inspectResult` 未纳入 `resultView` 面 —— v1 没有 FRONTEND 通道视图（发布冲突出现在后端工具段上、`list_tasks` 是 BACKEND），该面留待任一前端工具出视图时再加。
4. **后端 `/history` 不回传 `resultView`**：刷新恢复依赖 localStorage 镜像；镜像清空后 `list_tasks` 视图不可恢复（当前卡面也不渲它）。归步骤④。
5. 后端 `kind=table` 空态的 `empty.text` 复用 fallback key（v1 未单列空态 key）；前端发布冲突用 `meta.truncated` 由渲染器合成"共 N 行，展示前 M 行"。
6. 列 `width`（相对权重 1..12）在 v1 表格里**未消费**（原生 table 自动布局；映射到 Tailwind 宽度类需要 12 档静态类，留视觉口径）。

## 七、`git status --porcelain`

```
 M backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java
 M backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java
 M backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/locales/en-US.json
 M frontend/src/locales/zh-CN.json
 M frontend/src/stores/ai.ts
 M frontend/src/stores/task.ts
 M frontend/src/stores/workspace.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/sse.ts
 M frontend/src/utils/parts.ts
 M frontend/tests/ai-panel-parts-render.spec.ts
?? backend/src/main/java/com/example/configmgr/ai/resultview/
?? backend/src/test/java/com/example/configmgr/ai/resultview/
?? data/                      （开工前既有，非本批产物）
?? frontend/src/components/AiPanel/resultview/
?? frontend/src/types/resultview.ts
?? frontend/src/utils/resultview.ts
?? frontend/tests/publish-conflict-resultview.spec.ts
?? frontend/tests/resultview-renderer.spec.ts
?? frontend/tests/resultview-utils.spec.ts
```
`frontend/tests/ai-panel-parts-render.spec.ts` 只**追加**两条用例（旧 190 条零改动）；`frontend/dist/` 已被 `.gitignore` 覆盖，未污染工作树。

## 八、停手点

- **未 commit / 未 push**；**未启动任何服务**（因此 E2E 未跑：仓库 E2E 脚本只按帧**类型**断言、不校验 `tool_result` 键集合，`resultView` 为 additive 键，判断为不受影响，但这是**读码判断，未实机验证**）。
- 未改 `docs/`（任务看板/决策看板的进度行、evidence 报告未落 —— 如需登记请指挥方发令；设计稿本身未改动，其 §7.1-④/⑤ 仍是未完成步骤）。
- 下一步若要继续：步骤④（普通工具卡/GF 只读回执/表格预览逐个接渲染器，`list_tasks` 卡面渲染与 `/history` 回传 view 一并做），以及上面 §六-1 的"后端构造"替代方案裁决。
