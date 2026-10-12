<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\施工卡-定稿.md`（92312 B，sha256 `bba860e664e766a37e3e7aa7d2846fd1dbee624c42ac945eebe3319bebcfd1fa`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 轮询治理一期施工卡 · 定稿 · R-88

## ⚠️ 本稿完整替代草案

| 项 | 值 |
| --- | --- |
| **本稿性质** | **定稿 = 完整替代件**。本稿**完整替代**草案，**草案仅存档**（不再作为施工依据）。 |
| **被替代件** | `<SCRATCH>\施工卡草案.md` = **62476 B / 443 行** / sha256 `e3dfacf011f78e4dcaad5b601a3d9395ba20fc14d266d6452815a8826a2fe0e9`（本稿执笔前逐字节核验【实测】） |
| **本稿不是增量补丁** | 本稿是**可直接施工的单文件**，草案中每一处被会审/K3 修订的内容**已就地改写**（不留删除线悬空引用）；草案内的**实体不丢档**纪律由**本文件 §6 的终态表**承接（D1~D16 逐条给出终态，草案原文引用见《决策卡》§2）。 |
| **定稿依据** | 《**决策卡-轮询一期**》（`<SCRATCH>\决策卡-轮询一期.md`）**T1~T15** 裁决 —— 全文逐条落点对照，本稿 §6 为承载表。 |
| **定稿执笔** | `agent-563`（子智能体）；决策署名由 **K3 汇总代签** |

---

## 卡头

| 项 | 值 |
| --- | --- |
| 项目 | `<MAIN_REPO>`（前端 Vue3 + Element Plus + Tailwind ／ 后端 Spring Boot） |
| 主仓库 | 本任务**唯一可操作仓库**（见 §10-3 仓库红线）；`<REPO_ROOT>` 与 `<REPO_ROOT>`（trae 线）本次**零读取零触碰** |
| 去向表条目 | **R-88**（`docs/M2-排期计划.md:280`，主）／同类点 **R-89**（`:281`）／关联 **R-26**（`:218`）、**R-14**（`:206`）、**R-13**（`:205`）、**R-94**（`:286`）【实测·逐行命中】 |
| 实读基线 | `HEAD = 6c9c699aa680297ecf0a83ec39eeae8716b68431`（全哈希）【实测】 |
| 工作树 | `git status --porcelain` **零输出 = 干净**；`git status --porcelain backend/` **零输出**【实测】 |
| 实读日期 | 2026-10-12 |
| 一期范围 | 纯前端三步：**条件轮询 → `visibilitychange` 纪律 → SSE 事件触发刷新**；后端零改动（红线，§5） |
| 断言标注 | `【实测】` = 本次实读/实跑亲验（含行号）；`【推断】` = 由实读结构推导，未跑起来验证；`【假设】` = 需施工时现场确认 |
| 本卡纪律 | 只读实读 + 仓库外落盘；未修改仓库任何文件、未做任何 git 写操作、未启动后端环境 |
| 基线门禁（已亲跑） | `yarn test` = **12 spec / 154 tests 全绿 / exit 0**【实测】；`yarn typecheck`（`vue-tsc --noEmit`）= **exit 0 无输出**【实测】；`git status --porcelain backend/` = **空**【实测】 |
| 修订记录 | **2026-10-12 定稿轮**：按《决策卡-轮询一期》T1~T15 全量改写 —— T1（保底 tick 单 interval 动态切换 + `fallbackIntervalMs` + 三条断言）、T2（dirty flag 覆盖三路径 + 注释）、T3（`application.yml:191-203` 等锚点订正）、T4（错峰作用于补刷本身）、T5（卸载出口 close-gate）、T6（`disposed` 复检）、T7（abort 捕获 + 单次出口）、T8（淘汰策略）、T9（止血口径）、T10（D5 论据改写）、T11/T12（驳回登记）、T13（6 条建议项落入）、T14（二期登记）、T15（5 条更正全采纳）。**原草案 -555 轮已按上一轮 K3 裁定改写的 D6/D8 在本稿中保持不动。** |

**本稿相对草案的硬性状态（照抄草案即为不合格）**：

1. **保底 tick 有了承载机制**：草案 T1/T2 对"全静止 30s 内 ≤1 次保底"**没有任何断言**（GLM/DSPro 交叉命中的语义真空）；本稿钉死 **单 interval 动态切换**，接口扩展 `fallbackIntervalMs`，并新增三条断言（§3 T2①/②/③）。
2. **`refreshNow` 不再有静默丢弃路径**：草案 D9 的 dirty flag 只覆盖"事件路径"；本稿**统一覆盖三路径**（SSE 事件帧 / 回可见 `refreshNow` / `JOB_DONE` 触发刷新），语义写进 controller 注释（§2 步 2-1、§3 T4④/⑤）。
3. **D12 从"回收"升为"回收 + 在途作废"**：草案只锁 `dispose` 计数，挡不住已发出的在途 `getJob` resolve 后的迟到写回/`onFinal` 误触新 job（§2 步 1-5、§3 T7②）。
4. **abort 不再产生 unhandled rejection**：草案未提 `stores/workspace.ts:452` 的 `void` 调用点会吞掉 rejection（§2 步 1-3、§3 T6④/⑤）。
5. **卸载出口钉死为跨层显式调用**：订阅状态挂 store（跨路由存活），必须由 `TaskCenterView.onBeforeUnmount` 显式调 store action；`task-events.ts` 派发补 close-gate（§2 步 3-2、§3 T8）。
6. **淘汰策略钉死**：上限 3 内按 `taskId` 稳定排序取前 3，第 4+ 个不订阅（§2 步 3-2、§3 T3①）。
7. **D5 论据改写**：删除"同域 6 连接保护 AI 通道"主论据，改为"防御性上限 + 可观测计数"（§6 D5）。
8. **锚点订正**：`application.yml` 的 `app.task.sse` 段 **`:191-203`**（草案 `:191-198` 为注释头）；`TaskSseService.java` 全路径 `configmgr/task/service/`（§9）。

---

## 0. 定稿结论速览

1. **有网络请求的周期轮询前端 3 条**：`stores/task.ts` 4s 任务列表（`:162`）、`utils/job-watch.ts` 3s 作业兜底（`:106-110`）、`api/jobs.ts` 1s 发布终态等待（`:50-68`）；**登记类定时器 2 条**（`AiPanel.vue:839` 1s tick、`api/sse.ts` 连接看门狗）。前端总账 5 条【实测】。
2. **后端轮询实例 1 条（补登记，不并入一期改动面）**：`JobWatchdog`（258 行）自建调度线程按 `scan-period` 扫在途作业（`JobWatchdog.java:114-123`，`application.yml:222` = `10s`）。
3. **`TASK_CHANGED` 仍是"后端发、前端零消费"**：前端全仓 `TASK_CHANGED` 只出现在 `types/sse.ts:337`（类型）与 `:339`（注释）、`types/api.ts:125` 为文档串 —— **无任何运行时消费点**【实测】。
4. **`watchJob` 的 3s 兜底与通道状态无关**：`utils/job-watch.ts:106-110` 的 `setInterval` 无任何 `channel` 判据；`realtime` 时 3s 照跑【实测】。
5. **停表谓词需新建**：`hasRunningJob` 全仓 **0 命中**、`hasActiveJob` **0 命中**【实测】；`latestJob` 仅作 `TaskSummary` 的行字段（`types/task.ts:112-119`）。
6. **用户可见文案仍在说谎**：`views/TaskCenterView.vue:8`「· 列表每 4 秒自动刷新」【实测】。
7. **【关键】任务级 SSE 通道没有周期心跳**：`HEARTBEAT` 仅由 `TaskSseService.java:88` 在**订阅时补发一帧**；**全后端（main 源码）字面 `"HEARTBEAT"` 仅此 1 处，无任何周期调度发布点**【实测】。`application.yml:191-203` 的 `app.task.sse` 只有 `write-timeout: 15s`（`:202`）/ `emitter-timeout: 5m`（`:203`），**无 heartbeat 键**。其余心跳全属 **AI 通道**（`ConfirmGate.java:77` = 2s、`ResilientChatService.java:96` = 5s）⇒ **不设帧新鲜度阈值**，假死发现依赖 30s 兜底轮询 + `onerror`/`readyState`（§6 D6/D8）。
8. **测试基线可锁**：**154/154 · 12 spec 全绿 / exit 0**（3.11s）【实测】⇒ D1 的 TDD 锁回归有现成跑道。
9. **E2E 门禁不钉死基数（K3 裁定，本稿保留）**：门禁取「**FAIL=0、GFSKIP=0、PASS 数不低于施工前实测基线**」（基数随用例演进，施工第 0 步记录当时实测值）。口径注记：三方记载不一致 —— 批 3③a/③b 实测 **28**、`docs/M2-排期计划.md:347` 记 **29**（GF6 进主计数后）、`.github/workflows/ci.yml:108/212` 按 **29** 命名【实测】。以施工时实测为准。
10. **keep-alive 前提已解（T5）**：`frontend/src/` 全仓 `keep-alive`/`keepAlive`/`onBeforeRouteLeave`/`beforeRouteLeave`/`$reset`/`$dispose`/`import.meta.hot` → **0 命中**【实测】⇒ 路由切换必然触发 `TaskCenterView.onBeforeUnmount`；施工第 0 步只需**复核引用**该实测结论（一行 grep + 结论），**无需补 `onActivated/onDeactivated`**。

---

## 1. 轮询清单总账

### 1.1 有网络请求的周期轮询（前端 3 条 + 后端 1 条）

#### P1 · 任务中心列表 4s 无条件轮询

| 项 | 内容 |
| --- | --- |
| 锚点 | 间隔常量 `frontend/src/stores/task.ts:32`（`TASK_AUTO_REFRESH_MS = 4000`）；timer 声明 `:58`；`setInterval` `:162`；`startAutoRefresh` **`:157-168`**（幂等守卫 **`:158-161`**）；`stopAutoRefresh` `:170-176`；`load` 短路 `:114-116`（`loading` 唯一置位 `:117` / 唯一复位 `:127`）；启动 `frontend/src/views/TaskCenterView.vue:351`（`onMounted` `:348-352`）；停止 `:355`（`onBeforeUnmount` `:354-356`）【全部亲验】 |
| 周期 | 4000ms，恒等间隔，无退避 |
| 拉什么 | `tasksApi.listTasks(queryParams)` → `GET /api/tasks?page&size[&type][&status][&keyword]`（`frontend/src/api/tasks.ts:10-12`），返回 `PageResult<TaskSummary>`；每行含 `latestJob` 行级进度（`types/task.ts:112-119`） |
| 启动/停止条件现状 | `onMounted` 即启、`onBeforeUnmount` 即停；**无「存在在跑作业」条件、无可见性纪律**；仅两道防重叠：tick 前 `if (!this.loading)`（`task.ts:163-165`）与 `load()` 自身 loading 短路（`task.ts:114-116`）。防重叠只防堆叠，**不降频**【实测】 |
| 其他（事件驱动）触发点 | `setPage`/`setSize`/`applyFilters`（`task.ts:131-154`）、`createTask`/`removeTask`/`startJob` 内部 `load()`、ui_event `refresh_tasks`（`stores/workspace.ts:514-517`）、表头手动刷新（`TaskCenterView.vue:12`）—— 均保留不动 |
| 一期治理后预期 | 保持 4s 条件周期（tick 内先判 `hasActiveJob`）；另补 **30s 保底 tick**（D10 终态，机制 = 单 interval 动态切换）。全静止且可见时 `GET /api/tasks` 由 4s 节奏**降为 30s 保底**（**≤1 次/30s**，非归零） |
| 文案同步 | `TaskCenterView.vue:8` 改条件口径并交代保底（D15）；同文件 `:151` 注释「4s 自刷：store 的 setInterval，tick 前查 loading 防重叠（deepseek 蓝本形态）」须一并改实【实测原文】 |

#### P2 · 作业跟踪兜底 3s 轮询（**每被跟踪作业一个实例**）

| 项 | 内容 |
| --- | --- |
| 锚点 | `frontend/src/utils/job-watch.ts:106-110`（`setInterval`）；常量 `JOB_FALLBACK_POLL_MS` `:21` = 3000；首拉 `:121`；拉取体 `syncSnapshot()` **`:86-104`**（`await jobsApi.getJob` `:92`；`onSnapshot` `:93`；`dispose()` `:96`；`onFinal` `:97`；`onError` 仅透传 `:99-100`）；`dispose` `:112-119`；通道标记 `onopen :76-78` / `onerror :79-84`；帧分支 `:57-74`（`JOB_DONE` → `void syncSnapshot()` **`:71`**）；守卫 `:87-89`；`disposed` 闸 `:52-54`/`:58-60`【全部亲验，全文 123 行通读】 |
| 周期 | 3000ms（可由 `pollIntervalMs` 覆盖；两个调用点均未覆盖）【实测】 |
| 拉什么 | `jobsApi.getJob(jobId)` → `GET /api/jobs/{jobId}`（`api/jobs.ts:20-22`） |
| 启动点 | Export：`views/ExportWizardView.vue:723-728`（`beginWatchJob` ← `startExport` `:698-721` 的调用点 **`:715`**），**单实例**（`:724` 先 `stopWatchJob?.()`）；改绑任务回收 `:489-495`；卸载 `:1355-1357`。Import：`views/ImportWizardView.vue:840-888`（`beginWatchJob` ← `startJob` `:806-825` 的调用点 **`:818`**，另 `attachJob` `:504-520` 的调用点 **`:516`**），**多实例**（`:306` 声明 `stopWatchers`，`:887` 只 push）【全部亲验】 |
| 停止条件现状 | ① 拿到终态快照 `dispose()`（`:96`）；② Export 侧重开/改绑/卸载（`:724`/`:490`/`:1356`）；③ Import 侧**仅卸载**（`:1114-1118`）。`onError` 不终止、无最大时长、无 channel 判据【实测】 |
| 已知缺陷（R-14 面） | Import 侧同 kind 重跑 **只 push 新 dispose、不回收旧的**（`:887` 前无同 kind 回收）⇒ 旧 watcher 的 `EventSource` + 3s timer 继续跑，N 次重跑 = N 个残留轮询【实测】 |
| **已知缺陷（T6 新增，红队命中）** | `syncSnapshot` 在 `await jobsApi.getJob`（`:92`）之后**无 `disposed` 复检**，直接 `options.onSnapshot?.(job)`（`:93`）+ 判终态后 `dispose()` + `options.onFinal?.(finalJob)`（`:96-97`）。⇒ 按 kind 回收**只能**清 timer + source，**挡不住在途写回**；而 Import 的 `onFinal`（`ImportWizardView.vue:859-885`）内部 `refreshTaskSnapshot()` 读**当前** `taskId.value` ⇒ 旧 job 终态会**覆盖新 job 状态 / 弹旧终态提示**【实测】 |
| 一期治理后预期 | ① Import 侧按 `kind` 回收旧 watcher（D12）；② **`syncSnapshot` 补 `disposed` 复检**（T6）；③ `channel === 'realtime'` **降为 30s 保底**（不停表清零）、`'polling'` 恢复 3s；首帧与 `onopen` 后各拉一次权威快照（D6）；④ 假死发现依赖 30s 保底轮询 + `onerror`/`readyState`，**不设帧新鲜度阈值**（D8）—— 见 §8 R3-3 |

#### P3 · 发布终态等待 1s 轮询（AI 路径）

| 项 | 内容 |
| --- | --- |
| 锚点 | 循环体 `frontend/src/api/jobs.ts:50-68`（`for(;;)` `:54`；**abort 判定在 loop-top `:55-57`**；`getJob` `:58`；`onTick` `:59`；超时 `:63-65`；sleep `:66`；`timeoutMs` `:52` = `300_000`；`signal` 选项 `:46`）；调用方 `frontend/src/stores/task.ts:294-321`（**前置守卫 `:295-297`**；`waitForJobFinal` 调用 **`:298`**，仅传 `intervalMs: 1000` + `onTick` ⇒ **无 `signal`**；`:307` `await this.refreshCurrent()`；`:308-310` 非 FAILED 静默置 null）；触发方 `stores/workspace.ts:442-456`（ui_event `run_job`，PUBLISH 分支 `:450-454`；`void taskStore.watchPublishOutcome(job.id ?? 0)` **`:452`**；`refreshCurrent` 直调 **`:449`**）【全部亲验】 |
| 周期 | 1000ms，上限 300s |
| 启动/停止条件现状 | 仅 AI 面板驱动的 `run_job(PUBLISH)` 一条路径进入；`signal` 字段已存在但**调用方不传**（`task.ts:298`）⇒ 页面卸载、AI 轮次取消、`resetSession` 都不中止它【实测】 |
| 已知缺陷（R-13 面） | 超时分支 `return job`（**非终态返回、不抛错**，`jobs.ts:63-65`）⇒ `task.ts:308-310` 判 `job.status !== 'FAILED'` 成立 → `publishConflict` 被**静默置 null**【实测】 |
| **已知缺陷（T7 新增，红队命中）** | 调用方是 **`void`**（`workspace.ts:452`）⇒ D2 透传 `signal` 后，abort 触发 `throw new Error('等待作业终态已被取消')`（`jobs.ts:55-57`）经 `watchPublishOutcome`（无 try/catch）上抛到 `void` 调用点 = **unhandled promise rejection**【实测】 |
| 一期治理后预期（D2 终态） | 传入 `AbortSignal` 并在 AI 轮次收尾/组件卸载时中止；**显式捕获 abort Error**（消除 unhandled rejection）；超时分支显式化（不再静默置 null），提示走**既有 notice / 局部态机制**且落**单次出口**（超时 1 次 / abort 1 次，**禁放 `onTick`**）。**不重建 `publishConflict` 渲染位**（归 R-94 批） |

### 1.2 登记类定时器（不计入"接口请求数"判据，但纳入可见性纪律）

#### P4 · AI 面板倒计时节拍 1s tick（纯本地）

- 锚点：`frontend/src/components/AiPanel/AiPanel.vue:839-841`（`setInterval` 1000ms）；清理 `:850-853`；被驱动状态 `now`/`confirmDeadline`/`tickTimer` `:452-454`；`resetPendingWatch` `:792-805`【亲验】
- 只写本地 `now` ref，**零网络请求**；AiPanel 由 `App.vue:32` 常驻挂载
- 一期预期：纳入统一可见性纪律（不可见即停、回可见重置 `now`）。**不作止血点，不作请求数判据**

#### P5 · SSE 连接看门狗与退避重连（不是轮询）

- 锚点：`frontend/src/api/sse.ts`（`EventSource` 构造 `:552`；`startConnect()` `:555`；帧间 idle 看门狗 `:481-494`）
- 一期预期：**不动**。仅登记：`visibilitychange` 暂停的是"我们自己的轮询"，**不覆盖 `EventSource` 自身重连**（P2/P3 的任务级 `EventSource` 同理）

### 1.3 非轮询定时器（明细登记，全部不动）

| 位置 | 用途 | 是否网络 |
| --- | --- | --- |
| `stores/ai.ts:62` 声明 / `:533-537` 赋值（`mirrorTimer`，300ms 防抖） | 会话镜像写 sessionStorage | 否 |
| `stores/ai.ts:52` 声明 / `:1299-1303` 赋值（`formExpiryTimer`） | 生成式表单本地超时 | 否（到点发一次回灌 POST） |
| `utils/frontend-tools.ts` **`:383-408`**（`runWithBudget`；`:410` 起为下一段注释） | 前端工具执行统一预算 | 否 |
| `components/SpreadGrid.vue:107` 声明 / `:137-140` 武装（`readyTimer` 看门狗）；`readyPromise` **`:132-134`** | 表格引擎就绪兜底 | 否 |
| `components/SpreadGrid.vue:86` 声明 / `:176-183`（`:180` 赋值，50ms） | 尺寸防抖 | 否 |
| `utils/excel.ts:847` | Blob URL revoke | 否 |

> **标签订正【实测】**：`readyTimer` = 声明 `:107`、武装 `:137-140`；`readyPromise` = `:132-134`（`new Promise` + `readyResolve` 捕获）。前棒交接词把两者并列，施工引用以本行为准。
> **区间订正【实测·T15-4】**：`runWithBudget` 实为 **`:383-408`**（草案 `:383-410` 尾多 2 行，`:410` 起为下一段注释）。

### 1.4 后端轮询实例（补登记，一期零改动）

#### B1 · `JobWatchdog` 自建调度扫描

| 项 | 内容 |
| --- | --- |
| 全路径 | `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java`（**258 行**） |
| 锚点 | `@PostConstruct start()` `:114-123`（`:117` 建单线程 daemon；`:123` `svc.scheduleWithFixedDelay(this::scanSafely, scanPeriodMs, scanPeriodMs, MILLISECONDS)`）；`scan()` `:159-160` 起（包级可见，供单测直驱）；`:161` `findByStatusIn(IN_FLIGHT)`；配置 `application.yml:220` `heartbeat-interval: 30s` / `:221-223` `watchdog` 块（`scan-period: 10s` = `:222`、`dead-multiplier: 3` = `:223`）/ `:224` `max-execution-time: 30m`【全部亲验】 |
| 性质 | **后端原生轮询实例 +1**（10s 扫表在途作业） |
| 一期处置 | **不动**（D16 后端零改动红线）。本卡"轮询总账" = **前端 5 条 + 后端 1 条** |
| 与一期的关系 | 判死动作链 = `ValidationIssue(ERROR,"-")` → `JobTerminalWriter.complete(FAILED)` → `JobCancellationRegistry.cancel`（`docs/M2-排期计划.md:288` R-96 留痕）；⇒ 作业可能被**看门狗**而非执行器置于终态，而 `JOB_DONE` 仍经 `JobTerminalWriter.java:86` 发出 ⇒ 步 3 的事件触发刷新对该路径同样有效【推断】→ **§4.3-7 要求施工时转【实测】**（T13-6） |

### 1.5 全仓兜底 grep 实测（§1 的来源）

```bash
# 工作目录 = <repo>/frontend/src
grep -rn "setInterval" .            # 调用点 3 处：stores/task.ts:162 / utils/job-watch.ts:106 / components/AiPanel/AiPanel.vue:839
                                    # 另 2 处为类型声明：stores/task.ts:58 / components/AiPanel/AiPanel.vue:454
