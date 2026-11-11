# 核心 AI 对话接口覆盖工具

`core-coverage.mjs` 使用 Node 内置 `fetch`，对 27 个核心 AI 对话 HTTP 路由逐项执行真实请求、计时和语义断言。它不是路径清单或健康检查脚本：任一项未执行、未断言、失败或清理失败都会使退出码非零。

`EVAL_ENABLE_API_LOAD=true` 时，脚本在 27 项覆盖之外追加一个可选的 API 负载阶段。该阶段默认关闭；关闭时不发起任何负载请求，也不改变 27 项的请求数量和覆盖判定。

## 范围

纳入：

- `AiController` 全部 19 个路由变体，含真实 `GET /api/ai/events/{runId}` SSE 重挂、`probe=1` JSON 探测及交叉产型 406 语义。
- `GenerativeFormAttachmentController` 的附件上传与选项数据源。
- `AiSessionPurgeController`、`AiSessionController`、`AiToolsController`、`TenantDirectoryController`。
- KB 的 `GET /api/ai/kb/status` 与 `GET /api/ai/kb/docs`；知识检索通过真实 `/api/ai/chat` 的 `kb_search` 帧验证。

排除：

- `AiConfigController` 的全部接口。
- `POST /api/ai/kb/reload`，因为重建索引属于配置操作。
- 普通 Task/Job 业务接口；它们只允许作为创建结果清理手段，不计入覆盖。

没有静默可选项。模型若未产生预期的 `create_task` 确认、KB 检索或 attachment 表单，脚本会显式失败，而不会把 404、409 或 `NOT_RUN` 记为覆盖成功。

## 执行

前置条件：隔离的后端、Redis 与模型服务均已启动；不得在正式环境执行。脚本不启停服务，不读取或保存密钥。

```bash
export BASE_URL=http://127.0.0.1:8081
export EVAL_ISOLATED_ENV=true
export EVAL_ALLOW_WRITES=true
export EVAL_CONFIRM_REMOTE=true       # 非本机地址时必须；本机可省略
export TENANT_ID=1
export USER_ID=101
export EVAL_MODEL_CONCURRENCY=6      # 可选，1..10；>10 或 <1 拒绝启动
export COVERAGE_PREFIX=core-r1        # 可选，会话/run/附件/反馈隔离前缀
export OUT_DIR=verification-results/ai-core-coverage

# 可选 API 负载阶段；默认关闭
export EVAL_ENABLE_API_LOAD=true
export EVAL_API_CONCURRENCY=4         # 可选，1..16，默认 4
export EVAL_API_LOAD_REQUESTS=8       # 可选，1..100，每接口逻辑请求数，默认 8

node scripts/perf/ai-api/core-coverage.mjs
```

## 输出

输出为 `$OUT_DIR/coverage-results.json`，逐项包含：

- `method/path/latencyMs/httpStatus/assertions/error/cleanup`；
- `configured` 与 `modelConcurrencyConfigured`（同一配置值）、`independentModelWorkers=6` 以及 `maxObservedInFlight`；
- `modelCircuitBreaker.failed/phase/error/modelInFlight/slotsNotSafelyReleased`，用于保留熔断及未安全释放名额的事实；
- `coverage.complete`、`notRun`、`cleanup`、`cleanupErrors`（合并）、`coreCleanupErrors`（原始 27 项 scope）和致命错误；
- `load` 段（见下）。

只有 27 项全部真实执行且断言通过、清理也完成、模型调度未熔断，并且启用时的负载阶段也通过，`coverage.complete=true`。`maxObservedInFlight` 不得超过模型配置值，模型配置值不得超过 10。

### load 段

`load` 与 `coverage` 平级，避免把负载结果混入 27 项覆盖：

