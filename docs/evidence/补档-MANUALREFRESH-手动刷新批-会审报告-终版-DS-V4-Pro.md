<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-终版-DSPro.md`（7191 B，sha256 `ed891de35dbf606e7305bc820771b81d52477e22c9cf76e46c6f6f786c991a81`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 手动刷新批 · 定稿差额评审报告（DSPro 方）

- 会审对象：`r137-guard/决策卡-手动刷新批-定稿.md`，sha256 = `0b0f864e536efe6919145a18ea095afe81405658d1582a940b99784eb26ae388`，与任务书给定值**一致**（sha256sum 实测）。
- 基线核实：主仓 `<MAIN_REPO>` HEAD = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`（= 45b2c6a），与卡声明一致。
- 署名：实际运行模型 **DeepSeek（deepseek-v4-pro，经 Kimi Code 主代理）** · 会话 id **DC-19** · 日期 2026-10-12。
- 评议方式：卡外独立实读（task.ts / task-events.ts / polling.ts / job-watch.ts / TaskCenterView.vue / AiPanel.vue / api/jobs.ts / TaskSseService.java / TaskSseServiceTest.java），无其它会审方结论参考。

## 逐条评议

**M1 任务中心纯手动 —— 方向采纳，但「连带面列全」不成立（多处遗漏）。** 删 startAutoRefresh + task-events.ts 整文件 + 池编排，方向正确；但卡列举的连带面**不完整**，以下均须删/改写、卡未点名：
- `load()` 内两个调用点：`this.syncTaskEvents()`（task.ts:224）、`autoRefreshController?.resync()`（task.ts:228）；
- `refreshNow()`（task.ts:260-273）现引 `autoRefreshController`，须改写为纯 `load()`（保 loading 守卫 + `refreshPending`）；`refreshPending`/`flushPendingRefresh`（:88/:244-250）去留未定——手动刷新仍需在途守卫，应明确保留；
- `taskEventTargets` getter（task.ts:172-189）未列（卡的「TaskEventTarget.jobId(:37)」指向被删文件内字段，与 store 侧 getter 混为一谈）；
- 模块级 `taskEventsPool`(:97)/`autoRefreshController`(:82)、常量 `TASK_AUTO_REFRESH_MS`/`TASK_IDLE_REFRESH_MS`(:46/:49)、三行 import(:34/:35/:36) 未列；
- spec 面实为 **4 个文件**受影响：task-events.spec.ts / task-events-lifecycle.spec.ts / polling-controller.spec.ts / polling-predicate.spec.ts（卡仅「相关 spec 同步删除」未点名，后两个测的是 M2 删的面）。
结论：方向采纳；建议施工卡按上列逐点点名，否则实现者按卡照删会踩 build/typecheck 断点。

**M2 polling.ts 瘦身 —— 「唯一剩余消费者」论断错误，须修正。** 实测**第二个消费者**存在：`AiPanel.vue:432/847` 以 `createPollingController({intervalMs:TICK_MS, fallbackIntervalMs:TICK_MS, run})` 作 AI 面板倒计时节拍（`tickController`，仅 start/stop）。故「唯一剩余消费者 = job-watch」为**假**。M2 若删 `fallbackIntervalMs`/双速，AiPanel 的构造调用不跟进即 build 断；AiPanel 用单节奏 + start/stop + visibility 纪律，与 M2 保留面兼容，但**必须同步改其构造调用**（或保留 fallback 兼容参数）。另 polling-controller/predicate 两个 spec 直测被删面，须同步删改（归 M1 遗漏）。修正：消费者清单 = job-watch + **AiPanel**。

