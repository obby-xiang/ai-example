> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 3③a 三方会审评议报告（DeepSeek V4 Pro），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 三方会审 · 批 3 ③a 施工方案评议报告（红队视角）

| 项 | 值 |
|---|---|
| 执行者 | **DeepSeek V4 Pro**（红队视角评议者：对抗性找茬——竞态/幂等/边界/回归面） |
| 授权来源 | Kimi K3 指挥官三方会审卡（2026-10-11，DC-19；最终裁决权在 K3，本报告为参谋输入） |
| 评议对象 | `批3③a-施工卡草案.md`（D1-D12 决策点 + A1-A14 条目） |
| 实读基线 | 仓库 `<MAIN_REPO>`，HEAD `3edaeb9`，工作树干净【实测】 |
| 纪律声明 | 零 git 写；未读 `.env`；静态审查，未跑 mvn、未起服务 |
| 模型自证 | 机械核验下沉 3 个子智能体，均以**显式 `model=DeepSeek/deepseek-flash`（DeepSeek V4 Flash）**下发，未靠会话默认。子智能体运行环境无法自省底层模型 ID（其系统提示不含模型标识、`env` 无模型变量），故以父棒下发参数为据；**GLM-5.3-Flash 不在本环境可用模型列表内，未发生互备切换**。本报告判断与整合由执行者（DS V4 Pro）完成。 |

---

## 〇、总判定（先行）

**带条件可施工 —— 不返工。** D1-D12 无一条需要整体推翻；D5/D6 方向正确但「一行修法」被草案高估了充分性，须补充三处结构性缺口处置或移交；D6 集合漏 `failed`；D7 有一个未定清的合并语义。详见 §一/§二，条件清单见 §四。

---

## 一、逐条评议表（D1-D12）

