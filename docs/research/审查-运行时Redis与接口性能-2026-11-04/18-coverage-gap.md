# 18 — 全量生产代码覆盖缺口终审

- 基线：`52b09364`；本次核对时 `HEAD` 同为该基线，指定生产树相对基线无差异。
- 对照范围：`git ls-files backend/src/main frontend/src` 的全部 473 个生产文件（后端 Java 350、后端资源 31、前端 92），以及本目录 `01-...` 至 `16-...` 的 16 份首轮报告。
- 方法：先按完整路径和文件名反查报告，再打开生产源码核对调用点、数据量边界、线程/调度、上下文和资源出口。报告正文中“已读某类”不自动等于该类所有运行路径均被审查。
- 边界：只读源码、报告和只读 Git 清单/差异；未修改生产或测试代码，未运行测试、构建、CI、服务或压测，未执行 Git 写操作，未启用子 agent。本文唯一写入物是本报告。
- 证据分级：**代码事实**为源码可以直接证明的调用、顺序和数据结构；**静态推导**为由这些事实推出的条件性成本；**待实测**为 SQL 数、真实耗时、浏览器长任务、堆峰值等运行结果。

## 一、终审结论

首轮 16 份报告已经闭合了主要 Redis 数据面、全部 Controller、后端显式调度器、前端定时器/SSE、主链身份传播和大部分资源生命周期。**没有发现新的 Redis 使用类、Controller 类、`@Scheduled` 或显式周期任务漏审。**

真实缺口集中在首轮分工没有单列的“AI 工具内部实现”及“前端表格工具内核”：

| 编号 | 覆盖缺口 | 风险判断 | 应补充的核心内容 |
|---|---|---|---|
| CG-01 | 后端 AI 只读工具及实时结果视图没有完整盘点 | **中**；任务/定义规模增大时产生全表与重复查询 | 补齐 `AiTools` 全部后端工具的 DB/Redis/文件工作量和实时结果视图第二次取数 |
| CG-02 | 确认门前的 `ToolPreflightService`、执行前的复核及目标解析没有专项覆盖 | **中高**；同一动作可能做两遍多项 DB 查询，且含 N+1 | 按四种 `start_*` 与 `create_task` 列出首次预检/批准后复核的 SQL、重复调用和一致性边界 |
| CG-03 | 对话槽位补齐与宿主表单没有进入首轮/续跑的完整同步调用链 | **中**；已有报告低估了 chat/resume 的 DB/Redis 工作 | 补 `TaskSlotResolver`、`HostForm` 子类、定义目录及台账读取的触发条件和规模上界 |
| CG-04 | `frontend/src/utils/sheet-tools.ts` 没有实质性能/生命周期审查 | **高（大表条件触发）**；同步全表物化、Top-N 前全量聚合和撤销副本未被盘点 | 补每个读/写表格工具的行列复杂度、主线程让出、内部内存上界及超时不可中断语义 |

这些缺口**不改变**首轮关于会话删除两趟无界 SCAN、run 同槽 Lua、任务 SSE、作业 runner、Redis 配置等已报告问题；它们是额外需要补审的调用面，不应被写成“删除接口超过 30 秒”的已实测新根因。

## 二、覆盖核对方法及“未点名文件”的处理

按文件名/完整路径反查，151 个生产文件没有被 16 份报告正文点名。其中大部分是 DTO、record、枚举、异常、常量、类型、locale、样式、静态资源配置或纯函数；**未被点名本身不是风险，也不是本文立项理由**。

进一步按运行行为筛选后，只有下列文件形成实质缺口：

