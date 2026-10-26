# 勘察 · i18n + 工具可配置专项（DS-Flash，只读勘察，未改主仓）

> **归档说明**：2026-10-26 归档入库（原件创作日期见正文「日期」字段，本批不新增、不改写结论）。源件 = `<LOCAL_WORK>/_shared/勘察-i18n工具可配置-DS-Flash.md`，正文逐字保留，仅路径 / 会话标识 / 用户名脱敏；本件为**勘察时点结论**，非现行基线，不表示当前已验证或已修复。

- 日期：2026-10-21
- 勘察对象：`docs/research/设计-i18n与工具可配置专项-2026-10-21.md`（索引第 33 件 §2 现状/§3 设计/§7 冲突/§10 分批）+ `docs/research/调研-i18n与工具可配置业界惯例-2026-10-21.md`（同索引件，含**附录 A**）
- 基线：主仓 `<REPO_ROOT>`，勘察开始于 `a5539c2`，**勘察中途 HEAD 漂到 `64c1caf`**（模型配置批② 合流笔，17:15:10 落库）。本报告所有数字以 `64c1caf` + 工作树（仅未跟踪 `data/`）为准，§7 有漂移清单
- 性质：**只读勘察与矩阵草案，不构成施工令**。未改主仓任何文件，未起服务，未 commit。仅跑了两次 `yarn test`（只读基线，见 §5.0/§5.0.1）

---

## 0. 自证（第 0 步）

`<KIMI_SESSIONS_ROOT>/<WORKSPACE>/<KIMI_SESSION_A>/agents/agent-1370/wire.jsonl` 的 `profile.bind` 单行：

```
{"type":"profile.bind","agentId":"agent-1370","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}
```

同会话另有 4 个并行子代理（agent-1367/1368/1369 等，同为 `DeepSeek/deepseek-flash` + `coder` + `high`），按任务原文（含「i18n + 工具可配置」）区分出本代理 = `agent-1370`。

## 0.1 基线漂移警示（先说，因为它改了三处结论）

勘察期间 `main` 从 `a5539c2` 前进到 `64c1caf`（`merge: 模型配置批②（写路径+URL闸+审计+AI设置页）合流`）。影响：

1. **前端多出 1 个路由页 + 1 个文案常量模块**：`views/AiSettingsView.vue`（707 行）+ `constants/ai-settings-copy.ts`（148 行、82 条中文），路由 `meta.title` 从 6 条变 **7** 条；
2. `frontend/src` 从"14 `.vue` + 40 `.ts`"变 **16 + 41**（另有批① 上下文统计带来的 `ContextPill.vue`/`ContextPopover.vue`）；
3. 四个 feature 分支（`feature/ctx-b23`、`feature/table-ai`、`feature/e2e-ctx`、`feature/model-config-b2`）**相对 main 均 0 提交领先**、`git stash list` 为空 ⇒ **此刻没有任何在途批次的未提交代码**（上一份「可视可操作勘察」里"表格专项正在 8 个共享文件上施工（未提交）"的观测在 `64c1caf` 已不成立）。冲突面因此是**计划面冲突**，不是**并发编辑冲突**。

## 0.2 结论速览（八条，供派工卡直接引用）

1. **规模**：前端 57 个源文件里 **1201** 条中文片段（非注释口径）/ **1264** 条非注释中文行 / 模板内 **139** 文本节点 + **159** 属性值；用户可见文案 **600–900 条**量级（与设计稿独立复现一致）。Top1 是 `AiPanel.vue`（3059 行 / 164 条），Top2–4 是三个向导/定义页。
2. **前端 i18n 设施 = 0**（依赖、调用、配置全零命中）；Element Plus/Day.js 的 locale **硬编码在 `main.ts` 一处**，是唯一注入点；`index.html` 的 `lang`/`title` 与 7 条路由标题另算。
3. **后端 i18n 设施 = 0**（`MessageSource`/`LocaleResolver`/`ProblemDetail`/`messages_*` 全 0），错误响应三形态并存；**码"存在但不稳定"**——无单一源，且已实际漂移（后端有 `RATE_LIMITED`/`AI_CONTEXT_UNAVAILABLE`/`TASK_FINALIZED`，前端 `ErrorCode` 13 条里 0 命中）。**真正的缺口是 70 处无 code 的 `IllegalArgument/IllegalState`**，不是那 20 处裸码。
4. **术语纠正**：本仓没有 `ToolSpecification`（那是 LangChain4j），等价物是 **Spring AI 1.1.8** 的 `ToolDefinition`/`ToolCallback`，且**框架不提供序列化**（`name/description/inputSchema` 三字段），落库序列化要自研。装饰器路径可行，但 `ToolCallback` 有**两个 `call` 重载**、本仓在用 `ToolContext`，**两个都要委托**。
5. **落库**：只有 V1–V4，**V4 已被模型配置批① 占用** ⇒ 工具表迁移应是 **V5**（抢号风险已消除）；`ddl-auto=validate` 要求迁移与实体逐字段对齐。`displayName` 派生有两处（`ToolRegistry:56` + `AiToolsController:67`）。
6. **分解**：建议 8 片 —— **S0 `error.code` 收口（可立即做）** → 模型配置批③ → 上下文 L2/L3 → 表格 → 可视 → 防护②③ → i18n（S1 底座 → S2 工具外置 → S3/S4/S5 抽取 → S6 messages → S7 DB+守卫）。降低触面靠四件事：codemod 三段式（可自动处理 ~300–450 条）、语义等价快照先行、`constants/*-copy.ts` 纪律扩展到在途批次、**工具外置契约放宽为"注解缺省 + YAML 可选覆盖"**。
7. **冲突面**：`AiPanel.vue` × 4 批、`stores/ai.ts` × 5 批、`AiTools.java` × 3 批是三个最贵重合点。**两稿排序建议互相矛盾**（模型配置稿说"i18n 先立骨架"，i18n 稿说"排最后"）——按现状（批② 已合流）应**取后者**，前者作废。
8. **矩阵**：7 组（底座/键与残留/语义等价/error.code 契约/工具注册表/回归/浏览器双语），核心是 **B1 键集双向对齐**、**C5 双受众切 en 后 prompt 仍是中文**、**D2 三方码表对账**、**E8 ToolContext 不丢**、**E10 迁移号 V5**。前端实跑基线 **30 文件 / 559 例**，本机负载下 4–5 例超时抖（非回归，需空载复跑）。

---

## 1. 前端文案现状盘点

### 1.1 规模（`frontend/src` 口径，排除纯注释行；命令见附录 A）

| 口径 | 数值 |
|---|---|
| `.vue` 文件 / `.ts` 文件 | **16 / 41**（57 个） |
| 含中文行（全部） | **5360**（`.vue` 1914 + `.ts` 3446） |
| 含中文行（非注释行） | **1264** |
| 引号/反引号/模板文本节点里的中文片段（非注释行） | **1201** |
| `<template>` 内纯文本节点含中文 | **139** |
| `<template>` 内 `label/placeholder/title/...` 属性值含中文 | **159** |

**估算**：用户可见文案条目量级 = **600–900 条**（设计稿同结论，本次独立复现一致）。1201 是含 data 夹具/日志/`reason` 内部串的上限；139+159 = 298 是"一眼即需 `t()`"的确定性下限；两者之差是 `.ts` 里的状态词表、摘要模板、error-card 三变体、notice 插值串。

