> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-10` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：19954 B / 13092 字符 / 207 行；行指纹覆盖率（仓内 + 落盘语料）6.2%
> - 类别：**正式独立报告**
> - 脱敏：路径 3 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# <LOCAL_WORK>\ai-example-kimi-1 事实性勘察报告

**评审范围声明**：本报告全部证据取自 `<LOCAL_WORK>\ai-example-kimi-1` 目录内部（含 `.git` 元数据），未读取任何外部文件。

---

## 1. 项目概览

**定位**：配置快速实施平台（动态配置项导出/导入向导）+ 常驻 AI 对话栏（工具调用 Agent）。

| 维度 | 事实 | 证据 |
|---|---|---|
| 后端 | Spring Boot **3.5.14**，JDK **21**，Spring AI BOM **1.1.8**（`spring-ai-starter-model-openai`，OpenAI 兼容协议对接 DeepSeek `deepseek-flash`），web/data-jpa/validation，H2（文件模式 `MODE=MySQL`），Lombok；Maven 构建 | `backend/pom.xml:10,21,29,51,54` |
| 前端 | Vue **3.5.13** + Vite **6.0.7** + Pinia 2.3.0 + Vue Router 4.5.0 + Element Plus 2.9.3 + GrapeCity SpreadJS **17.1.5**（3 个包锁定版本）+ jszip/axios/dayjs/uuid；Tailwind 3.4.17；Yarn | `frontend/package.json:12-31` |
| 数据库 | 嵌入式 H2 文件库 `./data/quickstart_db`，`ddl-auto: update` | `application.yml:8,14` |
| 端口 | 后端 8090（固定）、前端 5271（`strictPort: true`） | `application.yml:2`、`vite.config.js:13-20` |

**代码量统计**（wc -l）：

| 部分 | 规模 |
|---|---|
| 后端 Java | **62 文件 / 3,921 行**（ai 6 文件 1132 行、service 10 文件 1616 行、entity 7 文件 323 行、dto 22 文件 225 行、controller 4 文件 227 行、config 4 文件 153 行、repository 7 文件 108 行、`org.springframework.ai` 补丁类 1 文件 123 行） |
| 后端配置 | `application.yml` 36 行、`pom.xml` 73 行 |
| 前端 src | **19 文件 / 2,689 行**（views 3 个 1020 行、components 4 个 517 行、stores 2 个 399 行、utils 3 个 546 行、api 2 个 113 行）+ `scripts/build.mjs` 14 行 |
| 文档 | README 44 行 + docs/ 5 个文件 **620 行** |
| 测试 | **0 行**（无任何测试代码） |

**目录树（2 层）**：
```
ai-example-kimi-1/
├── README.md / .gitignore
├── backend/          pom.xml；src/main/java/com/example/quickstart/{ai,config,controller,dto,entity,repository,service}；org/springframework/ai/openai（补丁类）；src/main/resources
├── docs/             01-requirements ~ 05-verification 共 5 个 md
└── frontend/         package.json、vite/tailwind/postcss 配置、scripts/build.mjs、src/{api,stores,router,views,components,utils}、yarn.lock
```
注意：根目录**无 `scripts/`**、无 Dockerfile/docker-compose/.env.example/CI 配置（已逐一验证不存在）。工作区存在未跟踪的构建/运行产物：`backend/target/`（含 69MB jar）、`frontend/dist/`、`backend/backend-run.log`、`frontend/frontend-run.log`、`backend/data/quickstart_db.mv.db`。

---

## 2. 文档清单与评价

| 文件 | 行数 | 内容类型 | 完整度 |
|---|---|---|---|
| `README.md` | 44 | 项目简介+快速开始+文档索引 | 完整、与代码一致 |
| `docs/01-requirements.md` | 89 | 需求规格（领域模型、FR-1~FR-5、非功能） | 完整，边界明确（如 89 行“单机单用户演示规模，不做多用户权限体系”） |
| `docs/02-architecture.md` | 152 | 架构设计：9 项决策（D1~D9）含权衡、风险对策表 | **质量最高**，决策均有理由和代价说明 |
| `docs/03-api-contract.md` | 164 | REST/SSE/AI 工具契约（自称“前后端联调唯一事实来源”） | 完整，22 个端点 + 16 个工具全列出 |
| `docs/04-test-cases.md` | 53 | 测试用例（A/B/C/D 四组 29 条） | 完整但仅是计划 |
| `docs/05-verification.md` | 118 | 验证结果（12+7+7+3 全通过 + 第二轮回归 16 项）+ 排坑记录 | 完整，含已知限制 5 条 |

**关键片段摘录**（展示文档质量）：

`docs/02-architecture.md:30`（决策带权衡）：
> **能用 Spring AI 现成能力的绝不自造**：`OpenAiChatModel.stream(Prompt)` 做流式调用、`ToolCallback`/`ToolDefinition` 定义工具、`MessageWindowChatMemory`+`InMemoryChatMemoryRepository` 管对话历史……自研的只有一层薄编排：按轮驱动 Loop、BACKEND/FRONTEND 工具分流、FRONTEND 工具暂停-恢复

`docs/02-architecture.md:34`（实测踩坑记录）：
> **`deepseek-flash` 是思考模式模型：回填 assistant 消息必须携带本轮 `reasoning_content`，缺失会 400**……⚠ 这与 deepseek-chat/deepseek-reasoner 的旧规则（不可回传 reasoning_content）**相反**，是实测踩坑后修正的结论

`docs/05-verification.md:64-70`（诚实记录已知限制）：
> 1. SpreadJS 评估版水印…… 2. 导出第 3 步刷新后历史作业结果视图需重新导出…… 5. 上传匹配……未做真实文件上传 E2E

**含糊/缺失/自相矛盾之处**（有据）：
1. **过时陈述**：`docs/05-verification.md:62` “Key 仅存在于后端 application.yml（环境变量优先，**内置演示默认值**）”——与提交 `c0a9eab`（移除默认值）及现行 `application.yml:20`（`${DEEPSEEK_API_KEY:}` 空默认）矛盾；README 已同步修改但此文件未改。
2. **端口“顺延”未实现**：`docs/03-api-contract.md:163-164` 称“后端 8090（启动前检测占用，占用则顺延 8091…）；前端 5271（vite strictPort，占用则顺延）”——代码中后端端口固定（`application.yml:2`），前端 `strictPort: true`（`vite.config.js:14`）恰恰是**占用即失败不顺延**；“顺延”仅是验证时的手工动作（`05-verification.md:60`）。
3. **缺部署/运维文档**：无生产部署、无配置项清单文档、无性能容量预期（仅“单机单用户演示”一句）。
4. “支持更多任务类型扩展”（`01-requirements.md:10`）为声明性需求，代码中 `TaskService.create:37-39` 硬编码仅 EXPORT/IMPORT，扩展点未实际体现（除文档字样外无插槽机制）。

---

## 3. 后端实现

- **入口**：`backend/src/main/java/com/example/quickstart/QuickstartApplication.java`（`@SpringBootApplication` + `@EnableScheduling`）。
- **API 路由全清单**（22 端点）：
  - `ConfigController`：`GET /api/config-defs`、`GET /api/config-defs/{code}`、`POST /api/config-defs/query`、`GET /api/config-defs/{code}/template-info`
  - `TaskController`：`POST /api/tasks`、`GET /api/tasks`、`GET /api/tasks/{id}`、`PUT /api/tasks/{id}/step-data`、`POST /api/tasks/{id}/cancel`、`DELETE /api/tasks/{id}`
  - `JobController`：`POST /api/tasks/{id}/jobs/{export|check|import|publish}`、`GET /api/jobs/{jobId}`、`POST /api/jobs/{jobId}/cancel`、`GET /api/jobs/{jobId}/export-results`、`GET /api/tasks/{id}/staging`
  - `AiChatController`：`POST /api/ai/chat`（SSE）、`POST /api/ai/tool-result`（SSE）、`GET /api/ai/history`、`POST /api/ai/cancel`
- **数据模型**（7 个 JPA 实体）：`Task(qs_task)`、`JobRun(job_run, taskId 索引)`、`JobItem`、`ConfigDefinition`、`ConfigData`、`StagingConfigData`、`ExportResult`；元数据字段以 JSON 存 `@Lob`。持久化全部为 Spring Data 派生查询（无手写 SQL）。
- **鉴权与安全**：**无任何认证/授权**（按需求设计，`01-requirements.md:89`）；无用户/密码/token；API Key 仅环境变量注入 `application.yml:20`；无原始 SQL 拼接（注入面小）；但 `CorsConfig.java:12-14` 对所有来源放开 `allowedOriginPatterns("*")`。
- **错误处理**：`GlobalExceptionHandler.java` 覆盖 NotFoundException→404、IllegalArgument→400、IllegalState→409、参数校验→400、兜底 500（`error` 字段）；AI 链路错误经 SSE `error` 事件下发（`AiChatService.java:332-334`）。
- **日志**：SLF4J，级别 info；含工具执行日志 `BackendToolExecutor.java:37`、会话取消/重试/清理日志。
- **配置管理**：`@Value` 注入（max-rounds=8、session-ttl=120min、item-delay-ms=300）；两套专用线程池（`AsyncConfig.java`）。

**代码质量观察**：

✅ **好 1**：ChatMemory 窗口截断会拆散 `assistant(tool_calls)/tool` 配对，`AiChatService.java:454-481` 的 `sanitizedHistory` 专门做了协议完整性保护（丢弃头部孤立 tool 消息与应答不完整的 assistant 消息）——这是很多 Agent 实现会漏的 400 坑。

✅ **好 2**：异步作业在事务提交后触发，修复了经典竞态，且注释写明原因（`JobService.java:142-149`）：
```java
// 事务提交后再触发异步执行，否则执行线程读不到未提交的作业行
Long jobId = job.getId();
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        jobExecutor.submit(jobId);
    }
});
```

✅ **好 3**：流式调用自实现指数退避重试且“已产出内容不重试”，并处理 usage 末帧 choices 为空（`AiChatService.java:352-412`，其中 361-364 判空、397-409 重试）。

✅ **好 4**：框架补丁类克制且有退出策略——`org.springframework.ai.openai.ReasoningAwareOpenAiChatModel.java:25-34` Javadoc 明确“升级 Spring AI 版本后应评审官方是否已修复并移除本补丁”，覆盖点仅 `createRequest` 一处（71-75 行传播 `reasoningContent`）。

❌ **坏 1**：任务编号生成用 `count+1`，并发下可产生重复 taskNo（虽有唯一约束兜底但会抛异常）（`TaskService.java:113-118`）：
```java
private String nextTaskNo(String type) {
    ...
    long seq = taskRepository.countByTaskNoStartingWith(prefix) + 1;
    return prefix + String.format("%04d", seq);
}
```

❌ **坏 2**：`RoutingToolCallback.java:57` 用字符串拼接把异常消息塞进 JSON，消息含引号时会产出**非法 JSON**（`"{\"success\":false,\"error\":\"工具执行失败: " + e.getMessage() + "\"}"`）。

❌ **坏 3**：`synchronized` 方法级锁 + `@Transactional` 混用做幂等（`JobService.java:57,81,105`），仅在单实例下有效，且锁粒度粗（所有任务的创建互斥）。

❌ **坏 4**：查询为全量加载后 Java 内存过滤（`ConfigDefService.java:64-67` + `ConditionFilter.java:11` 注释自认“数据量小”），每次查询拉全表；`JobExecutor.run:143-161` 每个 item 多次逐条 save（进度写放大）。

❌ **坏 5**：`AiSessionStore.AiSession` 字段全部 public 可变（`AiSessionStore.java:36-43`），加锁纪律散落在调用方 `synchronized(session)`，易漏。

---

## 4. 前端实现

- **框架**：Vue 3 `<script setup>` + Pinia（2 个 store）+ Vue Router（3 条路由：`/tasks`、`/export/:taskId`、`/import/:taskId`，懒加载，`router/index.js:3-8`）。
- **页面/视图**：`TaskListView.vue`（任务列表）、`ExportWizardView.vue`（3 步向导，el-steps+el-tabs）、`ImportWizardView.vue`（4 步向导）；组件 `AiPanel.vue`（AI 对话栏）、`SpreadSheet.vue`（SpreadJS 封装）、`DefSelector.vue`、`JobProgress.vue`。
- **状态管理**：Pinia。`stores/workspace.js` 是两侧**唯一接口层**（`buildContext()/snapshot()/registerPageHandler()`，21-20 行注释定义契约）；`stores/ai.js`（303 行）管 SSE 会话/镜像恢复。
- **API 调用**：axios 实例统一错误拦截（`api/index.js:9-16`）；SSE 用 fetch+ReadableStream 手写 POST SSE 解析器（`api/sse.js`，因 EventSource 不支持 POST）。
- **UI 组件库**：Element Plus（中文 locale）+ Tailwind 工具类（`preflight: false` 防冲突，`tailwind.config.js:5-7`）。

**质量观察**：

✅ **好 1**：解耦契约清晰——`workspace.js:86-96` 页面 handler 注册表 + `App.vue:28` `AI_PANEL_ENABLED` 总开关 + `defineAsyncComponent` 懒加载（`App.vue:31`），与文档 D3/G3 声明一致，实测 grep 业务视图确不 import ai store。

✅ **好 2**：刷新恢复双保险（`ai.js:248-301`）：先 sessionStorage 镜像即时回放，再与 `/api/ai/history` 对账；后端为空时把挂起工具降级为 `expired` 防误点（265-270 行）。

✅ **好 3**：SpreadJS 封装处理了隐藏容器塌陷（ResizeObserver 防抖 refresh，`SpreadSheet.vue:30-37`）并在卸载时 dispose（39-49 行）。

✅ **好 4**：`frontend-tools.js:12-23` 前端工具可用性表与后端注册表镜像（第三道防线），37-38 行执行前兜底校验。

❌ **坏 1**：`ai.js:55` 模块级 `autoExecutedCallIds` Set 只增不清，长会话下缓慢膨胀；且换 sessionId 后旧集合仍残留。

❌ **坏 2**：`frontend-tools.js:51-56` navigate_to 用 100ms 轮询忙等页面就绪（≤3s），是文档承认的竞态修复，但属脆弱的时序补丁。

❌ **坏 3**：`sse.js:44` 有 `// eslint-disable-next-line no-constant-condition`，但项目**无任何 eslint 配置文件**（已验证不存在）——死注释，且前端无 lint 脚本。