| 生产包/文件 | 首轮状态 | 源码核对后的风险判断 |
|---|---|---|
| `ai/tools/AiTools.java` | 16 仅读四类建作业入口，并明确“不据此扩展审查整个 AI 模块” | 有缺口：`list_config_defs`、`list_tasks` 等只读工具也有独立 DB 成本，见 CG-01 |
| `ai/resultview/ListTasksResultViewProvider.java` | 10 只用它证明历史通道读持久化原文 | 有局部缺口：历史分支已覆盖，但实时分支会再次全量查任务，见 CG-01 |
| `ai/gate/ToolPreflightService.java`、`ToolTargetResolver.java` | 16 份报告未点名 | 有缺口：预检与执行前复核的同步 DB 链未盘点，见 CG-02 |
| `ai/form/HostForm.java`、`DefinitionSelectionForm.java`、`TaskTypeForm.java`、`ExportConditionWorkflow.java` | 06 只覆盖 `ConditionsForm` 的草案读取和附件相关文件；上述文件未被点名 | 有缺口：宿主交棒会新增 Redis/DB 调用；纯计算部分不单独报问题，见 CG-03 |
| `ai/slot/TaskSlotResolver.java`、`DefinitionSlotCatalog.java` | 09 只在全部 `while` 附录中盘点同包 `TaskSlotParser`；这两个文件未被点名 | 有缺口：chat/resume 的任务读取和宿主表单的定义目录读取未进入主链，见 CG-03 |
| `frontend/src/utils/sheet-tools.ts` | 12 的定时器盘点无需覆盖它；13 的 FE-08 只具体审查 `SpreadGrid.vue` 与 `utils/excel.ts` | 有缺口：它是独立的三千余行同步计算与撤销状态实现，见 CG-04 |

以下未点名文件经源码核对后**不列为问题**：`ai/config` 的纯解析/运行态 record 与 DB 实体仓储由配置服务报告间接闭合；`ai/feedback` 由反馈接口和迁移报告闭合；`ai/resultview` 的纯数据模型、`ai/slot` 的纯值对象/解析器、`ai/tool` 的常量和纯装饰/校验器没有独立 I/O、线程或周期任务；`YamlToolDefinitionSource` 的类路径流在 try-with-resources 中关闭（`backend/src/main/java/com/example/configmgr/ai/tool/YamlToolDefinitionSource.java:57-88`）；`ReasoningAwareOpenAiChatModel` 只做内存请求投影，没有独立网络、线程或资源所有权（`backend/src/main/java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java:90-157`）。前端未点名的 resultview/wizard 展示组件、薄 API 模块、类型和工具纯函数也未发现需要另立缺口的异步或资源面。

## 三、真实覆盖缺口与应补充内容

### CG-01 — 后端工具只读路径和实时结果视图的 DB 工作量漏审

#### 覆盖事实

16 对 `AiTools.java` 的阅读范围是 `:460-559` 的四类建作业入口，并在范围说明中明确没有扩展到整个 AI 模块。10 对 `ListTasksResultViewProvider` 只用于确认**历史**结果视图读取持久化原文。这两处合理结论都没有覆盖工具的只读方法和**实时**结果视图。

#### 代码事实

1. `list_config_defs` 先调用 `definitionService.findByLevel/findAll`，再在 Java 流上 `limit(50)`；返回前 50 个并不限制数据库读取范围（`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:159-182`）。
2. `list_tasks` 先执行 `taskService.findAll()`，然后才 `limit(50)` 生成文本（`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:272-287`）。`TaskService.findAll()` 直接调用无分页的 `findAllOrdered()`（`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:70-72`；`backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:44-47`）。
3. `Task.items` 是 EAGER 一对多（`backend/src/main/java/com/example/configmgr/task/entity/Task.java:61-67`）。因此这里不能只按 50 条文本行理解存储侧成本。
4. 成功的实时 `list_tasks` 在工具结果写回后还会构造结果视图（`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2929-2938,2955-2964`）。实时 `ListTasksResultViewProvider.build` 又执行一次 `taskService.findAll()`，再取前 50 条（`backend/src/main/java/com/example/configmgr/ai/resultview/ListTasksResultViewProvider.java:76-96`）。
5. 历史分支确实不查库，而走 `fromPersistedText`（同文件 `:99-152`）；所以不能用 10 对历史分支的正确结论替代实时分支审查。

#### 静态推导与风险

