> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 施工卡（定稿，完整替代草案），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 「对话时序包 · 批 3' ③b」施工卡（**定稿**）

> ## ⚠️ 本稿替代草案，草案仅存档
>
> 本文件 = **批 3' ③b 施工卡定稿**，**完整替代** `<SCRATCH>/chat-timeline-pkg\批3'③b-施工卡草案.md`（518 行 / 61,320 B / sha256 `0d5cd5430a5db5cc2ac6c6fc590ed3812f549e950900a74c38c6051579b2ae6f`）。
> 草案**仅存档**，不得作为施工依据；本稿之外的任何行号/清单/口径**一律无效**（尤其草案以下四处已订正：①A21 `frontendResultStatus` 终点 `parts.ts:232-250`；②A6「`Pick` 不含 done」的 `Pick` 实位 `types/ai.ts:341`；③A22 导入侧降级形 `ImportWizardView.vue:1057`；④同族面计数 **21→22**（补 `ExportWizardView.vue:1340`））。
> 定稿依据 = 《决策卡-批3③b.md》（K3 终裁 D1~D16 + X1~X15 + 定序 + 测试锁 + 台账清单 + 行号订正）。

| 项 | 值 |
|---|---|
| 文档定位 | **施工卡（定稿）**——施工棒的唯一作业依据 |
| 执行者 | **Kimi K3 指挥官**（**K3 代签**，署名口径 **DC-19**） |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（Kimi Code 子智能体，**模型显式钉版**；本棒只落稿：零施工、零 git 写、未改仓库文件、未读 `.env`） |
| 施工执行 | **待派施工棒**（机械活下沉条款见 §8.3：下沉子智能体**须显式钉 `model=DeepSeek/deepseek-flash`**，**禁靠会话默认**，报告自证实际模型） |
| 授权来源 | **K3 指挥官 2026-10-12 三方会审汇总定稿指令（DC-19）**（会审输入：草案 518 行 + GLM-5.3 评议 + DS V4 Pro 红队评议） |
| 仓库 | **`<MAIN_REPO>`**（⚠️**严禁** `<OTHER_REPO>（另一条线的仓，严禁触碰）`——无共同祖先的另一条线，任何误用即整批作废） |
| 实读基线 | **HEAD `505f6d4`**（`505f6d4e0fa1360f04a3dbe3313842f1513a0952`），分支 `main`，`git status --porcelain` **空**【落稿棒实测】 |
| 工具链（钉死） | **JDK 21**（实测 `21.0.12`）；Maven 走包装脚本 `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`（**③b 不含后端 ⇒ 不要求 `mvn`**）；**node v24.21.0** / **yarn 1.22**（实测 `1.22.22`） |
| 前端 dev 端口 | **5202**（仓内 `frontend/vite.config.ts` 默认 `5200` + `strictPort: true` ⇒ 起 dev **必须**显式 `VITE_DEV_PORT=5202`，并配 `VITE_API_BASE` 指向后端） |
| 纪律（红线） | **零 git 写**（提交官统一提交）；**禁读任何 `.env`**；未获授权的仓库外文件一律不改；**③b 不含后端**；**不触 `utils/frontend-tools.ts`（红线无例外）** |
| 测试口径 | **TDD 双向留证**（照批 2 / ③a）：每个锁须「**施工前红 / 施工后绿**」**双向留证**，写入施工报告；**预期翻红**的既有用例须**同批改写**并留证（§6(d)） |
| 报告落盘 | **`<SCRATCH>/chat-timeline-pkg\施工报告-批3③b.md`**（必含 §8.1 九项） |
| 落盘 | 本卡 `<SCRATCH>/chat-timeline-pkg\批3③b-施工卡.md` |

---

## §0 ③b 是什么（一句话口径 + 可观察验收）

> **③b = 「一卡全生命周期」的段状态机收口 + 死亡代码清除**：把 ③a 在**工具结局维度**上打通的 `unknown` 终态，向下推进到**段状态机**层——（a）让「一张工具卡走完自己的一生」不再被**帧重投**（通用 FRONTEND 工具不经守卫）/ **re-attach 新宿主** / **loadHistory 整替** 三条旁路改写成第二张卡或另一种终态；（b）把批 2 权威翻转后**已失去可达路径的守卫与模板分支**（`sanitizeMirrorResidues` 的「进行中轮」守卫、挂起卡终态 tag 死码、镜像 `streaming` 假态、`suspended` 单帧）做出**处置或如实登记**；（c）承接 ③a 移交的 R-141 四项 + R-142 + R-143 择项 + ③a §12 M6。**不触后端**，**不碰 `utils/frontend-tools.ts`**，**不重做** ③a 的三态契约，**模板合并移交批 4④**。

**本批形态（D1(B)）**：③b **不触 `AiPanel.vue` 渲染区**（唯一例外 = A3 死码 tag 删除，按 D2 **殿后**）。

**验收口径（可观察，逐条对应单测/CDP 证据）**

| # | 验收点 | 对应 |
|---|---|---|
| 1 | **同一 `toolCallId` 全局唯一一张卡**（已决面）：已终态 `toolCallId` 经**重投帧 / re-attach 回放 / 历史整替 / 镜像恢复**任一路径**不再产生第二张状态矛盾卡** | A7 / A8 / A9 / D6 / D7 / D8 |
| 2 | **段状态单调**：已终态段（含 `unknown`/`failed`）**不被**「重投帧 / `suspended` 帧 / 快照收敛」改写成 `pending` 或另一种终态 | A9 / A10 / D8 |
| 3 | **通用 FRONTEND 工具重投不再重执行**：重投 `frontend_tool_request` 对**已决** `toolCallId` **不再触发第二次浏览器副作用** | A9 / D8 |
| 4 | **挂起卡终态 tag 分支的处置明确**：在**旁路关闭之后**删除（A3），且**不丢渲染位**；活分支（`:213-232` / `:254-256`）**排除在删除范围外** | A3 / D1 / D2 / §5 |
| 5 | **死码有判决**：`sanitizeMirrorResidues` 守卫、镜像 `part.streaming` 假态、`suspended` 单帧、`assertUnknownTerminalInvariant` 的 DEV 门——每条**或改或删或带理由登记**，不得静默留存 | A2 / A5 / A6 / A14 / D3 / D4 / D5 / D12 |
| 6 | **台账可销记**：R-139①/③、R-140①-1/①-2/①-4、R-141①~④、R-142、R-143①~④、③a §12 M6 在 ③b 卡**显式承接**并**逐项点名**，不打包（C8） | §8.5 |
| 7 | **未闭合面如实申报**：**不得表述「A7 已闭合」**；D8 落地后**不得销记「重挂已闭合」**；残余面按 §8.4 **六条逐条申报** | X5 / X15 / D6 / D8 |

---

## §1 A 条目清单（**A1~A22**；行号 = `505f6d4` 落稿棒实读复核值）

> **归属列**= 本批施工取值（D16 边界裁决后）。**施工范围** = A2 / A3 / A5 / A6 / A7 / A8 / A9 / A10 / A11 / A12 / A13 / A14 / A15 / A16 / A17 / A21（+ A22 仅登记）。**明确不施工** = A1 / A4（批 4④）/ A18 / A19 / A20（移出登记不修）。
> 证据分级：【实测】/【推断】/【假设】沿草案口径**不擅自升级**（两方会审已亲证者按会审原文标注）。

### 组 1 · 主题本体与死码清除

#### A1 主题本体：挂起卡与工具卡仍是**两张卡**（A1 本体）

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/components/AiPanel/AiPanel.vue:179-196`（工具卡块）/ `:198-287`（挂起卡块，门控 `:201` `m.pendingCall && …toolCallId`）；源码注释自述「批2 过渡形态……③b 把两者合并成一卡之前」（`:199`）；`utils/parts.ts:41` 注释「段级状态参与后续状态机（**子包③**）」 |
| **归属（终裁）** | **✅ 移交批 4④（模板合并）**。D1(B)：③b 只做状态机与死码 ⇒ **不触渲染区**；**收工报告必须如实写**「模板合并未做，挂起卡与工具卡仍并列渲染（`AiPanel.vue:179-288`）」 |
| 自证锚点 | `parts.ts:39-42` 的「子包③」注释 = 本批即子包③的代码内预告（报告可引用） |

#### A2 `sanitizeMirrorResidues` 的「进行中轮不触碰」守卫 = **防御性死码**（③a E1 / R-139①）

| 项 | 内容 |
|---|---|
| 锚点 | `stores/ai.ts:222-240`（函数体）；**守卫 `:225-227`**（`if (message.done !== true) { continue }`）；唯一调用点 `:601` |
| 死码现场【实测】 | ①`writeMirror`（`:279-304`）的 7 字段（`id/role/content/parts/reasoning/toolRuns/pendingCall`，`:290-298`）**无 `done`**；②`loadHistory` 在调用 sanitize（`:601`）**之前**已强制 `{...message, streaming:false, done:true}`（`:593-597`）⇒ `continue` **永不触发** |
| 反向风险 | 若后续**直调镜像原始数据**（`undefined !== true`）⇒ **全段跳过**（守卫方向反了：应收口却整段罢工） |
| **施工取值（D3(A)）** | **删守卫** + 注释写明「**入参契约：调用方须已归一 `done`**」 |

#### A3 挂起卡终态 tag 分支的**可达性**（③a E2 / R-139③）

| 项 | 内容 |
|---|---|
| 锚点 | 挂起卡块 `AiPanel.vue:201-287`；**死码分支**：`:206`（非 CONFIRM ⇒ 「自动」`v-else`）、`:250-253`（GF 已取消/已提交/会话已失效/未提交）、`:264-277`（通用 FRONTEND 的 8 个 tag，含 `:277` `v-else` 兜底「已执行」）、`:280-286`（CONFIRM 终态列，含 `:285` `v-else` 兜底「执行失败」） |
| 活旁路【实测链】 | `suspended` 帧（`parts.ts:402-416`：`createIfMissing=false`、**不改 status**）对**已存在的终态段**补写 `requiresAction:true` ⇒ 派生 `pendingStatusOf('succeeded'/'cancelled'/'expired'/…)` ⇒ `:266`/`:268`/`:269`/`:272` 等**终态 tag 在该旁路下可达**；`failed` 段落 `pendingStatusOf` 的 `default`（`parts.ts:120-126`）⇒ `'pending'`（见 A10） |
| 「恒 pending」订正 | 台账 R-139③ 的「`pendingCall.status` 恒 `'pending'`」**不成立**（否证【实测】），但**结论方向成立**：终态 tag 全集在**权威翻转后**（旁路未关时）仍可由 `suspended` 旁路触达 |
| **施工取值（D2）** | **先关旁路（A9/D8）→ 再删死码**；**死码精确范围排除 `:213-232` 与 `:254-256`**；删 tag **不得破坏 `v-if`/`v-else-if` 链**（`:264` 链头挂 `:234`、`:280` 的 `v-else` 挂 `:264`）⇒ **逐行号清单见 §5** |

#### A4 终态挂起卡消失 ⇒ GF 取消回灌失败文案**失去渲染位**（③a E7 / R-140①-1）

| 项 | 内容 |
|---|---|
| 锚点 | `AiPanel.vue:258-260`（`ai.activeForm?.status === 'cancelled' && ai.activeForm.error` ⇒ 渲染 `error`）；宿主块 = 挂起卡 GF 分支 `:234-262` |
| 现状 | 段终态后 `requiresAction=false` ⇒ `pendingCall=null` ⇒ 挂起卡整块不渲染 ⇒ **文案无处可显**（`:258` 条件仍在，但外层 `v-if`（`:201`）恒假） |
| **归属（终裁，D16 第 5 条）** | **❌ 移交批 4④**（随 A1 模板合并给**独立槽位**，工具卡 `reason` 位或 notice 通道），**本批登记**（R-140①-1 行尾注记须写明移交批 4④，**不得静默**） |

