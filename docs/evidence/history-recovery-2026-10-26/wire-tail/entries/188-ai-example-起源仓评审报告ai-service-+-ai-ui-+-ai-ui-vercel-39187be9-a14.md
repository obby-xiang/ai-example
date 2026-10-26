> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-14` 的 wire 末条 assistant 文本（消息序号 m=31；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 20:00 → 2026-10-05 20:07（wire 事件时间窗，UTC+8）；当时模型：`Zhipu AI/glm-5.3-flash`
> - 原基线：`198604f`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：17712 B / 11972 字符 / 137 行；行指纹覆盖率（本批复算）9.7%（最高单文件 10%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/023-ai-example-kimi-2-代码仓事实性勘察报告-39187be9-agent-11.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：与 `agent-12`、`agent-13` 为**不同仓**的变体评分勘察，非续版；本件评的是三模块起源仓本体
> - 脱敏：路径 2 / 会话标识 0 / 身份 1 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
勘察完成，所有证据已收集齐。以下是完整评审报告。

---

# `ai-example` 代码仓变体评审报告（事实性勘察）

评审对象：`<LOCAL_WORK>\ai-example`（main 分支，工作树干净，与 origin/main 同步）。全部结论基于仓内文件与 git 元数据。

## 1. 项目概览

**定位**：AI Agent 驱动业务向导的全栈演示项目，含两种 AI 运行时范式共享同一套工具体系。

| 组成 | 技术栈 | 说明 |
|---|---|---|
| `ai-service` | Spring Boot 3.5.14 + Java 21 + Spring AI 1.1.8 + JPA/H2 + Lombok，Maven 构建（`pom.xml:10-21`） | 后端，端口 8081 |
| `ai-ui` | Vue 3.5 + Vite 6 + Pinia 2.3 + Element Plus 2.9 + SpreadJS 17（GrapeCity）+ axios，yarn 管理（`ai-ui/package.json`） | 范式1：后端 Agent Loop |
| `ai-ui-vercel` | 同上 + `ai@6.0.221` + `@ai-sdk/openai@3.0.97` + `zod@3.25.76`（`ai-ui-vercel/package.json:12-14`） | 范式2：前端 AI Runtime |

**代码量统计（wc -l）**：

| 部分 | 行数 | 明细 |
|---|---|---|
| 后端 Java | 4,235 行 / 21 文件 | controller 4 文件 456 行；service 6 文件 2,063 行；tool 4 文件 754 行；entity 5 文件 420 行；dto 4 文件 365 行；repository 5 文件 117 行；config 2 文件 48 行 |
| 后端 resources | 530 行 | tools.yaml 358；scenarios.yaml 108；application.yml 64（另有 pom.xml 109、maven-settings.xml 29） |
| ai-ui/src | 5,039 行 / 28 文件 | components 4 文件 1,260；views 9 文件 1,722；utils 4 文件 1,019；stores 3 文件 584；api 4 文件 108 |
| ai-ui-vercel/src | 5,390 行 / 34 文件 | 含 ai/（provider+system-prompt）、tools/（13 工具 + runtime）、mock/configDefs.js |
| 文档 | 5,455 行 | README 370；docs/ 5 文件 3,920；.trae/documents 4 文件 803；memory/ 4 文件 162 |

**目录树（2 层）**：`ai-service/{src/main/java/com/example/ai/{config,controller,dto,entity,repository,service,tool}, src/main/resources}`；`ai-ui/src/{api,components,router,stores,styles,utils,views/wizard}`；`ai-ui-vercel/src/{ai,components,mock,router,stores,styles,tools,utils,views/wizard}`；`docs/`；`memory/`；`scripts/githooks/`；`.trae/documents/`。

## 2. 文档

| 文件 | 行数 | 类型 | 完整度 |
|---|---|---|---|
| `README.md` | 370 | 总览/部署 | 高：架构图、双范式对比表、技术栈表、启动步骤、13 工具清单、信任分级、API 表、设计决策 |
| `docs/design-and-implementation.md` | 636 | 设计+实现+经验 | 高（v1.3）：架构、pause-resume、接口契约、bug 根因复盘（§8 进度条）、12 条经验沉淀、验证记录 |
| `docs/conversation-archive-20260904-excel-capabilities.md` | 1,780 | 对话归档 | 高保真归档（44 条用户消息逐字），非工程文档 |
| `docs/conversation-archive-session-6a8abe78.md` | 1,073 | 对话归档 | 同上（18 条用户消息，声明 #28 真实 key 已替换 `[REDACTED_KEY]`） |
| `docs/conversation-lifecycle-industry-research-and-plan-review.md` | 227 | 调研/评审 | 中高：7 痛点 × 业界对标 × 6 项调整建议 |
| `docs/quick-suggestions-ui-patterns.md` | 204 | 设计方案 | 中：快捷选项设计，**代码中无对应实现**（grep `quickRepl|suggestion` 于两前端 src 无命中）——纯前瞻设计 |
| `.trae/documents/` 4 文件 | 803 | 阶段计划 | ai-ui-vercel 范式 265、collect_user_input 109、生命周期 plan v1.1 236、Excel 计划 193 |
| `memory/` 4 文件 | 162 | 经验沉淀 | `project-memory.md` 91 行极密集（硬约束/约定/30+ 条 Lessons Learned）；`user-profile.md` 含开发者本机路径与 Trae 数据库结构（个人环境信息入库） |

**文档质量摘录**（`docs/design-and-implementation.md:553-556`，体现复盘深度）：
> 1. **串行执行 tool_call**：OpenAI 协议要求 tool_call_ids 与 tool message 数量匹配……否则多 tool_call 时 400。
> 4. **cleanupWaitingState 精细化**：无条件清理同 session 的 WAITING_TOOL 会误杀活跃 state……

**含糊/缺失/自相矛盾之处**：
- **过期时间漂移**：`design-and-implementation.md:159、213` 两处写 AgentState“24h 过期”，但代码已是 10 分钟（`application.yml:58` `waiting-tool-ttl-minutes: 10`，`AiService.java:79-80`）——改造 C1 后文档未回改。
- **接口契约不符**：`design-and-implementation.md:513` 写任务接口为 `POST /{id}/step-data`、`POST /{id}/selected-defs`，实际 `TaskController.java:40-48` 是 `POST /update-step`、`POST /update-selected`；§7.1 也缺 `/pending`、`/cancel` 两个新端点。
- **实体注释自相矛盾**：`AgentState.java:31` 说“同一 sessionId 同一时间只能有一个 WAITING_TOOL”，而 `AiService.java:519-522` 明确“多个活跃 WAITING_TOOL 不冲突”；`AgentState.java:35` 说“24h 未恢复自动清理（由定时任务或下次启动清理）”，实际无定时任务，TTL 为 10 分钟。
- **README 小误**：`README.md:229` 称 wizard 下“9 个步骤页”，实际为 8 个步骤页 + Dashboard 共 9 个视图；README 项目结构只列 `docs/design-and-implementation.md`，未列其余 4 个 docs 文件，`.trae/documents` 列 3 个漏 1 个。
- **失效链接**：设计文档与 .trae 文档中文件链接均指向开发机绝对路径 `file:///<DRIVE>/workspace/trae/hello-world/ai-example/...`，仓库内无效；`design-and-implementation.md:574` 启动命令含 IDE 内置 Maven 硬路径。