❌ **坏 4**：构建主 chunk 巨大：`frontend/dist/assets/index-wOjfd_uE.js` **7.64MB**（SpreadJS+Element Plus 基本全量打入；视图虽有懒加载分包，AiPanel 独立 chunk 7KB）。

❌ **坏 5**：任务列表无分页/过滤（`TaskListView.vue:102-109` 全量拉取），与后端 `list()` 无分页一致。

---

## 5. 测试

- **单元/集成/E2E 测试代码：0 个文件、0 个用例**。`backend/src` 下无 `test` 目录；`pom.xml` 无 `spring-boot-starter-test` 依赖；前端无测试框架（package.json 无 vitest/jest/cypress）。
- **无 CI 配置**（无 `.github/`、`.gitlab-ci.yml` 等，已验证）。
- 替代物：`docs/04-test-cases.md` 定义 29 条手工用例（A 组 12 条 curl、B 组 7 条 SSE、C 组 7 条浏览器、D 组 3 条非功能）；`docs/05-verification.md` 记录全部通过 + 第二轮回归 E1-E7/F1-F3/G1-G3/H1-H3（16 条）；`backend/target/test-payloads/` 留存 **47 个**手工 curl 验证 payload（未跟踪）。
- 不可自动化运行：测试不存在的意义上“不可运行”；可验证性完全依赖手工重放。

