> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 三方会审评议报告（DeepSeek V4 Pro，红队视角），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 会审评议报告 · 批 4④ 施工卡草案（DS V4 Pro · 反方审查视角）

| 项 | 值 |
|---|---|
| 文档定位 | **评议报告**（反方推翻式审查）。评议对象 = `<SCRATCH>\chat-timeline-pkg\批4④-施工卡草案.md`（83,334 B / 485 行 / sha256 `d6835ebc…a57e82`） |
| 角色 | **DS V4 Pro = 反方审查**：默认草案有错，对抗性找茬（渲染回归面 / ③b 段状态机一致性 / 时序竞态 / 投影-段双写不一致 / 折叠边界用例 / 测试锁绕过路径 / 遗漏连带读点） |
| 授权来源 | 本卡《会审任务卡-批4④-DSPro.md》（K3 三方会审派单 · 批次级裁决标准动作） |
| 实读基线 | 主仓库 `<MAIN_REPO>`，HEAD = **`296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`**，分支 main。**只读，零写** |
| 路径红线 | 对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` **零操作**；未读任何 `.env`；未启前后端/dev server/Redis；未跑 yarn/mvn/E2E/CDP |
| 唯一写入 | 本文件 |

---

## §0 首页署名（DC-19）

- **执行模型（自证，非配置推定）**：`DeepSeek/deepseek-v4-pro`（= primary，DS V4 Pro）。
  - 实证 = 读本会话 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_76cc2cfe-5617-4c86-93a5-b984be8f6cd2\agents\main\wire.jsonl` 中 `llm.request` 记录的 `"modelAlias":"DeepSeek/deepseek-v4-pro"`，命中 **16** 次（`"model":"DeepSeek/deepseek-v4-pro"` 15 次 + `"model":"deepseek-v4-pro"` 15 次）。**【实测】**
- **CU 会话 id**：`session_76cc2cfe-5617-4c86-93a5-b984be8f6cd2`（agent = `main`，非子智能体）。
- **下沉子智能体（机械活）**：5 个 `explore` 子智能体（agent-531 / 532 / 533 / 534 / 535），经 `Agent` 工具 `model` 参数**显式钉版 `DeepSeek/deepseek-flash`**（禁靠会话默认）。
  - 实证 = 本会话 `wire.jsonl` 记 `subagent.spawned` × **5**、`"model":"DeepSeek/deepseek-flash"` × **20**（即钉版参数）。**【实测】**
  - 其核验输出已逐份读取（`agents/main/tasks/agent-*/output.log`），结论按【实测】引用。
- **授权来源**：本卡（会审任务卡 · 批4④ · DSPro）。

---

## §1 总体结论：**带条件可施工**（不推翻）

草案方向正确、锚点扎实（§6 的 66 行实读基线经 5 路核验**绝大部分 PASS**），③b 的 D6/D8 闸（挂起语义帧已决不重挂不重执行）在**模板/纯函数层不触闸**的前提下**未被本批正面破坏**。但反方审查命中 **5 项必做条件**，其中两项触及本批**设计层面的自相矛盾或闸边风险**，必须在定稿/施工卡中前置解决，否则 A4' 目标落空、或施工棒可能误写重挂已决段。**不构成推翻**，但「照草案原文直接施工」不可接受。

**核心风险排序**：C1（A4' 与折叠规则可见性冲突）> C2（A4' upsert 的 `requiresAction` 闸风险）> C3（测试基线数字陈旧）> C4（D2 前置核实计数不实+scripts 域遗漏）> C5（D8「保持基线连续」论证失效）。

---

## §2 逐决策点评议（D1~D10）

### D1 合批口径 —— **同意（带条件）**

- 三项同改 `AiPanel.vue:166-267` 渲染区，拆批即同区二次重构，合批理由成立；③b 决策卡 `D1(B)`/`:117-120` 已明文移交，本批是唯一兑现窗口。【实测】`AiPanel.vue:166-267` 段分派区 + `:179-266` 两卡并列均已实读在位。
- 条件：合批回归面须按 **C3** 锚真实测试基线（113 静态 / 124 运行时），否则"回归面可控"的判据失真。

### D2 挂起卡数据源：段驱动 —— **条件同意（前置核实不充分）**

