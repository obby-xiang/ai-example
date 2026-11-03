# 20 — 覆盖缺口补充：AI 工具、确认预检、宿主交棒与表格内核

- 基线：`52b09364`；本次核对时 `HEAD` 同为该基线，`backend/src/main` 与 `frontend/src` 相对基线无差异。
- 输入：同目录 `18-coverage-gap.md` 确认的 CG-01～CG-04；本报告只补这四组，不重开 Redis、Controller、调度器、身份传播等已闭合主题。
- 方法：只读生产源码、既有报告和只读 Git 状态/差异；未修改生产或测试代码，未运行测试、构建、CI、服务或压测，未执行 Git 写操作，未启用子 agent。本文是唯一写入物。
- 证据口径：**代码事实**是源码可直接证明的调用、顺序、数据结构和上限；**静态推导**是由这些事实推出的条件性成本；**待实测**是实际 SQL 语句数、耗时、连接占用、浏览器长任务和堆峰值等运行结果。下文的“查询次数”若未特别说明，指源码中的仓储/服务调用次数，不冒充 ORM 最终 SQL 数。
- 去重边界：报告 16 已覆盖四类建作业的实际写入口，报告 10 已覆盖历史结果视图，报告 06 已覆盖 `CreateTaskDraftStore` 的读写协议，报告 13 的 FE-08 已覆盖 `SpreadGrid`/OOXML 主线程工作。本报告新增的是这些报告没有展开的只读工具、实时视图、预检/复核、宿主交棒和 `sheet-tools.ts` 内核；纯 DTO、record、枚举和纯值对象不立项。

## 一、结论与优先级

| 缺口 | 补充结论 | 风险与首要治理点 |
|---|---|---|
| CG-01 | `list_config_defs`、`list_tasks` 都是先全量取实体、后截取 50 条；成功的实时 `list_tasks` 还会为结果视图再全量取一次 | 中；优先改分页/标量投影，并让文本与实时视图共享一次结构化快照 |
| CG-02 | 预检不是轻量内存判断；同一趟内存在任务、最近预检、文件列表和定义的重复读取，PUBLISH 有逐条目 COUNT，IMPORT 有逐条目文件摘要；批准后又按条件完整复核 | 中高；保留跨批准边界的 freshness 复核和最终执行守卫，先消除单趟内部重复与 N+1 |
| CG-03 | 有 taskId 的首轮会同步读任务；续跑交棒会连续做权威同步、槽位读取和条件表单读取。宿主判定还会重复读整份台账和 pending 组 | 中；以“一次交棒一份事实快照”合并同拍读取，跨用户挂起期仍必须重读 |
| CG-04 | 所有表格扫描/计划/应用均为同步主线程代码；输出 20 行/组不等于内部工作量有界。撤销有 WeakMap、3 层 FIFO 和单计划 100,000 格上限，不是无界泄漏 | 高（大表条件触发）；先修选区校验和聚合内部物化，再做分批让出/协作取消；不能把 `Promise.race` 当中断机制 |

这些结论**不改变**既有报告关于会话删除 SCAN、run/frame、任务 SSE、作业 runner 等问题的归因，也没有实测证据把本文任一新增路径说成 DELETE 超过某秒数的根因。

---

## 二、CG-01：AI 只读工具与实时结果视图的全量/重复 DB 查询

### 2.1 完整调用链与执行线程

**代码事实：**

1. 模型产生 READ/BACKEND 工具调用后，`SpToolCallingManager.executeOnce` 经台账重放、复核/目标和资源锁分派到 `claimAndExecute`，认领胜者才执行工具（`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2663-2713,2808-2856`）。只读工具不是 HTTP 控制器里的另一次同步查询。
2. 配置正超时时，`executeWithTimeout` 把真正的工具体提交到有界 `toolTimeoutExecutor`，调用线程用 `Future.get(timeout)` 等待；超时 `cancel(true)` 只是请求中断，不保证不响应中断的 DB/CPU 工作立即停止（同文件 `:2991-3031`）。禁用该超时时则在调用线程直跑。
3. 工具成功后先写台账/pending，再调用 `resultViewOf`；只有 `ok=true` 且有认领该工具名的 provider 才构造视图（同文件 `:2906-2938,2955-2974`）。因此超时/失败不会多构造一次实时视图。
4. 实时 `list_tasks` 的视图由 `ListTasksResultViewProvider.build` 再调用 `taskService.findAll()`（`backend/src/main/java/com/example/configmgr/ai/resultview/ListTasksResultViewProvider.java:76-96`）。这次取数发生在工具体返回之后；它与工具体内的查询是两次独立服务调用，不是源码注释所称的“同一次查询”。
5. 历史结果视图走 `historical=true → fromPersistedText`，只解析持久化原文，不查任务库（同文件 `:99-152`）。报告 10 对历史分支的结论继续成立，不能把它误改成“历史也重复查库”。

### 2.2 全部后端只读工具的数据访问账

