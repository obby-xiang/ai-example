# A 领域独立审查报告

审查范围为请求身份、租户/用户目录、上下文传播、键作用域、语言与相关前端身份存储。结论仅依据当前代码和配置，未采用仓库内既有审查文档的裁决。

## 1. 身份与版本证据

- `agentId`：`agent-940`
- `profile.bind`：`<会话证据根>/agents/agent-940/wire.jsonl:6`
  - `modelAlias=kimi-code/k3.1-pro`
- 首条 `llm.request`：`<会话证据根>/agents/agent-940/wire.jsonl:20`
  - `modelAlias=kimi-code/k3.1-pro`
  - `model=k3.1-pro`
- 身份门禁：**通过**
- 仓库 HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。
- 审查阶段未修改代码或文档，未运行测试、批量脚本或服务；只进行了只读检索、阅读及 `git rev-parse HEAD`。本次仅将既有报告沉淀为本文档。

## 2. 结论汇总

| 编号 | 严重度 | 分类 | 结论 |
|---|---|---|---|
| A-01 | P0 | 确定问题 | 客户端可直接伪造任意已登记租户/用户，当前只有命名空间隔离，没有认证 |
| A-02 | P1 | 确定问题 | 存在公开的全租户/全用户目录接口，前端启动强依赖该枚举能力 |
| A-03 | P1 | 确定问题 | 静态目录不适合多实例动态授权；恢复任务绕过目录，撤权身份仍可续跑 |
| A-04 | P2 | 风险 | 前端无身份时回退到不带租户/用户的本地键，违反强制隔离口径 |
| A-05 | P2 | 风险 | 后端 `long` 身份经 JSON/JS `number` 传递，超过 `2^53` 后可能串身份 |
| A-06 | P2 | 确定问题 | 日志上下文缺少租户、用户和独立 trace，业务 ID 重名时无法唯一归因 |
| A-07 | P3 | 确定问题 | 语言级回退忽略 `q=0`，可能使用客户端明确拒绝的语言 |

---

## 3. 详细发现

### A-01：P0，请求头即可冒用任意租户和用户

**证据**

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118-149`
  - 直接读取 `X-Tenant-Id`、`X-User-Id`。
  - 只检查整数、正数及 `TenantUserDirectory.exists`，随后建立上下文。
  - 没有令牌、签名、会话、调用方认证或可信网关注入证明。
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-34`
  - 有效组合硬编码为 `1/101`、`1/102`、`2/201`、`2/202`。
- `frontend/src/utils/ai-identity.ts:78-105,130-138`
  - 页签 `sessionStorage` 中的数值直接成为身份请求头。
- `frontend/src/stores/ai.ts:10876-10884`
  - 存储身份不存在或失效时，自动选择目录第一租户的第一个用户，而不是保持未认证状态。
- 全仓未发现 Spring Security、`Authentication`、Bearer 校验或可信头覆盖过滤器。

**完整调用链**

`sessionStorage["ai-identity"]`
→ `currentAiIdentity()`
→ `identityHeaders()`
→ Axios 拦截器 `frontend/src/api/http.ts:157-173` 或 SSE `frontend/src/api/sse.ts:934-939`
→ `WebConfig.tenantIdentityFilter()`
→ `TenantIdentityFilter`
→ 静态 `exists(tenantId,userId)`
→ `RequestContextUtils.useContext()`
→ 后续对话读写、确认、取消、历史、恢复等操作。

**具体触发场景**

任意调用方向任一实例发送：

```http
X-Tenant-Id: 2
X-User-Id: 202
```

即可以租户 2 用户 202 的身份进入业务链。无需先读取目录，因为组合已硬编码；目录接口只是进一步降低探测成本。同一静态目录打进所有实例，因此请求落到任何实例都可复现。

**影响**

这是全部 AI 对话功能的直接跨租户、跨用户越权，包括但不限于读取历史、建立会话、发起运行、重挂事件、确认工具、取消运行及销毁会话。键中包含 `tenantId/userId` 只能分隔命名空间，不能阻止攻击者自称该命名空间的所有者。

**修改建议**

1. 后端必须认证调用方：
   - 直接校验 Bearer token/登录会话，从已验证主体和有效租户成员关系得到 `(tenantId,userId)`；或
   - 由可信网关认证并注入签名身份，网关必须剥离并覆盖外部同名头，后端通过 mTLS/签名验证网关身份，且网络层禁止绕过网关直连。
