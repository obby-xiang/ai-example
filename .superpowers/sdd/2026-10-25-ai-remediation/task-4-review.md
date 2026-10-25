# Task 4 独立复核：文件、任务切换与导出保真

审核对象：`task-4-brief.md` / `task-4-report.md` / `task-4.patch`
审核方式：只读复核 + 在施工副本（`<隔离副本>/_verify-20261025-file-fidelity/frontend`，主线零改动）独立复跑测试/类型检查/浏览器探针；另用两份**临时** spec 复现两条残余缺陷（跑完已删除，未进入补丁、未改主仓）。
行号约定：以下行号均为**补丁应用后**的文件行号（施工副本），主仓未打补丁、行号会偏移。

---

## 0. 结论

| 维度 | 判定 |
|---|---|
| **Spec Compliance** | ❌ **FAIL（部分满足）** —— 需求 3、4 达成；需求 1 只覆盖路由换任务（导出侧与新建任务入口未覆盖）；需求 2 仍可复现"未载入/模板内容覆盖真实文件"（两条 Critical） |
| **Task Quality** | ⚠️ **需返工（Request changes）** —— 测试工艺与 RED 证据扎实、改动面克制、报告边界诚实；但两处"读闸"的语义选错（`loadedGrids` 是"开始载入"而非"已载入"；`editorLoaded` 是"载过内容"而非"载过服务端文件"），且导出侧没有任何代次闸 |
| **合并建议** | **暂不合并**：先修 C1/C2（数据丢失，改动都很小）并补 2–3 个用例；I1/I2 建议同批修；I3–I5 可随后续任务，但需登记 |

**阻断项（Blockers）**

1. **C1**：换到"有同名上传文件"的任务 B 时，编辑器会用客户端**空模板**顶替 B 的文件并置 `editorLoaded`，第 1 步"下一步"就把模板 PUT 覆盖 B 的真实文件（已在副本实测复现）。
2. **C2**：导出结果表格**下载在途**时点「保存编辑到服务端」，`loadedGrids` 已置位、表格尚未载入，`toXlsxBlob()` 产出空工作簿并 PUT，覆盖服务端真实导出结果（已实测复现）。

---

## 1. 独立验证结果（报告数字全部复核通过）

在施工副本执行：

```bash
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
# → Test Files 3 passed | Tests 15 passed (15)          ✅ 与报告一致

./node_modules/.bin/vitest run tests/task4-*.spec.ts tests/excel-zip.spec.ts \
  tests/export-wizard-step-outcome.spec.ts tests/export-wizard-batch-download.spec.ts \
  tests/import-publish-confirm.spec.ts
# → Test Files 7 passed | Tests 50 passed (50)          ✅

./node_modules/.bin/vitest run tests/import-watch-recycle.spec.ts tests/ai-start-job-sync.spec.ts \
  tests/a10-copy-closure.spec.ts tests/ai-region-cond-errors.spec.ts
# → 27 passed (27)                                      ✅

./node_modules/.bin/vue-tsc --noEmit
# → 无输出，exit 0                                      ✅

node tests/probes/tabs-eager-mount.probe.mjs
# → 三个 pane 首帧全部 mount、切换后无补挂（与 logs/tabs-probe.log 一致） ✅
```

补充验证（报告没做、但属"会挂载这两个视图"的 spec）：

```bash
./node_modules/.bin/vitest run tests/ai-region-batch-b.spec.ts tests/ai-snapshot-dedupe-epoch.spec.ts
# → 24 passed (24)   ⇒ 这两个视图相关的 10 个 spec 现已全部通过，未发现回归
```

`git apply --check -p1 task-4.patch`（主仓）→ **通过**；主仓 `frontend/src/views/ImportWizardView.vue`、`ExportWizardView.vue`、`frontend/src/utils/excel.ts`、`frontend/tests` 的 `git status` 均为空 ⇒ 报告"主线零源码改动"属实。

---

## 2. 四条需求逐条判定

