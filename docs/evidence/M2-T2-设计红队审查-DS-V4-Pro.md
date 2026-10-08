# M2-T2 表单态一致性包 —— 设计红队审查（DeepSeek V4 Pro）

> 角色：反方审查 / 红队。职责是推翻，不是附和。只审查、不改被审代码；本文档为唯一新增产物，未提交、未执行任何 git 写操作。
> 审查对象：本对话产出的六张设计决策卡 **T2-D#1~#6**（服务端权威+唯一投影 / 刷新续填+自动 reattach / activeForm 降级聚焦指针 / status 补 cancelled+帧广播 / 409 DUPLICATE 中性终态 / 不引 vitest）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `9de199d`。
> 事实基线：`docs/evidence/GFd-双FormRenderer冒烟验证.md`、`GFc-脚本红队审查-DS-V4-Pro.md`、`GFc-端到端执行验证.md`、`GFb-红队审查-DS-V4-Pro.md`、`GFb-复核验证-GLM-5.3.md`，以及前后端源码（见各条证据行号）。
> 证据等级：**【实测】** = 本次实际读码确认；**【推断】** = 由代码路径推演、未实跑；**【假设】** = 需执行棒/冒烟实测才能确认。
> 脱敏：本文所有路径相对 `<REPO_ROOT>`（不出现盘符）；无密钥、无用户名、无内网 IP。
> 审查日期：2026-10-08。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 问题总数 | **14**（阻断 ×2 / 严重 ×5 / 建议 ×7） |
| 最高严重度 | **阻断（Blocker）** |
| 总结论 | **打回，须先补齐两处阻断级缺口再施工** |

一句话：T2 包把「刷新续填」「双卡收敛」「取消后消息级 status 收敛」三件事绑成一次「单一事实源」重构，方向成立；但 **D#2 选择的“自动 reattach 重建表单卡”机制，与既有的 `loadHistory` 历史映射在结构上相互冲突**（双重渲染 + 写后写竞态，直接击穿 D#1 的“唯一投影”），且 **D#2 新端点 `runs/current` 在本包最需要覆盖的边界场景（已超时 / 已被他端提交 / 进程已死）恰恰无法定位当前 run**。这两处不补，六张卡里的核心两张（D#1、D#2）无法按现规格落地。

---

## 1. 问题清单

### 攻击面 a —— 唯一投影的并发/时序

#### A1（阻断）｜自动 reattach 的回放帧与 `loadHistory` 历史映射双重渲染 —— “唯一投影”按现规格不可达成

- **位置**：`frontend/src/stores/ai.ts:343-396`（loadHistory）、`frontend/src/stores/ai.ts:520-566`（reattachActive）、`frontend/src/stores/ai.ts:528`（appendAssistantMessage）、`backend/.../run/SseChatEmitter.java:358-375`（replayLocked）、`backend/.../web/AiController.java:292`（replayAndAttach）。
- **证据**【实测 + 推断】：
  - `loadHistory` 把 `/api/ai/history/{sessionId}` 的每条 assistant 消息映射为带 `toolRuns` 的展示消息（`ai.ts:373-393`，`toolRuns` 由 `toolCalls` 重建，行 375-382），同时 `pendingCall: null`（行 389）。
  - `reattachActive` 每次 `appendAssistantMessage('（正在重挂…）')`（行 528）**新增一条**助手消息，随后把 reattach 回放的帧经 `handleFrame(frame, assistant.id)` 灌进这条新消息。
  - 服务端 `replayLocked` 回放时**只跳过 delta/heartbeat**，`tool_start`/`tool_result`/`frontend_tool_request`/`suspended`/`confirm_request`/`done` 全部保留（`SseChatEmitter.java:358-375`）。也就是说，reattach 回放会把**整轮工具卡片与挂起卡重建一遍**。
  - 刷新后 `runSeq` 是模块级空对象（`ai.ts:209` 初值），`reattachActive` 传 `lastSeq=undefined`（行 527）→ 服务端**全量回放**，孤儿过滤失效（`SseChatEmitter.java:378-387`）。
  - 结论：刷新 → `loadHistory` 已把历史里的那条“发出 tool_call 的 assistant 消息 + 工具卡”渲染出来，D#2 再自动 reattach 又会新增一条助手消息并回放同一批 `tool_start`/`tool_result`/`frontend_tool_request` → **同一工具调用渲染两遍**（一张在历史消息里，一张在新挂消息里），与 D#1“同 toolCallId 至多一条挂起卡”直接矛盾。
  - 更进一步的竞态：`loadHistory` 的 `this.messages = history.messages.map(...)`（行 373）是**无条件整体替换**。若它发起的 `aiApi.history()` 响应晚于 reattach 已回放完成的帧（两条异步请求无排序约束），替换动作会把 reattach 刚重建出的消息与挂起卡**整体冲掉** —— 投影写丢。
