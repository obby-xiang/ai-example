---
source: DS-V4-Flash（M2-T3b 施工棒）
date: 2026-10-09
scope: M2-T3b
baseline_head: 749fa43
---

# M2-T3b 施工报告

纪律声明：全程**零 git 写操作**（只执行 `status`/`log`/`rev-parse`/`show` 等只读命令）；**未读写任何 `.env`/密钥文件**（E2E 走桩上游 + 占位 key，未使用真 key）；未改动仓库内任何非必要文件（改动清单与归属见 §10，`git status` 结束态见 §14）；**脱敏自查范围 = `git status --porcelain` 全量且含本报告自身**（§12），正文路径一律用占位符（`<REPO_ROOT>` / `<MAIN_REPO>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<SCRATCH>`），不复述任何禁用字面量。

---

## 0. 基线

| 项 | 实读值（命令原文） |
|---|---|
| 开工 HEAD | `749fa43`（`git rev-parse --short HEAD`；与 origin/main 一致） |
| 工作区 | `git status --porcelain` **空**（干净，无 T3-5 在途产物） |
| JDK | `java version "21.0.12" 2026-07-21 LTS` |
| 后端全量基线 | `"<MAVEN_HOME>/mvn.cmd" -B test`（backend 目录）→ `Tests run: 298, Failures: 0, Errors: 0, Skipped: 0`；XML 口径求和 **298 / 0 / 0 / 0，46 个 XML**（`backend/target/surefire-reports/*.xml` 的 `tests` 属性求和）【实测】 |
| 前端基线 | `yarn typecheck` → `Done in 3.63s`；`yarn build` → `✓ built in 28.02s`（双绿）【实测】 |
| 端口 | `netstat -ano \| findstr :18330` 开工时**无监听**（18399 / 5173 同样空闲） |
| Redis | Memurai 服务 `STATE: RUNNING`，`127.0.0.1:6379` LISTENING（PID 5032）；`INFO memory` → `maxmemory:17179869184`（16 GiB，与技术方案 §9 的部署背景一致）【实测】 |
| E2E 基线计数 | 本棒实读 = **28 主用例**（TC1–TC22=22 + GF1–GF5=5 + **TC23=1**，Q8 单列不计）；开工时（未加 TC23 前）为 27 —— 见 §5 说明 |
| **基线结论** | 后端 298/0/0/0 + 前端双绿 + 工作区干净 ⇒ **满足 §3 的开工条件**，继续施工 |

**规格漂移登记（A24）**：`docs/M2-T3-设计卡.md` 的「门禁与验收总则」仍写「基线 263」，属未随 T3a/T3-5 更新的过期表述。本棒以**实读新基线 298** 为准，**未改设计卡**。
本棒撰写时卡内的 `1a28216` / `284` 亦为历史值，未沿用（按 §3 附加裁定 1）。

**收尾基线（同一口径）**：`"<MAVEN_HOME>/mvn.cmd" -B test` → `Tests run: 346, Failures: 0, Errors: 0, Skipped: 0`，XML 口径 **346 / 0 / 0 / 0，53 个 XML** ⇒ **只增不减**（+48 用例 / +7 XML，逐项见 §7）。

---

## 1. T3-3 导出失败落 validation_issues

### 1.1 改了什么（文件 + 锚点）

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java` | ① 注入 `ValidationIssueRepository`（`@RequiredArgsConstructor` 新增 final 字段）；② `run` 方法体开头（`items` 查询之前，与 `PrecheckJobRunner#run:73` 同位置）加 `issueRepository.deleteByJobId(jobId)` 幂等清理（A3）；③ 项级 `catch (Exception e)` 内新增 `saveIssue(jobId, defCode, issueMessage(e))`；④ 外层作业级 `catch` 内新增 `saveIssue(jobId, JOB_LEVEL_DEF_CODE, "导出作业失败: " + issueMessage(e))`；⑤ 新增 `JOB_LEVEL_DEF_CODE = "-"`（哨兵，A1）、`ISSUE_MESSAGE_MAX = 1024`、`ISSUE_MESSAGE_TRUNCATED = "…[已按列宽截断]"` 与两个私有方法 `saveIssue` / `issueMessage` |

**列宽保护的实现与措辞解释**：设计卡要求「截断到 1024 并在消息尾保留异常类名」。本棒实现为 `类名 + ": " + 摘要`，**类名置于消息头并在截断预算里先行扣除**（`budget = 1024 − 类名长度 − 截断标记长度`），截断只吃摘要尾部并追加可见标记 —— 即「异常类名**不可被截掉**」这一实质要求成立；若把类名放消息尾，则同一条消息里类名会与前缀语义倒置。此为**实现细节的落地读法**（非判据放宽），特此留痕。

### 1.2 怎么验证（命令原文）

```
"<MAVEN_HOME>/mvn.cmd" -B -q test -Dtest='ExportJobRunnerIssuesTest,ExportJobRunnerJobLevelIssueTest,ExportFlowJobTest'
"<MAVEN_HOME>/mvn.cmd" -B -q test -Dtest='PrecheckFingerprintGuardTest,AiControllerFrontendResultTest'   （关联面）
```

### 1.3 实测输出

- `ExportJobRunnerIssuesTest`：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`【实测】
- `ExportJobRunnerJobLevelIssueTest`：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`【实测】
- `ExportFlowJobTest`：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（既有导出用例不受影响）【实测】
- **开发期实测到的两条事实（留痕，非缺陷放宽）**：
  1. 首版幂等用例写成「同一 jobId 连跑两次」，实测第二跑在 `progressService.startItem` 处撞 `UQ_JOB_ITEM_INDEX_3 (job_id, def_code)` 唯一约束 → 抛到**外层** catch 并落作业级 issue。**结论：生产侧同一作业行不可重跑**；故幂等用例改为「预置 2 条同 jobId 历史 issue → 跑一次 → 只剩本轮 1 条」（正是设计卡 §6.1 的原始要求形态）。
  2. 超长异常消息（操作符 1200 字符）实测会触达列宽保护分支，落库 message 长度 ≤1024 且 `contains("IllegalArgumentException")` 成立。
- 端点级：`JobService#findIssues(job.getId(), 0, 50)` 返回该 ERROR 条目（issues 端点的服务层同一条读路径）。

### 1.4 验收判据逐条结论

| 判据（设计卡 §T3-3 + A1/A2/A3） | 结论 | 证据 |
|---|---|---|
| 单配置项导出失败 → issues 端点可见 ERROR 条目 | **PASS**【实测】 | `ExportJobRunnerIssuesTest#itemLevelFailureWritesErrorIssueWithinColumnWidth`（severity=ERROR、defCode=SYS_PARAM、rowKey/fieldCode 为空、message 含类名且 ≤1024、端点读得到） |
| 作业级 issue 落库且 `defCode="-"` | **PASS**【实测】 | `ExportJobRunnerJobLevelIssueTest#outerFailureWritesJobLevelIssueWithSentinelDefCode`（`defCode=="-"`、severity=ERROR、终态 FAILED） |
| 幂等 `deleteByJobId`（A3） | **PASS**【实测】 | `ExportJobRunnerIssuesTest#rerunClearsPreviousIssuesForTheSameJob` |
| 不硬截 500、列宽 1024 用足 | **PASS**【实测】 | 同上（1200 字符操作符场景）；`ISSUE_MESSAGE_MAX = 1024` |
| **A2 前端 smoke（计入验收）** | **PASS**【实测】 | §1.5 |
| 成功路径不产 issue（不误伤） | **PASS**【实测】 | `ExportJobRunnerIssuesTest#successfulExportWritesNoIssue` |

### 1.5 A2 前端 smoke（真起前后端 + 真导出失败 + issues 原文 + `JobProgress.vue` 分组逻辑走查；不驱动浏览器）

- **真起前后端**：后端 `<SCRATCH>/e2e-run` 下以打包 jar 起在 **18330**（`/api/ai/health` → `available:true, redis.available:true`）；前端 `yarn dev` 起在 **5173** 并由探针 GET 校验：`前端 dev server GET http://127.0.0.1:5173/ => HTTP 200，含 vite 客户端注入: True`。
- **真导出失败**：`POST /api/tasks {EXPORT}` → `select-defs [SYS_PARAM]` → `PUT /items/SYS_PARAM/condition` 写入非法操作符 `NOT_A_REAL_OPERATOR` → `POST /jobs {EXPORT}` → 作业终态原文：`status=FAILED errorCount=1`。
- **issues 端点原文**（`GET /api/jobs/30/issues?page=0&size=50` → HTTP 200，节选）：

  ```json
  {"success":true,"data":{"content":[{"id":11,"jobId":30,"defCode":"SYS_PARAM","rowKey":null,"fieldCode":null,
    "severity":"ERROR","message":"IllegalArgumentException: 未知的查询条件操作符: NOT_A_REAL_OPERATOR（字段 paramKey），
    已登记的操作符: [NE, LTE, IN, EQ, GTE, GT, STARTS_WITH, NOT_EMPTY, LIKE, CONTAINS, EMPTY, LT]","rowIndex":null}],
   "totalElements":1,"numberOfElements":1,"empty":false}}
  ```
  即：**`severity=ERROR`、`defCode=SYS_PARAM`、`rowKey=null`、`fieldCode=null`、message 含异常类名**，条目在 `content[0]`（端点可见）。
