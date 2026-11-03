# K3.1 Pro 审查：记忆、展示历史与上下文压缩

- 基线：`52b09364`
- 范围：`ai/memory`、`ai/history`、`ai/context` 的全部生产代码；为闭合调用链，另外只读追踪控制器、运行编排、Redis 实现、身份传播、删除链路与相关前端调用/配置。
- 方法：静态代码审查。未运行测试、构建、CI、服务或压测，未修改生产/测试代码，未执行 git 写操作。
- 证据口径：下文“代码证实”仅指控制流、数据结构和复杂度可由代码直接推出；实际延迟、QPS、Redis CPU、线程占用时长仍需运行证据。特别是“删除接口超过 30 秒”，本报告确认多条可放大延迟的代码路径，但不在没有运行数据时把该数值写成既成事实。

## 一、结论与优先级

当前实现已具备较好的 Cluster 基础：记忆 final/staging/version/generation 同槽，transcript 的 data/bytes/ready/truncated 同槽，记忆合并与清空使用 Lua CAS，展示历史与模型上下文也已分离。主要问题不再是明显的 `CROSSSLOT`，而是**整量读放大的接口成本、Lua 内大块工作、串行摘要缺少总预算、跨实例状态合并，以及删除接口的全键空间扫描和串行阻塞调用**。

| 编号 | 严重度 | 确定性 | 摘要 |
|---|---|---|---|
| MT-01 | 高 | 代码证实 | `/history` 分页发生在整份读取、反序列化、记忆补偿和排序之后；首屏仍请求全量 |
| MT-02 | 高 | 代码证实 | L2 分块/递归摘要串行，每次独立 60 秒超时，首轮 CAS 冲突还会重算；无整次压缩 deadline |
| MT-03 | 中 | 代码证实 | transcript 计数缺失时在 Lua 内全量 `LRANGE`，字节裁剪循环执行 `LRANGE+LTRIM`，会占用单分片执行时间 |
| MT-04 | 高 | 扫描/串行等待为代码证实；实际超过 30 秒为待验证假设 | 删除一次做两次无结果上限的逐 master SCAN，活跃 run 等待不是硬 deadline，随后还有大量串行命令 |
| MT-05 | 中 | 代码证实 | `ai:ctx` 采用无版本读改写；并发记账、熔断、半开和压缩提交可互相覆盖 |
| MT-06 | 中 | 代码证实 | 上下文读失败被降级成 `EMPTY`，随后写路径可能把旧计量、谱系和熔断状态重置 |
| MT-07 | 中 | 代码证实 | transcript baseline 是“先查 ready、append、再 SETNX”，多实例会物理重复 append，读出去重不能阻止容量膨胀 |
| MT-08 | 中 | 代码证实 | transcript 对同 `entryId` 纯 last-wins；通过终态检查的旧 pending 写者仍可晚于终态 append，造成状态回退 |
| MT-09 | 中 | 代码证实 | 记忆每次合并都整窗读/解码/复制，no-op 也先完整序列化；CAS 重试和额外计数器续期放大 RTT 与 CPU |
| MT-10 | 低 | 代码证实，默认配置有缓解 | `MemoryWindow` 每淘汰一轮就重算整个后缀，配置放大后为近似 O(n²)；默认 40 条限制当前影响 |
| MT-11 | 中 | 代码证实 | 压缩记忆 CAS 成功后，状态、台账、事件分多步提交，失败会留下部分提交与展示缺口 |

## 二、实际阅读文件

### 2.1 指定目录内全部生产文件

- `ai/memory`（11 个）：`RedisChatMemoryRepository.java`、`IdentityBoundChatMemoryAdvisor.java`、`RunMemoryMerge.java`、`SessionKeyCodec.java`、`RunUpsertChatMemoryRepository.java`、`MemoryWindowSnapshot.java`、`MemoryTurn.java`、`PerRunChatMemory.java`、`RunScopedChatMemory.java`、`ReasoningOrigin.java`、`MessageJsonCodec.java`。
- `ai/history`（5 个）：`ConversationTranscriptStore.java`、`ConversationTranscriptConfig.java`、`TranscriptEntries.java`、`TranscriptEntry.java`、`SyntheticMessages.java`。
- `ai/context`（21 个）：`ContextSummaryClient.java`、`ContextLimitResolver.java`、`ContextCompactor.java`、`ToolResultClearing.java`、`ContextStateStore.java`、`ContextTrigger.java`、`MemoryWindow.java`、`ContextBreakdownService.java`、`TurnBudgetPlan.java`、`ContextMeter.java`、`ContextState.java`、`HardTrim.java`、`ConversationTurns.java`、`RollingSummarizer.java`、`ToolResultShrink.java`、`SystemMessages.java`、`ContextConfig.java`、`ContextOverflow.java`、`ContextEstimator.java`、`ContextBreakdown.java`、`CompactionFuse.java`。

上述文件均位于 `backend/src/main/java/com/example/configmgr/` 对应包下。未读取测试实现来替代生产证据。

### 2.2 为入口和返回链路追加阅读的生产文件/配置

