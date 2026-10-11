> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 3③a 三方会审评议报告（GLM-5.3），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 三方会审 · 批 3 ③a 施工方案评议（GLM-5.3）

| 项 | 值 |
|---|---|
| 执行者 | **GLM-5.3**（复核视角评议者：行为基线对照、设计一致性、测试覆盖盲区） |
| 授权来源 | Kimi K3 指挥官三方会审卡（2026-10-11，DC-19；最终裁决权在 K3，本报告为参谋输入） |
| 评议对象 | `批3③a-施工卡草案.md`（380 行）+ K3 裁决草案 D1~D12 |
| 实读基线 | HEAD `3edaeb9`（亲测 `git rev-parse` = `3edaeb900dcc446b918913738a6f527c780e7b81`，工作树干净） |
| 纪律遵守 | 零 git 写；未读 `.env`；静态审查（未跑 yarn/mvn、未起服务）；机械核查下沉 3 个只读子智能体（均显式钉 `model=DeepSeek/deepseek-flash`，子报末行自证），关键锚点由 GLM-5.3 本体亲读复核 |

---

## 一、逐条评议表（D1~D12）

> 判定口径：**同意** / **同意+修正**（结论维持、论据或范围须订正）/ **反对**（须改判）。

| # | K3 裁决 | GLM 判定 | 评议（锚点均为 3edaeb9 实读） |
|---|---|---|---|
| D1 | (A) `ok === (outcome==='ok')`，unknown⇒ok:false，dev 断言钉死一致 | **同意+修正** | 实测 `execution.ok` 全仓唯一消费点 = `stores/ai.ts:1804`（三处 `execution.result`/`:1815` availability 与 ok 无关）⇒ (A) 迁移面极小、行为不劣化，方向正确。**修正①**：`utils/frontend-tools.ts` 内 `ok:` 生产点共 26 处（`:119~:891`，子核查①清单），dev 断言若逐点手写必漏——应改为**单一工厂函数**（如 `makeExecution(outcome, ...)` 收口 ok 的推导），26 处生产点全部改走工厂，断言只写一处。**修正②**：`:864` 的 `ok: outcome.handled` 是 dispatchUiEvent 路径，(A) 下须同步推导 outcome（handled:true 且副作用已 `await` 落地 ⇒ 'ok'；fail ⇒ 'failed'）——草案 D1 理由③ 引用了该点但未把它列入施工清单，须补。 |
| D2 | unknown 判据 =「已发起/已受理但时限内无法确认真实结局」 | **同意** | 判据把 unknown 限定在"未确认"而非"不确定怎么做"，与卡面文案「未确认成功/失败」严格对应，且与 D4(a) 的二分（确定性失败 vs 可能只是慢）自洽。补充一条边界确认：执行器 `throw`（`:844-849`/`:889-894` 均落 ok:false+degraded）维持落 `failed`，不进 unknown——正确，异常是确定性失败。 |
| D3 | (iii) 本批只改导出向导失败分支，导入登记待排期 | **同意+修正** | 折中方向正确（一次施工一次回归 + 过渡口径注释 + 缺口不静默）。**但登记清单有三处事实错，必须重列**：①导入侧失败形 return 实为 **11 处而非 10 处**——漏了 `ImportWizardView.vue:1074-1076`（`publishJob.value ? '发布作业 #… 已启动…' : '发布未启动'`，带成功支三元）；②`:1047`（"上传文件需要浏览器文件句柄：请提示用户点击…"）是降级/指引形，计不计入须定口径；③导出侧 `:1181` **不是**"带成功支的三元形"（它是单一模板串 return、主句成功语义+括注降级），真正的三元形是 `:1204`。登记表按错清单施工会漏改/错改。**另**：D3(iii) 的排期理由（ImportWizardView 两个在飞批次撞车）仓内**不可证实**——两视图自 `97711b5` 后零改动、工作树干净、单分支单工作树（子核查⑩）；撞车只可能来自仓外会话，请 K3 按仓外实况定夺是否借本批一次闭合。 |
| D4 | (a) `:767-772`→unknown+ok:false；(b) `:774` 不改 | **同意+修正** | (a) 证据强：`:758-766` 判据是"已就绪但任务没绑（再等也不会变）"=确定性失败；`:767-772` 触发条件是 `waitForPageReady` 3s 超时（`pageReady===false`）=可能只是慢。两者必须二分，同意 unknown。(b) 结论同意（`:774` = 无导航目标时回灌契约层 `outcome.message`，handled:true 意味着 `stores/workspace.ts` 的 `await tasksApi.*` 已落地 = 副作用真话），**但草案论据须订正**：三支中只有 `select_defs`/`set_conditions` 有只读守卫（`workspace.ts:407-409`/`:422-424`），**`goto_step`（`:392-401`）无只读守卫且总是真写**——只读态下 AI 的 goto_step 仍发 `PUT /step`，与手工路径 `viewStep`（零写）口径分叉。这不推翻 (b)（写已落地即真话），但它是 R-137 的 goto_step 守卫耦合点，施工报告必须申报（草案 §5.2 已部分覆盖，须落成条目）。 |
| D5 | (甲) 维持本地胜，localTerminal 补 unknown（一行） | **同意** | 亲读 `parts.ts:358-364`：localTerminal 六值（failed/rejected/blocked/succeeded/expired/cancelled），`:362-364` 三级优先（本地终态→帧 status→.ok 兜底）。补 unknown 一行 = unknown 段对回执帧 first-wins，正确。关键补强证据（子核查⑭）：`submitFrontendToolResult` 的 POST payload **不带 status/ok**（`types/ai.ts:66-78`：仅 runId/toolCallId/result/source/cancelled）⇒ 本地 unknown **没有任何后端通道**，后端对超时工具回发 `FRONTEND_RESULT` 帧（`frontendResultStatus` 映射→succeeded）⇒ **localTerminal 是 unknown 存活的唯一机制**。因此：(1) `parts.ts:355-356` 注释须把 unknown 写进口径（"含 unknown"）；(2) 对应测试锁（本地 unknown 遇帧不改写）是**生死线用例**，缺它任何人未来"顺手优化"成帧胜即全灭。非帧路 `applyFrontendToolTerminal`（`ai.ts:1731-1754`）对通用 FRONTEND 只收 requiresAction 不写 status ⇒ 与帧路无冲突，(甲) 全局自洽。 |
| D6 | (A) isFrameDecidedPendingStatus 纳入 unknown | **同意+修正** | 亲读 `ai.ts:374-377`：五态无 unknown（也无 failed——该函数口径=挂起侧终态，纳入 unknown 不制造新不对称）。**修正（保护面比草案宣称的窄，必须申报）**：两条帧路守卫都带 `GENERATIVE_FORM_TOOL` 前缀（`ai.ts:1573` / `:1620` 亲读确认）——**通用 FRONTEND 工具的重投 `frontend_tool_request` 帧现状就不受 `hasDecidedPendingResidue` 拦截**（`:1590` 直接 `void this.runFrontendTool(...)`）。故 D6(A) 实际只保护：①`restoreSuspendedSnapshot` 快照恢复路（`:713`/`:725`，全条目生效）；②GF 帧路。A10 描绘的"通用工具 unknown 段被重投帧复活"这一场景，D6(A) **修不掉**（与 unknown 无关的既有缺口，归 ③b 段状态机）。一行改动、方向正确、成本最低，同意；但施工报告须把保护面写准，不得宣称"重挂已闭合"。 |
| D7 | (A) 对齐导入蓝本 `goStep():Promise<boolean>`，ensureStepLoaded 不纳入并注释 | **同意+修正（范围与设计均须订正）** | 对齐仓内正解方向正确，但两处实质修正：**①调用方是 5 处不是 2 处**（子核查⑤全列）：模板 `:14`（`@prev`）、`nextStep():599`、`preflightDownload:1128`（`goStep(2)` 后**无条件** `return {ok:true}`）、`export.nextStep` handler `:1146`（W-06 撒谎点本体）、open-editor handler `:1225`。草案 A11 写"两个调用方（:551/:552）"是**把 goStep 体内的两个 void 调用误当成了外部调用方**——`:1128`/`:1225` 这两处若不取结局，W-06 在这两个入口**继续存在**，属草案漏掉的残余撒谎面；模板 `:14` 绑 async 函数须评估未处理拒绝。**②蓝本自身不闭合 W-07**：导入侧 `goStep:727` `await syncStepToBackend()`（内部 `:763` 空 catch 吞错）后照样 `return true`——蓝本只保证"步骤推进了"，不保证"服务端 currentStep 同步成功"。若导出侧只做字面对齐，会把 W-06 的谎报收窄但**复制** W-07 的静默。故 D7 必须与 D8 组合：`syncStepToBackend` 返回结构化结局（D8），`goStep` 的返回值 = `persistStepData` 结局 ∧ `syncStepToBackend` 结局的组合（persistStepData 现有 `:622` `ElMessage.error` 可见但同样返回 void，须一并改返回值）。 |
| D8 | (A) catch{}→结构化失败+页面可见提示+供调用方读的返回值 | **同意** | 亲读 `ExportWizardView.vue:587-591`：catch 体**零语句仅注释**，属实。修法 (A) 仓内已有现成范式：同文件 `persistStepData` 的 `:622` `ElMessage.error(...)`（ElMessage 已 import，全文件 30 处），无需新机制。与 R-137 的耦合按草案 §5.2 处置正确：任何非 2xx（含未来守卫 4xx）都落结构化失败，不得只覆盖网络错误。 |
| D9 | (B) 导入侧同型 catch{} 登记待批 | **同意+附升级条件** | `ImportWizardView.vue:761-765` 与导出侧逐字同款（子核查④实测）。维持 (B) 可接受，但注意：**"两在飞批次撞车"的依据仓内不可证实**（见 D3 评注：两视图自 97711b5 后零改动、单分支、工作树干净）——撞车表是体检报告的**规划面**，不是在飞实况。若 K3 确认当前无并行会话在写 `ImportWizardView.vue`，**升级 (A)（本批一并修）更优**：与 D3 的导入 11 处登记同文件，一次施工一次回归；且 D7 修正②已证明"蓝本不闭合 W-07"，导入侧自己的 `goStep:727` 也在等这个修法。维持 (B) 的条件：R-140 行尾列名 + 登记清单用**订正后的 11 处**。 |
| D10 | (A) toolTagType 去 default | **同意+实现注意** | 亲读确认 `AiPanel.vue:537-561` 九态显式覆盖、`default:'primary'` 在 `:558-559`。**注意**：default 当前兜的是 `'pending'`（非纯死码）——去 default 须给 pending 显式 case（或改 `Record<ToolRunStatus,…>`，与 `:563-579` `toolStatusLabel` 的 Record 形对齐，后者已是天然穷尽闸）。成本近零，同意。 |
| D11 | (A) 回灌文本不加机器前缀 | **同意（论据订正）** | 结论正确：结局已有结构化通道（outcome），文本嗅探是退化。但草案理由①引用"`types/tools.ts:118` 注释写明同时给模型和人看"**不实**——`:118` 原文仅「回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result）」，全文件无"给人看"表述（`:184` 的"展示文案+给模型看"属另一接口 WorkspaceActionRecord.summary）。论据须改为：payload 契约就是单字段 result，结构化结局走 `outcome` 字段而非文本编码。 |
| D12 | (A) CDP 冒烟强制覆盖 A2/A6（至少 A2） | **同意** | 本批全部价值在卡面，CI（vitest/E2E 桩）均不驱动 DOM 渲染层；批 1/批 2 已有 CDP 截图先例。"至少 A2"的降级底线合理。补充：A9 的"回执帧到达后不被翻成已成功"在冒烟里可通过等一拍（帧到达后）再截图验证。 |

