> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-12` 的 wire 末条 assistant 文本（消息序号 m=31；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 20:00 → 2026-10-05 20:08（wire 事件时间窗，UTC+8）；当时模型：`Zhipu AI/glm-5.3-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：21807 B / 14515 字符 / 224 行；行指纹覆盖率（本批复算）9.3%（最高单文件 7%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/023-ai-example-kimi-2-代码仓事实性勘察报告-39187be9-agent-11.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：kimi-1/2 分支打分会话；13/14 为同题续版已排除
> - 脱敏：路径 3 / 会话标识 0 / 身份 1 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
勘察完成，信息已充分。以下为最终证据报告。

---

# 代码评审勘察报告：ai-example-deepseek-v4-pro

评审根目录:`<LOCAL_WORK>\ai-example-deepseek-v4-pro`(分支 `deepseek-v4-pro`,工作区干净，远端 `github.com/<USER>/ai-example`)。以下所有结论均基于仓库内实际文件。

---

## 1. 项目概览

**定位**：AI 辅助动态配置管理系统——配置项结构数据驱动(字段/类型/依赖全部存 JSON),核心业务为“导出配置 / 导入配置”两类向导任务，右侧常驻 AI 助手(后端 AI Runtime)经工具调用驱动左侧工作区。

**技术栈**(来自 `backend/pom.xml:7-23`、`frontend/package.json:12-26`):

| 层 | 技术 | 版本 |
|---|---|---|
| 后端 | Spring Boot (parent) | 3.5.14 |
| 语言 | Java | 21(`pom.xml:21`) |
| AI | spring-ai-starter-model-openai + BOM | 1.1.8(`pom.xml:22,56-58`) |
| 数据库 | H2 嵌入式文件库(runtime scope) | 随 Boot |
| Excel | Apache POI poi-ooxml | 5.4.0 |
| 前端 | Vue 3 / vue-router / Pinia | ^3.5.13 / ^4.5.0 / ^2.3.0 |
| UI | Element Plus | ^2.9.0 |
| 样式 | TailwindCSS(preflight 关闭) | ^3.4.17 |
| 表格 | @grapecity/spread-sheets + spread-excelio | 17.1.5(商业组件，评估模式) |
| 构建 | Maven(无 wrapper)/ Vite 5 + yarn | — |

**代码量统计**(wc -l):

| 部分 | 文件数 | 行数 |
|---|---|---|
| 后端 Java(`backend/src/main/java`) | 42 | **5,426**(其中 ai/ 包 9 文件 1,399 行；service/ 9 文件 2,423 行) |
| 后端配置 `application.yml` | 1 | 78 |
| 前端 `frontend/src`(vue/js/css) | 21 | **3,065**(views 5 个 1,581;components 5 个 564;stores 4 个 413;utils 3 个 200) |
| 文档 `docs/*.md` | 8 | 1,127 |
| README.md | 1 | 63 |
| 脚本 `scripts/verify-e2e.ps1` | 1 | 685 |
| **合计** | ~74 | **≈10,400** |

**目录树(2 层)**：

```
ai-example-deepseek-v4-pro/
├── README.md / .gitignore
├── backend/          pom.xml + data/(运行时产物,H2库/exports/imports,已gitignore)
│   └── src/main/     java/com/example/configadmin/{ai,common,config,controller,dto,entity,repository,service}
│                     resources/application.yml
├── docs/             8 份 md + screenshots/(3 张 png)
├── frontend/         index.html / package.json / yarn.lock / vite|tailwind|postcss 配置
│   └── src/          api/ components/ router/ stores/ styles/ utils/ views/
└── scripts/          verify-e2e.ps1
```

---

## 2. 文档评估

docs/ 下 8 份文档全部存在、无占位空文件，总 1,127 行。逐份：

| 文件 | 行数 | 类型 | 完整度 |
|---|---|---|---|
| 01-需求规格说明书 | 171 | 需求(含术语表/FR 编号/验收标准) | 高，FR 编号到 FR-TASK-5 |
| 02-技术方案 | 159 | 设计(ADR-1~10 + 架构图 + 风险表) | 高，决策有取舍理由 |
| 03-实现方案 | 193 | 设计/实现(代码结构+流程+工具披露矩阵) | 高 |
| 04-测试用例 | 109 | 测试计划(F1-F7 分层用例+回归清单) | 中高 |
| 05-验证结果 | 167 | 测试报告(TC1-TC22 结果+13 条问题修复记录) | 中(结构有缺陷，见下) |
| 06-部署运行手册 | 95 | 部署(环境/端口/配置速查/FAQ) | 高，但命令硬编码本机路径 |
| 07-AI对话与状态同步设计 | 120 | 设计(快照/ui_event/HITL) | 中高 |
| 08-接口文档 | 113 | API 参考(42 端点逐一列表) | 高，与代码核对一致 |

