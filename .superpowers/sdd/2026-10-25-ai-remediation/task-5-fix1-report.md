# Task 5 · 复审修复报告（I-1 / I-2 / M-1 / M-2）

**结论：DONE** —— 复审点名的 3 项（I-1、I-2、M-1）与 1 项测试注释陈旧（M-2）全部先证 RED、再最小修复并转 GREEN。未扩到 M-3～M-11，未跑全量/build，未改主仓、未提交、未派子 agent。

- 工作副本：`<隔离副本>/_verify-20261025-chat-state/frontend`（= main `34fd8f4` + `task-5.patch` + 本次修复）
- 补丁（两份，按需取用）：
  - **`task-5-fix1.patch`（12.5 KB / 235 行）—— 增量补丁，作用于「main + task-5.patch」**（推荐：控制器已应用 task-5.patch 的树直接 `git apply -p1`）
  - `task-5-fix1-cumulative.patch`（69 KB / 1512 行）—— 累计补丁，作用于**当前 main**（等价 task-5 + fix1 一次落地）
  - 两份均已用「pristine 树 → apply → 逐字节 cmp」验证，累计补丁另在**主仓**用 `git apply --check` 验证（未落盘）
- 证据日志（`<隔离副本>/_verify-20261025-chat-state/`）：
  - `red-fix1.log` —— 修复前：**3 failed / 9 passed**
  - `green-fix1.log`、`green-fix1-final.log` —— 修复后：**39 passed**
  - `green-fix1-adjacent-a.log`、`green-fix1-adjacent-b.log` —— 相邻 spec

---

## 1. 改动清单（精确）

| 文件 | 改动 |
|---|---|
| `frontend/src/stores/ai.ts` | ① `hasOtherPendingCards` 计入 `restoredPendingQueue`（I-1）；② `applyGateReceipt` 在权威裁决落地后补出队列里的下一张（I-1 收容）；③ `error` 终帧分支加 `this.suspended = false`（I-2） |
| `frontend/tests/ai-multi-card-mixed-resume.spec.ts` | +2 例：时序反转（帧先于 POST 响应）��409 校正拿到裁决后补卡 |
| `frontend/tests/ai-round-terminal-residue.spec.ts` | +1 例：挂起中收到 `error` ⇒ 解挂起 + 清单例 + 不再回灌 |
| `frontend/tests/ai-region-batch-b.spec.ts` | M-1：B-08 站到 `QUERY_COND` 并先证明量到的是 COND 快照；M-2：两处 `bumpAiEpoch()` 及过时注释按 domVersion 语义改写（并移除随之无用的 import） |

未改动：`ai-dom-tools.ts`、`frontend-tools.ts`、`ui-receipt.ts`、`AiPanel.vue`（本轮无需触碰）。

---

## 2. I-1｜结局帧先于 POST 响应时丢卡 + 丢重连 + 解锁输入区

### 机制（复审 §2-② 已定位，本次独立复证）

`restoreSuspendedSnapshot` 只把第一张建成段，其余进 `restoredPendingQueue`。`hasOtherPendingCards` 只看段 ⇒ 当 `confirm_decision` **先于** `confirm` POST 响应到达（帧与决策同锁发出，合法时序）时：

1. 帧 handler → `clearSuspendedIfNoOtherConfirms('c1')` 见"没有别的段" ⇒ `suspended=false`；
2. POST 返回 → `reconnectSuspendedRun` 早退（`!suspended`）、`revealNextRestoredPendingCard` 早退 ⇒ **不重连、第二张永不出现**；
3. 输入区被解锁，而后端这一轮仍 SUSPENDED ⇒ 下一条消息撞 409。

### 修复

```ts
// ai.ts#hasOtherPendingCards（节选）
const hasPendingPart = this.messages.some(...)          // ① 已建段的待人卡（原有判据）
if (hasPendingPart) return true
// ② 已从快照恢复、但还没轮到建段的卡（串行呈现队列）
return this.restoredPendingQueue.some((entry) => !this.hasDecidedPendingResidue(entry.toolCallId))
```

