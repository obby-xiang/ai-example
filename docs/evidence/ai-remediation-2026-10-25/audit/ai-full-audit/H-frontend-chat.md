> 来源=<仓外证据>/ai-full-audit/H-frontend-chat.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# H｜前端 AI 对话面板与状态 —— 只读验收审计

- 审计对象（固定快照）：`<仓外证据>/ai-full-audit/tree`
  - 基线提交：`ae883fbae52be36e45a635ee276cf8298ac24bd2`（`ae883fb fix: 修复集群扫描与挂起续跑`）
  - 未提交补丁：`working.patch` SHA256 = `6c88b47de7e77263bea84e07e17a8dea154b83ad2bc35675519697bef8dab955`（**本机实测一致**）
  - 补丁中与本域相关的只有两处：`frontend/src/utils/parts.ts`（+12/−1，`tool_start` 帧序防御）、新增未跟踪 `frontend/tests/park-request-tool-start-order.spec.ts`
- 审计方式：只读静态审读。**未启动任何服务、未跑测试、未访问 8081/5202、未读写密钥、未派子 agent、未改动快照内任何文件**（本报告写在仓外）。
- 结论形式：`可证明正确（带条件）` / `已证实缺陷` / `具体风险` / `仅缺测试` / `未验证`。**不给出绝对保证**。

---

## 1. 覆盖清单

### 1.1 本域生产文件

| 文件 | 行数 | 审读程度 | 说明 |
|---|---|---|---|
| `frontend/src/stores/ai.ts` | 5523 | **逐行读 1–970、1202–3290、4339–4820、4862–5241、5447–5523**（≈4060 行，73%） | 会话身份/所有权/镜像/历史/恢复/发送/停止/确认门/帧处理（核心面全覆盖） |
| `frontend/src/api/sse.ts` | 752 | 全文 | POST 流 + EventSource 重挂 + 双看门狗 + 分帧器 |
| `frontend/src/api/ai.ts` | 263 | 全文 | AI 端点 client |
| `frontend/src/api/http.ts` | 217 | 全文 | axios 实例、错误归一、**无任何认证头** |
| `frontend/src/utils/parts.ts` | 871 | 全文（含未提交补丁） | 段写口 / 派生投影 / 帧→段词表 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 3700 | **逐行读 430–620、1280–1620、3095–3400、3500–3700**（≈1100 行，30%） | 门卡模板 / composer / 挂载卸载；其余为展示面取样 |
| `frontend/src/utils/form-draft.ts`、`src/composables/useCopy.ts`、`src/utils/markdown.ts` | — | 定点审读 | 草稿键口径 / 文案 / 渲染净化 |

### 1.2 明确未逐行审读的区域（不悄悄略过）

`stores/ai.ts` 未逐行读的四段（≈1460 行）：

1. `970–1202`：`frameStatusToPendingStatus` / `isFrameDecidedPendingStatus` / `isRestorablePendingEntry` / 上传区域解析 / `setNotice` 族 / `cleanupSessionLocalArtifacts`（按调用点读过头注与关键分支，未逐行）；
2. `3290–4339`：表格写预览、上传交接、UI 撤销、生成式表单提交/取消 —— **属"前端工具（HITL 第二类）"子面**，本报告只在边界处评估（多卡串行、会话/页签收口、cancelled 语义）；其内部正确性归工具域；
3. `4820–4862`：消息工厂（`recordUserMessage` / `appendAssistantMessage` / `currentAssistantMessage`）——读过头注与调用语义；
4. `5241–5447`：`applyPendingTerminal` / `applyFrontendToolTerminal` / `setBlockExpanded` / 发布冲突与作业同步 —— 只在"帧终态广播"语义上抽样。

`AiPanel.vue` 未逐行审读：模板 1–430、620–1160、1160–1280、1620–3095、3400–3500（滚动跟随 / 复制 / mermaid 视口 / 卡片标题摘要状态色 / 错误卡动作）。这些是展示层，与"身份/会话/重连/取消"判定链无交叉（只读 `ai.messages`）。

### 1.3 相邻测试（本域 = 会话身份/页签/重连/取消/恢复/段状态）

| 测试 | 例数 | 类型 | 读法 |
|---|---|---|---|
| `tab-ownership-lifecycle.spec.ts`（+`tab-ownership-fixtures.ts`） | 10 | store + FakeLockManager/FakeBroadcastChannel（jsdom） | 全文 |
| `r166b-fixes.spec.ts`（RC-4 段 + GF 值段） | 25 | store/纯函数 + mock api | 全文（RC-4 §523-676） |
| `session-purged-recovery.spec.ts` | 5 | store + mock api | 全文 |
| `session-purge-reset.spec.ts` | 4 | store + mock api | 题名 + 断言面 |
| `restore-reconcile.spec.ts` | 4 | store + mock api | 全文（含负例） |
| `restore-orphan-null-req.spec.ts` | 6 | store + mock api | 题名 + 核心断言 |
| `restore-request-emitted.spec.ts` | 8 | store + mock api | 题名 |
| `r2-midstream-refresh.spec.ts` | 5 | store + 镜像夹具 | 全文（含启动期空写闸） |
| `aie2e-refresh.spec.ts` | 12 | **store + mock api（非真浏览器）** | 前 110 行全文 + 题名全量 |
| `aie2e-stop.spec.ts` | 11 | store + mock api | 关键 3 例全文 + 题名全量 |
| `aie2e-stream.spec.ts` | 6 | store/`api/sse` + 桩帧 | 题名 |
| `aie2e-panel` / `aie2e-qc-safety` / `aie2e-refresh-forms` / `aie2e-refresh-upload-region` | 14 / 4 / 27 / 5 | 组件/store + mock | 题名 |
| `b1-suspend-disconnect.spec.ts` | 14 | store + 桩流 | 题名 + 关键断言 |
| `concurrency-matrix.spec.ts` | 4 | store/组件 + mock | 全文 |
| `parts-reducer` / `parts-double-write` / `parts-residue-cleanup` | 32 / 5 / 49 | 纯函数 / 组件 | 逐节（describe 级） |
| `park-request-tool-start-order.spec.ts`（未提交） | 4 | 纯函数（parts.ts） | 全文 |
| `sse-watchdog` / `sse-parser-boundary` / `sse-language-header` | 7 / 29 / 2 | `api/sse` + FakeEventSource | watchdog 全文 |
| `gate-matrix` / `irreversible-lifecycle` | 5 / 8 | 组件/store + mock | 题名 |
| `context-restore` / `truncated-answer*` / `rg-*` | 6 / 9 / 24 | store/组件 | 题名 |
| `empty-panel-matrix` / `ai-panel-parts-render` / `ai-panel-security-render` / `ai-panel-composer-limit` | 6 / 表驱动 / 10 / 9 | 组件（jsdom） | 题名 + security 面 |
| **真浏览器**：`e2e/13-nfezui.spec.ts` N06–N09 | 4（本域） | Playwright + 真后端 + 桩上游（**13 域专用 config，不在主切片**） | 全文 N06–N09 |
| **真浏览器**：`e2e/02-reload-stop` / `e2e/08-session` / `e2e/01-stream` | 5 / 3 / — | Playwright 主栈（127.0.0.1:18401） | 题名 |