一次成功的实时 `list_tasks` 至少包含“工具文本一次全量任务查询 + 结果视图一次全量任务查询”；`list_config_defs` 也有“先全量、后截断”的同类结构。任务数、每任务条目数、定义/字段数增大时，DB 结果水合与 AI 工作线程占用随之增加。这里不是 Redis Cluster 跨槽问题，也不是 HTTP 容器线程直接执行工具的问题；成本发生在 AI 工具执行链。

#### 应补充的审查

- 对 `AiTools` 所有 `@Tool` 方法建立“是否后端直执行 → 调用的服务 → DB/Redis/文件命令数 → 返回/读取上限”的表，而非只列四个建作业方法。
- 将 `list_tasks` 的文本与实时视图放在同一条链计算查询次数，核对能否共享同一次结构化快照；同时保持历史视图只读持久化原文。
- 核对 `list_config_defs`、选项源、槽位目录是否可复用同一分页/轻量投影，避免只限制模型输出而没有限制存储读取。
- **待实测**：在各任务/定义/字段规模下记录 SQL 条数、水合行数、单次工具耗时、视图构造耗时和运行池占用；目前没有实测数据支持具体秒数或 OOM 结论。

### CG-02 — 确认预检、批准后复核与目标校验的重复 DB 链漏审

#### 覆盖事实

16 份报告没有点名 `ToolPreflightService.java` 或 `ToolTargetResolver.java`。既有报告已审 `SpToolCallingManager` 的线程池、确认门、pending 和 Redis 状态，但没有把确认卡生成前与批准执行前的 DB 工作量展开。因此“确认门已审”不能覆盖这一缺口。

#### 代码事实

1. 对支持预检的 CONFIRM 工具，`SpToolCallingManager` 在挂起/确认卡之前同步调用 `preflight.inspect`（`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:967-993`）。
2. 已存预检报告含 `reportHash` 时，批准后的执行前复核会再次调用同一个 `inspect`（同文件 `:4147-4176`）。所以“同一动作最多两趟”是条件性代码路径，不是每个工具无条件执行两遍。
3. 一趟普通 `start_*` 预检会依次读取任务、终态/在途冲突、最近预检等状态（`backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:241-360`），上传检查还读取任务文件（同文件 `:704-728`）。
4. PUBLISH 影响面对每个任务条目分别执行一次暂存行 `countByTaskIdAndDefCode`（同文件 `:998-1017`）；IMPORT/EXPORT 影响面还会再读任务文件列表（同文件 `:1020-1068`）。这是明确的按配置项 N+1 形态。
5. `create_task` 带 `defCodes` 时逐编码调用 `definitionService.findByCode`（同文件 `:408-445`）；带非空 conditions 时又逐编码进入 `WizardActionGuard`（同文件 `:458-520`），后者的定义解析仍调用 `definitionService.findByCode`（`backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:459-465`）。需要补充核对是否存在可合并的重复读取，而不是只数控制器 SQL。
6. 执行前的 `ToolTargetResolver.check` 还会按 taskId 再读一次任务（`backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:114-161`）。它的失败开放语义是既有设计，本次不另判正确性缺陷，但其 DB 调用必须进入成本账。

#### 静态推导与风险

预检不是纯内存确认卡生成：它可以在 AI 工作线程同步执行多组 DB 查询，并在批准后复核时重复。配置项数增加会放大 PUBLISH 计数和 `create_task` 定义校验；慢 DB、连接池等待或锁等待会推迟确认卡和执行结局。是否超过某秒数、是否构成连接池瓶颈均待实测。

#### 应补充的审查

- 分别列出 EXPORT、PRECHECK、IMPORT、PUBLISH、CREATE_TASK 的首次预检与批准后复核 SQL 序列；标出只有特定分支才发生的调用，不能把所有分支成本简单相加。
- 核对同一趟预检中的定义、文件、任务和作业是否重复读取，及首次预检/复核之间必须重新读取的状态与可以复用的不可变投影。
- 检查多次独立查询形成的状态快照与 `reportHash` 的一致性边界；本报告只要求补审，不在没有完整时序证据时另报“已发生脏读”。
- **待实测**：按配置项数、历史作业数、文件数测量 SQL 数、DB 连接占用和确认卡延迟；故障下分别记录首次预检与执行前复核耗时。

