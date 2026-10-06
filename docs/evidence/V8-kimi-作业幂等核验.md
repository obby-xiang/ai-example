# V8 核验：kimi-k3「作业幂等——同任务同 kind 作业重复提交时拒绝重复执行并返回现有作业 id」

- **被核验分支**：`ai-example-kimi-k3`（`git rev-parse --abbrev-ref HEAD` = `kimi-k3`，HEAD = `cf5b8a53275f6045c2285611149c4201622b5e53`）
- **核验日期**：2026-10-06（本地时间 19:00 前后）
- **核验方式**：以 `java -jar` 启动真实后端（**全新空库**），用真实 HTTP 请求在作业 RUNNING 期间并发重复提交，并以 H2 文件库直查作业表作最终佐证
- **核验端口**：18293（`--server.port=18293` 覆盖分支默认 8090）
- **结论**：**【实测-符合】**（附 1 项设计边界，见 §5）

---

## 1. 核验目标（自述原文与出处）

### 1.1 本核验项的定义来源（任务书原话）

> kimi-k3 分支自述"作业幂等——同任务同 kind 作业重复提交时拒绝重复执行并返回现有作业 id"

### 1.2 被核验分支自身的声明原文

| 出处 | 原文 |
|---|---|
| `ai-example-kimi-k3/docs/05-verification.md:16` | \| A6 \| ✅ \| **RUNNING 中重复提交返回同一作业 id，未新建** \| |
| `ai-example-kimi-k3/backend/.../service/JobService.java:33` | 「作业生命周期：创建（**幂等**）/查询/取消/导出结果/暂存区」 |
| `ai-example-kimi-k3/backend/.../service/JobService.java:55` | 「导出作业。**幂等：同任务同 kind 有 PENDING/RUNNING 作业时直接返回该作业**」 |
| `ai-example-kimi-k3/backend/.../service/JobService.java:79` | 「检查/导入作业（请求结构相同）」 |
| `ai-example-kimi-k3/backend/.../repository/JobRunRepository.java:12` | `Optional<JobRun> findFirstByTaskIdAndKindAndStatusIn(Long taskId, String kind, Collection<String> statuses);` |

**拆解为可核验断言**：

- **C1**：作业创建 API 在**同任务 + 同 kind** 且已存在 `PENDING`/`RUNNING` 作业时，**不新建作业**，直接返回已有作业。
- **C2**：重复提交的响应中 `id` 与首次提交**相同**。
- **C3**：重复提交后数据库中**不产生第二条作业记录**。
- **C4**：幂等判定按 **kind 隔离**（不同 kind 不受影响）。
- **C5**：作业完成后（终态 SUCCESS）再提交同 kind，行为符合代码设计。

### 1.3 幂等判定逻辑（源码实读）

`backend/src/main/java/com/example/quickstart/service/JobService.java`

```java
// 第 55-63 行（EXPORT）
/** 导出作业。幂等：同任务同 kind 有 PENDING/RUNNING 作业时直接返回该作业 */
@Transactional
public synchronized JobRunDto createExportJob(Long taskId, ExportJobRequest req) {
    taskService.getOrThrow(taskId);
    var existing = jobRunRepository.findFirstByTaskIdAndKindAndStatusIn(
            taskId, "EXPORT", List.of("PENDING", "RUNNING"));
    if (existing.isPresent()) {
        return toDto(existing.get());
    }
    ...
}

// 第 80-87 行（CHECK / IMPORT，kind 由入参决定）
public synchronized JobRunDto createRowsJob(Long taskId, String kind, RowsJobRequest req) {
    taskService.getOrThrow(taskId);
    var existing = jobRunRepository.findFirstByTaskIdAndKindAndStatusIn(
            taskId, kind, List.of("PENDING", "RUNNING"));
    if (existing.isPresent()) { return toDto(existing.get()); }
    ...
}

// 第 104-111 行（PUBLISH，同样模式）
public synchronized JobRunDto createPublishJob(Long taskId) { ... List.of("PENDING","RUNNING") ... }
```

要点：

