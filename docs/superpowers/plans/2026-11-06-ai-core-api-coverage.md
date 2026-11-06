# 核心 AI 对话接口全 HTTP 覆盖实施计划

## 目标与范围

新增 `scripts/perf/ai-api/core-coverage.mjs` 与配套 README，覆盖 27 个核心 AI 对话 HTTP 路由变体。每项均显式登记、真实计时、语义断言、清理并输出证据；未执行、未断言或失败均使退出码非零。普通 Task/Job API 仅用于前置和清理。

排除：`AiConfigController` 全部接口及 `POST /api/ai/kb/reload`。KB 仅测 `GET /api/ai/kb/status`、`GET /api/ai/kb/docs`；知识检索经真实对话链验证，不虚构额外路由。

## 实施

- 以 Controller 和现有 verification/perf 脚本为契约来源，建立 27 项登记表与前置状态机。
- 通过真实 chat/确认门/宿主表单建立 pending，完成 confirm、frontend-tool-result、两个取消 POST、runs resume、startup scan 及真实 SSE events 重挂；不用 404/409 充当成功覆盖。
- 对 KB、tools、tenants、健康、run 列表/详情/状态、附件、反馈执行真实读写；配置独立 tenant/user/session/run 前缀并在 finally 清理。
- 重复 DELETE、并发 DELETE 与最终键面复用既有 B 套件证据依赖，本脚本不宣称独立完成这些场景。

## 模型驱动并发

- 所有可能驱动模型的接口共享一个全局限制器，包括 chat、confirm 后续执行、resume/scan、frontend-tool-result 后续执行及其它实际模型路径。
- 默认上限 6，可配 1..10；配置大于 10 必须拒绝启动。任一时刻在途模型驱动请求不得超过 10，普通读取/状态/删除接口使用独立并发。
- 输出 `modelConcurrencyConfigured` 与 `maxObservedInFlight`，证据能核对限流覆盖不只作用于 `/chat`。

## 隔离与证据

- 强制 `EVAL_ISOLATED_ENV=true`、`EVAL_ALLOW_WRITES=true`；远程另需 `EVAL_CONFIRM_REMOTE=true`。
- 不新增依赖，使用 Node 内置 fetch；不读取或硬编码密钥、节点，不执行真实下载、发布或正式数据导入。
- 输出 `coverage-results.json`，逐项记录 method/path/latencyMs/httpStatus/assertions/error/cleanup；仅 27 项全部真实执行且断言通过时写 `coverage.complete=true`。

## 验证边界

- 完成后独立静态审核纳入路由、模型并发上限、payload、清理、SSE 和退出码，执行 `node --check` 与定向 diff。
- 不实际运行压测、批量测试或服务，不宣称实测通过；无法由现有接口确定构造的基础设施依赖必须显式 FAIL，不静默跳过。

## 审核返修

- 限流等待改为 `while` 复核，所有模型路径在同一队列中受限；独立 KB/attachment 场景用 6 个 worker 验证，主创建链不并行。
- KB 与附件必须确认正常 done 终帧；取消主链必须确认 cancelled 终帧后才能移出 activeRuns。create_task 成功帧即时登记任务号。
- `call` 内部捕获网络异常并记录真实耗时、HTTP 状态（若可得）、错误和 attempts。
- 保留正式 SSE 的同时补充 `probe=1` JSON 与交叉产型 406 断言；输出增加 `configured`，与 `modelConcurrencyConfigured` 同值。
- 会话清理严格检查 `complete=true`、无 deferred/errors；反馈 rating=0 也纳入 finally 的 cleanupErrors。
- 证据路径统一使用 `scripts/verification/ai-current-protocol-api.mjs`。仍不执行运行时压测，返修后只做静态自审与 `node --check`。

## 第二轮精确返修

- 附件的 `frontend-tool-result` POST 与 done 合并进同一个 `modelCall`，POST 后无名额空窗。
- 两个 SSE 读取器在首个 runId 出现时立即登记 activeRuns，再做 seq/create_task/其它断言；异常统一进入 `settleRunAfterSseError`，先取消并确认 cancelled 终帧，未得 runId 则记清理未知失败。
- 恢复读取通过 `recoverOnError=false` 防止递归取消；恢复完成后才允许模型名额释放。
- 正式 events 统一由 `callSse` 记录成功/异常 attempts；初始 chat 与后续 events 错误分开归因且错误字段不被后续覆盖。附件 pending 与 done 分开断言。
- 保持 while 限流、6 worker、即时任务登记、严格 DELETE 清理、probe/406、configured 和反馈 rating=0 清理。第二轮仍仅做静态审核与 `node --check`，不宣称运行时通过。

## 第三轮 fail-stop 重构

- 模型调度增加永久熔断状态。任一模型 SSE、后续执行或独立 worker 异常立即禁止新 acquire，并 reject 当前全部等待者；后续 worker fail-fast，经 `Promise.allSettled` 等待在途 worker 后进入清理和失败报告，不继续压测。
- 正常完成可释放名额；异常路径只有取消 run 且确认 cancelled 终帧后才允许当前名额安全递减，但熔断不解除、不唤醒等待者。取消/恢复失败、`cancelled=false`、终帧不符或 runId 未知时保留 `modelInFlight` 与 `slotsNotSafelyReleased`，不得制造安全释放假象。
- `settleRunAfterSseError` 不再经 `cleanupOne` 吞错：保留首个业务错误并原样重抛，取消/恢复错误追加到 cleanupErrors。恢复读取仍显式使用 `recoverOnError=false`。
- chat 与 events 的 fetch、非 200、content-type、getReader、reader、seq、create_task/taskId 解析、目标帧/done 超时及终帧语义断言全部进入统一 SSE 生命周期异常入口；有 runId 即取消确认，无 runId 记录清理未知并熔断。
- `startChat` 仅把初始 `readSseChat` 错误记为 POST chat；后续 `callSse` 错误只记 events 并直接透传。`record` 不再用包装错误替换原业务错误，成功与失败 attempts 均保留。
- 保持附件 POST/done 同一名额窗口、6 个独立 worker、while 复核、runId/任务号即时登记、严格 DELETE、probe/406、configured 与反馈 rating=0 清理。结果增加 `modelCircuitBreaker`，任一熔断均使 `coverage.complete=false`。第三轮仍只做静态审核与 `node --check`，不宣称运行时通过。
