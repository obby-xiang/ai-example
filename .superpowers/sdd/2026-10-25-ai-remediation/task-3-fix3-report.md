# Task 3 Fix-3 报告：`enumerate` 回退分支的归属读取成本收口（复审最后 1 个 Important）

**结论：DONE**（唯一 Important 已修；RED 在**当前 main**（已含 fix2）上确定性复现；增量补丁 `task-3-fix3.patch` 基线 = 当前 main，可干净应用、无整文件行尾重写）

- 隔离副本：`<隔离副本>/_verify-20261025-session-integrity/backend`
- RED 基线（本轮权威）：`<隔离副本>/_verify-20261025-session-integrity/red-fix3` = **当前 main 原样** + 仅本轮的测试文件
- 补丁应用验证树：`<隔离副本>/_verify-20261025-session-integrity/patchcheck-fix3-apply` = 当前 main + `task-3-fix3.patch`
- 交付：`.superpowers/sdd/2026-10-25-ai-remediation/task-3-fix3.patch`（**增量**，3 文件，18,509 字节）
- 未改 main、未提交/推送、未跑全量、未派子代理；Maven 仅用
  `<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，不 clean。
- 未触碰 `ResumeService` / `ResilientChatService` / `ConfirmGate`；未处理其它 Minor、未动 Task 8 项。

> 前提确认：当前 main 已含 fix2（`RunStore.java` / `AiSessionPurgeService.java` / 本测试类与 fix2 交付逐字一致，
> 由 `diff` 核对），因此本轮所有 diff 都是 **fix3 增量**，不会回退 fix2 或其它任务的合并结果。

---

## 1. 缺陷与修复

### 缺陷（复审 Important）

`AiSessionPurgeService#enumerate` 的**回退分支**（没有 `__session_runs_complete__` 哨兵时）把 `ai:runs`
全部成员当候选，逐条 `runStore.get(...)` —— 只为判归属就把整份快照（含整窗 `messageJson`）反序列化。
首次销毁（新会话 / 老数据 / 升级后第一条 `DELETE`）必然走这条路径，海量无关轮次全被解析。
fix2 只修了删除尾部的 `residualRuns`；fix2 的用例预置了哨兵（走快路径），恰好**绕过**了主枚举路径。

### 修复（最小，且保留全部旧语义）

| 改动 | 位置 | 说明 |
|---|---|---|
| 回退分支归属过滤改走轻量口 | `AiSessionPurgeService.java:492`（`knownOwn`）、`:520`（`!named && !knownOwn.contains`） | 只有 **点名（gateRunId / currentRunId）** 或 **确认属于本会话** 的 run 才完整 `get`；其余先 `runStore.snapshotOwner(...)`（原始 JSON + 流式顶层 `sessionId`） |
| `UNREADABLE` ⇒ **中止整次 enumerate** | `AiSessionPurgeService.java:528` | 抛 `AiStateUnavailableException` ⇒ `purge()`/`retry()` 的 enumerate catch 把它落成 `phase=enumerate`、`complete=false`、**一个数据键都不删**（pending 回滚、守卫未装）。**不跳过**：跳过等于猜"他会话" |
| `ABSENT` ≠ `MISSING` | `RunStore.java:1052`（`snapshotOwner`）、`:1084`（`topLevelOwner`）、`:1117`（`State`） | 新增 `MISSING`（键已消失 = 死成员）；`ABSENT` 收窄为"键在、JSON 合法但没有可用 `sessionId`（字段缺失或显式 `null`）"。回退分支对两者都**跳过且不删任何键**——旧快照绝不当死成员删 13 键 |
| `sessionId` 类型严格化 | `RunStore.java:1084` 起 | 字符串 ⇒ `ATTRIBUTED`；`null` / 缺字段 ⇒ `ABSENT`；**非字符串非 null（数字/对象/数组/布尔）⇒ `UNREADABLE`**（旧实现被 Jackson 强转成字符串后当"他会话"静默跳过）；顶层不是 JSON 对象 ⇒ `UNREADABLE` |
| `residualRuns` 同步四态 | `AiSessionPurgeService.java:922` 起 | `MISSING`/`ABSENT` 都跳过（不猜、不删）；`UNREADABLE` 仍记"无法确认归属（可重试）" ⇒ `complete=false`（沿用 fix2 口径） |
| gate/current 旧语义保留 | `AiSessionPurgeService.java:520` 的 `named` 短路 | 被会话门/当前轮索引点名的 run **不经**归属口判定，仍走完整 `get`：快照已消失 ⇒ `UNKNOWN`/active 收进候选（随后按最新状态清理孤儿键） |

保留未动：`ai:observed:<runId>` 真实删除、staging `escapeGlob`、SCAN/DELETE 失败可重试、业务数据负向清单、
`enumerate` 失败"不半删"的旧错误语义（`phase=enumerate` + pending 回滚）。

