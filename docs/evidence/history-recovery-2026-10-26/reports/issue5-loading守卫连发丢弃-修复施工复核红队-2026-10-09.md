<!-- 历史取证 · 非现行基线 · 归档批次 history-recovery-2026-10-26 -->
# issue #5 · 前端 loading 守卫静默丢弃连发消息

> **归档来源**：`<LOCAL_WORK>/issue5-reports/`（本件收 4 件）｜**归档日期**：2026-10-26｜**源件日期**：2026-10-09（正文自述）
> **性质**：历史取证 / **非现行基线**。正文为源件当时时点的结论（日期见各分件），逐字保留，仅做脱敏替换与行尾空白清理，**不删段、不改结论、不补写**；旧行号锚点对应当年快照，**不代表当前已验证或已修复**。
> **归并规则**：同一批次的「侦察 / 施工 / 复核 / 红队 / 会审 / 提交」按**一个入库单元**处理，件内以 `**〔分件 N／M〕**` 分隔，各分件保留源件完整标题与正文。
> 占位符口径：`<REPO_ROOT>` = 主仓检出根（源件中亦有 `<MAIN_REPO>` 写法，本件已归一为 `<REPO_ROOT>`） · `<LOCAL_WORK>` = 仓外工作区根（源件的 `<SCRATCH>` / `<TEMP_ROOT>` 同义） · `<REPORTS_DIR>` = 本单元对应的仓外报告目录 · `<USER_HOME>` = 用户主目录 · `<KIMI_SESSIONS_ROOT>` = 会话根 · `<KIMI_HOME>` = `.kimi-code` 目录 · `<REPO-URL>` = 远端仓库地址 · `<USER>` = 用户名 / 账号名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址 · `<DRIVE>` = 其它盘符 · `<KIMI_SESSION_A…>` = 会话标识（本件内按首见序） · `<USER_EMAIL>` = 邮箱 · `<USER_FRAGMENT>` = 用户名主干片段 · `<OLD_REPO_DIR>` / `<OLD_REPO_DIR_CONCAT>` / `<OLD_REPO_DIR_UPPER>` = 旧主仓目录名 / 同名分支名（连字符分隔 / 零分隔连写 / 全大写）；非连字符的其它分隔形态记作 `<OLD_REPO_DIR⟨分隔符⟩>`（`␠` = 空格），使「不同分隔符变体」仍可分辨但字面不落库。按命名纪律，该名的**一切字面写法**（含全角、Unicode 连字符作分隔、零宽字符插入）在库内一律改写为上述占位符；需要分辨零宽构造的场合改写为码位标注（如 `<U+200B>`），原字面仅存仓外原件。
> **本单元不含**：同目录 `evidence/`、`recheck-rerun/`、`_recheck_ai_ts.diff` 为证据与复跑产物，不入。

## 分件表

| # | 分件（源件） | 源行 | 源字节 | 角色 | 结论要点 |
|---:|---|---:|---:|---|---|
| 1 | `<LOCAL_WORK>/issue5-reports/issue5-修复报告.md` | 202 | 28,940 | 只读根因 + 方案权衡 | `send()` 控制流与入口可达性实读；三候选取舍 + 推荐组合（含 issue 未提的状态收敛）；本报告阶段二未施工 |
| 2 | `<LOCAL_WORK>/issue5-reports/issue5-施工报告.md` | 150 | 23,348 | 施工 | R1–R6 + G6 落地；q1–q8 before/after + q9 补充回归；附复核勘误 O1 行数口径与 O2 动作名 |
| 3 | `<LOCAL_WORK>/issue5-reports/issue5-复核报告.md` | 192 | 25,430 | 复核 | 先证明复核前后仓库不变，再逐 hunk 核对（ai.ts 7 hunk / AiPanel.vue 1 hunk）+ 门禁复跑 + 探针亲自复跑 |
| 4 | `<LOCAL_WORK>/issue5-reports/issue5-红队确认.md` | 123 | 14,761 | 红队 | K7–K14：K8「runSeq 记账在丢弃判定之前」攻击成立定级 S4（不推翻），K9 攻击不成立维持裁决 |

---

**〔分件 1／4〕** `<LOCAL_WORK>/issue5-reports/issue5-修复报告.md` · 源 202 行 / 28,940 B · 角色：只读根因 + 方案权衡

# issue #5 修复报告（前端 loading 守卫静默丢弃连发消息）

- 严重度（issue 自评）：低 → **本报告复核建议改为「低，但定性需更正」**：症状（静默丢弃用户已输入的消息）在当前 UI 下**不可复现**；同一处守卫背后**真正可复现**的缺陷是 `loading` 与真实轮次状态失步（并发轮次 / 丢失 abort 句柄 / UI 谎报空闲），见 §1.4、§3。
- 主仓库：`<REPO_ROOT>`（= 主仓库根，Windows 绝对路径已脱敏；分支 main）
- 报告角色：修复棒子智能体（只读分析阶段）
- 本报告版本：**阶段一（只读）** —— 未施工、未提交、未跑探针
- 实读时刻：`HEAD = da3d084`（`docs: issue#3 红队 R1-R9 观察项与裁决留痕登记 M2 排期去向表`），`git status --porcelain` 为空
- 结论摘要：**`send()` 的静默 `return` 代码成立（`stores/ai.ts:575-579`），但 UI 层不可达（`canSend = !loading` 同时禁用 textarea 与发送按钮，Element Plus 把 `@keydown` 绑在内层 `<textarea>` 上 ⇒ 禁用后连 Enter 都不派发）；后端 409 SESSION_BUSY / 一键重挂链路确实完备；真正需要修的是 `loading` 的三处复位竞态（终帧提前清 → `finally` 无条件覆盖 → `stop()` 提前清），issue 建议的"notice 提示"应作为纵深防御与最小体验修补采纳，但不能替代状态收敛。**

---

## 0. 只读纪律与实读口径

| 检查项 | 只读命令 | 结果 |
| --- | --- | --- |
| HEAD | `git rev-parse --short HEAD` | `da3d084`【实测】 |
| 工作区 | `git status --porcelain` | 空（干净）【实测】 |
| 本阶段写操作 | — | 零 git 写操作、零文件修改、未建探针文件、未起服务、未提交【实测】 |

> 行号均为 `HEAD=da3d084` 实读版；另一提交官子智能体若再推进，请按新 HEAD 复核（本 issue 的锚点文件 `frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue` 不在 issue#3 的改动面内，冲突面小）。

---

## 1. 阶段一：只读根因复核

### 1.1 `send()` 全文控制流（`frontend/src/stores/ai.ts:575-634`）

```
575  async send(text: string): Promise<void> {
576    const content = text.trim()
577    if (!content || this.loading) {
578      return                       ← ① 静默返回（issue 点名；空内容与 loading 共用一条）
579    }
580    const workspace = useWorkspaceStore()
581    this.recordUserMessage(content)          ← 副作用 1：用户消息上屏
582    const assistant = this.appendAssistantMessage()  ← 副作用 2：占位气泡
583    this.loading = true              ← ② 同步置位（await 之前；同一 tick 内即生效）
584    this.stopped = false
585    this.notice = null               ← ③ 清空上一次提示（守卫命中时走不到这里）
586    this.streamInterrupted = false
587-589  busyConflict/poolSaturated 清空；currentAbort = new AbortController()
591-609  handlers（onFrame / onReconnect 写 notice / onClose 置 streamInterrupted + notice）
611    const options = { signal: currentAbort.signal }
614    const context = workspace.buildContext()  ← 契约上下文（严格 5 键）
615    workspace.clearRecentActions()            ← 动作只注入一轮
616-621  await aiApi.chat({ sessionId, message: content, context }, handlers, options)
622-623  catch → this.handleChatError(error, assistant.id)
624-633  finally → loading = false; suspended = false; currentAbort = null;
                 把未 done 的助手气泡收尾为 done
```

**复位路径逐条核对**（issue 要求"正常、异常、abort 各分支是否都复位"）【实测】：

| 出口 | 复位点 | 是否复位 `loading` | 备注 |
| --- | --- | --- | --- |
| 正常（收到 `done`） | `handleFrame` 终帧分支 `:1255-1257` **且** `send` 的 `finally:625` | 是（**两次**，见 §3-R2） | 终帧即清 ⇒ 早于 `finally` |
| 异常（开流前 4xx/5xx、网络错误） | `catch:622-623` → `handleChatError` → `finally:625` | 是 | `handleChatError` 自身**不动** `loading`（只在 `finally` 收敛） |
| abort（用户点「停止」） | `stop():948` abort → `send` 的 promise reject → `catch` → `finally:625`；`stop()` 自身也在 `:953` 清 `loading` | 是（**两次**） | `stop()` 先清 ⇒ 早于 `finally`（见 §3-R2/R3） |
| 断流（无终帧） | `finally:625`（`loading`）+ `onClose:596-608`（`streamInterrupted`/`notice`） | 是 | — |
| **守卫命中** | 无（`:578` 直接 return） | 不涉及 | 静默、无 UI 反馈 |

### 1.2 发送入口与 disabled 逻辑（issue 点名项：Enter 是否仍可达 `send()`）

| 环节 | 位置 | 事实 | 标注 |
| --- | --- | --- | --- |
| 输入框 | `frontend/src/components/AiPanel/AiPanel.vue:262-270` | `<el-input v-model="inputText" type="textarea" :disabled="!ai.canSend" @keydown.enter.exact.prevent="send" />` | 【实测】 |
| `canSend` | `stores/ai.ts:298-300` | `canSend(state) { return !state.loading }` | 【实测】 |
| 发送按钮 | `AiPanel.vue:277` | `:disabled="!canSubmit"`；`canSubmit = ai.canSend && ai.available && inputText.trim().length > 0`（`:383`） | 【实测】 |
| 面板层守卫 | `AiPanel.vue:510-517` | `if (!text || !ai.canSend) { return }` ⇒ **第二层静默返回**（同理无反馈；且不消费输入框内容） | 【实测】 |
| Enter 的落点 | `frontend/node_modules/element-plus/es/components/input/src/input.vue_vue_type_script_setup_true_lang.mjs:520-537` | `ElInput` 声明 `inheritAttrs:false`（`:87` 起）并把 `useAttrs()` 的结果 `mergeProps` 到内层 `<textarea>`，与 `disabled: inputDisabled` **同一元素** | 【实测】 |
| Element Plus 版本 | `frontend/package.json` → `"element-plus": "^2.9.3"` | 上述文件即 `node_modules` 内实装版本 | 【实测】 |

**推论【实测（代码级）】**：`loading === true` ⇒ `canSend === false` ⇒ 内层 `<textarea>` 带 `disabled` ⇒ **浏览器对 disabled 表单元素不派发键盘事件**，`@keydown.enter.exact.prevent` 根本不会触发；按钮同样带 `disabled`（点击也不触发）。⇒ **issue 描述的"第二次 `send()` 命中守卫"在正常 UI 操作下不可达**；能到达 `:577` 守卫的只有"程序化调用 `ai.send()`"或"`loading` 与 DOM 状态失步"两类情形 —— 后者是 §1.4/§3 的真缺陷，而它的失步方向恰好相反（`loading=false` 而实际在跑），此时守卫**不会**拦（消息不会丢，但会并发）。

**该"不可达"结论的边界（诚实声明）**：
- 若将来把 `:disabled` 从输入框拿掉（例如为了"挂起等待时也能输入"），守卫立刻变成用户可见的静默丢弃 ⇒ 本守卫必须与 UI 同时评审（登记为 §6.3 裁决项）。
- `AiPanel.send()` 与 `store.send()` **两层都用同一个 `loading` 做静默返回**：即便第一层放开，第二层仍会沉默（修复必须覆盖两层，见 §2）。

### 1.3 后端 409 / 重挂链路完备性（issue 自述"完备"的复核）

| 环节 | 位置 | 事实 | 标注 |
| --- | --- | --- | --- |
| 会话门两道锁 | `backend/.../ai/session/SessionGate.java`（类注释 `:25-40`） | ① 进程内 `ConcurrentHashMap` 单值门；② Redis `ai:session:lock:<sessionId>`（watchdog 续期、只释放自己那一轮）；Redis 异常降级为进程内门并把 `degraded` 写回 | 【实测】 |
| 占用判定 | 同 `#acquire` `:72-98` | 已有在跑 ⇒ 返回 `Busy(previous.runId, degraded)` | 【实测】 |
| 409 响应体 | `backend/.../ai/web/AiController.java` chat 分支 `:163-176` | `HTTP 409` + `{code:'SESSION_BUSY', message, sessionId, runId, degraded, reattach:'/api/ai/events/<runId>'}` | 【实测】 |
| 前端错误映射 | `frontend/src/api/sse.ts:325-347`（`fetch` 后 `!response.ok` → `toResponseError` → 抛 `ApiError`）+ `frontend/src/types/api.ts:51-53`（`ErrorCode.SESSION_BUSY`）、`:82-90`（`SessionBusyConflict`） | 409 在**开流前**即抛错（不会先进流再断） | 【实测】 |
| 前端处理 | `stores/ai.ts#handleChatError` `:637-683`，`:644-648` 给占位气泡写"本轮未开始…"，`:656-663` 置 `busyConflict` + `notice` | 完备 | 【实测】 |
| 一键重挂 | `AiPanel.vue:43-52`（busy 提示 + 「一键重挂收流」按钮）→ `reattach()` `:523-526` → `store.reattachActive` `:696-740`（用 `busyConflict.runId`，带 `lastSeq` 增量回放） | 完备 | 【实测】 |
| 重挂通道 | `api/sse.ts:31-41` `SSE_PATHS.events(runId, lastSeq)`（EventSource，GET） | 完备 | 【实测】 |

