# V5 核验：combined 分支「发布冲突检测 / OPTIMISTIC_FORCE_INCREMENT 乐观锁」

- 被核验分支：`ai-example-claude-opus-5.5+deepseek-v4-pro`（工作副本 `<REPO_ROOT>/ai-example-claude-opus-5.5+deepseek-v4-pro`，`git branch --show-current` = `claude-opus-5.5+deepseek-v4-pro`，HEAD = `a98d0b2`）
- 核验日期：2026-10-06
- 结论：**【实测-符合】**（附带 2 条形态性修正，见第 5 节）
- 原始记录附件（同目录）：
  - `V5-附件-requests-responses.log`（本次全部 REST 请求/响应逐字记录）
  - `V5-附件-backend-console.log`（本次后端完整控制台日志，180 行）

---

## 1. 核验目标（分支自述原文与出处）

| # | 自述原文 | 出处（该分支内文件:行） |
|---|---------|----------------------|
| S1 | 「### 3.4 独立用例：发布冲突检测（双任务并发）」→「任务 A、B 同时导入同一行 TO3 \| 快照版本均为 1」「B 先发布 \| TO3 版本 1→2（OPTIMISTIC_FORCE_INCREMENT 强制递增）」「A 后发布 \| ✅ FAILED + 问题"发布冲突：行 TO3 在导入后被其他操作修改（快照版本 1，当前版本 2），请重新导入后再发布"」 | `docs/verification-results.md:152-158` |
| S2 | 「并发冲突检测失效 \| Hibernate 脏检查跳过无变化 UPDATE，版本不递增 \| OPTIMISTIC_FORCE_INCREMENT 强制递增」 | `docs/verification-results.md:188`（缺陷表 #9） |
| S3 | 「并发控制 \| 乐观锁（@Version）+ 业务守卫 \| 轻量；H2 环境足够」 | `docs/technical-design.md:395` |
| S4 | 「B-12 \| 导入后行被他人修改再发布 \| 发布失败并报冲突（快照版本 vs 当前版本），需重新导入」 | `docs/test-cases.md:139` |
| S5 | 「N-IM-03 \| 冲突检测-版本变更 \| 发布 FAILED，问题列表含冲突说明」「N-IM-04 \| 冲突检测-新行被抢先创建 \| 同上（快照为 null 但行已存在）」 | `docs/test-cases.md:175-176` |

对应的实现（核验前先读源码定位，未做任何修改）：

- `backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:113-146`：逐暂存行先 `hasConflict(sr, existing)` 判定，命中则写 `ValidationIssue` 并把暂存行置 `FAILED`、`totalErrors++`；未命中才 upsert，且对已存在行做 `entityManager.lock(liveRow, LockModeType.OPTIMISTIC_FORCE_INCREMENT)`。
- `PublishJobRunner.java:220-225` `hasConflict()`：暂存行有快照版本 → 当前行不存在或版本不等即冲突；暂存行无快照版本（新增行）→ 当前行已存在即冲突。
- `backend/src/main/java/com/example/configmgr/data/entity/ConfigDataRow.java:34-35`：`@Version private Long version`。
- 快照来源：`ImportJobRunner.java:120-124` 导入时把当前已发布行 `version` 写入 `config_staging_rows.base_version`（迁移脚本 `V2__staging_base_version.sql`）。

---

## 2. 执行环境与命令

| 项 | 值 |
|---|---|
| JDK | `java 21.0.12`（PATH 中） |
| Maven | `"<MAVEN_HOME>/bin/mvn.cmd"` |
| 构建 | `cd backend && mvn -B -DskipTests package` → **BUILD SUCCESS**，8.9s，产物 `backend/target/config-mgr.jar` |
| 启动 | `cd backend && export DEEPSEEK_API_KEY=dummy-for-verification && java -jar target/config-mgr.jar --server.port=18292`（该分支默认端口是 8081，用启动参数覆盖为本任务分配的 18292；未改任何配置文件） |
| 启动前端口检查 | `netstat -ano \| grep 18292` → 空闲 |
| 数据库 | H2 文件库 `./data/config_mgr_db`；启动前**已备份并删除**：`cp -r data <WORKSPACE>/v5v6/db-backup/data-before-fresh-start-185336 && rm -rf data`（原库只有一个空 schema，无业务数据） |
| 启动结果 | `Tomcat started on port 18292` + `Started ConfigMgrApplication in 7.121 seconds`，PID 11484；`curl /api/definitions` → 200 |
| 客户端 | Git Bash + `curl`，所有请求/响应逐字保存于 `<WORKSPACE>/v5v6/raw/` |

