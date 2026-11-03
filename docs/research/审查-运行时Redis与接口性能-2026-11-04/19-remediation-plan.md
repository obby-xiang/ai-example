# 运行时 Redis 与接口性能：统一整改计划

- 基线：`52b09364`
- 输入：本目录 `01-...` 至 `16-...` 的 16 份首轮报告；本计划已通读，并对高危项、主要重复项及修复约束回读生产源码/配置。
- 本文件性质：终审后的统一问题台账与施工排序，不是运行验收报告。
- 本次仅写入本文件；未修改生产/测试代码，未运行测试、构建、CI、服务、压测或 Git 写操作。

## 1. 终审结论

1. **删除对话慢不是单一命令问题。** 每次首次 DELETE 都会依次做 staging、legacy archive 两趟完整键空间 SCAN；Cluster 下逐 master、逐游标串行。之后还有 run、上下文、附件、DB、文件及活跃 run 收尾，整请求没有共享总预算。代码能证明工作量与串行结构，不能证明现网每次或某次请求确实超过 30 秒；实际贡献排序必须靠分阶段计时。
2. **第一批不应直接重写删除协议。** 最小止血应是：同会话执行单飞、严格发现读、阶段预算与失败短路、前端旧身份固定和重复调用收敛、必要的小范围键面补漏及观测。保留 pending/guard/发现键，不能把 SCAN 截断后伪报 `complete=true`。
3. **根治 SCAN 需要写路径与存量迁移。** staging 要在写/CAS/清理路径维护精确会话索引；archive 当前生产提交已不写新 archive，SCAN 主要承担旧键发现，应以一次性/有界后台清账退出请求热路径，而不是为 legacy archive 新建一套长期双写复杂度。
4. **正确性优先于性能美化。** 租约释放回退 plain 写、帧 fence 与落档分离、决策 WAL 不完整、取消整写覆盖、终帧扫描把条数预算当 seq 差、任务取消/终态不可靠、GLOBAL 空值唯一键、预检文件未固定等，均不能因“发生频率未知”而无限后移。
5. **不得把“异步”“加线程”“加锁 TTL”“pipeline”“Pub/Sub”当万能修复。** 它们分别不能替代持久义务、减少总命令量、提供 fencing、减少服务端工作量或提供可靠权威状态。

### 证据口径

- **代码事实**：下述调用顺序、命令形态、锁/事务边界、资源上限或竞态窗口可由源码直接确认。
- **静态推导**：在指定数据量、慢 I/O、并发或故障交错下可能出现的正确性/时延/容量后果。
- **待实测**：真实毫秒数、QPS、P99、堆峰值、Redis/DB 执行计划、框架取消或 socket 中断效果；本计划未执行这些验证。

## 2. 优先级与批次定义

- **P0**：删除主事故面、明确的数据/身份/执行正确性缺口、可造成失控资源占用的入口；优先做小而确定的修复。
- **P1**：高概率长尾、跨实例一致性、重要可用性或持续资源放大；需要一定协议/数据模型改造。
- **P2**：有明确边界或部署前提的容量、生命周期和性能问题；先做局部修复或先测量。
- **P3**：低频、默认有缓解、主要是运维/可观测性/代码卫生；不进入首轮大改。

施工批次不是“一批改完全部 P0”：

- **B0：测量基线与不变量先行**（只增加观测和设计约束，不宣称优化完成）。
- **B1：最小可行止血包**（删除单飞/严格读/预算、前端旧身份与防重、少数独立正确性小修）。
- **B2：删除发现面根治**（staging 索引、legacy archive 清账、有界批处理与活跃 run deferred）。
- **B3：核心写协议正确性**（run/decision/frame、job 终态/取消、文件与业务唯一性，按独立小包拆分）。
- **B4：高放大接口与 KB**（恢复索引、history、摘要、KB、任务/SSE）。
- **B5：长期容量与生命周期**（本地/Redis 字段回收、停机、readiness、前端连接复用等）。

依赖关系必须遵守：B2 依赖 B0/B1 的 durable pending 和阶段观测；`ended` 回收依赖 P0 租约/fence 修复；history 规范读模型应同时承载 baseline 与终态单调性；job 判死、取消和终态应共用 fencing/幂等终态设计；KB 发布协议先于增量 Lucene 等更大改造。

---

## 3. P0 统一台账

### P0-01｜DELETE 两趟全键空间 SCAN

- **合并来源**：SDC-01、MT-04、P-01、API-BLK-001、RC15-01、MR-03 的删除关联部分。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:631-640,660-665,739-742,778-795,817-828` 每次枚举分别扫描 archive 与 staging，均 `maxKeys=0`；`backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java:51-55,83-95,98-147` 会排完游标，Cluster 逐 master 串行且失败上抛。`COUNT=256` 不是总量上限。
- **静态推导/待实测**：复杂度随全库而非本会话键数增长；实际是否主导 30 秒必须测每 master 页数、游标时长和键空间。
- **最佳修复**：B2。staging 在同槽写入 Lua 内维护会话成员索引，并在 RENAME/CAS/清理时摘成员；删除只读精确集合。legacy archive 用可恢复游标的一次性/低频后台清账，完成前保留兼容发现义务，完成后从请求链路退出 SCAN。
- **取舍/影响**：索引增加写路径复杂度，必须保证“数据成功而索引未写”不会成为永久不可发现状态；不能仅增大 COUNT、用 KEYS、并行所有 master 或截断 SCAN 后报完成。
- **依赖/批次**：B0 指标 → B1 保持 fail-closed → B2 双读/迁移与旧键清理。

### P0-02｜DELETE 无端到端预算，串行清理与活跃取消占据 HTTP 线程

- **合并来源**：SDC-02/03/05/06、R2-01、P-02、API-BLK-001、OR-04、RC15-02。
- **代码事实**：控制器同步等待服务，见 `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`；主链依次取消、等待、重读并删除各数据面，见 `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:461-540,845-980,1191-1494`。1.5 秒仅约束轮询循环入口，圈内的多次同步 GET 和其它阶段不在其中；2 秒配置只是单命令预算。
- **静态推导/待实测**：run/archive/attachment 数量、RTT、DB/共享盘和部分节点超时可线性叠加；具体阶段占比待实测。
- **最佳修复**：B1 先建立共享单调 deadline、每阶段新工作预算和设施故障短路；超预算保留 durable pending/guard/发现键并返回未完成，禁止继续逐键等待。B2 再做按 slot 有界 pipeline、候选分页和活跃 run 持久 deferred。
- **取舍/影响**：同步 Redis 调用已开始后不能承诺硬实时取消；deadline 是“不再发新工作”的上界，不是强杀在飞 I/O。若改 202，必须同时提供进度查询，不可只把同一重链搬到后台。
- **依赖/批次**：B1；注意当前新建 pending 在枚举失败时有回滚逻辑（同文件 `:375-387`），预算接续不得制造“返回后台继续但台账已删”的假受理。

### P0-03｜同会话 purge 没有记录级执行互斥与停滞 fencing

- **合并来源**：SDC-04、MR-07/08、RC15-03、FE-03 的服务端放大面。
- **代码事实**：已有 pending 会复用 purgeId 并重置 nextAt，但 HTTP 仍执行完整主体，见 `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:279-340`、`AiSessionPurgeService.java:350-418,429-458`。runner 全局锁只约束自动轮次；候选卡死后原调用仍可继续，见 `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:198-236,253-327,360-379`。
- **静态推导/待实测**：重复 HTTP、HTTP/runner 和跨实例接管可重复 SCAN、DB、文件及 Redis 工作；实际频率待观测。
- **最佳修复**：B1 在 pending 增加 execution token、leaseUntil、续租和 epoch；HTTP 与 runner 共用它，重复请求只读/短等状态。状态推进必须带 epoch，旧执行体不得改写新执行体结果。
- **取舍/影响**：不能复用全局 runner 锁或会话门；不能只做 JVM 锁；不可中断文件 I/O 仍需允许接管，但旧执行体不能继续推进台账。
- **依赖/批次**：B1，与 P0-02 的 durable 接续同一状态机。

### P0-04｜gate/current 发现读失败被当成不存在

- **合并来源**：SDC-08。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:494-505` 与 `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2426-2437` 将 GET 异常折成 null；`AiSessionPurgeService.java:566-592` 只将这两个补充发现口当可空值。
- **静态推导**：若 run 仅由 gate/current 点名，故障交错可漏取消/漏删并误报完成；需要该数据前置条件，不能声称任意故障都会漏删。
- **最佳修复**：B1 增加 strict 读取口；真正不存在才返回 null，异常使枚举在删除前整体失败。保留 sessionRuns 现有 fail-closed 和发现键最后删。
- **取舍/影响**：极小协议内改动；不得回退全局 `ai:runs` 扫描来补偿。