### 1.2 Top 20（按"非注释中文片段数"排序）

| # | 片段数 | 文件 | 备注 |
|---|---|---|---|
| 1 | 164 | `src/components/AiPanel/AiPanel.vue` | 3059 行；含 `TOOL_LABELS`(27)、`SUGGESTIONS`(4)、健康标签、空态、卡面 9 态文案 |
| 2 | 130 | `src/views/ExportWizardView.vue` | 向导步骤/按钮/校验 |
| 3 | 99 | `src/views/DefinitionsView.vue` | 表头 + 弹窗 + 字段类型名 |
| 4 | 94 | `src/views/ImportWizardView.vue` | 同 Export 对称 |
| 5 | 82 | `src/constants/ai-settings-copy.ts` | **模型配置批② 新交付的集中文案模块**（已按"code→文案"收敛，是本专项最好的迁移样板） |
| 6 | 57 | `src/stores/ai.ts` | 3200 行；**37 处 `this.notice =` 插值串** |
| 7 | 57 | `src/utils/frontend-tools.ts` | 前端工具参数/结果摘要 |
| 8 | 54 | `src/stores/workspace.ts` | 动作摘要（**双受众**，见 1.4）+ `PAGE_LABELS` |
| 9 | 51 | `src/utils/tool-summaries.ts` | 25 模板 + 10 状态词 |
| 10 | 51 | `src/views/TaskCenterView.vue` | 任务表 + 状态词 |
| 11 | 35 | `src/types/condition.ts` | 条件运算符标签（`等于/不等于/介于/包含于…`，**UI 与查询构造共用**） |
| 12 | 34 | `src/views/DataBrowserView.vue` | |
| 13 | 26 | `src/components/JobProgress.vue` | |
| 14 | 24 | `src/utils/blocks.ts` | |
| 15 | 24 | `src/utils/format.ts` | `WEEKDAY_LABELS`、任务类型/步骤词表、`刚刚` |
| 16 | 20 | `src/api/sse.ts` | 4 处内部 reason（`连接已取消` 等） |
| 17 | 20 | `src/utils/excel.ts` | |
| 18 | 18 | `src/stores/task.ts` | 任务状态 label |
| 19 | 16 | `src/components/wizard/DefSelector.vue` | |
| 20 | 15 | `src/utils/context-usage.ts` | 上下文批① 新增（分桶名） |

> 交叉口径（按"非注释含中文行"）：AiPanel 286 / Export 131 / Import 97 / Definitions 96 / stores/ai 68 / frontend-tools 57 / TaskCenter 54 / workspace 52 / tool-summaries 39 / DataBrowser 35 —— 与上表同序，两种口径互证。

### 1.3 现有 i18n 框架痕迹：**零**

| 检索 | 结果 |
|---|---|
| `vue-i18n\|useI18n\|createI18n\|\$t(`（`frontend/src` + `package.json`） | **0** |
| `frontend/package.json` 依赖 | 20 项，**无 `vue-i18n`**；无 `@intlify/*` |
| `frontend/node_modules` | 无任何 `i18n/intlify` 包（连传递依赖都没有） |
| `intlify` 相关 vite 插件 / `unplugin-vue-i18n` | 无 |

**Element Plus / Day.js 现状（locale 被硬编码在入口，是唯一注入点）**：

- `frontend/src/main.ts:13` `import zhCn from 'element-plus/es/locale/lang/zh-cn'`；`:28` `app.use(ElementPlus, { locale: zhCn })`；
- `:17` `import 'dayjs/locale/zh-cn'`；`:23` `dayjs.locale('zh-cn')`；
- **Element Plus 全量注册**（`app.use(ElementPlus)`），无按需插件 ⇒ 切 locale 需改走 `<el-config-provider :locale>`（包在 `App.vue` 根），不能只改入口常量；
- 版本实测：`element-plus 2.14.7`（`package.json` 写 `^2.9.3`）、`dayjs 1.11.23`、`vue 3.5.43`；
- SpreadJS 另有一套中文资源（`utils/spreadjs.ts:11-12,50-53` `@grapecity/spread-sheets-resources-zh` + `CultureManager.culture('zh-cn')`）——**i18n 不会自动覆盖它**，是"en 下仍有中文"的已知残留（§8）。

**其他静态硬编码**：`frontend/index.html:2` `<html lang="zh-CN">`、`:7` `<title>配置管理平台</title>`；`src/router/index.ts:28-64` **7 条** `meta.title` 全中文（新增 `/settings/ai`）。

### 1.4 两个结构性陷阱（比"条数"更影响施工）

**陷阱 1 · 四份重复的工具文案源**

| # | 落点 | 条数 |
|---|---|---|
| ① | `AiPanel.vue:1247` `TOOL_LABELS` = 工具名 → 中文（18 后端工具名 + 前端工具 + 9 条 `export.*`/`import.*` 页面能力名） | **27** |
| ② | `utils/tool-summaries.ts:21-32` 状态词 10 条 + `summaryFor` 摘要模板 | 25 模板 + 10 词 |
| ③ | `types/tools.ts:82-108` `FRONTEND_TOOLS[].displayName` | 2 |
| ④ | 后端 `ai/tool/ToolRegistry.java:56-58`：`displayName` = **`@Tool.description` 前 20 字截断**（`ToolMeta.java:14` 注释原文 `// Chinese display name`） | 18（派生） |

⇒ i18n 若只搬 ①，UI 上仍会看到 ④ 下发的截断描述（`GET /api/ai/tools` 面）；**这四份必须在同一批里收敛到 `tool.<toolKey>.title`**。

**陷阱 2 · 双受众字符串（会静默改模型输入）**

`stores/workspace.ts:212` `touch(summary)` 写入的 `recentActions` 会作为 `context.extra.recentActions` **送进模型**（同文件 `:90`、`:157-195` 的 6 处 `touch()` 全是中文动宾串）。同类还有：`types/condition.ts` 运算符 `label`（UI 与查询构造共用）、`stores/workspace.ts:49` `PAGE_LABELS` ↔ 后端 `ai/tool/ContextBuilder.java:55` `PAGE_LABELS`（两边注释互指"必须同改"）。

⇒ 抽取前必须先做**双受众二分**（UI 侧进 i18n，prompt 侧保留中文单语），否则"切英文界面"会把中文提示词悄悄变成英文，改变模型行为——这是本专项**唯一会改变运行时语义**的风险点。

---

## 2. 后端消息现状

### 2.1 i18n 设施：**零**（逐项实测 0 命中）

`grep -rn "MessageSource|LocaleContextHolder|LocaleResolver|ProblemDetail|problemdetails" backend/src/main` = **0**；`backend/src/main/resources` 下无任何 `messages*.properties`；`application.yml` 无 `spring.messages` 段。（`application.yml:43` `ddl-auto: validate` 在，这是表设计阶段的硬约束。）

### 2.2 错误/消息产生的三种形态（全部中文硬编码）

