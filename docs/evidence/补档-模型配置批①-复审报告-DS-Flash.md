# 补档 · 模型配置专项批①（表 + 读路径，行为等价）复审报告（DS-Flash，2026-10-21）

## 0 自证与纪律

- 复审方自证行以复审链原始报告为准；本补档由合流提交官转录，**不代签**（提交官自证行见 `-提交报告-` §0）。
- 与施工方互盲：只读落库的施工报告 + worktree 代码与用例，未读其 wire。
- 只读纪律：零 commit / 零 push / 零服务启停；唯一产出 = 本报告与逐条意见。
- 复审时点：分支 `6c19f63`（施工笔）。收口笔 `5f302f2` 的落地结果见 `-复核确认-`。

## 1 结论：**READY-WITH-NITS**（0 Critical / 0 Important / 7 Minor / 6 Nit）

- **0C / 0I 的判据**：批① 的验收口径是"**行为等价**"，而等价性有**可证的两侧**——(a) 空表 + env 的请求体/文案逐值等价（§2 i / 施工报告 §6），(b) 29 条 E2E 主用例零回归 **且** 与基线 `d8056d5` 的 PASS/FAIL 清单逐条相同。两侧都实测成立，故无阻断项。
- **7M / 6N 的分布**：4 条 M 有明确代码修法且不扩范围（M1–M4，随收口笔落地）；3 条 M（M5/M6/M7）与 6 条 N 属"批① 边界外"或"必须等写路径才成立"，**登记不修**（§3 逐条给理由）。
- 断言只升不降：本批无一处"把断言改绿"；4 个既有测试文件的改动全是构造/覆写形态适配（§4）。

## 2 攻击面（a–j，逐条给落点与判据）

| # | 攻击面 | 判据 / 落点 | 结论 |
| --- | --- | --- | --- |
| **a** | **明文 key 的进程内存面** | 明文只在 `resolveKey` 的局部变量里活到"构造 client"为止，绝不进日志/异常消息；`AiProviderLeakTest#unreadableKeyWarnCarriesOnlyTheStableCode` 锁日志面 | 通过。**残余**：JDK 无法阻止 JVM 内部复制，`byte[]` 主动清零只制造"已擦除"的错觉 ⇒ 不做，边界写进 `AiKeyCipher` 类注（N2 后半条） |
| **b** | **密文落库面** | `key_cipher` 的 `@ToString.Exclude` + `@JsonIgnore`；`providerToStringNeverCarriesTheCipherOrThePlaintext` 锁 `toString`；M1 后指纹同样排除 | 通过（M1 收口后连"key 派生材料"也不出现在 toString） |
| **c** | **密文跨行搬运面** | AAD = `provider.code|keyRef`；`ciphertextMovedToAnotherProviderRowCannotBeDecrypted` | 通过。代价已登记：`code`/`keyRef` 不可原地改（写路径批② 处理） |
| **d** | **KEK 缺失/畸形面** | `requireKek` 三口 fail-closed；无默认 KEK、不自动生成落盘；`malformedKekIsTreatedAsMissingInsteadOfSilentlyDerivingOne` | 通过。**这条是"宁可整个写路径不可用，也不静默降级"的红线** |
| **e** | **响应体泄漏面** | `ProviderView` 键集 = `keyConfigured`/`keyLast4`/`keyRef`（+ 展示面字段），**无** `apiKey`/`keyCipher`/`keyFingerprint`；`providersResponseCarriesOnlyTheThreeKeyFacts` 按**键集**断言（加回 cipher 即红）；`/health.providers[]` 同口径（无 key 材料） | 通过 |
| **f** | **日志 / 诊断 / 可用性 reason 面** | `diagnosticViewAndAvailabilityReasonNeverCarryKeyMaterial`；三档码是稳定码而非"上游报错原文" | 通过 |
| **g** | **种子覆盖面** | strict seed + `seed-force` 只刷 `ENV_SEED` 行 + 并发双启动由唯一约束兜底（异常记 INFO 不抛）；B7/B8/B9/B11 四组用例 | 通过。**残余**：重灌动作本身无审计留痕（N5） |
| **h** | **热生效面** | `RuntimeKey` 四元组（providerId / 指纹 / baseUrl / completionsPath）；在飞轮持旧引用跑完；换 model 只重绑（复用 `OpenAiApi`）；版本号真源 + **加载失败下一个 tick 无条件重试**（M2） | 通过（M2 修的是"坏快照被永久冻住"：旧实现先记版本再读库） |
| **i** | **可用性降级面** | 三档码；空表 + env 合成行；空表 + 无 env 的**文案逐字**与 503 形状；`StartupResumeRunner` 自检 + `AiController#startupResume` 补 `AI_UNAVAILABLE` 分支 | 通过。**残余**：`/health` 的 `model`/`baseUrl` 回落 env 而 `providerId`/`defaultModelId` 为 null ⇒ 三者不同源（N3） |
| **j** | **模型身份面** | `RunSnapshot` additive（旧快照反序列化 null）、`asMap` 透出、7 参 `create` 写入、5 参委托；批① **只写不读** ⇒ 续跑取用口径零变更 | 通过。**残余**：身份日志行无 E2E 断言（N4） |

