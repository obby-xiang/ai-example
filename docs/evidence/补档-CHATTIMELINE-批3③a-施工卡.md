> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③a 施工卡（定稿），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 「对话时序包 · 批 3 ③a」施工卡（**定稿**）

> ## ⚠️ 本稿替代草案，草案仅存档
>
> 本文件 = **批 3 ③a 施工卡定稿**，**完整替代** `<SCRATCH>/chat-timeline-pkg/批3③a-施工卡草案.md`（380 行）。
> 草案**仅存档**，不得作为施工依据；本稿之外的任何行号/清单/口径**一律无效**（尤其：草案的「21 处失败形字符串」「`goStep` 两个调用方」「九态全显式」「`types/tools.ts:118` 同时给模型和人看」四处已订正）。
> 定稿依据 = 《决策卡-批3③a.md》（K3 终裁 D1~D12 + X1/X2 + 归属订正 + R-a + 登记清单）。

| 项 | 值 |
|---|---|
| 文档定位 | **施工卡（定稿）**——施工棒的唯一作业依据 |
| 执行者 | **Kimi K3 指挥官**（**K3 代签**，署名口径 DC-19） |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（Kimi Code 子智能体，**模型显式钉版**；本棒只落稿：零施工、零 git 写、未改仓库文件、未读 `.env`） |
| 施工执行 | **待派施工棒**（机械活下沉条款见 §7.3：下沉子智能体**须显式钉 `model=DeepSeek/deepseek-flash`**，**禁靠会话默认**，报告自证实际模型） |
| 授权来源 | **K3 指挥官 2026-10-11 三方会审汇总定稿指令**（会审输入：草案 380 行 + GLM-5.3 评议 + DS V4 Pro 红队评议） |
| 仓库 | **`<MAIN_REPO>`**（⚠️**严禁** `<OTHER_REPO>（另一条线的仓，严禁触碰）`——无共同祖先的另一条线，任何误用即整批作废） |
| 实读基线 | **HEAD `3edaeb9`**（`3edaeb900dcc446b918913738a6f527c780e7b81`），工作树干净【落稿棒实测】 |
| 工具链（钉死） | **JDK 21**（实测 `21.0.12`）；Maven 走**包装脚本 `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`**；**node v24.21.0** / **yarn 1.22**（实测 `1.22.22`） |
| 前端 dev 端口 | **5202**（仓内 vite 默认 `5200` + `strictPort:true`，见 `frontend/vite.config.ts:11/16/26` ⇒ 起 dev **必须**显式 `VITE_DEV_PORT=5202`；冒烟脚本默认 `SMOKE_APP=http://localhost:5202/`） |
| 纪律（红线） | **零 git 写**（提交官统一提交）；**禁读任何 `.env`**；未获授权的仓库外文件一律不改；本批**不触后端源码**（故**不要求** `mvn`，若届时后端已被他批改动则按「重建后再跑」处理） |
| 落盘 | 本卡 `<SCRATCH>/chat-timeline-pkg/批3③a-施工卡.md`；施工报告 `<SCRATCH>/chat-timeline-pkg/施工报告-批3③a.md` |
| 测试口径（**照批 2**） | **TDD：先写失败测试留证（RED）再施工**；每个测试锁（T1~T9）须有「施工前该用例红 / 施工后绿」的**双向留证**，写入施工报告 |

---

## §0 ③a 是什么（一句话口径 + 可观察验收）

> **③a = 「工具结局不得谎报」的结构化收口**：把前端工具/页面动作的真实结局从**字符串/布尔二元**升级为**结构化三态**（`ok` / `failed` / `unknown`），让「预算到期」「页面在等待上界内没等到就绪」「页面 handler 如实报失败」三类既有事实，在**工具卡面**与**回灌给模型的文本**上如实呈现；并一次性钉全 `unknown` 的**四个连带读点**（`localTerminal` / `isFrameDecidedPendingStatus` / `pendingStatusOf` / `applyPendingTerminal` + `expireResidueToolParts`）。落在**存量投影世界**（`parts` 已是唯一真值源），不依赖 ①/②，**不触后端**。

**验收口径（可观察，逐条对应 CDP/单测证据）**

| # | 验收点 | 终裁口径 |
|---|---|---|
| 1 | 同一底层事件（导出结果编辑器引擎加载失败）经 **page-handler 路径**（`open_export_file_editor` 默认执行器）与**注册工具路径**、**动作表/契约层路径**触发时**卡面一致**且为「执行失败」 | A3/A4/A5 + **D3(iii) 兼容口径**（耦合红线） |
| 2 | 前端工具**预算到期**的卡面显示「**结局未知（未确认成功/失败）**」，与回灌文本不再自相矛盾 | A2 + A8 + **D1(A)** |
| 3 | 页面动作**在等待上界内没等到目标页就绪**时不再按成功回灌，且 `availability:'degraded'`（触发降级 notice） | A6 + **D4(a)** |
| 4 | 上述三态**在回执帧到达后不被改写**（本地终态 first-wins 覆盖 `unknown`） | A9 + **D5(甲)** |
| 5 | **`unknown` 段永不复活为挂起卡**（`unknown ⇒ requiresAction === false` 不变式有断言与测试锁） | **X1 + T2** |

---

## §1 A 条目清单（14 条；行号 = `3edaeb9` 落稿棒实读复核值）

### A1 契约层无「结局」载体（**P1 硬前置**）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/types/tools.ts:115-120`（`interface FrontendToolExecution { ok: boolean; availability: ToolAvailability; result: string }`；`result` 注释在 `:118`） |
| 问题 | `ok` 二元；`availability` 是**可用性**维度（available/degraded/unavailable），**不是结局维度** ⇒「结局未知」在数据层**无载体** |
| 修法 | 增 `outcome: 'ok' \| 'failed' \| 'unknown'`（可带 `reason?: string`）；`ok`/`availability` 保留兼容；注释写明「失败」与「结局未知」判据差别 |
| 施工取值 | **D1(A)**：`ok === (outcome === 'ok')`；`ok` 标 `@deprecated`、**接受零读者** |

### A2 预算到期缺结构化通道

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:411-426`（`timeoutOutcome`；`return { ok:false, availability:'degraded', result }` @ **`:425`**；文案「本次调用结局未知 —— 既未确认成功，也未确认失败」@ **`:421`（下载类支）/ `:423`（其余支）**） |
| 问题 | **文本说"未知"、卡面说"执行失败"** ⇒ 自相矛盾（N3） |
| 修法 | 返回 `outcome:'unknown'`；`ok:false` 不变；文案**一字不动**（已如实） |
| ★附注（DS Pro §三-A7） | `executeFrontendTool`（`:315-322`）的 **timeout 支新建对象**（`:320`）⇒ 新字段**必然缺失**，故 A2 与 A7 必须同改才闭环 |

### A3 `runPageHandler` 硬编码成功（**R-84 主面**）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:875-896`（成功支 **`:884-888`**：`ok:true` @ **`:885`**、`result: typeof outcome === 'string' ? outcome : \`${label}已执行\`` @ **`:887`**；`catch` @ `:889-894` 落 `ok:false`+`degraded`） |
| 问题 | handler 返回的**任何字符串**都被包成 `ok:true` ⇒ 卡面落 `succeeded`，卡内文却写「未设置/未启动/表格未就绪」 |
| 修法 | ①handler 返回值支持结构化 `{ ok:false, reason }`（**保留字符串兼容解读**）；②失败结局**不得**再包成 `ok:true, availability:'available'`；③仓内失败形字符串处置 = **D3(iii)**（本批只改导出侧，导入侧登记待批） |
| 注册点（实读 11 个） | 导出 5：`ExportWizardView.vue:1136` / `:1150` / `:1167` / `:1198` / `:1207`；导入 6：`ImportWizardView.vue:1006` / `:1027` / `:1041` / `:1050` / `:1059` / `:1068` |

