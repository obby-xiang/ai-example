# 租户运行链整合修复报告（RunStore / ResumeService / ConfirmGate / LeaseService / 唤醒 / 控制器归属）

- 状态：**完成**（残留风险见文末「剩余风险」，均为未授权文件面或已登记的过渡期口径）
- 修复范围（仅运行链生产代码 + 对应测试）：
  `RunStore`、`ResumeService`、`ConfirmGate`、`CancelledRunFinalizer`、`LeaseService`、`StartupResumeRunner`、
  `SseChatEmitter`、`FrameWakeListener`、`RedisFrameWakeBus`（仅退订语义由调用方保证）、`AiController`、
  `CancellationRegistry`（核对，无改动）、`RunIdentityScope`（+ 同包 `RequestContextUtils` 的 `clearContext()` 必需小补）。
- 未触碰（按要求）：`AiSessionPurgeService`、迁移脚本、前端、业务/AI 配置。
- 依据：`task-2-core-review.md`（C1/C2/I1/I2/I3/I5）、`task-3-review.md`（C1/C2/C3/I1/I2/I3/I4）、
  `task-2-session-review.md`（O1/O2/O3 与键形契约，均不改）。

## 一、逐条落点（对应三条复审的 Critical / Important）

| 复审项 | 修法 | 落点 |
| --- | --- | --- |
| core C1 取消无归属仍有副作用 | `POST /cancel/{runId}` 先核归属；无归属/不存在 ⇒ **404 `UNKNOWN_RUN`，不写任何取消事实**。`GET /cancel/{runId}` 同样核归属（否则是跨身份状态探测） | `AiController#cancel` / `#cancelStatus` / 新增 `#hasOwnArchive` |
| core C1 取消键身份化 | 已在 `CancellationRegistry`（`TenantKeyScope.scoped`）成立，本次只补控制器归属 | 核对，无改动 |
| core C2 / task3 C1+C2「先 store.get 无身份」 | 新增**显式身份读口** `RunStore#get(runId, tenantId, userId)` / `get(IndexRef)`；`listRuns()` 在<b>无身份线程</b>上改走 `allRunRecords()`（成员自带身份）并<b>逐条</b> `RunIdentityScope.open(ref)`；条目新增 `tenantId/userId`，扫描方据此进入该轮身份 | `RunStore#readSnapshot/get(...)`、`ResumeService#listRuns/#listRun/#systemRunRecords`、`StartupResumeRunner#resumeWithRunIdentity` |
| task3 C1 定时链（HITL 到期 / 派发重试 / 租约重试 / 取消收尾重试） | 身份改「**提交时刻随任务携带**」：`ConfirmGate#recordIdentityNow`、`CancelledRunFinalizer#recordIdentityNow`；任务体在 `RunIdentityScope.open(identity)` 里执行，不再「先读快照再建作用域」 | `ConfirmGate`（三条链）、`CancelledRunFinalizer`（交棒 + 重试） |
| task3 C3 续租判据首拍掉租 / 双驱动 | ① `ResumeService#resumeWithReason` 整体进入<b>记录身份作用域</b>（抢租/还租/markDriving 都在内），`retryModelStep` 的 markDriving 同样带身份；② `LeaseService#markDriving(lease, alive, identity)`：身份随租约携带，**每一拍在它的作用域里执行判据**；③ 判据本体 `snapshotNotTerminal(runId, identity)` / `stillDriving(runId, lease, identity)` 用显式身份读口 | `ResumeService`、`LeaseService#renewTick`、`ConfirmGate#markDrivingGuarded/#stillDriving` |
| core I1 删除口静默 no-op | `deleteRun(runId, tenantId, userId)` / `deleteRun(IndexRef)` / `deleteRun(runId, TenantUser)`：身份不可用 ⇒ `IllegalArgumentException`；**记录存在但归属 ≠ 给定身份 ⇒ `IllegalStateException`**（响亮失败）；删除键面与索引成员一律按给定身份拼 | `RunStore#deleteRun*`、`#readForDelete`、`#deleteRunKeys` |
| core I2 prune legacy 判活键形 | 新增 `RunStore.legacyRunKey(prefix, runId)`（显式 `{run:<id>}`），`pruneRunIndex` 的 legacy 分支与 `allRunIds` 的 legacy 分支都用它 | `RunStore#legacyRunKey/#pruneRunIndex/#allRunIds` |
| core I5 写口 0/0 adopt | `openSnapshotWriteScope`：记录无身份（0/0）而当前有身份 ⇒ **拒绝**（`IllegalStateException`，不 adopt、不改写记录字段）；两者皆无 ⇒ 显式无身份作用域（legacy） | `RunStore#openSnapshotWriteScope` |
| core I3 / task3 I3 唤醒按裸 runId | `FrameWakeListener`：登记表改按 **身份段+runId** 分桶；每个目标登记时绑定 `Registration(identity, runId, key)` 句柄，`subscribe`/`unsubscribe` 用它（退订在**无身份线程**上也落到同一频道）；扇出只到该身份桶 | `FrameWakeListener`（含 `keyOf` / `Registration`） |
| task3 I2 SSE 身份解析 | `SseChatEmitter`：构造线程身份降级为「取键提示」；`deliveryIdentity()` **只按记录解析**（显式身份读口；记录身份优先；记录不存在才用提示身份；0/0 或读不出 ⇒ `null`），删掉 `currentOrNull()` 末行回落 | `SseChatEmitter#deliveryIdentity` / `requestIdentityHint` |
| task3 I1 / 0/0 沿用调用者 | `RunIdentityScope.open(0/0)` / `open(null)` 改为**显式清空**线程身份并返回可关闭句柄（关闭即还原）；新增 `denied()`；嵌套还原由 `RequestContextUtils.SafeCloseable` 保证 | `RunIdentityScope`、`RequestContextUtils#clearContext` |
| session 复审 O2/O3 | 取消/观测键身份化属另组面；本次只在控制器层堵住「无归属即可取消」 | 核对 |

