# 施工报告 · 模型配置批③（对话面板 + 契约）

> DC-19 首页：本页第一屏即可判断"这批复了什么、门禁是什么数、有哪些自决与待裁"。

## 0. 身份与范围（DC-19）

- 日期：2026-10-21
- 施工官自证（`profile.bind` 单行，取自本 agent 自己的 wire
  `.../session_ced79706-.../agents/agent-1372/wire.jsonl`）：
  `{"type":"profile.bind","agentId":"agent-1372","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**。
- 授权：用户 2026-10-21「前序的任务直接实施」。
- 分支与基点：`feature/model-config-b3`，基点 = 本地 `main` HEAD **`64c1caf`**（批② 合流笔）。
  `git rev-parse main origin/main` = `64c1caf…` / `a5539c2…`（origin 未推进 —— 禁 push 所致，故以本地 main 为准）。
- 施工树：`<REPO>/wt-model-config-b3`（worktree）。**全部改动未 commit**（`git status` 见 §6）。
- 范围（设计稿 §11 批③）：`AiChatRequest` 扩展、面板选择器/参数 popover、快照固化 model/provider 的**消费侧**、能力位降级、`PROVIDER_GONE`；外加指挥官裁决的首项 **I-3 修复**与**帧集断言守卫**。
- 不做：批④（审计落表、多 kid、私网白名单）、E2E 脚本改动、设计稿原文修改、任何服务启动。

## 1. 测试矩阵（先行登记 → 逐条落为用例）

维度：**K**=契约、**B**=行为、**D**=降级、**S**=安全与兼容、**R**=回归。

### 1.1 后端（43 例，`backend/src/test/java/com/example/configmgr/ai/`）

| # | 维 | 判据 | 用例（文件） |
|---|---|---|---|
| B3-1 | K/S | 旧前端体（三键）在新后端反序列化 ⇒ `modelId`/`params` 为 null | `web/AiChatRequestCompatTest#legacyBodyWithoutModelFieldsStillDeserializes` |
| B3-2 | K | `modelId` + `params{temperature,topP,maxTokens}` 逐键落地 | `…#modelIdAndParamsLandOnExactKeys` |
| B3-3 | S | `params` 的 record 键集**恰为**三键（改名 = 旧请求 400） | `…#paramsKeySetIsFrozen` |
| B3-4 | S | 3 参构造器仍可用（24 处既有构造点零改动） | `…#threeArgConstructorStaysAvailable` |
| B3-5 | S | 未知顶层键仍 400（**部署顺序"后端先上"的书面依据**） | `…#unknownTopLevelKeyIsStillRejectedWhichIsWhyBackendMustShipFirst` |
| B3-6 | K | 请求模型不可用 ⇒ 400 `MODEL_NOT_FOUND`，**不进流** | `web/AiControllerChatModelTest#unknownModelIsRejectedBeforeTheStreamOpens` |
| B3-7 | K | 请求模型透传到 `ResilientChatService`（captor） | `…#requestedModelIdReachesTheService` |
| B3-8 | K | `params` 越界（2.5 / 1.5 / 0）⇒ 400 `INVALID_PARAM` | `…#outOfRangeParamsAreRejected` |
| B3-9 | R | 三键旧体照旧 200 开流 | `…#legacyBodyWithoutModelFieldsStillOpensTheStream` |
| B3-10 | S | 生产同款 `ObjectMapper` 下未知键 400（servlet 边界） | `…#unknownJsonKeyIs400WithTheProductionObjectMapper` |
| B3-11 | B | 显式模型优先于 `is_default` 与全局默认 | `config/AiChatTargetResolutionTest#explicitModelWinsOverDefaultFlagAndSetting` |
| B3-12 | R | 未指定模型 ⇒ 解析链与批③ 前逐值等价 | `…#blankModelIdKeepsTheLegacyChain` |
| B3-13 | D | 停用 provider 的模型不可选 | `…#modelsOfDisabledProvidersAreNotSelectable` |
| B3-14 | D | key 不可解的 provider 的模型不可选 | `…#modelsOfProvidersWithUnreadableKeysAreNotSelectable` |
| B3-15 | D | 未知模型 / 停用模型行均不可选 | `…#unknownModelYieldsEmpty`、`…#disabledModelRowIsNotSelectable` |
| B3-16 | R | env 种子模型（合成行 `id=null`）仍可选（CI/E2E 主路径） | `…#envSeedModelStaysSelectable` |
| B3-17 | B | 能力位随 target 返回 | `…#toolCallingCapabilityIsCarriedByTheTarget` |
| B3-18 | D | `allow.userParams` 缺省 = 允许，显式 false 才锁 | `…#allowUserParamsDefaultsToTrueAndRespectsExplicitFalse` |
| B3-19 | K | `/api/ai/models` 只列可用行 | `…#availableModelsListsOnlyUsableRows` |
| B3-20 | B | 逐请求参数覆盖全局默认，`streamUsage` 不丢 | `config/AiProviderRegistryParamsTest#requestParamsOverrideGlobalDefaultsAndKeepStreamUsage` |
| B3-21 | B | 交替两个模型 ⇒ 缓存条目仍 1，默认模型不被污染 | `…#alternatingModelsKeepOneCachedRuntime` |
| B3-22 | D | 越界请求参数忽略（不 500） | `…#outOfRangeRequestParamsAreDroppedNotFatal` |
| B3-23 | B | 模型相同且无覆盖 ⇒ 复用缓存实例 | `…#noOverrideAndSameModelReusesTheCachedInstance` |
| B3-24 | D | `allowUserParams=false` ⇒ `done.extra.paramsLocked=true` | `run/ChatModelContractTest#paramsLockedIsReportedInDoneExtra` |
| B3-25 | B | 允许时应用参数且**不**出 `paramsLocked` 键 | `…#allowedParamsAreAppliedAndNotReportedAsLocked` |
| B3-26 | D | 不支持工具调用的模型：不取工具、无 `tool_start`、正常 done | `…#modelWithoutToolCallingGetsNoToolsAndNoToolFrames` |
| B3-27 | K | `done.model` = 上游回显优先 | `…#upstreamEchoWinsOverParsedModel` |
| B3-28 | K | 上游不回显 ⇒ 回落解析值 | `…#parsedModelIsTheFallbackWhenUpstreamOmitsIt` |
| B3-29 | K | 续跑：provider 已删 ⇒ `PROVIDER_GONE`，不驱动 | `run/ResumeProviderGoneTest#deletedProviderYieldsProviderGone` |
| B3-30 | B | 续跑按快照模型（不改默认） | `…#resumeKeepsTheSnapshotModelIdentity` |
| B3-31 | R | 旧快照无 `providerId` ⇒ 走五参旧路径（WARN，不 500） | `…#legacySnapshotWithoutProviderFallsBackWithWarn` |
| B3-32 | D | 同名模型换了 provider ⇒ 仍判 `PROVIDER_GONE` | `…#providerSwitchOfTheSameModelYieldsProviderGone` |
| B3-33 | R/S | **帧集冻结守卫**：后端发射点 ∪ {message_start,message_end} 与前端白名单**双端恰为 16 种** | `conformance/FrameTypeSetFrozenTest#frameTypeSetIsExactlyTheFrozenSixteen` |
| B3-34 | R | 反例孪生：多一种帧必须判不等（防"空集对空集"） | `…#falsificationTwinProvesTheGuardCanGoRed` |
| B3-35 | K | `/health.allowedParams` + `defaultParams` 三键 | `web/AiControllerHealthParamsTest#healthCarriesAllowedParamsAndDefaults` |
| B3-36 | D | `allowedParams` 缺省 true；`defaultParams` 各项 null | `…#allowedParamsDefaultsToTrueWhenUnset` |
| B3-37 | K | `GET /api/ai/models` 形状/顺序/能力位 | `web/AiModelsEndpointTest#modelsEndpointReturnsOnlyUsableModels` |
| B3-38 | K | 响应键集冻结（前端 `AiModelView` 逐键对应） | `…#wireKeySetIsFrozen` |
| B3-39 | D | 无可用模型 ⇒ 空数组（不是 500） | `…#emptyWhenNothingIsUsable` |
| B3-40 | K | **I-3**：`baseUrl` 未变 ⇒ 跳过 URL 闸（私网种子行可改） | `config/AiConfigWriterGuardTest#unchangedPrivateBaseUrlSkipsTheGuard` |
| B3-41 | K | 省略 `baseUrl` 同样跳过 | `…#omittedBaseUrlAlsoSkipsTheGuard` |
| B3-42 | S | **I-3 反例**：地址改成另一个私网 ⇒ 仍 `BLOCKED_PRIVATE_ENDPOINT` | `…#changedPrivateBaseUrlIsStillBlocked` |

