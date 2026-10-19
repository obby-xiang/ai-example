> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# 2A② 生成式表单只读回填缺陷 · 施工报告

日期：2026-10-19
仓库：`<REPO_ROOT>`
BASE：`527441936715eaa6bc395f1a1ced9cc0314b2604`（开工核验 HEAD = BASE，工作树仅 `?? data/`）
范围：**数据/显示链路修复**。只读视觉（用户明确「暂缓，不一定做」）不在本批。

## 1. 自证

本执行子智能体 wire `profile.bind` 单行实测：

```json
{"type":"profile.bind","agentId":"agent-1129","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high","systemPrompt":"…"}
```

（完整单行见 `<SESSIONS_ROOT>/<SESSION_ID>/agents/agent-1129/wire.jsonl` 首条；同会话 `agent-1130` 为 explore 只读智能体，非本执行体。）

约束遵守：未改视觉样式/只读标记；未改后端/API 协议；未 commit/push；未起停服务；未触碰 `data/`（`git status` 仍仅 `?? data/`）。

## 2. 根因与修复口径

用户可见缺陷链（与派发一致）：

1. `frontend_tool_result` 帧先到 ⇒ 段 `succeeded` + `requiresAction=false` ⇒ 只读卡立即挂载（`v-show` 折叠时也挂载）。
2. 此时 `POST /frontend-tool-result` 应答未回，`part.submittedValues` 仍为空 ⇒ `FormRenderer` 以 `initialValues=null` 在 setup 里初始化 model 为 schema 默认值。
3. 应答后 `writeSubmittedValues` 写回段值，但 `FormRenderer` **不监听 `initialValues`** ⇒ model 永久停在默认值（刷新后才由 history `resultText` 重灌）。
4. `writeSubmittedValues` 只写**第一条**持该 `toolCallId` 的消息；只读卡按段逐条渲染 ⇒ 其余宿主卡面空。
5. 结局帧本身携带提交值原文（`ConfirmGate#submitFrontendResult` 落 `resultText` → `SseChatEmitter#frontendToolResult` 原样入帧），但落地时被丢弃。

## 3. 改动清单（3 个生产文件 + 2 个既有测试文件）

### 3.1 `frontend/src/components/AiPanel/FormRenderer.vue`（:305-317）

- 新增 `rehydrateReadonlyModel()` + `watch(() => [props.readonly, props.initialValues], …, { deep: true, immediate: true })`。
- 仅当 `readonly === true` 时重灌：`model = initialModel()`、清空 `localProblems`。
- 覆盖两种顺序：只读先到（值后到）与值先到（只读后到）；编辑态忽略 `initialValues` 变化，不覆盖本地输入。
- `initialValues` 变 `null`（取消/超时收尾，F9 未提交不记录）⇒ 回落 schema 默认值。
- 模板与样式类**零改动**。

### 3.2 `frontend/src/utils/parts.ts`（:317 helper / :483 帧写点）

- 新增 `submittedValuesFromResult(name, result)`：仅 `generative_form` + 合法 JSON **对象**；坏 JSON / 数组 / `null` / 标量返回 `null`。
- `frontend_tool_result` 分支：解析出的 `status === 'succeeded'` 且段上**尚无** `submittedValues` 时，同帧写入该值（POST 先落或他端同值 first-wins）。
- 非 GF / failed / rejected / cancelled / expired / 坏 JSON 一律不写，不伪造。
- `submittedValuesFromResult` 置于 `applyFrameToParts` 文档块之前，原 JSDoc 仍归属 `applyFrameToParts`。

### 3.3 `frontend/src/stores/ai.ts`（:2220-2248）

- `writeSubmittedValues` 由「`find` 首条消息 + `find` 首段」改为遍历**全部消息的全部匹配段**逐段 `splice` 更新；只匹配同一 `toolCallId`，不碰其他工具段。
- 每个被改消息调用 `deriveProjections`（旧实现不派生，`pendingCall` 可能残留）。
- `decidedAt` 取同一时刻；已有 `decidedAt` 不覆盖（保持「第一次决策时刻」口径）。
- 仍保留 `requiresAction: false`。

