# K 领域独立审查报告：分布式协调与 Pub/Sub

## 1. 身份与审查边界

**IDENTITY_PASS**

- `agentId`：`agent-950`
- 绑定记录：`profile.bind`
  - 文件：`<会话证据根>/agents/agent-950/wire.jsonl`
  - **第 6 行**
  - `modelAlias=kimi-code/k3.1-pro`
- 首条 `llm.request`
  - 同一文件 **第 20 行**
  - `modelAlias=kimi-code/k3.1-pro`
  - `model=k3.1-pro`
- 辅助证据：同文件第 2 行 `environment.set_binding.agentId=agent-950`
- 仓库：`ai-example-main-v2`
- 实测 HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。
- 仅执行只读代码/配置检索及 `git rev-parse HEAD`；未修改仓库、未运行测试或批量脚本、未执行 git 写、未启停服务、未写审查报告文件。

## 2. 结论摘要

| 编号 | 严重度 | 判定 | 问题 |
|---|---|---|---|
| K-01 | **P0** | 确定问题 | `create_task` 幂等查询和唯一约束不含租户/用户，可复用并返回他身份任务 |
| K-02 | **P1** | 确定问题 | `taskId` 资源锁不含租户/用户，跨身份误阻塞及 UNKNOWN 对账串扰 |
| K-03 | **P1** | 确定问题 | `task:events:<taskId>` Pub/Sub 与本地订阅表跨身份串帧，泄露作业和配置项信息 |
| K-04 | **P1** | 确定问题 | `jobId` 心跳、runner、取消、判死及本地 Map 全部裸 ID，跨身份覆盖/误取消/误判死 |
| K-05 | **P1** | 确定问题 | 新租约取得时不推进 fence，旧主人在新主首笔写入前仍可写快照、归档帧和认领工具 |
| K-06 | **P1** | 确定问题 | 存在全租户目录接口及静态循环目录，前端启动和身份选择强依赖该能力 |
| K-07 | **P1** | 确定问题 | 前端工具 `frontendToolsInFlight` 仅按 `toolCallId` 去重，绕过正确 Web Lock 键并误抑制他轮执行 |
| K-08 | **P2** | 风险 | `recoveryEntriesByRun` 仅按 `runId` 缓存执行事实，身份切换依赖外围清理而非键级隔离 |
| K-09 | **P2** | 风险 | 资源锁 Redis 故障时放行，故障恢复与后续 claim 之间存在无资源锁执行窗口；当前作业创建有 DB 行锁缓解 |
| K-10 | **P3** | 确定问题 | fencing/只读告警按裸 `runId` 节流，跨身份同名轮可互相抑制关键告警 |

---

## 3. 详细发现

### K-01 — P0：`create_task` 可将他身份任务作为幂等结果返回

**判定：确定问题。**

#### 证据与调用链

1. AI 工具从 `ToolContext` 取得裸 `runId`：
   - `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:435-442`
2. 进入：
   - `TaskService.createIdempotent(...)`
   - `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:175-192`
3. 查询只按 `aiCreateRun`：
   - `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:199-203`
   - `backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:40`
4. 命中后，`reuseOrConflict` 直接返回已有任务，没有租户/用户校验：
   - `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:340-349`
5. 并发冲突后的回查同样只按裸 run/key：
   - `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:360-376`
6. 数据库唯一约束也是全局单列唯一：
   - `backend/src/main/java/com/example/configmgr/task/entity/Task.java:14-17`
   - `backend/src/main/resources/db/migration/V1__schema.sql:102-103`
   - `backend/src/main/resources/db/postgresql/V1__schema.sql:102-103`
   - 实际结构：`UNIQUE(ai_create_run)`、`UNIQUE(ai_create_key)`。
7. 返回工具层后，任务 ID 被写入 `CREATED_TASK_ID`，并返回任务标题和步骤：
   - `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:443-456`

#### 跨租户/跨用户场景