| 工具/触发 | 服务与存储链 | 代码事实：读取范围与输出上限 | 判断 |
|---|---|---|---|
| `list_config_defs(level?)` | `AiTools.listConfigDefs` → `DefinitionService.findByLevel/findAll` → `ConfigDefinitionRepository.findByLevelOrdered/findAllOrdered`（`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:154-182`；`backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java:38-55`；`backend/src/main/java/com/example/configmgr/definition/repo/ConfigDefinitionRepository.java:15-19`） | 无 level 读全部定义，有 level 读该层全部定义；Java 流最后才 `limit(50)`。`ConfigDefinition.fields` 为 EAGER（`definition/entity/ConfigDefinition.java:36-44`），字段数又被用于输出 | **问题**：50 条只限制模型文本，不限制 DB 读取和实体/字段水合 |
| `get_config_def(code)` | `AiTools.getConfigDef` → `definitionService.findByCode` → 单编码仓储查询（`AiTools.java:194-226`；`DefinitionService.java:53-55`） | 读一个定义及其字段，并输出全部字段详情 | 定点读取有业务必要；本缺口不把它夸大为全表问题 |
| `list_tasks()` | `AiTools.listTasks` → `TaskService.findAll` → `TaskRepository.findAllOrdered`（`AiTools.java:272-287`；`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:70-72`；`backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:45-47`） | 全量任务按时间排序后才取 50 条；`Task.items` 为 EAGER，但文本只用任务标量（`backend/src/main/java/com/example/configmgr/task/entity/Task.java:61-67`） | **问题**：全表 + 非所需明细水合；成功实时视图再做一遍 |
| `get_workspace_state(taskId?)` | 工具上下文/参数决定 taskId → `workspaceState` → `taskService.findById`（`AiTools.java:290-319`） | 无 taskId 不查库；有 taskId 读任务和条目。`findById` 除 `taskRepository.findById` 外还显式调用 `taskItemRepository.findByTaskIdOrderBySortOrder`（`TaskService.java:93-97`） | 条目确实用于文本；是每调用一次的定点成本，不是全表问题。EAGER 与显式条目读取最终产生几条 SQL 待实测 |
| `check_job_status(jobId)` | `AiTools.checkJobStatus` → `JobService.findById` → `JobRepository.findById`（`AiTools.java:322-333`；`backend/src/main/java/com/example/configmgr/job/service/JobService.java:270-273`） | 读一个作业，只输出标量；`Job.items` 却为 EAGER（`backend/src/main/java/com/example/configmgr/job/entity/Job.java:42-47`） | 非全表，但可用标量投影避免无用 `job_items` 水合 |

其余 `@Tool` 中，`create_task` 与四个 `start_*` 是写/确认工具，转入 CG-02；前端工具方法体均为哨兵桩，正常生产链不执行后端 DB 工作（`AiTools.java:546-564,1008-1180,1183-1302`），不列为“每个工具都查库”。

### 2.3 根因、影响与最佳修复

**根因（代码事实）**：输出层有 50 条限制，但仓储 API 只有无分页实体列表；实时视图为了得到结构化行而重新走服务层，没有接收工具执行时的任务快照。EAGER 关联又使“只显示标量”与“实际水合内容”不一致。

**静态推导与影响面：**

- 设任务数为 T、定义数为 D；`list_tasks` 文本与实时视图各读取全部 T，`list_config_defs` 读取全部 D 或该层全集。ORM 对 EAGER 集合采用 join、二次查询还是其它策略，以及最终 SQL 数，均**待实测**，不能仅凭注解写死为某个 N+1 数字。
- 成本落在 AI 工具执行/结算链，占用 DB 连接和 AI 工作线程；结果视图失败会被降级为无视图，不会把成功工具改成失败（`ListTasksResultViewProvider.java:81-96`）。
- standalone/Cluster 的应用实例共享同一 DB 时，多实例并发会线性增加相同查询；这不是 Redis Cluster 跨槽问题。工具超时后若底层调用不响应中断， DB 工作仍可能继续，具体取决于驱动与在飞语句，**待实测**。

**最佳修复与取舍：**

1. 为 `list_tasks` 增加稳定排序的分页标量投影（`createdAt DESC,id DESC`）及单独 `count`，为 `list_config_defs` 增加同排序、可选 level 的分页摘要投影；不要在 Java 里用 `limit` 假装 DB 分页。
2. 在一次成功工具调用内生成不可变 `ListTasksSnapshot { total, rows }`，文本与实时视图从同一快照分别投影。这样还消除两次独立查询造成的文本/卡面时间窗；历史视图仍只读持久化原文。
3. `check_job_status` 用作业标量投影；`get_workspace_state` 保留条目读取，因为输出确实需要它。是否连 `TaskService.findById` 的显式条目查询也合并，应以实际 ORM SQL 和实体使用面决定，不做全局关联盲改。
4. 取舍：分页 + count 是两次定向 DB 工作而非一次全量水合，通常更小，但精确总数仍有 count 成本；共享快照需要调整工具结果与 provider 的内部接口，不能为省事重新解析文本文档，也不能让历史卡面改读“今天的库”。

**后续验证（不执行）：**在 0/50/51/大批任务、定义和字段规模下开启 SQL/水合指标，分别记录工具体与视图构造的逻辑查询、实际 SQL、返回行数、耗时和连接占用；验证文本与实时视图同一快照、历史视图零 DB；再在多实例并发和工具超时/取消下检查是否仍有在飞查询。

---

## 三、CG-02：确认前预检、批准后复核与目标校验的重复 DB/N+1

### 3.1 总体调用链和复核触发条件

**代码事实：**

1. 对支持预检的 CONFIRM 工具，只有未武装预算、未超工具步数且通过当前 scope 时，`SpToolCallingManager` 才在确认卡/挂起前同步调用 `preflight.inspect`；异常按不可用阻断，失败报告不进门（`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:967-994`）。该阶段在 `executeWithTimeout` 之前，**不受工具体 exec-timeout 包裹**。
2. 批准后进入 `executeOnce`：台账重放 → `verifyApprovedReport` → `ToolTargetResolver.check` → 资源锁 → 认领 → 真正工具体（同文件 `:2663-2713`）。
3. 只有 CONFIRM、支持预检且已存报告含非空 `reportHash` 时，`verifyApprovedReport` 才再次调用同一个 `inspect`；旧条目无 hash 不复核，指纹不符/复核失败则不执行（同文件 `:4137-4176`）。所以“两趟预检”是正常新协议批准路径的条件性事实，不是所有调用无条件两遍。
4. 四个 `start_*` 还会做目标校验；`create_task` 没有既有 taskId，不走 `ToolTargetResolver`（`backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:29-38,72-80,114-161`）。
5. 最终 `JobService.createAndStart` 仍在任务行锁和事务内执行终态、在途、runner 与预检守卫，然后才建作业（`backend/src/main/java/com/example/configmgr/job/service/JobService.java:158-231`）。这是权威执行边界，不能因前面已预检就删除。

