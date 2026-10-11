> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 施工卡（定稿，完整替代草案；草案仅存档），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 「对话时序包 · 批 4④ 折叠规则」施工卡（**定稿**）

> ## ⚠️ 本稿替代草案，草案仅存档
>
> 本文件 = **批 4④ 施工卡定稿**，**完整替代** `<SCRATCH>\chat-timeline-pkg\批4④-施工卡草案.md`（485 行 / 83,334 B / sha256 `d6835ebcb0404c93d7f17e33020984e1efbc6a38914a0b4424ab851dc5a57e82`）。
> 草案**仅存档**，不得作为施工依据；本稿之外的任何行号/清单/口径**一律无效**。定稿依据 = 《决策卡-批4④.md》（K3 终裁 D1~D11 + X1~X11 + 会审条件处置总表 G-C1~G-C10 / D-C1~D-C11 + 未覆盖条目 U-1~U-11 + E-1~E-14 + R1~R10）。
> **本稿相对草案的订正（逐条，不得回退）**：① 改写范围 ③b → **③ + ③b**（G-C1）；② A4' RED 形态 =「**语境文案可见**」（G-C2）；③ `ToolPart.reason` 活写点 = **3 处**（G-C3）；④ 计数口径终稿（`pendingCall` **21 行/26 次**；`tag-form-cancelled` 代码域 **3 处** / `docs/evidence` **7 份 16 处**）（G-C4 + D-C4 + D-C11）；⑤ 测试基线 = **静态 `it(` 113**、运行门**锚实跑**（D-C3 + §附 A-1）；⑥ D8 理由按现态重写（D-C5）；⑦ `expandedBlocks` 类型 = **`Record<blockId, boolean>`**（D-C6）；⑧ `:206` / `:251-253` **直接删**、不设 `activeForm` 读取分支（D-C7）；⑨ `reasoning` 组**默认折叠**（D-C8）；⑩ 新增**多 `requiresAction` 段锁**（D-C9）；⑪ 新增**组强制展开判据「成员段 `reason` 非空」**（D-C1）；⑫ A4' patch 形状**钉死**（D-C2）；⑬ 新增**D11 文案钉死**；⑭ 单槽跟随位置断言 **`:106-115` 必改**（D-C10）；⑮ 另附 **A-4 锚点精度订正 15 项**。

