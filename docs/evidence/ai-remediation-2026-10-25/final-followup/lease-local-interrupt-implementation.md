> 来源=本批新建（2026-10-26 整理；素材＝三个只读实现/验证会话的 output.log 与既有日志名）· 实现记录＋红绿证据；属历史证据，不代替终稿/现行基线

# 取消中断的租约健康分类 · 实现与红绿证据（本地；已合入工作树，未 commit）

- **性质**：**只读整理**本轮已完成的实现与证据，**未再开发、未改产品/测试**。所有计数照录日志；推断与实测**分开标注**。
- **补丁**：`<仓外证据>/cancel-lease-health.patch`（**恰好 2 个文件**：`LeaseService.java` 修改 + `LeaseLocalInterruptHealthTest.java` 新增；末版 452 行，`+101 / −12`，新测试 248 行）。
- **基线**：主仓 `<主仓>`，HEAD `2816f663b065777146693d9cb83e3f04744248bf`（复审记录的基线与 sha256 均对应该提交；本轮整理未改任何文件）。
- **状态**：该补丁**已合入 `<主仓>` 工作树，尚未 commit / push**；与 W-30 交棒补丁的组合**已由主控串行跑出 16/16**（本地替身/单测环境，见 §3 末行与 §5 第 2 项）。**真实 Lettuce/Redis 中断与 W-30 完整 E2E 仍待 GitHub**。

## 1. 根因（CI 观测到的事实 vs 机制推断）

### 1.1 日志能直接证明的（CI run 40211303032）

- `<仓外证据>/ci-40211303032/boot.log:6733-6735`：工具线程 `boundedElastic-28` 上 `RunStore` 写请求帧标记失败 / `LeaseService 租约抢用降级`，两处 message 都是 `Redis command interrupted`。
- 4ms 后（`:6740`）驱动线程 `cancelTerminal` 的写被同一写门拒绝（UNAVAILABLE）。
- 同一次 CI 里，`SessionPurgeGuard` 的 `hasKey` 堆栈打印了**完整 cause 链**，但那是**另一条命令**，只能佐证包装形态。

### 1.2 机制推断（**不是日志逐层打印的事实**）

- 取消 → dispose 上游 → `boundedElastic` 工具线程被 interrupt → `LeaseService.acquire(key, fenceKey, ttl, owner)` → `StringRedisTemplate.execute(...)` → lettuce `AsyncCommand.await` → `CompletableFuture.get` 抛 `InterruptedException`（**先清中断位**）→ `io.lettuce.core.RedisCommandInterruptedException` → `LettuceExceptionConverter` 折成 `RedisSystemException("Redis command interrupted")`（cause 链保留 `InterruptedException`）→ `catch (Exception)` → `markFacilityUnhealthy()` 清掉**实例级** `facilityHealthy` / `lastProbeOk`。
- 另一线程/另一轮：`RunStore.permittedToWrite(runId)` → `LeaseService.authority(key)` → 无墓碑、无 held ⇒ `facilityHealthy()`；`HEALTH_PROBE_INTERVAL_MS = 1000`，窗口内**直接复用** `lastProbeOk=false` ⇒ `UNAVAILABLE` ⇒ 终态/终帧被跳过。
- 因此准确说法是「**一次在飞的 Redis 命令被打断 → 实例级健康位被置假 + 1s 节流 ⇒ 这一拍必然 UNAVAILABLE**」，**不是**「Redis 抖动/故障」。W-30 的拒写发生在失败后 4ms，即使 Redis 已完全正常也必然 UNAVAILABLE（同进程 7ms 前还有另一轮「租约设施自证恢复」）。
- 依赖事实（实现时核对，非 CI 日志事实）：`spring-data-redis 3.5.11` **没有** `org.springframework.data.redis.RedisCommandInterruptedException`；真实类是 `io.lettuce.core.RedisCommandInterruptedException`（唯一构造器 `Throwable`）。
- 一个判别细节：**catch 现场 `isInterrupted()` 通常是 false** —— `CompletableFuture` 的等待路径先用 `Thread.interrupted()` 清位再抛。所以「当前线程确已 interrupted」若字面实现就是死条件；可靠证据是 cause 链里的 `InterruptedException`。测试把这一点显式断言（`isInterrupted()==false`）。

