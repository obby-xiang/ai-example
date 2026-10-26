> 来源=agent-e754tnpm/output.log（2026-10-26 归档）· 全面代码审查（三套存储、刷新/挂起/409/复制页签恢复交互 N1–N12）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

# AI 会话生命周期 × 恢复交互 只读审查（<REPO_ROOT>）

**口径**：只读静态审查（源码 + 测试 + 既有审计件），未改文件、未起停服务、未跑测试/构建、未发 HTTP、未开浏览器、未读 `.env`/凭证。基线 `HEAD = 61de820`，工作树干净（`git status` 0 条），下文行号即该基线。与既有全链路审计（`docs/evidence/ai-remediation-2026-10-25/audit/ai-full-audit/`）的关系已在第 5 节逐条标注：**本报告只新增/升级它没覆盖的恢复交互项，不重复它的编号**。多用户/越权（G 域）不在范围。

---

## 1. 结论摘要

| # | 恢复场景 | 现状 | 是否要用户手工动作 | 判定 |
|---|---|---|---|---|
| S1 | 流式中刷新（RUNNING） | 镜像 + `/runs/current` + 静默续收，用户无"重挂"感 | 否 | 成立（条件：归档可读） |
| S2 | 刷新时 `/history` 或 `/runs/current` 失败 | 整条恢复链**短路**，镜像气泡永久"工作中…"，无提示、无重试入口 | **是（只能再刷新）** | **缺口 N3** |
| S3 | park 挂起中刷新 | 卡能重建、能决策；但宿主气泡仍显示"工作中…"+光标 | 否（但状态误导） | **缺口 N4** |
| S4 | 决策/表单提交后 | 成功则带 `lastSeq` 重连补帧 | 否 | 成立 |
| S5 | 断线（浏览器侧）重连耗尽 | 顶部"连接中断…可重新发送或刷新页面继续接收" | **是** | 文案依赖手工刷新（既有 FE-09 邻近） |
| S6 | 409 `SESSION_BUSY`（在跑轮还在） | 后端给了 `runId + reattach`，前端**丢弃**，只显示"这条消息没有发出去" | **是（反复重试或刷新）** | **缺口 N5**（审计只记"不进恢复"，未记"用户无路可回"） |
| S7 | 410 `SESSION_PURGED` | 换新会话 + 整屏清空 + 原稿回输入框 | **是（再点发送）** | 既有 H-4；新增"加载期无销毁信号"（N5 附） |
| S8 | 新建对话（在飞/挂起中） | 立即清屏、cancel、purge；未删净只提示 | 否 | 成立（提示口径偏服务端，见 N10） |
| S9 | 关闭页签 | 服务端继续跑；重开（会话恢复）可续 | 否 | 成立，但空态文案说"关闭页签即结束"（N12） |
| S10 | 复制页签（安全上下文） | RC-4 三层握手：清本页签 sessionStorage → 新会话 | 否 | 隔离成立；**但静默换会话无任何提示**（N6） |
| S11 | 慢重载 >1.5s / bfcache | 降级 duplicate 但保屏；可能被空白副本接走 | 否 | 既有 H-8/FE-07 |
| S12 | L1/L2/L3 与轮内压缩后刷新 | 历史变短/出现"压缩摘要"用户气泡/内部键收尾气泡 | **是（用户自己重问）** | **缺口 N1、N2、N9** |
| S13 | 跨实例无粘滞刷新/重挂 | `/runs/current`、帧归档、待决均在 Redis，结构上实例无关 | 否 | 成立（条件：归档写成功；既有 F-10） |

一句话：**"能自动重挂"这一层已经做得比多数产品好；问题集中在"重挂失败/根本不该重挂"的那些分叉——前端把后端已经给出的恢复线索丢掉、把合成消息当用户原话展示、把"记忆窗口"当成"历史"，于是用户被迫刷新或重问。**

---

## 2. 三套存储与用户可理解性（这是本轮最核心的一致性问题）

三套存储的生命周期在代码里是清楚的，在界面上是**完全不可见**的：

