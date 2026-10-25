# Task 4 · Fix2 复核（scoped re-review）

对象：`task-4-fix2-report.md` + 增量 `task-4-fix2.patch`（作用于 task-4 树）
方式：只读复核 + 施工副本独立复跑；另用 3 份**临时** spec 做独立探针（跑完已删除，未进补丁、未改主仓）。
行号为 fix2 应用后（施工副本）行号。

---

## 0. 判定

| 复核项 | 判定 |
|---|---|
| **N1** loading 标志在唯一 reset 入口复位，且不破坏"仅同代次复位"的 finally / 业务数据 | ✅ **ADDRESSED**（导入 `:698-700`、导出 `:571-576`；只清 loading 标志，`counts` 等业务数据不动；测试断言用户可见的按钮 `loading`） |
| **N2a** `startJob → attachJob → beginWatchJob` 不把 A 的作业/issues/watcher 挂到 B | ✅ **ADDRESSED**（入口捕获 + createJob 返回即复检；独立探针：`issues=[]`、`openTaskEvents=0`，作业建在任务 1） |
| **N2b（导入）** `refreshTaskSnapshot` / `syncStepToBackend` 不把 A 的快照写到 B | ✅ **ADDRESSED**（签名 `(generation, forTask)`、响应写回前复检；`onFinal` 在 `refreshIssues` 后再复检；N2b 用例通过） |
| **N2b（导出）** 同一语义的 `syncStepToBackend` | ❌ **NOT ADDRESSED**（导出同名函数仍是实时 `taskId` + 无复检，`ExportWizardView.vue:716`；独立探针实测 A 的 `CANCELLED` 步骤响应把 B 的页面变成只读、开始导出按钮消失） |
| **detached 回执不"像失败"、不诱发重发** | ❌ **NOT MET**（三个 handler 返回 `{ok:false}` ⇒ `interpretHandlerOutcome` 落 `outcome:'failed'`；导出侧回执还追加"请提示用户核对网络后重试"，直接邀请重发） |
| 早前 **C1/C2/I1–I4** 仍成立 | ✅ 未回退（23 条原用例原样保留全绿；本次改动不触及这些路径） |

**数据面结论：无 Critical，无新增数据丢失路径。** 但上述两条 ❌ 使"N2 已全修"的结论不成立，需小修。

---

## 1. 独立验证（报告数字复核）

```bash
vitest run tests/task4-import-file-fidelity.spec.ts tests/task4-export-condition-fidelity.spec.ts \
  tests/task4-excel-numeric-fidelity.spec.ts
# → 3 passed | 27 passed (27)                        ✅（导入 13 + 导出 7 + 数值 7）
vitest run excel-zip / export-wizard-step-outcome / export-wizard-batch-download / import-publish-confirm
# → 35 passed (35)                                   ✅
vitest run import-watch-recycle / ai-start-job-sync / a10-copy-closure / ai-region-cond-errors \
  / ai-region-batch-b / ai-snapshot-dedupe-epoch
# → 51 passed (51)                                   ✅
vue-tsc --noEmit                                     ✅ 无输出
主仓三生产文件 + frontend/tests 的 git status         ✅ 为空
# 应用顺序（临时仓库：main → task-4.patch → task-4-fix2.patch）
git apply -p1 task-4.patch && git apply --check -p1 task-4-fix2.patch   ✅
```

独立探针（fix2 代码，临时 spec）：

```
# N2a：createJob 悬挂 → 切到任务 2 → 作业 99 回包
RR2_IMPORT {"ok":false,"outcome":"failed",
  "result":"预检查作业 #99 已建在原任务上，但页面已切换到别的任务，本次未在本页跟踪（作业仍在后端继续…）",
  "issues":[],"events":0}                     # 不 attach、不拉 issues、不登记 watcher ✅

# detached 导出回执
RR2_EXPORT {"ok":false,"outcome":"failed",
  "result":"导出作业 #31 已建在任务 #7 上，但页面已切换到别的任务，本次未在本页跟踪。请提示用户核对网络后重试，或手动在界面上重新设置查询条件"}

# 导出 syncStepToBackend 在途换任务
RR2_EXPORT_SYNC {"goToStepArg":[7],"startButtonVisibleBefore":true,"startButtonVisibleAfter":false}
```