| 需求 | 判定 | 依据 |
|---|---|---|
| 换任务不得把 A 写进 B | ⚠️ **部分** | 路由 `?taskId` 换任务路径已有代次闸且实测有效（新增用例 1/2 通过）；但**新建任务入口**绕过复位（I4），**导出侧** `saveEdits`/`startExport` 无代次闸且实时读 `taskId.value`（I2）；换任务后编辑器被模板顶替并覆盖 B 的文件（C1） |
| 未载入 grid / 文件读取失败不得覆盖真实文件 | ❌ **未达成** | C1（换任务后模板覆盖 B 文件）、C2（导出下载在途时空工作簿覆盖）、I1（`GET /files` 失败后补读成功但不重载，模板覆盖） |
| 数值不得静默丢前导零 / >2^53 精度 | ✅ **达成** | `numericCellValue` 往返判据正确；`007`、`1.50`、`1e3`、`9007199254740993` 均按文本；普通数值仍写数字格 |
| 落库失败不得按旧条件启动作业 | ✅ **达成** | `startExport` 先过 `persistStepData` 结局闸，失败不 `POST /jobs`，`export.start` 结构化回灌（新增用例 3 通过）；仅存 I2 的跨任务竞态 |

---

## 3. Critical

### C1 换任务后"模板顶替 B 的真实文件"，推进即覆盖

- 触发链（全部为现实时序，不需要故障）：
  1. 路由换任务 → `resetTaskScopedState()` 把 `uploadedFiles` 清空并 `taskGeneration += 1`（`frontend/src/views/ImportWizardView.vue:581-613`、`:1799`）；
  2. `taskId = B` → `loadTask()`：**先** `selectedDefs.value = B 的 items`（`:512`），**后**才 `await loadUploadedFiles()`（`:530`）；
  3. `selectedDefs` 的 watch（`:1774-1778`）在同一 microtask 内启动 `loadEditors(true)`；`loadEditorsNow` 只等 `nextTick()`（`:780`），此时 `GET /files` 还在网络上，`uploadedFiles` 必然为空 ⇒ 走模板分支（`:801-804`），随后 `editorLoaded.add(def.code)`（`:810`）；
  4. `GET /files` 返回并写入 `uploadedFiles`（`:554-560`）—— **没有任何代码因"磁盘上其实有文件"而重载编辑器**；
  5. 用户点第 1 步「下一步」：`filesOk` 为 true（闸①放行）、`editorLoaded` 有该码（闸③放行）⇒ `toXlsxBlob()` = 空模板 → `PUT /tasks/B/files/CURRENCY` 覆盖 B 的真实上传文件（`:881-889`、`:911`、`:935`）。

- 实测证据（副本内临时 spec，复用 `task4-import-file-fidelity.spec.ts` 的全部桩，仅把 `getFiles` 延后到编辑器载入之后）：

```
REVIEW_PROBE {"afterSwitch":"{\"template\":\"empty\"}",
              "afterFiles":"{\"template\":\"empty\"}",
              "writesToB":["{\"template\":\"empty\"}"]}
```

即：B 明明有文件，页面上是模板，且写回载荷就是模板。**报告 §3 的用例 ① 只覆盖了"B 没有该文件"（模板写回是合理设计），恰好绕开了"B 有文件但清单晚到"这一主路径。**

- 影响：B 的上传文件被 header-only 工作簿静默替换；用户界面也看不到 B 的数据（"说没有，其实有"）。
- 最小修法（任一或组合）：
  - `loadTask` 里把 `await loadUploadedFiles()` 移到 `selectedDefs.value = ...` **之前**；
  - `loadEditorsNow` 在 `filesOk.value === false` 时不要构造模板（等文件清单落定后由 `loadTask` 再触发一次）；
  - `uploadedFiles[code]` 由"无"变"有"时使 `editorLoaded.delete(code)` 并重载。
- 必补用例：就是上面的 probe 场景（B 有文件 + `getFiles` 晚于 `nextTick`）；再补一条"`filesOk` 落定前不得构造模板"。

### C2 导出：下载在途时 `loadedGrids` 已置位，保存编辑即写空工作簿

