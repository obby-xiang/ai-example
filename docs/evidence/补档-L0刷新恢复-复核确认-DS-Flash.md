# 补档 · L0 刷新恢复漏洞修复 复核确认（DS-Flash，2026-10-21）

## 0 自证行（wire 实测）

- 会话 `session_081d49e8-17a5-451b-befe-c0422c23a69b`；复审 agent = `agent-1294`（同一 wire，`grep profile.bind` 单行）
- 实测：`{"type":"profile.bind","agentId":"agent-1294","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}`
- 只读：零 commit/push、零服务启停；新 diff 取数 = 6 个新件中 5 个逐个 `git add -N` → `git diff` → `git reset -q`（已还原，`git status` 仍为 15 M + 7 `??`）。

### 新锚（SHA-256，与上一轮对比）

| 取数面 | 上一轮 | 本轮 | 行数 |
|---|---|---|---|
| 全量 diff | `e79a1ca9…7161` | **`eb89a9d5bfeaf5825029fde1cf8d67b03e7a638e0636bcf535d215947cf602f8`** | 1786（+268） |
| 生产-only | `794d53f2…0077` | **`7ed532a884987a7e49c8c0db3f282b27dc6cefc416d93946b1f4b7ec027de9d1`** | 338（+62） |

生产面增量逐行核对 = `ConfirmGate` 复判 + `consumeDecided` 抽提 / `ai.ts` 无卡挂流 + `resumeSnapshotStream` 宿主可选 / `activeRoundHostId` 接受 null，**无其他生产改动**。

## 1 结论：**READY-WITH-NITS**（0C / 0I / 0M / 3N）

上轮 1I+4M 全部按裁决面收口且经实测复核；三点核查全部通过，门禁自跑与施工自报一致。仅余 3 条 N（测试覆盖/文档行号精度/既有看门狗语义的残余边界），不阻塞。

## 2 三点核查

### 2.1 I-1 / M-1 修法正确性

**I-1（无卡挂流）**：`stores/ai.ts:1369-1390`。有卡路径不变并 `return`（`:1371-1372`）；无卡路径要求 `this.suspended && current.runId`（`:1381`，`suspended` 由本函数 `:1305-1309` 在同一轮内先置位 ⇒ 该守卫等价于「存在 PENDING 条目」，语义自洽），有末条助手消息传其 id（`:1384-1386`），否则省略宿主（`:1389`）。

- **无空气泡**：连接建立期不建任何消息；`activeRoundHostId = hostMessageId ?? null`（`:1414`）→ `handleFrame` 的 `ownedByActiveRound = assistantId === undefined || …`（`:2626`）恒真，宿主解析走 `currentAssistantMessage()`（`:2606-2617`，优先 `!done` 的助手消息、无则 `appendAssistantMessage()`）⇒ **首个业务帧到达时才建消息**。测试也证实：例 3/I-1 回归例在 restore 后断言 `toolSegments(store.messages)` 为 0（`restore-request-emitted.spec.ts:158`、`:176`），`messages` 为空，`reattachRun` 已调 1 次（`:160`、`:178`）。
- **无新类「错宿主」**：`[...messages].reverse().find(role==='assistant')`（`:1384`）与建卡路径的兜底宿主**完全同源**（`:1329`），故无卡路径只是沿用自 T2b-D 起既有的宿主约定；帧后续写入同一宿主，也与建卡路径把 `host.id` 传给 `resumeSnapshotStream` 的行为一致（`:1371`）。**不构成新行为面**。
- 类型/收尾链路：`activeRoundHostId` 声明 `string | null`（`:64`），`finally` 复位 null（`:1455`）；`onClose` 在 `info.suspended` 时直接返回，不误报断流（`:1438-1440`）。

**M-1（置位后复判）**：`ConfirmGate.java:444-461`。第一分支 `fresh.resolved()` 与新增第二分支 `marked != null && marked.resolved()` 都走 `consumeDecided`（`:448`、`:454`），该函数单点完成「先摘闸门、后摘预登记标记 + INFO 日志」（`:467-477`），与 `await.finally`（`:408-409`）、`disarm`（`:281-285`）同序 ⇒ **两分支行为逐字一致**，不存在只归还一半的路径。`marked == null`（条目消失）仍落到发帧，属既有边界（见 §3）。

- 窗口是否关死：`markRequestEmitted` 的读与写在 `submitLock` 内（`:445`），返回的副本若已决则该次置位**未被写入**（`RunStore.java:415-417` 原样返回）⇒ 复判后不发帧、不断流。剩余窗口 = 「置位写入 → emit」这一次调用间隔，与「决策恰好与 emit 同时到达」同阶，**属固有窗口，非本修法残留**（要再关只能上跨实例 fencing，B3）。
- 用例：`ConfirmGateResolvedSkipTest#entryDecidedBetweenTheReadAndTheMarkIsConsumedInsteadOfEmitted`（`:123-160`）用 `mock(RunStore)` 精确复现「读 PENDING → 置位返回已决」，守护线程 + `join(5000)` 把旧的「挂死」形态转成可判定失败；断言覆盖：不进入等待、按 APPROVED 消费、无 `confirm_request`、`subscriberCount()==1`、闸门归零。

### 2.2 断言同步

