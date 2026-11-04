# Redis 整改台账与施工前协议验证计划

- 记录基线：`ab713297`。
- 输入：本目录 `01`～`20` 的静态审查、真实 standalone/Cluster 验证报告及报告误报复核。
- 当前状态：只记录施工范围与前置验证，不修改生产代码，不开始施工。
- 目标：后续收到“开始修改”后可直接按本台账执行；结构性性能方案先通过 Redis 协议级实测，再进入生产代码。

## 一、结论与施工原则

代码审查已经能够确认绝大多数结构性问题；实测用于确认严重程度、比较候选协议和防止错误优化，不再为已知问题重复跑整包压测。

所有实现必须让 standalone 与 Redis Cluster 走同一套生产代码，不允许按部署模式写分支：

- 多键原子操作只使用相同 hash tag 下的 Lua；批量操作使用有界单键 pipeline。
- 禁止跨槽 MGET/DEL/Lua，禁止 KEYS，禁止新增无界 SCAN。
- Redis 负责缓存、精确索引、租约和加速；权威业务事实、防复活与最终接续不能依赖 Pub/Sub 或本地内存。
- 旧数据必须有有界迁移/对账；不得因引入新索引而漏删旧键，也不得截断 SCAN 后伪报完成。

修改量口径：小＝局部实现；中＝单模块多文件；大＝涉及写协议、状态机或旧数据兼容。

## 二、合并后的 Redis 修改台账

