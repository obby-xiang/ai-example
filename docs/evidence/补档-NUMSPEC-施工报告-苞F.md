> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 — 小批苞 F（E2E 读窗与等待改造，裁决⑤）

- 日期：2026-10-10
- 仓库：`<MAIN_REPO>`（主仓，分支 `main`）；施工基线 = 苞 A + 苞 B 落盘后的**未提交工作树**（既有未提交改动共 19 项 = 18 `M` + 1 `??`，全部保留；本苞**未还原、未覆盖**任何既有未提交改动；苞 A 在 `scripts/verify-e2e.ps1:1392` 的键名注释**未回退**）
- 依据：`<SCRATCH>/numeric-spec-audit/小批施工卡.md` §2「苞 F」（F-1 / F-2 / F-3 / F-4）为最高施工依据；另依据指挥官本苞任务书（其中「读窗 N-4 一并改 5000」为指挥官对本卡 §5.2 N-4 待裁决项的**授权结论**，见 §6 偏差 1）
- 纪律：本苞未做任何 git 写操作（无 `commit` / `push` / `reset` / `checkout`）；未触碰工作区另外六个源分支克隆目录；报告全文脱敏（无盘符路径 / 用户名 / 内网 IP / 被禁命名组合，仓库名一律 `<MAIN_REPO>`，仓库外目录一律 `<SCRATCH>`）

---

## 1. 改动文件清单（仓库相对路径 + 行级说明）

### 1.1 `scripts/verify-e2e.ps1`（F-1 读窗）

| # | 行（改后实读） | 改动 |
|---|---|---|
| 1 | `1307-1314` | **新增 8 行** `Read-Sse-Brief` 的函数头依据注释（卡片 F-1.2 文案落地）：判据 = 「窗口 ≥ 2 个心跳周期 + 抖动余量」，被观察节拍 = `ConfirmGate.HEARTBEAT_SECONDS = 2` ⇒ `2s × 2 + 1s = 5000ms`；三处调用点统一口径声明（后续调用点不再各写一遍）；依据指向业界依据 §2 / §12 与裁决⑤；末行指向登记落点 `docs/M2-排期计划.md` 去向表 `R-100` |
| 2 | `1818`（GF4-A3） | 读窗 `3500` → **`5000`**（卡片 F-1.3 列为 N-4 待裁决，本轮由指挥官授权同改，见 §6 偏差 1） |
| 3 | `1816` | 同一窗口的**陈旧描述**同步：`# A3 拒绝路径不发帧不唤醒：3s 窗口内…` → `5000ms（5s）窗口内…` |
| 4 | `1819` | 同一窗口的**失败文案**同步：`'400 后 3s 窗口内出现 frontend_tool_result（拒绝路径不应发帧）'` → `'400 后 5s 窗口内…'` |
| 5 | `1851` | GF4 的 PASS 文案同步：`（PENDING 保持/3s 无帧）` → `（PENDING 保持/5s 无帧）` |
| 6 | `1888`（GF5-A4） | 读窗 `3000` → **`5000`**（卡片 F-1.1 主锚） |
| 7 | `2203`（TC23 ④） | 读窗 `3000` → **`5000`**（卡片 F-1.1 主锚） |

- **未改**：函数体（`1315-1358`）一字未动 —— 读窗语义仍为「`Stopwatch` 定截止 + `ReadLineAsync` 等剩余窗口 + 到点主动断开」，放大的是窗口值而非退出机制；裁决 #3 的「握手失败显式抛出、不让"无新帧"断言空转」逻辑未削弱。
- **未改**：13 处 `Start-Sleep -Seconds 2`（F-3 只登记，见 §3）。
- **未回退**：苞 A 在 `1392` 的 `app.ai.hitl.frontend-tool-timeout` 解耦注释保持原样（本苞未触碰该行）。

### 1.2 `scripts/m2t2a-smoke.mjs`（F-2 AWAIT_MS 事件驱动）