- 方向正确：段是唯一真值（`parts.ts:1-13` 头注），投影只由 `deriveProjections`（`:178-217`）派生；挂起卡"投影自匹配"在合并后失去意义。【实测】
- 但**前置核实（C-3）计数不实**：草案称 `\.pendingCall\b` 全仓 19 处，实测 **21 行 / 26 次**（漏 `stores/ai.ts:1710`、`:1799` 两条注释行，却计入 `types/ai.ts:262` 一条注释行，口径不自洽）。纯代码行实为 **18**。【实测】（子智能体 grep）
- **遗漏 scripts/ 域**：`scripts/m2t2a-smoke.mjs:174` 有运行时 `ai.messages[].pendingCall.status` 消费（草案 §10 Q1 自问"scripts/ 域"，但 C-3 未覆盖）。该消费读的是保留投影，模板改写不影响它，但"核实充分"的表述不成立。【实测】
- 反向确认：`frontend/src/utils/frontend-tools.ts`（红线文件）对 `pendingCall` **零命中**（仅 `:60` 的 `toolCallId?: string` 类型位；文件头 `:14-16` 自述 `generative_form` 不走通用执行器）。草案 Q1 对 frontend-tools.ts 的担忧是过度假设。【实测】
- 结论：段驱动改写的"投影消费面充分性"论证**需订正计数 + 补 scripts/ 域点名**（见 C4），但**不影响 D2 立场成立**（所有非模板消费读的是保留投影）。
- 附带行为变化（草案已自认、但**未锁测试**）：段驱动把"单槽投影（该消息最后一个 requiresAction 段）"改为"逐段成立"。复数挂起段场景（③b R3-4 "parts 支持复数"）下旧模板只出末段卡、新模板逐段出卡。此变化**无测试锁**（见 C9）。

### D3 A4' 落点：段 reason 位 —— **条件同意（本批最高风险决策点，两条必做条件）**

同意方向：段是唯一持久载体（镜像 v2 带 `parts`，`ai.ts:290-298`），`activeForm` 在 5 处被清（`:1665-1673`/`:1714-1718`/`:1731-1734`/`:1368-1372`/`:1433`），复用 `:195` reason 位零新增模板结构。notice 单槽位确会被 20+ 写点挤掉（`ai.ts:420`/`:440`）。【实测】`AiPanel.vue:195` = `<div v-if="part.reason" class="...text-[#f56c6c]">原因：{{ part.reason }}</div>`。

但两条**必做**条件（详见 C1/C2）：

- **C1 — 与 A5' 折叠规则的可见性冲突**：段 reason 落在**工具卡**（`:181-196` 段驱动无条件渲染），而本批 A5' 把工具段合成"活动组"、完成后默认折叠。GF 取消失败发生在段转 `cancelled`（终态、`requiresAction=false`）之后 ⇒ 若该 GF 段与其它工具同组，块折叠，`reason` 默认不可见 —— **恰好违背 A4' 的原始诉求**（红队问题 5："取消回灌失败的提示在 cancelled 态下也要可见"）。草案仅在 D3 推翻条件 #2 提了一句"若实测证明 :195 不可达⇒提升到组头摘要"，但**未前置设计**。这是 D3 与 A5' **同批合批的自相矛盾面**，必须定稿时作为设计约束而非推翻条件处理。
- **C2 — upsert patch 的 `requiresAction` 闸风险**：`ToolPartPatch` 的 `requiresAction` 是**必填字段**（`types/ai.ts:284`），`reason` 可选（`:288`）。`upsertToolPart`（`parts.ts:90-103`）对已存在段走 `:94` 的 `{...existing, ...patch}` 就地合并。A4' 写 reason 时若 patch 误置 `requiresAction:true`，会把 `applyPendingTerminal`（`:1344`→`:1776` 已落 `requiresAction:false`）刚收口的终态段**重挂回 requiresAction** —— **正面破坏 ③b D8 闸"已决不重挂"**。草案只说"不经 applyPendingTerminal（避免污染 confirm 路径）"，**未警告 patch 的 requiresAction 必须显式 `false`**。
- 同域混读（会审 Q2 ①）：`reason` 位现承载 `confirm_decision` 拒绝原因（`parts.ts:372` `reason: frame.reason ?? '用户拒绝'`）与 `tool_result` blocked/rejected 文案（`:326-328`），但**两者落在不同段**（CONFIRM 段 vs FRONTEND 段），无单段双写。草案建议的结构化前缀（"取消回灌失败 —— …"）可消解混读，但**前缀格式须定稿钉死**（现为"建议"级）。【实测】
- ③a M4「`execution.reason` 零读者」同域性（Q2 ②）：`ToolPart.reason` 在 HEAD **有活读者**（`AiPanel.vue:195`）+ 两个活写点（`parts.ts:326-328`/`:372`）【实测】⇒ ③a M4 所指"零读者"若指 `FrontendToolExecution.reason`（`frontend-tools.ts` 侧）则不同域、不冲突；若指 `ToolPart.reason` 则与 HEAD 不符。草案已列【假设】待核，**同意其"待会审核对"的诚实标注**。

