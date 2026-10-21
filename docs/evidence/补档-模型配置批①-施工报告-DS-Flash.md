# 补档 · 模型配置专项批①（表 + 读路径，行为等价）施工报告

- 日期：2026-10-21
- 范围：`docs/research/设计-模型配置功能专项-2026-10-21.md` §11 批①（§10 裁决全按建议侧）；表与读路径。批②（写路径 + 安全 + 设置页）/ 批③（对话面板 + 契约）/ 批④（审计落表、多 kid、私网白名单）**本批不做**。
- 分支：`feature/model-config-b1`，基点 `d8056d5`；施工笔 `6c19f63`、复审收口笔 `5f302f2`；合流笔见 `-提交报告-` §0（`--no-ff` 合入主线）。
- 施工链：施工（后端 745 / 121 suite）→ 复审 READY-WITH-NITS（0C/0I/7M/6N）→ 收口（M1/M2/M3/M4 + N2 半条，后端 750 / 122 suite）。
- 本件为**补档**：由合流提交官按**代码最终态 + 分支/合流实测**对齐转录。凡与施工自报不一致处，以代码与实测为准并写明（§4 数字一节）。
- 提交官自证行见 `-提交报告-` §0（`agentId` / `modelAlias` / `profileName` / `thinkingEffort`）。

## 0. 测试矩阵（先行登记；每条均落为用例）

> 用例名取自**最终态代码**（`backend/src/test/java/com/example/configmgr/ai/{config,web,run}/`），非施工期草稿名。

### 0.1 加密面（`AiKeyCipher`，AES-256-GCM / JDK 内置）

| # | 判据 | 用例 |
| --- | --- | --- |
| B1 | 加密→解密往返成立，AAD 绑 `provider.code|keyRef` | `encryptsAndDecryptsWithAadBoundToProviderAndKeyRef` |
| B2 | 密文**搬到别的 provider 行**解不开（AAD 不匹配 ⇒ `KEY_UNREADABLE`） | `ciphertextMovedToAnotherProviderRowCannotBeDecrypted` |
| B5 | 每次加密 IV 全新 ⇒ 同一明文两次密文不同 | `eachEncryptionUsesAFreshIvSoCiphertextDiffers` |
| — | 密文与指纹都**不含**明文子串 | `ciphertextAndFingerprintNeverContainThePlaintext` |
| — | `last4` 是唯一允许出现的 key 片段 | `last4IsTheOnlyAllowedFragmentOfTheKey` |
| — | 换 KEK ⇒ 旧密文不可读；`kid` 参与信封并校验 | `ciphertextFromAnotherKekIsUnreadableAndKekKidIsEnforced` |
| N2 | `kid` 非法（含 `.`、超长）不得把信封打成 5 段 | `illegalKidIsRejectedSoTheEnvelopeKeepsFourSegments` / `kidValidationFollowsTheCharsetAndLengthBoundary` |
| — | KEK 缺失/畸形 ⇒ **fail-closed**（加密/指纹/解密三口都抛码，不降级） | `missingKekFailsClosedForEncryptFingerprintAndDecrypt` / `malformedKekIsTreatedAsMissingInsteadOfSilentlyDerivingOne` |

### 0.2 泄漏面（`AiProviderLeakTest`）

| # | 判据 | 用例 |
| --- | --- | --- |
| B3 | `AiProvider#toString` 不带密文、不带明文（M1 收口后连**指纹**也不带） | `providerToStringNeverCarriesTheCipherOrThePlaintext` |
| — | 解不开的 key 的 WARN 只带稳定码，不带材料 | `unreadableKeyWarnCarriesOnlyTheStableCode` |
| — | 诊断视图与 `AiAvailability` 的 reason 不含 key 材料 | `diagnosticViewAndAvailabilityReasonNeverCarryKeyMaterial` |
| B4 | `GET /api/ai/providers` 响应体**键集**固定为三件套（加回 `cipher` 即红） | `providersResponseCarriesOnlyTheThreeKeyFacts` |

### 0.3 种子面（`AiConfigSeeder`）