---

## 6. 脚本与部署

- 根目录**无 `scripts/`**；唯一脚本是 `frontend/scripts/build.mjs`（14 行，把 TMP/TEMP 指向项目内 `.tmp-build` 以绕过杀毒软件锁定 esbuild 临时文件，有注释说明）。
- **无 Dockerfile、无 docker-compose、无 .env.example**（前端/后端均无，已验证）。密钥说明靠 README 环境变量段落。
- **README 启动步骤可信**：`mvn package -DskipTests` + `java -jar target/quickstart-backend-1.0.0.jar`（jar 确实存在，69,021,931 字节，2026-10-05 18:37）；`yarn install && yarn dev`（yarn.lock 存在，Vite 6.4.3 ready 日志在 `frontend/frontend-run.log`）。端口/环境变量要求与代码一致；唯一前置是 **`DEEPSEEK_API_KEY` 必须显式提供**（否则 AI 调用不可用，README:33 已标注“必须”）。
- 佐证真实运行：`backend/backend-run.log`（56 行）记录 18:44–19:15 间后端在线并实际执行了 `list_config_defs/get_task/get_config_def` 等后端工具（真实 DeepSeek 调用）。

---

## 7. Git 历史

- **10 个提交**，全部为同一作者 `kimi <kimi@local>`；分支 `main` 与远端 `origin/kimi-k3` 完全一致（remote：`<REPO-URL>`）。
- **时间跨度**：2026-10-05 14:17 → 19:20，约 **5 小时**内完成（含文档、实现、两轮重构与安全修复）。
- **风格**：规范化前缀（`chore:`/`docs:`/`feat:`/`fix:`/`refactor:`/`security:`），中文详细描述（如 `50aa2b7` 描述了 4 项改动内容）。
- **粒度**：先文档（4606bed +448 行）→ 一个巨型 feat（`175dcb5`：84 文件 +7,573 行）→ 小步修复（+20/+25 行级）→ 大重构（`50aa2b7`：24 文件 +1306/-567）→ 安全修复。前期粒度粗（一次性倾倒），后期收敛为提交即变更点，可追溯性尚可。

