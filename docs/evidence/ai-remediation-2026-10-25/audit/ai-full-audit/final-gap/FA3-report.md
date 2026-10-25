> 来源=<仓外证据>/ai-full-audit/final-gap/FA3-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
**FA3-report — 未运行任何测试**

> 审计对象：`<仓外证据>/ai-full-audit/tree`（下称 `⟨snap⟩`），只读快照；基线与既有报告同源。
> 全程**未运行任何测试、未构建、未改动任何源码/测试/配置**；所有结论仅来自逐行读码与交叉核对既有报告文本。下文路径均相对 `⟨snap⟩`（覆盖表中给出可点击全路径）。

---

## 1. 逐行覆盖证明

| # | 文件（全路径） | 行数 | 已读范围 | 覆盖 |
|---|---|---:|---|---|
| 1 | `_p2-review/ai-full-audit/tree/backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java` | 789 | 1–789 | 789/789 |
| 2 | `_p2-review/ai-full-audit/tree/backend/src/main/java/com/example/configmgr/job/service/JobService.java` | 555 | 1–555 | 555/555 |
| 3 | `_p2-review/ai-full-audit/tree/backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java` | 479 | 1–479 | 479/479 |
| 4 | `_p2-review/ai-full-audit/tree/frontend/src/utils/excel.ts` | 865 | 1–865 | 865/865 |
| 5 | `_p2-review/ai-full-audit/tree/frontend/src/utils/spreadjs.ts` | 86 | 1–86 | 86/86 |
| 6 | `_p2-review/ai-full-audit/tree/frontend/src/views/ImportWizardView.vue` | 1,697 | 1–1,697 | 1,697/1,697 |
| | **合计** | **4,471** | | **4,471/4,471（100%，无跳读）** |

`wc -l` 复核与 FA3 manifest 完全一致（789/555/479/865/86/1,697 = 4,471）。

为判定"新/旧"与避免误报，额外只读交叉核对了（**不属本组 6 文件、仅作证据**）：`RedisTaskEventBus.java`(355)、`TaskEventBus.java`(63)、`SseWriteBudget.java`、`JobWatchdog.java:240-376`、`JobProgressService.java:60-200`、`JobTerminalWriter.java`、`AsyncJobExecutor.java`、`StaleJobRecoveryRunner.java`、`RunnerActiveKeyCleanupRunner.java`、`TaskService.applyJobOutcome`、`FileController` 端点、`ExcelReader.findDataSheet`、`job-watch.ts`(253)、`workspace.ts:430-500`、`frontend-tools.ts:670-1045,1312-1418`、`TaskWizard.vue`、`SpreadGrid.vue` 交接面。

---

## 2. 与既有 canonical ID 的关系（逐文件）

### 2.1 `JobHeartbeatRegistry.java`（789 行）
- **确认** E §L7 / §283 与 FINAL"多实例接管"表：三键族 `job:beat:`（短 TTL=interval×deadMultiplier，缺省 90s）/`job:runner-active:`（2×max-exec+5m，值带 `|v2`）/`job:deadjudge:`（`SET NX PX` 单飞）在代码上逐条成立（`:85-99,187-231,267-293,462-488,503-529`）；`hold` 不延长共享 TTL（`:545-549`）、`isRunnerActive`/`isHeld` 是本实例豁免（`JobWatchdog:247-259`）、判死成功后 `revokeRunner` 立即清两键（`:312-322`）均与自述一致。
- **确认** `clearSharedRunnerState` 的"先删 beat、后删 anchor"危险形态处理（`:681-722`）确实规避了 anchor-only ⇒ 立即 STALE 的误杀。
- **补充**（新）：`isRunnerAlive` 的 Redis 读取**无可用性快速降级**，且被建作业路径按"任务全部历史作业"逐个调用 ⇒ **FA3-N7**。
- **纠正**（部分）：自述"Redis 不可用 ⇒ 心跳判死 fail-closed、绝不误杀"只覆盖**读失败**；写侧失败（非对称分区）仍会误杀并释放闸门 ⇒ **FA3-N8**（C/R）。
- **补充**（新）：`runner-active` 残留锚点对**新建作业**闸门的阻塞上界 = 65m（max-exec=2h 时 4h5m），而同主机 PID 清理在容器化部署（主机名每次重建变化）恒不匹配 ⇒ **FA3-N16**。