租户 A 的 `(tenant=1,user=101)` 已用 `runId=R` 创建任务 T。按照本次硬性口径，另一个身份允许存在相同 `runId=R`。

租户 B 的 `(tenant=2,user=201)` 在相同 `runId=R`、相同创建类型和标题下调用 `create_task`：

- `findFirstByAiCreateRunOrderByIdAsc(R)` 命中 A 的任务；
- `reuseOrConflict` 将其返回为 B 的“幂等复用”；
- B 的工具上下文记录 A 的任务 ID，并看到 A 的任务标题、当前步骤；
- 后续导航、配置或作业发起可继续指向 A 的任务。

若创建键不同，B 也会因 A 的全局唯一行而无法创建自己的任务，并收到包含 A 任务 ID 的冲突信息。

#### 影响

- 直接的跨租户/跨用户任务元数据泄露。
- 后续业务动作可能落到错误身份的任务上，形成完整性破坏。
- 全局唯一约束使受害方无法创建自己的任务。
- “唯一约束证明跨实例幂等安全”的结论在多身份下不成立。

#### 修改建议

- 给 `tasks` 增加不可空的 `tenant_id`、`user_id` 归属列；创建时从可信请求上下文显式写入，不从模型参数或任务标题推断。
- 将约束重划为：
  - `(tenant_id, user_id, ai_create_run)`
  - `(tenant_id, user_id, ai_create_key)`
- 查询改为：
  - `findFirstByTenantIdAndUserIdAndAiCreateRunOrderByIdAsc(...)`
  - `findFirstByTenantIdAndUserIdAndAiCreateKeyOrderByIdAsc(...)`
- `createChecked`、冲突回查、continuation 查询、进程内 `createLock` 全部使用同一 `(tenant,user,run/key)` 身份。
- 旧数据不能统一填入默认租户；无法证明归属的历史行应隔离，由运维显式映射后再开放。
- 迁移前先审计重复值，按身份去重，再删除旧全局唯一约束并建立复合约束。

**后续单点验证（不要在本轮执行）：**

- 两租户、两用户分别使用相同 `runId` 和相同创建参数，必须各自创建并只能回查自己的任务。
- 两实例并发执行同身份同 run 创建仍至多一行；异身份同 run 不得互相触发唯一冲突或复用。

---

### K-02 — P1：`taskId` 资源锁跨身份互相阻塞

**判定：确定问题。**

#### 键结构与调用链

裸键：

```text
ai:lock:task:<taskId>
ai:lock:job:<taskId>:<JOBTYPE>
```

- 唯一键生成口：`backend/src/main/java/com/example/configmgr/ai/run/LeaseKeys.java:47-60`
- 抢任务锁和作业锁：`backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:127-170`
- AI 工具参数中的 `taskId` 直接进入锁键：
  - `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3263-3272`
- 执行顺序：
  - `executeOnce` → 目标校验 → `acquireResourceLocks` → `claimAndExecute`
  - `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2742-2761`
- UNKNOWN 对账也读取同一裸键：
  - `backend/src/main/java/com/example/configmgr/ai/gate/ToolUnknownReconciler.java:113-124`
  - `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:178-187`

#### 场景

租户 A 对 `taskId=7` 执行 IMPORT，持锁 60 秒。租户 B 在自己的任务空间也有 `taskId=7`：

- B 的 `start_import` 命中 A 的 `ai:lock:task:7`；
- B 被结算为 `blocked/RESOURCE_LOCKED`，没有 claim、没有台账；
- B 的 UNKNOWN 对账还可能因为 A 的锁而错误报告“目标任务仍有作业在执行”。

跨用户同租户同样成立。

#### 影响

- 跨租户/跨用户可用性串扰。
- 错误阻断真实工具调用，并污染人工对账结论。
- 不能依赖当前 DB 自增 ID 实际唯一来否定此问题；本次明确要求按“业务 ID 仅租户内唯一”设计。

#### 修改建议

