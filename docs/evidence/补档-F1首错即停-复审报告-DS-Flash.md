> 入库注记（2026-10-20）：出处 = `agents/agent-1201/wire.jsonl` 末条 assistant 正文（会话 `<SESSION>`，6636 字符），作者 = agent-1201（DeepSeek/deepseek-flash，profile=explore，本批复审方）；按 DC-08 脱敏 1 处（本机仓库绝对路径 → `<REPO_ROOT>`），本件除本注记行与本标题行外与源件逐字一致（零改写）。
# 复审报告 · F1 批内首错即停（复审结论 = **CHANGES-REQUESTED**，0 Critical / 3 Important / 5 Minor）
## 自证行（`profile.bind` 首部，原样）

```
{"type":"profile.bind","agentId":"agent-1201","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.\n\nYour primary goal is to help users with software engineering tasks.\n\nYou are now running as a subagent. All the `user` messages are sent by the main agent. ...
```

（该 wire 中 `profile.bind` 仅 1 条；后续因 `systemPrompt` 过长省略。）

## 审查锚

- 仓库：`<REPO_ROOT>`，HEAD `33829c0c73f0389db613de50491b2292303b985a`
- `git diff | sha256sum` = `ad747a2a3a162788354bc19cffa8572235ecb73ed49b62788deabc819c0f86aa`
- 未跟踪新文件（`data/` 已排除）：
  - `backend/.../tool/ToolCategory.java` = `ecd0a69c9eaf538631153c0bd3089244c6ee5b8092e707bf3f41e7ede419cc96`
  - `backend/.../gate/ResumeSkipCascadeTest.java` = `e259345e493f09f9c682b46acd652a05a432741d30fc817618742a6dae4540d1`
  - `backend/.../gate/SpToolCallingManagerBatchStopTest.java` = `6461ca037b21466bc634333242c1d7d7973defad382a9289e1627ee283ec53d2`
- 改动面 18 文件 +390/−38，与卡面预期一致；无越界文件。

## 结论：**CHANGES-REQUESTED**

功能面本身是对的：判据、三通道、续跑同策略、前端映射都成立，门禁全绿。请求改动仅 3 项（两项测试/契约、一项卡面观感），无 Critical、无数据损坏风险。

## Critical

无。

## Important

