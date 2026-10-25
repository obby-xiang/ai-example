> 来源=<仓外证据>/ai-full-audit/final-gap/FA5-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
## 交付说明（请先读这条）

我这个角色**没有写入工具**，所以 `final-gap/FA5-report.md` **没有落盘**；报告全文如下，请控制面原样保存。

---

# FA5-report｜前端工具执行与状态辅助（11 文件 / 3,807 行逐行）

**未运行任何测试。**（未 build、未跑 vitest、未起服务、未改任何代码/测试/配置；本报告全部为静态证据，`C` = 代码可证，`R` = 需运行期/特定交错，`T` = 测试自身可证。）

快照：`<仓外证据>/ai-full-audit/tree`（`ae883fb` + `working.patch`）。行号均为该工作树。

---

## 0. 覆盖声明

| # | 文件 | 行数 | 读取 | 与既有 canonical ID 的关系 |
|---|---|---:|---|---|
| 1 | `frontend/src/utils/frontend-tools.ts` | 1,560 | 逐行 1–1560 | **确认** FE-09/FE-30/FE-33 的相邻面；**补充** FE-33（第二层缓存）、FE-30（路由不对称）；**新增** N1/N2/N3/N6 |
| 2 | `frontend/src/utils/blocks.ts` | 585 | 逐行 1–585 | **确认** FE-30（`argRows` 默认不掩码）；**更正** 掩码正则覆盖面（N4）；**新增** 无（GF 非成功态归"已取消"列入 §5 未决） |
| 3 | `frontend/src/utils/tool-summaries.ts` | 265 | 逐行 1–265 | **补充** FE-29（`create_task` 硬编码中文类型名，N5）；否证"ui_* 卡片会露出英文标识"（见 §4-C3） |
| 4 | `frontend/src/utils/resultview.ts` | 410 | 逐行 1–410 | 无新缺陷；**新增** 生产调用点与契约字段错配（并入 N2） |
| 5 | `frontend/src/utils/ui-receipt.ts` | 93 | 逐行 1–93 | **新增** 声明但从不产生的码 + 死导出（N6） |
| 6 | `frontend/src/utils/error-card.ts` | 188 | 逐行 1–188 | **确认** FE-09（专属 Alert 码不出卡 ⇒ 只剩单槽 notice）；**新增** N7（按自由文本分类） |
| 7 | `frontend/src/utils/ai-suggestions.ts` | 112 | 逐行 1–112 | **确认** FE-10（构造器无可用性入参，闸只在点击处且只判 `canSend`） |
| 8 | `frontend/src/utils/job-watch.ts` | 253 | 逐行 1–253 | **确认** FE-16 邻（无换任务感知，靠调用方 dispose）；无新缺陷 |
| 9 | `frontend/src/utils/polling.ts` | 248 | 逐行 1–248 | 无新缺陷（13 条用例覆盖可见性/在途/dirty） |
| 10 | `frontend/src/utils/publish-mode.ts` | 61 | 逐行 1–61 | 无新缺陷；fail-open 面 = "字段缺失⇒MERGE"（与后端同口径，登记为边界） |
| 11 | `frontend/src/utils/publish-links.ts` | 32 | 逐行 1–32 | **确认** FE-14（内存态、每页签独立；消费方有 sessionId 闸，行为正确） |

**未做递归非 AI 扩展**：11 个文件之外只读了**直接证据点**（`types/tools.ts` 注册表、`stores/ai.ts` 调用方与预览特判、`stores/workspace.ts` 路由 2/3 落点、`stores/task.ts` 发布冲突出口、`api/tasks.ts`/`api/jobs.ts`、`AiPanel.vue` 的 `paramRows`/`gateRows`/`toolSummaryOrNull`/`followups`、`resultview/{ResultViewRenderer,ResultViewTable}.vue`、两份 locale），以及测试文件；未对这些文件做域级审计。

---

## 1. 新增/更正条目（只有代码可证者）

