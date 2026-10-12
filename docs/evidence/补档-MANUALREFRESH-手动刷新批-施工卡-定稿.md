<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\施工卡-手动刷新批.md`（6018 B，sha256 `c55124669e0e67ed119dacf579535150a5aac9f16e6b93487a8ef07832183765`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 施工卡 · 手动刷新批（K2.8 施工，TDD）

- 决策依据：`<SCRATCH>\r137-guard\决策卡-手动刷新批-定稿.md`（M1~M6 + F1~F6 处置，K3 代签，三方会审两轮已闭环）。
- 基线 HEAD = 45b2c6a；主仓库 = <MAIN_REPO>（唯一可操作；严禁碰 <REPO_ROOT> 与 <REPO_ROOT>）。
- **施工期间禁 commit**（commit 归提交官）；只改工作树。
- 本卡行号锚点以 45b2c6a 取，**开工前置重取**：行号会漂移，动手前按当时工作树重扫。

## 开工前置（必做，先做再动手）

1. **P1 全量引用扫描定 M1 删面**：grep 符号族 `task-events|TaskEventTarget|startTaskEvents|closeTaskEvents|syncTaskEvents|taskEventTargets|taskEventSubscriptionCount|hasActiveJob|startAutoRefresh|stopAutoRefresh` 于 frontend/src 与 frontend/tests 全目录，列出全部命中文件:行号——删面以扫描结果为准（会审实证本卡文字版连带面不全）。
2. **P2 复核 job-watch SSE 通道源**：确认 `utils/job-watch.ts` 的 EventSource 订阅端点（是否同为 `/api/tasks/{id}/events`）——决定 S5 后端摘除面边界。
3. **P3 AiPanel 消费项核对**：`components/AiPanel/AiPanel.vue:847-854` 倒计时 tick 使用 `createPollingController` 的哪些参数/方法——S2 删面不得波及（F1）。

## S1 删任务中心自刷 + SSE 订阅池（M1+F2）

- 删文件 `frontend/src/utils/task-events.ts` 整文件；删其 spec（实测相关 spec 共 4 个文件，以 P1 扫描为准）。
- `stores/task.ts`：删 startAutoRefresh/stopAutoRefresh（:308-327/:361 一带）、订阅编排（:342-391）、`taskEventSubscriptionCount`(:135)、`taskEventTargets` getter(:172-189)、`load()` 内 `syncTaskEvents()`(:224) 与 `autoRefreshController?.resync()`(:228)、模块常量/import（:34-36/46/49/82/97 一带，以扫描为准）。
- **保留**：`refreshNow()`(:260-273) 与 `refreshPending/flushPendingRefresh`（手动刷新通道，F2）。
- `TaskCenterView.vue`：onMounted(:353) 改为 `taskStore.load()` 一次；删 startAutoRefresh/closeTaskEvents 挂载卸载(:353-363)；手动刷新按钮接 `refreshNow()`；用户写操作（取消任务等）成功后本地 `refreshNow()` 一次。
- `hasActiveJob`(:159-163)：P1 复核零引用后删；仍有引用则保留并在施工报告说明引用方。
- `types/sse.ts`：删 TASK_CHANGED 死类型面（:337/339 一带 TaskEventType 成员与 TaskChangedPayload，以 P1/P2 复核为准）。
- 假文档同步改写：`stores/task.ts:7-14/:94/:169-170`、`TaskCenterView.vue:151-159`、`api/tasks.ts:114` 注释（不得再描述已删机制）。

## S2 polling.ts 瘦身（M2+F1）

- 保留：`createPollingController` 基础形态（intervalMs 单节奏 + run）、visibilitychange hidden 停表、stop/dispose、refreshNow（job-watch 与 AiPanel 依赖项以 P3 为准保留）。
- 删除：`shouldRun` 双速语义 / `desiredIntervalMs` 变节奏 / `resync` / `CATCHUP_JITTER_MS` / `isPaused`——逐项先 grep 引用，AiPanel 或 job-watch 在用则保留该项并报告偏差。
- 对应 spec 同步删改（polling-controller.spec.ts 双速/补刷用例）。

## S3 job-watch 收敛 + 帧空闲看门狗（M3+F3）

- 删 realtime 30s 保底（`JOB_REALTIME_FALLBACK_POLL_MS` 路径）；保留 polling 通道 3s 兜底。
- 新增**帧空闲看门狗**：常量 `FRAME_IDLE_WATCHDOG_MS = 90_000`（**可注入**，测试/E2E 注入小值）；**仅 SSE 帧到达与 onopen 复位**（polling 快照不复位，F3）；触发 ⇒ 通道判死 ⇒ 降 3s polling；此后新 SSE 帧到达 ⇒ 回事件驱动。
- 触发源收敛为三类：SSE 帧 / onopen 一发 / 兜底表；inflight 守卫保留。
- 注释改写：`job-watch.ts` 头部与 :70-73 一带「无帧是正常态」（一期 D8 口径）改为新口径——**此为有意反转**，新口径 = 90s 无帧即判通道死亡降级。

## S4 消灭 waitForJobFinal 1s 裸轮询（M4+F4）

- 删 `api/jobs.ts:73-91` 轮询循环。
- `stores/task.ts watchPublishOutcome`(:514-530) 改复用 watchJob：
  1. watchJob 补 taskId 管道（现仅收 jobId）；
  2. 300s 墙钟 = 独立定时器，**终态到达即清除**；
  3. watchingJobId 闸（F4 原有逻辑）与「单次提示」语义移植进 onFinal；
  4. watchJob 返回 dispose 而非收 signal——`stores/workspace.ts:452` 调用点持有 dispose，组件卸载/轮次收尾调用；
  5. 注册表归属单一：AI dispatch 触发的 publish watcher 与向导页 watcher 不得双开（以 taskId+jobId 判重）。
- 终态 issues 拉取落 onFinal 内。

## S5 后端摘 TASK_CHANGED 发布点（M5+F5）

- `TaskSseService.java:92-101` onTaskChanged 监听摘除；`TaskService.publishTaskChanged`(:300 一带) 及 `TaskChangedEvent` 定义 grep 全量引用后随删（零消费者才删，有引用报告偏差）。
- **必改**：`TaskSseServiceTest.java:161-166` 断言 TASK_CHANGED 发布的用例同步删改（不删 mvn test 断）。
- `/api/tasks/{id}/events` 端点保留（JOB_PROGRESS/JOB_DONE 仍被 job-watch 消费，以 P2 复核为准）。

## 验收口径（TDD：先写失败测试再施工）

1. vitest：删除面下修基数 + 新增断言（任务中心不起周期表/手动刷新路径/看门狗降级与恢复/300s 墙钟/onFinal 收敛/hidden 期零请求）；typecheck、build 绿。
2. 后端 `mvn test` 全绿（442 基数随 S5 删改同步下修并记录新基数）。
3. `scripts/ci/check-docs.mjs` 6/6。
4. E2E 桩：任务中心全静止可见 ≥60s `GET /api/tasks` 恰好 0 次 + 零 EventSource 连接；断链演练（杀 SSE → 注入小阈值看门狗降级 3s → 恢复回 0）；发布等待功能等价（成/败均收敛、300s 出口在）。
5. 施工报告 ≤8KB，首页署名**实际运行模型 + CU 会话 id + wire modelAlias 实证**（DC-19，照抄卡头不合格）；机械读取/扫描继续下沉子智能体（显式钉 DeepSeek/deepseek-flash）。
