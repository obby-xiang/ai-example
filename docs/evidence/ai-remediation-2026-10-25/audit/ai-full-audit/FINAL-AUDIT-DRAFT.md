> 来源=<仓外证据>/ai-full-audit/FINAL-AUDIT-DRAFT.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FINAL-AUDIT-DRAFT｜AI 全链路只读验收（A–J + L/M 对抗复核 + G1–G5 缺口全量）

> **快照**：`<仓外证据>/ai-full-audit/tree` = base `ae883fbae52be36e45a635ee276cf8298ac24bd2` + 未提交 `working.patch`（SHA256 `6c88b47d…dab955`）。
> **输入**：A–J 十份域报告（I 已恢复）、`L-adversarial.md`、`M-adversarial.md`、`K-synthesis.md`（临时版）、G1 47/47、G2 51/51、G3 61/61、G4 63/63、G5 46/46 分支全量输出。
> **性质**：只读合成。未修改任何代码/测试/配置/工作流，未触发 CI，未启动服务，未读取或输出任何密钥值。唯一写入物是本文件。
> **口径**：**不作任何绝对保证**。每条结论都带证据类别与成立条件；「已证实」= 代码路径可证或运行日志可证，不等于在本机复现。

## 0. 对前序结论的修正（本文件已采纳，优先级高于 K/A–J 原表述）

| # | 原表述 | 修正 | 依据 |
|---|---|---|---|
| C-1 | （引文）`ResumeService.java:1414` 作记忆整窗写入方 | **引用错误**：`ResumeService.java` 全文 **1,260 行**，不存在 1414 行；整窗写入器 `convergeMemory` 在 **`ResilientChatService.java:1414`**（`persistMemory:1463`）。凡"僵尸接管覆盖记忆"的链路一律改引 `ResilientChatService:1414-1448` | 本报告实测 `wc -l`；L-4、M-A1 同 |
| C-2 | B-F-A「无测试断言心跳仍发」 | **撤回**。`SseChatEmitterAuthorityFailureTest:51-77` 的用例名与断言正是该组合（业务帧全丢 + 心跳单帧）。真实缺口是**无用户面缺口信号/无 degradation 计数** | L-3 |
| C-3 | A-F2b「先到的人机卡既不能提交也不能取消」（三类卡同论） | **收窄**：`generative_form` 成立；**表格预览可放弃**（原判断为误）；上传交接是**另一种缺陷**——老卡"放弃"取消的是新卡 | L-2b |
| C-4 | A-F2「两张前端卡」 | **扩大**：`clearSuspendedIfNoOtherConfirms` 只统计 CONFIRM 卡，**混批（确认卡+前端卡）同样提前清 `suspended`** | L-2 |
| C-5 | J-F-02「6 条可复现」 | **分层**：**5 条真产品红**（shared-file / suspend-kill / streaming-kill / job-kill / readiness）+ **1 条假红**（`suspend-cross-instance`）+ **1 条间歇**（`cold-start-3`，一次 PASS 一次 FAIL） | L-6b、G5 U-37 |
| C-6 | K S-04「任意主机」 | **限定**：默认配置下可证的是**任意外部公网主机**；LAN 目标需 `allow-private-endpoints=true` 或 DNS TOCTOU（闸两处都拦私网） | M-A3（PARTLY） |
| C-7 | K S-05「整轮挂死」 | **改写**：「归档零终帧 + 重挂连接被服务端保留至客户端看门狗」；停止发起页靠本地收敛；且派发可能 `skipped`（此时连快照转 CANCELLED 都不保证） | M-A4 |
| C-8 | 「违反恰好一个终帧」 | 补：`FrameContract.terminalContract` **不检查零终帧**（`terminals.isEmpty()` 直接跳过）⇒ 现有 conformance 抓不到此类，属**校验器缺口** | M-A4.4 |
| C-9 | K S-06「任意两实例可覆盖」 | **收窄**：需「≥180s 无业务帧 + 一次接管触发 + 旧写者苏醒」三条件同时成立 | M-A5 |
| C-10 | A-F3「仅 7 个测试文件 / 2,000+ 单测大多验阻塞路径」 | **限定**：8 个文件显式置 true（7 行为 + `AiParkConfigTest` 配置断言）；**58 个 `@SpringBootTest` 加载 yml ⇒ park=true**，不能说"测试全在验老路径" | L-5 |
| C-11 | G/J「无鉴权 = Critical 缺陷」 | **定性改为**：DC-10/CH-P7 **明文登记**的演示选择；仅三条**超出登记前提**（`/runs` 枚举、记忆键别名、key 外发）。严重度按部署前提分档 | L-1、M-§9 |
| C-12 | K「≥3 JVM 无粘滞无证据 / 顺序未验」 | **修正**：`proxy-no-sticky` 与 `same-tcp-across-abc` **实测 PASS**，无粘滞**有**证据（只是整网从未绿）；但**负载均衡顺序**未被断言（允许 8–12/30） | L-6.2、G5 U-38 |
| C-13 | K U-42…U-45「无主分支」 | **关闭**：AI 设置页、markdown 流式/mermaid、上下文条、赞踩视图经 G5 复核为**单元级分支完整、设计可接受**；残留仅为"无真机/无跨页" | G5 U-42..U-45 |
| C-14 | B-F-E2 记忆覆盖链路 | 与 C-1 同改；另补：唯一守卫是"新轮正在跑 ⇒ SESSION_BUSY"，**新轮已完结时无守卫** | L-4 |
| C-15 | A-F4「`/chat` 不查 draining」 | **拆开**：`/chat` 不查 draining 属**刻意设计**（LB 摘流层负责，G5 U-28）；真正的已证实缺陷是**SIGTERM 后可观测 draining 窗口不存在**（E3），以及**执行器 `shutdownNow` 无终态写**（条件风险） | G5 U-28/U-29、J-F-05 |

---

## 1. 执行裁决（Executive verdict）

**一句话**：这套 AI 链路的**协议与状态机设计质量高于其交付证据质量**——帧序/序号/幂等/租约/fencing 的逻辑自洽且有能变红的用例；但在任务给定的前提（Redis Cluster + 共享 PG/文件 + ≥3 JVM 无粘滞 + 多用户）下，**没有任何一条端到端保证成立**，因为唯一的多实例网从未绿过、Cluster/PG 零覆盖、补丁零 CI 验证。

**能成立的部分（都带条件）**

1. 单实例 + Redis 正常 + 无并发外部输入时：帧协议（seq 单调、终帧闸门、差量回放）、决策幂等（`ai:decided` HSETNX）、工具认领（`claimExecution`）、租约 token 精确释放、会话销毁键族隔离、单编码下载字节一致（补丁后）——代码与单 JVM 用例可证。
2. 跨实例**读**面（历史分页、幂等决策、配置热更新、会话销毁、Redis 整体停机降级）在**单节点 Redis + H2 TCP** 上被真跑过（run `40174291244` 的 5 项 PASS）。
3. 无粘滞路由**已实现且被测通两项**（HAProxy roundrobin 无 stick-table；`proxy-no-sticky`、`same-tcp-across-abc` PASS）。

**不能成立的部分**

1. **交付面是红的**：cluster-3node 4/4 failure；5 条同签名产品红（共享文件双 zip、接管 seq 回卷、接管缺 `tool_start`、job_items 残留、无 draining）。
2. **门禁不可信**：PR 的 e2e 在 HEAD 停在 `H-01`，130 条只跑 17 条后被 25 分钟超时取消；39 条 P2 红不阻塞；纯前端改动不跑浏览器 e2e。
3. **前提层零证据**：真实 Redis Cluster × 产品 JVM = 0；真实 PostgreSQL = 0；真模型仅 1 次 9 条无复跑；多用户不可能被测（无用户维度）。
4. **两条超出演示边界、且补认证后仍会复发**：记忆键别名（跨 sid 读+删）、`/runs` 全局枚举（含工具参数/终稿）。密钥外发（改 baseUrl + 探活）是第三条，但目标范围需按 C-6 限定。

**最高优先级（按"用户可感知损害 × 证据强度"）**

| 序 | 问题 | 说明 |
|---|---|---|
| 1 | **决策 × park 退出丢批准（PK-01）** | 200 + `wokeInProcessGate:true`，但无任何线程执行；再点 409；无本地超时 |
| 2 | **park 取消零终帧（RT-04）** | 归档缺 `done`；其它页签/刷新后客户端挂到看门狗；派发 skipped 时连 CANCELLED 都不保证 |
| 3 | **零条目假成功 + 任务被锁死（BZ-01）** | 四链全 COMPLETED 零数据；EXPORT 还让任务永久 `TASK_FINALIZED` |
| 4 | **取消后仍执行不可逆写（PK-02）** | 续跑路径的宽窗口（压缩期），非毫秒级 TOCTOU |
| 5 | **记忆键别名（ID-04）** | 正常发一轮即删掉他人记忆；认证修不了 |
| 6 | **重试重复写记忆（MD-01）** | 失败轮持久可见，挤占 40 条窗口 |
| 7 | **僵尸接管覆盖新记忆/current（RT-05）** | 模型"忘记"最近一轮；刷新回放旧轮 |
| 8 | **前端 409 死角 + 多卡不重连（FE-17 / PK-13）** | 面板显示空闲、答案永不出现 |

---

## 2. 后端保障矩阵

> 前提：原生 Redis Cluster（3 主 3 从）、共享 DB/文件、≥3 JVM、**任意请求可落任意实例**。
> 状态：**成立(条件)** / **部分成立** / **不成立** / **未验证** / **未覆盖**。所有"成立"仅指代码或单 JVM 用例可证。

### 2.1 Redis Cluster

| 能力 | 机制/证据 | 状态 | 缺口 |
|---|---|---|---|
| run 级 13 键同槽 | `runScopedKey` 统一 `{run:<id>}`（`RunStore:462-474,1019-1050`）；`ClusterSlotTest` CRC16 替身 | 成立(条件：键都经该入口) | 非真 Cluster（U-01） |
| 租约三键同槽 + 取号同序 | `ACQUIRE` Lua，`epoch=max(INCR,fence+1)`（`LeaseService:155-186`） | 部分成立 | **Lua 原文从未执行**；改 `>=`→`>` 仍 1000+ 绿（G3 M-2） |
| 台账+幂等索引原子 | `LLEN` 先取 + `HSETNX`；仅 `IDEM_PUSH` 断言过脚本文本 | 部分成立 | 其余 6 条脚本无 `lastScriptBody` 断言 |
| 记忆 staging→RENAME 同槽 | tag 逐字相同（`RedisChatMemoryRepository:193-199`） | 成立(条件) | RENAME 同槽未在真 Cluster 验 |
| 销毁 13 键一条 DEL | 同 tag | 成立(条件) | 观测键 3 键 DEL 跨槽（RT-minor） |
| 逐 master SCAN | `RedisClusterScanSupport:95-135`，任一失败 fail-closed | 部分成立 | 真多 master 游标/去重未验（U-03） |
| 单 master 故障下的可用性 | `RedisAvailability:72-104` | **分歧未消解** | F 域字节码称 `ping()` 逐 master（任一失败即全站 503）；G5 称"单连接一次 PING、无仲裁"。二者均同意**无故障切换测试**（U-02）→ 记条件风险，不作已证实 |
| `maxmemory` 淘汰 | 部署假设 noeviction；CI/3 节点已设 | 条件风险 | 无仓库内部署清单强制（U-04） |
| Cluster 安全静态门禁 | `forbid-redis-cluster-unsafe.mjs` 实跑 308 文件 0 命中 | 成立 | `keyCommands().scan` 形态不匹配（门禁盲点） |

### 2.2 ≥3 JVM / 无粘滞 / 任意路由

