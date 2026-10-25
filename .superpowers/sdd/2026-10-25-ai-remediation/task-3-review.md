# Task 3 独立复审 — 会话互斥、记忆键与销毁完整性

复审对象：`task-3-brief.md` / `task-3-report.md` / `task-3-session-integrity.patch`（176 KB，7 个文件）
复审方式：**只读**。未改主树、未跑任何套件（未跑全量，也未重跑聚焦类）。做法是证据链核验 + 源码/调用点静态审计 + 在 `<隔离副本>/_task3-review/scratch`（主树 `backend/src` 的临时副本，独立 git 仓）里 `git apply` 本补丁以取得真实语义 diff。
权威判定基准：`task-3-brief.md` Step 1 三条 + plan §Task 3 + `_p2-review/ai-full-audit/FINAL-AUDIT.md` 的 RT-20/21/28/34/35/36/37 + 用户裁定"所有 AI 数据（含 `ai:observed:<runId>`）在会话销毁时删除，不只靠 TTL"。
复审日期：2026-10-25

---

## 0. 结论

| 项 | 判定 |
|---|---|
| **Spec Compliance** | **FAIL（部分实现）** — 4 条已落地（ID-04/ID-15 记忆键别名、RT-36 写回交错、RT-37 staging、RT-34/35 之外的租约探针 fail-open），但 **RT-20/21、RT-34、RT-35、RT-28、`ai:observed:` 销毁面 5 项未做**；其中 2 项是用户明确划定的 P0-2，1 项是用户直接点名的数据销毁要求 |
| **Task Quality** | **NEEDS-CHANGES** — 已做部分方向正确、测试有真实 RED/GREEN、补丁可应用且与被测代码逐字一致；但存在 **1 个 D3 自伤缺陷（staging SCAN 未转义 glob）**、**D2 在 RENAME 结果不可知时不复检**、2 份整文件重写补丁、1 条几乎无效的"回归钉子" |
| **合并建议** | **不建议以当前形态合并为"Task 3 完成"**。可以把 D1/D3/D4 视为可独立合入的子集（需先修 I1/I3 并重切最小 diff）；D2 需先修 I2；B1–B4 必须在合并前补上或被用户显式缩小范围 |

**Blocker（按严重度排序）**

| # | 阻塞项 | 依据 |
|---|---|---|
| B1 | 旧驱动仍可写**记忆与 DB 历史**（RT-20），`HELD-but-fence-ahead` 仍可出帧/写台账/取号（RT-21） | `ResilientChatService.java:1463-1481` 无任何 `runLeaseAuthority` 判定；`:1002-1006` 先 `recordFinalAssistant` 再 `store.save`；`RunStore.java:1863-1866`、`:2130` 只查 `authority.readOnly()`，不查 fence |
| B2 | 会话门**静默降级**（RT-34）与 `Busy.runId()` 回退成"自己"（RT-35）都未改 | `SessionGate.java:392-403`（续期返回 0 只 WARN、`degraded` 不置位）、`:210/:230` + `:372-380`；消费侧 `ResumeService.java:781-785`（`runId.equals(busy.runId())` 即当成功）；补丁未触碰 `SessionGate.java` |
| B3 | **会话→run 索引完整性（RT-28）**：`complete` 哨兵存在但缺成员时销毁直接报 `complete=true` 并残留 13 键 | `AiSessionPurgeService.java:469-471`（打补丁后；主树 `:466-473`）信任 `complete()`；`RunStore.java:655-664` 的 SADD 是 best-effort；无该用例 |
| B4 | 销毁**不删** `ai:observed:<runId>` | `AiSessionPurgeService.java:847-855` 只删 beat/activity/cancel；报告 §5 明确"留给集成方" |
| B5 | 补丁形态：2/3 个生产文件是**整文件重写**（`-1,412 +1,521`、`-1,011 +1,084`），真实语义改动只有 227+/23− | `git diff --ignore-cr-at-eol` 实测；见 §5.1 |

---

## 1. 证据链核验（不重跑套件）