- **RUNNING 如何查询**：与 PENDING 用**同一个方法** `findFirstByTaskIdAndKindAndStatusIn(taskId, kind, ["PENDING","RUNNING"])`，即状态过滤是 `IN (PENDING, RUNNING)`，不区分二者。
- 作业状态机（`entity/JobRun.java:34` 注释原文）：`PENDING / RUNNING / SUCCESS / FAILED / CANCELLED`。
- 三处创建方法均为 `synchronized`（单实例 JVM 内串行化），额外降低并发重复创建的概率。
- 新作业先落库为 `PENDING`，再在 `TransactionSynchronization.afterCommit()` 中提交线程池（`JobService.java:122-151`），避免执行线程读不到未提交行。

### 1.4 作业创建 API（`controller/JobController.java`）

| 方法 | 路径 | kind |
|---|---|---|
| POST | `/api/tasks/{id}/jobs/export` | `EXPORT` |
| POST | `/api/tasks/{id}/jobs/check` | `CHECK` |
| POST | `/api/tasks/{id}/jobs/import` | `IMPORT` |
| POST | `/api/tasks/{id}/jobs/publish` | `PUBLISH` |
| GET | `/api/jobs/{jobId}` | 查询单个作业（状态/进度/明细） |
| POST | `/api/jobs/{jobId}/cancel` | 取消 |
| GET | `/api/jobs/{jobId}/export-results` | 导出结果 |

> **注意**：**不存在**"按任务列出全部作业"的 HTTP 接口（`TaskDto` 不含作业列表；`findTop3ByTaskIdOrderByIdDesc` 仅供 AI 工具内部使用）。因此 C3"未产生第二条记录"的最终佐证只能落到**数据库层直查**，见 §3.4。

---

## 2. 环境与核验方法

| 项 | 实际值 |
|---|---|
| OS / Shell | Windows，Git Bash（`<GIT_HOME>`） |
| JDK | `java version "21.0.12" 2026-07-21 LTS` |
| Maven | `"<MAVEN_HOME>/bin/mvn.cmd"` |
| 构建命令 | `cd backend && mvn -B -DskipTests package` → 产物 `backend/target/quickstart-backend-1.0.0.jar`（69,021,758 字节） |
| 启动命令 | `cd backend && export DEEPSEEK_API_KEY=*** && java -jar target/quickstart-backend-1.0.0.jar --server.port=18293` |
| 启动证据 | 日志：`Tomcat started on port 18293 (http)`；`Started QuickstartApplication in 8.136 seconds`；`数据库为空，开始播种示例配置项...` → `播种完成：6 个配置项` |
| 工作目录 | `backend/`（数据源 URL 为相对路径 `./data/quickstart_db`，H2 文件落于 `backend/data/`） |
| 基线状态 | `backend/data/` **原本不存在**（无任何 `*.mv.db`），即干净空库，**无需备份**；`backend/target/` 亦不存在 |
| AI Key | 仅以环境变量注入供启动前置（Spring AI `OpenAiApi` 要求非空 key）。本核验项**未发起任何 AI 请求**，证据文档中记作 `***` |
| 端口前置检查 | `netstat -ano \| grep 18293` 启动前为空闲；未占用 18080/18290/18291/18292/18294 |

**实验流程**：

1. `POST /api/tasks` 创建任务 `{"type":"EXPORT","name":"V8-幂等核验-20261006"}` → taskId = **1**。
2. `POST /api/tasks/1/jobs/export`，body 为 **6 个配置项**（COUNTRY / PROJECT_TYPE / REGION_GROUP / TAX_RATE / PROJECT_INFO / UNIT_CONVERSION，`conditions` 均为空），单进程 300ms/项的演示节流使作业 RUNNING 窗口约 2 秒，便于重复提交命中 RUNNING。
3. **首次提交后立即**并发发出 **5 个完全相同**的重复 POST（脚本内 `Promise.all`，实测发出时刻分别为 t=116/117/117/117/118 ms）。
4. 每 250ms 轮询 `GET /api/jobs/1`，记录状态轨迹直至终态。
5. **附加对照 A**：作业 SUCCESS 之后再提交同 kind，观察是否允许新建。
6. **附加对照 B**：改用 **kind = IMPORT** 在同一任务上并发 4 次提交（验证 kind 隔离）；再提交一次 **kind = CHECK**（验证不同 kind 不被拦截）。
7. 关闭后端 → 用 H2 客户端只读打开库文件，直查 `JOB_RUN` 表全量记录。