| 能力 | 证据 | 状态 | 缺口 |
|---|---|---|---|
| 同会话单轮互斥 | `SessionGate` 单键 Lua + token；`SessionBusyFrameIsolationConformanceTest` | 成立(条件：Redis 可用) | Redis 异常降级为进程内门 ⇒ 跨实例失效 |
| 跨实例驱动唯一（续跑） | `ai:lease:run` + fence + `verifyHeld` 对 Redis 真值 | 成立(条件) | **首轮不取租约**：`NEVER_HELD ⇒ plain SET`（RT-02） |
| 决策恰一次 | `ai:decided` HSETNX + 复判 + WAL | 成立(条件) | WAL 只覆盖 3 个可重建态（PK-04）；幂等≠派发存活（PK-01） |
| 工具副作用恰一次 | `claimExecution` HSETNX + 台账 + fence | 成立(条件) | `epoch==0` 时**跳过** fence 复核（RT-02 同源） |
| 无粘滞路由（拓扑） | HAProxy `roundrobin`、无 stick-table；`proxy-no-sticky`、`same-tcp-across-abc` **PASS** | 成立（真跑过） | **顺序未断言**（8–12/30 容差，U-38） |
| 跨实例读历史/翻页 | `history-pagination` PASS（42 条三元组同源） | 成立(条件：H2 TCP + 单节点 Redis) | 非 PG；N 会话并发交叉污染未测 |
| 跨实例配置热更新 | `config-hot-reload` PASS（版本递增、pid 不变） | 成立(条件) | 快照 3 查询非事务（MD-20） |
| 跨实例会话销毁 | `session-purge-cross-instance` PASS | 成立(条件) | 销毁 × 续跑无用例（RT-06） |
| 跨实例帧补帧 | `reattach-any-instance`、`seq-cross-instance` PASS | 部分成立 | 接管后 seq 回卷（RT-14，补丁未验）；窗口缺口不可见（RT-07） |
| 3 JVM 全链 | cluster-3node 4/4 failure | **不成立** | 5 真红 + 1 假红 + 1 间歇（C-5） |
| 并发冷启动 | `Flyway baseline-on-migrate` + 3 实例并发 | **不成立（间歇 2/3）** | `SeedLock` 只串行播种，Flyway 阶段无重试（RT-19） |

### 2.3 重连 / 取消 / park / 续跑 / kill / 恢复

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 挂订阅与回放原子 | 成立(条件：写方存活) | `SseChatEmitter:666-714` | — |
| 终帧收尾判据=归档有无终帧 | 成立(条件) | `SseChatEmitter:694-703` | **零终帧不报错**（C-8 校验器缺口） |
| 差量 `?lastSeq` | 部分成立 | `ReattachLastSeqConformanceTest`；集群网 PASS | 窗口外静默跳（RT-07）；接管回卷（RT-14） |
| 唤醒丢失兜底 | 部分成立 | 仅前端 45s 看门狗 | 服务端无周期补帧（RT-15，设计接受：U-16） |
| 取消（驱动线程） | 成立(条件) | `ResilientChatService:1246-1300` | — |
| **取消（park）** | **不成立** | `ResumeService:681-697` 只写快照+`cancelPendings` | 零终帧（RT-04）；派发可 skipped |
| 取消（Redis 写失败） | 不成立 | `AiController:862` 无条件 `cancelled:true` | 跨实例继续跑（RT-13） |
| 取消后不再产生副作用 | **不成立** | `awaitConfirm→executeOnce`、`resolvePending→executeOnce` 无复查 | 阻塞路径 Minor / 续跑窗口 Important（PK-02） |
| park 线程交还 | 成立(条件：`RunParkedException` 原样穿出) | `ConfirmGate:592-623` | 真实 Spring AI 循环未验（PK-15） |
| 决策 × park 退出 | **不成立** | latch 预登记于 park 分支之前 + `woke` 即不派发 | 批准被吞（PK-01） |
| 即时前端工具到期收敛 | 部分成立 | 进程内单线程调度器 | 实例死亡即永不收敛（PK-05） |
| 续跑准入/单驱动 | 成立(条件) | `ResumeService:613-802` | — |
| 僵尸接管 | 条件风险 | `forceTakeover` 只校验快照自身 | 会话已前进时覆盖记忆（RT-05） |
| 丢租（LOST 墓碑） | 条件风险 | `LeaseService:686-709,844-850` | RUNNING 无接管源（RT-10） |
| kill 驱动实例 | **不成立** | 180s 内 409 + 前端丢弃 reattach + 无周期扫描 | RT-03 |
| 死轮 → purge | **不成立** | 永 `complete=false` → 同 sid 永久 410 | RT-06 |
| 停机 draining | **不成立（实测）** | 两次 run 15s 内无 `ready=false`，全 ECONNREFUSED | 补丁已加 gate 未验证；`cluster` profile 无 graceful（RT-18a/19） |
| 停机时在飞轮 | 条件风险 | `AiExecutorConfig:52` `shutdownNow`，无终态写 | 快照停 RUNNING（RT-18b，U-29） |

### 2.4 历史 / 配置 / 工具 / 业务作业 / 文件

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| DB seq 不重不跳 | 成立(条件：共享 DB) | 唯一约束 + 32 次重试（`AiChatHistoryService:148-168`） | PG 未真跑；`hasMore` 方向依赖（MD-07） |
| 记忆整窗写 Cluster 安全 | 成立(条件) | staging+RENAME | **别名漏洞**（ID-04） |
| 记忆配对保护 | 部分成立 | 产品写口保配对；advisor 写口不保 | MD-02；`ToolResultClearing` 短结果反而变长（MD-16） |
| 上下文分母 | **不成立** | `/context` 用 `limitTokens(null, echoModel)`；`ContextState` 无 providerId | 与压缩分母不同源（MD-04 升级）；未知档不可达（MD-03） |
| 压缩原子性 | 部分成立 | 失败即原样 | 状态 `HSET+EXPIRE` 无 CAS（MD-18）；同轮归档键覆盖（MD-19） |
| 配置热更新 | 成立(条件) | 版本号 + 1s 轮询 | 写并发无 CAS（MD-08）；审计缺前后像（BZ-24） |
| 配置写 SSRF 闸 | 成立(条件) | `AiEndpointGuard:64-113` 拦私网/元数据 | 公网放行 ⇒ ID-03 前门 |
| 密钥外发（baseUrl+test） | **已证实缺陷** | `AiConfigWriter:325-335`（改即 `source=DB`）+ `AiConfigController:152`（`trusted=ENV_SEED`）+ `AiConnectionProbe:151` | 任意外部主机（默认）；LAN 需开关/TOCTOU（C-6） |
| 种子 provider 编辑后探活 | **已证实缺陷** | 同上：`source` 翻转 → `BLOCKED_PRIVATE_ENDPOINT` 永久 | 破坏文档化 CI/E2E 路径（BZ-23） |
| 工具签名/披露/授权 | 部分成立 | risk/channel/scope/category 全部只来自注解（G1-Q7） | **元数据缺失 ⇒ 四维同时 fail-open**（PK-11）；scope 守卫下限 26 < 37（CI-14） |
| 提示词定界 | 部分成立 | 闭标签中和、注入用例是计数式强断言 | FORMAT 字符可跨防线（PK-17）；取消收尾路径无外壳（PK-17） |
| 截断契约 | 部分成立 | 文本/结构双通道 | `notice` 与 `returnedChars` 自相矛盾（PK-17）；双通道同时裁剪覆盖语义（PK-17） |
| 工具认领/台账 | 成立(条件) | 三态 + 回滚 CAS | — |
| 业务作业互斥/终态 | 成立(条件：DB 行锁) | `JobService:156-165` 四守卫 | **无可执行性校验**（BZ-01） |
| IMPORT/PRECHECK 终态兜底 | **不成立** | 仅 EXPORT 有 `afterCompletion` | 悬置 30m（BZ-02） |
| 条目收敛（FAILED/取消） | 成立(补丁后) | `JobProgressService:146-190` | 补丁零验证；实测残留（BZ-06） |
| 运行期改条件 | 条件风险 | `TaskItem` 无 `@Version` | 静默回退（BZ-03） |
| 单编码下载字节 | 成立(补丁后) | `FileController:276-310` | ae883fb 一律 zip（BZ-07 实测） |
| 共享盘多实例 | 成立(条件) | 启动守卫强制绝对路径 | 同 runner 本地目录，非跨机（U-07） |
| KB 索引/增量 | 部分成立 | 异常 fail-open 保留旧索引 | **空清单清空检索面**（BZ-17）；元数据变更不自洽（BZ-18）；`match=high` 恒真（BZ-19） |

---

## 3. 前端保障矩阵

> 前提：多页签并行 + 多用户隔离 + 业务态同步。结论先行：**页签级隔离在安全上下文可证；用户级隔离不存在；业务态跨页签同步不存在（设计上就没有）；而"页签共享"在非安全上下文反而成立（缺陷）。**

| # | 场景 | 机制 | 状态 | 证据 | 缺陷/缺口 |
|---|---|---|---|---|---|
| F1 | 多页签并行（各自新会话） | `sessionStorage` 页签级 + Web Locks `ai-session-<sid>` | 成立(条件：安全上下文) | `tab-ownership-lifecycle`(10，桩)；`13-nfezui` N06–N09 真机（旧绿） | 3+ 页签矩阵无用例（FE-13） |
| F2 | 并发对话（同 sid） | 服务端 `SessionGate` 409 | 成立(条件) | `SessionBusyFrameIsolationConformanceTest` | 前端 409 后不进恢复（FE-17） |
| F3 | 复制页签（安全上下文） | 判 duplicate → 换新 id + 清镜像 | 成立(条件) | 同上 | 接管路径继承表单草稿（FE-08） |
| F4 | **复制页签（http + 内网 IP）** | 无 `navigator.locks` → `unsupported` → 按"恢复" | **不成立** | `ai.ts:419-427,4588-4590`；`vite host 0.0.0.0` | 共用会话：消息不同步、互抢 409、"新建对话"互毁（FE-01） |
| F5 | 慢重载 | 有返回意向 → 只降级 | 部分成立 | `ai.ts:4607-4609` | 降级后不重认领，可被空白副本接走（FE-07） |
| F6 | **多用户** | 无 | **不存在** | `api/http.ts` 无 Authorization；无登录态 | 隔离全在后端，而后端无主体（FE-02） |
| F7 | 一轮多张前端卡 | `suspended` 单布尔 + 三单例 | **不成立** | `ai.ts:5090` 无条件置 false；`:3328/3427/3533` | 最后一张不回连（FE-13/PK-13）；混批同样中（C-4） |
| F8 | 一轮多张人机卡 | 单例 + 按 toolCallId 渲染 | **部分不成立（已修正）** | `AiPanel.vue:614,632-639,645-653` | GF：先到卡无入口；表格预览：**可放弃**（原判断撤回）；上传交接：**放弃会取消新卡**（PK-12） |
| F9 | 刷新恢复（RUNNING） | 镜像 → `/history` → `lastSeq=0` 全量回放 | 成立(条件：镜像可写) | `ai.ts:2169-2202` | 先清空后回放、`/events` 失败无回滚（FE-06）；配额击穿丢 park 轮正文（FE-03） |
| F10 | 刷新恢复（SUSPENDED） | 多卡串行重建 + 增量续收 | 成立(条件) | `restore-*` | 串行队列只覆盖恢复路径 |
| F11 | 停止 | 本地先封口 → POST cancel | 成立(条件) | `aie2e-stop`(11) | `start` 帧前停止取消上一轮（FE-05） |
| F12 | 会话销毁（410） | 换新 id + 清屏 | 成立(行为) | `session-purged-recovery` | 静默清空且通知不提历史（FE-04） |
| F13 | 通知/错误 | 单槽 notice | 部分成立 | `ai.ts:1222-1230` | 互相覆盖（FE-09）；建议词绕过可用性闸（FE-10） |
| F14 | **业务态跨页签同步** | 无 | **不存在** | 复核：`BroadcastChannel` 仅用于 `ai-session-ownership` | 表格预览/撤销、上传交接、表单草稿、作业 watch 各自为政；表格写入无服务端互斥（FE-14） |
| F15 | 设置页 ↔ 工作区 | 设置页不 `enterPage`、不注销上下文 | **不成立** | `router:54-66`；`AiSettingsView:20-25`；`workspace:471-509` | 上下文陈旧，AI 可写"看不见"的任务（FE-15） |
| F16 | 导入向导同路由换任务 | 只 `setTaskId+loadTask` | **不成立** | `ImportWizardView:1665-1679,541-578,1086-1111` | 旧作业串到新任务、误只读、旧 watcher 泄漏（FE-16） |
| F17 | AI 发起发布 | `START_JOB_SYNC` 只含 export/precheck/import | **不成立** | `ai.ts:812-816,5360-5382` | 发布作业不绑页面面板（FE-18） |
| F18 | 并发发布冲突 | `watchingJobId` 单值 | **不成立（测试已固化）** | `task.ts:113-116,298,352-355`；`watch-publish-outcome:255-289` | 较早作业的冲突完全静默（FE-19） |
| F19 | 作业终态回灌模型 | `recentActions` 只随下一轮 | 部分成立 | `workspace:283-302` | 本轮模型不知失败、无取消工具（FE-20，设计缺口） |
| F20 | 前端提交门禁 | `changed-files` frontend 只开 frontend 面 | **不成立** | 复核 `changed-files.mjs:75-79` | 纯前端改动不跑浏览器 e2e（CI-06） |

**补充**：前端测试拓扑与生产前提不一致——`harness.ts` 为 A/B 两个 JVM 指定**各自独立 H2 目录**、只共享单库 Redis；双 JVM 只能证明"Redis 共享面"，**不能**证明共享 DB/文件（IN-06）。

---

