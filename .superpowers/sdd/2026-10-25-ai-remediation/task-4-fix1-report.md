# Task 4 · Fix1 报告：C1 / C2 + I1–I5 返工（代次与"载入先于清单"的语义）

- 施工副本（唯一改动地）：`<隔离副本>/_verify-20261025-file-fidelity/frontend`（`node_modules` 仍为主仓同一份的 junction，零 install）
- 主线：`<主仓>` —— **零源码改动**（三生产文件与 `frontend/tests/` 的 `git status` 均为空；本次只新增本报告，并**覆盖**了 `task-4.patch`）
- 补丁：**累计补丁**（对当前 main 一次性可应用，不是叠加栈）：`.superpowers/sdd/2026-10-25-ai-remediation/task-4.patch`（同件：`<隔离副本>/_verify-20261025-file-fidelity/task4-file-fidelity.patch`）
- 未提交、未推送、未跑全量、未起服务（无 8081/5202）、未派生子代理、未加依赖

---

## 0. 结论先行

| 复核项 | 处置 | 关键语义变化 |
|---|---|---|
| **C1** 换任务后编辑区被空模板顶替并覆盖 B 的真实文件 | **已修** | 文件清单成为**载入的前置条件**：`loadTask` 先 `await ensureFileList()` 再赋 `selectedDefs`；`loadEditorsNow` 在清单未知时**不构造模板**；模板只在"清单确认无该文件"时才算内容 |
| **C2** 导出结果下载在途时保存 ⇒ 写空工作簿 | **已修** | `loadedGrids` 改为**载入成功后**才置位（"在途"≠"已载入"） |
| **I1** `GET /files` 失败→补读成功却仍是模板 | **已修** | 清单是载入前置 ⇒ 补读成功必然按文件载入；闸①补读后还会 `await loadEditors(true)` 兜底重载 |
| **I2** 导出侧无代次闸、写/建作业实时读 `taskId` | **已修** | `saveEdits` / `startExport` / `persistStepData` / `beginWatchJob` 全部入口捕获 `(taskId, generation)`，逐项复检 |
| **I3** 旧代次在**应用字节之后**才判废（视觉串台 + 误清新标记） | **已修** | 下载返回后、`loadFromXlsx` 前复检；标记改为**按代次记账**（`editorState: Map<code,{generation,source}>`），旧代次记录自然失效，不再 delete 别人的标记 |
| **I4** `createAndBindTask` 绕过复位/代次 | **已修** | 导入新增 `bindTask()`（路由 watch 与新建任务共用）；导出 `createAndBindTask` 改走 `applyTaskBinding()` |
| **I5** `loadTask`/`resumeJobs`/`loadStaging`/`uploadOne` 旧响应落到 B | **已修** | 全部入口捕获代次，`await` 后不一致即丢弃；作业 watcher 的回调也按代次复检 |
| M1 导入模式未随复位 | **已修**（同一复位流程内，1 行） | `resetTaskScopedState` 复位 `importMode='MERGE'` |
| M6 `loadedGrids` 注释语义不符 | **已修** | 注释与代码一致（载入成功后置位） |

> 唯一未"改到 RED"的是 **I4**：在 UI 上，`createAndBindTask` 的按钮只在 `!taskId` 时渲染，而"有任务 A"的同一实例里按钮不出现 ⇒ 复核描述的残留态在 v1 里**不可达**，属潜在不一致。改法照做（统一流程更安全、防未来入口），用例作为**回归护栏**如实标注（v1 亦通过），不冒充 RED。

---

## 1. 本轮改法（语义，不是再加布尔）

编辑区状态从"载入过没有（布尔）"改成**按代次记账的来源**：

```ts
interface EditorState { generation: number; source: 'file' | 'template' }
const editorState = new Map<string, EditorState>()
```

- **可写判据**（`flushEditors` 闸③）：`state.generation === 当前代次` 且
  `source === 'file'` **或**（`source === 'template'` 且清单已落定且该码没有服务端文件）。
  → 模板只有在"确认服务端没有该文件"时才是合法内容（保住**新建空任务的合法导入**）；
  → 清单未知 / 清单晚到 / 换任务后，模板一律不可写。
- **载入前置**：`ensureFileList()`（单飞）是 `loadEditorsNow` 的第一句；清单未知就不载入，
  用户会看到一次明确告警，而不是"看起来有内容、其实是模板"。
- **代次**：只在记录里写代次，旧记录由 `editorReady()` 判废 —— 不需要"旧流程去 delete 新标记"
  （I3 的误清面从结构上消失），也不需要额外的"清理顺序"约定。
- **写/建作业一律用入口捕获的任务号**（`forTask`），逐项复检代次；实时读 `taskId.value` 的写法全部移除。

### 精确改动（累计：3 生产文件 +596 / −101）

