# 补档 · B23L1 批② fencing（LeaseService / 续跑权租约 / 确认门改造 / 挂起 TTL 读侧续期）施工报告

- 日期：2026-10-21
- 范围：`docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md` §2.2（B3 主件）+ §7 批② + §5 验收中批②相关项。§2.1（设施）/§2.3（资源治理）分别属批①/批③，**本批不做**（批① 已入库 `de7095a`）。
- 施工子智能体自证（取自本 agent wire 的 `profile.bind` 单行，非首行 metadata）：`agentId=agent-1316`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`；取值文件 `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1316/wire.jsonl`（`grep -n 'profile.bind'` 命中第 5 行）。

## 0. 测试矩阵（先行登记；下列每条均落地为测试用例）

### 0.1 单实例面（L / E / G / T / R / D 六族）

| # | 面 | 断言 | 落点（用例） | 状态 |
| --- | --- | --- | --- | --- |
| L1 | LeaseService 设施 | 空闲键抢到：**新 epoch + 配置 TTL** 一次到位 | `LeaseServiceTest#acquiresAFreeKeyWithAFreshEpochAndTheConfiguredTtl` | ✅ |
| L2 | LeaseService | 被占用键**拒绝且不覆盖现值**（不偷别人的租约） | `LeaseServiceTest#refusesAnOccupiedKeyWithoutOverwritingIt` | ✅ |
| L3 | LeaseService | 同主重入返回**同一 epoch**、只发一次 `SET`（不消耗新号） | `LeaseServiceTest#reentrantAcquireReturnsTheSameEpochWithASingleSet` | ✅ |
| L4 | LeaseService | 过期租约可被重抢，且新 epoch **严格更大**（号不回退 = fencing 前提） | `LeaseServiceTest#expiredLeaseCanBeAcquiredWithAStrictlyGreaterEpoch` | ✅ |
| L5 | LeaseService | 续租：epoch 或 owner 不匹配即拒（不续别人的租约） | `LeaseServiceTest#renewRejectsAMismatchedEpochOrOwner` | ✅ |
| L6 | LeaseService | 释放：**只删自己那一任**（GET→比对→DEL 全在 Lua 内；非原子的旧实现会删掉别人的租约） | `LeaseServiceTest#releaseOnlyDeletesItsOwnEpoch` | ✅ |
| L7 | LeaseService | 抢/续/放三条写路径**各一次脚本调用**（不回落成多条命令拼装） | `LeaseServiceTest#allWritePathsGoThroughOneScriptCallEach` | ✅ |
| L8 | LeaseService | Redis 不可用 ⇒ **显式降级**（`UNAVAILABLE`，不抛异常、不吞成"别人持有"） | `LeaseServiceTest#degradesExplicitlyWhenRedisIsDown` | ✅ |
| L9 | LeaseService | `heldEpoch` = **进程内视图**（零往返；与 Redis 真值的分工见 I3） | `LeaseServiceTest#heldEpochIsAProcessLocalOwnershipView` | ✅ |
| L10 | LeaseService | 续租按 `renew-interval` 到点发生，`release` 之后**不再续** | `LeaseServiceTest#markDrivingRenewsOnTheConfiguredIntervalUntilRelease` | ✅ |
| E1 | fence 写 | 持租写成功并把 `ai:fence:run:<runId>` **推进到本次 epoch** | `RunStoreFencedWriteTest#fencedWriteSucceedsAndPushesTheFence` | ✅ |
| E2 | fence 写 | 陈旧 epoch 写**被拒**，已落库值一字未动 | `RunStoreFencedWriteTest#staleEpochWriteIsRejectedAndTheStoredValueIsUntouched` | ✅ |
| E3 | fence 写（**红线**） | 无租约（epoch=0）⇒ 保持 plain `SET`，单实例/降级路径语义零变化（"无租约不拒写"） | `RunStoreFencedWriteTest#withoutALeaseSaveStaysAPlainSet` | ✅ |
| E4 | fence 写 | 终态写**顺带释放**本实例持有的续跑租约（正常收尾不留 TTL 尾巴） | `RunStoreFencedWriteTest#terminalWriteReleasesTheLease` | ✅ |
| E5 | 键族 | `deleteRun` 清掉新增键族（lease / fence / req / decided） | `RunStoreFencedWriteTest#deleteRunClearsTheNewKeyFamilies` | ✅ |
| E6 | 认领 | 认领值升级为 `<epoch>\|<instanceId>`：`claimedBy()` **剥前缀**、旧裸值原样返回（兼容红线） | `RunStoreClaimEpochTest`（4 条） | ✅ |
| E7 | 快照兼容 | 旧 JSON 无 `runEpoch` ⇒ 0，且 `asMap()` 透出（"这条状态是哪一任租约写的"可排障） | `RunSnapshotEpochCompatTest`（2 条） | ✅ |
| G1 | 确认门接线 | 等待方进 `await` 即持租；按 `renew-interval` 续租；决策到达后离开等待**不释放** | `ConfirmGateLeaseTest`（G1） | ✅ |
| G2 | 确认门接线 | 抢到租 ⇒ 派发续跑并带**同一个 lease**（`resume(runId,false,lease)`）+ 交调度器续租 | `ConfirmGateLeaseTest`（G2） | ✅ |
| G3 | 确认门接线 | 抢不到 ⇒ 不派发、`LEASE_HELD_ELSEWHERE`，但决策**已落库**（条目 APPROVED、回执帧恰一次） | `ConfirmGateLeaseTest`（G3） | ✅ |
| G4 | 确认门接线 | 延迟重试（`lease.retry-delay`）在 holder 租约失效后派发成功 | `ConfirmGateLeaseTest`（G4） | ✅ |
| G5 | 回归锁 | `woke=true` 快路径**不碰租约** | `ConfirmGateLeaseTest`（G5） | ✅ |
| G6 | 回归锁 | `ACTIVE_LATCH` 短路**连抢都不抢** | `ConfirmGateLeaseTest`（G6） | ✅ |
| G7 | 确认门接线 | holder 存活时重试不派发也不再排重试；holder 死亡后下一次外部输入以**严格更大** epoch 接管 | `ConfirmGateLeaseTest`（G7） | ✅ |
| G8 | 确认门接线 | 释放是 epoch scoped：等待方**迟到的 release 不删**新持有者的租约 | `ConfirmGateLeaseTest`（G8） | ✅ |
| T1 | 读侧续期 | `GET /runs/current`：取快照后、组装 body 前 touch 一次（快照重载，省一次读） | `AiControllerSuspendedTouchTest`（T1）/`RunStoreTouchTest#snapshotOverloadSkipsTheSnapshotRead` | ✅ |
| T2 | 读侧续期 | `POST /confirm` 与 `POST /frontend-tool-result`：**先续命再落决策** | `AiControllerSuspendedTouchTest`（T2） | ✅ |
| T3 | 读侧续期 | `GET /events/{runId}`（reattach）：`get` 成功后 touch 一次 | `AiControllerSuspendedTouchTest`（T3） | ✅ |
| T4 | 读侧续期（负例） | 快照缺失/已终态 ⇒ **不** touch（读侧不得养出孤儿键）；touch 同时续会话索引与新增键族 | `AiControllerSuspendedTouchTest`（T4）/`RunStoreTouchTest#terminalOrMissingRunIsNotRenewed` / `#touchingASuspendedRunRenewsBothKeyFamilies` | ✅ |
| T5 | 读侧续期 | 心跳本体 `renewSuspensionTtl` **零改动**（`RunStoreSlidingTtlTest` 锁的 exactly 5 次 EXPIRE 不动） | `RunStoreSlidingTtlTest`（回归，5/5 绿） | ✅ |
| R1 | req 位原子化 | 置位路径**不覆写决策**（旧实现整条回写会把 `APPROVED` 覆盖回 `PENDING`） | `RunStoreRequestEmittedAtomicTest#...decisionNotOverwritten...`（R1） | ✅ |
| R2 | req 位原子化 | `ai:req:` 用 `HSETNX` **首写者胜**：重复置位不改时刻 | `RunStoreRequestEmittedAtomicTest`（R2） | ✅ |
| R3 | req 位原子化 | 已决条目**不再置位** | `RunStoreRequestEmittedAtomicTest`（R3） | ✅ |
| R4 | 双决策原子化 | 并发双决策：恰一个 `ACCEPTED`、恰一个 `DUPLICATE`、`confirm_decision` 帧**恰一次** | `ConfirmGateDecisionAtomicTest#...`（R4） | ✅ |
| R5 | 双决策原子化 | 决策/回灌/放弃三处争**同一把 guard**（放弃也参与，不旁路） | `ConfirmGateDecisionAtomicTest` / 收口落点 `ConfirmGate:1123/1141` | ✅ |
| R6 | req 位原子化 | 取消优先：迟到的置位不得把取消**复活成 PENDING** | `RunStoreRequestEmittedAtomicTest`（R6） | ✅ |
| D1/D2 | 决策 guard | 同一条目**全集群只有一次决策**能抢到（`HSETNX` 首写者胜） | `RunStoreDecisionGuardTest`（D1/D2） | ✅ |
| D5 | 决策 WAL | 结论先落 `ai:decided:` 再改条目；写者死在两步之间 ⇒ 读侧可收敛成已决 | `RunStoreDecisionGuardTest`（D5）/`RunStoreWalConvergeTest` | ✅ |

