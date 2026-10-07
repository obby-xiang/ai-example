# W1 核验：combined 分支「作业取消注册表 JobCancellationRegistry——取消真正生效」

- 被核验分支：`ai-example-claude-opus-5.5+deepseek-v4-pro`（工作副本 `<REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro`，HEAD = `a98d0b2`，`git status --porcelain` 空 = 未改动源码/配置）
- 核验日期：2026-10-07
- 结论：**【实测-符合】**（取消确实生效且可复现；同时实测出 6 条边界，见 §5，其中 B1/B3/B4 对合流方案有直接影响）
- 原始记录附件（同目录）：
  - `W1-附件-执行中取消-请求响应原文.json`（用例 B：作业执行中取消的完整请求/响应/耗时）
  - `W1-附件-主组与对照-请求响应原文.json`（探索用主组：证明对已终态作业取消的行为）
  - `W1-附件-对照组与立即取消-请求响应原文.json`（对照组 + 用例 C）
  - 说明：两份附件中 `/api/jobs/{id}/diff` 的响应原文（3000 条 staging 行、约 90 万字符/次）已按 `resp_truncated=true` 标注为"只保留行数占位"，行数见 §3；其余字段为原始记录。
  - `W1-附件-作业时间线.txt`（对照组与用例 C 的 500ms 轮询逐帧记录）
  - `W1-附件-后端日志摘录.log`（作业启动行与全部 ERROR 行；全库仅 2 条 ERROR，均为我人为中断的 SSE 客户端所致，见 W2 §5-B4——**取消路径不产生任何异常**）

---

## 1. 核验目标（机制自述与出处）

| # | 自述/待验项 | 出处 |
|---|------------|------|
| S1 | ADR-8 逐机制证据标注把「取消注册表」列为**未实测**：SP-01d 测的是 spike 自建 CancellationRegistry，非 combined 实现 | `<REPO_ROOT>/docs/adr/DECISION-CARDS.md:94-96` |
| S2 | 源码自述：「作业取消标志注册表。作业执行器在循环批次之间检查标志，用户取消后尽早停止（**而非只改数据库状态**）」 | `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:9-12` |

待核验问题：取消 API 是否真正让**执行线程**停下来？停在哪个检查点？终态是什么？取消后已写入的部分数据是保留还是回滚？不取消的同类作业是否正常完成（特异性对照）？

---

## 2. 源码机制摘要（未做任何修改）

**注册表本体** — `job/service/JobCancellationRegistry.java:14-36`
- 结构：`ConcurrentHashMap<Long, AtomicBoolean> flags`（:16）；
- `register(jobId)` :18-20 ／ `isCancelled(jobId)` :22-25 ／ `cancel(jobId)` :27-32（只置位，不等线程）／ `unregister(jobId)` :34-36。
- **标志是进程内内存 Map，不是 Redis/数据库共享标志**；跨实例不可见（见 §5-B4）。

**注册/注销时机**
- 注册：`job/service/JobService.java:39`（`createAndStart` 事务内，紧跟 `jobRepository.save` 之后）；异步执行在**事务提交后**才启动（:41-50 `TransactionSynchronization.afterCommit`）。
- 注销：`job/service/AsyncJobExecutor.java:39-41`（`finally` 中 `unregister`），即作业线程退出即摘除标志。

**取消 API** — `job/controller/JobController.java:43-47`（`DELETE /api/jobs/{jobId}`）→ `job/service/JobService.java:67-77`
- :70 守卫：仅当 `status ∈ {RUNNING, PENDING}` 才动作；
- :71-73 置取消标志 → 置作业状态 `CANCELLED` → 保存；
- :74-75 推 SSE `JOB_DONE {jobId, status:"CANCELLED"}`；
- **守卫不成立时方法体什么都不做、也不报错**，控制器仍返回 `200 {"success":true}`（见 §3-E4）。

**执行线程的检查点（4 个 runner 均带 `@Transactional`）**

| runner | 条目级检查 | 批次级检查（batchSize=100 + demo-batch-delay 300ms） | 取消终态 |
|---|---|---|---|
| Import | `ImportJobRunner.java:70-74` | `:127-140`（命中后 `:134-139` 置 job_item `CANCELLED` 并 break） | `:170-174` → `CANCELLED` |
| Export | `ExportJobRunner.java:63-67` | `:93-105` | `:144-153` |
| Precheck | `PrecheckJobRunner.java:79-84` | `:168-176` | `:235` |
| Publish | `PublishJobRunner.java:83-84` | `:158-166` | `:192` |