### 3.2 四种 `start_*`：单趟 `inspect` 的完整读取序列

设任务条目数为 N；以下是**任务存在且各分支继续执行时**的调用账。任务不存在、任务已 COMPLETED、预检缺失/未通过、文件摘要不符等会改变后续分支，不能把所有上限简单当成每次固定 SQL 数。

**公共前缀（`ToolPreflightService.inspect`，`backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:241-360`）：**

1. `jobService.findTask(taskId)` → `taskService.findByIdRaw`：第一次任务读取（`JobService.java:117-124`；`TaskService.java:120-126`）。
2. `finalizedOf` → `jobService.finalizedConflict` → 第二次 `findByIdRaw`；若任务 COMPLETED，再查一次最终类型成功作业（`ToolPreflightService.java:269-296,1182-1188`；`JobService.java:80-124`）。
3. `jobService.inFlightConflict`：一次同类在途作业查询（`ToolPreflightService.java:299-314`；`JobService.java:321-329`）。
4. `jobService.latestPrecheck`：一次最近 PRECHECK 查询；EXPORT/PRECHECK 虽不靠既往预检放行，也会执行这次读取用于报告事实（`ToolPreflightService.java:316-339`；`JobService.java:380-383`）。
5. `itemsCheck` 复用已读任务的 items；目标一致、步骤、条件摘要和 publishMode 主要是已有对象/上下文计算，不另查库（`ToolPreflightService.java:341-357,687-701,731-834,866-970`）。但 `findByIdRaw` 的注释声称“不水合 items”，实体却标 EAGER；实际关联 SQL 待实测，不能以方法名替代实体映射事实。

**按工具增加的读取：**

| 工具 | 单趟额外链 | 重复/放大点 |
|---|---|---|
| `start_export` | `uploadCheck` 对 EXPORT 不读文件；`impactOf → exportImpact → summedRows(EXPORT)` 读一次 `taskFileRepository.findByTaskId`（`ToolPreflightService.java:704-728,978-995,1036-1068`） | 无上传检查重复；文件清单用于影响行数。步骤与每条目条件是内存扫描 |
| `start_precheck` | `uploadCheck` 读一次全部任务文件；影响面仅按 N 生成文本，不再读文件（同文件 `:341-343,704-728,987-995`） | 仍执行公共最近预检查询，即使本工具自身就是 PRECHECK |
| `start_import` | `precheckConflict` 内再次 `latestPrecheck`；若最近预检 COMPLETED 且无错误，则读取任务条目，逐条目查当前 UPLOAD 文件并同步 `fileStorageService.digest`，直到完成或首个失败；`uploadCheck` 再读全部文件，`uploadImpact` 又读一次全部文件（`ToolPreflightService.java:335-343,1020-1033`；`JobService.java:358-431`） | **一趟内最近预检查两次、文件清单两次；另有逐条目文件查询 + 文件内容 SHA-256。** 批准后复核会重跑，实际建作业还会再做权威指纹守卫 |
| `start_publish` | `precheckConflict` 再次 `latestPrecheck`；`uploadCheck` 读全部任务文件；`stagingImpact` 对每个任务条目调用一次 `countByTaskIdAndDefCode`（`ToolPreflightService.java:335-343,998-1017`；`backend/src/main/java/com/example/configmgr/data/repo/ConfigStagingRowRepository.java:11-15`） | **最近预检查两次 + 明确的 N 次 COUNT。** PUBLISH 不做 IMPORT 的文件摘要链，不能把二者成本合并计算 |

文件清单仓储为 `findByTaskId`，IMPORT 指纹另用 `findByTaskIdAndDefCodeAndFileType`（`backend/src/main/java/com/example/configmgr/task/repo/TaskFileRepository.java:8-12`）。文件摘要读取的是当前路径内容，不是只读 DB 元数据；其实际字节量随文件大小增长。

### 3.3 批准后复核、目标校验与最终执行的新增账

- **复核**：上述单趟序列在满足 `reportHash` 条件时整体再执行一次。IMPORT 因而可能在“首次预检 + 批准复核 + 实际 `createAndStart` 指纹守卫”三个时点读取并摘要当前文件；前两次是展示/新鲜度链，最后一次是权威写守卫。
- **目标校验**：四个 `start_*` 在复核 hash 相同后，`ToolTargetResolver.check` 先用上下文做零 I/O 一致性判断，再 `taskRepository.findById(taskId)` 读任务和 EAGER items；当前四个公开工具不带 defCode，预留的 defCode 分支不触发（`ToolTargetResolver.java:114-179`）。仓储异常按既定 fail-open 放行，本文只计成本，不另报正确性缺陷。
- **最终执行**：`createAndStart` 再锁任务、读终态/在途/runner/预检状态（`JobService.java:158-231,281-431`）。这些读取与 Redis runner 协议已由报告 06/16 覆盖；此处只说明它们仍在预检之后，避免把“删掉重复预检”误当可行优化。

### 3.4 `create_task` 的专用链

1. `inspectCreate` 在 `independent != true`、上下文有 taskId 且符合续建条件时，经 `continuationTask → jobService.findTask` 读当前任务；全新独立创建不读（`ToolPreflightService.java:386-400,576-589`）。
2. `defCodes` 形状合法时，逐编码调用 `definitionService.findByCode`；解析器已去重，因此 N 是唯一编码数（同文件 `:408-445,639-683`）。每个定义仍带 EAGER fields。
3. EXPORT 的非空 `conditions` 还要求合法 defCodes，并逐 defCode 构造 `WizardActionGuard` 探测；`validateCondition → findDefinition → definitionService.findByCode` 又读一次同编码定义及字段（同文件 `:458-520`；`backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:287-334,357-397,453-470`）。所以“N 次存在性检查 + 非空条件再 N 次定义检查”是同一趟内的可合并重复。
4. 空 `{}` 条件不走上述逐定义 guard；IMPORT 传 conditions、缺 conditions 的 EXPORT、非法形状等走各自拒绝分支，不能计入正常成功路径的额外 N。
5. 满足 hash 条件时批准后重跑同一 `inspectCreate`；`create_task` 不做 `ToolTargetResolver`，真正幂等创建仍由 `TaskService.createIdempotent` 负责。