| 文件 | 位置（补丁应用后行号） | 改动 |
|---|---|---|
| `frontend/src/utils/excel.ts` | `:72` `numericCellValue`、`:90` `:363` `:513` | 与 v1 相同（数值保真未动） |
| `frontend/src/views/ImportWizardView.vue` | `:353-372` 状态；`:527-585` `loadTask`；`:587-660` `loadUploadedFiles`/`ensureFileList`；`:662-712` `resetTaskScopedState`；`:717-775` `resumeJobs`/`attachJob`；`:836-870` `uploadOne`；`:896-1000` `loadEditors/loadEditorsNow`；`:1040-1120` `flushEditors`；`:1450` `refreshIssues`；`:1505` `loadStaging`；`:1930-1955` `bindTask`/`createAndBindTask`；`:1982` 路由 watch | 见上；另：`persistSelection(forTask)`、`beginWatchJob(kind, jobId, generation)` |
| `frontend/src/views/ExportWizardView.vue` | `:260-280` `loadedGrids`+`taskGeneration`；`:399-500` `loadTask`/`resumeJobs`/`loadExistingResults`；`:508` reset 换代次；`:729-780` `persistStepData`；`:836-960` `startExport`/`beginWatchJob`/`refreshResults`；`:1018-1085` `ensureGridLoaded`/`loadGrid`；`:1294-1360` `saveEdits`；`:1895` `createAndBindTask` | 见上 |

### 测试（累计：3 个 spec，890 行，23 例；探针未变）

| 文件 | 例数 | 覆盖 |
|---|---|---|
| `frontend/tests/task4-import-file-fidelity.spec.ts` | 10 | v1 的 5 条（保留）+ C1 / I1 / I3 / I5 / I4 各 1 |
| `frontend/tests/task4-export-condition-fidelity.spec.ts` | 6 | v1 的 3 条（保留）+ C2 / I2a / I2b |
| `frontend/tests/task4-excel-numeric-fidelity.spec.ts` | 7 | 数值保真（未动） |
| `frontend/tests/probes/*` | — | `el-tab-pane` 挂载前提探针（未动，本轮复跑一致） |

**没有削弱任何既有断言**：v1 的 15 条全部保留；只把 v1 用例 ② 的**前提**行从"清单失败⇒页面构造模板"
改成"清单失败⇒**不**构造模板"（这正是 C1/I1 要修的行为，断言方向更强），数据保护断言原样保留。

---

## 2. RED（新用例跑在**复核时的那版代码**上）

做法：把 v1 补丁（`task-4.patch` 的复核版）应用到 main 原件的干净副本，再跑本轮 3 个 spec
（日志 `logs/fix1-red-vs-review.log`）：

```
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
# → Test Files 2 failed | Tests 8 failed | 15 passed (23)
```

| 用例 | v1 上的失败证据（原文） |
|---|---|
| **C2** 下载在途时保存 | `expect(saveFile).not.toHaveBeenCalled()` 失败，PUT 载荷 = `<EMPTY-WORKBOOK>` |
| **I2a** `toXlsxBlob` 在途换任务 | PUT 载荷 = `DEMO-BYTES` **写向任务 8** |
| **I2b** 落库在途换任务 | `expect(createJob).not.toHaveBeenCalled()` 失败（在 8 上建了作业） |
| **C1** 清单晚到 | `expected '{"template":"empty"}' to be ''`（换任务后编辑区被模板顶替，写回也是模板） |
| 用例②（清单失败） | `expected '{"template":"empty"}' to be undefined`（清单未知却构造了模板） |
| **I1** 补读成功 | `expected 1 to be greater than or equal to 2`（只补读一次且**从不重载**编辑区） |
| **I3** 旧代次画字节 | `expected '' to be '{"template":"empty"}'`（A 的字节迟到后画进 B 的面板并清掉了 B 的状态） |
| **I5** 旧作业列表 | `expected [ [ 31, 0, 200 ] ] to deeply equal []`（A 的作业问题被拉进 B 的面板） |
| **I4**（护栏） | v1 亦通过 —— 见 §0 说明（不可达/潜在不一致） |

## 3. GREEN（本轮实现）

```
./node_modules/.bin/vitest run tests/task4-*.spec.ts
# → Test Files 3 passed | Tests 23 passed (23)          （logs/fix1-green.log）

./node_modules/.bin/vitest run tests/excel-zip.spec.ts tests/export-wizard-step-outcome.spec.ts \
  tests/export-wizard-batch-download.spec.ts tests/import-publish-confirm.spec.ts
# → 35 passed (35)                                     （logs/fix1-adjacent.log）

./node_modules/.bin/vitest run tests/import-watch-recycle.spec.ts tests/ai-start-job-sync.spec.ts \
  tests/a10-copy-closure.spec.ts tests/ai-region-cond-errors.spec.ts \
  tests/ai-region-batch-b.spec.ts tests/ai-snapshot-dedupe-epoch.spec.ts
# → 51 passed (51)                                     （logs/fix1-adjacent2.log）

./node_modules/.bin/vue-tsc --noEmit                   # 无输出，exit 0
node tests/probes/tabs-eager-mount.probe.mjs           # 三个 pane 首帧全挂载、切换无补挂（与上轮一致）
```

## 4. 逐条实现说明（为什么这样最小）

