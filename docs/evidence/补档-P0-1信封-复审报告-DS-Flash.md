> 入库注记（2026-10-20）：出处 = 本批复审任务（agent-dauq8vlp）输出 `output.log`（会话 `<SESSION>`，12428 B），作者 = agent-1185（DeepSeek/deepseek-flash，profile=explore）；本件除本注记行与本标题行外与源件逐字一致（零改写）。
# 复审报告 · P0-1 统一结果信封（复审结论 = **CHANGES-REQUESTED**，2 Critical / 2 Important / 9 Minor）

## 自证与审查锚

自证（`agents/agent-1185/wire.jsonl` 首条 `profile.bind` 原样）：
`{"type":"profile.bind","agentId":"agent-1185","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high",...}`

- HEAD：`d48866b171887f2905b8708728d5a3ad1b35df5c`
- `git diff | sha256sum`：`6d775a19aa45025537728c201ed4d969f3db5af96516e9d066134d480b12aa92`（staged 为空）
- 新建文件 sha256：
  - `backend/.../ai/tool/ToolOutcome.java` `52d55ec9077a866a90f8f715c8f43d53000ed3ac5b416c0f85e97486762170c9`
  - `backend/.../ai/tool/ToolResultEnvelope.java` `1c666738db1236e9c0fb3e9374c1263fec867c00af2389953959747b476d9461`
  - `backend/.../gate/ToolOutcomeExitTest.java` `95a4d33f1801ec03c171f768d8844fe96cb4fb758ec2eba01be2368f7a4486b3`
  - `backend/.../tool/ToolResultEnvelopeTest.java` `84a706d9eeade18c0217b4e6b48bb0ef18dff633c983466d14e1b06e685dffa9`
  - `frontend/tests/tool-outcome-envelope.spec.ts` `5293511eb43476c7d3d31d9f3ffe280d325cbd65589ade915e07af88d4e2ed1e`
  - `data/`（未跟踪，非本批产物，mtime 2026-10-12）

## 结论：CHANGES-REQUESTED

复跑全绿（详见文末），但存在两条 Critical：一条让模型侧信封在真实 Redis 下对「确认门/前端工具」系统性失真，一条让刷新后的历史工具卡显示被裁剪过的信封 JSON。两者都被测试替身的对象同一性掩盖。

## Critical

**C1. `modelData` 用了等待前的旧 `pending`，CONFIRM/FRONTEND 两通道的模型信封在生产环境恒为 `unknown/ok=false`**
`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:217-232`

循环里 `awaitFrontend` / `awaitConfirm` 返回的是 `ConfirmGate.await` 从存储读出的**新对象**（`ConfirmGate.java:283-298` 每次 `store.pending()` 反序列化），但回填模型时仍用循环里的原始 `pending`：

```java
text = switch (pending.getKind()) { case FRONTEND -> awaitFrontend(...); case CONFIRM -> awaitConfirm(...); ... };
responses.add(new ToolResponseMessage.ToolResponse(..., modelData(pending, text)));
```

原始 `pending` 的 `status` 仍是 `PENDING`、`outcome` 为 null ⇒ `effectiveOutcome()` 走 `fromStatus(PENDING)` → `UNKNOWN`。真实 Redis 下 `pending()` 每次返回新对象（`RunStore.java:396-402`），所以：
- 前端工具回灌成功 → 模型收到 `{ok:false, outcome:"unknown", message:"<成功文案>"}`；
- 确认门放行并执行成功/失败、拒绝、超时、取消 → 模型一律收到 `unknown`，丢失 `TASK_FINALIZED` 等 code；
- 只有 BACKEND 分支（`executeOnce` 原地改同一对象）是对的。

`ResumeService.java:309-324` 恰好用了结算后重读的 `source`，同一份逻辑两条路径形态不一致，反证首轮路径写错。

为什么测试没红：`InMemoryRunStore` 明确声明「返回同一个对象引用」（`backend/src/test/java/com/example/configmgr/ai/conformance/InMemoryRunStore.java:34-36`），`ToolOutcomeExitTest.java:119-125`、`ConfirmGateWiringTest.java:114/180/209/242` 的 Mockito `thenAnswer` 也都改同一实例。新断言（如 `ToolFrameSequenceConformanceTest.java:319-322`）因此只在「假存储」下成立。

修复：循环内结算后重读 `this.store.pending(runId, pending.getToolCallId())`（fallback 原对象）再 `modelData(settled, text)`；并加一条 `pending()` 返回副本的回归用例（或给 InMemory 增加 round-trip 模式）。

**C2. 历史通道被模型信封污染：刷新后工具卡「输出」显示裁剪过的信封 JSON**
`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:561-566`、`frontend/src/stores/ai.ts:1212`（序位兜底 `:1247` 同）、`frontend/src/components/AiPanel/AiPanel.vue:394-402`

