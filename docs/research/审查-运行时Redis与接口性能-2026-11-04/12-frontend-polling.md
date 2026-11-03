# 12. 前端轮询、SSE 重连与页面生命周期审查

- 审查日期：2026-11-04
- 仓库基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`（审查时 `HEAD` 与该基线一致，工作区无既有改动显示）
- 审查边界：`frontend/src` 全部 TypeScript/Vue 源文件；只读生产代码，不读取测试来反推行为，不执行测试、构建、CI、服务或压测。
- 本报告只负责前端。前端没有直连 Redis 或数据库的代码；文中“服务端/DB/Redis 影响”均指经 HTTP/SSE 到达后端后的压力上界或待后端报告接续核实的边界，不把后端实现写成已知事实。

## 1. 结论摘要

1. **未发现组件卸载后仍无条件无限运行的前端 API 轮询。** 作业快照轮询有 `stop/dispose`、终态自收、在途合并和隐藏页停表；AI 对话重连有次数、退避、中止和轮次代次上限。
2. **存在明确的 API 放大面。** 任务通道没有周期心跳，却把 120 秒无帧判为假死，健康但安静的作业也会进入 3 秒轮询；导入页最多三类 watcher 共用同一任务却各开一条相同 URL 的 `EventSource`，多页签再无共享或选主。
3. **“隐藏页零请求”只在作业快照 REST 轮询上成立，不能推广到所有通道。** 作业 `EventSource` 仍保留并由浏览器自动重连；AI 的建连/静默/退避计时器、准备中恢复和 409 权威校正均无页面可见性门控。
4. **发布跟踪的可靠性与通用 watcher 的自愈目标相冲突。** 通用 `watchJob` 会吞住单次快照错误并继续，但 `watchPublishOutcome` 的 `onError` 立即 `dispose`，一次网络错误或无 `EventSource` 环境就会停止整个发布跟踪。
5. **没有发现前端轮询导致“删除对话接口超过 30 秒”的代码证据。** 新建对话会同步作废旧 SSE 与恢复计时器；删除是单次 `DELETE`，Axios 单请求上限为 60 秒。完整 `resetSession()`  Promise 还串行等待发号和可选取消，但该 Promise 被 UI 以 `void` 启动，清屏不等待它；DELETE 自身为何慢必须由后端删除链报告回答。
6. 本报告列出 **10 项发现**：5 项中严重度、5 项低严重度。没有仅凭猜测登记高严重度问题。

## 2. 审查范围、方法与实际阅读文件

### 2.1 无遗漏搜索口径

对 `frontend/src` 的完整源文件清单做了全局搜索，并逐条排除注释、类型字段和一次性非循环调用的误报。搜索面包括：

- 定时/帧机制：`setInterval`、`clearInterval`、`setTimeout`、`clearTimeout`、`requestAnimationFrame`、`cancelAnimationFrame`、`requestIdleCallback`；
- 异步循环：`for (;;)`、递归/重排定时器、`delay/sleep` 驱动的循环；
- 业务名词：`poll`、`job-watch`、`refresh`、`reload`、`health`、`heartbeat`、`reconnect`；
- 流与生命周期：`EventSource`、SSE、`visibilitychange`、`document.hidden`、`visibilityState`、`pagehide/pageshow`、`onMounted/onBeforeUnmount`；
- 竞争与清理：`AbortController/abort`、请求序号、任务代次、事件订阅和解绑。

### 2.2 实际深入阅读的生产文件

- API 与超时：`frontend/src/api/ai.ts`、`api/http.ts`、`api/jobs.ts`、`api/sse.ts`、`api/tasks.ts`、`utils/time-constants.ts`。
- 作业与页面状态：`frontend/src/utils/job-watch.ts`、`utils/polling.ts`、`stores/task.ts`、`stores/workspace.ts`。
- 对话与页签生命周期：`frontend/src/stores/ai.ts`、`components/AiPanel/AiPanel.vue`、`components/AiPanel/CompactionStatus.vue`、`components/AiPanel/ReasoningHeader.vue`。
- 向导与刷新入口：`frontend/src/views/ImportWizardView.vue`、`views/ExportWizardView.vue`、`views/TaskCenterView.vue`、`views/AiSettingsView.vue`、`views/DataBrowserView.vue`、`views/DefinitionsView.vue`。
- 局部轮询与一次性计时：`frontend/src/utils/frontend-tools.ts`、`utils/ai-dom-tools.ts`、`utils/markdown.ts`、`utils/form-draft.ts`、`utils/excel.ts`、`components/SpreadGrid.vue`、`components/AiPanel/FormRenderer.vue`。

`JobProgress.vue`、类型与 locale 等其余源文件也在上述全文搜索范围内；没有因为只有注释命中就当作一条实际轮询。

## 3. 全量机制盘点表

下表覆盖搜索到的全部周期循环、递归/延时重试、SSE 重连、可见性联动和一次性计时器。相同函数内的多个看门狗合并为一行，但所有实际行号均列出。

| # | 位置 | 机制与节奏/上界 | 是否发网络请求 | 卸载、隐藏与竞态审查 |
|---|---|---|---|---|
| 1 | `frontend/src/utils/polling.ts:57-283` | 通用 `setInterval`；另有一次 0–250ms 回可见补刷定时器 | 由回调决定；当前仅作业快照 | `start/stop` 幂等；切节奏先清旧表；hidden 停表；在途合并 + dirty 补发；stop 清表和监听。**未发现重复 interval。** |
| 2 | `frontend/src/utils/job-watch.ts:127-139` | 120 秒无帧看门狗，SSE 帧/onopen 重置 | 触发后间接启动 3 秒 GET 轮询 | dispose 清理；但不随 hidden 暂停，健康安静流也会判死，见 FE-POLL-01。 |
| 3 | `frontend/src/api/tasks.ts:123-142`；`utils/job-watch.ts:158-208,267-286` | 原生 `EventSource` 自动重连，无前端次数/抖动控制 | 长连接及浏览器自动重连 | watcher dispose 会 close；hidden 不 close；同任务/跨页签不共享，见 FE-POLL-02、03。 |
| 4 | `frontend/src/stores/task.ts:470-523` | 发布 watcher 独立 360 秒墙钟 | 本身不请求，到点停止 watcher | 终态/dispose 清墙钟；与 3 秒轮询相互独立。 |
| 5 | `frontend/src/stores/task.ts:618-651` | 任务类型读取首发 + 200ms + 400ms，共 3 次 | `GET /tasks/{id}` | 仅瞬时错误重试；每拍重查缓存；不是常驻轮询。无 hidden 门控，但有界。 |
| 6 | `frontend/src/stores/ai.ts:7482-7488,7502-7557` | 409 后权威校正，3s/6s/12s，共 3 次 | `GET /ai/runs/current` | 同 toolCallId 重排前清旧表；会话/终态/停止清理；旧会话响应不写回；hidden 仍执行。 |
| 7 | `frontend/src/stores/ai.ts:3994-4069` | 通用自动恢复，每类最多 2 次、间隔 400ms | 恢复历史、快照或接回既有轮 | 同类单飞；epoch 作废旧链；新轮/停止/换会话取消；hidden 不暂停但有界。 |
| 8 | `frontend/src/stores/ai.ts:6648-6695,6809-6867` | 开流前瞬时失败最多重试 1 次，等待 400ms；首帧超时另走有界恢复 | 重试复用同一 `clientRequestId`；恢复只读/接回 | 检查当前轮、停止和 abort；不是无限重发。等待本身不随 hidden 停止。 |
| 9 | `frontend/src/stores/ai.ts:9903-10020` | 重复请求接管后的 readiness 探测；复用 preparing 最多 2 次预算；0ms 换链 | `GET /ai/events/{runId}?probe=1` | run/host/session/epoch 多重归属闸；有界，不形成永久循环。 |
| 10 | `frontend/src/api/sse.ts:533-553,618-627` | POST 流首帧 40 秒、帧间 45 秒看门狗 | 超时 abort 当前流，后续可转 reattach | 首帧后清首帧表；读循环 finally 清两表；不随 hidden 暂停。 |
| 11 | `frontend/src/api/sse.ts:651-710` | 每次准备中探测独立 15 秒 abort | 单次轻量 GET | finally 清表；无外部 signal，调用方作废后响应虽被忽略，请求仍最多持续到 15 秒。 |
| 12 | `frontend/src/api/sse.ts:875-918` | POST `/ai/chat` 建连 15 秒 | 超时 abort | finally 清表；主动取消与超时分流。 |
| 13 | `frontend/src/api/sse.ts:1017-1278` | reattach 建连 15 秒、帧间 45 秒、1/2/4/8 秒 ±25% 抖动，最多重连 4 次 | GET 流；未建连失败时并行一次 probe | settle 清三表、关流、解 abort；世代与尝试序号防旧 probe 收尾；挂起/终态不盲目重连。hidden 不暂停。 |
| 14 | `frontend/src/stores/ai.ts:776-788` | 等待停止落地最多 2 秒 | 等待既有 cancel，不新增轮询 | gate 落地即清表并 resolve。 |
| 15 | `frontend/src/stores/ai.ts:1198-1200,1548-1564` | Web Locks 被占时 150ms 间隔，共 3 次 | 仅浏览器本地锁 | 有界；与后端无关。 |
| 16 | `frontend/src/stores/ai.ts:1576-1607,11101-11113` | pagehide 后 1500ms 锁释放；会话让渡 1500ms 宽限 | 仅本地锁/BroadcastChannel；接手后按既有链读历史 | claim 撤销待接手；pageshow 有界重认领；模块级只绑一次。 |
| 17 | `frontend/src/stores/ai.ts:4091-4126` | 会话镜像 300ms leading/trailing，空闲回调；hidden/pagehide 立即 flush | 仅 `sessionStorage` | 防重复订阅；flush 前取消 timer/idle；模块级监听是应用寿命设计，不随每张卡累积。 |
| 18 | `frontend/src/utils/frontend-tools.ts:522-550` | 前端工具预算 `Promise.race`；计时器清于 finally | 原任务可能已发 HTTP；超时本身不发请求 | 到点置 `lifetime.abandoned`，但不 abort 已发请求；代码明确说明此取舍。 |
| 19 | `frontend/src/utils/frontend-tools.ts:835-995` | 本地就绪 `for (;;)`，50ms 一拍，默认 8 秒；到点 0ms 后最后读一次 | 只读 store/handler，不调用后端 | 有硬上界；无 AbortSignal，但最多持续 8 秒；hidden 也只是本地等待。 |
| 20 | `frontend/src/utils/ai-dom-tools.ts:1216-1305` | DOM 稳定 quiet 30ms/上限 500ms；`waitUntil` MutationObserver + 25ms 兜底、500ms 上限 | 纯 DOM | finish 幂等，同时 disconnect、清 interval/deadline；未发现残留 observer。 |
| 21 | `frontend/src/utils/ai-dom-tools.ts:267-270,1717-1735` | 通用一次性 delay；高亮 2500ms 自清 | 无 | 高亮清理同时清表、移除徽标；无周期循环。 |
| 22 | `frontend/src/components/AiPanel/AiPanel.vue:1530-1557` | 压缩中每秒本地计时 | 无 | 状态重评先停旧表；结束/卸载清理；hidden 未主动停，见 FE-POLL-10。 |
| 23 | `frontend/src/components/AiPanel/CompactionStatus.vue:29-48` | preparing 每秒本地计时 | 无 | watch 前先停；卸载清理；hidden 未主动停。 |
| 24 | `frontend/src/components/AiPanel/ReasoningHeader.vue:32-57` | 仅未封口思考段每秒计时 | 无 | 非 live 不起表；状态变化先停旧表；卸载清理；hidden 未主动停。 |
| 25 | `frontend/src/components/AiPanel/AiPanel.vue:1848-1880,2138-2144,2491-2540,2951-2968,3367-3376,3403-3412,4820-4843,5230-5255` | 回底 600ms；消息/按钮/查看器复制 2s；淡出 300ms；Blob 0ms 回收 | 无 | 同类重入先清或按元素记账；卸载统一清理；均非轮询。 |
| 26 | `frontend/src/components/AiPanel/AiPanel.vue:3233-3237` | 唯一 `requestAnimationFrame`，一次恢复 Mermaid 滚动位置 | 无 | **不是递归帧循环**；无需持续 cancel；卸载后最多对脱离 DOM 做一次写。 |
| 27 | `frontend/src/components/SpreadGrid.vue:197-245,313-359` | 引擎就绪 60 秒；ResizeObserver 后 50ms 防抖 refresh | 动态 chunk 加载；resize 不请求后端 | 引擎代次防旧回包；卸载清表、断 observer、销毁 workbook。 |
| 28 | `frontend/src/utils/form-draft.ts:51-187` | 表单草稿 250ms 合并；pagehide/hidden flush | 仅 `sessionStorage` | 每键只留一个 timer；直写/清理先作废旧 timer；监听只装一次。 |
| 29 | `frontend/src/utils/markdown.ts:531-568` | 流式 Markdown 75ms 合并刷新 | 无 | timer 到点自摘；面板卸载注销 flush handler；非周期。 |
| 30 | `frontend/src/utils/markdown.ts:1181-1244` | Mermaid 等一次 idle（1 秒兜底）；渲染 3 秒 race | 动态 import；无业务轮询 | 非循环；3 秒只放弃等待、不打断同步渲染，代码已明确；timer 在 finally 清理。 |
| 31 | `frontend/src/views/ImportWizardView.vue:637-657`；`views/ExportWizardView.vue:640-655` | 等待定义在途请求，各自有界 race | 等待同一笔请求，不新发 | finally 清表；导入还按剩余额度计算，避免多个调用各等满一轮。 |
| 32 | `frontend/src/views/ExportWizardView.vue:2417-2447` | 下载前置 30 秒 deadline | 前置链可发 HTTP；超时自身不请求 | finally 清表；只决定等待结局，不伪称已中止前置 HTTP。 |
| 33 | `frontend/src/stores/workspace.ts:923-941` | UI 下载 60 秒 abort | 单次 fetch | finally 清表；超时明确 abort。 |
| 34 | `frontend/src/utils/excel.ts:848-858` | 本地下载 3 秒后 revoke Object URL | 无 | 一次性回收；非轮询。 |

### 3.1 刷新入口的定性盘点

| 页面/域 | 自动周期刷新 | 实际触发源与旧响应治理 |
|---|---|---|
| 任务中心 | **无**；也不开任务级 EventSource | 挂载一次、手动按钮、写后刷新、受限 AI 作业事件；`stores/task.ts:271-325` 用 `loadSeq` last-wins，在途刷新合并一次。 |
| 导入向导 | 仅活动作业 watcher 的 SSE 降级轮询 | 挂载/换任务恢复；三类作业可并存；同 kind 重跑先 dispose；`taskGeneration` 防跨任务写回。 |
| 导出向导 | 仅活动作业 watcher 的 SSE 降级轮询 | 挂载/换任务恢复；新 watcher 先停旧；结果、任务详情均有代次闸。 |
| AI 健康 | **无周期健康轮询** | 面板挂载一次；设置页 `load()` 一次；特定聊天错误后补探；见 FE-POLL-09 的冷启动重复面。 |
| 对话 | 无常驻 REST 对话轮询 | POST 流 + 断流 reattach + 少量准备中/409 有界校正；均无 hidden 总闸。 |
| 表单选项 | 无周期刷新 | 挂载和父字段变化；`FormRenderer.vue:723-750` 用每字段序号 + 父值快照防旧响应覆盖。 |
| 配置定义/数据浏览 | 无周期刷新 | 挂载、手动、筛选/分页/写后；两者都有请求序号防旧响应覆盖。 |
| 工作区 `refresh_defs/refresh_data` | 无实际刷新 | `stores/workspace.ts:943-955` 只记录意图并明确提示没有页面订阅；`refresh_tasks` 才实际调用列表加载。 |

## 4. 完整前端调用链（到服务边界）

以下链路中的“服务→Redis/DB/线程→返回”不在本前端报告阅读范围；准确边界是 axios/fetch/EventSource 发出请求及收到响应。未据此猜测后端内部实现。

### 4.1 导入/导出/发布作业跟踪

```text
页面 onMounted / 换任务 / 用户或 AI 启动作业
  → ImportWizardView.resumeJobs/attachJob 或 ExportWizardView.resumeJobs
  → beginWatchJob（同 kind 先回收；导入三类可并存）
  → watchJob
      ├─ createPollingController.start（先武装 3s 兜底表）
      ├─ tasksApi.openTaskEvents → EventSource GET /api/tasks/{taskId}/events
      └─ syncSnapshot → jobsApi.getJob → axios GET /api/jobs/{jobId}
  → 服务边界：任务事件流 / 作业快照服务 → DB/线程（由后端报告接续）
  → 快照返回 → disposed 检查 → onSnapshot
  → isJobFinal：dispose（清轮询、看门狗、EventSource）→ onFinal
  → 导入：getIssues → 必要时 loadStaging → getTask → 更新工作区
  → 导出：getIssues → 成功时 getFiles/加载表格 → getTask → 更新工作区
