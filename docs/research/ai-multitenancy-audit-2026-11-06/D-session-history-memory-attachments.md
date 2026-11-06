# D 领域独立审查报告：会话 / 历史 / 记忆 / 附件

## 1. 身份门禁与审查边界

**IDENTITY_PASS**

证据文件：`<会话证据根>/agents/agent-943/wire.jsonl`

实际 `agentId=agent-943`。

| 行号 | 证据 |
|---|---|
| `:2` | `environment.set_binding`，`agentId=agent-943` |
| `:6` | `profile.bind`，`agentId=agent-943`，`modelAlias=kimi-code/k3.1-pro` |
| `:20` | 首条 `llm.request`，`modelAlias=kimi-code/k3.1-pro`，`model=k3.1-pro` |

均符合预期。

仓库 HEAD 已核实为 `17080859f7b6905aafbbea90f6d2281913bcab9e`。审查全程只读；未修改代码、未运行测试或批量脚本、未执行 git 写操作、未启停服务。审查结束时 `git status --short` 为空。

**结论汇总：P0×1、P1×2、P2×1、P3×1。**

最重要的结论是：**Redis 键和 DB 查询大多正确地带了租户+用户，但 HTTP 身份可以直接由请求方伪造，导致这些隔离在真实攻击面整体失效。**

---

## 2. 确定问题与风险

### D-01　P0／确定问题：身份头可伪造，且未认证接口直接列出全部可用租户/用户，能够读取、篡改和销毁其它身份的数据

#### 证据

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118-149`
  - 直接读取 `X-Tenant-Id`、`X-User-Id`。
  - 只检查数字范围及 `TenantUserDirectory.exists(...)`，随后建立权威请求上下文。
  - 没有登录态、签名、令牌、会话 Principal 或网关联名验证。
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-57`
  - 静态列出所有租户和用户；`exists` 只是检查请求方自报组合是否在清单内。
- `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`
  - `GET /api/ai/tenants` 返回完整目录。
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68-69`
  - `/api/ai/tenants` 被明确排除在身份边界之外。
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:3071-3090`
  - 以自报身份读取反馈列表，响应包含 `sessionId`、`messageId` 和正文摘录，给后续攻击提供直接入口。
