> 来源=<仓外证据>/main-ci-cancel-analysis.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 主分支 CI 运行 40174289141（ae883fb）E2E job 被取消 — 归因报告

- 仓库：`<主仓>`
- 运行：`gh run 40174289141`，push，head `ae883fbae52be36e45a635ee276cf8298ac24bd2`，attempt 1
- 结论：**不是外部取消，不是依赖失败，是 E2E job 自己撞上 job 级 `timeout-minutes: 25`**；撞上限的直接原因是 **H-01 用例后产品侧"决策已落库但没有续跑"，客户端随后在一次读流上无限阻塞**。
- 证据目录：`<仓外证据>/main-ci-cancel-evidence/`
  - `run.json`（run+job+step 时间线，gh run view 原样）
  - `e2e-job.log`（E2E job 全量日志，656 行）
  - `e2e-evidence/`（本次运行归档：`boot.log` 557KB / `stub.log` / `gfc/`，artifact 12591919564）
  - `prev-e2e-965e6ef/`（上一提交 965e6ef 的同名归档，用于对照）
  - `verify-e2e.ps1`（ae883fb 版本的 E2E 驱动脚本，5725 行）

---

## 1. 事实（可从 GitHub API / 日志 / 代码直接复核）

### 1.1 取消类型 = job 级超时

| 事实 | 证据 |
|---|---|
| run conclusion `cancelled`，07:50:20Z 起，08:19:41Z 止 | `run.json` |
| E2E job `121112281427`：started 07:54:19Z，completed 08:19:35Z，conclusion `cancelled` | `run.json` |
| job 注解原文：**"The job has exceeded the maximum execution time of 25m0s"** | `gh api repos/<仓库所有者>/ai-example/check-runs/121112281427/annotations` |
| 25 分钟来自 workflow：`e2e: timeout-minutes: 25` | `.github/workflows/ci.yml:227` |
| 其余 6 个 job 全部 success（changes / backend / frontend / docs / scan-history / redis-guard） | `run.json` |
| 门禁 job `121117190760` 在取消**之后**才启动（08:19:38Z）并正常跑完，输出 `E2E_RESULT: cancelled` 后 exit 1 | `main-ci-ae883fb-failed.log` 第 60-63、86、91-93 行 |

推论排除项（同为"事实级"排除）：
- **不是 run 级外部取消**：门禁 job 在取消点之后仍被调度并完整执行（若 run 被 Cancel workflow，它会被一起取消）。
- **不是依赖失败**：`needs: [changes, backend]`，backend `success`，e2e 已实际启动并跑了 24 分钟。
- **不是 step 级超时**：`运行 E2E（35 用例）` 这个 step 自己没有 `timeout-minutes`（只有 job 有），step 是在 job 到点时被连带杀掉的。

### 1.2 取消时刻在做什么：H-01 之后完全静默

- step 12 `运行 E2E（35 用例）`：07:55:28Z 开始，08:19:30Z 被取消（`##[error]The operation was canceled.`）。
- 该 step 的**最后一条业务输出**是 07:59:23.255Z：
  `[PASS] AIE2E-M-09 [P1] lastSeq 差量 + 孤儿过滤…`（`e2e-job.log:570`）。
- 之后 **19 分 57 秒零输出**，没有 `[FAIL]`、没有下一个用例的任何一行，直到被杀（`e2e-job.log:571`）。
- 用例执行顺序 = 脚本内 `Add-AiCase` 的注册顺序（`verify-e2e.ps1:2744-3418`，顺序执行于 `:5481` 的 `foreach`）。M-09 之后的下一条是 **H-01「单卡批准 → 真实执行」**（`verify-e2e.ps1:3126`）。失败用例会立刻打印 `[FAIL] … => why`（`No()`，`verify-e2e.ps1:106-108`）——**没有 [FAIL] 说明脚本卡死在 H-01 的函数体里，而不是断言失败**。

### 1.3 产品侧证据：H-01 的"批准"没有换来续跑

`e2e-evidence/boot.log`（本次运行后端日志，PID 2683），H-01 的 runId = `04e186af-20f5-40ca-a538-eb0551538be5`：