**小结**：D1~D12 无一条须整体推翻；K3 裁决方向全部正确。但 **D3（清单三处事实错）、D6（保护面虚高）、D7（调用方 2→5 + 蓝本不闭合 W-07）三条的施工范围/论据必须订正后才能开工**，否则会带病施工（D7 尤甚：按草案范围施工会留下两个 W-06 残余入口）。

---

## 二、新增风险 / 遗漏（草案未列）

### 2.1 行为基线对照：批 2 与 unknown 的真实关系（草案叙述有两处归属错误）

1. **`unknown`/`cancelled` 是批 1（`1efab09`）落的，不是批 2**。`git log -L 165,165:types/ai.ts` 实测：6 态（b2b2ec5）→ 7 态（ad3ffb8，+blocked）→ **9 态（1efab09 批 1，+unknown+cancelled）**；批 2（6bea4ca）未改枚举行，只改注释。草案 A8「批 2 之后 'unknown' 已进 ToolRunStatus」、A14「状态枚举收敛已由批 2 实质完成」**归属错误**；会审任务卡里"批 2 已落地的状态枚举收敛（7 态）"同样不成立（无 7→8→9 收敛事件，是 7→9 一步到位且发生在批 1）。不改变施工内容，但施工报告的回归归因与"批 2 基线"叙述必须订正，否则下批对照会错。
2. **unknown 现状 = 死枚举成员 + 死 UI 分支**：全仓 `'unknown'` 字面量仅 2 处（`types/ai.ts:165` 定义、`AiPanel.vue:554` case + `:576` 标签键），**零生产者**（`frontendResultStatus` 对未知帧值返回 null 而非 unknown）。③a 是它第一次获得生产者——这就是为什么连带面（下条）必须一次钉全。

