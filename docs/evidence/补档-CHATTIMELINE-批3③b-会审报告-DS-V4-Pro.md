> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 三方会审评议报告（DeepSeek V4 Pro，红队视角），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 三方会审 · 批 3' ③b 施工方案评议 —— DeepSeek V4 Pro（红队视角）

| 项 | 值 |
|---|---|
| 执行者 | **DeepSeek V4 Pro**（红队视角评议者：对抗性找茬——竞态 / 幂等 / 边界 / 回归面） |
| 授权来源 | **K3 指挥官三方会审卡，2026-10-12，DC-19**（最终裁决权在 K3，本报告为参谋输入） |
| 评议对象 | `<SCRATCH>/chat-timeline-pkg\批3'③b-施工卡草案.md` |
| 评议对象复算 | sha256 = `0d5cd5430a5db5cc2ac6c6fc590ed3812f549e950900a74c38c6051579b2ae6f`（**与落卡值一致**）；61,320 B；518 行（`wc -c` / `wc -l` 实测） |
| 实读基线 | HEAD `505f6d4e0fa1360f04a3dbe3313842f1513a0952`（`git rev-parse HEAD` 实测），分支 `main`，`git status --porcelain` **空**【评议者实测】 |
| 仓库 | `<MAIN_REPO>`（未误入 `<OTHER_REPO>（另一条线的仓，严禁触碰）`） |
| 纪律遵守留证 | **未跑** `yarn typecheck` / `yarn test` / `yarn build` / `scripts/verify-e2e.ps1`；**未启** dev server / 后端 / Redis；**未做** CDP 冒烟 / 注入实验。只读命令仅：`sha256sum`、`wc`、`git rev-parse HEAD`、`git status --porcelain`、`git branch --show-current`、`git show 3edaeb9:…`（子智能体）、`grep -n`、`Read`。**零 git 写、零文件修改（含不改被评议草案）、未读任何 `.env`** |
| 机械活下沉 | 4 个子智能体（`model=DeepSeek/deepseek-flash` 显式钉版）：①A6 `streaming` 穷尽扫描 ②D6/D8 守卫与记账穷举 ③`git show 3edaeb9` 对照（A11/A21）④A22 失败形字符串穷举。子报末行均自证「运行环境无法自省底层模型 ID」 |
| 关键锚点终读 | 由评议者本体亲读：`types/ai.ts`（全文）、`utils/parts.ts`（全文）、`stores/ai.ts`（关键段）、`AiPanel.vue`、`ExportWizardView.vue`、`ImportWizardView.vue`、`utils/frontend-tools.ts`、`types/sse.ts`、`RED-T7.log`（全文） |

---

## 〇 总判定（先行）

**带条件可施工。**

草案与 K3 立场表 16 条整体**成立**，锚点亲读命中率高（仅 3 处行号偏差 + 1 处措辞校准），16 条裁决中**无一条需要改判（反对）**。但存在 **6 类必须在定稿卡闭合的条件**，否则施工棒会踩坑：

1. **编号缺陷（必须修）**：D16 被两用（§2 定义为「边界裁决」，A18 却写「待裁 D16」指 M7）；A19→D17、A20→D18、A22→D19/D20 为**悬空引用**（§2 只到 D16）；§4.1 第 2 条「frontend-tools.ts 红线例外」的门挂在**不存在的 D18** 上 ⇒ **M7/M8/M9（A18/A19/A20）三个裁决点实际缺失**。
2. **D8(B) 的拦截点定义不充分**：`suspended` 帧的 toolCallId 在 `frame.toolCalls[]`（复数数组），**不在** `frame.toolCallId`（单数）——「入口按 toolCallId 统一拦」若只读 `frame.toolCallId` 会**漏拦 suspended 旁路**（已亲读 `types/sse.ts:219-222` 证实）。
3. **D6(B)/D8(B) 的「已决」时序判据未区分本地终态与后端终态**：`failed`/`unknown` 是前端本地悲观态，与 `succeeded/cancelled/…`（后端权威终态）语义不同；一刀切「已决⇒不重执行」会锁死通用 FRONTEND 工具的**重试**路径。
4. **D5(A) 的「永久 pending 段」归属缺失**：`suspended 单独到达建 pending 段` 后若终态帧永不达，`expireResidueToolParts` 只在 `done/error` 触发，无专属过期路径；且该风险**与 D8 无关**，取决于「suspended 后必发 confirm_request/frontend_tool_request」这一**未实跑验证的后端保证**。
5. **D1(B) 与 D2(A) 的 O1/A3 范围张力**：草案 D1(B)「代价」栏把 A3/A4 列为「仍悬空（须显式承接，即划出 ③b）」，而 D2(A)「O1 死码殿后」把 A3 划回 ③b 内删除——两处口径需 K3 统一。
6. **三处行号/措辞订正 + 一处台账漏登记**（见 §三、§六）：A21 R-141④ `parts.ts` 终点 `:249`→`:250`；A6「Pick」实位 `types/ai.ts:341` 非 `ai.ts:279-304`；A16「红在夹具」实为 **4/4 全红**（非仅 T7③④）且「GREEN 为真锁」未实跑验证；A22 之外 E19 **漏登记** `ExportWizardView.vue:1340` 同族面。

---

## 一 逐条判定表（D1~D16，16 条齐全）

> 锚点均评议者亲读（`505f6d4` 工作树实读），非抄草案/立场表。

### D1 —— **(B) 只做状态机与死码，挂起卡模板合并移交批 4 ④** —— **同意 + 修正**

- **理由**：验收点 1「同一 toolCallId 全局唯一一张卡」在**段状态机层**即可达成——`hasDecidedPendingResidue`（`stores/ai.ts:1210-1219`）已是**跨消息**查域（`this.messages.some(...)`），与模板合并（A1）无关。A1 是渲染块重构（`AiPanel.vue:179-287` 工具卡块与挂起卡块并列），归批 4 ④ 正确。
- **修正**：草案 D1(B) 的「代价」栏写「A3/A4 的终态 tag 死码与 GF 错误渲染位仍悬空」，把 **A3（终态 tag 死码）** 也归入「划出 ③b」；但 A3 本质是「死码删除」而非「渲染重构」，**可独立于 A1 在 ③b 内处置**（这正是 D2(A) 的「O1 死码殿后」）。此与 D2 的口径张力见 D2 条与本报告重点问题 2。建议 K3 明确：A3 属 ③b「死码」子集，A4 才随 A1 归批 4。
- 前提证实情况：无需【假设】前提，纯范围裁决。