grep -rn "new EventSource" .        # 2 处：api/tasks.ts:106 / api/sse.ts:552
grep -rn "visibilitychange\|document.hidden" .   # 0 处
grep -rn "TASK_CHANGED" .           # 类型/注释/文档串，运行时消费点 0
grep -rn "hasRunningJob\|hasActiveJob" .         # 0 / 0
grep -rn "openTaskEvents" .         # 仅 api/tasks.ts:105 定义 + :134 出口 + utils/job-watch.ts:57 唯一调用
grep -rn "keep-alive\|keepAlive\|onBeforeRouteLeave\|beforeRouteLeave\|\$reset\|\$dispose\|import.meta.hot" .   # 0 处（T5 前提已解）
```

结论：**`setInterval` 调用点 3 处 / `EventSource` 构造 2 处 / `visibilitychange` 0 处 / `TASK_CHANGED` 运行时消费 0 处 / 停表谓词 0 处 / 卸载替代出口 0 处**【实测】。

---

## 2. 三步任务分解（每步独立可提交）

**顺序理由**：步 1 收益最大、面最小、可独立提交，且它引入的"停表/起表/补刷"开关正是步 3 复用的入口；步 2 是横切所有 timer 的纪律层，与步 1 正交、判据独立（"不可见期间请求数为 0"）；步 3 引入 `EventSource` 生命周期（上限/回收/降级），风险最高，需前两步稳定的节拍语义兜底。
**提交纪律**：**每步一个 commit**，便于按步回退。步 1 不依赖步 2/3 新增文件；步 2 的封装对未接入调用方零副作用；步 3 依赖步 1 的 `refreshNow` 入口（若必须严格串行，步 3 顺延无误）。

### 步 1 · 条件轮询（P1 条件化 + P2 回收/降频 + P3 中止/超时显式化）

**改动文件 / 函数**

1. `frontend/src/stores/task.ts`
   - **新增 getter `hasActiveJob`**（重建被 UI 包拆掉的口径，以**列表行**为准）：
     `state.items.some(it => it.latestJob?.status === 'RUNNING' || it.latestJob?.status === 'PENDING')`
     行字段锚点 `types/task.ts:112-119`；状态枚举锚点 `types/job.ts:12`（`'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'`，非终态真子集 = PENDING/RUNNING，**无遗漏**）【亲验】。
   - **改 `startAutoRefresh` `:157-168`**：tick 内先判 `if (this.hasActiveJob && !this.loading) void this.load()`；**保留 `:158-161` 的 `if (autoRefreshTimer !== null)` 幂等守卫**与 4s 周期；**另加 30s 长周期保底 tick**（D10 终态 —— 机制由步 2 的 controller 承载为**单 interval 动态切换**，步 1 单独提交时可用 `setInterval` 双表过渡，但**合并前必须收敛为单 interval 动态切换**）。
   - **新增 `refreshNow()`**：绕过条件（不看 `hasActiveJob`）、**复用 `loading` 守卫**地拉一次（`:114-116` 短路语义保留），供事件路径与回可见路径调用；**重置 interval 起点**（D7）；**被守卫挡掉时记 dirty flag，收尾补发恰好 1 次**（D9 终态）。
   - **改 `watchPublishOutcome` `:294-321`**：
     - `:298` 调用处补 `signal`；
     - **新增 try/catch 显式捕获 abort Error**（或在 `jobs.ts` 改可判别返回值 + 调用方处理）—— **不得让 rejection 逃到 `workspace.ts:452` 的 `void` 调用点**（T7）；
     - `:308-310` 非终态分支改为**显式提示**（走既有 notice/局部态），**不静默 `publishConflict = null`**（D2）；
     - **提示落单次出口**（超时 1 次 / abort 1 次），**禁止放入 `onTick`**（`jobs.ts:59`，每 1s 一次 ⇒ 300s 上限下 300 次刷屏）（T7）。
   - 停表谓词纳入 `filterStatus` 口径（`:68` 字段）。
2. `frontend/src/api/jobs.ts:50-68`：`WaitJobOptions.signal` 已在 `:46`；超时分支 `:63-65` 的 `return job` 改为**可判别结果**（与调用方同批改，避免双写口径）。
3. `frontend/src/api/tasks.ts`：**注释订正**（T13-5，DSPro S5）—— `:103` 注释现写「任务中心的进度口径**按规格走 4s 轮询**」【亲验原文】，与 `utils/job-watch.ts:21` 的 `JOB_FALLBACK_POLL_MS = 3000` **数字口径不对齐**；本批顺手改实（使注释与本仓实态一致；**纯注释改动，不改行为**）。
4. `frontend/src/views/ImportWizardView.vue`：`beginWatchJob` `:840-888` 入参已带 `kind`；在 `:887`（`stopWatchers.push(dispose)`）前**按 kind 回收旧 watcher**（D12，蓝本 = `ExportWizardView.vue:489-495`/`:724`）。
5. **【T6 新增，必做】** `frontend/src/utils/job-watch.ts#syncSnapshot` `:86-104`：在 `await jobsApi.getJob(options.jobId)`（`:92`）**之后、`options.onSnapshot?.`（`:93`）之前**补 `disposed` 复检：
   - `if (disposed) return`（或等价提前返回）；
   - 效果 = dispose 后的在途快照**不写回 `onSnapshot`、不触发 `onFinal`**；
   - `finally` 的 `inFlight = false` 保持不变（`:101-103`）。
