> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 仓外红队报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 红队报告 · 批 3' ③b（一卡全生命周期：段状态机收口 + 死亡代码清除）

## 署名头

| 项 | 值 |
|---|---|
| 执行者模型 | **DeepSeek V4 Pro**（反方审查 + 红队） |
| 授权来源 | **K3 指挥官红队卡（DC-19）** |
| 被审对象 | Kimi K2.8 Preview 施工、工作树未提交的批 3' ③b（10 文件 / +529 / -79） |
| 机械子智能体实际模型 | **DeepSeek/deepseek-flash**（父棒在 `Agent` 调用中**显式下发 `model=DeepSeek/deepseek-flash`**；运行环境无法自省底层模型 ID，此自证以父棒显式下发参数为据，**如实申报该局限**） |
| 关键锚点亲读 | 锚定哈希/字节/段数/逐文件指纹、`ai.ts` 全帧路 `:1515-1756`、`parts.ts` `:90-103`/`:378-443`、`AiPanel.vue` `:175-266`、`types/sse.ts` `:219-303`、`runFrontendTool` `:1852-1906`、CDP 截图 md5、RED/GREEN 日志计数、测试全量 —— **全部由父棒本体亲读/亲跑，非子报结论** |

---

## ② 锚定核验声明

**逐条比对结果（全部 PASS，未停工）**：

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| `diff-b3b.sorted.patch` SHA-256 | `8b7149bedbd2350f4641068ed9a95bce4eea13117abbc38bd2a51bc2a1eda0c1` | 同值 | ✅ |
| 字节数 | 58420 | 58420 | ✅ |
| `^diff --git` 段数 | 10 | 10 | ✅ |
| 仓库 HEAD | `505f6d4e0fa1360f04a3dbe3313842f1513a0952` | 同值 | ✅ |
| `git status --porcelain` | 恰 10 改 + 0 新 | 10 个 ` M`，0 个 `??` | ✅ |
| `.bak` SHA-256 | `756b660a…a7584603` | 同值（65566 字节） | ✅ |
| `.bak` vs 工作树同名文件 | 逐字节一致 | `cmp` = IDENTICAL | ✅ |

**夹带自核**：patch 内 `grep '\.env'` 命中 **3 处**，均为 `import.meta.env.DEV`（D12 DEV 门实现/注释），**非凭据文件**；无 `Binary files` / `GIT binary patch` 段；`^diff --git` 段路径恰为 §1 表的 10 个路径，**无第 11 段**。

**逐文件指纹（EOL 归一化 SHA-256）**：10/10 全部与 §1 表逐字一致（`AiPanel.vue` `b7ea26e4…`、`ai.ts` `41e15ab7…`、`parts.ts` `a8c287e7…`、`ExportWizardView.vue` `f5ad02cb…`、`ImportWizardView.vue` `060bc152…`、`ai-panel-parts-render.spec.ts` `2e5a6562…`、`export-wizard-step-outcome.spec.ts` `cdc3ca41…`、`frontend-tool-result-terminal.spec.ts` `48879350…`、`parts-reducer.spec.ts` `ce15dda3…`、`parts-residue-cleanup.spec.ts` `ef440afe…`）。字节数与行尾（两文件 CRLF、其余 LF）亦与 §1 表一致。

**声明**：
- ✅ **审查字节与锚定一致**。
- ✅ 生成器 `_anchor-diff-b3b.mjs` 可复现（本批无未跟踪新文件，`git diff` 本身即路径序输出；`diff-b3b.patch` 与 `.sorted.patch` 同 SHA/字节/段数，**非文件缺失**）。
- ⚠️ **局限申报**：③b 证据目录**无全量 diff 快照**（③a 有 `final-review-v2.diff`）⇒ 逐文件指纹表为主判据，`.bak` 单文件交叉核验为独立旁证。
- 审查全程**未发现工作树漂移**（指纹 + status + HEAD 三源一致）。

---

## ③ §3 必查面 ①~⑩ 逐项过程与结论

### ③-1 D8 闸实现正确性（最高优先）——【实测，本体亲读】

**① 守卫落点**：【实测】D8 三 case 守卫只在 `confirm_request :1615` / `frontend_tool_request :1636` / `suspended :1688`，均取 `break`；终态帧 case `frontend_tool_result :1656` / `confirm_decision :1624` **一字未动**（`frontend_tool_result` 的 activeForm 收敛 `:1665-1673` 与 `suspended=false :1674`、`confirm_decision` 的 `suspended=false :1628` 均在 D8 覆盖之外）。

**② suspended 遍历口径**：【实测】`suspended` case 读 `frame.toolCalls.find(call => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')`，判据钉在 `waiting.toolCallId`，**无单数字段误用**（`SseSuspendedFrame` 确为 `toolCalls[]` 复数，`types/sse.ts:219-226` 亲证）。

**③ break vs return 职责分界**：【实测】D8 三 case 取 `break`（seq 记账 `:1536-1541` 与 `markApplied :1749-1751` 照常）；D6(B) `:1569-1574` 取 `return`（见 ③-9）。