| 项 | 值 |
|---|---|
| 文档定位 | **施工卡（定稿）**——施工棒的唯一作业依据 |
| 执行者 | **Kimi K3 指挥官**（**K3 代签**，署名口径 **DC-19**） |
| 落稿执行子智能体 | **`agent-540`**（Kimi Code 子智能体，CU 会话 `session_07374787-e888-4a2b-86d7-bd63b19ee73a`）；**实际运行模型 = `DeepSeek/deepseek-flash`**（wire 实证见《决策卡-批4④.md》§7；**非配置推定**） |
| 施工执行 | **待派施工棒**（机械活下沉条款见 §11.3：下沉子智能体**须显式钉 `model=DeepSeek/deepseek-flash`**，**禁靠会话默认**，报告自证实际模型） |
| 授权来源 | **K3 指挥官派单 · 批4④ 定稿卡**（会审输入 = 草案 485 行 + `会审-批4④-GLM.md` 180 行 + `会审-批4④-DSPro.md` 186 行） |
| 仓库（唯一可读取，红线） | **`<MAIN_REPO>`**（⚠️ **严禁** `<OTHER_REPO>（另一条线的仓，严禁触碰）`——无共同祖先的另一条线，任何误用即整批作废；**严禁** `<OTHER_REPO-2>（另一条线的仓，严禁触碰）`） |
| 实读基线 | **HEAD `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`**（`296ffe2`），分支 `main`，`git status --porcelain` **空**【落稿棒实测】 |
| 行号口径 | 本卡全部行号 = **`296ffe2` 工作树实读**（落稿棒逐条 `Read` / `grep -n` 亲验，见 §12）。③b 时期（`505f6d4`，`stores/ai.ts` 1844 行）与更早（`3edaeb9`）行号**已全线漂移**（最大位移：`runFrontendTool` `:1800` → **`:1852`**；`handleFrame` 整段 `:1151-1399` → **`:1517-1756`**）⇒ **凡引旧卡行号一律重锚** |
| 行号索引件（仓外，只作索引） | `<SCRATCH>\pkg-health\体检报告.md`（629 行；**§9 = 2026-10-12 批4④前置刷新注记** `:569-629`；刷新 114 处 / **失锚 3 处** / 时点 HEAD `296ffe2`）。**⚠️ 其中 3 处失锚点不得再作为施工锚**（见 §4 S0-⑤） |
| 工具链（钉死） | **node v24.21.0** / **yarn 1.22**（`frontend/package.json` `"test": "vitest run"`，`vitest 4.1.11`）；**JDK 21** 保留备用（**本批零后端改动 ⇒ 不要求 `mvn`**） |
| 前端 dev 端口 | **5202**（仓内 `frontend/vite.config.ts`：`:11` `DEFAULT_DEV_PORT = 5200`、`:16` `VITE_DEV_PORT` 覆盖、`:26` `strictPort: true` ⇒ 起 dev **必须**显式 `VITE_DEV_PORT=5202`，并配 `VITE_API_BASE` 指向后端） |
| 纪律（红线） | **零 git 写**（提交官统一提交）；**禁读任何 `.env`**；未获授权的仓库外文件一律不改；**不触后端**；**不触 `utils/frontend-tools.ts`（红线无例外）**；**不动 `types/sse.ts`**；**不改 `utils/parts.ts` 段模型语义** |
| 测试口径 | **TDD 双向留证**（照批 2 / ③a / ③b）：每个锁须「**施工前红 / 施工后绿**」**双向留证**并写入施工报告；**预期翻红**的既有用例须**同批改写**（§5 TS-3 / §6）。**RED 必须是"夹具就位仍红"**，不得是"夹具未就位而红"（③b D14 范式） |
| 报告落盘 | **`<SCRATCH>\chat-timeline-pkg\施工报告-批4④.md`**（必含项见 §11.1） |
| **批次证据目录（本批正式）** | **`<SCRATCH>\chat-timeline-pkg\evidence-p17\`**（含 `RED-*.log` / `GREEN-*.log` / `typecheck.log` / `vitest.log` / `E2E-stub-final.log` / `m2t2a-*.log` / `cdp/*.png` / `锚点实读表.md`） |
| 落盘 | 本卡 `<SCRATCH>\chat-timeline-pkg\批4④-施工卡.md` |

---

## §0 批 4④ 是什么（一句话口径 + 可观察验收）

> **批 4④ = 「折叠规则」纯前端渲染批**：把 `AiPanel` 消息体的**段分派**升级为**块分派**（新建 `frontend/src/utils/blocks.ts` 纯函数 `groupParts` + store `expandedBlocks` 展开态 + 受控折叠 UI），并在**同一次渲染区重构**中兑现 ③b 移交的两项（**A1' 挂起卡/工具卡模板合并**、**A4' GF 取消回灌失败文案渲染位**）与附带承接项（**A6' 受控折叠**、**A7'/A8'/A10' 测试与引用域口径**、**A9' 死位后模板收拢收尾**）。**不触后端、不触 `frontend/src/utils/frontend-tools.ts`（红线无例外）、不动 `frontend/src/types/sse.ts`、不改 `utils/parts.ts` 的段模型语义。**

**验收口径（可观察，逐条对应单测 / CDP 证据）**

| # | 验收点 | 判据（可观察） | 对应 | 证据面 |
|---|---|---|---|---|
| 1 | **块分派形态** | 一轮「正文 → 3 个连续工具 → 正文」在 DOM 中呈现为：正文段 → **一个可折叠的"3 个工具活动组"**（完成后折叠为一行摘要）→ 正文段；`[t,tool,tool,t]` 的**段序与块序一致**；**正文永不进组**（任何 Block 成员 `kind !== 'text'`） | A5' / D5 / D7 | §5 TS-1 + CDP「折叠组行为」 |
| 2 | **展开态承诺（三态）** | ① 用户**手动展开**某组后，后续帧（新成员加入 / 状态变化 / 轮终态）**不得使其自动折叠**；② 用户**手动收起**某组后，自动逻辑不得把它打开（`undefined` = 未触碰 / `true` = 显式展开 / `false` = 显式收起）；③ **`requiresAction === true` 的组强制展开**（用户手动收起**不生效**）；④ **组内任一成员段 `reason` 非空 ⇒ 默认展开且不得被自动折叠** | D4 / D6 / D-C1 / D-C6 | §5 TS-1 + §5 TS-2 |
| 3 | **单卡不变式 + 失败文案可见** | 同一 `toolCallId` 的整个生命周期里屏幕上**始终只有一张卡**（③b 段级不变式在模板合并后**保持**：`document.querySelectorAll('[data-testid^="pending-card-"]').length ≤ 1`）；GF 取消回灌失败时 **`reason` 文案在卡内可见**，**含「多工具折叠组内」可见** | A1' / A4' / D-C1 / D9③ | §5 TS-2 + §5 TS-3 + CDP 三张截图 |

---

## §1 施工范围与文件互斥

### 1.1 施工范围（**触碰清单，逐文件**）

| 文件 | 动作 | 内容（对应条目） | 预估规模 |
|---|---|---|---|
| `frontend/src/utils/blocks.ts` | **✅ 新建** | `groupParts(parts): Block[]` 纯函数 + `Block` 类型 + 默认展开态判定序（D6）+ 组头文案；**不依赖 store**（循 `utils/parts.ts:12` 纯函数层自述） | ~80-140 行 |
| `frontend/src/stores/ai.ts` | **✅ 改（窄面）** | ① 新增 `expandedBlocks: Record<string, boolean>` state + 读写动作（`setBlockExpanded` / `toggleBlock`）；② `resetSession` / 新建会话时清 `expandedBlocks`；③ **A4' 写点**（`cancelGenerativeForm` 失败分支 `:1351-1353` 旁，按 **D-C2 钉死形状**调用既有 `upsertToolPart`） | ~25-45 行 |
| `frontend/src/components/AiPanel/AiPanel.vue` | **✅ 渲染区大改** | ① 段分派区 `:166-267` 改块分派（`v-for="block in blocksOf(m)"`）；② 思考链 `<details>` `:174-177` 改受控折叠块；③ **A1' 挂起卡并入工具卡**（挂起内容降为子块）；④ 卡头/门控改**段驱动**（D2 清单）；⑤ `:206` / `:251-253` 死位**直接删**（D-C7） | 净减 ~15 行（合并 + 删死位 > 新增块分派） |
| `frontend/tests/blocks.spec.ts` | **✅ 新建** | `groupParts` 纯函数锁（§5 TS-1 全部用例） | ~120-180 行 |
| `frontend/tests/ai-panel-parts-render.spec.ts` | **✅ 改写** | 按 §6 改写清单（必改 `:94` + 三路锁适配 3 例 + 位置断言 `:106-115`；`:119`/`:134` 零改动、`:75` 实跑确认） | ~60-120 行变动 |
| `scripts/m2t2a-smoke.mjs` | **✅ 改写** | 断言 **③ + ③b** 两条件态化（G-C1）；`countExpr` 增补段态判据 + DOM「已取消」tag 强判据 | ~30-50 行变动 |

### 1.2 不触清单（**红线，逐条**）

| 文件 / 面 | 处置 | 依据 |
|---|---|---|
| `frontend/src/utils/frontend-tools.ts` | **❌ 不触（红线无例外）** | ③b `E19` 钉版；决策卡-批3③b `D16` 第 1 条的「红线例外须 D18 授权」**已整条删除**。若发现新面 ⇒ **登记入报告**而非就地修（另批授权） |
| `frontend/src/types/sse.ts` | **❌ 不触** | K3 批次边界（帧类型/字段不动） |
| `frontend/src/utils/parts.ts` | **⚠️ 仅调用** | 只允许**调用**既有 `upsertToolPart`（`parts.ts:90-103`）；**不新增段字段、不改 `deriveProjections`（`:178-217`）、不改 `pendingStatusOf`（`:106-127`）、不改 `localTerminal`（`:401`）、不改 X1 不变式** |
| 后端源码（任何 `.java` / `application.yml` / 测试类） | **❌ 不触** | 本批纯前端 ⇒ **不要求 `mvn`** |
| `frontend/src/stores/workspace.ts` | **❌ 不触** | R-137 面 |
| `scripts/chat-panel-smoke.mjs` | **❌ 不触** | 其 13 断言与 GF 链路**零重叠**（不受本批影响）；仅作 CDP 形态参照 |
| ③a / ③b 已落地面 | **❌ 不得重做/扩张** | 三态 `outcome`、`localTerminal` 含 `unknown`、`isFrameDecidedPendingStatus` 7 态、`pendingStatusOf` X1 门禁 + dev 断言、`applyPendingTerminal`（`:1765-1780`）/ `expireResidueToolParts`（`:246`）、`toolTagType` 九态穷尽（`AiPanel.vue:515-542`）、③b 段状态机闸（`:753`/`:765`/`:1571`/`:1615`/`:1636`/`:1688` 六调用点）、跨宿主已决终帧守卫（`:1569-1574`）、三 case 去 GF 前缀守卫（`:1615-1617`/`:1636-1641`/`:1686-1690`） |
| `docs/evidence/` 下历史文档 | **❌ 不设施工动作** | 引用域口径 = **代码/测试域**；历史文档只读、不做批量改写 |
| `<OTHER_REPO>（另一条线的仓，严禁触碰）` / `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` | **❌ 零操作（路径红线）** | 未读、未写、未 `git` 任何子命令 |

### 1.3 文件级互斥

| 文件 | 本批触碰 | 同文件他批 | 处置 |
|---|---|---|---|
| `frontend/src/utils/blocks.ts` | ✅ **新建** | 无（落稿棒 grep `groupParts\|expandedBlocks\|blocksOf` 在 `frontend/src` / `scripts` / `frontend/tests` **零命中**【实测】；`frontend/src/utils/` 现无该文件） | 无冲突 |
| `frontend/src/components/AiPanel/AiPanel.vue` | ✅ **渲染区大改**（`:166-267`） | ③b 已闭环（`fc6e5a2`，同文件不同区：`:212-253` 活分支链**本次重构宿主**）；轮询治理一期（**未开工**，其旁证锚点见 `体检报告.md §9.3` 附注） | **③b 已闭环 ⇒ 无在飞窗口**；轮询治理一期若开工须与本批**串行**（同文件） |
| `frontend/src/stores/ai.ts` | ✅ 新增 `expandedBlocks` + A4' 写点 | ③b 已闭环；轮询治理一期（未开工）；R-137（无） | 串行；本批改动面**窄**（state 新增 + 一处 upsert 调用） |
| `frontend/src/utils/parts.ts` | ⚠️ **仅调用** | ③b 已闭环（`D3/D4/D9/D12` 落码） | **不得改段模型语义**（§1.2） |
| `frontend/tests/*.spec.ts` | ✅ 新建 `blocks.spec.ts` + 改写 `ai-panel-parts-render.spec.ts` | 无 | — |
| `scripts/m2t2a-smoke.mjs` | ✅ **改写**（D8） | 无 | 仓内脚本，**非 CI 链路**（不在 `yarn test`、不在 `verify-e2e.ps1`） |

### 1.4 串行序

**③b（已闭环 `fc6e5a2` + 入库 `296ffe2`）→ 批 4④（本批）→ 轮询治理一期**。本批起手（S0）须**确认无同文件在飞窗口**（`AiPanel.vue` / `stores/ai.ts` / `utils/parts.ts` / `scripts/m2t2a-smoke.mjs`）。

### 1.5 锚定纪律（**红线，逐条**）

1. **diff 段序固定**：本批提交所需的 patch 一律**按文件路径排序后输出**（形如 `diff-批4④.sorted.patch`），并对每条 diff 记录 **`SHA-256`**（文件 + patch 双份）⇒ 前后两次产出可**逐字节比较**（防"同一提交两次产出不一致"）。
2. **收口修后重锚**：任何**收口修**（复审/复核后的修补）落地后，**必须重跑 S0 的锚点复取**（§4 S0①-⑤）并**重出锚点对比表**；**不得沿用收口修前的行号**。
3. **锚点引用一律带基线**：本批所有锚点写作 `file:line`（基线 = `296ffe2`）；**引用 ③a/③b 时期旧卡的锚点必须另标基线**（旧卡 = `505f6d4` / `3edaeb9`）。
4. **失锚三处禁用**（§4 S0-⑤）：`体检报告.md §9.2` 的 3 处失锚点（`toPendingCall` / `AiPanel.vue` FRONTEND 兜底 else / CONFIRM 兜底 else）**不得再作为施工锚**（其目标代码已被 ③b 删除）。
5. **零 git 写**：施工棒**不执行任何 git 写**（提交由提交官办理）；只读 `git` 子命令（`rev-parse` / `log` / `show` / `diff` / `status`）可用于留证。

---

## §2 A 条目清单定稿（**A1' ~ A10'**；行号 = `296ffe2` 实读）

> 逐项格式：**锚点** / **现状（实读）** / **施工取值（照 K3 终裁）** / **证据分级** / **关联**。
> 证据分级：【实测】= 直接读到原文；【推断】= 由实读事实按代码路径推出（未实跑）；【假设】= 未经实跑复验的前提。

### A1' 挂起卡 / 工具卡模板合并（**③b A1 移交**）

- **锚点**：`frontend/src/components/AiPanel/AiPanel.vue:179-266`（段分派区内两卡并列）；工具卡 **`:181-196`**；挂起卡 **`:201-265`**；GF 分支 **`:233-259`**；CONFIRM 确认块 **`:212-231`**；挂起卡门控 **`:201`**；复制区 **`:269-293`**。
- **现状（实读）【实测】**：
  - **工具卡（`:181-196`）**：**段驱动**（`:179` `<template v-else-if="part.kind === 'tool'">`，`:179` 是链头），**无条件渲染**（任何工具段都出卡），**无 `data-testid`**；内容 = 卡头（`:182-190`：`Operation` 图标 + `toolLabel(part.name)` + `kind2` tag `:185-186` + 段状态 tag `:187-189` `toolTagType/toolStatusLabel(part.status)`）+ 参数（`:191-193`）+ 结果（`:194`）+ **原因位（`:195`，`<div v-if="part.reason" class="mt-1 text-xs text-[#f56c6c]">原因：{{ part.reason }}</div>`）**。
  - **挂起卡（`:201-265`）**：**投影驱动**（`:201` `v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"`），带 **`data-testid="'pending-card-' + m.pendingCall.toolCallId"`**（`:201`）；内容 = 卡头（`:202-207`：`:204` `toolLabel(m.pendingCall.name)` + `:205` 「需确认」tag + `:206` 「会话已失效」tag）+ 参数（`:208-210`）+ **CONFIRM 确认块（`:212-231`）** + **GF 分支（`:233-259`：`:237-246` `FormRenderer`（宿主条件 `:238`）、`:247-258` 非宿主 `v-else` 块）**。
  - 两卡**并列渲染**（`:179-266`），即同一段先出工具卡、紧跟出挂起卡；`:198-200` 与 `:248-250` / `:261-264` 留有 ③b 删除痕迹与过渡形态注释（`:199` 自述「批2 过渡形态……③b 把两者合并成一卡之前」）。
- **施工取值（照 K3 终裁）**：**合并为单卡结构，挂起内容降为工具卡内子块**：
  1. **卡根只剩一份**：保留 `data-testid="'pending-card-' + part.toolCallId"` 的**语义**，但**仅在段 `requiresAction === true` 时挂该 testid**（段驱动，不再经投影）；
  2. **卡头统一为段单一来源**（`part.name` / `part.kind2` / `part.status`），**删除** `m.pendingCall.*` 卡头读点（`:204` / `:205` / `:206`）；
  3. **子块 = 原挂起卡块内层**，按段判据分派：
     - `part.kind2 === 'CONFIRM' && part.status === 'pending'` → **确认块**（原 `:212-231` 内容，判据改段）；
     - `part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL` → **GF 块**（`FormRenderer` 宿主条件 `:238` **逐字保留**，见 D10）；
     - 其余（通用 `FRONTEND` 待执行 / 已决态）→ **无子块**（**消除 A9' ⑤ 的空壳卡**）；
  4. **`:206` 「会话已失效」tag 与 `:251-253` 三 `activeForm` 判据 tag 一并直接删**（D-C7；其语义由**段状态 tag** `toolStatusLabel` 九态 `AiPanel.vue:544-560` 承载）。
- **证据分级**：现状 **【实测】**；修法结构 **【终裁口径】**（K3 D2 / D-C7 / A1' 立场）。
- **关联**：D1 / D2 / D-C7 / D10 / A9' / R1 / R3 / R6。

### A4' GF 取消回灌失败文案的渲染位（**③b A4 移交**）

- **锚点**：唯一文案写点 **`stores/ai.ts:1351-1353`**（文案本体 **`:1352`**）；唯一渲染位 **`AiPanel.vue:254-257`**（现 `:255` `v-if="ai.activeForm?.status === 'cancelled' && ai.activeForm.error"`）；notice 兜底 **`stores/ai.ts:1222-1224`**（`:1222` `const message = error instanceof Error ? error.message : '前端工具结果回灌失败'`；`:1223` `this.notice = message`；`:1224` `return { ok: false, message }`）。
- **现状（实读）【实测】**：
  - 写点 = `cancelGenerativeForm(auto)` 失败分支：`:1341` `form.status = 'cancelled'`；`:1344` `this.applyPendingTerminal(form.toolCallId, 'cancelled')`；`:1345` `disarmFormExpiry()`；`:1346` `await this.submitFrontendToolResult(form.toolCallId, '', 'http-post', true, form.runId)`；`:1347-1350` **收敛早退守卫**（活跃表单已换 ⇒ 不写过期表单）；**`:1351-1353`** `if (!outcome.ok && !outcome.duplicate) { form.error = \`${auto ? '表单已超时，' : ''}取消回灌未送达（${outcome.message ?? '网络异常'}），后端将在超时后自动收尾\` }`。
  - **该渲染位静态恒假（三条链，两方独立复现）**：① `AiPanel.vue:701-704`（`onFormCancel → ai.cancelGenerativeForm()`）→ `:1344` → **`:1775-1776`**（覆盖域三子句 `part.status === 'pending' || part.status === 'cancelled' || part.requiresAction` ⇒ **任何挂起段必被收口**，落 `requiresAction: false`）→ `:1777` `deriveProjections(item)` → `utils/parts.ts:216` `message.pendingCall = pending`（恒 `null`）⇒ 外层门控 `AiPanel.vue:201` **恒假**；② **`FormRenderer` 的 `error` prop 也兜不住**（`:238` 分支依赖 `liveForm` 与 `m.pendingCall`，同恒假）；③ 轮终态 / 新轮 / 结局帧等**七处**清空 `activeForm`（`stores/ai.ts:933` 断流 / `:1090` 重挂断流 / `:1371`（`stop()`）/ `:1433`（`resetSession`）/ `:1672`（`frontend_tool_result`）/ `:1717`（`done`）/ `:1733`（`error`）——**落稿棒实读 7 处**）⇒ **`activeForm` 不是稳定的可见载体**。
  - **实际可见渠道只剩 notice 单槽位**：`ai.ts:1222-1224` 把原始 `error.message` 写进 `this.notice`（`AiPanel.vue:98-105` 渲染 `el-alert` v-if → `:title="ai.notice"`），但 notice 是**单槽位**（`ai.ts:420` 声明 + `:440` 注释「与 notice 分开，便于面板用不同级别的 Alert 呈现」）⇒ 会被后续 `notice` 写点**挤掉**，且是**原始错误串**（无「取消回灌」语境）。
- **施工取值（照 K3 终裁）**：**D3 落点 = 段 `reason` 位** + **D-C2 写点形状钉死** + **D11 文案钉死**：
  1. **写点** = `cancelGenerativeForm` 失败分支（`stores/ai.ts:1351-1353` 旁）对**持该 toolCallId 的段**调用既有 `upsertToolPart`（`utils/parts.ts:90-103`）写 `reason`，**不经 `applyPendingTerminal`**（防污染 confirm 路径的 `reason` 语义）；
  2. **patch 形状钉死（D-C2，硬约束）** = `{ toolCallId, name, kind2, requiresAction: false, reason }`——`name` / `kind2` **读自既有段**；**`requiresAction` 必须显式 `false`**（`ToolPartPatch.requiresAction` 是**必填**字段，`types/ai.ts:284`；误置 `true` 会把 `applyPendingTerminal` 刚收口的终态段**重挂回 `requiresAction`**，**正面破坏 ③b D8 闸**）；
  3. **`createIfMissing = false`**（`parts.ts:90` 第三参）：段不存在时**静默丢写**（`parts.ts:98-100` 提前 `return`）并**报 DEV 警告**（调用方在 `import.meta.env.DEV` 下 `console.warn`，警告文案须含 `toolCallId`），**不新建段**；
  4. **文案按 D11 钉死**（见 A11' 条）：段 `reason` = **`取消回灌未送达（{原始错误明细}），后端将在超时后自动收尾`**；`auto === true` 时前缀「**表单已超时，**」沿用；**前缀「取消回灌未送达」即结构化前缀**；
  5. **`form.error` 原写入保留**（不改既有写点——`form.error` 仍作 `FormRenderer` 的 `error` prop 输入，且 `:1347-1350` 早退守卫语义不动）；**notice 兜底路径（`:1222-1224`）保留不动**；
  6. **段持久 ⇒ `activeForm` 被清不影响可见性**（工具卡 `:181-196` 为段驱动、**无条件渲染**；`:195` `reason` 行随之出现）；
  7. **可见性保证** = D-C1 的组强制展开判据（成员段 `reason` 非空 ⇒ 组默认展开且不得被自动折叠）⇒ 折叠组内**恒可见**。
- **证据分级**：写点/渲染位/恒假链 **【实测】**（逐行读到）；「段持久 ⇒ 可见」为 **【推断】**（未跑构造实验，**RED/GREEN 双向留证须补齐**）；③a M4 同域性 **【实测·不同域】**（U-1：`FrontendToolExecution.reason`（`types/tools.ts:142`）在**生产**零读者——`runFrontendTool`（`stores/ai.ts:1852-1908`）只读 `execution.outcome/result/availability`，段补丁 `:1893-1900` **不含 `reason`**；测试域读者 `frontend/tests/frontend-tool-outcome.spec.ts:50`）。
- **关联**：D3 / D-C1 / D-C2 / D11 / A1' / A5' / R4 / R5 / TS-3。

### A5' 折叠规则本体（**施工卡-刷新版 §4.4**）

- **锚点**：`frontend/src/utils/blocks.ts`（**新建**，落稿棒核实**不存在**【实测】；`groupParts\|expandedBlocks\|blocksOf` 全仓零命中）；`frontend/src/stores/ai.ts` state（`expandedBlocks` **全仓零命中**【实测】）；`AiPanel.vue` **`:166-267`**（段分派区，改块分派）、**`:174-177`**（思考链 `<details>`，改受控折叠块）。
- **现状（实读）【实测】**：渲染真值 = `m.parts` 的**段序**（`AiPanel.vue:166-169` 的 `v-for="part in m.parts"`，`:key` = `part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg`，注释自述「key 用业务稳定 id（toolCallId / kind-seg），禁用数组下标（§7 R4-3）」）；思考链用**原生** `<details>`（`:174-177`），**DOM 重建即丢展开态**。
- **施工取值（照 K3 终裁 + §4.4 逐字）**：
  1. **新建 `frontend/src/utils/blocks.ts`**：纯函数 `groupParts(parts): Block[]`——**相邻**连续 `reasoning` + `tool` 段合成一个"活动组"（**不按类型桶**）；每组成员、组头文案、`defaultExpanded`；**`text` 段一律 `break`**（正文永不进组）；
  2. **store 新增 `expandedBlocks: Record<string, boolean>`**（**D-C6 定稿类型**：`true` = 显式展开 / `false` = 显式收起 / `undefined`（键缺省）= 未触碰）；**自动逻辑只作用于未触碰组**；**例外：`requiresAction` 组强制展开优先级最高**（用户手动收起**不生效**）；**会话态**（`resetSession` / 新建会话须清空）；**`blockId` 必须稳定**（`toolCallId`，或 `reasoning` 段的 `seg`，**禁止下标**）；
  3. **`AiPanel.vue` 渲染改 `v-for="block in blocksOf(m)"`**（现 `:166-267` 段分派区重构为块分派）；
  4. **思考链 `<details>`（`:174-177`）改受控折叠块**（绑定 store 展开态，读 `expandedBlocks[id] ?? defaultExpanded`）。
  - `Block` 形状（**定稿**，实现者不得自行改判别字段名）：
    ```ts
    interface Block {
      id: string                 // 组首成员稳定 id：toolCallId 或 `reasoning-${seg}`
      kind: 'text' | 'activity'  // text = 单段正文（永不进组）；activity = reasoning/tool 组
      parts: MessagePart[]       // text 块恒 1 个元素；activity 块成员 kind ∈ {'reasoning','tool'}
      defaultExpanded: boolean   // D6 判定序（见下）
      /** D7：activity 组 |parts| === 1 且成员为 tool ⇒ 直接渲染该卡、不套壳 */
      bare: boolean
    }
    ```
- **三规则（验收标准，逐条可测；§4.4 逐字 + 终裁增补）**：
  1. **相邻连续块折叠**：只有**位置相邻**且同类（`reasoning` / `tool`）的 part 合并进同一组；被 `text` part 隔开的工具调用**分属不同组**。
  2. **用户展开过永不自动收起**：用户手动展开某组后，后续帧（新成员加入、状态变化、轮终态）**不得**使其折叠；**用户手动收起同理不得被自动打开**（除 `requiresAction` 组）；**默认态 = 见下判定序**。
  3. **正文永不进折叠组**：任何 Block 的成员**不得**含 `text` part；分组函数遇到 `text` part 必须**打断组**（并在 DEV 下 `console.error` 断言成员 `kind`）。
- **`defaultExpanded` 判定序（终裁，D6，实现按此顺序短路）**：
  | 序 | 条件 | 结果 | 备注 |
  |---|---|---|---|
  | ① | 组内任一成员段 `requiresAction === true` | `true`（**强制**，手动收起**不生效**） | R4-5 + D-C6 |
  | ② | 组内任一成员段 `reason` **非空** | `true`（未触碰组**不得被自动折叠**；**允许**用户手动收起） | **D-C1（本批新增）** |
  | ③ | 组内存在工具成员段且 `status ∈ {'pending','running'}` | `true`（"进行中"） | R4-1/D6 |
  | ④ | 其余（含**纯 `reasoning` 段组**、全部工具成员已终态） | `false` | **D-C8：`reasoning` 组维持现 UX 默认折叠** |
- **预期行为（§4.4 逐字）**：一轮"正文 → 3 个连续工具 → 正文"呈现为：正文、一个可折叠的"3 个工具活动组"（完成后折叠为一行摘要）、正文；点击组头展开后，即使后续又来了 2 个工具帧，该组**保持展开**。
- **证据分级**：现状与不存在性 **【实测】**；`Block` 形状与判定序为 **【终裁口径】**（实现按此写，不得自行取舍）。
- **关联**：D4 / D5 / D6 / D7 / D-C1 / D-C6 / D-C8 / R2 / TS-1 / TS-2。

### A6' E6 `<details>` 展开态不持久化（`AiPanel.vue:174-177`）

- **锚点**：`AiPanel.vue:174-177`（`:174` `<details v-else-if="part.kind === 'reasoning' && part.text" class="mb-1.5 text-xs text-[#909399]">`；`:175` `<summary class="cursor-pointer">思考过程</summary>`；`:176` 内容 div `max-h-40 overflow-y-auto`；`:177` `</details>`）。
- **现状（实读）【实测】**：原生 `<details>` ⇒ **DOM 重建即丢**展开态，直接违反规则②（对应台账 `R-140①-6`「`<details>` 展开态仍不持久化（属 R4-1、④批范围）」，`docs/M2-排期计划.md:329`）。
- **施工取值**：**随 A5' 受控化消解**——折叠块展开态改为读 store（`expandedBlocks[id] ?? defaultExpanded`），不再依赖 DOM 原生行为。**不另立施工项**（A6' 属 A5' 的子面）。**默认展开态 = 折叠**（**D-C8**：`reasoning` 无状态概念，进行中/完成均默认折叠；用户手动展开后**永不自动收起**）。
- **证据分级**：现象 **【实测】**；消解 **【推断】**。
- **关联**：D4 / D6 / D-C8 / A5' / TS-1。

### A7' `scripts/m2t2a-smoke.mjs` 断言失效处置（**范围 = ③ + ③b**）

- **锚点**：`scripts/m2t2a-smoke.mjs`（**488 行**）；TESTID 常量 **`:53`**（`const TESTID_TAG_CANCELLED = 'tag-form-cancelled'`；另 `:39` / `:166` 为**注释**引用）；计数表达式 **`:167-179`**（`:170` `formRendererCount` / `:171` `cancelButtons` / `:172-173` `pendingCards` / **`:174` `pendingStatuses`（读 `m.pendingCall.status`，**待段态化**）/ `:175` `activeFormStatus` / `:176` `cancelledTags` / `:177` `toolRunCount`）；断言点 **`:285`（②）/ `:297`（③）/ `:299`（③b）/ `:346`（① 失败支）/ `:457`（① 成功支）**；`assert` 函数 **`:183-187`**；`SKIP_MODEL` 分支 **`:306-312`**（断言① 记 skipped，退出码仅由 ②③③b 决定）；退出码 **`:487`** `process.exit(failures.length === 0 ? 0 : 1)`。
- **现状（实读）【实测】**：
  - **断言③ 与 ③b 均已静态失效**：③ 依赖 `:174` 的 `pendingStatuses`（读 `m.pendingCall`），取消链把它置 `null`（恒假链见 A4'）⇒ **恒空数组** ⇒ `:294` 的 `c.every(...)` **立即为真 → 提前 `break`** ⇒ `:297` `length === 2` **恒假**；③b 依赖 `c2.cancelledTags === 2`（`:299`），而 `AiPanel.vue` 内 `tag-form-cancelled` 现 **0 处**（③b A3 已删）⇒ **按设计不再成立**。
  - 脚本**不在** `yarn test`（`frontend/vitest.config.ts` `include: ['tests/**/*.spec.ts']` 只收仓内 spec）与 `verify-e2e.ps1` / CI 链路内（`.github/workflows/` / `frontend/package.json` / 根 **无 `package.json`** 对 `m2t2a` **零命中**）⇒ 现态属**静默双红**（若现在实跑，退出码 1），而非草案所写"静默假绿"。
- **施工取值（照 K3 终裁：**改写**，**退役否决**）**：
  1. **改写范围 = 断言 ③ + 断言 ③b 两条**（G-C1）；判据 = **段态**：「**该 `toolCallId` 全消息域段 `status === 'cancelled'` 且**无任何段 `requiresAction === true`**」；`countExpr` 新增（`pendingStatuses` 保留但**改为段来源**）：
     ```js
     cancelledSegments: ai.messages.reduce((n, m) => n + m.parts.filter(
       p => p.kind === 'tool' && p.toolCallId === '<id>' && p.status === 'cancelled').length, 0),
     hangingSegments: ai.messages.reduce((n, m) => n + m.parts.filter(
       p => p.kind === 'tool' && p.toolCallId === '<id>' && p.requiresAction === true).length, 0),
     ```
     ③ 新断言 = `cancelledSegments === 2 && hangingSegments === 0`；③b 新断言 = `formRendererCount === 0 && hangingSegments === 0 && cancelledStatusTags >= 1`；
  2. **另加强判据（G-C1 后半）**：新增 `cancelledStatusTags` = DOM 中「**已取消**」状态 tag 计数（`toolStatusLabel('cancelled')`，`AiPanel.vue:555`）——**取法**：以该 `toolCallId` 的工具段所属卡容器为范围，或以全文档范围取「文本 `trim() === '已取消'` 的元素数」；**取法须在施工报告中逐字写明并给出实际值**（**底线 `>= 1`；若合并后逐段出卡，期望值 `>= 2`**）。**该判据防"段态对但卡面错"的漏网**；
  3. **D9 锁显式加行**：**「`SMOKE_SKIP_MODEL=1` 下改写后全脚本退出码 0」**（依据 `:306-312` + `:487`）；
  4. **理由按现态重写（D-C5）**：改写理由 = 「**修复失效断言、维持 GF 链路仓内唯一 CDP 冒烟存活**」——**退役否决**的实证依据 = `scripts/chat-panel-smoke.mjs` 与 GF 表单链路**零断言重叠**（`FormRenderer|gf-submit|pending-card|tag-form-cancelled|generative_form` 命中数 **= 0**【落稿棒独立复现，grep 计数 `0`】）；
  5. **`scripts/chat-panel-smoke.mjs` 不触**（本批无改动面）；引用其断言时**勿按编号推物理顺序**（**U-9：⑬ 在 `:737` 物理先于 ⑫ 在 `:797`**）。
- **证据分级**：**【实测】**（脚本骨架、失效链、零重叠、退出码事实源逐点读到）；「改写后退出码 0」**须实跑留证**（TS-5）。
- **关联**：D8 / D-C5 / A10' / TS-5 / TS-6 / R9。

### A8' 重投路渲染锁改自承 RED（**GLM O-4 互证链重估**）

- **锚点**：`frontend/tests/ai-panel-parts-render.spec.ts`（**243 行 / 7 例**）；「重投路」用例 **`:165-184`**；施工报告-批3③b **`:182`**（§8 台账销记建议第 7 条）；其 §10-2 与 §2 第 73 行自述「重投路渲染锁 **green-at-RED**」（RED 面由 `frontend/tool-result-terminal` 的 D8 用例承载，两处**互证**）。
- **现状（实读）【实测，静态】**：③b 施工报告 §10-2 自述「重投路渲染锁 **green-at-RED**（施工前后行为不变/该路不受本批改动）」⇒ **本锁在 ③b 的 RED 轮并未真红**，RED 面靠**另一文件**的互证；台账第 7 条要求批 4④ 把「**重投路渲染锁改为自承 RED**」列入测试锁要求。
- **施工取值（照 K3 终裁）**：**A8' = 把该锁改为"夹具就位仍红"的自承 RED**（循 ③b D14 范式：**恒假变异 / 夹具全就位下断言必须红**），不再依赖另一文件的互证。**同时落 D-C9（升为必做）**：补「**多 `requiresAction` 段**」锁——段驱动后**复数挂起段逐段出卡**（旧模板单槽只出末段卡）；**取舍（施工卡定）**：**优先落 `ai-panel-parts-render.spec.ts`**（模板层就是被改面，夹具可复用 `buildMessage`），**并在 `blocks.spec.ts` 补一条分组层配对断言**（同一消息 2 个 `requiresAction` 段 ⇒ `groupParts` 产出 2 个 `bare`/独立 activity 组，`defaultExpanded` 均为 `true`）。
- **证据分级**：现状 **【实测】**（报告原文）；改法 **【终裁口径】**；「自承 RED」的 RED 证据**须实跑留证**（TS-3）。
- **关联**：D9 / D-C9 / TS-3 / TS-7 / R1。

### A9' §5 死位后模板收拢收尾（**侦察已静态复核：链结构闭合无悬空**）

- **锚点**：`AiPanel.vue:201-265`（合并前的挂起卡块）。**待消解面五处（逐条，行号 = `296ffe2` 实读）**：

| # | 面 | 锚点 | 现状（实读） | 合并后处置（**终裁**） |
|---|---|---|---|---|
| ① | GF 非宿主 `v-else` 块的「表单已取消」tag | `:251` | 条件 = 外层 `:201`（`pendingCall` 非空且同 id）**且** `:238` 宿主条件不成立 ⇒ 需 `activeForm.status === 'cancelled'` 同时该 `toolCallId` 仍 `requiresAction` —— **同一次取消在 `:1344` 已收 `requiresAction=false`，两条不可同真** ⇒ **静态恒假**【实测/推断】 | **直接删**（D-C7）；其语义由**段状态 tag**（`toolStatusLabel('cancelled')` = 「已取消」，`:555`）承载 |
| ② | GF 取消回灌失败文案 | `:254-257` | **= A4' 本体**（外层恒假 ⇒ 该渲染位不可达） | **迁段 `reason` 位**（D3/D11），旧位**删** |
| ③ | 「表单状态已丢失」tag 误标 | `:253` | 落 `v-else` 的**非宿主重复卡**（有段、非 `formHostMessageId`）会命中 ⇒ 此时表单其实**活着**（宿主在别的消息上）⇒ **文案误标**（"丢失/无法续填"失真）【实测/推断】 | **直接删**（D-C7）；合并后非宿主消息不再出独立挂起卡（D10 宿主条件单份渲染） |
| ④ | `expired` tag「会话已失效」 | `:206` | `m.pendingCall.status === 'expired'`。派生口径 = 段 `requiresAction = true` **且** `pendingStatusOf(段 status) === 'expired'`（`utils/parts.ts:106-127`）⇒ 可达组合 = **v1 镜像迁移面**：`migrateMirror` 写 `requiresAction: pending.status === 'pending'`（`stores/ai.ts:203`）而段 `status` 由 `run.status` 派生（`:200-201` 注释）⇒ 组合 `pendingCall.status = 'pending'` + 段 `status = 'expired'` 可使 `requiresAction = true` 且映射为 `expired`【推断】 | **直接删**（D-C7）；其可见性由**工具卡状态 tag**（段 `status = 'expired'` ⇒ `toolStatusLabel` 输出「会话已失效」`, `:553`）承接，**不另开渲染位** |
| ⑤ | 通用 FRONTEND 挂起卡删列后**空壳** | `:201-210` 对非 GF 的 FRONTEND 段 | ③b 删终态 tag 列后，非 GF FRONTEND 待执行段只出**卡头 + 参数**（`:202-210`），无动作区 ⇒ **空壳卡**（与紧随其后的工具卡重复观感） | **随 A1' 合并消除**（单卡 + 无子块） |

