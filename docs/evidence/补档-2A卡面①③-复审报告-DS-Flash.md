# 补档：2A 卡面①③ 修复 — 复审报告（DS-Flash）

- **归档来源（已脱敏）**：`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1211/wire.jsonl` 末条 assistant 正文（逐字提取，仅脱敏本机路径与对话路径）
- **归档日期**：2026-10-20
- **角色**：独立红队复审（explore · 只读）；批次：2A 剩余①③卡面修复
- **自证**：自身 wire 的 `profile.bind` 单行 = `{"type":"profile.bind","agentId":"agent-1211","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high"}`

---

# 2A①③ 卡面修复批 — 独立红队复审

## 自证（首部原样引用）

`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1211/wire.jsonl` 首条 `profile.bind`（首部原样）:

```
{"type":"profile.bind","agentId":"agent-1211","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.\n\nYour primary goal is to help users with software engineering tasks.\n\nYou are now running as a subagent...
```

## 审查锚

- 仓库 HEAD: `830a1c96665e55bf3f80ed6ab58a6f9c17028b67`
- `git diff | sha256sum` = **`8fa5c539bec05872f8750d0c04f6d9794343d0f2a7da8d151abaf58b8d761d0d`**（复跑前后重锚同值，工作树零漂移）
- 范围：5 文件 `+278/-8`（裁：parts.ts +84、ai.ts +7、blocks.ts +4、parts-reducer.spec +186、blocks.spec +5）；`git diff --check` 干净
- 旁注：工作树有未跟踪 `data/`（非本批产物，入库时勿 `git add -A`）

## 实测（只读复跑）

| 命令 | 结果 |
|---|---|
| `cd frontend && yarn test 2>&1 \| tail -3` | **24 files / 488 tests 全绿**（含 r166-gate-link、parts-double-write） |
| `cd frontend && yarn typecheck` | `vue-tsc --noEmit` 0 错 |
| `node scripts/ci/check-docs.mjs` | **6/6 PASS** |

## 结论：**READY-WITH-NITS**

两项挂账的主路径修法正确，且均可用后端源码证伪/证实；未发现需要返工的功能缺陷。以下为分级发现。

## 必查项逐条

### 1. ③「修复前即绿、P0-1 已打通」——**主张可信（亲证）**

三支 ③ 用例在 HEAD 版本下都会绿，理由是旧 `readOnlyStateOf` 的兜底链（`git show HEAD:frontend/src/utils/blocks.ts:340-352`）：无 `decision` 时 `failed ⇒ 'failed'`，`isReadOnlyCard` 对 `CONFIRM + failed + requiresAction=false` 为真，标签即 `! 已批准 · 执行失败`。因此：

- 乐观写路径：用例自己先 `upsertToolPart({decision:'approved'})`，旧帧分支不写 decision 也不影响判定 → 绿；
- 无乐观写路径（`parts-reducer.spec.ts:726`）：旧代码 `status running→failed` 无 decision，兜底同样落 `failed` → 也绿；
- store 集成路径 → 同上。

所以「P0-1 早已打通，本批只是补锁」成立；新加的 `decision:'approved'`（`parts.ts:635`）在 ③ 里**并非必需**，真正锁它的只有 ① 的负控制行（`parts-reducer.spec.ts:630`）。实施方未夸大。

**第三条未覆盖路径**：历史恢复后执行失败（`stores/ai.ts:1184-1207` 的 outcome→status 映射 + `:1212` decision 回读）。逻辑与帧径同源，但本批无对应用例（现有 `ai-panel-parts-render.spec.ts:2340` 只覆盖 `EXECUTED→succeeded`）→ 列为 NIT-4。

### 2. ① first-wins 边界——**实现正确，且「批准后超时帧」不会漏真实超时**

亲证后端：`ConfirmGate.expire()` 对**非 PENDING** 条目直接 `return fresh` 且不落帧（`backend/.../gate/ConfirmGate.java:379-403`），`SseChatEmitter.confirmDecision` 的 `status` 取的就是 `pending.getStatus()`（`SseChatEmitter.java:618-628`）。故「服务端已 APPROVED 之后再发 TIMEOUT」在一致后端下不可达；唯一会命中 `running` 分支的 TIMEOUT 是**本地乐观写 running、服务端尚未裁决**的情形，此时以服务端 TIMEOUT 落 `expired` 是对的。守卫 `existing.status !== 'pending' && !== 'running'`（`parts.ts:598`）不会吞掉任何真实超时。