- **复现路径（推断）**：AI 轮处于表单挂起 → 刷新 → `loadHistory` 拉历史 + `runs/current` 命中 → 自动 reattach 全量回放 → 面板出现“历史工具卡 + 重挂工具卡”两套，或（响应乱序时）挂起卡闪现后消失。
- **修复方向**：二选一并写进 D#1/D#2 规格——(a) `loadHistory` 在决定自动 reattach 时**不再渲染历史里的 `toolRuns`/挂起段**（只渲染正文，工具态全部交给 reattach 回放）；或 (b) 放弃“自动 reattach 重建”，改由 `loadHistory` 直接调用 `runs/current` 拿 pending 条目快照，用单一 `upsertPendingCall` 就地重建卡（不新增消息、不回放帧）。无论哪种，都必须显式定义 `loadHistory` 与 reattach 的先后/合并规则。

#### A2（严重）｜本地 cancel 与 `frontend_tool_result` 帧的终态“谁赢”未定义

- **位置**：`frontend/src/stores/ai.ts:723-738`（cancelGenerativeForm）、`frontend/src/stores/ai.ts:950-975`（frontend_tool_result 帧处理）。
- **证据**【推断】：
  - `cancelGenerativeForm` **同步**置 `form.status='cancelled'`（行 728）后 `await` 取消回灌 POST；POST 与 SSE 帧是两条独立通道。
  - 若此时 `frontend_tool_result` 帧先/后到达（例如他端已提交 → 条目 `FRONTEND_RESULT`、帧 `ok=true`），帧 handler 会 `upsertToolRun`（行 957-964）并把 `activeForm` 清空（行 968-972）；但 `pendingCall.status` 的最终值取决于“本地 cancelled 写”与“帧 status 广播”的落点先后。
  - D#4 把帧 handler 从“置 null”改为“按帧 status 广播”后，会出现两种相反终态：本地先置 `cancelled`、帧后到 `succeeded`（他端先提交成功）时，卡片先显示“已取消”再翻成“已成功”——语义上“已成功”才对（后端权威），但设计卡未写死“帧终态恒覆盖本地 cancel”这一 winner 规则，实现极易写成“后写覆盖先写”的竞态产物。
- **影响**：不定义 winner → 同一 toolCallId 在不同消息/不同时刻显示互相矛盾的终态（一条 cancelled、一条 succeeded），正是 D#4 要消灭的“消息级不一致”，却在竞态路径上复活。
- **修复方向**：在 D#4 明确“服务端帧 status 为最终裁决，本地 cancel 只是乐观置位”，并给 `frontend_tool_result` 帧 handler 加“已终态（succeeded/rejected/blocked）不降级为 cancelled”的守卫（见 C1）。

#### A3（建议）｜`runSeq` 刷新即清零，差量去重对“刷新自动 reattach”完全失效

- **位置**：`frontend/src/stores/ai.ts:209`（runSeq 初值）、`frontend/src/stores/ai.ts:527`（lastSeq 读取）、`frontend/src/stores/ai.ts:836-843`（seq 记录）。
- **证据**【实测】：`runSeq` 是 store state 里的空对象（行 209），只在**同页签运行期**累积（行 841）；刷新后从零开始。D#2 的“刷新 → 自动 reattach”恒走 `lastSeq=undefined` → 全量回放（行 527），T6 差量补发机制对目标场景**完全不起作用**，进一步放大 A1 的重复渲染面。
- **影响**：不是独立 bug，但说明 D#2 不能依赖既有 T6 增量去重来缓解双重渲染，设计未提及这一点。
- **修复方向**：若坚持 reattach 路线，需把 `runSeq` 也进镜像/sessionStorage，或直接采用 A1 的“就地重建”方案绕开回放。