### 1.4 本域**没有**覆盖的分支（逐条）

1. **非安全上下文（http + 非 localhost）**：`navigator.locks` / `crypto.randomUUID` 不可用时的页签判定与 id 生成（全部测试跑在 `127.0.0.1`/jsdom）。
2. **3 个及以上页签**的并行所有权矩阵（现有最多"owner + 1 duplicate"）。
3. `stop()` 在 `start` 帧之前触发（runId 仍为上一轮）——`concurrency-matrix` 的 `hangRound` 在 mock 里**同步**发 `start`，结构上测不到。
4. 页签所有权判定完成前的"可发送窗口"（无任何用例断言此时不可发）。
5. `syncGateFromAuthority` 的"条目消失 / 轮次已不存在"分支。
6. `restoreInFlightRound` 后 `reattach` 收不到归档（`/events` 404 或 FrameStore 503）时的收尾形态。
7. 镜像写盘失败（QuotaExceeded）后的降级（见 H-3）。
8. `takeOverSession` 的"探活成功但二次判据拒绝 ⇒ 还锁"断言。
9. 3 JVM + 无粘滞下 `POST /chat`(A) → `POST /confirm`(B) → `GET /events`(C) 的前端行为（`cluster-3node.yml` 是后端面，无浏览器参与）。
10. 「关闭页签即结束」文案与实际（会话恢复 / 让渡 / 服务端仍有历史）的一致性。

---

## 2. 逐链分析

> 前提按要求设定：后端 Redis Cluster + 共享 DB + ≥3 JVM + **同一对话的请求可无粘滞落到不同实例**；前端覆盖多页签并行与多用户隔离。
> 前端能观察到的跨实例契约只有三条：`GET /runs/current`、`GET /events/{runId}`（后端 `SseChatEmitter` 用 `PUBLISH ai:wake:<runId>` + 归档回放做跨实例补帧）、`POST /confirm|/frontend-tool-result`（`ConfirmGate` 落 Redis）。前端侧对应能力只有"45s 帧间看门狗 + 15s 建连看门狗 + 4 次退避重挂"。

### L1 会话身份建立与页签所有权

- **入口**：`AiPanel.onMounted`（`AiPanel.vue:3620`）→ `ai.startMirror()`（3626）→ `ai.ensureTabOwnership()`（3633，**await**）→ `if (!ai.messages.length) await ai.loadHistory()`（3638-3640）。
- **身份**：`sessionId = sessionStorage['ai-session-id']`，缺失即 `crypto.randomUUID()`（`ai.ts:637-649`；`crypto` 不可用退 `s-<base36 时间>-<8 位随机>`，202-211）；`pageId = sessionStorage['ai-tab-page-id']`（346-368）；会话锁 `ai-session-<sid>`（290）；广播通道 `ai-session-ownership`（292）；返回意向 `ai-session-return-intent`（303）。
- **共享状态**：`sessionStorage`（页签级，复制页签会继承）；Web Locks（按文档持有，**唯一裁决**）；BroadcastChannel（同源页签）；服务端会话（Redis/DB，跨实例共享）。
- **正常**：锁空闲 → `owner` + 广播 `claim`（4601-4604）；复制页签 → `clearMirrorRefs()` + `adoptFreshSession()` + `rotatePageId()` + `claimSessionOwnership`（4606-4617）；慢重载（有返回意向）→ 只降级 `duplicate`，**不清会话/不清屏**（4607-4609）。
- **异常**：无 Web Locks → `tabOwnership='unsupported'`，**同步返回 null，按"恢复"处理**（4587-4590）。本链最重要的异常口，见 **H-1**。
- **恢复**：`pagehide` 只广播 `released` + 写返回意向，**不同步放锁**；1500ms 后若文档仍活着才 `releaseSessionLock()`（596-603）。bfcache（`persisted===true`）不广播不释放；`pageshow` → `revalidateTabOwnership()`：先放锁再真探（4634-4657）。`onOwnershipMessage` 收到 `released` 时，只有"仍是 duplicate + 自己还没产生任何内容"才在 1500ms 后探活接手（4679-4707）；`takeOverSession` 以锁为最终裁决并二次判据（4718-4750）。
- **用户可见**：独立新页签 = 全新空会话；复制页签 = 静默换新空会话；原页签关闭后副本 = 静默变成原会话（历史读回）。
- **多页签并行**：3 个独立页签各锁不同 sid，互不影响（**可证明正确，条件：Web Locks 可用**）。**多用户**：见 **H-2**。