### P0-05｜租约条件释放失败后旧写者可回到 plain 写

- **合并来源**：R2-02；并关联 R2-07、TC-10、RL-07 的墓碑依赖。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:744-767` 在 release 返回 0 时仍摘掉本地 held，却不写 LOST/UNAVAILABLE；`:839-855` 随后可回 NEVER_HELD；`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1432-1456,1480-1496` 在 epoch=0 时可走 plain 分支。
- **静态推导**：租约已过期/被接管时，旧 owner 后续可能绕过 fence；实际交错待确定性故障验证。
- **最佳修复**：B1 的独立正确性小包。run 租约 `released==0/null` 时保留/写 ended 为 LOST 或至少 UNAVAILABLE；曾持带 fence 租约的 run 未成功重新 acquire 前不得 plain 写。
- **取舍/影响**：主要影响 LeaseService/RunStore 写门；不能只清 ended 做容量修复，后续容量治理由 P1-19 承接。

### P0-06｜帧 fence 检查与原子落档分离

- **合并来源**：FR-01。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3374-3402,3417-3455` 先检查权限再进帧 Lua；`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:265-394` 的原子脚本只有 seq/xevents/xack/xtrim，没有 fence/期望 epoch。
- **静态推导**：旧实例通过检查后，新实例可推进 fence，旧帧随后仍落档并交付。
- **最佳修复**：B3。把 fence key 与 expected epoch 纳入同槽 APPEND Lua，在取号前拒绝 STALE_FENCE；拒绝对应不归档、不扇出、不唤醒。
- **取舍/影响**：可省独立 fence GET，但要处理无租约 legacy 写口和终帧；不能用进程内 emitLock 或单纯分布式锁替代 fencing。

### P0-07｜决策 guard 与 pending 非原子，部分结论无恢复载荷

- **合并来源**：R2-03。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:1810-1860,1981-2026` 先写 decided 再写 pending；APPROVED/FRONTEND_RESULT 不从残缺 guard 重建。`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3367-3397,3522-3535` 证实读侧限制。
- **静态推导**：写者死在两键之间后，重复决策撞 DUPLICATE，续跑仍见 PENDING，形成悬轮。
- **最佳修复**：B3 用同 run tag 的单条 Lua 原子完成原状态检查、decided HSETNX、pending HSET 与 TTL；传入完整条目，尤其是前端结果正文。
- **取舍/影响**：保留唯一回执帧语义；仅扩大 guard 字符串却仍分两步，只是次优补偿。

### P0-08｜取消 pendings 的读改写可覆盖并发决策/执行证据

- **合并来源**：R2-04。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4274-4288` 对旧副本直接 putPending；`:4331-4341` 对 PENDING 不检查 claim；`:3340-3348` 是无条件 HSET。
- **静态推导**：取消与批准/回灌或工具 claim 交错时，可丢决策或把已认领工具写成未执行取消。
- **最佳修复**：B3 与 P0-07 共用同槽条件结算设施：读取当前 JSON、检查 claim/原状态后原子取消；执行完成回写也带 claim epoch/状态条件。
- **取舍/影响**：不能只在 Java 里再读一次 claim 缩窗；需保持台账、条目和终帧事实一致。

### P0-09｜终帧/工具扫描把物理条数预算误作 seq 区间

- **合并来源**：FR-05。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:2323-2408,2411-2421` 以 `lastSeq-ARCHIVE_SCAN_BUDGET` 为起点；帧接口允许跳号。
- **静态推导**：少量幸存记录但序号大跳时，可把实际存在的终帧/tool_start 误判为不存在。
- **最佳修复**：B1 独立正确性小包。按 Stream 物理 entry ID 反向分页，下一页使用上一页排他 entry ID；扫描数量由物理条目数约束。
- **取舍/影响**：从 0 全扫虽简单但放大删除；扩大减法常数不是正确修复。与 P1-02 的 xterminal 可后续协同，但先修逻辑错误。

### P0-10｜模型探测响应先完整入堆，超时又不取消 future

- **合并来源**：TC-03/04、P-10、RL-10、API-BLK-010。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:143-180,205-224` 使用 `BodyHandlers.ofByteArray()`，`future.get` 后截断；Timeout/Interrupted 分支没有 cancel。
- **静态推导/待实测**：超大/chunked 响应可造成堆峰值，重复慢探测可叠加在飞工作；JDK cancel 的实际网络停止效果待测。
- **最佳修复**：B1/B3 小包。使用受限 BodySubscriber 或 ofInputStream，在读取中超过 1MiB 立即拒绝并关闭；超时/中断 cancel future，保留中断位。
- **取舍/影响**：Content-Length 只能预检；用户主动 `/test` 的同步 5 秒结果应保留，不必异步化。

### P0-11｜job 终态失败被吞并永久压判重，嵌套事务又放大部分提交

