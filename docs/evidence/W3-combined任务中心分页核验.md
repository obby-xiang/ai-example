# W3 核验：combined 分支「任务中心——统一任务列表与服务端分页」

- 被核验分支：`ai-example-claude-opus-5.5+deepseek-v4-pro`（工作副本 `<REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro`，HEAD = `a98d0b2`，`git status --porcelain` 空 = 未改动源码/配置）
- 核验日期：2026-10-07
- 结论：**【实测-符合】**（任务列表的分页 / 类型过滤 / 状态过滤 / 关键词 / 排序全部按源码语义工作，跨页不重不漏；同时实测出 5 条边界，见 §5）
- 原始记录附件（同目录）：
  - `W3-附件-分页与过滤-请求响应原文.json`（造 26 个任务 + 全部分页/过滤/边界请求的原始响应）
  - `W3-附件-边界与排序与进度刷新-请求响应原文.json`（标题落库校验、排序稳定性、过滤后分页、运行中进度观测）
  - `W3-附件-基线任务列表.json`（动手前的任务列表基线）

---

## 1. 核验目标

| # | 待验项 | 出处 |
|---|--------|------|
| S1 | ADR-8 逐机制证据标注：「**任务中心与服务端分页（无核验）**」 | `<REPO_ROOT>/docs/adr/DECISION-CARDS.md:94-96` |
| S2 | 分支自述：「历史任务列表：类型/状态/关键词筛选 + 分页，每条附带配置项数、文件数与最新作业进度」 | `backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:34-36` |

核验问题：任务列表 API 的路径/分页参数/过滤参数/排序各是什么？分页边界（第 0/1/末页、越界页）行为是否正确？跨页是否不重不漏？类型/状态过滤是否真的生效？任务详情里的进度字段在作业运行中如何刷新？

---

## 2. 源码机制摘要（未做任何修改）

**列表端点** — `task/controller/TaskController.java:37-48`
```
GET /api/tasks?type=&status=&keyword=&page=0&size=10
  type    : 可选，Task.TaskType 枚举名（大小写不敏感，:44 做了 toUpperCase）
  status  : 可选，Task.TaskStatus 枚举名（:45 同样 toUpperCase）
  keyword : 可选，匹配 标题 LIKE %kw% 或 id 转字符串 LIKE %kw%（见下）
  page    : 默认 0；size : 默认 10
  返回    : ApiResponse<Page<TaskSummary>>
```

**查询与排序** — `task/repo/TaskRepository.java:19-27`
```sql
SELECT t FROM Task t
WHERE (:type IS NULL OR t.type = :type)
  AND (:status IS NULL OR t.status = :status)
  AND (:kw IS NULL OR :kw = '' OR t.title LIKE CONCAT('%',:kw,'%')
       OR CAST(t.id AS string) LIKE CONCAT('%',:kw,'%'))
ORDER BY t.createdAt DESC
```
- **排序键只有 `createdAt DESC`，没有第二排序键（无 id 兜底）**；`:kw=''` 视为不过滤；`LIKE` 模式未转义（`%`/`_` 直接当通配符）。

**列表每条的内容** — `task/controller/TaskSummary.java:9` = `record TaskSummary(Task task, int itemCount, int fileCount, Job latestJob)`；由 `TaskController.java:61-68 toSummary` 逐条拼装：`latestJob = jobs.isEmpty()? null : jobs.get(0)`（`JobRepository.findByTaskIdOrderByCreatedAtDesc` 的**第一条**），fileCount 来自 `taskFileRepository.findByTaskId`——**每行 2 次额外查询（N+1）**，单页 10 条 = 列表查询 + 21 次查询。

**其它任务中心端点**
- 详情概览：`TaskController.java:53-59` `GET /api/tasks/{id}/overview` → `{task, jobs[], files[]}`（作业按 createdAt DESC）
- 任务本体：`:78-81` `GET /api/tasks/{id}`（**只返回 Task，不含作业/进度**）
- 任务下作业：`job/controller/JobController.java:33-36` `GET /api/tasks/{taskId}/jobs`
- 作业详情：`JobController.java:38-41` `GET /api/jobs/{jobId}`（含 `items[]` 每项 `status/processed/total`）
- 分页参数由 Spring Data `PageRequest.of(page,size)` 构造（`TaskController.java:46`），越界参数由框架抛 `IllegalArgumentException`，经 `common/GlobalExceptionHandler.java` 转为 400。

