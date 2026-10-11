> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 · 对话面板包 苞 B（消息 hover 复制 + 停止误闪红条修复 + historyLoaded 骨架屏接线）

- 施工日期：2026-10-10
- 施工依据：`<对话面板包目录>\施工卡.md`（定稿 v1.0（2026-10-10））§2（项 2）/§3（项 3）/§4（项 4）/§6.2（CDP 冒烟）/§7（分包）为主依据；冲突处以指挥官下达的苞 B 裁决要点（5 条）为准，逐条见 §7
- 目标仓库：`<MAIN_REPO>`（分支 `main`，开工 HEAD `03a6cbe`；工作树含**苞 A 未提交在制品**，本苞**叠加其上、未还原未覆盖**）
- 施工范围：**苞 B = 项 2 单条复制 + 项 3 停止误闪红条抑制 + 项 4 骨架屏接线 + CDP 冒烟同脚本追加苞 B 阶段**
- 不在本苞范围：项 1 新建对话（苞 A，已在制品中）；前端测试框架引入（vitest/playwright，随业务裁决，本苞不引入）
- **零后端改动**（苞 B 三项纯前端）
- 纪律遵守：**零 git 写操作**（无 add/commit/push/stash/checkout）；未触碰工作区另六个源分支克隆目录；`AI_API_KEY` 仅经环境变量注入（测试/桩一律占位串 `ci-stub-placeholder`）、未写入任何文件

---

## 0. 结论摘要（一句话）

苞 B 三项全部落地：**消息 hover 出「复制」（正文纯文本、`clipboard` + `execCommand` 双路径、反馈走组件局部状态不占 `notice`）**、**三处 `onClose` 统一接 `stopped` 抑制（用户主动停止不再误闪红条）**、**消息区按 `historyLoaded` 接骨架屏（与空态互斥、带 `data-testid`）**；CDP 冒烟在苞 A 同一脚本内追加 8 条断言（⑥~⑬），**苞 A 五条 + 苞 B 八条共 13 条断言，两次实跑连续全 PASS**（`SMOKE_EXIT=0`，各 108s）。六个验证面全部实测通过（§6）。

---

## 1. 改动文件清单（3 个：2 改 + 1 追加）

| # | 路径（仓库相对） | 类型 | 行级说明 |
|---|---|---|---|
| 1 | `frontend/src/components/AiPanel/AiPanel.vue` | 改 | **项 4**：`:120-126` 消息区顶部新增 `el-skeleton`（`v-if="!ai.messages.length && !ai.historyLoaded"`、`data-testid="history-skeleton"`、`:rows="4"`、`animated`）；`:127` 原 `el-empty` 的 `v-if` 改 `v-else-if`（与骨架屏互斥）。**项 2**：`:144` 消息行加 `group`（hover 显现的锚）；`:170-192` 正文之后新增复制行（局部提示 `span[data-testid="copy-hint-{id}"]` + `el-button[data-testid="copy-msg-{id}"]`，`opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity`，仅 `m.content` 非空时渲染）；`:346-349` 组件头「本仓功能增量」补两项；`:375-380` 新增局部状态 `copyState` / `copyTimer`；`:588-655` 新增 `legacyCopy()`（`:600`）与 `copyMessage()`（`:629`）；`:740-745` `onBeforeUnmount` 清 `copyTimer` |
| 2 | `frontend/src/stores/ai.ts` | 改 | **项 3**：`:264-273` `stopped` 字段补语义注释（只由 `stop()` 置位、只由 `send()` 复位；用途仅限抑制主动停止轮的断流提示）；三处 `onClose` 守卫统一加 `&& !this.stopped` —— `:614`（`resumeSnapshotStream`）、`:682`（`send`）、`:828`（`reattachActive`） |
| 3 | `scripts/chat-panel-smoke.mjs` | 改（苞 A 新建文件上的**追加**） | `:1-49` 头部文档补苞 B 八条断言口径与 `SMOKE_KILL_BACKEND` 说明；`:81-90` Chrome 启动参数补四个"禁止判为遮挡/后台"开关（hover 断言稳定性）；`:112-125` `Fetch.requestPaused` 事件处理（挂住历史请求）；`:218-220` `waitFor` 支持 `intervalMs`；`:230-395` 新增苞 B 夹具（`AI_EXPR`/`sendRound`/`startSuspendedRound`/`msgFactsExpr`/`revealCopyButton`/`clickCopyAndWait`/`killBackendByListeningPort`/`waitHintGone`）；`:495-780` 追加阶段 6~13（八条断言） |