### 2.2 `JobService.java`（555 行）
- **确认 BZ-01**：`createAndStart` 的四守卫（`:160-165`：任务终态 / 同类互斥 / 旧 runner 存活 / 预检查）**无任何可执行性校验**（条目数、UPLOAD 文件是否存在、staging 是否为空），与 FINAL 表述一致。
- **确认 BZ-02 机制细节**（新证据，不开新号）：`ImportJobRunner.run` 是 `@Transactional`（`ImportJobRunner.java:60-61`），其外层 catch 的 `terminalWriter.complete(...)` 走 **afterCommit** 注册（`JobTerminalWriter.java:96-104`）——提交期回滚/commit 失败时该终态写不会执行，作业停在 RUNNING 直到 30m 上限。与 BZ-02 判据一致，无需改写。
- **补充**（新）：同类互斥/预检查判据之外的**任务类型↔作业类型一致性**完全缺失，仅存在于 AI 预检与前端 ⇒ **FA3-N17**。
- **补充**（新）：建作业守卫的 Redis 依赖与 O(N) 调用 ⇒ **FA3-N7**；错误码复用 `JOB_ALREADY_RUNNING` 造成误导。
- **否证**：无。`:485-518` 的取消事务边界（CAS + 条目收敛 + 旁路后置）与自述一致；`:532-553` 旁路失败仅归因不回滚，与 ADR-8 口径一致。

### 2.3 `TaskSseService.java`（479 行）
- **确认**"跨实例去回声"成立——但**不在本文件**：回声过滤由 `RedisTaskEventBus.handleMessage` 的 `origin` 字段完成（`RedisTaskEventBus.java:220-231`），`TaskEventBus` 接口把它写成实现方义务（`TaskEventBus.java:30-32`）。本文件的 `publish`（`:192-203`）本机直投后又发总线，自身不做任何去重，正确性完全依赖总线实现。
- **确认**"锁内只快照 / 锁外带预算写出"（`:250-285`）、"首订阅注册 / 末订阅退订 + 引用计数锁回收"（`:317-368`）、"订阅失败一次性 10s 退避重试、只对有本机 emitter 的任务重试"（`:377-422`）均与自述一致；`sendEvent` 已消费 `sendFrame` 返回值（`:231-240`）。
- **纠正**：`:43-45` 的"调用线程最多被占用一个预算就会脱身"在订阅者 > 1 时**不成立**（顺序循环，每 emitter 各一次预算）⇒ **FA3-N9**；与之配套的 `SseWriteBudget.java:99-102`"线程峰值 ≈ 并发订阅者数"在"同一 emitter 被两个发布者并发写"时不成立。
- **补充**（新）：跨实例发布是**作业线程上的同步 Redis 命令**、无预算 ⇒ **FA3-N10**；订阅无任务存在性校验/无上限 ⇒ 次要项（§5）。

### 2.4 `excel.ts`（865 行）
- **确认**模板/写回自产 OOXML 的形状（`:457-481,544-636`）与后端 `ExcelTemplateBuilder`/`ExcelReader` 的 `_meta` 口径一致；`ExcelReader.findDataSheet`（`ExcelReader.java:143-152`）确实"跳过 `_` 前缀与隐藏表，回退 sheet0"。
- **否证**（此前"导出/下载产物字节路径"未逐行的怀疑）：`buildRowsXlsxBlob` 路径不含 ExcelIO 保存，无水印表问题；字节构造本身（Content_Types/rels/styles/sharedStrings）完整可解析。
- **补充**（新）：`normalizeCellValue` 的 NUMBER 规范化会静默改写数值 ⇒ **FA3-N12**（本次最重要的数据面发现）；数据表名保留字/下划线前缀 ⇒ 次要项；空行跳过导致行号漂移 ⇒ 次要项；DATE 无格式化 ⇒ 条件项（R）。

### 2.5 `spreadjs.ts`（86 行）
- **确认**懒加载顺序与失败不缓存（`:45-81`）正确；`isSpreadCoreLoaded()` 语义为"已发起加载"而非"已就绪"，且**全仓无消费者**（次要项，§5）。
- **否证**：不存在"动态加载竞态/重复注入"——单线程下赋值先于 await 返回，失败路径置空可重试。