6. `frontend/src/views/TaskCenterView.vue:8`：文案改条件口径并交代保底（D15），例如「有进行中作业时每 4 秒自动刷新；其余情况每 30 秒兜底刷新一次」；同文件 `:151` 注释同改。

**预期行为**：任务中心全静止且可见时 `/api/tasks` 由 4s 降为 30s 保底（**≤1 次/30s**）；一旦出现 RUNNING/PENDING 作业恢复 4s；重复启动同类作业不再累积 watcher；**回收旧 watcher 后其在新作业期内的迟到在途快照不污染新作业状态**；AI 发布等待可在轮次结束时中止、超时不再静默、abort 不再产生 unhandled rejection。

**验证**：见 §4.1（含第 0 步基线实测）。

### 步 2 · `visibilitychange` 纪律（统一定时器封装 + 接入 P1/P2/P4）

**改动文件 / 函数**

1. **新增** `frontend/src/utils/polling.ts`（定名见 **D3**，现**不存在**【实测】）：
   - 导出 **`createPollingController({ intervalMs, fallbackIntervalMs, run, shouldRun }) → { start, stop, refreshNow, isPaused }`**（**T1 终态**：接口扩展 `fallbackIntervalMs`）。
   - **双节奏承载机制 = 单 interval 动态切换**（T1 终态，**不采双 controller 实例**）：`shouldRun()` 为真时按 `intervalMs`（4s），为假时按 `fallbackIntervalMs`（30s 保底）；切换 = 清旧 interval + 起新 interval，**切换瞬间必须清旧 timer**（切换后首个 tick 距切换 ≥ `min(intervalMs, fallbackIntervalMs)`）。
   - 内部挂 `document.addEventListener('visibilitychange', ...)`：
     - `hidden` → 停表（清 interval，**不取消在途请求**）；
     - `visible` → **先按 0~250ms 随机错峰延时补刷一次（错峰作用于补刷本身，T4 终态）**，再重置节奏（**不补偿错过的 tick**）。
   - **注释须写死两条口径**（T2/D7）：
     - ① `refreshNow` **重置 interval 起点**（防"补刷 + 原 tick"双发）；
     - ② **dirty flag + 收尾补发语义**：被 `loading`/`inFlight` 等既有在途守卫挡掉的刷新**不丢弃**，标记待办，在途结束后**补发恰好 1 次**（多次在途叠加**合并为 1 次**）。
   - 覆盖三路径（T2 终态）：① SSE 事件帧路径；② 回可见 `refreshNow` 路径；③ `JOB_DONE` 触发刷新路径（`utils/job-watch.ts:71`，与 tick 共用 `inFlight`）。
2. `frontend/src/stores/task.ts`：`startAutoRefresh` `:157-168` / `stopAutoRefresh` `:170-176` 改为委托该 controller（模块级 timer `:58` 仍是唯一实例）；**幂等语义保持不变**（重复 `start` 不产生第二个 interval、重复 `stop` 不抛错）。
3. `frontend/src/utils/job-watch.ts:106-110`：兜底 poll 交给 controller（保留 `inFlight` 守卫 `:87-88`）；`dispose` `:112-119` 同时卸载 visibility 监听。
4. `frontend/src/components/AiPanel/AiPanel.vue:839-841`（低优先）：1s tick 同款接入（回可见重置 `now` `:452`）；清理点 `:850-853` 同步。

**预期行为**：页面不可见 → 自管定时器全停（任务列表、作业兜底、面板 tick）；回可见 → 每个轮询点**立刻补一次**（错峰 0~250ms 打散齐射）再按原周期走。

**验证**：见 §4.2。

### 步 3 · SSE 事件触发刷新（事件驱动替代部分定时轮询）

**改动文件 / 函数**

1. `frontend/src/utils/job-watch.ts`
   - `channel === 'realtime'` → **不停表清零、降为 30s 保底间隔**；`'polling'`（`onerror` 降级，`:79-84`）→ 恢复 3s；`onopen` `:76-78` 与首帧各拉一次权威快照（**D6**）。把 poll 间隔切换与 `emitChannel` `:51-55` 绑定。
   - `onProgress`（`:61-67`）不改。
   - **`syncSnapshot` 的 `disposed` 复检**（步 1-5）与 interval 切换同批落地。
   - **裁定依据**：任务级通道无周期心跳（§0-7）⇒ "长时间无帧"是**正常态**，停表将失去唯一的假死发现面（R3-3）；降级判定只由 `onerror`/`readyState` 驱动，**不设帧新鲜度阈值**（D8）。反查判据见 §4.3-5。
2. **新增** `frontend/src/utils/task-events.ts`（定名见 **D4**，现**不存在**【实测】）：管 `EventSource` 生命周期（上限/回收/降级），订阅状态字段挂 `stores/task.ts`（D5）。
   - 对当前页"有 RUNNING/PENDING 作业"的任务开 `tasksApi.openTaskEvents(taskId, onFrame)`；
   - **淘汰策略（T8 终态）**：上限内**按 `taskId` 稳定排序取前 3**；**第 4+ 个在跑任务不订阅 SSE**，靠列表条件轮询兜底（`hasActiveJob` 覆盖全体可见行 ⇒ 进度仍 ≤4s 新鲜【推断】）；
   - `onFrame` 分派：`JOB_PROGRESS` 可直更对应行 `latestJob.progress/total`；`JOB_DONE` / `TASK_CHANGED` → `refreshNow()`（**`TASK_CHANGED` 是当前零消费的头号缺口**）；`HEARTBEAT` 只作"通道已建立"判据，不做业务动作；
   - **`onFrame` 派发补 close-gate（T5 终态）**：close 后到达的帧**丢弃、不触发 `refreshNow`**（与 `job-watch.ts:58-60` 既有 `disposed` 闸同款）；
   - **硬约束（D5）**：实例上限 = **3**（与当前页在跑任务数取小者）；筛选/翻页/作业终态/卸载**四个出口同步 `close()`**；订阅数暴露为 **store 可观测字段**；
   - **卸载出口（T5 终态，跨层显式调用）**：订阅状态挂 store（Pinia 单例，**跨路由存活**）⇒ **必须由 `TaskCenterView.onBeforeUnmount` 显式调 store action 关闭全部 `EventSource`**（`views/TaskCenterView.vue:354-356`），**不得依赖组件卸载自动清理**；
   - **D9 终态**：事件路径用 **dirty flag + 收尾补发**（覆盖三路径，见步 2-1 注释）。
3. `frontend/src/api/tasks.ts:105-115`：`openTaskEvents` 补 `onerror` 回调与可选最大时长（`:106` 构造、`:107-113` 现只挂 `onmessage`【实测】）；**不新增后端事件类型**，复用现有 4 类（`types/sse.ts:337`）+ `EventSource` 自动重连。

**预期行为**：作业进度/终态由事件推进（不等下一个 4s tick）；全静止时列表接口由事件唤醒；SSE 正常时向导页的 3s 兜底**降为 30s 保底**（消除"双通道 3s 并行"的浪费，但保留低频自愈，D6）。

**验证**：见 §4.3。

---

## 3. vitest 锁回归清单（TDD 口径，D1）

> ⚠️ **命名空间警告**：本表 **T 编号 = 测试行编号（T1~T8）**，与《决策卡-轮询一期》的 **T1~T15 裁决编号是两套独立命名空间**。引用时须写全（如"§3-T2①"vs"决策卡 T1"），勿混引。
> ⚠️ **C 编号警告**：两份会审报告的 C 编号复用不同主题（GLM 的 C1 = `application.yml` 锚点；DSPro 的 C1 = D12 强化）。本稿一律按**主题**引用，不按 C 编号。

**跑道（现成，实装亲验）**：`frontend/vitest.config.ts` = `environment: 'jsdom'` + `include: ['tests/**/*.spec.ts']` + `setupFiles: ['./tests/setup.ts']`；`tests/` **扁平结构**（**12 个 `*.spec.ts` + `setup.ts`，无子目录**【实测】）⇒ **新增 spec 落 `frontend/tests/` 根，沿用既有命名 `*.spec.ts`**。命令 = `yarn test`（`package.json:11`）/ `yarn test:watch`（`:12`）。**禁新增任何框架/依赖、不动 `package.json` 依赖面**（D1）。

**写法口径（TDD）**：先写 RED（断言目标语义，此时不存在实现）→ 保留 RED 输出留证 → 再实现 → GREEN。核心**纯函数/谓词**优先（不依赖网络、不依赖渲染）。

**桩件注记（GLM S4，必读）**：
- **`EventSource` 桩必须挂 `globalThis`** —— jsdom 30 **不内置 `EventSource`**，且 `api/tasks.ts:106` 是内部直接 `new EventSource`、**非参数注入** ⇒ 只能全局替换（先例 = `tests/setup.ts` 的 `ResizeObserverStub` 挂 `globalThis`，7 行【实测】）。桩件接口须随步 3-3 给 `openTaskEvents` 补 `onerror` **同步演进**。
- **`document.hidden` 须用 `Object.defineProperty` 覆写** —— jsdom 中 `visibilitychange` 可 `dispatchEvent` 模拟，但 `document.hidden` 默认 `false` 且只读。
- **假时钟**：vitest 4 自带 `vi.useFakeTimers()`（`T2/T5/T8` 的"推进 N 倍周期不产生调用"、"30s 恰好 1 次"断言可直锁【推断·基于 vitest 4 标准能力】）；**错峰断言须注入 `Math.random` 可控桩**。
- **DB/网络零依赖**：全部断言走桩件，不打真实后端。

