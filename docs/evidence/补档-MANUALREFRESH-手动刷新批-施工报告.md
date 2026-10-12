<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\施工报告-手动刷新批.md`（7247 B，sha256 `3b1e796f298398778d8e26ecb606acd4048d961dbd2b4b4e78383160dab04b8d`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 施工报告 · 手动刷新批（TDD）

## 署名（DC-19 实证）

- **实际运行模型（施工主 agent）**：`kimi-code/kimi-for-coding`（profile=agent，thinkingEffort=high）。
- **CU 会话 id**：`session_856bb412-5a36-4c00-b41b-610cd406fe01`（会话目录 `~/.kimi-code/sessions/wd_temp_38dcbf7ced2f/session_856bb412-…/`，state.json `"id"` 字段实证）。
- **wire modelAlias 实证**：本会话 `agents/main/wire.jsonl` 首条 `{"type":"profile.bind","agentId":"main","modelAlias":"kimi-code/kimi-for-coding","profileName":"agent","thinkingEffort":"high",…}`（2026-10-12 11:41 抓取；全文件 8 条 modelAlias 记录均为 `kimi-code/kimi-for-coding`）。
- **下沉子智能体（机械扫描，显式钉 DeepSeek/deepseek-flash）**：agent-599（P1 删面扫描）、agent-600（P2/P3 复核）、agent-604（S5 后端 TASK_CHANGED 扫描）。
- 施工卡 sha256 开工核验：`c5512466…183765`，与卡面一致。

## 基线与验收（全部实测跑绿）

| 项 | 基线（HEAD 45b2c6a） | 完工 | 结果 |
|---|---|---|---|
| 前端 vitest | 19 文件 / 207 | 18 文件 / **202** | 全绿 |
| typecheck（vue-tsc --noEmit） | 绿 | 绿 | 全绿 |
| build（vue-tsc + vite） | 绿 | 绿（24.7s） | 全绿 |
| scripts/ci/check-docs.mjs | 6/6 | **6/6** | 全绿 |
| 后端 mvn test | **442** | **442**（0F 0E）BUILD SUCCESS | 全绿 |

- 测试基数变动：删 task-events.spec(9)+lifecycle(3)+wait-job-final(7)+polling 双速/接线(4+1)＝24；新增 task-center-view(4)+watch-publish-outcome(8)+看门狗/单节奏改写净增 7 ⇒ 207−24+19=202。
- 后端 442 不变：TaskSseServiceTest.taskFrameFormatIsUnchanged 契约改写（TASK_CHANGED→JOB_PROGRESS 帧形态），用例数不减（卡片"必改"项）。
- check-docs ⑤ 口径说明：命名纪律按 `git ls-files` 全量读盘；施工期删除未 commit 时清单仍列已删文件。验收时**临时 `git add -A` 使清单反映删除 → 6/6 通过 → `git reset` 还原 index**，最终只留工作树改动（禁 commit 铁律未破，HEAD 仍 45b2c6a）。
- E2E 桩三场景由假时钟单测化身全绿：①任务中心全静止 120s `GET /api/tasks` 恰好 1 次（挂载那次）+`setInterval` 0 次+零 EventSource（task-center-view.spec）；②断链演练：注入 10s 看门狗 → 降 3s polling → 新帧回 realtime 并二次降级可用（job-watch-channel.spec）；③发布等待成/败均收敛、300s 出口在且终态即清（watch-publish-outcome.spec）。

## S1 删任务中心自刷 + SSE 订阅池（M1+F2）TDD

- RED：新建 task-center-view.spec（4 锁：不起周期表/零 EventSource/手动刷新按钮/取消后本地 refreshNow）2 红灯 → GREEN 195。
- 删 `utils/task-events.ts`、spec×2；`stores/task.ts` 删 startAutoRefresh/stopAutoRefresh/startTaskEvents/syncTaskEvents/closeTaskEvents/applyJobProgress/hasActiveJob/taskEventTargets/taskEventSubscriptionCount/autoRefresh 字段与常量 TASK_AUTO_REFRESH_MS/TASK_IDLE_REFRESH_MS，load() 去 syncTaskEvents/resync；`refreshNow` 简化为手动通道（loading 合并补发保留 F2）。
- `TaskCenterView.vue`：onMounted 仅 `load()` 一次，卸载钩子删；刷新按钮接 `refreshNow()`；取消成功后本地 `refreshNow()`；模板副标题与头部注释同步改写。`types/sse.ts` 删 TASK_CHANGED 成员与 TaskChangedPayload；`api/tasks.ts` 注释改写（job-watch 为唯一消费方）。

## S2 polling.ts 瘦身（M2+F1）TDD

- RED：polling-controller.spec 改单节奏契约 + contract-slimmed 负断言 1 红灯 → GREEN 196。
- 删 shouldRun/desiredIntervalMs/fallbackIntervalMs/resync/isPaused/导出 CATCHUP_JITTER_MS；保留单 intervalMs+run、hidden 停表/visible 0~250ms 错峰补刷、stop、refreshNow、setIntervals。AiPanel（仅 intervalMs/run+start/stop）与 job-watch 依赖项按 P3 逐项复核后在用保留，无偏差。

## S3 job-watch 收敛 + 帧空闲看门狗（M3+F3）TDD

- RED：job-watch-channel.spec 7 看门狗用例全红 → GREEN 200。
- 删 `JOB_REALTIME_FALLBACK_POLL_MS`（30s 保底）；新增 `FRAME_IDLE_WATCHDOG_MS=90_000`（可注入 `frameIdleWatchdogMs`）；复位源仅 SSE 帧/onopen（polling 快照不复位，F3 专例锁）；触发降 3s polling、新帧回 realtime；注释头部与降级判据改写为新口径（有意反转一期 D8）。实现注：realtime 期间控制器不 stop（否则 refreshNow 被 F1 闸吞），以 setIntervals 推 2^31-1（32 位安全上界，>该值会溢出成 ~1ms 周期）等价停表。

## S4 消灭 waitForJobFinal 1s 裸轮询（M4+F4）TDD

- RED：watch-publish-outcome.spec 8 锁 7 红 → GREEN 202；旧 wait-job-final.spec 删除。
- `api/jobs.ts` 删 waitForJobFinal/WaitJobOptions/WaitJobFinalOutcome/JobWaitAbortedError；`watchPublishOutcome(taskId, jobId, signal?)` 改同步返回 dispose，内部 watchJob + 300s 独立墙钟（`PUBLISH_WATCH_WALL_CLOCK_MS`，onFinal 即清）+ `taskId:jobId` 注册表判重不双开 + watchingJobId 闸与单次提示移植 onFinal（applyPublishOutcome：refreshCurrent→FAILED 拉 issues 组装 Q4）；onSnapshot 接替 onTick 直更 currentJobs。workspace.ts:452 传 `event.taskId` 并持有 dispose（新一轮先收旧）。

## S5 后端摘 TASK_CHANGED（M5+F5）

- TaskSseService 删 @EventListener onTaskChanged（92-101 块）与 EventListener import；TaskService 删 publishTaskChanged 定义 + 9 处调用点 + eventPublisher 字段/import；删 TaskChangedEvent.java；javadoc/注释（TaskSseService sendFrame、TaskService delete、前端 types/api.ts）同步改写；`/api/tasks/{id}/events` 端点与 JOB_PROGRESS/JOB_DONE 发布保留（P2 实证 job-watch 消费）。零消费者经全量扫描确认（大小写不敏感复核仅 4 文件涉入）。

## 偏差与说明

- **C1 原替换未披露声明（收口修补登）**：S4 施工时 `watchPublishOutcome` 自行添加了 `AbortSignal` 参数与 abort 提示分支，超出了施工卡 M4「watchJob 返回 dispose、调用点持有 dispose」的原口径，且生产调用点（workspace run_job）从未传 signal ⇒ abort 分支为死代码、对应 spec 属不可达路径锁（红队 MAJOR-1）。收口修 C1 已按「以既有实现形态为准、不得两套并存」处置：摘除 signal 参数与 abort 分支，取消链路唯一化为 dispose 形态（调用点持有、轮次收尾调用，静默收摊），spec 用例改为锁生产可达的 dispose 路径（18 spec/202 基数不变）。
- hasActiveJob：store 外零代码引用（仅 1 处注释）→ 按卡删除。
- mvn 环境：bash PATH 无 mvn，使用 IDEA 捆绑 Maven 3.9.16（JDK 21）跑测，前后端基线/验收同口径。
- check-docs ⑤ 的临时 stage→reset 见上"口径说明"。

## 变更清单（23 路径，未 commit）

M：TaskService/TaskSseService/TaskSseServiceTest（后端）、jobs/tasks/task/workspace/types(api,sse)/utils(job-watch,polling)/views(TaskCenterView)/AiPanel、tests(job-watch-channel,polling-controller,polling-predicate)；D：TaskChangedEvent.java、task-events.ts、task-events.spec.ts、task-events-lifecycle.spec.ts、wait-job-final.spec.ts；新增：task-center-view.spec.ts、watch-publish-outcome.spec.ts。