| # | 问题与根因 | 修改方案 | 量 |
|---|---|---|---|
| 1 | 删除对话有两趟全键空间 SCAN，成本随全库而非本会话增长；Cluster 实测空会话 DELETE 也超过 30 秒 | staging 在写入/合并/清理时原子维护会话精确索引；legacy archive 以有界后台清账退出请求热路径；删除只处理精确目标 | 大 |
| 2 | DELETE 同步串行、无整链预算；HTTP 与 runner 可重复执行同一 purge | 会话级执行租约、owner token、epoch、durable pending 和共享阶段 deadline；HTTP/runner 共用单飞；主体有界分批并可接续 | 大 |
| 3 | 已有终帧但快照仍 RUNNING 时反复等待并可能永久 deferred；一次取消收尾还可能重复探测归档 | 用条件写收敛快照/runstate 与权威终帧；无法确认才 deferred；合并探测，后续以同槽 xterminal 记录终帧事实 | 中 |
| 4 | gate/current 读失败折成 null，可能漏删；xtrim/xack 与附件会话索引未完整纳入销毁 | 增加 strict 读口；xtrim 并入同槽删除；附件索引精确删除；xack 改固定 run HASH 并兼容旧短 TTL 键 | 中 |
| 5 | 删除逐键 TYPE+DEL、快照和附件元数据重复读取，RTT 随对象数线性叠加 | 保留归属复核；单键 TYPE+DEL 合并为单键 Lua；有界 pipeline；复用合法快照与附件元数据 | 中 |
| 6 | 租约条件释放失败后旧写者可能回 plain 写 | release=0/null 保持 LOST/UNAVAILABLE；曾持 fence 租约者重新 acquire 成功前禁止 plain 写 | 小 |
| 7 | 帧 fence 检查与落档 Lua 分离，旧实例可能在检查后写入 | fence key 与 expected epoch 纳入同槽 APPEND Lua；旧 epoch 在取号、归档、扇出前统一拒绝 | 中 |
| 8 | decided/pending 分步写，取消对旧 JSON 无条件 HSET，可能覆盖并发决策或执行证据 | 同槽条件 Lua 原子完成状态检查、决策、完整 pending 载荷与 TTL；取消和执行回写校验状态、claim、epoch | 大 |
| 9 | Stream 扫描把物理条数预算误作 seq 差，跳号时会漏终帧/tool_start | 按真实 Stream entry ID 物理分页，用上一页排他 ID 续读；预算只按实际条目数 | 小 |
| 10 | ai:runs 全局 SET 使恢复/列表/创建形成系统性 N+1 | 建 recovery-due ZSET/窄索引，按到期时间读轻量候选；创建路径移除全量清理，保留低频有界对账 | 大 |
| 11 | history 全量 LRANGE 后假分页；baseline 可能重复复制，last-wins 可回退终态 | 按 entryId 幂等的规范条目 HASH + 稳定游标/版本；写时物化 last-wins 和终态优先级；异常重建移到后台 | 大 |
| 12 | 上下文 RMW、更新读失败折 EMPTY、压缩 state/ledger/event 多步提交 | 更新 strict read；字段级 HSET/HINCRBY 或单键 Lua；压缩 version CAS + 稳定 compactionId 幂等补写 | 大 |
| 13 | AI 配置通知可漏失效、DB 版本可同号；配置刷新/PING 在实例锁内做阻塞 I/O | DB 主键版本为权威且原子递增；Redis 仅作提示；刷新/探活 single-flight，读者优先 last-good | 中 |
| 14 | KB 缺 sha/缓存补写失败会重复回源；lease、manifest/version 非可靠发布协议；reload 无总预算 | 不可变 corpus version；同键维护 manifest/version；lease 续租和 token 校验；reload 单飞、有界分批、有总预算；已取正文直接返回 | 大 |
| 15 | job Redis 取消/撤销/心跳/判死不是可靠跨实例协议 | DB 保存权威 cancel/version，Redis 加速；beat/end 使用共享 generation fence；判死前复核；心跳有界 pipeline 和整拍预算 | 大 |
| 16 | 每帧 wake、多实例重复读档、活动戳及 scheduler 直接收尾放大 Redis/调度压力 | 按 run 单飞和短窗合流 wake，终帧立即发送；活动戳合流；scheduler 只向既有有界执行器投递 | 中 |
| 17 | 附件上传/收口存在 TOCTOU，TTL 只回收元数据，物理孤儿无持久回收 | 每表单 owner/TTL 互斥内复核并登记；持久文件回收台账，到期条件删盘并有界重试 | 大 |
| 18 | LeaseService ended 与 ai:creq 字段可长期积累 | 先完成 fence/终态修复再回收 ended；creq 按防重窗口和 run 终态条件 HDEL；禁止盲删活跃状态 | 中 |
| 19 | Pub/Sub 半初始化、观测 release 无 token、模型缓存非正 TTL 永久驻留、拓扑刷新等配置缺口 | 启动失败幂等 destroy；token 条件 release 并去多余 EXPIRE；关闭缓存跳过读写；核对并显式配置拓扑刷新与容量策略 | 中 |

建议生产施工顺序：先做 1～9 的删除事故面与 run 正确性，再做 10～16 的高放大链路，最后做 17～19 的生命周期与容量治理。每一组独立审查，不做大一统重写。

## 三、为什么先做候选协议验证

当前证据已经证明旧删除链不可用，但尚未证明候选方案的：

- 精确索引、Lua 和 pipeline 在真实 Cluster 网络/槽分布下的实际延迟；
- 新键形与脚本全部同槽，没有 CROSSSLOT；
- 新写协议在并发接管、决策/取消交错下保持原子性；
- 旧数据迁移与后台对账不会重新引入无界 SCAN；
- 优化后的成本只随目标对象数增长，而不随背景键空间增长。

因此不能仅凭设计文档直接改完整生产链，再等服务接口压测发现协议仍不合格。

## 四、前置验证方式：Redis 协议级候选压测，不走服务接口

### 4.1 工具形态

新增独立于生产代码的候选验证 harness，使用项目相同的 Java/Lettuce 版本、连接方式、序列化和 Redis 配置。它直接对隔离命名空间执行：