---

## 8. 可运行性静态判断

**判断：可构建、可运行，且已有本机构建/运行证据；但需环境变量 `DEEPSEEK_API_KEY`。**

依据：
1. 依赖声明完整：Maven 侧 6 个依赖 + BOM（`pom.xml`），前端有 `yarn.lock` 锁定；未发现指向不存在模块的 import（`OpenAiConfig.java:9` 引用的 `org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties` 属 starter 自带）。
2. 构建产物存在：`backend/target/quickstart-backend-1.0.0.jar`（69MB，18:37）、`frontend/dist/`（18:15，含懒加载 chunk）——两轮构建均成功（与 `05-verification.md:88,96` D2/E7 记录吻合）。
3. 运行日志证明曾成功启动并完成真实 AI 工具调用（`backend-run.log`）。
4. **无 TODO/FIXME/stub**（全库 grep 为 0）；无死代码；唯一“占位”是 `RoutingToolCallback` 对 FRONTEND 工具的防御性错误返回（有注释说明，非未完成功能）。
5. **密钥处理**：git 历史干净——曾提交的默认值是字面占位符 `REMOVED_SECRET`（`git show c0a9eab` diff 可见），全历史 grep 无真实 `sk-` key。**但**：本地构建产物 `backend/target/classes/application.yml` 与 jar 内 `BOOT-INF/classes/application.yml`（均为 18:37 构建，早于 19:16 修复提交）内嵌了真实形态的 DeepSeek Key（`sk-5d2f****`，本报告已打码）。该文件未被 git 跟踪（`target/` 已 ignore），但 jar 一旦分发即泄漏。

