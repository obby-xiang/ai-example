# Task 4 · Fix1 复核（scoped re-review）

对象：`task-4-fix1-report.md` + 累计 `task-4.patch`（2231 行）
方式：只读复核 + 在施工副本独立复跑；另用 3 份**临时** spec 复现 C1 / C2 / I5 残留（跑完已删除，未进补丁、未改主仓）。
行号为补丁应用后（施工副本）行号。

---

## 0. 判定总表

| 原发现 | 判定 | 关键证据 |
|---|---|---|
| **C1** 清单晚到 ⇒ 模板顶替并覆盖 B 的真实文件 | ✅ **ADDRESSED** | 独立探针：清单在途时编辑区为空（`""`，不构造模板）⇒ 清单落定后按 B 文件载入 `CURRENCY:B-BYTES` ⇒ 写回即 B 字节；代码 `ImportWizardView.vue:535-585`（`ensureFileList` 在 `selectedDefs` 之前）、`:883-945`（载入前置清单） |
| **C2** 导出下载在途保存 ⇒ 写空工作簿 | ✅ **ADDRESSED** | 独立探针：下载悬挂时点保存 ⇒ `saveFile` 调用 `[]`；`loadedGrids.add` 移到 `loadFromXlsx` 成功之后（`ExportWizardView.vue:1039-1075`，add 在 `:1060`） |
| **I1** 清单失败→补读成功仍是模板 | ✅ **ADDRESSED** | 回归测试断言 `attempts>=2` 且全程无 `json:` 载入、写回文件字节；`loadEditorsNow` 以 `ensureFileList` 为前置（`ImportWizardView.vue:889`），`flushEditors` 补读后 `await loadEditors(true)`（`:1030`） |
| **I2** 导出无代次、实时读 `taskId` | ✅ **ADDRESSED** | `persistStepData(generation, forTask)`（`ExportWizardView.vue:734-782`）、`startExport`（`:836-886`）、`saveEdits`（`:1297-1360`）、`beginWatchJob`（`:888`）全部入口捕获并逐项复检；测试 I2a/I2b 通过 |
| **I3** 旧代次**应用之后**才判废 | ✅ **ADDRESSED（数据面）** | 下载返回后、`loadFromXlsx` 前复检（`ImportWizardView.vue:913-927`）；`editorState` 按代次记账（`:367-372`），旧记录自然失效；测试 I3 通过。极窄残留见 §3 注 |
| **I4** `createAndBindTask` 绕过复位/代次 | ✅ **ADDRESSED** | 导入 `bindTask()` 唯一改绑入口（`:1938-1948`，`createAndBindTask`/路由 watch 共用）；导出 `createAndBindTask` 走 `applyTaskBinding`（`:1901-1910`）；新任务模板可写回归测试通过（报告已如实标注 I4 的 RED 不可达） |
| **I5** `loadTask`/`resumeJobs`/`loadStaging`/`uploadOne` 旧响应落到 B | ⚠️ **PARTIALLY ADDRESSED** | 上述入口与 watcher 回调均已加代次；但 `startJob → attachJob → beginWatchJob`（`:1229-1243`）与 `refreshTaskSnapshot` / `syncStepToBackend`（`:1401`、`:1194`）仍未捕获代次。实测：作业建在 A、切到 B 后 A 的作业被挂到 B 并登记 watcher（见 §3） |
| M1 `importMode` 未复位 | ✅ ADDRESSED | `ImportWizardView.vue:671` 复位 `MERGE` |
| M6 `loadedGrids` 注释语义 | ✅ ADDRESSED | `ExportWizardView.vue:68-74` 与实现一致 |

---

## 1. 独立验证（报告数字全部复核）

```bash
# 3 个任务 spec
Test Files 3 passed | Tests 23 passed (23)          ✅ 与报告一致
# excel-zip / export-step-outcome / export-batch-download / import-publish-confirm
35 passed (35)                                       ✅
# import-watch-recycle / ai-start-job-sync / a10-copy-closure / ai-region-cond-errors
# / ai-region-batch-b / ai-snapshot-dedupe-epoch
51 passed (51)                                       ✅
vue-tsc --noEmit                                     ✅ 无输出
node tests/probes/tabs-eager-mount.probe.mjs         ✅ 3 pane 首帧全挂、切换无补挂
git apply --check -p1 task-4.patch（主仓）           ✅
主仓三生产文件 + frontend/tests 的 git status         ✅ 为空（零源码改动）
logs/fix1-red-vs-review.log                         ✅ 8 failed / 15 passed，与报告逐条吻合
```