- 锁键改为：
  - `ai:lock:task:<tenantId>:<userId>:<taskId>`
  - `ai:lock:job:<tenantId>:<userId>:<taskId>:<JOBTYPE>`
- `LeaseKeys.task/job`、`ToolResourceLocks.acquire/isHeld` 增加显式 `TenantUser` 参数，从 run 快照或当前真实请求获取，不从 `taskId` 反查猜测。
- `SpToolCallingManager` 和 `ToolUnknownReconciler` 使用同一个身份源。
- 旧裸键只读不写；切换期新路径只查新键，旧键自然 TTL 到期，不应回退到裸键，否则重新打开串扰。
- 若业务确实要求“同租户所有用户共享任务锁”，也必须明确锁定主体是“租户资源”还是“用户会话”；当前硬性口径要求租户+用户隔离，因此默认采用完整身份。

---

### K-03 — P1：任务事件 Pub/Sub 跨身份串帧并泄露配置项名称

**判定：确定问题。**

#### 频道与本地表

频道：

```text
task:events:<taskId>
```

- 前缀：`backend/src/main/java/com/example/configmgr/task/service/TaskEventBus.java:30`
- 发布：`backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:170-190`
- 总线本地 `subscribers/listeners` 仅按 `Long taskId`：
  - `backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:81-85`
- 收消息后直接交给同 taskId 的本地消费者：
  - `backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:263-295`
- `TaskSseService` 的 emitter 表同样仅按 taskId：
  - `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:108-109`
  - 订阅：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:202-221`
  - 投递：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:294-327`

载荷不只是状态码。例如发布作业进度包含：

- `jobId`
- `defCode`
- `currentItem`
- `currentItemName`
- `processed/total`

证据：`backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:378-384`；其余 Import/Export/Precheck 同形。

#### 场景

实例 A 上，租户 A 的 `taskId=7` 正在发布；实例 B 上，租户 B 的同名任务页有 SSE 订阅：

- A 发布到 `task:events:7`；
- B 的总线收到后，以裸 `taskId=7` 找到 B 的 emitter；
- B 页面收到 A 的作业进度、配置项编码和配置项名称。

`origin` 仅用于去除发布实例自身回声，不含身份，不能提供隔离。

#### 影响

- 跨租户/跨用户业务数据泄露。
- 同名任务的前端状态被他人进度污染。
- 多实例部署下这是直接的数据面串扰，而非单纯锁竞争。

#### 修改建议

- 频道重划为 `task:events:<tenantId>:<userId>:<taskId>`。
- `RedisTaskEventBus.subscribers/listeners`、`TaskSseService.emitters`、重试表和订阅锁全部改为复合 `TaskKey(tenant,user,taskId)`。
- `publish` 从作业记录显式携带身份；`subscribe` 从真实请求捕获身份。
- 信封增加 `tenantId/userId`，接收端除频道外再校验一次，防止错误订阅或旧代码误发。
- 调度重试不能仅 `RunIdentityScope.denied()` 后丢失身份；应在注册时保存 `TaskKey`，回调按该身份执行。
- 新旧频道并存期间，新消费者不得订阅旧裸频道；旧频道只在旧发布者耗尽后退役。

---

### K-04 — P1：作业心跳、取消和 runner 协调可跨身份覆盖、误取消、误判死

**判定：确定问题。**

#### 裸 Redis 键和本地表

`JobHeartbeatRegistry`：

```text
job:runner-active:<jobId>
job:beat:<jobId>
job:deadjudge:<jobId>
```

- 常量：`backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:96-103`
- 实际拼键：`backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:743-752`
- 本地 `lastBeatAtMs/runnerGenerations/activeRunners/revokedJobIds/authorityCache` 均按 `Long jobId`：
  - `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:193-233`
- `beginRunner` 对 runner-active 和 beat 直接 `SET`：
  - `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:326-362`

`JobCancellationRegistry`：

```text
job:cancel:<jobId>
```

