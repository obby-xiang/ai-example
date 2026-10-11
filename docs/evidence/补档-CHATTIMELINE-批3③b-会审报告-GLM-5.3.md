> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 三方会审评议报告（GLM-5.3，复核视角），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 会审报告 · 批 3' ③b 施工方案评议（GLM-5.3 · 复核视角）

| 项 | 值 |
|---|---|
| 执行者 | **GLM-5.3**（Kimi Code 主棒，复核视角评议者：行为基线对照、设计一致性、测试覆盖盲区） |
| 授权来源 | **K3 指挥官三方会审卡，2026-10-12，DC-19**（最终裁决权在 K3，本报告为参谋输入） |
| 评议对象 | `<SCRATCH>/chat-timeline-pkg\批3'③b-施工卡草案.md`，61,320 B / 518 行，sha256 = `0d5cd5430a5db5cc2ac6c6fc590ed3812f549e950900a74c38c6051579b2ae6f`【本棒起手实测复算，一致】 |
| 实读基线 | HEAD `505f6d4e0fa1360f04a3dbe3313842f1513a0952`，分支 `main`，`git status --porcelain` 空【本棒起手实测】；末次复核（跑完只读命令后）porcelain 仍空 |
| 纪律遵守留证 | 零 git 写（未执行任何 add/commit/stash/checkout/restore/clean）；未改仓库任何文件、未改被评议草案；**未读任何 `.env`**；未跑 `yarn build`、未起 dev server/后端/Redis、未跑 verify-e2e.ps1、未做 CDP/注入实验。**放宽项已用**：`cd frontend && yarn typecheck && yarn test`（子智能体复跑，前后各一次 porcelain 留证，均空） |
| 机械活下沉 | 两项机械活下沉子智能体，均**显式钉 `model=DeepSeek/deepseek-flash`**：①typecheck+test 复跑（报告 §六-2 留证）；②机械锚点核验（`git show 3edaeb9` 对照、全仓 `.streaming` 清点、`goStep(` 调用点、台账 :276/:289/:328-332 逐字引文、tests 清单、Import「失败」grep、`AiHistory`/`PendingToolCall` 定义）。**互备申报：GLM-5.3-Flash 不在本环境可用模型列表内，未发生互备切换**；机械活按设计全部由 DeepSeek V4 Flash 承担。关键锚点终读由本棒（GLM-5.3）本体完成 |
| 评议靶子 | 草案 §1 A1~A22 / §2 D1~D16 / §3 E1~E18 + K3 立场表 16 条 |

---

## 〇 总判定（先行）

**带条件可施工**（7 项条件，逐条可验收，见 §五）。

一句话理由：草案锚点可靠性极高——本棒抽验 A1~A12 + A16 共 13 条（含全部 5 条【假设】条目与全部 ★ 关联条目），**12 条完全属实、1 条属实但行号微差（A11）**，两条【假设】（A8 的 `AiHistory` 字段形状、A16 的「红在夹具」）已由本棒亲读**升级为实测**；K3 立场 16 条中 14 条同意（其中 6 条带修正）、2 条经本棒补证后落地（D7 落 (B)）。但本棒复核发现 **4 项草案与 K3 立场均未覆盖的实质问题**（§二），其中 D8(B)「帧型无关统一拦」若按字面施工会打破 ③a 已落地面（frontend_tool_result 的 activeForm 收敛副作用被闸吞），必须收窄拦截面后方可开工。

---

## 一 逐条判定表（D1~D16，16 条齐全）

> 判定口径：**同意** / **同意+修正**（结论维持，论据或范围订正）/ **反对**（须改判）。锚点均为本棒亲读（`505f6d4` 工作树）。

