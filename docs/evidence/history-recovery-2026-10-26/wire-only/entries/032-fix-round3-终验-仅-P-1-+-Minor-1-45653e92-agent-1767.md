> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1767` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：8391 B / 5243 字符 / 93 行；行指纹覆盖率（仓内 + 落盘语料）14.0%
> - 类别：**施工复核**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## fix round3 终验（仅 P-1 + Minor 1）

**只读；未修改、未提交、未起停服务、未派子 agent、未重跑测试。** 依据：报告 87 行 + `P0-cluster-epoch-r3.diff`（727 行 / **仅 2 文件**）+ 工作树源码 + `run1/target/surefire-reports`（18:21–18:22）+ `fix3-adjacent.log` + `mutation-P1.txt` / `mutation-guard.txt`。

### 元数据核对

| 项 | 结果 |
|---|---|
| 实例模型 | `agent-pn9iy8y1.json` → `"model":"DeepSeek/deepseek-flash"`、`completed`、`coder` ✔ |
| 主仓 | HEAD 实测 **`80a752e`**（`git log` 确认，较上轮 `ad7d7fa` 前进 1 笔，仅 `docs/`）✔ 与报告一致 |
| 改动面 | r3 diff 只有 `LeaseService.java` + `LeaseEpochPerRunTest.java`，与"仅 2 文件、三键逻辑/13 键/XRANGE 未动"一致；`FakeLeaseRedis` 确实**不在** r3 diff 内 |
| 树未被变异污染 | 生产 Lua `if curEpoch >= fence then`（`LeaseService.java:161`）、替身 `if (currentEpoch >= fence)`（`FakeLeaseRedis.java:568`）**均为 `>=`** ✔ |

---

## 一、P-1：`epoch == fence` 用例是否有效 + 两处变异是否可红

**判定：ADDRESSED。**

**（1）用例有效性——逐条核过，四条都成立：**

| 有效性条件 | 实测 |
|---|---|
| 语料前提真的成立 | `storeA.tryAcquireRunLease` → `storeA.save(...)` → 断言 `fence() == held.epoch`（`LeaseEpochPerRunTest:242-247`）。`save` 走 `FENCED_SET` 把 fence 推到该 epoch，前提是硬断言而非假定 ✔ |
| **不走进程内短路** | 被拒方用 `newStore("inst-x")`（新建 `LeaseService`，`held` 视图为空）⇒ 真的发到脚本。若复用 `storeA`，`held.get()` 会直接返回 `ACQUIRED/created=false`，用例就成了空断言——这一点避开了 ✔ |
| 走生产入口 | `acquireRunLease` → `acquireFenced(runLeaseKey, fenceKey)`，不是测试专用重载 ✔ |
| 断言有判别力 | `outcome == HELD_ELSEWHERE`、`created == false`、租约键值仍为 `<epoch>\|inst-a`、计数器未被推进（`held.epoch`）。`>=`→`>` 时首条即 `ACQUIRED`（变异实证见下）✔ |

**（2）变异是否确实可红——两份 artifact 与声称逐字吻合：**

| 变异 | artifact | 实测内容 |
|---|---|---|
| `>=` → `>`（Lua + 替身两处） | `mutation-P1.txt`（18:17） | `Tests run: 10, Failures: 1`；失败用例正是 `aLeaseAtExactlyTheFenceIsValidAndIsNotStolen`；`expected: HELD_ELSEWHERE but was: ACQUIRED` ✔ 与我上轮预测的失败形态完全一致 |
| 删除入口闸 | `mutation-guard.txt`（18:18） | `Tests run: 10, Failures: 1`；`fenceKeyFromAnotherRunIsRejected…`："Expecting code to raise a throwable" ✔ 说明无闸时跨 run fence 的 `CROSSSLOT` 被 `catch` 折成 `UNAVAILABLE`（不抛），正是要消灭的静默降级 |

**（3）这一条必须说清的边界（属 P-1 成立前提，不是新 Minor）：**
JUnit 执行的是**替身**，Lua 文本从不被执行。因此"两处变异"里只有**替身那处**能让用例变红；**只变异生产 Lua 时用例恒绿**——作者已在报告里明说这一点（"否则替身不随脚本变化、用例恒绿"），并只把变异施加在隔离副本。所以 artifact 证明的是"**断言逻辑**能抓住语义回退"，而不是"生产 Lua 被锁住"。生产 Lua 与替身当前语义同构（`>= fence` + 命中分支只抬计数器/刷 TTL + 取号分支 `epoch ≤ fence → fence+1`，我逐行比对过），真 Lua 的守卫仍只能靠真集群（G7 / `MONITOR`）兑现。

---

## 二、Minor 1：跨 run fenceKey 入口闸

**判定：ADDRESSED。**

**闸的位置与形态**（`LeaseService.java:436-454`，两道，均在 `try` 之外、且在 `held` 短路之前）：

1. `epochKey == null && fenceKey != null` → `IllegalArgumentException`（无 tag 键不接受 fence，与上一轮一致）；
2. `fenceKey != null` → `hashTagOf(key)` 与 `hashTagOf(fenceKey)` 必须相等，否则 `IllegalArgumentException`，消息同时给出 `key/tag`、`fence/tag`（`:448-453`）。

**是否 fail-fast：** 是。抛点在 `try` 之前，不会被 `catch (Exception) → UNAVAILABLE` 吞掉；变异实证（删闸后该用例变为"不抛"）反向证明这条路径原本确实会被降级吞掉。

**是否误伤：**

| 形态 | 结果 | 依据 |
|---|---|---|
| 同 run 的 lease + fence（两个生产入口） | 放行 | 正向控制 `matchingTagsStillAcquire` 断言 `ACQUIRED`+`created`；`ClusterSlotTest` 9/9、`LeaseServiceDistinctOwnerTest` 9/9 全绿 |
| 无 tag 资源锁 + `fenceKey == null`（`ToolResourceLocks` 真实路径） | 两道闸都不进 ⇒ 走单键脚本 | `ToolResourceLockGateTest` **12/12** 绿——这是"不误伤"最直接的证据，比任何推断都硬 |
| 带 tag 但 `fenceKey == null`（预留两键路径） | 放行 → `ACQUIRE_NOFENCE_SCRIPT` | 代码路径 `:475-478`；`fenceKey != null` 才校验 |
| 跨 run fence | 入口抛 IAE，消息含"同槽"+ 该 runId | `fenceKeyFromAnotherRunIsRejected…` 断言三条文案 ✔ |
| 无 tag + 任意 fence | 入口抛 IAE | `fenceOnAnUntaggedLockKeyIsRejected` ✔ |

**一处值得记录的语义细节（非缺陷）**：闸比较的是 **tag 字符串**而非真实槽号——比"同槽"更严（同 tag 必同槽；反例只可能是"不同 tag 恰好同槽"被误拒，而那种调用本来就是拼键错误）。另外 `hashTagOf` 抽取后 `epochKeyFor` 行为不变，由 `ClusterSlotTest` 的 `epochKeyFor == runLeaseEpochKey` 断言与 9/9 绿兜住。附带收益：闸在 `held` 短路之前，连"误传参数的重复调用"也会立刻暴露，不会因视图命中而漏检。

---

## 三、报告数字核对

| 声称 | 实测 | 结论 |
|---|---|---|
| `LeaseEpochPerRunTest` 10/10 | surefire 18:22：`Tests run: 10, Failures: 0, Errors: 0` ✔ | 一致（6 + P-1 1 + 闸 3） |
| `Cluster9+5` | `ClusterSlotTest` 9/9、`ClusterProfileConfigTest` 5/5 ✔ | 一致 |
| 相邻 **838 例** 绿 | `fix3-adjacent.log`：`[INFO] Tests run: 838, Failures: 0, Errors: 0`，`[ERROR]` 行数 **0** ✔ | 一致，且是带 Maven 汇总行的可复现日志 |
| 全量 1888 / 4F | 报告所述 4 个失败类均在 `ai.config`/`common` 错误码面，不在本批文件集合内 | 与"本批面 0 失败"不冲突（我未重跑，采信其日志口径） |

---

## 四、双 verdict

| 维度 | 结论 |
|---|---|
| **规格 verdict** | **通过（P-1 与 Minor 1 均 ADDRESSED）**。P-1 的用例把"`>=` vs `>`"这个一字符边界真正钉住了：语料前提有断言、绕开了进程内短路、走生产入口、四条断言都在变异下可红；Minor 1 的入口闸是 fail-fast（`try` 外抛）、不误伤同 tag 与无 tag 资源锁（`ToolResourceLockGateTest` 12/12 佐证），并顺手统一了 tag 解析。 |
| **质量 verdict** | **通过（无 Critical / Important）**。本轮改动只碰两文件，三键自愈、13 键、XRANGE 与既有用例零改动；变异证据（`mutation-P1.txt` / `mutation-guard.txt`）与声称逐字一致，未发现新窗口。按指令不扩新 Minor。唯一需长期记住的边界是替身与 Lua 的"两处同构"成本——生产 Lua 的正确性最终由真集群兑现。 |

---

## 五、能否进入真实 Cluster 验证

**可以进入。** P-1 与入口闸把上次遗留的两处都收口了，本批已无待修的规格/质量项。进入真集群时请仍执行既定清单，其中与本次终验直接相关的两条：

- **G7**：`MONITOR` 观察 ACQUIRE 为**一条** `EVAL` 带 3 键（lease/counter/fence 同槽）、`FENCED_SET`、`IDEM_PUSH`、`deleteRun` 13 键一条 `DEL`；0 次 `CROSSSLOT`、无未收敛 `MOVED`。这是**唯一能覆盖生产 Lua**（替身覆盖不到）的手段，尤其要验 `epoch == fence` 的现值不被替换。
- **K1/K2（观测化）**：手工 `DEL ai:lease:epoch:{run:X}` 后触发续跑，确认日志出现"抬到 fence+1"且 `save` 成功；`TTL` 差值不再要求严格同寿（自愈已覆盖）。

两条前置仍未闭环（与本批无关，但不解决就跑不了整门禁/上不了生产）：**共享工作树里他人未跟踪的 `ResilientChatServiceMemoryWriteTest.java:141` 导致仓库级 `mvn test` 无法编译**；**C1（`chat:mem:` 双键 EVAL 跨槽 ⇒ 真集群"新建对话" 500）** 仍开。另：`deleteRun` 在 `main` 无生产调用点，13 键 DEL 的原子性仍只有替身级证据。