### 0.2 实例面（I1–I4：必须"第二个主人"才可达）

| # | 断言 | 落点（用例） | 状态 |
| --- | --- | --- | --- |
| I1 | 两个 `ConfirmGate` 实例（各自一把 `submitLock`）+ 同一 store，栅栏让两边都先读到 `PENDING` ⇒ 失效实现**确定性红**（恰一个 accepted / 一帧） | `ConfirmGateDecisionAtomicTest`（`FirstTwoReadsBarrier`） | ✅ |
| I2 | WAL 已决但条目仍 PENDING ⇒ **必须有驱动者**把结论推进（否则卡停在"已决 + 没人推进"）；无驱动者时 `resumeService.resume` 不被调用 = 红 | `ConfirmGateDecisionAtomicTest#walDecidedCardIsRepairedAndThenDriven` | ✅ |
| I3 | 归属校验对 **Redis 真值**做（`verifyHeld`）：租约在另一实例手里 ⇒ `LEASE_HELD_ELSEWHERE`，不回落到进程内视图 | `ResumeServiceLeaseTest#legacySignatureReportsHeldElsewhere...`（两例，inst-b 制造） | ✅ |
| I4 | 三态必须分明：**设施不可用**（`UNAVAILABLE`）≠ **别人持有**（`HELD_ELSEWHERE`）；报错前者会让一次 Redis 抖动变成全线拒跑 | `LeaseServiceTriStateTest`（I3/I4/I7 三节） | ✅ |

