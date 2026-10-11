> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③a 决策卡（K3 代签终裁记录），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 「对话时序包 · 批 3 ③a」决策卡（K3 三方会审终裁全量书面化）

| 项 | 值 |
|---|---|
| 文档定位 | **决策卡（终裁记录）**。本稿 = K3 指挥官终裁的逐条书面化；与《批3③a-施工卡.md》（定稿）配套，二者不一致时以施工卡为准（施工卡不得超出本卡授权的范围） |
| 执行者 | **Kimi K3 指挥官**（**本决策卡为 K3 代签**，署名口径 DC-19） |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（Kimi Code 子智能体，模型显式钉版；本棒只落稿：**零施工、零 git 写、未改仓库任何文件**） |
| 授权来源 | **K3 指挥官 2026-10-11 三方会审汇总定稿指令**（会审材料：`批3③a-施工卡草案.md` 380 行 + `会审-批3③a-GLM.md` + `会审-批3③a-DSPro.md`） |
| 仓库 | `<MAIN_REPO>`（⚠️**严禁** `<OTHER_REPO>（另一条线的仓，严禁触碰）`——无共同祖先的另一条线） |
| 实读基线 | **HEAD `3edaeb9`**（`3edaeb900dcc446b918913738a6f527c780e7b81`），工作树干净（`git status --porcelain` 空）【落稿棒实测】 |
| 落盘 | `<SCRATCH>/chat-timeline-pkg/决策卡-批3③a.md` |
| 纪律 | 零 git 写；未读任何 `.env`；未改仓库任何文件；本棒唯一写入 = 本决策卡 + 《批3③a-施工卡.md》 |

**会审总判定（两方一致）**：**带条件可施工，不返工**。D1~D12 无一条须整体推翻；两方的硬条件（GLM 条件 1~3、DS Pro C1~C5）全部并入终裁；其余条件（申报/测试锁/台账）落进施工卡。

---

## §0 终裁一句话

> **③a = 「工具结局不得谎报」的结构化收口**：把前端工具/页面动作的真实结局从**字符串/布尔二元**升级为**结构化三态**（`ok` / `failed` / `unknown`），让「预算到期」「页面没超时等到就绪」「页面 handler 如实报失败」三类既有事实，在**工具卡面**与**回灌给模型的文本**上如实呈现；并一次性钉全 `unknown` 之所以能存活的**四个连带读点**（`localTerminal` / `isFrameDecidedPendingStatus` / `pendingStatusOf` / `applyPendingTerminal`+`expireResidueToolParts`）。落点在**存量投影世界**（`parts` 已是唯一真值源），不依赖 ①/②，**不触后端**。

---

## §1 两方交叉命中（**无条件采纳**，落进施工卡）

### X1 · `pendingStatusOf` 的 `default` 支折叠 `unknown`/`failed`（**GLM §2.2 头号读点**）

- **事实（落稿棒实读 `frontend/src/utils/parts.ts:105-124`）**：`default:`（`:120-122`）把 `pending` / `failed` / `unknown` 一律折成 `'pending'`，而 `:121` 注释断言「后两者无挂起语义」——**代码与注释不互锁**。`pendingCall` 的生成条件是 `part.requiresAction === true`（`deriveProjections`，`parts.ts:181-183`），故 `status:'unknown' && requiresAction:true` 的段会被投影成 `pendingCall.status:'pending'` = **挂起卡复活**。
- **终裁**：**必修**。显式 `case 'unknown'`，返回值 = `'pending'`（因为 `PendingToolCall['status']` 为 8 态联合、**无 `unknown`**，见 `frontend/src/types/ai.ts:324`；保守映射是唯一不破类型的诚实落点）+ **dev 断言「`unknown` 段 `requiresAction` 恒 false」** + **测试锁 T2**。
- **定位**：这是 A9/A10 之外的**第三处 `unknown` 连带读点**（草案完全未列）。

### X2 · D3/D9 登记清单事实订正（**GLM 条件 3 + DS Pro C4 合流**）

| 项 | 草案 | 实读订正（3edaeb9） |
|---|---|---|
| 导入侧失败形字符串 | 10 处 | **11 处**——补 `frontend/src/views/ImportWizardView.vue:1074-1076`（`publishJob.value ? '发布作业 #… 已启动…' : '发布未启动'`，带成功支三元） |
| `ImportWizardView.vue:1047` | 未列 | **定口径 = 降级/指引形**（`上传文件需要浏览器文件句柄：请提示用户点击…`，无失败声明语义），**计入登记清单并标注**（不得与失败形混算） |
| 导出侧 `:1181` | 「带成功支三元/降级形」 | **非三元**——单一模板串 `return`（主句成功语义 + 括注「当前未绑定任务…推进步骤时会尝试落库」），定性 = **降级形** |
| 导出侧真三元 | — | **`:1204`**（`exportJob.value ? '导出作业 #… 已启动' : '导出未启动（请检查配置项选择）'`） |
| 「带成功支三元/降级形」合计 | 3 处 | **4 处** = `ExportWizardView.vue:1204` + `ImportWizardView.vue:1056` / `:1065` / `:1074-1076` |

