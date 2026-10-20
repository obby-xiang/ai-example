> 入库注记（2026-10-20）：出处 = `agents/agent-1205/wire.jsonl` 末条 assistant 正文（会话 `<SESSION>`，4696 字符），作者 = agent-1205（DeepSeek/deepseek-flash，profile=explore，收口修复核确认方）；按 DC-08 脱敏 1 处（本机 wire 绝对路径 → `<SESSION>`），本件除本注记行与本标题行外与源件逐字一致（零改写）。
# 复核确认报告 · F1 批内首错即停（结论 = **通过**；I-1/I-2/I-3 真修 + M-1/M-3/M-5 真锁）
## 自证行（agent-1205，原样引用）

`<SESSION>/agents/agent-1205/wire.jsonl` 第 3 行：

```
{"type":"profile.bind","agentId":"agent-1205","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.\n\nYour primary goal is to help users with software engineering tasks.
```

## 结论：**通过**

I-1/I-2/I-3 三处修复均为真实修复且带有效锁；M-1 注释 sweep 未出现把事实性「七」误改成「八」；M-3/M-5 是字节级/帧级真锁；三道门禁全绿。

## 三点核对

**1) I-1 / I-2 / I-3 修复真伪**

- **I-1 真**。`backend/src/test/java/com/example/configmgr/ai/conformance/FrameContract.java:239-256` 的豁免只发生在「结局帧无 start」这一支，且判据是 `isSkippedOutcome`（`outcome=skipped` 或 `status=SKIPPED`）；「有 start 无结局」「start 早于结局」「重复结局」三支未动，非 skipped 结局无 start 仍报 `没有 tool_start`。豁免正控 `acceptsSkippedOutcomeWithoutToolStart:138` 与边界杀回 `killsNonSkippedOutcomeWithoutToolStart:152` 成对存在；全链路 `ToolFrameSequenceConformanceTest.firstDeterministicFailureSkipsTheRestAndSkippedFrameHasNoToolStart:424` 断言帧序恰为 `start,suspended,tool_start,tool_result,tool_result,done`、`tool_start` 只有 `call-w`、跳过帧 `outcome=skipped/status=SKIPPED/executed=false`、`skippedInvocations=0`，并走真实 `SpToolCallingManager.executeToolCalls` + emitter。删豁免 ⇒ 该用例第 4 帧无 start 必红，机制成立（只读卡禁改，我未做变异实跑，但正控/边界双用例已覆盖杀回方向）。`FrameContractFalsificationTest` 我单独跑了 **17/17 绿，EXIT=0**。
- **I-2 真**。`AiToolsDisclosureEndpointTest.java:154-161` 是 `@SpringBootTest` 真实上下文注入的 `ToolRegistry`（:47），逐名断言 `create_task→W / start_export→J / start_precheck→J / start_import→J / start_publish→P`，五名字一一对应 `AiTools.java:251/303/323/339/350` 的真实 `@ToolCategory` 注解，非集合近似。fail-open 由 `SpToolCallingManagerBatchStopTest.undeclaredCategoryFailureKeepsTheBatchGoing:218` 锁死（未声明类别 → `categoryOf=null` → 不停批），`ToolRegistry.java:158-160` 与 `SpToolCallingManager.java:303-305` 的判据同源。
- **I-3 真**。`frontend/src/utils/parts.ts:482-496` 的 skipped 早分支只写 `result`（`frame.result ?? frame.message`）、**不写 `reason` 键**，也无 `reason` 透传。数据层锁在 `tool-outcome-envelope.spec.ts:397-434`：`hasOwnProperty(part,'reason')===false`、`part.reason===undefined`、`[result,reason]` 中含文案者恰 1 个，另一条 `:436-455` 锁旧帧缺 result 回落；渲染锁 `ai-panel-parts-render.spec.ts:1514-1529` 做过非空验证——`wrapper.text().split(TEXT).length-1 === 1`（0 会红）且 `tool-error`/`ro-reason-sk1` 均零命中。

