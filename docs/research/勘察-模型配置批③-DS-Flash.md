# 勘察 · 模型配置批③（对话面板 + 契约）

> **归档说明**：2026-10-26 归档入库（原件创作日期见正文「日期」字段，本批不新增、不改写结论）。源件 = `<LOCAL_WORK>/_shared/勘察-模型配置批③-DS-Flash.md`，正文逐字保留，仅路径 / 会话标识 / 用户名脱敏；本件为**勘察时点结论**，非现行基线，不表示当前已验证或已修复。

- 日期：2026-10-21
- 勘察官：agent-1368（纯只读；未改任何文件、未起服务、未 commit）
- 基线：main `a5539c2`（工作树净，仅预存未跟踪 `data/`）；批② worktree `wt-model-config-b2` @ `3ed8fba`（只读）
- 依据：`docs/research/设计-模型配置功能专项-2026-10-21.md` §3.5 / §3.6 / §4 / §11

## 0. 自证（profile.bind）

`<KIMI_SESSIONS_ROOT>/<WORKSPACE>/<KIMI_SESSION_A>/agents/agent-1368/wire.jsonl`

| 字段 | 值 |
|---|---|
| agentId | `agent-1368` |
| modelAlias | `DeepSeek/deepseek-flash` |
| profileName | `coder` |
| thinkingEffort | `high` |

---

## 1. 批③ 范围逐条核对（设计稿 §11：「AiChatRequest 扩展、面板选择器/参数 popover、快照固化 model/provider、能力位降级、PROVIDER_GONE」）

### 1.1 面板侧（§3.6）

| # | 设计稿要求 | 核对结论 | 落点 |
|---|---|---|---|
| P1 | 模型选择器 `el-select size="small"`，`data-testid="model-picker"`，**只列 enabled + 能力可用项**，选项后缀能力标 | 完全空白；但占位已预留：`AiPanel.vue:741-744`「工具位（预留 · 留空）」虚线格，注释明写「左端虚线格留给模型配置专项批③ 的选择器/参数 popover」 | `frontend/src/components/AiPanel/AiPanel.vue:744` |
| P2 | 参数 popover 触发器 `data-testid="params-toggle"`，三项 temperature/top_p/max_tokens + 「恢复默认」 | 空白；无任何参数 UI（全文件 `temperature|top_p|max_tokens` 零命中，仅 `v-model`） | 同上，选择器右侧 |
| P3 | `allowUserParams=false` ⇒ 整组置灰 + `PARAMS_LOCKED` 文案 | 空白；`AiSetting.ALLOW_USER_PARAMS` 常量已存在（`config/db/AiSetting.java:34`），**无任何消费点** | 面板 + `constants/ai-settings-copy.ts`（b2 已建） |
| P4 | `supports_tool_calling=false` ⇒ 顶部黄条「该模型不支持工具调用，本轮退化为纯问答」 | 空白；`ModelView.supportsToolCalling` 已在 DTO 里，但无消费点 | 面板 |
| P5 | 副标题（`:20`）显示当前模型，`data-testid="current-model"`，纯展示不承担切换 | 现为固定文案「流式对话 · 工具调用」（`AiPanel.vue:20`） | `AiPanel.vue:20` |
| P6 | 切换语义：**下一轮生效**，不清会话、不改 `sessionId` | 无实现；`sessionId` 初始化在 `stores/ai.ts:406 initSessionId()` / `:716` | `stores/ai.ts` |
| P7 | store：`selectedModelId`（sessionStorage `ai-model-id`）、`modelParams`（`ai-model-params:<sessionId>`，**不进消息镜像**） | state 无这两项；镜像版本 `MIRROR_VERSION` 在 `stores/ai.ts:44-46` 区；既有键范式 `SESSION_ID_KEY='ai-session-id'`（`stores/ai.ts:44`） | `stores/ai.ts:739-800` |
| P8 | `probeHealth()` 派生 `availableModels`，「无需额外请求」 | **设计稿此句不成立**：`/health.providers[]` 只有 `{id,code,name,enabled,keyConfigured,keyRef,source,status}`（`AiConfigService.describeProviders():591-609`），**没有 models**。面板要么另取 `/api/ai/providers`（含 `models`），要么新建 `GET /api/ai/models`（§3.5 已定义） | 见 G2 |
| P9 | 设置页入口**不进面板** | 已满足（b2 的入口在 `App.vue` 顶栏） | — |

### 1.2 契约侧（§4）