- **终裁**：**登记清单按 X2 订正重列**（错清单不得流入后续批次）。

---

## §2 逐条终裁（D1~D12）

> 每条格式：**K3 终裁** / 两方评议要点 / 采纳-驳回 / **被否决方案的坑** / **推翻条件**。

### D1 `outcome` 引入后 `ok` 的语义

- **K3 终裁**：走 **(A) 语义收窄**（`ok === (outcome === 'ok')`，`unknown ⇒ ok:false`），并**全部采纳两方修正**：
  1. **工厂函数收口**（GLM 修正①）：`utils/frontend-tools.ts` 内 `ok:` 生产点（`:119`~`:891`）**全改走单一工厂**（形如 `makeExecution(outcome, …)`，由工厂推导 `ok`），一致性断言**单点化**——逐点手写 dev 断言必漏。
  2. **`:864` dispatchUiEvent 路径补进施工清单**（GLM 修正②）：`ok: outcome.handled` 须同步推导 outcome（`handled:true` 且副作用已 `await` 落地 ⇒ `'ok'`；`fail` ⇒ `'failed'`）。
  3. **DS Pro 附注全采纳**：A8 落地后 `stores/ai.ts:1804` 改读 `outcome`，`ok` 标 `@deprecated`、**接受零读者**，不留长期双真源。
- **两方评议**：GLM = 同意+修正（`execution.ok` 全仓唯一消费点 = `stores/ai.ts:1804`，迁移面极小；但须工厂收口 + `:864` 入清单）；DS Pro = 同意（`availability` 唯一读点 `:1815`，`outcome` 全仓零读点，爆炸半径 = 1 行；附注 `ok` 应弃用）。
- **采纳/驳回**：全部采纳；无驳回项。
- **被否决方案的坑**：
  - **(B)**（`unknown ⇒ ok:true`）：任何**未迁移**读点**今天就开始谎报成功**（`:864` 的 `ok: outcome.handled`、availability 判读等）——风险方向最差。
  - **(C)**（`ok` 不动、只加 `outcome`）：等于把一致性问题留给未来，契约层会出现 `ok:true && outcome:'failed'` 这类自相矛盾组合。
- **推翻条件**：若施工中实读发现 `ok` 存在**第二个真实读点**且其语义无法由 `outcome` 表达（届时先补工厂再重议）；或 `outcome` 与 `ok` 的一致性无法在类型层/工厂层强制（则必须保留 dev 断言并逐点列清单）。

### D2 `unknown` 的判据边界

- **K3 终裁**：维持判据 =「**动作已发起/已受理，但时限内无法确认其真实结局**」。采纳 DS Pro 补充并要求**施工卡显式点名**：`open_export_file_editor` 的 handler 返回**失败形字符串**（`ensureGridLoaded` 返回 `{ok:false}` → `ExportWizardView.vue:1236`「表格未就绪…」）= **确定性失败，落 `failed`，不得误入 `unknown`**。执行器 `throw` 维持落 `failed`（`frontend-tools.ts:843-849` / `:889-894`）。
- **两方评议**：GLM = 同意（判据与卡面文案「未确认成功/失败」严格对应，throw/{ok:false} 落 failed 正确）；DS Pro = 同意（补 `:1236` 点名，防误判 unknown）。
- **采纳/驳回**：全部采纳。
- **被否决方案的坑**：把 `throw` / `{ok:false}` 也归 `unknown` ⇒ `unknown` 退化成**万能兜底**，失去信息量，且模型会在确定性失败上放弃修复。
- **推翻条件**：若实测出现「执行器抛异常但其副作用可能已落地」的真实形态（须**实测证据**，非推断）⇒ 仅为该执行器单独判 `unknown`。

### D3 R-84 兼容口径 vs 仓内失败形字符串

- **K3 终裁**：**维持 (iii) 主体 + 修订**：
  - 本批**不改**导入侧 **11 处**失败形字符串（**登记待批**）；登记清单按 **X2** 订正重列。
  - **删除草案「ImportWizardView 两在飞批次撞车」表述**——仓内**不可证实**（两视图自 `97711b5` 后零改动、工作树干净、单分支单工作树）；**仓外经 K3 确认**：步骤条包①已闭环入库、轮询治理③在队列未开工、**无在飞会话写该文件**。
  - 理由改写为：**范围控制**——登记清单刚经两方订正、与 `unknown` 主链无关、不宜搭车（一次施工一次回归）。
  - **耦合红线（DS Pro）**：D3(iii) 的兼容口径须与 **A4**（执行器返回类型放宽）+ **A5**（`defaultExecutor` 结构化透传）**同一提交**；否则 `open_export_file_editor` 的 `{ok:false}` 会被 `defaultExecutor:990`（`typeof outcome === 'string' ? … `）折成**成功兜底**。