- **合并来源**：MR-01、BR-02/06、OR-18 的终态关联。
- **代码事实**：`backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:214-217,328-399` 在终态前加入 judgedJobIds；`backend/src/main/java/com/example/configmgr/job/service/JobTerminalWriter.java:79-145` 在 afterCommit/REQUIRES_NEW 中工作并吞异常，job 可先独立终态而 task 联动失败。
- **静态推导**：暂时 DB 故障可让在途作业不再被本实例重试；多连接与部分提交可叠加。
- **最佳修复**：B3。终态 API 返回 WROTE/ALREADY_TERMINAL/RETRYABLE_FAILURE；失败不得进入永久 judged。将 job、job_items、task 纳入一个短事务并统一 task→job 锁顺序；若不能原子，必须有持久补偿而非日志。
- **取舍/影响**：不要只放大 Hikari 或仅在 catch 删除 judged；issue 去重与终态重试分开。

### P0-12｜跨实例 job 取消与 runner 撤销不是可靠共享事实

- **合并来源**：OR-09/12/14、BBA-10、BR-07。
- **代码事实**：DB 取消提交后 Redis 写失败仍返回既有成功响应，见 `backend/src/main/java/com/example/configmgr/job/service/JobService.java:489-560`；`backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:42-43,82-105,157-179` 读失败按未取消且 TTL 固定 1 小时。`backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:335-345,397-404` 的 revoked 只在本地生效。
- **静态推导**：远端 runner 可继续副作用或重建已判死作业的共享键；EXPORT 的命中数驱动检查还形成长盲区（`backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:179-225`）。
- **最佳修复**：B3。DB 持久化 cancel_requested/version 或 outbox，有界重试 Redis；执行边界核 DB 兜底，响应区分 accepted/propagated/stopped。runner 建立共享 generation/revoked fence，所有 beat/end 条件写删；EXPORT 按 rowsSeen 与单调时间间隔检查。
- **取舍/影响**：Redis/PubSub 只是加速；不能因 DB 已 CANCELLED 就宣称线程已停止，也不能只缩短 TTL 或增加 GET 频率。

### P0-13｜ZIP/Excel/下载存在无界或多层全量内存物化

- **合并来源**：BBA-03/07/08、BR-03、RL-03/05。
- **代码事实**：`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:63-97,178-196` 在 task 锁/事务内把 ZIP 全部 entry 读入 List；`:201-218,273-355,358-391` 单文件/ZIP/模板均返回完整 byte[]。5MB multipart 不限制解压后总量。
- **静态推导/待实测**：高压缩比、大 Excel、多并发可造成长 GC/OOM 和 DB 锁长占用；峰值取决于实际数据。
- **最佳修复**：B3 先加权威硬上限：entry 数、单件/总解压字节、压缩比、Excel 行列、下载总字节与 codes 数；解压/文件 I/O 移出 task 锁。随后流式上传、POI SAX/游标、StreamingResponseBody 和 ZIP 边读边写。
- **取舍/影响**：先上限会拒绝超大合法输入，但可快速封堵资源事故；只改异步 byte[] 不减少堆。

### P0-14｜GLOBAL 的可空 scope_key 绕过业务唯一约束

- **合并来源**：BR-04。
- **代码事实**：`backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:119-132,173-185` 可写 null 并先查后插；`backend/src/main/resources/db/postgresql/V1__schema.sql:71-83` 的唯一键包含可空 scope_key。
- **静态推导**：H2/PG 的普通 UNIQUE 允许多个 NULL，并发新增同 GLOBAL 业务键可产生重复行。
- **最佳修复**：B3。盘点/合并存量重复后，将 GLOBAL 规范化为不与真实编码冲突的非空哨兵，回填 NOT NULL；所有读写/导入/发布统一规范化，保留唯一冲突兜底。
- **取舍/影响**：需 H2/PG 两套迁移；不能只靠 Redis 锁或 JPA @Version。

### P0-15｜预检通过与实际导入没有绑定同一不可变文件快照

- **合并来源**：BR-05。
- **代码事实**：`backend/src/main/java/com/example/configmgr/job/service/JobService.java:398-431` 建作业时按当时路径重算摘要；`backend/src/main/java/com/example/configmgr/job/service/ImportJobRunner.java:123-131` 执行时重新读取当前 task_files 路径。
- **静态推导**：两阶段之间换件可导入未经该次预检的内容。
- **最佳修复**：B3。作业创建持久化 defCode→storageKey/sha256/TaskFile 版本，runner 只读该快照；旧文件按在途作业引用保留并由有界回收器清理。
- **取舍/影响**：可选择“在途禁止换件”作短期方案，但不可用长 task 锁覆盖整个导入。

### P0-16｜旧会话 DELETE 未固定动作入口身份

- **合并来源**：FE-01。
- **代码事实**：`frontend/src/stores/ai.ts:11266-11305` 捕获 purgeIdentity，cancel 显式使用它，DELETE 却未传；后端按请求身份定位销毁范围。
- **静态推导**：等待发号/cancel 期间切换身份后，旧 A 会话 DELETE 可带 B 身份；可能漏删或被拒，但不能据此声称会误删 B 的同号会话。
- **最佳修复**：B1。`deleteHistory` 接收 Axios 配置，reset 传旧身份头；所有代表旧现场的写请求统一固定 sid/runId/identity。
- **取舍/影响**：前端局部改动，不新增请求体身份双事实源。

---

## 4. P1 统一台账

### P1-01｜销毁键面遗漏 xtrim/xack 与 stale 附件会话索引

- **合并来源**：R2-05、FR-10、OR-05、RC15-08。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2886-2908` 的 13 键 DEL 不含 xtrim/xack；`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:217-235` 证实 xack 为动态 token 键、120 秒 TTL。`backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:564-593,685-699` 只对 live 登记 SREM，销毁服务没有精确 DEL 整个会话附件索引。
- **最佳修复**：B1 先把 xtrim 加同槽 DEL/续期登记，主体成功后精确 DEL 附件会话索引。B2/B3 将 xack 改每 run 固定 HASH（token 为 field），随 run 删除；迁移期兼容旧短 TTL 键。
- **取舍**：xack 可暂明确为 120 秒延迟清理例外，但不能为清它新增 SCAN；这些键主要是元数据，不夸大为帧正文泄漏。

### P1-02｜活跃 run 取消在一次 DELETE 内重复扫描归档

- **合并来源**：SDC-05、FR-04。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2323-2406` 收尾前后各探一次，中间 `cancelParked` 又在 `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3389-3521` 探测；每页 256、单轮扫描预算 6256。
- **最佳修复**：B2 先合并三层重复判定并传递已知结果；随后在原子帧 Lua 中维护同槽 xterminal（终帧 seq/type），老流才兼容扫描并回填。HTTP 对未立即终态者持久 deferred。
- **取舍**：XREVRANGE 只能加速“终帧在表尾”，证明不存在仍需完整扫描；不能为降延迟删除仍活跃 run 的键。