### 组 2 · 段状态机残留

#### A5 `suspended` 帧单独到达**不产生挂起语义**（③a E8 / R-140①-2）

| 项 | 内容 |
|---|---|
| 锚点 | `parts.ts:402-416`（`case 'suspended'`：`:406` 只挑 CONFIRM/FRONTEND；`:408-413` `upsertToolPart(..., false)` = **`createIfMissing=false`**）；store 侧同级 `ai.ts:1629-1645`；`parts.ts:98-100`（段不存在 ⇒ return） |
| 现状 | 段不存在则**什么都不写** ⇒ 若后端存在「只发 `suspended`」路径，则该次调用**无任何段**（前端不知有此工具） |
| 前提订正【半证伪】 | 批 2 「生产不可达」**半证伪**：`SpToolCallingManager.java:163` `out.suspended(...)` **先发**，循环内 `checkCancelled(runId)`（实读 **`:169`**，另 `:192`）在取消竞态下可让整轮在 `toolStart` 前终止 ⇒「只发 suspended」**可达** |
| **施工取值（D5(A)）** | **`createIfMissing=true` + `status:'pending'`**；**建段前先过闸**（跨消息查 `hasDecidedPendingResidue`：他消息已决 ⇒ **不建**，防**跨消息双段**）；安全靠**双兜底**（轮终态 `expireResidueToolParts` `:1661`/`:1678` + `restoreSuspendedSnapshot` 非 PENDING 收敛 `:723-731`），**不靠不可达**；**申报前提**「后端必发终态帧」（**未实跑复验**） |

#### A6 存量 v2 镜像段的 `streaming:true` 假态（③a E9 / R-140①-4）

| 项 | 内容 |
|---|---|
| 锚点 | 写入侧 `ai.ts:279-304`（`:294` `parts: message.parts` **原样入镜像**；`:290-298` 不含 `done`）；读取侧 `loadHistory:593-597`（**只清 `message.streaming`，不清 `part.streaming`**）；`sanitizeMirrorResidues:228-237`（**只收 `requiresAction`**）；消费侧 `parts.ts:44-51`（`openStreamPart` 读 `last.streaming`）+ `:69-74`（`sealTrailingStreamPart`） |
| 可达性限定 | ①镜像路径仅在「后端历史为空/不可达」时留存（历史非空走 `:646-678` 整替，镜像被丢弃）；②渲染层**不读** `part.streaming`（全仓唯一渲染读点 = `AiPanel.vue:529` 的 `message.streaming`，两方穷尽证实）⇒ 后果局限在**段边界**而非视觉假态 |
| **施工取值（D4(A)，GLM 判定「分处」）** | **读取侧归一**：`loadHistory` 镜像分支补 `part.streaming=false`（与 `:595` 对齐）——**注意 `parts` 是数组，需 `parts.map`（数行，非一行）**；**不收进 `sanitizeMirrorResidues`**（防职责扩大） |

### 组 3 · R-141 四项（台账 `:330`）

#### A7 **R-141①** re-attach 新宿主 ⇒ 同一 `toolCallId` **两张卡**

| 项 | 内容 |
|---|---|
| 锚点 | `ai.ts:1022`（`lastSeq` 读点）/ `:1023`（`appendAssistantMessage('（正在重挂进行中的轮次…）')`）/ `:1035`（`activeRoundHostId = assistant.id`）/ `:1046`（`onFrame: (frame) => this.handleFrame(frame, assistant.id)`）；段定位 `parts.ts:77-80`（`findToolPart` **单消息域**）+ `:90-103`（未命中即**新建段**） |
| 现状 | 重挂新建宿主 ⇒ 回放帧全落**新消息** ⇒ `findToolPart` 找不到旧段 ⇒ 新建段 ⇒ **旧消息的 `unknown`/`failed`/`cancelled` 卡仍在**（两张状态矛盾的卡） |
| 同理成立范围 | 对 `failed`/`cancelled` 系为**存量同族**（R-141 原文），对 `unknown` 为 ③a 新引入面的**已知代价**（③a 施工报告 §7-1 已双向留痕） |
| **施工取值（D6(B) + 收窄申报）** | **店级守卫**（判据现成：`hasDecidedPendingResidue` `ai.ts:1210-1219`（**已跨消息**）+ `isFrameDecidedPendingStatus` `:388-392`（**七态含 `unknown`/`failed`**））⇒ **只闭合「已决」面**。**未决双卡残余**（旧段 `pending`/`running` + 断流重挂回放）**如实申报移交后续**；**re-attach 空宿主消息**属**可接受 UX 残留**（登记）；**不得表述「A7 已闭合」**；**R-141① 行尾注记承接** |

#### A8 **R-141②** `loadHistory` 整替 ⇒ 工具段状态**硬编码 `succeeded`**

| 项 | 内容 |
|---|---|
| 锚点 | `ai.ts:646-678`（`this.messages = history.messages.map(...)`）；**硬编码点 `:659`**（`status:'succeeded'`）+ `:658`（`kind2:'UNKNOWN'`）；`:662`（`requiresAction:false`）；`:676`（`deriveProjections`）；`:680-683`（`historyLoaded=true` ⇒ `await restoreSuspendedSnapshot()`） |
| **S0 已证（两方会审）** | `AiHistory`（`types/ai.ts:146-150` = `sessionId`/`count`/`messages`）与 `AiHistoryMessage`（`:134-143` = `type`/`text`/`toolCalls?`/`toolResponses?`/`timestamp`）**无任何状态字段** ⇒ D7(A) **不可行** |
| **施工取值（D7(B)）** | **维持硬编码** + **口径注记（采用 GLM §四-4 措辞本体，逐字入卡面注释与报告）**；新立项 **R-144 = 后端历史携带状态**（出 ③b）；**R-141② 行尾注记指向 R-144** |

> **D7(B) 注记正文（施工时照此写入 `ai.ts:646-678` 段注释与施工报告）**：
> 「`loadHistory` 历史整替（`ai.ts:646-678`）的工具段 `status:'succeeded'`（`:659`）与 `kind2:'UNKNOWN'`（`:658`）是**展示占位值，不是事实断言**：后端历史载荷（`AiHistory` / `AiHistoryMessage`，`types/ai.ts:133-150`）只有 `type` / `text` / `toolCalls` / `toolResponses` / `timestamp` 五类字段，**不携带任何工具执行状态**，前端无从判定成败。因此本地已落的 `unknown` / `failed` 终态在整替后**必然丢失并显示为「已成功」——这是已申报的已知失真（R-141②），不是可接受行为**；修复属后端面（历史携带状态），已登记立项（**R-144**），③b 不含后端。在后者落地前，「`UNKNOWN` + `succeeded`」组合 = 载荷缺状态的占位口径，**任何消费方不得以该 `succeeded` 值做业务判定**，**任何后续批次不得把本注记解读为「历史整替丢 `unknown` 可以接受」**。」

#### A9 **R-141③** 通用 `FRONTEND` 工具重投帧**不经守卫**（且会**重执行**）

| 项 | 内容 |
|---|---|
| 锚点 | `ai.ts:1587-1606`（`case 'frontend_tool_request'`）；**守卫带 GF 前缀 `:1588`**（`frame.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(...)`）；**直跑点 `:1605`**（`void this.runFrontendTool(message, frame)`）；第二处守卫 `:1635`（`suspended` 帧路，同带 GF 前缀，case `:1629-1645`）；段写入**无 first-wins**：`parts.ts:359-372`（无条件 `status:'pending'` + `requiresAction:true`；`upsertToolPart` 为 `{...existing, ...patch}` 合并 `:93-95`） |
| 现状【实测链】 | ①守卫不拦（前缀不符）；②`parts.ts:359-372` 把该段**改回 `pending` + `requiresAction=true`**（终态被改写，与验收点 2 冲突）；③`:1605` **再次执行** `runFrontendTool`（⇒ 页面动作/下载**重复触发**） |
| **施工取值（D8(B) 收窄版）** | 见 §2 D8：**去 GF 前缀**（守卫扩到**全工具**）+ **闸只落挂起语义帧** + **落点在各 case 内** + `suspended` 帧**遍历 `frame.toolCalls[].toolCallId`**；**已决判据维持统一 7 态**；**契约前提三条申报** |

#### A10 **R-141③ 行尾注记（DS Pro O-5）**：`failed + requiresAction=true` 经 `suspended` 支**残余可达**

| 项 | 内容 |
|---|---|
| 锚点 | `parts.ts:196-205`（X1 不变式**只拦 `status === 'unknown'`** `:200-201`，`failed` 落 `else` ⇒ 正常投影）；`parts.ts:120-126`（`pendingStatusOf` 的 `default` 把 `pending`/`failed`/`unknown` 一律折 `'pending'`）；旁路 = `parts.ts:402-416` + `ai.ts:1629-1645`（GF 前缀守卫 ⇒ 通用工具不拦） |
| 现状【实测】 | rogue `failed + requiresAction=true` ⇒ `pendingStatusOf('failed') → 'pending'` ⇒ **挂起卡复活**（显示为「等待确认」类 pending 卡），而工具卡同时显示「执行失败」⇒ **单段双面** |
| **施工取值** | 与 A9 **同源**：**D8 关旁路后本条自然消解**（**终裁驳回** DS Pro C3 的「本地/后端终态区分」修法 ⇒ **不加 `failed` 特例、不改 `pendingStatusOf` 折法**）；若实测仍有可达路径 ⇒ **报告申报**（不得静默） |

#### A11 **R-141④** 两张翻译表穷举口径分叉 + **台账口径订正**

| 项 | 内容 |
|---|---|
| 锚点 | ①`parts.ts:232-250`（`frontendResultStatus`：`FRONTEND_RESULT`/`SUCCEEDED → succeeded`、`FRONTEND_CANCELLED`/`CANCELLED → cancelled`、`TIMEOUT → expired`、`REJECTED_ARGUMENTS`/`REJECTED → rejected`、`BLOCKED → blocked`、`default → null`）；②`ai.ts:340-364`（`frameStatusToPendingStatus`：**无 `SUCCEEDED`**；`CANCELLED` 带 **run-cancel 依据注释 `:347-349`**） |
| **台账口径订正【实测】** | R-141④ 原文「后者缺 `CANCELLED` 依据注释」**不成立**：`ai.ts:347-349` 三行注释实存，且 `git show 3edaeb9` **同期存在** ⇒ 该表述自 ③a 落行起即为**误述**；**唯一真实分叉 = `SUCCEEDED`**（仅 `parts.ts:234-236` 有） |
| **施工取值（D9(C)）** | **维持两表 + 防漂移注记覆盖三对**：①`frontendResultStatus` vs `frameStatusToPendingStatus`（分叉 = `SUCCEEDED`）；②**`isFrameDecidedPendingStatus`（`ai.ts:388-392`）vs `localTerminal`（`parts.ts:383-386`）**（两枚举同 **7 态**、**无共享常量**）；③台账**误述订正**（落 R-141④ 行尾注记） |

### 组 4 · R-142（台账 `:331`）

#### A12 `stop()` 后在途 `executeFrontendTool` 仍**写段 + 回灌**