| 存储 | 作用 | 生命周期 | 用户可见路径 |
|---|---|---|---|
| Redis `chat:mem:*`（`AiProperties.Memory`，`application.yml:147-162`） | **给模型看**最近 40 条 / 48k token | `session-ttl` 24h 滑动 | `/history`（`AiController.java:1284-1302`）→ 前端唯一历史来源 |
| DB `ai_chat_message`（`AiChatHistoryService.java:39-44`） | 注释原文：**"本服务负责『给用户看全部』"，不设 TTL** | 删会话才物理删 | `/history/{sid}/messages`（`AiController.java:1317-1361`）——**前端从未调用** |
| 浏览器 sessionStorage 镜像 | 刷新秒开 + 在途轮正文 | 页签级 | `stores/ai.ts:1590-1603` |

**N1【P1｜契约未落地】"给用户看全部"的能力建好了，但展示面没用它**
- 证据：`AiChatHistoryService.java:41` 明写分工；前端 `api/ai.ts:65-68` 只有 `history()`，`types/ai.ts:284-288` 的 `AiHistory` 连 `total/hasMore` 都没有；`AiPanel.vue:3637-3639` 只调 `loadHistory()`；`loadHistory` 在 `ai.ts:1791-1801` 把空历史当"后端为空"。
- 后果：**同一会话刷新后只剩最近 ≤40 条**（`convergeMemory` 的 `trimKeepingToolPairs(..., maxMessages=40)`，`ResilientChatService.java:1826-1828`；`MemoryWindow` 同样按 40/48k 写回，`:2386-2410`）。现场面板里明明有 60 条，一刷新少 20 条，DB 里一条不少——用户没有任何解释，也没有"加载更早消息"。
- 新旧归因：40 条窗口是 `d7b9d54` 的既有口径；DB 完整历史是 `20c56bb`（2026-10-25）新增并声明"给用户看全部"——**新能力没有对应 UI，反而把"刷新丢历史"从"没地方存"变成"存了不给看"**。D 域已把它当"覆盖缺口"记了一句（`D-model-history.md:142`），未立编号缺陷，本条升级为 UX 缺陷。
- 最小方向（不施工）：刷新恢复改读 `/history/{sid}/messages`（倒序游标，先取最新一页，向上翻页），记忆窗口只用于模型侧；或在现有 `/history` 上补 `total>count` 时给"更早的消息"入口。

**N12【P2｜文案与事实相反】唯一解释"对话存在哪"的文案是错的**
- `AiPanel.vue:180` 空态：`ai.panel.tabOnlyNotice` = "对话保存在当前浏览器页签中，关闭页签即结束。"（`zh-CN.json:410`）。
- 事实：服务端 Redis 存 24h、DB **永久**、关闭页签服务端继续跑、复制页签/接手还会换页签。用户据此对"能不能找回、会不会被存"的判断全反。A 域已按"多用户"提过（H-2），但**本条是纯 retention 口径**，与多用户无关。
- 方向：改成如实三句（本页签显示 / 服务端保留多久 / 关闭页签后本轮仍会继续），或删掉。

**"AI 对话数据不落库"的立法与实际相反**：决策看板 10-21 的 🏛️ 条与 `AiChatHistoryService`（无 TTL、仅 purge 删除）冲突。这不是安全问题，但它决定了用户对"删除到底删了什么"的预期（见 N10）。

---

## 3. 新增/升级的实证问题

### N2【P1｜规格变化直接制造"假用户消息"】压缩摘要 + 轮内收尾指令以**用户气泡**回放

这是"上下文 L1/L2/L3 + 轮内压缩规格变化"带来的最直接 UX 回归。

- 形状变更（新）：`20c56bb` 把 L2 产物从"user + assistant 两件套"改成**单条 UserMessage**，理由写明"避免摘要以助手身份获得更高信任位"（`RollingSummarizer.java:66-71` 的类注 + `:223-232`）。产物文本 = `[上下文压缩摘要]\n<conversation_summary>…</conversation_summary>`（`SUMMARY_PREFIX`/`SUMMARY_OPEN`，`:55-59`）。
- 传播链：写回记忆（`ResilientChatService.java:1805-1808` 只过滤 SYSTEM，摘要留下）→ `/history` 把 USER 原样透出（`AiController.java:1364-1377`）→ 前端 `roleOf('USER') → 'user'`（`ai.ts:957-963`）→ 用户气泡。
- 同一类还有**两条轮内指令**（审计未覆盖）：
  - `STEP_LIMIT_WRAP_UP`（"已达到本轮工具调用步数上限（`app.ai.resilience.max-tool-steps`）…"，`SpToolCallingManager.java:161-163`，`8ba1058` 引入，`:692-697` 追加进历史）；
  - `TURN_BUDGET_WRAP_UP`（"本轮上下文已接近模型窗口上限（`app.ai.context.limit-tokens`）…"，`:165-173`，`20c56bb`/`636dd78`，`:906-914` 追加）。
  两者都是 `UserMessage`，都会在刷新后变成"用户说过的话"，且**把 `app.ai.*` 配置键直接印在用户气泡里**。