| # | 判据 | 用例 |
| --- | --- | --- |
| B7 | 首启（空表 + env key）恰好写 1 provider + 1 model；`/health` 与 `/providers` 报出同一身份 | `startupSeedsExactlyOneProviderAndOneModelFromEnv` / `healthAndProvidersEndpointsReportTheSeededIdentity` |
| B8 | 第二次启动**不再写** | `secondStartupDoesNotWriteAgain` |
| B9 | strict seed：表非空时改 env 不改变 DB 值 | `strictSeedKeepsTheDatabaseValueEvenWhenEnvChanged` |
| §3.3 | `seed-force` 是唯一的重灌途径（且只刷 `source=ENV_SEED`） | `seedForceIsTheOnlyWayToRefillFromEnv` |
| B11 | 并发双启动仍只有一行（唯一约束兜底，异常记 INFO 不抛） | `concurrentDoubleStartupSeedsExactlyOneRow` |

### 0.4 读路径与解析（`AiConfigService`）

| # | 判据 | 用例 |
| --- | --- | --- |
| B6 | `key_ref=DB` 且 KEK 正确 ⇒ 解出可用 key；**KEK 不对不得回落 env** | `dbKeyWithTheRightKekResolvesToTheDecryptedValue` / `dbKeyWithAnotherKekIsUnreadableAndDoesNotFallBackToEnv` |
| — | 行存在但无 key ⇒ `AI_KEY_MISSING`（不是"可用"） | `providerWithoutAnyKeyMakesTheEndpointUnavailableWithKeyMissingCode` |
| — | **空表 + 无 env** ⇒ 与批①之前逐字相同的 503 契约 | `emptyTableWithoutEnvKeepsTheLegacyUnavailableContract` |
| — | **空表 + env** ⇒ 合成行与种子**逐值相同** | `emptyTableWithEnvResolvesToTheSameValuesAsToday` |
| — | 默认目标选择序：`is_default` 旗标 > 排序 + 设置项 > 首个可用；禁用行跳过 | `defaultModelFlagWinsOverOrderAndSettings` / `firstEnabledModelWinsWhenNoDefaultFlagAndSettingsComeAfter` / `firstUsableProviderWinsInSortOrder` / `disabledProviderIsSkipped` |
| — | 版本号 +1 ⇒ 快照重载（同实例，不重启） | `versionBumpReloadsTheSnapshot` |
| M2 | 加载**失败**时不得先把 Redis 版本记账（否则坏快照被永久冻住） | `failedLoadIsRetriedAtTheNextTickEvenWhenTheRedisVersionDoesNotChangeAgain` / `failedFirstLoadIsRetriedWithoutAnyVersionChange` |

### 0.5 热生效（`ProviderRegistryHotReloadTest`，真实双 stub 端口计数）

| # | 判据 | 用例 |
| --- | --- | --- |
| B13 | 改 `base-url` ⇒ 运行时重建，**下一次请求**打到新端点（旧端点计数不再增） | `baseUrlChangeRebuildsTheRuntimeAndTheNextRequestHitsTheNewEndpoint` |
| B14 | 换 `model` ⇒ 只重绑 model，复用同一个 `OpenAiApi` | `modelChangeRebindsTheModelButKeepsTheSameHttpApi` |
| — | 默认选项 = env 缺省逐值副本（**`streamUsage` 不丢**） | `runtimeOptionsInheritTheEnvDefaultsIncludingStreamUsage` |
| — | `ai_setting` 有值时覆盖 env 缺省 | `aiSettingDefaultsOverrideTheEnvOptionsWhenPresent` |
| M3 | `ai_setting` 脏值（`"hot"` / `"NaN"` / `"-5"`）⇒ WARN + 忽略该项，其余照常 | `dirtyAiSettingValuesAreIgnoredWithAWarningInsteadOfBlowingUp` |
| — | `RuntimeKey` 同值稳定、`baseUrl`/指纹变化即变 | `runtimeKeyIsStableForTheSameRowAndChangesWithBaseUrlOrFingerprint` |