| 项 | 内容 |
|---|---|
| 锚点 | `ai.ts:1800-1842`（`runFrontendTool`）：`:1816-1821` `await executeFrontendTool(...)`（**无轮次身份守卫**）；`:1829-1836` 写段；`:1837` `deriveProjections`；`:1838-1840` 降级 notice；`:1841` `await this.submitFrontendToolResult(...)`；`send` 的 `finally` `:929-943`（`:933-936` 只复位 `loading`/`suspended`/`currentAbort`/`activeRoundHostId`；`:938-942` 只把宿主消息标 `done`，**不清段、不中止在途执行器**） |
| **施工取值（D10(A) + 捕获点）** | **入口捕获** `const controllerAtStart = currentAbort`（`isCurrentRound` 是四处局部闭包 `:811`/`:883`/`:1037`/`:1428`，**非 drop-in**）⇒ `:1816` await 返回后**比对**，失配 ⇒ **丢弃段写（`:1829-1836`）与回灌（`:1841`）**；`:1808-1815` 开跑落 `running` **属正常**（当时仍当前轮），**校验点只设在 await 返回之后** |

### 组 5 · R-143 四项（台账 `:332`）

#### A13 **R-143①** `syncStepToBackend` 失败 ⇒ `ElMessage.error` + `ElMessage.warning` **双弹**

| 项 | 内容 |
|---|---|
| 锚点 | 导出侧：`ExportWizardView.vue:608-627`（`syncStepToBackend`，catch 内 **`:624`** `ElMessage.error`）+ `:561-583`（`goStep`，**`:579`** `ElMessage.warning`）；导入侧：`ImportWizardView.vue:758-776`（**`:773`** `ElMessage.error`）+ `:714-735`（**`:731`** `ElMessage.warning`） |
| **施工取值（D11(A)）** | **单点提示**：`syncStepToBackend` **不自弹**，由 `goStep` 只弹 `warning`（两向导同改；`syncStepToBackend` 全仓**仅两个调用方** ⇒ 不留静默路径） |

#### A14 **R-143③** `assertUnknownTerminalInvariant` 缺 `import.meta.env.DEV` 门

| 项 | 内容 |
|---|---|
| 锚点 | `parts.ts:129-139`（`:134-139` 函数体，无条件 `console.error`）；调用点 `:196-205`（`:200-201` dev 注释与调用） |
| 现状 | 被注释/卡面称为「dev 断言」，实为**生产也执行**（仅 `console` 留痕，无功能影响）⇒ **口径不实**（方向安全） |
| **施工取值（D12(A)）** | **补 `import.meta.env.DEV` 门**（vitest 环境 `DEV=true` ⇒ 既有测试行为不变；③b 后 X1 有「守卫 + 测试锁」双保护，DEV 门不削弱） |

#### A15 **R-143②** CDP 冒烟脚本用**裸路径探针**（**仓外脚本**）

| 项 | 内容 |
|---|---|
| 锚点 | `<SCRATCH>/chat-timeline-pkg\batch3a-outcome-smoke.mjs:147-150`（`await evaluate(\`(async () => { const ws = await import('/src/stores/workspace.ts'); ws.registerPageHandler('batch3a.hang', …) })()\`)`）——**仓外文件** |
| 现状 | 以 dev-server **裸路径** `import('/src/...')` 取得模块并注册探针 handler ⇒ 对 **HMR 换版**脆弱（模块实例/转换缓存变化即失配） |
| **施工取值（D13(B)）** | **脚本头写明「复跑前重启 dev server」**（③b 冒烟脚本照此执行，见 §7）；**R-143② 行尾注记追加「(A) 已排查无应用内入口」** |

#### A16 **R-143④** T7③/④ 的 RED 留证**红在夹具未就位**而非断言本体

| 项 | 内容 |
|---|---|
| 锚点 | `<SCRATCH>/chat-timeline-pkg\批3a-evidence\RED-T7.log`（589 行）；对应用例 `frontend/tests/export-wizard-step-outcome.spec.ts`（4 例） |
| 现状【两方亲读】 | **4/4 全部 red-on-fixture**：T7① `selectDefs reject` 未生效、T7② `goToStep reject` 未生效（= mock-reject 未生效）；T7③ 命中上下文守卫（log:554）、T7④ 命中结果文件缺失（log:570）⇒ 均**未触达被测断言**；**「GREEN 终态断言为真锁」本身无任何绿色留证**（属【假设】级结论） |
| **施工取值（D14(A) + 强化）** | **反向留证覆盖 T7①②③④ 全四例**（**夹具就位仍红**）⇒ 证明断言在**真实被测代码**上会红；若某例经夹具就位后**变绿** ⇒ 该用例**重写断言**（问题在锁本身，非留证） |

### 组 6 · ③a §12「登记不修」minor 四项

#### A17 **M6** 导入侧 `goStep` 同步失败**只给 `ElMessage`、回灌文本仍是干净成功文案**（与导出侧不对称）

| 项 | 内容 |
|---|---|
| 锚点 | `ImportWizardView.vue:714-735`（`goStep` **仍返回 `boolean`** —— ③a D9「最小接入」；`:731` 仅 `ElMessage.warning`，**无 `syncWarning` 返回值**）；`:1016-1035`（`import.nextStep` handler；`:1028` `const advanced = await goStep(index)`；`:1034` 成功回灌 `已推进到导入向导步骤「…」` —— **不含同步失败信息**） |
| 对照 | 导出侧已把 warning 带进回灌：`ExportWizardView.vue:1202-1203`（`if (advanced.syncWarning) return \`已推进到…（${advanced.syncWarning}）\``） |
| **施工取值（D15(A)）** | **导入侧对齐导出**：`goStep` 返回结构化（含 `syncWarning`）+ handler 回灌带警告；**导入侧无既有回灌文本锁 ⇒ 无翻红面** |

#### A18 **M7** `startExport` **丢弃** `persistStepData` 的结构化结局后照旧 `createJob`

| 项 | 内容 |
|---|---|
| 锚点 | `ExportWizardView.vue:699-720`（`async function startExport()`；**`:708` `await persistStepData()`** —— 返回值被丢弃，随后 `:711` `jobsApi.createJob(taskId, 'EXPORT')`） |
| 现状 | `persistStepData` 已能返回 `{ok:false, reason}`（③a D7 改造），但 `startExport` 不读 ⇒ 条件/勾选未落库时仍启动导出（「设了条件、导出却是全量」同族面） |
| **施工取值（D16，终裁）** | **❌ 移出 ③b，登记不修**（并入 **R-143 行尾注记追加**；原文本就建议「随下一批」） |

#### A19 **M8** `goStep` 无 in-flight 门 ⇒ 双击 prev/next 可并发两轮 `persistStepData`

| 项 | 内容 |
|---|---|
| 锚点 | `ExportWizardView.vue:561-583`（`goStep`：`:566` `await persistStepData()` 在 `:570` `stepIndex.value = next` **之前** ⇒ 连点两次算出同一 `next`）；调用点 `:14`（模板 `@prev="goStep(stepIndex - 1)"`）、`:635`（`nextStep` ⇒ `goStep(stepIndex.value + 1)`） |
| 现状 | 两次并发 `persist`（无 `persisting` 门）；若一成一败，成功那次推进、失败那支回 `{ok:false}`；**不跳步、无崩溃、无 unhandled rejection**（③a 红队已复核） |
| **施工取值（D16，终裁）** | **❌ 移出 ③b，登记不修**（**注意**：草案 A19 的「待裁 D17」为**悬空引用**，本稿一律改指 **D16 边界裁决 / 登记清单**） |

#### A20 **M9 / O-2** `runUiEventAction` 的 `handled:false ⇒ failed` 仍为 `availability:'available'`（**无降级 notice**）且无直接单测

| 项 | 内容 |
|---|---|
| 锚点 | `frontend/src/utils/frontend-tools.ts:908-933`（`runUiEventAction`）；**`:921-925`**（`makeExecution({ outcome: outcome.handled ? 'ok' : 'failed', availability: 'available', result: outcome.message })`）；对照 `:926-931`（`catch` 支 `availability:'degraded'`） |
| 现状 | 契约层**未受理**（`handled:false`）现已如实落 `failed`（③a D1 已修语义），但 `availability` 保持 `available` ⇒ `ai.ts:1838-1840` 的降级 notice **不触发**（用户/模型只有卡面 tag，无 notice） |
| **施工取值（D16，终裁）** | **❌ 移出 ③b，登记不修**；草案 **§4.1 第 2 条「`frontend-tools.ts` 红线例外须 D18 授权」整条删除** ⇒ **红线无例外，③b 不触 `frontend-tools.ts`**（定稿范围红线见 **§8.2 第 2 条** + **§3 E19**；草案 A20 的「待裁 D18」为**悬空引用**，改指 **D16 边界裁决 / 登记清单**） |

### 组 7 · 实例订正与边界

#### A21 ③a 交付的行号在 `505f6d4` **全线漂移**（③b 卡一律用新行号）

| 台账/卡内旧锚（③a 基线 `3edaeb9`） | `505f6d4` 实读锚 | 漂移 | 落稿棒核验 |
|---|---|---|---|
| R-141① `stores/ai.ts:1008` | **`stores/ai.ts:1023`** | +15 | **PASS** |
| R-141② `stores/ai.ts:631-663`，硬编码 `:644` | **`:646-678`**，硬编码 **`:659`** | +15 | **PASS** |
| R-141③ `:1573` / `:1620` / `:1590` | **`:1588` / `:1635` / `:1605`** | +15 | **PASS** |
| R-141④ `parts.ts:210-228` / `ai.ts:333-357` | **`parts.ts:232-250`**（`DS Pro C6` 订正终点）**/ `ai.ts:340-364`** | +22 / +7 | **PASS** |
| R-142 `ai.ts:1798-1818` / `:923-927` | **`:1800-1842` / `:929-943`** | +2 / +6 | **PASS** |
| R-143① `Export:624`/`:579`；`Import:773`/`:731` | **同值命中** | 0 | **PASS** |
| R-143③ `parts.ts:134-139` | **同值命中** | 0 | **PASS** |
| R-139① `ai.ts:225-227`（m2 守卫） | **同值命中** | 0 | **PASS** |
| R-139③ `AiPanel.vue:250-285` | 现 **`:201-287`**（死码分支 `:206`/`:250-253`/`:264-277`/`:280-286`） | 区间口径 | **PASS** |
| R-97（`M2:289`）「5 处调用方」`Export:14`/`:599`/`:1128`/`:1146`/`:1225` | **`:14` / `:635` / `:1169` / `:1192` / `:1284`**（`goStep(` 实读 **5/5**） | +0/+36/+41/+46/+59 | **PASS** |

| 项 | 内容 |
|---|---|
| **施工取值** | ③b 施工卡与报告**一律引用 `505f6d4` 行号**；对以上旧锚做**行尾注记式订正**（**本批不动 `M2` 正文**，提交官办理，见 §8.5） |

#### A22 边界待裁：R-84 的**同族残留面**（只读/越界拒绝字符串仍折 `ok`）

| 项 | 内容 |
|---|---|
| 锚点 | `ExportWizardView.vue:1182-1184`（只读 ⇒ `:1183` `return '任务已结束，仅供查看，不能推进步骤'`）与 `:1187-1189`（越界 ⇒ `:1188` `return \`步骤 …不在导出向导范围内…\``）；消费 handler `export.nextStep` 起于 `:1180` |
| 机制 | `interpretHandlerOutcome`（`frontend-tools.ts:970-989`，`:974-975`）把字符串 `return` 折 `outcome:'ok'` ⇒ 卡面 tag「已成功」而卡内文是拒绝文案 |
| 台账口径 | `docs/M2-排期计划.md:276`（R-84 行尾注记）：「本批**未动**（D3(iii) 范围控制，登记待批），**随 R-84 批一并结构化**」 |
| **施工取值（D16(A) 终裁）** | **❌ 不搭车**：③b **不动** A22；**归登记** —— **R-84 行尾注记追加** ①补登 **`ExportWizardView.vue:1340`**（同族面 **21→22**）②**GLM §四-5 口径**：**机制 + 判定词「未闭合」+ 后果 + 去向 + 逐处行号**（清单见 §8.6，**颗粒度 = 下批只读台账即可开工**）；**导入 11 处维持 D3(iii) 登记**（③b 不升级为结构化） |