### 3.4 测试文件（未新增文件）

- `frontend/tests/ai-panel-parts-render.spec.ts`（+4 it）：只读先到/值后到六型重灌、编辑态不覆盖 + 只读后到、取消收尾回落默认、只读卡已挂载→store 写回→展开显示真值的端到端。
- `frontend/tests/r166b-fixes.spec.ts`（+6 it）：帧同帧写值、已有值 first-wins、非 GF 不写、非成功终态不写、坏/非对象 JSON 不伪造、多宿主不漏写不错写。

## 4. TDD 证据（同目录日志）

| 日志 | 阶段 | 结果 |
|---|---|---|
| `red.log` | 改生产前，目标两 spec | **5 failed / 190 passed**；失败均为新增用例：值后到仍默认（只读先到、值先到、取消回落）、帧未写 `submittedValues`、多宿主第二宿主空 |
| `red-e2e.log` | 临时回退 3 个生产文件后单跑端到端用例 | **1 failed**：`expected 'HB' to be 'HD'`（卡面停在 schema 默认枚举）；随后按备份逐字节恢复（sha256 三对相等） |
| `green-targeted.log` | 修复后目标两 spec | **196 passed / 2 files** |
| `green-full.log` | 全量 `yarn test` | **454 passed / 23 files**，exit 0 |
| `green-typecheck.log` | `yarn typecheck` | exit 0（vue-tsc 无输出） |
| `green-build.log` | `yarn build` | `✓ built`，exit 0 |

RED 关键断言（摘自 `red.log`）：
- 只读六型重灌：`expected { select: 'fast', text: '' … } to deeply equal { select: 'safe', text: '季度导出' … }`（model 未重算）。
- 只读后到：`expected '本地草稿' to be '服务端值'`。
- 取消收尾：`expected '已提交' to be ''`。
- 帧同帧写值：`submittedValues` 缺失（对象 diff 仅少该字段）。
- 多宿主：第二条宿主段 `submittedValues` 缺失。
- `red-e2e.log`：`expected 'HB' to be 'HD'`（store 已写、UI 未重灌）。

## 5. 同类问题排查清单

| # | 检查项 | 结论 | 依据 / 处理 |
|---|---|---|---|
| 1 | 只读 `initialValues` 后到不重灌 | **已修** | `FormRenderer.vue:305` watcher；RED→GREEN 四用例 |
| 2 | 结局帧携带的提交值被丢弃 | **已修** | `parts.ts:317/483`；succeeded + 合法 JSON 对象同帧写入 |
| 3 | 同 `toolCallId` 多宿主只写首条 | **已修** | `ai.ts:2220` 全匹配段写回；回归锁断言两宿主均有值、无关段不变 |
| 4 | 卡片 key | **不受影响** | block id = `${messageId}:${toolCallId}`（`blocks.ts:44-48`）；同消息同 `toolCallId` 段由 `upsertToolPart` 保证唯一；字段 key 由 `parseFormSchema` 去重校验（`form-schema.ts:119-127`） |
| 5 | 字段 shape | **不受影响** | `FormRenderer.initialModel` 按六型逐字段形状守卫，非法值回落默认；后端 `GenerativeFormGuard.inspectResult` 在落 `FRONTEND_RESULT` 前已做类型复核，帧内 JSON 形状可信 |
| 6 | history JSON | **不受影响** | `stores/ai.ts:1177-1185` 仅 FRONTEND、`JSON.parse` catch ⇒ `undefined`；既有 M1 用例锁定坏 JSON 不伪造 |
| 7 | cancelled / expired 语义 | **已修覆盖** | GF 非 succeeded ⇒ `readOnlyStateOf='cancelled'` ⇒ `readOnlyFormValues=null`；watcher 回落默认（F9 不保留半成品），新增用例锁定 |
| 8 | `markFormDecided` 多宿主 | **刻意除外** | `decidedAt` 为数据层记录、卡面不渲染；旧「首宿主 first-wins」不影响显示，改动收益低 |
| 9 | `applyPendingTerminal` / `applyFrontendToolTerminal` 的 `findIndex` | **不受影响** | 单消息内同 `toolCallId` 段在既有写口下唯一；两函数收敛 status，不承载提交值 |
| 10 | 2A 实时超时链路 | **刻意除外** | 派发明确不并入本批 |
| 11 | failed 真实 UI | **刻意除外** | 同上；本批未改 failed 文案/视觉 |
| 12 | 只读视觉（灰底/锁标记/对比度） | **刻意除外** | 用户直裁「暂缓，不一定做」；模板与类名零改动 |