### 1.2 前端（新增 25 例，`frontend/tests/`；既有 2 处口径随修复同步收紧）

| # | 维 | 判据 | 用例 |
|---|---|---|---|
| F-1 | K | `select()` 只写 `ai-model-id`，不动 `ai-session-id` | `ai-model-store.spec.ts#select 写 ai-model-id，且不动 ai-session-id` |
| F-2 | R | 重建 store 后从 sessionStorage 恢复选择 | `…#刷新（重建 store）后从 sessionStorage 恢复选择` |
| F-3 | B | `ensureModels` 只取一次，`force` 才重取 | `…#ensureModels 只取一次；force 才重取` |
| F-4 | D | 取列表失败 ⇒ 空列表 + 错误文案 + 投影 `{}` | `…#取列表失败 ⇒ 空列表 + 错误文案` |
| F-5 | B | 参数按会话存 `ai-model-params:<id>`，跨会话不串味 | `…#参数写 ai-model-params:<sessionId>，另一个会话读不到` |
| F-6 | B | `resetParams` 清存储 + 投影回 `{}` | `…#恢复默认 ⇒ 清空存储与内存` |
| F-7 | K | 全空 ⇒ 投影 `{}` | `…#未选模型、未改参数 ⇒ 空对象` |
| F-8 | K | 选了模型 ⇒ 只有 `modelId` 键 | `…#选了模型 ⇒ 只有 modelId 键` |
| F-9 | K | `params` 只含改过的项（键名逐字） | `…#改了某一项参数 ⇒ params 只含那一项` |
| F-10 | K | 与全局默认相同 ⇒ 视为没改 | `…#参数与全局默认相同 ⇒ 视为"没改"` |
| F-11 | D | `allowedParams=false` ⇒ 一项都不发 | `…#allowedParams=false ⇒ 一项参数都不带` |
| F-12 | K | 选择器只列后端返回的可用模型（首项 = 默认） | `ai-panel-model-picker.spec.ts#只列后端返回的可用模型` |
| F-13 | D | 空列表 ⇒ 不渲染选择器、不阻塞输入 | `…#列表为空 ⇒ 不渲染选择器，也不阻塞输入` |
| F-14 | B | 选中 ⇒ 写 `ai-model-id`，会话 id 不变 | `…#选中模型 ⇒ 写 ai-model-id，且不清会话` |
| F-15 | K | 副标题显示当前模型（纯展示元素） | `…#P5 副标题显示当前模型` |
| F-16 | K | 下一轮 body 带 `modelId`，键集恰为四键 | `…#选中模型后下一轮 chat 体带 modelId` |
| F-17 | K | 未选 ⇒ body 无 `modelId` 键 | `…#未选模型 ⇒ 体里没有 modelId 键` |
| F-18 | D | 能力位 false ⇒ 黄条（逐字文案 + 无 emoji） | `…#选中不支持工具调用的模型 ⇒ 顶部黄条出现` |
| F-19 | D | 换回支持工具调用的模型 ⇒ 黄条撤除 | `…#换成支持工具调用的模型 ⇒ 黄条撤除` |
| F-20 | K | popover 默认收起，展开三项且初值 = 全局默认 | `ai-panel-params-popover.spec.ts#触发器默认收起` |
| F-21 | K | 改一项 ⇒ body.params 只有该项 | `…#改一项 ⇒ 下一轮 body.params 只有改过的那一项` |
| F-22 | B | 恢复默认 ⇒ body 不再带 params | `…#「恢复默认」⇒ 存储清空、body 不再带 params` |
| F-23 | D | 锁定 ⇒ 置灰 + `PARAMS_LOCKED` 文案 + body 无 params | `…#整组置灰 + PARAMS_LOCKED 文案，且 body 不带 params` |
| F-24 | K | **I-3**：只改显示名 ⇒ 提交体只有 `displayName` | `ai-settings-view.spec.ts#只改显示名 ⇒ 提交体只有 displayName` |
| F-25 | K | **I-3 反例**：改了 `baseUrl` ⇒ 提交体带该键 | `…#改了 baseUrl ⇒ 提交体带 baseUrl` |