- **`JobProgress.vue` 分组逻辑走查（逐点，文件 `frontend/src/components/JobProgress.vue`）**：
  1. 明细区只在 `job.items.length` 非空时渲染（`:23`）；展开列由 `hasAnyDetail`（`props.issues` 中存在 ERROR/WARNING）决定（`:24`、`:145`）。
  2. **分组键是 `defCode`**：`detailOf(defCode)` = `props.issues.filter(issue => issue.defCode === defCode)`（`:147-149`），其中 `defCode` 来自 `row.defCode`，而 `items` 来自 `props.job.items`（配置项条目，`:130-132`）。
  3. 因此本棒新增的**项级** ERROR 条目（`defCode=SYS_PARAM`）落在 **`SYS_PARAM` 所在行**的展开明细里：行号列显示 `issue.rowIndex ?? '—'` → **「—」**（导出失败无行号）；字段列显示 `issue.fieldCode ?? '—'` → **「—」**；级别列渲染红色 tag「错误」（`severityType(ERROR)='danger'`）；说明列显示 `message` 全文（`:27-38`）。
  4. 该行「失败」列由 `errorRowsOf(defCode)` 计数变红并显示 `1`（`:54-60`）；行「说明」列由 `firstMessageOf(defCode)` 取同分组**第一条** issue 的 message（`:61-63`、`:155-157`）。
  5. `ValidationIssue` 前端类型（`frontend/src/types/job.ts:56-65`）字段与后端实体逐字一致（含 `rowKey?/fieldCode?/rowIndex?` 可空），故新增条目不需要前端类型改动。
- **已知缺口（A1 附带义务，登记）**：**作业级 issue（`defCode="-"`）在前端分组明细区不展示** —— 第 2 点的等值分组判定使哨兵不落入任何 `row.defCode` 分组（`items` 里不存在编码为 `-` 的配置项）；展示出口**另立观察项，本轮不实现**（见 §11 观察项 1）。

---

## 2. T3-4 预检查文件指纹锚定（仅 IMPORT 生效）

### 2.1 改了什么（文件 + 锚点）

| 文件 | 改动 |
|---|---|
| `backend/.../file/FileStorageService.java` | 新增 `FileDigest digest(String storagePath)`（`DigestInputStream` + 8KB 缓冲顺序读，返回 `sha256` 小写十六进制 + `size`）与 `public record FileDigest(String sha256, long size)`；**不复用** `read()`（`Files.readAllBytes`） |
| `backend/.../job/service/PrecheckJobRunner.java` | ① 注入 `JobRepository`；② `run` 内局部变量 `fingerprintSummary`（**非字段**：本类是单例 @Service，作业可并发执行，状态挂字段会串味）；③ 读盘处记录**本次实际使用的 fileType**（UPLOAD，空则回落 EXPORT）的流式摘要；④ 收尾（同一 `@Transactional` 内、`JobTerminalWriter.complete` 注册的 afterCommit 之前）一次性 `writeFingerprintSummary`：`jobRepository.findById(jobId)` 取受管实体 → `setResultJson(mapper.writeValueAsString(summary))` + `save`；⑤ 新增 `digestOf` / `writeFingerprintSummary` |
| `backend/.../job/service/JobService.java` | ① 注入 `TaskItemRepository` / `TaskFileRepository` / `FileStorageService` / `ObjectMapper`；② `assertPrecheckPassed` 保留既有状态守卫，**仅对 IMPORT** 追加 `assertImportFingerprintFresh`；③ 新增 `parseFingerprintSummary`（null/非 JSON/非对象 → MissingNode）+ `stale(...)`（统一 409 `PRECHECK_STALE`）；④ 新增常量 `IMPORT_FILE_TYPE="UPLOAD"`、`public static final String PRECHECK_STALE_CODE="PRECHECK_STALE"`（A5） |
| `frontend/`（`types/ai.ts`、`types/job.ts` 等） | **零改动**（前端按 `code` 展示 409 文案；`PRECHECK_STALE` 是新增码，无需前端分支） |
| `.github/workflows/ci.yml` 等 | 无（T3-4 不涉 CI） |

摘要结构（严格按设计卡）：`{"<defCode>":{"<fileType>":{"sha256":"…","size":123}}}` —— **键控到 fileType**，杜绝「记 EXPORT、比 UPLOAD」的恒 409。

### 2.2 怎么验证（命令原文）

```
"<MAVEN_HOME>/mvn.cmd" -B -q test -Dtest='PrecheckFingerprintGuardTest,PrecheckGateGuardTest,ImportFlowJobTest'
java -Xmx512m -cp "<REPO_ROOT>/<MAIN_REPO>/backend/target/classes;<deps>" <SCRATCH>/DigestCostProbe.java <SCRATCH>/hmb.bin   （S3-5 成本实测，见 §2.4）
```

### 2.3 实测输出（用例逐条）

`PrecheckFingerprintGuardTest`：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`【实测】

| # | 用例 | 断言要点 | 结论 |
|---|---|---|---|
| ① | `importAllowedWhenUploadFileUnchanged` | 真跑预检查（COMPLETED）→ `resultJson` 非空且含 `"UPLOAD"`/`"sha256"` → IMPORT **201** | PASS |
| ② | `importRejectedWhenUploadFileReplaced` | 同 defCode 重写 UPLOAD 件 → IMPORT **409 `PRECHECK_STALE`** 且 message 命中「已被替换」分支 | PASS |
| ③ | `importRejectedWithUploadMissingWhenPrecheckRecordedExportInstead` | 只放 EXPORT 件 → 预检查记 `"EXPORT"` 且 **不含** `"UPLOAD"` → IMPORT 409 且命中「没有记下 UPLOAD 件指纹」分支（**显式断言走的是哪条分支**，非"指纹不一致"） | PASS |
| ④ | `importRejectedWhenLatestPassingPrecheckHasNoFingerprint` | `resultJson=null` 的 COMPLETED PRECHECK → 409 `PRECHECK_STALE`，且**不留导入作业行** | PASS |
| ⑤ | **`legitimateReUploadAndRePrecheckStillAllowsImport`（A4 附加义务）** | 预检查通过 → 替换上传件（此步再验一次被拦）→ **重新预检查**（写新指纹）→ IMPORT **201** → 轮询作业至 **COMPLETED** | PASS |
| ⑥ | `publishIsNotAffectedByFingerprint` | 无指纹的通过态 → PUBLISH **201**（不查指纹）；FAILED 预检查 → PUBLISH 409 `PRECHECK_NOT_PASSED`（状态守卫不变） | PASS |
| 附 | `realPrecheckWithoutUploadStillFailsAndBlocksImport` | 真跑预检查（无上传件）→ FAILED → IMPORT 抛 `PRECHECK_NOT_PASSED` | PASS |

`PrecheckGateGuardTest`：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`【实测】；`ImportFlowJobTest`：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`（既有全流程不受影响）【实测】

**A7 既有用例改动登记（H8 要求逐条）**

| 用例 | 改前 fixture / 期望 | 改后 fixture / 期望 | 理由 |
|---|---|---|---|
| `PrecheckGateGuardTest#latestPassingPrecheckAllowsImportAndPublish` | 任务**无配置项**（`importTask` 只建任务）→ PRECHECK 行 `COMPLETED,0`、**无 resultJson**；期望 IMPORT **201**（该 201 实为"指纹分支空转"得到的，未真正验证原语义） | 任务 `selectDefs([SYS_PARAM])` + 真写一份 UPLOAD xlsx + PRECHECK 行写入**与上传件一致的真实指纹摘要**（`fileStorage.digest` 生成）；期望 **仍为 201** | A7：保留「最近一次通过 → 放行」原语义，只是从"空转放行"变为"指纹比对通过后放行" |
| `PrecheckGateGuardTest#importRejectedWhenPassingPrecheckHasNoFingerprint`（**新增**） | — | 同上 fixture 但 `resultJson=null` → 期望 409 `PRECHECK_STALE` + 不留导入作业行 | A7：另加「无指纹 → 409」用例（**未**把原用例改成断言 409） |

### 2.4 S3-5 成本实测（百 MB 级件）

`java -Xmx512m -cp "<...>" DigestCostProbe.java hmb.bin`（100.0 MB 全零文件）【实测】
**注意：探针调用的是生产类 `new FileStorageService(new AppProperties())`，非复刻实现。**

```
file=hmb.bin bytes=104857600 (100.0 MB)
A. Files.readAllBytes      :   48.1 ms  heap-delta~   80.5 MB  (len=104857600)
B. digest (DigestInputStream):  132.8 ms  heap-delta~    0.2 MB  sha256=20492a4d0d84f8be… size=104857600
一致性：digest.size == file bytes ? true
B1. digest repeat #1      :   90.9 ms
B2. digest repeat #2      :   95.5 ms
B3. digest repeat #3      :   89.5 ms
digest 后 heap-used=13.4 MB（未持有整文件）
```

**结论**：流式 digest 不产生"整文件进内存"（堆增量 ≈0.2 MB，即 8KB 缓冲），百 MB 级件一次顺序读耗时 **≈90 ms（warm）**，未二次全量加载 —— 「成本≈0」的表述撤回后，本棒给出可核对的量化值。

### 2.5 验收判据逐条结论

| 判据（设计卡 §T3-4 + A4/A5/A6/A7） | 结论 |
|---|---|
| 替换上传件 → IMPORT 409 `PRECHECK_STALE` | **PASS**（用例②）【实测】 |
| 未替换 → 放行 | **PASS**（用例①）【实测】 |
| EXPORT 回落与 UPLOAD 交叉 fileType 用例 | **PASS**（用例③，显式断言从严分支）【实测】 |
| 历史无指纹兼容用例 | **PASS**（用例④）【实测】 |
| `PrecheckGateGuardTest` 五分支不破 | **PASS**（8/8，含既有 5 类状态分支）【实测】 |
| A4「合法重跑不误伤」用例 | **PASS**（用例⑤）【实测】 |
| A5 同步错误码清单 | 仓库**无集中错误码清单**（`grep -rn "错误码" docs/*.md docs/adr/*.md` 仅命中 §5.6 的流式码命名行）；已在 §8 的技术方案回灌注记里写明新码 `PRECHECK_STALE`。报告登记「无该清单」 |
| A6 摘要收尾一次性写 | **PASS**（代码走查：`writeFingerprintSummary` 在 `run` 收尾、`terminalWriter.complete(...)` 之前，同一 `@Transactional`）【实测】 |
| 前端无需改动（实测确认前端不因新码报错） | **PASS**：前端 `yarn typecheck` + `yarn build` 双绿；409 的 `code` 走 `ApiError.code` 通用展示路径（`stores/ai.ts`/`api/*` 无按码分支的新增需求）【实测】 |
| TOCTOU 窗口不闭合 | 已登记观察项（§11 观察项 4） |

