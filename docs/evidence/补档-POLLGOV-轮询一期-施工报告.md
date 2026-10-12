<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\施工报告-轮询一期.md`（17404 B，sha256 `3f972434a10cf43b738cfbce1c22be27dbe2048e371480f1145d5a00c71abd27`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 轮询治理一期 · 施工报告（派发 R-88 / 证据批次 p18）

> 依据：《轮询治理一期施工卡 · 定稿 · R-88》（`<SCRATCH>\施工卡-定稿.md`）+ 《决策卡-轮询一期》T1~T15。
> 施工基线 `HEAD = 6c9c699aa680297ecf0a83ec39eeae8716b68431`，开工 `git status --porcelain` 零输出（干净，非异常）。

---

## 0. DC-19 署名块（硬性完成判据）

| 字段 | 值 |
| --- | --- |
| 执行者 | 本棒 agent id = **`main`**（K3 派发头下的施工主棒；`profile.bind` 佐证见下） |
| 授权来源 | **K3 派单 · 轮询治理一期施工卡定稿 R-88**（2026-10-12，工作区 `wd_temp_38dcbf7ced2f`） |
| 会话 id | **`session_148239f6-da73-4c40-9e04-3e1bacea6bbd`** |
| 实际运行模型 | **`kimi-code/kimi-for-coding`**（primary） |
| 模型取证（wire 实证，非照抄卡头） | 文件 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_148239f6-da73-4c40-9e04-3e1bacea6bbd\agents\main\wire.jsonl`（**776 行**，报告落盘时复数）；`grep -o '"modelAlias":"[^"]*"' wire.jsonl \| sort \| uniq -c` → **82 命中，全部为 `"kimi-code/kimi-for-coding"`，无混杂**；`llm.request` 样本行（首条，截断）：`{"type":"llm.request","agentId":"main","kind":"loop","provider":"openai","model":"kimi-for-coding","modelAlias":"kimi-code/kimi-for-coding","thinkingEffort":"high",...}`；`profile.bind` 佐证：`{"type":"profile.bind","agentId":"main","modelAlias":"kimi-code/kimi-for-coding","profileName":"agent","thinkingEffort":"high"}` |
| 子智能体模型自证（§10-1，显式钉 `DeepSeek/deepseek-flash`，互备 GLM 未启用） | 见 §0.1 表 |

### 0.1 子智能体逐棒自证（wire `modelAlias` 实证）

| 子智能体 | 任务 | wire 文件（同会话 `agents\<id>\wire.jsonl`） | 行数 | `modelAlias` 计数 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `agent-565` | 第 0 步基线门禁实测 | `...\agents\agent-565\wire.jsonl` | 138 | 12 × `"DeepSeek/deepseek-flash"`（无混杂） | 符合钉模 |
| `agent-566` | 步1 施工（条件轮询） | `...\agents\agent-566\wire.jsonl` | 790 | 73 × `"DeepSeek/deepseek-flash"`（无混杂） | 符合钉模 |
| `agent-567` | 步2 施工（visibilitychange 封装） | `...\agents\agent-567\wire.jsonl` | 729 | 69 × `"DeepSeek/deepseek-flash"`（无混杂） | 符合钉模 |
| `agent-568` | 步3 施工（SSE 事件订阅） | `...\agents\agent-568\wire.jsonl` | 820 | 77 × `"DeepSeek/deepseek-flash"`（无混杂） | 符合钉模 |
| `agent-569` | 终态门禁复跑 | `...\agents\agent-569\wire.jsonl` | 177 | 16 × `"DeepSeek/deepseek-flash"`（无混杂） | 符合钉模 |

> 机械活（基线命令复跑、grep 复核、门禁复跑）全部按 §10-1 下沉子智能体并显式钉模；主棒仅做派发、接线、运行时验证与裁决。

---

## 1. 施工结果总览