| # | 形态 | 落点 | 样例 |
|---|---|---|---|
| ① | 业务信封 `{success, message?, code?, data?}`（`@JsonInclude(NON_NULL)`） | `common/ApiResponse.java`；`common/GlobalExceptionHandler.java:18-68` 六类 handler | `ApiResponse.error(e.getMessage())`（**无 code**） |
| ② | AI 端点**裸对象** | `ai/web/AiController.java`（`"code",` 出现 **20** 次、**9** 个不同码） | `Map.of("code","BAD_REQUEST","message","sessionId 与 message 均为必填")` |
| ③ | SSE `error` 帧 | `ai/run/SseChatEmitter.java:907-913` → `{type, atMs, runId, code, message}` | `UPSTREAM_STREAM_ERROR` / `AI_RUN_FAILED` / `AI_RESUME_FAILED` |

### 2.3 `error.code` 现状：**有码，但无单一源、且已实际漂移**

| 载体 | 实测 | 说明 |
|---|---|---|
| `ai/tool/ToolOutcome.java` `CODE_*` | **15** 个不同码 | 工具面，常量类内自洽 |
| `new ConflictException("CODE", …)` | **15 处调用 / 8 个不同码**（`TASK_FINALIZED`×4、`PRECHECK_NOT_PASSED`×2、`JOB_ALREADY_RUNNING`、`JOB_ALREADY_FINAL`、`DEFINITION_{CODE_DUPLICATE,HAS_DATA,REFERENCED,IN_ACTIVE_TASK}`） | 分散在 `job/`、`task/`、`definition/` 三个 service |
| `AiController` 裸字面量 | 20 处 / **9 个码**（`BAD_REQUEST`×8、`SESSION_BUSY`、`SUSPEND_POOL_SATURATED`、`UNKNOWN_RUN`、`UNKNOWN_TOOL_CALL`、`DUPLICATE_TOOL_CALL_ID`、`MESSAGE_TOO_LONG`、`RATE_LIMITED`、`AI_CONTEXT_UNAVAILABLE`） | 后 3 个是**批① 防护/上下文新加的**，设计稿写作时还不存在 |
| SSE `error` 调用点 | 4 处（`ResilientChatService:594,654`、`ResumeService:638`、`AiController:281`） | 码由上游 reason 透传，非枚举 |
| `AiAvailability.code()` | 3 档（`AI_UNAVAILABLE`/`AI_REDIS_UNAVAILABLE`/动态） | 文案 `reason` 中文 |
| **前端镜像** `types/api.ts:51` `ErrorCode` | **13** 个 | 手工同步，**无对账用例** |

**稳定性判定：码"存在"但"不稳定"**——不是码值会变，而是**码的集合没人管**。已发生的实证漂移（本次勘察新发现，可直接作为 M3 契约用例的红测证据）：

- 后端有、前端镜像 0 命中：`RATE_LIMITED`、`AI_CONTEXT_UNAVAILABLE`、`TASK_FINALIZED`、`PRECHECK_*`、`DEFINITION_*`（8 个）；
- `MESSAGE_TOO_LONG` 前端只有 2 处字面量、未进 `ErrorCode` 枚举；
- 反向：`FRONTEND_CANCELLED`、`FORM_SCHEMA_REJECTED` 是"前端登记、后端不一定发"的防御性码。

**无码面（真正的缺口）**：`new IllegalArgumentException/IllegalStateException` 带中文消息 **70 处** ⇒ 全部经 `ApiResponse.error(message)` 返回，**没有 code**，前端只能靠文案分支。这批是"错误码收口"必须处理的主量，不是那 20 处。

### 2.4 消息外置的可行落点（按可行性排序）

1. **`GlobalExceptionHandler` + `ErrorBodyFactory`（新建）**：唯二出口，天然收口点；`ConflictException` 已带 `code`，改造只需把 `message` 换成 `messageSource.getMessage(code, args, locale)`。
2. **`AiController` 的 9 个裸 `Map.of(...)`**：改成走同一 factory。
3. **SSE 帧**：`SseChatEmitter.error(code, message)` 已是 `code + message` 二元组，加 `args` 即可，无需改帧类型（**帧契约零变更**，这点比设计稿更乐观）。
4. **作业 issues / `validation_issues` 表**：文案**落库**（`ExportJobRunner:208` `"导出作业失败: "`、`JobService` 的 issues 串），已持久化的历史行无法回溯翻译 ⇒ 只能"新写用 code，旧行按原文展示"。**本专项不做**（设计稿 §9 已登记边界）。
5. **前端 37 处 `ai.notice`**：是"前端自己的文案 + 后端 message 拼接"的混合体（`stores/ai.ts:1738` `AI 未启用：${error.message}`），必须拆成"本地模板 + code 参数"，否则 en 下会中文夹英文。

### 2.5 文案规模与 E2E 硬耦合

- 后端非注释中文串 **1186** 条（75 文件）；其中 **log 261** / 非 log **925**，非 log 分布：`ai` 603、`seed` 159（**数据种子夹具，不抽**）、`job` 68、`task` 35、`config` 20、`definition` 20、其余 <10。
- 真正需要外置的"用户可见面"≈ 34 键（18 工具码 + 11 HTTP 码 + 5 数据面码），与设计稿估算一致。
- **E2E 硬绑中文**：`scripts/verify-e2e.ps1`（2442 行、29 用例）里**按中文文案断言/反断言共 14 处**（`:621-624`、`:778`、`:784`、`:848`、`:855`、`:1008`、`:1750`、`:1830`、`:1865`、`:1955`、`:2404`）。设计稿写"10 处"——**实测 14**，其中 4 处是 `-notlike`（断言"日志里**不**出现某中文"），改名/改语种会**假绿**，比正向断言更危险。

---

## 3. `ToolSpecification` 现状（含一处术语纠正）

### 3.1 术语纠正：本仓没有 `ToolSpecification`，等价物是 Spring AI 的 `ToolDefinition`/`ToolCallback`

- `pom.xml:22` `spring-ai.version = 1.1.8` + `spring-ai-starter-model-openai`；**无 LangChain4j 依赖**（全仓 0 命中）。
- 调研件里的 `ToolSpecification` + `toJson()/fromJson()` 是 **LangChain4j** 的 API。本仓对应面（从 `~/.m2/.../spring-ai-model-1.1.8.jar` 字节码实测）：
  - `org.springframework.ai.tool.definition.ToolDefinition`：**只有** `name()` / `description()` / `inputSchema()`，**没有任何序列化方法**；
  - `org.springframework.ai.tool.ToolCallback`：`getToolDefinition()` / `getToolMetadata()` / **两个 `call` 重载**；
  - `DefaultToolDefinition.builder()` 可用。
- **结论**：落库的"可序列化"必须**自己实现**（JSON 三字段往返 + 对账），框架不白送。设计稿 §3.3 的 `ToolDefinitionOverride` record + `DefaultToolDefinition` 映射方向正确。

### 3.2 定义与装配现状