**两处既有断言随修复同步收紧**（不是放宽，理由见 §4）：

1. `ai-settings-view.spec.ts#留空保存 ⇒ 请求体不含 apiKey 键`：原断言 `body.baseUrl === 原值`
   （**这正是"改不动"的成因**）改成 `body` 不含 `baseUrl`/`displayName`；并由 F-24/F-25 成对覆盖。
2. `ai-panel-parts-render.spec.ts#composer v2 形态`：原断言"底栏首子元素 = `SPAN` 且文案 `工具位（预留 · 留空）`"
   （该占位按上下文批① 的裁决**就是留给本批填充的**）改成"首格 = `model-slot`、第二格 = `params-toggle`、
   `flex-1` 撑开在发送钮之前"；并新增"选择器/参数位必须在撑开位之前"的顺序断言。

## 2. 红测留痕

### 2.1 前端：先写 3 个新 spec → 跑红 → 再实现

命令与结果（实现前，`stores/ai-model.ts` 尚不存在）：

```text
$ node node_modules/vitest/vitest.mjs run tests/ai-model-store.spec.ts \
    tests/ai-panel-model-picker.spec.ts tests/ai-panel-params-popover.spec.ts
 Test Files  3 failed (3)
      Tests  11 failed | 1 passed (12)
```