### P1-03｜resume 与手工恢复扫描在 HTTP 受理前做重建/工具/摘要

- **合并来源**：API-BLK-002。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1102-1435,1621-1820` 在最终提交执行器前同步准备；手工扫描逐轮调用 resume，见 `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1213-1242`。
- **最佳修复**：B4 将 HTTP 缩为核归属、持久续跑意图、有界投递；完整准备在有界恢复执行器执行。手工 scan 返回可追踪 scanId/deferred。
- **取舍**：202 仅表示受理；保留租约、SessionGate、取消优先与 fence，不能盲投。

### P1-04｜ai:runs 全局 SET 造成恢复/列表/创建的系统性 N+1

- **合并来源**：R2-06、MR-03、P-03、RC15-06、API-BLK-003。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3120-3151,3163-3189,3218-3288` 均 SMEMBERS 后逐成员 EXISTS；创建清理节流只是 JVM 内。恢复动作上限不限制前面的发现成本。
- **最佳修复**：B4 短期把清理移出创建，使用有界 SSCAN/游标与按 slot pipeline，只为接管候选读轻量字段；长期维护 recovery-due ZSET/窄索引，score 为下次检查时刻，保留低频全量对账。
- **取舍**：仅 pipeline 不减少 SMEMBERS/总键数；仅给动作循环分页是假治理。终态不能直接从所有诊断索引消失而不评估兼容性。

### P1-05｜帧 emitLock 内同步 I/O、每帧 wake 扇出及共享调度阻塞

- **合并来源**：FR-02/03/06、R2-09/10。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3417-3455,3996-4094,4124-4180` 在锁内归档/读差量；唤醒按上界通知后每实例读档。`backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:438-447` 的单线程 scheduler 到点后直接执行完整取消收尾。
- **最佳修复**：B4 分小步：重试 scheduler 只转投既有有界执行器；活动戳按 run 合流并移出调度线程；wake 发布/接收按 run 单飞短窗合流，终帧立即冲刷。emitLock 最终改为按 run 有序单写者异步链，不先简单把 Lua 移出锁。
- **取舍**：扩大线程池只提高阻塞容量；Pub/Sub 可降轮询/通知成本，但归档对账与权威 Redis 状态必须保留。

### P1-06｜任务 SSE 串行同步扇出与共享阻塞 socket 写池

- **合并来源**：FR-07、TC-01、BBA-09。
- **代码事实**：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:294-317,550-573` 对每个 emitter 同步 write；`backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java:337-391,403-473` 的 timeout/cancel 只释放等待方，不保证阻塞 socket 写停止；AI 与任务共享 64 线程。
- **最佳修复**：B4 任务通道改 writeAsync + 每订阅者有界 FIFO/最新状态合并；短期 AI/任务分池和连接限额降低跨通道误伤。长期用容器支持的非阻塞/async 写与真实连接关闭。
- **取舍**：不能只扩 64 或把 15 秒调小；慢连接摘除与 DB 快照兜底继续保留。

### P1-07｜L2 分块/递归摘要只有单次 60 秒超时，没有整次预算

- **合并来源**：MT-02。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/context/RollingSummarizer.java:203-245` 串行调用；`backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:144-181` 每次独立 future.get(timeout)。
- **最佳修复**：B4 在整次 compactor.plan 建绝对 deadline，传入每次摘要并使用剩余时间；限制总请求数，CAS 重试复用与输入内容绑定的分块结果。
- **取舍**：池已有 4 线程/8 队列，不是无界线程问题；异步压缩不能改变模型必须看到已提交结果的时序，future.cancel 不等于上游已停止。

### P1-08｜history 假分页与 transcript 在线 Lua 全量修复

- **合并来源**：MT-01/03、OR-16、API-BLK-005、RC15-10。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:242-269` 先 LRANGE 0 -1、全量解析/去重/记忆对账；控制器后切片。`:98-159` 在计数异常时于 Lua 内全量 LRANGE，并循环裁剪。
- **最佳修复**：B4 设计规范当前条目 HASH + 稳定 revision/游标索引，写时物化 last-wins，读一页；首屏直接请求最近页。计数异常迁移到有界后台重建，正常在线 Lua 禁止全量重算。
- **取舍**：只对 LIST 提前 LRANGE 会破坏全局 last-wins/total；现有 5000/8MiB 是容量上限，不是低延迟证明。

### P1-09｜transcript baseline 可重复物理复制，last-wins 可回退终态

- **合并来源**：MT-07/08。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:312-343` 先查 ready、append、再 SETNX；`:251-260` 仅按 append 顺序覆盖同 entryId，写前终态检查与 append 不原子。
- **最佳修复**：与 P1-08 同一读模型批次完成：规范条目按 entryId 幂等，携带 fencing/单调版本和终态优先级；若暂留 LIST，baseline 用带 TTL/owner 的初始化状态机，读侧增加终态优先兜底。
- **取舍**：不能提前置 ready 后让崩溃永久缺 baseline；只按 atMs 依赖多实例时钟，不足以表达状态优先级。

### P1-10｜上下文状态 RMW、读失败按 EMPTY 写回、压缩多步提交

- **合并来源**：MT-05/06/11。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java:551-619,674-698,740-747` 分别证实多步 commit、读改写和 EMPTY 降级。
- **最佳修复**：B4 写路径改 strict read；轮末计量用字段级 HSET/HINCRBY，熔断用单键 Lua，压缩携带 version CAS。以稳定 compactionId 幂等补写 state/ledger/event，读侧区分 pending/committed。
- **取舍**：会话大锁会串行所有 run；交换写顺序只移动失败窗口；展示读可降级，更新读不可把未知当空。

### P1-11｜AI 配置通知可永久漏失效，DB 版本又可同号

- **合并来源**：OR-01/02。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:245-255` 在 Redis 版本存在时不再核 DB；`backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java:66-99` 是 DB 读改写，Redis INCR 失败仅 WARN。
- **最佳修复**：B4 每个既有 reload interval 必检 DB 主键版本，Redis 只作提示；配置事务开始对 config.version 行 FOR UPDATE 或使用原子 UPDATE，保证单调且同号不表示不同快照。
- **取舍**：每实例增加稳定主键读，成本低于错误配置；outbox 可作增强但不是必须一次性引入。

### P1-12｜配置刷新与 Redis 探活把阻塞 I/O 放在实例级锁内

- **合并来源**：TC-02、OR-17、P-04、API-BLK-007/008、RC15-04。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:38-53,71-100` 正缓存 0 且 synchronized 内 PING；`AiConfigService.java:155-171,201-207` 在 refreshLock 内 Redis/DB。
- **最佳修复**：B4 使用原子 single-flight，让同批请求共享一次探测/刷新；配置读者优先 last-good，首次无快照才有界等待。健康结果标观测时刻，负缓存保留。
- **取舍**：100–250ms 正缓存只可作为测量后的可选压突发窗口，不能默认恢复长缓存；完全去掉锁会造成 PING/DB 风暴。