**任务状态枚举与写路径** — `task/entity/Task.java:57`：`ACTIVE / COMPLETED / CANCELLED / FAILED`，默认 `ACTIVE`（:32）。全仓 grep `TaskStatus.` 的写入点只有：`TaskService.java:153 complete()`→COMPLETED、`TaskService.java:163-168 updateStatus()`（调用方只有 `ExportJobRunner.java:150`、`PublishJobRunner.java:198`，都传 COMPLETED）→ **`CANCELLED` / `FAILED` 这两个状态在代码中从无赋値路径**（B4）。

**前端消费** — `frontend/src/views/TaskListView.vue:151-155`：列表挂载后 `setInterval(reload, 5000)` 静默刷新（注释即"展示作业实时进度"）；行内进度条用 `latestJob.progress / latestJob.total`（`TaskListView.vue:72-74`）。作业级轮询见 `export/StepExport.vue:151-160`（1.5s）。

---

## 3. 实验过程

### 3.0 环境

```
cd <REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro/backend
"<MAVEN_HOME>/bin/mvn.cmd" -s maven-settings.xml -DskipTests package
export DEEPSEEK_API_KEY=dummy-for-verification
java -jar target/config-mgr.jar --server.port=18295
```
- 基线（`W3-附件-基线任务列表.json`）：`GET /api/tasks?page=0&size=100` → `totalElements=16`（既有 10 条 + 本次 W1/W2 产生的 6 条）。
- 造数据：`POST /api/tasks` 连发 26 次（`W3-分页核验-EXPORT-01/03/…` 与 `W3-分页核验-IMPORT-02/04/…`，即 13 个 EXPORT + 13 个 IMPORT，id 39~64），**不启动作业**（符合"不必执行完"）→ 全量 `totalElements=42`（≥25 要求达成）。
- 全部请求走本机 `http://localhost:18295`（仅回环，未使用被禁端口）。

### 3.1 分页（size=10，逐页原文）

| page | number | size | numberOfElements | totalElements | totalPages | first | last | 页内 id |
|---|---|---|---|---|---|---|---|---|
| 0 | 0 | 10 | 10 | 42 | 5 | true | false | 64,63,62,61,60,59,58,57,56,55 |
| 1 | 1 | 10 | 10 | 42 | 5 | false | false | 54,53,52,51,50,49,48,47,46,45 |
| 2 | 2 | 10 | 10 | 42 | 5 | false | false | 44,43,42,41,40,39,38,37,36,35 |
| 3 | 3 | 10 | 10 | 42 | 5 | false | false | 34,33,10,9,8,7,6,5,4,3 |
| 4 | 4 | 10 | **2** | 42 | 5 | false | **true** | 2,1 |

**跨页不重不漏**（与 `size=300` 单页全量对比）：
```
分页合并 len = 42 | 去重后 = 42 | 重复 id = [] | 缺失 = []
全量顺序前 12: [64,63,62,61,60,59,58,57,56,55,54,53]
分页顺序前 12: [64,63,62,61,60,59,58,57,56,55,54,53]
顺序一致 = True
```

### 3.2 页面边界

```
page=3&size=10  -> 200 number=3 numberOfElements=10 totalElements=42 totalPages=5 last=false 首条=[34,33,10]
page=4&size=10  -> 200 number=4 numberOfElements=2  totalElements=42 totalPages=5 last=true  首条=[2,1]
page=10&size=10 -> 200 number=10 numberOfElements=0 totalElements=42 totalPages=5 首条=[]      ← 越界页不报错，返回空页
page=0&size=1   -> 200 numberOfElements=1 totalElements=42 totalPages=42 首条=[64]
page=0&size=1000-> 200 numberOfElements=42 totalElements=42 totalPages=1 首条=[64,63,62]
page=-1&size=10 -> 400 {"success":false,"message":"Page index must not be less than zero"}
page=0&size=0   -> 400 {"success":false,"message":"Page size must not be less than one"}
page=0&size=-5  -> 400 {"success":false,"message":"Page size must not be less than one"}
```

### 3.3 类型过滤 `type`

```
type=EXPORT -> 200 totalElements=13  页内类型集合=['EXPORT']
type=IMPORT -> 200 totalElements=29  页内类型集合=['IMPORT']
type=export -> 200 totalElements=13  （小写可用，:44 toUpperCase）
type=''     -> 200 totalElements=42  （空串等同不过滤）
type=FOO    -> 400 {"success":false,"message":"No enum constant com.example.configmgr.task.entity.Task.TaskType.FOO"}
```
（13+29=42，与全量一致。）