| # | 行（改后实读） | 改动 |
|---|---|---|
| 1 | `189-196` | **新增**等待上界依据注释（卡片 F-2 JS 注释文案落地，数值口径按实读改写，见 §6 偏差 3）+ **新增** `const FALLBACK_AWAIT_MS = 330_000`（回落值 = `total-budget` 缺省 300s + 30s 余量） |
| 2 | `198-206` | **新增** `parseIsoDurationMs(raw)`：把 `/health` 的 `resilience.totalBudget`（ISO-8601，形如 `PT5M`）解析为毫秒；非时长 / 非正值 / 非法串一律返回 `null` |
| 3 | `208-229` | **新增** `deriveAwaitCap()`：读**已在跑**的 `/api/ai/health` 的 `resilience.totalBudget` → `capMs = budgetMs + 30_000`；三条回落路径（非 2xx / 解析失败 / 读取异常）均 `console.warn` + 回落具名常量，并回传 `capSource` 供证据留痕。**不依赖任何新增端点**（与卡片 §2 A-4-7「`/health` 是否增键」无耦合） |
| 4 | `231-270` | `readSse` 签名 `(response, onFrame, deadlineMs)` → **`(response, onFrame, opts)`**，兼容旧数字形态（数字 ⇒ 等价 `{ deadlineMs }`）；新增 `until` 终止帧谓词（`done` / `error` 任一命中即停读）、`label`、`stopReason`；返回值在原 `{ reader, seenTypes, stopped, frame }` 之上增 `stopReason` 与 `label` |
| 5 | `316-321` | 阶段 3a 注释同步（事件驱动 + 硬上限由事实源推导 + FAIL detail 记 `stopReason`） |
| 6 | `330-339` | 删 `const AWAIT_MS = 240_000`，改为 `deriveAwaitCap()` 取 `{ capMs: AWAIT_CAP_MS, capSource }`；读流调用改为 `{ deadlineMs: Date.now() + AWAIT_CAP_MS, until: f => f.type === 'done' \|\| f.type === 'error', label: 'a-await-frontend_tool_request' }`；日志打印 `stopReason / stopped / capMs / capSource / seenTypes` |
| 7 | `340-351` | 断言① 判据由 `!awaited.stopped \|\| !awaited.frame` 改为 **`awaited.stopReason !== 'matched' \|\| !awaited.frame`**（关键：早停于终止帧时 `stopped` 亦为 `true`，若不改判据会把"读到终帧"误当成功 —— 卡片未点明，属本苞实现必改项，见 §6 偏差 4）；FAIL detail 与异常文案均写入 `awaitStopReason / awaitCapMs / awaitCapSource` |
| 8 | `354-358` | `RESULT.phases.realRound` 证据增 `awaitStopReason / awaitCapMs / awaitCapSource` |

- **未改**：`362` 的 `const deadline = Date.now() + 150_000`（3d 收敛等待，卡片 F-2.4 明确「只登记不改」）；`143` 合成帧 `timeoutSeconds: 120` 与 `239` 的 `12 × 500ms` 轮询（卡片 §5.2 N-6「登记不改」）；阶段 1/2 与断言②③③b 一字未动。
- **兼容性**：全仓仅此一处调用 `readSse`（实读 `grep -n "readSse" scripts/m2t2a-smoke.mjs` = 定义 1 处 + 调用 1 处），旧签名兼容分支保留，未破坏任何外部调用点。

### 1.3 `docs/M2-排期计划.md`（F-3 13 处 `Start-Sleep` 登记）

| # | 行（改后实读） | 改动 |
|---|---|---|
| 1 | `292` | **新增** `R-100` 行，落点在**去向表表体内**、紧接既有末行 `R-99`（`:291`）之后、表体闭合空行之前（卡片 §5.1 指定锚点：去向表 + 只追加） |
| 2 | `357-359` | **新增**小节 `### 小批苞 F 登记：E2E 时序脆弱面（13 处 Start-Sleep）（2026-10-10，只追加）`（追加在 `## 等待治理排查登记注记（2026-10-10）` 节末，紧随苞 A 的同类小节之后；按指挥官任务书「登记入等待治理排查登记注记节」的措辞补一个**节内指针**，正文仍以去向表 `R-100` 行为准） |

- **只追加证明**：`git diff --numstat docs/M2-排期计划.md` = `11 增 / 2 删`；该 2 处删除行**不是本苞引入** —— 实读删除行内容为苞 A 改写的 `R-41` 行与 `R-99` 行（相对 HEAD 的差异，属苞 A 未提交改动）。即**本苞对该文件零删除**。
- **内容零删**：既有 `R-95`~`R-99`、批 A 红队观察项小节、苞 A 观察项小节全部原样保留。