补充独立探针（在**修后**代码上跑，非其测试用例）：

```
RR_C1 {"beforeList":"","afterList":"CURRENCY:B-BYTES","writesToB":["CURRENCY:B-BYTES"]}
RR_C2 {"calls":[],"loaded":{}}                     # 在途保存零 PUT
RR_STARTJOB {"createJob":[[1,"PRECHECK"]],"issues":[99],"events":[[2,null]]}
```

---

## 2. 关键实现核对（回答本轮问点）

**① 晚到清单不可能留下"模板覆盖真实文件"** —— 三重保证同时成立：
- 载入前置：`loadTask` 先 `await ensureFileList()` 再赋 `selectedDefs`（`:535-585`），因此 `selectedDefs` watch 触发的 `loadEditors(true)` 不可能在清单未知时跑模板分支；
- 其他入口：`loadEditorsNow` 第一句就是 `ensureFileList()`，失败即告警 + 不载入（`:883-896`）；
- 写回闸：模板内容只有在"清单已落定且 `uploadedFiles[code] === undefined`"时才可写（`:1052-1057`）。
单飞 `ensureFileList`（`:632-648`）+ `filesInFlight = null`（`:663`）+ 旧 `finally` 身份守卫，不会出现"旧清单把新任务标成已读"。

**② 清单成功载入确实重载为 `source=file`** —— `loadUploadedFiles` 对"此前按无文件构造的模板"逐码作废（`:606-616`），且三条调用路径都在作废后紧接按文件载入：`loadTask`（先于编辑区）、`loadEditorsNow`（同一次内续跑）、`flushEditors`（显式 `loadEditors(true)`）。探针 RR_C1 证实。

**③ `loadFromXlsx` 只在成功后置"已载入"** —— 导入：`editorState.set` 在 `await loadFromXlsx` + 代次复检之后（`:913-927`），catch 不置位；导出：`loadedGrids.add` 移出 try 前段，放到 `loadFromXlsx` 成功 + 代次复检之后（`:1060`）。失败/在途均不置位。

**④ 各流代次安全** —— 保存/导出/暂存/上传/任务详情/作业列表/问题清单/watcher 回调均有入口捕获与返回复检；`persistStepData` 每个 await 后复检；`saveEdits`/`startExport` 用入口捕获的 `forTask` 发 PUT/createJob。**例外见 §3。**

**⑤ `editorState` 没有引入新的陈旧状态** —— 记录按 `(generation, source)` 记账、`reset` 直接 `clear()`、`setGridRef(null)` 只摘同代次记录（`:464-472`）；`editorReady` 仅用于"视图是否有内容"，写回判据单独看 `source`，两种语义已分离。未发现"旧流程 delete 新标记"的新变体。
**但本轮的条件式 `finally` 引入了另一类陈旧态（§3 N1）。**

**⑥ 合法的新建空任务导入未被破坏** —— 清单成功且无文件 ⇒ 模板可写；I4 回归用例（新建任务、`GET /files` 返回空、写回模板到任务 9）通过。

**⑦ 测试仍驱动真实组件/处理器** —— 全部挂真 `ImportWizardView`/`ExportWizardView`，经真 `executeFrontendTool`/`registerPageHandler` 链路；mock 只在 API 边界与 `SpreadGrid`（假实现语义="表格里现在是什么字节"）。C1/C2/I1/I2a/I2b/I3/I5 都是确定性时序（deferred），不是"mock 自证"。
覆盖缺口：导出"下载失败后再保存"未单测；N1 的 loading 标志未断言；I3 的"模板应用窗口"未测（见下）。

---

## 3. 新增 / 残留的 Important

### N1（新引入）换任务后 loading 标志不复位 ⇒ 新任务页面按钮永久转圈

