# H. 前端身份与本地共享状态独立审查报告

## 身份门禁证据

- `agentId`：`agent-947`
- `profile.bind`：`modelAlias=kimi-code/k3.1-pro`，位于 `<会话证据根>/agents/agent-947/wire.jsonl` 第 6 行
- 首条 `llm.request`：`model=k3.1-pro`、`modelAlias=kimi-code/k3.1-pro`，位于同一文件第 20 行
- 门禁结果：**通过**
- 仓库 HEAD 已只读核实：`17080859f7b6905aafbbea90f6d2281913bcab9e`

本次仅静态阅读，未修改代码、未运行测试/批量脚本、未执行 git 写操作、未启停服务。仓内未发现 `switchIdentity` 的 UI 调用方，也未发现登出实现；因此下文会区分“默认启动即可发生”与“调用公开 store action、接入真实登录/登出后可发生”。

## 总结

| 严重度 | 数量 | 核心结论 |
|---|---:|---|
| P0 | 1 | 客户端可任意自称租户/用户，后端仅检查静态目录，不能形成身份安全边界 |
| P1 | 6 | 全租户目录依赖；跨身份同 sessionId 的迟到响应串写；反馈跨身份写入/展示；工作区旧任务和异步结果残留；无登出清理 |
| P2 | 4 | SSE/发号未固定操作身份；无 Web Locks 时复制页签共享会话；long 身份 ID 精度风险；若干内存/草稿台账未完整命名空间化 |
| P3 | 1 | SSE 重连期间语言未按轮次固定，可能产生混合语言语义 |

---

## H-01 P0 确定问题：身份由客户端自由选择，任意访客可读写任意预置租户/用户的 AI 数据

**证据**

- 前端身份可从 `sessionStorage['ai-identity']` 恢复，随后直接生成 `X-Tenant-Id/X-User-Id`：
  `frontend/src/utils/ai-identity.ts:78-105`、`:130-138`
- 无目录身份时自动选择“目录第一项”：
  `frontend/src/stores/ai.ts:10877-10884`
- 目录接口不要求身份：
  `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:14-24`
- 后端 Filter 只检查请求头是否为正数、组合是否存在于静态目录，没有认证主体、签名、会话或权限校验：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118-149`
- 静态目录公开两个租户及四个用户：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-34`

**完整调用链与触发场景**

1. 新浏览器启动，`AiPanel.onMounted → loadTenantDirectory()`。
2. 默认落入第一租户第一用户；或者攻击者预先写入另一个合法 `ai-identity` 后刷新。
3. `currentAiIdentity → identityHeaders → http interceptor/SSE fetch`。
4. 后端 `TenantIdentityFilter` 仅执行 `TenantUserDirectory.exists`，随后建立该身份上下文。
5. 攻击者即可按受害身份调用 history/chat/feedback/cancel/session 等接口。所有实例共用同一目录和 Filter，因此落到任意实例结果相同。

**影响**

这不是“键名少租户段”，而是整个身份值不可信：对话、反馈、运行态及写操作的租户+用户隔离均可被直接绕过。默认启动还让不同真实用户共同使用同一个预置身份。

**修改建议**

- 身份必须来自服务端认证主体，例如登录会话、可信 token 或网关注入且经过验证的 principal。
- 删除客户端任意传入 `tenantId/userId` 的入口；`switchIdentity(tenantId,userId)` 应改为 `refreshIdentityFromPrincipal()`。
- 请求头最多作为“期望上下文”：服务端必须逐一与认证主体比较；不匹配即拒绝。若由可信网关传递，外部请求必须先剥离同名头。
- 用只返回“当前真实请求身份”的端点替代目录端点，例如 `GET /api/ai/identity`，不得返回其它租户或其它用户。
- 升级时应使旧未认证会话失效。现有带身份浏览器数据只有在当前认证主体与键内身份一致时才可恢复；无身份旧键不得迁移给默认用户。

**后续单点验证**

分别伪造两个合法目录身份请求 history/feedback/chat，必须全部 401/403；不带身份调用 `/identity` 只能得到当前登录主体，不能得到任何候选列表。