| 面 | 现状 |
|---|---|
| 声明 | `ai/tools/AiTools.java`（608 行）：**18 个 `@Tool(name, description)` + 42 个 `@ToolParam(description, required)`**，description **全中文** |
| 装配 | `ai/tool/ToolRegistry.java:31-78` `@PostConstruct` 反射扫全部 bean → `method.getAnnotation(Tool.class)` → `MethodToolCallbackProvider.builder().toolObjects(bean).build()` → `callbackMap` |
| 披露 | `forContext(ctx)` 按 `@ToolScope` 标签裁剪（`:83-93`）；`matchesContext` 是执行前第三道防线 |
| 展示名 | `ToolRegistry.java:56-58`：`description.length() > 20 ? substring(0,20) : description` ——**模型文案与 UI 文案是同一根字符串**（调研 §5.3 明确要拆的东西） |
| 诊断面 | `GET /api/ai/tools?page&taskType&step&taskId` → `AiToolsController.java:51-83`，下发 `name/displayName/riskLevel/channel/scopePatterns` |
| 不一致（新发现） | 同文件 `:67`：`meta == null ? callback.getToolDefinition().description() : meta.getDisplayName()` —— 元数据缺失时下发**完整 description**，与 20 字截断两种形态并存 ⇒ M7-5 退役 displayName 时这里也是改造点 |

### 3.3 落库：**工具表不存在，需新建；版本号已不是 V4**

- Flyway 只有 **V1–V4**：`V1__schema.sql`（14 张业务表）、`V2`、`V3__ai_feedback.sql`、`V4__ai_provider_config.sql`（**模型配置批① 占用了 V4**，4 张表 `ai_provider/ai_model/ai_setting/ai_config_audit`）。
- **无任何工具元数据表、无对应 JPA 实体**（`@Entity` 17 个，全是业务 + `AiFeedback` + 4 张配置表）。
- ⇒ 设计稿 §3.3 的 `V4__ai_tool_registry.sql` **必须改为 `V5`**；且 `ddl-auto: validate`（`application.yml:43`）要求**迁移与实体逐字段对齐**，否则启动即失败（这一点设计稿已标红，实测确认）。
- 上下文统计批① 已入库但**没有**新增表（`chat_session_usage` 最终没落地，用的是 Redis 键族）⇒ **V5 目前无人争号**，序号风险已消除（设计稿 §9"抢号"条可销）。

### 3.4 "标签化版本"的含义（与调研件核对）

按调研 §1/§2-Q3.2（Langfuse/LangSmith 模型）：

1. **行不可变**：`ai_tool_def(tool_key, version)` 每次改文案插新行，旧行永久可回滚；
2. **标签 = 指针**：`ai_tool_release(label, tool_key, version)`；发布 = 移指针，回滚 = 指针移回旧版本；
3. **缺标签 fail-fast**：找不到 `production` 指针时**报错/启动失败**，不静默回落（Langfuse 口径"缺标签直接 404，不静默回退"）；
4. **改描述即击穿 prompt 缓存**（tools → system → messages 前缀），所以必须"发新版本 + 移指针"，**禁止"保存即生效"**；
5. 本仓单实例无租户 ⇒ 只启用 `production`/`staging` 两个 label（设计稿同）。

### 3.5 装饰器可行性：**确认可行，但有一个必须注意的重载陷阱**

- 设计稿 §3.3 的 `OverriddenToolCallback implements ToolCallback` 方向正确：`getToolDefinition()` 返回外置 definition，`call()` 委托原 callback。
- **陷阱（字节码实测）**：`ToolCallback` 有 **两个 `call` 重载**。本仓确实在用 ToolContext（`ResilientChatService.java:339` 写入 `runId`/`sessionId`，`SpToolCallingManager.java:1007` 读取）。若只覆写 `call(String)`，被调 2 参重载时 ToolContext 会**静默丢失**（当前 18 个工具方法都不带 `ToolContext` 参数，所以是**潜伏**缺陷，一旦有人加就炸）。
- ⇒ 施工口径：**两个重载都要委托**；并补一条"装饰器不丢 ToolContext"的用例。

### 3.6 生成式表单：工具描述语言一变，用户可见表单文案跟着变

`generative_form` 的 `label`/`placeholder`/`options[].label` **由模型按 schema 生成**（`AiTools.java:553,588,601` 的 `@ToolParam(description="字段标签…")` 是中文指令）。若把工具 description 切英文，模型**大概率**改吐英文 label ⇒ **用户可见面直接变英文**。这是 D3"本批保持中文单语"的又一条实证理由，也是"`field_key` + 前端 i18n"必须后置的原因。

---

## 4. 施工分解建议（如何把"触面最大"的冲突降到最低）

### 4.1 版本与工具链核对（施工前就要钉死）

| 项 | 实测 | 结论 |
|---|---|---|
| `vue-i18n` | `npm view` = **11.4.16**，`peerDependencies.vue = ^3.0.0` | ✅ 与 `vue 3.5.43` 兼容；v11 是当前线（无 v12 可担） |
| 包管理 | `yarn 1.22.22`（classic）+ `yarn.lock` **v1**；CI `yarn install --frozen-lockfile` | ⚠️ 加依赖**必须同笔提交 `yarn.lock`**，否则 CI 直接红 |
| Element Plus | 装 2.14.7（spec `^2.9.3`） | `ElConfigProvider` 全 2.x 可用 |
| Day.js | 1.11.23 | `dayjs.locale()` 切换 + 补 `import 'dayjs/locale/en'` |
| 构建 | `vite.config.ts` `manualChunks.vendor = ['vue','vue-router','pinia','axios','dayjs']` | 需把 `vue-i18n` 加进 `vendor`，否则进主 chunk（体积登记项） |
| 测试注入 | `vitest.config.ts` 只有 `setupFiles: ['./tests/setup.ts']`；**242 处 `mount()`** 分散 30 个 spec | ⚠️ 底座批必须用 `config.global.plugins` 在 setup 里**一处注入** i18n 实例，绝不能逐 spec 加 |

### 4.2 切片（8 片；片内可并行，片间按依赖）

