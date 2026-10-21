# 补档 · B23L1 批② fencing 复审报告（DS-Flash，2026-10-21）

> 本件为入库补档：逐条按**收口落点与代码最终态**对齐，条目编号沿用收口提交内的注释锚（`C1` / `I1–I7` / `M1–M9` / 二轮 `R1–R9`）。凡与施工自报不一致处，以代码与复跑实测为准并在条目内写明。

- 复审对象：`git status` 中的批② 全部改动（生产 2 新 + 7 改、测试 16 新 + 2 改、scripts 2 改）
- 只读纪律：零 commit/push/stash、零服务启停、零代码改动（唯一新增文件 = 本报告）
- 自证行（wire `profile.bind` 单行，非首行 metadata）：`agentId=agent-1317`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`

## 0. 结论：**CHANGES-REQUESTED**（1 Critical + 7 Important + 9 Minor + 若干 Nit）

一句话：**设施（`LeaseService`）本身是对的，危险全在"谁在什么时候持有/归还它"**。C1 是"前端超时被当成轮次终态 ⇒ 等待方提前还租"，一旦落地就是**双驱动**（另一实例立刻可抢，同一轮两个线程同时推进）。I 系七条集中在"三态混同、归属校验用视图、WAL 无驱动者、认领不看 fence、僵尸续租"，每条都会把一次 Redis 抖动或一次进程死亡放大成"卡死"或"重复执行"。M 系九条是口径与可观测性，不阻断但必须同批落定，否则下一批（③ 资源治理）会在同一组原语上重复踩。

**准予修后入库**；C1 + I 系全部修完、M 系逐条处置后方可进复核。

## 1. 关键路径核对（先看"钱在哪"）

| 路径 | 代码事实 | 判定 |
| --- | --- | --- |
| 等待方持租 | `ConfirmGate:425` 进 `await` 前抢租、`leaseRenewEveryTicks` 心跳续租 | ✅ 方向正确（"等待方持租"是把互斥从进程内搬到 Redis 的唯一办法） |
| 交棒 | `handOffLease:564` → `markDrivingGuarded:586`（带 `stillDriving` 判据） | ⚠️ 首版交棒**无存活判据**，见 I7 / 二轮 R2 |
| 抢租派发 | `maybeTriggerResume:1178` → `dispatchResume:1242` → `runResume:1291` | ✅ 抢到才派发、带同一 lease |
| 抢不到 | 落决策 + `LEASE_HELD_ELSEWHERE` + `scheduleLeaseRetry:1267` | ⚠️ 首版无重试，见 I6 |
| 释放 | 终态 `RunStore.save` 释放 + 等待方出口释放 | ❌ **C1**：前端超时出口也释放 |
| fence 写 | `RunStore:286-322`（持租走 Lua、否则 plain SET） | ✅ 红线 E3 保留 |
| 决策 guard | `ai:decided:` `HSETNX`（`tryRecordDecision`） | ⚠️ 首版未覆盖"放弃"与"回灌"三处，见 R5 |
| req 位 | `ai:req:` `HSETNX` + 读侧合并 | ✅ 根治 L0 M-2 |
| 读侧续期 | `touchActiveRun` 四入口 | ⚠️ 入口清单与终态判据需核对，见 M4 |

## 2. Critical（1 条）

### C1 · 前端超时出口删租约

- **现象**：`FRONTEND_TIMEOUT` 分支被当成"本轮结束"，走与终态相同的释放路径。
- **为什么是 Critical**：前端超时**不是轮次终态**——结局文本要回填给模型，本线程**继续驱动**这一轮（与"挂起即断"语义一致：卡超时只是"用户没答"，不是"这一轮完了"）。租约一删，其余实例的下一次派发立刻能抢到 ⇒ **同一轮两个线程同时推进**（重复帧、settle 分叉、台账双写）。
- **修法**：超时出口只回填结局文本、继续驱动，**不释放**；释放只发生在"真正没有交棒"的出口。
- **红测**：`[超时出口不得删租约] expected: 0 but was: 1`（详见施工报告 §3.3）。
- **关联**：R5 把"交棒出口"的口径统一到这一条（`ConfirmGate` 类注"续跑权只在没有交棒的出口归还"）。

## 3. Important（7 条）

| # | 问题 | 风险 | 收口 |
| --- | --- | --- | --- |
| **I1** | WAL 读侧收敛**不加区分**地把 guard 结论合并进条目 | `APPROVED` 需要执行侧字段（`executed=false` 等）、`FRONTEND_RESULT` 需要用户填的 `resultText` —— 只合并状态会让用户看到一张"已决但没有内容"的卡（等于伪造载荷） | **只合并可重建的三种**：`REJECTED`/`FRONTEND_CANCELLED`/`CANCELLED`；其余留在 PENDING 并登记 L-9 |
| **I2** | WAL 收敛后**没有驱动者** | 卡停在"已决 + 没人推进"（确认门无超时兜底 ⇒ 永久卡死）；`expected` 红态原文：`Wanted but not invoked: resumeService.resume(...)` | 收敛路径带驱动（`convergeOnDecided` → `ResumeTrigger` 派发）；读侧只收敛不驱动是不完整的 |
| **I3** | 归属校验用**进程内视图** `heldEpoch` | 视图在"TTL 已到、续租还没失败"的窗口里会说谎 ⇒ 本进程以为自己还是主人 | 新增 `verifyHeld(key,epoch)`：一次往返、比对在 Lua 内；只用于"入口判定"（续跑准入），不进热路径 |
| **I4** | **设施不可用**与**别人持有**混成一种返回 | 一次 Redis 抖动 ⇒ 启动续跑/运维续跑/死卡兜底**全线停摆**；红态原文：`expected: "ACCEPTED" but was: "LEASE_HELD_ELSEWHERE"` | `acquire` 三态（`ACQUIRED`/`HELD_ELSEWHERE`/`UNAVAILABLE`）；`UNAVAILABLE` 按"无租约降级"放行 |
| **I5** | 认领（`ai:claim:`）**不看 fence** | 持租 ≠ 仍是主人：TTL 到期被别人接管后、续租还没失败的窗口里，本进程仍会执行副作用（导入/发布不可撤销） | 认领前复核 `fence > epoch ⇒ 拒绝认领`（返回 false 与"被他实例认领"同义：不得执行）；`epoch == 0` 不破红线 E3 |
| **I6** | 抢租失败**只落决策、不再派发** | holder 死掉（进程被 kill，没有优雅停机）后，这一轮再也没有驱动者 ⇒ 卡死到 TTL 收敛 | 按 `lease.retry-delay`（35s = TTL + 5s）延迟重试一拍；holder 仍存活则不派发也不再排（G7） |
| **I7** | 续租是**不看业务的定时器** | 驱动线程死掉/轮已收尾却没走到终态释放 ⇒ 租约被**永远**续下去，别的实例接不了管、运维看不出"没有主人" | `markDriving(lease, alive)`：每拍先问存活判据（快照非终态 ∧ 本进程仍是该 epoch 主人），转假即停续租并按 epoch 归还 |

## 4. Minor（9 条）

| # | 问题 | 处置 |
| --- | --- | --- |
| **M1** | `ai:fence:run:<runId>` 的 TTL 若短于快照，"号"会随键消失而回退（下一次 fence 写把陈旧 epoch 当成更大的号） | **已修**：fence 键与快照同寿（写快照时同步续期），fence 只增不减 |
| **M2** | 红线 E3 的"无租约写回"在 fence 已被推进的轮上是**静默放行**，排障时看不见 | **已修**：旁路观测 + `WARN`，按 runId 节流（R6），失败不影响写回（可用性优先） |
| **M3** | `ai:lease:epoch` 是**全局单调**取号键，若被 `maxmemory` 淘汰会从 1 重开 ⇒ 陈旧 epoch 被当成更大号 | **已修（文档口径）**：类注写明运维前提 = 落 `noeviction`（至少不淘汰该键）；租约键本身可淘汰（等价于"到期"），**只有计数器键不能丢** |
| **M4** | `touchActiveRun` 的**入口清单**：只接 `runs/current`、`confirm`、`frontend-tool-result`、`events` 四处，其余读（如历史/会话索引）不续期 | **接受**：这四处是"有人正在看/操作这张卡"的强信号；其余读不需要保活。终态/缺失**不续期**（负例 T4 锁住） |
| **M5** | `ai:resume:lock:` 摘除后构造器参数保留，读代码的人会以为锁还在 | **已修（注释口径）**：类注写明"保留仅为构造器兼容，锁已摘除"，并给出去向（整体并入 `ai:lease:run:`） |
| **M6** | `/health` 的 `leaseOk` **每次真打 PING**：热路径成本与"Redis 恢复后能回绿"的取舍未写明 | **接受并写明**：健康面是低频观测，报"现在能不能抢租"而不是"上次行不行"；无租约设施 ⇒ false |
| **M7** | `RunSnapshot.runEpoch` / `PendingToolCall.claimedEpoch` 进 `asMap()` 后是否改变取证契约 | **接受**：additive 字段，旧载荷缺省为 0；排障需要"这条状态是哪一任租约写的" |
| **M8** | 用例的**实例面维度**未标注：哪些是单实例即可达、哪些必须第二个主人 | **已修**：三个新类的类注各加"单实例即可达 / 必须两个实例 / 不覆盖"三档（DC-11 边界写清，避免"单例会绿就以为多实例安全"） |
| **M9** | `data/` 未入 `.gitignore`，提交官可能顺手 `git add -A` 把它带进来 | **不采纳改 `.gitignore`**（历史遗留、改忽略规则属另一件事）；**提交纪律**：显式路径 add，永不 `add -A`/`add .` |

### Nit（顺手改的都改了）

- `LeaseService` 类注补"所有权口径：谁创建谁释放，重入不释放"（后续演化为 `AcquireResult.created`）。
- `ResumeService` 的 `describe()` 键面补 `lease`/`fence`（排障要能一眼看到租约键与 fence 键）。
- `RunStoreRequestEmittedAtomicTest` 的类注写清"R1/R2/R3/R6 四条各锁什么"，避免"一红全红"时分不清责任。
- `scripts/README.md` 的用例面表格补"退出码口径 + `instance-kill` 恒最后 + `lease-scripts` 会 kill A"。

## 5. 攻击面九条结论表（a–i）

| # | 攻击面 | 攻击构造 | 结论 | 依据 |
| --- | --- | --- | --- | --- |
| **a** | 租约过期与重抢 | 让 holder 的 TTL 到期，另一实例立刻抢 | **被拦**：`INCR` 全局号 ⇒ 新 epoch 严格更大；旧主人写回被 fence 拒（E2）；旧主人视图由"续租失败"或"认领前 fence 复核"作废 | L4/L10、E2、I5 |
| **b** | 交棒窗口 | 决策到达与等待方离开 `await` 同时发生 | **被拦**：交棒**不释放**、续租由调度器接手；释放只在不交棒的出口（C1 修后口径） | C1、G1/G2、`ConfirmGate:564` |
| **c** | 抢不到就放死 | holder 是僵尸（被 kill、无优雅停机），决策方只落决策不派发 | **先漏后补（I6）**：延迟重试一拍；holder 仍存活则不派发也不再排（避免活锁）。**残留**：需一次外部输入才能接管（已登记，不阻断） | G4/G7、D-3 |
| **d** | Redis 不可用 | 关掉 Redis 后走完整决策/续跑 | **被拦**：三态分明 ⇒ `UNAVAILABLE` 走无租约降级放行（进程内互斥），不让抖动变成全线停摆 | I4、L8、`LeaseServiceTriStateTest` |
| **e** | WAL 两步之间崩溃 | 写完 `ai:decided:` 后立刻 kill，条目仍 PENDING | **部分被拦**：可重建结论读侧收敛 + 带驱动；**不可重建结论（`APPROVED`/`FRONTEND_RESULT`）只剩卡死出口** ⇒ 挂账 **L-9** | I1/I2、`RunStoreWalConvergeTest` |
| **f** | 双决策并发 | 两个实例各读 PENDING、各自落库 | **被拦**：`ai:decided:` `HSETNX` 首写者胜 ⇒ 恰一个 accepted、`confirm_decision` 帧恰一次（栅栏用例确定性红） | R4、D1/D2、`ConfirmGateDecisionAtomicTest` |
| **g** | 陈旧写覆盖新主人 | 旧主人拿旧快照写回 | **被拦**：fence 比较写（`fence ≤ epoch` 才落库并推进）；**残留**：claim 侧"读 fence → 比较 → HSETNX"非单脚本，存在窄 TOCTOU 窗 ⇒ 挂账 **L-10** | E1/E2、I5、复核确认 §3-R7 |
| **h** | req 位与决策互相覆写 | 置位与决策并发（L0 M-2 的 lost update） | **被拦**：req 拆独立 HASH `HSETNX`，置位不再触碰条目 ⇒ 决策不可能被覆盖回 PENDING | R1/R2/R3/R6、`RunStoreRequestEmittedAtomicTest` |
| **i** | 证据真伪（harness 假绿） | 端口被旧实例占用、B 侧订阅未就绪、哨兵未写、相对路径落错目录 | **先漏后补**：端口连通预检 + Redis 连通预检 + 订阅就绪哨兵 + 绝对路径 + `Read-Sse` 空响应防御；三轮修复后 **PASS=6/6** 且 `lease-scripts` 用真 Redis 命中四段 | 实机核验 §2–§4 |

## 6. 门禁复跑（复审时点）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `<MAVEN_HOME>/bin/mvn -o test` | **687/687 绿**（0F/0E/0S）→ 收口后 689 → created 修复后 **692**（最终态由提交官复跑为 702，见提交报告 §1.2） |
| 定向 | `-Dtest='LeaseServiceTest,LeaseServiceTriStateTest,ConfirmGateLeaseTest,ConfirmGateDecisionAtomicTest,ResumeServiceLeaseTest,RunStoreFencedWriteTest,RunStoreClaimFenceTest,RunStoreRequestEmittedAtomicTest,RunStoreWalConvergeTest,RunStoreTouchTest,RunStoreDecisionGuardTest,RunStoreClaimEpochTest,RunSnapshotEpochCompatTest,AiControllerSuspendedTouchTest'` | 全绿 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 绿 |
| 前端 | 未跑（按卡免；`git status` 无 `frontend/` 条目 ⇒ 零改动成立，512/512 与批①同数） | — |

## 7. 遗留与去向

| 级别 | 条目 | 去向 |
| --- | --- | --- |
| C | C1 超时出口删租约 | 收口（必修） |
| I | I1–I7 | 收口 |
| M | M1–M9 | 逐条处置（M9 不采纳改 `.gitignore`，落提交纪律） |
| 挂账 | WAL 对 `APPROVED`/`FRONTEND_RESULT` 的卡死出口 | **L-9** |
| 挂账 | claim fence 复核 TOCTOU | **L-10**（复核轮补登） |
| 挂账 | scripts 侧 `GetJson` Dispose / `Stop-Children` StartTime | **L-11**（复核轮补登） |
| 挂账 | harness 跨实例 H2 隔离边界 | **L-12**（实机核验补登） |

**准予修后入库**：设施方向正确、红线 E3 未被破；C1 与 I 系必须在同一批内收口（它们共同决定"谁有权驱动这一轮"），M 系同批落定。
