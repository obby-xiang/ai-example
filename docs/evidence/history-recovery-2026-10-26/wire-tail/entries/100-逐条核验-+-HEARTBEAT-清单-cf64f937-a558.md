> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-558` 的 wire 末条 assistant 文本（消息序号 m=9；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 08:22 → 2026-10-12 08:23（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`6c9c699`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：10733 B / 8168 字符 / 43 行；行指纹覆盖率（本批复算）2.9%（最高单文件 3%：`docs/任务看板.md`）
> - 类别：**施工复核**
> - 与既有件关系：复核报告；报告口径覆盖率 0.03
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完成，全程只读（未写入任何文件，未做任何 git 写操作）。

## ① 逐条核验表

| # | 锚点 | 预期 | 实测（关键片段） | 判定 |
|---|---|---|---|---|
| 1 | `com/example/configmgr/task/TaskSseService.java` | 该路径 + `:73`/`:87-88`/`:92-101` | **实际路径 = `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`**（包 `configmgr.task.service`，全仓仅此一个）。`:73` `public SseEmitter subscribe(Long taskId) {`；`:87` `// Send initial heartbeat` + `:88` `sendEvent(taskId, emitter, "HEARTBEAT", Map.of());`；`:92` `@EventListener` / `:93` `public void onTaskChanged(TaskChangedEvent event)` / `:94-100` `publish(event.getTask().getId(), "TASK_CHANGED", Map.of("taskId"…, "version"…, "currentStep"…, "status"…, "summary", event.getSummary()))` / `:101` `}` | **行号与内容一致；路径不一致**（少一层 `service/`） |
| 1附 | 全文件 `HEARTBEAT` | 唯一发布点 `:88`，无周期心跳调度 | 命中 `:88`（发布）/ `:153`、`:166`（javadoc）/ `:181`（`sendFrame` 上下文串「订阅首帧 HEARTBEAT taskId=」）/ `:199`（javadoc）。全文件 232 行无 `ScheduledExecutorService`/`@Scheduled`/定时器 | **一致** |
| 2 | 全后端 `"HEARTBEAT"` | TaskSseService:88 任务级唯一；ConfirmGate:77≈2s；ResilientChatService:96≈5s | 字面 `"HEARTBEAT"` 仅 2 处：`TaskSseService.java:88`、`TaskSseServiceTest.java:145`（用例）。常量族：`ConfirmGate.java:77` `public static final int HEARTBEAT_SECONDS = 2;`（等待循环 `:240` `latch.await(Math.min(remain, HEARTBEAT_SECONDS * 1000L))`）；`ResilientChatService.java:96` `public static final long STREAMING_HEARTBEAT_MILLIS = 5000L;`（`:311` 判据）。无其它任务级周期心跳发布点 | **一致** |
| 3 | `application.yml:191-198` / `:198` / `:219-220` | task.sse 段仅 write-timeout 15s + emitter-timeout 5m，无 heartbeat 键；`:198`=emitter-timeout；`:219-220`=注记+heartbeat-interval；scan-period 10s / dead-multiplier 3 / max-execution-time 30m | 实际段 = `:191 task:` `:192 sse:` … `:202 write-timeout: 15s` `:203 emitter-timeout: 5m`（**段跨 191-203**，无 heartbeat 键 ✓）；`:198` 实为注释 `#     原为 SseEmitter(0L) = 该 emitter 没有生命周期超时 —— 但这不等于"连接没有释放手段"：`；注记实为 `:218-219`（`# 启动期 fail-fast（AppProperties#verifyJobWatchdogKeys）：四项必须 > 0 且` / `#   heartbeat-interval × dead-multiplier < max-execution-time。`），`:220 heartbeat-interval: 30s`，`:222 scan-period: 10s` / `:223 dead-multiplier: 3` / `:224 max-execution-time: 30m` | **不一致（行号整体后移：emitter-timeout 在 :203 而非 :198；:198 是注释行；job 注记起于 :218）**；键值语义全部符合 |
| 4 | 四 Runner + JobTerminalWriter + JobService | 6 处 publish 行号 | 全部逐字命中：`ExportJobRunner.java:271` `taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(`；`ImportJobRunner.java:216` 同形；`PrecheckJobRunner.java:360` 同形；`PublishJobRunner.java:364` 同形；`JobTerminalWriter.java:86` `taskSseService.publish(job.getTaskId(), "JOB_DONE", doneEvent);`；`JobService.java:268` `taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(` | **一致** |
| 5 | `job/service/JobWatchdog.java` | 258 行；`:114-115`/`:117`/`:123`/`:159-160`/`:161` | `wc -l` = **258** ✓；`:114 @PostConstruct` `:115 void start() {`；`:117 ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {`（`:118 Thread thread = new Thread(r, "job-watchdog");` `:119 thread.setDaemon(true);`）；`:123 svc.scheduleWithFixedDelay(this::scanSafely, scanPeriodMs, scanPeriodMs, TimeUnit.MILLISECONDS);`；`:159` javadoc + `:160 void scan() {`；`:161 List<Job> inFlight = this.jobRepository.findByStatusIn(IN_FLIGHT);` | **一致** |
| 5附 | 死动作链三件 | 出现行 | `ValidationIssue`：`:5` import、`:44` javadoc、`:224 new ValidationIssue();`、`:227 issue.setSeverity(ValidationIssue.Severity.ERROR);`；`JobTerminalWriter`：`:45` javadoc、`:70 private final JobTerminalWriter terminalWriter;`、`:81` javadoc、`:96/:103` 构造参数；`JobCancellationRegistry`：`:46` javadoc、`:71 private final JobCancellationRegistry cancellationRegistry;`、`:96/:103` 构造参数 | **一致**（三件均以字段/构造注入实存，非死引用） |
| 6 | M2 表多行 | 见下 | `:280` = R-88 行 ✓ 但**行内仍写旧锚** `startAutoRefresh:182-199`（现态 `stores/task.ts:157 startAutoRefresh(...)`，`:162 setInterval`，`:182-199` 现为 `refreshCurrent` 体）；`:281` = R-89 行 ✓，**行内仍写** `views/ImportWizardView.vue:748-795`（现态 `:748-795` 只有只读态点节点函数与 javadoc，`watchJob({` 实际调用在 `:844`，兜底 `setInterval` 在 `utils/job-watch.ts:106`）；`:218` = R-26 行 ✓（含 2026-10-10 升维注记）；`:206` = R-14 ✓；`:205` = R-13 ✓；`:286` = R-94 ✓（"**正式立项，非观察项**"、死码族清除 + 发布冲突落点重建）；`:288` = R-96 ✓（含批 B 完成留痕）；`:347` = T4-4 行，记 **29**（"桩模式启用并**进主计数（28→29，实读）**"、"实跑：`PASS=29 FAIL=0 GFSKIP=0 exit 0`"） | **行号全部一致；`:280`/`:281` 两条旧锚未更正（不一致）；E2E 计数 29 一致** |
| 7 | `.github/workflows/ci.yml:108` / `:212` | 按 29 命名 | `:108 name: E2E 全流程（29 用例 · 桩上游 + redis）`；`:212 - name: 运行 E2E（29 用例）` | **一致** |
| 8 | `docs/requirements.md:58` | 仍写「列表 5 秒自动刷新」且尾部带 2026-10-10 注记纠正为 4s | `:58 - **进度实时刷新**：列表 5 秒自动刷新作业进度；页面提供手动刷新 ｜**注记（2026-10-10 UI 改造）**：刷新间隔口径纠正——实测本仓任务中心列表为 **4s** 自刷（`frontend/src/stores/task.ts` 的 `TASK_AUTO_REFRESH_MS = 4000`…），非 5 秒；原表述按只追加纪律保留留痕。` | **一致** |
| 9 | `docs/evidence/W3-combined任务中心分页核验.md:58` | 是否引用旧锚 | `:58` 引用 `frontend/src/views/TaskListView.vue:151-155`（`setInterval(reload, 5000)`）+ `TaskListView.vue:72-74` + `export/StepExport.vue:151-160`（1.5s）。**现树中 `TaskListView.vue` 与 `StepExport.vue` 均不存在**（`frontend/src/views/` 只有 DataBrowser/Definitions/ExportWizard/ImportWizard/TaskCenter；全仓 Glob `**/StepExport*` 零命中） | **不一致（引用现树不存在的旧锚）** |
| 10 | HEAD / 工作树 | HEAD=6c9c699…、backend 空 | `git rev-parse HEAD` → `6c9c699aa680297ecf0a83ec39eeae8716b68431`；`git status --porcelain backend/` → **空输出** | **一致** |

## ② 不一致项（附实际行内容）

1. **锚点 1 路径**：不存在 `task/TaskSseService.java`；实体文件 = `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`（包声明 `package com.example.configmgr.task.service;`）。
2. **锚点 3 行号**：`app.task.sse` 段实为 `:191-203`（`task:` :191、`sse:` :192、`write-timeout: 15s` :202、`emitter-timeout: 5m` :203）。`:198` 实际内容 = `#     原为 SseEmitter(0L) = 该 emitter 没有生命周期超时 —— 但这不等于"连接没有释放手段"：`（注释，非 emitter-timeout）。job 段注记起于 `:218-219`，`:220 heartbeat-interval: 30s`。文件共 232 行。
3. **锚点 6 / `docs/M2-排期计划.md:280`（R-88）**：行内仍写 `stores/task.ts`（`TASK_AUTO_REFRESH_MS=4000` / 模块级 `setInterval` / `startAutoRefresh:182-199`）。现态 `startAutoRefresh` 在 `:157`（`startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {`，`setInterval` 在 `:162`，体至 `:167`）；`:182-199` 现为 `refreshCurrent` 体（`:182 return` / `:186 const overview = await tasksApi.getTaskOverview(taskId)` / `:199 },`）。
4. **锚点 6 / `docs/M2-排期计划.md:281`（R-89）**：行内仍写 `views/ImportWizardView.vue:748-795`。该区间现为只读态点节点函数与 javadoc（`:750` 起 `* 只读态点节点查看（施工卡 §S3b）…`，`:748 }` 为上一函数收尾）；`watchJob({` 实际调用点在 `:844`（`function beginWatchJob(...)` 体内），兜底轮询 `setInterval` 在 `frontend/src/utils/job-watch.ts:106`。
5. **锚点 9 / `docs/evidence/W3-combined任务中心分页核验.md:58`**：`**前端消费** — frontend/src/views/TaskListView.vue:151-155：列表挂载后 setInterval(reload, 5000) 静默刷新…行内进度条用 latestJob.progress / latestJob.total（TaskListView.vue:72-74）。作业级轮询见 export/StepExport.vue:151-160（1.5s）。` —— `TaskListView.vue`、`export/StepExport.vue` 现树皆不存在。

## ③ 全后端 HEARTBEAT 命中完整清单（`backend/src`）

字面 `"HEARTBEAT"`（2 处）：
- `task/service/TaskSseService.java:88` — 任务级首帧发布点（订阅时补发，唯一任务级 HEARTBEAT 发布点）。
- `test/.../task/service/TaskSseServiceTest.java:145` — 测试用例桩件直呼 `sendEvent(..., "HEARTBEAT", Map.of())`（非生产发布点）。

常量名/类型名族（大小写敏感 grep，`main` 下 12 处）：
- `task/service/TaskSseService.java:153 / :166 / :181 / :199` — 均为该首帧的 javadoc 与 `sendFrame` 上下文串（非独立发布点）。
- `ai/gate/ConfirmGate.java:52`（javadoc「每 {@value #HEARTBEAT_SECONDS} 秒发一帧」）、`:77`（`HEARTBEAT_SECONDS = 2`）、`:240`（`latch.await(Math.min(remain, HEARTBEAT_SECONDS * 1000L))`）— 确认门 2s 等待期心跳（等待线程自己发，无独立调度）。
- `ai/run/ResilientChatService.java:96`（`STREAMING_HEARTBEAT_MILLIS = 5000L`）、`:308 / :311`（流式期每 5s 至多补一帧的判据）— 流式 5s 补帧。
- `ai/web/AiController.java:682 / :683`（javadoc）、`:691 / :692`（诊断快照 `"suspend", ConfirmGate.HEARTBEAT_SECONDS * 1000L` / `"streaming", ResilientChatService.STREAMING_HEARTBEAT_MILLIS`）— 仅观测面取值，非发布点。
- `ai/run/RunStore.java:85`（`HEARTBEAT_TYPE = "heartbeat"`）、`:574`（心跳帧判据）；`ai/run/SseChatEmitter.java:441 / :731`（心跳帧过滤）；`ai/run/CancellationRegistry.java:29`（javadoc 引 `HEARTBEAT_SECONDS`）。

测试侧另有：`test/.../ai/web/AiControllerHealthSseDeliveryTest.java:64,66`（取两常量值）、`test/.../ai/conformance/FrameContract.java:68,102`、`SseFrameSequenceConformanceTest.java:155`、`InMemoryRunStore.java:324`（心跳帧过滤/契约）。

结论：任务级 HEARTBEAT 唯一发布点 = `TaskSseService.java:88`（订阅即发一次、无周期调度）；AI 侧另有确认门 2s 与流式 5s 两处，均在 AI 域、非任务通道。