1. 旧协议的命令序列：只用于已有小规模基线，不在 640 万键 Cluster 重跑全量旧场景。
2. 候选协议的精确键形、索引 Lua、帧/决策 Lua、xterminal、单键删除 Lua 和有界 pipeline。
3. 读写后的精确键面、slot、TTL、状态与终态断言。

不调用 `/api/ai/session/*` 等服务接口，不启动真实模型，不修改生产代码，不引入新第三方依赖。候选 harness 不是生产实现，验证通过后仍需把同一协议落入真实服务并做接口验收。

### 4.2 必须纠正现有脚本的实验口径

- 每个数据规模独立测量，不得像当前 generate 一样先把 bg_10k 和 bg_100k 全部生成后再比较。
- 旧/新协议使用等价数据副本和相同节点；交替执行，避免节点状态与缓存偏差。
- 客户端超时不代表服务端停止；每轮后必须确认旧执行体/重试已安静，再读取最终键面或清理。
- cleanup.complete=false 必须展开 remaining 分类，不能把全部残留写成 tombstone。
- 所有扫描、生成、测量和清理都有页数/对象数/单调时间预算；部分结果明确标为不完整。

## 五、前置验证范围

### A. 删除协议：必须先做性能验证

场景至少覆盖：

- 空会话、最小会话；1/10/50 个终态 run；混合活跃/挂起 run。
- 5/500 个 staging；10/2000 个 legacy archive；多 transcript/attachment 元数据。
- 已有终帧+RUNNING 快照、无终帧、终帧在首/中/尾、大跳号。
- 重复删除、HTTP/runner 等价双执行体竞争、执行租约失效后的接管。
- 相同目标规模下分别放入小键空间、10 万背景键和现有大键空间 Cluster。

记录：P50/P95/P99、最大耗时、精确命令数/字节、pipeline 批次、每节点 RTT、SCAN 页数、最终键面、pending/guard/发现键及 tombstone 分类。在线删除候选协议的 SCAN 页数必须为 0。

建议初始性能门槛（真实环境首轮前冻结，不能看到结果后再放宽）：

- 空/最小会话：P95 不超过 250ms，且不得出现 30 秒级长尾；
- 50 个终态 run：P95 不超过 2 秒；
- 2000 个 legacy archive：P95 不超过 3 秒；
- 背景键空间放大后，相同会话目标的工作量与 SCAN 页数不得增长；若延迟明显退化，必须能由节点/RTT证据解释，不能直接进入生产施工。

### B. run/frame/decision 原子协议：先做直接 Redis 正确性验证

至少验证：

- fence 检查与帧落档之间发生 epoch 接管，旧帧不得归档/扇出；
- decided 与 pending 写入之间模拟旧实现崩溃，新实现不得留下半状态；
- claim、批准、回灌与取消交错，已认领副作用不得被写成未执行；
- 大跳号 Stream 的终帧/tool_start 不漏判；
- 所有键同 slot，standalone/Cluster 得到同样业务结论。

这些项目主要防正确性回归，不需要为每种交错做长时间压测。

### C. 高放大读模型：只为大改建立候选原型

history 规范读模型、recovery-due 索引、KB 发布协议在各自生产施工前，复用 harness 验证目标数据量下的命令数、字节数与延迟。配置 single-flight、Pub/Sub 生命周期、TTL/资源回收等局部项不做独立预压测，避免验证工程超过生产修改本身。

## 六、施工门禁

1. 先完成候选 harness、场景、指标和验收断言，再运行；不接受只看平均耗时。
2. A 的性能与完整性、B 的原子性未通过时，不开始对应生产代码修改。
3. 候选通过后，生产实现必须复用已验证的键形、Lua 与不变量，不得在落地时另换未经验证的协议。
4. 生产落地后再用真实服务接口做少量端到端验收；前置 harness 通过不等于生产集成已验证。
5. 最终同时验收 HTTP 占用、最终清理完成时间、多实例接管、standalone/Cluster 一致性和资源结局；禁止只凭接口提前返回宣称性能达标。