- `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:319-350`
  - 历史读取正确使用当前上下文，但当前上下文本身已被伪造。
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`
  - 同一自报身份可以调用完整会话销毁。

#### 完整攻击链

1. 未认证调用 `GET /api/ai/tenants`，得到受害者的合法 `tenantId/userId`。
2. 携带伪造的 `X-Tenant-Id`、`X-User-Id` 调用 `GET /api/ai/feedback`，得到该身份的会话 ID、消息 ID 和正文摘录。
3. 调用 `GET /api/ai/history/{sessionId}`，读取该会话完整展示历史及分页内容。
4. 调用 `DELETE /api/ai/session/{sessionId}`，删除受害者的会话、历史、记忆、反馈及附件登记。
5. 也可以受害者身份发新对话、读取模型记忆、写反馈；若再获得 run/toolCall ID，还可操作附件。

CORS 不能缓解非浏览器直接请求。

#### 影响

- 跨租户、跨用户读取对话正文和反馈摘录。
- 跨身份写反馈、创建会话和运行。
- 跨身份永久删除会话数据。
- 所有“键中带 tenantId/userId”的防护都被信任边界上游绕过。

#### 修改建议

- 身份必须来自经过认证的服务端 Principal，并从服务端租户成员关系授权；不能信任客户端直接提交的身份头。
- 若身份由网关注入，网关必须认证调用方、剥离所有外部身份头并重新写入可信值；更稳妥的是后端自行验证签名令牌和租户成员关系。
- 删除生产环境的 `GET /api/ai/tenants` 及前端任意身份选择流程。租户和用户应由真实登录请求确定，不能通过全量目录选择。
- 移除静态 `TenantUserDirectory` 作为生产身份事实源；改为按当前 Principal 查询其真实身份和授权，不枚举其它租户。
- 现有 Redis 键形和 DB 唯一约束无需因本问题重划；需要补的是认证与授权边界。应审计既有访问日志，因为历史上无法仅凭键形排除冒用。
- 后续单点验证：
  - 无认证凭据时，仅修改身份头不得读取反馈、历史或销毁会话。
  - 有合法登录但无目标租户权限时返回 401/403。
  - `/api/ai/tenants` 在生产不可用。
  - 两个身份使用相同 sessionId/messageId 时仍互不可见。

---

### D-02　P1／确定设计缺口：多实例文件闸门只验证“绝对路径”，共享 Redis 登记与实例本地磁盘组合会造成附件永久残留

#### 证据

- `backend/src/main/java/com/example/configmgr/file/FileStorageMultiInstanceGuard.java:111-135`
  - 多实例校验仅要求路径为绝对路径，没有验证各实例看到的是同一共享文件系统。
- `backend/src/main/java/com/example/configmgr/file/FileStorageService.java:68-99`
  - 文件直接写入当前实例的本地路径。
- `backend/src/main/java/com/example/configmgr/file/FileStorageService.java:108-130`
  - 同一个 UUID 相对键按当前实例基目录解析。
- `backend/src/main/java/com/example/configmgr/file/FileStorageService.java:182-189`
  - `Files.deleteIfExists` 的 IOException 被吞掉；其它实例上“文件本来就不存在”不会构成错误。
- `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:145-189`
  - 附件登记及索引写入共享 Redis，上传实例信息却没有进入登记。
- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormGuard.java:405-421,553-564`
  - 消费成功后直接删除当前实例路径下的文件。
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:1474-1524`
  - 会话销毁也只检查当前实例的 `Files.exists(path)`，随后删除共享登记和索引。

#### 跨实例触发场景

1. 实例 A 接收上传，文件写入 A 的 `/var/lib/app/files/<uuid>`，登记写入共享 Redis。
2. 同一用户的下一请求落到实例 B；B 消费附件或销毁会话。
3. B 将同一个相对键解析到自己本地的 `/var/lib/app/files/<uuid>`。
4. B 上文件不存在，`deleteIfExists` 返回 false，不报错；B 删除 Redis 登记和索引。
5. A 上的真实文件失去所有发现入口，永久残留。即使 B 返回销毁完成，也无法证明 A 的文件已删除。

两台机器使用完全相同的绝对路径也能通过现有启动闸门，因此“配置合法”不代表“文件共享”。

#### 影响

- 删除、消费、表单收口在不同实例执行时产生不可发现的附件孤儿。
- 隐私删除和会话销毁语义不成立。
- 当前没有附件内容下载接口，因此本项主要是残留与生命周期问题，而非直接跨身份读取。

#### 修改建议

- 首选统一对象存储或真实共享卷，附件内容键建议包含 `tenantId/userId/sessionId/fileId`，登记中继续保存权威身份和存储键；不得使用实例本地路径作为共享事实。
- 如果必须保留本地磁盘：
  - 登记增加 `storageInstanceId`；
  - 删除任务显式携带 `tenantId/userId/sessionId/fileId/storageInstanceId` 路由到拥有者实例；
  - 使用持久删除队列，拥有者不可用时应失败保留，不得删除发现索引并假报完成。
- 现有登记需要迁移：补充身份和存储定位信息；无法证明位置的旧登记不得直接按任意实例路径删除。
- 后续单点验证：A 上传、B 消费、C 销毁后，同时检查 A/B/C 文件系统和 Redis 索引；未确认所有副本删除前不得返回 `complete=true`。

---

### D-03　P1／风险：Redis 锁或会话登记失败时仍允许开轮，多实例下会话串行约束失效

#### 证据

- `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:328-345`
  - Redis 取锁结果不可知时，保留进程内门并返回 `null`。
- `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:671-683`
  - Redis 异常被转成 `null`，不向上拒绝本次请求。
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:465-499`
  - `busy == null` 被视为取门成功并继续执行。
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionService.java:145-168`
  - 会话登记异常被映射为 `UNAVAILABLE`，而 `ensure` 又将其压成 `false`。
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:520-540`
  - 对话路径不区分“首次登记”和“登记不可知”，继续读取记忆并执行本轮。

#### 跨实例触发场景

同一租户、用户、sessionId 的请求同时落到 A/B。若锁脚本调用失败但其它 Redis 操作仍可用：

- A 只在 A 的 `ConcurrentHashMap` 中占位；
- B 只在 B 的本地 Map 中占位；
- 两边都返回取门成功并创建不同 run；
- 同会话并发执行模型和工具，可能产生重复副作用。记忆 CAS 不能替代会话互斥，也不能撤销工具副作用。

健康计数和 WARN 只能观测降级，不能阻止重复执行。

#### 修改建议

- 多实例模式下，新轮及续跑在分布式会话锁不可确认时应 fail-closed，返回可重试的 503；不得仅用本地锁继续。
- 进程内降级仅允许在显式单实例配置中开启。
- `ensure` 应向控制层保留 `UNAVAILABLE`，在登记不可知时于模型调用前终止，而不是与“新会话”共用布尔值。
- 锁键 `ai:session:lock:<tenant>:<user>:<sessionId>` 本身正确，无需重划；需要改变的是失败语义。
- 后续单点验证：仅让锁脚本失败而保持其它 Redis 命令可用，A/B 同会话并发时只能一方执行，另一方不得创建 run 或调用工具。

