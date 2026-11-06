# 领域 B 独立审查报告：AI 服务配置与模型目录

## 1. 身份与审查边界

身份门禁已通过，原始证据为：

- `<会话证据根>/agents/agent-941/wire.jsonl:2`：`agentId=agent-941`
- `<会话证据根>/agents/agent-941/wire.jsonl:6`：`profile.bind`，`agentId=agent-941`、`modelAlias=kimi-code/k3.1-pro`
- `<会话证据根>/agents/agent-941/wire.jsonl:20`：首条 `llm.request`，`model=k3.1-pro`、`modelAlias=kimi-code/k3.1-pro`

仓库 HEAD 经只读 `git rev-parse HEAD` 核对为：

```text
17080859f7b6905aafbbea90f6d2281913bcab9e
```

与指定 HEAD `17080859` 一致。审查期间未修改代码、未运行测试或批量脚本、未执行 Git 写操作、未启停服务。本文件仅为审查结论沉淀。

## 2. 结论摘要

| 编号 | 严重度 | 判定 | 结论 |
|---|---|---|---|
| B-01 | P0 | 确定问题 | 配置管理面整体绕过身份过滤，且无独立管理员授权；可篡改全局默认上游并窃取其他身份后续提示词 |
| B-02 | P0 | 确定问题 | provider/model/key_ref/默认参数/快照/运行态均只有全局一份，不满足租户+用户隔离 |
| B-03 | P1 | 确定机制问题，实际漂移无法判定 | ENV 密钥由各实例本地读取，同一用户落到不同实例可使用不同上游身份 |
| B-04 | P1 | 确定迁移风险 | 当前全局 `code` 唯一与密文 AAD 不支持跨租户同名 provider；直接放宽约束会产生歧义和密文搬运风险 |
| B-05 | P1 | 确定问题，短 ENV 密钥时触发 | 长度不超过 4 的 ENV 密钥会通过 `keyLast4` 完整落库并无身份回显 |
| B-06 | P2 | 确定问题 | 模型目录 Redis 缓存不含配置版本、端点或密钥指纹，跨实例最长返回旧清单 10 分钟，特定配置下永不过期 |
| B-07 | P2 | 确定问题 | 无身份 `/health` 暴露 provider、baseUrl、默认参数和原始 Redis 错误 |
| B-08 | P2 | 确定问题 | 审计操作者固定为 `local-user`，日志和告警缺少租户/用户归属 |
| B-09 | P2 | 风险 | 探测超时未取消异步请求，响应体仍完整读入内存；DNS 重绑定可绕过连接前私网检查 |
| B-10 | P3 | 风险，未找到现成泄露调用点 | 多个含密钥材料的 Java record 自动生成含明文的 `toString()`；实体指纹缺少 JSON 防线 |

---

## 3. 详细发现

### B-01：P0——配置管理面无身份、无管理员授权，可劫持所有身份的后续对话

**证据**

`TenantIdentityFilter` 明确排除整个配置子树：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68-76`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:103-110`

排除范围包括：

```text
/api/ai/providers
/api/ai/models
/api/ai/settings
```

过滤器确实注册到了 `/api/*`：

- `backend/src/main/java/com/example/configmgr/config/WebConfig.java:78-90`

但 `AiConfigController` 的读写接口没有任何认证或角色检查：

- 创建、修改、删除 provider：`backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:104-131`
- 使用已保存密钥探测：`backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:136-157`
- 拉取模型：`backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:160-168`
- 增删改模型：`backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:173-196`
- 修改全局默认目标：`backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:199-209`

**完整攻击调用链**

```text
无身份 POST /api/ai/providers
  → AiConfigWriter.createProvider
  → 写入攻击者控制的 baseUrl/apiKey
  → POST /providers/{id}/models 添加模型
  → PUT /settings/defaults 将该 provider/model 设为全局默认
  → config.version 增加并刷新各实例
  → 其他租户/用户 POST /api/ai/chat
  → ResilientChatService 调 providerRegistry.current()
  → AiConfigService.resolveDefault()
  → AiProviderRegistry.newApi()
  → 使用攻击者 baseUrl 创建 OpenAiApi
  → 受害者提示词发往攻击者端点
```

