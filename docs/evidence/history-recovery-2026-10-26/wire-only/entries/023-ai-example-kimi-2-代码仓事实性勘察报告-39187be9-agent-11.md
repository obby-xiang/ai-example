> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-11` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：17889 B / 11925 字符 / 203 行；行指纹覆盖率（仓内 + 落盘语料）4.3%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 0 / 身份 2 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# `ai-example-kimi-2` 代码仓事实性勘察报告

评审对象：`<LOCAL_WORK>\ai-example-kimi-2`（只读勘察，未修改任何文件）。所有证据均来自该目录内部。

---

## 1. 项目概览

**定位**：AI 辅助的动态配置管理平台——左侧多步骤任务向导（导出/导入配置）+ 右侧常驻 AI 对话栏，双通路等价、SSE 联动。

| 端 | 技术栈 | 证据 |
|---|---|---|
| 后端 | Spring Boot **3.5.14** · JDK **21** · Spring AI **1.1.8**（BOM）· Spring Web/Data-JPA/Validation · H2 文件库（MODE=MySQL）· Lombok · Maven | `backend/pom.xml:8,18-19,41,53-56` |
| 前端 | Vue **3.5** · Element Plus **2.9.3** · TailwindCSS **3.4.17** · SpreadJS **17.1.5**（锁定）· Vite **6** · Pinia 2.3 · vue-router 4.5 · axios · JSZip · yarn | `frontend/package.json:11-29`，yarn.lock 1460 行 |
| AI | OpenAI 兼容接入 DeepSeek `deepseek-flash`，Function Calling + SSE 流式，后端 Runtime | `backend/src/main/resources/application.yml:16-30` |

**代码量统计**（`wc -l` 实测）：

| 部分 | 文件数 | 行数 |
|---|---|---|
| 后端主代码（`backend/src/main/java`，Java） | 46 | **4010**（另 application.yml 44 行） |
| 后端测试（`backend/src/test`） | 6 Java + 1 yml | **910**（另 test-yml 33 行） |
| 前端源码（`frontend/src` + `scripts/build.mjs`） | 17 + 1 | **2189** + 15 |
| 文档（`docs/*.md`） | 5 | **688** |
| README.md | 1 | 89 |

**目录树（2 层，省略构建产物）**：

```
├── README.md / .gitignore / scripts/githooks/pre-commit
├── backend/            pom.xml, maven-settings.xml
│   ├── src/main/java/com/example/quickstart/
│   │   ├── ai/ common/ config/ controller/ dto/ entity/ repository/ runtime/ service/
│   ├── src/main/resources/application.yml
│   ├── src/test/java/...（5 测试类 + BaseIntegrationTest）, test/resources
│   ├── data/ logs/ target/（本地运行痕迹，gitignore 内）
├── frontend/           package.json, vite.config.js, yarn.lock
│   ├── src/api/ components/wizard/ router/ stores/ styles/ utils/ views/
│   └── scripts/build.mjs, dist/（构建产物）, logs-dev.log
└── docs/               01需求～05验证结果 共5份
```

---

## 2. 文档

| 文件 | 行数 | 类型 | 完整度 |
|---|---|---|---|
| `README.md` | 89 | 使用/部署 | **高**。技术栈表、快速启动（前置→后端→前端→测试）、使用指引、文档索引、安全说明 |
| `docs/01-需求规格说明书.md` | 175 | 需求 | **高**。术语表 + FR-C01～S04 编号需求 + NFR-01～08 + 6 条验收标准，明确范围排除项 |
| `docs/02-技术方案设计.md` | 290 | 设计（架构/ADR/API/SSE/安全/测试策略） | **很高**。架构 ASCII 图、ADR **D1-D10**、数据模型、状态机、API 契约 20 条、SSE 事件契约、校验规则 V1-V8、风险对策 |
| `docs/03-实现方案说明.md` | 80 | 实现说明 | **高**。代码结构对照、关键实现点、**与设计差异记录 3 条**、已知限制 3 条 |
| `docs/04-测试用例.md` | 67 | 测试计划 | **高**。38 条自动化用例（T-A01～E08）+ 10 个 E2E 场景（T-F01～F10）+ 测试数据约定 |
| `docs/05-测试验证结果.md` | 76 | 测试报告 | **高**。surefire 原始输出、E2E 逐条记录、**缺陷闭环表 B1-B6**（根因/修复/复测） |

**文档质量摘录**（ADR，`docs/02:46`）：

> | D1 | AI Runtime 全后端，用 Spring AI 高层 `ChatClient + ChatMemory + @Tool + Advisor` | ①手写 OpenAiApi Agent Loop（kimi-1 方案）②前端 Runtime | 硬约束“能用 Spring AI 就不自研”。已实验验证：deepseek-flash 回放含 tool_calls 的 assistant 历史…返回 200 |

（缺陷闭环，`docs/05:69`）：

> | B5 | 事件分发循环依赖 | TaskService↔EventPublisher 构造环 | EventPublisher 对 TaskService @Lazy | 38 测试通过 |

**含糊/缺失/矛盾点（逐条核实过）**：
- `docs/02:81` 与 `:271` 写 `api-key: ${DEEPSEEK_API_KEY:}`，实际 `application.yml:19` 为 `${DEEPSEEK_API_KEY:dummy-not-set}`（README:87 表述正确）——轻微漂移。
- `docs/02:219` 基础工具列表含 `open_task`，实际 `BasicAiTools` 的 9 个 @Tool 中**无 open_task**（实为 `set_selected_configs` 常驻基础组，而文档把它列进导出/导入组）——渐进式披露描述与实现有出入。
- `docs/02:164` 任务列表契约含 `keyword=` 过滤，`TaskController.java:45-46` 仅实现 type/status。
- `docs/02:255` 设计了 `useProgressStore`，实际 stores 只有 session/task/ai 三个，进度并入 `stores/task.js`。
- `docs/02:273` 称“文件大小上限 10MB”，但后端无文件上传端点（Excel 在前端解析），10MB 无处强制；5000 行上限真实存在（`TaskController.java:185-187`）。
- FR-I04（`docs/01:103`）要求差异摘要含“新增/修改/未变化”，实现仅给 staged/published 两个行数（见 §3 问题②）。

---

## 3. 后端实现

- **入口**：`QuickstartApplication.java`（`@EnableScheduling` + `@SpringBootApplication`，13 行）。
- **框架**：Spring MVC（REST + SseEmitter），JPA/Hibernate，无 MyBatis。
- **API 路由全量清单**（22 条，均在 `/api` 前缀，多数带可选 `X-Session-Id` 头）：
  - Catalog：`GET /catalog/configs`、`GET /catalog/scopes`、`GET /catalog/task-types`（CatalogController）
  - Task：`POST /tasks`、`GET /tasks`、`GET /tasks/{id}`、`POST /tasks/{id}/open|cancel|selection|conditions|export/start|data|check/start|import/start|publish/start`、`GET /tasks/{id}/export/results`（TaskController，13 条）
  - Session：`GET /sse`、`GET /session/state`、`POST /session/bind`、`POST /session/unbind`
  - AI：`POST /ai/chat`（SSE 流）、`GET /ai/history`、`DELETE /ai/history`
- **数据模型**：5 实体——`Task`（状态机+params JSON CLOB）、`ConfigDef/ConfigField`（动态字段结构）、`ConfigData`（PUBLISHED/STAGED 双状态+task_id 暂存隔离，双索引 `ConfigData.java:12-15`）、`ScopeDict`。
- **持久化**：H2 文件库 `jdbc:h2:file:./data/quickstart_db;MODE=MySQL`，`ddl-auto: update`；任务参数整体存 JSON CLOB（ADR D9）。
- **鉴权/安全**：**无用户鉴权**（单用户演示定位，`docs/03:80` 自认）；CORS 全开（`WebConfig.java:13` `allowedOriginPatterns("*")`）；API Key 仅环境变量占位；pre-commit 密钥扫描（见 §6）；无 SQL 注入面（查询为内存过滤，不拼 SQL）。
- **错误处理**：`GlobalExceptionHandler` 三层（BizException/参数校验/兜底 500），统一 `ApiResponse{code,data,msg}` 包。
- **日志**：lombok `@Slf4j`，`application.yml:40-44` 分级配置；运行日志 687 行在 `backend/logs/backend-run.log`。
- **配置管理**：yml + `@Value` 注入（线程池延迟、AI 记忆窗口、会话 TTL），test profile 独立 yml。

**质量观察（好）**：

1. **状态机守卫集中一处、REST 与 AI 工具共用**（单一事实源的关键），`service/TaskService.java:103-114`：
```java
public void assertNotTerminal(Task task) {
    if (SUCCESS.equals(task.getStatus()) || FAILED.equals(task.getStatus())
            || CANCELLED.equals(task.getStatus())) {
        throw new BizException("任务已结束（" + task.getStatus() + "），不能继续操作");
    }
}
```
2. **协作式取消设计清晰**（volatile 标志 + 配置项边界生效），`runtime/ProgressManager.java:76-90`。
3. **依赖拓扑排序带环检测**，`service/ImportRunner.java:293-295`：`if (!visiting.add(code)) throw new BizException("配置项依赖存在循环：" + code);`
4. **AI 会话互斥 + 友好错误映射**（401/429/并行工具调用），`ai/AiChatService.java:62-66,151-163`。
5. **循环依赖用 `@Lazy` 显式打破并注释说明**，`runtime/EventPublisher.java:20`。

**质量观察（坏）**：

1. **导出失败任务卡死 EXECUTING**：`ExportRunner.java:91-99` 的 catch 只处理“操作已取消”，其余异常直接 `throw e`，被 `AsyncRunner.java:32-34` 吞为日志——任务永不落 FAILED（对比 `ImportRunner.java:183` 有 `markTerminal(task, FAILED)`）。
2. **死代码 + 误导调用**：`ImportRunner.java:193-194` `List<ConfigData> published = dataService.stagedRows("__none__", def.getId());` 结果从未使用（用 "__none__" 当 taskId 查询恒为空）；且 `dataRepo` 字段声明在文件中部（`ImportRunner.java:266`）。
3. **暂存清理越界**：`ConfigDataService.java:271-274`——非全局配置重导入时 `deleteByDefIdAndStatusAndScopeValue(defId,"STAGED",scopeValue)` 按 scope 删除，会连带删掉**其他任务**的同范围暂存数据。
4. **异常静默吞掉**：条件匹配 `ConfigDataService.java:205-207` `catch (Exception e) { return false; }`——脏数据导致的解析失败被当作“不匹配”，无任何日志。
5. **SSE 断连引发二次异常**：运行日志 17:39:21 显示 SSE 客户端断开后 `GlobalExceptionHandler` 试图对 `text/event-stream` 返回 JSON，报 `No converter ... with preset Content-Type 'text/event-stream'`（`backend/logs/backend-run.log`），异常处理器未区分 SSE 端点。

---

## 4. 前端实现

- **框架/构建**：Vue 3 `<script setup>` + Vite 6（proxy `/api → 18290`，`vite.config.js:15-22`）+ Element Plus（全量引入 + zh-cn）+ Tailwind 3。
- **路由**（hash 模式，3 页）：`/` Home、`/task/:id` TaskWizard（异步加载 Export/ImportWizard）、`/tasks` TaskCenter（`router/index.js:3-7`）。
- **状态管理**：Pinia ×3——`session`（sessionId sessionStorage + EventSource 管理）、`task`（任务快照 + 目录 + 进度，REST 与 SSE 统一走 `applyTaskMap`）、`ai`（消息/流式/活动卡片）。
- **API 调用**：axios 实例 + 请求拦截器自动带 `X-Session-Id`（`api/index.js:5-11`）；AI 流式用 fetch + ReadableStream 手动解析 POST-SSE（`api/sse.js`）。
- **UI 组件库**：Element Plus + 自研 SpreadGrid（SpreadJS 封装）。无 uniapp/其他。

**质量观察**：

1. **好｜导航主权实现完整**：AI 创建任务时按当前路由决定跳转或弹通知，`App.vue:69-82`。
2. **好｜Excel 链路防御性编码**：校验器逐类型 try/catch、表头归一化（trim/去 `*`/全角转换/小写），`utils/excel.js:30-59,121-126`。
3. **好｜Windows 环境适配**：`scripts/build.mjs:9-13` 将 TMP/TEMP 重定向到项目内 `.tmp-build` 防杀毒锁 esbuild。
4. **坏｜遗留调试代码**：`main.js:24-37` 把全局错误写进 `document.title`，注释自称“临时调试…验证后移除”但仍在源码中。
5. **坏｜SpreadGrid 事件死代码**：`SpreadGrid.vue:59-61` 定义 `markChanged`/`emit('changed')`，但 `onMounted` 从未 `sheet.bind(...)`，该事件永不触发（仅卸载时 unbind）。
6. **坏｜伪回退**：检查发现错误后“返回修改数据”按钮只弹提示不改步骤（`ImportWizard.vue:391-396` 注释自认“后端步骤机不允许回退 API”），与设计文档“允许回退”不符。

---

## 5. 测试

| 维度 | 事实 |
|---|---|
| 后端单测/集成 | **5 个测试类 38 个 @Test**：AiToolsTest 8、CatalogServiceTest 5、ConfigDataServiceTest 11、ExportFlowTest 4、ImportFlowTest 10；JUnit5 + `@SpringBootTest`(RANDOM_PORT) + H2 内存库 test profile（`BaseIntegrationTest.java:11-13`），`@DirtiesContext`/`@TestMethodOrder` 使用得当 |
| 运行证据 | `backend/target/surefire-reports/TEST-*.xml`（时间 2026-10-05 17:01:12-14）合计 `tests=8+5+11+4+10=38`，与 `docs/05:15`、README:56、提交信息三方一致 |
| AI 测试方式 | 工具直调，不实调模型（`AiToolsTest.java:20-23`；test yml 用假 key `test-key-not-real`） |
| 前端测试 | **无**（无 vitest/jest 依赖，无 *.test.js；仅 node_modules 内第三方自带测试） |
| E2E | **无自动化框架**（无 playwright/cypress）；docs 04 §2 的 10 个场景为人工执行 + `docs/05` 文字记录（含 curl 验证） |
| CI | **无**（无 .github/、.gitlab-ci 等） |
| 可运行 | `mvn -s maven-settings.xml test` 可复现（依赖/test profile 齐全，测试消耗 `app.job.per-config-delay-ms:1` 已调小，`application-test.yml:24`） |

---

## 6. 脚本与部署

- `scripts/githooks/pre-commit`（17 行）：正则扫描 `sk-/ghp_/AKIA/AIza/xox` 五类密钥特征 + 白名单，命中即拒绝提交；需手动 `git config core.hooksPath scripts/githooks` 启用（README:88 有说明）。
- `backend/maven-settings.xml`：阿里云镜像 + spring 源站排除（绕过华为云镜像同步滞后），附注释说明。
- `frontend/scripts/build.mjs`：vite build 的 Windows TMP 包装。
- **无 Dockerfile / docker-compose / .env.example**（find 确认）；`.gitignore` 覆盖 target/node_modules/dist/logs/`backend/.local.env`。
- **README 启动步骤完整可信**：前置（JDK21/Maven3.9/Node18+/yarn1.22）、端口 18290/15271、API Key 注入命令（PowerShell/CMD）、首次建库说明、测试命令，全部与实物对得上。运行痕迹充分：`backend/target/quickstart-backend.jar`（67MB，17:06 构建）、`backend/logs/backend-run.log` 687 行（17:06-19:17，jar 启动 + 服务请求）、`frontend/logs-dev.log`（vite 507ms 就绪）、`frontend/dist` 构建产物。

---

## 7. Git 历史

- `git log --oneline`（共 **6** 条，工作区 clean）：
```
821addb docs: 实现说明/测试用例/验证结果/README（38 自动化测试 + 10 E2E 场景全部通过）
cd18e61 fix: E2E 修复与体验优化
40e104d frontend: Vue3 + Element Plus + Tailwind + SpreadJS 17.1.5 前端实现（构建通过）
44a7de8 backend: Spring Boot 3.5.14 + Spring AI 1.1.8 后端实现（38 个测试全通过）
20448e8 docs: 技术方案设计（架构/ADR/数据模型/API/SSE契约/AI Runtime/前端/安全）
ea07793 docs: 需求规格说明书（含验收标准）+ 密钥防泄漏 pre-commit 钩子
```
- 时间：首条 `2026-10-05 16:39:42`，末条 `17:42:19`，**总跨度约 63 分钟**；单一作者 <USER>。
- 风格/粒度：类型前缀（docs/backend/frontend/fix）+ 中文摘要 + 括号内验证结论；**粒度极大**（backend 提交 56 文件 +5102 行，frontend 提交 24 文件 +3697 行），docs 提交 4 文件 +312 行。
- 分支：`master` 与 `glm-5.3` 同指 821addb，跟踪 `origin/glm-5.3`（GitHub <USER>/ai-example）。
- 提交信息中的“38 个测试全通过”经 surefire 报告核实为真。

---

## 8. 可运行性判断

**结论：静态判断可构建、可运行，且已有运行实证。**
- 依赖完整：pom 8 依赖 + Spring AI BOM；package.json 10+5 依赖且 yarn.lock 在；无缺失 import（抽查全部控制器/服务/store）。
- 构建/运行产物在库：后端 jar（67MB）+ 前端 dist + 双端运行日志 + H2 数据文件。
- 无 TODO/FIXME/stub（grep 0 命中）；无硬编码密钥（仅 `dummy-not-set` 占位与测试假 key）。
- 测试可复现（surefire 证据 + test profile）。

保留风险：① AI 通路依赖外部 `deepseek-flash` 模型名与 `thinking:disabled` extra-body 参数，无法静态验证（README 未把 AI 列为启动必需）；② `cd18e61` 修复提交改了 `SystemPromptBuilder.java`（4 行），但当前 jar 构建于 17:06（早于该提交），工作区 target 内 jar 未包含此后端修复；③ 运行日志中 5 条 ERROR 均为 SSE 客户端断连的二次异常（见 §3-5），非功能故障。

---

## 9. 五条最大亮点 / 五条最大问题

**亮点**：
1. **文档体系完整且与实物互相印证**——5 份 688 行编号文档（含 ADR、差异记录、缺陷闭环），README 中可核对的数字（38 测试、端口、目录结构）全部属实（`docs/01-05`、surefire 报告）。
2. **双通路解耦真正落地**：REST 与 20 个 AI @Tool 共用同一 `TaskService` 状态机守卫（`TaskService.java:101-139`），SSE 单一事实源广播，且有 AiToolsTest 8 例工具直调测试佐证。
3. **测试真实可信**：38 个 Spring Boot 集成测试覆盖条件引擎/导出/导入/发布/依赖拓扑/工具守卫，surefire 时间戳与提交链一致，测试基础设施（内存库+profile+DirtiesContext）规范。
4. **AI Runtime 工程化程度高**：Spring AI 高层 API + 每轮动态系统提示词（状态摘要注入，`SystemPromptBuilder.java:24-92`）+ 工具渐进式披露 + 会话互斥/TTL 清理（`AiSessionRegistry.java:62-95`）。
5. **安全与环境细节**：pre-commit 密钥扫描钩子、API Key 仅环境变量、协作式取消（配置项边界生效）、Windows TMP/镜像/端口适配均有据可查。

**问题**：
1. **导出失败任务永久卡在 EXECUTING**——`ExportRunner.java:91-99` 缺少 FAILED 落态（`ImportRunner.java:183` 有），用户只能手动取消。
2. **FR-I04 差异摘要未实现 + 死代码 + 伪回退**：`ImportRunner.java:192-201`（unused `published`、`stagedRows("__none__")`）；检查后无法返回修改数据（`ImportWizard.vue:391-396`、`TaskController.java:184` 步骤锁），需求“可修复后重试”链路断裂。
3. **前端零自动化测试、无 CI**：10 个 E2E 场景仅有文字记录不可复现；`main.js:24-37` 遗留自称“验证后移除”的调试代码与 `SpreadGrid.vue` 从未触发的事件死代码。
4. **隔离与安全边界薄弱**：暂存清理按 scope 误删他任务数据（`ConfigDataService.java:271-274`）；CORS 全开 + 无鉴权 + 上传行数据仅限行数不限内容（单行可塞满 CLOB）。
5. **文档与实现多处漂移**：`open_task` 工具不存在、`keyword` 过滤未实现、`useProgressStore` 不存在、10MB 文件上限无处强制（`docs/02:164,219,255,273`）——不影响主体，但削弱了“文档即契约”的可信度。