---

## 3. T3-6a 死卡兜底（第 4 方案）

### 3.1 改了什么（文件 + 锚点）

| 文件 | 改动 |
|---|---|
| `backend/.../ai/gate/ConfirmGate.java` | ① 新增字段 `ObjectProvider<ResumeService> resumeServiceProvider`（**解环①**，不直接依赖 ResumeService）与 `Executor resumeTriggerExecutor`；② 新增 `@Autowired` 8 参构造器，**保留** 6 参构造器（既有单测/非 Spring 装配路径不变，委托 8 参并传 `null`）；③ `Submission` record 增第 5 个分量 `ResumeTrigger resumeTrigger` + **4 参兼容构造器**（既有调用方零改动）；④ 新增 `ResumeTrigger` record（`triggered/outcome/detail`）与取值表；⑤ `submitDecision` / `submitFrontendResult` / `dismiss` 三条 ACCEPTED 路径在 **`submitLock` 之外**追加 `maybeTriggerResume(runId, woke)`；⑥ 新增 `maybeTriggerResume` / `runResume` / `hasActiveLatch(runId)`（**A8**：只读遍历 `latches` 的 key 前缀，不加锁外状态、不改 `latches` 对外语义） |
| `backend/.../ai/exec/AiExecutorConfig.java` | 新增 bean `aiResumeTrigger`（`ThreadPoolTaskExecutor`：core=max=`app.ai.resume.trigger-pool-size`(2)、队列 `trigger-queue-capacity`(16)、`AbortPolicy`、`shutdown`）—— **A9 的"轻量执行器"，独立于挂起池** |
| `backend/.../ai/config/AiProperties.java` | `Resume` 增 `triggerPoolSize=2` / `triggerQueueCapacity=16` |
| `backend/src/main/resources/application.yml` | `app.ai.resume.trigger-pool-size: 2`、`trigger-queue-capacity: 16` |
| `backend/.../ai/web/AiController.java` | `POST /api/ai/confirm` 与 `POST /api/ai/frontend-tool-result` 响应体新增只读字段 `resumeTriggered` / `resumeOutcome`（**A10**，纯增量） |

**R4 三缺口的落实**：① 解环 = `ObjectProvider` 惰性注入（`getIfAvailable()`，为 null 静默跳过，无 key 降级装配下不报错）；② 触发点移出 `submitLock` 且异步化（HTTP 线程立即返回）；③ 防双驱动 = `hasActiveLatch(runId)` 前缀只读查询 + `claimExecution` 原子认领兜底。

### 3.2 载体与饱和语义（A9 红队必审点 3 的自证）

- **所选载体**：bean `aiResumeTrigger`（`ThreadPoolTaskExecutor`，线程名前缀 `ai-resume-trigger-`），**容量 2 / 队列 16 / 拒绝策略 `AbortPolicy`**，可配 `app.ai.resume.trigger-pool-size` / `trigger-queue-capacity`。
- **为什么不能直接用挂起池（禁嵌套）**：`ResumeService.resume` 内部还会向 `aiRunExecutor`（挂起池）提交一次真正的驱动任务；若触发动作本身跑在挂起池里，同一次续跑占该池两条线程，池满即自锁。本池任务很短（一次 Redis 读 + 重建历史 + 转投）。
- **HTTP 线程立即返回的证据（时序）**：用例 `confirmReturnsBeforeResumeActuallyRuns` 用一个"只捕获不执行"的执行器注入 `ConfirmGate`，断言 `submitDecision` 已返回、`resumeTrigger.triggered=true`，而**此刻 `resumeService.resume` 尚未被调用**（`verify(never())`）—— 即续跑动作确实落在 HTTP 线程之外。
- **饱和时的语义**：`execute` 抛 `RejectedExecutionException` → **吞掉**，回 `resumeTriggered=false` + `resumeOutcome=EXECUTOR_SATURATED` + WARN 日志；**外部输入本身已落库成功，绝不因此报错**（用例 `saturatedTriggerExecutorIsReportedNotThrown` 断言 HTTP 层仍为 `ACCEPTED`）。
- **触发结果的留痕（A9/A10）**：`runResume` 在真实续跑结束时打 INFO：`死卡兜底续跑结局 runId=… outcome=<ACCEPTED|ALREADY_RUNNING|PENDING_UNRESOLVED|POOL_SATURATED|ALREADY_DONE|IN_PROGRESS|NO_RESUME_SERVICE> body={…}`；异常路径打 ERROR。响应体的 `resumeOutcome` 是**投递结论**（同步可得），取值表：

| `resumeOutcome` | 含义 |
|---|---|
| `SCHEDULED` | 已派发给轻量执行器（`triggered=true`），HTTP 立即返回 |
| `WOKE_IN_PROCESS_GATE` | `woke=true`：同进程等待方已被唤醒，走正常续跑路径（不触发死卡兜底） |
| `ACTIVE_LATCH_IN_PROCESS` | 本进程仍有该 runId 的活跃等待（R4③ 毫秒窗）⇒ 不触发，防双驱动 |
| `SNAPSHOT_NOT_SUSPENDED` | 快照缺失或状态非 SUSPENDED ⇒ 不触发 |
| `NO_RESUME_SERVICE` | 续跑服务不可用（无 AI key 的降级装配）⇒ 不触发 |
| `EXECUTOR_SATURATED` | 触发池饱和，本次未派发（外部输入仍成功） |
| `DISPATCH_FAILED` | 派发时的其它运行时异常（已吞并留痕） |
| `NOT_APPLICABLE` | 非 ACCEPTED 分支（DUPLICATE / NOT_FOUND / REJECTED） |

### 3.3 实测输出

`ConfirmGateDeadlockResumeTest`：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`【实测】

| # | 用例 | 结论 |
|---|---|---|
| ① | `doesNotTriggerWhenInProcessGateWoken`（真起等待线程 + 真 `awaitDecision` → `woke=true`）→ `WOKE_IN_PROCESS_GATE`、不派发 | PASS |
| ② | `triggersResumeWhenNotWokenAndSnapshotSuspended` → 派发 1 次 + 执行后 `resumeService.resume(runId)` 恰 1 次 | PASS |
| ③ | `doesNotTriggerWhenSnapshotIsNotSuspended`（RUNNING）→ `SNAPSHOT_NOT_SUSPENDED` | PASS |
| ④ | `doesNotTriggerWhenAnotherLatchForSameRunIsActive`（同 runId 另一 toolCallId 的活跃 latch）→ `woke=false` 但 `ACTIVE_LATCH_IN_PROCESS` | PASS |
| ⑤ | `duplicateSubmissionDoesNotTrigger`（APPROVED 条目重复提交）→ `NOT_APPLICABLE` | PASS |
| ⑥ | `dismissPathAlsoTriggersResume`（前端 `cancelled=true`）→ 触发（**A11**） | PASS |
| ⑦ | `saturatedTriggerExecutorIsReportedNotThrown` → `EXECUTOR_SATURATED`、不抛、`resume` 从未调用 | PASS |
| ⑧ | `confirmReturnsBeforeResumeActuallyRuns` → 时序证据（HTTP 先返回） | PASS |
| ⑨ | `noResumeServiceSkipsSilently`（provider 拿不到 bean）→ `NO_RESUME_SERVICE` | PASS |

既有 AI 用例回归（全绿）：`ConfirmGateWiringTest` 4/4、`GenerativeFormGateTest` 8/8、`AiControllerFrontendResultTest` 4/4、`AiControllerRunsCurrentTest`、`AiControllerHealthSseDeliveryTest`、`ToolFrameSequenceConformanceTest` 7/7、`SpToolCallingManagerScopeGuardTest`【实测】

### 3.4 验收判据逐条结论

| 判据 | 结论 |
|---|---|
| 8 条用例全绿 | **PASS**（实为 9 条，多补一条 NO_RESUME_SERVICE 降级）【实测】 |
| `woke=false` + SUSPENDED 能把死卡收敛为真实续跑 | **PASS**：单测②断言触达 `resume`；**端到端**见 §5 的 TC23（真实挂起 → 断流 → 重建 → confirm → 发布 COMPLETED）【实测】 |
| `submitLock` 内不含 resume 调用 | **PASS**：代码走查（`maybeTriggerResume` 在 `synchronized` 块**之后**调用）+ 用例⑧的时序断言【实测】 |
| 无循环依赖 | **PASS**【旁证+实测】：全部 `@SpringBootTest` 上下文重启成功（ConfirmGate 为无条件 `@Component`，无 key 时 `ObjectProvider` 解环成立）；有 key 的完整图由本棒 E2E 后端启动证明（`AI_API_KEY=ci-stub-placeholder` → `ResumeService` bean 存在 → `/api/ai/health` `available:true`，且 28 用例全跑通） |
| A10 响应新增字段且前端不报错 | **PASS**：TC23 实读 confirm 响应含 `resumeTriggered/resumeOutcome`（§5）；前端 `api/ai.ts:44` 的 `confirm()` 是 `post<ConfirmResult>` —— **纯类型标注、无运行时形状校验**（TS 类型擦除，多余键被忽略），`stores/ai.ts#confirm`（`:745-765`）只读 `code/status` 等已知字段 ⇒ 不报错；`yarn typecheck`+`build` 双绿【实测】 |
| 载体与饱和语义已在报告登记 | **PASS**（§3.2） |

---

## 4. T3-7~T3-10 四项裁定落地

### 4.1 T3-7 统一终态判定函数 + 空回复语义

