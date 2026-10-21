> 本文件由复核官（只读）在 worktree 内产出，**未 commit**；合流前供提交官取用。
> 只读纪律：零代码改动 / 零 commit / 零 push / 零服务启停；唯一写入为本文件与构建产物（`backend/target/`、`frontend/dist/`，均被 `.gitignore` 豁免）。

# 复核报告 · 模型配置专项批②（写路径 + URL 闸 + 审计 + AI 设置页）合流前（DS-Flash，2026-10-21）

## 0 自证与对象（DC-19）

| 项 | 值 |
| --- | --- |
| 执行者模型 | **DeepSeek/deepseek-flash**（复核官，只读复核角色） |
| 授权来源 | **用户 2026-10-21 指令：前序任务直接实施** |
| wire 自证行（本次实际 grep `agents/agent-1363/wire.jsonl` 的 `profile.bind`） | `modelAlias="DeepSeek/deepseek-flash"` · `profileName="coder"` · `thinkingEffort="high"` |
| 复核对象 | 分支 `feature/model-config-b2`，worktree `wt-model-config-b2`，HEAD **`3ed8fba`**（= 施工 `c87047c` + 复审收口 `3ed8fba`） |
| 合流目标 | 主仓 `<REPO_ROOT>`，main **`a5539c2`**（merge-base = `3c79eaa`） |
| diff 基准 | `git diff a5539c2...3ed8fba`（38 文件，+5460/−53） |
| 设计依据 | `docs/research/设计-模型配置功能专项-2026-10-21.md` §11 批② 行；批① 已入库（合并笔 `a6a93cd`） |

## 1 结论：**READY-WITH-NITS**（0 Critical / 3 Important / 8 Minor）→ **建议合流**

- 批② 的四个实体面（写路径、URL 闸、审计、设置页）**主体实现到位**，安全面的关键判据（KEK fail-closed、明文不落库/不落日志/不落响应、last4 掩码、URL 闸默认禁私网、探活不跟随重定向）**逐条有代码 + 用例双落点**，断言只升不降。
- 门禁全绿：后端 **963/963 · 0F/0E/0S**、前端 **536/536 · 28 文件**、`vue-tsc` **0 错**、`vite build` **成功**；与 main 的合并树**无冲突**。
- 3 条 Important 全部是"**不阻断合流、但必须在提交报告登记处置**"的性质（无鉴权的历史面被本批放大、证据补档缺失、设置页对私网种子行的可用性毛刺），无一条要求改代码才能入库。
- 8 条 Minor 见 §8，均建议登记到批③/批④ 或提交官文档回写。

## 2 范围符合性（对照设计稿 §11 批② 行）

| §11 批② 要求 | 落点 | 判定 |
| --- | --- | --- |
| provider/model 写端点 | `AiConfigWriter`（809 行）+ `AiConfigController` 新增 `POST/PUT/DELETE /providers[/{id}]`、`/providers/{id}/models[/{modelId}]`、`/settings/defaults` | ✅ |
| `/test` | `POST /providers/{id}/test` + `AiConnectionProbe`（四档稳定码 + 超时 + 字节上限） | ✅ |
| `/models/fetch` | `POST /providers/{id}/models/fetch` + `AiModelCatalog`（Redis TTL 10min，不自动写 allowlist） | ✅ |
| URL 闸 | `AiEndpointGuard`（scheme/凭据/端口/长度/私网/元数据，开关 `allow-private-endpoints`） | ✅ |
| 审计 | `ai_config_audit` 每次变更落一行，与数据行 + 版本号**同一事务**；key 面只写 `last4` | ✅（"可后置"项提前做了） |
| `AiSettingsView.vue` + 路由/菜单 | `frontend/src/views/AiSettingsView.vue`（707 行）+ `router/index.ts`、`App.vue` 菜单 | ✅ |
| stub `/models` 扩展 | `scripts/ci/stub-upstream.mjs` `handleModels`（含 401/403/404/405/500/empty/notarray 注入） | ✅ |

**夹带核查（超范围但可接受，需登记）**：

