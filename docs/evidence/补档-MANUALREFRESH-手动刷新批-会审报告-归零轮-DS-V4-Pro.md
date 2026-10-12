<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-归零-DSPro.md`（7661 B，sha256 `de244ce4d388b6d8d3d9128736d6f38c1295be94282ccddccceb04ea5beb162f`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 轮询归零批 · 独立会审报告（DSPro 方）

- 会审对象：`r137-guard/会审卡-轮询归零.md`，sha256 = `b87438ccd361c27cd81396e03ce93cb25a7ed2d106780c73af92279e3691c22f`，与任务书给定值**一致**（sha256sum 实测）。
- 基线核实：主仓 `<MAIN_REPO>` HEAD = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`（= 45b2c6a），与卡声明一致。
- 署名：实际运行模型 **DeepSeek（deepseek-v4-pro，经 Kimi Code 主代理）** · 会话 id **DC-19** · 日期 2026-10-12。
- 评议方式：卡外独立实读（polling.ts / job-watch.ts / task-events.ts / stores/task.ts / stores/workspace.ts / api/jobs.ts / TaskSseService.java / application.yml），无其它会审方结论参考。

## 逐条评议

**Z1 PollingController 增「谓词假⇒停表」—— 采纳（附注）。** 现控制器 `desiredIntervalMs()`（polling.ts:106-108）谓词假只降频不停表，永不清 interval；Z1 增 dormant 态（清表不 destroy）是 Z2/Z4 的机制前提，方向正确。**附注（须精规格）**：dormant 态下 `refreshNow()` 必须"跑一次**不**重新武装表"（现实现 refreshNow 会 `arm(desiredIntervalMs())`，若沿用会每次事件刷新都重新起一张表，归零落空）；re-arm 仅由"谓词重新评估为真"触发；并须定义与 `resync()`/visibilitychange/dirty flag 的交互（回可见在 dormant 态应只错峰补刷一次、不重武装）。

**Z2 任务中心列表归零 —— 采纳。** 符合用户直裁（任务信息不须定时刷新），列表陈旧可接受。SSE 帧（JOB_DONE/TASK_CHANGED）→ refreshNow + 回可见补刷 + 手动刷新按钮，足以覆盖可见期一致性；`hasActiveJob` 只参与订阅池资格是对的。验收①（全静止可见 ≥60s `GET /api/tasks` 恰好 0 次）可达。**附注**：归零后"未订阅/被淘汰/跨分页隐藏的在跑任务"将永不自刷（pool 只覆盖当前页 `items`，task.ts:172-189），这是"不可见不刷"的必然结果，与指令一致，登记知悉即可。

**Z3 订阅池扩面（会审重点）—— 有条件采纳（须加硬上界 + 超限慢速保底）。** 方向正确：Z2 归零后，上限 3 + 按 taskId 降序淘汰意味着第 4+ 个在跑任务**无任何刷新通道**（事件没订、列表也不再轮询），正确性缺口成立，扩面是必须的。但"订阅**全部** RUNNING/PENDING"有两处须收口：
- **连接预算无硬界**：任务中心页每个订阅 = 1 条长连 `EventSource`，与 fetch/XHR 共享同域 HTTP/1.1 的 6 连接上限（AI 通道走 fetch POST 非 EventSource，但列表 load、任务详情等短请求仍占额度）。"在跑任务个位数"是**假设**而非约束——若并发在跑 >6，订阅全部会占满预算、饿死列表/详情请求。须按连接预算设**硬上限**（如 6，预留 fetch 额度），而非无界。
- **超限兜底应是安全阀而非"违背归零"**：卡把"超限部分保留 60s 慢速保底"列为"违背归零精神、仅兜底备选"，我持不同意见——它正是"无界扩面"缺口的必要安全阀（见 R3），且可与 Z4 的"假死发现面"复用同一条慢速保底，一举两得。
- **上限解除即丢防泄漏界**：现"上限 3 + 淘汰保新"兼有防 watcher 泄漏累积的防御性（task-events.ts 文件头自述）。扩面后靠 `taskEventSubscriptionCount` 可观测兜底，但硬界消失，泄漏只剩"观察发现"而无"被动封顶"，建议保留一个（连接预算导出的）硬上限。