### N1｜同参去重是 `ui_snapshot` 的**第二层**陈旧缓存（FE-33 补充，独立于 `ai-dom-tools`）

- **严重度/确定性**：S3（并入 FE-33 计分；修复面多一层）/ **C**
- **path:line**：`frontend-tools.ts:600`（`DEDUPE_TOOL_NAMES` 含 `ui_snapshot`）、`:579-591`（命中即直返）、`:628-635`（键 = `runId|epoch|name|args`）、`:646-668`（清表与记账）
- **触发**：同一 `runId` + 同一页面 epoch 内，模型第二次发**完全相同参数**的 `ui_snapshot`，且中间执行过 `ui_click`/`ui_fill`/`ui_select`。写动词不推进 epoch（`bumpAiEpoch` 在生产 `src/` 内**零调用方**，epoch 只由 `noteAiPage`/区域注册注销推进）。
- **效果**：第二次返回 `本轮已执行过同参调用 ui_snapshot（未再次执行）。\n<动作前快照>`。即使 `ai-dom-tools` 的快照缓存（FE-33 引用的 `:119,531-535`）被修好，本层仍逐字返回动作前画面 ⇒ "可再 `ui_snapshot` 确认"的文案依旧结构性不可兑现，模型可能把勾选点回去。
- **测试证据**：`tests/ai-snapshot-dedupe-epoch.spec.ts:58-70` **把该窗口写成期望**——"同 epoch 内 DOM 变化但 epoch 未推进 ⇒ 仍去重（去重绑定的是 epoch，不是 DOM 内容）"，断言第二次仍含旧按钮 `import.upload.choose`。这是本批次**第二条**"缺陷固化为期望"的用例（另一条为 `ai-region-batch-b.spec.ts:172-201`）。
- **与 FE-33 的关系**：同一用户可见失败，**不同代码、不同修复点**（`ai-dom-tools` 写动词失效 vs `frontend-tools` 去重失效）；不建议合并为一条修复任务。

### N2｜发布冲突数被第一页（50）静默截断，却当"总数"同时喂给模型与卡脚

- **严重度/确定性**：S4 / **C**（上限确定）+ **R**（>50 的实际实例）
- **path:line**：`stores/task.ts:361`（`jobsApi.getIssues(jobId, 0, 50)`）、`:362`（只取 `content`，`totalElements` 丢弃）、`:103-110`（`共 ${issues.length} 处`）；`stores/ai.ts:5421-5425`（`total: issues.length` 传给 `buildPublishConflictView`）；`utils/resultview.ts:359`（该入参的文档语义是"**真实**冲突总数，issues 可能已被上游裁剪"）；`components/AiPanel/resultview/ResultViewTable.vue:38-44`（脚注渲 `meta.truncated.totalRows`）
- **触发**：一次发布终态 `FAILED` 且 ERROR 级校验问题 > 50 条（无 ERROR 时降级用全量清单，同样封顶 50）。
- **效果**：① 模型读到的 reason 首行"发布作业 #N 校验冲突明细（共 **50** 处，仅列前 5 条）"；② 卡面表格脚注"共 **50** 条，显示 5 条"；实际可能 200 条 ⇒ 模型把"已看完冲突范围"当事实汇报，用户按 50 条修不完。③ 唯一生产调用点传的是**已裁剪长度**，使 `buildPublishConflictView` 的 `total` 契约在生产中永远等于 `issues.length`（字段名不成立）。
- **测试证据（mock vs 实际）**：`tests/watch-publish-outcome.spec.ts:120-172` 的 `/issues` 桩恒为 `{content, totalElements}` 且两者相等（1 条、7 条），**结构上不可能暴露 50 上限**；而 `tests/resultview-utils.spec.ts:91-94`、`tests/publish-conflict-resultview.spec.ts:44,206-215` 都**显式喂真实 total**（9、6）——单测守住了纯函数契约，恰好证明错在调用点。
- **归属**：BZ-05（影响面数字失真）族；与 FE-18（AI 发起发布不绑页面）相邻但不同因。