- `enabled`：是否启用负载阶段。
- `ran`：是否真正进入负载阶段。
- `status`：`PASS` / `FAIL` / `NOT_RUN`。关闭时固定 `NOT_RUN`；启用但未到达时也是 `NOT_RUN`，且 `complete=false`。
- `config.concurrency`、`config.requestsPerEndpoint`：本次配置。
- `maxObservedApiInFlight`：负载阶段任务在途数的实际峰值（不是精确 HTTP 连接数；附件任务等待模型名额时也计入），不会超过 `EVAL_API_CONCURRENCY`。
- `skipped`：所有条目 `skipped` 的合计；仅配对 DELETE 在部分/全部会话创建失败时产生。
- `scriptTimeoutCancelled`：脚本自身超时并主动取消的次数，单列但不计入成功；这些尝试仍计入 `failed`，条目和 load 仍为 FAIL。
- `planned`：计划中的全部负载条目键名。
- `complete`：关闭时为 `null`；启用时必须所有条目 PASS（或零目标的配对 DELETE 为 SKIPPED）、`load.cleanupErrors` 为空、无负载致命错误、附件证据完整，并且 Cluster 模式下逐主节点命令增量采集成功才为 `true`。
- `endpoints`：按条目键名组织，每条包含：
  - `route/variant/scenario/modelGate/serial`；
  - `configured/requested/completed/failed/scriptTimeoutCancelled/skipped`；
  - `latencyMs.p50/p95/min/max/mean`；
  - `httpStatuses`，例如 `{"200":8}`；
  - `errors[]`（`index/httpStatus/message/classification/realCode/runId/evidencePath`，消息截断到 500 字符）；`realCode` 保留服务端真实终帧码，不把 `AI_RUN_CANCELLED` 改写成模型错误；
  - `status` 与 `durationMs`。
- `clusterCommandStats`：Cluster 逐主节点压测前后命令快照及本次增量，见下文；非 Cluster 或 load 未执行为 `NOT_RUN`。
- `cleanup.done/detail`、`cleanupErrors`（仅负载清理）、`fatalError`、`notes`。

`attachmentEvidence` 与 `load` 平级，逐次 attachment 尝试记录 `runId/sessionId`、阶段时间线、最后 SSE/终帧白名单字段、取消前后 pending、取消来源与响应、`waitKind`、`classification`、`realCode`、脱敏错误摘要和证据路径。`missing[]` 同时追踪没有最终证据的 `MISSING`（从未写盘）与 `INCOMPLETE`（只有中间证据）；这些条目的 run/session 清理被明确阻止，不会从报告中消失。证据文件位于 `$OUT_DIR/attachment-evidence/`，不保存提示词、表单值、文件内容、原始响应体或原始异常堆栈。

`configured`/`requested` 是计划与已启动的**逻辑操作数**，不是“每个都一定拿到 HTTP 响应”。配对 DELETE 的 `configured=requested=成功创建的会话数`，`skipped=EVAL_API_LOAD_REQUESTS-成功创建数`；`httpStatuses` 只统计真正收到的 HTTP 响应。零目标的 DELETE 记为 `SKIPPED`，不是 PASS/FAIL。

延迟统计口径：`p50/p95` 使用 nearest-rank（`ceil(p/100 * n) - 1`），返回真实样本值；`mean` 保留两位小数；只统计断言通过的成功请求。`httpStatuses` 记录所有拿到 HTTP 响应的尝试（成功和失败都计）。失败请求另见 `errors`。

**未实际运行不代表 PASS。** 关闭、未到达、条目缺失、请求数不足、任一失败或清理失败都会留下 `NOT_RUN`/`FAIL` 或 `complete=false`，不会生成绿色结果。关闭或未到达时 `cleanup.detail` 固定为 `no load-created resources`，不会写成“已清理”。

## 可选 API 负载阶段

### 参数

| 变量 | 默认 | 允许范围 | 语义 |
| --- | --- | --- | --- |
| `EVAL_ENABLE_API_LOAD` | 关闭 | 仅字面量 `true`（大小写不敏感） | 启用负载阶段；其它值一律视为关闭 |
| `EVAL_API_CONCURRENCY` | 4 | 1..16 | 全部负载请求共享的全局在途上限 |
| `EVAL_API_LOAD_REQUESTS` | 8 | 1..100 | 每个负载条目的逻辑请求数；配对 DELETE 以成功创建数为实际 configured |

显式设置非法值会立即失败，即使 `EVAL_ENABLE_API_LOAD` 未开启。未设置时使用默认值。

非串行条目会真实发起 `EVAL_API_LOAD_REQUESTS` 次请求，worker 池在任务启动时同步占位，实际并发达到 `min(请求数, EVAL_API_CONCURRENCY)`。串行条目（`POST /api/ai/session`、终态 `resume`）逐条发起 N 次请求，单条在途不超过 1；配对 DELETE 的 configured 可能小于 N，差额记入 `skipped`。`maxObservedApiInFlight` 记录的是负载任务在途峰值：它包含附件任务等待模型名额的时间，因此不是精确的 HTTP 连接数峰值。