**Z4 job-watch 归零（会审重点）—— 部分驳回（"0 周期"不可靠）。** 关键：**0 周期下断链检出不可靠**，因为任务级通道**无周期心跳**（TaskSseService:87-88 仅在订阅时补发 HEARTBEAT 首帧，其后纯事件驱动；application.yml `app.task.sse` 无 heartbeat 键），而 `onerror`/`readyState` 只能检出**收到 FIN/RST 的显式断链**，**检不出半开 TCP 静默死亡**（无 FIN/RST，浏览器 readyState 恒 OPEN、无 onerror、无帧）。这正是现代码注释（job-watch.ts:32-36）已点明并据此保留 30s 保底的理由（"无周期心跳，长静默是正常态 ⇒ 停表就关掉唯一的假死发现面"，R3-3）。
- 5m emitter-timeout（application.yml:203）只约束**服务端** emitter 寿命；健康连接上它造就一次"关闭→重连→onopen→补刷"的 ~5m 周期，但**半开连接上客户端根本收不到这次关闭**，静默窗口无界——作业进度条会在中途永久冻结，比任务列表陈旧严重得多（活跃进度 vs 已接受陈旧）。
- **修正方向**（二选一）：① 保留一条**慢速保底**（30s 或 60s，成本可忽略）作为假死发现面，realtime 态**不清零**——这与 Z3 超限兜底同源，可统一；② 若坚持归零，必须先补**服务端周期心跳**（后端改动，卡未含）并用"心跳新鲜度"判活，否则归零不成立。断链演练（Z6③）只能覆盖主动 kill 的显式断链，覆盖不了半开静默，故不能作为可靠性的充分证据。

**Z5 消灭 waitForJobFinal 1s 裸轮询 —— 采纳（附注）。** `api/jobs.ts:73-91` 是 1s 裸 `setTimeout` 轮询、上限 300s、不接 visibility 纪律；调用点 `workspace.ts:452` 未传 AbortSignal（一期 P3 未落地，卡属实）。改复用 `watchJob`（SSE 事件驱动 + 快照终态判定 + 继承 visibility 纪律与 dispose）正确。**附注**：① `watchJob` 需 taskId 开事件流，而现 `watchPublishOutcome` 只收 jobId，须补 taskId 管道（调用点 `event.taskId` 可得）；② `watchJob` 无 300s 超时上限，"等待超时提示"分支会消失，须决定是否用独立定时器保留该语义；③ F4 jobId 闸（watchingJobId 只认最后发起）与"单次提示"纪律须原样保留；④ 复用后新增的 EventSource 须经注册表 dispose，防与向导页既有 publish watcher 双开泄漏。

**Z6 验收口径 —— 采纳（补两处澄清）。** ①可达；②"hidden 期一切业务请求 0 次"应澄清**不含已开 SSE 长连**（EventSource 后台不关闭，仍在流）；③断链演练只能证明**显式断链**可降级，不能证明半开静默可检出（见 Z4），建议补一条"慢速保底存在性"断言而非仅"恢复后回 0"。

## 新增风险点与漏项

- **R1（Z4 半开静默死亡）**：0 周期 + 无周期心跳 ⇒ 进度条中途永久冻结且无从发现，本批唯一会"静默劣化"的项，优先级最高。
- **R2（Z3 连接预算无界）**：订阅全部在跑任务在 >6 并发时会占满 HTTP/1.1 同域 6 连接，饿死列表/详情 fetch。
- **R3（Z3 超限兜底定位）**：慢速保底应作安全阀统一用于"超限覆盖"+"假死发现面"，而非"违背归零精神"的边缘选项。
- **R4（Z5 语义丢失）**：watchJob 无超时上限，须显式决定 300s 超时提示的去留；taskId 管道未在卡中点名。
- **R5（Z1 dormant 交互）**：refreshNow/resync/visibilitychange 与 dormant 的交互未精规格，是 Z2/Z4 落地时的首要实现坑。
- **R6（Z2 未订阅任务陈旧）**：跨分页/被筛选隐藏/被淘汰的在跑任务归零后永不自刷——符合"不可见不刷"，但建议在 UI 保留手动刷新入口的可见性。

## 结论

Z1/Z2/Z5/Z6 采纳（Z1/Z5/Z6 各带附注）；Z3 有条件采纳（加硬上限 + 超限慢速保底）；Z4 **部分驳回**（"0 周期"不可靠，须保留慢速假死发现面或先补服务端周期心跳）。Z4 是本批唯一会造成静默劣化的点，建议与 Z3 的超限兜底合并为一条统一的慢速保底后再归零。
