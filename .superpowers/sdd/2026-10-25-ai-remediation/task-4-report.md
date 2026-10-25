# Task 4 报告：文件、任务切换与导出保真

- 施工副本（唯一改动地）：`<隔离副本>/_verify-20261025-file-fidelity/frontend`（由当前 main 的 `frontend/` 全量复制，`node_modules` 以 **junction** 复用主仓的同一份，未执行任何 install）
- 主线：`<主仓>` —— **零源码改动**（本次涉及的三生产文件与 `frontend/tests/` 在 `git status` 中与开工前逐项一致；只新增了本报告与 `task-4.patch`）
- 补丁：`<隔离副本>/_verify-20261025-file-fidelity/task4-file-fidelity.patch`（同件副本：`.superpowers/sdd/2026-10-25-ai-remediation/task-4.patch`）
- 未提交、未推送、未跑全量、未起后端/前端服务、未使用 8081/5202、未派生子代理

---

## 0. 结论先行

| 审查项（brief Step 1） | 判定 | 处置 |
|---|---|---|
| A→B 换任务把 A 的内容写进 B | **确认是缺陷**（RED 实测：`PUT /tasks/2/...` 载荷 = `CURRENCY:A-BYTES`） | 任务代次 + 换任务页面态复位 + 写回时逐项代次闸 |
| 未加载 grid 却 PUT 空工作簿（导入 `flushEditors`） | **确认是缺陷**（RED 实测：载荷 = `<EMPTY-WORKBOOK>`） | `editorLoaded` 读闸 + 未载入项如实回灌（阻断推进） |
| `GET /files` 失败仍写回（拿空模板覆盖真文件） | **确认是缺陷**（RED 实测：载荷 = `{"template":"empty"}`） | `filesOk` 写回闸（先补读一次，仍失败则不写并阻断） |
| 未加载 grid 却 PUT 空工作簿（导出 `saveEdits`） | **确认是缺陷**（RED 实测：`[[7,'DEMO'],[7,'OTHER']]`，OTHER 未载入） | `loadedGrids` 读闸 |
| 条件落库失败仍启动导出作业（按旧条件/全量导出） | **确认是缺陷**（RED 实测：`setCondition` reject 后仍 `POST /jobs`） | 落库闸 + 结构化失败回灌 |
| `007`、超过 `2^53` 的标识符失真 | **确认是缺陷**（RED 实测：`'007'→'7'`、`'9007199254740993'→'9007199254740992'`，且 xlsx 里已失真） | `numericCellValue`：能原样往返才写数字，否则按文本落格 |
| 「未激活的 `el-tab-pane` 不会挂载 ⇒ 没有 grid ref ⇒ flush 静默跳过」 | **前提不成立**（真实 Chromium 探针：三个 pane 首帧**全部**挂载，切标签不再补挂） | **不改** `if (!grid) continue` 这一分支；该子项收窄为"ref 存在但内容未载入"（已由写回闸③覆盖） |

---

## 1. 精确改动（3 个生产文件，+190 / −23）

| 文件 | 改动 | 为什么最小 |
|---|---|---|
| `frontend/src/utils/excel.ts` | +18/−9：新增 `numericCellValue(text)`（`:72`）；`normalizeCellValue` 的 NUMBER 分支改为 `String(numericCellValue(text))`（`:90`）；`spreadValue`（`:363`）与 `cellValueForXlsx`（`:513`）改用它 | 判据只有一条"解析后再序列化回同一串"，不新增字段类型、不改校验器、不改表头/工作表结构；数字单元格路径对普通数值逐字不变 |
| `frontend/src/views/ImportWizardView.vue` | +129/−5：① `taskGeneration`（`:362`）+ `filesOk`（`:369`）；② `loadUploadedFiles` 返回"清单是否读到"并按代次丢弃旧任务结果（`:542`）；③ 新增 `resetTaskScopedState()`（`:581`），在换任务 watch 里调用（`:1799`）；④ `loadEditorsNow` 增加两处代次复检；⑤ `flushEditors` 三道写回闸（`:879` 清单闸、`:900` 换任务闸、`:910` 未载入闸） | 不改模板结构、不改读写 API 形状、不改 `goStep`/回执语义（`FlushOutcome` 早已有 `ok/reason/savedDefs`，本次只是如实填值）；`put` 改用入口捕获的 `forTask`，不再实时读 `taskId.value` |
| `frontend/src/views/ExportWizardView.vue` | +43/−9：① `startExport` 改为返回结局并**先过落库闸**（`:770`）；② `export.start` handler 按结局回灌（`:1517`）；③ `saveEdits` 加 `loadedGrids` 读闸（`:1186`），部分未载入时如实告警 | 不改 `persistStepData`/`goStep`（它们原本就阻断），只是不再丢弃返回值；不改 `exportResults` 结构与懒加载策略 |

测试（新增 3 个 spec + 1 个探针，共 724 行）：