### 2.2 deriveProjections 投影层连锁面：草案漏了头号读点

`deriveProjections`（`parts.ts:157-189`）对 status 只做透传（`:175` `toolRuns[].status = part.status`，unknown 可直接透传，无风险）+ `pendingCall` 经 `pendingStatusOf` 派生。**真正的连锁缺口在 `pendingStatusOf`（`parts.ts:105-124`，亲读）**：

> `default:` 分支把 `pending/failed/unknown` 一律折成 `'pending'`（`:120-122`），而 `:121` 注释断言"unknown 无挂起语义"——**代码与注释不互锁**。`pendingCall` 的入口条件是 `part.requiresAction === true`（`:181`），所以 `status:'unknown' && requiresAction:true` 的段会被投影成 `pendingCall.status:'pending'` = **挂起卡复活**。

③a 主路径不可达（`runFrontendTool` 的 upsert 同时写 `requiresAction:false`，`ai.ts:1806`），但镜像恢复/未来任何写点一旦产出 `unknown + requiresAction:true` 的段，挂起卡即复活。**处置**：给 `pendingStatusOf` 的 unknown 加显式 case（返回值须定——因 `PendingToolCall['status']` 8 态无 unknown，最诚实的做法是 case 'unknown' 直接 `return 'pending'` 但配 dev 断言"unknown 段 requiresAction 必为 false"，或在该 case 里 console.error）；至少必须有测试锁。**这是 A9/A10 之外草案完全未列的第三处 unknown 连带读点**。

