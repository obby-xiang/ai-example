> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# GF 回填 edge fix · Scoped Re-review

- 审查者：`agent-1131`（独立审查子智能体）
- 自证复核：自身 wire `profile.bind` 仍为 `agentId=agent-1131`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- BASE：`527441936715eaa6bc395f1a1ced9cc0314b2604`；HEAD = 未提交工作树。
- 当前全量 diff：`<SCRATCH>/_cu-gf-fix-r26/edge-review.diff`（897 行，8 文件）。
- 只读复审：未改文件、未提交、未起停服务、未重跑全量测试。

## 1. 范围

本批实际改动（报告 §7.1 与 diff 一致）：

- `frontend/src/utils/parts.ts`：新增 `submittedValuesFromResult`（导出）、`writeSubmittedValuesToMessage` 单一提交值写口；帧路径改走该写口并加 `frameStatus === 'succeeded'` 门。
- `frontend/src/stores/ai.ts`：`writeSubmittedValues` 改为纯函数写口 + `overwrite` 参数；`frontend_tool_result` 落段后按帧宿主落定值对全量消息 first-wins 重放；`submitGenerativeForm` 的 duplicate 分支不再写本地值。
- `frontend/tests/r166b-fixes.spec.ts`：+6 用例（frame-only 多宿主 3、409 3）。

工作树另有 `docs/demo/README.md`、`docs/任务看板.md`、`docs/决策看板.md` 三个 **他人看板改动**，报告明载未触碰；`edge-review.diff` 按 `git diff 5274419 --` 全量生成，已包含它们。提交时须逐路径 add，勿裹入本修复。

## 2. 两项此前 Important 的裁决

### ① frame-only 多宿主 first-wins 全匹配补齐 —— **ADDRESSED**

证据链：

- `parts.ts:348-376` `writeSubmittedValuesToMessage(message, toolCallId, values, { overwrite, decidedAt })`：遍历消息内**全部**匹配段，`overwrite=false` 时逐段 first-wins，返回是否改写；同消息重复段也不漏。
- `parts.ts:540-542`：帧解析出的值在该宿主消息内经同一写口落值（`overwrite:false`）。
- `stores/ai.ts:2826-2830`：`applyFrameToParts` 后读取帧宿主 `settled.submittedValues`，若为 GF + succeeded + 有值，则 `writeSubmittedValues(..., false)` 对**全部消息**幂等重放：空宿主补齐、已有值保留；坏 JSON / 非 GF / 非成功帧不广播。
- 测试 `r166b-fixes.spec.ts:405-460`：两宿主皆空 → 都得到帧值；帧宿主已有 OLD → OLD 保留且空宿主按 OLD 补齐；坏 JSON / 数组 / REJECTED / 非 GF → 一个都不写。RED 证据：edge-red 两个正例均失败（空宿主未补齐、first-wins 值未补齐）。

结论：此前“帧降级路径只写帧宿主”的缺口已闭合；多宿主取值一致，且 first-wins 语义保留。

### ② 409 DUPLICATE 不写本地值 / 不覆盖先到值 —— **ADDRESSED**

证据链：

- `stores/ai.ts:2275-2280`：`if (!outcome.duplicate) this.writeSubmittedValues(...)`；duplicate 不再写本地 values，仍保留 `form.status='submitted'` 收口。
- 409 的状态收敛仍由 `submitFrontendToolResult` 内的 `applyFrontendToolTerminal`（响应体 status）承担，未改。
- 测试 `r166b-fixes.spec.ts:482-530`：先到 HD + 本地 HB → 409 后仍 HD；空段 + 409 → 保持 undefined；正常 ok → 仍写 HB（证明防护未削弱 POST 成功路径）。RED 证据：edge-red 两个正例失败（HB 覆盖 HD、空段被写 HB）。

结论：此前的“本地值覆盖/冒充权威值”问题已闭合；正常 POST ok 行为未回归。

## 3. 新增 Critical / Important 检查

- **单一写口**：`writeSubmittedValuesToMessage` 同时服务 POST 与帧路径，`writeSubmittedValues` 仅做全消息遍历 + 投影派生；未发现双写口径分裂或新的状态机分叉。
- **POST 回归**：`submitGenerativeForm` 正常 `ok` 路径不变；`writeSubmittedValues` 默认 `overwrite=true`，全量写回/`decidedAt` 同刻/`requiresAction:false` 行为与上批一致。409 分支是有意行为变更，不是回归。
- **帧语义**：`status` 仍是 localTerminal first-wins；新增 `frameStatus === 'succeeded'` 门只收紧“帧显式失败/取消但段已 succeeded”时不伪造值，符合既有单调语义。
- **多宿主投影**：每次实际改写均 `deriveProjections`；广播只对 touched 消息派生。
- **类型/构建**：edge-typecheck exit 0；edge-full 460/23 通过。

未发现新的 Critical 或 Important。

## 4. 剩余 Minor（不阻塞）

1. **409 duplicate 不再记 `decidedAt`**：旧实现经 `writeSubmittedValues` 落该字段；现在 duplicate 跳过，`applyFrontendToolTerminal` 也不写。数据层字段、卡面不渲染、无逻辑消费，故仅 Minor。若要保持元数据口径，可在 duplicate 分支只写 `decidedAt/requiresAction:false`（不写值）。
2. **409 响应体无 status 时不回收挂起段**：`applyFrontendToolTerminal` 仅在 `mapped` 非空时调用；旧代码的 `requiresAction:false` 侧效随 duplicate 跳写一起消失。当前后端 `duplicateToolCall` 恒带 status，属防御性缺口；建议 duplicate 分支至少补 `requiresAction:false`。
3. **同消息“混合重复段”可能值不一致**：若同一条消息内既有带旧值的段、又有空重复段，帧值只补空段，`settled` 取首个段的旧值再广播，空段会留在帧值上。`upsertToolPart` 不变式下同消息不应有重复段，故仅 Minor；更稳的做法是每帧先确定一个权威值再写给全部匹配段。
4. **`submittedValues: null` 被当作“已有值”**：first-wins 判据用 `!== undefined`；若外部/镜像注入 null，会挡住回填。现有写路只产出 undefined/对象，故仅 Minor。
5. **测试缺口（低风险）**：未断言广播后的 `pendingCall/toolRuns` 投影；未覆盖同消息重复段；未覆盖 409 无 status。核心帧/409 行为已有真实 store 级断言。
6. **提交卫生**：`edge-review.diff` 含 3 个他人 docs 改动；本次不评审其内容，提交时排除即可。

## 5. Verdict

**READY（可提交）。**

两项此前 Important 均已 ADDRESSED，未引入新的 Critical/Important，POST 成功路径无回归；edge-red 4/25 失败 → edge-green 255/5、edge-full 460/23、typecheck 0 的证据链成立（`edge-src-backup` 与最终两文件 sha256 一致，full 跑在备份同字节内容上）。剩余均为 Minor/提交卫生项。
