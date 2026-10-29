# Task 3 报告：全部异步与恢复入口

- 状态：**完成**
- 代码提交：`1da3c128`（`feat(ai): 全部异步与恢复入口接入租户身份上下文（租户任务3）`）
- 基线：`0787b9fd`（Task 2 会话销毁组已并入后的主仓 HEAD）
- 范围：仅 Task 3 指定的 11 个类 + 对应测试 + 新增 `RunIdentityScope`；未改
  `RunStore` / `LeaseService` / `SessionGate` / `AiSessionService` / `AiSessionPurge*` /
  `AiController` / 前端 / 业务 / AI 模型配置。未推送。

## 交付内容

| 项 | 落点 |
| --- | --- |
| 身份作用域基座 | `ai/tenant/RunIdentityScope.java`：按记录 `tenantId/userId` 建立可关闭作用域；`0/0`（旧记录）返回空作用域，**不伪造默认租户/用户**；`userId=-1` 可用 |
| 普通线程池 | `AiExecutorConfig`：`aiRunExecutor`/`aiResumeTrigger` 保持 MDC+TTL 组合装饰；`aiStreamWatchdog` 改为 `TtlExecutors` 包装的调度器（提交抓快照、每拍回放） |
| 定时续跑 / 启动恢复 | `StartupResumeRunner`：启动扫描与运行期死轮扫描在调 `resume` 前读取该轮 `RunSnapshot`，按记录身份 `useContext`；作用域只包一次续跑，循环不继承 |
| 续跑交棒 | `ResumeService`：`resumeInternal` / `retryModelStepInternal` 读出快照后建立作用域并在 finally 关闭；挂起池任务用 `withRunIdentity` 再建一次；`cancelConfirmed` 同样按快照恢复 |
| HITL / 重试 | `ConfirmGate`：死卡兜底派发带入快照身份；租约重试、派发有界重试、park 后前端回执到期三条定时任务均"读快照 → 作用域 → 动作"（读快照的异常仍落在各自 catch 内，有界周期不断链） |
| 取消收尾 | `CancelledRunFinalizer`：新增 `RunStore` 读取面（Spring 注入；旧测试构造保留），交棒执行体按快照身份调用 `cancelConfirmed` |
| Reactor / 官方工具 | `ToolExecutionEnvelope` 增加 `tenantId/userId`（快照/Map 双形态往返）；`SpToolCallingManager` 先解析快照→信封身份再执行整批工具，工具独立超时执行器提交时抓取、执行线程重放 |
| SSE | `SseChatEmitter`：构造时抓身份，跨实例唤醒创建的写出器按 `ai:run:<runId>` 快照懒解析一次；投递 drain、弹性写出池、唤醒补帧三条异步路径均建立作用域 |
| 看门狗 / 保活 | `StreamWatchdog` / `TurnKeepalive`：建立时抓本轮身份，每个调度节拍 `useContext` 后执行 |
| 摘要旁路 | `ContextSummaryClient`：提交线程抓身份，摘要池线程重新建立作用域 |

## 覆盖清单（逐项，不只两个池）