| # | 草案建议 | 判定 | 理由 / 代码锚点 |
|---|---|---|---|
| **D1** `ok` 语义 | (A) `ok === (outcome==='ok')`，unknown⇒ok:false，dev 断言钉死一致 | **同意 (A)** | 全仓 `execution.ok` 唯一读点 = `stores/ai.ts:1804`（`execution.ok ? 'succeeded' : 'failed'`），`execution.availability` 唯一读点 = `:1815`；`execution.outcome` 全仓**零读点**【实测】。收窄语义的爆炸半径 = 1 行，风险最低。(B) 让未迁移读点今天就谎报成功，方向最差，(C) 留一致性债。**附注**：A8 落地后 `:1804` 改读 `outcome`，`ok` 将成死字段——应标 `@deprecated` 并接受「零读者」而非长期双真源。 |
| **D2** `unknown` 判据 | 「已发起/已受理但时限内无法确认真实结局」 | **同意** | 判据把 `unknown` 锁在「未确认」而非「不确定怎么做」，与卡面文案「未确认成功/失败」严格对应。①预算到期、②`:767-772` 等不到就绪均满足「已发起」；③ throw、④ `{ok:false}` 是确定性失败，落 `failed` 正确。**补一条**：A5 的 `open_export_file_editor` handler 返回失败形字符串（`ensureGridLoaded` 返回 `{ok:false}`，`ExportWizardView.vue:1236`）是**确定性失败**，应按 ④ 落 `failed`，不得误入 `unknown`——草案虽隐含此意，建议在施工卡显式点名。 |
| **D3** R-84 兼容口径 | (iii) 本批只改导出向导失败分支，导入 10 处登记待批 | **同意 (iii)，但清单须订正** | 撞车约束（`ImportWizardView.vue` 被步骤条包①+轮询治理③两在飞批共用）成立，(iii) 是务实折中。**但「21 处失败形字符串」清单有误，须先订正再开工**：① `ExportWizardView.vue:1181` 非三元（只在 `taskId===null` 时返回单一「成功前缀+未落库」降级形，真三元在 :1190/:1193）；② `:1204` 是三元（成功/失败双支）；③ **漏列** `ImportWizardView.vue:1074-1076`（`publishJob.value ? 已启动 : 发布未启动`，三元）。∴ 「带成功支的三元/降级形」实为 **4 处**（:1056/:1065/:1204/:1074-1076），非草案 3 处；总失败形计数需复核。**耦合提示**：(iii) 与 A4（执行器返回类型放宽）+ A5（`defaultExecutor` 结构化透传）必须同一提交原子落地，否则 `open_export_file_editor` 的 `{ok:false}` 对象会被 `defaultExecutor:990` 的 `typeof outcome === 'string' ? ...` 折成成功兜底。 |
| **D4** A6 两落点 | (a) `:767-772`→unknown+ok:false；(b) `:774` 不改（合法成功） | **(a) 同意；(b) 同意（带一保留）** | (a) `:767-772` 触发条件确为 `waitForPageReady` 3s 超时（`pageReady===false`），文案「仍在加载…稍后重试」【实测】，与 `:758-766`（已就绪未绑任务=确定性失败）语义不同，`unknown` 正确且保留重试指引（R-c 满足）。**缺口：草案未钉死 `unknown` 分支的 `availability` 取值**——现 :767-772 为 `'available'`，若保持则 `ai.ts:1815` 的降级 notice 不触发；须明确是留 `'available'`（仅回灌文本传达）还是降 `'degraded'`（补 notice）。(b) `dispatchUiEvent`（`stores/workspace.ts:368-374`，非草案的 :392-427）三支中 `goto_step`/`select_defs`/`set_conditions` 的 `ok()` 均在 `await tasksApi.goToStep/selectDefs/setCondition` 成功返回后调用【实测】，`handled:true ⇒ 副作用已落地` 成立，`:774` 不改合理。**保留**：`goto_step` 支（workspace.ts:392-401）**无只读态守卫**而 `select_defs`/`set_conditions` 有（:408/:422），属 R-137 面，仅登记不归 ③a。 |
| **D5** localTerminal | (甲) 维持本地胜，localTerminal 补 `unknown`（一行） | **同意 (甲) 方向，但「一行」不足以收口（判定降级为「必须 + 须补结构性缺口」）** | 方向对：`frontend_tool_result` 帧的 `FRONTEND_RESULT` 支**只是回执**——后端 `ConfirmGate.java:484-491` 硬编码 `ok=true`、`SseChatEmitter.java:613-619` 取 `pending.getStatus()`，不含比前端已提交更多的真相【子智能体实测】；携带独立真相的只有 `FRONTEND_CANCELLED/CANCELLED/TIMEOUT/REJECTED_ARGUMENTS/BLOCKED` 五支。故 `unknown` 应为不可改写终态，(甲) 而非 (乙)（(乙) 会让后到回执把用户取消抹成成功，且须破既有测试断言）。**但**：`localTerminal` 的 first-wins 是**消息级**（`parts.ts:77-80`/`:357` `findToolPart(message, …)`），且 `unknown` 补进集合后仍有两条结构性路径失守（见 §二-3），草案「一行」表述高估了充分性。 |
| **D6** isFrameDecidedPendingStatus | (A) 纳入 `unknown` | **同意 (A) 且须一并纳入 `failed`** | `unknown` 是终态、纳入正确（防重投/回放复活挂起卡）。**但该集合现为 `succeeded/rejected/blocked/expired/cancelled`（`stores/ai.ts:374-377`），还缺 `failed`**：`failed` 同为终态，缺项导致 `hasDecidedPendingResidue`（`:1188-1204`，店级，草案标 :353-372 有误）对本地已 `failed` 的段返回 false ⇒ `frontend_tool_request`/`suspended` 的防重挂守卫（`:1573`/`:1620`，仅 GF）对 failed 段失效。这是与 A10 同族的**第二处遗漏**，D6 应改为「纳入 `unknown` **和** `failed`」，或显式登记 failed 缺项的去向。 |
| **D7** W-06 修法 | (A) 对齐导入蓝本 `goStep(): Promise<boolean>` | **同意 (A)，但须先定清一个合并语义（判定降级为「修正」）** | 对齐方向正确、成本最低。**但草案漏了一个未定清的合并语义**：导入蓝本 `ImportWizardView.vue:714-730` 只在 `flushEditors()` 失败时 `return false`，其 `syncStepToBackend()`（:727）**同样被 swallow**（:753-766 同款空 catch）——即「对齐导入蓝本」本身**并不**让 `syncStepToBackend` 失败可见。导出侧 `goStep` 有三个发后不管调用（`:551 persistStepData` / `:552 syncStepToBackend` / `:553 ensureStepLoaded`），D7(A) 说「失败不推进界面」与 D8(A) 的「不阻塞界面（下次切步会重试）」存在张力：**若 `syncStepToBackend` 失败就 `return false` 不推进界面，会把一次网络抖动升级成向导卡死**，违背现有 `:590` 的可用性设计。建议钉死：`persistStepData`（本地落库）失败 ⇒ 阻断推进；`syncStepToBackend`（远端 currentStep）失败 ⇒ **推进界面 + 回灌文案附「步骤已推进但服务端步骤同步失败，下次切步会重试」警告**，而非阻断。`ensureStepLoaded` 不纳入结局（草案已意）。 |
| **D8** W-07 修法 | (A) 结构化失败 + 页面可见提示 + 供调用方读的返回值 | **同意 (A)** | (A) 保留「不阻塞界面」的可用性设计又补可见通道，正确。(B) throw 会把网络抖动升级成整轮 AI 动作失败（违背 :590 注释）；(C) 仍无人知。§5.2 的「任何非 2xx（含 R-137 守卫 4xx）都落结构化失败」口径必须落实。**附注**：回灌文案形状未在草案定义——W-07 的「步骤同步失败」若按 D7 附注走「推进+警告」，则回灌文本需有对应的自然语言措辞，建议随 D7 一并定稿。 |
| **D9** W-07 同型面 | (B) 登记待批（ImportWizardView 撞车面） | **同意 (B)，但同型面清单严重低估** | 撞车约束成立，defer 正确。**但草案只登记 `ImportWizardView.vue:753-766` 一处，实读同型面远不止**：两向导文件内**仅注释型空 catch 共 7 处**——`Export:413/589/723`、`Import:477/763/875/915`【子智能体实测全量扫描】，其中仅 `syncStepToBackend` 一对（`Export:589`/`Import:763`）落在 AI 回灌成败链上，其余 5 处为展示/降级路径（无回灌义务、危害低）。全仓另有仅注释型空 catch 10 处（`api/sse.ts:314`、`api/tasks.ts:110`、`SpreadGrid.vue:206`、`utils/excel.ts:432`、`stores/ai.ts:72/140/294/1321/1369/1382/1402`）+ `.catch(()=>[]/null)` 变体 5 处（`Export:401`/`Import:487`、`DataBrowserView.vue:332-333`、`workspace.ts:354`）。defer 前须**完整登记**这 7+10+5 处，否则「择机同批」的口径没有边界。 |
| **D10** toolTagType 去 default | (A) 做（可选，允许降 B） | **同意 (A)（低价值，可降 B）** | 收益仅「未来新增态编译期报错」，非缺陷修复，成本近零。**事实订正**：草案 A13 称「九态已全部显式覆盖」**不成立**——`AiPanel.vue:537-561` 显式 case 只覆盖 8 态，`pending` 靠 `default: 'primary'`（:558-559）兜底【实测】。去 default 的副作用 = 强制 `pending` 补显式 case，反而与「防漏」目标一致。 |
| **D11** 回灌前缀 | (A) 不加 | **同意 (A)** | 结局已有结构化通道（`outcome`），加机器前缀污染人可读文本、且属行为契约变更需单独评估提示词影响。台账 R-97 的「机器可读前缀」是后端 W-05 场景备选，不适用本批。 |
| **D12** CDP 冒烟 | (A) 强制覆盖 A2/A6（至少 A2） | **同意 (A)** | 本批价值全在卡面，而 CI（vitest/E2E）不驱动 DOM，CDP 冒烟+截图是唯一可证手段；批 1/批 2 已有先例。至少 A2（结局未知不被回执翻成成功）一条必须留证。 |