- `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:79-80`
- 本地 flags/listeners/负缓存也仅按 jobId：
  - `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:66-69`

AI 工具通过 `JobService.createAndStart` 进入此层：

- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:542,561,578,590`
- `backend/src/main/java/com/example/configmgr/job/service/JobService.java:152-171`

#### 场景

两个身份都合法拥有 `jobId=9`：

1. A 的 runner 已登记 `job:runner-active:9` 和 `job:beat:9`。
2. B 调用 `beginRunner(9)`，无条件覆盖这两键。
3. A 后续 heartbeat 使用自己的 generation prefix，与 B 的值不匹配而写不进去；A 可被其它实例判为失联。
4. B 写入 `job:cancel:9` 后，A 的 `isCancelled(9)` 也会返回 true，并可唤醒本地同名 listener。
5. `job:deadjudge:9` 还会让一方阻止另一方的判死单飞。

同实例的 `Map<Long,...>` 也无法同时容纳两个身份的 job 9。

#### 影响

- 跨租户/跨用户取消串扰。
- 活跃作业被误判死，或真实判死被他人锁阻挡。
- runner/heartbeat 状态互相覆盖，破坏跨实例恢复和“不得重复启动”判据。

#### 修改建议

- 引入不可变 `JobKey(tenantId,userId,jobId)`，替换所有 `Long jobId` 本地 Map/Set 和 Redis 键。
- 新键：
  - `job:runner-active:<t>:<u>:<jobId>`
  - `job:beat:<t>:<u>:<jobId>`
  - `job:deadjudge:<t>:<u>:<jobId>`
  - `job:cancel:<t>:<u>:<jobId>`
- `Job` 必须能取得归属：增加租户/用户列，或通过带身份约束的 Task 关系获得；禁止只用裸 jobId 查库后猜身份。
- `beginRunner/endRunner/beat/revoke/isCancelled/tryAcquireDeadJudgement` 全部显式接收 `JobKey`。
- `AsyncJobExecutor`、ticker、watchdog、取消 HTTP 入口、事务回调必须传递同一个 `JobKey`；不能在异步边界只保留 Long。
- host 索引可继续按主机共享，但 field 必须变为 `<t>:<u>:<jobId>`，值继续保留 instance+generation fencing。
- 现有 generation/CAS 机制可保留；它只解决同键换主，不解决键归属错误。

---

### K-05 — P1：fence 未在抢租时推进，旧主可在新主首写前继续推进

**判定：确定问题，静态时序可证明。**

#### 根本原因

`LeaseService.ACQUIRE_SCRIPT` 在新租约写入时只推进计数器和租约：

- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:157-182`
- 新 epoch 写入发生于 `:173-181`，没有同步 `SET fence=epoch`。

各写路径仅比较当前 `fence > expectedEpoch`：

- 快照：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:395-405`
- 工具认领：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:561-587`
- 完成落账：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:626-669`
- 帧归档：`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:278-282`

它们没有在同一 Lua 中校验当前租约值是否仍为 `<epoch>|<owner>`。

#### 具体时序

1. 实例 A 持 `epoch=e`，本地 `held` 尚未到续租失败点，因此 `authority=HELD`。
2. A 的租约实际过期，实例 B 通过恢复入口取得 `epoch=e+1`：
   - `RunStore.acquireRunLease`：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3088-3095`
   - 取消接管示例：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2367-2386`
3. 此时 fence 仍为 `e` 或更低，因为 B 尚未写第一份快照。
4. A 的迟到帧经过：
   - `SseChatEmitter.emitInternal`
   - `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3697-3779`
   - `RedisFrameStore.allocateAndAppend`
   - `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:899-908`
   检查结果是 `fence(e) > epoch(e)` 为假，因此旧帧成功归档。
5. 同样窗口内，A 可通过 `tryClaimExecution`：
   - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3895-3920`
   认领并执行工具。
