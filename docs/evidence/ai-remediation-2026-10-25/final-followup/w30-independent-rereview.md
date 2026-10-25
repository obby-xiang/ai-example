> 来源=<仓外证据>/w30-independent-rereview.md（2026-10-26 归档）· W-30 窄复审 · 第二轮（APPROVED，含 13/13 实跑）；属历史证据，不代替终稿/现行基线
# W-30 取消收尾补丁 · 第二轮窄复审（REVIEW ROUND 2）

- 基线：`1d7b1f0872769987c89a5083ef1aa3f15fb33183`（主仓 1d7b1f0）
- 候选：隔离工作树 `<仓外证据>/w30-repro` 的**未提交**修订（无可冒认 commit）；`w30-fix.patch` 6 文件（4 改 2 新增）
- 上一轮：`<仓外证据>/w30-independent-review.md`（REQUEST_CHANGES，I-1/I-2/I-3/I-4 + M-1/M-3/M-6）
- 方式：只读；未改产品/测试、未提交、未派生 reviewer。**实跑 2 个聚焦测试类**（命令与结果见 §7）
- 本轮报告的最终 sha256（2026-10-26 01:29 +08:00，Maven 运行后复算未变）：

| 文件 | sha256（最终） | 相对第一轮 |
|---|---|---|
| `gate/ConfirmGate.java` | `47774e21c0ec078e604a8ddac6e8eef5327e603c21b6b88d2e1305a627f6d9bf` | 未变 |
| `run/ResilientChatService.java` | `97e0855428d16a203b9191117d8f8ff8e60bd3a5b33f4c62011a03ed6856a725` | 已变 |
| `run/ResumeService.java` | `8d000633b8793081e914adf6e1600a2b341d8e5c6961dd74a6b0fd59694bfa33` | 已变 |
| `run/SseChatEmitter.java` | `a50ff5a6a93d4dd3a3ac6869cf7cabdf9017228f73c5f64b68210f8dea6f9eb7` | 新增改动 |
| `run/CancelledRunFinalizer.java` | `685fbbd9df607284484efb5a30a10830ccf80f0a2170929938d7bf0511f13c1c` | 已变 |
| `test/.../CancelFinalizeHandoffTest.java` | `4de5f329af3538b17ba532ae2751bbb2fe5ca1a05c522fe431d7a795d17c1fb8` | 已变 |

**结论：APPROVED（Ready to merge: Yes）**。上一轮 4 个 Important 全部实质闭合；本轮**无 Critical、无 Important**，仅 4 条非阻塞 Minor。合入后的放行条件仍是 GitHub E2E（见 Declined to judge）。

## Strengths