- `backend/src/main/java/com/example/configmgr/ai/config/AiModelConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java`
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-cluster.yml`
- `frontend/src/api/ai.ts`
- `frontend/src/stores/ai.ts`

## 三、数据面与 Cluster/线程边界

| 数据面 | 结构 | 关键代码 | Cluster 结论 |
|---|---|---|---|
| 模型记忆 | LIST：消息 JSON；另有 STRING `:ver`、`:gen`，写入使用临时 LIST | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:50-65` | final、staging、ver、gen 使用相同 `{escapedSessionId}`；Lua/RENAME 同槽 |
| 展示历史 | LIST：`TranscriptEntry` JSON；另有 bytes/ready/truncated | `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:33-41` | 四键同槽；append Lua 不跨槽 |
| 上下文状态 | HASH：`ai:ctx`；LIST：ledger/events；旧 archive STRING | `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4992-5005`、`:5134-5187` | 每个运行期命令/脚本均单键；删除是逐键执行而非跨槽脚本 |
| key 转义 | 先转义 `%`，再转义花括号 | `backend/src/main/java/com/example/configmgr/ai/memory/SessionKeyCodec.java:21-37` | 记忆与 transcript 共用实现，避免 tag 漂移 |

线程边界：

- 首轮由 HTTP 线程提交到有零队列、有界拒绝的平台线程池，身份与 MDC 在提交时捕获：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:544-564`，`backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java:93-113`、`:209-219`。
- 官方记忆 advisor 的读写线程不固定；`IdentityBoundChatMemoryAdvisor` 在执行信封/入口解析身份，再在每次读写时显式开作用域：`backend/src/main/java/com/example/configmgr/ai/memory/IdentityBoundChatMemoryAdvisor.java:100-117`、`:221-258`。
- 续跑从快照恢复身份，交棒后再次按快照开作用域：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1125-1148`、`:1753-1759`。
- 摘要池为每实例 4 条平台线程、8 个排队任务；调用线程用 `Future.get` 等待，池线程重开身份作用域：`backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:90-99`、`:145-180`、`:227-235`。池不是全局跨实例限流器。

## 四、完整入口 → 服务 → Redis/DB/线程 → 返回调用链

### 4.1 首轮对话与 transcript/记忆/压缩

1. `POST /api/ai/chat` 创建 SSE，随后把 `ResilientChatService.chat` 提交到 `aiRunExecutor`：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:544-564`。
2. `ResilientChatService.chat` 在新会话先 clear；随后 `readWindow` 依次续 ver/gen TTL、读 ver、读 gen、`LRANGE 0 -1` 并反序列化：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:681-713`，`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:658-668`。
3. 轮首 `recordTranscriptUser` 先确保 baseline，再把用户原文 append 到 transcript：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3744-3767`。baseline 可能读取记忆并复制；append 进入四键 Lua。
4. `compactBeforeRequest` 做 `MemoryWindow`、L1/L2/L3 规划。L2 经 `RollingSummarizer` 串行调用摘要池；摘要在池线程调用本轮 `ProviderRuntime.chatModel`，不挂主记忆 advisor：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4680-4723`，`backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:109-141`。
5. 有变化时先 `replaceWindow`：写 staging Lua，再执行 ver/gen CAS + RENAME；成功后 `ContextCompactor.commit` 依次写 `ai:ctx`、ledger、压缩 event：`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:679-724`，`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:551-619`。
6. 模型主链路由 `IdentityBoundChatMemoryAdvisor` 再读记忆；增量写经 `PerRunChatMemory` → `upsertRun`，终局经 `convergeMemory` → `upsertRun(replaceRunBlock=true)`：`backend/src/main/java/com/example/configmgr/ai/config/AiModelConfig.java:77-107`，`backend/src/main/java/com/example/configmgr/ai/memory/PerRunChatMemory.java:29-47`，`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3887-3925`、`:3954-3986`。
7. 终态确认后写 transcript、收敛记忆、写 `ai:ctx` 用量，最后发 SSE 终帧：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2577-2583`、`:2597-2599`。

### 4.2 续跑

`ResumeService.resumeInternal` 读快照并恢复身份 → 重建 pending/历史 → transcript baseline → `readWindow` → `RunMemoryMerge` 并入本 run → `MemoryWindow` → `ContextCompactor.plan`/摘要池 → `replaceWindow` → 写快照 → `commit` → context 帧 → 提交驱动线程：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1102-1148`、`:1566-1614`、`:1618-1703`、`:1727-1759`。续跑代码自身也明确记录 L2 是 N 次串行摘要，每次最长 60 秒：`:1661-1665`。

### 4.3 历史读取

`GET /api/ai/history/{sessionId}` → `ConversationTranscriptStore.read`：

1. transcript `LRANGE 0 -1`；
2. 全量 JSON 解析、按 entryId 去重；
3. 非空 transcript 再 `chatMemory.get` → 模型记忆 `LRANGE 0 -1` + 全量消息反序列化；
4. 记忆补偿、legacy baseline 收敛、排序；
5. 四次 EXPIRE 和一次 truncated EXISTS；
6. 控制器在全量结果上切片，只对本页做展示投影；
7. 再读最多 200 条压缩 events、排序并返回。