- `frontend/tests/task4-import-file-fidelity.spec.ts`（5 例）
- `frontend/tests/task4-export-condition-fidelity.spec.ts`（3 例）
- `frontend/tests/task4-excel-numeric-fidelity.spec.ts`（7 例）
- `frontend/tests/probes/tabs-harness.html` + `frontend/tests/probes/tabs-eager-mount.probe.mjs`（真实 Chromium 探针）

**未触碰**：任何 store（`stores/*`）、AiPanel、其他 utils、后端、`SpreadGrid.vue`（只调用它既有的 `loadData` / `toXlsxBlob` / `validateLocal`）、i18n 词条、路由、e2e 配置。

---

## 2. RED（先写测试，只跑聚焦文件）

副本 `frontend/` 下，日志 `logs/red.log`（**先把三生产文件还原成 main 原样**，只留新测试）：

```
./node_modules/.bin/vitest run \
  tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts \
  tests/task4-excel-numeric-fidelity.spec.ts
```

`Test Files 3 failed | Tests 11 failed | 4 passed (15)` —— 4 条通过的是**故意写的"防过度拦截"对照**（正常写回/正常建作业/普通数值仍写数字）。

逐条失败证据（原文摘录）：

| 用例 | 修复前断言结果 |
|---|---|
| NUMBER 前导零 | `expected '7' to be '007'` |
| 超过 2^53 | `expected '9007199254740992' to be '9007199254740993'` |
| 文本格原样读回 | `expected [ '7', '9007199254740992' ] to deeply equal [ '007', '9007199254740993' ]` |
| 长标识符落格 | sharedStrings 里**没有** `9007199254740993`（已写成数字） |
| `007` 落格 | sharedStrings 里**没有** `007`（已写成数字） |
| 导出条件落库失败 | `expect(createJob).not.toHaveBeenCalled()` 失败：**实际 `POST /jobs` 1 次**（taskId 7 / EXPORT） |
| 导出保存编辑 | `expected [[7,'DEMO'],[7,'OTHER']] to deeply equal [[7,'DEMO']]`（未载入的 OTHER 也被 PUT） |
| A→B（B 无该文件） | 切任务后编辑区仍是 `CURRENCY:A-BYTES`（`expected 'CURRENCY:A-BYTES' to be '{"template":"empty"}'`） |
| A→B（B 文件在途） | `expect(saveFile).not.toHaveBeenCalled()` 失败，载荷 = `CURRENCY:A-BYTES` **写向任务 2** |
| `GET /files` 失败 | `expect(saveFile).not.toHaveBeenCalled()` 失败，载荷 = `{"template":"empty"}`（覆盖服务端文件） |
| 未载入 grid | `expect(saveFile).not.toHaveBeenCalled()` 失败，载荷 = `<EMPTY-WORKBOOK>` |

---

## 3. GREEN

```
./node_modules/.bin/vitest run \
  tests/task4-import-file-fidelity.spec.ts tests/task4-export-condition-fidelity.spec.ts \
  tests/task4-excel-numeric-fidelity.spec.ts \
  tests/excel-zip.spec.ts tests/export-wizard-step-outcome.spec.ts \
  tests/export-wizard-batch-download.spec.ts tests/import-publish-confirm.spec.ts
```

`Test Files 7 passed | Tests 50 passed (50)`（日志 `logs/green-final.log`）。另跑相邻 spec 一批（未计入上面 4 个配额）：`import-watch-recycle`、`ai-start-job-sync`、`a10-copy-closure`、`ai-region-cond-errors` → `27 passed`。

类型检查（生产代码 + 新测试）：`./node_modules/.bin/vue-tsc --noEmit` → 无输出（clean）。

---

## 4. 真实浏览器探针：`el-tab-pane` 的挂载前提（前提**不成立**）

- 探针：`frontend/tests/probes/tabs-eager-mount.probe.mjs` + `tabs-harness.html`
- 方式：`file://` 直开本地静态页，用**主仓同一份** `element-plus` / `vue` UMD 产物（经 `node_modules` 相对路径，零拷贝、零构建），渲染与导入向导第 1 步同构的
  `<el-tabs v-model><el-tab-pane v-for :name><子组件/></el-tab-pane></el-tabs>`（3 个 pane，子组件在 `onMounted` 记账），Playwright Chromium 无头只读。
- 结果（`logs/tabs-probe.log`）：

```json
"afterMount": { "events": ["mount:CURRENCY","mount:DEMO","mount:PROJECT"], "childCount": 3,
  "panes": [{"id":"pane-CURRENCY","hidden":"false","display":"block"},
            {"id":"pane-DEMO","hidden":"true","display":"none"},
            {"id":"pane-PROJECT","hidden":"true","display":"none"}] }
"afterSwitch": { "events": ["mount:CURRENCY","mount:DEMO","mount:PROJECT"], "childCount": 3 }
```

- 库侧同证：`node_modules/element-plus/es/components/tabs/src/tab-pane*.mjs`
  `shouldBeRender = !props.lazy || loaded.value || active.value`，而 `lazy: Boolean`（默认 `false`）
  ⇒ 三个 pane **首帧全部渲染并常驻**（未激活的只是 `v-show` 隐藏）。