口径一致性：APPROVED/REJECTED 覆盖本地 `expired`，与 `stores/ai.ts:1966` 注释「后端结局帧若随后到达，仍按既有收敛路径覆盖为真实终态」一致；仅 TIMEOUT 帧不覆盖（同属 expired 终态），有意为之（见 NIT-3）。

### 3. 回归面——**全绿为真；枚举扩容有一处「安全但陈旧」的写侧读者**

- 既有消费方全绿（488/488），大小写帧（`decision:'REJECTED'`）也被 `toLowerCase` 兼容（`parts.ts:371-372`）——反而比旧 `approved` 布尔更稳。
- 唯一写侧词表：`stores/ai.ts:1212` 仍为 `'approved' | 'rejected'` 二元白名单。**当前不炸**（后端历史载荷从不写 `timeout`，`ResilientChatService.java:738-747` 只写 approved/rejected；镜像分支原样透传 parts），但类型已三值、注释仍写「仅认词表两值」→ MINOR-2。

### 4. CANCELLED 顺带对齐——**同 bug 类收口可接受，但优先级有一处真实帧形态未覆盖**

后端 `ConfirmGate.cancel()` 对**非 PENDING** 条目只跳过状态改写、仍无条件发帧（`ConfirmGate.java:344-359`），而等待循环把「已取消」判定放在「已决」判定之前（`:290-302`），因此**真实会发出** `confirm_decision{decision:'cancel', status:'APPROVED'|'REJECTED', approved:true}`。新 helper 让 `decision='cancel'` 先于 `status` 命中（`parts.ts:374-376`）→ 段态落 `cancelled`（`parts.ts:620-628`）。常见实时路径下标签仍由既有 decision 元数据兜住，回放被截断/首帧缺失时会显示「! 已超时」而实际是已批准/已拒绝 → MINOR-1。

## 分级发现

**MINOR-1** `frontend/src/utils/parts.ts:374-376`（配 `:620-628`）— `decision='cancel'` 的优先级高于具体终态 `status`。证据：`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:344-359`（cancel 只对 PENDING 改状态、但无条件发 `decision="cancel"`）+ 循环顺序 `:290-302`。建议：`status` 命中 APPROVED/EXECUTED/REJECTED/REJECTED_ARGUMENTS 时先按状态归并，仅当 status 为 CANCELLED/PENDING/缺失时才走 cancel 分支。影响面窄（需回放缺帧），不阻断本批。

**MINOR-2** `frontend/src/stores/ai.ts:1209-1213` — 类型已扩到 `'approved' | 'rejected' | 'timeout'`（`types/ai.ts:304`），历史回读白名单仍二元且注释写「仅认词表两值」。今日后端不产 timeout 所以无实害；建议补 `'timeout'` 或改写注释，避免后续真正持久化 timeout 时被静默丢弃。

**NIT-1** `frontend/tests/parts-reducer.spec.ts`（新增 describe 共 7 例）— 新增的 CANCELLED 分支**零用例**；它超出两项挂账的范围，是行为变更，建议至少一条负控制（`decision:'cancel'` → `status:'cancelled'`、`decision` 缺省、标签「! 已超时」）。

**NIT-2** `frontend/tests/blocks.spec.ts:275` — 新增的「③ 已批准·执行失败」断言与 `:268` 原文重复，零新增覆盖（注释亦自认「上方既有行已覆盖」）。

**NIT-3** `frontend/src/utils/parts.ts:594-607` — 本地已 `expired` 时跳过 TIMEOUT 帧，导致同一服务端结局在不同页签落成两种持久化形状（一边 `decision:'timeout'`，一边无 decision）；且与权威回执 `gateReceiptOfStatus('TIMEOUT')`（`stores/ai.ts:235-236`，status expired、无 decision）不对称。展示等价，仅元数据口径差异，建议在注释里点明取舍。

**NIT-4** 覆盖缺口：③ 的「历史恢复后执行失败」路径（历史映射 `stores/ai.ts:1184-1207` + `:1212`）无新增用例；逻辑同源，风险低。

## 锚定与纪律

- 只读复核，未改文件、未 commit、未起服务；diff SHA 复跑前后一致 `8fa5c53…`，审查锚 = 本次复核锚（若后续收口修，须重锚）。
