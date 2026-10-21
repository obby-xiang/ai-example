# 补档 · 模型配置专项批①（表 + 读路径，行为等价）复核确认（DS-Flash，2026-10-21）

## 0 自证与纪律

- 复核方自证行以复核链原始报告为准；本补档由合流提交官转录，**不代签**（提交官自证行见 `-提交报告-` §0）。
- 与施工方互盲：只读其落库的施工报告与工作树，未读其 wire。
- 只读纪律：零 commit / 零 push / 零服务启停；只改工作树。
- 复核时点：分支 `5f302f2`（收口笔之后）；合流后读数见 §5（由提交官复跑补入，标注口径）。

## 1 结论：**READY-WITH-NITS**（0C / 0I / 0M，准予入库）

- 复审 **0C/0I/7M/6N** 中，**5 条收口**（M1 / M2 / M3 / M4 / N2 半条）、**8 条登记**（M5 / M6 / M7 / N1 / N3 / N4 / N5 / N6 → 批② / 批③）。
- 收口的判据是**代码 + 用例双落点**，不以"报告里写了"为准；登记的判据是**写明去向与理由**，不假装修了（§3）。
- 断言只升不降终核通过（§4）。

## 2 五条收口逐条核验

| # | 复审要求 | 最终态 | 核验方式 |
| --- | --- | --- | --- |
| **M1** | 指纹不进 `toString` | `AiProvider#keyFingerprint` 加 `@ToString.Exclude`（`@Column` 保留 ⇒ 落库与运行时缓存键不变） | `AiProviderLeakTest#providerToStringNeverCarriesTheCipherOrThePlaintext` 补断言；**先红**（注解未加时 `Expecting actual … not to contain` 命中指纹值） |
| **M2** | `lastRedisVersion` 只在 load 成功之后推进 | 新增 `ReloadCheck(是否重载, 本次观察到的 Redis 版本)` 与 `LoadOutcome(快照, 是否成功)`；`checkReload` 不再落地版本，`applyLoad` 在 `ok=true` 时才推进；新增 `lastLoadFailed` ⇒ 下一个 tick 无条件重试 | 两例：`failedLoadIsRetriedAtTheNextTickEvenWhenTheRedisVersionDoesNotChangeAgain`（Redis 路径，**红测 = 还原旧实现后 `expected: 2L but was: 1L`**）、`failedFirstLoadIsRetriedWithoutAnyVersionChange`（无 Redis / 首次失败）。夹具加"N 次读库失败"注入 + `StringRedisTemplate` 桩 |
| **M3** | `ai_setting` 脏值容错 | `buildOptions`：`temperature`/`topP` 需可解析且有限、`maxTokens` 需正整数；不合格 WARN + 忽略该项（沿用 `spring.ai.openai.*` 缺省），其余项照常 | `dirtyAiSettingValuesAreIgnoredWithAWarningInsteadOfBlowingUp`（`"hot"`/`"NaN"`/`"-5"` 三项各 WARN 一次；**红测 = 去掉 try/catch 后 `NumberFormatException: "hot"`**） |
| **M4** | `README.md` 环境表口径 | 删"为空则不装配模型 bean"的过期表述；补 `AI_SECRET_KEK` / `AI_SECRET_KEK_KID` 两行；新增 strict seed 段（首启种 1 provider 1 model、`source=ENV_SEED`、`key_ref=ENV`、env key 不落库；表非空后 env 只作种子；重灌需 `seed-force` + 重启；KEK 只服务 `key_ref=DB`，缺失即 fail-closed） | `check-docs.mjs` **6/6**；环境表两行与 `AiKeyCipher` 的常量逐字对账 |
| **N2**（半条） | `kid` 字符校验 | `AiKeyCipher`：`kid` 只接受 `[A-Za-z0-9_-]{1,16}`；非法**不静默改写**，回落 `DEFAULT_KID`（P1 单 kid）并暴露 `isKidValid` 给调用方复核 | `illegalKidIsRejectedSoTheEnvelopeKeepsFourSegments`（**红测 = 只 trim 不校验后 `expected: "k1" but was: "k.1"`**）、`kidValidationFollowsTheCharsetAndLengthBoundary` |

> N2 的**另一半（KEK/明文 `byte[]` 主动清零）不做**：JDK 无法阻止 JVM 内部复制，`Arrays.fill` 只制造"已擦除"的错觉；现边界 = 明文只在局部变量存在、绝不进日志/异常消息（理由与边界写进 `AiKeyCipher` 类注）。这是**显式不做**，不是漏做。

