> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-973` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：8853 B / 5460 字符 / 87 行；行指纹覆盖率（仓内 + 落盘语料）1.5%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 2A 批施工完成报告（纯前端）

### 一、已决 HITL、GF 只读锁定态重写（核心）

**判定层** `frontend/src/utils/blocks.ts`
- `Block` 新增 `messageId`（:30）——「回合仍在进行」判据用；`defaultExpandedOf` 改为按只读终态分派（:57-63）。
- `isReadOnlyCard` 收窄：`blocked / unknown / pending` **不是决策态**，仍走普通工具卡（状态胶囊承接「超出当前范围 / 结局未知 / 等待确认」），不进审批只读四态（:303-317）。这一取舍保住了既有 R-144 的「blocked → 超出当前范围」回归锁，也避免把非决策读成「已超时」。
- `readOnlyStateOf`（:334-357）：门卡恰四态 `已批准 / 已拒绝 / 已超时 / 已批准·执行失败`（`cancelled` 归未批准收尾=已超时，闸门侧无「已取消」）；GF 两态 `已提交 / 已取消`。
- `readOnlyDefaultExpanded`（:363-366）：成功折叠、四类例外展开。
- `formSpecOf`（:372-378）：已决 GF 复用同一张表单的 schema。

**模板与呈现层** `frontend/src/components/AiPanel/AiPanel.vue`
- 只读卡整段重写 `:281-360`：
  - 卡体 = 原样 UI：门卡直接复用与待决态同源的 `gateRows` 字段表（操作 + args 全键 + 敏感掩码，`min(280px,35vh)` 内滚，`ro-rows-*`）；GF 复用 `FormRenderer readonly`（六型控件原生 disabled、值回填）；**卡体零按钮**（批准/拒绝/提交/取消均不渲染）。
  - 卡头 = 状态徽标 + 人话摘要 + chevron 的单行裸行：`px-[10px] py-[8px] gap-[6px]`、无底色无分隔线、chevron `text-[12px] leading-[1.7] ml-auto`（36.4px，与工具卡同规格），展开 `rotate-90`；`aria-expanded` + 原生 `<button>`（Enter/Space 原生可展开）。
  - 卡面**不渲染 decidedAt**（旧时间戳 span 已删）。
- 呈现函数 `:2566-2660`：`readOnlyBadgeClass/Label`（四态 + 表单两态，9.5px/1.7/px-5）、`readOnlySummary`（`已批准 · …` / `已提交 · …`）、`readOnlyFormSpec`（WeakMap 缓存）、`readOnlyFormValues`（仅提交态回填，取消/超时为 null → F9）。
- 折叠时序 `:2147-2174`：`isBlockExpanded` 叠加 `turnStillOpen`（`ai.loading` 且该卡属末条消息）+ `readOnlySuccessCard`——**决策当下不折叠，回合结束才收**；手动触碰（`expandedBlocks` 显式值）永远优先。

**FormRenderer** `frontend/src/components/AiPanel/FormRenderer.vue`
- 新增 `readonly` / `initialValues` props（:199-216）；readonly 下不渲卡头/操作行/时限脚注/错误条（:14、:131、:137、:164），只渲业务标题 + 同一张表单（:22-32）。
- `initialModel`（:232-274）按字段类型合并 `submittedValues`（枚举校验在 options 内、多选过滤非法值）；`disabled` 纳入 readonly（:307）。
- 锁定观感按 §12 H2：`FI_READONLY = disabled:bg-white disabled:text-[#57606a]`（不降对比度，:262-264）。

### 二、缺陷四项

1. **decidedAt 数据层记录**：门卡原有乐观写/权威回填不变；新增 GF 提交 `decidedAt`（`stores/ai.ts:2202`）与 `markFormDecided`（:2212-2226，取消/超时收尾 :2281）。卡面已零时间渲染（与 r10 一致）。
2. **枚举初值合法默认**：`types/form-schema.ts:160-168`——`defaultValue` 缺省/非法时取首个选项，不再落空串。
3. **按钮防折行**：门卡操作行 `flex-wrap` + 按钮组 `flex-none whitespace-nowrap` + 按钮 `whitespace-nowrap flex-none min-w-[56px]/[72px]`（`AiPanel.vue:502-540`）；GF 操作行同样 `flex-wrap` + 按钮 `flex-none whitespace-nowrap min-w-[56px]`（`FormRenderer.vue:137-156`）。
4. **拒因框移除核验**：**核验发现真实残留**（不是「已无残留」）——门卡里仍有 `reject-reason` textarea、`rejectReason` 状态与「换人清空」watcher。已删净（textarea、`resetPendingWatch`/`watchedGateId`、`decide()` 内清空逻辑）；拒绝改提交固定口径 `用户拒绝执行`（后端 reason 可选字段契约不变）。全仓 `grep reject-reason|rejectReason` 零命中。

### 三、限高新尺度 15 处（全 `max-h-[` 清单，逐处注释同步）

| 位置 | 新值 |
|---|---|
| `AiPanel.vue:277` 思考体 | `min(280px,35vh)` |
| `AiPanel.vue:324` 门卡只读参数区（替代旧 :313 只读表槽位） | `min(280px,35vh)` |
| `AiPanel.vue:408` 工具卡 args | `min(280px,35vh)` |
| `AiPanel.vue:426` 工具卡 output | `min(280px,35vh)` |
| `AiPanel.vue:443` 卡内原因行 | `min(280px,35vh)` |
| `AiPanel.vue:449` 冲突明细表体 | `min(480px,60vh)` |
| `AiPanel.vue:484` 待决门卡字段区 | `min(280px,35vh)` |
| `AiPanel.vue:710` 错误卡 detail | `min(280px,35vh)` |
| `AiPanel.vue:1123` 用户长消息 | **184 不动**（注释写明唯一例外） |
| `AiPanel.vue:1132` 代码 pre | `min(280px,35vh)` |
| `AiPanel.vue:1647` mermaid 预览 | `min(320px,45vh)` |
| `AiPanel.vue:1657` mermaid 源码盒 | `min(280px,35vh)` |
| `FormRenderer.vue:32` GF 字段区 | `min(320px,45vh)` |
| `markdown.ts:190` md 围栏 pre | `min(280px,35vh)` |
| `markdown.ts:443` 表格表体 | `min(480px,60vh)` |

注释同步：AiPanel 头注 :934-970（新增整段 2A 口径）、只读卡注释 :281-292、`gateRows` 注释 :2297-2304、限高族注释各处；FormRenderer :27-31 / :126-136；markdown.ts :183-190 / :420-425；blocks.ts 所有只读态注释。

### 四、验证实测

- `cd frontend && npx vitest run`：**393/393 通过 · 23 spec**（基线 386 + 新增 7 例）。
- `npx vue-tsc --noEmit`：**0 错**。
- `yarn build`：**成功**（vue-tsc + vite build，✓ built in 29.35s）。
- `node scripts/ci/check-docs.mjs`（仓库根）：**6/6 全绿**（改文档后复跑仍 6/6）。
- pre-commit 脱敏钩子：两笔均 `✓ 敏感信息扫描通过（暂存区无命中）`。

> 诚实说明：`tests/restore-reconcile.spec.ts` 的「负例①：取数期间换会话」是**既有时间敏感 flake**——基线跑（改动前）就出现 1 failed（385/386，15s 超时），施工中一次全量跑亦复现；单跑 3/3 通过，最终全量跑 393/393 全绿（该用例 12.7s，逼近 15s 上限）。与本次改动无关。

### 五、spec 改动清单

- `tests/blocks.spec.ts`：import 调整；`hasReadOnlyBody` 断言改为 `isReadOnlyCard` 非决策态；新增 3 例（只读态全集与默认展开 / `groupParts` 默认展开分派 / `formSpecOf`）。
- `tests/ai-panel-parts-render.spec.ts`：`mountPanel` 增 `realForm` 选项；重写 3 个只读态用例（门卡原样只读、无参确认、GF 同表单值回填）；五态徽标改为门卡四态 + 例外展开 + 旧字样零残留；决策态徽标改写；GF 摘要断言；限高数值全量替换；R-160 loadHistory/M1 两例改写；表格 regex 与徽标规格用例改写；A1' 门卡用例删拒因框断言并加防折行断言。**新增 4 例**：回合结束才收（含手动收起优先）、F9 GF 取消回初始空态、enum 初值合法默认、徽标规格 + 非决策态不进只读卡。
- `tests/r166-gate-link.spec.ts`：R-166 口径② describe 重写为「拒因框已删除（帧重投/换门卡不复活）」；超时徽标 `! 已超时`（去「作废」）；批准徽标 `✓ 已批准`。
- `tests/r166b-fixes.spec.ts`：RC-2 补 `decidedAt` 断言；新增「GF 取消收尾落 decidedAt 且不写提交值」用例。

### 六、入库

- `c92ce1b` `feat(ai-panel): HITL 已决只读按 r14 裁决重写 + 缺陷四项 + 限高双约束（2A 批纯前端）`（10 files, +870/−412）
- `ec88c8a` `docs: 2A 批落库（任务看板补 2A 行 + 问题2 行进展改成 2A 已闭环；决策看板补 2A 流水一行；锚 c92ce1b）`
- 已 `git push origin main`：`a65f06a..ec88c8a`。

### 七、自证（自身 wire.jsonl）

`profile.bind` 单行：**modelAlias = `DeepSeek/deepseek-flash`**（agentId `agent-973`，profileName `coder`，thinkingEffort `high`）。

### 八、需指挥官知悉的判断点

1. 缺陷④与派活预期不符：**拒因框此前并未删净**，2A 已按裁决 §3「随批删净」处理（纯前端、无后端契约变更）。
2. `blocked / unknown / pending` 保留普通工具卡形态（不进审批只读四态）——为诚实性与既有回归锁，已在代码注释说明；若要求严格「门卡全部强制只读」，需再裁。
3. 门卡 `cancelled`（停止本轮等基础设施收尾）按 K2 呈现 `! 已超时`；GF 侧 `cancelled/expired/failed` 统一 `✗ 已取消`（§2 表单系只有两态）。
4. GF「他端已提交但无本地 submittedValues」时只能渲同一张表单的初始态（摘要仍为「已提交」）——本地无从回填真实值，属数据边界而非渲染缺陷。
5. 未改后端、未启停任何服务、未新增依赖、未手写 CSS。`data/` 仍未跟踪（既有产物，未纳入本批）。