### 3.5 根因、并发影响与一致性边界

**根因：**为保证“确认卡看到当前事实”和“批准时事实未变”，设计上需要两个时点的读取；但单趟实现没有共享任务、最近预检、文件和定义快照，又把影响面计数写成逐条目仓储调用。这是“必要的跨时点复核”与“可消除的同趟重复”叠加，不能统称其为多余查询。

**集群/多实例/多线程影响（静态推导）：**

- 预检各查询没有覆盖整趟的共同事务/锁，多个应用实例和其它写者可并发改变任务、作业、文件与暂存行；`reportHash` 证明的是两份报告的结构化事实相同，不证明单趟读取来自一个原子 DB 快照。最终任务行锁和 `createAndStart` 守卫仍是必要兜底。
- 多实例/多线程同时发起相同动作会叠加 DB 和文件摘要工作；Cluster 拓扑不减少 DB 调用。文件位于共享存储时，各实例还可能读同一大文件。真实锁等待、连接池饱和和确认卡延迟均**待实测**。
- 首次预检和批准复核都在 AI 驱动线程同步执行，不在工具体超时笼内；慢 DB/文件会推迟确认卡或执行结局。不能宣称已有 exec-timeout 能保证这段按时返回。

### 3.6 最佳修复、取舍与影响面

1. **单趟共享快照**：在一次 `inspect` 内复用任务、latestPrecheck、items 和文件清单；`precheckConflict/latestPrecheck` 提供接受已读快照的内部口径，避免 IMPORT/PUBLISH 再查一次最近预检。跨批准边界仍重新取快照。
2. **消除 N+1**：PUBLISH 改为按 taskId 对所需 defCodes 做一次 `GROUP BY defCode` 计数并补零；`create_task` 用一次 `findAllByCode` 建 code→definition 映射，同时供存在性和条件字段校验。保持缺失编码逐项报告和稳定顺序。
3. **降低目标校验水合**：`ToolTargetResolver` 只需当前公开工具的 id/type/status，改标量投影；不要为类型判断读 EAGER items。若选择复用刚复核的任务事实，也必须保留独立/旧数据路径的目标校验。
4. **文件成本**：同趟复用文件元数据；只有与不可变 storageKey + sha256/size 快照绑定时才复用摘要，绝不能按路径跨批准缓存。文件不可变快照与最终执行一致性由既有 P0-15 承接，本文不重复立正确性项。
5. **取舍**：批查询会增加 IN/GROUP BY 实现和空集处理，但比逐定义/逐条目不引入新协议；共享快照需要重构 `JobService` 只读守卫的参数，而不是复制判据。不要用一个长 DB 事务包住文件 SHA-256 来制造“快照一致”，那会扩大连接/锁占用。

影响文件集中在 `ToolPreflightService`、`ToolTargetResolver`、`JobService` 的只读守卫、相关 repository 投影，以及 `WizardActionGuard` 的批量定义输入；实际建作业锁、确认门、pending、reportHash 协议和前端卡面无需改变。

### 3.7 后续验证（不执行）

- 五种工具分别覆盖首次预检失败/通过、旧条目无 hash、批准复核同 hash/变 hash/异常、目标不合法和最终守卫冲突；记录每个阶段的实际 SQL 与文件读取，不只测总耗时。
- 对 N=0/1/多条目验证 PUBLISH 聚合 COUNT 与逐条结果一致，对重复/缺失 defCodes、空/非空 conditions 验证批定义校验完全等价。
- IMPORT 在首次、复核和最终执行之间分别替换同路径不同内容文件，确认不缓存旧摘要；多实例共享 DB/存储下制造并发变更，验证最终守卫仍 fail-closed。
- 测量确认卡延迟、批准到执行延迟、DB 连接占用、文件字节和线程占用；这些当前均为待实测，不预设具体毫秒阈值。

---

## 四、CG-03：chat/resume 槽位补齐与宿主表单的同步 DB/Redis 调用

### 4.1 首轮与续跑的槽位链

**首轮代码事实：**`ResilientChatService.chat` 在系统提示和工具披露之前调用 `taskSlotResolver.enrich`（`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:681-725`）。`enrich → resolve → task(ctx)`：只要上下文有 taskId，就同步 `taskService.findById`；无 taskId 不读 DB，异常降级为槽位缺失（`backend/src/main/java/com/example/configmgr/ai/slot/TaskSlotResolver.java:72-126,314-324`）。该读取包含任务条目并用于配置/条件槽位，属于真实新增 DB 工作。

`TaskSlotResolver` 虽持有 `DefinitionCatalog`，当前 `resolve/enrich` 不调用 `definitions()`；因此**不能给每次 chat/resume 多算一次全量定义目录**。目录读取发生在后面的宿主定义表单特定分支。

**续跑代码事实：**交棒前 `syncAuthorityForHandoff` 固定执行 `syncTaskBeforeHandoff → recomputeSlotsBeforeHandoff`（`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1946-1995`）：

1. `RunContextRefresher.syncTask` 有 taskId 时经 `findByIdRaw` 读 type/step/status（`backend/src/main/java/com/example/configmgr/ai/gate/RunContextRefresher.java:168-186,430-447`）。
2. 随后的 `TaskSlotResolver.enrich` 又用 `findById` 读同一任务和条目。
3. 因而同一交棒点有两次任务服务读取；若继续走到 `ConditionsForm.parkIfDueAtQueryCond`，它还会先 `readTask → findById`，再判断 EXPORT/QUERY_COND/条目（`backend/src/main/java/com/example/configmgr/ai/form/ConditionsForm.java:278-297,1355-1366`）。即使最终不是条件表单，这次读取也已发生。
4. 无 taskId 时这些任务读取均跳过；同步/槽位异常按各自既有的保留旧值或缺槽位策略处理，不把增强失败直接等同整轮失败。

