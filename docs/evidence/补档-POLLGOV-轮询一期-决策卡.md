<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\决策卡-轮询一期.md`（51547 B，sha256 `fa06045e7bfeddf2c42fbe49395e0d117af4f647b14828dd66b35efa06e1b298`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 决策卡 · 轮询治理一期（R-88）

> **本件性质**：K3 指挥官终裁之**忠实承载件**。裁决权在 K3，本件只做归并、落点与留痕；
> 执行者（子智能体）**无裁决权**。凡本件文字与 K3 复裁冲突，一律以 K3 复裁为准。
> 本件配套产出 = `<SCRATCH>\施工卡-定稿.md`（完整替代草案的施工单文件）。

---

## 卡头

| 项 | 值 |
| --- | --- |
| 项目 | `<MAIN_REPO>`（前端 Vue3 + Element Plus + Tailwind ／ 后端 Spring Boot） |
| 决策日期 | **2026-10-12** |
| 实读基线（全哈希） | `HEAD = 6c9c699aa680297ecf0a83ec39eeae8716b68431`【实测】 |
| 工作树 | `git status --porcelain` **零输出 = 干净**；`git status --porcelain backend/` **零输出**【实测】 |
| 决策对象（草案） | `<SCRATCH>\施工卡草案.md` = **62476 B / 443 行** / sha256 `e3dfacf011f78e4dcaad5b601a3d9395ba20fc14d266d6452815a8826a2fe0e9`【实测·逐字节一致】 |
| 会审输入①（复核方·GLM-5.3） | `<SCRATCH>\会审-轮询一期-GLM.md` = **29688 B / 251 行** / sha256 `e1c712041dc6f5f25a508069e7311a12df90e80486d1f5f51fdca9b901f2b978`【实测】；会话 id `session_cf64f937-4010-4a06-aef9-7a19fad9f2b7`；实际模型 `Zhipu AI/glm-5.3`（报告附录 A wire 自证） |
| 会审输入②（反方·红队·DS V4 Pro） | `<SCRATCH>\会审-轮询一期-DSPro.md` = **26422 B / 199 行** / sha256 `45122549d9822329dd6448942475dcf0a5d3adc9e671653fcafd687af3c2a3e3`【实测】；会话 id `session_ecea153e-82a6-4d9a-a79b-142f07b7757`；实际模型 `DeepSeek/deepseek-v4-pro`（报告 §7 wire 自证） |
| 去向表条目 | **R-88**（`docs/M2-排期计划.md:280`，主）／同类点 **R-89**（`:281`）／关联 **R-26**（`:218`）、**R-14**（`:206`）、**R-13**（`:205`）、**R-94**（`:286`）【实测·逐行命中】 |
| 一期范围 | 纯前端三步：**条件轮询 → `visibilitychange` 纪律 → SSE 事件触发刷新**；后端零改动（红线） |
| 断言标注 | `【实测】` = 本次实读/实跑亲验（含行号）；`【推断】` = 由实读结构推导，未跑起来验证；`【假设】` = 需施工时现场确认 |
| 编号口径警告 | 两方会审报告的 **C 编号复用不同主题**（GLM 的 C1 = application.yml 锚点、C2 = 保底 tick 建模、C3 = 错峰作用点、C4 = refreshNow×loading、C5 = 卸载出口；DSPro 的 C1 = D12 强化、C2 = abort 语义、C3 = task-events 生命周期、C4 = 淘汰策略、C5 = 保底锁回归、C6 = 止血口径）。**本卡一律按主题归并为 T1~T15，不按 C 编号引用**；草案 §9「订正登记」的第三套 C 编号亦不复用。 |
| 本件纪律 | 仓库全程只读；未做任何 git 写操作；未改仓库任何文件；未读任何 `.env`；未触碰 trae 线（`<REPO_ROOT>`、`<REPO_ROOT>`）；落盘限于 `<SCRATCH>\` |

---

## 0. 执行者本轮亲验复现（本卡事实基础）

| # | 项 | 复现值 | 标签 |
| --- | --- | --- | --- |
| V1 | 草案 sha256 / 字节 / 行数 | 三者与卡头声明逐字节一致 | 【实测】 |
| V2 | HEAD + 工作树 | `6c9c699…`；`git status --porcelain` 零输出；`backend/` 零输出 | 【实测】 |
| V3 | 测试基线 | `cd frontend && yarn test` → `Test Files 12 passed (12)` / `Tests 154 passed (154)` / Duration 3.11s / **exit 0** | 【实测】 |
| V4 | 静态门禁基线 | `yarn typecheck`（`vue-tsc --noEmit`）→ **exit 0 无输出** | 【实测】 |
| V5 | 任务级 SSE 无周期心跳 | 全后端（main 源码）字面 `"HEARTBEAT"` **仅 1 处** = `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:88`（订阅时补发首帧）；**无任何 `schedule`/`Timer` 周期发布点** | 【实测】 |
| V6 | keep-alive 复核 | `frontend/src/` 全仓 `keep-alive`/`keepAlive`/`onBeforeRouteLeave`/`beforeRouteLeave`/`$reset`/`$dispose`/`import.meta.hot` → **0 命中** ⇒ 路由切换必然触发 `TaskCenterView` 卸载 | 【实测】 |
| V7 | 全仓构造点总账 | `setInterval` 调用点 3 处（`stores/task.ts:162` / `utils/job-watch.ts:106` / `components/AiPanel/AiPanel.vue:839`）；`new EventSource` 2 处（`api/tasks.ts:106` / `api/sse.ts:552`）；`visibilitychange` **0 处** | 【实测】 |
| V8 | 后端终态发布点 | `JOB_PROGRESS` = ExportJobRunner:271 / ImportJobRunner:216 / PrecheckJobRunner:360 / PublishJobRunner:364；`JOB_DONE` = JobTerminalWriter:86 / JobService:268 | 【实测】 |

> V5 使 **T1/T2/T3 的"任务级通道无周期心跳"前提成立**；V6 使 **T5 的"卸载出口"前提成立**（无需补 `onActivated/onDeactivated`）。

---

## 1. 决策总表 T1~T15

> 每行：**主题 / 两方立场 / K3 裁决 / 理由 / 落点**。裁决为「采纳必做」「半采纳」「驳回」三态。

### T1 · 保底 tick 建模（GLM C2 ＋ DSPro C5 交叉命中）

- **主题**：D10 的 30s 保底 tick 在 D3 契约下如何承载？草案未裁定「单 interval 动态切换」还是「双 controller 实例」，导致 T1/T2 对"全静止 30s 内 ≤1 次保底"这一核心语义**无人锁**。
- **两方立场**：**双方独立命中同一空洞**。GLM 判为「语义真空点三」（D3 评议第 4 问：`{intervalMs, run, shouldRun}` 单一间隔无法表达双节奏）；DSPro 判为 G5（"30s 保底无独立锁回归 + 机制未钉"）。
- **K3 裁决**：**采纳必做**。
- **理由**：① 这是 D10（一期降频的核心收益项）的**唯一承载机制**，不定死则施工方自由选择，TDD 断言无处落；② 单 interval 动态切换相较双 interval 并行**少一套状态**（无双发风险面）、相较双 controller 实例**少一份监听器与实例计数**（与 T12 驳回"监听器放大"的判断同源）；③ 三条新断言全部可假时钟机械锁定，符合 D1 的"TDD 可回归"标准。
- **终态**：
  1. 机制**钉死 = 单 interval 动态切换**（不采双 controller 实例）。
  2. `createPollingController` 接口扩展为 **`{ intervalMs, fallbackIntervalMs, run, shouldRun }`**。
  3. 新增三条断言（落 T2/T5，见 §6）：
     - ① `shouldRun=false` 持续 30s（假时钟）→ `run` **恰好 1 次**且以 30s 为周期；
     - ② 保底 tick 与 4s 条件 tick **不同 tick 双发**；
     - ③ 3s↔30s 切换瞬间**旧 timer 清除**（切换后首个 tick 距切换 ≥3s）。
- **落点**：`施工卡-定稿 §2 步 2-1`（接口签名 + 注释）；`§3 T2①/②/③`；`§4.2-1/-5`。

### T2 · `refreshNow` × `loading` 短路（GLM C4 ＋ DSPro S3/S6）

- **主题**：`refreshNow`（回可见补刷）撞在途 `load()` 时被 `loading` 守卫（`stores/task.ts:114-116`）静默丢弃；草案 D9 的 dirty flag **只写明覆盖"事件路径"**，回可见路径无补发语义。
- **两方立场**：GLM 判为「语义真空点一」（C4：若不处理，T2②"visible → 恰好补 1 次"在补刷瞬间恰有在途 load 时实测为 0 次有效拉取，建议 dirty flag 统一覆盖）；DSPro 判为 S3/S6（A1/C2：被挡者意图静默丢弃、D7 的"重置 interval 起点"也随之丢失；S6 另指出 `job-watch.ts:71` 的 `JOB_DONE` 刷新与 tick 共用 `inFlight`，同受短路）。
- **K3 裁决**：**采纳必做，且扩口径**。
- **理由**：① 三条不同来源的刷新路径（SSE 事件帧 / 回可见 `refreshNow` / `JOB_DONE` 触发刷新）**共用同一套防重叠守卫**，只覆盖其中一条等于留下两条静默丢数据的路径；② DSPro 的 S6 命中了一处草案未枚举的路径——`utils/job-watch.ts:71` 的 `void syncSnapshot()` 与 3s/30s tick 共用 `inFlight` 守卫（`job-watch.ts:87-88`），事件触发刷新可能被轮询在途窗口吞掉一次；③ 语义写进 controller 注释是**最低成本的防回归手段**（下一棒改守卫时能看见口径）。
- **终态**：
  1. **D9 dirty flag 语义统一覆盖三路径**：① SSE 事件帧路径；② 回可见 `refreshNow` 路径；③ `JOB_DONE` 触发刷新路径（`utils/job-watch.ts:71`，与 tick 共用 `inFlight`）。
  2. 语义**写进 controller 注释**（"被在途守卫挡掉的刷新不丢弃、标记待办、收尾补发恰好 1 次"）。
  3. 断言落 **T2 + T4**（见 §6）。
- **落点**：`施工卡-定稿 §2 步 2-1`（注释）+ `步 1-1`（task store 侧）+ `步 3-1`（job-watch 侧）；`§3 T2④/⑥`、`§3 T4`。

### T3 · `application.yml` 锚点（GLM C1 ＋ DSPro S4）

- **主题**：草案全文引用 `application.yml:191-198` 的 `app.task.sse` 段 —— 实测该段为 **`:191-203`**，`:191-198` 只有注释头，键值在 `:202`/`:203`。
- **两方立场**：**双方独立命中**。GLM 判为 C1（"行号区间尾界漂移 5 行"，唯一硬性不一致）；DSPro 判为 S4（"漂移 2~4 行（结论面全真）"，并顺带订正 `job.watchdog` 与 `max-execution-time`）。
- **K3 裁决**：**采纳必做（文书级）**。
- **理由**：① 锚点可复现是本项目一贯的审计纪律，漂移锚点会让下一棒 grep 落空；② 结论面（段内**确无 heartbeat 键**）不受影响，故为文书级订正、无代码影响；③ 顺带订正 `job.watchdog`（`:221-223`）与 `max-execution-time`（`:224`）成本为零，且草案 §0-7 正引用该段作为"作业心跳 ≠ SSE 帧心跳"的对照。
- **终态**：施工卡/施工报告中该锚点一律按下列亲验值书写（本次逐处 `awk` 亲验【实测】）：

| 键 | 亲验值 |
| --- | --- |
| `app.task.sse` 段 | **`:191-203`**（`task:` = `:191`、`sse:` = `:192`、注释 `:193-201`） |
| `write-timeout: 15s` | **`:202`** |
| `emitter-timeout: 5m` | **`:203`** |
| 注记（`write-timeout` / `emitter-timeout` 口径说明） | **`:193-201`**（草案原写"无 heartbeat 键"，**结论不变**） |
| `app.job.heartbeat-interval: 30s` | **`:220`** |
| `app.job.watchdog` 块 | **`:221-223`**（`scan-period: 10s` = `:222`、`dead-multiplier: 3` = `:223`） |
| `app.job.max-execution-time: 30m` | **`:224`** |
| 看门狗注解块 | **`:207-219`** |
| `TaskSseService.java` 全路径 | **`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`**（`configmgr/task/service/`，**非** `configmgr/job/service/`；232 行） |

> 附加订正（与 DSPro S4 合并承载）：草案 §1.4 与 §6-D5 引 `TaskSseService` 时用 `...` 省略，施工方按 `job` 包定位会落空 ⇒ 全路径已在施工卡内写死。

- **落点**：`施工卡-定稿 §0-7 / §1.4 / §6-D6 / §6-D8 / §5-6` 全部锚点行。

### T4 · 错峰作用点（GLM C3）

- **主题**：D11 写"苏醒加 0~250ms 随机错峰"，但**未钉作用点** —— 是作用于「回可见补刷本身」还是仅作用于「interval 重置」？
- **两方立场**：GLM 独立命中（C3：若仅作用于后者，R2-1 的"苏醒齐射打爆接口"未解；若作用于前者，T2② 的"恰好补 1 次"必须推进假时钟才可观测，两条断言的时钟口径需统一）；DSPro 支持 D11 但未钉作用点（仅 S1 提滞回，被 T11 驳回）。
- **K3 裁决**：**采纳必做**。
- **理由**：① R2-1 的真风险是**醒齐射**（列表 1 条 + 每被跟踪作业 1 条），只有把延时加在补刷请求本身才能打散；② 加在 interval 重置上只影响后续节拍、不影响齐射瞬间，**解不了 R2-1**；③ 统一时钟口径后 T2②/T2⑤ 可由同一条断言机械锁定。
- **终态**：0~250ms 随机延时**作用于「回可见补刷本身」**（非仅 interval 重置）；**T2② 与 T2⑤ 统一为「假时钟推进 ≤250ms 窗口内观测恰好 1 次 `run`」**。
- **落点**：`施工卡-定稿 §2 步 2-1`；`§3 T2⑤`；`§4.2-2/-3`。

### T5 · 卸载出口（GLM C5 ＋ DSPro C3 合并）

- **主题**：D4 把订阅状态挂 `stores/task.ts`（Pinia 单例，**跨路由存活、不随组件卸载**）；"卸载出口"若不显式调 store action，离开任务中心页后 ≤3 条 `EventSource` 仍存活。另：新增的 `task-events.ts` 若无"已关闭闸"，`close()` 后迟到帧会触发 `refreshNow()`。
- **两方立场**：GLM 判为 C5/G3（"keep-alive 前提未验，D5 的卸载出口成立性系于此"）；DSPro 判为 C3/G3（B2 实测全仓 0 命中 keep-alive ⇒ "路由切换即卸载，这是好消息"，但跨层绑定未钉死）+ B3（迟到帧）。
- **K3 裁决**：**采纳必做**。
- **理由**：① **GLM 的 C5 前提已由 DSPro 的实测解掉** —— 本次执行者**独立复验确认**：`frontend/src/` 全仓 `keep-alive`/`keepAlive`/`onBeforeRouteLeave`/`beforeRouteLeave`/`$reset`/`$dispose`/`import.meta.hot` **0 命中**【实测 V6】⇒ 路由切换必然触发 `TaskCenterView.onBeforeUnmount`，**无需补 `onActivated/onDeactivated`**；施工第 0 步只需**引用该实测结论**复核一次即可；② 但"组件会卸载"≠"订阅会被关"：状态在 store 里，**必须显式跨层调用**，否则卸载钩子只停了 timer；③ DSPro 的迟到帧风险与 `job-watch.ts:58-60` 既有 `disposed` 闸同源，新模块须同款闸。
- **终态**：
  1. 施工第 0 步**复核引用 keep-alive 实测**（一行 grep + 结论），不重复调查。
  2. **卸载出口 = `TaskCenterView.onBeforeUnmount` 显式调 store action 关闭全部 `EventSource`**（不依赖 store 自动清理）。
  3. `task-events.ts` 的 `onFrame` 派发**补 close-gate**：close 后迟到帧**丢弃、不触发 `refreshNow`**。
  4. 新增断言（见 §6）：**「离开任务中心页后 EventSource 计数归 0」** ＋ **「close 后到达的帧不触发 refreshNow」**。
- **落点**：`施工卡-定稿 §2 步 3-2`；`§3 T3③`、新增断言；`§4.3-3`；`§0-第0步`。

### T6 · D12 强化（DSPro C1，红队本职命中）

- **主题**：D12「Import 侧按 `kind` 回收旧 watcher」**只能**清 `clearInterval` + `source.close()` + 置 `disposed=true`，**挡不住已经发出的在途 `getJob` promise**。`utils/job-watch.ts:86-104` 的 `syncSnapshot` 在 `await jobsApi.getJob`（`:92`）之后**无 `disposed` 复检**，直接 `options.onSnapshot?.(job)`（`:93`）+ 判终态后 `dispose()`（`:96`）+ `options.onFinal?.(finalJob)`（`:97`）。而 Import 的 `onFinal`（`views/ImportWizardView.vue:859-885`）内部 `refreshTaskSnapshot()` 读的是**当前** `taskId.value` ⇒ 旧 job 的终态会**覆盖新 job 的状态/弹旧 ElMessage**。
- **两方立场**：GLM 未命中（仅验 dispose 计数面）；DSPro 独立命中为 **C1/G1「最关键必做条件」**，判定"不补 `disposed` 复检则按 kind 回收形同虚设"。
- **K3 裁决**：**采纳必做**。
- **理由**：① 这是**红队本职面**（竞态/生命周期泄漏）的真实命中，且执行者亲验坐实（`job-watch.ts:92-97` 逐行确认无复检）；② 一期把 D12 列为核心缺陷修复项，若只做"回收"而不做"在途作废"，则用户重跑同 kind 作业时会看到**旧作业终态提示覆盖新作业状态**——比修复前更难诊断；③ 修复成本极低（`await` 后一行 `if (disposed) return`），TDD 断言可机械锁。
- **终态**：
  1. `utils/job-watch.ts#syncSnapshot` 在 `await jobsApi.getJob` **之后补 `disposed` 复检**：dispose 后在途快照**不写回 `onSnapshot`、不触发 `onFinal`**。
  2. **T7 扩断言**：「同 kind 回收后旧 job 在途 resolve **不写回新 job 状态、不弹旧终态提示**」（现有 T7 只锁 dispose 计数 1 次，不锁在途写回）。
