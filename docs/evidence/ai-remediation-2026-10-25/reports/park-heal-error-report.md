> 来源=<仓外证据>/park-heal-error-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# park 自愈对账错误路径（I1：订阅泄漏）—— 修复报告

日期：2026-10-25　基线：main `ae883fb`（工作树含前序未提交批次）　隔离副本：`<验证副本>/_verify-20261025-park-heal-error/backend`
复审输入：`<仓外证据>/park-latch-wake-rereview.md` § I1

## 0. 结论

`AiController.events` 在 `replayAndAttach` **之后**做"已决但无驱动方"的自愈对账，而对账
（`ConfirmGate.triggerResumeForParked` → `RunStore.get`）在 Redis 故障时抛
`AiStateUnavailableException`。异常从 controller 传出 ⇒ Spring 回 500、`SseEmitter` 永不交给容器 ⇒
它的 `onCompletion/onTimeout/onError` 永不触发 ⇒ 刚挂上的订阅留在 `SseChatEmitter.subscribers`
里到进程结束（`hasLiveSubscriber` 常真、`RunRegistry.recycleIfIdle` 永不放行、唤醒/回放继续往死订阅排队）。

修法保持"就地降级"口径（与相邻的 `stillWaitingForInput` 读失败完全同形）：
**catch `RuntimeException` → `log.warn` → `out.completeSubscriptions()` → 照常返回 200 + emitter**。

## 1. 缺陷链（当前 main 逐行）

1. `AiController.java:757-759`：`SseEmitter emitter = new SseEmitter(-1L)` → `runRegistry.of(runId)` →
   `out.replayAndAttach(emitter, lastSeq, terminal)`。`replayAndAttach` 内部 `attachSubscription(sub)`
   （`SseChatEmitter.java:673`）把订阅登记进 `subscribers`，并把三个生命周期回调**挂在调用方给的 emitter 上**
   （`SseChatEmitter.java:543-550`）。
2. `AiController.java:766-790`：park 且快照仍 `SUSPENDED`；条目已全部结算 ⇒ 走自愈对账
   `this.confirmGate.triggerResumeForParked(runId)`（`:776`）。
3. `ConfirmGate.triggerResumeForParked` → `triggerResume` → `this.store.get(runId)`
   （`ConfirmGate.java:1688`）；`RunStore.get` 在 Redis 故障时抛 `AiStateUnavailableException`
   （`RunStore.java:991-998` 一类分类路径）。
4. 该异常在 `events` 里**没有任何 catch**，且此刻 `emitter` 还没 `return` 给容器：
   - 清理回调只在容器持有 emitter 后才会被回调 ⇒ 永不触发 ⇒ `closeSubscription` never runs；
   - 于是 `subscribers` 里留下一个"活着"的死订阅（复审批注的进程级泄漏）。

**RED 证据正是这条链**（`park-heal-red.log`，修复前源码 + 新用例）：

```
com.example.configmgr.common.AiStateUnavailableException: Redis 不可用：快照读失败
	at com.example.configmgr.ai.gate.ConfirmGate.triggerResumeForParked(ConfirmGate.java:1984)
	at com.example.configmgr.ai.web.AiController.events(AiController.java:776)
	at com.example.configmgr.ai.web.AiControllerEventsParkCloseTest.selfHealFailureAfterAttachClosesTheStreamInsteadOfLeakingTheSubscriber(AiControllerEventsParkCloseTest.java:128)
```

## 2. 修法（`AiController.java`，仅此一个生产文件）

```java
try {
    ConfirmGate.ResumeTrigger healed = this.confirmGate.triggerResumeForParked(runId);
    log.debug("reattach 已决无驱动方的自愈对账 runId={} outcome={}", runId,
            healed == null ? null : healed.outcome());
}
catch (RuntimeException ex) {
    log.warn("reattach 自愈对账失败 runId={}（收流，不泄漏订阅）：{}", runId, ex.getMessage());
    out.completeSubscriptions();
}
```

三个取舍，都有明确理由（不是随手选型）：

1. **不上抛、不改成 503**：上抛就是本缺陷本身（emitter 不交付 ⇒ 订阅泄漏）。且此时回放帧已经入队到这条连接，
   再回 503 会让"帧已发一半"的连接被换成错误 JSON，语义更乱。