**误伤面**：`facilityHealthy` / `lastProbeOk` 是 `LeaseService` **实例级** `volatile`，`authority()` 对「无 held、无墓碑」的 run 只靠它 + 1s 节流 ⇒ **同实例的其它 run 会被连坐**。CI 日志里实际只出现「跨线程」（受害者都是被中断的那个 run 自己），**跨轮次误伤是结构性风险**，用确定性单测证成（见 §3 红）。

## 2. 改动面（单文件 + 单测试）

`LeaseService.java`：

1. **`isLocalThreadInterrupt(Throwable)`**：唯一窄分类，沿 cause 链找 `InterruptedException`。
2. **`markFacilityUnhealthy(Throwable)`**：**全部 5 个调用面**（`acquire` / `verifyHeld` / `renewState` / `release` / `probe`）统一走带 cause 的入口 —— 局部中断只记 debug、**不动共享健康位**；真故障原样作废 `facilityHealthy` + `lastProbeOk`。
3. **`acquire` 的 catch**：局部中断分支**仍返回 `UNAVAILABLE`**（本轮仍只读，不放宽），并给**这一任**钉 `ended = UNAVAILABLE` 墓碑（`viewKey = key + owner`）。
4. **`probeFacility()` 三态 + `facilityHealthy()`**：探针被本线程中断打断时**不写 `lastProbeOk`**、不放节流窗口，只用 `compareAndSet(now, 0L)` 释放节流令牌让下一个调用方立刻重探；本次仍回答「不可知 ⇒ 只读」。`leaseOk()` 公开契约（true = PING ok）不变。

**未改**：`ResilientChatService` / `ResumeService` / `ConfirmGate` / `CancelledRunFinalizer`、`ended` 的 LOST 终局语义、fencing 判据、中断位（不全局清）、`FakeLeaseRedis`；无新依赖、无新线程、无新监听器。

## 3. 红 → 绿（日志照录）

命令统一：隔离树 `backend/` 下 `mvn.cmd -o -Dtest=… -DfailIfNoTests=false test`（IDEA 自带 Maven；**无 `clean`、未起服务、未跑全量**）。

| 阶段 | 日志 | 范围 | 计数 |
|---|---|---|---|
| **红**（生产改动临时还原为 HEAD，跑完还原并 sha1 校验一致） | `<仓外证据>/lease-local-interrupt-red.log` | 仅新增类 | **Tests run: 4, Failures: 2** —— `aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly:108 expected NEVER_HELD but was UNAVAILABLE`、`anInterruptedProbeIsNotCachedAsAFacilityVerdict:175` 同形；两条对照组（真宕机／真连接失败）**通过** |
| 绿 | `<仓外证据>/lease-local-interrupt-green.log` | 新增类 + `LeaseServiceTest` | **14/14**（4 + 10），BUILD SUCCESS |
| 绿（复跑） | `<仓外证据>/lease-local-interrupt-green-rerun.log` | 新增类 | **4/4**（确认不抖） |
| 绿（相邻两风险点） | `<仓外证据>/lease-local-interrupt-green-probes.log` | `LeaseAuthorityProbeFailClosedTest` + `LeaseServiceTriStateTest` | **8/8**（**2** + **6**），BUILD SUCCESS |
| 绿（主仓定向） | `<仓外证据>/main-lease-targeted.log` | 主仓侧三类 | **12/12**（2 + 4 + 6）；01:25:08 完成，晚于源码 mtime 01:24:25；含新文案「租约抢用被本线程中断」⇒ 跑的是补丁后字节 |
| **绿（组合，主控串行）** | `<仓外证据>/final-w30/backend-combined-targeted.log` | 主仓工作树：`CancelFinalizeHandoffTest` + `LeaseAuthorityProbeFailClosedTest` + `LeaseLocalInterruptHealthTest` | **Tests run: 16, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**；组成 **9 + 2 + 5 = 16**（编译源为 `<主仓>/backend`，01:43）。**这是替身/单测组合，不是真实环境证明** |

