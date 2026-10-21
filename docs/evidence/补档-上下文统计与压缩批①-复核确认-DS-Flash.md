# 补档 · 上下文统计与压缩专项批① 复核确认（DS-Flash，2026-10-21）

## 0 自证与纪律

- 复核方自证行以复核链原始报告为准；本补档由合流提交官转录（体例同 `补档-模型配置批①-复核确认-DS-Flash.md`），**不代签**（提交官自证行见 `-提交报告-` §0）。
- 与施工方互盲：只读其落库的施工报告与工作树，未读其 wire。
- 只读纪律：复核轮零 commit / 零 push / 零服务启停。
- 复核时点：收口笔之后、提交官入库之前；提交官本轮的复核微修（M-a / N-a / N-b）见 §2。

## 1 结论：**READY-WITH-NITS**（0C / 0I / 0M，准予入库）

- 复审 **0C/1I/6M/5N** 中，**I-1 与 M-1/M-2/M-4/M-5/M-6 收口**、**M-3 登记**（无条文原文，不臆造修法）；5 条 N 全部落注释或登记（`-复审报告-` §3 / §4）。
- 复核轮新出 **1M + 4N**（M-a / N-a / N-b / N-c / N-d）：M-a、N-a、N-b 三条**随提交官本轮微修落地并各自有用例**（§2），N-c / N-d 登记不改（§3）。
- 收口的判据是**代码 + 用例双落点**，不以"报告里写了"为准；登记的判据是**写明去向与理由**，不假装修了。
- 断言只升不降终核通过（§4）。

## 2 三条微修逐条核验（提交官本轮落地）

| # | 复核意见 | 最终态 | 核验方式 |
| --- | --- | --- | --- |
| **M-a** | `ContextCompactor#compact` 的方法注仍写「调用方按**快照 → 记忆**顺序落库」—— 与实际执行相反，且与类注（差异段）自相矛盾。收口 M-2 只改了三处，这处是**第四处** | 方法注改为「**记忆 → 快照**」，并指向类注差异段（两处必须是同一份 ⇒ 否则续跑与下一轮的"模型看到的"分叉） | 代码面逐字核对（`ContextCompactor.java:157-159`）；与 `ResilientChatService#compactBeforeRequest`、`ResumeService` 压缩段的措辞三处对齐 |
| **N-a** | 交棒被拒（`RejectedExecutionException` ⇒ `POOL_SATURATED`）时，`noteCompaction` 已写入的**一次性级别交接位没有清除** —— 这一轮不会被驱动，`resumedTurn` 永远不来取 ⇒ 槽位留到进程结束。runId 不复用所以不致命，但"一轮一个键、读完即删"的容量论证失真，排障时会看到一条"压过 L1 但其实没跑"的幽灵记录 | `ResilientChatService` 新增 `clearCompaction(String runId)`（`compactionLevels.remove`，**不是** `noteCompaction(runId, null)` —— 后者写 `"none"` 仍占位）；`ResumeService` 的 `POOL_SATURATED` catch 里就地清一次 | `ResumeContextCompactionTest#poolSaturationClearsTheCompactionHandoffSlot`：`executor.execute` 抛 `RejectedExecutionException` ⇒ 结局 `POOL_SATURATED`；`InOrder` 断言"先 `noteCompaction(L1)`、后 `clearCompaction`"。**先红**（把清理行去掉）⇒ `Wanted but not invoked: chatService.clearCompaction(...)` |
| **N-b** | `out.context(...)` 未包异常：emitter 已关闭/序列化异常时，**发帧失败会把整轮续跑打成异常**。发帧是观测面，不得决定续跑成败 —— 而挂起轮续跑恰恰是"离开后又回来"的路径，最容易撞断连 | `try/catch(RuntimeException)` + `WARN`（"续跑 context 帧发送失败（已忽略，不影响本轮续跑）"）；`noteCompaction` 与交棒**都不在** catch 内 ⇒ 帧失败不丢级别交接、不阻断 `execute` | `ResumeContextCompactionTest#contextFrameFailureDoesNotBlockResume`：mock emitter 的 `context` 抛 `IllegalStateException` ⇒ 结局仍 `ACCEPTED`、`resumed=true`、`contextCompaction=L1`、`noteCompaction` 仍被调用、`driveResumed` 仍被调用、`clearCompaction` **未被**调用。**先红** ⇒ `java.lang.IllegalStateException: emitter closed`（异常穿出 `resume`） |

