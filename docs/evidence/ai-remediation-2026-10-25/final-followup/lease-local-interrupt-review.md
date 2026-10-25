> 来源=<仓外证据>/lease-local-interrupt-review.md（2026-10-26 归档）· 取消中断的租约健康分类补丁 · 小范围只读复审（APPROVED；m1 为当时 Minor）；属历史证据，不代替终稿/现行基线
# 取消中断的租约健康分类补丁 · 小范围只读复审

- 主仓：`<主仓>`，HEAD `2816f663b065777146693d9cb83e3f04744248bf`
- 审查范围：**仅** `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`（未提交修改）+ `backend/src/test/java/com/example/configmgr/ai/run/LeaseLocalInterruptHealthTest.java`（新增）
- 补丁原件：`<仓外证据>/cancel-lease-health.patch`（2 文件）
- 方式：只读；未改任何文件、未提交/推送、未派生 reviewer、未跑 Maven（理由见 Declined 第 2 项）
- 最终 sha256（2026-10-26 01:31 +08:00）：
  - `LeaseService.java` = `1ed2ed8ba623b1ab641f7ec5e2c161d5d6fc01119c1e32a5c496384601b826d1`
  - `LeaseLocalInterruptHealthTest.java` = `50a280557055ba08c7720fab960cd4a5aa0961a85e1efda786bc2ed68bfd76b4`
- 补丁与工作树一致性：`git diff -- LeaseService.java` 与 patch 的对应段**逐行相同**（仅 `index` 行不同）；新测试文件与 patch 的 `+++` 内容相同（199 行，仅 CRLF 差异）。

**判决：APPROVED — Ready to merge: Yes**。无 Critical、无 Important；4 条非阻塞 Minor。局部中断仍被限制在"本次调用/这一任"，真故障仍 fail-closed，W30 交棒的墓碑不会被堵死。

## 1. 现场与根因（按日志核对）

- `<仓外证据>/ci-40211303032/boot.log:6733-6735`：工具线程 `boundedElastic-28` 上 `RunStore` 写请求帧标记失败 / `LeaseService 租约抢用降级`，原因都是 `Redis command interrupted`；4ms 后（`:6740`）驱动线程 `cancelTerminal` 的写被同一写门拒绝。
- 日志**只**显示顶层 message（`Redis command interrupted`），不含 cause 链；"`RedisSystemException` ← `io.lettuce.core.RedisCommandInterruptedException` ← `InterruptedException`" 来自 Lettuce/Spring 的包装形态与用例复刻（见 Declined 第 1 项），不是日志逐层打印出来的。
- 补丁的分类方向正确：**不动共享健康位，只钉本 run 的墓碑**。这一点决定了 W30 不会被"修反"（见 §2）。

## 2. 与 W30 交棒的兼容性（本次重点）

W30 交棒链（`<仓外证据>/w30-repro` 的 `ResumeService.java:1310 → 1320 → 1328`）：`acquireFenced` → `verifyHeld` → `cancelParked` → `cancelTerminal` → `permittedToWrite`。

- **acquire 成功会清墓碑**：`LeaseService.java:631`（`interpret`）在脚本返回自己那一任时执行 `this.ended.remove(viewKey)`，并且 `this.facilityHealthy = true`；`LeaseService.java:661`（`verifyHeld` HELD 分支）也 `ended.remove(viewKey)`。所以交棒执行体在 `aiResumeTrigger` 线程上重新抢租一次，就会把工具线程留下的 `UNAVAILABLE` 墓碑清掉。
- **重入路径也不会堵死**：若本实例已有租约视图，`acquire` 走进程内短路（不碰 Redis、不清墓碑），但 `cancelConfirmed` 紧接着调用 `verifyHeld`；HELD 即清墓碑。两条路径都覆盖。
- **W30 的触发不变、误伤消失**：W30 里被中断的是**本 run 的** `tryAcquireFenced`（`boot.log:6735` 的 key 就是 `run:3fef5d65…`）。补丁后它留下**本 run** 的墓碑（而不是全实例健康位），驱动的 `authority(runId)` 仍是 `UNAVAILABLE`（`authority` 顺序是墓碑 → 视图 → 从未持租，`LeaseService.java:826`），所以仍会拒写并交棒；而同实例的**其它 run** 不再被 1s 节流连坐——这正是补丁要修的另一半。
- **W30 既有测试的替身不会触发新分类**：`FakeLeaseRedis.goDown()` / `failRenew(true)` 抛的是 `IllegalStateException`（无 `InterruptedException`），分类器判为**设施级**，与补丁前的全局置假同形；因此 W30 测试里 `runLeaseAuthority == UNAVAILABLE`、`permittedToWrite` 拒绝、交棒触发这些前提都保持不变。
- **结论**：新墓碑不会把 W30 交棒堵死；如果交棒执行体这一拍拿不到租约，返回 `UNAVAILABLE` 并重试，恢复后由 `interpret`/`verifyHeld` 清墓碑再落 CANCELLED。

