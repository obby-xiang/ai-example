# W2 核验：combined 分支「终态补发——订阅晚于作业完成时能否补拿进度/终态」

- 被核验分支：`ai-example-claude-opus-5.5+deepseek-v4-pro`（工作副本 `<REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro`，HEAD = `a98d0b2`，`git status --porcelain` 空 = 未改动源码/配置）
- 核验日期：2026-10-07
- 结论：**【机制不存在】**——本分支**没有**任何"补发/回放/终态快照事件"机制：SSE 通道只对"订阅那一刻在线"的客户端推送，晚订阅者只能拿到一条 `HEARTBEAT`；错过的事件与终态事件都不会补发。**等价的补拿能力由 REST 快照接口提供**（`GET /api/jobs/{id}`、`GET /api/tasks/{id}/overview`、`GET /api/tasks/{id}/jobs`、`GET /api/jobs/{id}/issues`），晚订阅方靠**轮询快照**能完整拿到终态（作业状态/进度计数/条目明细/结果文件），实测不 hang、不空。
- 原始记录附件（同目录）：
  - `W2-附件-A-全程在线SSE原文.txt`（作业启动前订阅，带 1ms 精度本地时间戳的原始 SSE 流）
  - `W2-附件-B-中途订阅SSE原文.txt`（作业进行中订阅）
  - `W2-附件-C-终态后订阅SSE原文.txt`（终态之后才订阅）
  - `W2-附件-作业时间线.txt`（轮询作业状态的逐帧记录）
  - `W2-附件-快照接口原文.json`（8 个快照接口的原始响应体）

---

## 1. 核验目标

| # | 待验项 | 出处 |
|---|--------|------|
| S1 | ADR-8 逐机制证据标注：「**终态补发（V1~V9 零覆盖）**」 | `<REPO_ROOT>/docs/adr/DECISION-CARDS.md:94-96` |
| S2 | 分支自述的进度通道：「作业偶发…进度推送：SSE（任务级别）；**前端作业进度当前用 1.5s 轮询 `GET /api/jobs/{id}`**（与 SSE 并存）」 | 该分支 `docs/technical-design.md:184`、`docs/implementation-plan.md:182` |

核验问题：进度事件走 SSE 还是轮询？作业终态后结果/进度还能否查询？"终态补发"具体指什么（事件回放？终态快照接口？）？晚订阅方（订阅发生在作业完成之后）能否拿到完整终态，还是会 hang 住或拿到空？

---

## 2. 源码机制摘要（未做任何修改）

**SSE 通道本体** — `task/service/TaskSseService.java`
- `:23` 状态只有一张在线表：`Map<Long, List<SseEmitter>> emitters`（内存、按 taskId 分组）；**没有事件缓冲、没有事件序号、没有 lastEventId、没有持久化**。
- `:25-36` `subscribe(taskId)`：新建 `SseEmitter(0L)`（永不超时）→ 加入在线表 → 注册 `onCompletion/onTimeout/onError` 清理（:29-31）→ **立刻只发一条 `HEARTBEAT`**（:34）→ 返回。**订阅动作本身不回放任何历史事件、也不带终态快照**。
- `:49-65` `publish(taskId,type,data)`：`emitters.get(taskId)` 为空即**直接 return（事件被丢弃）**（:50-51）；有订阅者时逐条 `send`，把 IO 异常的 emitter 收进 `dead` 并移除（:53-63）。即"只在订阅者在线时送达"。
- 事件类型共 4 种：`HEARTBEAT`（:34）、`TASK_CHANGED`（:39-47，`@EventListener TaskChangedEvent`）、`JOB_PROGRESS`、`JOB_DONE`。
- 发布点（全仓 grep）：`ImportJobRunner.java:182-184`(JOB_DONE) / `:198-203`(JOB_PROGRESS)、`ExportJobRunner.java:160-162` / `:165-170`、`PrecheckJobRunner.java:247` / `:291`、`PublishJobRunner.java:209` / `:272`、`JobService.java:74-75`（取消时 JOB_DONE CANCELLED）。
- **全仓无定时任务、无事件存储**：`grep -rn "@Scheduled\|EnableScheduling" backend/src/main/java` → 无命中；`grep -rniE "lastEventId|replay|event_store|eventStore|resumeFrom" backend/src/main/java` → 仅命中 AI 记忆模块的注释，业务事件侧无命中。