### 0.3 回归面

| 面 | 断言 | 落点 | 状态 |
| --- | --- | --- | --- |
| 兼容装配 | 三参 `RunStore` / 三参 `RunRegistry` 路径语义不变（批① conformance 全绿零改动） | `ai/conformance` 7 类 55 条 | ✅ |
| 帧契约 | 新增字段一律 additive；`confirm_decision` 帧序与 `tool_start/tool_result` 恰一次不变 | `FrameContractFalsificationTest`（17）/`ToolFrameSequenceConformanceTest`（11） | ✅ |
| 会话滑动 | 挂起期心跳 5 次 EXPIRE 口径不动 | `RunStoreSlidingTtlTest`（5/5） | ✅ |
| 续跑闭环 | 既有 `ResumeService` 调用方（启动续跑/运维续跑/死卡兜底）零改动即获得 fencing | `ResumeService*Test` 全族 + `ConfirmGate*Test` | ✅ |
| 健康面 | `/health` 增 `leaseOk`（现算 PING）不改变既有字段与 503 判据 | `AiControllerHealthSseDeliveryTest`（改 5 条） | ✅ |

## 1. 改动清单（带行号，行号按最终态）

### 1.1 新增 · 生产代码（2 个类，`backend/src/main/java/com/example/configmgr/ai/run/`）

| 文件 | 关键行 | 作用 |
| --- | --- | --- |
| `LeaseService.java` | `EPOCH_KEY`:75（`ai:lease:epoch`，全局 INCR、无 TTL）、`ACQUIRE_SCRIPT`:91、`RENEW_SCRIPT`:104、`VERIFY_SCRIPT`:115、`RELEASE_SCRIPT`:125、`AcquireOutcome`:143、`AcquireResult#created`:157、`Hold`:172、`acquire`:267、`verifyHeld`:307、`renew`:331、`release`:348、`markDriving(lease,alive)`:404、`renewTick`:431、`leaseOk`:461、`@PreDestroy shutdown`:472 | **B3/L1 共用租约原语**：抢/续/放/校验全部 Lua 原子；值 = `<epoch>\|<owner>`；epoch 全局单键 INCR；Redis 不可用显式降级 `UNAVAILABLE`；内置单线程续租调度器（`ai-lease-renew` 守护线程） |
| `InstanceId.java` | `VALUE`:17、`get()`:24 | 进程内唯一实例标识（`instance-<host>-pid<pid>-<6位>`）。收口点：租约 owner、认领值、台账 `executedBy` 三者从此**同源**（此前 `RunStore` 自算一份，租约另算一份会让"自己写的记录读回来不认识"） |

### 1.2 修改 · 生产代码