### 0.6 可用性动态化与降级（`AiAvailabilityDynamicTest` / `AiConfigUnavailableSpringTest`）

| # | 判据 | 用例 |
| --- | --- | --- |
| B20 | 同一个 controller + availability 实例：加一行可用 provider ⇒ **下一次** `/chat` 200，不重启 | `addingAUsableProviderFlipsTheSameControllerTo200WithoutRestart` |
| — | 无 provider 且无 env key ⇒ 503 + **逐字原文案** | `chatIs503WithTheLegacyCodeWhenNoProviderAndNoEnvKey` |
| B10 | 空表 + 无 key：种子跳过、`/health` 保 503 形状、`/chat` 不建流、只读端点空列表降级 | `seedingIsSkippedWhenBothTheTableAndTheEnvKeyAreEmpty` / `healthKeepsTheLegacy503ShapeWithNoProvider` / `chatStillReturns503WithoutBuildingAStream` / `readOnlyConfigEndpointsDegradeToEmpty` |
| — | 空配置 ⇒ `/providers` 返回空列表而非报错 | `emptyConfigReturnsAnEmptyListRatherThanAnError` |

### 0.7 模型身份（`RunSnapshotModelIdentityTest`）

| # | 判据 | 用例 |
| --- | --- | --- |
| — | 7 参 `create` 固化 `providerId/modelId`，`asMap` 取证面透出 | `createPersistsTheModelIdentityOfTheTurn` / `asMapExposesTheIdentityForEvidence` |
| — | 5 参旧入口仍可用且身份为 null（旧快照兼容） | `oldCreateSignatureStillWorksAndLeavesTheIdentityNull` |

## 1. 红测取证（13 条反例注入，全部 RED-OK）

> 取证方式：先落断言再落实现；收口轮用"**把实现改回错的**"反证断言真的在守。每条都对应一个现在仍存在的用例——删掉实现即复红。

| # | 注入的反例（把实现改成） | 观察到的红 | 锁它的用例 |
| --- | --- | --- | --- |
| 1 | 加密时**去掉 AAD** | 密文搬到别的行仍能解开 ⇒ `ciphertextMovedToAnotherProviderRowCannotBeDecrypted` 红 | 同左 |
| 2 | **固定 IV** | 同明文两次密文相同 ⇒ 断言不等红 | `eachEncryptionUsesAFreshIvSoCiphertextDiffers` |
| 3 | 去掉 `AiProvider#keyCipher` 的 `@ToString.Exclude` | `toString` 含密文 ⇒ 红 | `providerToStringNeverCarriesTheCipherOrThePlaintext` |
| 4 | 去掉 `keyFingerprint` 的 `@ToString.Exclude`（M1 收口时的反证） | `Expecting actual … not to contain` 命中指纹 ⇒ 红 | 同左 |
| 5 | **KEK 兜底**（缺失时用固定/派生的 KEK） | fail-closed 三例全红 | `missingKekFailsClosedForEncryptFingerprintAndDecrypt` / `malformedKekIsTreatedAsMissingInsteadOfSilentlyDerivingOne` |
| 6 | DB key 解不开时**回落 env** | `expected: KEY_UNREADABLE but was: <env key>` ⇒ 红 | `dbKeyWithAnotherKekIsUnreadableAndDoesNotFallBackToEnv` |
| 7 | 种子**总写**（不看表是否非空） | `secondStartupDoesNotWriteAgain` 红 | 同左 |
| 8 | 表非空时**仍用 env 兜底更新** | `strictSeedKeepsTheDatabaseValueEvenWhenEnvChanged` 红 | 同左 |
| 9 | **不捕**唯一约束冲突（并发双启动直接抛） | `concurrentDoubleStartupSeedsExactlyOneRow` 红（异常冒泡） | 同左 |
| 10 | `RuntimeKey` **去掉 `baseUrl`** | 改 base-url 不重建 ⇒ 请求仍打旧端点，旧端点计数 +1 ⇒ 红 | `baseUrlChangeRebuildsTheRuntimeAndTheNextRequestHitsTheNewEndpoint` |
| 11 | 把 `model` **放进 `RuntimeKey`** | 换 model 时 `OpenAiApi` 也被重建 ⇒ `keepsTheSameHttpApi` 红 | `modelChangeRebindsTheModelButKeepsTheSameHttpApi` |
| 12 | **版本比对失效**（永远认为版本没变 / 失败也记账） | `versionBumpReloadsTheSnapshot` 红；M2 两例红（`expected: 2L but was: 1L`） | 同左 |
| 13 | 去掉 `/chat` 的**可用性门** / 给 DTO **加 cipher 字段** | 503 契约例与 `providersResponseCarriesOnlyTheThreeKeyFacts`（按键集断言）各红 | 同左 |