### N3｜路由 3（ui_event 同名动作）**没有**任何页/步/任务闸，与路由 2 不对称（今天零触发）

- **严重度/确定性**：S4 / **R**（潜在，当前不可达）
- **path:line**：`frontend-tools.ts:690-693`（路由 3 入口）、`:1311-1337`（`runUiEventAction` 直接 `dispatchUiEvent`，**无** `allowPages`/`allowSteps`/`needsTask`）；对照 `:974-1001`（路由 2 三道闸）、`:1005-1017`（同页幂等）、`:988-994`（`allowSteps` **实际生效**）
- **触发**：出现一个后端 `@ToolChannel(FRONTEND)`、名为 `run_job` / `download` / `cancel_job` / `set_import_mode` / `refresh_defs|data|tasks` / `select_defs` / `set_conditions` 的工具。
- **效果**：这些动作会绕过路由 2 的页面/步骤/任务绑定闸直接落到契约层（`run_job` 的 PUBLISH 仍有确认框，EXPORT 无）。即"同一动作有两个入口，安全闸只装在其中一个"。
- **当前可达性（实测核对）**：后端 37 个 `@Tool` 名中**没有**上述任何一个（`aggregate_sheet … update_rows`，无 `run_job`/`download`/`cancel_job`/`set_import_mode`/`refresh_*`/`select_defs`）⇒ 今天**零触发**；`tests/r166b-fixes.spec.ts:514-517` 是唯一按名驱动路由 3 的用例，且只喂 `args: {}` 走"参数不完整"失败支，**正路径零覆盖**。
- **归属**：新增（潜在面）。同时更正本模块头注释"四条路由"给人的"四路安全等价"印象。

### N4｜敏感键正则比它自己声称的窄，门卡（唯一掩码面）会明文显示典型密钥名

- **严重度/确定性**：S4 / **R**（需该形参）+ **C**（正则缺口）
- **path:line**：`blocks.ts:139-140`（`/pass(word)?|secret|token|api_?key/i`）、`:201-209`（仅键名命中才掩码；`maskSensitive` 仅门卡传 `true`）、`AiPanel.vue:3105-3109`（门卡 `argRows(part.args, true)`）vs `:2913-2916`（普通卡 `argRows(part.args)`，注释自认"敏感不掩码"）
- **触发**：门卡工具的 args 出现 `authorization` / `accessKey` / `privateKey` / `signingKey` / `credential` / `pwd` 等键名（后端现有 34 个 AiTools 的 gate 参数无此类键，故今天无实例）。
- **效果**：门卡明文显示该值并进 per-tab 镜像；即当前"唯一的掩码面"实际只覆盖 `password/secret/token/apiKey` 四类。
- **测试证据**：`tests/blocks.spec.ts:388-393` 只断言 `password`/`apiKey` 两个键；正则缺口无任何用例（加一个 `authorization` 用例即可稳定变红）。
- **与 FE-30 的关系**：FE-30（普通卡不掩码，S4/D）成立不变；本条是其**上游**（即使把掩码扩到普通卡，正则仍漏这几类）。

### N5｜`create_task` 摘要硬编码中文任务类型名 ⇒ 英文界面出现"Create 导入 task"（FE-29 补充）

- **严重度/确定性**：S4 / **C**
- **path:line**：`tool-summaries.ts:70-73`（`type === 'IMPORT' ? '导入' : 'EXPORT' ? '导出'`）、`:124-130`（模板插值 `{ type: label }`）；`locales/en-US.json:563-567`（`"summary": "Create {type} task"`）
- **触发**：en-US 界面下模型调用 `create_task`（任何 `type`）。
- **效果**：卡面一行摘要 = `Create 导入 task "月报"`——同一句话中英混排；用户看不出这是导出还是导入。
- **测试证据**：`tests/tool-i18n-coverage.spec.ts:71-72,79-80` 只钉 zh 快照（`创建导出任务「月报」`）；en 断言只覆盖 `start_export`（`:139-141`）⇒ 该路径无 en 覆盖。
- **归属**：FE-29 的**第二处**生产点（FE-29 引用 `AiPanel:2846-2856,3528-3531` + `format.ts:90-93`，未含本文件）。