### 负载如何构造

共享 `mainSession` / 已取消的 `mainRun` 上的安全只读与终态接口：

- `health`、`tools`、`tenants`、`kb/status`、`kb/docs`、`option-sources`、`runs`、`runs/{runId}`、`runs/current`、`history`、`context`、`pending`、`feedback GET`、`cancel GET`、`startup-resume GET`：每条独立请求，复用既有 `request` 与断言，不驱动模型。
- `events`：两个独立条目。`probe=1` 只验证 JSON readiness；`[sse-replay]` 从 `lastSeq=0` 建立真实 SSE 连接并读到 cancelled `done` 终帧，逐帧校验 `seq` 严格递增。**probe 不能替代 SSE 并发证据。** 每条 SSE 响应体只由一个 reader 消费一次，不复制、不二次读取。
- `runs/current`：`mainRun` 取消并落终态后，会话 current 索引按后端语义被清除，因此断言 `found=false`；取消前的 `found=true` 仍由原核心流程承担。

终态幂等：

- `POST /api/ai/cancel/{runId}`：只压已 `CANCELLED` 的 `mainRun`，断言 `cancelled=true` 且 `alreadyCancelled=true`。结果条目标注 `terminal-idempotent`，**不冒充首次取消运行中的 run**；首次取消仍由原核心流程执行。
- `POST /api/ai/runs/{runId}/resume`：终态 `ALREADY_CANCELLED` 的幂等调用，断言 `resumed=false` 且 `reason=ALREADY_CANCELLED`。该终态路径不调用模型，因此新增负载**不**使用 `modelCall`，也不占用模型名额；404/409/503 不会误触模型熔断。它仍刻意串行（`serial=true`）：后端进程内 `resuming` 单飞互斥会让重叠的终态续跑返回 409 `RESUME_IN_PROGRESS`，串行才能让每条请求都观察到真正的终态幂等结果。原核心的单次 `resume` 路径保持原有 `modelCall` 语义不变。

可能驱动模型的入口：

- `POST /api/ai/form/attachments`：每个请求运行一个**独立的** `attachmentWorkflow`，各自通过真实对话产生独立的 pending form，再上传、回灌并等到 done。生产代码对单字段重传执行 supersede，因此绝不对同一表单同一字段并发；独立表单从根上避免该竞争。每个 workflow 仍走 `modelCall` 限额，上传延迟单独记入该条目。
- `chat`、`confirm`、`frontend-tool-result`：不在负载阶段重放。真实首次决策由原核心模型工作流承担，D 套件可补充；不使用已决策 run 重放 409 来伪造负载。

`POST /api/ai/startup-resume/scan` 不进入新增负载阶段，只保留原核心的单次覆盖：后端可能在响应返回后异步驱动续跑，脚本侧 `modelCall` 无法持有或观测那段执行，因此不能用 N 次扫描声称满足模型并发上限。该路由不产生负载请求，也不会有负载条目。

独立资源写入：

- `POST /api/ai/session`：串行（`serial=true`）发起 N 次创建并记录每次延迟。`TimestampIdGenerator` 是毫秒发号器，同一毫秒并发取号会合法撞号；并发的 N 次请求会人为制造 503 `SESSION_ID_UNAVAILABLE`，因此这里改为串行测量。脚本不重试、不掩盖 503，真实非 2xx 仍使该条目失败。`sessionIdText` 只在断言成功后登记到 `createdSessions`。
- `DELETE /api/ai/session/{sessionId}`：只对“成功创建”的会话 ID 发删除，不按原计划索引的空位发请求。`configured=requested=成功创建数`，`skipped=N-成功创建数`；零目标时为 `SKIPPED`（不是 PASS/FAIL）。DELETE 本身仍按全局 API 池并发执行。每个目标仍必须 `complete=true`、`deferred=[]`、`errors=[]`；成功后从 `createdSessions` 摘除，失败则保留给 finally 重试。部分目标可以正常测量，但 POST 创建失败仍会使整体负载失败。
- `POST /api/ai/cancel/client-request`：每次使用独立 session 和独立 `clientRequestId`，断言 tombstone 形态 `cancelled=true`、`pendingRun=true`；session 进入 finally 会话 purge。
- `POST /api/ai/feedback`：每次使用独立 `messageId`，**只有断言 `saved=true` 后才登记**到待清理集合；finally 先按 `rating=0` 删除并断言 `saved=false` 且 `cancelled=true`，再由所属会话完整 purge 兜底，不遗留反馈行。