### L2 发送与首流（POST /api/ai/chat）

- **入口**：`AiPanel.send()`（3519）→ `ai.send(text)`（2481）。闸：`loading` 时静默返回 `not-sent`（2487-2493）；composer 侧 `canSubmit = ai.available && 非空 && ≤8192`（`AiPanel.vue:1589`）、`composerHardDisabled`（1580）。
- **身份/session/run**：请求体 `{sessionId, message, context}`（2582-2586）；`runId` 只来自 `start` 帧（4951-4954）；`roundEpoch += 1`（2496）；`activeRoundHostId = assistant.id`（2512）。
- **共享状态/外部调用**：`POST /api/ai/chat`（裸 fetch + `Accept-Language`，`sse.ts:489-498`）；`workspace.buildContext()` 一次性注入并清空（2563-2564）。
- **正常**：`start → message_start → delta* → tool_* → done`；`done` 封口 + `followupReady=true`；`contextUsage` 只在 `done` 刷新（5146-5186）。
- **异常**：409 `SESSION_BUSY`（回收乐观气泡 + 单条"这条消息没有发出去"）；503 池饱和/无 key/无 Redis；410 `SESSION_PURGED`（换新会话，见 L7）；网络异常 → 内联错误卡 + 单槽通知。
- **多实例影响**：`/chat` 落 A、`confirm` 落 B、`/events` 落 C —— 前端不假设粘滞（正确设计）；代价是落点漂移时只能靠看门狗（45s 静默 → 4 次退避 ≈15s）收敛。
- **用户可见**：正文逐字、工具卡按段序出现、门卡/表单出现在其段位。

### L3 断流 / 重连 / 增量续收（SSE 层）

- **入口**：`streamChat`（`sse.ts:475`）、`reattach`（577）。
- **正常**：收到 `done/error` → `state.terminal=true` → 不再等服务端关流（410-413）→ `finish(terminal=true)`。
- **异常/断流**：无终帧 → `tryReattach`（548-561）；无 runId 直接判断流；EventSource `onerror` → 关流 + 退避重挂（730-742），4 次耗尽 → `settle(false)`；建连黑洞由 15s `connectTimer`（659-674）覆盖；帧间静默 45s（634-647）。
- **终帧闸门**：`consumeFrame` 顶部 `if (state.terminal) return`（270-272）；`readStream` 每块前查 `aborted`（391-401），停止后不消费已返回的块。
- **续收锚点**：`lastSeq` = 本连接已收最大 seq 优先（710），其次调用方传入；store 按 runId 记账（`ai.ts:4909-4915`），`resumeSnapshotStream` 用 `Math.max` 抬起（2388）。
- **用户可见**：`onClose(terminal=false)` 且非挂起收流且非用户停止 → `streamInterrupted=true` + 「连接中断，结果可能不完整…」；挂起收流 → 静默（正常收尾）。
- **可证明正确（带条件）**：seq 幂等（`alreadyApplied`/`markApplied`）、单连接单轮次闸（`sse.ts:282-285`）、轮次身份守卫（`ai.ts:2514/2401/2803`）、停止后迟到帧丢弃（4877）。条件 = 后端不重发已终态 toolCallId、toolCallId 跨轮不复用（4942 的契约前提，前端无法自证）。

### L4 刷新恢复（镜像 + 历史 + 快照）

- **入口**：`loadHistory`（`ai.ts:1659`）。
- **身份**：入口捕获 `sid`，跨 await 每次写回前对账（1717、1726）；`restoreSuspendedSnapshot` 再捕获 `sid + currentAbort`（2075-2088）。
- **共享状态**：`sessionStorage['ai-mirror:<sid>']`（`writeMirror`，818-855）、后端 `/history`、`/runs/current`。
- **顺序**：镜像即时恢复（1668-1711）→ `GET /history` 对账（1713-2003）→ G3 合并镜像在途轮（1997、2020-2047）→ `restoreSuspendedSnapshot`（2002）→ 在途轮静默全量续收（2169-2202）或挂起卡重建 + 增量续收（2124-2154）。
- **异常**：`/history` 失败 → 保留镜像 + `historyLoaded=true`（1716-1721）；历史为空 → 残留挂起降级 `expired`（1734-1748）；`/runs/current` 失败 → 保持镜像形态（2078-2083）。
- **用户可见**：**无横幅、无"重挂"**（G2 口径）；在途轮"接着往下写"；挂起多卡串行逐张出现（2358-2375）。
- **可证明正确（带条件）**：启动期空态不落盘（1538-1540，`r2-midstream-refresh` 第 1 例锁住）；`findInFlightHost` 不回落上一轮（2220-2235）。条件 = 镜像未因配额丢失（H-3）。

### L5 取消 / 停止

- **入口**：`AiPanel.stop()`（3695）→ `ai.stop()`（`ai.ts:4339`）。
- **身份**：`runId = this.activeRunId`（4343）；幂等 `stopping`（4340）+ `hadRound`（4345）。
- **外部调用**：`abort()` 本地连接 + `POST /api/ai/cancel/{runId}`（4390-4398），读 `alreadyCancelled` 静默对齐。
- **本地收口顺序**：`stopped=true`（4358）→ abort → `loading=false/suspended=false` → `dropActiveForm` → `dropSheetPreview('stop')` → `clearUiUndo` → `cancelRoundParts()`（pending/running/requiresAction → cancelled + decision='cancel'，4414-4436）→ `clearGateSubmissions` → `markStoppedMessage()`（宿主编排 4447-4464）→ `writeMirrorNow()`。
- **异常**：后端不可达 → 本地仍视为已停止（4399-4400）；新会话/新轮已起 → 旧轮 settle 为 `interrupted` 而非 `cancelled`（2568-2579）。
- **用户可见**：「已停止」标记（有正文/工具段才标）+ 输入解禁 + 停止钮消失 + 不再出"继续"入口。
- **缺口**：`start` 帧前的停止会 cancel **上一轮** runId（H-5）。