**复核结论**：issue 对后端的描述属实；**该链路当前"永不触发"的主因是 §1.2 的 UI 禁用（单页签内），而非守卫本身**——但注意它仍有真实触发面：**复制标签页**（Chrome"复制标签页"会**继承 sessionStorage** ⇒ 同一 `sessionId`，见 `stores/ai.ts:63-71` 的 `initSessionId()`）与 §3 的失步窗口。

### 1.4 根因复核结论（一句话）

**issue 的代码级事实成立（`stores/ai.ts:577-578` 的 `!this.loading` 静默 return），但"静默丢弃连发消息"的因果链在现 UI 下不成立 —— `canSend = !loading` 使输入框与发送按钮同时 `disabled`，Enter 连事件都到不了 `send()`；可复现的真缺陷是相反方向的 `loading/状态失步`：终帧提前清（`:1255-1257`）、`finally` 无条件覆盖（`:625-627`）、`stop()` 提前清（`:953`）三处都会把"正在跑的轮次"标记成空闲，从而放开输入、覆盖 `currentAbort`、丢掉对新流的取消能力。**

---

## 2. 修复方案权衡（issue 要求给推荐与理由）

### 2.1 三个候选

| 方案 | 实现要点 | 利 | 弊 / 风险 | 结论 |
| --- | --- | --- | --- | --- |
| **(a) notice 提示**（issue 建议） | 在 `send()` 守卫（`stores/ai.ts:577`）与面板层（`AiPanel.vue:512`）命中时写 `this.notice = '本轮进行中：本条消息未发送（可等本轮结束后重发）'` 之类；`notice` 已有 UI 落点（`AiPanel.vue:88-95`，`:376-382` 决定 `info/warning/error`；`notice` 在 `send():585` 与 `start 帧:1052` 会被清） | 改动最小（2 行）、零行为风险、消灭"静默"；**是 deny 路径的正确兜底** | 只治"看不到反馈"，不治 §1.4 的状态失步；且**当前 UI 不触发**（§1.2）⇒ 单用等于"永不生效的补丁" | ✅ **采纳（作为纵深防御 + 与 R1/R2 同提交）** |
| **(b) 客户端队列化** | 守卫命中时把 `content` 入队，在本轮 `finally`（或终帧）后自动重放 | 用户连发不丢、体验最顺 | 语义冲突：`loading` 期间包含"挂起等待（确认卡/生成式表单）"，此时自动重放会绕过用户决策（确认卡未处理就发下一句）；须新增"排队态 UI + 取消排队 + 归属哪个 runId"；与 `recordUserMessage` 的去重、`clearRecentActions()` 的一次性注入语义纠缠 | ⚠ **不推荐（作为默认行为）**；如需，只做"**保留草稿**"（不清空输入框）而非自动重放 |
| **(c) 放行，让后端 409 接管** | 去掉 `loading` 守卫，允许并发 `POST /api/ai/chat` | 复用已完备的 409 + 一键重挂 | ① 真的并发：前端会同时存在两条 SSE 流（`currentAbort` 被后者覆盖，前一条无法本地取消）；② 双份 token 与上游配额消耗；③ 模型两轮同时改同一工作区 ⇒ 与 issue#1（任务绑定竞态）/issue#3（选中集收敛）的修复正面冲突；④ `send()` 的前置副作用已发生（消息已上屏，`:581-582`），409 之后 `handleChatError` **不会重放该输入**（只把占位气泡收尾）⇒ 用户仍要手工重发，只是从"静默"变成"上屏未执行" | ❌ **不推荐作为主方案**（"不静默"的收益可由 (a) 更低成本获得；若采纳须配套"重挂后自动重发/回填输入框"） |

### 2.2 推荐组合（含 issue 未提的状态收敛）

| 编号 | 改动 | 锚点 | 目的 |
| --- | --- | --- | --- |
| **R1**（必须） | `send()` 的 `finally` 只在"仍是本轮"时复位：以 `AbortController` 身份为准 —— `const controller = new AbortController(); currentAbort = controller;` … `finally { if (currentAbort === controller) { this.loading = false; this.suspended = false; currentAbort = null } }` | `stores/ai.ts:589/624-633`（`currentAbort` 已是模块级变量，无需新增状态字段） | 治"旧轮 `finally` 覆盖新轮状态"（§3-R2/R3） |
| **R2**（必须） | 终帧复位加身份/归属判定：`handleFrame` 的 `if (isTerminalFrame(frame)) this.loading = false` 只在"该帧属于当前展示轮"时执行（`handleFrame` 已按 `assistantId` 绑定消息；可用 `frame.runId === this.activeRunId` 判定） | `stores/ai.ts:1255-1257` | 治"终帧提前清 `loading` ⇒ 输入框提前解禁 ⇒ 用户可发起第二轮，随后被旧 `finally` 抹平" |
| **R3**（建议） | `stop()` 不再抢在 `finally` 之前清 `loading`（改为只 abort + `cancel`；或同样加身份判定） | `stores/ai.ts:945-968`（`:953`） | 治"停止后立刻发送"的同型竞态 |
| **R4**（采纳 issue 的 a） | 两处守卫改为"非静默"：store 层写 `notice`（不动 `inputText`，也不触碰 `:585` 的清空），面板层同样写 `notice` 并**保留输入框内容** | `stores/ai.ts:577`、`AiPanel.vue:512` | 纵深防御；一旦将来放开 `disabled` 或走到失步窗口，用户有明确反馈 |
| **R5**（建议，同文件顺手） | ① `resetSession()` 补 `this.loading = false` 并（若在跑）abort 当前流；② `reattachActive()` 进入时 `if (currentAbort) currentAbort.abort()`（与 `resumeSnapshotStream:543-545` 同款"防双连接"） | `stores/ai.ts:971-986`、`:696-740` | §3-G5/G6 |
| **R6**（建议） | `notice` 归属：给 `send()` 的 catch/断流路径的 `notice` 写入加"本轮归属"判定（或改成 `runNotice` + 轮次 id），避免旧轮的 abort/断流提示覆盖新轮状态 | `stores/ai.ts:593-600/651-682` | §3-G7（"旧轮写 notice 覆盖新轮"） |

> 兼容性：R1-R6 均在 `frontend/src/stores/ai.ts`（+1 个 `AiPanel.vue` 守卫行），不改后端、不改契约；`notice` 字段与 UI 复用，无新增依赖。

---

## 3. 多挖缺口（issue 未提）

- **G1【实测】空内容静默返回**：`:577` 的 `!content` 与 `loading` 共用一条 return；面板层 `:512` 也静默。当前面板已在 `:511` 用 `inputText.trim()` 拦过 ⇒ 用户不可见；但 `resetSession` 后 `inputText` 未清（`stores/ai.ts:971-986` 只清会话相关状态）⇒ 若将来把 `:512` 的 trim 去掉，纯空白会被静默吞。低危，登记。
- **G2【实测（代码级）】终帧复位早于 `finally`**：`handleFrame` `:1255-1257` 在收到 `done/error` 时即刻 `loading = false`；而 `send()` 的 `await aiApi.chat(...)` 要等流关闭/收尾（`api/sse.ts:349-…` 的 `finish/tryReattach` 链路）才 resolve ⇒ **存在"UI 已解禁、本轮尚未收尾"的窗口**。窗口内用户发送 ⇒ 新一轮 `currentAbort` 被赋值 ⇒ 旧轮 `finally`（`:625-627`）无条件把 `loading/suspended/currentAbort` 复位 ⇒ **界面谎报空闲、新一轮失去本地取消句柄**。这是 issue 所述"连发"最接近的真实形态（但方向是"不该接受时接受了"，不是"静默丢弃"）。【推断：窗口长度需探针 q3 量化】
- **G3【实测（代码级）】`stop()` 抢跑**：`:948` abort → `:953` 立刻 `loading = false`（不等 reject/`finally`）。用户紧接着发送 ⇒ 与 G2 完全同型；此外旧轮 `catch → handleChatError` 会把本次 abort 的 `error.message`（形如 "The user aborted a request."）写进 `notice`（`:651-653`），**覆盖新一轮的提示**（G7）。
- **G4【实测】`reattachActive` 不 abort 既有连接**：`:696-740` 无 `if (currentAbort) currentAbort.abort()`（对照 `resumeSnapshotStream:543-545` 有）。双击「一键重挂」（`AiPanel.vue:51/83` 两个按钮）⇒ 两条 EventSource 同时写不同宿主消息 ⇒ 帧重复渲染风险；且两者 `finally` 互相覆盖 `loading/currentAbort`（同 R1 型问题）。
- **G5【实测】`resetSession()` 不复位 `loading`/不中断在跑的轮**：`:971-986` 清 `messages/activeRunId/notice/busyConflict/poolSaturated/suspended/form`，但不 abort、不清 `loading`。若在流中重置：`loading` 仍 `true`（输入区继续禁用至流结束），且后续帧仍会写进**已清空的** `messages`（`handleFrame` 按 `assistant.id` 找不到宿主 ⇒ 静默丢弃帧）⇒ "会话已重置但界面状态滞后"。
- **G6【实测】`restoreSuspendedSnapshot()` 的静默 return**：`:446-448` `if (this.loading) return` —— 页面加载时 `loading` 可能被 `probeHealth`/`loadHistory` 之外的路径置位？当前调用链（`AiPanel.onMounted:589-600` → `loadHistory` → `:409/:438`）中 `loading` 恒为 false ⇒ 该守卫当前是死分支；登记。
- **G7【实测】`notice` 是单槽位**：至少 6 处写入（`send` 的 `onReconnect:594`、`onClose:600`、`handleChatError:652/659/667/673/677/681`、`reattachActive:700/707/716/722/736`、`stop:964/966`、`handleFrame:1223/1236/1247`、`runFrontendTool:820/1355`），彼此无条件覆盖 ⇒ 旧轮的迟到提示会覆盖新轮状态（G2/G3 的次生面）。R6 处理。
- **G8【实测】`canSend` 不含 `available`**：`:298-300` 只看 `loading`；而按钮的 `canSubmit`（`AiPanel.vue:383`）额外要求 `available`。⇒ AI 不可用（无 key/无 Redis）时输入框仍可输入、Enter 仍可触发 `send()`（走 `503` 路径，`:672-679` 有提示）—— 这是**有反馈**的路径，不算静默；但若采纳 R4/R6，需保持该路径文案优先级不被 `notice` 覆盖。登记。
- **G9【推断，需实证】跨页签同会话**：`initSessionId()` 用 `sessionStorage`（`stores/ai.ts:63-71`）；Chrome「复制标签页」会复制 sessionStorage ⇒ 两个页签同 `sessionId` ⇒ 真正的并发会话 ⇒ **409 + 一键重挂是这类场景的正式解法**（可作为 §4 q5 的正当性依据，也说明该链路不是死代码）。

---

## 4. 探针矩阵（阶段二执行；**本次未运行**）

**框架现状与桩需求（关键差异）**：issue#2/#3 的探针（`<LOCAL_WORK>/issue2-reports/evidence/probe-src.ts`）**只把 axios 适配器换成假后端**（`probe-src.ts:198` `http.defaults.adapter = …`），而 `POST /api/ai/chat` 走的是 **`fetch` + `ReadableStream`**（`api/sse.ts:325-347`）与 **`EventSource`**（reattach 通道），**均不经过 axios** ⇒ 本 issue 必须**扩展桩层**：

1. **`window.fetch` 桩**（覆盖 `SSE_PATHS.chat = '/api/ai/chat'`）：
   - 模式 A（正常）：返回 `Response`（`status 200`，`body` 为受控 `ReadableStream`），可**逐帧定时投喂** `start` / `message_start` / `delta` / `done`（帧格式照 `types/sse.ts`），并支持"**保持流不关闭**"（用于把 `loading` 钉在 true，观测守卫/禁用态）；
   - 模式 B（409）：返回 `status 409` + JSON `{code:'SESSION_BUSY', message, sessionId, runId, degraded, reattach}`（照 `AiController:163-176`）；
   - 模式 C（503）：`{code:'SUSPEND_POOL_SATURATED', …}`（回归用，非必需）；
   - 需**记录调用次数与请求体**（证明"第二次请求到底有没有发出"）。
2. **`window.EventSource` 桩**（覆盖 `/api/ai/events/<runId>`）：可控回放帧 + 记录**实例数**（用于 G4 双连接取证）。
3. **真实 `AiPanel` + 真实 pinia `ai` store**（当前 issue2/3 探针只挂视图，不含面板）⇒ 需 `app.use(ElementPlus)` 并挂载 `AiPanel`（或其宿主 `App.vue`），以便观测 `disabled` 属性、按钮可用性、`el-alert`（`notice`）文本。
4. **观测面**：`ai.loading / suspended / notice / busyConflict / stopped / streamInterrupted / activeRunId / messages（含 assistant.done）`、DOM（textarea/发送按钮的 `disabled`、提示 Alert 文案）、`window.__fetchCalls`、`window.__esInstances`。
5. 不含后端（纯前端桩）⇒ **不需要起 Java 服务**；若要"409 真值"可用 §4.3 的 503/409 桩等价替代，后端链路的权威性由现有 `SessionGate`/`AiController` 用例覆盖（本次只读复核见 §1.3）。