## 2. 落地形态（最终态）

### 2.1 数据面（`db/migration/V4__ai_provider_config.sql`，只建表不动既有表）

- `ai_provider` / `ai_model` / `ai_setting`（预置 `config.version=1`）/ `ai_config_audit`（**P2：只建结构不写数据**）。
- 唯一约束 `uq_ai_provider_code`、`uq_ai_model_provider_model`；实体落 `ai/config/db/`（范式照 `AiFeedback` + V3）。
- 泄漏面硬约束①：`key_cipher` 加 `@ToString.Exclude` + `@JsonIgnore`；M1 后 `key_fingerprint` 同样 `@ToString.Exclude`（`@Column` 保留，落库与运行时缓存键不变）。

### 2.2 加密（`AiKeyCipher`，AES-256-GCM，零新依赖）

- 信封 `v1.<kid>.<iv_b64url>.<ct+tag_b64url>`；12B 随机 IV / 128bit tag；**AAD = `provider.code|keyRef`** ⇒ 密文搬到别的行解不开。
- KEK = env `AI_SECRET_KEK`（base64 32B），fail-closed：缺失/格式不对 ⇒ `encrypt`/`fingerprint` 抛 `AI_SECRET_KEK_MISSING`、`decrypt` 抛 `KEY_UNREADABLE`；**无明文降级、无默认 KEK、不自动生成落盘**。
- 指纹 = `HMAC-SHA256(KEK, key)` 前 16 hex（§10-8：不用裸 SHA-256，避免"离线撞库/彩虹表"与 key 空间弱口令相关性）。
- N2：`kid` 只接受 `[A-Za-z0-9_-]{1,16}`；非法不静默改写，回落 `DEFAULT_KID` 并暴露 `isKidValid` 给调用方复核。

### 2.3 种子（`AiConfigSeeder`，`ApplicationRunner`）

- 仅当 `ai_provider` 空表**且** env key 非空：写 1 provider（`code=deepseek` / `source=ENV_SEED` / `key_ref=ENV` / `key_cipher=null` / `key_last4`）+ 1 model（`is_default` / `max_context=128000` / `supports_tool_calling=true`）。
- env key **不落库**；strict seed（表非空 ⇒ env 完全不参与）；`seed-force=true` 只重灌 `source=ENV_SEED` 行；并发双启动由唯一约束兜底（捕 `DataIntegrityViolationException` 记 INFO 不抛）。

### 2.4 读路径与热生效