### 并发边界

- API 并发与模型并发是两套独立限制器：前者限制负载任务在途数，后者继续使用既有的 1..10 `EVAL_MODEL_CONCURRENCY`。`load.maxObservedApiInFlight` 与顶层 `maxObservedInFlight` 分别取证，不能互相替代；前者是任务在途峰值（附件任务等待模型名额时也计入），不是精确 HTTP 连接数峰值。
- 非串行条目按全局 API 池并发发起；`POST /api/ai/session` 与终态 `resume` 因上述原因串行，请求数仍达到 `EVAL_API_LOAD_REQUESTS`。因此“并发达到 `min(请求数, EVAL_API_CONCURRENCY)`”只适用于非串行条目。
- 附着 workflow 在整个生命周期内占用一个 API 名额，但只在 chat / frontend-tool-result / 终帧等待窗口占用模型名额。
- SSE replay 每次都是独立连接、独立 reader；失败也在 `finally` 移除共享终态 run。不共享响应体，也不引入第二套 SSE 解析逻辑，复用 `readSseRaw`；`readSseRaw` 只记录实际响应状态供错误对象取证，不重复消费 body，核心默认语义不变。
- 清理按资源来源分 scope：原始 27 项产生的清理错误进 `coreCleanupErrors`，负载产生的错误进 `load.cleanupErrors`；顶层 `cleanupErrors` 仍合并两者并参与整体 `complete`。27 个 coverage 条目的 `cleanup.done` 只看 `coreCleanupErrors`，不会被负载清理错误污染；`load.complete` 包含所有负载清理错误（反馈、会话、任务、活跃 run）。SSE 恢复的清理 scope 通过显式参数逐层传递（`settleRunAfterSseError`/`readSseRaw`/`readSseChat`/`startChat`/`callSse`）：原核心调用使用默认 `coreCleanupErrors`，负载 attachmentWorkflow 在 `recordCoverage=false` 时传 `apiLoad.cleanupErrors`，因此负载 SSE 恢复失败不会污染 core；未使用模块级“当前负载”标志，避免并发串扰。

## 27 路由压测去向矩阵

新增负载阶段共 24 个条目：27 个路由中 `chat`、`confirm`、`frontend-tool-result`、`startup-resume/scan` 不执行新增负载，`events` 拆为 probe 与 SSE replay 两个条目。

`×N` 表示 `EVAL_API_LOAD_REQUESTS` 次真实请求；“原核心”表示无论负载开关如何都会执行的单次真实工作流。