| 场景 | 步骤 | before 期望（现状） | after 期望（R1-R6 后） |
| --- | --- | --- | --- |
| **q1 守卫可达性（正名证据）** | ① 在 textarea 输入文本后**真实按 Enter** 触发第一轮（流保持打开）；② 立即读 DOM：textarea/按钮 `disabled`；③ 再真实按 Enter；④ 再**程序化**调 `ai.send('第二条')` | ② `disabled=true`；③ **无第二次 HTTP**（事件压根不到）；④ 命中 `:577` **静默**（`notice` 不变、`inputText` 不变、无第二条消息上屏） | ④ 命中后写 `notice`（R4）且文案可读；② 仍禁用（不改变 UI 语义） |
| **q2 空内容守卫** | 程序化 `ai.send('   ')` | 静默 return（无消息、无 notice） | 建议同样给 notice（低优先）或明确保持静默（裁决 §6.2） |
| **q3 终帧提前清 × finally 覆盖（G2 主证据）** | 流投喂到 `done`（**保持连接不关闭**）→ 立刻 `ai.send('第二条')`（此时 `loading` 已被 `:1255` 清为 false，UI 允许）→ 等旧轮 promise 收尾 | 第二条 HTTP 已发出；随后 `loading=false`、`currentAbort===null`（旧轮 `finally` 覆盖）⇒ 断言"新轮在跑但 UI 显示空闲、`停止` 按钮消失、无法本地取消新轮" | 新轮期间 `loading` 恒 `true`（旧轮 `finally` 不覆盖），`currentAbort` 指向新轮；`停止` 可取消新轮 |
| **q4 `stop()` 抢跑（G3）** | 流打开 → `ai.stop()` → **立刻** `ai.send('第二条')` → 观测旧轮 `catch` 写入的 `notice` | 新轮被旧轮 `finally`/异常提示覆盖（`notice` 变成 abort 文案；`loading` 被清） | 新轮状态不被覆盖；abort 提示不污染新轮（R3/R6） |
| **q5 409 一键重挂（链路回归）** | 第一条流打开（占门）；另开一条 `send` 命中**模式 B（409）** → 断言 `notice`/`busyConflict`/Alert 出现 → 点「一键重挂收流」→ EventSource 桩回放 `done` | 409 文案 + 重挂按钮出现；重挂后 `loading` 收敛 false、`busyConflict` 清空 | 行为不变（本修复不得回归该链路）；若采纳 (c) 放行方案，本场景是必须保持绿的前置约束 |
| **q6 `reattachActive` 连点（G4）** | 连续两次点「一键重挂」 | EventSource 实例数 = **2**（旧连接未 abort）⇒ 双份帧写入不同宿主消息 | 实例数 = 1（旧连接被 abort） |
| **q7 `resetSession` 期间在跑（G5）** | 流打开 → `ai.resetSession()` → 观测 `loading` 与后续帧 | `loading` 仍 true（输入禁用），后续帧因宿主消息不存在而静默丢弃 | `loading` 复位且当前流被 abort；无孤儿帧 |
| **q8 挂起等待期输入（G8/裁决项）** | 投喂 `suspended`（CONFIRM 卡）→ 断言输入框 `disabled`；再投喂 `done` | 挂起期禁用（无法输入下一句，直到处理确认卡） | 保持禁用（若裁决要"可输入+排队"，本行改为断言排队 UI） |

每个场景产出 `probe-<phase>-<scenario>.json`（`records`：`ai` 状态快照 / DOM 关键属性（disabled）/ `fetch` 调用序列与请求体 / EventSource 实例数 / Alert 文案 / 消息列表摘要）+ 同名 `.png`；命名 `probe-before-*` / `probe-after-*`，证据落 `<LOCAL_WORK>/issue5-reports/evidence/`。

---

## 5. 阶段二验证计划（计划，未执行）

1. `frontend`: `yarn typecheck` 0 error、`yarn build`（`vue-tsc --noEmit && node scripts/build.mjs`）绿。
2. 探针 q1-q8 before/after 两轮真实浏览器跑通（q1 为"正名"证据、q3/q4 为修复主证据、q5 为不得回归的约束）；产物落 `<LOCAL_WORK>/issue5-reports/evidence/`。
3. 后端不受影响（本 issue 不改后端）；如提交官要求，可只跑 `backend` 的 AI 相关用例做一次"零回归"抽样（非必需）。
4. 收尾：删除仓库内探针文件与桩扩展（`__issue5_*`）后 `git status --porcelain` 仅剩预期修改（`frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue`）；**零 git 写操作**，保持未提交交由提交官。
5. 前端无单测框架（`frontend/package.json` 仅 `dev/build/typecheck/preview`）⇒ 不新建测试框架、不引新依赖【实测】。

---

## 6. 【待裁决】清单

1. **【待裁决：issue 定性更正】** 症状"静默丢弃连发消息"在现 UI 下不可达（§1.2）；建议把 issue 改写为"`loading` 与真实轮次失步（终帧提前清 / `finally` 无条件覆盖 / `stop()` 抢跑）导致并发轮次、丢失 abort 句柄、UI 谎报空闲"，并保留"守卫静默"作为附带项。是否照此变更 issue 文本。
2. **【待裁决：修复范围】** 是否只做 (a) notice（最小、纯体验），还是采纳 §2.2 的 R1-R6（含状态收敛）。**建议至少 R1+R2+R4**（否则 (a) 在现 UI 下等于永不生效）。
3. **【待裁决：是否放开挂起等待期的输入禁用】** 当前 `canSend = !loading`（挂起等待中同样禁用输入）。若要支持"等确认卡时也能先打下一条"，必须同时实现 (b) 的排队/草稿语义 —— 这会直接决定守卫的可达性（§1.2 边界）。是否纳入本 issue。
4. **【待裁决：跨页签同会话（G9）】** 是否在前端为"复制标签页"场景引入更细的会话隔离（如把 `sessionId` 与 `performance.navigation`/`window.name` 关联），或明确"跨页签 409 + 一键重挂"就是设计解法（若后者，请在文档登记）。
5. **【待裁决：`notice` 是否升级为轮次级对象】** R6 需要"提示归属轮次"；升格会改动 `AiPanel` 的 Alert 绑定与既有 e2e 文案断言面（当前仅前端展示）。是否允许。
6. **【待裁决：R5 顺手项（G4/G5/G6）是否同提交】** 三处都在同一文件、风险低、与主修复同型；若提交官要求"最小 diff"，可剥离为独立 commit 或登记遗留。

---

## 7. 证据与产物

- 本报告：`<LOCAL_WORK>/issue5-reports/issue5-修复报告.md`
- 探针证据目录（阶段二产出，目录待建）：`<LOCAL_WORK>/issue5-reports/evidence/`
- 复用模板与扩展点：`<LOCAL_WORK>/issue2-reports/evidence/probe-run.mjs`、`probe-src.ts`（`:198` axios 适配器桩；本 issue 需另加 `fetch`/`EventSource` 桩）、`probe.html`
- 只读留痕：`git rev-parse --short HEAD` → `da3d084`；`git status --porcelain` → 空
- 关键实读锚点：`stores/ai.ts:575-634`（`send`）、`:298-300`（`canSend`）、`:291-296`（`runStateLabel`）、`:637-683`（`handleChatError`）、`:696-740`（`reattachActive`）、`:945-968`（`stop`）、`:971-986`（`resetSession`）、`:1035-1258`（`handleFrame`，`:1255-1257` 终帧复位）、`:446-448`（`restoreSuspendedSnapshot` 守卫）、`:542-571`（`resumeSnapshotStream`，`:543-545` 防双连接）、`:63-71`（`initSessionId`/sessionStorage）；`components/AiPanel/AiPanel.vue:43-52/88-95/262-270/277/383/510-517/589-600`；`api/sse.ts:31-41/325-347`；`types/api.ts:51-53/82-90`；`node_modules/element-plus/es/components/input/src/input.vue_vue_type_script_setup_true_lang.mjs:87/520-537`；后端 `SessionGate.java:25-40/72-98`、`AiController.java:163-176`、`FrontendToolResultRequest.java:14-15`

## 8. 断言标注汇总

- 【实测】`send()` 全文与复位路径矩阵；两处静默守卫；`canSend = !loading` 与输入框/按钮 `disabled`；Element Plus 把 `@keydown` 与 `disabled` 绑在同一内层 `<textarea>`（含版本与文件行号）；409 链路（SessionGate 两道锁 → 409 体 → `ApiError` → `handleChatError` → `busyConflict` → 一键重挂）；`handleFrame:1255-1257` 终帧复位；`stop():953` 提前复位；`reattachActive` 无 abort 旧连接；`resetSession` 不清 `loading`；`notice` 的多写入点；`FrontendToolResultRequest` 无 context 字段（与 issue#4 的冻结机制无关）；探针框架只桩 axios（`fetch`/`EventSource` 未桩）；前端无单测框架。
- 【推断（代码级）】"Enter 在 `disabled` 下不可达"的浏览器行为结论（disabled 表单元素不派发键盘事件 —— 标准行为，但以真实浏览器探针 q1 复核为准）；G2/G3 竞态窗口的实际宽度与触发概率。
- 【推断】采纳 (c) 放行后的真实并发后果（双流、双份 token、与 issue#1/#3 冲突）；(b) 队列化与挂起语义的冲突表现。
- 【旁证】issue 自述的 `:577-578` 与实际代码逐字一致；issue 对后端 409/reattach"完备"的描述与实读一致。
- 【假设】① 修复不得改变"挂起等待期禁用输入"的既定语义（若裁决改成允许输入，守卫可达性结论随之改变）；② `notice` 继续作为单槽位提示使用（R6 未获批时）。


---

**〔分件 2／4〕** `<LOCAL_WORK>/issue5-reports/issue5-施工报告.md` · 源 150 行 / 23,348 B · 角色：施工

# issue #5 施工报告（loading 守卫非静默 + 轮次状态收敛 R1-R6/G6）

- 主仓库：`<REPO_ROOT>`（= 主仓库根，分支 main，`HEAD = da3d084`，目录名按命名纪律脱敏）
- 本报告角色：**施工棒子智能体**（阶段二：施工 + 真实浏览器探针 + 证据）
- 依据：`<LOCAL_WORK>/issue5-reports/issue5-修复报告.md`（阶段一只读报告，含可达性反证 / abc 权衡 / R1-R6 修法表 / q1-q8 探针矩阵）
- 施工范围（裁决既定，未自行改道）：`frontend/src/stores/ai.ts` + `frontend/src/components/AiPanel/AiPanel.vue`
- 纪律：**零 git 写操作**（未 add/commit/push/branch/stash），工作区保持未提交；探针文件用后已从仓库删除
- 结论摘要：R1-R6 + G6 全量落地并按裁决不做 4 项；`yarn typecheck` 与 `yarn build` 均绿（修复后复跑）；q1-q8 全部真实浏览器探针跑通（q1/q3/q4/q6/q7 双相位对照，q5/q8 after 相位 + 不回归断言，另加 after-only q9 覆盖本轮改动面里的续收路径）；**最关键的实测发现：终帧提前清 `loading` 的窗口只有 1 个微任务 tick（用户不可达），而 `stop()` 抢跑的窗口是"同一 tick 内"，两者都被"以 AbortController 身份判定"的收尾彻底关掉。**

---

## 1. 改动文件清单（逐文件 + 锚点）

| 文件 | 位置（修复后行号） | 改动 | 对应裁决 |
| --- | --- | --- | --- |
| `frontend/src/stores/ai.ts` | `:33-40` 模块级新增 `let activeRoundHostId` | 轮次身份判据（宿主助手消息 id），与 `currentAbort` 成对使用 | R2 |
| 同上 | `noticeBusySend()`（公开 action，`ai.ts:597-599`） | 守卫命中的统一提示（面板层/store 层共用文案） | R4 |
| 同上 | `#send`（`:602-690`）：守卫分支 `:604-613`、`controller/isCurrentRound` `:625-627`、`onReconnect`/`onClose` 归属 `:630-641`、`options.signal` `:648`、`catch` 归属 `:666-668`、`finally` 身份判定 `:670-678` | ①空内容静默+loading 写 notice；②本轮身份以 controller 为准；③旧轮迟到提示不写；④finally 仅"仍是本轮"才复位 | R1/R4/R6 |
| 同上 | `#handleFrame`（`:1117-1367`）：归属判定 `:1119-1135`、旧轮帧整体丢弃 `:1145-1156`、终帧复位 `:1364-1366` | 非本轮帧不写任何轮次级状态；宿主消失且非本轮 ⇒ 整帧丢弃（防孤儿消息） | R2/R6/G5 |
| 同上 | `#reattachActive`（`:747-812`）：入口 `previous`/换身份后中止 `:754-770`、`finally` 身份判定 `:805-812` | 连点/重复重挂只留一条活连接，旧连接收尾不复位新连接状态 | R5②/R1 同型 |
| 同上 | `#resumeSnapshotStream`（`:547-590`）：身份 + `previous?.abort()` `:548-559`、`finally` 身份判定 `:583-589` | 续收路径同款"先换身份再中止旧连接" | R6/R1 同型 |
| 同上 | `#resetSession`（`:1045-1069`）：`:1045-1054` | 先 abort 在跑轮次 + `loading=false` + 清轮次身份 | R5① |
| 同上 | `#stop`（`:1017-1042`）：`:1023-1028` | 不再抢清 `loading`（复位交给本轮收尾） | R3 |
| 同上 | `#restoreSuspendedSnapshot`（`:454`） | 删除死守卫 `if (this.loading) return` | G6 |
| `frontend/src/components/AiPanel/AiPanel.vue` | `#send`（`:510-524`）：`:512-522` | 空内容静默；`!ai.canSend` 时写 notice 且**保留输入框内容** | R4 |