## 6. 风险与遗留

- **帧内 JSON 被截断**：若 SSE 帧限流截断 `result`，`JSON.parse` 失败 ⇒ 不写值（安全降级，卡面显示默认值），刷新后由 history 回读。未在本批扩展帧字段。
- **多宿主写入覆盖**：POST 成功路径对全部匹配段写入本次提交值（同一 `toolCallId` 的唯一真值）；若历史残留段带不同旧值，以本次 POST 为准。帧路径保持 first-wins，不覆盖已有值。
- **编辑态到只读的瞬时覆盖**：只读一到即以提交值为准（用户本地草稿让位），符合「已提交事实优先」；编辑态期间 `initialValues` 变化完全不触碰 model。
- **未做浏览器验收**：按派发要求不自行做浏览器验收；缺陷复现证据沿用 `<SCRATCH>/_cu-gf-readonly-diagnosis/`。
- **modern-web-guidance**：检索 `Vue form controls async initial values readonly disabled inputs` 与 `controlled form state synchronization reset when async data arrives`，最高相似度 0.40/0.42 且均为校验/遥测类指南，无一条适用于「受控表单异步初值重灌」；未采用，故不引用 id/原句。
- 未提交、未推送；未改 `data/`、后端、API 协议、服务状态。

---

# 7. 边缘项补充批（2A② fix round · 2026-10-19）

## 7.1 自证与边界

本执行子智能体 wire `profile.bind` 单行复核（同一会话、执行体未变）：`{"type":"profile.bind","agentId":"agent-1129","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`。

本批只改 `frontend/src/stores/ai.ts`、`frontend/src/utils/parts.ts` 与既有测试 `frontend/tests/r166b-fixes.spec.ts`。未改 FormRenderer（上批已改且本批无需再动）、视觉、后端、API、demo、docs、服务、`data/`；未提交/推送。工作树中的 `docs/demo/README.md`、`docs/任务看板.md`、`docs/决策看板.md` 为他人看板改动，**未触碰**。

## 7.2 两个边缘项与修复

### 边缘① frame-only 多宿主（`frontend/src/utils/parts.ts:317-377`、`frontend/src/stores/ai.ts:2820-2835`）

- 根因：结局帧只由 `applyFrameToParts` 写入**当前宿主消息**；POST 应答丢失/未回时 `writeSubmittedValues` 不会执行，其他同 `toolCallId` 宿主段仍为空。
- 修复：把「全部匹配段」逻辑收敛为纯函数单一写口 `writeSubmittedValuesToMessage(message, toolCallId, values, { overwrite, decidedAt })`（`parts.ts:348`）：
  - 帧路径 `overwrite:false`（first-wins，已有值不覆盖）；
  - POST 成功路径 `overwrite:true`（默认，保持上批「全量写回」语义不变）。
