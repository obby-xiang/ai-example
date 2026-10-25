# Task 4 · Fix3 复核（scoped re-review，最终轮）

对象：`task-4-fix3.patch`（`task-4.patch` → `task-4-fix2.patch` → `task-4-fix3.patch`）+ `task-4-fix3-report.md`
方式：只读复核；施工树独立跑相关 3 个 spec（31 例）；相邻 35/51 只读报告日志；`vue-tsc` 复跑；增量补丁链 `git apply --check`。未改源码、未提交、未跑全量。
行号为 fix3 应用后（施工副本）行号。

---

## 结论：**CHANGES REQUIRED**

上轮三项（detached 失败形 / 导出步骤同步代次 / 发布确认窗口）**均已真正闭合**，且"作业已创建后不得假失败"这条现已成立。但复核准则的另一半——"**没创建的不能假成功**"——仍有 5 个处理器把"未启动/只读拒绝"回成**成功形**（string ⇒ `outcome:'ok'`），其中 `createJob` 真失败时 AI 侧拿到的是 `ok:true, result:'预检查未启动'`（实测），用户侧只有一条 toast，两面不同源。属 **Important**，最小修复约 10 行 + 2 条用例。

---

## 1. 独立验证

```bash
vitest run tests/task4-import-file-fidelity.spec.ts tests/task4-export-condition-fidelity.spec.ts \
  tests/task4-excel-numeric-fidelity.spec.ts
# → 3 passed | 31 passed (31)          ✅（导入 15 + 导出 9 + 数值 7）
vue-tsc --noEmit                       ✅ 无输出
# 日志（未复跑，按指示只查阅）
fix3-green.log 31/31；fix3-adjacent.log 35/35；fix3-adjacent2.log 51/51；fix3-red-vs-fix2.log 5 failed/26 passed ✅ 与报告一致
# 补丁链（临时仓库 main → task-4 → fix2 → fix3 --check） ✅ CHAIN_OK
# 主仓三生产文件 + frontend/tests git status              ✅ 为空
```

独立探针（临时 spec，已删除）：

```
RR3_IMPORT_NOJOB    {"ok":true,"outcome":"ok","result":"预检查未启动"}          # createJob reject 后
RR3_EXPORT_READONLY {"ok":true,"outcome":"ok","result":"任务已结束，仅供查看，未启动导出作业"}
```

---

## 2. 上轮三项闭合判定

### ① detached 回执像失败、诱发重发 —— **CLOSED（已创建路径）**

- 导入：`jobStartedNote`（`ImportWizardView.vue:1926-1937`）返回 `{ok:true, result}`，三个 handler 共用（`:1869`、`:1886`、`:1908`）。
- 导出：`startExport` 的 detached/attachError 一律 `ok:true`（`:865-940`），handler 只在 `!outcome.ok`（真没建作业）才加"重试"引导（`ExportWizardView.vue:1699-1716`）。
- 独立/回归证据：`execution.ok === true`、含 `#作业号`、`任务 #N`、`请勿重复发起`、`not.toContain('重试')`（export spec 新用例；import N2a 已改断言）。
- "作业已建、挂接抛错"：`startJob` 用 `created` 区分（`:1287-1320`），挂接异常进 `attachError`，回执成功形；用例 77 通过。
- **作业已创建后的所有返回分支均不再出现 `job:null`/`ok:false`** —— 我已逐条核对 `startJob` / `startExport` / `startPublish` 的全部 return（含 detached、attachError、挂接期间换任务、`ElMessage` 抛错被外层 catch 时 `created !== null` 的兜底）。

### ② 导出 `syncStepToBackend`/`goStep` 代次 —— **CLOSED**

- `goStep` 入口捕获 `(generation, forTask)`，persist 后/同步后各复检（`ExportWizardView.vue:660-690`）；`syncStepToBackend(generation, forTask)`（`:719-746`）响应写回前复检。
- 用例：A 的 `CANCELLED` 响应晚到后「开始导出」按钮仍在（B 不变只读），`goToStep` 实参恒为 7。通过。
- 残留（Minor）：同步期间换任务时，`stepIndex`/`workspace.setStep` 已改、`goToStep(A)` 已发，`goStep` 却回 `{ok:false,'本次未推进步骤'}`（`:683`）——对 A 而言"已推进"，措辞是假失败；导入侧同场景回 `ok:true`（`:1146-1185` 无 post-sync 复检）。两侧语义不一致，但不涉及作业，登记 Minor。

### ③ 发布确认窗口跨任务 —— **CLOSED**

- `startPublish` 入口捕获 `(generation, forTask)`（`:1348-1350`），`resolvePublishMode` 后（`:1367`）与 `confirmPublishByMode` 后（`:1386`）各复检；不一致 ⇒ `{kind:'switched'}`，**不建作业**；handler 回 `{ok:false}` + "未发起任何发布作业…请重新发起并再次确认"（`:1895-1904`）。
- 用例（确认框悬挂 → 切到任务 2 → 确认）断言 `createJob` **零调用**、回执含"任务已切换/未发起任何发布作业"。通过。
- 检查点：复检与 `startJob('publish')` 之间只有同步语句（`publishConflict.value = null`），**没有 await 窗口**；`startJob` 入口再捕获当前代次（同一同步段），不存在"检查后同步代码再启动作业"的窗口。