**I-1 停走路径发出「无 `tool_start` 的 `tool_result`」，与既有帧序契约正面冲突（但现有测试测不到）**
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:209-212`：`haltedBy != null` 时直接 `skipAsUpstreamFailed(...)` 后 `continue`，**跳过了 `out.toolStart(pending)`**；`skipAsUpstreamFailed`（`:317-334`）只发 `tool_result`。
- `backend/src/test/java/com/example/configmgr/ai/conformance/FrameContract.java:229-232` 明确把「结局帧没有 `tool_start`」判为违规（`assertConformant` 的一部分）。
- 新的 `SpToolCallingManagerBatchStopTest` / `ResumeSkipCascadeTest` 用 `mock(SseChatEmitter)`，`ToolFrameSequenceConformanceTest` 也没有停走场景 ⇒ 该契约在今天已不成立，却没有任何红灯。
- 建议（二选一）：①（推荐）在 `toolPairingContract` 为 `outcome=skipped/status=SKIPPED` 的结局帧显式豁免，并在 `ToolFrameSequenceConformanceTest` 用真实 emitter 跑一条「首错即停」流；②在跳过项前补发 `tool_start`（语义不准确，不推荐）。前端本身能兜住（`upsertToolPart` 的 `createIfMissing`），所以这是契约/覆盖缺口，不是运行时故障。

**I-2 生产侧 `@ToolCategory` 接线零测试覆盖，且缺省是 fail-open**
- `ToolRegistry.java:61` 只在方法上扫注解，`:158-161` 未声明一律返回 `null`；`SpToolCallingManager.haltsBatch`（`:299-311`）对 null 返回 false。注解要求位于 `AiTools.java`（我逐个核对：`create_task`=W；`start_export/start_precheck/start_import`=J；`start_publish`=P；`open_export_file_editor/navigate_to/select_definitions/set_condition/confirm_step`=U；`download_export_files`=F；`generative_form`=H；6 个读工具=R，与定稿一致）。
- 但所有类别测试都在 stub `categoryOf`（`SpToolCallingManagerBatchStopTest.java:274-283`、`ResumeSkipCascadeTest.java:181-184`），没有一条用真实 `ToolRegistry` 断言这 5 个工具名。将来某次改注解/加工具忘了标，停走策略会静默失效且测试全绿。
- 建议：仿 `AiToolsDisclosureEndpointTest` 增加一条注册表级断言（`categoryOf` 五个名字），并补一条「category=null ⇒ 继续执行」的用例，把 fail-open 变成有锁的行为。

**I-3 skipped 卡面：同一文案显示两处，且「原因」行仍是红色错误样式**
- `frontend/src/utils/parts.ts:476-488` 同时写 `result: frame.result` 与 `reason: frame.message ?? frame.result`；后端 `skipAsUpstreamFailed` 恰恰把 `skippedMessage` 放进 `frame.result`，于是两值相同。
- `frontend/src/components/AiPanel/AiPanel.vue:394-408` 渲染「输出」= `result`，`:416-419` 渲染 `原因：…` 红底 `!` 错误行。设计口径是「已跳过」中性灰；展开后用户会看到同一句话两遍，且其中一遍是错误色。
- 对卡面第 5 项的直接回答：**当前 reason 显示是正确的**（取到 result 的人读文案）；但「未来后端补 `message`」并不能消除双显 —— 只要 `result` 仍带同一句，`reason=message` 与「输出=result」还是两处同文。建议：skipped 时 `result` 留空（只写 reason），或前端对 `status==='skipped'` 不渲错误原因行；后端补 `message` 时把 `result` 置空。
- 另：`frontend/tests/tool-outcome-envelope.spec.ts:397-428` 两条用例都先喂 `tool_start` 再喂 skipped `tool_result`，与真实后端序列（无 start）不符，掩盖了「仅凭 tool_result 建段」这条路径。

## Minor

- **M-1** 注释仍写「七态」，与本批新增的第八态不一致：`frontend/src/utils/parts.ts:248`（`localTerminal` 已含 skipped，注释却写「同 7 态」）、`:426`、`:430`；`types/sse.ts:71/181/260`；`types/ai.ts:80`；`stores/ai.ts:1166/2163/3251`。建议统一为八态或「七态 + skipped」。
- **M-2** `frameStatusToPendingStatus`（`stores/ai.ts:737-768`）没有 `SKIPPED` 分支，而 `restoreSuspendedSnapshot`（`:1371-1380`）会用它翻译 `pendingEntries` 里的非 PENDING 条目。今天 `SKIPPED→null` 是 no-op 且该路径只在 `awaitingExternal` 时进入，无害；建议补 case 或写明「SKIPPED 不经本表」。
- **M-3** `ResumeSkipCascadeTest` 只覆盖了 APPROVED（E2 窗口）批量续跑；未覆盖 `PENDING+BACKEND` 崩溃窗口，也未测「再次续跑时 SKIPPED 不被重放/不重复发帧」。代码侧我核过是安全的（`resolvePending` 的 `shouldExecute` 不含 SKIPPED；`isUnsettled` 排除 SKIPPED），只是缺锁。
- **M-4** 对账措辞只加在 `executeOnce` 的 catch（`SpToolCallingManager.java:525`）；前端通道执行失败（`awaitFrontend`）不带该措辞。因停走只发生在后端 W/J/P，可接受；若口径是「凡是执行后失败一律加」，需补前端通道。
- **M-5** 「续跑重放与首轮字节同形」是结构性断言而非逐字节：两路都走同一 `ToolResultEnvelope.of` + 同一 `ToolResultLimiter` + 同一 `skippedMessage`，实际字节同形成立；建议加一条跨两路等值断言把结论钉死。

## 必查项实测结论

1. **判据真伪**：`haltsBatch` 同时看类别与 `outcome==FAILED && !retryable(code)`；retryable 仅 `PRECHECK_NOT_PASSED/PRECHECK_STALE`。`retryableFailureDoesNotHalt` 用 `PRECHECK_NOT_PASSED` 证明不停（`SpToolCallingManagerBatchStopTest.java:190-211`）。类别未声明 fail-open 是显式默认（`ToolRegistry.java:153-157` 注释），但缺回归锁（I-2）。
2. **SKIPPED 完整性**：每个未执行调用恰一条 ToolResponse，id 严格对齐（`everyPlannedCallGetsExactlyOneResponseWhenBatchHalts` 断言 `c-1,c-2,c-3`）；写库 + `toolResult` 帧 + 模型信封三通道齐；`executed=false`、不认领、不记台账；`haltsBatch` 不会把 SKIPPED 当 FAILED（级联只触发一次）；`resolvePending`/`isUnsettled` 都不会再执行 SKIPPED。模型侧缺一条会被 OpenAI 兼容端点 400（本实现不存在该缺口）。
3. **续跑同形**：`ResumeSkipCascadeTest` 确实覆盖「续跑中遇 W/J/P 失败 ⇒ 级联」，断言 3 条响应与 `rebuiltTools` 标签；重放与首轮共用 `skipAsUpstreamFailed` 及同一信封构造，字节同形成立（仅未逐字节锁，见 M-5）。
4. **协议面**：`out.suspended` 只在执行前调用（`SpToolCallingManager.java:201-202`），`pendings` 由 `newPendings` 新建、此刻全为 PENDING ⇒ **后端不会把 SKIPPED 放进 suspended 的 toolCalls**；前端 suspended 分支里的 skipped 处理目前是防御性死代码，但 `hasDecidedPendingResidue` 已含 skipped，重投帧不会复活挂起卡。历史/回放：`toolCallState` 写 `status=SKIPPED/outcome=skipped/code=UPSTREAM_FAILED`，`/history` 解包信封把「未执行：上游…」作为 `responseData` 回显，刷新后卡面仍为「已跳过」；但刷新后不再有 reason 行（只有输出）。
5. **前端偏离**：见 I-3，另 `reason` 行红色错误样式与「中性灰」设计口径不符。
6. **既有不破**：`ToolFrameSequenceConformanceTest` 10/10、`ResumeEnvelopeShapeTest` 1/1、`AiControllerHistoryPayloadTest` 10/10、`ConvergeMemoryToolCallStatesTest` 15/15 全绿；P0-1 七态映射原值未动，C1（后置重派生）/C2（重复 id tie-break）未被触碰；D9 一致性锁已同步为 8 态。

## 门禁实测

| 门禁 | 结果 |
|---|---|
| `mvn -o -q -f backend/pom.xml -Dtest='*BatchStop*,*SkipCascade*,ToolOutcome*,ToolFrame*,ConvergeMemory*' test` | 通过：BatchStop 10、SkipCascade 1、ToolOutcomeExit 2、ToolFrameSequence 10、ConvergeMemory 15 |
| 追加 `AiControllerHistoryPayloadTest,ResumeEnvelopeShapeTest,SpToolCallingManagerGateArmTest,ScopeGuardTest` | 退出码 0（History 10、EnvelopeShape 1） |
| `cd frontend && yarn test` | **24 文件 / 479 用例全绿** |
| `yarn typecheck`（额外跑） | `vue-tsc --noEmit` 通过 |
| `node scripts/ci/check-docs.mjs` | 6/6 通过 |

注：maven 输出尾部有 `ResilientChatService` 的预期异常/空回复日志栈，属测试内日志，非失败；以 surefire 报告与退出码为准。