- **落点**：`施工卡-定稿 §2 步 1-5`（新增条目）；`§3 T7②` 扩条。

### T7 · abort 语义（DSPro C2）

- **主题**：① `stores/workspace.ts:452` = `void taskStore.watchPublishOutcome(job.id ?? 0)` —— **`void` 丢弃 promise**【实测】；D2 给 `waitForJobFinal` 透传 `signal` 后，abort 触发 `throw new Error('等待作业终态已被取消')`（`api/jobs.ts:55-57`）经 `watchPublishOutcome`（无 try/catch）上抛到 `void` 调用点 ⇒ **unhandled promise rejection**。② 超时提示若落在 `onTick`（每 1s）⇒ 300s×1s = **300 次刷屏**。
- **两方立场**：GLM 未命中（D2 评议判"支持"，只给"行尾注记"要求）；DSPro 独立命中为 **C2/G2**，并给出落点（`watchPublishOutcome` 内 try/catch，或改可判别返回值 + 调用方处理）。
- **K3 裁决**：**采纳必做**。
- **理由**：① D2 的 AbortSignal 透传是**一期保留项**，若不配捕获则该保留项会把一个静默吞没缺陷（`publishConflict = null`）换成一个 unhandled rejection —— 换错不换对；② 超时提示落 `onTick` 是**必然的刷屏路径**（`onTick` 每 1s 调一次，300s 上限），必须单次出口；③ DSPro 另实测 abort 判定在 **loop-top `:55-57`**（在 `getJob` `:58` 与 `sleep` `:66` 之前）⇒ 退出保证成立（下一轮 loop-top 必 throw，无死循环）但**非立即**，且 `onTick` 可在 abort 后再写一次 `currentJobs` —— 该"一次 post-abort 写回"由"单次出口"口径覆盖，不另立机制。
- **终态**：
  1. `stores/task.ts#watchPublishOutcome` **显式捕获** `waitForJobFinal` 抛出的 abort Error（try/catch 或可判别返回值），**消除 `workspace.ts:452` `void` 调用下的 unhandled rejection**。
  2. 超时/中止提示落**单次出口**（**超时 1 次 / abort 1 次**），**禁止放 `onTick`**。
  3. **T6 扩断言**：「abort 无 unhandled rejection」＋「300s 超时提示恰好 1 次」。