**④ 终态帧副作用完好**：【实测】`frontend_tool_result` 的 activeForm 收敛 + `suspended=false`、`confirm_decision` 的 `suspended=false` 均未被 D8 波及（D8 不覆盖这两型）。**构造攻击 (d)**（本端执行器先落终态、后端回执帧后到）：推演 —— 本端 `runFrontendTool` 已把段写 `succeeded`（已决），回执 `frontend_tool_result` 到达时 `findToolPart(当前消息, toolCallId)` = **true** ⇒ D6(B) 的 `!findToolPart` 条件**不成立** ⇒ 帧**不被丢弃**，走 `frontend_tool_result` case 正常收敛（first-wins 保 succeeded + activeForm 收敛 + suspended=false）。**GLM C1 反例不成立** —— 整帧丢弃只发生在跨消息新宿主场景，而该场景下 arming（activeForm/suspended）从未发生（见 ③-9③）。**结论：终态帧副作用完好，构造攻击 (d) 被 `!findToolPart` 条件天然化解。**

### ③-2 已决判据 7 态 + 契约前提三条 ——【实测 + 推断】

`isFrameDecidedPendingStatus :400-404` 7 态（succeeded/rejected/blocked/expired/cancelled/unknown/failed）亲证；`hasDecidedPendingResidue :1235-1243` 跨消息 `messages.some` 亲证。契约前提三条如实申报：① 后端不重发已终态 toolCallId；② toolCallId 跨轮不复用（**依据未复验**，报告 §6-2 如实标）；③ 未来合法重试 ⇒ 闸须重议。**§6b** D5 前提「后端必发终态帧」**未实跑复验**。**攻击点结论**：前提①②破裂时闸的**误拦方向**（正当帧不执行/漏建段）已在报告 §6-2 与 §9 裁决1 承认（「误杀面≈0」「前提破裂则漏写一张卡，不大于施工前」）；报告**未把【假设】偷升为【实测】**（②③ 与 D5 前提均明确标「未复验」）。此与前身 DSPro §2.3 边界口径一致。

### ③-3 D5 建段前跨消息过闸 ——【实测】

`parts.ts` `case 'suspended' :420-440`：`upsertToolPart(..., { status:'pending', requiresAction:true })`（**无 `createIfMissing=false`** ⇒ 默认 `true` 建段）亲证。**建段前过闸**：store 侧 `suspended :1688` 的 D8 守卫在 `applyFrameToParts` **之前**（`hasDecidedPendingResidue` 天然跨消息 ⇒ 他消息已决不建，防跨消息双段）——**过闸落在写段之前** ✅。

**攻击点（§7-add 自报）**：迟到 suspended 命中**未决非 pending 段**（`running` 的 CONFIRM/FRONTEND）时段 status 被降回 `pending`。推演：`running` ∉ 7 态 ⇒ D8 不拦 ⇒ `upsertToolPart` 就地合并 `status:'pending'` 覆盖 `running`。可达性依赖「suspended 先于 confirm_request/decision」帧序契约 —— 正常链路 suspended 先发，段仍 pending 或不存在，**不会命中 running**；违反序（后端乱序/异常重放）才可达。**后果**：若可达，是「挂起卡复活 + 用户可见」而非「瞬时摆动」（running 段被降 pending + requiresAction=true ⇒ pendingCall 复活）。**分级**：报告 §7-add 已自报并定性「D5 增修而非本批缺陷」；我裁定其**缓释前提（suspended 先发序契约）未列入 §6/§6b 契约前提清单**（见发现 O-8），自身不升为缺陷（序契约是既存后端保证，且与 D5 前提「必发终态帧」同源未复验）。

### ③-4 GF 前缀移除后回归留证 ——【实测 + 变异思想实验】

施工卡 §10-47 正确指出既有测试无 GF 前缀守卫直接覆盖 ⇒ 必须新增。**实测**：`frontend-tool-result-terminal.spec.ts` D8 describe 内含「GF 回归锁（去 GF 前缀后既有行为不变）：已决 GF toolCallId 重投不重新武装 activeForm」用例 —— **真断言**（`expect(store.activeForm).toBeNull()` + `expect(soleToolPart(assistant)).toMatchObject({ status:'cancelled', requiresAction:false })`），非「无异常即通过」。**变异思想实验**：去掉守卫后，重投 `frontend_tool_request` 会把 cancelled 段改回 pending + 重武装 activeForm ⇒ 该锁**变红**。✅ 回归留证非空转。

### ③-5 D10 捕获点实现 ——【实测】