- 决定性证据：后端自己维护了 `synthetic` 判据并**在写 DB 时跳过它们**——`AiChatHistoryRecorder.java:177-185` 明确跳过 `compactedFrom` 元数据、`SUMMARY_PLACEHOLDER`、`STEP_LIMIT_PREFIX`、`TURN_BUDGET_WRAP_UP`。也就是说：**DB 通道认得出"这不是用户说的话"，展示通道认不出。** 前端 grep 无任何对应过滤（`[上下文压缩摘要]` 仅在 e2e 断言上游请求里出现）。
- 新旧归因：F-05（已有）登记了"摘要在 `/history` 变用户气泡"；**本轮新增**=①`20c56bb` 的单条形态变更让该气泡从"两件套的一半"变成"一整条带 XML 标签的用户原话"；②两条 wrap-up 指令同族未登记；③`AiChatHistoryRecorder` 的 synthetic 清单可以直接复用为前端过滤依据（说明修复成本极低）。
- 最小方向：`/history` 增透 `synthetic`/`compacted` 标记（或前端按同一份前缀常量过滤）；展示为"系统摘要块/折叠条"，不进用户气泡；wrap-up 指令根本不进展示面。**不做**内容改写（历史逐字不动）。

### N3【P2｜恢复链短路】刷新时 `/history` 或 `/runs/current` 失败 ⇒ 永久"工作中…"+ 无提示 + 无重试

- 入口：`onMounted` 只 `await loadHistory()`（`AiPanel.vue:3637-3639`）；`loadHistory` 失败分支（`ai.ts:1766-1775`）直接 `historyLoaded=true; return`，**连 `restoreSuspendedSnapshot()` 都不调**——即使 `/runs/current` 是健康的，也不会重建挂起卡、不会建立续收连接。
- `restoreSuspendedSnapshot` 自己失败时也是静默 return（`ai.ts:2131-2136`）；只有 `found=false` 才封口（`:2144-2148` → `sealLeftoverInFlight`，`:2299-2316`）。因此镜像里 `inFlight` 的宿主会保留 `streaming=true`（`:1739-1747`），面板据此常驻"工作中…"呼吸点（`AiPanel.vue:1844-1846`、`:726-728`）。
- 此时 `loading=false`、`suspended=false` ⇒ composer 可用（`AiPanel.vue:1580-1586`）⇒ 用户可以在一个"看起来正在生成"的气泡下继续输入，再撞 409（N5）。
- 另：`loadHistory` 全仓只有两个调用点（挂载、`takeOverSession`），**没有重试、没有手动"重新加载"按钮**；`/context` 失败同样静默（`ai.ts:1707-1709`），浮层永远停在"正在读取用量明细…"（`ContextPopover.vue:42`），没有任何失败态。
- 新旧归因：`0e8bc7c`（批G）删掉了"一键重挂"、改为静默续收，收益很大；代价是**静默路径失败时没有任何可见出口**。既有 H-6 只覆盖"清空宿主后归档取不到"，本条覆盖"取数就失败、连清空都没发生"的另一半。
- 最小方向：①`loadHistory` 失败仍调 `restoreSuspendedSnapshot`（两者独立）；②失败时封口镜像在途段 + 一条"离线缓存，点此重试"（复用 `probeHealth` 的"重新检测"样式）；③`loadContext` 失败给浮层一个错误态而不是无限 loading。

### N4【P2｜状态自相矛盾】park 等人期间气泡仍在"工作中…"