---

## 3. 实际观察（请求/响应原文全文见附件 A、B、C）

### 3.1 创建任务

请求 `POST /api/tasks`：

```json
{"type":"EXPORT","name":"V8-幂等核验-20261006"}
```

响应（HTTP 200）：

```json
{"id":1,"taskNo":"EXP-20261006-0001","type":"EXPORT","name":"V8-幂等核验-20261006","status":"DRAFT","currentStep":1,"stepData":{},"progress":0,"message":null,"createdAt":"2026-10-06T18:59:55.5528674","updatedAt":"2026-10-06T18:59:55.5528674"}
```

### 3.2 导出作业请求体（6 个配置项，首次与全部重复提交逐字节相同）

```json
{"items":[
 {"defCode":"COUNTRY","conditions":[]},
 {"defCode":"PROJECT_TYPE","conditions":[]},
 {"defCode":"REGION_GROUP","conditions":[]},
 {"defCode":"TAX_RATE","conditions":[]},
 {"defCode":"PROJECT_INFO","conditions":[]},
 {"defCode":"UNIT_CONVERSION","conditions":[]}
]}
```

### 3.3 首次提交 + 5 次并发重复提交（RUNNING 期间）

`POST /api/tasks/1/jobs/export`，脚本内相对时刻 t0 = 脚本启动：

| 请求 | 发出时刻 | 收到时刻 | HTTP | 响应 `id` | 响应 `status` | 结论 |
|---|---|---|---|---|---|---|
| **首次提交** | 0 ms | 116 ms | 200 | **1** | `PENDING` | 新建作业 1 |
| 重复提交 #1 | 116 ms | 196 ms | 200 | **1** | `RUNNING` | 返回既有作业，未新建 |
| 重复提交 #2 | 117 ms | 185 ms | 200 | **1** | `RUNNING` | 返回既有作业，未新建 |
| 重复提交 #3 | 117 ms | 173 ms | 200 | **1** | `RUNNING` | 返回既有作业，未新建 |
| 重复提交 #4 | 117 ms | 147 ms | 200 | **1** | `RUNNING` | 返回既有作业，未新建 |
| 重复提交 #5 | 118 ms | 158 ms | 200 | **1** | `RUNNING` | 返回既有作业，未新建 |

首次提交响应原文（HTTP 200，节选至 items 首项）：

```json
{"id":1,"taskId":1,"kind":"EXPORT","status":"PENDING","total":6,"processed":0,"currentItem":null,"result":null,"error":null,"createdAt":"2026-10-06T19:00:00.5157363","startedAt":null,"finishedAt":null,"items":[{"defCode":"COUNTRY","seq":1,"status":"PENDING","totalRows":null,"okRows":null,"errorRows":null,"message":null,"detail":null}, ...共 6 项... ]}
```

重复提交 #1 响应原文（HTTP 200）——**`id` 与首次相同（1）**，且 `createdAt` 与首次提交完全相同（`2026-10-06T19:00:00.515736`，同一行记录），返回时 `status` 已变为 `RUNNING`：

```json
{"id":1,"taskId":1,"kind":"EXPORT","status":"RUNNING","total":6,"processed":1,"currentItem":"COUNTRY","result":null,"error":null,"createdAt":"2026-10-06T19:00:00.515736","startedAt":"2026-10-06T19:00:00.54174","finishedAt":null,"items":[{"defCode":"COUNTRY","seq":1,"status":"SUCCESS","totalRows":8,"okRows":8,"errorRows":0,"message":null,"detail":null},{"defCode":"PROJECT_TYPE","seq":2,"status":"PENDING", ...}, ...]}
```

重复提交 #5 响应原文（HTTP 200）：

```json
{"id":1,"taskId":1,"kind":"EXPORT","status":"RUNNING","total":6,"processed":0,"currentItem":"COUNTRY","...","createdAt":"2026-10-06T19:00:00.515736","startedAt":"2026-10-06T19:00:00.54174","finishedAt":null,"items":[...]}
```