## 3. 后端实现

- **入口**：`AiServiceApplication.java`；Web 框架 spring-boot-starter-web（Servlet 栈，非 WebFlux）。
- **数据模型**（5 实体）：`Task`（场景/步骤/JSON stepData）、`ConfigDefinition`（columns JSON）、`ConfigDataRow`（row_data JSON）、`AiChatMessage`、`AgentState`（resumeToken 为主键，messagesJson CLOB 存全量对话现场）。
- **持久化**：JPA + H2 文件模式 `jdbc:h2:file:./data/ai_config_db`（`application.yml:11`），`ddl-auto: update`。
- **API 路由清单（31 个端点）**：
  - `/api/ai`（AiController，11 个）：POST `/chat`、`/tool-result`、`/plan`、`/auto-prompt`、`/cancel`；GET `/tools`、`/scenarios`、`/history`、`/pending`、`/health`；DELETE `/history`
  - `/api/ai/proxy/**`（AiProxyController，1 个）：POST 通配透明 SSE 代理
  - `/api/configs`（ConfigController，8 个）：GET `/definitions`、`/definitions/{id}`、`/data/count`、`/data/page`、`/data/all`；POST `/definitions`、`/data/batch-save`、`/data/operations`
  - `/api/tasks`（TaskController，11 个）：GET ``、`/{id}`、`/meta/scenarios`；POST ``、`/select-scenario`、`/update-step`、`/update-selected`、`/goto-step`、`/{id}/complete`、`/{id}/changes`；DELETE `/{id}`