### L6 park 多卡（HITL 串行/并行）

- **入口**：`confirm_request` / `suspended` / `frontend_tool_request`（4998-5122）；刷新路 `restoreSuspendedSnapshot` + `revealNextRestoredPendingCard`（2358）。
- **身份**：审批身份 = `toolCallId`；`pendingConfirms` 逐段扫描（1387-1400），面板按钮逐段传 id（`AiPanel.vue:540-600`、`3519-3530`）；`pendingCall` 投影只是"最后一个 requiresAction 段"，**不可用于多卡决策**（`parts.ts:206-215`）。
- **外部调用**：`POST /api/ai/confirm`（3108）、`POST /api/ai/frontend-tool-result`（3193）；409 `DUPLICATE_TOOL_CALL_ID` → 不写乐观裁决，置 `syncing` + 3s 后 `GET /runs/current` 校正（3120-3128、2959-2981）。
- **正常**：决策送达 → `reconnectSuspendedRun`（带 lastSeq 重连，2461-2472）→ `revealNextRestoredPendingCard` → `clearSuspendedIfNoOtherConfirms`（没有其它待决卡才撤 suspended）。
- **异常**：提交失败 → 段回 `pending` + 撤在途表（3130-3144）；409 且条目已消失 → 卡可能停在"已提交，同步中…"（H-7）。
- **未提交补丁影响**：`parts.ts:539-548` 新增"已建立的等动作卡不得被迟到 `tool_start` 复位"，修复 park 顺序（`suspended → frontend_tool_request → tool_start`）下生成式表单失去提交入口的缺陷。**该修复只在段层被单测锁定，store 层 `handleFrame` 序列无集成用例**（§3.2-4）。
- **用户可见**：一次一张（或多卡并存、逐卡独立按钮），提交后下一张自动出现。

### L7 新会话 / 410 / 清除 / 跨页签存活

- **入口**：面板「新建对话」→ `ai.resetSession()`（4768）；410 → `send()` catch（2593-2604）。
- **顺序**：`adoptFreshSession()`（同步换 id + 清屏 + 删镜像键 + 清表单/预览/撤销/折叠/发布注册表/上下文，4494-4556）→ `claimSessionOwnership(nextId)` → `POST /cancel/{oldRunId}` → `DELETE /api/ai/session/{oldSessionId}`（最后）→ 按 `clientCleanup` 精确清草稿/镜像（1175-1200）。
- **用户可见**：「新建对话」= 点击即清屏（不等后端）；旧会话删净失败只提示"不影响新对话"；410 = 换新 id + 清屏 + 原稿回输入框（`ai.notice.sessionPurged`）。
- **跨页签存活**：会话 id 在 `sessionStorage`；浏览器"恢复上次会话"会把 sessionStorage 一并还原 ⇒ 下次打开仍回到同一会话；空态文案却写「关闭页签即结束」（`src/locales/zh-CN.json:407`）。见 H-2。

### L8 错误降级与用户可见结果

- **入口**：`handleChatError`（2649-2756）、`buildErrorCard`（`utils/error-card.ts`）、`setNotice`/`setRawNotice`（1140-1174）。
- **分级**：内联错误卡（挂在出错那条助手消息上，非顶部）+ 单槽通知 + 空正文时补 `（这条消息没有发出去：…）`。
- **用户可见**：无 key / key 不可读 / 无模型 / Redis 不可用 / AI 未启用 → 各自本地化一句 + 指路"设置 → AI"；不泄露 runId / "重挂"等实现词。
- **缺口**：通知**单槽**，重连公告、断流、池饱和、反馈失败互相覆盖（H-10）。

---

## 3. 测试可信度

### 3.1 各层证据证明了什么

| 层 | 代表文件 | 证明了什么 | **不能**证明什么 |
|---|---|---|---|
| 纯函数/表驱动 | `parts-reducer`(32)、`parts-double-write`(5)、`parts-residue-cleanup`(49)、`park-request-tool-start-order`(4)、`sse-parser-boundary`(29)、`sse-watchdog`(7) | 段写口词表、投影不变量、分帧边界、看门狗时序与"settle 后零残留计时器" | 真实 fetch/EventSource 行为；store 侧帧序列（`applyFrameToParts` 单测**绕过** `handleFrame` 的 D8/P-11/归属闸） |
| store + mock api | `aie2e-refresh`(12)、`aie2e-stop`(11)、`b1-suspend-disconnect`(14)、`restore-*`(18)、`session-purged-*`(9)、`r2-midstream-refresh`(5)、`concurrency-matrix`(4)、`r166b`(25) | store 状态机与写盘/回灌**调用面** | 真实 SSE 时序、真实 HTTP 状态码、真实浏览器 storage/锁/事件 |
| 组件 + jsdom | `ai-panel-parts-render`（表驱动）、`gate-matrix`(5)、`irreversible-lifecycle`(8)、`empty-panel-matrix`(6)、`ai-panel-security-render`(10) | 渲染判据、按钮可用性、XSS 白名单 | 布局/滚动/真实焦点；`onMounted` 时序（`@/api/ai` 被 stub，`ensureTabOwnership` 在 jsdom 恒 unsupported） |
| 所有权桩 | `tab-ownership-lifecycle`(10)、`r166b` RC-4(6) | 三层握手的**账本**行为 | 浏览器"按文档持锁"、复制页签真实继承、bfcache 真实销毁/恢复 |
| 真浏览器 | `e2e/13-nfezui` N06–N09（**13 域专用 config**）、`e2e/02-reload-stop`、`e2e/08-session` | 真复制页签换新会话、真接管、真刷新续收、真停止 | 非安全上下文；3 JVM 无粘滞时 `confirm` 与 `chat` 落不同实例 |