| 片 | 内容 | 触面（实测条数） | 依赖 / 冲突 |
|---|---|---|---|
| **S0** | `error.code` 收口：`common/ErrorCodes.java` + `ApiResponse.args/traceId` + `ErrorBodyFactory` 雏形 + 前后端对账用例 | 后端 6 处载体（ToolOutcome 15、Conflict 8、AiController 9、SSE 4、Availability 3）+ 前端 `types/api.ts` 13 | **零文案变更、零 UI 变更**，可**立即独立做**。越早做，后续批次加码越省（表格/可视/模型配置批③ 都要加码） |
| **S1** | 前端底座：`vue-i18n@11` + `stores/locale.ts` + `locales/{zh-CN,en-US}.json`（**空壳**）+ `ElConfigProvider` + dayjs + `<html lang>` + `document.title` + `Accept-Language` 拦截器 + 路由标题 key 化 + **测试 setup 全局注入** | 新增 ~6 文件；改 `main.ts`/`App.vue`/`index.html`/`vite.config.ts`/`tests/setup.ts`/`router/index.ts`；**业务文案 0 条** | 独立可做，但**建议仍留在 i18n 批内**（见 4.4 的权衡） |
| **S2** | 工具定义外置（模型侧）：`ToolDefinitionSource`/`ToolSnapshot`/`OverriddenToolCallback` + `ai/tools.yaml` + 启动期 schema 对账 + 帧内 `toolKey` + `displayName` 退役 | `AiTools.java`（18×description + 42×param）、`ToolRegistry`、`ToolMeta`、`AiToolsController:67` | **必须等表格(8 工具)+可视(7 工具)的工具集定稿**，否则每加一个工具都要补 YAML 行（见 4.3 契约解法） |
| **S3** | 抽取 A：AI 面板族 | `AiPanel.vue`(164) + `stores/ai.ts`(57+37 notice) + `tool-summaries`(51) + `error-card`(13) + `parts/blocks` + `FormRenderer`(26) + `ContextPill/Popover` | 面板是"四份重复源"的收口地，**必须与 S2 同批或紧随** |
| **S4** | 抽取 B：五页 + 公共件 | Export(130)/Import(94)/Definitions(99)/TaskCenter(51)/DataBrowser(34)/JobProgress(26)/TaskWizard/DefSelector/ConditionForm/SpreadGrid/AiSettingsView+constants(82)/AiFeedback | 与 S3 无共享词表冲突，可并行 |
| **S5** | 抽取 C：词表与类型层 + **双受众二分** | `types/condition.ts`(35)、`utils/format.ts`(24)、`stores/task.ts`(18)、`stores/workspace.ts`(54，`touch()` 拆分)、`PAGE_LABELS` 两端 | 先把"进 prompt 的串"列白名单冻结，再抽 UI 侧 |
| **S6** | 后端 messages + ProblemDetail 双形态 | `messages_zh/en.properties`（~34 键）+ `AcceptHeaderLocaleResolver` + advice 双形态 + **locale 显式下传**（`LocaleContextHolder` 在 `app.ai.suspend`/`sse-write-*` 池上不可用） | 可与 S4/S5 并行；与 S0 有先后（先 S0） |
| **S7** | DB 版本化 + 守卫 | `V5__ai_tool_registry.sql` + `ai_tool_def/release` 实体 + 发布/回滚 + 缺标签 fail-fast + `check-i18n-residue.mjs` 进 CI + 双语截图 | 最后；**闸门必须在所有在途批次的文案落定后开**，否则一开就红 |

### 4.3 降低触面的四个手法（本勘察的主要建议）

1. **codemod 三段式**（不是人肉 1201 条）：
   - `extract`：一次性脚本按"最高置信度模式"抽 CSV（`文件:行 → 建议 key → zh 原文`），模式限三类：`>中文<` 文本节点、`label/placeholder/title="中文"` 静态属性、`'中文'`/`` `中文` `` 纯字面量；含 `${}` 插值的一律标 `manual`；
   - `dry-run`：输出 unified diff + 统计（预计可自动处理 139+159+部分字面量 ≈ **300–450 条**，占 600–900 的 50% 左右）；
   - `apply`：逐文件 apply，**一文件一提交**（便于回滚与按文件审）。
   - 脚本**不进 CI**（一次性工具），但要入库留痕（`scripts/i18n/`）。
2. **语义等价快照先行**：迁移前对 zh 输出打快照（`toolSummary()` 25 模板 × 18 工具、`ai.notice` 37 条、`TOOL_LABELS` 27 条、error-card 3 变体），迁移后**逐字比对**。这比"读 diff"可靠得多，也是唯一能防"搬错一个字"的手段。
3. **`constants/*-copy.ts` 纪律扩展到在途批次**（零成本、收益最大）：模型配置批② 已经示范（`ai-settings-copy.ts` 148 行集中文案 + `describeAiConfigError` code→文案）。**建议给表格/可视/批③ 下一条纪律：新文案只准进 `constants/<area>-copy.ts`，不得散写模板**。这一步不花任何工期，却能把后续 300+ 条新文案的抽取成本降一个量级，也避免"先硬编码再迁移"。
4. **工具外置的"缺省+覆盖"契约要放宽**（对设计稿的一处修正）：设计稿 §3.3 校验①要求 YAML 与 `@Tool(name)` 必须存在映射、⑤只让 `published` 进快照。但表格/可视批次会持续新增工具（+15 个），若"每个工具都必须在 YAML 有一行"，工具外置就被其他批次绑架。**建议契约改为**：注解 = 缺省（永远可用）；YAML 条目 = **可选覆盖**；只在"YAML 有条目"时才做 schema 对账与 fail-fast；`toolKey` 缺省等于 `name`。这样 S2 可以更早做，且新工具不会撞红。

### 4.4 一处必须裁的排序矛盾（见 §6.3）

模型配置设计稿 §"与 i18n 的交点"写的是 **"i18n 先立骨架（推荐）"**；i18n 设计稿 §7.3 论证的是 **"i18n 排最后"**。两稿直接冲突。按 `64c1caf` 的现状（批② 已合流、设置页已用集中文案模块落地），"i18n 先行"的窗口**已经关闭**——强行先做只会让 `AiSettingsView` 二次迁移。**建议按 i18n 稿执行**，并把模型配置稿的那段标注为"已被批② 的实际落地方式取代"。

---

## 5. 测试矩阵草案

### 5.0 基数（本次实测，作为矩阵的"红/绿"参照）

| 面 | 数字 | 来源 |
|---|---|---|
| 前端 spec 文件 | **30** 个（`frontend/tests/*.spec.ts`） | 实测；设计稿写 26、看板写 29（均过时） |
| 前端 `mount()` 调用点 | **242** 处 | 实测（决定 i18n 注入方式） |
| 前端中文文案断言 | **329** 处（`toContain/text()/toBe` 带中文） | 实测；设计稿写 297 |
| **前端实跑（`yarn test`，本次两跑）** | **559 例 / 30 文件**：第 1 跑 545 过·5 败·9 跳；第 2 跑 546 过·4 败·9 跳 | 见 §5.0.1 |
| 后端测试类 | **154** 个 `*Test.java`（卡片口径 939 例） | 实测 + 看板 |
| E2E | 29 用例 / 2442 行；**14 处**文案耦合 | 实测 |

### 5.0.1 基线实跑的两点发现（施工首步必须复跑确认）

1. **本机两跑都不全绿，且失败集合在变**：第 1 跑败 5 例、第 2 跑败 4 例（`b1-suspend-disconnect`×2、`restore-reconcile`×1、`restore-request-emitted`×1、`ai-panel-composer-limit` 文件级）。
   **失败性质全是超时**（`Hook timed out in 30000ms` / `Test timed out in 15000~20000ms`），外加 1 例 `expected 'not-sent' to be 'interrupted'`（SSE 看门狗时序断言）。
   勘察当刻本机有 4 个并行子代理在跑，属**负载诱发**；看板登记的 545/545 是在空载下测的。**结论：不是本仓回归，但"i18n 批的验收基数"必须以空载复跑为准**（建议施工首步先跑一次空载基线并留痕）。
2. **`ai-panel-parts-render.spec.ts` 单文件 187 例、耗时 32.7s**，`ai-panel-composer-limit.spec.ts` 的 `beforeAll` 显式给了 30s 导入预算 —— i18n 注入若让 `AiPanel` 的导入链再变重（vue-i18n + locale 资源），**这两个文件最先超时**。⇒ 底座批必须用 `config.global.plugins` 注入（不额外增加组件导入面），并把 locale 资源做成同步小 JSON（不要 `import()` 懒加载进测试路径）。

### 5.1 M-A 底座与切换（S1）