- `loadGrid()` 在**下载之前**就 `loadedGrids.add(code)`（`frontend/src/views/ExportWizardView.vue:942`），而 `saveEdits()` 的读闸只看 `loadedGrids.has()`（`:1186`）；`SpreadGrid#toXlsxBlob()` 不会等载入落定（`frontend/src/components/SpreadGrid.vue:497-502`，只有 `whenLoaded()` 具备等待语义且未被使用）。
- 现实触发：在第 3 步切到另一个结果标签（`onTabChange` → `void ensureGridLoaded(...)`，`:960-964`，不 await）后立刻点「保存编辑到服务端」（按钮常驻，`:86`）。大文件下载窗口可达数秒。
- 实测证据（副本内临时 spec，令首个结果下载悬挂）：

```
REVIEW_PROBE_EXPORT {"downloaded":{},"written":[[7,"DEMO","<EMPTY-WORKBOOK>"]]}
```

即服务端真实导出结果被一本空工作簿覆盖。
- 影响：与需求 2 正面冲突，且是无提示的数据丢失。
- 最小修法：把 `loadedGrids.add(code)` 移到 `loadFromXlsx` **成功之后**（并发去重本来已由 `gridLoads` 承担，`:917-931`）；或在 `saveEdits` 里额外排除 `gridLoads.has(code)`；更稳的是 `saveEdits` 在 `toXlsxBlob()` 前 `await grid.whenLoaded()`（需给在途失败设上限）。
- 必补用例：上面这条（在途不 PUT）；并把用例 3 的断言从"OTHER 未载入"扩到"DEMO 在途也不 PUT"。

---

## 4. Important

### I1 `GET /files` 失败后"补读成功"不复位编辑器 ⇒ 模板覆盖真实文件

- 闸①（`ImportWizardView.vue:881-889`）在首次 `GET /files` 失败后补读一次；补读成功后 `filesOk=true`、`uploadedFiles` 被填上，但**编辑器仍是失败期间构造的模板**，`editorLoaded` 也被置位（`:810`）⇒ 闸③放行 ⇒ 模板覆盖真实文件。
- 报告用例 ② 只覆盖"两次都失败"（直接阻断），未覆盖"失败→补读成功"这一半。
- 修法：补读成功后，对"现在有文件但内容来自模板"的码使 `editorLoaded.delete` 并 `await loadEditors(true)`，或在重载完成前拒绝写回。建议同时引入"来源标记"（`loadedFrom: 'file' | 'template'`），让闸③判断"是否载过服务端文件"。

### I2 导出侧没有任务代次闸，且写/建作业实时读 `taskId.value`

- `saveEdits`：`await grid.toXlsxBlob()` 之后直接用 `taskId.value` 发 PUT（`:1201-1202`）；换任务发生在序列化期间时，A 的表格字节写到 B。`resetTaskScopedState` 清 `loadedGrids` 只能保护"尚未进入循环"的条目，保护不了在途那一条。
- `startExport`：`persistStepData()` 内部跨多个 await 实时读 `taskId.value`（`:686-716`），`createJob(taskId.value, 'EXPORT')` 也是（`:789`）。换任务发生在落库中途 ⇒ A 的选中集/条件可能落到 B，并在 B 上建导出作业（"旧条件启动作业"的跨任务变体）。
- 修法：导出页也引入代次（`reset/applyTaskBinding` 时 +1），入口捕获 `(taskId, generation)`，每次 PUT / `createJob` 前复检；`persistStepData` 用入口捕获的任务号而不是实时值。补一条"保存编辑途中换任务 ⇒ 不写 B / 不建作业"的用例。

### I3 导入的"旧代次载入"会在应用字节**之后**才被判废

- `loadEditorsNow` 在 `await tasksApi.downloadFile(...)` 与 `grid.loadFromXlsx(...)` 之间**没有**代次复检（`:795-797`），复检在应用完成之后（`:806-809`）。A 的下载晚到会把 A 的字节画进 B 当前的面板（视觉串台），并 `editorLoaded.delete(code)` —— 若此时 B 的新一轮载入已经置位，这个 delete 会误清 B 的合法标记，导致"明明该能保存"被闸③拦下。
- 修法：下载返回后、应用前复检代次（拿不到就丢弃 blob）；`editorLoaded` 改为按代次记账（或只在"同一代次"内 delete）。

