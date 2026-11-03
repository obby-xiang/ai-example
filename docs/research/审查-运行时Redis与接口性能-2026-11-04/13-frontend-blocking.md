# 前端 API、交互等待与阻塞审查（K3.1 Pro / 报告 13）

## 1. 审查结论与边界

- 基线：`52b09364`；审查时 `HEAD` 与该基线一致，工作区起初无改动。
- 本报告只读生产代码与配置；未修改任何生产/测试代码，未运行测试、构建、CI、服务或压测，未执行 git 写操作。
- 前端不直接访问 Redis、DB，也不创建 Java 工作线程。单例 Redis/Cluster、多服务实例和后台线程的影响，必须通过 HTTP/SSE 的延迟、身份上下文、重复请求与响应乱序传导到本报告所审代码；不能把后端删除耗时直接归因于浏览器。
- “新建对话”目前确实先同步清屏、切换本地会话，不等待 DELETE 后才清屏。这一点是正确的；主要问题是旧会话销毁被新发号和取消串行推迟、DELETE 未固定旧身份、重复入口无单飞，以及超时/失败后的前端语义不可见。
- 共记录 10 项问题。其中 FE-08 的“同步主线程路径”由代码证实，但具体卡顿时长和可感知程度属于待测性能假设，不作为已测事实。

### 问题总览

| 编号 | 严重度 | 确定性 | 摘要 |
|---|---|---|---|
| FE-01 | 高 | 代码证实 | 旧会话 DELETE 未携带动作入口捕获的旧身份，等待期间切身份会带错请求上下文 |
| FE-02 | 中高 | 代码证实 | 旧会话销毁串行等待新发号与可选取消；60 秒单请求上限不能覆盖整条链，后台销毁被无谓推迟 |
| FE-03 | 中 | 代码证实 | 新建对话无 reset 级单飞，按钮也无在途态；重复操作可重复发号、取消和重型 DELETE |
| FE-04 | 中 | 代码证实 | 设置页首屏及每次写后刷新串行等待 providers→defaults→health，可选健康检查阻塞主界面 |
| FE-05 | 中高 | 代码证实 | 设置页 `withBusy` 只有标志没有互斥，多个按钮未禁用；并发写与并发 `load()` 可重复请求、提前解 busy、旧响应覆盖 |
| FE-06 | 高 | 代码证实；是否产生重复作业取决于服务端受理 | 导出/导入若干动作缺少入口同步锁；导出在 persist 完成后才亮 starting，双击/AI+手点可重复落库或建作业 |
| FE-07 | 中 | 代码证实 | 任务创建 action 内隐藏等待列表刷新；任务删除无 per-id 防重，写后刷新又拉长等待 |
| FE-08 | 中 | 主线程路径代码证实；卡顿程度待测 | 表格读取、全量校验、OOXML 字符串物化在主线程同步执行；批量下载还存在无上限 `Promise.all` |
| FE-09 | 中 | 代码证实 | 导出作业 `onFinal` 的 issues 请求无 catch，异步回调又未被 watchJob 等待，失败后跳过结果与任务快照刷新 |
| FE-10 | 中低 | 代码证实 | 表单附件上传在会话等待后才置 uploading，未传已有 signal 能力，也无同字段 last-wins；可重复上传或旧响应覆盖新选择 |

---

## 2. 审查范围与实际阅读文件

以下均为本基线实际打开阅读的生产文件；大型 store 按入口、状态、调用链和辅助函数相关区段选读，并非声称逐行阅读全部 1.2 万余行。

### 指定主范围

- `frontend/src/api/http.ts`
- `frontend/src/api/ai.ts`
- `frontend/src/api/sse.ts`
- `frontend/src/api/tasks.ts`
- `frontend/src/stores/ai.ts`
- `frontend/src/stores/task.ts`
- `frontend/src/stores/workspace.ts`
- `frontend/src/components/AiPanel/AiPanel.vue`
- `frontend/src/views/TaskCenterView.vue`
- `frontend/src/views/ImportWizardView.vue`
- `frontend/src/views/ExportWizardView.vue`
- `frontend/src/views/AiSettingsView.vue`
- `frontend/src/views/AiFeedbackView.vue`

### 为闭合调用链实际补读

- `frontend/src/api/aiConfig.ts`、`frontend/src/api/jobs.ts`、`frontend/src/api/index.ts`
- `frontend/src/types/ai.ts`、`frontend/src/types/sse.ts` 的相关契约引用由调用点核对
- `frontend/src/utils/time-constants.ts`
- `frontend/src/utils/ai-identity.ts`
- `frontend/src/utils/job-watch.ts`、`frontend/src/utils/polling.ts`
- `frontend/src/utils/frontend-tools.ts`（预算、作废标记和回灌等待相关段）
- `frontend/src/utils/excel.ts`、`frontend/src/utils/form-draft.ts`、`frontend/src/utils/blocks.ts`、`frontend/src/utils/markdown.ts`
- `frontend/src/components/TaskWizard.vue`、`frontend/src/components/JobProgress.vue`、`frontend/src/components/SpreadGrid.vue`
- `frontend/src/components/AiPanel/FormRenderer.vue`、`frontend/src/components/AiPanel/CompactionStatus.vue`

### 为核实 DELETE 的真实 HTTP/身份/后台语义补读的后端生产入口

- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`

后端只用于确认前端契约，不在本报告中替其他专项重复展开 Redis 删除算法审计。

---

## 3. 完整入口→服务→Redis/DB/线程→返回调用链

### 3.1 新建对话 / 删除旧会话

1. `AiPanel` 头部“新建对话”按钮进入 `newChat()`；无内容时直接 `void ai.resetSession()`，有内容/在途/挂起时先经原生 dialog，确认后同样 `void ai.resetSession()`（`frontend/src/components/AiPanel/AiPanel.vue:23-31,4863-4880`）。
2. `resetSession()` 捕获旧 sessionId、可证明的 oldRunId、旧身份，调用 `adoptFreshSession()`（`frontend/src/stores/ai.ts:11266-11277`）。
3. `adoptFreshSession()` 同步递增会话/轮次代次，中止或隔离旧传输，清屏、清本地指针与旧镜像、复位 loading/挂起/表单/预览等状态（`frontend/src/stores/ai.ts:10704-10765,10780-10865`）。因此 C 端主视图不等待后端删除。
4. 随后先 `await ensureSession()`，必要时经 `POST /api/ai/session` 发新号；再可选 `await aiApi.cancel(oldRunId)`；最后才 `await aiApi.deleteHistory(oldSessionId)`（`frontend/src/stores/ai.ts:11278-11305`）。
5. `createSession`、`cancel`、`deleteHistory` 分别进入 axios POST/POST/DELETE；axios 全局单请求超时为 60 秒（`frontend/src/api/http.ts:142-145,192-214`，`frontend/src/utils/time-constants.ts:21`）。cancel 显式带旧身份，DELETE 当前没有显式身份配置（`frontend/src/api/ai.ts:168-171,298-306`）。
6. 对 AI 对话域 REST，请求拦截器补 `Accept-Language` 及缺省身份头；缺省值读取的是拦截器执行时的当前身份，而不是 reset 入口身份（`frontend/src/api/http.ts:157-175`，`frontend/src/utils/ai-identity.ts:100-105,130-139`）。
7. DELETE 到后端后，`AiSessionPurgeController.purge()` 校验 Redis 可用性与请求身份，同步调用 `AiSessionPurgeService.purge()`，直到服务返回后才写 200（`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`）。该端点不是 202。
8. 服务在请求线程中固定 RequestContext，登记 durable pending，枚举 Redis 会话数据，删 H2 反馈，取消并短等有界活跃 run，再逐项删 Redis、附件与本地文件；结果含 complete/deferred/errors/clientCleanup（`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:341-418,461-540`）。这是后端请求线程上的同步链，不是浏览器主线程计算。
9. 前端收到响应或捕获失败后清浏览器残留；响应中的 complete/deferred/errors 不进入用户提示，当前 UI 也不展示“正在销毁/销毁失败”（`frontend/src/stores/ai.ts:11296-11305`）。

### 3.2 发送、停止、SSE 重挂与返回

1. 输入框按钮或回车进入 `AiPanel.send()`；先检查 canSend/身份，领 sendGeneration，清空草稿并 `await ai.send(text)`（`frontend/src/components/AiPanel/AiPanel.vue:4720-4744`）。
2. `ai.send()` 在 stopping 时最多等 2 秒；已有 loading 则不发送；否则 ensureSession、记录用户/助手消息、置 loading、创建 AbortController 与 clientRequestId（`frontend/src/stores/ai.ts:6559-6632`）。
3. `workspace.buildContext()` 生成页面/任务/最近动作上下文，`aiApi.chat()` 进入 `streamChat()` 的 POST fetch（`frontend/src/stores/ai.ts:6769-6775,6811-6815`；`frontend/src/api/ai.ts:32-39`）。
4. SSE 有 15 秒建连、40 秒首帧、45 秒帧间看门狗；帧经 parser→consumeFrame→store handleFrame 写 parts/投影/镜像；终帧后停止迟到帧（`frontend/src/api/sse.ts:34-87,508-630,861-972`）。
5. 断流且有 runId 时进入 reattach，最多 4 次、1/2/4/8 秒加抖动；准备中探测本身有 15 秒上限，连接代次防止旧探测关掉新连接（`frontend/src/api/sse.ts:974-1278`）。
6. send 的 finally 只复位仍属本轮的 loading/currentAbort，并封口本轮宿主；AiPanel 再按结构化 outcome 与 sendGeneration 决定是否回稿，避免旧轮覆盖新草稿（`frontend/src/stores/ai.ts:6928-6962`，`frontend/src/components/AiPanel/AiPanel.vue:4741-4773`）。
7. 停止按钮进入 `ai.stop()`；stopping 防重，本地先同步取消段落、复位 loading/suspended、写镜像，再 best-effort 等 cancel；新发送对停止落地最多等 2 秒（`frontend/src/stores/ai.ts:10049-10218`）。

### 3.3 任务中心与作业跟踪

1. 任务中心挂载只调一次 `taskStore.load()`；没有周期自刷，也没有任务中心 EventSource 池（`frontend/src/views/TaskCenterView.vue:424-449`）。
2. `load()` 快照筛选参数并领序号，旧响应/旧错误均丢弃；在途计数归零才收 loading，refreshNow 撞在途时合并补发一次（`frontend/src/stores/task.ts:271-325`）。
3. 创建任务：按钮→store.createTask→POST `/tasks`→`await load()`→返回 task→页面再导航；删除任务：确认→store.removeTask→DELETE `/tasks/{id}`→`await load()`→toast（`frontend/src/stores/task.ts:411-437`，`frontend/src/views/TaskCenterView.vue:371-421`）。
4. 向导/发布跟踪经 `watchJob()`：初始及 SSE 降级时 3 秒轮询 `GET /jobs/{id}`；SSE open 后停周期表；120 秒无帧才降级；隐藏页停表，在途合并，终态/卸载关闭（`frontend/src/utils/job-watch.ts:85-153,158-223,233-286`）。
5. 终态以 GET 快照为准，随后各页面拉 issues、文件或任务快照；异步回调的错误处理见 FE-09。

### 3.4 设置页

1. 挂载→`load()`；依次 await listProviders、getDefaults、health，然后才 finally 收全局 loading（`frontend/src/views/AiSettingsView.vue:275-297,330-333`）。
2. provider/model/defaults 各写操作大多在成功后再 `await load()`，因此每次都重复上述串行链；test/pull 本身还可能包含外网等待（`frontend/src/views/AiSettingsView.vue:397-715`）。
3. `withBusy` 仅写布尔标志并在 finally 清除，没有入口 busy 检查、请求 token 或取消；部分按钮甚至不读取 busy 作为 disabled（`frontend/src/views/AiSettingsView.vue:83-94,818-945`）。

### 3.5 导入导出向导与大数据

1. 两页挂载并行执行定义列表与 loadTask（`frontend/src/views/ExportWizardView.vue:2839-2847`，`frontend/src/views/ImportWizardView.vue:2564-2572`）。
2. loadTask 后仍有详情→文件清单→作业恢复→结果/暂存等有序依赖；任务代次用于拒绝旧任务响应。该顺序中有真实依赖，不能一律改成并行。
3. 导出 persist 为 selectDefs→逐项 setCondition→getTask；导出保存及导入写回逐文件“校验→读取 SpreadJS→生成 xlsx→PUT”；导入编辑载入逐文件下载/解析（`frontend/src/views/ExportWizardView.vue:1170-1255,2149-2186`，`frontend/src/views/ImportWizardView.vue:1209-1266,1370-1427`）。
4. SpreadGrid 的 collectRows、validateLocal 均为同步调用；OOXML 在 JSZip 的异步 generateAsync 之前已同步构造完整行列对象和 XML 字符串（`frontend/src/components/SpreadGrid.vue:486-515`，`frontend/src/utils/excel.ts:468-491,554-645`）。

---

## 4. 逐项问题、根因与修复方案

### FE-01 高：旧会话 DELETE 会丢失动作入口身份

**证据与确定性：代码证实。**

- reset 入口捕获 `purgeIdentity`（`frontend/src/stores/ai.ts:11270-11273`）。
- cancel 正确使用 `identityHeaders(purgeIdentity)`（`frontend/src/stores/ai.ts:11282-11287`）。
- DELETE 却只传 oldSessionId，API 也不接受 AxiosRequestConfig（`frontend/src/stores/ai.ts:11296-11298`，`frontend/src/api/ai.ts:304-306`）。
- 拦截器只补缺省头，缺省来自当时的 `currentAiIdentity()`（`frontend/src/api/http.ts:157-173`，`frontend/src/utils/ai-identity.ts:100-105,130-139`）。
- 后端明确按请求上下文身份定位销毁范围（`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:80-100`）。

**触发条件。** 用户在 A 身份点击新建对话；reset 正在等待新发号或旧轮 cancel；期间切到 B；随后旧 A 会话的 DELETE 才发出，并带 B 的身份头。即使没有显式 cancel，ensureSession 的等待也提供该窗口。

**影响。** 旧 A 会话可能不被删除或请求被拒绝，具体表现由后端该身份下的数据决定；不能从代码推断会误删 B 的同号会话。单实例与 Cluster 都存在；多实例/非粘滞路由不改变错误身份事实。浏览器异步任务交错即可触发，与 Java 多线程无关。Cluster 慢删除会扩大窗口。

**根因。** 同函数已经认识到 cancel 必须固定旧身份，却没有把同一规则应用到 DELETE；身份捕获与 API 签名不完整。

**最佳修复。** 给 `deleteHistory(sessionId, config?)` 增加可选 Axios 配置，并在 reset 中传 `{ headers: identityHeaders(purgeIdentity) }`；所有“代表旧现场”的写请求统一在动作入口捕获 sessionId、identity、runId。不要依赖全局当前身份。

**备选取舍。** 也可让请求体携带身份，但这会扩大后端契约且容易与 Filter 产生双事实源，不推荐。仅把 DELETE 移到切身份之前而不加显式头，只能缩小窗口，不能消除后续扩展中的同类问题。

**修改点/影响面。** `api/ai.ts` 的 deleteHistory 签名、`stores/ai.ts` 的调用点；与现有 cancel 同形态，其他调用方不受影响。

**后续验证（只记录，不执行）。** 用可控 promise 分别停住新发号与 cancel，期间切身份；断言 DELETE 的 URL 为旧 sid 且头仍为旧身份。覆盖无旧 run、有旧 run、发号失败、单实例与两个服务实例轮流响应。

### FE-02 中高：旧会话销毁被新发号和取消串行挡住

**证据与确定性：代码证实。**

`resetSession()` 的顺序为：同步本地 reset→await ensureSession→可选 await cancel→await DELETE（`frontend/src/stores/ai.ts:11277-11305`）。每一步 HTTP 各自最多 60 秒，链上没有总预算（`frontend/src/api/http.ts:142-145`）。后端 DELETE 是同步控制器调用，且自身已负责取消活跃 run（`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:87-103`；`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:461-470`）。

**触发条件与超过 30 秒场景。** 后端删除超过 30 秒时，本地新会话已显示，用户不会看到 30 秒遮罩；但旧数据销毁回执和响应驱动清理仍未完成。若先发号慢、cancel 也慢，DELETE 甚至尚未开始。单步超过 60 秒时 axios 归一为网络类错误并进入 catch；这只能证明前端等待失败，不能证明服务端线程已停止或删除已回滚。

**影响。** 单例 Redis 与 Cluster 都会发生；Cluster 慢操作只是放大后台驻留时间。多实例下旧会话销毁可能由任意实例承担，无需粘滞，但旧身份必须正确（FE-01）。多个后台 reset 会占更多连接/后端请求线程（与 FE-03 叠加）。C 端“不假死”不等于“删除语义已完成”。

**根因。** 把互相独立的新会话发号与旧会话销毁写成一个串行 async action；又在 DELETE 前重复执行 purge 自身已经包含的取消职责。

**最佳修复（保持 C 端无感且语义正确）。**

1. 保留 `adoptFreshSession()` 同步立即清屏。
2. 本地 reset 后立刻创建“旧会话后台销毁任务”，不要等新发号；固定旧 sid/身份。
3. 优先让 DELETE 端点独自承担取消与销毁，删除该路径额外的前置 cancel；如果必须保留兼容旧后端，则把 cancel→DELETE 放在后台任务内部，也不能挡新发号。
4. 新会话 `ensureSession()` 与旧会话后台销毁并行推进；二者结果分别处理，不能用新会话失败阻止旧会话删除。
5. 不在前端把超时描述成“已删除”或“后端已停止”。本地清理可按已知旧键立即执行，服务端销毁依赖 pending/重试事实，而不是伪造完成态。

**备选取舍。** 若产品要求 HTTP 快速 ACK，可后端改 202 + purgeId 查询/事件，前端只跟踪轻量状态；这是更大的契约改造。当前已有 durable pending 与后端重试时，先做“立即发起 + 身份固定 + 后台单飞”即可，不必为了隐藏等待而新造一套轮询。

**修改点/影响面。** `stores/ai.ts#resetSession` 与提取的旧会话销毁函数；`api/ai.ts` 配置参数；AiPanel 无需加全局 loading。注意保持旧传输隔离和本地清理键不变。