1. `AiModelResolver`（新类）+ `AiConfigService#resolveModelId` 改为委托 —— 属**批① 读路径重构**，行为等价（`isEnabled` 过滤移入 resolver，优先级链不变），动机是批③ 复用。用例 `AiModelResolverTest` 6 例 + 批① 既有断言全绿 ⇒ 判"重构非夹带"。
2. `/health.providers` 增加 `synthetic` / `baseUrl` 两键 —— 是**批① 复审登记 M5 的收口**，用例 `envFallbackRowIsMarkedSyntheticUntilItIsPersisted` 锁住。
3. `AiKeyCipher` 新增 `KEY_EMPTY`（M6）、`AiConfigControllerTest` 字段白名单升为**精确键集**（M7）—— 均为批① 复审登记项在批② 收口，断言**加强**而非放宽。
4. `ResumeServiceLeaseTest`（60 行）—— 与模型配置无关的**去 flake 修改**，落在收口笔 `3ed8fba`。断言由"含两结局"改为"恰一个 ACCEPTED + 恰一个 IN_PROGRESS + 胜方驱动期间租约仍在"，**只升不降**；属测试面技术债清理，建议在提交报告注明。

## 3 安全面（重点）

| 判据 | 实现 | 证据 | 判定 |
| --- | --- | --- | --- |
| 写路径校验 | code 正则 `[a-z0-9][a-z0-9_-]{0,63}`、displayName ≤128、protocol 仅 `OPENAI_COMPAT`、path 必以 `/` 开头且 ≤128、modelId 禁空白、数值区间（temp[0,2]/top_p[0,1]/max_tokens[1,200000] 且 ≤ 所选模型 max_output） | `AiConfigWriterTest#modelRangesAreValidatedOnWrite`、`#defaultsAreRangeCheckedOnWrite…`、`#invalidCodePatternIsRejected` | ✅ |
| 写路径鉴权 | **无**（全仓无 Spring Security / 无拦截器，`WebConfig` 只有 CORS；CORS 仅放行 `http://localhost:*`、`http://127.0.0.1:*`） | `grep` 全仓 + `WebConfig.java` | ⚠️ 见 I-1 |
| URL 闸：协议白名单 | 仅 `http`/`https`；`file:`/`ftp:`/`jar:` 拒 | `AiEndpointGuardTest#malformedOrCredentialedUrlsAreRejectedEvenWhenPrivateIsAllowed`（含 `file:///etc/passwd`） | ✅ |
| URL 闸：绝对地址/凭据/端口/长度 | scheme+host 必需；`user:pass@` 拒；端口越界拒；>512 拒；尾斜杠归一化 | 同上 + `#urlLongerThanTheColumnIsRejected` | ✅ |
| URL 闸：SSRF | 解析**全部**地址，任一 loopback/any-local/link-local/site-local/multicast/CGNAT(100.64/10)/192.0.0.0/24/IPv6 ULA 即 `BLOCKED_PRIVATE_ENDPOINT`；元数据主机名**任何配置下**拒 | `#privateTargetsAreBlockedByDefault`（11 个目标，含 `169.254.169.254`、`[::1]`、`metadata.google.internal`） | ✅ |
| 出站不跟随重定向 | `HttpClient…followRedirects(NEVER)` | `AiConnectionProbe.java:98` | ✅（比要求更严） |
| 种子例外边界 | `checkForOutbound(trusted)` 只跳过私网判定，scheme/凭据**仍生效** | `#seededBaseUrlBypassesOnlyThePrivateNetCheck` | ✅ |
| DNS 重绑定 | 写入时解析，连接期不复查 —— 类注登记为知悉风险（单用户本地应用） | `AiEndpointGuard` 类注"已知限制" | ⚠️ 已知情接受 |
| 密钥加密 | 复用批① `AiKeyCipher.encrypt(key, providerCode, KEY_REF_DB)`（AES-256-GCM + AAD 绑 code/keyRef）；`code` 因此不可改（`CODE_IMMUTABLE`） | `AiConfigWriter#sealKey`、`#codeCannotBeChangedInPlaceBecauseTheCipherIsBoundToIt` | ✅ |
| KEK fail-closed | KEK 缺失 ⇒ `AI_SECRET_KEK_MISSING` → HTTP 400，**事务外**加密 ⇒ 表里不留半行 | `#kekMissingIsRejectedAndNothingIsPersisted`、`AiConfigWriteEndpointTest#kekMissingSaveIsA400…` | ✅ |
| 明文不落库 | `key_cipher` 只有密文；`key_last4` 末 4 位；`key_fingerprint` = HMAC-SHA256(KEK) | `AiConfigWritePersistenceTest#noPlaintextKeyLandsInAnyColumnOfAnyConfigTable`（LISTAGG 全表扫明文 + 密文前缀） | ✅ |
| 明文不落日志 | 探活只记 `uri` / 异常类名；控制器 `log.debug(code, message)`；错误消息不含 key | 实读 `AiConnectionProbe`、`AiConfigController` | ✅（残留见 M-6） |
| 明文/密文不进响应 | `ProviderView` 只有四件套 + `keyStatus`；单测**精确键集**断言 | `AiConfigControllerTest`（M7 精确键集）+ 全局禁键名扫描 | ✅ |
| 审计完整性 | 每次变更 1 行：`actor=local-user`、`action`、`target_type/target_id`、`diff_json`（key 只写 `set/updated/cleared/none` + `last4`）、`at`（`@CreationTimestamp`）；与数据行同事务 | `#auditsAreRecordedForEveryMutation`（含动作序列 + `INCR` 次数逐一对齐）、`AiConfigWritePersistenceTest#auditRowsCarryOnlyTheLast4AndTheAction`（全表逐行不含明文/信封） | ✅（who 为标称，见 M-1） |
| 设置页不显示明文 key | `type=password` + `autocomplete="new-password"`、**无**"显示明文"开关、留空 = 不修改（请求体不带 `apiKey` 键）；卡片只显示 `last4` | `ai-settings-view.spec.ts` F2 组（4 例） | ✅ |