`git diff --stat`（含苞 A 在制品）：`frontend/src/components/AiPanel/AiPanel.vue` +144/−6、`frontend/src/stores/ai.ts` +67/−14；`scripts/chat-panel-smoke.mjs` 由 302 行扩到 781 行。**苞 B 未新增任何仓库文件**（CDP 扩展按裁决落在苞 A 已建的同一脚本内），未新增依赖，未改 `package.json`。

---

## 2. 修复面 ①：消息 hover 复制（项 2）

### 2.1 前后对照

| 维度 | 修复前 | 修复后 |
|---|---|---|
| 消息级操作 | 无（全 `frontend/src` 无 `clipboard` 命中；气泡只有渲染） | 每条**正文非空**的消息在正文下方出一行右对齐操作区：hover / 键盘聚焦显现「复制」 |
| 用户消息 / AI 消息 | — | **都给**（同一 `v-for` 内，`m.role` 不参与判据） |
| 空正文（流式中空气泡） | — | **不出按钮**（`v-if="m.content"`） |
| 复制内容 | — | `m.content` **原样**：不含 `m.reasoning` / `m.toolRuns` / `m.pendingCall` / 头像 / 角色标签；**不 trim** |
| 复制实现 | — | 优先 `navigator.clipboard.writeText`；不可用**或**被拒 → `document.execCommand('copy')` 回退 |
| 成功/失败反馈 | — | 组件局部 `copyState`：`已复制`（绿）/ `复制失败，请手动选中文本复制`（红），2s 自清 |

### 2.2 关键代码（`AiPanel.vue`）

```ts
async function copyMessage(m: ChatMessage): Promise<void> {
  const text = m.content
  if (!text) { return }
  let ok: boolean
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(text); ok = true }
    catch { ok = legacyCopy(text) }          // 存在但被拒（权限/非聚焦）→ 回退
  } else {
    ok = legacyCopy(text)                    // 非安全上下文（navigator.clipboard 为 undefined）→ 回退
  }
  copyState.value = { id: m.id, ok }
  …  // 2s 后自清
}
```

`legacyCopy`：临时 `textarea`（只读、`position: fixed; top: -1000px`）→ `select()` + `setSelectionRange` → `document.execCommand('copy')` → `finally` 移除节点；异常一律 `false`。

### 2.3 两处口径说明（与裁决/施工卡的对应）

1. **为什么必须含 `execCommand` 回退**：`navigator.clipboard` 只在安全上下文（https / localhost）可用，而 `frontend/vite.config.ts` 的 `host: '0.0.0.0'` 使本仓存在「经内网 http 访问」的真实面 —— 那里 clipboard 为 `undefined`，没有回退就是"点了没反应"的静默失败（施工卡 §2.3 与调研要点 9）。两条路径都在冒烟里被**独立驱动并断言文本逐字符相等**（断言⑧/⑨/⑩）。
2. **为什么"已复制"不占 `ai.notice`**：`notice` 是单槽位（排期已登记瑕疵②），用它报复制成功会挤掉并发的断流/取消提示（施工卡 §2.2 待裁决 7 的裁决结论）。本苞用组件局部 `copyState`，并把"**`ai.notice` 必须仍为 `null`**"写成冒烟断言（⑧⑨⑩ 的 `noticeHeldByNotice`），使该口径可被机器复核而不是靠人读代码。
3. **失败不静默**：两条路径都失败时出局部红字提示（施工卡 §5.3），不是 `console.error` 了事。

---

## 3. 修复面 ②：停止误闪红条（项 3）

### 3.1 前后对照（三处同型路径，一处不漏）