| # | K3 立场 | 本棒判定 | 理由 + 亲读锚点（+修正/替代） |
|---|---|---|---|
| D1 | (B) 只做状态机与死码，合并移交后续 | **同意** | 验收点 1 在状态机层即可达成：`AiPanel.vue:166-168` 的 parts `v-for` 以 `toolCallId` 为 key，段唯一 ⇒ 卡唯一，不依赖模板合并。`AiPanel.vue:199` 注释自认「批2 过渡形态……③b 把两者合并成一卡之前」——合并确为预告项，但 `AiPanel.vue` 与批 4 ④ 折叠规则同文件同渲染区（:166-196），③a 回归面刚平复即并批风险大。(B) 下 A3/A4 须**带理由登记**（验收点 4/5 的「登记」支），且 A1 的「两张卡」名义目标在 ③b 只做状态机半边——须在收工报告如实写「模板合并未做，挂起卡与工具卡仍并列渲染（AiPanel.vue:179-288）」 |
| D2 | (A) 先关旁路再删死码，D8 先行、O1 殿后 | **同意+修正**（补第三依赖清单） | 定序正确：终态 tag 分支当前经 A9 旁路可达（suspended 帧补 `requiresAction` ⇒ `pendingStatusOf('succeeded'/'cancelled'/…)` 直接映射终态，`parts.ts:106-127`），先删分支会丢渲染位。**修正（本棒新发现）**：死码可达性/删除范围还有三个依赖项草案未列——① `AiPanel.vue:254-256`（读 `ai.activeForm?.status`）是**活分支**（GF 用户提交后 `submitting/submitted` 窗口、回执帧到达前可达），**不属死码，删除范围须显式排除**；② `AiPanel.vue:213-232`（CONFIRM+pending 确认按钮/倒计时）是活分支，其 `pendingCall` 来源除活帧路外还有 `restoreSuspendedSnapshot` 重建路（`ai.ts:760-767` upsert pending+requiresAction，`ai.ts:748-752` 宿主优先镜像/历史里已持段的消息）——草案未列此第二来源；③ 删 tag 不得破坏 v-if/v-else-if 链（:264 链头挂在 :234 的 v-else-if 上，O1 只删 tag 不删块则链安全）。替代方案：无需改判，定稿卡死码清单逐行号列明并加排除项 |
| D3 | (A) 删 sanitize 守卫 + 入参契约注释 | **同意** | 死码四段链条全部亲证：`writeMirror` 的 `Pick` 字段表 `ai.ts:290-298`（id/role/content/parts/reasoning/toolRuns/pendingCall，**无 done**）；`loadHistory` 在调用 sanitize（:601）之前已 `streaming:false, done:true`（:593-597）⇒ `:225-227` 的 `message.done !== true ⇒ continue` 永不触发。反向罢工方向成立（直调镜像原始数据则 `undefined !== true` 全跳过）。删守卫 + 注释「调用方契约：入参须已归一 done」即可；唯一调用点 :601 亲证 |
| D4 | (A) loadHistory 镜像分支补 part.streaming=false | **同意** | 与 :595 的 `message.streaming:false` 同点收口、一行改动；镜像原样存 parts（:294）而读取侧只清消息级（:593-597），段级假态影响续段判断（`openStreamPart` 读 `last.streaming`，`parts.ts:46`）。关于「同处收口还是分处」（会审题目）：本棒判定**分处正确**——D4 是入参归一（落 loadHistory，与 :595 对齐），D3 是删守卫（落 sanitize），二者本就在同一调用链上下游；若收进 sanitize 会扩大其职责（现注释只讲 requiresAction，`ai.ts:211-221`），且 sanitize 的语义是「清残留」而非「归一入参」 |
| D5 | (A) 补建段，施工序在 D8 之后 | **同意+修正**（前提订正 + 既有锁翻红清单） | 前提「后端不产只发 suspended 路径」**被本棒半证伪**：`SpToolCallingManager.java:163` `out.suspended(pendings,…)` 先于逐工具帧发出，而循环内 `checkCancelled(runId)`（:172，注释「事件边界上的取消检查……命中即终止整轮」）在取消竞态下可让整轮在 toolStart 之前终止 ⇒「只发 suspended」**可达**。但该路径轮终态 `done{cancelled:true}` 必达 ⇒ 前端 `expireResidueToolParts`（`ai.ts:1661/:1678`，pending→expired）兜底。**修正**：D5(A) 的安全论证须从「不可达前提」改写为「轮终态 + 快照收敛双兜底」，且测试锁须含「suspended 建段后 done 帧 ⇒ expired」。**另**：D5(A) 会直接翻红两条既有锁——`parts-reducer.spec.ts:184`（「suspended 帧……未知 toolCallId 不建段」）与 `parts-residue-cleanup.spec.ts:465`（同款）——须同批改写并按 TDD 双向留证（草案 §5.2 未列预期变红清单，见 §二-3） |
| D6 | (B) 店级守卫，与 D8 合并为一处设计 | **同意+修正**（覆盖面申报：只闭合 A7 已决面） | 「已决 = 同 toolCallId 存在 localTerminal 终态段」判据现成（`hasDecidedPendingResidue`，`ai.ts:1210-1219`，跨全部消息 + `isFrameDecidedPendingStatus` :388-392 含 unknown/failed）。**修正（核心）**：该判据对 A7（re-attach 新宿主两张卡）**只闭合已决面**——旧段 unknown/failed/cancelled 等终态时，回放帧被闸拦、不建第二卡 ✓；但**未决面不闭合**：本端断流重挂（`reattachActive` `ai.ts:1023` 新建宿主 + 回放）时旧段若为 pending/running，闸不拦 ⇒ 回放帧在新宿主 `upsertToolPart` 新建段（`findToolPart` 只在单消息内查，`parts.ts:77-80`）⇒ 同 toolCallId 两张**未决**卡仍在。替代方案二选一：(i) 接受残余并在施工卡/台账如实申报「未决段 reattach 双卡面移交后续」（推荐——D6(A) 跨消息查域会改 `upsertToolPart` 隐式契约，风险更大）；(ii) 把判据扩为「同 toolCallId 存在**任何**工具段 ⇒ 回放帧路由原宿主」（=D6(A) 变体，代价是 store 侧 upsert 契约重构）。**反对把 D6(B) 表述为「A7 已闭合」**——验收点 1 的字面（同一 toolCallId 全局唯一一张卡）在未决双卡场景仍被打破 |
| D7 | 条件式（S0 先复取 AiHistory） | **同意——S0 已由本棒完成，落 (B)** | 亲读 `types/ai.ts:145-150`：`AiHistory = { sessionId, count, messages: AiHistoryMessage[] }`；`AiHistoryMessage`（:133-143）= `{ type, text, toolCalls?, toolResponses?, timestamp? }`——**无任何状态字段**（无 status/ok/outcome）⇒ (A) 不可行（无数据可判），落 (B)：维持硬编码（`ai.ts:659` `status:'succeeded'` + :658 `kind2:'UNKNOWN'`）+ 口径注记 + 「后端历史携带状态」登记新立项候选（出 ③b）。注记措辞建议见 §四-4 |
| D8 | (B) handleFrame 入口按 toolCallId 统一拦 | **同意+修正**（拦截面收窄——最重要修正） | 统一拦方向正确（一处覆盖 A9/A10，且「已决不再挂起」与批 2 确立的 first-wins 单调语义同向：`parts.ts:377-390` localTerminal、`applyFrontendToolTerminal` 排除 unknown/failed `ai.ts:1720-1723`）。**修正（红线级）**：「帧型无关」若按字面在 `handleFrame` 入口整帧丢弃，会顺带拦掉 `frontend_tool_result` 帧 case 内的**轮次级副作用**——activeForm 收敛（`ai.ts:1617-1625` disarmFormExpiry/表单他端提交提示/activeForm=null）与 `suspended=false` 复位——这会打破 ③a 已落地面（E17 回归锁）。**拦截面必须收窄为挂起语义帧**（`suspended` / `confirm_request` / `frontend_tool_request`）的「建段 / 写 requiresAction / 置 suspended / 武装 activeForm / 触发 runFrontendTool」；终态帧（frontend_tool_result/tool_result/confirm_decision）保留原 case（其段侧 first-wins 已在 parts.ts 实现，行为不变）。判据表述与误拦反例见 §四-1 |
| D9 | (C) 维持两表 + 防漂移注记 + 台账 R-141④ 订正 | **同意** | 分叉唯一真实点亲证：`frameStatusToPendingStatus`（`ai.ts:340-364`）无 `SUCCEEDED` case，`frontendResultStatus`（`parts.ts:232-249`）有——`SUCCEEDED` 只在后者 ✓。台账「后者缺 CANCELLED 依据注释」**确为误述**：注释本体在 `ai.ts:347-349`（`case 'CANCELLED'` :346 + 3 行注释 + return :350 的 5 行块），且 `git show 3edaeb9` 对照 :340-342 同期存在（子棒逐字引文证实，③a 未触该函数）。抽公共表会引入 `parts.ts` → store 的依赖方向问题（`parts.ts:12` 注释自述「本文件不依赖 store（纯函数层）」）——(C) 正确，配 §5.2-⑤ 表驱动一致性测试 |
| D10 | (A) 执行返回后校验轮次身份 | **同意** | `runFrontendTool`（`ai.ts:1800-1842`）的写段（:1829-1836）与回灌（:1841）均在 `await executeFrontendTool`（:1816）之后且**无身份守卫**（亲读）；`send` 的 finally（:929-943）只复位轮次标志与宿主 done，不清段、不中止在途执行器 ✓。`isCurrentRound` 同族范式现成（:926/:930/:1037）。补一个施工注意点：:1808-1815 的「开跑即落 running」段写在 stop() 前发生属正常（当时仍是当前轮），校验点只须设在 :1816 await 返回之后 |
| D11 | (A) goStep 单点弹 warning，syncStepToBackend 不自弹 | **同意** | 双弹四处亲证：Export `:624`（error）+ `:579`（warning）；Import `:773`（error）+ `:731`（warning）。`syncStepToBackend` 全仓仅两个调用方（两侧 goStep :576/:729，本棒 grep 穷尽确认，无模板/其他调用）⇒ 摘掉其自弹不会留静默路径。(A) 与「不阻断 + 警告」设计意图（:620-625/:770-771 注释自述）一致 |
| D12 | (A) 补 import.meta.env.DEV 门 | **同意** | `assertUnknownTerminalInvariant`（`parts.ts:134-139`）无条件 `console.error`，调用点 :200-201 注释自称 dev 断言但无门——口径不实亲证。(A) 成本一行；vitest 环境 `import.meta.env.DEV` 默认 true ⇒ 既有测试行为不变。③b 后 X1 不变式有「守卫（deriveProjections :196-205 跳过投影）+ 测试锁」双保护，DEV 门不削弱 |
| D13 | (B) 脚本头写明复跑前重启 dev server | **同意** | 裸路径探针亲证：`batch3a-outcome-smoke.mjs:144-150` `await import('/src/stores/workspace.ts')` + `registerPageHandler('batch3a.hang',…)`。本棒复核确认**应用内无测试探针注册入口**（registerPageHandler 是 workspace store 通用口，无 dev 专用入口）⇒ (A) 无落点，(B) 是唯一低成本选项 |
| D14 | (A) 补「夹具就位仍红」反向留证 | **同意**（且【假设】已由本棒亲读证实） | `RED-T7.log`（589 行）本棒亲读：T7③ 红在 `Received: "当前上下文（页面=export，步骤=SELECT_DEFS）不支持下载导出文件。"`（log:554）——走到了**上下文守卫分支**，persist 失败注入未到达被测断言；T7④ 红在 `Received: "…任务 #7 里没有 DEMO 的导出结果文件…"`（log:570）——**结果文件缺失分支**（exportResults 未回填）。两条均「红在夹具」✓。对照 T7①② 红在旧成功文案（log:519/:535-538 `已推进到…` 未含目标断言词），属「红在断言本体」的真锁——TDD 双向留证口径下 ③④ 须补反向留证，(A) 正确 |
| D15 | (A) 导入侧 goStep 对齐导出 | **同意** | 不对称亲证：Import `goStep` 返回 `Promise<boolean>`（:714），`:1028-1034` handler 回灌 `已推进到导入向导步骤「…」` **不含**同步失败信息；Export `:1202-1203` 已带 `syncWarning` 回灌。(A) 对齐即可；导入侧无既有测试锁回灌文本（grep 证实 tests 无 import.nextStep 用例），无翻红风险 |
| D16 | (A) 严守台账边界 + 串行序钉死 + CDP 冒烟强制 | **同意** | A22 锚点亲证（Export `:1182-1184` 只读 / `:1187-1189` 越界 return 失败形字符串，handler 起于 :1180）；台账 :276 行尾注记「本批未动……随 R-84 批一并结构化」逐字证实（子棒引文）⇒ 边界已由台账钉死，③b 不搭车正确，重演 ③a D3 范围控制之争无益。文件互斥表（草案 §4.2）与「③b → 批4④ → 轮询治理一期」串行序合理。CDP 强制正确：验收点 1/3 的「重投后仍一张卡 / 不重执行」的端到端面 vitest 锁不全（渲染层单卡计数可锁，浏览器副作用不可锁）——至少 1 张「重投后仍只有一张卡」截图。**附加**：定稿卡须清除 D17~D20 悬空引用（见 §三-A22 项） |