- **鉴权与安全**：**无任何认证/授权**（无 Spring Security、无 token、无用户体系）；API Key 仅存后端环境变量（`application.yml:36` `${DEEPSEEK_API_KEY:your-deepseek-api-key-here}`），代理层注入真实 key（`AiProxyController.java:100`），前端持 `'placeholder'`（`provider.js:32`）。CORS 全开放：`WebConfig.java:16-21` `allowedOriginPatterns("*") + allowCredentials(true)`；H2 console 开启且 `web-allow-others: true`（`application.yml:15-20`）。无注入风险点（JPA 参数化），但 `ConfigController` 无任何入参校验（无 `@Valid`）。
- **错误处理**：无 `@ControllerAdvice`/`@ExceptionHandler`（grep 全仓 0 命中）；控制器直接 `throw new RuntimeException(...)`（如 `ConfigController.java:33,78`），异常路径返回 Spring 默认 500 而非统一的 `R{code,msg}` 结构；service 层有兜底（`AiService.fallbackResponse`）。
- **日志**：Lombok `@Slf4j`，debug/info/warn/error 分级使用，`application.yml:60-63` 按包配置级别。
- **配置管理**：`@Value` 注入 + 单一 yml，超时/TTL/重试均可配（`application.yml:52-58`）。

**代码质量观察（好的 3 处）**：
1. `AiService.java:906-937` LLM 调用错误三分类重试：429/5xx/网络超时按 `1s×2^n + ±20% jitter` 退避、尊重 `Retry-After`，401/400 直接抛——生产级可靠性设计。
2. `AiService.java:377-391` 串行只执行第一个 tool_call 并把 assistant 消息裁剪为单个 tool_call_id，注释精确解释 OpenAI 协议 400 根因（"N 个 tool_calls 配 1 个 tool message 触发 insufficient tool messages"）。
3. `AiService.java:233-248` 工具结果 >2000 字符截断摘要化 + dataVersion 变更时在 tool result 前置系统提示强制 AI 现查——上下文治理与状态一致性双到位。

**代码质量观察（坏的 3 处）**：
1. `AiService.java:912-933` 重试的 `Thread.sleep(backoffMs)` 发生在 `@Transactional` 调用链内（`chat()`/`submitToolResult()` 均事务性），退避期间占用 DB 连接与事务，且同步阻塞请求线程。
2. `PlannerService.java:70-79` RestClient 未设任何超时（对比 `AiService.java:106-114` 有 180s），且 `callDeepSeekApi` 与 AiService 重复实现约 40 行。
3. `pom.xml:58-62` 声明完整 `spring-ai-starter-model-openai` starter，但代码仅 `FrontendTools.java:6` 引用其 `OpenAiApi.FunctionTool` 类型，实际 AI 调用全走手写 RestClient——重依赖轻使用（自动装配的 ChatModel 全部闲置）。

## 4. 前端实现

- **框架/路由/状态**：Vue 3 Composition API；9 条路由（`/dashboard` + 8 个 `/wizard/*`，`ai-ui/src/router/index.js:4-63`），`beforeEach` 守卫无任务回 dashboard（:79-88）；Pinia 3 store（ai/task/config）；axios 实例统一拦截 `R.code`（`api/request.js:12-31`）。UI 库 Element Plus + SpreadJS。
- **视图**：DashboardView、StepSelectScenario/SelectDefs/ViewDefs/QueryCond/Precheck/Review/Publish/Result。
- **范式2 差异**：agent loop 前移——`ai-ui-vercel/src/stores/ai.js:126-136` `streamText({tools, stopWhen: stepCountIs(10), maxRetries: 3, abortSignal})`；暂停-恢复在工具 execute 的 Promise 内（`tools/runtime.js:67-108` waitForConfirm/waitForForm + 10 分钟 TTL 过期定时器）；数据 localStorage + `mock/configDefs.js`，后端仅透明代理。