---

### D-04　P2／确定问题：前端草稿清理会取消其它身份的待写数据，造成跨身份数据丢失

#### 证据

- `frontend/src/utils/form-draft.ts:103-121`
  - `discardPendingFor(toolCallId)` 不按身份过滤，取消所有身份下同名 toolCallId 的待写值。
- `frontend/src/utils/form-draft.ts:272-287`
  - `clearFormDraft` 先取消所有身份的待写值，但只删除当前身份的 storage 键。
- `frontend/src/utils/form-draft.ts:300-310`
  - `pruneFormDrafts` 对“不属于当前身份前缀”的 pending 项也进入 `else discardPendingKey(key)`。
- `frontend/src/stores/ai.ts:8780-8795`
  - 会话清理调用上述 `pruneFormDrafts`。
- `frontend/src/stores/ai.ts:10987-11006`
  - 身份切换在写入新身份前执行旧会话清理，因而能够碰到目标身份以前留下的 pending 项。

#### 触发场景

1. 用户 A 修改表单，最终值处于 250ms 防抖窗口，尚未写入 storage。
2. 切换到用户 B，或 B 清理同名 toolCallId 的表单。
3. A 的 pending 项因“不匹配当前前缀”或“toolCallId 相同”被取消。
4. A 的最后修改永久丢失。B 自己的存储键并未被删除，形成“只丢别人内存待写值”的不对称行为。

这不是服务端跨租户泄露，但违反了前端身份隔离并造成用户数据丢失。

#### 修改建议

- `pruneFormDrafts` 应先执行 `if (!key.startsWith(prefix)) continue`，只允许处理当前身份的 pending 项。
- `clearFormDraft` 只调用 `discardPendingKey(formDraftKey(toolCallId))`，不得按裸 toolCallId 跨身份取消。
- 销毁旧身份会话时，如需清旧身份草稿，应显式把旧身份传入清理函数，而不是使用当前身份或全身份扫描。
- 现有 storage 键形已经带身份，无需迁移。
- 后续单点验证：A/B 使用相同 toolCallId，A 有待写值时执行 B 的 prune/clear，A 的定时器、最终 storage 值和恢复结果均不得受影响。

---

### D-05　P3／风险：反馈写口未验证 messageId 与 sessionId 的真实归属，可在本身份内搬移反馈并破坏会话删除对应关系