- **两方评议**：GLM = 同意+修正（清单三处事实错必须重列；撞车依据仓内不可证实）；DS Pro = 同意 (iii)（清单须订正；耦合提示如上）。
- **采纳/驳回**：采纳 (iii)；**驳回**草案沿用撞车表述；**驳回** (i)/(ii)。
- **被否决方案的坑**：
  - **(i)** 逐注册点结构化：撞总卡 §5.1「除 W-06/W-07 不得再动 `ExportWizardView.vue`」红线，且触 `ImportWizardView.vue`（共用文件）。
  - **(ii)** 只加通道不改调用点：仓内**无任何生产者**产出结构化失败，A3/A4 沦为「能力已具备、无人使用」，**R-84 名不副实**，预期行为 1/3 均不可达成。
  - **沿用「两在飞批次撞车」**：把**规划面**当成**在飞实况**，会误导后续批次排期判断。
- **推翻条件**：若 R-137 批或步骤条包①后续确需动导入侧 handler 失败分支 ⇒ 导入 11 处结构化应与其**同批**（改判 (i)）；若仓外出现真实在飞会话写 `ImportWizardView.vue` ⇒ 立即降级为「登记待批」并暂停本批对该文件的任何写入。

### D4 A6 的两个待定落点

- **K3 终裁**：
  - **(a)** `ExportWizardView.vue:767-772`（`waitForPageReady` 3s 超时支）改 **`outcome:'unknown'` + `ok:false`**，且 **`availability` 由 `'available'` 降 `'degraded'`**（**K3 补钉**，解决 DS Pro C5）。依据：①触发 `stores/ai.ts:1815` 的降级 notice，让用户/模型有可见提示；②与 `timeoutOutcome`（`frontend-tools.ts:425` 的 `ok:false, availability:'degraded'`）口径一致；③同文件 `:776-781`（契约层拒绝支 `ok:false` + `degraded`）**已是先例**。文案**不动**（已含「稍后重试或提示用户手动操作」的行动指引）。
  - **(b)** `:774` **不改**；**论据订正**：`goto_step` 支（`stores/workspace.ts:392-401`）**无只读守卫**（`select_defs` / `set_conditions` 有，`:407-409` / `:422-424`），只读态下 AI 的 `goto_step` **仍真写**「不推翻 (b)」（写已落地即真话），落成**与 R-137 的守卫耦合申报条目**（施工卡 E 表 + 施工报告）。
- **两方评议**：GLM = 同意+修正（(a) 二分证据强；(b) 论据须订正为 goto_step 无守卫）；DS Pro = (a) 同意、(b) 同意带保留（同指 `:767-772` 的 `availability` 未钉死；`goto_step` 无守卫属 R-137 面仅登记）。
- **采纳/驳回**：全部采纳；**驳回** (b) 整支改判 `unknown`。
- **被否决方案的坑**：
  - **(a) 保持 `availability:'available'`**：`:1815` 降级 notice 不触发 ⇒ 卡面 `unknown` 但**无可见提示**，与「不得静默留白」冲突。
  - **(b) 改判 `unknown`**：把「已 `await` 落地真写」标成未确认，是**口径收紧**而非缺陷修复，且会让模型对已发生的写操作重复动作。
  - **合并 `:758-766` 与 `:767-772` 为同一结局**：等于把「可能只是慢」标成「确定失败」，模型会据此**放弃重试**（R-c 风险）。
- **推翻条件**：若 R-137 为 `goto_step` 补只读守卫后，出现「只读态 `goto_step` 被拒却仍回灌成功」的实际面 ⇒ (b) 须重新裁决；若 `:767-772` 的 `degraded` 造成 notice 对活跃用户的过度打扰（实测证据）⇒ 改回仅文本传达（须补其他可见通道）。

### D5 `localTerminal` 纳入 `unknown` + 「本地胜/帧胜」总口径

- **K3 终裁**：**维持 (甲)「本地胜」**（first-wins 单调）+ **充分性分层处置**，**采纳 DS Pro §二-3 定性**（「一行收口」表述作废）。**③a 内修四处**：
  1. `localTerminal`（`utils/parts.ts:358-364`）**补 `unknown`**（原案一行）；
  2. `stores/ai.ts:1704-1705` `applyPendingTerminal` 覆盖域（`pending || cancelled || requiresAction`）**显式排除 `unknown`/`failed`**；
  3. `stores/ai.ts:246` `expireResidueToolParts` **不把 `unknown` 翻 `expired`**（与 `:233` `sanitizeMirrorResidues` 口径对齐）；
  4. `pendingStatusOf`（**X1**）。