### 2.6 `ImportWizardView.vue`（1,697 行）
- **确认 FE-16 的代码事实**：`:1665-1679` 的 `route.query.taskId` watcher 只做 `onSheetPageLeft()` → 改 `taskId` → `setTaskId` → `loadTask()`，**不回收** `jobWatchDisposes`、不清 `checkJob/importJob/publishJob`、不清 `checkIssues/importIssues/publishIssues/publishConflict`、不清 `uploadedFiles/savedDefs/editorLoaded/staging`。`beginWatchJob` 只在"同 kind 再次 attach"时回收（`:1054-1063`），新任务若缺某类作业则旧 watcher 常驻。
- **补充 / 提级 FE-16**（新）：缺失的重置不只导致"旧进度挂新任务"，还可导致**跨任务数据串写**与**跨任务动作**（取消他人任务的作业）⇒ **FA3-N1**（建议 S1）。
- **确认 FE-19 边界**：本页 `publishConflict` 单槽、`:1041-1042` 仅在确认通过后清旧冲突；与 `task.ts` 单槽同源（FINAL FE-19 判据不在此文件，故不重复评级）。
- **确认 FE-18 关系**：`import.syncJob`（`:1288-1311`）已覆盖"AI 起作业后把作业绑回页面"，但只认 `resumeJobs` 选出的**最新**作业 ⇒ 次要项。
- **补充**（新）：`confirm_step`(→`import.nextStep`) 的写回纪律（`:1352-1404`）被 `ui_event.goto_step` 与 `workspace.step` watcher 绕过 ⇒ **FA3-N2**；`startPrecheck/startImport` 失败回执假成功 ⇒ **FA3-N3**；勾选/AI 选配置项强刷丢编辑 ⇒ **FA3-N4**；`GET /files` 静默失败 + 无条件写回 ⇒ **FA3-N5**；零暂存仍可发布 ⇒ **FA3-N6**（BZ-01 的前端可见面）。
- **否证**：`viewStep`（`:914-920`）"只读才可达"成立——`TaskWizard.vue:38-45` 的 `step-click` 只在 `v-if="readOnly"` 分支发出，故不存在"非只读点节点跳过写回"的路径（该怀疑不成立）。

---

## 3. 新增问题（按严重度）

### FA3-N1｜同路由换任务不重置编辑区/上传态/作业位 ⇒ 可把 A 任务的数据写进 B 任务
- **严重度 / 确定性**：S1 / **C（路径）+ R（实际触发需 step≠0 或文件下载失败）**
- **位置**：`frontend/src/views/ImportWizardView.vue:1665-1679`（换任务 watcher）；`:522-538`（`loadUploadedFiles` 只增不清）；`:700-732`（`loadEditorsNow`：`editorLoaded` 命中且 `force=false` 直接跳过；`stepIndex!==0` 直接 return）；`:427-434`（`editorLoaded` 只在组件卸载/`el===null` 时删除）；`:789-822`（`flushEditors` 对所有挂载表格无条件 `toXlsxBlob`+`saveFile`）；`:1314-1349`（`sheet.current` 直接把陈旧表格适配器交给 AI 工具）
- **触发**：同一路由记录下 `?taskId=` 变化（AI `navigate_to/restore_task`、任务中心链接）→ 两个任务含**同一 defCode** → 切换发生于 B 任务 `currentStep≠0`，或 B 任务无该 code 的 UPLOAD 件（`downloadFile` 404 → catch 后 `editorLoaded` 未清、网格保留旧内容）→ 之后回到第 1 步并推进/`flushEditors`。
- **用户可见效果**：① 标签页出现 A 任务的"已上传/已保存"标记（`uploadedFiles/savedDefs` 未清）→ 用户以为 B 任务已上传；② 编辑区显示 A 任务的行数据；③ 推进步骤时 `PUT /tasks/B/files/{code}` **创建/覆盖 B 任务的 UPLOAD 件为 A 任务的内容**（`FileController:210-230` 对不存在的 file 会新建行），随后 PRECHECK/IMPORT 校验并导入"另一个任务的数据"；④ `sheet.current` 把该陈旧表交给 AI 工具读写，AI 回执说"已修改 B 配置项"。
- **既有覆盖**：`import-watch-recycle` 用页面自建接线，不挂视图（FINAL §529），故这条写入面零覆盖。
- **与 ID 关系**：FE-16 的**补充与提级**（FE-16 只写到"进度/冲突挂新任务、误只读、watcher 泄漏"）。

