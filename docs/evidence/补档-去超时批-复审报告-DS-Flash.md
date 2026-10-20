# 补档：去超时批 — 复审报告（DS-Flash）

- **归档来源（已脱敏）**：`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1258/wire.jsonl` 末条 assistant 正文（逐字提取，仅脱敏本机路径与会话 id）
- **归档日期**：2026-10-20
- **角色**：独立复审（explore · 只读）；批次 = HITL 去超时 + 人机预算分离 + 会话 24h 滑动 + 卡面一致性（**收口修之前**的复审）
- **自证**：自身 wire 的 `profile.bind` 单行首部 = `{"type":"profile.bind","agentId":"agent-1258","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high"`
- **存档说明**：本件结论为 **CHANGES-REQUESTED**（P1 ×1 + P2 ×4 + P3），随后由施工 agent-1259/1260 收口；收口后复核结论见 `docs/evidence/补档-去超时批-复核确认-DS-Flash.md`。正文逐字保留当时判定，不回改。

---

## 结论：**CHANGES-REQUESTED**

后端语义与卡面主体是对的（测试全绿、demo 场景 1/2/3/4 逐卡对得上），但有 **1 条 P1 与"等待不死"直接冲突**，外加 1 条必跑门禁红。

### 自证（profile.bind 首部原样引用，agent-1258，`wire.jsonl` 单行）

```json
{"type":"profile.bind","agentId":"agent-1258","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, …
```

### 审查锚

| 项 | 值 |
|---|---|
| HEAD | `0a5d9cd` |
| `git diff \| sha256sum` | `4b3aad0899c76c736593d45fb572faf8c8b2c34b17556974e0a14da6ee99a1ec` |
| 新增 `RunStoreSlidingTtlTest.java` | `7c097b76…c5ca6` |
| 新增 `StreamWatchdogGateWaitBudgetTest.java` | `1a3cf843…cf53` |
| 新增 `docs/demo/chat-ux/card-consistency-fix.html` | `937518d7…2c5` |
| 被删（HEAD 内容 sha） | `RunStoreTtlAbsoluteCapTest.java` = `455c8484…dcf56`（未 stage） |

---

## P1