### A4 `runRegisteredTool` 成功支硬编码 + 执行器返回类型

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:807-850`（成功支 `:842` `return { ok:true, availability:'available', result }`；`catch` **`:843-849`**；执行器签名 @ **`:69`** `export type FrontendToolExecutor = (call: FrontendToolCall) => Promise<string> \| string`） |
| 问题 | 执行器**只能返回字符串** ⇒ 想如实报失败只能 `throw`（`catch` 落 `ok:false`+`degraded`，这条是对的）⇒ 本处是**契约面收口（latent）** |
| 修法 | 返回类型放宽为 `string \| FrontendToolExecution`；`runRegisteredTool` 按结构化解读；`throw` 路径保持 |
| ★诚实定性（不得当"已证缺陷"） | 仓内**唯一**显式执行器 = `ExportWizardView.vue:1256` `download_export_file`，**不返回失败声明**；真正可复现的谎报面是 **A5** |

### A5 `defaultExecutor` 把页面 handler 的**如实失败文案**包成成功

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:981-994`（`function defaultExecutor(name)` @ `:982`；`:989` `const outcome = await handler(...)`；`:990` `return typeof outcome === 'string' ? outcome : \`已请求打开 …\``） |
| 问题 | 注册工具 `open_export_file_editor`（`types/tools.ts:84-95`）**无显式执行器** ⇒ `defaultExecutor` 直调页面 handler、把字符串原样返回 ⇒ `:842` 包成 `ok:true` ⇒ `ExportWizardView.vue:1236`（「表格未就绪…」**如实失败文案**）从工具名路径进来时卡面仍显示**已成功** |
| 定性 | **同族谎报面**；总卡《刷新版》与体检报告**均未登记** |
| 修法 | `defaultExecutor` 不再自吞 handler 结局：**结构化透传**（复用 A3 的解读逻辑） |
| ★D2 点名 | `:1236` 这条失败形字符串 = **确定性失败 ⇒ 落 `failed`，不得误入 `unknown`** |

### A6 工作区动作表：等待上界耗尽却按成功回灌

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:739-782`；`PAGE_READY_TIMEOUT_MS = 3_000` @ **`:517`**；`waitForPageReady` @ **`:529-544`** |
| 逐段定性 | `:750-756`（`ok:true` @ `:753`，"页面已就绪 + 任务已绑定"）= **合法成功，不动**；`:758-766`（`ok:false` + `degraded` @ `:761`）= **已是正解，不动**；**`:767-772`（`ok:true` @ `:768`）= 谎报面，本批必改**；`:774`（`ok:true`，无导航目标时回灌 `outcome.message`）= **不改（D4(b)）**；`:776-781`（契约层拒绝 ⇒ `ok:false` + `degraded`）= **已是正解，且是 D4(a) 的先例** |
| 修法（**D4 终裁**） | ①`:767-772` ⇒ `outcome:'unknown'` + `ok:false` + **`availability` 由 `'available'` 降 `'degraded'`**（触发 `stores/ai.ts:1815` 降级 notice；文案不动、保留重试指引）；②`:774` 不改 |

### A7 执行入口未透传结局维度

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:315-322`（`export async function executeFrontendTool`；**:319-321 两分支**：`kind==='timeout'` ⇒ `timeoutOutcome`，否则 `outcome.execution`） |
| 问题 | 是**回灌的唯一前置**（唯一调用方 `stores/ai.ts:1798`），新维度必须在此完整透传，否则结局在中途被结构丢弃 |
| 修法 | 两条支路都携带 `outcome`；`runWithBudget`（`:351-`）不需改 |
| ★订正 | 草案「三分支」**表述有误**，实为**两分支 + `BudgetOutcome` 判别联合**（`kind:'settled' \| 'timeout'`）；`settled` 支按引用透传（新字段不丢），**`timeout` 支新建对象**（A2 必须同改，见 A2 附注） |

### A8 落卡仍是二元（消费端）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/stores/ai.ts:1782-1819`（`:1804` `const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'`；`availability` 读点 `:1815`；回灌 POST `:1818`） |
| 问题 | 无 `unknown` 通道；`'unknown'` 全仓仅 2 处字面量（`types/ai.ts:165` 定义、`AiPanel.vue:554` case + `:576` 标签键）⇒ **零生产者** |
| 修法 | 三元落卡：`outcome==='unknown'` ⇒ `status:'unknown'`；`'failed'` ⇒ `'failed'`；`'ok'` ⇒ `'succeeded'`；**`:1804` 改读 `outcome`**（`ok` 转 `@deprecated`，D1） |
| 归属订正 | 草案「批 2 之后 `'unknown'` 已进 `ToolRunStatus`」**归属错误** ⇒ **批 1（`1efab09`）**落地（见 §2 D 表末「归属订正」） |

### A9 `localTerminal` 缺 `unknown` ⇒ 结局未知会被回执帧**翻成成功**

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/parts.ts:351-375`；`localTerminal` 集合 @ **`:358-360`**（六值 `failed/rejected/blocked/succeeded/expired/cancelled`，**无 `unknown`**）；三级优先 @ `:362-364`（本地终态 → `fromFrame` → `.ok` 兜底）；`FRONTEND_RESULT → 'succeeded'` 映射 @ `parts.ts:210-228`（`frontendResultStatus`） |
| 问题（chain 实读） | A8 落 `unknown` → `ai.ts:1818` 发回灌 POST → 后端 `ConfirmGate#submitFrontendResult` 标 `FRONTEND_RESULT` 回帧 → 前端收帧走 `:358-366`：`'unknown'` **不在** `localTerminal` ⇒ `fromFrame('succeeded')` **覆盖** `unknown` ⇒ 卡面从「结局未知」翻成「已成功」，**比现状更差** |
| 修法（**D5(甲)**） | 把 `'unknown'` 纳入 `localTerminal`（first-wins）；注释（`:355-356`）补「**含 unknown**」 |
| ★关键证据 | `submitFrontendToolResult` 的 POST payload **不带 `status`/`ok`**（`frontend/src/types/ai.ts:66-78`：仅 `runId`/`toolCallId`/`result`/`source`/`cancelled`）⇒ **`localTerminal` 是 `unknown` 存活的唯一机制** ⇒ **T1 为生死线用例** |
| ★充分性（不得宣称"一行收口"） | 本修只覆盖「同消息帧回执」一路；re-attach 新宿主与 loadHistory 整替两路**仍失守** ⇒ 见 **E17（移交 ③b / R-141）** |