| 路径 | 锚点（现行） | 修复前 | 修复后 |
|---|---|---|---|
| `send` 的 `onClose` | `stores/ai.ts:682` | `if (!info.terminal)` | `if (!info.terminal && !this.stopped)` |
| `resumeSnapshotStream` 的 `onClose` | `stores/ai.ts:614` | 同上 | 同上 |
| `reattachActive` 的 `onClose` | `stores/ai.ts:828` | 同上 | 同上 |
| `stopped` 字段语义 | `stores/ai.ts:264-273` | 无注释（只写字段的语义靠猜） | 补注释：只由 `stop()` 置位、只由 `send()` 复位；用途仅限抑制主动停止轮的断流提示 |

### 3.2 根因链（实读，逐跳可核）

1. 点「停止」→ `AiPanel.vue` 停止按钮 → `stop()` → `stores/ai.ts#stop` 第一件事 `this.stopped = true`，随后 `currentAbort?.abort()`；
2. abort 使 `api/sse.ts#readStream` 走 abort 分支（`controller.signal.aborted` 为真）→ 返回 `{terminal:false, aborted:true}`；
3. `streamChat` 在 `result.aborted` 且**非帧间静默超时**时不做 `tryReattach`，直接 `finish(..., terminal=false, '连接已中止')`；
4. `onClose({terminal:false})` → 修复前置 `streamInterrupted = true` + 写「连接中断，结果可能不完整…」；
5. 面板据此渲染红色 `el-alert`「连接中断，结果可能不完整」+「一键重挂该轮」—— **用户点了主动停止，却被告知"连接中断"**。

### 3.3 为什么用 `stopped` 是正确判据

- `stopped` 的语义就是"本轮是用户主动停的"；它在**本轮 `send` 开头复位**，因此只影响用户按过停止的那一轮，不会污染后续轮次；
- **真断流路径不受影响**：真断流时 `stopped === false` ⇒ 红条与「一键重挂该轮」照常出现。这一点不是推断，而是**行为级对照实测**：冒烟断言⑫ 在真断流场景下实测 `streamInterrupted === true`、红条在页面可见、`stopped === false`（§6.5）；
- 三处一起改（含重挂/续收路径）：否则"用户停止"在重挂/刷新续收路径上仍会闪红条（施工卡 §3.1 的一致性建议）。

### 3.4 不触碰面（回归安全边界）

未改 `finally` 的 `loading` 复位、`activeRunId`、`runSeq`、`reattachActive` 的双连接收口、`send` 的重连公告；三处改动**各只加一个 `&&` 条件**，无新增状态字段、无新增分支出口。

---

## 4. 修复面 ③：`historyLoaded` 骨架屏接线（项 4）

### 4.1 前后对照

| 维度 | 修复前 | 修复后 |
|---|---|---|
| `historyLoaded` | 彻底的**只写字段**（`:265` 声明、`:437/:447/:476` 置位；`AiPanel.vue` **零消费**） | 消息区消费：`el-skeleton` 与 `el-empty` 互斥 |
| 渲染分支 | 消息区只有 `el-empty`（`v-if="!ai.messages.length"`）与消息流 | `<el-skeleton v-if="!ai.messages.length && !ai.historyLoaded" data-testid="history-skeleton" :rows="4" animated />` + `<el-empty v-else-if="!ai.messages.length">` |
| 可测性 | — | 骨架屏带 `data-testid`（裁决 9：加），与 `pending-card-*` / `tag-form-cancelled` 的 testid 纪律一致 |
| `resetSession` 清空后 | 苞 A 已落 `this.historyLoaded = true`（苞 A 报告 D5） | **苞 B 无需再改**：消费侧落地后，该行正是"新建对话后立即见空态、不长骨架屏"的锚点（本苞只做消费侧，未重复改 store） |

### 4.2 状态切换关系（施工卡 §4.3 口径，本苞实测复核）

| 状态 | `messages.length` | `historyLoaded` | 渲染 | 冒烟证据 |
|---|---|---|---|---|
| 首屏 / 刷新且无镜像，等后端历史 | 0 | false | **骨架屏** | 断言⑬ whileHeld |
| 对账完成（历史为空） | 0 | true | **空态** | 断言⑬ afterRelease |
| 有消息（含镜像/历史重建后） | N>0 | false→true | 消息流（骨架屏不出现） | 断言⑥ `skeletonWhileMessagesExist=false` |
| 新建对话后 | 0 | true（苞 A 落地） | **空态** | 苞 A 断言③ 与 ⑬ afterRelease 同框复核 |

