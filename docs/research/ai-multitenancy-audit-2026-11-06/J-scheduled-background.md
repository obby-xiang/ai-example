# J. 定时与后台任务专项独立审查报告

## 1. 身份证据与审查边界

- `agentId`：`agent-949`
  - 证据：`<会话证据根>/agents/agent-949/wire.jsonl:2`
- `profile.bind`：`modelAlias=kimi-code/k3.1-pro`
  - 证据：同文件第 **6** 行。
- 首条 `llm.request`：`modelAlias=kimi-code/k3.1-pro`、`model=k3.1-pro`
  - 证据：同文件第 **20** 行。
- 身份门禁：**通过**，无 `IDENTITY_FAIL`。
- 仓库 HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。
- 范围：启动/周期续跑、会话清理重试、任务看门狗/启动恢复、心跳清理、知识库刷新、AI 配置种子，以及相关单轮定时器与直接调用链。
- 未修改代码或文档，未运行测试、批量脚本、服务或 Git 写操作。以下均为静态代码审查结论。

## 2. 结论汇总

| 编号 | 严重度 | 判定 | 问题 |
|---|---|---|---|
| J-1 | P1 | 确定问题 | `jobs/tasks` 无租户/用户身份，后台扫描、恢复、心跳、终态写入均无法建立记录身份；AI 创建唯一约束还造成跨租户冲突 |
| J-2 | P1 | 确定问题 | `JobWatchdog` 在终态动作成功前写入本机“已判”集合，失败后后续周期不再重试 |
| J-3 | P2 | 确定问题 | 启动/运行期续跑没有恢复记录语言，无请求语言被固定为中文 |
| J-4 | P2 | 风险/无法判定 | 没有租户删除/停用协议；若外部删除租户，旧 run 仍可被后台续跑 |
| J-5 | P2 | 条件性风险 | 多实例共用 KB 索引目录时，会互删对方的 `build-*` 活跃索引 |
| J-6 | P2 | 风险 | 百实例均全量扫描在途作业；判死租约不续期且 issue 无唯一约束 |
| J-7 | P3 | 风险/加固项 | 会话清理发现成员与 JSON 台账未做键值一致性校验，损坏数据可引导到另一身份 |

未发现 P0。

---

## 3. 详细问题

### J-1：任务/作业后台链没有租户+用户身份模型

**严重度：P1；确定问题。**

#### 证据

- `Job` 只有全局自增 `id`、`taskId` 等字段，没有 `tenantId/userId`：
  `backend/src/main/java/com/example/configmgr/job/entity/Job.java:15-27`
- `Task` 同样没有身份字段；其 AI 创建幂等列被声明为**全局唯一**：
  `backend/src/main/java/com/example/configmgr/task/entity/Task.java:14-18`
  `backend/src/main/java/com/example/configmgr/task/entity/Task.java:42-56`
- 数据库约束也是裸 `UNIQUE(ai_create_run)`、`UNIQUE(ai_create_key)`，不是身份内唯一：
  `backend/src/main/resources/db/migration/V1__schema.sql:90-103`
- 看门狗扫描只按状态，无身份投影或条件：
  `backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java:57-62`
- 启动恢复同样扫描全部在途行，后续只按 `jobId/taskId` 锁和更新：
  `backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java:42-55`
  `backend/src/main/java/com/example/configmgr/job/service/StaleJobRecoveryRunner.java:98-120`
  `backend/src/main/java/com/example/configmgr/job/service/StaleJobRecoveryRunner.java:141-176`
- 心跳和判死键只有 `jobId`：
  - `job:runner-active:<jobId>`
  - `job:beat:<jobId>`
  - `job:deadjudge:<jobId>`
  `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:96-103`
  `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:743-756`

#### 完整调用链

- 周期判死：
  `JobWatchdog.start → scanSafely → scan → JobRepository.findByStatusIn → judge → JobTerminalWriter.complete → TaskService.applyJobOutcome`
- 启动恢复：
  `StaleJobRecoveryRunner.run → findInFlightRefsAfter → recoverOne → tryLockForUpdate(taskId) → isRunnerAlive(jobId) → finishInterruptedIfInFlight(jobId) → applyJobOutcome(taskId)`