**关于 `DEEPSEEK_API_KEY`（实测环境事实，非本核验项结论）**：该分支在 key 为空时**根本起不来**，启动直接报
`OpenAI API key must be set. Use the connection property: spring.ai.openai.api-key or spring.ai.openai.chat.api-key property.`（见 `V5-附件-backend-console.log` 首次启动记录）。
这与 `application.yml:44-46` 注释「未配置时启动会给出明确提示，AI 功能不可用但业务功能不受影响」及文档已知限制 #4「空 api-key 时 AI 返回 401 错误；业务功能不受影响」**不一致**。按任务约定，本次仅设置占位假值 `dummy-for-verification` 用于启动，全程未调用任何 AI 接口。

### 数据集与脚本

- 新建测试定义 `TO_CFG`（GLOBAL 级，主键字段 `rowKey`，普通字段 `value`），用 REST 创建：`POST /api/definitions`。
- 测试 Excel：用 JDK 单文件程序生成最小 xlsx（内联字符串、表头 `rowKey/value`），内容 `TO3 / v3`；批量场景另生成 `TO_CFG_v9.xlsx`（`TO3 / V93`）、`TO_CFG_new.xlsx`（`NEW1 / n1`）、`TO_CFG_big.xlsx`（5000 行，V6 用）。生成器 `<WORKSPACE>/v5v6/MakeXlsx.java`（临时目录，不在被核验分支内）。
- 作业流程：`POST /api/tasks` 建任务 → `POST /api/tasks/{id}/select-defs` → `POST /api/tasks/{id}/files/upload` → `POST /api/tasks/{id}/jobs {"jobType":"IMPORT"}` →（导入完成）→ `POST /api/tasks/{id}/jobs {"jobType":"PUBLISH"}`；用 `GET /api/jobs/{id}` 轮询到终态，`GET /api/jobs/{id}/issues` 取问题列表，`GET /api/jobs/{id}/diff` 看暂存行快照，`GET /api/data/TO_CFG?scopeType=GLOBAL` 看已发布行。

---

## 3. 实际观察结果

### 3.1 实验序列与任务/作业编号（均为实测产生）

| 任务 | taskId | 文件内容 | 导入 jobId | 暂存行 baseVersion | 发布 jobId | 发布结果 |
|---|---|---|---|---|---|---|
| T0 seed | 1 | TO3 / v3 | 1 COMPLETED | 1（新建时行不存在） | 2 COMPLETED | 生成已发布行 version=1 |
| T_A | 2 | TO3 / v3 | 3 COMPLETED | **1** | 5 **COMPLETED** | version 1→2 |
| T_B | 3 | TO3 / v3 | 4 COMPLETED | **1** | 6 **FAILED** | 冲突提示 |
| T_C（对照1） | 4 | TO3 / v3 | 7 COMPLETED | **2** | 8 COMPLETED | version 2→3 |
| T_D（对照2，内容不同） | 5 | TO3 / **V93** | 9 COMPLETED | **3** | 10 COMPLETED | version 3→5，内容更新为 V93 |
| T_E / T_F（同时发布） | 6 / 7 | TO3 / V93 | 11 / 12 COMPLETED | **5** / **5** | 13 / 14 | 13 COMPLETED（→6），14 **FAILED** |
| T_G / T_H（新行争抢） | 8 / 9 | NEW1 / n1 | 15 / 16 COMPLETED | **null** / **null** | 17 / 18 | 17 COMPLETED（创建行），18 **FAILED** |

### 3.2 主场景：先发布 T_A（成功），再发布 T_B（冲突提示）

- `POST /api/tasks/2/jobs {"jobType":"PUBLISH"}` → HTTP 201，`{"success":true,"data":{"id":5,"taskId":2,"jobType":"PUBLISH","status":"PENDING",...}}`
- `GET /api/jobs/5` → `"status":"COMPLETED","progress":1,"total":1,"errorCount":0`，`startedAt 18:54:34.279225` → `finishedAt 18:54:34.306225`
- `GET /api/jobs/5/issues` → 空
- `POST /api/tasks/3/jobs {"jobType":"PUBLISH"}` → HTTP 201，`{"success":true,"data":{"id":6,"taskId":3,"jobType":"PUBLISH","status":"RUNNING",...}}`
- `GET /api/jobs/6` → **`"status":"FAILED","progress":1,"total":1,"errorCount":1`**（`18:54:34.741378` → `18:54:34.76354`）
- `GET /api/jobs/6/issues` → **冲突提示原文**：

```json
{"success":true,"data":{"content":[{"id":1,"jobId":6,"defCode":"TO_CFG","rowKey":"TO3","fieldCode":null,"severity":"ERROR",
 "message":"发布冲突：行 TO3 在导入后被其他操作修改（快照版本 1，当前版本 2），请重新导入后再发布","rowIndex":null}], ... "totalElements":1 ...}}
```