| # | 用例 | 期望 | 证红方式 |
|---|---|---|---|
| A1 | bootstrap | `createI18n({legacy:false})` 后 `i18n.global.locale.value==='zh-CN'`；`<html lang>`=zh-CN | 无底座 ⇒ import 失败 |
| A2 | 默认语言不嗅探 | 伪造 `navigator.language='en-US'` 仍为 zh-CN（待裁 A 口径） | 若实现嗅探 ⇒ 红 |
| A3 | 切换四件事 | `setLocale('en-US')` ⇒ i18n locale / `<html lang>` / `localStorage['cm.locale']` / 下一请求 `Accept-Language` 四者同变 | 任一漏 ⇒ 红 |
| A4 | 持久化优先序 | localStorage 有值 > 默认；坏值（`'xx'`）回落 zh-CN 且不抛 | — |
| A5 | Element Plus 联动 | en 下 `ElConfigProvider` locale = en（断言 `el-pagination` 的 `Total` 文案或 locale 对象 identity） | 只改 main.ts 常量 ⇒ 红 |
| A6 | Day.js 联动 | en 下 `formatDateTime` 输出英文月份；zh 下中文 | 漏 import en locale ⇒ 红 |
| A7 | 路由标题 | 7 条 `meta.title` 随 locale 变；`document.title` 同步 | 硬编码 ⇒ 红 |
| A8 | 测试注入 | `mount(AiPanel)` 不显式传 i18n 也能渲染（setup 全局注入生效） | 逐 spec 注入 ⇒ A8 红且 242 处要改 |

### 5.2 M-B 缺 key、回退、残留（S1+S7）——本专项的"核心验收"

| # | 用例 | 期望 | 证红方式 |
|---|---|---|---|
| B1 | **键集对齐** | 遍历 `zh-CN.json` 全部叶子 key，`en-US.json` 必须都有；反之亦然（**双向**，不是单向） | 缺 en ⇒ 红 |
| B2 | 缺 key 行为 | 运行期取不存在的 key：渲染 key 原文 + `fallbackWarn` 记录（不渲染空白、不抛） | — |
| B3 | 插值完整性 | 每个 key 的 `{name}` 占位符在 zh/en 两侧**同名同数**（正则比 `{...}` 集合） | 漏参 ⇒ en 下显示 `{name}` |
| B4 | 复数/列表 | zh 单分支、en 双分支的 key 各自渲染正确（`{count} 条` vs `{count} item\|{count} items`） | — |
| B5 | **残留扫描** | `check-i18n-residue.mjs`：非注释行出现 CJK ⇒ 红；allowlist 仅四类（模型侧 prompt 常量、seed 夹具、log 文案、generative_form schema 说明） | 现状 1201 条 ⇒ 全红（所以只能最后开闸） |
| B6 | allowlist 不可静默膨胀 | allowlist 条目数上限 + 每条必须有理由串（防"全加白名单"式作弊） | — |
| B7 | 扫描器误杀防护 | 含中文的**测试夹具**（`tests/` 不在扫描面）、注释（`//`/`/*`/`<!--`）、正则字面量（`/配额/`）三类不报 | error-card 的 `QUOTA_RE` 含中文 ⇒ 必须不报 |

### 5.3 M-C 语义等价（迁移不许改文案）

| # | 用例 | 期望 |
|---|---|---|
| C1 | 工具摘要在 zh 下**逐字**等于迁移前快照（25 模板 × 18 工具） | 快照断言 |
| C2 | `TOOL_LABELS` 27 条 zh 值逐字不变 | 快照 |
| C3 | `ai.notice` 37 条与迁移前逐字一致（插值后） | 快照 |
| C4 | error-card 三变体 title/guide/action 逐字不变 | 快照 |
| C5 | **双受众不变**：`recentActions` 送模型的串在切 en 后**仍是中文** | 切 en 后快照必须不变（这条最容易漏） |
| C6 | `PAGE_LABELS` 前端/后端两端口径一致 | 交叉断言 |

### 5.4 M-D `error.code` 契约（S0）

| # | 用例 | 期望 | 证红方式 |
|---|---|---|---|
| D1 | **单一源** | 后端所有 code 来自 `ErrorCodes`；源码里除该文件外无大写码字面量（curated allowlist + 正则扫描） | 现状 6 处载体散落 ⇒ 红 |
| D2 | **三方对账** | `ErrorCodes` ↔ 前端 `ErrorCode` ↔ `messages_zh/en` 键集，三向差集为空 | 现状已漂 3 个码 ⇒ 红（实证） |
| D3 | 无码面清零 | 70 处 `IllegalArgument/State` 的响应体必须带 `code`（或明确归入"内部错误"单码） | 现状无码 ⇒ 红 |
| D4 | messages 覆盖 | 每个用户可见码都有 `error.<CODE>` 键；**无键时不得回显裸 key**（回落到原文） | — |
| D5 | locale 协商 | 无 `Accept-Language` ⇒ zh（与今天逐字一致）；`en` ⇒ 英文；`zh-TW` ⇒ 回落 `messages_zh` | 默认不是 zh ⇒ E2E 全红 |
| D6 | 池线程 locale | 挂起/续跑/SSE 写在专用池执行时，`message` 仍按**请求时锁定的 locale** 生成（不得在池线程读 `LocaleContextHolder`） | 池线程读 ThreadLocal ⇒ 偶发中英串味 |
| D7 | ProblemDetail 双形态 | `Accept: application/problem+json` ⇒ `type/title/status/detail/code/args/instance/traceId`；**其它 Accept ⇒ 响应字节与今天一致** | 整体切换 ⇒ 前端 13 码分支 + E2E 红 |
| D8 | `args`/`traceId` 非破坏 | 存量响应**不新增键**（`NON_NULL`） | 加了空键 ⇒ 字节断言红 |

### 5.5 M-E 工具注册表与版本标签（S2/S7）

| # | 用例 | 期望 | 证红方式 |
|---|---|---|---|
| E1 | 覆盖生效 | YAML 改 description ⇒ 送模型的 `ToolDefinition.description()` 变，`call()` 行为不变 | 未覆盖 ⇒ 红 |
| E2 | 缺省回落 | YAML 无该条 ⇒ 用注解值（**放宽后的契约**，见 4.3-4） | 若要求"必须有行" ⇒ 新工具启动失败 |
| E3 | schema 对账 | YAML `inputSchema` 与反射生成的不一致 ⇒ 启动失败并打印 diff | 无对账 ⇒ 红 |
| E4 | 请求路径零 DB | 计数 DataSource 代理：`forContext()` ×100 ⇒ SQL=0 | 每请求查库 ⇒ 红 |
| E5 | 缺标签 fail-fast | `production` 指针缺失 ⇒ 生产 profile 启动失败；dev 允许 fallback + WARN；**不得静默回落** | — |
| E6 | 版本不可变 | 改文案必须插新行；UPDATE 旧行被拒（应用层断言） | — |
| E7 | 标签发布/回滚 | 移指针 ⇒ 新快照生效；移回 ⇒ 逐字回到旧 description | — |
| E8 | **ToolContext 不丢** | 装饰器 `call(input, toolContext)` 委托后，工具拿到的 `runId` 不变（用测试工具读取 ToolContext 断言） | 只覆写 1 参重载 ⇒ 红（潜伏缺陷，见 3.5） |
| E9 | `displayName` 退役 | `GET /api/ai/tools` 返回 `titleKey`；改 description 后 `titleKey`/展示名不变 | 现状派生 ⇒ 红（含 `AiToolsController:67` 的 meta==null 分支） |
| E10 | 迁移号 | 新迁移 = `V5`；`ddl-auto=validate` 启动通过（实体逐字段对齐） | 用 V4 ⇒ 迁移冲突 |
| E11 | 快照版本可观测 | `version()` 变化打 INFO（含旧/新），供缓存排查 | — |