### A10 `isFrameDecidedPendingStatus` 缺 `unknown`（**及 `failed`**）⇒ 残留守卫放行

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/stores/ai.ts:374-377`（`isFrameDecidedPendingStatus`：`succeeded/rejected/blocked/expired/cancelled`，**无 `unknown`、无 `failed`**）；调用方 `hasDecidedPendingResidue` @ **`:1195-1204`**（文档区 `:1188-1194`）；两条帧路守卫 @ `:1573` / `:1620` |
| 问题 | 该函数是 `hasDecidedPendingResidue` 的判据（防"取消竞态重挂"开出第二张死卡）；③a 引入 `unknown` 后，`unknown` 段不被视为已决 ⇒ 重投帧可再次挂起；`failed` 缺项 = 同族**第二处遗漏**（DS Pro C2） |
| 修法（**D6(A) 扩充**） | 集合纳入 **`unknown` 和 `failed`**（两者均为终态） |
| ★保护面申报（GLM 收窄，**必须如实**） | 两条帧路守卫均带 `GENERATIVE_FORM_TOOL` 前缀 ⇒ 实际保护面 = ①**快照恢复路**（`ai.ts:713` / `:725`，全条目）+ ②**GF 帧路**；**通用 `FRONTEND` 工具重投帧现状不受拦截**（`:1590` 直跑）= 与 `unknown` 无关的既有缺口 ⇒ **归 ③b**（R-141③）。**报告不得销记「重挂已闭合」** |

### A11 W-06：导出向导 `goStep` 发后不管

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/views/ExportWizardView.vue:545-554`（`function goStep(next: number): void` @ `:545`；`void persistStepData()` @ `:551`；`void syncStepToBackend()` @ `:552`；`void ensureStepLoaded(next)` @ `:553`） |
| 问题 | 勾选集/查询条件未落库却回灌「已推进到步骤「…」」；紧随的 `start_export` 读服务端 `conditionJson` ⇒「AI 说设了条件、导出却是全量」 |
| 调用方（**实读 5 处，草案「2 处」作废**） | `ExportWizardView.vue:14`（模板 `@prev="goStep(stepIndex - 1)"`）/ `:599`（`nextStep`）/ **`:1128`**（`preflightDownload`，`goStep(2)` 后 `:1131` **无条件** `return {ok:true, defCode}`）/ **`:1146`**（`export.nextStep` handler，**W-06 撒谎点本体**，`:1147` 回灌「已推进到…」）/ **`:1225`**（open-editor handler） |
| ★残余入口 | `:1128` / `:1225` 是草案漏掉的 W-06 残余入口，**必须取结局**（否则 W-06 在这两个入口继续存在） |
| 修法（**D7 终裁**） | `goStep(): Promise<结构化结局>`，**永不 reject**（失败也 resolve）；`persistStepData` 失败 ⇒ **阻断推进**；`syncStepToBackend` 失败 ⇒ **推进界面 + 警告**（不阻断）；`ensureStepLoaded` **不纳入结局并注释**；`persistStepData`（`:603-626`，`:621-622` `ElMessage.error` 后返回 `void`）**一并改返回值** |

### A12 W-07：导出向导 `syncStepToBackend` 整段 `catch {}` 全吞

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/views/ExportWizardView.vue:579-592`（`async function syncStepToBackend(): Promise<void>` @ `:579`；`try { task.value = await tasksApi.goToStep(...) }` @ `:587-588`；`catch {` @ `:589`，**catch 体零语句仅注释 `:590`**，`}` @ `:591`） |
| 问题 | 服务端 `currentStep`（刷新恢复 + AI 上下文渐进披露的**唯一权威**）未同步而界面已变，**无人知** |
| 修法（**D8(A)**） | catch 内改为**结构化失败**（不静默、也不阻塞界面）：返回 `{ ok:false, reason }` + 页面可见 `ElMessage`（同文件 `:622` 已有范式）+ 供调用方读取；**任何非 2xx（含未来 R-137 守卫 4xx）都落结构化失败**；回灌文案形状随 D7（含警告措辞） |

### A13 `toolTagType` 的 `default` 兜底（P2 收尾，低价值）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/components/AiPanel/AiPanel.vue:537-561`（`toolTagType`；**显式 case 覆盖 8 态**；`default: 'primary'` @ **`:558-559`**）；对照 `toolStatusLabel` @ `:563-579`（**已是 `Record<ToolRunStatus, string>` 穷尽闸**） |
| ★事实订正 | 草案「九态**已全部显式覆盖**」**不成立**——`pending` 由 `default` 兜（**非纯死码**；`default` 当前兜的正是 `pending`） |
| 修法（**D10(A)**） | 去 `default` + **`pending` 补显式 `case`**；形式对齐 `:563-579` 的 `Record<ToolRunStatus, …>` 穷尽闸 |

### A14 回归锁（本批**不重复施工**）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/types/ai.ts:165`（`ToolRunStatus` 九态）；`AiPanel.vue:549-557` / `:563-579`；`utils/parts.ts:105-124`（`pendingStatusOf`） |
| 事实 | 枚举收敛**已由批 1 实质完成**（九态 + `pendingCall` 由段派生） |
| 本批动作 | **只做**「确认无遗漏 + 新增回归断言」，**不得**再做枚举扩张或重命名 |
| ★归属订正 | `unknown`/`cancelled` = **批 1（`1efab09`）**；「批 2 完成 7 态收敛」**叙述作废**（历史 6 → 7 → 9；批 2 `6bea4ca` 未改枚举行只改注释） |

**③a 条目数 = 14 条**；其中**实读新增、总卡与体检均未登记**者 = **A5 / A9 / A10**（另 **X1（`pendingStatusOf`）** 为会审新增第三处连带读点，已并入 §2 D5）。

---

## §2 D1~D12 终裁落表（**施工取值**）