### 4.3 已知时序代价（如实留痕，未修）

`loadHistory` 在 `onMounted` 里被 `await`，而镜像恢复发生在 `loadHistory` **内部** ⇒ 有镜像的刷新会有一个极短窗口（同一任务链内）满足"无消息 + 未对账"，视觉上是数毫秒级骨架屏闪现。施工卡 §4.3 已评估为可忽略且**不建议**为此打散既有对账结构，本苞**维持现状不动**（未改 `loadHistory` 的置位时机与 `onMounted` 结构）。

---

## 5. CDP 冒烟新阶段断言矩阵（同一脚本内追加，不另起脚本）

定位纪律：新增元素一律 `data-testid`（`copy-msg-{id}` / `copy-hint-{id}` / `history-skeleton`）；状态断言走 `$pinia` 直读 store（手法与既有冒烟一致），不耦合 UI 文案。

| 断言 | 阶段 | 断言内容 | 关键手法 |
|---|---|---|---|
| ⑥ | 6 复制按钮结构 | 每条**正文非空**消息都有复制按钮、空正文消息**没有**（双向）、按钮文案 `复制`、默认计算样式 `opacity: 0`（未 hover 不显现）、**有消息时骨架屏不出现** | `data-testid` 全量对账 + `getComputedStyle` |
| ⑦ | 7 hover 显现 | CDP **真实鼠标**移到按钮上 → 计算样式 `opacity: 1`（`group-hover` 生效） | `Input.dispatchMouseEvent`（mouseMoved）；重试 3 次，失败才回落 CSS 域强制 `:hover`（落盘记明机制） |
| ⑧ | 8 复制·主路径 | 真实点击 → `navigator.clipboard.writeText` 收到 **`m.content` 逐字符相等**（包一层记录再转调原生）+ 局部提示出现且文案为 `已复制` + **`ai.notice` 仍为 `null`**（不占单槽位）；附剪贴板回读（非断言，供交叉校验） | 真实 press/release；`clipboard.writeText` 记录式包装 |
| ⑨ | 9 复制·回退 A | 把 `navigator.clipboard` 置为 `undefined`（模拟经内网 http 的非安全上下文）→ 点击走 `document.execCommand('copy')`，回退文本 === `m.content`，`已复制` 仍出现、`notice` 仍 `null` | `Object.defineProperty(navigator, 'clipboard', …)` + `execCommand` 记录式包装（读 `activeElement.value` 取回退文本） |
| ⑩ | 10 复制·回退 B | `clipboard.writeText` 存在但**拒绝**（`NotAllowedError`）→ 回退路径同样生效（被拒计数 1 + execCommand 被调 + 文本相等 + `已复制` + `notice` 仍 `null`） | 同上 + reject 计数 |
| ⑪ | 11 停止不误闪（**项 3 主证据**） | 起一轮长跑（DANGER 工具 `start_export` → 确认门挂起）→ 用户点「停止」→ `ai.streamInterrupted === false`、页面**无**「连接中断，结果可能不完整」文案、`notice === '已请求取消本轮对话'`、`stopped === true` | 前置自证：`startSuspendedRound` 等到 `loading && activeRunId && suspended`，否则断言不成立（防"打在已收尾轮次上"的空转） |
| ⑫ | 12 真断流回归对照 | **未**调 stop 的真断流 → 红条**照常出现**、`ai.streamInterrupted === true`、`ai.stopped === false`（守卫没有削弱真断流路径） | kill 后端 java 进程（`SMOKE_KILL_BACKEND=1` 授权 + 端口反查 PID + 进程名必须 `java.exe` 三重闸）；落后端后由产品自己的 90s 帧间静默看门狗收尾 |
| ⑬ | 13 骨架屏接线 | 清镜像后 reload，用 `Fetch` 域**挂住**历史请求 → 骨架屏存在 + `historyLoaded === false` + 消息 0 + **空态不与之同框**；放行历史请求 → 骨架屏消失 + 空态出现 | `Fetch.enable`（`*api/ai/history*`，Request 阶段）+ `continueRequest`；无 sleep 竞态 |