1. **补丁可用性**：在主树 `backend/src` 的临时副本上 `git -c core.autocrlf=false apply` ⇒ 成功，7 个文件全部落地，无冲突。报告的"可干净应用"成立。
2. **补丁 == 被测代码**（真实性检查）：把打完补丁的 7 个文件与 `<隔离副本>/_verify-20261025-session-integrity/backend/src` 逐一 `diff --strip-trailing-cr` ⇒ **7/7 SAME**。RED/GREEN 日志描述的就是这份补丁。
3. **RED 基线确实是主树生产代码**：`red-baseline/src` 的 3 个生产文件与主树 `backend/src` 的对应文件 `diff` ⇒ **3/3 SAME**；新增 4 个测试类在其中。
4. **RED**：`red-baseline.log` = `Tests run: 16, Failures: 12, Errors: 0`。逐条失败信息与报告 §1 一一对应（D1 5 条、D2 2 条、D3 3 条、D4 2 条）。
5. **GREEN**：`green-final.log` = `Tests run: 77, Failures: 0, Errors: 0`，分类行与报告表 10 类/77 例逐一对上（18+6+4+4+2+5+6+4+23+5 = 77）。未跑全量符合 plan cap。
6. 未验证：真实 Redis/Cluster、真实跨 JVM 拓扑（报告 §6 已如实登记，本复审同样未验证，且下述 I1 恰好只能在真 Redis 上暴露）。

> 结论：**"77 例 GREEN、12 例 RED"这两个事实成立**。问题不在证据真实性，而在证据覆盖的**要求面**。

---

## 2. 逐条对照（approved 要求 → 判定）