- **现状（复核结论）【实测】**：③b 删除后 **链结构闭合、无悬空 `v-else-if`/`v-else`**（实读：`:201` `v-if` → `:212` `v-if`（CONFIRM+pending）→ `:233` `v-else-if`（GF）→ `:247` `v-else`（GF 内层）→ 内层 `:251` `v-if` → `:252` `v-else-if` → `:253` `v-else`；`:259` / `:265` / `:266` / `:267` 正常闭合）✅
- **施工取值（照 K3 终裁）**：**合并后 ① ② ③ ⑤ 须断言消失**（旧渲染位不得残留、旧文案不得再出现在 DOM）；**④ 的可见性由段状态 tag 承接**（**DOM 零命中断言** + v1 镜像夹具断言卡面仍显示「会话已失效」，见 §5 TS-2 / §6）。
- **⚠️ 口径收窄（不得笼统表述「全部不可达」）**：台账 `R-139` 行尾订正（DS Pro M-2，`docs/M2-排期计划.md:328`）已记明「**不得笼统表述「全部不可达」**：FRONTEND `pending`「待浏览器自动执行」tag 曾可达（**亚 tick 瞬态**：`frontend_tool_request` 同步建段至 `runFrontendTool` 写 `running` 之间）」。本批合并后该空壳随 A1' ⑤ 消除，但**表述须沿用该收窄口径**。
- **证据分级**：链结构闭合 **【实测】**；①③⑤ 的可达性判断 **【推断】**（未跑构造实验）；④ 机制 **【推断】**。
- **关联**：A1' / A4' / D-C7 / TS-2 / R6 / R7。

### A10' `data-testid="tag-form-cancelled"` 引用域口径落定

- **锚点**：`scripts/m2t2a-smoke.mjs:39` / `:53` / `:166`（**3 处**）；`frontend/tests/` **零引用**；`docs/evidence/` 域 **7 份 / 16 处**【落稿棒实读】；`docs/` 根另 **1 份 / 2 处**（`docs/M2-排期计划.md`）。
- **现状（实读）【实测】**：**代码 / 测试域**（可执行代码）= 仅该脚本 **3 处**出现（`:53` **常量 = 唯一可执行判据**；`:39` / `:166` 为注释）；`frontend/` 全域 **0 处**（`AiPanel.vue` 内已无 DOM 对应物，③b A3 已删）；`docs/evidence/` 域 **7 份**历史证据文档引用（**非可执行代码**，不受删除影响）：`M2-T2b-施工与验证-K2.8.md`（3）/ `补档-CHATTIMELINE-批3③b-红队报告-DS-V4-Pro.md`（4）/ `补档-CHATTIMELINE-批3③b-复核报告-GLM-5.3.md`（3）/ `补档-CHATTIMELINE-批3③b-施工报告-K2.8.md`（2）/ `补档-CHATTIMELINE-批3③b-施工卡.md`（2）/ `补档-CHATPANEL-施工卡.md`（1）/ `补档-CHATPANEL-施工报告-苞B.md`（1）；**不计入 evidence 子域**者 = `docs/M2-排期计划.md`（**`docs/` 根**，2 处）。
- **施工取值（照 K3 终裁）**：**引用域口径 = 代码 / 测试域**（落本卡 §8 与施工报告）；`docs/evidence/` 域**不设施工动作**（历史文档只读、不做批量改写）；**计数按上列实读值落行**。
- **证据分级**：**【实测】**（逐处 `grep -ro`）。
- **关联**：D8 / A7' / §8 / §12。

