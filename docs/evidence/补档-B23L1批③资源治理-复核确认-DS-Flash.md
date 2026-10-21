# 补档 · B23L1 批③ 资源治理复核确认（DS-Flash，2026-10-21）

## 0 自证与纪律

- 复核方自证行（wire `profile.bind` 单行，非首行 metadata）以复核链原始报告为准；本补档由提交官转录（`agentId=agent-1336`），**不代签**。
- 与施工方互盲：只读其落库的施工报告与工作树，未读其 wire。
- 只读纪律：零 commit/push、零服务启停（harness 由实机核验方运行）、只改工作树。

## 1 结论：**READY-WITH-NITS**（0C / 0I / 0M + 6 条已登记挂账 + 若干 Nit）

- 复审 **2 Important + 4 Minor + 7 Nit 逐条核验通过**：I-1（摘除续租）与 I-2（重试 + SKIP 口径）都修在"谁有权持有这把锁/这段证据算不算成立"这个点上，不是"把断言改绿"。
- M-1/M-2/M-3/M-4 四条**全部落成代码 + 用例**（M-3 的字面错误已由复审自纠并留痕，见复审报告 §3 注）。
- N 系七条的处置**逐条有去向**：N-1 订正、N-4 保留并写明理由、N-2/N-3/N-5/N-6/N-7 转挂账（L-13/L-14/L-15/L-18/L-16）。
- **数字仲裁**：复核前两轮自报的 **687**（批② 时点）与 **766**（本批）均为**读数口径错误**，本件更正并统一口径（§5）。最终 **778**。
- **断言只升不降终核通过**（§6）。

## 2 复审项逐条核验

| # | 修法（核验点） | 判定 | 实测依据 |
| --- | --- | --- | --- |
| **I-1** | 资源锁**不再续租**：`resource-ttl`（60s）只作临界区上界；`ToolResourceLocks` 内无 `markDriving`；口径写进类注 | **PASS** | `ToolResourceLocks` 类注 + `AiProperties:340-364`；`LeaseResourceTtlTest#theLockBlocksUntilTheTtlExpiresAndThenANewOwnerTakesOver` 锁住"到期即放开" |
| **I-2** | 跨实例变体：最多 3 轮、任一命中即 PASS、3 轮未命中 **SKIP**（不计 FAIL）；跨实例证据以 **H3** 为准；SKIP 与真故障同形登记 L-17 | **PASS** | `scripts/b23l1-dual-instance.ps1` 的 `Invoke-ResourceLockRace` / `Case-Resource-Lock`；`scripts/README.md` §3.2 边界段 |
| **M-1** | 索引命中后校验 `toolCallId`，不符 ⇒ WARN + 回落扫描 | **PASS** | `RunStore:1177/1195`；`RunStoreIdempotencyIndexTest#anIndexPointingAtAnotherCallsRecordFallsBackToTheScan`（红→绿留痕见施工报告 §3.3） |
| **M-2** | 下标在脚本内推入**之前**取（`LLEN` 先于 `RPUSH`），Java 侧不参与 | **PASS** | `IDEM_PUSH_SCRIPT:224`；`#indexPointsAtTheRightRecordWhenIdsShareAPrefix` |
| **M-3** | 作业级锁失败时**归还**已拿到的任务级锁 | **PASS** | `ToolResourceLocks:150-170`；`ToolResourceLockGateTest#jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook` |
| **M-4** | 认领首次即走三态口，布尔口仅轻装配面回落 | **PASS** | `SpToolCallingManager:684-726`；`RunStoreClaimFenceTest#claimOutcomeDistinguishesWonLostAndStaleFence` |
| N-1 | 降级 WARN 口径订正为"这一拍校验不了，杀招在 fence 与 claim" | **PASS** | `ResumeService` 降级段注释、`ToolResourceLocks` 类注 |
| N-4 | 旧台账缺 `outcome` 在两条路径结论不同的理由写进类注 | **PASS** | `ToolUnknownReconciler` 类注"口径差异（N-4）" |
| N-2/N-3/N-5/N-6/N-7 | 转挂账 L-13/L-14/L-15/L-18/L-16（逐条带代价说明） | **PASS（处置成立）** | 复审报告 §4、施工报告 §7.1 |

## 3 主线语义终核（四条红线）

| 红线 | 判据 | 实测 |
| --- | --- | --- |
| **台账才是判据** | 索引只加速：坏值/错位/旧数据/异常四条路径全部回落 `ledgerFor` | `#aBrokenIndexFallsBackToTheScanInsteadOfReportingNotExecuted` / `#recordsWithoutAnIndexFallBackToTheScan` / `#anIndexPointingAtAnotherCallsRecordFallsBackToTheScan`（正反向都锁） |
| **不确定性不猜成成功** | 只有"台账里有非 unknown 结论"才收敛；其余保持 UNKNOWN | `ToolUnknownReconcilerTest`（8 条，含"台账写着 unknown 保持 unknown"负例） |
| **拿不到就报 blocked** | 锁/目标/写回权三条阻断都是"不执行 + 一句人话 + 可继续同批"，不抛不断流不占认领 | `ToolResourceLockGateTest#blockedCallKeepsFrameOrderAndDoesNotHaltBatch` / `#blockedCallDoesNotConsumeClaimAndCanRunAfterLockRelease`；`ToolTargetResolverGateTest#invalidTargetIsBlockedWithoutClaimOrLedgerAndBatchContinues` |
| **无租约/设施不可用 = 放行**（批② 红线 E3 的 L1 同族） | `UNAVAILABLE` 与 `DISABLED` 都放行，且**零往返**（DISABLED 路径不碰 Redis） | `#unavailableLeaseFacilityDegradesToExecutionWithWarn` / `#disabledLockSkipsWholeSectionWithoutRedisRoundTrip` |