证据：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:242-269`、`:425-438`；`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2136-2192`。空 transcript 才直接回落模型记忆：`:2163-2173`。

### 4.4 上下文读取

`GET /api/ai/context/{sessionId}` → `chatMemory.get` → `ContextBreakdownService.snapshot` → `RunStore.read`（HGETALL + EXPIRE）→ `ContextLimitResolver` 从 `AiConfigService.current()` 快照匹配 DB 模型配置 → 可选读取 20 条 ledger → 返回固定六/七项：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2672-2696`，`backend/src/main/java/com/example/configmgr/ai/context/ContextBreakdownService.java:49-84`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5013-5031`。该响应的记忆输入默认最多 40 条，成本有界；`/history` 的 transcript 则不是同一量级。

### 4.5 删除会话

`DELETE /api/ai/session/{sessionId}` → 身份检查 → `AiSessionPurgeService.purge` → pending 注册 → enumerate → guard → 删除反馈 DB 行 → 取消/等待活跃 run → 删记忆 → 删 transcript → 删 run/上下文/会话/附件 → 清理发现键并返回裸结果：`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`，`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:341-417`、`:461-499`。

与本报告直接相关的成本点：

- enumerate 对每个候选 run 读快照和 pending；随后全集群扫 staging、扫旧 archive，并推导 `0..turnNo` archive 键：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:566-684`、`:731-795`、`:817-828`。
- 活跃 run 以 50ms 节拍轮询快照，默认名义等待 1500ms：`:139-151`、`:911-947`。
- 记忆正式键用同槽 Lua 删除并推进代次，staging 逐个删除并复核：`:995-1041`。
- transcript 四个键分别 TYPE + DEL；其它上下文键同样逐键 TYPE + DEL：`:1296-1324`、`:1334-1362`。

## 五、逐项问题

### MT-01：`/history` 的“分页”没有降低存储读取和全量对账成本

- **严重度**：高。
- **确定性**：代码证实。
- **证据**：transcript 上限 5000 条、8MiB payload：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:65-69`；读取固定 `LRANGE 0 -1`：`:242-250`；随后全量解析、去重、读取模型记忆、补偿和排序：`:251-269`。控制器到 `:2174-2179` 才做 `subList`；前端首屏不传分页参数，拿到全量后才在本地切最近一页：`frontend/src/stores/ai.ts:4440-4444`、`:4499-4510`。
- **触发条件**：长会话、频繁工具状态重写、刷新/恢复历史；同一页面还会并发触发 `/context`。历史失败自动恢复最多再尝试两次，但不是固定间隔轮询：`frontend/src/stores/ai.ts:455-456`、`:4453-4461`。
- **单例 Redis 与 Cluster**：单实例承担大响应、JSON 和排序；Cluster 中同会话键同槽，因此压力集中到一个 master，同时客户端仍有完整网络传输。分页响应变小并不减少 Redis 到后端的流量。
- **多实例/多线程**：每个实例都重复全量物化；多请求线程可同时造成堆内存、CPU 和 Redis 带宽峰值。多个实例没有共享的规范条目缓存。
- **根因**：append-only 物理日志同时承担“当前条目状态”和“分页数据源”；读取侧必须全量 last-wins 去重，并为了补缺每次都读模型记忆。API 分页只是投影层优化。
- **最佳修复**：建立“规范当前条目 + 稳定游标”的读模型。建议以同槽 HASH 保存 entryId→最新条目，以有序结构保存稳定顺序，append Lua 同时更新规范条目、revision 和容量；读取按 revision/游标只取一页。记忆补偿按缺失标识或版本触发，不要每次全量执行。首屏直接请求最近一页，旧页用游标避免并发 append 导致 offset 漏重。
- **备选取舍**：保留 LIST、增加物化全量快照和 revision/ETag，改动较小，但写放大和大快照拷贝仍在；单纯提前 `LRANGE` 分页无法得到正确 last-wins 与稳定总数，不能作为正确修复。
- **涉及修改点/影响面**：`ConversationTranscriptStore` 的写读模型与容量脚本、`TranscriptEntries` 对账入口、`AiController.history` 分页语义、`frontend/src/api/ai.ts` 与 `frontend/src/stores/ai.ts` 首屏/加载更早；需迁移既有 LIST。
- **后续验证方案（不执行）**：构造 5000 物理条且含大量同 entryId 版本的会话，分别验证首屏、向前翻页、并发 append 时不漏不重；记录 Redis 返回字节、后端分配量、响应 p50/p95/p99，并证明分页后成本随 page 而非 total 增长。

### MT-02：L2 只有“单次调用超时”，没有“整次压缩预算”

- **严重度**：高。
- **确定性**：代码证实。
- **证据**：分块调用和递归合并均为串行循环：`backend/src/main/java/com/example/configmgr/ai/context/RollingSummarizer.java:212-245`；每次 `summarize` 都重新以完整 `summary-timeout` 等待：`:145-168`（`ContextSummaryClient.java`）；默认每次 60 秒：`backend/src/main/resources/application.yml:190-196`。首轮整窗 CAS 失败会重读并重跑规划，最多五轮：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4690-4734`。
- **触发条件**：旧前缀需要多个 chunk、递归 merge、多轮 CAS 冲突、摘要提供方缓慢或线程池排队。一个默认超时就已超过 30 秒；多个调用会线性累加。
- **单例 Redis 与 Cluster**：摘要本身不读写 Redis，但压缩期间持有运行线程和保活资源；CAS 重试额外产生记忆整窗读写。Cluster 拓扑不改变串行模型等待。
- **多实例/多线程**：每实例独立 4 线程 + 8 队列，实例数增加会放大上游摘要并发；池满只在本实例拒绝。调用线程被 `Future.get` 阻塞；取消 future 不等于已经证明上游 socket/模型工作立即停止。
- **根因**：超时作用域是“一次模型补全”，而业务等待对象是“整次 L2 压缩”；CAS 重算也没有跨尝试复用已成功的分块结果或独立的摘要预算。
- **最佳修复**：在 `ContextCompactor.plan` 入口建立绝对 deadline，把它传入 `RollingSummarizer` 和 `ContextSummaryClient`；每次提交/等待只使用剩余时间，deadline 到达即保持历史原样。设置整次最大摘要请求数，并在外层 CAS 重试间复用与输入内容绑定的分块结果。保留每次 socket/调用超时作为更细的护栏。
- **备选取舍**：异步压缩并交还请求线程可改善占用，但会改变“模型必须看到已提交压缩结果”的时序，不能直接替代总 deadline；提高每实例线程数只会增加上游压力。分布式信号量可限制全局摘要并发，但引入 lease/恢复复杂度，优先级低于总预算。
- **涉及修改点/影响面**：`ContextSummaryClient`、`RollingSummarizer.Summarizer` 签名、`RollingSummarizer`、`ContextCompactor`、首轮/续跑编排、`AiProperties.Context` 和配置文件；需保持摘要身份、runtime 与中断语义。
- **后续验证方案（不执行）**：覆盖多分块、递归合并、池排队、每次超时、首轮连续 CAS 冲突、续跑取消；断言整次压缩不超过配置 deadline，取消后无迟到写回，池线程无泄漏，身份与本轮模型不串线。

