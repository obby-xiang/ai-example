> 入库注记（2026-10-20）：出处 = `agents/agent-1229/wire.jsonl` 末条 assistant 正文（会话 `<SESSION>`，7080 字符），作者 = agent-1229（DeepSeek/deepseek-flash，profile=explore，独立复审方）；按 DC-08 脱敏 0 处；本件除本注记行与本标题行外与源件逐字一致（零改写）。
# 复审报告 · F5 预检前移 + 确认卡预检摘要（结论 = **CHANGES-REQUESTED**；1 MAJOR / 4 MINOR + NIT）
## 结论

**CHANGES-REQUESTED**。后端的“预检前移、不进门、批准绑定、F1/F8 兼容”主链成立，测试也全绿；但确认卡走**刷新恢复**路径时会丢失预检摘要，demo 场景一的 1:1 只在实时 SSE 成立，用户刷新后看不到自己批准的是哪一版预检。若明确将“刷新后保留摘要”排除在本批验收外，其余问题可降为 READY-WITH-NITS。

## 自证（自身 wire 的 `profile.bind` 单行首部，systemPrompt 后续省略）

```json
{"type":"profile.bind","agentId":"agent-1229","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.\n\nYour primary goal is to help users with software engineering tasks.\n\nYou are now running as a subagent. ...
```

## 审查锚

- `git diff | sha256sum` = `0d5a16e88a92732ade94aa2a661d146c4891e317f460eaa37672cc7bb2395a25`
- 未跟踪新文件（排除 `data/`）：
  - `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java` = `fe7d711eb055bb70fb395319d273e23aa2150e8b94eb908c341002010a6c61fd`
  - `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightGateTest.java` = `7986ec4caa5a95e516a76a75498e412adf2d676468ad9854b968da73580f5a0d`
  - `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightServiceTest.java` = `321a567e9ab3c6d18c1197050cc3183d5ddeffdf5001e4d64da767aeac7de47b`
  - `docs/demo/chat-ux/hitl-confirm-preflight.html` = `d60dc79c09ea64dbe9ec75b515f743b27030488ffc7939995f9fd2bee40b584f`
- 工作区：14 个已修改、4 个未跟踪新文件；未编辑源码、未 commit、未启动服务。

## 实测

| 命令 | 结果 |
|---|---|
| 定向后端 `ToolPreflight*,*BatchStop*,ConfirmGate*,ToolFrame*` | 69 tests，0 failure / 0 error |
| 后端全量 `mvn -o -q -f backend/pom.xml test` | **575 tests，0 failure / 0 error / 0 skip**（89 个 surefire XML 汇总） |
| 前端 `yarn test` | 24 files，**494 tests passed** |
| 前端 `yarn typecheck` | 通过 |
| `node scripts/ci/check-docs.mjs` | 6/6 PASS |

## 必查项结论

1. **预检真只读：PASS。** `ToolPreflightService` 只调用 `findTask` / `finalizedConflict` / `inFlightConflict` / `precheckConflict` / `latestPrecheck`、仓库查询和文件 `digest`，无 `save/put/delete`；`FileStorageService.digest` 只读流式 sha256。  
2. **JobService 守卫抽取：PASS。** `createAndStart` 仍在原顺序调用三个守卫（`JobService.java:148-151`）；`finalizedConflict` / `inFlightConflict` / `precheckConflict` 与原 `orElseThrow` / `ifPresent` 的冲突条件、文案、`ResourceNotFoundException` 口径逐项一致，`importFingerprintConflict` 仅把 throw 改为 return，判定分支未变。  
3. **不进门完整性：基本 PASS。** 全失败批次 `suspended` 为空，因此不 `saveSuspended`、不 `arm`（`SpToolCallingManager.java:244-248`、`:237-238`）；测试断言 `hasActiveLatch=false`、`pendingGates=0`。`PRECHECK_FAILED` 在 `resolvePending` 不会执行，`restoreSuspendedSnapshot` 只重建 `PENDING`，不会被恢复成挂起卡。  
4. **批准绑定：后端 PASS。** 批准时 `confirm_decision.preflightHash` 与执行台账 `preflightHash` 同源（`SseChatEmitter.java:630`、`RunStore.java:460`）；执行侧守卫仍保留，预检不替代执行校验。  
5. **demo 1:1：结构 PASS，字段口径 PARTIAL。** 容器位置、目标行、逐项 ✓、影响面、批准绑定五行齐全，按钮为「拒绝 / 批准」；但后端实际检查名/顺序与 demo 不一致（见 MINOR-3）。  
6. **F1/F8/P0-1：PASS。** `PRECHECK_FAILED` 的 `retryable=true`，`haltsBatch` 不停批；`gate.arm` 只取 `suspended`；信封 `outcome=failed / code=PRECHECK_FAILED / retryable=true` 一致，定向测试覆盖。