- 行数口径（`git diff HEAD --numstat`）：`frontend/src/stores/ai.ts` **+135/-26**、`frontend/src/components/AiPanel/AiPanel.vue` **+9/-1**；`--stat` 对 ai.ts 显示的 `161` 是改动行合计（= 135+26），不是插入行数（见 §7.1 复核勘误 O1）。
- 未改：`canSend` 语义（`= !loading` 不变）、面板层 `inputText.value = ''` 清空逻辑（`AiPanel.vue:523`，仍在守卫之后）、后端、契约、`notice` 数据结构。
- 工作区另有 7 个文件被**并行任务**改动（`backend/**` 4 个 + `frontend/src/stores/workspace.ts`、`frontend/src/types/workspace.ts`、`frontend/src/utils/frontend-tools.ts` + 1 个新测试文件）——本棒未触碰，`git status` 可见，属在途任务。

---

## 2. R1-R6 + G6 落实情况（逐条）

| 项 | 落实 | 说明与证据 |
| --- | --- | --- |
| **R1** finally 仅"仍是本轮"才复位 | ✅ `send():625-627/670-678` | `const controller = new AbortController(); currentAbort = controller` + `isCurrentRound = () => currentAbort === controller`；`finally` 内 4 个轮次级字段（`loading/suspended/currentAbort/activeRoundHostId`）全部置于该判定之下，气泡收尾仍无条件执行。【实测】q3：旧轮 `finally` 不再抹平新轮（`loading` 保持 true、`停止` 按钮在、`stop()` 能 abort 新流）；【实测】q4：同 tick"停止+发送"不再产生孤儿流。 |
| **R2** 终帧复位加归属判定 | ✅ `handleFrame():1119-1135/1145-1156/1364-1366` | 判据用**宿主消息 id**（`assistantId === activeRoundHostId`）而非 `runId === activeRunId`：新轮 start 帧到达前 `activeRunId` 仍是上一轮的值，而宿主 id 在 `send`/`reattachActive`/续收入口即已就位（`handleFrame` 的 3 个调用点全部传入本文件设置的宿主 id）。非本轮帧在 switch 之前整体 `return`，连 `loading/activeRunId/suspended/notice/activeForm` 都不写。【实测】q7 迟到帧注入不再生成孤儿消息（before: 1 条孤儿 → after: 0 条）。**诚实说明**：在 q3/q4 的可构造路径上，真正生效的是 R1（终帧先行、旧轮收尾后到）；R2 覆盖的是"旧轮终帧迟于新轮启动"的镜像顺序，属纵深防御。 |
| **R3** `stop()` 不抢跑 | ✅ `stop():1023-1028` | 采取裁决给出的第一种形态：只 abort + `aiApi.cancel`，`loading` 复位交给本轮 `finally`。【实测】q4 同 tick：before 第二条已被接受（`fetchCalls 1→2`）→ after 被守卫挡住并写 notice（`fetchCalls` 不变）；q1 收尾：`stop()` 后 200ms 内 `loading=false`、`notice='已请求取消本轮对话'`（未出现"停不下来"）。 |
| **R4** 两处守卫非静默 | ✅ `stores/ai.ts:604-613`、`AiPanel.vue:512-522`、共用文案 `noticeBusySend():597-599` | 文案：`本轮进行中：本条消息未发送，可等本轮结束后重发。`；空内容（`!content`/`!text`）两处都保持静默；`inputText` 不被清空（面板层 notice 分支在 `inputText.value=''` 之前）。【实测】q1：合成 Enter（面板层）与程序化 `ai.send`（store 层）在 after 相位都写出该 notice，且输入框内容保留、无第二次 HTTP、无新用户消息；before 相位两处都静默（`notice=null`）。 |
| **R5①** `resetSession` 补 `loading=false` + abort 在跑流 | ✅ `resetSession():1045-1054` | 【实测】q7：after `loading=false` 且流被 abort（`aborted=true/closed=true`）；迟到帧注入不再生成孤儿；流上迟到帧被丢弃（`droppedAfterAbort=1`）。 |
| **R5②** `reattachActive` 入口防双连接 | ✅ `reattachActive():754-770`（**先换身份再 abort 旧连接**，见 §5 顺带项） | 【实测】q6：连点两次 → `EventSource created=2 / live=1`（before: live=2）；回放只被一条连接消费（before: 两条宿主消息内容重复渲染）。 |
| **R6** 旧轮迟到提示不污染新轮 | ✅ `send` 的 `onReconnect/onClose/catch`、`reattachActive`/`resumeSnapshotStream` 的两个回调与 `catch` 全部加 `isCurrentRound()` 判定 | 【实测】q4 同 tick：after 的 `notice` 是新轮的守卫提示，不是旧轮 abort/断流文案；【实测】q9：防双连接中止旧续收连接时，旧连接的"连接已取消"提示不再写脏新连接现场（notice=null）。详见 §5 的未覆盖面。 |
| **G6** 删除 `restoreSuspendedSnapshot` 死守卫 | ✅ `:454`（已删） | 先核实：该 action 的调用点只有 `loadHistory():409/438`（`historyLoaded=true` 之后），而 `loadHistory` 由 `AiPanel.onMounted` 在 `messages` 为空时调用 ⇒ 到达时 `loading` 恒为 false ⇒ 守卫是死分支【实测（代码级，含调用点 grep）】。删除后行为不变（修复前后各探针未出现该路径差异）。 |
| **不做项（照裁决）** | ✅ | ①不放开挂起等待期输入禁用（`canSend=!loading` 不变，【实测】q8：挂起期主输入框 `disabled=true`，语义不变）；②`canSend` 不加 `available`（G8 登记）；③不做排队/草稿（方案 b 否决）；④不放行并发（方案 c 否决）；⑤不改后端、不改契约。 |

---

## 3. 验证结果（全部真实执行）

### 3.1 门禁

| 命令 | 结果 |
| --- | --- |
| `frontend: yarn typecheck`（`vue-tsc --noEmit`） | ✅ 0 error（修复后；两次运行均绿）【实测】 |
| `frontend: yarn build`（`vue-tsc --noEmit && node scripts/build.mjs`） | ✅ 1747 modules transformed，`✓ built`（修复后）【实测】 |
| 后端 | 未改动、未启动（本 issue 纯前端） |
| `git status --porcelain` | 仅本棒 2 个文件 + 并行任务 7 个文件 + 其 1 个新测试；**探针文件已删除，无残留**（施工后复核）【实测】 |

### 3.2 探针方法与真实性

- 载体：真实 `AiPanel` + 真实 pinia `ai` store + `app.use(ElementPlus)`，**只桩三处网络出口**：`window.fetch`（`POST /api/ai/chat`，受控 `ReadableStream` 逐帧投喂 + 保持流不关闭 + 409 模式 + 记录调用次数与请求体）、`window.EventSource`（`GET /api/ai/events/{runId}`，记录实例数/关闭数）、axios 适配器（`/ai/health`、`/ai/history`、`/ai/runs/current`、`/ai/cancel`）。
- **真实输入**：Enter 与按钮点击均由 CDP `Input.dispatchKeyEvent` / `Input.dispatchMouseEvent` 走浏览器输入管线派发（页面通过 `window.__probeAwait` 与运行器握手），因此"disabled 元素收不到键盘事件/点击"这一结论是浏览器真实行为，不是桩的产物。合成 `KeyboardEvent`/程序化 `store.send` 只在明确标注的步骤里用来逼出守卫本身。
- 端口纪律：dev `5212`、CDP `9336`（启动前 netstat 确认空闲；仅停自己起的进程，vite 子进程按 PID 13000 单独终止）。
- 观测面：`ai.loading/suspended/notice/busyConflict/stopped/streamInterrupted/activeRunId/messages`、DOM（textarea/发送/停止按钮的 disabled、Alert 文案、确认卡）、`__fetchCalls`、`__esInstances`（含 created/live/closed）。
- 复跑法：`<LOCAL_WORK>/issue5-reports/evidence/probe-run.mjs` 头部注释已参数化（`PROBE_CHROME/PROBE_OUT/PROBE_PROFILE/PROBE_URL/PROBE_CDP_PORT` + `--phase before|after [--scenarios ...]`），无本机绝对路径。

### 3.3 q1-q8 结论（before = 修复前，after = R1-R6 后）

标注：【实测】= 本次真实浏览器探针观测；【实测（代码级）】= 代码实读；【推断】= 未直接观测。

| 场景 | before（修复前） | after（修复后） | 结论 |
| --- | --- | --- | --- |
| **q1 守卫可达性（正名）** | ①真实 Enter 发第一轮（`fetchCalls=1`）→ ②DOM：textarea `disabled=true`、发送按钮 `disabled=true`、停止按钮在 → ③**第二次真实 Enter：`fetchCalls` 仍=1** → ③b **真实点击 disabled 发送按钮：仍=1** → ④合成 Enter 命中面板层守卫：**静默**（`notice=null`，输入框内容保留）→ ⑤程序化 `ai.send`：**静默**、无第二次 HTTP、无新消息 | ④⑤ 两处守卫都写 `本轮进行中：本条消息未发送，可等本轮结束后重发。`，**输入框内容保留**、无第二次 HTTP、无新用户消息；②仍 `disabled=true`（UI 语义未变） | 【实测】issue 所述"静默丢弃连发消息"在现 UI **不可达**（真实 Enter/真实点击都到不了 handler）；守卫本身确实静默 → R4 修正，且 R4 在现 UI 只对程序化/合成路径生效（纵深防御） |
| **q3 终帧提前清 × finally 覆盖（主证据）** | 投喂 `done` 后保持连接不关：**同一微任务 tick 内读 `ai.loading=false`**（终帧已清、`readStream` 尚未 return）→ 在该 tick 发第二条被接受 → 旧轮 `finally` 无条件复位 ⇒ 新轮期间 `loading=false`、`runState=空闲`、**停止按钮消失**、`currentAbort=null`（新轮流仍打开）→ `ai.stop()` **abort 不到新流**（`aborted=false`） | 新轮期间 `loading=true`、`runState=生成中…`、停止按钮在、`ai.stop()` 成功 abort 新流（`aborted=true/closed=true`） | 【实测】窗口宽度 **= 1 个微任务 tick（0 个宏任务）⇒ 用户不可达**（报告 §3-G2 里"窗口内用户可发送"的推断被证伪，登记为定性更正依据）；同一窗口内 R1 确实关掉了覆盖：UI 不再谎报空闲、不再丢取消句柄 |
| **q4 `stop()` 抢跑** | 同 tick `stop(); send('第二条')`：`fetchCalls 1→2`（**被接受**）→ 收尾后 `loading=false`、新轮流仍打开且 `currentAbort` 已被抹（`i=1 aborted=false`）→ 旧轮 abort 产生 `streamInterrupted=true` + "连接中断，结果可能不完整 / 一键重挂该轮" 并污染提示 | 同 tick 发送**被守卫挡住**：`fetchCalls` 不变 + notice=守卫文案，无第二条消息；错开窗口后（200ms）第三条正常发出且可被 `stop()` 取消（`aborted=true`） | 【实测】R3+R1 联合生效：停止后不再"立刻放开→被旧轮收尾覆盖"，也不再产生"孤儿流"（before 收尾时 i=1 流永久打开、无法取消） |
| **q5 409 → 一键重挂（回归约束）** | 模式 B 409 → `busyConflict.runId` + notice + 「当前会话正在进行中 / 一键重挂收流」Alert → 真实点击该按钮 → `EventSource created=1` → 回放 `start/delta/done` → `loading=false`、`busyConflict=null`、消息 3 条 | **与 before 逐字段一致**（同文案、同按钮、同实例数、同收敛结果） | 【实测】**行为零回归**（本修复未触碰该链路；也据此确认"守卫不可达 ⇒ 不会误吞 409 场景"） |
| **q6 `reattachActive` 连点** | 同一 tick 调两次 → `created=2 / live=2` → 回放注入两条连接 ⇒ **两条宿主消息内容重复渲染** | `created=2 / live=1`（旧连接被 abort 并 close）→ 回放只被一条连接消费，无双渲染；`loading` 收敛 false | 【实测】G4 双连接/双渲染消除（`live` 2→1） |
| **q7 `resetSession` 期间在跑** | reset 后 `loading` 仍 `true`（输入区保持禁用）、当前流未 abort；随后迟到帧被 `handleFrame` 兜底 append 到新助手消息 ⇒ **孤儿消息**（`msgs 0→1`，内容="（孤儿帧注入）"），流上再投喂继续追加 | reset 后 `loading=false`、流被 abort（`aborted=true`）、迟到帧注入被整帧丢弃、流上迟到帧被丢弃 ⇒ `msgs=0` 全程无孤儿 | 【实测】G5 修复 |
| **q8 挂起等待期输入** | 投喂 `suspended(CONFIRM)` → `suspended=true`、`runStateLabel=挂起等待中…`、**主输入框 `disabled=true`**（`textareaCount=2`：确认卡拒绝原因框 + 主输入框，按 DOM 顺序取最后一个为主输入框）→ `done` 后 `disabled=false` | **与 before 完全一致**（断言 `assertSuspendedDisabled=true`、`assertEnabledAfterDone=true`） | 【实测】"挂起等待期禁用输入"语义不变（裁决 8 不做项成立） |
| **q2 空内容守卫** | 按裁决省跑 | 按裁决省跑 | 【实测（代码级）】`stores/ai.ts:604-607` 与 `AiPanel.vue:512-515` 两处 `!content`/`!text` 分支保持静默 `return`，不改 |

### 3.4 q9（after-only 补充回归，本棒自加）