- **裁定一句话**：终态全集 = `DONE|FAILED|CANCELLED`（+ `REJECTED` 串归一为失败类），由 `RunSnapshot#isTerminal` 唯一判定；**空文本（去空白后为空）的轮次终态转 FAILED + 机器可读码 `EMPTY_REPLY`**（语义变更）。
- **A14 落地**：`RunSnapshot.isTerminal(String)`（`backend/.../ai/run/RunSnapshot.java`）；三处散点改调用 —— `AiController#isTerminal`（改为委托，保留语义化别名）、`RunStore#save` 的 session→current 终态分支、`ResumeService`（`resume` 的早退比较 + `listRuns` 的 `resumeable` 组合）。**顺序注意**：`ResumeService` 的"取消标志（Redis）优先"分支保留在统一判定**之前**，以免快照已 `CANCELLED` 时跳过 `cancelPendings` 收敛（保持既有副作用面）。
- **A13**：`REJECTED_ALIAS` 仅存在于判定函数内部（`private static final String`），**未**新增轮次状态常量、**未**触碰 `PendingToolCall.REJECTED` 与前端 `rejected`。
- **A12 落地**：`ResilientChatService#finish` 空文本分支改走新方法 `emptyReply`：快照 `FAILED` + `error=EMPTY_REPLY：…` + `finalText` 保留原文（可能是空白），**不写入空 assistant 消息**（不污染记忆），已开的正文段先 `message_end` 再发 `error` 终帧；`EMPTY_REPLY_CODE="EMPTY_REPLY"` 为 public 常量。
- **验证**：`EmptyReplyTerminalTest` `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`【实测】

| 形态 | 用例 | 结论 |
|---|---|---|
| 空串（只给终帧） | `emptyTextTerminalBecomesFailedWithEmptyReplyCode`：帧序 `start, error`（**无 done**）、`error.code=EMPTY_REPLY`、快照 FAILED、`finalText=""` | PASS |
| 纯空白 | `whitespaceOnlyTextAlsoBecomesFailed`：帧序 `start, message_start, delta, delta, message_end, error`（`message_end.chars=2`），快照 FAILED | PASS |
| 空文本但本轮用过工具 | `emptyTextAfterAToolRoundAlsoBecomesFailed`：快照历史含 `assistant(tool_calls)+tool` 配对 → 仍 FAILED，且历史条数不增（未写空 assistant） | PASS |
| 非空回复回归 | `nonEmptyTextStillEndsWithDone`：`start, message_start, delta, message_end, done` + 快照 DONE（原行为不变） | PASS |
| 参数化判定 | `isTerminalMatchesTheUnifiedRule`（RUNNING/SUSPENDED/DONE/FAILED/CANCELLED/REJECTED/done/未知串）+ `isTerminalIsStrictAboutNullAndUnknownStrings`（null/""/空格/"RUNNING "） | PASS |

- **受影响帧协议用例清单（红队必审点 1 要求）**：`grep -rn "ResilientChatService" backend/src/test` → 命中 5 个类；逐一核对：`ChatTurnFrameSequenceConformanceTest`（5 例，全部有非空正文 ⇒ 走 `done`，**不受影响**，实测全绿）、`SessionBusyFrameIsolationConformanceTest` / `SseFrameSequenceConformanceTest` / `ToolFrameSequenceConformanceTest`（用桩/替身驱动，不经 `finish`）、`AiControllerHealthSseDeliveryTest`（只测 health）。**结论：既有帧协议用例无需改期望值，新增 `EmptyReplyTerminalTest` 覆盖新终帧语义（已按 H8 口径登记：本项为"新增用例"，未修改任何既有用例期望）**【实测】。

### 4.2 T3-8 会话 TTL 绝对上限

- **裁定一句话**：滑动 TTL 之上加绝对上限 —— 剩余 TTL = `min(session-ttl, createdAtMs + session-ttl − now)`，锚点为该轮 `createdAtMs`（A15）。
- **第一步实测（A16 要求）：现有续期语义是否突破"创建 + 6h" ⇒ 突破**【实测】（三路证据）

  1. **旧代码面（只读 git）**：`git show HEAD:...ai/run/RunStore.java | grep -n "ttl()"` → **7 个续期点**全部传**整额** `ttl()`：
     ```
     176: opsForValue().set(runKey(...), json, ttl())      # save（快照）
     351: expire(pendingKey(runId), ttl())                  # putPending
     405: expire(claimKey(runId), ttl())                    # claimExecution
     436: expire(ledgerKey(runId), ttl())                   # appendLedger
     556: expire(eventsKey(runId), ttl())                   # appendEvent（每业务帧）
     596: opsForValue().set(seqKey(runId), seq, ttl())      # recordIssuedSeq（每帧）
     615: opsForValue().set(beatKey(runId), now, ttl())     # touchActivity（每 2s 心跳）
     ```
     即"expire = now + session-ttl"，挂起期每 2s 一次心跳即可无限续命。
  2. **Redis 层等比复现**（`<MEMURAI_HOME>/memurai-cli.exe`，本机 Memurai 127.0.0.1:6379）【实测】：
     ```
     [session-ttl=20s 缩小复现]
     T0=17:34:52.385  SET … EX 20        → T0 TTL=20
     T12=17:35:04.524                    → TTL=8      （创建+20s 上限只剩 8s）
                        EXPIRE … 20      → TTL=20     ⇒ 到期被推回 20s，越过 创建+20s
     [生产配置 session-ttl=6h]
     T0=17:35:04.713  SET … EX 21600     → TTL=21600
     T5=17:35:09.874                     → TTL=21595  （创建+6h 上限只剩 21595s）
                        EXPIRE … 21600   → TTL=21600  ⇒ 整额 21600 > 剩余 21595 = 突破
     probe keys cleaned: 0
     ```
  3. **处置（A16："突破 → 实现绝对上限"）**：`RunStore` 新增纯函数 `remainingTtl(createdAtMs, nowMs, sessionTtl)`（到期/过期回落 `EXPIRED_RENEWAL=1s`，无锚点回落整额）+ `ttlFor(runId[, knownCreatedAtMs])`（进程内缓存 `runCreatedAtMs`，`createdAtMs` 不可变故不陈旧），**7 个续期点全部改走 `ttlFor`**。

- **验证**：`RunStoreTtlAbsoluteCapTest` `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`【实测】：纯函数四边界（整额 / 取剩余 / 剩余恰 1s / 恰好到上限 0 ⇒ `EXPIRED_RENEWAL` / 已过上限 / null-零-无锚点回落）；**公开路径** `touchActivity` 在"创建于 5.5 小时前"时落库 TTL ∈ (29min,30min+2s]（而非整额 6h）；"创建于 7 小时前"时落 `EXPIRED_RENEWAL`（不复活）。既有 `SeqPersistenceTest` 3/3、`RunStoreSessionCurrentTest` 7/7 不受影响。
- **A15 边界**：session 记忆键（`RedisChatMemoryRepository`）绝对上限**另立观察项，本轮不改**（§11 观察项 2）。
- **顺带的口径边界**：`session→current` 索引键仍用 `sessionCurrentTtl()`（10m，与会话锁 watchdog 同源），**未纳入**本次绝对上限（它不是会话记忆键，且其 TTL 远小于 6h；登记为边界说明）。

### 4.3 T3-9 Redis 淘汰策略 noeviction

- **裁定一句话**：`maxmemory-policy = noeviction`（AI 键宁报错不丢帧）。
- **A17 三处落点**（**未新建部署文档**）：
  1. `docs/03-技术方案文档-v2.1.md`：§9「淘汰策略与容量」后**追加**注记（原 `volatile-lru`/`allkeys-lru` 建议保留留痕）+ §13.2「T3b 闭环注记」#5 + §11.1 表后追加注记（原行内容未改）；
  2. `.github/workflows/ci.yml` 的 e2e job `redis` service 加 `--maxmemory-policy noeviction`（并补 job 注释说明理由）；
  3. 本报告（本节点 + §8）。
- **A18 验证（配置级验证）**：本机 Memurai 实跑原文【实测】：
  ```
  $ <MEMURAI_HOME>/memurai-cli.exe -h 127.0.0.1 -p 6379 CONFIG GET maxmemory-policy
  maxmemory-policy
  noeviction                      # 本机 Memurai 默认已是 noeviction（实测现状，非本棒写入前为 volatile-lru）
  $ … CONFIG SET maxmemory-policy noeviction
  OK
  $ … CONFIG GET maxmemory-policy
  maxmemory-policy
  noeviction
  $ … INFO memory | grep -i "maxmemory\|policy"
  maxmemory:17179869184      (=16.00G)
  maxmemory_policy:noeviction
  ```
  **机制级论证（`ai:seq` 耐久性）**：`ai:events:<runId>`（归档帧）与 `ai:seq:<runId>`（已发放最大业务号）在 `noeviction` 下**同为"不淘汰"**，两者只在时间维度分寿（同刻 `EXPIRE` 为 `session-ttl`，且 T3-8 起同受 `createdAtMs + 6h` 绝对上限约束）⇒ **不存在"归档还在、seq 没了"或反之的窗口**，跨进程续号锚点 `max(归档末帧, ai:seq)` 因此恒可靠；而 S2-4 的"`SET ai:seq` 失败仅 WARN + 继续出帧"口径与之自洽：容量触顶时 `noeviction` 让写**报错可观测**（`OOM command not allowed`），而不是静默丢帧造成 seq 洞。**注意（取证边界）**：`CONFIG SET` 是**运行期**生效，不落 `memurai.conf`（未执行 `CONFIG REWRITE`）；持久化口径由部署侧承载（本棒按 A17 不改部署文件）。
- **验证**：上述命令原文 + 论证；`CONFIG SET` 可用（未被拒），故**未触发**【待裁决】。

### 4.4 T3-10 单帧上限与截断