- 事实：`send()` 在 `suspendedClose` 时**故意不封口**（`ai.ts:2618-2623`、`:2715-2720`）；`onClose` 收到 `info.suspended` 也不报断流（`:2479-2481`）；前端 `sse.ts:539-543` 把挂起收流当正常收尾。于是 `message.streaming` 一直为 true。
- 界面：输入框 placeholder 说"挂起等待中：请先在上方处理确认/执行"（`zh-CN.json:402`），同屏 AI 气泡说"工作中…"+ 光标（`AiPanel.vue:1844-1846`）。
- 刷新后同样：`writeMirrorNow` 把 `inFlight = loading || suspended` 写进镜像（`ai.ts:1596-1602`）→ `loadHistory` 把宿主标成 streaming（`:1739-1747`）→ `rebuildPendingCard` 复用宿主且**不封口**（`:2325-2357`）→ 卡在等人，气泡仍在"工作中"。
- 新旧归因：B1 挂起即断（`2a86e1b`）留下"不封口"的语义，`20c56bb` park 上线后这段等待从"秒级/分钟级"变成**可能很久且没有线程**，矛盾被放大。既有挂账 B1 M-2（`docs/任务看板.md:580`）只覆盖"挂起中 stop() 后不封口"，**未覆盖正常等人期间**。
- 最小方向：不改 `streaming`（轮次确实没结束），只改展示判据——存在待人卡（`pendingConfirms/pendingToolCall/activeForm`）时把"工作中…"换成"等待你的确认/填写"，并收起光标；或封口正文段但保留消息未 done。二选一需产品裁决，成本都很小。

### N5【P2｜有恢复线索却不给恢复入口】409 `SESSION_BUSY` ⇒ 只能反复重试或刷新

- 后端已按 ADR-5 在 409 体里给出 `runId` + `reattach`（`AiController.java:423-444`），并区分 `unknown`（不编造地址）。
- 前端 `send()` catch 对 `SESSION_BUSY` 只做三件事：提示"这条消息没有发出去，请稍后再试"（`ai.ts:2776-2780`）、撤掉本次乐观用户气泡与空气泡（`:2693-2697`）、把原稿回输入框（`AiPanel.vue:3379-3382`）。**从不读 `error.body.reattach`，也不调 `reattachActive()`**（全仓 `reattach` 只出现在注释/后端）。
- 用户体验：在跑轮（工具执行可能数分钟）期间，用户每点一次发送都得到同一句"没发出去"，界面没有任何"回到正在进行的那一轮"的入口；只有整页刷新会走 `restoreInFlightRound` 接回来。
- 新旧归因：`0e8bc7c`（批G G5/G6，2026-10-22 用户直裁）刻意"不暴露 runId/重挂概念"并回收气泡，方向正确；但把**恢复通道**一起删了。既有 FE-09 只记"这四类码无错误卡、刷新后解释消失"，FE-17 只记"门卡 409 冻结"，均未覆盖"发送 409 无恢复路"。
- 最小方向：不显示 runId，但可给"这一轮还在继续，点此回到回答"（内部用 `body.reattach` 解析出的 runId 调 `reattachActive`），或 409 时自动静默重挂一次（气泡不重复：原稿仍在输入框，等本轮结束后用户自己决定是否再发）。取哪种需产品定，二者都不暴露术语。

### N6【P2｜复制页签"隔离正确但告知为零"】静默换新会话，且副本一说话就再也回不去

- 复制判定成立路径（安全上下文）：`ensureTabOwnership` 的 duplicate 分支 → `clearDuplicatedTabStorage()` → `adoptFreshSession()` → `rotatePageId()`（`ai.ts:4900-4911`）。**全过程没有一条 notice**，`adoptFreshSession` 还会 `clearNotice`（`:4822`）；`tabOwnership` 全仓无任何 UI 读者（grep 仅 store 内部）。
- 用户视角：从原页签复制后，AI 面板突然空白、标题栏"会话 xxxxxxxx"换了个值（`AiPanel.vue:44`），没有任何解释——很容易被理解为"对话丢了/出了 bug"。
- 更隐性的边界：副本一旦有了内容（`messages.length>0 || loading || activeRunId`）就不再接手原会话（`ai.ts:4984-4990`、`5020-5024`）。如果原页签随后关闭，原对话在这个页签**永久不可达**（只能在原页签重开前回滚）——而用户从未被告知存在这个取舍。
- 业务工作区侧：frontend 全仓除 AI 外**没有业务 sessionStorage**（`grep sessionStorage` 仅 `stores/ai.ts` / `form-draft.ts` / locale / main），所以整份 `clear()` 不会误删业务状态；复制页签按 URL 重建同一路由/任务，业务上下文"共享但无损"是正确取舍。**结论：隔离对，沟通错。**
- 最小方向：duplicate 分支落一条一次性 notice（"已在新页签开始新对话；原页签的对话不受影响"）；原页签关闭让渡时，空白副本接手前也可给一条"已接回原页签的对话"（当前 `takeOverSession` 静默换内容，`ai.ts:5034-5044`）。