---

## H-02 P1 确定问题：启动和身份校验依赖“获取全部租户/用户目录”，违反生产硬性口径

**证据**

- 前端封装：`GET /api/ai/tenants`
  `frontend/src/api/ai.ts:148-157`
- store 启动必须加载完整目录，目录不可用就禁用发送：
  `frontend/src/stores/ai.ts:10826-10885`
- 身份是否有效也通过全目录 `directoryHas()` 判断：
  `frontend/src/stores/ai.ts:1027-1033`
- 后端直接返回 `TenantUserDirectory.tenants()` 全量列表：
  `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`

**场景与影响**

生产没有“循环租户列表/获取全部租户列表”的能力，因此当前前端启动链会稳定进入 `directoryStatus='unavailable'`，无法发号、无法对话。若为了兼容而重新开放该接口，又会形成租户/用户枚举面。任何实例都必须额外提供这一不符合部署能力的端点。

**修改建议**

- 删除 `tenantUsers()`、`tenantDirectory`、`directoryHas()` 以及“目录第一项”默认逻辑。
- 用当前请求身份引导：`GET /identity → 校验当前 principal → 恢复该身份自己的 session pointer`。
- 只允许缓存从真实请求得到的当前租户/用户；登出、401、主体变化时立即失效，不得通过租户列表补全或刷新缓存。
- 本地 `sessionStorage/localStorage` 的前缀清理可以按键前缀执行，不属于枚举服务端租户，不应再调用 `/tenants` 辅助清理。

**后续单点验证**

在完全不存在 `/api/ai/tenants` 的环境启动前端，当前登录用户应能正常发号和恢复自己的会话，且网络中没有任何租户列表请求。

**本领域全部租户枚举/列表依赖**

仅发现上述 `/api/ai/tenants → loadTenantDirectory → directoryHas/default first` 及后端 `TenantUserDirectory.tenants()`。未发现本领域其它遍历租户做恢复、任务扫描或缓存填充的代码。

---

## H-03 P1 确定问题：历史/上下文/快照响应只用 sessionId 判新鲜度，跨身份同号时会串写对话数据

**证据**

以下守卫均没有比较捕获身份或 `sessionEpoch`：

- 历史请求代次仅以 `sid` 为键：
  `frontend/src/stores/ai.ts:4606-4614`、`:4730-4756`
- 上下文响应只比较 `this.sessionId`：
  `frontend/src/stores/ai.ts:4565-4576`
- 更早历史同样只比较 sid/cursor/revision：
  `frontend/src/stores/ai.ts:5383-5414`
- 当前运行快照和 reattach probe 只比较 sid/AbortController：
  `frontend/src/stores/ai.ts:5790-5813`、`:5883-5887`
- 确认门权威校正同样只比较 sid：
  `frontend/src/stores/ai.ts:7926-7957`

会话号并不保证跨身份唯一：

- 当前发号器是毫秒时间戳；
- 撞号重取只检查当前身份内是否已存在，归属明确是 `(tenantId,userId,sessionId)`。
  `backend/src/main/java/com/example/configmgr/ai/web/AiSessionController.java:35-52`、`:99-113`

**完整调用链与触发场景**

1. 用户 A 调用 `loadHistory → aiApi.history(A_sid)`，请求合法返回 A 的历史。
2. 响应尚未到达时切换用户 B，创建 B 会话；不同身份、不同实例在同一毫秒可合法得到相同 `sessionId`。
3. B 的 `this.sessionId` 也等于该值。
4. A 的迟到响应通过 `this.sessionId === sid` 和 `historyRequestGenerations.get(sid)` 两项检查。
5. `this.messages`、上下文、挂起卡或运行快照被 A 的数据覆盖。共享 DB/Redis 不改变这一前端竞态，跨实例同毫秒发号反而使碰撞更容易。

**影响**

跨租户/跨用户正文、工具参数、反馈摘要及挂起状态泄露；随后镜像可能把污染数据写入 B 的身份键，形成持久污染。

**修改建议**

统一捕获操作作用域：