### FA3-N2｜AI 的 `ui_event.goto_step` 可越过第 1 步而不写回整表 ⇒ 预检查/导入读旧文件
- **严重度 / 确定性**：S2 / C
- **位置**：`frontend/src/stores/workspace.ts:471-483`（`goto_step` 只 `tasksApi.goToStep` + `workspace.setStep`）；`frontend/src/utils/frontend-tools.ts:692`（路由 3 `ui_event`）→ `:1312-1319` → `:1400-1418`（`goto_step` 事件生成）；`ImportWizardView.vue:1685-1696`（`workspace.step` watcher 只改 `stepIndex`，仅 index===3 才 `loadStaging`）；写回纪律只在 `:875-905`（`goStep`）与 `:1352-1404`（`import.nextStep`）
- **触发**：用户在第 1 步在线编辑了表格（未点"保存草稿"），模型调用 `ui_event` 的 `goto_step`（而非工具 `confirm_step`）跳到第 2/3 步，随后 `start_precheck`/`start_import`。
- **用户可见效果**：服务端 `currentStep` 与界面都前进，回执"已跳转到步骤 X"，但**整表未 `saveFile`**；PRECHECK/IMPORT 读的是旧 UPLOAD 件（屏幕上是新数据，判定用的是旧数据）。与 `confirm_step` 路径的回执（`R3-02` 明确写"已写回 N 个配置项"）语义不一致。
- **测试**：`confirm_step` 的 flush 有页面级用例；`ui_event.goto_step` 只有 store 层用例，无"编辑后 goto_step 再预检查"的端到端断言。
- **与 ID 关系**：新（FE 系列只修了 `nextStep` 一侧）。

### FA3-N3｜`import.startPrecheck` / `import.startImport` 在建作业失败时回“旧作业已启动”
- **严重度 / 确定性**：S2 / C
- **位置**：`ImportWizardView.vue:1442-1458`（读完 `startJob` 后直接读 `checkJob.value/importJob.value`）；`:963-984`（`startJob` catch 后 `return null`，**不清空**对应 ref）
- **触发**：AI 调用 `start_precheck`/`start_import`，而 `createJob` 失败：同类互斥 409、`PRECHECK_NOT_PASSED`、`TASK_FINALIZED`、Redis 不可用导致的 `JOB_ALREADY_RUNNING`（见 N7），或线程池拒绝后的 FAILED 回包。
- **用户可见效果**：模型收到"预检查作业 #N 已启动"（N 是**上一次**的作业号），用户侧只有一条错误 toast；模型据此继续下一步（例如直接 `start_import`）。同文件的 `import.nextStep`/`import.startPublish` 已按结构化失败返回，此处是残余的不对称。
- **测试**：页面 handler 的失败分支无用例（`import-watch-recycle` 只测回收）。
- **与 ID 关系**：新（DC-14 同类，F2/I-3 修正未覆盖这两个 handler）。

### FA3-N4｜勾选配置项（含 AI `select_definitions`）强制重载所有编辑区，静默丢弃未写回的编辑
- **严重度 / 确定性**：S2 / C
- **位置**：`ImportWizardView.vue:1651-1655`（`watch(selectedDefs)` → `loadEditors(true)`）；`:1406-1418`（`import.selectDefs` handler → `syncSelectedDefsToWorkspace` + `loadEditors(true)`）；`:700-732`（`force` 时对**所有** `editorDefs` 重新 `loadFromXlsx`/`loadFromJson`）
- **触发**：在第 1 步手工改过 A 配置项的表格（未保存草稿），随后勾选/取消任意配置项，或模型调用 `select_definitions`。
- **用户可见效果**：A 的编辑内容被服务端文件/空模板覆盖，无任何提示、无 flush、无版本提示；后续"下一步"写回的是被覆盖后的内容。AI 回执说"已选择配置项…"，不提示丢改动。
- **测试**：无（现有用例不覆盖"编辑后改勾选"）。
- **与 ID 关系**：新。

### FA3-N5｜`GET /files` 静默失败 + 第 1 步无条件写回 ⇒ 覆盖既有上传件为空模板（BZ-01 的新入口）
- **严重度 / 确定性**：S2 / **C（每次推进都会为无文件项创建空件）+ C/R（覆盖既有件需一次 files 拉取失败）**
- **位置**：`ImportWizardView.vue:523-538`（`catch {}` 静默）；`:714-724`（无 `uploadedFiles[code]` 时回落 `buildTemplateWorkbookJson(..., sample:false)`）；`:789-822`（无条件 `saveFile`）；`excel.ts:457-481`（空行数组 ⇒ 仅表头）
- **触发**：① 正常路径——选中 4 个配置项直接点"下一步"，其中未上传的项会被创建为**0 数据行**的 UPLOAD 件；② 异常路径——页面加载时 `GET /tasks/{id}/files` 超时/500（一次即可），已上传项的编辑区回落到模板，推进步骤即把该 UPLOAD 件**覆盖为仅表头**。
- **用户可见效果**：上传数据被静默替换；PRECHECK 读到 0 行 ⇒ 0 错误 ⇒ COMPLETED（BZ-01）⇒ IMPORT/PUBLISH 全链"成功但零数据"；原上传件已不可恢复（覆盖写同一 storage 键）。
- **测试**：无"files 拉取失败后禁止写回"的用例。
- **与 ID 关系**：BZ-01 的**新触发入口**；与 FE-16 叠加（换任务后 `uploadedFiles` 陈旧会放大）。