### D2 —— **(A) 先关旁路再删死码，同批定序 D8 先行、O1 殿后** —— **同意 + 修正**

- **理由**：亲读证实「唯一活旁路 = `suspended` 帧对通用 FRONTEND 段补 `requiresAction`」。`AiPanel.vue:264-277` 的终态 tag 分支确实经 `suspended`（`parts.ts:402-416`，`createIfMissing=false`、不改 status）+ `pendingStatusOf`（`parts.ts:106-127`）可达：终态段被补 `requiresAction=true` 后派生 `pendingStatusOf('succeeded')→'succeeded'` 等，`:266/:268/:269/:272` 等 tag 可渲染。先删分支会丢渲染位，D8 先行的定序**正确**。
- **修正（范围张力）**：见 D1。若 K3 终判「A3 归批 4」，则 D2(A) 的「O1 死码殿后」应改写为「③b 关旁路（D8），O1 删除移交批 4」；若「A3 在 ③b 删」，则草案 D1(B) 代价栏须改。二选一须 K3 钉死。
- **第三依赖**（重点问题 2 作答）：除 A9/A10 外，终态 tag 分支可达性还依赖 **`restoreSuspendedSnapshot` 快照重建**（`stores/ai.ts:760` 落 `status:'pending'`）——但该路已被 `hasDecidedPendingResidue`（`:740`）守卫，只建**非已决**条目的 pending 段，**不构成**终态 tag 的活旁路；以及 **A10 的 `failed` 落 `pendingStatusOf` default**（`parts.ts:120-126`）使 rogue `failed+requiresAction` 显示为「待确认」卡——这是**另一类**终态 tag 可达，D8 关旁路后一并消解。

### D3 —— **(A) 删 sanitizeMirrorResidues「进行中轮」守卫 + 入参契约注释** —— **同意**

- **理由**：亲读 `stores/ai.ts:222-240`。守卫 `:225-227`（`if (message.done !== true) continue`）确为死码：写入侧 `writeMirror`（`:279-304`）的 7 字段字面量**不含 `done`**（`:290-298`），读取侧 `loadHistory`（`:593-597`）在调用 sanitize（`:601`）**之前**已 `done:true` 归一 ⇒ `continue` 永不触发。反向罢工方向成立：未来直调镜像原始数据（`done===undefined`）时 `undefined !== true` ⇒ 整段跳过。D3(A) 正确。
- 锚点：`stores/ai.ts:225-227`、`:279-304`、`:593-597`、`:601`，均亲读命中。

### D4 —— **(A) loadHistory 镜像分支补 part.streaming=false** —— **同意 + 修正**

- **理由**：亲读 `stores/ai.ts:593-597`（只清 `message.streaming`、不清 `part.streaming`）+ 子智能体穷尽扫描证实**渲染层零 `part.streaming` 读点**（唯一渲染读点 `AiPanel.vue:529` 读的是 `message.streaming`）。镜像 `part.streaming=true` 假态仅影响 `openStreamPart`/`sealTrailingStreamPart` 的段边界判断，不影响视觉。D4(A) 方向正确。
- **修正**：①「一处一行」不实——清 `part.streaming` 需 `parts.map`（parts 是数组），是**数行**而非一行；②A6 锚点「`Pick` 不含 done」的 `Pick` 实位在 `types/ai.ts:341`，`ai.ts:279-304` 是等价对象字面量，措辞需订正。

### D5 —— **(A) suspended 单帧补建段（createIfMissing=true + status:'pending'），序在 D8 之后** —— **同意 + 修正**

- **理由**：亲读 `parts.ts:402-416`（suspended 支 `createIfMissing=false`）与 `parts.ts:98-100`（段不存在即 return）。语义完备方向成立。
- **修正（重点问题 3，红队最强风险点）**：D5(A) 与 D8(B) **在入口并不互相排斥**（D8 拦「已决」id，D5 只建「不存在段」的新 id，二者正交），K3 理由「D8 关旁路后才安全」说得通；但**「永久 pending 段」风险与 D8 无关**——它取决于「suspended 后必发 confirm_request/frontend_tool_request」这一**后端保证**（A5 引批 2 结论，【推断】未复验）。若该保证不成立，D5(A) 建的 pending 段**无专属过期路径**（`expireResidueToolParts` 只在 `done/error` 触发，`stores/ai.ts:1661/1678`），会留「永久 pending」卡。**施工卡须写明**：D5(A) 的 pending 段如何被过期降级收敛（复用 `expireResidueToolParts` 轮终态路，还是接受「后端必发终态帧」为前提），并把它写进测试锁。
- 前提证实情况：后端保证**未实跑验证**（不授权跑后端，按【推断】处理，判定降级为带条件）。

### D6 —— **(B) 店级守卫，与 D8 合并为一处设计** —— **同意 + 修正**

- **理由**：亲读证实 `hasDecidedPendingResidue`（`stores/ai.ts:1210-1219`）**已是跨消息查域**（`this.messages.some(item => item.parts.some(...))`），判据 = `isFrameDecidedPendingStatus(part.status)`（`:388-392`，7 态终态白名单）。所以 D6(B)「店级守卫」**不是新建跨消息查域**（草案 D6(A) 描述「findToolPart 扩为全量消息查」会把「写定位」与「已决判定」两件事混为一谈）——`findToolPart`/`upsertToolPart` 仍**单消息**（`parts.ts:77-80`/`:90-103`），而「已决」判定**早已**跨消息。D6(B) 正确且成本最低。
- **修正**：①「已决」时序判据须区分**本地终态（`failed`/`unknown`）与后端终态（`succeeded`/`rejected`/`blocked`/`expired`/`cancelled`）**——前者是前端悲观态，一刀切「已决⇒不建段」会把通用 FRONTEND 工具的**重试**也锁死（见重点问题 1 的具体帧序）；②D6(B) 后 re-attach 仍新建「（正在重挂…）」宿主消息，但工具帧被拦 ⇒ 该宿主为空消息并最终 `done:true`，属**可接受的 UX 残留**，施工卡应如实登记（不得销记「重挂已闭合」）。
- **竞态反例（已构造）**：见重点问题 1 与 §二·竞态。

### D7 —— **条件式（S0 复取 AiHistory 定义）** —— **同意（S0 探针已由评议者完成：无状态字段 ⇒ (B)）**