- **落点**：`施工卡-定稿 §2 步 1-3`；`§3 T6①/②/③`。

### T8 · 淘汰策略（DSPro C4）

- **主题**：`EventSource` 上限 3 时，**第 4+ 个在跑任务的处置**与**淘汰顺序**均未定义。淘汰顺序不写死 ⇒ T3 无法写死，且翻页/筛选触发 `close()` + 重订阅时可能抖动。
- **两方立场**：GLM 未命中（D5 评议只查四出口穷尽性，判"上限口径合理"）；DSPro 独立命中为 **C4/G4**（B1），建议按 `taskId` 稳定排序取前 3。
- **K3 裁决**：**采纳必做**。
- **理由**：① 稳定顺序是可测性的前提（否则 T3① 的两条新断言无处落）；② `taskId` 稳定排序是**确定性**排序，不引入"最近使用"这类需额外状态的时间序；③ 第 4+ 个任务**不订阅 SSE 也不会失去进度可见性** —— `hasActiveJob` 覆盖全体可见行（含第 4+ 个），4s 条件轮询照跑，进度仍 ≤4s 新鲜【推断】。
- **终态**：
  1. `EventSource` 上限内**按 `taskId` 稳定排序取前 3**。
  2. **第 4+ 个在跑任务不订阅 SSE，靠列表条件轮询兜底**。
  3. **T3① 扩断言**：「第 4 个不产生实例」＋「重订阅前后淘汰顺序稳定」。
- **落点**：`施工卡-定稿 §2 步 3-2`；`§3 T3①` 扩条。

### T9 · 止血口径（DSPro C6）