四个用例（初版 4 条）：

1. `aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly` —— **反例主证据**：「设施刚 PING 证过 → A 被中断失败 → B 被误判 UNAVAILABLE，而 PING 实际可用」。
2. `aRealFacilityFailureStillMakesEveryRunReadOnly` —— 真宕机仍 **fail-closed**。
3. `aConnectionFailureIsNotMistakenForALocalInterrupt` —— **同文案、因链不同**（`ConnectException`），仍 fail-closed；与 ① 的唯一差别就是异常链形状。
4. `anInterruptedProbeIsNotCachedAsAFacilityVerdict` —— 同类洞：中断线程上的 PING 失败不得进节流缓存；中断解除后必须立刻重探。

补丁核对：初版 389 行，`git apply --check -p1` + `patch -p1 --dry-run` 通过；相邻验证后按工作树重新生成与已交付 patch **逐字节相同**（可复现，无漂移）；`git status` 始终只有这两项（`target/` 被 ignore）。

## 4. 后续最小闭合（m1 / m3）——**未改写上一轮报告原文**

上一轮复审把 m1（`ended` 墓碑波及资源锁且无清理）列为**当时的非阻塞 Minor**；本轮按最小口径闭合，原始报告保持历史原样。

| 项 | 改动 | 红 → 绿 |
|---|---|---|
| **m1** 墓碑只给 run 租约（有 fence） | `acquire` 的局部中断分支加**唯一一个条件** `if (fenceKey != null) { markEnded(viewKey, UNAVAILABLE); }`；两条路径仍返回 `UNAVAILABLE`。**未做** `ended` 的 TTL/容量重构、未改 `release` | 红：`<仓外证据>/lease-local-interrupt-m1-red.log` **Tests run: 5, Failures: 1**（只有新用例红：`ended` 实测含 `ai:lock:task:cancel-lock-1\0inst-cancel:run-lock-1:call-1 = UNAVAILABLE`；另 4 条通过）。绿：`<仓外证据>/lease-local-interrupt-m1-green.log` `LeaseAuthorityProbeFailClosedTest` **2/2** + `LeaseLocalInterruptHealthTest` **5/5** = **7/7**，BUILD SUCCESS |
| **m3** 证据口径改准确 | 注释由「生产形态（对 CI 日志逐层核对过）」改为「这条链**按 Lettuce/Spring 的包装形态推断**，并由用例逐层复刻」；新增「**证据口径（勿过度引用）**」：CI 里租约降级那一行**只有顶层 message，没有逐层 cause** | 无测试面（纯注释），随 m1 一起复核 |

- m1 新增反例：`aLocallyInterruptedResourceLockLeavesNoTombstone`（无 tag/无 fence 的资源锁，中断线程上抢用失败 ⇒ `UNAVAILABLE` 且 `ended` 不含该 `viewKey`）；读取用 `ReflectionTestUtils` 取私有字段，**未加生产 getter、未加新依赖**。
- m1 红日志的 `viewKey` 含 `\0` 分隔符，`grep` 会判二进制，需 `grep -a`；**m1 之前的 4/2、14/0、4/0、8/0 四份日志全部保留**，对应旧代码版本，结论未被覆盖。
- 末版 patch：`<仓外证据>/cancel-lease-health.patch` 452 行、恰好 2 个文件；在 HEAD 纯净副本上 `git apply --check -p1` **OK**、`patch -p1 --dry-run` **OK**；**整理时**该补丁独占一个工作树（`git status` 只有这两项），现已被主控合入 `<主仓>` 工作树（见文首状态）。

## 5. 边界与未验证（保留，不扩新任务）