| 时刻 | 事件 |
|---|---|
| 07:59:23.680/24.091 | H-01 前置（PRECHECK job 31 / IMPORT job 32；`aie2e-h01-…` 会话） |
| 07:59:24.116 | `event=tool_call_start tool=start_publish` |
| 07:59:24.119 | `ConfirmGate: 人工等待 park runId=04e186af… toolCallId=call-stub-82 tool=start_publish kind=confirm（请求帧已投递并收流；线程交还…）` |
| 07:59:24.120 | `event=approval_decided … decision=approve actor=user status=APPROVED`（线程 `io-18330-exec-5`，即 `/api/ai/confirm` 已到达） |
| 07:59:24.120 | `event=turn_parked` / `AI 轮次进入人工等待挂起态`（park 线程收尾） |
| 07:59:24.625 | 客户端重挂：`FrameWakeListener 帧唤醒订阅建立` |
| 07:59:24.626 | `AiController: reattach runId=04e186af… status=SUSPENDED lastSeq=3 回放帧数=2` |
| 08:00:49.498 | 无关的 `uri=/api/tasks/10/events` AsyncRequestTimeoutException（TC11 遗留的任务 SSE） |
| …08:19:30 | **boot.log 结束，再无任何请求/帧/续跑日志**（该时刻之后仅 4 行，全是上面那段栈） |

关键对照（同一次运行内、同一个构建）：
- 本次运行共 **14 次 park**（`人工等待 park` 行），共 **14 次 `死卡兜底触发续跑`**。
- 按 runId 对齐后：`04e186af…`（H-01）**只有 park、没有触发续跑**；`426acde4…`（P-14 类非 park 路径）只有触发续跑。也就是说：**H-01 是唯一一次"决策落了、续跑没派发"的 park**。
- 对照上一提交 965e6ef（run 40173314741，E2E 通过）的同一用例 H-01：`人工等待 park` 07:44:27.708 → `死卡兜底触发续跑`（woke=false 且快照 SUSPENDED）07:44:27.712 → 续跑跑完 → `[PASS] AIE2E-H-01` 07:44:28.632。20c56bb（run 40171915038）同样是 `[PASS] AIE2E-H-01` 07:21:23。
- 桩上游 `e2e-evidence/stub.log`：最后一次工具轮是 `[req-082] tool_call start_publish route=TC17-PUBLISH`，此后**没有任何上游请求** —— 与"没有续跑、没有再调模型"一致。
- 归档 `gfc/` 里也没有 H-01 的帧（只有 GF1-6、TC23），与"H-01 没走到任何断言"一致。

---

## 2. 根因

### 2.1 直接原因（产品竞态，已定位到代码与毫秒窗口）

park 模式下**没有等待线程**，但闸门 Latch 仍是"预登记"状态，且要跨越一次 Redis 往返才摘除：

1. `SpToolCallingManager` 在挂起前 `this.gate.arm(runId, gateWaitIds)`（`SpToolCallingManager.java:463`）→ `ConfirmGate.arm` 里 `latches.computeIfAbsent(key, …)`（`ConfirmGate.java:409`）**预登记 Latch**（F8 反竞态的既有设计）。
2. `saveSuspended` 写快照 SUSPENDED → `suspended` 帧 → `tool_start` → `confirm_request`（`SpToolCallingManager.java:473-491`）。**客户端看到卡时，快照已是 SUSPENDED、Latch 已在表里。**
3. park 分支：打 `人工等待 park` 日志 → `releaseWaitingLease(lease, runId)`（`ConfirmGate.java:829-837`，**一次 Redis release 往返**）→ `throw RunParkedException`；Latch 的删除在 `finally`（`ConfirmGate.java:716`）。
4. 决策恰好落在这段窗口里时：`doSubmitDecision` 中 `woke = signal(runId, toolCallId)`（`ConfirmGate.java:1189`）会命中这个**没人 await 的 Latch** → `woke=true` → `triggerResume` 第一句就 `return ResumeTrigger.skipped("WOKE_IN_PROCESS_GATE")`（`ConfirmGate.java:1608-1609`，**不打任何日志**）。若 `signal` 未命中但 Latch 仍在，则走 `hasActiveLatch` 分支（`:1610-1613`，只有 DEBUG 日志；本运行 ConfirmGate 是 INFO 级 —— 全程 `ConfirmGate` DEBUG 行数为 0，所以该行不可见）。
5. park 线程随后 `finally` 摘 Latch 并退出 → **一轮既没有驱动方、也没有终帧**，快照永久停 `SUSPENDED`，条目已是 `APPROVED`。进程内没有任何周期性收敛器（`ai` 包内 `@Scheduled` 数为 0；只有启动期 `StartupResumeRunner`），所以这个"悬轮"不会自愈。