- 作业入口 `@Transactional`：`ImportJobRunner.java:55-56`（Export :51-52、Precheck :57-58、Publish :60-61）→ **整个作业是单个数据库事务**，这一点决定 §5-B1/B2。
- 批次节流参数来自 `backend/src/main/resources/application.yml` 的 `app.job.batch-size: 100` / `app.job.demo-batch-delay-ms: 300`（本次**未修改**配置，靠数据量拉长作业）。
- 前端调用：`frontend/src/api/jobs.js:6`（`cancelJob = DELETE /jobs/{id}`）；`frontend/src/views/export/StepExport.vue:93`（`isRunning = status==='RUNNING'||'PENDING'`）、`:151-160`（1.5s 轮询作业）。

---

## 3. 实验过程

### 3.0 环境与命令

```
cd <REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro/backend
"<MAVEN_HOME>/bin/mvn.cmd" -s maven-settings.xml -DskipTests package     # BUILD SUCCESS（3.1 s，增量）
export DEEPSEEK_API_KEY=dummy-for-verification                            # 占位值，本次三实验零 AI 调用
java -jar target/config-mgr.jar --server.port=18295                       # 启动前 netstat 确认 18295 空闲
```
- 端口 18295（未触碰禁用段）；H2 文件库 `backend/data/config_mgr_db.mv.db` 启动前已备份到 `<临时目录>/db-backup/`，**未删库**，沿用既有数据。
- 实验数据：用 `openpyxl` 生成 `CURRENCY.xlsx`（表头 `*货币代码 / *货币名称 / *对人民币汇率 / 小数位数`，3000 个唯一行），经 `POST /api/tasks/{id}/files/upload` 上传并匹配到 def `CURRENCY`。
- 作业时长：同一文件导入约 11.3~12.0 s（30 个 100 行批次 × 300ms 节流 + 逐行 `findRow` 查询），足够形成"执行中取消"窗口。

### 3.1 探索组（主组）：对已终态作业发取消 → 看 API 行为

`W1-附件-主组与对照-请求响应原文.json`。作业 33 于 `01:10:29.639` 创建，14.9 s 后自行完成（`GET /api/jobs/33` 在 `01:10:44.522` 已返回 `COMPLETED`），此刻 `01:10:46.737` 才发取消：

```
01:10:46.737 DELETE /api/jobs/33 -> 200 (0.015s)
HTTP body: {"success":true}
01:10:46.751 GET    /api/jobs/33 -> 200
  {"success":true,"data":{"id":33,...,"status":"COMPLETED","progress":1,"total":1,
   "items":[{"defCode":"CURRENCY","status":"COMPLETED","processed":3000,"total":3000}],
   "finishedAt":"2026-10-07T01:10:42.482136"}}
```
→ 终态**未被改写**（仍 COMPLETED），API 却回 200 成功（§5-B3）。这一组同时暴露了 B1：从 `01:10:29.645` 到 `01:10:44.522` 共 15 s 的轮询中，作业**始终显示 `PENDING`**，从未出现 `RUNNING`。

### 3.2 用例 B（主结论）：作业执行中取消

`W1-附件-执行中取消-请求响应原文.json`。

```
01:10:56.671 POST /api/tasks {"type":"IMPORT","title":"W1-取消核验-执行中取消"} -> 201  task/34
01:10:56.682 POST /api/tasks/34/select-defs {"defCodes":["CURRENCY"]}           -> 200
01:10:56.70x POST /api/tasks/34/files/upload  CURRENCY.xlsx(3000 行)            -> 200
01:10:56.718 POST /api/tasks/34/jobs {"jobType":"IMPORT"} -> 201  job/34
            响应原文：{"id":34,"jobType":"IMPORT","status":"RUNNING","progress":0,"total":0,
                      "items":[],"startedAt":"2026-10-07T01:10:56.719126","finishedAt":null}
01:10:59.720 === 发出取消请求（启动后 +3.0 s，此时约 900 行已入 staging） ===
01:10:59.737 DELETE /api/jobs/34 -> 200 (0.017s)   {"success":true}
01:10:59.741 GET /api/jobs/34 -> 200  status=CANCELLED  progress=0  finishedAt=null  items=[]
```

再等作业线程收尾（它仍在自己的事务里），`01:10:59.916` 之后复查：