### 4.2 续跑四个宿主交棒点与触发条件

`ResumeService` 在整批结算、权威同步和工作区重写之后，依次尝试导航、代选、推进、定义选择表；随后才尝试条件表（`ResumeService.java:1450-1534`）。任一 `park*` 返回 true，本拍即停止后续模型驱动，所以这些成本是条件分支，不应全部相加为固定开销。

| 交棒点 | 触发与同步 I/O | 主要证据 |
|---|---|---|
| 宿主导航 `parkNavigateIfDue` | park 开启且有 settledBatch；先 `createdTaskInRun` 读整份台账，仅本批新建成功或首次宿主导航刚失败等条件继续；随后 `alreadyIssued` 读 pending 组，真正导航则进入共用 park | `backend/src/main/java/com/example/configmgr/ai/form/DefinitionSelectionForm.java:151-241` |
| 宿主代选 `parkSelectDefinitionsIfDue` | 再次 `createdTaskInRun`；新建任务已到 EXPORT/SELECT_DEFS、配置已知、有导航或页面事实才继续；`navigatedToTaskInRun` 和 `alreadyIssued` 可各读一次 pending 组 | 同文件 `:244-301,357-401` |
| 宿主推进 `parkAdvanceIfDue` | 不读台账；EXPORT/SELECT_DEFS、配置已知、本批选择成功且未推进时才 `alreadyIssued` 并 park | 同文件 `:523-565` |
| 平铺定义表 `parkIfDue` | 第三次可能调用 `createdTaskInRun`；新建任务、页面归属、选择步骤、配置未知且未处理/未挂过后，才 `catalog.list()` 建表 | 同文件 `:490-520,568-635` |
| 条件表/草案 `ConditionsForm` | 有 taskId 即先读任务；EXPORT + QUERY_COND + 有条目后读草案/反馈 pending，必要时读会话草案、逐配置校验并 park | `ConditionsForm.java:278-350,541-615,811-847,1166-1204` |

`ExportConditionWorkflow.plan(task)` 只对已给 Task 做条件三态和命令规划，没有仓储/Redis 调用；其 `TaskService` 字段不参与该实现（`backend/src/main/java/com/example/configmgr/ai/form/ExportConditionWorkflow.java:41-105`）。不应为纯计算另立 I/O 问题。

`TaskTypeForm.parkIfDue` 当前恒为 false，首轮这个兼容入口没有 I/O（`backend/src/main/java/com/example/configmgr/ai/form/TaskTypeForm.java:80-104`）。真正活跃的是模型已发 `create_task` 但 type 缺失/非法时，`SpToolCallingManager` 调用 `parkIfCreateTaskMissingType`；它先 `alreadyIssued`，再构造并挂起类型表（`SpToolCallingManager.java:920-938`；`TaskTypeForm.java:106-145`）。

### 4.3 Redis 读取的真实粒度

- `createdTaskInRun → RunStore.ledger` 是 `LRANGE 0 -1` 后逐条 JSON 反序列化整份台账（`backend/src/main/java/com/example/configmgr/ai/form/HostForm.java:302-331`；`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3967-3982`）。在一次未提前挂起的续跑交棒中，导航、代选、定义表三个入口最多各做一次，形成三份完整读取/解析，而不是一次共享判断。
- `alreadyIssued/navigatedToTaskInRun/draftFacts/feedbackOf` 使用 `pendingsLenient` 或条件候选的 `pendings`。`pendingsLenient → readPendings` 不只是单条 HGET：它读取 pending 全 HASH，并再读 request/decision 标记，逐条反序列化合并（`RunStore.java:3363-3393`）。因此报告中的“一次 pending 组读取”不能被理解成总只有一次 Redis command。
- 条件候选下发还会逐 defCode 调 `alreadyIssued`，并用 `WizardActionGuard` 做定义校验（`ConditionsForm.java:541-615`）；这是特定新建+非空条件分支的附加 pending/DB 放大。会话级 `CreateTaskDraftStore.read` 的快照归属读取和 HGET 已由报告 06 覆盖，本文只把它纳入交棒总链，不重报其协议问题。

### 4.4 真正挂起时的完整同步链

`HostForm.parkAll` 的代码顺序为（`HostForm.java:173-229`）：

1. `store.get(runId)` 读当前快照以保留正文/元数据；
2. `store.saveSuspended`：逐 pending `putPending`，再读一次快照、序列化历史并保存 SUSPENDED 快照；`putPending` 自身为 HSET + EXPIRE（`RunStore.java:3292-3338,3340-3344`）；
3. 发 `suspended` 帧；
4. `ConfirmGate.publishRequestsForParkedBatch` 逐条发布请求帧并写相应请求状态；
5. `completeSubscriptions` 收流，等待用户回灌后的既有 resume。

若写挂起失败，`HostForm` 对每条 pending 再尽力 `putPending` 收敛；失败成本随本批条目数增加（`HostForm.java:189-196,240-256`）。这些帧、pending、续跑和 SSE 的正确性已由既有报告覆盖，本文新增的是它们作为宿主表单附加调用的次数账，不另造双驱动/丢帧结论。

### 4.5 根因、影响与最佳修复

**根因：**槽位、权威同步和各宿主表单分别独立取数，没有“本次交棒事实”对象；确定性去重又都通过完整 pending/台账重读实现。目录实现为每次 `list()` 全量 `definitionService.findAll`，逐定义/字段转换并解析 options（`backend/src/main/java/com/example/configmgr/ai/slot/DefinitionSlotCatalog.java:34-79`），而平铺选项上限在完整读取后才检查（`DefinitionSelectionForm.java:615-635`）。

**静态推导与影响面：**