关键运行落点：

- 默认目标解析：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:536-579`
- 聊天消费默认目标：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:552-569`
- 创建携带密钥的上游客户端：`backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java:391-398`

**触发场景与影响**

攻击者不需要知道受害者 API Key，只需创建一个自己控制的 OpenAI 兼容端点并设为默认。其他租户或同租户其他用户的下一轮未显式指定模型的请求，就会把提示词、上下文和工具披露信息发往该端点。

此外还可：

- 清空他人密钥、停用 provider，造成全局面 DoS；
- 使用他人已保存密钥发起聊天，消耗其额度并混合上游账户数据；
- 读取全局 provider 配置、末四位密钥和模型清单；
- 反复调用探测接口发起出站请求。

**修复建议**

1. 立即将 `providers/models/settings` 纳入可信身份边界，不能继续依赖当前的整棵子树排除。
2. 配置写接口、探测和模型拉取必须要求管理员或配置所有者权限；仅去掉过滤排除并不等于完成管理员授权。
3. tenant/user 必须来自经过验证的真实请求身份，不接受请求体、查询参数或路径中另传的 scope。
4. `/models` 按当前 tenant+user 返回自己的可用模型；provider 管理读接口按管理员权限或所有者限制。
5. 在外部网关确实存在鉴权的情况下，也应在应用层保留对象归属校验，不能只靠路径放行规则。

**后续单点验证建议（不在本次执行）**

- 无身份调用所有配置写接口，统一得到 401/403，且 DB、Redis、`config.version` 均无变化。
- 租户一管理员创建并设为默认的 provider，不影响租户二及租户一其他用户。
- 非管理员即使携带合法 tenant/user，也不能修改、探测或拉取不属于自己的 provider。

仓库外是否另有认证网关无法判定；应用自身没有形成有效防线。

---

### B-02：P0——配置持久化、快照和运行态全部为全局共享

**数据库没有租户/用户维度**

- provider：`backend/src/main/java/com/example/configmgr/ai/config/db/AiProvider.java:51-57`
- model：`backend/src/main/java/com/example/configmgr/ai/config/db/AiProviderModel.java:31-41`
- setting 主键仅为 `setting_key`：`backend/src/main/java/com/example/configmgr/ai/config/db/AiSetting.java:52-57`
- audit 无 tenant/user：`backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAudit.java:33-50`

实际 H2/PostgreSQL 基线约束也是全局的：

```sql
ai_provider:      UNIQUE(code)
ai_model:         UNIQUE(provider_id, model_id)
ai_setting:       PRIMARY KEY(setting_key)
```

证据：

- `backend/src/main/resources/db/migration/V1__schema.sql:194-240`
- `backend/src/main/resources/db/postgresql/V1__schema.sql:193-239`

这直接违背“跨租户 provider 编码只保证租户内唯一”的要求。

**内存快照和查询也是全局的**

`AiConfigService` 只有一个 `volatile ConfigSnapshot`，加载时读取所有 provider、这些 provider 的模型以及全部 setting：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:118-128`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:310-317`

默认参数通过全局键读写：

- 写入：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:497-519`
- 全局 setting 查询：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:628-639`
- 全局版本锁：`backend/src/main/java/com/example/configmgr/ai/config/db/AiSettingRepository.java:14-18`

**运行态缓存无身份成分**

当前键结构：

```text
RuntimeKey = providerId | keyFingerprint | baseUrl | completionsPath
Redis      = ai:cfg:models:<providerId>
```

证据：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:1092-1096`
- `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java:82-93`
- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java:34`
- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java:90-119`

`AiProviderRegistry` 还只保留当前一个 runtime/API key，其他键会被删除：

- `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java:188-191`

**异步身份存在，但配置层完全不消费**

续跑正确地从快照恢复身份：

- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:810-823`