> **攻击面口径**：a–j 是"key 材料从库到响应到日志的完整搬运链"+"两个行为面（热生效、可用性）"+"一个取证面（模型身份）"。缺任何一环，"行为等价"就退化成"看起来等价"。

## 3 意见逐条（M1–M7 / N1–N6）

### 3.1 Minor（有修法、不扩范围；M1–M4 随收口笔 `5f302f2` 落地）

| # | 意见 | 为什么算 M（不是 N） | 收口 |
| --- | --- | --- | --- |
| **M1** | `AiProvider#keyFingerprint` 未排除 `toString` | 指纹虽是单向摘要，但足以离线回答"两处 key 是否同一个"，属 **key 派生材料**；`toString` 会进日志 | 加 `@ToString.Exclude`（`@Column` 保留 ⇒ 落库与运行时缓存键不变）+ 泄漏用例补断言（**先红**：`Expecting actual … not to contain`） |
| **M2** | `lastRedisVersion` 在**读库之前**推进 | 真实事故面：看到新版本先记账、再读库；读库一失败 ⇒ 坏快照**永久冻住**（直到人工改配置），表现为"改了配置不生效且无任何告警" | `ReloadCheck`（是否重载 / 本次观察到的版本）+ `LoadOutcome`（快照 / 是否成功）；只在 `ok=true` 推进版本；新增 `lastLoadFailed` ⇒ 下一个 tick 无条件重试 |
| **M3** | `ai_setting` 脏值直接抛 | `temperature="hot"` / `"NaN"` / `maxTokens=-5` 会让**整条读路径**炸掉——一个手填的设置项能关掉全部 AI | `buildOptions` 逐项校验（可解析且有限 / 正整数），不合格 WARN + 忽略该项、其余照常 |
| **M4** | `README.md` 环境表仍写"为空则不装配模型 bean" | 批① 把装配闸拆了，文档说的和代码做的相反；运维按文档排查会走错方向 | 删过期表述 + 补 `AI_SECRET_KEK`/`AI_SECRET_KEK_KID` 两行 + 新增 strict seed 段 |

### 3.2 Minor（登记不修，理由是"批① 边界外"）

| # | 意见 | 证据 | 去向 |
| --- | --- | --- | --- |
| **M5** | **两处 provider 视图不同形**：`/health.providers[]` 是精简 `Map`（`id/code/name/enabled/keyConfigured/keyRef/source/status`），`/providers` 是 `ProviderView`（`displayName` + `baseUrl`/`completionsPath`/`modelsPath`/`keyLast4`/`models`） | `AiConfigService#describeProviders:591-607` vs `web/dto/ProviderView` 记录 | 批②（设置页要消费完整视图，届时统一为一种 DTO；现在改会让 `/health` 的 additive 面提前定型） |
| **M6** | **`app.ai.config.reload-interval` 无启动期守卫**：`≤0` 不报错而是退化为"每 tick 都比对"，与同文件里 `verifySseWriteTimeout` / `verifyRateLimit` 两处"配错即启动失败"的姿态不对称 | `AiProperties` 只有两个 `@PostConstruct` 守卫 | 批②（该键的取值依据要等写路径的推送面定稿） |
| **M7** | **无回滚路径、无审计面**：V4 只建表（可 `flyway` 前滚不可 undo）；`ai_config_audit` 只建结构不写数据 ⇒ 批② 前"谁在什么时候改了什么"无法回答 | `V4__ai_provider_config.sql`（P2 裁决：只建结构） | 批②（审计是写路径的伴生面；本批不写库就没有可审的动作） |