### N6｜`UI_ALREADY_EXECUTED` 声明但无生产者；`UI_CODE_PATTERN` 零消费者（契约面死码）

- **严重度/确定性**：S4 / **C**
- **path:line**：`ui-receipt.ts:29`（`'UI_ALREADY_EXECUTED'` 在闭集里）、`:35`（`UI_CODE_PATTERN` 导出，`src/` 与 `tests/` 零引用）；`frontend-tools.ts:581-588`（去重命中时 `code: previous.code`，**不产** `UI_ALREADY_EXECUTED`）
- **触发**：任何同参去重命中。
- **效果**：① 去重命中把**上一次**的 `outcome`/`availability`/`code` 原样复制给新工具段——上次是 `timeout`/`failed` 时，这次"未执行"也照样落 `failed` 并携带旧码，模型无法区分"未执行（沿用旧结局）"与"这次真的失败了"；② 闭集里有一个永远不会出现的码，后端台账/取证按码统计时该分支恒 0；③ `UI_CODE_PATTERN` 的注释自称"与后端 `FrontendToolResultRequest` 校验正则同源"，但前端无任何使用者，跨端同源**不被任何断言保护**。
- **测试证据**：`tests/ai-tool-receipt.spec.ts:96-103` 只断言码的合法性；无"去重命中出 `UI_ALREADY_EXECUTED`"用例（该断言会红）。

### N7｜错误卡变体分类会扫描**自由文本**，上游原句里的词会改判色族与动作

- **严重度/确定性**：S4 / **R**
- **path:line**：`error-card.ts:122-134`（`classifyErrorVariant(code, message)` → `QUOTA_RE`/`FILTER_RE`/`CONTEXT_RE` 拼 `code + message`）、`:168`（命不中专属码表时使用）、`:52-56`（三个正则；`CONTEXT_RE` 含 `too[_-]?long`）
- **触发**：非配额/非超限的上游错误，其 `message` 里恰好含 `配额`/`额度`/`rate limit`/`too long` 等词（例如把模型输出片段回显进错误信息的 400，或 `Request URI too long`）。
- **效果**：用户看到"配额已用尽"蓝/红卡与"开新会话/升级"动作钮，而真实原因是参数或上游 5xx——错误卡把"未分类故障"伪装成可判定类别。
- **测试证据**：无该用例（`error-card` 只有 classify 的码优先路径被间接覆盖）。
- **建议口径**：`R`，因为需要真实上游文案样本；方向是"码优先、文本仅在同码族内兜底"。

---

## 2. 对既有 canonical ID 的确认（逐条代码锚点，不新开编号）