1. **I-1 真的双守卫，且交棒入口在取任何锁之前就判终态。** `ResumeService.java:1279-1284` 用 `RunSnapshot.isTerminal` 且排除 CANCELLED 后直接 `ALREADY_TERMINAL`（不取会话门、不取租约、不写）；`ResilientChatService.java:1399-1407` 在收尾体内再判一次（覆盖"其它调用方/旧路径把终态交进 cancelParked"）。测试 `CancelFinalizeHandoffTest.java:260-281` 同时打两个入口（`cancelConfirmed` + `cancelParked`），断言 DONE/FAILED 不被改写、也不补 done。这正是我上一轮 I-1 要求的最小反例。
2. **I-2 判据换成了"归档里有没有终帧"，且真的接生产 FrameStore。** `SseChatEmitter.java:755-777` 调 `frames.readFromWithStatus(runId, 0L, 0)`，生产 `RedisFrameStore.readFromWithStatus` 对末号探测失败（`streamFramesAbove` 的 `degraded = !tail.ok()`）和 XRANGE 失败都回 `degraded=true`；`degraded`/异常/未装配一律 `null`，调用方**没有**把它折叠成"没有 done"：`cancelTerminalLocked` 对 `null` 明确 WARN 并按未归档继续收尾（`ResilientChatService.java:1388-1396`），`cancelConfirmed` 对 `null` 不返回 ALREADY_TERMINAL、走 `cancelParked` 并在收尾后复核归档（`ResumeService.java:1293-1296`、`:1328-1339`）。上一轮不安全的 `alreadyCancelled && cancelPendings==0` 早返回已删除。`CANCELLED + 无 done` 可补且只补一次（测试 `:287-304`，第二次 `ALREADY_TERMINAL`）——I-2-C（CANCELLED 时早返回导致残留分支不可达）也随之消失：现在 CANCELLED 无 done 会走完整收尾，`cancelPendings` 无条件执行。
3. **I-3 的准入与释放纪律正确，且没有释放别人的租约/锁。** `cancelConfirmed` 先 `resuming.putIfAbsent`（`ResumeService.java:1263-1266`，IN_PROGRESS 在 try 之外返回、不误删），会话门用一次性 token 取（`:1300-1308`）、finally 同 token 精确释放（`:1346-1354`，`SessionGate.release(session, run, token)` 本身是 token 精确匹配，旧 token 迟到释放为 no-op）；租约 `acquireFenced` + `verifyHeld`（`:1310-1326`），`fresh=false` 一律不释放（`:1355-1358`），因此**不动原驱动/等待方那一任**。代码注释也如实写明"同 owner 重入是同一 epoch，不声称隔离了原驱动"（`ResumeService.java:1246-1255`），没有把 monitor 说成万能锁。
4. **I-4 的证据是真的，不是漂亮报告。** 两份基线红日志的失败原因与行为对应：`w30-red-baseline.log` = `expected "CANCELLED" but was "SUSPENDED"`（W-30 形态）、`w30-red-baseline-terminal-guard.log` = `expected "DONE" but was "CANCELLED"`（`RunStore#save` 不查原状态）；`w30-red-review-fixes.log` 是**上一版补丁**的 9 跑 7 败，失败点正好是我上一轮的四条（`IN_PROGRESS`/`SESSION_BUSY`/`FINALIZED`/`ALREADY_TERMINAL` 的 expected vs actual，`ResumeService` 会话门零交互）。非调用线程断言是真实的：done 落档线程记录在 `InMemoryRunStore.appendEvent`（`CancelFinalizeHandoffTest.java:133-140`），断言等于真实单线程池 `cancel-finalize-handoff`（`:225-228`）。InMemory 与生产的差别（两键 `acquire` vs 三键 `acquireFenced`）已被用例显式对齐（`:125-131`）并在类注与报告 §8 中如实标注。
5. **现场纠偏到位。** 类注（`CancelFinalizeHandoffTest.java:49-55`）、`CancelledRunFinalizer.java:26-38`、报告 §0 都写对了：W-30 **没有** park，取消来自 `ConfirmGate#await` 循环，真正覆盖现场的是 `cancelTerminal` 的 persist-denied 交棒；park 分支两个用例明确标注"不是 W-30 的时序"（`:397`）。根因也不再写成"Redis 故障"，而是"一次在飞 Redis 命令被打断 → 整实例健康位 + 1s 节流"。我按 `boot.log:6724-6749` 复核：无 `approval_park`/`tool_call_park`，拒写在 `6740/6741`，与注释一致。
6. **M-3/M-6 闭合。** `submit` 的两条失败分支都走 `rescheduleOrGiveUp`（`CancelledRunFinalizer.java:159-172`），受 `maxDispatchRetries` 约束（测试 `:383-395`）；`NOT_CANCELLED + 非终态` 返回 `CANCEL_FACT_UNCONFIRMED`（`ResumeService.java:1289-1293`）并由交棒侧重试（测试 `:310-334`），不再假成功、不驱动。
7. **新方法不碰协议与序号。** `terminalFrameArchived()` 纯读：不取号、不 `append`、不加帧型；相邻回归 425 条（含 `ChatTurnFrameSequenceConformanceTest`、`CancelLedgerConformanceTest`、全部 `SseChatEmitter*Test`）与我的实跑都通过。
8. **Spring 装配不成环。** `CancelledRunFinalizer` 用 `ObjectProvider<ResumeService>` 懒获取，构造只依赖 provider/executor/properties；`@SpringBootTest` 全上下文能启动（`w30-green-spring-context-r2.log`，Hikari + Started），临时探针也跑通（`w30-wiring-probe.log`，1/1）。`w30-wiring-probe.log` 里的 "unresolvable circular reference" DEBUG 是 `ToolRegistry` 早期扫描的既有噪声（基线全量日志同样有 649 处），不是本补丁引入。

## Declined to judge（明确搁置的边界）