6. B 首笔写入后 fence 才变为 `e+1`，此后 A 才被拒绝。

#### 影响

- 已被接管的旧主仍能产生业务帧、快照写回和工具副作用。
- 新旧主状态可短暂交错，随后被新主旧快照覆盖，造成进度回拨或重复/错序恢复。
- `CancelledRunFinalizer` 的多实例收尾也继承同一窗口。
- 这不是跨租户键冲突，而是同身份 run 的跨实例 fencing 缺口。

#### 修改建议

首选在 `ACQUIRE_SCRIPT` 内原子推进 fence：

```lua
-- 新建 epoch 后、返回前
redis.call('SET', KEYS[3], tostring(epoch), 'PX', ARGV[3])
```

- 新 epoch 必须大于现有 fence 后，立即把 fence 提到该 epoch。
- 命中现有有效租约时，如 fence 低于 `curEpoch`，也应在同一脚本补齐。
- 这样 B 一取得 `e+1`，A 的所有 `epoch=e` 写脚本立即失败，不再依赖 B 的首笔业务写。
- 为纵深防御，可在快照、claim、complete、frame 脚本中额外加入当前租约键及 expected token 校验；但“抢租即推进 fence”是关闭本窗口的最小统一修复。
- 无需改键形或迁移数据；fence 和计数器仍是同槽、同 TTL。
- 升级时新旧代码兼容：新实例一旦接管并推进 fence，旧实例的高延迟写会被拒绝。

**后续单点验证：**

- A 持 e 后模拟失租，B 抢 e+1 但暂停在任何业务写之前；A 的快照、帧、claim、ledger 必须全部失败。
- 特别覆盖“B 已抢租但尚未保存”的窗口，而非只测“B 已写 DONE 后”。

---

### K-06 — P1：实际存在并依赖全租户目录和循环静态注册表

**判定：确定问题，违反本次明确部署口径。**

#### 全量接口

- `GET /api/ai/tenants`：
  - `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`
- 返回全部预置租户及用户，且无需身份头：
  - 类注明确说明，`backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:11-16`
  - 过滤器排除：`backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68-69`

#### 静态注册表循环

- 静态完整目录：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-41`
- 每个请求通过循环 `TENANTS` 校验：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:45-57`
  - 调用点：`backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:144-147`

#### 前端启动依赖

- 前端加载该接口：
  - `frontend/src/stores/ai.ts:10842-10860`
- 无目录、空目录或请求失败会禁用发送：
  - `frontend/src/stores/ai.ts:10818-10819`
  - `frontend/src/stores/ai.ts:10870-10875`
- 无存档身份时自动使用目录第一租户的第一用户：
  - `frontend/src/stores/ai.ts:10876-10884`

因此这不是一个孤立的展示接口；当前 Web Lock、会话锁和工具锁的身份来源链依赖全量目录可用。

#### 影响

- 与“生产没有循环/全量租户列表能力”直接冲突。
- 未认证暴露租户名、用户 ID 和组合关系。
- 移除该接口按当前实现会导致整个 AI 对话不可用。
- 自动落第一身份不符合“身份只来自真实请求”的要求。

#### 修改建议

- 生产删除 `/api/ai/tenants` 和静态 `TENANTS` 注册表，不以“演示目录”名义保留生产路径。
- 从认证后的真实请求 principal/token/session 中解析 tenant/user；不要用全量目录证明身份存在。
- 前端没有真实身份时保持未就绪并禁用发送，不得默认选择第一租户。
- 如确需短期缓存，只缓存“本真实请求已验证的身份”，键含 tenant+user，并设置短 TTL；不得通过枚举填充缓存。
- 启动恢复只使用记录中已固定的身份，不通过目录反查。
- 部署迁移应先让前后端支持 principal 身份，再关闭目录接口，最后删除静态目录。

---

### K-07 — P1：前端本地单飞键绕过完整 Web Lock 身份并误抑制执行

**判定：确定问题。**

#### 正确的锁与错误的本地键