本轮改动面覆盖了"刷新后快照重建 + 增量续收"（`restoreSuspendedSnapshot` → `resumeSnapshotStream`，含 G6 删除的守卫与新增的轮次身份），故加一条 after-only 回归探针（无 before 对照，因 before 需要回退源码而本棒禁止 git 写操作）：

- 桩 `runs/current` 返回 `found=true, awaitingExternal=true, pendingEntries=[PENDING(CONFIRM)]` → 面板加载 → 快照重建出一张挂起卡 + 续收 EventSource（`live=1`）+ `loading=true`；
- 回放 `start/delta/done` → 挂起卡按帧收敛（`pendingConfirm=false`）、宿主消息收到增量、`loading=false`、`suspended=false`、无孤儿（`assistantMessages=1`）、`assertConverged=true`、`assertNoOrphan=true`【实测】；
- 该探针还暴露并验证了一处顺带项（见 §5）：`resumeSnapshotStream` 的"防双连接"中止旧连接时，旧连接的 `onClose` 会写脏 `notice`——把"先换身份再中止"后（`previous?.abort()` 置于 `currentAbort = controller` 之后），旧连接收尾被 R6 判定为"非本轮"而跳过，`notice` 保持 `null`【实测】。

### 3.5 证据清单（`<LOCAL_WORK>/issue5-reports/evidence/`）

`probe-{before,after}-{q1,q3,q4,q5,q6,q7,q8}.json + .png`（28 个文件）+ `probe-after-q9.json/.png` + 运行器 `probe-run.mjs` + **探针源码存档** `probe-issue5.html` / `probe-issue5-src.ts`（脱敏副本，用时可整体复制回 `<REPO_ROOT>/frontend/__issue5_probe.html` + `frontend/__issue5_probe.ts`；本次已用存档副本实跑 q1/q9 复刻出与记录一致的结果【实测】）+ `dev-before.log`/`dev-after.log`。
入库前已脱敏：证据文件与本文件均**无盘符、无仓库目录名、无用户名**（对这三类做 grep 自检，命中 0）【实测】。仓库内探针文件已在施工收尾时删除，`git status` 复核无残留、`yarn typecheck` 复跑仍绿【实测】。

---

## 4. 与只读报告 §6【待裁决】清单的对应

| §6 条目 | 本次处理 | 证据/说明 |
| --- | --- | --- |
| 1. issue 定性更正 | **照裁决执行**：报告/施工均按"守卫静默不可达 + 真缺陷=loading 失步"表述 | q1 正名 + q3 窗口量化（1 微任务 tick）【实测】 |
| 2. 修复范围 | **R1-R6 全量同批**（R4 的 (a) 方案作为纵深防御与 R1/R2 同提交） | §2 全表 |
| 3. 是否放开挂起等待期输入禁用 | **不做**（裁决 8）：`canSend=!loading` 不变 | q8 断言不变【实测】 |
| 4. 跨页签同会话（G9） | **不做**（未改会话隔离）；本报告登记"跨页签 409 + 一键重挂即设计解法"，并以"跨页签等价构造"覆盖 q5 | q5 after 与 before 一致【实测】 |
| 5. `notice` 是否升格为轮次级对象 | **不升格**（裁决 6 轻量）：R6 用 controller/宿主归属判定实现，`notice` 仍单槽位 | §2 R6；遗留项见 §5 |
| 6. R5 顺手项（G4/G5/G6）是否同提交 | **同提交**（都在同文件、同型、风险低） | q6/q7 + G6 代码级核实 |

---

## 5. 遗留、风险与【待裁决】

1. **【待裁决】R3 的取舍**：`stop()` 不再立刻清 `loading`，复位依赖"本轮 promise 的 abort 收尾"。探针里该收尾在 200ms 内完成（q1 收尾观测）【实测】，真实浏览器 abort 会立即 error body 流；但若将来传输层/后端在客户端 abort 后仍长时间不关流，输入区会一直禁用。备选：保留旧"立刻清"并**同时**加身份判定（收益相同但窗口仍在）。是否需要加"停止后超时兜底"请示下。
2. **【待裁决】停止后的"连接中断"提示（既有行为，本次未改）**：用户点「停止」后，本轮 `onClose(terminal=false)` 仍会置 `streamInterrupted=true` 并显示红色"连接中断，结果可能不完整 + 一键重挂该轮"（随后 `notice` 被 `已请求取消本轮对话` 覆盖）。q1 收尾证据可见【实测】。`stopped` 标志已存在，可低成本按 `stopped` 抑制该提示——但属行为变更，未纳入本期。
3. **【待裁决】`notice` 仍是单槽位**：R6 只挡"旧轮不再活跃"的迟到写入；旧轮**仍活跃**时的收尾提示（如第 2 条的停止提示）仍会覆盖新提示。若要求彻底"提示归属轮次"，需按 §6.5 升格为轮次级对象（会动 Alert 绑定与既有文案断言面）。
4. **风险（低）**：`handleFrame` 对"非本轮帧整体丢弃"是行为收紧（连 `delta/tool_result` 也不再写旧气泡）。当前 3 个调用点（`send`/`reattachActive`/`resumeSnapshotStream`）都由本文件传入自身设置的宿主 id，形态封闭；若将来后端出现"帧的 assistantId 与宿主不一致但仍属本轮"的路由形态，需同步放宽该判据。
5. **风险（低）**：续收路径（`resumeSnapshotStream`）只有 after-only 覆盖（q9），无 before 对照（before 需回退源码，本棒禁 git 写操作）。
6. **登记（不做）**：G8（`canSend` 不含 `available`，输入框在 AI 降级时仍可输入并走 503 有提示路径）；G1（`resetSession` 后 `inputText` 未清，纯空白会被面板层 trim 拦住）；方案 (b) 排队/草稿、(c) 并发放行——均按裁决否决，未实现。
7. **未覆盖**：后端 409/重挂链路的权威性未在本次重跑（纯前端桩等价替代，q5 覆盖前端侧全链路）；如需"零回归"抽样，可另跑 backend 的 `SessionGate`/`AiController` 用例（非必需，且本棒未获后端施工授权）。

---

## 6. 复现指引（供提交官/复核）

1. 把 `<LOCAL_WORK>/issue5-reports/evidence/probe-issue5.html` 复制为 `<REPO_ROOT>/frontend/__issue5_probe.html`、`probe-issue5-src.ts` 复制为 `<REPO_ROOT>/frontend/__issue5_probe.ts`，`frontend/` 下 `VITE_DEV_PORT=5212 yarn dev`；
2. `PROBE_CHROME=<chrome.exe> PROBE_OUT=<证据目录> PROBE_PROFILE=<临时 profile> PROBE_URL=http://localhost:5212/__issue5_probe.html PROBE_CDP_PORT=9336 node probe-run.mjs --phase after [--scenarios q1,...]`（运行器与探针源存档均已参数化、无本机绝对路径）；
3. before 对照需把 `stores/ai.ts`/`AiPanel.vue` 回退到 `da3d084`（**需 git 授权**，本棒未做）；
4. 复跑收尾：删除 `<REPO_ROOT>/frontend/__issue5_probe.*`，再次确认 `git status` 只剩预期修改。

---

## 7. 复核勘误（2026-10-09）

本节记录独立复核报告（`issue5-复核报告.md`）发现项 **O1/O2** 对本报告的更正，均属**行数口径 / 命名表述**问题，不改变 R1-R6 + G6 的落地事实与任何结论。

### 7.1 O1 行数口径（`git diff HEAD --numstat`）

| 文件 | 原口径 | 更正后（numstat） | 说明 |
| --- | --- | --- | --- |
| `frontend/src/stores/ai.ts` | 「+161/-27」 | **+135/-26** | `--stat` 显示的 `161` 是改动行合计（= 135 + 26），原口径把合计误当作插入行数 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 「+10/-2」 | **+9/-1** | 同上，`--stat` 显示 `10` |

更正来源：复核报告 §8 观察项 **O1**（依据 `git diff HEAD --numstat` = `135 26` / `9 1`【实测】）。说明：原「+161/-27」「+10/-2」出现在施工交接口径中，本报告正文此前未单列逐文件行数；本次已按 numstat 口径在 **§1** 补注逐文件行数，后续引用一律以该口径为准。

### 7.2 O2 R4 动作名与行号（store 层）

- **更正前**：`#notifyBusySend`（`:593-599`）。
- **更正后**：**`noticeBusySend()`（公开 action，`ai.ts:597-599`）**。
- 理由：`#` 前缀在 TS 中会被读作私有字段（`#name`），而该动作是**公开 action**——面板层 `ai.noticeBusySend()`（`AiPanel.vue:519`）必须公开可见；行号同步由 `:593-599` 更正为 `:597-599`。
- 落点：**§1 锚点表**（R4 行）与 **§2 R4 行**。更正来源：复核报告 §8 观察项 **O2**（依据 `ai.ts:597`、`AiPanel.vue:519`【实测】）。


---

**〔分件 3／4〕** `<LOCAL_WORK>/issue5-reports/issue5-复核报告.md` · 源 192 行 / 25,430 B · 角色：复核

# issue #5 独立复核报告（loading 守卫非静默 + 轮次状态收敛 R1-R6/G6）

- 复核对象：`<REPO_ROOT>`（分支 main，`HEAD = da3d084`）工作区未提交改动中的 **#5 面**：`frontend/src/stores/ai.ts` + `frontend/src/components/AiPanel/AiPanel.vue`
- 被复核报告：`<LOCAL_WORK>/issue5-reports/issue5-施工报告.md`（全文实读）
- 复核角色：施工棒之外的**独立复核员**（只读核验 + 复跑验证）
- 纪律：**零 git 写操作**（未 add/commit/push/reset/stash；仅用只读 `git show`/`git diff`/`git hash-object`）；**零仓库文件修改**（复跑用 scratch 影子副本承载探针）；命名纪律用 `<REPO_ROOT>`/`<LOCAL_WORK>` 占位符，本报告零命中「main + 分隔符 + v2」组合
- 判定口径：结论标签为 【实测】（本次亲自观测/执行）、【实测（代码级）】（本次亲自读码+调用点检索）、【推断】、【假设】、【旁证】
- 总判定：**PASS（零阻断项）**；发现项均为报告口径/行号/边角风险类「观察项」，不影响 R1-R6/G6 的落地与裁决符合性

---

## 0. 复核前/后仓库不变性（先证明"我没动过仓库"）

| 检查 | 复核开始 | 复核结束 | 判定 |
| --- | --- | --- | --- |
| `git status --porcelain` | 8 M + 1 ??（#4 面 5 个 + #5 面 2 个 + 新测试 1 个） | **逐行一致**（无新增/无残留） | 【实测】 |
| `git hash-object` 两文件 | `9d7450b…`(ai.ts) / `0a40e22…`(AiPanel.vue) | 同前，且与 `git diff` 的 `index eba477e..9d7450b`/`a1a8113..0a40e22` 一致 | 【实测】 |
| 探针残留 | 仓库内无 `__issue5_probe.*` | 仍无 | 【实测】 |
| `node_modules` 完整性 | 153 项 | 153 项 + `yarn typecheck` 清理后复跑仍 0 error；`du -sh` 205M、`node_modules/.bin` 44 项、`vite`/`vue`/`element-plus`/`vue-tsc` 齐备 | 【实测】 |

复跑方式说明：本次复核**没有**把探针文件放回仓库，而是建了 scratch 影子副本 `<LOCAL_WORK>/issue5-reports/_recheck_probe/frontend`（复制 `src/`+配置文件，`node_modules` 用目录联接指向仓库，避免复制 6GB 依赖；探针文件写在副本内）。复核收尾已删除该影子副本（含联接，用非递归方式解除，已确认仓库 `node_modules` 未受影响）。

---

## 1. 复核清单逐项判定

| # | 清单项 | 判定 | 依据摘要 |
| --- | --- | --- | --- |
| 1 | 读施工报告全文 | **PASS** | 127 行全文实读，§1 锚点表与实读代码逐条对得上 |
| 2 | `git diff HEAD` 逐 hunk 核对 R1-R6/G6 + 越界 + 命名纪律 | **PASS** | 8 个 hunk 全覆盖，无越界改动，命名纪律零命中（§2） |
| 3 | 复跑 `yarn typecheck && yarn build` | **PASS** | 双绿（typecheck 0 error；build 1747 modules / ✓ built）【实测】（§3） |
| 4 | 证据抽查 q1/q3/q4/q5/q6/q7 | **PASS** | 与报告结论逐条一致；q5 归一化后逐字段零差异；q6 `live` 2→1；q3 before 的"UI 谎报空闲"与 after 的收敛字段均可读证（§4） |
| 4b | 证据脱敏 | **PASS（含 1 条观察项）** | 无盘符/用户名/仓库目录名（0 命中）；`dev-*.log` 含内网地址 `192.168.<x>.<y>`（不属报告自定的三类，见 O6） |
| 5 | 复跑探针 after 相位关键场景（q1/q3/q4） | **PASS** | 与归档证据**逐字段一致**（规范化 id/UUID/label 后 diff=0）；额外用 `git show HEAD:` 源码在影子副本复刻 **before 相位**，同样逐字段一致（§5） |
| 6 | 「非本轮帧整体丢弃」收紧面（3 个调用点形态） | **PASS** | 3 个调用点形态封闭、均为本文件自设宿主 id；误伤面仅为"已结束轮的迟到帧"（§6） |
| 7 | R6 有效性（先换身份再中止旧连接） | **PASS** | 代码级顺序 + 闭包判定 + q9 证据三重佐证；旧连接 `onClose('连接已取消')` 确实被拦（§7） |

---