---

## 二、新增风险 / 遗漏（红队重点）

### 1. A5/A9/A10 的「同型面」还有未被捕捞的（实读结论）

- **A5 同型面 = 唯一，无第二处**：`defaultExecutor`（`frontend-tools.ts:981-994`）只服务 `open_export_file_editor`（全仓唯一显式 executor = `download_export_file` @ `ExportWizardView.vue:1256`），故「字符串透传→ok:true」的 A5 面仅此一家，**捕捞完整**。
- **A9/A10 同型面 = 至少 3 处未捕捞**（草案只列了 `localTerminal` 与 `isFrameDecidedPendingStatus` 两处终态集合字面量，实读确认「纯终态集合字面量」确实只有这 2 处；但**同族「非终态才可改写 / 无条件改写 / 展示态默认支」3 处遗漏**）：
  1. `stores/ai.ts:1704-1705` `applyPendingTerminal` 覆盖域 `pending || cancelled || requiresAction` —— `unknown + requiresAction=true` 会被改写成终态，属「非终态才可改写」的补集写法，应显式并入 `unknown`（及 `failed`）保护；
  2. `stores/ai.ts:246` `expireResidueToolParts` **无条件** `status:'expired'`（done/error 轮收尾）—— `unknown + requiresAction=true` 会被翻成 `expired`，且与 `:233 sanitizeMirrorResidues`（只降级 pending、保留 unknown）**口径不一致**；
  3. `utils/parts.ts:120-122` `pendingStatusOf` 默认支把 `unknown`（及 `failed`）映射回 `'pending'` —— 一旦出现 `unknown + requiresAction=true` 段，卡面复活为「待浏览器自动执行」，与终态语义相反。