但随后调用的 `target(providerId, modelId)` 没有 tenant/user 参数：

- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1127-1148`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:653-666`

因此 `RunIdentityScope`、TTL 或记忆 advisor 的存在不能证明配置隔离。

**具体触发场景**

1. 租户一用户 101 修改默认模型。
2. 全局 `ai_setting['default.providerId'/'default.modelId']` 被覆盖。
3. 租户二用户 202 发起未指定模型的聊天。
4. 所有实例逐渐刷新到同一份全局默认值，用户 202 使用用户 101 选择的 provider 和密钥。

同租户不同用户之间同样成立。

**详细修复设计**

引入不可为空的值对象：

```java
ConfigScope(long tenantId, long userId)
```

所有配置入口显式传递，例如：

```java
current(scope)
resolveDefault(scope)
resolveChatTarget(scope, providerId, modelId)
provider(scope, providerId)
updateDefaults(scope, command)
```

异步续跑从 `RunSnapshot.tenantId/userId` 构造 scope，不能依赖执行线程恰好存在 ThreadLocal。

建议表结构：

```text
ai_provider:
  UNIQUE(tenant_id, user_id, code)
  UNIQUE(tenant_id, user_id, id)

ai_model:
  UNIQUE(tenant_id, user_id, provider_id, model_id)
  复合外键 (tenant_id,user_id,provider_id)
      → ai_provider(tenant_id,user_id,id)

ai_setting:
  PRIMARY KEY(tenant_id, user_id, setting_key)

ai_config_audit:
  tenant_id, user_id, actor_tenant_id, actor_user_id, ...
```

即使保留数据库全局自增 ID，也必须在每个查询中同时限定 scope，不能继续依赖 ID 碰巧全局唯一。

缓存重划为：

```text
内存 RuntimeKey =
  (tenantId,userId,providerId,keyFingerprint,baseUrl,completionsPath)

Redis =
  ai:cfg:v2:models:{tenantId}:{userId}:{providerId}:{scopeConfigVersion}
```

同时：

- `ConfigSnapshot` 改为按真实请求 scope 按需加载的有界缓存；
- 版本检查和版本行锁改为 `(tenant_id,user_id,'config.version')`；
- `unreadableWarned` 改为 scope+provider；
- 写后只刷新、淘汰当前 scope，不清空或重载所有用户；
- 不启动全用户预热，不通过枚举租户填充缓存。

**后续单点验证建议（不在本次执行）**

构造两个租户、每租户两个用户，四者拥有相同 `provider.code/modelId` 和人工构造的相同外部 provider ID，验证：

- 列表、默认参数、密钥状态、更新和删除互不可见；
- 数据库唯一约束只在各自 scope 内生效；
- 同一线程交替处理四个身份以及跨实例续跑，都使用各自密钥与模型。

---

### B-03：P1——ENV 密钥是实例本地状态，多实例可能使用不同上游账户

**调用链**

```text
AiConfigSeeder/ApplicationRunner
  → 全局 ai_provider 写入 key_ref=ENV
  → 请求落到任意实例
  → AiConfigService.resolveKey
  → envApiKey()
  → 当前实例 spring.ai.openai.api-key
```

证据：

- 全局种子判定：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:89-117`
- ENV 密钥解析：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:890-900`
- 实例环境读取：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:1028-1035`
- 种子行使用 `KEY_REF_ENV`：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:483-495`

**跨实例场景**

滚动发布或配置错误导致：

- 实例 A 配置账户 A 的 ENV Key；
- 实例 B 配置账户 B 的 ENV Key；
- 实例 C 未配置 ENV Key。

同一用户的连续请求可能分别使用 A、B 的上游账户，或在 C 上得到不可用。共享 DB 的 `config.version` 不会变化，因而现有版本刷新机制无法发现这种漂移。

**修复建议**