## 2. `git diff HEAD` 逐 hunk 核对

### 2.1 ai.ts（7 hunk，实读 +135/-26，见 O1 行数口径）

| hunk | 位置（改后） | 落地内容 | 对应裁决 | 判定 |
| --- | --- | --- | --- | --- |
| H1 | `@@ -25,16 +25,24 @@` | 新增模块级 `let activeRoundHostId: string \| null = null`（:40）+ 判据注释 | R2 判据 | 【实测】 |
| H2 | `@@ -439,19 +447,16 @@` | 删除 `restoreSuspendedSnapshot` 的 `if (this.loading) return` | G6 | 【实测】 |
| H3 | `@@ -535,101 +540,147 @@` | ①`resumeSnapshotStream`：`previous = currentAbort` → 先 `currentAbort=controller`/`activeRoundHostId=hostMessageId` → 再 `previous?.abort()`；`onReconnect`/`onClose`/`finally` 全加 `isCurrentRound()`；②新增公开 action `noticeBusySend()`（:597-599）；③`send` 守卫拆成 `!content` 静默 + `this.loading` 非静默；④本轮身份 `const controller`/`isCurrentRound`；⑤`onReconnect`/`onClose`/`catch`/`finally` 全量身份判定 | R6/R5②/R4/R1 | 【实测】 |
| H4 | `@@ -695,54 +746,75 @@` | `reattachActive`：入口 `previous = currentAbort` + 换身份后 `previous?.abort()`（防双连接）；`onReconnect`/`onClose`/`catch`/`finally` 身份判定 | R5②/R1 同型/R6 | 【实测】 |
| H5 | `@@ -945,17 +1017,19 @@` | `stop()` 删除 `this.loading = false`（复位交给本轮收尾） | R3 | 【实测】 |
| H6 | `@@ -964,16 +1038,26 @@` | `resetSession()` 先 `currentAbort?.abort()`、`currentAbort=null`、`activeRoundHostId=null`、`loading=false` | R5① | 【实测】 |
| H7 | `@@ -1028,29 +1112,54 @@` | `handleFrame`：`ownedByActiveRound` 归属判定（:1125）；宿主缺失且非本轮 → 整帧 `return`（:1130-1134，G5）；非本轮帧只收敛自身终态后 `return`（:1145-1156） | R2/R6/G5 | 【实测】 |

说明：报告把「终帧复位 `:1364-1366`」列为 R2 锚点，实读确认为**既有代码**（`if (isTerminalFrame(frame)) this.loading = false`），其行为被 H7 的提前 `return` 收窄——表述准确，非新增行。【实测（代码级）】

### 2.2 AiPanel.vue（1 hunk）

`@@ -509,7 +509,15 @@`：`if (!text || !ai.canSend)` 拆为 ①`if (!text) return`（保持静默）②`if (!ai.canSend) { ai.noticeBusySend(); return }`（非静默、**不消费草稿**）。`inputText.value = ''` 仍在守卫之后（:523），顺序未变。【实测】

### 2.3 越界检查（全部"未越界"）

| 检查项 | 实读结果 | 判定 |
| --- | --- | --- |
| `canSend` 语义 | `ai.ts:306-308` 仍 `return !state.loading`，**未加 `available`**；面板 `:267 :disabled="!ai.canSend"`、`:383 canSubmit = ai.canSend && ai.available && …` 均未改 | 【实测】 |
| 挂起等待期输入禁用 | 未放开（q8 证据两相位 `textareaDisabled=true` 一致） | 【实测】 |
| 空内容分支 | `ai.ts:604-607`、`AiPanel.vue:512-515` 两处均 `return` 且不写 `notice` | 【实测】 |
| 后端/契约 | `git diff` 仅前端 2 文件；后端 4 文件与 `stores/workspace.ts`/`types/workspace.ts`/`utils/frontend-tools.ts` 属 #4 面，本棒未碰 | 【实测】 |
| `notice` 结构 | `ai.ts:223` 仍 `notice: null as string \| null`，**未升格**为轮次级对象 | 【实测】 |
| 命名纪律 | `git diff` 新增行 + 两文件全文 `grep -inE "main[ _/.-]*v2"`：**0 命中**；`issue5-施工报告.md`/`issue5-修复报告.md` 同样 0 命中 | 【实测】 |

---

## 3. 门禁复跑（复核自己执行）

| 命令 | 结果 | 判定 |
| --- | --- | --- |
| `cd <REPO_ROOT>/frontend && yarn typecheck` | `vue-tsc --noEmit` → `Done in 3.73s`，**0 error**（复核开始时与 scratch 清理后各跑一次，均绿） | 【实测】 |
| `cd <REPO_ROOT>/frontend && yarn build` | `vite v6.4.4 building for production... ✓ 1747 modules transformed. ✓ built in 27.24s` | 【实测】 |
| 后端 | 未启动/未跑（#5 为纯前端面，裁决要求不改后端/契约；后端权威性由 #4 面与既有 CI 承担） | 【旁证】 |

---

## 4. 证据抽查（归档证据 vs 报告结论）

抽查路径：`<LOCAL_WORK>/issue5-reports/evidence/`（`probe-{before,after}-{q1,q3,q4,q5,q6,q7,q8}.json/.png` + `probe-after-q9.*`，共 30 个产物 + 运行器 + 探针源码存档）。

| 场景 | 报告结论 | 证据实读（本次逐字段读 JSON） | 判定 |
| --- | --- | --- | --- |
| **q1** | before 面板层/ store 层守卫均**静默**（`notice=null`）；after 两处写同一文案且**输入框内容保留**；真实 Enter/真实点击 disabled 按钮都到不了 handler | before：`step4_syntheticEnterPanelGuard.ai.notice=null`、`step4b_storeGuard.ai.notice=null`、`textareaValue='第二条消息（真实 Enter 试探）'`；after：两处 `notice='本轮进行中：本条消息未发送，可等本轮结束后重发。'` 且 `textareaValue` 同样保留；两相位 `step3/step3b.fetch.calls=1`（无第二次 HTTP）、`step2.dom.textareaDisabled=true/sendBtnDisabled=true/stopBtnFound=true` | 【实测】一致 |
| **q3** | before：终帧后**同一微任务 tick** 内 `loading=false`，第二条被接受 → 旧轮 `finally` 无条件复位 ⇒ 新轮期间 UI 谎报空闲、停止按钮消失、`stop()` abort 不到新流；after：新轮期间 `loading=true`、`runState=生成中…`、停止按钮在、`stop()` 成功 abort 新流 | before：`windowTick={loading:false,fetchCalls:1}`、`microtaskAfterSend={loading:true,fetchCalls:2}`、`afterSecondSend.ai={loading:false,runStateLabel:'空闲',dom.stopBtnFound:false,canSend:true}`、`afterStop.streams[1]={closed:false,aborted:false}`（孤儿流不可取消）；after：`afterSecondSend.ai={loading:true,runStateLabel:'生成中…',dom.stopBtnFound:true,dom.textareaDisabled:true}`、`afterStop.streams[1]={closed:true,aborted:true,abortReason:'The user aborted a request.'}` | 【实测】一致 |
| **q4** | before 同 tick `stop();send()` 被接受（`fetchCalls 1→2`）+ 产生孤儿流/污染提示；after 被守卫挡住（`fetchCalls` 不变 + 守卫 notice），错开窗口后第三条正常发出且可被取消 | before：`sameTick={fetchBefore:1,fetchAfter:2,noticeRightAfter:null}`；after：`sameTick={fetchBefore:1,fetchAfter:1,noticeRightAfter:'本轮进行中：本条消息未发送，可等本轮结束后重发。'}`、`thirdSend.fetch.calls=2`（第三条发出） | 【实测】一致 |
| **q5** | 409 → `busyConflict` + 一键重挂 → 回放 `start/delta/done` 收敛；**after 与 before 逐字段一致（零回归）** | 我把两份 JSON 做了**规范化 diff**（抹掉随机 id/UUID/label）：**唯一差异为空**（diff 行数 0）。两侧 `esAfterClick={created:1,live:1,closed:0}`、`afterReattachDone.ai={loading:false,notice:null,busyConflictRunId:null,messageCount:3}` 完全相同 | 【实测】一致 |
| **q6** | 连点两次 `reattachActive`：EventSource `created 2 / live 2→1`，回放只被一条连接消费（before 双宿主重复渲染） | before：`es={created:2,live:2,closed:0}`、`liveCountAtReplay=2`、`replayedInto=2`、两条宿主消息内容均为 `…回放内容`（重复渲染）；after：`es={created:2,live:1,closed:1,closeCounts:[1,0]}`、`liveCountAtReplay=1`、`replayedInto=1` | 【实测】一致 |
| **q7** | before `resetSession` 后 `loading` 仍 `true`、流未 abort、迟到帧生成孤儿消息；after `loading=false`、流 abort、迟到帧整帧丢弃、流上迟到帧丢弃、无孤儿 | before：`rightAfterReset.ai={loading:true,messageCount:0}`、`afterLateFrameInjection.ai.messageCount=1`（`content='（孤儿帧注入）'`）、`afterLatePush` 继续追加；after：`rightAfterReset.ai={loading:false,runStateLabel:'空闲'}`、`afterLateFrameInjection.ai.messageCount=0`、`afterLatePush` 仍 0、`streams[0]={closed:true,aborted:true,droppedAfterAbort:1}` | 【实测】一致 |
| **q8** | 挂起等待期主输入框 `disabled=true`，`done` 后恢复；两相位完全一致 | before/after 断言字段 `assertSuspendedDisabled=true`、`assertEnabledAfterDone=true`，`runStateLabel='挂起等待中…（待人工确认或待前端执行）'` | 【实测】一致 |
| **q9**（after-only） | 快照重建 + 增量续收收敛；防双连接中止旧续收连接时，旧连接提示**不写脏新连接**（`notice=null`） | `afterRestore.es={created:2,live:1,closed:1,closeCounts:[1,0]}`、`afterDone.ai={loading:false,suspended:false,notice:null,canSend:true}`、`assertConverged=true`、`assertNoOrphan=true`、`afterDone.es={live:0,closed:2}` | 【实测】一致 |

脱敏抽查：对 `evidence/` 全目录 `grep` 盘符（`<DRIVE>/`/`<DRIVE>/`）、本机用户名、仓库目录名——**0 命中**（仅 `http://localhost` / `127.0.0.1` 这类 URL 假阳性，且被 JSON 内嵌的探针源码字符串命中）。两份报告同样 0 命中。**唯一观察项**：`dev-before.log`/`dev-after.log` 含局域网地址 `192.168.<x>.<y>:5212`（不属自定三类脱敏目标，见 O6）。【实测】

---

## 5. 探针复跑（本次复核亲自执行）

### 5.1 复跑环境

- scratch 影子副本承载探针（**仓库零写入**，见 §0）；`node_modules` 目录联接复用仓库依赖
- 端口纪律：启动前 `netstat` 确认 `5212`/`9336` 空闲；dev 用 `VITE_DEV_PORT=5212`；CDP 用 `9336`；Chrome 由运行器自行 `kill`，**用户已开的 Chrome 未被触碰**（复核只查询进程，未 kill 非自启进程）；复核收尾只终止自启 vite（PID 8076，端口已释放）【实测】
- 运行器与探针源码直接取自归档（`evidence/probe-run.mjs`、`probe-issue5.html`、`probe-issue5-src.ts`），未做任何修改【实测】

### 5.2 结果

| 相位 | 场景 | 复跑产物 | 与归档证据比对（规范化 id/UUID/label/随机 sessionId 后） | 判定 |
| --- | --- | --- | --- | --- |
| after（当前工作区源码） | q1,q3,q4 | `<LOCAL_WORK>/issue5-reports/recheck-rerun/probe-after-q{1,3,4}.{json,png}` | **逐字段零差异（diff 行数 = 0）** | 【实测】 |
| before（`git show HEAD:` 的两文件注入影子副本，只读操作） | q1,q3,q4 | 同上 `probe-before-*` | q1/q4 零差异；q3 仅随机 `sessionId` 不同（规范化残留，非行为差异） | 【实测】 |

复跑摘要（运行器 stdout）：after `q1 notice='本轮进行中：本条消息未发送，可等本轮结束后重发。'`、`q3 loadingAfter=true`、`q4 loadingAfter=false/notice='已请求取消本轮对话'`；before `q1 notice=null`、`q3 loadingAfter=false`、`q4 loadingAfter=false`——与归档证据的相位特征完全对应。【实测】

**结论**：归档证据不是一次性产物，两个相位均可复现（before 相位原报告声明"需 git 授权回退源码、本棒未做"，本次用 `git show` 只读方式补齐，可作为后续参考）。清单第 5 项未触发"降级为逻辑自洽性核验"分支。

---

## 6. 「非本轮帧整体丢弃」收紧面（清单第 6 项）

### 6.1 `handleFrame` 全部调用点（`grep -rn "handleFrame" frontend/src/` 仅 3 处，无第 4 处、无 `undefined` 参形态）

| 调用点 | 形态 | 宿主 id 来源 | 与 `activeRoundHostId` 的关系 |
| --- | --- | --- | --- |
| `send` `ai.ts:630` | `onFrame: (frame) => this.handleFrame(frame, assistant.id)` | 本轮 `appendAssistantMessage()` 返回的消息 | :625 紧邻前赋 `activeRoundHostId = assistant.id`，同一同步块内，**恒等** |
| `reattachActive` `ai.ts:775` | 同上 | 重挂新建的助手消息 | :764 紧邻前赋值，**恒等** |
| `resumeSnapshotStream` `ai.ts:564` | `onFrame: (frame) => this.handleFrame(frame, hostMessageId)` | 快照重建就位的宿主消息 id | :554 紧邻前赋值，**恒等** |