---

## 二 新增风险 / 遗漏（草案与 K3 立场均未列）

1. **D8(B)「帧型无关统一拦」会打破 ③a 已落地面（最高优先）**。`frontend_tool_result` 帧 case 内含轮次级副作用：activeForm 收敛（`ai.ts:1617-1625`，含「他端已提交」提示、disarmFormExpiry）、`suspended=false`（:1626）；`confirm_decision` 同理（:1584）。入口整帧丢弃 = 这些副作用被吞 = ③a E17 回归锁（T1 生死线等 20 例所在的 `frontend-tool-result-terminal.spec.ts`）面临行为改变。**修法**：闸只拦挂起语义帧（suspended/confirm_request/frontend_tool_request）的写段与触发面；终态帧保留原路径。已写入 D8 判定与条件 C1。
2. **D5 前提半证伪（后端取消竞态）**。`SpToolCallingManager.java:163`（suspended 先发）+ `:172`（checkCancelled 事件边界终止整轮）⇒「只发 suspended」可达；批 2 的「生产不可达」结论在该竞态下有例外。安全性靠 `expireResidueToolParts`（done/error 收尾，`ai.ts:1661/:1678`）与 `restoreSuspendedSnapshot` 非 PENDING 收敛（`ai.ts:723-731`）双兜底，不靠不可达。D5 论证与测试锁须随之改写（条件 C3）。
3. **D5(A) 翻红既有锁清单（草案 §5.2 缺「预期变红」节）**。`parts-reducer.spec.ts:184`「suspended 帧……未知 toolCallId 不建段」与 `parts-residue-cleanup.spec.ts:465`（同款写点③用例）锁定的正是 D5(A) 要推翻的现状语义。施工卡须列「预期变红既有用例」清单并按 TDD 双向留证改写。其余拟动点的翻红排查：D1(B) 下 `ai-panel-parts-render.spec.ts` 4 例（pending-card 断言 :92-110）**不红**（模板不动）；D3/D4/D12 在既有 110 例上无翻红面（sanitize 入参均 done:true、镜像测试构造 streaming:false、DEV 门在 vitest 为 true）；D15 无导入侧回灌文本锁。
4. **D6(B)+D8(B) 的未决双卡残余**（详见 D6 判定）：旧段 pending/running + 本端断流重挂 ⇒ 新宿主回放建第二段，闸（判终态）不拦。验收点 1 字面被打破，须申报或扩判据。
5. **店级守卫落点纪律**：`restoreSuspendedSnapshot`（`ai.ts:736-767`）是**非帧路**，自带 `hasDecidedPendingResidue` 双查（:728/:740）与闸语义一致——若施工时误把快照重建路也收进店级守卫，会破坏「PENDING 条目重建挂起卡」的正常路径。定稿卡须写明闸只落 `handleFrame`。同理 `applyFrontendToolTerminal`/`applyPendingTerminal`（非帧路）不在闸覆盖内——两函数 first-wins 语义与闸同向（亲读 :1713-1774），无行为改变，但覆盖面须如实申报。
6. **测试盲区补充**：(a) D8「不重执行」在 vitest **可锁**——mock `executeFrontendTool` 调用次数，`frontend-tool-result-terminal.spec.ts` 已有 reattachRun mock 范式（:30/:52）可循；(b) 「重投后仍一张卡」的渲染面须扩展 `ai-panel-parts-render.spec.ts`（现仅 4 例，:92-110 只锁单槽跟随）按 §5.2-① 三路（重投/新宿主/历史整替）各加 pending-card 计数断言；(c) 三路中「历史整替」路的单卡断言在 vitest 里语义弱（A8 整替后卡是重建的 succeeded 占位卡——「单卡」成立但「状态如实」不成立，断言须写清锁的是「单卡」而非「状态正确」，避免测试名与 D7(B) 口径打架）。
7. **D13(A) 无落点确认**：本棒复核应用内确无测试探针注册入口——支持 K3 取 (B)，同时建议 R-143② 台账行尾注记写明「(A) 已排查无应用内入口」以免下批重查。