| 文件 | 行号 | 改动 |
| --- | --- | --- |
| `ai/config/AiProperties.java` | `lease` 字段:54、`Lease` 内部类:326–345 | 新增 `app.ai.lease.{ttl=30s, renew-interval=10s, retry-delay=35s}`（TTL=3×续租；重试=TTL+5s 余量） |
| `ai/run/RunStore.java` | `REQ_PREFIX`:96、`DECIDED_PREFIX`:99、`LEASE_RUN_PREFIX`:102、`FENCE_RUN_PREFIX`:105、`FENCED_SET_SCRIPT`:175、`reqKey`:230、`decidedKey`:235、`runLeaseKey`:240、`fenceKey`:245、`save` fence 分支:286–322、`warnIfFenceIsAhead`:327、`fencedSet`:360、`releaseRunLease` 调用:479、`tryAcquireRunLease`:500、`acquireRunLease`:507、`releaseRunLease`:517、`heldRunEpoch`:530、`leaseService()`:535、`markRequestEmitted`（HSETNX）:822、`touchActiveRun`:864 起、`tryRecordDecision`/`decisionOf`、`stripEpochPrefix`:912 | ①新增四个键族（`ai:req:` / `ai:decided:` / `ai:lease:run:` / `ai:fence:run:`）；②`save` 在**持租**时走 Lua fence 写（fence ≤ epoch 才落库并把 fence 推到 epoch），**不持租时保持 plain SET**（红线 E3）；③`markRequestEmitted` 改 `HSETNX` 落独立 HASH，不再整条回写（根治 L0 M-2 lost update）；④`pending()/pendings()` 读侧合并 req 与 decided 标记（只读不写）；⑤`touchActiveRun` 读侧续期（自带终态判据）；⑥认领值前置 epoch |
| `ai/run/ResumeService.java` | 类注租约节:56、`describe()` 键面:221、`resume(runId)`:225、`resume(runId,forceTakeover)`:248–286、`resume(runId,forceTakeover,lease)`:309、`snapshotNotTerminal`:… | ①续跑入口**自抢租约**（旧签名调用方零改动即获 fencing），非 `ACCEPTED` 立刻还租、`ACCEPTED` 交调度器；②新增带租约重载：**不再自抢**、只 `verifyHeld` 对 Redis 真值复核归属（不符即 `LEASE_HELD_ELSEWHERE`，设施不可用按无租约降级放行）；③`ai:resume:lock:` 的 GET→比对→DEL 非原子实现**整体摘除**（构造器保留兼容，M5） |
| `ai/gate/ConfirmGate.java` | 类注触发方抢租:104、等待期持租/续租:425、`handOffLease`:564、`markDrivingGuarded`:586、`stillDriving`:600、`leaseRenewEveryTicks`:621、`releaseWaitingLease`:640、`convergeOnDecided`:952、`tryRecordDecision` 接入:885/1072/1123、`maybeTriggerResume`:1178、`dispatchResume`:1242、`scheduleLeaseRetry`:1267、`runResume`:1291 | ①等待方**持租**（进 `await` 即抢、心跳续租、决策到达**交棒不释放**）；②`maybeTriggerResume` 从"查本地 latch"升级为"先 `tryAcquire`"：抢到才派发并带同一 lease，抢不到只落决策 + `LEASE_HELD_ELSEWHERE` + 按 `retry-delay` 延迟重试；③决策/回灌/放弃三处统一走 `ai:decided:` guard（WAL 先落结论再改条目）；④`woke=true` 与 `ACTIVE_LATCH` 两条快路径不碰租约（回归锁） |
| `ai/run/RunSnapshot.java` | `runEpoch`:107、`asMap`:133 | 新增 `runEpoch`（0 = 无租约写入/旧数据），取证视图透出 |
| `ai/run/PendingToolCall.java` | `claimedEpoch`:130、`asMap`:233 | 新增 `claimedEpoch`（认领所用的租约 epoch 的结构化副本，0 = 无租约/旧数据）；`req` 语义改为"读侧视图，存储位置 = `ai:req:` HASH" |
| `ai/web/AiController.java` | `/confirm` touch:236–239、`/frontend-tool-result` touch:273、`/events` touch:333、`/runs/current` touch:473、`resume` 409 家族:557–559、`/health leaseOk`:787–789 | ①四个读/写入口接 `touchActiveRun`（先续命再落决策）；②`LEASE_HELD_ELSEWHERE` / `IN_PROGRESS` / `ALREADY_RUNNING` 显式归 **409**（"这一轮已经有主"既不是 503 也不是 404）；③`/health` 增 `leaseOk`（现算 PING） |

### 1.3 新增 · 测试（16 个新文件 = 14 个用例类 + 2 个替身，`backend/src/test/java/com/example/configmgr/ai/`）

> 口径订正：卡片登记「测试 15 个新类」，实测 `git status` 的新增测试文件为 **16 个**（14 个用例类 + `FakeLeaseRedis` / `FakeRenewScheduler` 两个替身）；用例条数 = 75 条。

| 文件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `run/LeaseServiceTest.java` | 10 | L1–L10 |
| `run/LeaseServiceTriStateTest.java` | 6 | I3 / I4 / I7（三态 + 归属校验 + 僵尸续租） |
| `run/FakeLeaseRedis.java`（替身） | — | 进程内 Redis：脚本按 `LEASE_*` 标记分派（真 Lua 语义在 Java 侧等价实现）、TTL 可推进、可注入失败 |
| `run/FakeRenewScheduler.java`（替身） | — | 可控 tick 的续租调度缝（"到点续一次"可断言，不睡真实时间） |
| `gate/ConfirmGateLeaseTest.java` | 12 | G1–G8 |
| `gate/ConfirmGateDecisionAtomicTest.java` | 4 | R4/R5 + I1/I2（`FirstTwoReadsBarrier` 让失效实现确定性红） |
| `run/ResumeServiceLeaseTest.java` | 9 | 旧签名自抢/放、带租约重载的归属校验、`describe()` 键面、降级（I3/I4） |
| `run/RunStoreFencedWriteTest.java` | 5 | E1–E5 |
| `run/RunStoreClaimEpochTest.java` | 4 | E6（认领值 epoch 前缀 + 旧裸值兼容） |
| `run/RunStoreClaimFenceTest.java` | 3 | I5（认领侧 fencing：`fence > epoch` 拒认领；无租约不破 E3） |
| `run/RunStoreRequestEmittedAtomicTest.java` | 4 | R1/R2/R3/R6 |
| `run/RunStoreDecisionGuardTest.java` | 3 | D1/D2/D5 |
| `run/RunStoreWalConvergeTest.java` | 4 | I1 / I2① / M2（读侧收敛只合并"可重建"结论） |
| `run/RunStoreTouchTest.java` | 3 | T1/T4（touch 续两族键、终态不续、快照重载省一次读） |
| `run/RunSnapshotEpochCompatTest.java` | 2 | E7 |
| `web/AiControllerSuspendedTouchTest.java` | 6 | T1–T4 三条触发点 + 一条负例 |