**SSE 端点与快照端点** — `task/controller/TaskController.java`
- `:115-118` `GET /api/tasks/{id}/events`（`text/event-stream`）→ `taskSseService.subscribe(id)`；
- `:37-48` `GET /api/tasks?type&status&keyword&page&size` 列表，每条含 `latestJob`（作业状态/进度）；
- `:53-59` `GET /api/tasks/{id}/overview` → `{task, jobs[], files[]}`；
- `job/controller/JobController.java:33-36` `GET /api/tasks/{taskId}/jobs`、`:38-41` `GET /api/jobs/{jobId}`、`:49-54` `GET /api/jobs/{jobId}/issues`、`:56-61` `GET /api/jobs/{jobId}/diff`。
- 前端消费方式：任务列表 5s 刷新（`frontend/src/views/TaskListView.vue:151-155`），作业进度 1.5s 轮询（`frontend/src/views/export/StepExport.vue:151-160`、`import/StepImport.vue:122-134`、`import/StepPrecheck.vue:128-138`、`import/StepPublish.vue:134-142`）。

**结论性判定（读源码即可确定）**：本分支的"进度/终态传递"= **在线 SSE 推送 + 离线 REST 轮询快照**，不存在事件回放，也不存在"作业终态事件在订阅时补发一次"的机制。

---

## 3. 实验过程

### 3.0 环境

```
cd <REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro/backend
"<MAVEN_HOME>/bin/mvn.cmd" -s maven-settings.xml -DskipTests package
export DEEPSEEK_API_KEY=dummy-for-verification
java -jar target/config-mgr.jar --server.port=18295
```
任务 38 / 作业 38：`IMPORT` + `CURRENCY.xlsx`（3000 行）。作业实际运行 `01:12:21.832`（createdAt）→ `01:12:33.188`（finishedAt，≈11.4 s），期间发出 30 条 `JOB_PROGRESS`（processed 99→2999）+ 1 条 `JOB_DONE`。

三路订阅（同一任务、同一作业）：
- **A：作业启动前 1.0 s 订阅，全程在线 18 s**（原始流见 `W2-附件-A-全程在线SSE原文.txt`）
- **B：作业进行中（+4 s，已处理约 1100 行）订阅 8 s**（`W2-附件-B-中途订阅SSE原文.txt`）
- **C：作业终态之后（+0.7 s）才订阅 10 s**（`W2-附件-C-终态后订阅SSE原文.txt`）

### 3.1 订阅 A（全程在线）——事件流完整

```
01:12:20.824 data:{"data":{},"type":"HEARTBEAT"}                                  ← 订阅瞬间：只有心跳
01:12:22.235 data:{"data":{"jobId":38,"total":3000,"defCode":"CURRENCY","jobType":"IMPORT","pct":3,"processed":99},"type":"JOB_PROGRESS"}
01:12:22.546 data:{... "pct":6,"processed":199 ...}      （此后每 ~0.3 s 一条，共 30 条）
...
01:12:33.214 data:{"data":{"jobId":38,...,"pct":99,"processed":2999},"type":"JOB_PROGRESS"}
01:12:33.272 data:{"data":{"jobType":"IMPORT","jobId":38,"status":"COMPLETED","errors":0},"type":"JOB_DONE"}
```
→ 在线订阅者能拿到**从订阅时刻起**的全部进度与终态（此处恰好等同于全量）。注意订阅瞬间**没有**任何历史或"当前进度"快照，只有 `HEARTBEAT`。

### 3.2 订阅 B（中途订阅）——错过的部分不补

