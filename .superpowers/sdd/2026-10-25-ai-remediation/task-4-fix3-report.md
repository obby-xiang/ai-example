# Task 4 · Fix3 报告：回执真实性（detached 不得像失败）+ 导出步骤同步代次闸 + 发布确认窗口代次

- 施工副本（唯一改动地）：`<隔离副本>/_verify-20261025-file-fidelity/frontend`（`node_modules` 为主仓同一份的 junction，零 install）
- 主线：`<主仓>` —— **零源码改动**（三生产文件与 `frontend/tests/` 的 `git status` 为空；本轮只新增本报告与 `task-4-fix3.patch`）
- 补丁：**增量补丁**（作用于**当前 Task 4+fix2 树**）：`.superpowers/sdd/2026-10-25-ai-remediation/task-4-fix3.patch`（同件 `<隔离副本>/_verify-20261025-file-fidelity/task4-fix3-incremental.patch`）
- 应用顺序：`task-4.patch` → `task-4-fix2.patch` → `task-4-fix3.patch`；已在临时 `core.autocrlf=true` 仓库按此顺序 `git apply` 实应用，`--check` 通过、应用后与施工副本逐字一致
- 未提交、未推送、未跑全量、未起服务、未派生子代理、未加依赖

---

## 0. 结论

| 复核项 | 处置 | 语义 |
|---|---|---|
| **① detached 回执像失败、诱发重发** | **已修** | "作业已建"一律**成功/信息形**（`{ok:true, result}`）：点明作业号 + 归属任务 + "本页未跟踪" + **请勿重复发起**；导出 `export.start` 的"重试"引导**只保留给真的没建作业的失败**。真正的作业失败（`createJob` 抛错）仍走 `{ok:false}` + 重试引导 |
| **② 导出 `syncStepToBackend`/`goStep` 无代次闸** | **已修**（与导入同形） | `syncStepToBackend(generation, forTask)`：入口捕获、响应写回前复检；`goStep` 入口捕获，persist 后与 sync 后各复检一次（换任务即中止，不把推进算到新任务） |
| **③ `startPublish` 确认窗口跨任务** | **已修** | 入口捕获 `(generation, forTask)`；`resolvePublishMode` 与 `confirmPublishByMode` 两个 await 之后各复检一次；不一致 ⇒ 新结局 `{kind:'switched'}`，**不建任何作业**，回执说明"确认的是任务 #A、当前是任务 #B、本次未发起；如仍需发布请重新确认" |
| 同族真实性路径：`createJob` 成功后挂接抛错 / 挂接期间换任务 | **已修** | `startJob` 记住 `created`：任何后续失败都不退化成 `job:null`/`detached:false`；挂接抛错 ⇒ `attachError`，回执"已建在任务 #N（本页未能跟踪 <原因>），请勿重复发起" |

**未削弱任何既有修复**：C1/C2/I1–I4/N1/N2 的 27 条用例全部保留并通过（本轮把 `N2a` 的断言从"必须 ok:false"**改成**"必须 ok:true 且不含重试"——这是复核 ① 要求的语义修正，不是放宽）；合法新建空任务导入用例仍通过。

---

## 1. 精确改动（增量：2 生产文件 +150 / −46，2 测试文件 +109 / −3）

| 文件 | 位置（fix3 应用后行号） | 改动 |
|---|---|---|
| `frontend/src/views/ImportWizardView.vue` | `:1254-1264` | `JobStartOutcome` 增 `taskId`（作业归属）与 `attachError`（已建但本页未挂上） |
| 同上 | `:1275-1330` `startJob` | 记住 `created`；`attachJob`/`beginWatchJob` 包在内部 try 里（抛错 ⇒ `attachError`）；返回前**再复检代次**（挂接期间换任务也算 detached）；`createJob` 之前的失败仍是 `job:null` |
| 同上 | `:1332-1340`，`:1341-1425` `startPublish` | 入口捕获 `(generation, forTask)`；两个 await 后复检；新增 `{kind:'switched', from, to}`；`started.warning` 只在 `detached`/`attachError` 时给出"已建在任务 #N…请勿重复发起" |
| 同上 | `handler 尾部` `jobStartedNote()` | 新增统一回执助手：`{ok:true, result:'…作业 #N 已建在任务 #M（页面已切换到别的任务/本页未能跟踪…）。作业仍在后端继续，请勿重复发起。'}`；三个 `import.start*` handler 共用 |
| 同上 | `import.startPublish` handler | 处理 `switched`（如实说明未发起 + 需重新确认）与 `warning`（改成功形） |
| `frontend/src/views/ExportWizardView.vue` | `:660-700` `goStep` | 入口捕获 `(generation, forTask)`；persist 后/同步后各复检，换任务 ⇒ `{ok:false, reason:'任务已切换，本次未推进步骤'}` |
| 同上 | `:719-746` `syncStepToBackend` | 签名 `(generation, forTask)`；`goToStep(forTask, step)`；仅同代次写 `task.value` |
| 同上 | `:865-940` `startExport` | 返回 `{ok, reason?, job?, taskId?, detached?, attachError?}`；`createJob` 成功后的任何分支都是 `ok:true`（含 detached / 挂接抛错） |
| 同上 | `:1694-1716` `export.start` handler | 只有**真失败**（未建作业）才加"请核对网络后重试"；detached/attachError ⇒ `{ok:true, result:'…已建在任务 #7（页面已切换到别的任务，本页未跟踪）。作业仍在后端继续，请勿重复发起；结果可在任务中心查看'}` |
| `frontend/tests/task4-import-file-fidelity.spec.ts` | +57/−3 | 更新 `N2a` 断言（ok:true / 无"重试"）；新增 ③ 发布确认窗口、① 挂接抛错两例；把 `confirmPublishByMode` 做成可控桩 |
| `frontend/tests/task4-export-condition-fidelity.spec.ts` | +52 | 新增 ② 导出步骤同步在途换任务、① 导出 detached 回执两例 |

