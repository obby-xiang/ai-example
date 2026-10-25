# Task 3 Fix-1 报告：按复审补齐 RT-20/21/28/34/35、`ai:observed:` 销毁面与补丁卫生

**结论：DONE**（复审列出的 7 组要求全部落地，每组都有确定性 RED；累计补丁对<b>实际 main</b> 可干净应用，且<b>无整文件 LF/CRLF 重写</b>）

- 隔离副本（唯一改动位置）：`<隔离副本>/_verify-20261025-session-integrity/backend`
- RED 基底：`<隔离副本>/_verify-20261025-session-integrity/red-baseline2`（main 生产代码原样 + 本轮全部测试类/替身）
- 累计补丁：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-3-fix1.patch`（取代 `task-3-session-integrity.patch`）
- 日志：`logs/fix1-red.log`、`logs/fix1-green-own.log`、`logs/fix1-green-adjacent.log`
- 未改 main、未提交、未 push、未派子代理；main 的 `git status` 中我负责的文件无变化（本报告与补丁在 gitignore 的 `.superpowers/sdd/` 下）

复审判定"第一版是部分实现"成立：本轮的 6 个生产文件里，`RunStore` / `ResilientChatService` / `SessionGate` 上一轮一行未动，本轮全部按复审落点补齐。

---

## 1. 逐条落实（复审要求 → 改动 → RED 证据）

| 复审项 | 落地 | 主要位置 | 确定性 RED |
|---|---|---|---|
| **(1) RT-20**：旧驱动不得赢 DB 历史/记忆，不得发第二份终帧 | `finish()`/`cancelTerminal()`/`convergeMemory()`/`persistMemory(runId,…)` 在<b>写之前</b>过同一道写门；出帧侧沿用同一个 `runLeaseAuthority`（fence-ahead 由写门另行判定，见 RT-21） | `ResilientChatService.java:1005`（终态）、`:1282`（取消态）、`:1426`（记忆/历史收敛）、`:1480`（`persistMemory` 新签名，保留无 runId 兼容口）、`:1527`（`persistAllowed`） | `ResilientChatStaleDriverGuardTest`：LOST / UNAVAILABLE / fence-ahead 三例，main 上 `repository.saves expected:0 but was:1` + 终帧存在 |
| **(1) RT-21**：`HELD-but-fence-ahead` 不得出帧/写台账/取号 | 抽出唯一写门 `RunStore#permittedToWrite`（authority + `fence ≤ 本进程 epoch`；fence 读不出 ⇒ 拒写），接到台账/事件帧/业务序号三处；`epoch==0` 保持 E3 普通写 | `RunStore.java:1175`（写门）、`:1201/:1216`（留痕）、`:1941`（台账）、`:2207`（帧）、`:2262`（序号） | `RunStoreFenceAheadWriteGateTest.fenceAheadDriverCannotWriteLedgerFramesOrSeq`：main 上台账被写入（`Expecting empty but was: [{... toolCallId="call-fence-ahead"}]`） |
| **(2) RT-34**：续期返回 0 必须记降级 | watchdog 的 `renewLockOnce == false` 分支置 `holder.degraded` + `lockLost` 计数 + WARN（与异常同口径）；后续 409 的 `degraded` 字段因此如实上报 | `SessionGate.java:450`、`:361`（`lockLost()` 观测面） | `SessionGateBusyUnknownTest.renewReturningZeroMarksTheGateDegraded`：main 上 `degraded` 恒 false |
| **(2) RT-35**：诊断 GET 失败不得回退成调用方自己的 runId | `observedRunId` 不再有 fallback（读失败/键消失 ⇒ `null`）；新增 `busyHeldByAnother`：不可知 ⇒ `Busy(runId=null, degraded=true, unknown=true)`；`releaseDeadHeld(null)` 直接放弃（不猜着删锁）；**未改 ResumeService**（其 `!runId.equals(busy.runId())` 对 null 天然成立 ⇒ 不再误认"取门成功"） | `SessionGate.java:503`（`Busy` 三态）、`:409`、`:424` | `SessionGateBusyUnknownTest.unknownHolderIsNeverReportedAsTheCallersOwnRun`（main 上 `busy.runId()==MY_RUN`）、`strongReleaseWithoutAKnownRunIdTouchesNoLock` |
| **(3) RT-28**：会话→run 索引 `complete` 哨兵不得掩盖缺成员 | 删除尾部新增 `residualRuns`：按全局 `ai:runs` 复核本会话 run，终态即删、活跃即 `deferred`（`complete=false` 可重试）、复核失败记可重试错误、触顶也按未确认处理 | `AiSessionPurgeService.java:887`、`:121`（`MAX_RESIDUAL_RUNS`）、`executeDeletion` 的第二趟 | `AiSessionPurgeRunIndexResidueTest`：哨兵在+缺成员（终态/活跃各一例）在 main 上 `complete=true` 且 `run-b` 键残留 |
| **(4) `ai:observed:<runId>` 销毁面** | 走既有 `deleteObservabilityKeys`：与 `ai:beat:/ai:activity:/ai:cancel:` 同列，按 runId <b>单键 DEL</b>，删除失败记可重试错误（错误进 `complete=false`）。键形取自 Task 2 契约，<b>不引用其类</b>（Task 2 未并入时是 no-op） | `AiSessionPurgeService.java:101`（`OBSERVED_PREFIX`）、`:975` | `AiSessionPurgeRunIndexResidueTest.observedKeyOfThisSessionsRunsIsDeletedAndOtherSessionsAreUntouched` + `observedKeyDeleteFailureIsReportedAsRetryableIncomplete`（main 上观测位仍在） |
| **(5) I1**：staging 扫描必须转义 glob（含 `[` 与反斜杠） | `pattern = escapeGlob(prefix) + "*"`，逐字前缀过滤保留 | `AiSessionPurgeService.java:671` | `stagingScanEscapesGlobMetacharactersInSessionIds`（`sid="a[b"`）+ `stagingScanEscapesBackslashInSessionIds`（`sid="a\b"`，且不得命中 `{ab}`）；替身 `PurgeFakeRedis` 同时改为<b>忠实复刻 Redis `stringmatchlen`</b>（`*` `?` `[...]` `^` 区间与反斜杠转义），否则该用例在旧替身上是假绿 |
| **(6) I2**：RENAME 回包丢失也必须复检 | 复检+撤销移入 `finally`（`aborted` 标记抑制后续索引/legacy 写），异常照旧上抛（<b>不吞错误</b>）；守卫命中即删该会话记忆键（本笔未确认落地的旧历史同属"已销毁会话数据"） | `RedisChatMemoryRepository.java:382`、`:401` | `RedisChatMemoryWriteBackRaceTest.lostRenameReplyStillTriggersTheRecheckAndUndo`（`FakeMemoryRedis.loseRenameReply` = 服务端生效+回包丢失） |
| **(7) I3**：复核 SCAN 失败不得当"已删净" | `StagingClear(known, remaining)`；`known=false` ⇒ `memory.deleted=false` + 可重试错误 | `AiSessionPurgeService.java:825`、`:838` | `AiSessionPurgeMemoryResidueTest.residueScanFailureIsNotTreatedAsDeleted`（main 上 `deleted=true`） |
| **(7) I4**：缺成员用例要真构造"数据还在" | 旧用例改为：记忆索引<b>从来没有</b>该会话成员 + 删除口<b>没命中</b>真键 ⇒ 断言 `removedFromIndex=true` 但 `deleted=false`、`complete=false` | `AiSessionPurgeMemoryResidueTest.missingIndexMemberCannotMaskResidualMemoryData` | main 上 `complete=true` |
| **I6/B5**：补丁不得整文件重写 | 生成器按<b>每个文件在 main 的原始行尾</b>归一化后再 diff（CRLF 文件保持 CRLF、LF 文件保持 LF），无 `@@ -1,…` 整文件 hunk | `task-3-fix1.patch` | `grep "^@@ -1,"` ⇒ **none**；`RedisChatMemoryRepository` `+137/-13`（原 `-1412/+1521`）、`AiSessionPurgeService` `+216/-9`（原 `-1011/+1084`） |
| **I7**：文档与事实对齐 | 类注补：staging 扫描"与归档同口径（含 escapeGlob）"、删除面含 `ai:observed:` 与 RT-28 复核、`saveAll` 复检在 `finally`、`SessionGate` 的 RT-34/35 契约 | 三个生产类类注 | — |