## 4. 去重发现总表

> 合并规则：同根因一行；各域原始编号列在"来源"。**证据类别**：`E1`=本报告/对抗复核二次核验代码；`E2`=域或缺口报告代码级结论（未二次核验）；`E3`=CI run 日志；`E4`=测试断言级；`E5`=仅缺测试/未验证；`E6`=已登记演示选择/已裁决非缺陷。
> 严重度：`S1`=数据/密钥/不可逆写外泄或无人工干预的持久错误；`S2`=用户可见错误结果、静默丢内容、整轮挂死、多实例承诺不成立；`S3`=一致性/可观测性/窄窗口；`S4`=文档/文案/性能/死码。
> 共 **130 条**（来自各域原始条目约 331 条，去重率约 61%）。

### 4.1 身份 / 隔离 / 密钥（ID，14 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| ID-01 | 全链无认证/无主体（39 端点） | S1（多用户）/S3（单机） | **E6 已登记**（DC-10/CH-P7） | E1 | `pom.xml` 无 security；`AiController` 全部端点 | 任意可访问者调用 | 无（不可有） |
| ID-02 | `/runs` 全局枚举 sessionId+runId+**pending.arguments/resultText**；`/runs/{id}` 再给 `finalText`/ledger | S2→S1 | **超出登记前提** | E1（L/M 核验） | `AiController:934-951`；`ResumeService:293-360`；`PendingToolCall.asMap:280-306` | 一条 GET 拿全 id 与业务载荷 → 直接喂 ID-05 | `resumeServiceZombieThreshold` 不涉；G5 U-24 判"设计即全局运维面" ⇒ **需产品裁决** |
| ID-03 | 改 baseUrl 保留旧 key，`/test` 与 `/models/fetch` 把库中 key 发到新主机 | S1 | **已证实（范围按 C-6）** | E1 | `AiConfigWriter:325-335,309-313`；`AiConfigController:143-158`；`AiConnectionProbe:151`；`AiModelCatalog:80-82` | 4 个未认证请求 → key 外带 | 有"保留 key"与"拦私网"两半边，**无联动用例** |
| ID-04 | 记忆键别名：`sid="{x}"` 读/删 `sid="x"` 记忆 | **S1** | **已证实（认证修不了）** | E1 | `RedisChatMemoryRepository:187-204,281-291,304-340,350-355` | 正常发一轮即删（`saveAll` 的 legacy 清理），比 DELETE 更易触发 | 无花括号 id 用例 |
| ID-05 | 他人可批准/取消/续跑/销毁他人轮次 | S1（多用户） | 条件（依赖 ID-01/02） | E2 | `AiController:524,855,1072`；`AiSessionPurgeController:78` | 批准即真实执行 import/publish | 无负向用例 |
| ID-06 | 可向他人上下文注入任意文本（`result` / 拒绝 `reason`） | S2 | 条件 | E2 | `AiController:590-717`；`ConfirmGate:1138-1216` | 伪造"工具结果/用户决定" | schema 只覆盖结构化工具 |
| ID-07 | 反馈越权：全表 top100、`rating=0` 全局删行、无会话过滤 | S2 | 条件（单用户前提依赖） | E2（G4 确认仓储无过滤） | `AiFeedbackRepository:10,13`；`AiController:1933-1999` | 跨会话泄露/篡改/删除 | standalone+mock，全部不可见 |
| ID-08 | `sessionId`/`runId` 无形/长/熵校验（含 >64 落库 500、限流桶串用） | S2 | 已证实 | E2 | `AiController:313-316`；`AiFeedback:23-27` | 使能 ID-04、跨用户 429 | 无 |
| ID-09 | 表单附件 `sessionId` 由客户端声明；不传则孤儿 | S3 | 条件 | E2（G4 F11） | `GenerativeFormAttachmentController:82,107,143-153` | 挂到他人会话、随其销毁被删 | 无 |
| ID-10 | 跨任务绑定只在上下文带 taskId 时生效 | S2 | 条件（测试固化为期望） | E2 | `ToolTargetResolver:101-131`；`ToolTargetResolverTest:118-120` | 任务中心可对任意同类型任务起作业（仍需批准） | 缺"页面级 + 他人 taskId"负例 |
| ID-11 | CORS `localhost:*`+凭据+`0.0.0.0:8081` | S2 | 条件 | E2 | `WebConfig:20-27`；`application.yml:1-3` | 本机任意网页跨源读 /api/ai | 无 CORS 用例 |
| ID-12 | 非安全上下文 sid 熵 ≈41bit + 时间戳 | S3 | 条件 | E2 | `ai.ts:201-211` | 猜中即共享会话 | 无 |
| ID-13 | 未认证昂贵/长连接操作（`/kb/reload`、`/scan`、SSE 无死线） | S3 | 条件 | E2 | `AiController:894-932,1911`；`AiKbController:43` | 单实例耗尽 | 无压测 |
| ID-14 | 409 仍下发 `runId`+`reattach`；前端不消费 | S3 | 条件（双向同源） | E2 | `AiController:424-434`；`ai.ts:2589-2620` | 安全：他人可重挂；UX：用户无自救 | 409 用例给了字段却不断言 |

### 4.2 park / 确认门 / 工具契约（PK，17 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| PK-01 | **决策撞 park 退出：`woke=true` 但无消费方 ⇒ 批准被吞、整轮挂死** | S2 | 已证实（竞态） | E2（C 域逐段 + 本报告核 latch 顺序） | `ConfirmGate:558`（预登记）、`601-623`（park 不消费）、`1596-1613`（`woke` 即 skip）、`1876-1897` | 批准 200 但无执行；再点 409；刷新后 `awaitingExternal=false` 走全量重放等不到 done | **无**；`ConfirmGateDeadlockResumeTest:330-346` 反把窗口固化为期望 |
| PK-02 | 取消后仍执行已放行工具 | S2（不可逆写） | 已证实 | E2（M-A6） | `SpToolCallingManager:1320-1328,1816-1830`；`ResumeService:681-698→884-960` | 批准→取消→续跑仍执行 import/publish。**阻塞路径窗口仅毫秒级（计 S3）；续跑路径跨重建/压缩，宽窗口（S2）** | 无"批准后取消"用例 |
| PK-03 | 超时/取消写回裸写非 CAS（整条覆盖） | S2 | 已证实 | E2 | `ConfirmGate:905-931,944-963`；`RunStore:1320-1328` | 丢用户输入、双结局帧、guard 与条目分叉 | 只覆盖单侧 |
| PK-04 | WAL 只覆盖 3 个可重建态；`APPROVED`/`FRONTEND_RESULT` 崩溃窗留永久死卡 | S2 | 条件风险 | E2 | `ConfirmGate:1150,1186-1190,1296-1342`；`RunStore:1517-1527` | 不能批/拒/续跑/超时，只能取消整轮 | 无 |
| PK-05 | park 后即时前端工具到期收敛是进程内一次性任务 | S2 | 条件风险 | E2 | `ConfirmGate:617-619,978-1011`；`StartupResumeRunner:139-148` | 实例死亡 → 卡永不收敛（至 24h TTL） | `ParkedFrontendExpiryTest` 同步执行任务 |
| PK-06 | park 后同批"已结算未发帧"条目永远没有 `tool_start/tool_result` | S3 | 已证实 | E2 | `SpToolCallingManager:528-548,490-494,1839-1842` | 模型口头解释、卡面缺失 | 无 |
| PK-07 | 轮内预算 armed 只在本进程本段；`MAX_ARMED_BATCHES` 跨 park/实例失效 | S3 | 已证实 | E2（G2 F3：**无补偿**，仅 wrap-up 文本仍留历史可再武装） | `SpToolCallingManager:380-383,900-909`；`ToolActivityBeacon:242-276`；`ResilientChatService:475,543` | park 后继续产生新副作用 | 单实例单段 |
| PK-08 | 已公布卡被 step-limit/首错 skip ⇒ 前端停在"正在同步" | S3 | 已证实 | E2（G5 U-33：无 `SKIPPED` 回执映射） | `SpToolCallingManager:495-557`；`ai.ts:3122-3129,2959-2987` | 双按钮禁用，只能刷新 | `parts-residue-cleanup` 只覆盖 REJECTED |
| PK-09 | `RunRegistry.close` 可能掐掉同实例刚启动的续跑 | S3 | 条件风险 | E2 | `AiController:470-474`；`RunRegistry:189-194` | 续跑帧只进归档，客户端提前 close | 无 |
| PK-10 | 非驱动实例 `/confirm` 丢回执帧；emitter 条目从不回收 | S3 | 已证实 | E2 | `ConfirmGate:1186-1191`；`SseChatEmitter:1437-1443`；`RunRegistry:138-153` | "已批准"要等 `tool_result` 才可见；内存线性增长 | `RunRegistryIdleRecycleTest` 只测 recycle 本身 |
| PK-11 | **元数据缺失 ⇒ risk/channel/scope/category 四维同时 fail-open，且无启动不变量** | S2（潜伏越权） | 条件风险（当前无触发实例） | E2（G1-03） | `ToolRegistry:88,202-216,317-327,371-376,415-436` | 父类/非 CGLIB 代理 ⇒ 任意页披露 + 越 scope 执行 + DANGER 免确认 + FRONTEND 桩被后端执行 | 无 `callbackMap ⊆ metaMap` 断言；名称清单硬编码 |
| PK-12 | 一轮多张"等人"卡共用单例 | S2 | **部分成立（已修正）** | E2（L-2b） | `ai.ts:3328,3427,3533`；`AiPanel.vue:614,632-639,645-653` | GF：先到卡无入口；表格预览：可放弃（原判断撤回）；上传交接：**放弃取消新卡** | 无 |
| PK-13 | 一轮多张前端卡：第一张回灌后 `suspended=false` ⇒ 最后一张不重连 | S2 | 已证实（静态） | E2（L-2 扩大） | `ai.ts:5090,2461-2462,2987-2992`；`AiController:766-769` | 最后一张提交后无连接、终帧只进归档、输入区锁死；**确认卡+前端卡混批同样中** | 无 live 双卡用例 |
| PK-14 | park 前置 `tool_start` ⇒ 等待期后端工具显示 running | S4 | **E6 已裁决非缺陷** | E1（注释） | `SpToolCallingManager:483-494,726-730`；`parts.ts:539-557` | 语义取舍（公告≠已执行） | `ParkToolStartOrderConformanceTest` |
| PK-15 | `RunParkedException` 穿越真实 Spring AI 循环 | S2 | 未验证 | E5 | 无真实循环证据（替身 `Flux.error`） | 若被包一层 ⇒ 全 park 轮失败且卡悬空 | 无 |
| PK-16 | 双通道同时截断：live 帧 `noticeCode` 被覆盖，live 与 `/history` 文案语义分裂 | S2 | 已证实 | E2（G1-02） | `SseChatEmitter:1275-1292`；`FrameSizeLimiter:104-142,274-296`；`AiController:1407-1440` | "模型视图被裁"在前端消失；同字段两种文案 | `ToolResultDualChannelContractTest` 无"两侧同时"用例 |
| PK-17 | 契约层 minor 包：YAML 覆盖边界三处不一致（G1-04）、schema 仅对账名字/单层类型/required（G1-05）、`PromptDelimiters` 不剥 FORMAT 字符且信封路径无前置清洗（G1-06）、截断前全文 `readTree`（G1-08）、`ResultViews` 的 id/format/align 未消毒（G1-09，无现发漏洞）、`resultView` 预算只靠调用方约定（G1-10）、`defaultFrom` 允许 attachment 字面 fileId（G4 F13） | S3 | 已证实/条件风险 | E2 | `YamlToolDefinitionSource:77-79`；`ToolSchemaReconciler:53-72`；`PromptDelimiters:33`；`ToolResultLimiter:146`；`ResultViews:119-136,239-241`；`GenerativeFormRules:749,940` | 模型侧表达可被静默改写；闭标签防线有字符级缝隙；新增 provider 即爆帧 | 见 G1-12 九条缺测 |