### MT-03：transcript Lua 在计数异常/迁移时做全量工作，裁剪也在服务端循环

- **严重度**：中。
- **确定性**：代码证实；具体阻塞毫秒数需运行测量。
- **证据**：计数缺失、非正或非数字且 LIST 已存在时，脚本执行 `LRANGE 0 -1` 并遍历全部元素：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:113-143`；超字节后每次最多取/裁 200 条，while 可重复：`:144-152`。客户端把所有 payload 一次作为 Lua ARGV 发送：`:396-420`。
- **触发条件**：旧版本键没有 `:bytes`、计数被删/先过期、计数漂移，或单批 append 使 8MiB 上限被超过。计数正常时常规条数裁剪只读待删前缀，问题不应被夸大为每次写都全量核算。
- **单例 Redis 与 Cluster**：Lua 在 Redis 主线程串行执行；单实例会拖住同节点其它命令，Cluster 则拖住该 hash slot 所在 master 上的其它会话。8MiB 是 payload 上限，不含 Redis 对象开销。
- **多实例/多线程**：多个实例对同会话 append 虽由 Lua 保证计数原子性，但也把重算/裁剪竞争串行化到 Redis；应用线程同步等待脚本结果。
- **根因**：把“异常计数修复”和正常写请求共用一条在线 Lua；容量控制同时追求精确字节与原子裁剪，导致异常分支的服务端工作量与整个 LIST 成正比。
- **最佳修复**：正常写路径禁止全量重算。对缺计数旧键设置一次性迁移状态，由有界后台任务分批重建计数；迁移期间采用明确的保守容量策略。把写批次大小和脚本执行预算设为硬上限，并保留原子计数更新。
- **备选取舍**：在线重算实现简单但正是当前风险；改用条数近似字节会降低内存控制精度；将 recount 拆成多条命令会降低单脚本阻塞，却需要迁移锁/状态防止并发 append 破坏计数。
- **涉及修改点/影响面**：`APPEND_SCRIPT`、`appendWithBounds`、transcript 迁移/运维对账、purge 对新增迁移键的清理；不改模型记忆语义。
- **后续验证方案（不执行）**：分别构造计数正常、缺失、损坏和超 8MiB 的 LIST，观察 Redis 命令耗时/慢日志和同一 master 其它会话延迟；验证并发 append 下 bytes 与真实 payload 总和一致、无永久键。

### MT-04：删除接口存在多段串行、无总 deadline 的 Cluster 工作量

- **严重度**：高。
- **确定性**：代码证实存在可放大路径；“现网超过 30 秒”是需运行数据确认的假设，不能仅靠本审查下结论。
- **证据**：每次删除都调用 staging SCAN 和 archive SCAN：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:631-665`；两者传入 `maxKeys=0`，即不按结果数截断：`:778-795`、`:817-828`。Cluster 逐 master 串行 drain 到游标结束：`backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java:98-147`。`COUNT 256` 是每批提示，不是总量上限。活跃等待只在 while 入口检查 deadline，内部仍顺序遍历所有 run 并做阻塞读：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:915-947`。transcript/上下文随后逐键 TYPE + DEL：`:1296-1357`。
- **触发条件**：大 keyspace、多 master、SCAN 慢/重定向、候选 run 多、活跃 run 快照读取接近命令超时、历史 archive 多、自动/手动重试叠加。配置命令超时为 2 秒：`backend/src/main/resources/application-cluster.yml:30-36`。即使名义 activeWait=1500ms，一轮 for 内多个 2 秒读也可能越过该时间；再叠加两次全扫描和其它串行删除，具备超过 30 秒的结构条件。
- **单例 Redis 与 Cluster**：standalone 仍需全 keyspace SCAN；Cluster 把同一过程扩展为逐 master。SCAN 不是 KEYS 式一次性阻塞，但大量游标往返会延长接口并占用连接；Lua/单键删除本身没有跨槽问题。
- **多实例/多线程**：HTTP 请求线程同步承担全流程；多实例同时 DELETE/自动重试会重复扫描同一集群。大 LIST 使用同步 `DEL`，其实际释放成本还取决于服务端 lazyfree 能力/配置，本次没有服务器运行配置证据。
- **根因**：为了发现无索引的 staging/旧 archive，在请求热路径做全集群扫描；活跃等待是“循环节拍预算”而非覆盖每个阻塞调用的硬预算；所有阶段共用一个同步请求，没有总 deadline。
- **最佳修复**：把删除改为有明确总预算的后台作业，HTTP 只做 pending/guard 登记并立即返回可查询状态。staging 用同槽的 per-session 登记集合维护精确成员，删除时读该集合并清理；旧 archive 做一次性迁移/清账后退出请求期 SCAN。等待循环在每个 Redis 调用前检查剩余预算，并限制候选数/每轮工作量。
- **备选取舍**：保留同步 200 契约可减少前端改动，但必须取消全扫描并严格限时；staging 集合会让每次记忆写增加一次维护操作及失败补偿，换取删除可预测性。对大键用 UNLINK 可降低同步释放风险，但需确认 Redis 兼容版本，且不能解决扫描和串行等待。
- **涉及修改点/影响面**：`RedisChatMemoryRepository` staging 生命周期、`AiSessionPurgeService` 枚举/等待/删除、`RedisClusterScanSupport` 使用面、purge controller 响应和前端删除进度；需迁移旧 staging/archive。
- **后续验证方案（不执行）**：在隔离的三主 Cluster 分别放入不同 keyspace、run 数、archive 数，注入单节点慢 SCAN 和快照读超时；记录每阶段耗时与命令数，证明 HTTP 有界返回、后台删除最终 complete，且并发重试不重复放大扫描。只有在该证据中测得 >30 秒，才把用户报告数值闭环为事实。

### MT-05：`ai:ctx` 无版本读改写，多 run/多实例会丢状态更新

- **严重度**：中。
- **确定性**：代码证实。
- **证据**：熔断类已经承认读→判→写非原子，半开可多方尝试：`backend/src/main/java/com/example/configmgr/ai/context/CompactionFuse.java:21-25`。实际影响不止半开：`recordTurn` 读后整份派生再写：`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:674-683`；摘要失败也读出的旧 state 上加一并写回：`:740-747`；底层只是 HSET + EXPIRE：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5034-5052`。首轮五次 CAS 重算时，`ContextState state` 还在循环外只读一次：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4688-4696`。
- **触发条件**：同会话多 run 同时完成、两个摘要失败同时计数、冷却到点多实例同时 half-open、压缩与轮末记账交错。
- **单例 Redis 与 Cluster**：单实例多线程即可发生；Cluster 不引入也不解决该问题，因为 HSET 本身没有比较基线。
- **多实例/多线程**：后写者可覆盖较新的 usage、turnNo、provider/model、谱系或 fuseFails；失败计数丢失会延长故障期摘要尝试，陈旧的 usage 又会错误触发/抑制后续压缩。
- **根因**：把整份 `ContextState` 当文档做读改写，却同时承担计量、压缩谱系和熔断三种并发写面；没有 version/CAS 或字段级原子状态机。
- **最佳修复**：拆分原子写面：轮末只 HSET 本轮计量字段并 HINCRBY turnNo；熔断用单键 Lua 原子完成“读状态→half-open/失败计数/闭合”；压缩提交携带状态版本做 CAS。确需保留整份写入时增加 version 并针对最新基线重算，不能拿旧内容盲目重试。
- **备选取舍**：会话级分布式锁实现简单但会把允许并行的 run 全部串行化；只用 `atMs` 最后写入者胜依赖多实例时钟且不能正确累计 fuse 失败，不建议作为唯一修复。
- **涉及修改点/影响面**：`ContextCompactor`、`CompactionFuse`、`ContextStateStore`、`RunStore`、首轮/续跑状态读取；前端只消费最终状态，无需改协议。
- **后续验证方案（不执行）**：两实例/多线程交错完成 run、摘要失败和 half-open，断言 turnNo 不丢增、连续失败计数不丢、usage/model 属于明确获胜轮、谱系不回退；再覆盖首轮 CAS 重算时状态已变化的情形。

### MT-06：读失败降级结果可进入后续写路径，可能清空既有上下文状态

- **严重度**：中。
- **确定性**：代码证实存在该写入形态；是否发生取决于“读失败、随后写成功”的故障交错。
- **证据**：`ContextCompactor.readState` 把异常变成 `ContextState.EMPTY`：`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:689-698`；`recordTurn` 随后以该值为基线写 usage：`:674-683`。更底层 `RunStore.read` 同样吞异常并返回 EMPTY：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5013-5031`。`ContextState.withUsage` 保留基线的 level/fuse 等字段，因此错误基线会把它们写成缺省：`backend/src/main/java/com/example/configmgr/ai/context/ContextState.java:69-74`。
- **触发条件**：HGETALL 暂时失败而后续 HSET 恢复，或调用方把“不可知”误当“从未记账”；压缩 plan/commit 也使用同一降级读口。
- **单例 Redis 与 Cluster**：两者均可能在故障恢复窗口发生；Cluster 的拓扑切换/重定向增加此类交错的触发机会，但不是必要条件。
- **多实例/多线程**：任一实例的读抖动都可能把共享会话状态回退；其它实例随后看到空谱系/闭合熔断，产生重复摘要和错误计量。
- **根因**：展示端点所需的“读不到就显示空态”与写路径所需的“不知道就不得覆盖”共用了同一 API。
- **最佳修复**：区分 `readForDisplay` 与 `readStrictForUpdate`。写路径读失败必须放弃本次整份写入；轮末记账优先改为无需读基线的字段级原子更新，从根本上避免该依赖。
- **备选取舍**：只在 `recordTurn` 检查日志/空态不足以区分真正空键，必须让存储层保留错误或显式存在性；重试读可降低概率，但不能代替禁止“未知基线写回”。
- **涉及修改点/影响面**：`ContextStateStore`、`RunStore.read/write`、`ContextCompactor.readState/recordTurn/plan/commit`；展示 503/降级口径可保持不变。
- **后续验证方案（不执行）**：故障注入“第一次 HGETALL 失败、下一次 HSET 成功”，验证谱系、compSeq、fuse、turnNo 不被重置；真正不存在键仍能初始化；端点读失败仍按既有契约降级。