## 4 测试面

- **写路径正例**：创建（带 key/不带 key）、更新（留空不改 key / 显式 `clearKey`）、软删/硬删、模型增改删、默认参数整段替换、`/test` 与 `/models/fetch` 成功面。
- **反例**：重名 409、`code` 不可改 409、模型重名、数值越界 400、maxOutput>maxContext、maxTokens>模型上限、KEK 缺失 400、`BLOCKED_PRIVATE_ENDPOINT` 400、非法 baseUrl 400、404（provider/model 不存在）、`PROVIDER_IN_USE` 409。
- **URL 闸边界**：11 个私网/元数据目标 + 9 个畸形/带凭据 URL + 开关全开 + 种子例外 + 超长 URL。
- **审计断言**：动作/目标类型全序列、`INCR` 次数=变更次数、全表逐行不含明文与密文信封、`last4` 存在。
- **批① 断言**：`AiConfigControllerTest` 只**增加**了 `keyStatus` 断言与精确键集（原有 `keyConfigured/keyLast4/keyRef/source` 与禁键扫描全保留）；`AiConfigServiceTest`/`AiKeyCipherTest` 只新增用例，**无删改**。逐文件 diff 核对通过，判"只升不降"。
- 新增/改动用例规模：`AiConfigWriterTest` 27、`AiEndpointGuardTest` 24、`AiConfigWriteEndpointTest` 19、`AiConnectionProbeTest` 10、`AiModelCatalogTest` 8、`AiConfigWritePersistenceTest` 8、`AiModelResolverTest` 6 + 既有类新增 2 例 = **净增 104**（963 − 859）。

## 5 纪律面

- 前端新增文件**零** `style=` 内联、**零** `<style>` 块，全 Tailwind（含 `text-[#909399]` 一类的 arbitrary value，与既有页一致）。
- 新增/改动文件 emoji 计数 = **0**（`AiSettingsView.vue`、`ai-settings-copy.ts`、`aiConfig.ts`、`types/ai.ts`、spec）。
- 密钥/组织名字面量：全 diff 扫 `sk-`、厂商名、内网域名 = **0 命中**（仅测试用假 key `probe-key-1234abcd`、KEK `0x5a` 常量）。
- 文案口径：后端只回稳定 `code` + 中文兜底，前端按 `code` 查表（`ai-settings-copy.ts` 21 条），F3 用例锁"不显示后端中文"。