---

## 2. N1 复核

- 复位点位于两个向导本来就有的唯一 reset 入口（`ImportWizardView.vue:698-700`、`ExportWizardView.vue:571-576`），只清 `busy/stagingLoading/publishPending` 与 `persisting/startingExport/savingFiles/countLoading`（`countLoading` 是 loading 映射，`counts` 未动）。
- 生成守卫保留：`startJob` `:1288`、`persistStepData` `:787`、`saveEdits` `:1360`、`loadStaging` `:1603` 仍是"仅同代次复位"；复位点与守卫并存语义正确：换任务时清，旧操作收尾时不会误清新任务。
- 测试用**用户可见面**断言（`loading` 属性），导入/导出各 1 例，26 行；独立复核通过。
- 备注（不阻断）：`flushEditors` / `uploadOne` 的 `busy = null` 与 `startPublish` 的 `publishPending = false` 仍是**无条件** finally（`ImportWizardView.vue:1103`、`:791`、`:1363`），旧操作收尾仍可能清掉新任务的同名标志（既有模式，N1 未恶化）。`publishPending` 被 reset 清掉这一条同时收窄了发布双击护栏（见 §4-③）。

---

## 3. N2 复核

**已达成**：
- `startJob` 入口捕获 `(generation, forTask)`（`:1261-1286`），`createJob(forTask, …)` 返回后先复检，代次变 ⇒ `{job, detached:true}`，**不**写作业位、不拉 issues、不登记 watcher；
- `attachJob`/`beginWatchJob` 收到显式 generation（`:747`、`:752`），内部仍有代次复检；
- `refreshTaskSnapshot(generation, forTask)`（`:1463-1490`）与导入 `syncStepToBackend(generation, forTask)`（`:1206-1230`）响应写回前复检；`onFinal` 在 `refreshIssues` 之后再复检（`:1425`）；
- 三个 `import.start*` handler 不再读页面位（`checkJob.value` 等），改用 `started.job`/`detached`。

**未达成（必须区分）**：
1. **导出 `syncStepToBackend` 未修**（`ExportWizardView.vue:707-725`，写回在 `:716`）：`task.value = await tasksApi.goToStep(taskId.value, step)` 没有任何代次/任务号捕获。探针证实：`goToStep` 发给了任务 7（正确），但 A 的 `CANCELLED` 响应晚到后被写到 B 的 `task.value` ⇒ B 变只读、按钮消失（与导入 N2b 完全同形的失真）。这是本轮"`refreshTaskSnapshot`/`syncStepToBackend` 不得串台"准则在**导出侧**的遗漏；`goStep`（`:660`）也顺带缺"写回途中换任务"复检。
2. **detached 回执仍是失败形**：`interpretHandlerOutcome`（`src/utils/frontend-tools.ts:1374-1396`）把 `{ok:false}` 判为 **确定性 failed**；实测 `outcome:'failed'`。三个导入 handler（`:1821`、`:1840`、startPublish 的 warning 支 `:1358`/`:1867`）都返回 `{ok:false}`，导出 detached 分支（`:872`）在 handler（`:1670`）里更追加"**请提示用户核对网络后重试**" —— 这正是"invite duplicate submission"。测试还把 `execution.ok === false` 写死（import spec N2a），会把错误形状固化。

---

## 4. 需要处理的 Important

### ①（新，直接违反本轮准则）detached 回执形状 = failed，且导出侧邀请重试