- **S0 结果（评议者亲读 `types/ai.ts:133-150`）**：`AiHistoryMessage`（`:134-143`）字段全集 = `type / text / toolCalls?{id,type,name,arguments} / toolResponses?{id,name,responseData} / timestamp`。**无 `status`、无 `ok`、无 `done`、无段级状态**。`AiHistory`（`:146-150`）= `{sessionId, count, messages}`。
- **结论**：D7(A)（按后端历史字段判）**不可行**（载荷无状态字段，除非后端改——出 ③b 范围）。⇒ 落在 **D7(B)**：维持 `loadHistory` 硬编码（`stores/ai.ts:659 status:'succeeded'`）+ 口径注记「历史段不承载终态判定」，并**登记「后端历史携带状态」为新立项候选（出 ③b）**。
- 前提证实情况：【假设】前提已由评议者**证实**（无状态字段）。

### D8 —— **(B) handleFrame 入口按 toolCallId 统一拦（帧型无关）** —— **同意 + 修正（附竞态反例）**

- **理由**：亲读证实缺口——`frontend_tool_request`（`stores/ai.ts:1587-1606`）守卫 `:1588` 带 `frame.name === GENERATIVE_FORM_TOOL` 前缀，通用 FRONTEND 走 `:1605 void this.runFrontendTool(...)` **无守卫直跑**；`suspended`（`:1629-1645`）守卫 `:1635` 同样 GF 前缀。`parts.ts:359-372` 的 `frontend_tool_request` 写段**无 first-wins**（`upsertToolPart` 为 `{...existing, ...patch}` 合并，`:93-95`）⇒ 重投帧把终态段**改回 `pending`**，且 `:1605` 重执行。D8(B) 最彻底、与 D6(B) 同源合并，方向正确。
- **竞态反例（应拦却放过，已亲读 `types/sse.ts:219-222` 证实）**：`SseSuspendedFrame` 的字段是 **`toolCalls: PendingToolCallSnapshot[]`（复数数组）**，**没有**单数 `frame.toolCallId`（其余工具帧 `SseToolStartFrame:133` / `SseToolResultFrame:143` / `SseConfirmRequestFrame:160` / `SseFrontendToolRequestFrame:194` 等才有单数 `toolCallId`）。若 D8(B) 落地为「`handleFrame` 入口读 `frame.toolCallId` 统一拦」，**suspended 帧的 `frame.toolCallId === undefined` ⇒ 整帧漏拦**，A10 的 rogue `failed+requiresAction` 旁路原样存活。**具体帧序**：①通用 FRONTEND 工具经 `frontend_tool_request`→执行器异常→本地终态 `failed`；②后端/回放发 `suspended{ toolCalls:[{kind:'FRONTEND', name:'download_export_file', toolCallId:X}] }`；③入口拦 `frame.toolCallId`（undefined）→ 放行；④`suspended` case `waiting.name !== GENERATIVE_FORM_TOOL` → 无守卫 → `applyFrameToParts` 给 `failed` 段补 `requiresAction:true`（status 不改）；⑤`deriveProjections` → `pendingStatusOf('failed')` → `'pending'`（`parts.ts:120-126` default）⇒ 挂起卡复活。
- **修正（必须）**：D8(B) 的拦截点**不能是单一入口单字段**，须按帧型定义 toolCallId 来源——单工具帧读 `frame.toolCallId`，`suspended` 帧遍历 `frame.toolCalls[].toolCallId`。最稳妥是**把守卫放在各相关 case 内**（`frontend_tool_request` / `suspended` / `confirm_request` / `confirm_decision`），而非一个前置统一拦点。
- 红线合规：草案与 K3 均明确「不得销记『重挂已闭合』」，合规（见重点问题 6）。

### D9 —— **(C) 维持两表 + 防漂移注记；订正 R-141④ 误述** —— **同意 + 修正**

- **理由**：亲读 + 子智能体 `git show 3edaeb9` 证实：`frameStatusToPendingStatus`（`stores/ai.ts:340-364`）**确有** `CANCELLED` run-cancel 依据注释（`:346-350` 三行），且 `3edaeb9:339-343` 同期存在（③a 未触该函数）⇒ R-141④「缺 CANCELLED 依据注释」**误述成立**；唯一真实分叉 = `SUCCEEDED`（`parts.ts:234-236` 有、`ai.ts` 侧无）。D9(C) 低危维持正确。
- **修正（新发现，草案未列）**：除两张翻译表外，还有**第三对同族漂移面**——`isFrameDecidedPendingStatus`（`ai.ts:388-392`）与 `localTerminal`（`parts.ts:383-386`）是**两个独立枚举、成员集合完全相同（7 态）、无共享常量**。D9(C) 的「防漂移注记」应**一并覆盖**这一对，否则「两表」注记不完整。

### D10 —— **(A) 执行返回后校验轮次身份，失配丢弃段写+回灌** —— **同意 + 修正**

- **理由**：亲读 `runFrontendTool`（`stores/ai.ts:1800-1842`）：`:1816-1821 await executeFrontendTool(...)` 之后**无任何轮次身份守卫**，`:1829-1836` 写终态段、`:1841 await submitFrontendToolResult(...)` 回灌 POST，均无条件执行。`send` 的 `finally`（`:929-943`）只复位 `loading/suspended/currentAbort/activeRoundHostId` 并标宿主 `done`，**不清段、不中止在途执行器**。D10(A) 正确。
- **修正（实现细节）**：`isCurrentRound` 是**四个互不相同的局部闭包**（`ai.ts:811/883/1037/1428`，均 `currentAbort === controller`），`runFrontendTool` 内**没有该闭包作用域**。D10(A) 的「同族判据」**非 drop-in**：须在 `runFrontendTool` 入口捕获轮次身份（如 `const controllerAtStart = currentAbort`），await 后比对 `currentAbort === controllerAtStart`。施工卡须写明这一捕获点，否则会误用 `this.sessionId` 或 `activeRoundHostId` 当判据。

### D11 —— **(A) goStep 单点弹 warning，syncStepToBackend 不自弹；导入侧同改** —— **同意**

- **理由**：亲读导出侧 `ExportWizardView.vue:624`（`ElMessage.error`）+ `:579`（`ElMessage.warning`）= 双弹；导入侧 `ImportWizardView.vue:773`（error）+ `:731`（warning）= 双弹。同步失败本就是不阻断（下次切步重试），warning 是正确严重度。D11(A) 正确，两侧同改正确。
- 锚点均亲读命中。

### D12 —— **(A) 补 import.meta.env.DEV 门** —— **同意**

- **理由**：亲读 `assertUnknownTerminalInvariant`（`parts.ts:129-139`）函数体无条件 `console.error`（`:135-138`），调用点 `:196-205` 的 X1 分支（`:200-201`）。无 DEV 门属实。D12(A) 一行级改动、零风险，正确。

### D13 —— **(B) 脚本头写明复跑前重启 dev server** —— **同意**