### I4 `createAndBindTask` 绕过复位与代次（两个向导都有）

- `ImportWizardView.vue:1764-1766`、`ExportWizardView.vue:1766-1768`：先 `taskId.value = created.id` 再 `router.replace`，路由 watch 因 `id === taskId.value` 直接 return（`ImportWizardView.vue:1793`）⇒ **不复位、不换代次**。
- 导入侧后果（可复现的功能死路）：`uploadedFiles`/`savedDefs`/`filesOk`/`importMode` 仍是 A 的；新任务里勾选同名配置后，`loadEditors` 走"有上传文件"分支去下载**新任务不存在**的文件 → 失败 → 模板也不会载入 → 第 1 步永远被闸③拦住（此前是"静默写空模板后推进"，现在变成卡死）。
- 修法：`createAndBindTask` 在赋值前调用 `resetTaskScopedState()`（导入还要用复位后的 `importMode` 默认值），并纳入代次。

### I5 其余任务级异步完成没有代次闸

`loadTask`（`:502-535`）、`resumeJobs`（`:616-635`，`attachJob` 在 `:637-653` 里可能**在 reset 之后**注册新 watcher）、`loadStaging`（`:1334-1360`）、`uploadOne` 的后续（`:706-726`）都没有捕获代次：

- A 的 `GET /tasks/A` 晚到会覆盖 B 的 `task`/`stepIndex`/`selectedDefs`（页面显示 A 的标题、步骤、勾选）；
- A 的作业列表晚到会把 A 的作业挂到 B 的作业面板并启动 watcher，reset 的 dispose 拦不住"reset 之后才注册"的；
- A 的 `getDiff` 晚到把 A 的 staging 行渲染到 B。

不写文件（写回仍有代次/任务号复核），但违反"换任务隔离"，且与报告"任务代次"的表述不符。修法：这些函数入口捕获 `taskGeneration`，返回时不一致即丢弃（与 `loadUploadedFiles` 同型）。

---

## 5. Minor

- **M1** `resetTaskScopedState` 未复位 `importMode`（`:581-613`），而 `loadTask` 仅在 `readImportMode()` 非空时才赋值（`:514-516`）⇒ B 的 `settingsJson` 为空时继承 A 的模式（界面与 AI 上下文都受影响）。
- **M2** 新增用户可见文案是硬编码中文、未走 `t()` 与 `zh-CN/en-US` 词条：`ImportWizardView.vue:884,903,941-943`、`ExportWizardView.vue:783-785,1205`；英文界面会混显中文。
- **M3** 同一失败弹两次提示：`flushEditors` 自己 `ElMessage.warning`（`:943`）+ `goStep` 的 `unsavedEdits`（`:1006`）。
- **M4** 闸①失败文案在 await 之后读 `taskId.value`（`:884`），换任务时可能点名新任务；`loadUploadedFiles` 把"代次已变"与"HTTP 失败"都返回 false（安全，但调用方无法区分原因）。
- **M5** 数值语义细节：`1.50`、`1e3` 未进测试（行为是"按文本"、与判据一致）；≥1e21 的数字单元格会归一为 `'1e+21'` 并回写成科学计数法数字格（未测）。后端 `ExcelReader` 对 NUMBER 列里"文本型数字"的接受度仍未实测（报告 §8.5 已如实登记）。
- **M6** 报告/注释把 `loadedGrids` 说成"内容确实载入过"（`ExportWizardView.vue:1172-1174`），实际是"开始载入"——正是 C2 的根因，措辞需同步修正。
- **M7**（既有问题，非本补丁引入）`buildXlsx`（`ExportWizardView.vue:983-993`）与「全部打包 / AI 批量下载」（`:1084-1102`）都不先 `ensureGridLoaded`，未打开过的标签页会往 zip 里放空工作簿。未因本补丁恶化，但与本任务同源，建议登记后续。
- **M8** 测试脆弱点：保存按钮靠 `findAll('el-button')` + 文案匹配定位（`tests/task4-export-condition-fidelity.spec.ts:208-213`，`element-plus` 被整体 mock 成裸自定义元素）；A→B 用例只等一次 `flushPromises()`，若实现再加一个 await 容易变成偶发失败。