### FA3-N6｜暂存为空时仍可发布；零行 PUBLISH 成功后任务永久只读
- **严重度 / 确定性**：S2 / C
- **位置**：`ImportWizardView.vue:182`（空态只展示）、`:213`（发布按钮只按 `jobRunning` 禁用）、`:1001-1052`（`startPublish` 不检查暂存/导入结果）、`:402-408`（`isReadOnly`）；`JobService.java:78-113`（PUBLISH 成功即 `TASK_FINALIZED`）
- **触发**：IMPORT 作业 0 行完成（BZ-01）或暂存被清空后，用户点"发布"或模型 `start_publish`（REPLACE 模式还要过一次确认框）。
- **用户可见效果**：任务被置 COMPLETED（`applyJobOutcome`），`isReadOnly` 立刻为真 ⇒ 页面"任务已结束，仅供查看"，发布/导入/上传全部消失；再发起任何作业 409 `TASK_FINALIZED`，只能新建任务。用户确认过"范围内替换"却得到空发布。
- **测试**：无"0 行发布"用例。
- **与 ID 关系**：BZ-01 的**前端可见面确认**（FINAL BZ-01 只写到后端四链；此处补出可点击入口与不可逆后果）。

### FA3-N7｜建作业守卫按“任务全部历史作业”逐个打 Redis，且无可用性快速降级
- **严重度 / 确定性**：S2 / C（无快速路径、O(N) 调用是代码事实）/ R（Redis 慢/断的时长与频度）
- **位置**：`JobService.java:163-165`（在 task 行锁 + 事务内）、`:293-310`（`for (Job existing : jobRepository.findByTaskId(taskId))` 无状态过滤）；`JobHeartbeatRegistry.java:340-364`（每次 `hasKey`，失败即抛）
- **触发**：任务累计 N 个历史作业（随生命周期单调增长）；Redis 抖动/不可达。
- **用户可见效果**：① 每次 `start_*`（含全部 AI 作业工具与 REST）产生 N 次同步 Redis 命令，期间持有 task 行锁（阻塞删除/并发建作业）；② Redis 不可用时**所有**建作业请求 409 `JOB_ALREADY_RUNNING`，文案为"旧作业 #id 状态 COMPLETED 正在收尾，请稍后重试"——状态与原因都不对，客户端会当冲突无限重试；③ 该状态没有指标/健康面（对比 `RedisTaskEventBus` 专门做了 `redisKnownDown` 快速降级 + `metrics()`，`RedisAvailability` 只在 AI 侧装配，作业侧不引用）。
- **测试**：`RunnerActiveMultiInstanceTest`/`StaleJobRecoveryMultiInstanceTest` 用 mock Redis（ConcurrentHashMap，TTL 不生效、无命令超时），N 次调用的成本面完全未覆盖。
- **与 ID 关系**：**纠正** E §L7/§283"Redis 不可用 ⇒ fail-closed"只讲正确性；可用性/文案/可观测后果为本组新增。

### FA3-N8｜非对称分区下 `STALE` 会杀活 runner 并释放闸门 ⇒ 双写者（条件性）
- **严重度 / 确定性**：S3（可升 S2，取决于生产网络形态）/ **C（链路）+ R（需 A 写 Redis 失败而 B 读成功）**
- **位置**：`JobHeartbeatRegistry.java:409-416`（ticker 失败只 WARN、下周期重试）、`:625-634`（续期失败仅 WARN）、`:462-488`（beat 不在 + `|v2` anchor ⇒ STALE）、`:312-322`（revoke 删两键）；`JobWatchdog.java:247-259,352-357`（STALE ⇒ judge → `revokeRunner`）；`JobService.java:293-310`（闸门随之放行）
- **触发**：实例 A 到 Redis 的写失败（ACL/单向丢包/主从切换期），A 的 ticker 连续失败 > 90s；实例 B 读正常，看到 beat 过期而 anchor（`|v2`）在 ⇒ 判 A 的作业"死"。
- **用户可见效果**：A 上**仍在跑**的作业被判 FAILED 并写 ERROR issue；A 的 runner 也读不到 `job:cancel:` ⇒ 不会在分片边界止步，继续写 staging/文件；同时 B 释放闸门，允许同任务新建作业 ⇒ **同一任务两个写者**（staging 行/文件互相覆盖）。用户看到"作业失败"但数据仍在变。
- **证据缺口**：E §124/§134 已指出该族测试全是"同 JVM 两个 registry + mock Redis（TTL 不生效）"；`cluster-3node.yml` 非 PR 门禁且未注入写侧分区。**无任何用例覆盖写失败/单向分区**。
- **与 ID 关系**：纠正"绝不误杀"的表述边界；不新开 BZ 号（属 C15 家族）。