---

### 攻击面 b —— D#2 自动 reattach 的边界

#### B1（阻断）｜`runs/current` 端点在“已超时 / 已被他端提交 / 进程已死”时无法定位当前 run，降级口径未定义

- **位置**：`backend/.../session/SessionGate.java:116-127`（currentRunId）、`backend/.../run/ResumeService.java:118-168`（listRuns）、`backend/.../run/RunStore.java:173-193`（allRunIds）、`backend/.../web/AiController.java:186`（会话锁在轮终态释放）。
- **证据**【实测 + 推断】：
  - 后端当前**没有**“按 sessionId 找当前挂起 run”的现成索引。唯一接近的是 `SessionGate.currentRunId`（行 116-127），它读进程内 holder 或 Redis 锁值 `ai:session:lock:<sessionId>`。
  - 该锁只在**活轮**存活：`chat` 的 finally 块在轮终态释放锁（`AiController.java:186`）；Redis 锁带 TTL + watchdog 续期（`SessionGate.java:137-156`），**进程存活才续期**。因此：
    - 挂起已**超时**（后端已落 `TIMEOUT`、轮已终态）→ 锁已释放 → `currentRunId` 返回 null；
    - 已被**他端提交**（条目 `FRONTEND_RESULT`、轮已 `settle` 回 RUNNING 后 done）→ 锁已释放 → null；
    - 进程**已死**（僵尸/待重启续跑）→ watchdog 停止续期 → 锁过期 → null。
  - 于是 D#2 端点在本包**最需要正确处理的场景**（“挂起已超时/已被他端提交”正是 GFd 裁决与 GF-B 问题要收敛的边界）反而拿不到 runId，设计卡未定义这三类分支的降级口径：是回退扫描 `GET /runs`（`ResumeService.listRuns` 含 sessionId，但它是 `allRunIds()` 全量扫 `ai:runs` 集合，无 session→run 索引，O(全部 runId)）？还是把历史里的挂起卡标 `expired`（现有 `ai.ts:363-371` 只对“历史为空”分支做）？
- **影响**：若实现只包一层 `SessionGate.currentRunId`，则“刷新时恰逢挂起刚超时/刚被他端提交”会静默拿不到 run，自动 reattach 落空，历史里的未决挂起卡以 `pending` 残留（不收敛、不标 expired），回到 GF-B 想修的原始症状。
- **修复方向**：D#2 必须写清“current 定位失败”的分支语义，并给出 session→run 的查询成本口径（新增索引键 vs 全量扫）；至少覆盖“条目已决但快照仍 SUSPENDED”的中间态（见 D1）。

#### B2（严重）｜多标签页同会话：输入值互不可见 + “已提交但另一页仍在填”的错配提示未定义

- **位置**：`frontend/src/stores/ai.ts:70-82`（sessionStorage 页签级 sessionId）、`backend/.../session/SessionGate.java:70-94`（acquire）、`backend/.../run/SseChatEmitter.java:53,105-110`（多订阅者扇出）。
- **证据**【推断】：
  - `sessionId` 存 sessionStorage（`ai.ts:6,70-82`），**页签级隔离**，故常规“开两个标签页”各自是独立会话，不冲突。
  - 但若同一 sessionId 被两个上下文持有（复制带 sessionId 的 URL、或未来 sessionId 上云），后端 `SessionGate.acquire` 用 Redis 锁做**跨实例单值**（行 77-87），而 `SseChatEmitter` 天然支持**多订阅者扇出**（`subscribers`，行 53、`attach` 行 105-110）。两个页面各自 reattach 会挂到同一 emitter 上，各自维护独立的前端投影。
  - 后果与 GFd §3.C 同构但跨页面：两页 FormRenderer 的输入值是组件本地 `ref`（GFd 已证），一页提交 → 后端幂等封顶（先到者胜）→ 另一页收到 `frontend_tool_result` 帧后把自己还在填的卡收敛。此时“哪页的值被采纳、另一页为何被无声收敛”没有任何提示口径，且 D#3“唯一实例”+ D#4“帧广播”在多标签页下会跨页产生“一张卡被远程收敛、输入瞬间作废”的体验。