- `applyFrameToParts` 的 GF 落值改走该写口，并新增**帧级** succeeded 门（`parts.ts:528`）：本地段 first-wins 为 succeeded、但帧显式 REJECTED/FAILED 时也不写。
- `handleFrame` 在 `frontend_tool_result` 落段后读取**帧宿主落定值**（`settled.submittedValues`），以 `overwrite:false` 对全量消息幂等重放：空宿主补齐、已有值保留，避免多宿主出现两套值；坏 JSON / 非 GF / 非 succeeded 时 `settled.submittedValues` 为空，不广播。

### 边缘② 409 DUPLICATE（`frontend/src/stores/ai.ts:2272-2282`）

- 根因：`submitGenerativeForm` 把 `outcome.ok || outcome.duplicate` 一并当作提交成功，duplicate 时仍用**本地值**调用 `writeSubmittedValues`，会覆盖先到帧值；空段则被写入本地值冒充「同值」。
- 修复：最安全最小行为 = **duplicate 分支不写任何提交值**（`if (!outcome.duplicate)`）；仍按既有语义把当前 `activeForm` 置 `submitted`（表单收口）。正常 `ok` 路径行为不变。
- 依据：409 DUPLICATE 表示该 `toolCallId` 已是终态（他端先提交），本地值不是权威提交值；权威值由已到/将到的结局帧或 history 承载。

## 7.3 新增测试（`frontend/tests/r166b-fixes.spec.ts:384-530`）

- 边缘①：
  - 无 POST 应答、两宿主皆空 ⇒ 两段都得到帧内提交值，无关 `toolCallId` 不写。
  - 帧宿主预置旧值 ⇒ 旧值 first-wins 不被覆盖，空宿主以该首个值补齐（多宿主值一致）。
  - 坏 JSON / 数组 JSON / REJECTED / 非 GF ⇒ 多宿主一个都不写。
- 边缘②：
  - 本地值 HB vs 先到值 HD ⇒ 409 后保持 HD，状态 succeeded。
  - 空段 + 409 ⇒ 不写 HB，保持空。
  - 正常 ok 路径 ⇒ 仍写回本地提交值。

## 7.4 TDD 与验证日志（同目录）

| 日志 | 内容 | 结果 |
|---|---|---|
| `edge-red.log` | 先写测试、对**上批已修状态**（`src-backup/` 的 ai.ts/parts.ts）运行 | **4 failed / 21 passed**；4 个失败恰为本批边缘用例：两宿主空宿主未补齐、first-wins 值未补齐、409 覆盖 HD→HB、409 空段被写 HB |
| `edge-red-base.log` | 同上用例对 BASE 回退运行（辅助证据） | 6 failed：除本批 4 项外，另 2 项为上批已修用例（BASE 尚无上批修复） |
| `edge-green-targeted.log` | 修复后 5 个相关 spec | **255 passed / 5 files**，exit 0 |
| `edge-full.log` | 全量 `yarn test` | **460 passed / 23 files**，exit 0 |
| `edge-typecheck.log` | `yarn typecheck` | exit 0（vue-tsc 无输出） |

RED 关键断言：`expected {...} to match object { submittedValues: { region: 'HB' } }`（非帧宿主空）、`expected {...HD} / received {...HB}`（409 覆盖）、`expected { region:'HB' } to be undefined`（409 空段写入）。所有临时回退均按备份恢复并核对 sha256（ai.ts `6b0e3ae4…`、parts.ts `361f0eac…` 两对一致）。

## 7.5 风险与刻意除外

- 帧宿主已有旧值时，空宿主以旧值补齐（同一 `toolCallId` 首个权威值优先），不再采信后到帧值；这是 first-wins 的一致化选择。
- 409 时不写本地值意味着：若后端最终没有任何结局帧且 history 不可用，卡面可能仍无值（极端降级，优于显示错误值）。
- 未改 POST `ok` 路径、未改 `applyFrontendToolTerminal`（409 的状态收敛仍按后端响应体）、未改视觉/后端/API。
- 未做浏览器验收、未提交/推送；他人 docs 看板改动未触碰。