| # | 拟锁对象 | 落点 | 断言（逐条给名 + 语义） |
| --- | --- | --- | --- |
| **T1** | **`hasActiveJob` 谓词** | `tests/polling-predicate.spec.ts` | ① **`predicate-all-idle`**：全静态（行 `latestJob` 为 COMPLETED/null）→ `false`；② **`predicate-running-or-pending`**：任一行 RUNNING 或 PENDING → `true`；③ **`predicate-filtered-scope`**（D10）：筛选口径下"当前可见行"无在跑行 → `false`，**即使全量数据里有在跑行** |
| **T2** | **controller 契约（停表/苏醒/保底/幂等/dirty flag）** | `tests/polling-controller.spec.ts` | ① **`hidden-stops-tick`**：`hidden` → 假时钟推进不产生 `run` 调用；② **`visible-single-catchup`**：`visible` → **恰好补 1 次** `run`，随后按原周期；③ **`no-missed-tick-compensation`**：hidden 期间推进 N 倍周期，visible 后总调用数 = 1 + 后续实数；④ **`refreshnow-resets-interval`**（D7）：`refreshNow` 后原周期**不立刻叠加触发**；⑤ **`catchup-jitter-window`**（D11 终态）：**错峰延时作用于补刷本身**，`Math.random` 桩下延时落在 **0~250ms**，**同一假时钟口径下"推进 ≤250ms 窗口内观测恰好 1 次 `run`"**；⑥ **`inflight-not-cancelled`**：`hidden` 期间已发出的 `run` promise 仍可 resolve；⑦ **`start-stop-idempotent`**（GLM S1）：重复 `start()` 不产生第二个 interval、重复 `stop()` 不抛错（对齐 `stores/task.ts:158-161` 守卫语义）；**⑧ `fallback-tick-when-skipped`**（T1 终态①）：`shouldRun=false` 持续 **30s**（假时钟）→ `run` **恰好 1 次**且以 30s 为周期；**⑨ `fallback-and-conditional-not-same-tick`**（T1 终态②）：保底 tick 与 4s 条件 tick **不同 tick 双发**（不出现同 tick 两次 `run`）；**⑩ `dirty-flag-refreshnow-path`**（T2 终态）：`refreshNow` 撞在途守卫 → 标记待办 → 在途结束**补发恰好 1 次** |
| **T3** | **订阅上限与回收计数** | `tests/task-events.spec.ts` | ① **`subscribe-cap-3-and-eviction-order`**（T8 终态）：拟订阅 N>3 个任务 → 活跃实例 **≤3**；**第 4 个不产生实例**；**按 `taskId` 稳定排序取前 3**，**重订阅前后淘汰顺序稳定**；② **`four-exits-monotonic-close`**（D5）：筛选/翻页/作业终态/卸载四出口各触发后，计数**单调下降**至 0；③ **`counter-matches-stub`**：暴露字段读数与真实 `EventSource` 桩件计数一致（无泄漏）；④ **`dedupe-same-taskid`**：同 `taskId` 重复订阅不产生重复实例 |
| **T4** | **dirty flag 收尾补发（三路径）** | `tests/task-events.spec.ts` + `tests/job-watch-channel.spec.ts` | ① **`dirty-marked-inflight`**（事件路径）：在途 `load()` 期间事件到达 → 本次被挡掉但**标记待办**；② **`dirty-flush-exactly-once`**：在途结束后**补发恰好 1 次**刷新（而非 0 次或 2 次）；③ **`dirty-merge-multiple`**：多次事件在途叠加 → 收尾仍只补发 1 次（**合并语义**）；④ **`dirty-visible-refreshnow-path`**（T2 终态）：**回可见 `refreshNow` 路径**撞在途守卫 → 同样补发恰好 1 次；⑤ **`dirty-jobdone-path`**（T2 终态）：**`JOB_DONE` 触发刷新路径**（`utils/job-watch.ts:71`）撞 `inFlight`（`:87-88`）→ 同样补发恰好 1 次 |
| **T5** | **job-watch 通道降级切换（D6）** | `tests/job-watch-channel.spec.ts` | ① **`realtime-switch-to-30s`**：`onopen` → `realtime` 时兜底间隔**切换为 30s 保底**（**不是清零**）：假时钟推进 30s **恰好 1 次**拉取；② **`onerror-back-to-3s`**：`onerror` 且 `readyState !== OPEN` → 回 `polling` 且 **3s 恢复**；③ **`old-timer-cleared-on-switch`**（GLM S3）：**间隔切换瞬间旧 timer 清除** —— **切换后首个 tick 距切换时刻 ≥3s**（3s→30s 切换不因旧 timer 残留多打一次）；④ **`authoritative-snapshot-twice`**：首帧与 `onopen` 后**各拉一次**权威快照（合计 **2 次**，不多不少）；⑤ **`final-disposes-both`**：`onFinal` 走 `dispose`：timer 与 source 同时释放；⑥ **`no-frame-freshness-threshold`**（D8 终态）：**不设帧新鲜度阈值** —— 任意长度的"无帧"**不得触发降级**，降级只由 `onerror`/`readyState` 驱动 |
| **T6** | **`waitForJobFinal` 超时显式化 + `AbortSignal`（D2 终态）** | `tests/wait-job-final.spec.ts` | ① **`abort-throws-recognizable`**：`signal.aborted` → 抛可识别错误（`api/jobs.ts:55-57` 语义保留）；② **`timeout-not-silent-return`**：超时分支**不再**返回"看起来正常"的作业对象（改可判别结果）；③ **`no-silent-publishconflict-clear`**：调用方拿到非终态时**不静默清空**发布冲突态；④ **`abort-no-unhandled-rejection`**（T7 终态）：abort 时**无 unhandled rejection**（覆盖 `stores/workspace.ts:452` 的 `void` 调用点）；⑤ **`timeout-notice-exactly-once`**（T7 终态）：**300s 超时提示恰好 1 次**（**禁止落 `onTick`**，`onTick` 每 1s 一次） |
| **T7** | **Import 侧同 kind 回收（D12 + T6 终态）** | `tests/import-watch-recycle.spec.ts` | ① **`same-kind-old-disposed`**：同 `kind` 连启 2 次 → 旧 `dispose` 已被调用（`dispose` 计数 **1 次**）；② **`inflight-resolve-not-written-back`**（T6 终态，**关键**）：**同 kind 回收后旧 job 在途 `getJob` resolve 不写回新 job 状态、不弹旧终态提示**（覆盖 `utils/job-watch.ts:92-97` 的 `disposed` 复检）；③ **`different-kind-independent`**：不同 `kind` 并存时互不回收（check/import/publish 各自独立）；④ **`unmount-collects-all`**：卸载时全部回收 |
| **T8** | **【新增】卸载出口与 close-gate（T5 终态）** | `tests/task-events-lifecycle.spec.ts` | ① **`unmount-closes-all-eventsources`**：**离开任务中心页后 `EventSource` 计数归 0**（`TaskCenterView.onBeforeUnmount` 显式调 store action 的跨层出口）；② **`late-frame-after-close-ignored`**：**close 后到达的帧不触发 `refreshNow`**（close-gate）；③ **`close-gate-covers-dispatch`**：close-gate 覆盖全部帧类型分派（`JOB_PROGRESS`/`JOB_DONE`/`TASK_CHANGED`/`HEARTBEAT`），非只挡其一 |

> 基线不得回退：新增 spec 后 `yarn test` 总数 **≥154**、spec 文件 **≥12**，且 **0 failed**（现态 **154/154 · 12 spec · exit 0**【实测】）。新增 5 个 spec 文件后预期 spec 总数 **17**、用例总数 **≥154 + 新增数**（施工时记录实数）。

---

## 4. 验收标准 / 门禁

### 4.0 通用门禁（每步都跑，先跑再看）

```bash
# 1) 类型 + 构建
cd <repo>/frontend && yarn typecheck      # vue-tsc --noEmit；基线 = exit 0 无输出【实测】
cd <repo>/frontend && yarn build          # package.json:8 = vue-tsc --noEmit && node scripts/build.mjs
# 2) 测试（D1 新增跑道）
cd <repo>/frontend && yarn test           # 基线 = 12 spec / 154 passed / exit 0【实测】；不得回退
# 3) 后端零改动
cd <repo> && git status --short backend/  # 期望无输出；基线 = 空【实测】
# 4) E2E 桩（若 CI 链路适用）—— 【不钉死基数】（K3 裁定，本稿保留）
#    门禁 = FAIL=0 且 GFSKIP=0 且 PASS 数 ≥ 施工前实测基线（基数随用例演进，施工第 0 步记录当时实测值）
#    口径注记（三方记载不一，一律以施工时实测为准）：批 3③a/③b 实测 28；docs/M2-排期计划.md:347 记 29（GF6 进主计数后）；
#    .github/workflows/ci.yml:108/212 按 29 命名【实测】
```

**DevTools 类判据留档要求（T13-3 / GLM S5，硬性）**：§4.1 / §4.2 / §4.3 中所有"请求数/连接数/时间轴"判据**须随施工报告附 Network 截图或 HAR 留档**（便于三方会审复核口径统一）。无留档的 DevTools 判据**视为未执行**。

### 4.1 步 1 验证

0. **第 0 步：测改动前基线（必做，先于任何代码改动）**（D14）——
   - 无在跑作业时 30s 内 `GET /api/tasks` 次数；
   - 有在跑作业时 30s 内 `GET /api/tasks` 次数；
   - `realtime` 通道下 `GET /api/jobs/{id}` 的 30s 频次。
   实测值**回填施工报告**；原卡 §5「7~8 次/30s」系【推断】，**不得沿用为事实**。
   **同时完成 keep-alive 复核（T5，一行 grep + 结论）**：
   ```bash
   cd <repo>/frontend/src && grep -rn "keep-alive\|keepAlive\|onBeforeRouteLeave\|beforeRouteLeave\|\$reset\|\$dispose\|import.meta.hot" .   # 期望 0 命中
   ```
   记录"路由切换必然触发 `TaskCenterView.onBeforeUnmount`"的确认结论；**若出现命中 ⇒ 停工上报（推翻条件 P2）**。
1. **静态**：`yarn typecheck` + `yarn build` 通过。
2. **全静止判据（核心）**：列表中无任何 RUNNING/PENDING 作业，DevTools → Network → 过滤 `tasks` → 清空 → 计时 30s。
   - 改动后应为 **≤1 次**（30s 保底 tick，**非 0 次** —— D10 终态口径）。
   - 基线值 = 第 0 步实测。**须按时间轴分段看**（区分 4s 与 30s 节奏）。
3. **有在跑作业判据**：启动一个导出作业并停留在任务中心，30s 内应恢复 4s 节奏（≈7~8 次，与第 0 步实测一致）。
4. **终态收敛判据**：作业跑完，列表在终态后**最多 1 个额外 tick** 内停止请求（落到 30s 保底节奏）。
5. **P2 回收判据**：导入向导连续两次点「预检查」；改动后稳态应为 **1 条** `/api/jobs/{id}` 流、EventStream 连接 **1 条**。
   **扩（T6）**：第二次点击后，**不得出现旧作业的终态提示**（如旧 `ElMessage`/旧进度覆盖新作业状态）。
6. **P3 判据**：AI 面板触发发布，作业进行中停止本轮 → `GET /api/jobs/{id}` 1s 轮询终止；超时不再静默（有明确提示，且**提示恰好 1 次**）；**abort 场景 Console 无 unhandled rejection**。
7. **D1 回归锁**：§3 的 **T1/T2/T6/T7** 全绿（T2 含新增 ⑧/⑨/⑩，T6 含新增 ④/⑤，T7 含新增 ②）。
8. **TDD 留证**：RED 阶段输出（断言失败原文）与 GREEN 阶段输出**均入施工报告**（§10-4）。

### 4.2 步 2 验证

1. **不可见 30s 判据（核心）**：任务中心 + 一个在跑作业；切走标签页/最小化 30s → `hidden` 区间内 `GET /api/tasks` 与 `GET /api/jobs/*` **恰好 0 次**。必须按时间轴**分段**看。
2. **回可见补刷判据**：切回瞬间（≤1s）每个轮询点应 **恰好 1 次** 请求，随后恢复原周期；不得"2 次/秒级连发"（R2-1/R2-2 反查）。
3. **错峰判据（T4 终态）**：多轮询点同时苏醒时，Network 时间轴上不出现同一毫秒的 2~3 条请求；**分散窗口 ≤250ms**（错峰作用于**补刷本身**）。
4. **AI 面板倒计时不回归**：制造一次 HITL 确认卡（含 `expiresAt`），隐藏 20s 后回来应**立即重算**（不显示已过期假倒计时）。
5. **RESET 与保底判据（D7 + T1）**：
   - `refreshNow()` 后紧跟的原周期触发**不发生**在同一时刻（不出现"补刷 + 原 tick"双发）；
   - 全静止持续 ≥60s：`GET /api/tasks` 应为 **2 次上下**（30s 保底节奏），**不出现 4s 节奏**，且**不出现同一 tick 双发**。
6. **D1 回归锁**：§3 的 **T2** 全绿（含 ①~⑩）。

### 4.3 步 3 验证

1. **事件驱动判据（核心）**：任务中心保持"全静止、30s 保底"态，用外部手段触发作业终态（`curl` 或另一标签页），观察本页 Network：作业中应有 `/api/tasks/{id}/events` EventStream 连接；完成瞬间应在事件到达后出现 **1 次** `GET /api/tasks`，此后回到保底节奏。
2. **`TASK_CHANGED` 判据（唯一直接证据）**：DevTools → Network → 该 EventStream → Messages 面板应能看到 `HEARTBEAT`/`TASK_CHANGED`/`JOB_PROGRESS`/`JOB_DONE` 四类帧；`TASK_CHANGED` 到达后应能观测到"接了就刷"。**注意**：通道无周期心跳（§0-7），Messages 里 `HEARTBEAT` **只有连接时 1 帧**【实测】，**不得据此判"通道假死"**。
3. **实例上限与回收判据（R3-1，D5 + T8）**：制造 5 个在跑任务 → EventStream 连接数 **≤3**；**第 4/5 个任务无 EventStream**（其进度靠 4s 列表条件轮询推进）；逐个翻页/筛选/终态**切换路由**后连接数**单调下降**至 0；**离开任务中心页后连接数归 0**（T5 跨层出口）。
   **反查（保留但不再作为上限值的主论据，T10）**：同时开 AI 面板触发一轮 chat，确认 AI reattach 通道未被挤掉。
4. **向导页降频判据（D6 + T9 口径）**：导出向导跑一个作业，`realtime` 正常时 30s 内 `GET /api/jobs/{id}` 应为 **0~1 次**（即原 3s 节奏**降为 30s 保底**，**非清零**；基线 = 第 0 步实测）；断网/恢复后降级 `polling`，3s 节奏应恢复。
   施工报告须在此节写明口径：「**降频 90%（3s→30s）而非归零**」（T9 终态）；多页签残余请求量化**建议附，不作门槛**。
5. **假死反查（R3-3）**：作业运行中用 DevTools 把该 EventStream **Block**（或 Kill），观察进度条：**不允许"永久停在旧百分比"** —— 30s 保底轮询应仍能拉到权威快照并**收敛终态**（Network 重新出现 `/api/jobs/{id}`，节奏 = 30s；若 `onerror` 已置 `polling` 则回到 3s）。本判据**不依赖任何帧新鲜度阈值**（D8：任务级通道无周期心跳，"无帧"是正常态，静默不做降级）。
6. **D1 回归锁**：§3 的 **T3/T4/T5/T8** 全绿。
7. **B1 判死链反查（T13-6 / DSPro P9，必做）**：制造一个由 **`JobWatchdog`**（而非执行器）判死/超时的作业（判死窗 90s + 扫描周期 10s，`application.yml:220-224`），确认其终态经 `JobTerminalWriter.java:86` 的 `JOB_DONE` 发出后**同样触发本页刷新** —— 把 §1.4 的【推断】转为【实测】并写进施工报告。

### 4.4 回归面（三步共同）

- 任务中心：手动刷新、分页、三种筛选、创建任务、取消作业、删除任务。
- 导入向导：预检查 / 导入 / 发布三路（含发布冲突文案与 issues 明细）。
- 导出向导：导出作业进度条、完成后结果表格懒加载。
- AI 面板：chat 一轮、HITL 确认、生成式表单提交与超时、`reattach`、`refresh_tasks` 类 ui_event。
- 裁剪性（ADR-10）：收起 AI 栏后五页业务照常（业务页不得依赖 AiPanel 挂载）。