| # | 路由 | 本负载阶段 | 原核心模型工作流 | B 套件 | D 套件 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `POST /api/ai/chat` | 否 | 是：真实首次决策（create_task / KB / attachment） | — | 可补充首次决策负载 | 不重放已决策 run 的 409 |
| 2 | `POST /api/ai/confirm` | 否 | 是：真实首次确认 | — | 可补充首次决策负载 | 同一 pending 只决策一次 |
| 3 | `POST /api/ai/frontend-tool-result` | 否 | 是：真实 navigate_to / attachment 回灌 | — | 可补充首次决策负载 | 不伪造已决策回灌 |
| 4 | `GET /api/ai/events/{runId}` | 是：probe ×N + 真实 SSE replay ×N 到 terminal | 是：真实 chat 重挂与终帧 | — | — | probe 不替代 SSE 并发 |
| 5 | `POST /api/ai/cancel/{runId}` | 是：仅 terminal-idempotent ×N | 是：运行中 run 的首次取消 | — | — | 不冒充首次取消 |
| 6 | `POST /api/ai/cancel/client-request` | 是：独立 session + clientRequestId ×N | 是：单次预取消 tombstone | — | — | finally purge |
| 7 | `GET /api/ai/cancel/{runId}` | 是：只读 ×N | 是 | — | — | 终态取消旗标 |
| 8 | `GET /api/ai/startup-resume` | 是：只读 ×N | 是 | — | — | — |
| 9 | `POST /api/ai/startup-resume/scan` | 否：新增负载不执行 | 是：单次 | — | — | 响应后的异步续跑无法被脚本侧限流，不能用 N 次扫描声称满足模型并发上限 |
| 10 | `GET /api/ai/runs` | 是：只读 ×N | 是 | — | — | 列表含共享终态 run |
| 11 | `GET /api/ai/runs/{runId}` | 是：只读 ×N | 是 | — | — | 断言 `snapshot.runId` |
| 12 | `GET /api/ai/runs/current` | 是：终态 `found=false` ×N | 是：取消前 `found=true` | — | — | 终态清除会话 current 索引 |
| 13 | `POST /api/ai/runs/{runId}/resume` | 是：serial ×N，`ALREADY_CANCELLED`，不占模型名额 | 是（单次，沿用 modelCall） | — | — | 不冒充真实恢复 |
| 14 | `GET /api/ai/history/{sessionId}` | 是：只读 ×N | 是 | 是：删除后空历史 | — | — |
| 15 | `GET /api/ai/context/{sessionId}` | 是：只读 ×N | 是 | — | — | — |
| 16 | `GET /api/ai/health` | 是：只读 ×N | 是 | — | — | 200/503 均按既有语义 |
| 17 | `GET /api/ai/pending/{runId}` | 是：只读 ×N | 是 | — | — | 终态条目可为 CANCELLED |
| 18 | `POST /api/ai/feedback` | 是：独立 messageId ×N | 是 | — | — | 仅 saved=true 后登记；finally saved=false + cancelled=true |
| 19 | `GET /api/ai/feedback` | 是：只读 ×N | 是 | — | — | — |
| 20 | `GET /api/ai/kb/status` | 是：只读 ×N | 是 | — | — | — |
| 21 | `GET /api/ai/kb/docs` | 是：只读 ×N | 是 | — | — | — |
| 22 | `POST /api/ai/form/attachments` | 是：独立 attachmentWorkflow ×N | 是：单次真实附表 | — | — | 同表单同字段不并发，避免 supersede |
| 23 | `GET /api/ai/form/option-sources/{sourceId}` | 是：只读 ×N | 是 | — | — | 未注册 sourceId 400 只在核心 |
| 24 | `POST /api/ai/session` | 是：serial ×N（毫秒发号器避免人为撞号） | 是 | — | — | 不重试掩盖 503；成功才登记 |
| 25 | `DELETE /api/ai/session/{sessionId}` | 是：仅成功创建数；skipped=N-成功数 | 是 | 是：重复/并发 DELETE、空会话 | — | 零目标为 SKIPPED，不是 PASS/FAIL；键面完整性归 B 套件 |
| 26 | `GET /api/ai/tools` | 是：只读 ×N | 是 | — | — | — |
| 27 | `GET /api/ai/tenants` | 是：只读 ×N | 是 | — | — | — |

配置接口、`POST /api/ai/kb/reload`、普通 Task/Job 业务接口不进入本负载阶段，也不计覆盖。

## 模型驱动并发口径

全局限制器覆盖所有可能驱动模型的调用，而非只限制 `/chat`：

- `/api/ai/chat`；
- `/api/ai/confirm` 及批准后等待下一条宿主工具/终帧的后续执行；
- `/api/ai/frontend-tool-result` 及回灌后的后续执行；
- `/api/ai/runs/{runId}/resume`；
- `/api/ai/startup-resume/scan`。

限制器按“请求 + 必要的后续 SSE 执行窗口”持有名额，等待唤醒后必须重新检查在途数。主创建链保持有状态串行；独立 KB/attachment 场景由 6 个 worker 发起，worker 可排队但不得突破配置上限。已有 pending GF 的直接 `/api/ai/frontend-tool-result` 回灌不重新申请模型名额，真实熔断不得阻断该既有回执；回灌后的终帧等待仍按原语义进入 `modelCall`，真实熔断可拒绝该新窗口并触发清理。普通 KB/工具/状态/history/context/run/pending/tenant/feedback/DELETE 和 Task 清理请求不占用模型名额，可独立并发。原核心单次 `resume` 仍计入模型路径；新增负载只测终态 `ALREADY_CANCELLED`，该路径不调用模型，因此不占模型名额。

任一真实模型 SSE、后续执行或独立 worker 异常都会立即熔断调度：禁止新的 `acquireModel`，并 reject 全部等待者，后续 worker fail-fast 进入 `Promise.allSettled`。异常时不是继续压测，而是停止新模型流量，等待当前在途 worker settle，执行 finally 清理并失败退出。脚本自身 `AbortSignal.timeout` 主动取消单列 `SCRIPT_TIMEOUT_CANCEL`，不因这一客户端动作判定生产模型失败或触发模型熔断；该尝试仍 FAIL，不放宽健康断言。