- 心跳：
  `beginRunner(jobId) → ensureTicker → tickSafely → tickOnce → databaseInFlight(jobId)/writeHeartbeatBatch(jobId)`

#### 具体场景与影响

1. 租户 A、B 合法使用相同 `runId/aiCreateKey` 时，B 的第二条任务记录会撞上全局唯一约束，形成确定的跨租户干扰。
2. 任意实例的看门狗都会读取并修改所有租户的在途作业，但整条链无法回答“该记录属于哪个租户、哪个用户”，也无法在 DB 更新时附加归属条件。
3. 自增 `jobId/taskId` 在单库内全局唯一，**所以当前证据不等于看门狗会误更新另一租户的同名 ID 行**；问题是隔离模型和唯一约束本身不满足硬性口径，且后续分库、迁移或按业务 ID 处理时没有可用身份。

#### 修改建议

- 给 `tasks/jobs` 增加非空 `tenant_id/user_id`；直接按作业查询的 `validation_issues` 可冗余同样字段，或通过带身份条件的 job join 核验。
- 将唯一约束改为：
  - `(tenant_id, user_id, ai_create_run)`
  - `(tenant_id, user_id, ai_create_key)`
- 后台扫描投影必须同时返回 `id/taskId/jobType/tenantId/userId`，逐条开启 `RunIdentityScope`；`finishInterruptedIfInFlight`、`finishIfInFlight`、`tryLockForUpdate` 等写查询增加身份谓词。
- 新键建议：
  - `job:<tenantId>:<userId>:runner-active:<jobId>`
  - `job:<tenantId>:<userId>:beat:<jobId>`
  - `job:<tenantId>:<userId>:deadjudge:<jobId>`
  - 主机 HASH field 改为 `<tenantId>:<userId>:<jobId>`。
- 不应通过循环租户列表迁移。存量行从原始 run 快照、任务创建上下文等真实记录回填；无法证明归属的行进入隔离/人工处理集合，不归入默认租户。
- 滚动迁移顺序：先加可空列并双写身份 → 按记录回填 → 新键双写/按已核验归属旧键兼容读 → 查询和锁切到复合身份 → 非空约束及复合唯一约束 → 停写旧键。

---

### J-2：看门狗终态写失败后不再重试

**严重度：P1；确定问题。**

#### 证据与调用链

- 每拍先跳过本机已判作业：
  `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:214-228`
- 在 issue 和终态写之前就执行 `judgedJobIds.add(jobId)` 并增加成功计数：
  `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:368-395`
- `terminalWriter.complete` 在终态提交前失败会抛出：
  `backend/src/main/java/com/example/configmgr/job/service/JobTerminalWriter.java:103-147`
- 只要 DB 行仍是 `PENDING/RUNNING`，`retainTracked` 就会保留这个本机判重标记：
  `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:233-254`

#### 触发场景

1. 实例 A 判定 job 超时，先写入 `judgedJobIds`。
2. issue 写成功后，DB 瞬断导致 `terminalWriter.complete` 在提交 job 终态前失败。
3. 后续每拍 A 都跳过该 job，不再尝试终态写，也不再设置取消标志。
4. 单实例时会一直卡到进程重启；多实例时只能等 `job:deadjudge` 到期后由另一实例成功。如果其它实例也遇到同类失败，它们也会各自留下“已判”毒化状态。

#### 影响

- 作业长期处于在途状态，任务态不收敛，执行线程可能继续运行。
- 指标在真实终态写成功前就增加，观测会夸大完成判死/判超时的数量。

#### 修改建议

- 将“正在判定”“issue 已写”“终态已收敛”拆成不同状态；只有终态写成功后才记录 converged。
- 失败路径必须移除本拍判重标记或改为短退避重试状态，不得永久跳过。
- 为 watchdog verdict 增加持久幂等记录，例如 `(tenant_id,user_id,job_id,verdict_type)` 唯一约束；issue 插入与终态推进分开幂等，重试只补缺失步骤。
- 继续保留 DB 条件 UPDATE 先到者胜，但不要把“防重复 issue”建立在“永不重试终态”之上。
- 后续单点验证：单实例终态写前 DB 瞬断、issue 成功后失败、多实例租约到期接管、重试不得重复 issue/事件/计数。