### 3.3 Nit（6 条，全部登记）

| # | 意见 | 证据 | 去向 |
| --- | --- | --- | --- |
| **N1** | `AiKeyCipher#last4` 对长度 ≤4 的 key 返回**整串**（`key.length() <= 4 ? key : substring`） | `AiKeyCipher:218-223` | 批②（写路径引入用户输入 key 后才有实害；现在 key 来自 env，长度不受控但不是攻击面） |
| **N2** | `kid` 未做字符校验：信封是 `v1.<kid>.<iv>.<ct>` 点分段，`kid` 含 `.` 会把段数变 5 ⇒ **自己的 kid 把完好密文打成 `KEY_UNREADABLE`** | 复现：`kid="k.1"` ⇒ 4 段变 5 段 | **半条已修**（`[A-Za-z0-9_-]{1,16}` + 非法回落 `DEFAULT_KID` + 暴露 `isKidValid`）；另一半（KEK/明文 `byte[]` 主动清零）**不做**，理由见 §2 a |
| **N3** | `/health` 的 `model`/`baseUrl` 在快照无默认目标时**回落 env**，而 `providerId`/`defaultModelId` 此时为 `null` ⇒ 三者不同源 | `AiController#health` 的两分支 | 批②（消费者应判 `providerId == null` 再读 `model`；口径写进设置页对接说明） |
| **N4** | 新增的 `AI 轮次模型身份 runId=… provider=… model=…` 日志行**无 E2E 断言**（`scripts/verify-e2e.ps1` 零命中），取证靠人眼 | `grep 模型身份 scripts/verify-e2e.ps1` = 0 | 批③（身份变成续跑的判据后才有断言价值） |
| **N5** | `seed-force` 重灌是**静默动作**（只记应用日志，无审计行） | `AiConfigSeeder` 的 `seedForce` 分支 | 批②（与 M7 同源） |
| **N6** | `base-url` **无任何 scheme / 私网校验**：本批只来自 env 种子，但写路径一旦落地，一行 DB 就能把请求导向任意地址 | 设计稿 §3.3 明写"URL 闸属批②" | 批②（**写路径的前置条件**，不是可选项） |

## 4 数字与断言面

| 项 | 复审时读数 | 说明 |
| --- | --- | --- |
| 后端 | **745 / 745 · 121 suite**（施工笔） | 基点 702 ⇒ +43；与施工报告一致 |
| 前端 | **512 / 512 · 26 文件** | 本批零前端改动（含 0 新 spec） |
| check-docs | **6/6** | `README.md` 改动后仍绿 |
| 断言只升不降 | 通过 | 4 个既有测试文件 = 构造签名 / 7 参覆写适配，**断言零改动**；无删例、无放宽 |

## 5 遗留清单（本复审轮）

1. **M5/M6/M7 + N1/N3/N4/N5/N6** 八条登记至批② / 批③（§3.2 / §3.3 逐条给去向）。N2 半条落地、半条显式不做。
2. **`ai_setting` 的"语义正确性"不在本批复审范围**：只验了"脏值不炸"，没验"值是否与上游契约一致"（如 `top-p` 上界）——批② 写端点时要按 provider 能力位校验。
3. **`/health` 的 additive 面已定型**（`model`/`baseUrl`/`providerId`/`defaultModelId`/`providers`）：批② 若要改字段集，须按"只增不改名"处理（既有消费方 = 前端面板与 E2E 就绪探针）。