---

## 三 A 表抽验结论

抽验范围：全部 5 条【假设】（A3/A6/A8/A10/A16）+ ★ 关联条目（A1/A2/A4/A5/A7/A9）+ A11 + A21/A22（顺带）。每条「草案断言 → 亲读事实 → 判定」。

| 条目 | 草案断言（摘要） | 本棒亲读事实（锚点） | 判定 |
|---|---|---|---|
| A1【实测】 | 工具卡块 :179-196 与挂起卡块 :198-287 并列；parts.ts:41 注释「段级状态参与后续状态机（子包③）」；AiPanel:199「批2 过渡形态……③b 把两者合并成一卡之前」 | 全部亲证：工具卡块 :179-196（toolTagType/toolStatusLabel 渲染 :187-189）、挂起卡块 :198-287（门控 `m.pendingCall && …toolCallId` :201）、:199 注释原文一致、`parts.ts:41` 注释原文一致 | **属实** |
| A2【实测】 | 守卫 :225-227 死码：镜像 Pick 无 done + loadHistory 先强制 done:true | 亲证：`ai.ts:290-298` Pick 字段表无 done；`:593-597` `{...message, streaming:false, done:true}` 先于 `:601` 调用；守卫 `:225-227`（`if (message.done !== true) continue`）；唯一调用点 :601 | **属实** |
| A3★【假设→静态双确认】 | 「恒 pending」不成立但方向成立；唯一活旁路 = suspended 帧；死码分支 :206/:250-253/:264-277/:280-286 | 亲证：`pendingStatusOf`（parts.ts:106-127）对 succeeded/cancelled/expired/rejected/blocked 直接映射终态 ⇒「恒 pending」确被否证；镜像路封死链（:593-601 sanitize 清 requiresAction → :604 derive ⇒ pendingCall=null）成立；suspended 支（:402-416）只补 requiresAction 不改 status ⇒ 终态 tag 经此旁路可达；死码分支行号逐一亲证。触发条件仍属未实跑（本棒亦静态推导，与草案同源双确认） | **属实**（触发条件未实跑的限定维持） |
| A4【实测】 | GF 取消回灌失败文案唯一渲染位在挂起卡内（:257-260），外层门控 :201 恒假 ⇒ 文案无处可显 | 亲证：`AiPanel.vue:258-260`（`ai.activeForm?.status === 'cancelled' && ai.activeForm.error`）位于 GF 分支（宿主 :234-262）内，外层 `v-if="m.pendingCall && …"`（:201）为整块门 | **属实** |
| A5【实测+推断】 | suspended 单帧 createIfMissing=false 不建段（:402-416/:98-100） | 亲证：`parts.ts:408-413` 第三参 false、`:98-100` 无 existing 且 !createIfMissing 直接 return；store 侧 `ai.ts:1629-1645` 同构。「生产不可达」前提被本棒半证伪（见 D5/§二-2）——**A5 的代码路径属实，「不可达」限定须订正为「取消竞态可达、有轮终态兜底」** | **部分属实**（路径属实；可达性限定须订正） |
| A6【假设】 | 镜像 part.streaming 假态影响续段判断；渲染层不读 part.streaming，全仓唯一 streaming 渲染读点 = AiPanel:529 的 message.streaming | 亲证 + 子棒全仓穷尽清点：`part.streaming` 读点仅 `parts.ts:46/:49/:71`（openStreamPart/sealTrailingStreamPart）；`.vue` 层唯一 streaming 读 = `AiPanel.vue:529`（isTyping，读 message.streaming）；无引号键访问（`['streaming']` 零命中）。写入侧 `:294` parts 原样入镜像、读取侧 :593-597 只清消息级、sanitize :228-237 只收 requiresAction——链条完整 | **属实**（「渲染层不读」穷尽成立；「实际可复现」仍属未实跑【假设】，本棒维持该分级） |
| A7★【实测】 | reattach 新建宿主 ⇒ 回放帧落新消息 ⇒ findToolPart 找不到旧段 ⇒ 新建段 ⇒ 两张矛盾卡 | 亲证：`ai.ts:1022`（lastSeq 读点）/`:1023`（appendAssistantMessage）/`:1035`（activeRoundHostId）/`:1046`（onFrame → handleFrame(frame, assistant.id)）；`parts.ts:77-80`（findToolPart 单消息域）/`:90-95`（命中不到即新建）。③a 基线 :1008 → 现 :1023（+15）✓。**补充**：D6(B)+D8(B) 只闭合其中已决面（见 D6 判定） | **属实** |
| A8【假设→已证】 | 整替硬编码 succeeded（:646-678/:659）；(A) 可行性依赖 AiHistory 字段形状 | 亲证：`:646-678` 整替、`:658` kind2:'UNKNOWN'、`:659` status:'succeeded'、`:662` requiresAction:false、`:676` deriveProjections。**AiHistory 已亲读**（`types/ai.ts:145-150` + AiHistoryMessage :133-143）：**无状态字段** ⇒ 【假设】落地为「(A) 不可行」 | **属实**；D7(A) 前提**已被证伪**（无数据可用） |
| A9★【实测+推断】 | 通用 FRONTEND 重投：守卫带 GF 前缀不拦（:1588/:1635）、段写无 first-wins（parts.ts:359-372 + :93-95 spread 合并）、:1605 直跑重执行 | 全链亲证：`:1587-1606` case 体、`:1588` `frame.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(...)`、`:1605` `void this.runFrontendTool(message, frame)`、`:1635` suspended 路同前缀；`parts.ts:359-372` 无条件 status:'pending'+requiresAction:true、`:93-95` `{...existing, ...patch}` | **属实** |
| A10★【推断/假设】 | rogue failed+requiresAction ⇒ pendingStatusOf default 折 'pending' ⇒ 挂起卡复活；X1 只拦 unknown；折法是 8 态联合类型所迫 | 亲证：`parts.ts:196-205`（X1 只拦 `status === 'unknown'`）、`:120-126`（default 折 'pending'，注释自认「仅为类型穷尽兜底」）；`types/ai.ts:324` `PendingToolCall['status']` 8 态联合（pending/running/approved/rejected/expired/cancelled/succeeded/blocked）**无 unknown/failed**（草案引 :297-… 为 interface 起始行，联合在 :324，区间口径成立） | **属实** |
| A11【实测】 | 台账 R-141④「后者缺 CANCELLED 依据注释」不成立；唯一真实分叉 = SUCCEEDED 只在 frontendResultStatus | 亲证 + 3edaeb9 对照（子棒 `git show` 逐字引文）：注释本体 `ai.ts:347-349`（草案引「:346-350」为 case 标签+注释+return 的 5 行块——**块口径成立，注释本体严格在 :347-349，微差不影响结论**）；3edaeb9 同期 :340-342 存在 ⇒ 误述自 ③a 落行起成立；`frameStatusToPendingStatus`（:340-364）确无 SUCCEEDED case | **属实**（行号微修正：注释本体 :347-349） |
| A16【假设→已证】 | T7③④ RED 红在夹具未就位（scope 未拨 / getFiles 未回结果）而非断言本体 | **本棒亲读 RED-T7.log 全文（589 行）**：T7③ `Received: "当前上下文（页面=export，步骤=SELECT_DEFS）不支持下载导出文件。"`（log:554）= 上下文守卫分支，persist 注入未到达；T7④ `Received: "…任务 #7 里没有 DEMO 的导出结果文件…"`（log:570）= 结果文件缺失分支；T7①② 红在旧成功文案（log:519/:538）= 真锁 | **属实**，且可升级为【实测（本评议亲读）】 |
| A21（顺带） | ③a 行号全线漂移，R-97 五处 = :14/:635/:1169/:1192/:1284 | 子棒 grep 证实五处全部命中；R-141 系 +15/+22/+7、R-142 +2/+6、R-143①③ 同值——本棒亲读抽核一致 | **属实** |
| A22（顺带） | Export :1182-1184/:1187-1189 只读/越界拒绝字符串；台账划给 R-84 批 | 亲证锚点与台账 :276 行尾注记（子棒逐字引文） | **属实** |