### 5.6 M-F 回归面（口径不改）

| # | 面 | 期望 |
|---|---|---|
| F1 | 前端单测 | 30 文件 / 559 例全绿（**空载**基线；本机负载下已观测 4–5 例超时抖，见 §5.0.1）；**329 处中文断言逐条复核**（改断言 = 改契约，须在施工报告登记） |
| F2 | 后端单测 | `mvn test` 全绿（154 类 / 939 例口径）；`ddl-auto=validate` 抓 DDL 漂移 |
| F3 | E2E | 29 用例全绿；**14 处**中文耦合点逐条确认（含 4 处 `-notlike` 反断言，改语种会假绿） |
| F4 | typecheck / build | `vue-tsc --noEmit` 0 错；`vite build` ✓；`manualChunks` 加 `vue-i18n` 后体积增量登记 |
| F5 | 文档门禁 | `check-docs` 6/6 |
| F6 | 脱敏 | 工作树 0 命中 |
| F7 | 浏览器验收 | zh/en 双语逐页截图（**7 页**，本次新增 AI 设置页 + AI 面板 4 种卡态）；静态扫描不能替代 |

### 5.7 不建议进矩阵的（诚实边界）

- "en 下零中文"**不可测**：模型正文、后端 `reason`/`message`、作业 issues 落库文案、SpreadJS 中文资源包都带中文。只能测"chrome 层零中文 + 残留清单化"。
- 工具描述语言对**模型准确率**的影响：无 A/B 数据，不做。

---

## 6. 与在途批次的冲突面评估与排序建议

### 6.1 文件 × 批次热区矩阵（● = 会改）

| 文件 | i18n | 模型配置批③ | 上下文 L2/L3 | 表格(8 工具) | 可视(7 工具) | 防护②③ |
|---|---|---|---|---|---|---|
| `AiPanel.vue`（3059 行 / 164 条） | ● S3 | ● 选择器+参数 popover | ● 压缩提示条/分桶 | ○ | ● `data-ai-*` + 支链 | ○ |
| `stores/ai.ts`（3200 行） | ● 57+37 notice | ● 选择器状态 | ● 压缩状态位/记账 | ● 表格工具回灌 | ● 7 工具路由 | ● 输出收敛 |
| `utils/frontend-tools.ts`（57） | ● | ○ | ○ | ● 执行器 | ● 执行器 | ○ |
| `utils/tool-summaries.ts`（51） | ● | ○ | ○ | ● 新工具摘要 | ● 新工具摘要 | ○ |
| `types/tools.ts` | ● displayName | ○ | ○ | ● | ● | ○ |
| `stores/workspace.ts`（54，双受众） | ● S5 二分 | ○ | ○ | ● 表格动作 | ● 区域动作 | ○ |
| `App.vue`（9） | ● S1 | ○ | ○ | ○ | ● 根级 `data-ai-*` | ○ |
| `ai/tools/AiTools.java`（18 工具） | ● S2 外置 | ○ | ○ | ● +8 | ● +7 | ○ |
| `ai/tool/ToolOutcome.java` | ● S0 码收口 | ○ | ○ | ● 新码 | ○ | ○ |
| `ai/web/AiController.java` | ● 码/文案 | ● `AiChatRequest` 扩展 | ○ | ○ | ○ | ● |
| `ai/run/RunSnapshot.java` | ● locale 观测 | ● `modelId` | ● usage | ○ | ○ | ○ |
| `constants/ai-settings-copy.ts`（82） | ● S4 | ○（批②已落） | ○ | ○ | ○ | ○ |
| Flyway 序号 | V5 | （批② 已用 V4） | ○（未建表） | ○ | ○ | ○ |

### 6.2 三个"最贵"的重合点

1. **`AiPanel.vue` × 4 批**（i18n / 批③ / 上下文 / 可视）：单文件 3059 行、164 条文案、且是 `v-if part.requiresAction` 卡面链的所在地。任何两批并行都会产生结构性冲突（不是行级，是**同一段模板的三向插入**）。
2. **`stores/ai.ts` × 5 批**：3200 行、3000+ 行同文件，`switch(frame.type)` 与 `notice` 双热点。
3. **`AiTools.java` × 3 批**（i18n 外置 / 表格 +8 / 可视 +7）：i18n 要把这对 18×description + 42×param 搬进 YAML，另外两批要在这文件里加 15 个新工具——**谁先谁后决定返工量**。

### 6.3 排序建议（并对一处文档矛盾下令）

**推荐顺序**：

```
S0 error.code 收口（现在就能做，0.5–1 人日）
  → 模型配置批③（面板选择器；把 AiPanel 的最后一批"新增文案"落完）
  → 上下文统计 L2/L3（压缩提示条；同上）
  → 表格专项（+8 工具）
  → 可视可操作（+7 工具）
  → 防护批②③（可与上两条并行，不碰面板文案）
  → i18n S1 底座 → S2 工具外置 → S3/S4/S5 抽取 → S6 messages → S7 DB+守卫
```

理由（四条，都有实测支撑）：

1. **i18n 是"搬移 + 一次性冻稿"的活**：面对一个每天在长的界面拍照，必然二次迁移。当前 `AiPanel.vue` 已因上下文批① 从 2973 → 3059 行、`AiSettingsView` 凭空多 82 条文案，就是证据。
2. **工具外置必须对着"冻结的工具集"**：表格 +8、可视 +7 ⇒ 现在写 YAML 只能写 18 行，之后每批都要补行 + 重跑对账。若采 4.3-4 的放宽契约，可把"契约先行、YAML 行后补"解耦，但 `tool.<toolKey>.title` 的 i18n 键仍应等工具集定稿。
3. **`error.code` 是全批共同地基且零文案变更**：模型配置批③ 要加 `PROVIDER_GONE` 一类码、表格要加写侧码、可视要加 `UI_*` 码。先有单一源，后面加码就是"加一行常量"，否则每次都要重新对账 6 处载体。
4. **在途批次不必等 i18n**：只要遵守 4.3-3 的 `constants/*-copy.ts` 纪律，它们的文案就是"集中且可机械抽取"的，后置抽取几乎无返工。

**要裁的文档矛盾**：`设计-模型配置功能专项` 写"排序建议（二选一，推荐前者）① i18n 先立骨架"；`设计-i18n与工具可配置专项` §7.3 论证"i18n 排最后"。按 `64c1caf` 现状（批② 已合流、设置页已按"集中文案模块"交付），**前者已被现实取代**，建议按后者执行并在前者加注。

### 6.4 一处"顺序无关但必须同批"的耦合

`error.code` 的 `args` 扩展（S0）与前端 `ErrorCode` 镜像（`types/api.ts`）**必须同批**；`messages_zh` 的 zh 值必须与今天的硬编码**逐字相同**（否则 E2E 的 14 处断言里至少 10 处会红）。这两条是"分批"的红线。

---