Web Lock 本身是完整身份：

```text
ai-frontend-tool:<tenant:user>:<sessionId>:<runId>:<toolCallId>
```

- `frontend/src/stores/ai.ts:2114-2138`

本地 journal 也使用同一复合身份：

- `frontend/src/stores/ai.ts:1966-1975`

但在到达 Web Lock 之前，还有：

```ts
const frontendToolsInFlight = new Set<string>()
```

- 仅按 `toolCallId`：`frontend/src/stores/ai.ts:1944-1945`
- 检查、添加、删除：`frontend/src/stores/ai.ts:12854-12865`
- 恢复驱动也使用裸 toolCallId：
  - `frontend/src/stores/ai.ts:6018-6062`

#### 场景

1. 身份 A 的 run/session 中，`toolCallId=X` 的慢前端工具仍在执行。
2. 用户切换身份或会话，进入身份 B 的新 run；模型也生成 `toolCallId=X`。
3. B 的 `runFrontendTool` 首先命中 `frontendToolsInFlight.has(X)` 并直接返回。
4. 正确的复合 Web Lock 根本没有机会执行。
5. B 的后端 pending 等不到回灌，只能超时或走不确定恢复。

身份切换时清理了 recovery 和队列，但**没有清理这个 inFlight 集合**：

- `frontend/src/stores/ai.ts:11197-11204`

旧操作继续运行是合理的，但它们不应以裸 ID 阻塞新身份操作。

#### 影响

- 跨租户/跨用户/跨会话工具执行被错误抑制。
- 后端等待与前端实际状态分叉。
- 与“toolCallId 只在单轮内唯一”的既有约束直接冲突。

#### 修改建议

实现统一 helper，例如：

```text
FrontendToolKey = encode(identity) + ":" + sessionId + ":" + runId + ":" + toolCallId
```

所有 `has/add/delete` 必须使用该键，包括恢复路径。不要为修复而简单清空旧 inFlight；旧身份任务应携带自己的复合键独立收尾。该集合是内存态，无存储迁移。

---

### K-08 — P2：`recoveryEntriesByRun` 的执行事实缓存只有裸 runId

**判定：风险；有外围清理缓解，但键级隔离不成立。**

- 定义和读写：`frontend/src/stores/ai.ts:2050-2064`
- 前端工具恢复时据此判断 `settled/claimed/not-executed`：
  - `frontend/src/stores/ai.ts:12911-12950`
- 在 `adoptFreshSession` 中有全量清理：
  - `frontend/src/stores/ai.ts:11201-11203`

问题在于正确性依赖“所有身份切换路径都及时清表且没有迟到回包重写”，而不是缓存键本身。若旧身份的异步 `runsCurrent` 在清理后重新 `rememberRecoveryEntries(runId,...)`，或未来新增一条恢复路径漏清理，同 runId 的另一身份会继承别人的执行事实，可能误跳过或误允许工具。

**建议：**

- Map 改为 `(identity,sessionId,runId)` 复合键。
- `remember/get/clear` 全部使用同一 key 类型；禁止提供只按 runId 的便捷重载。
- 保留身份切换清理作为内存回收手段，但不再把它当作隔离判据。
- 与 K-07 使用同一个 `FrontendToolKey` 基础实现，避免两套键形再次漂移。

---

### K-09 — P2：资源锁故障放行存在无锁执行窗口，当前仅由 DB 守卫缓解

**判定：风险，不是当前四工具已证重复创建。**