#### 证据

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:3014-3061`
  - 请求体的 `messageId/sessionId/excerpt` 未与 transcript 中的真实条目核对。
  - 已存在相同 messageId 时，直接执行 `entry.setSessionId(sessionId)`。
- `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java:16-19`
  - 查询正确地带租户和用户，但不带 sessionId。
- `backend/src/main/resources/db/migration/V1__schema.sql:176-190`
  - 唯一约束为 `(tenant_id,user_id,message_id)`。

#### 场景与影响

同一身份先在会话 S1 对消息 M 写反馈，再以 S2+M 调用写口，原行会被改挂到 S2：

- 销毁 S1 不再删除该反馈；
- 销毁 S2 会删除原本属于 S1 的反馈；
- 客户端还能为不存在的消息写入任意正文摘录。

租户+用户隔离仍成立，因此不定为跨租户问题；这是会话生命周期和反馈完整性风险。

#### 修改建议

- 写入前从当前身份的 transcript 验证 M 确实存在且属于 S；验证通过后才允许 upsert，摘录最好由服务端条目生成而非信任请求体。
- 若 messageId 只保证会话内唯一，唯一约束及查询应改为 `(tenant_id,user_id,session_id,message_id)`；若继续使用服务端 UUID run 派生的全局 messageId，可保留现有三元唯一约束，但必须做会话归属校验。
- 迁移前按 `(tenant,user,message)` 检查既有 session 归属冲突，不能盲目加四列唯一约束。
- 后续单点验证：跨会话重用 messageId 应拒绝，且 S1/S2 的销毁各自只影响真实归属行。

---

## 3. 已证安全项

以下结论以“**请求身份已经可信认证**”为前提；D-01 仍是当前 HTTP 边界的总缺口。

| 数据面 | 审查结论 |
|---|---|
| 会话记录与锁 | `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java:40-76` 对缺失身份直接失败；`backend/src/main/java/com/example/configmgr/ai/session/AiSessionService.java:75-86,145-179` 的会话记录为 `ai:session:<t>:<u>:<sid>`，SET NX 不覆盖旧归属。`backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:202,286-345` 的本地 Map 也使用完整身份锁键。 |
| SessionGate 异步续期与释放 | `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:731-766,816-863` 在 holder 中固化身份，watchdog 显式恢复；锁脚本为单键原子操作。`releaseDeadHeld` 同时核对身份，不会按同名 runId 跨身份强释放。 |
| 展示历史与分页 | `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:324-399` 的 data/order/meta/truncated 键均为 `ai:tx:<t>:<u>:{escapedSessionId}...`；读取与 before 游标分页使用同一身份键。`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3715-3720,3805-3806` 的终态写使用快照固化身份，而非回调线程身份。 |
| 模型记忆 | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:311-371` 的 final/staging/index 键都带身份和转义 session hash tag；读、写、删走同一键接口。`backend/src/main/java/com/example/configmgr/ai/memory/IdentityBoundChatMemoryAdvisor.java:170-175,227-264` 将身份绑定到 advisor 实例，并在每次读写显式开作用域，缺身份时拒绝读写。 |
| 记忆跨实例写入 | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:592-662,698-765,947-951` 使用版本 CAS 和清空代次；删除推进代次而非简单 DEL，能阻止清空前写回复活。 |
| 附件登记与消费 | `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:691-712` 的登记、墓碑、会话索引、表单索引全部带身份。`backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormAttachmentController.java:251-265,316-327` 只从当前身份 run 快照取得 sessionId，并验证当前表单确实待填；请求方 sessionId 不被采信。 |
| 附件表单归属 | `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormGuard.java:503-540` 再核对 runId/toolCallId；fileId 本身不能跨身份、跨轮次或跨表单使用。消费采用先墓碑后 DEL，Redis DEL 只会有一个并发调用返回成功。 |
| 会话销毁发现面 | `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:589-708` 只读当前身份的 session-run 索引，不枚举全部租户或全局 run；`739-750` 复核快照身份。发现键最后删除，减少失败后无法重试的风险。 |
| purge 定时恢复 | `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:565-627` 的 due ZSET 虽为全局协调结构，但 member 是完整身份 pending 键，身份从真实请求形成的 JSON 记录恢复；`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:280-327` 每条记录显式建立身份作用域，没有租户列表依赖。 |
| DB 查询与约束 | `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java:16-19` 所有读写均带 tenant/user；`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:80-90` 的枚举和删除均按 `session_id+tenant_id+user_id`。H2/PostgreSQL 建表脚本均为 `(tenant_id,user_id,message_id)` 唯一，允许跨租户/跨用户相同 messageId 共存。 |
| 浏览器主要存储键 | `frontend/src/utils/ai-identity.ts:141-179` 的会话指针、镜像、表单草稿和页签锁均带身份指纹；服务端 purge 返回的清理键也使用同一拼接口。D-04 是 pending 内存队列的例外。 |

本领域没有发现对话正文、session、history 或 attachment 的其它 DB 表；DB 持久面主要是 `ai_feedback`。

---

## 4. 租户枚举 / 列表依赖专项清单

1. **明确存在且必须移除的生产依赖**
   - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-42`：静态全量 `TENANTS` 和 `tenants()`。
   - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:45-57`：每次身份校验循环静态租户列表。
   - `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`：未认证的 `GET /api/ai/tenants`。
   - `frontend/src/api/ai.ts:149-156`：前端启动时获取完整目录。
   - 该目录同时是 D-01 的攻击辅助面，且最多两个预置租户，不符合真实生产身份来源。

2. **全局 purge due ZSET，不是租户枚举**
   - `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:89,565-627`
   - 它按到期分数有界读取 purge 记录，身份来自各真实请求写入的记录；没有先取得或循环租户列表。该设计符合“按真实请求获得的身份按需恢复”。

3. **当前身份下的会话 SCAN，不是跨租户枚举**
   - `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:409-449`
   - `findConversationIds` 仅扫描当前身份前缀；生产主链路未发现调用方。它是 O(keyspace) 诊断能力，不用于恢复或销毁。
   - `backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java` 在当前主代码中没有调用方。

4. 除以上项目外，D 领域的 session/history/purge/attachment 数据路径未发现其它“循环租户列表”或“获取全部租户列表”依赖；未发现本领域 Pub/Sub 数据同步通道。

---

## 5. 验证限制与建议修复顺序

原审查未执行任何测试、脚本或动态请求，以上均为 HEAD 源码与配置的静态闭环证据。实际部署是否存在外部认证网关、各实例是否使用真实共享文件系统，仓库内无法证明；这两项外部条件不能替代代码中缺失的保障。

建议顺序：

1. 先修 D-01 身份认证和全量租户目录，否则其它隔离结论无法形成真实安全边界。
2. 再统一附件共享存储及跨实例删除语义，并将会话锁/登记改为多实例 fail-closed。
3. 修复前端 pending 草稿身份过滤。
4. 最后补反馈的 message-session 归属校验与相应迁移检查。