---

## 6. 重点问题的专项回答

**taskGeneration 与异步返回顺序**
- 路由换任务路径的代次闸是有效的：清单（`:551`）与载入（`:783`、`:806`）都会丢弃旧代次结果，写回有"入口捕获 `forTask` + 逐项复检"（`:901`、`:930`、`:935`），实测 A→B 不再写 A 的字节。
- 但代次覆盖面不全：`loadTask`/`resumeJobs`/`loadStaging`/`uploadOne` 未接入（I5）；`createAndBindTask` 根本不换代次（I4）；导出页完全没有代次（I2）。所以"任务代次"目前只能算**部分**落地。
- 真正的问题不是"旧结果被判废太晚"，而是**新任务的载入在文件清单之前就开始**（C1）。

**watcher / grid 清理**
- 作业 watcher：`jobWatchDisposes` 的 dispose 循环正确（`:597-600`），但只回收"reset 之前已登记"的；`attachJob` 在 reset 之后仍可注册旧任务 watcher（I5）。
- 视口清理：`grid.loadData(fieldsOf(code), [])` 语义正确（`SpreadGrid#loadData` 会把挂起载入一并落定），但**清理时机**解决不了 C1：清理之后新载入立刻用模板填回。
- 未清：`importMode`、`busy`/`stagingLoading` 等瞬时态（M1，影响小）。

**fail-closed 与"合法的新任务导入"**
- 设计取舍是自洽的：B 没有文件时写空模板仍被允许（用例 ① 明确断言 `['{"template":"empty"}']`），只有"没载入/清单未知"才阻断。
- 代价是两条真实体验回归：`GET /files` 抖动时第 1 步被卡住（报告 §8.1 已登记，可接受）；新建任务路径因 `uploadedFiles` 残留而**永久**卡住（I4，不可接受，需要修）。

**loadedGrids / saveEdits 覆盖**
- 用例只覆盖"从未打开过的标签"（`loadedGrids` 缺失）。完全没覆盖：下载在途（C2）、全部结果都未载入（应零 PUT）、`GET /files` 失败、换任务途中保存（I2）、部分保存后 `gridDirty` 是否保持 true。C2 是这三条里唯一会造成数据丢失的。

**numericCellValue 的往返语义**

| 输入 | `Number` | `String(Number)` | 结果 | 评价 |
|---|---|---|---|---|
| `'007'` | 7 | `'7'` | 文本 `'007'` | ✅ 正确 |
| `'1.50'` | 1.5 | `'1.5'` | 文本 `'1.50'` | ✅ 保真；代价是失去数值单元格（报告已登记） |
| `'1e3'` | 1000 | `'1000'` | 文本 `'1e3'` | ✅ 保真 |
| `'9007199254740993'` | 9007199254740992 | `'9007199254740992'` | 文本，全精度保留 | ✅ 正确 |
| `'100'` / `'1.5'` / `' 1.5 '` | 相同 | 相同 | 数字 | ✅ 普通值不受影响 |
| 数字单元格 `100` | — | — | `'100'` | ✅ 读写两端一致 |
| `'-0'`、`'12.'`、`'NaN'` | 非往返 / 非有限 | — | 文本 | ✅ 一致（保守） |

判据本身没有发现错误；`normalizeCellValue`（读回）与 `spreadValue`/`cellValueForXlsx`（写格）共用同一函数，语义一致。唯二缺口是测试没锁 `1.50`/`1e3`（M5）和后端接受度未验（已如实登记）。

**测试是否"自证 mock"**
- 不是。导入/导出用例都挂**真** `ImportWizardView`/`ExportWizardView`、经**真** `executeFrontendTool`/`registerPageHandler` 路由链，断言服务端调用面（`saveFile`/`createJob` 的载荷与次数）；mock 只落在 API 边界与 `SpreadGrid`（其假实现明确模拟"未载入=空工作簿"这一真实契约），数值用例用真 `utils/excel.ts` + 真 JSZip 解包检查 `sharedStrings`/`sheet1.xml`。RED 日志（`logs/red.log`：11 failed / 4 passed，含 4 条"防过度拦截"对照）与报告逐条吻合。
- 局限：`element-plus` 被 mock 成只剩 `ElMessage`，所以 jsdom 里**没有**真实的 `el-tabs/el-tab-pane` 组件，面板挂载/ref 行为只能靠探针 + 库源码佐证（见下）。