### 5.1 ⑫ 的两个"反直觉"实测事实（都已写进脚本注释，供后续复用）

1. **CDP 断网模拟不出真断流**：`Network.emulateNetworkConditions{offline:true}` 只拦**新**请求，**不会拆掉已在飞的流式响应** —— 首轮实跑 35s 内 `streamInterrupted` 一直 `false`、`notice` 一直 `null`（连接根本没被动过）。故改用"kill 后端进程"这一真实断链。
2. **dev 形态下断流提示要等 ~92s**：`SSE_PATHS.chat` 是相对路径 ⇒ SSE 走前端 dev server 的 `/api` 代理；后端被杀的瞬间代理只打 `http proxy error`（`read ECONNRESET`）但**不结束客户端响应** ⇒ 浏览器侧那条流只是"不再来字节"。收尾因此改由产品自己的**帧间静默看门狗**（`DEFAULT_IDLE_TIMEOUT_MS=90s`，挂起期后端每 2s 一次心跳，心跳一停即倒计时）触发 → 判断流 → reattach（后端已死，失败）→ `onClose(terminal=false)` → 红条。**实测采样**：第 0~81s 一直 `loading=true / suspended=true / streamInterrupted=false`；第 91s 出现「连接中断，2 秒后重挂（第 2 次）」；**第 93s** `streamInterrupted=true`、红条可见、`loading=false`。即本断言同时证到"真断流仍亮红条"与"看门狗兜底有效"。

---

## 6. 全部实测数字（全部实跑）

### 6.1 后端 · 全量（`clean test`，包装脚本 `<SCRATCH>/../.run-fix/mvn-run.sh`）

```
mvn-run.sh -B clean test
→ Tests run: 397, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS（02:07 min）

surefire XML 求和：61 个 XML / tests=397 / failures=0 / errors=0 / skipped=0
苞 A 收尾基线（同一口径）：397 / 61 个 XML
差值：0（苞 B 零后端改动 ⇒ 只增不减成立，未减）
已知 flaky SseChatEmitterWriteBudgetTest：本批全量实跑未见红（无需单类重跑）
```

打包（供冒烟/E2E 用 jar）：

```
mvn-run.sh -B package -DskipTests → BUILD SUCCESS（6.1s），产出 backend/target/config-mgr.jar
```

### 6.2 前端 · typecheck + build（CI 同口径）

```
cd frontend && yarn typecheck → vue-tsc --noEmit，Done in 5.80s（0 error）
           && yarn build     → ✓ built in 42.40s（dist 产物正常，element/spreadjs 分块与基线一致）
```

### 6.3 门禁

```
node scripts/ci/check-docs.mjs
→ ① 无盘符绝对路径 PASS   ② 技术栈版本一致 PASS   ③ 引用文件存在 PASS
   ④ 端口表一致 PASS      ⑤ 命名纪律（git ls-files 920 路径，实扫 917）PASS   ⑥ 探针不参与构建 PASS
   全部通过：6/6 断言绿
```
- 命名纪律自查（复刻断言⑤三态正则，扫 `git status` 全量 6 个变更文件，**含 2 个未跟踪文件**——断言⑤ 只扫 `git ls-files`，未跟踪文件需自查补齐）：**命中 0 处**。

### 6.4 CDP 冒烟（`scripts/chat-panel-smoke.mjs`，苞 A 阶段 + 苞 B 阶段共 13 条断言）

编排：桩上游（18399）+ 后端 jar（18330，占位 key）+ 前端 dev（5202，代理指向 18330）+ 自启 Chrome（CDP 9334）。端口纪律：18330 起、桩 18399；启动前 netstat 预检（四端口 + Redis 6379）；收尾只按监听 PID 停自启进程，实测四端口全部释放。

