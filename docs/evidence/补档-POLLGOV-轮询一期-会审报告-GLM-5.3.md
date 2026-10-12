<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\会审-轮询一期-GLM.md`（29688 B，sha256 `e1c712041dc6f5f25a508069e7311a12df90e80486d1f5f51fdca9b901f2b978`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 会审报告 · 轮询治理一期施工卡草案（刷新版 R-88）· GLM-5.3 复核方

> **署名（DC-19）**
> | 项 | 值 |
> | --- | --- |
> | 实际执行者模型 | **`Zhipu AI/glm-5.3`**（wire 实证，见附录 A；非照抄卡头） |
> | 授权来源 | K3 指挥官派单 · 轮询治理一期三方会审卡（2026-10-12） |
> | 本会话 id | `session_cf64f937-4010-4a06-aef9-7a19fad9f2b7` |
> | 会审对象 | `<SCRATCH>\施工卡草案.md`（62476 B / 443 行，sha256 见 §1） |
> | 机械活下沉 | 批量锚点抽验由 2 个子智能体执行，均**显式钉 model = `DeepSeek/deepseek-flash`**（互备 GLM-5.3-Flash 口径；wire 实证见附录 B），本方仅做关键判断与整合 |
> | 纪律声明 | 主仓库全程只读（无任何 git 写操作、未改任何文件）；未读任何 `.env`；未触碰 `<REPO_ROOT>` 与 `<REPO_ROOT>`（trae 线）；未查看/引用/推测 DS V4 Pro 会审方任何结论；唯一写权限 = 本报告 |

---

## 0. 结论先行

**带条件可施工。**

一句话：草案锚点质量与事实基础经独立复现**高度可靠**（亲验 + 子智能体抽验合计约 60+ 锚点，硬性不一致仅 1 处行号区间尾界漂移 + 2 处轻微口径注记，且均在草案自己声明的"已知漂移"或无代码影响范围之内），测试基线复跑通过（12 spec / 154 tests 全绿、typecheck exit 0），D6/D8 修订的事实前提（任务级通道无周期心跳）经全后端 HEARTBEAT 清单独立复核**成立**；但 D3 契约存在三个**语义真空点**（保底 tick 建模、错峰作用点、refreshNow 与 loading 短路交互），须以 5 条可执行、可验收的必做条件（§5 C1~C5）落实施工卡后方可开工。全部条件均为**文档/测试口径级**，不需要改动草案结构与改动面。

---

## 1. 核验门禁

| 项 | 结果 |
| --- | --- |
| 草案 sha256 | `e3dfacf011f78e4dcaad5b601a3d9395ba20fc14d266d6452815a8826a2fe0e9` —— 与卡头声明**逐字节一致**【实测】；62476 B / 443 行一致【实测】 |
| 主仓库 HEAD | `6c9c699aa680297ecf0a83ec39eeae8716b68431`【实测】，`git status --porcelain` 零输出 = 干净【实测】 |
| 测试基线复现 | `yarn test` = **Test Files 12 passed (12) / Tests 154 passed (154)**，Duration 2.85s；`npx vue-tsc --noEmit` = **exit 0 无输出**【实测·子智能体 agent-557 复跑，本方采信其原始输出】 |
| 后端零改动基线 | `git status --porcelain backend/` = 空【实测·子智能体 agent-558】 |

字节核验通过 ⇒ 依卡继续。以下断言除特别标注外均为本方亲验【实测】；凡子智能体执行处标注【实测·子验】；推导标注【推断】/【假设】。

---

## 2. 锚点抽验（本方亲验 16 处 + 子智能体两批 26 组）

### 2.1 本方亲验（关键判断所需文件全文 Read / 精准 sed 亲跑）

| # | 锚点 | 预期（草案） | 实测 | 判定 |
| --- | --- | --- | --- | --- |
| A1 | 草案文件本身 | sha256 `e3dfa…e0e9` / 62476 B / 443 行 | 逐字节一致 | 一致 |
| A2 | 仓库 HEAD / 工作树 | `6c9c699…` 全哈希、干净 | `git rev-parse HEAD` = 全哈希一致；porcelain 零输出 | 一致 |
| A3 | `stores/task.ts:32` | `TASK_AUTO_REFRESH_MS = 4000` | `:32` 逐字一致（全文件 369 行亲读） | 一致 |
| A4 | `stores/task.ts:58 / :157-168 / :162 / :170-176 / :114-116` | timer 声明 / startAutoRefresh 块 / setInterval / stopAutoRefresh / loading 短路 | 全部逐字命中；`:158-161` 另有幂等守卫 `if (autoRefreshTimer !== null)`（T2 漏锁面，见 D3 评议） | 一致 |
| A5 | `stores/task.ts:294-321`（`:298` 无 signal / `:308-310` 静默置 null） | watchPublishOutcome 现状缺陷 | `:298-306` 仅传 `intervalMs: 1000` + `onTick`，无 `signal`；`:308-310` = `if (job.status !== 'FAILED') { this.publishConflict = null; return }` | 一致 |
| A6 | `utils/job-watch.ts` 全部锚点（`:21/:46/:51-55/:57/:61-74/:76-84/:86-104/:96/:106-110/:112-119/:121`） | 见草案 §1.1-P2 | 全文件 123 行亲读，逐处命中；`:106-110` setInterval 确无 channel 判据；`:79-84` onerror 内确有 `readyState !== EventSource.OPEN` 判据；`:99-100` onError 仅透传不处置（新缺口 G2 依据） | 一致 |
| A7 | `types/job.ts:12` | JobStatus 含 PENDING/RUNNING | `export type JobStatus = 'PENDING' \| 'RUNNING' \| 'COMPLETED' \| 'FAILED' \| 'CANCELLED'` —— D10 谓词取 `RUNNING/PENDING` 与枚举口径吻合 | 一致 |
| A8 | `application.yml` `app.task.sse` 段 | 草案引 `:191-198`，只有 write-timeout/emitter-timeout、无 heartbeat 键 | 实际段 = **`:191-203`**（`task:` :191、`sse:` :192、`write-timeout: 15s` :202、`emitter-timeout: 5m` :203）；段内确无 heartbeat 键。**行号区间尾界漂移 5 行**（草案 `:198` 实为注释行） | **结论一致，行号尾界漂移** |
| A9 | `application.yml:220` | `heartbeat-interval: 30s`（job 段，与 SSE 无关） | `:220 heartbeat-interval: 30s` 命中；注记实起于 `:218-219`（草案写 `:219`，尾差 1 行） | 一致（尾差 1 行） |
| A10 | `TaskSseService.java:87-88` | 订阅时补发一帧 HEARTBEAT | `:87` 注释 + `:88 sendEvent(taskId, emitter, "HEARTBEAT", Map.of());` 亲验命中 | 一致 |
| A11 | 全后端 HEARTBEAT 命中 | 任务级唯一发布点；其余属 AI 通道 | 【实测·子验】字面 `"HEARTBEAT"` 仅 2 处：TaskSseService.java:88（任务级唯一）+ TaskSseServiceTest.java:145（测试）；ConfirmGate.java:77 = 2s、ResilientChatService.java:96 = 5s 均在 AI 域 | 一致（D6/D8 事实前提**成立**） |
| A12 | `JobWatchdog.java` | 258 行、`:114-123` start | `wc -l` = 258；`:114-115` @PostConstruct、`:117` 单线程 daemon、`:123` scheduleWithFixedDelay 亲验命中；判死链三件（ValidationIssue/JobTerminalWriter/JobCancellationRegistry）实存 | 一致 |
| A13 | `ImportWizardView.vue:840-888`（`:887` push 前无同 kind 回收） | D12 缺陷坐实 | 亲读：`:887` = `stopWatchers.push(dispose)`，此前**无任何同 kind 回收**（全文件 stopWatchers 命中仅 306/887/1115/1116【实测·子验】） | 一致 |
| A14 | `ExportWizardView.vue:489-495` | D12 蓝本 | 亲读：`stopJobWatchForTaskSwitch()` 先 `stopWatchJob?.()` 再清态，蓝本成立 | 一致 |
| A15 | `frontend/tests/setup.ts` | ResizeObserverStub 桩件先例（D1 可行性依据） | 亲读：7 行，`globalThis.ResizeObserver = ResizeObserverStub` —— globalThis 挂桩先例确存，EventSource 同款可行 | 一致 |
| A16 | 四 Runner + 终态写入点六处 | 草案 §9-A12 新值 | 【实测·子验】Export:271 / Import:216 / Precheck:360 / Publish:364 / JobTerminalWriter:86 / JobService:268 逐字命中 | 一致 |

### 2.2 子智能体批次摘要（机械抽验，model 实证见附录 B）

- **前端批（agent-557，16 组）**：全部一致。含 grep 基线六条（setInterval 3 调用点+2 类型声明、EventSource 2、visibilitychange 0、TASK_CHANGED 运行时消费 0、hasRunningJob/hasActiveJob 0、openTaskEvents 三命中）、package.json `:6-12` 六条 scripts、vitest 4.1.11/jsdom 30.1.2/@vue/test-utils 2.5.1、tests/ 扁平 12+1、`yarn test` 12/154 复跑、typecheck exit 0。仅 3 条口径注记（均不构成偏离）：① `workspace.ts:463` 是 `cancelJob` 行，refreshCurrent 为间接链（`:449` 才是直调）；② `TaskCenterView.vue:151` 另有一处 setInterval **注释**命中；③ `runWithBudget` 区间实为 `:383-408`（草案 `:383-410` 尾多 2 行）。
- **后端批（agent-558，10 组）**：行号与内容基本全中；不一致 3 项：① `TaskSseService.java` 实际路径在 `configmgr/task/service/` 子包（草案 §1.4 用 `...` 省略未指错，注记级）；② **`app.task.sse` 段实为 `:191-203`**（同 A8，唯一硬性行号漂移）；③ `docs/evidence/W3-...md:58` 引用现树不存在的 `TaskListView.vue`/`StepExport.vue` —— 但这正是草案 E4（N-4）**自己登记的已知漂移**，非草案错误。另 M2 表 `:280`/`:281` 行内旧锚（`startAutoRefresh:182-199`、`ImportWizardView.vue:748-795`）确实未更正 —— 亦属草案 E4 已知项并已要求施工报告同批更新，与草案陈述一致。
- **E2E 基数口径**：`docs/M2-排期计划.md:347` 记 29、`.github/workflows/ci.yml:108/212` 按 29 命名【实测·子验】—— 草案 §0-9 三方记载不一致的注记属实，"不钉死基数"裁定（C2）处理方式恰当。

**抽验总量与结论**：亲验 16 处 + 子验 26 组，硬性不一致 = 1 处（A8 行号尾界），且不影响该锚点承载的**结论**（段内确无 heartbeat 键）。草案 §9 自称"15 条全部亲验一致"基本可复现，其 A15 所引基线数字全部复现成功。

---

## 3. K3 立场 D1~D16 逐条评议（复核视角）

> 精读重点按任务卡要求落在 D1 / D3 / D5 / D6 / D8 / D10；其余条目给结论与理由。

### D1（vitest 锁回归 T1~T7）—— **支持（含 2 条补充条件，见 C2/C4 与建议 S1）**

- **跑道复现**：vitest 4.1.11 实装、jsdom 30.1.2、`tests/` 扁平 12 spec + setup.ts、12/154 全绿、typecheck exit 0【实测·子验复跑】。跑道声明全部属实。
- **假时钟可行性**：vitest 4 自带 `vi.useFakeTimers()`，T2/T5 的"推进 N 倍周期不产生调用""30s 恰好 1 次"断言可直锁【推断·基于 vitest 4 标准能力】。
- **EventSource 桩件可行性**：jsdom 30 **不内置 EventSource**，且 `api/tasks.ts:106` 是内部直接 `new EventSource`、非参数注入 —— 桩件只能挂 `globalThis.EventSource`（setup.ts 的 ResizeObserverStub 正是同款先例【实测 A15】）。可行，但 T3/T5 的桩件实现方式应显式写为 globalThis 替换，且步 3 给 `openTaskEvents` 补 `onerror` 时桩件接口要同步演进（施工注意点，非缺陷）。
- **document 桩件可行性**：jsdom 中 `visibilitychange` 可 `dispatchEvent` 模拟，但 `document.hidden` 默认 false 且只读，须 `Object.defineProperty` 覆写 —— 可测，实现注意点。
- **真锁得住语义的判断**：T1①②③（谓词三态）、T2③（不补偿错过的 tick，假时钟强断言）、T4③（合并语义）、T5③（"合计 2 次不多不少"）、T5⑤（任意长度无帧不降级 —— 直接锁死 D8）设计都是**可判真伪的硬断言**，不是烟雾测试。
- **漏测面（复核方独立发现）**：
  1. **保底 tick 本身无断言**：D10 的"全静止时 30s 保底 1 次"未出现在 T1/T2 任何一条 —— T2 只锁 hidden/visible/refreshNow，`shouldRun=false` 时的保底行为是真空（根因是 D3 接口未建模保底间隔，见 C2）。
  2. **T2 缺 start/stop 幂等断言**：现 `startAutoRefresh:158-161` 有 `if (autoRefreshTimer !== null)` 幂等守卫【实测 A4】，迁移到 controller 后该回归面无人锁（建议 S1）。
  3. T5① 锁"切 30s 后 30s 恰 1 次拉取"，但**切换瞬间的旧 timer 清除**（3s→30s 切换时不因旧 timer 残留多打一次）未显式断言 —— T5① 在特定时钟推进序列下可间接覆盖，建议补一句"切换后首个 tick 距切换时刻 ≥3s"。
- **判**：T1~T7 主体锁得住；补 C2/C4 口径后达"可机械回归"标准。

### D2（R-94 收窄）—— **支持**

锚点亲验一致（A5）：`:298` 确未传 signal、`:308-310` 确为静默置 null【实测】。收窄边界（AbortSignal + 超时显式化留一期、渲染位归 R-94）与 E5/E6 自洽；"`return job` 非终态返回"缺陷在 `jobs.ts:63-65` 亲验坐实【实测·子验】。R-94 行尾注记要求可执行、可验收。

### D3（createPollingController 契约）—— **有条件支持（本会审发现的核心条件项）**

任务卡点名三问，逐一复核：

1. **语义自洽性**：`hidden` 停表不清在途 —— 成立：`loading`（task.ts:114-116）与 `inFlight`（job-watch.ts:87-88）都在 promise 层，与 timer 生命周期正交【实测 A4/A6】。`visible` 先刷再重置节奏（防 R2-2 双发）与 D7 呼应，自洽。`isPaused` 反映 visibility 态合理。
2. **refreshNow 与 tick 竞态**：JS 单线程保证同步段不交错；async `run` 的 await 挂起点之间可插入 refreshNow 调用 —— 此时 refreshNow 的 run 会被 `loading` 短路。**真空点一**：草案未定义"refreshNow 被 loading 挡掉"时的语义 —— 事件路径有 D9 dirty flag 兜底，**回可见补刷路径没有**；若不处理，T2②"visible → 恰好补 1 次"在"补刷瞬间恰有在途 load"时实测为 0 次有效拉取（详见 C4）。
3. **错峰作用点**：D3 写"visible 先 refreshNow 一次再重置节奏"，D11 写"苏醒加 0~250ms 随机错峰" —— **真空点二**：错峰是作用于**补刷本身**（补刷延时 0~250ms，多轮询点打散）还是仅作用于 interval 重置？若仅后者，R2-1 齐射未解；若前者，T2②"恰好补 1 次"必须推进假时钟才可观测，两条断言的时钟口径需统一（详见 C3）。
4. **接口表达力**：**真空点三**：`{intervalMs, run, shouldRun}` 单一间隔无法表达 D10 的"4s 条件 tick + 30s 保底 tick"双节奏 —— 保底 tick 是第二个 controller 实例还是接口扩展 `fallbackIntervalMs`，草案未裁定，直接影响 T2 补断言的写法（详见 C2）。

以上三真空均为**施工前可一页纸裁定**的口径问题，不动结构 ⇒ 有条件支持。

### D4（task-events.ts 落点）—— **支持**

路径现不存在【实测·子验 grep openTaskEvents 命中清单旁证】；`openTaskEvents` 现有唯一调用方 job-watch.ts:57 不迁移、只补 onerror，边界清晰。

### D5（EventSource 上限 3 + 四出口回收）—— **有条件支持**

- **上限口径**：min(3, 当前页在跑任务数)。同域 HTTP/1.1 连接上限 6（浏览器通行值）【假设·通行浏览器行为】，AI reattach 占 1、任务级 ≤3、余量 ≥2，口径合理；§4.3-3 反查 AI 通道共存的设计正确。
- **四出口穷尽性亲查**【实测 A4 + 子验】：筛选/翻页入口 = `task.ts:131-154`（setPage/setSize/applyFilters 三函数全覆盖）✓；停表 `:170-176` ✓；卸载 = `TaskCenterView.vue:354-356` onBeforeUnmount ✓；作业终态 = JOB_DONE/onFinal 路径 ✓。**但有一个隐含前提未验证**：SPA 路由切换离开任务中心是否必然触发该组件卸载（即路由层无 `<keep-alive>`）——草案未查【假设：大概率无，但 D5 的"卸载出口"成立性系于此】（详见 C5）。
- **设计自洽性补充**【推断】：任务中心不可见期间 TASK_CHANGED 确无消费者，但跨页签/他端变更的可见性由步 2"回可见补刷"兜底，向导页作业由 job-watch 自带通道推进 —— 逻辑闭环，无需第五出口。

### D6（realtime 降 30s 保底而非清零 · K3 修订）—— **支持（修订版严格优于原案）**

- **事实前提复核**：任务级通道无周期心跳 —— 全后端 HEARTBEAT 清单独立复核成立【实测·子验 A11】；`app.task.sse` 段确无 heartbeat 键【实测 A8】。原案（清零）会使"连接活、无帧、无 onerror"的假死失去唯一发现面，修订必要。
- **30s 保底是否足以发现假死**：假死最坏暴露延迟 ≈ 30s（保底拉权威快照）+ 终态判定偏移。对比原案的"永不发现"，是严格改进；且 30s 与 D10 保底语义统一，认知负担低。**够用**【推断】。
- **onerror/readyState 在浏览器实态下可靠吗**：连接断开/服务端错误/代理切断/DevTools Block 均触发 onerror ✓；`readyState !== OPEN` 区分"自动重连中"与"已连上"的现有写法（job-watch.ts:79-84 亲读）语义自洽，重连成功后 onopen 切回 realtime，闭环成立。**残余盲区**：TCP 半开（网络切换黑洞）可能既无 onerror 也无帧 —— 此时靠 30s 保底轮询拉快照；若整个网络都断，保底轮询也会失败，而 `onError` 现状只透传不处置（job-watch.ts:99-100【实测 A6】）⇒ 断网期间 3s/30s 轮询持续打失败请求。此为**现状既有行为、非一期引入**，且被 max-execution-time/终态/卸载三重 dispose 限界 —— 登记为新缺口 G2（建议级），不构成反对。
- **更稳替代？** 主动 close+重建或 navigator.onLine 监听更激进，但撞"最小面"约束且收益边际 —— 一期取 onerror/readyState + 30s 保底是**合理工程解**，无必要更强。
- 措辞小瑕：「首帧与 onopen 后各拉一次」的"首帧"与现状构造时首拉（`:121`）的对应关系需在施工注释里写死（T5③ 已按"合计 2 次"锁定，可操作）。

### D7（refreshNow 重置 interval 起点）—— **支持**

写进注释与判据的要求明确；T2④ 假时钟可机械锁。与 R2-2 反查闭环。

### D8（不设帧新鲜度阈值 · K3 修订）—— **支持**

裁定依据链完整且经独立复核成立（A8/A11）：任务级无周期心跳 ⇒ `max(2T,10s)` 无值可代 ⇒ "距最近帧"在空转任务上无界 ⇒ 不可作降级判据。修订后降级触发面收敛为 onerror+readyState，T5⑤ 以"任意长度无帧不降级"反向锁死，**可测性反而优于原案**（原案阈值断言需构造帧时序，修订案只需断言"不动作"）。第 0 步观测留证（≥120s 空转任务帧记录）转为非门禁留痕，处置得当。

### D9（dirty flag + 收尾补发）—— **支持（须扩口径，见 C4）**

T4 三断言（挡掉标记待办/收尾恰好补 1 次/多次合并）语义完备可测。**但覆盖面只写了"事件路径"** —— 回可见补刷路径被 loading 短路时同样需要补发语义（与 D3 竞态分析同源），建议 dirty flag 机制统一覆盖 `refreshNow` 路径。

### D10（停表谓词以筛选后列表行为准 + 30s 保底）—— **支持**

- **谓词与 UI 展示一致性**：UI 渲染的就是 `state.items`（筛选后当前页行）【实测：store 结构亲读】，谓词 `items.some(latestJob.status ∈ {RUNNING,PENDING})` 与展示口径**一致**；`latestJob` 行字段锚点 `types/task.ts:112-119` 子验一致。`PENDING/RUNNING` 取自 JobStatus 枚举全集的非终态真子集【实测 A7】，无遗漏（枚举仅 5 值）。
- R1-2（筛选错位）由 filterStatus 口径覆盖；R1-1（跨页签）由 30s 保底覆盖。
- 30s 保底 tick 的**建模与断言真空**归入 C2（D3 责任项）。

### D11（错峰 0~250ms）—— **支持（须钉作用点，见 C3）**

`Math.random` 注入桩可行；上限 250ms 对"≤1s 内补刷"判据（§4.2-2）无冲突。

### D12（Import 侧按 kind 回收）—— **支持**

缺陷坐实【实测 A13：push 前无同 kind 回收】、蓝本亲验成立【实测 A14】、T7 三断言（同 kind 回收/异 kind 独立/卸载全收）可测。`Map<kind,dispose>` 与既有 `stopWatchers` 数组并存时的卸载语义（Map 优先、数组兜底）施工时写清即可。

### D13（refresh_defs/refresh_data 不并入）—— **支持**

子验确认两分支注释已"如实陈述"（`workspace.ts:504-512`）【实测·子验】；E1 排除理由（牵动两页加载语义、面大于一期）成立。

### D14（第 0 步测基线）—— **支持**

"原卡数字全为推断、一律降级待测"的处理是本草案相对原卡的最大可信度改进之一；静态判据（setInterval 3/EventSource 2/visibilitychange 0）已由本会审独立复现【实测·子验 grep 基线】。

### D15（文案同步）—— **支持**

`TaskCenterView.vue:8` 与 `:151` 双点同改的要求【实测·子验】防止注释与实现再次脱节；保底口径文案示例可直接采用。

### D16（一期红线全量）—— **支持**

10 条红线全部带判据；`git status backend/` 空为可机械测门禁【实测复现为空】。与 §5/§7 无发现矛盾或漏项。

---

## 4. 验收门禁可测性评估（§4 逐条）

| 门禁 | 分类 | 备注 / 替代建议 |
| --- | --- | --- |
| §4.0-1 typecheck + build | **可机械测** | build 本会审未跑（耗时），基线声明采信草案【假设】；施工时门禁自会跑 |
| §4.0-2 yarn test ≥154·≥12·0 fail | **可机械测** | 本会审已复现 12/154 全绿【实测·子验复跑】 |
| §4.0-3 git status backend/ 空 | **可机械测** | 已复现空【实测】 |
| §4.0-4 E2E 桩 FAIL=0/GFSKIP=0/PASS≥基线 | **可机械测**（CI）| 基数需人工跑一次记录（第 0 步）；三方 28/29 记载不一已由"不钉死"裁定消解 |
| §4.1-0 D14 三项基线 | **需人工 DevTools** | 无可替代（改动前行为只能实测）；建议记录口径写进施工报告模板 |
| §4.1-1 静态门禁 | **可机械测** | — |
| §4.1-2 全静止 30s 请求计数 | **需人工 DevTools** | 部分可下沉：T2/T5 的 controller 层断言已机械锁"该停就停"，DevTools 只做端到端抽验 |
| §4.1-3/4 有作业节奏 / 终态收敛 | **需人工 DevTools** | 终态收敛（≤1 额外 tick）可由 T2③ 间接锁一半 |
| §4.1-5 P2 回收（1 条流/1 条 EventStream） | **需人工 DevTools** | T7 已锁 dispose 计数，DevTools 验连接面 |
| §4.1-6 P3 中止/超时提示 | **需人工**（AI 面板交互）| T6 锁 signal/超时语义 |
| §4.1-7 / §4.3-6 D1 回归锁 | **可机械测** | — |
| §4.2-1 不可见 30s 恰好 0 次（分段） | **需人工 DevTools** | "按时间轴分段"要求正确（R2-3 后台节流混淆点）；controller 层由 T2① 机械锁 |
| §4.2-2/3 回可见补刷 / 错峰 | **需人工 DevTools** | T2②⑤ 机械锁语义；时间轴分散度（≤250ms）人工抽验 |
| §4.2-4 HITL 倒计时回归 | **需人工** | 无桩可代（真实确认卡）；判据"立即重算"清晰可执行 |
| §4.2-5 RESET 语义 | **可机械测（主）** | T2④ 已锁；DevTools 只做旁证 |
| §4.3-1 事件驱动 1 次刷新 | **需人工 DevTools + curl** | 外部触发手段明确 |
| §4.3-2 TASK_CHANGED 帧观测 | **需人工 DevTools**（Messages 面板）| "HEARTBEAT 仅 1 帧、不得据此判假死"的提醒写得准确（防误判，好设计）|
| §4.3-3 上限 ≤3 / 单调下降 / AI 反查 | **需人工 DevTools** | 步 3 实现后可借 D5 的 store 可观测字段断言下沉一部分 |
| §4.3-4 向导页 0~1 次/30s | **需人工 DevTools** | T5① 机械锁间隔切换 |
| §4.3-5 假死 Block 反查 | **需人工 DevTools** | 判据（30s 保底收敛终态、不永久停旧百分比）**可判定性强**，修订后不依赖任何阈值输入 |
| §4.4 回归面五组 | **需人工** | 清单完整（含 ADR-10 裁剪性），无漏页 |

**总评**：§4 门禁的机械/人工分层现状是"语义层全部机械锁（T1~T7）+ 运行时数字全部人工 DevTools"，在 D1 禁新增依赖（无 Playwright）的约束下**已是可达的上限**，无需替代方案；唯二建议见 S2/S5（保底失败处置、DevTools 判据脚本化留档）。

---

## 5. 必做条件（C1~C5，均可执行、可验收）

> 全部为**口径/文档/测试断言级**条件，落实施工卡即满足，不需要改草案结构与代码改动面。

- **C1 · application.yml 锚点订正**：施工引用 `app.task.sse` 段一律按实测 **`:191-203`**（`write-timeout: 15s` = `:202`、`emitter-timeout: 5m` = `:203`；`heartbeat-interval: 30s` = `:220`、注记起 `:218-219`）；草案 D6/D8/§0-7 中「`application.yml:191-198`」的区间尾界随之订正。验收 = 施工卡/施工报告中该锚点与 `sed -n '191,203p'` 输出一致。**（无代码影响；本会审 A8 实测依据）**
- **C2 · 保底 tick 建模裁定**：明确 D10 的 30s 保底 tick 在 D3 契约下的承载方式 —— 二选一写死：① `createPollingController` 接口扩展 `{ intervalMs, fallbackIntervalMs }`；② 双 controller 实例（4s 条件 + 30s 保底）。并给 T2 补一条断言：`shouldRun=false` 持续 30s（假时钟）→ `run` 恰好 1 次且以 30s 为周期。验收 = T2 新断言 GREEN 且 D3/D10 文字无歧义。
- **C3 · 错峰作用点钉死**：D11 明确 0~250ms 随机延时作用于**回可见补刷本身**（而非仅 interval 重置），T2② 与 T2⑤ 统一为"假时钟推进 ≤250ms 窗口内观测恰好 1 次 run"。验收 = T2②⑤ 同一时钟口径下同时 GREEN。
- **C4 · refreshNow × loading 短路口径**：写死"refreshNow 被 `loading`/`inFlight` 短路"时的语义 —— 建议 D9 的 dirty flag 机制统一覆盖 `refreshNow` 路径（回可见补刷被在途请求挡掉时收尾补发 1 次）；或在 T2② 显式声明断言口径为"`run` 调用次数"而非"有效请求次数"。验收 = 对应断言落 T2/T4 且语义在 controller 注释中可查。
- **C5 · 卸载出口前提确认**：施工第 0 步确认任务中心路由无 `<keep-alive>`（路由切换必然触发 `TaskCenterView` onBeforeUnmount）；若存在 keep-alive，D5 卸载出口须补 `onActivated/onDeactivated` 钩子。验收 = 施工记录含该确认项（一行 grep + 结论）。

## 6. 建议（非必做）

- **S1**：T2 补 start/stop 幂等断言（对齐现 `startAutoRefresh:158-161` 守卫语义，防迁移回归）。
- **S2**：登记"保底轮询自身失败无退避/无放弃"（job-watch.ts:99-100 onError 仅透传；断网时 3s/30s 持续失败请求）为二期项 —— 现状既有、非一期引入，泄漏时长已被终态/卸载/max-execution-time 限界。
- **S3**：T5 补"间隔切换瞬间旧 timer 清除"断言（切换后首个 tick 距切换 ≥3s）。
- **S4**：施工注记两处桩件实现要点 —— EventSource 桩挂 `globalThis`（jsdom 30 无内置）、`document.hidden` 须 `Object.defineProperty` 覆写。
- **S5**：DevTools 类判据（§4.1/4.2/4.3 运行时数字）建议随施工报告附 Network 截图或 HAR 留档，便于三方会审复核口径统一。
- **S6**：R1-4 的 `workspace.ts:463` 锚点宜改写为「`:449` 直调 + `:463` 经 cancelJob 间接」的如实口径（本会审注记 a）。

## 7. 草案外新缺口（本会审独立发现）

| # | 缺口 | 定级 | 依据 |
| --- | --- | --- | --- |
| G1 | **D3 契约三个语义真空**（保底建模 / 错峰作用点 / refreshNow×loading） | 必做条件化（C2/C3/C4） | §3-D3 分析【推断·基于亲读语义】 |
| G2 | **保底轮询失败无处置**：断网期间兜底轮询持续打失败请求，onError 只透传 | 建议（S2，二期） | job-watch.ts:99-100【实测 A6】 |
| G3 | **D5 卸载出口的 keep-alive 前提未验** | 必做条件化（C5） | 【假设→待施工第 0 步确认】 |
| G4 | **T2 漏锁 start/stop 幂等**（现 store 有守卫、迁移后回归面无人锁） | 建议（S1） | task.ts:158-161【实测 A4】 |
| G5 | application.yml 锚点尾界漂移（`:191-198` → `:191-203`） | 必做条件化（C1，无代码影响） | 【实测 A8/子验】 |

## 8. 对草案文本的更正清单（供 K3 合并）

1. §0-7 / §6-D6 / §6-D8 承载判读 / §5-6 中 `application.yml:191-198` → `application.yml:191-203`（结论不变：段内无 heartbeat 键）。
2. §6-D8「`application.yml:191-198` 的 `app.task.sse` 只有 `write-timeout` / `emitter-timeout`」补注具体行（`:202`/`:203`）。
3. §8-R1-4 `stores/workspace.ts:449/:463` → `:449（直调）/:463（经 cancelJob 间接）`。
4. §1.3 `runWithBudget :383-410` → `:383-408`（尾 2 行为下一段注释起点）。
5. （可选）§1.1-P2/§6-D5 补注 TaskSseService 全路径 `configmgr/task/service/TaskSseService.java`，免施工 grep 二次定位。

---

## 附录 A · 本方模型自证（wire 实证）

- 文件：`<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_cf64f937-4010-4a06-aef9-7a19fad9f2b7\agents\main\wire.jsonl`（58 行）
- `grep -o '"modelAlias":"[^"]*"' | sort | uniq -c` → **4 命中，全部 `Zhipu AI/glm-5.3`，无混杂**
- 样本行（第 5 行）：`{"type":"profile.bind","agentId":"main","modelAlias":"Zhipu AI/glm-5.3","profileName":"agent","thinkingEffort":"high",...}`
- `llm.request` 计 4 条，modelAlias 同上；agentId=main 与本会话 id 归属一致。

## 附录 B · 子智能体模型自证（机械活下沉条款）

| 子智能体 | 任务 | wire 文件（`.../session_cf64f937.../agents/<id>/wire.jsonl`） | modelAlias 实证 |
| --- | --- | --- | --- |
| `agent-557` | 前端锚点 16 组 + 基线复跑 | 257 行 | **15 命中，全部 `DeepSeek/deepseek-flash`**；第 5 行 `profile.bind` 佐证归属 |
| `agent-558` | 后端/文档锚点 10 组 | 168 行 | **10 命中，全部 `DeepSeek/deepseek-flash`**；第 5 行 `profile.bind` 佐证归属 |

两个子智能体均由本方**显式钉 model 参数**派发（非会话默认），符合机械活下沉条款。

## 附录 C · 已执行与未做

- 已执行：草案 sha256 核验；HEAD/工作树核验；草案 443 行全文通读；亲读 `stores/task.ts`（369 行全文）、`utils/job-watch.ts`（123 行全文）、`ImportWizardView.vue:840-888`、`ExportWizardView.vue:485-499`、`tests/setup.ts`、`types/job.ts`、`application.yml`/`TaskSseService.java`/`JobWatchdog.java` 精准段；两批子智能体抽验（含 `yarn test`/`vue-tsc` 基线复跑）；本会话与子智能体 wire 取证。
- 未做（声明）：未启动前后端环境、未做浏览器 DevTools 实测（故 §4 运行时数字判据的复核止于可测性评估，数字本身维持草案 D14 待测口径）；未运行 `yarn build` 与 E2E 桩；未读取 DS V4 Pro 会审方任何产出。