**D17~D20 悬空引用判定**（会审卡指定项）：A19 写「待裁，D17」、A20 写「待裁，D18」、A22 写「D19」「D20」，而 §2 只定义 D1~D16。判定 = **无害编号遗留 + 会误导施工的缺陷（低危）**：施工棒按 A19/A20 找裁决点会落空，可能把 M8/M9 当「已裁不动」漏施工（M7/M8/M9 的实际裁决承载在 §5.1 表「M6/M7/M8/M9 由 A17/A18/A19/A20 + D15~D18 承接」一行——注意该行自己又写了「D15~D18」这个不存在的区间表述，双重悬空）。**订正建议**：不重编号 D1~D16（保三方引用稳定），定稿卡把 A19/A20/A22 的「D17/D18/D19/D20」字样改为「并入 D16 边界裁决 / §5.1 承接表」，§5.1 该行「D15~D18」改为「D15 + D16 边界裁决」。

---

## 四 重点提问题六条作答

### 1. D6/D8 合并店级守卫的误拦风险与时序判据

**可施工判据表述**：在 `handleFrame` 处理**挂起语义帧**（`suspended` / `confirm_request` / `frontend_tool_request`）且该帧将「建段 / 写 requiresAction=true / 置 suspended / 武装 activeForm / 触发 runFrontendTool」之前，按 `frame.toolCallId` 调用 `hasDecidedPendingResidue`（现实现 `ai.ts:1210-1219`：跨全部消息、判 `isFrameDecidedPendingStatus(part.status)`，终态集含 unknown/failed）；命中 ⇒ 跳过上述写与触发，**仅保留** seq 记账（:1509-1516）与 `markApplied`（:1697-1699）。终态帧（frontend_tool_result / tool_result / confirm_decision）**不入闸**，保留原 case（段侧 first-wins 已在 `parts.ts:382-390`）。