| ID | 结论 | FA5 内的代码锚点 |
|---|---|---|
| **FE-09** 单槽通知互相覆盖 | **确认** | `error-card.ts:44-49`（`DEDICATED_ALERT_CODES`：SESSION_BUSY / SUSPEND_POOL_SATURATED / AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE 被**明确排除出卡**）+ `:140-144`。推论（新表述）：这四类错误**只**存在于单槽 notice，历史里零痕迹；刷新后 409/503 的解释整条消失（与 FE-26 的"空气泡"叠加）。 |
| **FE-10** 建议词绕过可用性闸 | **确认** | `ai-suggestions.ts:27-32,66-112`（`SuggestionContext` 无 `available`；构造器无法表达可用性）+ `AiPanel.vue:1534-1557`（`followups` 闸只判 `followupReady/loading/suspended/streamInterrupted/pendingConfirms/activeForm`，**不判 `ai.available`**）+ `:1514-1519`（`sendPreset` 只判 `ai.canSend`）。故"必然 503 的按钮仍可点"成立。 |
| **FE-16** 导入向导换任务不回收 | **确认（跨证）** | `job-watch.ts:73-252` 全程只持有 `{taskId, jobId}`，**没有任何换任务判据**，回收责任 100% 在调用方 `dispose()`；`tests/import-watch-recycle.spec.ts:60-73` 仍是"自建同构接线"（`beginDirect/disposeAllDirect`），不挂 `ImportWizardView`。 |
| **FE-19** 并发发布单槽 | **确认（相邻面）** | `stores/task.ts:116`（`watchingJobId` 单槽闸）、`:298`；`stores/workspace.ts` 的 `publishWatcherDispose?.()` 单字段。FA5 只作旁证，不改判。 |
| **FE-30** 普通卡不掩码 | **确认** | `AiPanel.vue:2913-2916`（`paramRows` → `argRows(part.args)`，注释"敏感不掩码"）vs `:3105-3109`；`blocks.ts:201-209`。新增上游缺口见 N4。 |
| **FE-33** 快照缓存不失效 | **确认 + 补充** | `ai-dom-tools` 侧（非本组）保持；本组新增**第二层**（N1），且 `bumpAiEpoch` 在生产 `src/` 零调用方（`grep bumpAiEpoch src/` 仅定义与一处注释）。 |
| **FE-32** 隐藏控件可写 / `allowSteps` 死闸 | **确认，但需限定语** | 本组内存在**两份同名 `allowSteps`**：① `WorkspaceAction.allowSteps`（`frontend-tools.ts:722,849,867,883,895,988`）**是活闸**（`SELECT_DEFS/UPLOAD`、`QUERY_COND` 真拦）；② 区域侧 `AiRegion.allowSteps`（`ai-regions.ts:50`，读者仅 `ai-dom-tools.ts:981`）**零注册方** ⇒ 死闸。**不要**把①当死码一并删除。 |
| **FE-14** 业务态跨页签不存在 | **确认为同族** | `publish-links.ts:16` 是**模块级 Map（每页签一份）**：发布冲突明细只能落到"发起它的那个页签"。参考实现正确性：`stores/ai.ts:5398-5403` 有 `link.sessionId !== this.sessionId ⇒ unlink` 身份闸（这点是对的）。 |
| **FE-18** AI 发起 `start_publish` 不绑页面 | **确认（另一路径存在且不同）** | 路由 3 的 `run_job` 分支（`stores/workspace.ts` PUBLISH 段）**会**读服务端权威模式 + 弹确认 + 绑 `watchPublishOutcome`；`start_publish`（后端工具）不走这条。二者并存，FE-18 仍成立。 |
| **FE-20** 作业终态不回灌模型 | **部分缓和，仍成立** | 唯一回灌口 = 发布冲突（`onConflict` → `reportUiEventResult('run_job', …)` + `attachPublishConflict`）；EXPORT/IMPORT/PRECHECK 终态无通道。 |

---

## 3. 明确否证 / 不作缺陷登记（本轮读出来的"假缺口"）