| 异步/恢复入口 | 线程 | 身份来源 | 处理 |
| --- | --- | --- | --- |
| `aiRunExecutor`（首轮驱动） | 平台池 | 请求 TTL | 装饰器（Task 1 基座） |
| `aiResumeTrigger`（死卡/取消/重试派发） | 平台池 | RunSnapshot | TTL + `ConfirmGate`/`CancelledRunFinalizer` 显式作用域 |
| `suspendExecutor`（续跑驱动） | 平台池 | RunSnapshot | TTL + `ResumeService.withRunIdentity` |
| Reactor 上游事件回调 | Netty event loop | 本轮（驱动线程） | 每个事件 `RunIdentityScope.open` |
| 官方工具执行（`executeToolCalls`） | 上游线程 | RunSnapshot → 信封 | 支持恢复的整批作用域 |
| 工具独立超时执行器 | `ai-tool-timeout-*` | 提交线程身份 | 提交抓取 + 执行重放 |
| SSE 投递池 | `sse-delivery-*` | 构造身份 → 快照 | drain 作用域 |
| SSE 弹性写出池 | shared writer pool | 本轮身份 | 单帧写出作用域 |
| SSE 唤醒补帧 | Redis 订阅线程 | 快照 | `onWake` 作用域 |
| `aiStreamWatchdog` | 调度线程 | 建立时身份 | TTL + 每拍作用域 |
| `TurnKeepalive` 节拍 | 同一调度器 | 建立时身份 | 每拍作用域 |
| 摘要池 | `ctx-summary-*` | 提交线程 | 提交抓取 + 执行重放 |
| 启动扫描 | `ai-startup-resume` | RunSnapshot | 每轮作用域 |
| 运行期死轮扫描 | `ai-runtime-sweep` | RunSnapshot | 每轮作用域 |
| 租约重试 / 派发重试 / 前端回执到期 | `ai-resume-lease-retry` | RunSnapshot | 每任务作用域 |
| 取消收尾交棒与重试 | 触发池 / `ai-cancel-finalize-retry` | RunSnapshot | 每任务作用域 |

`@Async`、`CompletableFuture`：本批范围内未发现新增入口；`ResilientChatService` 的
`CompletableFuture` 只做同线程完成信号，不跨线程传递身份。不扫描租户列表、不读默认租户：
作用域参数只来自 `RunSnapshot` / 执行信封 / 当前已建立的本轮作用域。

## TDD 红/绿（单点，先红后绿）

Maven：`<MAVEN_HOME>/bin/mvn.cmd`，统一 `-s maven-settings.xml -Djacoco.skip=true`；
每条命令只带一个 `-Dtest=类#方法`，未跑整类/批量/循环，未 `clean`，未推送。

先做的**定时续跑身份恢复最小失败单点**：

| 方法 | 红（实现前） | 绿（实现后，重跑同一方法） |
| --- | --- | --- |
| `ScheduledResumeIdentityTest#runtimeSweepRestoresRunIdentityFromSnapshotBeforeResume` | `AssertionFailedError：[定时续跑必须以快照身份进入续跑入口] expected: TenantUser[tenantId=9001, userId=7] but was: null` | `Tests run: 1, Failures: 0, Errors: 0` |

其余新增单点（实现后逐个精确执行）：

| 方法 | 覆盖 | 结果 |
| --- | --- | --- |
| `ResumeHandoffIdentityTest#handoffDrivesResumedTurnWithSnapshotIdentityWhenEntryThreadHasNone` | 续跑交棒（无身份入口线程 → 快照身份） | 通过 |
| `ConfirmDispatchIdentityTest#deadcardDispatchRunsResumeWithTheParkedRunIdentity` | HITL 死卡兜底派发 | 通过 |
| `CancelledRunFinalizerIdentityTest#handOffRunsCancelFinalizeWithTheRunIdentity` | 取消收尾交棒 | 通过 |
| `StreamWatchdogIdentityTest#scheduledTickRestoresRunIdentityOnTheWatchdogThread` | 看门狗节拍 | 通过 |
| `TurnKeepaliveIdentityTest#keepaliveBeatRestoresRunIdentityOnTheSchedulerThread` | 保活节拍 | 通过 |
| `ContextSummaryClientIdentityTest#summaryModelCallRunsWithTheSubmittingTurnIdentity` | 摘要旁路池 | 通过 |
| `SseChatEmitterIdentityTest#deliveryPoolRestoresRunIdentityFromSnapshotWhenConstructedWithoutOne` | SSE 投递池（无构造身份） | 通过 |
| `SpToolCallingManagerIdentityTest#toolBodyRunsWithTheRunIdentityEvenOnTheTimeoutExecutorThread` | 官方工具线程 + 超时执行器 | 通过 |
| `ToolExecutionEnvelopeIdentityTest#snapshotAndMapRoundTripPreserveTenantIdentity` | 信封身份冻结/往返/恢复 | 通过 |