> 5 次重复提交的响应全部为 **HTTP 200 + `id = 1`**，无任何一次返回新 id，也无任何 4xx/5xx。作业 1 在整个重复提交窗口内始终处于 `PENDING`/`RUNNING`（未越过终态）。

**作业 1 状态轮询轨迹**（`GET /api/jobs/1`，250ms 间隔）：

```
t=208ms  RUNNING  processed=1/6  currentItem=COUNTRY
t=472ms  RUNNING  processed=1/6  currentItem=COUNTRY
t=746ms  RUNNING  processed=2/6  currentItem=PROJECT_TYPE
t=1017ms RUNNING  processed=3/6  currentItem=UNIT_CONVERSION
t=1292ms RUNNING  processed=4/6  currentItem=REGION_GROUP
t=1552ms RUNNING  processed=4/6  currentItem=TAX_RATE
t=1813ms RUNNING  processed=5/6  currentItem=TAX_RATE
t=2081ms RUNNING  processed=6/6  currentItem=PROJECT_INFO
t=2346ms SUCCESS  processed=6/6  currentItem=null
```

**作业 1 终态**（`GET /api/jobs/1`）：`status=SUCCESS`、`processed=6`、`total=6`、`createdAt=2026-10-06T19:00:00.515736`、`startedAt=2026-10-06T19:00:00.54174`、`finishedAt=2026-10-06T19:00:02.631883`；6 个 item 全部 `SUCCESS`（COUNTRY 8 行 / PROJECT_TYPE 8 行 / UNIT_CONVERSION 10 行 / REGION_GROUP 8 行 / TAX_RATE 12 行 / PROJECT_INFO 12 行）。

`GET /api/jobs/1/export-results` 佐证作业确实真实执行完毕：

```json
[{"def":"COUNTRY","file":"COUNTRY.xlsx","rows":8},{"def":"PROJECT_TYPE","file":"PROJECT_TYPE.xlsx","rows":8},{"def":"UNIT_CONVERSION","file":"UNIT_CONVERSION.xlsx","rows":10},{"def":"REGION_GROUP","file":"REGION_GROUP.xlsx","rows":8},{"def":"TAX_RATE","file":"TAX_RATE.xlsx","rows":12},{"def":"PROJECT_INFO","file":"PROJECT_INFO.xlsx","rows":12}]
```

### 3.4 数据库直查：重复提交未产生第二条作业记录（C3 核实）

后端停止后，以只读方式打开 H2 文件库查询（完整输出见附件 C）：

```bash
java -cp "<USER_HOME>/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar" org.h2.tools.Shell \
  -url "jdbc:h2:file:<REPO_ROOT>/ai-example-kimi-k3/backend/data/quickstart_db;MODE=MySQL;ACCESS_MODE_DATA=r" \
  -user sa -password "" \
  -sql "SELECT ID, TASK_ID, KIND, STATUS, TOTAL, PROCESSED, CREATED_AT FROM JOB_RUN ORDER BY ID"
```

输出原文：

```
ID | TASK_ID | KIND   | STATUS  | TOTAL | PROCESSED | CREATED_AT
1  | 1       | EXPORT | SUCCESS | 6     | 6         | 2026-10-06 19:00:00.515736
2  | 1       | EXPORT | SUCCESS | 6     | 6         | 2026-10-06 19:00:02.807911
3  | 1       | IMPORT | SUCCESS | 1     | 1         | 2026-10-06 19:00:14.459458
4  | 1       | CHECK  | SUCCESS | 1     | 1         | 2026-10-06 19:00:14.494458
(4 rows, 12 ms)
```

**全库 `JOB_RUN` 只有 4 行。** 本次实验共发出 **12 次作业创建请求**（EXPORT 7 次 = 首次 + 5 重复 + 完成后再提交；IMPORT 4 次 = 首次 + 3 重复；CHECK 1 次），而落库记录为 4 行 —— 差额 8 行正是被幂等拦截的重复提交。其中 **EXPORT 只有 ID=1 与 ID=2 两行**：ID=1 是首次（5 次并发重复全部命中它），ID=2 是作业完成后重新提交产生的新作业（见 §3.5）。**C3 成立。**