`runFrontendTool` 入口 `const controllerAtStart = currentAbort`（`:1859`）→ await `executeFrontendTool` 后比对 `if (currentAbort !== controllerAtStart) return`（`:1883`）亲证。**判据用模块级 `currentAbort`，未误用 `this.sessionId` / `activeRoundHostId`**（后者是 `:811/:883/:1037/:1428` 四处不同局部闭包，`runFrontendTool` 无该作用域，卡面已列）。`:1808-1815`（施工前口径）开跑落 `running` 在 `:1864-1870`，**在 await 之前**，未被 D10 误改 ✅。**stop() 窗口残留**（stop 至本轮 finally 复位之间返回的执行器仍写段）在报告 §7-4 如实申报 ✅。**附加观察**：D10 测试锁用「重挂换句柄」制造失配，而非 stop()（见 O-7）。

### ③-6 死码删除是否误伤活分支 ——【实测，本体亲读 `AiPanel.vue :175-266`】

- **两排除项完好**：`:212-231`（CONFIRM 待决确认按钮 + 倒计时 + 拒绝原因 + 确认/拒绝双钮，`v-if="CONFIRM && pending"`）✅；`:251-253`（GF 提交窗口 `cancelled`/`submitted`/`v-else`）✅；`:255-257`（A4 GF 取消回灌失败文案）✅ 保留。
- **v-if/v-else-if 链完整**：`:212` `v-if` → `:233` `v-else-if`（FRONTEND&&GF），链头由 `v-else-if` 收拢为 `v-if`（`:251`），无悬空链头、无无主 `v-else`（删 `:264-277`/`:280-286` 两块后 `:233` 的 v-else-if 自然闭合）✅。
- **`data-testid="tag-form-cancelled"` 删除后引用复核**：【实测 grep】`frontend/tests/` **零引用** ✅；`scripts/m2t2a-smoke.mjs` 引用（`:53`/`:166`，B3 已登记）✅；**但 `docs/evidence/` 下另有 3 份历史证据文档引用**（见 O-6）——报告「仓内仅 scripts/m2t2a-smoke.mjs 引用」陈述失实（未限定「代码/测试」域）。
- **⚠️ 死码口径过宽（本项核心发现，见 M-2）**：删除的「自动」tag（header `v-else`）与 FRONTEND `pending`「待浏览器自动执行」tag（body）**并非「D8 关旁路后不可达」** —— D8 只拦**已决 toolCallId 重投**；首次投递的 FRONTEND 工具仍会**瞬态处于 pending**（`frontend_tool_request` 同步建段 + `:1645` deriveProjections ⇒ pendingCall=FRONTEND+pending，直到 `runFrontendTool` 微任务写 running）。终端态 tag（running/rejected/expired/cancelled/succeeded/blocked/approved）确不可达（terminal ⇒ requiresAction=false 不变式），但 `pending` 态**可达（亚 tick 瞬态）**。后果：瞬态窗口内挂起卡 body 由「待浏览器自动执行」变空。**分级 m**（影响亚 tick、可能不 paint，非功能性回归；但「不可达死码」的论证不精确）。

### ③-7 T1 生死线 20 例仍绿 ——【实测】

`yarn test tests/frontend-tool-result-terminal.spec.ts` → **30 tests passed**（= 20 例 T1 生死线 + 10 例新锁 D8×6/D10×1/D12×1/D9×2）。全量 `yarn test` → **11 files / 125 tests passed**，`yarn typecheck` → **0 错**。D6(B)/D8 未在同消息已持段的正常收敛（first-wins）路径误拦 —— 由「收窄面」用例 + T1 全绿佐证 ✅。**测试时段**：2026-10-12 ~02:40 本地，可能与并行 GLM 复核棒并发（vitest 不占端口，仅争 CPU）。

### ③-8 「重挂已闭合」未销记 + 残余面六条逐项申报 ——【实测 + 对照】

- 报告/代码注释中**未出现**「A7 已闭合」「重挂已闭合」或等价表述；§7-1 明写「不得表述 A7 已闭合，本批只闭合已决面」✅。
- **GLM §四-6 六条**逐项对账：①未决双卡面 → §7-1 ✅；②loadHistory 整替丢真 → §7-2 ✅；③非帧路不在闸内 → §7-3 ✅；④stop() 在途窗口 → §7-4 ✅；⑤D5 生命周期 → §7-5 ✅；⑥后端契约前提 → §6 ✅。**六条全部承接**。
- **DSPro §四-6 六条**逐项对账：①re-attach 空宿主消息 → §7-7(d) ✅；②loadHistory 丢真 → §7-2 ✅；③**appliedSeqs 跨宿主失效 → 未在 §7 单独列名**（折叠进 §6 职责分界，见 O-9）⚠️；④A20 无降级 notice → §8 台账「M7/M8/M9 移出、登记不修」✅（登记未修）；⑤A4 GF 渲染位 → §8 台账「R-140①-1 移交批4④」+ A22 → §8「R-84 行尾注记」✅（登记）；⑥runSeq 跨会话残留 → §8 台账「登记未修」✅。**结论：DSPro 六条中 5 条逐条承接，第 3 条（appliedSeqs 跨宿主失效）未单独列名，给 O 级**。