- 多租户生产模式禁用全局 ENV provider，使用按 scope 保存的加密密钥或统一的密钥管理引用。
- 若必须保留 ENV 引导，只能绑定到明确指定的单一 scope，并在该 scope 的真实请求到达时按需初始化；不得为所有租户复制同一把 ENV Key。
- 所有实例必须使用相同密钥来源，并记录不含密钥材料的指纹一致性告警。
- 不得以 `TenantUserDirectory.tenants()` 或任何全租户列表驱动种子。

**后续单点验证建议（不在本次执行）**

三个实例分别配置不同 ENV Key 和缺失 ENV Key，验证系统不会把同一 scope 的请求静默发往不同账户；启动日志和 DB 中不得出现其他 scope 的种子行。

---

### B-04：P1——同名 provider 迁移会破坏查询唯一性和密文归属

**当前防护依赖全局唯一 code**

- 全局查询：`backend/src/main/java/com/example/configmgr/ai/config/db/AiProviderRepository.java:11`
- 创建重名检查：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:238-241`
- 种子也按全局 code 查：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:143-147`

密文 AAD 为：

```text
provider.code + "|" + keyRef
```

见：

- `backend/src/main/java/com/example/configmgr/ai/config/AiKeyCipher.java:263-265`

**风险**

若只删除 `UNIQUE(code)` 以允许跨租户同名：

1. `findByCode()` 可能返回多行，不再具有单一结果语义；
2. 两个 scope 的相同 `code + keyRef` 具有相同 AAD，复制密文后仍可解密，失去行归属绑定；
3. ENV 种子可能选中其他 scope 的同名行。

这是迁移风险；当前全局唯一约束下不是已发生的跨租户复制漏洞。

**迁移顺序建议**

1. 先增加 scope 列、复合唯一约束和所有 scope 查询。
2. 引入信封 v2，例如：

   ```text
   AAD v2 = tenantId | userId | providerId | provider.code | keyRef
   ```

3. 对明确归属的旧行，用旧 v1 AAD 解密后立即以 v2 重加密。
4. 在完成重加密前，不允许同名 code 跨 scope 落库，也不能简单复制旧密文给多个身份。
5. 切换所有读写后删除旧全局唯一约束；保留 v1 只读兼容时，必须仅用于已明确映射的旧行。
6. 既有全局行归属不明时应禁用并等待管理员认领，不能自动归给第一租户或复制给所有用户。

**后续单点验证建议（不在本次执行）**

两个 scope 创建相同 code/keyRef 后，交换 `key_cipher` 必须解密失败；旧 v1 行完成迁移后能用 v2 解密，且不再接受跨 scope 的 v1 密文。

---

### B-05：P1——短 ENV 密钥会被当成 `last4` 完整暴露

`AiKeyCipher.last4` 对长度不超过 4 的输入原样返回：

- `backend/src/main/java/com/example/configmgr/ai/config/AiKeyCipher.java:231-236`

UI 写路径有至少 8 位校验：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:802-806`

但 ENV 路径没有相同校验：

- 合成 provider：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:483-493`
- 种子重灌：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:162-166`

随后 `ProviderView` 返回 `keyLast4`：

- `backend/src/main/java/com/example/configmgr/ai/web/dto/ProviderView.java:19-21`
- `backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java:243-249`

**影响**

ENV Key 长度为 1～4 时，完整密钥被写入 `ai_provider.key_last4`，再经无身份 `GET /api/ai/providers` 返回。是否当前环境存在这种短密钥无法判定，但代码路径确定。

**修复建议**

- ENV 种子和合成行同样执行最小长度校验，短密钥 fail-closed，不生成 provider。
- `last4` 对过短输入返回 `null`，而不是原串。
- ENV provider 最好只返回 `keyConfigured/keyRef`，完全不持久化 `keyLast4`。
- 清理既有短密钥行的 `key_last4`，清理过程不得把值写入日志。

**后续单点验证建议（不在本次执行）**

分别使用 4 位和 8 位 ENV Key：前者拒绝且无任何明文落库/响应，后者只出现末四位。

---

### B-06：P2——模型目录缓存跨实例陈旧，且不在配置版本失效链中

当前缓存键只有：

```text
ai:cfg:models:<providerId>
```

读取和写入：

- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java:90-119`

