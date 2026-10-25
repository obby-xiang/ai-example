# Task 3 Fix-2 报告：`residualRuns` 归属读取成本收口（复审唯一 Important）

**结论：DONE**（1 个 Important 已按复审指定方向落地；RED 在“fix1 代码基线”上确定性复现；累计补丁替换 `task-3-fix1.patch`，对<b>实际 main</b> 可干净应用、无整文件行尾重写）

- 复审原文件：主仓 `.superpowers/sdd/2026-10-25-ai-remediation/` 下**没有** `task-3-rereview1.md`（也无 `task-3-review-final`）；
  本报告以调用方转述的复审结论为准（唯一 Important：`AiSessionPurgeService#residualRuns` 对 `ai:runs` 每个非候选成员
  `runStore.get` 反序列化完整快照，`residual.size` 上限限制不了扫描量）。
  **证据误差声明**：未能核验复审原文，故本报告不引用其行号/措辞，只按缺陷描述自证 RED。
- 隔离副本：`<隔离副本>/_verify-20261025-session-integrity/backend`
- RED 基线：`<隔离副本>/_verify-20261025-session-integrity/patchcheck6/backend` = **main + task-3-fix1 补丁**（复审针对的那份实现）
- 累计补丁：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-3-fix2.patch`（取代 fix1）
- 报告：本文件（只追加 fix2 证据，不改写 `task-3-report.md` / `task-3-fix1-report.md` 的历史结论）
- 未改 main、未提交、未推送、未派子代理、未跑全量；Maven 仅用
  `<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，不 clean。

---

## 1. 缺陷与修复

### 缺陷（复审 Important）

`residualRuns` 逐 `ai:runs` 成员判归属时调 `runStore.get(runId)` —— 对**每个无关轮次**都把整份快照
（含整窗 `messageJson` 的每条消息）反序列化成 `RunSnapshot` 对象图。销毁端点的成本因此是
O(总轮次 × 快照大小) 的 CPU/内存，而 `MAX_RESIDUAL_RUNS` 只限制“发现了多少条**本会话** run”，
对扫描量没有任何作用（无关 run 再多也照扫照解析）。

### 修复（最小，按复审指定方向）

| 改动 | 位置 | 说明 |
|---|---|---|
| 新增 `RunStore#snapshotOwner(runId)` | `RunStore.java:1046` 起 | 只 `GET` 原始 JSON + **Jackson 流式**扫描**顶层** `sessionId`（找到即返回；嵌套同名字段不会被误读）。不物化 `RunSnapshot`，不解析 `messageJson` |
| 三态 `SnapshotOwner{ATTRIBUTED / ABSENT / UNREADABLE}` | `RunStore.java`（同处） | `ABSENT` = 键不存在或旧快照无该字段（**无法归属**，不得当成“他会话”）；`UNREADABLE` = 读失败/坏值（**fail-closed**，不得当“他会话”也不得删） |
| `residualRuns` 改用轻量归属 | `AiSessionPurgeService.java:895` 起 | 无关 run 只付一次单键 GET；`ATTRIBUTED && sessionId 相同` 才进候选；`UNREADABLE` 记“无法确认归属（可重试）”错误 ⇒ `complete=false` |
| 完整快照只在 `deleteOrDefer` 读 | 未改（`AiSessionPurgeService.java:938` 起） | 命中本会话后由 `deleteOrDefer` 自己 `get` 取状态/活跃判定；**孤儿 run 仍可被发现**（死成员/无字段者不猜、不误删） |
| **删除** `MAX_RESIDUAL_RUNS` 与“触顶 ⇒ complete=false” | `AiSessionPurgeService.java`（常量已移除；`residualRuns` 不再截断） | 见下“为什么不设扫描条数上限” |

**为什么不设扫描条数上限（复审明确要求避免的坑）**：任何按条数截断都会让未扫到的成员**永远确认不了** ⇒
`complete=false` 永远成立 ⇒ `SessionPurgeRetryRunner` 每次自动重试都从头再扫，大会员集合上变成
“永远删不完 + 重试放大”。本批**不截断**，把每次判定的成本压到“一次单键 GET + 几十字节流式解析”，
完整性优先。成本上界写清在类注里：

- 归属读取：`|ai:runs|` 次单键 GET（索引本身由 `RunStore#pruneRunIndex` 惰性收敛、快照带 TTL）；
- **完整快照反序列化次数 ≤ 本会话的 run 数**（只由 `deleteOrDefer` 触发）—— 这是本批真正“被限制”的成本类。

保留未动（复审点名）：`ai:observed:<runId>` 的真实 DEL、staging 转义（`escapeGlob`）、SCAN/DELETE 失败的可重试口径、
业务数据保护（负向清单、只删本会话 AI 键）。未处理其它 Minor、未触碰 Task 8 项目。

---

## 2. RED → GREEN（真实日志）

测试只改既有 `AiSessionPurgeRunIndexResidueTest`（同类小用例，共 6 例）：

