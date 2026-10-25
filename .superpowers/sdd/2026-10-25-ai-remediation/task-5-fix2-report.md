# Task 5 · fix2 报告（M-a / M-b / M-d 收口）

**结论：DONE** —— 控制者重评为 Important 的 M-a、M-b 已闭合，M-d 经确定性复现后按最小口径修复并补齐反向用例。未扩 M-c 与其它 Minor，未改主仓源码、未提交、未推送，未跑全量/构建/Maven。

- 工作副本：`<隔离副本>/_verify-20261025-chat-state/frontend`
  - **本次改动的两个文件**：内容 = main `2eb2539` + `task-5-fix1-cumulative.patch` + 本次修复（逐字节核对过）
  - 该副本还携带**其它任务的在途未提交改动**（`FormRenderer.vue` / `locales/*` / `types/api.ts` / `types/form-schema.ts` 等，
    与 Task 5 无关，来自 fix1 之前就已存在的施工现场）。所以本报告只把上面两个文件视为等价基线，
    其余文件不作等价性声明。
- 增量补丁：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-5-fix2.patch`（25 KB / 498 行，`md5 e339575289d73009d030614fdbc7b00f`，**作用于「main + fix1 累计补丁」之上**）
- 改动文件（2 个，均在 Task 5 范围）：`frontend/src/stores/ai.ts`（+161 / −9）、`frontend/tests/ai-multi-card-mixed-resume.spec.ts`（+216）
- 未触碰：`ai-dom-tools.ts` / `frontend-tools.ts` / `ui-receipt.ts` / `AiPanel.vue` / `ai-region-batch-b.spec.ts`（Task 4 共享的隐藏步骤语义原样保留，逐字节未变）
- 证据日志：`<隔离副本>/_verify-20261025-chat-state/fix2-red.log`、`fix2-green-5spec.log`、`fix2-green-adjacent.log`

---

## 0 应用顺序（必须）

```bash
# 1) 先落 Task 5 全量（若尚未落）：
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-fix1-cumulative.patch
# 2) 再落本次增量：
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-fix2.patch
```

已核对：`task-5-fix2.patch` **不能**单独作用于当前 main（`syncGateFromAuthority` / `confirm` 的上下文来自 fix1）——
`git apply --check` 报 `error: patch failed: frontend/src/stores/ai.ts:3023`，这是"增量补丁"的预期形态，不是缺陷。

---

## 1 M-b｜前端工具 409 DUPLICATE 后丢卡 + 锁死（用户阻断）

### 机制（实读调用链）

`submitGenerativeForm` → `submitFrontendToolResult` → `catch` 409 分支（旧：`ai.ts:3368-3380`）只做
`applyFrontendToolTerminal` + `setNotice` 就 `return`：

- 段收口了（`g1 = succeeded / requiresAction=false`），**但** `restoredPendingQueue` 里那张（`g2`）永不补出；
- `suspended` 也没按真实待决重算 ⇒ 一直是 `true`；
- `pendingToolCall` 变 `null`（段已收口）、`activeForm.status` 由 `submitGenerativeForm` 落成 `submitted`（无可点按钮）
  ⇒ **输入区禁用 + 屏上无可点卡**，只能刷新。

### RED（正式用例，非探针）

`tests/ai-multi-card-mixed-resume.spec.ts` 新增两条（真实 store、真实恢复队列、真实段断言）：

```
× 恢复队列里还有下一张 ⇒ 补出且仍可提交（409 当次不能把本轮锁在无卡可点）
  AssertionError: expected 'g1' to be 'g2'
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:364:42        // expect(store.activeForm?.toolCallId).toBe('g2')

× 没有下一张 ⇒ 按真实待决解除挂起（不留「等待确认」死锁），且不盲连
  AssertionError: expected true to be false
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:387:29        // expect(store.suspended).toBe(false)
```

### 修复（ai.ts，409 分支内 12 行）

```ts
          if (mapped) { this.applyFrontendToolTerminal(toolCallId, mapped) }