2. 不再把两个 HTTP 头作为独立事实源。用户切换租户时，应验证“当前登录主体是否属于目标租户”。
3. 删除前端“目录第一项即默认身份”的逻辑。认证未完成或身份不可确认时，保持发送关闭。
4. 成员查询必须使用复合条件：

   ```sql
   WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
   ```

   并对 `(tenant_id,user_id)` 建唯一约束；不得只按 `user_id` 查询。
5. 缓存只允许真实请求已解析出的组合，例如 `identity:membership:<tenantId>:<userId>`，不得预加载或缓存全租户清单。
6. 若现有数值身份可保持不变，现有 Redis 键无需重划；禁止为了兼容旧无认证请求而保留“头优先”或默认身份通道。历史无归属数据只能离线按已验证归属迁移，不能在线双读猜测。

**后续单点验证**

- 外部请求伪造两个头必须失败，即使组合存在。
- 绕过网关直连后端必须失败。
- 同一 `userId` 在两个租户中分别存在时，不能交叉匹配。
- 对全部实例逐一验证相同攻击请求，避免滚动发布期间某实例仍接受旧逻辑。

---

### A-02：P1，公开目录构成被明确禁止的全租户枚举能力

**证据**

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68-69,103-105`
  - `/api/ai/tenants` 被精确排除在身份边界之外。
- `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-25`
  - 无身份请求即可取得全部租户和用户。
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-42`
  - 返回完整静态目录，包括租户名、用户名及所有 ID。
- `frontend/src/api/ai.ts:148-157`
  - 前端提供 `tenantUsers()` 调用。
- `frontend/src/stores/ai.ts:10842-10884`
  - 面板初始化必须取得完整目录，空目录或接口失败会禁用对话。

**调用链与影响**

未认证 `GET /api/ai/tenants`
→ `TenantUserDirectory.tenants()`
→ 全量租户/用户元数据
→ 前端身份下拉及默认第一身份。

这既是用户标识和租户元数据枚举面，也使产品无法部署到“没有获取全部租户列表能力”的生产身份体系。它还放大了 A-01，但即使修复认证，该接口及前端依赖仍不符合硬性口径。

**修改建议**

- 删除公开全量目录接口，替换为认证后的 `GET /api/ai/identity`，只返回当前已验证主体及当前租户成员信息。
- 若产品允许同一登录主体切换租户，只能返回该主体自己的成员关系，不能返回系统全部租户。
- 前端不得依赖完整目录才能初始化；身份来自登录/换租结果，不自动选第一项。
- 当前身份响应设置 `Cache-Control: no-store`，避免共享缓存保存身份数据。
- 不得把全量目录从 HTTP 接口转移到启动加载、Redis 预热线程或静态全量缓存中。

---

### A-03：P1，静态授权目录与恢复路径的授权语义不一致

**证据**

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-57`
  - 只有两个硬编码租户。
  - `exists()` 每次遍历静态全表查找组合。
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:144-147`
  - 只有 HTTP 入口检查该目录。
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:55-60,91-93`
  - 恢复路径只要求租户为正、用户为正或系统用户 `-1`，不检查目录或成员状态。
- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java:72-76`
  - `useContext` 本身也不验证组合，这是其作为底层作用域工具的合理分工，但意味着调用方必须完成授权。
- `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:617-632,775-780,1219-1223`
  - 启动/运行期扫描按记录身份直接建立作用域并续跑。

**具体场景**

1. **滚动发布不一致**：实例 A 仍含旧目录、实例 B 已删除某用户；同一请求落到 A 被接受、落到 B 被拒绝。
2. **撤权后后台副作用**：用户 `2/201` 被移除后，其旧 run 仍在共享 Redis 恢复索引中；实例启动扫描按快照中的 `2/201` 开作用域，继续模型或工具执行。
3. **系统身份仅凭数值**：任何记录若带 `userId=-1` 即被视为系统身份，记录中没有独立的身份类型证明。

这里的主要问题是授权新鲜度和撤权语义，不是键碰撞；恢复路径按记录身份拼键反而避免了借用其他调用者身份。

**修改建议**

- 用单一身份服务替换静态清单；查询真实请求的单个复合身份，不扫描租户。
- 对当前用户的新 HTTP 请求、手工续跑及系统恢复分别定义撤权策略。若撤权应停止后台副作用，恢复执行前必须按记录中的单个 `(tenantId,userId)` 再查 `ACTIVE` 状态。
- 系统账号增加显式 `principalType=SYSTEM` 或服务身份字段，不能只凭 `-1` 推断。
- 多实例撤权可使用带版本的短 TTL 缓存，失效消息只携带被撤销的单个复合身份，不广播或重建全租户目录。
- 滚动发布期间统一授权版本；撤权后的旧 run 应进入明确的 `PAUSED_REVOKED` 状态，而不是静默续跑。