`refresh=false` 在密钥可解后直接返回缓存：

- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java:61-87`

配置写后的版本发布只刷新快照和运行态，没有删除模型目录缓存：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java:62-68`

默认 TTL 为 10 分钟：

- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:150-157`

而 TTL 为 null、零或负数时，代码写入**无过期时间**的缓存：

- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java:112-119`

**跨实例场景**

1. 实例 A 从 provider 的旧 baseUrl/账户拉取模型并写共享 Redis。
2. 管理员修改同一 provider 的 baseUrl、modelsPath 或密钥。
3. 实例 B 调用 `refresh=false`。
4. B 仍获得旧端点/旧账户的模型列表，直到 TTL；无 TTL 配置下可无限持续。

当前数据库 ID 是全局自增主键，因此不能据此断言“当前已发生同 ID 跨租户碰撞”；当前越权读取主要由 B-01/B-02 造成。迁移后若不增加 scope，则会产生直接的跨身份缓存碰撞。

**修复建议**

- 键中增加 tenant/user/provider 和目录配置代次。
- 代次至少覆盖 baseUrl、modelsPath、keyFingerprint；使用 per-scope `config.version` 是最简单的实现。
- provider 更新、密钥清理和硬删后，新版本必须自然绕过旧缓存。
- 旧键不读，使用新 `ai:cfg:v2:` 命名空间，让旧键自然过期；不要在请求路径 `SCAN` 全部租户键。
- 非正 TTL 应表示禁用缓存，而不是永久缓存。

**后续单点验证建议（不在本次执行）**

两实例共享 Redis，修改 baseUrl/modelsPath/key 后，`refresh=false` 不得返回修改前清单；相同 provider ID 的不同 scope 缓存互不命中。

---

### B-07：P2——无身份健康接口泄露配置和内部错误

`/api/ai/health` 被精确排除身份过滤：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:68-69`

响应包含：

- 默认 provider/model/baseUrl：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2621-2637`
- 全部生效 provider 描述：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2650`
- 全局默认参数：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2652-2660`
- 原始 Redis 诊断：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2683-2686`

provider 描述含 baseUrl、keyRef、source、状态：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:1111-1128`

`RedisAvailability.describe()` 明确加入原始 `lastError`：

- `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:162-169`
- 错误来源：`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:106-108`
- 失败日志：`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:135-146`

**影响**

未认证调用者可获取内部上游地址、配置状态、参数和可能的 Redis 连接地址/异常细节，为后续攻击提供侦察信息。

**修复建议**

- 公共健康接口只返回 `available/code` 等最小事实。
- provider、默认参数和 Redis 原始错误移到经过身份与管理员授权的运维端点。
- 当前身份的可用性检查改为 `availability(scope)`，不能回显其他身份的默认配置。
- 外部响应统一使用 `describeForResponse()` 的安全子集，原始异常只留在受控日志中。

---

### B-08：P2——审计与观测无法归属真实租户/用户

审计写死：

```java
audit.setActor("local-user");
```

见：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:597-604`

实体注释仍按“单用户本地应用”设计：

- `backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAudit.java:37-39`

审计仓储提供全量列表查询，虽然当前主代码没有控制器消费：

- `backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAuditRepository.java:15-19`

解密告警仅以 provider code 去重，没有 scope：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:120-121`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:937-942`

**影响**

无法证明谁在何时修改了哪个身份的配置；同名 provider 迁移后，一个身份的解密告警还可能抑制另一个身份的告警。

**修复建议**

- 审计记录行归属 scope 与实际操作者 scope，操作者来自可信请求身份。
- 所有审计查询默认带 scope；跨身份运维查询应是独立权限能力，不属于普通配置读接口。
- 告警去重键增加 tenant/user/provider。
- 日志增加安全的 scope/trace 关联信息，但不记录明文、密文、完整指纹或密钥末四位。

**后续单点验证建议（不在本次执行）**

两个身份修改相同 code 的 provider，审计分别记录真实操作者，普通身份只能查询自己的审计；两边解密失败各自产生可区分告警。

---

### B-09：P2——探测超时与私网闸仍有资源及 SSRF 风险

**异步请求未取消**

`sendAsync` 后只执行 `future.get(timeout)`：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:143-160`

