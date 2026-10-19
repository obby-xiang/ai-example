> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。**历史件（非现行）**：记录被后续批次报告覆盖的中间态，现行口径以最新现势件为准。

# 问题4 composer 项目回落 · 阶段一施工报告

- 日期：2026-10-19
- 仓库：`<REPO_ROOT>`
- BASE：`cb0e926c0b7c72aacc21a27ede1171cc83b15a62`
- 修改文件：`frontend/src/components/AiPanel/AiPanel.vue`、`frontend/tests/ai-panel-parts-render.spec.ts`（仅此两个）
- 未 commit / 未 push；未起停服务；未改后端/队列/API/SSE/#3/2A/Tailwind 配置/demo/docs/stores；`data/` 为开工前既存未跟踪目录，未触碰。

## 0. 开工自证

wire：`<SESSIONS_ROOT>/<SESSION_ID>/agents/agent-1109/wire.jsonl`

`profile.bind` 单行（节选前段）：

```json
{"type":"profile.bind","agentId":"agent-1109","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high", ...}
```

## 1. RED（先只改 spec）

改写旧 composer 结构锁（原 `rows=3` / 灰底 / `rounded-[8px]` 方钮 / `busy` 虚线 + 禁用），新增阶段一行为用例：

- 形态：`rows=1`、`min-h-[40px]`、`max-h-[101px]`、`overflow-y-auto`、`[field-sizing:content]`、白底 shell + `focus-within` 蓝环、左工具位留空、28×28 `rounded-full` 发送/停止钮、TIP_CLASS 气泡、无原生 `title`。
- 流式：textarea 可编辑、无 `disabled`、无虚线。
- 禁用拆分：`loading` 不硬禁用；health 明确不可用 / `suspended` / `pendingToolCall` 仍硬禁用。
- Enter：流式普通 Enter `preventDefault` 且不发送/不停止/不换行/不提示；Shift+Enter 不拦截；IME 组合期 `isComposing` / `keyCode 229` 不误拦；`compositionend` 后约 30ms 守卫；`event.repeat` 无行为；空闲普通 Enter 仍走现有 `send`。
- 停止钮只调用 `stop`，不自动发送、不改草稿。

RED 运行结果（`phase1-red.log`）：**6 failed / 141 passed（147）**，失败均来自功能缺失（`rows` 仍为 3、流式仍 disabled/虚线、loading 仍硬禁用、IME/repeat 仍触发发送）。  
说明：流式 Enter、空闲 Enter、停止钮三个回归用例在旧实现下已通过（旧 textarea 在 loading 时 `disabled`，jsdom 不派发键盘事件），GREEN 后它们才真正压到新 keydown 分支。

## 2. GREEN（仅改 AiPanel.vue）

### 模板 `AiPanel.vue:721-763`

- 外层 composer 容器保留顶细线/内边距；内层新增白底 shell：`rounded-[12px] border border-[#e3e6ea] bg-white focus-within:border-[#2563eb] focus-within:ring-2 focus-within:ring-[#2563eb]/40`。
- textarea：`rows="1"`、`min-h-[40px] max-h-[101px] w-full overflow-y-auto [field-sizing:content]`、`bg-transparent`、`border-0`、`rounded-t-[12px] px-[11px] pt-[11px] pb-[7px]`；`disabled` 改挂 `composerHardDisabled`。
- 底栏：`span.flex-1` 左工具位留空；右侧 `v-if="ai.loading"` 停止钮 / `v-else` 发送钮，均 28×28 `rounded-full`、`flex items-center justify-center`；停止内白方块 9×9；两者复用 `TIP_CLASS` / `TIP_ARROW_CLASS`，删除原生 `title`（避免双 tip）。
- 保留 `data-testid="composer-input"` / `data-testid="send-btn"` / `aria-label="发送"` / `aria-label="停止生成"`。

### 脚本 `AiPanel.vue:1297-1349`

- 新增 `composerHardDisabled`：health 已明确不可用、`healthError`、`ai.suspended`、`ai.pendingToolCall !== null`；**不含 `ai.loading`**。health 尚未探测（`null` 且无 error）视为未知态，不硬禁用，避免首帧闪禁吞输入。
- `canSubmit` 改为 `ai.available && inputText.trim().length > 0`，不依赖 loading；未改全局 `canSend`。
- 新增 `onComposerKeydown`：仅处理 `Enter` 且 `!shiftKey`；`isComposing || keyCode===229 || 组合中` 直接返回（不拦截）；`compositionend` 后 30ms 内 `preventDefault` 不发送；随后 `preventDefault`，`repeat` 或 `ai.loading` 静默返回；空闲才 `void send()`。
- 新增 `compositionstart/end` 本地守卫状态（非响应式，无新增 store 字段）。

### 注释同步 `AiPanel.vue:904-906`、`2652-2653`

- 组件头注释与 `send()` 纵深防御注释改为 composer v2 口径（流式中 Enter 已在 keydown 层短路）。