```

SSE `onopen`、首帧、恢复帧和 `JOB_DONE` 会经 `poller.refreshNow()` 回到同一快照链。终态只认 GET 快照，不认 `JOB_DONE` 载荷，避免事件领先数据库提交。

AI 发起发布的附加链为：

```text
SSE 工具帧 → workspace.dispatchUiEvent/runUiEvent(run_job)
  → jobsApi.createJob → POST /api/tasks/{taskId}/jobs
  → taskStore.watchPublishOutcome → 同一个 watchJob
  → 终态 FAILED → GET /jobs/{jobId}/issues
  → reportUiEventResult → 工作区时间线 + 面板回执
```

### 4.2 任务中心刷新与可见性

```text
TaskCenterView.onMounted / 手动刷新 / 筛选分页 / 取消或删除后
  → taskStore.load 或 refreshNow
  → tasksApi.listTasks → axios GET /api/tasks?...
  → 服务边界：任务列表服务 → DB（后端报告接续）
  → loadSeq 仅允许最新请求写 items/total/lastLoadedAt
  → 视图表格返回
```

AI 作业事件另一条入口为 `onWorkspaceEvent → notifyJobEvent`：仅白名单事件、正在观察任务中心、存在非终态作业或本事件刚起作业时刷新；hidden 只记一个待办，visible 时 `flushAutoRefreshOnVisible` 补一次。事件总线是文档内 `Map`，不是跨页签广播。

### 4.3 对话发送、断流与恢复

```text
AiPanel.onMounted
  → ensureTabOwnership / loadTenantDirectory / startMirror
  → probeHealth（单次 GET /api/ai/health）与 loadHistory