超时分支没有 `future.cancel(true)`：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:165-168`

因此调用方超时返回后，携带 Authorization 的请求和响应读取仍可能继续。

**响应体内存上限不完整**

`BodyHandlers.ofByteArray()` 会先完整接收响应，`boundedBody()` 只在之后截断：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:158-163`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:205-224`

**DNS 重绑定**

URL 闸在连接前解析并检查地址，但 HTTP 客户端连接时可能重新解析：

- `backend/src/main/java/com/example/configmgr/ai/config/AiEndpointGuard.java:94-112`
- 代码自身也登记了该限制：`backend/src/main/java/com/example/configmgr/ai/config/AiEndpointGuard.java:33-38`

默认私网关闭、禁止重定向是有效防线，但不能消除 DNS 重绑定。

**修复建议**

- 超时和中断时取消 future，并关闭不再需要的响应资源。
- 改为流式读取，在传输层累计字节达到上限即终止。
- 对探测接口增加身份、权限和每 scope 速率限制。
- 连接期固定已验证地址并保留原 Host/TLS 名称，或采用能再次验证实际连接地址的 HTTP 客户端。
- 私网放行开关不应成为普通用户可利用的共享配置能力。

**后续单点验证建议（不在本次执行）**

慢响应超过超时后确认连接被取消；超大响应在达到上限时停止读取；DNS 首次返回公网、连接时返回回环地址的请求必须被拒绝。

---

### B-10：P3——含密钥对象存在意外的字符串化/序列化泄露面

以下 record 会自动生成包含全部组件的 `toString()`：

```java
KeyResolution(KeyStatus status, String apiKey, String fingerprint)
ResolvedProvider(..., String apiKey, String fingerprint, ...)
ProviderCommand(..., String apiKey, ...)
```

位置：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:875-888`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:1083-1084`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:192-194`

此外 `AiProvider.keyFingerprint` 只有 `@ToString.Exclude`，没有 `@JsonIgnore`：

- `backend/src/main/java/com/example/configmgr/ai/config/db/AiProvider.java:84-92`

**当前判定**

本次搜索未发现这些对象的现成日志或直接 JSON 返回调用点，因此是风险而非已证实泄露。当前控制器通过 `ProviderView` 排除了明文、密文和指纹，这是有效防线。

**修复建议**

- 为含密钥 record 自定义脱敏 `toString()`，或直接禁止其字符串化。
- 对 `apiKey`、指纹和密文统一增加 JSON 序列化防线。
- 不将 `ResolvedProvider/KeyResolution` 作为 Web DTO 返回。
- `runtimeKeys()` 含指纹，继续保持仅诊断/测试用途，不进入普通观测接口。

---

## 4. 已证安全或当前边界内有效的机制

1. **同一全局配置内，跨 provider 同名 model 不会仅靠名字串用。**
   `resolveChatTarget(providerId, modelId)` 限定 provider，模型必须属于该 provider 的 enabled allowlist：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:653-666`、`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:736-747`。
   写路径和数据库也以 `(provider_id,model_id)` 唯一：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:375-378`、`backend/src/main/resources/db/migration/V1__schema.sql:215-230`。
   该结论只覆盖 provider 内归属，不覆盖租户/用户。

2. **DB 密钥正常路径采用加密和 fail-closed。**
   DB 解密失败不会回落 ENV：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:890-918`。
   AES-GCM 使用随机 IV：`backend/src/main/java/com/example/configmgr/ai/config/AiKeyCipher.java:155-175`。
   指纹使用 KEK 的 HMAC：`backend/src/main/java/com/example/configmgr/ai/config/AiKeyCipher.java:209-228`。
   修改 origin 时要求重新录入密钥：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:288-304`。