**本阶段未做**：`send()` 返回值/outcome、失败保稿、发送后焦点回收（阶段二）；未改 `stores/ai.ts`。

## 3. 测试与静态检查

| 项目 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | 6 failed / 141 passed | `phase1-red.log` |
| GREEN targeted | 同上 | **147 passed（147）** | `phase1-green-targeted.log` |
| 全量 | `yarn test` | **23 files / 418 tests 全通过** | `phase1-full.log` |
| 类型检查（附加） | `yarn typecheck` | 通过（exit 0） | `phase1-typecheck.log` |

全量日志含既有测试环境警告（非本次改动引入、无失败）：`Failed to resolve component: el-*`、`Failed to resolve directive: loading`（`task-center-view.spec.ts` 等既有 EP 桩警告）及 R-156 下载链路 stderr。targeted 日志无 `Vue warn`/stderr。

静态检查：

- `git diff --check`：exit 0（仅 Git 的 LF→CRLF 提示）。
- `git status --short`：仅两个允许文件被修改；`?? data/` 为开工前既存。
- 新增行扫描：无 `style=`、无 `<style>`、无 emoji、无 queue/排队相关改动。
- 未新增 CSS 块、未使用内联 style；气泡复用 `TIP_CLASS` / `TIP_ARROW_CLASS`。

## 4. modern-web-guidance 引用

搜索命令（Windows 经 node 调 npx）：

`modern-web-guidance search "textarea field-sizing content IME safe Enter accessible tooltip Vue"`

命中并 retrieve 的指南：

- **`form-fields-automatically-fit-contents`**：用于 `[field-sizing:content]` + `min-h/max-h` + `w-full` 组合。准确引句：*"For textareas, allowing horizontal auto-sizing can cause a jarring UX ... To prevent this, apply an explicit width (like `width: 100%`) to the textarea."*；并遵循 *"Explicit `width` and `height` properties override `field-sizing: content`"* —— 因此只设 `min-h`/`max-h`，未设固定 `height`。
- **`ime-safe-enter-submit`**：用于 Enter/IME 守卫。准确引句：*"The default action of `Enter` in a `<textarea>` is to insert a newline, so you must also call `event.preventDefault()` to suppress that."*；Safari 回退采用 *"Block submission if `event.isComposing` or if `keyCode` is 229"*；指南建议 compositionend 后 50ms 窗口，本实现按已批准的 demo v2 取 **30ms**。

## 5. 风险与关注点

1. **field-sizing 兼容性**：`field-sizing` 为 Baseline Newly available（Chrome/Edge 123+、Firefox 152+、Safari 26.2+）。不支持时按 demo 方案自然回退为 40px 起始 + `overflow-y-auto` 框内滚动，不自动增高；未加 JS 镜像方案。
2. **health 未知态**：为实现“真实不可用才禁用”，health 尚未探测时不硬禁用；探测失败（`healthError`）或明确 `available=false` 才禁用。若产品要求未知态也禁用，需要额外裁决。
3. **Enter 修饰键**：统一 keydown 仅豁免 Shift，因此 Ctrl/Meta/Alt+Enter 在空闲态也会发送（与 demo 的 `if (e.key !== 'Enter' || e.shiftKey) return` 一致）；如只允许裸 Enter，需要再加修饰键闸。
4. **IME 守卫为启发式**：`isComposing` / `keyCode 229` / compositionend 30ms 覆盖 jsdom 与常见浏览器；真实中文输入法仍需实机验收，本次未做浏览器验收。
5. **硬禁用范围**：未纳入 `busyConflict`、本地 `deciding` 等信号（任务明确列为真实不可用/挂起/pendingToolCall）；待后续裁决是否扩展。
6. **pendingToolCall 禁用面**：历史恢复出待处理工具调用时输入框会硬禁用，符合阶段一口径，但会改变旧会话中“可继续输入”的体验，需在阶段二/验收时确认。
7. **发送后焦点回收、失败保稿**未实现，属阶段二；`send()` 行为除注释外未改。

---

## 6. Fix round 1（2026-10-19）

裁决范围逐项落实，仍只改 `AiPanel.vue` 与同一 spec；未提交、未起停服务、未触碰其他文件。重新自证 `profile.bind`：`agent-1109` / `DeepSeek/deepseek-flash` / `coder` / `thinkingEffort: high`。

### 6.1 组合态 blur 复位（Important）

- **测试**：新增 `composerstart → blur → 重新聚焦 → Enter 应发送`（spec:3407）。另新增 `compositionstart` 后不带 `event.isComposing` / `keyCode` 的 Enter，断言本地组合态分支确实拦截（spec:3391）。
- **实现**：textarea 增加 `@blur.capture="onComposerBlur"`（`AiPanel.vue:740`）；`onComposerBlur` 复位 `composerComposing = false` 与 `composerCompositionEndedAt = null`（`AiPanel.vue:1330-1334`），采用 demo 的 capture blur 思路。
- **RED**：blur 用例失败（`defaultPrevented=false`、未发送），日志 `phase1-fix1-red.log`。
- **GREEN**：153/153 通过。说明：测试中 mock `Date.now()` 会被 Vue 的事件 `_vts` 守卫丢弃（`invoker.attached` 是挂载时真实时钟），因此该用例使用真实时钟，仅做同步的 composition/blur/Enter 序列。

