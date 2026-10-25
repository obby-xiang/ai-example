> 来源=<仓外证据>/cluster-probe-rereview2.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 集群探针 fix2 · 复审（I1 / N1 / N2 / N3）

- 复审对象：`cluster-probe-fix2-report.md`、`cluster-probe-fix2-incremental.diff`
- 交付探针：`_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`
  （5345 行，md5 `730b0093c00a45f6f5943a55390a9aea`；主仓工作树同 md5，逐字节一致）
- 增量补丁：以 v2（md5 `92d6ae...`）为基线 `patch -p1` 成功；应用后与交付文件逐字节一致
- C1 按控制器裁决**不重开**：候选已含 `ensureToolStart`、两处续跑补发、latch/frontend-result；本复审只查 I1/N1/N2/N3 与“早杀/更强检查是否保留”
- 只读复审；未起真实拓扑/CI/Docker；27/27 与 53/53 为探针自证，不是产品行为证明

---

## 0. 判定表

| 项 | 判定 | 说明 |
|---|---|---|
| **I1** 载荷/帧型 false-pass | **ADDRESSED** | C 预决策回放改为 `serverFields` 全字段 + `stableStringify`，并就地 `assertParkedPayloads`；B 差量用 `assertDeltaFrames`（白名单/前缀帧/decision/result/delta/tool_start 策略）；C 决策后连接直接 `assertFrameLedger`。 |
| **N1** 状态/ledger/jobs/history 证据前置 | **ADDRESSED** | `still`、kill 后 `current`、`run-detail`、ledger、jobs、history 均先写（`evidenceFirst` 或增量 `takeover.json`）再断言。 |
| **N2** done.seq 口径 | **ADDRESSED** | 断言文案改为“本次全量回放的末号（replay tip，不是服务端 archiveMaxSeq）”，返回/证据带 `lastSeqScope='full-replay'`；`done.seq === replay tip`。 |
| **N3** 早杀观测不推断来源 | **ADDRESSED** | `killToolStartObservation` 只返回 `toolStartSeenBeforeKill`/`toolStartNotObservedBeforeKill`；`park-transition.json`/`takeover.json`/caseLog 不再出现 `toolStartBackfilled` 或“false ⇒ 由接管方补发”。 |
| 早杀覆盖与既有强检查 | **保留** | `waitForPark:false` + 卡到即 SIGKILL；`assertFrameLedger`、`assertPreDecisionReplay`、`assertDeltaFrames`、park 载荷、I3 噪声允许均保留/加强。 |
| 场景 ID / A→B→C / 无粘滞 | **保留** | 16 个 ID 与顺序未变；A 挂起、B/C 重挂、C 决策、A/C 全量、B 接管/C 全量未变；`proxy-no-sticky`/`same-tcp` 未改。 |
| **新 Critical/Important** | **无** | 仅见一处文档级残留（N2 的函数注释仍写“归档末号”，见 §4），不影响断言或证据。 |

---

## 1. I1 —— ADDRESSED

### 1.1 C 预决策回放：全字段，不再白名单

- `serverFields` 只剔除客户端解析附加的 `_recvAtMs`/`_event`，其余全字段保留：
  `_verify.../scripts/ci/cluster3-probe.mjs:532-540`
- `stableStringify` 递归按 key 排序，避免序列化 key 顺序造成假红：`:542-550`
- `assertPreDecisionReplay` 逐帧 `stableStringify(serverFields)` 比对，并就地调用 `assertParkedPayloads`：
  `:628-640`
- 因此以下改动都会红：`suspended.toolCalls` 清空、`start.runId` 替换、`context` 载荷篡改、`atMs`/`preflight` 改写。
- 自证：`selfTestPreDecisionReplay`（`:4950-5000`）含 `toolCalls` 清空、`start.runId` 替换、`context` 正例 + 载荷篡改正例/反例。

### 1.2 parked 载荷增强

- `assertParkedPayloads`（`:554-585`）新增：
  - `suspended.runId` 非空；
  - `suspended.toolCalls[].kind === 'CONFIRM'`；
  - `suspended.toolCalls[].arguments` 与 `confirm_request.args` 一致；
  - `confirm_request.irreversible` 类型；
  - `tool_start.name/kind/args` 与确认卡一致。
- 产品 `PendingToolCall#asMap` 确实有 `arguments`/`kind`（`backend/.../PendingToolCall.java:280-305`），`suspended` 帧确实有 `runId`（`SseChatEmitter.java:1085-1093`），无新增 false-fail。

### 1.3 B/C 决策后连接与 B 差量

- B 决策后差量：`:2810-2821` 调 `assertDeltaFrames(..., allowToolStart:false)`。
- C 决策后连接（从 0 全量）：`:2832-2837` 直接 `assertFrameLedger(streamC.frames)`，不再只验 seq。
- `assertDeltaFrames`（`:658-700`）覆盖：
  - 非心跳 seq 合法性、16 型白名单、无 `error`；
  - 不得重发 `start/suspended/confirm_request` 前缀帧；
  - `done` 恰一次且末帧；`confirm_decision`/`tool_result` 各恰一次；
  - `tool_start`：非早杀 0；早杀 ≤1，且 name/args/先于 `tool_result`；
  - `assertConfirmedToolPayloads` 校验 `tool_result.ok/outcome/name/result`、`confirm_decision.decision/approved/name`、至少一帧非空 delta。
- 自证 `selfTestDeltaConnectionFrames`（`:4783-4855`）覆盖缺 result、`ok=false`、空 result、reject、空 delta、未知帧型、done 后业务帧、前缀帧泄漏、非早杀 tool_start 共 8 个反例。