| 项 | 施工报告 §9 声明 | 实测 | 判定 |
|---|---|---|---|
| I-1 落点 | `ai.ts:1369-1392`、`:1399-1414`；§7-2 措辞改「前一张卡的工具执行时长」 | 代码块实为 `:1369-1390`，签名/赋值 `:1399-1414`；§7-2 措辞已改 | ✔（行号差 2 行，N-2） |
| M-1 落点 | `:448-455`、`consumeDecided :467-477`（§9.1 表内写 `:467-482`） | 方法 `:444-461`、`consumeDecided :467-477` | ✔（`:467-482` 略超 5 行，N-2） |
| M-3 拆例 | 现 8 例 = 混批真实形态 + 全 BACKEND 早退 | `spec` 8 个 `it`：`:112` 混批（`awaitingExternal` 默认 true，`unresolvedExternal=[c1]`）、`:130` 全 BACKEND（显式 `awaitingExternal:false, unresolvedExternal:[]` + 断言不建卡/不置挂起/不挂流 `:142-145`） | ✔ 锁的是 API 可达状态 |
| N-1 | `value(nullValue())` + `keys()` 含该键 | `AiControllerRunsCurrentTest.java:187-188` 两条断言齐备 | ✔ |
| N-2 | 新增 `requestFrameIsEmittedOnlyAfterTheMarkCallReturns`（调用时序） | `ConfirmGateRequestEmittedTest.java:125-162`：emit 打点读计数 == 1，反序必为 0；`finally` 中断 + `join` | ✔ |
| N-3 | §7-9 点名「已决残留也置 suspended」 | 报告 §7-9 已加 | ✔ |
| 挂账 | 55→57，新增 L0 M-2 / M-2b 两行 | `docs/任务看板.md` 差量：新增 2 行 `| L0 M-2 / L0 M-2b`，计数改 **57**，依据分别指向复审报告 §3.2/§6-M-2 与 §6-M-2b；R-150 行同步改「已补 8 例」 | ✔ 措辞与落点正确 |
| 红测留痕 | §9.2 三行 | I-1 `2 failed/6 passed`、M-1 `1 failed/3 passed`（挂死形态说明）、N-2 `1 failed` —— 与我上轮指出的缺陷形态一一对应 | ✔ |

### 2.3 门禁（本轮自跑）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run`（无 `--reporter=basic`） | **512/512 · 26 文件**（14.68s，exit 0） |
| 后端定向 | `<MAVEN_HOME>/mvn.cmd -o -Dtest='ConfirmGateResolvedSkipTest,ConfirmGateRequestEmittedTest' test` | **8/8**（ResolvedSkip 4 + RequestEmitted 4，0F/0E，surefire mtime 00:46） |
| 前端类型 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |

与施工自报（586/586 · 93、512/512 · 26、typecheck 0）一致；全量后端数字按其自报采信（本轮按分派卡只做定向）。

## 3 残余边界（不阻塞，如实登记）

1. **无卡挂流仍受既有 SSE 看门狗约束**：`DEFAULT_IDLE_TIMEOUT_MS = 90s`（`api/sse.ts:22`）× `DEFAULT_MAX_RECONNECTS = 2`（`:25`），此路径的 `awaitsInput` 为 false（本连接未见过请求帧）⇒ 静默会退避重连；工具执行静默超过 ≈4.5 分钟时最终 `settle(false, …)` 会给出「连接中断，结果可能不完整」。该语义对**任何**长工具都成立（非本批引入），但 I-1 让它第一次落在「无卡」现场；可随 B1 M-9 / M-3 一并收。
2. **`marked == null`（条目 TTL 消失）仍会发帧** ⇒ 死卡（前端有卡、Redis 无条目、`await` 循环永远读不到）。上一轮已注为既有边界，本批未动，未登记 —— 建议随手归入 B1 M 族。
3. **旧挂起轮（M-2b）现在会额外常驻一条无帧连接**：`req` 永为 null ⇒ 永不建卡，但 I-1 会挂流（§9.3 已披露「B1 M-3 覆盖面扩大」）。与挂账 M-2b 交叉，建议 M-2b 处置时一并裁。

## 4 问题清单（全 N）

- **N-1**：I-1 的「末条助手消息 id」分支在生产是**唯一**行长路径（`loadHistory` 建出的助手消息一律 `done: true`，`ai.ts:1140`；镜像 `:964` 亦同），而两个新用例的 `messages` 均为空 ⇒ 只覆盖了「惰性创建」分支，生产分支无用例。分支本身只有 2 行、风险低；建议补一例「已有 done 助手消息 → 帧落在该消息且不新建」以锁宿主取舍。
- **N-2**：§9.1 两处行号略宽（`1369-1392` vs `1369-1390`；`consumeDecided :467-482` vs `:467-477`），不影响可核性。
- **N-3**：`resumeSnapshotStream` 的「省略宿主」与 `handleFrame` 的惰性宿主之间只靠注释约定（`:1395-1397`、`:1412-1413`），没有类型层面的「无宿主」标记（`string | undefined` 与「传空字符串」不可区分）。当前无调用方传空串，属防御性建议：可加 `hostMessageId?: string | null` 的显式 `null` 语义或断言。

## 5 结论口径

对 L0 批：**无 C / 无 I / 无 M**，3 条 N 可随下批或 B1 族顺手处理；**准予入库**。