**质量观察（好的 2 处）**：
1. `ai-ui/src/stores/ai.js:394-408` `stopRun` 三层协作式停止：AbortController 断在途请求 + flow-cancel 标志在步骤边界停 + 后端 `/cancel` 作废 WAITING_TOOL，语义对齐“轮次边界停止、保留现场不回滚”。
2. `frontend-tool-registry.js` 13 工具统一 `validate → needConfirm(args) → execute` 分层，且有防御（:89-91 未选场景禁止跳后续步骤，与后端 `TaskService.java:88-91` 双侧同防）。

**质量观察（坏的 3 处）**：
1. **全局变量耦合**：`SpreadSheet.vue:32-42` 注册 `window.__spreadsheets`、`App.vue:182-183` 挂 `window.__triggerAiAutoPrompt`/`__collectWorkspaceState`，工具注册表经 `frontend-tool-registry.js:217-224` 读取——组件间隐式契约，无类型/存在性保证。
2. **双份复制代码**：`excel-io.js` 430 行在两范式间 diff 仅 4 行（几乎逐字复制），`SpreadSheet.vue` diff 18 行；文档自述“业务逻辑可原样复制”（`README.md:312`）——无共享包机制，`ed0b7c2` 提交被迫做“两范式对称修复”即为佐证。
3. `AiPanel.vue` 678 行单文件组件（模板+工具卡片三分支+Plan 卡片+交互逻辑一体）；`stores/ai.js:342` `catch (e) { /* ignore */ }` 静默吞掉 loadHistory 错误。

## 5. 测试

- **测试文件数：0**。后端无 `src/test` 目录（find `test*` 目录 0 命中），尽管 `pom.xml:87-89` 已引入 `spring-boot-starter-test`；两前端无 vitest/jest/playwright 依赖，package.json 无 test 脚本。
- **CI：无**（无 `.github/`、Jenkinsfile、.gitlab-ci.yml）。
- 质量验证依赖文档记录的手工浏览器自动化验证（`design-and-implementation.md:588-619` 列了 2026-08-22/23 两批端到端 PASS 记录），但验证脚本不入库、不可复现。

## 6. 脚本与部署

- `scripts/` 仅 `githooks/pre-commit`（30 行）：扫暂存区新增行的 `sk-|ghp_|gho_|AKIA|AIza|xox|eyJ` 模式，占位符白名单，命中阻断提交。设计合理但需手动 `git config core.hooksPath scripts/githooks` 才生效（README:121-129 已说明）。
- **无 Dockerfile、无 docker-compose、无 `.env.example`**（`.env.local` 被 .gitignore 排除且无模板；SpreadJS 的 `VITE_SPREADJS_KEY` 只在 README 325 行提及）。
- **README 启动步骤可信**：`mvn clean package -DskipTests -s maven-settings.xml` + `java -jar target/ai-service.jar`（finalName 一致，`pom.xml:93`）；端口 8081/5173/5174 三方对齐（`application.yml:2`、两份 `vite.config.js`）；`maven-settings.xml` 阿里云镜像 + Spring 直连源排除（:14 `mirrorOf:*,!spring-release,...`）是针对 Spring AI 1.1.8 同步滞后的实际解法。前置条件：自备 `DEEPSEEK_API_KEY`。

## 7. Git 历史

- **11 个提交**，单作者 `<USER>`，全部在 main，时间跨度 **2026-08-22 21:55 → 2026-09-05 02:06**（约 13 天，活跃集中在 8/22-23 与 9/4-05 两个窗口）。
- **粒度大**：首提交 `895f3c5` 即 65 文件 9,726 行完整模块；最大单提交 `6f6da9f` 49 文件 7,430 行；后期逐渐变细（`38dfe75` 仅 31 行 hook）。无小步渐进。
- **风格**：Conventional Commits（feat/fix/docs/security + scope）+ 中文主题；HEAD 提交 `198604f` 消息含 A-H 八项改造的完整要点清单（27 文件 +1,333/-172），消息质量高。
- 类型分布：feat 4、docs 5、fix 1、security 1——docs 占近半，与仓内 5,455 行文档量一致。

## 8. 可运行性判断（静态）