## 7. 对设计稿的勘误与补充清单（建议直接回写设计稿）

| # | 设计稿写的 | 实测（`64c1caf`） | 处置 |
|---|---|---|---|
| E-1 | `AiPanel.vue` 2973 行 / 333 非注释中文行 | **3059 行 / 286 非注释中文行 / 164 中文片段** | 按符号定位，不按行号 |
| E-2 | `stores/ai.ts` 3120 行 | **3200 行**；`notice` 赋值 **37** 处 | 同上 |
| E-3 | 前端 14 `.vue` + 40 `.ts` | **16 + 41**（+`AiSettingsView`/`ContextPill`/`ContextPopover`） | — |
| E-4 | spec 26 个 / 文案断言 297 处 | **30 个 / 559 例 / 329 处**；`mount()` **242** 处；本机负载下 4–5 例超时抖 | M8-1 基数要改；且必须 `config.global.plugins` 注入 |
| E-5 | E2E 10 处中文断言 | **14 处**（含 4 处 `-notlike` 反断言） | 反断言改语种会**假绿**，必须逐条登记 |
| E-6 | 新迁移 `V4__ai_tool_registry.sql`（§9 还写"与上下文统计抢 V4 号"） | V4 已被 `ai_provider_config` 占用；上下文批① **未建表** | 改 **V5**；抢号风险**已消除** |
| E-7 | `OverriddenToolCallback` 只覆写 `call(String)` | `ToolCallback` 有**两个 `call` 重载**，本仓在用 ToolContext（`ResilientChatService:339`） | 两个都要委托 + 补用例（E8） |
| E-8 | 错误码"六处载体" | 实测 15(工具)/8(冲突)/9(AiController)/4(SSE)/3(Availability) + **70 处无码异常** | 无码面是主量，单独列任务 |
| E-9 | 前端 `ErrorCode` 13 个"与后端手工同步" | 已实际漂移：`RATE_LIMITED`/`AI_CONTEXT_UNAVAILABLE`/`TASK_FINALIZED`/8 个冲突码 **0 命中** | M3 对账用例的现成红测证据 |
| E-10 | `ToolSpecification`（LangChain4j 术语） | 本仓 = Spring AI 1.1.8：`ToolDefinition`/`ToolCallback`，**无任何序列化 API** | 术语统一；序列化自研 |
| E-11 | `displayName` 派生只在 `ToolRegistry:56` | 另有一处：`AiToolsController:67`（meta 缺失时下发**完整** description） | 退役时两处同改 |
| E-12 | 生成式表单文案"模型生成、前端只排版" | 模型之所以吐**中文** label，是因为 `@ToolParam(description="字段标签…")` 是中文 | 切英文描述 ⇒ 表单变英文；D3 裁决据此加固 |
| E-13 | 模型配置稿建议"i18n 先立骨架" | 批② 已合流、设置页已用集中文案模块 | 该建议作废，按 i18n 稿"排最后" |

---

## 8. 诚实边界

- **未跑后端测试**（`mvn test` 需 6+ 分钟且会占用 CPU，与前端测试并发会互相干扰）⇒ 后端 154 类 / 939 例是"文件计数 + 看板口径"，不是本次实跑。
- **前端实跑两跑都不全绿**（4–5 例超时抖动，见 §5.0.1）。我判为并发负载诱发（同机 4 个并行子代理），**未在空载下复跑确认**——因此"30 文件 / 559 例"是稳定事实，"545/546 通过"是抖动区间，**不能当作施工前的绿基线**。
- **未跑 E2E**（需起桩上游 + Redis + 后端，违反"不起服务"）⇒ 14 处文案耦合是静态阅读计数，不是运行观测。
- **未做双语浏览器验收**（本专项最关键的验收手段），因此"en 下会漏中文"的具体清单是**推断**，不是实测。
- **1201 / 1264 / 5360 三个数字口径不同**，不可混用；`grep -c` 级统计不含对象字面量"一行多条"与模板内联表达式，量级 ±10%。
- **`vue-i18n@11.4.16` 与本仓 Vue 3.5.43 只核了 peerDependencies，未实装验证**（`yarn add` + `yarn build` 属施工首步）。
- **"切英文界面不影响模型行为"不成立**：双受众字符串（`recentActions`、`PAGE_LABELS`、`condition.label`）一旦跟着切，prompt 就变——这是本专项**唯一会改变运行时语义**的路径，必须在 S5 之前冻结白名单。
- 勘察期间 HEAD 漂移（`a5539c2 → 64c1caf`），若后续再有合流，§1/§5 的数字会继续偏。

---

## 附录 A · 取证命令（可复现，全部只读）

```bash
cd <REPO_ROOT>

# 0 自证
grep -o '"profile.bind"[^}]*}' "<KIMI_SESSIONS_ROOT>/<sid>/agents/agent-1370/wire.jsonl" | head -1

# 1 前端规模（非注释口径用 node 脚本剔 // * /* <!-- 行）
find frontend/src -name '*.vue' | wc -l          # 16
find frontend/src -name '*.ts'  | wc -l          # 41
grep -rc '[一-鿿]' frontend/src --include=*.vue --include=*.ts | sort -t: -k2 -rn | head -20

# 1.3 i18n 设施零命中
grep -rn 'vue-i18n\|useI18n\|createI18n\|\$t(' frontend/src frontend/package.json | wc -l   # 0

# 1.0 / 5.0 前端基线（空载复跑，两跑对照）
ls frontend/tests/*.spec.ts | wc -l                                     # 30
cd frontend && yarn test 2>&1 | tail -5                                 # Tests 5xx / Test Files 30

# 2 后端
grep -rn 'MessageSource\|LocaleContextHolder\|LocaleResolver\|ProblemDetail' backend/src/main | wc -l  # 0
grep -rh -o 'new ConflictException("[A-Z_]*"' backend/src/main/java | sort | uniq -c
grep -rn 'new Illegal\(Argument\|State\)Exception("[^"]*[一-鿿]' backend/src/main/java | wc -l  # 70
grep -o '"code", "[A-Z_]*"' backend/src/main/java/com/example/configmgr/ai/web/AiController.java | sort | uniq -c

# 2.5 E2E 文案耦合
grep -n "\*[^']*[一-鿿][^']*\*" scripts/verify-e2e.ps1

# 3 工具
grep -c '@Tool(' backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java        # 18
grep -c '@ToolParam' backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java    # 42
grep -n 'setDisplayName' backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java  # 56
grep -h 'CREATE TABLE' backend/src/main/resources/db/migration/*.sql                      # V1..V4

# 3.5 Spring AI API（无 javap 时用 class 常量池）
cd /tmp && mkdir -p sai && cd sai && unzip -o -q ~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.8/spring-ai-model-1.1.8.jar 'org/springframework/ai/tool/*'
grep -a -o 'call\|getToolDefinition\|getToolMetadata' org/springframework/ai/tool/ToolCallback.class | sort -u

# 4 版本
cd frontend && npm view vue-i18n version peerDependencies --json    # 11.4.16 / vue ^3.0.0
yarn --version                                                      # 1.22.22
node -p "require('./node_modules/element-plus/package.json').version"  # 2.14.7

# 6 热区
git log --oneline -22 --first-parent
git rev-list --left-right --count main...feature/table-ai            # 3  0
```