### 语义变更（会改行为，需知会）

1. **`POST /api/ai/cancel/{runId}` 现在会对无归属/不存在的 runId 返回 404 `UNKNOWN_RUN`**（此前 200 + 无条件写旗标）。
   前端三条调用点都包在 `try/catch` 里（`stores/ai.ts` 的 `cancelStoppedRun` / `stop()` / `newChat`），
   404 只被当作"后端未确认"，不影响本地停止收敛；`GET /cancel/{runId}` 同样改为 404（前端仅导出未调用）。
   准备窗口（已 start、快照未建）仍可取消：判据是**本命名空间归档里有帧**（归档键按身份分开，读到即证明归属）。
2. **0/0 快照不得再写入有身份的命名空间**：`ToolExecutionEnvelope#toSnapshot`（旧信封 0/0）的恢复路径因此
   在落库处 fail-closed（`SpToolCallingManager#recoverSnapshot` 已 catch 并返回 `null`，语义等价于改造前的
   "写进去也读不回来"，只是失败点更早、日志更明确）。
3. **`ResumeService.listRuns()` 的行为分叉**：无身份线程 = 系统恢复面（`allRunRecords` + 逐条作用域 + legacy 裸成员）；
   有身份线程 = 请求面（只 `allRunIds` 的自己身份，**不**对别人的成员做活/死判定，避免读别人的键）。
4. `deleteRun` 在「记录存在但身份不符」时**抛异常**（此前静默 no-op）。生产唯一调用方（会话销毁）在记录身份
   作用域内调用，行为不变。

## 二、红/绿证据（严格逐个精确单点）

Maven：`<MAVEN_HOME>/bin/mvn.cmd`（本机 `mvn` 不在 PATH，用便携发行版），统一 `-o -B test -Dtest='<类>#<方法>'`。

### 2.1 先红后绿（真实 `RunStore` + 真实键形，不用 InMemory/mock 掩盖）

第一个先写失败用例：`StartupResumeRealStoreDiscoveryTest#startupScanFindsTheIdentityScopedRunAndResumesItUnderItsRecordIdentity`

- **红**（实现前）：`Tests run: 1, Failures: 1` ——
  `StartupResumeRealStoreDiscoveryTest:80 [无身份线程上必须按索引成员发现带身份的轮次]`
  （`listRuns()` 在无身份线程上返回空：`allRunIds()` 跳过复合成员）。
- **绿**（实现后）：`Tests run: 1, Failures: 0, Errors: 0`。

### 2.2 12 条新/重写单点在 HEAD 上的红/绿基线

做法：只把 `backend/src/main/java` 回退到 HEAD（`git stash`），保留新测试，跑同一批 7 个类；
再恢复实现重跑。**基线红 7 条 / 相邻对照绿 5 条**，实现后 **12/12 全绿**。