### 2. outcome 三态 vs PendingToolCall 状态映射缺口

- **数量订正**：任务卡称「后端 PendingToolCall 10 态」，实读前端 `types/ai.ts:324` 联合为 **8 态**（`pending/running/approved/rejected/expired/cancelled/succeeded/blocked`），含 `approved`、**无 `unknown`、无 `failed`**【实测】。「10 态」实为后端常量表（另含 `TIMEOUT/FRONTEND_RESULT/FRONTEND_CANCELLED/REJECTED_ARGUMENTS` 等），经 `stores/ai.ts:333-357 frameStatusToPendingStatus` 折进前端 8 态。
- **真正的映射缺口不是「缺 unknown 枚举」**（`unknown` 是段终态、不进 pendingCall，草案 E15 定性正确），而是**「`unknown ⇒ requiresAction===false`」这个不变式无任何强制**：`deriveProjections`（`ai.ts:181-183`）仅凭 `requiresAction` 决定是否产 `pendingCall`，types 层只声明 `requiresAction: boolean`。当前**真空成立**（无生产者），但镜像残留 / 未来写入点漏置 false 即复活。建议：dev 断言或类型收窄（`unknown` 段禁用 `requiresAction=true`）。
- **附带分叉**：`parts.ts:210-228 frontendResultStatus` 与 `ai.ts:333-357 frameStatusToPendingStatus` 两个翻译表**穷举口径已分叉**（前者有 `SUCCEEDED`、后者缺；后者缺 `CANCELLED` 依据注释），非本批缺陷但应登记防漂移。

### 3. `unknown` 进入 localTerminal / isFrameDecidedPendingStatus 后的帧序竞态（重投/回放/续收快照 × unknown）

**核心结论：把 `unknown` 补进 `localTerminal` + `isFrameDecidedPendingStatus` 是「必要但不充分」。** 三路判定：

