# 多实例、线程、Redis Cluster、轮询与阻塞 API 全面审查

- 审查基线：`52b09364`
- 审查对象：`backend/src/main`、`frontend/src` 全部 473 个生产文件（后端 Java 350、后端资源 31、前端 92）及其直接运行依赖。
- 工作方式：K3.1 Pro 分 16 个领域首轮审查，再做删除慢链独立复核、全量覆盖复核、统一修复计划与四组缺口补审。
- 边界：本轮只读生产代码并写报告；未修改生产/测试代码，未运行测试、构建、CI、服务、压测或真实 Redis Cluster 验证。

## 1. 总结论

当前代码不能仅凭静态审查被描述为“已经满足所有多实例、多线程和 Redis Cluster 场景”。核心链路已有不少正确设计，例如 run 多键同槽 Lua、会话/用户身份隔离、租约与 fence、帧归档重挂、有界线程池、前端页签会话隔离；但仍存在明确的正确性缺口、无界工作量、跨实例重复执行和同步阻塞链。

最重要结论如下：

1. **删除对话慢是多条同步成本叠加，不是一条 DEL 或一个超时配置的问题。** 每次首次 DELETE 和普通重试都包含 staging、legacy archive 两趟完整键空间 SCAN；Cluster 下逐 master、逐游标串行，之后还有 run、活跃取消、上下文、附件、DB 和文件清理。
2. **代码能证明存在超过 30 秒的结构条件，但不能证明某次事故各阶段的实际占比。** 单命令 2 秒不是接口总预算，局部 1.5 秒也不覆盖活跃取消与后续清理；必须补分阶段计时才能确定事故主贡献。
3. **单纯改 202、加线程或把同一套删除逻辑搬到后台不是根治。** 必须同时有持久 pending/guard、会话级执行租约与 epoch、精确发现索引、有界分批执行和最终完成验收。
4. **Redis Cluster 主体兼容设计不能笼统推翻。** 覆盖复核未发现生产全库 `KEYS`、阻塞 BLPOP/BRPOP/XREAD BLOCK 或新的核心 CROSSSLOT；54 个脚本主体已逐文件盘点。真正的问题集中在全键空间 SCAN、串行 RTT、无总预算、部分原子性缺口和后台放大。
5. **除性能外还有必须独立处理的正确性问题。** 包括租约释放后旧写者可能回 plain 写、帧 fence 与落档非原子、决策 WAL 不完整、取消整写覆盖、Stream 扫描把条数预算当 seq 差、作业终态/取消不可靠、GLOBAL 空值唯一约束及导入文件快照未固定。
6. **全部轮询不能一律删除。** 权威状态兜底、延迟重试、TTL/恢复扫描有存在必要；需要消除的是无界、重复、无单飞、无退避、后台页签放大和可用事件替代的高频盲轮询。

## 2. 删除对话慢链：最终复核结论

完整入口到时序、命令清单和证据见 [17-delete-rootcause-review.md](17-delete-rootcause-review.md)。

### 2.1 代码证实的主链

```text
新建对话前端
  → 立即清屏并准备新会话
  → 旧 run cancel → 旧会话 DELETE
  → DELETE 同步 PING、登记/复用 pending
  → 枚举 run 与快照/pending
  → staging 全键空间 SCAN
  → legacy archive 全键空间 SCAN
  → 安装 guard、删除反馈 DB
  → 活跃 run 同步取消收尾与局部等待
  → 记忆、transcript、run、上下文、会话、附件逐类清理
  → pending 收尾/退避与 60 秒记忆复核
```

活跃 run 的取消在一次完整收尾中最多进行三次终帧归档探测；每次是有帧数预算但无墙钟预算的 Stream 分页，不应与键空间 SCAN 混为一谈。HTTP 与自动 runner 还缺少同会话执行单飞，可能重复承担整套 SCAN、DB、文件和 Redis 工作。

### 2.2 推荐修复路线