| 用例（单点） | HEAD 基线 | 修复后 |
| --- | --- | --- |
| `StartupResumeRealStoreDiscoveryTest#startupScanFindsTheIdentityScopedRunAndResumesItUnderItsRecordIdentity` | 🔴 发现面为空 | 🟢 |
| `ScheduledResumeIdentityTest#runtimeSweepRestoresRunIdentityFromTheRecordBeforeResume`（重写为真实 store + `sweep()`） | 🔴 候选为空 | 🟢 |
| `SseChatEmitterIdentityTest#withoutARecordIdentityThePoolIdentityIsNotAdopted` | 🔴 沿用池化线程残留身份 | 🟢 |
| `SseChatEmitterIdentityTest#deliveryUsesTheRecordIdentityNotTheIdentityLeftOnThePoolThread` | 🟢（相邻对照） | 🟢 |
| `CancelledRunFinalizerIdentityTest#handOffAndItsRetryRunCancelFinalizeUnderTheRecordIdentity`（重写为真实 store） | 🔴 首拍与重试身份均 `null` | 🟢 |
| `LeaseRenewRecordIdentityTest#renewalKeepsTheLeaseWhenThePredicateRunsOnAnIdentityLessRenewThread` | 🔴 判据读到 `null`（首拍掉租） | 🟢 |
| `LeaseRenewRecordIdentityTest#renewalStopsWhenTheRecordIsTerminal` | 🟢（相邻对照：终态仍要还租） | 🟢 |
| `CancelCrossTenantSameRunIdTest#cancelWithoutOwnershipHasNoSideEffectEvenForAnExistingForeignRun` | 🔴 200 + 写旗标 | 🟢 |
| `CancelCrossTenantSameRunIdTest#cancelOfOneIdentitysRunDoesNotCancelTheOtherIdentitysSameRunId` | 🟢（相邻对照） | 🟢 |
| `RunIdentityScopeTest#zeroIdentityClearsTheCallersIdentityAndRestoresItOnClose` | 🔴 0/0 沿用了调用者身份 | 🟢 |
| `RunIdentityScopeTest#nestedScopesRestoreTheOuterIdentityInsteadOfClearingIt` | 🟢（相邻对照） | 🟢 |
| `RunIdentityScopeTest#systemAccountIdentityIsUsableAndNestedCleanupLeavesNoResidue` | 🟢（相邻对照） | 🟢 |

`ParkedFrontendExpiryIdentityTest#parkedFrontendCardConvergesOnTheSchedulerThreadThatHasNoIdentity`
（HITL 到期链，真实 store + **出生时无身份**的调度线程）：**HEAD 红** ——
`等待超时：park 后的即时前端工具必须在 expiresAt 到期收敛`（15.9s 超时，条目永远 PENDING）；
修复后 `Tests run: 1, Failures: 0`，且断言"调度线程出生时无身份 / 到期后不在线程残留身份"。

三条「红由 API 缺失构成」的单点（旧代码不存在显式身份口，无法在 HEAD 上编译出行为红，故列在实现后逐个跑）：
`FrameWakeListenerIdentityTest`（3 用例）、`RunStoreIdentityPortTest`（4 用例）—— 修复后逐个单方法全绿。

### 2.2b 一个副产品发现（已由本次修法一并覆盖）

`ConfirmGate.DEFAULT_RETRY_SCHEDULER` / `CancelledRunFinalizer.DEFAULT_SCHEDULER` / `LeaseService` 的续租执行器
都是**懒创建的单线程池**；`RequestContextUtils` 用的是 `TransmittableThreadLocal`（本质是 inherit 型），
因此**池线程在"第一次有身份的那次调度"时会永久继承那个身份**。后果：
- 定时链有时"碰巧能跑"（继承到的身份恰好就是该轮身份），有时读到别人的身份（跨身份读），且完全取决于
  哪一次调度先发生 —— 这是不可观测的隐性耦合；
- 本次修法把身份改成**显式随任务携带 + 每拍 `RunIdentityScope.open(identity)` 覆盖**，
  继承来的身份不再参与判定（`ParkedFrontendExpiryIdentityTest` 的夹具因此显式构造"出生即无身份"的调度线程，
  旧实现在该夹具下必然红、新实现绿）。

### 2.3 `RunStoreIdentityPortTest`（真实键形）逐个单点