### ③-9 `runSeq` / `markApplied` 职责分界 ——【实测，本体亲读实序】

**实读顺序**：`runSeq` 记账 `:1536-1541` → 旧轮迟到帧 `return :1543-1553` → seq 幂等 `return :1559-1561` → **D6(B) 守卫 `return :1569-1574`** → `switch :1576` → `deriveProjections :1745` → **`markApplied :1749-1751`**。与出卡棒实读一致。

**① 申报缺口（本卡钉死攻击点，确认成立）**：报告 §6 职责分界只写「被拦帧实现取 **break** ⇒ seq 记账与 markApplied 照常」，**只覆盖 D8 三 case**；**D6(B) 的 `return` 形态未被申报** —— 而 `return` 会**跳过 `deriveProjections` 与 `markApplied`**。这是「报告陈述与实态不符」的候选：§6 的「被拦帧…照常」陈述把 D6(B) 一并隐含为 break，与实态（D6(B) 取 return）不符。**分级 m（申报缺口，非功能缺陷）**。

**② D6(B) return 是否致同 seq 重复投递不被 seq 幂等挡住**：【推断】是 —— `markApplied` 未记 ⇒ `alreadyApplied` 不短路 ⇒ 同 seq 重投每次过 seq 幂等再进 D6(B)。**但守卫自身幂等**：`hasDecidedPendingResidue && !findToolPart` 在重投时恒同真（该已决 id 不会被任何非守卫路径写进当前宿主，restoreSuspendedSnapshot 只建 PENDING 条目），**必然再被拦** ⇒ 无二次副作用。**结论：纯口径/perf 问题，非实质风险**。

**③ D6(B) 拦掉的 confirm_decision/frontend_tool_result 是否丢轮次级副作用**：【推断】构造可达性：D6(B) 只对**跨消息已决 + 当前宿主无段**的终态帧 `return`；而该 toolCallId 的 arming（`suspended=true` 三 case、`openGenerativeForm→activeForm`）对**同一已决 id** 均被 **D8 break 对称守卫** ⇒ 重挂/回放现场里该 id 从未武装 `suspended`/`activeForm` ⇒ `return` 跳过 `suspended=false`/activeForm 收敛**无对象可解除**。若同轮另有**未决** id 已武装 `suspended=true`，则该帧被拦后 `suspended` 保持 true **正是正确态**（那 id 仍待决）。**结论：无实际副作用丢失，属报告口径缺口，给 O 级**（X1/GLM C1 同族「整帧丢弃吞副作用」在本实现**不成立**）。

**④ `loading=false` 只在 done/error**：【实测复核】`isTerminalFrame`（`types/sse.ts:294-296`）只认 done/error；D6(B) 拦的 `tool_start`/`tool_result`/`frontend_tool_result`/`confirm_decision` 四型**均非终态帧** ⇒ 被拦帧**不涉及 loading 复位**，`loading=false` 只由 done/error case（D6(B) 不拦）触发。**结论确认**。

### ③-10 施工报告裁决与偏差对抗复核 ——【实测】

**计数说明**：派棒口径「4 项裁决」与报告不符 —— 报告 §9 **实为六条裁决 R1~R6**，§10 **五条偏差 B1~B5**。以报告原文为准逐条核：

- **R1 D6(B) 落点**（switch 前四型守卫、`return` 形态）：**本批最有价值攻击点**，已展开于 ③-9 —— 实现正确但 §6 申报缺 D6(B) return 口径（M-1）。
- **R2 D9 为加锁 export 两内部函数**：`frontendResultStatus`/`frameStatusToPendingStatus` 加 `export`，纯注解级面扩大，生产调用点不变 ✅，错价可接受。
- **R3 suspended 判据钉写面条目 toolCallId**：首条已决+后续未决组合里后续条目不建段（依赖其后 confirm_request/request 帧或快照重建兜底），见 O-5。
- **R4 D10 以重挂换句柄制造失配**：锁语义正确，但「回退时是否真会红」—— 变异（去掉 `:1883` 比对）后 D10 锁断言 `seg.status === 'running'` 与 `api.frontendToolResult` 未调用**会红** ✅；锁覆盖 = 代理（换句柄），非 stop() 直测（见 O-7）。
- **R5 导入侧越界分支一并结构化**：D15 字面未点名越界分支，属**字面授权外的顺带改动**（含新增 reason 文案），语义对齐导出侧同分支、handler 消费方式不变（`if (!advanced.ok)` 仍成立），无翻红面；报告 §9-5 已如实披露（见 O-11）。
- **R6 D11 锁只落导出侧**：仓内 `tests/` 确无 ImportWizardView 测试文件（`grep -rl ImportWizardView tests/` = NONE）✅；导入侧经 typecheck + 对称性保障，报告已申报。