| # | 曾可能被报成缺陷 | 否证依据 |
|---|---|---|
| C1 | "`ui_*` 卡片一行摘要会露出英文工具名 `ui_click`" | **不成立**：`toolSummary` 走 `tool.fallback="{name} · {status}"`，但 `AiPanel.vue:1999-2013` 剥前缀后得到状态词、命中 `STATUS_WORD_SET` 即返回 `null` ⇒ 该行整体不渲染（只留标题 + 右标签）。仅剩"7 个原语无摘要模板 + 头注释声称全集"的**注释漂移**，S5 级，不计条目。 |
| C2 | "`publish-mode` 字段缺失⇒MERGE 是 fail-open" | **不是缺陷**：`parsePublishMode(undefined)` 与后端 `settingsJson` 为空⇒MERGE 同口径（`publish-mode.ts:11-13,28-31` 明示），且 GET `/tasks/{id}` 返回实体（`TaskController.java:84-87`）、`Task.settingsJson` 存在（`entity/Task.java:35`）⇒ 无 DTO 漂移现例。 |
| C3 | "`polling.ts` 在 realtime 用 `setInterval(2^31-1)` 会被 Node 折成 1ms" | **不成立**：`job-watch.ts:98` 取 `2_147_483_647`，恰为 32 位有符号上界（`> 上界` 才告警），且 `polling.ts:135-139` 每次 `arm` 都 `clearTimer` 先清。`tests/polling-controller.spec.ts:268-286` 覆盖运行时换节奏不双发。 |
| C4 | "`watchJob` 的 `onerror` 里 `source` 在 TDZ" | **代码上不可达**：`api/tasks.ts:114-134` 的 `onerror` 只由 EventSource 事件异步触发，`openTaskEvents` 返回后 `source` 已绑定；仅当 `new EventSource` 同步抛（环境不支持）时 `watchJob` 直接抛——登记为边界（§5-U8）。 |
| C5 | "`resolveViewRows`/`resolveJsonPointer` 可被 `__proto__` 打穿" | **不成立**：仅读、结果非数组即返回 `[]`；渲染色走 `{{ }}` 文本插值（`ResultViewTable.vue:91`、`ResultViewText.vue:31`），无 `v-html`，`javascript:` 伪协议无落点。 |
| C6 | "`undo_last_sheet_edit` 在 `runSheetTool` 被硬拒 ⇒ 撤销功能坏了" | **不成立**：撤销走 store 预览面（`SHEET_PREVIEW_TOOLS` 含它，`ai.ts:3809-3820` 起 `resolveSheetContext` 直连写回），执行器拒执只对"绕过确认面的旧客户端"生效——正是 fail-closed 设计。 |
| C7 | "路由 1 会把 `navigate_to` 拦在 scope 外" | **不成立**：`navigate_to` 不在 `FRONTEND_TOOLS`（18 条枚举核对：2 + 9 表格 + 7 原语），故走路由 2 的活闸，不被 `task:EXPORT/EXPORT` 收窄。 |

---

## 4. 逐文件关键判读（只列有裁决价值的）

**`frontend-tools.ts`**
- 四级路由的实际优先级：`FRONTEND_TOOLS` 描述符 → `delete_rows` 特判（后端无此工具 ⇒ 防御性死码，非缺陷）→ `WORKSPACE_ACTIONS`（含 6 个别名）→ `UI_EVENT_TYPES` → 页面 handler。头注释"实读确认 4 条"与代码一致。
- **预算面**（`:352-412,468-493`）：折算式 `min(max(server−60s,5s), server)` + `setTimeout` 上界收口 `2^31−1` 均自洽；`tests/tools-and-deadline.spec.ts:30-51` 已钉 12 组边界。**未覆盖**：同步阻塞主线程形态（注释自认不可打断，`R`）与"计时器赢后执行器晚到结局仍写去重表"（`:589-591`，见 §5-U4）。
- **结局一致性**：`makeExecution` 单点推导 `ok`、`sanitizeUiCode` 闭集过滤（`:145-170`）是正面设计；`normalizeExecutorReturn` 显式透传 `code`/`sheetMeta`（`:1292-1309`）堵了"三面同源"失明。
- **`ui:on` 的 fail-open 支在生产不可达**：`runRegisteredTool` 恒传 `listAiRegions().length`（`:1252-1257`），`matchesScope:189` 的 `undefined/null` 支只服务直调/测试——与 `types/tools.ts:149-157` 的注释（"缺失即披露，旧客户端/E2E 桩"）描述的场景**不由本文件产生**。
- **`resolveSheetContext`**（`:1117-1163`）：`handler` 抛错/结构不合法均降级为可行动文案；`adapter` 做了 `object` 存在性判但不校验方法面（成员缺失在 `sheet-tools` 内才炸），`R` 边界。