| 单点 | 断言要点 | 结果 |
| --- | --- | --- |
| `explicitIdentityReadPortReadsTheRecordFromANonIdentityThread` | 无身份线程：`get(runId)`=`null`，`get(runId, A)` 命中，`get(runId, B)`=`null`，`get(runId,0,0)` 抛 `IllegalArgumentException` | 🟢 |
| `deleteRunWithAMismatchedIdentityFailsLoudlyInsteadOfNoOp` | 身份不符 ⇒ `IllegalStateException`；数据与索引成员原样；正确身份删除后键与成员清零 | 🟢 |
| `pruneRunIndexKeepsLegacyMembersAliveOnTheLegacyKey` | 有身份线程上 prune legacy 成员不会被判死/`SREM` | 🟢 |
| `writingAZeroIdentityRecordUnderAnIdentityFailsClosedInsteadOfAdopting` | 0/0 记录在有身份线程上写 ⇒ `IllegalStateException`，且不落任何键 | 🟢 |

### 2.4 `FrameWakeListenerIdentityTest`（真实频道键形 `ai:wake:<t>:<u>:<runId>`）逐个单点

| 单点 | 断言要点 | 结果 |
| --- | --- | --- |
| `twoTenantsWithTheSameRunIdSubscribeToTheirOwnChannels` | 两个身份同 runId 各自订阅两条频道（旧实现第二家不订阅） | 🟢 |
| `unregisterReleasesTheSameChannelEvenFromAnIdentityLessThread` | 无身份线程退订落到 A 的频道，B 的订阅不受影响 | 🟢 |
| `wakeFromOneChannelOnlyReachesThatIdentitiesTargets` | A 频道唤醒只扇到 A 的目标 | 🟢 |

### 2.5 存量受影响用例（逐个单方法复核，改完即绿）

| 用例 | 原因 | 处理 | 结果 |
| --- | --- | --- | --- |
| `AiControllerRunOwnershipTest#cancelEndpointDoesNotAdoptAnotherIdentitiesSnapshot` | 语义从"200 + runExists=false"改为"404 + 无副作用" | 改断言 + `verify(cancellations, never()).cancel` | 🟢 |
| `AiControllerRunOwnershipTest#cancelStatusEndpointHidesAnotherIdentitiesRun`（新增） | GET 不再跨身份回显 | 新增 | 🟢 |
| `RunStoreFirstSnapshotWriteTest#createThrowsWhenTheFirstSnapshotWriteIsRefused` / `#createDoesNotRegisterTheRunIndexWhenTheFirstWriteIsRefused` | 夹具用 0/0 快照 + 有身份线程（正是本次禁止的 adopt 形态） | 给夹具快照补记录身份（生产快照恒带身份） | 🟢 |
| `LeaseLossReadOnlyTest`（7 用例） | **HEAD 即为红**（租户改造后 `create` 需要身份，该文件未跟改；`git stash` 基线实测 7 Errors）| 加身份作用域 + 租约键改 `storeA.runLeaseKey(...)`（真实键形） | 🟢 7/7 |
| `RunStoreFenceAheadWriteGateTest`（3 用例） | 同上（HEAD 3 Errors） | 加身份作用域 | 🟢 3/3 |
| `ScheduledResumeIdentityTest`（原 mock 版） | 依赖 `mock(RunStore)`（身份无关），正是复审点名的"假绿" | 重写为真实 store + `sweep()` | 🟢 |
| `SseChatEmitterIdentityTest`（原 InMemory 版） | 依赖身份无关的 `InMemoryRunStore` | 重写为真实 store + 两个单点 | 🟢 |
| `CancelledRunFinalizerIdentityTest`（原 InMemory 版） | 同上 | 重写为真实 store + 交棒/重试两拍 | 🟢 |

> 说明：HEAD 上 `LeaseLossReadOnlyTest`（7）与 `RunStoreFenceAheadWriteGateTest`（3）本来即红（`git stash` 实测），
> 属租户改造遗留；因与本次修改同面（lease/fence/身份键）一并修复，未扩大其它面。

## 三、剩余风险（如实登记）

1. **记忆删除（`RedisChatMemoryRepository`）仍只按调用线程身份取键**：该文件不在本次授权面内。
   现状安全的理由 = 唯一生产调用方（会话销毁 `AiSessionPurgeService`）已在该会话**记录身份作用域**内运行；
   风险形态 = 任何新的无身份调用方会只清 legacy 键且不报错。建议下一批给记忆删除口补显式身份重载
   （与 `deleteRun(runId, tenantId, userId)` 同形），并对"无身份删"打 WARN。