+         // 409 = 这张卡已有裁决，本页签不会再收到它的结局帧
+         this.revealNextRestoredPendingCard(targetRunId)
+         this.clearSuspendedIfNoOtherConfirms(toolCallId)
          setNotice(...)
          return { ok: false, duplicate: true }
```

- 补卡用 `targetRunId`（= 表单创建时捕获的 runId，缺省回退 `activeRunId`），与成功路径同源；
- **不重连**：`reveal` 只补卡；`clearSuspendedIfNoOtherConfirms` 只按"段 + 队列"的真实待决决定挂起，
  本轮仍挂起时由那张新卡的决策走既有续流（人等人期间没有连接，park 非阻塞语义不变）；
- 顺序：先 `reveal`（以 `suspended` 为闸）再 `clear`，与 `confirm`/`applyGateReceipt` 一致。

### GREEN

```
✓ 恢复队列里还有下一张 ⇒ 补出且仍可提交
  段：g1=succeeded/requiresAction=false；队列：g2 补出（activeForm=g2, status=filling）、队列清空；
  可提交性：紧接着再 submitGenerativeForm({k:'v2'}) ⇒ frontendToolResult 被调 2 次、g2 落 submitted
✓ 没有下一张 ⇒ suspended=false、pendingToolCall=null、reattachRun 0 次（不盲连）
```

---

## 2 M-a｜409 校正补出"权威已决"的卡（直通 409/锁死链）

### 机制

`syncGateFromAuthority` 手里就是**同一份** `runs/current.pendingEntries`（`ai.ts:3052`），
但只取目标条目的 `entryStatus` 交给 `applyGateReceipt`；`revealNextRestoredPendingCard` 随后只看
"本地有没有已决段"，于是队列里那张**权威已 APPROVED/EXECUTED、本地从未建段**的卡被补成
`pending + requiresAction=true` 的**可点卡** —— 用户点了必吃 409，正好落进 M-b 那条链。

### RED

```
× 队列项权威已是 APPROVED（已裁决、执行中）⇒ 按 running 展现且不可点，不补成可点卡
  AssertionError: expected 'pending' to be 'running'
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:312:24
× 队列项权威已 EXECUTED（真终态）⇒ 出队且不重建（不产生任何段）
  AssertionError: expected { kind: 'tool', seg: 2, …(6) } to be null
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:338:42
```

### 修法（读真实调用链后的判定：**不是简单丢弃**）

`applyGateReceipt(toolCallId, receipt, authorities?)` 新增可选的**同一份** `pendingEntries`，
在 `clearGateSubmission` 之后、`reveal`/`clearSuspended` 之前调用新 action
`reconcileRestoredQueueWithAuthority(authorities)`，按权威条目的真实状态分流：

| 权威 `entryStatus` | 处置 | 理由 |
|---|---|---|
| `PENDING` / 快照未列该条目 / 无法识别的值 | **原样保留**，交由既有 `reveal` | 信息不足不猜；语义不变 |
| `APPROVED`（已裁决、**尚未执行**） | 落 `running` + `requiresAction=false` + `decision='approved'` 的**只读卡**（`materializeRestoredDecidedCard`），出队 | `running` 在 `isFrameDecidedPendingStatus` 里**不是已决** ⇒ 不能当已决丢弃（那等于把一条正在执行的条目从界面抹掉）；但它绝不能再是可点卡 |
| 真终态（`EXECUTED`/`SUCCEEDED`/`REJECTED`/`TIMEOUT`/`BLOCKED` 经 `gateReceiptOfStatus`；`FRONTEND_RESULT`/`FRONTEND_CANCELLED`/`CANCELLED` 等经 `frameStatusToPendingStatus`） | **出队且不重建** | 条目已收口，没有任何需要用户动作的面；重建只会得到一张点了必 409 的卡 |

边界与不变项：

- **本地已有段的条目一律不动**（`materialized` 判定）——它归实时帧路径（`hasDecidedPendingResidue` / first-wins）管；
- **既有帧语义零改动**：`isFrameDecidedPendingStatus`(1101)、`hasDecidedPendingResidue`(3397)、
  `revealNextRestoredPendingCard`(2400-2417)、`rebuildPendingCard`(2312) 均未改；
- `materializeRestoredDecidedCard` 与 `rebuildPendingCard` 共用宿主口径，但**不**武装
  `activeForm` / `activeUploadHandoff` / `activeStalePreview`（那些单例只服务"等用户动作"的卡）；
- `applyGateReceipt` 的 `authorities` 是可选参数，旧调用/旧测试形态不变（唯一生产调用点已传真实快照）。

### GREEN

```
✓ APPROVED ⇒ c2 段 running / requiresAction=false / decision=approved；队列 0；
  pendingToolCall=null（无可点入口）；suspended=false