---

## 2. `AWAIT_MS` 改造前后对照（裁决要点 2 的显式交付）

### 2.1 改造前（HEAD 版，`:273-283`）

```js
const AWAIT_MS = 240_000
const awaited = await readSse(chatRes,
  f => f.type === 'frontend_tool_request' && f.name === 'generative_form',
  Date.now() + AWAIT_MS)
console.log('[阶段3a] await stopped =', awaited.stopped, 'seen =', JSON.stringify(awaited.seenTypes))
if (!awaited.stopped || !awaited.frame) {
  try { await awaited.reader.cancel() } catch { /* 忽略 */ }
  RESULT.phases.realRound = { sessionId: S, stage: 'a-await', seenTypes: awaited.seenTypes }
  assert('①', false, { stage: 'a-await-frontend_tool_request', timeoutMs: AWAIT_MS, seenTypes: awaited.seenTypes })
  throw new Error('阶段3a 超时未收到 frontend_tool_request')
}
```

`readSse` 的停止条件只有两条：`onFrame` 命中，或 `deadlineMs` 到点 / 对端关闭。**没有"后端已给终帧"这一路**。

### 2.2 改造后（工作树版，`:330-351`）

```js
const { capMs: AWAIT_CAP_MS, capSource: AWAIT_CAP_SOURCE } = await deriveAwaitCap()
const awaited = await readSse(chatRes,
  f => f.type === 'frontend_tool_request' && f.name === 'generative_form',
  {
    deadlineMs: Date.now() + AWAIT_CAP_MS,
    until: f => f.type === 'done' || f.type === 'error',
    label: 'a-await-frontend_tool_request'
  })
...
if (awaited.stopReason !== 'matched' || !awaited.frame) { /* detail 带 stopReason / capMs / capSource */ }
```

### 2.3 语义对照

| 维度 | 改造前 | 改造后 |
|---|---|---|
| 停止条件 | 仅命中目标帧才停读；否则烧满 240s 或等对端关闭 | 命中目标帧 → `matched`；命中 `done` / `error` → `terminal:<type>` 提前停读；否则 `window`（对端关闭 / 窗口到点） |
| 硬上限 | 写死 `240_000`（与后端挂起键/轮次预算无关） | 由服务端自报 `resilience.totalBudget` 推导 = `budgetMs + 30s`（余量 = 一次心跳 + 抖动）；解析失败回落具名常量 `FALLBACK_AWAIT_MS = 330_000` 并打日志 |
| 失败归因 | 只有 `stopped=false` + 帧类型序列；文案恒写「**超时**未收到」 | `stopReason` 区分「后端给了终帧（问题在业务）」与「窗内空读（问题在环境/超时）」；FAIL detail 与异常文案都带它 |
| 上限来源可审计 | 无（写死） | `capSource` 落证据：实跑取值 = `/api/ai/health resilience.totalBudget=PT5M` ⇒ `capMs = 330000` |
| 旧调用点兼容 | — | 数字第三参仍等价 `{ deadlineMs }`；`stopped` / `frame` 语义不变 |

> 实跑证据（§4.2）显示：**有帧**时 `capMs = 330000` 但 `matched` 在 **< 2s** 返回；**无帧**时 `stopReason = terminal:done`，而旧版在同一场景下给出无归因的「超时」结论。

---

## 3. 13 处 `Start-Sleep` 登记落点

### 3.1 行号（本苞落盘后实读；括号内 = 苞 F 前实读值）

| 用例 | 行号（苞 F 后） | 苞 F 前 | 类别 |
|---|---|---|---|
| GF1（schema 漂移重试） | `1523` | `1515` | 模型抖动重试 back-off |
| GF1（未触发表单重试） | `1530` | `1522` | 模型抖动重试 back-off |
| GF2 | `1615` | `1607` | 模型抖动重试 back-off |
| GF2 | `1622` | `1614` | 模型抖动重试 back-off |
| GF3 | `1695` | `1687` | 模型抖动重试 back-off |
| GF3 | `1702` | `1694` | 模型抖动重试 back-off |
| GF4 | `1789` | `1781` | 模型抖动重试 back-off |
| GF4 | `1796` | `1788` | 模型抖动重试 back-off |
| GF6 | `1932` | `1924` | 模型抖动重试 back-off |
| TC17（换会话前） | `2017` | `2009` | 换会话 / 等挂起落地节拍 |
| TC17（未调用工具重试） | `2025` | `2017` | 模型抖动重试 back-off |
| TC18 | `2096` | `2088` | 换会话 / 等挂起落地节拍 |
| TC23 | `2180` | `2172` | 换会话 / 等挂起落地节拍 |