- 现象链：`persistStepData` / `startExport` / `saveEdits` / `loadStaging` 在入口置 `persisting` / `startingExport` / `savingFiles` / `stagingLoading = true`，`finally` 改成**仅同代次复位**（`ExportWizardView.vue:742,779`、`:860,883`、`:1304,1352`；`ImportWizardView.vue:1509,1534`）。而 `resetTaskScopedState` 不复位这些 ref（`ExportWizardView.vue:550-575`、`ImportWizardView.vue:660-696`）。
- 结果：在本轮**特意修复**的"保存/落库途中换任务"场景里（I2a/I2b 用例），旧操作 `finally` 因代次不符跳过复位 ⇒ 切到 B 后 `persisting` / `savingFiles` / `startingExport` 仍为 true。三者分别绑定 `:next-loading`（`ExportWizardView.vue:13`）、保存按钮 `:loading`（`:86`）、开始导出按钮 `:loading`（`:74`），Element Plus 的 loading 会禁用按钮 ⇒ **B 上"下一步/保存编辑/开始导出"不可点，直到刷新**。导入侧同类为 `stagingLoading`（第 4 步）。
- 触发条件与已测场景完全重合（"操作在途换任务"），只是测试没断言这些 UI 标志。
- 修法（很小）：在 `resetTaskScopedState` 里把这些任务级标志复位（`persisting/startingExport/savingFiles/gridDirty` 与 `stagingLoading/busy`），并保留现有"仅同代次复位"的 finally（复位点 + 守卫并存最稳）；或改为操作令牌（token）而非布尔。

### N2（I5 残留，Important）作业启动在途换任务 ⇒ A 的作业挂到 B 并登记 watcher

- `startJob`（`ImportWizardView.vue:1229-1243`）不捕获代次：`createJob` 在途时换任务，返回后 `await attachJob(kind, job)` 与 `beginWatchJob(kind, job.id)` 都用**默认参数（当前代次）**，于是 A 的作业被写进 B 的 `checkJob/importJob/publishJob`、拉 A 的 issues、并为 A 的作业在 B 上登记 watcher（reset 已先发生，该 watcher 存活）。
- 实测（独立探针）：`createJob=[[1,"PRECHECK"]]`（正确建在 A），但切到 B 之后 `getIssues(99)` 被调用、`openTaskEvents` 以任务 2 注册 ⇒ 证据表明 A 的作业影响了 B 的面板/跟踪面。
- 同类未加代次：`refreshTaskSnapshot`（`:1401-1411`，`task.value = await getTask(taskId.value)` 之后 `workspace.setTaskId(taskId.value, …)` 用当前 id + 旧 status）与 `syncStepToBackend`（`:1194-1214`）。**报告 §4.6 声称 `refreshTaskSnapshot` 已"按入口捕获任务号刷新"，与代码不符。**
- 修法：`startJob` 捕获 `(generation, forTask)` 并传给 `attachJob`/`beginWatchJob`；`refreshTaskSnapshot(generation, forTask)`、`syncStepToBackend` 同型复检；在 `onFinal` 的 `await refreshIssues(...)` 之后再复检一次再调用它们。
- 影响面：仅面板/状态/回灌文案（不写文件），与"数据保真"无关；属 I5 声称"全修"下的残口。

---

## 4. 备注（非阻断，仅登记）

- I3 极窄残留：若换任务恰好发生在 `grid.loadFromJson/loadFromXlsx` 的 await 内，旧内容可能被画到同 key 的表格上一次，但**不置 `editorState`** ⇒ 写回被闸③拦下（fail-closed，仅视觉瞬态）。
- `loadUploadedFiles` 仍用实时 `taskId.value` 取 `getFiles`（`:598`），建议与其它入口一致改捕获 `forTask`（纯一致性）。
- 导出 `loadTask` 在 `await loadExistingResults()` 之后没有最终代次复检（`ExportWizardView.vue:452`），被更晚绑定取代时仍可能返回 `taskOk:true`；`loadExistingResults` 自身有代次保护，未发现写面影响。
- 首屏现在需等 `GET /files` 落定才载入编辑区（报告已披露）；HTTP 客户端 60s 超时，最坏是 60s 后 fail-closed，可接受。
- M2（硬编码中文）/M3（双提示）/M5/M7/M8 未改，报告已登记，维持上轮意见。

---

## 5. 合并建议

**数据面结论：通过。** C1/C2/I1/I2/I3/I4 均已按"语义"修复，且我用独立探针复现了修复行为；23/23、35/35、51/51、typecheck、探针、`git apply --check`、主仓零改动全部复核一致。

**Verdict：Conditional approve（可合并，但建议同批带上 N1；N2 可作紧随其后的 P2）。**
- N1 是本轮"仅同代次复位"带来的新回归，且触发场景与已修场景重合，修法 3 行（reset 中复位标志），建议合入前修掉；否则用户在"操作在途换任务"后会遇到按钮永久转圈，需要刷新。
- N2 是 I5 的残口（作业/快照跨任务串台，无数据写），可在后续任务修；若不修，请把报告对 I5/`refreshTaskSnapshot` 的"已修"表述改为 PARTIAL，避免夸大。