| 项 | 结果 |
| --- | --- |
| 提交序列 | `6c9c699`（基线）→ `2030c24` 步1 → `ca84ec9` 步2 → `d60c4a6` 步3（**每步一个 commit**，可按步回退） |
| 测试 | 基线 **12 spec / 154 tests** → 终态 **19 spec / 197 tests 全绿 / exit 0**（新增 7 spec / 43 用例：步1 +3/+14，步2 +1/+11，步3 +3/+18） |
| 类型/构建 | `yarn typecheck` exit 0（终态复跑无输出）；`yarn build` exit 0（1751 modules，dist 产物正常） |
| 后端零改动 | `git status --short backend/` 全程空；`git diff --stat 6c9c699..HEAD -- backend/` **空**（D16 红线满足） |
| 依赖面 | `package.json`/`yarn.lock` 零改动（D1 满足） |
| 工作树 | 终态 `git status --porcelain` 零输出 |

---

## 2. 第 0 步基线（D14 + T5 复核）

**静态实测（agent-565，证据 `baseline-git.txt` / `baseline-greps.txt` / `baseline-yarn-test.txt` / `baseline-typecheck.txt`）**：

- HEAD = `6c9c699…`，全仓与 `backend/` git status 均零输出；
- `yarn test` = **Test Files 12 passed / Tests 154 passed / exit 0**（3.08s）；`yarn typecheck` exit 0 无输出；
- keep-alive 族（`keep-alive|keepAlive|onBeforeRouteLeave|beforeRouteLeave|$reset|$dispose|import.meta.hot`）**0 命中** ⇒ "路由切换必然触发 `TaskCenterView.onBeforeUnmount`"结论复核成立（T5 前提，推翻条件 P2 未触发）；
- `setInterval` 调用点 3 处（`stores/task.ts:162`、`utils/job-watch.ts:106`、`AiPanel.vue:839`）+ 2 类型声明 + 1 注释；`new EventSource` 2 处（`api/sse.ts:552`、`api/tasks.ts:106`）；`visibilitychange` 0；`TASK_CHANGED` 仅类型/注释层；`hasRunningJob|hasActiveJob` 0。

**运行时基线（§4.1-0 三项请求数）**：**未能在改动前实测**——本棒环境与浏览器通道在改动完成后才就绪，时序上无法回补。替代口径：改动前 4s 恒等无条件轮询（`task.ts:163` 原 `if (!this.loading)`）⇒ 可见且全静止时 30s 内 ≈7~8 次【推断，非实测】，与施工卡"原卡数字不得沿用为事实"的口径一致，故本报告不引用任何改动前运行时数字。**此为一项如实声明的偏差**。

---

## 3. 分步施工摘要

### 步1 · 条件轮询（commit `2030c24`，agent-566）

- `stores/task.ts`：新增 getter **`hasActiveJob`**（行 `latestJob` 为 RUNNING/PENDING，`types/job.ts:12` 非终态真子集）；`startAutoRefresh` 改条件 tick（4s，判 `hasActiveJob && !loading`）+ 30s 保底 tick（双表过渡，步2 收敛）；新增 **`refreshNow()`**（绕过条件、复用 loading 守卫、dirty flag 被挡标记/收尾补发恰好 1 次/多次合并）；`watchPublishOutcome(jobId, signal?)` 透传 `AbortSignal`、try/catch 收口（abort 不再上抛到 `workspace.ts:452` 的 `void` 调用点）、超时不再静默清 `publishConflict`（ElMessage 单次提示）。
- `api/jobs.ts`：超时分支改判别联合 `{status:'timeout', job}`（唯一调用方经 grep 确认，向后兼容）。
- `utils/job-watch.ts`：`syncSnapshot` 在 `await getJob` 后补 **`if (disposed) return`**（T6 终态）；导出 `createJobWatcherRegistry()`。
- `ImportWizardView.vue`：`beginWatchJob` 按 kind 回收旧 watcher（D12，Export 侧为蓝本）。
- 文案：`TaskCenterView.vue:8` 条件口径 + `:151` 注释；`api/tasks.ts:103` 注释改实（T13-5）。
- 测试：`polling-predicate.spec.ts`（5）+ `wait-job-final.spec.ts`（5）+ `import-watch-recycle.spec.ts`（4）。**168/168 绿**。

### 步2 · visibilitychange 纪律（commit `ca84ec9`，agent-567）