- **先止血**：分阶段观测；严格 gate/current 读取；同会话跨实例执行 lease + epoch；共享阶段预算；前端 DELETE 固定旧身份并按旧会话单飞；补 xtrim/附件索引清理；修租约释放与 Stream 跳号两个独立正确性问题。
- **再减少固定成本**：按 slot 有界 pipeline、单键 TYPE+DEL Lua、复用合法快照和附件元数据；活跃 run 未立即终态时持久 deferred，而非在 HTTP 中完整等待。
- **根治发现面**：staging 写入/CAS/清理时维护同槽精确索引；legacy archive 以有界、可恢复的后台清账退出请求热路径。不能截断 SCAN 后伪报完成，也不能让旧数据失去发现面。
- **最后调整接口契约**：只有持久受理、跨实例接续、状态语义与告警都成立后，才考虑 `202 + purgeId + 状态查询`。接口异步化是外层结果，不是性能修复本身。

## 3. 统一问题与最佳修复方向

详细 P0/P1/P2/P3 台账、依赖和取舍见 [19-remediation-plan.md](19-remediation-plan.md)。以下为必须优先理解的主题。

| 主题 | 代表问题 | 最佳修复方向 |
|---|---|---|
| 多实例运行所有权 | 租约释放失败后可能回 plain 写；purge 无记录级单飞；job runner 撤销主要是本地事实 | 所有状态推进带 owner/epoch；旧执行体失去推进权；DB/Redis 保留权威取消与终态事实 |
| 帧与决策原子性 | fence 检查和帧 Lua 分离；decided 与 pending 分步；取消可覆盖并发决策 | 同 run hash tag 下把权限、状态和条目写入纳入原子 Lua；保留完整恢复载荷 |
| Redis 性能 | 两趟全键空间 SCAN、run/恢复 N+1、每帧同步 wake、history 全量读后分页、KB 逐篇串行 | 精确索引、规范读模型、同跳事实复用、有界批处理；先减少总工作，再考虑并行 |
| 多线程与阻塞 | 任务 SSE 串行写、共享 socket 写池、配置 PING 锁内 I/O、resume 受理前做重建/工具/摘要 | single-flight、按订阅者有界队列、受理与执行分离；不能只扩线程池 |
| 上下文传播 | 主链租户/用户传播已有防护，但中断、延迟重试 locale、部分裸线程 MDC 仍有边界 | 保持记录身份作用域；补齐中断取消与 locale 持久恢复；不重写已有身份主链 |
| 资源生命周期 | ZIP/Excel/下载全量入堆、附件磁盘无 durable 回收、Pub/Sub 半初始化、本地 ended/creq 增长 | 先硬上限与持久回收台账，再流式化；启动失败完整销毁；容量回收不得破坏 fence |
| 业务正确性 | 作业终态失败压制重试、取消传播不完整、GLOBAL NULL 绕过唯一键、预检后换文件 | 短事务与持久补偿、共享取消版本、非空规范化、不可变文件快照 |
| AI 工具与宿主交棒 | 列表先全量后 limit、实时视图再查一次、预检 N+1、同次 handoff 重读任务/台账 | 分页标量投影、一次结构化快照供文本与视图、单趟预检共享事实、HandoffFacts |
| 前端表格与轮询 | 选区校验仍扫整表、聚合保存全组值、同步计算不可被 Promise.race 中断、 watcher/页签放大 | 限定扫描范围、流式聚合、批间让出与协作取消、按文档复用连接并正确恢复 |

补审的具体调用链和修复细节见 [20-coverage-supplement.md](20-coverage-supplement.md)。

## 4. 轮询与定时机制审查结论

- 后端没有实际业务 `@Scheduled`/`@EnableScheduling`，但存在多组自建 scheduler、fixed-delay、阻塞等待和定时重试；已全部进入 [09-backend-polling.md](09-backend-polling.md)。
- 多实例 runner 的主要风险不是所有任务都缺锁，而是**全局锁、记录级锁、执行 epoch 和 durable 接续没有统一**；部分后台扫描无界，实例增加会放大 Redis/DB 压力。
- 前端定时器、局部轮询、SSE 重连、作业 watcher、健康与任务刷新已全部进入 [12-frontend-polling.md](12-frontend-polling.md)。
- 推荐保留低频权威对账；优先修复健康安静作业误降轮询、发布 watcher 单错停止、导出终态被 issues 失败阻断、隐藏页签重连与 dispose 不取消快照等问题。

## 5. 推荐施工顺序