### P1-13｜KB reload 与缓存同步无总量/总时间预算

- **合并来源**：KB-01/06/08/09、API-BLK-006、MR-13 的工作量面。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:287-387,409-440` synchronized 内逐文档和全量 Lucene；`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:261-319` 逐篇读取完整 content；`HttpKbSource.java:86-110,142-156` 同步 ofByteArray。
- **最佳修复**：B4 reload 改 202 单飞任务，任务有整轮 deadline、文档数/变化字节/单文档/manifest/总语料上限；Redis 比较只读 sha/generation，分批 pipeline，索引 Lua 分批。小语料可继续全量 Lucene，先不引入增量索引。
- **取舍**：保留旧快照检索；仅调小单 HTTP 超时不解决 N 篇累计，客户端 Redis 超时也不终止服务端 Lua。

### P1-14｜KB 发布 lease、源版本与 manifest/version 不构成可靠原子协议

- **合并来源**：KB-03/05/10、MR-13。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:142-189,261-319,424-450` 的 30 秒锁不续租，generation 在源响应后取号，manifest 与通知 version 分步；local 不走锁。`KbService.java:328-337` 对 sha 不符仅 WARN。
- **最佳修复**：B4 统一为不可变 corpus version/内容寻址快照：可续租 owner lease，发布前验证 token 和源版本；通知 version 放入 manifest HASH 同一单键 Lua，fromCache 超预算必须报错。local 指定单发布者或只读缓存，sha 不符 fail-fast。
- **取舍**：generation 只代表取号先后，不证明源内容更新；只调大 TTL/只加 generation fencing 均不足。

### P1-15｜KB 缺 sha 与缓存写失败会重复回源

- **合并来源**：KB-02/07。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:108-123,462-467` 空 manifest sha 无法命中且 cacheDoc 失败后再次 delegate.fetchContent；`KbService.java:320-337` 对空 sha 不复用。
- **最佳修复**：B4 前置小包。HTTP 源契约优先强制合法 SHA-256；若必须兼容，则首次下载实算并发布有效 sha。已成功取得的正文即使缓存补写失败也直接返回。
- **取舍**：不能把空 sha 当永久命中，否则正文变更不可见。

### P1-16｜KB 共享 index-dir 可跨实例删活目录

- **合并来源**：KB-04、RL-04。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:389-407` 只排除本实例 live，其余 build-* 全删。
- **静态推导**：仅在多实例共享目录时触发；默认相对路径并不证明生产共享。
- **最佳修复**：B4 部署核查先行；每实例独立子目录，构建目录 UUID/owner，清理只处理本实例登记项。若已共享，先隔离再改代码。
- **取舍**：没有必要为本来可实例本地的 Lucene 引入复杂网络文件锁。

### P1-17｜附件上传/收口 TOCTOU 与物理孤儿没有 durable 回收

- **合并来源**：OR-03、RL-02、API-BLK-011。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormAttachmentController.java:142-213` 先枚举/限额再落盘登记，无与收口共同锁；`backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:145-188` 的 TTL 只回收 Redis 元数据，文件失败没有持久重试。
- **最佳修复**：B3/B4。上传先临时文件，随后每表单带 owner/TTL 的互斥内复核 PENDING/配额并登记，收口使用同一协议。新增 durable 文件回收台账，记录 storage key/归属/reclaimAt，到期 worker 条件删盘并有界重试。
- **取舍**：不能只扫共享目录猜孤儿；上传仍需同步返回可用 fileId，不提前伪成功。

### P1-18｜job 心跳单线程串行、看门狗全量实体扫描及判死 TOCTOU

- **合并来源**：OR-10/13/18、MR-02/04/05、P-05/06、BR-09。
- **代码事实**：`backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:397-404,492-555` 逐 job 两 SET，读取 beat→anchor 后判死不再复核，deadjudge 无续租；`backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:200-254` 每实例全量 Job/EAGER items。
- **最佳修复**：B4 先修正确性：取得判死权后、不可逆写前重读 beat 和 DB 在途；issue 用持久唯一幂等，租约续租/token 只做降重。性能侧区分 PENDING 排队与 RUNNING，心跳按 slot pipeline、整拍 deadline/失败短路；watchdog 改标量投影+keyset 分页并重做 judged 回收。
- **取舍**：不能只调换 anchor/beat 顺序作为根治，不能只加扫描 leader 而不分页；增加 `(status,id)` 索引前先 EXPLAIN。

### P1-19｜LeaseService ended 与 ai:creq 字段存在长期无界积累

- **合并来源**：R2-07/08、TC-10、RL-07/08。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:374-397` 明确 ended 无 TTL/容量；`backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:29-36,100-118` 明确只回收 DONE，CANCELLED/RUN 留在活跃会话 HASH。
- **最佳修复**：B5。先完成 P0-05/P0-06，使 fence/终态而非永久 JVM Map 承担旧写拒绝，再为 ended 设安全回收与容量告警。CANCELLED 增加时刻并按明确防重窗口回收；RUN 与 runstate/终态对账后条件 HDEL。
- **取舍**：禁止简单 TTL/LRU 清 LOST 或按年龄删活跃 RUN；硬上限饱和时 fail-closed 比静默淘汰安全。

### P1-20｜定义更新/删除与业务写没有共同版本或锁协议

- **合并来源**：BBA-11、BR-10。
- **代码事实**：`backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java:115-154,173-190` 删除做多次 check-then-act，更新无版本；其他发布/选择路径不共享定义锁。
- **最佳修复**：B4/B5。定义增加 @Version 和两套迁移；删除与所有会创建引用的路径共用 definition 状态/行锁并复核，可表达的引用补 FK/RESTRICT。
- **取舍**：只给 delete 加锁无效；历史 task_items 保留语义不能直接被普通 FK 破坏。

### P1-21｜前端作业动作可重入，发布跟踪与导出终态又可被单错中断

- **合并来源**：FE-06、FE-POLL-04/05、FE-09。
- **代码事实**：导出在 persist 后才置 starting，导入若干 start 缺入口锁；`frontend/src/stores/task.ts:500-515` 的发布 onError 立即 dispose；`frontend/src/views/ExportWizardView.vue:1513-1548` 的 issues await 阻断核心结果刷新，`frontend/src/utils/job-watch.ts:240-263` 不捕获 async onFinal。
- **最佳修复**：B4。入口同步 guard 覆盖点击→persist→create→attach，AI handler 与手点共用；发布 onError 继续有界 watcher；导出 issues 独立降级，核心结果/任务快照必须继续，watcher 增加 rejection 兜底。
- **取舍**：disabled 只是体验，不替代 store 锁；后端幂等/互斥仍是必要纵深。

### P1-22｜Redis Pub/Sub 半初始化与停机重建没有完整资源所有权