> **三条微修的红测口径**：本轮**不是**红测先行（微修已在先），故采用"**加固性用例 + 反向验证**"：落完用例后把两处修法**临时改回错误形态**跑一次，得到 2 红（`Failures: 1 / Errors: 1`，同批既有 2 例仍绿），恢复后 4/4 绿。红证据见 `-提交报告-` §3。

## 3 两条登记项（不修，写明去向）

| # | 登记项 | 为什么不修 | 去向 |
| --- | --- | --- | --- |
| **N-c** | 条文原文未随本次转录链落地（复核链只给条目编号与归属，未给正文） | **无原文不臆造修法**（与复审 M-3 / N-5 同口径）。若原文属代码面，以复核原文为准补一处注释即可，不影响本批入库 | 复核方补录原文后按条文处置 |
| **N-d** | 同上 | 同上；本轮**未**按猜测改动任何代码，以免把"没修的东西"写成"修了" | 同上 |

> 这两条的登记方式是**显式留白**，不是漏项：`docs/任务看板.md` 本批行只登记"复核 READY-WITH-NITS，M-a/N-a/N-b 随提交官收口"，不声称 N-c/N-d 已处置。

## 4 断言只升不降终核

| 面 | 结论 |
| --- | --- |
| 后端 | 859（主线）→ **937**（施工 + 收口 +78）→ **939**（提交官微修 **+2 加固性用例**）：**只增**，无删例、无放宽、无 `@Disabled` |
| 前端 | 522 / 522 · 27 文件 → **545 / 545 · 29 文件**：新增 2 个 spec 文件（`context-pill` 17 例 + `context-restore` 6 例），既有断言零改（唯一改动是 M-4 的**文案字面**同步） |
| 既有测试改造 | **零**：新增用例全部落在新文件；既有构造器签名全部由字段注入/`ReflectionTestUtils` 适配，无需改任何 `new Xxx(...)` 调用点 |
| 红测可复现 | I-1 / M-1 / M-4 与提交官三条微修各有"改回错的即复红"的实证（§2 与 `-复审报告-` §2/§3） |

## 5 实机核验说明（**本批无实例面改动**）

- **结论：本批复核不含实机/真 Redis 跑，是有意为之，不是漏跑。**
- 依据：本批新增的全部是"单会话内的计量与一次工具结果清除"——Redis 新键族 `ai:ctx:*` 只经 `RunStore` 的**现有**读写通道（`HSET` / `LPUSH+LTRIM` / `SETEX`），**没有**新增 Lua 脚本、没有跨键原子操作、没有租约/栅栏语义、没有第二个实例才成立的判据。多实例面（fencing / 资源锁 / 扇出）本批**零触碰**，故不存在"单实例绿、双实例红"的验证缺口。
- 缺失的那一段是 **E2E 面**（真上游桩 + 真 HTTP + 三形态 usage）：设计稿 §5.8 的 E2E-CTX-1..4 与 `scripts/ci/stub-upstream.mjs` 的三形态 usage 桩**均未落** —— 施工纪律明令不动 `scripts/`，且本批 E2E 用例统一归**后续 E2E 批**（与模型配置批① 的 E2E 增量同处置）。当前覆盖等价物 = 后端 `ContextMeterTest`（C-01…C-06）与 `ContextFrameWiringTest`（`done.usage` 的 `est/source`）。
- 端点面（`GET /api/ai/context/{sessionId}`）由 `AiControllerContextEndpointTest` 7 例覆盖（含 `?ledger=1`、未测会话回 `source:none`、上限未知两态），属 MockMvc 层，不代替 E2E。