| # | 设计稿要求 | 核对结论 |
|---|---|---|
| C1 | `AiChatRequest` + 可选 `modelId` / `params{temperature,topP,maxTokens}` | 现 record 仅 `(sessionId,message,Context)`（`web/AiChatRequest.java:13`）。**改 record 会碎 24 处 `new AiChatRequest(...)` 构造点**（main 1 处 `AiController.java:251` 的「归一化后换新 record」+ 23 处测试） |
| C2 | `api/sse.ts:361` 原样透传，前端零改动 | 成立：`body: JSON.stringify(request)`（`api/sse.ts:344-361`） |
| C3 | SSE 帧**零变更** | 结构上成立（`done.model` 已有，见 §3）；字段级仍有 8 条待裁 |
| C4 | `/health` additive：`allowedParams` | **未实现**：`AiController.health():933-1010` 有 `providerId/defaultModelId/contextLimitTokens/compactionOn/providers`，**无 `allowedParams`** |
| C5 | `GET /api/ai/models`（面板用，跨 provider 可用模型） | **未实现**：main 只有 `GET /api/ai/providers`（`AiConfigController.java:39`）；b2 只有 `/{id}/models`；`ModelView` javadoc 自认「批③ 的 `GET /api/ai/models` 复用同一字段口径」 |
| C6 | resume 409 `PROVIDER_GONE` | 未实现：`ResumeService.Outcome`（`:127-129`）无此值；`AiController` 的 resume switch（`:638-647`）无此支 |
| C7 | `RunSnapshot` + providerId/modelId（additive） | **批① 已完成**（`RunSnapshot.java:111-118`、`RunStore.create(...,providerId,modelId):382`、`RunSnapshotModelIdentityTest.java` 已存在） |
| C8 | 前端 `types/ai.ts` 扩 `AiChatRequest`/`AiHealth` | main 仅 `AiChatRequest:33` / `AiHealth:99` 旧形；`AiHealth.providers` 的扩展在 **b2** 才出现（b2 `types/ai.ts:118+`） |

---

## 2. 现状盘点

### 2.1 前端对话面板

- `frontend/src/components/AiPanel/AiPanel.vue`（**3059 行**）
  - 头部 `:15-35`（:20 副标题固定文案）
  - 上下文条 `:37-54`（health chip / 会话 / 工作区 / 数据版本 / 重新检测）
  - composer `:713-767`：底栏 `:741-744` 工具位占位 → `:753 <ContextPill />` → 发送/停止 `:757-766`
  - 模型/参数 UI：**零**（除 `v-model` 与注释）
- `components/AiPanel/`：`AiPanel.vue` / `ContextPill.vue` / `ContextPopover.vue` / `FormRenderer.vue`（后两者为上下文专项批① 产物，b2 分支缺）
- `stores/ai.ts`：state `:739-800`（无 model/params）；`probeHealth():940` 只写 `health/healthError`；`send():1556` → `:1650 aiApi.chat({sessionId, message, context})`
- `types/ai.ts:33` `AiChatRequest` 三字段；`:99` `AiHealth`（main 侧无 providers）
- `api/sse.ts:344` `streamChat(request)` → `:361 JSON.stringify(request)`（加字段零改动）
- 测试：`frontend/tests/ai-panel-composer-limit.spec.ts`、`ai-panel-parts-render.spec.ts`（Element Plus 全 stub 的最小装配范式，可仿）

### 2.2 后端能力清单（批①②，精确到方法）

**`config/AiConfigService`**（批①）
`current():132` / `reload():152` / `listProviders():270` / `listModels():274` / `modelsOf(Long):278` / `model(Long,String):285` / `effectiveProviders():292,304` / `envSeedModel():319` / `resolveDefault():339` / **`resolveProvider(AiProvider):357`（javadoc 自认「批③ 的按请求选 provider 会复用」）** / `resolveModelId(ConfigSnapshot,AiProvider):371` / `KeyStatus:393` / `resolveKey(AiProvider):422` / `availability():482` / `hasUsableProvider():509` / `envApiKey():516` / `setting(String):543` / `ConfigSnapshot:549` / `ResolvedProvider:574`（含 `runtimeKey()`）/ `runtimeKeyOf():584` / `describeProviders():591`

**`config/AiProviderRegistry`**（批①）
`current():83` / `tryCurrent():90` / **`runtimeFor(ResolvedProvider):99`** / `runtimeCount():122` / `runtimeKeys():126` / `evictAll():131` / **`buildOptions(String modelId):169`**