**后续验证。** 分别注入发号 30 秒、cancel 30 秒、DELETE 30/60/60+秒；记录每个请求实际开始时刻，断言本地清屏立即、DELETE 不再等发号、不会因前端超时宣称完成。后端 standalone/Cluster 各验证 pending 与最终删除事实，但不在本次审查执行。

### FE-03 中：新建对话缺少 reset/销毁单飞，重复点击放大慢删除

**证据与确定性：代码证实。**

- new-chat 按钮无 loading/disabled，两个入口均 `void ai.resetSession()`（`frontend/src/components/AiPanel/AiPanel.vue:23-31,4863-4880`）。
- resetSession 没有 in-flight guard；sessionGate 只合并会话发号，不是 reset 或 DELETE 单飞（`frontend/src/stores/ai.ts:10597-10694,11266-11306`）。
- 后端声明重复 DELETE 幂等并复用 pending，可降低正确性风险，但不等于前端重复 HTTP 和枚举工作不存在（`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:350-373`）。

**触发条件。** 快速双击、确认框重复确认、错误卡“开新会话”与头部按钮交错，或旧 DELETE 很慢时再次操作。

**影响。** 同一旧会话可重复 cancel/DELETE；多次 reset 还可能使先发号被代次作废后再发。单例/Cluster 都承受额外请求；多实例会把负载分散而非消除；多浏览器页签各自还有独立调用面。后端幂等降低了“重复删坏”的概率，不应据此忽略请求放大。

