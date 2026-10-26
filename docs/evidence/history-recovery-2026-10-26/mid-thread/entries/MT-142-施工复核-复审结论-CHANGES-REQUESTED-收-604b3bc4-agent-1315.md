<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-142 · 施工复核 · 复审结论：**CHANGES-REQUESTED（收口面很窄：1 项与裁决冲突、1 项验收用例会假红）**（604b3bc4 / agent-1315）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`docs/任务看板`、`parts.ts`、`RunStore`、`SpToolCallingManager`。
> **稿态**：历史中间稿 —— 该 wire 第 `m156` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt4` 条有正文的 assistant 消息（共 11 条有正文），末条有正文消息为 `m392`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1315`（`main` = 主会话）；原字符 11,690（链内 1 段、合计 11,690）。
> **链号 / 类别**：`MT-142` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 8 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `11%`。
> **与末条归档（138 件）的关系**：末条 `m392`（12,168 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 复审结论：**CHANGES-REQUESTED（收口面很窄：1 项与裁决冲突、1 项验收用例会假红）**

审查对象 = 工作树相对 HEAD（`d8056d5`）的全部未提交改动。只读复核：未改文件、未起服务、未提交。

### 复跑证据（实测）

| 套件 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/apache-maven-3.9.16/bin/mvn -q -o test` | **EXIT=0，766 tests / 0 failures / 0 errors / 0 skipped**（123 个 test set，约 155s） |
| 前端 #1 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **EXIT=1，12 failed / 501 passed（513）** |
| 前端 #1 失败定位 | 全部集中在 `tests/ai-panel-parts-render.spec.ts`（mermaid 闭合围栏 / 代码块复制绿勾 / 预览·源码 tab / 全屏查看器族） | — |
| 前端 #1 隔离复跑 | `… vitest run tests/ai-panel-parts-render.spec.ts` | **187/187 通过**（EXIT=0） |
| 前端 #2 全量 | 同 #1 | **513/513 通过**（EXIT=0） |

- 前端这 12 例 = `docs/任务看板.md` 已登记的既有 flaky 族（同文件同族 12 例），与本批 `parts.ts` 改动（仅 blocked 文案映射）无关 ⇒ **非本批回归**；但「513/513」只在重跑时复现，首次全量会红，自报口径应写成「flaky，重跑绿」。
- **数字订正**：后端自报 776 与实测 **766** 不符（−10）。交叉核验：`git grep -c @Test HEAD` = 709 → 工作树 783（**+74**），与逐类统计（新类 8+10+4+5+8+5+5+9+3+10+6 = 73，外加 RunStoreClaimFenceTest +1）完全吻合 ⇒ 766 才是真值；776 属自报笔误。

---

## 一、审查要点逐条结论

### a. `executeOnce` 重排后的次序 —— **通过**
`SpToolCallingManager.java:648-670`：对账（`ledgerOf`/`replayLedger`）→ F2 目标校验 → 资源锁 → `claimAndExecute`（claim → 执行 → 台账）→ `finally { locks.close() }`（`:669`）。次序与裁决 1/4/8 一致；对账与目标校验都在取锁之前（无副作用、无锁开销）。
- 「撞锁不占 claim」有确定性实证：`ToolResourceLockGateTest:238`（预置他人任务锁 → 断言 `claimedBy==null`、`ledger` 空、解锁后重跑同一 toolCallId 可执行）。
- 锁释放完备性：`finally` 包住 claim 之后的整段（含 `settleBlocked` 的 STALE_FENCE/LOST 分支），`LockHandle.close()` 逐把 try/catch；异常路径有锁：`locksAreReleasedInFinallyEvenWhenToolThrows`（`:289`，断言两把键都消失 + 续租任务停）。**唯一不可覆盖的路径是 JVM 被杀**——那恰好是 I-1（续租）要讨论的地方。

### b. 双粒度固定顺序与取/放对称 —— **通过（有 1 处测试缺口）**
`ToolResourceLocks.acquire`：先任务后作业、抢不到即放弃（不等待）；**被 job 锁拒时确实归还已创建的任务锁**（`:152-156`），`release` 反向（先作业后任务）且只放 `created` 清单里的（重入无释放权，`reentrantAcquireHasNoReleaseRight` 锁住）。
- 缺口：**没有任何单测预置「只占 job 锁」**。S1/S2 都是先撞任务锁（`:211`、`:240`），所以 `:152-156` 这条「任务锁已到手、job 锁被拒 → 就地归还」的分支没有直接用例；目前只有 harness H3 变体隐式覆盖（预置 job 锁 → 第二轮若任务锁泄漏就会 blocked）。→ **Minor M-3**。

### c. `markDriving` 续租 vs 裁决「不续租」 —— **应摘（不是「无危害可留」）**
- 代码真的在续租：`ToolResourceLocks.java:147`/`:162` → `drive()` → `LeaseService.markDriving(lease)`（无存活判据的 ALWAYS_ALIVE 口径），`LeaseResourceTtlTest:97` 还把「续租越过原截止时刻」写成了断言。
- 与裁决直接冲突：设计稿 §2.3（本批刚回写）明写「TTL 60s 仅作临界区上界、**不续租**；跨实例『作业在途』互斥由共享库 `inFlightConflict` 承接（**不依赖租约续期存活**）」；`AiProperties.java:358` 却写「由使用方续租」——同一批里两份口径打架。
- 三条技术理由支持「摘」：
  1. **理由本身不成立**：类注说「一次导入/发布可能超过 resource-ttl（60s）」，但临界区只包 `acquire → createAndStart → release`，**不包作业执行**；作业时长由 `inFlightConflict` + 作业看门狗承接。正常路径临界区远小于一个续租间隔（10s），续租任务**永远不会触发**，收益为零。
  2. **它拿掉了 TTL 的自愈性**：这把锁的唯一自愈手段就是「持有者没了 → 60s 后自清」。一旦 `close()` 因任何意外没跑到（当前 `finally` 是严密的，但这是防御纵深），续租会把业务锁**永久**续下去，该 taskId 之后的导入/发布全部 `RESOURCE_LOCKED` 且现场只有一条 blocked 帧 —— 与批② I7 修掉的僵尸续租同一类。
  3. **TTL 口径被悄悄改小**：`renewTick` 用的是**续跑权 TTL**（`LeaseService.java:517` `renew(lease, properties.getLease().getTtl())` = 30s），而锁是按 `resourceTtl`（60s）创建的 ⇒ 第一次续租（10s）后锁 TTL 从 60s 掉到 30s，`resource-ttl=60s` 的语义只剩前 10 秒。
- 修法（3 处删除 + 1 个测试删除）：删 `ToolResourceLocks.drive()` 及 `:147`、`:162` 两个调用；删 `LeaseResourceTtlTest:97` 这一例；把 `AiProperties.java:358` 的「由使用方续租」改成「不续租，临界区上界」。若指挥官改判「保留续租」，则必须同时改设计稿 §2.3 与裁决 1，并让 `renewTick` 用租约自带的 TTL（否则 60s 名不副实）—— 两条路都要求改，现状是「代码续租 + 文档说不续租 + TTL 被改小」，三者互不相容。

### d. IDEM_PUSH 原子性与「脚本正文契约」 —— **基本通过（正文断言漏了一条）**
- 原子性成立：`index = LLEN` → `RPUSH` → `HSETNX` → 双 `PEXPIRE` 全在一条 Lua 内；下标在**推入前**用 `LLEN` 现取，单线程 Redis 内不可能被并发插队 ⇒ 下标与记录一一对应，`listIndex` 与并发追加无竞争。
- 替身能力边界处理得对：`FakeLeaseRedis` 按标记分派（语义复刻），所以 `RunStoreIdempotencyIndexTest:104-113` 另做了**正文契约**断言（`IDEM_PUSH`、`'RPUSH', KEYS[1]`、`'HSETNX', KEYS[2]`、双 `PEXPIRE`、KEYS 顺序）+ 「脚本外不得再有第二条 RPUSH/HSET」（`templatePushes`/`templateHashPuts`）—— 这正是「批③a 探针 C」的教训。
- **漏一条**：正文断言没有钉 `'LLEN', KEYS[1]`，也没有钉「LLEN 在 RPUSH 之前」。替身把顺序硬编码成正确顺序，所以「把 LLEN 挪到 RPUSH 之后」这种改写会**全绿**，而线上会得到 off-by-one 的下标 ⇒ 索引指向**下一条**记录。→ **Minor M-2**（补一行 `.contains("'LLEN', KEYS[1]")` + 一条 `indexOf` 次序断言即可）。

### e. `idemFor` 的 LINDEX 取值与错位风险 —— **通过（1 处加固建议）**
- 台账是**只追加、从不裁剪**的（全仓只有 `eventsKey` 做 `trim`，`RunStore.java:1317`；台账无 LTRIM/leftPop）⇒ 绝对下标稳定，索引长期有效。
- 三个回落路径都正确（索引未命中 → 扫描；命中但 `recordAt` 取不回 → 扫描；JSON 坏 → 扫描），且「索引坏不能把已执行报成没执行」有专门用例（`aBrokenIndexFallsBackToTheScan`）。
- **加固点（Minor M-1）**：`RunStore.java:1175` 的 `return record;` 不校验该记录的 `toolCallId`。一旦索引与台账轻微错位（外部只删了台账键、镜像/回档、将来加分片重排），会把**别人的结果**当成本调用的结果重放 —— 恰恰是幂等索引要防的错误。两行修法：`if (record != null && toolCallId.equals(String.valueOf(record.get("toolCallId")))) return record;` 否则回落扫描。

### f. UNKNOWN 三跳与钩子触发面 —— **通过**
`ToolUnknownReconciler` 与 U1–U5 一一对应：台账命中非 unknown → 采用（含 code/executedBy/原文）；台账命中 unknown → 保持；miss+他人认领 → 保持；miss+无认领（有/无资源锁）→ 保持 + 不同文案；FRONTEND 直接返回 null。`adopted()` 只在「台账命中且非 unknown」为真，不会把不确定性猜成成功。
- 旧 legacyLedger 把 unknown 当成功的问题已纠正：`replayLedger` 用 `ToolOutcome.fromWire`（未识别值 → UNKNOWN，不再回落 OK），只有**缺 `outcome` 键**才按历史兼容当成功。
- 钩子条件 `pending.resolved() && outcome==UNKNOWN && kind!=FRONTEND`（`SpToolCallingManager.java:919-922`）覆盖了所有「已终态且未知」的形态；`EXECUTED && !executed` 走 `shouldExecute` 先补执行（claim 兜底），不受钩子影响；BLOCKED/REJECTED 等 outcome 非 unknown，不会误入。**未发现漏面。**
- 唯一口径小瑕疵（Nit）：同一份**旧台账**（无 `outcome`）在 `replayLedger` 里按成功、在 `reconciler` 里按 UNKNOWN，两处对「旧数据」的解释不同 —— 有各自理由（一处是历史重放兼容，一处是对账保守），但建议在 reconciler 的类注里点明这层差异，免得下一位读者以为其中一处是 bug。

### g. STALE_FENCE 复用 `TOOL_ALREADY_CLAIMED` + 前端文案 —— **通过（残留 UX 语义偏差，可留）**
- 后端：`STALE_FENCE` 走 `settleBlocked(..., CODE_ALREADY_CLAIMED, "本轮写回权已转移…")` ⇒ 帧 `status=BLOCKED / outcome=blocked / executed=false / retryable=false`（`BLOCKED` 只有 `RESOURCE_LOCKED` 才 retryable，正确）。
- 前端：`parts.ts:576-582` 三支映射（`TOOL_ALREADY_CLAIMED` / `RESOURCE_LOCKED` / `TARGET_INVALID`）与后端码**逐字匹配**；STALE_FENCE 落在第一支，卡面短文案是「该操作已由另一实例执行，本次未重复执行」，**准确文案在 `result` 里**（"本轮写回权已转移…fence 已推进到 N"）。措辞略偏（是"写回权转移"不是"另一实例执行"），但方向不错、详情可见，且这是裁决采纳的「复用码」自决 —— **可留**，如需彻底分开再引入独立码即可（additive）。

### h. 断言只升不降 —— **通过**
既有测试文件改动共 5 个，逐行核完：
| 文件 | 改动 | 判定 |
|---|---|---|
| `InMemoryRunStore`（fixture） | 新增 `idemIndex`、`seedHeldRunEpoch`/`seedFence` 语料口、`tryClaimExecution` 三态（补齐 I5 fence 复核，与真实实现同口径）、`deleteRun` 清 idem | **零放宽**；`claimExecution` 转发到三态口，语义不变 |
| `FakeLeaseRedis`（fixture） | 新增 LIST 面、可控时钟 `advanceTime/nowMs`、`IDEM_PUSH` 语义 + 正文捕获、`scriptPushes/templatePushes/templateHashPuts/listRangeCalls/listIndexCalls` | **纯增量**，既有计数/语义未动 |
| `RunStoreClaimFenceTest` | +1 例 `claimOutcomeDistinguishesWonLostAndStaleFence`（WON/LOST/STALE_FENCE 三态 + fence 证据 + 布尔旧口两种失败均为 false） | **只升** |
| `ToolResultEnvelopeTest` | 同一用例内 +2 断言（`RESOURCE_LOCKED⇒retryable=true`、`TARGET_INVALID⇒false`），原 4 条断言原样 | **只升** |
| `tool-outcome-envelope.spec.ts` | +1 例（两支新文案 + status 不被降级 + `outcomeStatus('blocked')` 不变） | **只升** |

后端另有 11 个新测试类（含 `LeaseResourceTtlTest` 1 例 = I-1 要删的那例）。前端无既有断言删除。

### i. 前端改动纯 additive + 帧契约白名单 —— **通过**
`parts.ts` 只新增两个 `code` 分支（既有两支逐字不变）；`frame.code` 在前端无联合类型约束（typecheck 0，实跑未见类型错误）；仓内 conformance 白名单（`FrameContract`）只约束**帧类型/seq/终帧**，不枚举 `code` 值 ⇒ 新码值无影响。

### j. harness 批③用例字段一致性 —— **字段全对，断言设计有 1 处会假红**
字段逐条核过，全部与实现一致：
- `tool_result` 帧：`code/status/outcome/executed/retryable` 均由 `SseChatEmitter.toolResult` 真实发出（`:740-755`），`retryable` 由 `outcome.retryable(code)` 派生。
- `GET /api/tasks/{id}/jobs` → `ApiResponse<List<Job>>`（`JobController:34`）⇒ `.data` 是数组，`.Count` 成立。
- `/api/ai/runs/{runId}` → `ResumeService.describe` 含 `ledgerCounts`/`ledger`/`keys`；`ledgerCounts` 按工具名记 ⇒ `ledgerCounts.start_export` 成立。
- 幂等键形：`ai:idem:<runId>` + field `<runId>:<toolCallId>` 与 `RunStore.idemField` 一致 ✔。
- `describe.keys.lease` 存在（批②已加）✔；H3 预置值 `1|other-instance` 的 owner 与执行侧 owner（`instanceId:runId:toolCallId`）必然不同 ⇒ 必定 `HELD_ELSEWHERE` ✔（确定性用例，选得好）。
- **问题**：`-SharedDb` 变体（`:951/:957/:963`）要求「恰一个 RESOURCE_LOCKED + 恰一个真实执行」。两轮批准虽用同一发令时刻，但两侧临界区只有 `createAndStart` 那么长（毫秒级），**两段临界区完全可能不重叠** ⇒ 两轮都执行、`RESOURCE_LOCKED` 计数 0 ⇒ 用例红，而系统行为完全正确。→ **Important I-2**（验收证据的可信度问题）。

---

## 二、分级清单

### Important
**I-1 资源锁续租与裁决「不续租」冲突，且理由不成立 —— 建议摘**
- 位置：`backend/.../gate/ToolResourceLocks.java:46-47,147,162,224-231`；`backend/.../config/AiProperties.java:358`；`backend/src/test/.../run/LeaseResourceTtlTest.java:97`；对照 `docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md:34`（「不续租」）。
- 问题：见上（c）。要点：正常路径续租永不触发（无收益）／拿掉 TTL 自愈（可能永久锁死 taskId）／`renewTick` 用 30s 把 60s 悄悄改小／同批文档说不续租。
- 修法：删 `drive()` + 两个调用点 + `LeaseResourceTtlTest:97`，并把 `AiProperties` 那句改成「不续租」。若保留，须同时改裁决/设计稿，并让 `markDriving` 记住租约自身 TTL（`Lease` 加字段），否则 `resource-ttl` 不可信。
- 依据：裁决 1（资源租约 = 临界区持有、TTL 60s 不续租）+ 设计稿 §2.3。

**I-2 `-SharedDb` 资源锁用例存在固有竞态，可在无缺陷时假红**
- 位置：`scripts/b23l1-dual-instance.ps1:941-964`（`:951` 恰一个 RESOURCE_LOCKED、`:957` 恰一个执行、`:963` 台账合计 1）。
- 问题：两段临界区可能不重叠 ⇒ 两轮都成功。当前断言把它当 FAIL。
- 修法：① 循环重试该对（如 3 次，任一命中即过，全未命中再 FAIL 并注明"竞态窗口未命中"）；或 ② 把「两轮都执行」判为 SKIP 且打印原因（明确"本轮未构造出竞态"），保留严格断言只在命中时生效；③ 若允许，给出一个"延长临界区"的测试开关做确定性构造（不推荐改生产代码）。H3 预置变体已经是确定性版本，可把它列为默认必跑、SharedDb 变体降为可选。
- 依据：验收口径 §5-2 第 2 条（资源互斥：同 taskId 双 start_export 跨实例并发 → 恰一个 blocked/RESOURCE_LOCKED）—— 用例必须能稳定证明这一条，否则证据不可信。

### Minor
- **M-1 `idemFor` 命中索引时不校验记录身份**：`RunStore.java:1175`。索引/台账一旦错位就重放别人的结果。修法：比对 `record.get("toolCallId")`，不符即回落 `ledgerFor`。依据：裁决 6「idemFor O(1) + fallback ledgerFor」。
- **M-2 脚本正文契约漏 `LLEN` 与其次序**：`RunStoreIdempotencyIndexTest.java:106-113`。修法：补 `.contains("'LLEN', KEYS[1]")` + `assertThat(body.indexOf("'LLEN'")).isLessThan(body.indexOf("'RPUSH'"))`。依据：裁决 6（`IDEM_PUSH` 单 Lua 原子）。
- **M-3 「job 锁被拒 → 归还 task 锁」无单测**：`ToolResourceLocks.java:152-156`。修法：新增一例，只预置 `LeaseKeys.job(taskId,"IMPORT")`，断言 blocked + `LeaseKeys.task(taskId)` 已消失。依据：裁决 7 的取/放对称。
- **M-4 `claimAndExecute` 重复认领**：`SpToolCallingManager.java:682-684`。布尔口本身就是三态口的封装，失败后**再调一次**三态口 ⇒ 竞争路径上 2 次 `HSETNX` + 2 次 fence `GET`。修法：首次就用 `tryClaimExecution`，用 `won()` 判成功。依据：裁决 5（三态），兼容只需保留布尔方法本身，不要求调用方先走布尔口。

### Nit
- **N-1** `RunStore.fenceEpoch`（`:1012`）读失败仍 fail-open 且只记 DEBUG —— 那一笔执行实际跳过了 stale-fence 复核，建议 WARN（沿用批②复审 R7，本批仍未采纳）。
- **N-2** `appendLedger` 在脚本后又补一次 `EXPIRE ledger`（`RunStore.java:1107`）：脚本内已有双 PEXPIRE，这次纯为"滑动 TTL 可观测"的额外往返；每次执行 +1 RTT，可考虑改为断言脚本参数。
- **N-3** `LeaseService.stopDriving(String key)` 每次释放都要遍历全部 driving 键做前缀匹配（`:466-480`）—— 资源锁让这条路径变热（每次带锁工具调用 + 对账探针），量级仍小，记录。
- **N-4** 旧台账「无 outcome 键」在 `replayLedger`（按成功）与 `ToolUnknownReconciler`（按 UNKNOWN）口径不同，建议在 reconciler 类注点明。
- **N-5** `describeKeys` 给 `lockTask`/`lockJob` 塞 null 值（`ResumeService.java:230-246`），已用 `LinkedHashMap` 规避 `Map.of` 的 NPE；下游需容忍 null（harness 只读 `keys.lease`，无影响）。
- **N-6** 自报数字：后端 **766**（非 776）；前端「513/513」只在重跑时成立（首次全量 12 例既有 flake）。请按实测口径改写报告。
- **N-7** TARGET_INVALID 只做**执行前**（审批后）校验：taskId 不存在的卡仍会先弹给用户、批了才被 blocked。实际影响有限（F5 预检在发卡前就判 "任务不存在"），但恢复路径（resume）不跑预检时才由 resolver 兜住 —— 属设计取舍，记录。

---

## 三、文档线（只查事实性错误）

- `docs/research/README.md`：索引 33→36 件、编号勘误说明、`本目录除 7 件 raw 稿与 README 外的全部文件` —— **核过**：目录 44 个 .md = 7 raw + README + 36 索引件 ✔。
- `docs/research/设计-B23L1…md`：§2.3 改写为「临界区持有 / 不续租 / `inFlightConflict` 承接」—— 与裁决一致 ✔，**但与代码的续租实现冲突**（见 I-1，问题记在代码侧）；`inFlightConflict` 确实存在于 `JobService`/`ToolPreflightService` ✔；§7 批② ✅ / 批③ 🔧 与 HEAD 提交链一致 ✔。
- 三份新设计稿（上下文统计与压缩 / 模型配置 / i18n 与工具可配置）+ 索引行：**未发现事实性错误**（抽核了与既有件/裁决的引用方向、串行依赖表述、编号自引订正）。属文档线资产，不构成施工承诺，未做深度技术审。

---

## 四、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

## 五、放行条件（若指挥官采纳）

1. **I-1**：删 `drive()` 及两个调用点 + 删除 `LeaseResourceTtlTest:97` + 改 `AiProperties` 那句（3 删 1 改）；或改判「保留续租」并同步改裁决/设计稿 + 让续租用租约自身 TTL。
2. **I-2**：SharedDb 用例加"重试至命中"或"窗口未命中判 SKIP"，避免假红。
3. M/N 项可随下一批小修（M-1/M-2 各 2 行，建议顺手带上）。

两项落地后即为 **READY**；其余（a/b/d/e/f/g/h/i/j 与全部单测面）已在本次复核中确认成立。