### 1.4 修改 · 测试

| 文件 | 改动 |
| --- | --- |
| `ai/conformance/InMemoryRunStore.java` | 实现新增的 `req`/`decided`/`lease`/`fence`/`touch` 面（**同一语义的内存版**，非打桩）：`HSETNX` 首写者胜、fence 比较、`touchActiveRun` 终态判据、`leaseService()` 可注入真设施（默认 null = 无租约路径） |
| `ai/web/AiControllerHealthSseDeliveryTest.java` | 5 条：容错既有字段断言 + 新增 `leaseOk` 缺省/真值两态（断言只升不降） |

### 1.5 脚本与文档

| 文件 | 改动 |
| --- | --- |
| `scripts/b23l1-dual-instance.ps1`（改，+679 行） | ①`suspend-decision` / `instance-kill` 从 SKIP 占位转为**真用例**（决策落 B、杀真 A、B 侧接管）；②新增 `suspend-decision-concurrent`（可选）与 `lease-scripts`（真 Redis 租约冒烟：ACQUIRE/RENEW/FENCED-SET/RELEASE 四段）；③Redis 连通预检；④挂起类用例自建真实 `EXPORT` 任务 + `select-defs` + chat 带 `context`（否则预检不过/桩退化为文本轮）；⑤B 侧订阅就绪哨兵 + `Read-Sse` 空响应防御；⑥`Start-Job` 的 `-WorkingDirectory` 与**绝对路径** ArtifactDir；⑦`Stop-Child` 改 `taskkill /T /F` + `StartTime` 比对（`Kill($true)` 重载在目标进程上不存在）；⑧延迟打印 confirm→B 首帧 P50/P95（L-5 取证） |
| `scripts/README.md`（改） | §3 用例面扩为 8 条（批②五条转真）、退出码口径（`instance-kill` 恒最后）、哨兵与 P50/P95 说明、Redis 预检与"挂起类用例自建任务"说明 |
| `docs/任务看板.md` | 批② 行（本批由提交官落 ✅ 与门禁数字） |
| `docs/决策看板.md` | 批② 裁决行（本批由提交官落） |

## 2. 设计条款逐项落实（§2.2 / §5-2 / §7 批②）

| 设计条款 | 落实 | 位置 |
| --- | --- | --- |
| `LeaseService`：`tryAcquire(key,ttl)→epoch?` / `renew` / `release`，校验一律 Lua 原子 | 四个脚本（抢/续/校验/放）全部单次 `redis.execute`；`tryAcquire` 保留薄包装，主入口是**三态** `acquire` | `LeaseService:91-133/256-295` |
| 续跑租约 `ai:lease:run:<runId>`，TTL 30s、持有者每 10s 续租 | `AiProperties.Lease` 默认 30s/10s；等待方与续跑方都 `markDriving`，终态由 `RunStore.save` 释放 | `AiProperties:326-345`、`ConfirmGate:586/640`、`RunStore:479` |
| `RunSnapshot` 增 `runEpoch` | 新增字段 + `asMap` 透出 + 兼容读取（旧 JSON ⇒ 0） | `RunSnapshot:107/133` |
| 续跑路径所有写回（快照/ledger/claim）带 epoch 校验，不匹配即拒 | 快照 = `ai:fence:run:` 的 Lua 比较写；ledger 记 `epoch`；claim 值前置 epoch 且认领侧对 fence 复核（I5） | `RunStore:175-322/507/822`、`RunStoreClaimFenceTest` |
| `maybeTriggerResume` 从"查本地 latch"升级为"先 tryAcquire" | 抢到 ⇒ 派发 + 交棒；抢不到 ⇒ 落决策 + `LEASE_HELD_ELSEWHERE` + 延迟重试；`signal` 本地快路径保留 | `ConfirmGate:1178-1310` |
| 跨实例决策 = 落 Redis pending + 租约路径续跑 | 决策先 `HSETNX` 落 `ai:decided:`（guard/WAL），再改条目；派发方在任一实例都可发起，抢到租的那个驱动 | `ConfirmGate:885/1072/1123`、`RunStore:822` |
| `ai:claim:` 与 `ai:resume:lock:` 归并进 LeaseService 语义（值改 epoch） | claim 值 = `<epoch>\|<instanceId>`；`ai:resume:lock:` 整体摘除（构造器兼容保留） | `RunStore:822`、`ResumeService:56/559` |
| 挂起期 TTL 续期改由"读侧触发 + 24h 滑动" | `touchActiveRun` 接在 `/runs/current`、`/confirm`、`/frontend-tool-result`、`/events` 四个入口；自带终态判据 | `RunStore:864`、`AiController:236/273/333/473` |
| `/health` 增报 `leaseOk`（仅观测面） | 现算 `PING`（恢复后能自己回绿）；无租约设施 = false | `AiController:787`、`LeaseService:461` |
| 双实例 harness 用例面（§5-2 五条中批②三条） | `suspend-decision` / `instance-kill` / `lease-scripts` 交付并**实跑通过**（见 `补档-B23L1批②fencing-实机核验-DS-Flash.md`） | `scripts/b23l1-dual-instance.ps1` |
| 门禁（§5-1） | 后端 692/702、前端 512/512、typecheck 0、build ✓、check-docs 6/6、脱敏 0 命中 | 见 §5 与提交报告 §1 |

