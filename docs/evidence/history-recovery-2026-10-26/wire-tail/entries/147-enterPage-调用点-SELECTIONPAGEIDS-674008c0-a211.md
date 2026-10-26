> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-211` 的 wire 末条 assistant 文本（消息序号 m=13；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 20:19 → 2026-10-09 20:20（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：9259 B / 6303 字符 / 72 行；行指纹覆盖率（本批复算）3.9%（最高单文件 4%：`docs/spike/SP-01c-决策卡.md`）
> - 类别：**过程核验**
> - 与既有件关系：收尾报告；报告口径覆盖率 0.04
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 2
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核对完成。仓库 `<REPO_ROOT>`，未提交改动为 4 个文件（ContextBuilder.java、workspace.ts、Export/ImportWizardView.vue）+ 1 个新增测试，我读的是工作区版本。

## 任务 1：`enterPage` 全部调用点（9 处，含定义 2 处）

定义/类型：
- `frontend/src/stores/workspace.ts:118` `enterPage(init: Partial<WorkspaceSnapshot>)`；`selectedDefs` 仅在 `init.selectedDefs !== undefined` 时写入（`:139-141`）——**不传该键时不清空，保留上一页的旧值**
- `frontend/src/types/workspace.ts:61` 接口声明；`WorkspaceSnapshot.selectedDefs: string[]`（`:45`）
- `frontend/src/stores/workspace.ts:152` `leaveWizard()` 会清空 `selectedDefs`，但**全仓零调用点**

调用点清单：

| # | 文件:行 | pageId | selectedDefs 实参 |
|---|---|---|---|
| 1 | `ai-example-code/<REPO>/frontend/src/App.vue:71` | `'tasks'` | 未传 |
| 2 | `.../frontend/src/views/TaskCenterView.vue:453` | `'tasks'` | 未传（显式 `taskType/step/taskId: null`） |
| 3 | `.../frontend/src/views/DefinitionsView.vue:490` | `'definitions'` | 未传 |
| 4 | `.../frontend/src/views/DataBrowserView.vue:325` | `'data'` | 未传 |
| 5 | `.../frontend/src/views/DataBrowserView.vue:234-240` | `'data'` | **传了**：`selectedDefs: code ? [code] : []` |
| 6 | `.../frontend/src/views/ExportWizardView.vue:332-340` | `'export'` | 传了：`[...selectedDefs.value]`（`loadTask` 内） |
| 7 | `.../frontend/src/views/ExportWizardView.vue:973` | `'export'` | **未传**（`onMounted` 首次进入，`taskId: taskId.value`） |
| 8 | `.../frontend/src/views/ImportWizardView.vue:408-417` | `'import'` | 传了：`[...selectedDefs.value]`，另带 `importMode` |
| 9 | `.../frontend/src/views/ImportWizardView.vue:981` | `'import'` | **未传**（`onMounted` 首次进入） |

两个判定：

- **「pageId 非 export/import 却传 selectedDefs」＝ 有 1 处**：`DataBrowserView.vue:234-240`（`'data'`）。它不是 `SELECTION_PAGE_IDS` 漏项——该值是"当前正在浏览的单个定义编码"，不是用户勾选集，且该调用 `taskType: null`。新测试 `ContextBuilderSelectedDefsTest.java:116-122` 正是把这条路径锁成"退回任务条目"。但 `ContextBuilder.java:45-47` 的 javadoc 说该常量"与 `enterPage({pageId, ..., selectedDefs})` 的调用点逐页对应"——**这句字面上不成立**（调用点是 3 个页：export/import/data），测试名 `ContextBuilderSelectedDefsTest.java:149-151` 的自述"与前端调用点逐页对应（多/少都算契约漂移）"同样名不副实。行为正确，注释/测试命名失真。
- **export/import 存在不传 selectedDefs 的 enterPage（"从未同步"窗口）＝ 有 2 处**：`ExportWizardView.vue:973` 与 `ImportWizardView.vue:981`。因为 `enterPage` 不传该键时不重置，这两处会把**上一页/上一任务遗留的 `workspace.selectedDefs`** 原样留在 `pageId='export'|'import'` 之下，直到 `loadTask` 内的 `:332`/`:408` 才覆盖。若带 `?taskId=` 进入（恢复/跳转流程），窗口内发起的 chat 会被后端当成"页面实时勾选"并打出 `（来源：页面实时勾选）`，而内容是旧任务的集合。窗口短（`onMounted` 内 `Promise.all` 紧接），但契约上确实存在；`leaveWizard()` 无人调用意味着跨页也没有兜底清零。

## 任务 2：`SELECTION_PAGE_IDS` / `pageSelectedDefs` 出现位置

- `backend/src/main/java/com/example/configmgr/ai/tool/ContextBuilder.java:49` 定义 `List.of("export", "import")`
- `ContextBuilder.java:122` 唯一使用点（`pageSelectedDefs` 内的 `contains` 门）
- `ContextBuilder.java:110` javadoc `{@link}` 引用（注释）
- `backend/src/test/java/com/example/configmgr/ai/tool/ContextBuilderSelectedDefsTest.java:151` 唯一断言
- `pageSelectedDefs`：定义 `ContextBuilder.java:116`，唯一调用方 `ContextBuilder.java:79`（`pageDefs != null` 分支）；`:83-88` 是同一分支的两条消息。

**无其他消费者/分支**：全 `backend/src/main` 中读 `extra` 的只有 `ContextBuilder.java:95`（`renderExtra`）与 `:117`（`pageSelectedDefs`）；`extra.pageId`/`extra.selectedDefs` 无第三处读取。链路无过滤：`ResilientChatService.java:736` 把 `context.extra()` 原样交给 `AiContext.of`，`AiContext.java:36` 只是 `putAll` —— 门看到的是未截断的原 Map。没有按 `taskType`/`step`/`taskId` 的二次校验，也没有 `'data'` 的专门分支（按 null 路径处理）。

## 任务 3：「已选配置项」的出现与既有断言影响

出现位置（`grep 已选配置项` 于 `backend/src` 全量）：

- main：`ContextBuilder.java:83`（页面实时勾选分支，新增 `（来源：页面实时勾选）`）、`:88`（任务条目分支，新增 `（来源：任务条目）`）。git diff 确认改动前是 `sb.append("已选配置项: ").append(...).append("\n")`（无后缀）。
- test：仅 `ContextBuilderSelectedDefsTest.java` 一个文件（`:19, 75, 87, 100, 111, 122, 134, 145`），全部含新后缀。

**结论：没有任何既有测试断言旧格式。** 逐条排查了所有会走真实 `buildContextMessage` 的既有测试：

| 既有测试 | 是否会受行格式变化影响 |
|---|---|
| `ContextBuilderExtraTest.java:73-74`（唯一非新测试里调 `buildContextMessage`） | 不受影响：`taskId = null` ⇒ 根本不产「已选配置项」行，断言只查 `页面: tasks` / 不含 `额外上下文` |
| `ContextBuilderExtraTest.java:28-83` 其余用例 | 只测 `renderExtra` 与 `ALLOWED_EXTRA_KEYS`，不碰该行 |
| `ai/run/ChatTurnFrameSequenceConformanceTest.java:85` | `mock(ContextBuilder.class)`，真实文案从不断言 |
| `ai/run/EmptyReplyTerminalTest.java:84` | 同上，mock |
| 其他含"已选/配置项"的测试（TaskConditionJson、ExportFlowJob、GlmSeedCatalog、JobProgressTotals 等） | 均为注释/无关文案或不同字符串，无断言 |

另外 `backend/src/test/resources` 不存在（无 golden 文件），全量 grep 该中文串只命中上述 2 个文件。**所以新增后缀与"mvn 全量 354 绿"不矛盾——注意：我按只读约束没有跑 mvn，这是基于"无任何断言该行文本"的独立推断，不是复现。** 唯一风险面是格式契约本身：该行现在承载语义后缀，任何未来以 `isEqualTo` 或 `contains("已选配置项: X\n")` 精确匹配的断言都会因后缀而红。

## 任务 4：前端 `extra.selectedDefs` 组装与截断

- 组装点唯一：`frontend/src/stores/workspace.ts:234-250` `buildContext()`，`extra` 6 键 = `pageId, selectedDefs, importMode, dataVersion, contractVersion, recentActions`。
- 取值来源：`:242` `selectedDefs: [...this.selectedDefs]`——store 状态的**全量浅拷贝**，无 slice、无白名单、无长度限制。
- **前端不存在 `ALLOWED_EXTRA_KEYS` / `EXTRA_VALUE_MAX_CHARS`**：这两个符号全仓只出现在后端 `ContextBuilder.java:41/52`。前端唯一的裁剪是 `recentActions` 限 5 条（`frontend/src/types/tools.ts:192 WORKSPACE_ACTION_LIMIT`，用于 `workspace.ts:223`）。`frontend/src/types/tools.ts` 无 `extra` 结构定义，`frontend/src/types/ai.ts:28` 仅 `extra?: Record<string, unknown>` 自由字段。
- 发送路径无改写：`frontend/src/stores/ai.ts:614-618` 直接把 `workspace.buildContext()` 作为 `context` 发出。
- 后端截断只作用于"额外上下文"那行（`ContextBuilder.java:143` serialized ≤300 字符 / 总计 800），**不影响「已选配置项」行**——该行读的是 `ctx.getExtra()` 原始 List（`:125-129`），且 `String.join` 无长度上限。即：一个几百项的选择集在 `额外上下文` 里被截成 300 字符带 `…`，却在「已选配置项」行里完整铺开，两处口径不一致（次要观察，非本次任务判定项）。

## 结论：`SELECTION_PAGE_IDS` 是否与前端逐页对应完备

**行为上完备，注释上不实。** 就"拥有页面实时勾选集的向导页"这一意图，前端真正的勾选集页只有 `export`（`ExportWizardView.vue:332`）与 `import`（`ImportWizardView.vue:408`），二者以及其 AI handler 写入路径（Export `:892`、Import `:952`，均 `setSelectedDefs(codes,'REPLACE',source)`）都覆盖到，`SELECTION_PAGE_IDS` 与之一致，**无漏项**；`'data'`（`DataBrowserView.vue:234`，传 `[code]`）被正确排除，测试 `:116-122` 已锁定该语义；`'tasks'/'definitions'` 不传且被 pageId 门挡掉，即使 `workspace.selectedDefs` 有遗留（`leaveWizard` 无人调用）也不会误判。

需要提请注意的两点（都不改变"无漏项"的判定）：
1. `ContextBuilder.java:45-47` 与 `ContextBuilderSelectedDefsTest.java:149-151` 声称与 `enterPage({..., selectedDefs})` 调用点"逐页对应"——实际有 3 个页传 `selectedDefs`，注释/测试命名应改为"拥有用户勾选集的页"口径，否则后续按字面做契约漂移检查会误判。
2. `ExportWizardView.vue:973`、`ImportWizardView.vue:981` 这两个 onMounted `enterPage` 不传 `selectedDefs`，在 `loadTask`（`:332`/`:408`）覆盖前存在"旧集合被标为页面实时勾选"的短窗口；仅当带 `taskId` 进入时可见。