`settle()` 把带信封的 `ToolResponseMessage` 写进快照/记忆（`SpToolCallingManager.java:577-588`），`/history` 原样透出 `toolResponses.responseData`（`MessageJsonCodec.java:113-121` 逐字存取），前端 `part.result = matched.responseData`，面板直接渲染 `part.result`。于是**每一轮完成并刷新后**，后端/确认/前端工具的输出都变成：

```
{"ok":true,"code":null,"outcome":"ok","retryable":false,"target":{...},"evidence":null,"message":"…（已被 ToolResultLimiter 裁剪）"}
```

同时因为 `modelData` 用的是 `toModelJson(limiter)`，历史里连全文都丢了（只剩模型侧裁剪版 + 截断标记），与帧/台账全文不一致。这正好命中文档声称的「历史渲染仍读原文本」：GF 的 `submittedValues` 走的是 `toolCallStates.resultText`（`ResilientChatService.java:741-744`）没破，但**通用工具卡的 result 路径破了**。

修复（二选一）：
- 后端 `toHistoryItem` 对 `responseData` 做信封解包，只向历史载荷暴露 `message`（记忆里的模型消息不动）；
- 或在 `toolCallState` 增补 `resultText`（原文全文），前端 `loadHistory` 优先 `state.resultText`、缺失再回落 `responseData`。
任一方案都需要一条「完成一轮 → 调 /history → part.result 为原文」的用例。

## Important

**I1. 回灌回执帧 `frontend_tool_result` 没带 outcome，且 `ok=false` 会被 `status=FRONTEND_RESULT` 反向覆盖成成功**
`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:635-643`、`frontend/src/utils/parts.ts:550-575`

`ConfirmGate.submitFrontendResult` 已按前端自报 outcome 传 `ok=false`（`ConfirmGate.java:583-588`），但帧只带 `ok/status/result`；前端 `frontendResultStatus('FRONTEND_RESULT')` → `succeeded` 且优先于 `ok`。单标签页里 `runFrontendTool` 先落本地终态所以看不到，但「回灌」作为设计点名的三条通道之一，帧面与新信封自相矛盾：同一帧 `ok=false`、卡面 succeeded，且 `outcome/code/retryable/target` 全缺。建议同 `tool_result` 一样补四字段，并让 `frontend_tool_result` 分支在 `outcome` 存在时优先。

**I2. 测试诚实性：新用例证明的是「测试替身语义」而非生产语义**
`InMemoryRunStore.java:34-36` + `ToolFrameSequenceConformanceTest.java:319-322` + `ToolOutcomeExitTest.java:119-125` + `ConfirmGateWiringTest.java:114/180/209/242`

断言本身是加强（见下「改写审计」），但 C1 恰好只在「`pending()` 返回新对象」时出现，现有替身无法暴露。建议至少一个用例让 `pending()` 返回 deep copy；否则这批「模型侧信封」的门禁是自证。

## Minor

- **M1. 未决条目在历史里被写成 `outcome:"unknown"`，前端 `unknown→failed` 会盖掉 `PENDING→pending`。** `ResilientChatService.java:722-723` 无条件写 outcome；`frontend/src/stores/ai.ts:1168` 优先 outcome。当前记忆只在 finish/cancel/error 收敛，cancel 走 snapshot→CANCELLED，所以基本不可达；但这是脆弱不变量，建议对 `PENDING/APPROVED` 不写 outcome，或前端在 status 非终态时不让 unknown 覆盖。
- **M2. `TOOL_ALREADY_CLAIMED` 被前端固定文案说成「越 scope」。** `SpToolCallingManager.java:411-420` 用 `BLOCKED`+该码；`frontend/src/utils/parts.ts:477-479` 对任何 blocked 都写「不在披露范围内，未执行」，与「他实例已认领」事实相反。建议按 `code` 选择 reason。
- **M3. `SseChatEmitter` 的 ALWAYS 副本前提不成立。** `SseChatEmitter.java:245-248` 注释称「Spring 全局 non_null」；但 `JacksonConfig.java:16-24` 自定义了 `@Primary ObjectMapper` 且未设 inclusion（默认 ALWAYS），`spring.jackson.*` 不会应用到自定义 bean（旁证：`ApiResponse` 自己加了 `@JsonInclude(NON_NULL)`）。当前改动是 no-op，无害；但 `SseChatEmitterFramesTest.java:107-115` 把手工 NON_NULL mapper 标成「生产装配」，锁的不是生产接线。若将来真改成 NON_NULL，`RunStore.appendEvent` 用同一 primary mapper 归档会丢 null 键，而实时帧保留，形成回放分叉。
- **M4. `toolResult(pending, ok, ...)` 的 `ok` 已成死参。** `SseChatEmitter.java:576-583` 完全由 `outcome.ok()` 推导，与设计基线「ok 原样」不一致；对 ledger 重放反而是修正（旧实现会 `ok=true` 重放失败结局）。建议删参或加「derived == passed」一致性断言，避免未来调用点传错也无声。
- **M5. `list_config_defs` 非法 level 仍无码。** `AiTools.java:94-96` 的 `ConfigLevel.valueOf` 现在会浮出为 `FAILED/TOOL_EXECUTION_FAILED`（方向正确），但没有 `ILLEGAL_ARGUMENT` 码。若目标是「所有原吞错路径都带码」，这里应包一层。
- **M6. `generative_form` 工具描述与新模型形态漂移。** `AiTools.java:510-512` 仍写「你会收到字段 key→值的 JSON」，实际收到信封、值在 `message` 里。模型可解析，但描述应同步。
- **M7. runId/快照缺失的回落路径行为变了。** `SpToolCallingManager.java:151-163` 直接委托官方 manager；`alwaysThrow=true` 后工具异常会穿出整轮（以前是失败文本 + 继续）。生产恒有 runId，属死路径，但值得注释/兜底。
- **M8. 续跑信封形态无新用例。** `ResumeService.java:309-324` 实现正确（用 `source` 重读），但没有任何测试锁住「续跑 role:tool 与首轮同形」。
- **M9. 未跟踪 `data/`（2026-10-12 的 xlsx）不应随本批提交。**

