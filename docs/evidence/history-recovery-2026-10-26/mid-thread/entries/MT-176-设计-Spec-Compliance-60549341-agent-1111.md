<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-176 · 设计 · Spec Compliance（60549341 / agent-1111）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m32` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 4 条有正文），末条有正文消息为 `m39`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `60549341` / agent `agent-1111`（`main` = 主会话）；原字符 5,845（链内 1 段、合计 5,845）。
> **链号 / 类别**：`MT-176` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `10%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m39`（2,698 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

自证：本会话 wire `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_60549341/agents/agent-1111/wire.jsonl` 的 `profile.bind` 单行为 `{"type":"profile.bind","agentId":"agent-1111","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}`；模板已读。审查包已生成到 `<LOCAL_WORK>/_cu-composer-project-r26/phase1-review.diff`（status + stat + 完整 diff；HEAD==BASE，log 为空）。未改仓库、未提交、未起停服务；未重跑测试。

### Spec Compliance

**Spec: ✅**

可核验的批准口径全部满足：

- **仅两文件**：`git status` 只有 `frontend/src/components/AiPanel/AiPanel.vue` 与 `frontend/tests/ai-panel-parts-render.spec.ts`；`?? data/` 的 mtime 为 2026-10-12，属开工前既存，未进入 diff。`stores/ai.ts`、后端、队列、API、#3/2A、demo/docs 均未改。
- **最多 4 行 / 101px**：`AiPanel.vue:733-734` 的 `rows="1"`、`min-h-[40px]`、`max-h-[101px]`、`overflow-y-auto`、`[field-sizing:content]` 与 demo `composer-decisions.html:62` 逐类一致；未设固定 `height`，符合 field-sizing 覆盖规则。
- **白底 shell + focus-within 蓝环 + 左工具位 + 28×28 圆形钮**：`AiPanel.vue:731-762` 与 demo `60-68 / 131-135` 对齐；发送/停止均 `rounded-full`，停止内为 9×9 白方块。
- **流式可编辑 + 圆形停止**：`AiPanel.vue:735` 仅挂 `composerHardDisabled`（`1302-1308`），不含 `ai.loading`；停止钮 `744-752` 只调用 `stop`。测试 `spec:3250-3261`、`3396-3410` 覆盖。
- **流式普通 Enter 静默**：`AiPanel.vue:1331-1350` 先 `preventDefault()`，`loading` 或 `repeat` 直接返回；不调用 `send` / `stop` / `noticeBusySend`，无排队。测试 `3299-3325` 验证了发送、停止、提示、`api.chat`、草稿值五条。
- **Shift+Enter / IME / repeat 与 demo 一致**：`1332-1347` 与 demo `354-372` 的 `key !== 'Enter' || shiftKey`、`isComposing || keyCode 229 || active`、30ms、`repeat` 顺序一致；Ctrl/Meta/Alt 不豁免，也与 demo 相同，未越出 demo。
- **真实 hard-disabled**：`composerHardDisabled` 覆盖 health 明确不可用、`healthError`、`suspended`、`pendingToolCall`；首帧 `health === null && healthError === null` 不硬禁用，逻辑正确（store 初值 `ai.ts:812-813`）。
- **不改全局 canSend**：`ai.ts` 未改；`canSend` 仍为 `!loading`（`ai.ts:924-926`）。新的 `canSubmit` 去掉 `ai.canSend` 不影响发送钮，因为发送钮只在 `!loading` 时渲染。
- **全 Tailwind 无行内样式**：源码无 `style=`、无 `<style>`；新测试断言 textarea / 发送 / 停止无 `style` 且无 `<style>` 标签。
- **阶段二未提前**：`send()` 仅改注释（`2644-2679`），无返回值、失败保稿、发送后焦点恢复；diff 无相关实现。
- **测试未被削弱换绿**：只替换了旧的 composer 结构锁（旧断言 `rows=3`、灰底、方钮、busy 虚线+禁用，均与新口径冲突），未删除其它既有测试断言。RED 证据为 6 failed / 141 passed（`phase1-red.log`），GREEN 147/147、全量 418/418、typecheck 通过；targeted 日志无 Vue warn/stderr，full 日志中的 406 条 Vue warn 均来自 `task-center-view` 等其它 spec，不是本改动引入。

**⚠️ Cannot verify from diff alone**

- 真实浏览器中的 IME 行为、`field-sizing: content` 实际增高/101px 封顶、以及 blur 场景，jsdom 只能锁类名和事件分支；报告也明确未做实机 IME 验收。建议控制方补一次真实浏览器 + 中文输入法验收。
- demo 的 idle form 在左工具位画了虚线「工具位（预留 · 留空）」（`composer-decisions.html:64`），实现用的是空 `span.flex-1`（`AiPanel.vue:743`）；报告称「左工具位留空」已获批准。请确认这是批准后的裁剪，而不是漏掉 demo 的可视占位。

### Strengths

- hard-disabled 拆分准确：把 `loading` 从硬禁用中剔除、把 health 未探测视为未知态，既满足流式可编辑，也没有首帧闪禁；同时没有动 store 的 `canSend`，边界清楚。
- Enter 处理顺序与 demo 高度一致，`preventDefault` 语义正确；`repeat`、IME、30ms 守卫都有真实 KeyboardEvent/CompositionEvent 测试，不是只测 mock。
- 形态锁写得实：shell、footer、左占位、tip、28×28、无原生 `title`、无内联样式均有断言；field-sizing 回退与 demo 同类名，未引入 JS 镜像。
- 注释同步到位，`send()` 的纵深防御注释已改成新口径，没有留下描述旧行为的文档。
- 全量 418 通过、typecheck 通过，targeted 无警告，证据链完整。

### Issues

#### Critical (Must Fix)

无。

#### Important (Should Fix)

1. **IME 组合态缺少 blur 复位，可能让 Enter 从此不发** — `AiPanel.vue:737-739` 只挂了 `keydown` / `compositionstart` / `compositionend`；`1315-1325` 的 `composerComposing` 只由 `compositionend` 清零。demo 在 `composer-decisions.html:374-379` 额外用 capture `blur` 复位 `active` 与 `endedAt`。本实现没有这个兜底：若用户在组合中被 hard-disable（`suspended` / `pendingToolCall` 会令 textarea 失焦）或焦点异常丢失，而浏览器未补发 `compositionend`，`composerComposing` 会保持 `true`，之后按 Enter 会命中 `1335` 的 composing 分支直接 return（不 `preventDefault`），表现为「回车只换行、永远不发送」，直到下一次组合结束或刷新。
   **建议**：加 `@blur="onComposerBlur"`，在 handler 中复位 `composerComposing = false`、`composerCompositionEndedAt = null`；补一个 `compositionstart → blur → 重新聚焦 → Enter 应发送` 的测试。这是本单唯一阻塞项，修复约 3 行 + 1 个测试。

#### Minor (Nice to Have)

1. **首屏未知态与 `healthError` 分支无测试** — `spec:3137-3154` 的 `mountPhase1` 在挂载前先 `probeHealth()`，因此 `composerHardDisabled` 的「health 为 null 且无 error」分支（`AiPanel.vue:1304`）从未被跑到；`3262-3298` 只测试了 `available: false`，没有 `healthError`。建议补：health 保持 null 时 textarea 不 disabled、发送钮 disabled；`healthError` 非空时 textarea disabled。这正是用户点名要查的首屏风险。
2. **tip 外层 span 缺 `aria-hidden="true"`** — `AiPanel.vue:751,761` 的 `.tip` 外圈没有 `aria-hidden`，只隐藏了箭头；demo `composer-decisions.html:66,133-134` 在 tip 外圈就写了 `aria-hidden="true"`。因为按钮有 `aria-label`，可访问名不会重复，但 tooltip 文本仍会留在辅助技术的文本树里。建议给两个 tip 外圈补 `aria-hidden="true"`（或 `role="presentation"`），并同步测试。
3. **Enter 路径未看 health available，和发送钮状态不一致** — `AiPanel.vue:1331-1349` 只判 `ai.loading`，而 `canSubmit`（`1311`）还要求 `ai.available`；`send()` 的守卫只检查 `ai.canSend`（`ai.ts:924-926` = `!loading`）。在首屏 health 未知的短暂窗口里，发送钮是 disabled，但按 Enter 仍会走到 `send()`。这是既有模式，但新 handler 保留了它；建议在 Enter 分支中先看 `canSubmit` / `ai.available`，或把 available 判断下沉到 `send()`。
4. **hard-disabled 时发送钮未同禁** — `AiPanel.vue:759` 的 `:disabled="!canSubmit"` 不含 `composerHardDisabled`。若 `loading=false` 但 `suspended` / `pendingToolCall` 令 textarea 硬禁用，且输入框里已有旧草稿、health available，`canSubmit` 仍可能为 true，发送钮可点，随后 `send()` 因 `canSend=!loading` 为 true 而真正发送，绕过 hard-disable。该状态不常见，且旧实现也允许发送，但新 hard-disable 的语义不完整；建议改成 `:disabled="composerHardDisabled || !canSubmit"` 并补测试。
5. **IME 测试未单独压 `composerComposing` 分支** — `spec:3326-3348` 先 `compositionstart`，但 Enter 同时带 `isComposing: true`，无法区分是 `event.isComposing` 还是本地 `composerComposing` 拦下的。建议加一条「`compositionstart` 后、不带 `isComposing`/`keyCode` 的普通 Enter 不发送」的用例（与 Important 的 blur 测试一起补）。

### Assessment

**Task quality:** Not approved as-is — Needs fixes（仅 Important 第 1 项；其余口径均达标）

**Reasoning:** 表单形态、Enter/IME 主路径、hard-disabled 拆分、测试证据都与批准口径一致，且没有削弱既有测试；唯一阻塞是组合态缺少 demo 明确具备的 blur 复位，存在「Enter 只换行不发送」的粘滞失败模式，修复和回归测试都很小。

**READY / NOT READY:** **NOT READY**（补上 blur 复位 + 测试后可转 READY；若团队明确把该边缘 IME 场景留到后续验收，则其余部分可直接视为 approve-ready）。