- **两处结构性缺口登记移交 ③b，本批不修**：① **re-attach 新宿主消息**（`stores/ai.ts:1008`，`lastSeq` 刷新后全量回放 → 新宿主建 `succeeded` 新卡、旧 `unknown` 卡并存）；② **loadHistory 整替**（`stores/ai.ts:631-663`，`:644` 工具段状态**硬编码 `'succeeded'`**）。理由：该两路对 `failed`/`cancelled` 系**存量同族**，修法涉**消息级→店级去重设计**，属 ③b「一卡全生命周期」范畴。
- **处置要求**：写入施工卡 **E 表扩充** + 施工报告**申报已知代价**（`unknown` 经此两路仍可被翻成 `succeeded`/重复卡），**不静默留白**。
- **施工卡同时写明**：`parts.ts:355-356` 注释补「**含 unknown**」；`submitFrontendToolResult` 的 POST payload **不带 `status`/`ok`**（`frontend/src/types/ai.ts:66-78` 仅 `runId`/`toolCallId`/`result`/`source`/`cancelled`）⇒ **`localTerminal` 是 `unknown` 存活的唯一机制** ⇒ **T1 为生死线用例**。
- **两方评议**：GLM = 同意（亲读六值三分支；补强证据 = payload 无 status 通道）；DS Pro = 同意 (甲) 方向但判定降级（`FRONTEND_RESULT` 支只是回执——后端 `ConfirmGate` 硬编码 `ok=true`、`SseChatEmitter` 取 `pending.getStatus()`，不含比前端已提交更多真相；两条结构性路径失守）。
- **采纳/驳回**：采纳 (甲) + 四处同型面 + 两处移交；**驳回 (乙)**「全改帧胜」；**驳回「一行即收口」的表述**。
- **被否决方案的坑**：
  - **(乙) 全改帧胜**：后到的 `FRONTEND_RESULT` 会把**用户取消**抹成「已成功」（把人的动作改写成成功，方向危险）；且须破既有测试断言（`frontend/tests/frontend-tool-result-terminal.spec.ts`）。
  - **只补 `localTerminal` 即宣称收口**：re-attach/loadHistory 两路与 `localTerminal` 无关，`unknown` 仍会被翻成 `succeeded` 或产生**两张矛盾卡**。
  - **不修 `:246`/`:1705`**：`unknown + requiresAction=true` 会被 `expired`/终态改写，同一状态两种结果（取决于正交字段 `requiresAction`）。
- **推翻条件**：若 ③b 完成「帧写路径先过店级 `hasDecidedPendingResidue` + 历史段状态不硬编码 succeeded」设计 ⇒ 本批移交项须在 ③b 卡**显式承接**（否则不得销记）；若实测出现 `unknown` 经 re-attach/loadHistory 造成**用户可见矛盾**的现场 ⇒ 该两路提前立项修复。

### D6 `isFrameDecidedPendingStatus` 纳入 `unknown`（+ `failed`）+ 保护面申报

- **K3 终裁**：走 **(A) 扩充：纳入 `unknown` **和** `failed`**（`stores/ai.ts:374-377` 现五态 `succeeded/rejected/blocked/expired/cancelled`）。`failed` 缺项 = **DS Pro C2**，与 A10 同族**第二处遗漏**（同为终态）。
- **同时采纳 GLM 收窄申报**：两条帧路守卫均带 `GENERATIVE_FORM_TOOL` 前缀（`stores/ai.ts:1573` / `:1620`），故本改动**实际保护面** = ①**快照恢复路**（`:713` / `:725`，全条目生效）+ ②**GF 帧路**；**通用 `FRONTEND` 工具的重投 `frontend_tool_request` 帧现状就不受拦截**（`:1590` 直跑 `void this.runFrontendTool(...)`）= **与 `unknown` 无关的既有缺口**，归 **③b**。施工报告**如实申报、不得销记「重挂已闭合」**。
- **两方评议**：GLM = 同意+修正（保护面比草案宣称的窄，必须申报）；DS Pro = 同意 (A) 且须一并纳入 `failed`（现缺，导致 `hasDecidedPendingResidue` 对本地 `failed` 段失效）。
- **采纳/驳回**：全部采纳；**驳回 (B)**（不纳入、转 ③b）。
- **被否决方案的坑**：
  - **(B) 不纳入**：`unknown` 段被视为「待决」⇒ 重投/回放把同一 `toolCallId` 重新挂起；批 2 已删 `pendingCard`，视觉后果（工具卡被改回 `pending` 重新等待）与 ③b「一卡」目标**直接冲突**。
  - **宣称「重挂已闭合」**：通用工具重投帧不经守卫，属**错误销记**。
- **推翻条件**：若 ③b 段状态机把守卫扩到全工具（去 `GENERATIVE_FORM_TOOL` 前缀）⇒ 本条保护面限制自动消解，须在 ③b 卡注明；若 `failed` 纳入后出现「本地 failed 段被合法重投」的正向场景（实测）⇒ 须为 `failed` 单独口径（不得回退为不纳入）。

### D7 W-06 的修法（`goStep` 形状与合并语义）

- **K3 终裁**：**采纳 DS Pro 合并语义，驳回 GLM 的 `persist ∧ sync` 双阻塞；采纳 GLM 5 调用方修正**。
  1. `goStep(): Promise<结构化结局>`，**永不 reject**（失败也 resolve）——模板 `ExportWizardView.vue:14` 绑定安全（否则 `UnhandledRejection`）。
  2. **合并语义**：`persistStepData` 失败 ⇒ **阻断推进**（`return` 失败结局）；`syncStepToBackend` 失败 ⇒ **推进界面 + 警告**（页面可见 `ElMessage` + 回灌文案附「**步骤已推进但服务端步骤同步失败，下次切步会重试**」），**不阻断**。
  3. `ensureStepLoaded` **不纳入结局并注释**。
  4. **调用方 5 处全改**（GLM 修正①；草案「2 处」是把 `goStep` 体内 `void` 调用误当外部调用方）：`ExportWizardView.vue:14`（模板 `@prev`）/ `:599`（`nextStep`）/ `:1128`（`preflightDownload`，`goStep(2)` 后**无条件** `return {ok:true}`）/ `:1146`（`export.nextStep` handler，**W-06 撒谎点本体**）/ `:1225`（open-editor handler）。**`:1128` / `:1225` 是草案漏掉的 W-06 残余入口，必须取结局**。
  5. `persistStepData`（`:622` `ElMessage.error` 后返回 `void`）**一并改返回值**。
  6. 台账 `docs/M2-排期计划.md:289`（R-97）「两个调用方」与实态 5 处不符 ⇒ **记入收尾台账订正清单**（行尾注记方式，**本批不动 M2**）。