| # | 终裁（施工取值） | 关键红线/坑 | 否决方案 |
|---|---|---|---|
| **D1** | `ok` 语义收窄 = **(A)**：`ok === (outcome === 'ok')`，`unknown ⇒ ok:false`。**工厂函数收口**：`utils/frontend-tools.ts` 内 `ok:` 生产点全改走单一工厂（形如 `makeExecution(outcome, …)`），一致性断言**单点化**；`:864` 路径（`ok: outcome.handled`）**补进清单**并同步推导 outcome（`handled:true` 且副作用已 `await` ⇒ `'ok'`，`fail` ⇒ `'failed'`）；A8 后 `ai.ts:1804` 改读 `outcome`，`ok` 标 `@deprecated`、接受零读者 | 逐点手写断言必漏 ⇒ 必须工厂；不留长期双真源 | (B) `unknown⇒ok:true`（未迁移读点今天即谎报）；(C) 只加 `outcome`（留一致性债） |
| **D2** | `unknown` 判据 =「**已发起/已受理但时限内无法确认真实结局**」。**点名**：`open_export_file_editor` handler 的失败形字符串（`ExportWizardView.vue:1236`，`ensureGridLoaded` 返回 `{ok:false}`）= **确定性失败 ⇒ `failed`**；执行器 `throw` 维持 `failed` | 不得让 `{ok:false}`/`throw` 误入 `unknown`（否则退化万能兜底） | 把 `throw`/`{ok:false}` 归 `unknown` |
| **D3** | 维持 **(iii) + 修订**：本批**不改**导入侧 **11 处**失败形字符串（登记待批）；**删除「两在飞批次撞车」表述**，理由改写为**范围控制**（清单刚经两方订正、与 `unknown` 主链无关、不宜搭车；仓外已确认无在飞会话写该文件、步骤条包①已闭环、轮询治理③未开工）；登记清单按 **X2 订正重列**（见 §3 E19）；**耦合红线**：D3(iii) 兼容口径须与 **A4 + A5 同一提交** | `{ok:false}` 若不同时改 A4/A5，会被 `defaultExecutor:990` 折成**成功兜底** | (i) 逐注册点结构化（撞总卡 §5.1 红线 + 触 Import 共用文件）；(ii) 只加通道（无生产者，R-84 名不副实） |
| **D4** | (a) `:767-772` ⇒ **`outcome:'unknown'` + `ok:false` + `availability` 降 `'degraded'`**（触发 `ai.ts:1815` 降级 notice；与 `timeoutOutcome:425` 及同文件 `:776-781` 先例一致；文案不动）；(b) `:774` **不改**，论据订正为「`goto_step`（`workspace.ts:392-401`）**无只读守卫**（`select_defs`/`set_conditions` 有，`:407-409`/`:422-424`）⇒ 只读态下 `goto_step` 仍真写 ⇒ **不推翻 (b)**」，并落成 **R-137 守卫耦合申报条目** | 留 `available` 则 `:1815` notice 不触发；把 `:758-766` 与 `:767-772` 合并 = 把"可能只是慢"标成确定失败（模型放弃重试） | (a) 留 `available`；(b) 整支改 `unknown` |
| **D5** | **维持 (甲)「本地胜」** + **充分性分层处置**（采纳 DS Pro §二-3，「一行收口」表述作废）。**③a 内修四处**：①`localTerminal`（`parts.ts:358-364`）补 `unknown`；②`ai.ts:1704-1705` `applyPendingTerminal` 覆盖域**显式排除 `unknown`/`failed`**；③`ai.ts:246` `expireResidueToolParts` **不把 `unknown` 翻 `expired`**（与 `:233` 对齐）；④`pendingStatusOf`（**X1**）。**两处结构性缺口移交 ③b 本批不修**（见 E17）；`parts.ts:355-356` 注释补「含 unknown」 | `localTerminal` 是 `unknown` 存活**唯一机制**（payload 无 status，`types/ai.ts:66-78`）⇒ T1 生死线；须申报已知代价，**不得静默留白** | (乙) 全改帧胜（把用户取消抹成成功；须破既有断言）；「一行即收口」表述 |
| **D6** | **(A) 扩充：纳入 `unknown` 和 `failed`**（`ai.ts:374-377`）；**同时如实申报保护面**：守卫带 GF 前缀（`:1573`/`:1620`）⇒ 实际保护 = 快照恢复路（`:713`/`:725`）+ GF 帧路；通用工具重投帧**不受拦**（`:1590`）= 既有缺口 ⇒ 归 ③b；**不得销记「重挂已闭合」** | 错误销记 = 事实失真 | (B) 不纳入（重投帧复活挂起卡，与 ③b「一卡」目标冲突） |
| **D7** | `goStep(): Promise<结构化结局>`，**永不 reject**；合并语义 = **`persistStepData` 失败 ⇒ 阻断推进**；**`syncStepToBackend` 失败 ⇒ 推进界面 + 警告**（`ElMessage` + 回灌文案附「**步骤已推进但服务端步骤同步失败，下次切步会重试**」），**不阻断**；`ensureStepLoaded` 不纳入并注释；**调用方 5 处全改**（`:14`/`:599`/`:1128`/`:1146`/`:1225`，后两处 = 草案漏掉的 W-06 残余入口）；`persistStepData` 一并改返回值；台账 R-97（`M2:289`）「两个调用方」记入**收尾订正清单**（本批不动 M2） | 模板 `:14` 绑定 ⇒ 不可 reject；只字面对齐导入蓝本会复制 W-07 静默（蓝本 `:727` 自身吞错后照返 `true`） | GLM 的 `persist ∧ sync` 双阻塞（网络抖动 ⇒ 向导卡死，违背 `:590` 可用性设计） |
| **D8** | **(A)**：catch 内结构化失败 + **页面可见 `ElMessage`**（`:622` 范式）+ 供调用方读的返回值；**任何非 2xx（含未来 R-137 守卫 4xx）都落结构化失败**；文案形状随 D7 | 只覆盖「网络错误」⇒ R-137 的确定性 4xx 会被重新静默 | (B) `throw`（网络抖动升级成整轮失败）；(C) 仅 `console.warn`（仍无人知） |
| **D9** | **升级 (B) → (A)：本批修导入侧空 `catch`**。范围限定：只修 `ImportWizardView.vue:761-765`（D8(A) 同法）；导入侧 `goStep:727` 按 D7 同款语义**最小接入**（失败**不阻断** + 附警告）；**导入侧 11 处失败形字符串登记项不动**；**C6 全量登记**（见 E18）。升级依据：撞车前提已失效（仓外确认无在飞会话）+ 一次施工一次回归 + 与 D7 同源闭合 W-07 双生面 | 若仓外实况变化（有在飞会话写该文件）⇒ **立即撤回升级、降级为 (B)（撞车优先）** | (B) 维持登记（同一缺陷在导入侧多留一批）；搭车导入 11 处字符串（超出范围） |
| **D10** | **做 (A)** + 事实订正：`AiPanel.vue:537-561` = **8 态显式 + `pending` 走 `default`（`:558-559`）**；去 `default` 后 **`pending` 补显式 case**；对齐 `:563-579` 的 `Record` 穷尽闸 | 事实订正必须写进报告（草案"九态全显式"不成立） | (B) 不做（未来新增态被 `default` 静默误标） |
| **D11** | **(A) 不加机器前缀**（如 `[unknown]`）。**论据订正**：草案引 `types/tools.ts:118`「同时给模型和人看」**不实**（原文仅「回灌给模型的说明文本」；`:184` 属另一接口 `WorkspaceActionRecord.summary`）⇒ 改为「**payload 的结局信息只有单字段 `result`（文本），结构化结局走 `outcome` 字段而非文本编码**」（`types/ai.ts:66-78`） | 前缀污染人可见文本 + 属行为契约变更（提示词影响未评估） | (B) 加固定前缀 |
| **D12** | **(A) CDP 冒烟强制覆盖 A2/A6（至少 A2）**；**+GLM 补充：A9 验证法 = 等回执帧到达后一拍再截图**（证 `unknown` 不被翻成成功） | 仅单测+人工 ⇒ 无截图即无证（CI 不驱动 DOM） | (B) 仅单测 + 人工复现 |