**为什么按此判据不会错杀「应回放段」**：判据读的是**当前段状态**，不是帧历史。「应回放」的充要条件 = 该 toolCallId 当前**无终态段**（pending/running/无段）；此时闸不拦，任何帧（含重放）正常写入——这正是回放的正当情形。正确的回放帧不需要「跟在什么之后」的时序上下文，段状态本身就是充分判据。已终态 ⇒ 拦，而这恰是批 2/③a 已确立的 first-wins 单调语义（`parts.ts:377-390`、`ai.ts:1720-1723` 排除 unknown/failed）从一种帧型推广到挂起语义帧全集，不是新语义。

**反例逐一检验**：(a) 断线续收/重挂 + 段已终态（unknown 等）→ 拦 ✓（这是 A7 要修的「已决重投」面）；(b) 段 pending/running（挂起中断重挂的设计场景）→ 不拦 → 回放帧写入**新宿主**建第二段 → **这是拦不掉的未决双卡残余**（D6 修正项），须申报；(c) 同一 toolCallId 跨新旧轮交叠、localTerminal 段属上一轮 → 理论上误拦本轮正当帧，但 toolCallId 为服务端 UUID（E3 同族论证），碰撞概率可忽略，且即便发生，行为与既有 first-wins 语义一致，不产生新谎报；(d) **本端执行器先落终态、后端 frontend_tool_result 回执帧后到**（正常流，`ai.ts:1825-1836` 本地先落 succeeded）→ 该帧不入闸（终态帧保留）→ activeForm 收敛（:1617-1625）正常执行、段侧 first-wins 保持本地终态 ✓——这正是拦截面必须收窄的直接证明：若按「帧型无关」整帧丢弃，此场景的表单收敛被吞。

### 2. D2 定序的第三依赖

除 A9/A10（suspended/frontend_tool_request 旁路）外，终态 tag 分支可达性与删除安全性还依赖：① **`ai.activeForm` 会话态**——`AiPanel.vue:254-256`（activeForm cancelled/submitted tag）与 `:258`（error 文案）是活分支（GF 提交后 submitting 窗口、回执帧前可达；`GenerativeFormStatus` 含 submitting/submitted，`types/ai.ts:354`），**死码清单必须排除**；② **`restoreSuspendedSnapshot` 重建路**（`ai.ts:736-767`）是 `pendingCall` 的第二来源（草案未列）——但它只产 `status:'pending'` 投影，不产生终态 tag 可达性，不影响 A3 结论，只影响「pendingCall 来源清单」的完备性；③ 镜像迁移路（`migrateMirror` :167-209）与历史整替路（:646-678）恒产 requiresAction=false / pendingCall=null（OBS-3 链），不构成依赖。D1(B) 下挂起卡块整体留存的额外注意：删 tag 不得破坏 v-if/v-else-if 链结构（:264 链头挂 :234，:280 的 v-else 挂 :264）。结论：无新的终态 tag 可达路径，D2(A) 定序成立，但死码精确范围须含上述排除项（条件 C6）。

### 3. D5 补建段与 D8 叠加的安全性

