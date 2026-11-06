# 核心 AI 对话接口覆盖工具

`core-coverage.mjs` 使用 Node 内置 `fetch`，对 27 个核心 AI 对话 HTTP 路由逐项执行真实请求、计时和语义断言。它不是路径清单或健康检查脚本：任一项未执行、未断言、失败或清理失败都会使退出码非零。

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
node scripts/perf/ai-api/core-coverage.mjs
```

输出为 `$OUT_DIR/coverage-results.json`，逐项包含：

- `method/path/latencyMs/httpStatus/assertions/error/cleanup`；
- `configured` 与 `modelConcurrencyConfigured`（同一配置值）、`independentModelWorkers=6` 以及 `maxObservedInFlight`；
- `modelCircuitBreaker.failed/phase/error/modelInFlight/slotsNotSafelyReleased`，用于保留熔断及未安全释放名额的事实；
- `coverage.complete`、`notRun`、清理日志和致命错误。

只有 27 项全部真实执行且断言通过、清理也完成、模型调度未熔断时，`coverage.complete=true`。`maxObservedInFlight` 不得超过配置值，配置值不得超过 10。

## 模型驱动并发口径

全局限制器覆盖所有可能驱动模型的调用，而非只限制 `/chat`：

- `/api/ai/chat`；
- `/api/ai/confirm` 及批准后等待下一条宿主工具/终帧的后续执行；
- `/api/ai/frontend-tool-result` 及回灌后的后续执行；
- `/api/ai/runs/{runId}/resume`；
- `/api/ai/startup-resume/scan`。

限制器按“请求 + 必要的后续 SSE 执行窗口”持有名额，等待唤醒后必须重新检查在途数。主创建链保持有状态串行；独立 KB/attachment 场景由 6 个 worker 发起，worker 可排队但不得突破配置上限。普通 KB/工具/状态/history/context/run/pending/tenant/feedback/DELETE 和 Task 清理请求不占用模型名额，可独立并发。`resume` 的终态幂等调用也计入模型路径，避免把潜在恢复执行漏出限制器。

任一模型 SSE、后续执行或独立 worker 异常都会立即熔断调度：禁止新的 `acquireModel`，并 reject 全部等待者，后续 worker fail-fast 进入 `Promise.allSettled`。异常时不是继续压测，而是停止新模型流量，等待当前在途 worker settle，执行 finally 清理并失败退出。

SSE 异常先熔断，再尝试取消 run 并确认 cancelled 终帧。只有确认终帧后才允许当前异常名额安全递减；取消失败、恢复失败、`cancelled=false`、终帧不符或 runId 未知时，不递减名额、不唤醒新调用，并保留 `modelInFlight/slotsNotSafelyReleased`。即使恢复成功，熔断状态也不会解除，不会恢复后续模型流量。原始业务错误保留，取消/恢复错误另行追加到 `cleanupErrors`。

## 关键链路

- `create_task`：真实 chat 产生确认门 pending，每个 SSE 帧一旦报告成功创建就立即登记任务号；随后用真实 `navigate_to` 待决项完成 `/frontend-tool-result`。
- KB：真实对话要求检索正式口径，并断言出现 `kb_search`；模型名额持续持有到正常 done 终帧，不能收到 tool_start 就提前释放。SSE 异常按上述 fail-stop 语义处理，没有 runId 时按清理未知失败并保留未安全释放名额。
- 附件：真实 `generative_form` 必须包含 attachment 字段，再上传小型文本文件并回灌合法 fileId；POST 与 done 处于同一个 modelCall 名额窗口，未达终态不删除 activeRuns。pending 与 done 分别记录，不在 pending 时提前写 done 断言。
- 取消与恢复：取消真实 run 后核对取消旗标和 cancelled 终帧；`resume` 使用终态 `ALREADY_CANCELLED` 的幂等语义，不把 409 当恢复成功。
- SSE：fetch、HTTP 状态、content-type、getReader、帧处理与等待超时全部进入统一异常入口；恢复读取使用 `recoverOnError=false`，禁止递归取消。正式 events 统一经 `callSse` 记录 attempts，只有初始 `readSseChat` 错误记入 POST chat，后续 events 错误直接透传且保留历史 attempts。另以 `probe=1` 验证 JSON ready，以无 probe 的 `Accept: application/json` 验证 406，三者不可互相替代。
- 清理：finally 中取消活跃 run，删除创建的任务和会话，反馈 rating 置 0并检查 cancelled=true；附件由表单/会话清理链回收。会话删除必须同时满足 `complete=true`、无 deferred、无 errors，任一不满足都计入 cleanupErrors。无真实下载、发布或正式数据导入。

## 与 B / D 套件的关系

B 套件（`scripts/perf/redis`）继续负责重复 DELETE、并发 DELETE 与最终键面，本文件不重复声称独立完成这些场景，证据依赖记录于结果 JSON：

- `perf_redis/scenarios.py:226-233`：重复与并发删除场景。
- `perf_redis/verify.py:248-337`：自有键清除、guard/pending 与 gen/ver 最终状态。
- `scripts/verification/ai-current-protocol-api.mjs:67-87`：重复删除与删除后空历史。

D 套件如用于非模型读取/状态接口的辅助负载，可采用独立并发；它不能替代上述模型路径统一限流，也不计入模型在途请求。本脚本不依赖 D 的存在。

## 验证边界

本次仅完成实现与静态审查，未实际执行压测、批量测试或服务。`node --check` 与定向 diff 只能证明脚本语法和变更范围，不构成 27 项运行时 PASS。实际执行时应同时核对 `coverage.complete`、`maxObservedInFlight`、各项断言和清理结果。