其余 status 读点核查结论（子核查⑧全清单）：与 unknown 语义相关的共四处——`pendingStatusOf`（上条，冲突）、`localTerminal`（A9 已列）、`isFrameDecidedPendingStatus`（A10 已列）、`applyFrontendToolTerminal`（只对 pending/cancelled 开写，unknown 段不被改写，安全）。`AiPanel.vue:265-284` 的 pendingCall 分支链无 unknown 分支——但 unknown 永远进不了 `PendingToolCall['status']`（被 pendingStatusOf 折掉），所以**靠的是运行时保证而非类型保证**，正是上条要加断言/测试的原因。

### 2.3 帧路守卫的 GENERATIVE_FORM_TOOL 前缀（D6 修正的展开）

`ai.ts:1573`/`:1620`（亲读）：通用 FRONTEND 工具的 `frontend_tool_request` 重投帧**现状就不经守卫**直接 `:1590 void runFrontendTool(...)`。A10 描绘的"重挂第二张卡"对通用工具是**与 unknown 无关的既有缺口**（归 ③b）；D6(A) 修的是快照恢复路 + GF 路。施工报告必须如实申报保护面，防"已闭合"的错误销记。

### 2.4 测试覆盖盲区（哪些新行为必须有 vitest 用例锁）

子核查⑥实况：9 spec / 93 例（与 R-138 留痕一致）；`frontend-tool-outcome.spec.ts` 不存在（真新增）；既有 5 个用例以字符串驱动 handler（兼容口径下不红）；**无任何"本地终态遇 FRONTEND_RESULT 帧"的组合用例**（R-140 ③ 同源缺口）。逐条点名必锁：