- **理由**：A15 锚点（`batch3a-outcome-smoke.mjs:147-150` 裸路径 `import('/src/...')` 探针）为仓外文件；HMR 换版脆弱为【推断】。D13(B) 成本最低、不影响入库代码，正确。（评议者未亲读该仓外脚本，但 D13(B) 属保守选型，不依赖该脚本细节。）

### D14 —— **(A) 补「夹具就位仍红」反向留证** —— **同意（评议者亲读日志后强化）**

- **理由（亲读 `RED-T7.log` 全文 589 行）**：4 个用例**全部 red-on-fixture**，且失败文案证明执行在夹具相关守卫处**短路、未触达被测断言**：T7① `selectDefs reject` 未生效 → 收到「已推进到…查询条件」；T7② `goToStep reject` 未生效 → 无「服务端步骤同步失败」；T7③ scope/step 未拨到结果步 → 命中「页面=export，步骤=SELECT_DEFS 不支持下载」而非「无法切换」；T7④ `getFiles` 未回结果 → 命中「没有 DEMO 导出结果文件」而非「保存选择与条件失败」。
- **强化（草案低估）**：草案 A16 只把「红在夹具」归给 **T7③④**，实测**四例全红在夹具**（T7①② 是 mock-reject 未生效，T7③④ 是 scope/getFiles 未就位）。且「GREEN 终态断言为真锁」在本日志中**无任何绿色留证**（本日志只有 RED）——它本身是【假设】级结论，依赖 ③a 红队 O-4 的推理，**未实跑验证**。这**强化**了 D14(A) 的必要性：不做「夹具就位仍红」，就无法证明这 4 条断言在真实被测代码上真的会红。

### D15 —— **(A) 导入侧 goStep 对齐导出（结构化返回 + syncWarning 回灌）** —— **同意**

- **理由**：亲读 `ImportWizardView.vue:714`（`Promise<boolean>`）、`:731`（仅 `ElMessage.warning`，无 syncWarning 返回）、handler `:1028 const advanced = await goStep(index)`、`:1034 return '已推进到…'`（不含同步失败信息）。对照导出侧 `ExportWizardView.vue:1202-1203` 已把 `advanced.syncWarning` 带回灌。不对称属实。D15(A) 正确。

### D16 —— **(A) 严守台账边界 + 串行序钉死 + CDP 冒烟强制** —— **同意 + 修正**

- **理由**：亲读证实 A22 的「同族谎报面」——`interpretHandlerOutcome`（`frontend-tools.ts:970-989`）把字符串 return 折 `outcome:'ok'`（`:974-975`），故 `ExportWizardView.vue:1183/1188`、`ImportWizardView.vue:1019/1024` 等只读/越界拒绝字符串被消费为成功（卡面 tag「已执行」、正文是拒绝文案）。台账已把它划给 R-84 批 / D3(iii)，D16(A) 守边界正确；A20（`frontend-tools.ts:921-925` availability 口径）作为唯一例外候选也合理。
- **修正（编号缺陷，必须修）**：①**D16 被两用**——§2 定义它是「边界裁决」，但 A18（M7）写「待裁 D16」，指向 M7 的 startExport 裁决，二者不是同一件事；②**A19→D17、A20→D18、A22→D19/D20 为悬空引用**（§2 无 D17~D20）；③§4.1 第 2 条「frontend-tools.ts 红线例外须 K3 在 **D18** 明确授权」的 gate **挂在不存在 D18 上** ⇒ **M7/M8/M9（A18/A19/A20）三个裁决点实际缺失**。结论：这不是无害编号遗留，是**会误导施工的缺陷**——施工棒在 A19/A20 找不到裁决、在 §4.1 找不到红线例外授权。订正建议：**把 M7/M8/M9 移出 ③b（登记不修，A18 原文本就建议「随下一批」），或补 D17/D18 并同步进 K3 立场表**；并统一 A18 的「D16」指向、删除悬空 D19/D20（其承载内容并入 D16）。
- 边界项（串行序 / 批 4 ④ 同文件）：D1(B) 后 ③b **不触** `AiPanel.vue`（A1/A3/A4 若均划出），故「与批 4 ④ 折叠规则同文件不同区」的串行风险**实际不触发**——这进一步支持 D1(B)。`stores/ai.ts` / `parts.ts` 无批 4 ④ / R-137 文件交集，串行无前置。

---

## 二 新增风险 / 遗漏（红队视角，草案未列者）

### 2.1 竞态（六路帧序穷举）

| # | 组合 | 后果 | 证据强度 |
|---|---|---|---|
| R1 | **重投帧（通用 FRONTEND）** × 终态段 | `parts.ts:359-372` 无 first-wins，`upsertToolPart` 合并把 `status` 改回 `pending`，`:1605` 重执行 | 【实测】代码路径成立；「后端真重投」【推断】未验证 |
| R2 | **re-attach 回放** × 新宿主消息 | `appliedSeqs` 是**消息级**（`types/ai.ts:216`，不入镜像 `ai.ts:289`），新宿主消息 `appliedSeqs` 为空 ⇒ `alreadyApplied`（`ai.ts:1534`）**永不短路** ⇒ 全量回放帧重新应用；`findToolPart` 单消息 miss ⇒ 新消息新建段 ⇒ **两张卡** | 【实测】+ 子智能体 §4.3.3 确认「同一 seq 投到不同宿主，`runSeq` 认为已见、`appliedSeqs` 认为未见 ⇒ 重放一次」 |
| R3 | **loadHistory 整替** × 本地 `unknown/failed` 段 | `ai.ts:646-678` 整体替换 + `:659` 硬编码 `succeeded` ⇒ `unknown/failed` **被改写为 succeeded**（状态翻转，非判据翻转） | 【实测】硬编码点 |
| R4 | **suspended 补写** × 终态段（通用 FRONTEND） | `parts.ts:402-416` 补 `requiresAction:true` 不改 status；`pendingStatusOf` 把 `failed` 折 `'pending'`（`:120-126`）⇒ 挂起卡复活（A10） | 【实测】 |
| R5 | **快照收敛 restoreSuspendedSnapshot** × 已决条目 | `ai.ts:740` 有 `hasDecidedPendingResidue` 守卫（skip 已决），**不是**活旁路；但 `:760` 对非已决条目落 `pending` | 【实测】 |
| R6 | **stop() 后回灌结果** × 新轮 | `runFrontendTool` await 后无轮次守卫（`:1816-1841`）⇒ 旧轮执行器返回仍写段+POST | 【实测】 |