---

## 5. 【不做清单】（红线，D16 全量 + 本期新增驳回项）

一期**明确不做**（越界即视为超范围）：

1. **后端零改动** —— 判据 = `git status --short backend/` 空（基线已空【实测】）。不改 `TaskSseService`（订阅表/写超时/摘除策略）、不改 `TaskController`、不改四个 Runner、不改 `JobTerminalWriter`/`JobService`、不改 `JobWatchdog`、不改 `application.yml`。
2. **不新增 SSE 事件类型** —— 不加"任务列表级"全局通道（如 `/api/tasks/events`）、不加 `JOB_DONE` 载荷字段、不加新事件名。一期只用现有 4 类（`types/sse.ts:337`）。
3. **不修后端 N+1** —— `TaskController#toSummary` 的每任务 2 次查询不动（二期；`docs/M2-排期计划.md:280` 明文：默认 `size=10` ⇒ 每 tick 1+10×2=21 次查询）；一期只从**前端请求频率**侧减负。
4. **不改 `api/sse.ts` 与 `stores/ai.ts` 计时器** —— AI 流通道看门狗/退避链（`api/sse.ts:552` 一带）与 AI store 的 `mirrorTimer`（`:62`/`:533-537`）、`formExpiryTimer`（`:52`/`:1299-1303`）一律不动。
5. **不改 `SpreadGrid` / `excel` / `frontend-tools` 定时器** —— `SpreadGrid.vue:107/137-140/86/176-183`、`excel.ts:847`、`frontend-tools.ts:383-408` 全部不动。
6. **`visibilitychange` 不扩展到 SSE 连接本身** —— 不主动关闭后台 `EventSource`；通道假死只在步 3 内按 **30s 保底轮询 + `onerror`/`readyState`** 处置（D6/D8：**不设帧新鲜度阈值**）。
7. **不重建 `publishConflict` 渲染位** —— 归 R-94 批（D2）。
8. **不碰 `run_job` 之外的其他 ui_event 死链** —— `refresh_defs`/`refresh_data`（`stores/workspace.ts:499-513`）维持挂账（D13）。
9. **不新增任何测试框架/依赖** —— 不新增运行时依赖、不改 `package.json` 依赖面（D1）。
10. **不改 `stores/task.ts` 之外的任务列表刷新调用点** —— `setPage`/`setSize`/`applyFilters`（`:131-154`）、`createTask`/`removeTask`/`startJob` 内部 `load()`、`refresh_tasks`（`workspace.ts:514-517`）、手动刷新（`TaskCenterView.vue:12`）保持无条件事件驱动。
11. **【本期新增·驳回登记 T11】不做 `realtime`/`polling` 切换的滞回/debounce** —— 切换仅在 `channel` 状态迁移时发生（`utils/job-watch.ts:76-84`），频率低；滞回引入额外状态机复杂度不值（决策卡 T11 驳回）。留痕于本清单与 §7-E9。
12. **【本期新增·驳回登记 T12】不做 `visibilitychange` 的 module 级单例广播** —— 稳态监听器 ≤5 个（task 1 + 每 job watcher 1（最多 3 kind）+ AiPanel 1），线性放大面可忽略；module 级广播引入全局态与订阅管理复杂度（决策卡 T12 驳回）。留痕于本清单与 §7-E10。
13. **【本期新增·二期登记 T14】不做保底轮询的失败退避/放弃策略** —— `utils/job-watch.ts:99-100` 的 `onError` 仅透传为**现状既有**、非一期引入；泄漏时长已被终态/卸载/`max-execution-time` 三重限界；一期已把失败频率降 90%（3s→30s）。登记为**二期观察项**（§7-E8）。

---

## 6. K3 立场 D1~D16 · 定稿终态（会审修订后）

> **本节 = 定稿终态表**（对应《决策卡-轮询一期》§2）。**实体不丢档**：草案原文的引用与差异见《决策卡》§2 的「原草案立场」列；本稿只承载**终态**。标 **⚠️ 修订** = 被 T1~T15 条件修订的条目。

**D1.** §6.6「不引入测试框架」作废改写：本批可用既有 vitest 锁回归（TDD 口径：核心纯函数先 RED 后 GREEN），禁新增任何框架/依赖（不动 `package.json` 依赖面）。
- 承载判读：轨道已就绪（`package.json:6-12` 六条含 `test`/`test:watch`、`vitest.config.ts`、**12 spec / 154 用例 / exit 0**【实测】）；落点见 §3（**T1~T8**，`frontend/tests/` 扁平结构）。
- **补（T13-1）**：§3-T2⑦ 补 start/stop 幂等断言（对齐 `stores/task.ts:158-161`）。
- **补（T13-2）**：§3 写法口径的桩件注记（`EventSource` 挂 `globalThis`、`document.hidden` 用 `Object.defineProperty` 覆写）。

**D2.（⚠️ 修订）** R-94 重叠面收窄：步 1 的 `watchPublishOutcome` AbortSignal 透传 + `waitForJobFinal` 超时不再静默**保留在一期**；**不重建 `publishConflict` 渲染位**（归 R-94 批）；施工报告中须给 **R-94 行尾注记建议**。
- 定点 = `stores/task.ts:294-321`（`:298` 补 `signal`；`:308-310` 改显式提示）；`api/jobs.ts:63-65` 同批改。
- **修订（T7）**：① **显式捕获 abort Error**（消除 `stores/workspace.ts:452` `void` 调用下的 unhandled rejection）；② 超时/中止提示落**单次出口**（超时 1 次 / abort 1 次），**禁放 `onTick`**。
- R-94 行 = `docs/M2-排期计划.md:286`（已正式立项）⇒ 施工报告须建议在 `:286` 行尾追加注记：本批已收「AbortSignal 透传 + 超时显式化 + abort 捕获」，`publishConflict` 渲染位仍待该批。

**D3.（⚠️ 修订）** 步 2 新文件定名 `frontend/src/utils/polling.ts`，导出 **`createPollingController({ intervalMs, fallbackIntervalMs, run, shouldRun }) → { start, stop, refreshNow, isPaused }`**；hidden 停表（不取消在途）、visible **先按 0~250ms 错峰延时补刷一次**再重置节奏（不补偿错过的 tick）。
- 承载判读：该路径**现不存在**【实测】；接口四成员 + `fallbackIntervalMs` 即 §3-T2 的锁定面。
- **修订（T1/T2/T4）**：① 接口扩展 `fallbackIntervalMs`（保底 tick 唯一承载）；② 错峰**作用于补刷本身**；③ `refreshNow` × 守卫短路口径（dirty flag 收尾补发）**写进 controller 注释**。

**D4.（⚠️ 修订）** 步 3 订阅落点：新增 `frontend/src/utils/task-events.ts` 管 `EventSource` 生命周期（上限/回收/降级），订阅状态字段挂 `stores/task.ts`。
- 承载判读：该路径**现不存在**【实测】；`api/tasks.ts:105-115#openTaskEvents` 的现有唯一调用方 `utils/job-watch.ts:57` 不在本次迁移面内（保持原样，仅补 `onerror`）。
- **修订（T5）**：① **卸载出口 = `TaskCenterView.onBeforeUnmount:354-356` 显式调 store action 关闭全部 `EventSource`**（state 在 store 跨路由存活）；② `onFrame` 派发补 **close-gate**（close 后迟到帧丢弃、不触发 `refreshNow`）。
- 前提已解：全仓 keep-alive 等 **0 命中**【实测 V6】⇒ 无需 `onActivated/onDeactivated`。

**D5.（⚠️ 修订）** `EventSource` 实例上限 = **3**（与当前页在跑任务数取小者）；筛选/翻页/作业终态/卸载**四出口**同步 `close()`；订阅数暴露为 **store 可观测字段**。
- 承载判读：四出口锚点 = `task.ts:131-154`（筛选/翻页入口）、`:170-176`（停表）、`TaskCenterView.vue:354-356`（卸载）；作业终态出口 = 事件侧 `JOB_DONE`/`onFinal` 路径。
- **修订（T8）**：**淘汰策略钉死** —— 上限内按 **`taskId` 稳定排序取前 3**；**第 4+ 个在跑任务不订阅 SSE**，靠列表条件轮询兜底。
- **修订（T10，论据改写）**：上限 3 的论据 = 「**防御性上限 + 可观测计数**」；**删除**"同域 6 连接保护 AI 通道"**主论据**（实测：AI 通道对话走 fetch POST 非 `EventSource`，**只有 reattach 用 `EventSource` 且单次调用内 ≤1 条**（`api/sse.ts:549-556`），正常用法下 6 连接上限**不可达**）；明写：**真实放大源是 `job-watch` 漏 `dispose`（R-14 面）**，**该源已由 D12＋T6 治理**；上限 3 只约束 `task-events` 池、**对 `job-watch` 池无效**。
- `TaskSseService` 全路径（T15-5）：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`。

**D6.** job-watch 兜底：`channel==='realtime'` 时**不停表清零、降为 30s 保底间隔**（与 D10 的 30s 保底语义统一）；`'polling'` 降级恢复 3s；首帧与 `onopen` 后各拉一次权威快照；`onProgress` 不改。
- 裁定理由（上一轮 K3 采纳）：任务级通道**无周期心跳**（`TaskSseService.java:88` 仅订阅首帧；`app.task.sse` **`:191-203`** 段内无 heartbeat 键，键在 `:202`/`:203`）⇒ 原案"停表"会使 R3-3 假死（连接活着但不再发帧、不触发 `onerror`）**失去任何发现面**。
- 承载判读：定点 = `job-watch.ts:51-55`（`emitChannel`）、`:76-84`（onopen/onerror）、`:106-110`（setInterval）、`:121`（首拉）⇒ 施工改为**间隔切换**（realtime 30s ⇄ polling 3s）而非启停。锁回归 = §3-T5；验收 = §4.3-4。
- **补注（T3/T15-1）**：本条的 `application.yml` 锚点一律写 **`:191-203`**（草案 `:191-198` 为注释头）。

**D7.** `refreshNow` 重置 interval 起点（防抖语义），**写进注释与验证判据**。
- 承载判读：判据 = §4.2-5；锁回归 = §3-T2④。
- **补（T2）**：其被在途守卫挡掉时"重置也丢失"的口径由 **D9 的 dirty flag 统一覆盖**。

**D8.** 【实测】任务级通道**无周期心跳，T 不存在** ⇒ **不设帧新鲜度阈值**；假死发现依赖 **30s 兜底轮询 + `EventSource` `onerror`/`readyState`** 判通道态。R3-3 假死反查的验收判据 = 「**Block 通道后 30s 保底轮询仍能收敛终态，进度条不得永久停在旧百分比**」。
- 裁定理由（上一轮 K3 采纳）：全后端（main 源码）字面 `"HEARTBEAT"` **仅 1 处**（`TaskSseService.java:88`，订阅时补发首帧）【实测 V5】；其余命中全属 **AI 通道**（`ConfirmGate.java:77` = 2s / `ResilientChatService.java:96` = 5s）；`app.task.sse`（**`:191-203`**）只有 `write-timeout: 15s`（**`:202`**）/ `emitter-timeout: 5m`（**`:203`**）⇒ 原案的 `max(2T,10s)` 在任务级通道上**无值可代**；且"距最近任一类型帧"在空转任务上**无界**。**补注（T15-2）**：键值具体行已按本表写死（`:202`/`:203`）。
- 承载判读：降级触发面收敛为 `onerror` + `readyState`（`job-watch.ts:79-84`）；保底面 = D6 的 30s 保底轮询。验收 = §4.3-5；锁回归 = §3-T5⑥。
- **第 0 步观测口径（保留，转为留证）**：DevTools → Network → EventStream → Messages，在**空转任务**上连续观测 ≥120s，记录帧到达时刻与类型；预期 = "仅连接期 1 帧 `HEARTBEAT` + 稀疏业务帧（无周期）"。本项作**观察留证**写入施工报告，**不再作为阈值输入**。

**D9.（⚠️ 修订·扩口径）** 事件路径用 **dirty flag + 收尾补发**（R3-4 建议案采纳）。
- 承载判读：`load()` 的 `loading` 短路在 `task.ts:114-116`（挡掉不重试）⇒ 必须补发而非丢弃；锁回归 = §3-T4。
- **修订（T2 终态）**：语义**统一覆盖三路径** —— ① SSE 事件帧路径；② 回可见 `refreshNow` 路径；③ **`JOB_DONE` 触发刷新路径**（`utils/job-watch.ts:71` 的 `void syncSnapshot()` 与 tick **共用 `inFlight`**，`job-watch.ts:87-88`）；语义**写进 controller 注释**。

**D10.（⚠️ 修订）** 停表谓词：`hasActiveJob` 以**列表行（筛选后）**为准，纳入 `filterStatus` 口径；另补 **30s 长周期保底 tick**（跨页签/他端变更可见性，R1-1）。
- 承载判读：谓词锚点 = `types/task.ts:112-119`（行字段）、`task.ts:68`（`filterStatus`）；锁回归 = §3-T1③。
- **修订（T1 终态）**：保底 tick 机制**钉死 = 单 interval 动态切换**（**不采双 controller 实例**），接口承载 = D3 的 `fallbackIntervalMs`；新增三条断言落 §3-T2⑧/⑨ 与 §3-T5③。
- 与 §4.1-2 判据的耦合：D10 生效后"全静止 30s = 0 次"变为"**≤1 次**"（保底 tick），**验收数字按此口径写死**。

**D11.（⚠️ 修订）** 回可见苏醒**错峰 0~250ms 随机延时**（R2-1）。
- 承载判读：锁回归 = §3-T2⑤；判据 = §4.2-3。
- **修订（T4 终态）**：错峰**作用于回可见补刷本身**（**非仅 interval 重置**）；§3-T2⑤ 与 §3-T2② **统一为同一时钟口径**（假时钟推进 ≤250ms 窗口内观测恰好 1 次 `run`）。

**D12.（⚠️ 修订·强化）** N-1 一期顺手收：`beginWatchJob` 按 `kind` 回收旧 watcher（`Map<kind,dispose>`），Export 侧 `:489-495` 为蓝本。
- 承载判读：Import 侧锚点 = `:306`（`stopWatchers` 声明）、`:840-888`（`beginWatchJob`）、`:887`（push 点）；Export 蓝本 = `:234`（`stopWatchJob` 声明）、`:724`（重开先停）、`:489-495`（改绑回收）；锁回归 = §3-T7。
- **修订（T6 终态，必做）**：**仅"按 kind 回收"不足** —— `utils/job-watch.ts#syncSnapshot` 必须在 **`await jobsApi.getJob`（`:92`）之后补 `disposed` 复检**，使 dispose 后的在途快照**不写回 `onSnapshot`（`:93`）、不触发 `onFinal`（`:97`）**；§3-T7② 扩断言锁此语义。