### 3.2 假绿 / 时序水分 / 自证风险

1. **`aie2e-*` 不是端到端**：文件名叫 E2E，实际是 vitest + jsdom + `vi.mock('@/api/ai')`（`aie2e-refresh.spec.ts:30-38`）；`api.chat` 是手写假实现，连 `start` 帧都由测试自己造（`concurrency-matrix.spec.ts:61-75`）。结论只能落在"store 记账正确"，**不能当生产验证**。
2. **所有权桩不建模真实语义**：`FakeLockManager.request` 在"锁被占"时 `Promise.resolve(callback(null))` 同步回调（真实是异步派发）；也不建模"同一文档二次 ifAvailable 必然拿不到锁"（代码用 `heldLock?.name === name` 短路绕过，`ai.ts:440-443`）。故 10 例只证明账本逻辑。
3. **jsdom 下恒 `unsupported`**：除显式注入 `navigator.locks` 的用例，`aie2e-*`/`concurrency-matrix`/`ai-panel-*` 的 `onMounted` 都走 `unsupported` 同步分支 ⇒ 这些用例**从未**覆盖 duplicate/owner 的组合态。
4. **纯函数单测绕闸**：`park-request-tool-start-order` 只调 `applyFrameToParts`，不经过 `hasDecidedPendingResidue`（5002/5031）与 `suspended` 置位；G-01 的真实修复面（表单是否还能提交）只有真模型 CI 用例，快照内无 store/浏览器级回归锁。
5. **`stop()` 测试时序人为**：`hangRound` 同步发 `start` ⇒ "stop 早于 start"（H-5）结构上测不到；C8-02 的"1 次 chat / 1 次 cancel"是在 runId 已知前提下成立。
6. **`sse-watchdog` 用 FakeEventSource**：证明计时器语义，不证明真实 EventSource 在 401/404/410/代理下的行为（真实浏览器 HTTP 错误只给 `onerror`、拿不到状态码 —— H-6 依赖这一点）。
7. **无 `it.skip` 假绿**：本域未见用例级 skip（`e2e/00-harness` 的 `test.skip(MODE==='real')` 属正常分档）。
8. **数量不等于结论**：`ai-panel-parts-render` 6387 行、`parts-residue-cleanup` 49 例多为"同一写点的六残留点"枚举 —— 证明的是回归锁密度，不是链路正确性。

---

## 4. 发现清单

> severity / 确定性 / 位置 / 触发 / 用户影响 / 对应或缺失的测试。

### H-1【Important｜已证实缺陷（条件：非安全上下文）】没有 Web Locks 时，复制页签与原页签共用同一会话