## 3. 红测取证（逐条留痕）

> 口径说明：本批用例与实现同批落地，**没有完整"先写用例再写实现"的过程证据**。为证明"用例有牙"，逐条做**定点变异 → 复跑 → 复原**（变异只存在于临时工作副本，未入库）。"能力级红"（§3.7）是另一类：整个测试类**编译不过**，属"该能力尚不存在"的红，不等价于断言红。

### 3.1 R1：req 位回写导致决策丢失

| 项 | 内容 |
| --- | --- |
| 变异 | `markRequestEmitted` 退回"读整条 JSON → 改 req → 整条写回"，且不加 guard 复判 |
| 复跑 | `RunStoreRequestEmittedAtomicTest` |
| 红态（原文） | `expected: "APPROVED" but was: "PENDING"`（置位前已落的决策被旧副本覆盖回待决） |
| 复原 | 恢复 `HSETNX` 落独立 HASH；`diff` 复核 = 原文件一致 |

### 3.2 R2：首写者胜

| 项 | 内容 |
| --- | --- |
| 变异 | `ai:req:` 的 `HSETNX` 改 `HSET`（后写覆盖先写） |
| 复跑 | `RunStoreRequestEmittedAtomicTest`（R2 例） |
| 红态 | 重复置位把首次置位时刻改掉 ⇒ "首写者胜"断言失败（`reqAtMs` 被第二次请求推进） |
| 复原 | `HSETNX`；`diff` 一致 |

### 3.3 C1：前端超时出口删租约

| 项 | 内容 |
| --- | --- |
| 变异 | 前端超时（`FRONTEND_TIMEOUT`）分支照"终态"处理，调用 `release` |
| 复跑 | `ConfirmGateLeaseTest`（G1 段）/ `ResumeServiceLeaseTest` |
| 红态（原文） | `[超时出口不得删租约] expected: 0 but was: 1`（租约被删 ⇒ 等待方交棒失效，另一实例可抢 ⇒ 双驱动） |
| 复原 | 超时分支只回填结局文本给模型、**继续驱动本轮**，不释放租约；`diff` 一致 |

### 3.4 I2：WAL 已决但无驱动者

| 项 | 内容 |
| --- | --- |
| 变异 | 读侧收敛后不补驱动（`convergeOnDecided` 只回 `DUPLICATE`，不派发续跑） |
| 复跑 | `ConfirmGateDecisionAtomicTest#walDecidedCardIsRepairedAndThenDriven` |
| 红态（原文） | `Wanted but not invoked: resumeService.resume(...)`（卡停在"已决 + 没人推进"） |
| 复原 | 收敛路径带驱动（`ResumeTrigger` 派发）；`diff` 一致 |

### 3.5 I4：设施不可用被报成"别人持有"

| 项 | 内容 |
| --- | --- |
| 变异 | `acquire` 的 catch 分支返回 `HELD_ELSEWHERE` 而不是 `UNAVAILABLE` |
| 复跑 | `LeaseServiceTriStateTest` / `ResumeServiceLeaseTest` |
| 红态（原文） | `expected: "ACCEPTED" but was: "LEASE_HELD_ELSEWHERE"`（Redis 抖动 ⇒ 续跑全线停摆） |
| 复原 | catch 返 `UNAVAILABLE`，调用方按无租约降级放行；`diff` 一致 |

### 3.6 并发抢租误删胜方租约（真 Redis 实证，harness `lease-scripts` 首跑）

| 项 | 内容 |
| --- | --- |
| 现象 | harness `lease-scripts` 首跑 FAIL：并发同主双抢时**败方**走 `release`，把**胜方**的租约键删掉 ⇒ fence 键变 `nil`、快照 `runEpoch=0`、台账 `epoch=0` |
| 红态（原文） | `ResumeServiceLeaseTest:302` 租约键被删成 `nil`（单测复现）；harness 侧是**真 Redis 实证**（`lease-scripts` 首跑） |
| 根因 | 所有权判据用"抢占前 epoch 是多少"推断创建者 —— 并发同主双抢时两边读到同一前置状态，推断必然同形（败方也认为自己是创建者） |
| 修法 | `AcquireResult.created` 由 Lua 返回值末段 `\|1` / `\|0` 直接给出："谁创建谁释放，重入不释放"；`release` 同时改**条件删除**（只摘本次那一任的进程内视图与续租任务，R3） |
| 复原验证 | `lea​se-scripts` 复跑 PASS（ACQUIRE/RENEW/FENCED-SET/RELEASE 各命中一次，fence=14/runEpoch=14/ledgerEpoch=14） |
| 连带 | `ConfirmGate.dispatchResume` 存在**同型缺陷**（拿"抢租返回值"之外的东西推断所有权），随本次一并修（见 `补档-B23L1批②fencing-复核确认-DS-Flash.md` §4） |