---

## 9. 总评：5 条最大亮点 + 5 条最大问题

**亮点**
1. **AI Agent Loop 工程完整度高**：受控工具循环 + BACKEND/FRONTEND 分流 + 暂停恢复 + 会话历史协议保护（`AiChatService.java:209-342,454-481`），幻觉工具/未应答调用均有防 400 兜底（286-292 行、`AiChatService.java:161-164`）。
2. **文档与代码互证程度罕见地高**：`docs/02-architecture.md` 的 9 项决策每项都能在代码中找到对应实现（afterCommit→`JobService.java:144-149`、补丁类→`ReasoningAwareOpenAiChatModel`、三道防线→`AiToolRegistry/PromptBuilder/frontend-tools.js`），且记录了实测踩坑与修复（`05-verification.md:36-54`）。
3. **渐进式披露三道防线真实落地**：注册表按 page+step 过滤（`AiToolRegistry.java:122-128`）、system prompt 强约束（`PromptBuilder.java:49-55`）、前端执行器兜底（`frontend-tools.js:12-23,37-38`）。
4. **验证留痕充分**：29 条用例 + 16 条回归全记录结果与证据摘要，47 个手工 payload 留存于 `backend/target/test-payloads/`，运行日志可交叉印证。
5. **安全响应及时且历史干净**：发现内置默认 Key 后 4 分钟内移除（19:16 `c0a9eab`）并同步 README（19:20 `cf5b8a5`），git 全历史无真实密钥。

**问题**
1. **零自动化测试、零 CI**：无任何单测/集成/E2E 代码（`backend/src` 无 test 目录、`pom.xml` 无 test 依赖、前端无测试框架），质量完全依赖手工验证，重构（如 `50aa2b7`）无回归网。
2. **真实 API Key 残留在本地构建产物中**：`backend/target/classes/application.yml` 与 `quickstart-backend-1.0.0.jar` 内含 `sk-5d2f****`（18:37 构建，早于修复提交）；未跟踪但存在随 jar 分发泄漏的风险，且修复提交后未重新构建清理。
3. **单机假设渗透进代码且无护栏**：taskNo 生成竞态（`TaskService.java:116-117`）、方法级 `synchronized` 幂等（`JobService.java:57,81,105`）、内存态会话/取消标志（`AiSessionStore.java`）、CORS 全放开（`CorsConfig.java:12-14`）——文档已声明“单机演示”，但代码无任何多实例防御或警告。
4. **文档两处与代码失同步**：`docs/05-verification.md:62`（“内置演示默认值”已过时）、`docs/03-api-contract.md:163-164`（端口“顺延”与 `vite.config.js:14` `strictPort:true`、`application.yml:2` 固定端口矛盾）——“唯一事实来源”承诺打了折扣。
5. **性能/健壮性取舍无上限保护**：查询全量内存过滤（`ConfigDefService.java:64-67`，size 上限 500 但过滤前全表加载）、前端主 chunk 7.64MB（`frontend/dist/assets/index-wOjfd_uE.js`）、`RoutingToolCallback.java:57` 异常消息拼接可产出非法 JSON、`ai.js:55` 只增不减的 Set。