`ToolResourceLocks` 明确规定 `UNAVAILABLE` 放行：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:30-40`
- 任一把锁不可用仍返回允许执行的句柄：
  - `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:139-170`
- 随后进入 `claimAndExecute`：
  - `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2751-2757`

可能的时序是：锁读取短暂失败 → `UNAVAILABLE` → Redis 很快恢复 → claim 成功 → 执行时实际没有资源锁。百实例同时经历该窗口时，进程内也没有等价互斥。

当前四个 `start_*` 工具的 DB 行锁和在途作业检查提供第二道保护：

- `backend/src/main/java/com/example/configmgr/job/service/JobService.java:152-160`
- `backend/src/main/java/com/example/configmgr/job/service/JobService.java:292-323`

因此未证明当前会产生重复作业；但资源锁自身承诺被削弱，且未来无同等 DB 守卫的工具会继承风险。

**建议：**

- 对 DANGER 作业，锁状态不可知应有界重试后 fail-closed，不进入 claim。
- 只在明确只读、可重放的操作上保留 fail-open。
- 保留 DB 行锁作为最终防线，不把 Redis 锁重新当作唯一正确性来源。
- 单点验证“锁失败—恢复—claim 成功”的交错，而不只测持续 Redis 宕机。

---

### K-10 — P3：跨身份同名 run 会互相抑制 fencing 告警

**判定：确定问题，仅影响观测。**

裸 runId 集合：

- `fenceAheadWarned`
- `readOnlyWarned`
- `fenceStaleWarned`

位置：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:330-347`

使用点包括：

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1892-1908`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3135-3149`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3197-3207`

若 A 的 runId 已触发 WARN，B 的同 runId  fencing 事件会降为 DEBUG，导致接管/旧主写等关键事故难以发现。

**建议：** 日志节流键改为 `LocalRunKey.of(runId)` 或直接采用完整身份 Redis key；这只改变观测，不影响协调状态。

---

## 4. 已证相对安全的部分

以下结论仅限已核对的代码路径，不代表运行时测试通过。

### Run 级键和值来源

- `RunKeyScope` 统一生成 `<prefix>{<tenant>:<user>:run:<runId>}`，无身份直接失败：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:37-71`
- 快照创建同时冻结 tenant/user：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1245-1256`
- 快照写回会拒绝记录身份与当前身份不一致：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1481-1492`
- `claim/pending/fence/cancel/ledger/idem/req/decided/seq/xevents` 均经该身份拼接口；不是仅键名“碰巧包含 tenantId”。

### AI 帧唤醒

- 频道为 `ai:wake:<tenant>:<user>:<runId>`：
  - `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:272-283`
- 发布与订阅缺少身份会跳过，不生成裸 run 频道：
  - `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:137-148,226-242`
- Redis 回调按捕获身份建立作用域：
  - `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:237-241`
- `FrameWakeListener` 桶和 Registration 都含身份，退订使用精确句柄：
  - `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java:67-84,167-172,199-230,304-357`
- 消息只有 seq 上界，不含帧正文；最终真值是身份隔离的归档，重复/迟到 wake 只做差量读取。
- `ai:lock:resume-scan` 和 `ai:purge:retry:lock` 是全局基础设施单飞，不承载对话数据，可保留全局命名。

### 取消与 finalizer

- `CancellationRegistry` 的 Redis slot、本地 flags/listeners/degradedCancels 全部使用身份化 cancel key：
  - `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:110-120,134-180`
- 读失败返回 `UNKNOWN`，没有折叠成“未取消”。
- `CancelledRunFinalizer` 的 `inFlight/attempts` 使用 `LocalRunKey`：
  - `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:247-269`
- 身份随执行和重试显式传递：
  - `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:287-301,369-382,426-438`
- 裸 runId 的 monitor stripe 只损失并行度，不共享业务状态，属于安全设计。

### Lease 基本所有权

- renew/release/verify 都按完整 `<epoch>|<owner>` 原子比对：
  - `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:204-234`
- 进程内 `held/driving/ended` 按 `key + \0 + owner`，owner 缺省为随机实例 ID。
- `ai:lease:epoch` 全局计数只用于无 tag 资源锁 token，不参与 fencing，可视为基础设施编号而非隔离数据。
- K-05 是“抢租后 fence 推进时机”的缺陷，不否定上述 token 释放保护。

### 恢复扫描与上下文传播