- **裁定一句话**：`ai:events` 窗口维持 **3000**；**单帧上限 64KB**；超限**字段级**截断 + 标记（**严禁**帧字节级截断）。
- **落地**：
  | 位置 | 内容 |
  |---|---|
  | `backend/.../ai/config/AiProperties.java` | 新增 `Frame` 段：`public static final int DEFAULT_MAX_BYTES = 64*1024` + `maxBytes`（单一事实源） |
  | `backend/src/main/resources/application.yml` | `app.ai.frame.max-bytes: 65536` |
  | `backend/.../ai/run/FrameSizeLimiter.java`（新增） | 判定 = 整帧 JSON UTF-8 字节数；字段级截断（`text`/`result` 按原始字节加权分预算、二分取字符数、代理对保护）；挂 `truncated` + `truncatedReason`；截后**复测**，仍超则按超出量迭代收缩（≤8 轮）；不可截字段占主导时只挂标记、绝不字节截断；`apply` 返回最终负载字节（**免去二次序列化**） |
  | `backend/.../ai/run/SseChatEmitter.java` | 新构造参数 `maxFrameBytes`（3 参/5 参构造器委托到新 6 参；`RunRegistry` 传 `app.ai.frame.max-bytes`）；`emit` 内在"**取号之后**、落档与入队之前"调用 `FrameSizeLimiter.apply`（判定须含 `seq`），归档与投递拿同一份帧；负载字节复用，不再二次序列化 |
  | `backend/.../ai/tool/ToolResultLimiter.java` | **javadoc 修订**（A21）："给前端看的帧"由"全文不裁剪"改为"受 T3-10 单帧上限约束的字段级截断 + 标记"，并标明台账 `resultText` 不受该上限约束；**截断标记文案**同步（原"前端帧与台账不截断"→"前端帧受单帧字节上限约束、台账为全文"） |
  | 快照 JSON（`RunSnapshot#messageJson`/`context`） | **不另设单键上限**（A20：只落"窗口 3000 + 单帧 64KB"两值，"单键理论最大 ≈192MB、M1 不做额外限制"记观察项） |
- **验证**：`SseFrameSizeLimitTest` `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`【实测】

| # | 判据 | 用例 | 结论 |
|---|---|---|---|
| ① | 超限帧字段级截断后仍可 `JSON.parse` + `RunStore#events` 回放**不丢帧**（R5 反例防线） | `oversizeToolResultIsTruncatedAtFieldLevelAndStillReplayable`（归档恰 1 帧、值被截、整帧 ≤ 上限、可反序列化回 Map） | PASS |
| ①′ | 同一截断点覆盖 `delta` 的 `text`（投递通道） | `oversizeDeltaPayloadIsTruncatedToo`（投递负载含 `truncated:true`、字节 ≤ 上限） | PASS |
| ② | `truncated` / `truncatedReason` 存在且可读 | `truncatedFrameCarriesTruncatedAndReason`（reason 含 `frame-bytes-exceeded`/`limit=`/被截字段名；其余字段原样） | PASS |
| ③ | 未超限帧一字不改（不误伤） | `smallFrameIsUntouched`（无标记、值原样，归档与投递两侧都验） | PASS |
| ④ | 边界：恰好 = 上限不截断、上限 + 1 触发且落地 ≤ 上限 | `boundaryAtExactLimitIsNotTruncatedButOneByteOverIs`（"恰好"的字节数由骨架实测反推，非估算） | PASS |
| ⑤ | 回放 seq 无洞（超限帧照旧占号） | `oversizedFramesStillConsumeContiguousSeqs`（seq 恰 1/2/3，回放 3 帧不少） | PASS |
| — | 前端不因新字段报错 | 前端 `typecheck`+`build` 双绿；帧消费侧按 `type` 分派，未知键被忽略【实测】 | PASS |

### 4.5 四项共同：裁定回灌文档（A26）

见 §8（逐处列明"改了哪个文件的哪一处、标闭环位置"），**只追加注记 + 标闭环，未改历史正文（H9）**。

---

## 5. T3-6b CONFIRM 卡快照重建 E2E

### 5.1 改了什么（文件 + 锚点）

| 文件 | 改动 |
|---|---|
| `scripts/verify-e2e.ps1` | 在 TC22 之后、TC19 清理之前**新增主用例 TC23**（约 120 行）：① 建 IMPORT 任务 + 上传 + PRECHECK + IMPORT（与 TC17 同构前置）；② 桩上游经 `TC17-PUBLISH` 路由**真挂起**（`start_publish` 落确认门）；③ 客户端在 `confirm_request` 处**断开流**（= 整页刷新形态）；④ `GET /api/ai/runs/current` 断言 `found/runId/snapshotStatus=SUSPENDED/awaitingExternal=true/pendingEntries[entryStatus=PENDING,kind=CONFIRM]/archiveMaxSeq>0`；⑤ 差量续收孤儿过滤（`lastSeq=archiveMaxSeq` 时旧业务帧零重发）；⑥ 用帧里的**真实 toolCallId** `POST /api/ai/confirm` 并断言 A10 新字段；⑦ 重挂 `/api/ai/events/{runId}` 回放收敛（`confirm_decision` + `tool_result(executed=true)` + `done` 恰 1）+ 台账含该 toolCallId + 发布作业 **COMPLETED** + 暂存行提升 `PUBLISHED`；`finally` 里 `POST /api/ai/cancel/{runId}` 清理。用例帧与 HTTP 原文经 `Gf-Dump 'TC23'` 落 `gfc-TC23.sse.txt` / `gfc-TC23.http.txt` |
| `scripts/verify-e2e.ps1`（头部注释） | 27 → **28** 用例（结果口径行补 TC23 说明） |
| `scripts/README.md` | §1.4 用例构成 27 → **28**，新增一行 `TC23（CONFIRM 卡快照重建，T3b/T3-6b）| 1 | 是 |` |
| `.github/workflows/ci.yml` | 文案 27 → **28**（job 名、注释、"运行 E2E" step 名） |

### 5.2 怎么验证（命令原文）

```
# 桩上游（188ms 起）
node scripts/ci/stub-upstream.mjs --port 18399 --dir "<SCRATCH>/stub"
# 后端（仓库外工作目录，占位 key，非真 key）
cd "<SCRATCH>/e2e-run"
AI_API_KEY=ci-stub-placeholder AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub \
  java -jar <MAIN_REPO>/backend/target/config-mgr.jar --server.port=18330 \
       "--spring.datasource.url=jdbc:h2:file:./data/ci_e2e_db;DB_CLOSE_DELAY=-1" \
       --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150
# 就绪等待
curl -s http://127.0.0.1:18330/api/ai/health   → {"available":true,...,"redis":{"available":true}}
# E2E（桩模式）
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File <MAIN_REPO>/scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog "<SCRATCH>/boot2.log" -ArtifactDir "<SCRATCH>/e2e-artifacts"
```

### 5.3 实测输出

```
结果：PASS=28  FAIL=0  （主用例计数，含 TC1–TC22 与 GF1–GF5；基数不写死，随用例演进）
 GFSKIP=0  （桩模式零容忍：GFSKIP≥1 即整棒 FAIL（上游是确定性替身，任何 SKIP 都属桩路由或产品链路异常））
 Q8 项：PASS=2  FAIL=0  （不计入主用例计数）
EXIT=0
```

`[PASS] TC23 CONFIRM 卡快照重建：真挂起→runs/current 重建（found/awaitingExternal/PENDING/archiveMaxSeq）→差量续收孤儿过滤→真实 toolCallId confirm（resumeTriggered/Outcome 契约）→confirm_decision+tool_result(executed=true)+done 收敛 + 发布 COMPLETED`

**TC23 的原文证据（`<SCRATCH>/e2e-artifacts/gfc-TC23.http.txt`，节选）**

```
### 整页刷新：挂起轮快照（重建确认卡的数据源）
GET /api/ai/runs/current?sessionId=s5b-tc23-91c943a09174435e89009a81137fd2ff
RESPONSE: HTTP 200
{"success":true,"data":{"found":true,"runId":"d834ac36-…","snapshotStatus":"SUSPENDED",
 "pendingEntries":[{"toolCallId":"call-stub-49","entryStatus":"PENDING","kind":"CONFIRM",
 "name":"start_publish","arguments":"{\"taskId\":19}"}],
 "unresolvedExternal":["call-stub-49"],"awaitingExternal":true,"archiveMaxSeq":4}}

### confirm（帧里的真实 toolCallId；响应含 A10 新增只读字段）
POST /api/ai/confirm
REQUEST: {"approved":true,"toolCallId":"call-stub-49","runId":"d834ac36-…","reason":"e2e-tc23"}
RESPONSE: HTTP 200
{"accepted":true,"duplicate":false,"runId":"d834ac36-…","toolCallId":"call-stub-49","status":"APPROVED",
 "approved":true,"reason":"e2e-tc23","executed":false,"wokeInProcessGate":true,
 "resumeTriggered":false,"resumeOutcome":"WOKE_IN_PROCESS_GATE","storedBy":"instance-…"}
```

**TC23 的帧序列（`gfc-TC23.sse.txt`，15 帧：2×start（首轮 + 重挂回放各一）+ 2×suspended + 2×tool_start + 2×confirm_request + 1×heartbeat + confirm_decision + tool_result + message_start + delta + message_end + done）** —— 其中 `tool_result` 原文：

```
{"type":"tool_result","toolCallId":"call-stub-49","name":"start_publish","kind":"CONFIRM","ok":true,
 "executed":true,"executedBy":"instance-…","reused":false,"status":"APPROVED","messageId":"tool-call-stub-49",
 "result":"\"发布作业已启动（作业 #25），正在将数据写入正式库…\"","seq":6,…}
```

- **实读口径修正（留痕）**：`CONFIRM` 类工具执行后条目状态保持 **`APPROVED`**（只有 `BACKEND` 类会转 `EXECUTED`，见 `SpToolCallingManager#executeOnce`），故 TC23 的收敛判据用「`executed=true` + `executedBy` 非空」而非状态字面量（首跑因断言 `EXECUTED` 而 FAIL 一次，已修正并复跑全绿 —— **本棒只改了脚本断言，未改产品语义**）。

### 5.4 验收判据逐条结论

