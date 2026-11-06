# 领域 F 独立审查报告：对话请求与续跑生命周期

## 1. 身份门禁与审查边界

身份文件：`<会话证据根>/agents/agent-945/wire.jsonl`

| 证据 | 读取结果 |
|---|---|
| 第 6 行 `profile.bind` | `agentId=agent-945`，`modelAlias=kimi-code/k3.1-pro` |
| 第 20 行首条 `llm.request` | `agentId=agent-945`，`model=k3.1-pro`，`modelAlias=kimi-code/k3.1-pro` |
| 门禁结论 | 与预期完全一致，通过 |

- 仓库：`ai-example-main-v2`
- 实际 HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定值一致。
- 只读审查了指定类、请求 DTO，以及闭环所需的直接调用方/被调用方。
- 未修改代码或文档，未运行测试、批量脚本或服务，未执行 Git 写操作。
- 审查结束时 `git status --short` 无输出，工作区无变更。
- 以下运行时故障交织均由静态调用链证明，未动态复现；相应单点验证仅作建议，未执行。

## 2. 结论摘要

| 严重度 | 数量 | 结论 |
|---|---:|---|
| P0 | 1 | 请求头可任意声明租户/用户，完整隔离体系可被直接绕过 |
| P1 | 5 | 禁止的全租户目录；会话门与续跑租约在故障期失去跨实例互斥；取消写失败仍成功；取消读不可知仍允许 DONE |
| P2 | 2 | 异步恢复语言未固化；日志缺少租户/用户且显式 trace 可错绑 |
| P3 | 1 | 全局恢复索引是跨身份候选发现面，需要与“禁止枚举”口径明确区分 |

**最先处理 P0。** 当前所有 `tenantId/userId` 键、记录归属复核、异步作用域，都只是“按调用者自称的身份分桶”，不是“按已认证主体授权的身份分桶”。

---

## 3. 确定问题与风险

### F-01 P0／确定问题：客户端可伪造任意已登记租户和用户

**证据**

- `TenantIdentityFilter` 直接读取客户端的 `X-Tenant-Id`、`X-User-Id`：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118`
- 唯一实质检查是组合是否存在于静态目录，随后直接建立身份：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:134`
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:144`
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:148`
- 静态目录公开包含两个租户及各自用户：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32`
- 全仓未找到把这两个请求头绑定到认证主体、签名网关声明或会话 principal 的安全层；`WebConfig` 仅注册安全响应头和该身份过滤器：
  `backend/src/main/java/com/example/configmgr/config/WebConfig.java:70`
  `backend/src/main/java/com/example/configmgr/config/WebConfig.java:83`

**完整调用链**

```text
任意 HTTP 客户端
  → 自填 X-Tenant-Id: 2 / X-User-Id: 201
  → TenantIdentityFilter.exists(2,201)=true
  → RequestContextUtils.useContext(2,201)
  → AiController.ownedRun/ownedByCurrentIdentity
  → RunStore.get(runId,2,201)
  → 读取、重挂、取消、确认、回灌或续跑该身份的数据