- **影响**：多页共享会话是现实的 UX/正确性边角，设计卡对“跨页投影一致性”零提及。
- **修复方向**：D#2/D#4 明确多订阅者下的收敛提示（如帧到达且本地 `activeForm` 仍 filling 时给“已在其他窗口提交”提示），或声明本包不承诺同 session 多页共享（并补文档化约束）。

#### B3（建议）｜刷新命中“已超时”轮时，reattach 回放会瞬时重挂一张已死表单卡

- **位置**：`backend/.../gate/ConfirmGate.java:225-251`（expire 落 TIMEOUT + 发 `frontend_tool_result`）、`backend/.../run/SseChatEmitter.java:358-375`（回放含 `frontend_tool_request`）、`frontend/src/stores/ai.ts:937-945`（openGenerativeForm）。
- **证据**【推断】：若 D#2 在“后端已 TIMEOUT”后仍按“存在 runId”走 reattach（终端回放），归档里 `frontend_tool_request` 会先被回放 → `handleFrame` → `openGenerativeForm` 重新武装一张 `filling` 表单卡（行 940-943）与本地超时计时器（行 678-684），直到回放到 `frontend_tool_result(TIMEOUT)` 才收敛。用户会看到一张“已超时表单”短暂复活。
- **影响**：瞬时 UI 抖动 + 一次多余的本地计时器武装/中和；非正确性缺陷，但与本包“收敛为单一事实源”的目标相悖。
- **修复方向**：reattach 回放进入 `frontend_tool_request` 时，先查该 toolCallId 的后续帧/条目状态，已决则不重新 open；或 `openGenerativeForm` 加“该 toolCallId 已有终态帧则不挂起”的守卫。

---

### 攻击面 c —— D#4 广播语义与 status 域扩充

#### C1（严重）｜“同 toolCallId 全标 cancelled”会误伤“一条 pending、一条已 succeeded”的历史场景

- **位置**：`frontend/src/stores/ai.ts:877-897`（tool_result 帧 → toolRun 终态）、`frontend/src/stores/ai.ts:950-975`（frontend_tool_result → toolRun）、`frontend/src/types/ai.ts:161`（ToolRunStatus）vs `frontend/src/types/ai.ts:213`（PendingToolCall.status）。
- **证据**【实测 + 推断】：
  - 前端存在**两个独立投影**：`toolRuns[].status`（域 `pending|running|succeeded|failed|rejected|expired|blocked`，行 161）与 `message.pendingCall.status`（域 `pending|running|approved|rejected|expired`，行 213）。二者由不同帧分别写入。
  - D#4 只说“`frontend_tool_result` 帧 status 广播到所有持该 toolCallId 的消息”，**未区分广播的是 `pendingCall.status` 还是 `toolRun.status`**。若广播落到 `toolRun.status`，则“同一 toolCallId 在一条消息里已被 `tool_result(ok=true)` 标为 succeeded、在另一条消息里 pendingCall 仍 pending”的混合场景（正是 GFd 观测到的双持形态）会把那条已 succeeded 的 `toolRun` 覆盖成 cancelled —— 语义回退。
  - 即便广播只落到 `pendingCall.status`，`upsertToolRun`（行 957-964）里 `localTerminal` 只认 `failed|rejected|blocked`（行 955-956），**不含 succeeded**，故“frame.ok=true 但本地 toolRun 已是 succeeded”之外的组合也可能被错误改写。
- **影响**：D#4 若不写清“广播只作用于 pendingCall 投影、且已终态不降级”的双守卫，会把一个“收敛消息级 pendingCall”的修复，扩大成“误伤工具卡终态”的回归。
- **修复方向**：D#4 明确广播对象 = `pendingCall.status`；并加“目标消息该 toolCallId 的 toolRun 已是 succeeded/rejected/blocked 时不改写”。

#### C2（建议）｜status 域补 `cancelled` 对既有镜像反序列化与前后端状态名映射的兼容缺口