真实 SSE 异常先熔断，再尝试取消 run 并确认 cancelled 终帧。脚本超时则先保留 pending/末帧证据，再取消并确认终帧，不把它当作模型失败。只有确认终帧后才允许当前异常名额安全递减；取消失败、恢复失败、`cancelled=false`、终帧不符或 runId 未知时，不递减名额、不唤醒新调用，并保留 `modelInFlight/slotsNotSafelyReleased`。`alreadyTerminal` 也必须重读权威终帧：仅 `DONE` 且无真实错误可按脚本超时恢复；`FAILED`、`error` 或真实错误码保存原 code 并仍 FAIL、熔断，不能归入 `SCRIPT_TIMEOUT_CANCEL`。取消恢复失败本身是真实清理错误，会以 `script-cancel-recovery` fail-stop，不能借“脚本取消”掩盖。真实模型熔断一旦触发不会解除；原始业务错误保留，取消/恢复错误另行追加到 `coreCleanupErrors` 与合并的 `cleanupErrors`。

## 关键链路

- `create_task`：真实 chat 产生确认门 pending，每个 SSE 帧一旦报告成功创建就立即登记任务号；随后用真实 `navigate_to` 待决项完成 `/frontend-tool-result`。
- KB：真实对话要求检索正式口径，并断言出现 `kb_search`；模型名额持续持有到正常 done 终帧，不能收到 tool_start 就提前释放。SSE 异常按上述 fail-stop 语义处理，没有 runId 时按清理未知失败并保留未安全释放名额。
- 附件：真实 `generative_form` 必须包含 attachment 字段，再上传小型文本文件并回灌合法 fileId；直接回灌 POST 不占新模型名额，done 等待才进入 modelCall 名额窗口，未达终态不删除 activeRuns。pending 与 done 分别记录，不在 pending 时提前写 done 断言。负载阶段改用独立 workflow，每个请求独占一张表单。
- 取消与恢复：取消真实 run 后核对取消旗标和 cancelled 终帧；原核心单次 `resume` 沿用 `modelCall` 和终态 `ALREADY_CANCELLED` 幂等语义；新增负载的终态 `resume` 不调用模型、不占模型名额，也不把 409 当恢复成功。
- SSE：fetch、HTTP 状态、content-type、getReader、帧处理与等待超时全部进入统一异常入口；恢复读取使用 `recoverOnError=false`，禁止递归取消。正式 events 统一经 `callSse` 记录 attempts，只有初始 `readSseChat` 错误记入 POST chat，后续 events 错误直接透传且保留历史 attempts。另以 `probe=1` 验证 JSON ready，以无 probe 的 `Accept: application/json` 验证 406，三者不可互相替代。负载 SSE replay 复用 `readSseRaw`，只关闭覆盖记录与错误恢复，不复制解析器；`readSseRaw` 记录实际 HTTP status 并在失败时附加到错误对象（不重复消费 body），负载条目的 `httpStatuses` 因此能保留“收到 200 后流失败”的事实。核心 `callSse` 的成功断言语义不变；失败记录新增 `httpStatus` 与 `stage`，`attempts` 保留 `stage`。
- 清理：finally 中取消活跃 run，删除创建的任务和会话，反馈 rating 置 0并检查 `cancelled=true`；附件由表单/会话清理链回收。会话删除必须同时满足 `complete=true`、无 deferred、无 errors。按来源分 scope：原始 27 项资源错误进 `coreCleanupErrors`，负载新增的任务/活跃 run/会话/反馈错误进 `load.cleanupErrors`；顶层 `cleanupErrors` 合并两者。27 个 coverage 条目的 `cleanup.done` 只看 core，`load.complete` 包含所有 load 清理错误。无真实下载、发布或正式数据导入。

