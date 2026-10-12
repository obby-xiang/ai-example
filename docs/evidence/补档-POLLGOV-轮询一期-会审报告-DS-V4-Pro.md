<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\会审-轮询一期-DSPro.md`（26422 B，sha256 `45122549d9822329dd6448942475dcf0a5d3adc9e671653fcafd687af3c2a3e3`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 会审报告 · 轮询治理一期施工卡草案 · 反方（DS V4 Pro）

> 评议对象：`<SCRATCH>\施工卡草案.md`（62476 B / 443 行 / sha256 `e3dfacf011f78e4dcaad5b601a3d9395ba20fc14d266d6452815a8826a2fe0e9`）
> 核验结论：**sha256 / 字节 / 行数三者与任务卡一致，核验通过**（非"字节不符停手"情形）。
> 主仓库（只读）：`<MAIN_REPO>`，HEAD=`6c9c699aa680297ecf0a83ec39eeae8716b68431`，工作树干净（`git status --porcelain` 零输出）——均【实测】。

---

## 0. 结论先行

**带条件可施工。**

草案整体质量高、可施工：锚点逐处亲验、K3 裁定留档（实体不丢档）、TDD 跑道现成（12 spec / 154 tests 全绿）、红线清晰可验收。但存在 **3 处草案未覆盖的真实缺陷**（D12 回收挡不住在途写回、D2 的 abort 会成为 unhandled rejection、task-events 订阅状态挂在 store 上导致"卸载出口"跨层悬空），须以 **6 条必做条件（C1~C6）** 在施工时落地方可验收；另有 6 条建议（S1~S6）。

---

## 1. 攻击面推演（逐条给触发路径与后果）

### A. 轮询控制器（步 2 `createPollingController`）

**A1 · refreshNow 与 interval tick 竞态（双发）**
- 触发路径：`refreshNow()`（回可见 / 事件 / 回滚）与 4s tick 在同一时刻都调 `load()`。
- 后果：**不会真正双发**——`loading` 短路（`task.ts:114-116`，唯一置位 `:117` / 唯一复位 `:127`）挡掉后到者【实测】。但被挡者**意图被静默丢弃**：若 `refreshNow` 被丢 → D7 的"重置 interval 起点"未发生 + 补刷丢失；若 tick 被丢 → 仅延迟 4s 自愈（可接受）。**关键**：草案 D9 的 dirty flag 只写明覆盖"事件路径"，`refreshNow`（回可见路径）撞 `loading` 的丢弃**未纳入** → 见 C/S3。
- 判定：可控，但需把 `refreshNow` 的丢弃语义写进 D9 或明确"回可见撞在途不补刷"。【推断】

**A2 · hidden→visible 高频切换抖动**
- 触发路径：快速反复切标签页（alt-tab 连打），每次 `visible` 触发 `refreshNow` + interval 起点重置（D7）+ 0~250ms 错峰（D11）。
- 后果：`loading` 守卫使请求收敛（不爆接口），但 interval 起点被反复后推 → 4s tick 可能长期不触发，仅靠每次 `visible` 的 `refreshNow` 维持；最坏是"切得比 4s 快就永远在补刷、不走周期"。低危，但 T2 的"总调用数"断言须覆盖此抖动而非只测单次隐藏/苏醒。【推断】

**A3 · 多组件同时持有 controller 的归属**
- 触发路径：task 列表（模块级单例 `task.ts:58`）、job-watch（**每被跟踪作业一个实例**）、AiPanel（1 个）各自 `createPollingController`。
- 后果：D3 写死 controller **内部自挂 `document.addEventListener('visibilitychange')`** → N 个实例 = N 个监听器。Import 多实例场景（`beginWatchJob` 每作业一个 watcher，`:887` 只 push 不回收【实测】）会让监听器随作业数线性增长。建议收敛为"module 级单例广播 + 每实例订阅"，或至少声明监听器上界 → S2。【推断】

**A4 · 模块级单例与多页签共存**
- 触发路径：多浏览器页签各自独立 JS 上下文，Pinia store 是**每页签独立实例**，"模块级单例"只在单页签内成立；多页签 = 每页签独立 4s/30s 轮询同一后端。
- 后果：请求数 = N 页签 × 频率（30s 保底也 × N）。跨页签"他端启动作业"靠 30s 保底发现（R1-1），最坏 30s 才见新任务。前端减负在多页签场景收益打折，但相对原 4s/3s 仍大幅下降。须在施工报告量化多页签放大 → 并入 C6。【推断】

### B. EventSource 生命周期（步 3）

**B1 · 上限 3 的抢占（第 4 个在跑任务）**
- 触发路径：当前页出现 5 个 RUNNING/PENDING 任务，`task-events.ts` 只给 3 个开 SSE。
- 后果：第 4/5 个任务无 SSE 订阅，进度靠 **4s 列表轮询**兜底（有在跑任务时 4s 不降，`hasActiveJob` 覆盖全体可见行），进度仍 ≤4s 新鲜 → 可接受。**但淘汰顺序未定义**（FIFO？LRU？按 taskId 稳定排序取前 3？）——淘汰策略不写死，T3 无法写死、翻页/筛选触发 close+重订阅时可能抖动。→ C4。【假设】

**B2 · 四出口之外的漏回收路径**
- 实测全仓 **0 命中**：无 `keep-alive`、无 `onBeforeRouteLeave`/`beforeRouteLeave`、无 `$reset`/`$dispose`、无 `import.meta.hot`【实测】。故"卸载"靠 `onBeforeUnmount`（`TaskCenterView.vue:354-356` / Import `:1114-1118` / Export `:1355-1357`），无 keep-alive 意味着路由切换即卸载，这是好消息。
- **但**：D4 把订阅状态挂 `stores/task.ts`（Pinia 单例，**跨路由存活，不随组件卸载**）。若"卸载出口"只是"TaskCenterView 卸载"而订阅状态在 store 里，则**离开任务中心页后 ≤3 条 EventSource 仍然存活**，除非 `onBeforeUnmount` 显式调 store action 关闭全部订阅。这个"跨层绑定"（store 持有、view 卸载触发关闭）草案未钉死 → C3。【实测 + 推断】

**B3 · close() 后 onmessage 迟到帧**
- `job-watch.ts` 已有 `disposed` 闸（`:58-60` 帧入口、`:52-54` emitChannel、`:87-89` syncSnapshot）【实测】。
- 新增的 `task-events.ts` 若无同款"已关闭闸"，`close()` 后到达的帧会触发 `refreshNow()`（脏读/无效刷新）。T3/T4 未锁"close 后迟到帧丢弃" → 并入 C3。【推断】

**B4 · 与 AI reattach 通道（api/sse.ts）同域连接挤压**
- 实测：AI 通道对话走 fetch POST（非 EventSource，`sse.ts:341-346`）；**只有 reattach 用 EventSource，且单次调用内 ≤1 条**（`open()` 唯一构造点 `:552`，每次迁移先 `close()`；settle 后 `settled=true` 不再重开）【实测】。任务通道 = 每被跟踪作业 1 条（`job-watch.ts:57`）。
- 结论：正常用法下 6 连接上限**不可达**（AI reattach 1 + task-events ≤3 + job-watch 1~2 ≈ 5~6）。**真正的放大源是 Import 的 job-watch 漏 dispose（R-14）**，而 D5 的"上限 3"只约束 `task-events` 池、**对 job-watch 池无效**（D12 另治）。⇒ D5 的"同域 6 连接"论证与真实风险源**错位**：上限 3 的价值是"防御性上限 + 可观测计数"，而非"保护 AI 通道"。建议 D5 理由改写为"防御性上限"，不要拿 6 连接当主要论据。【实测 + 推断】

### C. 条件轮询谓词（步 1）

**C1 · 筛选后列表为空但其它页有在跑任务 → 状态饥饿**
- R1-2（谓词纳入 `filterStatus` 口径，锁回归 T1③）+ R1-1（30s 保底）已覆盖【实测谓词锚点 `task.ts:68`】。但**跨页签**场景：本页筛选后无在跑行 → 本页降至 30s 保底，而另一页签向导在跑作业 → 本页最坏 30s 才见进度变化。可接受，但须明确"30s 保底是唯一跨页签发现面"。【推断】

**C2 · load() 防重叠 vs refreshNow 绕过条件的交互**
- `refreshNow` 绕过 `hasActiveJob`（条件），但**复用 `loading` 守卫**。撞在途 `load()` 时被丢弃（`:114-116` 短路、`load()` 自身短路）。D9 只覆盖"事件路径"，**回可见路径撞 `loading` 同样被丢**、且无 dirty flag → 补刷丢失。→ S3。【实测（守卫代码）+ 推断】

**C3 · 30s 保底 vs 4s 条件轮询的切换抖动**
- D10 的 30s 保底实现机制未钉：单 interval 动态切换（clear+set，切换有一 tick 空窗）还是双 interval 并行（有双发风险）。作业出现/消失瞬间切换点可能抖动，无滞回。**30s 保底 tick 自身无独立锁回归**（T1 锁谓词、T2 锁 hidden/visible，均不锁"全静止 30s 内 ≤1 次保底请求"）。→ C5 / S1。【推断】

### D. watchPublishOutcome / waitForJobFinal（D2 收窄后）

**D1 · AbortSignal 透传后 abort 在 `for(;;)` 内的退出保证**
- 实测 `jobs.ts:50-68`：abort 判定在 **loop-top `:55-57`**（`if (options.signal?.aborted) throw`），在 `getJob`（`:58`）与 `sleep`（`:66`）**之前**。⇒ abort 在"在途 getJob / sleep 期间"触发时**不会立即中断**：最坏延迟 ≈（getJob 网络延迟 + 1000ms sleep），且 `onTick`（`:59`）可在 abort 后**再写一次** `currentJobs`。**退出保证成立**（下一轮 loop-top 必 throw，无死循环），但**非立即**，且有一次 post-abort 写回。【实测】

**D2 · abort throw 的 rejection 去向（草案未提，关键）**
- 实测调用方 `workspace.ts:452` = `void taskStore.watchPublishOutcome(job.id ?? 0)` —— **`void` 丢弃 promise**【实测】。D2 给 `waitForJobFinal` 透传 `signal` 后，abort 触发 `throw new Error('等待作业终态已被取消')`（`:56`），该 rejection 经 `watchPublishOutcome`（无 try/catch）上抛到 `void` 调用点 → **unhandled promise rejection**。⇒ D2 必须显式捕获（`watchPublishOutcome` 内 try/catch，或改可判别返回值 + 调用方处理）。→ C2。【实测】

**D3 · 超时显式化走 notice 是否会刷屏**
- 超时分支 `:63-65 return job`（非终态、不抛）【实测】。D2 的"显式提示"若落在 `onTick`（每 1s）→ 300s×1s = **300 次提示刷屏**；必须落在**单次出口**（超时一次 / abort 一次）。→ C2。【推断】

### E. beginWatchJob 按 kind 回收（D12）

**E1 · 旧 watcher 的 onFinal 迟到回调误触新 job 状态（草案外，最关键）**
- 实测 `job-watch.ts:86-104` 的 `syncSnapshot`：`await jobsApi.getJob(...)`（`:92`）之后**无 `disposed` 复检**，直接 `options.onSnapshot?.(job)`（`:93`）+ 判终态后 `dispose()`（`:96`）+ `options.onFinal?.(finalJob)`（`:97`）。
- D12 按 kind 回收旧 watcher（调用旧 `dispose()`）**只能**清 `clearInterval` + `source.close()` + 置 `disposed=true`，**挡不住已经发出的在途 `getJob` promise**：resolve 后仍会写回 `onSnapshot`，并触发 `onFinal`。而 Import 的 `onFinal`（`ImportWizardView.vue:859-885`）内部 `refreshTaskSnapshot()` 读的是**当前** `taskId.value`【实测】→ 旧 job 的终态会**覆盖新 job 的状态/弹 ElMessage**。
- 结论：D12 的"按 kind 回收"是必要但不充分——**必须同步给 `job-watch.ts` 在 `await getJob` 后补 `disposed` 复检**，否则回收只清了 timer+source，清不掉在途写回。草案 D12/T7 均未触及此点。→ C1（最关键必做条件）。【实测】

### F. D6/D8（30s 保底 + onerror/readyState）

**F1 · 假死发现盲区（代理缓冲、半开连接）**
- 实测后端 `TaskSseService.publish` 摘除"死订阅者"（写超时 >15s / 写失败）时**只移出推送表、故意不 `complete()`**（类注释 `:46-50`；`sendFrame` 无 complete），socket 由容器 120s 写超时（`application.yml:6` `connection-timeout: 120000`）或 5m 生命周期收尾 ⇒ **半开窗口最长 ~2min**：服务端已停发、连接看似 alive【实测】。
- 前端任务通道**无 idle 看门狗**（对比 AI 通道 `sse.ts:481-494` 的 90s 帧间看门狗）【实测】；`onerror`/`readyState` 在"代理静默挂起"时可能**不触发**（连接未断）。
- 处置成立：**30s 保底轮询是唯一的假死发现面**（独立 HTTP GET，不受 SSE 半开影响）。D6 已保证 realtime 下 30s 保底**持续**跑（非清零），故盲区被兜住。**前提**：实现时 30s 保底**不得因 `channel==='realtime'` 而停**——D6 文字已满足，但 T5① 的"假时钟推进 30s 恰好 1 次拉取"必须真锁此语义。【实测 + 推断】

**F2 · realtime 下 30s 仍发请求是否违背"止血"初衷（量级）**
- 量化：3s→30s = **降 90%**（长作业 10min：200 次→20 次 GET）。"止血"从原案的"完全停表"退化为"**降频 90% 而非归零**"，换取假死自愈面，是合理取舍。但短作业（预检查 ~5s）下 30s 保底几乎不触发，realtime 实际只拉"首帧 + onopen"各 1 次（共 2 次），与原 3s（5s 内 1~2 次）同量级，无浪费。
- 须在施工报告把"止血"口径改写为"降频 90% 而非归零"，并量化多页签放大 → 并入 C6。【推断】

---

## 2. K3 立场 D1~D16 逐条评议

| 条目 | 立场 | 理由（要点） |
| --- | --- | --- |
| **D1** vitest 锁回归 + 禁新增依赖 | **支持** | 跑道现成【实测 12 spec/154】；"禁新增框架"下手写桩口径已明确（沿用 `setup.ts` 的 ResizeObserverStub 思路）。 |
| **D2** R-94 收窄（AbortSignal + 超时显式化留一期，不重建渲染位） | **有条件支持** | 收窄边界合理（R-94 已立项 `:286`）。**条件 C2**：abort throw 会经 `void watchPublishOutcome`（`workspace.ts:452`）成为 unhandled rejection；超时提示须单次出口（防 300 次刷屏）。 |
| **D3** 新文件 `polling.ts` + `createPollingController` 四成员 | **有条件支持** | 接口合理、路径不存在【实测】。**条件 S2**：每实例自挂 `visibilitychange` 在 job-watch 多实例下监听器线性放大，建议 module 级广播。 |
| **D4** 新文件 `task-events.ts` 管生命周期，状态挂 store | **有条件支持** | 定位准确。**条件 C3**：store 单例跨路由存活，"卸载出口"必须由 `TaskCenterView.onBeforeUnmount` 显式调 store action 关闭，否则离开页面 EventSource 存活。 |
| **D5** 上限 3 + 四出口 + 可观测 | **有条件支持** | 上限 3 合理但**论据错位**：同域 6 连接正常用法下不可达【实测】，真放大源是 job-watch 漏 dispose（D12 另治），上限 3 对 job-watch 池无效。**条件 C4**：淘汰策略（第 4+ 个任务的处置 + 稳定顺序）未定义。 |
| **D6** realtime 降 30s 保底（非清零）+ 首帧/onopen 各拉一次 | **支持** | 实测任务级通道无周期心跳（`TaskSseService` 仅 `:88` 一处 HEARTBEAT，无 `schedule/Timer`）【实测】；清零会失去唯一假死发现面。**建议 S1**：3s↔30s 切换加滞回防抖动。 |
| **D7** refreshNow 重置 interval 起点 | **支持** | 防"补刷+原tick"双发，T2④ 已锁。**补 S3**：撞 `loading` 丢弃时重置也丢失，需与 D9 对齐口径。 |
| **D8** 不设帧新鲜度阈值，假死靠 30s 保底 + onerror/readyState | **支持** | 实测无周期心跳、"无帧是正常态"，`max(2T,10s)` 无值可代。半开窗口（~2min）由 30s 保底兜住【实测】。前提 = 30s 保底在 realtime 下持续跑（D6 已满足）。 |
| **D9** dirty flag + 收尾补发 | **支持** | `loading` 短路挡掉不重试【实测 `task.ts:114-116`】，事件会丢。**补 S6**：`JOB_DONE` 触发的刷新与轮询 tick 共用 `loading`/`inFlight`，同样受短路影响，须同批纳入 dirty flag 语义。 |
| **D10** hasActiveJob 以筛选后列表行为准 + 30s 保底 | **有条件支持** | 谓词口径正确（T1③ 锁 `filterStatus`）。**条件 C5**：30s 保底 tick 自身无独立锁回归；实现机制（单/双 interval）未钉。 |
| **D11** 回可见错峰 0~250ms | **支持** | R2-1 齐射打爆接口，T2⑤ 已锁。 |
| **D12** Import 按 kind 回收旧 watcher（蓝本 Export） | **有条件支持（最关键）** | Import 无回收实锤（`:840-888` 全文无同 kind 查找，`:887` 只 push）【实测】。**条件 C1**：仅按 kind 回收不够，须同步给 `job-watch.ts` `await getJob` 后补 `disposed` 复检，否则旧 job 在途写回/onFinal 覆盖新 job 状态。 |
| **D13** refresh_defs/refresh_data 不并入 | **支持** | 面大于一期，W-09 已如实陈述，无紧急面。 |
| **D14** 第 0 步测改动前基线 | **支持** | 原卡 7~8 次/30s 系推断，必须实测回填。 |
| **D15** 文案同步 | **支持** | `:8`「列表每 4 秒自动刷新」与 `:151` 注释「4s 自刷：store 的 setInterval…」确在说谎/脱节【实测】。D10 生效后文案须交代 30s 保底口径（草案已提）。 |
| **D16** 一期红线 10 条 | **支持** | 判据清晰可验收（`git status backend/` 空等）。补一句：B1 的"判死动作链对步 3 事件触发刷新有效"是【推断】，建议步 3 验收时顺带实测确认。 |

---

## 3. 锚点抽验（≥8 条，逐条给「锚点 | 预期 | 实测 | 判定」）

> 机械活下沉：以下锚点由子智能体 agent-559/560/561（model=DeepSeek/deepseek-flash，见 §6 自证）实测 Read/Grep，行号按 HEAD `6c9c699` 亲验；其中 3、12、13 由本执行者亲自 Read/Grep。

| # | 锚点 | 预期 | 实测 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | `stores/task.ts:32/58/162/157-168/170-176/114-116` | `TASK_AUTO_REFRESH_MS=4000` / timer 声明 / `setInterval` / start/stopAutoRefresh / loading 短路 | 逐字命中（`loading` 唯一置位 `:117`、唯一复位 `:127`） | **一致** |
| 2 | `stores/task.ts:294-321` watchPublishOutcome | `:298` 调 `waitForJobFinal` 未传 `signal`；`:308-310` 非 FAILED 静默 `publishConflict=null` | 命中；另有预期外前置守卫 `:295-297 if (!jobId) return` | **一致** |
| 3 | `api/jobs.ts:46/50-68/55-57/63-65/66` | `signal?` / `waitForJobFinal` / abort throw / 超时 `return job` / 1s sleep | 逐字命中；abort 在 loop-top、超时非终态返回【本人亲验】 | **一致** |
| 4 | `utils/job-watch.ts:21/46/76-78/79-84/96/106-110/121` | 常量 3000 / watchJob / onopen→realtime / onerror→polling / dispose / setInterval / 首拉 | 逐字命中；**另发现** `syncSnapshot` `await getJob` 后无 `disposed` 复检（新缺口 E1） | **一致**（+新缺口） |
| 5 | `api/tasks.ts:105-115` openTaskEvents | 仅 onmessage + try/catch，无 onerror/close/最大时长 | 命中，无 onerror | **一致** |
| 6 | `ImportWizardView.vue:306/840-888/887/1115-1116` | stopWatchers / beginWatchJob / push 无回收 / 卸载回收 | 命中；`:840-886` 无任何同 kind 回收 | **一致**（坐实 R-14） |
| 7 | `ExportWizardView.vue:234/489-495/723-728` | stopWatchJob / 改绑回收 / 单实例先停旧 | 命中（D12 蓝本有效） | **一致** |
| 8 | `TaskSseService.java:73/87-88/92-101`（无周期心跳） | subscribe / 首帧 HEARTBEAT / onTaskChanged；无 schedule/Timer | 命中；`heartbeat` 仅 `:88` 一处发布点，无周期调度 | **一致**，但**路径漂移**：实为 `configmgr/task/service/TaskSseService.java`（非 `job/service`），草案用 `...` 省略未暴露，施工方按 job 包找会 404 |
| 9 | `application.yml:191-198` app.task.sse（write-timeout 15s / emitter-timeout 5m / 无 heartbeat） | 键在 191-198 | 键值**在 `:202`/`:203`**，`:191-198` 仅注释头；"无 heartbeat 键"结论为真；`job.watchdog` 块实为 `:221-223`，`max-execution-time: 30m` 在 `:224`、`heartbeat-interval: 30s` 在 `:220` | **漂移 2~4 行**（结论面全真） |
| 10 | `JobWatchdog.java` 258 行 / `:114-123` / `:159-160` | 258 行 / @PostConstruct start / scheduleWithFixedDelay / scan | `wc -l`=258 命中；`scan()` 起 `:160`，`findByStatusIn(IN_FLIGHT)` 在 `:161` | **一致**（findByStatusIn 差 1 行） |
| 11 | `types/sse.ts:337/340-347/350-353` | 四事件类型 / TaskChangedPayload 五字段 / JOB_DONE 仅 {jobId,status} | 逐字命中 | **一致** |
| 12 | `types/job.ts` isJobFinal | 终态 = COMPLETED/FAILED/CANCELLED | 命中【本人亲验】 | **一致** |
| 13 | 漏回收路径（keep-alive / 路由守卫 / $reset / HMR） | 草案 D5 四出口之外应无其它出口 | 全仓 **0 命中**：无 keep-alive、无 onBeforeRouteLeave、无 $reset/$dispose、无 import.meta.hot【本人 Grep】 | **一致**（但引出 C3 跨层问题） |
| 14 | `TaskCenterView.vue:151` 注释 | D15 需改的"4s 自刷…"注释 | 命中原文「4s 自刷：store 的 setInterval，tick 前查 loading 防重叠（deepseek 蓝本形态）」【本人 Read】 | **一致** |

---

## 4. 必做条件（C1~C6，逐条可执行可验收）

**C1（D12 强化，最关键）**：`job-watch.ts` 的 `syncSnapshot` 在 `await jobsApi.getJob` 之后补 `disposed` 复检，使 dispose 后的在途快照不再写回 `onSnapshot`、不触发 `onFinal`。
- 验收：T7 增补断言"同 kind 回收后，旧 job 在途 getJob resolve **不**写回新 job 状态、不弹旧终态 ElMessage"（现有 T7 只锁 dispose 计数 1 次，不锁在途写回）。

**C2（D2 abort 语义）**：`watchPublishOutcome` 对 `waitForJobFinal` 抛出的 abort Error 显式捕获（try/catch 或改可判别返回值），消除 `workspace.ts:452` 的 `void` 调用下的 unhandled rejection；超时/中止提示落在**单次出口**（超时一次 / abort 一次），**禁止放入 `onTick`**。
- 验收：T6 增补"① abort 时调用方无 unhandled rejection；② 300s 超时提示恰好 1 次"。

**C3（task-events 生命周期）**：订阅状态虽挂 store，但"卸载出口"必须由 `TaskCenterView.onBeforeUnmount` 显式调 store action 关闭全部 EventSource；`task-events.ts` 的 `onFrame` 派发补 close-gate（关闭后迟到帧丢弃、不触发 refreshNow）。
- 验收：新增断言"离开任务中心页后 EventSource 计数归 0" + "close 后到达的帧不触发 refreshNow"。

**C4（上限 3 淘汰策略显式化）**：明确第 4+ 个在跑任务的处置（不订阅 SSE、靠 4s 列表轮询兜底）与淘汰顺序（建议按 taskId 稳定排序取前 3，避免翻页/筛选触发 close+重订阅时抖动）。
- 验收：T3① 扩"第 4 个不产生实例 + 淘汰顺序在重订阅前后稳定"。

**C5（30s 保底独立锁回归）**：锁死"全静止 30s 内 ≤1 次保底请求，且不与 4s 条件 tick 同 tick 双发"；并钉死实现机制（单 interval 动态切换 或 双 interval 互斥，二选一）。
- 验收：新增 T 项（或并入 T1/T2）锁保底 tick 频次与无叠加。

**C6（止血口径改写 + 多页签量化）**：施工报告把"止血"口径从"停表"改写为"**降频 90%（3s→30s）而非归零**"，并实测量化多页签（N 页签）下 30s 保底的残余请求量。
- 验收：施工报告出现"降频 90%"口径 + 多页签残余请求实测数。

---

## 5. 建议（S1~S6，非必做）

- **S1**：realtime/polling 切换加**滞回/debounce**，避免网络抖动导致 3s↔30s 频繁切换（T5 可增补"抖动序列下切换次数有上界"）。
- **S2**：`createPollingController` 的 visibilitychange 监听收敛为 module 级单例广播 + 每实例订阅，避免 job-watch 多实例下监听器线性放大。
- **S3**：明确 `refreshNow`（回可见路径）撞 `loading` 在途时的丢弃口径（纳入 D9 dirty flag，或声明"撞在途不补刷、下周期自愈"）。
- **S4**：补全 `TaskSseService.java` 全路径（`task/service`，非 `job/service`）；订正 `application.yml` 漂移锚点（`app.task.sse` 键 `:202-203`、`job.watchdog` `:221-223`、`max-execution-time` `:224`）。
- **S5**：统一口径注释——`api/tasks.ts:103` 写"按规格走 4s 轮询"、`job-watch.ts:21` 实为 3000ms，两处数字不对齐（注释级）。
- **S6**：把 `JOB_DONE` 触发的刷新（`job-watch.ts:71`）与轮询 tick 共用 `inFlight` 导致的"事件丢弃"（最多 3s 自愈）纳入 D9 dirty flag 语义，或至少在设计说明中写明"事件与轮询共用防重叠守卫，事件可能被轮询在途窗口吞掉一次"。

---

## 6. 草案外新缺口（单列）

1. **G1（D12 不充分，最重要）**：草案 D12/T7 只锁"回收 dispose 计数"，未覆盖 `job-watch.ts` 在途 `getJob` resolve 后的迟到写回/onFinal 误触新 job 状态（E1 实锤）。这是"按 kind 回收"方案的结构性盲区，不补 `disposed` 复检则回收形同虚设。
2. **G2（D2 的 unhandled rejection）**：草案未提"abort throw 经 `void` 调用点变成 unhandled rejection"（D2 实锤）。
3. **G3（订阅状态跨层悬空）**：D4 挂 store 但未钉"卸载出口由 view 的 onBeforeUnmount 触发"，离开页面 EventSource 存活风险（B2）。
4. **G4（上限 3 淘汰策略未定义）**：D5 未给第 4+ 个任务的处置与稳定淘汰顺序（B1）。
5. **G5（30s 保底无独立锁回归 + 机制未钉）**：D10 的核心语义（30s 保底）在 T1~T7 里没有任何一条直接锁它（C3）。
6. **G6（D5 论据错位）**：上限 3 的"同域 6 连接"论据与实测风险源（job-watch 漏 dispose）不符，会误导评审对风险权重的判断（B4）。

---

## 7. 署名节（DC-19）

| 项 | 值 |
| --- | --- |
| 执行角色 | 三方会审**反方**（红队：生命周期泄漏 / 竞态 / 降级路径 / 边界条件 / 过度设计） |
| 授权来源 | K3 指挥官派单 · 轮询治理一期三方会审卡（2026-10-12） |
| 本会话 id | `session_ecea153e-82a6-4d9a-a79b-142bf07b7757`（工作区 `wd_temp_38dcbf7ced2f`） |
| 实际运行模型 | **`DeepSeek/deepseek-v4-pro`**（与卡头一致，但以下为 wire 独立实证，非照抄卡头） |
| 模型取证（实证） | wire 取 `agents/main/wire.jsonl` 的 `profile.bind` 与 `llm.request` 的 `modelAlias`：<br>文件 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_ecea153e-82a6-4d9a-a79b-142bf07b7757\agents\main\wire.jsonl`；<br>`profile.bind` = `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","thinkingEffort":"high",...}`；<br>`grep -o '"type":"llm.request"[^}]*"modelAlias":"[^"]*"'` → **9 条全为 `"DeepSeek/deepseek-v4-pro"`**，且 `"model":"deepseek-v4-pro"`、`"provider":"openai"` 佐证【实测】 |
| 子智能体模型自证 | 机械活（锚点抽验/代码抽取）下沉 3 个子智能体，**显式钉 model=DeepSeek/deepseek-flash**，wire 实测：<br>`agent-559` → `profile.bind`/`llm.request` 全为 `DeepSeek/deepseek-flash`（7 条）；<br>`agent-560` → 同（10 条）；`agent-561` → 同（14 条）【实测】。互备 GLM-5.3-Flash 未启用；**未查看/引用/推测 GLM-5.3 会审方任何结论** |
| 纪律声明 | 主仓库全程只读（`git status --porcelain` 零输出）；未读任何 `.env`；未触碰 `<REPO_ROOT>` 与 `<REPO_ROOT>`（trae 线）；唯一写权限 = 本报告落盘路径 |
| 未做的事 | 未启动前后端环境、未复跑 `yarn test`（非本评关注点，采信草案 §9-A15 的【实测】声明并标注）；§4 所有"请求数/连接数"运行时判据仍为【推断】基线，待 D14 第 0 步实测回填 |