---

### J-3：后台续跑语言被固定为中文

**严重度：P2；确定问题。**

#### 证据与调用链

- 扫描调用：
  `StartupResumeRunner.runPipeline → resumeWithRunIdentity → ResumeService.resume(runId,takeover)`
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:728-732`
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1223`
- `resume` 经 `resumeWithReason` 取 `ErrorBodyFactory.requestLocale()`：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:818-829`
- 无请求上下文时该方法不是返回 `null`，而是固定返回简体中文：
  `backend/src/main/java/com/example/configmgr/common/ErrorBodyFactory.java:116-127`
- `RunSnapshot` 固化租户/用户，但没有 locale/language 字段：
  `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java:83-126`
- 缺少 SYSTEM 时，重建提示会使用当前线程语言；驱动线程也显式设置上述 locale：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1327-1335`
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752-1763`
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2227-2272`
- 语言指令确实进入系统提示：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4958-4983`

#### 触发场景与影响

- 英文用户的 run 在后台续跑且快照因旧窗口/压缩丢失 SYSTEM 时，重建提示会改为中文语言指令。
- 即使 SYSTEM 仍在，后台失败文案也会按默认中文生成，而不是原轮语言。
- 注释所称“无请求上下文 ⇒ null、不设置 locale”在这条实际调用链上不成立。

#### 修改建议

- 在 `RunSnapshot` 增加不可变 `replyLocale`，创建轮次时与租户/用户一起冻结。
- 启动/运行期扫描从快照读取该值并显式传到 `resumeWithReason(..., replyLocale)` 和驱动线程；不要读取调度线程的 locale。
- 旧快照迁移：优先从已有权威 SYSTEM 的语言指令恢复；无法判断且又缺 SYSTEM 时，应跳过自动续跑并报告 `LOCALE_UNKNOWN`，或明确产品默认，不能静默把英文轮改成中文。
- 后续单点验证：同一调度线程连续处理中英文轮、英文轮缺 SYSTEM、后台失败文案、池线程复用后无 locale 残留。

---

### J-4：租户删除后的旧 run 仍可续跑

**严重度：P2；风险/删除语义无法判定。**

#### 证据

- 身份目录是静态清单，仅提供 `tenants()/exists()`，没有删除/停用生命周期：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-57`
- 后台续跑只按索引/快照身份开作用域，不检查租户当前是否仍有效：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1248`
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1140-1148`

#### 场景与影响

若生产由外部系统删除或停用租户，该租户已进入 `ai:recovery:due` 的旧 run 仍可能被任一实例续跑，继续调用模型及业务工具。代码内没有租户删除接口，因此无法从仓内确定期望是“继续完成”“取消”还是“立即清理”。

#### 修改建议

- 明确租户/用户删除协议。删除事件应携带已知身份并写入 identity tombstone，不能通过枚举全部租户发现删除。
- 恢复扫描在处理复合成员时检查该身份的 tombstone；命中后取消/终止并移除恢复候选，不驱动模型和工具。
- 会话销毁重试应继续允许，以便删除残留数据，不应被 tombstone 阻断。
- 只缓存从真实请求或删除事件获得的身份状态，不引入全租户列表缓存。
- 后续验证：删除前已存在的到期 run 不再续跑，删除后清理仍能完成，其它租户不受影响。

---

### J-5：共享 KB 索引目录时多实例会互删索引

**严重度：P2；条件性风险，取决于部署文件系统。**

#### 证据与调用链

`kb-refresh → refreshNow → performRefresh → newBuildDir → cleanupStaleBuildDirs`

- 每次刷新都会列出索引目录，删除所有不是**本实例当前快照**的 `build-*`：
  `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:443-468`
- 默认目录是固定相对路径 `./data/kb-index`：
  `backend/src/main/resources/application.yml:324-328`
- 样例播种有文件锁，但 Lucene 构建目录清理没有跨实例活跃目录登记：
  `backend/src/main/java/com/example/configmgr/ai/kb/KbSampleSeeder.java:108-119`

