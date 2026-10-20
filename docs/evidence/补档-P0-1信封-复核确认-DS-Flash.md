> 入库注记（2026-10-20）：出处 = `agents/agent-1192/wire.jsonl` 末条 assistant 正文（会话 `<SESSION>`，6422 字符），作者 = agent-1192（DeepSeek/deepseek-flash，profile=explore，收口修复核确认方）；按 DC-08 脱敏 2 处（本机绝对路径 → `<SESSION>`、仓库目录名 → `<REPO>`），其余逐字一致（零改写）。
# 复核确认报告 · P0-1 统一结果信封（结论 = **通过**；附 3 条非阻塞残留观察）

## 自证行

`<SESSION>/agents/agent-1192/wire.jsonl` 的 `profile.bind` 单行首部原样：

```json
{"type":"profile.bind","agentId":"agent-1192","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.\n\nYour primary goal is to help users with software engineering tasks.\n\nYou are now running as a subagent. All the `user` messages are sent by the main agent.
```

## 结论：**通过**

C1/C2/I1 的修复属实，M4/M1 属于"修严"而非放松，三道门禁全绿。附 3 条非阻塞建议（下方"残留观察"），不构成打回理由。

---

## 一、C1 / C2 / I1 修复真伪

**C1（结算后重读）——真。** `<REPO>/backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:235-240`：循环末尾统一 `settled = store.pending(...)`，缺失才回落 `pending`，随后 `modelData(source, text)`；该行在 for 循环内、三通道共用，不存在只覆盖某一路的分支。三条通道的结算都在重读之前落到外置存储：BACKEND 走 `executeOnce` 的 `putPending`（:413）→ 发帧；CONFIRM 放行后由 `executeOnce(resolved)` 写入，拒绝/超时由 `ConfirmGate` 写（:499-505 / :386-390）；FRONTEND 由 `submitFrontendResult`（:585-590，`putPending` 先于发帧、发帧先于唤醒）、越 scope（:305-309）、入参被拒（:373-374）写入。而 `awaitConfirm`/`awaitFrontend` 只返回文本、丢弃 `resolved` 副本（:335-341、:381-389），所以旧写法里循环变量确实停在 PENDING —— 这正是缺口。
新用例 `ToolFrameSequenceConformanceTest.java:193` 在旧实现下会红：`InMemoryRunStore` 往返模式（:67-91、:247-258）使 `pending()` 返回 JSON 深拷贝，等待前的 `pending` 与存储副本断链；旧代码组装信封时 `effectiveOutcome()` 会落到 `fromStatus("PENDING") → UNKNOWN`、`ok=false`，而用例断言 `envelope.outcome()==ok`（:214）——必红。需要说明的是同用例里 `store.pending(...).getOutcome()==ok`（:219）在旧实现下也是绿的（`executeOnce` 写的是副本），所以"红"的判据完全来自信封断言，用例注释的 kill 描述成立。

**C2（历史解包）——真，且只动回显通道。** `toHistoryItem` 仅被 `/history` 端点调用（`AiController.java:537`、:553），它按 Message 重新拼 Map，不改模型记忆；解包只作用于 `toolResponses[].responseData`（:580-590）。模型侧 `role:tool` 仍是完整信封（`SpToolCallingManager:525-528`、`ResumeService:316-320`）。
三键判据（:600-603 要求 `outcome` 文本 + `retryable` 布尔 + `message` 文本）的误判面：信封由 record + `ALWAYS` inclusion 产出，三键恒在；反向风险是"旧数据里恰好含这三键的普通 JSON"。当前全仓没有任何工具把 JSON 写进 `responseData`（工具结果都是文本），GF 提交值是**嵌在信封一层之内**的，解包只做一层、不会二次递归，所以取回的正是提交值原文。误判概率极低，但见建议 ①。

**I1（两帧四字段 + 前端 outcome 优先）——两端一致，旧帧回落真。** 后端 `SseChatEmitter.java:583-599`（tool_result）与 :644-657（frontend_tool_result）用同一 `effectiveOutcome()/effectiveCode()` + `retryable()` + `targetOf()` 推导；前端 `parts.ts:572`（tool_result 走 `outcomeStatus(frame.outcome) ?? ok 三元`）与 :569-574（frontend_tool_result 走 `outcomeStatus(outcome) ?? frontendResultStatus(status) ?? ok`），`outcomeStatus` 缺键/域外值返回 `null`（:326-344），旧帧逐字走旧表；`frontend_tool_result` 的 `localTerminal` first-wins 未变。

---

## 二、断言强度（M4 + M1）

**M4 是修严。** `toolResult` 现在先算 `outcome`，`ok != outcome.ok()` 直接 `IllegalArgumentException`（`SseChatEmitter.java:583-586`），且在组帧/归档之前抛出（用例 `SseChatEmitterFramesTest` 用 `verify(store, never()).appendEvent` 锁住了这一点）。生产五个调用点逐一核对全部自洽：:309（BLOCKED/false）、:387（REJECTED|TIMEOUT|CANCELLED/false）、:429（FAILED|BLOCKED/false）、:475（`ok` 与 outcome 同一 try 分支赋值）、:415 台账复用改为传 `pending.effectiveOutcome().ok()`。复用路径是实质修严：旧实现恒传 `true`，会把台账里已持久化的失败结局写成成功帧，现在结构上不可能；旧台账（无 `outcome`）仍回落到 OK，兼容口径未变。