仍然遵守的红线：未放宽 fencing、未删任何删除保护、未整面清业务数据；`legacyEligible` 的普通 id 迁移面保持（`ordinaryIdsKeepTheLegacyMigrationPath` 仍绿）。

---

## 2. 测试账目（RED → GREEN）

新增/更新测试（8 个新类 + 2 个替身 + 1 处相邻用例断言）：

| 测试类 | 例数 | main 上失败 |
|---|---|---|
| `ai/session/SessionGateBusyUnknownTest`（新） | 3 | 3 |
| `ai/run/RunStoreFenceAheadWriteGateTest`（新） | 3 | 1 |
| `ai/run/ResilientChatStaleDriverGuardTest`（新） | 4 | 3 |
| `ai/session/AiSessionPurgeRunIndexResidueTest`（新） | 4 | 4 |
| `ai/session/AiSessionPurgeMemoryResidueTest`（改：+I1 两支 `[` / `\`、+I3、重写 I4） | 7 | 7 |
| `ai/memory/RedisChatMemoryWriteBackRaceTest`（改：+I2） | 5 | 3 |
| `ai/memory/RedisChatMemoryKeyNamespaceTest`（上轮，保留） | 6 | 5 |
| `ai/run/LeaseAuthorityProbeFailClosedTest`（上轮，保留） | 2 | 2 |
| **合计** | **34** | **28** |

（main 上仍绿的 6 例是刻意的回归钉子：legacy 迁移面、入口守卫、正常写回、健康驱动、fence 未超前、无租约设施。）

- **RED**（`red-baseline2` = main 生产代码 + 本轮测试）：主跑 `Tests run: 32, Failures: 26, Errors: 0`（`logs/fix1-red.log`，7 个类）+ 单跑 `LeaseAuthorityProbeFailClosedTest` `Tests run: 2, Failures: 2` ⇒ **34 例 / 28 例失败**
- **GREEN · 自有**：`Tests run: 34, Failures: 0, Errors: 0`（`logs/fix1-green-own.log`）
- **GREEN · 相邻（每域 2 个）**：`Tests run: 104, Failures: 0, Errors: 0`（`logs/fix1-green-adjacent.log`）
  - 会话门：`SessionGateLeaseTokenTest`(14)、`ResumeSessionGateTest`(4)
  - 租约/写门：`RunStoreClaimFenceTest`(4)、`LeaseLossReadOnlyTest`(5)
  - 韧性：`ResilientChatServiceMemoryWriteTest`(7)、`ConvergeMemoryToolCallStatesTest`(20)
  - 销毁：`AiSessionPurgeServiceTest`(23)、`SessionPurgeGuardFailClosedTest`(5)
  - 记忆：`RedisChatMemoryRepositoryClusterSafetyTest`(18)、`RedisChatMemoryRepositoryDeleteTest`(4)

命令口径（IDEA Maven 3.9.16 + JDK 21，离线、不 clean、无真实 Redis/拓扑）：

```bash
export JAVA_HOME="<本机工具链>\Java\jdk-21.0.12"
export PATH="<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin:$JAVA_HOME/bin:$PATH"
mvn -o -B -Dtest='<聚焦类清单>' test
```

**一处相邻用例断言按新契约更新**（`AiSessionPurgeServiceTest:444`）：旧断言是 `verify(spyStore, never()).get("run-b")`（"有索引时绝不碰他会话快照"）。RT-28 的复核按设计必须按全局索引归属 run，因此改为
`containsExactly("run-a")`（他会话的 run 不得成为候选）+ `atMost(1)`（至多读一次快照），语义从"绝不读"收窄为"不进候选"，并在用例内注明原因。

---

## 3. 补丁与应用

```
task-3-fix1.patch  （17 个文件；相对"实际 main"的累计补丁，替代上一版）
  6 个生产文件：RedisChatMemoryRepository +137/-13、LeaseService +29/-7、RunStore +88/-9、
                ResilientChatService +74/-18、SessionGate +91/-13、AiSessionPurgeService +216/-9
  3 个既有测试：AiSessionPurgeServiceTest +5/-1、PurgeFakeRedis +126/-24、FakeMemoryRedis +11/-0
  8 个新测试类（见上表）