### 归属订正（**施工报告必写**）

`unknown`/`cancelled` 枚举 = **批 1（`1efab09`）**落地；历史 = 6 态（`b2b2ec5`）→ 7 态（`ad3ffb8`，+`blocked`）→ **9 态（`1efab09`，+`unknown`+`cancelled`）**；「**批 2 完成 7 态收敛**」叙述**作废**；批 2（`6bea4ca`）**未改枚举行只改注释**。

---

## §3 排除项与登记去向表（E 表；E1~E16 照终裁口径 + E17~E20 扩充）

| # | 排除项 | 现态锚点 | 去向 |
|---|---|---|---|
| E1 | `sanitizeMirrorResidues` 的「进行中轮不触碰」守卫（`message.done !== true`）死码 | `frontend/src/stores/ai.ts:225-227` | **③b** |
| E2 | 挂起卡终态 tag 全集不可达（`pendingCall.status` 恒 `pending`）⇒ `AiPanel` 两处 `v-else` 兜底 | `frontend/src/components/AiPanel/AiPanel.vue:201-289`（死码 `:206`/`:250-253`/`:265-277`/`:281-285`） | **③b** |
| E3 | `migrateMirror` 的 `requiresAction` 判据申报粒度（`m3`） | `frontend/src/stores/ai.ts:197-203` | **③a 前置登记面**：施工报告**逐条点名申报**（不在本批改） |
| E4 | `appliedSeqs` 消息级去重 × `runSeq` runId 级记账两维错位 | `frontend/src/utils/parts.ts`（`appliedSeqs` 注释区）/ `frontend/src/stores/ai.ts:1499`（声明 `:429`） | 登记（实测无路径可达） |
| E5 | `v-for` key 跨类型理论碰撞（`toolCallId` 恰为 `text-0`） | `frontend/src/components/AiPanel/AiPanel.vue:166-168` | 登记（服务端 UUID 不可达） |
| E6 | `isTyping` 在「仅 reasoning 流式、无 text」时三点与思考链同现 | `frontend/src/components/AiPanel/AiPanel.vue:503-505` | 登记（后端当前不产 reasoning 帧） |
| E7 | 终态挂起卡不再保留（GF 取消回灌失败文案失去渲染位） | 施工报告-批2 §5 观察 1 | **③b** |
| E8 | `suspended` 帧单独到达不产生挂起语义 | `frontend/src/utils/parts.ts:376-`（`case 'suspended'`） | **③b** |
| E9 | 存量 v2 镜像段可能带 `streaming:true`（假态） | 镜像读取侧 / `sanitizeMirrorResidues` | **③b** |
| E10 | 复制按钮位置变化需人工确认视觉可接受 | `frontend/src/components/AiPanel/AiPanel.vue:291-315`（testid `:298`/`:308`） | **CDP 人工面**确认（非代码改动） |
| E11 | 重复终帧被 seq 幂等跳过时不复位 `loading` | `frontend/src/stores/ai.ts` 轮终态分支 | 登记（现症未现） |
| E12 | `<details>` 展开态不持久化 | `frontend/src/components/AiPanel/AiPanel.vue:174` | **批 4（④ 折叠规则）** |
| E13 | 后端 `AiTools` 8 处 `catch (Exception e) { return "<中文失败文案>" }`（W-05） | `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`（R-137 落点 `:280-342`） | **R-137 批**（③a **不含后端**） |
| E14 | 页面 handler 的如实失败面（`ensureGridLoaded` 结构化 + `:1215`/`:1218`/`:1259` `throw`） | **订正**：`ensureGridLoaded` **定义**在 `ExportWizardView.vue:776-804`；消费 handler 在 `:1207-1241`（`:1236` 失败形字符串） | **已修面，不得重复施工**（A3/A5 只改**包装层**，不改 handler 判据与文案） |
| E15 | `PendingToolCall['status']` 自成一格 **8 态**联合（含 `approved`，**无 `unknown`/`failed`**） | `frontend/src/types/ai.ts:324` | 登记（`unknown` 段 `requiresAction === false` ⇒ 永不进 `pendingCall`，**无类型冲突**）；「后端 10 态」= 后端常量表经 `frameStatusToPendingStatus` 折进前端 8 态 |
| E16 | `Download` 类超时文案「过度保守」（文件也许已落盘） | `frontend/src/utils/frontend-tools.ts:400-410` 注释 | 登记不修（方向安全） |
| **E17** | **两处结构性缺口（`unknown` 仍会被翻成 `succeeded` / 产生重复卡）**：① re-attach 新宿主消息（`stores/ai.ts:1008`，`lastSeq` 刷新→全量回放→新宿主建 `succeeded` 新卡、旧 `unknown` 卡并存）；② `loadHistory` 整替（`stores/ai.ts:631-663`，`:644` 工具段状态**硬编码 `'succeeded'`**） | 同左 | **登记移交 ③b，本批不修** ⇒ **R-141①/②**；**施工报告须申报已知代价**（对 `failed`/`cancelled` 系为存量同族，对 `unknown` 为本批新引入面的**已知代价**，不得静默留白） |
| **E18** | **空 `catch` 同型面全量登记（C6）** | ①两向导内**仅注释型 7 处** = `Export:413` / `:589`（=A12，本批修）/ `:723`、`Import:477` / `:763`（=D9，本批修）/ `:875` / `:915`；②**全仓另 11 处**（会审文本标"10 处"为计数笔误，清单 11/11 实读命中）= `api/sse.ts:314`、`api/tasks.ts:110`、`components/SpreadGrid.vue:206`、`utils/excel.ts:432`、`stores/ai.ts:72` / `:140` / `:294` / `:1321` / `:1369` / `:1382` / `:1402`；③`.catch(() => [] / null)` 变体 **5 处** = `ExportWizardView.vue:401`、`ImportWizardView.vue:487`、`views/DataBrowserView.vue:332-333`、`stores/workspace.ts:354` | **defer 边界定清**：仅 ①中 `Export:589`（A12）与 `Import:763`（D9）本批修；其余（含 5 处降级型）**登记不修**，写入 E 表 + 登记清单；`Export:447` 的 `catch { return false }` **不属**仅注释型（有返回值） |
| **E19** | **D3 失败形字符串登记清单（按 X2 订正重列；本批一律不动）** | **导出侧 11 处**（照草案，实读复核命中）：`:1139` / `:1144` / `:1154` / `:1170` / `:1174` / `:1181` / `:1193` / `:1201` / `:1204` / `:1210` / `:1236`；**导入侧 11 处**：`:1009` / `:1014` / `:1020` / `:1030` / `:1045` / `:1053` / `:1056` / `:1062` / `:1065` / `:1071` / **`:1074-1076`（补）**；**另 `ImportWizardView.vue:1047`** = **降级/指引形**（计入登记并**标注**，不与失败形混算）；**「带成功支三元/降级形」共 4 处** = `Export:1204` + `Import:1056` / `:1065` / `:1074-1076`（**`:1181` 非三元**，是单一模板串降级形） | **登记待批**（与 R-137 批 / 步骤条包① 排期对齐；若需动导入侧 handler 失败分支则改判 D3(i) 与其同批） |
| **E20** | **通用 `FRONTEND` 工具重投帧不经守卫** + **两翻译表穷举口径分叉** | ①`stores/ai.ts:1573`/`:1620`（GF 前缀），`:1590`（直跑）；②`utils/parts.ts:210-228`（`frontendResultStatus`，有 `SUCCEEDED`）vs `stores/ai.ts:333-357`（`frameStatusToPendingStatus`，缺 `SUCCEEDED`、缺 `CANCELLED` 依据注释） | **归 ③b** ⇒ **R-141③/④**（②为**防漂移登记**） |