1. **C1 / I1（同一个根）**：`loadTask` 顺序改为 `getTask → ensureFileList → selectedDefs`。
   `selectedDefs` 的 watch 会触发 `loadEditors(true)`，此时清单必已落定 ⇒ 分支只可能是"下载文件"或
   "确认无文件后建模板"。`loadEditorsNow` 自带 `ensureFileList()` 兜底（其它入口也会载入编辑区），
   清单未知直接不载入 + 一次告警。`flushEditors` 闸①补读成功后再 `await loadEditors(true)`，
   覆盖"失败→补读成功"这一半（I1）。
2. **C2**：`loadGrid` 的 `loadedGrids.add` 从"下载前"移到 `loadFromXlsx` 成功之后；并发去重仍由
   `gridLoads` 承担（`ensureGridLoaded` 命中在途即等同一结局），语义与文档一致。
3. **I2**：导出页引入 `taskGeneration`（`resetTaskScopedState` 内 +1）；`persistStepData(generation, forTask)`、
   `startExport`、`saveEdits`、`beginWatchJob`、`loadGrid`、`loadExistingResults`、`resumeJobs` 全部入口捕获并复检。
   `persistStepData` 的 `finally` 也只在同代次时复位 `persisting`（不干扰新任务的在途状态）。
4. **I3**：`editorState` 按代次记账 + 下载返回后/应用前的复检；`setGridRef` 卸载时只摘同代次记录；
   `resetTaskScopedState` 直接 `editorState.clear()` 并丢弃在途的 `editorLoadInFlight` 引用
   （旧 promise 仍在跑，但其 `finally` 有身份守卫，不会误清新任务的载入）。
5. **I4**：导入 `bindTask(id)` 成为唯一改绑入口（`aiStore.onSheetPageLeft → resetTaskScopedState →
   taskId/workspace → loadTask`），`createAndBindTask` 与路由 watch 共用。
6. **I5**：`loadTask`/`resumeJobs`/`attachJob`/`refreshIssues`/`loadStaging`/`uploadOne`/`loadExistingResults`
   /`resumeJobs`(导出) 入口捕获代次，返回后不一致即丢弃；`refreshTaskSnapshot`/导出 `onFinal` 的任务快照
   也改为按入口捕获的任务号刷新。

## 5. 边界与未做

1. **没起服务**：仍未对真实后端跑这三条写回路径（无 8081/5202），证据层级=真组件 + 真 handler 路由链 +
   真 flush/save/persist 代码，HTTP 与表格引擎为替身（`SpreadGrid` 假实现语义="表格里现在是什么字节"）。
2. **未跑全量 vitest / Playwright e2e**；`dist/`、`test-results/` 未动。
3. **M2（硬编码中文文案）未处理**：需要新增 `zh-CN/en-US` 词条，超出本任务文件所有权（仅三个文件），
   已登记；新增的 5 处文案仍是中文，英文界面会混显。
4. **M3（同一失败弹两次提示）未改**：`flushEditors` 的 warning + `goStep` 的 `unsavedEdits`，属体验问题，
   改动会牵动既有回灌文案断言，登记后续。
5. **M4/M5/M7/M8 均按复核建议登记，未在本轮扩散**（`loadUploadedFiles` 仍无法区分"代次已变"与"HTTP 失败"；
   `1.50/1e3/≥1e21` 与后端 `ExcelReader` 接受度未测；`buildXlsx` 打包未打开标签页仍会放空工作簿；
   测试里保存按钮靠文案定位）。
6. **I4 的可用性说明**：其 RED 在 UI 上不可达，本轮按"统一流程"改了代码 + 留回归护栏，不夸大证据。

## 6. 复现

```bash
# 补丁（对当前 main 累计可应用）
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4.patch
#   已在临时 autocrlf=true 仓库验证：--check 通过，应用后与施工副本逐字一致（换行符归一后）

cd frontend
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
./node_modules/.bin/vue-tsc --noEmit
node tests/probes/tabs-eager-mount.probe.mjs
```

## 7. 关注点（交复核）

1. **行为收紧**：清单未知 / 编辑区内容来源不明时第 1 步会被拦住并说明原因（比"静默写空模板"安全）；
   新建空任务的合法路径经用例显式保住（无文件 ⇒ 模板可写）。
2. **导出 `saveEdits`**：只写"载入成功"的标签页；在途/失败项跳过并告警（不再等 `whenLoaded`，避免
   无上限等待）。若用户刚切标签就点保存，会看到"未载入，本次未保存"的提示。
3. **`loadTask` 顺序变化**会让第 1 步的编辑区晚于清单落定才出现（多一次 `GET /files` 的时延，
   但该请求本来就要发；`ensureFileList` 单飞保证不重复）。
4. **导出换任务的 AI 回执**：`loadTask` 在代次已变时返回 `taskOk:false`，`ensureTaskBoundForTool`
   会给出"任务 #N 的绑定没有完成"的文案 —— 该场景只在绑定被更晚的绑定取代时出现，文案不完美但**不谎报**。

DONE