**根因。** 把 fire-and-forget 当成不需要所有权；没有按“ reset 引导”和“旧会话销毁”分别设单飞边界。

**最佳修复。** reset 引导阶段使用同步令牌/同一 promise 合并重复点击；后台销毁按 `{旧身份指纹, oldSessionId}` 建 Map<string, Promise> 单飞，完成后删除。不要以旧 DELETE 未完成来长期禁用新会话输入；只在相同 reset 尚未完成本地引导时合并。

**备选取择。** 只给按钮加 disabled 可减轻手点，却不能覆盖错误卡和程序化调用，且会引入“后台删除多久就锁多久”的错误交互；必须同时在 store 防重。

**修改点/影响面。** `stores/ai.ts` 模块级销毁登记表与 reset 引导锁；AiPanel 可只做即时视觉反馈，不承担正确性。

**后续验证。** 同一 tick 双击、确认框与错误卡交错、慢 DELETE 期间再次新建；统计 create/cancel/DELETE 次数，并确认新的有内容会话不会被旧回包清理。

### FE-04 中：设置页串行瀑布让可选 health 阻塞整页

**证据与确定性：代码证实。**

`load()` 依次等待 providers、defaults、health（`frontend/src/views/AiSettingsView.vue:275-291`），全局 loading 到最后才复位（`:292-296`）。多数写操作成功后又 await 同一个 load（如 `:397-412,448-462,644-665`）。每跳均使用 60 秒 axios 上限。