订阅于 `01:12:26.102`（此时 A 已收到 processed=999/1099/1199 三条）：

```
01:12:26.102 data:{"data":{},"type":"HEARTBEAT"}
01:12:26.324 data:{... "pct":43,"processed":1299 ...}     ← 第一条数据事件从 1299 开始
01:12:26.696 data:{... "pct":46,"processed":1399 ...}
...
01:12:33.272 data:{"data":{"jobType":"IMPORT","jobId":38,"status":"COMPLETED","errors":0},"type":"JOB_DONE"}
```
→ 与 A 逐条比对：`processed = 99/199/…/1199` 这 12 条事件**从未送达 B**，也没有"当前进度"补发。B 拿到的只有订阅之后的事件，且收尾的 `JOB_DONE` 正常送达（因为它在订阅期内发生）。

### 3.3 订阅 C（终态之后订阅）——只拿到心跳，无补发

`GET /api/jobs/38` 已在 `01:12:33.303` 显示 `COMPLETED`；`01:12:38.981` 才订阅，持续 10 s（`--max-time 10`）：

```
01:12:39.024 data:{"data":{},"type":"HEARTBEAT"}
（此后 10 秒内流保持打开，共 1 条事件 / 2 行记录，无 JOB_PROGRESS 回放、无 JOB_DONE 补发、无终态快照事件）
```
→ **不 hang、不报错，但也什么终态都拿不到**；连接会一直挂着（`SseEmitter(0L)` 无超时，`TaskSseService.java:26`），需要客户端自己断开。

### 3.4 晚订阅方改用快照接口——终态可完整补拿

C 订阅期间/之后，用轮询快照重新取终态（原文见 `W2-附件-快照接口原文.json`）：

```
GET /api/jobs/38 ->
{"id":38,"taskId":38,"jobType":"IMPORT","status":"COMPLETED","progress":1,"total":1,
 "errorCount":0,"items":[{"jobId":38,"defCode":"CURRENCY","status":"COMPLETED","processed":3000,"total":3000}],
 "createdAt":"2026-07-... ","startedAt":"2026-10-07T01:12:21.833439","finishedAt":"2026-10-07T01:12:33.188462"}

GET /api/tasks/38/overview ->
  task {"id":38,"type":"IMPORT","status":"ACTIVE","currentStep":"UPLOAD"}
  items [{"defCode":"CURRENCY","sortOrder":0,"status":"IMPORTED"}]
  jobs  [(38,"COMPLETED",progress=1,finishedAt=2026-10-07T01:12:33.188462)]
  files [("CURRENCY","UPLOAD","CURRENCY.xlsx")]

GET /api/tasks/38/jobs   -> [job 38（同上，含 items 明细）]
GET /api/jobs/38/issues  -> {"content":[],"totalElements":0,...}   （无错误即空，不报错）
```
→ 晚订阅方通过轮询同样能拿到：最终状态、条目级 `processed/total`、任务项状态、结果文件清单、问题清单。缺的只有"过程曲线"（每 100 行的时间序列）。

### 3.5 附带实测：作业运行中，"快照侧"看不到进度

同一作业运行期间的 8 次快照（原文见 `W2-附件-快照接口原文.json` 与 `W2-附件-作业时间线.txt`）：

```
01:12:26.245 status=PENDING progress=0 finishedAt=None items=[]
...（每 1 s 一帧，全部 PENDING）...
01:12:32.290 status=PENDING progress=0 finishedAt=None items=[]
01:12:33.303 status=COMPLETED progress=1 finishedAt=2026-10-07T01:12:33.188462 items=[('CURRENCY','COMPLETED',3000,3000)]
```
→ 作业是单事务（`ImportJobRunner.java:55`），`RUNNING`/进度对其它连接不可见；**运行中的实时进度只能从 SSE 的 `JOB_PROGRESS` 拿到**，轮询快照只能看到"开始前"和"结束后"两个态。这一点与 W1 的边界 B1 是同一根因。

---