- **两方评议**：GLM = 同意+修正（调用方 5 处；蓝本自身不闭合 W-07——导入侧 `:727` `await syncStepToBackend()` 内部 `:763` 空 catch 吞错后照返 `true`）；DS Pro = 同意 (A) 但须先定清合并语义（`persistStepData` 阻断、`syncStepToBackend` 推进+警告；否则违背 `:590` 可用性设计）。
- **采纳/驳回**：采纳 DS Pro 合并语义 + GLM 5 调用方修正；**驳回** GLM 的 `persist ∧ sync` 双阻塞。理由：`syncStepToBackend` 同步的是服务端 `currentStep` **光标**、失败**下次切步可自愈**，阻断会把**网络抖动升级成向导卡死**，违背 `:590` 的可用性设计。
- **被否决方案的坑**：
  - **`persist ∧ sync` 双阻塞**：网络抖动 ⇒ 整轮 AI 动作失败/向导卡死（`:590` 明说「不阻塞界面操作（下次切步会重试）」）。
  - **只字面对齐导入蓝本**：蓝本只保证「步骤推进了」、不保证「服务端 `currentStep` 同步成功」⇒ 会把 W-06 谎报收窄却**复制** W-07 的静默。
  - **`goStep` 会 reject**：模板 `:14` 直接绑定 ⇒ 未处理拒绝；且 `:1146` handler 内的 throw 语义会串味。
- **推翻条件**：若服务端 `currentStep` 被判定为「不推进即不可用」的强一致要求（例如切步即触发不可重试的服务端副作用，须实测证据）⇒ 重新裁决为阻断；文案措辞若经实测引起用户误解 ⇒ 只改措辞、不改合并语义。

### D8 W-07 的修法（空 `catch` 改成什么）

- **K3 终裁**：走 **(A) 结构化失败 + 页面可见提示（`ElMessage`，同文件 `:622` 已有范式）+ 供调用方读取的返回值**。口径：**任何非 2xx（含未来 R-137 守卫的 4xx）都落结构化失败**（不得只覆盖「网络错误」）。回灌文案形状**随 D7 定稿**（含警告措辞）。
- **两方评议**：GLM = 同意（亲读 `:587-591` catch 体零语句仅注释；`:622` 范式现成）；DS Pro = 同意（(B) 违背 `:590`；(C) 仍无人知；附注文案形状随 D7 定）。
- **采纳/驳回**：采纳 (A)；**驳回 (B)/(C)**。
- **被否决方案的坑**：
  - **(B) `throw`**：把一次网络抖动/守卫 4xx 升级成整轮 AI 动作失败。
  - **(C) 仅 `console.warn`**：仍无人知（用户与模型都不知道 `currentStep` 未同步）。
  - **只覆盖「网络错误」**：R-137 落地状态守卫后会新增**确定性 4xx**，若口径不写「任何非 2xx」，这类失败会被重新静默。
- **推翻条件**：若 R-137 先落地并实测「4xx 属正常业务流」（例如未开始任务的 `goto_step` 被拒是预期分支）⇒ 文案须分层（口径不推翻，4xx 与网络错误分别给措辞）。

### D9 W-07 同型面（`ImportWizardView.vue`）是否本批纳入

- **K3 终裁**：**升级 (B) → (A)：本批修导入侧空 `catch`**。
  - **升级依据**：①撞车前提**已失效**（仓内 GLM 证伪 + 仓外 K3 确认**无在飞会话**写 `ImportWizardView.vue`）；②**一次施工一次回归**；③与 D7 语义同源，闭合 **W-07 双生面**（导入侧 `goStep:727` `await` 吞错的 `syncStepToBackend` 后照返 `true`）。
  - **范围限定**：只修 `ImportWizardView.vue:761-765` 空 `catch`（D8 (A) 同法：结构化失败 + 页面可见 + 返回值）；导入侧 `goStep:727` 对结局的读取按 **D7 同款语义最小接入**（失败**不阻断**、附警告）；**导入侧 11 处失败形字符串登记项不动**。
  - **C6 采纳**：**空 `catch` 同型面全量登记**，落施工卡 E 表/登记清单，**defer 边界定清**（清单见施工卡 §3 E18）。