- **合并来源**：RL-01/11。
- **代码事实**：`backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:323-349,390-424` 和 `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:248-269` 启动失败均未 destroy 局部容器；任务总线还缺 closed 闸。
- **最佳修复**：B4 小包。局部引用移到 try 外，失败幂等 destroy；只有完整启动后才发布字段。任务总线增加 closed，subscribe/publish/container/stop 同状态协议。
- **取舍**：Spring 注入执行器不得被总线误关；具体每次失败创建多少连接/线程待运行核实。

---

## 5. P2 统一台账

### P2-01｜删除点操作、快照与附件的局部 RTT 优化

- **合并来源**：SDC-03/06、OR-04、RC15-02 中未被 P0-02 覆盖的实现细化。
- **代码事实**：`AiSessionPurgeService.java:958-980,1191-1234` 仍有状态读取与 RunStore 删除复核；`:1296-1363` 每键 TYPE+DEL；`:1439-1494` 逐附件文件与登记；`RedisFormAttachmentStore.java:564-593` 删除时再次 GET。
- **最佳修复**：B2。保留最终归属/活跃复核，复用枚举附件元数据；独立单键命令按 slot 有界 pipeline，单键 TYPE+DEL 可合成单键 Lua。附件路径只解析一次，文件确认后才删登记。
- **取舍**：不要删除 RunStore 独立归属防线，不使用跨槽 MGET/DEL/Lua；慢文件可后台接续，但 Future 超时不等于 I/O 停止。

### P2-02｜帧硬上限、delta 分片与 Stream 全局软预算

- **合并来源**：FR-11/12/13、RC15-11。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/FrameSizeLimiter.java:120-140,223-227` 不可截字段仍可超限，分片每轮复制余串；Stream 最大帧观察是实例本地。
- **最佳修复**：B5。不可收缩帧明确 typed error，各结构化字段在生产点限长；分片按原串 offset/length，在锁外预计算。若必须严格全局 Stream 预算，再把 maxFrameBytes 纳入同槽 Lua；否则明确软上限并加实际内存告警。
- **取舍**：不为软预算盲目增加每帧额外 Redis 往返；先测大帧实际发生面。

### P2-03｜唤醒坏页游标与重挂重复探测

- **合并来源**：FR-08/09。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4026-4094,4128-4191` 的续读只随已解析帧推进，整页坏帧可停读；重挂存在多次尾探/空读回退。
- **最佳修复**：B4/B5。WakePage 携带 lastReadEntryId/lastReadSeq，读取位置与成功解耦；短期整页不可解析可明确摘除并重挂。回放读返回带状态复合结果，正常空页不再做无意义类型化回退。
- **取舍**：坏页属于损坏/不兼容触发；不能用跳过坏帧而不推进的方式永久重复读同页。

### P2-04｜异步上下文、locale、MDC 与中断语义不完整

- **合并来源**：TC-06/07/08/09。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2258-2289` 中断返回 false，调用方可继续下一次模型尝试；SSE 写裸线程未统一 denied；延迟重试未持久 locale。
- **最佳修复**：B5。中断走统一取消收尾；共享裸线程启动即 denied，回调按记录身份并恢复旧 MDC/TTL；replyLanguage 归一化后存 run 快照/权威信封，跨实例恢复建立 locale scope。
- **取舍**：主链身份传播已有防护，不重写；TC-07 主要是可观测性，不能夸大为已串租户。

### P2-05｜后台启动/关闭相位、启动失败重试和时钟口径

- **合并来源**：MR-09/11/12、KB-12、RL-06。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:254-291,295-379` 启动裸线程未保存句柄，发现失败不属启动重试；KB stop 不与 refresh 同步，见 `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:191-210,287-367`。
- **最佳修复**：B5。runner 用统一 SmartLifecycle/phase：停止接新拍→有界等待→交 durable 租约→关共享资源；启动线程改受控 Future，发现失败有界指数重试。进程间隔用 nanoTime，DB 持久时间统一 UTC Instant。
- **取舍**：shutdownNow 不等于不可中断 I/O 已停；时区/Instant 迁移需独立评估存量数据，不夹进删除止血。

### P2-06｜readiness 自我放大、job 能力降级与共享存储证明

- **合并来源**：MR-10/15、OR-15、BBA-13/14/15。
- **代码事实**：`backend/src/main/java/com/example/configmgr/health/ReadinessProbeRunner.java:82-85,102-173` 每请求提交全部探针，取消的排队任务不主动出队；Redis required=false 与 job 硬依赖不一致。文件 guard 只验证绝对路径。
- **最佳修复**：B5。单实例 single-flight/极短快照，取消任务出队；增加 jobs/taskDeletion capability 和基础设施 503 错误码，不直接全局 required=true。多实例验收做跨节点写入—读取证明。
- **取舍**：PING 不证明全 Cluster/所有槽，readiness 也无需每次逐 master PING；共享目录是否一致属于部署证据。

### P2-07｜反馈与 job 扫描索引必须先核实执行计划