2. **`out.completeSubscriptions()` 而不是 `emitter.completeWithError(...)`**：与相邻的
   `stillWaitingForInput` 读失败分支（`AiController.java:767-768`）**逐字同口径**；且走
   `SseChatEmitter` 的收尾路径（`completeWhenDrained → completeOne → closeSubscription + emitter.complete()`），
   同时完成"摘订阅 + 退唤醒订阅 + 队列计数归零 + emitter 收尾"。`completeWithError` 会绕过这条清理路径，
   且 emitter 尚未 `initialize`（容器侧），在"返回前就 completeWithError"上的容器行为依赖 Spring 版本，
   不值得引入。"返回前 `complete`"本身不是新形态：park 收流（`stillWaitingForInput` → `completeSubscriptions`）
   一直就在 `return` 之前完成 emitter，是本仓已上线的既有语义。
3. **不补 `error` 帧**：`SseChatEmitter.error(code,message)` 是**归档里的终态帧**。这轮已决、只差驱动方，
   后续成功续跑还要发 `done`；一旦把 `error` 落档，这条轮之后的所有重挂回放都会被
   `replayAndAttachWithStatus` 判成 `archivedTerminal` 而当场收流，真正的 `done` 反而漏掉。
   客户端拿到的是"流结束"（既有 park 收流语义），由前端按既有重挂/重试收敛。
4. **不静默吞**：`log.warn` 带 runId 与异常消息；不是 debug、不是空 catch（与 `stillWaitingForInput` 一致）。
   只 catch `RuntimeException`（`AiStateUnavailableException extends CodedIllegalStateException`），不吞 `Error`。

**成功路径逐字未动**：`healed` 的 debug 日志、`null` 处理、`ALREADY_RUNNING / LEASE_HELD_ELSEWHERE`
等既有跳过口径全部原样；PENDING 分支（`completeSubscriptions`）与非 park 路径完全未改。

## 3. 改动清单（精确到文件）

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | `events` 对账段包 `try/catch (RuntimeException)`：`log.warn` + `out.completeSubscriptions()`；补 6 行注释说明泄漏链与"为什么不补 error 帧"（+18 −3 行）|
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerEventsParkCloseTest.java` | 新增用例 `selfHealFailureAfterAttachClosesTheStreamInsteadOfLeakingTheSubscriber`；3 个 import（`AiStateUnavailableException`、`ResponseEntity`、`when`）；类 javadoc 补该错误路径的证红说明（+31 −1 行）|

未改：`SseChatEmitter.java`、`ConfirmGate.java`、`RunStore.java`、`GlobalExceptionHandler`、`main` 仓任何文件。
不需要第二个 error-mapping 文件：`completeSubscriptions` 是既有公开 API，收尾语义全在 `SseChatEmitter` 内。

## 4. 测试设计（TDD 车辆 = 既有 harness）

`AiControllerEventsParkCloseTest` 只有真 `InMemoryRunStore` + 真 `RunRegistry`/`SseChatEmitter`，
`ConfirmGate` 是 mock —— 正好能确定性地把"对账抛异常"注入在**订阅已挂上之后**，并用真实
`subscriberCount()` 读出泄漏：

```java
this.store.putPending(RUN_ID, pending(PendingToolCall.FRONTEND_RESULT)); // 已决 ⇒ 走自愈对账
this.registry.of(RUN_ID).attach(attached);                              // 同轮另有一条活连接
when(this.confirmGate.triggerResumeForParked(RUN_ID))
        .thenThrow(new AiStateUnavailableException("Redis 不可用：快照读失败"));

ResponseEntity<?> response = this.controller.events(RUN_ID, null);