---

## 2. N1 —— ADDRESSED

- `suspend-cross-instance`：
  - `still` 用 `evidenceFirst` 先写 `runs-current-b.json` 再 5 项断言：`:2775-2789`
  - `run-detail` 先用 `evidenceFirst` 写 `detailEvidence`，再断 DONE/台账/条目/executedBy/outcome/resultText：`:2857-2883`
  - `jobs` 先写 `run-detail.json` 再断数量：`:2885-2888`
  - `history` 先写 `{b,c}` 再断相等：`:2889-2893`
- `suspend-kill-a`：
  - kill 后 `runs-current-b.json` + `takeover.json` 先写，再断 found/runId/status/awaiting/pending：`:3433-3445`
  - `run-detail.json` + `takeover.json`（snapshot/ledger/lastSeqScope）先写，再断 DONE/台账/executedBy/outcome：`:3510-3525`
  - `jobs` 先写 `takeover.json` 再断数量：`:3527-3530`
  - `history` 先写 `takeover.json` 再断 `>=2`：`:3531-3534`
- 自证 `selfTestStateEvidenceOrder`（`:4859-4899`）按 current→detail→jobs→history 的顺序断言 `write → assert`，并让每个断言必抛。

---

## 3. N2 —— ADDRESSED（功能/证据层）

- `assertFrameLedger` 的文案：
  `done.seq 必须等于本次全量回放的末号（replay tip，不是服务端 archiveMaxSeq）`
  `:721-760`
- 返回值新增 `lastSeqScope: FULL_REPLAY_TIP_SCOPE`（`'full-replay'`）：`:719`、`:763`
- 证据带口径：`run-detail.json.lastSeqScope`（`:2866`）、`takeover.json.frameLedgerTipScope/lastSeqScope`（`:3501`、`:3517`）
- `summary.md` 也写明“full-replay，不是 archiveMaxSeq”：`:4234-4235`
- 自证 `selfTestLedgerTipScope`（`:4901-4914`）：`scope==='full-replay'`、`lastSeq===done.seq`、缺 done 必红。
- 原 N2 的“回放若在 done 后截断仍无法发现”仍作为**已登记端点面残余**（报告 §7.1），不属于断言写法 false-pass。

---

## 4. N3 —— ADDRESSED

- `killToolStartObservation(frames, toolCallId)` 只返回两个观测字段：
  `toolStartSeenBeforeKill` / `toolStartNotObservedBeforeKill`
  `:2177-2180`
- `park-transition.json` 用 `...observation`（`:3400-3408`）；`takeover.json` 也用 `...observation`（`:3423-3430`、`:3499-3503`）。
- caseLog 改为“kill 前是否已见 tool_start（观测值，不推断来源）”：`:3404`、`:3541`。
- 全文件不再出现 `toolStartBackfilled`；自证 `selfTestKillObservation`（`:4916-4926`）断言返回对象不含该键。
- 报告 line 50 的“走的是哪条路”旧表述已由本报告 §1.2/§4 取代；fix1 报告顶部已补基线更正块。

---

## 5. 早杀与更强检查保留

- `createSuspendedRun(waitForPark:false)` 仍“卡到即返回”：`:2083-2095`；`caseSuspendKillA` 仍立刻 SIGKILL：`:3389-3420`。
- `assertFrameLedger` 的 seq/帧型/载荷/末号检查仍在：`:721-770`。
- `assertPreDecisionReplay` 全字段比对仍在：`:628-640`。
- `assertDeltaFrames` 的差量前缀/帧型/载荷仍在：`:658-700`。
- I3 的 pre-park 噪声允许仍在：`PRE_PARK_NOISE_TYPES` `:461-463`、`assertCardReachedFrameSet` `:477-504`、`assertParkedFrameSet` `:506-513`。
- 16 个场景 ID/顺序未变；A→B→C 路由未变；无粘滞用例未改。

---

## 6. 新 Critical / Important

**无。**

只有一条**文档级残留**（不计为 Critical/Important，也不阻断合并）：

- `assertFrameLedger` 的函数注释仍写“`done.seq === 归档末号`”：`:694`，与新断言文案/`lastSeqScope` 不一致。建议顺手改成“replay tip”，但不影响行为、证据口径或 false-pass 能力。

---

## 7. 验证（本机只读复跑）

```text
node --check scripts/ci/cluster3-probe.mjs          → OK
node scripts/ci/cluster3-probe.mjs --self-check     → PASS 27 / FAIL 0
bash scripts/ci/cluster3-run.sh --self-check        → PASS 53 / FAIL 0
```

- `cluster-probe-fix2-incremental.diff` 以 v2 为基线 `patch -p1` 成功；应用后 md5 `730b0093...`，与交付文件逐字节一致。
- 未执行真实三实例/CI/Docker/HAProxy/Redis；27/27 仅为自证。

---

## 8. 合并判定

**MERGE（探针侧）**。

- I1 / N1 / N2 / N3 均 **ADDRESSED**。
- 早杀覆盖、`assertFrameLedger`/`assertPreDecisionReplay`/`assertDeltaFrames`/park 载荷、I3 噪声允许均保留。
- 场景 ID、A→B→C、无粘滞未变。
- 无新 Critical/Important；唯一残留是 N2 的函数注释（文档级，不阻断）。
- C1 按控制器裁决不重开；候选产品补丁前置已记录在探针文件头。
- 既往 I4/I5/M1-M9 与真实拓扑未验证项仍按原样开放，本批未扩张。