- 有任务上下文时，DB 成本随任务条目水合增加；新建任务链还随台账长度、pending 条数、定义/字段数增加。挂起期跨越用户等待后必须读新事实，但同一交棒点的三次任务读取和三份台账没有额外 freshness 收益。
- 这些调用发生在既有 chat/resume 同步执行链；续跑准备本身已有报告 10/P1-03 的 HTTP 受理前工作问题，本项会扩大那段工作，但不新建线程、定时器或 Redis 脚本。
- 单实例多线程/多实例共享 DB 与 Redis 时，并发交棒会叠加读取；run 租约/fence 的既有所有权不因本项改变。上述键面都是单 run/单会话读取，没有新增 CROSSSLOT。实际 SQL、Redis command 数、字节和时延待实测。

**最佳修复与取舍：**

1. 在一次 handoff 建立 `HandoffFacts`：一份完整 Task（含 items）、一份按相应一致性要求读取的 pending 组、一份台账和按需惰性定义目录。权威 type/step/status 与槽位从同一 Task 投影，条件表复用它；导航/代选/定义表复用台账与 pending。
2. 首轮保留无 taskId 零 DB；续跑用一次完整 Task 同时完成 `syncTask` 和 `enrich`，避免先 raw 再 full。跨工具批、跨用户挂起和下一次 resume 必须重新建 facts，不做全局长时间缓存。
3. 保留强读/lenient 读语义差异：条件候选的强一致 `pendings` 不能被普通 lenient 快照悄悄替代；读取失败应按消费者现有策略处理，而不是共享一份“空表”掩盖故障。
4. 定义目录只在所有表单触发条件满足后惰性读取一次；若长期需要跨 handoff 缓存，应先有定义版本/失效协议。仅把 50 条限制下推到列表会减少读取，但必须同时保留“超平铺上限则不下发残缺表单”的总数判断。
5. 取舍：`HandoffFacts` 会增加内部接口重构，但无需改帧或用户协议；过早做全局长缓存虽能再减查询，却会引入陈旧定义/任务和跨实例失效问题，不是首选。

### 4.6 后续验证（不执行）

按“无 taskId、有 taskId 普通续跑、新建后导航、已知配置代选、选择成功推进、未知配置定义表、QUERY_COND 条件草案/表单”分别记录 DB/Redis 调用和字节；断言同跳只读一份 Task/台账/pending，跨挂起仍能看见手工改步骤和定义变化。再覆盖强读失败、lenient 降级、park 写失败、多实例接管和重复 resume，确认不会因共享快照改变现有失败语义或重复发卡。

---

## 五、CG-04：`sheet-tools.ts` 的同步全表计算、内部物化、撤销副本与不可中断预算

### 5.1 两条完整前端调用链

**即时读/建议链（代码事实）：**

`frontend_tool_request` → store 对非写预览工具排队 → `executeFrontendTool` → `dispatchFrontendTool` → `runSheetTool` → `resolveSheetContext().then(...)` → 同步调用 `readSheetSummary/aggregateSheet/inspectColumns/reviewCells/findRows/normalizeSuggest`（`frontend/src/utils/frontend-tools.ts:448-460,1571-1640,2036-2044`）。外层 `runWithBudget` 用 `Promise.race` 包执行 Promise 和 timer（同文件 `:488-550`）。

**写计划/应用/撤销链（代码事实）：**

- 帧到达后 `needsSheetPreview` 为 true 的写工具不走上述即时执行器，而进 `openWritePreview`；解析上下文后同步 `planSheetWithLevel → planReplace/planUpdate/planNormalize/planUndo`，建卡且暂不回灌（`frontend/src/stores/ai.ts:8400-8489`；`frontend/src/utils/sheet-tools.ts:3156-3170`）。
- 切档由 `setPreviewLevel → replanSheet` 同步重算（`stores/ai.ts:8895-8952`；`sheet-tools.ts:3173-3223`）。
- 用户点应用后 `confirmWritePreview` 可在计数档先同步 `replanSheet` 物化，再同步 `applySheetPlan/applyUndoPlan`，最后才回灌（`stores/ai.ts:9003-9115`）。
- 因此写计划、切档、应用和撤销**不进入 `runWithBudget`**；不能只说“它们会被 60 秒超时但无法中断”，准确事实是这条人围栏特判链没有该预算。

### 5.2 公共扫描器的成本与取消边界

`scanRows` 按连续行段、连续列段和每 500 行批次调用同步 `getArray`，先建 `span × columns.length` 的批缓冲，再逐格访问（`sheet-tools.ts:537-588`）。它减少适配器调用次数，却没有 `await`、Worker、AbortSignal 或事件循环让出；回调 false 只提供业务层提前停止，且在读批边界生效。

`resolveScope` 无论最终 scope 是什么都先 `allRowIndexes(ctx)` 物化 R 个行号；有 predicate 时再对目标行按全部列扫描并保留命中行（同文件 `:595-737`）。设 R=全表行数、C=字段/列数、S=解析后范围行数、X=实际扫描列数、G=组数、M=指标数、W=变更格数、U=栈顶撤销格数。下列复杂度是源码工作量的静态上界表达，不是实测时间。

### 5.3 读工具与建议工具逐项账