### MT-07：baseline 初始化不是单飞，多实例会重复物理复制

- **严重度**：中。
- **确定性**：代码证实；代码注释也明确承认多实例可能各复制一份。
- **证据**：`ensureBaseline` 先 `hasKey(ready)`，之后序列化并 append baseline，最后才 `setIfAbsent(ready)`：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:312-343`；注释说明多实例重复及读取去重：`:295-304`。LIST 物理追加不会因相同 entryId 自动去重：`:98-107`。
- **触发条件**：旧会话首次升级、ready 不存在，两个实例/线程同时执行轮首、挂起或终态写点。
- **单例 Redis 与 Cluster**：单实例多线程足够触发；Cluster 同槽 Lua 只保证每次 append 自身原子，不保证整个初始化单飞。
- **多实例/多线程**：展示读取按 entryId last-wins，通常不显示重复气泡；但物理条目、网络、Lua 容量核算和裁剪都翻倍，可能更早触发 5000/8MiB 裁剪。
- **根因**：ready 是完成后的标记，而不是初始化所有权；检查、复制和置标不是一个原子协议。
- **最佳修复**：增加带 TTL、owner 的 `initializing/ready/failed` 状态机，先用 SETNX 抢占初始化权；只有 winner 读取并复制，失败时按明确状态允许有界接管。若采用 MT-01 的规范 HASH，可让同 entryId 写天然幂等，进一步降低重复成本。
- **备选取舍**：直接把 ready 提前 SETNX 再复制会在进程崩溃后永久缺 baseline，不可接受；仅用分布式锁能解决问题，但需要处理持锁死亡与等待预算。接受重复并只改读侧无法解决容量问题。
- **涉及修改点/影响面**：`ConversationTranscriptStore.ensureBaseline`、键/TTL 清理、三个 baseline 调用面、purge 键清单；读取仍须兼容旧重复数据。
- **后续验证方案（不执行）**：两个实例同时对同一旧会话执行不同写点，验证物理只复制一次；注入 winner 在 append 前/中/后退出，验证租约接管、无永久 initializing、无重复气泡、无跨身份复制。

### MT-08：transcript last-wins 没有终态单调性，旧写者可把终态盖回 pending

- **严重度**：中。
- **确定性**：代码证实竞态窗口；具体出现频率需并发运行证据。
- **证据**：读取只按 append 顺序覆盖同 entryId：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:251-260`。挂起写点先读快照判非终态，之后独立做 authority check 和 append：`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3557-3589`；批次写也有相同“先检查、后 append”结构：`:3643-3713`。这些检查与 LIST append 不在同一原子操作中。
- **触发条件**：写者 A 读到非终态后被暂停；写者 B 将 run 置为 DONE/CANCELLED/FAILED 并 append 终态；A 恢复后再 append 旧 pending/approved。多实例接管和工具回调延迟会扩大窗口。
- **单例 Redis 与 Cluster**：单实例线程调度即可触发；Cluster 的 RPUSH 原子性只保证顺序，不保证业务状态单调。
- **多实例/多线程**：展示历史可能回退；模型记忆补偿能补 assistant 的部分终态事实，但既有 tool 条目不会被同样合并，不能把它当成完整防线：`backend/src/main/java/com/example/configmgr/ai/history/TranscriptEntries.java:265-285`。
- **根因**：entryId 只有身份，没有单调版本/状态优先级；终态检查和写入分离。
- **最佳修复**：写入携带 run fencing/单调序列，在规范条目更新脚本中拒绝旧版本；读侧另加“终态优先于 pending/approved，同级再按版本”的兜底，兼容既有数据。
- **备选取舍**：仅按 `atMs` 排序依赖多实例时钟且无法定义状态优先级；只在写前再读一次快照仍留下检查后窗口。读侧状态优先级修复较小，但物理旧版本仍占容量，最好与 MT-01 的规范条目写模型一起完成。
- **涉及修改点/影响面**：`TranscriptEntry`/entry 版本、`ConversationTranscriptStore` 读写、挂起/批次/终态写点、记忆补偿；前端状态映射保持兼容。
- **后续验证方案（不执行）**：用可控屏障复现 A/B 上述顺序，覆盖 pending→EXECUTED、pending→CANCELLED、approved→FAILED；验证旧写不能回退终态，同终态的合法补充仍可更新。