| # | 必锁行为 | 对应条目 | 草案是否已列 |
|---|---|---|---|
| T1 | 本地 `unknown` 段 + `frontend_tool_result{status:'FRONTEND_RESULT'}` 帧 ⇒ 段仍 unknown | A9（生死线，见 D5 评注） | ✅ 已列（S3-③） |
| T2 | `unknown` 段 `requiresAction` 恒 false / `pendingStatusOf` 不产 pending 挂起卡 | **本报告 2.2 新增** | ❌ 未列，须补 |
| T3 | `hasDecidedPendingResidue` 对 unknown 段拦截（快照恢复路可测） | A10 | ✅ 已列（S3-③ 后半） |
| T4 | 动作表 3s 等不到就绪 ⇒ `outcome:'unknown'`（fake timers + `advanceTimersByTimeAsync(3000)`） | A6/D4(a) | ✅ 已列 |
| T5 | handler 返回结构化 `{ok:false}` ⇒ failed；字符串 ⇒ ok（兼容）；`defaultExecutor` 透传结构化 | A3/A5 | ✅ 已列 |
| T6 | 执行器返回 `FrontendToolExecution` 结构化解读 | A4 | ✅ 已列（三路） |
| T7 | **`export.nextStep` handler + `tasksApi.goToStep` reject 注入 ⇒ 回灌含失败语义**（W-06 真锁） | A11/D7 | ⚠️ 草案 S4 只说"handler 级驱动即可"，**未点名失败注入**——不注入失败则 W-06 用例是空转，须补 |
| T8 | `ok`/`outcome` 一致性（若走 D1 修正①的工厂函数，即工厂纯函数单测） | D1 | ⚠️ 草案只说 dev 断言，未定实现形状 |
| T9 | 既有 5 个字符串驱动用例不红（回归门） | R-e | ✅ 已列（S0 grep 清点） |

### 2.5 其他

- **R-a 升级**：草案 R-a 说"A8/A9 必须同一提交"，正确且应扩为 **A8/A9/A10/T1-T3 同一提交**——unknown 一旦有生产者，三处读点缺一即回归。
- **模板未处理拒绝**（D7 修正①衍生）：`goStep` 变 async 后模板 `:14` 的绑定若内部 reject 会产生 UnhandledRejection；修法上 `goStep` 应**永不 reject**（失败也 resolve 布尔/结构体），须写进施工卡。
- **`docs/M2-排期计划.md:289`（R-97）"两个调用方"原文与实态 5 处不符**——台账订正时一并修，避免下批再按"2 处"施工。

---

## 三、A1~A14 落点抽验结论

抽验方式：3 个只读子智能体（model=DeepSeek/deepseek-flash 钉定）全量核验 + GLM-5.3 本体亲读 5 段核心锚点（`parts.ts:100-130`/`:350-370`、`ai.ts:370-381`/`:1565-1626`、`ExportWizardView.vue:538-595`）。