### CG-03 — chat/resume 的槽位补齐与宿主表单同步 I/O 未进入完整链路

#### 覆盖事实

首轮对 chat/resume 已详述 Redis 快照、记忆、压缩、工具和 SSE，但没有点名 `TaskSlotResolver`、`DefinitionSlotCatalog`、`HostForm`、`DefinitionSelectionForm`、`TaskTypeForm` 或 `ExportConditionWorkflow`。06 对 `ConditionsForm` 只追踪会话草案读取，不能代表整个宿主表单链已覆盖。

#### 代码事实

1. 首轮在 `ResilientChatService.chat` 中、系统提示和工具披露之前调用 `taskSlotResolver.enrich`（`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:715-719`）；续跑交棒前也调用同一入口（`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1980-1992`）。
2. 只要上下文有 taskId，`TaskSlotResolver` 就同步 `taskService.findById` 读取任务（`backend/src/main/java/com/example/configmgr/ai/slot/TaskSlotResolver.java:72-106,314-324`）。该任务同样带 EAGER items；异常被降级为“槽位缺失”，所以调用链报告不能只写 Redis/模型而漏掉 DB。
3. 续跑的工具批交棒会依次尝试宿主导航、代选、推进和定义表单（`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1494-1527`）。这些分支不是每轮都执行，应按触发条件补账。
4. 定义表单构造调用 `catalog.list()`（`backend/src/main/java/com/example/configmgr/ai/form/DefinitionSelectionForm.java:615-635`）；生产目录实现执行 `definitionService.findAll()` 并逐定义/字段转换、解析 options（`backend/src/main/java/com/example/configmgr/ai/slot/DefinitionSlotCatalog.java:34-79`）。平铺选项数上限是在完整目录读取之后才检查（`DefinitionSelectionForm.java:628-631`）。
5. 宿主导航判定可调用 `createdTaskInRun`，后者读取并反序列化整份台账（`backend/src/main/java/com/example/configmgr/ai/form/HostForm.java:302-331`；`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3967-3982`）。`alreadyIssued` 另读 pending（`HostForm.java:107-127`）。
6. 真正挂起时还依次读取/写入快照、发 suspended/请求帧并收流（`HostForm.java:173-229`）。这些 Redis 原语和 SSE 生命周期已由其它报告覆盖；缺口是它们作为**宿主表单附加调用**没有进入 chat/resume 的总次数账。

#### 静态推导与风险

带任务上下文的普通首轮/续跑会增加同步 DB 读取；满足宿主表单条件时还会增加全量定义读取、pending/台账读取和挂起写。风险主要是 AI 工作线程占用与长台账/大目录放大，不是新增线程、定时器或 Redis 脚本，也没有证据表明它直接解释 DELETE 慢。

#### 应补充的审查

- 在现有 chat/resume 时序图中插入槽位补齐和四个宿主交棒点，标明“无 taskId / 有 taskId / 新建任务 / 已到选择步骤 / 条件待收集”等触发条件。
- 将任务 EAGER items、定义全量目录、pending 和整份台账纳入每跳 DB/Redis 次数与字节量；核对是否能复用当前快照/工具结果，而不是重复全量读。
- 将 `HostForm` 挂起失败后的逐条收敛（`HostForm.java:189-196,240-256`）纳入故障成本，但仍沿用首轮关于 pending/帧/续跑正确性的结论。
- **待实测**：按任务条目、定义/字段、台账大小测量每跳 SQL/Redis 命令、序列化字节和工作线程占用；当前没有实际时延证据。

### CG-04 — 前端表格工具内核的同步计算和内部生命周期漏审

#### 覆盖事实