### MT-09：记忆合并热路径有整窗复制和多轮 RTT，no-op 也支付完整序列化

- **严重度**：中。
- **确定性**：代码证实。
- **证据**：每次 `upsertRun` 先查活跃 run 和 gen，每次尝试再读 ver、整窗 LRANGE、反序列化、合并：`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:563-575`；`:575` 先 `serializeAll(merged)`，`:578` 才检查 `sameWindow`，因此 no-op 也完整序列化。no-op 还顺序续两个计数器 TTL：`:578-582`、`:750-758`。staging Lua 逐元素 RPUSH，一次携带全部 JSON：`:833-840`。CAS 最多五次，每次重读/重算：`:569-622`。
- **触发条件**：官方 advisor before/after、轮末收敛、多 run 同会话并发、大 metadata/工具参数、CAS 冲突。no-op 是常见的重复收敛/幂等写场景。
- **单例 Redis 与 Cluster**：单实例主要支付 RTT 和 CPU；Cluster 同槽避免 CROSSSLOT，但所有请求集中到同一 master，且未减少网络次数。staging 脚本的执行和 ARGV 大小随窗口体量增长。
- **多实例/多线程**：CAS 正确性由 ver/gen 保证，但冲突越多，重复反序列化/序列化和临时对象越多；每个实例独立承担 CPU，Redis 承担重复读写。
- **根因**：为了通用 SPI 在每个增量写都走整窗读修改写；早退判断放在序列化之后；计数器/窗口读取和 TTL 没有合并。`RunMemoryMerge` 还需构建包含完整正文的去重 identity，并按 run 块排序：`backend/src/main/java/com/example/configmgr/ai/memory/RunMemoryMerge.java:196-203`、`:269-282`、`:316-329`。
- **最佳修复**：先把 `sameWindow(merged,current)` 移到序列化之前；用同槽 Lua 一次读取 ver/gen/LRANGE 形成快照，计数器续期合并为脚本或可靠 pipeline；保留现有 CAS 方向。进一步可维护内容 hash/规范化摘要，减少大 metadata 的深比较和重复编码。
- **备选取舍**：按 run 拆独立 LIST 可减少合并范围，但整窗读取、压缩和排序会变复杂，仍需同槽聚合视图；简单增加 CAS 次数只会增加资源消耗，不应作为冲突处理方案。
- **涉及修改点/影响面**：`RedisChatMemoryRepository`、`MessageJsonCodec`、`RunMemoryMerge`、活跃 run lookup；无需改变 transcript 或前端协议。
- **后续验证方案（不执行）**：对 no-op、追加、替换和 1/5 次 CAS 冲突分别统计 Redis 命令数、序列化字节与耗时；验证早退不推进版本，CAS/代次/TTL 行为完全不变，大工具消息无内容损失。