### 3.5 附加对照 A：作业完成后再提交同 kind

作业 1 于 t≈2346ms 变为 `SUCCESS`；t=2360ms 再次 `POST /api/tasks/1/jobs/export`（body 与之前逐字节相同）：

```json
{"id":2,"taskId":1,"kind":"EXPORT","status":"PENDING","total":6,"processed":0,"currentItem":null,"result":null,"error":null,"createdAt":"2026-10-06T19:00:02.8079107","startedAt":null,"finishedAt":null,"items":[...]}
```

**HTTP 200，返回新作业 id = 2（与作业 1 不同）。** 这与代码注释「同任务同 kind 有 **PENDING/RUNNING** 作业时直接返回该作业」完全一致：终态 `SUCCESS` 不在 `IN (PENDING, RUNNING)` 过滤条件内，**允许新建**。作业 2 同样跑到 `SUCCESS`（轮询轨迹：PENDING → RUNNING(1..6) → SUCCESS，t≈2399–4536ms）。

### 3.6 附加对照 B：kind 隔离与跨 kind 不干扰

在同一任务 1 上改用 `kind = IMPORT`（body 为 1 项 UNIT_CONVERSION 数据），**首次 + 3 次并发重复**：

| 请求 | HTTP | 响应 `id` | 响应 `kind` | 响应 `status` |
|---|---|---|---|---|
| IMPORT 首次 | 200 | **3** | `IMPORT` | `PENDING` |
| IMPORT 重复 #1 | 200 | **3** | `IMPORT` | `RUNNING` |
| IMPORT 重复 #2 | 200 | **3** | `IMPORT` | `RUNNING` |
| IMPORT 重复 #3 | 200 | **3** | `IMPORT` | `RUNNING` |

**IMPORT 同样幂等（全部返回 id=3）。**

随后提交 `kind = CHECK`（同一任务、请求结构相同）：**HTTP 200，返回新作业 id = 4、kind = CHECK** —— 说明幂等判定**按 kind 隔离**，不会错误复用其它 kind 的在途作业。两个作业终态均为 `SUCCESS`（IMPORT 1/1；CHECK 1/1）。**C4 成立。**

---

## 4. 逐项对照表

| 断言 | 分支自述 | 本次实测 | 判定 |
|---|---|---|---|
| **C1** 同任务同 kind 且已有 PENDING/RUNNING 作业时，重复提交**不新建**、直接返回已有作业 | 是 | 5 次并发重复提交（EXPORT）与 3 次（IMPORT）全部返回既有作业，均未新建 | ✅ 符合 |
| **C2** 重复提交返回的作业 `id` 与首次**相同** | 是 | EXPORT：首次 id=1，5 次重复全为 **id=1**；IMPORT：首次 id=3，3 次重复全为 **id=3**；`createdAt` 亦与首次完全一致 | ✅ 符合 |
| **C3** 重复提交后**不产生第二条作业记录** | 是 | 12 次创建请求 → `JOB_RUN` 仅 4 行；EXPORT 仅 ID=1、2 两行（ID=2 为完成后另行新建） | ✅ 符合 |
| **C4** 幂等判定按 **kind 隔离** | 是（由 `findFirstByTaskIdAndKind` 语义隐含） | 同一任务上 EXPORT 在途不阻塞 IMPORT（得 id=3）、IMPORT 在途不阻塞 CHECK（得 id=4） | ✅ 符合 |
| **C5** 作业完成后再提交同 kind 的行为符合代码设计 | 设计为「终态不在过滤条件内 → 允许新建」 | 作业 1 SUCCESS 后提交 → 新建作业 2（id 不同、PENDING 起跑并最终 SUCCESS），与注释一致 | ✅ 符合 |
| 附 | 作业在 RUNNING 中确实在推进（非卡死） | 轮询见 processed 1→6 递增、currentItem 依次推移、终态 SUCCESS + 6 项明细 + export-results 全量行数 | ✅ 符合 |

---

## 5. 结论

### 结论标签：**【实测-符合】**

**具体表述**：