**D13.** N-2（`refresh_defs`/`refresh_data` 空转）**不并入本批**，维持挂账。
- 承载判读：锚点 = `stores/workspace.ts:499-506`（`refresh_defs`）、`:507-513`（`refresh_data`）【亲验】；两者已由 W-09 改为**如实陈述**（"当前没有任何页面订阅该事件并重新拉取数据"）⇒ 不再"说谎"，但机制仍空转。排除项见 §7-E1。

**D14.（+T5）** 施工第 0 步 = **测改动前基线**（原卡数字全为推断）：无在跑时 30s 请求数、有在跑时 30s 请求数、`realtime` 下 jobs 轮询频次，**实测值回填施工报告**。
- 承载判读：已列为 §4.1-0；原卡 §5 的"7~8 次/30s（推断）"在本卡中**一律降级为待测**。
- 静态可判据（与运行时基线互补）：`setInterval` 3 处 / `EventSource` 2 处 / `visibilitychange` 0 处 / keep-alive 族 0 处【实测】。
- **补（T5）**：第 0 步**同时复核引用 keep-alive 实测**（一行 grep + 结论），不重复调查。

**D15.** 文案同步：`views/TaskCenterView.vue:8` 改条件口径；同文件 `:151` 注释「4s 自刷：store 的 setInterval，tick 前查 loading 防重叠（deepseek 蓝本形态）」须一并改实（否则注释与实现再次脱节）。
- 承载判读：**若采 D10 保底 tick（本稿已采），文案须同时交代保底口径**，例如「有进行中作业时每 4 秒自动刷新；其余情况每 30 秒兜底刷新一次」。

**D16.** 一期红线不变：后端零改动（`git status backend/` 空为判据）、不新增 SSE 事件类型、不修 N+1、不改 `api/sse.ts` 与 `stores/ai.ts` 计时器、不改 `SpreadGrid`/`excel`/`frontend-tools` 定时器、`visibilitychange` 不扩展到 SSE 连接本身。
- 承载判读：已全量展开为 §5（**13 条**：10 条原红线 + T11/T12/T14 三条新增登记），逐条带锚点与判据。
- **补注（T3/T15）**：§5-6 的 `application.yml` 锚点按 **`:191-203`** 订正；§5-3 的"不修 N+1"理由锚点 = `docs/M2-排期计划.md:280`。

---

## 7. 排除项（E 节）：不并入本批

| 编号 | 项 | 处置 | 理由 |
| --- | --- | --- | --- |
| **E1** | **N-2**：`refresh_defs` / `refresh_data` 空转（`stores/workspace.ts:499-513`；`workspace_changed` 唯一订阅者仍是 AI 面板 `stores/ai.ts:550-571`） | **不并入**（维持挂账） | ① 属 M2-T4 / 契约联动面（W-09），已由 `workspace.ts:500-512` 注释改为**如实陈述** ⇒ 一期不存在"用户被误导"的紧急面；② 与步 3 虽同机制，但业务页订阅总线会牵动 `DefinitionsView`/`DataBrowserView` 两页的加载语义（含筛选/分页/数据版本），**面远大于一期三步**；③ K3 D13 明示不并入 |
| **E2** | **N-5**：`JOB_DONE` 载荷粒度不足（无 `jobType`，列表页无法"只刷对应行"；`types/sse.ts:350-353` 仅 `{jobId,status}`） | **不并入**（一期统一走整页刷新） | 补字段 = 后端契约变更，撞 D16 红线；一期步 3 一律 `refreshNow()` 刷整页，载荷粒度问题不影响一期正确性 |
| **E3** | **N-3**：`api/tasks.ts#openTaskEvents` 是"无守卫裸封装"（`:105-115`，无 `onerror`/无最大时长） | **部分并入** | 一期只**补 `onerror` 回调 + 可选最大时长**（步 3-3，纯前端）；**不**引入后端侧通道寿命改造（`emitter-timeout: 5m` 已在 `application.yml:203`，不动） |
| **E4** | **N-4**：文档锚点漂移（`docs/evidence/W3-combined任务中心分页核验.md:58` 的 `TaskListView.vue:151-155` / `export/StepExport.vue` 在现树不存在；`docs/requirements.md:58` 仍写「列表 5 秒自动刷新」但已带 2026-10-10 注记纠正为 4s【实测】） | **不并入改动，但同批补登记** | 判据 = 现树实证：任务中心是 `views/TaskCenterView.vue`（无定时器）+ `stores/task.ts:162` 4s【实测】。一期改动 `stores/task.ts` 会再次推动行号漂移 ⇒ **施工报告须同批给出 R-88/R-89 行内锚点更新建议**（R-88 行现写 `startAutoRefresh:182-199`，实态 `:157-168`；R-89 行现写 `ImportWizardView.vue:748-795`，实态 `:840-888`）【实测·漂移确认】 |
| **E5** | **R-94 死码族**：`refreshCurrent` 恒空转（`stores/task.ts:179-199`；`currentTaskId` 无非空写点，仅 `:77` 声明 / `:180` 读 / `:202` 写 null / `:225` 比较）+ `publishConflict` 唯一渲染点消失（`docs/M2-排期计划.md:286` 正式立项） | **收窄后部分并入**（见 D2） | 一期只收 `watchPublishOutcome` 的 AbortSignal + 超时显式化 + abort 捕获（`:294-321`）；**死码族清除与渲染位重建归 R-94 批** |
| **E6** | **`refreshCurrent` 的其余调用方条件化** | **不做** | `stores/workspace.ts` 的三处调用点（`:399`、`:413`、**`:449`**）**不得**被 `hasActiveJob` 拦住 —— 它们是明确的事件驱动路径（原卡 R1-4 反查点，仍成立）；另 `stores/task.ts` 内部调用点 = `:239`/`:251`/`:264`/`:307`【实测】 |
| **E7** | **`utils/job-watch.ts#pollIntervalMs` 两个调用点覆盖参数** | **不做** | 一期用 `channel` 判据控制**间隔切换**，不引入"每调用点自定义间隔"的第二套口径 |
| **E8** | **【T14 二期登记】** 保底轮询失败无退避/无放弃（`utils/job-watch.ts:99-100` 的 `onError` 仅透传；断网期间 3s/30s 持续打失败请求） | **登记二期观察项，本批不动** | ① **现状既有、非一期引入**（一期只把 3s 降为 30s，失败频率**下降 90%**）；② 已被三重限界：终态 `dispose()`（`:96`）、卸载 `dispose()`（`ExportWizardView.vue:1355-1357` / `ImportWizardView.vue:1114-1118`）、后端 `max-execution-time: 30m`（`application.yml:224`）；③ 退避需新增状态（连续失败计数 + 退避曲线 + 恢复判据），与 T11/T12 同源的复杂度考量；④ 一期目标（请求总量降频）已由 D6＋D10 达成 |
| **E9** | **【T11 驳回】** `realtime`/`polling` 切换的**滞回/debounce** | **驳回**（留痕） | ① **最小面约束**：滞回需额外状态机（抖动窗口 + 迟滞阈值 + 与 `refreshNow` 重置语义的交互）；② **切换频率本就低**：3s↔30s 只在 `channel` **状态迁移**时发生（`utils/job-watch.ts:76-84`），非每 tick 判定【推断】；③ 最坏情形仅"多切几次"，每次只改一个间隔常量，无资源泄漏路径【推断】。若实测出现频繁切换，作二期观察项再评估 |
| **E10** | **【T12 驳回】** `createPollingController` 的 `visibilitychange` 收敛为 **module 级单例广播** | **驳回**（留痕） | ① 稳态实例数小：`stores/task.ts` 1 个（模块级单例 timer `:58`）＋ 每 job watcher 1 个（`utils/job-watch.ts:106`；一期已由 D12+T6 治同 kind 累积；Import 侧最多 3 种 kind 并存）＋ AiPanel 1 个 ⇒ **≤5 个监听器**，放大面可忽略【推断】；② module 级广播引入全局态 + 订阅表管理（注册、卸载竞态、广播顺序），与 T11 同源的复杂度；③ 每实例自挂监听器语义更局部、controller 单测无需构造 module 单例 |

---

## 8. 风险与反查点

### 步 1 风险

- **R1-1 轮询停了但事件漏了 → 数据不新鲜（最重）**：停表谓词只覆盖"我能看到的行"。跨页签/他端启动作业时本页会静止。**→ D10**（30s 长周期保底 tick）。反查：若单独提交步 1 而无步 3，须在施工记录里写明中间态不可见面。
- **R1-2 筛选/分页与"在跑作业"错位**：有在跑作业时切到 `status=已完成` 或翻页 ⇒ 显示行无 RUNNING ⇒ 立即停表，真实世界仍在变。**→ D10**（谓词纳入 `filterStatus` 口径）。反查：谓词必须取**筛选后口径**，锁回归 §3-T1③。
- **R1-3 任务完成瞬间"停表 vs 最后一次拉取"竞态**：判定必须基于**刚刚写入的 `items`**（同 tick 内先 `await load()` 后判），不得用 render 前的陈旧快照。反查：断言"终态后最多 1 个额外 tick"（§4.1-4）。
- **R1-4 P1 与 P3 同文件改动冲突**：`stores/task.ts` 同时被 P1（tick 条件化）与 P3（`waitForJobFinal` 语义）修改。反查：`refreshCurrent` / `load` 的所有既有调用点不受条件化影响 —— `load()` 调用点见 §5-10 清单；**`refreshCurrent` 调用点 = `stores/workspace.ts:449`（直调）、`:463`（经 `cancelJob` 间接）、`:399`/`:413`，以及 `task.ts:239`/`:251`/`:264`/`:307`**【实测·T13-4 口径订正】。**→ D2 + E6**
- **【本轮新增】R1-5 D12 回收挡不住在途写回**：按 `kind` 回收只清 timer + source；已发出的 `getJob` resolve 后仍会写 `onSnapshot` 并触发 `onFinal`，而 Import 的 `onFinal` 读**当前** `taskId` ⇒ 旧终态覆盖新作业。**→ T6（D12 强化）**。反查：§3-T7② + §4.1-5。

### 步 2 风险

- **R2-1 回可见"补一次"与在途轮询叠加打爆接口**：醒齐射（列表 1 + 作业 N）。**→ D11 终态**（0~250ms 错峰**作用于补刷本身**）。反查：`refreshNow()` 必须复用各点既有守卫（`loading` `task.ts:114-116` / `inFlight` `job-watch.ts:87-88`）。
- **R2-2 补刷打乱停表判据 / 双发**：**→ D7**（`refreshNow` 重置 interval 起点，写进注释与判据）。反查：§4.2-5。
- **R2-3 后台节流与"停表"语义混淆**：浏览器对后台 `setInterval` 本就节流 ⇒ 判据取"**恰好 0**"，且必须在 DevTools Network 计数，不看 `console.log`。反查：§4.2-1 按时间轴分段。
- **R2-4 `hidden` 只停定时器，不停 `EventSource`**：**→ D16**（`visibilitychange` 不扩展到连接本身）。反查：口径写清"停表 = 停定时器"，避免评审误以为"隐藏即零流量"。
- **【本轮新增】R2-5 保底 tick 与条件 tick 双发**：单 interval 动态切换若清理不彻底，切换瞬间可能同 tick 两次 `run`。**→ T1 终态②/③**。反查：§3-T2⑨ + §3-T5③ + §4.2-5。

### 步 3 风险