assertThat(response.getStatusCode().value()).isEqualTo(200);   // 不是 500
assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
verify(this.confirmGate).triggerResumeForParked(RUN_ID);       // 确实走了对账（不是被跳过）
assertThat(this.registry.of(RUN_ID).subscriberCount()).isZero(); // 不泄漏
```

确定性：单线程、mock 抛固定异常、无 Redis/无定时、无线程调度介入（该 harness 的两条订阅队列都为空，
`completeWhenDrained` 在 CAS 抢到 `draining` 且队空时就地 `completeOne`，不经过投递池 ⇒ 同步可断言；
与既有第二个用例同一路径）。

## 5. RED → GREEN

环境：IntelliJ IDEA 自带 Maven `<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn`，
Java 21，`-o`（离线）、`-B`、`-Dspring.data.redis.port=1`（无 Redis）、**未跑 `clean`**。

| 阶段 | 命令（`backend/` 下） | 结果 |
| --- | --- | --- |
| **RED** | `mvn -o -B -Dspring.data.redis.port=1 -Dtest=AiControllerEventsParkCloseTest test` | `Tests run: 3, Failures: 0, Errors: 1` — **BUILD FAILURE**；唯一红 = 新用例，`AiStateUnavailableException` 从 `AiController.events(AiController.java:776)` 传出（栈见 §1）；同 class 另 2 个用例绿（既有行为未被本用例破坏）|
| **GREEN** | `mvn -o -B -Dspring.data.redis.port=1 -Dtest='AiControllerEventsParkCloseTest,AiControllerParkWiringTest,AiControllerEventsStateUnavailableTest,AiControllerSuspendedTouchTest' test` | **`Tests run: 14, Failures: 0, Errors: 0` — BUILD SUCCESS**：ParkClose 3/3（含新用例）、ParkWiring 4/4、EventsStateUnavailable 1/1、SuspendedTouch 6/6 |

- 日志：`<仓外证据>/park-heal-red.log`（= `park-heal-baseline.log`，改名复制作 RED 证据）、
  `<仓外证据>/park-heal-green.log`。
- GREEN 运行里能看到降级真的发生（不是被 mock 断言掩盖）：
  ```
  WARN com.example.configmgr.ai.web.AiController -- reattach 自愈对账失败 runId=run-park-close（收流，不泄漏订阅）：Redis 不可用：快照读失败
  ```
- 相邻成功语义同时被钉住：`settledParkedRunKeepsTheReattachedStreamOpenForTheResumedTerminalFrames`
  仍断言 `subscriberCount()==2` 且 `verify(triggerResumeForParked)`（成功自愈不变）；
  `stillPendingParkedRunClosesTheReattachedStreamAfterReplay` 仍断言 `0` 且 `never()`（PENDING 分支不变）。

## 6. 补丁（对当前 main 的工作树内容）

- 文件：`<仓外证据>/park-heal-error.patch`（99 行，2 文件，`a/backend/...` `b/backend/...` 路径，`git apply -p1` 口径）
- 生成方式：仓外 scratch git 仓，base = **当前 main 工作树**的两个文件（`AiController.java` 与测试，
  `diff` 校验与隔离副本启动时逐字节一致），target = 隔离副本改后文件；`git diff`（`core.autocrlf=false`，保留 CRLF）。
- 冒烟：在 scratch 仓 base 状态跑 `git apply --check` → OK；`git apply` 后两文件与隔离副本 `diff -q` 完全一致（往返验证）。
- 应用方式（在 main 仓根）：
  ```
  git apply -p1 <仓外证据>/park-heal-error.patch
  ```
  main 仓**未被本次工作改动**（两次 `git status` 中这两个文件的 mtime 仍是 17:03，早于本次编辑）。

## 7. 影响与边界（如实）

- **`completeSubscriptions()` 收的是该 run 的当前全部订阅**，不只是本次新建的那一条：与本轮其它重挂连接
  一起 EOF（与 `stillWaitingForInput` 读失败分支完全同口径）。代价是这些连接提前收流，收益是不留死订阅；
  前端按既有重挂/重试收敛。若将来要"只摘新订阅"，需要在 `SseChatEmitter` 增加按订阅摘除的 API——
  那是另一个文件的范围，本轮按"最小改动"不做。
- **失败仍对调用方可见**：HTTP 200 + 流结束，不是错误 JSON；前端无法从这条响应知道"对账失败"。
  这是与相邻分支一致的既有降级口径；日志 `WARN` 是观测面。
- **未验证**：真实 Redis 故障下的端到端行为（本轮是 controller 边界的 mock 注入，与复审指出的
  "自愈无端到端接线测试"同一处缺口）；未跑全量/相邻大套件、未跑 `clean`、未起实例、未跑 `@real` E2E。
- **未处理（复审 Observation，非本轮范围）**：`ConfirmGate.java:602-604` 的 CANCELLED-in-park 早退
  仍未 `closeCancelHandle`（pre-existing）。
- **未改锁语义**：本修复只在 error 分支加收流，不触碰 latch / 驱动方计数 / `submitLock` 设计，
  与"不重做 latch"的边界一致。