- **主题**：D6 把 `realtime` 从原案"停表清零"改为"降为 30s 保底"⇒ 施工报告若仍写"止血 = 停表"，则口径与实际相反。
- **两方立场**：GLM 判 D6 修订"严格优于原案"但未提口径改写；DSPro 独立命中为 **C6**（F2：量化 3s→30s = **降 90%**，长作业 10min 从 200 次降到 20 次 GET），要求把"止血"改写为"降频 90%（3s→30s）而非归零"，并量化多页签放大。
- **K3 裁决**：**半采纳**。
  - **必做**：施工报告"止血"口径改写为「**降频 90%（3s→30s）而非归零**」。
  - **降为建议**：多页签残余请求**量化**（施工报告方便则附，**不作验收门槛**）。
- **理由**：① 口径必改——文档与实现说两套话是本项目反复治理的对象（参照 `requirements.md:58` 的刷新间隔口径纠正）；② 多页签量化降为建议——多页签是**既有放大面**（原 4s/3s 同样 ×N），一期只把基线频率压低，前端减负在多页签场景"收益打折"是事实但**不构成一期缺陷**；且量化需真实多页签 DevTools 计数，成本高于收益，不宜设为门槛。
- **终态**：施工报告"止血"节口径 = 「降频 90%（3s→30s）而非归零」；多页签残余请求量化列入"建议附"栏，不计入验收。
- **落点**：`施工卡-定稿 §4.3-4` 口径说明 + `§6` 施工报告要求节。

### T10 · D5 论据（GLM 支持 vs DSPro 反对，K3 采纳 DSPro）

- **主题**：D5 把"同域 `EventSource` 并发上限（浏览器通常 6）"作为上限 3 的**主要理由**。
- **两方立场**：**两方对立**。GLM **支持**（D5 评议："同域 HTTP/1.1 连接上限 6（浏览器通行值）【假设·通行浏览器行为】，AI reattach 占 1、任务级 ≤3、余量 ≥2，口径合理"，并判"§4.3-3 反查 AI 通道共存的设计正确"）。DSPro **反对**（B4/G6：实测 AI 通道对话走 fetch POST 非 EventSource，**只有 reattach 用 EventSource 且单次调用内 ≤1 条** ⇒ 正常用法下 6 连接上限**不可达**（AI reattach 1 + task-events ≤3 + job-watch 1~2 ≈ 5~6）；**真实放大源是 Import 的 job-watch 漏 dispose（R-14）**，而 D5 的"上限 3"只约束 `task-events` 池、**对 job-watch 池无效**；⇒ D5 的 6 连接论证与真实风险源**错位**，会误导评审对风险权重的判断）。
- **K3 裁决**：**采纳 DSPro**（论据改写）。
- **理由**：① DSPro 的实测证据（`api/sse.ts` 对话走 fetch POST、reattach 单次 ≤1 条 EventSource）**否证了 GLM"AI reattach 占 1 连接"的隐含假设**——GLM 该条自标【假设·通行浏览器行为】，DSPro 给的是【实测】；② 更关键：DSPro 指出上限 3 **对真放大源无效** —— job-watch 池的泄漏由 D12+T6 治理（已在 T6 采纳），把它写成 D5 的理由会让评审误判"上限 3 已治住泄漏"；③ 上限 3 本身**仍然保留**，只把理由从"保护 AI 通道"改为"防御性上限 + 可观测计数"——这不削弱 D5，反而使其论据与实测一致。
- **终态**：
  1. 上限 3 的论据改写为「**防御性上限 + 可观测计数**」。
  2. **删除**"同域 6 连接保护 AI 通道"**主论据**（实测正常用法下 6 连接不可达）。
  3. 明写：**真实放大源是 `job-watch` 漏 `dispose`（R-14 面）**，**该源已由 D12＋T6 治理**；上限 3 只约束 `task-events` 池，对 `job-watch` 池无效。
  4. §4.3-3 的"AI reattach 通道未被挤掉"反查**保留**（作为防御性验证），但不再作为上限值的主要依据。
- **落点**：`施工卡-定稿 §6-D5`；`§4.3-3` 反查注记。

### T11 · 滞回/debounce（DSPro S1） — **驳回**

- **主题**：DSPro S1 建议给 realtime/polling 切换加**滞回/debounce**，避免网络抖动导致 3s↔30s 频繁切换。
- **两方立场**：DSPro 提为建议（非必做，自标 S1）；GLM **未提**（GLM D6 评议反称"一期取 onerror/readyState + 30s 保底是合理工程解，无必要更强"）。
- **K3 裁决**：**驳回**（留痕）。
- **理由**：① **最小面约束**：一期已含三步 + 12 条必做条件，再引入滞回需额外状态机（抖动窗口计数 + 迟滞阈值 + 与 `refreshNow` 重置语义的交互），复杂度不值；② **切换频率本就低**：3s↔30s 只在 `channel` **状态迁移**时发生（`onopen` → realtime ／ `onerror && readyState !== OPEN` → polling，`utils/job-watch.ts:76-84`），不是每 tick 判定；③ 网络抖动下的最坏情形是"多切几次"，每次切换只改一个间隔常量，无资源泄漏路径【推断】；④ 若实测出现频繁切换，可作二期观察项再评估。
- **终态**：**不做**。留痕于施工卡 §5【不做清单】与 §7 排除项。

### T12 · module 级 `visibilitychange` 广播（DSPro S2） — **驳回**

- **主题**：DSPro S2 建议把 `createPollingController` 的 `visibilitychange` 监听收敛为"module 级单例广播 + 每实例订阅"，理由是 job-watch 多实例下监听器线性放大。
- **两方立场**：DSPro 提为建议（自标 S2，A3）；GLM 未提。
- **K3 裁决**：**驳回**（留痕）。
- **理由**：① **实例数小且已收敛**：`stores/task.ts` 1 个（模块级单例 timer `:58`）+ 每 job watcher 1 个（`utils/job-watch.ts:106`，一期已由 D12＋T6 治理同 kind 重复累积，且 Import 侧最多 check/import/publish 三种 kind 并存）+ AiPanel 1 个 ⇒ 稳态监听器数 ≤5，**线性放大面可忽略**【推断】；② module 级广播引入**全局态 + 订阅管理**（注册表、卸载竞态、广播顺序），与 T11 同源的"额外状态机复杂度"；③ 一期最小面原则下，**每实例自挂监听器**的语义更局部、更易测（controller 单测无需构造 module 单例）。
- **终态**：**不做**。留痕于施工卡 §5【不做清单】与 §7 排除项。

### T13 · 采纳的建议项（全部落施工卡）

> 以下 6 条原为会审"建议"（非必做），K3 全采纳并**逐条落施工卡**。