- 计数实读：`grep -c "Start-Sleep -Seconds 2" scripts/verify-e2e.ps1` = **13**（与卡片 §5.1 完全一致）。
- **行号位移说明（已在登记行内注明）**：苞 F 在 `Read-Sse-Brief` 函数头新增 **8 行**依据注释（`:1307-1314`），故 `:1515` 之后的锚点整体 **+8**。登记行同时给出「苞 F 后」与「苞 F 前」两套锚点，避免后续批次读错。

### 3.2 登记文本落点

- **正文** = 去向表 `R-100` 行（`docs/M2-排期计划.md:292`）：登记对象、13 处锚点、分类口径、**不改理由**、下批候选统一形态（`Wait-Until { <条件> } 60 '<用例X的Y就绪>'`，函数已存在 `scripts/verify-e2e.ps1:166`）、行号位移口径、来源（`<SCRATCH>/numeric-spec-audit/清单总账.md` §2 H-06 / §1.6 S-16）。
- **节内指针** = `docs/M2-排期计划.md:357-359`（`## 等待治理排查登记注记（2026-10-10）` 节内），按指挥官任务书的「登记入该节」措辞补，内容与 `R-100` 行一致、不另立条目。

---

## 4. 实测结果（全部实跑）

> 所有实跑在 `<SCRATCH>/baoF-e2e/` 下编排（脚本化复现 CI `e2e` job 口径：桩上游 + redis + 打包 jar；后端工作目录在仓库外，故仓库工作区不留 `data/` 与上传件）。

### 4.1 E2E 桩模式全量（29 用例 · `-EnableGf6` · `E2E_STUB_MODE=1`）

| 轮次 | 被测脚本 | 结果 | 退出码 | E2E 自身耗时 |
|---|---|---|---|---|
| ① run1 | 工作树版（读窗 5000×3，GF4 PASS 文案为改前文案） | `PASS=29 FAIL=0`，`GFSKIP=0`，`Q8 项 PASS=2 FAIL=0` | **0** | **56s** |
| ② run2 | 工作树版（**最终文件**，含 GF4 PASS 文案 `5s 无帧` 同步） | `PASS=29 FAIL=0`，`GFSKIP=0`，`Q8 项 PASS=2 FAIL=0` | **0** | **56s** |
| ③ 基线轮 | `HEAD` 版脚本（读窗 3500/3000/3000，置于 `<SCRATCH>`，同一后端 / 同一桩 / 同一 29 用例） | `PASS=29 FAIL=0`，`GFSKIP=0`，`Q8 项 PASS=2 FAIL=0` | **0** | **52s** |

- **绿判据全中**（卡片 F-4.1）：退出码 0、`FAIL=0`。
- **F-4.2 复核**：GF5（`1888` 的成对重挂无新帧断言）与 TC23 ④（`2203` 的孤儿过滤断言）**均仍绿**，读窗放大未把「漏帧」转成「误报有帧」。
- **F-4.4 时序**：工作树版 56s vs 基线版 52s ⇒ **Δ = +4s**（预期上限 ≈ +5.5s = 三处窗口各 +1.5s/+2s/+2s，只在窗口读满时发生）。**未超预期**，无「早停反被拖慢」迹象。
- 用例集合逐条比对：两版 `[PASS]` 用例名集合 `diff` 为空（同一 29 主用例 + 2 个 Q8 项）。
- 端口纪律：三轮均在启动前 `netstat` 预检 18399 / 18330，并按**监听端口的 PID** `taskkill` 收尾（日志含 PID 与 `SUCCESS` 行），收尾后端口均已释放；未触碰任何非自启进程。

### 4.2 `m2t2a-smoke.mjs`（F-2 运行期实证）

编排：桩上游（18399）+ 后端 jar（18330，`AI_BASE_URL` 指向桩）+ 前端 dev（5202，`VITE_API_BASE` 指向 18330）+ Chrome CDP（9333）。

