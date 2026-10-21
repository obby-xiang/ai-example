# 补档 · B23L1 批② fencing 复核确认（DS-Flash，2026-10-21）

## 0 自证行（wire 实测）

- `agentId=agent-1318`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1318/wire.jsonl`
- `grep -n 'profile.bind'` 命中**第 5 行**（首行为 `{"type":"metadata",...}`），原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1318","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 与施工方（agent-1316）互盲：只读其落库的施工报告与工作树，未读其 wire。
- 只读纪律：零 commit/push、零服务启停（harness 由实机核验方运行）、只改工作树。

## 1 结论：**READY-WITH-NITS**（0C / 0I / 0M / 3 残余挂账 + 若干 Nit）

- 复审 1C + 7I + 9M **逐条核验通过**：C1 与 I1–I7 的修法都对在"谁有权驱动这一轮"这个点上，不是"把断言改绿"。
- 二轮复审（R1–R9）**七条已收口**（R2/R3/R4/R5/R6/R7-WARN/R9），**三条残余**转挂账（R1→L-9 / R7-TOCTOU→L-10 / R8→L-11）。
- `created` 修复（所有权口径"谁创建谁释放，重入不释放"）核验通过，且发现并同修 `ConfirmGate.dispatchResume` 的**同型缺陷**。
- **数字订正**：收口报告自报 697 条后端用例，实测为 **687**（差 10）。订正后：复审时点 682 → 收口 687 → R 系二轮 689 → created 修复 692。
- **断言只升不降终核通过**：全量 diff 里唯一的"替换型"断言是 `scriptCalls → verifyCalls`（从"脚本被调了几次"升级为"校验用了哪个判定口"），判定**更强**，不算降级。

## 2 复审项逐条核验（C1 + I1–I7）

| # | 修法（核验点） | 判定 | 实测依据 |
| --- | --- | --- | --- |
| **C1** | 前端超时分支不再释放租约：结局文本回填模型后**继续驱动**；"续跑权只在没有交棒的出口归还"写进类注 | **PASS** | `ConfirmGate` 类是；红态原文 `[超时出口不得删租约] expected: 0 but was: 1` 已由施工报告 §3.3 留痕；R5 把出口口径统一 |
| **I1** | 读侧只合并**可重建**结论（`REJECTED`/`FRONTEND_CANCELLED`/`CANCELLED`）；`APPROVED`/`FRONTEND_RESULT` 不合并 | **PASS** | `RunStore` 的 `applyDecisionMarker` + 白名单常量；`RunStoreWalConvergeTest` 正负两向都锁 |
| **I2** | 收敛带驱动：`convergeOnDecided` → `ResumeTrigger` 派发；读侧只收敛不写库 | **PASS** | `ConfirmGate:952`；红态 `Wanted but not invoked: resumeService.resume(...)` 已被 `walDecidedCardIsRepairedAndThenDriven` 转绿 |
| **I3** | `verifyHeld(key, epoch)` 对 Redis 真值一次往返；不进热路径 | **PASS** | `LeaseService:307`；`LeaseServiceTriStateTest` 三态锁（HELD/LOST/UNAVAILABLE） |
| **I4** | 三态 `ACQUIRED`/`HELD_ELSEWHERE`/`UNAVAILABLE`；`UNAVAILABLE` 走无租约降级 | **PASS** | `LeaseService:143/291`；红态 `expected: "ACCEPTED" but was: "LEASE_HELD_ELSEWHERE"` 已转绿 |
| **I5** | 认领前 fence 复核（`fence > epoch` 拒），`epoch == 0` 不破 E3 | **PASS** | `RunStoreClaimFenceTest`（3 条，含"无租约保持原语义"负例） |
| **I6** | 延迟重试一拍（`retry-delay` 35s）；holder 存活则不派发也不再排 | **PASS** | `ConfirmGate:1267`；G4/G7 两向锁 |
| **I7** | `markDriving(lease, alive)`：每拍先问存活判据，转假即停续租 + 归还 | **PASS** | `LeaseService:404/431`；死卡兜底与交棒两处都带判据 |
| M1–M9 | 见复审报告 §4；M9 **不采纳**改 `.gitignore`，落提交纪律（显式路径 add） | **PASS** | 逐条对照代码注释与 `scripts/README.md` |

## 3 二轮复审（R1–R9）

| # | 问题 | 处置 | 判定 |
| --- | --- | --- | --- |
| **R1** | WAL 对**不可重建结论**（`APPROVED` / `FRONTEND_RESULT`）只剩"卡死出口"：写者死在 guard 与条目之间时，卡一直 PENDING，只能等取消或 TTL | **挂账 L-9**（不修：补帧会破帧恰一次，补状态会伪造载荷；根治需"执行侧字段/用户载荷"进 guard，属批③面） | 残余（已登记） |
| **R2** | 延迟重试路径的存活判据与交棒路径**不同形**（一处带 `stillDriving`、一处不带） | **已修**：两条路径共用 `markDrivingGuarded`（快照非终态 ∧ 本进程仍是该 epoch 主人） | PASS |
| **R3** | `release(旧租约)` 会把本进程**当前**那一任的视图与续租任务一起误杀；`verifyHeld` 失败时顺手作废了更新的那一任 | **已修**：视图与续租任务改**条件删除**（`held.remove(key, lease)` 才 `stopDriving`）；`verifyHeld` 只摘"被校验的那一任" | PASS |
| **R4** | 决策路径的"读态 → 判 PENDING → 改写 → 落库"只在**实例内**串行；两实例各读 PENDING 会双写 | **已修**：决策/回灌/**放弃**三处统一 `HSETNX ai:decided:` guard（全集群唯一写者）；`FirstTwoReadsBarrier` 让失效实现确定性红 | PASS |
| **R5** | 带租约重载用进程内视图判"我是否仍是主人"（TTL 已到、续租未失败的窗口会说谎） | **已修**：改 `verifyHeld` 对 Redis 真值；设施不可用按无租约降级放行 | PASS |
| **R6** | 无租约写回在 fence 已推进的轮上静默放行，排障看不见 | **已修（WARN 侧）**：旁路观测 + `WARN`，按 runId 节流；观测失败不影响写回 | PASS |
| **R7** | fence 读失败时的取舍未定 | **已修（fail-open）**：读失败按 0 处理（不拒认领）——**放宽而非收紧**，因为"读不到"不等于"被超越"；**残余 TOCTOU**："读 fence → 比较 → HSETNX"非单脚本 | WARN + 残余 **L-10** |
| **R8** | scripts 侧：`GetJson` 的 `HttpResponseMessage` 未 `Dispose`；`Stop-Children` 的 `StartTime` 比对未覆盖全部子进程 | **未收**（脚本质量项，不影响判定；`taskkill /T /F` 已落地） | 残余 **L-11** |
| **R9** | `markDriving` 的登记是"`containsKey` 判空 → schedule → put"：两线程同到会各排一个任务，后 put 的把先前的 handle 覆盖成**孤儿任务**（release 之后还在续租） | **已修**：`computeIfAbsent` 原子登记 | PASS |

## 4 created 修复（含同型缺陷）

### 4.1 缺陷

harness `lease-scripts` 首跑 FAIL：**并发同主双抢**时，败方走"我还租"把**胜方**的租约键删掉 ⇒ fence 键变 `nil`、快照 `runEpoch=0`、台账 `epoch=0`。根因 = 所有权判据是"抢占前后 epoch 变了没变"——并发同主双抢时两边读到同一前置状态，**推断必然同形**（败方也认为自己是创建者）。

### 4.2 修法

- `ACQUIRE_SCRIPT` 返回值末段直接给结论：`|1` = 本次 `INCR + SET` 是我做的（创建者），`|0` = 命中现值（无释放权）；
- `AcquireResult.created` 承载该位；口径写进类注与 record 注：**"谁创建谁释放，重入不释放"**；
- `release` 与 `verifyHeld` 的进程内清理改条件删除（R3 已同批落）。

### 4.3 同型缺陷（`ConfirmGate.dispatchResume`）

`dispatchResume` 里也有一处"用抢租之外的信号推断所有权"的同型写法（拿派发路径的局部状态当"我是创建者"）。**已同修**：派发只认 `AcquireResult.created` / 调用方交棒的 lease，不再自造判据。

### 4.4 核验

| 面 | 期望 | 实测 |
| --- | --- | --- |
| 单测 | `ResumeServiceLeaseTest` 全绿（含 `:302` 租约键被删成 `nil` 的复现用例转绿） | 绿 |
| 真 Redis | `lease-scripts`：ACQUIRE / RENEW / FENCED-SET / RELEASE **各命中一次**；`fence=14, runEpoch=14, ledgerEpoch=14` | PASS（见实机核验 §4） |

## 5 数字与断言的终核

| 项 | 自报 | 实测/订正 |
| --- | --- | --- |
| 收口轮用例数 | 697 | **687**（差 10；订正后施工链 = 657 → 682 → 687 → 689 → 692） |
| 收口轮测试类 | — | 收口后按 surefire 计数，最终态 112 个 suite |
| 断言方向 | — | **只升不降**：唯一"替换型"= `scriptCalls → verifyCalls`（从"调了几次脚本"升级为"校验走了哪个判定口"），判定更强；其余均为新增断言或 `times(n)` 上调 |
| 红线 E3 | 未破 | 未破：`RunStoreFencedWriteTest#withoutALeaseSaveStaysAPlainSet` + `RunStoreClaimFenceTest#claimWithoutALeaseKeepsThePreviousSemantics` 双锁 |