三个调用点**形态封闭**：宿主 id 都是在发起连接之前由本文件写入 `activeRoundHostId`，且 `handleFrame` 的第二参就是同一个值。因此 `ownedByActiveRound === false` 只能由两种事件造成：①**宿主被换**（新轮 `send`、`reattachActive`、`resumeSnapshotStream` 入口重写 `activeRoundHostId`）——此时旧连接已被/将被中止（`reattachActive`/`resumeSnapshotStream` 显式 `previous?.abort()`；`send` 由 `loading` 守卫挡住绝大多数再入）；②**宿主被清**（`resetSession` 或任一轮收尾 `finally`）。两种都是"旧轮已不再活跃"的充分条件。【实测（代码级）】

### 6.2 误伤面评估

- 非本轮帧**不再**写 `delta/reasoning/tool_*`：但 `readStream` 在收到终帧即 `break`（`api/sse.ts:266-269`「终帧到达即不再等待服务端关流」）且不取消 fetch body ⇒ 旧连接在终帧后**不再有帧投递**，能走到"非本轮"判定的帧只可能是断流-重挂链路的迟到帧或 `resetSession` 之后的迟到帧，属协议外/已失效帧，丢弃正确。【实测（代码级）】
- 唯一的"非丢弃"口子刻意保留：`isTerminalFrame` 时仍把**该宿主消息自身**收敛为 `streaming=false/done=true`（:1151-1154），避免旧宿主永久停在"生成中"转圈。q7 after（`messageCount=0`、`streaming=false`）与 q6 after（被弃宿主停在占位文案、非转圈）【实测】。
- 回归面（正常路径不受损）：q5（409 → 一键重挂）与 q9（快照重建 + 增量续收）都是本收紧面直接覆盖的路径，两相位/after-only 证据均绿；且 §5 复跑重放一致。**结论：收紧安全，无正常路径误伤**【实测 + 实测（代码级）】。
- 报告自陈的"若将来后端出现『帧的 assistantId 与宿主不一致但仍属本轮』的路由形态，需同步放宽判据"——本次未发现这种形态存在的证据，且判据是**宿主消息 id**（不是 runId），后端帧不携带宿主 id（宿主 id 是前端生成的 `a-…`），故该形态在当前契约下不可构造。【推断】

---

## 7. R6 有效性（清单第 7 项）

### 7.1 代码级论证

- 顺序正确：`reattachActive`（:762-770）与 `resumeSnapshotStream`（:552-559）都是 **`currentAbort = controller`（换身份）→ `activeRoundHostId = host`（换宿主）→ `previous?.abort()`（中止旧连接）**。`AbortController.abort()` **同步**派发 `abort` 事件 ⇒ 旧连接的 `onAbort`（`api/sse.ts` reattach 的 `settle(state.terminal, '连接已取消')`）→ `finish()` → `handlers.onClose({terminal:false, reason:'连接已取消'})` 在换身份**之后**才执行，旧闭包的 `isCurrentRound()` 判定 `currentAbort === 旧 controller` 为 **false** ⇒ 直接 `return`，`streamInterrupted`/`notice` 均不写。【实测（代码级）】
- 旧轮的 `finally` 同名判定 ⇒ 不复位 `loading`/`currentAbort`/`activeRoundHostId`（否则"新连接刚建好就被旧收尾抹平"）。【实测（代码级）】
- 旧轮的 `catch`（含 abort 异常、网络异常）同样被 `isCurrentRound()` 拦住，异常文案不写进新轮。【实测（代码级）】
- 若把顺序反过来（先 abort 再换身份）或漏判 `catch`，都会写脏新轮——本次三者齐备。反向验证由 q9 提供：中止旧续收连接时，**`notice` 保持 `null`**（若无 R6 守卫，旧连接 `onClose(terminal=false)` 会写「连接中断，结果可能不完整（连接已取消）…」并把 `streamInterrupted` 置 true）。【实测】

### 7.2 残余（不影响裁决项，见 O4/O5）

`reattachActive` 成功分支里的 `this.busyConflict = null` 与 `handleFrame` 的 `runSeq` 记账位于身份判定之外，属"未被本轮 R6 覆盖的写点"，风险极低（详见观察项）。

---

## 8. 发现清单

**零阻断性缺陷**（R1-R6/G6 全部如实落地、无越界、门禁双绿、证据可复现）。以下为观察项：

| 编号 | 级别 | 发现 | 证据/说明 |
| --- | --- | --- | --- |
| **O1** | 报告口径 | 施工棒自报行数不准：ai.ts 实为 **+135/-26**（`--stat` 显示的 `161` 是改动行合计 = 135+26），AiPanel.vue 实为 **+9/-1**（`--stat` 10）；报告/交接写成「+161/-27」「+10/-2」 | `git diff HEAD --numstat` = `135 26` / `9 1`【实测】。把 `--stat` 合计当插入数，属口径误读，**不影响任何结论** |
| **O2** | 报告笔误 | R4 的 store 层动作被写作 `#notifyBusySend`（`:593-599`），实际是**公开 action `noticeBusySend()`（`:597-599`）**；`#` 前缀会被误读为"私有字段"，而面板层 `ai.noticeBusySend()` 必须公开可见 | `ai.ts:597`、`AiPanel.vue:519`【实测】。**仅命名/行号表述问题** |
| **O3** | 行号小偏差 | 报告多处锚点行号与实读相差 1-6 行（如 `handleFrame` 判据写 `:1119-1135`，实为 `:1125` 起；`notifyBusySend` 写 `:593-599`，实为 `:597-599`）；`send` 相关锚点整体偏 1-2 行 | 实读 `sed`/`Read`【实测】。不影响可核对性（语义锚点可定位） |
| **O4** | 低风险（边角） | `handleFrame` 的"整帧丢弃"发生在 **`runSeq` 记账之后**（:1136-1143 先于 :1145）：非本轮帧若缺 `frame.runId`，会用 `this.activeRunId`（可能是**新轮**的 runId）抬高水位，理论上使将来该 runId 的 `reattach` 的 `lastSeq` 偏大而漏补帧 | 【实测（代码级）】。当前 SSE 帧均带 `runId`（`SseFrame` 契约），且 seq 仅影响增量重挂水位、不涉用户可见状态；报告未提及此点。建议后续若收紧，可把归属判定前移到 `frameSeq` 记账之前 |
| **O5** | 低风险（边角） | `reattachActive` 成功分支的 `this.busyConflict = null` 未纳入身份判定：被后一次重挂/新轮取代的旧重挂完成时仍会清空 `busyConflict`（极窄窗口内可能抹掉刚写入的 409 冲突态） | 【实测（代码级）】。不在 R1-R6 列表内，未改变裁决符合性 |
| **O6** | 证据脱敏（超出自定三类） | `evidence/dev-before.log`、`evidence/dev-after.log` 含局域网地址 `192.168.<x>.<y>:5212`；不属"盘符/用户名/仓库目录名"三类，但外发前建议一并清理为 `http://<host>:5212` | `grep`【实测】 |
| **O7** | 行为观察（非回归） | `stop()` 之后仍可能出现 `streamInterrupted=true`（红色「连接中断，结果可能不完整 + 一键重挂」），随后 `notice` 被「已请求取消本轮对话」覆盖。这是**既有行为**：q1 两相位 teardown 均为 `streamInterrupted=true`；q3 after 由 `false` 变 `true` 只是"停止真的生效了（旧代码 stop 连流都没 abort）"的副作用，不是本次引入的回归 | q1/q3 证据 + `api/sse.ts` 的 `finish(terminal=false,...)` 路径【实测】。施工报告 §5.2 已登记为【待裁决】遗留项，处置与裁决一致 |
| **O8** | 流程建议 | 施工棒 §5.5 声明"续收路径只有 after-only 覆盖（before 需回退源码）"。本次复核已证明 **before 相位可用 `git show HEAD:<path>` 只读注入 scratch 影子副本复刻**，无需 git 授权写操作；后续棒可沿用该手法补齐 before 对照 | §5.2【实测】 |

---

## 9. 未覆盖面与限制（诚实声明）

1. **后端链路未复跑**：纯前端桩等价覆盖（q5 覆盖前端侧 409/重挂全链路），未跑后端 `SessionGate`/`AiController` 用例——与裁决「不改后端」一致，非本次风险点。【实测】
2. **#4 面未复核**：本次仅复核 #5 面（ai.ts + AiPanel.vue）；工作区中 #4 的 7 个文件按交接口径视为在途。【实测】
3. **只复跑了 q1/q3/q4 两个相位 + 未重跑 q5-q9**：q5/q6/q7/q8/q9 走「归档证据逐字段实读 + 逻辑自洽性核验」，未做第二次浏览器复跑（受时间与端口纪律约束）。q5/q6/q7 的复跑可行性已由 q1/q3/q4 的逐字段可复现性间接支持。【实测 + 推断】
4. **q3「用户不可达」的定性**：本次实测确认窗口宽度 = 1 个微任务 tick（程序化 `queueMicrotask` 内可发送；`windowTick.loading=false`），且真实键盘/点击路径在 `disabled` 下不产生第二次 HTTP。「用户无法在该窗口内命中」为【推断】（用户输入是宏任务，微任务队列在同一宏任务内必然耗尽）；报告将其作为"证伪 §3-G2 推断"的依据，本次认可该定性，但标签应记为【实测 + 推断】而非纯实测。
5. **`resetSession`/`restoreSuspendedSnapshot` 的"死守卫"结论**：G6 经本次独立复核——`restoreSuspendedSnapshot` 仅由 `loadHistory():417/446` 触发，`loadHistory` 仅由 `AiPanel.onMounted`（`App.vue:34` 常驻挂载、无 `v-if`）在 `messages.length === 0` 时调用，而 `messages=[]` 仅出现在 `resetSession`（同时 `loading=false`）⇒ 到达时 `loading` 恒为 false，守卫确为死分支。【实测（代码级）】

---

## 10. 结论

- **R1-R6 + G6 如实落地**：8 个 hunk 与裁决逐条对应，无越界（`canSend` 语义 / `available` / 后端契约 / 空内容静默 / 挂起期禁用 / `notice` 结构 均未动）。
- **门禁双绿**（typecheck 0 error、build 1747 modules ✓ built），**证据可复现**（after 与 before 两相位 q1/q3/q4 逐字段一致）。
- **行为收紧面安全**：3 个 `handleFrame` 调用点形态封闭，无正常路径误伤（q5/q9 实测佐证）。
- **R6 有效**：换身份在 abort 之前、三处回调与 `finally` 全部判身份，旧连接 `onClose('连接已取消')` 被拦（q9 `notice=null` 佐证）。
- **可进入提交官流程**：无阻断项；O1-O3 为报告表述修正（建议施工棒顺手订正，不影响交付物本体），O4/O5 为低风险边角（可选择登记排期），O6 为脱敏建议，O7 与裁决一致（既有行为），O8 为流程改进建议。


---

**〔分件 4／4〕** `<LOCAL_WORK>/issue5-reports/issue5-红队确认.md` · 源 123 行 / 14,761 B · 角色：红队

# issue #5 红队确认报告（GLM-5.3 · 攻击面评审）

- 评审角色：红队评审官（本体裁决 + 机械活子代理代跑；全程只读 + scratch，零 git 写操作，未修改仓库任何文件）
- 评审对象：`<REPO_ROOT>` 工作区未提交改动中的 **#5 面**（`frontend/src/stores/ai.ts` +135/-26、`frontend/src/components/AiPanel/AiPanel.vue` +9/-1），HEAD=`da3d084`
- 被评审材料：`<REPO_ROOT>/issue5-reports/` 下修复/施工/复核三份报告（含施工报告复核勘误节）+ `evidence/` + `recheck-rerun/`
- 红队方法：本体逐 hunk 实读全量 diff 与 `stores/ai.ts`/`api/sse.ts` 关键路径（不依赖报告转述）；机械活派子智能体（证据对账抽查、脱敏扫描）；结论全部独立复核
- **总判定：放行**（无 S1/S2；S3×1 + S4×5 登记，均不阻断提交）

标注约定：【实测】= 本次红队直接观测/执行；【实测（代码级）】= 本次亲自读码+调用点检索；【推断】/【假设】/【旁证】同各报告口径。

---

## 0. 红队独立验证基线

| # | 验证项 | 结果 | 标注 |
| --- | --- | --- | --- |
| 1 | 证据对账抽查（q1/q3/q6/q7/q9 + recheck-rerun 六份复跑 JSON，子代理逐字段核对） | 全部与报告结论一致；recheck-rerun 与 evidence 同名文件比对**唯一差异为 session UUID**，复跑复现成立 | 【实测】 |
| 2 | ai.ts 全量 diff 逐 hunk + 全部 `loading`/`currentAbort`/`activeRoundHostId` 写点 grep 清单化 | 与施工/复核报告的 hunk 描述一致，无越界；写点清单见 K7 | 【实测】 |
| 3 | 前端门禁 | 施工与复核已各自双绿（typecheck 0 error / build 1747 modules）；红队未重复执行（两方独立绿已足够），后端 357 全量绿见 #4 侧报告 | 【旁证】 |

---

## 1. K7 R1 身份判定覆盖完整性 — **PASS**

红队本体对 `stores/ai.ts` 做了**全量写点清单化**（grep + 逐处实读，非引用报告）：