**`blocks.ts`**
- `readableValue`：对象一律"（复杂值）"、路径→文件名、数组顿号，**不泄 JSON**（`:150-178`）——这使 FE-30 的实际暴露面只在"顶层标量 + 键名不命中正则"，比"args 明文上卡"的说法窄；建议 FE-30 的效应描述按此收窄（**更正**）。
- `isReadOnlyCard` + `readOnlyStateOf` 与 `cardStatusOf` 三层判据自洽；`NON_DECISION_STATUSES` 把 `pending/blocked/unknown/skipped` 挡在只读卡外（`:316-322`）——与"skipped 不得折成已超时"契约一致。
- **GF 非成功态折叠**：`:343-346` 把 GF 的 `failed` 也归 `cancelled`（"✗ 已取消" danger），`tests/blocks.spec.ts:306-309` 将其钉为期望。见 §5-U1（可达性未证，暂不立条）。
- `hasToolBody` 把 `resultView != null` 计入行数（`:540-543`）——避免"视图能渲但原文空 ⇒ 丢展开入口"，与 `isRenderableView` 的降级口径互补。

**`tool-summaries.ts`**：除 N5 外，`optional()` 用"译文 == key"判缺键（`:54-58`），zh/en 缺键都安全回落；`taskTypeLabel` 对未知类型原样回显（`:72`）⇒ 不臆造。

**`resultview.ts`**：白名单/规模/字节三把尺实现与后端同值（`:24-56,300-326`）；`repairEmptyText` 阻"零行读成渲不出"（`:237-244`）；`isRenderableView` 把 `status=error` 让位给原文（`:338-341`）。唯一问题在**调用点**（N2），不在本文件。`buildPublishConflictView` 对数值型 `defCode` 会经 `sanitizeResultViewText` 变空串（`:89-94,365`），`R`（类型契约外输入）。

**`ui-receipt.ts`**：`toolStatusOfOutcome` 把 `timeout` 落 `failed`、`unknown` 只留给下载类（`:74-93`），与 `timeoutOutcome`（`:542-561`）成对，口径一致。N6 的 `UI_ALREADY_EXECUTED`/`UI_CODE_PATTERN` 是本文件仅有的两处死面。

**`error-card.ts`**：`baseErrorCode` 先剥 `_AFTER_TOOL_SIDE_EFFECT`/`_AFTER_PARTIAL` 再查专属表（`:62-71,141-144`）——这一点比 FINAL 的描述更严；卡面文案"建卡定格"（`:100-102`）与 i18n 切换的取舍是明确裁决，非缺陷。N7 是唯一新增。

**`ai-suggestions.ts`**：`push` 上限 3 + 去重 + 保底 2（`:66-111`）；`taskProgress` 的 `id` 插值是 `taskId ?? 0`（`:72`），但该分支只在 `taskId !== null/undefined` 时进入（`:75`）⇒ 不会出现 #0。除 FE-10 外无缺陷。

**`job-watch.ts`**：看门狗复位源严格两项（帧/onopen，`:151,191`），polling 快照**不**复位（`:204-236` 无 resetWatchdog 调用）——与头注释一致；`syncSnapshot` 的 `inflight` 交回 + `disposed` 双侧复检（`:202-236`）堵了"死跟踪错误弹到新作业"。**未覆盖**：`onDisposed` 与 `onFinal` 的**先后**（`:238-249,220-224`：先 dispose→onDisposed，再 onFinal）无断言；若页面在 `onDisposed` 里清空卡片容器，`onFinal` 的 UI 写入可能落到已回收视图（`R`，见 §5-U9）。

**`polling.ts`**：`invoke` 的三闸（`!started`/`paused`/`running`）+ `dirty` 收尾补发 + `arm` 先清后起（`:102-151`）自洽；`resume` 的 0~250ms 错峰只作用于补刷本身（`:160-174`）。`tests/polling-controller.spec.ts` 13 条覆盖到位。

**`publish-mode.ts`**：fail-closed 只覆盖"非字符串/JSON 不合法"（`:33-48`），与文档一致；残留窗口见 §5-U7。