| # | 来源 | 内容 | 终态 |
| --- | --- | --- | --- |
| 1 | GLM S1 | T2 补 **start/stop 幂等断言**（对齐现 `stores/task.ts:158-161` 的 `if (autoRefreshTimer !== null)` 守卫语义，防迁移到 controller 后回归面无人锁） | **落 §3 T2⑦** |
| 2 | GLM S4 | 桩件注记：`EventSource` 桩挂 **`globalThis`**（jsdom 30 不内置 `EventSource`，且 `api/tasks.ts:106` 是内部直接 `new EventSource`、非参数注入）；`document.hidden` 须用 **`Object.defineProperty` 覆写**（jsdom 默认 false 且只读） | **落 §3 写法口径 + T3/T5 桩件注记** |
| 3 | GLM S5 | DevTools 运行时判据随施工报告**附 Network 截图/HAR 留档** | **落 §4 门禁注记 + §6 报告要求** |
| 4 | GLM S6 | R1-4 锚点口径改为「**`:449` 直调 + `:463` 经 `cancelJob` 间接**」（亲验：`stores/workspace.ts:449` = `await taskStore.refreshCurrent()` 直调；`:463` = `await taskStore.cancelJob(event.jobId)`，`refreshCurrent` 为间接链） | **落 §8 R1-4** |
| 5 | DSPro S5 | `api/tasks.ts:103` 注释「按规格走 **4s 轮询**」与 `utils/job-watch.ts:21` 的 `JOB_FALLBACK_POLL_MS = 3000` **数字口径不对齐**（注释级顺手修） | **落 §2 步 3-3** |
| 6 | DSPro P9 | 步 3 验收**顺带实测** B1 判死动作链对事件触发刷新有效（作业可能被 `JobWatchdog` 而非执行器置于终态，而 `JOB_DONE` 仍经 `JobTerminalWriter.java:86` 发出 ⇒ 该路径同样应被事件触发刷新覆盖；草案该结论为【推断】，须转【实测】） | **落 §4.3-7** |

### T14 · 保底轮询失败无退避（GLM S2） — **登记二期观察项，本批不动**

- **主题**：`utils/job-watch.ts:99-100` 的 `onError` **仅透传不处置**；断网期间 3s/30s 兜底轮询持续打失败请求。
- **两方立场**：GLM 提为 S2/G2（"现状既有、非一期引入，泄漏时长已被终态/卸载/max-execution-time 限界"）；DSPro 在 F1 中亦登记同面（"若整个网络都断，保底轮询也会失败"），未单列为条件。
- **K3 裁决**：**登记为二期观察项，本批不动**。
- **理由**：① **现状既有、非一期引入** —— `onError` 只透传是 HEAD 既有行为，一期不新增该失败路径（只把 3s 降为 30s，失败频率**下降 90%**）；② **已被三重限界**：终态 `dispose()`（`job-watch.ts:96`）、卸载 `dispose()`（`ExportWizardView.vue:1355-1357` / `ImportWizardView.vue:1114-1118`）、后端 `max-execution-time: 30m`（`application.yml:224`）；③ 引入退避/放弃策略需新增状态（连续失败计数 + 退避曲线 + 恢复判据），与 T11/T12 同源的复杂度考量；④ 一期目标（请求总量降频）已由 D6＋D10 达成。
- **终态**：**不做**。登记于施工卡 §5【不做清单】/ §7 排除项，标注「二期观察项」。
- **落点**：`施工卡-定稿 §7-E8`。

### T15 · GLM §8 更正清单 5 条 — **全采纳**

- **主题**：GLM 报告 §8 列出对草案文本的 5 条更正，供 K3 合并。
- **两方立场**：GLM 提出（自标"供 K3 合并"）；DSPro 的 S4 与之第 1/2/5 条部分重合（口径一致，无冲突）。
- **K3 裁决**：**全采纳（5 条全采纳）**。
- **终态**（与 T3/T10 合并承载）：

| # | GLM §8 原文 | 终态落点 |
| --- | --- | --- |
| 1 | §0-7 / §6-D6 / §6-D8 承载判读 / §5-6 中 `application.yml:191-198` → **`:191-203`**（结论不变：段内无 heartbeat 键） | 施工卡全文按 `:191-203` 书写（T3） |
| 2 | §6-D8「`application.yml:191-198` 的 `app.task.sse` 只有 `write-timeout` / `emitter-timeout`」**补注具体行**（**`:202`/`:203`**） | 施工卡 §6-D8 补注具体行（T3 表） |
| 3 | §8-R1-4 `stores/workspace.ts:449/:463` → **`:449`（直调）／`:463`（经 `cancelJob` 间接）** | 施工卡 §8 R1-4（T13-4） |
| 4 | §1.3 `runWithBudget :383-410` → **`:383-408`**（尾 2 行为下一段注释起点） | 施工卡 §1.3（亲验：`:383` = `async function runWithBudget(`，块止 `:408`） |
| 5 | （可选）§1.1-P2 / §6-D5 **补注 `TaskSseService` 全路径** `configmgr/task/service/TaskSseService.java`，免施工 grep 二次定位 | 施工卡 §1.4 / §6-D5（T3 表） |

---

## 2. D1~D16 终态表

> 「原草案立场」= `施工卡草案.md` §6 的文字（**草案 -555 轮已按上一轮 K3 裁定改写过 D6/D8**，故 D6/D8 的"原草案立场"即修订后文本）。
> 「会审修订后终态」= 本卡终态。带 **⚠️ 修订** 标记者 = 被 T1~T15 条件修订的条目。