## 分级发现

### MAJOR-1：刷新恢复的确认卡丢失预检摘要

- 实时路径 `SseChatEmitter.confirmRequest` 会带 `preflight`（`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:613`），但刷新恢复走 `runs/current`：`AiController.runsCurrent` 的 `pendingEntries` 只放 `toolCallId/entryStatus/kind/name/arguments/expiresAt/atMs`，**没有 `preflight`**（`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:454-464`）。
- 前端 `restoreSuspendedSnapshot` 重建段时也只写 `args/requiresAction/expiresAt/atMs`，**没有 `preflight`**（`frontend/src/stores/ai.ts:1429-1437`）。
- 后果：用户在确认卡等待期间刷新页面，卡面仍可批准，但「预检摘要 / 批准绑定」整块消失；后端仍用 Redis 中保存的预检绑定，审计不断，但用户看不到自己批准的那一版预检。demo 场景一的 1:1 在刷新路径不成立。
- 建议：`runs/current` 的条目增加 `entry.put("preflight", pending.getPreflight())`；`restoreSuspendedSnapshot` 的 `upsertToolPart` 增加 `...(entry.preflight ? { preflight: entry.preflight } : {})`；补一条 `runs/current + restore` 回归测试。

### MINOR-1：预检先于防线③，可能改写越 scope 的结论

- 预检在批前对全部 `CONFIRM` 的 `start_*` 执行（`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:199-211`），而 `matchesContext` 的防线③在后面的执行循环里（同文件 `:280`）。
- 越 scope 的 `start_*` 会先被预检读取任务（含目标标题），若预检失败会以 `PRECHECK_FAILED` 结算，而不是 `SCOPE_NOT_DISCLOSED`；若预检通过，目标标题还会进入确认卡帧。实际不会执行（后续 scope 检查仍拦），但错误码语义和帧面信息暴露被改变。
- 建议：在预检前先做 `matchesContext`；越 scope 条目跳过预检，并保持既有 `blockOutOfScope` 结算路径。

### MINOR-2：预检重活不在工具活动信标窗口内，长指纹计算可能触发看门狗

- 预检在 `beacon.enter` 之前执行（`SpToolCallingManager.java:199-211` 对比 `:276`）；`StreamWatchdog` 只有 `beacon.isActive` 为真时才不判静默（`backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:137-140`）。
- IMPORT 的预检会对每个 UPLOAD 件流式算 sha256，文件大/多时可能超过 `inter-event-timeout`，被误判为上游静默。
- 建议：增加不递增 `startedCount` 的“被动活动锚点”（避免污染重试副作用判定），在预检前后刷新 `lastActivityAtMs`；或将预检纳入一个明确的活动窗口。

### MINOR-3：demo 检查项名称/顺序与后端实发不一致，前端测试未用真实载荷