| 轮 | 冒烟脚本 | 场景 | 阶段 3a 观测 | 结论 |
|---|---|---|---|---|
| A | `HEAD` 版（未修改） | 桩正常路由 | `[阶段1 双消息]` 计数全 0 → `[断言②] FAIL` → FATAL（点击 `gf-cancel` 时元素为 null） | **阶段 1/2 的既有缺陷复现**：与本苞无关（见下方「既有缺陷」说明） |
| B | 工作树版（**只跳过阶段 1/2 的 scratch 副本**） | 桩正常路由 | `await stopReason = matched`，`stopped = true`，`capMs = 330000`，`capSource = /api/ai/health resilience.totalBudget=PT5M`，`seen = ["start","suspended","tool_start","frontend_tool_request"]`；**断言① PASS**（`pendingCards=1 / formRendererCount=1 / toolRunCount=1 / frontendToolResult=true / done=true`，收敛路径 `submit`） | **早停生效**：帧一到达即返回，`[阶段3]`→`[阶段3a]` 相邻日志时间戳相差 **< 2s**（卡片判据：< 30s） |
| C | 工作树版（同一 scratch 副本） | **桩不给挂起帧**（路由表 `GF-FILTER` 的 `messageAny` 改写为不命中，其余逐字相同） | `await stopReason = terminal:done`，`seen = ["start","message_start","delta","message_end","done"]`；断言① FAIL 的 detail 含 `awaitStopReason / awaitCapMs / awaitCapSource` | **无帧场景 stopReason 落在 `terminal:*`**（卡片 F-4.3 后半）；失败结论可读 = 「后端给了终帧」而非「窗内空读」 |
| D | `HEAD` 版（同一 scratch 副本 + 同一负向路由表） | 同上 | `await stopped = false`，`seen = ["start","message_start","delta","message_end","done"]`，文案 `阶段3a 超时未收到 frontend_tool_request`，`detail` 只有 `timeoutMs=240000` | **对照组**：旧版同一场景下**无归因**，且把「流在 `done` 后正常收尾」误报为「超时」——正是卡片 F-2 要消除的退化形态 |

**既有缺陷（本苞发现，非本苞引入，未修）**：`m2t2a-smoke.mjs` 的阶段 1/2（`injectExpr` → `ai.handleFrame(FRAME, m.id)`）在现仓下**必然失败**。根因 = `frontend/src/stores/ai.ts` 的轮次身份守卫（`activeRoundHostId`，`1157` 行 `ownedByActiveRound = assistantId === undefined || assistantId === activeRoundHostId`）：直连注入的帧带了宿主消息 id 而 `activeRoundHostId` 为 null（无 `send()` 建立的轮次），该帧被判为**旧轮迟到帧**而丢弃（非终帧直接 `return`），故 `message.pendingCall` 永不写入。该守卫来自 commit `99d00d2`（issue#5 R2/R6），晚于该冒烟最后一次绿跑；冒烟**未接 CI**（`docs/M2-排期计划.md` R-91 已登记「未接 CI」），故一直未被发现。**A 轮用 HEAD 版脚本复现同一失败**，证明与本苞改动无关。候选修法（未授权，见 §7）：注入后不带宿主消息 id（`ai.handleFrame(FRAME)`，让 `ownedByActiveRound = true`）并按序 append，使每条消息各挂同一 `toolCallId`，语义与现写法等价。

（B/C/D 三轮为取阶段 3a 运行期实证，使用 `<SCRATCH>` 下的 **scratch 副本**（生成器 `<SCRATCH>/baoF-e2e/make-harness.mjs` 只删除阶段 1/2 整段、其余逐字保留，`node --check` 通过、前 20 行与源文件逐字节相同）；**仓库内 `scripts/m2t2a-smoke.mjs` 的阶段 1/2 未被改动**。）

### 4.3 语法自检

| 对象 | 命令 | 结果 |
|---|---|---|
| `scripts/verify-e2e.ps1` | `[System.Management.Automation.Language.Parser]::ParseFile(...)` | `PARSE_OK`（0 错误） |
| `scripts/m2t2a-smoke.mjs` | `node --check` | `NODE_SYNTAX_OK` |
| 两版 scratch 副本 | `node --check` | `HARNESS_SYNTAX_OK` |