- **两方评议**：GLM = 同意+附升级条件（撞车依据仓内不可证实；若 K3 确认无并行会话则升级 (A) 更优）；DS Pro = 同意 (B) 但指出同型面清单严重低估（仅注释型空 catch 两向导 7 处 + 全仓另若干 + `.catch(()=>[])` 5 处）。
- **采纳/驳回**：采纳升级 (A) + C6 全量登记；**驳回**导入侧失败形字符串一并搭车。
- **被否决方案的坑**：
  - **维持 (B)**：同一缺陷在导入侧多留一批（`goStep:727` 的 W-07 双生面继续把「步骤已推进」谎报为「服务端已同步」）。
  - **搭车导入 11 处失败形字符串**：超出本批范围（清单刚订正），且与 `unknown` 主链无关。
- **推翻条件**：若 K3 后续发现**仍有在飞会话**写 `ImportWizardView.vue`（仓外实况变化）⇒ 立即撤回本升级、降级为 (B)（**撞车优先**），并把已开工部分回退。

### D10 `AiPanel.toolTagType` 去 `default`

- **K3 终裁**：**做 (A)，含 A13 事实订正**。`AiPanel.vue:537-561` 实为 **8 态显式 + `pending` 走 `default`**（`:558-559` `default: 'primary'`），草案「九态全显式」**不成立**。去 `default` 后 **`pending` 补显式 `case`**；形式对齐 `toolStatusLabel`（`:563-579`）的 `Record<ToolRunStatus, string>` **穷尽闸**。
- **两方评议**：GLM = 同意+实现注意（`default` 兜的是 `pending`，**非纯死码**；去 default 须补 `pending` case）；DS Pro = 同意（低价值可降 B；事实订正同前）。
- **采纳/驳回**：采纳 (A)。
- **被否决方案的坑**：**(B) 不做** ⇒ 未来新增态由 `default` 静默兜成 `'primary'`（**误标**），丧失编译期拦截（总卡 §5.2 ⑥ 的原意）。
- **推翻条件**：若 K3 后续判定本批须严格聚焦「可见谎报」⇒ 可降为 (B)，但降级时**须以一行登记留档**（不得静默不做）。

### D11 回灌文本是否加机器可读前缀

- **K3 终裁**：**同意 (A)：不加机器前缀**。**论据订正**：草案引 `frontend/src/types/tools.ts:118`「同时给模型和人看」**不实**（原文仅「回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result）」，全文件无「给人看」表述；`:184` 的「展示文案 + 给模型看」属**另一接口**）。论据改为：**payload 契约里结局只有单字段 `result`（文本），结构化结局走 `outcome` 字段而非文本编码**（`types/ai.ts:66-78`）。
- **两方评议**：GLM = 同意（论据订正同上）；DS Pro = 同意（文本嗅探是退化；前缀属行为契约变更需单独评估提示词影响）。
- **采纳/驳回**：采纳 (A)；**驳回 (B)**。
- **被否决方案的坑**：加固定前缀会**污染人可见文本**（卡内文即 `result`），且属**行为契约变更**（模型提示词影响未评估）；台账 R-97 的「机器可读前缀」是**后端 W-05** 场景的备选手段，不适用本批。
- **推翻条件**：若后续出现「模型必须机器判定结局」的真实需求（例如结构字段在上游链路被清洗丢失，须实测证据）⇒ 另立项评估。

### D12 验收口径：CDP 冒烟是否强制覆盖 A2/A6

- **K3 终裁**：**同意 (A)：CDP 冒烟强制覆盖 A2/A6（至少 A2）**。**+GLM 补充**：**A9 的验证法 = 等回执帧到达后一拍再截图**（证 `unknown` 不被翻成成功）。
- **两方评议**：GLM = 同意（本批价值全在卡面，CI 均不驱动 DOM；A9 可用「等一拍再截图」验证）；DS Pro = 同意（至少 A2 一条必须留证）。
- **采纳/驳回**：采纳 (A) + GLM 补充；**驳回 (B)**。
- **被否决方案的坑**：仅单测 + 人工复现 ⇒ **无截图即无证**；本批的全部价值（卡面）恰是 CI（vitest / E2E 桩均不驱动 DOM）覆盖不到的面。
- **推翻条件**：机时受限时的**降级底线 = 至少 A2（结局未知）一条**（已写入施工卡）；若 `unknown` 无法在真环境稳定触发（须实测）⇒ 允许以「极小 `budgetMs` 注入」替代，但须在报告中申报注入方式。

---

## §3 归属订正（GLM 条件 4；**施工报告 + 决策卡必写**）

| 事实 | 订正内容 |
|---|---|
| `unknown` / `cancelled` 两个枚举成员的落地 | **批 1（`1efab09`）**落地，**不是批 2** |
| 枚举历史 | 6 态（`b2b2ec5`）→ 7 态（`ad3ffb8`，+`blocked`）→ **9 态（`1efab09`，+`unknown` + `cancelled`）** |
| 「批 2 完成 7 态收敛」 | **叙述作废**（无 7→8→9 收敛事件，是 7→9 一步到位且发生在批 1） |
| 批 2（`6bea4ca`）对枚举行的改动 | **未改枚举行，只改注释** |
| 草案 A8 / A14 的「批 2 之后 `unknown` 已进 `ToolRunStatus`」「状态枚举收敛已由批 2 实质完成」 | **归属错误**，回归归因与「批 2 基线」叙述须订正 |