**1. SSE emitter 寿命 300s = 批次之后**新**的事实死线，"GF 不自动取消 / 确认门一直等"在传输层不成立**

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:192`、`:313` 都是 `new SseEmitter(budgetMillis())`，`:845` 的 `budgetMillis()` = `total-budget`（300s）；`SseChatEmitter.java:340` 在 `onTimeout` 里 `closeSubscription`。确认门/GF 等待已无上限，但这条响应 300s 一定结束。
- 客户端侧：`frontend/src/api/sse.ts:30` `DEFAULT_MAX_RECONNECTS = 2`；断流后 `stores/ai.ts:1471-1474` 在非终态收尾时执行 `activeForm = null`。粗算：首流 300s + 重挂 3×300s ≈ **20 分钟**即判断流；GF 表单被收起（卡片还在、表单没了，只能刷新靠 `runs/current` 重建）。确认卡按钮还能点，但顶部挂"连接中断"。
- 这正是本批要消灭的那类"自动收摊"，只是搬到了 transport 层。建议：可挂起的 AI 流改 `new SseEmitter(-1)`（或 `session-ttl`），并把 `onClose` 非终态分支改为"挂起中不清 activeForm"（或抬高 reconnect 上限）。至少要有一条"等待 20 分钟仍可提交表单"的实测。

## P2

**2. `check-docs` 红（必跑命令）**
`node scripts/ci/check-docs.mjs` → `[FAIL] ⑤ 命名纪律禁则`：`backend/src/test/.../RunStoreTtlAbsoluteCapTest.java` 已删但未 stage，脚本按 `git ls-files` 读不到（`scripts/ci/check-docs.mjs:455`）。一处 `git add -A` 即绿；但当前交付态 CI 门禁是红的。

**3. 会话索引 10m 短于会话 24h：崩溃后"卡在、人找不到"**
`RunStore.java:281-283` 的 `sessionCurrentTtl()` 取 `app.ai.session.lock.ttl`（`application.yml:153` = 10m），只靠 2s 心跳续期；进程一死心跳即停，`/runs/current` 在 `AiController.java:441-446` 返回 `found:false`，而 `ai:pending`/`ai:run` 还有 24h。`ResumeService` 对 PENDING 条目只回 `PENDING_UNRESOLVED`，不补索引。删掉 240s/120s 后这个洞从"几分钟"放大到"24h 内都可能是孤儿"。建议启动扫描把 `SUSPENDED && unresolvedExternal` 的轮次 `markSessionCurrent` 补回（或索引 TTL 与会话对齐）。

**4. GF 只读卡（已提交/已取消）与 demo 场景五留白不一致**
`AiPanel.vue:331`（`ro-body` 只有 `pb-[11px]`）+ `:353-361`（只读 GF 无 `mx-[11px] mt-[9px]` 包裹）+ `FormRenderer.vue:25`（`readonly || flat ⇒ ''`）⇒ 字段贴卡片左右边；demo `card-consistency-fix.html:179` 是 `mx-[11px] mt-[9px]`。删标题行后该卡彻底 0 内缩，比改前更偏离 demo。建议只读 GF 卡体加一层与门卡只读态同构的 `mx-[11px] mt-[9px]`。

**5. GF 请求帧"带空值"与自身契约矛盾**
`SseChatEmitter.java:655-657` 对无上限仍 `put("timeoutSeconds", null)` / `put("expiresAt", null)`，javadoc（`:646-648`）却写"null 时帧里不带"；`SseChatEmitterFramesTest` 还把 null 钉死；而 `types/sse.ts:286` 声明 `timeoutSeconds: number`（不可空）。建议按键不写（与 confirm_request 口径统一），或改类型+改注释，二选一。

## P3（不阻塞）

- 契约/注释漂移（后端已删、前端仍声明）：`types/ai.ts:107` `AiHealth.confirmTimeoutSeconds`（`AiController.java:712-716` 已不再下发，两个测试仍在 mock）；`types/sse.ts:248` `SseConfirmRequestFrame.timeoutSeconds` 必填；`SpreadGrid.vue:97`、`utils/frontend-tools.ts:209`、`api/sse.ts:33-35` 仍写 confirm-timeout 240s；`AiController.java:461` 仍写「等待人工确认」静态兜底；`types/ai.ts:456` 仍写"挂起拖到超时"。
- `stores/ai.ts:1817-1818` `clearGateSubmission(toolCallId)` 连写两遍（无害，死语句）。
- `utils/format.ts:95` `TASK_STATUS_LABELS` 随预检摘要下线后已无消费方（死导出）。
- `docs/03-技术方案文档-v2.1.md:270/551/642` 仍以"冻结原文"记 240s，该文件惯例是"只追加"，本批未补 2026-10-20 退役行注。
- `ConfirmGate.java:365` 注释写"裁决第 6 条（中断不写超时）"，与用户裁决第 6 条（预检机制保留）编号不对应。

---

## 绿项（逐条必查结论）

1. **等待不死（后端）**：确认门 `suspendWaitSeconds → null` 后无 deadline 分支；2s 心跳照常（`pulse` 锚点 + `renewSessionCurrent` + 新增 `renewSuspensionTtl`）；挂起期 `beacon.isActive` 为真故不判静默；取消路径（循环首判 + `onCancel` 加速）与 F8 `arm/disarm`、`maybeTriggerResume` 均与 `expiresAt` 无耦合。唯一缺口即 P1（客户端）。
2. **预算分离**：`gateWaitMs` 含进行中窗口；watchdog 仅扣本尝试新增（重试前置 `sideEffect` 使跨尝试不成立）；`awaitSettled` 改短切片复查；不碰 `startedCount`（有专测）。
3. **TTL 滑动**：`ttlFor` 统一整额 `session-ttl`；`save/appendEvent/touchActivity/putPending/claim/ledger/seq` 全走它；绝对封顶、`EXPIRED_RENEWAL`、`runCreatedAtMs` 与对应测试全部退役。遗漏键见 P2-3（索引）与 claim/ledger（等待期不存在，理由成立）。
4. **GF 豁免**：`suspendWaitSeconds` 按 `kind+name` 区分，`generative_form` 与确认门均 null；前端 GF 已无线程内定时器；即时类前端工具 120s 与 `expiresAt` 成对下发仍在。
5. **前端拆除**：`confirmClock / frameDeadlineLocal / heartbeatDeadlineLocal / isFreshFrame / isStaleFrame / expiredLocally / gateExpirySynced / formExpiryTimer / formatCountdown / tickController / confirmRingOffset` 全无残留引用；历史 TIMEOUT 仍只读显示「! 已超时」；`preflight` 类型保留、摘要区整块不渲染；按钮样式/操作行/参数行零改动（仅 `:disabled` 去掉 `confirmExpired`，属必要）；demo 场景 1/2/3/4 逐项一致，场景 5 见 P2-4。
6. **协议兼容**：`confirm_request` 不再带 `timeoutSeconds/expiresAt`（conformance 有 `doesNotContainKey` 锁）；旧帧/回放/快照只存不用，不炸。

## 实测

```
mvn -o -Dtest='ConfirmGate*,StreamWatchdog*,RunStore*,ToolFrame*,Resume*' test
  → Tests run: 58, Failures: 0, Errors: 0   BUILD SUCCESS
mvn -o -Dtest='AiPropertiesT35DefaultsTest,SseChatEmitter*Test,AiControllerHealthSseDeliveryTest,SseFrameSequenceConformanceTest'
  → Tests run: 52, Failures: 0, Errors: 0   BUILD SUCCESS
cd frontend && yarn test   → 24 files / 488 tests passed
yarn typecheck             → vue-tsc --noEmit, Done（0 错）
node scripts/ci/check-docs.mjs → ① ② ③ ④ ⑥ PASS，⑤ FAIL（见 P2-2）
```

只做了读操作与题目指定的构建/测试；未改任何文件、未 commit、未起停服务。