## 6 门禁复跑（复核时点）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `<MAVEN_HOME>/bin/mvn -o test` | **692/692 绿**（0F/0E/0S）—— 与 created 修复后的自报一致 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6** 绿（exit 0） |
| 前端 | 未跑（前端零改动成立） | — |
| harness | 由实机核验方跑；复核只核证据文件与报告摘录 | — |

## 7 遗留清单（本确认轮）

| 级别 | 条目 | 去向 |
| --- | --- | --- |
| 挂账 **L-9** | WAL 对 `APPROVED`/`FRONTEND_RESULT` 只剩卡死出口（不可重建结论不合并） | 批③ 或"执行侧字段进 guard"专项 |
| 挂账 **L-10** | claim fence 复核的"读 → 比较 → HSETNX"TOCTOU 未合成单脚本 | 批③（资源租约要复用同一原语，届时一并收） |
| 挂账 **L-11** | scripts 侧 `GetJson` Dispose / `Stop-Children` StartTime 同族未收 | harness 维护批 |
| 观察 | 延迟重试为**单次**（非退避阶梯）；极端场景需一次外部输入才接管 | 已写入施工报告 D-3，不阻断 |
| 观察 | `lease-scripts` 的 P50/P95 为**单样本**（P50=P95），样本量少 | 实机核验已注明口径；L-5 仅按"已取得两样本（94ms / 107ms）"销记，不据此给性能结论 |

**准予入库**：0C/0I/0M，残余三项均为已登记挂账、不改变本批交付边界；生产主线语义（单实例）零回归。