### D4 展开态存储：内存 store 只增不减 —— **同意（带建议）**

- 内存 store 合理：sessionStorage 需与镜像同生命周期管理（多一个 `resetSession`/`newChat` 清理残留面），规则②"若入 sessionStorage"为可选。镜像膨胀论据成立。【实测】`writeMirror` 7 字段（`ai.ts:292-300`）无 `appliedSeqs`。
- **建议 C6**：`expandedBlocks: Record<blockId, true>`（只增不减）**无法表达"用户手动收起"**——类型只有 `true`（显式展开）与 `undefined`（未触碰），无 `false`。验收标准 §0.1 用词"**可折叠**的 3 个工具活动组"、D7 引 LibreChat 组头 toggle，若要求手动收起，`Record<blockId,true>` 不成立，须改 `Record<blockId,boolean>` 或双集合。草案未明确"手动收起"是否在验收范围。

### D5 blockId 稳定性 —— **同意**

- `toolCallId`（后端 UUID）/ `reasoning` 段 `seg`（`nextSeg` 单调，`parts.ts:29-31`）均稳定；禁数组下标正确（流式期间段序增长会整体错位）。【实测】

### D6 默认展开 + requiresAction 强制 —— **同意（带建议）**

- 与 `施工卡-刷新版 §7 R4-5` 逐字一致；HITL 确认门/GF 表单若折叠会漏看倒计时/按钮，可用性硬伤成立。【实测】`AiPanel.vue:212-231` 确认块、`:233-259` GF 分支均在挂起卡内，折叠会隐藏它们。
- **建议 C8**：reasoning 段（`:174-177` 现原生 `<details>` 默认折叠）受控化后**默认展开态未定义**——`Block.defaultExpanded` 的"进行中展开/完成后折叠"对无状态概念的 reasoning 段不适用。若按"进行中"判，reasoning 块会从"默认折叠"翻转为"默认展开"，改变 UX。需定稿明确。

### D7 单调用组不套壳 —— **同意**

- 单工具轮（本仓最常见形态）套壳会加"1 个工具活动组"壳 + 一次多余点击；与"完成后折叠为摘要"叠加产生"摘要→点开→一张卡"冗余层级。论据成立。【实测】`parts-reducer.spec.ts` 等既有用例覆盖单工具路径。

### D8 m2t2a-smoke 改写 vs 退役 —— **条件同意（「保持基线连续」论证需改写）**

- **改写方向可**：断言③b 语义（"取消后全消息域无表单残留"）仍有价值，只是判据（被删旁路渲染出的 `tag-form-cancelled` 数）已失效。【实测】`AiPanel.vue` 内 `tag-form-cancelled` **0 处**（③b A3 已删）；`:299` `assert('③b', c2.formRendererCount === 0 && c2.cancelledTags === 2, …)` 已无 DOM 对应物。
- **C5 — 论证缺口**：草案 D8 理由①称该脚本是"GF 表单链路端到端的**唯一** CDP 冒烟"。但仓内既有登记（`docs/M2-排期计划.md` R-106、`docs/evidence/补档-NUMSPEC-施工报告-苞F.md:166`）指出 `injectExpr` 带宿主消息 id 会被 `activeRoundHostId` 轮次守卫判为旧轮帧丢弃 ⇒ 阶段 1/2 的 `message.pendingCall` 永不写入 ⇒ 断言②③③b **现态已失效/假红**。故"改写以**保持基线连续**"是**伪命题**——该脚本现态已是失效基线，改写本质是"**修复或退役**"，不是"保持连续"。理由①的"唯一证据面"论证需按现态重写。【推断】（基于既有台账登记，未实跑复验）
- 附带：`chat-panel-smoke.mjs` 13 断言成立，但 **⑬(:737) 物理先于 ⑫(:797)**（执行序项4→项3），定稿引用时勿按编号推顺序。【实测】