### 4.3 运行 / 帧 / 租约 / 恢复（RT，19 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| RT-01 | 租约权威判定失败时业务帧被丢（取号前）、心跳照发 | S2 | **已证实机制（刻意 fail-closed）** | E2（L-3 修正） | `SseChatEmitter:1421-1444,1454-1476`；`LeaseService:797-841`；`RunStore:695-699` | 正文静默缺段且重连补不回；**无用户面缺口信号、无 degradation 计数** | **有**：`SseChatEmitterAuthorityFailureTest:51-77`（原"无测试"撤回） |
| RT-02 | 首轮从不取租约 ⇒ 常规轮无 fence，`NEVER_HELD ⇒ plain SET` | S2 | 已证实（条件风险） | E1/E2（M-A5、G3 R-1） | `AiController:299-488`；`RunStore:690-726,764-789`；`tryClaimExecution` 在 epoch=0 跳过 fence | 陈旧实例可覆盖接管方终态、再发一个 done；**需 ≥180s 静默 + 接管 + 旧写者苏醒** | 无"never-held vs 已推进 fence"用例 |
| RT-03 | kill 驱动实例：180s 409 + 前端丢弃 reattach + 无周期扫描 | S2 | 已证实 | E2 | `AiController:416-435`；`RunLiveness:42-62`；`StartupResumeRunner:69-108` | 3 分钟"发送失败"，提示与真实原因无关 | `AiControllerZombieGateTest` mock 化 |
| RT-04 | **park 场景取消零终帧** | S2 | 已证实 | E1/E2（M-A4） | `ResumeService:681-697`；`SseChatEmitter:694-703`；`AiController:855-884` | 归档无 done；重挂连接保留至客户端 45s×4；**派发 skipped 时连 CANCELLED 都不保证**；停止页本地已收敛 | `HumanWaitParkTest:230-243` 反把"无终帧"固化为期望 |
| RT-05 | 僵尸 RUNNING 接管：重建旧历史 → 整窗覆盖记忆 → current 指回旧轮 | S2 | 条件风险（代码级） | E2（L-4） | `ResumeService:326-349,710-729`；`RunStore:733-735`；**`ResilientChatService:1414-1448`（C-1 更正）**；`RedisChatMemoryRepository:304-340` | 模型"忘记"最近一轮；刷新回放旧轮半截 | 无"旧轮接管+会话已前进"用例 |
| RT-06 | 死轮 ⇒ purge 永 `complete=false`、同 sid 永久 410、数据残留至 TTL | S2 | 已证实 | E2 | `AiSessionPurgeService:367-431,742-760`；`SessionPurgeGuard:106-123` | 销毁"看似成功未删净" | `AiSessionPurgeServiceTest` 的活跃 run 会收敛 |
| RT-07 | 归档窗口/字节预算缺口只有服务端计数、无帧内标记 | S2 | 条件风险 | E2 | `RedisFrameStore:399-416,668-679`；`ai.ts:4930-4935` | 静默少一段正文；`tool_result` 落在缺口则整卡消失 | 只断言计数 |
| RT-08 | `wakeFloor` 本地扇出时推进 ⇒ 晚到小号帧永久缺失 | S2 | 条件风险 | E2 | `SseChatEmitter:1517-1520,1564-1573,1598-1601` | 一小段永久缺、表现为"没发生" | `WakeFloorTest` 覆盖反方向 |
| RT-09 | seq INCR 失败降级进程内计数 ⇒ 撞号、`mergeBySeq` 丢帧 | S3 | 条件风险 | E2 | `RedisFrameStore:183-197,485-499,741-743` | 回放丢帧、lastSeq 倒退 | `SeqPersistenceTest` 不是撞号场景 |
| RT-10 | 丢租超 TTL ⇒ LOST 墓碑终局；RUNNING 无接管源 | S2 | 条件风险 | E2 | `LeaseService:686-709,844-850`；`ConfirmGate:1614-1618` | 整轮静默停摆，只有人工/重启可救 | 无"断 Redis > TTL"用例 |
| RT-11 | `RUNNING` 死轮被 `/events`、`/runs/current` 反复续期 24h | S3 | 已证实 | E2 | `RunStore:2339-2385` | purge 永不因过期自愈 | 无 |
| RT-12 | 溢出摘除在 `emitLock` 内做 pub/sub 退订 | S3 | 条件风险 | E2 | `SseChatEmitter:1511-1515,574-583` | 慢 Redis 拖住该 emitter 全链路 | 无观测面 |
| RT-13 | 取消 Redis 写失败仍 200 `cancelled:true`；`wokeInProcess` 名不副实 | S2 | 已证实 | E2（G5 U-27） | `AiController:855-884`；`CancellationRegistry:147-154,232-247` | UI 已停、另一实例继续跑（含副作用） | 无 |
| RT-14 | 接管后重挂从 `seq=1` 全量重放 | S2 | **E3 已证实（运行级）** | E3 | `cluster3-probe.mjs:2809-2827`；补丁 `SseChatEmitter` wakeFloor=max(...,lastSeq) | 正文/卡片重复 | 补丁已加测试，**从未运行** |
| RT-15 | 跨实例唤醒丢失无服务端兜底 | S3 | **E6 设计接受** | E2 | `RedisFrameWakeBus:48-56`；`sse.ts:36-40,525,645` | 最长 45s 静默后自愈；非浏览器客户端永久停 | `WakeFloorTest`（未跑） |
| RT-16 | `ai:safety` 只记 `sheet_sample`；拒绝/限流/降级/截断仅进程内计数 | S3 | 已证实 | E2（G3 D-2） | `SecurityEvents:24-39`；`RunStore:2756-2777`；`AiController:329,357,372,651` | 事后无法回答"被拒几次/对谁" | 无 |
| RT-17 | 唤醒监听容器无 `@PreDestroy`；`markDriving` 把资源锁 TTL 改写为 30s（陷阱）；畸形租约值卡一轮 TTL | S3 | 已证实 | E2（G3 R-3/R-6） | `RedisFrameWakeBus:105-135`；`LeaseService:347-354,869-881,601-609` | 非守护线程阻退出；资源锁语义被静默改写 | 无 |
| RT-18 | draining：SIGTERM 后无可观测窗口（实测）+ 执行器 `shutdownNow` 无终态写（条件） | S2 | **前半 E3 已证实 / 后半条件风险** | E3+E2 | `ReadinessService`；`ShutdownDrainGate`；`AiExecutorConfig:52`；`/chat` 无 draining 检查（**E6 设计**） | 在途 SSE 被硬切；快照停 RUNNING 至 180s | `ReadinessDrainWindowTest` 不绑真实端口 |
| RT-19 | 三 JVM 并发冷启动 Flyway baseline 竞态（2/3） | S2 | **E3 已证实（间歇）** | E3 | `application.yml:64-67`；`cluster3-probe.mjs:1728-1765` | 至少一实例启动失败；曾出现顺序基线也失败 | 无；补丁未触及 |

### 4.4 模型 / 记忆 / 历史 / 上下文（MD，20 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| MD-01 | 重试把 user 消息重复写进记忆并重复进请求；`fail()` 不收敛 | S2 | 已证实（机制） | E2（M-A8） | `AiModelConfig:77-81`；`AiProviderRegistry:308/341/415`；`ResilientChatService:458-471,683-706,1378-1405` | 第二次 attempt 的 messages 里同句出现两次；失败轮重复持久可见并挤占 40 条窗口。**成功/取消路径整窗覆盖会掩盖** | 无；服务级用例全部 mock advisor |
| MD-02 | advisor 的 40 条裁剪不保 tool 配对 ⇒ 孤儿 `role:tool` | S2 | 条件风险 | E2 | `AiModelConfig:68-75`；`ResilientChatService:1638-1647` | 偶发整轮 `UPSTREAM_REJECTED`，错误码不指向历史结构 | 无真实 advisor + Redis 组合用例 |
| MD-03 | `max_context` 缺失静默 128000；"未知档"实际不可达 | S2 | 已证实（结构性） | E2（G2 F2） | `ContextLimitResolver:72-125`；`AiProperties:1341`；`ContextConfig:75-90` | 32K 模型压缩永不触发；`/health` 反报 `compactionOn=true` | `ContextLimitResolverTest` 不覆盖该组合 |
| MD-04 | **上下文分母不同源**：续跑丢 providerId；`/context` 用上游回显模型名；`ContextState` 无 providerId | S2 | 已证实 | E2（G2 F1，High/High） | `ResilientChatService:2145` vs `:1961-1962`；`ContextBreakdownService:51`；`ContextState:37-39,78-101` | 首轮与续跑 percent 跳变；同名模型跨供应方取错行；压缩正确但显示错误 | 51 个用例全都走 properties 回落，**DB 路径零覆盖** |
| MD-05 | 压缩摘要以 user 消息进入 `/history`；**且 wrap-up 文本被当作用户指令持久化** | S2 | 已证实 | E2（D-F-05 + G2 F4） | `RollingSummarizer:222-237`；`ResilientChatService:1420-1422`；`AiChatHistoryRecorder:177-196` | 用户看到自己没说过的话；下一轮模型读到"接近窗口上限（已弃用键名）"的 user 指令，可能拒绝工具 | 无 |
| MD-06 | 工具执行期（≤120s）无帧，前端 45s 判断线 | S3 | 条件风险 | E2（G5 U-31：多为体验退化非丢失） | `application.yml:206-210`；`ResilientChatService:714-721`；`sse.ts:31` | 每 ~45s 重连一次，用户易误点停止 | 无慢工具用例 |
| MD-07 | `/history` 的 `hasMore` **方向依赖**（before=更旧 / after=更新），同名字段两义 | S3 | 已证实 | E2（G2 Q6） | `AiController:1268`；`AiChatHistoryService:305-311` | 当前无前端消费者；未来翻页客户端会误判到底 | 测试与实现同源背书 |
| MD-08 | 配置行无 `@Version`/CAS；并发覆盖但两条审计都称成功；重复 create → 500 而非 409 | S3 | 已证实 | E2（G3 D-7） | `db/AiProvider:33` 等；`AiConfigWriter:276-352` | 修改静默丢失、审计不可信 | 只覆盖单写者 |
| MD-09 | 种子/Flyway 回填不推进 `config.version` | S3 | 条件风险 | E2 | `AiConfigSeeder:111-150`；`V5` | 滚动重启只一台生效，请求打旧端点/旧 key | 只验写库结果 |
| MD-10 | 请求级 `modelId/params` 在产品中不可达；`/health` 不报配置版本 | S3 | 已证实 | E2 | `AiPanel.vue:936`；`ai.ts:2566`；`AiController:1579-1672` | 外部客户端发 modelId 会被解析到库序第一家；跨实例排查缺字段 | `ai-panel-params-removed.spec` 锁死"不带" |
| MD-11 | 会话 TTL 文档漂移（README 6h vs 实现 24h，且只写时滑动） | S4 | 已证实（文档） | E2 | `application.yml:146`；`README` | 运维估错销毁窗口 | — |
| MD-12 | 非流式/摘要路径无 HTTP 读超时，只靠线程中断 | S3 | 仅缺测试 | E5 | `AiProviderRegistry:390-410`；`ContextSummaryClient:140-173` | 半开连接下摘要可能挂到父预算 | 无运行时断言 |
| MD-13 | `/history` Redis 故障返回 500（`/context`、`/events` 为 503） | S3 | 已证实 | E2（G5 U-26） | `AiController:1251-1262`；`GlobalExceptionHandler:320` | 不可重试语义、与类注契约矛盾 | 无该端点故障用例 |
| MD-14 | 反馈写面：超长 id → 500；`findByMessageId→save` 非原子，并发撞唯一键 → 500 | S3 | 已证实 | E2（G4 9） | `AiController:1948-1976`；`AiFeedback:23-27` | "点赞偶发失败"；用户看到无解释 500 | standalone+mock，不可见 |
| MD-15 | 刷新恢复锚点用 `archiveMaxSeq`；镜像 flush 失效时有不可补缺口 | S3 | 条件风险（已大幅缓解） | E2（A-F8 自我更正） | `ai.ts:2136,1475-1520` | 崩崩溃/配额后少最后一截 | 无 flush 失败用例 |
| MD-16 | `ToolResultClearing` 对短结果"清空后变长"，L1 可令 `after>before`；基线被高估 | S3 | 已证实 | E2（G2 F5） | `ToolResultClearing:113-135`；`ContextCompactor:306-311` | 压缩收益虚报、后续触发线偏移 | 只用 ~150 字符语料 |
| MD-17 | 挂起条目读失败时姿态不一致：轮内 fail-closed，预请求路径吞掉 ⇒ HITL 保护失效 | S2 | 已证实 | E2（G2 F6） | `ResilientChatService:2033-2061` vs `SpToolCallingManager:979-994` | Redis 抖动时未决轮的工具结果可被清空/摘要 | 无 |
| MD-18 | `ai:ctx:*` 状态 `HSET+EXPIRE` 无 CAS（last-writer-wins）；同轮归档键覆盖；ledger `LPUSH+LTRIM` 非原子 | S3 | 已证实 | E2（G2 F7/F8） | `RunStore:2715-2728,2813-2819`；`ContextCompactor:217-356` | 熔断计数/滞回丢失或复活；"压缩前原文可回溯"对首次压缩失效 | `CompactionFuseTest` 两次调纯函数 |
| MD-19 | 计量口径 minor 包：`lostTokens` 含 L1 收益（G2 F9）、pin 窗口按 response 计数导致本批新结果被清（F10）、摘要继承用户 maxTokens → length → 熔断（F13）、`CONTEXT_OVERFLOW` 误分类（F14）、`percent=null` 但 `free=limit`（F15） | S3 | 已证实 | E2 | `ContextCompactor:312-314`；`ToolResultClearing:100-113`；`ContextSummaryClient:116-125`；`ContextOverflow:25-27`；`ContextBreakdownService:67-71` | 指标失真、偶发 L2/L3 降级、错误提示指向"开新会话" | 均无用例 |
| MD-20 | 配置面 minor 包：`AiUnavailableException` **抛而无人接**（G3 D-4）、`reload()` 先 load 后读版本（D-5）、`models-cache-ttl<=0` = 永久且 provider 变更不失效（D-6）、配置快照 3 查询非事务（R-2）、`ResolvedProvider.toString` 含明文 key（R-5）、KEK"轮换"=逐条重录且无启动扫描（D-8）、`done` 不带 providerId 致计价归属歧义（R-4） | S3 | 已证实/条件风险 | E2 | `AiProviderRegistry:141-146`；`AiConfigService:175-181,257-276,1038-1044`；`AiModelCatalog:108-124`；`AiKeyCipher:52-56,190-193`；`SseChatEmitter:1329-1337` | 窄窗口 500 而非稳定码；配置静默陈旧；一次 KEK 变更导致全部 key 不可读 | 多数无 |