（失败面 = 模块缺失 / 选择器与 popover 选择器全空 / `params-toggle` 不存在；实现后同一命令 → 全绿。）

### 2.2 后端：I-3 的**变异证红**（最直观的一条）

把 `AiConfigWriter.updateProvider` 的"地址没变则跳过闸"短路删掉（只改这一行），跑：

```text
[ERROR] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0
[ERROR]   AiConfigWriterGuardTest.unchangedPrivateBaseUrlSkipsTheGuard:64 » AiConfig
baseUrl 解析到私网/回环/保留地址（127.0.0.1 → 127.0.0.1）：默认禁止…
```

即**用户报告的现象原样复现**（改显示名 ⇒ 400 `BLOCKED_PRIVATE_ENDPOINT`），恢复后转绿。
其余后端用例的证红方式写在各自类注（"kill：删掉哪一段实现会让它红"），并用
`FrameTypeSetFrozenTest#falsificationTwinProvesTheGuardCanGoRed` 钉住"守卫真的会红"。

### 2.3 帧契约零新增的机械旁证

`Run/ChatModelContractTest` 的每一例都跑 `FrameContract.assertConformant`（6 条不变量），
`paramsLocked` 只作为 `done.extra` 的 additive 键出现 —— 出现第 17 种帧型时
`FrameTypeSetFrozenTest` 立刻红（双端各扫一遍）。

## 3. 自决清单（指挥官授权的"择一/自决"项）

| # | 决策 | 理由 |
|---|---|---|
| D-1 | **新建 `GET /api/ai/models`**，不复用 `GET /api/ai/providers` 派生 | ①设计稿 §3.5 已定义该端点；②面板只要"能选什么"，`/providers` 还带 baseUrl/keyRef/路径，前端要二次过滤 —— 过滤口径一旦与后端漂移就是"列出来点不通"；③payload 更小（挂载一次就取）。可用性三条判据（provider enabled / key 可解析 / 模型 enabled）落在 `AiConfigService#availableModels()` 唯一口径 |
| D-2 | `done.model` = **上游回显优先、回落本轮解析值** | 回显是"这一轮真跑了谁"的最强证据；两路都空则**不写该键**（与批③ 之前逐字相同）。续跑同口径（`TurnContext.model` = 快照模型） |
| D-3 | `paramsLocked` 载体 = **`done.extra`**（additive 键），只在其为真时出现 | 帧型不变（16 冻结）；`/health.allowedParams` 是"策略态"，`done.extra` 是"本轮事实"（参数确实没生效），两者区别必须可见 |
| D-4 | 能力位降级**不报错**：`MODEL_TOOLCALL_UNSUPPORTED` 不发射，只下发空工具集 + 面板黄条 | 模型能力不是用户错误；错误码保留在文案表（设置页/未来场景可用），码值稳定 |
| D-5 | 续跑**不重放** params（params 不进快照） | 本批不扩快照结构（`RunSnapshot` 的字段面是批① 的对外形状）。续跑用"快照模型 + 全局默认参数"；`paramsLocked` 续跑恒 false（本来就没有请求参数） |
| D-6 | provider 选择靠 `modelId` **反查**（不新增 `providerId` 请求字段） | 设计稿只要求可选 `modelId`；一个模型名在同一时刻最多落在一个可用 provider 上（allowlist 唯一键是 provider_id+model_id，跨 provider 同名时按库序取第一个并记日志） |
| D-7 | 请求参数越界 = 400 **`INVALID_PARAM`**（复用批② 已登记码） | 本批**零新增错误码**；范围与设置页写路径逐值相同（同一把尺） |
| D-8 | `allowUserParams` **缺省 = 允许** | 与设置页 `allowUserParams !== false` 同口径；未设置时谈"锁定"没有依据 |
| D-9 | 选择器用**原生 `<select>`** 而非设计稿的 `el-select size="small"` | composer 自 R-165 S2/S3a 起"EP 全退场"（原生 button/textarea/span + Tailwind）；引入 el-select 会让现有 4 个面板 spec 的 stub 全要改，且 jsdom 下 popper 不稳。`data-testid` 与语义不变 |
| D-10 | 请求级模型/参数**不写缓存**：新增 `AiProviderRegistry#forRequest`，缓存条目仍按 `RuntimeKey` 一 个 | 批① `runtimeFor` 的"同 key 换模型轻量重绑"是给**默认模型变更**的；套到逐请求模型会互相踩踏并污染默认路径（下一轮"没选模型"会带上上一轮的模型名）。`forRequest` 复用缓存里的 `OpenAiApi`，只新建 model/client，**不读写不淘汰**缓存 |
| D-11 | 前端 `allowedParams` 未知（旧后端/health 失败）按**允许**处理 | 不把"未知"渲染成"锁定"（那会把面板锁死）；锁定只由显式 `false` 触发 |