**毫秒证据**：失败运行 `park 日志 .119 → approval_decided .120`（+1ms，落入窗口）；对照运行 `park .708 → 死卡兜底触发续跑 .712`（+4ms，已过窗口）。窗口宽度 ≈ 一次 Redis release 往返 + throw/finally，量级正是 1-3ms。

> 事实与推断的边界：`approval_decided` 在 `.120`、park 在 `.119`、且**没有任何** `死卡兜底触发续跑/未派发/池饱和` 日志 —— 这三条是事实。由代码可知，"静默跳过"只有 `WOKE_IN_PROCESS_GATE` / `ACTIVE_LATCH_IN_PROCESS`（DEBUG 不可见）/ `SNAPSHOT_NOT_SUSPENDED` / `NO_RESUME_SERVICE` 四种；其中 `SNAPSHOT_NOT_SUSPENDED` 可排除（快照在发帧前已写 SUSPENDED，且 `.626` 的重挂也读到 SUSPENDED），`NO_RESUME_SERVICE` 可排除（同一次运行另外 13 次派发成功）。所以"命中未摘除的 park Latch"是**唯一与全部日志一致的解释**，但它是推断，不是直接断言（`wokeInProcessGate` 与 `resumeTriggered` 在 `/api/ai/confirm` 响应体里，脚本没有落档）。

### 2.2 放大原因（把"一次丢唤醒"变成"整个 job 被取消"）

1. **ae883fb 的 reattach 改动把"静默"从短连接变成永久连接**：`AiController` 的 `stillWaitingForInput(runId)`（本次提交新增，`AiController.java` reattach 段）在"条目都已结算"时**故意不调用 `completeSubscriptions()`**，让连接留着等续跑的 done/error。本例里条目已全部结算但**续跑根本不存在**，于是 `.626` 建立的重挂流一直开着且无任何帧 —— 客户端 `ReadLine()` 无限阻塞。
2. **harness 没有"用例级"超时**：`Read-Sse` 只设了 `$cts.CancelAfter($script:HttpTimeoutMs)`（= total-budget 300s + 60s = 360s，`verify-e2e.ps1:118-135`、`:280`），没有帧间空闲上限、也没有"单用例墙钟"。经验事实：07:59:24.626 之后到 08:19:30 这 18.5 分钟里，后端**没收到任何新请求**，脚本也没有打印 `[FAIL]` —— 说明那次阻塞读在 360s 到点后也没有真正退出（`HttpClient` 对"已拿到响应头后的流式读"不保证被该 token 打断，且 `Read-Sse` 的 `catch` 会吞掉异常），用例超时防线实际不存在。
3. 结果：24 分钟的用例阶段（其中 20 分钟纯等）撞上 job 的 25 分钟上限 → `cancelled` → 门禁 `failure`。

---

## 3. 未提交的修复是否解决了它？——没有（仅有反证）

工作区（`ae883fb` + 17 个未提交文件）针对的是**另一个问题**：