**文档质量摘录**(每段≤10 行)：

`docs/02-技术方案.md:30-31`(ADR 有真实取舍论证)：
> ### ADR-1 动态结构的存储模型：通用行表 + JSON
> 配置项结构完全动态(字段集合/类型/必填/选项随时变化)，若为每个配置建物理表则需 DDL 权限且变更成本高。决策：
> - `config_def` 表存定义，字段集合以 JSON 存 `fieldsJson`;

`docs/05-验证结果.md:65-67`(记录了修复过程，可信度高)：
> | 1 | DeepSeek 400 | 工具 JSON Schema 中属性级 `required:true` 泄漏(DeepSeek 严格校验) | Schemas.obj() 剥离内部标记 |
> | 3 | 导出文件数据列为空 | queryRows 返回信封行，buildExport 期望扁平行 | ExportService 显式扁平化 |

**文档缺陷(均有行号证据)**：

1. **会话持久化自相矛盾(需求 vs 实现未同步)**——`docs/01-需求规格说明书.md:134` FR-AI-6 写“后端会话为内存态，后端重启后历史自然失效”；而 `README.md:20`、`docs/02-技术方案.md:55`(ADR-6)、`docs/03-实现方案.md:94`、`docs/08-接口文档.md:113` 均称“H2 持久化，后端重启不丢”。代码为后者(`AiSessionStore.java:94-108` 确实落 H2)。
2. **docs/07 内部冲突**——`docs/07-AI对话与状态同步设计.md:28` 标题为“会话生命周期(页签唯一、**不持久化**)”，同节表格 `:33` 却写“内存 Map(热)+ H2 `ai_session` 表(冷)；后端重启后对话历史均不丢失”；`:117` 权衡表又写“会话持久化|不持久化(页签级)|历史对话为预留扩展点”。三处自相矛盾(标题与权衡表是旧版残留)。
3. **测试组数字混乱**——`docs/04-测试用例.md:4` 与 `docs/06-部署运行手册.md:82` 写“19 个测试组”，`docs/05-验证结果.md:5` 头部写“20/20”、`:19` 写“19/19”,后文与脚本实为 22 组(TC1-TC22,`scripts/verify-e2e.ps1` 中 grep 到 TC1~TC22 全部存在)。头部数字未随迭代更新。
4. **验证结果章节编号错乱**——`docs/05-验证结果.md` 章节顺序为 §6 结论→§8(92 行)→§7(103 行)→§10→§11→§12→§13,缺 §9,且出现两个“结论”(§6、§13)。
5. **实现方案的“预留扩展点”已过时**——`docs/03-实现方案.md:185` 仍写“对话历史：AiSessionStore 增加 H2 落盘即可”，实际已实现。
6. **技术方案路由清单过时**——`docs/02-技术方案.md:113-117` 前端架构仅列 4 路由，实际 `frontend/src/router/index.js:4-9` 有 5 个(新增 `/tasks` 且默认重定向到它)。
7. **部署命令硬编码开发者本机路径**(见 §6)。

---

## 3. 后端实现

- **入口**:`ConfigAdminApplication.java`(16 行，`@SpringBootApplication @EnableAsync @EnableScheduling`)。
- **Web 框架**：Spring MVC(`spring-boot-starter-web`)+ `SseEmitter` 推 SSE;未用 WebFlux(流式 AI 走 Reactor 但 `blockLast()` 收尾于线程池线程，`AiRuntimeService.java:145`)。
- **API 路由清单(42 个端点，从 7 个 Controller 核对)**：
  - `/api/defs`(7):GET 列表 / GET {code} / POST / PUT {code} / DELETE {code} / POST {code}/toggle / GET check-code
  - `/api/data`(5):GET rows / POST query / POST rows / PUT rows/{id} / DELETE rows/{id}
  - `/api/export`(6):POST tasks / GET tasks / GET tasks/{id} / GET tasks/{id}/events(SSE) / GET files/{defCode} / GET package
  - `/api/import`(15):GET templates / POST batches / POST batches/ensure / GET batches / GET batches/{id} / GET batches/{id}/events(SSE) / POST batches/{id}/files / GET batches/{id}/files/{defCode} / POST check / POST import / POST publish / GET results / GET issues/{defCode} / GET errors/{defCode}(xlsx) / GET drafts/{defCode}
  - `/api/ai`(7):POST chat(SSE) / POST confirm / POST stop / POST clear / GET history / GET tools / GET sessions/count
  - `/api/meta`(1)GET;`/api/tasks`(1)GET;另 H2 console `/h2-console` 开启(`application.yml:21-25`)