---

## §2 D1~D16 终裁落表（**施工取值**）

| # | 终裁（施工取值） | 关键红线/坑 | 否决方案 |
|---|---|---|---|
| **D1** | **(B) 只做状态机与死码**：模板合并**移交批 4④**；收工报告**如实写**「模板合并未做，挂起卡与工具卡仍并列渲染（`AiPanel.vue:179-288`）」；③b **不触 `AiPanel.vue` 渲染区**（唯一例外 = A3 死码 tag，殿后）；批 4④ 同文件串行风险**实际不触发** | 不得让 (B) 被误读为「两张卡已解决」；A3/A4 归属见 D16-5 | (A) 完整合并（渲染重构批 + 与批 4④ 同渲染区叠批）；(C) 拆两笔 |
| **D2** | **(A) 先关旁路再删死码**；定序 = **D8 闸先行 → D5 建段 → O1 死码殿后**；死码**排除 `AiPanel.vue:254-256`**（`activeForm` `cancelled`/`submitted` 活分支，GF 提交窗口可达）与 **`:213-232`**（CONFIRM+pending 确认按钮/倒计时；`pendingCall` **第二来源** = `restoreSuspendedSnapshot` 重建路 `ai.ts:760-767`）；删 tag **不得破坏 `v-if`/`v-else-if` 链**（`:264` 链头挂 `:234`；`:280` 的 `v-else` 挂 `:264`）；**死码清单逐行号列明（§5）** | 先删分支再关旁路 ⇒ 丢渲染位；误删 `:213-232`/`:254-256` ⇒ 确认按钮/提交窗口 tag 丢失；破链 ⇒ 渲染塌陷 | (B) 先合并；(C) 维持登记 |
| **D3** | **(A) 删守卫 + 入参契约注释**（「调用方须已归一 `done`」） | 反向罢工风险须在注释写明（直调镜像原始数据 ⇒ 全段跳过） | (B) 改判据（需补镜像 `done`，形态变更）；(C) 维持 |
| **D4** | **(A) 分处落地**：入参归一落 **`loadHistory`**（与 `:595` 对齐，`parts.map` 清 `part.streaming` —— **数行非一行**）；**不收进 `sanitize`**（防职责扩大） | 「一处一行」表述不实；不得把"归一入参"混进"清残留" | (B) 收进 sanitize；(C) 登记不修 |
| **D5** | **(A) 补建段**：`createIfMissing=true` + `status:'pending'`；**建段前跨消息查 `hasDecidedPendingResidue`**（他消息已决 ⇒ 不建，防**跨消息双段**）；**论证 = 双兜底**（轮终态 `expireResidueToolParts` `:1661`/`:1678` + 快照非 PENDING 收敛 `:723-731`），**不靠不可达**；**申报前提**「后端必发终态帧」（**未实跑复验**）；**测试锁**「`suspended` 建段 → done 帧 ⇒ `expired`」；**预期翻红两锁改写**（`parts-reducer.spec.ts:184` / `parts-residue-cleanup.spec.ts:465`） | 不建"建段前过闸" ⇒ 跨消息双段（新竞态）；不给终态兜底 ⇒ **永久 pending**（比现状更糟）；不同批改写两旧锁 ⇒ 落地即翻红 | (B) 维持「生产不可达」；(C) 只公告 + notice |
| **D6** | **(B) 店级守卫 + 收窄申报**：判据现成（`hasDecidedPendingResidue` `:1210-1219` **已跨消息** + `isFrameDecidedPendingStatus` `:388-392` **七态含 `unknown`/`failed`**）；**只闭合 A7 已决面**；**未决双卡残余**（旧段 `pending`/`running` + 断流重挂回放）**移交后续 + 申报**；**re-attach 空宿主消息**属**可接受 UX 残留**（登记）；**不得表述「A7 已闭合」**；**R-141① 行尾注记承接** | 错误销记「A7 已闭合」= 事实失真（未决双卡 + 空宿主残留仍在） | (A) 段定位跨消息 + 写回原宿主（改 `upsertToolPart` 隐式契约、把"写定位"与"已决判定"混述）；(C) 重挂复用原宿主（与 `lastSeq`/`activeRoundHostId` 设计冲突） |
| **D7** | **(B) 定案**：维持 `loadHistory` 硬编码（`:659` `succeeded` + `:658` `kind2:'UNKNOWN'`）+ **GLM §四-4 措辞本体注记**（占位值非事实断言 / 已知失真申报 / 任何消费方不得以该 `succeeded` 做业务判定 / 不得解读为「丢 `unknown` 可接受」）；**新立项 R-144**（后端历史携带状态，**出 ③b**）；**R-141② 行尾注记指向 R-144** | 注记过软 ⇒ 下批把占位 `succeeded` 当事实 | (A) 按后端历史字段判（载荷无字段，**不可行**）；(C) 改后端（越界 ⇒ 由 R-144 承接） |
| **D8** | **(B) 收窄版**：①闸**只拦挂起语义帧**（`suspended`/`confirm_request`/`frontend_tool_request`）的**建段 / 写 `requiresAction` / 置 `suspended` / 武装 activeForm / 触发 `runFrontendTool`**；**终态帧不入闸**（`frontend_tool_result`/`tool_result`/`confirm_decision` 保留原 case：activeForm 收敛 `:1617-1625`、`suspended=false` `:1626`）；②**落点 = 各 case 内守卫**；单工具帧读 `frame.toolCallId`；**`suspended` 帧遍历 `frame.toolCalls[].toolCallId`**（`types/sse.ts:219-222` 无单数字段）；③**命中 ⇒ 跳过写与触发，保留 seq 记账（`:1509-1516`）与 `markApplied`（`:1697-1699`）**；**"被拦帧不走 `markApplied`"的职责分界写明**（下次再投靠**闸**兜底、**非** seq 幂等）；④去 GF 前缀（**放宽**）后既有 GF 用例**行为不变** + **须留回归留证**；⑤**已决判据维持统一 7 态**（**驳回** DS Pro C3 区分修法）；⑥**闸只落 `handleFrame` 帧路**，非帧路（`restoreSuspendedSnapshot`/`applyFrontendToolTerminal`/`applyPendingTerminal`）**不收入但覆盖面并列申报**；⑦**红线：不得销记「重挂已闭合」**，残余面**六条逐条申报**（§8.4） | **入口整帧丢弃** ⇒ 吞 activeForm 收敛/`suspended` 复位 ⇒ 破 ③a E17 回归锁；**入口单字段** ⇒ `suspended` 帧 `undefined` ⇒ **整帧漏拦**（rogue 旁路存活）；**按 C3 区分** ⇒ 重试锁死/二次副作用二难 + **帧胜回潮**；把非帧路收进闸 ⇒ 破坏正常重建路 | **DS Pro C3**（区分本地/后端终态）—— 驳回；入口统拦（帧型无关）—— 驳回 |
| **D9** | **(C) 维持两表 + 防漂移注记（覆盖三对）**：①`frontendResultStatus`（`parts.ts:232-250`）vs `frameStatusToPendingStatus`（`ai.ts:340-364`）—— 唯一真实分叉 = `SUCCEEDED`；②`isFrameDecidedPendingStatus`（`ai.ts:388-392`）vs `localTerminal`（`parts.ts:383-386`）—— 两枚举同 7 态、无共享常量；③**R-141④ 误述订正**（`CANCELLED` 注释实存 `ai.ts:347-349`，`3edaeb9` 同期即有） | 抽公共表 ⇒ 破 `parts.ts`「不依赖 store」纯函数层约束（`parts.ts:12`） | (A) 抽公共常量表；(B) 单向复用 |
| **D10** | **(A) + 捕获点**：`runFrontendTool`（`:1800-1842`）**入口捕获** `const controllerAtStart = currentAbort`（`isCurrentRound` 是四处局部闭包 `:811`/`:883`/`:1037`/`:1428`，**非 drop-in**）；`:1816` await 返回后比对，失配 ⇒ **丢弃段写（`:1829-1836`）与回灌（`:1841`）**；`:1808-1815` 开跑落 `running` 属正常 | 误用 `this.sessionId`/`activeRoundHostId` 当判据 ⇒ 判据失效 | (B) `stop()` 时就地 `cancelled` + 标志（须定义中断语义；窗口残留）；(C) 维持登记 |
| **D11** | **(A) 单点提示**：`syncStepToBackend` 不自弹，由 `goStep` 只弹 `warning`（`Export:561-583`/`:608-627`；`Import:714-735`/`:758-776` 同改） | —（全仓仅两调用方 ⇒ 不留静默路径） | (B) 只弹 error + 回灌带 warning 文案；(C) 维持双弹 |
| **D12** | **(A) 补 `import.meta.env.DEV` 门**（`parts.ts:134-139`）；vitest `DEV=true` ⇒ 既有测试不变 | DEV 门不得削弱 X1「守卫 + 测试锁」双保护 | (B) 编译期不可达；(C) 维持 |
| **D13** | **(B) 脚本头写明「复跑前重启 dev server」** + **R-143② 行尾注记追加「(A) 已排查无应用内入口」** | 若仍按裸路径探针复跑 ⇒ 可能**假红** | (A) 应用内入口（**无落点**）；(C) 维持 |
| **D14** | **(A) 反向留证覆盖 T7①②③④ 全四例（夹具就位仍红）**；「GREEN 真锁」本身**无绿色留证** ⇒ 不得当既证事实 | 若某例夹具就位后**变绿** ⇒ 该断言须**重写**；夹具不可就位 ⇒ 改 (B) + 申报 | (B) 只改夹具构造；(C) 接受现有留证 + 声明 |
| **D15** | **(A) 导入侧对齐导出**：`goStep` 返回结构化（含 `syncWarning`）+ handler 回灌带警告；导入侧**无既有回灌文本锁 ⇒ 无翻红** | 复取调用点清单（若发现其他消费者 ⇒ 逐处取结局，须**追加授权**） | (B) 保留 `boolean` + side-channel；(C) 维持 + 注记 |
| **D16** | **(A) 严守台账边界 + DS Pro C1 处置**：①**M7/M8/M9（A18/A19/A20）移出 ③b，登记不修**（并入 R-143 行尾注记追加）；草案 **§4.1 第 2 条「`frontend-tools.ts` 红线例外」整条删除**（**红线无例外** ⇒ 定稿范围红线见本卡 **§8.2 第 2 条**，对应排除项 **§3 E19**）；②**清悬空引用**：A19/A20/A22 的 `D17`~`D20` 改指「**D16 边界裁决 / 登记清单**」；草案 §5.1 承接表的「`D15~D18`」区间表述**作废** ⇒ 定稿承接清单落到本卡 **§8.5**（逐项点名、无区间表述）；**本卡全文无裸 `D17`~`D20`**；③**A22 归登记** + **R-84 行尾注记**（**`ExportWizardView.vue:1340`** 补登，同族面 **21→22** + GLM §四-5 口径）；④**串行序 = ③b → 批 4④ → 轮询治理一期**；**CDP 冒烟强制**（5202 配方 + ≥1 张「重投后仍只有一张卡」截图 + 复跑前重启 dev server 写脚本头）；⑤**A3/A4 归属消歧**：**A3 归 ③b 删**（殿后 + 排除清单）、**A4 移交批 4④**（本批登记） | 搭车 A22/导入 11 处 ⇒ 重演 ③a D3 范围控制之争、与段状态机主链无关、打破「一次施工一次回归」；保留悬空 D 号 ⇒ 施工棒找裁决点落空、红线例外无授权、M7/M8/M9 悬空 | (B) 同文件搭车（A22 + 导入 11 处）；(C) 部分搭车（仅导出侧） |