### MT-10：MemoryWindow 的重复后缀估算在配置放大后成为二次复杂度

- **严重度**：低。
- **确定性**：代码证实；默认配置下影响有限。
- **证据**：`dropOldestTurns` 每增加一个 cut，就对 `messages.subList(from,...)` 重新执行 measure：`backend/src/main/java/com/example/configmgr/ai/context/MemoryWindow.java:219-246`。默认 max/min 都是 40：`backend/src/main/resources/application.yml:154-168`，因此当前默认窗口很小，且 token 淘汰受 min 限制。
- **触发条件**：运维显著提高 max-messages、降低 min-messages，且大量长消息需要逐轮淘汰。
- **单例 Redis 与 Cluster**：这是应用 CPU/对象遍历，不直接阻塞 Redis；但延长请求线程占用并推迟后续 Redis/模型调用。
- **多实例/多线程**：各实例独立消耗 CPU，多请求可同时放大。
- **根因**：每轮从后缀重新计算 token，而非预计算每条消息 token 和后缀和。
- **最佳修复**：先一次计算每条消息 token，再用后缀和/累计条数选择 cut；保持 turn/pin/min/max 判据不变。
- **备选取舍**：维持现状在默认 40 条下合理；若仅为默认配置重写算法，收益有限。配置开放更大窗口前再改也可接受，但应加配置上限或复杂度说明。
- **涉及修改点/影响面**：仅 `MemoryWindow` 和对应窗口验证；不改存储格式。
- **后续验证方案（不执行）**：用不同消息数/长度验证新旧 plan 完全一致，并确认运行时间随 n 近似线性；默认 min=max=40 的 L1 移交行为不变。

### MT-11：压缩提交是多步非原子过程，Redis 故障会留下部分事实

- **严重度**：中。
- **确定性**：代码证实；实现注释也明确承认 state/ledger/facts 可能不完整。
- **证据**：`commit` 依次写 state、ledger，再取序号并写 event；任一步异常统一返回 `committed=false`：`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:561-619`。取号和 event append 也是分开的 Redis 操作：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5139-5166`。此时记忆 CAS 已经成功，不能简单回滚到原历史。
- **触发条件**：state 成功而 ledger 失败、ledger 成功而取号/event 失败，或进程在步骤间退出。
- **单例 Redis 与 Cluster**：单实例同样发生；Cluster 各键目前不是同槽原子脚本，不能靠 Redis 事务自然解决。
- **多实例/多线程**：其它实例可看到“状态已压但事件缺失”，刷新恢复少一个压缩节点；后续重试可能产生新的序号/事件，需要幂等身份避免重复。
- **根因**：记忆、状态、台账和事件没有统一提交记录/可恢复状态机，错误处理只保证“不发 frame”，不能补交缺漏。
- **最佳修复**：引入稳定 compactionId 和提交状态机，按 compactionId 幂等补写 state/ledger/event；读取侧能区分 committed/pending，后台对账完成未竟提交。若要让 Redis 内部一步提交，需先让相关键同槽并合并 Lua，但仍不能和记忆 CAS 形成跨键全局事务。
- **备选取舍**：保持现状并只发 WARN 成本低，但长期展示缺口仍存在；简单交换写入顺序只会移动部分失败窗口，不解决问题。
- **涉及修改点/影响面**：`ContextCompactor`、`ContextStateStore`、`RunStore`、history events 读取、purge/重试对账；需要兼容旧 eventId。
- **后续验证方案（不执行）**：在每个写步骤后注入异常/进程退出，验证重试按 compactionId 补齐且不重复，历史与模型窗口保持一致，committed=false 时不发伪成功帧。

## 六、轮询专项结论

- 未发现 `/history` 或 `/context` 由前端固定 `setInterval` 持续调用；现有调用主要是刷新/恢复和显式重载。`frontend/src/stores/ai.ts:4271-4303`、`:4311-4324`、`:4440-4514`。因此不能把这两个端点描述为常态定时轮询；但全量历史在每次恢复及最多两次自动重试中都会重复支付。
- 删除活跃 run 使用 50ms 同步轮询，且每个活跃 run 顺序读 Redis：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:911-947`。它是本范围最明确的 Redis 轮询热路径，问题已由 MT-04 覆盖。
- 摘要不是轮询，而是多分块/多轮递归的串行模型请求；其时间叠加由 MT-02 覆盖。
- `findConversationIds` 虽逐 master SCAN，但生产 `src/main/java` 未发现调用方，不能把它列为当前请求热路径：`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:373-414`。