用户发送 → ai.send
  → aiApi.chat → streamChat → fetch POST /api/ai/chat
  → 15s 建连看门狗 → 40s 首帧看门狗 → 45s 帧间看门狗
  → 服务边界：对话服务 / Redis 运行态与归档 / 模型流（后端报告接续）
  → readStream → consumeFrame → store.handleFrame → 消息与工具卡

非终态断流且有 runId
  → reattach GET /api/ai/events/{runId}?lastSeq=...
  → 建连/帧间看门狗 + 1/2/4/8s 抖动退避，最多 4 次
  → 未建连失败可并行 GET ...?probe=1
  → store 的 runId single-flight、AbortController、roundEpoch、lastSeq 防双连接和串轮
  → onClose/finally 复位当前轮状态并返回
```

准备中、历史失败、410 和首帧超时恢复都汇入每类最多两次的 `autoRecoverBounded`；409 决策校正走独立的最多三次 `runs/current` 链。二者均不是无限轮询。

### 4.4 新建对话/删除旧会话

```text
AiPanel.newChat / 确认框 close(confirm)
  → void ai.resetSession()
  → adoptFreshSession：同步换会话、abort 旧流、取消自动恢复/门校正/待接手计时器
  → await ensureSession：POST /api/ai/session（若需发号）
  → 有旧 runId 时 await POST /api/ai/cancel/{runId}
  → await DELETE /api/ai/session/{oldSessionId}
  → 按 clientCleanup 或失败兜底清本地镜像/草稿/停止墓碑