## 3. 其余审查点

- **局部中断仍限制在当前调用/当前租约**：`acquire` 的局部中断分支只 `markEnded(viewKey, UNAVAILABLE)` 并返回 `UNAVAILABLE`（`LeaseService.java:573-581`），`viewKey = key + owner`，只影响这一任；`verifyHeld`/`renewState` 仍保留各自的墓碑；`release` 失败时视图仍在（`authority` 按 HELD 回答），与补丁前一致（`authority` 的视图分支本来就不看健康位）。**没有放大写权限**：被中断的 run 只读；其它 run 的写权限只由设施健康与自身租约事实决定。
- **真故障仍 fail-closed**：`markFacilityUnhealthy(Throwable)`（`:893-901`）只对非 `InterruptedException` 链清 `facilityHealthy` + `lastProbeOk`；`RedisConnectionFailureException`、`QueryTimeoutException`、CROSSSLOT 的链上没有 `InterruptedException`，照旧作废共享位。用例 `aRealFacilityFailureStillMakesEveryRunReadOnly`（`:118`）与 `aConnectionFailureIsNotMistakenForALocalInterrupt`（`:134`，**同一个文案** "Redis command interrupted" 但 cause 是 `ConnectException`）证明判据认因果链、不认字符串。
- **probe 三态不破坏 fail-closed**：`probeFacility()`（`:1081-1096`）在捕获到局部中断时 `markFacilityUnhealthy` 是 no-op，返回 `null`；`facilityHealthy()`（`:856-881`）对 `null` 返回 **false**（本次判定只读），并用 `lastHealthProbeMs.compareAndSet(now, 0L)` 放掉节流令牌，下一个非中断调用立刻重探；`leaseOk()` 对 `null` 返回 false。并发上：抢不到令牌的线程复用 `lastProbeOk`（此时必为 false，保守）；探针在飞失败时同一拍不会写入乐观结论。用例 `anInterruptedProbeIsNotCachedAsAFacilityVerdict`（`:152`）正是这个行为。
- **异常链分类的误放宽面**：能想到的误判只有"某包装层恰好把 `InterruptedException` 放进 cause 链、而设施其实真坏"；Spring Data Redis 的常见失败（连接失败/超时/CROSSSLOT/脚本错误）都不带该 cause。误判方向是"本次不宣布设施坏"，对**当前** run 仍有墓碑/只读保护；对**其它** run 只是让它们的写去碰真实 Redis（失败会抛 `AiStateUnavailableException`，不是静默成功）。安全方向可以接受。
- **资源/锁/墓碑清理**：W30 的 run 租约墓碑会被交棒执行体的 `acquire`/`verifyHeld` 清掉（§2）；没有临时监听器、没有新线程；`ended` 只在 `shutdown` 清空（`:1108`）。
- **测试真实性**：4 条用例用的是**真实** `LeaseService` + `FakeLeaseRedis`，没有 mock 分类器；`aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly`（`:84`）真的 `Thread.currentThread().interrupt()`，并断言异常抛出后中断位**已清**（`:98-100`），把"不能用 isInterrupted 判定"钉成事实；`interruptedRedisCommand()` 复刻 `CompletableFuture.get` 清位 + 三层 cause 链，且在 `@AfterEach` 清中断位避免污染后续用例。红绿对应真实行为（§4）。
- **装配/协议**：只改 `LeaseService` 与测试；不改 `RunStore`、`permittedToWrite`、fencing 判据、帧协议、W30 的 setter 装配；无新依赖、无新线程。