| 工具 | 同步工作与主要临时对象 | 输出限制不是内部限制的地方 |
|---|---|---|
| `read_sheet_summary` | scope 解析 O(R) 行号，可能的谓词扫描；随后空值统计扫全 R×投影列、主键判重再扫全 R×键列并保留唯一键 Set，最后才读采样（`sheet-tools.ts:874-975`） | 默认 projection ≤5、采样 ≤20/2000 字符；空值/判重仍整表，唯一键 Set 为 O(R)。选区只影响采样，不影响两项全表摘要 |
| `aggregate_sheet` | 无分组也先把 S 个值读成数组再算指标；分组时每组保存所有 `rows[]` 与 `values[]`，随后逐组逐指标计算、排序，再取 Top-N（同文件 `:985-1017,1099-1187`） | 总临时行/值引用 O(S)，组 O(G)，指标中的 map/filter/Set 还会产生额外 O(S) 临时对象；20 组/2000 字符只限制返回。Top-N 排序在全部指标之后 |
| `inspect_columns` | 不读业务行；物化表头，字段×列匹配及 extras×fields 建议查找，约 O(C×字段数)（同文件 `:1238-1295`） | 主要是列级成本；候选只显示少量不代表匹配循环被省去。不应把它误列为全行扫描 |
| `review_cells` | scope 为 selection 也先全量行号；随后按全部 R×C 建 `Record` 行数组，`validateRows` 再全量校验并积累 issues，最后才按选区过滤（同文件 `:1300-1352`；`frontend/src/utils/excel.ts:175-212`） | **明确的非必要整表物化面**：行对象 O(RC)，issues 最坏 O(RC)，键 Set O(R)；20 条明细只在全部校验后截取 |
| `find_rows` | 为得到真实命中总数，同步扫描 S×X；保存全部命中行 O(H)，但文本采样只保留 limit 行（`sheet-tools.ts:1365-1467`） | 全扫本身是“共 N 行”的必要语义，不应误报为多余；风险是无让出，且谓词可能先做一次全列扫描 |
| `normalize_column(commit=false)` | `computeNormalize` 对每列扫描并物化全部可执行规则 changes；space/number/date 规则又各自整范围扫描（同文件 `:2406-2521`） | 建议档没有写入，但仍可保留 O(S×列数) 个 change；样例只取 5 个发生在物化之后。`commit=true` 的计划成本见下表 |

### 5.4 写计划、重算与应用的内部物化

| 阶段 | 代码事实与复杂度 | 已有上限及其盲区 |
|---|---|---|
| `planReplace` | count 档只留 3 个 change，但保留全部命中行；sample/full 档都物化全部 W 个 change。`makePlan` 还保存 targetRows 并逐目标行读键列生成 fingerprint（`sheet-tools.ts:1967-2062,1627-1688`） | count 档可在超过 100,000 格后于读批边界停扫；明细档直到完整扫描后才检查 `changes.length`，所以拒绝前已经物化超限对象 |
| `planUpdate` | 每个 set 项分别读列；template 另存 S×引用列矩阵，fillDown 为寻找上方值会读全 R 而非只读选区。count 档保留 perRowHits/命中行，明细档保留全部 changes（同文件 `:2120-2320`） | set ≤5；但多列重复扫描仍在。count 档按 set 项后才检查 hitTotal，明细档也到最后才拒超上限 |
| `planNormalize` | 每列分别扫描；count 档保留 perRowHits，明细档全部 changes；分布另读一组值（同文件 `:2523-2592`） | count 档的溢出检查在 `computeNormalize` 完整扫描之后，不保证提前中止；100,000 是最终计划限制，不是所有临时数组的统一硬顶 |
| 分布统计 | `countRow` 对遇到的每个组都 `groups.set`，超过 20 只置 `truncated=true`，并不淘汰后续组（同文件 `:1809-1837`） | 注释中的“内部统计上限 20”不是实际 Map 容量上限；内存仍随 G 增长。其它排序/展示再取前 5 |
| `replanSheet` | 先重读目标行指纹，再用原 args 完整重跑计划；计数档应用到 sample-diff 时也走这条链（同文件 `:3182-3223`；`stores/ai.ts:9050-9073`） | 去重/防旧预览是必要正确性工作，但它是新的完整同步计算，不是复用 count 结果 |
| `applySheetPlan` | 指纹读取 → `changesToUpdates` 全量新数组 → 分块数组 → 同步 writeCells → appliedUpdates → `selectAppliedChanges` 建 Map/结果 → 撤销登记/上色（`sheet-tools.ts:2717-2807,2815-2886`） | 单计划 ≤100,000 格是有价值的上限；同一时刻仍有多个 O(W) 数组/Map。分块没有 await，不能视为响应性保证 |

`sample-diff` 与 `full-diff` 在内核里都以 `detail='full'` 保存全量 changes；差别主要是展示档，而不是内部计算量。输出“最多 20 行”或卡面分页不改变这一点。

### 5.5 撤销副本与生命周期：既有限制和真实成本

**代码事实：**

1. `undoStack` 是以 adapter 为弱键的 `WeakMap<SheetAdapter, SheetUndoEntry[]>`，每层最多保存实际写入的 changes，栈最多 3 层并 FIFO 淘汰（`sheet-tools.ts:2625-2629,2841-2865`）。adapter 不再被外部引用时，WeakMap 不阻止其回收；不能称为永久强引用泄漏。
2. 每层 `changes: [...changes]` 是新的数组，但 SheetChange 对象引用与原计划共享；`appliedValues` 另建等长数组保存 newValue 引用（同文件 `:2849-2859`）。这不是对单元格值的深拷贝，却确实保留每格 old/new 对象引用和两份数组槽位，三层最多保留三批各 ≤100,000 格的撤销数据。
3. `appliedValues` 是撤销冲突判定的必要事实，不能为省内存直接删除（同文件 `:2604-2623`）。
4. `sheetManualChangeCount` 和 `undoTop` 都逐格同步 `getCell`；`planUndo` 再建一套反向 SheetChange，`applyUndoPlan` 再复核并分块写回（同文件 `:2632-2653,2916-3057,3060-3153`）。这些都是 O(U) 主线程工作和额外数组。
5. 部分撤销会收缩栈顶到未回写格，全部成功才 pop；空栈会删除 WeakMap 值（同文件 `:2993-3057`）。存在明确生命周期出口。

**静态推导：**真实堆峰值取决于单元格值类型、字符串是否共享、预览/计划是否同时存活和三层各自格数；100,000 格上限不能换算成固定 MB。是否发生 GC 压力或 OOM 全部**待实测**。

### 5.6 超时、停止与多实例/多线程影响

**代码事实：**

