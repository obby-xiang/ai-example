> 来源=<仓外证据>/P0-frame50-evidence-note.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# P0 frame50 证据卫生说明（fix round 1 · 2026-10-24）

仓库：`<主仓>`　分支 `main`　HEAD `f2931f5`
本轮只做证据标注与勘误：**未删除、未修改、未重命名任何历史日志**；未 commit / 未 push；未改 docs；未起停服务。
写入者：P0 frame50 fix round1（子 agent）。

## 0. 结论先行

1. `_a-frame5-red-canonical.log` **不是独立运行**，是 `cp` 出来的副本 —— **不得引用**。
2. RED 的唯一有效来源 = `_a-frame5-red.log`（23 例 / 12 败），回退状态 = **原生产文件 + 新替身 + 新用例**。
3. `_a-frame5-green-mvn2.log`（及其副本 red-canonical）的**数字与归因不能当证据**。
4. 当前有效 GREEN 见第 4 节；I1 的两条 WARN 已各自被独立用例钉住，并以变异证明（第 5 节）。
5. 主报告如需更正，请**追加 errata**（第 7 节给出可直接粘贴的措辞），不要改历史正文。

## 1. 副本关系（md5 实测）

| 文件 | md5 | 性质 |
|---|---|---|
| `_a-frame5-red-canonical.log` | `68919d740da1956ee5fa06cb09112981` | 与 `green-mvn2` **逐字节相同** ⇒ 副本，非独立运行 |
| `_a-frame5-green-mvn2.log` | `68919d740da1956ee5fa06cb09112981` | 原始运行（16:35），但按第 3 节不可作证据 |
| `_a-frame5-green-canonical.log` | `7a71d73085a185683db2052d96f106ef` | 与 `green-mvn3` **逐字节相同** ⇒ 副本 |
| `_a-frame5-green-mvn3.log` | `7a71d73085a185683db2052d96f106ef` | 原始运行（16:36），**有效 GREEN** |

round1 把 `green-mvn3` `cp` 成 `green-canonical`、把 `green-mvn2` `cp` 成 `red-canonical`，命名具误导性；本次仅标注事实，不删文件（删除反而破坏可追溯性）。

## 2. RED 的唯一有效来源

`_a-frame5-red.log`（16:26）

- **生成方式**：`mvn -o surefire:test -Dtest=RedisFrameStoreTest`（当时模块 `testCompile` 被其它 agent 的未完成文件阻塞，故先 `javac` 编译该包、再直接跑 surefire；**不是**完整 `mvn test` 生命周期，证据强度按此打折）。
- **回退状态**：**原生产文件**（`Range.Bound.exclusive(from + "-0")` 排他下限；Stream / LIST 读失败仅 `debug`，末号探测与末帧探测**无任何日志**）+ **新替身**（按目标 5.0 拒绝排他区间）+ **新用例**（本批新增 6 条）。
- **结果**：`Tests run: 23, Failures: 12`，构成为
  - 9 条差量读 0 帧：`afterSeqBetweenListTailAndStreamTail`、`afterSeqInsideTheTrimmedRegion`、`deltaReadAboveLastSeq`、`firstReplayOfALongRun`、`fullReplayOverAnApproximatelyTrimmedStream`、`lateLowSeqFrameStaysReadable`、`readFromBoundaries`、`readFromMergesLegacyListAndStreamBySeq`、`streamIsTrimmedToWindow`
  - 3 条 WARN 用例（当时生产侧日志不足以被观测）：`streamReadFailure`、`tailProbeFailure`、`legacyListReadFailure`
- **更正**：报告正文写“11 败（3+9）”有误，产物为 **12 败**。

## 3. green-mvn2（= red-canonical）为何不能当证据

16:35 那次 `mvn -o test` 运行时，Maven 增量编译**没有重编** `RedisFrameStore.class`：`target` 里仍是作者为取 RED 而临时把下限改回 `exclusive` 时编出的字节码（同秒 mtime，增量判定为 up-to-date）。因此该次“12 败”反映的是**旧 exclusive 字节码 + 当时其它 agent 的在途改动**（同次 `FrameCompatReadTest` 3 败即来自他人 RunStore 改动），既不是当前源码的 RED，也不是当前源码的 GREEN。

round1 报告在同一处既称它“旧字节码假红”、又拿它的副本当 “canonical RED”，自相矛盾。以本说明为准：**不作 RED、不作 GREEN、不得引用其数字**。

## 4. 当前有效 GREEN（均为 `mvn -o test` 全生命周期，BUILD SUCCESS）

| 日志 | 时间 | 结果 | 覆盖 |
|---|---|---|---|
| `_p2-review/frame50-green-final.log` | 16:56 | **32/32** | RedisFrameStoreTest 24 + FrameCompatReadTest 4 + SseChatEmitterFrameStoreTest 4（**最新；含 I1 拆分后用例与接口注释**）|
| `_p2-review/frame50-i1-green.log` | 16:53 | 24/24 | I1 拆分后的 RedisFrameStoreTest |
| `_a-frame5-green-mvn3.log` | 16:36 | 31/31 | I1 之前的用例集（5.0 闭区间修复的有效 GREEN）|
| `_a-frame5-adjacent2.log` | 16:37 | 422/422 | `ai.run.*Test` + `ai.conformance.*Test` 相邻面 |
| `_a-frame5-green.log` | 16:29 | 31/31 | 更早的 surefire:test（保留备查）|
| `_a-frame5-green-mvn.log` | 16:33 | 31/31 | 更早的 mvn test（保留备查）|