## 通过面（已核实）

- **七态映射**：后端 `ToolOutcome.wire()` 全小写，前端 `outcomeStatus` 逐态一致（ok→succeeded/failed→failed/rejected/blocked/timeout→expired/cancelled/unknown→failed）；`code/target` 可空、两端可选性一致；`suspended`（`asMap`）、`history`（`toolCallStates`）、回灌 POST 均有 outcome（回执帧见 I1）。
- **alwaysThrow 爆炸半径**：`DefaultToolExecutionExceptionProcessor(alwaysThrow=true)` 重抛 `ToolExecutionException`，`failureCode` 沿 cause 链取 `ConflictException/ResourceNotFoundException` 的码（反编译 1.1.8 实证：`process` 第 50-58 字节直接 `athrow`，消息取 cause message）。6 处目标工具 + `list_config_defs` 均从「成功谎报」变为正确失败；未发现把本应成功的路径打成 failed 的误伤。
- **裁剪顺序**：`ToolResultEnvelope.toModelJson` 先 `limiter.forModel(message)` 再整封程序列化，JSON 不会被截坏；`FrameSizeLimiter` 只作用于出帧，`overhead()` 会按含 target 的实际序列化重算预算；`ResumeService` 同形。
- **GF 原文**：`ConfirmGate` 的 `resultText`、`appendLedger` 的 `resultText`、帧 `result`（`frameText`）、`toolCallStates.submittedValues` 全是原文，未被信封替换；被污染的是 C2 里的历史展示 `part.result`。
- **前端回落为真**：旧帧无 outcome 走 `frame.ok` 三元且不产生 `outcome` 键；旧历史走 `historyToolCallStatus`。新用例①④⑤在旧实现下确实会红。
- **改写审计（抽 3 处）**：Conformance 前端回灌（`isEqualTo(payload)` → 信封 `message` 相等 + outcome + target）、ScopeGuard 截断（原 4 条断言全部保留在 `envelope.message` 上并加 outcome）、GF schema 拒绝（原文案断言保留，新增 outcome/code）——均为「同语义换解析方式」或增强，未发现趁机放松。
- **ResourceNotFoundException 加 code**：`GlobalExceptionHandler` 仍只用 message 返回 404，REST 面零变化。

## 实测命令

| 命令 | 结果 |
|---|---|
| `mvn -o -q -f backend/pom.xml -Dtest='ToolOutcome*,ToolResult*,ToolFrame*,AiTools*,GenerativeForm*,SseChatEmitter*' test` | exit 0；surefire：ToolResultEnvelopeTest 4、ToolOutcomeExitTest 2、SseChatEmitterFramesTest 13、ToolFrameSequenceConformanceTest 9、ConfirmGateWiringTest 5、ScopeGuard 3、AiToolsInvocationTest 7、AiToolsThrowPathTest 6、AiControllerFrontendResultTest 5、GenerativeFormGateTest 14（nested，顶层 txt 显示 0 为 JUnit5 报告格式所致，XML 14 个 testcase 全绿） |
| `cd frontend && yarn test` | 24 files / 473 tests passed（含新 spec 8 tests） |
| `node scripts/ci/check-docs.mjs` | 6/6 断言 PASS |
| `cd frontend && yarn typecheck`（附加） | 通过 |

**总评**：契约字段与映射层面做得扎实，`alwaysThrow` 方向正确、裁剪顺序正确、旧帧回落真实；但模型侧对两个等待通道的取数对象取错（C1）与历史展示被模型信封反向污染（C2）都属于「真实链路必现」的问题，加上 I1 的回灌帧漏字段，本批不宜按 READY 收口。