### 3.4 状态过滤 `status`

```
status=ACTIVE    -> 200 totalElements=36  状态集合=['ACTIVE']
status=COMPLETED -> 200 totalElements=6   状态集合=['COMPLETED']
status=CANCELLED -> 200 totalElements=0
status=FAILED    -> 200 totalElements=0
status=active    -> 200 totalElements=36   （小写可用）
status=ZZZ       -> 400 {"success":false,"message":"No enum constant com.example.configmgr.task.entity.Task.TaskStatus.ZZZ"}
```

### 3.5 关键词 `keyword`

```
keyword='W3-分页核验-EXPORT-01' -> 200 totalElements=1   titles=['W3-分页核验-EXPORT-01']   （中文精确匹配，UTF-8 百分号编码可用）
keyword='W3-分页核验-IMPORT-02' -> 200 totalElements=1
keyword='分页核验'              -> 200 totalElements=26   （子串匹配）
keyword='38'                    -> 200 totalElements=1   ids=[38]      （id 转字符串 LIKE，命中任务 38）
keyword=''                      -> 200 totalElements=42
keyword='%'                     -> 200 totalElements=42   （LIKE 通配未转义，'%' 命中全部，见 §5-B3）
```
注：任务标题落库为正确 UTF-8（`GET /api/tasks/39` 返回 `W3-分页核验-EXPORT-01`），故中文检索有效——本报告的终端回显乱码是 Git Bash 控制台代码页问题，不是数据问题。

### 3.6 组合过滤与排序

```
GET /api/tasks?page=0&size=10&type=EXPORT&status=ACTIVE -> 200 totalElements=13
   页内类型集合=['EXPORT'] 状态集合=['ACTIVE']        （两条件 AND，符合查询语句）
同参数连续 5 次（page=0 与 page=1）结果签名完全一致 = True
   5 次 page=0 均为 [64,63,62,61,60,59,58,57,56,55]
全量 42 条 createdAt：总数 42 / 去重后 42 / 并列值 = []  → 本次数据无并列，未观察到排序抖动
```

### 3.7 过滤下的分页（不重不漏，`type=IMPORT&size=7`）

```
page=0: n=7 totalElements=29 last=false ids=[64,62,60,58,56,54,52]
page=1: n=7 totalElements=29 last=false ids=[50,48,46,44,42,40,38]
page=2: n=7 totalElements=29 last=false ids=[37,36,35,34,33,10,9]
page=3: n=7 totalElements=29 last=false ids=[8,7,6,5,4,3,2]
page=4: n=1 totalElements=29 last=true  ids=[1]
合并去重 = 29 = 全量 IMPORT 数；缺失 = []；重复 = []
```

### 3.8 任务列表响应耗时（实测）

```
size=10 各页: 0.008 ~ 0.022 s      size=100（42 条）: 0.049 s
全量 size=300: 0.021 s             关键词查询: 0.004 ~ 0.022 s
```
单机空载下未暴露 N+1 瓶颈（见 §5-B5 的量化口径）。

### 3.9 任务详情里的进度字段在作业运行中的刷新方式

在作业运行期间，同时观测三个"快照面"（任务 65 / 作业 39，IMPORT 3000 行，约 11.4 s）：

```
t+1.5s  列表.latestJob=(39,'PENDING',0) | GET /api/jobs/39 status=PENDING progress=0 | overview.jobs[0]=[(39,'PENDING',0)]
t+3.0s  同上（PENDING / 0）
t+4.5s  同上
t+6.0s  同上
t+7.5s  同上
t+9.0s  同上
t+10.5s 同上                                  ← 观测窗口内进度字段始终 0
作业结束后：列表.latestJob=(39,'COMPLETED',1)；overview/GET /api/jobs → status=COMPLETED progress=1
            items=[('CURRENCY','COMPLETED',3000,3000)] finishedAt=2026-10-07T01:13:30.471912
```
**结论**：任务中心侧（列表 `latestJob`、`overview.jobs[]`、`GET /api/tasks/{id}`）在作业运行中**只能看到 `PENDING` + `progress=0`**，进度字段的"刷新"实际只经历"未开始 → 已结束"两个取值；`GET /api/tasks/{id}` 本身不含作业字段。实时进度的唯一来源是 SSE 的 `JOB_PROGRESS`（见 W2 §3.1）。根因同 W1-B1：runner 的 `run()` 为单事务（`ImportJobRunner.java:55`），未提交的 `RUNNING`/进度对其他连接不可见。前端 5s 列表刷新（`TaskListView.vue:151-155`）与 1.5s 作业轮询（`StepExport.vue:151-160`）都受此限制。