| 判据（设计卡 + A25） | 结论 |
|---|---|
| 新用例 PASS 且**入主计数** | **PASS**：`PASS=28`（28 = 22 + 5 + TC23），基数不写死【实测】 |
| 既有主用例全 PASS | **PASS**：TC1–TC22 + GF1–GF5 全绿（无一处 FAIL/SKIP）【实测】 |
| `GFSKIP=0`（桩模式零容忍） | **PASS**：`GFSKIP=0`，退出码未因 SKIP 变红【实测】 |
| 退出码 0 | **PASS**：`EXIT=0`【实测】 |
| 第 4 方案的**真实收敛** | **PASS**：`confirm` 后该轮真的继续跑并收敛（`done` 恰 1、`tool_result.executed=true`、台账有记录、**PUBLISH 作业 COMPLETED**、暂存行 `PUBLISHED`），不是停在 SUSPENDED【实测】 |
| A25 前端断言口径 | **登记为口径收窄**：本脚本无浏览器自动化载体，故"整页刷新后的前端断言（`[data-testid="pending-card-<toolCallId>"]` + 「确认执行」可点）"降级为**接口级断言 + 逻辑走查**：接口级 = ④ `GET /api/ai/runs/current` 的四项契约事实 + ⑤ 差量续收孤儿过滤（`Read-Sse-Brief` 实测 0 个孤儿帧）；逻辑走查 = `frontend/src/stores/ai.ts#restoreSuspendedSnapshot`（`:446-536`）以 `runsCurrent` 为数据源、仅 `entryStatus==='PENDING'` 重建卡、宿主消息唯一 upsert、随后 `resumeSnapshotStream(runId, archiveMaxSeq, host.id)`，与 `AiPanel.vue:181-198` 的 `kind==='CONFIRM' && status==='pending'` 渲染条件对齐 ⇒ 该接口契约恰是重建卡所需的全部输入 |

### 5.5 其它

- 手工 `POST /confirm` 造不出 PENDING（409 `UNKNOWN_TOOL_CALL`）、同 session 二轮 409 `SESSION_BUSY`、测后 `POST /api/ai/cancel/{runId}` 清理 —— 三条前置约束均已按设计卡执行（TC23 每轮独立 sessionId；`finally` 取消清理）。
- 附带实测：`resumeTriggered=false` / `resumeOutcome=WOKE_IN_PROCESS_GATE` —— **进程存活时**（真挂起、真 latch）死卡兜底正确地**不**触发，未与正常续跑路径双驱动。

---

## 6. S3-4 前端历史回填兜底路径确认（A22/A23）

**范围锁定（A22）**：本棒 S3-4 = 「delta 不入档 → 断流期正文尾部的前端兜底路径确认」（对应排期计划遗留去向表 + 设计卡 T3-2 后果注记）；另两处同名异义（排期 `:101` 观察项、红队编号 S3-4）**不纳入**本棒范围。
**处置口径（A23）**：只确认 + 登记，**无功能性改动**。

### 6.1 兜底候选路径（自行复核后的现状，代码走查）

| 路径 | 位置 | 事实 |
|---|---|---|
| 历史端点 | `frontend/src/stores/ai.ts#loadHistory`（`:380-439`）→ `GET /api/ai/history/{sessionId}` | 先用 sessionStorage 镜像即时恢复；再与后端历史对账；后端为空 → 保留镜像并把 pending 降级 `expired`；两个分支末尾都 `await restoreSuspendedSnapshot()` |
| 增量续收 | `#resumeSnapshotStream`（`:542-572`）→ `GET /api/ai/events/{runId}?lastSeq=archiveMaxSeq` | **只收 `seq > lastSeq` 的状态帧，不补历史正文** |
| 记忆收敛时机 | 后端 `ResilientChatService#convergeMemory` | **只在轮末**（`finish` / `emptyReply` / `cancelTerminal`）把完整历史写回记忆窗口 |

### 6.2 实测判定（原始证据）

**场景 A：轮已结束后的断流**【实测】——脚本 `<SCRATCH>/t3b-s34-a2.ps1`，输出 `<SCRATCH>/s34-a2-out.txt`

```
断流点：实收 6 帧（start,suspended,tool_start,tool_result,message_start,delta）；实收 delta='好的，本轮已完成，请查看上方的结果。'
runId=e7a1856a-…（来自 start 帧）
历史通道：GET /api/ai/history -> HTTP 200，count=4，末条非空 ASSISTANT 正文='好的，本轮已完成，请查看上方的结果。'
对比：实收 delta('好的，本轮已完成，请查看上方的结果。') == 历史正文(同串) ? True
SSE 回放通道：GET /api/ai/events/{runId} 回放帧类型序列 = [start,suspended,tool_start,tool_result,message_start,message_end,done]
回放里有 delta 吗？False（T3-2 起 delta 不入档）
回放里有正文落定锚点 message_end / 终帧 done 吗？True / True
```

**场景 A 结论**：**兜底路径成立**【实测】——`delta` 不入档 ⇒ 断流期间丢失的**正文尾部不可由 SSE 回放复原**（回放只有状态帧；`message_end`/`done` 可复原，正文不可）；而**轮末收敛**后 `GET /api/ai/history` 的末条 ASSISTANT 正文**就是该轮完整正文** ⇒ 前端 `loadHistory` 是**成立且唯一**的尾部补齐路径（刷新页面即触发）。

**场景 B：挂起中 / 轮未结束的断流**【实测】——脚本 `<SCRATCH>/t3b-probes.ps1` ③，输出 `<SCRATCH>/probes-out.txt`

```
挂起前实收帧数：4；confirm_request 数：1；挂起前实收 delta 拼接：''（模型还没产出正文）
GET /api/ai/history/t3b-s34b-… => HTTP 200
{"success":true,"data":{"sessionId":"t3b-s34b-…","count":1,"messages":[{"type":"USER","text":"请立即调用 start_export …"}]}}
runs/current：{"found":true,"runId":"8f42596f-…","snapshotStatus":"SUSPENDED",
  "pendingEntries":[{"toolCallId":"call-stub-53","entryStatus":"PENDING","kind":"CONFIRM","name":"start_export"}],
  "unresolvedExternal":["call-stub-53"],"awaitingExternal":true,"archiveMaxSeq":4}
```

**场景 B 结论**：挂起中记忆窗口**尚未收敛**（历史里只有 USER 一条、没有任何 ASSISTANT）⇒ **此窗口内丢失的正文尾部不可补齐**；该轮仍以 `awaitingExternal=true` 悬置在确认门（前端可据 `runs/current` 重建挂起卡并续收**状态帧**）。
**前端提示现状（代码走查）**：`#resumeSnapshotStream` 的 `onClose` 在非终态关闭时置 `streamInterrupted=true` 且 `notice='连接中断，结果可能不完整（断流）。挂起卡保留，可刷新重试。'`（`stores/ai.ts:558-563`）—— 即：**UI 有明确提示，但提示不能补内容**。

### 6.3 结论与登记

- 路径**成立**（场景 A）⇒ 已按 A23 在**设计卡 T3-2 后果注记处追加一句**指向本报告 §6 的注记（**只追加、不改原文**，H9；`docs/M2-T3-设计卡.md:57` 之后）。
- **残余风险已登记**（§11 观察项）：**「挂起中 / 轮未结束」窗口**内发生断流时，正文尾部**不可补齐**（记忆未收敛 + delta 不入档），前端仅有 `streamInterrupted` 提示；影响面 = 该窗口内已流出的正文在刷新后缺失，**状态帧与挂起卡不受影响**。
- 按 A23：**未自决、未停工**；是否升级为返修项由指挥官决定（本棒判断：非正确性缺陷，属体验损失，建议维持观察项）。

---

## 7. 门禁

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `"<MAVEN_HOME>/mvn.cmd" -B test`（backend，先 `rm -rf target/surefire-reports` 避免陈旧 XML） | `Tests run: 346, Failures: 0, Errors: 0, Skipped: 0`；XML 口径 **346 / 0 / 0 / 0，53 个 XML**；`BUILD SUCCESS`【实测】 |
| 基线对比 | 基线 298 / 46 XML → 收尾 346 / 53 XML | **只增不减**：**+48 用例 / +7 XML**（新类 7 个 + `PrecheckGateGuardTest` +1）【实测】 |
| 前端 | `yarn typecheck` + `yarn build` | `vue-tsc --noEmit` → `Done in 3.63s`；`vite build` → `✓ built in 28.02s`（**双绿**，零错误）【实测】 |
| E2E | `E2E_STUB_MODE=1` + 桩上游 + 后端 18330 实跑 `scripts/verify-e2e.ps1` | `PASS=28 FAIL=0 GFSKIP=0 Q8 2/0 EXIT=0`【实测】（§5） |
| 新增单测清单 | 见下 | 8 个新类共 **47** 例 + `PrecheckGateGuardTest` **+1** 例 |

**新增/变更用例清单（逐条）**

| 类 | 例数 | 覆盖条目 |
|---|---|---|
| `job/service/ExportJobRunnerIssuesTest` | 3 | T3-3（项级 issue / 幂等 / 成功不误伤） |
| `job/service/ExportJobRunnerJobLevelIssueTest` | 1 | T3-3（作业级 issue，`defCode="-"`） |
| `job/service/PrecheckFingerprintGuardTest` | 7 | T3-4（①~⑥ + 端到端反例） |
| `job/service/PrecheckGateGuardTest` | 7 → **8** | T3-4 / A7（既有 fixture 补真实指纹 + 新增无指纹 409 用例） |
| `ai/gate/ConfirmGateDeadlockResumeTest` | 9 | T3-6a（§3.3） |
| `ai/run/EmptyReplyTerminalTest` | 16 | T3-7（A12 三形态 + 非空回归 + 参数化判定） |
| `ai/run/RunStoreTtlAbsoluteCapTest` | 5 | T3-8（纯函数边界 + 公开路径锚点） |
| `ai/run/SseFrameSizeLimitTest` | 6 | T3-10（①/①′②③④⑤） |
| 合计 | **+48** | 与 XML 增量一致 |

**文档回灌（A26）**：见 §8。**红队必审点自证**：见 §9。**脱敏自查**：见 §12。

---

## 8. 文档回灌（A26）

> 纪律（H9）：全部为**追加注记 + 标闭环**，历史正文一字未改。