- **位置**：`frontend/src/types/ai.ts:213`（前端 PendingToolCall.status）、`frontend/src/types/sse.ts:22-31`（前端 PendingStatus 联合）、`frontend/src/stores/ai.ts:103-134`（镜像写/读）、`frontend/src/stores/ai.ts:363-371`（空历史降级只认 pending）。
- **证据**【实测】：
  - 前端 `PendingStatus` 联合（`types/sse.ts:22-31`）只有 `PENDING/APPROVED/REJECTED/TIMEOUT/FRONTEND_RESULT/CANCELLED/EXECUTED/BLOCKED`，**没有**后端实际会下发的 `FRONTEND_CANCELLED`，也没有 D#4 要新增的小写 `cancelled`。`SseFrontendToolResultFrame.status` 是 `PendingStatus | string`（`types/sse.ts:196`）宽类型兜底，但 `frame.status` 到 `pendingCall.status` 的新映射（若 D#4 要用帧 status 驱动）需要新增 `FRONTEND_CANCELLED → cancelled` 的翻译层，设计卡未提。
  - 镜像 `writeMirror` 序列化 `pendingCall`（`ai.ts:127`）进 sessionStorage；`readMirror` 是裸 `JSON.parse`（行 109）。新增 `cancelled` 后，**旧镜像**里仍存 `pending`/`expired`，而 `loadHistory` 空历史分支只把 `status==='pending'` 降级为 `expired`（行 365），`cancelled` 不进该分支 → 旧镜像中“已取消但未落终态”的卡可能以 `pending` 残留、不收敛。
- **影响**：跨版本刷新（升级前留下镜像 + 后端已取消）的边角不收敛；属迁移兼容缺口。
- **修复方向**：D#4 明确前后端状态名映射表与“旧镜像非 pending 状态”的降级规则。

#### C3（建议）｜`pendingToolCall` getter 对非 `pending` 终态提前短路，会漏掉更早的待决调用

- **位置**：`frontend/src/stores/ai.ts:214-228`（getter）。
- **证据**【实测】：getter 从最新消息向前扫（行 215），遇 `message.pendingCall` 非 null 但 `status !== 'pending'` 时 `return null`（行 223-224）。D#4 若让 `pendingCall` 携带 `cancelled` 终态而非置 null，则“消息里残留一条 cancelled 的 pendingCall”会**提前终止扫描**，漏掉更早消息里真正 `pending` 的调用。
- **影响**：`pendingConfirm`/`canSend` 输入区提示在“多卡 + 某卡已 cancelled”场景下误判“无待处理”。
- **修复方向**：getter 改为“跳过非 pending 终态继续向前扫”，或 D#4 彻底采用“终态即从 pendingCall 投影移除”（与置 null 等价、但保留 status 供展示的另一投影），避免两者语义冲突。

---

### 攻击面 d —— 服务端配套改动是否被低估

#### D1（严重）｜reattach 的终态判定只看快照 status，漏掉“快照 SUSPENDED 但条目已决”的中间态

- **位置**：`backend/.../web/AiController.java:292,298-301`（isTerminal）、`backend/.../run/RunStore.java:202-217`（saveSuspended 写 SUSPENDED）、`backend/.../gate/SpToolCallingManager.java:459-470`（settle 回 RUNNING）。
- **证据**【实测 + 推断】：
  - `events` 端点用 `snapshot.getStatus()` 判终态（`AiController.java:298-301`：仅 `DONE/FAILED/CANCELLED` 算 terminal）。挂起中快照是 `SUSPENDED`（`RunStore.java:215`），提交/超时后由 `settle`（`SpToolCallingManager.java:459-470`）在工具全部结算后回 `RUNNING`，再产出 done。
  - 于是存在中间态：挂起条目已被他端提交为 `FRONTEND_RESULT`（`ConfirmGate.submitFrontendResult` 落库），但快照仍 `SUSPENDED`（尚未 settle/未 done）。此时 `runs/current` 拿到该 runId、`events` 判定为非终态 → `replayAndAttach(..., terminal=false)`（`AiController.java:292`）**挂订阅继续等实时帧**，而该轮的写出器可能已无活写者（等待方已醒、即将 settle done，或进程已死）→ 前端以为在“续收”，实际等不到终帧。
  - D#2 依赖的“自动 reattach 续收”没有覆盖这条中间态；服务端 `isTerminal` 需扩入“快照 SUSPENDED 且无 PENDING 未决条目”的判定，或 `events` 回放时参考挂起条目是否已决。