| 条目 | 原草案立场（摘要） | 会审修订后终态 | 修订来源 |
| --- | --- | --- | --- |
| **D1** | vitest 锁回归（TDD：核心纯函数先 RED 后 GREEN），禁新增框架/依赖，不动 `package.json` 依赖面 | **不变**。补：T2 追加 start/stop 幂等断言（对齐 `stores/task.ts:158-161` 守卫语义）；桩件注记（`EventSource` 挂 `globalThis`、`document.hidden` 用 `Object.defineProperty` 覆写） | T13-1 / T13-2 |
| **D2** | R-94 收窄：AbortSignal 透传 + 超时显式化留一期（提示走既有 notice/局部态）；不重建 `publishConflict` 渲染位 | ⚠️ **修订**：① `watchPublishOutcome` **显式捕获** abort Error（消除 `stores/workspace.ts:452` `void` 调用下的 unhandled rejection）；② 超时/中止提示落**单次出口**（超时 1 次 / abort 1 次），**禁放 `onTick`**；③ 其余（收窄边界、R-94 行尾注记要求）不变 | T7（DSPro C2） |
| **D3** | 新文件 `frontend/src/utils/polling.ts`，导出 `createPollingController({intervalMs,run,shouldRun})→{start,stop,refreshNow,isPaused}`；hidden 停表（不取消在途）、visible 先 `refreshNow` 一次再重置节奏（不补偿错过 tick） | ⚠️ **修订**：① 接口扩展为 **`{ intervalMs, fallbackIntervalMs, run, shouldRun }`**；② 错峰 0~250ms **作用于回可见补刷本身**；③ `refreshNow` × `loading`/`inFlight` 短路口径（dirty flag 收尾补发）**写进 controller 注释**；④ 四成员清单与 hidden/visible 语义不变 | T1 / T2 / T4 |
| **D4** | 新增 `frontend/src/utils/task-events.ts` 管 `EventSource` 生命周期（上限/回收/降级），订阅状态字段挂 `stores/task.ts` | ⚠️ **修订**：① **卸载出口 = `TaskCenterView.onBeforeUnmount` 显式调 store action 关闭全部 `EventSource`**（state 在 store 跨路由存活，不显式调用则卸载钩子只停 timer）；② `onFrame` 派发补 **close-gate**（close 后迟到帧丢弃、不触发 `refreshNow`）；③ `api/tasks.ts:105-115#openTaskEvents` 现有唯一调用方 `utils/job-watch.ts:57` 不迁移、仅补 `onerror` 的边界不变 | T5（GLM C5 + DSPro C3） |
| **D5** | `EventSource` 上限 = **3**（与当前页在跑任务数取小者）；四出口同步 `close()`；订阅数暴露为 store 可观测字段 | ⚠️ **修订**：① 淘汰策略钉死 = **按 `taskId` 稳定排序取前 3**，**第 4+ 个不订阅 SSE（靠列表条件轮询兜底）**；② 论据改写为「**防御性上限 + 可观测计数**」，**删除**"同域 6 连接保护 AI 通道"**主论据**，明写真实放大源 = `job-watch` 漏 `dispose`（R-14 面，已由 D12+T6 治理）；③ 上限 3 值本身、四出口、可观测字段不变 | T8 / T10（DSPro C4/B4） |
| **D6** | （草案已按上一轮 K3 C1 修订）`channel==='realtime'` **不停表清零、降为 30s 保底间隔**；`'polling'` 恢复 3s；首帧与 `onopen` 后各拉一次权威快照；`onProgress` 不改 | **不变**。关联：保底 tick 承载机制按 T1 钉死（单 interval 动态切换）；口径按 T9 改写为"降频 90% 而非归零" | T1 / T9 |
| **D7** | `refreshNow` 重置 interval 起点（防抖语义），写进注释与验证判据 | **不变**。关联：其被 `loading` 挡掉时"重置也丢失"的口径由 T2 的 dirty flag 统一覆盖 | T2 |
| **D8** | （草案已按上一轮 K3 C1 修订）任务级通道**无周期心跳，T 不存在** ⇒ **不设帧新鲜度阈值**；假死发现依赖 **30s 兜底轮询 + `onerror`/`readyState`** | **不变**。补注具体行（`app.task.sse` 段 `:191-203`、键 `:202`/`:203`）；第 0 步观测留证（≥120s 空转任务帧记录）维持"非门禁留痕" | T3 / T15-2 |
| **D9** | 事件路径用 **dirty flag + 收尾补发**（R3-4 建议案采纳） | ⚠️ **修订（扩口径）**：dirty flag 语义**统一覆盖三路径** —— ① SSE 事件帧路径；② 回可见 `refreshNow` 路径；③ `JOB_DONE` 触发刷新路径（`utils/job-watch.ts:71`，与 tick 共用 `inFlight`）；语义写进 controller 注释 | T2（GLM C4 + DSPro S3/S6） |
| **D10** | 停表谓词 `hasActiveJob` 以**列表行（筛选后）**为准、纳入 `filterStatus` 口径；另补 **30s 长周期保底 tick** | ⚠️ **修订**：保底 tick 机制**钉死 = 单 interval 动态切换**（非双 controller 实例）；接口承载 = D3 扩展的 `fallbackIntervalMs`；谓词口径与谓词锚点不变 | T1（GLM C2 + DSPro C5） |
| **D11** | 回可见苏醒**错峰 0~250ms 随机延时**（R2-1） | ⚠️ **修订**：错峰**作用于回可见补刷本身**（非仅 interval 重置）；T2②/T2⑤ 统一为"假时钟推进 ≤250ms 窗口内观测恰好 1 次 `run`" | T4（GLM C3） |
| **D12** | N-1 一期顺手收：`beginWatchJob` 按 `kind` 回收旧 watcher（`Map<kind,dispose>`），Export 侧 `:489-495` 为蓝本 | ⚠️ **修订（强化）**：**同步给 `utils/job-watch.ts#syncSnapshot` 在 `await jobsApi.getJob` 之后补 `disposed` 复检** —— dispose 后在途快照不写回 `onSnapshot`、不触发 `onFinal`；仅按 kind 回收（清 timer+source）**不足**。T7 扩断言锁在途写回 | T6（DSPro C1） |
| **D13** | N-2（`refresh_defs`/`refresh_data` 空转）**不并入本批**，维持挂账 | **不变**。亲验：`stores/workspace.ts:499-506`（`refresh_defs`）、`:507-513`（`refresh_data`）已由 W-09 改为**如实陈述**（"当前没有任何页面订阅该事件并重新拉取数据"） | — |
| **D14** | 施工第 0 步 = **测改动前基线**（无在跑时 30s 请求数、有在跑时 30s 请求数、`realtime` 下 jobs 轮询频次），实测值回填施工报告 | **不变**。追加：第 0 步同时**复核引用 keep-alive 实测**（一行 grep + 结论，不重复调查） | T5 |
| **D15** | 文案同步：`views/TaskCenterView.vue:8` 改条件口径；同文件 `:151` 注释「4s 自刷…」一并改实 | **不变**。补：文案须同时交代 **30s 保底口径**（草案已提，亲验 `:8` = `· 列表每 4 秒自动刷新{{ lastLoadedText }}`【实测】） | — |
| **D16** | 一期红线不变：后端零改动（`git status backend/` 空为判据）、不新增 SSE 事件类型、不修 N+1、不改 `api/sse.ts` 与 `stores/ai.ts` 计时器、不改 `SpreadGrid`/`excel`/`frontend-tools` 定时器、`visibilitychange` 不扩展到 SSE 连接本身 | **不变**（10 条红线全量）。补注：§5-6 的锚点按 T3 订正（`application.yml:191-203`）；§5-3 的"不修后端 N+1"理由锚点 = `docs/M2-排期计划.md:280` 明文（每 tick 1+10×2=21 次查询） | T3 / T15-1 |

**终态汇总**：D1/D6/D7/D8/D13/D14/D15/D16 = **8 条不变**；D2/D3/D4/D5/D9/D10/D11/D12 = **8 条被条件修订**（全部为"补充语义 + 钉死机制"，**无一条被推翻或缩面**）。

---

## 3. 被驳回方案与理由

| 编号 | 方案 | 提出方 | 裁决 | 理由（终态） |
| --- | --- | --- | --- | --- |
| **T11** | realtime/polling 切换加**滞回/debounce**（避免网络抖动导致 3s↔30s 频繁切换） | DSPro S1（建议级） | **驳回** | ① 最小面约束：滞回需额外状态机（抖动窗口 + 迟滞阈值 + 与 `refreshNow` 重置语义的交互）；② 切换仅在 `channel` **状态迁移**时发生（`utils/job-watch.ts:76-84`：`onopen`→realtime ／ `onerror && readyState !== OPEN`→polling），非每 tick 判定，频率本就低【推断】；③ 最坏情形仅是"多切几次"，每次只改一个间隔常量，无资源泄漏路径【推断】。若实测出现频繁切换，作二期观察项再评估。 |
| **T12** | `createPollingController` 的 `visibilitychange` 收敛为 **module 级单例广播 + 每实例订阅**（避免 job-watch 多实例下监听器线性放大） | DSPro S2（建议级） | **驳回** | ① 稳态实例数小：`stores/task.ts` 1 个（模块级单例 timer `:58`）＋ 每 job watcher 1 个（`utils/job-watch.ts:106`；一期已由 D12+T6 治同 kind 累积；Import 侧最多 3 种 kind 并存）＋ AiPanel 1 个 ⇒ **≤5 个监听器**，放大面可忽略【推断】；② module 级广播引入全局态 + 订阅表管理（注册、卸载竞态、广播顺序），与 T11 同源的复杂度考量；③ 每实例自挂监听器语义更局部、controller 单测无需构造 module 单例。 |

**半采纳 / 降级项**（非驳回，但降口径）：

| 编号 | 原方案 | 终态 |
| --- | --- | --- |
| **T9 后半** | 多页签（N 页签）下 30s 保底残余请求量**实测量化**，并要求写入施工报告 | **降为建议**：施工报告方便则附，**不作验收门槛**（多页签是既有放大面，一期只降基线频率；量化成本高于收益） |

---

## 4. 推翻条件（出现下列任一条证据时应推翻/重订本卡）

> 本卡全部结论建立在 **HEAD `6c9c699` 的实读事实**与**会审两方的实证锚点**之上。下列任一条被证伪，对应结论即失效；**由 K3 复裁，不由执行者自行改判**。

