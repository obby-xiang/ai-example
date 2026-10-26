> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-13` 的 wire 末条 assistant 文本（消息序号 m=38；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 20:00 → 2026-10-05 20:08（wire 事件时间窗，UTC+8）；当时模型：`Zhipu AI/glm-5.3-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：16426 B / 11253 字符 / 149 行；行指纹覆盖率（本批复算）18.0%（最高单文件 14%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/023-ai-example-kimi-2-代码仓事实性勘察报告-39187be9-agent-11.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：与 `agent-12`（ai-example-deepseek-v4-pro 仓）、`agent-14`（起源仓）为**不同仓**的变体评分勘察，非续版；与 wire-only 022/023（kimi-1 / kimi-2）同属五分支评测组
> - 脱敏：路径 1 / 会话标识 0 / 身份 1 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
勘察完成。以下为证据报告。

# 代码仓变体勘察报告：ai-example-claude-opus-5.5

## 1. 项目概览

**定位**：AI 辅助配置管理全栈演示系统——左侧业务向导（导出/导入配置）+ 右侧常驻 AI 对话栏（后端 Agent Runtime + 渐进式工具披露 + HITL 人在回路）。

**技术栈**（README.md:44-55、backend/pom.xml、frontend/package.json）：

| 层 | 技术 | 版本证据 |
|---|---|---|
| 后端 | Spring Boot / JDK 21 / Spring AI / H2 文件库 / Flyway / POI / Caffeine | pom.xml:10（3.5.14）、:21（java 21）、:22（spring-ai 1.1.8）、:23（poi 5.4.1） |
| 前端 | Vue 3 / Vite / Pinia / Element Plus / Tailwind 4 / SpreadJS / axios | package.json:14-19（spread-sheets 17.1.5、element-plus ^2.9.3）、:26-29（vite ^7.3.6、tailwindcss 4.3.3） |
| 包管理 | Maven（含 maven-settings.xml 阿里云镜像）/ yarn 1.22 | backend/maven-settings.xml、frontend/yarn.lock |

**代码量统计**（wc -l）：