## 4 双粒度与键面的终核

| 项 | 期望 | 实测 |
| --- | --- | --- |
| 键形 | `ai:lock:task:<id>` / `ai:lock:job:<id>:<JOBTYPE>`（大写归一） | `LeaseKeysTest`（5 条）绿 |
| 不同作业类型不互斥 | 导出与发布互不影响 | `#anotherJobTypeDoesNotBlockTheSameTask` 绿 |
| 轮次回收不清锁 | `deleteRun` 清 `ai:idem:` / `ai:req:` / `ai:decided:` / lease / fence / events / seq，**不含 `ai:lock:*`** | `RunStoreIdempotencyIndexTest#deleteRunLeavesResourceLocksAlone` 绿 |
| 运维面 | `describe().keys` 含 `lockTask` / `lockJob`，页面轮为 null | `ResumeDescribeLockKeysTest`（3 条）绿 |

## 5 数字仲裁（**口径更正，以本件为准**）

| 轮次 | 自报 | 真值 | 差 | 成因（已核实） |
| --- | --- | --- | --- | --- |
| 复核第一轮（批② 时点） | 687 | **702** | −15 | 用"`TEST-*.xml` 里 `<testcase>` 元素计数"读 surefire 报告，**`@Nested` 内部类的用例没被计入**（其报告在宿主类的文件里/或以独立文件出现，口径不统一） |
| 复核第二轮（本批） | 766 | **776** | −10 | 同上：漏计 **10** 条 `@Nested` 用例（本批新增的三个 `@Nested` 组恰 10 条） |
| 收口后 | — | **778** | — | 施工链 741 → 776 → 778 |

**统一口径（自本件起生效）**：后端用例数**一律取 `mvn` 输出的 surefire 汇总行**（`[INFO] Tests run: N, Failures: …, Errors: …, Skipped: …`，即 `BUILD SUCCESS` 前最后一行），**不用**"遍历报告文件求和"或"数 `<testcase>`"来交叉校验 —— 两者对 `@Nested` 的覆盖先天不同，差 10 是结构性差异而非漏跑。

提交官复跑已按此口径复核：`Tests run: 778` 与 `TEST-*.xml` 的 `tests` 属性求和 **778**（123 个 suite）一致，故本批门禁 = **778/778**。

## 6 断言只升不降终核

| 检查 | 结论 |
| --- | --- |
| 是否有断言被删/放宽 | **无**：本批全部为新增断言或新增用例；三处"改"均为**扩写**（`ToolResultEnvelopeTest` +2 断言；`RunStoreClaimFenceTest` +1 用例；前端 spec +1 例且既有 3 例原样） |
| 是否有用例被 @Disabled / 跳过 | **无**（`Skipped: 0`） |
| 是否有 mock 化掉了被测语义 | **无**：资源锁/读侧续期/幂等索引用的是"语义替身 + 真脚本语义"（`FakeLeaseRedis` 复刻 Lua 逐行，并保留脚本原文与 KEYS 供断言），不是打桩自证 |

## 7 门禁复跑（复核时点）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `<MAVEN_HOME>/bin/mvn -o test` | **778/778 绿**（0F/0E/0S；112 → **123 suite**） |
| 定向 | 十一类（见复审报告 §6） | 全绿 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6** 绿 |
| 前端 | `vitest run` | **513/513 · 26 文件** |
| typecheck / build | `vue-tsc --noEmit` / `build` | exit 0 / ✓ |

## 8 遗留清单（本确认轮）

| 级别 | 条目 | 去向 |
| --- | --- | --- |
| 挂账 **L-13** | `appendLedger` 脚本后多一次 `EXPIRE` 往返 | 下批（可合并进脚本返回值面） |
| 挂账 **L-14** | `stopDriving(key)` 裸键前缀全表扫描 | 下批（改成 `(key,owner)` 索引表） |
| 挂账 **L-15** | `describe().keys` 的 `lockTask`/`lockJob` 可为 null | 运维面消费方处理 |
| 挂账 **L-16** | `TARGET_INVALID` 仅审批后校验 | 若要做"审批前拦"须改 F5 预检口径（跨批） |
| 挂账 **L-17** | `-SharedDb` 变体 SKIP 与真故障同形 | 证据口径已定（H3 为准 + 标注），实现面不改 |
| 挂账 **L-18** | `matchesToolCall` 缺字段兜底链注释待补 | 下批注释项 |
| 观察 | 跨实例资源锁的**精确时序**证据仍只有"H3 确定性 + 至多一次命中" | 不做微秒级交错实验（成本/收益不成立） |
| 观察 | 既有 flaky `ResumeServiceLeaseTest#concurrentSameRunResumes...`（批② 起） | **非本批引入**，登记 **L-19**（提交官复跑发现，见提交报告 §4） |

**准予入库**：0C/0I/0M，残余均为已登记挂账且不改变本批交付边界；单实例生产语义（无锁无 Redis 路径）零回归。