回归（逐个类执行，主仓最终状态）：

`SseChatEmitterDeliveryTest`(18)、`SseChatEmitterCrossInstanceWakeTest`、
`SpToolCallingManagerRecoveryTest`（用例全通过；该次 Maven 在 JaCoCo 报告阶段因并发构建删除
`target/classes` 失败，非用例失败）、`SpToolCallingManagerToolTimeoutTest`、
`KnownInfoHandoffResumeTest`、`CancelFinalizeHandoffTest`、`CancelDuringRetryBackoffTest`、
`ResumeDispatchRetryTest`(15)、`ConfirmGateLeaseTest`、`ParkedFrontendExpiryTest`、
`TurnKeepaliveTest`、`StreamWatchdogSegmentBudgetTest`、`StartupResumeRunnerRuntimeSweepTest`、
`StartupResumeRunnerSessionGateTest`、`ContextSummaryClientTest`、
`FirstTurnCompactionStageTest`、`CompactionStageSignalTest`、`ResumeContextCompactionTest`、
`EmptyReplyTerminalTest`：全部通过。

回归中发现并修复的一处真实退化：`ConfirmGate` 的 `store.get` 最初写在重试 lambda 之外，
快照读失败会穿到调度线程；`ResumeDispatchRetryTest` 两个方法先报
`AiStateUnavailableException`，把读快照移入任务自身的 try/catch 后 15 个方法全绿。

收尾命令：
- `mvn -q -DskipTests compile` / `test-compile`：通过。
- `git diff --check`：无输出；提交前敏感信息钩子通过。

## 顾虑

1. **旧快照 `tenantId/userId=0` 没有身份可恢复**：`RunIdentityScope` 返回空作用域，
   仍不伪造默认租户。Task 2 已让键族按身份隔离后，这类旧轮次无法被新身份路径续跑/取消；
   需要一次数据治理决策（迁移或明确拒绝并清理），本批未改 `RunStore`。
2. **部分异步入口的身份是"提交线程抓取"而非"直接读快照"**：Reactor 事件、看门狗、保活、
   摘要池、工具超时池都属于"本轮驱动线程派生"，抓取点始终位于已有作用域内（首轮 TTL /
   续跑快照作用域）。若未来出现绕过这两条入口直接调用它们的路径，会得到空作用域而 fail-closed，
   届时需改为显式传 `RunSnapshot`。
3. **`SseChatEmitter` 的快照身份只懒解析一次**：跨实例唤醒创建的写出器若在快照建立前就开始投递，
   该实例生命周期内身份保持空（帧面不受影响，身份相关读会 fail-closed）。首轮正常路径由构造线程
   的请求身份兜底，未观察到实际缺口。
4. **`CancelledRunFinalizer` 构造签名新增 `RunStore`**：Spring 装配自动注入；旧测试构造保留
   （`store=null` 时作用域为空）。`AiExecutorConfig.aiStreamWatchdog` 由裸调度器改为 TTL 包装，
   返回类型仍是 `ScheduledExecutorService`。
5. **`ToolExecutionEnvelope` 是加法字段**：旧信封/旧快照读出 `0/0`，不会误认成真实租户；
   跨版本混跑时只是"无身份"，不会串身份。
6. **`AiExecutorConfig` 的 `aiStreamWatchdog` 与执行器装饰是两层**：TTL 负责自动传播，
   看门狗/保活自身再按建立时身份开作用域；两层身份一致（都来自同一驱动线程）。若未来调度器被
   共享给无关任务，TTL 的提交侧语义仍是正确的兜底。
7. **本批未跑全量测试套件**：共享工作区同时有 Task 2/4 在制品提交，期间出现过整体不可编译、
   测试发现失败与 JaCoCo 报告被并发清理的干扰；以上单点与回归均在相关代码提交后重跑通过。
   建议主控在干净工作树复跑一次全量门禁。
8. **未推送**：按要求只提交到本地主仓，`git log` 显示本提交位于 Task 2 会话销毁组提交之上。