| # | 要求 | 判定 | 证据 |
|---|---|---|---|
| 1 | `sessionId="{x}"` 不得读/写/删 `x` 的记忆；`__ids__` 不得删全局索引 | **PASS** | `legacyEligible`（打补丁后 `RedisChatMemoryRepository.java:248-252`）接到 5 个 legacy 触点：`:191`（`readableKeyFor`）、`:328`（`hasLiveMemory`）、`:342`（读回落）、`:372`（写后清理）、`:454-458`（删除）。测试 `RedisChatMemoryKeyNamespaceTest.java:51-103`。审计的 3 个碰撞族（tagged / staging / 索引）全部被枚举且**未发现漏网族**（已逐一反推：legacy 键只能撞这三类，因为其它键前缀都不是 `chat:mem:`） |
| 2 | purge 与记忆写回交错 ⇒ 不得 `complete=true` 后复活 | **PARTIAL** | D2 已实现（RENAME 后复检、fail-closed），`RedisChatMemoryWriteBackRaceTest.java:46-85` 有 RED；但 **RENAME 结果不可知（异常）路径完全不复检**（I2），且崩溃的写者永远无法复检（I5） |
| 3 | staging 中间态必须进删除面、只在删净后报 complete | **PARTIAL** | `stagingKeys`（`:639-651`）+ `clearStagingKeys`（`:783-796`）+ `hasLiveMemory` 复核（`:764-771`）已实现并有三条测试；**但新扫描没做 glob 转义**，带 `[`/`\` 的 sessionId 会漏掉**自己的** staging 键（I1），此时仍报 `complete=true` |
| 4 | 索引缺成员不得掩盖"数据还在" | **PARTIAL** | 只覆盖**记忆索引**（`removedFromIndex` 掩膜）这一个面；**会话→run 索引的哨兵完整性（RT-28）未做**（B3），`indexMemberMissing...` 用例（`:146-156`）也没有真正构造"索引缺成员但数据在"的形态（I4） |
| 5 | 锁续期失败/诊断 GET 失败不得进入可写或假取锁成功 | **PARTIAL** | 只做了"租约设施探针缓存 fail-open"（D4，正确且有效）；**续期返回 0 后 `degraded` 仍不置位**（RT-34）与**诊断 GET 失败把 `Busy.runId` 填成调用方自己**（RT-35）两条真正的"假成功"未做（B2） |
| 6 | 禁止放宽 fencing / 删保护 / 全清业务数据来转绿 | **PASS** | D1–D4 全部是收紧：D4 初值 false、D3 只删本会话 AI 键（staging 用精确前缀 + 逐字过滤）、D2 新增撤销。未发现任何放宽 |
| 7 | 销毁覆盖**全部** AI 数据（用户裁定，含 `ai:observed:<runId>`） | **FAIL** | 未删 `ai:observed:`（B4）；报告 §5 把它"留给集成方"，但该键形是 Task 2 明文契约（`task-2-report.md` §5：`ai:observed:<runId>` HASH、TTL=session-ttl），且 `deleteObservabilityKeys` 已经在按 run 删同类 `ai:beat:`/`ai:activity:`，一行即可加，不存在"引不到"的硬障碍 |
| 8 | 不提交、不改 main、不派子代理 | **PASS** | 主树 `git status` 未见本批新增生产改动（其它未提交项属于 Task 1/2/4/6/7 等批）；报告与补丁是仅有的新文件 |

---

## 3. 四个点名问题的直接回答

**Q1：是处理了旧驱动写/围栏，还是只做了 D4 初始探针？**
**只做了 D4。** 补丁未改 `ResilientChatService.java`、未改 `RunStore.java`、未改 `AiChatHistoryRecorder`/`AiChatHistoryService`。
- 记忆：`persistMemory`（`ResilientChatService.java:1463-1481`）只有 `try { saveAll } catch { WARN }`，**没有任何租约/围栏判定**，因此 LOST/UNAVAILABLE 的旧驱动在终局照样整窗覆写会话记忆。
- 历史：`:1002-1006` 是 `recordFinalAssistant(...)` → `store.save(snapshot)` → `convergeMemory(...)`。`store.save` 会被 fence 静默拒绝（`RunStore.java:695-717`，`void` 返回），但历史已经先入库（`AiChatHistoryService.java:138-146` first-write-wins）⇒ 输家答案可能先落库，正是 RT-20 描述的"刷新看旧答案"。
- 帧/台账/取号：`RunStore.java:1863-1866`（台账）、`:2130`（帧）、`:2185`（序号）只看 `authority.readOnly()`，**不看 fence 值**，`HELD-but-fence-ahead` 第三态（RT-21）原样保留。
- 报告 §2 的论证（"记忆写回只有 `persistMemory → saveAll` 一个写口，销毁拦截由守卫 + D2 复检完成"）回答的是**销毁交错**，不是**旧驱动围栏**——两件事被混为一谈。

**Q2：`SessionGate` 的 Busy 回退处理了吗？**
**没有。** `SessionGate.java:372-380` 的 `observedRunId(sessionId, runId)` 在读失败/键消失时返回 `fallback`，而两个调用点（`:210`、`:230`）传入的 fallback 正是**调用方自己的 runId**。后果：
- `AiController.java:415-433`：`isDeadRound(自己 runId)` 因快照为 null 被判"死轮"（`RunLiveness.java:44-46`）⇒ 白做一次强释放；随后 409 体 `runId` + `reattach` 指向一个**不存在的流**。
- `ResumeService.java:781-785`：`busy != null && !runId.equals(busy.runId())`，相等即被当成"取门成功"，`gateHeld=true` 推进续跑——**在没有跨实例会话门的情况下与另一实例并发**。这条比 409 文案错更严重，正是 RT-35 的 V1 形态。
- 报告 §2 只审了"五条写路径原子、异常不裸写"，这不是 RT-35 的问题面；补丁也无任何相关用例（`SessionGateLeaseTokenTest` 无"ACQUIRE 返回 0 且随后 GET 抛错"组合）。

**Q3：会话→run 索引哨兵完整性有测试/修复吗？**
**都没有。** `AiSessionPurgeService.java:469-471` 仍是 `readable() && complete() && !runIds().isEmpty()` 直接走快路径；哨兵由 `RunStore.backfillSessionRuns`（`:891-911`）在 SADD 之后补写，而新 run 入索引是 best-effort（`RunStore.java:655-664`，失败只 WARN）。一次 SADD 失败 + 已存在哨兵 ⇒ 该 run 永远不进候选 ⇒ 13 键/工具入参/草稿键残留，而 `complete=true`（没有第二道"删完复核 run 键"的检查，D3 的复核只管记忆）。这正是 FINAL-AUDIT **RT-28**，其引用的行号 `AiSessionPurgeService:466-473` 与本复审定位一致。现有用例 `AiSessionPurgeServiceTest.java:458-495` 只覆盖"非空但**未回填**"的回退；`legacyNonEmptyButUnbackfilledSessionIndexStillDeletesOldRuns` 也不能替代"有哨兵 + 缺成员"。

**Q4：observation key 生命周期实现了吗？**
**没有，被明确移交。** 报告 §5 写"这一行留给集成方"。按用户裁定（销毁即删全部 AI 数据），这是未完成项而非可接受边界。可落点很清楚：`AiSessionPurgeService.deleteObservabilityKeys`（`:847-855`）已在逐 run 删除 `ai:beat:`/`ai:activity:`/`ai:cancel:`，加 `"ai:observed:" + runId` 单键 DEL 即可（键形由 Task 2 契约固定，不依赖其 `SettlementObservationStore` 类；在 Task 2 未并入时该 DEL 是 no-op，前向兼容）。报告"引不到（会编译失败或猜错键形）"的说法只在"必须用 Task 2 的类"时成立，而这里并不需要。

---

## 4. 已做部分的技术复核

### 4.1 D1（legacy 命名空间）— 成立

- 判据 `!legacy.startsWith("chat:mem:{") && !legacy.startsWith("chat:mem:staging:{") && !legacy.equals("chat:mem:__ids__")`（`:248-252`）与三个碰撞族**完全等价**：`legacy = "chat:mem:" + id`，能撞 tagged 键 ⇔ id 以 `{` 开头；能撞 staging ⇔ id 以 `staging:{` 开头；能撞索引 ⇔ id = `__ids__`。
- `escapedId` 对 `%`/`{`/`}` 的转义是单射的，所以 tagged 键之间不会因转义产生新碰撞；legacy 键与 tagged 键的碰撞只可能经上述 `{` 分支。
- 迁移兼容：普通 id 的"读回落 → 保存后清 legacy"路径逐字保留，且有 `ordinaryIdsKeepTheLegacyMigrationPath`（`:105-118`）护栏。代价（`{…}`/`staging:{…}`/`__ids__` 类 id 不再做 legacy 回落/清理）已在类注释与本复审确认：这类 id 的 legacy 键本来就不是"自己的"数据，放弃迁移是正确取舍。
- 轻微过度拦截：`"{x}extra"` 这类不可能碰撞的 id 也被拒（legacy 以 `chat:mem:{` 开头），只损失一次迁移，不影响安全。

### 4.2 D2（RENAME 后复检）— 方向正确，有一处真漏洞

正确的部分：入口 `isArmed`（`:366-371`，抛异常即 fail-closed）保留；RENAME 成功后复检（`:396-401`）；命中走 `discardWrittenMemory`（`:431-443`）并 `return`，因此不写索引、不清 legacy。空窗路径走 `deleteByConversationId`，不需要复检。测试的 RED 形态（`afterStageWrite` 钩子把"装守卫"精确插在 staging 与 RENAME 之间）是确定性构造，不是 sleep。

**漏洞（I2）**：`renamed` 只在 `this.redis.rename(...)` **正常返回**后置真；若 `RENAME` 在服务端已生效但回包丢失/连接断（Lettuce 超时），异常从 `try` 抛出、`finally` 清 staging，**`purgeArmedAfterWrite` 根本不会执行**。这正是 D2 声称要关的形态：销毁已 `complete=true`，final 键仍在。修法很小：把"复检 + 撤销"移到 `finally`（或 catch 后统一执行），armed 时无条件 `DEL finalKey`（未写出时是 no-op，写出时正好撤销）。

**边界（I5）**：即使复检路径覆盖了异常，**写者在 RENAME 之后崩溃**仍无人复检；而那一轮已 `complete=true`、pending 已移除，**没有重试**。报告 §6.3 说"只能由 D2 的写侧复检兜住"——崩溃的写者兜不住。要闭合需要一条"销毁完成后仍按 guard 保留期对账记忆键"的补偿（例如 pending 保留期内由 `SessionPurgeRetryRunner` 复检 `hasLiveMemory`），或明确接受"TTL 内残留"。

### 4.3 D3（staging + 删净复核）— 方向正确，新增一个自伤缺陷

正确的部分：枚举阶段把 staging-only 会话算 `found`（`:525-534`）；删除阶段逐个**单键 DEL**（Cluster 不 CROSSSLOT）；删完复核 `hasLiveMemory` + 再扫 staging，残留 ⇒ `errors` + `complete=false`（`:421` 的 `errors.isEmpty()`），pending 保留。`RedisClusterScanSupport.scan(..., 0)` 不按结果数截断、逐 master、任一节点失败上抛，所以"扫描不完整当完整"这条路是关的。

**缺陷（I1，Important）**：`stagingKeys` 的 pattern 是 `prefix + "*"`（`:641`），而 30 行外的 `scanArchives` 用的是 `prefix + escapeGlob(sessionId) + ":*"`（`:610`）。`escapedId` 只转义 `%{}`，**不转义 glob 元字符**。于是 sessionId 含 `[` 或 `\` 时，Redis 侧 pattern 语义改变（`[` 开字符类、`\` 转义下一字符），**匹配不到本会话自己的 staging 键**：
- `sid="a[b"` ⇒ pattern `chat:mem:staging:{a[b}:*`，真 Redis 把 `[b}` 当字符类（无 `]` ⇒ 空类）⇒ 自己的键漏掉；
- `sid="a\b"` ⇒ pattern 里 `\b` = 字面 `b` ⇒ 自己的键漏掉。
此时的后果不是"删了别人的"（逐字前缀过滤挡住了），而是**自己的 staging 不删、`memoryStaged` 也是 false**，照样 `complete=true` 留残留——D3 对这类 id 反而退化为修复前。报告 §1 D3"id 自带 `* ? [ ] \` 也不会命中别的会话"只证明了半边，漏了"也不会命中自己"。
**该缺陷被测试替身掩盖**：`PurgeFakeRedis.globToRegex`（`PurgeFakeRedis.java:473-493`）把 `[`/`]` 当字面字符转义，与真 Redis 不同 ⇒ 用带 `[` 的 id 写用例在替身上会是绿的。修复 = `String pattern = escapeGlob(prefix) + "*"`（一行），并加一条 `sid="a[b"` 的用例 + 一条真 Redis/faithful-glob 的说明。

**次要（I3，Minor）**：`clearStagingKeys` 复核用的第二次 SCAN 若抛异常，`catch` 返回 `List.of()`（`:795`），于是"没有残留"⇒ `MemoryView.deleted` 可能为 `true`（`complete` 仍因 `errors` 为 false）。语义上应返回"未知/残留"或让 `deleted=false`，否则 API 字段自相矛盾。

**次要（Minor）**：报告 §6.8 说 purge 每次多 2 次 SCAN，实际是 3 次（枚举 1 + `clearStagingKeys` 2）。

### 4.4 D4（租约探针 fail-closed）— 成立且最小

- `lastProbeOk` 初值 false、`markFacilityUnhealthy()` 同时清两份记忆、5 处失败赋值全部替换（实测 `facilityHealthy = false` 只剩 `:861` 一处，即方法体内），未发现遗漏的失败观测点。
- `facilityHealthy()`（`:833-852`）在探针在飞/抢不到令牌时返回 `lastProbeOk`，而任何失败观测都会把它置假 ⇒ 不变式"节流窗口内复用的结论不优于最后一次观测"成立。成功探针会重新置真，Redis 恢复后能自回升，没有把系统钉死在只读。
- 两个用例用 latch 固定"探针在飞"，无 sleep，`facilityFailureInvalidates...` 在慢机上会走重探（必失败）⇒ 不会假绿。测试是真的。
- 但它是**另一个缺陷**（租约设施 PING 缓存 fail-open），不是 brief 里"诊断 GET 失败/续租失败"那两条；不要把它当成 RT-34/35 的修复。

---

## 5. 缺陷清单（与缺失要求分开）

### 5.1 合并卫生

| # | 严重度 | 事实 | 位置 |
|---|---|---|---|
| I6 | **Important** | 2 个生产文件是整文件重写：`RedisChatMemoryRepository` `-1,412 +1,521`、`AiSessionPurgeService` `-1,011 +1,084`；`git diff --ignore-cr-at-eol` 显示真实语义改动只有 227+/23− | `task-3-session-integrity.patch` 第 4、1038 行两处 `@@ -1,… +1,…` |
| — | 说明 | 原因是补丁侧用 LF、仓库是 CRLF；报告的 `core.autocrlf=false` 建议只是让"apply 成功"，**并不能避免整文件 changed**。后果：`git blame` 全丢、后续 Task 8/合并冲突面被放大 7 倍 | 同上 |

建议：用 `git diff --ignore-cr-at-eol` 的结果重切补丁（或先统一 CRLF 再 `git diff -U3`），把 2 个整文件 hunk 变成普通上下文 hunk。

### 5.2 实现缺陷

| # | 严重度 | 缺陷 | 位置 |
|---|---|---|---|
| I1 | **Important** | 新 staging 扫描 pattern 未 `escapeGlob`，含 `[`/`\` 的 sessionId 漏删自己的 staging 且报 `complete=true`；替身的 glob 语义比真 Redis 宽松，掩盖了它 | `AiSessionPurgeService.java:641`（对照 `:610`）；`PurgeFakeRedis.java:473-493` |
| I2 | **Important** | RENAME 结果不可知（服务端已生效、回包丢失）时异常直接穿出，armed 复检不执行 ⇒ D2 目标形态仍可复活 | `RedisChatMemoryRepository.java:380-401` |
| I3 | Minor | 删后复核 SCAN 失败被当成"无残留"，`memory.deleted` 可能与事实不符 | `AiSessionPurgeService.java:783-796` |
| I4 | Minor（测试质量） | `indexMemberMissingDoesNotHideMemoryThatStillExists` 从未构造"索引缺成员"：它只是证明"按 sessionId 删、不依赖索引"（改前就绿），没有覆盖 D3 声称的 `removedFromIndex` 掩膜，也没有覆盖 run 索引 | `AiSessionPurgeMemoryResidueTest.java:146-156` |
| I5 | Important（边界） | 写者在 RENAME 后崩溃时无人复检，且已完成销毁无 pending ⇒ 残留到 TTL；报告把它归给"D2 写侧复检"不成立 | `RedisChatMemoryRepository.java:396-403`；报告 §6.3 |
| I7 | Minor（文档） | 类注/报告称 staging 扫描"与归档键同口径"，实际少 `escapeGlob`；SCAN 次数 3 非 2；"五条写路径安全 ⇒ 无第三处缺陷"与 FINAL-AUDIT RT-34/35（V1、CONFIRMED）冲突 | `AiSessionPurgeService.java:636-638`；报告 §2、§6.8 |
| I8 | Minor（测试覆盖） | 命名空间用例只覆盖 `{x}` 与 `__ids__` 两族，报告表格列了三族（`staging:{…}` 无对应用例；代码本身是对的） | `RedisChatMemoryKeyNamespaceTest.java:51-103` |

不构成缺陷、但值得记录的取舍：`discardWrittenMemory` 用裸 DEL、无所有权校验（`:431-443`）。在"守卫已 armed ⇒ 该会话正在销毁"的前提下可接受（不存在合法的新写者），但若将来记忆键引入 epoch/版本，这里应改成条件删。

---

## 6. 缺失要求清单（未做，不是"做错了"）

| # | 缺失项 | 为什么必须在 Task 3 而不是"后续" | 最小落点 |
|---|---|---|---|
| B1 | RT-20：旧驱动的**记忆与 DB 历史**写入不受租约围栏约束 | 用户明确把 P0-2 的 RT-20/21 列入本任务；brief 的 Files 含 `ResilientChatService.java`，且 Step 1 要求"锁续期失败不得进入可写" | `persistMemory` 入口查 `runStore.runLeaseAuthority(runId)`（LOST/UNAVAILABLE ⇒ 跳过并 WARN/计数）；`recordFinalAssistant` 前先尝试 `store.save` 或先判权限（现在顺序反了）；补 `LeaseLossReadOnlyTest` 同级用例：丢租后 `saveAll` 与 `recordFinalAssistant` 都不得发生 |
| B1' | RT-21：`HELD-but-fence-ahead` 仍出帧/写台账/取号 | 同属 P0-2 的"旧驱动"面，且与 B1 同源（只看 authority、不看 fence） | 帧/台账/序号三处在 `authority==HELD` 后加 `fence ≤ epoch` 比较（`RunStore.java:1863-1866`、`:2130`、`:2185`），或用统一 `writable(runId, epoch)` |
| B2 | RT-34：续期返回 0 后 `degraded` 不置位、降级不可观测 | 用户点名；类注 `SessionGate.java:63-65` 自己承诺"不静默"，属契约未兑现 | `renewLockOnce` 返回 false 分支置 `holder.degraded=true`（现在只有异常分支置）；暴露计数（`/health` 增 `sessionLockDegraded`）或 SSE/响应字段；补"续期为 0 ⇒ degraded=true"用例 |
| B2' | RT-35：`Busy.runId` 回退成调用方自己 | 用户点名；`ResumeService` 会把它读成"取门成功"，直接破同会话单轮 | `observedRunId` 未知时返回 null（`Busy` 增 `unknown` 语义）；`AiController` 对 unknown 返回 409 不带 `reattach`（或 503）；`ResumeService` 对 unknown 一律不 `gateHeld=true`；补"ACQUIRE=0 + GET 抛错"组合用例 |
| B3 | RT-28：会话→run 索引哨兵不完整时销毁报 complete 并残留 | brief Step 1 明写"索引缺成员不得报 complete 后残留"；plan 的 Files 含 `RunStore.java` | 二选一：①销毁尾部对 `ai:runs` 做一次"本会话 run 残留"复核（销毁端点可接受全量扫描），有残留 ⇒ `complete=false`；②哨兵改为带高水位/代次的值，与候选集比对。并加"有哨兵 + 缺成员 ⇒ complete=false"用例 |
| B4 | `ai:observed:<runId>` 销毁面 | 用户直接裁定"所有 AI 数据（含该键）销毁时删除，不只 TTL"；报告把它留给集成方，等于未交付 | `deleteObservabilityKeys` 加 `redis.delete("ai:observed:" + runId)`（单键、无 tag、Cluster 安全）；加一条 purge 断言该键被删的用例；Task 2 并入前是 no-op，无编译依赖 |

> 另外：brief 的 6 个 Files 中有 3 个（`SessionGate`、`RunStore`、`ResilientChatService`）**一行未动**。其中 `RunStore`/`ResilientChatService` 的"无缺陷"结论在 §3 Q1、Q3 被推翻；`SessionGate` 的结论只对"写路径原子性"成立，对 RT-34/35 不成立。

---

## 7. 合并建议

**结论：当前形态不能作为"Task 3 完成"合并。**

- **可独立成立、可作为子集先合**：D1（记忆键命名空间）、D4（租约探针）、D3 的删除/复核骨架（需先修 I1、I3，且 D1/D3 的文件必须先按 I6 重切成最小 hunk）。这三项不依赖其它批，也不放宽任何保护。
- **D2 需先修 I2** 再合；I5 可以作为已登记边界，但不要用"由 D2 兜住"来结案。
- **合并前必须完成**：B1/B1'、B2/B2'、B3、B4（或由用户显式把这些从 Task 3 划走并另立任务）。
- **补丁重切**：把 2 个整文件重写换成最小上下文 hunk（I6/B5），否则合并会污染 blame 并放大 Task 8 的冲突面。
- 合并顺序注意：B4 依赖 Task 2 的键形契约（不依赖其类），可以在 Task 2 之前合；B1' 与 Task 1/8 可能同改 `RunStore`，按 plan 的串行规则排队。

**最小修复路径（按性价比）**：I1 一行 + I3 一行 + I2 结构微调 → 补 3 条用例（`[` id staging、有哨兵缺成员、`ai:observed` 删除）→ B2/B2'（`SessionGate` 两处 + 2 条用例）→ B1/B1'（`persistMemory`/`recordFinalAssistant`/帧台账 fence，3 条用例）→ 重切补丁 → 跑聚焦 + 每域 2 个相邻类。真实 Redis/Cluster 的 staging 扫描与拓扑仍需最终统一验证覆盖。

---

## 8. 未验证边界（本复审自身）

1. 未跑任何测试（用户要求），未起 Redis/Cluster。I1 的 Redis glob 语义结论来自 Redis `stringmatchlen` 语义 + 替身实现对比，属静态结论；修复后应在真 Redis 上补一条 `sid` 含 `[` 的用例。
2. 未验证 `RedisClusterScanSupport` 在真 Cluster 拓扑下的逐 master 完整性（沿用既有类，与归档扫描同源，风险等同）。
3. RT-20/21、RT-34/35、RT-28 的危害面引用 FINAL-AUDIT 的 V1 结论；本复审只复核了"代码未改、路径仍在"，未重做跨 JVM 复现。