### FA3-N9｜任务 SSE 顺序扇出：调用线程占用 = k×预算，且同一 emitter 可被并发写
- **严重度 / 确定性**：S3 / C
- **位置**：`TaskSseService.java:250-285`（`for (SseEmitter emitter : snapshot) sendFrame(...)`，逐个同步等预算）、`:446-462`（每帧 15s 预算）、`:43-45`（自述"最多被占用一个预算"）；`JobService.java:546-552`（第二个发布者：取消路径）与四个 runner 的 `publishProgress`（作业线程）
- **触发**：同一任务有 k≥2 个订阅者，其中至少一个"不读也不断开"（挂起的 DevTools/代理/停读页）；或取消 `JOB_DONE` 与分片 `JOB_PROGRESS` 并发。
- **用户可见效果**：一次 `publish` 可占用作业线程/取消 HTTP 线程至多 `k×15s`（每个滞留 emitter 各耗一份预算），作业分片节拍被拖慢；滞留写线程数最多 ≈ k×并发发布者（`SseWriteBudget.java:99-102` 的"线程峰值 ≈ 订阅者数"只在单发布者下成立），弹性池无上限（`max=Integer.MAX_VALUE`），单线程最长挂到容器 120s 写超时。
- **纠正对象**：E §286"任务 SSE 写不阻塞作业线程"（证据 `TaskSseServiceTest` 用 `SseWriteBudget` 桩）——预算只约束**单个** emitter，不约束一次 fan-out 的合计占用。
- **测试**：无"≥2 个滞留订阅者"的用例。

### FA3-N10｜跨实例发布是作业线程上的同步 Redis 命令，无预算
- **严重度 / 确定性**：S3 / C
- **位置**：`TaskSseService.java:192-203`（`publish` → `deliverJson` → `publishCrossInstance`）、`:288-293`；`RedisTaskEventBus.java:114-147`（`convertAndSend` 同步、失败才 `markKnownDown`）
- **触发**：Redis 延迟升高（未到失败阈值）而作业按分片边界持续发 `JOB_PROGRESS`。
- **用户可见效果**：每个分片边界都被一次 Redis RTT 阻塞（作业线程 = 发布线程），作业总时长随 Redis 延迟线性增长；与 N9 的本地写叠加。`SseWriteBudget` 的 15s 预算对这条路径**完全不适用**（它只覆盖 emitter 写）。
- **测试**：`RedisTaskEventBusTest` mock 模板，`TaskSseServiceCrossInstanceTest` 用假总线，均不体现真实 RTT。
- **与 ID 关系**：纠正 E §286 的适用范围（跨实例这一半没有任何预算/降级实证）。

### FA3-N12｜`excel.ts` 的 NUMBER 规范化静默改写数值（前导零 / >2^53）
- **严重度 / 确定性**：S2 / C
- **位置**：`frontend/src/utils/excel.ts:64-82`（`normalizeCellValue`：NUMBER → `String(Number(text))`）、`:344-358`（`spreadValue`）、`:361-412`（`readRows` 用 `normalizeCellValue`）、`:496-506`（`cellValueForXlsx`）；消费者 `SpreadGrid.vue:478-501`（`collectRows`→`buildRowsXlsxBlob`）、`ImportWizardView.vue:818-819`（`toXlsxBlob`→`saveFile`）
- **触发**：某 NUMBER 字段承载零填充编码（`007`、`001234`）或 ≥16 位整数（订单号/手机号/身份证）。
- **用户可见效果**：`007 → 7`、`12345678901234567890 → 12345678901234568000`。写回文件（第 1 步推进/flush）即固化错值；本地校验不报错（`Number.isFinite` 通过），PRECHECK/IMPORT/导出全部按改后值落库。**无告警、无痕迹**，属主链静默数据损坏。
- **测试**：excel 相关用例只覆盖校验器/zip/文件名，无精度与零填充断言。
- **与 ID 关系**：新（A/I 只到 `excel.ts` 接缝，未进数值规范化）。