1. **GitHub 上的 W-30 E2E**：真实 controller 先读 RUNNING、真实 Reactor dispose/线程中断、真实 `RedisCommandInterruptedException`、20s 内转 CANCELLED、迟到回灌 409、同会话下一轮。本机只有单测/替身。
2. **真实 Redis Stream 的"done 恰 1 条且是最后一条"**：本机用 InMemory 归档；生产 `RedisFrameStore` 的 `degraded` 只在该实现上出现，未被任何单测触发。
3. **持续"读降级但写可用"的极端组合**：此时 `terminalFrameArchived` 恒 `null`，交棒会按未归档重收尾，可能重复 `done`（基线同样可能重复）。我判定为可接受的取舍（"无终帧"比"重复终帧"更糟），未按缺陷立项。
4. **多实例窗口**：`LEASE_HELD_ELSEWHERE` 重试期间另一实例合法跑成 DONE 并释放租约 —— 只有单测的 DONE 终态保护覆盖，真实集群接管未验。
5. **`aiResumeTrigger` 池在取消风暴下饱和**（交棒与续跑共用池）：未压测。
6. **首轮驱动（非 resume）的会话门接管语义**：交棒用新 token 接管同 runId 的门、原驱动 finally 的旧 token 释放变 no-op —— 这是 `SessionGate` 既有的"同 runId 新 token = 接管"设计，未单测，也未在真实首轮并发下验证。
7. **生产全上下文对"最终字节"的装载**：`w30-green-spring-context-r2.log` 与临时探针都早于最后三处源码 mtime（01:14:24/01:14:29/01:15:12）数秒；我的实跑证明最终字节可编译且 13/13 通过，但**没有**再跑一次 `@SpringBootTest`（限制 2 个类，未扩）。
8. **既有并发帧序**：`ConfirmGate#cancel()`（工具线程）与驱动/交棒 `cancelTerminal` 的帧竞争，理论上可能出现业务帧排在 `done` 之后。基线已存在（W-30 里该帧被写门丢弃），本轮不扩。
9. **`LeaseService.markFacilityUnhealthy` 的触发面**（是否对 `RedisCommandInterruptedException` 不计设施故障）：实现者明确留给另一专项，我同意不在本补丁裁决。
10. 多用户、真 PG、跨供应商等既有未验边界（按要求不扩）。

## Critical

无。

## Important

无（上一轮 I-1 / I-2 / I-3 / I-4 均闭合，依据见 Strengths 1-4）。

## Minor（非阻塞）

- **m1 归档已存在时不再收敛残留条目**：`ResilientChatService.java:1388-1391` 在 `archived == TRUE` 时直接 return，跳过 `:1411-1412` 的 `pendingsLenient` + `cancelPendings`。
  影响：若首次收尾时 `pendingsLenient` 读失败（返回空表，`RunStore.java:1602`）而 `done` 帧写入成功，残留 PENDING 条目此后再无收敛路径（基线可靠"再收尾一次"自愈）。需要"pending 读失败 + 帧写成功"的部分故障，W-30 现场不涉及。
  最小修复：`archived == TRUE` 且快照为 CANCELLED 时仍执行一次 `store.cancelPendings(runId)`，只收敛条目，不重写快照、不重发终帧。
- **m2 终帧探测是全窗口读**：`SseChatEmitter.java:761` 用 `readFromWithStatus(runId, 0L, 0)`，生产按窗口（默认 6000 帧）`XRANGE` + 逐帧反序列化，每次取消走两次（驱动 + 交棒）。同类既有用法 `ensureToolStart`（`:1182`）有先例，故降为 Minor。若只需"最后一条是否终帧"，可复用 `tailFrameIsTerminal`（`:724`）的 O(1) 尾探；保留全窗口只在确需"历史任意 error"时。
- **m3 降级分支无单测**：`degraded ⇒ null` 的语义、以及 `null` 下"继续收尾"的行为，目前只有代码阅读与报告 §8.2 的 E2E 声明；用一个返回 `ReadResult(List.of(), true)` 的自定义 `FrameStore` 即可在单测层钉住，无需真 Redis。
- **m4 红测/日志的卫生**：`w30-red-baseline-tests/CancelFinalizeDeniedRedTest.java` 直接 `new ResilientChatService` 且**不注入** finalizer，因此它在修复树上也永远红（`w30-green-redtest-same.log`，00:59，仍在 `_p2-review` 目录，命名像"绿"）；另外该文件对 `CancellationRegistry` 的 stub 打在一个被丢弃的 mock 上（`when(mock(...).cancelState(...))`），无实际作用。报告未把它列为"修复树绿"，事实无误，但建议把这份过期日志改名/加注，避免后续审计误读。另：上一版 4 号用例里的"done 必须最后一条"断言在本轮测试里被删除了（W-30 并发形态下仍值得在 E2E 断言）。
- **m5 `inFlight` 在 `Error` 下泄漏**（沿用上一轮未改）：`CancelledRunFinalizer.java:183-191` 只 catch `RuntimeException`；若 `attempt` 抛 `Error`，`inFlight`/`attempts` 不会清理，该 runId 本进程内不再能交棒。仅 JVM 级错误，非现实主路径。