### 4.5 业务交界 / 作业 / 文件 / KB（BZ，24 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| BZ-01 | **零条目 `start_*` 假成功**：四链全 COMPLETED 零数据；EXPORT 还让任务永久 `TASK_FINALIZED` | **S1** | 已证实 | E2（M-A7 + G5） | `JobService:156-165`（四守卫无一条查可执行性）；`ExportJobRunner:112-121,281-292`；`PrecheckJobRunner:87-94`；`ImportJobRunner:71-78`；`PublishJobRunner:140-151` | AI 报告"已完成"，无文件/无发布；再导出 409 只能新建任务 | 无"0 条目"用例 |
| BZ-02 | IMPORT/PRECHECK 缺提交期回滚/异常终态兜底 | S2 | 已证实 | E2（M-A7.3） | `ImportJobRunner:60,183-197`；`PrecheckJobRunner:65`；`AsyncJobExecutor:27-45`；`JobWatchdog:193-207` | 悬置 RUNNING 至 30m，期间互斥挡住重跑 | EXPORT 有、此两处零用例 |
| BZ-03 | 作业期改条件被静默回退（长事务 + `TaskItem` 无 `@Version`） | S2 | 条件风险 | E2 | `ExportJobRunner:112,230`；`TaskService:348-354`；`TaskItem:1-33` | 用户的修正消失，下次出口用旧条件 | 无 |
| BZ-04 | 任务态联动失败被吞 ⇒ 可二次发布 | S2 | 条件风险 | E2 | `JobTerminalWriter:103-146`；`Task.java:37` | jobs 终态而 tasks 仍 ACTIVE | 只覆盖"取消胜出" |
| BZ-05 | 确认卡影响面失真（IMPORT 恒不可估、EXPORT 用上次行数） | S2 | 已证实 | E2（+G1 反证：`ResultViews` 本身不复用旧值） | `ToolPreflightService:210-240`；`FileController:145-161` | 用户在错误数字上批准 | 手工造 `row_count` ⇒ 假绿 |
| BZ-06 | 作业 FAILED 后 `job_items` 残留 RUNNING | S2 | **E3 已证实** | E3 | `cluster3-probe.mjs:2866-2946`；补丁 `JobProgressService:146-190` | "1/5 进行中"与 FAILED 自相矛盾 | 补丁新增测试**未跑** |
| BZ-07 | 单编码下载返回再包一层 zip | S2 | **E3 已证实** | E3 | `FileController:276-310`（快照已修） | 下载的 xlsx 打不开 | `FileDownloadByteIdentityTest` **未跑** |
| BZ-08 | 进度帧先于数据面提交 | S3 | 已证实 | E2 | `ExportJobRunner:184-186` 等三处 | 回滚后仍显示过"已处理 500 行" | 无 |
| BZ-09 | `list_tasks` 两遍全量载入（EAGER items） | S4 | 已证实 | E2 | `AiTools:176`；`ListTasksResultViewProvider:74` | 历史累积后变慢 | — |
| BZ-10 | 超时"未执行"措辞与"可能已起作业"并存（重试可建第二个作业） | S3 | 条件风险 | E2 | `SpToolCallingManager:1473-1484,1617-1643` | 台账与事实相反；极端下覆盖导出文件 | 无 |
| BZ-11 | AI 资源锁 60s 不续租、与业务守卫两套；假锁误导文案 | S3 | 已证实 | E2 | `ToolResourceLocks:127-171`；`SpToolCallingManager:1331-1332` | 秒级完成的作业后仍被劝退 | `ToolResourceLockGateTest` 只测三态 |
| BZ-12 | 旧绝对路径 `storage_path` 换挂载点不可读（迁移默认关） | S3 | 条件风险 | E2 | `FileStorageService:109-133`；`application.yml:466-471` | 预检"未找到上传文件"，无法自愈 | 无 |
| BZ-13 | IMPORT 取消后暂存区"部分批次"无标记 | S3 | 已证实 | E2 | `ImportJobRunner:150-164,97` | 暂存预览看到半批数据 | 无 |
| BZ-14 | ZIP 上传无规模上限（整包入内存 + 全量解压） | S3 | 已证实 | E2 | `FileController:62-96,167-186` | OOM；同实例 AI/作业受牵连 | 无 |
| BZ-15 | KB HTTP 源无地址白名单且跟随重定向 | S3 | 条件风险（配置面） | E2（A-F10 已实质审阅该文件） | `HttpKbSource:37-40,71-73` | 抓到内容进模型上下文 | — |
| BZ-16 | **manifest 合法但 `docs:[]` ⇒ 静默清空检索面** | S2 | 已证实 | E2（G4-1；本报告核 `KbManifest.validate` 无空表检查） | `KbManifest:48`；`KbService:250,274-288` | `ready=true,docCount=0`，检索退化、无告警 | 无空清单用例 |
| BZ-17 | 仅改元数据（title/tags）⇒ 索引 title 新、sectionPath 旧 ⇒ TOC/read_doc 全失配 | S2 | 已证实 | E2（G4-2） | `KbDoc:20`；`KbService:256-260,347-348` | SECTION_NOT_FOUND + 候选 | 无"仅元数据变更"用例 |
| BZ-18 | `match=high` 恒真：阈值是相对 topScore 的比例 | S2 | 已证实 | E2（G4-3） | `KbService:464-476` | 低相关度被标 high，模型当确定答案 | 只测单命中/零命中 |
| BZ-19 | 分块器可产出超上限 chunk；chunk 偏移互相重叠且非真实偏移 | S3 | 已证实 | E2（G4-4/5） | `MarkdownChunker:196-222,262-273` | 违反硬上限；未来消费者会错位 | `MarkdownChunkerTest:105` 只测等高行 |
| BZ-20 | `KbService.stop()` 后 `acquire()` 自旋 ⇒ 请求线程 100% CPU | S2 | 已证实 | E2（G4-6） | `KbService:171-187,792-801` | 停机期请求挂死 | 无 |
| BZ-21 | KB 多实例：本机盘当共享缓存源 ⇒ 版本乒乓；index 目录互删 | S2 | 条件风险 | E2（G4-8/F15） | `CachedKbSource:129-135,254-262`；`KbService:311-329` | 反复重建、条目互覆；A 删 B 的 reader 目录 | 无多源用例 |
| BZ-22 | KB 明文结果无定界（候选/建议走 `plain`） | S3 | 已证实 | E2（G4 F14） | `KbTools:191-197,322-333`；`KbText:158-165` | 间接注入面（manifest 可控文本） | 只测 knowledge 两标签 |
| BZ-23 | **种子 provider 任一编辑即 `source=DB` ⇒ 私网探活永久 BLOCKED** | S2 | 已证实 | E1（本报告核 `AiConfigWriter:325-335` + `AiConfigController:152`） | 同上 | 改显示名/key 后 `/test`、`/models/fetch` 全废；破坏文档化 CI/E2E 私网桩路径 | `AiConfigWriteEndpointTest` 只测未编辑行 |
| BZ-24 | 配置审计无法独立回答"端点变了吗"：无 baseUrl 前像、无"key 复用"正向标记、`enabled`/`max_context` 不入库、`actor` 恒 `local-user` | S3 | 已证实 | E2（G3 D-3） | `AiConfigWriter:340-347,474-476,730-738` | ID-03 事后不可发现/不可取证 | — |

### 4.6 前端（FE，20 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| FE-01 | 非安全上下文（http+内网 IP）复制页签共用同一会话 | S2 | 已证实（代码级 + 平台事实） | E2（L-8） | `ai.ts:419-427,4588-4590`；`vite.config.ts` host 0.0.0.0 | 消息不同步、互抢 409、"新建对话"互毁并清空对方 | 全部用例在 `127.0.0.1`/jsdom；`r166b:566-575` 反把该兜底写为期望 |
| FE-02 | "多用户隔离"不存在；空态文案"关闭页签即结束"与事实不符 | S2 | 已证实（架构事实） | E2 | `api/http.ts:133-147`；`zh-CN.json:407` | 共用工作站换人即见前一人对话 | 无（不可有） |
| FE-03 | 镜像无上限、正文与投影双份、写失败静默 | S2 | 条件风险 | E2 | `ai.ts:818-855,1483-1524` | 配额击穿后 park 轮正文整块丢失 | 无 |
| FE-04 | 410 静默清空整屏且通知不提"历史已没" | S3 | 已证实（行为） | E2 | `ai.ts:2593-2604,4521-4527` | 用户视角"对话突然清空" | `session-purged-recovery` 把清空当期望 |
| FE-05 | `start` 帧前点停止 ⇒ cancel 上一轮 runId | S3 | 已证实 | E2（L-9） | `ai.ts:4343,4390-4398` | 本轮无 runId 可取消，后端可能继续跑 | `concurrency-matrix` 同步发 start，结构上测不到 |
| FE-06 | "静默续收"先清空再全量回放，归档取不到时无回滚 | S3 | 条件风险 | E2 | `ai.ts:2191-2201` | 空气泡 + "结果可能不完整"，而答案刚还在屏上 | 无 |
| FE-07 | 慢重载降级 duplicate 后不再重认领；`pagehide` 兜底会在文档存活时放锁 | S3 | 条件风险 | E2 | `ai.ts:596-603,4607-4609,4634-4657` | 两页签又共用会话（同 FE-01 后果，触发面更窄） | 桩里文档销毁是即时的 |
| FE-08 | 复制/接管继承未提交表单草稿 | S3 | 已证实（行为） | E2 | `form-draft.ts`；`ai.ts:2340-2342` | 他人/他页签看到填了一半的值 | `gf-draft` 只测同页签刷新 |
| FE-09 | 单槽通知互相覆盖 | S3 | 已证实（设计） | E2 | `ai.ts:1222-1230` | "连接中断"顶掉"没发出去" | 各断言自己那条 |
| FE-10 | 空态建议词绕过可用性闸 | S3 | 已证实（行为） | E2 | `AiPanel.vue:1517-1522`；`ai.ts:1441-1443` | 必然 503 的按钮仍可点 | 无 |
| FE-11 | `onMounted` 的 `if(!messages.length)` 会跳过历史对账 | S3 | 仅缺测试（当前单实例不可达） | E5 | `AiPanel.vue:3638` | 未来多面板/HMR 时上下文与历史不同步 | 无 |
| FE-12 | `parts.ts` 帧序防御只有纯函数 4 例，无 store/浏览器级回归锁 | S2 | 仅缺测试 | E5（G5 U-34） | `frontend/tests/park-request-tool-start-order.spec.ts:65-115` | 补丁修的真实缺陷在门禁里无回归保护 | 4 例，且未跑 |
| FE-13 | 多页签/多用户真机矩阵缺失（3+ 页签、判定窗口内发送、同卡并发决策） | S2 | 仅缺测试 | E5（G5 U-19/U-20） | `13-nfezui.spec.ts:155-243`（仅该域） | 只在 jsdom 与单域真机验证过 | — |
| FE-14 | **业务态跨页签同步不存在** | S2 | 已证实（设计缺口） | E1 | 全仓 `BroadcastChannel` 仅 `ai-session-ownership` | 表格预览/撤销、上传交接、表单草稿、作业 watch 各页签独立；无服务端互斥 | 无 |
| FE-15 | **设置页不注销工作区上下文** ⇒ AI 仍可写"看不见"的任务 | S2 | 已证实 | E2（I-01） | `router:54-66`；`AiSettingsView:20-25`；`workspace:471-509` | 模型被披露成"仍在向导"，可 `PUT /tasks/N/...` 成功 | 无 |
| FE-16 | **导入向导同路由换任务不回收作业跟踪/作业位** | S2 | 已证实 | E2（I-02） | `ImportWizardView:1665-1679,335-343,541-578,1086-1111`；对照 `ExportWizardView:535-541` | 旧作业进度/冲突挂到新任务；**B 整页误只读、失败 Alert 归因错任务**；旧 watcher 泄漏 | `import-watch-recycle` 用自建接线，不挂视图 |
| FE-17 | **409 后不重挂 SSE** ⇒ 面板空闲但服务端仍在跑，终帧永不显示 | S2 | 具体风险（代码路径明确） | E2（I-03） | `ai.ts:3119-3129,3215-3227,2959-2981`；`AiPanel.vue:950,1580-1586` | 停止按钮消失/输入被锁，只能刷新；GF 分支反之锁死输入 | `r166-gate-link:217-238` 在物理上只覆盖非挂起轮 |
| FE-18 | AI 发起的 `start_publish` 不绑页面作业面板 | S2 | 已证实 | E2（I-04） | `ai.ts:812-816,5326-5382`；`ImportWizardView:1288-1311` | 页面"未开始" vs 对话"已启动"；用户重复点→互斥报错 | `ai-start-job-sync` 只覆盖三件套 |
| FE-19 | 并发发布跟踪单槽丢弃较早作业冲突（测试已固化） | S3 | 已证实（行为） | E2（I-05） | `task.ts:113-116,298,352-355`；`watch-publish-outcome:255-289` | 失败完全静默（无提示、无卡、模型也不可见） | **测试断言"丢弃"为预期** |
| FE-20 | 作业终态/发布冲突不真正回灌本轮模型；无取消作业工具 | S3 | 部分成立（设计缺口） | E2（I-06） | `workspace:283-302,405-409`；`ai.ts:812-816` | AI 最后一句话永远是"已启动" | 协议层无该通道 |