- 全局 `ai:recovery:due` 的成员是 `tenant/user/runId`，不是租户列表：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunKeyScope.java:78-99`
- 系统扫描逐条按记录身份执行：
  - `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1206-1235`
- 请求面只处理当前身份，GET 报告也按身份切片：
  - `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:409-442,617-640`
- `RunIdentityScope.open` 对旧 0/0 记录显式清空身份，不沿用池线程残留：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:46-81`
- AI 心跳和业务活动键为 `ai:beat:<t>:<u>:<run>`、`ai:activity:<t>:<u>:<run>`：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4870-4873,5314-5317`

### 浏览器锁

- 会话锁名含身份：`frontend/src/utils/ai-identity.ts:171-179`
- 前端工具锁和 journal 含 identity/session/run/tool：
  - `frontend/src/stores/ai.ts:1966-1975,2114-2138`
- BroadcastChannel 虽使用全局频道名，但消息带 tenant/user，接收端要求完全匹配：
  - `frontend/src/stores/ai.ts:1283-1297,1314-1326`
- 无 Web Locks 时普通前端工具不自动执行，只有幂等 `navigate_to` 例外：
  - `frontend/src/stores/ai.ts:12887-12897`

---

## 5. 本领域租户枚举/列表依赖清单

### 明确存在，必须整改

1. `GET /api/ai/tenants` 全量目录接口：
   - `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`
2. 静态全量 `TENANTS` 注册表及 `exists()` 循环：
   - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-57`
3. 前端启动加载、默认第一身份及目录不可用即禁用：
   - `frontend/src/stores/ai.ts:10808-10884`

### 不属于租户列表依赖

- `ai:recovery:due`：按到期记录枚举，成员自带 tenant/user/runId；不是循环租户列表。
- `ai:purge:retry:due`：member 是带身份的 pending 记录键，按台账到期处理，不枚举租户。
- `JobHeartbeatRegistry` 的 host index：按主机清理本机 runner，不是租户目录；但其裸 jobId 问题仍按 K-04 处理。
- 请求面 `ai:runs:<tenant>:<user>`：只枚举当前真实身份的记录。

除上述静态目录外，本领域未发现其它“循环租户列表/获取全部租户列表”的协调设计。

---

## 6. 建议修复顺序与后续验证

1. **先封住跨身份数据面**：K-01、K-03、K-04。先补归属模型和 DB 约束，再改 Redis/PubSub/内存键，避免只改键名而异步路径仍无法取得身份。
2. **关闭旧主写窗口**：K-05，改动小且兼容现有键，应优先合入。
3. **移除目录依赖**：K-06，先让真实请求身份贯通，再下线目录。
4. **统一前端工具复合键**：K-07、K-08。
5. **处理降级和观测**：K-02、K-09、K-10；K-02 虽列后，但键迁移应与 K-01 的任务归属改造同批完成。

建议后续只做以下单点验证，不在本轮执行：

- 两租户、两用户、同 task/job/run/toolCallId 的键与频道不交集。
- 同 runId 的 `create_task` 在异身份下各自创建、回查和冲突收敛。
- 100 实例逻辑时序中，旧 epoch 在新 epoch 抢租后立即失去所有写权限。
- Pub/Sub 迟到消息、重复消息、取消跨实例、实例死亡恢复均只影响目标身份。
- 异步 ticker、续租、finalizer、SSE 回调在池线程连续服务不同身份时不残留上下文。

## 7. 未判定事项

- 未运行真实 Redis Cluster、100 实例压力或浏览器测试；K-05 的结论是代码时序证明，不是运行时复现。
- `RunStore` 僵尸判断使用帧产生节点的墙钟，代码明确要求 NTP：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4851-4854,5292-5295`
  当前部署时钟偏差无法判定；应作为上线前环境检查。
- 现有 Task/Job 历史数据没有可证明的租户/用户归属，不能自动安全回填；迁移需要显式归属清单或隔离旧行。