```

`ownedByCurrentIdentity` 虽会比较记录身份，但比较对象正是攻击者刚自称的身份：

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1680`
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1693`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2636`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2655`

**触发场景**

攻击者先获得合法目录中的任一组合，再以该组合调用：

- `GET /api/ai/history/{sessionId}`：读取对话历史；
- `GET /api/ai/events/{runId}`：读取实时帧与归档；
- `POST /api/ai/confirm`、`/frontend-tool-result`：替他人批准或注入工具结果；
- `POST /api/ai/cancel/{runId}`：中断他人运行；
- `POST /api/ai/runs/{runId}/resume`：驱动他人轮次及潜在业务副作用。

**影响**

跨租户、跨用户的机密性和完整性全部失效。键名隔离、记录身份复核、唯一约束均无法弥补身份来源不可信的问题。

**修复与迁移**

1. 从已认证主体和服务器端租户成员关系解析身份，不得由请求体、浏览器存储或普通客户端请求头决定。
2. 若身份由网关注入，网关必须剥离所有外部同名头，并使用无法伪造的签名声明；后端校验签名、签发方、受众、时效及用户租户成员关系。
3. 前端“身份切换”只能切换当前主体已被授权的上下文，不能切换为任意用户。
4. 现有 Redis/DB 键形可保留，不需要数据迁移；上线时应先阻断伪造头，再审计既有数据是否已被越权访问。
5. 若仓库外确实存在受信网关，需提供其配置才能重新评价；本仓库代码本身不能证明该前提。

**后续单点验证**

- 无认证请求携带 `2/201` 必须 401/403，且 Redis/DB 零读写。
- 已认证用户 A 声明用户 B 的身份必须 403。
- 同一 `runId/sessionId` 在两个身份下仍可独立存在，但 A 永远无法读取或驱动 B 的记录。

---

### F-02 P1／确定问题：存在生产明确禁止的全租户列表接口及静态目录依赖

**证据**

- 全量静态租户注册表：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32`
- 返回全部租户的方法：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:39`
- 无身份要求的公开接口：
  `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:14`
  `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22`
- `/api/ai/tenants` 被明确排除在身份边界外：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:58`
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68`

**调用链与影响**

```text
未认证请求 GET /api/ai/tenants
  → TenantUserDirectory.tenants()
  → 返回所有 tenantId/userId
  → 为 F-01 的身份伪造直接提供有效凭据式标识
```

这与“生产没有获取全部租户列表的接口或能力”直接冲突，也扩大了身份信息暴露面。

**修复与迁移**

- 删除公开目录接口及生产静态全量注册表。
- 身份校验改为按当前认证主体查询其成员关系，而不是遍历目录判断组合是否存在。
- 前端不再依赖目录下拉选择任意用户。
- 保留“恢复逻辑不得使用目录”的现状；不要以删除目录为由改成从认证服务枚举租户。

**后续单点验证**

- `/api/ai/tenants` 不再暴露全量列表。
- 任意身份扫描、恢复扫描均不调用用户/租户目录。
- 合法用户只获得自身可见的租户上下文，不能发现其它租户及其用户清单。

---

### F-03 P1／确定问题：会话门在 Redis 不可确认时仍放行，跨实例可并发同一 session

**证据**

- Redis 锁异常或结果不可知时，`acquire` 仅设置 `candidate.degraded=true`，随后返回 `null`，而 `null` 在调用方表示取门成功：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:328`
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:340`
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:343`
- 调用方据此继续创建 emitter、提交模型任务：
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:465`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:547`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:564`
- 类注释也明确此时只剩进程内互斥：
  `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:69`

**完整触发链**

```text
用户 U 的 /chat 落到实例 A
  → A 取得会话门并开始驱动
  → 另一个 /chat 落到实例 B
  → B 的 Redis ACQUIRE 失败/结果不可知
  → B 仍返回 acquire=null
  → A、B 各自持有不同的进程内门
  → 同一 tenant/user/session 双轮并发
```

**影响**

同一用户的记忆窗口、run 索引、帧流和业务工具可能并发写入；请求切换、取消和恢复无法确定当前权威轮。目标部署为上百实例且请求可任意落点，因此不能以“单实例降级正确”作为可接受条件。

**修复与迁移**

1. 多实例模式下，锁不可确认必须 fail-closed：返回 503 和可重试码，不得进入模型调用。
2. 进程内降级只应保留在显式单实例配置中，不能作为生产通用兜底。
3. 已持锁实例续期失败时，应尽快停止新的业务副作用并进入恢复，而不是继续依赖本地门长期运行。
4. 键形 `ai:session:lock:<tenant>:<user>:<session>` 可保留，无需迁移。

**后续单点验证**

- A 驱动期间让 B 的锁脚本超时，B 必须拒绝开流。
- Redis 恢复前不得产生第二个 run、模型调用或工具副作用。
- 恢复后同一 session 仍只能有一个权威驱动。

---

### F-04 P1／确定问题：续跑租约失败后，`NEVER_HELD` 可绕过他人租约并无租写回

**证据**

首轮取租异常仍继续驱动：

- `DriveOwnership.none()` 的 `owned=true`：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:908`
- 取租异常后返回该对象：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:947`
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:955`
- 随后仍进入 `runAttempts`：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:689`
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:751`