**M1 双保险两侧都在。** 后端 `ResilientChatService.java:723-731`：PENDING/APPROVED 不写 `outcome` 键（`code` 仅在可推导时写），有 `ConvergeMemoryToolCallStatesTest` 新用例锁死 `doesNotContainKey("outcome")`；前端 `frontend/src/stores/ai.ts:1171-1174`：`statusDerived === 'pending'` 时无条件保留 pending，`outcome` 不能把它覆写成 failed。两侧独立成立，任一失效另一侧仍兜得住。

**残留观察（非阻塞）：** `frontendToolResult` 没有加同款 fail-fast，`ok` 仍由调用方传；当前 5 个调用点（`ConfirmGate:362/405/588/625`、`SpToolCallingManager:374`）全部与 outcome 自洽，且前端以 `outcome` 为准，所以不会出帧面矛盾——但对称性上不如 `toolResult`。

---

## 三、门禁抽查

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端定向 | `mvn -o -q -f backend/pom.xml -Dtest='ToolFrameSequence*,SseChatEmitter*,ResumeEnvelope*,AiControllerHistory*,ConvergeMemory*' test` | **exit 0**（`pipefail` 下取 `PIPESTATUS[0]`；报告时间戳 11:52，确为本轮新跑） |
| 前端 | `cd frontend && yarn test` | **exit 0**，24 文件 / **477 用例全绿** |
| 文档 | `node scripts/ci/check-docs.mjs` | **exit 0**，6/6 断言绿 |

定向套件用例数（`backend/target/surefire-reports`）：ToolFrameSequence 10、SseChatEmitterFrames 15、SseChatEmitterDelivery 18、SseChatEmitterWriteBudget 6、ResumeEnvelopeShape 1、AiControllerHistoryPayload 10、AiControllerHistoryDelete 11、ConvergeMemory 14 —— failures/errors 全 0。未跑全量后端套件（超出本轮指定门禁）；仓内 `git status` 在我只读复核前后一致（36 行，无新增产物）。

---

## 四、偶发失败判定：**与本批无关**

依据三条：

1. `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWriteBudgetTest.java` **不在本批改动清单**（`git status` 中无该文件）；
2. 生产侧 `SseChatEmitter` 的本批 diff 只有"构造函数里 `objectMapper.copy()` + 两处帧字段"，**未触碰投递池/写预算/`completeOne` 收尾**任何一行；
3. 报错断言是 `:119` 的 `completeThreads().allMatch(name -> name.startsWith("budget-writer"))`，而 `completeOne`（`SseChatEmitter.java:1076-1088`）的注释明确写了收尾由"置位 → 复查"两侧**任一侧**完成（"任一侧判定写在飞已结束就由它立即收尾"）——说明书写明两种线程都可能合法收尾，`budget-delivery-1` 正是复查窗口失败时的另一侧。这不是产品回归，而是该用例把"合法的另一侧"写成了断言。我这轮 6/6 复跑全绿，未能复现。

建议另开小修单：把该断言放宽为"收尾线程不是出帧/调用线程"或接受两种线程名（不要在本批改）。

---

## 五、入库锚 SHA（候选）

`git diff | sha256sum`（29 文件，+961/−129）：

```
fc4e4f8b01ca4d334552a1a08a0ce053cce1e878424b3d9514e74906b90af072
```

未跟踪新文件逐个（已排除 `data/`；`data/` 另有未跟踪内容，未计入）：

| SHA-256 | 文件 |
|---|---|
| `52d55ec9077a866a90f8f715c8f43d53000ed3ac5b416c0f85e97486762170c9` | backend/src/main/java/com/example/configmgr/ai/tool/ToolOutcome.java |
| `1c666738db1236e9c0fb3e9374c1263fec867c00af2389953959747b476d9461` | backend/src/main/java/com/example/configmgr/ai/tool/ToolResultEnvelope.java |
| `95a4d33f1801ec03c171f768d8844fe96cb4fb758ec2eba01be2368f7a4486b3` | backend/src/test/java/com/example/configmgr/ai/gate/ToolOutcomeExitTest.java |
| `93ba7b52278889734ca78e88dc68aecc074f388a33802b824d7927b228b8b70c` | backend/src/test/java/com/example/configmgr/ai/run/ResumeEnvelopeShapeTest.java |
| `84a706d9eeade18c0217b4e6b48bb0ef18dff633c983466d14e1b06e685dffa9` | backend/src/test/java/com/example/configmgr/ai/tool/ToolResultEnvelopeTest.java |
| `8e5fa6754e1a3a55df57b3a989a9c21168a9cf115f182398d7b849b37174a46d` | frontend/tests/tool-outcome-envelope.spec.ts |

## 残留观察（3 条，均不阻塞）

1. **C2 判据缺一条负向用例**：只测了"信封解包"与"纯文本 `OK` 透传"（`AiControllerHistoryPayloadTest.java:145`），没有测"普通 JSON 对象（非信封）原样透传"。建议补一条 `{"message":"x","other":1}` 不被吞掉的用例，把三键判据钉死。
2. `unwrapEnvelope` 用 `objectMapper.readTree` 解析失败只 `debug` 日志并透传——行为正确，但建议保留（现状已是透传）。
3. `frontendToolResult` 可与 `toolResult` 同样加 `ok != outcome.ok()` 的 fail-fast，避免未来新调用点再犯"帧 ok 与结局面矛盾"。