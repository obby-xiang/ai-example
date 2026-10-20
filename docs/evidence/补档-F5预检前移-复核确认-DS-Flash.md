> 入库注记（2026-10-20）：出处 = `agents/agent-1234/wire.jsonl` 末条 assistant 正文（会话 `<SESSION>`，4904 字符），作者 = agent-1234（DeepSeek/deepseek-flash，profile=explore，收口复核方）；按 DC-08 脱敏 0 处；本件除本注记行与本标题行外与源件逐字一致（零改写）。
# 复核确认报告 · F5 预检前移（结论 = **通过**；MAJOR-1 与 MINOR-1/2/4 + NIT 两端闭环）
## 自证行

我自身 wire（`...\agents\agent-1234\wire.jsonl`）第 5 行 `profile.bind` 首部原样：

```json
{"type":"profile.bind","agentId":"agent-1234","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high",
```

（本次只读：未改文件、未 commit、未启动服务；仓库 `HEAD=f8c7c8c`，工作区 28 项状态 = 22 改 + 5 新 + `data/`，全程未变。）

## 结论：通过

复审 agent-1229 提出的 MAJOR-1 与 MINOR-1/2/4（含 NIT）在代码与用例两端都能找到对应实现，且回归锁确实会在“没修”时失败；三道门禁全部通过。无阻断项。

## 一、MAJOR-1 两端闭环：通过

刷新恢复链路完整：`AiController.runsCurrent` 在 `pendingEntries[]` 中增加了 `preflight`（`AiController.java:464`）；该值来自 Redis 待决条目（`putPending` 序列化 `@Data` 的 `preflight`），通过路径在 `ToolPreflightService.report.asMap()` 之后由 `saveSuspended` 持久化，因此不是“只在 SSE 帧里有”的内存值。前端 `restoreSuspendedSnapshot`（`frontend/src/stores/ai.ts:1436`）按 `entry.preflight ? { preflight } : {}` 写入工具段：带报告的条目会原样继承，不带报告的旧载荷不会产生键。`loadHistory` 的两个分支（空历史 :1123 / 非空历史 :1349）都会走这个恢复函数，因此两条刷新路径都覆盖到了。回归测试是真实链路：`AiControllerRunsCurrentTest` 的 `pendingEntryCarriesPreflightReportForRefreshRestore`（9 例全绿）从 HTTP 端点断言 `data.pendingEntries[0].preflight.{reportHash,impact,checks[0].name,target.taskId}`；`ai-panel-parts-render.spec.ts:356` 通过真实挂载组件 → `onMounted` → 真实 `loadHistory`（该 spec 只桩 `@/api/ai`，不桩 store 方法）→ 空历史分支 → `restoreSuspendedSnapshot`，然后断言 DOM 里出现 `preflight-summary`、`✓ 预检通过`、`预检指纹新鲜（…3a9f…）`；`restore-reconcile.spec.ts` 另加正例②（带 preflight 保留）+ 反向断言（无 preflight 不产生键）。结论：刷新后预检摘要确实回来了，不是“只造了类型”。

## 二、MINOR-1/2/4：三处都是真修