**其余批①**：`AiKeyCipher`、`AiConfigSeeder`、`AiAvailability`（动态三档码 `AI_UNAVAILABLE`/`AI_KEY_MISSING`/`KEY_UNREADABLE`）、`ProviderRuntime:20`、`RunSnapshot.providerId/modelId`、`RunStore.create(...,providerId,modelId):382`

**`AiController`**：`chat():209`（闸序：必填 → Redis → 限流 → 归一化 → 空拒 → 长度 → SessionGate）、`health():933`、`resume():628`（switch `:638-647`）

**`ResilientChatService`**：`chat()` `:227 registry.current()` → `:246 store.create(...providerId,modelId)` → `:264 TurnContext(...,runtime.modelId())`；`driveResumed():301 registry.current()`（**注释自认「批③ 会改成按快照取，provider 没了 409 PROVIDER_GONE」**）；`buildResumedPrompt():335` 用 runtime 默认 options

**`SseChatEmitter`**：`done(usage, model, extra, context):854/868` → `:875 frame.put("model", model)`；`start():` 仅 `runId`/`sessionId`

**批②（b2，未合流）净增**：`AiConfigWriter`、`AiConnectionProbe`、`AiEndpointGuard`、`AiModelCatalog`、**`AiModelResolver`（静态纯函数，优先级 explicit → is_default → 首个 enabled → ai_setting → env；`resolve(...)` 已为批③ 备好）**、`AiConfigException`、`AiConfigAudit(+Repository)`、`AiConfigController` 全 CRUD + `/settings/defaults`（`:85-188`）、`DefaultsView/ProbeView/FetchModelsView`、前端 `api/aiConfig.ts`（`aiConfigApi` 12 方法）、`AiSettingsView.vue:707 行`、`constants/ai-settings-copy.ts`、路由/菜单、stub `/models`

### 2.3 缺口清单（批③ 必须闭环）

| # | 缺口 | 精确落点 |
|---|---|---|
| G1 | `AiChatRequest` 无 `modelId`/`params`；改 record 碎 24 构造点 | `web/AiChatRequest.java:13`；建议加 3 参紧凑构造器保编译（识别，不决定） |
| G2 | 无 `GET /api/ai/models`（跨 provider 可用模型）；health.providers 不含 models | 新端点 or 复用 `/api/ai/providers` |
| G3 | `buildOptions` 只吃 modelId，无 temperature/topP/maxTokens 覆盖；且 `runtimeFor():99-121` 用 `cached.modelId().equals(...)` 判定 → **按请求切模型会重建 ChatModel 并淘汰其它 runtimeKey**（`:119 removeIf`） | `AiProviderRegistry:99/169`；建议走「请求级 options 覆盖，不动 runtime」 |
| G4 | 续跑仍 `registry.current()`，无 `PROVIDER_GONE` | `ResilientChatService:301`、`ResumeService.Outcome:127`、`AiController:638` |
| G5 | 能力位无降级：`ToolRegistry.forContext():83` 无 `supportsToolCalling` 过滤 | `tool/ToolRegistry.java:83` + `ResilientChatService:232/335` 的 `setToolCallbacks` |
| G6 | 前端 state/类型无 model/params；main 的 `AiHealth` 无 providers | `stores/ai.ts:739`、`types/ai.ts:33/99` |
| G7 | 面板无选择器/popover/当前模型展示 | `AiPanel.vue:744/:20` |
| G8 | 无 `allow.userParams` 消费、无 `PARAMS_LOCKED` 文案 | `AiSetting.java:34`、`constants/ai-settings-copy.ts` |
| G9 | 无 `MODEL_NOT_FOUND` / `MODEL_TOOLCALL_UNSUPPORTED` / `PROVIDER_GONE` / `PARAMS_LOCKED` 的前端文案与卡片映射 | `utils/error-card.ts`、`constants/ai-settings-copy.ts` |
| G10 | `/health.allowedParams` 未实现（§3.5 明确要求） | `AiController.health():933` |

---

## 3. 帧契约风险专项（现状 16 帧冻结；**只识别，不决定**）

**结论先行**：批③ **不需要新帧型**。`done.model`（`SseChatEmitter:875`）与 `usage` 已能承载"本轮实际模型"；`error.code` 是自由字符串，新增码不改帧型。以下 8 条为**字段/语义级**风险，逐条「待裁」。