✓ EXECUTED ⇒ c2 无任何段；队列 0；pendingToolCall=null；suspended=false
```

---

## 3 M-d｜帧先于 POST 时丢续收（`req=false` 的未来卡）

### 复现与判定（先证后改）

确定性复现（正式用例，`ai-multi-card-mixed-resume.spec.ts`）：

```
恢复现场 [c1 PENDING req=true, g2 PENDING req=false] → suspend；恢复连接在 park 时被收掉（loading=false）
→ 用户确认 c1，`confirm_decision` 帧先于 POST 响应抵达（帧与决策同锁发出的合法时序）
→ 帧 handler 的 `clearSuspendedIfNoOtherConfirms('c1')`：c2 无段、队列按设计不含 `req=false` 条目
  ⇒ suspended=false
→ POST 返回后 `reconnectSuspendedRun` 因 `!suspended` 早退 ⇒ 本轮再无连接
   ⇒ g2 的 `frontend_tool_request` 永远收不到（唯一的下发通道是实时帧）

× confirm_decision 帧先到 ⇒ 决策送达仍要续收；随后的下一张请求帧能建卡
  AssertionError: expected "vi.fn()" to be called 1 times, but got 0 times
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:422:29
```

修法（最小，不引入等待、不恢复阻塞）：

```ts
    reconnectSuspendedRun(runId, toolCallId, parkedAtDecision, terminalSeqAtDecision) {
      const host = …（不变）
      if (!host) return
      if (!this.suspended) {
        if (!parkedAtDecision || this.stopped || this.loading
          || this.activeRunId !== runId || roundTerminalSeq !== terminalSeqAtDecision) return
      }
      void this.resumeSnapshotStream(runId, this.runSeq[runId] ?? 0, host.id)
    }
```

- `parkedAtDecision` / `terminalSeqAtDecision` 由 `confirm()` 与 `submitFrontendToolResult()` 在 **await 之前**捕获；
- 新增模块级 `roundTerminalSeq`，`done` / `error` 各 +1（帧归属守卫已保证只统计本轮终帧）
  ⇒ "POST 期间本轮已收口"可判定，**不对终态重连**；
- `this.loading` 闸：连接还在跑（帧由它送达，后续帧也由它送达）时**不另开**第二条；
- `this.stopped` / `activeRunId !== runId`：已停止 / 已换轮不连；
- `suspended === true` 时行为与旧口径**完全一致**（不新增判据），因此既有 5 个 Task 5 spec 的
  重连计数（1 次 / 2 次 / 3 次）全部不变；
- 没有新增任何等待、没有常驻连接、没有改变 park：补救分支只在"决策送达且本轮仍活、无连接、未收口"时发一次 `resumeSnapshotStream`。

### 反向用例（防"重复连接 / 对终态重连"）

```
✓ 反向①：POST 期间本轮已收口（done 先到）⇒ 不得对终态重连
   （reattachRun 0 次；suspended=false）
✓ 反向②：连接仍在跑（loading=true）⇒ 不得再开第二条连接
   （帧清掉挂起后：reattachRun 0 次）
✓ 反向③：inline 前端工具回灌（流还开着）⇒ 不得另开连接
   （reattachActive 建的 1 条连接保持不变，submitFrontendToolResult 后仍是 1）