- `AiConfigService`：只读快照 + 版本号（DB `ai_setting.config.version` 真源 / Redis `ai:cfg:ver` 跨实例信号 / 连不上退化本地版本比对，稳态 ≤1 次/秒/实例，**不在 chat 热路径查表**）；表空 + env key 时合成一行"与种子逐值相同"的临时 provider，消灭启动瞬间的可用性抖动窗口。
- `AiProviderRegistry`：`RuntimeKey = providerId|keyFingerprint|baseUrl|completionsPath`，变则下次取用重建，在飞轮持旧引用跑完；同 provider 换 model 走轻量重绑（复用 `OpenAiApi`）；默认选项 = `OpenAiChatProperties#getOptions()` 逐值副本（`streamUsage` 不丢）。
- `AiAvailability` 动态化：判据 = 存在 ≥1 `enabled` 且 key 可解析的 provider；三档码 `AI_UNAVAILABLE` / `AI_KEY_MISSING` / `KEY_UNREADABLE`；**空表 + 无 env 的码与文案与批①之前逐字相同**。
- 移除 4 处 `@Conditional(AiKeyPresentCondition)`（`AiModelConfig` / `ResilientChatService` / `ResumeService` / `StartupResumeRunner`），`AiKeyPresentCondition` 退役删除；`AiModelConfig` 不再产出 `ChatClient`/`ChatModel` 单例（记忆三件 bean 原地保留）；`StartupResumeRunner` 启动扫描前自检 availability，不可用直接跳过；`AiController#startupResume` 补"runner 在但不可用"的 `AI_UNAVAILABLE` 分支。
- `/health` additive：保留 `available`/`code`/`model`/`baseUrl` 与 503 语义，新增 `providerId`/`defaultModelId`/`providers`；`model`/`baseUrl` 优先取快照默认目标，取不到回落 env 回读（与今天逐值相同）。
- `GET /api/ai/providers`（只读，业务信封 `ApiResponse`）：DTO 无任何 key/cipher 字段，只有 `keyConfigured`/`keyLast4`/`keyRef`。
- `RunSnapshot` + `providerId`/`modelId`（additive，`asMap` 透出），`RunStore.create` 新增 7 参重载写入模型身份（5 参重载保留并委托 ⇒ 旧入口与既有用例零改动）。**批① 只写不读**（续跑取用口径是批③）。

### 2.5 assembly 随批（允许的测试修改，不算降格）

`ResilientChatService` 构造签名 `(ChatClient, ChatModel, …)` → `(AiProviderRegistry, …)`，随批改装配：`conformance/ChatTurnFrameSequenceConformanceTest`、`run/EmptyReplyTerminalTest`、`run/ConvergeMemoryToolCallStatesTest`；`conformance/InMemoryRunStore` 改为覆写 7 参 `create`。断言零改动。

## 3. 改动清单（43 文件 = 27 新 + 15 改 + 1 删，+3951 / −161）

| 面 | 文件 | 性质 |
| --- | --- | --- |
| 数据 | `db/migration/V4__ai_provider_config.sql` | 新（4 表） |
| 实体 | `ai/config/db/{AiProvider,AiProviderModel,AiSetting,AiConfigAudit}.java` + 3 个 `*Repository` | 新（7） |
| 加密 | `ai/config/AiKeyCipher.java` | 新 |
| 种子 | `ai/config/AiConfigSeeder.java` | 新 |
| 读路径 | `ai/config/AiConfigService.java` | 新（608 行） |
| 热生效 | `ai/config/{AiProviderRegistry,ProviderRuntime,AiUnavailableException}.java` | 新（3） |
| 可用性 | `ai/config/AiAvailability.java` | 改（动态化） |
| 装配闸退役 | `ai/config/AiKeyPresentCondition.java` | **删** |
| 装配 | `ai/config/AiModelConfig.java` | 改（不再产出模型单例） |
| 配置 | `ai/config/AiProperties.java` | 改（+`Config` 段） |
| 轮次 | `ai/run/{ResilientChatService,StartupResumeRunner,RunSnapshot,RunStore}.java` | 改（签名 / 身份字段 / 7 参重载） |
| 轮次 | `ai/run/ResumeService.java` | 改（**只删 `@Conditional` 与 import**） |
| Web | `ai/web/AiConfigController.java` + `ai/web/dto/{ProviderView,ModelView}.java` | 新（3） |
| Web | `ai/web/AiController.java` | 改（`/health` 扩展 + startup-resume 分支） |
| 配置 | `application.yml` | 改（`app.ai.config.*`） |
| 文档 | `README.md` | 改（环境表 / strict seed 段；M4 收口） |
| 测试 | `ai/config/{AiKeyCipher,AiProviderLeak,AiConfigSeeder,AiConfigService,AiAvailabilityDynamic,ProviderRegistryHotReload,AiConfigUnavailableSpring}Test` + `AiConfigTestFixture` | 新（8） |
| 测试 | `ai/web/AiConfigControllerTest`、`ai/run/RunSnapshotModelIdentityTest` | 新（2） |
| 测试 | `ai/conformance/{ChatTurnFrameSequenceConformanceTest,InMemoryRunStore}`、`ai/run/{ConvergeMemoryToolCallStatesTest,EmptyReplyTerminalTest}` | 改（4，装配/夹具） |