### 4.7 CI / 测试真实性 / 覆盖门禁（CI，16 条）

| # | 结论 | 严重度 | 状态 | 证据 | 关键 path:line | 触发 → 效果 | 测试 |
|---|---|---|---|---|---|---|---|
| CI-01 | PR 门禁 E2E 在 HEAD 跑不完：130 条只执行 17 条，停在 `H-01` 后被 25 分钟超时取消 | S2（门禁可信度） | **E3 已证实** | E3 | `ci.yml:225-237`；`verify-e2e.ps1:5483-5505,117` | "红"= 没跑完；H/G/T/K/Q/C/D/X/W 全域零执行证据 | — |
| CI-02 | 三实例网 4/4 failure：**5 条真产品红 + 1 假红 + 1 间歇** | S2 | **E3 已证实** | E3 | `cluster-3node.yml:43-109`；`cluster3-probe.mjs:1615-1632` | 多实例五项承诺（共享文件/接管执行/接管补帧/作业收敛/摘流）无一条被证明 | 见 C-5 |
| CI-03 | **补丁（含 6 个新测试）零 CI 验证**，而它覆盖 5 条红 | S1（证据边界） | **E3 已证实** | E3 | `working.patch`；各 run headSha | 所有"已修复"只能是意图 | 6 个新测试从未进入任何 run |
| CI-04 | `suspend-cross-instance` 期望与 park 收流契约冲突 ⇒ 确定性假红 | S3（门禁噪声） | 已证实 | E3+E2（G5 U-37） | `cluster3-probe.mjs:2157-2171` vs `AiController:762-768` | 假红会被当产品缺陷派工 | 修法：confirm 后再重挂 |
| CI-05 | 真模型指标把"卡被复位"计成"模型未触发" | S2（度量） | 已证实 | E3（G5 U-41） | `11b-hgta.spec.ts:505-520` | 产品缺陷被读成模型不服从 | 需拆"帧到达率 vs 卡可提交率" |
| CI-06 | 纯前端改动不触发浏览器 e2e | S2 | 已证实（配置） | E1 | `changed-files.mjs:75-79`（复核） | FE 类缺陷可绿着合入 | — |
| CI-07 | 无粘滞只验分布不验顺序（8–12/30；每节点 ≥2） | S3 | 条件风险 | E2（G5 U-38） | `cluster3-probe.mjs:1812-1813,1851` | `leastconn`/部分粘滞也能过 | 应断言 A→B→C 序列 |
| CI-08 | 39/130 条 P2 红不阻塞（分布 17/74/39） | S2 | 已证实（口径） | E1 | `verify-e2e.ps1:5707-5725` | 观察项与保证项无机器可读边界 | — |
| CI-09 | 作业名"35 用例"与实测 130/165 不符 | S4 | 已证实 | E1 | `ci.yml:225,345`；`verify-e2e.ps1:5507` | 按"35 条"设的预算正是 CI-01 诱因 | — |
| CI-10 | `verify-e2e.ps1` 的"从产品键派生超时"实为硬编码 300 | S4 | 已证实 | E2 | `verify-e2e.ps1:112-118` | 调大预算即新假红 | — |
| CI-11 | 永久/条件 skip 未登记为门禁缺口 | S3 | 仅缺测试 | E5 | `17-unlock:677`、`16-kqc:61`、`10-srp:164` | 对应行为永久无自动化 | — |
| CI-12 | `InMemoryRunStore` 复刻 `create()`（含 identity/freeze）⇒ 真实 `RunStore.create` 从未被执行；删掉 `setProviderId/setModelId/freezeParams` 仍全绿 | **S2（假绿）** | 已证实 | E2（G3 M-1） | `InMemoryRunStore:196-220`；`RunStore:648-651` | 身份冻结类回归无保护 | 对应用例跑在内存 store 上 |
| CI-13 | **7 条租约脚本无 `lastScriptBody` 断言**；改 `>=`→`>`、删 `PEXPIRE`、换 TTL 参数仍 1,000+ 绿 | **S2（假绿）** | 已证实 | E2（G3 M-2） | `FakeLeaseRedis:534-593`；`LeaseService:155-256` | Lua 回归不可见 | 仅 `IDEM_PUSH` 断言脚本文本 |
| CI-14 | 派工清单外的证据文件：`LeaseServiceTest` 未播种 fence（自愈分支未执行）；`ToolScopeTagSpaceGuard` 下限 26 < 实际 37 声明；`shippedYamlCoversEveryDeclaredTool` 只比 27 个硬编码 key；G2 有 6 个"构造性不可证伪"用例；34 个 web 测试仅 2 个 `@SpringBootTest` | S2（假绿） | 已证实 | E2（G1/G2/G3/G4） | 见 §5.2 | 覆盖数字好看，关键不变量无人守 | — |
| CI-15 | park 配置错位（生产 true / 代码缺省 false） | S3 | 已证实（按 C-10 限定） | E1 | `application.yml:258-259`；`AiProperties:855` | 非 Spring 单测走阻塞路径；Spring 用例（58）走 park 但未必覆盖 park 流程 | 8 个文件显式置 true |
| CI-16 | `assumeTrue(connect())` 的真 Redis 用例可静默 SKIP | S3 | 仅缺测试 | E5 | `RedisChatMemoryRepositorySaveAllEmptyTest` | CI 上常跳过 | — |

---

## 5. 测试完整性与真实性（什么绿了、绿得算不算）

### 5.1 分层：各自证明了什么

| 层 | 规模 | 真跑的部分 | 替身/桩 | 能证明 | 不能证明 |
|---|---|---|---|---|---|
| 后端单测 `mvn test` | 395 文件 / 2,607 `@Test`（AI 域 327 / 2,063） | 真 Java 对象、状态机、部分真 H2、部分真线程池 | **Redis 全部替身**；`InMemoryRunStore` | 分支/帧序/键名/降级路径 | 真实 Redis 语义、真实网络、跨进程 |
| `@SpringBootTest` | **58** | 真 H2 + 真 Bean + 真迁移 + **加载 yml（park=true）** | 无 Redis/PG | 装配级集成（单进程） | 多 JVM、park 流程本身 |
| 前端 vitest | 139 文件 / 1,511 例 | 真 store/reducer（jsdom） | `@/api/ai` 全量 mock；`FakeLockManager`/`FakeBroadcastChannel` | UI 状态机、段投影、恢复账本 | 真浏览器 API、真网络、真多页签 |
| `verify-e2e.ps1`（PR 门禁） | 165 例（130 AI + 35） | 真 JVM + 真 Redis(**单节点**) + 真 HTTP/SSE + 桩上游 | 上游是 `stub-upstream.mjs` | 帧序/门/表单/作业/取消/历史/字节（接口级） | 多实例、Cluster、真模型、UI 渲染、非安全上下文 |
| cluster-3node | 16 例 | 3 JVM + H2 TCP + HAProxy **真无粘滞** + 真 kill/pause/SIGTERM + 单节点 Redis | 桩上游 | 见 §6.2 | Cluster、PG、真模型、跨机共享盘、补丁有效性 |
| Playwright（9 套） | 278 `test()` | 真 Chromium + 真后端（部分域双 JVM，**H2 各自独立**） | 桩上游为主 | DOM/多页签/刷新 | **不在提交门禁**；HEAD 未跑 |
| 真模型 `@real` | 14 条（该次 9 条） | 真模型 + 真 UI | — | 采样服从率 | 稳定性（单次、无复跑、N=10） |

### 5.2 明确的假绿 / 弱断言 / 自证（逐条）

**A. 底座失真（影响面最大）**
1. `InMemoryRunStore` 默认 `deepCopyPending=false`（存同一对象引用）⇒ "读旧副本整条覆盖"类缺陷**结构性不可见**（PK-03 的根因）。
2. `InMemoryRunStore` **复刻** `create()` 的 identity/freeze ⇒ 真实 `RunStore.create` 永不被执行；删掉 `setProviderId/setModelId/freezeParams` 全绿（CI-12）。
3. `FakeLeaseRedis` 按**脚本注释标记**分派、Java 复刻语义 ⇒ 7 条租约脚本无脚本文本断言；改比较符/TTL/删 `PEXPIRE` 仍绿（CI-13）。
4. `InMemoryContextStateStore` 不建模 TTL/`LTRIM 20`/Redis 失败 ⇒ 台账边界与降级回归不可红。
5. `FrameWire.normalize` 只处理顶层（嵌套数字保持 `Integer`）；只实现 `send(String)`。
6. `FakeLockManager` 在"锁被占"时**同步**回调（真实是异步派发），且不建模"同一文档二次 `ifAvailable` 必然拿不到锁"（代码用 `heldLock?.name === name` 短路绕过）。

**B. 把缺陷窗口固化为期望**
7. `ConfirmGateDeadlockResumeTest:330-346`：arm 后 await 前决策 ⇒ `woke=true` 且**不触发续跑**（正是 PK-01 的缺陷形态）。
8. `HumanWaitParkTest:230-243`：park 期归档 `doesNotContain("done","error")`（把 RT-04 固化为期望）。
9. `ToolTargetResolverTest:118-120`：上下文无 taskId ⇒ 放行任意 taskId（ID-10）。
10. `r166b-fixes.spec.ts:566-575`：无 Web Locks ⇒ 按"恢复"（FE-01）。
11. `session-purged-recovery.spec.ts:87-113`：410 清空 `messages` 当期望（FE-04）。
12. `watch-publish-outcome.spec.ts:255-289`：并发发布"旧冲突被丢弃"当期望（FE-19）。
13. `ContextCompactorTest:381`：`lostTokens` 含 L1 的错误语义被锁死（MD-19）。
14. `AiControllerHistoryPayloadTest:210-221`：与实现同源背书反向 `hasMore`（MD-07）。

**C. 构造性不可证伪（命名承诺未兑现）**
15. `ContextEstimatorTest.messagesSkipSystemAndSumToolBodies`：`all < all + systemOnly` 是**恒真式**，"跳过 system"从未被证。
16. `ContextEstimatorTest.toolSchemasAreCountedWhenDisclosed`：只断言 null/空 时为 0，从未断言非空 > 0。
17. `CompactionFuseTest.twoInstancesBothSeeTheHalfOpenWindow`：两次调纯函数，无第二写者、无共享状态。
18. `SystemPromptHitlConfirmTest` 的 `.contains("才")`（单字）近乎无效；`LanguagePolicyTest`/`SystemPromptUiSnapshotRules` 为字面 `contains` 回归锁。
19. `AiCompactionLogTraceTest` 的 `limit=\d+` 也匹配 `limit=0`。
20. `AiControllerHealthParams/Sheet/SseDelivery` 三个 Health 测试断言的是"mock 返回值被回显"⇒ 硬编码常量也能过；且**无精确键集/敏感字段缺席断言**（ID-01 的暴露面只能 claim"字段在"）。
21. `ToolActivityBeaconSegmentTest:30,42` 用 `sleep(60ms)` 断言 50ms 下限（负载下可抖动）。