- **R3-1 `EventSource` 泄漏/累积（R-14 同面复发）**：翻页/筛选/终态/卸载四出口任一漏回收即累积连接。**→ D5 终态**（上限 3 + `taskId` 稳定排序淘汰 + 四出口回收 + 跨层卸载出口 + 可观测计数）。反查：§4.3-3 + 锁回归 §3-T3/T8。
- **R3-2 `JOB_DONE` 到达但列表刷新拿到旧数据**：`JOB_DONE` 在数据面事务提交后发布（`JobTerminalWriter.java:86`），但列表 `toSummary` 读的是 `task`/`jobs` 表；若任务态联动与作业终态写库之间有时序窗口，事件到达即刷可能读到"作业已终态、任务仍 ACTIVE"的中间态 ⇒ 下一 tick 判为还在跑，**降不到 30s 保底（但也不会停不下来）**。反查：终态事件后对本行做一次**即时收敛**或安排一次短延后补拉；判据 = "终态后 5s 内落到保底节奏"（§4.3-1）。
- **R3-3 `realtime` 时代的通道静默假死**：`EventSource` 在"连接活着但服务端不再发帧"时不触发 `onerror`。**加重事实**：任务级通道**本就没有周期心跳**（`TaskSseService.java:88`【实测 V5】）⇒ "长时间无帧"是**正常态**，无法用"帧间新鲜度"区分"正常空转"与"假死"。**→ D6 + D8**：处置 = `realtime` **降为 30s 保底而非清零**、**不设帧新鲜度阈值**、降级只由 `onerror`/`readyState` 触发。反查：§4.3-5（Block 该 EventStream 后 30s 保底仍须**收敛终态**，进度条不得永久停在旧百分比）。
- **R3-4 事件 / 条件 tick / 回可见补刷三路叠加**：`load()` 的 `loading` 短路挡掉的那次**不会自动重试**。**→ D9 终态**（dirty flag + 收尾补发，**覆盖三路径**）。反查：锁回归 §3-T4（含 ④/⑤）。
- **【本轮新增】R3-5 close 后迟到帧触发无效刷新**：`close()` 后 `onmessage` 仍可能投递已入队的帧。**→ T5 终态 close-gate**。反查：§3-T8②。
- **【本轮新增】R3-6 真放大源未治的误判风险**：把"上限 3"当作治住了 R-14 的泄漏。**→ T10 论据改写**（明写上限 3 对 `job-watch` 池无效，泄漏由 D12+T6 治）。反查：§4.1-5（P2 回收判据）。

**反查点总表**：R1-1→D10｜R1-2→D10｜R1-3→§4.1-4｜R1-4→D2/E6｜**R1-5→T6/§3-T7②**｜R2-1→D11｜R2-2→D7｜R2-3→§4.2-1｜R2-4→D16｜**R2-5→T1/§3-T2⑨**｜R3-1→D5/§3-T8｜R3-2→§4.3-1｜R3-3→D6/D8｜R3-4→D9/§3-T4｜**R3-5→T5/§3-T8②**｜**R3-6→T10**。

---

## 9. 锚点抽检证据清单（本稿执笔时逐处亲验）

判据 = **本稿引用值** vs **HEAD `6c9c699` 实读值**。全部【实测】。

| # | 锚点 | 实测 | 判定 |
| --- | --- | --- | --- |
| A1 | `fe/src/stores/task.ts`（369 行） | `:32` = `export const TASK_AUTO_REFRESH_MS = 4000`；`:58` = `let autoRefreshTimer: ReturnType<typeof setInterval> \| null = null`；`:68` = `filterStatus: null as TaskStatus \| null,`；`:114-116` = `if (this.loading) { return }`；`:117` = `this.loading = true`；`:127` = `this.loading = false`；`:131-154` = `setPage`/`setSize`/`applyFilters`；`:155` = JSDoc「开启 4s 自刷…」；`:157` = `startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {`；`:158-161` = `if (autoRefreshTimer !== null) { this.autoRefresh = true; return }`；`:162` = `autoRefreshTimer = setInterval(() => {`；`:163-165` = `if (!this.loading) { void this.load() }`；`:168` = `},`；`:170-176` = `stopAutoRefresh` 全体；`:179-199` = `refreshCurrent`（`:180` 读 `currentTaskId`、`:181-183` 早退）；`:294` = `async watchPublishOutcome(jobId: number): Promise<void> {`；`:295-297` = `if (!jobId) return`；`:298` = `const job = await waitForJobFinal(jobId, {`；`:308-310` = `if (job.status !== 'FAILED') { this.publishConflict = null; return }`；`:321` = `},` | **一致** |
| A2 | `fe/src/utils/job-watch.ts`（123 行，全文通读） | `:21` = `export const JOB_FALLBACK_POLL_MS = 3000`；`:46` = `export function watchJob(options: JobWatchOptions): () => void {`；`:47` = `const pollIntervalMs = options.pollIntervalMs ?? JOB_FALLBACK_POLL_MS`；`:48-49` = `disposed`/`inFlight` 声明；`:51-55` = `emitChannel`；`:52-54` = `if (!disposed) options.onChannel?.(state)`；`:57` = `const source = tasksApi.openTaskEvents(options.taskId, (frame…`；`:58-60` = `if (disposed) return`；`:61-67` = `JOB_PROGRESS` 分支；`:68-73` = `JOB_DONE` 分支（`:71` = `void syncSnapshot()`）；`:76-78` = `onopen → emitChannel('realtime')`；`:79-84` = `onerror`（`:81` = `if (source.readyState !== EventSource.OPEN)`）；`:86-104` = `syncSnapshot`（`:87-89` = `if (disposed \|\| inFlight) return`；`:92` = `await jobsApi.getJob(options.jobId)`；**`:93` = `options.onSnapshot?.(job)`（前无 disposed 复检）**；`:94-98` = 终态→`dispose()`（`:96`）→`onFinal`（`:97`）；`:99-100` = `onError` 仅透传；`:101-103` = `finally { inFlight = false }`）；`:106-110` = `const timer = setInterval(() => { if (!disposed) { void syncSnapshot() } }, pollIntervalMs)`；`:112-119` = `dispose`（`:117` `clearInterval`、`:118` `source.close()`）；`:121` = `void syncSnapshot()` | **一致**（+ T6 缺口坐实） |
| A3 | `fe/src/api/jobs.ts` | `:46` = `signal?: AbortSignal`；`:50` = `export async function waitForJobFinal(jobId: number, options: WaitJobOptions = {}): Promise<Job> {`；`:52` = `const timeoutMs = options.timeoutMs ?? 300_000`；`:54` = `for (;;) {`；`:55-57` = `if (options.signal?.aborted) { throw new Error('等待作业终态已被取消') }`；`:58` = `const job = await getJob(jobId)`；`:59` = `options.onTick?.(job)`；`:63-65` = `if (Date.now() - startedAt > timeoutMs) { return job }`；`:66` = `await new Promise<void>((resolve) => setTimeout(resolve, intervalMs))`；`:68` = `}`；`:20-22` = `getJob` | **一致** |
| A4 | `fe/src/api/tasks.ts` | **`:103` = 注释「按规格走 **4s 轮询**」（与 `job-watch.ts:21` 的 3000ms 不对齐 ⇒ T13-5 落点）**；`:105` = `export function openTaskEvents(taskId: number, onFrame: (frame: TaskEventFrame) => void): EventSource {`；`:106` = `const source = new EventSource(\`/api/tasks/${taskId}/events\`)`；`:107-113` = 仅 `source.onmessage` + try/catch；`:114` = `return source` ⇒ **无 `onerror`**；`:10-12` = `listTasks` | **一致** |
| A5 | `fe/src/stores/workspace.ts` | `:285` = `export function onWorkspaceEvent(topic: WorkspaceTopic, subscriber: Subscriber): () => void {`；`:442` = `case 'run_job': {`；`:448` = `const job = await jobsApi.createJob(event.taskId, event.jobType)`；**`:449` = `await taskStore.refreshCurrent()`（直调）**；`:450-454` = `if (event.jobType === 'PUBLISH') { … void taskStore.watchPublishOutcome(job.id ?? 0) … }`（**`:452` = `void` 调用点**）；`:456` = `}`；`:463` = `const outcome = await taskStore.cancelJob(event.jobId)`（**`refreshCurrent` 为间接链**）；`:499-506` = `refresh_defs`；`:507-513` = `refresh_data`；`:514-517` = `refresh_tasks`（`:515` `await taskStore.load()`） | **一致**（T13-4 口径确认） |
| A6 | `fe/src/views/TaskCenterView.vue`（357 行） | `:8` = `<span class="ml-1">· 列表每 4 秒自动刷新{{ lastLoadedText }}</span>`；`:12` = 手动刷新 `@click="taskStore.load()"`；`:151` = `* - 4s 自刷：store 的 setInterval，tick 前查 loading 防重叠（deepseek 蓝本形态）；`；`:348-352` = `onMounted`（`:351` = `taskStore.startAutoRefresh()`）；`:354-356` = `onBeforeUnmount`（`:355` = `taskStore.stopAutoRefresh()`） | **一致** |
| A7 | `fe/src/views/ImportWizardView.vue` | `:306` = `let stopWatchers: Array<() => void> = []`；`:504` = `async function attachJob(kind, job)`（`:512-519`，`:516` = `beginWatchJob(kind, job.id)`）；`:806` = `async function startJob(kind: 'check' \| 'import' \| 'publish'): Promise<void> {`（`:818` = `beginWatchJob(kind, job.id)`）；`:840` = `function beginWatchJob(kind: 'check' \| 'import' \| 'publish', jobId: number): void {`；`:859-885` = `onFinal`（`:861` `refreshIssues`、`:866/868/870` 三条 `ElMessage`、`:873` `refreshTaskSnapshot`）；**`:887` = `stopWatchers.push(dispose)`（前无同 kind 回收）**；`:1114-1116` = 卸载回收 | **一致** |
| A8 | `fe/src/views/ExportWizardView.vue` | `:234` = `let stopWatchJob: (() => void) \| null = null`；`:489-495` = `stopJobWatchForTaskSwitch()`（`:490` `stopWatchJob?.()`、`:491` 置 null）；`:698` = `async function startExport(): Promise<void> {`（`:710` createJob、**`:715` = `beginWatchJob(job.id ?? 0)`**）；`:723` = `function beginWatchJob(jobId: number): void {`（`:724` = `stopWatchJob?.()`）；`:1355-1357` = 卸载 | **一致** |
| A9 | `fe/src/components/AiPanel/AiPanel.vue` | `:452` = `const now = ref(Date.now())`；`:453` = `const confirmDeadline = ref<number \| null>(null)`；`:454` = `let tickTimer: ReturnType<typeof setInterval> \| null = null`；`:839-841` = `tickTimer = setInterval(() => { now.value = Date.now() }, 1000)`；`:850-853` = `if (tickTimer !== null) { clearInterval(tickTimer); tickTimer = null }` | **一致** |
| A10 | `fe/src/types/job.ts` / `types/sse.ts` / `types/task.ts` | `job.ts:12` = `export type JobStatus = 'PENDING' \| 'RUNNING' \| 'COMPLETED' \| 'FAILED' \| 'CANCELLED'`；`sse.ts:337` = `export type TaskEventType = 'HEARTBEAT' \| 'TASK_CHANGED' \| 'JOB_PROGRESS' \| 'JOB_DONE'`；`sse.ts:340-347` = `TaskChangedPayload` 五字段；`sse.ts:350-353` = `JobDonePayload` 仅 `{jobId,status}`；`task.ts:112-119` = `TaskSummary`（含 `latestJob: Job \| null`） | **一致** |
| A11 | `fe/src/api/sse.ts` | `:549` = `const open = (): void => {`；`:552` = `source = new EventSource(SSE_PATHS.events(runId, since ?? undefined))`；`:555` = `startConnect()`；`:481-494` = 帧间 idle 看门狗（`:485` `idleTimer = setTimeout(...)`、`:490` `source?.close()`） | **一致** |
| A12 | `fe/src/utils/frontend-tools.ts` / `components/SpreadGrid.vue` / `utils/excel.ts` / `stores/ai.ts` | `frontend-tools.ts:383` = `async function runWithBudget(`，块止 **`:408`**（`:410` 起下一段注释）；`SpreadGrid.vue:107` = `let readyTimer: ReturnType<typeof setTimeout> \| null = null`、`:132-134` = `readyPromise`、`:137-140` = `readyTimer = setTimeout(...)`；`excel.ts:847` = `setTimeout(() => URL.revokeObjectURL(url), 3000)`；`ai.ts:52` = `formExpiryTimer`、`:62` = `mirrorTimer`、`:533-537` = `$subscribe` 300ms 防抖、`:1299-1303` = `formExpiryTimer = setTimeout(...)` | **一致**（T15-4 区间订正） |
| A13 | `fe/tests/`（跑道） | 扁平结构：**12 个 `*.spec.ts`**（`ai-panel-parts-render` / `blocks` / `export-wizard-step-outcome` / `frontend-tool-outcome` / `frontend-tool-result-terminal` / `parts-double-write` / `parts-reducer` / `parts-residue-cleanup` / `ready` / `restore-reconcile` / `sse-watchdog` / `tools-and-deadline`）+ `setup.ts`（7 行，`globalThis.ResizeObserver = ResizeObserverStub`） | **一致** |
| A14 | `fe/package.json` / `vitest.config.ts` | `package.json:6-13` = 六条 scripts（`:11` = `"test": "vitest run"`、`:12` = `"test:watch": "vitest"`、`:8` = `"build": "vue-tsc --noEmit && node scripts/build.mjs"`、`:9` = `"typecheck": "vue-tsc --noEmit"`）；`vitest.config.ts` = `environment: 'jsdom'` + `include: ['tests/**/*.spec.ts']` + `setupFiles: ['./tests/setup.ts']` + `@` 别名 | **一致** |
| A15 | `backend/src/main/resources/application.yml`（**T3 新锚点**） | `:191` = `task:`；`:192` = `sse:`；`:193-201` = 注记块；**`:202` = `write-timeout: 15s`**；**`:203` = `emitter-timeout: 5m`**；`:204` = `job:`；`:207-219` = 看门狗注解块；`:220` = `heartbeat-interval: 30s`；**`:221-223` = `watchdog` 块**（`:222` = `scan-period: 10s`、`:223` = `dead-multiplier: 3`）；**`:224` = `max-execution-time: 30m`** ⇒ 段内**确无 heartbeat 键** | **一致**（段 = `:191-203`；草案 `:191-198` 为注释头） |
| A16 | `backend/.../task/service/TaskSseService.java`（**全路径**，232 行） | 路径 = `configmgr/**task**/service/TaskSseService.java`（**非** `job/service`）；`:73` = `public SseEmitter subscribe(Long taskId) {`；`:83-85` = `onCompletion`/`onTimeout`/`onError` 三摘除钩子；`:87` = `// Send initial heartbeat`；**`:88` = `sendEvent(taskId, emitter, "HEARTBEAT", Map.of())`**；`:92-101` = `@EventListener public void onTaskChanged(...)` → `publish(..., "TASK_CHANGED", Map.of("taskId",…,"version",…,"currentStep",…,"status",…,"summary",…))`。**全文件无周期心跳调度** | **一致**（+ 全路径订正） |
| A17 | 后端 `HEARTBEAT` 全量 / 终态发布点 | **字面 `"HEARTBEAT"` 在 main 源码仅 1 处**（`TaskSseService.java:88`）；`JOB_PROGRESS` = `ExportJobRunner.java:271` / `ImportJobRunner.java:216` / `PrecheckJobRunner.java:360` / `PublishJobRunner.java:364`；`JOB_DONE` = `JobTerminalWriter.java:86` / `JobService.java:268` | **一致**（V5） |
| A18 | `backend/.../job/service/JobWatchdog.java`（258 行） | `:114-115` = `@PostConstruct` + `void start() {`；`:117` = `Executors.newSingleThreadScheduledExecutor(r -> {`；`:118` = `new Thread(r, "job-watchdog")`；`:119` = `thread.setDaemon(true)`；`:123` = `svc.scheduleWithFixedDelay(this::scanSafely, scanPeriodMs, scanPeriodMs, TimeUnit.MILLISECONDS)`；`:159-160` = JSDoc + `void scan() {`；`:161` = `List<Job> inFlight = this.jobRepository.findByStatusIn(IN_FLIGHT)` | **一致** |
| A19 | keep-alive 族全仓复核（**T5 前提**） | `fe/src/` 全仓 `keep-alive`/`keepAlive`/`onBeforeRouteLeave`/`beforeRouteLeave`/`$reset`/`$dispose`/`import.meta.hot` → **0 命中** ⇒ 路由切换必然触发 `TaskCenterView.onBeforeUnmount` | **一致**（V6） |
| A20 | 基线数字 | `setInterval` 3 调用点 + 2 类型声明 / `EventSource` 2 / `visibilitychange` 0 / `TASK_CHANGED` 运行时消费 0 / `hasRunningJob`+`hasActiveJob` 0；`yarn test` = `Test Files 12 passed (12) / Tests 154 passed (154)` / exit 0；`yarn typecheck` = exit 0 无输出；`git status --porcelain`（含 `backend/`）= 零输出 | **一致** |
| A21 | 台账 / 文档锚点 | `docs/M2-排期计划.md:280` R-88（行内**确写** `startAutoRefresh:182-199` ⇒ **漂移确认**）；`:281` R-89（行内**确写** `ImportWizardView.vue:748-795` ⇒ **漂移确认**）；`:286` R-94；`:288` R-96；`:347` 记 **29**；`:205` R-13；`:206` R-14；`:218` R-26；`docs/requirements.md:58` 仍写「列表 5 秒自动刷新」+ 2026-10-10 注记纠正为 4s；`.github/workflows/ci.yml:108` 与 `:212` 按 **29** 命名 | **一致**（N-4 仍成立） |