**不互斥**：D8 闸的判据域 = 「段已终态」；D5(A) 建段的判据域 = 「段不存在」（createIfMissing 语义）。两域不相交，同一入口按「先查闸（终态？拦）→ 再建段（不存在？建）」顺序执行逻辑自洽。**永久 pending 段不会无兜底**：① 轮终态 done/error 帧 → `expireResidueToolParts`（`ai.ts:1661/:1678`）pending→expired；② 挂起中断后刷新 → `restoreSuspendedSnapshot` 非 PENDING 条目就地收敛（:723-731）+ 后端权威超时（TIMEOUT 帧）；③ 「suspended 建段后终态帧被闸拦」不会发生——终态帧不入闸（D8 修正后）。真丢终态帧且轮不收尾且不刷新 = 会话断流挂起，属既有「断流结果可能不完整」面（onClose 提示 :903/:833），非 D5 新引入。**前提订正**：安全性论证须改为「兜底论证」——后端取消竞态下「只发 suspended」可达（`SpToolCallingManager.java:163` suspended 先发、`:172` checkCancelled 终止整轮），随后 done{cancelled:true} 必达 ⇒ 兜底生效；测试锁须含「suspended 建段 → done 帧 ⇒ 段 expired」。

### 4. D7(B) 口径注辞措辞建议（S0 已证无状态字段）

建议措辞（可直接入定稿卡，锚点已核）：

> `loadHistory` 历史整替（`ai.ts:646-678`）的工具段 `status:'succeeded'`（:659）与 `kind2:'UNKNOWN'`（:658）是**展示占位值，不是事实断言**：后端历史载荷（`AiHistory` / `AiHistoryMessage`，`types/ai.ts:133-150`）只有 type / text / toolCalls / toolResponses / timestamp 五类字段，**不携带任何工具执行状态**，前端无从判定成败。因此本地已落的 `unknown` / `failed` 终态在整替后**必然丢失并显示为「已成功」——这是已申报的已知失真（R-141②），不是可接受行为**；修复属后端面（历史携带状态），已登记立项候选，③b 不含后端。在后者落地前，「UNKNOWN + succeeded」组合 = 载荷缺状态的占位口径，任何消费方不得以该 `succeeded` 值做业务判定，任何后续批次不得把本注记解读为「历史整替丢 unknown 可以接受」。

配套：登记「后端历史携带状态」新立项候选（出 ③b、入台账），并在 R-141② 行尾注记指向该候选。

### 5. D16 严守边界后的登记口径完备性

**现状不完备**，缺三样：① **导入 11 处无行号**——「11 处失败形字符串」沿用 ③a E19 清单，本轮子棒 grep「失败」在 ImportWizardView 得 26 处命中（ElMessage 14 行 / return 相关 2 行 / 注释 9 行 / 对象字面量 1 行），与「11 处失败形字符串」口径**不同源**，且 ③a E19 清单本身未见逐处 505f6d4 行号——**登记前须先落行号**，否则下批无法定位；② R-84 行尾注记须含**机制 + 判定词 + 后果**：锚点（Export :1182-1184 / :1187-1189 + Import 逐处行号）、机制（「字符串⇒ok」兼容支折 ok，③a D3(iii) 口径）、判定词「**未闭合**」、用户可见后果（只读/越界拒绝时卡面显示「已成功」）、去向（R-84 批结构化）；③ ③b 收工报告须单列「同族谎报残余面」节，逐条列 A22 两处 + Import 11 处 + W-05（后端 8 处 catch，R-137）+ N-6（ConfirmGate 硬编码 ok=true，R-137），写明「本批未动、不在本批范围」。**颗粒度判据**：下批施工者不看 ③a/③b 报告全文、只读台账行尾注记 + 锚点行号即可开工。

### 6. 红线延续：不销记「重挂已闭合」

**合规性判定**：K3 立场表 16 条与草案全部选项在这一点上**全部合规**——D8 红线原文（草案 §2 D8「任何方案都不得销记」+ §4.1-4「如实申报」）与 K3 D8 理由列（「不得销记……残余面如实申报」）均明示；其余 15 条无任何一条隐含销记语义。

**D8(B) 落地后仍不能销记「重挂已闭合」的残余面（逐条）**：
1. **未决双卡面**：旧段 pending/running + 本端断流 reattach 新宿主回放 ⇒ 同 toolCallId 两张未决卡（闸判终态，不拦；`parts.ts:77-80` 单消息查域 + `:90-95` 新建）；
2. **loadHistory 整替丢真**：本地 unknown/failed 被硬编码 succeeded 占位覆盖（A8/D7(B) 维持，修复属后端立项候选）；
3. **非帧路不在闸内**：409 响应体 / 续收快照走 `applyFrontendToolTerminal` / `applyPendingTerminal`（`ai.ts:1713-1774`），与闸语义同向（first-wins + 排除 unknown/failed）但覆盖面独立，须并列申报；
4. **stop() 在途执行器**（R-142）：D10(A) 收窄「返回后写段+回灌」主面，但 stop 与执行器返回之间的窗口内 running 段已落（:1808-1815），竞态窗口残留；
5. **D5(A) 建段后的生命周期**依赖轮终态兜底（取消竞态的 suspended-only 窗口，`SpToolCallingManager.java:163/:172`）；
6. **后端契约前提**：闸以「后端不重发已终态 toolCallId」为前提（UUID 唯一 + 回放仅 reattach/续收场景）；未来后端若引入合法重试语义，闸会拦正当帧——须作为契约前提申报。

---

## 五 条件清单（总判定 = 带条件可施工）