| 轮次 | 结果 | 用时 | 说明 |
|---|---|---|---|
| 第 1 次全量（最终脚本） | **13/13 PASS，`SMOKE_EXIT=0`** | 108s | ⑫ 由 kill 后端进程触发，`atSec=93` 出现红条 |
| 复跑（同一字节脚本） | **13/13 PASS，`SMOKE_EXIT=0`** | 108s | 一致复现 |

实测明细（复跑同构，此处取第 1 次全量）：

| 断言 | 实测 detail |
|---|---|
| ① 空态按钮置灰/小字可见 | PASS `{messages:0, buttonDisabled:true, buttonText:"新建对话", hintVisible:true}` |
| ② 一轮后按钮可点 + 后端有记忆 | PASS `{messages:2, buttonDisabled:false, backendHistoryCount:2}` |
| ③ 新建对话：换 id 双写 + 清屏 + 旧记忆归零 | PASS `{sessionIdChanged:true, stateMatchesStorage:true, oldMirrorRemoved:true, oldSessionHistoryCount:0, notice:null}` |
| ④ 新会话记忆落在新 id | PASS `{newSessionHistoryCount:2, oldSessionHistoryCount:0}` |
| ⑤ 置灰态点击无副作用 | PASS `{sessionIdUnchanged:true, buttonDisabled:true}` |
| ⑥ 复制按钮结构与默认不显现 | PASS `{messagesWithText:2, blankMessages:0, missingButtons:[], leakedButtons:[], defaultOpacity:"0", skeletonWhileMessagesExist:false}` |
| ⑦ hover 显现 | PASS `{opacityBeforeHover:"0", opacityAfterReveal:"1", mechanism:"cdp-mouse-hover"}` |
| ⑧ 复制主路径（clipboard） | PASS `{clipboardApiAvailable:true, clickVia:"cdp-mouse", writeCalls:1, exactMatch:true, hintText:"已复制", noticeHeldByNotice:null, clipboardReadback:"好的，本轮已完成，请查看上方的结果。"}` |
| ⑨ 回退 A（clipboard 缺失） | PASS `{clipboardForcedUndefined:true, clickVia:"cdp-mouse", execCommands:["copy"], exactMatch:true, hintText:"已复制", noticeHeldByNotice:null}` |
| ⑩ 回退 B（writeText 被拒） | PASS `{writeTextRejects:1, clickVia:"cdp-mouse", execCommands:["copy"], exactMatch:true, hintText:"已复制", noticeHeldByNotice:null}` |
| ⑪ 停止不误闪红条 | PASS `{suspendedBeforeStop:true, stopped:true, streamInterrupted:false, redBannerPresent:false, notice:"已请求取消本轮对话"}` |
| ⑫ 真断流仍亮红条 | PASS `{backendKillPid:<本次 java PID>, streamInterrupted:true, redBannerPresent:true, stopped:false}`，`atSec=93` |
| ⑬ 骨架屏接线 | PASS `whileHeld:{skeleton:true, historyLoaded:false, messages:0, emptyCard:false}` → `afterRelease:{historyLoaded:true, skeleton:false, emptyCard:true}` |

> key 口径：本苞**全程未使用真实 AI key**（无真模型轮），后端与桩一律占位 key 经环境变量注入，密钥零落盘。三项验收面都不依赖模型行为（复制是本地行为；停止抑制只需"一轮在飞"；骨架屏是纯前端渲染分支）。

### 6.5 E2E 回归（桩模式 29 用例 · `-EnableGf6` · `E2E_STUB_MODE=1`）

```
桩上游自检：PASS（路由表契约/白名单对账/收敛自证/披露驱动/帧形态/反解）
E2E_EXIT=0  elapsed=60s
 结果：PASS=29  FAIL=0   （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6（含 -EnableGf6 的 GF6））
 GFSKIP=0              （桩模式零容忍：GFSKIP≥1 即整棒 FAIL）
 Q8 项：PASS=2  FAIL=0  （不计入主用例计数）
```
⇒ 苞 B 前端改动对既有 29 用例**零回归**。

---

## 7. 偏差与处置