- **影响**：refresh 恰逢“他端刚提交、轮尚未 done”时，自动 reattach 可能悬在一条不再产帧的流上，直到 SSE 超时/断流，表现为“重挂后迟迟不结束”。
- **修复方向**：D#2 或配套服务端改动把“终态判定”与“未决外部输入判定”对齐（复用 `ResumeService` 的 `unresolvedExternal` 口径，行 151-155）。

#### D2（建议）｜“按 session 找当前挂起 run”需要新的服务端索引，成本口径未在卡内交代

- **位置**：`backend/.../session/SessionGate.java:116-127`、`backend/.../run/ResumeService.java:118-140`、`backend/.../run/RunStore.java:173-193`。
- **证据**【实测】：现无 session→run 索引；`SessionGate` 锁值只有 runId 且仅活轮存活；`listRuns` 逐 `allRunIds()` 读快照再取 `sessionId`（`ResumeService.java:128`），是 O(全部 runId) 的全量扫。D#2 端点若只做前端，服务端落地必然触及新查询路径，卡片未量化成本与 TTL 边界。
- **影响**：设计低估了服务端配套量（攻击面 d 的正面证据）。
- **修复方向**：D#2 明确端点实现（新 Redis 键 `ai:session:current:<sessionId>` vs 复用锁 vs 扫 runs）与并发/TTL 语义。

---

### 攻击面 e —— D#6 测试缺口

#### E1（严重）｜三域前端逻辑（镜像/loadHistory/帧投影/activeForm 收敛）零 CI 门禁，冒烟不进 CI 的残余风险被低估

- **位置**：`frontend/package.json:6-11`（无 test 脚本、无 vitest）、`.github/workflows/ci.yml:45-71`（frontend job 仅 typecheck + build）、`.github/workflows/ci.yml:86-213`（e2e job 为后端 HTTP 脚本，不驱动浏览器）。
- **证据**【实测】：
  - `package.json` 的 scripts 只有 `dev/build/typecheck/preview`（行 6-11），**无任何单测框架**（与 D#6“不引 vitest”一致）。
  - CI `frontend` job 只跑 `vue-tsc --noEmit` + `vite build`（`ci.yml:67-71`）。类型检查**完全不覆盖**本包要改的状态机/竞态逻辑（A1 双重渲染、A2 终态竞争、C1 广播误伤、B2 多页收敛全是运行时行为）。
  - CI `e2e` job 是 `scripts/verify-e2e.ps1`（`ci.yml:190-199`），纯 HTTP 客户端断言后端帧序列与回灌，**不启动浏览器、不验证任何前端渲染态**。
  - D#6 把验证押在“后端单测 + E2E 服务端侧新用例 + CDP 冒烟（不进 CI）”。前两者验证的是**后端契约**，唯一能验证前端投影的 CDP 冒烟（GFd 同款）不进 CI —— 即本包最易错、改动最大的三域前端逻辑，在合流门禁里**零自动回归**。
- **影响**：A1/C1/B2 这类竞态在 CI 上完全不可见，只能靠一次性手工冒烟“目测”。改动量（单一事实源重构 + activeForm 指针化 + FormRenderer 唯一实例）与验证强度严重不匹配，残余风险被 D#6 用“不进 CI 的冒烟”名义上覆盖、实质上未闭环。
- **修复方向**：至少把 CDP 冒烟脚本化并纳入一个**非阻塞或 nightly job**（即使不引 vitest，也可用现有 CDP 方式驱动真实 dev server 断言“单卡/无重复卡/取消后 status 收敛”三件事）；或接受“不引 vitest”但把这三条写成明确的、可重复执行的冒烟断言清单，而非一次性目测。

#### E2（建议）｜“E2E 服务端侧新用例”无法覆盖前端投影，验证对象与改动对象错位