续跑触发也有同样的无租派发：

- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2500`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2504`

设施恢复后，权威判定只看本进程视图；本进程没有持租记录时返回 `NEVER_HELD`：

- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:845`
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:860`

`NEVER_HELD` 不走 fencing，而走普通写；即使 `ai:fence` 已推进也只是 WARN：

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1506`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1549`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1882`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1903`

**完整触发链**

```text
实例 A 创建 run 后取租失败
  → A 继续调用模型
  → Redis 恢复，A 本进程仍无 lease 视图，得到 NEVER_HELD
  → 实例 B 已取得该 run 的 lease/fence 并续跑
  → A 不写 epoch，普通 SET/普通出帧仍被允许
  → A、B 同时驱动同一 run
```

**影响**

重复模型请求、重复工具副作用、快照与帧分叉，严重时可能重复执行业务写操作。`warnIfFenceIsAhead` 明确显示系统知道这是“没有续跑权的写”，但仍允许。

**修复与迁移**

1. 生产启用租约时，模型、工具、快照、帧和台账写入必须要求有效 epoch；取租不可确认应返回可重试失败。
2. 将 `NEVER_HELD` 限定为真正无租约语义的测试/单实例装配，不得用于生产已有 run 的普通写。
3. 无 epoch 写入应在同一 Lua 中同时检查 lease 与 fence；已有 lease/fence 的 run 禁止 plain SET。
4. 续跑触发不可确认时保留已决条目，稍后重试，不无租派发。
5. 现有 lease/fence 键和 epoch 可保留，不需要清空；修复应利用现有 fencing 数据阻止旧写者。

**后续单点验证**

- A 取租失败、B 接管后，A 的所有写必须得到 `STALE_FENCE/LEASE_REQUIRED`。
- B 已持租时，A 的 `NEVER_HELD` 不得产生普通写、工具认领或帧归档。
- 同 run 最终只能有一个驱动和一个权威终态。

---

### F-05 P1／确定问题：取消写入共享 Redis 失败仍返回成功，跨实例取消丢失

**证据**

- 取消先写本进程，再尝试写 Redis：
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:194`
- Redis 写失败仅记录本实例的短 TTL 降级事实：
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:197`
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:200`
- 该降级表仅在当前实例内：
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:87`
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:97`
- 控制器无条件返回 `ResponseEntity.ok`，且 `cancelled=true`：
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1593`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1596`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1601`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1603`

**触发场景**

```text
驱动在 A，取消请求落到 B
  → B 写 ai:cancel:{t:u:run:id} 失败
  → B 仅在自己的 degradedCancels 中记录取消
  → A 没有该本地记录，也读不到 Redis 取消旗标
  → B 仍向用户返回 200 cancelled=true
  → A 继续模型/工具执行