| # | 触发证据 | 被推翻的结论 | 应改走的方向 |
| --- | --- | --- | --- |
| P1 | 施工第 0 步或后续实测发现**任务级 SSE 通道存在周期性心跳**（`TaskSseService.java:88` 之外出现第二个任务级 `HEARTBEAT` 发布点，或 `write-timeout: 15s` 使空转连接周期性产生帧） | **D6/D8** 的事实前提（"无周期心跳 ⇒ 不设帧新鲜度阈值"） | 回到"帧新鲜度阈值"路线；realtime 可退回**停表清零**；T5⑤"任意长度无帧不降级"断言作废 |
| P2 | 全仓复核出现 `keep-alive` / `keepAlive` / `onBeforeRouteLeave` / route 级组件缓存 | **T5** 的"路由切换必然触发 `onBeforeUnmount`"（本次实测 0 命中【实测 V6】） | D5 卸载出口须补 `onActivated/onDeactivated` 钩子；T5 断言改为"离开视图即归 0"（不依赖卸载） |
| P3 | 实测某目标浏览器同域 `EventSource` 并发上限 **< 5**（非通行 6） | **D5 上限 3** 与 job-watch/AI reattach 的共存余量 | 上限下调并按新上限重排；T8 淘汰策略与 T3① 数字全改 |
| P4 | 全仓出现**第 3 个及以上** `new EventSource` 构造点（本次实测 2 处：`api/tasks.ts:106` / `api/sse.ts:552`【实测 V7】） | **D5 四出口穷尽性**与连接预算 | task-events 上限/淘汰策略/退出出口须重排；§4.3-3 反查面扩大 |
| P5 | `stores/task.ts` 的 `loading` 守卫语义被改动（现唯一置位 `:117` / 唯一复位 `:127`）或 `utils/job-watch.ts` 的 `inFlight` 守卫被移除 | **T2** 的"三路径共用守卫生效、需 dirty flag 补发"前提 | dirty flag 可能不再必要（若守卫被移除）或需重定落点；T2/T4 断言重写 |
| P6 | 施工第 0 步实测 E2E 基线 PASS 数与三方记载（批 3③a/③b = 28；`docs/M2-排期计划.md:347` = 29；`ci.yml:108/212` = 29 命名）**都不同且为下降** | **T9/§4.0 的"不钉死基数"口径** | 须重订门禁（可能是新增用例未计数或真回退），不钉死口径要加"不得低于某绝对下界"约束 |
| P7 | 用户/业务方明确裁示"止血 = 归零"优先于"假死自愈面" | **T9** 与 **D6** 的"降频 90% 而非归零" | realtime 改回停表清零；§4.3-4 判据改"恰好 0 次"；§4.3-5 假死反查判据须换机制（如 `navigator.onLine` 监听） |
| P8 | T1 断言①（`shouldRun=false` 持续 30s → `run` 恰好 1 次）在**单 interval 动态切换**实现下无法被假时钟机械锁住 | **T1** 的机制选择 | 退回**双 controller 实例**方案（此时 T12 对"监听器放大"的驳回理由需重新评估） |
| P9 | `api/jobs.ts` 的 abort 判定位置（现 loop-top `:55-57`，在 `getJob` `:58` / `sleep` `:66` 之前【实测】）被挪到其后 | **T7** 的"abort 单次出口 + 一次 post-abort 写回"口径 | 超时/abort 提示次数口径须重订；T6①/② 断言重写 |
| P10 | 被 §5 登记为"不动"的定时器（`api/sse.ts:552`、`stores/ai.ts:62/:52`、`SpreadGrid.vue:107/:137-140/:86/:176-183`、`excel.ts:847`、`frontend-tools.ts:383-408`）出现新增或语义变更 | **§1 总账**"前端 5 条 + 后端 1 条"的前提 | 总账与可见性纪律覆盖面重算 |

---

## 5. 验证用例清单（对应 T1~T9 的断言/验收项）

> 机械项 = 可在 `yarn test` 内机械判定；人工项 = 需 DevTools/浏览器（按 T13-3 附截图或 HAR 留档）。
> **全部断言在施工卡-定稿 §3/§4 中逐条承载**，本表为决策卡侧的对照总览。