**六路两两交叉最危险的组合**：**R2 × R4**（re-attach 新宿主回放 + suspended 补写）——回放到新宿主的 `suspended` 帧因 `findToolPart` 单消息 miss 而**无段可补**（`createIfMissing=false` 直接 return），但若新宿主此前已被 `frontend_tool_request` 建了 pending 段、又接 suspended，则叠加 A9 的「改回 pending」+ A10 的「failed 折 pending」→ 单段双面卡。

**D8(B)+D10(A)+D5(A) 三叠加**：D8 拦「已决」帧、D10 拦「旧轮」写、D5 建「新 id」段——三者判据**正交**（已决 / 轮次 / 段存在性），**不互相抵消也不互相放大**；但 D5 建的 pending 段若终态帧被 D8 拦（因该 id 在**别的消息**已有终态段），会出现「新消息有 pending 段、旧消息有终态段」的**跨消息双段**——这是 D5(A) 与 D6(B)/D8(B)「跨消息查域」叠加的**新竞态**，草案未列。施工卡须为「D5 建段前是否也跨消息查 `hasDecidedPendingResidue`」定判据。

### 2.2 幂等

- **`appliedSeqs`（消息级）在 D8 闸门下是否被绕过**：**是**。`appliedSeqs` 依附宿主消息（`alreadyApplied`/`markApplied` 均传 `message`），而 `runSeq`（`ai.ts:444`）是 runId 级、跨消息。**跨宿主回放时 seq 幂等被绕过**（R2），这不是 D8 引入的，而是 D8(B) 若只拦「已决」、不解决「seq 记账跨宿主」，则**重投帧的 seq 语义在 re-attach 新宿主下仍会重放**。D8(B) 落地时须明确：拦的是「已决 toolCallId 的**写段/建段/执行**」，而 **`loading` 复位与终态收敛**仍走既有 `isTerminalFrame`（`:1701-1703`）与 `done/error` 分支，不受影响——但被 D8 拦掉的帧**不再走 `markApplied`**（`:1697-1699` 在 switch 之后），**该帧下次再投仍判「未应用」**，依赖 D8 的「已决」判据兜底而非 seq 幂等兜底，二者职责须在施工卡写明。
- **`runSeq` 跨会话残留**（子智能体 §4.2）：`resetSession`（`ai.ts:1376-1437`）**未清 `runSeq`**；key 为 runId，仅当后端复用 runId 才串味——登记为边界，③b 不必动。

### 2.3 边界

- **toolCallId 跨轮复用**：`hasDecidedPendingResidue` 查**全部消息**（含历史轮次），若后端复用 toolCallId（草案 E3 称「服务端 UUID 不可达」），D6(B)/D8(B) 会**误拦新轮同名工具**。E3 的「不可达」依据未在③b 复验，D6(B)/D8(B) 落地时应在注记里保留此边界（不得静默假定「必不复用」）。
- **loadHistory 整替后「已决」判据翻转**：整替前 `unknown`/`failed` ∈ localTerminal ⇒ 已决=true；整替后硬编码 `succeeded` ⇒ 已决=true（`succeeded` 也是终态）。**判据不翻转，但底层状态翻转**（unknown→succeeded）——这是 D7 的事，D8(B) 不覆盖；D7(B) 注记措辞须防止下批误读（见重点问题 4）。

### 2.4 回归面

- **D8 进 `handleFrame` 主路径的回归锁**：若 D8(B) 按本报告建议「守卫放在各 case 内」而非入口，则 `handleFrame` 的 `frontend_tool_request`/`suspended` case 改动会**直接触碰** `frontend-tool-result-terminal.spec.ts`（T1 生死线 20 例）、`parts-residue-cleanup.spec.ts`（21 例）这两套 ③a 锁。施工卡 §5.2 已列这两套，但**未列**「D8 守卫改动后，`hasDecidedPendingResidue` 现有 4 个调用点（`:728/:740/:1588/:1635`）中 GF 前缀移除是否会改变既有 GF 用例的语义」——GF 用例（`frontend-tool-result-terminal.spec.ts:419-432`）在去前缀后**行为不变**（去前缀是放宽，GF 仍在守卫内），但须**留回归留证**。
- **`utils/frontend-tools.ts`「不触」红线在 A20 例外下的守界**：A20 唯一例外点 `:921-925`（`runUiEventAction` availability）。若 K3 在（悬空的）D18 授权动它，须注意 `runUiEventAction` 是**路由 3（ui_event 同名动作）**，与 `runPageHandler`（A22 面）是**不同路由**（`frontend-tools.ts:908` vs `:936`），改动 A20 不会触碰 A22 的 `interpretHandlerOutcome`，但两处同文件、同属「谎报面」族，D16 的「不搭车」红线须在收工报告里**逐点声明**「A20 动、A22 不动」的边界，防施工棒顺手改。

---

## 三 A 表抽验结论

> 判定：属实 / 不属实 / 部分属实。锚点均评议者亲读。

### 3.1 必读【假设】5 条（全覆盖）

| 条目 | 草案断言原文摘要 | 评议者亲读事实 | 判定 |
|---|---|---|---|
| **A3** | 旁路可达性触发条件未实跑；「恒 pending」否证为【实测】，唯一活旁路=suspended | `parts.ts:402-416` suspended 不改 status 补 requiresAction；`parts.ts:106-127` pendingStatusOf 终态映射 + default 折 pending；`deriveProjections:196-205` X1 只拦 unknown。镜像路 `ai.ts:593-601` 强制 done+清 requiresAction ⇒ pendingCall 恒 null（OBS-3 属实）。**唯一活旁路确为 suspended 对通用 FRONTEND 终态段** | **属实**（触发条件【推断】保留，不升级） |
| **A6** | 镜像 streaming 假态「实际可复现」 | 子智能体穷尽扫描：渲染层零 `part.streaming` 读点；唯一渲染读 `AiPanel.vue:529 message.streaming`；写侧 `:294 parts 原样入镜像`、读侧 `:593-597 只清 message.streaming` 均命中。假态仅影响段边界（`parts.ts:46/49/71`） | **属实**（「实际可复现」仍【假设】未实跑；「渲染层不读 part.streaming」【实测】升级属实） |
| **A8** | D7(A) 依赖未核实的 AiHistory 字段形状 | 亲读 `types/ai.ts:134-150`：`AiHistoryMessage` 无 `status/ok/done`；`toolResponses` 只有 `responseData`。⇒ D7(A) 不可行 | **属实**（前提已证：无状态字段） |
| **A10** | rogue `failed+requiresAction` 可达性 | `parts.ts:120-126` pendingStatusOf default 折 failed→'pending'；`deriveProjections:200-201` X1 只拦 unknown，failed 落 else 正常投影；旁路 `:402-416` suspended 补 requiresAction + `ai.ts:1635` 守卫 GF 前缀（通用不拦） | **属实**（可达性依赖 A9 旁路成立，【推断】保留） |
| **A16** | T7③④ RED「红在夹具」未亲读日志 | 亲读 `RED-T7.log`：4/4 全 red，且 4 例均在夹具守卫短路、未达被测断言；「红在夹具」属实但**不仅 T7③④**（T7①② mock 未生效、T7③④ scope/getFiles 未就位）；「GREEN 为真锁」无绿色留证 | **部分属实**（方向属实，范围低估 + GREEN 未证） |