```

- `git -c core.autocrlf=false apply --check` 在 main 的 pristine 副本上通过；实际应用后 17 个文件与验证树<b>逐字节一致</b>（`patchcheck6`）。
- **无整文件重写**：补丁内不存在 `@@ -1,…` 整文件 hunk；行尾按源文件保持（`RedisChatMemoryRepository`/`SessionGate`/`AiSessionPurgeService`/两个替身/`AiSessionPurgeServiceTest` 为 CRLF，`RunStore`/`ResilientChatService`/`LeaseService` 为 LF）。
- 应用建议：`git -c core.autocrlf=false apply .superpowers/sdd/2026-10-25-ai-remediation/task-3-fix1.patch`（仓库 `core.autocrlf=true`，不加该开关会把 LF 补丁行写成 CRLF 并污染 diff；本次已按源行尾生成，两种方式都不会产生整文件改动，但 `false` 最保险）。

---

## 4. 必须由后续串行任务承接的残余（不静默丢）

1. **RT-21 的生产出帧闸（SseChatEmitter / RedisFrameStore）**：`SseChatEmitter#emit` 只判 `authority.readOnly()`，不看 fence。本批把 `RunStore.appendEvent`（兼容/直塞帧）、台账、序号、快照、DB 历史、记忆全部收口，但<b>生产 SSE 帧</b>仍只剩 authority 判据 ⇒ `HELD-but-fence-ahead` 的窗口内旧驱动仍可能推帧（时长 = 一个续租间隔；新主人侧有 fence 拒写，数据面不会双写）。
   - 承接方式（一行）：`SseChatEmitter#emit` 的判定换成 `store.permittedToWrite(runId, "事件帧")`（或追加 `fence ≤ epoch` 比较）。
   - 为什么没做：`SseChatEmitter` / `RedisFrameStore` 不在本任务批准的 6 个文件内，且与 Task 1/8 的 RunStore 串行规则相邻 —— 属 <b>Task 8 串行窗口</b>。