12 的全量机制表覆盖定时器、SSE、轮询和局部异步等待；`sheet-tools.ts` 没有这些机制，所以不在该表是合理的。13 的 FE-08 覆盖了 `SpreadGrid.vue` 全量读取和 `utils/excel.ts` 校验/OOXML 物化，但没有打开表格工具内核的具体读、聚合、写计划和撤销结构。`frontend-tools.ts` 的预算框架虽已审，仍不能替代其同步 handler 的复杂度审查。

#### 代码事实

1. `frontend-tools.ts` 将 `aggregate_sheet`、`review_cells`、`find_rows` 等直接分派到 `sheet-tools.ts` 的同步函数（`frontend/src/utils/frontend-tools.ts:94-105,1589-1604`）。
2. 通用 `runWithBudget` 的注释和实现明确：`Promise.race` 的计时器无法打断同步阻塞主线程的执行器（同文件 `:488-520,522-550`）。所以 60 秒预算不是这些表格计算的最长执行时间。
3. `scanRows` 虽按 500 行批量 `getArray`，但整段是同步循环，批间没有 `await`、Worker 或事件循环让出（`frontend/src/utils/sheet-tools.ts:540-588`）。批量减少适配器调用次数，不等于消除长任务。
4. `reviewCells` 即使请求 scope 是 selection，也先对 `allRowIndexes(ctx)` 和全部列物化 `rows`，再全量 `validateRows`，最后才按选区过滤 issues（同文件 `:1300-1321`）；展示明细上限只在此后应用（`:1331-1351`）。这是报告 FE-08 未点出的具体“选区仍付整表成本”路径。
5. `aggregateSheet` 在 Top-20 输出之前，为所有组保存每行 row 和 value，再逐组计算指标并排序（同文件 `:1099-1177`）。输出 20 组/2000 字符不是内部堆或 CPU 上界。
6. 写计划最多 100,000 格（同文件 `:33-35,1833-1837,2033-2037`），应用时仍同步完成指纹读取、分块写入和实际落笔对账（`:2717-2766`）。
7. 撤销表使用 WeakMap 且层数最多 3，这是有效出口（同文件 `:2625-2629,2841-2865`）；但每层同时保留 `changes` 副本和 `appliedValues` 数组（`:2849-2859`）。首轮资源生命周期报告没有评估这部分大对象保留。

#### 静态推导与风险

- 大表读/校验/聚合/写入会形成同步主线程工作量；超时回灌也可能等到同步计算结束后才发生。是否可感知、是否错过服务端挂起期限取决于表规模和实际设备，待实测。
- selection 校验存在代码可证的非必要整表扫描；聚合和撤销存在输出上限之外的内部物化。不能把这些说成无限内存增长：写格数和撤销层数已有硬上限，WeakMap 也避免 adapter 被表本身永久强引用。

#### 应补充的审查

- 为 `read_sheet_summary`、`aggregate_sheet`、`inspect_columns`、`review_cells`、`find_rows`、各写计划和 undo 分别列出行×列复杂度、临时数组、适配器调用次数、输出上限与内部上限。
- 单独评估 selection 是否应先限定扫描行，及 Top-N 聚合是否能避免保存每组全部行号/值；正确性扫描与展示截断不能混为一谈。
- 将“同步执行不可被预算计时器打断”接到 AI 回灌时序，核对迟到结局、停止/换页和生命周期作废标记，而不是仅记录 `Promise.race` 有 timer。
- 资源审查需同时记录已有的 WeakMap、三层 FIFO 和 100,000 格上限，以及每层值数组的实际字节风险，避免误报为无界泄漏。
- **待实测**：用不同行×列、组数、错误密度、选区大小和写格数采集浏览器 performance trace、最长任务、堆峰值与回灌时刻；本次没有运行浏览器或压测。

## 四、五类指定主题的闭合核对

### 4.1 Redis 使用、SCAN/KEYS/Lua/跨槽

**结论：没有发现新的 Redis 生产使用文件或 SCAN/Lua 调用面漏审；不需要为“报告没有逐字写出每个脚本变量名”另立缺口。** 本次仍按源码逐项核对如下。