- 用 `hasDecidedPendingResidue` 过滤：队列里只剩已决条目时不计入（`revealNext` 会跳过并清队，不会造成永久锁）。
- **收容（必需，否则本条修法自身会造出"锁死且无卡"）**：`applyGateReceipt` 是 `syncGateFromAuthority` 的唯一调用点（409 后权威裁决落地）。此前该路径不补卡；现在 `suspended` 会因队列非空而保持为真，若不补卡就没有任何可点入口。故在 `clearGateSubmission` 之后、`clearSuspendedIfNoOtherConfirms` 之前加：

```ts
const runId = this.activeRunId
if (runId) this.revealNextRestoredPendingCard(runId)
```
只补卡、**不主动重连**：人等人期间本来就没有连接（park 非阻塞语义），用户决策该卡时 `submitFrontendToolResult` 会续流。

### RED → GREEN

```
# 修复前（red-fix1.log）
× 时序反转：confirm_decision 帧先于 confirm POST 响应到达 ⇒ 仍不得解锁、不得丢重连与第二张卡
  AssertionError: expected false to be true
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:167:29        // expect(store.suspended).toBe(true)

× 409 校正拿到真实裁决时：恢复队列里的下一张仍要补出（不得锁死输入区且无卡可点）
  AssertionError: expected undefined to be 'g1'
  ❯ tests/ai-multi-card-mixed-resume.spec.ts:286:42        // expect(store.activeForm?.toolCallId)

# 修复后
✓ 时序反转…仍不得解锁、不得丢重连与第二张卡
✓ 409 校正拿到真实裁决时…（gateSubmission 清空 + activeForm=g1 + 队列出清 + suspended 仍真）
```

用例形态（正是复审要求的"队列长度 1、reattach/card2 期望"）：`runs/current` 返回 `[CONFIRM c1, GENERATIVE_FORM g1]` → `restoreSuspendedSnapshot()`（断言 `restoredPendingQueue` 长度 1、`activeForm === null`）→ `api.confirm` 的实现里**先** `store.handleFrame(confirm_decision…)` **再** resolve → 断言 `suspended===true`、`reattachRun` 1 次、`activeForm.toolCallId === 'g1'`、队列清空。

**收容的独立验证**：单独移除 `applyGateReceipt` 的补卡 3 行（保留队列判据）后重跑第二条用例 ⇒ `expected undefined to be 'g1'`（RED）；恢复后 GREEN。即两条改动各自都有因果证据。

---

## 3. I-2｜`error` 终态不解挂起 ⇒ 输入区永久禁用且无卡可点

### 机制（复审 §2-③，本次独立复证）

`done` 分支显式 `this.suspended = false`，`error` 分支没有。挂起中（有 `confirm_request` / 交接卡）收到 `error` 时：`expireResidueToolParts` 把段收口（`pendingToolCall` 变 `null`），但 `suspended` 恒真 ⇒ `composerHardDisabled = true`、占位仍为"正在等待你的确认…"，屏上没有任何可点的卡，只能刷新。

### 修复

```ts
// ai.ts case 'error'，紧随 cleanupTerminalInteractionState()
// I-2（审查）：与 done 同口径解掉挂起态。…
this.suspended = false
```

### RED → GREEN

```
# 修复前（red-fix1.log）
× error（挂起中）：必须与 done 同口径解掉挂起态，屏上不留"等待确认"的死锁
  AssertionError: expected true to be false
  ❯ tests/ai-round-terminal-residue.spec.ts:173:29        // expect(store.suspended).toBe(false)

# 修复后
✓ error（挂起中）：…（pendingToolCall===null、loading===false、suspended===false、
    activeUploadHandoff===null、uploadHandoffAvailability()===null、notifyAiUploadDone 零回灌）
```

用例走**真实帧路**（`start` → `frontend_tool_request(ui_request_upload)` ⇒ `suspended` 置位、交接卡建立）再发 `error`，与 done 用例对称。