| 条目 | 草案核心断言 | 抽验结论 | 证据 |
|---|---|---|---|
| A2 | `timeoutOutcome :411-427`，`:425` ok:false+degraded，`:421/:423`「结局未知」文案 | **属实**【实测】 | 子核查⑤逐字命中（`:421` 下载类支 / `:423` 其余支各一次） |
| A3 | `runPageHandler :875-895`，`:885` ok:true，`:887` 字符串原样三元；catch 落 ok:false+degraded | **属实**（成功支区间微偏：实为 `:884-888`）【实测】 | 子核查⑥原文引用 |
| A5 | `defaultExecutor :982-994` 只判类型不判语义，失败形字符串被 `:842` 包成 ok:true；`open_export_file_editor` 无 executor 走默认 | **属实**【实测】 | 子核查⑧逐字命中 `:989/:990`；工具定义 `types/tools.ts:84-95` 无 executor 字段；`:1236` 失败文案实存 |
| A6 | 三处 ok:true 恰在 `:753/:768/:774`；`:767-772` 为 3s 超时谎报支；`:758-766` 已是正解 | **属实**【实测】 | 子核查⑨逐支引用原文（含触发条件链：`waitForTaskBound :581-582` 超时才回 pageReady:false）；`PAGE_READY_TIMEOUT_MS=3_000` 精确命中 `:517` |
| A9 | `localTerminal :358-360` 无 unknown ⇒ 回执帧把 unknown 翻成 succeeded | **属实**【实测·本体亲读】 | `parts.ts:357-364` 亲读：六值集合 + 三级优先（本地→帧→.ok）；`.ok` 兜底存在且草案对它的描述准确 |
| A10 | `isFrameDecidedPendingStatus :374-377` 五态无 unknown ⇒ 重投帧可复活挂起 | **属实但保护面须收窄**【实测·本体亲读】 | `ai.ts:374-377` 亲读五态确认；但 `:1573/:1620` 守卫带 GF 前缀（亲读），通用工具现状即不被拦（见 2.3） |
| A1 | 契约层无 outcome 载体 | **属实**【实测】 | `types/tools.ts:115-120` 亲读链（子核查①）；`result` 注释**不含"给人看"**（D11 论据须订正） |
| A4 | 执行器只能返回字符串；runRegisteredTool :842 硬编码成功；仓内无失败形字符串执行器 | **属实**（定性降级诚实）【实测】 | `:69` 签名逐字；`:842`/`:844-849` 逐字；唯一显式执行器 download_export_file 不返回失败声明 |
| A7 | executeFrontendTool :315-321 是唯一入口、须透传 outcome | **属实**【实测】 | 唯一调用方 `ai.ts:1798`；"三分支"实为二元三元 + BudgetOutcome 判别联合（`kind:'settled'|'timeout'`），改述即可 |
| A8 | `:1804` 三元落卡、`:1818` 回灌 POST、全仓零 unknown 生产者 | **属实**（"批 2 已进枚举"归属错，见 2.1）【实测】 | 子核查⑯/⑬ |
| A13 | toolTagType 九态全覆盖 + default 冗余 | **属实**（default 兜的是 pending，非纯死码）【实测】 | `AiPanel.vue:537-561`；`toolStatusLabel :563-579` 已是 Record |
| A14 | 批 2 已完成枚举收敛、本批只做回归锁 | **方向属实、归属须订正**【实测】 | 见 2.1：unknown/cancelled 系批 1 落地；"只做回归断言、不再扩张"的红线正确 |
| A11/A12 | goStep :545-554 发后不管；syncStepToBackend :587-591 空 catch；导入蓝本 :714-730 | **核心属实、两处订正**【实测·本体亲读】 | 亲读 `:545-554`（三 void）与 `:587-591`（空 catch）；**订正①** 调用方 5 处非 2 处（`:14/:599/:1128/:1146/:1225`）；**订正②** 蓝本 `:727` await 吞错的 syncStepToBackend 后返回 true，自身不闭合 W-07（见 D7 评注） |

**抽验总评**：14 条中 12 条核心断言属实、2 条属实但须订正范围/归属；草案的实读质量高（A5/A9/A10 三个新发现全部经独立复核成立），无虚构锚点。错漏集中在**计数与归属**（导入 10→11、调用方 2→5、批 2→批 1、:1181 三元、:118 注释），均属登记表级订正，不动摇方案骨架。

---

## 四、总判定

### **带条件可施工**

方案骨架（三态契约 + 六路由收口 + 落卡连带 + W-06/W-07 + 回归锁）成立，K3 十二条裁决方向全部正确，无一条须整体推翻。但以下 7 项条件未满足不得开工（前三项是**硬条件**，涉及施工范围错误）：

**硬条件（范围/设计错误，不订正会带病施工）**