---

### A-04：P2，浏览器存储保留无身份裸键回退

**证据**

`frontend/src/utils/ai-identity.ts`：

- `:141-144`：无身份会话键为 `ai-session-id`
- `:146-154`：无身份镜像键为 `ai-mirror:<sessionId>`
- `:156-169`：无身份草稿键为 `cm.ai.form.draft.<toolCallId>`
- `:171-179`：无身份锁名为 `ai-session:<sessionId>`

`frontend/src/utils/form-draft.ts:249-264` 的公开直写入口也不要求身份。

**风险场景**

在同一浏览器页签先后使用两个身份时，只要某条路径在未选定身份时读写，就会落入同一裸键。相同 `sessionId/toolCallId` 下，后一身份可读取、覆盖或清理前一身份的镜像、草稿或锁。

当前正常 UI 有两项缓解：

- `frontend/src/stores/ai.ts:1707-1713`：无身份不恢复会话 ID。
- `frontend/src/components/AiPanel/AiPanel.vue:2178,4852-4855`：目录未就绪或会话未发号时禁止发送。

因此这是**风险而非已证明的正常主流程泄漏**；但裸键设计本身与“AI 对话数据必须租户+用户隔离”不兼容。

**修改建议**

- 所有键构造函数在无身份时失败关闭，返回 `null` 或抛出明确异常，读写调用方不得落盘。
- 草稿、镜像、锁和会话指针统一要求显式身份参数，避免默认读取可变的模块级当前身份。
- 旧裸键只做一次性清理或有明确归属的离线迁移；禁止自动并入目录第一身份。
- 增加“无身份时所有本地写操作为零”的单点验证。

---

### A-05：P2，`long` 身份经过 JS `number` 会失去精度

**证据**

- 后端目录字段为 `long`：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:20-24`
- 前端身份字段为 `number`：
  `frontend/src/utils/ai-identity.ts:17-20,37-42`
- 合法性只检查 `Number.isInteger` 和正数：
  同文件 `:44-58`
- 该值继续用于请求头与所有存储键：
  同文件 `:61-64,130-179`

**触发场景**

合法后端 ID `9007199254740993` 经 JSON 解析后不能由 JS 精确表示，可能变成 `9007199254740992`。若后者也是有效身份，请求会进入错误用户/租户命名空间；即使不是有效身份，也会导致目录收窄、身份恢复及存储键异常。

当前演示 ID 很小，所以属于真实身份接入后的风险。

**修改建议**

- 身份 ID 在 JSON、TypeScript、存储中统一使用十进制字符串，如 `tenantIdText/userIdText`。
- 校验采用 `^[1-9]\d*$`，并用 `BigInt` 或字符串规范化检查 long 范围；不要使用 `Number(...)` 转换。
- HTTP 头仍是十进制文本，现有后端解析和 Redis 键形可以保持不变。
- 旧存储只在 `Number.isSafeInteger` 且为正数时迁移成字符串；不安全值必须作废并重新认证，不能猜测原值。
- 用 `2^53-1`、`2^53`、`2^53+1`、`Long.MAX_VALUE` 及跨租户同用户 ID 做后续边界验证。

---

### A-06：P2，日志/观测上下文无法区分跨身份同名业务对象

**证据**

- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:37-50`
  - MDC 只有 `runId/sessionId/instanceId/acceptedAtMs/resumeReason`。
- 同文件 `:69-81`
  - `bind()` 只绑定 run、session、instance。
- `backend/src/main/resources/application.yml:513-517`
  - 日志行首仅输出 `%X{runId} %X{sessionId} %X{instanceId}`。
- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextTaskDecorator.java:15-20`
  - 只传播 TTL 身份，不处理 MDC、locale 或独立 trace。
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java:139-140,168-176`
  - 定时调度器只使用 `TtlExecutors`，调度器本身不保证 MDC/trace 传播；需要任务体自行处理。

**影响**

在“run/session/业务 ID 只在租户内唯一”的口径下，同实例两个身份使用同一 `runId/sessionId` 时，日志行首可以完全同形。多实例日志聚合后无法可靠判断归属，可能误导排障或基于日志执行运维动作。该问题不是已证明的数据面串写，而是确定的观测归属缺陷。

**修改建议**

- 建立统一的 `AiExecutionContext`，包含 `TenantUser`、locale、runId、sessionId、traceId/spanId。
- HTTP 入口从已认证身份创建；异步提交显式传递不可变快照，执行线程内打开作用域。
- MDC 增加 `tenantId/userId/traceId`，结构化事件也携带这些字段；日志仍不得记录对话正文。
- 作用域关闭时恢复旧 MDC/TTL/locale，不能只清理其中一项。
- `CompletableFuture`、Reactor、Redis 回调、定时任务和容器 SSE 回调需逐类包装，不能假设 `TaskDecorator` 自动覆盖。
- 验证两个租户相同 run/session 的日志可区分，且连续使用同一池线程时无上下文残留。