**D. 只断言 mock 调用 / 直调不可达分支**
22. `MultiCardParkResumeTest`：`verify(resolvePending, times(2))` 证明"派发两次"，不证明"副作用两次"。
23. `workspace-run-job-publish-confirm`（7 例）、`gate-matrix:155-188`：直调 `dispatchUiEvent`，且其中 `run_job/cancel_job/download/refresh_*` **当前无模型入口**（FE-minor，覆盖/死码）。
24. `import-watch-recycle:61-73`：测试自建"与视图同构"的 3 行接线，不挂 `ImportWizardView`（FE-16 正落在此盲区）。
25. `AiToolsInvocationTest.upload():341-352`：手工写 `row_count`，生产上传从不写 ⇒ 预检影响面假绿（BZ-05）。
26. `publish-conflict-link` 只证"落段"，无"本轮模型可见"断言（协议无该通道）。

**E. 测试自造生产不可达状态 / 口径漂移**
27. `AiRetryDefaultsTest:28` 硬编码 60s 而非读 `summary-timeout` 属性（MD-20 类）。
28. `RedisChatMemoryRepositorySaveAllEmptyTest` 用 `assumeTrue(connect())`，连不上即 SKIP（CI-16）。
29. `AiProviderLeakTest` 只监听 `AiConfigService` 一个 logger（其余 4 个类未覆盖）。
30. `AiModelResolverTest` 断言 5 级链，生产只走 3 级（另两级不可达 ⇒ 测死分支）。

### 5.3 非阻塞 / 跳过（门禁口径）

| 项 | 事实 | 后果 |
|---|---|---|
| P2 不阻塞 | 130 条 AI 用例分布 **P0 17 / P1 74 / P2 39**；退出码只算 P0/P1 | 39 条即使真坏门禁仍可能绿 |
| 永久/条件 skip | `17-unlock:677`（永久）、`16-kqc:61`、`16-kqc-b:41`、`16-kqc-c:68`、`10-srp:164` | 对应行为无自动化，且不进任何计数 |
| 真 Redis 可跳过 | `assumeTrue(connect())` | 唯一"真 Redis"证据在 CI 上常消失 |
| 桩模式零容忍 | GF `SKIP≥1` 即整棒 FAIL；批① `SKIP:*` 强制 FAIL | 这是正面设计（无静默跳过） |
| 三实例网无 SKIP 通道 | `RESULT_STATUSES = ['PASS','FAIL']` | 正面设计（不会静默跳过） |

---

## 6. 精确的 CI 证据 + GitHub Actions 能否做"真无粘滞多实例 + Redis Cluster"

### 6.1 三个可引用的 run（边界划死）

**A. `40174291244` — cluster-3node @ `ae883fb`（workflow_dispatch）**
真跑：3 JVM（`--spring.profiles.active=h2tcp`）、1 个 H2 TCP 进程、**1 个单节点 Redis 7.2.16**（无 `--cluster-enabled`）、HAProxy 2.9（host 网、`roundrobin`、无 stick-table）、桩上游。真做过 SIGKILL/SIGSTOP/SIGCONT/SIGTERM、`docker pause` Redis、真 multipart、真 HAProxy stats、21 轮历史分页。
结论：**failure**，PASS 9 / FAIL 7。
PASS：`proxy-no-sticky`、`same-tcp-across-abc`、`reattach-any-instance`、`history-pagination`、`seq-cross-instance`、`idempotency-cross-instance`、`session-purge-cross-instance`、`config-hot-reload`、`redis-outage-recovery`。
FAIL：`cold-start-3`、`suspend-cross-instance`（假红）、`shared-file-download`、`suspend-kill-a`、`streaming-kill-a`、`job-kill-a`、`readiness-removal`。
**不能证明**：Redis Cluster、PostgreSQL、真模型、补丁有效性（树是 ae883fb）、多用户、主从切换（只 pause）、跨机共享盘。

**B. `40174289141` — ci.yml @ `ae883fb`（push）**
backend success（`mvn test` 全量，4 分钟）；frontend success；**e2e cancelled**（07:56 起跑，07:59:23 后 20 分钟无输出，08:19:30 取消）；summary failure（如实记红）。
只跑到 `M-09`（AIE2E 累计 **17** 条），下一条 `H-01` 停住 ⇒ **H/G/T/K/Q/C/D/X/W 全域在 HEAD 零执行证据**。
**边界**：只能证明"TC1–TC23、GF1–GF6、CTX-1..4、AIE2E-S/P/M 共 17 条在单实例桩环境通过"。

**C. `40171918873` — e2e-real @ `20c56bb`（dispatch，suite=real）**
真模型 + 真后端 + 真 Chromium + 单节点 Redis；**8 passed / 1 failed**（20.7 分钟）；覆盖 9 条；stub 作业 **skipped**。
唯一红：`G-01 N=10 触发=0`，但归因错误（CI-05）——实际是"卡被 `tool_start` 复位/未渲染"，被计成"模型未触发"。

**对照（趋势，不可当 HEAD 证据）**：`40173314741`@965e6ef 跑完 130 条但 33 条红（W 域 21 条"done 帧 0 个"）；`40131155913`@d66ab7c nightly 165 条 13 分钟跑完、PASS 165/FAIL 1（同样"done 0 个"）。⇒ 健康时 13 分钟、25 分钟预算**本来够**；真问题是"单用例失控无上限"。

### 6.2 现有 workflow 证明了什么（仅在它绿时）

| 已 PASS 的能力 | 用例 |
|---|---|
| 无粘滞拓扑（roundrobin、无 stick-table） | `proxy-no-sticky`、`same-tcp-across-abc` |
| 跨实例重挂差量 | `reattach-any-instance`、`seq-cross-instance` |
| 跨实例读历史/双向翻页（42 条三元组同源） | `history-pagination` |
| 跨实例决策幂等（重复 409 + 并发双决策恰一胜 + Redis 幂等索引） | `idempotency-cross-instance` |
| 跨实例配置热更新（版本递增、三元组一致、pid 不变） | `config-hot-reload` |
| 跨实例会话销毁（无残留 + deferred + 自动重试 + 旧 sid 410 + 业务 task 保留） | `session-purge-cross-instance` |
| Redis 整体停掉后降级（ready 200、`checks.redis=DOWN`、AI 503、业务面 200） | `redis-outage-recovery` |
| 无 key 不摘流 | `readiness-removal` 第①段 |

**条件**：3 JVM + H2 TCP（非 PG）+ **单节点 Redis（非 Cluster）** + 桩上游 + 同 runner 本地目录 + **未打补丁**。

### 6.3 明确回答

**Q1：GitHub Actions 能否实现"真无粘滞多实例"？**
**能，而且已经实现。** 单 runner 起 3 个 JVM（不同端口）+ HAProxy `roundrobin` 无 stick-table + 共享 H2 TCP，同一 `/chat` 请求落不同实例**已被两项 PASS 证明**。这在 GH Actions 上完全合法（不依赖跨机网络）。**限制**：它证明的是**应用层**无粘滞，不是存储层集群；且必须 `workflow_dispatch`（~15 分钟，含 3 JVM + 真 kill/pause，成本不适合每 PR）。

**Q2：GitHub Actions 能否做"真 Redis Cluster + 产品 JVM"？**
**技术上能，当前没有。** 可行做法：在 job 内用 docker 起 **3 主 3 从**（`redis-cli --cluster create`，16384 槽）+ 3 个 JVM 指向 `application-cluster.yml`（`AI_REDIS_CLUSTER_NODES`）。但必须同时改掉三处**当前阻碍**：
1. E2E harness 依赖 `spring.data.redis.database` 与 `FLUSHDB`（`harness.ts:357,222`）——**Cluster 下两者非法**，须改为 `SCAN`+`DEL` 或独立命名空间；
2. `application-cluster.yml` 的拓扑 fail-fast 是 `@Profile("cluster")` 门控（非属性门控），CI 必须显式激活 profile，否则占位符错误不 fail-fast；
3. 需 `--maxmemory-policy noeviction`（CI 已对单节点设置，Cluster 每节点都要设）。
另需接受：3 Redis + 3 JVM + HAProxy 在单 runner 上的 CPU/内存压力（建议 `-Xmx512m` 级、`maxmemory 256mb` 级）；且**仍不能**证明主从切换（需 kill 单 master 并等 `cluster-node-timeout`）。

**Q3：能不能顺带覆盖"共享 PostgreSQL"？**
能（GH `services: postgres` + `application-postgresql.yml`），**当前零覆盖**。建议与 Q2 合成一条"生产形态"workflow。

**Q4：什么永远无法由 GH Actions 证明？**
多用户隔离（产品无用户维度）；真实公网代理的空闲超时（harness 的 HAProxy 是 310s，生产 60s 未建模）；跨机存储（NFS/PVC）语义；真模型服从率（只能采样，且当前样本 N=10、单次、`retries:0`）。

### 6.4 门禁结论

- **PR 门禁当前不可信**：E2E 没跑完（17/130），且 39 条 P2 不阻塞、纯前端改动不触发浏览器 e2e。
- **唯一的多实例网不是 required check**，且 4/4 红。
- **补丁零验证**：所有 run 的 headSha 都是 `ae883fb`/`965e6ef`/`20c56bb`，`working.patch` 从未被任何 workflow 执行。

---

## 7. 覆盖对账与残余未测边界

### 7.1 文件级对账（不含"看起来覆盖"）

| 集合 | 数量 | 谁读了 | 说明 |
|---|---:|---|---|
| AI 生产文件 | **196** | A–J 覆盖 118（深度不一）+ G1–G4 覆盖 78 | 196/196 至少被**点名并读过其关键段**；但这不等于逐行 |
| 其中"仅单面实质审" | 2 | A 读过 `AiExecutorConfig`（停机语义）、`HttpKbSource`（SSRF/重定向） | 其余面（池容量/MDC 装饰器；HTTP 解析/增量）**未审** |
| AI 测试文件 | **327** | G1–G4 读 **144**（逐条断言强度）+ A–J 点名/抽样 183 | 144 是本次补全的"只被登记"集合 |
| 前端工作区（I 域） | 11 条链 / 12 条发现 | I 报告（恢复后） | 逻辑面覆盖完整；**拓扑前提未满足、未执行任何测试**（快照无 `node_modules`） |
| G5 分支 | 46 | G5 | 6 真缺陷 / 17 条件风险 / 12 缺测 / 10 设计可接受 / 1 非分支 |

### 7.2 仍不能声称"已全量覆盖"的文件（深度不足，逐条列出）

| 文件 | 行数 | 已读 | 未读/未核 |
|---|---:|---|---|
| `ai/run/RunStore.java` | 2,822 | `save/authority/create/safety/fence/sessionCurrent`（B/F/G3） | `claim/ledger/idem` 的 5 条 Lua 逐行、索引 prune 全路径、`backfillSessionRuns` 后填标记 |
| `ai/run/ResilientChatService.java` | 2,352 | attempts/finish/fail/empty/cancel/park/converge（B/D） | 压缩装配、KB 注入段、`contentFiltered` 全分支 |
| `ai/gate/ConfirmGate.java` | 1,937 | C 域近全 + G 决策/回灌段 | `awaitApproval` 全循环、`dismiss`、`scheduleParkedFrontendExpiry` 细节 |
| `ai/gate/SpToolCallingManager.java` | 1,940 | C 域近全 | 批策略/超时/台账重放细节（G 只抽样） |
| `ai/run/SseChatEmitter.java` | 2,206 | emit/onWake/replay/attach/wake（B/D）+ emit 窗口（G4） | 投递池策略、写预算内部、`evict` 全路径 |
| `ai/run/LeaseService.java` | 1,146 | 4 条 Lua + 四态 authority + 墓碑（F/C） | `ACQUIRE_NOFENCE`、`isHeld` 对账、`markDriving` 全路径（G3 只读 TTL 段） |
| `ai/session/AiSessionPurgeService.java` | 1,011 | 枚举/删除/守卫主序（B/F/G） | 回滚全分支、`MAX_ATTEMPTS` 耗尽后重排 |
| `ai/kb/KbService.java` | 943 | 启停/刷新/版本轮询/readDoc 分页（D）+ 索引/空清单/stop（G4） | 缓存穿透、增量播种并发细节 |
| `ai/config/AiConfigService.java` | 1,085 | 版本闸/解析/加载（D）+ reload 顺序（G3） | `availability()` 全分支、`resolveChatTarget` 双参 |
| `ai/config/AiConfigWriter.java` | 942 | 写路径/版本/审计（D/G3） | 删除/硬删引用检查、`afterCommit` 全部 evict 面 |
| `ai/form/GenerativeFormRules.java` | 1,486 | 规则表/附件/上限（C/E/G4 部分） | 联动/原型污染全规则 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 3,700 | H 约 30% + I 模板/门卡 + A composer | 滚动/mermaid/拖拽/键盘/错误卡动作 |
| `frontend/src/utils/sheet-tools.ts` | 2,918 | I 定点（716-733/1428-1500/2573-2620/2804-2911） | 读写/聚合/撤销全量 |
| `frontend/src/utils/ai-dom-tools.ts` | 1,718 | I 只审"与工作区边界" | DOM 原语内部 |
| `frontend/src/utils/markdown.ts` | 1,100 | G5 核 prefix/mermaid caps/sanitize/limits | A 自认 ~700 行未读（流式前缀复用细节） |
| `frontend/src/stores/ai.ts` | 5,523 | B/D/H/I 合计约 85% | H 明确 4 段（≈1,460 行）未逐行 |

