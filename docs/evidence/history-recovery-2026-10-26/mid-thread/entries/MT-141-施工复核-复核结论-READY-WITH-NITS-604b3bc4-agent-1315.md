<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-141 · 施工复核 · 复核结论：**READY-WITH-NITS**（604b3bc4 / agent-1315）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`RunStore`、`ConfirmGate`、`LeaseService.java`、`InMemoryRunStore.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m105` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 11 条有正文），末条有正文消息为 `m392`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1315`（`main` = 主会话）；原字符 10,625（链内 4 段、合计 28,492）。
> **链号 / 类别**：`MT-141` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 4 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `2%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m392`（12,168 字符）未入末条归档。
> **脱敏**：路径 8 / 会话标识 4 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

### 〔段 1／4 · assistant 消息 m105 · 10,625 字符〕

## 复核结论：**READY-WITH-NITS**

复核对象 = 工作树相对 HEAD（`43d24ad`）的全部未提交改动（11 改 + 18 新文件；`scripts/README.md` 也进了本批）。只读，未改文件、未起服务、未提交。

**复跑证据**：`cd backend && <LOCAL_WORK>/_mvn/apache-maven-3.9.16/bin/mvn -q -o test` → **EXIT=0**，surefire 汇总 **687 tests / 0 failures / 0 errors / 0 skipped**（112 个 test set，报告时间戳全为本次 09:12–09:13）。

> **数字订正**：施工自报「697」与实际不符。672（复审轮基数）+ 15 新例 = **687**；逐类核对：LeaseServiceTriStateTest 4 + RunStoreClaimFenceTest 3 + RunStoreWalConvergeTest 4 + ConfirmGateLeaseTest 10（8+2）+ ConfirmGateDecisionAtomicTest 4（3+1）+ ResumeServiceLeaseTest 8（7+1）= 与 687 完全吻合。697 属自报笔误，建议提交报告里改正。

---

## 一、逐条核验收口（全部真实落地）

| 项 | 结论 | 证据 |
|---|---|---|
| **C1** | **通过，修法正确** | `ConfirmGate.java:494-496` 超时出口先 `handedOff=true` + `handOffLease` 再 `expireFrontend`；`cancel` 出口保持 `:538` 还租。回归锁 `frontendTimeoutHandsOffTheLeaseInsteadOfReleasingIt`（`ConfirmGateLeaseTest.java:340`）不只断言「没删租约/续租已登记」，还**驱动一次 save 并断言 `snapshot.runEpoch==epoch`** —— 这是关键：它证明交棒后写回仍走 fenced 路径，直接封死了我原报告里「还租 ⇒ 退回 plain SET」那一半。`cancelStillReleases...` 把「取消 ≠ 超时」钉住。 |
| a 谁负责最终释放 | **闭合** | 终态 `RunStore.save` → `releaseRunLease` → `releaseIfHeld(key)` 拿的是**当前视图里的那一任**（即交棒时那个 epoch），token 比对命中即 DEL，`release` 内先 `stopDriving`；`LeaseService.shutdown()` 也补了归还。二次兜底 = 续租判据转假。 |
| **I1** | **通过** | `RunStore.mergeableDecisionStatus` 单点（`:724`），读侧 `applyDecisionMarker`（grant-only、只合并 3 种可重建终态）与写侧 `convergeOnDecided`（`:947` 早退）共用同一判据；`RunStoreWalConvergeTest` 用「读后 `pendingRaw` 仍 PENDING」锁住「读不写」。 |
| **I2** | **通过** | 读侧合并在 `pending()`/`pendings()`；写路径全部改 `pendingForWrite`（`pendingRaw` 优先，`ConfirmGate.java:920`），`convergeOnDecided` 也用 `pendingRaw`；补齐后 `maybeTriggerResume(runId,false)`（`:952`）。 |
| **I3** | **通过** | `LEASE_VERIFY` 只读脚本 + `verifyHeld` 三态（HELD/LOST/UNAVAILABLE，LOST 即作废视图）；`ResumeService.resume(…,lease)` 改用它（`:303`）。 |
| **I4** | **通过** | `AcquireOutcome` 三态；`ResumeService`（`:255`）与 `ConfirmGate.triggerResume`（`:1206`）都只对 HELD_ELSEWHERE 拒、对 UNAVAILABLE 走无租约派发。 |
| **I5** | **通过（有 1 处 Nit）** | `claimExecution`：`epoch>0` 才读 fence，`fence>epoch` 拒 + WARN + 不留痕（测试断言 `claimedBy==null`）；`epoch==0` 原样（E3 测试锁住）；`appendLedger` 加 `epoch`（取值顺序 claimedEpoch → heldEpoch）。 |
| **I7** | **部分通过（R2）** | `markDriving(Lease, BooleanSupplier)` + 判据转假即 release（测试锁住「不再往返」）；`ConfirmGate.handOffLease`/`ResumeService` 都带判据，但 **`ConfirmGate.java:1300` 死卡兜底派发路径仍是 `markDriving(lease)` 恒真**。 |
| **M1** | 通过 | `touchActiveRun` 续 `fenceKey`，测试 `RunStoreTouchTest:84` 锁 TTL。 |
| **M2** | 通过（观测面，见 R6） | `warnIfFenceIsAhead` 只 WARN、E3 照写；测试锁「WARN 级 + 仍落库」。 |
| **M3 / M4 / M5 / M8** | 通过 | M3 类注「noeviction 前提」；M4 全仓改名（含调用点/测试/README），仅 `FakeLeaseRedis:94` 一处 `@link` 仍是旧名（R5）；M5 `redis` 字段注释明写保留原因；M8 三个测试类都有「实例面维度 / DC-11」小节。 |
| **M6** | 通过（harness 侧） | `Stop-Child` 先 `Get-Process` 再比 `StartTime`，不符则「PID 已被复用，拒绝误杀」并不杀；杀完从列表移除，finally 不二次杀。`Stop-Children` 批量路径未加同款比对（裁决只点了 Stop-Child，见 R8）。 |
| **I6** | 已承接（待跑） | harness 新增 `suspend-decision` / `suspend-decision-concurrent` / `instance-kill` / `lease-scripts`，`$pendingCases` 只剩批③；`lease-scripts` 用真 RESP 读 `PTTL`（RENEW）、`snapshot.runEpoch>0`（FENCED-SET）、终态后键消失（RELEASE），`Invoke-RedisCommand` 只读不写。 |
| 其余 Nit 四项 | 3/4 落地 | 未用 helper 已删；`shutdown` 归还租约；`healthy` 死字段已删；`GetJson` 仍未 Dispose（R8）。 |

---

## 二、攻击面（父代理点名的五条）

**(a) C1 交棒后谁释放 / 判据何时转假 / 终态 save 是否覆盖该 epoch —— 闭合。**
释放有两条独立路径：① 终态 `save`（覆盖交棒 epoch，`releaseIfHeld` 取的就是它）；② 续租判据转假（快照终态 / 视图不是该 epoch / 读快照异常按活）。「模型继续跑到终态」不会造成永不释放：终态 save 先到，判据只是多余的一拍。判据本身**不会**误拦正常收尾（终态即假、逐拍停）。
**残余**：判据 = 快照非终态 ∧ 仍是主人，**不含「驱动线程还活着」**；`ResumeService` 侧因为有 `resuming` 表（finally 必删）而闭合，`ConfirmGate` 侧只覆盖「终态/视图丢失」。即「线程被强行丢弃且没走到终态 save」仍会僵尸续租 —— 比我原报告窄得多（Java 里异常/Error 都会展开 finally，走终态 FAILED 落库），但没归零。见 R2 一并处理。

**(b) `pendingForWrite`/`pendingRaw` 双轨是否引入新窗口；回落会不会把合并结果写回 —— 不会。**
- 真实 `RunStore.pendingRaw` 只在条目**不存在**时返回 null，`pending()` 同时刻也返回 null，回落分支实际上永不携带合并视图；替身（mock 未桩 `pendingRaw`）走回落，语义与批②b 前一致。
- 读侧合并只改返回值，不改哈希（`RunStoreWalConvergeTest` 用 `pendingRaw` 断言）。写者（submitDecision/submitFrontendResult/dismiss/converge）一律走 raw ⇒ 「自己刚写的 guard 把自己判成已决」这一自覆盖窗口被封死。
- 新增窗口审查：`cancelPendings` 仍不入 guard（既有），但它只在 `status==PENDING` 时写；合并后的视图是「读侧收敛」，与它不冲突。`saveSuspended` 写的是调用方传入的新条目，非读回值。未发现「读侧已决、写侧又覆盖」的新窗口。

**(c) I4 三态与 `leaseService==null` 两降级链语义一致；UNAVAILABLE 下 await 的行为 —— 一致，可接受。**
`acquireRunLease` 在无设施时直接报 **UNAVAILABLE**（不是 HELD_ELSEWHERE），于是 `ResumeService` 的三条路径（旧签名 / 重载 / 触发方）在「无设施」与「设施坏了」上完全同义：放行、走进程内互斥。`await` 用薄包装 `tryAcquire` ⇒ UNAVAILABLE 时 `lease=null`，`handOffLease(null)` 空转，本轮**整段无租约**（save 走 plain SET + `warnIfFenceIsAhead` WARN）。
可接受性：这是有意的可用性优先（「等不到人」比「少一层围栏」更糟），且 `/health.leaseOk=false` 可观测；真正的风险面是「租约脚本坏、其余 Redis 命令好」这种局部故障，属运维可诊断范畴。**结论：通过**，但请在收口报告里把它写成显式取舍（当前只写在代码注释里）。

**(d) I7 判据死锁面 —— 确认无害，但发现同族的另一条更实质的序列（R3）。**
判据「`heldEpoch==lease.epoch`」在 `renewTick` 因续租失败摘视图后会转假，但**摘视图的同一步已经 `stopDriving`**，所以下一拍根本不会再跑 —— 不存在「反复 release 别人的租约」。即便跑到，`release` 是 epoch scoped，Lua 拒删，无害。✔
**但** `release()` 失败分支是 `this.held.remove(key)`（无条件）且入口先 `stopDriving(key)`：若一个**陈旧** lease 的 release 在「视图已换成同进程新一任 E2」之后到达，它会 ① 杀掉 E2 的续租任务、② 把 E2 从视图里删掉 —— 于是本进程**仍真实持有 E2 却认为自己没租**（此后 save 退回 plain SET）。见 R3。

**(e) I5 的 fence 读：性能与失败口径 —— 可接受，两处 Nit。**
性能：只在 `epoch>0`（即续跑/接管驱动路径）多一次 `GET`；单实例/普通轮 epoch==0，零额外开销。热路径影响可接受。
一致性：`fenceEpoch` 读失败 → 返回 0 → **放行**（fail-open，符合 E3 精神），但只记 DEBUG；建议提 WARN（R7），否则「这一次执行没做围栏复核」现场不可见。另有 ms 级 TOCTOU（`GET fence` → `HSETNX` 之间 fence 可能推进），如需闭合把两步合成一条脚本（R7，非阻塞）。

---

## 三、残余问题分级

### Important（建议进下批或本批追加，非阻塞）

**R1 WAL 对 `APPROVED` / `FRONTEND_RESULT` 只剩「卡死」出口（无收敛路径）**
`ConfirmGate.java:947`（不可重建 ⇒ 直接 DUPLICATE，不写）+ `RunStore.java:724`（读侧也不合并）
触发：guard 已写、条目仍 PENDING（写者在此窗口死掉，或 `putPending` 因 Redis 抖动抛异常）。此时：
- 读侧仍 PENDING ⇒ `/runs/current` 照旧给卡、`ResumeService` 报 `PENDING_UNRESOLVED` 不驱动；
- 再点「批准」/「拒绝」/「取消表单」→ 一律 409 `DUPLICATE_TOOL_CALL_ID`（guard 被占且不可合并）；
- 唯一出口 = 整轮取消（`POST /api/ai/cancel/{runId}` → `cancelPendings`，它不入 guard）或等 24h TTL。
即：**WAL 存在的意义（「决策已落、回执未走完」要能收敛）对这两类恒不成立**，卡片级不可恢复。
这与裁决口径一致（我 I1 的修法没要求写 APPROVED），属**新信息下的设计取舍**，建议路由：① `APPROVED` 其实完全可重建（`status=APPROVED, executed=false, outcome=null` 就是 `submitDecision` 写的东西）⇒ 允许 WAL 写；② `FRONTEND_RESULT` 让**重试方用自己的载荷**补齐（它手里就有那份 result），或给「guard 早于 N 秒 + 条目仍 PENDING」一个接管窗口。
测试面：现有 `walDecidedCardIsRepairedAndThenDriven` 只覆盖 REJECTED，APPROVED/FRONTEND_RESULT 的孤儿窗口无锁。

**R2 I7 判据少接一条路径**
`ConfirmGate.java:1300`：死卡兜底派发成功后仍 `markDriving(lease)`（ALWAYS_ALIVE）。这正是「决策落 B、A 已死、B 接管」的主路径；该路径上的僵尸续租仍可能。修法 = 与 `handOffLease` 同形 `markDriving(lease, () -> stillDriving(runId, lease))`（一行），或把 `ResumeService` 的「resuming ∧ 非终态」判据暴露给 gate 复用（更强）。

### Minor

**R3 `release()` 失败分支的无条件 `held.remove` + 入口 `stopDriving`**
`LeaseService.java:317-336`、`:339-342`。序列：verifyHeld 判 LOST → 视图被摘 → 同进程重新 acquire 得到新 epoch E2 → 某条**陈旧** lease 的 `release()` 才到（await finally / `releaseLeaseQuietly`）→ `stopDriving(key)` 杀掉 E2 的续租任务 + `held.remove(key)` 删掉 E2 视图 ⇒ **有效租约被自己的旧调用作废**（此后写回 plain SET、`verifyHeld` 仍会通过，下次 acquire 会自愈）。修法：`if (this.held.remove(lease.key(), lease)) stopDriving(...)`，失败分支改条件删除（同样思路适用 `verifyHeld` 的摘视图）。

**R4 `InMemoryRunStore` 的读侧合并在默认模式下「就地写回」**
`InMemoryRunStore.java:410-440`：`deepCopyPending=false`（默认）时 `applyDecisionMarker` 直接改表里那份对象，违反它自己在真实现里被测试锁住的「读不写」不变量。当前无测试依赖它（R4/WAL 用例都用了 `new InMemoryRunStore(true)`），但**未来**用例可能因此走到「条目已被合并成已决 ⇒ `pendingRaw` 也看到已决 ⇒ WAL 补齐分支不被执行」的假绿。同处还缺 `resolvedAtMs`/REJECTED 文案的重建，且 `claimExecution` 未镜像 I5 的 fence 拒绝。修法：合并前复制（有 guard 时 copy-on-write），补齐 `resolvedAtMs/resultText`。

**R5 三处注释/链接仍在描述旧行为**（正是本仓「修完扫注释」的硬要求）
- `ConfirmGate.java:536-537`：「取消 / 中断 / 前端超时都是『本轮不驱动』」—— C1 已把前端超时改成交棒，紧随其后的代码与之矛盾；
- `ResumeService.java:292`、`:296`（方法 javadoc）与 `ResumeServiceLeaseTest.java:41`：仍写「归属校验 = `heldRunEpoch == lease.epoch()`」，实际已是 `verifyHeld`（对着 Redis 真值）；
- `FakeLeaseRedis.java:94`：`{@link RunStore#touchSuspendedRun(RunSnapshot)}` 已改名，链接断（不影响编译）。

**R6 M2 的旁路观测给 E3 写路径加了固定成本**
`RunStore.java:294`（每次 plain SET 前一次 `GET fence`）+ `:323-336`（`fence` 有值即 WARN）。单实例下每次 save 都多一次往返；一旦某轮被别实例推进过 fence，本进程**每一笔**写回都会 WARN（无节流）。建议：按 runId 节流（首次 WARN、其后 DEBUG），或仅在「本进程曾持该轮租约」时观测。

### Nit

- **R7** `claimExecution` 的 fence 复核与 `HSETNX` 之间仍有 ms 级 TOCTOU（如要闭合，合成一条脚本）；`fenceEpoch` 读失败 fail-open 建议 WARN 而非 DEBUG。
- **R8** `GetJson` 仍未 Dispose（Nit 清单第三项只落了 `PostJson`/`Read-Sse`）；`Stop-Children` 批量路径未用 `StartTime` 比对（裁决只点 `Stop-Child`，同族不一致）。
- **R9** `markDriving` 注册非原子（`containsKey` → `schedule` → `put`），并发两拍可登记两个续租任务（后者覆盖句柄，前者成孤儿；下次 release 自愈）。建议 `computeIfAbsent`。
- **R10** `pending()` 现在单次 = 3 次往返（entry + req + decided）；等待循环 2s 一次，量级可接受，记录一下。
- 自报数字「697」应为 **687**（见文首）。

---

## 四、断言只升不降（终核）

`git diff HEAD -- backend/src/test/` 只有两个**已跟踪**文件：`InMemoryRunStore.java`（fixture）与 `AiControllerHealthSseDeliveryTest.java`（+1 用例 + helper 参数化，与复审轮一致，无删断言）。其余测试类均为 untracked 新文件，我按**复审轮全文**逐类比对行数与断言：

| 测试类 | 轮次对比 | 判定 |
|---|---|---|
| LeaseServiceTest 200→200、RunStoreFencedWriteTest 191→191、RunStoreRequestEmittedAtomicTest 132→132、RunStoreClaimEpochTest 99→99、RunStoreDecisionGuardTest 88→88、RunSnapshotEpochCompatTest 46→46、AiControllerSuspendedTouchTest 185→185 | 逐行同文（仅 `touchSuspendedRun→touchActiveRun` 改名） | **只升（零变化）** |
| RunStoreTouchTest 113→117 | 新增 `fenceKey` TTL 断言（M1） | **只升** |
| ConfirmGateLeaseTest 408→474 | G1–G8 **逐条同文**（含 G1 的 `scriptDeletes==0`/`activeCount==1`、G3 的 outcome/重试/epoch、G4/G7 的「严格更大 epoch」），新增 C1×2 | **只升** |
| ConfirmGateDecisionAtomicTest 251→251+ | 原 3 例同文，新增 I2 例（`verify(resumeService, times(1)).resume` 比原「≤1」更强） | **只升** |
| ResumeServiceLeaseTest 253→293 | **唯一的断言替换**：`overloadValidates...` 由「`scriptCalls` 完全不变」改为「`verifyCalls==1 ∧ scriptCalls==before+1 ∧ sets 不变 ∧ deletes==0`」 | **判定：可接受且更强**。旧断言（零往返）已被 I3 设计否证（校验必须打一次 Redis）；新断言仍完整禁止「自抢/自还」，并新增「只校验一次」这一正向约束，断言数 4→5。 |

**结论：无断言删除、无降格。** 施工自报「两处替换」，我只能定位到一处（上述 `scriptCalls→verifyCalls`）；另一处我未发现（若指 `RunStoreTouchTest` 或改名类，那属新增/改名，不是替换）。若确有第二处，请在提交报告里点名，我复核轮再看。

---

## 五、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

## 六、未验证 / 待办

- **harness 未实跑**（禁起服务）：`suspend-decision` / `suspend-decision-concurrent` / `instance-kill` / `lease-scripts` 的断言我已逐条读过并被实现真有价值（不是自证：并发用例用同一墙钟发令枪 + 断言 200/409 各一 + `confirm_decision`/`tool_start` 各 1 帧；`lease-scripts` 用真 RESP 读 PTTL/键存在性）。真 Redis 覆盖仍以「harness 运行核验」为准（正在跑）。
- **E2E 31 用例 / 前端 typecheck·build / check-docs / 脱敏** 未跑（无前端改动，且禁起服务）。
- `data/files/*.xlsx` 仍是未跟踪文件（M9 已裁：本批不动，提交官按显式路径提交 —— 复核确认该风险仍在工作树里，需提交时避开）。
- 任务看板 `:389`/`:402` 两行未在本批 diff 内更新（收口手续）。

---

### 〔段 2／4 · assistant 消息 m184 · 6,480 字符〕

## 收口复核结论：**READY-WITH-NITS**

（所有 CHANGES-REQUESTED 项均已真实关闭；剩余项是已登记跟踪的 Nit + 两处口径纠正，不阻塞提交。）

只读复核：未改文件、未起服务、未提交。审查对象 = 工作树相对 `d8056d5` 的全部未提交改动。

### 复跑证据（实测，非 -q）

```
[INFO] Tests run: 778, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 前端本轮**未复跑**：`frontend/` 改动与上一轮逐字相同（`parts.ts` +7/−1、`tool-outcome-envelope.spec.ts` +42/−0），收口施工未触及前端；上一轮已验（全量首跑 12 例既有 flake，隔离 187/187、全量重跑 513/513）。typecheck 未重跑（无 TS 改动）。

---

## 一、766 vs 778 仲裁 —— **778 为准，且我要更正自己前两轮的计数错误**

结论：**施工方报的 776/778 一直是对的；我前两轮报的 687/766 少算了 10 条。**

根因（已定位到具体类）：`backend/src/test/java/com/example/configmgr/ai/gate/GenerativeFormGateTest.java` 用 `@Nested` 拆成 `$SubmissionGate`(8) + `$SuspensionGate`(2) = **10 条**。Surefire 对这个类是：
- `GenerativeFormGateTest.txt` 写的是 **`Tests run: 0`**（10 条全在嵌套类里），
- 嵌套类**没有单独生成 `.txt`**（只有外层 `TEST-….xml` 里有 10 个 `<testcase>`），
- 而汇总行 `Tests run: 778` 把这 10 条算进去了。

证据链：`ls *.txt` = 123 个，`cat *.txt | grep "^Tests run"` 求和 = **768**；`grep "^\[INFO\] Tests run:"` 逐类求和 = 778；差值 10 = `GenerativeFormGateTest` 的嵌套用例。同一口径回推也吻合历史：第 2 轮汇总 697 / 我报 687、第 3 轮汇总 776 / 我报 766 —— 差值恒为 10。

**结论**：① 以 **778** 为准（本轮真值）；② 我上一轮"776 是笔误、应为 766"的判断**是错的**，776 是当时的汇总真值；③ 本轮相对上轮 **+2**（−1 I-1 删例 +1 M-1 +2 M-3），与逐类计数完全一致；④ 今后报数口径统一写"surefire 汇总行"，不要用 report 文件求和（会漏 `@Nested`）。

---

## 二、逐条核验

### I-1 资源锁续租 —— **已摘，干净**
- `grep -rn "markDriving(" backend/src/main/java` ⇒ 只剩 `ConfirmGate.java:573/591`（续跑权，两处均为带存活判据的 `markDrivingGuarded`）与 `ResumeService.java:336`（续跑权）。**gate 包里已无资源锁续租路径**。
- `ToolResourceLocks.java:46-50` 类注改为「**本层不续租**：resource-ttl 是临界区上界，动作真超上界宁可让锁按 TTL 放开……」；`AiProperties.java:357` 改为「不续租，TTL 仅作临界区上界」、`:378` 同步。
- `LeaseResourceTtlTest` 5→4（删掉 `renewKeepsTheLockAlivePastTheOriginalDeadline`），保留的 4 例仍锁「TTL 到点 ⇒ 键自清 ⇒ 新 owner 接管且 epoch 更大」——正是"不续租"该有的性质。
- **反加回归锁到位**：`ToolResourceLockGateTest:164` 持锁期间 `renew.activeCount()==0`、`:304` 释放后仍为 0。
- 全仓已无「资源锁续租」的残留文字（grep `resource-ttl` / `锁.*续租`：只余上述正确表述与批②续跑权 TTL 的说明）。

### I-2 共享库竞态用例 —— **已关闭（自报未列，但代码/README 都在）**
`Invoke-ResourceLockRace`（ps1:860-871）：最多 3 轮，任一命中即 **PASS**，3 轮全 miss ⇒ **SKIP** 且打印每轮形态（`locked=N[...] executed=M ledgerSum=… jobs=…`）；README 把判据与理由写清（"行为可能完全正确，跨实例证据以 H3 确定性变体为准"）。另外新增一条我没要求的硬守卫（ps1:1318-1323）：`-SharedDb` 与 `instance-kill`/`lease-scripts` 同棒直接 FAIL（杀 A = 杀 H2 AUTO_SERVER 服务端，B 会半路断连），要求分两棒 —— 这是实跑才会暴露的坑，补得对。
- 残留风险（可接受）：3/3 miss 判 SKIP，理论上把一个**真**锁故障也显示成 SKIP。缓解：默认用例表里 H3 确定性变体必跑且会 FAIL；每轮形态也打印出来。建议运维口径写死「默认用例表整体跑，不允许只跑 `-SharedDb resource-lock`」。

### M-1 `idemFor` 身份校验 —— **已落地，比要求更完整**
`RunStore.java:1183-1194`：命中后 `matchesToolCall(record, toolCallId)` 才返回；错位时 **WARN + 回落 `ledgerFor`**（`recordAt==null` 仍走 debug 回落）。新例 `anIndexPointingAtAnotherCallsRecordFallsBackToTheScan`（:171-186）同时断言「c-2 拿到自己的结果」+「c-1 的索引不受影响」+「回落走一次扫描」——把"谁的产物回给谁"钉住了。

### M-2 脚本正文契约 —— **已落地**
`RunStoreIdempotencyIndexTest:109` 补 `'LLEN', KEYS[1]`；`:116-118` 补 `indexOf("'LLEN'") < indexOf("'RPUSH'")` 次序断言（正是替身无法覆盖的那一维）。

### M-3 处置忠实度 —— **判定：忠实，且比我原措辞更强；原措辞是我的错**
我上轮写的是「预置 `LeaseKeys.job(taskId,"IMPORT")` → **start_export** 被 blocked」——这在双粒度设计下**自相矛盾**（export 的作业级锁是 `:EXPORT`，不该被 `:IMPORT` 挡）。施工方指出的冲突成立，处置正确：
- 主用例 `jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook`（:366-388）：预置 `job:IMPORT` 空出 task 锁 → 跑 `start_import` ⇒ 断言 ①帧 `BLOCKED/RESOURCE_LOCKED/executed=false` ②**本次创建的 task 锁已归还** ③别人的 job 锁原样（`endsWith("|other:owner")`）④未认领、无台账。**这恰好覆盖了我要求的"被 job 锁拒 ⇒ 归还 task 锁"分支**，且把"不得误删别人的锁"也锁了。
- 对照用例 `anotherJobTypeDoesNotBlockTheSameTask`（:393-407）：占用 `job:IMPORT` 时跑 `start_export` ⇒ **应当执行成功**，并断言终态归还 task 锁、IMPORT 锁不受影响 —— 反向把「双粒度按作业类型分键」钉住，比我原本要求的更有价值。
- 判定：**忠实裁决意图（取/放对称），并修正了裁决措辞的字面错误**；建议把这条字面纠正登记进收口报告（评审原文见上）。

### M-4 三态先行 + null 回落 —— **已落地；「生产恒非 null」论证成立**
`SpToolCallingManager.java:685-694`：首次即 `tryClaimExecution`，`claim == null` 才回落布尔口。
- 论证核实：`RunStore.tryClaimExecution` 所有 return 路径都构造 `ClaimResult`（WON/LOST/STALE_FENCE），无 null 分支；`InMemoryRunStore.tryClaimExecution` 同样四路径全返回非 null；全仓 `extends RunStore` 只有 `InMemoryRunStore` 一个子类 ⇒ **真实实现不可能返回 null**，回落分支只对 Mockito mock（未桩方法返回 null）生效。
- 兼容面实证：`ConfirmGateWiringTest`（`verify/when(claimExecution)`）、`ToolOutcomeExitTest`、`GenerativeFormGateTest` 全部仍绿 —— 这些 mock 走 null 回落，既有断言零改动成立。
- 附带收益：竞争路径不再重复 `HSETNX` + fence `GET`。

### N-1 / N-4 —— **已落地（N-1 自报表述不准）**
- `RunStore.java:1012-1023`：`fenceEpoch` 读失败**已是 WARN**，并写明"fail-open 是放宽，必须 WARN"以及 TOCTOU 挂账。（自报说"已是 WARN（无需改）"与事实不符：上轮我读到的是 debug，本轮已改。结论正确，仅报告口径需订正。）
- `ToolUnknownReconciler.java:35-44`：新增「口径差异（N-4）」小节，说明同一旧台账在 `replayLedger`（按 OK）与 reconciler（按 UNKNOWN）结论不同的理由。✔
- N-2 / N-3 / N-5 / N-7：未改代码、声明登记 —— 与自报一致，我逐条确认登记项仍成立（`appendLedger` 脚本后多一次 EXPIRE、`stopDriving(key)` 全表前缀扫描、`describeKeys` 的 null 值、TARGET_INVALID 只在审批后校验）。

---

## 三、断言只升不降终核 —— **通过**

- **唯一删除**：`LeaseResourceTtlTest.renewKeepsTheLockAlivePastTheOriginalDeadline`（5→4）。理由成立：该例锁的是**已被否决的语义**（资源锁续租），删除后该文件仍在锁「TTL 到点自清 + 新 owner 接管 + 过期视图不敌 Redis 真值」；而"不续租"这一新语义由 `ToolResourceLockGateTest:164` 正面锁住（持锁期间零续租任务）。属**纠正**而非降格。
- 其余零删改：13 个相关测试类逐类点数，只有 3 处按计划增加 —— `ToolResourceLockGateTest` 8→10、`RunStoreIdempotencyIndexTest` 10→11，其余（ToolTargetResolverTest 10、ToolTargetResolverGateTest 4、ToolIdempotencyTest 5、ToolUnknownReconcilerTest 8、LeaseKeysTest 5、LeaseServiceDistinctOwnerTest 9、ResumeDescribeLockKeysTest 3、ToolOutcomeResourceCodesTest 6、RunStoreClaimFenceTest 4、ToolResultEnvelopeTest 4）不变；`ToolIdempotencyTest` 的 STALE_FENCE 断言（含「不得出现『实例 null 认领』」）原样保留。
- fixture（`InMemoryRunStore`/`FakeLeaseRedis`）本轮无削弱性改动（`tryClaimExecution` 非 null 路径已核、脚本正文捕获仍启用）。

---

## 四、残余项（分级，均不阻塞）

**Minor**
1. **SKIP 可能掩盖真故障（I-2 折衷的代价）**：`-SharedDb` 变体 3 轮全 miss 判 SKIP，真锁故障与"窗口未重叠"同形。缓解已在（H3 确定性必跑、每轮形态打印）。建议：把「只跑 `-SharedDb -Cases resource-lock`」标为不完整证据（README 已暗含），收口报告里明写"跨实例锁证据 = H3 + 至多一次命中"。
2. **`idemFor` 新身份的极端分支**：若某台账记录**缺 `toolCallId` 字段**（仅人为破坏），索引路径与扫描路径都会 miss ⇒ 判"未执行"并重走执行；此时 `ai:claim:` 的 HSETNX 是兜底（会 LOST，不重复副作业）。可接受，建议在 `matchesToolCall` 注释里补一句"记录缺字段 ⇒ 回落，最终由认领兜底"。

**Nit**
3. **自报口径两处订正**：① N-1「已是 WARN 无需改」→ 实际是本轮改的；② 计数口径统一用 surefire 汇总行（778），不要用 report 文件求和（会漏 `@Nested`，差 10）。我自己的 687/766 两处误报也一并记录（见第一节）。
4. N-2/N-3/N-5/N-7 四项已登记跟踪，本批不动 —— 与本复核意见一致。
5. `data/`（含 `data/files/*.xlsx`）仍为未跟踪（批② M9 挂账），提交时按显式路径避开。

---

## 五、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

### 〔段 3／4 · assistant 消息 m280 · 5,847 字符〕

## 复核结论：**READY-WITH-NITS**

复核对象：`<LOCAL_WORK>/ai-example-code/wt-ai-guardrails-b1`，新 HEAD=`65f3dc2`（`git diff 097fa66..HEAD`，15 文件）。只读：未改文件、未起停服务、未提交。

### 复跑证据（实测）

| 项 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/.../mvn -q -o test` | **EXIT=0**；surefire 汇总 **733**（report 求和 723 + `GenerativeFormGateTest` 的 10 条 `@Nested`）＝ 728 + **5**（`AiPropertiesLimitGuardTest` 4 + `AiRateLimiterTest` 脚本正文 1）✔ |
| 前端 #1（常规） | `node node_modules/vitest/vitest.mjs run` | **27 files / 521 tests / 0 errors / EXIT=0** |
| 前端 #2（常规） | 同上 | **27 / 521 / 0 errors / EXIT=0** |
| 前端 #3（**加压**：4 个 CPU 忙循环并发） | 同上 | **27 / 521 / 0 errors / EXIT=0**（80s 内 4 个 hog 全程占用） |
| typecheck | `npx vue-tsc --noEmit` | **EXIT=0** |

（前端 521 = 上轮 517 − 旧文件删 5 + 新文件 9；新文件 9 = 迁移 5 + M5 新增 4 ✔。）

---

## 一、逐项核验

### 1. C1（前端单测红 → 已消除）—— **通过**
- **新文件自包含**：`frontend/tests/ai-panel-composer-limit.spec.ts:20-81` 自带 `vi.mock('@/api/ai')` 且 `beforeEach` 给 `health/history/runsCurrent/chat` **全部实值**（正是上轮 5 条 unhandled rejection 的根因：`history` 返回 `undefined` → `loadHistory` 读 `history.messages` 崩）；`beforeAll` 以 30s 预热 3000 行组件的冷导入，两个 describe 各 `timeout: 20_000` ✔。
- **旧段整段删除、零残留**：`ai-panel-parts-render.spec.ts` 末尾已无该 describe（`grep "批①|composer-count" = 0`），且该文件尾部正常收在上一 describe 的 `})` ✔。
- **exit 0 / 0 errors**：三次全量复跑（含加压）均 **0 errors**；上轮那 12 条 mermaid/代码块家族在三次运行（含加压）里**一次未复现** ✔。

### 2. I1（脚本正文契约）—— **通过**
- `AiRateLimiterTest.scriptBodyKeepsTheAtomicSlidingWindowCommandOrder`（`:115-139`）断言：分派标记、`'ZREMRANGEBYSCORE', KEYS[1]`、`'ZCARD'`、`'ZADD'`、`'PEXPIRE'`、**`'TIME'`**，并钉住 **命令序** `ZREMRANGEBYSCORE < ZCARD < ZADD` ✔ —— 这正是「按标记分派的替身测不出正文改写」的那一维（改序/去回收/换本地时钟都会红）。
- **单一来源**：`AiRateLimiter.SCRIPT_MARK`（`:52-57`）为产品常量；`FakeRateRedis.MARK = AiRateLimiter.SCRIPT_MARK`（不再各自写字面量）⇒ 改脚本标记忘改桩会**编译期**暴露 ✔；`FakeRateRedis` 新增 `lastScriptBody()` 供正文断言，脚本语义复刻未变 ✔。

### 3. I2（闸门顺序 + 用例语义改写）—— **通过（断言强度不低于原）**
- 新顺序（`AiController.java:195-232`）：**必填 → Redis 探活 → 限流 → 归一化 → 空拒 → 长度 → 会话门** ✔；限流仍在会话门之前（被限流不占 in-flight 位）✔；注释明确写出代价「Redis 不可用时超长请求先撞 503 而不是 400」✔。
- 用例改写 `overlongMessagesStillGet400AndConsumeTheRateQuota`（`AiControllerChatGateTest.java:174-196`）：**逐条**断言 20 次超长均 400 + `MESSAGE_TOO_LONG`，随后第 21 条正常消息 429 + `RATE_LIMITED`。对比原例（只断言「20 条超长之后正常消息 200」）：400 侧由「无断言」升级为「逐条断言」，额度侧由裁决翻转 —— **强度只升不降**，且类注写明「这是经裁决批准的语义改写，不是降格」✔。

### 4. I3（E2E 与限流解耦）—— **通过（1 处文案不准，见 M-a）**
- `ci.yml:170-190`：e2e job 启动参数新增 `--app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000`，并加两处注释（job 头 + 启动步）说明「限流不在 E2E 覆盖面，由单测覆盖」✔。
- `verify-e2e.ps1:33-38` 头注释同步要求同参数，并注明 CI 已按此启动 ✔。
- **事实核对**：注释写「/chat 才 21 次、每个 sessionId ≤ 2 次」——**每个 sessionId ≤ 2 次不成立**：TC18 取消后重试循环（`verify-e2e.ps1:2127` `for ($i = 0; $i -lt 20 …)`）用的是**同一个 `$sc`**，加上此前 2 次，单 session 最多 **22** 次；全程最坏约 **40** 次。限流已抬高到 200/2000，**行为无风险**，但文案应改（见 M-a）。

### 5. M1（限流参数启动期守卫）—— **通过**
`AiProperties.verifyRateLimit()`（`@PostConstruct`）：`session/ip ≤ 0` ⇒ 启动失败并点名键与当前值（理由写清「等于关掉对话」）；`window ≤ 0` ⇒ 启动失败（理由写清「PEXPIRE 0 立刻删键 ⇒ 限流静默失效」）✔。4 例覆盖：默认通过 / session=0 / ip=-1 / window 0 与负 ✔。与既有 `verifySseWriteTimeout` 同一姿态，绑定先于 `@PostConstruct` ✔。

### 6. M5（播报节流）—— **通过**
- 计数节点**去掉** `aria-live`（`:746-750`）；新增独立 `sr-only` live region（`data-testid="composer-count-live"`，`aria-live="polite"`），文案按**四档（normal/near/limit/over）各一个常量**、不含数字 ⇒ 同档内逐字输入 DOM 文本不变、只在跃迁时播报 ✔。
- 视觉档位不变（near/limit 琥珀、over 红、normal 灰）——既有「到顶琥珀 / 超限红」断言仍成立 ✔。
- 4 条新用例：计数节点无 aria-live + live region 存在且 `sr-only`、常规档内逐字输入恒空、near 跃迁只说一次常量、limit/over 各有独立文案 ✔。

### 7. 额外改动 `resetDegradation`（**重点审**）—— **通过，且是真正的根因修复**
- **机制核对**：`markdown.ts:136` 的 `degraded` 是**模块级 `let`**；`:644-645` 在单次渲染 `performance.now() - started > RENDER_BUDGET_MS(24ms)` 时置位，`:618` 之后所有渲染走 `useDegraded` 降级分支 ⇒ 一次慢渲染（冷 JIT / 并行 worker / CPU 抖动）会**污染同进程其后的全部渲染**。这与上轮观察到的「12 条 mermaid/代码块族连带变红」完全吻合（它们正是走 `useDegraded` 的那一族）。
- **生产行为零变化（论证）**：① `markdown.ts` 的 diff **只有新增的 16 行函数**，`degraded` 的置位/消费逻辑一字未动；② `grep resetDegradation` 全仓只有 `markdown.ts` 定义 + `tests/setup.ts` 调用，**生产代码零调用点**；③ 测试 reset 只在用例之间发生 ⇒「同一用例内的粘滞语义」仍被真实执行（`T1 降级` 与 mermaid 正常渲染用例都仍在跑，若有人把预算改成恒降级，单用例内即会红）。**结论：测试 reset 不破坏产品语义，也不掩盖真实降级回归。**
- **加压复现可信度**：我在 4 个 CPU 忙循环并发下跑全量仍 **0 errors / exit 0**；叠加「机制吻合 + 旧文件 12 条在三次运行中一次未现」⇒ 自报「4 次连跑含 2 次加压」可信。
- 一处措辞要留痕（见 N-b）：注释把 `degraded` 称作「**会话级**粘滞」，而实现是**模块全局（页签级）**——同一页签内所有 session 共享，刷新才清零。不影响本修复，但描述与实现不一致。

### 8. 数字 —— **全部对上**
后端 733（723 + 10 nested）＝ 728 + 5 ✔；前端 27 文件 / 521 例 ✔；typecheck 0 ✔；旧 flaky 文件三次运行全绿 ✔。

---

## 二、残余分级

### Critical / Important
无。

### Minor
- **M-a** `scripts/verify-e2e.ps1:35` 事实不准：「每个 sessionId ≤ 2 次」——TC18 同一 `$sc` 最多 22 次（`:2127` 重试循环），全程最坏 ~40 次（典型 21 次）。限流已被 I3 抬到 200/2000，**无行为风险**，属文案精度。修法：改成「每 sessionId ≤ 22 次（TC18 取消后重试循环）、全程最坏 ~40 次；故限流在 E2E 中显式抬高」。

### Nit
- **N-a** `frontend/tests/setup.ts` 的全局 `beforeEach` 依赖「任何对 `@/utils/markdown` 的 mock 必须 `...actual` 透传 `resetDegradation`」。当前唯一的 mock（`ai-panel-parts-render.spec.ts:36`）符合 ✔，但未来若出现整模块替身，`resetDegradation` 会变 `undefined`、每个用例的 beforeEach 抛错。建议在 setup 里加一行 `if (typeof resetDegradation === 'function')` 防御，或在该 mock 处加注释。
- **N-b** `markdown.ts:145` 与新注释把 `degraded` 称「会话级粘滞」，实现是模块全局（同一页签内所有 session 共享，刷新才归零）。建议措辞改为「页签级（模块全局）粘滞」，避免下一位读者按 session 语义改坏。
- **N-c** 说明（非问题）：闸门顺序改为「Redis 探活在前」后，Redis 不可用时超长/纯不可见消息返回 **503 而非 400**。这是裁决 I2 的既定代价（注释已写明），登记即可。
- **N-d** 上轮 M2（前后端 8192 无交叉校验）复核后**视为已覆盖**：后端断言 `limit=8192`（取自 `app.ai.input.max-chars` 默认值）、前端断言 `maxlength=8192`，两端各自钉值 ⇒ 单侧改动必红，无需额外交叉校验。
- **N-e** 上轮 N1（无 `\r\n` 归一 / 孤立代理项显式用例）仍未补；行为已由 `Character.FORMAT/SURROGATE` 规则覆盖（Java 21 实测），登记即可。

---

## 三、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

### 〔段 4／4 · assistant 消息 m338 · 5,540 字符〕

## 复核结论：**READY-WITH-NITS**

复核对象：主工作树相对 `3c79eaa` 的未提交改动（收口轮新增 `AiPropertiesContextGuardTest` 8 例 + `ContextFrameWiringTest` +2 例 + 各处注释/文案/文档修订）。只读：未改文件、未起停服务。

### 复跑证据（实测）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/.../mvn -q -o test` | **EXIT=0**；汇总 **937**（report 求和 927 + `GenerativeFormGateTest` 10 条 `@Nested`）＝ 859 + **78**（施工 68 + 收口 10）✔ |
| 收口增量 | 逐类 | `AiPropertiesContextGuardTest` **8** + `ContextFrameWiringTest` 4→**6** = **+10** ✔ |
| 前端 | `node node_modules/vitest/vitest.mjs run` | **29 files / 545 tests / 0 errors / EXIT=0** ✔ |
| typecheck | `npx vue-tsc --noEmit` | **0 错** ✔ |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6** ✔ |

---

## 一、逐条核验

### 1. I-1（续跑侧 `context` 帧 + 级别显式交接）—— **通过**

**发帧时序（重点攻击面）—— 成立，且有真帧序证据**
- 结构：`ResumeService.java:614-622` 在「压缩成功 → 写快照 → 组 body」之后、`suspendExecutor.execute(...)`（交棒）**之前**同步执行 `out.context(ContextCompactor.framePayload(compaction))`。`start` 由原轮在挂起前发出（seq 更小），`context` 在 `emit` 内**现取号**⇒ 必然晚于 `start`；驱动线程在 `execute` 之后才启动 ⇒ 必然早于首个 `delta`。
- 并发面：该 run 此时**没有驱动线程**（续跑由 `resuming` 表 + 续跑权租约排他；等待方存在时 `maybeTriggerResume` 走 `WOKE/ACTIVE_LATCH` 不派发），`emit` 的 `emitLock` 与任何旁路心跳也不涉及业务 seq（心跳独立序号空间）⇒ 不存在"帧插到 delta 之后"的窗口。
- 实证：`ResumeContextCompactionTest` 改用**真实 `SseChatEmitter` + `FrameWire`**，断言 `FrameContract.types(frames) == start, context, message_start, delta, …` 且 `assertConformant` 通过（`:139-142`）——这是"帧序"而非"方法被调用过"。
- **挂起侧 latch**：与发帧无关（latch 只影响死卡派发判定）；且该帧无论有无订阅者都会**取号 + 落档**，重挂 `?lastSeq` 能补到 ✔。
- 载荷单一来源：首轮与续跑都调 `ContextCompactor.framePayload(outcome)`（`ResilientChatService:1028` / `ResumeService:616`）⇒ 两份载荷不可能漂移 ✔。

**`compactionLevel` 一次性交接 —— 正常路径正确，两个窄口见残余**
- 载体：`Map<String,String> compactionLevels = new ConcurrentHashMap<>()`（`ResilientChatService:1129`），`noteCompaction` 写、`resumedTurn` **`remove` 读后即弃**并缺省 `none`；`contextTelemetry` 直接用 `turn.compactionLevel()`，**时刻反推已删除** ✔。
- 同 run 连续两续跑轮**不串味**：每轮压缩成功后各自写一次、各自 `driveResumed` 消费一次；`compactionLevelHandoffIsOneShot` 用两次驱动断言"第二次为 none" ✔。
- **窄口 N-a**：交接位只在"驱动真的跑起来"时被消费。`ResumeService.java:643-649` 的 `RejectedExecutionException`（池饱和）路径**不清交接位**，且类注"条目也只留到该轮结束（runId 不复用）"不成立——同一 run 可被再次续跑。若下一次续跑本轮未压缩，会消费这条陈旧位 ⇒ `done.context.level` 误报 L1。
- **窄口 N-b**：`out.context(...)` 是本轮**新增的"交棒前同步发帧"**；`emit` 内部对序列化失败是吞掉的（记 ERROR 后 return），但 `nextSeq`/`archive` 走 Redis 时异常会向外抛 ⇒ `resume()` 抛错，而快照此前已被置 `RUNNING` 且无驱动线程（只能等僵尸接管）。发帧属观测面，建议 try/catch。

### 2. M-1 / M-4 / M-5 / M-6 / N-1 / N-2 / N-3 / N-4 —— **逐条落地**
- **M-1 ✔**：`AiProperties` 新增 `chunkTokens >= reserveTokens` 的 fail-fast（严格小于，消息点名两键与后果）；`AiPropertiesContextGuardTest` 8 例含边界 `chunk = reserve − 1` 合法 ✔。
- **M-4 ✔**：danger 档文案改为「下一次请求前会先清理历史工具结果」（`context-usage.ts:92`），注释点明"摘要属批③，文案不得承诺未落地手段"；spec 断言同步为 `toContain('清理历史工具结果')`，全文件已无「摘要压缩」残留 ✔。**强度判定：不降**——仍锁"高危档必须给出具体动作"，只是把"未实现的手段"换成"已实现的手段"。
- **M-5 ✔**：施工报告 §7 新增第 8 条（E2E-CTX-1..4 + 三形态 usage 桩未落，归 E2E 批，并列出当前等价物）与第 9 条（空回复轮不记账 = 既有边界，`emptyReply` 走 `error` 终帧）；看板本批行同步补两句 ✔。
- **M-6 ✔**：`technical-design.md:327` 已改「13 键」。
- **N-1 ✔**：`ContextMeter` 类注 + `model()` javadoc 均改为「首个非空 metadata」，并解释了与 usage"取最后"不矛盾（取值对象不同）✔。
- **N-2 ✔**：NUMSPEC `B1-47` 依据列注明「按 `ToolResponseMessage` 里的 response 条数计，不是按消息条数」✔。
- **N-3 ✔**：`RunStore` 的 `write` 增"字段级合并、非先删后写"边界注（`:1722`）；**读路径实际影响复核 = 无**：`model` 只在 EMPTY 时为 null（`recordTurn`/`withUsage` 均带非空 model），`reason` 残留正是"最近一次压缩/跳过原因"的预期语义。
- **N-4 ✔ / N-5 ✔**：端点注释点明有界性来源；`role=dialog` 无 `aria-modal` 按登记不改。
- **M-2 —— 只统一了 2/3（见 M-a）**：类注 `ContextCompactor:37` 与 `ResilientChatService:1037`（归档 → 记忆 → 快照，含"与设计稿相反"的理由）已改；**但 `ContextCompactor.java:157` 的方法注仍写「调用方按『快照 → 记忆』顺序落库」**，与同类 37 行和实现相互矛盾。

### 3. 断言只升不降终核 —— **通过**
- 本轮唯一被改的既有断言 = `frontend/tests/context-pill.spec.ts:138`（`toContain('摘要压缩')` → `toContain('清理历史工具结果')`）：同一位置、同一形态、仍是"必须给出具体动作"的强约束，且从"承诺未实现"改为"承诺已实现"⇒ **非降格**。
- 其余均为**新增**：`ContextFrameWiringTest` 4→6（`resumeCompactionLevelReachesDoneContext`、`compactionLevelHandoffIsOneShot`）、`ResumeContextCompactionTest` 仍 2 例但**新增**真帧序断言（`start, context, message_start, delta` + `assertConformant`）、`AiPropertiesContextGuardTest` 全新 8 例。无删除、无放宽。
- 既有 859 + 68 = 927（上轮）→ 937（本轮）逐数吻合 ✔。

---

## 二、残余分级

### Critical / Important
无（I-1 的必修面已闭合：帧已发、位置正确、级别不再靠时刻反推）。

### Minor
- **M-a M-2 残留一句**：`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:157` 方法注仍写「调用方按『快照 → 记忆』顺序落库」，与同类 `:37`（已改为实际「记忆 → 快照」并说明理由）及 `ResilientChatService:1037` 矛盾。修法：这半句改为「记忆 → 快照，理由见类注差异段」。

### Nit
- **N-a 交接位在交棒失败时不清理**：`ResumeService.java:643-649`（`RejectedExecutionException` → `POOL_SATURATED`）未清 `compactionLevels`；类注"runId 不复用"前提不成立（同一 run 可多次续跑）⇒ 后续未压缩的续跑轮可能误报 `level=L1`。修法：该 catch 里清一次交接位（或给交接位加"仅本轮起点之后有效"的时间戳）。
- **N-b 交棒前同步发帧的异常面**：`ResumeService.java:616` 的 `out.context(...)`（要 `nextSeq`/`archive` 打 Redis）若抛错会从 `resume()` 冒出，此时快照已 `RUNNING` 却无驱动线程。修法：包 try/catch + WARN（发帧是观测面，不该决定续跑成败）。
- **N-c** `ContextCompactor.framePayload` 的 `turns/lostTokens` 硬编码 0（L1 如实）；批③ 接 L2/L3 时改从 `Outcome` 取（登记即可）。
- **N-d** 上一轮"`keep-tool-pairs` 按 response 条数计"的偏差已由 NUMSPEC 注记吸收，代码行为不变（保持）。

---

## 三、未复核/边界

- E2E-CTX-1..4 与三形态 usage 桩仍未落（本棒不动 `scripts/`），已在施工报告 §7.8 与看板登记 ⇒ 验收 §9-2 的 E2E 面由后续 E2E 批承接（非本批缺陷，属已登记缺口）。
- 我未跑 E2E（禁起服务）；后端/前端/typecheck/check-docs 均实跑。

## 四、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。