**触发条件。** 首屏、重试、任一保存/启停/删除/添加后；defaults 或 health 慢时，已获得的 providers 仍被全局 loading 遮住。若前一步 reject，会提前结束，因此不能笼统说所有失败都固定等待 180 秒；若三跳都缓慢成功，延迟会相加。

**影响。** 与 Redis 拓扑无关，但 health 若读运行时状态会传递后端慢速；多实例延迟差异会暴露串行尾部延迟。浏览器只发三个串行请求，没有多线程加速。

**根因。** 将首屏必需数据与可选诊断数据放进同一个全局事务式 loading；写后又无条件全量重取。

**最佳修复。** providers/defaults 按依赖并行且各自落错误；health 独立后台更新，不控制整页 loading。写操作优先消费权威写响应或只刷新受影响资源；确需全量对账时保留当前内容并后台刷新。

**备选取舍。** 简单 `Promise.all` 可缩短首屏，但仍让 health 阻塞总 loading，且一个失败会拖垮全部，因此不是完整修复。

**修改点/影响面.** `AiSettingsView.vue#load` 与各写后刷新；保持 fillForms/fillDefaults 的脏值保护，不得为提速重新无条件覆盖用户输入。

**后续验证。** 分别延迟三接口，断言 providers 就绪即可交互、health 慢不遮罩、写后只产生预期请求，且脏表单不回退。

### FE-05 中高：设置页 busy 不是互斥，且 load 无 last-wins

**证据与确定性：代码证实。**

- `withBusy` 入口不检查既有 busy，finally 无条件清 false（`frontend/src/views/AiSettingsView.vue:87-94`）。
- clear/test/toggle/remove、添加模型、模型行保存/删除等按钮未统一 disabled；manual 只检查 selectedProvider（`:827-845,900,932-945`）。
- 同一 key 可重入；两个不同写操作又可各自触发 `load()`。load 无序号/取消，旧响应可后写 providers/defaults/health，旧 finally 也可提前收 loading（`:275-297`）。

**触发条件。** 快速双击、键盘与程序化重复触发、用户在一个写请求在途时操作另一卡片；两个 load 乱序返回。

**影响.** 重复测试/拉模型会放大外网调用，重复写会增加 DB 压力；旧列表响应可覆盖新状态。多服务实例无共享前端 busy，响应时序差更明显。Cluster 不直接相关。

**根因。** busy 被当作渲染布尔而非同步所有权；写后统一全量 load 又没有 last-wins。

**最佳修复.** `withBusy` 首行同步拒绝同 key 重入，finally 只释放自己持有的 token；所有对应按钮/表单统一读取 busy。load 增序号或取消前次，仅最新快照允许写回；不同资源可独立刷新，避免并发全量互相覆盖。

**备选取舍。** 仅补 disabled 不足；仅加 loadSeq 能修旧响应，却不能阻止重复写。对 testConnection 等只读探测可选择“并入同一 promise”而非简单拒绝。

**修改点/影响面.** `AiSettingsView.vue` 的 busy helper、load、模板按钮；无需改 API 契约。

**后续验证.** 同 key 双击、异 key 并发、两个 load 逆序返回、旧请求失败晚到；断言请求数、busy 归属、最终列表和错误提示均属于最新操作。

### FE-06 高：向导作业/写操作在首个 await 前后缺少完整同步锁

**证据与确定性：代码证实；重复作业是否落库取决于服务端受理。**

- 导出 `startExport()` 先 await persistStepData，直到 `:1420` 才置 startingExport；按钮只看 startingExport/jobRunning（`frontend/src/views/ExportWizardView.vue:84,1373-1423`）。入口没有 startingExport/persisting 重入拒绝，确认框之前也没有 pending 锁（`:1460-1478`）。
- 导入 startCheck/startImport 直接进 startJob，没有入口 guard；busy 虽在 createJob 前置位，但只是共享值（`frontend/src/views/ImportWizardView.vue:1795-1846`）。startPublish 的 publishPending 是已有的正确对照（`:1862-1878`）。
- 导出 persist 逐项写条件，导入 flushEditors 逐文件写回；重入会重复这些写（`frontend/src/views/ExportWizardView.vue:1178-1222`，`frontend/src/views/ImportWizardView.vue:1338-1447`）。

**触发条件.** 双击、确认框未答时再次点击、AI 页面 handler 与用户手点同时进入、慢 persist 时再次点开始。

**影响.** 确定会重复前端流程和请求；是否产生两个作业由后端互斥决定，不能写成既定事实。多实例下若只依赖进程内 busy，前端重复请求仍可到不同实例；Redis/DB 慢会扩大重入窗口。

**根因.** loading 被当成事后显示态，锁没有覆盖“参数校验/确认/persist/create/attach”完整动作；共享 busy 也不能表达各操作所有权。