## 4. 设计稿纠偏（只登记，不改原文）

| # | 位置 | 原文/口径 | 事实与处理 |
|---|---|---|---|
| C-1 | §3.6 store 段 | 「`probeHealth` 拿到 `providers/models` 后派生 `availableModels`，**无需额外请求**」 | **不成立**：`/health.providers[]` 只有 `{id,code,name,baseUrl,enabled,keyConfigured,keyRef,source,synthetic,status}`（`AiConfigService#describeProviders`），**没有 models**。本批改为 `GET /api/ai/models` 一次挂载请求（自决 D-1） |
| C-2 | §3.5 稳定错误码"封闭表" | 表内 16 码 | 批② 实际新增 5 码未收表（`PROVIDER_EXISTS`/`MODEL_EXISTS`/`CODE_IMMUTABLE`/`INVALID_PARAM`/`INVALID_MODEL`，见 `AiConfigWriter` 类注末段）；**批③ 新增 0 码**，`MODEL_NOT_FOUND`/`PARAMS_LOCKED`/`PROVIDER_GONE` 用表内既有码 |
| C-3 | §3.5 `/health` 行 | 新增 `allowedParams` | 只给了布尔，**没给参数初值来源**。本批 additive 补 `defaultParams{temperature,topP,maxTokens}`（否则 popover 初值只能再打一次 `/settings/defaults`，与"面板不需要额外请求"的意图冲突） |
| C-4 | §3.6 落点 | `el-select size="small"` | 与 R-165「EP 全退场」冲突；改原生 `<select>`（自决 D-9），`data-testid` 不变 |
| C-5 | §11 批③ 项 | 「快照固化 model/provider」 | 字段与写入在**批① 已完成**（`RunSnapshot.providerId/modelId`、`RunStore.create(...)`）；批③ 只做**消费侧**（`PROVIDER_GONE` + 按快照模型续跑），无误改 |
| C-6 | §3.5 端点表 | `GET/POST /api/ai/providers/{id}/models` | 实现同为一条路径的 GET+POST（批② 已有），批③ 未动；此处仅登记"表里的写法是合并语义" |

## 5. 门禁数字（全绿）

| 门禁 | 基线（开工前实测） | 交活（本批） | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | **1043** / 0F / 0E | **1086** / 0F / 0E · **164 suite**（净增 43 / +10 suite） | ✅ 只升不降 |
| 前端 `vitest run` | **559** / 30 文件 | **584** / 33 文件（净增 25） | ✅ 只升不降 |
| 前端 `vue-tsc --noEmit` | 0 error | **0 error** | ✅ |
| 前端 `vite build` | ✓ | **✓（built in 34.11s）** | ✅ |
| 帧集守卫 | 无 | 16 种（双端扫描） | ✅ |
| 密钥/组织名 | — | 0 字面（测试只用 `sk-test-…` 形态的假值） | ✅ |
| 服务 | — | 未启动任何服务 | ✅ |

