> 来源=<仓外证据>/ai-full-audit/gaps/03-config-provider-lease.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# 缺口分配 G3｜配置与供应方 / 租约与多实例状态 / 门与工具断言

- **主责域**：D4（配置/密钥/健康/停机）　**副域**：D3（多实例/租约）
- **规模**：61 个文件（生产 14 / 测试 47）
- **为什么算缺口**：`ai/config/{AiConfigException,AiRetiredKeysWarner,AiRetryConfig,AiUnavailableException}`、`ai/config/db/*`（6）、`ai/run/{ReplyLanguage,SecurityEvents,StreamViolationException,UsageDetails}` 零命名词；对应 47 个测试（config 24、gate 5、session 1、run 状态族 16、`AiConfigControllerTest`）未被任何域评估。
- **风险定位**：这是 S-04（key 外发）的仓储与审计面、RT-02/RT-09/RT-10（租约/fence/号段）的**测试证据面**、以及 CI-15（`InMemoryRunStore` 共享引用导致覆盖类缺陷不可见）的**旁证面**。G3 不重复审已审代码，只补"仓储/审计/租约状态机"的边界与断言强度。

## 必读清单（61）

| 路径 | 类型 | 重点核查点 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/config/AiConfigException.java` | 生产 | 配置异常/密钥退休/重试默认值 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiRetiredKeysWarner.java` | 生产 | 配置异常/密钥退休/重试默认值 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiRetryConfig.java` | 生产 | 配置异常/密钥退休/重试默认值 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiUnavailableException.java` | 生产 | 配置异常/密钥退休/重试默认值 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAudit.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiConfigAuditRepository.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiProviderModelRepository.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiProviderRepository.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiSetting.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/config/db/AiSettingRepository.java` | 生产 | 供应方/模型/设置仓储与配置审计 |
| `backend/src/main/java/com/example/configmgr/ai/run/ReplyLanguage.java` | 生产 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/main/java/com/example/configmgr/ai/run/SecurityEvents.java` | 生产 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/main/java/com/example/configmgr/ai/run/StreamViolationException.java` | 生产 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/main/java/com/example/configmgr/ai/run/UsageDetails.java` | 生产 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiAvailabilityDynamicTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiChatTargetResolutionTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiConfigUnavailableSpringTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiConnectionProbeTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiEndpointGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiKeyCipherTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiModelCatalogTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiModelResolverTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiParkConfigTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesContextGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesLimitGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesMemoryGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesSheetGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesStreamGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesT35DefaultsTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiProviderLeakTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiProviderRegistryUpstreamTimeoutTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiResumeZombieAgeConfigTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiResumeZombieAgeGuardTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiRetiredKeysWarnerTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/AiRetryDefaultsTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/RedisAvailabilityDegradationTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/RedisAvailabilityReasonTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/config/ResultBudgetPropertiesTest.java` | 测试 | 配置异常/密钥退休/重试默认值 |
| `backend/src/test/java/com/example/configmgr/ai/gate/FakeStartImportTool.java` | 测试 | 门/工具超时/作用域断言 |
| `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerGateArmTest.java` | 测试 | 门/工具超时/作用域断言 |
| `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerScopeGuardTest.java` | 测试 | 门/工具超时/作用域断言 |
| `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerToolTimeoutTest.java` | 测试 | 门/工具超时/作用域断言 |
| `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerTraceFallbackTest.java` | 测试 | 门/工具超时/作用域断言 |
| `backend/src/test/java/com/example/configmgr/ai/run/FakeRenewScheduler.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/LeaseKeysTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/LeaseServiceTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunParamsFreezeTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunSnapshotEpochCompatTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunSnapshotModelIdentityTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreActivityAnchorTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreReadFailureFailClosedTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreRunIndexPruneTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreSaveSuspendedTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreSlidingTtlTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RunStoreTouchTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SecurityEventsTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SessionKeyRegistryTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/ToolActivityBeaconSegmentTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/UsageDetailsTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/session/SessionPurgeDatabaseTest.java` | 测试 | purge 数据库面断言 |
| `backend/src/test/java/com/example/configmgr/ai/web/AiConfigControllerTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |

## 必须回答的验收问题

1. `AiConfigAudit`/`AiConfigAuditRepository` 的审计 diff 是否记录"baseUrl 变更 + key 复用"这一组合（S-04 的可追溯性）？不记录则 S-04 事后不可发现。
2. `AiProviderRepository`/`AiProviderModelRepository`/`AiSettingRepository` 的读取是否可能返回"半更新"的 provider+model 组合（D-F-08 的并发写）？
3. `AiKeyCipher` 的单 kid 信封与 `AiRetiredKeysWarner` 的轮换告警：旧 kid 数据能否被读？轮换期间 `/test` 会用哪把 key？
4. `AiRetryConfig`/`AiRetryDefaults` 与 `AiProperties` 的默认值是否一致（是否有"代码默认 ≠ yml 默认"的第二处，类似 S-16 的 park 错位）？
5. `SecurityEvents`/`SecurityEventsTest`：安全事件台账是否覆盖"拒绝/越权/限流"三类，且 `ai:safety` 的写入不被 fence 拦（B 域称人决策类写入刻意不问 authority）？
6. `UsageDetails` 的 token 计量是否与 `ContextMeter` 同源；`done.usage` 是否会因续跑丢 providerId 而错配模型价格？
7. `ReplyLanguage` 的判定与 `done.model` 回显是否冲突（D 域称"上游回显优先"）？
8. 24 个 `config` 测试中，哪些用**真实本地 HTTP 桩**（`ProviderRegistryHotReload` 级）、哪些只 mock？逐条标注，并把"只 mock"的列入 §K7.2 的假绿清单。
9. `LeaseServiceTest`/`RunStore*Test` 是否真的用 `FakeLeaseRedis` 复刻了 `epoch<=fence` 自愈与墓碑；`FakeRenewScheduler` 是否让"续租失败"路径可测（RT-10 的可测性）？

## 与 K 结论的挂接

| K 条目 | 依赖本组回答什么 |
|---|---|
| S-04（key 外发） | 审计是否留痕、轮换是否可控 |
| RT-02/RT-09/RT-10 | 租约/号段测试是否真能变红 |
| MD-08/MD-09（配置并发/版本） | 仓储层是否提供 CAS 原语 |
| CI-15（测试底座失真） | 这些用例是否继承 `InMemoryRunStore` 默认共享引用 |
| RT-13（取消写失败） | `SecurityEvents` 是否记录降级 |

## 边界

- 只读；不得读取/输出任何密钥内容（`AiKeyCipherTest`、`AiProviderLeakTest` 只核"不泄漏"断言，不取值）。
- 不得以本组结论替代真实 Redis 的 Lua 执行证据。