### A11'（**K3 新增决策点 D11 的施工条目**）A4' 段 `reason` 文案格式钉死

- **锚点**：`stores/ai.ts:1351-1353`（现文案，本体 `:1352`）；notice 兜底 `:1222-1224`。
- **施工取值（照 D11 终裁）**：段 `reason` 文案 = **`取消回灌未送达（{原始错误明细}），后端将在超时后自动收尾`**；**`auto === true`（表单已超时自动取消）时前缀「表单已超时，」沿用** ⇒ 完整形态 `表单已超时，取消回灌未送达（{原始错误明细}），后端将在超时后自动收尾`；**即现 `form.error` 文案原样落段**（**不新造字符串**）；**前缀「取消回灌未送达」即结构化前缀**（满足 G-C2）；**notice 兜底路径**（`:1222-1224`）**保留不动**。
- **实现要求**：① 段 `reason` 与 `form.error` **同源取值**（同一表达式产出，或直接复用 `form.error` 变量，**不得两处各写一份字面量**）；② `{原始错误明细}` = 现 `outcome.message ?? '网络异常'`，**逐字保留**；③ DEV 下若 `createIfMissing=false` 未命中段 ⇒ `console.warn` 且**不得**写入任何新段。
- **证据分级**：现文案 **【实测】**；文案语义区分（与 `parts.ts:336` blocked/rejected、`parts.ts:372` 用户拒绝**不同语境**）**【实测】**。
- **关联**：D11 / D3 / D-C2 / G-C2 / G-C3 / TS-3。

---

## §3 D1~D11 终裁落表（**施工取值**）

| # | 终裁（施工取值） | 关键红线 / 坑 | 否决方案 | 落点 |
|---|---|---|---|---|
| **D1** | **(A) 合批**：A1'/A4'/A5' **同批**；**并采纳 G-C6 批内拆两提交**（§9）；批次边界 = §1.2 红线 | 不得让合批被误读为"可无边界顺手改"；两提交**各自独立过三连** | (B) 拆批（同区二次重构）；(C) 两笔独立批 | §9 / §1 |
| **D2** | **(B) 段驱动**：`deriveProjections`（`parts.ts:178-217`）**保留不动**；store 级投影消费面**不动**；模板区 `:166-267` 改段驱动；**`:482` / `:730` 明确保留读投影**；**`:206` / `:251-253` 直接删**（D-C7）；**新增多 `requiresAction` 段锁**（D-C9） | 若把 `:482`/`:730` 也段化 ⇒ 须先清 `:484` 的 `'approved'` 死判据（**本批不做**）；段驱动后**单槽→逐段**行为变化**必须有锁** | (A) 保留投影自匹配（复数挂起段只出末段卡，与新形态冲突） | §2 A1'/A9' / §5 TS-2 / §5 TS-7 |
| **D3** | **(A) 段 `reason` 位**：写点 = `cancelGenerativeForm` 失败分支旁（`stores/ai.ts:1351-1353`）对持该 `toolCallId` 的段按 **D-C2 形状** upsert；**不经 `applyPendingTerminal`**；**RED/GREEN 双向留证**（RED 形态见 G-C2） | `ToolPartPatch.requiresAction` **必填** ⇒ 必须显式 `false`（否则重挂已决段、破 ③b D8 闸）；`reason` 与"用户拒绝/执行失败"**同域**⇒ 结构化前缀必做 | (B) 仅依赖 notice 单槽位（会被挤掉、无语境）；(C) 新开独立渲染位（新增死码风险） | §2 A4' / §2 A11' / §5 TS-3 |
| **D4** | **(A) 内存 store**（sessionStorage 排除）+ **D-C6 升为必做**：`expandedBlocks: Record<string, boolean>`（三态）；自动逻辑只作用于**未触碰组**；`requiresAction` 组**强制展开优先最高** | `resetSession` / 新建会话须**清空 `expandedBlocks`**（否则跨会话串味）；**不得**写进镜像（防二次膨胀） | (B) sessionStorage（多一个清理残留面 + 与台账原诉求解读冲突） | §2 A5' / §5 TS-1 |
| **D5** | **(A) 业务稳定 `blockId`**：`toolCallId` / `reasoning-${seg}`；**禁数组下标** | 流式期段序增长 ⇒ 下标作 id 会整体错位 | (B) 下标 / 组序号 | §2 A5' / §5 TS-1 |
| **D6** | **(A) 进行中展开 / 完成后折叠 + `requiresAction` 强制展开**；**增补 ① 成员段 `reason` 非空 ⇒ 默认展开且不得被自动折叠**（D-C1）；**增补 ② `reasoning` 组维持现 UX 默认折叠**（D-C8）；判定序四档见 A5' | `requiresAction` 组**手动收起不生效**（可用性硬伤，须在用例里锁）；FRONTEND `pending` **亚 tick 瞬态**可能引起反复强制展开（推翻条件(1) 的特判预案保留） | (B) 一律折叠（把 HITL 面重新藏起来）；(C) 一律展开（滚动噪声） | §2 A5' / §5 TS-1 |
| **D7** | **(A) `[tool]` 单元素组不套壳**（`Block.bare === true` 直渲该卡） | 单工具轮是本仓最常见形态；也是 A4' 文案恒可见的保证 | (B) 一律套壳（视觉噪声 + 多余点击） | §2 A5' / §5 TS-1 |
| **D8** | **(A) 改写**（**退役否决**）：范围 = **断言 ③ + ③b** 段态化 + DOM「已取消」强判据；**理由按现态重写**（"修复失效断言、维持 GF 链路仓内唯一 CDP 冒烟存活"）；**锁加行「`SMOKE_SKIP_MODEL=1` 下退出码 0」** | **不得以改判据掩盖真实失败**；空数组 `every()` 假通过（`:294`）是本次要消掉的机制 | (B) 退役（丢唯一端到端证据面；`chat-panel-smoke` 零重叠已实证） | §2 A7' / §5 TS-5 |
| **D9** | **六条 + 一条新增（修订后）**：见 §5（TS-1~TS-7）；**含 G-C1/G-C2/G-C5/G-C7/D-C9/D-C10 全部落锁** | 三路单卡锁**必须有卡内容断言**（防空转假绿）；`:75` 若翻红须改块序断言**不得删除** | 只做纯函数锁 / 只做纯函数+模板锁（A4'/A8' 变"改了没锁"） | §5 / §6 |
| **D10** | **(A) `liveForm` / `formHostMessageId` 判据不扩**：`liveForm` 仅 `filling`/`submitting`（`:465-468`）；宿主候选仅 `pending`/`running`/`approved`（`:484`）；**合并后保持 `FormRenderer` 单份渲染**；**G-C10 锚点消歧**：草案引的「决策卡 D2 排除项 `:254-256`」改口径为「**`AiPanel.vue` GF 提交窗口（`:247-258` 现值）**」 | 扩判据 ⇒ 「已提交」tag 与 `FormRenderer` 同现；**不改 `:484` 的 `'approved'` 死判据**（G-C9 登记挂账） | (B) 扩到 `submitted`/`cancelled` | §2 A1' / §2 A9' / §8 |
| **D11** | **段 `reason` 文案钉死**：`取消回灌未送达（{原始错误明细}），后端将在超时后自动收尾`（`auto` 前缀「表单已超时，」沿用）；**前缀 = 结构化前缀**；notice 兜底保留不动 | 段 `reason` 与 `form.error` **同源取值**（不得两处各写一份字面量） | (B) 另起专用文案；(C) 只加粗不加前缀 | §2 A11' / §5 TS-3 |

---

## §4 施工步骤 S0~S7（**TDD 口径：每步 RED → GREEN 双向留证**）

> **总纪律**：`AiPanel.vue` / `stores/ai.ts` / `utils/parts.ts` 是 ③a/③b 刚落地的文件，本批起手**不得与任何同文件批次并行**（§1.3）。**每一步**都须先落**失败用例（RED）**并写 `evidence-p17/RED-<step>.log`，实现后落 `evidence-p17/GREEN-<step>.log`。

| 步 | 内容 | **RED（施工前）** | **GREEN（施工后）** | 门 |
|---|---|---|---|---|
| **S0** | **前置清点（只读，零改动）**：①**复取 §12 全部锚点**（防新漂移，尤其 `AiPanel.vue:166-267` / `stores/ai.ts:1336-1354` / `utils/parts.ts:178-217` / `ai-panel-parts-render.spec.ts` / `m2t2a-smoke.mjs`）；②`grep -rn "\.pendingCall\b" frontend/src` 复核**必须仍为 21 行 / 26 次**（与 §8 基线对账，**逐项点名保留面**）；③`grep -rn "hasDecidedPendingResidue" frontend/src` 复核 **6 调用点**（`:753`/`:765`/`:1571`/`:1615`/`:1636`/`:1688`）；④`grep -rn "activeForm = null" frontend/src/stores/ai.ts` 复核 **7 处**；⑤**⚠️ 心跳检查：`体检报告.md §9.2` 的 3 处失锚点不得再作施工锚**（`toPendingCall` / `AiPanel.vue` FRONTEND 兜底 else / CONFIRM 兜底 else）；⑥**基线实跑**：`cd frontend && yarn test`（记录**实跑例数**，与静态 `it(` = 113 对账）；⑦**写 §5 全部锁的失败用例**（预期红）并留证 | 全部新锁**预期红**（`RED-s0.log`）；`yarn test` 实跑例数登记 | 锚点对比表 + 消费点清单 + **RED 留证** | `yarn test` 可跑通（红在断言而非夹具） |
| **S1** | **提交 1a · A1' 结构合并 + D2 段驱动（模板区）**：挂起卡并入工具卡（单卡 + 子块，卡头段单一来源）；**`:206` / `:251-253` 直接删**（D-C7）；**`:254-257` 旧渲染位删**（A4' 旧位）；链结构收拢（**不得留悬空 `v-if`/`v-else-if`/`v-else`**）；testid 仅在 `requiresAction === true` 时挂 | 单卡断言/新结构断言**红** | 单卡 + 段驱动断言**绿** | `yarn typecheck` 绿 |
| **S2** | **提交 1b · A4' 写点与落点**：`cancelGenerativeForm` 失败分支按 **D-C2 钉死形状**写段 `reason`（**D11 文案**、`createIfMissing=false`、`requiresAction:false` 显式）；`:195` `reason` 位渲染 | **TS-3 RED**：夹具就位（`activeForm` 存续 + 持该 `toolCallId` 的段 + 回灌失败）下断言「**取消回灌失败语境文案可见**」⇒ **必红**（文案只在 `form.error`（不可达）；notice 只有**无语境**原始串） | **TS-3 GREEN**：段 `reason` 位渲染 ⇒ 绿；**含折叠组内可见**（依赖 S3 的折叠规则；若先于 S3 则记录"待 S3 复验"） | `yarn test` 绿 |
| **S3** | **提交 2a · A5'/A6' 折叠规则本体**：新建 `frontend/src/utils/blocks.ts`（`groupParts` + 判定序四档 + `bare`）；`stores/ai.ts` 新增 `expandedBlocks` + 读写动作 + `resetSession` 清空；`AiPanel.vue` 改块分派 + `<details>` 受控化 | **TS-1 RED**（`blocks.spec.ts` 全用例红：文件不存在 ⇒ 导入失败即红，**须在报告写明"红在导入"**，或先建空壳导出再断言红——**取法须申报**）；**TS-2 RED**（块序/展开态断言红） | **TS-1 / TS-2 GREEN** | `yarn typecheck && yarn test` 绿 |
| **S4** | **提交 2b · A7'/A8'/A9'/A10'**：`m2t2a-smoke.mjs` 断言 ③/③b 段态化 + DOM「已取消」强判据（**D8**）；`ai-panel-parts-render.spec.ts` 按 §6 改写（**:94 必改 + 三路锁适配 + 位置断言 `:106-115`**；`:119`/`:134` **零改动**、`:75` **实跑确认**）；**A9' ①②③⑤ 的 DOM 零命中断言 + ④ 的 v1 镜像夹具用例**；**A8' 自承 RED**（恒假变异）；A10' 引用域口径入报告 | **TS-5 RED**（`SMOKE_SKIP_MODEL=1` 下改写前**退出码 1**⇒ 留 `RED-m2t2a.log`）；**A8' RED**（恒假变异下必红）；**A9' 旧文案零命中断言红** | **TS-5 GREEN**（退出码 0）；A8' **恢复变异后绿 + `diff` 逐字核验恢复**；A9' 零命中绿 | `yarn typecheck && yarn test && yarn build` 绿 |
| **S5** | **仓内三连（提交级）**：`cd frontend && yarn typecheck && yarn test && yarn build` **全绿**（**每提交各跑一次**） | — | 三连全绿（`typecheck.log` / `vitest.log` / `build.log`） | **提交前置门** |
| **S6** | **E2E 桩模式**：`$env:E2E_STUB_MODE=1; powershell -File scripts/verify-e2e.ps1` ⇒ **PASS=28 / FAIL=0 / GFSKIP=0**（沿用 `Wait-Job` 120s 口径；**本批零后端改动 ⇒ 不要求 `mvn`**） | — | `E2E-stub-final.log` 末段三行齐 | **提交前置门** |
| **S7** | **CDP 仓外专项冒烟（§7）+ 施工报告落盘** | — | 三张截图 + 配方 + 复跑纪律留证；报告含 §11.1 全部必含项 | **收口门** |

**验证口径（可直接粘贴执行；仓根 = `<MAIN_REPO>`）**

```bash
# 前端三连（仓内；每个提交各跑一次）
cd frontend && yarn typecheck && yarn test && yarn build

# m2t2a 零模型改写守卫（改写后，退出码必须 0）
cd "<MAIN_REPO>" && SMOKE_SKIP_MODEL=1 node scripts/m2t2a-smoke.mjs; echo "exit=$?"
```

```powershell
# E2E 桩模式（后端不参与本批改动）
$env:E2E_STUB_MODE=1; powershell -File scripts/verify-e2e.ps1
```

- **后端 `mvn`**：本批**不含后端** ⇒ **不要求**任何 `mvn` 执行。
- **RED 纪律**：**每条 RED 必须是"夹具就位仍红"**（③b D14 范式）；若某条在夹具就位后**变绿** ⇒ **该断言无效，须重写断言**（问题在锁不在留证）。

---

## §5 测试锁清单（**TS-1 ~ TS-7 + 补强锁；每条须 RED→GREEN 双向留证**）