> 基线取"开工前在**本 worktree**（基点 `64c1caf`）实跑的值"，不是文档里的旧数（963/536）——
> 批①② 与上下文专项已把两端推高到 1043/559，按纪律以实跑为准。

## 6. 文件清单（全部未 commit）

**后端 · 改（8）**
- `backend/src/main/java/com/example/configmgr/ai/web/AiChatRequest.java`：+可选 `modelId`/`params`，保留 3 参构造器
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java`：`ChatTarget`/`resolveChatTarget`/`selectableModel`/`supportsToolCalling`/`allowUserParams`/`availableModels` + `AvailableModel`
- `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java`：`Overrides`/`target`/`allowUserParams`/`supportsToolCalling`/`forRequest`；`buildOptions(model, overrides)` + 容错
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`：请求级运行态、能力位降级、`paramsLocked`、`doneModel`、`driveResumed(…providerId,modelId,…)` + `runtimeForSnapshot`
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`：`Outcome.PROVIDER_GONE` + 判据 + 按快照驱动（旧快照仍走五参）
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`：/chat 模型/参数闸 + `/health.allowedParams/defaultParams` + resume 409 分支
- `backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java`：`GET /api/ai/models`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java`：**I-3**（baseUrl 未变跳过 URL 闸）

**后端 · 新（11）**
- `…/ai/web/dto/AvailableModelView.java`
- 测试：`config/AiChatTargetResolutionTest`、`config/AiProviderRegistryParamsTest`、`config/AiConfigWriterGuardTest`、
  `web/AiChatRequestCompatTest`、`web/AiControllerChatModelTest`、`web/AiControllerHealthParamsTest`、`web/AiModelsEndpointTest`、
  `run/ChatModelContractTest`、`run/ResumeProviderGoneTest`、`conformance/FrameTypeSetFrozenTest`

**前端 · 改（8：6 源文件 + 2 既有 spec 的口径收紧）**
- `frontend/src/types/ai.ts`：**尾部追加**（同名 interface 合并：`AiChatRequest`/`AiHealth` + `AiChatParams`）
- `frontend/src/types/sse.ts`：`done.paramsLocked?`（additive 键的类型面）
- `frontend/src/api/aiConfig.ts`：`AiModelView` + `listAvailableModels()`
- `frontend/src/stores/ai.ts`：`send()` 投影（2 行）+ 新错误码 → 文案映射（G9 收口）
- `frontend/src/components/AiPanel/AiPanel.vue`：副标题（P5）、黄条（P4）、`model-slot`/选择器（P1）、参数 popover（P2/P3）、`modelPickerDisabled`（P6）
- `frontend/src/views/AiSettingsView.vue`：**I-3**（只提交变化字段）+ `provider-display-name` testid
- 测试：`frontend/tests/ai-panel-parts-render.spec.ts`、`frontend/tests/ai-settings-view.spec.ts`（两处口径收紧，见 §1.2 末）

**前端 · 新（4）**
- `docs/evidence/施工报告-模型配置批③-DS-Flash.md`（本报告）
- `frontend/src/stores/ai-model.ts`
- `frontend/tests/ai-model-store.spec.ts`、`ai-panel-model-picker.spec.ts`、`ai-panel-params-popover.spec.ts`（+ 本报告）

**未触（纪律）**：`docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`、`docs/research/设计-模型配置功能专项-2026-10-21.md`、`scripts/ci/stub-upstream.mjs`。

## 7. 待裁项（留给复审/指挥官）

1. **`start` 帧不加 `providerId`/`modelId`**（裁决冻结）：所以"在飞轮"里前端只能显示**上一轮**的 `done.model`，
   切模型后到 `done` 之前选择器与副标题显示的是"将要用的模型"而非"正在用的"。是否值得为它破一次帧冻结，请裁决（本批按裁决不加）。
2. **跨 provider 同名模型**：`resolveChatTarget` 取库序第一个可用 provider 并 `log.warn`。若将来需要"同模型双端点分流"，要新增 `providerId` 请求字段（本批按 D-6 不做）。
3. **续跑参数不回放**（D-5）：用户在第一轮调过 temperature，续跑回到全局默认。若要快照化 params，需要扩 `RunSnapshot`（属加法，但与"只做消费侧"的批界相悖，留待批④或专门批次）。
4. **`MODEL_TOOLCALL_UNSUPPORTED` 仍是"死码"**：只在文案表里，无发射点（D-4）。文案表已留位，是否需要设置页在"给不支持工具调用的模型开默认"时用它拒绝，请裁决。