---

## 2. RED → GREEN（真实日志）

测试只改既有 `AiSessionPurgeRunIndexResidueTest`（10 例；本轮 +4）：

| 用例 | 性质 | 当前 main（含 fix2） | 修复后 |
|---|---|---|---|
| `firstPurgeWithoutSentinelDoesNotFullyDeserializeForeignSnapshots`（新） | **RED（本 Important 的直接证据）** | ❌ `NeverWantedButInvoked: runStore.get(startsWith("other-")); ... invoked at AiSessionPurgeService.enumerate(AiSessionPurgeService.java:515) with arguments: [other-240] [other-000] …`（300 个无关 run 全被完整反序列化） | ✅ |
| `nonStringSessionIdIsUnreadableAndAbortsEnumerateWithoutDeleting`（新） | **RED（行为面）** | ❌ `expected: "enumerate" but was: null` —— 数字 `sessionId` 被强转后当"他会话"静默跳过，`complete=true` | ✅ |
| `legacySnapshotWithoutSessionIdIsSkippedInsteadOfDeletedAsDeadMember`（新） | 回归钉子（旧行为已正确，钉住"ABSENT 不得当死成员"） | ✅ | ✅ |
| `missingSnapshotNamedByCurrentRunIndexKeepsUnknownActiveSemantics`（新） | 回归钉子（gate/current 的 `UNKNOWN`/active 旧语义） | ✅ | ✅ |
| 既有 6 例（fix2 的 `residualRuns`/`ai:observed:`/坏值 fail-closed 等） | — | ✅ | ✅ |

- RED（当前 main 原样）：`Tests run: 10, Failures: 2` —— `logs/fix3-red.log`（仅上表两条）
- GREEN（隔离副本）：`Tests run: 10` + 唯一相邻类 `AiSessionPurgeServiceTest` `Tests run: 23` ⇒ **33 例 0 失败**
  —— `logs/fix3-green-adjacent.log`
- GREEN（**从补丁应用后的树**重跑，即交付物自证）：`Compiling 306 + 445 source files` ⇒
  `Tests run: 33, Failures: 0` / `BUILD SUCCESS` —— `logs/fix3-green-from-patch.log`

命令（IDEA Maven，离线，不 clean）：

```bash
export JAVA_HOME="<本机工具链>\Java\jdk-21.0.12"
"<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -B -Dtest='AiSessionPurgeRunIndexResidueTest,AiSessionPurgeServiceTest' test
```

---

## 3. 增量补丁

```
task-3-fix3.patch   （基线 = 当前 main（已含 fix2）；只在 fix2 之上追加本轮改动）
  RunStore.java                                +55 -30   （snapshotOwner 四态 + 流式解析重写）
  AiSessionPurgeService.java                   +28  -0   （enumerate 回退分支轻量归属 + import）
  AiSessionPurgeRunIndexResidueTest.java      +110  -2   （4 条新用例 + 种子辅助）
```

- `git -c core.autocrlf=false apply --check` 在**当前 main**的 pristine 副本上通过；应用后 3 个文件与验证树
  **逐字节一致**（`differing=0`）。
- **无整文件行尾重写**：`@@ -1,…` 计数 = 0；行尾按源文件保持（`AiSessionPurgeService.java` 为 CRLF，
  `RunStore.java` 与测试类为 LF）。
- 应用建议：`git -c core.autocrlf=false apply .superpowers/sdd/2026-10-25-ai-remediation/task-3-fix3.patch`
  （应叠加在已含 fix2 的 main 上；`task-3-fix2.patch` 不再需要重放）。

---

## 4. 成本与残余（如实登记）

1. **本轮真正被限制的成本类**：完整 `RunSnapshot` 反序列化次数 ≤（本会话 run 数 + 点名 run 数）。
   回退分支的归属判断 = `|ai:runs|` 次单键 `GET` + 顶层字段流式解析（每份通常几十字节），不再解析
   `messageJson`；本会话 run 会额外多一次轻量读（可接受，且回填哨兵写下后下一轮走快路径）。
2. **仍会整值 `GET`**（省的是反序列化 CPU/内存，不是字节）。要进一步压字节可改 `GETRANGE` 取头部；
   本次未做（多一跳往返、需扩替身 GETRANGE 语义），属优化项。
3. **ABSENT（旧快照无 sessionId）跳过**：可能漏删属于本会话的老轮次 —— 这是"不能误删他会话"的必然取舍，
   与 fix2 口径一致；`found`/`complete` 不受影响。
4. 真实 Redis/Cluster 仍未验证；fix1 报告中列出的 Task 8 项（SseChatEmitter 生产出帧闸、RENAME 后崩溃对账、
   `/health` 的 `lockLost` 接线、`ai:observed:` 的 `EXCLUDED_KEY_PREFIXES`）保持未动。
