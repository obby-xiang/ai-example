<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-079 · 其它 · RED / GREEN（45653e92 / agent-1752）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-code/ai-example-main`、`frontend/src/`、`workspace.ts`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m298` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt6` 条有正文的 assistant 消息（共 7 条有正文），末条有正文消息为 `m304`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1752`（`main` = 主会话）；原字符 2,613（链内 1 段、合计 2,613）。
> **链号 / 类别**：`MT-079` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 7 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `9%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m304`（967 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 4 处。

---

## RED / GREEN

### RED（实现前）

新增/加严的 5 条用例在旧实现下失败：

```text
npx vitest run tests/publish-mode.spec.ts tests/import-publish-confirm.spec.ts tests/workspace-run-job-publish-confirm.spec.ts
Test Files  3 failed (3)
Tests       5 failed | 28 passed (33)
```

逐项：

1. **① 防双击**：并发调用两次 `import.startPublish`，旧实现两个都进入 `getTask`/确认框，`confirmMock` 被调用两次。
2. **② workspace 同步**：页面与 `run_job` 解析出 REPLACE 后，`workspace.importMode` 仍是旧的 MERGE。
3. **③ 同一步短路**：`{step: 'UPLOAD'}` 在当前 UPLOAD 步仍调用 `goToStep`（无意义 PUT）。
4. **④ 非字符串 settingsJson**：`parsePublishMode(123)` 在 `trim()` 处抛 `TypeError`。
5. **⑤ 小写 replace**：这是新增回归守卫；当前实现已正确，故改前即绿（按指示补测，未制造假红）。

### GREEN（实现后）

```text
Test Files  3 passed (3)
Tests       33 passed (33)   // publish-mode 5 + import-publish 20 + run_job 8
```

相关回归：

- `ai-panel-parts-render.spec.ts -t 不可撤销`：**7/7**（门卡/i18n 未回退）。
- `confirm-irreversible` 3、`sheet-tools-write` 12、`ai-region-batch-b` 16、`gate-matrix` 5、`tool-i18n-coverage` 52：**88/88**。
- `vue-tsc --noEmit`：**exit 0**。

## 实现要点

- **① 防双击**：`publishPending` 在 `getTask` + 确认框之前置位；`busy='publish'` 让按钮进入 loading。`finally` 复位 `publishPending`，且只在 `busy` 仍是 `'publish'` 时清 `busy`，不覆盖 `startJob` 自身的 busy 管理。
- **② 权威模式回写**：页面在解析成功后直接 `workspace.importMode = resolution.mode`；`run_job` 仅当 `event.taskId === workspace.taskId` 时同步。直接赋字段，不调用 `setImportMode()`，不产生假的“界面动作”。
- **③ 同一步短路**：`index === fromIndex` 在 `goStep` 之前直接返回“步骤未改变”，不再发 `PUT /step`，也不会产生被丢弃的 `syncWarning`。
- **④ 解析加固**：`parsePublishMode` 对 `null/undefined` ⇒ MERGE、空白字符串 ⇒ MERGE、非字符串 ⇒ `{ok:false}`、非法 JSON ⇒ `{ok:false}`；不再 `TypeError`。
- **⑤ 小写测试**：`REPLACE` / `replace` / `RePlAcE` 均断言为 REPLACE，与后端 `equalsIgnoreCase` 对齐。

## 文件

- `<REPO>/frontend/src/utils/publish-mode.ts:33`：非字符串 fail-closed。
- `<REPO>/frontend/src/views/ImportWizardView.vue:351,997`：`publishPending`；入口置 busy/pending、`finally` 复位；`:1029` 同步 `workspace.importMode`；`:1362` 同一步短路。
- `<REPO>/frontend/src/stores/workspace.ts:538`：`run_job` 当前任务同步 `workspace.importMode`。
- `<REPO>/frontend/tests/publish-mode.spec.ts`（新增）：空值/大小写/缺失/坏 JSON/非字符串边界。
- `frontend/tests/import-publish-confirm.spec.ts`：防双击、workspace 同步、同一步零 PUT 用例。
- `frontend/tests/workspace-run-job-publish-confirm.spec.ts`：`run_job` workspace 同步用例。

## 说明

- 保留了全部已验收行为、`AiPanel.vue` 的 4B Continue 发送与 i18n，以及 `confirm_step` 的 WRITE/不加确认门口径；本轮未改后端、docs 或其它文件。
- 未处理独立复审中的 Minor 6（取消=失败的结局类型）和 `docs/03` 文档漂移：按本轮范围只做 1/2/3/4/5。
- 复审登记的 TOCTOU 残留（确认后到后端读 settings 之间模式仍可能被改）仍存在，属已知非阻断风险，本轮未扩到后端帧字段。
- 未 commit / push，未起停服务。