- 事实：`ok:false` ⇒ `outcome:'failed'`；导出回执文本含"请提示用户核对网络后重试"。若模型照做，会在**当前任务**上再建一个作业（publish 的 REPLACE 语义会真删行）。
- 建议：detached 是"已在原任务上发生的事实"，用成功形回执表达，例如
  `{ ok: true, result: '预检查作业 #99 已建在任务 #1（页面已切换，本页未跟踪）。作业仍在后端继续，请勿重复发起。' }`；
  或扩展 `interpretHandlerOutcome` 支持专用 outcome（但 `unknown` 仍 `ok:false`，同样像失败）。
  同时**删掉** `export.start` 对 detached 追加的"重试"引导（仅对真正的落库失败保留），并更新 3 处断言。
- 侧证据：本轮 N2a 用例断言的正是 `ok:false` —— 修回执要同步改用例。

### ② 导出 `syncStepToBackend`/`goStep` 缺少代次闸（N2b 的导出孪生）

- 证据见 §3-1 探针（`startButtonVisibleAfter:false`）。修法：照导入的写法，`syncStepToBackend(generation, forTask)` + 响应写回前复检；`goStep` 入口捕获，persist 后/同步后各复检一次；补一条与导入 N2b 对称的用例。

### ③（既有，非本轮引入；被本轮 reset 轻微放大）`startPublish` 确认窗口跨任务

- `startPublish`（`ImportWizardView.vue:1308-1368`）在 `resolvePublishMode`（网络）与 `confirmPublishByMode`（用户确认）两个 await 期间不捕获代次；确认晚到后调用 `startJob('publish')`，而 `startJob` 捕获的是**当前**任务 ⇒ 用户确认的是 A、实际在 B 上建发布作业；`importMode` 也被 A 的权威值改写。
- 本轮 reset 清 `publishPending`（`:700`）后，B 上可再发起一次发布，A 的确认晚到则可能形成第二个作业（REPLACE 有删除语义）。
- 建议：`startPublish` 入口捕获 `(generation, forTask)`，每次 await 后复检（含 `isReadOnly`），不一致即中止并如实说明"未在 B 上发布"；`publishPending` 的令牌也按代次记账。

---

## 5. Minor（登记，不阻断）

- `startJob` 只在 `createJob` 返回后复检一次；若换任务发生在 `await attachJob` 期间，返回值仍是 `detached:false`，handler 会回"作业 #N 已启动"（作业确实在 A，但未说明本页未跟踪）。建议返回前再复检一次。
- `startJob` 的 catch 在 `createJob` 成功、`attachJob/watch` 抛错时返回 `{job:null}` ⇒ handler 回"未启动"（假阴性，可能诱发重发）。既有结构，本轮可顺手改为"作业已建、挂接失败"的如实回执。
- UI 按钮路径（`startCheck`/`startImport` 丢弃结局、模板 `@click="startPublish"` 忽略 outcome）在 detached 时**无任何提示**（只有 AI handler 路径有回执）。
- `goStep` 在 `syncStepToBackend` 期间换任务仍返回 `ok:true`（步骤确实发给了 A，但 B 的页面被 loadTask 重置）——回执未说明归属。
- 报告 §4-3 的"detached 回执不诱发重发"与实现（`ok:false`→failed）自相矛盾，需与代码对齐。

---

## 6. 合并建议

**Request changes（小修后即可合）：**
1. 修 detached 回执形状（三处 handler + 导出 `export.start` 的重试引导一句 + 对应用例断言）——这是本轮准则"不看起来像失败/不诱发重发"的直接未达标；
2. 给导出 `syncStepToBackend`/`goStep` 补代次闸（N2b 导出孪生，已实测可重现）；
3. 建议同批把 `startPublish` 的确认窗口按代次收口（可作紧随其后的 P2，但必须登记）。

N1 与 N2 的作业/问题清单/watcher/snapshot（导入侧）机制均已按语义修好并经独立探针验证；C1/C2/I1–I4 无回退、无新增数据丢失路径。上述两条 ❌ 都是小改动能闭合的**正确性/回执语义**问题，不是数据面阻断。