### 3.2 ★ 关联条目抽验（亲读 ≥6 条）

| 条目 | 草案断言摘要 | 亲读事实 | 判定 |
|---|---|---|---|
| **A1**★D1 | 两卡并列、`parts.ts:41` 注释「子包③」 | `AiPanel.vue:179-196` 工具卡块 + `:198-287` 挂起卡块并列（`:199` 注释「③b 把两者合并成一卡之前」）；`parts.ts:40-42` 注释「段级状态参与后续状态机（子包③）」 | **属实** |
| **A4**★D1 | 错误文案唯一渲染位在挂起卡内，外层门控恒假 | `AiPanel.vue:258-260`（`activeForm.status==='cancelled' && activeForm.error`）在 GF 分支 `:234-262` 内，挂起卡整块 `:201` 门控 `m.pendingCall && …`；段终态后 requiresAction=false ⇒ pendingCall=null ⇒ 块不渲染 | **属实** |
| **A7**★D6 | re-attach 新宿主 ⇒ 两张卡 | `ai.ts:1022 lastSeq`、`:1023 appendAssistantMessage`、`:1035 activeRoundHostId=assistant.id`、`:1046 onFrame→handleFrame(frame, assistant.id)`；`parts.ts:77-80 findToolPart 单消息`、`:90-103 upsertToolPart 未命中即新建` | **属实**（附：`hasDecidedPendingResidue` 已跨消息，是「已决判定」跨消息、「段定位」单消息，草案 D6(A) 措辞把两者混述） |
| **A8**★D8 | loadHistory 整替硬编码 succeeded | `ai.ts:646 this.messages = history.messages.map`、`:658 kind2:'UNKNOWN'`、`:659 status:'succeeded'`、`:662 requiresAction:false`、`:676 deriveProjections` | **属实** |
| **A9**★D8 | 通用 FRONTEND 重投不经守卫 + 改回 pending + 重执行 | `ai.ts:1588` GF 前缀守卫、`:1605` 直跑 runFrontendTool、`:1635` suspended GF 前缀；`parts.ts:359-372` 写段 status:'pending'+requiresAction:true 无 first-wins（`upsertToolPart` `:93-95` 合并） | **属实** |
| **A10**★D8 | 同族面是否漏捕 blocked/expired/rejected | 亲读：`blocked/expired/rejected/cancelled` 经 suspended 旁路补 requiresAction 后，`pendingStatusOf` 有**显式 case**（`parts.ts:110-119`）映射到对应终态 tag（不折 pending），故它们经旁路**不会**变「待确认」卡，而是显示**终态 tag**（正是 A3 的「终态 tag 可达」）；**只有 `failed`（+`unknown` 被 X1 拦）落 default→'pending'**。⇒ 草案「同族面漏捕」表述**部分属实**：`blocked/expired/rejected` 非「复活为 pending」，而是「复活为终态 tag 挂起卡」，后果不同 | **部分属实** |
| **A11**★D16(间接) | 台账 R-141④ 误述订正 | 亲读 + `git show 3edaeb9`：CANCELLED run-cancel 注释 `ai.ts:346-350` 存在且 3edaeb9 同期存在；`SUCCEEDED` 只在 `parts.ts:234-236`、`ai.ts` 侧缺 | **属实**（唯一真实分叉=SUCCEEDED） |
| **A22**★D16 | 只读/越界拒绝字符串仍折 ok + 导入 11 处 | 亲读 `interpretHandlerOutcome:974-975`（字符串→ok）+ `ExportWizardView:1183/1188` + 子智能体穷举「导入侧 11 处、导出侧其余 10 处」精确命中；**新发现**：E19 漏登记 `ExportWizardView.vue:1340`（download_export_file 执行器「本次未发起下载」折 ok） | **属实**（且 E19 清单非全集，漏 1 处） |

### 3.3 草案内部引用一致性（D17~D20 悬空）

**判定：会误导施工的缺陷，非无害编号遗留。** §2 只定义 D1~D16，但 A19→「待裁 D17」、A20→「待裁 D18」、A22→「D19/D20」，且 §4.1 第 2 条「frontend-tools.ts 红线例外须 K3 在 D18 授权」的 gate 悬空 ⇒ **M7/M8/M9（A18/A19/A20）三个裁决点实际缺失**，A18 还把「D16」错指为 M7 裁决（D16 实为边界）。订正见 D16 修正与 §五条件 1。

### 3.4 行号不符（草案称漂移表有误处）

1. **A21 R-141④ `parts.ts`**：草案写 `parts.ts:232-249`，实测 `frontendResultStatus` 函数 HEAD 范围为 **`:232-250`**（`:250` 是函数闭合大括号，`git show 3edaeb9` 对照 +22 双臂一致，终点差 1 行）。
2. **A22 `ImportWizardView.vue:1047`**：草案写 `:1047`（降级形标注），HEAD 实际为 **`:1057`**（草案用了批前 `97711b5` 编号）。
3. 其余 A21 漂移抽样（R-141①②③④、R-142）**均精确**（子智能体 `git show 3edaeb9` 逐条 diff 为空）。

---

## 四 重点提问题六条作答

### 1. D6/D8 合并为店级统一守卫的误拦风险——「应回放段」的时序判据怎么定义？

**答**：不能只给「同 toolCallId 存在 localTerminal 段」这一个判据，须拆两层：

**(a) 拦截点位置**：`hasDecidedPendingResidue`（`ai.ts:1210-1219`）已跨消息，**不能**在 `handleFrame` 单一入口用 `frame.toolCallId` 拦——`suspended` 帧的 toolCallId 在 `frame.toolCalls[]`（`types/sse.ts:219-222`），单字段拦截会漏（见 D8 竞态反例）。拦点应放在**各 case 内**，且 `suspended` case 遍历 `frame.toolCalls[].toolCallId`。