**最佳修复.** 为 startExport、startCheck、startImport、flush/save 等建立入口同步 in-flight guard，锁从点击/调用瞬间覆盖到 attach 完成；按钮 loading/disabled 读取同一事实。开始导出应把 persist 纳入 startingExport 生命周期。AI handler 与手点必须复用同一锁。

**备选取舍.** 后端 idempotency key/作业去重是必要纵深，但不能代替前端体验修复；只给按钮加 disabled 同样不足。

**修改点/影响面.** ExportWizardView 的 startExport/onStartExportClick，ImportWizardView 的 startJob/flushEditors，必要时共享小组件；保持任务代次和“作业已建但 attach 失败仍算已建”的真实性逻辑。

**后续验证.** 同 tick 双击、AI+手点、persist 逆序延迟、create 成功后 attach 失败；统计每个 HTTP 写请求，并确认最终回执不把已建作业说成未建。

### FE-07 中：任务 store 把列表刷新藏进创建/删除，删除按钮又无防重

**证据与确定性：代码证实。**

- createTask/removeTask 都在写成功后 `await this.load()` 才返回（`frontend/src/stores/task.ts:411-437`）。
- 向导创建任务也复用该 action，因此即使不需要看任务中心，也要等隐藏列表请求后才绑定新任务（`frontend/src/views/ExportWizardView.vue:2902-2917`，`frontend/src/views/ImportWizardView.vue:2750-2771`）。
- 任务中心删除按钮无 loading/disabled，视图 removeTask 与 store 均无 per-id in-flight 锁（`frontend/src/views/TaskCenterView.vue:101,371-390`）。

**触发条件.** 创建时列表接口慢；用户连续删除同一行；删除后列表慢导致成功反馈延迟。

**影响.** POST/DELETE 与 GET 的等待串联；重复删除增加后端负载。与 Redis Cluster 无直接关系，多实例只增加响应时序变化。列表自身 last-wins 能避免旧 load 覆盖新 load，但不能避免重复 DELETE。

**根因.** store 混合“业务写”“列表订阅刷新”“页面导航”三种职责；删除视图只靠用户不再点击。

**最佳修复.** createTask 在权威创建响应后立刻返回，并保留 knownTaskTypes；任务中心自行触发一次后台刷新，向导不等待它。removeTask 按 taskId 单飞，视图按钮显示同一锁；成功后本地移除或后台 refreshNow，不必把 toast 阻塞在 GET 后。

**备选取舍.** 可给 action 加 `refresh=true` 参数，但显式拆分“create/remove”和“refresh”更清晰；不要通过全局 loading 阻止所有行的删除。

**修改点/影响面.** `stores/task.ts`、TaskCenterView、两个向导调用语义。TaskCenter 创建路径仍需刷新，删除失败仍应保留行并显示错误。

**后续验证.** 创建响应快而列表慢时向导应立即绑定；删除双击只发一次 DELETE；列表旧响应仍不能覆盖新筛选。

### FE-08 中：大表格处理与打包存在主线程/内存放大面

**证据与确定性：同步路径由代码证实；实际卡顿时长待 profiling。**

- `readRows()` 全行全列同步读取，`validateRows()` 全量逐字段校验并无界收集 issues（`frontend/src/utils/excel.ts:175-213,371-423`）。
- `toXlsxBlob()` 先同步 collectRows；buildRowsXlsxBlob 又物化完整 rows→cells；buildXlsx 在首个真正的异步压缩前同步拼完整 XML/sharedStrings（`frontend/src/components/SpreadGrid.vue:486-515`，`frontend/src/utils/excel.ts:468-491,554-645`）。
- 导入/导出保存对这些操作逐项串行 await，整体等待随文件数增加；批量下载则用无并发上限的 `Promise.all` 同时构建所有文件（`frontend/src/views/ExportWizardView.vue:2003-2006,2056-2060,2086-2090`）。
- async/await 与 JSZip.generateAsync 不会自动把前面的同步计算移出主线程。

**触发条件.** 大表、宽表、大量校验错误、多配置批量下载/保存。具体多少行会可感知卡顿，本审查未测量，不能给伪造阈值。

**影响.** 与 Redis 单例/Cluster 无直接关系；慢网络只会延长总体等待。浏览器默认单主线程会被同步读取/校验/字符串拼接占住；多个批量任务同时产生内存峰值。多服务实例无法解决浏览器主线程问题。

**根因.** 把“Promise 返回”误当成非阻塞；数据在多个完整副本间物化；批量侧没有容量/并发预算。

**最佳修复.** 将纯数据的 validate/OOXML 构建移到 Web Worker，输入只传可结构化克隆的 fields/plain rows，不传 SpreadJS 对象；主线程保留必要的 SpreadJS 提取，并分片让出事件循环。批量文件采用有界并发（例如经测量选择 2–4），逐批交付结果，保留任务代次/结果批次作废逻辑。错误清单可限制展示副本，但正确性扫描不能因展示上限而提前漏检。

**备选取舍.** 仅加 setTimeout/Promise.resolve 不能减少单次大同步块；分片 yield 是较小改造但仍占主线程。Worker 改造成本更高，适合作为大数据主方案。

**修改点/影响面.** `SpreadGrid.vue`、`utils/excel.ts`、导入导出保存/下载调用面；需保持 OOXML 字节契约、数值保真、校验规则与下载批次闸不变。