| # | 条件 | 验收方式 | 未满足的后果 |
|---|---|---|---|
| C1 | D8 闸拦截面收窄为**挂起语义帧**（suspended/confirm_request/frontend_tool_request）的写段与触发面；终态帧（frontend_tool_result/tool_result/confirm_decision）保留原 case 副作用（activeForm 收敛、suspended 复位） | vitest：`frontend-tool-result-terminal.spec.ts` 20 例全绿（T1 生死线不破）+ 新增「重投 frontend_tool_request 后 executeFrontendTool 不被二次调用」用例 | ③a E17 回归锁被打破（表单不收敛/挂起态残留），返工 |
| C2 | D6(B)+D8(B) 的**未决双卡残余**写入施工卡残余面申报（不得表述为 A7 全闭合），并落台账 R-141① 行尾注记 | 定稿卡含「残余面」节 + 提交官台账行尾注记 | 验收点 1 字面被违反且无申报，构成静默留白 |
| C3 | D5 论证改写为**兜底论证**（取消竞态可达 + expireResidue/快照收敛双兜底），测试锁含「suspended 建段 → done ⇒ expired」；同批改写既有锁 `parts-reducer.spec.ts:184` / `parts-residue-cleanup.spec.ts:465` 并双向留证 | 施工报告含两条旧锁改写前后留证 + 新增兜底用例 | D5(A) 落地即翻红既有用例；安全论证建立在被证伪的「不可达」前提上 |
| C4 | D7 落 **(B)** 并采用 §四-4 措辞（占位值非事实断言 + 已知失真申报 + 「后端历史携带状态」新立项候选登记） | 定稿卡文本 + 台账新立项候选行 | 下批误读为「历史整替丢 unknown 可接受」 |
| C5 | 定稿卡清除 **D17~D20 悬空引用**（A19/A20/A22 改指 D16/§5.1；§5.1「D15~D18」改「D15 + D16 边界裁决」） | 定稿卡全文检索无裸 D17~D20 字样（或显式注记指向） | 施工棒按 A19/A20 找裁决点落空，M8/M9 可能漏施工 |
| C6 | O1 死码精确范围**排除 :254-256 与 :213-232**（活分支），逐行号列清单；D2 定序（D8 先行、O1 殿后）同批落卡 | 定稿卡死码清单含行号 + 排除项 | 丢渲染位（A4 同族：提交窗口 tag、确认按钮被误删） |
| C7 | CDP 冒烟强制：沿用 ③a 配方（`VITE_DEV_PORT=5202` + `VITE_API_BASE` 指向后端），至少 1 张「重投后仍只有一张卡」截图；复跑前重启 dev server（D13(B) 口径写入脚本头） | evidence 目录含截图 + 冒烟脚本头部口径行 | 验收点 1/3 无端到端证据（浏览器副作用面 vitest 锁不全） |

---

## 六 异常申报

1. **模型自证**：本报告评议主体 = **GLM-5.3**（Kimi Code 主棒，Zhipu AI glm-5.3，即当前会话模型）。本棒运行环境无法自省底层模型 ID，身份依会话配置声明。**互备申报：GLM-5.3-Flash 不在本环境可用模型列表内，未发生互备切换**；不以默认模型冒名顶替。
2. **机械活下沉与子棒自证**：两项机械活均显式钉 `model=DeepSeek/deepseek-flash`（DeepSeek V4 Flash）派发。两份子报末行均自证「执行者 = DeepSeek V4 Flash（依父棒显式下发 model 参数）」并如实声明「运行环境无法自省底层模型 ID」的局限。机械活范围：① `yarn typecheck` + `yarn test` 复跑；② `git show 3edaeb9` 两处对照、全仓 `.streaming` 清点、`goStep(`/`syncStepToBackend(` 调用点、台账 :276/:289/:328-332 逐字引文、tests 清单、Import「失败」grep、`AiHistory`/`PendingToolCall` 定义引文。**关键锚点终读与全部判断由本棒本体完成**；子棒仅提供原始引文。
3. **只读命令纪律留证**：typecheck（退出码 0，0 错）与 test（110/110 通过 · 11 spec 文件）由子棒在仓库内复跑，跑前跑后各一次 `git status --porcelain` 均**空**，无未跟踪产物；实际执行命令为 `vue-tsc --noEmit` 与 `vitest run`（`package.json` 的 test 脚本即 `vitest run`，未进 watch）。既有噪声如实登记：`export-wizard-step-outcome.spec.ts` 挂载时未注册 Element Plus 组件的 `[Vue warn]` 告警（既存，非本评议引入）。基线口径与草案 §5.2（typecheck 0 错 / 110 例 · 11 spec）**完全一致**。
4. **未验证事项与局限**：
   - A6「实际可复现」、A3 旁路「触发条件」：本棒与草案同为静态路径推导，未实跑注入——分级维持【假设】/静态双确认，不升级；
   - 后端源码仅读 `SpToolCallingManager.java:130-200` 一段（D5 前提订正依据：:163 suspended 先发、:172 checkCancelled），`awaitFrontend`/`awaitConfirm` 内部发帧细节未逐行复核（③b 不含后端，该深度已满足 D5 判定需要）；
   - 台账 R-143② ③a E19 的「导入 11 处」清单本体未逐处核对（口径与 grep「失败」不同源，已作为 §四-5 登记前置条件提出）；
   - `frontend/tests/` 各 spec 内部断言仅抽读（pending-card 断言、suspended 用例、reattach mock 范式、T3 守卫用例），非逐例通读；
   - E 表锚点抽验范围：E2/E3/E4/E7/E8 已顺带亲证，E13（workspace.ts:392-401）等其余未逐条核——E 表为排除项清单，不影响施工判定。
5. **纪律总申明**：本评议全程零 git 写、零文件修改（仓库与草案均未动）、未读任何 `.env`、未跑 build / dev server / 后端 / Redis / verify-e2e.ps1 / CDP；唯一写入 = 本报告文件。会审对象哈希复算一致、仓库基线（HEAD/分支/porcelain）实测一致后开工。