## 4. 日志核对（本轮实际读取）

| 日志 | 计数 | 关键内容 |
|---|---|---|
| `lease-local-interrupt-red.log` | 4 run / **2 fail** | 失败恰是两组局部中断：`aCancelledThreads…:108 expected NEVER_HELD but was UNAVAILABLE`、`anInterruptedProbe…:175 同`；两组"真实故障"用例通过。日志里的 message 是**旧文案** `租约抢用降级…（Redis 不可用 ⇒ 调用方走进程内互斥）`，且 `租约抢用被本线程中断` 出现 0 次 ⇒ 确为基线跑 |
| `lease-local-interrupt-green.log` | 4 + 10 = **14/14** | 新类 4/4 + `LeaseServiceTest` 10/10 |
| `lease-local-interrupt-green-rerun.log` | 4/4 | 复跑一致 |
| `lease-local-interrupt-green-probes.log` | 2 + 6 = **8/8** | `LeaseAuthorityProbeFailClosedTest` + `LeaseServiceTriStateTest` |
| `main-lease-targeted.log` | 2 + 4 + 6 = **12/12** | 主仓；01:25:08 完成，晚于源码 mtime 01:24:25；含新文案 `租约抢用被本线程中断` ⇒ 跑的是补丁后的字节 |
| 统计 | — | 与实施报告声称的 4/2、14/14、8/8、12/12 完全一致 |

## 5. Declined to judge（明确搁置）

1. **Lettuce/Spring 的真实 cause 链**：CI 日志只有顶层 message；三层链是按库的包装形态与用例复刻的。未在真实 CI 产物上逐层验证。若某版本丢掉 cause，分类回落到旧的 fail-closed（安全方向），不会放宽。
2. **合入组合未实跑**：主仓工作树在我审查期间（01:29:53）出现了 W30 的 6 个文件，且与我上一轮 APPROVED 的 W30 字节**完全一致**；此刻主仓 = W30 补丁 + 本 LeaseService 补丁（均未提交）。`main-lease-targeted.log`（01:25）早于这次写入，**不覆盖组合**。我没有在主仓跑 Maven：另一个 agent 刚写入这批文件，同一 `backend/target` 上的并发 Maven 会互相破坏。组合的结论来自静态链（§2），建议合并方串行跑一次 `CancelFinalizeHandoffTest`（W30 9 条）+ `LeaseLocalInterruptHealthTest`（4 条）。注意：W30 那 9 条里的 `goDown`/`failRenew` 走的是**真实故障**路径，覆盖不到"局部中断墓碑 + 交棒"这一格；要单独证明那格需要一条新用例（见 Minor m2/m4）。
3. **真 Redis / 集群**：多实例下的墓碑、1s 探针与真实 `RedisCommandInterruptedException` 未做集成/压测；本轮只有单实例 + 替身。
4. **`QueryTimeoutException` / CROSSSLOT 分类**：按代码形状（无 `InterruptedException`）判为设施级，未单独加用例。
5. **探针节流的并发**：CAS 逻辑逐行审过（保守方向），但没有多线程用例。
6. **`ended` 长期增长**：见 Minor m1，未做容量测量。
7. 多用户、健康检查大重构、理论 JVM Error 等（按要求不扩）。

## 6. Critical / Important / Minor

### Critical
无。

### Important
无。

### Minor（均不阻塞）

