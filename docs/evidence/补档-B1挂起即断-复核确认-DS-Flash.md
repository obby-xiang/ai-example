# 补档：B1 挂起即断 — 复核确认（DS-Flash）

- **角色**：复核确认（只读；仅允许跑测试/门禁），批次 = B1 挂起即断 SSE（**收口修 C-1 之后**的短卡复核）
- **复核范围**：只核三点 —— ①C-1 修法正确性（`roundEpoch` 段 + 4 个新回归用例）②断言同步（施工报告 §7 / 任务看板 :13 与 :386-391 / research README 补登行）③门禁抽查
- **自证**：`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1283/wire.jsonl` 第 5 行，`profile.bind` 实测 `agentId=agent-1283`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`
- **仓根**：`<REPO_ROOT>`（仅此仓）

---

## 结论：**通过**

C-1 修法把「晚到结局」的判据从**连接身份**（`currentAbort` 引用）换成**轮次纪元**（`roundEpoch`），两个方向都锁住：挂起收流 / 同轮重连**不误杀**（真回灌），新消息 / 重挂 / 换会话 / 停止**不误放**（真丢弃）。文档三处与实际改动一致；前端全量 504/504 · 25 文件实测全绿，后端定向 3/3 绿。

## 审查锚（新，工作树 SHA-256）

| 项 | 值 |
|---|---|
| 含新件 `git diff \| sha256sum`（5 个未跟踪件逐个 `git add -N` 后 `git reset -q`；`data/` 排除） | `ac99db649b24b2518e11c04aee41eeb7f42005cbcaa547994ee1344f3baf2f3c` |
| 仅跟踪件 `git diff \| sha256sum` | `f25193e6532c32eeae7d2c66caacd41d7606e3048e352d840c921fb9948a30e1` |
| 规模 | 30 文件，+1730 / −117（较复审锚 `973e4a32…` 的增量 = C-1 修（`stores/ai.ts` +37/−6）+ 4 例新用例 + §7 文档） |
| 注释 | 本复核确认件自身不计入上述锚（写入前的树） |

## 1) C-1 修法正确性：通过

判据落点（`frontend/src/stores/ai.ts`）：

- 声明与口径注 `:75`（`let roundEpoch = 0`）；
- 四个**真换轮**入口递增：`send()` `:1474`、`reattachActive()` `:1673`、`stop()` `:2267`、`adoptFreshSession()` `:2320`；
- 捕获 `:3027`（`runFrontendTool` 入口 `const epochAtStart = roundEpoch`）、比对 `:3053`（`if (roundEpoch !== epochAtStart) return`，位于 `executeFrontendTool` await 之后、段终态写与回灌之前）；
- **不**递增的同轮入口：`resumeSnapshotStream()`（`:1362` 注释显式写明「不递增，否则工具回灌会在重连后被误丢」）、`reconnectSuspendedRun()` `:1433`。

方向 A（不误杀：挂起收流 + 同轮重连）：

- 挂起收流在 `send()` 的 finally 里把 `currentAbort` 置 `null`（`:1574`），纪元不变 ⇒ 旧判据必然误判、新判据放行；
- 用例①`b1-suspend-disconnect.spec.ts:468`：执行器挂起 → `onClose(suspended=true)` → 再 resolve，断言回灌恰 1 次（含 `runId/result/outcome`）且段 `succeeded`；
- 用例②`:500`：先 `confirm_request` 再挂起收流，`store.confirm()` 触发 `resumeSnapshotStream`（controller 已换，`loading=true`），随后工具 resolve ⇒ 仍回灌、段 `succeeded`。这一例正好锁死「新连接 ≠ 新轮」。

方向 B（不误放：true positive 仍丢弃）：

- 用例③`:539`：第一轮挂起后用户 `send('第二轮')`（`:1474` 递增）⇒ 旧工具结局不写终态（段保持 `running`）、回灌 0 次；
- 用例④`:569`：挂起态 `stop()`（`:2267` 递增）⇒ 同上丢弃且 `cancel` 已发。
- `reattachActive` / `adoptFreshSession` 两个入口的递增在位（`:1673` / `:2320`），无直接用例但判据同源（同一模块级计数器），见 §4 观察 1。

补充核对：捕获点在任何 `await` 之前（`:3027` 紧跟函数入口），`upsertToolPart(running)` 前的 `await Promise.resolve()` 不改变纪元；`send()` 的 loading 守卫在递增之前（`:1471` 早退不递增），不会因被拒发送误杀在途工具。

## 2) 断言同步：通过

- **施工报告 §7**：`docs/evidence/补档-B1挂起即断-施工报告.md:119-147` 的落点行号与实测逐一对上（`:75 / :1474 / :1673 / :2267 / :2320 / :3027 / :3053`）；§7.2 的 4 例场景描述与 `frontend/tests/b1-suspend-disconnect.spec.ts:467-592` 实际断言一致（1 正 + 1 同轮重连 + 2 负控制）；§7.3 M-1 更正为 576/576、M-7 README 补登、M-8 `EmptyReplyTerminalTest` 重复 javadoc 已删，均与工作树相符。
- **任务看板**：`:13` 多实例专项行状态 `🔧`、进展「B1 🔧 施工中（2026-10-20 二十九棒…）」与依据列（设计稿 + 施工报告 + README 第 25–28 行）一致；`:386-391` 恰 6 行 = M-2 / M-3 / M-4 / M-5 / M-6 / M-9，依据均指向复审报告 §7，与 §7.4 挂账表逐条对得上；页末速览「挂账小节 55 项（含 B1 补登记 6 条）」同步。
- **research README**：第 28 件 `设计-B1挂起即断SSE-2026-10-20.md` 已补登（主题 + 一句话结论 + 日期），「本索引收录上表 28 件（… 4 件 2026-10-20 补登）」与「B2/B3 仍待设计稿」注记一致。
- 门禁数字自洽：报告 §5 与 §7.5 均写「前端 504/504 · 25 文件」，与本轮实测相同（见 §3）。

## 3) 门禁抽查（本轮实测）

```
cd frontend && node node_modules/vitest/vitest.mjs run
  → Test Files 25 passed (25) / Tests 504 passed (504)；11.83s
    b1-suspend-disconnect.spec.ts 14/14（5.995s）