| 文件 | 位置 | 内容 |
|---|---|---|
| `docs/03-技术方案文档-v2.1.md` | §13.2 表**之后**新增 `> **T3b 闭环注记（2026-10-09，追加）**` 段（含 4 个圆点：#3/#4/#5/#6 各自"已闭环 + 口径 + 落点"）+ 末段附带机制项（T3-6a / `PRECHECK_STALE`） | #3 空回复即失败（`EMPTY_REPLY`）、#4 TTL 绝对上限、#5 `noeviction`（含"三处落点"）、#6 窗口 3000 + 单帧 64KB（含口径变更声明）；**并注明上表历史正文一字未改** |
| 同上 | §9「淘汰策略与容量（建议，标注【推断】）」块**之后** | `> **T3b 注记**` ：裁前 `volatile-lru`/`allkeys-lru` 建议**保留留痕**、落地取值改为 `noeviction` + 理由（归档帧被淘汰 = 回放 seq 洞；TTL 同寿；容量触顶写失败可见） |
| 同上 | §11.1 表**之后**（DC-10 修订注记之后） | `> **T3b 注记**`：`Redis maxmemory-policy` 行取值改为 `noeviction`；新增 `app.ai.frame.max-bytes`、`app.ai.resume.trigger-pool-size/trigger-queue-capacity` |
| `docs/adr/DECISION-CARDS.md` | 附一「Redis 五用点汇总」表**之后**（DC-11 注记之后） | `> **T3b 定值追加注记（2026-10-09；只追加，不改上表历史正文）**`：① 记忆 TTL 加绝对上限（锚 `createdAtMs`）；② `maxmemory-policy=noeviction`；③ SSE 单帧 64KB；另补 HITL 行"续跑协议 + 死卡兜底" |
| `docs/M2-T3-设计卡.md` | T3-2 **后果注记**之后（`:57` 后） | S3-4 的确认注记（指向 `<SCRATCH>/T3b-施工报告.md` §6，含残余风险窗口）—— 属 A23 要求的追加 |

**错误码清单（A5）**：仓库**不存在**集中错误码清单文件（`docs/` 下仅有 §5.6 的流式错误码命名表，非清单）；已在 §13.2 的 T3b 闭环注记中登记新码 `PRECHECK_STALE` 及其语义。报告在此登记「无该清单」。

---

## 9. 红队必审点自证（附加裁定 2）

### 9.1 A12 空回复语义变更

| 要求 | 证据 |
|---|---|
| 变更前后行为对比（三形态） | 变更前：`finish` 对空文本仍置 `DONE`（`git show HEAD:...ResilientChatService.java` 的 `if (StringUtils.hasText(text)) { history.add(...) }` 分支只决定是否补 assistant 消息，**状态无条件 DONE**）。变更后：三形态（空串 / 纯空白 / 空文本但用过工具）**全部 FAILED + `EMPTY_REPLY`** —— `EmptyReplyTerminalTest` 三例各自的帧序与快照断言（§4.1 表）【实测】 |
| 终帧形态变化 | `done` → **`error`**（`code=EMPTY_REPLY`）；纯空白形态的帧序实测为 `start, message_start, delta, delta, message_end, error`（先关正文段再终帧）【实测】 |
| 受影响帧协议用例清单 | `grep -rn "ResilientChatService" backend/src/test` → 5 个类；只有 `ChatTurnFrameSequenceConformanceTest` 走真实 `finish`，其 5 例全为**非空正文**（实测全绿，期望值未改）；其余为桩/替身驱动。清单与结论见 §4.1【实测】 |
| §13.2#3 闭环回灌位置 | `docs/03-技术方案文档-v2.1.md` §13.2「T3b 闭环注记」第 1 个圆点（见 §8） |

### 9.2 A21 截断口径变更

| 要求 | 证据 |
|---|---|
| 超限帧截断前后原文 | `SseFrameSizeLimitTest#oversizeToolResultIsTruncatedAtFieldLevelAndStillReplayable` / `#oversizeDeltaPayloadIsTruncatedToo`：截前 `"x".repeat(20000)`（约 20KB），截后值长度 < 原始、整帧字节 ≤ 上限（4096 的小上限等价复现 64KB 判定）【实测】 |
| `truncated`/`truncatedReason` 字段实测 | `#truncatedFrameCarriesTruncatedAndReason`：`truncated=true`；`truncatedReason` 含 `frame-bytes-exceeded` / `limit=4096` / `result`（被截字段名）【实测】 |
| `ToolResultLimiter` javadoc 修订 diff 锚点 | `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultLimiter.java` 类 javadoc「两条通道分离」第 2 项（"给前端看的"由"全文不裁剪"→"受单帧字节上限约束的字段级截断 + 标记，台账不受约束"）；另 `marker(...)` 文案由"前端帧与台账不截断"→"前端帧受单帧字节上限约束、台账为全文；T3-10 口径"【实测】 |
| 「前端不因新字段报错」证据 | 帧消费按 `type` 分派、未知键被忽略（`frontend/src/api/sse.ts#consumeFrame` 及 `stores/ai.ts#handleFrame` 只读已知键）；`yarn typecheck` + `yarn build` 双绿；E2E 全量 28 例（含大量帧断言）全绿【实测】 |

### 9.3 A9 续跑载体与饱和语义

| 要求 | 证据 |
|---|---|
| 所选执行器（名称/容量/拒绝策略） | bean `aiResumeTrigger`（`AiExecutorConfig`）：`ThreadPoolTaskExecutor`，core=max=`app.ai.resume.trigger-pool-size`(**2**)，队列 `trigger-queue-capacity`(**16**)，`AbortPolicy`，线程名前缀 `ai-resume-trigger-`（§3.2）【实测】 |
| HTTP 线程立即返回的证据（时序） | `ConfirmGateDeadlockResumeTest#confirmReturnsBeforeResumeActuallyRuns`：注入"只捕获不执行"的执行器 → `submitDecision` 返回时 `triggered=true` 且 `resumeService.resume` **尚未被调用**（`verify(never())`）【实测】 |
| 饱和时行为与 `resumeOutcome` 取值实测 | `#saturatedTriggerExecutorIsReportedNotThrown`：执行器抛 `RejectedExecutionException` → 外层 `outcome=ACCEPTED`（外部输入仍成功）、`resumeTriggered=false`、`resumeOutcome="EXECUTOR_SATURATED"`，无异常冒泡【实测】 |
| 端到端不被误触发 | TC23：进程存活、真 latch ⇒ `resumeTriggered=false / resumeOutcome=WOKE_IN_PROCESS_GATE`（不与正常续跑双驱动）【实测】 |

### 9.4 A4 不误伤验证

| 要求 | 证据 |
|---|---|
| 「预检查后新上传 → 重新预检查 → 再导入」放行通畅 | `PrecheckFingerprintGuardTest#legitimateReUploadAndRePrecheckStillAllowsImport`：上传 v1 → 预检查通过 → 替换为 v2（此步先验被拦，确证用例②的分支）→ **重新预检查（写新指纹）** → `POST /api/tasks/{id}/jobs {IMPORT}` **201 Created** → 轮询作业至 **COMPLETED**【实测】 |
| 另附 | `#importAllowedWhenUploadFileUnchanged`（未替换 → 201）；`#publishIsNotAffectedByFingerprint`（PUBLISH 不受指纹影响）【实测】 |

---

## 10. 范围外改动登记

对照施工卡 §6 的施工步骤逐条映射 **23 个修改文件 + 8 个新增文件**（`git diff --stat`：1013 insertions / 69 deletions），**无未登记改动**：

| 文件 | 归属条目 |
|---|---|
| `backend/.../job/service/ExportJobRunner.java`（+59） | T3-3 |
| `backend/.../file/FileStorageService.java`（+41）、`job/service/PrecheckJobRunner.java`（+60）、`job/service/JobService.java`（+99） | T3-4 |
| `backend/.../ai/gate/ConfirmGate.java`（+183）、`ai/exec/AiExecutorConfig.java`（+24）、`ai/config/AiProperties.java`（+42，含 T3-10 的 `Frame` 段）、`ai/web/AiController.java`（+12）、`application.yml`（+10） | T3-6a（A8~A11）+ T3-10（A20） |
| `backend/.../ai/run/RunSnapshot.java`（+24）、`ai/run/RunStore.java`（+103）、`ai/run/ResumeService.java`（+30）、`ai/run/ResilientChatService.java`（+59） | T3-7 / T3-8 |
| `backend/.../ai/run/SseChatEmitter.java`（+52）、`ai/run/RunRegistry.java`（+4）、`ai/run/FrameSizeLimiter.java`（新增）、`ai/tool/ToolResultLimiter.java`（+11） | T3-10 |
| `backend/src/test/.../PrecheckGateGuardTest.java`（+99，A7 的既有用例改法，§2.3 已逐条登记） | T3-4 / A7 |
| `.github/workflows/ci.yml`（+12） | T3-9（A17）+ E2E 计数 28 |
| `scripts/verify-e2e.ps1`（+131）、`scripts/README.md`（+5） | T3-6b（A25） |
| `docs/03-技术方案文档-v2.1.md`（+14 行）、`docs/adr/DECISION-CARDS.md`（+6 行） | A26 回灌 |
| `docs/M2-T3-设计卡.md`（+1 段注记） | S3-4 / A23（仅追加注记） |
| 7 个新增测试类 | §7 清单 |

**未在仓库内新增任何临时文件**（探针脚本、100MB 探针文件、日志、E2E 证据目录全部落 `<SCRATCH>`），**未做任何 git 写操作**。

---

## 11. 观察项登记