### N7【P3｜跨会话残留】新建对话后工作区 chip / ui_event 回执残留，且会误用红样式

- `adoptFreshSession` 调 `resetUiSurfaces()`（`ai.ts:4831`），但该函数只清撤销点/高亮/播报/上传交接（`:4078-4086`），**不清 `contextSync`（`:1324`）与 `lastUiEventResult`（`:1326`）**，也不重置 `lastRoundOutcome`（`:1313`）。
- 面板仍渲染：`AiPanel.vue:51-53`（"工作区已同步：…（v12 · AI）"）、`:106-110`（灰条"已打开任务中心"）。
- 叠加：`noticeIsError = notice && lastRoundOutcome==='error'`（`AiPanel.vue:3057`）。若上一轮以 error 收尾后点"新建对话"，新会话里第一条**无害**通知（例如 `sessionPurgeIncomplete`）会被渲染成红色错误样式，直到下一次 `send()` 把 `lastRoundOutcome` 复位为 ok（`ai.ts:2583`）。
- 新旧归因：`34fd8f4`/`20c56bb` 扩了 `resetUiSurfaces` 的覆盖面（此前是死代码），但清单没跟上新增状态。属"新建对话现场没清干净"的尾巴。
- 方向：`adoptFreshSession` 里清 `contextSync/lastUiEventResult` 并把 `lastRoundOutcome='ok'`（与 `send()` 同口径）。

### N8【P3｜内部术语 + 死卡】陈旧确认卡点"批准"⇒ 原样打印 `runId/toolCallId`，卡回到可点

- 后端：`/confirm` 对不存在/过期的条目回 409 `UNKNOWN_TOOL_CALL`，message = **"未知或已过期的 runId/toolCallId"**（`AiController.java:1803-1810`）。
- 前端：`submitDecision` 只对 `DUPLICATE_TOOL_CALL_ID` 有分支，其余一律 `setRawNotice(error.message)`（`ai.ts:3375-3406`），并把段**改回 pending + requiresAction=true**（`:3388-3400`）——卡重新变成可点，再点还是同一句。
- 触发：页签在挂起卡保留期间被销毁/过期（410 之外的路径，如另一页签"新建对话"销毁共享会话、Redis TTL 24h 后重开、bfcache 恢复的旧卡）。
- 方向：把 `UNKNOWN_TOOL_CALL` 映射成人话（"这轮对话已失效，请重新提问"）+ 段落终态（expired/read-only），不要回 pending。这与 H-7（409 DUPLICATE 卡在"同步中"）同族但不同码。

### N9【P3｜工具卡上的机器码/内部口径】skipped 卡、资源锁、错入口文案

- 轮内预算续接：armed 后的工具一律结算为 `skipped/TOOL_BUDGET_EXHAUSTED`，卡片徽标"已跳过"（`zh-CN.json:25`），正文"未执行：本轮上下文预算已耗尽（工具轮内上下文预算）。请直接用已经获得的信息给出结论…"（`SpToolCallingManager.java:833-848`）——**这段是说给模型听的**，同时经 `part.result` 原样渲染给用户（`AiPanel.vue:448-458`）。用户看到"AI 说要做，卡却跳过"且理由像内部配置说明。
- 资源锁：`RESOURCE_LOCKED_TEXT` 以机器码开头（`SpToolCallingManager.java:1473-1478`）；错入口给的是 `KIND_MISMATCH_TEXT`——含 `kind=FRONTEND` 与 `/api/ai/frontend-tool-result` 路径（`:1480-1485`）。两者都进 `part.result/reason` 渲染。
- L1 清除占位符（"[工具结果已清除：name · ≈n tokens · toolCallId=…]"）相对可接受，但 `toolCallId` 仍是内部标识。
- 方向：卡面展示层做"给模型的话 / 给人的话"二分（后端已有双受众先例，`workspace` 的 `PAGE_LABELS` vs `PAGE_LABEL_KEYS`），或前端对 skipped 卡显示"对话太长，这一步没有执行"。

### N10【P3｜销毁口径】"旧会话未删净"只陈述、无出口；草稿残留