**(b) 时序判据（防「应回放却拦」）**：关键在「已决」白名单 `isFrameDecidedPendingStatus`（`:388-392`）把 **`failed`/`unknown`（本地悲观终态）与 `succeeded/rejected/blocked/expired/cancelled`（后端权威终态）混为一谈**。具体「应回放却拦」帧序：通用 FRONTEND 工具 `navigate_to` 执行器抛**瞬时错误**→本地终态 `failed`→用户 re-attach 期望重试→回放 `frontend_tool_request`→D8 判「已决=failed」→拦掉，**重试被锁死**。而 `succeeded`（后端已确认成功）才应拦（防二次副作用）。**建议判据**：后端终态（`succeeded/cancelled/expired/rejected/blocked`）恒拦；本地终态（`failed/unknown`）**是否拦须单列裁决**——拦=放弃重试（对齐「不重执行」验收点 3），不拦=允许重试但可能二次副作用。二者语义相反，不可一刀切。**另一个「应拦却放过」帧序**：D8 入口读 `frame.toolCallId`，而 suspended 帧无此字段 ⇒ 漏拦，见 D8 竞态反例。

### 2. D2 定序是否还有第三依赖？D1(B) 后挂起卡块留存是否引入草案未列依赖？

**答**：终态 tag 分支的可达性来源经评议者穷举 = **①`suspended` 旁路（通用 FRONTEND，无守卫）** + **②`restoreSuspendedSnapshot` 快照重建** + **③`frontend_tool_request` 无 first-wins**。其中 ② 已由 `hasDecidedPendingResidue`（`:740`）守卫、③ 由 D8 关旁路后消解；**唯一在 D8 关旁路后仍要单独确认的是 ② 是否会在「后端 runs/current 快照里出现非 PENDING 终态条目」时重建终态 tag**——亲读 `:728`/`:740` 确认非 PENDING 条目被守卫拦/收敛，**不构成活旁路**。故 D2 无第三活依赖；但 D1(B) 留存挂起卡块后，**A4（GF 错误渲染位 `AiPanel.vue:258-260`）的「唯一渲染位在挂起卡内、终态后恒假」仍成立**，这条**新依赖**草案未显式列：即 D1(B) 下 A4 不修，意味着「GF 取消回灌失败文案」继续不可见——须在 ③b 收工报告与台账 R-140①-1 行尾**显式承接为「批 4 随 A1 合并给独立槽位」**，不得静默。

### 3. D5 补建段与 D8 叠加的安全性——是否互相排斥？会不会留永久 pending 段？

**答**：**不排斥**，但**会留永久 pending 段**（红队最可能抓到的自相矛盾点，此处坐实为「风险归属缺失」而非逻辑矛盾）：

- 「已决⇒不建段」（D8）与「suspended 单独到达⇒建 pending 段」（D5）判据**正交**：D8 拦「已决 id」，D5 只建「不存在段的新 id」，同一入口不冲突。
- **但**：D5(A) 建的 pending 段，其终态收敛依赖「后端同批必发 confirm_request/frontend_tool_request」这一**未实跑验证的保证**（A5 引批 2 结论，【推断】）。若 suspended 单独到达后终态帧**被 D8 拦**（因该 id 在**另一条消息**已有终态段——跨消息查域叠加），或后端根本不发终态帧，则新消息留下**永久 pending 段**，且 `expireResidueToolParts` 只在 `done/error` 触发（`ai.ts:1661/1678`），**无专属过期路径**。
- **结论**：D5(A) 安全的前提 = **（甲）后端「suspended 必随终态帧」保证成立（须定稿卡显式标注为未复验前提）**，或 **（乙）D5 建段前也跨消息查 `hasDecidedPendingResidue`（对齐 D6(B)/D8(B) 判据）**，或 **（丙）给 suspended 单建的 pending 段配一条过期收敛路径**。三者至少取一，否则 D5(A) 引入新类别的「永久 pending」卡，比现状（suspended 单独到达→工具不可见）更糟。

### 4. D7 条件式 S0 证实无状态字段后，(B) 口径注记的措辞建议

**答**（S0 已由评议者完成：`types/ai.ts:134-150` 的 `AiHistoryMessage` 无 `status/ok/done` 字段）：建议措辞如下——

> 「`loadHistory` 历史整替分支（`stores/ai.ts:646-678`）将历史工具段状态**硬编码 `succeeded`**，非读取后端真实终态。当前 `AiHistory` 载荷（`types/ai.ts:134-150`）**不携带任何工具段状态字段**（无 `status`/`ok`），故前端**无法**在历史整替时还原 `unknown`/`failed`/`cancelled` 等本地终态；这是**数据缺失导致的显示折损，不是被接受的行为**。本地 `unknown`/`failed` 段的「历史整替即丢失」属**已知代价**（R-141②），须如实申报；根治需后端历史携带状态，已登记为新立项候选（出 ③b），在立项落地前，任何依赖「历史段终态」的下游判定**不得**以历史回填段的 `succeeded` 为准。」

要点：明确「是数据缺失、非可接受行为」「已知代价须申报」「根治出 ③b」，避免下批把「历史整替丢 unknown」误读为「可接受」。

### 5. D16 严守边界后，「同族谎报面仍在」的登记口径与颗粒度

**答**：登记**不足**，须补到如下颗粒度（否则下批误以为已闭合）：

- ③b 收工报告须**逐点列名**：①`ExportWizardView.vue:1183/1188`（只读/越界拒绝字符串折 ok）——A22 未动；②导入侧 **11 处**（子智能体逐条：`:1019/:1024/:1030-1032/:1040/:1055/:1063/:1066/:1072/:1075/:1081/:1084-1086`）；③导出侧其余 **10 处**（`:1183/:1188/:1211/:1227/:1231/:1238/:1250/:1258/:1261/:1267`）；④**E19 漏登记的 `ExportWizardView.vue:1340`**（download_export_file 执行器「本次未发起下载」折 ok，E19 的 21 处非全集）。
- 台账行尾注记（R-84 `:276`）建议追加一句：「③b 收工时经穷举，同族面 **22 处**（导出侧 11 + 导入侧 11，含 E19 漏记的 `:1340`），本批**未闭合**，随 R-84 批结构化」，把数字从「21 处」订正为「22 处」并点名漏记项。
- **不得**只写「同族谎报面仍在」而不再点名——颗粒度必须到「文件:行号 + 22 处计数 + 漏记项」。

### 6. 红线延续——「重挂已闭合」不得销记的残余面逐条

