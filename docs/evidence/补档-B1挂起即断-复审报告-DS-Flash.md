# 补档 · B1 挂起即断 SSE 复审报告（独立复审 · DS-Flash · 2026-10-20）

## 0 自证行（wire 实测，非照抄卡片）

- agentId = `agent-1281`；wire = `<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1281/wire.jsonl`（`grep -n "profile.bind"` 命中第 5 行）
- 实测 `modelAlias` = **`DeepSeek/deepseek-flash`**，`profileName` = **`coder`**，`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（HEAD = `4de9399`）；复审期零 git 写操作（仅按纪律 `git add -N` 逐个新件取 diff 后 `git reset -q`）、零服务启停、零改动工作树（本报告为唯一新增件）
- **入库脱敏（提交官，2026-10-20）**：本机绝对路径按 DC-08 换占位符（`<REPO_ROOT>` / `<KIMI_HOME>` / `<SESSION_ID>` / `<MAVEN_HOME>` / `<TMP>`），正文事实与结论未改

## 1 审查锚

- 工作树 diff（28 文件 = 24 改 + 4 新；`+1283 / −103`）全量字节 SHA-256：
  **`973e4a3238144d5cf8f78d54f6fce816746f9c5b048ba209be5fe85fae047bf4`**
  （`git diff` → 2075 行；新件按纪律逐个 `git add -N`：`SuspendDisconnectGateTest.java` / `补档-B1挂起即断-施工报告.md` / `设计-B1挂起即断SSE-2026-10-20.md` / `b1-suspend-disconnect.spec.ts`；取完 `git reset -q`。本复审报告不计入该锚。）
- 未跟踪的 `data/`（2026-10-12 起就存在的运行期目录，非本批产物）不在 diff 内。

## 2 门禁复核（亲测，抽查口径）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **500/500 · 25 文件 全绿**（13.09s） |
| 后端全量 | `backend/`：`<MAVEN_HOME>\bin\mvn.cmd -o -q test`（JDK 21.0.12） | **576/576 · 91 类 · 0 fail / 0 error / 0 skipped**（surefire 报文本轮全量刷新，exit 0） |
| typecheck | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **0 error（exit 0）** |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 绿** |
| build / E2E | 未复跑（build 与 E2E 需服务/耗时；本批有 Critical 已足以定论） | — |

> 与施工报告的数字偏差：其自报后端「566/566 · 91 类」，**实测 576/576 · 91 类**（设计稿基数 563 已过期；91 类与 +3 新用例一致，全绿结论不变）。见 M-1。

## 3 A：十条裁决逐条核对

| # | 裁决 | 结论 | 证据（文件:行号） |
|---|---|---|---|
| 1 | 断连点在请求帧之后，由 ConfirmGate 调 `completeSubscriptions`，不挂 `suspended` | **落实** | `backend/.../gate/ConfirmGate.java:336`（`out.completeSubscriptions()` 紧接 `:315/:318` 的请求帧 emit、`latch.await` 之前）；`run/SseChatEmitter.java:1222-1236`（锁内快照、锁外 `completeWhenDrained`、不置 `terminating`）；新用例 `gate/SuspendDisconnectGateTest.java:104-140`（请求帧先投递后 `subscriberCount()==0`） |
| 2 | 续传锚 = 既有 `?lastSeq`，无双轨 | **落实** | 生产 diff 无 `id:` / `Last-Event-ID` 新增；`frontend/src/api/sse.ts:51-54` 原契约；`stores/ai.ts:1430` 以 `runSeq[runId]` 作 lastSeq |
| 3 | `delta` 入档、`heartbeat` 仍免 | **落实** | `run/RunStore.java:575-589`（只对 heartbeat 早返回；delta 走 `touchActivity` 后照常 rightPush/trim/expire）；`conformance/InMemoryRunStore.java:363-380` 同口径 |
| 4 | 回放取消 delta 跳过，孤儿过滤不变 | **落实** | `run/SseChatEmitter.java:464-487`（只跳 heartbeat；`isOrphan` 未动） |
| 5 | 等待循环主体不动、零订阅 emit 无副作用 | **落实** | `ConfirmGate.java` 全文件仅 1 个 hunk（`git diff` 计数 17 行，含注释），循环 `:337-390` 逐字未改；零订阅路径 `SseChatEmitter.java:840`（取号/落档之后才 `return`），心跳分支 `:807-820`（不占业务号、不入档）；用例 `SuspendDisconnectGateTest.java:169-195` |
| 6 | 线程驻留未改 | **落实** | diff 内无 `AiExecutorConfig` / 池参数变更 |
| 7 | 恢复双路径未加围栏 | **落实** | diff 内无 `submitDecision` / `maybeTriggerResume` 变更 |
| 8 | 前端挂起收流正常化 | **落实（含 1 处实现取舍）** | `sse.ts:241`（`suspended` 判据）、`:398-401`、`:552-556`；`stores/ai.ts:1501-1507`（不置 `streamInterrupted`）、`:1553-1560`（loading 复位、suspended 保留）、`:1562-1568`（不封口消息，设计未点名 → M-2） |
| 9 | 决策后带 lastSeq 重连（confirm / GF / 回灌三入口） | **落实** | `stores/ai.ts:1412-1432`（`reconnectSuspendedRun`）、`:1941-1946`（confirm）、`:2009-2015`（`submitFrontendToolResult`，GF 提交/取消与即时前端工具共用）；前端用例 `tests/b1-suspend-disconnect.spec.ts`（confirm / submitGenerativeForm / cancelGenerativeForm 三例均断言 `options.lastSeq` 与"不新起气泡"） |
| 10 | 停止钮 `loading \|\| suspended`、样式零动；stop 清态 | **落实** | `components/AiPanel/AiPanel.vue:744`（仅 `v-if` 一行）；`stores/ai.ts:2264-2267`；用例 `b1-suspend-disconnect.spec.ts`（可见 + 负控制 + stop 清态） |

**补充**：多卡请求帧"不被截断"的机制我已按并发路径复核——`emit()` 在 `emitLock` 内完成入队并 `kickDrain`（`SseChatEmitter.java:899-916` 先置 `draining` 再提交任务），`completeWhenDrained`（`:1246-1262`）先置 `terminating` 再 CAS，因此不存在"drain 已过判空点、收尾标志晚到"的丢唤醒；队列非空时必走 drain 排空后才 `completeOne`，`writeInFlight` 由 `completeOne`（`:1114-1130`）交接给写出线程。**结论：请求帧不会被截断，complete 不会先于最后一帧 flush。**

## 4 B：夹带扫描

逐文件核对：**未发现设计稿未点名的行为/样式/文案夹带**。逐条登记：

- `frontend/src/api/sse.ts`（+35/−4）：设计稿未逐字点名，但属裁决 8 的必要实现点（不改则挂起断连会在 1ms 内被客户端自己重挂、挂起期继续占连接），施工报告 §6 偏差 1 已如实申报 → 认可。
- `stores/ai.ts:1562-1568`"挂起收流不封口消息"：设计稿未写，属实现取舍（偏差 2）→ 见 M-2。
- `AiPanel.vue` 仅 `v-if` 一行，class/结构/文案/边距零改动；`types/ai.ts` 仅新增可选字段。
- `scripts/verify-e2e.ps1` **零改动**（`git status`/`git diff` 均无该文件）；抽查脚本：`Read-Sse` 在卡面帧早退（`:230-276`）、`Invoke-AiTurn` 与 GF 蓝本均以 `?lastSeq` 续读（`:284-298`、`:1235-1249`）、TC23 决策后用 `GET /events/{runId}` 全量回放且只断言 `done` 恰 1 帧（`:2235-2248`）——delta 入档不会打红现有断言。
- 文档面：`03-技术方案文档-v2.1.md` 仅文末追加注记；`任务看板.md` 仅该行状态/依据；`决策看板.md` 未动；新增设计稿与施工报告按批入库。唯一遗漏 = `docs/research/README.md` 未补登新设计稿（M-7）。

## 5 C：缺陷猎手（含实证）

### C-1【Critical，已实证】挂起收流后，即时前端工具（非 GF）的回灌被"旧轮守卫"误杀

- 机制：`frontend_tool_request` 帧到达后 `handleFrame` 起 `runFrontendTool`（`stores/ai.ts:2688` 起），其首行捕获 `controllerAtStart = currentAbort`（`:3004`）；服务端在该请求帧后**立刻**收流（裁决 1）→ `send()` 的 `onClose` 走 `info.suspended` 分支 → `finally` 里 `currentAbort = null`（`:1550-1560`）。当 `executeFrontendTool` 在收流之后才 resolve（页面就绪轮询 50ms 一档、导出文件编辑器/下载的串行 HTTP 等），`:3028` 的 `currentAbort !== controllerAtStart` 判真 → **直接 return**：既不发回灌 POST（`:3048`），也不写段终态（`:3038-3045`）。
- 后果：工具段永远停在 `running`；后端只能等满 120s 前端工具上限 → `FRONTEND_TIMEOUT` 结局帧（只入档，无人连接）→ 本轮继续跑但客户端无连接；面板长期停在"挂起/流式"，需刷新或再次动作才收敛。
- 波及面：后端 `@ToolChannel(FRONTEND)` 的 6 个非 GF 工具（`navigate_to` / `select_definitions` / `set_condition` / `confirm_step` / `open_export_file_editor` / `download_export_files`）。GF 不受影响（不走该执行器）。
- **实证（仓外临时 harness，未改仓库任何文件）**：`<TMP>/_review-b1/spec/b1-frontend-tool-probe.spec.ts` + `<TMP>/_review-b1/vitest.temp.config.ts`（root 指向前端、alias 指向真实 `src`、`node_modules` 软链），命令：
  `node frontend/node_modules/vitest/vitest.mjs run --config <TMP>/_review-b1/vitest.temp.config.ts`
  输出：
  - 慢工具（执行 50ms、收流 5ms）：`suspended=true loading=false part.status=running postCalls=0 execCalls=1` → 断言 `frontendToolResult` 调用 1 次 **失败**（0 次）。
  - 对照（执行 0ms、先于收流）：`suspended=true postCalls=1` → **通过**。
  即：能否回灌完全取决于"工具执行 vs 收流的赛跑"，且慢侧必然输。
- 现有测试为何没抓到：`tests/tool-outcome-envelope.spec.ts:280`、`tests/parts-residue-cleanup.spec.ts:619` 都是**直接调用** `runFrontendTool`（`currentAbort` 前后同为 null），绕开了"流内挂起收流"的时序；新 spec 只覆盖 GF 与 confirm。
- 建议修法（不代施工定稿）：把轮次身份判据从"AbortController 恒等"改为"仍是同一 run/宿主消息且未被 stop/新轮取代"，或在挂起收流的 `finally` 里保留 `currentAbort`（由决策后重连或新一轮来替换），并补一条"慢前端工具 + 挂起收流仍回灌"的回归锁。

### C-2 挂起断连后 ConfirmGate 每 2s `out.heartbeat` 的零订阅副作用

结论：**无副作用**。`emit()` 心跳分支（`SseChatEmitter.java:807-825`）只 `heartbeatSeq.incrementAndGet()` + `touchActivity` + 序列化，随后 `:840` 在 `subscribers.isEmpty()` 时 return：不取业务号、不落档、不扇出、不抛异常；等待循环其余三步（`renewSessionCurrent` / `renewSuspensionTtl` / `beacon.pulse`，`ConfirmGate.java:366-377`）逐字未改。用例 `SuspendDisconnectGateTest.java:169-195` 锁住"心跳不占业务号、归档无心跳"。

### C-3 completeSubscriptions 与在途 writeFrame/drain 的竞态

结论：**不会截断请求帧、不会 complete 先于最后一帧 flush**（推理见 §3 补充）。唯一残留是"`completeSubscriptions` 之后新 attach 的连接不会被本次收尾"——见 M-4。

### C-4 决策后重连的补帧完整性 / 重连失败是否丢态

- 补帧：前端 `lastSeq = runSeq[runId]`（`:1430`），`runSeq` 由 `handleFrame` 对所有带 `seq` 的帧单调推进（`:2578-2585`），服务端 `stageReplay` 以 `seq > lastSeq` 差量 + 回放与挂订阅同锁（`AiController.java:306-321` → `SseChatEmitter.replayAndAttach:400-410`）⇒ 决策→重连窗口内的 `confirm_decision` / `delta` / `tool_start` / 第二次 `confirm_request` 均可补回，无缺口。前端按 seq 幂等（`alreadyApplied`，`:2604-2608`）不双渲染。
- 重连失败：`resumeSnapshotStream` 的 `onClose`（`info.suspended` 早返回 / 断流提示）与 `finally`（复位 `loading`、**不**清 `suspended`，`:1396-1404`）保留 `suspended`/`runSeq`/`activeForm`；`suspended` 只在决策收口 / 终帧 / 停止时清 → 失败后"一键重挂 + 再决策"仍可用。**未见丢态**。

### C-5 120s 前端工具超时在断连后的收敛路径

结论：**卡片收敛路径仍在，但本轮的流不再自动收敛**。超时后条目转 `TIMEOUT`，用户提交得 409 → `confirm()` 走 `armGateSync`/`syncGateFromAuthority`（`:1953-1964`）/ 回灌 409 按 `status` 就地收敛（`:2028-2042`）；但 409 分支**不重连**，本轮后续帧（含 `done`）只入档，气泡停在流式态直到刷新/手动重挂。设计稿 §4 只承诺"提交时经 409 收敛"，此形态与之一致但体验残缺；与 C-1 叠加时更明显（C-1 会让"提交"本身发生不了）。

### C-6 刷新恢复主路（restoreSuspendedSnapshot → archiveMaxSeq）

结论：**delta 入档后口径仍闭合**。`archiveMaxSeq = RunStore.lastEventSeq`（`AiController.java:478`）读归档末帧 `seq`（`RunStore.java:730-744`），delta 入档后该值=已发放最大业务号；`resumeSnapshotStream` 以它为 lastSeq（`stores/ai.ts:1340-1341`），回放集为空 ⇒ 不会把已渲染正文重复拼一遍；后续决策的 `lastSeq` 也由同一次调用写进 `runSeq`（`:1351`）。**但**：这条恢复路径会在挂起态**常驻一条连接 + 2s 心跳**（服务端没有第二次"收流"触发点），"挂起期不占连接"对刷新后的会话不成立 —— 设计稿 §3 已写明此形态，属设计级缺口（M-3）。

### C-7 stop() 挂起态残留（施工报告偏差 3）

结论：**属实**。`stop()` 清 `suspended`/`activeForm` 并 POST cancel（`:2260-2271`），但：①不封口消息（`send()` 已在挂起收流时跳过封口，`:1565`）→ 气泡永久 `streaming`；②不落确认卡终态 → 卡仍可点（点击走 409→`syncGateFromAuthority` 收敛）。后端取消会发 `confirm_decision(cancel)`+`done`，但已无连接接收（只入档）。功能可收敛（刷新/再操作），观感有残留 → M-2。

### C-8 测试口径是否只升不降 / 后端 10 类改法是否掩盖回归

- 逐类通读：`ReattachLastSeq` / `SeqPersistence` / `SseFrameSequence` / `SseChatEmitterFrames` / `SseChatEmitterWriteBudget` / `SseFrameSizeLimit` 均为**跟随契约变更的等价或更强断言**（回放帧数 4→5、归档含 delta、按 `tool_result` 定位归档帧）。
- `ChatTurn` / `EmptyReply` / `ToolFrameSequence` / `ToolPreflightGate` 引入 `clientView = 归档 ∪ 各连接帧（按 seq 去重）`：符合"客户端最终持有的流 = 首连 + 重连差量"，但并集视角**会掩盖"实时投递丢失"**（某帧只进档、没投递也照样通过）。关键点仍有 wire-only 断言兜底（`decisionReceiptIsWrittenBeforeTheConsequenceItWakes` 的 `receiptInSend` + "回执未达时 `wire` 里不得有 `tool_result`"；`SuspendDisconnectGateTest` 断言请求帧真投递后订阅归零）→ 记为 Minor（M-5），不构成掩盖。
- 前端 4 个既有 spec：3 处补 `reattachRun` 桩（决策后重连会调它，必须补）+ 1 处"挂起态发送钮 disabled"改为"发送钮不渲染、停止钮渲染"（与裁决 10 一致，第二段仍断言 `disabled`）→ **未降级**。

## 6 D/E

- **D 门禁**：见 §2，前端全量 + 后端全量 + typecheck + check-docs 均亲测通过；施工报告唯一不实项 = 后端用例总数（M-1）。
- **E 脱敏**：全量 diff 字节内 `sk-` / `ghp_` / `AKIA` / `AIza…` / `xox?-` / PEM 私钥头 / `api_key=…` 模式 **0 命中**；无真实密钥前缀。

## 7 问题清单

### Critical（1）

| ID | 问题 | 落点 |
|---|---|---|
| C-1 | 挂起收流后即时前端工具（6 个非 GF 工具）执行晚于收流 ⇒ 轮次守卫误判"旧轮"，**不回灌、不落终态**，段卡 `running`、后端 120s 超时、UI 不收敛（仓外探针实证 postCalls=0 / execCalls=1） | `frontend/src/stores/ai.ts:3004`、`:3028-3030`、`:3038-3048`；触发链 `:1550-1560`、`:2688`、`backend/.../ConfirmGate.java:336` |

### Important（0）

无。

### Minor（9，可随批闭环或挂账）

| ID | 问题 | 落点 |
|---|---|---|
| M-1 | 施工报告后端门禁数字失真（自报 566/566，实测 **576/576 · 91 类**；基数 563 已过期）。门禁本身全绿 | `docs/evidence/补档-B1挂起即断-施工报告.md:100` |
| M-2 | 挂起收流不封口消息（设计未点名）；叠加 stop()/409 路径时气泡可长期停留流式态，无终帧到达 | `stores/ai.ts:1562-1568`、`:2264-2271` |
| M-3 | 刷新恢复主路在挂起态建立并常驻连接（2s 心跳），"挂起期不占连接"对刷新后的会话不成立；设计 §3 已写明，属设计级缺口，建议 B1.x 让挂起态 attach 回放后 complete | `stores/ai.ts:1340-1341`、`backend/.../SseChatEmitter.java:400-410` |
| M-4 | 多卡批次重连竞态：若决策后重连的 attach 落在 `completeSubscriptions` 之后，该连接不会被本次收尾（施工报告偏差 6 的"第二张卡再收流"不是结构性保证）。无数据损失 | `SseChatEmitter.java:1222-1236`、`ConfirmGate.java:336` |
| M-5 | 测试口径：`clientView = 归档 ∪ 实时` 的并集会掩盖"实时投递丢失"；建议关键帧保留 wire-only 断言 | `conformance/ToolFrameSequenceConformanceTest.java:720-737`、`ChatTurnFrameSequenceConformanceTest.java:304-315`、`gate/ToolPreflightGateTest.java:350-362` |
| M-6 | 存量代码：`frontend_tool_result` case 无条件 `suspended=false`，多卡批次里前端工具结局帧会提前撤下停止钮（B1 放大） | `stores/ai.ts:2728` |
| M-7 | 新增设计稿未补登 `docs/research/README.md`（该文件明示"新增沉淀件请同步追加一行"，check-docs 不覆盖） | `docs/research/README.md` 末节 |
| M-8 | `EmptyReplyTerminalTest` 方法上方残留两段 javadoc（重复注释） | `run/EmptyReplyTerminalTest.java:247-255` |
| M-9 | `sse.ts` 的 `awaitsInput` 守卫覆盖面宽于"服务端主动收流"：一旦本连接见过卡面请求帧，之后任何传输错误都不再自动重连（真实断流会被误判为挂起，靠下次决策/手动重挂自愈） | `api/sse.ts:540-556` |

## 8 结论

**CHANGES-REQUESTED** —— 有 1 条 Critical（C-1：挂起收流后即时前端工具回灌与终态整体丢失，已用仓外探针复现）。十条裁决的**主链**（断连点、delta 入档、差量回放、前端挂起收流正常化、决策后 lastSeq 重连、停止钮可见性）均忠实落实且门禁全绿；C-1 是"新断连语义 × 存量轮次守卫"的交互缺陷，收口后本批即可 READY。

**收口建议**：修 C-1（并补 1 条慢前端工具回归锁）→ 顺手清 M-1/M-8（文档与注释）→ 其余 M 项可挂账。

### 附：仓外探针复现（不涉仓库改动）

```
<TMP>\_review-b1\
├── vitest.temp.config.ts          # root=frontend，alias '@'→frontend/src，pinia/vue 去重
├── node_modules -> frontend\node_modules（软链）
└── spec\b1-frontend-tool-probe.spec.ts
# 运行（cwd 任意）：
node <REPO_ROOT>/frontend/node_modules/vitest/vitest.mjs run \
     --config <TMP>/_review-b1/vitest.temp.config.ts
# 实测：[probe] suspended=true loading=false part.status=running postCalls=0 execCalls=1 → FAIL
#       [probe-fast] suspended=true postCalls=1 → PASS
```