### 6.2 health 未知 / 错误一致性

- **测试**：
  - `health=null` 且无 `healthError`：textarea 不硬禁用，但发送钮 `disabled`、Enter `preventDefault` 且不发送（spec:3424）。
  - `healthError` 非空：textarea 硬禁用、发送钮禁用（spec:3442）。
  - health 明确 `available=false`：发送钮/textarea 均禁用；移除 DOM `disabled` 后直接派发 Enter，keydown 分支仍 `preventDefault` 且不发送（spec:3450）。
- **实现**：`composerHardDisabled` 保持“只有 health 明确不可用 / healthError / suspended / pendingToolCall 才硬禁用”，未把 `health=null` 改为硬禁用；Enter 分支末尾改为 `composerHardDisabled.value || !canSubmit.value` 与发送钮同闸（`AiPanel.vue:1355`）。`canSubmit` 仍为 `available + 非空`，不依赖 loading。
- **RED**：health 未知与明确不可用的 Enter 用例均失败（键盘绕过了禁用的发送闸），日志同 `phase1-fix1-red.log`。
- **GREEN**：通过。

### 6.3 硬禁用发送（按钮不可点）

- **测试**：草稿非空时先把发送钮置为可用，再分别置 `suspended` / `pendingToolCall`，断言发送钮 `disabled`，且 `trigger('click')` 不调用 `store.send`（spec:3469）。
- **实现**：发送钮 `:disabled="composerHardDisabled || !canSubmit"`（`AiPanel.vue:761`），与 Enter 闸同源。
- **RED**：`suspended` 后发送钮仍无 `disabled`，用例失败。
- **GREEN**：通过。

### 6.4 tip 外圈 aria-hidden

- **测试**：发送/停止两个 `.tip` 外圈均断言 `aria-hidden="true"`（结构锁内）。
- **实现**：两个 tip 外圈补 `aria-hidden="true"`（`AiPanel.vue:753`、`763`），按钮 `aria-label` 保留。
- **RED**：结构锁在工具位断言处先失败；补齐后 aria-hidden 断言同样覆盖。
- **GREEN**：通过。

### 6.5 左工具位对齐 demo

- **测试**：底栏首个子元素为 `SPAN`，文本 `工具位（预留 · 留空）`，含 `border-dashed` / `border-[#cddcff]` / `rounded-[6px]`；其后保留 `flex-1` 撑开位。
- **实现**：把空 `span.flex-1` 换成 demo 同款虚线占位 `span` + `span.flex-1`（`AiPanel.vue:743-744`），不改布局与交互。
- **RED**：结构锁因首子元素文本为空失败。
- **GREEN**：通过。

### 6.6 未扩大范围

未处理阶段二的 `send()` outcome、失败保稿、发送后焦点回收；未改 `stores/ai.ts`、后端、队列、SSE、Tailwind 配置、demo/docs。

### 6.7 Fix round 1 验证

| 项目 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | **5 failed / 148 passed（153）** | `phase1-fix1-red.log` |
| GREEN targeted | 同上 | **153 passed（153）** | `phase1-fix1-green-targeted.log` |
| 全量 | `yarn test` | **23 files / 424 tests 全通过** | `phase1-fix1-full.log` |
| 类型检查 | `yarn typecheck` | 通过（exit 0） | `phase1-fix1-typecheck.log` |

- `git diff --check` exit 0（仅 LF→CRLF 提示）；`git status` 仍只有两个允许文件被修改，`?? data/` 未触碰。
- 新增行扫描：emoji 0、内联 style / `<style>` 0、queue/排队 0；无调试日志残留。
- targeted 日志无 `Vue warn`/stderr；全量仅既有 EP 桩/指令解析警告（16 次 `loading` directive 等），无失败。

### 6.8 Fix round 1 关注点

- 组合态复位用元素级 `@blur.capture`；真实浏览器 click-out 会触发。若未来把 textarea 包进 Shadow DOM/自定义焦点管理，需要重新确认 blur 可达性。
- Enter 与发送钮现在共用 `composerHardDisabled || !canSubmit` 闸；后续若调整 `canSubmit` 语义，键盘与按钮会同步变化（预期同源）。
- health 未知态仍保持“可编辑但不发送”：输入不丢，但发送钮禁用，等待探测结果。
- `Date.now()` mock 与 Vue 事件 `_vts` 守卫冲突，后续测试 IME 时间窗时不要同时 mock 时钟并依赖 Vue 事件派发；可直接调用处理逻辑或使用真实时钟。