1. **被中断的 run 行为有一处收紧（有意）**：那次 `acquire` 会留下 `UNAVAILABLE` 墓碑，该 `(key, owner)` 要等**成功 acquire 或成功 release** 才清除。原先「1s 内 PING 自证后 A 又会变可写」，现在 A 保持只读（这正是「不得继续本次写/不得放宽 fencing」）。
2. **与 W-30 交棒的兼容性：先静态、后组合实跑（本地）**：交棒路径是「自抢租约（`acquireFenced`）→ `verifyHeld` → 再写」，成功 acquire/`verifyHeld` HELD 都会清墓碑，因此终态不会被这道墓碑挡住。
   - **复审当时未跑组合**：主仓定向 `12/12`（01:25）**早于** W-30 六个文件写入主仓的时刻（01:29:53），不覆盖组合。
   - **主控后来串行跑了组合**：`CancelFinalizeHandoffTest` 9 + `LeaseAuthorityProbeFailClosedTest` 2 + `LeaseLocalInterruptHealthTest` 5 = **16/16，Failures 0 / Errors 0，BUILD SUCCESS**（`<仓外证据>/final-w30/backend-combined-targeted.log`，编译源为 `<主仓>/backend`）。这消除了"两个补丁不能同跑"的疑虑。
   - **但不能升格为真实环境证明**：该组合仍是**替身/单测**环境（`FakeLeaseRedis`、InMemory 归档、单线程池），且 W-30 那 9 条的 `goDown`/`failRenew` 走**真实故障**路径，`LeaseLocalInterruptHealthTest` 走**复刻的 cause 链**——两者各自成立，仍不含「真实 Lettuce/Redis 中断发生在交棒链上」这一格。该格与 W-30 完整 E2E 一起，**必须由 GitHub 证明**。
3. **m2 未处理**（按指示保留）：墓碑没有 TTL；若中断落在非取消终态（`fail`/park）路径就没有交棒去清墓碑，理论上快照可能停在 RUNNING/SUSPENDED 直到下次 resume/重启的 `acquire`。窗口窄（毫秒级 + 非取消），方向是 fail-closed。
4. **m4 未处理**：`QueryTimeoutException` / CROSSSLOT 未单列用例；探针节流无多线程用例；「局部中断墓碑 + 交棒」无组合用例。
5. **真 Redis / 集群多实例 / `@real` 未验**：真实 `RedisCommandInterruptedException`、多实例下的墓碑与 1s 探针、并发风暴下的探针频率，均只有单实例 + 替身（`FakeLeaseRedis`）证据。
6. **误判方向可接受但已登记**：若某包装层把 `InterruptedException` 放进 cause 链而设施其实真坏，会被判「本次不宣布设施坏」；对**当前** run 仍有墓碑/只读保护，对**其它** run 至多让真实 Redis 命令失败（抛 `AiStateUnavailableException`，不是静默成功）。
7. **探针节流代价**：极端情形下（持续被中断的线程反复调 `authority`）会每拍一次 PING；lettuce 在中断下是快失败，量级有界。
8. **本轮整理本身未验证任何新行为**：本文不改代码、不重跑测试；所有数字来自上表日志。

## 6. 素材来源（会话证据）

| 会话日志 | 提供内容 |
|---|---|
| `<会话证据>/agents/main/tasks/agent-l31h4chx/output.log` | 根因与调用链、`isInterrupted()==false`、5 个调用面、红 4/2 与绿 14/14、4/4 复跑、边界 |
| `<会话证据>/agents/main/tasks/agent-r5fysfb2/output.log` | 相邻两风险点 8/8（2+6）、patch 逐字节可复现、`LeaseService.java` sha1 `c5c136bb…` |
| `<会话证据>/agents/main/tasks/agent-dvw60h67/output.log` | m1 最小条件化（`fenceKey != null`）、m1 红 5/1 → 绿 7/7、m3 注释口径纠正、末版 patch 452 行 |

组合证据（非会话日志，主控交付）：`<仓外证据>/final-w30/backend-combined-targeted.log` —— 主仓工作树串行跑 **16/16，BUILD SUCCESS**（9 + 2 + 5）；本文 §3 末行与 §5 第 2 项据此登记，**仅本地替身/单测组合**。

> 三份会话日志均为**只读文本**；未读取任何 `.env`/密钥文件。日志里的路径按本目录统一口径改写为占位符。

> 三份会话日志均为**只读文本**；未读取任何 `.env`/密钥文件。日志里的路径按本目录统一口径改写为占位符。