### D9 测试锁（六条）—— **条件同意（覆盖③b移交，但基线数字陈旧 + testid 移位需逐路重验）**

- **③b 移交三项覆盖核对**（会审 Q4）：① m2t2a-smoke 改写 → D8 + D9⑤；② 重投路渲染锁自承 RED → D9③ ② 条；③ data-testid 引用域口径 → A10' + §9 C-2。**逐项覆盖，无遗漏**。【实测】
- **C3 — 基线数字陈旧**：D9④/§5 写"既有 110 例"，实测静态 `it(`/`test(` 计数 = **113**（11 文件 7/5/9/30/5/15/22/4/3/4/9），且 `tools-and-deadline.spec.ts:31` 的 `it.each` 展开 12 组参数 ⇒ **运行时 124 例**。③b 口径"110"无法用任何文件子集复现。草案 §6 第 57 行已自认"113 vs 110"但 D9④/§5 仍写 110，**必须统一**。【实测】
- **testid 移位（Q5 ②）**：三路单卡锁（`ai-panel-parts-render.spec.ts:165-242`）断言的是 **`pending-card-*` 计数 == 0 + `segments` 计数 == 1**（重投 :180-182、新宿主 :210-213、历史整替 :238-240）【实测】。合并后 testid 移位到"requiresAction===true 的工具卡"时，三路场景段均为终态（requiresAction=false）⇒ 计数仍 == 0，**三路锁不被削弱**（"单卡"由 `segments.toHaveLength(1)` 承载，与模板无关）。真正受影响的**只有单槽跟随位置断言 `:105-115`**（`count==1` + `testid=='pending-card-c1'` + DOM 相对位置 `confirmTool < pendingCard < otherTool`）——折叠分组会改变 DOM 顺序，位置断言须明确改写（见 C10）。草案 D9② 说"三路锁保持计数口径"基本正确，但**未点名 `:110-115` 位置断言的改写**。

### D10 GF submitting 窗口与宿主条件 —— **同意**

- `liveForm`（`:465-468` 仅 `filling||submitting`）与 `formHostMessageId`（`:476-489` 扫 `pendingCall.status ∈ {pending,running,approved}`）判据不同域，合并后重审、保持 FormRenderer 单份渲染正确。【实测】`:238` `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId && m.id === formHostMessageId"`。扩判据到 `submitted` 会致"已提交"tag 与 FormRenderer 同现，观感矛盾。论据成立。

---

## §3 条件清单（分级）

### 必做（C1~C5）

| # | 条件 | 依据（文件:行号） | 证据级 |
|---|---|---|---|
| **C1** | **A4' 落点须与 A5' 折叠规则协同设计可见性**：GF 取消失败文案落在"完成后折叠"的块内默认不可见，违背 A4' 诉求。定稿须前置规定——单元素组不套壳（D7）可见 / 组头摘要提升 / `reason` 所在组强制展开 三选一，并写入 D9③ 的 GREEN 断言（"GF 取消失败文案**在折叠块内可见**"）。**不得仅留在 D3 推翻条件 #2 里** | `AiPanel.vue:195` 段驱动工具卡 + `:201-265` 挂起卡；`parts.ts:90-103` upsert；A5' 三规则（草案 §1） | 【推断】（由 D3+A5' 同批交互推出，未实跑构造） |
| **C2** | **A4' 的 upsert 必须锁定 `requiresAction:false`**：`ToolPartPatch.requiresAction` 必填（`types/ai.ts:284`），写 reason 时若置 `true` 会重挂已决段、正面破坏 ③b D8 闸。施工卡须明文规定 patch 形状 = `{ toolCallId, name, kind2, requiresAction: false, reason }`（name/kind2/requiresAction 读自既有段），并建议 `createIfMissing=false`（只更新语义，防段不存在时静默丢写） | `types/ai.ts:280-294`（`:284` 必填 / `:288` reason 可选）；`parts.ts:90-103`（`:93-95` 合并 / `:98-100` 缺 status 早退）；`ai.ts:1344`/`:1776` | 【实测】（类型与函数直读） |
| **C3** | **测试基线统一为 113 静态 / 124 运行时**：D9④、§5、§6 第 57 行的"110"陈旧，须改写；回归门锚 `yarn test` 实跑例数（124） | `tools-and-deadline.spec.ts:31` `it.each`（12 组） | 【实测】（子智能体逐文件 `it(` 计数） |
| **C4** | **D2 前置核实订正 + scripts/ 域补点名**：C-3 的"19 处"改为"21 行/26 次（纯代码 18 行）"，补 `ai.ts:1710`/`:1799`（注释）与 `scripts/m2t2a-smoke.mjs:174`（运行时）；口径统一（注释行是否计入） | 全仓 grep `\.pendingCall\b`；`m2t2a-smoke.mjs:174` | 【实测】 |
| **C5** | **D8「保持基线连续」论证改写**：m2t2a 断言②③③b 现态已失效（`tag-form-cancelled` DOM 已删 + 阶段1/2 可达性存疑），"保持连续"改为"修复或退役"，理由①按现态重写；"退役 vs 改写"的取舍须以"脚本现态是否已假红"为据重议 | `AiPanel.vue`（testid 0 处）；`docs/M2-排期计划.md` R-106；`补档-NUMSPEC-施工报告-苞F.md:166` | 【推断】（基于台账登记，未实跑） |