- 新建 `utils/polling.ts`（263 行）：**`createPollingController({intervalMs, fallbackIntervalMs, run, shouldRun})`**——单 interval 动态切换（切换清旧表、首 tick ≥ min 周期、**不双发**）、hidden 停表（不取消在途）、visible 先 **0~250ms 错峰补刷恰好 1 次**再重置节奏、**不补偿错过的 tick**、`refreshNow` 重置 interval 起点 + dirty flag 收尾补发（两条口径写进注释，注明覆盖三路径）、start/stop 幂等、`setIntervals`（步3 预留）。
- 接入：`task.ts` 双表**收敛为单 controller**（4000/30000，幂等语义保留）；`job-watch.ts` 兜底 poll 走 controller（dispose 卸监听）；`AiPanel.vue` 1s tick 走 controller。
- 测试：`polling-controller.spec.ts` **恰好 10 用例（T2①~⑩）** + `store-autorefresh-single-timer` 集成用例。**179/179 绿**。

### 步3 · SSE 事件触发刷新（commit `d60c4a6`，agent-568）

- `api/tasks.ts`：`openTaskEvents` 补 `{onerror, maxDurationMs}`（提前 close 撤定时器，不留悬挂 timer）。
- `utils/job-watch.ts`：channel 绑定间隔切换（**realtime→30s 保底非清零**、polling→3s，D6）；onopen + 首帧各拉 1 次权威快照；**不设帧新鲜度阈值**（D8）。
- 新建 `utils/task-events.ts`：订阅池 **上限 3、taskId 稳定排序淘汰、同 id 去重、close-gate（close 后全部 4 类帧丢弃）**、计数可观测；文件头写明"上限 3 = 防御性上限 + 可观测计数，对 job-watch 池无效"（T10 论据改写）。
- `stores/task.ts`：`taskEventSubscriptionCount` 可观测字段 + `startTaskEvents/syncTaskEvents/closeTaskEvents/applyJobProgress`（只认 `payload.jobId === latestJob.id` 防串写）；`load()` 后 resync。
- `TaskCenterView.vue`：onMounted 起订阅、**onBeforeUnmount 显式 `closeTaskEvents()`**（T5 跨层出口，经真实挂载组件的 spec 验证）。
- 测试：`task-events.spec.ts`（7，T3①~④+T4①~③）+ `job-watch-channel.spec.ts`（8，T5①~⑥+T4④⑤）+ `task-events-lifecycle.spec.ts`（3，T8①~③，EventSource 桩挂 `globalThis`）。**197/197 绿**。

---

## 4. TDD 留证（§10-4）