| # | 风险 | 事实证据 | 状态 |
|---|---|---|---|
| F1 | `start` 帧是否补 `providerId`/`modelId` | 设计 §4 明写「`start` 帧不加字段（避免触动 `conformance/FrameContract.java` 与三套帧序一致性测试的显式契约）」；但代价 = 面板在 `done` 之前无法显示"本轮实际用了哪个模型"（切换是下一轮生效，故此项只影响"在飞轮展示"） | **待裁** |
| F2 | `paramsLocked` 的载体未指定 | §3.4 只说「在响应里标 `paramsLocked=true`」；候选：`/health.allowedParams`（§3.5 有定义）/ `done.extra`（`done(...)` 第 3 参可塞，`:854`）/ `error.code=PARAMS_LOCKED` | **待裁** |
| F3 | `done.model` 的**语义**在切模型后需钉死 | 取值优先级 = `meter.model()`（上游 chunk 回显）→ 回落 `turn.model()`（解析值）（`ResilientChatService:976-977`、`:1191`）。桩会回显 `body.model`（`stub-upstream.mjs:443`），真实厂商未必回显 | **待裁**（"以上游回显为准" vs "以解析结果为准"） |
| F4 | 续跑帧的 `done.model` 口径 | 现 `driveResumed:305 runtime.modelId()`（取当前默认）；改为快照口径后，同一 runId 的 `done.model` 会变（帧结构不变，**断言与取证口径变**） | **待裁** |
| F5 | 新增 `error.code` 是否纳入"帧契约冻结面" | 新码 4 个（`MODEL_NOT_FOUND`/`MODEL_TOOLCALL_UNSUPPORTED`/`PROVIDER_GONE`/`PARAMS_LOCKED`）；`types/ai.ts:99` 注释里的 code 枚举、`utils/error-card.ts`、E2E 断言三处都要跟 | **待裁** |
| F6 | **16 帧冻结无自动守卫** | 后端无"帧类型集合"断言；前端 `types/sse.ts:119 SseFrameTypes` 是白名单，`parseSseFrame():495` 对未知 type 返回 `null` ⇒ **静默丢弃**。两端都不会因"偷偷加帧"变红 | **待裁**（建议补一条 conformance 断言；本批只识别） |
| F7 | `context` 帧与切模型的关系 | 帧体 `{level,before,after,turns,lostTokens,reason,est,...}`（`types/sse.ts:435-451`）无 model 维度；分母来自 `ContextLimitResolver.resolve(providerId,modelId)`（`:67`）。同一 runId 内若模型变，比例语义需明确（帧结构不变） | **待裁** |
| F8 | `start` 若加字段的帧宽 | `FrameSizeLimiter` 上限 64KB（`AiProperties:553-556`），两键远不触限，仅记录 | 知悉，不构成阻塞 |

**另**：`AiChatRequest` 加字段是**单向兼容**——后端 `ObjectMapper` 是裸 `new ObjectMapper()`（`config/JacksonConfig.java:19`），`FAIL_ON_UNKNOWN_PROPERTIES` 默认 **true** ⇒ **新前端 + 旧后端 = 400**。部署/回滚顺序必须"后端先上"，且 `params` 内部键名必须与 record 逐字一致。（待裁：是否在测试里用"键集断言"钉死。）

---

## 4. 测试矩阵草案

维度口径：**K**=契约（字段/帧/端点）、**B**=行为（优先级/生效时机）、**D**=降级（能力位/锁定/无 provider）、**S**=安全（key/未知键）、**R**=回归（既有绿）。

### 4.1 前端组件测试（vitest，`frontend/tests/`）

| 用例 | 维度 | 期望 | 落点文件（建议） |
|---|---|---|---|
| 选择器只列 enabled 且 key 可解析的模型 | K/B | 选项集 = 后端可用集；disabled 模型不出现 | `ai-panel-model-picker.spec.ts` |
| 能力标后缀 | D | `supportsToolCalling=false` 行带标记；选中后黄条出现 | 同上 |
| 切换模型 → sessionStorage 写入 `ai-model-id` | B | 键值 = 选中 modelId；不改 `ai-session-id` | 同上 |
| 切换模型 → 下一次 `aiApi.chat` 体含 `modelId` | K | mock 捕获 body：`{sessionId,message,context,modelId}` | `ai-panel-model-payload.spec.ts` |
| 在飞轮不改模型 | B | `loading/suspended` 时选择器置灰或改动不生效；在飞 body 不变 | 同上 |
| 参数 popover 三项 + 恢复默认 | K/B | 初值 = 全局默认；改后 body.params 三键；恢复默认后缺省（不发 params） | `ai-panel-params-popover.spec.ts` |
| `allowUserParams=false` | D | 整组置灰 + `PARAMS_LOCKED` 文案；body 不带 params | 同上 |
| 可用模型为空（无 provider） | D | 选择器隐藏/空态，不阻塞输入；沿用既有降级 chip | `ai-panel-model-picker.spec.ts` |
| 副标题当前模型截断 | K | `data-testid="current-model"` 文本 = 选中模型；超长省略号 | 同上 |
| `params` 键名与后端 record 逐字对齐 | K/S | 快照式断言键集（防 400） | `ai-panel-model-payload.spec.ts` |
| composer 既有 8192 上限/播报 | R | 不改仍绿 | `ai-panel-composer-limit.spec.ts`（既有） |
| parts 渲染 | R | 不改仍绿 | `ai-panel-parts-render.spec.ts`（既有） |