- **合并来源**：SDC-07、BR-09 的索引部分。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:89-113` 按 session+tenant+user COUNT/DELETE；现有迁移未提供对应顺序复合索引。job 按 status 全量扫描。
- **最佳修复**：B5 在 H2/PG 分别 EXPLAIN 并看锁/表量后，再决定 `(tenant_id,user_id,session_id)` 与 `jobs(status,id)`；若增加则两套迁移并评估写放大。
- **取舍**：不能仅凭 SQL 文本宣称已全表扫描，也不为很小的表预加所有索引。

### P2-08｜Cluster topology、生产 noeviction 与模型缓存 TTL 属配置硬化

- **合并来源**：RC15-05/09/12。
- **代码事实**：`backend/src/main/resources/application-cluster.yml:20-36` 只有 seeds/redirects/timeout 等，未显式 topology refresh；生产实际服务端策略不在主配置中；模型缓存非正 TTL 可走永久 SET。
- **最佳修复**：B5 先核对实际 Lettuce 版本默认行为与故障数据，再显式周期+自适应刷新、动态源和指标。部署清单核对每 master 容量/淘汰策略；models-cache-ttl 明确 >0，关闭缓存应跳过读写而非永久 SET。
- **取舍**：不把 CI 参数当生产证明；不盲目加 Redis 连接池、不将 CONFIG 权限变启动硬依赖。

### P2-09｜记忆合并 no-op、上下文/列表读放大与通用查询治理

- **合并来源**：MT-09、API-BLK-004/009、BBA-04/05/06/12、BR-11。
- **代码事实**：`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:569-582` no-op 在完整 serialize 后判断；`backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:58-79,102-117` 条件列表/count 全量解析。
- **最佳修复**：B4/B5 分离小改与结构改：no-op 先 sameWindow 后序列化；runs/current 复用 pending、touch 临近 TTL 才写；详情单次快照/台账分页。业务侧统一分页和 codes 上限，任务摘要用聚合投影，diff/依赖/REFERENCE 按工作集查询和缓存。
- **取舍**：条件精确 total 仍可能 O(N)；可先用只读游标降低堆，再决定 PG JSONB/下推，不假设 H2/PG JSON 语义相同。

### P2-10｜前端轮询、连接复用与旧请求取消

- **合并来源**：FE-POLL-01/02/03/06/07/08、FE-02/03。
- **代码事实**：`frontend/src/utils/job-watch.ts:123-139,149-208` 120 秒无帧即降级且每 watcher 独立 EventSource；dispose 不 abort 快照。`frontend/src/stores/ai.ts:11277-11305` 旧 DELETE 还等待新发号/额外 cancel。
- **最佳修复**：B1 先做旧会话销毁立即独立发起、按旧身份+sid 单飞，删除该路径重复前置 cancel；B5 服务端任务 SSE 增加心跳，文档内按 taskId 复用通道，hidden 暂停新重连/恢复，dispose abort 快照，终帧后 reader.cancel。
- **取舍**：跨页签 Web Locks 选主只在确有大量同源页签时做；hidden 暂停不是用户取消，visible 要按 lastSeq/权威快照恢复。

### P2-11｜设置页与任务 action 的请求瀑布/防重/last-wins

- **合并来源**：FE-04/05/07、FE-10、FE-POLL-09。
- **最佳修复**：B5。设置 providers/defaults 按依赖并行，health 独立；withBusy 用同 key token 防重，load 用序号 last-wins，写后只刷新受影响资源。任务创建/删除与列表刷新解耦，删除按 taskId 单飞。附件上传透传已有 AbortSignal，并按字段操作序号只准最新响应写回；健康调用共享 in-flight。
- **影响/取舍**：主要是 `AiSettingsView.vue`、`stores/task.ts`、`FormRenderer.vue` 等局部改造；不得破坏设置脏表单和任务代次保护。

### P2-12｜普通 xlsx 流、观测位与少量 TTL 写口的资源卫生

- **合并来源**：RL-09、OR-07/08、RC15-13。
- **代码事实**：`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:118` 未关闭 getInputStream；`backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java:116-170,200-207` 每次额外 EXPIRE 且 release 无 token。
- **最佳修复**：B5。try-with-resources 关上传流；claim 返回随机 token handle，release 单键 Lua 比对后 HDEL；正常 claim 去掉额外 EXPIRE。少量兼容写口的“数据后 EXPIRE”改单键 Lua，不为孤儿新增高频 SCAN。
- **取舍**：观测 Redis 故障 fail-open 是既定取舍，不因修 token 改成吞行。

---

## 6. P3 与暂不应改项

### P3-01｜默认配置已缓解的局部算法/低频调度

- **合并来源**：MT-10、MR-14、P-11/12、KB-11。
- **内容**：MemoryWindow 默认 40 条下重复后缀估算影响有限；purge 30 秒固定同拍抢锁和多实例局部重试是低频热点；KB prompt/建议全量遍历取决于语料。
- **处置**：B5 或 backlog。配置放大前再给 MemoryWindow 加后缀和/上限；purge 加小抖动和设施失败退避；KB 按快照缓存 prompt、Top-3 用固定堆。不要为它们引入全局队列/长持 leader。

### P3-02｜无当前生产热调用或明确条件性的接口

- **合并来源**：RC15-07、KB-13/14、RL-14。
- **内容**：`findConversationIds` 的 SCAN 在当前生产主代码未发现调用；KB 索引整体丢失是外部条件；固定 KB 前缀只有不同语料共用逻辑库才冲突；无 Redis 附件 fallback 只影响特殊装配。
- **处置**：不进入性能主批。若启用诊断口，增加分页/complete=false；KB 孤儿用维护态分批对账；不同 corpus 实际共库时才加 corpus-id；fallback 若不受支持则禁止生产装配，若支持再补 TTL。

### P3-03｜小型缓存与库生命周期卫生

- **合并来源**：RL-12/13。
- **内容**：SXSSFWorkbook 只 dispose 的实际残余取决于 POI；provider 告警表和浏览器 knownTaskTypes 缓增。
- **处置**：backlog。按当前 POI close/dispose 合约统一 try-with-resources；缓存随权威集合 retain/删除。不能宣称已发生 OOM 或永久句柄泄漏。

### P3-04｜前端纯 UI 秒表与主线程大数据处理

- **合并来源**：FE-POLL-10、FE-08。
- **内容**：hidden 的 1Hz 本地秒表无网络；SpreadJS/OOXML 主线程与批量 Promise.all 的卡顿程度未测。
- **处置**：秒表可共享 visible clock，但不进入服务端整改。大数据先用浏览器 trace 量最长 task/堆，再决定分片 yield 或 Web Worker，批量并发参数由测量选择，不预设 2–4 为已验证最优。

---

## 7. 明确不应修成“过度设计”的方向

以下不是否认对应局部问题，而是反对收益不足或风险更大的修法：

1. **不把现有合法同槽脚本重写为跨槽方案。** run 13 键 DEL、memory、transcript、frame APPEND 的现有同槽设计应保留；不得引入跨槽 MGET/DEL/Lua。
2. **不为删除慢加 KEYS、无界 master 并行或更大 timeout。** 也不盲目加 Lettuce 连接池；只有实测连接获取成为瓶颈才评估。
3. **不把同步链原样搬到后台。** 没有 pending checkpoint、执行 lease、进度查询和最终完成验收的“异步化”只是隐藏延迟。
4. **不为了快而截断 SCAN、删除活跃 run、提前删发现键、提前报 complete 或先删附件登记。**
5. **不对 ended 做简单 TTL/LRU，不对 CANCELLED/RUN 按年龄盲删。** 先建立 Redis fence/终态/防重窗口等安全承接。
6. **不把 Pub/Sub 当权威。** AI/job 取消、帧 wake、KB version 都必须保留 Redis/DB/归档对账；低频权威轮询不是天然应删除的浪费。
7. **不默认把 Redis readiness 改全站 required。** 优先 capability 和正确 503；否则 Redis 故障会连纯 DB 流量一起摘流。
8. **不为小语料立即做增量 Lucene、跨进程网络文件锁或全局 KB 命名空间。** 分别由语料规模、错误共享目录和真实多 corpus 共库触发。
9. **不为 park=false 旧阻塞模式新建复杂基础设施。** 默认 park=true；若必须支持，先做有界退避和共享通知，不重造一套系统。
10. **不为低流量局部重试强上全局持久重试队列。** 现有有界次数与业务 winner 已提供主要防线。
11. **保留正常同步接口。** `/test`、上传可用 fileId、反馈 Top100、选项小查询、纯内存 `/tools`/`/tenants`/KB status/docs 无需为“统一风格”异步化。
12. **不把已有保护当缺陷。** 当前没有生产全库 KEYS、BLPOP/BRPOP/XREAD BLOCK 或已证实的核心 CROSSSLOT；调度器/线程池大多有界，不能笼统重写线程模型。

## 8. 必须先实测再决定的事项

| 决策 | 先有数据 | 未测前默认动作 |
|---|---|---|
| DELETE 30 秒主贡献 | pending/enumerate/stagingScan/archiveScan/db/cancel/run/context/attachments 分阶段耗时、R/A/S/F、每 master 页数、请求来源 | 只确认结构风险，不指定唯一事故根因 |
| master 并行 SCAN | 单页耗时、Redis CPU、连接与节点排队 | 不作为默认根治；即使采用也只能很小固定并发 |
| pipeline 批次/并发 | RTT、槽分布、结果字节、Redis 压力 | 有界小批，不设未经验证的大批 |
| Redis 正缓存时长 | 慢 PING 并发、故障翻转容忍、入口 QPS | 优先 zero/极短窗口的 single-flight，不恢复长缓存 |
| AI 1Hz 取消改 Pub/Sub | 活跃轮数、GET QPS、调度排队、取消时效目标 | 默认 20 并发且可接受时效时可保留，先观测 |
| 全量 Lucene→增量 | 文档/chunk/变化比例、重建耗时、时效要求 | 小语料继续全量，先异步与上限 |
| history 新存储/DB 迁移 | 5000/8MiB 读字节、JSON CPU、分页调用频率 | 先设计规范读模型，不做只改 LRANGE 的假分页 |
| DB 新索引 | H2/PG EXPLAIN、表量、锁与写入成本 | 不凭静态 SQL 直接加索引 |
| topology refresh | 实际 Lettuce 默认、MOVED/ASK/failover 证据 | 配置缺口登记，不断言当前一定 stale |
| Stream 严格全局字节预算 | 实际槽内存、跨实例大小帧频率 | 接受软预算并监控，或再改同槽状态 |
| 前端 Worker/跨页签选主 | 浏览器 trace、页签数、长任务与网络计数 | 先做文档内复用/取消，不上重型协调 |

## 9. 最小可行第一批与后续施工包

### 9.1 B0：测量基线与验收不变量

在任何性能改造前补充分阶段观测，不输出正文、密钥或敏感连接信息：

- DELETE：阶段耗时、命令数/字节、SCAN 页数/节点数、R/A/S/F、purgeId 执行重叠、complete/deferred/errors。
- run/frame：租约结局、STALE_FENCE、归档扫描页数、emitLock 等待、每实例 wake 数。
- job：终态结果、重试、task 联动失败、心跳最老年龄、DB 锁/连接。
- 资源：堆、线程池 active/queue/reject、文件与定时器残留。

冻结不可退化不变量：身份隔离；取消不被翻案；旧 epoch 不写；已认领副作用不写成未执行；活跃 run 不提前删；删除不完整不得 complete；文件未确认删不得删登记；发现键最后删；pending/guard 可接续。

### 9.2 B1：最小可行止血包

按可独立审查的小提交落地，避免一次性重写：

1. **P0-04** strict gate/current；**P0-16** DELETE 固定旧身份。
2. **P0-03 + P0-02** pending 记录级 execution lease/epoch、共享阶段 deadline、设施失败短路；重复 DELETE 返回进行中，超预算保留 pending 并返回未完成。
3. **前端 P2-10 的最小子集**：旧会话销毁不等新发号，按旧身份+sid 单飞，移除该路径重复前置 cancel；不新增删除轮询。
4. **P1-01 小修**：xtrim 入同槽 DEL/续期，附件会话索引在主体成功后精确 DEL；xack 暂保留明确 120 秒例外，不为此 SCAN。
5. **P0-05 与 P0-09** 两个独立正确性小包：release=0 不回 plain；归档按物理 entry 反向分页。
6. **P0-10** 模型探测受限读取与 future cancel，可独立发布，和 DELETE 无耦合。

**B1 不承诺消除全 SCAN。** 它先阻止重复执行、无限新工作和错误身份，并建立后续迁移所需的可靠状态。若这批上线前仍无测量，不得宣称 DELETE 性能已达标。

### 9.3 B2：删除发现面与执行模型根治

1. staging 同槽原子索引、双读/迁移、stale 成员过滤；legacy archive 有界后台清账。
2. 删除候选分页与按 slot pipeline；复用合法快照/附件元数据；上下文单键类型校验+删除合并。
3. 活跃 run 写取消后未立即终态即 durable deferred；合并三次归档探测，随后引入 xterminal。
4. xack 每 run HASH 与旧键过渡；只有全部约定数据面完成才删发现键并排记忆复核。
5. 如保留同步快路径，到预算后必须能由 runner 接续；如改 202，增加状态查询和前端进行中语义。

### 9.4 B3：核心正确性与资源事故小包

按依赖拆成独立评审单元，不做“大一统状态机”：

- run：P0-06/07/08 原子 fence、决策和取消条件写。
- job：P0-11/12 终态结果、短事务、持久取消/outbox、runner fence；P1-18 的判死后复核先行。
- 文件/业务：P0-13/14/15 硬上限、GLOBAL 非空唯一、不可变导入快照。
- 附件：P1-17 上传/收口共同协议和 durable 文件回收。

### 9.5 B4/B5：高放大接口与长期治理

- B4：P1-03～17、P1-20～22，优先 resume/恢复索引、history/摘要、配置、KB、任务 SSE、Pub/Sub 生命周期。
- B5：P1-19 与 P2/P3，按测量和部署证据安排；不把长期容量治理反向阻塞 P0 正确性。

## 10. 后续验收门槛（本次未执行）

1. **DELETE 性能与完整性同时验收**：standalone/真实多 master Cluster、空/大会话、多 run/archive/staging/attachment、活跃 run、重复 DELETE、HTTP+runner、单 master 慢/MOVED/ASK；同时报告 HTTP 受理时间与最终完成时间。
2. **正确性故障注入**：release=0、fence 读与帧 Lua 之间接管、decision 两键之间崩溃、claim 与取消交错、跳号 Stream、DB 取消写 Redis 失败、终态 task 写失败、预检后换件。
3. **资源结局而非只看响应**：future.cancel、SSE timeout、shutdownNow 后检查真实线程/socket/文件/容器；ZIP/Excel/下载看堆和句柄，不能只看 HTTP 已返回。
4. **多实例与多身份**：同 sessionId/runId 跨身份隔离，后台按记录身份恢复；跨实例同任务只能一个 purge/runner 写胜者，旧 owner 不能推进新台账。
5. **无回归**：无 KEYS/跨槽多键命令；保留 pending/guard、发现键最后删、DB/文件 fail-closed、归档/快照/终帧一致和现有前端重挂/快照兜底。

在上述证据出现前，本计划只确认代码机制、整改方向和顺序，不宣称任何实际时延、OOM、误判或线上事故已被复现，也不宣称任何修复已验证通过。