### FA3-N16｜`runner-active` 残留锚点把“新建作业”挡到长 TTL；同主机清理在容器部署失效
- **严重度 / 确定性**：S3 / C（语义）+ R（需崩溃落在终态写与 `endRunner` 之间，或 DEL 失败）
- **位置**：`JobHeartbeatRegistry.java:91`（TTL=2×max-exec+5m）、`:296-301`（正常退出删键）、`:681-722`（删失败保 anchor / 补短 beat）、`:568-593`（清理只认同主机且本地 PID 不存在）、`:729-735`（`hostName()` 失败 ⇒ `"unknown"`）；`RunnerActiveKeyCleanupRunner`；`JobService.java:293-310`
- **触发**：实例在"终态已写、`endRunner` 未执行"窗口崩溃（或删键失败），后续请求落到**另一台主机**（k8s pod 重启后主机名变化 ⇒ 启动清理永远匹配不上）。
- **用户可见效果**：该任务所有新建作业/删除被 409"旧作业 #id [X] 状态 COMPLETED 正在收尾"挡住，最长 65m（max-exec=2h 时 4h5m），无任何可操作提示；运维只能等 TTL 或手动删键。
- **附带 fail-open**：`hostName()` 在加固容器上抛异常时所有实例都变成 `"unknown"`，同主机判定退化 ⇒ 可能删掉**别人仍在跑**的 runner-active 键（闸门提前释放，双写者风险）。R（环境）。
- **测试**：`RunnerActiveTtlConfigTest` 只测 TTL 派生；无崩溃残留/跨主机用例。

### FA3-N17｜`createAndStart` 无“任务类型 ↔ 作业类型”一致性校验（REST 面可跨类型建作业）
- **严重度 / 确定性**：S3 / C
- **位置**：`backend/.../job/service/JobService.java:156-230`（四守卫，无类型校验）；`backend/.../job/controller/JobController.java:29-30`（`Job.JobType.valueOf(body.get("jobType"))` 直接透传）
- **触发**：直接调 `POST /api/tasks/{id}/jobs {"jobType":"IMPORT"}`（EXPORT 任务，且已有一次通过的 PRECHECK），或 `PUBLISH` 打在 EXPORT 任务上。
- **用户可见效果**：staging/发布数据写进"类型不符"的任务；该任务的 `finalizedConflict`（IMPORT→PUBLISH / EXPORT→EXPORT）与前端 `isReadOnly`（`task.type==='IMPORT' && publishJob COMPLETED`）因此判错——例如 EXPORT 任务被 PUBLISH 置 COMPLETED 后不会进 TASK_FINALIZED；两个向导对同一任务的步骤/只读判断互相矛盾。
- **测试**：AI 路径有 `ToolTargetResolver` 等用例；REST 路径无类型矩阵用例。
- **与 ID 关系**：新（跨栈一致性缺口：矩阵只活在前端与 AI 预检）。

---

## 4. 次要 / 观察（低影响，仍属新增，未开主编号）

| 项 | 严重度/确定性 | 位置 | 触发 → 效果 |
|---|---|---|---|
| N11 任务 SSE 订阅无存在性校验、无上限 | S3 / C | `TaskSseService.java:159-179`（`subscribe` 不查任务）；`RedisTaskEventBus.java:170-200`（每个 taskId 注册频道监听） | 已认证客户端对随机/已删除 taskId 反复 `GET /api/tasks/{id}/events` → 每条 5m 的 emitter + 一个 Redis 频道监听，无回收上限；每个 `publish` 都为它们付一次写预算 |
| N13 数据表名未排除 `_meta`/`_dict` 与 `_` 前缀 | S3 / C（重复名）/ R（Excel 修复提示） | `excel.ts:46-49,457-481`；后端 `ExcelReader.java:85,143-152` | 定义名 `_meta`/`_dict` → 工作簿出现同名表，Excel 打开提示"修复"，POI 的 `getSheet("_meta")` 解析变为次序相关；普通 `_` 前缀靠"回退 sheet0"侥幸可读 |
| N14 `readRows` 跳过空行导致本地行号漂移 | S3 / C | `excel.ts:163-201,393-410` | 数据中间有空行 → 其后所有"第 N 行"与工作表/后端 `issue.rowIndex` 不再一致；本地预检与后端问题定位对不上 |
| N15 DATE 单元格无格式化 | S3 / **R**（值形态依赖 SpreadJS/ExcelIO） | `excel.ts:119-155,361-412`；`ImportWizardView.vue:805-817` | 上传件里是真正的 Excel 日期单元格时，`getValue` 得到序列号/Date 对象，`validateCell` 只接受 `yyyy-MM-dd` → `flushEditors` 跳过该配置项、`goStep` 中止推进（"存在未写回的编辑内容"），用户无自解路径 |
| N18 `import.syncJob` 只认"最新作业" | S3 / C | `ImportWizardView.vue:541-560,1288-1311` | 同类型存在更新作业时，AI 起的那条被判"未在本任务的作业中找到"，页面继续跟别的作业 → 面板与对话不一致（FE-18 的残余分支） |
| O1 `isSpreadCoreLoaded()` 语义错位 + 无消费者 | — / C | `spreadjs.ts:84-86` | 返回"已发起加载"（加载中即为 true）；全仓无调用点（死代码） |
| O2 死代码导出 | — / C | `excel.ts:662-664,726-734,777-787,790-805,817-831` | `blobToArrayBuffer`/`buildTemplateBlob`/`unzip`/`matchDefCode` 无消费者（`zipBlobs` 仅导出向导用）；`unzip` 若将来接入，无 zip 魔数/解压体积上限 |
| O3 `_meta` 表设计缺口（仅在自产写回路径） | — / C | `excel.ts:484-493` | `buildDictSheet` 只写 label 不写 code，与校验器"code 或 label 都收"不一致；当前后端只按 `_meta` 做列映射，故无可见影响 |

