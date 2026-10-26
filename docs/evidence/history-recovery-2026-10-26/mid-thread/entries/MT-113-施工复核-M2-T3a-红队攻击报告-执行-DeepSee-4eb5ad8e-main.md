<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-113 · 施工复核 · M2-T3a 红队攻击报告（执行：DeepSeek V4 Pro）（4eb5ad8e / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`RunRegistry`、`SseChatEmitter.java`、`RunStore`。
> **稿态**：历史中间稿 —— 该 wire 第 `m12` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 10 条有正文），末条有正文消息为 `m13`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `4eb5ad8e` / agent `main`（`main` = 主会话）；原字符 4,751（链内 1 段、合计 4,751）。
> **链号 / 类别**：`MT-113` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m13`（755 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

# M2-T3a 红队攻击报告（执行：DeepSeek V4 Pro）

- 对象：`<REPO_ROOT>` 工作区**未提交全态**（HEAD=2125126，14 条改动：11 改 + 2 测试新增 + 证据文档新增 + 设计卡 1 行）。
- 事实源：设计卡 v1.2（T3-1/T3-2/R1 裁决/施工注记 R2~R6）、证据文档 §1~7（只作线索，不采信）。
- 纪律：全程零 git 写操作（跑测前后 `git status --porcelain` 均 14 行一致）；未读 `.env`；探针放仓库外；本报告用 `<REPO_ROOT>` 等占位符，无禁则字样组合。

---

## 0. 验证基线（本棒【实测】）

- 全量测试：`mvn.cmd -B test` → **Tests run: 277, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**；42 个 surefire XML 独立求和 = 277/0/0/0，与基线一致；跑测前已核 class 产物新于源码、XML 无陈旧残留。
- 探针（仓库外 `<LOCAL_WORK>\m2t3a-probe\`，复用既有 classpath）：
  - 原始 `Probe.java`（攻击 S1-1 旧缺陷）：**[A 池饱和] 丢失=0、[B 200 次竞态] 丢失 0%、[D 4 线程占满] 丢失 0、[C 静态池泄漏] 装配前后均即时到达=1** → 修复批的终态排空与两参直执均已闭环。
  - 新增 `ProbeR1.java`（攻击 R1 双口径）：见 S1 问题。

---

## 1. 六个关键判断点结论

| # | 判断点 | 结论 | 一句话理由 |
|---|---|---|---|
| 1 | S1-1 并发正确性 | **通过** | `complete()`/`drain`/`completeOne`/`evict` 三路并发经 `completed` CAS 恰好一次 complete；`sub.terminating` volatile 保证可见；R2「先清标志再复查」顺序正确，活订阅者无丢 drain 窗口死角；【实测】probe A/B/D 零丢失 |
| 2 | R1 双口径不变量 | **击破** | 回放批与实时帧共用一条队列，实时容量闸按**绝对队列长度**判定 → 慢重挂者回放排空期被任一实时帧（心跳 2s 一次）摘除；【实测】probe 复现 |
| 3 | T3-2 正确性 | **通过** | 惰性 anchor `max(归档末帧, ai:seq)+1` 在部分归档失败下无重号（SeqPersistenceTest 锚定）；时钟不参与 seq 路径故回拨无重号风险；delta 跳档后状态帧为严格递增子序列，孤儿过滤无洞；附 1 条 S3 TTL 观察 |
| 4 | S2-1 修复 | **通过** | 生产侧零 `new RunRegistry(store,mapper)` 两参调用（grep 实测），三参 @Autowired 恒异步池；真实异步路径由 DeliveryTest 5 参/3 参覆盖，无「测试测不到真实路径」分歧 |
| 5 | 新 5 条用例真断言 | **通过** | :262/:286/:321 真异步池+阻塞替身+确定性拒绝，真断言；:360/:378 有 2 处弱断言（见 S3）；TerminalSemanticsEmitter 与真实 emitter 的 send-after-complete 语义同口径，无假绿构造 |
| 6 | 自由攻击面 | 见 §2 | 1×S1、若干 S3 |

---

## 2. 问题清单

### S1（阻塞）

**S1-1　R1 双口径不变量被击破：回放 backlog 污染实时容量闸，慢重挂者被实时帧确定性摘除 → 断点续收饥饿**

- 证据（代码，【旁证】）：回放批 `stageReplay`（`SseChatEmitter.java:321`）与实时帧 `enqueueLive`（`:637-643`）写入**同一条** `sub.queue`；实时闸判据是**绝对长度** `sub.queue.size() >= queueCapacity`；而 `emit`（`:617-625`）对心跳与业务帧一视同仁地逐订阅者 `enqueueLive`。即「回放段豁免」只在 *staging 入队那一刻* 豁免，实时闸根本不区分「队列里的回放 backlog」与「实时段」。
- 证据（探针，【实测】）：`ProbeR1.java` 场景 1——seed 3000 帧 → 慢订阅者 `replayAndAttach` staged=3000 → 首帧阻塞在写出 → 一条心跳 `out.heartbeat(...)` → **输出 `摘除订阅者 … reason=实时队列溢出（容量 256）`，complete=true，最终收到 0 帧**。场景 2——带 lastSeq=1 重挂 staged=2999 → 一条心跳再次摘除，收到 0 帧。
- 复现路径：`<REPO_ROOT>/backend` 下任一线程：① 归档铺 >256 条状态帧；② 慢订阅者（首帧写出阻塞）`replayAndAttach(emitter, null, false)`；③ 待 drain 阻塞后发任一实时帧（挂起期心跳 2s 一次必触发）；④ 观察到该订阅者被 `evict`+`complete`，回放剩余被丢弃。
- 影响：T3-1 明确要**保护**的慢重挂者恰是受害者——回放排空期间只要来一条心跳即被摘除；摘除后重挂若剩余回放 ≥256，下一次心跳再次摘除，**确定性饥饿**（客户端永远收不完尾批）。这直接违反 R1 裁决「回放初始批豁免有界容量、慢重挂者回放 3000 帧不阻塞 emit」的意图。
- 根因/修复方向：需把「实时段容量」与「回放 backlog」分离——实时闸只统计实时段入队数（独立计数器或回放完成后才开闸），或按证据文档 §五.2 自述的「物理有界 + 回放批另设暂存道」。证据文档 §五.2 的偏差登记（「无界队列 + emitLock 内精确容量闸…回放批豁免因此免第二批入队通道」）正是本缺陷的自我暴露。

### S2（严重）

无。除 S1 外未发现达到「严重」级、影响活客户端正确性的独立缺陷。

### S3（建议/观察）

1. **emitter 生命周期回调只 remove 不清队列/不复位 draining**（【旁证】代码阅读，【推断】可达性）：`attachSubscription` 的 `onCompletion/onTimeout/onError` 三回调（`SseChatEmitter.java:230-232`）仅 `subscribers.remove(sub)`，不像 `evict`/`completeOne` 那样 `queue.clear()`+标志复位。慢客户端超时/断开后，其无界队列（回放豁免下可至 3000 条）与 `draining` 可能滞留：`drain` finally 的重抢守卫 `subscribers.contains(sub)` 判假时，CAS 已把 `draining` 置回 true 却不再补投（`:696-699` 三条件与 CAS 非原子），形成死订阅者的内存泄漏 + 永久卡死的 drain 标志。对活客户端无影响，故 S3；但 `TerminalSemanticsEmitter` 不触发这三回调，该路径测试零覆盖。
2. **T3-2 使 `ai:events` 不再被 delta 续 TTL**（【旁证】）：`appendEvent` 的 delta 分支改走 `touchActivity`（写 `ai:beat`，`RunStore.java:543-551`），不再 `expire ai:events`。T3-2 前每个 delta 都会续期归档 TTL。因 session-ttl=6h，实际仅长流式轮次可能触及，且 seq 连续性因 `ai:seq` 单独续期而不受影响；登记观察项（S3-4 未覆盖此点）。
3. **twoArgRegistry 回归用例是弱守卫**（【旁证】）：`:360` 未先 `configureSharedDeliveryPool`，隔离运行时 `sharedDeliveryExecutorOrDirect()` 本就回落直执，断言 `isNotInstanceOf(ThreadPoolExecutor)` 对**修复前代码同样通过**——只有同 JVM 内先有 `@SpringBootTest` 装配才复现泄漏。守卫不稳健。
4. **S3-1 不变量用例无法捕获「等于 256 的第二处字面量」**（【旁证】）：`:378` 只锚定缺省值与 `< EVENT_WINDOW`；若某装配路径重新写死 `256`（等于常量），断言仍绿，证据 §7.5「任一写死字面量即红」的表述对等值字面量不成立。
5. **`completeWhenDrained` 的「CAS 抢到」两分支无定点用例**（【旁证】）：`:262/:286` 均覆盖「drain 在跑、CAS 抢不到」路径；「抢到且队空→直接收尾」「抢到且队非空→自己跑 drain」两分支缺定点覆盖（覆盖缺口，非假绿）。
6. **`EVICTED_TOTAL` 静态累计、无重置口**（【旁证】）：health 的 `evicted` 是进程累计值而非本轮值，指标语义需在文档标注（T3-5 压测口径影响）。
7. **`nextSeq` 在 `emitLock` 内做 2+ 次 Redis 往返**（【旁证】）：每业务帧锁内 `lastEventSeq`/`lastIssuedSeq`（首帧）读 + `recordIssuedSeq` SET + `appendEvent` rightPush/trim/expire，Redis 慢时放大整轮出帧延迟（含心跳 touchActivity）；属设计既定「发放即 SET」，登记为延迟观察项。
8. **`stageReplay`/`emit` 序列化失败 → 部分投递/提前 return**（【推断】理论性）：归档帧均为已反序列化的 Map，重序列化失败概率≈0，defensive break 无实际风险；登记留痕。

---

## 3. 总评

**不通过（1×S1 阻塞）。**

理由：修复批本身质量高且已被实测闭环——S1-1 终态排空（probe A/B/D 零丢失）、S2-1 两参直执（probe C 恒直执）、T3-2 seq 持久化（SeqPersistenceTest + 全量 277 绿）均正确。但本批的核心机制目标 **T3-1 R1 双口径不变量在实现上被击破**：回放 backlog 与实时帧共用一条队列、实时容量闸按绝对长度判定，导致「慢重挂者回放排空期」被任一实时帧（挂起期心跳 2s 一次）确定性摘除，断点续收重挂陷入饥饿——被攻击的恰恰是 T3-1 声明要隔离保护的慢订阅者。这是设计裁决与实现的实质背离，属阻塞级，须在 T3-5 压测前修正并补「回放排空期混入实时