---

## §4 施工步骤 S0~S4（每步可独立编译 + 绿测）

> **总纪律**：`AiPanel.vue` 与 `stores/ai.ts` 是批 2 刚落地的文件，③a 起手**不得与任何同文件批次并行**；文件级互斥另见 §8.3。

| 步 | 内容 | 产出 / 门 |
|---|---|---|
| **S0** | **前置清点（只读）**：①`grep -rn "execution\.\(ok\|availability\)" frontend/src` 列全部消费点；②`grep -rn "ok:" frontend/src/utils/frontend-tools.ts` 列生产点（**26 处字面量 / 21 处 `FrontendToolExecution` 生产点**，见 §9 注记 1）；③`grep -rn "registerPageHandler(" frontend/src` 复核 11 个注册点；④**复取本卡 §1 全部锚点**（防新漂移，尤其 `stores/ai.ts` / `AiPanel.vue` / 两向导）；⑤`grep -rn "registerPageHandler(" frontend/tests` 清点字符串驱动用例（T9 前置）；⑥**RED 前置**：先写出 T1/T2 的失败用例（预期红）并留证 | 消费点清单 + 锚点对比表 + **RED 留证** → 落施工报告 |
| **S1** | **A1 契约层**（`types/tools.ts`）+ **工厂函数**（`makeExecution`，D1） | `yarn typecheck` **立刻暴露**全部需跟改的消费点（把 A2~A8 变成"编译器驱动的清单"）；工厂纯函数单测（T8）先红后绿 |
| **S2** | **A2~A7**（`frontend-tools.ts` 六路由 + 入口透传）**+ D2 点名 + D4(a) + D1 的 `:864` 分支** | 新增 `frontend/tests/frontend-tool-outcome.spec.ts`；扩展 `tools-and-deadline.spec.ts`（T4/T5/T6） |
| **S3** | **A8/A9/A10 + X1 + D5 四处同型面**（`stores/ai.ts:1804`/`:1704-1705`/`:246`/`:374-377` + `utils/parts.ts:358-364`/`:105-124`） | 扩展 `frontend/tests/frontend-tool-result-terminal.spec.ts`（T1/T3）+ 新增 T2（`pendingStatusOf` / `requiresAction` 不变式）；**本步 = 原子提交红线的核心** |
| **S4** | **A11/A12 + D7/D8/D9**（导出向导 `goStep`/`syncStepToBackend`/`persistStepData` + 5 调用方；导入侧空 catch + `goStep:727` 最小接入）**+ A13（`AiPanel` `default` 收敛）+ A14（回归确认）** | T7（W-06 失败注入，**必做**）；`yarn typecheck && yarn test && yarn build` 全绿；随后 **CDP 冒烟（§6）** + **施工报告落盘** |

**验证口径（可直接粘贴执行；仓根 = `<MAIN_REPO>`）**

```bash
# 前端三连（仓内）
cd frontend && yarn typecheck && yarn test && yarn build
```

- **E2E（桩模式；后端不参与本批改动）**
  ```powershell
  $env:E2E_STUB_MODE=1; powershell -File scripts/verify-e2e.ps1
  ```
  沿用 **`Wait-Job` 120s 口径**（`scripts/verify-e2e.ps1:157-161` 显式参数化）；**本批不改后端源码 ⇒ 不要求重建 jar**（若期间他批改了后端，按"重建后再跑"处理）。
- **后端 `mvn`**：③a **不含后端** ⇒ **本批不要求**任何 `mvn` 执行；若需运行期证据（W-07 服务端 `currentStep` 未同步），用**断网 / 后端 5xx 注入**的人工路径，而不是跑后端测试。包装脚本（备用）= `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`（JDK 21）。

---

## §5 测试锁终版 T1~T9（**GLM 2.4 表全列**；每条须 RED→GREEN 双向留证）

| # | 必锁行为 | 对应 | 关键要求 |
|---|---|---|---|
| **T1** | 本地 `unknown` 段 + `frontend_tool_result{status:'FRONTEND_RESULT'}` 帧 ⇒ 段**仍 `unknown`** | A9 / D5 | **生死线用例**（`localTerminal` 是 `unknown` 存活唯一机制） |
| **T2** | `unknown` 段 `requiresAction` 恒 false / `pendingStatusOf` **不产 `pending` 挂起卡** | **X1** | dev 断言 + 用例双落；覆盖"镜像/未来写点产出 `unknown+requiresAction:true`"的负例 |
| **T3** | `hasDecidedPendingResidue` 对 `unknown` 段**拦截**（快照恢复路可测）；`failed` 段同样拦截 | A10 / D6 | 保护面限制（GF 前缀）**不得**在用例注释里写成"全覆盖" |
| **T4** | 动作表 3s 等不到就绪 ⇒ **`outcome:'unknown'` + `ok:false` + `availability:'degraded'`**（`vi.useFakeTimers` + `advanceTimersByTimeAsync(PAGE_READY_TIMEOUT_MS)`） | A6 / D4(a) | 断言 `availability` 取值（C5 钉死点） |
| **T5** | handler 返回结构化 `{ok:false}` ⇒ `failed`；**字符串 ⇒ `ok`（兼容）**；`defaultExecutor` **透传**结构化 | A3/A5/D2 | `:1236` 类失败形字符串（handler 返回）与 `{ok:false}`（结构化）**分列断言** |
| **T6** | 执行器返回 `FrontendToolExecution` 结构化解读（三路） | A4 | `throw` 支维持 `failed` 的负例 |
| **T7** | **`export.nextStep` handler + `tasksApi.goToStep` reject 注入 ⇒ 回灌含失败语义** | A11 / D7 | **必做**：不注入失败则该用例**空转**；`:1128`/`:1225` 两个残余入口各补一条 |
| **T8** | `ok`/`outcome` 一致性（**工厂纯函数单测**） | D1 | 工厂是唯一断言点（含 `:864` 的 `handled` 分支口径） |
| **T9** | **既有字符串驱动用例不红**（回归门） | R-e | 实读 `frontend/tests/` 共 **9 个 spec**（含 `tools-and-deadline.spec.ts`）；**不改既有用例断言语义** |

---

## §6 CDP 冒烟要求（**CI 覆盖不到，本批必须有**）

```bash
# 前端 dev：仓内 vite 默认 5200 且 strictPort:true ⇒ 必须显式 5202
cd frontend && VITE_DEV_PORT=5202 VITE_API_BASE=<后端地址> yarn dev
node scripts/chat-panel-smoke.mjs      # 默认 SMOKE_APP=http://localhost:5202/
```