> 任务书登记的是"42 文件"；**实测 43**（`git diff --name-status d8056d5..5f302f2` = 27 A + 15 M + 1 D）。差额 1 = 施工期把 `README.md`（M4 收口所在）算在文档面外。无静默夹带：清单逐行可对。

## 4. 门禁（各段实测；数字以本表为准）

| 段 | 后端 | 前端 | 其它 |
| --- | --- | --- | --- |
| 基点 `d8056d5` | 702 / 112 suite | 512 / 26 文件 | 批② 收口态 |
| 施工笔 `6c19f63` | **745 / 745 · 121 suite**（+43） | 512 / 512（**零改动**） | check-docs 6/6 |
| 收口笔 `5f302f2` | **750 / 750 · 121 suite**（+5：M2×2 / M3×1 / N2×2；M1 为既有例内补断言） | 512 / 512 | check-docs 6/6 |
| 主线合流前 `edccc1f` | 811 / 127 suite | 522 / 27 文件 | 含防护批① + 批②③ + L-19 |
| **合流后（本批提交官复跑）** | **859 / 859 · 136 suite**（0F/0E/0S，连跑 2 次） | **522 / 522 · 27 文件** | typecheck 0 · build ✓ · check-docs 6/6 · 脱敏 0 |

811 + 48 = **859**（+43 施工 + 5 收口），与任务书预判的"859 量级"逐数吻合。suite 口径 = `TEST-*.xml` 文件数（**136** = 主线 127 + 本批 9）；主控台 `-- in <类>` 行有 138 行，多出的 2 行是 `GenerativeFormGateTest` 的重复打印（同一类两行），**按类去重后 136**。批③ 复审裁决⑤ 的口径不变：**用例数取 mvn 汇总行、suite 取类/文件计数**。

## 5. 施工自决（六条，均有代码锚）

1. **env key 不落库 + KEK fail-closed**：`key_ref=ENV` 的行运行时从同一 `spring.ai.openai.api-key` 属性现读，库里只有 `key_last4`；真要 DB 托管才走密文，而 KEK 缺失/畸形一律抛码、**不做明文降级、不自动生成落盘**（`AiKeyCipher#requireKek`）。
2. **strict seed 为默认，`seed-force` 是唯一逃生门**：表非空后 env 完全不参与 —— 否则运维改 env 会静默改掉 DB 真源，与"DB 为真源"自相矛盾；重灌只刷 `source=ENV_SEED` 行，用户改过的行不动。
3. **空表 + env 时合成一行"与种子逐值相同"的临时 provider**：消灭"启动瞬间 / 首次迁移后"那段表空可用性抖动窗口；合成行走同一套解析与选项装配，不是特判分支。
4. **指纹取 HMAC-SHA256(KEK,key) 前 16 hex，不取裸 SHA-256**：指纹会进 `RuntimeKey` 与日志，裸哈希等于把"可离线比对的 key 摘要"发给任何能读日志的人；HMAC 让比对仍需 KEK。
5. **密文 AAD 绑 `provider.code|keyRef`**：防"把 A 行的密文搬到 B 行"（写路径落地后这是最廉价的错配方式），也防迁移时行错位；代价是 code/keyRef 不可原地改（写路径批② 处理）。
6. **模型身份批① 只写不读**：`RunSnapshot` 固化 `providerId/modelId` 只为取证面 + 给批③ 的"按快照续跑"备数据；批① 若同时改取用口径，就把"行为等价"这条验收线变成了两件事。

## 6. 行为等价论证（六条，逐条可核）