---

## §3 排除项与登记去向表（E 表；E1~E18 按终裁口径更新）

| # | 排除项 | 现态锚点（`505f6d4`） | 去向 |
|---|---|---|---|
| E1 | `m3`（红队批 1 `m-R1` 申报粒度）——修复本体已落地 | `stores/ai.ts:167-209`（`migrateMirror`） | **③a 已销记**；③b 不动 |
| E2 | `appliedSeqs` 消息级去重 × `runSeq` runId 级记账两维错位 | `utils/parts.ts:423-436`（`alreadyApplied`/`markApplied`）；`stores/ai.ts:444`（`runSeq` 声明）/`:800`/`:1022`/`:1514` | 登记（实测无路径可达）；③b 不动 |
| E3 | `v-for` key 跨类型理论碰撞（`toolCallId` 恰为 `text-0`） | `AiPanel.vue:166-168` | 登记（服务端 UUID 不可达）；③b 不动 |
| E4 | `isTyping` 在「仅 reasoning 流式、无 text」时三点与思考链同现 | `AiPanel.vue:529` | 登记（后端不产 reasoning 帧）；③b 不动 |
| E5 | 后端 `AiTools` 8 处 `catch (Exception e) { return "<中文失败文案>" }`（W-05） | `backend/.../ai/tools/AiTools.java` | **R-137 批**（③b **不含后端**） |
| E6 | `<details>` 展开态不持久化 | `AiPanel.vue:174` | **批 4④（折叠规则）** |
| E7 | 复制按钮位置变化的**人工目视**确认（R-140①-5，未销记） | `AiPanel.vue:291-315`（testid `:298`/`:308`） | **CDP 人工面**（非代码改动）；③b 冒烟时**可顺带目检**，**不列为施工项** |
| E8 | 重复终帧被 seq 幂等跳过时不复位 `loading`（R-140①-7） | `stores/ai.ts:1701-1703`（`isTerminalFrame` ⇒ `loading=false`）+ `:1697-1699`（seq 记账） | 登记（现症未现）；③b 不动 |
| E9 | R-140② `o1`（`appliedSeqs` 无上界）/ `o2`（滚动指纹只取末段）/ `o4`（`stop()` 不写段终态 —— 同族已由 **R-142/A12** 承接） | `parts.ts:423-436`；`AiPanel.vue` 滚动 watcher；`stores/ai.ts` `stop()` | 维持登记（`o4` 的新增残余走 A12） |
| E10 | R-140①-10/11/12（Maven ERROR 噪声 / E2E 前重建 jar 口径 / `frontend/dist` 未运行时验证） | — | 登记（治理项，非 ③b 面） |
| E11 | ③a E19 的失败形字符串（**③b 一律不动**） | 见 **§8.6** 逐处行号（**22 处**，含补登 `:1340`；另 `Import:1057` 降级/指引形**标注不计入**） | **D3(iii) 登记待批**；**D16(A) ⇒ ③b 不搭车** |
| E12 | ③a E18 的空 `catch` 同型面（两向导仅注释型 5 处 + 全仓 11 处 + `.catch(()=>[]/null)` 变体 5 处） | 见 ③a 施工卡 §3 E18 | 登记不修（③a 已修 `Export:589` / `Import:763`） |
| E13 | `goto_step` 前端 `ui_event` 只读守卫 + 后端 `TaskService.goToStep` 状态守卫 | `stores/workspace.ts:392-401` | **R-137 批**（③a D4(b) 耦合申报沿此） |
| E14 | `frontend/src/stores/workspace.ts` 任何改动 | — | **不在 ③b**（R-137 面） |
| E15 | 后端源码（任何文件、任何理由） | — | **不在 ③b**（**故不要求 `mvn`**） |
| E16 | ③a E14 的「页面 handler 如实失败面」（`ensureGridLoaded` 判据与文案） | `ExportWizardView.vue` 的 `ensureGridLoaded` 消费 handler | **已修面，不得重复施工** |
| E17 | ③a 已落地面（三态 `outcome`、`localTerminal` 含 `unknown`、`isFrameDecidedPendingStatus` 含 `unknown`/`failed`、`pendingStatusOf` X1 门禁 + dev 断言、`applyPendingTerminal`/`expireResidueToolParts` 两同型面、W-06/W-07、`toolTagType` 九态穷尽） | 见 ③a 施工报告 §2 | **回归锁**：只做「确认无遗漏 + 回归断言」，**不得重做/扩张** |
| **E18**（**终裁新增/改写**） | **M7/M8/M9（A18/A19/A20）+ A1（模板合并）+ A4（GF 错误渲染位）+ A22/导入 11 处同族谎报面** | A18 `Export:699-720`（`:708`）；A19 `Export:561-583`（`:566`）；A20 `frontend-tools.ts:908-933`（`:921-925`）；A1 `AiPanel.vue:179-288`；A4 `AiPanel.vue:258-260`；A22 `Export:1182-1184`/`:1187-1189` | **M7/M8/M9 = 移出 ③b、登记不修**（R-143 行尾注记）；**A1/A4 = 批 4④**（R-140①-1 行尾注记写明）；**A22 + 导入 11 处 = 登记待批**（R-84 行尾注记，**未闭合**） |
| **E19**（**终裁新增**） | **`utils/frontend-tools.ts` 红线（无例外）**：任何改动（含草案 A20 的「唯一例外候选」）**均不在 ③b** | — | **红线**：③b 若发现该文件新面，**登记入报告**而非就地修；**须另批授权** |

---

## §4 施工步骤 S0~S5（每步可独立编译 + 绿测）

> **总纪律**：`stores/ai.ts` 与 `utils/parts.ts` 是 ③a 刚落地的文件，③b 起手**不得与任何同文件批次并行**（文件级互斥见 §9.3）。

| 步 | 内容 | 产出 / 门 |
|---|---|---|
| **S0** | **前置清点（只读）**：①**复取本卡 §1 / §10 全部锚点**（防新漂移，尤其 `stores/ai.ts` / `utils/parts.ts` / `AiPanel.vue` / 两向导）；②`grep -rn "hasDecidedPendingResidue" frontend/src` 复核调用点（**4 处**：`:728` / `:740` / `:1588` / `:1635`）；③`grep -rn "runSeq" frontend/src` 复核记账点（`:444` / `:800` / `:1022` / `:1514`）；④清点 `suspended` 帧消费面（`parts.ts:402-416` + `ai.ts:1629-1645`）；⑤**RED 前置**：先写 §6 各锁的失败用例（预期红）并留证（**含 (d) 两条既有锁的改写前红**）；⑥**D14 前置**：为 T7①②③④ 构造「夹具就位仍红」的反向留证 | 锚点对比表 + 消费点清单 + **RED 留证** → 落施工报告 |
| **S1** | **D8 闸（**关旁路**）」：`frontend_tool_request` / `suspended` / `confirm_request` **各 case 内**加守卫（去 GF 前缀；`suspended` 帧**遍历 `frame.toolCalls[].toolCallId`**）；**终态帧 case 一字不动** | **本步 = 定序第 1 位**；扩展 `frontend-tool-result-terminal.spec.ts`（§6(a) 不重执行锁 + GF 回归留证）；`yarn typecheck` 绿 |
| **S2** | **D5 建段**：`parts.ts:402-416` 改 `createIfMissing=true` + `status:'pending'`；**建段前跨消息过闸**；store 侧 `ai.ts:1629-1645` 同步 | **本步 = 定序第 2 位**；**改写** `parts-reducer.spec.ts:184` / `parts-residue-cleanup.spec.ts:465`（**预期翻红**，§6(d)）+ 新增「`suspended` 建段 → done ⇒ `expired`」 |
| **S3** | **O1 死码殿后**：按 **§5 清单**逐行号删 tag（**排除 `:213-232` / `:254-256`**；不破 `v-if`/`v-else-if` 链）；**A6/D4**（`loadHistory` 镜像分支 `parts.map` 清 `part.streaming`）；**A2/D3**（删 sanitize 守卫 + 入参契约注释） | **本步 = 定序第 3 位**；`ai-panel-parts-render.spec.ts` 保持 **4 例绿**（模板结构不动）；`parts-residue-cleanup.spec.ts` 绿 |
| **S4** | **A11/D9 三对注记** + **A12/D10 捕获点与丢弃** + **A13/D11 单点弹** + **A14/D12 DEV 门** + **A7/A8/A9/A10 报告口径**（A8 注记正文照 §1） | 扩展 `parts-residue-cleanup.spec.ts`（T1 生死线相邻面）+ `frontend-tool-outcome.spec.ts`；`yarn typecheck && yarn test && yarn build` 绿 |
| **S5** | **A17/D15 导入侧对齐** + **A15/D13 冒烟脚本头口径** + **A16/D14 四例反向留证** + **A21 行号订正入报告** + **A1/A4 移交与 A18/A19/A20 移出登记** | 扩展 `export-wizard-step-outcome.spec.ts`（T7 全四例反向留证）；随后 **CDP 冒烟（§7）** + **`施工报告-批3③b.md` 落盘** |

**验证口径（可直接粘贴执行；仓根 = `<MAIN_REPO>`）**

```bash
# 前端三连（仓内）
cd frontend && yarn typecheck && yarn test && yarn build
```

- **E2E（桩模式；后端不参与本批改动）**
  ```powershell
  $env:E2E_STUB_MODE=1; powershell -File scripts/verify-e2e.ps1
  ```
  沿用 **`Wait-Job` 120s 口径**；**③b 零后端改动 ⇒ 不要求重建 jar**（若期间他批改了后端，按「重建后再跑」处理）。
- **后端 `mvn`**：③b **不含后端** ⇒ **不要求**任何 `mvn` 执行。包装脚本（备用）= `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`（JDK 21，实测 `21.0.12`）。

---

## §5 **O1 死码精确清单（逐行号）+ 排除项**（D2 终裁要求）

### 5.1 删除范围（**逐行号**；`AiPanel.vue` 挂起卡块）

| 行号 | 内容 | 处置 |
|---|---|---|
| `:206` | `<el-tag v-else size="small" type="success">自动</el-tag>`（非 CONFIRM ⇒ 自动） | **删 tag**（保留 `v-if`/`v-else` 结构占位或按模板等价改写，**不得留空链头**） |
| `:250-253` | GF 分支终态 tag：`cancelled`（表单已取消）/ `succeeded`（表单已提交）/ `expired`（会话已失效）/ `rejected`/`blocked`（表单未提交） | **删**（`:250` 带 `data-testid="tag-form-cancelled"` ⇒ **须同步核对既有测试引用**，见 5.3） |
| `:264-277` | 通用 FRONTEND 分支的 8 个 tag（`:265` 待执行 / `:266` 执行中 / `:267` 已拒绝 / `:268` 会话已失效 / `:269` 已取消 / `:272` 已执行 / `:273` 未执行（超出范围）/ `:276` 已放行 / **`:277` `v-else` 兜底「已执行」**） | **删**（`:277` 的 `v-else` 一并删，**不得留无主 `v-else`**） |
| `:280-286` | CONFIRM 终态列（`:281` 执行中 / `:282` 已放行 / `:283` 已拒绝 / `:284` 会话已失效 / **`:285` `v-else` 兜底「执行失败」**） | **删**（同上，`:285` 的 `v-else` 一并删） |

### 5.2 **排除项（活分支，禁止删除）**