```
GET /api/jobs/34 ->
{"id":34,"jobType":"IMPORT","status":"CANCELLED","progress":0,"total":1,"errorCount":0,
 "items":[{"defCode":"CURRENCY","status":"CANCELLED","processed":899,"total":3000}],
 "startedAt":"2026-10-07T01:10:56.719126","finishedAt":"2026-10-07T01:10:59.916257"}

GET /api/jobs/34/diff -> 200   行数 = 900        ← 取消后的最终 staging 行数（任务 34 名下）
  对照：取消瞬间（01:10:59.766，作业事务尚未提交）同一接口返回 {"success":true,"data":[]} = 0 行，
        证明"作业单事务"边界：取消时的未提交写入对外不可见，提交后才一次性可见 900 行。
GET /api/tasks/34/overview -> jobs[0]=CANCELLED；task.status=ACTIVE，currentStep=UPLOAD，
  items[0]={defCode:"CURRENCY",status:"PENDING"}
GET /api/data/CURRENCY/count -> {"count":5}          （发布态数据未被触碰，仍是种子 5 行）
```

**取消延迟量化**：`DELETE` 返回于 `01:10:59.737`，作业线程提交终态于 `01:10:59.916` → **≈0.18 s**；停在 900/3000 行（第 9 个批次边界），job_item 置 `CANCELLED`（`processed=899` 是 0 基索引，代表已处理 900 行）。后端日志全程无异常（`W1-附件-后端日志摘录.log`：仅有 `Starting job 34 type=IMPORT taskId=34`，取消路径无 stack trace）。

### 3.3 用例 C：启动后 0.15 s 立即取消（PENDING 窗口）

`W1-附件-对照组与立即取消-请求响应原文.json` + `W1-附件-作业时间线.txt`。

```
01:11:32.62x POST /api/tasks/36/jobs (IMPORT) -> 201 job/36
01:11:32.775 DELETE /api/jobs/36 -> 200 (0.014s) {"success":true}
01:11:32.790 GET /api/jobs/36 -> status=CANCELLED  finishedAt=null  items=[]
01:11:33.294 GET /api/jobs/36 -> status=CANCELLED  finishedAt=2026-10-07T01:11:33.003528
                                 items=[("CURRENCY","CANCELLED",99,3000)]
GET /api/jobs/36/diff -> 100 行 staging 已提交
GET /api/tasks/36 -> task.status=ACTIVE, items[0].status=PENDING
```
→ 即便取消发生在执行线程刚起步（1 个批次内），标志仍被读到：作业在**第一个批次边界**停下（100 行），终态 CANCELLED。

### 3.4 对照组：同参数、不取消

```
01:11:20.798 POST /api/tasks/35/jobs (IMPORT) -> 201 job/35
轮询（500ms）01:11:20.804 → 01:11:32.539 = 全程 PENDING（24 帧，见 W1-附件-作业时间线.txt）
01:11:32.539 GET /api/jobs/35 -> status=COMPLETED  progress=1
   items=[("CURRENCY","COMPLETED",3000,3000)]  finishedAt=2026-10-07T01:11:32.147732
GET /api/jobs/35/diff -> 3000 行 staging
GET /api/tasks/35 -> task.status=ACTIVE, currentStep=UPLOAD, items[0].status=IMPORTED
```
→ 特异性成立：不取消 = 3000 行全量入库 + job_item COMPLETED + 任务项转 `IMPORTED`；取消 = 部分行 + job_item CANCELLED + 任务项保持 `PENDING`。

---

## 4. 结论

**【实测-符合】。**

1. `DELETE /api/jobs/{id}` 通过 `JobCancellationRegistry` 的进程内标志**真正中止了执行线程**：用例 B 在 900/3000 行处停住、用例 C 在 100/3000 行处停住，作业终态 = **`CANCELLED`**（不是 FAILED/COMPLETED），job_item 同步 `CANCELLED`，并写 `finishedAt`。
2. 响应延迟：从 `DELETE` 返回到线程落终态 **≈0.18~0.23 s**（批量周期 300ms 内），即"尽早停止"成立；不是只改数据库状态。
3. **取消后部分写入保留、不回滚**：staging 行按取消前已落地的批次提交（用例 B 900 行、用例 C 100 行），`config_staging_rows` 未见清理；发布态 `config_data_rows` 完全未被触碰（仍 5 行）。
4. 对照组（不取消）正常 `COMPLETED` 且 3000 行全量写入 —— 取消路径的特异性成立。

---

## 5. 边界说明（结论不变，但合流必须处置）