## 6 门禁实测（本 worktree 内，均不依赖服务）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && mvn -o test` | **BUILD SUCCESS**，Maven 汇总 `Tests run: 963, Failures: 0, Errors: 0, Skipped: 0`，3:31 |
| 后端计数（XML 求和） | `backend/target/surefire-reports/TEST-*.xml` 逐文件求和 | **143 文件 / 963 / 0 / 0 / 0** —— 与 Maven 汇总逐值相同（无 @Nested 重复计数） |
| 前端全量（首次） | `node node_modules/vitest/vitest.mjs run` | 535/536 通过；1 红 = `tests/b1-suspend-disconnect.spec.ts` **5s 默认超时**（与 mvn 全量并跑，机器满载） |
| 前端全量（空闲复跑） | 同上 | **28/28 文件 · 536/536 通过**；该 spec 单跑亦绿（15 例，最长用例 6.5s）⇒ 判**既有 flake**，非本批引入（本批未改该文件） |
| 类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0，0 错**（输出 0 行） |
| 生产构建 | `TMP=<TMP> TEMP=<TMP> node node_modules/vite/bin/vite.js build` | **exit 0**，`✓ built in 1m 4s` |
| 基线对照 | 设计稿 §11：批① 合流后后端 859、前端 522/27 | 本分支 **963**（+104）、**536/28**（+14/+1） |

## 7 合流冲突预判

- `git merge-base a5539c2 3ed8fba` = `3c79eaa`（批① 证据补档后一点）。main 与分支各领先 2 笔，但均为 docs/代码互不相交的改动。
- `git merge-tree --write-tree --name-only a5539c2 3ed8fba` → 单行输出树 `dd8c0c489445d534e8024b562ff0a5a232d81286`，**无冲突文件清单** ⇒ 合并干净（可用 `--no-ff`）。
- 唯一潜在语义交叉：main 侧 `docs/任务看板.md` / 设计稿 §11 尚无批② 行 ⇒ 合流后需提交官回写（见 M-5）。

## 8 发现清单

### Critical（0）

无。

### Important（3，均不阻断合流，要求登记处置）

**I-1 · 写端点无鉴权，且后端默认监听 0.0.0.0 —— 本批把"改端点"变成新的凭据外送路径。**
全仓无 Spring Security / 无拦截器 / 无 token（`WebConfig` 仅 CORS，且放行 `localhost:*` 任意端口）；`application.yml` 未设 `server.address` ⇒ 嵌入式 Tomcat 绑定全部网卡。批② 之前，没有任何 API 能把已存密钥发往任意主机；本批之后，同网段任意客户端可 `PUT /api/ai/providers/{id}` 把 `baseUrl` 指到自己控制的公网主机，再 `POST /providers/{id}/test` ⇒ 上游收到 `Authorization: Bearer <既有 key>`。
**判定**：这是项目级历史面（所有写端点均无鉴权），不是批② 的设计缺陷，URL 闸默认禁私网也已把"打内网"挡住。**要求**：提交报告登记为知悉风险；后续以独立小批把 `server.address=127.0.0.1` 设为默认（或引入最小 token），不要在批② 里夹带。

**I-2 · 批② 的施工报告 / 复审报告未入 `docs/evidence/`。**
批① 有完整四件套（施工/复审/复核确认/提交报告）；批② 目录下**只有批① 的补档**，批② 的复审结论（I-1/I-2 必修、M-1..M-3、N-1..N-6）目前只存在于 commit message `3ed8fba`。§11 明定每批走"施工 → 复审 → 收口 → 复核 → 提交官"。
**要求**：合流前补档，或由提交官在提交报告中明确豁免并转录复审结论（否则批③ 无从对账"哪些登记项已关"）。