- **B1** D10 落点换文件（outcome → result-terminal）：仓内确有 `frontend-tool-outcome.spec.ts`，但 D10 需 api mock + 可注入 executeFrontendTool 桩，result-terminal 具备 ⇒ 偏差合理 ✅。
- **B2** 重投路渲染锁 green-at-RED：RED 面由 result-terminal D8 用例承载，两处互证成立 ✅。
- **B3** `scripts/m2t2a-smoke.mjs` 断言 `cancelledTags === 2` 按设计失效仅登记：脚本不在 `yarn test`/`verify-e2e.ps1` 链路内，属里程碑 2 遗留；仅登记**是否足够** —— 我判**足够但须进台账**（报告 §8 未把 m2t2a-smoke 失效列入台账销记建议，仅 §10-③ 偏差登记），给 O 级（见 O-6 关联）。
- **B4** E2E 首轮空跑（27 空值失败）：非产品缺陷，留痕合理 ✅。
- **B5** 5202 旧进程（PID 3804）终止：服务于本仓 frontend，本批期间无其他批次在飞，终止**可接受**；「不重启则 HMR 假红」权衡正确 ✅。

**附加：CDP 两张截图 md5 相同**【实测】：`-baseline.png` 与 `-final.png` 均 `62564e665629720debc9dafd95f4ed0c`、各 51516 字节。根因【实测读脚本 `batch3b-lifecycle-smoke.mjs`】亲证：`shotBaseline` 在 `:176`（重投断言②③之后）、`shotFinal` 在 `:191`（suspended 重投断言⑥⑦之后），两次之间 UI 未变 ⇒ 字节相同逻辑自洽。**判**：命名「baseline」实为「重投后」态、误导（不能作 before/after 对比）；但「重投后仍只有一张卡」底线仍由截图 + CDP `result-*.json` 探针计数/段计数断言共同佐证，**证据有效但非对比性**；**建议补一张真正执行前 baseline 或改名**（见 O-10）。

---

## ④ §4 对抗推演记录

### 4-1 帧序竞态（六路 + D6(B)/D8 交叉）

① `suspended`(新 id) 单发 → 无终态帧（取消竞态）：D5 建 `pending` 占位段。**归宿**：轮终态 `expireResidueToolParts`（done/error）⇒ `expired`；无终态则挂到下次刷新由快照/镜像收口。**永久 pending 风险**由 §6b 前提「后端必发终态帧」兜底（未复验）。**结论：非永久 pending（有兜底），但前提未复验属已知边界。**

② `suspended` 单发 → 随后 `confirm_request`/`frontend_tool_request`：suspended 已建 pending 段，后续帧 `upsertToolPart` 就地合并（仍 pending + requiresAction=true），**不二次建段**（`upsertToolPart` 按 toolCallId 合并）。**结论：无二次建段。**

③ 同一 toolCallId 在新宿主重放终态帧（D6(B) 路径）：D6(B) `return`，新宿主**不建第二张卡**；轮次级副作用（suspended/activeForm）**不吞**（该 id 从未武装，见 ③-9③）。**结论：无第二卡、无吞副作用。**

④ 重投 `frontend_tool_request`（已决，不同 seq）：D8 `break`，**不二次执行**（不触发第二次 runFrontendTool）、不把终态段改回 pending。测试锁 (a) 亲证（executeFrontendTool 计数仍 1）。**结论：不复活挂起卡、不二次副作用。**

⑤ 重投（同 seq）：seq 幂等（`:1559`）**先于** D6(B)/D8 命中 ⇒ `return`，闸不再判。**结论：同 seq 由 seq 幂等短路；不同 seq 由闸兜底，两者互补。**

⑥ `suspended` 帧内 `toolCalls[]` 多个 CONFIRM/FRONTEND（部分已决+部分未决）：**非逐条遍历**，`find` 取首个 CONFIRM/FRONTEND 判已决。首条已决 ⇒ **整帧 break**，后续未决条目不在此帧建段 —— **依赖其后 confirm_request/frontend_tool_request 帧（正常链路必有）或快照重建**；「只发 suspended + 取消竞态 + 首条已决」角落会**静默丢弃后续未决条目**（见 O-5）。**结论：正常链路无丢；窄角落有静默丢弃，给 O 级。**

### 4-2 幂等面

`appliedSeqs` 消息级（`alreadyApplied`/`markApplied` 传 `message`，`types/ai.ts:216`、不入镜像）⇒ re-attach 新宿主 `appliedSeqs` 空 ⇒ seq 幂等**跨宿主失效**，靠 D6(B)/D8 判据兜底 —— 这与前身 DSPro §2.2 一致。`runSeq` 跨会话残留（`resetSession :1376-1437` 未清）⇒ 水位串味条件 = 后端复用 runId，仍成立（登记未修）。**四象限**：同 seq 同 id → seq 幂等短路（同宿主）；同 seq 不同 id → 正常各自处理；不同 seq 同 id → 闸判已决拦；不同 seq 不同 id → 正常。**结论：无新增幂等缺陷。**

### 4-3 D5 生命周期边界

