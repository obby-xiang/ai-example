<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-终版-GLM.md`（6639 B，sha256 `0d507bb73f5011a52ebb649963111240b06f8e3041c9d059f6d0bbeda16ec633`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 手动刷新批 · 定稿终版评审报告（GLM 方）

- 评审对象：`r137-guard/决策卡-手动刷新批-定稿.md`，sha256 = `0b0f864e536efe6919145a18ea095afe81405658d1582a940b99784eb26ae388`，与任务书给定值**一致**（实测）。
- 基线：主仓 HEAD = 45b2c6a，与卡一致。
- 署名：实际运行模型 **GLM（Zhipu AI GLM-5.3，经 Kimi Code 主代理）** · 会话 id **DC-19** · 2026-10-12。
- 评审方式：对 M1~M5 逐项做卡外代码实读（polling/job-watch/task-events/task.ts/AiPanel/TaskSseService/TaskChangedEvent 消费面全量 grep），无其它会审方结论参考。上轮 Z 卡异议的处置回执（第 5 节）逐条核对无异议。

## M1 任务中心纯手动 —— 采纳，连带面补 4 项

删除面核实：task-events.ts 的 src 内消费者确仅 `stores/task.ts:35`（编排 :342-373 在卡列范围内）；spec 两件（task-events.spec.ts / task-events-lifecycle.spec.ts）卡已含"相关 spec"。**补充连带面**：①`api/tasks.ts:114` 注释引用 task-events（openTaskEvents 保留但注释须改写）；②`types/sse.ts:337/:339` 的 TASK_CHANGED 类型与载荷定义在 M1+M5 后成死定义，须删或显式保留（协议文档位）；③`stores/task.ts:7-14/:94/:169-170`、`TaskCenterView.vue:151-159` 头注释整段描述双速/订阅池/兜底口径，删除后即为**假文档**，须同步重写；④`hasActiveJob`（:159）消费面随删除仅剩注释——须裁决删码或降级为纯展示用途，不能留悬空 getter。刷新时机三口径（onMounted/手动/写操作后）自洽，无回可见补刷与用户直裁一致。

## M2 polling.ts 瘦身 —— 驳回"唯一消费者"表述，删除清单附条件

**事实错误**：`AiPanel.vue:847-854`（倒计时 tick，TICK_MS 恒等间隔）是 createPollingController 的**第二个消费者**，"唯一剩余消费者 = job-watch"不成立。AiPanel 不用 shouldRun/setIntervals，删除清单功能上不破坏它，但施工若按"唯一消费者"假设操作（尤其删 CATCHUP_JITTER_MS 改 resume 语义、动 refreshNow）会漏跑 AiPanel 回归面。**另两点**：①清单中的 `resync` 在 polling.ts 现码未见（有 setIntervals/refreshNow），条目名须校正；②M2 未交代 `setIntervals` 与 `refreshNow` 去留——job-watch 现用两者（:87/:92/:122/:135/:151），且 M3 的"realtime 0 周期"要求 realtime 态下**彻底停表**而非换间隔，polling.ts 须保留 stop/start 或 suspend/resume 形态的通道切换 API，M2/M3 交界处须在施工卡明确 API 面。修正表述并补上述两点后可采纳。

## M3 job-watch 收敛 + 90s 看门狗 —— 方向采纳，降级节奏须改

看门狗能堵静默悬挂（本方 Z4 异议的采纳形态正确：帧空闲超时 ⇒ 半开死亡 ⇒ 降轮询，SSE 恢复回事件驱动）。**两个设计问题**：①**假阳性代价劣化**：现码注释明证"长静默是正常态"（JOB_PROGRESS 只在分片边界发布，D8 决策原文"任意长度的无帧不得触发降级"），一个合法静默 10 分钟的作业在 90s 看门狗 + 0 周期下会落入 **3s 轮询 8.5 分钟（~170 请求）**，比一期的 30s 保底（20 请求）更差——与本批"砍请求"初衷相悖。建议看门狗降级用慢探活节奏（30s）或分级（90s→30s 探活，onerror/readyState 确认死才 3s）；②"回事件驱动"触发与看门狗复位条件须定义：onopen 也要复位看门狗（不能只认帧），恢复触发=下一帧或 onopen。另：job-watch 文件头与 :70-73 的 D8 注释编码的是相反决策，施工须同步改写，否则又留假文档。边界裁断（活跃操作反馈保留）合理，同意呈用户。

## M4 watchPublishOutcome 复用 watchJob —— 采纳，附两条件

watchJob API 面核实可承载：onFinal（快照终态判定 :178-181）、onDisposed（:196-205）、in-flight 防重叠（:160-194）齐全；300s 墙钟 + F4 闸 + AbortSignal + 防双开四条件齐列。**两条件**：①**注册表归属必须单一**：现注册表（createJobWatcherRegistry :233）是各页面自建的；AI dispatch（workspace.ts:452）发起的发布跟踪若注册进向导页之外的另一个表，与向导页自己的 publish watcher 形成**双开**（同 job 两条 SSE + 两张兜底表），"防双开"只有在模块级/ store 级单例注册表下才成立——施工卡须指定归属；②终态后的 issues 拉取与 F4 闸逻辑须落在 onFinal 回调内，且 hidden 期终态、回可见后收敛的路径要写用例（M2 删 catchup 后回可见不再补刷一发，首个 tick 前有整周期空窗，300s 墙钟兜底但用例须覆盖）。

## M5 TASK_CHANGED 摘除 —— 采纳

消费面全量核实属实：前端 TASK_CHANGED 分派仅 task-events.ts:89（随 M1 删），job-watch 帧处理只认 JOB_PROGRESS/JOB_DONE（:124-137，TASK_CHANGED 落 default 忽略）；后端 TaskChangedEvent 监听者**仅** TaskSseService.onTaskChanged（:92-101）一处，摘除后事件零监听、TaskService:300 的 publishEvent 保留无害。端点保留正确：job-watch 的通道源就是同端点（job-watch.ts:112 → tasksApi.openTaskEvents），"开工前置复核"会通过。补一点：后端若有断言 TASK_CHANGED 帧的测试（TaskSseService 相关 spec）须同步删除，卡未列。

## M6 审计入库 + R-148 —— 采纳

纯文档与台账动作，无代码风险；R-148 立项条目（C1/B1/C4/E1~E5/B3-B4）与审计清单呼应，无法在本轮核验登记细节，接受。

## 验收口径与漏项汇总

1. M2"唯一消费者"事实错误（AiPanel 第二消费者）——须改卡。
2. M3 看门狗假阳性降 3s 劣于一期 30s 保底——降级节奏须改/分级；onopen 须复位看门狗。
3. M2/M3 交界：setIntervals/refreshNow/停表 API 去留未定义，job-watch 通道切换无法直接施工。
4. M4 注册表归属未指定，跨页双开风险。
5. M1 连带面缺 4 项（api 注释 / sse.ts 死类型 / task.ts 与 TaskCenterView 假文档注释 / hasActiveJob 悬空）。
6. M5 后端相关测试同步删除未列。
7. 小项：验收的"杀 SSE → 90s 看门狗"演练在测试里等 90s 过慢，建议常量可注入/可缩短。

## 总结论

M1/M4/M5/M6 采纳（M1 补连带面、M4 指定注册表归属）；**M2 驳回"唯一消费者"表述**（AiPanel 为第二消费者，删除清单附条件修正后可施工）；**M3 方向采纳但降级节奏须改**（假阳性 3s 轮询劣于一期，建议分级或 30s 探活 + onopen 复位）。以上 7 项漏项落施工卡后本批可开工。