- **MINOR-1（越 scope 不预检）**：`SpToolCallingManager` 在预检前先要求 `toolRegistry.matchesContext(...)`，不满足即 `continue` 进 `suspended` 列表，交给既有 `blockOutOfScope`（`SCOPE_NOT_DISCLOSED`）。`ToolPreflightGateTest.outOfScopeStartToolSkipsPreflightAndBlocksAsScopeViolation` 用 `verifyNoInteractions(this.preflight)` 提供**预检零调用**的强证据，并同时断言帧里无 `confirm_request`、`tool_result.code=SCOPE_NOT_DISCLOSED/status=BLOCKED/executed=false`、工具零执行、条目落库为 BLOCKED。
- **MINOR-2（被动锚点不污染看门狗）**：`ToolActivityBeacon.markPassiveActivity` 只 `lastActivityAtMs.computeIfAbsent(...).set(now)`，既不碰 `started`、也不碰 `active`（`startedCount`/`hasActivity`/`isActive` 均不受影响），与 `enter` 语义彻底分离。用例按顺序 `InOrder` 断言 `markPassiveActivity → inspect → markPassiveActivity`，并 `never().enter(...)`。实现层面“不动 startedCount”可直接从源码确认。
- **MINOR-4（PRECHECK_UNAVAILABLE fail-safe）**：`inspect` 抛 `RuntimeException` 时走 `blockPreflightUnavailable`：状态 `PRECHECK_FAILED`、`outcome=failed`、`code=PRECHECK_UNAVAILABLE`、`executed=false`、`retryable=true`，措辞明确“这不代表条件已满足”。用例断言帧序列**恰好** `start, tool_start, tool_result, done`（无 suspended/confirm_request）、`gate.hasActiveLatch=false`、`pendingGates=0`、工具零调用——即“不进门、不放行”成立。
- 附带核实：NIT（`PRECHECK_FAILED` 补进 `isTerminalEntryStatus`）与时间倒挂修正（`saveSuspended` 跳过非 PENDING 条目）都已在 `ResilientChatService` / `RunStore` / `InMemoryRunStore` 同步落地，并有 `ConvergeMemoryToolCallStatesTest.precheckFailedEntryIsTerminalEvenWithoutPersistedOutcome`、`RunStoreSaveSuspendedTest.settledEntriesKeepTheirRequestedAtMs` 锁住。`JobService` 侧的 `finalizedConflict/inFlightConflict/precheckConflict/latestPrecheck` 是把执行路径原守卫抽成只读口径后复用（`assertX` 反过来调用它们），符合“判据只借不抄”，没有双份逻辑漂移。

## 三、门禁与锚

| 门禁 | 结果 |
|---|---|
| `mvn -o -q -f backend/pom.xml -Dtest='ToolPreflight*,AiControllerRuns*,RunStoreSaveSuspended*,ConvergeMemory*' test` | **EXIT=0**；surefire：ToolPreflightGateTest 6/6、ToolPreflightServiceTest 9/9、AiControllerRunsCurrentTest 9/9、RunStoreSaveSuspendedTest 1/1、ConvergeMemoryToolCallStatesTest 16/16，全 0 失败 |
| `cd frontend && yarn test` | **498 passed / 24 files**（符合预期 498） |
| `node scripts/ci/check-docs.mjs` | **EXIT=0，6/6 断言绿** |

## 入库锚清单（候选）

基线：`HEAD f8c7c8c`；工作区 `git diff --stat` = 22 files, +749/−46。

- `git diff | sha256sum` = `2a789d61decca122127dd2aa3e4243ff5ee64a93e6d5db5c5576ebc0bf78287e`
- 未跟踪新文件（已排除 `data/`）：
  - `a8cfba56551e9e68e1f4e8270e5cd0e9ffed31233834e00c44ec1f4cf46fb86f`  `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`
  - `9be58c8371a0144fa0db51eab2bceaa36e0306cda951a9a3db2b82f8661ccecb`  `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightGateTest.java`
  - `3172c09d64fa80ca0f6303a1adeddd1d6661e57c796d5ef488a5de2c854049df`  `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightServiceTest.java`
  - `ef63e3a21a5000a16ab4e31344607ce4ee17114a63c58f1628cb76fb0b004fee`  `backend/src/test/java/com/example/configmgr/ai/run/RunStoreSaveSuspendedTest.java`
  - `d60dc79c09ea64dbe9ec75b515f743b27030488ffc7939995f9fd2bee40b584f`  `docs/demo/chat-ux/hitl-confirm-preflight.html`

（重算两次 `git diff` 摘要一致，工作区未被本复审改动。）

## 非阻断观察（不计入打回）

1. MINOR-2 的“不污染 `startedCount`”目前靠源码直读 + mock 层 `never().enter`，没有一条针对真实 `ToolActivityBeacon` 的 `startedCount` 前后比对用例；实现简单到不会误改，故不阻断，但后续若有人重排 `enter`/`markPassiveActivity` 内部实现，这层只有语义无锁。
2. `AiControllerRunsCurrentTest` 用内存 `PendingToolCall` 种入，未走 Redis JSON 往返；`@Data` 下 `preflight` 映射是常规序列化，风险极低，可选补一条 round-trip 锁。
3. “本批全部预检不通过”时 `saveSuspended` 整段跳过（快照不置 SUSPENDED、不写 inFlightToolCalls）——这是“不挂起”的正确形态，已由用例的帧序列证明；仅记录为设计取舍。