| # | 观察（均为实测） | 证据 | 对合流/ADR-8 的影响 |
|---|-----------------|------|-------------------|
| B1 | **`RUNNING` 状态对外不可见**：作业执行全程（11~15 s）`GET /api/jobs/{id}` 只返回 `PENDING`、`progress=0`、`items=[]`；`RUNNING`/进度只在作业事务提交后（即结束时）才可见。原因是 4 个 runner 的 `run()` 均为 `@Transactional`，整作业一个事务。 | 主组 15 s 轮询全 PENDING；对照组 24 帧全 PENDING；③ w2 快照 | 取消守卫写的是 `RUNNING || PENDING`，因此**仍能取消**；但前端 `isRunning` 判定的"运行中"实际来自 PENDING 分支，UI 上的 RUNNING 语义与后端可见状态不一致；合流若按"先查 RUNNING 再允许取消"设计会失效 |
| B2 | 取消写入的 `CANCELLED` 与作业线程最后提交的整行数据**互相覆盖式写入同一 `jobs` 行**（取消事务读到的 `startedAt=null`、线程事务读到的 `status=RUNNING`），两笔都提交成功、未出现 H2 锁超时或乐观锁异常，最终行取"后提交者"的值（本例两者终态都是 CANCELLED，未见数据冲突）。取消不是字段级 UPDATE，而是整实体读改写。 | `W1-附件-执行中取消-请求响应原文.json`（01:10:59.741 快照 `startedAt=null` vs 终态快照 `startedAt=...719126`） | 合流改为 Redis 共享标志后，取消路径同样要避免直接读改写整行；建议改为定向 `UPDATE jobs SET status=...`（仅记录观察与建议，未改代码） |
| B3 | **对已终态作业取消静默成功**：`DELETE` 返回 `200 {"success":true}` 但状态不变（仍 COMPLETED），无 409/幂等提示，调用方无法区分"取消成功"与"早已完成"。 | 主组 01:10:46.737 | 前端 `cancelJob()` 会按成功处理并 `clearInterval`（`StepExport.vue:164-167`），用户会以为是自己停掉的；合流建议补明确返回码 |
| B4 | 标志是**进程内 `ConcurrentHashMap`**，非 Redis；ADR-8 修正③要求取消标志"以 Redis 共享标志为准（持有实例在分片/事件边界轮询可见）"。本分支实现=单实例可用、多实例不可用。 | `JobCancellationRegistry.java:16` | **继承本分支时该项必须替换实现**（属合流改造项，非本分支缺陷） |
| B5 | **取消不联动任务状态**：任务仍 `ACTIVE`、`currentStep` 不变、任务项停在 `PENDING`（对照组会变 `IMPORTED`）；作业层是终态 CANCELLED，任务层没有任何痕迹。 | 用例 B/C 的 `GET /api/tasks/{id}` | ADR-8 修正②只规定了"僵尸恢复→FAILED 联动回写任务状态"，**取消同样属于终态**却未联动；合流需明确是否补（FR-4.2 一键恢复通路会依赖它） |
| B6 | 建作业的 201 响应体里能看到 `"status":"RUNNING","startedAt":...`，而同一时刻 DB 里是 `PENDING`：`createAndStart` 返回的 `Job` 实例与异步线程持有的是**同一对象**，序列化前被异步线程改过（shared mutable state）。 | `W1-附件-执行中取消-请求响应原文.json` 第 3 条记录 vs 01:10:59.741 快照 | 仅记录；不影响取消语义，但说明"创建响应里的状态不可当权威值" |

---

## 6. 未覆盖/未做

- 未测 PRECHECK / PUBLISH 两条 runner 的取消（源码检查点已在 §2 列表给出：`PrecheckJobRunner.java:79-84/168-176/235`、`PublishJobRunner.java:83-84/158-166/192`），本次只跑了 IMPORT（+ 对照组）。取消注册表是四者共用的同一 Bean，风险低，但严格说属未实测。
- 未测多实例场景（本分支标志为内存 Map，B4 已由源码判定，未做双实例实测）。
- 未做并发压测（同时取消多个作业）、未测取消 PRE-CHECK 的行级检查点语义。
- 全程只读源码 + 调 REST；未修改任何分支文件、未做 git 写操作（`git status --porcelain` 为空）。
- 实验产生的数据：新增测试任务（id 33~39 属本次 W1/W2）与 staging 行留在该分支 H2 开发库中（已按纪律先备份库文件），发布态数据未变。