backend: mvn.cmd -o test -Dtest='*B1*,*b1*,SuspendDisconnect*'
  → Tests run: 3, Failures: 0, Errors: 0, Skipped: 0（SuspendDisconnectGateTest）· BUILD SUCCESS · exit 0
```

后端 576/576 · 91 类未重跑（按卡片只做定向）；报告 §5/§7.5 的 576 与其自述的「maven 汇总行 + 91 个 surefire suite XML 合计」口径一致，本轮不构成反证。

## 4) 残留观察（非阻塞，供入库记录）

1. `reattachActive()` / `adoptFreshSession()` 两个递增点无直接回归用例；前者的「新连接接管即换轮」由用例②从反方向（同轮重连不换轮）间接界定，后者与用例③同判据。建议后续补 1 条「重挂后旧工具结局丢弃」正控。
2. 施工报告 §1 前端表的行号锚（如 `suspendedClose :1479`、`reconnectSuspendedRun() :1412-1432`）是收口修前的快照，现已漂移 10–20 行（实际 `:1494` / `:1433-1450`）；§7 已给新锚，§1 属历史清单，不影响复核。
3. `roundEpoch` 为模块级且无显式复位 —— 四个换轮入口已覆盖 `resetSession`（经 `adoptFreshSession`），`takeOverSession` / `loadHistory` 有「messages 非空 / loading / activeRunId」前置守卫，接管时不可能存在在途前端工具，故无遗漏。

## 5) 纪律声明

只读：未改任何生产/测试/文档（仅新建本确认件），未 `commit` / `push`，未启停服务；`git add -N` 仅用于锚计算且随即 `git reset -q`（`git diff --cached` 为空），`data/` 未动。

## 自证行

`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1283/wire.jsonl` 第 5 行，原样首部：

```json
{"type":"profile.bind","agentId":"agent-1283","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.…
```