- **数据模型**：5 JPA 实体 + 2 枚举——`ConfigDef`(字段/依赖存 CLOB JSON,`ConfigDef.java:34-41`)、`ConfigRow`(dataJson+batchId+published 通用行表)、`ImportBatch`(filesJson 状态机 JSON)、`ExportTask`、`AiSessionRecord`。持久化：Spring Data JPA,`ddl-auto: update`,H2 文件库 `MODE=MySQL`(`application.yml:16-28`)。
- **鉴权与安全**：**无任何认证/授权**(无 Spring Security 依赖，所有 /api/** 匿名可写)；无用户/密码体系(无密码存储问题)；API 密钥经环境变量注入 `${DEEPSEEK_API_KEY:}`(`application.yml:45`),grep 全库无硬编码密钥；zip 上传防路径穿越 `ImportService.java:180`(`Path.of(ze.getName()).getFileName()` 取基本名)+ `:248-251`(normalize+startsWith 前缀校验)；CORS `allowedOriginPatterns("*")` 全放开(`WebConfig.java:14`);H2 console 开启且 sa 空密码(`application.yml:18-25`)。
- **错误处理**：统一信封 `R<T>`(code/msg/data,`R.java`)+ `ApiException`(status+中文消息)+ `@RestControllerAdvice` 三层兜底(`GlobalExceptionHandler.java:16-35`);AI 流错误转 `error` SSE 事件并做友好化(`AiRuntimeService.java:255-268`)。
- **日志**：SLF4J,关键路径有 log(会话恢复/清理/工具失败/任务失败)，`logging.level` 按包降噪(`application.yml:73-78`)。
- **配置管理**：环境变量覆盖(SERVER_PORT、DEEPSEEK_API_KEY)+ `app.*` 自定义配置段，部署手册有配置速查表。

**代码质量观察(好)**：

1. **HITL 确认门工程化完整**——`AiSession.java:84-130` 用 CountDownLatch 实现确认门，等待期每 20s 发 SSE ping 保活(`:111-117`);客户端断开自动释放门并终止流(`AiRuntimeService.java:96-107` 三处回调)，新消息自动拒绝遗留门(`AiSession.java:72-76`,“宁可少做不可错做”)。
2. **单一校验事实来源**——`ValidationEngine.java:16` 声明并被三处共用：导入检查(`ImportService.java:325`)、发布前检查(同路径)、手工保存(`ConfigDataService.java:201`),规则(类型/必填/选项/范围/引用/范围必填)只写一份。
3. **官方框架扩展点使用规范**——`AiModelConfig.java:39-45` 组合包装官方 `DefaultToolCallingManager`,仅叠加确认门与 SSE 可见性两个钩子(`HooksToolCallingManager.java:23-31` 注释明确“协议与循环完全交给 Spring AI”);提交 25639c5 删除了 1,668 行手工协议代码。
4. **Jackson non_null 解决真实兼容问题**——`application.yml:36-39` 注释完整解释“OpenAI 专属 null 字段会导致 DeepSeek 400”的原因，是实测经验的沉淀。

**代码质量观察(坏/风险)**：

1. **注释与实现矛盾**——`AsyncConfig.java:10` 类注释写“JDK21 虚拟线程，导出/检查/导入/发布互不阻塞”，实现却是平台线程池 `ThreadPoolTaskExecutor`(8-32,`:16-24`);`application.yml:10-12` 仍开 `spring.threads.virtual.enabled: true` 且注释称虚拟线程。实现本身合理(注释解释了载波钉死原因)，但类级注释未同步。
2. **“服务端分页”实为内存分页**——`TaskController.java:39-57` 先 `exportService.list()+importService.list()` 全量载入内存，排序后 `subList`;`ConfigDataService.java:76-88` 分页同样在内存完成。文档(FR-TASK-5、README)宣称“服务端分页”，语义勉强成立但数据量大时全表进内存。
3. **timeout+retry 的副作用重放风险**——`AiRuntimeService.java:124-125` `.timeout(90s).retry(1)`:Reactor 的 retry 会重放整条流，若首轮已执行过非幂等工具(如 `start_export`,`ExportService.create:58-75` 无幂等键，每次新建任务)，断流重试可能产生重复副作用。导入/发布有状态机幂等保护(`ImportService.java:386,414,480`)缓解，但导出没有。
4. **非常规 conversationHistory 组装**——`HooksToolCallingManager.java:128-130` 返回的 conversationHistory 仅 `[assistant, toolResponseMessage]`,未在原 prompt 历史上追加(官方示例做法)，依赖 Advisor 重新注入历史才能工作；功能经 TC22 验证正常，但属于对框架内部行为的依赖。
5. **ProgressHub 心跳任务轻微泄漏路径**——`ProgressHub.java:47-53` 每个订阅者注册 15s 周期心跳任务，取消挂在 onCompletion/onTimeout/onError 回调(`:55-57`);但 `:69-71` 快照发送失败的 catch 分支只调 `remove()` 未 `emitter.complete()`,该路径心跳任务不会被取消。
6. **500 响应泄露内部信息**——`GlobalExceptionHandler.java:34` 将 `e.getMessage()` 原样返回给客户端(演示可接受)。

---

## 4. 前端实现

- **框架**：Vue 3(Composition API `<script setup>`)+ Vite 5 + yarn(有 `yarn.lock`,可复现安装)。
- **页面/视图**(5):`TasksView.vue`(180 行，默认首页 `/`)、`ExportWizardView.vue`(319)、`ImportWizardView.vue`(500)、`DefsAdminView.vue`(381)、`DataBrowseView.vue`(137)。
- **路由**:`router/index.js:3-10`,懒加载，afterEach 设中文标题。
- **状态管理**:Pinia 4 个 store——`ai.js`(214,SSE 消息流+确认卡)、`exportTask.js`(84)、`importTask.js`(115)、`defs.js`(24)。
- **API 调用**:`api/index.js` 手写 fetch 封装(110 行)，含 SSE 流式解析器 `sseRequest`(`:61-90`,按 `\n\n` 分帧、解析 event/data、处理尾部残帧)，无 axios。
- **UI 组件库**：Element Plus(zh-cn locale,`main.js:4,20`)+ Tailwind 工具类(`tailwind.config.js:5-7` 显式关 preflight 防止重置 EP 样式)；SpreadJS 17.1.5 在线编辑(`SpreadSheet.vue`,ExcelIO 17.x JSON 协议 API 用法正确，`:54-71` 注释记录了类型校验坑)。

**质量观察(好)**：

1. **解耦架构确实落地**——`stores/ai.js:19-22` 明确声明不 import 任何工作区 store/路由(grep 验证属实);`utils/workspaceBus.js`(52 行)为唯一通信总线，`utils/workspaceContract.js:17-117` 是“ui_event↔store action”唯一映射点，且 AI 驱动时 `suppressWorkspaceEvents()` 抑制“工作区已更新”误报(`:45-47`)。
2. **用户与 AI 同源写入**——`exportTask.js:15-28` 的 selectDefs/setConditions 被用户点击与 AI ui_event 走同一 action,状态不可能分叉(设计文档承诺与代码一致)。
3. **边界处理细致**——`ai.js:165-166` tool_result 匹配“最后一个 running 工具，否则最后一个”;`exportTask.js:47-48` 快照 id 归一化(`snap.detail.id`)修复了实际 bug(验证结果 §4 #7 记录了根因)。

**质量观察(坏)**：

1. **Element Plus 1.x 时代死代码**——`SpreadSheet.vue:9` `<i class="el-icon-loading" />`:EP 2.x 图标已拆分到 `@element-plus/icons-vue`(package.json 未安装该包)，此写法不渲染任何图标。
2. **无任何前端工程质量设施**——package.json 无 eslint/prettier/vitest,无 lint 与 test script(仅 dev/build/preview);styles 内 `main.css` 与 `tailwind.css` 并存。
3. **`ai.js:59` 冗余判断**——`(!m.content || m.content.length === 0)` 两者等价(小瑕疵)；同类冗余还有 `AiPanel.vue:56-61` 的双空态分支。

---

## 5. 测试

| 项 | 事实 |
|---|---|
| 单元测试 | **无**:`backend/src` 下只有 `main` 目录(ls 确认)，无 `src/test`;`spring-boot-starter-test` 已声明(`pom.xml:79-83`)但零使用 |
| 前端测试 | 无(无 vitest/jest 依赖) |
| E2E | `scripts/verify-e2e.ps1` 685 行，PowerShell 直连 REST API:**22 个测试组**(TC1-TC22,脚本内 `Say '--- TC…'` 标记 22 处，每组成对 Ok/No 断言)，覆盖种子数据/CRUD/校验/导出条件/打包/模板/上传匹配/拓扑检查/失败明细/SSE/草稿隔离/发布替换/HITL/会话/历史恢复 |
| AI 用例 | TC15-TC17、TC22 真实调用 DeepSeek(需 `DEEPSEEK_API_KEY`,脚本才可全绿) |
| CI | **无** `.github/` 目录(git 亦无 workflow 文件) |
| 可运行性 | 脚本本身是可静态读通的完整自动化(含 SSE 读取 `:76-111`、xlsx 篡改构造 `:130`、zip 校验 `:161`),但需后端先启动;docs/04:11 自述“单元测试经 E2E 间接覆盖”，属于策略选择而非隐瞒 |

测试策略声明与实际相符，但“单元 0 个、E2E 依赖运行中的后端+真实外部 LLM”意味着 `mvn test` 实际没有可跑的东西。

---

## 6. 脚本与部署

- `scripts/` 仅 1 个文件:`verify-e2e.ps1`(685 行)。无 Dockerfile、无 docker-compose、无 `.env.example`(SpreadJS key 与 API key 的注入方式只在文档中说明，`.gitignore:12-13` 排除 `.env.local`)。
- **README 快速开始可信度**：步骤完整(构建→设密钥→启动→前端 dev→E2E),但后端构建/启动命令硬编码了开发者本机路径——`README.md:37-39` 与 `docs/06-部署运行手册.md:34-35`:
  ```
  $env:JAVA_HOME='<DRIVE>\Program Files\Java\jdk-21.0.12'
  & '<DRIVE>\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' ...
  ```
  项目**无 Maven wrapper**(backend/ 下无 `.mvn`/`mvnw`;`.gitignore:5` 的 `!.mvn/wrapper/maven-wrapper.jar` 是空挂规则)，换一台机器无法照 README 直接执行。E2E 脚本也仅 PowerShell(Windows-only)。
- 运行过的旁证:`backend/data/config_admin_db.mv.db` 与 `exports/1..6`、`imports/{1,2,4,6}`(含 `.issues.json` 明细)存在于工作区，`frontend/vite.log` 存在(均已被 gitignore)。

---

## 7. Git 历史

- `git log --oneline`:**13 个提交**，无 merge commit(线性)。
- 时间跨度：全部集中在 **2026-10-05 11:47 → 19:21(同日约 7.5 小时)**；最新一条与最旧一条首尾时间戳如上(git --date 输出已核对)。
- 提交粒度：3 个大提交——`ffe56a6`(+5,507 行，初始化+基础框架)、`28c232a`(+3,992 行，前后端全量+E2E)、`3fac8d3`(docs +756);10 个中小提交含 5 个 fix(每次 87~285 行)、1 个 refactor(`25639c5`,33 文件 +1,414/-1,668)。
- 风格:`type:` 前缀规范(feat/fix/docs/chore/refactor),但单条消息极长(一条提交消息 100+ 字、复合多主题)，如 `c41f459` 一次提交同时含“分页+布局+新接口+E2E 扩展”。分支 `deepseek-v4-pro` 已推送远端(origin 有 main/claude-opus-5.5 等并列分支)。

---

## 8. 可运行性静态判断

**结论：大概率可构建、可运行(未实际执行构建验证)**，依据：

正面依据：
- 后端依赖声明完整自洽(pom.xml 7 个依赖均为单一代码库来源；代码 import 的 Spring AI 类如 `ToolCallingManager`、`MethodToolCallbackProvider`、`MessageChatMemoryAdvisor` 均有对应 BOM 依赖)。
- 全库 grep `TODO|FIXME|XXX|HACK|stub|unimplemented`:**0 命中**；grep 硬编码密钥模式(sk-…/Bearer/硬编码 api-key):**0 命中**。
- 前端有 `yarn.lock`,依赖齐全;`vite.config.js` 代理指向 18080 与后端端口一致。
- 运行时产物(H2 库、导出/导入目录、日志)证明该代码实际跑过。

保留项：
- `DEEPSEEK_API_KEY` 为空时应用仍可启动但 AI 用例必然 401(`application.yml:45` 空默认值；友好错误已处理 `AiRuntimeService.java:261-262`)。
- `Spring Boot 3.5.14`、`Spring AI 1.1.8`、模型名 `deepseek-flash`(自称 DeepSeek-V4.1-Flash)的版本号/模型名真实性无法在仓库内验证(外部检索在本环境不可用)；文档自称“Maven Central 已验证存在”(docs/02:14-16)、“经实测可用”(docs/02:17)。评审仅记录该声明，不作真伪断言。
- 无鉴权/H2 console 开启不影响“能跑”，但影响“能上生产”。

---

## 9. 总结

### 五条最大亮点

1. **AI 全链路彻底框架化**：工具=`@Tool` 注解(AiTools.java,456 行 22 个工具)、循环=ChatClient 内部 ToolCallingManager、记忆=MessageChatMemoryAdvisor+自定义 ChatMemory(`AiSessionChatMemory.java`),自定义部分仅组合包装官方扩展点做 HITL+可见性(`AiModelConfig.java:39-45`),并有一次真实的 -1,668 行删除提交(25639c5)。
2. **解耦联动架构真实落地**:`workspaceBus.js`(52 行)+`workspaceContract.js`(118 行)使 AI 面板与工作区互不 import、可独立裁剪，UI 事件与用户点击同源写 store action(`exportTask.js:15-28`),并抑制 AI 驱动导致的误报(`workspaceContract.js:45-47`)。
3. **HITL 确认门工程化完整**：latch 门+20s 心跳保活(`AiSession.java:100-118`)+断开/停止/超时自动释放+“未确认即拒绝”安全默认(`AiRuntimeService.java:96-107`、`AiSession.java:72-76`)。
4. **校验规则单一事实来源**:`ValidationEngine` 一份实现服务导入检查/发布检查/手工保存三条路径(`ImportService.java:325`、`ConfigDataService.java:201`),并有依赖拓扑排序(Kahn,`ConfigDefService.java:192-229`)与“发布=删旧生效+草稿转正”的一致性语义(`ImportService.java:501-516`)。
5. **文档+验证记录密度高**：8 份文档 1,127 行覆盖需求→ADR→实现→用例→结果→部署→接口;`docs/05-验证结果.md` §4 以 13 条“问题-根因-修复”记录真实调试过程，22 组 E2E 脚本 685 行可重复执行。

### 五条最大问题

1. **测试金字塔倒置**：单元测试为 0(`backend/src` 无 test 目录)，`spring-boot-starter-test` 空挂(`pom.xml:79-83`);质量完全依赖需启动后端+真实 DeepSeek 密钥的 PowerShell E2E;**无 CI**(`.github/` 不存在)，回归清单(docs/04:107)宣称的 `mvn package`/`yarn build` 无自动化保障。
2. **文档多处过时与自相矛盾**：会话持久化三处冲突(docs/01:134 vs docs/02:55 vs docs/07:28/33/117)、测试组数 19/20/22 混用(docs/04:4、docs/05:5/19、docs/06:82)、验证结果章节号错乱(docs/05:92 的 §8 排在 §7 之前)、路由清单缺 /tasks(docs/02:113-117)。
3. **安全边界缺失且未声明**：全部 42 个 API 匿名可写(无 Spring Security),H2 console 开放+sa 空密码(`application.yml:21-25`),CORS `*`(`WebConfig.java:14`);`docs/02-技术方案.md` §8“安全设计”通篇未提鉴权缺失这一最大限制。
4. **部署可移植性差**：构建/启动命令硬编码本机 IntelliJ 内置 Maven 与 JDK 路径(README.md:37-39、docs/06:34-35),无 Maven wrapper;E2E 仅 PowerShell(Windows-only);`.gitignore:5` 的 wrapper 排除规则为空挂。
5. **重试/资源管理缺陷**：AI 流 `timeout(90s).retry(1)` 重放整流可重复触发非幂等工具(`AiRuntimeService.java:124-125`;`ExportService.create:58-75` 无幂等键);`ProgressHub.java:69-71` 异常分支不取消心跳任务(泄漏路径);`AsyncConfig.java:10` 注释与平台线程池实现矛盾;`SpreadSheet.vue:9` 存在 Element Plus 1.x 死代码；两处“分页”实为内存分页(`TaskController.java:39-57`、`ConfigDataService.java:76-88`,与文档“服务端分页”表述有落差)。