| 路径 | 不修（现草案前） | 修「一行」后 |
|---|---|---|
| (a) 重投·同消息（无 seq 帧重放） | `fromFrame`（`FRONTEND_RESULT→'succeeded'`）覆盖 unknown【假成功】 | 本地胜，稳 |
| (a') **re-attach 新宿主消息**（`stores/ai.ts:1008` 新建「正在重挂」助手消息，`lastSeq` 刷新后为空→服务端全量回放） | `findToolPart(新消息)` 找不到旧段 → `createIfMissing` 建**新 `succeeded` 卡**，旧 `unknown` 卡保留 → **两张矛盾卡** | **仍失守**（`findToolPart` 消息级，保护不到另一条消息） |
| (b) 回放·同宿主（`restoreSuspendedSnapshot` → `?lastSeq=0` 补发归档帧，宿主=已持段那条消息） | `findToolPart` 命中 → 覆盖 unknown | 本地胜，稳 |
| (b') **loadHistory 历史非空整替**（`stores/ai.ts:631-663`，工具段状态**硬编码 `'succeeded'` @ `:644`**） | 本地 unknown 段连段带状态被丢弃 → 换成 succeeded | **仍失守**（不经段级判定，直接换 messages） |
| (c) 续收快照 PENDING 条目 + 活帧 | 守卫失效 → 段写回 `pending+requiresAction=true`（`:745-752`）→ 活帧再落 succeeded（**unknown→pending→succeeded 两跳失真**） | 守卫命中 → `continue`，稳（除非另有一 PENDING 条目共享宿主，则同 a' 新建重复卡） |

⇒ **D5/D6 的「一行」修法不能宣称收口**。两条结构性缺口（re-attach 新宿主 `ai.ts:1008`、loadHistory 整替 `ai.ts:631-663/:644`）与 `localTerminal` 无关，`unknown` 在这两条路上仍会被翻成 `succeeded` 或产生重复卡。这两处属 ③b「一卡全生命周期」范畴（草案 §2 已划 `E8`「suspended 单独到达」去 ③b，但**未划**这两处），建议：③a 施工报告显式登记为已知代价移交 ③b，或在 ③a 内以「帧写路径先过 `hasDecidedPendingResidue`（店级）+ 历史段状态不硬编码 succeeded」补上。**无论如何不得静默留白**。

### 4. 取消竞态：本地乐观 `cancelled` × `unknown` 帧交叉

- 本地乐观 `cancelled` 的**唯一段写点** = GF 表单取消 `cancelGenerativeForm`（`ai.ts:1296-1306`）→ `applyPendingTerminal`（覆盖域 `:1705`）；`stop()`（`:1317-1342`）**不写任何 cancelled 段**。first-wins 保留 `cancelled`（有测试锁，`frontend-tool-result-terminal.spec.ts:195-211`）。
- **「先 unknown 后取消」在通用前端工具上不可达且不需覆盖**（通用工具无取消动作，后端 `ConfirmGate.cancel` 对已 `FRONTEND_RESULT` 条目不改状态）；**在 GF 上语义不自洽**：`unknown + requiresAction=false` 时取消**不**覆盖（副作用式成立），`unknown + requiresAction=true` 时取消**会**覆盖成 `cancelled`——同一状态两种结果，取决于正交字段 `requiresAction`。建议把 `unknown`/`failed` 显式并入 `applyPendingTerminal` 的保护域（与 §二-1 的①同源）。
- **附带残留**：`stop()` 之后在途的 `executeFrontendTool` 仍会返回并写段 + 回灌（`ai.ts:1798-1818`），`send` finally（`:923-927`）只置 `done` 不清段——「停止后仍写段」的残留面，单独登记。

---

## 三、落点抽验结论（A1-A14）

> 分级：**【实测】**=本会审子智能体逐行实读并摘录；**【推断】**=基于代码结构的合理推断。抽验覆盖任务卡要求的 A2/A3/A5/A6/A9/A10，另抽 A1/A4/A8/A13 佐证 D 判断。

| 条目 | 草案断言 | 抽验结论 | 分级 |
|---|---|---|---|
| A1 | 契约层无 outcome，仅 {ok/availability/result} | `types/tools.ts:114-120` 逐字一致，无 outcome | 【实测】确认 |
| A2 | timeoutOutcome 返回 ok:false+degraded、文案「结局未知」 | `frontend-tools.ts:411-426`，`:425` return、`:421/:423` 文案逐字一致 | 【实测】确认 |
| A3 | runPageHandler 字符串→ok:true 硬编码 | `:875-896`，`:885` ok:true，字符串/非字符串均 ok:true，仅 throw 落 failed | 【实测】确认（区间 :875-896，草案 :875-895 差 1 行） |
| A4 | runRegisteredTool :842 硬编码成功、执行器签名 string | `:808-850`，`:842`/`:69` 逐字一致；catch 在 `:843-849`（草案 :844-849 偏 1） | 【实测】确认 |
| A5 | defaultExecutor 把页面 handler 失败文案包成成功 | `:981-994` + `ExportWizardView.vue:1236` 失败文案 → `:842` ok:true → `ai.ts:1804` succeeded，链路成立 | 【实测】确认（关键新发现成立） |
| A6 | :767-772 ok:true 谎报；:774 合法；:750-756/:758-766 定性正确 | 四段 `:750-756/:758-766/:767-772/:774` 逐字确认；另 `:776-781` 契约拒绝支 ok:false+degraded（草案未列） | 【实测】确认（补充 :776-781） |
| A7 | 执行入口透传，须改 | `:315-322` **两分支**（timeout/settled，草案「三分支」表述有误）；settled 按引用透传（新字段不丢），**timeout 支新建 timeoutOutcome 对象、新字段必然缺失** | 【实测】确认（须同时改 A2 的 timeoutOutcome 才闭环） |
| A8 | 落卡二元、无 unknown 通道 | `ai.ts:1782-1819`，`:1804` 二元、`:1818` 回灌，`execution.outcome` 全仓零读点 | 【实测】确认 |
| A9 | localTerminal 缺 unknown ⇒ 回执帧翻成成功 | `parts.ts:351-375`，`:358-360` 6 态无 unknown、`:361-364` 帧胜分叉、`FRONTEND_RESULT→'succeeded'` | 【实测】确认（但「一行收口」不充分，见 §二-3） |
| A10 | isFrameDecidedPendingStatus 缺 unknown | `ai.ts:374-377` 5 态无 unknown；文档在 `:359-373`、`hasDecidedPendingResidue` 在 `:1188-1204`（草案锚点 :353-372 有误） | 【实测】确认（且缺 failed，见 D6） |
| A13 | 九态全部显式覆盖 | **不成立**：`AiPanel.vue:537-561` 显式 8 态，`pending` 走 default | 【实测】需修正 |
| — | 帧携带是否权威结局 | `FRONTEND_RESULT` 支 ok 硬编码 true（`ConfirmGate.java:484-491`），仅五支（cancel/timeout/reject/block）携带独立真相 | 【实测】子智能体读 Java 源码确认，支撑 D5(甲) |

---

## 四、总判定（带条件可施工 —— 条件清单）

**结论：带条件可施工，不返工。** D1-D12 无整体推翻项。开工前必须满足以下条件：

| 条件 | 内容 | 严重度 |
|---|---|---|
| **C1** | A9/A10 的「一行」修法不得表述为「收口」：须将两条结构性缺口（re-attach 新宿主 `ai.ts:1008`、loadHistory 整替 `ai.ts:631-663/:644` 硬编码 succeeded）与三处同型面（`ai.ts:1705` / `ai.ts:246` / `parts.ts:120-122`）在 ③a 内一并处置，或在施工报告**显式登记移交 ③b**并写明已知代价，不得静默留白 | 必做 |
| **C2** | D6 集合须一并纳入 `failed`（`ai.ts:374-377` 现缺），或显式登记 failed 缺项去向 | 必做 |
| **C3** | D7 须先钉死合并语义：`persistStepData` 失败阻断推进；`syncStepToBackend` 失败「推进+回灌警告」而非阻断（否则违背 :590 可用性设计、把网络抖动升级成向导卡死）；`ensureStepLoaded` 不纳入并注释 | 必做 |
| **C4** | D3 失败形字符串清单订正：补 `ImportWizardView.vue:1074-1076`、`ExportWizardView.vue:1204`；`:1181` 改判「非三元」；「三元/降级形」由 3 处更正为 4 处；总计数复核后再进附表 | 必做 |
| **C5** | D4(a) 钉死 `:767-772` 的 `availability` 取值（现 `'available'`），明确留 available 还是降 degraded（决定 :1815 notice 是否触发） | 必做 |
| **C6** | D9 同型面完整登记：两向导文件内空 catch 7 处（`Export:413/589/723`、`Import:477/763/875/915`）+ 全仓另 10 处 + `.catch(()=>[])` 5 处，defer 前列全清单定边界 | 建议 |
| **C7** | A13 事实订正：`pending` 走 default（非「九态全显式」）；去 default 后 `pending` 需补显式 case | 建议 |
| **C8** | 台账销记（R-f）与 R-139/R-140 逐项点名（含 §二-4 的「停止后仍写段」残留）不可打包 | 必做 |

---

## 五、异常申报

1. **草案「A9 是最高危、一行收口」的高危性判定我认可，但收口充分性被高估**：补 `unknown` 进 `localTerminal` 只覆盖「同消息帧回执」一路；re-attach 新宿主、loadHistory 整替两路与 `localTerminal` 无关，`unknown` 仍会被翻成 `succeeded`/产生重复卡（§二-3）。这三路对 `failed`/`cancelled` 是**存量同族**，但对 `unknown` 是本批**新引入**的可见回归面，草案 §2 E 表未划走这两路。
2. **`isFrameDecidedPendingStatus` 缺 `failed`**：草案 A10 只盯 `unknown`，漏了同为终态的 `failed` 缺项（D6/C2）。这是「已决不重挂」口径的既有缺口，③a 引入新终态时应一并收。
3. **任务卡「后端 PendingToolCall 10 态」与实读不符**：前端联合为 8 态（含 `approved`、无 `unknown`/`failed`），「10 态」是后端常量表；映射缺口的实质是「`unknown⇒requiresAction=false` 不变式无强制」而非「缺枚举」（§二-2）。
4. **草案锚点个别漂移**（相对 3edaeb9 现态）：`isFrameDecidedPendingStatus` 文档在 `ai.ts:359-373`、`hasDecidedPendingResidue` 在 `:1188-1204`（草案 :353-372）；`dispatchUiEvent` 在 `workspace.ts:368-374`（草案 :392-427，实际是 `runUiEvent` 三支）；`ensureGridLoaded` 定义在 `ExportWizardView.vue:776-804`（草案 E14 写 :1207-1240，那是消费 handler）；`waitForPageReady` 为 `:529-544`（草案 :529-546）。均为草案起草时的行号错位，不影响结论，施工棒 S0 已安排重取，可吸收。
5. **模型自证局限**：三个机械核验子智能体按其运行环境**无法自省底层模型 ID**（其系统提示与 `env` 均无模型标识），本报告以父棒**显式下发 `model=DeepSeek/deepseek-flash`** 为据自证；GLM-5.3-Flash 不在本环境可用模型列表，未作互备切换。若会审要求子智能体侧独立自证模型，需由运行侧（客户端配置/服务端路由）提供日志佐证，超出本棒能力。

---

*本报告为红队参谋输入，最终裁决权在 K3。落盘：`<SCRATCH>/chat-timeline-pkg/会审-批3③a-DSPro.md`。*