**I-3 · 私网/回环 `ENV_SEED` 行在设置页"改不动"（即使只改显示名）。**
`AiEndpointGuard#checkForWrite` 对写路径**无种子例外**（只有 `checkForOutbound` 有），而前端 `providerBody()` 每次都回传 `baseUrl`。于是 `allow-private-endpoints=false`（默认）时，任何 baseUrl 为 `http://127.0.0.1:18399` / 内网网关的种子行，在设置页改 `displayName` 或启停之外任一字段都会得到 `BLOCKED_PRIVATE_ENDPOINT`。
**影响面**：CI 桩形态与"env + 内网自建网关"形态（设计稿 §9 明列的真实场景）。错误信息可读、fail-closed 正确，故不判 Critical；但"页面能列出却保存不了"是真实用户困惑。
**建议**（任选其一，工作量都小）：① 前端只提交**发生变化**的字段；② `updateProvider` 在 `baseUrl` 与现值相同时跳过闸；③ 在 §9 明示"编辑私网种子行必须先开 `allow-private-endpoints`"。当前无用例覆盖此形态（`userUpdateDetachesTheSeedRow…` 用的是可解析的公网域名 `env-endpoint:1234`）。

### Minor（8，登记即可）

- **M-1 · 审计 `actor` 恒为 `"local-user"`**：无登录体系下的标称值（与 `AiFeedback#createdBy` 同先例），"谁"只能追到"本机"。建议批④ 引入操作者位时回填。
- **M-2 · `deleteModel` / 硬删 provider 不清 `ai_setting['default.model_id']` 悬空引用**：读路径 `AiModelResolver` 会回落到"第一个 enabled 模型"，不会 500；但设置页可能显示一个已不存在的默认模型，且下次 `PUT /settings/defaults` 可能得到 `MODEL_NOT_FOUND`。建议批③ 随 `PROVIDER_GONE` 一并收口。
- **M-3 · `AiModelResolver` 抽取与 M5 的 `/health` 扩键严格说超出 §11 批② 字面范围**（理由充分：批③ 复用 + 批① 登记项收口，行为等价）。建议设计稿 §11 批② 行补一句"含批① 登记 M5/M6/M7 的收口"。
- **M-4 · `ResumeServiceLeaseTest` 去 flake 夹在批② 收口笔**：断言加强、与批② 无关。建议提交报告注明"测试面技术债，不含产品行为变更"。
- **M-5 · 设计稿 §3.5 的"封闭错误码表"未收录本批新增五条**（`PROVIDER_EXISTS`/`MODEL_EXISTS`/`CODE_IMMUTABLE`/`INVALID_PARAM`/`INVALID_MODEL`），目前只在 `AiConfigWriter` 类注登记。合流后需回写设计稿，否则"封闭表"名存实亡。
- **M-6 · 新增 `ProviderCommand`/`DefaultsCommand` 是含 `apiKey` 明文的 record**，`toString()` 会打印明文；当前**无**日志点引用它，但缺一条"防止未来 `log.debug(command)`"的守护（批① M1 对 `AiProvider#toString` 做过同型加固）。建议给 `apiKey` 加 `@ToString.Exclude` 或补一个泄漏用例。
- **M-7 · URL 闸测试未覆盖已在实现里的两类判定**：IPv6 ULA（`fc00::/7`）、`192.0.0.0/24`；`::ffff:` 映射形态也未测（JDK 通常归并为 `Inet4Address`，但无断言）。实现有、测试无 ⇒ 回退不会被抓。
- **M-8 · `AiConnectionProbe` 内部 `new AiEndpointGuard(properties)` 而非注入 bean**：绕开容器单例（当前无状态、无功能影响），但让"闸有第二份实现"成为可能。建议改构造注入。

## 9 可合流性判断

**建议合流。** 批② 的写路径、URL 闸、审计、设置页在"正确性 + 安全默认值 + 用例证据"三层都成立，门禁全绿且与 main 无冲突；范围基本贴着 §11 批②，超范围的三处均为批① 登记项收口或测试面技术债，可在提交报告一次性说清。合流前请提交官：① 登记 I-1 为知悉风险；② 补批② 施工/复审报告（或书面豁免）；③ 把 I-3 的第二条（前端只提交变化字段）排进批③ 的首个小项。