未触碰：`utils/excel.ts`、`stores/*`、AiPanel、其他 utils、后端。测试集仍是 3 个 task4 spec + 1 个探针。

## 2. RED（新用例跑在**当前 Task 4+fix2 树**上）

把两生产文件还原成 fix2 版本，保留本轮测试：

```
vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
# → Test Files 2 failed | Tests 5 failed | 26 passed (31)      （logs/fix3-red-vs-fix2.log）
```

| 用例 | 失败证据（原文） |
|---|---|
| **②（导出）** 步骤同步在途换任务 | `expected undefined to be truthy` —— A 的 `CANCELLED` 步骤响应写进 B 后，「开始导出」按钮消失（B 被误判只读） |
| **①（导出）** detached 回执 | `expected false to be true` —— `execution.ok === false`（outcome `failed`），回执还带"请提示用户核对网络后重试" |
| **①（导入）** `N2a` detached 回执 | `expected false to be true` —— 同样是 `ok:false`；`issues=[]`、`openTaskEvents` 未调用（N2 的"不挂接"本身仍是对的） |
| **③** 发布确认窗口在途换任务 | `expect(createJob).not.toHaveBeenCalled()` 失败，实参 **`[2, 'PUBLISH']`** —— 用户对任务 1 的确认在任务 2 上建了发布作业 |
| **①** 作业已建、挂接抛错 | `expected '预检查未启动' to contain '#77'` —— 作业 77 已在后端跑，回执却说未启动（假阴性，会诱发重发） |

## 3. GREEN

```
vitest run tests/task4-*.spec.ts           # → 3 passed | 31 passed (31)   （logs/fix3-green.log）
vitest run excel-zip / export-wizard-step-outcome / export-wizard-batch-download / import-publish-confirm
                                           # → 35 passed (35)              （logs/fix3-adjacent.log）
vitest run import-watch-recycle / ai-start-job-sync / a10-copy-closure / ai-region-cond-errors \
  / ai-region-batch-b / ai-snapshot-dedupe-epoch
                                           # → 51 passed (51)              （logs/fix3-adjacent2.log）
vue-tsc --noEmit                           # 无输出，exit 0
```

## 4. 实现要点（为什么这样最小且正确）

1. **回执形状（①）**：`interpretHandlerOutcome` 把 `{ok:false}` 判为确定性 failed、把字符串判为 ok。因此"作业已发生"的路径统一返回 **`{ok:true, result}`**，文本里带三件事：作业号、归属任务号、"请勿重复发起"。
2. **只给真失败留重试**：`export.start` 的"请核对网络后重试"只在 `!outcome.ok`（= `createJob` 未成功）分支出现；detached/attachError 分支是成功形且**不含**"重试"字样（用例显式断言 `not.toContain('重试')`）。
3. **②与导入同形**：`goStep`/`syncStepToBackend` 的捕获点、复检点、返回值与导入侧一一对应（`persistStepData(generation, forTask)` 在上一轮已就位）。
4. **③ 确认窗口**：`resolvePublishMode`（网络）与 `confirmPublishByMode`（人）后各复检一次；`switched` 结局**不建作业**、回 `{ok:false}`（此时"未发生"是真的），文案要求重新确认——重新确认有新的用户手势，不是盲重发。`publishPending` 仍随 `resetTaskScopedState` 复位（N1），B 上可正常发起自己的发布；A 的确认晚到被代次闸拦下。
5. **真实性补口**：`startJob` 用 `created` 区分"没建出来"与"建了但本页没挂上"，并用 `attachError` 把原因带进回执；返回前再复检一次代次，修掉复核 §5-①"挂接期间换任务仍回 detached:false"。

## 5. 边界与未做

1. 仍未起后端/前端服务，无真实后端 E2E；证据层级 = 真组件 + 真 handler 路由链 + 真 startJob/startPublish/goStep 代码，HTTP 与表格引擎为替身。
2. 未跑全量 vitest / Playwright e2e。
3. **UI 按钮路径无回执**（复核 §5-③）：`startCheck`/`startImport` 丢弃结局、模板 `@click="startPublish"` 忽略 outcome —— detached 时用户看不到任何提示（AI handler 路径有）。本轮未改（会牵动页面提示交互与既有文案断言），登记后续。
4. `goStep` 在 `syncStepToBackend` 期间换任务现在返回 `{ok:false, reason:'任务已切换…'}`（不再谎称推进成功）。
5. 复核 §5 其余 Minor 维持登记：`loadUploadedFiles` 实时 `taskId`（纯一致性）、导出 `loadTask` 末尾缺一次代次复检（无写面）、I3 极窄视觉瞬态、M2/M3/M5/M7/M8。

## 6. 复现

```bash
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4.patch
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4-fix2.patch
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4-fix3.patch

cd frontend
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
./node_modules/.bin/vue-tsc --noEmit
```

## 7. 关注点

1. **回执措辞将成为模型行为输入**：detached/attachError 的文本含"请勿重复发起"，模型据此不应再建新作业；而 `switched`（发布确认期间换任务）是"什么都没发生"，文本要求重新确认。
2. **`JobStartOutcome`/`startExport` 返回类型扩展**已同步全部调用点（typecheck 无输出为证）。
3. **发布语义风险面**：③ 的修复点正是 REPLACE 会删行的那条路径（A 的确认落到 B），RED 证据为 `createJob([2,'PUBLISH'])`。
4. 测试里 `confirmPublishByMode` 现在被桩成可控 Promise（仅 import spec），未改生产确认逻辑。

DONE
