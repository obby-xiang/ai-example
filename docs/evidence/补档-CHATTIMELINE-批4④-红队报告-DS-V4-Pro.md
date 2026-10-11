> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 仓外红队报告（DS V4 Pro，结论带条件 PASS 0B/1 MAJOR-latent/5m/5O），署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 红队报告 · 批4④「折叠规则」对抗性审查（DS V4 Pro · 红队视角）

## 0. 首页署名（DC-19，实测非配置推定）

| 项 | 值 |
|---|---|
| 授权来源 | 本卡（K3 指挥官派单 · 批4④ 红队卡） |
| 实际执行模型 | `DeepSeek/deepseek-v4-pro`（wire 实测 modelAlias ×22） |
| CU 会话 id | `session_730c7cea-d26e-4ea7-b79b-50fb7b6dd137` |
| wire 路径 | `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_730c7cea-d26e-4ea7-b79b-50fb7b6dd137\agents\main\wire.jsonl` |
| 身份佐证（wire 实读命中） | 「红队任务卡 · 批4④」×6、「K3 指挥官派单」×6、锚定 hash `b3526474…` ×9 |
| model 键拼写 | `"model":"DeepSeek/deepseek-v4-pro"` ×21、`"model":"deepseek-v4-pro"` ×21、provider=`openai` ×21 |
| 机械活下沉子智能体 | agent-546，`model` 显式钉 `DeepSeek/deepseek-flash`；其 wire 自证 `"modelAlias":"DeepSeek/deepseek-flash"` ×15（非默认推定） |