| 对应 | 断言/验收项 | 落点 | 机械/人工 | 标签 |
| --- | --- | --- | --- | --- |
| **T1①** | `shouldRun=false` 持续 30s（假时钟）→ `run` **恰好 1 次**且以 30s 为周期 | `tests/polling-controller.spec.ts` | 机械 | 【推断】待实测 |
| **T1②** | 保底 tick 与 4s 条件 tick **不同 tick 双发** | 同上 | 机械 | 【推断】待实测 |
| **T1③** | 3s↔30s 切换瞬间**旧 timer 清除**（切换后首个 tick 距切换 ≥3s） | `tests/job-watch-channel.spec.ts` | 机械 | 【推断】待实测 |
| **T2①** | `hidden` → 不再 tick（假时钟推进不产生 `run`） | `tests/polling-controller.spec.ts` | 机械 | 【推断】待实测 |
| **T2②** | `visible` → **恰好补 1 次** `run`（在 ≤250ms 错峰窗口内观测） | 同上 | 机械 | 【推断】待实测 |
| **T2③** | **不补偿错过的 tick**（hidden 期间推进 N 倍周期，visible 后总调用数 = 1 + 后续实数） | 同上 | 机械 | 【推断】待实测 |
| **T2④** | `refreshNow()` **重置 interval 起点**：refreshNow 后原周期不立刻叠加触发 | 同上 | 机械 | 【推断】待实测 |
| **T2⑤** | 错峰延时落在 **0~250ms** 区间（注入 `Math.random` 可控桩）；**作用于补刷本身** | 同上 | 机械 | 【推断】待实测 |
| **T2⑥** | 在途不取消：`hidden` 期间已发出的 `run` promise 仍可 resolve；**被守卫挡掉的刷新标记待办并收尾补发** | 同上 + `tests/task-events.spec.ts` | 机械 | 【推断】待实测 |
| **T2⑦** | **start/stop 幂等**：重复 `start()` 不产生第二个 interval；重复 `stop()` 不抛错（对齐 `stores/task.ts:158-161` 语义） | `tests/polling-controller.spec.ts` | 机械 | 【推断】待实测 |
| **T3①** | 拟订阅 N>3 个任务 → 活跃实例 **≤3**；**第 4 个不产生实例** | `tests/task-events.spec.ts` | 机械 | 【推断】待实测 |
| **T3②** | 筛选/翻页/作业终态/卸载四出口各触发后，计数**单调下降**至 0 | 同上 | 机械 | 【推断】待实测 |
| **T3③** | 暴露字段读数与真实 `EventSource` 桩件计数一致（无泄漏）；**离开任务中心页后计数归 0** | 同上 | 机械 | 【推断】待实测 |
| **T3④** | 同 `taskId` 重复订阅不产生重复实例；**重订阅前后淘汰顺序稳定** | 同上 | 机械 | 【推断】待实测 |
| **T3⑤** | **close 后到达的帧不触发 `refreshNow`**（close-gate） | 同上 | 机械 | 【推断】待实测 |
| **T4①** | 在途 `load()` 期间事件到达 → 本次被挡掉但**标记待办** | 同上 | 机械 | 【推断】待实测 |
| **T4②** | 在途结束后**补发恰好 1 次**刷新（而非 0 次或 2 次） | 同上 | 机械 | 【推断】待实测 |
| **T4③** | 多次事件在途叠加 → 收尾仍只补发 1 次（**合并语义**） | 同上 | 机械 | 【推断】待实测 |
| **T4④** | **回可见 `refreshNow` 路径**撞在途守卫 → 同样落 dirty flag 并补发 1 次（D9 扩口径） | 同上 | 机械 | 【推断】待实测 |
| **T4⑤** | **`JOB_DONE` 触发刷新路径**（`utils/job-watch.ts:71`）撞 `inFlight` → 同样补发 1 次 | `tests/job-watch-channel.spec.ts` | 机械 | 【推断】待实测 |
| **T5①** | `onopen` → realtime 时兜底间隔**切换为 30s 保底**（非清零）：假时钟推进 30s 恰好 1 次拉取 | `tests/job-watch-channel.spec.ts` | 机械 | 【推断】待实测 |
| **T5②** | `onerror` 且 `readyState !== OPEN` → 回 `polling` 且 **3s 恢复** | 同上 | 机械 | 【推断】待实测 |
| **T5③** | 首帧与 `onopen` 后**各拉一次**权威快照（合计 2 次，不多不少） | 同上 | 机械 | 【推断】待实测 |
| **T5④** | `onFinal` 走 `dispose`：timer 与 source 同时释放 | 同上 | 机械 | 【推断】待实测 |
| **T5⑤** | **不设帧新鲜度阈值**：任意长度"无帧"不触发降级，降级只由 `onerror`/`readyState` 驱动 | 同上 | 机械 | 【推断】待实测 |
| **T6①** | `signal.aborted` → 抛可识别错误（`api/jobs.ts:55-57` 语义保留） | `tests/wait-job-final.spec.ts` | 机械 | 【推断】待实测 |
| **T6②** | 超时分支**不再**返回"看起来正常"的作业对象（改可判别结果） | 同上 | 机械 | 【推断】待实测 |
| **T6③** | 调用方拿到非终态时**不静默清空**发布冲突态；**提示恰好 1 次** | 同上 | 机械 | 【推断】待实测 |
| **T6④** | **abort 无 unhandled rejection**（`stores/workspace.ts:452` `void` 调用点） | 同上 | 机械 | 【推断】待实测 |
| **T6⑤** | **300s 超时提示恰好 1 次**（禁落 `onTick`） | 同上 | 机械 | 【推断】待实测 |
| **T7①** | 同 `kind` 连启 2 次 → 旧 `dispose` 已被调用（`dispose` 计数 1 次） | `tests/import-watch-recycle.spec.ts` | 机械 | 【推断】待实测 |
| **T7②** | **同 kind 回收后旧 job 在途 resolve 不写回新 job 状态、不弹旧终态提示** | 同上 | 机械 | 【推断】待实测 |
| **T7③** | 不同 `kind` 并存时互不回收（check/import/publish 各自独立） | 同上 | 机械 | 【推断】待实测 |
| **T7④** | 卸载时全部回收 | 同上 | 机械 | 【推断】待实测 |
| **T8①** | 淘汰顺序按 `taskId` 稳定排序；重订阅前后顺序一致 | `tests/task-events.spec.ts` | 机械 | 【推断】待实测 |
| **T9①** | 施工报告"止血"口径 = 「降频 90%（3s→30s）而非归零」 | 施工报告 | 人工（文书） | 【推断】待实测 |
| **T9②** | 多页签残余请求量（**建议附，不作门槛**） | 施工报告 | 人工 | 【推断】待实测 |
| **门禁** | `yarn test` `≥154` / `≥12 spec` / **0 failed**；`yarn typecheck` exit 0；`yarn build` exit 0；`git status backend/` 空；E2E FAIL=0 / GFSKIP=0 / PASS ≥ 施工前实测基线 | §4.0 | 机械 | 基线【实测】 |
| **人工** | §4.1-0 第 0 步基线；§4.1-2/-3/-4/-5/-6；§4.2-1/-2/-3/-4；§4.3-1/-2/-3/-4/-5/-7（**附 Network 截图/HAR**） | §4 | 人工 | 【推断】待实测 |

---

## 6. 署名节（DC-19）

| 项 | 值 |
| --- | --- |
| 本件产出 | `<SCRATCH>\决策卡-轮询一期.md`（本文件）＋ `<SCRATCH>\施工卡-定稿.md`（配套） |
| 执行者 | **`agent-563`**（子智能体） |
| 派发方 | K3 会话 `session_6d2ca6dd-42c9-4200-8024-db261420104f`（工作区 `wd_temp_38dcbf7ced2f`） |
| 授权来源 | **K3 指挥官派单** · 轮询治理一期（决策卡 + 施工卡定稿，2026-10-12） |
| 决策署名 | **决策由 K3 汇总代签** —— 本件为 K3 终裁之承载件，**执行者无裁决权**；凡本件与 K3 复裁冲突，以 K3 复裁为准 |
| 实际运行模型 | **`DeepSeek/deepseek-flash`** |
| 模型取证方式（wire 实证，非照抄卡头） | 取 `llm.request` 的 `modelAlias` 字段 —— 文件 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_6d2ca6dd-42c9-4200-8024-db261420104f\agents\agent-563\wire.jsonl`（**145 行**）；`grep -o '"modelAlias":"[^"]*"' wire.jsonl \| sort \| uniq -c` → **15 命中，全部为 `"DeepSeek/deepseek-flash"`**（无混杂）；`llm.request` 计 **14** 条，`modelAlias` 同上。样本行（首条 `llm.request`）：`{"type":"llm.request","agentId":"agent-563","kind":"loop","provider":"openai","model":"deepseek-flash","modelAlias":"DeepSeek/deepseek-flash","thinkingEffort":"high","thinkingKeep":"all","maxTokens":393216,…, "systemPromptHash":"bb5f824cbd5ee286499a461e0e4d5d95183dc1b9d90e5b01f70892bfb5745365","toolsHash":"62b6baf0f275073f0c3f5985e683e3e66d1e42dc3c3af162aeeb325c4b7a0288","messageCount":3,"turnStep":"0.1","time":1791765286287}`；另 `{"type":"profile.bind","agentId":"agent-563","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}` 佐证 agentId 归属 |
| 同一会话内其他执行者（供追溯） | `agent-552` / `agent-553` / `agent-554` / **`agent-555`（草案作者）** / `agent-556` / `agent-562`（wire 均在同目录 `agents/` 下） |
| 本件纪律声明 | 主仓库**全程只读**（未做任何 git 写操作、未改仓库任何文件）；未读任何 `.env`；未触碰 `<REPO_ROOT>` 与 `<REPO_ROOT>`（trae 线）；落盘限于 `<SCRATCH>\` |
| 已执行的验证命令（证据可复现） | `sha256sum` 草案与两份会审报告（三处逐字节核验）；`git rev-parse HEAD` + `git status --porcelain`（含 `backend/`）；`cd frontend && yarn test` → **12 spec / 154 passed / exit 0**；`yarn typecheck` → exit 0 无输出；前端 `awk`/`grep` 亲验 **50+ 处**锚点（`stores/task.ts` 369 行、`utils/job-watch.ts` 123 行全文读）；后端 `grep`/`awk` 亲验 `application.yml:186-230`、`TaskSseService.java`（232 行）、`JobWatchdog.java`（258 行）与四个 Runner / 两个 `JOB_DONE` 发布点；全仓 0 命中复核（keep-alive / 路由守卫 / `$reset` / HMR）；`agents/agent-563/wire.jsonl` 模型取证 |
| 未做的事 | 未启动前后端环境、未做浏览器 DevTools 实测 ⇒ §5 全部"请求数/连接数"运行时判据仍为**【推断】基线，待施工第 0 步实测回填**；未运行 E2E 桩（需桩上游 + Redis 容器）；未运行 `yarn build`（体积大、耗时） |