3. **正常 API 响应不返回明文、密文或完整指纹。**
   `ProviderView` 只包含配置状态和末四位：`backend/src/main/java/com/example/configmgr/ai/web/dto/ProviderView.java:5-21`。
   审计 diff 只写动作和末四位：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:47-49`、`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:323-330`。
   B-05 的短 ENV Key 是明确例外。

4. **当前“单一全局配置”前提下，DB 版本机制能促成跨实例最终收敛。**
   写入时锁版本行并递增：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java:35-59`。
   快照加载前后核对版本，避免混合快照：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:260-317`。
   运行态按版本淘汰：`backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java:200-240`。
   这不是即时 Pub/Sub，也不适用于多 scope；模型目录缓存和实例 ENV 漂移不在其保障范围内。

5. **RedisAvailability 的全局共享是合理的基础设施状态。**
   它只 PING 共享 Redis，不读取或写入用户数据，负缓存与 single-flight 无需 tenant/user 键：`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:78-127`。
   需要修复的是其无身份诊断出口，而不是给 PING 强行加租户键。

6. **探测共享 HttpClient 不在客户端默认配置中保存密钥。**
   每次请求单独构造 Authorization：`backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java:125-153`。
   异步任务直接捕获已解析的 baseUrl/apiKey，不在回调中读取 ThreadLocal；没有发现因缺少 TTL 装饰而自动改身份的代码路径。

7. **前端配置页未发现密钥或 provider 选择的浏览器持久化。**
   API Key 仅位于组件 reactive 状态：`frontend/src/views/AiSettingsView.vue:36-69`。
   保存成功后清空：`frontend/src/views/AiSettingsView.vue:461-470`。
   清空密钥输入框：`frontend/src/views/AiSettingsView.vue:505-509`。
   创建成功后清空：`frontend/src/views/AiSettingsView.vue:583-603`。
   `selectedProviderId` 也只是内存 ref，未写入 local/sessionStorage。前端不能弥补后端全局共享问题。

---

## 5. 本领域租户枚举/列表依赖

### 明确结论

**当前 `ai/config` 及对应配置控制器中，没有发现以下依赖：**

- 没有调用 `TenantUserDirectory.tenants()`；
- 没有循环租户列表来加载、刷新、种子化或清理配置；
- 没有通过全租户 Pub/Sub、Redis key 枚举或静态租户注册表构造配置；
- 没有 provider/model 的本地文件持久化或配置 Pub/Sub 通道。

### 需要区分的全局全表读取

以下虽不是“枚举租户接口”，但属于全局配置扫描：

- `AiConfigSeeder` 对 provider 全表 `count()`：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:89-95`
- `AiConfigService.loadOnce()` 读取全部 provider 和 setting：`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:310-317`
- 审计仓储存在全局列表方法，但当前主代码无消费方：`backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAuditRepository.java:15-19`

迁移后不能保留这些无 scope 查询，否则它们会退化为事实上的全用户配置枚举。正确方向是：**真实请求或既有 run 快照给出 tenant/user 后，只加载该 scope；没有身份就不加载、不预热、不刷新。**

---

## 6. 建议的优先修复顺序

1. **先封住 B-01**：配置写、探测、模型拉取全部要求身份与权限，阻止默认上游被任意劫持。
2. **再实施 B-02/B-04**：scope 数据模型、显式上下文、运行时和 Redis 键、密文 v2 必须作为一个兼容迁移整体设计，不能只给表加 `tenant_id`。
3. **处理 B-03/B-05**：禁用生产全局 ENV 共享，统一实例密钥来源，并封住短密钥回显。
4. **随后处理缓存、健康面、审计和探测加固**：B-06～B-10。
5. 全程保持“不枚举租户”：旧缓存使用新命名空间隔离，旧全局数据由明确归属映射迁移，不按租户列表复制。

本次仅进行静态只读审查，未验证实际 DB 数据、ENV 值、线上网关或运行中的跨实例行为；这些不影响上述代码层确定问题，但实际是否已发生密钥漂移或数据泄露仍无法判定。