**`publish-links.ts`**：无 TTL/无上限，`unlinkPublishTool` 只在"命中落段"与"sessionId 不匹配"两处调用 ⇒ **发布成功（无冲突）的 jobId 永久留在表里**。量级 = 会话内成功发布次数（个位到几十），无用户可见影响；登记为 S5 观察，不立条。

---

## 5. 未解决分支（显式，不假装覆盖）

| # | 分支 | 为什么读不出来 |
|---|---|---|
| U1 | GF 段 `status=failed` 是否真实可达 ⇒ 卡面把"提交失败"渲染成"✗ 已取消" | `blocks.ts:343-346` 的映射是代码事实、`blocks.spec.ts:308` 是期望；但要证"用户提交被后端拒后段确实落 failed"需跑一次真表单 + 守卫拒绝（P5-01/P5-02 只证守卫会 500，未证段状态） |
| U2 | `el.click()` 对 `display:none` 的语义（决定 FE-32 的"写入生效"强度） | 真机项，代码无法判定；FA5 只证"围栏放行 + 回执报成功" |
| U3 | 路由 3 正路径 | 后端无同名 `@Tool`（37 名核对），测试只走失败支 |
| U4 | 预算超时后执行器晚到结局仍被 `rememberSameCall` 记入去重表 ⇒ 同轮重试读到的"已执行过"其实来自超时那次 | 需真实慢执行器；`tests/tools-and-deadline.spec.ts` 无此交错 |
| U5 | 一次发布 > 50 条 ERROR 的真实实例（N2 的 R 维） | 需造大冲突集；现有夹具最大 7 条 |
| U6 | N7 的错误卡误判实例 | 需上游 400/5xx 原文样本 |
| U7 | 发布模式 TOCTOU：`resolvePublishMode`（`workspace.ts:532` / `ImportWizardView:1021`）读后、`jobsApi.createJob` 执行前，另一页签 `set_import_mode` 改库 ⇒ 确认文案说"增量合并"而作业按"整体替换"删行 | 需两页签交错；方向是"确认文案与后端执行同源不可再保证"，属设计残留而非可判缺陷 |
| U8 | 环境无 `EventSource` 时 `watchJob` 同步抛、页面无兜底轮询 | 需目标浏览器矩阵 |
| U9 | `dispose()` 内 `onDisposed` 先于 `onFinal` 的可见后果 | 需页面侧接线（`ImportWizardView` 轮次收尾）联读，且 `job-watch-channel.spec.ts:373-398` 只断 `onFinal` 次数与新取数，不断顺序 |
| U10 | `resolveSheetContext` 返回的 `adapter` 缺方法时（结构合法、契约不全）在哪一层炸 | 需在 `sheet-tools` 侧造残缺适配器 |

---

## 6. 结论

1. **11 文件 3,807 行已逐行读完**，无"未读"文件；仍有 10 条运行期分支读不出来（§5）。
2. **新增 7 条**（N1–N7），最高为 **S3/N1**（FE-33 的第二层缓存，且被 `ai-snapshot-dedupe-epoch.spec.ts:58-70` 固化为期望）；其余 6 条为 S4，其中 N2 有"单测喂真值、生产传裁剪值"的对照证据，最值得派人核。
3. **无需改判**既有 FE-09/FE-10/FE-16/FE-19/FE-30/FE-32/FE-33/FE-29 的严重度；仅需两处**收窄/限定**：FE-30 的暴露面（对象→"（复杂值）"、路径→文件名）+ FE-32 的 `allowSteps`（两份同名，只区域那份是死闸）。
4. **测试真伪新增两处**：`ai-snapshot-dedupe-epoch.spec.ts:58-70`（陈旧快照＝期望）、`watch-publish-outcome.spec.ts:120-172`（桩的 `totalElements` 恒等于 `content.length`，50 上限结构上不可见）。
5. 未提出任何修复方案，不声称任何条目"已修复"；`_p2-review/ai-full-audit/final-gap/FA5.md` 要求的报告文件因本角色无写入工具**未创建**，内容即上文。