**订正登记（本轮新采纳，全部来自《决策卡》T3/T13/T15）**

- **锚点订正①**（T3/T15-1/2）：`application.yml` 的 `app.task.sse` 段 **`:191-198` → `:191-203`**；键值补注 `write-timeout` = **`:202`**、`emitter-timeout` = **`:203`**（结论不变：段内无 heartbeat 键）。
- **锚点订正②**（T3/T15-5）：`TaskSseService.java` 补全路径 `configmgr/**task**/service/TaskSseService.java`（**非** `job/service`）。
- **锚点订正③**（T3）：`app.job.watchdog` = **`:221-223`**（`scan-period` `:222`、`dead-multiplier` `:223`）；`max-execution-time` = **`:224`**；注解块 = `:207-219`。
- **锚点订正④**（T13-4/T15-3）：`stores/workspace.ts:449` = **直调** `refreshCurrent`；`:463` = 经 `cancelJob` **间接**。
- **锚点订正⑤**（T15-4）：`utils/frontend-tools.ts#runWithBudget` = **`:383-408`**（草案 `:383-410`）。
- **口径订正⑥**（T13-5）：`api/tasks.ts:103` 注释「4s」与 `utils/job-watch.ts:21` 的 3000ms **不对齐**，本批顺手改实（注释级）。
- **口径订正⑦**（T10）：D5 上限 3 的论据删除"同域 6 连接"，改为"防御性上限 + 可观测计数"。

---

## 10. 给施工方的硬性条款（不满足即视为未完成）

> **本节为定稿的必要组成**。以下五条与 §2 的改动面同等效力；任一条不满足，施工报告**不得**判 PASS。

### 10-1 机械活下沉条款

- 施工中的**机械活**（锚点批量抽验、文件全文抽取、基线命令复跑、格式清点）**必须下沉给子智能体**执行。
- **派发时必须显式钉 `model = DeepSeek V4 Flash`（`DeepSeek/deepseek-flash`）；互备口径 = `GLM-5.3-Flash`（`Zhipu AI/glm-5.3-flash`）**，在主 model 不可用时启用。
- **禁止依赖会话默认模型** —— 不得以"会话默认就是这个"为由省略显式参数。
- **报告自证**：每个子智能体的实际模型须由**其 `wire.jsonl` 的 `modelAlias` 字段实证**（见 10-2），在施工报告中逐子智能体列出（子智能体 id + wire 文件路径 + `modelAlias` 计数 + 样本行）。

### 10-2 报告首页署名（DC-19）—— **硬性完成判据**

施工报告**首页**须含 DC-19 署名块，至少包含：

| 字段 | 要求 |
| --- | --- |
| 执行者 | **本棒 agent id**（真实 id，非占位） |
| 授权来源 | 派单来源（决策卡/施工卡编号与日期） |
| 会话 id | **本棒会话 id** |
| **实际运行模型** | 真实模型别名 |
| 模型取证（实证） | **`wire.jsonl` 的 `modelAlias` 实证**：文件全路径 + 行数 + `grep -o '"modelAlias":"[^"]*"' wire.jsonl \| sort \| uniq -c` 的**计数输出** + 一条 `llm.request` 样本行；另附 `profile.bind` 佐证 `agentId` 归属 |
| 子智能体模型自证 | 按 10-1 逐子智能体列出 |

> ⚠️ **照抄卡头 = 不合格**。**「报告自证实际模型 + 会话 id」是本批的硬性完成判据**：无法给出 `wire.jsonl` 的 `modelAlias` 实证（或实证与卡头声明不符而未说明）⇒ **施工报告判不合格**，不得进入复核。

### 10-3 仓库红线

- **主仓库唯一可操作 = `<MAIN_REPO>`**（HEAD `6c9c699`，工作树须干净起步）。
- **trae 线两目录严禁触碰**：`<REPO_ROOT>` 与 `<REPO_ROOT>` —— **零读取、零写入、零 grep**。
- **禁读任何 `.env`**（含 `.env.*` 变体）；需要配置值时只读 `.env.example`/`.env.template` 或 `application.yml`。
- 施工前须记录 `git rev-parse HEAD` 与 `git status --porcelain`；**后端目录改动 = 零**（见 10-5）。
- 不得做与本期无关的顺手改（含格式化、重命名、依赖升降级）。

### 10-4 TDD 口径（先 RED 后 GREEN 留证）

- §3 每条断言**必须先写 RED**（此时实现不存在，断言必然失败）→ **保留 RED 输出原文** → 再实现 → **GREEN**。
- 施工报告须给出：**RED 阶段的失败输出**（至少含 spec 文件名、用例名、失败原因）与 **GREEN 阶段的通过输出**（`Test Files` / `Tests` 计数与 exit code）。
- 不得以"先实现再补测试"代替；不得删除或跳过 RED 阶段的记录。
- 新增 spec 落 `frontend/tests/` 根、沿用 `*.spec.ts` 命名（§3 跑道口径）。

### 10-5 后端零改动判据

```bash
cd <MAIN_REPO>
git status --short backend/     # 期望：零输出（空）；基线已空【实测】
```

- **判据 = 空**。任一非空输出即视为越界（撞 D16 红线），须回退后再提交。
- 连带：`git status --porcelain` 中除 `frontend/` 与 `docs/`（若按 E4 补登记）外，不应出现其他路径。

### 10-6 施工报告其余必备内容

1. **§4.1-0 第 0 步基线实测值**（三项请求数 + keep-alive 复核结论）；
2. **§4 全部 DevTools 判据的 Network 截图或 HAR 留档**（无留档视为未执行）；
3. **§4.3-7 的 B1 判死链实测结论**（把 §1.4 的【推断】转【实测】）；
4. **"止血"口径写明**：「**降频 90%（3s→30s）而非归零**」（多页签量化建议附，不作门槛）；
5. **R-88 / R-89 行内锚点更新建议**（`docs/M2-排期计划.md:280` 的 `startAutoRefresh:182-199` → `:157-168`；`:281` 的 `ImportWizardView.vue:748-795` → `:840-888`）与 **R-94 行尾注记建议**（`:286`）；
6. **未做事项声明**（不得把未执行的判据写成已通过）。

---

## 11. 署名节（DC-19）

| 项 | 值 |
| --- | --- |
| 本件产出 | `<SCRATCH>\施工卡-定稿.md`（本文件，**完整替代草案**）＋ `<SCRATCH>\决策卡-轮询一期.md`（定稿依据） |
| 执行者 | **`agent-563`**（子智能体） |
| 派发方 | K3 会话 `session_6d2ca6dd-42c9-4200-8024-db261420104f`（工作区 `wd_temp_38dcbf7ced2f`） |
| 授权来源 | **K3 指挥官派单** · 轮询治理一期（决策卡 + 施工卡定稿，2026-10-12） |
| 决策署名 | **决策由 K3 汇总代签** —— 本稿为 K3 终裁之承载件，**执行者无裁决权**；凡本稿与 K3 复裁冲突，以 K3 复裁为准 |
| 实际运行模型 | **`DeepSeek/deepseek-flash`** |
| 模型取证方式（wire 实证，非照抄卡头） | 取 `llm.request` 的 `modelAlias` 字段 —— 文件 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_6d2ca6dd-42c9-4200-8024-db261420104f\agents\agent-563\wire.jsonl`（**145 行**）；`grep -o '"modelAlias":"[^"]*"' wire.jsonl \| sort \| uniq -c` → **15 命中，全部为 `"DeepSeek/deepseek-flash"`**（无混杂）；`llm.request` 计 **14** 条。样本行（首条 `llm.request`）：`{"type":"llm.request","agentId":"agent-563","kind":"loop","provider":"openai","model":"deepseek-flash","modelAlias":"DeepSeek/deepseek-flash","thinkingEffort":"high","thinkingKeep":"all","maxTokens":393216,"toolSelect":false,"systemPromptHash":"bb5f824cbd5ee286499a461e0e4d5d95183dc1b9d90e5b01f70892bfb5745365","toolsHash":"62b6baf0f275073f0c3f5985e683e3e66d1e42dc3c3af162aeeb325c4b7a0288","messageCount":3,"turnStep":"0.1","time":1791765286287}`；另 `{"type":"profile.bind","agentId":"agent-563","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}` 佐证 agentId 归属 |
| 本件纪律声明 | 主仓库**全程只读**（未做任何 git 写操作、未改仓库任何文件）；未读任何 `.env`；未触碰 `<REPO_ROOT>` 与 `<REPO_ROOT>`（trae 线）；落盘限于 `<SCRATCH>\` |
| 已执行的验证命令（证据可复现） | `sha256sum` 草案与两份会审报告（逐字节核验）；`git rev-parse HEAD` + `git status --porcelain`（含 `backend/`）；`cd frontend && yarn test` → **12 spec / 154 passed / exit 0**（3.11s）；`yarn typecheck` → **exit 0 无输出**（3.54s）；前端 `Read`/`awk`/`grep` 亲验 **60+ 处**锚点（`stores/task.ts` 369 行、`utils/job-watch.ts` 123 行、`TaskCenterView.vue` 357 行全文）；后端 `grep`/`awk` 亲验 `application.yml:186-230`、`TaskSseService.java`（232 行）、`JobWatchdog.java`（258 行）、四 Runner + 两 `JOB_DONE` 发布点；全仓 0 命中复核（keep-alive 族）；`agents/agent-563/wire.jsonl` 模型取证 |
| 未做的事 | 未启动后端与前端环境、未做浏览器 DevTools 实测 ⇒ §4 中所有"请求数/连接数"运行时判据仍为**【推断】基线，待施工第 0 步实测回填**；未运行 `yarn build`（体积大、耗时）；未运行 E2E 桩（需桩上游 + Redis 容器）；未执笔任何后端改动（D16 红线） |