**M3 job-watch 收敛 + 90s 帧空闲看门狗 —— 采纳（确实堵住半开静默，附两处注意）。** 90s 无帧判半开 → 降 3s polling 的做法**堵住了我上一轮的 R1**（onerror/readyState 检不出半开 TCP 静默，帧空闲看门狗可），方向对。附注：
- 这**反转了既有 D8 决定**（job-watch.ts:72-74 明言「不设帧新鲜度阈值、无帧是正常态」）。反转可接受（优先检出静默死亡），但应明示为**有意反转**，避免下批误当回归「改回来」。
- **90s 阈值对慢分片作业会误触发**：JOB_PROGRESS 是**分片边界驱动、非周期心跳**（Import/Export/Publish/Precheck 四 runner 的 publishProgress 均按 item 边界发，无固定节拍；后端 30s×3=90s 是**作业心跳**判死窗，与「无 SSE 帧」是**不同信号**，勿混用）。单 item 长跑 >90s 会误降 3s——但 3s 兜底自愈且正确（快照仍新鲜），属**良性**；建议阈值按实际分片节拍复核。
- 复位源语义须写实：**仅 SSE 帧/onopen** 复位回事件驱动；polling 快照不得复位（否则掩盖半开死），卡表述「SSE 恢复回事件驱动」正确，建议施工卡锁死此点。

**M4 复用 watchJob —— 采纳（四条件已识别，补两处落地坑）。** 消除 1s 裸轮询、继承 visibility/dispose，方向正确。附注：
- `watchJob` 需 taskId 开事件流，现 `watchPublishOutcome` 只收 jobId——须补 taskId 管道（`workspace.taskId` 可得），卡未点名；
- 300s 墙钟：`watchJob` 无超时上限，须加**独立定时器**且**终态时清除**（否则终态后误触超时提示）；F4 `watchingJobId` 闸与「单次提示」纪律须原样移植进 onFinal 回调；
- 「传入 AbortSignal」措辞偏松：watchJob 接口是**返回 dispose 函数**而非收 signal，实现需 signal→dispose 桥接或改签名。

**M5 摘除 TASK_CHANGED 发布点 —— 采纳（真零消费，但后端测试须同步）。** 零消费属实：M1 删 task-events.ts 后前端唯一 TASK_CHANGED 消费者消失；job-watch.ts 只消费 JOB_PROGRESS/JOB_DONE（:124-137 实测）；`/api/tasks/{id}/events` 端点保留即可。附注：
- **后端 `TaskSseServiceTest.java:161-166` 现断言 TASK_CHANGED 发布**，摘除发布点后该断言必须删/改写，卡未列（否则 `mvn test` 断）；
- `types/sse.ts:337` 的 `TaskEventType` 仍含 `'TASK_CHANGED'`，摘除后成死类型；`TaskChangedEvent` 仍被 `TaskService.publishTaskChanged`(:300) 发布但零监听者，成死事件——两者均可归 M6/R-148 收敛（非本批必做）。

**M6 过度设计审计入库 + 立项 R-148 —— 采纳（无技术风险）。** 把独立清理项延后到 R-148 串行批，避免本批范围膨胀，合理。提醒：M6 清单与本批 M1~M5 无耦合，延后不影响本批正确性；R-148 开工前复核其 B1/B4 是否为本批删除面的测试依赖即可（低风险）。

## 新增风险点与漏项

- **R1（M1 连带面不完整）**：load() 内 syncTaskEvents/resync 调用点、refreshNow 改写、taskEventTargets getter、模块变量/常量/import、4 个 spec 文件，均未在卡列举——按卡照删必踩 build/typecheck 断点。
- **R2（M2 漏 AiPanel 消费者）**：删 fallbackIntervalMs 不跟进 AiPanel.vue:847 会 build 断，是本卡唯一「论断与代码事实相悖」处。
- **R3（M3 反转 D8 + 阈值信号错位）**：90s 是「无 SSE 帧」阈值，与后端 30s×3 作业心跳是不同信号；慢分片误触发虽良性，但阈值依据须写实。
- **R4（M5 后端测试连带）**：TaskSseServiceTest TASK_CHANGED 断言须同步删改，卡未列，易漏（前端删干净、后端测试挂掉）。
- **R5（refreshPending 去留）**：手动刷新在途守卫（refreshPending/flushPendingRefresh）去留未定，归零后仍有「手动刷新撞 loading」场景，建议保留。

## 结论

M3/M4/M5/M6 采纳（各带附注）；M1 方向采纳但「连带面列全」不成立、须补清单；M2 **论断错误**（漏 AiPanel 消费者）须修正消费者清单。M1 的连带面遗漏与 M2 的 AiPanel 遗漏是本稿两处会直接导致 build/typecheck 断点的硬缺口，建议在施工卡中逐点补列后再开工。