```

**影响**

用户已收到停止成功，但后台仍可能产生正文和业务副作用。`clientRequestId` 取消墓碑同样依赖 Redis，不能解决这次共享存储故障。

**修复与迁移**

1. 权威 Redis 写成功后再返回成功；失败返回 503/`AI_REDIS_UNAVAILABLE`，允许客户端重试。
2. 本地监听者只作加速，不能把本地降级记录包装成集群取消成功。
3. 若必须接收后再处理，需要持久化取消命令或 outbox，并由任意实例按 `tenant/user/run` 重放；本实例易失的 120 秒表不满足要求。
4. 保留现有身份化取消键，无需迁移。

**后续单点验证**

- B 写取消键失败时响应必须非 200。
- A 不得因 B 的本地降级记录而被误判已取消。
- Redis 恢复并重试后，A 必须在一个轮询周期内停止。

---

### F-06 P1／确定问题：取消状态不可知仍允许 DONE，终态 CAS 不检查取消键

**证据**

成功收尾前，`cancelState` 返回 `UNKNOWN` 时不等于 `CANCELLED`，因此继续成功；异常也直接返回 `false`：

- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2563`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2568`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2573`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:2575`

注释声称由 DONE CAS 兜底，但实际 fenced 写只包含快照、fence、状态三个键，不包含 `ai:cancel`：

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1917`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1921`

**触发场景**

```text
B 已成功写入取消键，但尚未把快照改成 CANCELLED
  → A 即将写 DONE
  → A 读取取消状态时遇到 Redis UNKNOWN/异常
  → cancelledBeforeSuccess=false
  → A 写 DONE；DONE CAS 只看快照是否已 CANCELLED，不看取消键
  → 后续取消收尾不能合法把 DONE 改成 CANCELLED
```

**影响**

“取消优先”在跨实例读故障窗口失效，用户取消的轮次可被永久落成成功。

**修复与迁移**

1. DONE/FAILED 写入与取消键检查必须放进同一条 Lua；利用现有同槽 tag：
   ```text
   ai:run:{t:u:run:id}
   ai:runstate:{t:u:run:id}
   ai:fence:run:{t:u:run:id}
   ai:cancel:{t:u:run:id}
   ```
2. 取消键存在时拒绝 DONE/FAILED，并转交取消收尾。
3. 取消状态不可知时不得做成功终态写；保持非终态并返回可重试错误。
4. 现有键无需迁移，但需保留取消键至终帧和终态均已确定之后。

**后续单点验证**

- 仅写取消键、快照仍 RUNNING 时，DONE 必须被原子拒绝。
- 取消读 UNKNOWN 时不得产生 DONE/FAILED。
- 重放该交织后，最终只能是 `CANCELLED + done{cancelled:true}`。

---

### F-07 P2／确定问题：异步恢复与 emitter 没有固化本轮语言

**证据**

主请求路径已显式传递语言：

- 首轮 HTTP→工作线程：
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:562`
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:565`
- 工具上下文：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1217`
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1224`
- 续跑工作线程：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752`
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1759`

但 `RunSnapshot` 只有租户、用户、会话等字段，没有语言字段：

- `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java:69`
- `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java:85`

延迟重试只保存记录身份，随后在调度线程重新读取默认 locale：

- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2630`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2634`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2520`

跨实例离线重挂直接合成中文错误：

- `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3973`
- `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4031`
- `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4034`

**触发场景与影响**

- 英文用户的首次续跑遇到租约占用，延迟重试时调度线程无 HTTP locale，错误文案可能退回中文。
- 英文用户在另一实例重挂，终帧宽限耗尽后收到中文 `AI 服务暂不可用`。
- 若旧快照缺少 SYSTEM，自动恢复重建提示也可能默认中文：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2243`

**修复与迁移**

1. 在 `RunSnapshot` 增加可空 `replyLocale`，首轮创建时固化；执行信封和续跑任务一律携带它。
2. 所有延迟重试、启动恢复、离线 emitter 错误都使用该值，不再读取调度线程的 locale。
3. 旧快照可优先从已有 SYSTEM 的语言段恢复；无法恢复时按既有默认中文兼容。
4. emitter 的 typed error 应通过统一码级本地化组件生成，而不是硬编码中文。

**后续单点验证**

- 英文请求经过“租约占用→定时重试→失败”全链路仍全英文。
- 英文用户在另一实例触发离线重挂宽限，合成错误仍为英文。
- 旧无语言快照保持原有兼容行为。

---

### F-08 P2／确定问题：日志没有租户/用户身份，显式 `eventOn` 还可能行首与事件体错绑

**证据**

`AiTrace` 的 MDC 只有 `runId/sessionId/instanceId` 等，没有 `tenantId/userId`：

- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:37`
- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:43`
- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:69`