**非 park / 非 error 行为保持**（同文件回归锁，修复前后都绿）：

```
✓ done：上传交接单例被清，已结束轮不再接受 notifyAiUploadDone 的回灌
✓ error：表格预览计划与失效标记一并清…
✓ 挂起收流（park，无 done）不得收口：卡仍在、可继续（非阻塞语义不破）
✓ 活动卡自己的放弃照常回灌 cancelled=true
```
即：**只有真终态（done/error）才收口**；`suspended`（park）帧仍保留卡与挂起态。

---

## 4. M-1｜B-08 尺寸闸量到的是"简单快照"，不是"最复杂 COND 快照"

### 机制与 RED

`ai-region-batch-b.spec.ts` 的 B-08 仍用 `EXPORT_TASK`（`currentStep: 'SELECT_DEFS'`）。可见性闸生效后第 2 步整体隐藏 ⇒ 该用例实际在量**第 1 步的简单快照**，"2000 字符硬顶在最复杂快照上"的回归保护已悄悄丢失。

RED 证明（临时把修复行改回 `EXPORT_TASK`，只跑该用例）：

```
× 两个配置项各带条件时仍受 2000 字符硬顶，尾行「共 N 个元素」不丢
  AssertionError: expected [] to not have a length of +0
  ❯ tests/ai-region-batch-b.spec.ts:323:70     // 断言"本快照里确实有 export.cond.* 行"
```

### 修复

```ts
api.getTask.mockResolvedValue({ ...EXPORT_TASK, currentStep: 'QUERY_COND' })
await mountView(ExportWizardView)
const out = aiSnapshot({})
const refs = refsOf(out.result)
expect(refs.filter((ref) => ref.startsWith('export.cond.'))).not.toHaveLength(0)  // 先证明量到 COND
expect(out.result.length).toBeLessThanOrEqual(2000)
expect(out.result).toMatch(/共 \d+ 个元素/)
```

新增的"确有 COND 行"断言是**防再次降级**的护栏：将来若快照又只覆盖第 1 步，这条会立刻变红。

---

## 5. M-2｜`bumpAiEpoch()` 相关陈旧注释

修复前两处写着"快照按 epoch 缓存（同 epoch 同参直返）⇒ 点完必须先失效 epoch"，与 FE-33 后的新语义矛盾（写动作成功即 `markUiMutation()` 推进界面写入版本，两层缓存自动失效）。

处理（比"只改注释"更强）：

- 删除两处多余的 `bumpAiEpoch()` 调用 + 更新注释为"写动词成功即自动失效，不再依赖外部推进 epoch"；
- 移除随之无用的 `bumpAiEpoch` import（`vue-tsc` 零错误）。

结果：`点编辑区 tab ⇒ aria-selected 跟随` 与 `填写搜索框 ⇒ 表格行被过滤` 两条**在不调用 `bumpAiEpoch` 的情况下依然通过** —— 这恰好成为"真视图里 domVersion 失效有效"的端到端证据（此前测试靠手动 bump 绕过缓存，是审查 §6.2 点名的掩盖手法）。

---

## 6. 验证证据

工作目录：`<隔离副本>/_verify-20261025-chat-state/frontend`

### RED（仅 fix1 生产代码回退，测试已更新；`red-fix1.log`）

```
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts tests/ai-round-terminal-residue.spec.ts
→ Tests 3 failed | 9 passed (12)
  × 时序反转…                    (I-1)
  × 409 校正…下一张仍要补出       (I-1 收容)
  × error（挂起中）…             (I-2)
```

### GREEN（`green-fix1.log` / `green-fix1-final.log`）

```
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts tests/ai-dom-visibility.spec.ts \
  tests/ai-snapshot-freshness.spec.ts tests/ai-round-terminal-residue.spec.ts \
  tests/ai-region-batch-b.spec.ts
→ 5 files / 39 tests passed
   （mixed-resume 6、visibility 7、freshness 4、terminal-residue 6、batch-b 16）
```