### 建议（C6~C11）

| # | 条件 | 依据 | 证据级 |
|---|---|---|---|
| **C6** | D4「只增不减 `Record<blockId,true>`」无法表达手动收起；明确手动收起是否在验收范围，若是改 `Record<blockId,boolean>` | 草案 §1 A5'/D4 | 【推断】 |
| **C7** | D2 改写清单（":206/:251-253 用段 status 取代"）与 A9' 删除清单（①③删）对同一行处置口径需统一（改后删 或 直接删），避免施工棒二次返工 | 草案 §1 D2 / A9' | 【推断】 |
| **C8** | reasoning 段默认展开态未定义（现 `<details>` 默认折叠，受控化后需明确） | `AiPanel.vue:174-177` | 【推断】 |
| **C9** | 段驱动"单槽→逐段"行为变化（复数挂起段）无测试锁；在 `blocks.spec.ts` 或 `ai-panel-parts-render.spec.ts` 补一条"多 requiresAction 段"用例或锁其不可达 | 草案 D2 理由② | 【推断】 |
| **C10** | 单槽跟随位置断言 `:105-115`（`confirmTool < pendingCard < otherTool`）受块分组影响，D9② 须明确其改写 | `ai-panel-parts-render.spec.ts:106-115` | 【实测】（断言已读） |
| **C11** | 计数订正：C-2 的 docs/evidence 域"≥8 份"实为 **7 份 / 17 处**（把 `docs/M2-排期计划.md`（docs/ 根，非 evidence）算入才 ≥8）；另"grep 根 package.json 零命中"是**空真**（根无 package.json，宜改表述） | grep `tag-form-cancelled`；仓库根目录清单 | 【实测】 |

---

## §4 攻击面清单（尝试推翻的路径与结果）