> **落稿棒实读复核（`git log -L 165,165:frontend/src/types/ai.ts`）**：`b2b2ec5`（新建 6 态）→ `ad3ffb8`（+`blocked`）→ `1efab09`（+`unknown` + `cancelled`）= 9 态；批 2 的 `6bea4ca` **未出现在该行历史中** ⇒ 订正成立【实测】。

---

## §4 原子提交红线（**R-a 终版**）

> 以下改动 **必须在同一提交**（缺一即产生可见回归或带病落地）：

1. **A4**（执行器返回类型放宽）
2. **A5**（`defaultExecutor` 结构化透传）
3. **A8**（`stores/ai.ts:1804` 三元落卡，改读 `outcome`）
4. **A9**（`localTerminal` 补 `unknown`）
5. **A10**（`isFrameDecidedPendingStatus` 纳入 `unknown` + `failed`）
6. **`pendingStatusOf`（X1）** 显式 `case 'unknown'`
7. **`ai.ts:1704-1705`（`applyPendingTerminal` 覆盖域）** 与 **`ai.ts:246`（`expireResidueToolParts`）** 两处同型面
8. **D3(iii) 兼容口径**（`runPageHandler` / `runRegisteredTool` 对「字符串返回」的解读与注释）
9. **测试 T1 / T2 / T3**

**理由**：`unknown` 一旦有生产者，上述任一处缺失即回归——A4/A5 缺 ⇒ `{ok:false}` 被 `defaultExecutor` 折成成功（D3 耦合红线）；A9 缺 ⇒ 结局未知被回执帧翻成**已成功**（比现状更糟）；A10/X1/:1705/:246 缺 ⇒ 挂起卡复活 / 终态被改写。**R-a 由草案的「A8/A9 同一提交」升级为上述 9 项同一提交。**

---

## §5 登记清单（**落 M2 待收尾提交官办理；登记必带落点**）

### R 编号口径

- **下一空号 = `R-141` 起用**（`R-138` / `R-139` / `R-140` 已占用，`docs/M2-排期计划.md:327-329`）。
- `R-110` / `R-102` / `R-103` 为既有留空号，**不取用**。

### R-141（③a 移交 ③b 包 —— 四项，含两项本批已判定不修的结构性缺口）

**状态**：已登记·待排期（本批只登记、不施工）。**去向 = 对话时序包 ③b（「一卡全生命周期」）**。

| # | 内容 | **落点（必带）** |
|---|---|---|
| ① | **re-attach 新宿主消息**：`reattachRun` 新建「正在重挂」助手消息，`lastSeq` 刷新后服务端全量回放 ⇒ `findToolPart(新消息)` 找不到旧段 ⇒ `createIfMissing` 建**新 `succeeded` 卡**，旧 `unknown` 卡并存（**两张矛盾卡**）。同族于 `failed`/`cancelled` 存量面 | `frontend/src/stores/ai.ts:1008`（`appendAssistantMessage('（正在重挂进行中的轮次…）')`） |
| ② | **`loadHistory` 整替**：历史非空整体替换 `messages`，**工具段状态硬编码 `'succeeded'`**（`kind2:'UNKNOWN'`），不经段级判定 ⇒ 本地 `unknown` 段连段带状态被丢弃 | `frontend/src/stores/ai.ts:631-663`，**硬编码点 `:644`**（`status: 'succeeded'`） |
| ③ | **通用 `FRONTEND` 工具重投帧不经守卫**：两条帧路守卫带 `GENERATIVE_FORM_TOOL` 前缀 ⇒ 通用工具的重投 `frontend_tool_request` 帧现状**不受 `hasDecidedPendingResidue` 拦截**（与 `unknown` 无关的**既有缺口**） | `frontend/src/stores/ai.ts:1573` / `:1620`（GF 前缀），`:1590`（`void this.runFrontendTool(...)` 直跑） |
| ④ | **两张翻译表穷举口径分叉**（登记防漂移）：`frontendResultStatus` 有 `SUCCEEDED`、`frameStatusToPendingStatus` 缺；后者缺 `CANCELLED` 依据注释 | `frontend/src/utils/parts.ts:210-228`（`frontendResultStatus`）与 `frontend/src/stores/ai.ts:333-357`（`frameStatusToPendingStatus`） |

### R-142（`stop()` 后在途前端工具仍写段 + 回灌残留）

**状态**：已登记·待排期（本批只登记、不施工）。**内容**：`stop()` 之后在途的 `executeFrontendTool` 仍会返回并**写段 + 回灌残留**；`send` 的 `finally` 只置 `done`、**不清段**。**落点**：`frontend/src/stores/ai.ts:1798-1818`（在途执行器返回与写段/回灌），`stores/ai.ts:923-927`（`send` finally 只置 `done` 不清段）。**来源**：DS Pro 红队 §二-4 附带残留。

### 本批新增残留申报（写入 R-141 行尾注记）

- `unknown` 经 **R-141①** 与 **R-141②** 两路**仍可被翻成 `succeeded` / 产生重复卡** —— 属 ③a 已知代价，须在施工报告与 R-141 行尾**双向留痕**（不得静默）。