#### 场景与影响

实例 A、B 共用该目录时，A 的活跃索引对 B 而言仍是“非本实例快照”。B 刷新可将其递归删除，导致 A 检索/读取失败。若实例各自拥有独立容器文件系统，则此问题不触发。

#### 修改建议

- 最简单的是 `index-dir/<instanceId>/build-*`，每实例只清理自己的目录。
- 若必须共享索引，需持久登记活跃构建目录及实例租约，禁止删除其它实例仍持有效租约的目录。
- 从共享旧目录迁移到实例子目录时保留一份只读旧快照，待新索引构建成功后切换。
- 后续验证：两个真实进程共用文件系统，交错刷新和检索，确认不会互删；独立文件系统部署也记录为明确前提。

---

### J-6：百实例全量 DB 扫描及判死幂等窗口

**严重度：P2；风险。**

#### 证据

- 每个实例每拍都执行全量 `findByStatusIn`，且强制不得分页：
  `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:200-214`
- 返回完整 `Job`，会水合 EAGER `items` 与 `result_json`：
  `backend/src/main/java/com/example/configmgr/job/entity/Job.java:39-47`
- 判死单飞只是 `SET NX PX`，没有续租；过期后不删除：
  `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:585-623`
- `validation_issues(job_id,def_code)` 只有普通索引，没有唯一约束：
  `backend/src/main/resources/db/migration/V1__schema.sql:162-172`

#### 场景与影响

- 目标百实例会产生约百倍的相同在途 DB 查询和实体水合，即使最终只有一个实例能判死。
- 单次判定超过租约 TTL 后，另一实例可再次获得判死权；条件终态更新可防重复终态，但不能防重复 issue。

#### 修改建议

- 使用轻量标量投影和分页/keyset 扫描；判重状态不要依赖“本拍必须拿到全量实体集合”。
- 可增加全局扫描租约或分片认领，但这只是降载，正确性仍应由每作业持久幂等保证。
- 判死动作期间续租，或更可靠地使用 DB verdict 唯一记录，避免把幂等完全寄托在 TTL 上。
- 后续验证：百实例扫描 DB QPS/堆占用，以及慢 DB 使判定超过 TTL 时 issue 是否唯一。

---

### J-7：清理发现成员与台账身份未交叉校验

**严重度：P3；风险/加固项。**

#### 证据与调用链

`SessionPurgeRetryRunner.runOnce → SessionPurgePendingStore.listDue → scopeOf(pending) → AiSessionPurgeService.retry`

- `listDue` 只检查 member 前缀和 JSON 中身份是否存在，没有检查 `pending.key().equals(member)`：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:565-627`
- runner 随后完全采用 JSON 中的身份：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:280-311`
  `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:387-396`

正常 `register` 会先算同一身份键再写 JSON，正常路径一致：
`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:451-465`

#### 场景与影响

若旧版本、人工修复或损坏数据使 due member 指向 A 的键，而 JSON 是 B 的台账，runner 会按 B 的身份继续，而不是发现 A/JSON 不一致。该问题需要数据污染作为前提，不是正常请求可直接触发，因此为 P3。

#### 修改建议

- `listDue` 必须同时验证：
  - `pending.sessionId()` 与预期记录一致；
  - `pending.key()` 与 due member 逐字相同。
- 不一致时只做隔离，不进入 `scopeOf/retry`。
- 可参照 `RunStore.readSnapshot` 对“键身份 vs 记录身份”的复核：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2636-2661`

---

## 4. 已证安全或基本安全的链路

### 启动/运行期恢复的身份与键隔离

- `ai:recovery:due` member 为 `<tenantId>/<userId>/<runId>`，解析拒绝无身份成员：
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:78-131`
- 写索引时身份来自快照，不是扫描线程：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1762-1785`
- 无身份系统扫描使用全局工作索引；请求扫描使用 `ai:recovery:due:<tenant>:<user>`，不是循环租户：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3291-3321`
- 每个候选在记录身份下构造，快照读取还核对“键身份与记录身份”：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:488-503`
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2636-2661`
- 手工请求会过滤其它身份；系统动作用显式记录身份开启作用域：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:616-640`
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1248`
- 快照写入会拒绝记录身份与当前身份不一致：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1468-1497`
- 扫描锁区分系统全局锁与 `<tenant>:<user>` 请求锁；每轮租约也含身份：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:994-998`
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1399-1403`
- 启动报告按身份切片，不直接暴露全局 runId：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:397-452`
  `lastSweep()` 没有生产调用方，当前未发现对应的跨身份 HTTP 暴露。