| # | 攻击路径 | 结果 |
|---|---|---|
| 1 | **A4' 落段 reason 与折叠规则"完成后折叠"冲突**：GF 取消失败文案默认不可见 | **未攻破 = 草案确有缺陷**。草案仅列 D3 推翻条件 #2，未前置设计 ⇒ **命中必做 C1** |
| 2 | **A4' upsert 经 `ToolPartPatch` 必填 `requiresAction` 重挂已决段**，破坏 ③b D8 闸 | **未攻破 = 草案遗漏**。类型必填字段 + `upsertToolPart` 就地合并构成闸边风险 ⇒ **命中必做 C2** |
| 3 | **D2 前置核实计数不实**（19 vs 21 行/26 次），且漏 scripts/ 域 | **攻破**（草案 C-3 计数口径不自洽 + scripts 域遗漏）⇒ **命中 C4** |
| 4 | **D8「保持基线连续」**：脚本现态已失效（tag DOM 删 + 阶段1/2 假红），"保持连续"伪命题 | **攻破**（论证需改写）⇒ **命中 C5** |
| 5 | **测试基线数字"110"陈旧**，回归门锚假数字 | **攻破** ⇒ **命中 C3** |
| 6 | **D4 只增不减 store 无法表达手动收起**，"可折叠"验收不成立 | **攻破**（设计缺口）⇒ **命中 C6** |
| 7 | **段驱动"单槽→逐段"行为变化无测试锁** | **攻破**（测试缺口）⇒ **命中 C9** |
| 8 | **testid 移位削弱三路单卡锁**（Q5 ②） | **未攻破**。三路锁断言 `count==0`+`segments==1`，与模板无关，testid 移位**不削弱**；仅 `:105-115` 位置断言受影响（C10）【实测】 |
| 9 | **D2 改写清单 vs A9' 删除清单对 :206/:251-253 处置冲突** | **攻破**（口径需统一）⇒ **命中 C7** |
| 10 | **reasoning 块默认展开态未定义** | **攻破**（UX 缺口）⇒ **命中 C8** |
| 11 | **③b 闸被本批正面破坏**（模板/纯函数层） | **未攻破**。`blocks.ts` 纯函数不写段态；D2 模板只读段不写；A4' 是唯一写点，经 C2 锁定后不触闸。闸本体（`hasDecidedPendingResidue` 6 调用点 `ai.ts:753/765/1571/1615/1636/1688`）本批**零触碰** |
| 12 | **frontend-tools.ts 是 D2 遗漏消费面**（Q1 ①） | **未攻破 = 过度假设**。frontend-tools.ts 对 `pendingCall` 零命中，仅 `toolCallId` 类型位 |
| 13 | **suspended 段合并路径"回退已终态段"构成闸旁路** | **未攻破**。`parts.ts:420-438` 的 suspended 是"缺则建/在则覆盖"，但 store 侧 `ai.ts:1688-1690` 在调 `applyFrameToParts` 前已过 `hasDecidedPendingResidue` 闸 ⇒ 已决 id 到不了合并。**但**草案 §6 锚点 31 把 suspended 描述为"建占位段"、A7' 又写"给终态段补 requiresAction"，**两处措辞自相矛盾**，须订正（并入 C7 同族） |
| 14 | **deriveProjections「最后一个 requiresAction 段」不精确** | **攻破（精度）**。实为"最后一个**非 unknown** 的 requiresAction 段"（`:206-210` 显式跳过 unknown）。影响 D2 理由表述精度，不影响结论 |
| 15 | **docs/evidence 计数"≥8 份"** | **攻破** ⇒ **命中 C11**（实为 7 份） |

---

## §5 锚点抽验记录（≥10 项，逐项 PASS/FAIL）

> 口径：行号 = `296ffe2` 实读；本表由 5 个下沉子智能体（钉版 DeepSeek/deepseek-flash）逐行核验 + 本棒关键点直读交叉确认。