**后续验证.** 后续以不同行×列、错误密度和文件数做浏览器 performance trace，记录最长 task、堆峰值、输入响应和生成时长；同时做生成文件逐字段对账。该方案仅是验证计划，本次未运行。

### FE-09 中：导出终态的 issues 失败会中断后续结果刷新

**证据与确定性：代码证实。**

- watchJob 在终态快照后先 dispose，再调用 `options.onFinal?.(finalJob)`，没有 await/catch 异步结局（`frontend/src/utils/job-watch.ts:240-263`）。
- 导出 onFinal 是 async，但 `await jobsApi.getIssues(jobId)` 位于任何 try/catch 之外；成功 toast、refreshResults、任务快照刷新都在它之后（`frontend/src/views/ExportWizardView.vue:1513-1548`）。

**触发条件.** 作业已到终态，但 issues 请求网络失败/超时/5xx。

**影响.** async onFinal reject，后续 refreshResults/getTask 不执行，用户可能看到终态作业却仍是旧结果；并产生未处理 rejection。导入侧 refreshIssues 自己有 catch，结构不同，不应一概报同缺陷。Redis 拓扑不直接相关；多实例下任一实例失败均可触发。

**根因.** 把可选明细读取放在关键终态刷新的串行前置，同时 watcher 契约未承担异步回调错误。

**最佳修复.** 终态处理按“核心结果”和“可选 issues”隔离：issues 自己 catch 并给可见降级；无论 issues 成败都继续 refreshResults 与任务快照。可在 watchJob 层统一 `Promise.resolve(onFinal()).catch(...)` 作为纵深，但页面仍需保证核心刷新不被可选步骤阻断。

**备选取舍.** 只给 watchJob 加 catch 能消除未处理 rejection，却会保留“结果没刷新”的业务缺陷，不足够。

**修改点/影响面.** ExportWizardView onFinal，必要时 job-watch 的异步回调契约；其他 watcher 不应被统一改成无限重试。

**后续验证.** 终态快照成功、issues 失败，断言无 unhandled rejection、结果文件与任务状态仍刷新、错误提示不伪称导出失败。

### FE-10 中低：表单附件上传的等待窗、取消与 last-wins 不完整

**证据与确定性：代码证实。**

- onAttachmentChange 先 await ensureSession，直到 `:824` 才置 uploading；入口无 uploading 早退（`frontend/src/components/AiPanel/FormRenderer.vue:802-826`）。
- API 已支持 `context.signal`，但组件没有 AbortController，也未传 signal（`frontend/src/api/ai.ts:224-249`）。
- 响应回来无条件写 attachments/setValue；同字段没有请求序号，旧上传可覆盖更新选择（`FormRenderer.vue:826-835`）。

**触发条件.** 发号慢时连续选文件；上传在途时停止/换会话/卸载；同字段发生重入时响应逆序。

**影响.** 重复上传、旧 fileId 覆盖新选择、卸载后无用请求继续到 60 秒上限。与 Redis 拓扑无直接关系；多实例可能增加逆序概率。

**根因.** loading 只覆盖网络上传而没有覆盖会话准备；已有取消能力未接到组件生命周期；缺少同字段所有权。

**最佳修复.** 入口同步登记同字段操作序号并在会话等待前显示 uploading；每次新选择取消前一请求，stop/换会话/卸载 abort；响应只有序号和表单归属仍匹配才写值。直接把已有 signal 透传到 uploadFormAttachment。

**备选取舍.** 只加 disabled 仍有程序化/同 tick 窗口；只允许串行而不要求 last-wins，会保留旧响应覆盖。

**修改点/影响面.** FormRenderer.vue，无需改 ai.ts 的 API 形状；注意清理 input 值和上传错误仍属于对应操作。

**后续验证.** 发号 pending 双选、两次上传逆序、停止/卸载时 abort、旧响应不得改新 fileId；失败应保留可重试入口。

---

## 5. 轮询、超时、取消、旧响应与多实例专项结论

### 5.1 全部已定位的周期机制

- AI SSE 不是轮询；其重连和准备中恢复均有次数/时间预算，不应把心跳当成轮询缺陷。
- 任务中心无周期自刷；只有手动、写后、受限事件刷新。隐藏期只记一次补刷，不补账（`frontend/src/stores/task.ts:315-384`）。
- 作业 `watchJob` 是当前主要 3 秒兜底轮询：只在 watcher 存活且 SSE 未健康工作时持续；hidden 停表、visible 0–250ms 错峰补一次、在途 dirty 合并、终态/卸载清理（`frontend/src/utils/job-watch.ts:85-286`，`frontend/src/utils/polling.ts:57-283`）。
- 120 秒无 SSE 帧才判假死是明确的可用性/请求量取舍，不是无界等待；如产品要求更快恢复，应调阈值并评估请求量，而不是另加常驻多速轮询。
- AiPanel 的压缩/准备中秒表、表单草稿防抖、镜像节流是 UI/本地持久化计时器，不是后端轮询。
- 多页签会对同一作业各自维护 watcher；当前没有跨页签共享轮询所有权。这会在线性增加请求，但代码已有隐藏停表与在途合并，本报告不把它单独定性为缺陷。

### 5.2 超时/取消/重试