### 4.2 后端契约测试（`backend/src/test/java/com/example/configmgr/ai/`）

| 用例 | 维度 | 期望 | 落点文件（建议） |
|---|---|---|---|
| `modelId` 透传并优先于 is_default/全局/env | K/B | 解析值 = 请求值；`done.model` 对齐 | `web/AiControllerChatModelTest.java` |
| 未指定 `modelId` → 既有优先级不变（行为等价） | R | 与批③ 前逐值一致 | `config/AiModelResolverTest.java`（b2 已有，扩一例） |
| 未知模型 | K | 400 `MODEL_NOT_FOUND`，不进流 | `web/AiControllerChatModelTest.java` |
| `supportsToolCalling=false` | D | prompt 无 toolCallbacks；流里 `tool_start` 0 条；不 500 | `run/ModelCapabilityGatingTest.java` |
| `params` 覆盖全局默认 | K/B | options 三值 = 请求值；`streamUsage` 仍 true（上下文计量源） | `config/AiProviderRegistryParamsTest.java` |
| 切模型不重建 runtime | B | 连续两次不同 `modelId` → `runtimeCount()==1` | 同上（用 `ProviderRegistryHotReloadTest` 范式） |
| `allowUserParams=false` | D | 忽略 params；`paramsLocked` 标记（载体待裁 F2） | `web/AiControllerChatModelTest.java` |
| 续跑：快照 provider 已删 | K | 409 `PROVIDER_GONE` | `run/ResumeProviderGoneTest.java` |
| 续跑：切了默认模型后旧轮 | B | 仍用快照模型；`done.model` = 快照模型 | 同上 |
| 旧快照无 providerId（null） | R | 走默认 + WARN，不 500 | 同上（`RunSnapshotModelIdentityTest` 邻位） |
| `GET /api/ai/models` 形状 | K | 只回可用项；键集含 `modelId/displayName/capabilities/maxContext/maxOutput/isDefault/providerId` | `web/AiModelsEndpointTest.java` |
| `/health.allowedParams` | K | `allow.userParams` 的真值；缺省 false | `web/AiControllerHealthParamsTest.java` |
| 帧集不增 | R | `conformance/` 四套帧序测试零改动全绿 | 既有 `conformance/*` |
| 请求含未知键 → 400（口径钉死） | S | 400（现 `FAIL_ON_UNKNOWN` 行为） | `web/AiControllerChatGateTest.java` 扩 |

### 4.3 E2E（可选 1 条，`scripts/verify-e2e.ps1`）

- 切模型 → 下一轮 `done.model` = 新模型；桩已回显 `body.model`（`scripts/ci/stub-upstream.mjs:443`）且落 `req-*.json`，**零桩改动可断言**。
- 能力位用例：设置 `supportsToolCalling=false` 后同一提问不产生 `tool_start`。

---

## 5. 与在途批次的冲突预判

### 5.1 `wt-table-ai`（**未提交**，基于 `a5539c2`）

其工作树改动（`git status`）：`M SpreadGrid.vue`、`M stores/ai.ts`、`M types/ai.ts`、`M types/tools.ts`、`M utils/frontend-tools.ts`、`M ExportWizardView.vue`、`M ImportWizardView.vue`、`?? utils/sheet-tools.ts`、`?? tests/sheet-condition-parity.spec.ts`。