| 行号 | 内容 | 排除理由 |
|---|---|---|
| **`:213-232`** | `CONFIRM` + `status === 'pending'`：倒计时/「等待人工确认」/拒绝原因输入/拒绝与确认按钮 | **活分支**：`pendingCall` **第二来源** = `restoreSuspendedSnapshot` 重建路（`ai.ts:760-767` upsert `status:'pending'` + `requiresAction:true`；宿主优先镜像/历史已持段的消息 `:748-752`） |
| **`:254-256`** | GF 分支的 `ai.activeForm?.status === 'cancelled'` / `'submitted'` 两个 tag | **活分支**：GF 提交窗口（`submitting`/`submitted`，回执帧到达前）可达 |
| **`:258-260`** | GF 取消回灌失败文案（`activeForm.status === 'cancelled' && activeForm.error`） | **不属本批**：A4 移交批 4④（本批登记） |

### 5.3 链结构约束（**删 tag 前必读**）

- `:264` 的 `v-else-if="m.pendingCall.kind === 'FRONTEND'"` **挂在 `:234`** 的 `v-else-if` 上；`:280` 的 `v-else` **挂在 `:264`** 上 ⇒ 删 `:264-277` 或 `:280-286` 时**必须同步收拢链结构**（例如整块删除后把 `:280` 提升为 `:234` 之后的 `v-else`），**不得留下悬空 `v-else-if`/`v-else`**。
- `:250` 的 `data-testid="tag-form-cancelled"` 若被既有测试引用，须**先 grep 全部引用**（`frontend/tests/`）再决定删法；**既有 110 例（除 §6(d) 两条）不得变红**。

---

## §6 测试锁（**GLM §二-6 采纳**；每条须 RED→GREEN 双向留证）

| # | 锁 | 内容 | 关键要求 |
|---|---|---|---|
| **(a)** | **D8 不重执行锁** | `mock executeFrontendTool` **计数**：重投 `frontend_tool_request` 对**已决** `toolCallId` ⇒ 执行器**不被二次调用**；`suspended` 重投同理（**遍历 `toolCalls[]` 口径**） | 循 `frontend-tool-result-terminal.spec.ts:30`（`api` hoisted mock）/ `:52`（`reattachRun.mockImplementation`）范式 |
| **(b)** | **渲染面单卡锁（三路）** | 扩展 `ai-panel-parts-render.spec.ts`：**重投 / 新宿主 / 历史整替** 三路各加 `pending-card-*` **计数**断言（**单卡**） | 该文件现 **4 例**，单槽跟随断言在 `:92-110`；模板结构若动（§5）须先核对 |
| **(c)** | **历史整替路断言口径** | 「历史整替」路的断言**写清锁的是「单卡」而非「状态正确」** | **防与 D7(B) 口径打架**（整替后是重建的 `succeeded` 占位卡） |
| **(d)** | **D5 预期翻红两锁改写（同批双向留证）** | `parts-reducer.spec.ts:184`（「`suspended` 帧……未知 toolCallId **不建段**」）与 `parts-residue-cleanup.spec.ts:465`（同款写点③用例）⇒ **改写成「建段」语义**；新增「`suspended` 建段 → done 帧 ⇒ `expired`」 | **施工前红 / 施工后绿**双向留证 |
| **(e)** | **既有 110 例不红回归门** | 除 (d) 两条**预期翻红**外，既有 **110 例**（**11 spec 文件**）**全绿**；`yarn typecheck` 0 错、`yarn build` 绿 | 落稿棒静态核算 = 98 个 `it(` + `it.each` 12 行 = **110** ✔；施工棒 S0 复跑复核 |

**补强锁（终裁派生，均须留证）**：①**D10 轮次失配锁**：`stop()` 后执行器返回 ⇒ **段不被覆盖、POST 不发**；②**D11 单点弹锁**：`syncStepToBackend` 失败 ⇒ 仅 `warning` 一条（可 mock `ElMessage` 计数）；③**D12 DEV 门锁**：非 DEV 分支不 `console.error`（可用 `vi.stubEnv` 或断言函数形态）；④**D9 三对一致性锁**：表驱动断言两表 + 两枚举**成员集合一致**（`SUCCEEDED` 差异**显式登记**为已知）。

---

## §7 CDP 冒烟要求（**强制**，D16 第 4 条）

```bash
# 前端 dev：仓内 vite 默认 5200 且 strictPort:true ⇒ 必须显式 5202
# 【脚本头口径（D13(B)）】复跑前必须重启 dev server（裸路径探针 /src/... 对 HMR 换版脆弱）
cd frontend && VITE_DEV_PORT=5202 VITE_API_BASE=<后端地址> yarn dev
```

**强制覆盖（至少一项 + 配方留证）**：

1. **「重投后仍只有一张卡」**（**底线项，必须有截图**）：对同一 `toolCallId` **重投** `frontend_tool_request`（或走 re-attach 回放）⇒ 卡面**仍只有一张卡**、**不出现第二次执行**（执行器计数或页面副作用可观察）。
2. **可选顺带**：①E7（复制按钮位置）人工目检；②历史整替后的单卡计数（与 §6(c) 同口径：**锁单卡、不锁状态**）。
3. **证据**：截图（含时间戳）+ 触发配方（端口 / 地址 / 注入方式）写入施工报告；**注入方式若与配方不同须申报**。

---

## §8 红线、机械活下沉与台账纪律

### 8.1 报告落盘与必含项

**施工报告 = `<SCRATCH>/chat-timeline-pkg\施工报告-批3③b.md`**，必须包含：①**锚点对比表**（本卡 §10 为基线，逐条 PASS/FAIL + 实际行号）；②**各锁的 RED/GREEN 双向留证**（含 §6(d) 两条既有锁的改写前后）；③**CDP 冒烟截图与配方**（含**复跑前已重启 dev server** 的留证）；④**模板合并未做**的如实表述（D1(B)：`AiPanel.vue:179-288` 挂起卡与工具卡仍并列渲染）；⑤**已知代价申报**：`loadHistory` 整替丢真（A8/D7(B)，**注记正文见 §1 A8**）+ 未决双卡残余（A7/D6）+ 非帧路不在闸内（`restoreSuspendedSnapshot` / `applyFrontendToolTerminal` / `applyPendingTerminal`）；⑥**D8 契约前提三条**（后端不重发已终态 `toolCallId` / `toolCallId` 跨轮不复用（**依据未复验**，保留边界）/ 未来合法重试语义 ⇒ 闸须重议）；⑦**"被拦帧不走 `markApplied`"的职责分界**（§2 D8 第 3 条；实现取 `break`）；⑧**D5 前提申报**（「后端必发终态帧」**未实跑复验**）；⑨**偏差逐条申报** + **台账销记建议（C8 逐项点名）**。

### 8.2 范围红线（**③b 不许越界**）

1. **不触后端**（任何 `.java` / `application.yml` / 测试类）⇒ **不要求 `mvn`**；R-137 相关项（E5/E13）留在 R-137 批。
2. **不改 `utils/frontend-tools.ts`**（**红线无例外**；草案 §4.1 第 2 条的「例外候选 + D18 授权」**整条删除**）。③b 若在该文件发现新面 ⇒ **登记入报告**，不就地修。
3. **不重做 ③a 的红线**（原子提交 9 项已入库于 `f0210f2`）；③b 只在其之上做段状态机与死码。
4. **不销记「重挂已闭合」**：A9/A7 的保护面与旁路必须**如实申报**（③a 施工报告 §8 口径延续）。
5. **不触 `AiPanel.vue` 渲染区**（唯一例外 = §5 死码 tag，殿后）；**A1/A4 移交批 4④**。
6. **台账纪律**：销记须**逐项点名**（C8），不打包；**本批不动 `M2` 正文**（行尾注记由提交官办理，见 §8.5）。
7. **`stores/workspace.ts` / 后端 / R-137 面** 均**不在 ③b**。

### 8.3 机械活下沉条款（**明文**）

> 施工棒若把**机械活**（锚点复取、清单生成、扫描类核查、逐点计数核对、`git show` 对照等）下沉子智能体，**必须**：
> 1. **显式钉 `model=DeepSeek/deepseek-flash`** —— **禁靠会话默认**；
> 2. 子任务提示中写明用途与**只读**约束（零 git 写、不改仓库文件、不读 `.env`）；
> 3. **报告中自证实际模型**：若子智能体运行环境无法自省底层模型 ID，须以**父棒显式下发参数**为据自证并**如实写明该局限**（**不得**声称"已独立自证"，除非运行侧提供日志佐证）；
> 4. 关键锚点与关键结论须由**施工棒本体亲读**复核（不得只凭子报结论落卡）。

### 8.4 D8 落地后**仍不能销记「重挂已闭合」**的残余面（**六条逐条申报**）

1. **未决双卡面**：旧段 `pending`/`running` + 本端断流 re-attach 新宿主回放 ⇒ 同 `toolCallId` 两张**未决**卡（闸判终态、不拦；`parts.ts:77-80` 单消息查域 + `:90-103` 新建）；
2. **`loadHistory` 整替丢真**：本地 `unknown`/`failed` 被硬编码 `succeeded` 占位覆盖（A8/D7(B)，修复属 R-144）；
3. **非帧路不在闸内**：409 响应体 / 续收快照走 `applyFrontendToolTerminal`（`ai.ts:1749-1774`）/ `applyPendingTerminal`（`:1713-1728`），与闸语义同向但**覆盖面独立**；
4. **`stop()` 在途执行器**（R-142）：D10(A) 收窄"返回后写段 + 回灌"主面，但 `stop()` 与执行器返回之间的窗口内 `running` 段**已落**（`:1808-1815`），竞态窗口**残留**；
5. **D5(A) 建段后的生命周期**依赖**轮终态兜底**（取消竞态的 `suspended`-only 窗口：`SpToolCallingManager.java:163` / `:169`）；
6. **后端契约前提**：闸以「后端**不重发已终态 `toolCallId`**」为前提（UUID 唯一 + 回放仅 re-attach/续收场景）；未来后端若引入**合法重试语义**，闸会拦正当帧 ⇒ **须作为契约前提申报**。

### 8.5 台账与销记清单（**提交官办理；逐项点名，不打包**）

> **口径说明（D16 第 2 条）**：草案 §5.1 承接表的「**D15~D18**」区间表述**作废**，一律以本表「**D15** + **D16 边界裁决**」逐项点名；草案 A19/A20/A22 的 `D17`~`D20` 悬空引用**已全部改指 D16 边界裁决 / 本登记清单**（本卡无裸 `D17`~`D20`）。

| # | 项 | 施工报告须落的口径 |
|---|---|---|
| 1 | **③b 可销记** | **R-139①**（A2+D3 承接）、**R-139③**（A3+D2 承接）、**R-140①-1**（A4 **移交批 4④**，登记）、**R-140①-2**（A5+D5 承接）、**R-140①-4**（A6+D4 承接）、**R-141①**（A7+D6 **只闭合已决面**）、**R-141②**（A8+D7(B) **维持 + 注记 + R-144**）、**R-141③**（A9/A10+D8）、**R-141④**（A11+D9 **含误述订正**）、**R-142**（A12+D10）、**R-143①**（A13+D11）、**R-143②**（A15+D13 + 追加「(A) 已排查无应用内入口」）、**R-143③**（A14+D12）、**R-143④**（A16+D14）、**③a §12 M6**（A17+D15）——**逐项点名**，**不打包** |
| 2 | **R-143 行尾注记追加** | **M7/M8/M9（A18/A19/A20）移出 ③b、登记不修**（逐条点名 + 落点行号）；**D13(A) 无应用内入口**；**`runSeq` 跨会话残留**（`resetSession` `ai.ts:1376-1437` **未清 `runSeq`**） |
| 3 | **R-84 行尾注记** | **补登 `ExportWizardView.vue:1340`** ⇒ 同族面 **21→22**；**GLM §四-5 口径**：机制（`interpretHandlerOutcome` `:974-975` 折 `ok`）+ **判定词「未闭合」** + 后果（只读/越界拒绝时卡面显「已成功」）+ 去向（R-84 批结构化）+ **逐处行号**（§8.6） |
| 4 | **R-144 新立项** | **后端历史携带状态**（使 `loadHistory` 整替能还原真实终态）——出 ③b |
| 5 | **行号订正三处** | `parts.ts:250`（A21 终点）/ `types/ai.ts:341`（A6 `Pick` 位）/ `ImportWizardView.vue:1057`（A22） |
| 6 | **R-140①-5** | 复制按钮位置目视（E7）：③b 冒烟**可顺带目检**，**不列为施工项**；若目检即**未销记**如实写 |