- 所有 axios 请求共享 60 秒上限，避免普通 REST 永久 await；这不是“整条 reset 链 60 秒”的总预算。
- SSE 建连/首帧/帧间/探测都有看门狗；重连有界且带 jitter，停止后不重连。
- AI 开流前瞬时失败只自动重试一次，复用 clientRequestId；409 duplicateRequest 会接管既有 run，而不是再造一条驱动。不能把该路径误报为无保护重复发送。
- 普通任务/设置/删除请求没有统一 AbortController 生命周期；60 秒超时不等于离页取消。是否需要取消应按 FE-01/05/10 等具体所有权补，而不是给所有请求加盲目自动重试。
- 删除接口在后端声明幂等并有 pending 重试，但网络失败是否已到达后端不可由前端知道；前端不应自动宣称删除成功或后端已停止。

### 5.3 旧响应覆盖

已有可靠防线：任务列表 loadSeq、AI 会话/轮次代次、SSE 连接代次与终帧闸、设置拉模型的 provider 归属、FormRenderer 选项请求序号、导入导出任务代次与下载批次。问题集中在 FE-05 的设置全量 load、FE-10 的附件同字段上传，不应把其他已有防线误报成同类问题。

---

## 6. 未发现问题的关键路径（基于静态代码，不等同于运行时证明）

1. **新建对话的本地即时性。** `adoptFreshSession()` 在 DELETE 前同步清屏和复位 loading；当前不存在“必须等 Cluster 删除超过 30 秒才出现新会话”的前端 await 阻塞。需要修的是后台销毁时序与语义，不是重新加全局遮罩。
2. **发送按钮与停止。** 输入在在途时仍可编辑；send 有 loading 闸，stop 有 stopping 防重且本地先收敛，send 对 stop 的等待有 2 秒上限。
3. **SSE 无限 loading。** 建连、首帧、帧间、reattach 均有界；send/reattach 的 finally 有当前轮归属检查，断流会封口宿主，不是永久转圈。
4. **SSE 多实例帧序。** 不用“seq 必须 +1”猜缺口；归档裁剪只认后端响应头，符合多实例允许跳号的语义。
5. **SSE 重连风暴。** 次数、退避、jitter、单 run 驱动所有权和探测代次均存在；未发现无限自动重连。
6. **任务中心静态轮询。** 页面静止无周期请求；load 有 last-wins，refreshNow 有在途合并。
7. **作业轮询重叠。** watchJob 有 inflight+dirty，hidden 停表，终态/卸载 dispose；未发现固定 interval 无视在途叠加请求的实现。
8. **AI 历史分页。** “加载更早”从固定快照切片，不重复请求整份历史；有 historyPageLoading、防旧快照写回和滚动锚点补偿。
9. **AiPanel 大数据已有缓解。** Markdown 有 75ms 流式合并、前缀/历史缓存、长文降级与 mermaid idle/上限；镜像有 300ms 节流、增量序列化和条数/字符预算。它们是已有优化，不代表 FE-08 的 SpreadJS/OOXML 路径也已被覆盖。
10. **导入导出跨任务写入。** 多处捕获 taskGeneration/forTask，在 await 后复检，避免旧任务字节/快照写入新任务；该既有防线应保留。
11. **设置页脏表单。** fillForms/fillDefaults 使用基线保留用户脏值；提速重构不得破坏这一正确性。
12. **AiFeedbackView。** 反馈列表只在挂载/显式重试时读取，无周期轮询；loading/error/finally 收口存在。本次未发现需要单独立项的阻塞路径。

---

## 7. 推荐实施顺序与后续整体验证（只写计划，不执行）

### 实施顺序

1. **先修 FE-01、FE-02、FE-03：** 固定旧身份，旧会话销毁立即后台发起，按旧会话单飞；这是删除对话超过 30 秒场景的前端主线。
2. **再修 FE-06、FE-09：** 避免重复作业和终态后旧结果，属于业务正确性。
3. **再修 FE-05、FE-07、FE-04：** 收口重复写、旧响应和无谓瀑布。
4. **最后按测量实施 FE-08，补 FE-10：** Worker/有界并发需要性能数据选择参数；附件修复可独立小步完成。

### 删除对话端到端验证清单

- 本地点击到清屏的时延与 DELETE 无关；无全局 loading，输入区按新会话 readiness 正确启停。
- A 身份开始后切 B，旧 DELETE 始终带 A；两个服务实例交替处理也不变。
- 新发号慢/失败不推迟旧会话 DELETE；有活跃 run 时由明确归属的销毁链负责取消。
- DELETE 在 30 秒、60 秒、超过 60 秒及网络失败时，UI 不伪报完成；本地旧键清理与新会话互不污染。
- 双击/多入口只保留一次同旧会话销毁任务；新建第二个有内容会话不受旧回包影响。
- standalone 与 Redis Cluster 最终以服务端实际键/H2/附件事实验证 complete，而不是只看前端 toast；此部分需与后端专项联合验证。

### 其余验证清单

- 用可控延迟与逆序响应覆盖设置、任务、附件的重复请求和 last-wins。
- 覆盖 AI handler 与用户同 tick 触发同一作业，核对前端锁与服务端真实作业数。
- 覆盖作业终态+issues 失败，核心结果仍刷新。
- 大数据用浏览器 trace 验证 Worker/分片前后的最长任务、内存和输入响应；同时校验导出文件内容完全一致。
- 所有验证均为后续计划；本次审查未执行任何测试、构建、服务或压测。