**结论**：可以声称"**文件级 196/196 有点名与关键段阅读、测试级 327/327 至少被登记且 144 个逐条评估**"；**不能**声称"全部 5 万行主代码逐行审完"。

### 7.3 残余未测边界（按能否在本仓库闭环分类）

**A. 只能靠真实拓扑（12 条）**：真 Redis Cluster（Lua 原文/CROSSSLOT/MOVED-ASK/槽迁移）、单 master 故障与主从切换、多 master SCAN 完整性、`maxmemory` 淘汰、真实 PostgreSQL（Flyway 锁/唯一约束/行锁/`lock_timeout`）、三 JVM 并发冷启动 Flyway 竞态、跨机共享存储（NFS/PVC）、反代真实空闲超时；并发侧：决策×park 退出真时序、取消×已放行、超时×回灌、僵尸接管×会话已前进、销毁×续跑、首轮无租约×接管、设施不可用×并发作业、唤醒真丢包。

**B. 只能靠真机/真模型（5 条）**：真模型服从率（需多次采样，当前 1 次 9 条）、非安全上下文整条路径、3+ 页签真 Web Locks/bfcache、两页签同卡并发决策、真 Chrome 文件选择器/下载字节。

**C. 代码可达但缺用例（需新测试，20+ 条）**：park 取消唯一终帧；`sid="{x}"` 隔离；`/runs` 作用域；改 baseUrl 不外发 key；`/history` 503；`/cancel` 写失败语义；draining 期 `/chat`；executor 关闭在飞轮；首轮+僵尸交错；工具 >45s 心跳；advisor 重试请求体；两页签同卡"同步中"；`parts.ts` store 级；KB 空清单/仅元数据变更/CRLF-BOM/超宽表/`section` offset/`stop` 后请求；表单多附件部分失败与 TTL；反馈超长与并发；`/health` 精确键集；authority 判定窗口；种子 provider 编辑后探活；KEK 轮换；DNS TOCTOU；`ContextState.fromFields` dirty 回退；`ConversationTurns.firstPinnedTurn`；`aiResumeTrigger`/`aiStreamWatchdog` 的 MDC；摘要继承 maxTokens；`ToolScope` 下限 26<37。

**D. 门禁工程（7 条）**：per-case 超时与耗时预算；假红修正（`suspend-cross-instance`）；无粘滞**顺序**断言；P2 观察项边界；`frontend/**` 触发 e2e；真模型指标拆分；补丁提交后重跑 cluster-3node。

### 7.4 I 报告恢复带来的净变化

- **关闭**：K 曾把"前端业务联动整域"列为最大盲区（14 类文件零提及）——I 现已逐链覆盖 C1–C11，并新增 12 条发现（I-01…I-12）。
- **撤回**：A-F2b 关于"表格预览先到卡不能取消"的判断（L-2b 指出存在"放弃"入口）。
- **新增到本表**：FE-15（设置页留陈旧上下文）、FE-16（导入向导换任务不回收）、FE-17（409 死角）、FE-18（`start_publish` 不绑页）、FE-19（并发发布丢弃）、FE-20（终态不回灌模型）。
- **仍未闭环**：I 自己的测试拓扑是"A/B 双 JVM + 各自独立 H2 + 单库 Redis"，**不满足**任务前提的"共享 DB/文件 + ≥3 JVM + Cluster"；且快照无 `node_modules`，I 未执行任何测试。

---

## 8. 很可能被"后续已批准补丁"处理的问题（**不假设已修**）

> 判定依据：`working.patch` 的文件清单（`ConfirmGate`/`SpToolCallingManager`/`SseChatEmitter`/`Readiness*`/`JobItemRepository`/`JobProgressService`/`JobService`/`FileController`/`application*.yml`/`parts.ts` + 6 个新测试）与 M §11 的逐条核对。**所有条目当前 zero CI evidence**。

| 本表条目 | 补丁改动 | 是否真的覆盖根因 | 需要补的证据 |
|---|---|---|---|
| **BZ-07** 单编码下载返回 zip | `FileController` 改"单码回原字节" | **是**（快照已改，本报告核） | `FileDownloadByteIdentityTest` 从未运行 |
| **BZ-06** FAILED 后 job_items 残留 | `JobProgressService/JobService/JobItemRepository` 收敛条目 | **是**（方向正确） | `JobWatchdogItemConvergenceTest` 从未运行 |
| **RT-14** 接管后 seq 从 1 回卷 | `SseChatEmitter` 加 `wakeFloor=max(...,lastSeq)` | **是**（算法层） | `SseChatEmitterWakeFloorTest` 从未运行；真实 pub/sub 与 kill 时序未验 |
| **RT-18a** SIGTERM 无 draining | 新增 `ShutdownDrainGate` + `ReadinessProperties/Service` | **部分**：只解决"可观测 503 窗口"，`cluster` profile 仍无 `server.shutdown=graceful`（RT-19） | `ReadinessDrainWindowTest` 不绑真实端口；集群网未复跑 |
| **PK-13/PK-14** 帧序（request 先于 tool_start） | `ConfirmGate/SpToolCallingManager` 前移 `tool_start` + `parts.ts` 防御 | **是**（帧序）；`parts.ts` 只覆盖 `request→start` 与 `start→request`，**未覆盖 `result→start`**（FE-12） | `ParkToolStartOrderConformanceTest`、前端 4 例纯函数，均未运行 |
| **CI-05** G-01 归因 | 同上（帧序修复使其不再复位卡片） | **部分**：根因是帧序，但指标仍未拆分 | 需"帧到达率 vs 卡可提交率"两指标 |

**明确**不在补丁范围、不可假设已修：`ID-02`（/runs 枚举）、`ID-03`（key 外发）、`ID-04`（记忆别名）、`RT-02`（首轮无租约）、`RT-04`（park 取消零终帧）、`PK-01`（决策被吞）、`PK-02`（取消后执行）、`BZ-01`（零条目假成功）、`BZ-02`（IMPORT/PRECHECK 兜底）、`MD-01`（重试重复）、`RT-19`（Flyway 竞态）、`BZ-16…BZ-22`（KB 一组）、G3 的 D-1…D-9、G2 的 F1…F15、G1 的 G1-01…G1-12、`FE-15…FE-19`。

### 8.1 后续补丁的验收前置（硬要求）

1. 提交补丁并**重跑** `cluster-3node`；把 `suspend-cross-instance` 改为"confirm 之后再重挂"（否则永远假红）。
2. 6 个新测试必须进入一次真实 run；`FileDownloadByteIdentityTest`/`ReadinessDrainWindowTest` 建议补真实端口/容器边界。
3. 任何"已修"表述必须附 run id 与失败用例清单；**不得**以"单测绿"替代。
4. 修复 `ID-04`/`ID-02` 时必须补红用例（`sid="{x}"` 隔离、`/runs` 作用域），否则无法证明。

---

## 9. 计数与证据分级

### 9.1 发现计数（130 条）

| 分组 | 条数 | 主要证据类别 |
|---|---:|---|
| ID 身份/隔离/密钥 | 14 | E1 4 / E2 10 |
| PK park/门/工具契约 | 17 | E2 15 / E1 1 / E5 1 |
| RT 运行/帧/租约/恢复 | 19 | E2 14 / E3 3 / E1 2 |
| MD 模型/记忆/历史/上下文 | 20 | E2 19 / E5 1 |
| BZ 业务/作业/文件/KB | 24 | E2 21 / E3 2 / E1 1 |
| FE 前端 | 20 | E2 16 / E1 1 / E5 3 |
| CI 测试真实性/门禁 | 16 | E2 5 / E1 4 / E3 5 / E5 2 |
| **合计** | **130** | — |

### 9.2 按证据类别（每条只计主类）

| 类别 | 条数 | 含义 |
|---|---:|---|
| **E1 二次核验代码** | **13** | 本报告或 L/M 亲自复核源码（含本报告直接复核：`ID-04` 别名键、`ID-02` 枚举面、`ID-03` 密钥外发、`BZ-23` 源翻转、`BZ-16` 空清单、`C-1` 行号纠正） |
| **E2 他域代码级结论** | **100** | 域或缺口报告声明"代码可证"，本报告未逐条重读（这是本文件最大的**证据宽度**限制） |
| **E3 CI 运行证据** | **10** | 附 run id（`40174291244`/`40174289141`/`40171918873`） |
| **E5 仅缺测试/未验证** | **7** | 无证据表明坏，也无验证 |
| 合计 | 130 | — |

### 9.3 按状态（归一后）

| 状态 | 条数 | 说明 |
|---|---:|---|
| **已证实缺陷**（代码级或运行级） | **80** | 含 8 条运行级（E3） |
| **条件风险** | **40** | 机制确定，需特定交错/配置/拓扑（含 1 条"前半运行级已证实 + 后半条件"） |
| **仅缺测试 / 未验证** | **7** | 不含"缺测但代码已证"的条目（那些计入已证实） |
| **已登记演示选择 / 已裁决非缺陷** | **3** | `ID-01`（DC-10 无认证）、`PK-14`（tool_start 前置语义）、`RT-15`（唤醒可丢） |

### 9.4 按严重度

| 级别 | 条数 | 典型 |
|---|---:|---|
| **S1**（数据/密钥/不可逆写外泄、或无人工干预的持久错误） | **7** | 记忆别名、key 外发、零条目假成功、`/runs` 枚举、补丁零验证 |
| **S2** | **64** | 批准被吞、park 取消零终帧、取消后执行、僵尸覆盖记忆、重试重复、前端 409 死角… |
| **S3** | **54** | 一致性/可观测性/窄窗口 |
| **S4** | **5** | 文档/文案/死码/性能 |

### 9.5 本文件**不**声称的东西

1. 不声称"全链路已通过"：§7.2 列出 16 个"读过但未逐行"的大文件；§7.3 列出 44 类未闭环边界。
2. 不声称"多实例可用"：唯一多实例网 4/4 红，5 条同签名产品缺陷。
3. 不声称"Redis Cluster 已支持"：产品从未在真 Cluster 上执行，Lua 原文一次未跑。
4. 不声称"PostgreSQL 已支持"：零 CI 覆盖。
5. 不声称"多用户已隔离"：产品无用户维度；且 `ID-04`/`ID-02` 即使补认证仍成立。
6. 不声称"补丁已修复"：§8 的 6 项只能是"代码意图已改、证据为零"。
7. 不声称"2000+ 单测绿 ⇒ 生产正确"：§5.2 列出 30 条假绿/弱断言/自证。
8. 不声称"E2E 全绿"：HEAD 的 e2e 是 `cancelled`（未跑完），不是 passed。
9. 不声称"真模型达标"：1 次 dispatch、9 条、无复跑，且唯一失败项归因错误。
10. 不声称"零风险"：E2 类 100 条均未经本报告逐条复核，若其中任一被后续证据推翻，最可能的顺序是 **RT-02（条件太苛刻）→ ID-03 的 LAN 段 → RT-04 的"用户可见"段**（M §12 的判断仍适用）。

---

## 10. 给指挥官的三句话

1. **先修"用户以为成功、实际没发生"的四条**：决策被吞（PK-01）、park 取消无终帧（RT-04）、零条目假成功（BZ-01）、取消后仍执行（PK-02）——它们的共同特征是**没有错误提示**。
2. **把"声明边界"与"真缺陷"分开派工**：DC-10 的无认证是产品裁决（改标签即可）；真正超出边界且补认证后仍复发的是 **记忆键别名（ID-04）** 与 **`/runs` 枚举（ID-02）**；key 外发（ID-03）按 C-6 限定目标范围后仍是 S1。
3. **恢复交付证据链**：补丁提交 + 重跑 cluster-3node + 修假红 + 补 per-case 超时；在此之前，**任何"已修/已验"表述都不成立**。