- **位置**：`.github/workflows/ci.yml:190-199`、`docs/evidence/GFd-双FormRenderer冒烟验证.md:§2`（CDP 为唯一前端实测手段）。
- **证据**【实测 + 推断】：E2E 脚本走 HTTP，能断言“后端在超时/取消后发 `frontend_tool_result(ok=false)`、409 幂等”等契约，但**断言不了**“刷新后单卡、取消后所有消息的 pendingCall.status 收敛、无瞬时死卡复活”这些纯前端态。这些恰恰是 M2-T2 条目 4 的三个交付目标。D#6 的验收组合里，服务端侧 E2E 对“三域前端态”的贡献≈0。
- **影响**：D#6 的验收矩阵存在“验证对象错位”，需要显式承认前端态只能靠（不进 CI 的）CDP 兜底，并补出对应的可重复断言。

---

### 攻击面 f —— scope 是否过大 / 可否拆最小闭环

#### F1（建议）｜“同源一并修”把三个可独立交付的子问题绑成一次重构，D#2 的后端端点成为其余修复的前置依赖

- **位置**：`docs/M2-排期计划.md:23`（三子问题捆绑为“一并设计一次施工”）。
- **证据**【推断】：
  - 三个子问题**耦合度不同**：双卡收敛（D#3 唯一实例）是纯前端渲染去重；取消后 status 收敛（D#4）是纯前端帧处理小改；刷新续填（D#2）涉及**新端点 + 自动 reattach 的后端改动 + 与 loadHistory 的合并语义**。
  - 绑定后，D#2 的 `runs/current` 端点设计与 reattach/loadHistory 合并语义一旦被推翻（本审查 A1/B1 即为此），会**拖累 D#3/D#4 两个本可安全、独立合流的前端修复**，无法拆出“双卡收敛 + 取消收敛”的最小前端闭环先行上线。
- **影响**：交付粒度与“每任务包独立可交付、独立验证”的排期原则（`M2-排期计划.md:10`）冲突；建议先落地 D#3+D#4（纯前端、风险低、可直接由 CDP 冒烟验证），D#2 单独立项、先定端点语义再施工。
- **修复方向**：把 T2 拆成 T2a（前端投影唯一化：D#3+D#4）与 T2b（刷新续填：D#2 + D#1 的服务端权威对齐），各自独立验收。

---

## 2. 无问题攻击面排查方法（未发现缺陷，但需在施工时验证）

1. **服务端“同 toolCallId 至多一条”的唯一性**：已由 Redis `HASH field=toolCallId` 结构保证（`RunStore.putPending` 用 `opsForHash().put`，行 219-228），无并发写重风险。**排查方法**：新增一条后端单测断言“连续两次 `putPending` 同一 toolCallId 后 `pendings()` 长度仍为 1”，把结构保证固化为契约测试。
2. **镜像不持久化 `activeForm`、输入值不恢复**（D#2 明确接受）：与现有架构一致——`activeForm` 不进镜像（`ai.ts:200-204` 注释 + `writeMirror` 只留 `pendingCall` 字段，行 127），输入值存 FormRenderer 本地 `ref`（GFd §3.C）。**排查方法**：CDP 刷新冒烟断言“刷新后表单卡重建且字段为空”，作为 D#2“已输入值不恢复”的显式验收点。
3. **服务端帧对多订阅者扇出**：`SseChatEmitter.emit` 遍历 `subscribers` 广播（行 442-444），本身正确。**排查方法**：双标签页冒烟断言“两页同刻收到同一 `frontend_tool_result`”，并复核 B2 的收敛提示是否落地。

---

## 3. 总结论

**打回（须先补齐 A1、B1 两项阻断级缺口）。**

- 方向与动机成立：把“单一事实源”作为 T2 的统一目标、以及“服务端权威、前端唯一投影”的分层，是对 GFd/GF-B 已证缺陷的正确归纳；D#3（聚焦指针+唯一实例）与 D#5（409 中性终态）本身无致命问题。
- 但 **D#2 选择的“自动 reattach 重建表单卡”机制在结构上自相矛盾**：它与 `loadHistory` 的历史映射产生双重渲染，且依赖的全量回放会放大重复（A1/A3）；**`runs/current` 端点在本包目标边界（已超时/他端已提交/进程已死）无法定位当前 run**（B1/D1），降级口径缺失。
- 叠加 **D#6 把本包最高风险的三域前端竞态交给“不进 CI 的冒烟”**（E1），以及 **D#4 广播语义未区分 pendingCall 与 toolRun 两个投影**（C1），当前规格无法支撑“一次施工即达成单一事实源”的验收。
- 建议最小动作：先拆出 T2a（D#3+D#4 纯前端唯一投影化，CDP 冒烟脚本化）独立交付；T2b（D#2 刷新续填）单独定端点语义（session→run 索引、终态判定、loadHistory 合并规则）后再施工。