---

## 4. 结论

**【实测-符合】。**

1. 端点与参数与源码一致：`GET /api/tasks?type&status&keyword&page&size`（默认 `page=0,size=10`），返回 `Page<TaskSummary>`，每条含 `task + itemCount + fileCount + latestJob`。
2. 分页服务端生效且边界正确：`size=10` 时 `totalElements=42/totalPages=5`，前 4 页满 10 条、末页 2 条且 `last=true`；越界页 `page=10` 返回空页（200）而非报错；`page=-1`、`size=0`、`size<0` 返回 400 且报文明确。
3. 跨页不重不漏：5 页合并 = 42 = 全量，重复/缺失均为空，顺序与单页全量完全一致；过滤条件下（`type=IMPORT&size=7`）同样 29/29 无重无漏。
4. 过滤与排序按源码语义工作：`type`（13/29，大小写不敏感，非法值 400）、`status`（ACTIVE 36/COMPLETED 6，非法值 400）、`keyword`（标题或 id 的 `LIKE %kw%`，中文可检索）、组合过滤是 AND；排序 `createdAt DESC`，同参数 5 次结果完全一致。
5. 任务详情进度字段：只有 `latestJob`（列表）与 `overview.jobs[]` 暴露进度，运行中固定 `PENDING/0`，终态才跳到 `COMPLETED/1` + 条目级 `processed/total`（§3.9）。

---

## 5. 边界说明（结论不变，但合流须知）

| # | 观察（实测） | 证据 | 影响 |
|---|-------------|------|------|
| B1 | 排序只有 `createdAt DESC`，**无第二排序键**；本次 42 条 createdAt 恰好全不重复（并列值=[]），5 次重复请求顺序一致，所以未观察到抖动；但一旦出现同微秒创建（批量脚本、并发创建）理论上跨页可能重复/漏行 | `TaskRepository.java:19-27`；§3.6 | 合流建议补 `, t.id DESC` 兜底（观察+建议，未改代码） |
| B2 | **每行 2 次附加查询（N+1）**：`toSummary` 对每条任务各查一次最新作业与文件（`TaskController.java:61-68`）；单页 10 条 = 1 + 21 次查询。单机空载实测 8~22 ms/页，未暴露问题 | §3.8 | 数据量/并发上升后是首要优化点；合流若要保留 combined 语义，建议改成批量查询 |
| B3 | `keyword` 直接拼进 `LIKE '%'||kw||'%'`，**未转义**：`keyword=%` 命中全部 42 条 | §3.5 | 非 SQL 注入（参数绑定），但通配符语义暴露给用户；属行为观察 |
| B4 | `TaskStatus` 枚举含 `CANCELLED/FAILED`，但**全仓无任何写入路径**（只有 COMPLETED）：`status=CANCELLED/FAILED` 过滤恒为 0 条。相应地 W1 实测"取消作业不联动任务状态"，任务永远停在 ACTIVE | `TaskService.java:153/163-168` + 调用点 grep；W1 §5-B5 | 前端若提供"CANCELLED/FAILED"筛选会永远空；合流需明确任务状态机是否补齐 |
| B5 | 越界页返回 200 空页（`number=10 > totalPages=5`），调用方不能靠 HTTP 码判断越界；`size` 无上限（`size=1000` 直接返回全量 42 条） | §3.2 | 合流若要防大页拉取需另加约束 |
| B6 | 运行中看不到进度（§3.9），任务中心的"实时进度"承诺与单事务作业模型不匹配 | §3.9 + W1-B1 | 这是 combined 作业模型的固有边界，合流沿用则需接受"列表只有起止两态" |

---

## 6. 未覆盖/未做

- 未测 `itemCount`/`fileCount` 的数值正确性（只记录字段存在；本次造的任务无 items/files，绝大多数为 0，无法构成对比）。
- 未测分页在大数据量（数千任务）下的稳定性与深分页性能（`Pageable` 无 `SORT` 参数暴露，前端不能自定义排序）。
- 未测前端页面实际渲染（只读源码确认调用点，未启前端）。
- 全程只读源码 + 调 REST；未修改任何分支文件、未做 git 写操作（`git status --porcelain` 为空）。本次新增的 27 个测试任务（id 39~65）留在该分支 H2 开发库中（已按纪律先备份库文件）。