占位段（pending）归宿：①轮终态帧 ⇒ `expireResidueToolParts` ⇒ `expired`；②快照恢复 ⇒ `restoreSuspendedSnapshot` 只重建 PENDING 且未决条目，已决不重建、非 PENDING 收敛；③历史整替 ⇒ `loadHistory` 整体替换 + 硬编码 `succeeded`（D7(B) 占位失真，R-144 承接）。**三条路归宿唯一且不冲突**：pending 段要么 expired（①）要么 succeeded（③整替）；快照路（②）不新增 pending（只 PENDING 且未决）。**结论：无「永久 pending 或被整替改写成 succeeded」的额外风险（后者即 D7(B) 已登记失真）。**

### 4-4 死码删除回归面

① `data-testid="tag-form-cancelled"` 仓外引用：`scripts/m2t2a-smoke.mjs` 的 `cancelledTags === 2` 断言按设计失效（该脚本不跑 ⇒ **静默假绿**，因不在 CI 链路，不会「假红」）—— 报告 §10-③ 已登记。② `pending-card-*` 既有渲染锁（`ai-panel-parts-render.spec.ts`）：链结构收拢后三路锁（重投/新宿主/历史整替）新增，既有 `:92-110` 单槽跟随断言不受影响（全量 125 绿）。**但** FRONTEND `pending` 瞬态可达（M-2），若未来有渲染锁断言该瞬态，「语义漂移」风险存在。③ 模板无主 `v-else`：Vue 编译期**未报错**（`yarn build`/typecheck 绿），链结构正确（`:212` v-if → `:233` v-else-if → `:251` v-if 收拢，无悬空）。

### 4-5 unknown/failed 段与闸交叉

`unknown`/`failed` 段重投挂起语义帧 ⇒ `hasDecidedPendingResidue` 7 态含二者 ⇒ **恒被拦**（first-wins 单调）。本地 `failed` 后用户/AI 合法重试同 id 场景**被锁死** —— 决策卡已裁「无合法重试通道」，代码/协议侧核无反例（`runFrontendTool` 无 retry、后端无合法重发同 id 语义），前提③「未来合法重试 ⇒ 闸须重议」已登记。**结论：无现有合法重试通道被误锁。**

---

## ⑤ §5 测试锁绕过检查（断言是否真锁死 + 变异体）

| 锁 | 能否被顺手优化绕过 | 变异体（改哪处应变红） | RED 日志是否红在预期断言 |
|---|---|---|---|
| (a) D8 不重执行锁 | 否 —— `executeFrontendTool` **真计数**（`toHaveBeenCalledTimes(1)`）+ suspended 重投覆盖**遍历 `toolCalls[]`**（failed 段 rogue 面） | 去掉 D8 任一 case 守卫 ⇒ 执行器二次调用/requiresAction 复活 ⇒ 红 | RED-pre 红在「执行器被二次调用」「requiresAction 复活」【实测，11 红含此】 |
| (b) 渲染面单卡锁（三路） | 否 —— `findAll('[data-testid^="pending-card-"]')` **计数断言**（`toHaveLength(0)`）+ 全消息域段数计数（`toHaveLength(1)`），非存在性断言 | 去掉 D6(B)/D8 ⇒ 回放建第二张卡 ⇒ `toHaveLength(1)` 红 | 新宿主路 RED 红在「回放建出第二张卡」【实测】 |
| (c) 历史整替路口径 | 「锁单卡不锁状态」已写清（`ai-panel-parts-render.spec.ts` 注释 + D7(B) 注记），不与 D7(B) 打架 | 若误加状态断言 ⇒ 与 D7(B) 占位 succeeded 冲突 | —（特征化锁，RED 轮即绿，如实申报） |
| (d) D5 两锁改写 | 否 —— `parts-reducer.spec.ts`（suspended 建段）+ `parts-residue-cleanup.spec.ts`（写点③建段 + 新增「建段→done⇒expired」）双向留证 | 改回 `createIfMissing=false` ⇒ 建段断言红 | RED-pre 11 红/114 绿对账一致；`expired` 新锁存在【实测】 |
| (e) 既有 110 例回归门 | 否 —— 施工后 **125 = 110 + 15** 逐条归因：result-terminal +10、ai-panel-render +3、parts-residue-cleanup +1、export-wizard-step-outcome +1、parts-reducer 0（1 改写） | — | 全量 125 绿【实测】 |
| 补强① D10 轮次失配锁 | 否 —— 断言 `seg.status==='running'` + `api.frontendToolResult` 未调用 | 去掉 `:1883` 比对 ⇒ 段被终态覆盖 + POST 发出 ⇒ 红 | RED 红在「段被覆盖+POST 发出」【实测】 |
| 补强② D11 单点弹锁 | 否 —— `ElMessage` **计数**（error 0 次 + warning 恰 1 次） | 恢复 syncStepToBackend 自弹 ⇒ error 被调 ⇒ 红 | RED 红在「error+warning 双弹」【实测】 |
| 补强③ D12 DEV 门锁 | 否 —— `vi.stubEnv('DEV', false)` + `console.error` spy 未调用 | 去掉 DEV 门 ⇒ 生产形态 console.error ⇒ 红 | RED 红在「生产形态仍 console.error」【实测】 |
| 补强④ D9 三对一致性锁 | 否 —— 表驱动直译两表 + 两枚举成员集合（`toHaveLength(7)` + sort 相等）+ SUCCEEDED 分叉显式登记 | 改任一翻译表 ⇒ 7 态/映射断言红 | RED 红在表漂移【实测】 |