- 后端四项为「任务存在 / 类型匹配 / **无在途作业 / 预检指纹新鲜**」（`backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:69-75`、实际顺序 `:108-163`）。
- demo 场景一为「任务存在 / 类型匹配 / **预检已通过（指纹 3a9f…）/ 无在途作业**」（`docs/demo/chat-ux/hitl-confirm-preflight.html:76-79`）。
- 前端测试的 `PREFLIGHT` 夹具直接使用了 demo 的名称/顺序（`frontend/tests/ai-panel-parts-render.spec.ts:237-245`），因此没有验证真实 `PreflightReport.asMap()` 的卡面。实际运行时第三、四行文案与 demo 不一致。
- 另：`CHECK_TYPE_MATCH` 把“任务已终态”也折进“类型匹配”（`ToolPreflightService.java:122-136`），失败文案会出现“类型匹配：任务已 COMPLETED…”，语义不够直白。
- 建议：二选一——后端检查名/顺序对齐 demo，或更新 demo 并同步测试；测试至少加一条用真实 `asMap()` 的夹具。

### MINOR-4：预检异常未兜底，整轮可能因预检基础设施错误失败

- `this.preflight.inspect(...)` 的调用没有 try/catch（`SpToolCallingManager.java:202-203`）；`inspect` 只对影响面/指纹读取做了局部捕获，`findTask`、仓库查询、`task.getItems()` 等运行时异常会直接穿出 `executeToolCalls`。预检是新增的“前移检查”，不应让数据库/文件系统抖动把整轮工具调用变成失败。
- 建议：在 manager 侧捕获预检异常，按“预检不可用”降级（例如不放行也不判 `PRECHECK_FAILED`，回落到既有确认卡，或返回带 `PRECHECK_UNAVAILABLE` 的结构化失败），并补测试。

### NIT

- `historyToolCallStatus` 未收录 `PRECHECK_FAILED`（`frontend/src/utils/parts.ts:289-313`）；当前靠 `outcome=failed` 优先兜住（`frontend/src/stores/ai.ts:1184-1187`），但若历史载荷缺 `outcome`，最后会落到 `mappedStatus ?? 'succeeded'`（`:1203`），存在潜在误显示为成功。建议补映射并加回归锁。
- `PendingStatus` 未收录 `PRECHECK_FAILED`（`frontend/src/types/sse.ts:35-51`）；`isTerminalEntryStatus` 也未收录（`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:686-697`）。当前功能不破，但“状态表已穷举”的注释已过期。
- `SseConfirmDecisionFrame` 未声明后端已实发的 `preflightHash`（`frontend/src/types/sse.ts:262-273` 对比 `SseChatEmitter.java:630`）。建议补可选字段。
- `blockPreflight` 先写 `resolvedAtMs`（`SpToolCallingManager.java:428-431`），混合批次随后 `saveSuspended` 又给同一终态条目重写 `requestedAtMs`（`RunStore.java:356-358`），会出现 `resolvedAtMs < requestedAtMs` 的时间倒挂。建议 `saveSuspended` 跳过非 `PENDING` 条目，或只在挂起项上设置 `requestedAtMs`。
- `AiPanel.vue:486` 的 `data-preflight="pass"` 是硬编码，和下方动态的 `preflightAllPass` 徽标不一致；建议改成动态值。
- 证据口径：`frontend/src/types/sse.ts:56` 与 `frontend/tests/ai-panel-parts-render.spec.ts:235` 写“用户 2026-10-20 批准”，但 demo 自身 `data-review-status="pending"`（`docs/demo/chat-ux/hitl-confirm-preflight.html:2,195`），README 也标“待选择/未批准（待审）”（`docs/demo/README.md:64`）。建议统一为“用户已裁决不弹卡；demo 场景一仍待审”，避免证据链自相矛盾。

## 总体判断

- 后端核心逻辑、只读性、F1/F8、信封与批准绑定均可接受，测试覆盖也扎实。
- 唯一建议阻断的是 **MAJOR-1 刷新路径丢失预检摘要**；它直接影响本功能“让审批人看到可执行性与绑定版本”的价值。
- 其余为 MINOR/NIT，可在同一批或紧随其后收口；尤其是 MINOR-3 的 demo 字段口径和 MINOR-1 的 scope 顺序。