| # | 偏差 | 处置 / 理由 |
|---|---|---|
| D1 | 施工卡 §2.1 示意把复制按钮放在"气泡顶部一行或正文之后"；本苞选**正文之后**（右对齐一行，与局部提示同行） | 顶部一行会在**流式空气泡**上方留一条空行；正文之后最贴合"复制这段正文"的心智，且 `v-if="m.content"` 天然保证空气泡无按钮（施工卡 §2.1「无正文不出按钮」）。**未改任何可观测契约** |
| D2 | 施工卡 §2.2 的示意代码把 `ai.notice = '已复制'` 作为反馈载体，同时又标注待裁决；本苞按**裁决 7** 用组件局部状态 | 裁决 7 明确"不占 `notice` 单槽位"。为防该口径回退，冒烟把"`ai.notice` 必须仍为 `null`"写成断言（⑧⑨⑩），使口径可被机器复核 |
| D3 | 复制失败提示文案「复制失败，请手动选中文本复制」为施工自拟（施工卡只要求"必须有可见反馈"） | 两条路径都失败（非安全上下文 + `execCommand` 也被拒）时的兜底文案；与调研要点 9「不得静默失败」对齐。**措辞待指挥官认可** |
| D4 | 冒烟把 ⑫（真断流回归对照）放在**最后一步**，且需要杀后端进程 | 杀进程后后端不可用，任何后续阶段都会假红；故置末。破坏性动作加三重闸（`SMOKE_KILL_BACKEND=1` / 端口反查 PID / 进程名必须 `java.exe`），未授权时如实报 **SKIP 不假绿**；本棒编排脚本显式打开该闸 |
| D5 | 冒烟 Chrome 启动参数新增四个"禁止判为遮挡/后台"开关 | 首轮实跑 ⑦~⑩ 全红，根因是窗口被判为遮挡/后台后 Chrome 挂起渲染 ⇒ **CDP 真实鼠标 hover 不更新 `:hover`、点击落空**（同一坐标、同一脚本，前后两次跑结果不同）。这是**测试夹具**配置，不改被测代码；加开关后连续两轮 ⑦~⑩ 全绿且 `clickVia` 均为 `cdp-mouse`（真实鼠标，未走 DOM 回落） |
| D6 | 冒烟新增 `revealCopyButton` / `clickCopyAndWait` 的**回落路径**（强制伪类 / DOM `click()`） | 纯防御：若 CI 机器上窗口仍被判遮挡，回落路径保证"被测对象（复制行为）"仍被确定性驱动，且落盘记明用的是哪条路径（`mechanism` / `clickVia`）。两轮实测均**未**触发回落 |
| D7 | 本苞 CDP 冒烟首轮 ⑫ 失败、次轮 ⑦~⑩ 失败、第三轮 ⑫ 仍失败（已修） | 如实留痕：三次失败**全部是冒烟夹具自身的问题**（⑫ 的断流制造方式、Chrome 遮挡），不是产品缺陷；产品侧修复面 ① ② ③ 在首轮即已全绿。最终脚本连续两轮 13/13 |
| D8 | 未新增 `docs/evidence/` 证据文档、未生成 R-101 排期行文本 | 同苞 A D6：施工卡 §6.3「证据文档是否入库由指挥官定」；排期登记体例为"只追加"，写台账属指挥官动作，本苞不代写（见 §9） |
| D9 | `<SCRATCH>` 内的编排脚本与原始日志含本机路径；仓库内变更文件命名纪律实测命中 0 | 脱敏要求针对**仓库内容与报告正文**：仓库内 6 个变更文件零命中（含 2 个未跟踪文件）；**报告正文**一律 `<>` 占位符与端口号（盘符/用户名/内网 IP 全零命中，自查见 §6.3）；`<SCRATCH>` 原始日志在仓库外、未入库，另出脱敏摘录 `<SCRATCH>/证据摘要-苞B.md` |

---

## 8. 回归安全边界（"没碰什么"清单）