### 3.7 能力级红 = 编译红（口径）

`LeaseService` / `InstanceId` / `ai:req:` / `ai:decided:` / `fence` 写 / `touchActiveRun` 这些能力在批②a 之前**不存在**，因此 L/E/G/T/R/D/I 各族用例在实现落地前是**编译红**（`cannot find symbol`），不是断言红。本批的红→绿证据因此分两类：

- **能力级**：编译红 → 编译绿（批②a 存储层 657、批②b 门闸接线 682 两次门禁各推进一次）；
- **断言级**：§3.1–§3.6 的定点变异红，每条都能对着当前代码推演出同一结论。

两类都不做"改断言凑绿"——断言只升不降（复核确认 §6 终核）。

## 4. 施工链（五段门禁，数字为后端用例数）

| 段 | 内容 | 门禁（后端） | 增 |
| --- | --- | --- | --- |
| 批②a 存储层 | `LeaseService` + `InstanceId` + 四个键族 + fence 写 + `ai:req:`/`ai:decided:` + 认领 epoch + 读侧 touch（**只建设施，不接门**） | **657** | +65（自批① 626） |
| 批②b 门闸接线 | `ConfirmGate` 等待方持租/交棒/抢租派发/延迟重试 + `ResumeService` 双签名 + `AiController` 四入口 touch 与 409 家族 + `/health leaseOk` | **682** | +25 |
| 复审收口 | 复审 CHANGES-REQUESTED（1C+7I+9M+Nit）逐项收口：C1 超时出口、I1 可重建结论白名单、I2① 读侧收敛 / I2② 驱动者、I3 归属校验、I4 三态、I5 认领 fence、I7 存活判据、M1–M8 | **687** | +5 |
| R 系二轮 | 二轮复审 R1–R9：R2 存活判据同形化、R3 条件删除、R4 全集群唯一写者、R5 交棒出口口径、R6 告警节流、R7 fence 读 fail-open、R9 `computeIfAbsent` 原子登记 | **689** | +2 |
| created 修复 | 所有权判据改 Lua 返回的 `created` 位（"谁创建谁释放，重入不释放"）+ `ConfirmGate.dispatchResume` 同型缺陷同修 | **692** | +3 |

## 5. 门禁实测（本批最终态）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端单测 | `<MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线） | **exit 0 / BUILD SUCCESS**；mvn 汇总 `Tests run: 702, Failures: 0, Errors: 0, Skipped: 0`；surefire `TEST-*.xml` 求和 = **702**（112 个 suite）——**两口径一致**，见下方口径订正 |
| 前端单测 | `node node_modules/vitest/vitest.mjs run` | **512/512 绿 · 26 文件**（本批前端零改动） |
| 前端类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |
| 前端构建 | `node scripts/build.mjs`（`package.json` 的 `build` = `vue-tsc --noEmit && node scripts/build.mjs`） | **✓ built**（exit 0，`dist/` 产出完整） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（exit 0） |
| 脱敏扫描 | 仓内 `scripts/githooks/scan-lib.sh` 三规则（工作树 diff + 未跟踪新件） | **0 命中** |
| harness | `scripts/b23l1-dual-instance.ps1`（真 Redis + 双实例 + 桩） | **PASS=6/6**（批①三条基线 + 批②三条新用例）+ `lease-scripts` **PASS** |

> 计数口径订正（提交官实测，2026-10-21 09:48）：卡片登记的两口径为「mvn 汇总 702 / surefire 求和 692」，**其中 692 不可复现** —— 同一份 `target/surefire-reports` 的 `tests` 属性求和与 `<testcase>` 元素计数**都是 702**，与 mvn 汇总行逐条对齐（112 个 suite，`Failures=0/Errors=0/Skipped=0`）。差 +10 无法由"排除某包/某类"复现（非 ai 包合计远大于 10、`@ParameterizedTest` 展开数也不是 10），判为施工收口阶段的自报口径偏差（例如报告目录残留/漏读）。**本批最终口径一律以实测 702 为准**；施工链 §4 的 657/682/687/689/692 是各段自报数，保留原样以便追溯，不再据此推导最终门禁。

## 6. 偏差与自决项

### 6.1 自决项（六条，均为"设计稿未写死、必须现场裁"的口径）