**与自述逐字一致**（S1 中的文案、快照版本 1/当前版本 2、FAILED）。

### 3.3 「冲突提示」的真实响应形态（重要）

- 发布是**异步作业**：`POST /api/tasks/{id}/jobs` 一律返回 `201`，**不存在 HTTP 409 / 409 冲突语义**。全仓 `GlobalExceptionHandler` 只映射 400/404/500；实测冲突发布请求本身也是 201。
- 冲突的对外形态是：**作业终态 `FAILED` + `errorCount=1` + `GET /api/jobs/{id}/issues` 中的一条 `severity=ERROR` 文案**。
- 冲突**不是**异常：`V5-附件-backend-console.log` 中没有任何 `Publish failed` / 异常堆栈 / `OptimisticLock` 字样，作业 5、6 只有两条 `AsyncJobExecutor Starting job ... type=PUBLISH` INFO 行。也就是说冲突走的是 `hasConflict()` 业务守卫分支，而不是乐观锁异常分支。

### 3.4 发布完成后数据库实际状态

- 已发布行（`GET /api/data/TO_CFG?scopeType=GLOBAL`）：

```json
{"defCode":"TO_CFG","scopeType":"GLOBAL","scopeKey":null,"rowKey":"TO3","dataJson":"{\"rowKey\":\"TO3\",\"value\":\"v3\"}","version":2,
 "createdAt":"2026-10-06T18:54:28.697909","updatedAt":"2026-10-06T18:54:28.697909"}
```

- **以 T_A（先发布者）的数据为准**，T_B 的发布被整体拒绝对该行生效，未出现静默覆盖、也未出现 500。
- 暂存行状态（`GET /api/jobs/{jobId}/diff`）：T_A 的暂存行 `status=PUBLISHED`（`baseVersion` 仍为 1）；T_B 的暂存行 `status=FAILED`（`baseVersion` 仍为 1，供重新导入比对）。

### 3.5 OPTIMISTIC_FORCE_INCREMENT 的独立证据（S2/S3）

三次「内容完全相同」的发布，`dataJson` 始终是 `{"rowKey":"TO3","value":"v3"}`，但版本每次都递增：

| 动作 | 发布前 version | 发布后 version | dataJson 是否变化 |
|---|---|---|---|
| T0 首次发布（新建行） | — | 1 | — |
| T_A 发布 | 1 | **2** | 否（v3 → v3） |
| T_C 发布 | 2 | **3** | 否（v3 → v3） |
| T_E 发布（并行） | 5 | **6** | 否（V93 → V93） |
| T_D 发布（内容确有变化） | 3 | **5** | 是（v3 → V93，+2） |

若仅有 `@Version` 而无强制递增，脏检查会跳过 UPDATE、版本不动（正是 S2 描述的缺陷 #9 根因），快照比对将永远不冲突。实测版本在内容零变化时仍递增 → **强制递增行为存在且生效**。

### 3.6 对照场景：无冲突的正常发布仍成功（证明不是「一律拒绝」）

- 对照 1（T_C）：当前行 version=2 时导入 → 暂存行 `baseVersion=2` → 发布 **COMPLETED**，`errorCount=0`，issues 为空，行 version 2→3。
- 对照 2（T_D）：内容真正改变（V93）→ 暂存行 `baseVersion=3` → 发布 **COMPLETED**，issues 为空，行 version 3→5，`dataJson` 更新为 `{"rowKey":"TO3","value":"V93"}`。
- 结论：冲突检测是「版本守卫」而非「一律拒绝」，合法发布照常生效。

### 3.7 真正同时发起的双发布（并行发出两条 PUBLISH）

- 两条 POST 用 `curl ... & ... & wait` 同时发出：job 13（T_E）`createdAt 18:55:00.613`，job 14（T_F）`createdAt 18:55:00.626995`。
- 结果：job 13 `COMPLETED`（`18:55:00.613994 → 18:55:00.624995`），job 14 `FAILED/errorCount=1`，issue 原文：

```json
{"message":"发布冲突：行 TO3 在导入后被其他操作修改（快照版本 5，当前版本 6），请重新导入后再发布", "severity":"ERROR", ...}
```

- 诚实说明：虽然两条请求同时发出，但 job 14 的事务实际在 job 13 **提交之后**才开始（时间戳可见差 14ms），因此这一次仍然是**快照比对**命中冲突，**没有**观察到 `ObjectOptimisticLockingFailureException`/行锁竞争路径。乐观锁在真正同刻竞争时的行为本次未取得直接观测（见第 6 节「未覆盖」）。