**代次检查后的同步代码窗口审计**（逐点）：`startExport` check → `createJob(forTask)` 之间无 await；`startJob` 捕获即建；`startPublish` confirm 后 check → `startJob` 无 await；两个 `goStep` 的 check → 写 step 无 await。未发现窗口。

---

## 3. Important（阻合流）：未创建作业的路径仍回成功形

`interpretHandlerOutcome`（`src/utils/frontend-tools.ts:1374-1396`）把**字符串**判为 `outcome:'ok'`，只有 `{ok:false}` 才是失败。当前 5 处"没有建任何作业"的 return 仍是字符串：

| 位置 | 代码 | 结局 |
|---|---|---|
| `ImportWizardView.vue:1858` | `return '任务已结束，仅供查看，未启动预检查作业'`（readonly） | `ok:true` |
| `ImportWizardView.vue:1862` | `if (!started.job) return '预检查未启动'`（createJob 失败/无任务） | `ok:true` |
| `ImportWizardView.vue:1875` | `return '任务已结束，仅供查看，未启动导入作业'`（readonly） | `ok:true` |
| `ImportWizardView.vue:1879` | `if (!started.job) return '导入未启动'`（createJob 失败/无任务） | `ok:true` |
| `ExportWizardView.vue:1697` | `return '任务已结束，仅供查看，未启动导出作业'`（readonly） | `ok:true` |

实测（探针）：`createJob` reject ⇒ `ok:true, outcome:'ok', result:'预检查未启动'`；readonly ⇒ `ok:true`。问题：
- **两面不同源**：用户侧只有 `ElMessage.error`（且若建作业途中换任务，连 toast 都没有），AI 侧拿到的却是成功；模型可能据此继续后续步骤。
- **丢失原因**：`startJob` 的 `createJob` 失败分支只回 `job:null`（`:1309`），没有 `error` 字段，handler 连失败原因都无法带出。
- 与 `import.startPublish`（readonly/cancelled/switched/failed 均 `{ok:false}`）和 `export.start`（`!outcome.ok` 时 `{ok:false}`）自身口径不一致——同一页面的不同 handler 语义分叉。

### 最小修复建议

1. `JobStartOutcome` 增 `error?: string`（`ImportWizardView.vue:1254-1263`）；`startJob` 的 `created === null` catch 写入 `error`（`:1309`）。
2. 两个 import handler 的 `!started.job` 改为
   `return { ok: false, reason: started.error ?? '预检查未启动（创建作业失败）' }`（import 同理）；
   readonly 三个分支（import 2 处 + export 1 处）改为 `{ ok: false, reason: '任务已结束，仅供查看，未启动…' }`（与 `import.startPublish:1893` 对齐）。
3. 补 2 条用例：`createJob` reject ⇒ `execution.ok === false` 且 reason 含 API 错误；只读任务 ⇒ `execution.ok === false`。现有测试没有断言这些字符串（已 grep 确认），改动风险低。
4. 说明：只读拒绝回 `ok:false` 可能诱发一次重试，但只读会再次确定性拒绝、文案已说明，可接受；"未创建"绝不能再是成功形。

---

## 4. Minor（登记，不扩围）

1. **导出 `goStep` 同步期间换任务的回执是假失败**：步骤已在 A 上推进并落库，却回"本次未推进步骤"；与导入侧（回 `ok:true`）不一致。建议：两边统一为"步骤已在原任务推进，当前任务未推进"或统一成功形 + warning。
2. **`startJob` 内层 catch 吞掉全部异常**（`:1294-1300`）：`attachJob`/`beginWatchJob` 里的编程错误（TypeError 等）会被折叠成"本页未能跟踪（原文）"成功回执，且没有 `console.error`/埋点。建议至少记录日志，或只捕 API 类错误（作业已建、回执真实性可保持）。
3. **`createJob` 超时/网络错误语义未知**：导出 handler 对 `!outcome.ok` 一律加"核对网络后重试"（`ExportWizardView.vue:1701-1705`）。若服务端已建作业而响应丢失，重试会重复建作业。建议对超时/网络类失败改为"结局未知，请先在任务中心确认是否已建"。
4. **UI 按钮路径无回执**（报告已登记）：`startCheck`/`startImport` 丢弃结局；模板 `@click="startPublish"`/`startExport` 忽略 outcome ⇒ detached/switched 时用户无提示。AI handler 侧已有如实回执。
5. `jobStartedNote` 在 `job.id === undefined` 时会输出 `#`（空号）；服务端正常返回 id，登记即可。
6. 测试未覆盖：导出侧 attachError 分支、UI 按钮路径、只读/未建作业回执（正是本轮 Important 的缺口）。
7. 早前 Minor 维持：`loadUploadedFiles` 实时 taskId、导出 `loadTask` 末尾缺一次复检、I3 视觉瞬态、M2/M3/M5/M7/M8。

---

## 5. 合并建议

**CHANGES REQUIRED**（仅一处 Important，修复面约 10 行 + 2 条用例）：
- 必须：把"未创建作业"的 5 个字符串返回改为 `{ok:false, reason}`，并让 `startJob` 携带失败原因；只读拒绝与 `import.startPublish` 对齐。
- 其余（Minor）可随后续；上轮三项与 C1/C2/I1–I4/N1/N2 均已闭合且 31 例通过，无数据面回归。

修掉这一处后，本轮 patch 可 **APPROVED**。