1. **D7 范围重定**：`goStep` 调用方按 5 处全改（`ExportWizardView.vue:14/:599/:1128/:1146/:1225`）——`:1128`（preflightDownload）与 `:1225`（open-editor handler）两处现状"goStep(2) 后无条件成功回灌"是草案漏掉的 W-06 残余撒谎入口；模板绑定须保证 goStep 永不 reject。返回值设计 = `persistStepData` ∧ `syncStepToBackend` 结局组合（persistStepData 须一并改返回值），不得只字面对齐导入蓝本（蓝本不闭合 W-07）。
2. **新增必改项（本报告 2.2）**：`pendingStatusOf`（`parts.ts:105-124`）对 unknown 的显式处置 + dev 断言"unknown 段 requiresAction 恒 false" + 测试 T2。缺此条，unknown 一旦经任何非主路径产出 `requiresAction:true` 即挂起卡复活。
3. **D3/D9 登记清单按实读重列**：导入失败形 11 处（补 `:1074-1076`；`:1047` 定口径）；导出 `:1181` 非三元（三元=`:1204`）。错清单不得流人后续批次。

**条件（申报与配套，不阻塞开工但须落进施工卡）**

4. 施工报告订正两处归属：unknown/cancelled 枚举 = 批 1（1efab09）落地；"批 2 完成 7 态收敛"叙述不成立（历史 6→7→9）。
5. D6 落地时如实申报保护面限制（GF 前缀 ⇒ 通用工具重投拦截归 ③b），不得销记为"重挂已闭合"。
6. 测试锁补两条：T7（W-06 失败注入用例——不注入失败则用例空转）、T8（D1 一致性断言的实现形状建议走单一工厂函数，26 处 ok: 生产点收口）。
7. D4(b) 论据订正（goto_step 无只读守卫，`workspace.ts:392-401`）+ 与 R-137 的守卫耦合落成申报条目；台账 R-97 行内"两个调用方"一并订正为 5 处。

**红线重申（草案已列、本评议确认升级）**：A8/A9/A10 + T1~T3 必须同一提交（R-a 升级版）；`ImportWizardView.vue` 默认不动（D3(iii)+D9(B) 维持时），若 K3 确认仓外无在飞会话可按 D9 升级条件改判一次闭合。

---

## 五、异常申报

1. **执行链自证**：本报告执行者 = GLM-5.3（主会话本体，关键判断与整合）；机械核查下沉 3 个只读子智能体，均显式钉 `model=DeepSeek/deepseek-flash`（互备 GLM-5.3-Flash），各子报末行含模型自报。授权来源：Kimi K3 指挥官三方会审卡（2026-10-11，DC-19）。
2. **纪律遵守**：零 git 写（仅 `rev-parse`/`log`/`status` 只读）；未读任何 `.env`；未跑 yarn typecheck/test/build、未跑 mvn、未起服务、未做 CDP——全静态实读。
3. **对草案 7 条异常申报的复核**：第 3 条（A5/A9/A10 三新发现）**全部经独立复核成立**；第 4 条（W-07 导入同型面 `:753-766`）属实；第 5 条（W-06 蓝本）属实但**蓝本自身不闭合 W-07**（本报告 D7 修正②，草案未察）；第 6 条（A4 定性降级）属实；第 1/2 条（基线漂移、§5.1 与 D3 冲突）属实。
4. **本评议新发现的草案事实错**（均不推翻主结论，属登记表级）：导入失败形 10→**11** 处（漏 `:1074-1076`）；`goStep` 调用方 2→**5** 处（A11 把体内 void 调用误当外部调用方）；unknown/cancelled 枚举归属批 2→**批 1**；`:1181` 三元形定性错（实为 `:1204`）；`types/tools.ts:118` 注释"给人看"引用不实；"批 2 落地 7 态收敛"无仓库依据。
5. **仓内不可证实项**：D3/D9 的"ImportWizardView 两在飞批次撞车"依据在仓内无法证实（工作树干净、单分支单工作树、两视图自 `97711b5` 后零改动）——是否构成真实撞车取决于仓外会话状态，请 K3 定夺；本报告按"不可证实即保守"维持 (B) 默认并附升级条件。
6. **本评议的局限**：A10"通用工具重投帧复活挂起"的**运行时后果**为代码路径推断（未触发实跑）；`frontendResultStatus` 对超时工具的后端实发帧值依据 `parts.ts:194-206` 注释自述（红队批 2 §3.1 实测留痕），未在本轮重新抓帧验证。