- 结论与处置：**"未激活标签页没有 grid ref ⇒ flush 静默跳过 ⇒ 数据没写回"这一前提不成立**，该子项按指示**收窄**：`flushEditors` 的 `if (!grid) continue` 保持原样（它只覆盖"尚未渲染/挂载失败"这种真正无内容的情形），真正的风险是"ref 在、内容没载入"（下载失败/载入未完成），由新增写回闸③拦截并**阻断推进**（不再静默写空）。

---

## 5. 真实浏览器边界（没做什么 / 为什么）

1. **没有起服务**：未启动后端（8081）与 vite（5202），因此**没有**对真实后端跑这三条写回路径的 E2E。三条写回路径的证据层级是：真 `ImportWizardView`/`ExportWizardView` 组件 + 真 `registerPageHandler` → `executeFrontendTool` 路由链 + 真 `flushEditors`/`startExport`/`saveEdits` 代码，只有 HTTP 层与表格引擎是替身（SpreadGrid 假实现的 `toXlsxBlob()` 语义是"当前表格里的字节"，与本页真实契约一致）。
2. **表格引擎（SpreadJS）没进 jsdom**：`readRows`/`buildRowsXlsxBlob` 的保真用真 `utils/excel.ts` + 真 JSZip 验证（不依赖引擎）；引擎侧行为（数字格写入后被 Excel 端读成 double）由"按文本落格"从根上绕开，未在真 Excel/POI 里复测。
3. **未跑全量 vitest、未跑 Playwright e2e**（按 brief 要求）；`test-results/`、`dist/` 未改动。
4. 副本里为了不往主仓 `node_modules` 写缓存，`vitest.config.ts` 加了 `cacheDir: './.vite-cache'`（**仅在副本**，不在补丁内）。

## 6. 明确未修 / 留作后续

| 项 | 现状 | 理由 |
|---|---|---|
| `el-tab-pane` "未激活不挂载"子项 | **未改** | 前提已被探针否证（§4） |
| 导出 `persistStepData` 里 `if (!model) continue`（缺模型就跳过落库） | **未改** | 实测不可达：`loadTask` 按任务 items 建模型，`watch(selectedDefs)` 又为每个选中码兜底建模型 ⇒ 迭代时 `selectedDefs` 每项必有模型 |
| 导入 `flushEditors` 把"空模板"写入 B（B 本来没有该文件） | **保留** | 这不是失真：B 没有文件时编辑区就是空模板，写它是"从零开始导入"的既有设计路径；本次只保证**绝不写 A 的字节** |
| `uploadOne` 上传链路 | 未改 | 上传后本身就会 `editorLoaded.delete` + 强载入，已满足读写闸语义 |
| 导出 `saveEdits` 未校验 `GET /files` 成败 | 未改 | `loadExistingResults` 失败时 `exportResults` 为空 ⇒ 循环体不进；且新增读闸后不会再拿未载入表格覆盖 |

## 7. 复现方式（含补丁落地）

```bash
# 1) 隔离副本（已完成，供复核）
#    <隔离副本>/_verify-20261025-file-fidelity/frontend
#    node_modules 为 junction -> 主仓 frontend/node_modules（未 install）

# 2) 补丁落地（在仓库根、core.autocrlf=true 的仓库里）
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4.patch
#    已验证：在临时仓库里 git apply --check 通过，应用后与施工副本逐字一致（换行符归一后）

# 3) 运行
cd frontend
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
./node_modules/.bin/vue-tsc --noEmit
node tests/probes/tabs-eager-mount.probe.mjs   # 需 PLAYWRIGHT 浏览器（沿用主仓已装 chromium）
```

---

## 8. 关注点 / 风险（交给复核）

1. **行为变更面（阻断推进）**：编辑区未载入、或 `GET /files` 读不到时，第 1 步"下一步"现在会**停住并说明原因**（原来是静默写空/写旧数据后推进）。这是有意的，但会改变"网络抖一下也能往下走"的体感 —— 需要产品侧确认可接受。
2. **换任务时的清屏**：`resetTaskScopedState()` 会对已挂载的 grid 调 `grid.loadData(fields, [])` 把视口清空（避免 B 的页面上显示 A 的行）。若 `loadEditors` 随后失败，用户看到的是空表 + 告警，而不是旧数据 —— 同样是"宁可空、不可假"。
3. **数值保真影响面**：`NUMBER` 字段里"看起来是数字但书写形态特殊"的值（`007`、`1.50`、`1e3`、`9007199254740993`）现在按文本落格，Excel 端不再是右对齐数字、不参与默认数值聚合。这是保真的代价，只在非往返安全的值上生效。
4. **导出条件落库闸**：`setCondition` 失败时不再建作业，用户/AI 会看到明确失败。若后端偶发 5xx，用户需重试（比"静默导出全量"安全）。
5. 未覆盖的观测面：真实后端 `PUT /files` 对文本型数值列的接受度（后端 `ExcelReader` 走 DataFormatter，理论上按字符串取），本次未起服务实测。

---

DONE