此外，`eventOn` 只有在当前没有 runId 时才绑定显式 runId；若池线程残留的是 A，而调用方显式上报 B，行首仍可能为 A，事件体却为 B：

- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:222`
- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:229`
- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:247`

**触发场景与影响**

跨租户允许相同 runId/sessionId。日志聚合后无法可靠判断某条拒绝、取消、续跑属于哪个租户/用户，安全审计和故障定位可能错归身份。异步线程若残留其它 run 的 MDC，还会出现同一行两个 runId 的证据冲突。

**修复与迁移**

1. `AiTrace.bind` 增加身份感称重载，从记录或当前认证上下文显式写入 `tenantId/userId`。
2. `clear` 同步清理新增字段；现有 `wrap`/TaskDecorator 继续传播完整 MDC。
3. `eventOn` 在显式 runId 与当前 MDC 不同时，也应临时重绑显式值，结束后恢复。
4. 更新日志 pattern 和聚合索引；保留 runId 作为轮次标识，不把它当跨租户唯一 traceId。
5. 日志只增加身份元数据，不加入对话正文。

**后续单点验证**

- 两个身份使用相同 runId/sessionId 时，所有日志仍能按 tenant/user 正确拆分。
- 在 MDC 已绑 A 的线程调用 `eventOn(B)`，行首和事件体都必须为 B，结束后恢复 A。

---

### F-09 P3／风险：自动恢复使用跨身份全局候选索引，但不等同于“枚举租户列表”

**证据**

- 全局索引成员为 `tenantId/userId/runId`：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:143`
- 无身份系统线程读取全局 `ai:recovery:due`，真实请求线程读取 `ai:recovery:due:<tenant>:<user>`：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3271`
- 每条记录再按成员身份打开作用域：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:489`
- 启动及周期扫描均使用该发现面：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:816`
- 全局报告缓存按当前请求身份切片，不直接回显其它身份条目：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:409`

**判定**

它不是“循环租户列表/获取全部租户列表”的依赖，身份来自原始 run 记录而非租户目录，**不计作 F-02 同类直接违规**。但它确实是跨身份 run 候选枚举面，若安全口径进一步禁止任何跨租户后台发现，则当前自动恢复设计也不满足。

**建议**

- 明确允许“身份化工作项队列”与“租户目录枚举”的边界。
- 若不允许全局发现，只保留真实请求驱动的 `ai:recovery:due:<tenant>:<user>` 恢复；或改为原始请求时写入的身份化工作队列，由消费者逐条处理，而非批量扫描全局 ZSET。
- 继续保持报告切片和无身份记录 fail-closed，不引入新的全租户查询。

---

## 4. 已证安全的部分

### 4.1 Redis、内存、Pub/Sub 的主要键与归属复核

- run 键统一为 `{<tenantId>:<userId>:run:<runId>}`，并拒绝花括号注入：
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:51`
- `RunStore.get` 不仅按身份拼键，还核对记录中的身份，错归属按不存在处理：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2619`
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2655`
- `RunRegistry`、`ResumeService.resuming`、`drivingRuns`、`ToolActivityBeacon` 均通过 `LocalRunKey` 包含租户和用户，而非裸 runId：
  `backend/src/main/java/com/example/configmgr/ai/tenant/LocalRunKey.java:27`
- 取消键为 `ai:cancel:{tenant:user:run:id}`，clientRequestHash 为 `ai:creq:<tenant>:<user>:<session>`：
  `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:110`
  `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:300`
- Pub/Sub 频道包含完整身份，合流缓存也以完整频道名为键：
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:148`
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:278`
- `FrameWakeListener` 以注册时固定的身份句柄退订，不依赖关闭回调线程的偶然身份：
  `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java:304`
  `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java:351`

### 4.2 主要异步身份传播不是仅依赖 ThreadLocal 装饰器

- Reactor `onNext` 在每个事件回调中重新打开驱动身份：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1258`
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1335`
- 续跑以快照身份重新建立作用域：
  `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:810`
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1140`
- 看门狗及取消轮询逐拍恢复创建时身份：
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:150`
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:191`
  `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:279`