#### Redis 使用面

全局检索 `StringRedisTemplate`、连接/回调、`DefaultRedisScript`、Pub/Sub 容器和 `opsFor*` 后，31 个直接相关生产文件均落在 01～06、15 的专项或跨链阅读范围内。`AiTools`、`ToolPreflightService` 等新增缺口不直接新增 Redis 实现；它们的 Redis 成本来自已审的 `RunStore`、pending、台账等下游。

#### SCAN / KEYS

| 源码调用面 | 实际形态 | 首轮覆盖 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:739-795,817-828`，公用实现 `ai/session/RedisClusterScanSupport.java:48-55,86-147` | 删除会话的 archive 与 staging 两趟逐 master/全游标 SCAN，`maxKeys=0` | 01、02、03、09、10、15 已交叉覆盖 |
| `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:353-414` | SPI/诊断会话枚举，standalone/Cluster 分支 | 09、15 已覆盖，未误算为周期任务 |
| `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:588-696` | 启动 runner-active 清理，逐 master、匹配候选有界 | 06、07、09、15 已覆盖；15 还指出候选上限不约束全部检视页数 |

没有发现生产 Redis 全库 `KEYS` 命令；Lua 中的 `KEYS[n]` 是脚本键参数数组，不能误报为 `KEYS *`。也没有发现需要另补的 BLPOP/BRPOP/XREAD BLOCK 调用面。

#### Lua 与跨槽

静态声明共涉及 14 个文件、54 个脚本/脚本主体。按文件闭合如下；行号是脚本声明区，不代表只阅读该区间的其它业务代码：

| 文件 | 脚本数 | Cluster/成本核对与首轮承接 |
|---|---:|---|
| `ai/run/RunStore.java:386-802` | 17 | run 快照/fence/runstate、ledger/idem、session 索引、活动/序号、同槽续期；02、03、15 承接 |
| `ai/run/LeaseService.java:157-234` | 5 | run 三键同槽 acquire、无 tag 单键资源锁、续/验/放；02、07、15 承接 |
| `ai/run/ClientRequestRegistry.java:100-289` | 4 | 全部单会话 HASH；HSCAN sweeper 与墓碑增长已在 02、14 覆盖 |
| `ai/session/SessionGate.java:133-196` | 4 | 单键取门/续期/释放/判死释放；01、02、08、15 承接 |
| `ai/session/SessionPurgePendingStore.java:121-182` | 4 | 单键 pending CAS；与全局 due ZSET 不假装同事务；01、02、15 承接 |
| `ai/kb/RedisKbCacheStore.java:27-112` | 7 | 全部单键，含代次条件写/删；05、15 承接 |
| `ai/memory/RedisChatMemoryRepository.java:168-240` | 3 | staging 单键、merge 四键同槽、clear 三键同槽；01、03、15 承接 |
| `ai/run/RedisFrameStore.java:156-196,265-446` | 3 | seq/frame 同槽脚本及 xtrim 单键；02、04、15 承接 |
| `ai/session/SessionPurgeGuard.java:65-93` | 2 | 单键 guard；01、15 承接 |
| `ai/history/ConversationTranscriptStore.java:98-159` | 1 | 四键同槽 append/裁剪；03、15 承接 |
| `ai/form/RedisFormAttachmentStore.java:107-114` | 1 | 单键索引 SADD+PEXPIRE；06、15 承接 |
| `ai/observe/SettlementObservationStore.java:75-85` | 1 | 单键观测位；06、15 承接 |
| `ai/run/CreateTaskDraftStore.java:82-92` | 1 | 单键草案写；06、15 承接 |
| `job/service/JobHeartbeatRegistry.java:117-127` | 1 | 单键比对删除；06、15 承接 |

跨槽替代路径也已有点名：附件续期和 run 批量状态使用逐条单键 pipeline，而非跨槽 MGET（`backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:410-475`、`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2124-2146`；06、15）。多键 DEL/RENAME 的同槽前提、无 tag 键不能混入 Lua 的限制均已覆盖。本报告未发现需要新增的 CROSSSLOT 缺口。

### 4.2 所有 Controller

源码 `@RestController` 共 15 个：AI 侧 8 个由 10 全部映射表覆盖，业务/通用侧 7 个由 11 全部映射表覆盖；`GlobalExceptionHandler` 是 advice，不是额外业务 Controller。10 的表已包含容易漏掉的 events 三种映射、取消状态、startup-resume、pending、feedback、option-sources；11 已包含文件模板、下载和打包、作业 issues/diff、健康接口。

**Controller 文件和 HTTP 映射没有漏审。** CG-01～CG-03 指的是 Controller 之外的 AI 工具/交棒内部链，不能误写为又发现了未审 Controller。

### 4.3 线程、定时、周期调用与前端轮询

- 生产后端没有实际 `@Scheduled`/`@EnableScheduling`；`JobWatchdog` 注释中的字样不是注解调用。唯一业务 `@Async` 是 `AsyncJobExecutor`，08、11、16 已覆盖。
- 后端显式 `schedule`、`scheduleAtFixedRate`、`scheduleWithFixedDelay` 分别落在任务 SSE 重试、SSE 写超时、确认门/取消收尾、作业心跳与看门狗、AI keepalive/watchdog/cancel poll、启动恢复、attach 对账、租约、会话门、purge、KB；07～09 的总表和调用链已覆盖这些执行体及关闭出口。
- 前端 `setInterval/setTimeout/requestAnimationFrame/requestIdleCallback`、EventSource、可见性和卸载清理由 12 的 34 行机制表覆盖；13、14 又交叉审查阻塞和生命周期。`sheet-tools.ts` 的缺口是**同步计算而非新增定时器**。
- 因此不建议另补“寻找 `@Scheduled`”或“把所有 `for` 循环当轮询”的冗余报告；应补的是 CG-04 的实际工作量。

### 4.4 上下文传播

08 已覆盖 `ai/tenant` 全部 9 个文件、MDC/locale、AI 运行池/触发池/摘要池/工具池、Redis Pub/Sub 回调、SSE 和后台 runner；01～04、07、15 对记录身份恢复有交叉证据。CG-02/03 的新增调用都发生在既有 AI 工作线程和记录身份作用域内，没有发现它们新建裸线程或另开身份源。

**没有新增上下文传播空白。** 后续补审 CG-02/03 时仍应确认新增 SQL/Redis 调用发生在既定作用域里，不需要另造“未传播租户”的问题。

### 4.5 资源生命周期

14 已覆盖 Redis listener 半初始化、SSE/线程池、附件文件、Lucene、HTTP future、Excel 流、注册表和 Redis 墓碑；16 又覆盖文件、事务和迁移。CG-04 只补首轮未评估的表格内部数组与撤销副本，并明确现有 WeakMap/FIFO/格数上限，不能把它夸大成无界泄漏。

后端本轮缺口文件没有新建独立线程池、连接、文件句柄或调度器；它们的生命周期问题主要是同步 DB/Redis 调用占时，已在 CG-01～CG-03 说明，不另立虚假的资源泄漏项。

## 五、建议的补审交付顺序

1. **先补 CG-04**：一张逐表格工具的复杂度/内存/取消表，加浏览器测量计划；它能直接决定大表下前端预算是否有效。
2. **再补 CG-02**：按五种确认工具分首次预检、批准后复核、目标校验三栏列 SQL 与 N+1，避免把已有确认门 Redis 审查误当成全链完成。
3. **补 CG-01 与 CG-03**：合并为“AI 工具与宿主交棒数据访问清单”，同时覆盖只读工具、实时视图、槽位和宿主表单，避免按类重复写报告。
4. Redis、Controller、调度器和上下文传播仅需在上述新链中引用既有报告，不应为凑数重开全仓同类专项。

所有性能阈值、实际 SQL 数、长任务毫秒数、内存峰值和线上影响均为**待实测**；本文确认的仅是覆盖空白及源码可证的工作量增加路径。