**2) 断言强度 / M-1 sweep**

- **M-3 真锁**。`ResumeSkipCascadeTest` 新增两条：`pendingBackendCrashWindowStillCascadesToSkipped:170`（PENDING+BACKEND 崩溃窗口不被 `PENDING_UNRESOLVED` 挡、补执行一次后级联 SKIPPED）、`secondResumeDoesNotReplaySettledSkipsOrEmitDuplicateFrames:199`（退回快照后二次续跑：`never()` 对 c-2/c-3 发 `toolResult`、`resolvedAtMs` 原值不变、重放历史仍恰 3 条）。不重放是方法级 `never()` + 计数双重锁，不是结构性推断。
- **M-5 真锁**。`replaySkippedEnvelopeIsByteIdenticalToFirstRoundSkip:246` 先直接调 `skipAsUpstreamFailed` 取首轮 `ToolResponse.responseData()` 基线，再跑续跑取重放条目，`isEqualTo` **字符串逐字节相等**，跨路字节同形被钉死。
- **M-1 sweep 无误改**。全前端已无「七态/7 态」残留；所有「八态」都指向 `ToolOutcome` 的 8 值（ok/failed/rejected/blocked/timeout/cancelled/unknown/skipped，见 `ToolOutcome.java:24-41`）或 `isFrameDecidedPendingStatus`/`localTerminal` 的 8 个已决态（`ai.ts:804-808`、`parts.ts:597-600` 实为 8 个），`blocks.ts:476` 那段由「八态」改「九态」也正确（含 skipped 的卡片状态共 9 个）。**agent-1203 的判断成立**：`ai.ts:735`「实发交集七值两表同映射」未改（`git diff -U0` 该串零命中，属纯上下文外的未触碰行），且事实正确——`parts.ts:252-253` 权威孪生注释逐条列出这 7 值 `FRONTEND_RESULT/FRONTEND_CANCELLED/CANCELLED/TIMEOUT/REJECTED_ARGUMENTS/REJECTED/BLOCKED`；`SUCCEEDED` 是已申报分叉、`SKIPPED` 是两表同为 null 的契约增量并在紧邻段落单列，不计入该交集。

**3) 门禁抽查（全部实跑）**

| 门禁 | 结果 |
|---|---|
| `mvn -o -q -f backend/pom.xml -Dtest='*BatchStop*,*SkipCascade*,ToolFrame*,*Disclosure*' test` | **EXIT=0**；BatchStop 11、ResumeSkipCascade 4、ToolFrameSequence 11、AiToolsDisclosureEndpoint 7、另 ToolRegistry/GenerativeForm Disclosure 11/7，全 0 失败 |
| 补跑 `-Dtest=FrameContractFalsificationTest` | **EXIT=0**，17/17 |
| `cd frontend && yarn test` | **24 files / 481 tests passed**（ai-panel-parts-render 180 通过），9.09s |
| `node scripts/ci/check-docs.mjs` | **6/6 PASS**，EXIT=0 |

## 入库锚 SHA 候选

`git diff | sha256sum`（23 个已跟踪文件，+628/−58）：

```
d65d056879bd4b7a9629f9f7de158e7e624303dceacb7fa63026b5dad8a9977c
```

未跟踪新文件（排除 `data/`，恰 3 个）：

```
ecd0a69c9eaf538631153c0bd3089244c6ee5b8092e707bf3f41e7ede419cc96  backend/src/main/java/com/example/configmgr/ai/tool/ToolCategory.java
9e91fdc05d0307ccf4efb480dd914ac017818a936b1c0e3c9dbc131c11b53b5c  backend/src/test/java/com/example/configmgr/ai/gate/ResumeSkipCascadeTest.java
19a8d660a566f3b94ccea2039ff9294255e2a384e3666586c3e9e8be90962a9f  backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerBatchStopTest.java
```

工作树除此之外干净（`git status --porcelain` 仅 23 M + 上述 3 ?? + `data/`）。全程只读，未改文件、未 commit、未起服务。