### 8.6 同族「字符串⇒ok」谎报面逐处行号（**`505f6d4` 实读**；供 R-84 行尾注记直接落行）

- **导出侧 11 处**：`:1183` / `:1188` / `:1211` / `:1227` / `:1231` / `:1238` / `:1250-1251` / `:1258` / `:1261` / `:1267` / **`:1340`（补登）**。
- **导入侧 11 处**：`:1019` / `:1024` / `:1030-1032` / `:1040` / `:1055` / `:1063` / `:1066` / `:1072` / `:1075` / `:1081` / `:1084-1086`。
- **标注不计入**：`ImportWizardView.vue:1057`（**降级/指引形**，无失败声明语义；**计入登记并标注**，不与失败形混算）。
- **计数**：导出 11 + 导入 11 = **22**（**未闭合**，随 R-84 批结构化）。

---

## §9 交付物、文件互斥与风险

### 9.1 交付物清单

| 类 | 内容 |
|---|---|
| 代码 | `frontend/src/utils/parts.ts`、`frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue`（**仅 §5 死码删除**）、`frontend/src/views/ImportWizardView.vue`（A17/D15）、`frontend/src/views/ExportWizardView.vue`（A13/D11）—— **5 文件** |
| 测试 | 改写 `parts-reducer.spec.ts` / `parts-residue-cleanup.spec.ts`（§6(d)）；扩展 `frontend-tool-result-terminal.spec.ts`（§6(a)）/ `ai-panel-parts-render.spec.ts`（§6(b)）/ `export-wizard-step-outcome.spec.ts`（§6 补强①）/ `frontend-tool-outcome.spec.ts` |
| 证据 | 仓内三连日志、E2E 桩日志、**CDP 冒烟截图**（「重投后仍只有一张卡」）、T7 四例**反向留证**、锚点实读表 |
| 报告 | `施工报告-批3③b.md`（§8.1） |
| 仓外 | 冒烟脚本（若改脚本头口径）—— 仓外文件，**不入库** |

### 9.2 串行序（D16 第 4 条）

**③b → 批 4④（模板合并，含 A1/A4）→ 轮询治理一期**。③b 起手须确认**无同文件在飞窗口**（`stores/ai.ts` / `utils/parts.ts` / `AiPanel.vue` / 两向导）。

### 9.3 文件级互斥

| 文件 | ③b 触碰 | 同文件他批 | 处置 |
|---|---|---|---|
| `frontend/src/stores/ai.ts` | ✅ A2/A5/A7/A8/A9/A10/A11/A12 | 轮询治理一期（未开工）；R-137（无）；批 4④（无） | 串行；③b 先行（无前置） |
| `frontend/src/utils/parts.ts` | ✅ A5/A6/A9/A10/A11/A14 | ③a 已落（`f0210f2`） | 串行 |
| `frontend/src/components/AiPanel/AiPanel.vue` | ✅ **仅 §5 死码删除** | **批 4④（`A1`/`A4` 渲染区）** | **同文件不同区**；③b 只删 tag，**不触渲染结构**；批 4④ 紧随此后 |
| `frontend/src/views/ExportWizardView.vue` | ✅ A13（**不含 A18/A19 —— 已移出**） | R-84 批（台账指向，③b **不搭车**） | 须确认在飞窗口 |
| `frontend/src/views/ImportWizardView.vue` | ✅ A17 | 步骤条包①（已闭环）；轮询治理③（未开工） | 须确认在飞窗口 |
| `frontend/src/utils/frontend-tools.ts` | ❌ **不触（红线无例外）** | — | — |
| `frontend/tests/*.spec.ts` | ✅ 改写/扩展（§6） | 无 | — |

### 9.4 风险与反查点

| # | 风险 | 反查点 |
|---|---|---|
| R-a | **闸拦截面过宽**（吞终态帧副作用）⇒ ③a E17 回归锁被破 | X1 + §6(e)（`frontend-tool-result-terminal.spec.ts` 20 例全绿） |
| R-b | **闸漏拦**（`suspended` 帧单字段）⇒ rogue `failed + requiresAction` 旁路存活 | X2 + §6(a)（`suspended` 重投锁） |
| R-c | **先删 tag 后关旁路** ⇒ 丢渲染位 | D2 定序（§4 S1→S2→S3）+ §5.3 链约束 |
| R-d | **D5 建段**引入「永久 pending」 | X3（建段前过闸 + 双兜底 + `expired` 测试锁） |
| R-e | **错误销记**（「A7 已闭合」/「重挂已闭合」） | X5 + X15 + §8.4 六条 + §8.5 逐项点名 |
| R-f | **`loadHistory` 注记被下批误读** | X11 措辞本体（§1 A8 逐字入注释与报告） |
| R-g | **行号漂移**致施工打错位置 | S0① 锚点复取 + §10 基线表 + §8.1① |
| R-h | **D10 判据误用**（`sessionId`/`activeRoundHostId`） | X7 捕获点（入口 `controllerAtStart`） |
| R-i | **T7 反向留证做假**（只改夹具不改断言） | §6 D14 + 若变绿须重写断言（D14 推翻条件） |
| R-j | ③b 与批 4④ 对 `AiPanel.vue` 撞车 | §9.2 串行序 + §9.3（本批仅删 tag） |

---

## §10 锚点实读表（**落稿棒在 `505f6d4` 逐项抽验；施工棒 S0 须复取**）