**强制覆盖（D12(A)）**：

1. **A2 —— 结局未知（底线项，必须有）**：让某前端工具执行器**永久不给结局**（或用极小 `budgetMs`）⇒ 卡面 tag =「**结局未知（未确认成功/失败）**」；**A9 验证法（GLM 补充）**：**等回执帧到达后一拍再截图**，证 `unknown` **不被翻成「已成功」**（同一张截图须能读出"仍为结局未知"）。
2. **A6 —— 3s 等不到页面就绪**：构造目标页未就绪的窗口（路由 chunk 未拉/页面未挂载）⇒ 卡面非成功 + **降级 notice 可见**（`availability:'degraded'` 的可见后果）。
3. **R-84 主面（人工复现，两条路径同卡面）**：导出向导断网/改坏引擎 URL 触发 `ensureGridLoaded` 失败，分别经 **`open_export_file_editor`（工具名路径，A5）** 与 **`export.openEditor` 动作/handler 路径**触发，核对**两张卡面一致且为「执行失败」**。
4. **证据**：截图（含时间戳）+ 触发配方（端口/地址/注入方式）写入施工报告；**注入方式若与配方不同须申报**（D12 推翻条件）。

---

## §7 红线、机械活下沉与台账纪律

### 7.1 原子提交红线（**R-a 终版**）

> **必须同一提交**：**A4 + A5 + A8 + A9 + A10 + `pendingStatusOf`(X1) + `ai.ts:1705`/`ai.ts:246` 两处同型面 + D3(iii) 兼容口径 + 测试 T1/T2/T3**。

理由：`unknown` 一旦有生产者，任一处缺失即产生可见回归（A4/A5 缺 ⇒ `{ok:false}` 被折成成功；A9 缺 ⇒ 回执帧把 `unknown` 翻成**已成功**；A10/X1/`:1705`/`:246` 缺 ⇒ 挂起卡复活或终态被改写）。**其余改动（D7/D8/D9/D10）可在同一批内分笔提交，但不得先于上述红线落地。**

### 7.2 台账纪律（**C8：逐项点名，不得打包**）

- 收尾销记须**逐项点名**：`R-139` ①②③④ 各子项 + `R-140` ①（12 条观察）+ ②（`o1`~`o4`）+ ③（§5-5 口径角）+ **本批新增残留**（R-142、`unknown` 经 re-attach/loadHistory 的已知代价）。
- `R-97`（`docs/M2-排期计划.md:289`）「两个调用方」与实态 **5 处**不符 ⇒ **行尾注记订正**；**本批不动 M2 正文**（由提交官统一办理，见《决策卡》§6）。
- **R 编号**：`R-141` / `R-142`（`R-138`/`R-139`/`R-140` 已占用，`M2:327-329`）。

### 7.3 机械活下沉条款（**明文**）

> 施工棒若把**机械活**（锚点复取、清单生成、扫描类核查、逐点计数核对等）下沉子智能体，**必须**：
> 1. **显式钉 `model=DeepSeek/deepseek-flash`**——**禁靠会话默认**；
> 2. 子任务提示中写明用途与**只读**约束（零 git 写、不改仓库文件、不读 `.env`）；
> 3. **报告中自证实际模型**：若子智能体运行环境无法自省底层模型 ID，须以**父棒显式下发参数**为据自证并**如实写明该局限**（不得声称"已独立自证"，除非运行侧提供日志佐证）；
> 4. 关键锚点与关键结论须由**施工棒本体亲读**复核（不得只凭子报结论落卡）。

### 7.4 其他纪律

- **零 git 写**（提交官统一提交）；**未读任何 `.env`**；仓库外文件（本卡、决策卡、报告）为唯一写入面。
- **禁止**使用 `<OTHER_REPO>（另一条线的仓，严禁触碰）`（另一条线）。
- **不得**擅自扩大范围：D3(i)/(ii)、导入 11 处失败形字符串、后端源码、`stores/workspace.ts` 均**不在本批**。

---

## §8 报告、交付物与风险

### 8.1 报告落盘路径

**施工报告 = `<SCRATCH>/chat-timeline-pkg/施工报告-批3③a.md`**（必须包含：①锚点对比表（本卡 §9 为基线）；②T1~T9 的 RED/GREEN 双向留证；③CDP 冒烟截图与配方；④**归属订正**（§2 末）；⑤**已知代价申报**（E17 + R-142 + `unknown` 经两路翻转）；⑥**保护面如实申报**（D6：不得销记「重挂已闭合」）；⑦D3 耦合（A4/A5 同提交）与 R-137 守卫耦合条目；⑧偏差逐条申报；⑨R-139/R-140 逐项点名销记建议）。

### 8.2 交付物清单

| 类 | 内容 |
|---|---|
| 代码 | `frontend/src/types/tools.ts`、`utils/frontend-tools.ts`、`utils/parts.ts`、`stores/ai.ts`、`components/AiPanel/AiPanel.vue`、`views/ExportWizardView.vue`、`views/ImportWizardView.vue`（**7 文件**；后两者按 D3(iii)/D9 的范围限定） |
| 测试 | 新增 `frontend/tests/frontend-tool-outcome.spec.ts`；扩展 `tools-and-deadline.spec.ts`、`frontend-tool-result-terminal.spec.ts`（+ T2 用例） |
| 证据 | 仓内三连日志、E2E 桩日志、**CDP 冒烟截图**（A2/A6 + R-84 双路径同卡面）、锚点实读表 |
| 报告 | `施工报告-批3③a.md`（§8.1） |

### 8.3 风险与反查点

| # | 风险 | 反查点 |
|---|---|---|
| R-a | 红线内任一项单独落地 ⇒ 可见回归（`unknown`→已成功 / `{ok:false}`→成功 / 挂起卡复活） | 同一提交 + T1/T2/T3 |
| R-b | `outcome` 与 `ok` 不一致（D1 的 (C) 路线） | 工厂单点 + dev 断言 + S1 编译器暴露读点 |
| R-c | A6 的 `unknown` 让模型在"页面只是慢"时放弃重试 | 回灌文案**保留**行动指引（现文案已含，不得删） |
| R-d | A11/A12/D9 改成失败可见后，正常网络抖动让 AI 动作链整轮失败 | D7「`syncStepToBackend` 失败不阻断 + 警告」、D8「不阻塞界面」 |
| R-e | 页面 handler 结构化后既有字符串驱动用例变红 | T9 + S0 ⑥ 清点（该批当前 9 spec） |
| R-f | 台账销记打包、保护面虚高销记 | C8 逐项点名 + D6 申报（**"重挂已闭合"禁写**） |
| R-g | ③a 与 ③b 对 `unknown` 的处置重叠 | 本卡已把两处结构性缺口划 R-141（③b），③b 卡须显式承接 |
| R-h | D9 升级前提（无在飞会话写 `ImportWizardView.vue`）在施工期失效 | 施工棒起手复核仓外实况；失效即**降级为 (B)** 并回退已开工部分 |