---

### A-07：P3，语言回退没有排除 `q=0`

**证据**

`backend/src/main/java/com/example/configmgr/common/WebI18nConfig.java:58-80`

`Locale.LanguageRange.parse` 得到的列表直接用于 `Locale.lookup`，随后在 `:74-79` 逐项进行语言级回退，但没有检查 `range.getWeight()`。

**触发场景**

```http
Accept-Language: de-DE, en;q=0
```

`q=0` 表示不接受英文。正常 lookup 无可用匹配后，自定义回退仍会遍历到 `en` 并返回 `en-US`。随后 `ErrorBodyFactory.requestLocale()` 和 `ReplyLanguage.current()` 都会采用该结果。

影响仅为当前请求的语言契约错误，不构成跨身份数据泄漏。

**修改建议**

- 在 lookup 和语言级回退之前统一过滤 `weight > 0` 的 range。
- 全部 range 均为零或没有匹配时返回 `zh-CN`。
- 后续验证：
  - `de-DE,en;q=0` → `zh-CN`
  - `zh;q=0,en;q=0.5` → `en-US`
  - `en;q=0,zh;q=0` → `zh-CN`
  - 保留现有 `en-GB`、`zh-Hans` 和畸形头行为。

---

## 4. 全部租户枚举/列表依赖

| 位置 | 行为 | 裁决 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-25` | 对外返回全部租户与用户 | **确定违规，见 A-02** |
| `frontend/src/api/ai.ts:148-157`、`frontend/src/stores/ai.ts:10842-10884` | 启动依赖完整目录并默认第一项 | **确定违规，见 A-01/A-02** |
| `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:40-57` | `tenants()` 暴露全表；`exists()/require()` 循环静态清单 | **静态全表依赖，见 A-02/A-03；应改为单组合查询/O(1) 查找** |
| `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:617-630` | 系统扫描处理全局 run 索引，请求扫描过滤当前身份 | **不是租户目录枚举** |
| `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3297-3320` | 有请求身份时读取该身份的 recovery ZSET；系统面才读取全局索引并解析成员身份 | **未依赖租户列表** |
| 其余 `TenantUserDirectory` 调用 | 生产代码仅过滤器 `exists` 与目录控制器 `tenants` | **未发现用于循环租户找任务的调用** |

本领域没有数据库租户全表查询、全租户本地缓存或“逐个租户扫描任务”的实现。系统级 run 索引扫描属于跨租户后台处理，但它按每条记录身份执行，并非依赖租户列表。

## 5. 已证安全或合理设计

- **后端无默认身份**：`backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java:38-58` 在缺上下文时抛错，没有 `tenant=0/user=0` 回退。
- **请求线程清理**：`backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:148-150` 使用 try-with-resources；`backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java:109-131` 支持嵌套还原、最外层清理及同线程关闭。
- **旧记录不借用调用者身份**：`backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:55-81` 对不可用身份显式清空当前上下文。
- **键构造包含租户和用户**：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java:61-76`：`<tenantId>:<userId>:`
  - `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:51-76`：`{tenantId:userId:run:<runId>}`
  - `backend/src/main/java/com/example/configmgr/ai/tenant/LocalRunKey.java:27-42`：本地表复用同一身份 tag。
  数值 ID 不含分隔符，跨身份相同 run/session 不会因键形本身碰撞。
- **Pub/Sub 身份段**：`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:272-282` 使用 `ai:wake:<tenantId>:<userId>:<runId>`，缺少身份时发布/订阅失败关闭。
- **异步身份不只是依赖装饰器**：主 chat、续跑、摘要、看门狗、保活及 Redis 回调均在执行线程显式设置身份或按记录身份重开作用域；本次未在这些闭环路径发现身份缺失。
- **语言显式搬运**：
  - 首轮：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:558-565,600-602`
  - 续跑：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752-1777`
  - 确认门：`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2518-2535`
  未把 `LocaleContextHolder` 错当成会自动跨池传播。
- **前端已选身份的存储键**包含租户和用户，且 Axios 拦截器只补缺省头，不覆盖动作入口捕获的显式身份，能避免切身份后的微任务错贴新身份。

这些安全项不能抵消 A-01：它们保证“上下文一旦可信，后续分隔和清理大体成立”，但不证明上下文来源可信。