（`verify-e2e.ps1` 另有 §4.1 的三轮**真实执行**作为更强验证面。）

### 4.4 门禁

```
node scripts/ci/check-docs.mjs
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）    ← 919 路径，实扫 916，豁免 3；规则自检 5/5 + 24/24 + 负对照 11
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。（exit 0）
```

### 4.5 环境与工作树纪律

- 端口预检：每轮启动前 `netstat`（18399 / 18330 / 5202 / 9333）；redis（6379）由既有 Memurai 提供，本苞未启动 / 未停止它。
- 进程纪律：收尾一律按**监听自启端口的 PID** `taskkill //PID <pid> //F`，日志逐条留 PID；未使用按镜像名批量杀进程的手段。
- **首次尝试用 8081 起后端失败**（`APPLICATION FAILED TO START: Port 8081 was already in use`）：实读 `netstat` 显示 8081 上存在**宿主应用的非监听套接字**（既无 `LISTENING` 条目，又有 `ESTABLISHED` 落在本地 8081），故预检放行但绑定被拒。改用仓库既有 E2E 口径 18330 后全绿。**本苞未改任何脚本的默认端口**，该现象登记于 §7。
- 工作树纪律：全部实跑结束后 `git status --porcelain` = **20 项**（19 `M` + 1 `??`）：苞 F 只新增 **1 项**（`scripts/m2t2a-smoke.mjs`；`scripts/verify-e2e.ps1` 与 `docs/M2-排期计划.md` 本已是苞 A 的 `M` 项），其余 19 项为苞 A/B 既有改动；**无新增未跟踪文件、无 `data/` 残留、无构建产物入库**。
- 后端**未**全量重跑 `mvn -B test`：本苞改动面仅脚本与文档，**零后端文件**（`git status` 的后端项 = 11 个 `M` + 1 个 `??`，全部为苞 A/B 既有改动）。仅做了一次 `mvn -B -DskipTests package`（用于 E2E 的 jar，`BUILD SUCCESS`，`Tests are skipped`），jar 内实读确认已含苞 A 的双键（`confirm-timeout: 240s` / `frontend-tool-timeout: 120s`）与苞 B 的截断告知（`仅显示前` / `共 `）。

---

## 5. 与施工卡的对照（逐条）

| 卡片条目 | 要求 | 本苞实际 | 状态 |
|---|---|---|---|
| F-1.1 | `:1880`/`:2195` 的 `3000` → `5000` | `:1888` / `:2203` 改 `5000`（行号 +8 位移，见 §3.1） | 完成 |
| F-1.2 | 函数头注释后追加依据 | `:1307-1314` 新增 8 行，文案按卡片逐句落地（含 §2/§12 依据与裁决⑤编号） | 完成 |
| F-1.3 | `:1810` 的 `3500` 属【待指挥官裁决】 | 指挥官已授权同改 ⇒ `:1818` 改 `5000`；同窗口的注释 / 失败文案 / PASS 文案一并改实 | 完成（授权范围内，见 §6 偏差 1、2） |
| F-2.1 | `readSse` 增 `until` 终止帧 + `opts` 兼容旧 `deadlineMs` | 已实现（`:231-270`），`stopped:true, frame` 语义保留 | 完成 |
| F-2.2 | `stopReason` ∈ `matched` / `terminal:done` / `terminal:error` / `window`；FAIL detail 写入 | 已实现（`:262/264/269`；detail 见 `:342-348`）；实跑两态均取到（§4.2 B/C） | 完成 |
| F-2.3 | 硬上限由 `/api/ai/health` 的 `resilience.totalBudget` 推导 + 30s；解析失败回落具名常量并打日志 | 已实现（`deriveAwaitCap`，`:208-229`）；实跑取到 `capMs=330000` / `capSource=/api/ai/health resilience.totalBudget=PT5M`（= 300s + 30s） | 完成 |
| F-2.3 附注 | 不依赖 A-4-7（`/health` 增键） | 只用既有 `resilience.totalBudget`，零耦合 | 完成 |
| F-2.4 | `:362` 的 150s 死线只登记不改 | 未改 | 完成 |
| F-3 | 13 处 `Start-Sleep` 登记不改 | 代码零改动（`grep -c` = 13 前后一致）；登记见 §3 | 完成 |
| F-4.1 | 退出码 0、`FAIL=0` | 三轮全绿（§4.1） | 完成 |
| F-4.2 | GF5 / TC23 无新帧断言仍绿 | 两断言均 PASS | 完成 |
| F-4.3 前 | 有帧时 < 30s 返回（早停实证） | `< 2s`（§4.2 B） | 完成 |
| F-4.3 后 | 无帧场景 `stopReason` 落 `terminal:*` 而非 `window` | `terminal:done`（§4.2 C） | 完成 |
| F-4.4 | GF/TC 总时长不得显著变长 | Δ = +4s（< 预期 +5.5s） | 完成 |
| 裁决要点 3 | 登记「只追加、内容零删、按既有小节格式」 | 本苞对该文件零删除（§1.3） | 完成 |
| 裁决要点 4 | `:1387` 附近（苞 A 已改的键名注释）不得回退 | `:1392` 现值仍为 `app.ai.hitl.frontend-tool-timeout` | 完成 |