**挂起 EOF 有界重挂**：仅当服务端正常 EOF、predicate 未达且未收到 `done`/`error` 时，`callSse` 才以当前 `recoverySeq` 作为 `lastSeq` 重连，最多 8 次、每次间隔 200ms；每次先取消旧 reader，累计 frames 并保持 seq 严格递增。预算用尽后 predicate 未达断言失败，才进入既有 model circuit 与 `settleRunAfterSseError`；已收到 `done`/`error` 但 predicate 不匹配时不再重连。`recoverOnError=false`（settle 恢复与 load replay）一律不重试，即使传入了 `eofRetry`。fetch 失败、非 200、content-type 不符、seq 非递增或 predicate 抛错会立即进入熔断/结算，不等待预算。失败文本中的 `lastSeq` 与 `type:name:seq` 帧列表来自最终 predicate 未达断言；其它错误保留原错误文本。`readSseRaw` 跨重连保留最近一次实际收到的 HTTP status：若最后一次 fetch 在响应前失败，错误上的 `httpStatus` 可能来自此前连接（例如 200），不一定属于最后失败连接。阶段标签（如 `after-frontend-result-suspended`）写入 coverage attempts，收到 200 后流失败仍保留该 200 事实。

**create_task fixture**：主 chat 使用“请调用 create_task 工具创建导出任务，不要调用 ui_snapshot 或其他工具。”作为明确的 HITL fixture 输入；这不改变 predicate，也不增加重试或纠正对话。自然语言稳定性仍由 D 套件负责。

## 脚本超时、HITL 回灌与失败取证

`TIMEOUT_MS` 是单次 HTTP/SSE 请求的客户端预算，默认 `120000` ms，不是整条 attachment workflow 的总预算，也不是服务端模型超时配置；只有显式配置为 `30000` ms 时才表示 30 秒。证据中的 `waitKind=MODEL_RESPONSE` 表示等待模型帧或模型终帧，`waitKind=HITL_RECEIPT` 表示上传/公开回灌等人工回执阶段；两者都不得仅凭客户端取消推断生产模型故障。不得通过延长该预算掩盖未知错误。

attachment 观察到真实 `generative_form` 后立即上传并直接调用公开 `/api/ai/frontend-tool-result`，不重新申请模型名额；脚本超时和真实熔断均不得阻止这个已有 pending 回执。终帧等待仍按原语义申请名额，真实熔断时可能被拒绝并清理，不能把回灌接受外推为健康 done。

每次 attachment 尝试最多写两次证据：取消前先写 pending、最后 SSE、取消来源和阶段时间线，取消后写终帧、真实 `code`、取消后 pending 和最终时间线，最终写盘成功才允许后续 run/session 清理。取消失败、终帧缺失、取消后 pending 缺失、runId 未知或证据不完整一律写 `FAILURE`，保持 `cleanupWithheld=true` 和 `evidenceComplete=false`，禁止重试取消或删除。权威终帧码优先于其他 run 的熔断错误，包括 `code=null`。普通成功附件也在会话清理前写最终证据。

## Cluster 本轮命令增量

`REDIS_CLUSTER=true` 且启用 load 时，`REDIS_CLUSTER_NODES=host:port,...` 必填。脚本在负载请求前后分别对每个配置主节点直接执行只读 `INFO commandstats`，保存 `load.clusterCommandStats.before/after` 快照，并在 `deltas` 中逐主节点计算 `commands` 与 `totalCalls` 的 after-before 增量。采集发生在负载清理前；前后缺快照、节点不可达、计数倒退或解析失败均使 load FAIL。

累计值只用于求差，不能单独证明本轮流量分布；增量为 0 也如实记录，不假设各主节点应均匀。该证据不替代 Cluster 拓扑、故障接管、多实例或 B 套件验收。未实际连接 Cluster 时状态为 `NOT_RUN`，纯函数测试和静态检查不构成 Cluster PASS。

## 与 B / D 套件的关系

B 套件（`scripts/perf/redis`）继续负责重复 DELETE、并发 DELETE 与最终键面，本文件不重复声称独立完成这些场景，证据依赖记录于结果 JSON：

- `perf_redis/scenarios.py:226-233`：重复与并发删除场景。
- `perf_redis/verify.py:248-337`：自有键清除、guard/pending 与 gen/ver 最终状态。
- `scripts/verification/ai-current-protocol-api.mjs:67-87`：重复删除与删除后空历史。

D 套件如用于非模型读取/状态接口的辅助负载，可采用独立并发；它不能替代上述模型路径统一限流，也不计入模型在途请求。`chat/confirm/frontend-tool-result` 的真实首次决策负载由既有模型工作流与 D 套件承担，本负载阶段不重放。本脚本不依赖 D 的存在。