| # | 锁 | 内容（可执行口径） | 关键要求 / 判据 | 留证 |
|---|---|---|---|---|
| **TS-1** | **新建 `frontend/tests/blocks.spec.ts`（纯函数直测）** | `groupParts` 边界用例**全列**：① `[t,tool,tool,t]` → **1 组**；② `[tool,t,tool]` → **2 组**；③ `[tool]` → **单组且 `bare === true`（不套壳）**；④ `[reasoning,reasoning]` → **1 组且默认折叠**（D-C8）；⑤ 任何 Block 成员 `kind !== 'text'`（含 DEV 断言）；⑥ **`blockId` 稳定性**（新成员加入后既有组 id 不变）；⑦ **`requiresAction` 强制展开** + **手动收起不生效**；⑧ **成员段 `reason` 非空 ⇒ 默认展开**（D-C1）；⑨ **手动展开后不得被自动折叠**；⑩ **手动收起后不得被自动打开**（三态语义，D-C6） | 循 `foldDeadline` 同型落点（`施工卡-刷新版 §4.4` 验证方式）；`[tool]` 用例是 **D7** 的锁；③④⑦⑧⑩ 是本批**新增判据**的锁 | `RED-s3.log` / `GREEN-s3.log` |
| **TS-2** | **`ai-panel-parts-render.spec.ts` 改写（现 7 例）** | 按 §6 **改写边界**：**:94（单槽跟随）必改**（段驱动后"单槽"语义已变，须改为**逐段出卡**断言并申报）；**三路单卡锁（`:165`/`:186`/`:217`）适配合并结构**（`pending-card-*` 计数口径**不得放宽**；段计数断言 `toHaveLength(1)` 保留）；**位置断言 `:106-115` 必改**（块分组改变 DOM 顺序）；**:119`（N10）/`:134`（`isTyping`）零改动**；**:75`（段序）实跑确认**（若因块序改变而红 ⇒ 改**块序断言**，**不得删**）；**新增**：① A9' ①②③⑤ 的**文案/结构 DOM 零命中断言**；② A9' ④ 的 **v1 镜像夹具**用例（构造 `pendingCall.status='pending'` + `run.status='expired'`，断言卡面仍显示「会话已失效」）；③ **G-C5 卡内容断言**（三路锁**至少一条**断言工具名或状态 tag 文案**出现于 DOM**） | 三路单卡锁的计数口径**不得放宽**；**禁止只依赖 testid 计数**（防空转假绿） | `RED-s1/s4.log` / `GREEN-s1/s4.log` |
| **TS-3** | **A4' RED/GREEN 双向留证（形态按 G-C2）+ A8' 自承 RED** | ① **A4'**：夹具 = `cancelGenerativeForm` 失败分支全就位（`activeForm` 存续 + 持该 `toolCallId` 的段 + 回灌失败）；断言对象 = 「**取消回灌失败语境文案**（含前缀「取消回灌未送达」）**对用户可见**」⇒ **施工前红**（文案只在 `form.error`（不可达），notice 只有**无语境**原始串 ⇒ **不构成假绿**）；**施工后绿**（段 `reason` 位渲染）；**并断言折叠组内可见**（D-C1）；② **A8'**：重投路渲染锁改**自承 RED**（恒假变异 / 夹具全就位下断言必须红），**恢复变异后绿 + `diff` 逐字核验恢复** | RED 必须是「**夹具就位仍红**」；**结构前缀必做**（G-C2/G-C3）；**须同时断言「旧位不可达」不构成 RED 主体** | `RED-s2.log` / `GREEN-s2.log` / `RED-a8p-mutant.log` + `diff` |
| **TS-4** | **仓内三连回归门** | `cd frontend && yarn typecheck && yarn test && yarn build` **全绿**；**基线 = 静态 `it(` 113（11 spec：7/5/9/30/5/15/22/4/3/4/9）**；**运行时例数 = `yarn test` 实跑值**（S0 记录）；除 §6 明列的改写项外**不得翻红** | 回归门**锚实跑例数**（静态 113 为下限核算；推算值 125 见《决策卡-批4④.md》§附 A-1，**以实跑为准**）；`yarn build` 绿 | `typecheck.log` / `vitest.log` / `build.log` |
| **TS-5** | **`m2t2a-smoke.mjs` 改写锁** | ① **`SMOKE_SKIP_MODEL=1` 下改写后全脚本退出码 0**（退出码事实源 `scripts/m2t2a-smoke.mjs:487`；`SKIP_MODEL` 分支 `:306-312`）；② 改写**前**该模式**退出码 1**（留 `RED-m2t2a.log`）；③ 段态判据（`cancelledSegments === 2 && hangingSegments === 0`）+ DOM 强判据（`cancelledStatusTags` **取法须逐字写明 + 实际值**，底线 `>= 1`） | **不得以改判据掩盖真实失败**（若退出码仍非 0 且非判据问题 ⇒ 报 K3 重裁） | `RED-m2t2a.log` / `GREEN-m2t2a.log` |
| **TS-6** | **E2E 桩模式** | `$env:E2E_STUB_MODE=1; powershell -File scripts/verify-e2e.ps1` ⇒ **PASS=28 / FAIL=0 / GFSKIP=0**（③b 基线实证 = `批3b-evidence/E2E-stub-final.log` 末段「PASS=28 FAIL=0」「GFSKIP=0」「e2e_exit=0」） | **本批零后端改动 ⇒ 不要求 `mvn`**；沿用 `Wait-Job` 120s 口径 | `E2E-stub-final.log` |
| **TS-7** | **多 `requiresAction` 段锁**（**D-C9 新增，升为必做**） | **取舍（本卡定）**：**优先**在 `ai-panel-parts-render.spec.ts` 补一条用例——同一消息 **2 个 `requiresAction === true` 段** ⇒ **逐段各出一卡**（旧模板单槽只出**末段**卡）；**配对**在 `blocks.spec.ts` 补分组层断言（2 个 `requiresAction` 段 ⇒ 产出 2 个独立 `bare` 组，`defaultExpanded` 均 `true`） | 该行为变化是 D2 立场的**必然推论**（`parts.ts:202-210`）；**不锁 = "改了没锁"** | `RED-s3/log` / `GREEN-s3.log` |
| **TS-8** | **补强 · ③b 闸零触碰锁**（照 DS Pro #11 结论） | 施工前后对 `frontend/src/stores/ai.ts` 的**闸 6 调用点**（`:753`/`:765`/`:1571`/`:1615`/`:1636`/`:1688`）作 `diff` 断言「零改动」；对 `utils/parts.ts` 作 **sha256 前后对比**（**只允许 A4' 的调用点变化，段模型函数零改动**） | 落实「闸本体本批零触碰」的可验证形式（防"顺手改闸"） | `diff` + `sha256` 留证（`evidence-p17/`） |
| **TS-9** | **补强 · CDP 冒烟形态锁**（§7） | ① **折叠组行为**（含确认门等待形态）；② **GF 取消失败文案可见**；③ **GF 取消失败 `reason` 在多工具折叠组内可见**；④ 单工具轮形态（R4-4 要求"冒烟用例必须含单工具轮"） | 仓外件**不入库**；**复跑前必须重启 dev server**（写脚本头） | `cdp/*.png` + 配方 + ISO 时间戳 |
| **TS-10** | **补强 · 锚点复取锁** | S0 复取 §12 全部锚点并出**锚点对比表**（逐条 PASS/FAIL + 实际行号）；`grep` 复核三项计数（§4 S0 ②③④） | 行号漂移是本项目**历史最高频坑**（§12 已列 15 项订正） | `evidence-p17/锚点实读表.md` |

---

## §6 预期翻红与改写清单（**逐文件、逐行号**）

> 口径：**改写 = 同步改动断言**（TDD 双向留证）；**零改动 = 不得触碰**；「实跑确认」= 由施工棒跑后据实决定（**改也必须留证**）。

| 文件 | 行 / 例 | 处置 | 依据 |
|---|---|---|---|
| `frontend/tests/ai-panel-parts-render.spec.ts` | **`:75`**（段序） | **实跑确认**：预期原样保留（D7 单元素组不套壳下 DOM 序保持）；**若红 ⇒ 改块序断言，不得删** | G-C7 / DS Pro F-8【推断】 |
| 同上 | **`:94`**（挂起卡跟随位置 + 单槽） | **必改** ⇒ 段驱动后改为**逐段出卡**断言（**须申报改写取法**） | G-C7 / D2 / D-C9 |
| 同上 | **`:106-115`**（位置断言 `confirmTool < pendingCard < otherTool`；`:111`/`:112`/`:113` 三 `indexOf` + `:114-115` 比较） | **必改**（块分组改变 DOM 顺序） | **D-C10** |
| 同上 | **`:119`**（N10 复制判据，锚点区 `:269-293` 在 `v-for` 区**外**） | **零改动** | G-C7【实测】 |
| 同上 | **`:134`**（`isTyping`，判据 `:506-508` 不涉 `:166-267`） | **零改动** | G-C7【实测】 |
| 同上 | **`:165-184`**（重投路）/ **`:186-215`**（新宿主路）/ **`:217-242`**（历史整替路） | **适配合并结构**（`pending-card-*` 计数口径**不得放宽**；段计数 `toHaveLength(1)` 保留）；**至少一条加卡内容断言**（G-C5）；重投路按 **A8'** 改自承 RED | G-C5 / D9② / A8' |
| 同上 | **新增** | A9' ①②③⑤ 的 DOM 零命中断言；A9' ④ 的 v1 镜像夹具用例；**TS-7** 多 `requiresAction` 段用例 | A9' / D-C9 |
| `scripts/m2t2a-smoke.mjs` | **`:167-179`（`countExpr`）/ `:297` / `:299`** | **改写**（段态判据 + DOM 强判据，含 `:174` 的 `pendingStatuses` 改段来源） | G-C1 / D8 |
| `frontend/src/components/AiPanel/AiPanel.vue` | **`:201` / `:202-210` / `:233` / `:238` / `:255-257`** | **改段驱动 / 删旧位**（按 §2 A1'/A9'） | D2 / D-C7 |
| 同上 | **`:206` / `:251` / `:252` / `:253`** | **直接删**（D-C7） | D-C7 |
| 同上 | **`:482` / `:730` / `:484`** | **零改动**（明确保留面）；`:484` 的 `'approved'` 死判据**登记挂账** | D2 / G-C9 |
| `frontend/src/utils/parts.ts` | 全文件 | **零改动**（**只允许** A4' 的调用点；`diff` + `sha256` 留证） | D2 / E-9 / TS-8 |
| `frontend/src/stores/ai.ts` | **`:753`/`:765`/`:1571`/`:1615`/`:1636`/`:1688`（闸 6 调用点）** | **零改动**（TS-8 断言） | DS Pro #11【实测】 |
| 同上 | **`:1336-1354`（`cancelGenerativeForm`）** | **增补**：失败分支旁按 D-C2 写段 `reason`（**不改** `:1341`/`:1344`/`:1345`/`:1346`/`:1347-1350` 既有语义） | D3 / D-C2 / D11 |
| 同上 | **新增 `expandedBlocks` + 读写动作 + `resetSession` 清空** | **新增** | D4 / D-C6 |
| `frontend/vitest.config.ts` | — | **零改动**（`include: ['tests/**/*.spec.ts']` 决定 `m2t2a-smoke.mjs` 不在 `yarn test` 面） | A7' 关键前提【实测】 |

---

## §7 CDP 仓外专项冒烟要求（**强制**）

```bash
# 前端 dev：仓内 vite 默认 5200 且 strictPort:true ⇒ 必须显式 5202
# 【脚本头口径（沿用 ③b D13(B)）】复跑本脚本前**必须重启 dev server**
#   —— 裸路径探针 /src/... 对 HMR 换版脆弱（R-143②）
cd frontend && VITE_DEV_PORT=5202 VITE_API_BASE=<后端地址> yarn dev
```

**强制覆盖（三项必含截图 + 一项形态）**

| # | 形态 | 判据 | 必含证据 |
|---|---|---|---|
| 1 | **折叠组行为** | 一轮「正文 → 3 个连续工具 → 正文」⇒ 正文 → 一个可折叠的「3 个工具活动组」（完成后折叠为一行摘要）→ 正文；点击组头展开后新工具帧到达**仍保持展开**；**含确认门等待形态**（`requiresAction` 组强制展开） | **截图 1** |
| 2 | **GF 取消失败文案可见** | 构造 GF 取消 + 回灌失败 ⇒ 卡内可见「取消回灌未送达（…），后端将在超时后自动收尾」 | **截图 2** |
| 3 | **GF 取消失败 `reason` 在多工具折叠组内可见** | 该 GF 段与其它工具**同组**、组**默认折叠**情形下，`reason` 文字**仍在页面上可见**（D-C1 判据的端到端实证） | **截图 3** |
| 4 | **单工具轮形态**（R4-4） | `[tool]` 单元素组**直接渲染该卡、不套壳** | 截图（可并入截图 1 或单列） |

**证据与配方**