**答**：K3 立场表与草案全部选项在「不得销记『重挂已闭合』」上**均合规**（草案 §4.1 第 4 条、D8 红线、③a 施工报告 §8 口径延续）。D8(B) 落地后**仍不能销记**的残余面逐条：

1. **re-attach 新宿主仍新建「（正在重挂…）」空宿主消息**（`ai.ts:1023`），工具帧被 D8 拦后该消息为空并最终 `done:true`——「重挂」本身仍产生一条空消息，非「已闭合」。
2. **`loadHistory` 整替丢 `unknown`/`failed`**（R-141②/A8/D7）——D8(B) 不覆盖，历史整替仍把本地终态改写为 `succeeded`。
3. **`appliedSeqs` 消息级 seq 幂等跨宿主失效**（re-attach 新宿主全量回放不被 seq 去重，依赖 D8「已决」判据兜底而非 seq 幂等）——这是「重挂」路径的幂等面，D8 拦的是「已决写段」，未修「seq 记账跨宿主」。
4. **A20 的 `runUiEventAction handled:false⇒failed 但 availability:'available'`**（`frontend-tools.ts:921-925`）无降级 notice——属「谎报面」族，与「重挂已闭合」同族如实申报面，D16 未搭车即仍存在。
5. **GF 错误渲染位（A4）**与 **A22 只读/越界拒绝折 ok**——前者批 4 前不可见、后者 R-84 批前仍谎报，均「未闭合」。
6. **`runSeq` 跨会话残留**（`resetSession` 未清）——重挂/续收的水位边界，未闭合。

---

## 五 条件清单（总判定 = 带条件可施工）

| # | 条件 → 验收方式 → 未满足的后果 |
|---|---|
| C1 | **修复编号缺陷**：明确 M7/M8/M9（A18/A19/A20）去向——补 D17/D18 并进 K3 立场表，或（推荐）移出 ③b 登记不修；统一 A18 的「D16」指向；删除悬空 D19/D20（并入 D16）；§4.1 第 2 条红线例外 gate 从 D18 改为实际裁决点。→ 验收：定稿卡 §2 D 表与 A 表无悬空引用、§4.1 gate 指向存在的 D 条。→ 未满足：施工棒在 A19/A20/§4.1 找不到裁决，红线例外无授权，M7/M8/M9 悬空 |
| C2 | **D8(B) 拦截点按帧型定义**：单工具帧读 `frame.toolCallId`，`suspended` 遍历 `frame.toolCalls[].toolCallId`；建议守卫落在各 case 内。→ 验收：定稿卡写明拦截点位置与字段来源，施工后 `suspended` 旁路有测试锁。→ 未满足：suspended 旁路（A10 rogue）漏拦，验收点 2/3 不达标 |
| C3 | **D6/D8「已决」时序判据区分本地/后端终态**：`failed`/`unknown` 是否拦须单列裁决（拦=弃重试，不拦=允许二次副作用），不得与后端终态一刀切。→ 验收：定稿卡给判据矩阵 + 至少一条「重试 vs 二次副作用」测试锁。→ 未满足：通用 FRONTEND 工具重试被锁死，或二次副作用未防 |
| C4 | **D5(A) 永久 pending 归属**：明确 suspended 单建段的过期收敛路径（复用 `expireResidueToolParts` 或跨消息查 `hasDecidedPendingResidue` 或显式接受后端保证前提），并写测试锁。→ 验收：定稿卡给收敛机制 + 测试锁。→ 未满足：引入「永久 pending」卡，比现状更糟 |
| C5 | **统一 D1(B)/D2(A) 的 O1/A3 范围**：A3 在 ③b 删还是批 4 删，二选一钉死，草案 D1(B) 代价栏与 D2(A)「殿后」措辞对齐。→ 验收：两处口径一致。→ 未满足：施工棒不知 A3 归属，O1 死码或误删或漏删 |
| C6 | **三处行号/措辞订正 + 一处漏登记**：A21 `parts.ts:232-250`（终点）；A6「Pick」位 `types/ai.ts:341`；A22 `ImportWizardView.vue:1057`（非 1047）；E19 补登 `ExportWizardView.vue:1340`（同族面计数 21→22）。→ 验收：定稿卡行号修正、台账计数订正。→ 未满足：施工棒按错行号施工、同族面登记非全集 |
| C7 | **D9(C) 防漂移注记覆盖第三对枚举**：`isFrameDecidedPendingStatus` vs `localTerminal` 一并注记。→ 验收：注记覆盖三对。→ 未满足：第三对漂移面继续无人看守 |
| C8 | **D10(A) 轮次身份捕获点**：runFrontendTool 入口捕获 `currentAbort` 快照、await 后比对，写入定稿卡。→ 验收：实现要点入卡。→ 未满足：误用 `this.sessionId`/`activeRoundHostId` 当判据 |

---

## 六 异常申报

1. **纪律留证**：本评议**未跑任何 build/test/typecheck/e2e/CDP/dev server/后端/Redis/注入实验**（授权即禁止）。只读命令 = `sha256sum`、`wc -c/-l`、`git rev-parse HEAD`、`git status --porcelain`、`git branch --show-current`、`git show 3edaeb9:…`（子智能体）、`grep -n`、`Read`。**零 git 写、零文件修改（含不改被评议草案）、未读任何 `.env`**。
2. **实际模型自证**：评议者本体 = **DeepSeek V4 Pro**（依任务卡角色与父棒下发参数）；运行环境**无法自省底层模型 ID**，此局限如实声明。机械活 4 个子智能体均以 `model=DeepSeek/deepseek-flash` 显式钉版派发，各自子报末行自证「无法自省底层模型 ID」，**未发生互备切换**（GLM-5.3-Flash 不在本环境可用模型列表内，如实申报未使用，未以默认模型冒名顶替）。
3. **未实跑即降级的条目**：A3/A9/A10（触发条件依赖后端是否真重投）、A5/D5（后端「suspended 必随终态帧」保证）、A6（假态实际可复现）、A16（GREEN 为真锁）——均维持【推断】/【假设】，**未升级为【实测】**；相关判定（D5/D8 等）**降级为带条件**，验证步骤已写入 §五 C2/C3/C4 供施工棒执行。
4. **未亲读的仓外文件**：`batch3a-outcome-smoke.mjs`（A15，仅按草案锚点认可 D13(B) 保守选型）；后端源码（不在 ③b）；③a 各报告全文（按节摘读）。
5. **锚点口径基准**：全部行号 = `505f6d4` 工作树实读；③a 时期行号另标「③a 基线」的漂移由子智能体 `git show 3edaeb9` 对照核实（A21 抽样 5/6 精确、1 处终点差 1 行）。