| 状态字段 | 置位/赋值点 | 复位/清除点 | 归属判定覆盖 |
| --- | --- | --- | --- |
| `loading` | `send:617`、`reattachActive:757`、`resumeSnapshotStream:550`（三入口，全部先建 `controller` + `activeRoundHostId` 再置位） | 三入口 `finally`（`:586`/`:674`/`:808`）、终帧 `:1365`、`resetSession:1055` | finally×3 与终帧均在判定内；resetSession 属清场语义（见下） |
| `currentAbort` | 三入口（`:553`/`:624`/`:763`） | 三入口 `finally` 判定内置 null（`:587`/`:676`/`:810`）、`resetSession:1053` | 同上 |
| `activeRoundHostId` | 三入口（`:554`/`:625`/`:764`） | 同上四点 | 同上 |

**五条复位路径逐条裁定**：
- `send`/`reattachActive`/`resumeSnapshotStream` 的 finally：`isCurrentRound()` 判定 ✓（`currentAbort === controller` 闭包比较）。
- 终帧复位（`handleFrame:1365`）：位于 `ownedByActiveRound` 判定 `return` 之后，非本轮帧到不了 ✓。
- `stop()`：**不动 `loading`**（R3 删了抢清），只 `abort`（`:1020`）——不属复位路径 ✓。
- `resetSession`（`:1049-1055`）：先 abort 在跑轮、再 `currentAbort=null`/`activeRoundHostId=null`/`loading=false`——**先清身份再复位**，被中止的旧轮收尾因身份已清判定为非本轮、不再覆盖 ✓（与三入口的「先换身份再中止」同构）。

结论：身份判定对本模型下所有轮次级状态复位路径**封闭，无漏网路径**。【实测（代码级）】

## 2. K8 O4「runSeq 记账在丢弃判定之前」 — **攻击成立，定级 S4（不推翻）**

红队独立构造该场景并评估：

- **可达性论证**【实测（代码级）】：记账（`handleFrame` 内 runSeq 段）确在 `!ownedByActiveRound → return` 之前；`SseFrameBase.runId` 在 TS 契约上**可选**（`types/sse.ts:84`，注释明言「旧帧可能没有该字段（照收）」），记账用 `frame.runId ?? this.activeRunId` 抬水位。攻击构造：旧轮连接的迟到帧若缺 `runId` 且带 `seq`，而 `activeRunId` 已是新轮 runId ⇒ 新轮水位被抬高 ⇒ 未来该 runId 的 reattach 回传偏大的 `lastSeq` ⇒ 服务端少补帧 ⇒ 消息缺段。
- **真实性压制**（三重）：①当前后端 `SseChatEmitter` 产帧均带 runId（复核报告断言 + s2 帧序对账一致），缺 runId 帧只可能是旧版本/异常序列——协议外形态；②「旧连接仍活着且迟到」的前提被三个入口的 `previous?.abort()` 压缩到 abort 派发到流关闭的毫秒级间隙；③即便发生，后果是增量续收缺段（体验级），无状态破坏、可刷新全量回放恢复。
- **裁决**：S4 登记下批修复（一行序调整：归属判定前移到 runSeq 记账之前），不构成本批打回理由。复核报告 O4 的风险评级（低）恰当，其「报告未提及此点」对施工报告的批评成立——施工报告 §5.4 登记的收紧面风险未覆盖此边角，属透明性小缺口。
- **附加观察**【实测（代码级）】：`resetSession` 清 `activeRunId` 但不清 `runSeq`——旧 runId 水位残留无害（新会话必然新 runId），不构成攻击面。

## 3. K9 R3 无超时兜底 — **攻击不成立，维持裁决（S4 登记前瞻观察）**

红队构造「abort 收尾异常使 loading 永久 true」的攻击场景，结论：**在该传输层实现下不可构造**。

- **收尾必然性**【实测（代码级）】：红队实读 `api/sse.ts` 三条通道的 abort 路径——
  1. **chat（fetch + readStream）**：外部 signal 经 `linkAbort` 串到内部 controller；abort 后 reader 循环退出、`finally` 中 settle（`:293`），`send` 的 `await` 必然 resolve/reject；
  2. **reattach（EventSource）**：`addEventListener('abort', onAbort)`（`:516`）→ `settle(state.terminal, '连接已取消')`（`:439`）——DOM 规范保证 abort 事件监听器同步派发；
  3. settle 内 `source?.close()` + `resolve(...)`，无悬挂 Promise。
  三通道 settle ⇒ handlers 回调 ⇒ `await` 返回 ⇒ `finally` 必跑（try 块内无死循环路径）。「loading 永久 true」需要「abort 事件不派发或 await 永不 settle」，在本实现下无构造路径。探针 q1/q4 实测 stop 后 200ms 内收敛与之一致。【实测（代码级）+旁证】
- **裁决**：不加超时兜底的决定**成立**。S4 登记前瞻观察：该结论绑定当前传输层（fetch/EventSource + 本 sse.ts 实现）；若未来换传输层（如自定义 WebSocket）或 sse.ts 重构 settle 路径，须重新评估此兜底。施工报告 §5.1 已如实列出该待裁决项并请示，处理透明，红队予以结案。

## 4. K10 R4 notice 可见性 — **PASS**

红队逐一排查「守卫 notice 被立即抹掉」的相邻路径：

| 相邻写点 | 是否抹掉守卫 notice | 依据 |
| --- | --- | --- |
| `send()` 开头 `notice = null`（`:619`） | 否 | 位于守卫 `return` **之后**才执行；守卫命中走不到（红队读守卫分支 `:612-614` 与 `:619` 的顺序确认） |
| start 帧 `notice = null`（`:1161`） | 否 | 守卫命中时**无新流** ⇒ 无 start 帧；且该行在 `ownedByActiveRound` 内 |
| 旧轮收尾提示竞速（如 stop 的「已请求取消」） | 竞速存在但方向无害 | 旧轮迟到写已被 R6 拦截（`isCurrentRound` 判定）；旧轮**仍活跃**时的写点（O7 既有行为）与守卫命中（`loading=true`）同时窗内并存的最坏结果是提示内容先后覆盖，非反馈丢失 |
| 面板层 `inputText.value = ''`（`AiPanel.vue:523`） | 否 | notice 分支在清空之前 `return`，草稿保留（q1 实测） |

结论：无相邻抹除路径；q1 实测 notice 文案稳定显示。【实测（代码级）+q1 实测】

## 5. K11 q3「失步窗口=1 微任务 tick、用户不可达」 — **结论成立；严重度维持「低」，建议关闭评论按红队口径措辞**

- **定性强度**：复核报告 §9.4 主动把该结论降级为【实测+推断】——窗口宽度（微任务级、`windowTick.loading=false`）为实测，「用户不可达」为推断。红队认可这一标注纪律，且补充其推断依据在 JS 事件循环模型下**成立**【实测（代码级）】：用户输入（键盘/点击）是宏任务，其处理器内的同步代码与微任务队列必然在同一宏任务内全部耗尽，下一个用户事件才会派发——1 微任务 tick 的窗口对用户输入在原理上关闭。before 相位证据（`windowTick` 与 `microtaskAfterSend` 的对比）结构完整。
- **严重度影响**：issue 原症状（静默丢弃）实测不可达（q1 真实 Enter/点击均无第二次 HTTP）；真缺陷（G2/G3 失步）的窗口又实测为微任务级用户不可达 ⇒ 本 issue 的**用户可见危害 ≈ 0**。「低」已是底档，无需再降；修复应定性为**纵深防御 + 状态收敛**（q6 双渲染、q7 孤儿消息是真实可构造的修复收益，非空转）。
- **关闭评论建议措辞**：写明三层事实——①「静默丢弃」在现 UI 不可达已实测；②失步窗口实测为微任务级且用户不可达；③本批修复的实际收益在 reattach 连点（双渲染）、resetSession（孤儿帧）、409 重挂等**程序化/边缘路径**的状态收敛——避免读者误以为修复了一个用户级可复现缺陷，也避免反向误读「修复无价值」。

## 6. K12 G6 死守卫真死 — **PASS**

红队独立核实全部调用链【实测（代码级）】：`restoreSuspendedSnapshot` 仅 `loadHistory:417/:446` 两处调用；`loadHistory` 仅 `AiPanel.vue:605` onMounted（且 `!ai.messages.length` 时才调）；AiPanel 常驻挂载无 v-if（复核报告已核 App.vue）；`messages=[]` 仅出现在 `resetSession`（同时清 loading）⇒ 到达守卫时 `loading` 恒 false，守卫不可触发。删除安全，q9 after-only 回归覆盖重建路径行为正常。

红队补一条前提性观察（S4 知悉）：该结论依赖「AiPanel 永不移除」「loadHistory 不新增调用点」。即便未来出现「resetSession 后立即 loadHistory」的新调用形态，当前实现（resetSession 已 abort + 清 loading）下删除守卫仍无行为差异——结论稳健。

## 7. K13 整体回归面 — **PASS；桩 E2E 28 用例不必本地补跑**

**未覆盖交互路径搜查**（红队在施工/复核报告之外补查的路径）：

- `formExpiryTimer` 超时回调（`generative_form` 本地过期 → `cancelGenerativeForm(true)` 回灌）：不写 `loading`/`currentAbort`/`activeRoundHostId`，不受本批改动影响 ✓
- `submitGenerativeForm` 的 `activeForm` 竞态守卫（`:975` 附近）：不依赖轮次身份，不受影响 ✓
- `stop()` 的 `notice='已请求取消本轮对话'`（`:1038`）与旧轮 `onClose` 的写入顺序：O7 已登记的既有行为，非本批引入 ✓
- `probeHealth`/`loadHistory`：不动 loading ✓
- 结论：本批改动面（三入口 + handleFrame + stop/resetSession）之外的前端状态写点与本批身份判定机制无未审交互。【实测（代码级）】

**桩 E2E 是否本地补跑**：不要求。依据【实测（代码级）】：`scripts/verify-e2e.ps1` 是**纯后端 HTTP+SSE 断言**（grep 证实无浏览器/前端 loading/notice/断言，无 chrome/yarn 驱动）；#5 为纯前端改动，不进入其覆盖面；#4 面的关键断言（IMPORT/PUBLISH 不披露 `start_export`）已由 s3 端点 E1 口径实测间接覆盖。CI 会跑，本地补跑收益 ≈ 0 且需完整夹具与端口编排。

**S4 登记（红队新发现）**：后端提示词变化（#4 的第 10 条 + 页面中文文案）与 e2e 的**会话式用例**（GF1-GF6 用自然语言指令驱动真模型）存在弱耦合——模型行为若受第 10 条影响（如主动 stop-and-wait），e2e 可能出现非确定性失败。当前后端 357 例绿，风险低；CI 首跑后如遇 e2e 偶发红，优先排查该耦合再定性为环境抖动。

## 8. K14 之 #5 侧（与 #4 的叠加效应）— **PASS**

见 #4 侧报告 §7（同一分析，双向引用）：改动面零交集；运行时方向互补（#5 保证「继续」消息发出时的轮次干净，#4 引导模型把冻结失真转化为明确的「请回复继续」）；条款 9/10 触发条件互斥，无叠加冲突通路。【实测（代码级）+旁证】

---

## 9. 红队新发现（三报告均未覆盖）

- **N1【实测】S3（工作区管理，同 #4 侧报告 N1，此处复述以防单读）**：当前工作区除 #5 面 2 文件外，另含 #4 面 8 文件与**第三批未申报改动**（`README.md` + `application.yml`，内容经红队抽验无害：删的是无 Java 绑定的 `app.server.*` 死键，README 与 vite.config/脚本事实源一致）。所有施工/复核报告的工作区快照均未登记第三批 ⇒ 提交官必须重新生成完整 `git status` 逐一归属，不能以任何报告内快照为准。
- **N2【实测】S4（脱敏）**：`recheck-rerun/dev-recheck.log:6` 含 `192.168.<x>.<y>:5212`（局域网地址前两段未脱敏）。且 issue5 复核报告 O6 的**出处标注有误**：它称泄漏在 `evidence/dev-before.log`/`dev-after.log`，实测这两个文件已是 `<LAN_IP>` 占位符（且其 mtime 晚于复核报告成文时间，疑为报告指出后追加脱敏），而 `recheck-rerun/` 目录未纳入其脱敏抽查范围、`dev-recheck.log` 漏网。处置：`issue5-reports/` 目录外发前清理该行（及同类）；O6 的文件定位应在关闭材料中更正。

---

## 10. 总判定与建议

**总判定：#5 修复批放行**（可进入提交官流程）。

- R1-R6 + G6 逐 hunk 坐实，无越界（`canSend` 语义、挂起期禁用、`notice` 结构、后端契约均未动）；身份判定机制对全部复位路径封闭（K7）；两个被点名攻击的边角（K8 runSeq、K9 无超时）一个定级 S4 登记、一个攻击不成立；q1-q9 证据真实可复现（recheck-rerun 与归档唯一差异为 session UUID）。
- 无 S1/S2 项。分级汇总：S3×1（N1 工作区归属）；S4×5（K8 runSeq 前移、K9 前瞻观察、K12 前提知悉、K13 e2e 弱耦合、N2 脱敏清理）。

**建议**（提交官动作项，均非阻断）：
1. 与 #4 批同批提交前，重新生成完整工作区清单并逐一归属（含第三批 README/application.yml，单独裁决）。
2. 关闭评论按 K11 口径措辞（三层事实 + 修复实际收益定位），并把 K8（runSeq 判定前移）与「notice 单槽位升格」（施工报告 §5.3 遗留）并入下批观察清单。
3. `issue5-reports/` 外发前清理 `dev-recheck.log` 的局域网地址，更正复核报告 O6 的文件定位。