2. **`deleteRun` 在"线程无身份 + 给定身份下确实没有记录"时仍是幂等 no-op**（无法枚举全局索引判断
   "这个 runId 属于别人"而不引入 O(索引) 扫描）。当前能识别的失配形态是"记录在、归属不同"（会用
   `IllegalStateException` 响亮失败）。若后续要求更强的"跨身份误删告警"，需要一条按 runId 反查归属的索引。
3. **`SpToolCallingManager#recoverSnapshot` 的旧信封（0/0）恢复**：现在在 `store.save` 处 fail-closed
   （返回 `null`，调用方按失败关闭）。语义与改造前"写得进去但读不回来"等价，但失败点前移、日志为
   "拒绝把无归属（0/0）…adopt"。
4. **`RunRegistry.emitters` 仍按裸 runId 键控**（`RunRegistry` 不在授权面）：`hasLiveSubscriber(runId)`
   在"跨租户同 runId + 另一家还有活连接"时会误判为活跃（保守方向：多等一拍）。`/events` 入口有
   `preparingOrUnknown` + 归属核对短路，未发现内容泄漏。
5. **`ResumeService.listRuns()` 在无身份线程上不再返回 legacy 裸成员**的活/死判定口径变化：legacy 成员仍会被
   `allRunIds()` 的过渡期分支发现（无身份线程），因此启动扫描对迁移前轮次的行为保持不变；但
   **prune 会按 legacy 键形判定**（本次修复），不会再误删。
6. **`SseChatEmitter` 在"构造线程完全无身份"时无法解析记录**（读记录需要身份拼键）：投递按"无身份"执行
   （fail-closed，不借用池化线程身份）。生产路径（`RunRegistry.of` 的调用方）都在驱动/HTTP 线程上，故不可达；
   若将来出现"唤醒线程创建写出器"，需要在 `RunRegistry.of` 增加显式 `TenantUser` 入参（`RunRegistry` 未授权）。
7. **`AiController` 的取消归属判据依赖归档可读**：Redis 抖动时 `lastSeqIfKnown` 为空 ⇒ 准备窗口的取消会被
   404（fail-closed：不写旗标）。已运行中的轮次不受影响（快照可读即走归属核对）。
8. 未跑前端/E2E/packaged 服务；本次不改前端与迁移脚本，接口面变更仅"cancel 的 404"与 listRuns 的 additive
   字段（`tenantId` / `userId`）。

## 四、门禁

- 环境：本机 `mvn` 不在 PATH，使用 `<MAVEN_HOME>/bin/mvn.cmd -o -B`（离线，依赖已在 `~/.m2`）。
- **后端全量**：`mvn -o -B test`（先 `rm -rf target/surefire-reports`）
  = **`Tests run: 3381, Failures: 5, Errors: 2, Skipped: 0`**（512 个 surefire XML）。
- 这 7 条**全部是改动前就存在的红**（`git stash` 后同一批类复跑：`Tests run: 36, Failures: 5, Errors: 2`，逐条一致），
  且不在本次改动面：
  | 类 | 数量 | 失败原因（既有） |
  | --- | --- | --- |
  | `common.ErrorCodesContractTest` | 2 | 后端新增 `TENANT_IDENTITY_REQUIRED` / `TENANT_IDENTITY_UNKNOWN` 缺 `messages_zh` 与前端镜像码表 |
  | `common.GlobalExceptionHandlerTest` | 1（Error） | 同上：`error.TENANT_IDENTITY_REQUIRED` 无中文文案 |
  | `config.FlywayStartupLockTest` | 3 | 断言迁移序列 `1..9`，仓库已有 `V10`/`V11` |
  | `config.FlywayStartupLockContextTest` | 1（Error） | 同上 |
- **本次新增/重写的 20 个单点（10 个类）全部绿**：新用例按类逐次跑（每个方法即一个单点，见 §2.2–2.4 的逐行表），
  存量受影响用例按 `-Dtest='<类>#<方法>'` 单方法复跑（§2.5）；其中 **8 条在 HEAD 上实测为红**（§2.2 表 + HITL 到期那条）。
- 未执行：前端（本次零前端改动）、E2E/Playwright、起服务实机核验、打包。
- `git diff --check`：无空白错误（仅 `core.autocrlf` 的 CRLF 提示）。