| # | 自决 | 理由 | 影响 |
| --- | --- | --- | --- |
| S-1 | `AiController` / `RunStore` 的新增依赖用**字段注入 9 参 `@Autowired`**（构造器签名不变） | 构造器一变就要改 9 处 `new AiController(...)`（含 3 个 `@WebMvcTest`）与多处 `new RunStore(...)`；字段注入是仓内既有先例（批① 的 `frameStore`、`aiFeedbackRepository`） | 既有测试零触碰；未注入时走"无租约"分支（红线 E3） |
| S-2 | 认领时 `claim == null`（HSETNX 未命中但读回为空）**视为抢到** | 读回空值只可能发生在"键刚被删/过期"的窄窗；把它当"别人抢到"会让这一轮**永久无人执行**（比重复执行更坏——重复执行有幂等键兜底） | 与"恰好一次"的取舍方向一致：偏向不放死 |
| S-3 | WAL 收敛**只补结论、不补帧** | 收敛发生在读侧（`pending()`/`pendings()`），补帧会把"已经发给前端的帧"再发一次，破坏帧恰一次；且读侧无 emitter 上下文 | 卡面显示已决、帧不重复；客户端重连按 `?lastSeq` 拿历史 |
| S-4 | `markDriving` 交棒**必须带存活判据**（`stillDriving`） | 纯定时续租的僵尸租约会永远续下去：驱动线程已死、轮已收尾（没走到 `save` 的终态释放）时，别的实例既接不了管、运维也看不出"这个租约没有主人" | 判据转假 ⇒ 停续租 + 按 epoch 归还 |
| S-5 | 确认门构造参数里的租约**优先于**注入的设施 | 死卡兜底路径的租约由调用方（等待方）持有并交棒，`ConfirmGate` 若另抢一把会立刻自拒（同一 run 的第二任）；"调用方已持租"是这一条路径的契约 | 带租约重载不回抢；无租约时回落注入设施 |
| S-6 | `InMemoryRunStore` 的租约设施**可选注入真实现**（默认 null = 无租约） | conformance 7 类 55 条按三参装配跑，强制注入会改写它们的断言基础；需要实例面语义的用例（`ConfirmGateLeaseTest`）显式注入真 `LeaseService` + `FakeLeaseRedis`，**真脚本语义、真 epoch** | 单/多实例两条装配路径都可达，且都不是打桩自证 |

### 6.2 偏差

| # | 偏差 | 理由 | 影响 |
| --- | --- | --- | --- |
| D-1 | `resume(runId)` 旧签名内部**自抢租约**，而不是要求所有调用方改造 | 调用方有 6 处（启动续跑/运维/死卡兜底/测试），逐个改造既扩面又容易漏；自抢 + 非 ACCEPTED 立刻还租，语义等价且零改动 | 旧签名多一次 SET/DEL 往返（低频路径） |
| D-2 | `ai:resume:lock:` 的构造器参数**保留但锁已摘除** | 摘参数会改构造器签名（同 S-1 的爆点）；保留并在类注写明"仅为构造器兼容" | 生产不再有该键 |
| D-3 | 延迟重试为**单次**（`retry-delay` 一拍），不做无限重试/退避阶梯 | 无限重试在"holder 是僵尸"时会变成活锁；单次 + 下一次外部输入（决策/读取/重挂）兜底，已有 G7 锁住"holder 死亡后以严格更大 epoch 接管" | 极端场景需一次外部输入；reaper 预留（见 §7） |
| D-4 | 台账（`ai:ledger:`）只**记录** epoch，不做写回校验 | 台账是"已发生"的取证，不是状态；拒写会造成"执行了但没台账"的更坏形态 | 与"ledger 优先对账"（§2.4）不冲突 |
| D-5 | harness 用例从 5 条扩到 8 条（多 `suspend-decision-concurrent`、`lease-scripts`） | §5-2 只要求三条；并发双决策与租约冒烟是**fencing 本身的实证**，缺了就只能靠单测的替身自证 | 运行时长增加（`lease-scripts` 需等租约过期），故标"可选/终止性" |

## 7. 挂账（本批不销记，逐条带依据）

| # | 条目 | 依据 |
| --- | --- | --- |
| L-9 | **WAL 对 `APPROVED` / `FRONTEND_RESULT` 只剩"卡死出口"**：这两类结论不可从 guard 单独重建（前者需执行侧字段、后者需用户填的 `resultText`），读侧收敛**刻意不合并** ⇒ 写者死在"落 guard / 改条目"之间时，卡会一直显示 PENDING，只能靠取消或 TTL 收敛 | 本报告 §6.1 S-3、`RunStoreWalConvergeTest` 类注、复核确认 §3-R1 |
| L-10 | **claim fence 复核的 TOCTOU 未合成单脚本**：`claimExecution` 是"读 fence → 比较 → HSETNX"，三步之间 fence 仍可能被推进（概率极低：需要恰好落在 TTL 到期与续租失败之间的窗） | 复核确认 §3-R7、`RunStoreClaimFenceTest` 类注 |
| L-11 | **scripts 侧同族未收**：`GetJson` 的 `HttpResponseMessage` 未 Dispose、`Stop-Children` 的 `StartTime` 比对未覆盖全部子进程 | 复核确认 §3-R8（脚本质量项，不影响判定） |
| L-12 | **harness 跨实例 H2 隔离边界**：A、B 各用独立 H2 文件库，任务只在 A 库 ⇒ B 接管后 `start_export` 报 `RESOURCE_NOT_FOUND`；租约/台账证据不受影响（台账仍证 B 执行） | 实机核验 §5 附带观察 |