## 3 八条登记项（不修，写明去向）

| # | 登记项 | 为什么不修 | 去向 |
| --- | --- | --- | --- |
| M5 | `/health.providers[]`（精简 `Map`，`name`）与 `/providers`（`ProviderView`，`displayName` + `baseUrl`/`models`）不同形 | 现在统一会让 `/health` 的 additive 面提前定型；设置页才是完整视图的消费方 | 批② |
| M6 | `app.ai.config.reload-interval` 无启动期守卫（`≤0` = 每 tick 比对，不报错） | 该键的取值依据要等写路径的推送面定稿；现在加守卫是在"猜上界" | 批② |
| M7 | 无回滚路径 + `ai_config_audit` 只建结构不写数据 | 审计是写路径的伴生面；本批不写库就没有可审的动作 | 批② |
| N1 | `last4` 对长度 ≤4 的 key 返回整串 | key 来自 env、长度不受控但不是攻击面；写路径引入用户输入后才有实害 | 批② |
| N3 | `/health` 的 `model`/`baseUrl` 与 `providerId`/`defaultModelId` 不同源（前者回落 env） | 口径已写进 `AiController#health` 注释与复审报告；改字段集要动既有消费方 | 批②（设置页对接说明） |
| N4 | 模型身份日志行无 E2E 断言 | 身份还不是任何判据（批① 只写不读） | 批③ |
| N5 | `seed-force` 重灌无审计留痕 | 与 M7 同源 | 批② |
| N6 | `base-url` 无 scheme / 私网校验 | 设计稿明写"URL 闸属批②"；本批 base-url 只来自 env 种子 | 批②（**写路径的前置条件**） |

## 4 断言只升不降终核

| 面 | 结论 |
| --- | --- |
| 后端 | 745 → 750（**+5**，只增）；M1 是既有例内补断言。无删例、无放宽、无 `@Disabled` |
| 前端 | 512 / 512 · 26 文件，**零改动**（本批无前端文件） |
| 既有测试改造 | 4 个文件（`ChatTurnFrameSequenceConformanceTest` / `EmptyReplyTerminalTest` / `ConvergeMemoryToolCallStatesTest` / `conformance/InMemoryRunStore`）= 构造签名 / 7 参覆写适配，**断言零改动** |
| 红测可复现 | 5 条收口各有"把实现改回错的即复红"的实证（§2 逐条给红测现象） |

## 5 数字仲裁（**口径以本表为准**）

| 项 | 预估值 | 分支实测（施工→收口） | 合流实测（提交官复跑） | 差 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 后端用例 | 811 + 48 = **859** | 745 → **750** | **859 / 859**（连跑 2 次，0F/0E/0S） | **0** | 859 = 811（主线）+ 43（施工）+ 5（收口）；预估与实测逐数吻合 |
| 后端 suite | — | 121 → **121** | **136** | — | 136 = 主线 127 + 本批 **9** 个新测试类；`-- in` 行 138 行含 `GenerativeFormGateTest` 重复打印 2 行，按类去重 136 |
| 前端用例 | 522 | **512**（26 文件） | **522 / 522 · 27 文件** | **0** | 本批零前端改动；522 = 主线既有（防护批① 收口后的数字），分支侧的 512 是基点数 |
| 分支文件面 | 42 | **43** | — | **+1** | 27 A + 15 M + 1 D；差额 = `README.md`（M4 收口所在）未计入"42" |

**两口径一致**：合流后 859 = 主线 811 + 分支净增 48；分支净增 48 = 43（施工）+ 5（收口）。**无一项需要"不可复现"作解释**。

## 6 遗留清单（本确认轮）

1. **八条登记项**（M5/M6/M7/N1/N3/N4/N5/N6）→ 批② / 批③，逐条见 §3。
2. **N2 后半条显式不做**（`byte[]` 清零），边界已写进类注，不得在后续批次被当作"未完成项"重开。
3. **`/health` 的 additive 面已定型**：批② 若改字段集，须按"只增不改名"处理。
4. **合流面另有一处复审未预判的语义冲突**（`AiAvailabilityDynamicTest` 的 4 个 `chat(...)` 调用点 + 防护批① 的两道闸门字段注入）：由合流提交官适配（`chat(controller, req)` 适配器固定传 `null` + `ReflectionTestUtils` 灌入 `InputSanitizer`/`AiRateLimiter`），**断言零改动**。处置留痕见 `-提交报告-` §3。