**判断：可构建、可运行（未实际执行验证）。依据**：
- 依赖声明完整：pom parent/依赖齐全且与代码 import 对应；两前端 `yarn.lock` 存在，SpreadJS 五个包、excelio 均在 dependencies（`ai-ui/package.json:18-22`）。
- 无缺失 import：抽查后端 6 个 service 与前端 registry/runtime 的模块引用均可解析到仓内文件或声明依赖。
- 无 TODO/FIXME 残留（grep 0 命中）；唯一“未实现”字样是 backend 工具 default 分支的合理兜底（`AiService.java:559`）。
- 无硬编码密钥：`sk-[A-Za-z0-9]{10,}` 全仓 grep 0 命中；API key 走环境变量 + 占位符默认值。
- 风险点：① Spring AI 1.1.8 需从 spring-release 源解析（settings 已处理，但属非常规仓库依赖）；② H2 JSON 列类型 + `MODE=MySQL` 组合可用但少见；③ 无 SpreadJS LicenseKey 时评估模式水印（README 已声明）；④ `deepseek-v4-flash` 模型名依赖外部服务有效性；⑤ Spring AI 自动装配会用占位 key 初始化 ChatModel bean（不影响手写 RestClient 路径，启动不报错）。

## 9. 总评

### 五条最大亮点
1. **双范式同构架构对照**：同一业务两套 AI Runtime（后端 Agent Loop vs 前端 Vercel AI SDK）并存且改造 A-H 两边同步落地（`198604f` 涉及两前端各 ~10 文件），是少见的范式对比教学样本。
2. **声明式工具系统 + Progressive Disclosure**：`tools.yaml`（358 行/13 工具含 JSON Schema 与信任分级）+ `scenarios.yaml` 状态机 + `ToolDiscoveryService`（:149-194 按 scenario+step 过滤），AI 每轮只看到当前步骤工具。
3. **暂停-恢复机制的工程完备度**：resumeToken/AgentState 状态机 + 10 分钟 TTL 过期拒绝回灌（`AiService.java:186-191`）+ 刷新恢复 `/pending`（:478-510）+ dataVersion 变更感知强制现查（:242-248）+ 协作式取消（:287-302）——覆盖 LangGraph/AG-UI 对照的中断恢复全场景。
4. **LLM 调用生产级健壮性**：错误三分类重试 + 指数退避 + Retry-After（:906-937）、180s 超时、结果 2000 字符/50 条截断、历史窗口 20 轮——上下文治理系统化。
5. **文档与复盘密度罕见**：12 条经验沉淀（`design-and-implementation.md:549-564`）每条带根因与修复位置；7 痛点 → 行业对标 → 方案评审 → 落地的完整链路（`.trae/documents/conversation-lifecycle-and-state-sync-plan.md` v1.1）；pre-commit 密钥扫描 + 密钥泄露补救流程沉淀（`memory/project-memory.md`）体现安全意识。

### 五条最大问题
1. **零测试、零 CI**：`pom.xml:87-89` 引入 starter-test 却无 `src/test`；前端无测试框架与脚本；无任何 CI 配置——质量全靠不可复现的手工浏览器验证（`design-and-implementation.md:588-619`）。
2. **无任何鉴权与安全边界**：31 个端点全部匿名可用；CORS `allowedOriginPatterns("*")+allowCredentials(true)`（`WebConfig.java:16-21`）；H2 console 开启且 `web-allow-others: true`（`application.yml:15-20`）；`/api/ai/proxy/**` 无来源校验，部署即可被任意第三方白嫖 LLM 调用消耗真实 key。
3. **无全局异常处理**：控制器直抛 `RuntimeException`（`ConfigController.java:33,78` 等），无 `@ControllerAdvice`，错误响应脱离统一 `R` 结构，前端拿到 Spring 默认 500 体。
4. **文档与代码漂移**：AgentState 24h vs 实际 10 分钟（`design-and-implementation.md:159,213` vs `application.yml:58`）、任务接口路径与 `TaskController` 实际路由不符（`design-and-implementation.md:513`）、`AgentState.java:31,35` 注释与实现矛盾——文档可信度打折扣。
5. **双前端复制式维护**：`excel-io.js` 430 行两份 diff 仅 4 行、SpreadSheet/SchemaForm 等组件复制（README:312 自述“可原样复制”），无共享包/monorepo 机制，已有提交被迫“两范式对称修复”（`ed0b7c2`）；另 `docs/` 3,920 行中 2,853 行（73%）为对话归档而非工程文档，`quick-suggestions-ui-patterns.md` 为已写文档未实现的纯设计。