| 未提交改动 | 内容 | 是否触及本次根因 |
|---|---|---|
| `ConfirmGate.java` +12 | 新增 `publishesRequestsAheadOfLoop()`（park 时请求帧前移的判据） | 否：只读判定，未改 park 退出时 Latch 生命周期 |
| `SpToolCallingManager.java` +36 | park 时把 `tool_start` 前移、`emitToolStartOnce` 去重 | 否：只改帧序（tool_start 先于 request） |
| `SseChatEmitter.java` +28 | 每订阅者 `wakeFloor`（`?lastSeq` 重挂不重发） | 否：解决"重复帧/跨实例补帧重发"，不解决"没有驱动方" |
| `frontend/src/utils/parts.ts` +12、新增两个 spec/test | 帧序与订阅者唤醒的单测/前端回归 | 否 |
| readiness / job watchdog / FileController 等 | 另一批（就绪排空、作业项收敛、下载字节一致） | 否 |

具体反证：
- 现有修复链里**没有任何一处**在"决策到达但本进程无等待方"时补派续跑；`triggerResume` 的 `woke=true` 早退仍在（`ConfirmGate.java:1608-1609`）；park 退出前仍未摘 Latch（`releaseWaitingLease` 在 `:829`，Latch 删除在 `:716` 的 finally）。
- 也没有增加"`SUSPENDED` + 无 `PENDING` + 无驱动方 ⇒ 自愈续跑"的兜底（`ai` 包内无 `@Scheduled`，`StartupResumeRunner` 只在启动期跑）。
- 反过来，未提交改动会**加剧**观察到的现象：`stillWaitingInInput` 保留连接的逻辑来自 ae883fb，未提交批没有动它；而 `frontend/tests/park-request-tool-start-order.spec.ts` 目前仍让 `vue-tsc` 报 5 个 TS2345（见 `<仓外证据>/park-seq-task-review.md` F1），若原样推送，前端 job 会红。
- 结论：**未提交修复不能合理地修好本次取消**；把它们推上去重跑，H-01 仍有同一竞态（概率问题）。

---

## 4. 需要的下一步动作（按优先级）

1. **修竞态（阻断级，产品）**：park 分支必须在 `throw RunParkedException` **之前**把本批 Latch/armedKey 摘掉（或给 Latch 打 `parked` 标记，使 `signal` 返回 false），并让 `triggerResume` 只在"确有一个正在 `await` 的线程"时才认 `woke`。补一条并发单测：`arm → 发帧 → park 退出窗口内决策` ⇒ 必须派发续跑（`WOKE_IN_PROCESS_GATE` 不得出现在 park 语义下）。
2. **加自愈兜底（防御级，产品）**：`GET /api/ai/events` 重挂 / `runs/current` / 启动扫描任一处，检测"快照 SUSPENDED 且无 PENDING 且无驱动方" ⇒ 派发一次有界续跑（本次 20 分钟静默会变成一次即时恢复）。
3. **修 harness（阻断级，门禁）**：`Add-AiCase` 外层加单用例墙钟（建议 120-180s，按 `total-budget` 派生），`Read-Sse` 加帧间空闲上限（心跳周期 x 3），超时打印**用例 id + 已收帧数**后判 FAIL。要求：任何一次挂死都在 3 分钟内变成一条带名字的 `[FAIL]`，而不是吃掉 job 预算。
4. **重跑并复核**：修复后重跑 `ci.yml`，确认 E2E job 用时回到 6-7 分钟量级（对照 2ed8f95=6.9min、bbc2089=6.1min；965e6ef 那次的用例阶段约 4min）。**不要把 25 分钟上限调大当作修复**——本次 20 分钟是什么都没做的空等，调大只会把红变慢红。
5. 门禁语义保持现状（`cancelled` 即红）是对的，本次门禁行为没有问题。

---

## 5. 一句话结论

E2E job 是被自己的 `timeout-minutes: 25` 掐掉的（注解原文 "The job has exceeded the maximum execution time of 25m0s"），起因是 **H-01「单卡批准」在 park 退出窗口内收到决策：`woke` 命中了没人等待的预登记 Latch，`triggerResume` 静默跳过续跑，run 永久停在 `SUSPENDED`**；客户端在 ae883fb 新逻辑保留的重挂流上无限阻塞、harness 又无用例级超时，20 分钟空等吃掉 job 预算 -> job `cancelled` -> 门禁红。未提交的 park 帧序/唤醒下界修复不覆盖这条路径。