```ts
{
  identity,
  sessionId,
  sessionEpoch,
  requestEpoch
}
```

- Map 键改为 `tenant:user:sessionId`，不能只改存储键而不改内存代次键。
- 每个 `await` 后同时核对：身份不变、sessionId 不变、sessionEpoch 不变、请求代次仍最新。
- 请求显式携带捕获身份，禁止拦截器现读新身份。
- 切换时使旧请求作废；即使 B 与 A 同 sessionId，也绝不能合并 A 的镜像、历史或运行快照。
- 可让服务端响应返回身份与 sessionId 作纵深校验，但不能以此替代前端现场代次。

**后续单点验证**

让 A/B 使用完全相同的 sessionId，分别延迟 A 的 history/context/runs-current 响应；切到 B 后释放 A 响应，B 的消息、卡片、上下文和镜像必须完全不变。

---

## H-04 P1 确定问题：赞踩提交可把旧身份消息及摘录写入新身份；本地赞踩 Map 也未隔离

**证据**

- 点击时只传 `messageId/rating/excerpt`，没有捕获 sessionId 和身份：
  `frontend/src/components/AiPanel/AiPanel.vue:4760-4776`
- store 在等待 `ensureSession()` 后使用“那时”的会话和请求头：
  `frontend/src/stores/ai.ts:10788-10799`
- `feedbackSubmit` 不支持显式身份 config：
  `frontend/src/api/ai.ts:369-372`