### 3.8 冲突判定另一分支：新行被抢先创建（对应 S5 的 N-IM-04）

- T_G、T_H 各导入尚不存在的行 `NEW1`，两任务暂存行 `baseVersion` 均为 `null`。
- T_G 发布 → COMPLETED，创建该行（version=1）。
- T_H 发布 → FAILED/errorCount=1，issue 原文：

```json
{"message":"发布冲突：行 NEW1 在导入后被其他操作修改（快照版本 null，当前版本 1），请重新导入后再发布", "severity":"ERROR", ...}
```

---

## 4. 与声明逐项对照

| 声明 | 实测 | 判定 |
|---|---|---|
| S1 两会话同时导入同一行 → 快照版本均为 1 | T_A/T_B 暂存行 `baseVersion` 均为 1（`GET /api/jobs/3/diff`、`/4/diff`） | **符合** |
| S1 先发布者成功、行版本 1→2 | job 5 COMPLETED，行 version=2 | **符合** |
| S1 后发布者 ✅ FAILED + 原文冲突提示（快照版本 1，当前版本 2） | job 6 FAILED、errorCount=1，issue 文案与自述逐字一致 | **符合** |
| S2/S3 乐观锁保证「无变化的发布」也递增版本 | 三次内容零变化的发布版本 1→2→3、5→6 | **符合**（机制存在且生效） |
| S3「乐观锁（@Version）+ 业务守卫」 | 源码确为 `@Version` + `hasConflict()` 守卫；冲突文案由守卫产生，乐观锁负责版本推进 | **符合** |
| S4「发布失败并报冲突（快照版本 vs 当前版本），需重新导入」 | 冲突后暂存行仍为 `FAILED` 且保留 `baseVersion`，须重新导入 | **符合** |
| S5 N-IM-03 版本变更冲突 / N-IM-04 新行被抢先创建 | 两者均实测复现 | **符合** |
| （隐含）冲突以同步 HTTP 错误（如 409）返回 | **不成立**：接口恒 201，冲突形态为异步作业 FAILED + issues 文案 | **形态修正**（自述本身写的就是「FAILED + 问题」，故不构成不符） |

---

## 5. 结论

**【实测-符合】**

两点需要带入合流的形态性说明（非不符，但会影响调用方/前端预期）：

1. **冲突不是同步 HTTP 错误码**。发布接口是异步作业（`POST /api/tasks/{id}/jobs` → 201），冲突只能通过「作业终态 FAILED + errorCount + `GET /api/jobs/{id}/issues` 的 ERROR 文案」或任务 SSE 的 `JOB_DONE` 事件感知。任何依赖 409/400 同步判定的合流方案都要改。
2. **「冲突提示」的直接来源是暂存行 `base_version` 快照比对（业务守卫），乐观锁（`OPTIMISTIC_FORCE_INCREMENT`）是可触发该守卫的前提**：它保证内容无变化的发布也推进版本号。因此二者缺一不可——合流时不要把 `OPTIMISTIC_FORCE_INCREMENT` 当作「冲突检测本身」删掉，也不要把快照字段 `base_version` 迁移丢掉（依赖 `V2__staging_base_version.sql`）。

补充观察（顺带发现，不在本项结论内，供合流时复查）：

- `POST /api/definitions` 的字段对象**不接受** `isKey`（实测返回 500：`Unrecognized field "isKey" (class ...ConfigField)`），须用 `key`；而 `frontend/src/views/DefinitionsView.vue:286` 发送的正是 `isKey`，同文件 242/253 行也以 `f.isKey` 读取。本次未通过 UI 端到端复现，仅作为线索记录。
- 发布接口的**创建响应体里的 `status` 不可信**：job 5 创建响应为 `PENDING` 却带 `startedAt`，job 6 创建响应为 `RUNNING`，而库中实际是 `PENDING`（见 V6 附件 D 节，同一竞态）。判断状态应重新 `GET /api/jobs/{id}`。

---

## 6. 未覆盖 / 已知边界

- 未观察到真正 DB 级行锁竞争下的 `ObjectOptimisticLockingFailureException` 路径（3.7 说明）：两次「同时」发布的实际执行时序仍先后错开 14ms。若需要该路径的直接证据，需要让单次发布事务持续更久（如暂存行数 >100 触发 `app.job.demo-batch-delay-ms` 休眠），本次未做，以免为核验改写运行配置造成口径不一致。
- 未驱动前端 UI，全部走 REST。
- 本项未验证多配置项（多 defCode）任务的「部分行冲突」时作业的整体行为（本次每任务仅 1 个 defCode、1 行）。