**不作计数证据**：
- `_p2-review/frame50-i1-baseline.log`（`-q` 运行，无 summary 行）；
- `_a-frame5-full.log`（1751 tests / 0 failures / **77 errors**，全部 `ClassNotFoundException` —— 并发 `clean` 清空 `target/test-classes`，非代码问题；`RedisFrameStoreTest` 在该次 23/0）；
- `_a-frame5-red-mvn.log`（被他人未完成改动阻断在 `compile`，未跑到测试）。

## 5. I1 修复与变异证明

**问题**：`failListRead=true` 让 `LRANGE -1 -1` 先抛，`readFrom → legacyFramesAbove` 在末帧探测处就短路，`readLegacyList` 永不执行；且两条 WARN 共用前缀，旧用例实际命中的是「末帧探测」。于是把 `:409`（整段读）改回 `debug`，旧用例仍绿 ⇒ 对 `:409` 是**假保护**。

**修复（仅测试与替身，生产 WARN 保持）**：
- 替身拆成两个独立开关：`failListTailProbe`（只让 `LRANGE -1 -1` 抛）与 `failListFullRead`（只让整段读 `LRANGE -窗口 -1` 抛），按参数区分（`FakeFrameRedis.java`）。
- 用例拆成两条并断言互斥文案：
  - `legacyListTailProbeFailureIsReportedInsteadOfSilentlyEmpty`：contains「末帧探测」+ doesNotContain「本批 LIST 帧缺失」；
  - `legacyListFullReadFailureIsReportedInsteadOfSilentlyEmpty`：contains「本批 LIST 帧缺失」+ doesNotContain「末帧探测」。

**变异（每次只改一行日志级别，跑完即恢复）**：

| 变异 | 日志 | 结果 |
|---|---|---|
| `:409` 整段读 `warn → debug` | `_p2-review/frame50-mutation-m1-409debug.log` | 2 跑 1 败：**整段读用例红**（`[整段 LIST 读取失败必须 WARN，且不是靠末帧探测兜住]`），末帧探测用例绿 |
| `:437` 末帧探测 `warn → debug` | `_p2-review/frame50-mutation-m2-437debug.log` | 2 跑 1 败：**末帧探测用例红**，整段读用例绿 |

**恢复核对**：生产文件 `RedisFrameStore.java` 的 `git diff` 与复核留档 `_p2-review/P0-frame50.diff` 中该文件的段**逐字节相同**（md5 `d981127109726a0ee9e50436cc9256bf`）⇒ 变异未留下残留，生产 WARN 与 round1 一致。

## 6. 接口注释同步（仅注释，不改行为）

`backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java` 的 `readFrom` javadoc 新增一段：
**「空结果 ≠ 证明『没有帧』」** —— 读失败（Redis 抖动、目标 5.0 拒绝非法 stream ID 等）同样返回空列表并 WARN 留痕（带 runId 与读取区间），调用方不得把“空”当成“该 run 确实没有新帧”的证明；本方法不抛异常（回放与唤醒补帧不能被一次读抖动打断）。该文件 `git diff` 只有注释行。

## 7. 供主报告追加的 errata（请追加，勿改历史正文）

- 【更正】“canonical RED” 不可引用：`_a-frame5-red-canonical.log` 与 `_a-frame5-green-mvn2.log` 逐字节相同（md5 `68919d74…`），是副本。RED 唯一依据为 `_a-frame5-red.log`（23/12），回退状态 = 原生产文件 + 新替身 + 新用例。
- 【更正】“11 红”应为 **12 红**（9 差量读 + 3 WARN 用例）。
- 【更正】`green-mvn2` 的数字与归因作废（旧 exclusive 字节码 + 他人并发改动），既非 RED 也非 GREEN。
- 【补充】`_a-frame5-green-canonical.log` 亦为 `_a-frame5-green-mvn3.log` 的副本；有效运行是 `green-mvn3`。
- 【补充】I1 已修：替身拆 `failListTailProbe` / `failListFullRead`，两条 WARN 各有独立用例并以变异证明（见本 note 第 5 节）。
- 【补充】接口 `FrameStore#readFrom` javadoc 已同步“空 ≠ 无帧”。

## 8. 本轮改动文件

- `backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java` —— 仅 javadoc
- `backend/src/test/java/com/example/configmgr/ai/run/FakeFrameRedis.java` —— 拆分 LIST 读失败开关
- `backend/src/test/java/com/example/configmgr/ai/run/RedisFrameStoreTest.java` —— 拆分 LIST 用例
- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java` —— 仅用于变异，已恢复，内容与 round1 一致（md5 校验见第 5 节）
- 本说明文件（仓库外）

## 9. 明确不扩（主会话另行挂账）

I2 回放读失败仍正常 `complete`；Minor 4 自动 ID 兜底 + `COUNT` 窗口遗漏；WARN 去重/限流；`ex.toString()` 丢栈；替身 `xRevRange` 未做区间过滤；`frameOf` 与 LIST 单帧坏损仍 `debug`。本轮均未触碰。

## 10. 补记（fix round2 · 2026-10-24 17:02，仅追加，上文原文保留）

- 本轮复查发现：`RedisFrameStore.java` 已被**其它 agent 并发修改**（新增 `{run:<runId>}` hash tag 相关 javadoc 等），因此第 5 节 / 第 8 节中 `d981127109726a0ee9e50436cc9256bf` 这个 md5 只对应 **round1 当次快照（16:57）**；今日直接按 md5 复核会不一致，属他人改动，**不是** I1/I3 修复的漂移。
- 已按内容核对：本批的 5.0 闭区间修复（`Range.Bound.inclusive((from + 1) + "-0")`）与四条读失败 WARN 仍在当前文件（行号分别约 322 / 344 / 415 / 443 / 476）。
- 若后续再要做“生产段未变”的字节比对，请以**当时**的 `git diff` 与最新复核留档为准；本说明不对 round1 之后他人的改动作评审。