## Recommendations

1. 合入前只做一件事：在 GitHub 跑 W-30 E2E，并在该用例里额外断言**归档最后一条是 `done` 且恰 1 条**（补上 m4 删掉的断言）。
2. 若本批还想再稳一点，做 m1（一行 `cancelPendings`）与 m3（一个自造 `FrameStore` 的单测）——都不需要新依赖，也不需要真 Redis。
3. 合入后观察 `取消收尾在 N 次交棒后仍未落定` 的 ERROR 与 `取消终帧归档状态不可读` 的 WARN 计数：前者是 E2E 之外唯一的"仍有死轮"信号，后者是 m3/Declined-3 的真实发生率。
4. m2 的窗口读若在压测中出现在取消延迟里，再换成尾探；现在不必改。

## Assessment

**APPROVED — Ready to merge: Yes。**

理由：上一轮 4 个 Important 均已用"代码 + 真实反例测试 + 行为对应的红日志"闭合，本轮无 Critical/Important 残留；新增的 `terminalFrameArchived` 不改变帧协议、序号或回放兼容，装配不成环；我在最终字节上实跑 `CancelFinalizeHandoffTest`（9/9）与相邻但未被实现者 425 条回归覆盖的 `ResilientChatStaleDriverGuardTest`（4/4），合计 **13/13、BUILD SUCCESS**。4 条 Minor 均为窄边界或证据卫生，不构成阻塞；合入后的放行条件 = Declined 第 1/2 项由 GitHub E2E/CI 证明。

## §7 本轮实跑（命令与真实结果）

```
cd <仓外证据>/w30-repro/backend
"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -Dtest=CancelFinalizeHandoffTest,ResilientChatStaleDriverGuardTest -DfailIfNoTests=false test
```
- 结果：`Tests run: 9, Failures: 0`（CancelFinalizeHandoffTest）+ `Tests run: 4, Failures: 0`（ResilientChatStaleDriverGuardTest）= **13/13，BUILD SUCCESS**（21s；`target/classes` 显示 "Nothing to compile - all classes are up to date"，即主类已是最新字节；测试源码重编译 448 个）。
- 日志：`<仓外证据>/w30-rereview-tests.log`
- 未跑全量、未 `clean`、未改任何文件；`git status` 仍是 4 改 2 新增，与补丁一致。

## §8 日志计数核对（本轮实际读取）

| 日志 | 计数 | 失败/结论是否对应行为 |
|---|---|---|
| `w30-red-baseline.log` | 1 run / 1 fail | `expected "CANCELLED" but was "SUSPENDED"`，W-30 同形，基线 |
| `w30-red-baseline-terminal-guard.log` | 1 / 1 fail | `expected "DONE" but was "CANCELLED"`，I-1 基线 |
| `w30-red-review-fixes.log` | 9 / 7 fail | 上一版补丁；`IN_PROGRESS`、`SESSION_BUSY`、`FINALIZED`、`ALREADY_TERMINAL` 的 expected/actual 与 I-1/I-2/I-3/M-6 一一对应 |
| `w30-green-new-r2.log` | 9 / 0 | 与我的实跑一致（同一类，最终字节 13/13 中的 9 条） |
| `w30-green-baseline-terminal-guard.log` | 10 / 0 | 9 条新用例 + 基线红用例在修复树转绿 |
| `w30-green-adjacent-r2.log` | 425 / 0 | 22 类相邻；**不含** `ResilientChatStaleDriverGuardTest`（我已补跑） |
| `w30-green-spring-context-r2.log` | 4 / 0 | `@SpringBootTest` 全上下文 "Started ... in 10.87 seconds" |
| `w30-wiring-probe.log` | 1 / 0 | 临时探针（已删，不进补丁）；证明 bean 存在并注入两处 setter |
| `w30-green-redtest-same.log` | 5 / 1 fail | **过期中间产物**：`CancelFinalizeDeniedRedTest` 在补丁树仍红（该探针不注入 finalizer，永远不会绿）；报告未引用它，见 Minor m4 |