### 会话清理

- pending JSON 固化身份，due member 是完整身份键；runner 逐条恢复身份，不使用默认租户。
- 每条清理另有 `executionOwner/epoch/purgeId` 租约和阶段续期，不只是全局 30 秒锁：
  `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:436-473`
  `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:1598-1628`
- DB 删除使用 `session_id + tenant_id + user_id` 三元组：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:70-90`
- run 删除前再次核对快照归属，跨身份/无身份记录不删：
  `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:617-645`
  `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:1178-1185`
- staging 使用会话精确索引及同槽删除，不需要扫描租户或全键空间：
  `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:321-369`

### 单轮定时任务上下文

- `SessionGate` 在 holder 中保存锁键和身份，每拍显式恢复：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:722-766`
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:816-863`
- `StreamWatchdog` 在创建时抓取身份，判定节拍和取消轮询均显式恢复：
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:120-159`
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:191-198`
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:279-293`
- `TurnKeepalive` 同样显式保存并恢复身份：
  `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:68-69`
  `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:189-196`
- attach 保活的纯本地心跳不读 Redis；归档对账另按 `deliveryIdentity()` 开作用域：
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:1054-1086`
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:1123-1132`
- 取消收尾重试以 `LocalRunKey(identity,runId)` 去重，执行与重试均携带身份：
  `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:247-268`
  `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:369-382`
  `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:426-438`

### 其它后台入口

- `RunnerActiveKeyCleanupRunner` 只 HSCAN 本机 HASH，不扫全 Redis 键空间；删除前复核主机/PID和当前值。
- `StaleJobRecoveryRunner` 的 task 行锁和条件 UPDATE 能防多实例重复终态联动，但不能解决 J-1 的身份缺失。
- KB Redis 缓存使用刷新 token、续租和 generation fencing；`kb:cache:docs` 是共享文档索引，不是租户列表。
- `AiConfigSeeder` 处理的是全局模型配置而非对话数据；`config.version` 行锁、provider code 真实唯一约束及并发冲突处理构成多实例播种护栏：
  `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:107-127`
  `backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java:35-59`
- 通用 `DataSeedRunner` 播的是共享主数据，不承载对话身份；其 `SeedLock` 是 DB 行锁，未发现租户枚举依赖。

## 5. 本领域全部租户枚举/列表依赖

**未发现 J 领域后台任务调用“获取全部租户列表”或循环 `TenantUserDirectory.tenants()` 的代码。** 仓内 `/api/ai/tenants` 和静态目录存在，但本领域未消费它们。

需要区分的全局工作发现面如下：

| 发现面 | 是否租户列表依赖 | 结论 |
|---|---|---|
| `ai:recovery:due` | 否，复合 run 工作索引 | 身份来源完整，按记录恢复 |
| `ai:purge:retry:due` | 否，复合 pending 工作索引 | 正常路径安全，需 J-7 一致性加固 |
| `jobs.findByStatusIn` / `findInFlightRefsAfter` | 不是 tenant API，但确实扫描全部在途作业 | 缺身份，构成 J-1 |
| `job:runner-active-host:<host>` | 否，仅本机 runner 索引 | 不扫全键空间 |
| `kb:cache:docs` | 否，共享文档 ID 索引 | 不承载用户对话身份 |
| `ai_provider`、模型及 `ai_setting.findAll` | 否，全局共享配置 | 非对话数据 |
| 静态 region/project/seed 编码循环 | 否，主数据播种清单 | 不是租户枚举 |

总体上，恢复与会话清理已经采用“**全局工作索引携带复合身份，逐条恢复**”的正确方向，无需也不应改成循环租户；主要缺口集中在通用 job/task 后台模型、看门狗重试语义和语言/删除上下文。