| # | 锚点 | 判定 | 证据（文件:行号 + 要点） |
|---|---|---|---|
| 1 | `AiPanel.vue` 文件规模 791 行 + `:166-267` 段分派区 | **PASS** | 模板 :1-320 / script :322-771 / style :773-791；`:166-169` `v-for="part in m.parts" :key="…"`，`:267` 闭合 |
| 2 | `AiPanel.vue:179-196` 工具卡 + `:195` reason 位 | **PASS** | `:179` `<template v-else-if="part.kind==='tool'">`；`:195` `原因：{{ part.reason }}`（`text-[#f56c6c]`）；`:181` 卡根无 data-testid |
| 3 | `AiPanel.vue:201` 挂起卡门控 + testid | **PASS** | `v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"` + `data-testid="'pending-card-'+m.pendingCall.toolCallId"` |
| 4 | `AiPanel.vue:212-231` CONFIRM / `:233-259` GF / `:251-253` 三 tag / `:254-257` 取消失败文案 | **PASS** | `:255` `v-if="ai.activeForm?.status==='cancelled' && ai.activeForm.error"`；`:253`「表单状态已丢失（刷新后无法续填），等待后端超时收敛」 |
| 5 | `parts.ts` 458 行 + `deriveProjections` `:178-217` | **PASS** | `:202` `if(part.requiresAction)`；`:216` `message.pendingCall = pending`（覆盖式赋值，取"最后一个非 unknown requiresAction 段"） |
| 6 | `parts.ts:326-328` / `:372` reason 写点 | **PASS** | `:327` blocked 文案；`:328` rejected `existing?.reason ?? '用户拒绝执行'`；`:372` `reason: frame.reason ?? '用户拒绝'` |
| 7 | `parts.ts:420-438` suspended case | **PASS（措辞订正）** | 实为"缺则建/在则覆盖"（`:433-434` `status:'pending', requiresAction:true` 经 `:94` 就地合并），非草案"仅补建占位段"；"在则覆盖"可把已终态段回退 pending，但被 store 闸 `ai.ts:1688-1690` 前置拦截 |
| 8 | `parts.ts:90-103` upsertToolPart + `ToolPartPatch` | **PASS（关键）** | `:94` `{...existing,...patch}` 就地合并；`types/ai.ts:284` `requiresAction` **必填**、`:288` `reason` 可选 ⇒ **C2 依据** |
| 9 | `ai.ts` 1908 行 + `cancelGenerativeForm` `:1336-1354` | **PASS** | `:1341` cancelled / `:1344` applyPendingTerminal / `:1351-1353` 失败分支 `form.error`（文案 :1352）；`:1347-1350` 收敛早退守卫在失败分支之前 |
| 10 | `ai.ts:1765-1780` applyPendingTerminal | **PASS** | `:1776` `requiresAction:false, status:mapped`；`:1777` deriveProjections |
| 11 | `ai.ts:1235` hasDecidedPendingResidue + 6 调用点 | **PASS** | 调用点 `:753/:765/:1571/:1615/:1636/:1688`（闸本体本批零触碰） |
| 12 | `ai.ts:193-206` migrateMirror | **PASS** | `:203` `requiresAction: pending.status === 'pending'`；`:200-201`「status 一律以 run.status 派生为权威」 |
| 13 | `ai-panel-parts-render.spec.ts` 243 行 / 7 例 / 三路锁 | **PASS** | describe :74（4 例 :75/:94/:119/:134）+ :153（3 例 :165/:186/:217）；三路锁断言 `count==0`+`segments==1`（:180-182/:210-213/:238-240） |
| 14 | 测试例数 113 静态 / 124 运行时 | **FAIL（草案"110"陈旧）** | 11 文件 `it(` = 7/5/9/30/5/15/22/4/3/4/9 = 113；`it.each`（:31）展开 12 ⇒ 运行时 124；③b"110"无法复现 |
| 15 | `m2t2a-smoke.mjs` `:53` / `:299` + 5 处 `assert('` | **PASS** | `:53` 常量、`:299` 断言③b、断言 5 处 `:285/:297/:299/:346/:457`（①出现两次） |
| 16 | 全仓 `\.pendingCall\b`（frontend/src） | **FAIL（草案 19 → 实测 21 行/26 次）** | 漏 `ai.ts:1710`/`:1799` 注释；计入 `types/ai.ts:262` 注释；纯代码 18 行 |
| 17 | `tag-form-cancelled` 引用域 | **部分 FAIL** | 代码/测试域 3 处 PASS（:39/:53/:166）；docs/evidence 域 **7 份/17 处**（非"≥8 份"，`docs/M2-排期计划.md` 在 docs/ 根非 evidence） |
| 18 | `blocks.ts` 不存在 / `expandedBlocks|groupParts|blocksOf` 零命中 | **PASS** | 全仓 grep 零命中；`frontend/src/utils/` 无 blocks.ts |
| 19 | `frontend-tools.ts` pendingCall 零命中 | **PASS** | 仅 `:60` `toolCallId?: string` 类型位；`:14-16` 自述 generative_form 不走通用执行器 |

---

## §6 未复验事项（勿当结论引用）

1. 未跑 `yarn typecheck/test/build`、`verify-e2e.ps1`、CDP 冒烟；未构造 A4'/A9' RED 夹具（C1 的可达性判断为【推断】，A9' ①③④⑤ 的可达性沿用草案【推断】）。
2. C5（m2t2a 脚本现态假红）基于台账登记（R-106 / 苞F报告:166），**未实跑复验**，属【推断】。
3. 测试例数 124（运行时）为按 `it.each` 参数组数推算，**未经 `vitest run` 实测**。
4. 两处行号精度订正（非锚点错误）：`pendingToolCall` getter 实体 `ai.ts:461-476`（草案写 :466-472）；`restoreSuspendedSnapshot` 实体 `ai.ts:725-817`（草案写 :765-811 为其内部循环）。供定稿参考，不影响结论。

---

## §7 签字

- 评议人：**DS V4 Pro（反方审查）**，执行模型 `DeepSeek/deepseek-v4-pro`（wire modelAlias 16 次【实测】）。
- CU 会话 id：`session_76cc2cfe-5617-4c86-93a5-b984be8f6cd2`（agent = main）。
- 下沉子智能体：5 个，显式钉版 `DeepSeek/deepseek-flash`（wire `subagent.spawned` ×5 + `"model":"DeepSeek/deepseek-flash"` ×20【实测】）。
- 结论：**带条件可施工**；必做条件 C1~C5，建议条件 C6~C11；不推翻。