- `sessionPurgeIncomplete`（`zh-CN.json:135`）在 `complete=false` 或请求失败时落（`ai.ts:5085-5098`），内容是服务端口径，用户无任何可做的动作——好在服务端 `SessionPurgeRetryRunner` 会自动收敛（`SessionPurgeRetryRunner.java:24-40`，30s×10 次 + durable pending），所以**提示偏保守但不误导**；可接受，建议改写成"后台会自动清理，无需操作"。
- `cleanupSessionLocalArtifacts` 只清服务端枚举出的 `draftToolCallIds`（`ai.ts:1228-1243`）——已决卡的历史草稿键不会被枚举，会长期留在 sessionStorage。无人可见（toolCallId 不重复），属卫生问题，可在 `adoptFreshSession` 里按前缀清"本页签所有 AI 草稿"（注意不能 `clear()`，会误伤复制页签之外的状态）。

---

## 4. 重点核对项的逐项裁定

### 4.1 park（2026-10-25，`20c56bb`；`application.yml:258-267` `enabled: true`）
- **UX 回归 1（N4）**：等人期间"工作中…"（新旧语义冲突，未被 B1 M-2 覆盖）。
- **UX 回归 2（N3 的 park 变体）**：刷新后 `restoreSuspendedSnapshot` 只为卡重建、正文靠镜像；镜像丢/取数失败时"只剩一张卡 + 永久光标"。
- **已知/不新增**：批准后 `woke=true` 却无驱动方（C-park F1）、派发 15 次 ≈360s 用尽后停"已决无人执行"（`C-park-tools.md:210`、PK-07）——**这是后端恢复边界，本报告只登记"用户侧表现为画面停在已批准 + 断流提示，需要刷新"**，不与 C 域重复。

### 4.2 跨实例无粘滞
- 结构上成立：`/runs/current`（`AiController.java:1007-1090`）、`/events` 回放、待决、租约均在 Redis；前端恢复不依赖实例身份。
- 用户侧唯一可见风险来自"归档缺帧"（既有 F-10 / `RedisFrameStore` 吞错）与"决策后派发失败"（C 域）；本报告不重复。
- **N3 的 `/runs/current` 503 分支**是跨实例恢复里最容易被用户撞到的一层（Redis 抖动 → 整条恢复静默）。

### 4.3 destroy / purge
- 已登记：H-4（410 静默清屏）、H-1③（共享会话互毁）、FE-09（409/503 无痕迹）。
- **新增**：①**加载期没有任何"该会话已被销毁"的信号**——`/history` 读记忆、无守卫（`AiController.java:1284-1302`；守卫只在 `/chat`、`saveAll`、DB append 三处，见 `B-runtime-sse.md:384`），所以旧页签会**把已销毁的会话当成正常会话展示**，直到用户打完字才 410；②410 的恢复是**换会话 + 清屏 + 原稿回填 + 手工再发**（`ai.ts:2667-2681`、`2700`；`AiPanel.vue:3379-3382`），而 410 发生在建 run 之前（`AiController.java:396-410`，无任何副作用），**自动重发一次是安全的**；当前选择不做，代价是用户重打一次发送。
- 方向：`/history`（或轻量 `GET /session/{sid}/state`）回 `purged:true` → 面板加载即显示"此会话已销毁（只读/已清空）"+ 一键新会话；410 则自动在新会话重发（有幂等前提：未建 run）。

### 4.4 复制页签 sessionStorage
- 隔离正确（AI 状态 + 表单草稿 + 业务键都不带走；`localStorage` 不动）。
- 缺口：无提示（N6）、`tabOwnership` 无 UI、慢重载降级无提示（H-8 已登记行为，未登记"无提示"）。
- **非安全上下文（http + 内网 IP）完全没有隔离**（H-1 / FE-01），本报告不重复。

### 4.5 上下文 L1/L2/L3 与轮内压缩
- **L1（清工具结果）**：轮内 L1 不发光 `context` 帧（`SpToolCallingManager` 只记 `turn_budget` trace），所以**同一张工具卡"实时看是全文、刷新后是占位符"**；可接受但应知情（可在卡上加"结果已压缩"角标）。
- **L2（摘要）**：N2 / F-05。
- **L3（硬丢轮）/ 记忆窗口淘汰**：N1——刷新后旧轮消失，UI 无任何"更早内容已压缩/不可见"的说明；`context` 帧被 store 收下但不渲染（`ai.ts:5420-5433` 明写"本批不渲染"，设计稿排批④）。**在批④落地前，这三级的用户可见性等于零**；这是"规格变化带来 UX 回归"的主因。
- **轮内预算**：N9（skipped 卡 + 模型向文案进用户视图）；正确性面（armed 跨 park/实例失效）属 PK-07，不在本报告。