| 用例 | 性质 | 基线（main+fix1） | 修复后 |
|---|---|---|---|
| `largeUnrelatedIndexDoesNotFullyDeserializeForeignSnapshots`（新） | **RED（本 Important 的直接证据）** | ❌ `Wanted but not invoked ... runStore.get(startsWith("other-")); ... Actually: get("other-000".."other-299")`（300 个无关 run 全被完整反序列化） | ✅ |
| `unreadableSnapshotIsFailClosedInsteadOfTreatedAsAnotherSession`（新） | 回归钉子（行为面 fix1 已成立；钉住新口子必须返回“不可读”而不是“非本会话”） | ✅（绿） | ✅ |
| `sentinelCompleteWithAMissingMemberStillDeletesTheRunAndReportsResidueFree`（保留） | 完整性钉子 | ✅ | ✅ |
| `sentinelCompleteWithAnActiveMissingMemberLeavesRetryableIncompleteState`（保留） | 延迟/可重试钉子 | ✅ | ✅ |
| `observedKeyOfThisSessionsRunsIsDeletedAndOtherSessionsAreUntouched`（保留） | `ai:observed:` 真删 | ✅ | ✅ |
| `observedKeyDeleteFailureIsReportedAsRetryableIncomplete`（保留） | 观测位删除失败可重试 | ✅ | ✅ |

RED 日志：`logs/fix2-red-vs-fix1.log` ⇒ `Tests run: 6, Failures: 1`（仅成本用例），失败点为
`verify(spyStore, never()).get(startsWith("other-"))`，证据链直指 `RunStore.get(RunStore.java:1002)`
（完整反序列化入口）。

GREEN 日志：`logs/fix2-green.log` ⇒ 目标类 6/6 + 相邻 2 类（`AiSessionPurgeServiceTest` 23、
`SessionPurgeGuardFailClosedTest` 5）= **34 例，0 失败**。

说明：`logs/fix2-red.log` 是同一目标类在**纯 main**上的运行（6/6 全红）——那是 fix1 整个 RT-28/`ai:observed:`
面都不存在的基线，只作旁证；本轮的权威 RED 是上面“main+fix1”那一份。

---

## 3. 补丁与应用

```
task-3-fix2.patch （累计：fix1 全部修复 + 本次成本收口；17 个文件）
  生产 6：RedisChatMemoryRepository +137/-13、LeaseService +29/-7、RunStore +192/-9、
          ResilientChatService +74/-18、SessionGate +91/-13、AiSessionPurgeService +219/-9
  既有测试 3：AiSessionPurgeServiceTest +5/-1、PurgeFakeRedis +126/-24、FakeMemoryRedis +11/-0
  新测试 8：见 fix1 报告；其中 AiSessionPurgeRunIndexResidueTest 由 +198 变为 +269（新增 2 例）
```

- `git -c core.autocrlf=false apply --check` 在**当前 main**的 pristine 副本上通过；应用后 17 个文件与验证树
  **逐字节一致**（`patchcheck-fix3`），`differing=0`。
- **无整文件行尾重写**：补丁内 `@@ -1,…` 计数 = 0；行尾按源文件保持（`RedisChatMemoryRepository` / `SessionGate` /
  `AiSessionPurgeService` / `PurgeFakeRedis` / `FakeMemoryRedis` / `AiSessionPurgeServiceTest` 为 CRLF，
  `RunStore` / `ResilientChatService` / `LeaseService` 为 LF）。
- 应用后模块可编译：`mvn -o -B -DskipTests compile` ⇒ `Compiling 306 source files` + `BUILD SUCCESS`（含其它任务落在 main 的新文件）。
- 建议命令：`git -c core.autocrlf=false apply .superpowers/sdd/2026-10-25-ai-remediation/task-3-fix2.patch`

---

## 4. 残余（不静默丢，均非本 Important 范围）

1. **归属读取的字节量**：本批避免的是“反序列化对象图/解析整窗消息”，原始快照字符串仍会整值 `GET`
   （大轮次快照可达数十 KB）。若要进一步把**字节**也压到常数，可用 `GETRANGE` 取头部（`sessionId` 紧跟 `runId`）
   再回退整值；本次未做：多一跳往返、且需扩测试替身的 GETRANGE 语义 —— 属优化项，不是正确性问题。
2. **旧快照无 `sessionId` 字段** ⇒ `ABSENT`（无法归属、跳过）：与枚举同口径；若要覆盖，需要“无 sessionId 的老
   run 一律按本会话删”的策略，那会误删他会话，故不采用。
3. fix1 报告 §4 列出的四项（RT-21 的 SseChatEmitter 生产出帧闸、RENAME 后崩溃对账、`/health` 的 `lockLost`
   接线、`ai:observed:` 的 `EXCLUDED_KEY_PREFIXES`）仍归 **Task 8 串行窗口**，本批未动。
4. 真实 Redis/Cluster 仍未验证（替身口径同 fix1）。