- **m1 `ended` 对"抢租失败"的墓碑会波及资源锁且无清理**：`LeaseService.java:573-581` 的局部中断分支对**所有** `acquire` 都 `markEnded(viewKey, UNAVAILABLE)`，包括 `fenceKey == null` 的资源锁（`ToolResourceLocks.acquire` → `LeaseKeys.task/job`，owner = `instanceId:runId:toolCallId`，`ToolResourceLocks.java:127/139/151`）。资源锁调用方（`ToolResourceLocks.acquireQuietly`、`SessionPurgeRetryRunner`）**不读** `authority`，而失败的 acquire 没有 release/verify 去清墓碑 ⇒ 每次"取消恰好打断资源锁抢用"都会在 `ended`（`:405`，无 TTL/无容量淘汰）里留下一个永久条目。
  影响：内存缓慢增长；无正确性影响；W30 的 run 租约不受影响（有 fence，且被交棒清）。
  最小修法：局部中断分支只在 `fenceKey != null` 时 `markEnded`（资源锁不需要这个墓碑）；或给 `ended` 加与 `FENCE_AHEAD_WARNED_CAP` 同类的容量上限（更大改动，非必需）。
- **m2 墓碑没有 TTL，非取消终态没有交棒**：局部中断的墓碑只能被"该 run 下一次成功的 acquire/verify/renew/release"清掉。W30 的取消路径有交棒去清；但若中断落在 gate 的 `acquire`（一个 Redis 往返的毫秒窗口）而这一轮走的是 `fail`/park 等**非取消**终态，就没有交棒，可能的结局是终态写被拒、快照停在 RUNNING/SUSPENDED，直到 resume/重启的 `acquire` 才清。窗口很窄（1ms 级 + 非取消），方向是 fail-closed（不是数据损坏），且 W30 不受影响；基线在"重试数秒后"能靠 1s 探针恢复、补丁不能，这是一个真实的差异。
  最小决策：要么接受（安全优先），要么在"成功写出本 run 的任一状态"时加一个清墓碑的钩子——后者属于新行为，**不建议塞进本补丁**；本轮只要求记录。
- **m3 文档证据口径略超**：`LeaseService.java:901-905` 写"生产形态（对 CI 日志逐层核对过）"。CI 日志只有顶层 message（`boot.log:6733/6735`），没有逐层 cause。建议改为"按 Lettuce/Spring 的包装形态（测试复刻），CI 日志只显示顶层 message"；这关系到后续维护者是否会误以为链已被日志证实。
- **m4 用例覆盖的窄口**：`aConnectionFailureIsNotMistakenForALocalInterrupt` 已覆盖"同文案不同链"，但 `QueryTimeoutException`/CROSSSLOT 未单列；探针节流没有多线程用例；"局部中断墓碑 + W30 交棒"这一格没有组合用例（见 Declined 2）。都属于可选加固，不需要扩成专项。

## 7. Assessment

**APPROVED / Ready to merge: Yes。**

- 局部中断只钉本任的墓碑、不动共享健康位；被中断的 run 仍只读，其它 run 不再被 1s 节流连坐。
- 真连接/超时/脚本类失败仍走原 fail-closed；probe 三态在"中断"这一支上只回答"本次不可知 ⇒ 只读"，并放掉节流令牌让下一拍立即重探，不写乐观缓存。
- 异常链判据认 cause 不认文案；误判方向不会放大写权限（当前 run 仍有墓碑/只读，其它 run 至多让真实 Redis 命令失败）。
- **W30 兼容性成立**：`acquire` 成功（`interpret`，`:631`）与 `verifyHeld` HELD（`:661`）都会清 `ended` 墓碑，`cancelConfirmed` 的 `acquireFenced → verifyHeld → cancelParked` 顺序正好覆盖；W30 的触发条件（本 run 被中断 ⇒ 墓碑 ⇒ 拒写 ⇒ 交棒）保持不变。
- 证据链真实：红是基线（旧文案、两组局部中断失败、两组真故障通过），绿 14/14，探针 8/8，主仓 12/12；补丁与工作树逐行一致。
- 唯一需要在合并流程里补的一步是**串行**验证"W30 补丁 + 本补丁"的组合（Declined 2）；这是流程项，不是代码缺陷。4 条 Minor 均为窄边界或文档口径，不阻塞。