| 部分 | 文件数 | 行数 |
|---|---|---|
| 后端 Java（main） | 77 | **5,763** |
| 后端资源（application.yml 81 + V1 schema 165 + V2 4） | 3 | 250 |
| 前端 src（.vue 2,672 / .js 646 / .scss 36 / .css 1） | 33 | **3,355** |
| docs/*.md（5 个） | 5 | 1,230 |
| README.md | 1 | 134 |
| **测试代码** | **0** | **0** |

后端分模块行数：ai 1,549（含 runtime 312/ToolRegistry 118/HitlManager 119/4 个 tools 类 368）、job 1,376（7 个 runner/service）、task 925、data 475、definition 410、excel 389、seed 247、其余 379。前端最大文件：DefinitionsView.vue 358、stores/ai.js 271、StepQueryCond.vue 245、TaskListView.vue 243。

**目录树（2-3 层）**：`backend/{config,src/main/java/com/example/configmgr/{ai,common,config,data,definition,excel,file,job,masterdata,seed,task},src/main/resources/db/migration,src/test(空),data(运行时,gitignore),target}`；`frontend/{src/{api,components/{AiPanel,SpreadJSEditor},router,stores,styles,views/{export,import}},dist(已构建),.env.example}`；`docs/`、`scripts/githooks/`、`.scratch/(188 个文件,gitignore)`。git 追踪文件共 **130** 个。

## 2. 文档

| 文件 | 行数 | 类型 | 完整度 |
|---|---|---|---|
| README.md | 134 | 总览/启动 | 高：架构 ASCII 图、技术栈表、启动步骤、AI Key 注入说明、端口表 |
| docs/requirements.md | 137 | 需求规格 | 高：功能需求分节 + 状态机步骤表 + 非功能需求表 |
| docs/technical-design.md | 396 | 技术方案 | 很高：分层图、ER、状态机、Agent 伪代码、AG-UI 事件协议、设计决策表 |
| docs/implementation-plan.md | 263 | 实现/API 契约 | 高：REST 全清单、SSE 协议示例 JSON、前后端目录结构、种子数据表 |
| docs/test-cases.md | 210 | 测试计划 | 高：约 **104 个用例 ID**（单测 21/集成 18/端到端 14/边界 15/增量 36） |
| docs/verification-results.md | 224 | 验证记录 | 很高：分批次实测记录（20/20、12/12、7/7、10/10、11/11 通过）、**19 个缺陷的“现象/根因/修复”表**、5 条已知限制 |

质量摘录（verification-results.md:198，缺陷 #19，体现记录深度）：
> 19 | 工具调用后的下一轮对话 400 | ToolCallingManager.executeToolCalls 的 conversationHistory() 返回整段对话上下文…… | 只追加本次调用 id 对应的响应

**文档含糊/缺失/自相矛盾**（均有实据）：
- **README.md:13-14** 写“导出向导（4 步）/导入向导（5 步）”，实际导出 3 步（ExportWizardView.vue:49-53 `exportSteps` 三项）、导入 4 步（ImportWizardView 同构）。
- **requirements.md:137** “默认 500ms/批”，实际 application.yml:69 `demo-batch-delay-ms: 300`。
- **implementation-plan.md:250-263** 种子表列 **9** 个定义（含 HOLIDAY、PROJ_WAREHOUSE）、“PROJ_PRICE 约 3000 行”；实际 DataSeedRunner.java:69-187 只 seed **7** 个定义（无 HOLIDAY/PROJ_WAREHOUSE），行数为 300×4 项目=**1200**（verification-results.md:38 写 1200，与代码一致、与 plan 矛盾）。
- **technical-design.md:315-322** 路由含 `/tasks/create`、`/definitions/:code`，router/index.js:4-39 中**均不存在**。
- **implementation-plan.md:210-241** 前端目录含 MessageList.vue、JobProgressPanel.vue、ConfirmDialog.vue、EmptyState.vue、variables.scss，实际均不存在。
- README.md:76/83 写死本机 Maven 路径 `<DRIVE>\Program Files\JetBrains\IntelliJ IDEA\...`，可移植性差。
- verification-results.md 自述“vite dev 用 5174（5173 被占用）”，与 README/端口规划表（5173）不一致（属环境史实，非代码）。

## 3. 后端实现

- **入口**：ConfigMgrApplication.java:7-13（`@SpringBootApplication @EnableAsync`）。
- **框架**：Spring MVC + JPA/Hibernate，Flyway 迁移（V1__schema.sql 165 行、V2 加 base_version 列）。
- **API 路由：41 个端点**（grep 注解统计）——AiController 9（含 SSE `POST /sessions/{sid}/runs`、HITL `POST /interactions/{iid}`、调试 `GET /tools`）、TaskController 11（含 SSE `/events`、`PUT /{id}/step`）、JobController 6、FileController 6（upload/单文件/保存/download-all/download 子集/templates）、DefinitionController 5、DataController 2、MasterDataController 2。
- **数据模型**：12 张表（V1__schema.sql），实体含 `@Version` 乐观锁（Task.java:37-38、ConfigDataRow.java:34-35）。
- **持久化**：H2 文件模式 + Flyway + `ddl-auto: validate`（application.yml:16-38）；JSON 动态字段存 CLOB（data_json）。
- **鉴权/安全**：**无任何认证授权**（无 spring-security 依赖）；API Key 只经环境变量或未追踪 config 注入（application.yml:46-51）；CORS 限 localhost（WebConfig.java:18）；pre-commit 密钥扫描钩子（scripts/githooks/pre-commit:20-38，含 sk-/ghp_/AKIA 等 7 类模式 + 白名单）；H2 Console 开启且 `web-allow-others: true`、sa 空密码（application.yml:22-27）。
- **错误处理**：GlobalExceptionHandler.java:15-47 覆盖 5 类异常，统一 ApiResponse 信封。
- **日志**：lombok `@Slf4j` 广泛使用；application.yml:77-81 分级（业务 DEBUG）；AgentRuntime:110-119 留有消息序列 debug 日志。
- **配置**：AppProperties（prefix=app）+ application.yml；AI 参数 maxIterations=12、TTL=30min、HITL 超时 5min。

**质量观察（好）**：
1. 事务提交后再启动异步作业，避免竞态——JobService.java:45-50：
```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        asyncJobExecutor.execute(savedJob);
    }
});
```
2. 发布冲突检测用强制版本递增——PublishJobRunner.java:141-145：`entityManager.lock(liveRow, LockModeType.OPTIMISTIC_FORCE_INCREMENT)`，并注释解释“脏检查跳过 UPDATE 会使冲突检测失效”（141-144）；冲突判定 hasConflict（220-225）。
3. @Async 必须独立 Bean 的注释（AsyncJobExecutor.java:9-15）与 HITL“先推送再阻塞”时序注释（HitlManager.java:20-24）都是踩坑后沉淀的知识。
4. 文件名最长前缀匹配防 `PROJECT_PRICE` 被 `PROJECT` 抢先（FileController.java:277-294）；ZIP 中文文件名 UTF-8 失败回退 GBK（FileController.java:121-127）。

**质量观察（差）**：
1. **整个作业一个 @Transactional 且内含 Thread.sleep 演示延迟**——PublishJobRunner.java:60 + 153-154：取消（break at 166）后事务照常提交，**已发布的行不会回滚**，作业状态却是 CANCELLED，语义不自洽。
2. **死代码**——DependencyResolver.java:50-66 先构建 `inDegree`/`inDeg` 两个从未被读取的 Map（60-66 循环体为空），随后 69-78 又完整重建一套。
3. 重复 import——PublishJobRunner.java:18-19 连续两行 `import com.example.configmgr.task.entity.Task;`。
4. 健康检查返回错误数据——AiController.java:203：`"sessionCount", sessionStore.getClass().getSimpleName()`（把类名字符串当计数返回）。
5. 参数双写漂移风险——AgentRuntime.java:283-284 硬编码 `temperature(0.2).maxTokens(4096)`，与 application.yml:57-60 重复。
6. ToolRegistry.init（32-73）遍历所有 bean 并逐个 `getBean` 做注解扫描，启动期强制实例化全部 Bean，脆弱。

## 4. 前端实现

- **框架/组件**：Vue 3.5 `<script setup>` + Element Plus（全局注册 + zh-cn locale，main.js:16-22）+ Tailwind 4（`@tailwindcss/vite`）+ SpreadJS 17.1.5（懒加载 + LicenseKey 走 `VITE_SPREADJS_KEY`，SpreadJSEditor.vue:57-70）。
- **路由**：5 条（router/index.js:4-39）：`/tasks`、`/tasks/:id/export/:step`、`/tasks/:id/import/:step`、`/definitions`、`/data`；`afterEach` 驱动上下文上报（46-54）。
- **状态管理**：4 个 Pinia store——workspace.js（300ms 防抖上报上下文，28-31）、task.js、ai.js、definitions.js。
- **API 层**：axios 实例 `/api`（request.js:3-6）+ 5 个资源模块；AI 流另用 `fetch` + ReadableStream 解析 SSE。
- **视图**：TaskListView、ExportWizard(3 步)、ImportWizard(4 步)、DefinitionsView、DataBrowserView + AiPanel 5 组件。

**质量观察（好）**：
1. SSE 手工解析正确处理跨 chunk 断行——stores/ai.js:143-145 `buffer += decoder.decode(...); const lines = buffer.split('\n'); buffer = lines.pop()`。
2. “联动不耦合”架构落地：task.js:79-103 订阅 `/api/tasks/{id}/events`，`liveJobs` 供步骤组件“认领”AI 启动的作业（112-121），事件来源（用户/AI）透明。
3. 会话恢复防御历史脏数据——ai.js:16-20 主动清理 sessionStorage 中历史版本写入的 `"undefined"`；信封解包注释记录了缺陷 #17（55-57）。
4. UI_COMMAND 三协议分发清晰：navigate→router.push、download→`<a>` 触发、其余 window 事件广播（ai.js:249-265）。

**质量观察（差）**：
1. **死依赖**：package.json:18 声明 `dayjs`，全前端 0 处使用（grep 无命中）。
2. 未用 import：stores/ai.js:2 `import { ref, reactive }` 中 `reactive` 未使用。
3. 大量静默吞错：`catch (e) { /* ignore */ }`（ai.js:65、177、254、task.js:99、101 等），出错时用户无感知。
4. 消息 id 用 `Date.now()`（ai.js:76、81、97），同毫秒批量插入会撞 id（Vue key 冲突风险）。

## 5. 测试

- **自动化测试：0**。backend/src/test 目录存在但为空；pom.xml:98-102 引入了 `spring-boot-starter-test` 却无一用例；frontend/package.json 无 test 脚本、无 vitest/jest 依赖。
- **测试计划**：docs/test-cases.md 定义约 **104 个用例 ID**（U-DR/ER/FM/TR、I-EX/IM/AI、E-TC/EX/AI、B-01..15、N-*、TM-*），全部为文档/手工用例。
- **验证脚本**：位于 `.scratch/`（188 个文件，已 gitignore 不入库），含 api-tests-1.ps1~6.ps1、t8.ps1、craft.py（openpyxl 构造 Excel）等 26 个脚本，verification-results.md:214-224 声称批次实测 20/20、12/12、7/7、10/10、11/11 通过——**脚本不入库，第三方无法复现**。
- **CI**：无（无 .github/、.gitlab-ci.yml 等）。

## 6. 脚本与部署

- scripts/ 仅有 `githooks/pre-commit`（40 行密钥扫描，README:124 给出启用命令）。
- **无 Dockerfile、无 docker-compose**。有 `frontend/.env.example`（SpreadJS Key 占位）与 `backend/config/application.yml.example`（.gitignore:23-24 精确排除目录但保留 example）。
- README 启动步骤（78-95）：后端设 `DEEPSEEK_API_KEY` → `mvn clean package -s maven-settings.xml` → `java -jar target\config-mgr.jar`；前端 `yarn install && yarn dev`。**步骤基本可信**（backend/target/config-mgr.jar 88MB 已实际构建于 16:14；frontend/dist 已构建；backend/data/ 有运行痕迹：config_mgr_db.mv.db 983KB + files/ 数十个实测 xlsx），但 Maven 路径为本机私有路径（README:76），换机不可直接照搬。

## 7. Git 历史

- 共 **35 个提交**，单作者 `Claude Code <<USER_EMAIL>>`，时间跨度 **2026-10-05 12:43 → 16:16（约 3.5 小时，单日）**。
- 风格：conventional commits（feat/fix/docs/refactor 前缀），粒度细且末尾提交带“现象/根因/修复/验证”多行正文（如 a98d0b2）。开头三连发（12:43 同分钟 docs+backend+frontend），中段为联调修复串（05873c1、801cc3c、74357b1、3a069e6…）。
- 分支：当前 HEAD 在 `claude-opus-5.5+deepseek-v4-pro`（35 提交全量）；`claude-opus-5.5` 停在第 3 个提交 6cc9b3d（早期检查点，落后 32 提交）；两分支均有 origin 远端。工作区干净（git status 无未提交变更）。

## 8. 可运行性静态判断

**判断：可构建、可运行（无 API Key 时业务功能可用，AI 功能受限）。依据**：
- 依赖声明完整：后端 jar 已产出（target/config-mgr.jar，88MB），前端 dist 已产出、node_modules 在位——两端的构建在该机器上成功过。
- 无 TODO/FIXME/stub/未实现标记（全仓 grep 0 命中）；无写死密钥（api-key 走 `${DEEPSEEK_API_KEY:}` 空默认 + example 文件占位）；import 均可解析（jar 构建成功佐证）。
- 风险点：① 未配置 Key 时 AI 返回 401（已知限制 #4 自述）；② multipart 未配置上限（application.yml 无 multipart 段），Spring Boot 默认 1MB，>1MB 的 Excel/ZIP 上传会 413；③ 模型名 `deepseek-flash`（application.yml:58）与文档自洽，但属项目自称的新模型，无法在仓内核实其真实性；④ README 的 Maven 路径绑定本机。

## 9. 五大亮点 / 五大问题

**亮点**：
1. **AI Agent Runtime 完整落地**：自管循环 + 渐进式工具披露（ToolRegistry.java:80-90 按 `task:TYPE/STEP` 标签过滤，16 个 @Tool 全部带 @ToolScope/@ToolRisk）+ HITL 阻塞等待/超时拒绝闭环（HitlManager.java:66-87）+ 真流式 TEXT_DELTA/usage 统计（AgentRuntime.java:248-273）。
2. **并发与事务细节超出演示水准**：afterCommit 启动异步（JobService.java:45-50）、独立 @Async Bean 规避自调用（AsyncJobExecutor.java:12-15）、作业取消检查点 + 僵尸恢复（StaleJobRecoveryRunner.java）、`OPTIMISTIC_FORCE_INCREMENT` 冲突检测（PublishJobRunner.java:141-145）。
3. **文档体系罕见地全且诚实**：5 份文档 1,364 行，含 19 个缺陷的“根因/修复”记录与已知限制清单（verification-results.md:178-210），测试用例 104 个 ID。
4. **密钥卫生做足**：环境变量注入 + gitignore 精确保留 example（.gitignore:23-24）+ pre-commit 七类密钥扫描（scripts/githooks/pre-commit:11-23）+ 前端 .env.example。
5. **业务功能密度高**：41 个 REST 端点覆盖导出/预检/导入/发布全流程，含动态 schema、拓扑排序依赖解析（DependencyResolver）、条件求值器双端口径一致（ConditionEvaluator.java:9-11 注释）、SpreadJS 在线编辑、SSE 双流。

**问题**：
1. **零自动化测试 + 无 CI**：backend/src/test 空、pom 自带 test starter 闲置（pom.xml:98-102）、验证脚本全在 gitignore 的 .scratch/ 不可复现、无任何 CI 配置。
2. **作业事务语义缺陷**：PublishJobRunner.java:60 整个发布作业单事务 + Thread.sleep（153-154）演示延迟，取消后部分发布结果照常提交（166 break 不回滚），状态 CANCELLED 与数据不一致。
3. **文档与代码多处漂移**：README 步数 4/5 vs 实际 3/4（README.md:13-14 vs ExportWizardView.vue:49-53）、500ms vs 300ms（requirements.md:137 vs application.yml:69）、种子 9 定义/3000 行 vs 7 定义/1200 行（implementation-plan.md:250-263 vs DataSeedRunner.java:69-197）、设计与路由/组件不符（technical-design.md:315-322 vs router/index.js:4-39）。
4. **安全为演示级**：全 API 无认证授权；H2 Console 开放外部访问且 sa 空密码（application.yml:22-27）；上传无大小限制配置（默认 1MB 即上限）；ZIP 全量读内存（FileController.java:53、135）。
5. **代码卫生瑕疵成串**：重复 import（PublishJobRunner.java:18-19）、死代码 inDegree/inDeg（DependencyResolver.java:50-66）、死依赖 dayjs（package.json:18）、健康检查返回类名冒充计数（AiController.java:199-204）、硬编码 temperature/maxTokens 与 yml 双写（AgentRuntime.java:283-284）、`AsyncConfig.getAsyncExecutor` 返回 null 的“空壳配置”（AsyncConfig.java:22-27）。