## 七、未发现问题的关键路径与现有有效防线

1. **记忆 Cluster 同槽**：final/staging/ver/gen 共享同一 escaped hash tag，staging 写、CAS+RENAME、清空脚本均没有发现跨槽 `KEYS` 使用：`RedisChatMemoryRepository.java:168-204`、`:235-240`、`:313-336`、`:590-593`、`:905-910`（均位于 `backend/src/main/java/com/example/configmgr/ai/memory/`）。
2. **CAS 与清空代次**：合并写版本不符会重读重算，代次不符会放弃；压缩单次 `replaceWindow` 不用旧内容直接覆盖。`readWindow` 计数器先于窗口读，竞争中即使读到新窗口/旧版本，后续 CAS 也会保守失败，而非证实丢新消息：`RedisChatMemoryRepository.java:567-622`、`:653-724`。
3. **生产写入口**：生产装配用 `PerRunChatMemory` 接管 add，终局走 upsert；在已读生产主链中未发现 Redis 仓储 `saveAll` 的整窗覆盖调用。`saveAll` 仍是公开 SPI 能力面，不能据此宣称未来调用也受 CAS 保护：`backend/src/main/java/com/example/configmgr/ai/config/AiModelConfig.java:77-90`，`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3964-3978`。
4. **transcript Cluster 键**：data/bytes/ready/truncated 同槽，append Lua 没有发现跨槽访问；精确 UTF-8 字节计数在脚本内同步维护：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:94-159`、`:416-419`。
5. **展示与模型上下文隔离**：压缩摘要和工具清理只改模型记忆；transcript 过滤 synthetic 与 L1 占位符，保留用户面原文：`backend/src/main/java/com/example/configmgr/ai/history/TranscriptEntries.java:21-30`、`:632-641`，`backend/src/main/java/com/example/configmgr/ai/history/SyntheticMessages.java:88-123`。
6. **身份传播**：首轮执行器、续跑、官方工具回调和摘要池均有显式身份机制。`ensureBaselineFromMemory` 内部的 `chatMemory.get` 确实依赖当前线程作用域：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:287-293`；但已追踪的生产调用点位于首轮/续跑/工具记录身份作用域内，因此本次未证实现有主链会错读 legacy 键。该隐含前置条件应通过注释或方法内显式 `RunIdentityScope.open(tenantId,userId)` 固化，避免未来新调用者破坏它。
7. **摘要资源池有界**：不是无界创建线程；池满拒绝、超时/中断会 cancel future，并恢复中断标记。问题在整次预算，而不是池完全无界：`backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:145-180`、`:227-235`。
8. **上下文响应有界**：`/context` 只遍历默认 40 条模型记忆，ledger 最多 20 条；未发现随 transcript 总轮数增长的同类问题：`backend/src/main/java/com/example/configmgr/ai/context/ContextBreakdownService.java:16-29`。
9. **压缩序号跨实例唯一**：`HINCRBY` 单键原子取号可保证序号不冲突；MT-11 指出的是后续多步提交完整性，不是取号冲突：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5139-5151`。
10. **工具配对保护**：MemoryWindow、HardTrim、RollingSummarizer 以 turn/保护集处理，RunMemoryMerge 裁剪避免以 TOOL 开头；本次未发现明显“分页/压缩切断 tool_call/tool_result”的新证据。

## 八、建议实施顺序

1. 先处理 MT-04 与 MT-02：给删除和整次压缩建立硬预算，消除最长尾等待；删除侧同步退出全集群 SCAN。
2. 随后处理 MT-01/MT-03/MT-07/MT-08：这些共同指向 transcript 的物理日志模型，适合一次设计规范条目、revision、初始化和单调状态，避免分别在旧 LIST 上叠加补丁。
3. 再处理 MT-05/MT-06/MT-11：把上下文计量、熔断、压缩提交拆成可原子恢复的状态机。
4. 最后做 MT-09/MT-10 的局部优化；其中“no-op 先比较再序列化”是可独立提前的小改，但仍需完整并发与 TTL 验证。

本报告仅完成静态审查，所有运行验证均为后续方案，未在本轮执行。