```

---

## 4 验证证据（真实命令与原文）

工作目录：`<隔离副本>/_verify-20261025-chat-state/frontend`

### RED（生产代码回退到 fix1 版、测试保留）

```bash
cp <隔离副本>/_rv5/frontend/src/stores/ai.ts src/stores/ai.ts      # fix1 版（= main + cumulative）
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts
# → Tests  5 failed | 9 passed (14)
#   5 条即 M-a×2 / M-b×2 / M-d×1（失败原文见 §1/§2/§3）
cp /tmp/ai.fix2.ts src/stores/ai.ts                             # 恢复 fix2
```

### GREEN（fix2）

```bash
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts
# → Tests  14 passed (14)

npx vitest run tests/ai-multi-card-mixed-resume.spec.ts tests/ai-dom-visibility.spec.ts \
  tests/ai-snapshot-freshness.spec.ts tests/ai-round-terminal-residue.spec.ts \
  tests/ai-region-batch-b.spec.ts
# → Test Files  5 passed (5) / Tests  47 passed (47)
#   （mixed-resume 14、visibility 7、freshness 4、terminal-residue 6、batch-b 16）

npx vitest run tests/b1-suspend-disconnect.spec.ts     # 唯一的相邻 spec（挂起/断流/重连语义）
# → Test Files  1 passed (1) / Tests  14 passed (14)

npx vue-tsc --noEmit
# → 无输出，exit 0（prod + tests 全量类型检查）
```

### 补丁等价性（真实命令）

```bash
# 主干归档 → 累计补丁 → 本增量补丁 → 与工作副本逐字节一致
cd <仓外根> && rm -rf _rv5e && mkdir -p _rv5e
cd <主仓> && git archive HEAD frontend/src frontend/tests | tar -x -C <隔离副本>/_rv5e
cd <隔离副本>/_rv5e && git apply -p1 …/task-5-fix1-cumulative.patch \
  && git apply -p1 …/task-5-fix2.patch
cmp frontend/src/stores/ai.ts 工作副本/src/stores/ai.ts                          # SAME
cmp frontend/tests/ai-multi-card-mixed-resume.spec.ts 工作副本/tests/…           # SAME
md5sum …/task-5-fix2.patch    # e339575289d73009d030614fdbc7b00f（交付件与生成件一致）

# 增量补丁不能单独作用于当前 main（预期）
git apply --check -p1 …/task-5-fix2.patch
# error: patch failed: frontend/src/stores/ai.ts:3023 → patch does not apply
```

---

## 5 明确未做 / 未验证边界

1. **M-c（`revealNextRestoredPendingCard` 在该调用点的轮次身份闸恒真）未改**：现由队列在
   `send` / `reattachActive` / `stop` / `adoptFreshSession` / 终态 / `restoreSuspendedSnapshot`
   六处被清空兜住；按控制者口径不扩。
2. 前审其它 Minor（M-3 文案、M-4 409 在 done 之后、M-5 文本段判据、M-6 opacity/sr-only、
   M-7 非 ui 写动作、M-8 ui_scroll 隐藏目标、M-10 `chooseUploadFile` 无身份 / `unavailable` 死态、
   M-11 `beginUiTurn`）一律未动。
3. `exception` 场景未覆盖：M-b 的 409 若响应体 `status` 不可识别（`mapped === null`），本卡仍留
   pending+requiresAction（可重试），此时**也会**补出下一张（两张同屏、都待人）——这是多卡并存的
   既有形态，未额外收紧。
4. 未跑：前端全量 `vitest run`、`npm run build`、后端 Maven、Playwright、真实 Chromium 探针、
   真实 SSE 时序（帧/POST 顺序、park 关闭时机）——全部为 mock SSE + jsdom 下的状态机证据。
5. `irreversible-lifecycle` / `restore-reconcile` 的机器争用超时抖动（fix1 报告 §7）本次未复跑。
6. M-d 的 `loading` 闸依赖"`loading` ⇔ 有在跑连接"这一既有实现约定（`send` / `reattachActive` /
   `resumeSnapshotStream` 三处置真，各自 `finally` 置假）；若将来新增连接入口而不维护该约定，
   需同步复查本闸。