本批只调整 Suite C 压测脚本、测试探针与本文档，不包含生产修复。Suite B 的 DELETE 场景和 Suite A 的 13 项均不在本批生产修复范围；本批结果不得用于宣称这些场景的生产问题已关闭，也不得据此修改其原压力、SLO 或通过结论。

## 验证边界

`attachment-regression.protocol-stub.mjs` 仅是**脚本协议单元辅助**：它自造协议 SSE/状态，结果固定标为 `evidenceLevel=SCRIPT_PROTOCOL_STUB_UNIT`、`productEvidenceStatus=NOT_RUN`，成功断言只写 `UNIT_PASS`，不得作为真实后端或真实产品 API 证据。用法为 `node scripts/perf/ai-api/attachment-regression.protocol-stub.mjs <scenario>`，每个场景独立进程；`PROBE_ARTIFACT` 可指定仓外结果文件。内部 `EVAL_ATTACHMENT_PROBE=true` 只用于隔离回归，不是完整 Suite C。

本轮新 RED 覆盖 `real-circuit-hitl-receipt`、`cancel-failure-withholds-cleanup`、`terminal-missing-withholds-cleanup`、`failed-race-still-trips`、`unknown-runid-withholds-cleanup`，分别证明真实熔断阻断回灌、取消/终帧失败后的二次取消与删除、FAILED 竞态误判和未知 runId 清理。修复后的对应结果仅为脚本协议单元 GREEN。

`attachment-real-backend.mjs` 是真实产品证据驱动：使用当前生产 jar、隔离 H2、公开后端 API 和独立 OpenAI 上游桩，产品 SSE/pending/run 状态均由后端产生，不由探针赋值。`real-circuit-hitl-receipt` 实测后端返回 `UPSTREAM_AUTH_FAILED` 并熔断后，另一 GF 仍获得公开回执且取消前 pending=`FRONTEND_RESULT`；随后按真实熔断语义取消，两个会话清理均为 `complete=true`。其结果 `PRODUCT_EVIDENCE_PASS` 只表示该负向场景断言成立，不是完整 Suite C 或健康模型 PASS。

`cluster-command-stats.test.mjs` 验证 after-before 纯函数；`cluster-command-stats-missing-command.test.mjs`、`cluster-command-stats-backwards.test.mjs`、`cluster-command-stats-missing-snapshot.test.mjs` 分别验证缺命令、倒退和缺快照拒绝，均独立进程执行。

真实模型、真实 Redis Cluster、多实例、真实 c=6 × 8 附件负载、完整 27 项与完整 SLO，以及取消失败、终帧缺失、FAILED 竞态的真实后端变体均为 `NOT_RUN`。未执行真实 Cluster 故障演练。标准 Maven `package` 曾在基线测试源码编译阶段因既有 `AiRateLimiter`、`ClientIpResolver` 缺失而失败；真实驱动使用 `-Dmaven.test.skip=true` 打包生产产物，未修改这些基线问题。

静态检查与单测只能证明纯函数、语法和变更范围，不构成运行时 PASS。实际执行时应同时核对：

- `coverage.complete` 与 27 项断言；
- `load.status`/`complete`（启用时）；PASS 条目应满足 `requested=completed=configured`、`failed=0`，配对 DELETE 零目标应为 `SKIPPED` 且 `configured=requested=0`；
- `load.skipped` 与 DELETE 条目的 `skipped=N-成功创建数` 一致；`scriptTimeoutCancelled` 只单列脚本取消，不能从 `failed` 中扣除；
- `attachmentEvidence.complete=true` 且 `missing=[]`，逐 run 证据先于清理落盘；脚本取消保留 `realCode`，真实错误仍 FAIL；
- Cluster 模式下 `load.clusterCommandStats.status=PASS`，每个主节点均有前后快照和本轮增量，不能只看累计值；
- `maxObservedApiInFlight` 不超过 `EVAL_API_CONCURRENCY`；它是负载任务在途峰值，不是精确 HTTP 连接数；
- `maxObservedInFlight` 不超过 `EVAL_MODEL_CONCURRENCY`；
- `cleanupErrors`（合并）、`coreCleanupErrors` 与 `load.cleanupErrors` 为空；coverage 条目的 `cleanup.done` 只由 core 决定。

`NOT_RUN`、`PENDING`、缺失条目和请求数不足都不是 PASS；`SKIPPED` 只表示配对 DELETE 没有目标，不是 PASS。