---

## 5. 未解决的分支（R：需运行期/真集群，本次无法定论）

1. **真 TTL 下的判死窗**：`job:beat:` 过期与 ticker 抖动、`job:deadjudge:` 租约重入——现有"多实例"用例全部 mock Redis（TTL 不生效），**没有任何用例在真 TTL 下证明 90s 判死**（E §124/§134 已登记；本次读码确认 `JobHeartbeatRegistry` 侧没有可注入 TTL 的集成测试入口）。
2. **非对称分区/写侧失败**（N8）：需要真 Redis + ACL/单向丢包注入；`cluster-3node.yml` 非 PR 门禁且目前不注入。
3. **Redis 命令超时的真实耗时**（N7/N10）：取决于客户端配置，本次未读 `application.yml` 的 Redis 超时段，故只断言"无预算/无快速路径"，不断言秒数。
4. **POI/Excel 对同名表与 `_` 前缀表的实际处置**（N13）：只读码到"生成同名表"，Excel 修复提示与 POI 取值次序未实测。
5. **SpreadJS 读回单元格的值形态**（N15、N12 的显示面）：`getValue` 对日期/数字格式单元格返回序列号还是字符串需真机；N12 的精度改写不依赖该形态（`normalizeCellValue` 直接 `Number()`），但 DATE 分支依赖。
6. **前端多订阅者滞留场景**（N9）：需 ≥2 个"不读不断开"的客户端才能观测 k×预算；本次只做结构证明。
7. **`GET /files` 瞬时失败的实际频度**（N5）：`catch {}` 决定了"失败即静默回落"，但真实触发概率未知。
8. **崩溃落在"终态写—endRunner"窗口的概率**（N16）：代码可证窗口存在，真实分布未测。
9. **`JobTerminalWriter` 迟到终态与 `judge()` 的竞态**：判死方在拿到租约后**先写 issue 再写终态**（`JobWatchdog.java:300-341`），若作业在此期间自然完成，会出现"COMPLETED 作业 + 一条看门狗 ERROR issue + 取消标志"。代码路径可证，但窗口极窄且只影响 issue 列表展示（前端 `errorMessageOf` 仅在 FAILED 时消费），本次未单列条目，登记为观察。

---

## 6. 结论摘要

- 6 文件 **4,471 行全部逐行读完**；FA3 指定的 4 个既有 ID 全部**确认成立**（BZ-01、BZ-02、FE-16、FE-19 的代码事实与判据一致），其中 **BZ-01 新增两个前端可达入口（N5、N6）**，**FE-16 升级为可跨任务写数据（N1）**。
- 新增 **13 条主条目 + 5 条次要/观察**；按"代码可证"分级：S1×1、S2×7、S3×7（含条件项）。
- 三条**纠正**既有表述：① "Redis 不可用 ⇒ fail-closed" 只覆盖读失败（N8）且会全量阻塞建作业并给出错误文案（N7）；② "任务 SSE 写不阻塞作业线程" 只对单个 emitter 成立（N9），跨实例发布完全没有预算（N10）；③ `SseWriteBudget` 的"线程峰值 ≈ 订阅者数"在并发发布者下不成立（N9）。
- 未运行任何测试；以上均未经验证性执行，也未做任何修复或改动。