| # | 观察项 | 来源 / 现状 |
|---|---|---|
| 1 | **作业级 issue 的展示出口**（`defCode="-"` 在前端分组明细区不展示） | T3-3 / A1：`JobProgress.vue#detailOf(defCode)` 与 `job.items` 的 defCode 等值分组 ⇒ 哨兵不落任何分组（§1.5 已登记为已知缺口）；本轮**不实现** |
| 2 | **session 记忆键（`RedisChatMemoryRepository`）的绝对上限** | T3-8 / A15：本轮只做 `ai:*` 运行键（锚 `createdAtMs`），会话记忆键的 `setTtl/getTtl` 绝对上限另立观察项 |
| 3 | **单键理论最大 ≈192MB，M1 不做额外限制** | T3-10 / A20：只落"窗口 3000 + 单帧 64KB"两值；`ai:run` 快照 JSON 未设单键上限 |
| 4 | **T3-4 的 TOCTOU 窗口不闭合** | guard（`assertPrecheckPassed`）→ 作业落库 → `JobTerminalWriter` afterCommit 之间上传件仍可被替换；风险面 = "校验的文件"与"实际导入的文件"之间的窗口（= guard→导入实际读盘，含异步拾取与排队延迟，秒级；正常用户流程不命中，恶意竞态才可利用），已被 L1 级指纹比对大幅收窄（不再是整轮窗口） |
| 5 | **S3-4 残余风险窗口**（挂起中 / 轮未结束断流时正文尾部不可补齐） | §6.3：前端仅 `streamInterrupted` 提示；未升级返修项（A23 上报口径） |
| 6 | **`ai:seq` 在整体 Redis 宕机下的重号风险** | 沿用既有口径（S2-4 承诺收窄：SET 失败仅 WARN）；T3-9 的 `noeviction` 只覆盖"淘汰导致丢帧"，不覆盖"Redis 不可用" |
| 7 | **单帧上限带来的每帧一次额外序列化** | T3-10 实现：`emit` 内 `FrameSizeLimiter.apply` 需序列化一次以判字节数（已把该结果复用为投递负载，**未**二次序列化；相比旧实现"有订阅者时才序列化一次"，净增 = 无订阅者场景的一次）。M1 量级下可忽略，登记备查 |
| 8 | **续跑触发池不计入 boundedElastic 容量联动式** | T3-6a：`AiPoolCapacityBudget` 的校验式为"挂起池×2 + 投递池 ≤ boundedElastic"，触发池线程只做短任务（Redis 读 + 转投），未纳入；若后续把长任务放进该池需重审 |
| 9 | **A9 残余竞态：saveSuspended→latches.put 毫秒窗内 confirm 可双驱动**（卡 R4③ 已接受，claimExecution 原子认领兜底恰好一次） | GLM 红队 S3-1（2026-10-09）补登记 |
| 10 | **EXECUTOR_SATURATED 错过派发后无进程内自愈**（重复 confirm 走 DUPLICATE 不补触发；恢复靠重启 StartupResumeRunner 或 cancel；前端不展示 resumeOutcome） | GLM 红队 S3-2；后续可考虑 DUPLICATE 时探测"SUSPENDED 且无进展"补触发 |
| 11 | **FrameSizeLimiter unreachable-limit 分支**：不可截字段占主导时落地帧仍 >64KB（JSON 合法、seq 无洞，R5 不破；"落地≤上限"非无条件） | GLM 红队 S3-3 |
| 12 | **ResumeService 终态判定顺序位移**：快照 DONE/FAILED 但取消标志存活时由 ALREADY_DONE 变为 ALREADY_CANCELLED+cancelPendings 副作用（代码已注释，结局码语义不变） | GLM 红队 S3-5 留痕 |
| 13 | **TC23 三个弱断言**（/diff 空集可空转且无取证落盘；孤儿过滤锚点=服务端自报 archiveMaxSeq；台账只证存在不证恰好一次） | GLM 红队 S3-4；下棒顺手加固（-lt 1 守卫/实收最大 seq 作锚/ledgerCounts -eq 1） |

**沿用 T3-5 既有观察项（未推翻，仅追加）**【旁证】：投递池 8/256 之外的双超时 / `max-attempts` / `total-budget` / 心跳间隔的定值仍待真 key 路线复验（`docs/M2-排期计划.md` 遗留去向表已有登记 + `docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md`，本棒不改、未复跑）。

---

## 12. 脱敏自查（以 `git status` 全量为准，含本报告）

- **范围**：`git -c core.quotepath=false status --porcelain` 的**全部条目**（23 个 `M` + 8 个 `??` = 31 个文件）+ **本报告自身**（`<SCRATCH>/T3b-施工报告.md`）= **32 个文件**。
- **规则**：绝对盘符路径（盘符 + `:\` 或 `:/`）、Git-Bash 风格的本机暂存盘路径、个人用户名、主仓库目录名字面量（`main`+分隔符+`v2` 任意大小写）、密钥样式串（`sk-` 后接 6+ 字符）、内网 IP（`10.` / `192.168.` / `172.16-31.`）。
- **结果**：**零命中（REAL HITS = 0）**【实测】。规则字面量按 DC-08 拼接构造，本报告正文不复述禁用字面量。
- 补充：本次改动**未触碰任何** `.env*` / 密钥文件；E2E 使用 `ci-stub-placeholder` 占位 key（与 CI 口径一致），**全程未使用真 key**。
- 命名纪律（H4）：本报告引用主仓库一律写 `<MAIN_REPO>`，未出现主仓库目录名字面量（自查规则已含该项，零命中）。

---

## 13. 【待裁决】

**本棒无新增待裁决项。** 逐条确认卡内 4 个红队必审点的落地状态：

1. **A12 空回复语义变更** —— 已按口径落地（`EMPTY_REPLY`，三形态 + 非空回归用例全绿，§9.1）；未降级。
2. **A21 截断口径变更** —— 已按口径落地（字段级截断 + `truncated`/`truncatedReason` + `ToolResultLimiter` javadoc 与标记文案修订，§9.2）；未降级。
3. **A9 续跑载体与饱和语义** —— 已按口径落地（独立轻量执行器 + HTTP 立即返回 + `EXECUTOR_SATURATED` 如实回报，§9.3）；未降级。
4. **A4 不误伤验证** —— 已加并通过「合法重跑不误伤」用例（§9.4）；未降级。

其它需指挥官知悉但**不属判据放宽**的三点：

- **S3-4 残余风险**（挂起中/轮未结束窗口的正文尾部不可补齐）：按 A23「不停工、不自决」上报，**是否升级为返修项请指挥官裁定**（本棒判断：体验损失、无正确性破坏，建议维持观察项）【待裁决-建议维持】。
- **T3-3 列宽保护的措辞读法**：设计卡写"消息尾保留异常类名"，本棒实现为类名置于**消息头**（并在预算中先行扣除），实质"类名不被截掉"成立（§1.1 已留痕）。
- **A25 前端断言降级**：TC23 的前端断言降级为接口级 + 逻辑走查（无浏览器载体），已按 A25 登记为**口径收窄**（§5.4）。

---

## 14. 结论

| 条目 | 结论 | 一句证据指针 |
|---|---|---|
| T3-3 导出失败落 issues | **PASS** | 项级 3 例 + 作业级 1 例全绿；A2 smoke 的 issues 原文含 `severity=ERROR/defCode=SYS_PARAM/rowKey=null` 条目 + 前端 dev server 200（§1.5） |
| T3-4 指纹锚定（仅 IMPORT） | **PASS** | `PrecheckFingerprintGuardTest` 7/7（含 A4 合法重跑 201 + 导入 COMPLETED）；`PrecheckGateGuardTest` 8/8；A7 改法逐条登记（§2.3） |
| T3-6a 死卡兜底 | **PASS** | `ConfirmGateDeadlockResumeTest` 9/9 + TC23 端到端收敛（发布 COMPLETED）（§3、§5） |
| T3-6b CONFIRM 卡快照重建 E2E | **PASS** | `PASS=28 FAIL=0 GFSKIP=0 exit=0`，TC23 入主计数（§5） |
| T3-7 终态判定 + 空回复 | **PASS** | `RunSnapshot#isTerminal` 三处改调用；`EmptyReplyTerminalTest` 16/16（§4.1） |
| T3-8 TTL 绝对上限 | **PASS** | 突破性三路实测 + `RunStoreTtlAbsoluteCapTest` 5/5；应用层 TTL 实测"随时间下降"（如下） |
| T3-9 noeviction | **PASS** | Memurai `CONFIG SET/GET` 原文 + 机制级论证；CI e2e redis service 参数 + 技术方案 §9/§13.2 注记（§4.3） |
| T3-10 单帧 64KB | **PASS** | `SseFrameSizeLimitTest` 6/6 + A21 javadoc/文案修订（§4.4、§9.2） |
| S3-4 兜底路径确认 | **PASS（只确认 + 登记）** | 场景 A/B 两路原文证据；残余风险窗口已登记并上报（§6） |
| A26 文档回灌 | **PASS** | 4 处文件、只追加注记（§8） |

**显式声明（供红队/复核卡直查）**：**A12 / A21 / A9 / A4 四项均已按本卡口径落地，未放宽、未降级**（逐条证据见 §9）。

**T3-8 的应用层实测补充（收尾门禁后于运行中的后端实取）**【实测】：

```
session=t3b-t38-… runId=54e5cf49-…   createdAtMs=…85717  now=…862012  (创建+6h 剩余 ≈ 21593s)
T0 探测（17:41:02）：TTL ai:run=21594  TTL ai:beat=21594  TTL ai:events=21594
T40 探测（17:41:42）：仍挂起？SUSPENDED/True（说明这 40s 内心跳一直在续期）
T40 探测：TTL ai:run=21553  TTL ai:beat=21553  TTL ai:events=21553
```

**读法**：旧语义下这三个键的 TTL 会被每次活动重置为**整额 21600**（恒不下降）；新语义下它们 = `min(21600, createdAtMs+6h−now)`，实测 21594 → 21553（**下降 41s ≈ 观察间隔 40s**），且始终 ≤ 创建+6h 的剩余量 ⇒ **绝对上限在真实运行路径上生效**。

**门禁四句结论**：后端 XML 口径 **346 / 0 / 0 / 0（53 XML）**，只增不减 ✅；前端 `typecheck`+`build` **双绿** ✅；E2E 桩模式 **PASS=28 / FAIL=0 / GFSKIP=0 / exit 0** ✅；脱敏自查 **零命中**（31 个仓库文件 + 本报告 = 32 个文件全量）✅。