| 共同文件 | 表批改动位置 | 批③ 改动位置 | 冲突判定 |
|---|---|---|---|
| `frontend/src/stores/ai.ts` | imports `:17-46`；state `:777-786`（`activePreview`/`stalePreviewToolCallId`，紧接 `activeForm` 之后）；`restore` `:1425`；`handleFrame` `:3043` | state（`health` 附近 `:739`）；`send()` `:1650`；可能 `probeHealth():940` | **同文件、相邻 hunk** ⇒ 高风险（行号漂移 + `removeIf` 式插入） |
| `frontend/src/types/ai.ts` | `:339` `ToolPart` 之后（Sheet* 类型） | `:33`（`AiChatRequest`）、`:99`（`AiHealth`） | 同文件不同 hunk ⇒ 机械可合，但必须串行 |
| `utils/tools.ts`/`frontend-tools.ts`/`SpreadGrid.vue`/两个 wizard | 表批独占 | 批③ 不碰 | 无冲突 |

**避让建议**（建议，非决定）：
1. 批③ 的模型状态不写进 `stores/ai.ts` 的 `state()`，而是 **独立模块**（`stores/ai-model.ts` 或 `composables/useModelSelection.ts`）+ `AiPanel.vue` 内引用 ⇒ 与表批的共同文件从 2 个降到 1 个（`types/ai.ts` 仍共享，但 hunk 相距 300 行）。
2. 若坚持进 `stores/ai.ts`：**不要**改 `handleFrame` 的 `frontend_tool_request` 分支（表批 `:3043` 独占），只动 `state`/`probeHealth`/`send`；合并顺序建议**表批先合**（其面更大且不依赖批③）。
3. `MIRROR_VERSION` 不动（`selectedModelId`/`modelParams` 明确不进镜像，§3.6）。

### 5.2 `wt-model-config-b2`（`3ed8fba`）— **更硬的冲突**

- `merge-base = 3c79eaa`，**落后 main 3 个提交**；main 上的上下文统计专项批①（`0507a37`/`9274370` 等）**不在 b2 里**。
- 证据：`git diff a5539c2..3ed8fba` 显示 b2 **删除**了 `components/AiPanel/ContextPill.vue`、`ContextPopover.vue`、`utils/context-usage.ts`、6 个 context 测试、`types/ai.ts` 的 `ContextUsage/ContextBreakdown/ContextCompaction` 段（b2 `types/ai.ts:598` 起整段删）。
- 后果：若批③ 直接基于 b2 → ① 面板 `ContextPill`（`AiPanel.vue:753`）消失；② `done.context` 类型回退；③ 上下文专项的 5 个前端/6 个后端用例失踪。
- **前置条件（建议）**：b2 先 `rebase`/merge main 并复跑双端门禁，批③ 再起分支。否则批③ 的"回归零改动"承诺无法成立。

### 5.3 与上下文专项（已在 main）

- `ContextLimitResolver.resolve(providerId, modelId):67` 与 `ContextMeter` 已按模型取分母；批③ 切模型后**同轮** `limitTokens` 的变化语义需与上下文批对齐（不改其代码，只钉调用口径）。
- 面板底栏顺序已被上下文批钉死：`[模型选择器位][参数位] …… [用量 pill][发送/停止]`（`AiPanel.vue:741-744` 注释），批③ 填左端两格即可，**不得**重排 `ContextPill`。

### 5.4 其他在途

`wt-ctx-b23`、`wt-e2e-ctx` 均在 `a5539c2`，与批③ 无文件交集（前者后端上下文、后者 E2E 脚本）；但三者共用 `scripts/ci/stub-upstream.mjs`，若同批改桩需串行。

---

## 6. 勘察证据（可复现命令）

```bash
git -C <REPO_ROOT> log --oneline -3        # a5539c2
git -C <LOCAL_WORK>/ai-example-code/wt-model-config-b2 log --oneline -3        # 3ed8fba
git -C <LOCAL_WORK>/ai-example-code/wt-model-config-b2 merge-base HEAD a5539c2 # 3c79eaa
git -C <LOCAL_WORK>/ai-example-code/wt-model-config-b2 diff --stat a5539c2..3ed8fba
git -C <LOCAL_WORK>/ai-example-code/wt-table-ai status --porcelain             # 未提交改动清单
grep -rn "new AiChatRequest(" backend/src                                 # 24 处
grep -rn 'frame("[a-z_]*")' backend/src/main/.../ai/run/*.java            # 14 个发射点（+ 前端 reasoning 前向兼容 = 16 白名单）
```

**主仓工作树状态**：`git status --porcelain` = 仅 `?? data/`（勘察开始前即存在，非本次产生；本次未写主仓任何文件）。