**浏览器证据是否被恰当限定**
- 是。探针只断言"未激活 pane 是否挂载"这一前提，`file://` + node_modules 内同版本 UMD，无服务、无后端，报告 §5 明确列出"没有真实后端 E2E、没有引擎进 jsdom、没有全量"。我复跑得到与日志一致的结果。
- 结论成立性我另做了两点交叉验证：`ImportWizardView.vue:69-93` 与 `ExportWizardView.vue:112-129` 的 `el-tab-pane` **都没有** `lazy`，且 element-plus 的 `shouldBeRender = !props.lazy || loaded || active` ⇒ "未激活不挂载"确属伪前提。因此"不改 `if (!grid) continue`"的收窄決定是合理的。
- 仍需注意：探针是**同构静态页**，不经过真实视图的 `defs/editorDefs` 时序，所以它证明的是"pane 会挂"，不能证明"pane 里的 grid 已载入"——后者恰恰是 C1 的盲区。

**多文件下载是否回归**
- 未回归：本补丁没改下载/打包路径，`export-wizard-batch-download.spec.ts` 6/6 通过，`startExport` 的返回值改造不影响 `download_export_files`。
- 既有的"未打开标签页打包出空工作簿"（M7）与本补丁无关，但建议后续一并处理（与本任务同源）。

---

## 7. 报告准确性核对

| 报告陈述 | 复核 |
|---|---|
| 15/15 新增 | ✅ 复跑一致 |
| 50/50（4 相邻 spec） | ✅ 复跑一致 |
| 27/27（另 4 个相邻） | ✅ 复跑一致 |
| typecheck clean | ✅ `vue-tsc --noEmit` exit 0、无输出 |
| 真实 Chromium 探针 | ✅ 可复现，输出与日志一致 |
| 主仓零源码改动、未跑全量 | ✅ `git status` 为空（三生产文件与 `frontend/tests`）；报告/补丁确为新增 |
| `git apply --check` 通过 | ✅ 主仓实测通过 |
| "A→B 已收口" | ⚠️ 仅路由换任务；C1（模板覆盖 B 文件）、I2（导出侧）、I4（新建任务）仍是 A→B 家族 |
| "未载入 grid 不覆盖" | ❌ C2（导出在途）、C1/I1（模板顶替）反例已实测 |
| "`loadedGrids` = 内容确实载入过" | ❌ 语义不符（M6） |
| 未跑全量、证据边界声明 | ✅ 限定恰当、无夸大；"最多 4 个相邻 spec"略有超出（4+4），但已披露、不构成问题 |

---

## 8. 修复优先序（建议）

1. **C1 + I1**：把"文件清单是否已知"变成载入前置条件（`loadTask` 先 `await loadUploadedFiles()`，`loadEditorsNow` 在 `filesOk=false` 时不构造模板；补读成功后使模板来源的编辑器失效并重载）。补 2 条用例。
2. **C2**：`loadedGrids` 改为"成功载入后"置位（或 `saveEdits` 排除 `gridLoads` 在途项 / `await whenLoaded()`）。补 1 条用例。
3. **I2 + I4**：导出页引入代次；`createAndBindTask` 走统一复位（两个向导）。补 1–2 条用例。
4. **I3 + I5**：代次复检前移到"应用载入结果之前"；`loadTask`/`resumeJobs`/`loadStaging`/`uploadOne` 接入代次。
5. 之后再做 M1–M8。

> 结论一句话：**测试与 RED 做得可信、范围控制得好，但两条最关键的数据保护闸选错了判据，导致"未载入/模板内容覆盖真实文件"这一核心需求仍有两条可实测复现的漏洞；请修复 C1、C2（及建议同批的 I1/I2）后再合并。**
