<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\决策卡-手动刷新批-定稿.md`（5712 B，sha256 `e7c1a97cf1fae6e9f3c2d51a7a9157ae53014082019447f54aceedf57c0501f9`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 手动刷新批 · 决策卡定稿（K3 代签）

- 裁决链：**用户直裁（2026-10-12 两条）> 三方会审（归零卡 Z1~Z6：GLM 7145B sha 8add1476… / DSPro 7661B sha de244ce4…，已回收吸收）> K3 定稿**。用户第二直裁「任务中心纯手动刷新、轮询与长连接全砍 + 全面排查过度设计」覆盖原归零卡 Z2/Z3；本卡为覆盖后定稿。批次级会审已就同主题执行一轮，修订源为用户直裁与过度设计审计（agent-597 全量清单），K3 裁决不再二次会审，质量门由复核∥红队把守。
- 基线 HEAD = 45b2c6a。

## 定稿 M1~M6

- **M1 任务中心纯手动**：删 `stores/task.ts:308-327` startAutoRefresh（PollingController 消费）；删 `utils/task-events.ts` 整文件（订阅池/帧分派/close-gate）及连带面 = `stores/task.ts:342-391` 订阅编排、`TaskCenterView.vue:353-363` 挂载/卸载、`taskEventSubscriptionCount`(:135)、`TaskEventTarget.jobId`(:37)、`activeCount()/activeTaskIds()`(:60/:62)、相关 spec 同步删除。**列表刷新时机仅三种**：进入页面 onMounted 一次 + 手动刷新按钮 + 用户写操作（创建/取消等）成功后本地刷新一次。无周期表、无 SSE、无回可见补刷。
- **M2 polling.ts 瘦身**：唯一剩余消费者 = job-watch（恒等间隔）→ 删 `shouldRun`/`desiredIntervalMs` 双速/`resync`/`CATCHUP_JITTER_MS`/`isPaused`；保留单节奏 setInterval + visibilitychange hidden 停表 + stop/dispose。
- **M3 job-watch 收敛**（向导页作业进度。**边界裁断：活跃操作进行中反馈保留**，非「任务信息刷新」；呈用户，若要连此也砍一句话即入下批）：删 realtime 30s 保底；保留 SSE 主通道 + 3s 断链兜底；**补帧空闲看门狗：90s 无 SSE 帧 ⇒ 判半开静默死亡 ⇒ 降 3s polling，SSE 恢复回事件驱动**（吸收 DSPro R1 / GLM Z4「静默悬挂不可检出」最高危项）；触发源收敛 = SSE 帧 + onopen 一发 + 兜底表（审计 C2 的 5 触发源收敛）。
- **M4 消灭 waitForJobFinal 1s 裸轮询**：`watchPublishOutcome` 改复用 watchJob；保留 300s 墙钟出口 + F4 jobId 闸移植 + `workspace.ts:452` 传入 AbortSignal + watcher 注册表 dispose 防双开（Z5 双方附条件全吸收）。
- **M5 后端 TASK_CHANGED 发布点摘除**（审计 A7：前端已零消费；`TaskSseService.java:94-100`）；`/api/tasks/{id}/events` 端点保留（job-watch 仍消费 JOB_PROGRESS/JOB_DONE——施工卡开工前置复核 job-watch 通道源是否同源）。
- **M6 过度设计审计入库**：全量清单（A1~A7 / B1~B4 / C1~C6 / D2~D4 / E1~E5 + 拆除 Top 5）随批入 `docs/evidence/`；**立项 R-148 = 过度设计清理批**（AI 流双层看门狗去重 C1、AiPoolCapacityBudget 折回 B1、JobWatchdog 双路径合并 C4、配置膨胀族 E1~E5、B3/B4 测试专用面回收），串行序排队。

## 验收口径

全静止可见 ≥60s `GET /api/tasks` 恰好 0 次；任务中心零 EventSource 连接；hidden 期 0 业务请求；job-watch 断链演练（杀 SSE → 90s 看门狗降级 3s → 恢复回 0）；waitForJobFinal 原调用面功能等价（发布成/败仍正确收敛且有 300s 墙钟出口）；vitest 基数随删除面下修 + 新增断言（无周期表/看门狗降级/手动刷新路径）；typecheck / build / check-docs 6/6 全绿；施工期间禁 commit，commit 归提交官。

## 会审异议处置回执

- GLM Z3 dead-set 永久盲区 / DSPro Z3 连接预算：**消解**（M1 整链删除，订阅池不复存在）。
- DSPro R1 / GLM Z4 静默悬挂：**采纳** → M3 帧空闲看门狗。
- GLM Z1 dormant 语义 / DSPro R5：**消解**（M2 删双速后无 dormant 态）。
- Z5 双方附条件（300s 墙钟 / jobId 闸 / AbortSignal / dispose）：**全吸收** → M4。
- DSPro Z2「不可见不刷属必然，登记知悉」：**采纳为用户意图本身**，不登记为风险。

## 会审终版处置 F1~F6（K3，2026-10-12 第二轮会审后）

- **F1（双方共同事实修正）** M2「唯一消费者=job-watch」错误：第二消费者 = `AiPanel.vue:847-854` 倒计时 tick（1s/1s 双参同值）。采纳 → 施工删面以全量引用扫描为准，AiPanel 在用项保留。
- **F2 M1 连带面不全**：DSPro 实证漏 `load()` 内 `syncTaskEvents()`(:224)/`resync()`(:228)/`refreshNow()`(:260-273)/`taskEventTargets` getter(:172-189)/模块常量 import(:34-36/46/49/82/97)，受影响 spec 实为 4 个；GLM 补 `api/tasks.ts:114` 注释、`types/sse.ts:337/339` 死类型、头注释假文档、`hasActiveJob`(:159) 悬空。采纳 → 开工前置全量引用扫描定删面；`hasActiveJob` 复核零引用后删；`refreshNow`/`refreshPending` 保留（手动刷新通道，DSPro R5 建议成立）。
- **F3 M3 看门狗细则**：仅 SSE 帧/onopen 复位、polling 快照不复位（DSPro）采纳；明示反转 D8「无帧是正常态」注释并同步改写（双方）采纳；阈值常量可注入加速验收（GLM）采纳；GLM 分级方案（90s→30s 探活→确死 3s）**驳回**——3s 兜底自愈属良性（DSPro 同判），再叠分级机制本身即过度设计，与本批主题相悖。
- **F4 M4 四条件**：taskId 管道、独立 300s 墙钟（终态即清除）、watchingJobId 闸+单次提示移植入 onFinal、watchJob 返回 dispose 的桥接（非 signal）+ 注册表归属单一防双开——全采纳。
- **F5 M5 后端 spec 连带**：`TaskSseServiceTest.java:161-166` 断言 TASK_CHANGED 发布，摘除后必断 mvn test——采纳，同步删改列入施工。
- **F6 验收节奏**：「杀 SSE 等 90s」过慢——采纳，看门狗阈值可注入，E2E 注入小值。