```

每段 REST 都走 60 秒 Axios 上限。链上没有删除状态轮询；旧会话帧受轮次/会话闸阻断。需要后端继续核查的是 DELETE 服务内部的 Redis Cluster 删除、DB 和线程等待，而不是把前端重连当作删除慢因。

### 4.5 表单与工作区刷新

```text
FormRenderer.setValue（父字段变化）
  → refreshSourceOptions → formOptionSource
  → GET /api/ai/form/option-sources/{sourceId}?value=...
  → 字段请求序号 + 父值相等检查 → 最新选项写回
```

草稿仅写 `sessionStorage`，不会周期回灌服务器。`refresh_defs`/`refresh_data` 当前只有意图记录；任务中心才消费实际任务列表刷新。

## 5. 逐项发现

### FE-POLL-01：健康但 120 秒无业务帧的作业会被误判为死通道并进入 3 秒轮询

- **严重度：中。确定性：代码证实。**
- **证据：** 任务通道明确“无周期心跳”（`frontend/src/api/tasks.ts:112-121`）；`watchJob` 对连续 120 秒无帧直接 `emitChannel('polling')`（`frontend/src/utils/job-watch.ts:123-139`），轮询间隔固定 3000ms（同文件 `:49-56,149-153`）。轮询快照不会重置看门狗（同文件 `:19-20`）。
- **触发条件：** SSE TCP 连接和 `onopen` 均正常，但作业超过 120 秒没有 `JOB_PROGRESS/JOB_DONE` 等 JSON 帧。长任务、等待外部资源或进度分片稀疏都可能满足；是否常见取决于后端作业节奏。
- **影响：** 健康连接被降级，活动 watcher 每 3 秒发一次 `GET /api/jobs/{id}`，即每个 watcher 每分钟 20 次，直到新帧到来或终态。单例/Cluster 不改变此前端判定；多实例故障不是必要条件。多页签与多 watcher 线性放大，DB 快照查询压力由后端承接。
- **根因：** 把“无应用帧”同时当作“传输假死”证据，而协议没有心跳提供第三种“健康但安静”状态。
- **最佳修复：** 服务端在任务 SSE 上增加周期、可解析的心跳帧（或独立命名事件并显式绑定），前端收到即重置看门狗；心跳间隔应明显小于 120 秒。保留无帧降级用于真正半开连接。
- **备选与取舍：** 仅把 120 秒调大或改成自适应轮询，改动小但不能区分健康安静与假死；仅靠 TCP/EventSource 状态也无法识别所有代理半开。心跳需要改后端协议，但语义最完整。
- **修改点/影响面：** 后端任务 SSE 发布点、前端 `types/sse.ts` 帧白名单/解析（若新增帧型）、`job-watch.ts` 收帧复位；保持快照为终态权威。
- **后续验证（只写不执行）：** 构造健康连接连续超过 120 秒只发心跳、无业务帧，断言始终 realtime 且零周期 GET；再分别模拟完全静默和断网，断言仍能降级并在恢复帧后停表。

### FE-POLL-02：同任务多 watcher 与多页签没有连接/轮询共享，故障期请求和连接线性放大

- **严重度：中。确定性：代码证实结构；实际压力倍率取决于活动 watcher 与页签数。**
- **证据：** 每次 `watchJob` 都新建独立 controller 和 `EventSource`（`frontend/src/utils/job-watch.ts:85-86,149-159`）。导入页按 `check/import/publish` 各存一个 dispose，三类可并存且互不回收到其他 kind（`frontend/src/views/ImportWizardView.vue:1936-1952,2018`），它们对同一 `taskId` 打开相同 `/tasks/{taskId}/events`。`publishWatchers` 仅在当前文档内按 `taskId:jobId` 去重（`frontend/src/stores/task.ts:149-154`），没有跨页签协调。
- **触发条件：** 同一导入任务有两类或三类非终态作业；或同一任务在 N 个可见页签打开且 SSE 降级。
- **影响：** 一个页签三类 watcher 即三条同 URL SSE；降级且都未终态时，每页签每分钟最多 60 次作业 GET（3×20），N 页签为 60N 次/分钟，另加每 watcher 的 SSE 重连。固定 3s 表只在回可见时加抖动，常规 tick 无随机相位，服务同时故障后容易保持同相。单例与 Cluster 都会承受相同 HTTP 数；多实例只是由负载均衡分配这些请求。
- **根因：** 复用粒度是 job watcher，而事件流实际粒度是 task；浏览器文档间也没有 leader/shared worker。
- **最佳修复：** 先实现“每文档、每 taskId 一条事件流 + 多 job 订阅分发”，再把轮询也按 task/job 注册表统一调度；确有大量同源页签时，用 Web Locks + BroadcastChannel 选主，由 leader 轮询并广播快照，pagehide/锁释放时接管。
- **备选与取舍：** 只做文档内 task 通道复用，改动较小、能去掉导入页重复 SSE，但不能消除跨页签放大；只增加轮询抖动/指数退避实现更简单，却不能减少正常 watcher 数。跨页签选主要处理快照归属、身份和 leader 崩溃，不能无条件上线。
- **修改点/影响面：** `job-watch.ts` 可拆成 task channel 与 job subscription；导入/导出/发布 dispose 语义和终态自收需保持；跨页签方案还需身份隔离与数据新鲜度协议。
- **后续验证：** 同任务三类活动作业只应出现一条 task SSE；三个页签在选主方案下只应有 leader 发周期 GET；leader 关闭后恰好一个接管，所有 follower 不错误写回旧 job。

### FE-POLL-03：SSE 与 AI 恢复链缺少统一 hidden 门控，后台页签仍会重连/探测

- **严重度：中。确定性：代码证实。**
- **证据：** 作业轮询控制器明确只在 hidden 停 REST 表（`frontend/src/utils/polling.ts:166-205`），`watchJob` 的 `source` 只在 dispose 时 close（`frontend/src/utils/job-watch.ts:267-272`），原生 `EventSource` 自动重连由 `api/tasks.ts:107-109,128` 确认。AI reattach 的 retry/connect/idle timer 全在 `api/sse.ts:1017-1278`，全文件无可见性检查；准备中/历史等恢复在 `stores/ai.ts:3994-4069`，409 校正在 `:7482-7557`，也无 hidden 判断。
- **触发条件：** 页签 hidden 时连接断开、45 秒静默、建连挂起，或已有自动恢复/门校正排期。浏览器冻结/节流会改变实际触发时刻，因此不能承诺固定墙钟请求数。
- **影响：** 后台任务 EventSource 仍可自动重连；AI 单个 reattach 生命周期仍可产生最多四次重连及相应探测，其他有界恢复也继续。多页签、多服务实例切换/同时重启时放大。该发现不表示存在无限 AI 重连：次数和退避仍有界。
- **单例/Cluster/多线程影响：** 前端行为不随 Redis 拓扑变化；后台请求会增加服务连接与运行态读取，具体 Redis 命令和线程占用须后端核实。
- **根因：** 可见性治理只封装在 `polling.ts`，没有上提到 task EventSource 和 AI transport/recovery 生命周期。
- **最佳修复：** 建立统一页面生命周期策略：hidden 时暂停新的重连/恢复排期并清理 retry timer，记录“传输暂停”而非用户取消；visible 后按最新 `lastSeq` 和权威快照恢复。作业 EventSource 可在 hidden close、visible 重建并立即取一次快照。
- **备选与取舍：** 保留正常 SSE、只暂停“已失败后的重连”改动更小，也让健康流继续实时接收，但后台仍保留连接；直接 abort 所有 hidden AI 流实现简单，却必须避免被当成停止/错误，并验证归档增量完整。
- **修改点/影响面：** `api/sse.ts`、`stores/ai.ts`、`utils/job-watch.ts`；需区分 cancelled、suspended、hidden-paused 三种收尾，保持 currentAbort/roundEpoch 归属。
- **后续验证：** hidden 期间模拟断流、202、409 和 45 秒静默，断言无新网络连接且 UI 不误报取消；visible 后仅从最后序号恢复一次，无重复帧、无双连接、无丢失终态。

### FE-POLL-04：发布跟踪遇任意一次 `onError` 即停止，抵消 SSE/轮询自愈能力

- **严重度：中。确定性：代码证实。**
- **证据：** 通用 `syncSnapshot` 捕获 GET 错误后只回调，不 dispose（`frontend/src/utils/job-watch.ts:240-263`）；EventSource 构造失败也特意保留 3 秒轮询（同文件 `:209-223`）。但发布消费者在 `onError` 中立刻 `dispose()`（`frontend/src/stores/task.ts:500-515`），同时清 360 秒墙钟、关闭 SSE 和轮询（同文件 `:485-498`）。
- **触发条件：** 任意一次快照网络失败/60 秒超时/5xx，或环境无 `EventSource`、构造同步抛错。单次瞬时错误足够，不要求持续故障。
- **影响：** 发布仍在后端运行时，前端停止进度与终态跟踪，后续冲突明细也不会经该 watcher 回灌。多实例切换、LB 短暂 502 或 Cluster 抖动若表现为一次请求失败，就会命中；代码本身不证明这些错误一定发生。
- **根因：** 通用 watcher 把错误当可恢复事件，发布调用方却把同一回调当终局；通道构造错误与快照错误也没有类型区分。
- **最佳修复：** 发布 `onError` 不 dispose，继续复用 watcher 的轮询/重连，直到终态或 360 秒墙钟；用户提示按 watcher 去重/节流。若要区分错误来源，给 `onError` 增加 `source: 'channel'|'snapshot'` 和是否可重试信息。
- **备选与取舍：** 保留 fail-fast 并提供明确“重新跟踪”按钮，实现简单但把恢复责任交给用户；只对 5xx/网络错误续跑、对确定性 404 停止可减少无意义请求，但需要定义 404 在作业刚创建/多实例可见性窗口中的语义。
- **修改点/影响面：** 主要是 `stores/task.ts` 的 `watchPublishOutcome`；若扩错误类型则涉及 `job-watch.ts` 的构造与快照回调。
- **后续验证：** 第一次 GET 500、第二次返回运行中、随后终态，断言 watcher 不中断且只回灌一次结局；模拟无 EventSource，断言 3 秒快照仍推进；360 秒非终态仍只提示一次并收摊。

### FE-POLL-05：导出终态回调未捕获 issues 请求失败，会阻断结果和任务状态刷新

- **严重度：中。确定性：代码证实。**
- **证据：** `ExportWizardView` 的 `onFinal` 在 `await jobsApi.getIssues(jobId)` 处没有 try/catch（`frontend/src/views/ExportWizardView.vue:1513-1525`）；只有该 await 成功后才会执行成功时的 `refreshResults()` 和后续 `getTask`（同文件 `:1523-1548`）。`watchJob` 调用 `options.onFinal?.()` 不 await 也不捕获其 Promise（`frontend/src/utils/job-watch.ts:249-253`）。导入页同功能则在 `refreshIssues` 内捕获失败并继续（`frontend/src/views/ImportWizardView.vue:1982-2016,2075-2096`）。
- **触发条件：** 作业已到终态快照，但紧随的 `GET /jobs/{id}/issues` 网络失败或返回错误。
- **影响：** 终态作业已成功导出时，结果文件可能不重载，任务状态/只读态也不更新；回调 Promise 还可能形成未处理拒绝。不是轮询风暴，但会让用户误以为作业没有正确收口并手动重复刷新。
- **单例/Cluster/多实例影响：** 与 Redis 拓扑无直接关系；任何一次 HTTP 失败都可触发，多实例切换只是可能增加瞬时失败窗口。
- **根因：** 终态主链把非关键明细读取设成硬前置，与导入页的错误隔离口径不一致。
- **最佳修复：** 在导出 `onFinal` 内捕获 issues 失败、保留旧/空清单并给出非阻断提示；结果刷新与任务快照必须在后续 `finally` 或独立保护块中继续，且每一步保留 generation 检查。
- **备选与取舍：** 在 `watchJob` 统一捕获所有 async `onFinal` rejection 能消除未处理拒绝，但若只在外层吞错，导出后续刷新仍被跳过，不能替代本处修复。
- **修改点/影响面：** `ExportWizardView.vue` 的终态回调；可选给 `job-watch.ts` 加异步回调 rejection 兜底。
- **后续验证：** COMPLETED 快照后注入 issues 500，断言仍调用文件列表和任务详情、无未处理拒绝、旧任务响应不跨代写回；FAILED/CANCELLED 同样完成任务状态刷新。

### FE-POLL-06：每次作业通道启动/重连可能做初始、onopen、首帧三份权威快照

- **严重度：低。确定性：代码证实触发点；实际是否形成三次完整 HTTP 取决于在途合并和响应时序。**
- **证据：** watcher 末尾无条件 `void syncSnapshot()`（`frontend/src/utils/job-watch.ts:285`）；`source.onopen` 又 `poller.refreshNow()`（`:204-207`）；首帧在 `!firstFrameSeen` 时再次 `refreshNow()`（`:165-175`）。controller 只在请求重叠时把后续刷新合并为 dirty 补发（`frontend/src/utils/polling.ts:115-145`），若前次已完成则仍会发新 GET。
- **触发条件：** 每次 watcher 创建或 EventSource 重连成功并收到首帧。快速连续的请求可重叠合并，快速完成则可能顺序发出多笔。
- **影响：** 正常 realtime 也有额外快照，抵销部分“零周期请求”的启动收益；多 watcher/多页签时叠加。对单例/Cluster 语义无差异。
- **根因：** onopen 与首帧都独立承担“权威对账”，没有记录本次 open 已完成快照。
- **最佳修复：** 保留初始快照与每次 onopen 快照，删除“首帧必再刷”；`JOB_DONE` 和从 polling 恢复的即时刷新保留。若担心异常事件顺序，可记录 `snapshotRequestedForOpen`，而不是无条件首帧补发。
- **备选与取舍：** 保留首帧补发能提高极窄时序下的对账概率，但常态重复请求不值得；仅依赖 dirty 合并不需要改状态，却无法消除顺序完成的重复请求。
- **修改点/影响面：** `job-watch.ts` 的 `firstFrameSeen` 和收帧分支；通道恢复语义不变。
- **后续验证：** 统计启动、重连、首帧、首帧+JOB_DONE 四种时序的 GET 数；断言启动/onopen 各仅需设计规定的一发，在途时不丢终态补刷。

### FE-POLL-07：dispose 不 abort 在途作业快照，快速换任务后旧请求仍占连接到超时/响应

- **严重度：低。确定性：代码证实“不取消”；不是永久泄漏证据。**
- **证据：** `polling.ts` 明确 hidden 不取消在途 promise（`frontend/src/utils/polling.ts:11-13`），`stop()` 也只清表/监听（`:239-248`）；`watchJob.teardown()` 不持有快照 AbortController（`frontend/src/utils/job-watch.ts:267-272`）。响应回来后由 `disposed` 阻止写回（`:242-247`）。`jobsApi.getJob` 没传 signal（`frontend/src/api/jobs.ts:18-21`），通用 Axios 上限为 60 秒（`api/http.ts:142-145`）。
- **触发条件：** GET 在途时卸载、换任务、同 kind 重跑或另一发终态导致 dispose。
- **影响：** 旧请求最多仍持续到响应或 60 秒，占用浏览器/代理/服务连接；每 watcher 因 in-flight 守卫最多一笔，不会无限累积。旧响应不会覆盖新任务，这是现有正确性保护。
- **单例/Cluster/多线程影响：** 都是额外 HTTP/DB 快照工作；具体服务线程占用时间由后端决定。
- **根因：** 取消只覆盖定时器和 SSE，没有覆盖 REST 快照。
- **最佳修复：** 每 watcher 建一个 AbortController，`jobsApi.getJob(jobId, {signal})` 透传 Axios signal，teardown abort；保留 disposed 检查处理已排队响应。
- **备选与取舍：** 不取消也仅有 60 秒有界占用，改动最小；统一取消能省资源，但需确保终态当前这次快照不会因 dispose 顺序先 abort 而影响已拿到的结果。
- **修改点/影响面：** `api/jobs.ts` 增加可选 config/signal，`job-watch.ts` 管理 controller；调用方无需变化。
- **后续验证：** 挂起 GET 后 dispose，断言请求 abort、无 onSnapshot/onFinal/onError 迟到回调；终态自收不丢本次结果，快速 A→B→A 切换不串状态。

### FE-POLL-08：POST 对话流收到终帧后只 release reader，不主动 cancel 尚未 EOF 的响应体

- **严重度：低。确定性：代码证实前端缺少取消；持续占用以服务端未立即关流为条件。**
- **证据：** `readStream` 在 `state.terminal` 时跳出读取循环（`frontend/src/api/sse.ts:591-594`），finally 仅 `reader.releaseLock()`（`:618-627`），没有 `reader.cancel()` 或 `controller.abort()`。注释预期后端紧随终帧 `complete()`（`:591`），所以正常后端路径不会长期暴露。
- **触发条件：** 前端收到 done/error，而后端、代理或异常路径没有立即完成响应体。
- **影响：** 该响应可能继续保持网络/流资源，直到对端关闭或浏览器回收；不能仅凭前端代码断言会永久泄漏。多页签异常终帧会放大占用，Redis Cluster 拓扑与此无直接关系。
- **根因：** 终帧被当作业务结束，但传输层只释放锁，没有明确取消剩余流。
- **最佳修复：** 在终帧跳出后的 finally 中，若 `state.terminal && !controller.signal.aborted`，先 best-effort `await reader.cancel()` 再 release；保持 onTerminal/onClose 只执行一次。
- **备选与取舍：** 也可 abort controller，但需复核它是否改变 close 的 cancelled/terminal 语义；reader.cancel 更局部。继续依赖后端 complete 改动为零，但异常路径没有前端兜底。
- **修改点/影响面：** 仅 `api/sse.ts` 的 `readStream`；reattach 的 `settle` 已会 `source.close()`，无需照搬。
- **后续验证：** 模拟 done 后服务端不写 EOF，断言 reader.cancel 被调用且 onClose 为 terminal=true；正常 EOF、error 终帧、用户 abort 三类回归不得改变文案和重连次数。

### FE-POLL-09：健康探测无共享 single-flight，设置页冷启动可与全局面板各发一次

- **严重度：低。确定性：代码证实无共享；重复并发只在特定启动/调用时序发生。**
- **证据：** 全局 `AiPanel.onMounted` 无条件 `void ai.probeHealth()`（`frontend/src/components/AiPanel/AiPanel.vue:5204-5216`）；设置页自己的 `load()` 也 `await aiApi.health()`（`frontend/src/views/AiSettingsView.vue:275-291`）。`probeHealth` 没有在途 Promise 或 TTL（`frontend/src/stores/ai.ts:4218-4244`）。特定聊天错误也会再次补探（`:7056-7083`），但这不是周期轮询。
- **触发条件：** 应用直接以设置页为初始路由，两个 mounted/load 的窗口重叠；或多个错误/入口短时间分别调用。已进入应用后再普通切换设置页，不会仅因该代码让 AiPanel 再挂载，不能泛化为每次进设置都双发。
- **影响：** 冷启动可能多一笔 `/ai/health`；多页签各自重复。若后端健康检查访问 Redis，则会增加相应探活，但本报告未阅读后端，不能把具体 Redis 命令写成事实。
- **根因：** 面板 store 与设置页局部 `health` 各自调用 API，没有共享读模型。
- **最佳修复：** 把健康状态和 in-flight Promise 收到 AI store，设置页复用同一 `probeHealth`/状态；短时间重复调用共享在途结果，可选加很短 TTL 而非周期刷新。
- **备选与取舍：** 只在面板挂载时探测、设置页读取现有值会少请求，但设置页可能展示较旧状态；保留独立调用最简单，代价是偶发重复。
- **修改点/影响面：** `stores/ai.ts`、`AiSettingsView.vue`；保持 503 携带 `available=false` 是正常健康事实的语义。
- **后续验证：** 初始路由为设置页时只发一笔共享请求；一笔失败时两视图一致降级；错误后手动补探不被陈旧 single-flight 永久吞掉。

### FE-POLL-10：三个纯 UI 秒表在 hidden 时未主动停表，有后台渲染工作但无 API 压力

- **严重度：低。确定性：代码证实。**
- **证据：** 压缩消息秒表（`frontend/src/components/AiPanel/AiPanel.vue:1544-1555`）、preparing 秒表（`components/AiPanel/CompactionStatus.vue:39-48`）、思考秒表（`components/AiPanel/ReasoningHeader.vue:45-57`）均只按业务状态/卸载清理，没有 visibility 监听。
- **触发条件：** 页签 hidden 而对应状态仍 live。浏览器通常会节流后台 timer，因此不能按每秒一次保证实际执行次数。
- **影响：** 仍保留 interval 并可能触发 Vue 更新；无网络、Redis 或 DB 请求。多页签会增加少量客户端工作，不是服务端接口放大源。
- **根因：** 可见性优化只应用于 API 轮询，没有应用于本地时钟。
- **最佳修复：** 抽取共享的 1Hz 可见时钟 composable：hidden 停订阅，visible 立即以 `Date.now()` 重算再恢复；避免每个组件另装一套监听。
- **备选与取舍：** 依赖浏览器后台节流无需改代码，且功能正确；主动停表主要省客户端资源，优先级低于所有网络项。
- **修改点/影响面：** 三个组件及一个共享 composable；耗时仍按墙钟计算，不得因停表而少计。
- **后续验证：** hidden 后无 interval 回调，visible 后秒数一次追平；live→done、组件卸载和多个思考段不会残留/互相停表。

## 6. 未发现问题的关键路径与已有防护

1. **通用轮询无重复定时器：** `start` 幂等，`arm` 先清旧表，`refreshNow` 只在有表时重排，hidden/visible 切换清 catchup，stop 清监听；在途和 dirty 合并避免同一 watcher 并发快照（`frontend/src/utils/polling.ts:76-189,223-282`）。
2. **作业旧响应与终态清理：** `disposed` 在成功和失败两侧都检查；终态先 dispose 再 onFinal；看门狗、轮询和 EventSource 同点清理（`frontend/src/utils/job-watch.ts:233-282`）。
3. **跨任务旧响应：** 导入/导出均使用 `taskGeneration`，详情、作业、问题、文件和终态后续都有代次检查；换任务先 dispose 旧 watcher。FE-POLL-05 是错误隔离缺口，不是旧响应覆盖缺口。
4. **任务中心静止零周期请求：** 无视图级 interval/EventSource；列表 `loadSeq` last-wins，旧错误也不污染新筛选；hidden 事件只攒一次补刷（`frontend/src/stores/task.ts:271-384`；`views/TaskCenterView.vue:424-449`）。
5. **AI 重连有界且有归属：** 四次指数退避加抖动；settle 清三类 timer、关流、解监听；probe 有 generation/attempt 闸；store 有同 runId single-flight、轮次 epoch 与 abort 身份闸（`frontend/src/api/sse.ts:1034-1075,1134-1277`；`stores/ai.ts:6322-6490,7128-7363`）。
6. **挂起不空转：** 确认/表单请求后服务端收流被识别为正常 suspended，不在等待用户期间自动重连；决策后按 lastSeq 显式续收。
7. **表单无周期回灌：** 草稿只在本地，250ms 合并，提交/取消/清理会取消旧写；选项请求有序号和父值快照，旧响应不会覆盖新选择。
8. **定义/数据手动刷新有旧响应保护：** 配置定义和数据浏览均有序号控制；工作区的 `refresh_defs`/`refresh_data` 不会假装触发实际刷新。
9. **一次性 UI 计时器总体有界：** 复制、淡出、高亮、resize、下载、对象 URL、DOM observer 均有清理或自然一次性结束；`requestAnimationFrame` 只有一次性滚动恢复，没有动画帧循环。
10. **新建对话无旧会话轮询残留：** `adoptFreshSession` 取消恢复、门校正和待接手表，abort 当前流并增加会话/轮次代次；本报告未找到删除后的删除状态轮询。

## 7. 建议实施顺序与总体验证方案

### 7.1 修改顺序

1. 先修 **FE-POLL-04、05** 的终态可靠性，避免为了性能调整掩盖结果丢失。
2. 再做 **FE-POLL-01** 的心跳语义，消除“健康安静也降级”的根因。
3. 然后做 **FE-POLL-02、03** 的连接复用和生命周期统一；这两项涉及协议与页签协调，不能仅用缩短/延长间隔替代。
4. 最后处理 **FE-POLL-06～10** 的重复请求、取消和本地资源优化。

### 7.2 后续验证计划（本审查未执行）

- **静态计数：** 用浏览器网络日志分别记录 realtime、静默降级、SSE 不可用、hidden、visible、卸载六类场景，按 taskId/jobId/runId 统计 SSE 数和 GET 数；验证请求数与设计公式一致。
- **竞态矩阵：** 覆盖初始 GET 与 onopen/首帧/JOB_DONE 交错、快照在 dispose 前后返回、A→B 换任务、同 kind 重跑、三 kind 并存；断言 last-wins 和恰好一次终态。
- **生命周期矩阵：** hidden 初始、运行中 hidden、hidden 断流、hidden 超过看门狗、visible 恢复、pagehide/pageshow/bfcache；区分用户停止、挂起和 hidden-paused。
- **多页签：** 同源 N 页签打开同一任务，验证连接复用/选主、leader 关闭接管、不同身份不共享会话与快照；不得只测单页签。
- **多服务实例/Redis Cluster 联动：** 由后端报告提供实例切换与 Redis 故障注入点，前端只观察同一 SSE/REST 契约。断言一次 502/超时不会终止发布 watcher，恢复后不双连、不重复回灌。
- **删除对话：** 独立测量 POST 发号、可选 cancel、DELETE 各自的开始/结束时间，避免把串行前置耗时误记成 DELETE 耗时；确认旧流 abort、无旧会话 probe/恢复，后端再核查 Cluster 删除耗时与线程占用。
- **资源回收：** 对终帧后不回 EOF、挂起 GET 后 dispose、组件卸载、Mermaid/SpreadJS 超时分别检查 reader、fetch、observer 和 timer 是否释放；不能仅以 UI 正常作为无泄漏证明。

## 8. 审查限制

本报告是静态生产代码审查，没有运行任何测试、构建、服务或压测，因此未实测浏览器后台节流、代理空闲超时、后端心跳/作业帧频率、Redis Cluster 时延和实际请求速率。所有这类运行时事实均需按第 7 节另行验证；本报告已把代码直接证实的行为与条件性影响分开标注。