1. **空表 + env**：种子 `base_url=${AI_BASE_URL}`、`completions-path=/chat/completions`、`model=${AI_MODEL}`、`key_ref=ENV`（运行时读同一 `api-key` 属性）—— 与今天 `spring.ai.openai.*` 三键**逐值等价**。
2. **默认选项逐值副本**：`OpenAiChatProperties#getOptions()` 全字段复制 + `setModel(同一值)`、`streamUsage` 显式保留 ⇒ 请求体逐字段一致。
3. **`done.model` 仍来自上游响应**：模型身份没有插进帧，帧类型集与帧内字段零变更 ⇒ 前端零改动（本批 0 前端文件）。
4. **空表 + 无 env**：`AI_UNAVAILABLE` + **原文案逐字**，`/chat` 不建流，`/health` 503，`providers` 空列表（`AiConfigUnavailableSpringTest` 四例锁住）。
5. **`/health` 旧键不降级**：`available`/`code`/`model`/`baseUrl` 同名同语义；新键是 additive，`AiConfigService` 未注入时旧键照旧（`@Autowired(required=false)` + 字段注入，构造器签名不变）。
6. **E2E 零回归**：桩模式真启动 jar 跑 `verify-e2e.ps1`，本分支 **PASS=30 / FAIL=1**，唯一 FAIL = **MAJOR-2**（R-137 红队补测的取消时序，非 29 主用例）；同一脚本 + 同一桩对**基线 `d8056d5`** jar 跑出**逐条相同的** 33 条 PASS/FAIL 清单（diff 为空）⇒ 该失败是基线既有环境时序问题，29 条主用例零回归。

**一处真回归已当场修掉（留痕）**：首轮 E2E 暴露出"provider/model 被插进了 `AI 轮次开始 … 上下文=… 披露工具=[…]` 日志行的字段之间"，打断了 E2E 的正则取证（TC15/GF1 红）。改为另起一行 `AI 轮次模型身份 runId=… provider=… model=…`，原行**逐字恢复**。

**泄密面实测**：E2E 库文件、`boot.log`、`gfc` 证据目录 grep 桩 key —— **0 命中**。

## 7. 未做 / 偏差（显式登记）

| 项 | 现状 | 去向 |
| --- | --- | --- |
| 写路径（provider/model 写端点、`/test`、`/models/fetch`） | 本批只读 | 批② |
| URL 闸 / 私网白名单 | **无任何 scheme 或私网校验**（本批 base-url 只来自 env 种子） | 批②（复审 N6） |
| 设置页 / 对话面板选择器 | 零前端改动 | 批② / 批③ |
| `ai_config_audit` 落表 | 只建结构、不写数据 ⇒ 批② 前"谁改了配置"无审计面 | 批②（复审 M7） |
| 续跑按快照取用 provider/model | 身份只写不读 | 批③（`PROVIDER_GONE` 同批） |
| `ai_setting` 脏值之外的**语义**校验（如 temperature 上界是否与上游一致） | 只做"可解析且有限/正整数" | 批②（复审 M3 的收敛面） |
| 两处 provider 视图字段集不一致 | `/health.providers[]`（精简 Map，`name`）vs `/providers`（`ProviderView`，`displayName` + `baseUrl`/`models`） | 批②（复审 M5） |
| `reload-interval` 无启动期守卫 | `≤0` 退化为"每 tick 比对"，不报错 | 批②（复审 M6） |

## 8. 纪律留痕

- 分支施工在隔离 worktree（`wt-model-config-b1`）内完成，**未碰主工作树**；禁 push 纪律在施工/收口两段均遵守（推送归合流提交官）。
- 断言只升不降：全部为新增用例；既有 4 个装配面只改构造/覆写形态，断言零改动。
- 合流面预判与实测一致（见 `-提交报告-` §3）：`AiProperties` / `AiController` 两处真冲突，`RunStore`/`ResumeService`/`InMemoryRunStore`/`application.yml` 四处自动合；**另有一处预判外**的语义冲突（`AiAvailabilityDynamicTest` 的 `chat` 调用点），由合流提交官适配。
- `?? data/` 未动未入库；仓外工作日志不入库。