---

## 4. 裁决结论（指挥官 K3）

裁决日期 2026-10-08，指挥官 K3。总体：接受打回。采纳 F1 拆分建议，T2 拆为 T2a（纯前端唯一投影化，先行交付）/ T2b（刷新续填，先定端点语义后施工）两包；原六卡 T2-D#1~#6 签字继续暂停，返修后重新出卡（T2a-D#1~#5、T2b-D#1~#3）请业务方签字，签字后旧卡作废留痕。逐条处置：

- A1【阻断】采纳。T2b 放弃"全量回放重建"，改"快照重建+增量续收"：loadHistory 先行完成（historyLoaded 前不触发重建）；挂起卡由 pending 条目快照经唯一投影 upsert 就地重建，不 appendAssistantMessage 新起消息；续收以 lastSeq=归档最大 seq 挂载，只收新帧。
- A2【严重】采纳。定终态单调规则：服务端帧 status 为最终裁决，本地 cancel 仅乐观置位；frontend_tool_result 帧 handler 加"已终态（succeeded/rejected/blocked/cancelled）不降级"守卫。
- A3【建议】采纳，随 T2b 快照方案消解（不依赖回放差量去重，runSeq 无需入镜像）。
- B1【阻断】采纳。T2b 新建 session→run 索引（Redis 键 ai:session:current:<sessionId>，挂起时写、轮终态清、TTL 与锁 watchdog 对齐，O(1)）；runs/current 返回结构化状态（runId+快照 status+pending 条目逐条状态+unresolvedExternal 计数），明确定义已超时/他端已提交/进程已死三分支降级口径（前端按条目状态标 expired/已提交展示，不留 pending 残留）。
- B2【严重】部分采纳。本包不承诺同 session 多页签共享（写入 T2b 规格约束并文档化）；低成本缓释并入 T2a：frontend_tool_result 帧到达且本地 activeForm 仍 filling 时提示"该表单已在其他窗口提交"。
- B3【建议】采纳，随 T2b 快照方案消解（无回放即无死卡复活）；另加防御守卫：openGenerativeForm 前查该 toolCallId 已有终态则不挂起。
- C1【严重】采纳。D#4 广播对象写死为 pendingCall.status 投影；双守卫：目标消息 toolRun 已终态不改写；localTerminal 集合补 succeeded。
- C2【建议】采纳。状态映射表进规格：补 FRONTEND_CANCELLED→cancelled 翻译，types/sse.ts PendingStatus 联合补 FRONTEND_CANCELLED；镜像兼容规则：旧镜像中非 pending 残留状态按终态展示、不再参与 pending 扫描。
- C3【建议】采纳。pendingToolCall getter 改为跳过非 pending 终态继续向前扫描。
- D1【严重】采纳。T2b 终态判定对齐 ResumeService unresolvedExternal 口径，消除"快照 SUSPENDED 但条目已决"中间态误判。
- D2【建议】采纳。索引成本口径：新 Redis 键 O(1) 读写，TTL 与锁 watchdog 对齐；否决全量扫 allRunIds 方案。
- E1【严重】部分采纳。维持不引 vitest；风险缓释升级：CDP 冒烟从一次性目测改为脚本化+三条可重复断言清单（①刷新后单卡 ②无双份工具卡 ③取消后所有消息 pendingCall.status 收敛），脚本入库；nightly/非阻塞 CI job 评估与 vitest 评估一并挂 T4。显式承认：本包前端态在阻塞门禁内零自动回归，属已接受风险，签字即确认。
- E2【建议】采纳。验收矩阵显式标注：服务端 E2E 对前端投影态贡献≈0，前端态验收全部挂 CDP 冒烟断言清单。
- F1【建议】采纳。T2 拆 T2a（原 D#3+D#4+D#5 纯前端唯一投影化+cancelled 终态+409 中性终态）/ T2b（原 D#1 服务端权威对齐+D#2 刷新续填，端点语义先定后施工）。