- `TurnKeepalive` 同样逐拍恢复身份：
  `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:68`
  `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:189`
- SSE 出帧、归档对账、唤醒、drain、实际写出均重新使用本轮记录身份：
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3603`
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4325`
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4724`
  `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4851`

### 4.3 请求 DTO 与 DB 唯一约束

- DTO 不接受客户端指定 `tenantId/userId`，身份只能来自边界上下文：
  `backend/src/main/java/com/example/configmgr/ai/web/AiChatRequest.java:38`
- `clientRequestId` 不是 runId，且在身份+会话 Hash 内做原子状态转换：
  `backend/src/main/java/com/example/configmgr/ai/web/ClientRequestCancelRequest.java:6`
  `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:172`
- 反馈查询带租户和用户，DB 唯一约束也是 `(tenant_id,user_id,message_id)`：
  `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java:16`
  `backend/src/main/resources/db/migration/V1__schema.sql:188`

### 4.4 前端直接调用方的基本隔离结构

- chat、events、probe 都通过裸 `fetch` 同时携带身份和语言头：
  `frontend/src/api/sse.ts:706`
  `frontend/src/api/sse.ts:801`
  `frontend/src/api/sse.ts:932`
- 会话、镜像、表单草稿、页签锁的键都含身份指纹：
  `frontend/src/utils/ai-identity.ts:141`
  `frontend/src/utils/ai-identity.ts:146`
  `frontend/src/utils/ai-identity.ts:156`
  `frontend/src/utils/ai-identity.ts:171`

这些只能防止误串桶，不能解决 F-01 的主动伪造。

### 4.5 其它明确结论

- 本领域指定类中未发现服务器本地文件读写依赖。
- `/runs` 请求面只枚举当前身份的窄 ZSET，不枚举租户：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3243`
- `RunIdentityScope` 对旧 `0/0` 记录会显式清空身份，不沿用池线程残留身份：
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:55`
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:76`

---

## 5. 本领域全部租户枚举／列表依赖

1. **明确存在且违规**：`TenantUserDirectory.tenants()` 静态全量列表及公开 `GET /api/ai/tenants`，详见 F-02。
2. **跨身份 run 候选发现**：系统线程读取 `ai:recovery:due`，逐条解析 `tenant/user/run`；不是租户目录枚举，但属于全局恢复发现面，详见 F-09。
3. **进程内维护扫描**：`RunRegistry.sweepIdle`、`SessionGate.findHolder` 会遍历本地记录，用于资源回收或精确释放，不用于发现租户或批量业务处理，不算租户列表依赖。
4. **未发现**：`ResumeService`、emitter、看门狗、保活、取消、clientRequest 取消路径通过 `TenantUserDirectory.tenants()` 循环租户执行恢复的情况。

---

## 6. 建议修复顺序与未决验证

1. **先修 F-01/F-02**：认证主体、租户成员授权及移除公开目录，否则其它隔离措施没有安全边界。
2. **再修 F-03/F-04**：多实例故障时停止放行和无租写入，防止重复副作用。
3. **随后修 F-05/F-06**：让取消命令可靠持久化，并将取消键纳入终态原子判定。
4. **最后统一 F-07/F-08**：快照化语言和日志身份；明确 F-09 的全局恢复队列口径。

未执行任何动态验证。建议后续按各条目的单点场景逐一验证，尤其要覆盖：两个身份相同 `runId/sessionId`、同用户请求随机落实例、Redis 写失败与恢复、取消与 DONE 竞争、延迟续跑、跨实例离线重挂。