---

## §6 M2 收尾待办（**提交官办理；本批不动 M2 正文**）

| # | 待办 | 口径 |
|---|---|---|
| 1 | **R-141 / R-142 落行** | 紧接 `R-140`（`docs/M2-排期计划.md:329`）之后追加，**编号自 `R-141` 起**；行尾注记方式，本批不施工 |
| 2 | **R-97（`docs/M2-排期计划.md:289`）行尾注记订正** | 原文「两个调用方都不取结局」与实态 **5 处**不符（`ExportWizardView.vue:14` / `:599` / `:1128` / `:1146` / `:1225`）⇒ **行尾注记方式订正**，不删原文 |
| 3 | **逐项销记（C8：不得打包）** | ① `R-139` ①②③④ 逐子项点名（`m2` 死码 → ③b；`m3` 申报粒度 → ③a 前置登记面；`O1` 挂起卡终态不可达 → ③b；`O2`~`O5` 逐条）；② `R-140` ① 12 条观察逐条点名（1/2/4/7/9/11/12 的去向与现状是否变化）；③ `R-140` ② `o1`~`o4` 逐条（含 `o3` CDP 配方口径 = **`VITE_DEV_PORT=5202`**）；④ `R-140` ③ §5-5 口径角（本批已由 D5(甲) 定案：**本地胜**，销记方式 = 记「已定案 + 已加 `unknown`」） |
| 4 | **本批新增残留登记** | R-142（见 §5）；`unknown` 经 re-attach/loadHistory 的已知代价（见 §5 末） |

---

## §7 签字

| 项 | 值 |
|---|---|
| 终裁人 | **K3（代签，署名口径 DC-19）** |
| 签字日期 | **2026-10-11** |
| 授权来源 | K3 指挥官 2026-10-11 三方会审汇总定稿指令 |
| 会审输入 | 《批3③a-施工卡草案.md》(380 行) + 《会审-批3③a-GLM.md》 + 《会审-批3③a-DSPro.md》 |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（显式钉版；本棒零施工、零 git 写、未读 `.env`、未改仓库文件） |
| 配套文档 | 《批3③a-施工卡.md》（定稿；文首标注「本稿替代草案，草案仅存档」） |

---

## 附 · 落稿棒精读注记（**3 条**，只作精度订正，**不改裁**；冲突项见本卡末「上报」）

> 以下为落稿棒在 `3edaeb9` 上逐行实读时发现的**计数/锚点精度**差异。**K3 终裁的结论与范围一律不动**；差异如实上报供 K3 决定是否落行尾注记。

1. **D1 的「26 处 `ok:` 生产点」是 grep 口径，工厂实际目标面 = 21 处**：`grep -c "ok:" utils/frontend-tools.ts` = **26** ✓，范围 `:119`~`:891` ✓；但其中 **5 处**（`:119` / `:125` / `:132` / `:138` / `:141`）属 `validateArgs` 的 `{ok:true; coerced}` \| `{ok:false; reason}` **判别联合**（参数校验结果，**无 `availability`/`result`**，与 `FrontendToolExecution` 不同型）。**工厂收口的实际目标面 = 21 处 `FrontendToolExecution` 生产点**（`:425` / `:459` / `:704` / `:711` / `:718` / `:734` / `:753` / `:761` / `:768` / `:774` / `:778` / `:815` / `:825` / `:834` / `:842` / `:845` / `:857` / `:864` / `:867` / `:885` / `:891`）。**处置**：目标（单点工厂 + 断言一处）不变；施工棒 S0 按 21 处逐点编号入报告，26 处全量清单同列；`validateArgs` 是否并入工厂**由施工棒按类型安全判定**（若并入须另设只判 `ok` 的纯函数口径）。
2. **D9/C6 的「全仓另 10 处」清单实为 11 条**：清单本身逐条命中（`api/sse.ts:314`、`api/tasks.ts:110`、`SpreadGrid.vue:206`、`utils/excel.ts:432`、`stores/ai.ts:72` / `:140` / `:294` / `:1321` / `:1369` / `:1382` / `:1402`），但条目数为 **11**，与会审文本的「10 处」不符（**计数笔误，清单正确**）。两侧向导内 7 处（`Export:413` / `:589` / `:723`、`Import:477` / `:763` / `:875` / `:915`）与 `.catch(() => [] / null)` 5 处（`Export:401`、`Import:487`、`DataBrowserView.vue:332-333`、`workspace.ts:354`）**逐条命中**。
3. **DS Pro 报告的两处内锚漂移（其自身 §五-4 已申报，落稿棒复验确认）**：① `deriveProjections` 的定义与 `pendingCall` 门（`requiresAction`）在 **`frontend/src/utils/parts.ts:157-189`（门在 `:181`）**，非 `ai.ts:181-183`；② `dispatchUiEvent` 的**调用**在 `stores/workspace.ts:374`、三支（`goto_step` / `select_defs` / `set_conditions`）在 **`:392-401` / `:402-415` / `:416-428`**（`:368-374` 是 `runUiEvent` 的函数头/文档区）。**施工卡一律采用落稿棒复核后的锚点**。