## 4. 结论

**【机制不存在】**（对"终态补发"这一机制名本身）。

1. 本分支不存在事件回放 / 终态补发事件 / `lastEventId` 续传 / 事件持久化：`TaskSseService` 只有一张在线 emitter 表，`publish` 对无订阅者的事件直接丢弃（`TaskSseService.java:49-51`），订阅时只补一条 `HEARTBEAT`（:34）。
2. 实测三路订阅给出直接证据：A 全程在线→完整；B 中途订阅→**丢失订阅前的 12 条事件且无补发**；C 终态后订阅→**10 s 内只有 1 条 HEARTBEAT**，无终态事件补发，也不 hang（连接常驻）。
3. 但"晚订阅方能否拿到完整终态"的**业务需求并未落空**：作业终态后结果/进度**可查**——`GET /api/jobs/{id}`（终态 + 条目 processed/total）、`GET /api/tasks/{id}/overview`、`GET /api/tasks/{id}/jobs`、`GET /api/jobs/{id}/issues`、`GET /api/jobs/{id}/diff`、`GET /api/tasks/{id}/files` 全部可用。合流时若 ADR-8 想要的是"晚订阅不掉终态"，**现有实现靠轮询快照即可满足**；若想要的是"事件级回放/断线续传"，则需新增机制（当前不存在）。

---

## 5. 边界说明

| # | 观察（实测） | 证据 |
|---|-------------|------|
| B1 | 订阅瞬间只有 `HEARTBEAT`，**没有"当前进度"快照**：晚订阅方若不主动轮询 `GET /api/jobs/{id}`，UI 上会出现"进度条从 0 开始"的观感（错过的那段等于不可见） | 订阅 A/B/C 首行均为 `HEARTBEAT` |
| B2 | `JOB_PROGRESS` 事件在作业**事务未提交**时就已发出（`ImportJobRunner.java:200` 在事务内），故 SSE 进度是"领先于数据库"的：事件说 processed=2999 时，`GET /api/jobs/38` 仍返回 PENDING/0 | 3.5 与订阅 A 时间线对照 |
| B3 | SSE emitter 无超时（`SseEmitter(0L)`）且订阅时不发送任何"作业已结束"信号 → 终态后订阅会**永久挂住**，客户端必须自行超时断开 | 订阅 C 持续 10 s 仅 1 条事件 |
| B4 | 客户端**异常**断线（RST）会在服务端产生 2 条 `AsyncRequestNotUsableException: Connection reset by peer`，被 `GlobalExceptionHandler` 按 `ERROR`/500 记录（`common/GlobalExceptionHandler.java` 兜底分支）。本条由我在调参过程中人为中断的客户端触发；随后用 `curl --max-time` 正常关闭的 3 路订阅**未产生任何 ERROR**。 | `W1-附件-后端日志摘录.log`（01:11:45.685、01:11:49.336 两条 ERROR，波形对应被我中断的订阅；W2 正式三路订阅期间无 ERROR） |
| B5 | `TASK_CHANGED` 是唯一的"任务级"事件，但它由业务写操作触发（`TaskService.publishTaskChanged`，如 `TaskService.java:55/90/104/113/145/156/168`），作业终态本身不发 `TASK_CHANGED`（只发 `JOB_DONE`） | `TaskSseService.java:38-47` + 发布点 grep |

---

## 6. 未覆盖/未做

- 未测断线重连后能否续传（结论已由源码判定无 `lastEventId` 支持，未构造重连用例）。
- 未测多任务并发订阅、`emitters` 泄漏（断线客户端是否残留）——B4 的 ERROR 提示存在残留清理路径，但未做量化观测。
- 未测 AI run 级别的 SSE 流（`/api/ai/sessions/{sid}/runs`）是否具备补发能力：本次只核验任务级 `JOB_PROGRESS/JOB_DONE` 通道。
- 全程只读源码 + 调 REST；未修改任何分支文件、未做 git 写操作（`git status --porcelain` 为空）。