| # | 锚点 | 结论 | 实读结果 |
|---|---|---|---|
| 1 | `stores/ai.ts:225-227` `sanitizeMirrorResidues` 守卫 | **PASS** | 函数 `:222-240`；守卫 `:225-227`（`if (message.done !== true) { continue }`）；`parts.forEach` `:228-237`（只收 `requiresAction`） |
| 2 | `stores/ai.ts:290-298` `writeMirror` 的 7 字段 | **PASS** | `:290-298` `id/role/content/parts/reasoning/toolRuns/pendingCall`，**无 `done`**；`:289` 注释「`appliedSeqs` 不入镜像」 |
| 3 | `stores/ai.ts:593-597` / `:601` 归一 + 调用 sanitize | **PASS** | `:593-597` `{...message, streaming:false, done:true}`；`:601` `const sanitized = sanitizeMirrorResidues(this.messages)`；`:604` `deriveProjections` |
| 4 | `stores/ai.ts:646-678` / `:658` / `:659` `loadHistory` 整替硬编码 | **PASS** | `:646` `map`；`:658` `kind2:'UNKNOWN'`；**`:659` `status:'succeeded'`**；`:662` `requiresAction:false`；`:676` `deriveProjections`；`:683` `await restoreSuspendedSnapshot()` |
| 5 | `stores/ai.ts:340-364` `frameStatusToPendingStatus` + `:347-349` 注释 | **PASS** | 函数 `:340-364`；`case 'CANCELLED'` `:346` + **注释 `:347-349`（3 行 run-cancel 依据）** + `return` `:350`；**无 `SUCCEEDED`** |
| 6 | `stores/ai.ts:388-392` `isFrameDecidedPendingStatus` | **PASS** | 7 态：`succeeded/rejected/blocked/expired/cancelled/unknown/failed`（`:389-391`）；文档区 `:366-387` 含 ③a D6(A) 保护面申报 |
| 7 | `stores/ai.ts:1210-1219` `hasDecidedPendingResidue` | **PASS** | `:1213-1218` `this.messages.some(...)`（**跨消息**）+ `isFrameDecidedPendingStatus(part.status)` |
| 8 | `stores/ai.ts:723-731` 非 PENDING 收敛 | **PASS** | `:723-726` 跳过 `PENDING`；`:727` `frameStatusToPendingStatus`；`:728` `hasDecidedPendingResidue` 守卫；`:729` `applyFrontendToolTerminal` |
| 9 | `stores/ai.ts:736-767` 重建路（含 `:748-752` 宿主、`:760-767` upsert） | **PASS** | `:737-742` 非 PENDING/已决跳过；`:748-752` 宿主优先持段消息；`:760-767` upsert `status:'pending'` + `requiresAction:true` |
| 10 | `stores/ai.ts:811`/`:883`/`:1037`/`:1428` `isCurrentRound` 四闭包 | **PASS（4/4）** | `:811`（`resumeSnapshotStream`）/ `:883`（`send`）/ `:1037`（`reattachActive`）= `currentAbort === controller`；`:1428`（`resetSession`）= `sessionId === nextId && messages.length === 0` |
| 11 | `stores/ai.ts:929-943` `send` finally | **PASS** | `:930-936` 仅当仍是本轮才复位 `loading`/`suspended`/`currentAbort`/`activeRoundHostId`；`:938-942` 只标宿主 `done`（**不清段**） |
| 12 | `stores/ai.ts:1022`/`:1023`/`:1035`/`:1046` re-attach 新宿主 | **PASS** | `:1022` `const lastSeq = this.runSeq[targetRunId]`；`:1023` `appendAssistantMessage('（正在重挂进行中的轮次…）')`；`:1035` `activeRoundHostId = assistant.id`；`:1046` `onFrame: (frame) => this.handleFrame(frame, assistant.id)` |
| 13 | `stores/ai.ts:1509-1516` seq 记账 | **PASS** | `:1510-1515` `runSeq[runId] = Math.max(...)` |
| 14 | `stores/ai.ts:1573-1578` `confirm_request` case | **PASS** | `:1574` `suspended = true`；`:1577` `applyFrameToParts`（**实找结论：无 residue 守卫**） |
| 15 | `stores/ai.ts:1587-1606` `frontend_tool_request` case | **PASS** | `:1588` 守卫 **带 GF 前缀**；`:1594` `suspended = true`；`:1595` `applyFrameToParts`；`:1598-1602` GF 支；**`:1605` `void this.runFrontendTool(message, frame)` 直跑** |
| 16 | `stores/ai.ts:1608-1626` 终态帧 case 副作用 | **PASS** | `:1616` `applyFrameToParts`；**`:1617-1625` activeForm 收敛**（`:1620-1622` 他端提交提示、`:1623` `disarmFormExpiry`、`:1624` `activeForm=null`）；**`:1626` `suspended = false`** |
| 17 | `stores/ai.ts:1629-1645` `suspended` case | **PASS** | `:1633` `frame.toolCalls.find(...)`（**复数数组**）；`:1635` 守卫带 GF 前缀；`:1640` `suspended = true`；`:1643` `applyFrameToParts` |
| 18 | `stores/ai.ts:1661` / `:1678` `expireResidueToolParts` | **PASS** | `:1661`（`done` 分支）/ `:1678`（`error` 分支）；函数定义 `:242-249` 区（`:245` 条件、`:246` 置 `expired`） |
| 19 | `stores/ai.ts:1697-1699` `markApplied` + `:1701-1703` `isTerminalFrame` | **PASS** | `:1697-1699`（`switch` **之后**，`break` 的帧仍会执行）；`:1701-1703` `if (isTerminalFrame(frame)) this.loading = false` |
| 20 | `stores/ai.ts:1713-1728` `applyPendingTerminal` / `:1749-1774` `applyFrontendToolTerminal` | **PASS** | `:1720-1723` 覆盖域**排除 `unknown`/`failed`**；`:1759-1764` 通用 FRONTEND 只收 `requiresAction`；`:1765-1771` GF 待决才写 status；`:1772` 已终态 first-wins |
| 21 | `stores/ai.ts:1800-1842` `runFrontendTool` | **PASS** | `:1808-1815` 开跑落 `running`（`createIfMissing=false`）；**`:1816-1821` `await executeFrontendTool(...)`（无守卫）**；`:1825-1827` 三态落卡；`:1829-1836` 写段；`:1838-1840` 降级 notice；**`:1841` `await this.submitFrontendToolResult(...)`** |
| 22 | `stores/ai.ts:1376-1437` `resetSession` **未清 `runSeq`** | **PASS** | `:1387-1388` 清 `currentAbort`/`activeRoundHostId`；`:1400-1412` 重置会话态；**全段无 `runSeq` 清理** |
| 23 | `utils/parts.ts:41` 「子包③」注释 | **PASS** | `:39-42` 注释原文「段级状态参与后续状态机（**子包③**）」 |
| 24 | `utils/parts.ts:77-80` / `:90-103` / `:98-100` | **PASS** | `:77-80` `findToolPart` **单消息域**；`:90-103` `upsertToolPart`（`:93-95` spread 合并）；`:98-100` `!createIfMissing` ⇒ return |
| 25 | `utils/parts.ts:106-127` `pendingStatusOf` | **PASS** | 显式 6 态 `:108-119`；`default` `:120-126` ⇒ `'pending'`（注释 `:121-124`：「unknown 仅为类型穷尽兜底」） |
| 26 | `utils/parts.ts:129-139` `assertUnknownTerminalInvariant` | **PASS** | 文档区 `:129-133`；函数体 `:134-139` **无条件 `console.error`，无 DEV 门** |
| 27 | `utils/parts.ts:196-205` X1 不变式 | **PASS** | `:196` `if (part.requiresAction)`；`:200-201` **只拦 `status === 'unknown'`** + dev 断言；`:203` `toPendingCallProjection` |
| 28 | `utils/parts.ts:232-250` `frontendResultStatus` | **PASS** | `:234-236` `FRONTEND_RESULT`/`SUCCEEDED → succeeded`；`:237-239` cancelled；`:240-241` expired；`:242-244` rejected；`:245-246` blocked；`:247-249` `default → null`；**`:250` 函数闭合**（草案 `:249` ⇒ **订正为 `:250`**） |
| 29 | `utils/parts.ts:359-372` `frontend_tool_request` 写段 | **PASS** | 无条件 `status:'pending'` + `requiresAction:true`；**无 first-wins**（经 `upsertToolPart` 合并） |
| 30 | `utils/parts.ts:383-386` `localTerminal` | **PASS** | 7 态：`failed/rejected/blocked/succeeded/expired/cancelled/unknown`；`:387` `fromFrame`；`:388-390` 三级优先 |
| 31 | `utils/parts.ts:402-416` `suspended` 支 | **PASS** | `:406` 只挑 `CONFIRM`/`FRONTEND`；`:408-413` `upsertToolPart(..., false)`（**`createIfMissing=false`**）；**不改 status** |
| 32 | `types/sse.ts:219-222` `SseSuspendedFrame` | **PASS** | `:219-226`：`type:'suspended'` + **`toolCalls: PendingToolCallSnapshot[]`** + `stateKey?`/`pendingKey?`/`writtenBy?`；**无单数 `toolCallId`** |
| 33 | `types/ai.ts:133-150` `AiHistory`/`AiHistoryMessage` | **PASS** | `:134-143` 字段 = `type`/`text`/`toolCalls?`/`toolResponses?`/`timestamp`（**无状态字段**）；`:146-150` = `sessionId`/`count`/`messages` |
| 34 | `types/ai.ts:341` `Pick`（镜像） | **PASS** | `:341` `messages: Array<Pick<ChatMessage, 'id' \| 'role' \| 'content' \| 'parts' \| 'reasoning' \| 'toolRuns' \| 'pendingCall'>>`（**不含 `done`**） |
| 35 | `AiPanel.vue:206` 死码 tag | **PASS** | `:205-206` `v-if kind==='CONFIRM'` 需确认 / `v-else` **自动** |
| 36 | `AiPanel.vue:213-232` 活分支 | **PASS** | `:213` `v-if kind==='CONFIRM' && status==='pending'` ⇒ 倒计时 `:214-219` / 拒绝原因 `:220-227` / 拒绝+确认按钮 `:228-231` |
| 37 | `AiPanel.vue:234-262` GF 分支 + `:250-256` | **PASS** | `:234` `v-else-if FRONTEND && GENERATIVE_FORM_TOOL`；`:238-247` `FormRenderer`；`:250-253` 终态 tag（**死码**）；**`:254-256` `activeForm` cancelled/submitted tag（活分支）** |
| 38 | `AiPanel.vue:258-260` GF 错误文案 | **PASS** | `:258-260`（`activeForm.status==='cancelled' && activeForm.error`）—— **A4 ⇒ 批 4④** |
| 39 | `AiPanel.vue:264-277` / `:280-286` 死码 tag | **PASS** | `:264` `v-else-if FRONTEND`（**链头挂 `:234`**）；`:265-277` 9 个 tag（含 `:277` `v-else`）；`:280-286`（含 `:285` `v-else`，**挂 `:264`**） |
| 40 | `AiPanel.vue:179-288` 两卡并列 | **PASS** | 工具卡块 `:179-196`（`:187-189` tag）+ 挂起卡块 `:198-287`（门控 `:201`）—— **收工报告必写项** |
| 41 | `utils/frontend-tools.ts:908-933` / `:921-925` | **PASS** | `:908` 函数头（路由 3 `ui_event`）；**`:921-925` `makeExecution({ outcome: outcome.handled ? 'ok' : 'failed', availability: 'available', … })`**；`:926-931` catch 支 `degraded` |
| 42 | `ExportWizardView.vue` 死码/双弹/调用点（A13/A19/A21） | **PASS** | `goStep` `:561-583`（`:566` `await persistStepData()`、`:570` `stepIndex.value = next`、`:576` `await syncStepToBackend()`、**`:579` `ElMessage.warning`**）；`syncStepToBackend` `:608-627`（**`:624` `ElMessage.error`**）；模板 `:14`；`:635` `void goStep(stepIndex.value + 1)`；`goStep(` 调用点 **5/5** = `:14`/`:635`/`:1169`/`:1192`/`:1284`；**`:708` `await persistStepData()`（返回值被丢弃，随后 `:711` `createJob`）**；A22 锚点 `:1182-1184`/`:1187-1189`；**`:1340`「本次未发起…的下载」** |
| 43 | `ImportWizardView.vue`（A15/A17 + 11 处） | **PASS** | `goStep` `:714`（`Promise<boolean>`）、`:731` `ElMessage.warning`；`syncStepToBackend` `:773` `ElMessage.error`；`import.nextStep` handler `:1016-1036`（`:1028` 取结局、`:1034` 回灌无警告）；**11 处行号见 §8.6**；`:1057` 降级/指引形 |
| 44 | `backend/.../gate/SpToolCallingManager.java` D5 前提 | **PASS（含 1 处行号订正）** | **`:163` `out.suspended(pendings, …)` 先发** ✔；循环内 `checkCancelled(runId)` **实读 `:169`**（另 `:192`；方法定义 `:211`）—— 终裁/GLM 引「`:172`」**偏差 3 行，实质结论不变** |
| 45 | `frontend/tests/parts-reducer.spec.ts:184` / `parts-residue-cleanup.spec.ts:465` | **PASS（2/2）** | `:184` `it('suspended 帧：只更新已存在的段（requiresAction 复位、status/seg 不变），未知 toolCallId 不建段'`；`:465` 同款（`写点③ suspended（createIfMissing=false）…未知 toolCallId 不建段`）⇒ **D5(A) 预期翻红** |
| 46 | `frontend/tests/frontend-tool-result-terminal.spec.ts:30`/`:52` mock 范式 | **PASS（2/2）** | `:30` `reattachRun: vi.fn()`（`vi.hoisted`）；`:52` `api.reattachRun.mockImplementation(() => new Promise(() => {}))` |
| 47 | `frontend/tests/frontend-tool-result-terminal.spec.ts:419-432` | **PASS（含标签订正）** | 实读 = **`describe('批3③a T3：hasDecidedPendingResidue 对 unknown / failed 段的拦截（D6(A)）')`**（文件共 434 行，此为末块）；**非 GF 前缀用例** ⇒ GF 路用例在 `:225`/`:253`；**既有测试中无直接覆盖 GF 前缀守卫者**（`hasDecidedPendingResidue` 仅出现于 `:419`/`:431`/`:432`）⇒ 去前缀后**须新增**回归留证 |
| 48 | `frontend/tests/` 清单与例数 | **PASS** | **11 个 spec**（`ai-panel-parts-render` / `export-wizard-step-outcome` / `frontend-tool-outcome` / `frontend-tool-result-terminal` / `parts-double-write` / `parts-reducer` / `parts-residue-cleanup` / `ready` / `restore-reconcile` / `sse-watchdog` / `tools-and-deadline`）+ `setup.ts`；`it(` **98** + `it.each` **12 行**（`tools-and-deadline.spec.ts:31`）= **110 例** ✔ |
| 49 | `frontend/vite.config.ts` dev 端口口径 | **PASS** | `DEFAULT_DEV_PORT = 5200` + `server.strictPort: true` + `port: devPort`（`VITE_DEV_PORT` 可覆盖）⇒ **必须显式 5202** |
| 50 | `docs/M2-排期计划.md` 台账行 | **PASS** | `:276` R-84（行尾注记含「本批未动…随 R-84 批一并结构化」）；`:289` R-97；`:326` R-137；`:327` R-138；`:328` R-139；`:329` R-140；`:330` R-141（**含④「后者缺 `CANCELLED` 依据注释」误述原文**）；`:331` R-142；`:332` R-143 |

**另核的支撑锚点（施工棒可直接引用）**：`stores/ai.ts:279-304`（`writeMirror`）、`:167-209`（`migrateMirror`）、`:444`（`runSeq` 声明）、`:800`（`runSeq` 写点）、`:1514`（同）、`utils/parts.ts:44-51`/`:69-74`（段边界）、`:120-127`（`default`）、`types/ai.ts:324`（`PendingToolCall` 8 态，**无 `unknown`/`failed`**）、`utils/frontend-tools.ts:970-989`（`interpretHandlerOutcome`，`:974-975` 字符串⇒`ok`）、`frontend/package.json`（`test` = `vitest run`）、`scripts/verify-e2e.ps1`（存在，**本批桩模式复用**）。

---

## §11 签字

| 项 | 值 |
|---|---|
| 定稿人 | **K3（代签，署名口径 DC-19）** |
| 签字日期 | **2026-10-12** |
| 授权来源 | **K3 指挥官 2026-10-12 三方会审汇总定稿指令** |
| 落稿执行子智能体 | **DeepSeek V4 Flash**（**模型显式钉版**；零施工、零 git 写、未读 `.env`、未改仓库文件） |
| 配套文档 | 《决策卡-批3③b.md》；草案《批3'③b-施工卡草案.md》（**仅存档**） |

> **落稿棒自证与局限**：本棒由父棒以 **`model=DeepSeek/deepseek-flash`** 显式钉版派发；运行环境**无法自省底层模型 ID**，仅据父棒下发参数声明 **执行者 = DeepSeek V4 Flash**（未做独立自证）。本棒**未跑** `yarn typecheck`/`yarn test`/`yarn build`/`verify-e2e.ps1`，**未启** dev server/后端/Redis，**未做** CDP 冒烟与注入实验；§10 全部结论为**静态实读**（含 §10-47/§10-44 两处**标签与行号订正**）。