2. **I5：写者在 RENAME 之后崩溃**：仍无人复检（守卫有保留期，但没有对账者）。本批把"回包丢失"这条可捕获路径闭合了（I2），崩溃路径需要销毁完成后的补偿（例如 `SessionPurgeRetryRunner` 在保留期内复检 `hasLiveMemory` 并按需补删）——建议并入 <b>Task 8</b>，或由用户明确接受"TTL 内残留"。
3. **`/health` 的 `sessionLockDegraded` 字段**：`SessionGate#lockLost()` 已提供计数，接线在 `AiController`（不在本任务文件集）⇒ Task 8。
4. **RT-35 的 409 文案**：`Busy.runId=null` 时 `AiController` 仍会拼出 `reattach: /api/ai/events/null`（不会崩溃、也不再自指调用方 runId）。建议 `AiController` 对 `busy.unknown()` 省略 `reattach` 并回落 503 —— Task 8（或单独小修）。
5. **Task 2 的 `ai:observed:` 迁移排除**：`EXCLUDED_KEY_PREFIXES` 由其所有者登记；本批只做"销毁时删除"（不依赖其类，未并入时 no-op）。
6. **真实 Redis/Cluster 未验证**：staging 扫描的转义与逐 master 完整性、`RENAME` 与复检的真实时序、TTL 语义，均需最终统一验证；`PurgeFakeRedis` 的 glob 已按 Redis `stringmatchlen` 复刻（含 `[` / `^` / `a-c` / `\`），但仍是替身。

---

## 5. 关注点（设计取舍，已写进代码注释）

1. **写门成本**：持租时每笔多一次单键 `GET fence`（帧/台账/序号/历史/记忆共用）。不缓存是刻意的 —— 缓存窗口恰好会放过 "HELD-but-fence-ahead"。
2. **`permittedToWrite` 是唯一写门**：帧、台账、序号、DB 历史、记忆共享同一判据，避免"快照拒写但答案已落库"的半只读状态；`epoch==0`（无租约语义）保持 E3 普通写不变。
3. **`saveAll` 的 finally 复检**会删掉该会话记忆键 —— 即使本笔 RENAME 未生效：守卫已装 = 该会话正在销毁，它的记忆键本来就不该留；删除失败只 WARN 并留键名对账。
4. **RT-28 复核代价**：销毁端点每次多一轮 `SMEMBERS ai:runs` + 对非候选成员各读一次快照（上限 5000，触顶按"未确认"处理）。这是复审认可的取舍，并已把相关相邻用例的断言从"绝不读"改为"不进候选"。
5. **`persistMemory` 保留无 runId 兼容口**（`persistMemory(sessionId, msgs, stage)`）：该重载不做续跑权判定（无 runId 无从判定），只服务既有装配/单测调用；生产调用点全部走带 runId 的版本。