---

## 6. 偏差与处置

1. **N-4（`3500` → `5000`）由指挥官授权落地**：卡片 F-1.3 与 §5.2 N-4 将其列为【待指挥官裁决】，本苞任务书明确授权同改。已在函数头注释与 `R-100` 行内写明「三处调用点一律 5000」及判据，未改变窗口机制。
2. **同一窗口的三处陈旧描述同步（卡片未列）**：`1816` 注释、`1819` 失败文案、`1851` PASS 文案原写「3s」（与改前 `3500` 已不符），改 `5000` 后如不同步即留下与代码不一致的文案。属"改到即顺手改实"，已逐条同步。另注：`GF4` 的 PASS 文案因此变更，故 E2E **重跑第二轮（run2）** 以保证证据与最终文件一致。
3. **F-2 依据注释中的取值口径按实读改写**：卡片 JS 注释草案写「拆分后等人 600s / 等机器 60s」，而**工作树实读**（苞 A 落盘后）为 `confirm-timeout: 240s`（过渡值）/ `frontend-tool-timeout: 120s`。本苞按实读写注释（`189` 段），未照抄卡片草案 —— 否则注释即与仓内事实不符。
4. **断言① 判据必须同步改（卡片未点明，实现必改）**：`until` 命中时 `readSse` 返回 `stopped:true`，若沿用卡片外层逻辑 `if (!awaited.stopped || !awaited.frame)`，则「读到 `done`/`error`」会被误判为「等到目标帧」。故判据改为 `awaited.stopReason !== 'matched' || !awaited.frame`（`:340`）。已在 §2.3 对照表显式登记。
5. **登记节位置双落点**：卡片 §5.1 指定「去向表」锚点，指挥官任务书措辞为「等待治理排查登记注记节」。二者在本仓是**同一登记家族的两处**（该节正是 `R-95`~`R-99` 的登记说明节，表体在 `M2-T3a 闭环注记 · b) 遗留去向` 节内）。处置 = 正文行落**去向表表体内**（卡片锚点，`292`）＋该节内加**一行指针小节**（`357-359`），两处内容一致、不另立条目、不重复计数。
6. **`m2t2a-smoke.mjs` 阶段 1/2 既有缺陷**：见 §4.2。本苞**未改**（不在授权面内，且属"改测试断言语义"的独立变更）；因它使整脚本无法跑到阶段 3，故 F-2 的运行期实证改用**仓库外 scratch 副本**（只跳过阶段 1/2）取得，并同时以 HEAD 版复现该缺陷以证明与苞 F 无关。
7. **`8081` 起后端被占（宿主应用非监听套接字）**：见 §4.5。改为 18330（仓库 CI/本地既有口径）后全绿；未改任何脚本默认值。
8. **卡片 §5.2 的 N-5 / N-6 未登记**：二者与本苞同文件（`m2t2a-smoke.mjs` 的 150s 死线、合成帧 `120` 与 12×500ms 轮询），卡片称「建议同批追加」，但本苞任务书仅授权 13 处 `Start-Sleep` 的登记。故**只登记不改、且未写进文档**，列入 §7【待裁决】。

---

## 7. 遗留问题与【待裁决】