- 后端只校验字段形状，不校验消息是否属于 sessionId、sessionId 是否属于当前身份，就直接 upsert：
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:3014-3027`、`:3036-3061`
- 本地状态只按 `messageId`，失败时还会用旧 Map 整体覆盖新 Map：
  `frontend/src/components/AiPanel/AiPanel.vue:4754-4776`

**完整调用链与触发场景**

1. A 在某条助手消息上点赞，`rateMessage` 保存 A 的 messageId 和前 200 字摘录。
2. `submitFeedback → ensureSession` 等待所有权或发号。
3. 等待期间切换 B；随后请求使用 B 当前 sessionId 和 B 身份头，但 body 仍是 A 的 messageId/excerpt。
4. 后端不验证归属，在共享 DB 中创建 B 名下的反馈行。
5. B 在任意实例读取反馈列表都会看到 A 的摘录。

即使请求未错位，`feedbackState` 在身份切换后仍保留；当跨身份 messageId 合法重复时，B 会看到 A 的赞踩选中态。旧请求失败还可能通过整体 `feedbackState.value = before` 覆盖 B 新做的选择。

**修改建议**

- 点击时捕获 `{identity, sessionId, sessionEpoch, messageId}`；旧消息所属会话必须已经就绪，不得为了反馈另发一个新会话。
- `submitFeedback` 和 `feedbackSubmit` 接收该作用域并显式写身份头；发送前作用域失效就放弃。
- 后端必须按当前身份验证 session 和 message 的归属，最好由服务端历史生成 excerpt，而不是信任客户端正文。
- 本地键改为 `tenant:user:sessionId:messageId`；切换时清理。失败回退只修改同一个作用域键，不得整表回滚。
- 已存在的错位反馈无法可靠自动修复，应按 session/message 归属审计清理。

**后续单点验证**

延迟 `ensureSession`，A 点击后切 B：不得产生 B 名下含 A 摘录的记录；A 的失败回包不得改变 B 的按钮状态。

---

## H-05 P1 确定问题：AI 反馈页在身份切换后持续展示旧身份数据，迟到响应也无归属守卫

**证据**

- 页面只在挂载时执行一次 `load()`：
  `frontend/src/views/AiFeedbackView.vue:61-64`
- `load()` 直接 `items.value = await aiApi.feedbackList()`，没有身份捕获、请求代次、取消或切换清理：
  `frontend/src/views/AiFeedbackView.vue:20-29`
- API 使用调用时当前身份：
  `frontend/src/api/ai.ts:374-376`

**场景与影响**

- A 的反馈已经显示后切换 B：页面不 watch 身份，A 的摘录、reason、session/message 摘要会一直留在 B 界面。
- A 请求在途时切 B：A 的合法响应仍可迟到写入 `items`。
- 首次进入页面若目录/身份尚未就绪，还可能直接以无身份请求而失败。
- 数据从共享 DB 的哪个实例返回不影响结论。

**修改建议**

- 页面引入当前 AI 身份，监听 `tenant:user`，切换时同步清空 `items/error` 并递增请求代次。
- 等当前真实身份就绪后再加载；请求显式携带捕获身份。
- 响应和错误均只在身份、代次、组件存活状态一致时落地；可用 AbortController 辅助取消，但仍需逻辑代次。
- 不需要也不应通过租户目录决定本页数据归属。

**后续单点验证**

A 列表已加载、A 请求在途两种情况下切 B，页面必须立即为空或显示 B 数据，任何时刻不得残留 A 行。

---

## H-06 P1 确定问题：身份切换未作废页面 handler、路由任务上下文和异步 watcher

### 6a. 静态 handler 与视图本地任务仍属于旧身份

**证据**

- `resetIdentityScopedContext()` 只清 Pinia 字段：
  `frontend/src/stores/workspace.ts:215-250`
- 页面能力是模块级静态注册表，无身份或代次：
  `frontend/src/stores/workspace.ts:427-479`
- 导出 handler 闭包继续使用视图本地 `stepIndex/selectedDefs/taskId`：
  `frontend/src/views/ExportWizardView.vue:2480-2489`、`:2518-2538`、`:2558-2582`
- 路由中的 taskId 在身份切换时不清除，刷新还会重新读取：
  `frontend/src/views/ExportWizardView.vue:516-519`

**场景**

A 在 `?taskId=A` 的导出/导入向导中切换 B。store 摘要被清空，但页面仍显示并持有 A 的本地任务。B 的下一轮 AI 调用 `select_defs/confirm_step` 时，静态 handler 仍操作 A 的草案和任务。跨租户任务 ID 或定义编码重复时，仅比较 ID/编码无法证明归属。

### 6b. 旧身份的异步发布结果会写入新身份

**证据**

- workspace 的 watcher 只在下一次 publish 时 dispose，身份切换没有 dispose：
  `frontend/src/stores/workspace.ts:86`、`:883-897`
- `reportUiEventResult` 直接写“调用时”的 workspace 和事件总线，没有身份参数：
  `frontend/src/stores/workspace.ts:650-653`
- AI 侧 `armPublishConflictWatch` 甚至未保存返回的 dispose；回调总会执行 `reportUiEventResult`：
  `frontend/src/stores/ai.ts:12571-12590`
- 新会话重新启动订阅后，旧 watcher 的事件可被新身份订阅接收：
  `frontend/src/stores/ai.ts:11182-11185`、`:11336-11337`

**影响**

旧任务被新身份继续修改；旧发布冲突摘要进入新身份的 `recentActions`，下一轮注入模型，并出现在新身份面板。请求落到不同业务实例时，共享任务数据仍可能受到相同错误写入。

**修改建议**

- 为 registry、watcher、subscriber 统一引入显式 `AiOperationContext {identity, sessionId, sessionEpoch}`。
- 身份切换时递增工作区身份代次，注销旧 handler、dispose 全部 watcher、作废旧 subscriber。
- handler 调用前及每个 await 后核对捕获上下文，不得只读当前全局状态。
- 视图实现统一身份复位：清本地 task/draft/editor/job 状态，并移除路由中的旧 `taskId`；必要时导航到中性页。
- `reportUiEventResult` 必须接收捕获作用域，旧作用域结果只能记录到旧身份诊断面，不得写新身份时间线。

**后续单点验证**

A 的向导和发布 watcher 保持存活时切 B，再触发旧 handler/旧 onConflict：不得修改 A 或 B 的任务，不得进入 B 的 recentActions、面板或后续 chat context。

---

## H-07 P1 确定缺口：没有登出清理，且内存身份会在 `sessionStorage.clear()` 后继续存活

**证据**

- `identityCache` 被明确设计为运行时权威，storage 只是副本：
  `frontend/src/utils/ai-identity.ts:90-105`
- 因此外部登出若只清 sessionStorage，旧内存身份仍继续产生请求头。
- `writeAiIdentity(null)` 虽存在，但仓内没有生产调用方：
  `frontend/src/utils/ai-identity.ts:108-127`
- 全前端未发现 logout/auth store/401 后的 AI 身份清理；路由也没有身份切换守卫：
  `frontend/src/router/index.ts:23-67`

**场景与影响**

共享浏览器上 A 登出，宿主应用清空 storage 并展示登录页；当前 SPA 若未整页销毁，AI 仍用 A 的内存身份发请求。B 登录后可能看到 A 的会话、反馈和本地工作区。旧 SSE/定时器/watcher 也可能继续访问任意后端实例。

**修改建议**

建立唯一、同步的 `resetAiOnLogout`：

1. 递增 session/round/workspace epoch，使所有异步回包失效。
2. abort 全部连接，清定时器、pending Maps、watcher、草稿、反馈状态和视图缓存。
3. 关闭 BroadcastChannel、释放锁，清 `identityCache` 和 Pinia 身份。
4. 按 AI 键前缀清 sessionStorage/localStorage；这是本地键清理，不需要枚举租户。
5. 服务端撤销认证会话，最后整页跳转登录页，避免模块级状态残留。

401/主体变化也应进入同一复位路径。只调用 `resetAiIdentityCache()` 不够，因为它会再次从 storage 读回旧身份。

**后续单点验证**

A 保持 SSE、历史请求、发布 watcher 和未写草稿时执行真实登出，再让 B 登录：不得出现 A 的请求头、消息、反馈、任务上下文或迟到回调。

---

## H-08 P2 确定问题：SSE、发号及附件请求没有固定“操作开始时身份”

**证据**

- `SseStreamOptions` 只有 signal/reconnect 参数，没有身份：
  `frontend/src/api/sse.ts:265-281`
- `/chat` 初次 fetch 现读身份：
  `frontend/src/api/sse.ts:932-941`
- 每次 reattach 和 probe 也现读当时身份：
  `frontend/src/api/sse.ts:698-710`、`:801-810`
- `send()` 捕获了 sessionId，但没有对应身份传入 SSE：
  `frontend/src/stores/ai.ts:7005-7009`、`:7238-7242`
- `createSession()` 只依赖拦截器现读身份：
  `frontend/src/api/ai.ts:115-119`
- 附件上传只传 signal，未显式传捕获身份：
  `frontend/src/api/ai.ts:216-230`

**场景与影响**

- A 的 `ensureSession` 等待期间切 B，随后可能出现“A session body + B header”。
- A 发号请求尚未进入 axios 拦截器时切 B，可用 B 身份创建孤儿会话，A 的代次守卫再丢弃返回值。
- pre-start 停止后保留的旧传输在切身份后发生重连，可能使用 B 身份探测 A 的 run。
- 结果通常是拒绝、孤儿会话、恢复失败或错误取消；若任一后端端点未完整校验归属，则会升级成跨身份写入。跨实例重挂时同样存在。

**修改建议**

- 在 store 操作入口捕获完整作用域，经 `SseStreamOptions.identity` 显式传给 chat、probe 和每次 reconnect。
- `createSession`、附件、feedback 等 API 增加可选 `AxiosRequestConfig`，由调用方传捕获身份。
- 作用域已失效时应停止发送/重连，而不是用新身份继续旧操作。
- 兼容期可允许缺省身份仅用于旧测试；生产路径要求显式作用域。

**后续单点验证**

分别在初次 fetch 前、四次 reconnect、probe 和附件 ensureSession 等待中切身份，所有后续请求必须仍属于原作用域，或完全不发送，绝不能出现混合身份。

---

## H-09 P2 确定风险：无 Web Locks 时复制页签会共享原会话

**证据**

无锁能力直接标记 `unsupported` 并按恢复处理，不执行复制页签清场或重新发号：

`frontend/src/stores/ai.ts:11362-11384`

**场景与影响**

在不支持 Web Locks 的浏览器复制页签，sessionStorage 中身份、sessionId 和镜像均被继承。两个页签会操作同一后端会话，出现 SESSION_BUSY、相互看到对方消息及历史覆盖。这是同身份跨页签完整性问题，不是跨租户泄露；后端请求落任意实例都相同。

**修改建议**

- 不能把“无法判定”当作“原页签恢复”。
- 优先使用服务端页签实例租约：`identity + sessionId + pageId`，租约存共享 Redis，刷新可续租，复制页签发现活租约后必须发新会话。
- 如果仅做前端降级，至少使用 BroadcastChannel 握手确认活跃页签，但仍需处理刷新与复制的区分，不能只靠 localStorage 非原子标志。

**后续单点验证**

禁用 `navigator.locks` 后分别测试刷新、复制、关闭原页签和 bfcache 恢复；复制页签不得与原页签并发写同一 session。

---

## H-10 P2 风险：tenantId/userId 使用 JS Number，未拒绝超过安全整数范围的 long

**证据**

`isPositiveId` 只检查 `Number.isInteger`，没有 `Number.isSafeInteger`；随后身份被用于键和请求头：

`frontend/src/utils/ai-identity.ts:44-63`、`:130-138`

后端已明确认识 session long 超过 `2^53` 的精度问题，并对 session 使用字符串，但 tenant/user 仍走 number：

`backend/src/main/java/com/example/configmgr/ai/web/AiSessionController.java:27-32`

**场景与影响**

生产若使用 long 身份 ID，两个不同 ID 可能在 JSON 解析后舍入成同一个 Number，导致错误请求头和身份键碰撞。当前静态演示 ID 很小，所以这是接入真实目录后的风险，不是当前四行演示数据的已发生碰撞。

**修改建议**

- 最佳方案是 tenantId/userId 与 sessionId 一样，全链路使用十进制字符串并校验格式。
- 若短期保留 Number，必须在所有入口使用 `Number.isSafeInteger` fail-closed，并明确后端 ID 上限。
- BroadcastChannel 消息、目录/当前身份响应和存储迁移必须同步修改，不能只改请求头。

**后续单点验证**

使用 `9007199254740992` 与 `9007199254740993` 两个 ID，验证解析、刷新恢复、请求头、锁名和镜像键不会合并。

---

## H-11 P2 风险：若干内存/草稿台账仍只按业务 ID 或全局 toolCallId 工作

**证据**

- 前端工具在途去重仅为 `Set<toolCallId>`：
  `frontend/src/stores/ai.ts:1944-1945`、`:12124-12136`
- 停止墓碑补取消去重仅为 runId：
  `frontend/src/stores/ai.ts:759-770`
- `clearFormDraft/saveFormDraft` 会取消所有身份的同名 toolCallId 待写值：
  `frontend/src/utils/form-draft.ts:115-120`、`:249-258`
- `pruneFormDrafts` 的 `else` 会丢弃不在当前身份前缀下的全部 pending write：
  `frontend/src/utils/form-draft.ts:300-311`

**场景与影响**

A/B 合法复用 toolCallId 时，A 仍在执行可使 B 的请求被跳过；A 的晚到清理可取消 B 尚未落盘的输入。主要是跨身份功能抑制和草稿丢失，未证明直接正文泄露。浏览器内问题与后端实例无关。

**修改建议**

- 在途键统一为 `tenant:user:sessionId:runId:toolCallId`，值中放执行 token；释放时同时核对 token，防止旧执行清新执行。
- 停止去重使用完整身份+session+run。
- 草稿清理只操作调用方明确指定的完整键；`prune` 不得处理其它前缀的 pending write。
- `clientRequestId` 使用随机 UUID，现有全局去重碰撞风险可忽略，无需机械改动。

**后续单点验证**

两个身份使用相同 toolCallId/runId 并发执行和清理，双方必须各自执行，且一方 pending draft 不受另一方 prune/clear 影响。

---

## H-12 P3 风险：语言在每次 SSE 重连时重新读取，未按轮次固定

**证据**

REST、chat、probe 和 reattach 都正确携带 `Accept-Language`，但每次调用都读取当时的 `acceptLanguage()`：

- `frontend/src/api/http.ts:147-159`
- `frontend/src/api/sse.ts:706-709`、`:801-807`、`:932-939`
- 语言值本身来自白名单和 `cm.locale`，无非法头注入：
  `frontend/src/locales/index.ts:43-65`

**场景与影响**

一轮以 zh-CN 开始，断流期间用户切到 en-US，后续 reattach 又变成 en-US。模型已开始生成时不一定改变正文，但后端本地化错误、恢复语义和本轮语言来源可能不一致。是否允许“跟随当前 UI”需要明确，因此列为风险而非确定隔离缺陷。

**修改建议**

若回答语言属于轮次上下文，将 `locale` 与身份一起捕获进 `SseStreamOptions`，整轮及全部重连固定；纯 UI 错误可继续随当前语言。需要同时明确 confirm/cancel 等迟到操作的语言策略。

**后续单点验证**

首轮中文、重连前切英文，确认模型语言、后端错误和 UI 文案分别符合预定策略，而不是随机混合。

---

## AiSettingsView：全局配置边界无法判定，需产品确认权限模型

**已证事实**

- `/ai/providers`、`/ai/models`、`/ai/settings` 被明确排除在对话身份头之外：
  `frontend/src/utils/ai-identity.ts:199-227`
- 后端也将这些子树排除在身份 Filter 外：
  `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:71-76`
- 设置页可直接新增、修改、删除共享 provider/model/default，路由无角色守卫：
  `frontend/src/views/AiSettingsView.vue:461-476`、`:583-607`、`:762-779`

这不是“AI 对话数据漏带租户头”，因为代码明确把模型配置定义为全局配置。但在多租户生产环境，任何用户都能修改共享 AI 配置是否合理，单凭本领域无法判定。

建议确认：

- 若是全局管理员配置：增加独立管理员认证和角色授权，不能仅靠“有没有 tenant/user 头”。
- 若是租户配置：必须移除排除项，并把配置查询、唯一约束和缓存全部改为租户作用域。
- 不应为追求形式隔离而把真正的全局配置盲目复制到每个租户。

---

## 已证相对安全的实现

以下实现本身有正确隔离设计，但都不能抵消 H-01 的身份值不可信：

- 会话指针：`ai-session-id:<tenant>:<user>`；镜像：`ai-mirror:<tenant>:<user>:<sessionId>`；草稿：`cm.ai.form.draft.<tenant>:<user>.<toolCallId>`；锁：`ai-session-<tenant>:<user>:<sessionId>`。
  `frontend/src/utils/ai-identity.ts:141-179`
- 停止墓碑和前端工具日志的持久键包含身份、session、run、toolCall；清理也按身份+session 前缀：
  `frontend/src/stores/ai.ts:773-828`、`:1963-2042`
- BroadcastChannel 虽共用 `ai-session-ownership`，消息接收会同时比较 tenantId/userId，锁探活也按会话捕获身份：
  `frontend/src/stores/ai.ts:1314-1326`、`:1379-1388`
- confirm、frontend-tool-result、cancel、deleteHistory 的主要写路径已显式捕获旧身份，避免 axios 微任务现读新身份：
  `frontend/src/stores/ai.ts:8262-8277`、`:8416-8442`、`:10232-10236`、`:3591-3604`
- 镜像写入前检查 `identity === sessionIdentity`，空 session 不写：
  `frontend/src/stores/ai.ts:4401-4413`
- `ai-panel-expanded` 和 `cm.locale` 只是 UI 偏好，不含对话数据，可跨身份共享。
- 未发现本领域 IndexedDB 使用。
- HTTP 诊断 URL 会去掉 query/hash，降低 sessionId 等参数进入 console 的风险：
  `frontend/src/api/http.ts:58-69`

**最终结论：当前版本不能认定为租户+用户隔离安全。首要工作是去掉客户端身份自声明和全租户目录；随后必须修复异步作用域、反馈、工作区及登出清理，单纯给浏览器键追加 tenantId/userId 不足以闭环。**