| 阶段 | 证据文件（`<SCRATCH>\evidence-p18\`） |
| --- | --- |
| 步1 RED | `red-step1-polling-predicate.txt`（5/5 failed）、`red-step1-wait-job-final.txt`（5/5 failed）、`red-step1-import-watch-recycle.txt`（4/4 failed）、`red-step1-import-watch-recycle-inflight-teeth.txt`（摘复检断言后恰 1 失败，证明用例有牙） |
| 步1 GREEN | `green-step1.txt`（15/168）、`green-step1-postcommit.txt`、`typecheck-step1.txt` |
| 步2 RED | `red-step2-polling-controller.txt`（骨架期 11 failed 断言级原文，两轮） |
| 步2 GREEN | `green-step2.txt`（16/179）、`typecheck-step2.txt` |
| 步3 RED | `red-step3-task-events.txt`、`red-step3-job-watch-channel.txt`（6 failed/2 passed）、`red-step3-task-events-lifecycle.txt`（3 failed）——均以 stash 回退步2 基线实测 |
| 步3 GREEN | `green-step3.txt`（19/197）、`typecheck-step3.txt`、`verify-open-task-events-step3.txt`（新挂具 4 例一次性验证，临时 spec 已删未入 commit） |
| 终态门禁 | `final-gates.txt`（HEAD/porcelain/yarn test 19/197 exit 0/typecheck exit 0/build exit 0/后端空，`git diff --stat 6c9c699..HEAD -- backend/` 空） |

---

## 5. 运行时验证（真实环境：H2 后端 :8081 + vite dev :5202 + 用户真实浏览器 Kimi WebBridge）

判据留档：network 抓包 JSON + performance entries 时间戳 + 页面截图 `runtime-taskcenter.png`（任务中心页头新文案「有进行中作业时每 4 秒自动刷新；其余每 30 秒兜底刷新」可见）。**注**：留档形态为 WebBridge network 抓包 + Resource Timing 时间戳 + 截图，非 DevTools 面板直截；两个验证 hidden 窗口均以页内 `document.hidden===true` 实测探针确认（`runtime-store-probe.json`、`runtime-tab1-hidden-check.json`）。

| 判据 | 实测结果 | 结论 |
| --- | --- | --- |
| §4.2-1 不可见期间请求数 | 窗口 A：09:17:24→09:19:22（118s，hidden 实测确认）`/api/tasks` **0 次**；窗口 B：09:23:34→09:25:06+（≥92s，hidden 实测确认）**0 次**（时间戳序列 `runtime-perf-entries4.json`：09:23:22 后至 09:25:51 无任何请求） | **PASS（恰好 0）** |
| §4.2-2 回可见补刷 | 09:19:22 真实可见化 → **恰好 1 次** `GET /api/tasks`（catch-up），随后节奏恢复 | **PASS（恰好 1 次）** |
| §4.2-5 保底节奏与无双发 | 可见且全静止：09:19:52 / 09:20:22 / 09:20:52 / 09:21:22 连续 4 个间隔 = **30002/30001/30003ms**（精确 30s），无同 tick 双发 | **PASS（≤1 次/30s，非 0）** |
| §4.1-2 全静止判据 | 全静止可见时 = 30s 保底 1 次/30s（上两行即此判据的运行时面） | **PASS** |

**环境干扰说明（如实）**：首次 bringToFront 启用了 CDP focus emulation，使页面在真实切走时仍报 visible（09:21:32–09:22:39 窗口内出现 2 次 30s tick）——此为测量伪影而非应用行为，已通过关闭 emulation + `document.hidden` 实测探针的窗口 B 复测排除。真实浏览器存在用户并行操作，窗口边界以页内探针而非墙钟为准。

**未执行的运行时判据（如实声明，不判 PASS）**：

- §4.1-3（有在跑作业恢复 4s）、§4.1-4（终态收敛）、§4.1-5（P2 回收运行时面）、§4.1-6（P3 abort/超时运行时面）；
- §4.2-3（错峰 ≤250ms 多轮询点齐射分散）、§4.2-4（HITL 倒计时重置）；
- §4.3 全部（事件驱动判据、TASK_CHANGED Messages 面板、实例上限 5 任务场景、向导页降频、假死 Block 反查、**B1 判死链反查**）；
- §4.4 回归面（真实点击遍历）与 E2E 门禁（未钉死基数，且需桩上游 + Redis 容器，未运行）。
- 原因：上述需要造在跑作业数据（创建任务 + 启动作业经向导文件上传）与 ≥90s 判死窗，在用户并行操作的真实浏览器内继续成本高、干扰大；其语义已由 §3 对应 spec（T3/T4/T5/T7/T8 全绿）锁定。D8 第 0 步观测口径（空转任务 EventStream Messages ≥120s 帧留证）同此声明未执行。

---

## 6. 必备声明与建议（§10-6）

1. **止血口径**：「**降频 90%（3s→30s）而非归零**」（realtime 通道 30s 保底 + 全静止列表 4s→30s）；多页签残余请求量化未附（建议项，不作门槛）。
2. **R-88 / R-89 行内锚点更新建议**（`docs/M2-排期计划.md`，本批未改 docs）：`:280` R-88 `startAutoRefresh:182-199` → 现 `:157-168` 区域（步2 后已委托 controller，建议改注「startAutoRefresh→createPollingController」）；`:281` R-89 `ImportWizardView.vue:748-795` → 现 `:840-888`（步1 后回收逻辑落点 `:853` 一带）。
3. **R-94 行尾注记建议**（`:286`）：本批已收「AbortSignal 透传（`watchPublishOutcome(jobId, signal?)`）+ 超时判别联合显式化（`{status:'timeout',job}`）+ abort 全量捕获（无 unhandled rejection）」；`publishConflict` 渲染位与 `refreshCurrent` 死码族仍待 R-94 批。
4. **B1 判死链**：§1.4 的【推断】未转【实测】（见 §5 未执行声明）。

---

## 7. 裁量清单（Rulings）

1. **步1·超时判别联合**（agent-566）：`waitForJobFinal` 超时返回 `{status:'timeout',job}` 而非抛错——保持无新增 throw 路径；签名变更前 grep 确认唯一调用方。
2. **步1·abort 识别**：保留原文案 + `JobWaitAbortedError` 类名，spec 断言 name/message 而非静态 import（让 RED 是语义失败）。
3. **步1·catch 全量收口**：非 abort 的网络异常同样走"恰好 1 次提示"出口——否则同样会在 `workspace.ts:452` 的 `void` 点变 unhandled rejection。代价：网络故障也会 toast 一次（可接受）。
4. **步1·提示机制 = ElMessage.warning**：stores 层无既有 toast 机制，element-plus 为既有依赖（views 层 5 处在用），未新建渲染位、未加依赖。
5. **步2·store.run 返回 `this.load()` 的 promise**（对卡文 `void this.load()` 的偏差）：controller 的 in-flight/dirty 以 run 返回 promise 为准，`void` 掉则 dirty flag 形同虚设。代价：load 超 4s 时撞上的 tick 会补发 1 次（合并 1 次）——正是 D9 要求的语义。
6. **步2·`job-watch.ts:71` 改 `poller.refreshNow()`**（对卡文 `void syncSnapshot()` 的偏差）：否则 JOB_DONE 路径"被 inFlight 挡即丢弃"，与口径②注释自相矛盾。
7. **步3·`syncSnapshot` 撞在途返回在途 promise**（agent-568）：原 `return` 已 resolve 的 promise 使 controller 误判"本次已完成"，dirty 永不置位（`dirty-jobdone-path` 的 RED 即此面）。既有 spec 全绿，语义保留。
8. **步3·`authoritative-snapshot-twice` 计数口径**：锁"通道驱动 2 次"（onopen 1 + 首帧 1），与开局首拉（既有语义）分开断言。
9. **运行时基线未实测**（主棒）：见 §2——时序上无法回补，声明为偏差，未编造数字。
10. **focus emulation 伪影**（主棒）：09:21:32–09:22:39 窗口数据作废，以窗口 B 复测为准。

## 8. 未做事项声明（汇总）

- §4.1-0 运行时三项基线：未实测（以静态实测 + 推断口径替代，§2）。
- §5 列出的全部运行时/DevTools/E2E 判据：未执行（语义由 spec 锁定）。
- `stores/workspace.ts:452` 未接 abort 触发源（改动面未列该文件，E6 红线约束）：AbortSignal 透传能力已就绪，"AI 轮次收尾/卸载时中止"的运行时接线未做，声明为二期/R-94 批可接项。
- docs 锚点订正：未改仓库 docs（仅本报告给建议）。

---

## 9. 结论

- **硬性完成判据**：本报告落盘非空 + DC-19 署名自证（实际模型 `kimi-code/kimi-for-coding` + 会话 id + wire `modelAlias` 实证）——**已满足**。
- **施工卡 §10 条款核对**：10-1 机械活下沉且逐棒 wire 实证 ✓；10-2 首页署名 ✓；10-3 仓库红线（基线干净起步、未读 .env、未触碰 trae 线两目录、无 git 写越界）✓；10-4 TDD RED→GREEN 留证 ✓；10-5 后端零改动（diff 级证据）✓；10-6 第 0 步声明 ✓ / DevTools 类判据部分执行部分如实声明未执行 / B1 未转实测（声明）/ 止血口径 ✓ / 锚点与 R-94 建议 ✓ / 未做事项声明 ✓。
- **判级**：代码与测试面判 **PASS**（197/197 + typecheck + build + 后端零改动 + 运行时核心判据通过）；运行时覆盖面判 **部分 PASS**（§5 明示未执行项，不夸大）。

| 收尾项 | 值 |
| --- | --- |
| 终态 HEAD | `d60c4a6e88640a6fab63199265010ec9101c2bfd` |
| 工作树 | 干净（运行时验证后复查） |
| 证据目录 | `<SCRATCH>\evidence-p18\`（43 件） |
| 报告落盘 | `<SCRATCH>\施工报告-轮询一期.md`（本文件） |