1. **幂等行为实测成立。** 在作业 RUNNING 期间并发重复提交同任务同 kind 作业（EXPORT 5 次、IMPORT 3 次），**每一次都返回 HTTP 200 且 `id` 与首次提交完全相同**（EXPORT 全为 1、IMPORT 全为 3），且响应的 `createdAt`/`startedAt` 与首次记录逐字段一致，证明返回的是**同一个数据库行**而非新建的等价作业。
2. **未产生重复记录，实测佐证到位。** 12 次作业创建请求最终在 H2 `JOB_RUN` 表中只留下 4 行；EXPORT 只有 ID=1（首次，吸收全部 5 次重复）与 ID=2（作业完成后重新提交产生的合法新作业）。
3. **幂等按 kind 隔离，设计边界行为与代码注释一致。** 同任务下 EXPORT 的在途作业不会拦截 IMPORT（得 id=3），IMPORT 在途也不影响 CHECK（得 id=4）；**唯一例外**是：作业进入终态（SUCCESS/FAILED/CANCELLED）后再提交同 kind，**会新建作业**（ID=2）——这与源码注释「有 PENDING/RUNNING 作业时返回该作业」严格一致，属于设计预期，不是缺陷。
4. **作业本身真实执行完毕**：轮询可见 `processed` 1→6 递增、`currentItem` 依次推移、终态 `SUCCESS`，`export-results` 返回 6 个配置项的完整行数（8/8/10/8/12/12 行），说明返回的"现有作业"确实在被执行，而非空转返回。
5. **一处需登记的观察（非缺陷）**：幂等的**唯一防线是 `findFirstByTaskIdAndKindAndStatusIn`（数据库查询）+ 方法级 `synchronized`**。在"单实例 + 单 JVM"下足以成立；若未来多实例部署或去掉 `synchronized`，两次并发请求理论上可能同时查不到在途作业而各建一条。这属于合流后可留意的扩展性边界，**不影响本次实测结论**。
6. **一处工具性说明**：分支**未提供"按任务列出全部作业"的 HTTP 接口**，故 §3.4 的"未新建第二条记录"只能用 H2 文件库直查佐证（后端已停止后**只读**打开，未写入、未修改任何数据）。

---

## 6. 附件清单（同目录）

| 文件 | 内容 |
|---|---|
| `V8-附件-A-幂等请求响应原文.json` | 创建任务请求/响应、导出作业请求体、§3.3 首次与 5 次并发重复提交的完整请求/响应原文、状态轮询轨迹、完成后再次提交的原文 |
| `V8-附件-B-不同kind对照.json` | §3.6 IMPORT 首次+3 次并发重复、CHECK 提交、两者终态的完整请求/响应原文 |
| `V8-附件-C-H2作业表查询输出.txt` | §3.4 H2 直查命令与输出原文（`JOB_RUN` 全量 4 行） |

> 说明：真实 API Key 仅存在于启动后端的进程环境变量中，未发起任何 AI 请求，文档与附件中一律记作 `***`。

---

## 7. 现场清理与纪律声明

- **端口释放**：后端进程（Windows PID **19628**）已用 `taskkill //PID 19628 //F` 终止。
  `netstat -ano | grep 18293` 复查结果：**无任何 `LISTENING` 条目**，仅余若干本机 curl/node 客户端连接的 `TIME_WAIT`（`[::1]:11145→[::1]:18293` 等，随 TCP 超时自行清除）——**端口 18293 已释放**。
- **数据库文件**：`backend/data/quickstart_db.mv.db`（180,224 字节）按任务要求**保留**，即本次实验结束时的状态；实验前该目录不存在，故无备份。已用只读方式（`ACCESS_MODE_DATA=r`）查询，未写入。
- **纪律**：全程**未修改**被核验分支的任何源码/配置/文档，**未执行任何 git 写操作**（`git status --short` 在结束后为空）。未修改任何配置来"让核验通过"。
- **被核验分支遗留物**：`ai-example-kimi-k3/backend/target/`（构建产物，含 `quickstart-backend-1.0.0.jar`）与 `backend/data/`（运行期 H2 库）。二者均被该分支 `.gitignore` 覆盖（`.gitignore` 含 `target/`、`data/`）。
- **未执行项**：无。核心幂等实验 + 两项附加对照全部完成，无失败重试，无环境受限项。