---

## 5. 与既有审计的分工（避免重复）

| 本报告条目 | 既有登记 | 关系 |
|---|---|---|
| N1 40 条窗口 / 完整历史无人消费 | `D-model-history.md:142`（覆盖缺口，未编号） | **升级为 UX 缺陷**（契约原文"给用户看全部"） |
| N2 摘要用户气泡 | `D-model-history.md:246` F-05 | 已有；**新增** wrap-up 两族 + `20c56bb` 单条形态变更 + `isSynthetic` 可复用 |
| N3 恢复链短路 | `H-frontend-chat.md:233` H-6（相邻） | **新增**：`/history` 失败绕过 `/runs/current`、无重试、永久光标 |
| N4 park 中"工作中" | `docs/任务看板.md:580` B1 M-2（仅 stop 路径） | **新增**：正常等人期间 |
| N5 409 无恢复入口 | `FINAL-AUDIT.md:572` FE-17（门卡 409）、FE-09（通知无痕迹）、F2 行"409 后不进恢复" | **新增**：发送 409 丢弃 `reattach`、用户无路可回 |
| N6 复制页签静默 | `H-frontend-chat.md:191` H-1（非安全上下文）、`:247` H-8（慢重载）、FE-08（草稿继承） | **新增**：安全上下文下的"零告知"与"副本一说话就不可回" |
| N7 残留 chip/红样式 | 无 | 新增 |
| N8 UNKNOWN_TOOL_CALL 原样串 + 死卡 | `H-frontend-chat.md:240` H-7（DUPLICATE 卡） | 新增（不同码） |
| N9 skipped/机器码 | 无 | 新增 |
| N10 purge 提示/草稿残留 | `H-frontend-chat.md:219` H-4（410 清屏） | 新增（提示与草稿卫生） |
| N12 "关闭页签即结束"文案 | `H-frontend-chat.md:201` H-2 第③点（多用户视角） | 新增（retention 视角） |

**明确不覆盖**：①全量流式重试策略（`sse.ts` 的 45s/4 次/1·2·4·8s 退避、建连 15s）——另一 agent；②压缩卡死根因（L2 摘要超时/熔断/阻塞）——另有报告；③多用户/越权/鉴权（G 域）；④业务数据正确性（I 域）。

---

## 6. 未证实 / 局限（不猜）

- 全部结论来自静态读码 + 既有测试断言，**未在真浏览器/真 Redis 跑过**。"两页签共用会话后消息互不可见、刷新才合并"是结构性推论（共享 `sessionId` + `SessionGate` 只挡并发轮），本机未复现。
- "Redis 24h TTL 到期后模型失忆、UI 仍显示对话"——代码路径成立（`chatMemory.get` 空 + 镜像仍在），**TTL 到期未实跑**（`ResilientChatService.java:428` 只读记忆，无 DB 回灌）。
- "`/history` 对已销毁会话返回 200 空"——据 `AiController.java:1284-1302`（无守卫）与 `B-runtime-sse.md:384`（只有三处查守卫）推断，未实跑。
- 浏览器"恢复上次会话"是否连 `sessionStorage` 一起还原、复制页签在具体浏览器的契约行为——未实测（H-2 亦标注未实测）。
- 若干行号属"当前基线 61de820"，`AiPanel.vue`/`stores/ai.ts` 体量大，未逐行通读（仅覆盖本报告涉及的控制流）。

---

## 7. 建议的最小处理顺序（供裁决，不施工）

1. **N2 + N1**（同一处修复面）：`/history` 带 synthetic 标记 / 前端复用 `AiChatHistoryRecorder.isSynthetic` 的四条判据；显示层改"系统摘要块"；随后把展示源换到 DB 完整历史（或补"更早消息"入口）。这两条直接决定"刷新后我看到的是不是我说过的话"。
2. **N3 + N5**：恢复失败要有可见出口（重试/封口/提示）；409 要有"回到进行中的回答"入口。二者都是"后端已经给了能力，前端没接"。
3. **N4 + N6**：两条纯文案/展示分支（park 中"等待你确认"、复制页签一次性告知），改动面最小、收益最直接。
4. **N8 + N9 + N7**：术语与残留清理，可合批。
5. **N10 + N12**：口径与文案，随 i18n/运营文案批处理。

以上均未修改任何文件。