1. **B0：观测与不变量**：删除各阶段耗时、SCAN 页数/节点数、run/archive/staging/attachment 数量、命令数与执行重叠；冻结身份、防复活、发现键最后删、附件先文件后登记等不变量。
2. **B1：最小止血**：purge 单飞/epoch、共享预算、严格发现读、前端旧身份与防重、少量键面补漏、租约释放和 Stream 跳号修正、探测响应限量。
3. **B2：删除发现面根治**：staging 精确索引、legacy archive 有界清账、活跃 run deferred、按 slot pipeline 与可恢复分批执行。
4. **B3：核心正确性小包**：run/decision/frame、job 终态与取消、文件资源硬上限、GLOBAL 唯一性、不可变导入快照；每包独立评审，不做大一统重写。
5. **B4：高放大接口与 KB**：恢复索引、history 规范读模型、摘要总预算、配置 single-flight、KB 发布协议、任务 SSE、AI 工具/预检/handoff 数据访问。
6. **B5：长期容量与生命周期**：本地/Redis 字段安全回收、停机相位、readiness、前端连接复用与表格响应性；由测量结果决定具体数值和是否引入 Worker。

不应采用的方向：KEYS 替代 SCAN、无界 master 并行、只调大超时、跨槽 MGET/DEL/Lua、删除活跃 run 换速度、提前删 pending/guard/发现键、把 Pub/Sub 当权威、对所有接口一刀切异步化。

## 6. 报告导航

| 文件 | 内容 |
|---|---|
| [01-session-delete-cluster.md](01-session-delete-cluster.md) | 删除对话 Cluster 慢链首审 |
| [02-runstore-resume-cluster.md](02-runstore-resume-cluster.md) | RunStore、Resume、租约与 pending |
| [03-memory-transcript.md](03-memory-transcript.md) | 记忆、展示历史、压缩与摘要 |
| [04-frame-sse-redis.md](04-frame-sse-redis.md) | 帧 Stream、唤醒、重挂与 SSE |
| [05-kb-redis-cluster.md](05-kb-redis-cluster.md) | KB 缓存、代次、回源与 Lucene |
| [06-other-redis.md](06-other-redis.md) | 配置、附件、观测、任务/作业等其余 Redis |
| [07-multi-instance-runners.md](07-multi-instance-runners.md) | 多实例后台 runner 与调度 |
| [08-thread-context.md](08-thread-context.md) | 线程池、异步与请求上下文传播 |
| [09-backend-polling.md](09-backend-polling.md) | 后端全部轮询、等待与定时重试 |
| [10-ai-blocking-api.md](10-ai-blocking-api.md) | AI 全部 Controller 阻塞面 |
| [11-business-blocking-api.md](11-business-blocking-api.md) | 业务与通用 Controller 阻塞面 |
| [12-frontend-polling.md](12-frontend-polling.md) | 前端全部轮询、定时器与重连 |
| [13-frontend-blocking.md](13-frontend-blocking.md) | 前端请求等待、身份、防重与交互 |
| [14-resource-lifecycle.md](14-resource-lifecycle.md) | 内存、句柄、监听器与资源生命周期 |
| [15-redis-config-topology.md](15-redis-config-topology.md) | Redis/Lettuce 配置、拓扑、脚本与键形 |
| [16-business-runtime.md](16-business-runtime.md) | 业务事务、作业、文件与运行时 |
| [17-delete-rootcause-review.md](17-delete-rootcause-review.md) | 删除慢链独立终审与目标架构 |
| [18-coverage-gap.md](18-coverage-gap.md) | 473 个生产文件覆盖复核与缺口 |
| [19-remediation-plan.md](19-remediation-plan.md) | 统一 P0–P3 台账与施工计划 |
| [20-coverage-supplement.md](20-coverage-supplement.md) | AI 工具、预检、handoff 与表格内核补审 |

## 7. 证据边界

本轮结论属于静态源码审查与修复设计，不是运行验收。真实事故阶段耗时、Redis 节点/页数、SQL 数与执行计划、线程/socket 实际停止、浏览器长任务、堆峰值和修复后性能均待按各报告的验证清单执行。任何后续施工都应同时验收响应时间、最终清理完成时间、多实例/Cluster 正确性与资源结局，不能只看接口是否提前返回。