- 证据落 **`<SCRATCH>\chat-timeline-pkg\evidence-p17\cdp\`**（命名含 ISO 时间戳 + 形态名，如 `2026xxxxTxxxxxxZ-折叠组行为.png`）。
- **配方必须写入施工报告**：端口（5202）/ `VITE_API_BASE` 指向 / 注入方式 / 脚本路径（**仓外件、不入库**）。
- **复跑纪律留证**：报告须写明「**复跑前已重启 dev server**」+ 起手端口占用确认（`Get-NetTCPConnection -LocalPort 5202`，若被同仓 vite 旧进程占用 ⇒ 终止后全新启动；③b 曾遇 PID 3804 占用）。
- **注入方式若与配方不同须申报**（不得静默更换）。

---

## §8 台账承接清单（**提交官办理；逐项点名，不打包**）

| # | 项 | 施工报告须落的口径 |
|---|---|---|
| 1 | **`R-140①-1` 销记/推进** | 台账 `docs/M2-排期计划.md:329` 该条（「1 终态挂起卡不再保留」）③b 已注「A4 渲染位随**批 4④** 模板合并给独立槽位办理」⇒ **本批兑现后**可写「已由批 4④ **A4'+A1'** 落**段 `reason` 位**（复用 `AiPanel.vue:195`）+ 模板合并闭合；文案钉死见 D11」 |
| 2 | **`R-140①-6` 销记** | 台账 `:329` 该条（`<details>` 展开态仍不持久化，属 R4-1、④批范围）⇒ **本批 A6' 随 A5' 受控化消解**；口径须写明**持久化范围 = 会话内**（D4：**sessionStorage 不做**）；并写明**「手动收起」为 D-C6 新增验收面** |
| 3 | **`R-139③` 余脉（A10' 引用域）** | ③b 已销记 O1 死码；本批承接其「引用域口径 = 代码/测试域」的落行（**A10'**），**并按实读订正计数**（代码/测试域 **3 处**；`docs/evidence` **7 份 / 16 处**；`docs/` 根 **1 份 / 2 处**——见《决策卡-批4④.md》§附 A-2 冲突申报） |
| 4 | **`scripts/m2t2a-smoke.mjs` 登记销记** | ③b 施工报告 §8 第 7 条（`:182`）/ 决策卡-批3③b §5 台账已登记「随批 4④ 改写或退役」⇒ **本批按 D8(A) 改写落行**（范围 = 断言 **③ + ③b**），**销记该登记项**；**须同时写明退役仍否决的实证依据**（`chat-panel-smoke.mjs` 与 GF 链路**零重叠**） |
| 5 | **R4-1 ~ R4-5（`施工卡-刷新版 §7`）逐项对应** | R4-1 → **D4 / A6'**；R4-2 → **TS-1 规则③ / A5'**；R4-3 → **D5 / A5'（`blockId` 禁下标）**；R4-4 → **D7 / TS-1 用例③ / CDP 形态 4**；R4-5 → **D6 / TS-1 用例⑦** |
| 6 | **A8' 登记销记** | 施工报告-批3③b `:182` 第 7 条要求「批 4④ 施工卡同时应把**重投路渲染锁改为自承 RED**列入测试锁要求」⇒ 本批 **TS-3②** 落行 |
| 7 | **`data-testid="tag-form-cancelled"`** | 本批合并后该 testid 在仓内彻底消失（③b 已删 DOM，本批改 `m2t2a-smoke.mjs` 断言）⇒ 引用域口径落 **A10'** + §12 |
| 8 | **G-C9 新登记挂账（防"保护面虚高"）** | `AiPanel.vue:484` 宿主候选 `'approved'` 判据在段派生投影下**不可达**（`pendingStatusOf`（`utils/parts.ts:106-127`）输出域 = {`running`,`succeeded`,`rejected`,`blocked`,`expired`,`cancelled`,`pending`}，**不含 `approved`**）= **遗留死判据**；本批**不动**（D2 保留面）；**去向 = 后续清理批 / 轮询治理一期**；**R 编号待提交官取 R-145**（全新编号，不得复用既有） |
| 9 | **计数口径订正落行（G-C4 + D-C4 + D-C11）** | ① `pendingCall` 消费面终稿 = 「`frontend/src` **21 行 / 26 次**（纯代码 18 + 注释 3 = `stores/ai.ts:1710`/`:1799`、`types/ai.ts:262`）+ `scripts` 域 **1 处**（`m2t2a-smoke.mjs:174`，随 TS-5 段态化）+ `frontend/tests` 夹具面（6 spec，属 `deriveProjections` **保留面，不计入消费面**）」；② 「根 `package.json` 零命中」改为「**仓库根无 `package.json`**」 |
| 10 | **行号精度订正落行（U-7 + A-4）** | `pendingToolCall` getter 实体 = **`stores/ai.ts:461-476`**（草案写 `:466-472`）；`restoreSuspendedSnapshot` 实体 = **`stores/ai.ts:725-817`**（草案写 `:765-811`）；`runFrontendTool` 实体 = **`:1852-1908`**（③b 卡写 `:1800-1842`）；`activeForm = null` **7 处**（DS Pro 记 5 处）；`assert` 函数 = **`:183-187`**；GFSKIP `Say` = **`:2351`**（GLM 记 `:2352`） |
| 11 | **G-C10 锚点消歧落行** | 草案 D10 的「决策卡 D2 的排除项 `:254-256`」**引注错位**（决策卡-批3③b `:254-258` = **D16 第 1-3 条**；「排除项」裁决在 `:258`；逐行排除清单实体在《批3③b-施工卡.md》§5.2）⇒ 定稿口径改「**`AiPanel.vue` GF 提交窗口（`:247-258` 现值）**」 |
| 12 | **不作销记的项（防"保护面虚高"）** | ① **未决双卡残余**（`R-141①` 行尾注记：「不得表述「A7 已闭合」」）—— 本批**不动**；② **`loadHistory` 整替丢真**（`R-144`，后端批）—— 本批**不动**；③ **22 处"字符串⇒ok"**（`R-84`，判定词「**未闭合**」）—— 本批**不动**；④ **M7/M8/M9**（`R-143`）—— 本批**不动**；⑤ **③b 闸零触碰**（TS-8）—— 本批**不动** |
| 13 | **`R-140①-5`（复制按钮位置目视）** | 本批 CDP 冒烟**可顺带目检**，**不列为施工项**；若目检 ⇒ **未销记**如实写（③b 亦未销记） |

---

## §9 提交计划（**GLM C6 采纳：批内两提交**）

> 总纪律：**零 git 写由施工棒执行？否 —— 提交由「提交官」办理**；施工棒负责**产出可提交的工作树 + 两提交的拆分边界说明**。每次提交**独立过三连**（§5 TS-4）。

| 提交 | 内容 | 覆盖条目 | 门 |
|---|---|---|---|
| **提交 1** | **「A1'/A4' 结构合并 + 7 例锁改写 + RED/GREEN」**：A1' 挂起卡并入工具卡（单卡 + 子块 + 段驱动卡头）；**`:206` / `:251-253` / `:254-257` 直接删**（D-C7：**不做"先改后删"两步**）；A4' 写点（D-C2 形状 + D11 文案）+ 段 `reason` 位渲染；`ai-panel-parts-render.spec.ts` 按 §6 改写（`:94` + 三路锁适配 + 位置断言）；**TS-3** RED/GREEN 留证 | A1' / A4' / A11' / D2 模板面 / D-C7 / D-C2 / D11 | `yarn typecheck && yarn test && yarn build` **全绿** |
| **提交 2** | **「A5' ~ A7'/A9'/A10' 折叠规则与死位收尾 + `blocks.spec.ts`」**：新建 `blocks.ts` + `expandedBlocks`（三态）+ 块分派 + `<details>` 受控化（A5'/A6'）；`m2t2a-smoke.mjs` 断言 ③ + ③b 段态化（A7'/D8）；A9' 收尾复核 + **零命中断言**（**死位清除动作实体已并入提交 1**——D-C7「整体重构、不两步」；提交 2 承接其**断言与复核**）；A10' 引用域口径落行；`blocks.spec.ts` 全用例；**TS-1/TS-2/TS-5/TS-7** RED/GREEN 留证 | A5' / A6' / A7' / A8' / A9' / A10' / D4 / D5 / D6 / D7 / D-C1 / D-C6 / D-C8 / D-C9 | 同上 **全绿** |

**两提交与 G-C6 的口径统一说明**：G-C6 原文把「**死位清除**」列在提交 2，而 **D-C7** 终裁为「挂起卡块整体重构时**不做"先改后删"两步**」。二者**统一为**：**死位（`:206` / `:251-253` / `:254-257`）随提交 1 的结构合并一并删除**（D-C7 优先），**提交 2 承接 A9' 的收尾复核与零命中断言**。该统一**不构成立场反转**（两条终裁指向同一动作实体，仅归属时点不同）。

---

## §10 验收标准（**逐条可判，全部须有证据**）

| # | 验收项 | 判据 | 证据 |
|---|---|---|---|
| V1 | **块分派形态** | §0 验收点 1 全成立；`[tool]` 单组不套壳 | TS-1 + CDP 截图 1/4 |
| V2 | **展开态三态承诺** | §0 验收点 2 全成立（手动展开不自动折叠 / 手动收起不自动打开 / `requiresAction` 强制且收起无效 / `reason` 非空强制展开） | TS-1 + TS-2 |
| V3 | **单卡不变式保持** | 三路单卡锁全绿（`pending-card-*` 计数口径**未放宽**）；**至少一条卡内容断言** | TS-2 |
| V4 | **GF 取消失败文案可见** | RED（施工前不可见）→ GREEN（施工后卡内 + 折叠组内可见） | TS-3 + CDP 截图 2/3 |
| V5 | **m2t2a 改写有效** | `SMOKE_SKIP_MODEL=1` 下**退出码 0**；改判据**不掩盖**真实失败 | TS-5 |
| V6 | **仓内三连** | `yarn typecheck && yarn test && yarn build` 全绿；基线 = 静态 `it(` **113** + 新增 `blocks.spec.ts`；运行时**锚实跑** | TS-4 |
| V7 | **E2E 桩模式** | **PASS=28 / FAIL=0 / GFSKIP=0** | TS-6 |
| V8 | **CDP 形态** | 三项必含截图 + 单工具轮形态；配方与复跑纪律留证 | TS-9 |
| V9 | **红线零触碰** | `frontend-tools.ts` / `types/sse.ts` / 后端 / `workspace.ts` **零 diff**；`utils/parts.ts` 段模型函数**零 diff**（sha256）；③b 闸 6 调用点**零 diff** | TS-8 |
| V10 | **锚点复取 + 锚定纪律** | §12 全部锚点 PASS/FAIL 表；`grep` 三项计数与基线对账；**diff 段序固定 + SHA-256 双份留证**；**若发生收口修 ⇒ 已重锚并重出对比表** | TS-10 + §1.5 |
| V11 | **台账承接** | §8 十三项**逐项点名**落行（不打包）；**R-145 新编号已取** | 施工报告 §台账节 |
| V12 | **报告自证** | §11.2 硬性完成判据**全部满足**（含 wire 实证，**禁照抄卡头署名**） | 施工报告 + `wire.jsonl` 读取留证 |

---

## §11 硬性完成判据（**工作方式 12/⑰**）

### 11.1 施工报告必含项（**`<SCRATCH>\chat-timeline-pkg\施工报告-批4④.md`**）

1. **锚点对比表**（§12 为基线，逐条 PASS/FAIL + 实际行号 + 与本卡差异）。
2. **各锁的 RED/GREEN 双向留证**（含 TI-3 的 A4' 与 A8' 两条；**RED 必须是"夹具就位仍红"**）。
3. **CDP 冒烟截图与配方**（三张必含 + 单工具轮；含**复跑前已重启 dev server** 的留证 + 端口占用确认）。
4. **A1' 合并后的实际结构说明**（单卡 + 子块；`:206`/`:251-253`/`:254-257` 已删的逐行号点名）。
5. **A4' 落点与文案留证**（段 `reason` 实际文本样例 + patch 形状逐字 + `createIfMissing=false` 的 DEV 警告实现）。
6. **D8 改写留证**（改写前退出码 1 / 改写后退出码 0 的两份日志 + `cancelledStatusTags` **取法逐字**与实际值）。
7. **已知代价与未闭合面申报**：① **A9' ④ 的 v1 镜像组合面**（若实测不可达 ⇒ 按"确不可达的死码"处理并留证）；② **G-C9 的 `:484` 死判据**（登记挂账，本批未修）；③ **③b 闸零触碰**（TS-8 留证）；④ **`docs/evidence/` 引用域不动作**（A10'）。
8. **偏差逐条申报** + **台账销记建议（§8 逐项点名，不打包）**.
9. **`SMOKE_SKIP_MODEL=1` 与「`yarn test` 实跑例数」双基线记录**。

### 11.2 **硬性判据（不满足即报告不合格，批次不得收口）**

| # | 判据 | 不合格情形（举例） |
|---|---|---|
| **H1** | **施工报告必须自证实际运行模型 + CU 会话 id + wire `modelAlias` 实证** | ① 只写「施工棒」而无模型名；② **照抄卡头署名（如「DeepSeek V4 Flash」）而无 wire 实证** ⇒ **不合格**；③ 以"配置推定"代替实证 ⇒ **不合格** |
| **H2** | **自证方式必须可复现**：报告须给出 `wire.jsonl` 的 **绝对路径** + `"modelAlias":"<实际值>"` 的**原文** + **命中次数** + **统计命令**；须同时给出 **CU 会话 id**（`session_<uuid>`）与 **agent id** | 只给"实际模型 = X"一句话、无路径/无次数/无命令 ⇒ **不合格** |
| **H3** | **机械活下沉（若发生）必须显式钉 `model=DeepSeek/deepseek-flash`（禁靠会话默认）**，且**下沉子智能体亦须自证**（各自 `wire.jsonl` 的 `modelAlias` 命中数）；**关键锚点与关键结论须由施工棒本体亲读复核** | 下沉未钉版 / 未自证 / 只凭子报结论落卡 ⇒ **不合格** |
| **H4** | **路径红线**：对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` **零操作** | 任一读/写/`git` ⇒ **整批作废** |
| **H5** | **红线零触碰**（§1.2）**且有可验证留证**（TS-8 的 `diff` + `sha256`） | 无留证 ⇒ **不合格**（"声称没改"不算） |
| **H6** | **RED 真实性**：每条 RED 的日志须能证明**夹具已就位**（而非导入失败/夹具缺失即红）；若红在"文件不存在/导入失败"⇒ **须在报告中显式标注并补"夹具就位仍红"的证据** | 以"文件不存在"充当 RED ⇒ **不合格** |
| **H7** | **报告必须写「未做/未复验」事项**（照本项目惯例：未跑的命令、未构造的夹具、未核对的目录） | 隐去局限、把【推断】当【实测】⇒ **不合格** |

> **H1/H2 的模板（施工报告直接照填）**：
> ```
> 施工棒执行模型自证：
>  - 执行者 = <agent-id>（CU 会话 id = session_<uuid>）
>  - wire 路径 = <KIMI_HOME>\sessions\<workspaceId>\<session_<uuid>>\agents\<agent-id>\wire.jsonl
>  - 统计命令 = grep -o '"modelAlias":"[^"]*"' wire.jsonl | sort | uniq -c
>  - 实测命中 = "modelAlias":"<实际模型>" × <N> 次；"model":"<实际模型>" × <M> 次
>  - 结论 = 实际运行模型 = <实际模型>（wire 实证，非配置推定）
> ```

### 11.3 机械活下沉条款（**明文**）

> 施工棒若把**机械活**（锚点复取、清单生成、扫描类核查、逐点计数核对、`git show` 对照等）下沉子智能体，**必须**：
> 1. **显式钉 `model=DeepSeek/deepseek-flash`** —— **禁靠会话默认**；
> 2. 子任务提示中写明用途与**只读**约束（零 git 写、不改仓库文件、不读 `.env`）；
> 3. **报告中自证实际模型**：子智能体须各自给出 `wire.jsonl` 路径 + `modelAlias` 命中数；**若其运行环境无法自省底层模型 ID**，须以**父棒显式下发参数**为据自证并**如实写明该局限**（**不得**声称"已独立自证"，除非运行侧提供日志佐证）；
> 4. 关键锚点与关键结论须由**施工棒本体亲读**复核（不得只凭子报结论落卡）；
> 5. **⚠️ 前置确认（本棒实测经验）**：若施工棒运行时**无 `Agent`/`Task` 派发面**（如落稿棒所见：`wire.jsonl` 内无 `subagent.spawned` 记录）⇒ **如实申报局限**，机械活由本体内联完成，**不得**以"未下沉"为由跳过锚点复核。

---

## §12 锚点实读表（**落稿棒在 `296ffe2` 逐项抽验；施工棒 S0 须复取**）

> 口径：每条 = 「锚点 → 实读结论 → 实读要点」。**全部为【实测】**（`Read` / `grep -n` / `sed -n` / 只读 `python` 直接读到），除标注者外不含推断。

| # | 锚点 | 结论 | 实读结果 |
|---|---|---|---|
| 1 | `AiPanel.vue` 文件规模与分区 | **PASS** | **791 行**（`:1-320` 模板 / `:322-771` script / `:773-791` style；`:320` `</template>`、`:322` `<script setup lang="ts">`、`:771` `</script>`、`:773` `<style scoped>`、`:791` `</style>`） |
| 2 | `AiPanel.vue:166-169` 段分派 `v-for` + key | **PASS** | `:166-169` `<template v-for="part in m.parts" :key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg">`；`:267` `</template>` 闭合；`:162-165` 注释自述「key 用业务稳定 id……禁用数组下标（§7 R4-3）」 |
| 3 | `AiPanel.vue:171` 正文段 | **PASS** | `:171` `v-if="part.kind === 'text' && part.text"` |
| 4 | `AiPanel.vue:174-177` 思考链 `<details>` | **PASS** | `:174` `<details v-else-if="part.kind === 'reasoning' && part.text" class="mb-1.5 text-xs text-[#909399]">`；`:175` `<summary class="cursor-pointer">思考过程</summary>`；`:176` 内容 div（`max-h-40 overflow-y-auto`）；`:177` `</details>` |
| 5 | `AiPanel.vue:179-196` 工具卡 + `:195` reason 位 | **PASS** | `:179` `<template v-else-if="part.kind === 'tool'">`；`:181` 卡根 div（**无 `data-testid`**）；`:182-190` 卡头（图标 + `toolLabel(part.name)` + `:185-186` `kind2` tag + `:187-189` 状态 tag）；`:191-193` 参数；`:194` 结果；**`:195` `<div v-if="part.reason" class="mt-1 text-xs text-[#f56c6c]">原因：{{ part.reason }}</div>`**；`:196` 闭合 |
| 6 | `AiPanel.vue:198-200` 过渡形态注释 | **PASS** | `:198-200` 原文「挂起卡片：HITL 确认（确认/拒绝双路径）或待前端执行。**批2 过渡形态：挂起卡跟随其工具段位置（③b 把两者合并成一卡之前）**；单槽 pendingCall 保证每条消息至多一张挂起卡。」 |
| 7 | `AiPanel.vue:201` 挂起卡门控 + testid | **PASS** | `v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"` + `:data-testid="'pending-card-' + m.pendingCall.toolCallId"` |
| 8 | `AiPanel.vue:202-210` 挂起卡头 + 参数 | **PASS** | `:204` `toolLabel(m.pendingCall.name)`；`:205` 「需确认」tag（`kind === 'CONFIRM'`）；`:206` 「会话已失效」tag（`status === 'expired'`）**← 本批删除对象**；`:208-210` `argsText(m.pendingCall.args)` |
| 9 | `AiPanel.vue:212-231` CONFIRM 确认块（**活分支**） | **PASS** | `:212` `v-if="m.pendingCall.kind === 'CONFIRM' && m.pendingCall.status === 'pending'"`；`:213-218` 倒计时/等待文案；`:219-226` 拒绝原因 `el-input`；`:227-230` 拒绝/确认按钮（`:229` `decide(true)`） |
| 10 | `AiPanel.vue:233-259` GF 分支 | **PASS** | `:233` `v-else-if="m.pendingCall.kind === 'FRONTEND' && m.pendingCall.name === GENERATIVE_FORM_TOOL"`；`:237-246` `<FormRenderer>`（宿主条件 `:238`）；`:247` `v-else` 非宿主块；`:259` 闭合 |
| 11 | `AiPanel.vue:248-250` 删除痕迹注释 | **PASS** | 原文记「批3③b D2（R-139③）：帧终态 tag 分支（原 `:250-253`）在 D8 关旁路后为不可达死码，删除；活分支只剩 activeForm 提交窗口（`:254-256`，排除项）。链头由 `v-else-if` 收拢为 `v-if`（不得留悬空 `v-else-if`）。」 |
| 12 | `AiPanel.vue:251-253` GF 非宿主三 tag（**A9' ①②③**） | **PASS** | `:251` `v-if ai.activeForm?.status === 'cancelled'` → 「表单已取消」；`:252` `v-else-if … === 'submitted'` → 「表单已提交，等待回执」；`:253` `v-else` → 「**表单状态已丢失（刷新后无法续填），等待后端超时收敛**」 |
| 13 | `AiPanel.vue:254-257` GF 取消回灌失败文案（**A4' 本体**） | **PASS** | `:254` 注释「红队问题 5：取消回灌失败的提示在 cancelled 态下也要可见」；`:255` `v-if="ai.activeForm?.status === 'cancelled' && ai.activeForm.error"`；`:256` `{{ ai.activeForm.error }}`；`:257` 闭合 |
| 14 | `AiPanel.vue:261-264` 删除痕迹注释 | **PASS** | 原文记「通用 FRONTEND 终态 tag 列（原 `:264-277`）与 CONFIRM 终态 tag 列（原 `:280-286`）……按 §5 清单整列删除；排除项 `:213-232` 与 `:254-256` 保留」 |
| 15 | `AiPanel.vue:269-293` 复制区（N10） | **PASS** | `:273` `v-if="m.parts.some((part) => part.kind === 'text' && part.text)"`；`:277` `copy-hint-<id>`；`:286` `copy-msg-<id>`；`:293` 闭合（**N10：判据取 `parts`，内容仍取 `m.content`**）— **锚点区在段分派 `v-for` 之外** ⇒ `:119` 例零改动依据 |
| 16 | `AiPanel.vue:98-105` notice 渲染 | **PASS** | `:99-105` `el-alert v-if="ai.notice"` + `:title="ai.notice"`（单槽位 Alert） |
| 17 | `AiPanel.vue:446` placeholder 消费 store 投影 | **PASS** | `:446` `if (ai.suspended \|\| ai.pendingToolCall) { return '挂起等待中：请先在上方处理确认/执行，或点「停止」结束本轮' }` |
| 18 | `AiPanel.vue:465-468` `liveForm`（**D10**） | **PASS** | `:465-468` `const liveForm = computed(() => { const form = ai.activeForm; return form && (form.status === 'filling' \|\| form.status === 'submitting') ? form : null })` |
| 19 | `AiPanel.vue:476-489` `formHostMessageId`（**D10 / G-C9 / D2 保留面**） | **PASS** | `:481-487` 倒序扫 `ai.messages[i]?.pendingCall`（**:482 保留读点**），`:484` 候选限定 `pending`/`running`/**`approved`（死判据）**，返回 `ai.messages[i].id` |
| 20 | `AiPanel.vue:506-508` `isTyping` | **PASS** | `:506-508` `message.streaming && !message.parts.some((part) => part.kind === 'text' && part.text.length > 0)` ⇒ **判据不涉 `:166-267`** ⇒ `:134` 例零改动依据 |
| 21 | `AiPanel.vue:515-542` `toolTagType` 九态 | **PASS** | 显式 case + 去 `default`（③a D10(A)）；`expired`/`cancelled` → `info`（`:532-536`）；`unknown`/`blocked`/`rejected` → `warning`；`failed` → `danger` |
| 22 | `AiPanel.vue:544-560` `toolStatusLabel` 九态穷尽 | **PASS** | `Record<ToolRunStatus, string>`：`pending` 等待确认 / `running` 调用中… / `succeeded` 已成功 / `failed` 执行失败 / `rejected` 已拒绝 / `blocked` 超出当前范围 / **`expired` 会话已失效（`:553`）** / **`cancelled` 已取消（`:555`）** / `unknown` 结局未知（未确认成功/失败）**← D8 强判据与 A9' ④ 承接位的依据** |
| 23 | `AiPanel.vue:701-704` `onFormCancel` | **PASS** | `:702-704` `function onFormCancel(): void { void ai.cancelGenerativeForm() }`（A4' 恒假链入口） |
| 24 | `AiPanel.vue:730` 滚动指纹 | **PASS** | 指纹串末项 = `${last && last.pendingCall ? last.pendingCall.status : ''}`（**D2 保留面**） |
| 25 | `AiPanel.vue:741-745` watch `ai.pendingConfirm` | **PASS** | `watch(() => ai.pendingConfirm, (pending) => resetPendingWatch(pending), { immediate: true })` |
| 26 | `utils/parts.ts` 文件规模 + 头注 | **PASS** | **458 行**；`:1-13` 头注「**唯一写口**」「`content`/`reasoning`/`toolRuns`/`pendingCall` 四个旧字段是**派生投影**，只允许由 `deriveProjections` 写入」；`:12` 「本文件**不依赖 store**（纯函数）」 |
| 27 | `utils/parts.ts:178-217` `deriveProjections`（**保留不动**） | **PASS** | `:202` `if (part.requiresAction)`；`:206-210` `unknown` 走 dev 断言（`assertUnknownTerminalInvariant`）/ 否则 `pending = toPendingCallProjection(part)`（**U-5：取最后一个非 `unknown` 的 `requiresAction` 段**）；`:216` `message.pendingCall = pending`；`:213-215` 三个投影赋值；`:199` `reason: part.reason`（`toolRuns[]` 抄写 `reason`，**U-3：无 UI 读者**） |
| 28 | `utils/parts.ts:106-127` `pendingStatusOf` | **PASS** | 显式 6 态 `:108-119`（`running`/`succeeded`/`rejected`/`blocked`/`expired`/`cancelled`）；`default` `:120-126` → `'pending'`（注释：`unknown` 仅类型穷尽兜底）⇒ **输出域不含 `approved`**（G-C9 依据） |
| 29 | `utils/parts.ts:90-103` `upsertToolPart`（**A4' 调用目标**） | **PASS** | `:91` 按 `toolCallId` 定位；`:93-95` 已存在 ⇒ `message.parts.splice(index, 1, { ...existing, ...patch })` 就地合并；`:97-100` `!createIfMissing \|\| status === undefined` ⇒ `return`；`:101-102` 新建前 `sealTrailingStreamPart` + `push` |
| 30 | `utils/parts.ts:152-164` `toPendingCallProjection` | **PASS** | `:156` `kind: part.kind2 === 'FRONTEND' ? 'FRONTEND' : 'CONFIRM'`；消费点（`deriveProjections` 内）**`:209`**（体检报告 §9.2 失锚 #1 的复刻锚） |
| 31 | `utils/parts.ts:326-336` `tool_result` 写 `reason`（**写点 1/3**） | **PASS** | `:326-328` `blocked`/`rejected` 文案（`:328` `existing?.reason ?? '用户拒绝执行'`）；`:336` 落 `reason` |
| 32 | `utils/parts.ts:372` `confirm_decision` 写 `reason`（**写点 2/3**） | **PASS** | `:372` `reason: frame.reason ?? '用户拒绝'`（rejected 支） |
| 33 | `utils/parts.ts:401` `localTerminal` | **PASS（订正）** | **`:401` `const localTerminal = existing?.status === 'failed' \|\| …`**（`:406` 使用）——③b 卡写 `:383-386` 为 ③b 前基线，**已下移** ⇒ 本批引用一律用 `:401` |
| 34 | `utils/parts.ts:420-438` `suspended` 支（**U-6 订正**） | **PASS** | `:420` `case 'suspended': {`（注释明写「段尚不存在时**建占位段**」）；`:428-429` `find(call => call.kind === 'CONFIRM' \|\| call.kind === 'FRONTEND')`；`:430-437` `upsertToolPart`（**默认 `createIfMissing=true`** ⇒ **缺则建 / 在则覆盖**）；`:433-434` `status:'pending'` + `requiresAction:true` |
| 35 | `utils/parts.ts:250-270` `frontendResultStatus` | **PASS（订正）** | 声明 **`:250`**（③b 卡写 `:232-250` = ③b 前基线）⇒ 本批引用用 `:250-270`；内含 D9 三对防漂移注记（`:242-249`） |
| 36 | `stores/ai.ts` 文件规模 | **PASS** | **1908 行**（③b 时 1844 行） |
| 37 | `stores/ai.ts:1336-1354` `cancelGenerativeForm`（**A4' 写点**） | **PASS** | `:1341` `form.status = 'cancelled'`；`:1344` `applyPendingTerminal(form.toolCallId, 'cancelled')`；`:1345` `disarmFormExpiry()`；`:1346` `await submitFrontendToolResult(..., true, form.runId)`；`:1347-1350` 收敛早退守卫；**`:1351-1353` 失败分支（文案本体 `:1352`）** |
| 38 | `stores/ai.ts:1765-1780` `applyPendingTerminal` | **PASS** | `:1767` `approved → running` 映射；`:1774` 排除 `unknown`/`failed`；`:1775` 覆盖域三子句（`pending` / `cancelled` / `requiresAction`）；**:1776 `requiresAction: false`（+ `status: mapped`）**；`:1777` `deriveProjections(item)` |
| 39 | `stores/ai.ts:1852-1908` `runFrontendTool`（**D-C2 / U-1 依据**） | **PASS（订正）** | 声明 **`:1852`**（③b 卡写 `:1800-1842`）；`:1858` `const controllerAtStart = currentAbort`；`:1864-1870` 开跑落 `running`（`createIfMissing=false`）；**`:1872` `await executeFrontendTool(...)`**；**`:1883` `if (currentAbort !== controllerAtStart) { return }`**；`:1889` `status` 三态；**`:1893-1900` 段补丁（不含 `reason`）**；`:1902-1904` 降级 notice；`:1905` `await submitFrontendToolResult(...)`；`:1908` 闭合 |
| 40 | `stores/ai.ts:1235` `hasDecidedPendingResidue`（**TS-8 对象**） | **PASS（订正）** | 声明 **`:1235`**（③b 卡写 `:1210-1219`）；`this.messages.some(...)` **跨消息** + `isFrameDecidedPendingStatus(part.status)` |
| 41 | `stores/ai.ts` 闸调用点 6 处 | **PASS** | `:753` / `:765` / `:1571` / `:1615` / `:1636` / `:1688`（**本批零触碰**，TS-8 断言） |
| 42 | `stores/ai.ts:400` `isFrameDecidedPendingStatus` | **PASS（订正）** | 声明 **`:400`**（草案/③b 写 `:388-392`）；7 态含 `unknown`/`failed` |
| 43 | `stores/ai.ts:347` `frameStatusToPendingStatus` | **PASS（订正）** | 声明 **`:347`**（草案写 `:340-364`）；`:354-356` 为 `CANCELLED` 三行 run-cancel 依据注释（③b 误述订正指向的 `:347-349` 在 `505f6d4` 基线） |
| 44 | `stores/ai.ts:193-206` `migrateMirror` v1 组合面（**R7**） | **PASS** | **`:188` `reason: run.reason ?? null`（写点 3/3）**；`:193` `const pending = message.pendingCall`；`:200-201`「status 一律以 run.status 派生为权威」；**`:203` `requiresAction: pending.status === 'pending'`** |
| 45 | `stores/ai.ts:606-613` `loadHistory` 归一（③b D4 落码） | **PASS（订正）** | `:606-608` `{ ...message, streaming: false, done: true }`；**`:613` 段级 `message.parts.map(part => 'streaming' in part && part.streaming ? { ...part, streaming: false } : part)`**；`:618` `sanitizeMirrorResidues(this.messages)`（③b 卡写 `:601`） |
| 46 | `stores/ai.ts:420` / `:440` notice 与 activeForm 声明 | **PASS** | `:420` `notice: null as string \| null`；`:439-440` 注释「与 notice 分开，便于面板用不同级别的 Alert 呈现（notice 覆盖更多降级场景）」；`:451` `activeForm: null as ActiveGenerativeForm \| null` |
| 47 | `stores/ai.ts:1222-1224` notice 兜底（**D11：保留不动**） | **PASS** | `:1222` `const message = error instanceof Error ? error.message : '前端工具结果回灌失败'`；`:1223` `this.notice = message`；`:1224` `return { ok: false, message }` |
| 48 | `stores/ai.ts` `activeForm = null` **7 处**（**U-10 订正**） | **PASS（订正）** | `:933`（断流）/ `:1090`（重挂断流）/ `:1371`（`stop()`）/ `:1433`（`resetSession`）/ `:1672`（`frontend_tool_result`）/ `:1717`（`done`）/ `:1733`（`error`）——**DS Pro 记 5 处，实为 7 处** |
| 49 | `stores/ai.ts:246` `expireResidueToolParts` | **PASS（订正）** | 声明 **`:246`**；调用点 `:653`（历史空 ⇒ 降级）/ `:1713`（`done`）/ `:1730`（`error`）；③b 卡写 `parts.ts:242-249` + `ai.ts:1661`/`:1678`（`505f6d4` 基线） |
| 50 | `stores/ai.ts:461-476` `pendingToolCall` getter（**U-7 订正**） | **PASS** | 声明 `:461`、闭合 `:476`；`:467` 命中 `role === 'assistant' && message.pendingCall`；`:470-471` 仅 `status === 'pending'` 返回；`:479` `pendingConfirm` 消费 |
| 51 | `stores/ai.ts:725-817` `restoreSuspendedSnapshot`（**U-7 订正**） | **PASS** | 声明 `:725`、闭合 `:817`；`:726-727` 入口捕获 `sid` / `abortAtEntry`；`:737-739` 身份二次对账；`:659`/`:708` 两个调用点 |
| 52 | `stores/ai.ts:1569-1574` 跨宿主已决终帧守卫（③b D6/D8 落码） | **PASS** | 体检报告 §9.3 记 `:1569-1574`；与 §12-41 的 `:1571` 调用点一致 |
| 53 | `stores/ai.ts:1615-1617` / `:1636-1641` / `:1686-1690` 三 case 去 GF 前缀守卫 | **PASS** | 体检报告 §9.3 逐点记明；与 §12-41 的 `:1615`/`:1636`/`:1688` 调用点一致 |
| 54 | `stores/ai.ts:1523` / `:1543` 轮次归属守卫（`ownedByActiveRound`） | **PASS** | `:1523` `const ownedByActiveRound = assistantId === undefined \|\| assistantId === activeRoundHostId`；`:1543` `if (!ownedByActiveRound) { … }`（**D8 现态失效链的依据**） |
| 55 | `stores/ai.ts:267` `DERIVE_FRAME_TYPES` + `:1745` 使用点 | **PASS** | `:267` `const DERIVE_FRAME_TYPES: ReadonlySet<string> = new Set([…])`；`:1745` 全量重算判据 |
| 56 | `types/ai.ts` 文件规模与关键接口 | **PASS** | **442 行**；`:165` `ToolRunStatus` 九态；`:184` `ChatMessage`；`:200` `parts: MessagePart[]`；`:225` `TextPart`；`:233` `ReasoningPart`；`:243-268` `ToolPart`（`:254` `reason?: string \| null`、`:258` `requiresAction: boolean`、`:265-267` `timeoutSeconds`/`expiresAt`/`atMs`）；`:271` `MessagePart`；**`:280-294` `ToolPartPatch`（`:284` `requiresAction` 必填 / `:288` `reason` 可选）**；`:297-325` `PendingToolCall`（status 8 态含 `approved`，**无 `unknown`/`failed`**）；`:341` 镜像 `Pick`（不含 `done`）；`:361` `ActiveGenerativeForm` |
| 57 | `types/ai.ts:133-150` `AiHistory` / `AiHistoryMessage` | **PASS** | `:134-143` 字段 = `type`/`text`/`toolCalls?`/`toolResponses?`/`timestamp`（**无状态字段**）；`:146-150` = `sessionId`/`count`/`messages` |
| 58 | `types/tools.ts:142` `FrontendToolExecution.reason`（**U-1**） | **PASS** | `:142` `reason?: string`；生产零读者（`runFrontendTool` 段补丁 `:1893-1900` 不含 `reason`） |
| 59 | `frontend/tests/` 清单与例数 | **PASS** | **11 个 spec** + `setup.ts`；**静态 `it(` = 113**（逐文件 **7 / 5 / 9 / 30 / 5 / 15 / 22 / 4 / 3 / 4 / 9**）；另 **1 条 `it.each`** 于 `tools-and-deadline.spec.ts:31`（参数表 `:32-43` **12 组**）⇒ 运行时以 `yarn test` 实跑为准（推算 = 113 + 12 = **125**，见《决策卡-批4④.md》§附 A-1） |
| 60 | `frontend/tests/ai-panel-parts-render.spec.ts` | **PASS** | **243 行 / 7 例**（`it(` 于 `:75` / `:94` / `:119` / `:134` / `:165` / `:186` / `:217`；两个 `describe` 于 `:74` / `:153`） |
| 61 | 同上 三路锁断言体 | **PASS** | `:180-182`（重投：`pending-card-*` `toHaveLength(0)` + 段 `toHaveLength(1)`）；`:210-213`（新宿主：同 + `toMatchObject({ status: 'unknown' })`）；`:238-240`（历史整替：同） |
| 62 | 同上 `:106-115` 单槽跟随位置断言 | **PASS** | `:106-108` `findAll('[data-testid^="pending-card-"]')` 长度 1 + `data-testid === 'pending-card-c1'`；`:111` `html.indexOf('启动导出作业')`；`:112` `pending-card-c1`；`:113` `html.indexOf('列出任务')`；`:114-115` 两条比较 ⇒ **D-C10 必改对象** |
| 63 | `frontend/vitest.config.ts` | **PASS** | `include: ['tests/**/*.spec.ts']`；`environment: 'jsdom'`；`setupFiles: ['./tests/setup.ts']` ⇒ **`scripts/m2t2a-smoke.mjs` 不在 `yarn test` 面** |
| 64 | `frontend/package.json` | **PASS** | `:11` `"test": "vitest run"`；`:12` `"test:watch": "vitest"`；`:37` `"vitest": "4.1.11"` |
| 65 | `frontend/vite.config.ts` dev 端口 | **PASS** | `:11` `const DEFAULT_DEV_PORT = 5200`；`:16` `const devPort = Number(env.VITE_DEV_PORT \|\| DEFAULT_DEV_PORT)`；`:26` `strictPort: true` ⇒ **必须显式 5202** |
| 66 | `frontend/src/utils/blocks.ts` | **PASS（不存在）** | `ls frontend/src/utils/` 输出 = `excel.ts` / `format.ts` / `frontend-tools.ts` / `job-watch.ts` / `parts.ts` / `spreadjs.ts` ⇒ **无 `blocks.ts`**；`grep -rn "groupParts\|expandedBlocks\|blocksOf"`（`frontend/src` / `scripts` / `frontend/tests`）**零命中** |
| 67 | `scripts/m2t2a-smoke.mjs` 骨架 | **PASS** | **488 行**；`:53` `TESTID_TAG_CANCELLED = 'tag-form-cancelled'`（`:39`/`:166` 注释）；`:167-179` `countExpr`（**`:174` `pendingStatuses` 读 `m.pendingCall`**、`:176` `cancelledTags`）；`assert` 函数 **`:183-187`**；**具名断言 4 个**，`assert('` 调用点 **5 处**（`:285` ② / `:297` ③ / `:299` ③b / `:346` ① / `:457` ①）；`:294` 空数组 `every()` **提前 `break`**；`:306-312` `SKIP_MODEL` 分支；`:487` `process.exit(failures.length === 0 ? 0 : 1)` |
| 68 | `scripts/m2t2a-smoke.mjs:299` 断言③b 原文 | **PASS** | `assert('③b', c2.formRendererCount === 0 && c2.cancelledTags === 2, { formRendererCount: c2.formRendererCount, cancelledTags: c2.cancelledTags })` |
| 69 | `scripts/chat-panel-smoke.mjs` | **PASS** | **823 行**；**13 具名断言**（`:454`① / `:472`② / `:487`③ / `:514`④ / `:529`⑤ / `:558`⑥ / `:570`⑦ / `:602`⑧ / `:638`⑨ / `:669`⑩ / `:696`⑪ / **`:737`⑬** / **`:797`⑫**）⇒ **U-9：⑬ 物理先于 ⑫**；**与 GF 表单链路零重叠**（`FormRenderer\|gf-submit\|pending-card\|tag-form-cancelled\|generative_form` 命中数 **0**） |
| 70 | `scripts/verify-e2e.ps1` PASS/GFSKIP 口径 | **PASS** | `:2344` `Say (" 结果：PASS={0}  FAIL={1}  …")`；`:2345` GFSKIP 摘要口径注释；`:2347` 桩模式零容忍文案；**`:2351` `Say (" GFSKIP={0}  （{1}）" …)`**（GLM F-7 记 `:2352`，**差 1 行**）；`:2354` 零容忍判定注释；`:49`/`:67` 退出码与计数声明 |
| 71 | ③b E2E 基线证据 | **PASS** | `批3b-evidence/E2E-stub-final.log` 末段：「TC19 清理完成」「Q8①/Q8② PASS」「**PASS=28  FAIL=0**」「**GFSKIP=0**」「Q8 项：PASS=2 FAIL=0」「e2e_exit=0」 |
| 72 | ③b CDP 冒烟证据 | **PASS** | `批3b-evidence/cdp/20261011T182213Z-重投后仍只有一张卡-{baseline,final}.png` + `result-20261011T182213Z.json`；仓外脚本 = `<SCRATCH>\chat-timeline-pkg\batch3b-lifecycle-smoke.mjs`（**头部含 D13(B) 复跑纪律**「复跑前必须重启 dev server」+ 配方） |
| 73 | `施工报告-批3③b.md` | **PASS** | **224 行**；`:73` / `:204` 记「重投路渲染锁 **green-at-RED**」与原 D8 锁互证；`:172-182` §8 台账销记建议，**第 7 条（`:182`）** = A7'/A8'/A10' 的登记出处（要求批 4④ 改写或退役 m2t2a + 把「重投路渲染锁改为自承 RED」列入测试锁） |
| 74 | `docs/M2-排期计划.md` 台账行 | **PASS** | **499 行**；`:276` **R-84**（行尾注记含「本批未动…随 R-84 批一并结构化」+ 22 处逐处行号）；`:328` R-139 / **`:329` R-140（含 ①-1 终态挂起卡 ⇒ 批 4④、①-6 `<details>` 持久化 ⇒ ④批）** / `:330` R-141 / `:331` R-142 / `:332` R-143 / **`:333` R-144（后端历史携带状态）** |
| 75 | 上游基线文档（**仓外件**） | **PASS** | `<SCRATCH>\chat-timeline-pkg\施工卡-刷新版.md` **1020 行**（`:475` = 「### 4.4 子包④ 折叠规则（三规则照抄业界）」；`:484` 单调用组不套壳；`:490` 规则② 括注「若入 sessionStorage」；`:669-675` R4-1~R4-5（`:675` = R4-5）；`:679-690` §8 不做清单）；《决策卡-批3③b.md》341 行（D1 块 `:115-121`、`D1(B)` `:117-120`、D16 `:251-262`）；《批3③b-施工卡.md》564 行（§5 死码清单 `:339-361`、§5.2 排除项实体、§10 锚点表 `:495-550`） |
| 76 | 行号索引件 `体检报告.md §9`（**仓外件**） | **PASS** | `<SCRATCH>\pkg-health\体检报告.md` **629 行**；`:569` 「## 9. 2026-10-12 批4④前置刷新注记」；`:577` 刷新时 HEAD = `296ffe2`；**`:607-611` 失锚 3 处**；**`:617` 的关键现值表**（含 `AiPanel.vue` 18 处新旧对照 + 「活分支只剩 `activeForm` 提交窗口（`:247-258`）」）；**`:618` 附注（轮询治理区间旁证锚，取用须复核）**；`:619` 卡② 已入库替代事实（`parts` 段模型 / `MIRROR_VERSION=2` / 七态入口闸 `:400-404` / 跨宿主守卫 `:1569-1574` / 三 case 守卫 / `runFrontendTool` 句柄比对 `:1856-1885`）；`:620` ③a/③b 收口后残面（22 处 + R-144） |

**另核的支撑锚点（施工棒可直接引用）**：`stores/ai.ts:1517-1756`（`handleFrame` 整段，体检报告记）；`:653`/`:1713`/`:1730`（`expireResidueToolParts` 三个调用点）；`:1810-1813`（`applyFrontendToolTerminal` 区，体检报告记 `:1749-1774` 为 `505f6d4` 基线）；`utils/parts.ts:29-31`（`nextSeg`）；`:44-61`（`openStreamPart`）；`:69-74`（`sealTrailingStreamPart`）；`:77-80`（`findToolPart` 单消息域）；`utils/frontend-tools.ts:970-989`（`interpretHandlerOutcome`，**红线文件，仅作引用**）；`frontend/tests/frontend-tool-outcome.spec.ts:50`（`execution.reason` 的**测试域**读者）。

---

## §13 签字

| 项 | 值 |
|---|---|
| 定稿人 | **K3（代签，署名口径 DC-19）** |
| 签字日期 | **2026-10-12** |
| 授权来源 | **K3 指挥官派单 · 批4④ 定稿卡** |
| 落稿执行子智能体 | **`agent-540`**（CU 会话 `session_07374787-e888-4a2b-86d7-bd63b19ee73a`）；**实际运行模型 = `DeepSeek/deepseek-flash`**（wire 实证见《决策卡-批4④.md》§7；**非配置推定**） |
| 配套文档 | 《决策卡-批4④.md》；草案《批4④-施工卡草案.md》（**仅存档**，sha256 `d6835ebcb0404c93d7f17e33020984e1efbc6a38914a0b4424ab851dc5a57e82`） |
| 本批正式证据目录 | **`<SCRATCH>\chat-timeline-pkg\evidence-p17\`** |

> **落稿棒自证与局限**：本棒**未跑** `yarn typecheck` / `yarn test` / `yarn build` / `scripts/verify-e2e.ps1`；**未启** dev server / 后端 / Redis；**未做** CDP 冒烟与注入实验；§12 全部结论为**静态实读**。本棒**未读任何 `.env`**、**未改主仓库任何文件**、**零 git 写**；对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` **零操作**。**机械活下沉**：本棒运行环境**无 `Agent`/`Task` 派发面**（`wire.jsonl` 内无 `subagent.spawned` 记录）⇒ 机械活由本体内联完成，**三条（下沉 / 钉版 / 子智能体自证）未能履行**，属工具面限制（详见《决策卡-批4④.md》§附 A-3）；施工棒的对应要求已写入 **§11.3 第 5 条**。