**变异思想实验总结**：所有锁（含补强）均「改实现该处应变红」，无「无异常即通过」空转；RED-pre 日志 11 红均红在**预期断言**（非 import/环境错）—— 抽查 `RED-pre.log` 标准 vitest 输出「11 failed / 114 passed」对账一致。

---

## ⑥ 发现清单（分级 B/M/m/O）

> 口径：`B` BLOCKER / `M` MAJOR / `m` MINOR / `O` OBSERVATION。**本批无 B、无 M。** 相对复核报告：复核报告-批3③b.md **未落盘**（复核卡-批3③b-GLM.md 存在，报告未产出）⇒ 全部发现**无法标注「新增/复核确认」相对项，以「相对会审/决策卡」口径标注**。

### M-1（m）报告 §6 职责分界未申报 D6(B) `return` 形态（申报缺口）
- **锚点**：`ai.ts:1569-1574`（`return`）vs 报告 §6「被拦帧实现取 break ⇒ seq 记账与 markApplied 照常」。
- **证据**：D6(B) 取 `return`，跳过 `deriveProjections :1745` 与 `markApplied :1749-1751`；报告 §6 只写 D8 break 语义。
- **定性**：报告陈述与实态不符候选；经 ③-9②/③ 推演**无功能后果**（守卫自身幂等 + arming 被 D8 对称守卫），但属**申报缺口**，须在台账/报告补一句「D6(B) 取 return、跳过 markApplied/deriveProjections；其幂等由守卫条件自身保证、非 seq 幂等」。
- **相对会审**：前身 DSPro §2.2 已预警「被 D8 拦掉的帧不再走 markApplied、依赖已决判据兜底而非 seq 幂等」——**本项为前身预警在报告层面的未落地**。

### M-2（m）死码删除「D8 关旁路后不可达」口径过宽（FRONTEND pending 瞬态可达）
- **锚点**：`AiPanel.vue:251`（删后链头）及已删「自动」tag / FRONTEND `pending`「待浏览器自动执行」tag；`ai.ts:1642-1654`（frontend_tool_request 同步建段 + `:1645` deriveProjections 后 `void runFrontendTool`）。
- **证据**：D8 只拦已决 toolCallId 重投；首次投递 FRONTEND 工具的 pending 态（建段至 runFrontendTool 写 running 之间的微任务窗口）**可达**，非 D8 关旁路所致。终端态 tag 确不可达（terminal ⇒ requiresAction=false 不变式）。
- **定性**：影响亚 tick、可能不 paint（`await Promise.resolve()` 后 running 写与 Vue 批渲染同微任务周期），**非功能性回归**；但「不可达死码」论证不精确。
- **相对会审**：新增（会审/决策卡 §5 死码清单未区分「terminal 不可达」vs「pending 瞬态可达」）。

### M-3（m）D9 防漂移注记自身行号漂移
- **锚点**：`parts.ts:246` 与 `ai.ts:388-391` D9 注记引用「`localTerminal`（parts.ts:383-386 / 本文件 :383-386）」。
- **证据**：`git show HEAD:...parts.ts` 的 `const localTerminal` 在 **:383**；工作树在 **:401**（③b 自身新增 D9 注记 + D5 改动把其下移）。防漂移注记引用了**被自己改动**的行号。
- **定性**：注释行号失效，无运行时影响；但「防漂移注记自身漂移」讽刺性削弱其可信度。建议改为相对锚（如「`localTerminal`（本文件 `frontend_tool_result` case 内）」）。
- **相对会审**：新增（会审 C6 订正三处行号，未及此注记内部自引用）。

### O-4（O）D6(B) `return` 丢轮次级副作用 —— 推演后不可达
- **锚点**：`ai.ts:1628`（confirm_decision `suspended=false`）/ `:1665-1673`（activeForm 收敛）/ `:1674`（`suspended=false`）。
- **结论**：D6(B) 拦掉的终态帧其 arming 被 D8 对称守卫 ⇒ 无对象可解除，无实际丢失（详见 ③-9③）。
- **相对会审**：X1 / GLM C1 同族「整帧丢弃吞副作用」在本实现**不成立**。

### O-5（O）suspended D8 守卫「首条已决即整帧 break」，非逐条遍历
- **锚点**：`ai.ts:1686-1690`（`find` + 单判已决 + `break`）。
- **结论**：部分已决+部分未决批次的后续未决条目依赖其后帧/快照重建兜底；「只发 suspended + 取消 + 首条已决」窄角落静默丢弃（详见 4-1⑥）。
- **相对会审**：报告 §9-3 已承认，但补救全押「其后帧仍会建」，未覆盖「只发 suspended」角落。