- 纪律合规：主仓库**零写零 git 写**（只读 + 仓外 scratch 复现）；未读任何 `.env`；未触碰 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）`；未跑 mvn（本批零后端改动）。
- 并行复核方（GLM-5.3）与本棒互不见结论；本报告未引用其立场。

---

## 1. 总体结论：**带条件 PASS**（0 BLOCKER / 1 MAJOR-latent / 5 minor / 5 观察）

**攻破最高优先方向 ①（③b 闸）未遂**：A4' 写点形状严格 `{requiresAction:false, createIfMissing:false}`；批 4④ 新增代码（blocks.ts / expandedBlocks / setBlockExpanded / A4' reason 写点）没有任何路径把已决段重挂回 `requiresAction`；`hasDecidedPendingResidue` 6 调用点（:760/:772/:1600/:1644/:1665/:1717）与 `isFrameDecidedPendingStatus` 7 态集合零 diff。

**唯一 MAJOR 为折叠规则本体新缺陷（潜伏态）**：reasoning 段块 id 取 `reasoning-${seg}`，而 `seg` 是**逐消息**从 0 起的（`parts.ts nextSeg` 起始 -1），与单例全局 `expandedBlocks` 键空间冲突 → **跨消息展开态串味**。当前后端不产 reasoning 帧故潜伏，但该缺陷就在本批为 reasoning 专建的 A6'/D-C8 折叠特性里。另有 requiresAction 翻转引起组重并 + 键集合换代、死代码 `store.toggleBlock`、A4' 写点漏 `deriveProjections` 等 minor/观察（详见 §3）。

---

## 2. 攻击面清单（逐条）

> 证据分级：`【实测】`= 已读源码/已跑复现/已跑测试；`【推断】`= 由实测事实逻辑推得；`【假设】`= 尚未验证。未攻破项同样落列。

### ① ③b 闸完整性（最高优先）

**1-A · A4' upsert 写 reason 形状** —— 未攻破【实测】
路径：`stores/ai.ts:1370-1376` `this.upsertToolPart(host, { toolCallId, name, kind2, requiresAction: false, reason }, false)`。第三参 `createIfMissing=false`，`requiresAction` 显式 `false`，patch 不含 `status`（`upsertToolPart` 就地 spread 保留段上原 status）。与 D-C2 钉死形状逐字一致。

**1-B · 已决段重挂路径** —— 未攻破【实测】
批 4④ 全部新增写面：A4'（`requiresAction:false`）、`setBlockExpanded`（只写 `expandedBlocks`，不碰 parts）、`groupParts`（纯函数不改段）。无一能把已决段翻回 `requiresAction:true`。6 处闸调用点均属既有代码（frame 守卫 `confirm_request`/`frontend_tool_request`/`suspended`/`tool_*`/`confirm_decision` + `restoreSuspendedSnapshot` 双点），批 4④ 零触碰。

**1-C · hasDecidedPendingResidue 6 调用点零 diff** —— 未攻破（核验通过）【实测】
锚定 diff 中 `stores/ai.ts` 仅 4 个 hunk：`@@ -453`（expandedBlocks 声明）、`@@ -1350`（A4'）、`@@ -1431`（resetSession 清空）、`@@ -1835`（setBlockExpanded/toggleBlock）。四 hunk 均不覆盖 :760/:772/:1600/:1644/:1665/:1717；grep 实测 6 调用点行号与内容逐字不变，`isFrameDecidedPendingStatus`（:400-404）7 态集合（succeeded/rejected/blocked/expired/cancelled/unknown/failed）未动。

### ② 折叠规则边界攻击（groupParts / expandedBlocks）

**2-A · reasoning 块 id 跨消息碰撞（expandedBlocks 串味）** —— **攻破（MAJOR，潜伏）**【实测】
路径：`blocks.ts:30-31` reasoning → `reasoning-${seg}`；`parts.ts:29-31` `nextSeg = reduce(max seg,-1)+1` ⇒ 每条消息首段 seg 恒 0；`stores/ai.ts:463` `expandedBlocks` 为**单例全局** `Record<string,boolean>`；`AiPanel.vue:598` `isBlockExpanded` 读 `ai.expandedBlocks[block.id]`。仓外 scratch 复现（agent-546，逐字拷贝四纯函数）：两条消息各含 `reasoning@seg:0` → 两者 block.id **同为 `reasoning-0`**；对消息 A 展开（写 `expandedBlocks["reasoning-0"]=true`）后，`resolveBlockExpanded(blockB, …)` 返回 `true` —— **消息 A 的展开操作令消息 B 的 reasoning 组同步展开**。`expandedBlocks` 清空点仅 `resetSession`（:1462），无按 messageId 命名空间、无消息删除回收。当前后端不产 reasoning 帧（`types/ai.ts:232` 注释）故潜伏；一旦 reasoning 上线即爆发。

**2-B · requiresAction 翻转 → 组重并 + 键集合换代** —— 攻破（minor）【实测】
路径：D-C9 使 requiresAction 段独立成组（`blocks.ts:120-125`）。`[toolX(终态), toolA(requiresAction:true), toolY(终态)]` → 3 个 bare 组（id=`call_X`/`call_A`/`call_Y`）；toolA 决议后（requiresAction:false）→ 1 组 `[X,A,Y]`（id=`call_X`）。复现确认：键集合整体换代（旧键 `call_A`/`call_Y` 成孤儿、新键 `call_X` 之前未必存在），决议卡从独立卡重归组并继承组首展开态，已决卡可能被并入默认折叠组而"消失"。属 D-C9 隔离规则的连带，施工报告 R-1 只申报了"隔离规则"本身，未申报此键抖动/重归组代价。

**2-C · text 打断 / 空 text / 空组 / 全 text / 单成员 / unknown 段混入 / 复数 requiresAction** —— 未攻破【实测】
- 空组 `groupParts([])→[]`、全 text 逐段出 text 块、单成员 `bare=true`、复数 requiresAction 逐段独立成组（D-C9）：均正确（`blocks.ts:84-130`）。
- 空 text 段 `continue` 不打断组，与模板 `v-if="part.text"` 渲染真值一致。
- unknown 段混入：`MessagePart = TextPart | ReasoningPart | ToolPart`（`types/ai.ts:271` 封闭三成员判别联合），`!isActivityPart → continue` 分支对合法类型不可达，防御性死分支。

**2-D · expandedBlocks 三态状态机（手动收起后新帧 / reason 后到达）** —— 未攻破【实测】
`resolveBlockExpanded`（`blocks.ts:64-72`）判定序：requiresAction 强制展开 > manual 显式值 > defaultExpanded。手动收起后新帧（成员状态变化）不重开（D6② 用例锁）；reason 后到达且组未触碰 ⇒ defaultExpanded 升 true 自动展开（D-C1 用例锁）；已手动收起则 reason 不强制展开（D-C1 允许）。

### ③ A4' 文案链路

**3-A · reason 写点互覆（parts.ts:336/:372、migrateMirror:188 与新写点）** —— 未攻破【实测】
`parts.ts:336` 是 `tool_result`（BACKEND/CONFIRM 路径）、`:372` 是 `confirm_decision` rejected、`migrateMirror:188` 是 v1 镜像迁移；三者均不覆盖 `generative_form` 取消链（GF 走 `frontend_tool_result`，其 case 只写 status/result/requiresAction，不写 reason）。A4' reason 无同段竞争写点。

**3-B · 结构化前缀断言绕过 / notice 单槽挤掉 / auto=true 前缀路径** —— 未攻破【实测】
A4' 文案同源自 `form.error`（`ai.ts:1359`），`reason = form.error`（:1365），无第二处字面量可被绕过；落点 = 段 `reason` 位而非 `notice` 单槽（:1375），不挤占 notice；`auto=true` 前缀「表单已超时，」含于 `form.error` 再落入 reason，逐字保留。

**3-C · A4' 写点漏 deriveProjections → toolRuns[].reason 滞留** —— 攻破（minor，潜伏）【实测+推断】
`deriveProjections` 会把 `part.reason` 映射进 `toolRuns[].reason`（`parts.ts:199`）。A4' 写点（`ai.ts:1370-1376`）**未**调用 `deriveProjections(host)`（对比 `applyPendingTerminal`/`runFrontendTool`/`openGenerativeForm` 均调用）。后果：段 `reason` 已写、`host.toolRuns[i].reason` 仍为旧值。当前无 `toolRuns[].reason` 渲染消费方（模板读 parts），故潜伏；但违背「parts 唯一真值、投影由 deriveProjections 派生」的既定双写契约。

**3-D · A4' reason 只落首个宿主（messages.find）** —— 攻破（观察）【实测】
`ai.ts:1366` `this.messages.find(…)` 取**第一条**持该 toolCallId 的消息；而 `applyPendingTerminal`（:1351）广播**全部**消息。双卡/重投场景（m2t2a 同 toolCallId 双消息）下，用户从末条宿主卡取消，reason 却落在首条消息的卡上，与操作位错位。

### ④ 投影与段双写一致性

**4-A · formHostMessageId / 滚动指纹保留面** —— 未攻破（观察）【实测】
`formHostMessageId`（`AiPanel.vue:547-560`）读 `pendingCall` 投影，滚动指纹（:815）仍读 `last.pendingCall?.status`（投影）与 parts 混合。`pendingCall` 由 requiresAction 段派生、多数写点经 `applyPendingTerminal`/`deriveProjections` 同步，取消链后 pendingCall 为 null ⇒ 无错位窗口。但与「批2 改 parts 口径」注释的"无需读旧投影"表述留有缝隙（观察级）。

**4-B · A9'④ v1 镜像 expired+requiresAction 组合呈现** —— 未攻破【实测】
migrateMirror（:168-210）`requiresAction = pending.status==='pending'`；`sanitizeMirrorResidues`（:227-242）对 done 消息残留降级。段状态 tag `toolStatusLabel('expired')→「会话已失效」` 承接旧投影 tag（A9'④ 用例绿）。残留 requiresAction 段会强制展开（defaultExpanded ① 档），属视觉面，非闸缺陷。

### ⑤ 测试锁绕过

**5-A · 找一条行为变异全绿通过** —— **攻破**【实测】
2-A（reasoning 跨消息碰撞）与 2-B（requiresAction 翻转键换代）均为**真实行为缺陷，但 152 例全绿**。`blocks.spec.ts` 无跨消息用例、`resolveBlockExpanded` 用例一律传字面量 manual（不经 `expandedBlocks` 键查找）；面板 `expandedBlocks` 用例（`ai-panel-parts-render.spec.ts:379` 区）用 `g1/g2/run1/done1` **成对不重名自造 id**，天然规避碰撞。⇒ 三规则中「展开态三态」的跨消息/键换代面未被锁住。

**5-B · 三路单卡锁卡内容断言强度** —— 未攻破【实测】
G-C5 补了重投路内容断言（`probe.tool` 于 DOM，`ai-panel-parts-render.spec.ts:427-428`），计数口径未放宽（锁单卡仍锁 pending-card 计数）。

**5-C · RED 留证真实性 / 恒绿伪装** —— 未攻破（观察）【实测】
- 面板层 RED（`RED-s1.log`）为真实断言失败（工具名 2≠1、D-C9 0≠2、A9' 旧文案命中、A4' reason 缺失），夹具就位、红因=断言。A8' 恒真变异（`RED-a8p-mutant.diff`）命中重投/新宿主/历史整替 4 例，非恒绿。
- **纯函数层 blocks.spec.ts 的 RED = "导入缺失"**（`RED-s3.log`：`@/utils/blocks` 不存在），非行为变异红 ⇒ groupParts/defaultExpandedOf/resolveBlockExpanded 的"能抓真回归"未被证明。

### ⑥ m2t2a 改写

**6-A · 段态判据误伤/漏判** —— 未攻破（观察）【实测】
`cancelledSegments===2` 为硬计数，与脚本固定注入 2 段耦合（injectExpr 恒 n=2），改注入数即需改断言；`hangingSegments===0` 覆盖 requiresAction 残留。`cancelledStatusTags>=1` 全文档取法会命中任务中心 JobProgress 常量「已取消」（阶段1 基线 1，施工报告 R-5 已如实申报特异性局限）。

**6-B · SMOKE_SKIP_MODEL=1 退出码 0 断言真空** —— 未攻破【实测】
`m2t2a-smoke.mjs:320-327` 断言① 记 `{pass:null, skipped:true}` 不 push failures；②③③b 恒执行、恒经 `assert()` 计 failure；退出码 `failures.length===0?0:1`（:501）。**不存在"全 SKIP 也 0"路径**——②③③b 永不跳过。

### ⑦ 并发 / 时序

**7-A · 流式帧与展开态读写竞态** —— 未攻破【实测】
`groupParts` 每次渲染重算（`blocksOf`），block.id 取组首稳定 id；帧只 append、不改组首 ⇒ 流式期 id 稳定（工具侧）。manual 显式值优先于自动逻辑，晚到帧不覆盖用户手动态（D6② 双用例锁）。

**7-B · restoreSuspendedSnapshot 与折叠组重建** —— 未攻破【实测】
快照重建写 `requiresAction:true` 段（:792-799）⇒ defaultExpanded ① 档强制展开 + resolveBlockExpanded 强制展开，挂起卡直渲可见。

**7-C · migrateMirror expired+requiresAction 组合** —— 未攻破（见 4-B）【实测】

---

## 3. 发现清单（分级 + 修法建议）

### MAJOR（1）

**M-1 · reasoning 块 id `reasoning-${seg}` 跨消息碰撞 → expandedBlocks 串味**
- 证据：`blocks.ts:30-31` / `parts.ts:29-31` / `stores/ai.ts:463,1462` / `AiPanel.vue:598`；仓外复现输出（agent-546，`<SCRATCH>\chat-timeline-pkg\redteam-b4-scratch\redteam-b4-blockid-collision.mjs`）。
- 触发条件：reasoning 帧上线（当前后端不产，故潜伏）；届时任意两条消息的 reasoning 组共享同一键，一条的展开/收起联动另一条。
- 修法（建议，任选其一）：
  1. `stablePartId` 对 reasoning/text 段加消息命名空间前缀（`${message.id}:reasoning-${seg}`），需把 `groupParts` 签名扩为接收 messageId，或 block.id 由调用侧合成；
  2. `expandedBlocks` 改为 `Record<messageId, Record<blockId, boolean>>`（按消息分区）；
  3. 若团队裁决"reasoning 帧永不发出"，则降级为可接受风险并显式登记，但仍建议顺手加命名空间（成本极低）。

### minor（5）

**m-1 · `stores/ai.ts:1880` store action `toggleBlock` 为死代码**
- 证据：grep `\.toggleBlock(` / `ai.toggleBlock` 零命中；唯一写口是 `setBlockExpanded`（:1872，调用点 `AiPanel.vue:603` + 测试）。其实现 `!this.expandedBlocks[blockId]`（undefined→true）若被误接线会绕过 resolveBlockExpanded 的 requiresAction 强制展开。
- 修法：删除 `toggleBlock` action（或改为文档标注保留待用）。

**m-2 · A4' 写点漏 `deriveProjections`，`toolRuns[].reason` 投影滞留**
- 证据：`ai.ts:1370-1376` 未调 `deriveProjections(host)`；`parts.ts:199` 投影含 reason。当前无消费方（潜伏）。
- 修法：A4' 写段后补 `deriveProjections(host)`；或在 `ToolRun.reason` 已无消费方的前提下明确"reason 不入投影"契约并删投影字段，避免第三处写点再踩。

**m-3 · A4' reason 宿主选择 `messages.find`（首条）与 `applyPendingTerminal`（广播）不对称**
- 证据：`ai.ts:1366` vs `:1351/1794-1808`。
- 修法：与 formHostMessageId 同口径（末条持该挂起调用的宿主），或明确"reason 单落点"口径并注释。

**m-4 · requiresAction 翻转引发组重并 + expandedBlocks 键集合换代（决议卡重归组/可能被折叠）**
- 证据：`blocks.ts:120-125` + 复现输出（3 组↔1 组）。
- 修法：属 D-C9 连带；若判定为可接受 UX（终态卡并入活动组），需在施工卡/决策卡显式登记此键抖动代价（现仅 R-1 登记了"隔离规则"，未登记键换代）；否则决议后保留该段独立组直至轮终。

**m-5 · blocks.spec.ts 纯函数层缺真实 RED（现 RED=导入缺失），且无跨消息/键换代用例**
- 证据：`RED-s3.log` 为 `Failed to resolve import "@/utils/blocks"`；`blocks.spec.ts` 177 行无跨消息 reasoning 用例。
- 修法：补一条行为变异 RED（如把 `stablePartId` 的 reasoning 分支改成恒定串，证明某用例红）；补跨消息 reasoning 与 requiresAction 翻转用例（与 M-1/M-4 对应）。

### 观察（5）

- **O-1**：`groupParts` 的 `!isActivityPart → continue` 为不可达防御（MessagePart 封闭三成员），保留无害。
- **O-2**：滚动指纹（`AiPanel.vue:815`）仍读 `last.pendingCall`（投影），与"改 parts 口径"注释有残留；不影响正确性。
- **O-3**：`cancelledStatusTags` 全文档取法命中 JobProgress 常量「已取消」（基线 1），特异性局限已由施工报告 R-5 申报，与段态判据互补后可用。
- **O-4**：施工报告 §1 自述 `utils/blocks.ts`「新建 141 行」，与锚定（131 行）及实读不符，疑为笔误（函数行号 45/64/75/84 与实读一致）。
- **O-5**：`expandedBlocks` 无消息删除/会话切换（非 resetSession）时的回收点，仅 resetSession 清空；键滞留无害但属内存态卫生。

---

## 4. 锚定核验记录（SHA 逐文件比对）

| 对象 | 记录 hash | 我方重算 | 结果 |
|---|---|---|---|
| `diff-批4④-full.sorted.patch`（69971 B） | `b3526474e9d9e84c59859b15077ec1219959258c98a035100534c597d9767467` | 同 | ✅ |
| `frontend/src/components/AiPanel/AiPanel.vue` | `995846a912ecc248b6a89d57b81691b17b1ce5e772feb720bdf0866e8e623ae7` | 同 | ✅ |
| `frontend/src/stores/ai.ts` | `6d879adff54a3277ad1955c8a145694afa326aefdf2d16700a082b627e0c21a9` | 同 | ✅ |
| `frontend/tests/ai-panel-parts-render.spec.ts` | `e6e15582fbdec887b78bf4fc0acb58a8b4b6155805045ab52864ac5d4ce57e75` | 同 | ✅ |
| `scripts/m2t2a-smoke.mjs` | `07ed083d6279f093d760015014a45a623c1fa6b4390bf4c62b5368b705c80ce8` | 同 | ✅ |
| `frontend/src/utils/blocks.ts` | `3afd6262225597de5b699788af068109205650df16483887d49401790b99cd52` | 同 | ✅ |
| `frontend/tests/blocks.spec.ts` | `d045d8d29dce5198b516819d08c041df3875a014811c6e92212ce706d6d8e56a` | 同 | ✅ |

- HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（与锚定一致）；`git status --porcelain` = 4 ` M ` + 2 `??`（6 项，与锚定 `statusAfter` 一致）；`git diff --stat` = 4 files +530/−142（与锚定一致）。
- **结论：无夹带。6 文件 sha256 与记录逐条吻合，patch 全文 sha256 吻合。**
- 独立复验：测试套件 `yarn test` = **152 passed（12 files）**，与施工棒自述一致；运行后 `git status` 仍为原 6 项（仓外 scratch 未渗入）。

---

## 5. 改正条件（分级）

### 必做
- **无 BLOCKER 级必做项**。③b 闸零触碰、A4' 形状正确、锚定无夹带，均核验通过。

### 建议（按优先级）
1. **M-1（条件必做）**：reasoning 块 id 改全局唯一（消息命名空间 / 按消息分区 expandedBlocks）。**若 reasoning 帧上线则为必做**；上线前可暂缓但须显式登记裁决口径。
2. **m-4（建议）**：requiresAction 翻转的键换代代价在决策卡/施工卡显式登记，或调整"决议后保留独立组"。
3. **m-2（建议）**：A4' 写点补 `deriveProjections(host)`（或明确 reason 不入投影契约）。
4. **m-3（建议）**：A4' reason 宿主选择与 formHostMessageId 对齐。
5. **m-1（建议）**：删除死代码 `store.toggleBlock`。
6. **m-5（建议）**：blocks.spec.ts 补行为变异 RED + 跨消息/键换代用例。
7. **O-4（建议）**：施工报告 §1「141 行」订正为 131。

---

*报告完 · 批4④ 红队（DeepSeek V4 Pro）*