- `runWithBudget` 的 timer 是宏任务，无法打断同步主线程扫描；同步函数若超时后才完成并立即给出结局，微任务可能让真实结局先于已过期 timer 结算（`frontend-tools.ts:488-550`）。所以预算是异步等待的兜底，不是 CPU 执行上限。
- 超时/停止设置的 `lifetime.abandoned` 只在既有 await 后发挥作用；`sheet-tools.ts` 的扫描、计划、写入函数不接收 lifetime，也没有中途检查（`frontend-tools.ts:464-486,515-520`）。
- store 在 resolve 上下文的 await 之后检查轮次/停止状态，但一旦进入同步 `planSheetWithLevel`、`replanSheet`、`applySheetPlan` 或 `applyUndoPlan`，直到返回前没有新的让出点（`stores/ai.ts:8443-8458,8928-8952,9003-9082`）。同步应用途中收到停止也无法由当前代码中途撤销已开始的循环。
- 迟到回灌是否已被服务端挂起期限拒绝、停止后的最终帧如何收敛，要沿既有回灌/续跑协议做运行验证；本文不把“浏览器还在算”误写成“服务端一定已超时”。

**静态推导与影响面：**每个浏览器页签独立占用自己的主线程，多页签可重复相同工作；后端多实例/Redis Cluster 不直接改变纯本地复杂度，只会通过迟到回灌、重试和重复动作放大调用次数。这里不创建 Java 线程，也没有证据表明 Worker 已存在。

### 5.7 根因、最佳修复与取舍

**根因：**批量适配器 API 被误当成异步/可中断边界；输出尺寸闸与内部算法预算分离不足；读、聚合、预览、应用都围绕完整数组/Map 组织；人围栏写链又绕过通用预算。撤销的保留有正确性依据，问题是没有把其值引用规模纳入容量评估，而不是缺少所有出口。

**建议按以下顺序修复：**

1. **先做无语义扩张的局部减量**：`resolveScope` 改为惰性行范围，避免 selection/rows 也无条件物化全 R；`reviewCells(selection)` 先限定普通字段校验行，不为选区创建全表 Record/issues。
   - 取舍：当前全表主键判重可发现“选区行与选区外重复”。若必须保留该语义，可只对选区做逐字段校验、另以键列流式判重，或明确产品改为选区内判重；不能无声改变结果。
2. **聚合改流式状态**：每组保存 count/sum/min/max/blank 等标量，不为 count 保存 rows，不为普通指标保存 values；仅 `distinct` 按请求保留每组值 Set。无分组同样直接累计。Top-N 仍需全部组的排序键，可用固定堆减少排序临时成本，但不能把 G 组事实误说成只剩 20 组。
   - 取舍：精确 distinct 天然需要额外基数状态；若接受近似算法会改变业务答案，不应作为默认优化。
3. **写计划按档位物化**：建议/sample 档只保留实际展示样例与计数，应用前再一次性物化必要明细；把 100,000 格溢出检查放进所有扫描/ set 项循环，越界即停，不等完整数组形成。分布若保留“其余 N 组”精确计数，需要另行设计计数口径，不能只截 Map 后仍声称精确。
4. **响应性与取消**：把纯计算改成批间 `await` 让出，并传 deadline/AbortSignal/lifetime，在每批前后检查；读和计划可安全取消。应用若允许批间取消，必须沿用现有“部分写入 + 实际格数 + 可撤销”诚实结局，不能假装整批未写。
   - 取舍：让出会延长墙钟时间并引入表格中途变化窗口，需要继续执行 previewId/指纹闸。SpreadJS adapter 是同步主线程对象，简单搬进 Worker 不可行；可先把纯校验/聚合/匹配的分块快照送 Worker，再回主线程分批落笔，但结构化克隆会增加复制成本和内存。
5. **撤销按实测再定容量策略**：保留 WeakMap、3 层和冲突检查；可合并坐标/值存储以减少数组槽位，或为三层总保留格数设明确预算，但不要删除 appliedValues、改深拷贝所有值或误称当前无界泄漏。

影响面主要是 `sheet-tools.ts` 的扫描/范围/聚合/计划接口、`frontend-tools.ts` 的执行器生命周期传参、`stores/ai.ts` 的写预览链和适配器分批实现。无需改变后端工具清单、模型输出上限或 Redis 协议；若引入 Worker/异步计划，必须同步调整预览身份、部分写入和回灌时序测试。

### 5.8 后续验证（不执行）

- 用不同 R×C、选区大小、谓词命中率、组数、distinct 基数、错误密度、变更格数与单元格字符串大小采集浏览器 performance trace、最长 task、堆峰值、GC 和回灌时刻；分别测读、计划、切档、应用、撤销，不能只测最终文本长度。
- 在预算前/预算中/服务端挂起到期后结束同步计算，验证真实结局、超时结局与后端受理一致；停止、换页、换表、换会话时验证不再开始新批，已开始写入按真实部分结局收敛。
- 验证 selection 判重语义、聚合全指标、Top-N 稳定排序、所有写工具 100,000 边界、三层 FIFO、WeakMap 回收和撤销冲突不回归。
- 多页签与多服务实例组合只用于测量调用放大和迟到回灌；不要把浏览器长任务直接归因为 Redis Cluster。

---

## 六、统一后续观测与验收口径

1. 后端为工具体、实时视图、首次预检、批准复核、目标校验、槽位 enrich、各宿主 handoff 分阶段计数/计时；区分逻辑调用、实际 SQL、ORM 水合行、文件字节和 Redis command，避免一个“DB 次数”混盖所有层。
2. 前端为每个 sheet 工具记录范围行列、批数、实际扫描格数、内部 change/group/issue 数、是否让出/取消、回灌时刻；不记录业务值或敏感连接信息。
3. 修复验收同时看结果等价与资源结局：历史视图不查库、最终执行守卫不删除、宿主强/弱读语义不退化、写预览身份/指纹/部分撤销不失效。
4. 本报告没有执行上述验证；所有具体 SQL 数、毫秒、QPS、P99、堆峰值、OOM 或线上事故频率均仍为**待实测**。