### O-6（O）`tag-form-cancelled` 删除后 grep 结论不完整
- **锚点**：报告 §3「删前全仓 grep：仓内仅 scripts/m2t2a-smoke.mjs 引用」。
- **证据**：`docs/evidence/M2-T2b-施工与验证-K2.8.md`（×2）、`补档-CHATPANEL-施工报告-苞B.md`（×1）、`补档-CHATPANEL-施工卡.md`（×1）仍引用该 testid（均非可执行代码）。
- **定性**：应限定「代码/测试域」；m2t2a-smoke 失效（B3）报告 §8 台账未列入，仅 §10-③ 偏差登记，建议补进台账。

### O-7（O）D10 锁代理覆盖（重挂换句柄，非 stop()）
- **锚点**：`frontend-tool-result-terminal.spec.ts` D10 describe（`store.reattachActive('run-2')`）。
- **结论**：R-142 stop() 场景（stop→finally 窗口残留）**无直接测试锁**，锁覆盖 = 换句柄代理；报告 §9-4 已如实自报。

### O-8（O）D5 依赖的「suspended 先发」帧序契约未列入 §6/§6b 契约前提清单
- **锚点**：报告 §6（D8 三条）+ §6b（必发终态帧），均无「suspended 先于 confirm_request/decision」。
- **结论**：该序契约是 §7-add 自报「running 段被迟到 suspended 降 pending」的缓释前提，应显式列入契约前提（否则该降级面无前提声明支撑）。

### O-9（O）DSPro §四-6 残余面第 3 条（appliedSeqs 跨宿主失效）未在报告 §7 单独列名
- **锚点**：报告 §7 无「appliedSeqs 消息级幂等跨宿主失效」条目。
- **结论**：被折叠进 §6 职责分界；D6(B) 设计本身即其缓释，但作为「残余面」应逐条点名（其余 5 条 DSPro 均逐条承接）。

### O-10（O）CDP 截图「baseline」命名误导（实为重投后态）
- **锚点**：`批3b-evidence/cdp/*-baseline.png` md5 = `62564e…`（与 `-final.png` 相同，51516 字节）；脚本 `:176`/`:191` 时序。
- **结论**：不能作 before/after 对比；「重投后仍一张卡」底线仍由截图 + CDP result JSON 探针/段计数断言佐证，证据有效但非对比性；建议补执行前 baseline 或改名。

### O-11（O）R5 导入侧越界分支结构化超出 D15 字面授权
- **锚点**：`ImportWizardView.vue` `goStep` 越界/flushEditors 失败分支 `return {ok:false, reason:...}`（D15 字面仅「对齐导出 + syncWarning 回灌」）。
- **结论**：顺带改动，语义对齐导出、handler 消费不变，无翻红；报告 §9-5 已披露。

---

## ⑦ 总结论

**带条件 PASS**。

- **无 BLOCKER、无 MAJOR**。D8 三 case 守卫（break）+ D6(B) 店级守卫（return）的实现与终裁一致，终态帧 case 一字未动；D5 建段前跨消息过闸落点正确；D10/D11/D12/D9 落点正确；死码删除的**两排除项与 v-if/v-else-if 链完整**；「重挂已闭合」未销记、GLM/DSPro 残余面（除 DSPro #3 外）逐条承接。
- **实测**：全量 `yarn test` 125/125（11 files）、`yarn typecheck` 0 错；T1 生死线 20 例全绿；15 例增量逐条归因（110+15=125）；RED-pre 11 红/114 绿对账一致；CDP 截图 md5 与字节亲证。
- **条件（非代码改动，属报告/台账/注记补正）**：① 报告 §6 补 D6(B) `return` 形态申报（M-1）；② 死码清单区分「terminal 不可达」vs「FRONTEND pending 瞬态可达」（M-2）；③ D9 注记行号改相对锚（M-3）；④ `tag-form-cancelled` grep 结论限定「代码/测试域」+ m2t2a-smoke 失效入台账（O-6）；⑤ 契约前提清单补「suspended 先发」序（O-8）；⑥ 报告 §7 补列 DSPro #3 残余面（O-9）；⑦ CDP baseline 改名或补执行前截图（O-10）。
- **未执行声明**：未跑 CDP/E2E 全链（§6 授权由复核棒承担）、未跑 mvn（③b 不含后端）；以上结论为**静态实读 + 定向/全量 vitest + typecheck** 的综合，分级结论与方法一致（未把【推断】写成【实测】）。

**最小反例（若须推翻，不存在）**：未能构造出「D8 误拦正当帧」「D6(B) 吞副作用」「D5 永久 pending」的可复现证伪链 —— 三者分别被 `!findToolPart` 条件、D8 对称守卫、expireResidue/快照双兜底化解。