---

## §9 锚点实读表（落稿棒在 `3edaeb9` 逐项抽验；施工棒 S0 **须复取**）

| 锚点 | 结论 | 实读结果 |
|---|---|---|
| `utils/parts.ts:105-124` `pendingStatusOf` | **PASS** | 函数定义 `:106`；`default:` `:120-122` 折 `pending/failed/unknown` → `'pending'`；`:121` 注释断言与代码**不互锁** |
| `utils/parts.ts:358-364` `localTerminal` | **PASS** | 集合 `:358-360`（六值无 `unknown`）；`fromFrame` `:361`；三级优先 `:362-364`；注释 `:355-356`（"含乐观 cancelled"） |
| `stores/ai.ts:374-377` `isFrameDecidedPendingStatus` | **PASS** | 五态 `succeeded/rejected/blocked/expired/cancelled`，**无 `unknown`、无 `failed`** |
| `stores/ai.ts:1704-1705` `applyPendingTerminal` | **PASS** | `:1704-1705` 覆盖域 = `pending \|\| cancelled \|\| requiresAction` |
| `stores/ai.ts:246` `expireResidueToolParts` | **PASS** | 函数 `:243-249`；`:245` 条件 `part.requiresAction`；**`:246` 无条件 `status:'expired'`** |
| `stores/ai.ts:1008` re-attach 新宿主 | **PASS** | `:1008` `this.appendAssistantMessage('（正在重挂进行中的轮次…）')`；`lastSeq` 读取 `:1007` |
| `stores/ai.ts:631-663` / `:644` `loadHistory` 整替 | **PASS** | `:631` 起 `history.messages.map`；**`:644` `status: 'succeeded'` 硬编码**（`kind2:'UNKNOWN'`） |
| `stores/ai.ts:1573` / `:1620` / `:1590` GF 前缀守卫 | **PASS** | `:1573` `frame.name === GENERATIVE_FORM_TOOL && hasDecidedPendingResidue(...)`；`:1620` 同款（`waiting.name`）；`:1590` `void this.runFrontendTool(message, frame)` 直跑 |
| `ExportWizardView.vue:587-591` 空 catch | **PASS** | `:587` `try`、`:588` `await tasksApi.goToStep`、`:589` `catch {`、**`:590` 仅注释**、`:591` `}` |
| `ExportWizardView.vue:14` / `:599` / `:1128` / `:1146` / `:1225` 五调用方 | **PASS（5/5）** | `:14` 模板 `@prev="goStep(stepIndex - 1)"`；`:599` `goStep(stepIndex.value + 1)`；`:1128` `goStep(2)` + `:1131` 无条件 `return {ok:true, defCode}`；`:1146` `goStep(index)` + `:1147` 回灌「已推进到…」（**撒谎点本体**）；`:1225` open-editor handler 内 `goStep(2)` |
| `ImportWizardView.vue:761-765` 空 catch | **PASS** | `:761` `try`、`:763` `catch {`、**`:764` 仅注释**、`:765` `}` |
| `ImportWizardView.vue:1074-1076` 三元 | **PASS** | `:1074-1076` `publishJob.value ? '发布作业 #… 已启动；…' : '发布未启动'` |
| `AiPanel.vue:537-561` / `:558-559` `default` | **PASS** | 显式 case **8 态**（running/succeeded/failed/rejected/blocked/expired/cancelled/unknown）；**`:558-559` `default: 'primary'` 兜 `pending`** ⇒「九态全显式」不成立 |

**附：落稿棒另核的支撑锚点（施工棒可直接引用）**：`frontend-tools.ts` — `:69` 执行器签名、`:315-322` `executeFrontendTool`（两分支）、`:411-426` `timeoutOutcome`（`:425` return）、`:517` `PAGE_READY_TIMEOUT_MS=3_000`、`:529-544` `waitForPageReady`、`:739-782` 动作表四段（`:753`/`:761`/`:768`/`:774`/`:776-781`）、`:807-850` `runRegisteredTool`（`:842`，catch `:843-849`）、`:864` `ok: outcome.handled`、`:875-896` `runPageHandler`（`:885`/`:887`）、`:981-994` `defaultExecutor`（`:989`/`:990`）；`types/tools.ts:84-95`（`open_export_file_editor` 无 executor）与 `:115-120`（`FrontendToolExecution`）；`types/ai.ts:66-78`（payload 无 status/ok）、`:165`（九态）、`:324`（`PendingToolCall` 8 态）；`stores/ai.ts:1195-1204`（`hasDecidedPendingResidue`）、`:1782-1819`（`:1804`/`:1815`/`:1818`）、`:603-626`（`persistStepData`，`:621-622` `ElMessage.error`）；`utils/parts.ts:157-189`（`deriveProjections`，`pendingCall` 门 `:181`）、`:210-228`（`frontendResultStatus`）；`stores/workspace.ts:392-401`（`goto_step` 无守卫）、`:402-415`（`select_defs`，守卫 `:407-409`）、`:416-428`（`set_conditions`，守卫 `:422-424`）；`docs/M2-排期计划.md:276`（R-84）、`:289`（R-97）、`:326`（R-137）、`:327-329`（R-138/139/140）。

**注记（3 条，只作精度订正、不改终裁；详见《决策卡》附录）**：

1. **D1「26 处 `ok:` 生产点」= grep 口径**：`grep -c "ok:"` = **26** ✓、范围 `:119`~`:891` ✓；但其中 **5 处**（`:119`/`:125`/`:132`/`:138`/`:141`）属 `validateArgs` 的 `{ok:true; coerced} \| {ok:false; reason}` **判别联合**（**非 `FrontendToolExecution`**）⇒ 工厂收口的实际目标面 = **21 处 `FrontendToolExecution` 生产点**（`:425`/`:459`/`:704`/`:711`/`:718`/`:734`/`:753`/`:761`/`:768`/`:774`/`:778`/`:815`/`:825`/`:834`/`:842`/`:845`/`:857`/`:864`/`:867`/`:885`/`:891`）。施工棒 S0 按 21 处逐点编号入报告，26 处全量清单同列。
2. **E18② 的「全仓另 10 处」实为 11 条**（清单逐条命中，条目数 = 11；会审文本为计数笔误）。
3. **DS Pro 报告两处内锚漂移已复验订正**：`deriveProjections` 在 `utils/parts.ts:157-189`（非 `ai.ts:181-183`）；`dispatchUiEvent` 调用在 `stores/workspace.ts:374`、三支在 `:392-401`/`:402-415`/`:416-428`（`:368-374` 是 `runUiEvent` 文档区）。**本卡一律采用复核后锚点**。

---

## §10 签字

| 项 | 值 |
|---|---|
| 定稿人 | **K3（代签，署名口径 DC-19）** |
| 签字日期 | **2026-10-11** |
| 授权来源 | K3 指挥官 2026-10-11 三方会审汇总定稿指令 |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（模型显式钉版；零施工、零 git 写、未读 `.env`、未改仓库文件） |
| 配套文档 | 《决策卡-批3③a.md》；草案《批3③a-施工卡草案.md》（**仅存档**） |