- **位置**：`frontend/src/stores/ai.ts:420-427`（`lockManager()` 取 `navigator.locks`）、`:4587-4590`（无锁即 `unsupported` 并 `return null`）、`:4679-4691`（接手逻辑要求 `tabOwnership==='duplicate'`）。
- **技术事实**：Web Locks 仅存在于安全上下文（HTTPS / localhost）——[MDN: Navigator.locks](https://developer.mozilla.org/en-US/docs/Web/API/Navigator/locks)「此特性仅在安全上下文（HTTPS）中可用」。
- **本仓事实**：`vite.config.ts` 的 `server.host='0.0.0.0'`，且代码注释自认这条路：`AiPanel.vue:1103`「因 `vite.config.ts` 的 `host: '0.0.0.0'` 存在经内网 http 访问的**非安全上下文**面（`navigator.clipboard` 为 undefined）」；`AiPanel.vue:3393` 同口径。剪贴板为此保留 `execCommand` 回退，**所有权判定没有回退**。
- **触发**：以 `http://<内网IP>:端口` 打开 → 复制页签（Chrome「复制标签页」/ Firefox「复制标签页」）。
- **用户影响**：复制页签继承 `ai-session-id` 且判为 `unsupported`（按"恢复"处理）⇒ 两页签共用同一后端会话：① 两边各加载同一份历史，一边发的消息另一边不可见（无跨页签消息同步），刷新才出现；② 同时发 → 一边吃 409「这条消息没有发出去」；③ 任一边点「新建对话」→ `DELETE /api/ai/session/{sid}` 销毁共享会话 ⇒ 另一边下次发送收到 410、**整屏历史被静默清空**（2593-2604、4521-4527）。这正是 RC-4 要修的场景，在该访问面上完全未修。
- **测试**：`r166b-fixes.spec.ts:566-575` 反而把该兜底写成期望（"环境无 Web Locks ⇒ 按恢复处理，不丢现场"）；全部测试跑在 `127.0.0.1`/jsdom，**无非安全上下文用例**。
- **附**：同一上下文下 `crypto.randomUUID` 亦不可用，sessionId 退化为 `s-<时间>-<8 位随机>`（202-211）。

### H-2【Important｜已证实（架构事实）】"用户间隔离"不存在：隔离粒度是「(origin, 页签)」而非「用户」

- **位置**：`frontend/src/api/http.ts:133-147`（axios 只有 `baseURL`/`timeout`/`Accept-Language`，**无 Authorization、无身份**）；`stores/ai.ts:637-649`（sessionId 客户端自造、不与用户绑定）；`App.vue`（无登录/用户态）；后端 `ai/web/AiController.java:1251`（`/history/{sessionId}`）、`:974`（`/runs/current?sessionId=`）、`:740`（`/events/{runId}`）均只需 id；仓库挂账 `docs/任务看板.md:593`（L-30）自认「任何人拿到 sessionId 即可读该会话历史」「多用户/多实例部署前必须先补」。
- **三类真实共享面**：
  1. **同一浏览器多用户**（共用工作站/远程桌面/未锁屏）：同一页签内换人即看到前一人完整对话；关闭浏览器后"恢复上次会话"会把 `sessionStorage` 一并还原（Firefox/Chromium session restore 行为，**本机未实测**），下一位打开即回到上一会话。
  2. **同网段任意客户端**：知道 sessionId 即可 `GET /api/ai/history/{sid}` 读全文、`GET /api/ai/events/{runId}` 挂别人的轮、`POST /api/ai/cancel/{runId}` 取消别人的轮；id 会出现在反代/网关访问日志的 URL 路径里。
  3. **产品文案与事实不符**：空态小字「对话保存在当前浏览器页签中，关闭页签即结束」（`src/locales/zh-CN.json:407`）——实际服务端保存（`/history` 可读）、且"关闭页签"不等于结束（让渡/接管、会话恢复）。
- **测试**：无。本域全部用例都是单用户单/双页签模型。

### H-3【Important｜具体风险】会话镜像无体积上限、正文与投影双份存储、写失败静默

- **位置**：`stores/ai.ts:818-855`（`writeMirror`：每条消息同时写 `parts` **与** `content/reasoning/toolRuns/pendingCall`；`sessionStorage.setItem` 的 `catch {}` 完全吞错）、`:1483-1524`（深度订阅 + 300ms 节流全量 `JSON.stringify`）、`:1537-1550`。
- **事实**：镜像无任何长度上限/裁剪/淘汰；工具结果段（`result`）与历史响应原文（`toolRuns[].result`）各存一份；`runSeq` 逐轮累积、从不裁剪（同样入镜像）。
- **触发**：长会话（多轮 + 大工具结果，如 `sheet` 批量读 500 行）把镜像推到 `sessionStorage` 配额上限（量级 5MB/源，浏览器相关，**本机未实测**）⇒ 之后所有写盘抛 `QuotaExceededError` 被吞。
- **用户影响**：刷新恢复退化为"无镜像"。RUNNING 轮尚可由 `restoreInFlightRound` 全量回放救回；但 **park（挂起）轮**——`restoreSuspendedSnapshot` 只重建挂起卡、不重建正文（2124-2154），后端 ChatMemory 在轮次结束前不保存该轮助手正文 ⇒ 该轮说明文字与已执行卡整块消失，只剩一张确认卡，即 `ai.ts:1994-1997` 记录的 G3 真机症状（"整轮卡与说明文字丢失"）在配额耗尽后原样复发。
- **附加**：流式期每 300ms 对整个会话做一次 JSON 序列化（含双份），长会话下是 O(会话) 的持续主线程成本（未测量）。
- **测试**：无（无配额/超限/写失败用例）；`r2-midstream-refresh` 只锁"启动期不写空态"。

### H-4【Minor｜已证实（行为）】410 恢复静默清空整屏对话，通知不提"历史已清"

- **位置**：`stores/ai.ts:2593-2604`（410 → `adoptFreshSession()`）、`:4521-4527`（删旧镜像键 + 换 id）、`zh-CN.json` 的 `ai.notice.sessionPurged`。
- **触发**：会话被服务端销毁后再次发送（单页签 = TTL/重启/别处删除；共享会话 = 另一页签点了「新建对话」，见 H-1）。
- **影响**：整屏历史立即消失（`messages=[]`、镜像删除），只留一条讲"这条消息没发出去"的通知，**没说"你会话里的历史已经没了"**，也无挽回入口。用户视角是"对话突然清空"。
- **测试**：`session-purged-recovery.spec.ts:87-113` 把 `messages` 长度 0 当作**期望**（该行为已被批准）；缺"通知是否如实告知历史丢失"的用例。

### H-5【Minor｜已证实缺陷】`start` 帧之前点停止，会 cancel 上一轮的 runId

- **位置**：`stores/ai.ts:4343`（`const runId = this.activeRunId`）+ `:4390-4398`（`POST /ai/cancel/{runId}`）；`activeRunId` 只在 `start` 帧写入（4951-4954），`stop()` 不清它。
- **触发**：发消息后、`start` 帧到达前（首包前，通常几十~几百 ms）点「停止」。
- **影响**：① 取消请求打到**上一轮**已终态 runId（后端幂等收敛，通常无害）；② 本轮新 run 在前端**无 runId 可取消**，后端是否继续跑取决于服务端是否因写失败自杀（**本报告未证实**，属后端面）——若继续跑则 token 照常消耗，结果会在下次刷新时以"静默续收"形式冒出来。
- **测试**：无（`concurrency-matrix C8-02` 的 mock 同步发 `start`）。

### H-6【Minor｜具体风险】"静默续收"先清空宿主正文再全量回放，归档取不到时无回滚

- **位置**：`stores/ai.ts:2191-2201`（`host.parts=[{text:''}]`、`content/reasoning/toolRuns/pendingCall` 全清、`appliedSeqs=[]`，随后 `lastSeq:0` 全量回放）。
- **触发**：`/runs/current` 报 RUNNING，但随后 `GET /events/{runId}` 拿不到归档（FrameStore 503 / 快照在窗口内被清 / 代理 5xx）。
- **影响**：正文与镜像那一份**已先被抹掉**；EventSource 拿不到状态码（只有 `onerror`），4 次退避后 `settle(false)` ⇒ 界面变成"空气泡 + 连接中断，结果可能不完整"，而答案几百毫秒前还在屏幕上。无重试以外的恢复路径。
- **测试**：无（`aie2e-refresh` 的 `hangReattach` 只造"连接永挂"，不造"重连失败"）。

### H-7【Minor｜具体风险】409 幂等 + 条目已消失 ⇒ 门卡停在"已提交，同步中…"

- **位置**：`stores/ai.ts:3120-3128`（409 → `markGateSyncing` + `armGateSync`）、`:2959-2981`（3s 后拉 `/runs/current`；`entry` 缺失 ⇒ 只给 `ai.notice.decisionPending`，不收敛段）。
- **触发**：两个页签（或窗口）对同一张卡几乎同时决策，且该轮随后结束、条目被清理；同时 SSE 已断（不再有 `done` 帧兜底 `clearGateSubmissions`）。
- **影响**：该卡永久"已提交，同步中…"、双按钮禁用；`suspended` 仍为真 ⇒ composer 硬禁用。可解路径只剩「停止」或刷新（`b1-suspend-disconnect.spec.ts:273-284` 锁了"挂起时停止钮可见"，可自救）。
- **测试**：无（只有"仍 PENDING ⇒ 提示"的语义）。

### H-8【Minor｜具体风险（低概率、链式）】慢重载降级为 duplicate 后不再重认领；`pagehide` 的 1500ms 兜底会在文档仍活着时放锁

- **位置**：`stores/ai.ts:596-603`（pagehide 后 1500ms `releaseSessionLock()` 只校验 `ownedSessionId`，不校验文档是否真在卸载）、`:4634-4657`（`revalidateTabOwnership` 只在 `pageshow` 且 `ownershipHidden` 时触发）、`:4607-4609`（带回返回意向的慢重载只降级、不认领锁）。
- **触发**：原页签刷新，旧文档销毁慢于 3×150ms 探活 ⇒ 新文档被判 duplicate + 有返回意向 ⇒ 降级保屏但**不持锁**，此后无重认领入口；若此时存在一个空白副本页签，它在 1500ms 宽限后探活会拿到空闲锁并 `takeOverSession` ⇒ 两页签又共用同一会话。
- **影响**：跨页签消息不同步 + 409 + 「新建对话」互毁（同 H-1 的后果，触发面更窄）。
- **测试**：`tab-ownership-lifecycle.spec.ts:166-194` 锁了"降级不清屏"，**没有**锁"降级后不再被别人接走"，也没有"刷新慢于探活预算"的用例（桩里文档销毁是即时的）。

### H-9【Minor｜已证实（行为）】复制页签继承未提交的生成式表单草稿

- **位置**：`utils/form-draft.ts`（`sessionStorage['cm.ai.form.draft.<toolCallId>']`）、`stores/ai.ts:2340-2342`（重建卡时 `loadFormDraft(toolCallId)`）。
- **触发**：原页签有 park 中的 GF 表单（填了一半未提交）→ 复制页签 → 关闭原页签 → 副本接手该会话 → `restoreSuspendedSnapshot` 重建同一 `toolCallId` 的表单卡。
- **影响**：另一页签里出现"用户填了一半"的值；配合 H-2 则草稿跨人可见。
- **测试**：无（`gf-draft` 只测同页签刷新恢复）。待合流方案（§6）处理了"复制瞬间的继承"，但**接管路径**（`takeOverSession` → `loadHistory`）仍会读到该键。

### H-10【Minor｜已证实（设计）】单槽通知互相覆盖

- **位置**：`stores/ai.ts:1222-1230`（`notice/noticeCode/noticeArgs` 单槽）、`:1140-1174`（`setNotice/setRawNotice`）。
- **触发**：重连公告（每次重试覆盖一次）与 `error` 帧的 `turnError`、池饱和、`formSubmittedElsewhere`、反馈失败同时写入。
- **影响**：用户只看到最后一条；如"连接中断，结果可能不完整"会顶掉"这条消息没发出去"。`streamInterrupted` 另有独立标志（1255）兜住最关键一句，其余场景无兜底。
- **测试**：各用例只断言"自己那条 notice"，无覆盖式断言（结构上测不出）。

### H-11【Minor｜已证实（行为）】空态建议词绕过可用性闸

- **位置**：`AiPanel.vue:1517-1522`（`sendPreset` 只判 `ai.canSend` = `!loading`）+ `ai.ts:1441-1443`；对比 `canSubmit`（1589，要求 `ai.available`）。
- **影响**：health 报不可用（无 key/无 Redis）时点建议词仍会发 POST，得到 503 后再走一次错误降级；与"前端不留点了必然 400 的按钮"的自定口径不一致。

### H-12【Minor｜仅缺测试】`onMounted` 的 `if (!ai.messages.length)` 会跳过历史对账

- **位置**：`AiPanel.vue:3638`。
- **说明**：当前只有一个 `AiPanel` 实例（`App.vue` 常驻），生产不可达；但该短路意味着"面板重挂"（未来拆页/多面板/HMR）时不会重新对账历史与 `/runs/current`，与 `takeOverSession` 里显式 `loadHistory()`（4749）口径不一致。设计脆弱点，非现网缺陷。

### H-13【仅缺测试】多页签/多用户的真机矩阵

- 现网只有 `e2e/13-nfezui.spec.ts` 的 N06–N09 四例真浏览器页签用例，且**属 13 域专用 config**（`playwright.config.ts` 的 `STUB_MODE_IGNORE` 把 `13*-nfezui` 排除在主切片外，只在 `e2e-real.yml` 单独跑）。缺：3+ 页签、复制页签在判定窗口内发送、两页签对同一张卡同时决策、`http://` 非安全上下文整条路径。

### H-14【仅缺测试】`applyFrameToParts` 新防御的 store 级/浏览器级回归锁

- 未提交补丁只加了 `parts.ts` 纯函数 4 例（`park-request-tool-start-order.spec.ts`）；`handleFrame` 的 `suspended → frontend_tool_request → tool_start` 序列（含 `hasDecidedPendingResidue` 守卫、`openGenerativeForm` 武装、面板 `gf-submit` 实际可见）**无** store/组件级用例。真实证据只有真模型 CI 的 G-01（快照内不可复现）。

---

## 5. 可证明正确（带条件）的清单

| 结论 | 证据 | 条件 |
|---|---|---|
| 终帧之后不落任何帧；停止后不消费已返回的字节块 | `sse.ts:270-285`、`:391-401`；`ai.ts:4877-4906` | 后端不在同一连接混入异轮帧（前端另有 282-285 兜底） |
| 旧轮迟到帧不改写新轮轮次级状态 | `isCurrentRound`(2514/2401/2803)、`ownedByActiveRound`(4885)、`sealedRoundHost`(4892-4900) | `activeRoundHostId` 只在四个换轮入口变更 |
| 停止 = 一次决策（段 cancelled + decision='cancel'），不产生空气泡 | `ai.ts:4414-4436`、`4447-4464`；`aie2e-stop.spec.ts:325-418` | 宿主可解析 |
| 多卡按 `toolCallId` 独立决策、串行补卡、全部决完才撤挂起 | `ai.ts:1387-1400`、`3034-3118`、`2358-2375`；`restore-orphan-null-req.spec.ts:132-247` | 后端快照提供 `entryStatus/kind/req` |
| 刷新不丢在途轮（正文续写、不回落上一轮） | `ai.ts:2169-2235`、`1994-2047`；`r2-midstream-refresh`、`aie2e-refresh` | 镜像可写（未被配额击穿，H-3）；`/runs/current` 可用 |
| 启动期空态不覆盖已有镜像 | `ai.ts:1537-1540`；`r2-midstream-refresh.spec.ts:169-200` | —— |
| 会话销毁只清精确键，绝不清整份 storage | `ai.ts:1175-1200`；`session-purge-reset.spec.ts:56-129` | 后端回 `clientCleanup` |
| AI 正文渲染 XSS 面收敛 | `utils/markdown.ts`（markdown-it → DOMPurify 白名单 → v-html；mermaid `securityLevel:'strict'` + SVG profile）、`ai-panel-security-render.spec.ts`(10) | 白名单/`afterSanitizeAttributes` hook 未回退 |
| 跨实例 reattach 可行 | 后端 `SseChatEmitter` 的 `PUBLISH ai:wake:<runId>` + 归档回放（`run/SseChatEmitter.java:203-207`、`:51-53`）+ 前端 15s/45s 看门狗 | 唤醒总线可用；否则最多 45s 静默后判断流 |

---

## 6. 与"已批准的复制页签清空方案"的关系（**待合流，未修改**）

- 快照内**没有**该方案：`frontend/tests/duplicated-tab-clear.spec.ts` 不存在；`stores/ai.ts` 的 duplicate 分支仍是 `clearMirrorRefs()`（4613）。
- 方案实物在仓外：`<仓外证据>/tab-clear-final.diff`（+30/−7，单文件 `frontend/src/stores/ai.ts`）、`tab-clear-final-report.md`、`tab-clear-final-real-browser-probe.mjs`；实现副本在 `<验证副本>/_verify-20261025-tab-clear-final/frontend`。另：`_p2-review/AiStore.tabs-merged.ts` 与快照 **逐行相同**（仅 CRLF 差异），不是另一份实现。
- 方案内容：新增 `clearDuplicatedTabStorage()`（`sessionStorage.clear()`，失败退回 `clearMirrorRefs()`），在 `role==='duplicate' && !returning` 分支、`adoptFreshSession()` **之前**调用一次。
- **该方案不改变**：① 判定仍只依赖 Web Locks（H-1 在非安全上下文依旧成立；方案报告 §2 自认"无 Web Locks 时选择不清，这是有意的安全兜底"）；② 判定完成前的可发送窗口；③ 接管路径的草稿继承（H-9）。
- **需指挥官确认的外溢**：整份 `sessionStorage.clear()` 会连带清掉**非 AI** 的页签级业务键（如导入向导草稿）与 `ai-panel-expanded`。方案把它列为"业务页签键一律不带走"的既定口径 —— 属产品裁决，但影响面大于"AI 会话清空"，合流前建议确认是否只清 `ai-*` / `cm.ai.*` 前缀。
- 方案的 8 例单测与真浏览器探针**不在本快照**，本次无法复核其断言质量；按快照如实审计，**本报告结论不含该方案**。

---

## 7. 一句话结论（按确定性归档）

- **已证实缺陷**：H-1（非安全上下文复制页签共用会话）、H-2（无用户隔离/无鉴权，文案与事实不符）、H-4（410 静默清屏且通知不提历史）、H-5（start 前停止 cancel 错轮）、H-9（草稿跨页签继承）、H-10（单槽通知覆盖）、H-11（建议词绕过可用性闸）。
- **具体风险**：H-3（镜像无上限 + 静默失败 ⇒ park 轮正文丢失面）、H-6（先清空后回放无回滚）、H-7（门卡永久同步中）、H-8（慢重载降级后不重认领 + 页签兜底放锁）。
- **仅缺测试 / 未验证**：H-12、H-13、H-14；§1.4 的 10 条分支；§3.2 的 8 条测试水分；"关闭页签即结束"与浏览器 session restore 的真实差异（本机未实测）。
- **可证明正确（带条件）**：§5 九条；条件是表内"条件"列，**不构成绝对保证**。