### 相邻 spec（同一副本，均 GREEN）

| 批次 | spec | 结果 |
|---|---|---|
| A（`green-fix1-adjacent-a.log`） | `parts-residue-cleanup` 49、`b1-suspend-disconnect` 14、`r166-gate-link` 9、`r166b-fixes` 25 | **97 passed** |
| B（`green-fix1-adjacent-b.log` 及后续单跑） | `frontend-tool-result-terminal` 30、`restore-request-emitted` 8、`restore-orphan-null-req` 6、`irreversible-lifecycle` 8、`restore-reconcile` 4 | **56 passed** |

### 类型检查

```
npx vue-tsc --noEmit    →  0 error
```

### 补丁可应用性

```
# 增量补丁（base = main + task-5.patch）
git apply -p1 task-5-fix1.patch          → 4 个文件逐字节一致

# 累计补丁（base = 当前 main）
git apply -p1 task-5-fix1-cumulative.patch → 10 个文件逐字节一致
cd <主仓>
git apply --check -p1 …/task-5-fix1-cumulative.patch → OK（仅检查，未落盘）
# 主仓 frontend 仍只有既有的 types/api.ts 改动
```

---

## 7. 机器负载导致的间歇失败（如实登记，非本次改动引入）

在这台机器上跑并行批次时，`restore-reconcile.spec.ts` / `irreversible-lifecycle.spec.ts` 出现间歇失败；**同一时段另一个 agent 的 vitest 副本（`_verify-20261025-cluster-config-resume`）在跑**，失败形态全部是超时或与超时伴生的断言：

- `irreversible-lifecycle` 的挂载用例：`Error: Test timed out in 5000ms`（`AiPanel` 挂载在争用下 >5s）；`--testTimeout=30000` 下 **8/8 通过**。
- `restore-reconcile`：多次为 `Test timed out in 15000ms`（该 spec 自带 15s 上限）；闲置时连续 **5/5 通过**。
- 用**未打补丁的 main `ai.ts`** 同样复现 `负例①: timed out in 15000ms`，说明不是本次改动引入。
- 为排除"补丁让它更容易超时"的可能，做了**交替 A/B**（5 轮：fix1 → main 同一用例交替跑）：`FIX1[passed] MAIN[passed]` × 5，无差异。

结论：**属于机器争用下的超时抖动**，不构成本补丁的功能回归；但控制器最终全量运行时若仍见该 spec 抖动，建议提高其 per-test timeout 或串行跑，而不是当作逻辑问题归因于本补丁。

---

## 8. 明确未做（按指令不扩围）

- 未处理复审 Minor：M-3（`GATE_SYNC_GRACE_MS` 文档与"用尽后文案"）、M-4（409 在 done 之后复活僵尸卡）、M-5（文本段判据更浅）、M-6（opacity/sr-only/移出视口未覆盖）、M-7（非 ui 写动作不推进版本）、M-8（`ui_scroll` 对隐藏元素仍报成功）、M-10（`chooseUploadFile` 无身份、`unavailable` 死态）、M-11（`beginUiTurn` 只在首次 UI 工具时推进）。
- 未跑前端全量、`npm run build`、后端、Playwright、真实 Chromium 探针（brief Step 1 的"原生点击/折叠先上浏览器确认"仍未做，登记在 `task-5-report.md` §8-⑤）。
- 未提交、未 push、未改主仓源码。

---

## 9. 合流提示

1. 若控制器树已含 `task-5.patch`：`git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-fix1.patch`（4 文件）。
2. 若尚未应用：直接 `git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-fix1-cumulative.patch`（10 文件，等价 task-5 + fix1）。
3. `task-5.patch` / `task-5-report.md` 保持不变，本报告只增量说明；`ai-region-batch-b.spec.ts` 仍可能与 Task 4 相邻改动冲突，口径以"隐藏步骤不披露"为准。
4. 建议把 `irreversible-lifecycle` / `restore-reconcile` 的超时抖动（§7）在最终全量时单独观察一次。
