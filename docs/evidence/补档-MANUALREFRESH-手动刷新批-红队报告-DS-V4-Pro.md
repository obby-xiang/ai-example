<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\红队报告-手动刷新批.md`（6051 B，sha256 `7abdddf173010b96df436abb1798781686d0206da74d8216b25ebca284cbd4bd`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 红队报告 · 手动刷新批（DS V4 Pro 推翻式审查）

- 双核验：红队卡 sha256 = `bda367ed…c2477d` ✓；diff 锚 sha256 = `a2d9d708…bb28fa7` ✓（与任务书一致）。
- 基线 HEAD = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`；工作树 23 路径（6D/15M/2??）。
- 署名（实测）：实际运行模型 **DeepSeek/deepseek-v4-pro（经 Kimi Code 主代理）** · 会话 id **session_45401319-a996-41b5-bac5-c2efa213e438** · wire modelAlias 实证 = 本会话 `agents/main/wire.jsonl` 首条 `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",…}`（全文件 8 条 modelAlias 均同，grep 去重实证，非照抄卡头）。
- 结论：**带条件 PASS**（1 MAJOR + 3 minor + 3 OBSERVATION）。

## 攻击面 1 删除面残留引用 —— minor
- backend：`TaskChangedEvent` 全仓 .java **0 命中**；TaskService 删 `eventPublisher`/`ApplicationEventPublisher`/`publishTaskChanged`(9 处)；TaskSseService 删 `@EventListener onTaskChanged`。干净。
- frontend：`types/sse.ts:337` `TaskEventType` 已删 TASK_CHANGED 成员。干净。
- 残留（typecheck 抓不到、非运行时）：`scripts/verify-e2e.ps1:857` 注释仍写「帧类型 JOB_PROGRESS/JOB_DONE/**TASK_CHANGED**/HEARTBEAT」（陈旧）；`docs/technical-design.md:185/278` 仍写「→ TASK_CHANGED」「TaskChangedEvent→SSE 推送」。均为文档面漂移，无字符串/动态 import/模板/反射/事件名运行时残留。

## 攻击面 2 泄漏与生命周期 —— PASS
- 90s 看门狗：`job-watch.ts:242` `dispose()` 内 `clearWatchdog()` 真清（终态自收/外部 dispose 双路径同出口）。
- 300s 墙钟：`task.ts:391` `onFinal` 首行 `clearWallClock()` + `:367` `dispose()` 内再清（幂等）；onError/abort 同走 dispose。真清。
- `poller.stop()`（清 interval+卸载 visibilitychange）+ `source.close()` + `onDisposed` 注册表注销均到位。无泄漏路径。

## 攻击面 3 2^31-1 停表 hack —— minor
- `job-watch.ts:97` `REALTIME_IDLE_MS=2_147_483_647`，`:105` `setIntervals({intervalMs:2^31-1})` 等价停表。**功能正确**：24.8 天永不触发、refreshNow 走 `invoke` 的 `!started` 闸不受影响、降级 `setIntervals(3s)` 清旧表。
- 两处瑕疵：①注释「>2^31-1 溢出回绕成 ~1ms」**不准确**（现代 HTML 规范是钳制到 2^31-1，非回绕）；②更干净实现（控制器加 armed/dormant 态清表保 refreshNow）被绕过——但绕过理由（stop 会把 refreshNow 一并关死）在 job-watch.ts:85-87 已明示，属知情取舍，非夹带。

## 攻击面 4 回归狩猎 —— **MAJOR（AbortSignal 未接线）**
- 卡 M4 明列「workspace.ts:452 传入 AbortSignal」；施工报告 S4 只写「传 event.taskId 并持有 dispose」，**偏差节未披露此替换**。实测唯一生产调用点 `workspace.ts:457` = `taskStore.watchPublishOutcome(event.taskId, job.id ?? 0)` —— **无 signal**。`watchPublishOutcome(taskId,jobId,signal?)` 的 `signal?` 参数 + `onAbort` 监听 + `signal.addEventListener('abort')` + spec `abort-upfront-no-unhandled` 全成**生产不可达死代码**，运行期取消无法提前收掉发布跟踪（只能等终态/300s）。→ 复现：workspace.ts:457 只传 2 参；task.ts:401-403 的 abort 监听无触发源。
- 其余回归面**均正确**：向导三态（SSE 正常→realtime 0 周期；显式断链→onerror 降 3s；慢分片>90s→看门狗降 3s，良性自愈振荡）；F4 `watchingJobId` 闸（task.ts:356/418）+ `publishWatchers` 注册表判重（:348-351）+ workspace 跨键收尾 `publishWatcherDispose?.()`（:456）三位一体防双开/防旧结局覆盖；300s 墙钟边界 OK；任务中心手动刷新（onMounted 一次 load + 刷新按钮 + 取消后 refreshNow）链路完整。

## 攻击面 5 后端摘除面 —— PASS
- `/api/tasks/{id}/events` 端点保留：`subscribe` 仍发 HEARTBEAT 首帧（TaskSseService:87），`publish` 保留；JOB_PROGRESS（四 runner publishProgress）与 JOB_DONE 发布链完整，job-watch 消费无缺口。
- TaskChangedEvent 删除后事件总线**无订阅残留**（.java 全仓 0 引用）；`TaskSseServiceTest.java:161` 已改写契约（TASK_CHANGED→JOB_PROGRESS 锁帧形态）。

## 攻击面 6 覆盖空洞 —— minor
- 新增/改写 spec 覆盖到位：`job-watch-channel.spec` 看门狗 7 用例（90s 缺省/降级/F3 快照不复位/onopen 复位/dirty）、`watch-publish-outcome.spec`（300s 墙钟/F4 闸/abort-upfront）、`task-center-view.spec`（手动刷新/取消后刷新）、`polling-controller.spec`（单节奏/dirty/stop）、`polling-predicate.spec`（改测 refreshNow）。
- 唯一空洞：abort **中途**触发路径（signal 在跟踪期间 abort→onAbort→dispose）无用例，且与攻击面 4 的 MAJOR 同源（生产无触发源）。

## 攻击面 7 零夹带核验 —— PASS
- 按卡要求重生成：对 2 个未跟踪文件逐个 `git add -N`（非 -A，不吞删除段）→ `git diff` → `git reset -q` 还原。产物 sha256 = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`，与锚**逐字节一致**（175069 字节）。无夹带、无范围外改动混入。

## 结论分级
- **MAJOR-1**：AbortSignal 未接线（卡 M4 要求未落地 + 施工报告未披露 + abort 死代码/假绿 spec）。处置：接线 AbortSignal（或施工报告补偏差声明 + 登记 R-94 收口）。
- **minor-1**：2^31-1 停表「溢出回绕 ~1ms」注释不准（规范为钳制）。
- **minor-2**：verify-e2e.ps1:857 陈旧注释仍列 TASK_CHANGED。
- **minor-3**：docs（technical-design 等）仍描述 TASK_CHANGED/TaskChangedEvent 为现状。
- **OBSERVATION**：docs/evidence 大量历史引用 waitForJobFinal/startAutoRefresh/hasActiveJob（非运行时，登记即可）。

综合：除 MAJOR-1（AbortSignal 未接线）需在收口修或偏差登记中处置外，其余为注释/文档漂移与知情取舍，不构成放行阻断。