| 面 | 本苞是否触碰 |
|---|---|
| 后端任何文件 | **否**（零后端改动，XML 397/61 与苞 A 一致） |
| `stores/ai.ts` 的 `loading` / `activeRunId` / `runSeq` / 双连接收口 / `finally` 复位 | **否**（项 3 只加 `&& !this.stopped`；项 4 未改 store） |
| `loadHistory` 的 `historyLoaded` 三处置位时机 | **否**（苞 B 只做消费侧） |
| `resetSession`（苞 A 在制品） | **否**（未改一行；其 `historyLoaded = true` 由苞 A 落地，正是骨架屏的互斥锚点） |
| `m2t2a-smoke.mjs` 等既有冒烟/脚本 | **否**（苞 B 按裁决在苞 A 新建的 `chat-panel-smoke.mjs` 内追加，未另起脚本、未动既有冒烟） |
| 前端测试框架 / 依赖 | **否**（不引入 vitest/playwright，`package.json` 零改动） |

---

## 9. 【待裁决】

| # | 事项 | 本苞处置与建议 |
|---|---|---|
| 1 | **R-101 排期行文本是否入库**（施工卡 §8.3 已给草案；体例"只追加、既有行零改动"） | 未动台账（零 git 写 + 不代写排期）；建议指挥官按 §8.3 草案追加，编号 `R-101` |
| 2 | **是否补 `docs/evidence/` 证据文档**（本苞实测数字与冒烟结果 JSON 现落 `<SCRATCH>`，仓库外） | 未新增；如需入库，可把本报告 §6 数字 + 冒烟结果 JSON 摘要按既有 M2 证据命名体例落库 |
| 3 | **测试脚本内"杀进程"能力的存废与默认值** | 本苞默认**关**（`SMOKE_KILL_BACKEND` 未置 1 时 ⑫ 报 SKIP）；三重闸（环境变量 / 端口反查 PID / 进程名必须 `java.exe`）。若指挥官认为"测试脚本不得具备杀进程能力"，可把 ⑫ 降级为人工步骤（保留断言与采样口径） |
| 4 | **⑫ 的等待代价（~92s）是否接受** | 该时延来自**产品自己的帧间静默看门狗**，不是脚本 sleep 兜底；dev 形态下 SSE 经前端代理，代理不结束客户端响应 ⇒ 只能等看门狗。若指挥官要求更快，需要"让代理结束响应"或"非代理直连后端"的前置改造，**超出本苞范围**，建议维持 |
| 5 | **触屏/平板是否要给复制按钮"常驻显示"**（施工卡 §2.1 遗留待裁决） | 本苞未加媒体查询：本仓 AI 面板是桌面工具形态（固定 400px 宽），`hover: none` 下按钮不可见但键盘/程序化仍可触发。若要支持平板，建议单独立项（`@media (hover: none)` 下常驻） |
| 6 | **复制内容是否并入思考链/工具卡**（施工卡 §2.2 建议维持"正文纯文本"） | 本苞维持正文纯文本（不含 `reasoning`/`toolRuns`/`pendingCall`、不 trim）。理由三条见施工卡 §2.2；若指挥官需"复制全部"，建议**另给**二级动作而非改本按钮语义 |
| 7 | **复制失败兜底文案**（D3） | 「复制失败，请手动选中文本复制」为施工自拟，待措辞认可 |

---

## 10. 附录：证据落盘位置（均在仓库外）

| 证据 | 位置 |
|---|---|
| **脱敏证据摘要**（本报告 §6 数字的原始出处摘录） | `<SCRATCH>/证据摘要-苞B.md` |
| 后端全量测试日志 / 打包日志 | `<SCRATCH>/mvn-full.log`、`<SCRATCH>/mvn-package.log` |
| CDP 冒烟日志（第 1 次全量 / 复跑）+ 结果 JSON | `<SCRATCH>/orchestrate-smoke-4.log`、`orchestrate-smoke-5.log`、`<SCRATCH>/smoke-out/result-*.json` |
| 冒烟编排脚本（含 `SMOKE_KILL_BACKEND=1` 授权点） | `<SCRATCH>/run-smoke.sh` |
| E2E 日志 + 编排脚本 | `<SCRATCH>/e2e-run.log`、`<SCRATCH>/orchestrate-e2e.log`、`<SCRATCH>/run-e2e.sh` |
| 命名纪律 / 报告脱敏自查脚本 | `<SCRATCH>/naming-check.mjs`、`<SCRATCH>/report-check.mjs` |