| # | 项 | 状态 / 建议 |
|---|---|---|
| 待裁 1 | **`m2t2a-smoke.mjs` 阶段 1/2 既有缺陷**（`activeRoundHostId` 守卫下直连注入帧被判旧轮帧丢弃，来自 `99d00d2`） | **建议单独立项修复**（一行级改动：注入口不带宿主消息 id + 按序 append，语义等价）；修完该脚本才可能整脚本绿。**本轮未改**（超出苞 F 授权面）。现状影响：该冒烟自 `99d00d2` 起阶段 1/2/3 全部不可达 ⇒ 仓内"CDP 冒烟"事实失效，与 `R-91` 登记的「UI 浏览器层盲区」叠加 |
| 待裁 2 | 卡片 §5.2 **N-5**（`m2t2a-smoke.mjs:362` 的 `deadline = Date.now() + 150_000` 写死） | 卡片口径「登记不改」，本苞同口径未改；是否补登记入 `docs/M2-排期计划.md` 待指挥官定（改法与 F-2 同族：事件驱动 + 推导上界） |
| 待裁 3 | 卡片 §5.2 **N-6**（`:143` 合成帧 `timeoutSeconds: 120` + `:239` `12 × 500ms` 轮询） | 同上；拆键后 `120` 已不对应任何键，与 `scripts/verify-e2e.ps1:1392`「不硬编码 120」形成**同仓两种相反做法**，建议至少加行内注记 |
| 待裁 4 | **A/B 的计时口径**：§4.1 的基线轮是「同一后端 + HEAD 版脚本」，总时长含自然抖动（±数秒） | 若要求更严的时序证据，可再跑 N 轮取中位数；当前 Δ=+4s 已落在预期的 +5.5s 之内 |
| 未做 | **"流保持打开且不给帧"的窗口烧满分支**未取运行期计时证据 | 需自制"黑洞"上游（200 + 持续心跳、不发帧）并把 `total-budget` 调小；本轮以 B/C/D 三轮 + 代码级论证替代（旧版无终帧早停判据，只能靠 deadline / 流关闭退出）。**如实登记为未做的验证面** |
| 未做 | `mvn -B test` 全量后端 | 本苞零后端文件改动（见 §4.5），按任务书「不动后端代码则无需全量重跑」执行；如需与苞 A/B 合并验收，由合并方统一跑 |
| 未做 | 前端 `yarn typecheck` / `yarn build` | 本苞零前端文件改动；E2E 与冒烟均已真实驱动该前端（5202 dev server） |

---

## 8. 证据落点（全部在 `<SCRATCH>` 内，无盘符路径）

| 证据 | 位置 |
|---|---|
| E2E run1 / run2 / 基线轮 编排日志与 E2E 原始输出 | `<SCRATCH>/baoF-e2e/orchestrate-run1.log` / `orchestrate-run2.log` / `e2e-run.log` / `orchestrate-baseline.log` / `e2e-run-baseline.log` |
| E2E 桩 / 后端原始证据（boot.log、req-*.json、gfc-*.sse.txt / .http.txt） | `<SCRATCH>/baoF-e2e/artifacts/`、`artifacts/stub/`、`artifacts/gfc/`、`artifacts/gfc-baseline/` |
| 冒烟 A/B/C/D 四轮日志与结果 JSON | `<SCRATCH>/baoF-e2e/smoke-baseline-stage12.log` / `smoke-harness-positive.log` / `smoke-harness-noframe.log` / `smoke-baseline-noframe.log`；结果 JSON 在 `<SCRATCH>/baoF-e2e/smoke-out/result-*.json` |
| scratch 副本与生成器（阶段 1/2 跳过版、负向路由表、HEAD 版脚本） | `<SCRATCH>/baoF-e2e/make-harness.mjs`、`smoke-f3-harness.mjs`、`baseline-smoke-f3-harness.mjs`、`baseline-verify-e2e.ps1`、`baseline-m2t2a-smoke.mjs`、`no-route-routes.json` |
| 编排脚本（端口预检 + 就绪等待 + 收尾） | `<SCRATCH>/baoF-e2e/run-e2e.sh`、`run-e2e-baseline.sh`、`run-smoke.sh` |
| 打包日志（E2E 用的 jar） | `<SCRATCH>/baoF-e2e/mvn-package.log` |
| 门禁输出 | `node scripts/ci/check-docs.mjs`（6/6，见 §4.4） |
