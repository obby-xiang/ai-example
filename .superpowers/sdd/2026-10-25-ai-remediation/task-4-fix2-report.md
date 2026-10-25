# Task 4 · Fix2 报告：N1（换任务后 loading 残留）+ N2（作业/快照跨任务串台）

- 施工副本（唯一改动地）：`<隔离副本>/_verify-20261025-file-fidelity/frontend`（`node_modules` 为主仓同一份的 junction，零 install）
- 主线：`<主仓>` —— **零源码改动**（三生产文件与 `frontend/tests/` 的 `git status` 为空；本轮只新增本报告与 `task-4-fix2.patch`）
- 补丁：**增量补丁**（作用于**当前 Task 4 树**，即 `task-4.patch` 已应用后的 tree）：`.superpowers/sdd/2026-10-25-ai-remediation/task-4-fix2.patch`（同件 `<隔离副本>/_verify-20261025-file-fidelity/task4-fix2-incremental.patch`）
- 未提交、未推送、未跑全量、未起服务、未派生子代理、未加依赖

应用顺序：`git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4.patch`（= 当前 Task 4 树）→ `git apply -p1 …/task-4-fix2.patch`。已在临时 `core.autocrlf=true` 仓库里按此顺序实应用：`--check` 通过，应用后与本轮施工副本逐字一致。

---

## 0. 结论

| 复核项 | 处置 | 语义 |
|---|---|---|
| **N1**（新引入）换任务后 `persisting/startingExport/savingFiles/stagingLoading` 不复位 ⇒ 新任务页面按钮永久转圈 | **已修**（两个向导） | 在 `resetTaskScopedState` 里复位**任务级 loading 标志**（导入：`busy`/`stagingLoading`/`publishPending`；导出：`persisting`/`startingExport`/`savingFiles`/`countLoading`），并**保留** finally 的"仅同代次复位"守卫（双保险：复位点清 + 守卫防止旧操作的 finally 误清新任务的状态） |
| **N2**（I5 残口）作业建在 A、切到 B 后 A 的作业被挂到 B 并登记 watcher；`refreshTaskSnapshot`/`syncStepToBackend` 实时读 `taskId` | **已修** | `startJob` 入口捕获 `(generation, forTask)`，`createJob` 返回后先复检：代次已变 ⇒ `{job, detached:true}`，**不写作业位、不拉 issues、不登记 watcher**；回执由三个 handler 按 `detached` 如实说明。`attachJob`/`beginWatchJob` 显式收 `generation`；`refreshTaskSnapshot(generation, forTask)`、`syncStepToBackend(generation, forTask)`、`goStep` 全部入口捕获并复检 |

**没有削弱任何既有修复**：C1/C2/I1–I4 的 23 条用例原样保留并全绿；合法的新建空任务导入（模板可写回新任务）用例仍通过。也没有把 `I5` 扩成"再加几个布尔"——N2 走的是同一条代次语义（捕获→复检），`detached` 只是一个**已判定事实**的回执字段，不参与任何闸门。

---

## 1. 精确改动（增量：2 生产文件 +131 / −28，2 测试文件 +87）

| 文件 | 位置（本轮增量后行号） | 改动 |
|---|---|---|
| `frontend/src/views/ImportWizardView.vue` | `:694-699`（reset 尾部） | N1：`busy = null` / `stagingLoading = false` / `publishPending = false` |
| 同上 | `:1232-1284` `startJob` | N2：`JobStartOutcome { job, detached }`；入口捕获 `(generation, forTask)`；`createJob(forTask, …)`；代次变 ⇒ 直接返回 `detached`（不 attach/watch）；`finally` 仅同代次清 `busy` |
| 同上 | `:1290-1293`、`:1336-1352` `PublishStartOutcome`/`startPublish` | `started` 变体增 `warning?`；`detached` 时以 `warning` 表达（**不报失败**，避免触发重发） |
| 同上 | `:1153-1190` `goStep` | 入口捕获代次；写回途中换任务 ⇒ 不再把推进算到新任务；`syncStepToBackend(generation, forTask)`；`ensureStepLoaded` 仅同代次执行 |
| 同上 | `:1216-1246` `syncStepToBackend` | 签名 `(generation, forTask)`；`goToStep(forTask, step)`；响应仅在同代次写 `task.value` |
| 同上 | `:1424-1452` `onFinal` | `refreshIssues` 之后再复检代次；`refreshTaskSnapshot(generation, forTask)` |
| 同上 | `:1466-1490` `refreshTaskSnapshot` | 签名 `(generation, forTask)`；用捕获的任务号取快照/写 workspace，代次不符直接丢弃 |
| 同上 | `:1814-1875` 三个 `import.start*` handler | 回执按 `started.job`/`detached` 如实说明（"已建在原任务上、本次未在本页跟踪"），不再读 `checkJob.value` 之类的页面位 |
| `frontend/src/views/ExportWizardView.vue` | `:568-578`（reset 尾部） | N1：`persisting` / `startingExport` / `savingFiles` / `countLoading` 复位 |
| `frontend/tests/task4-import-file-fidelity.spec.ts` | 末尾 +59 行 | 3 例：N1（写回在途换任务 ⇒ 按钮 loading 归零）、N2a（建作业在途换任务 ⇒ 零 issues/零 watcher/回执按原任务）、N2b（步骤同步在途换任务 ⇒ A 的 CANCELLED 快照不得把 B 变只读） |
| `frontend/tests/task4-export-condition-fidelity.spec.ts` | 末尾 +28 行 | 1 例：N1（建作业在途换任务 ⇒ 「开始导出」loading 归零） |

未触碰：`utils/excel.ts`（数值保真零改动）、`stores/*`、AiPanel、其他 utils、后端、`SpreadGrid.vue`。测试集仍只有 3 个 task4 spec + 1 个探针。

## 2. RED（新用例跑在**当前 Task 4 树**上）

```
# 先把两生产文件还原成上一轮（fix1）状态，再跑本轮 3 个 spec
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
# → Test Files 2 failed | Tests 4 failed | 23 passed (27)   （logs/fix2-red-vs-fix1.log）
```

| 用例 | 失败证据（原文） |
|---|---|
| **N1（导出）** 建作业在途换任务 | `expected 'true' to be 'false'` —— 切到任务 8 后「开始导出」按钮仍是 `loading="true"`（永久转圈） |
| **N1（导入）** 写回在途换任务 | `expected 'true' to be 'false'` —— 切到任务 2 后「保存草稿到服务端」按钮仍是 `loading="true"` |
| **N2a** 建作业在途换任务 | `expected [ [ 99, 0, 200 ] ] to deeply equal []` —— A 的作业 issues 被拉进 B 的面板；同一用例的 `openTaskEvents` 断言亦红（A 的 watcher 以任务 2 注册） |
| **N2b** 步骤同步在途换任务 | `expected false to be true` —— A 的 `CANCELLED` 步骤响应被写到 B 的任务位 ⇒ B 变只读、上传区按钮消失 |

其余 23 条（C1/C2/I1–I4 + 数值保真）在这一步全绿，证明本轮是在上一轮修复之上叠加，而不是替换。

## 3. GREEN

```
./node_modules/.bin/vitest run tests/task4-*.spec.ts
# → Test Files 3 passed | Tests 27 passed (27)          （logs/fix2-green.log）

# 相邻 spec（作业/步骤/导出/导入相关）
./node_modules/.bin/vitest run tests/excel-zip.spec.ts tests/export-wizard-step-outcome.spec.ts \
  tests/export-wizard-batch-download.spec.ts tests/import-publish-confirm.spec.ts
# → 35 passed (35)                                      （logs/fix2-adjacent-rerun.log）

./node_modules/.bin/vitest run tests/import-watch-recycle.spec.ts tests/ai-start-job-sync.spec.ts \
  tests/a10-copy-closure.spec.ts tests/ai-region-cond-errors.spec.ts \
  tests/ai-region-batch-b.spec.ts tests/ai-snapshot-dedupe-epoch.spec.ts
# → 51 passed (51)                                      （logs/fix2-adjacent2.log）

./node_modules/.bin/vue-tsc --noEmit                    # 无输出，exit 0
node tests/probes/tabs-eager-mount.probe.mjs            # 与上轮一致（三 pane 首帧全挂、切换无补挂）
```

**一次可复现的相邻 spec 抖动（已如实登记）**：`tests/import-publish-confirm.spec.ts` 的
"未勾选点「确定」…"在 4 文件并行时偶发 `Test timed out in 5000ms`。同一命令在**上一轮代码**上
（`logs/fix2-adjacent.log` vs 用 fix1 源码复跑）同样超时；单独跑该文件 20/20 通过、4 文件复跑
35/35 通过 ⇒ 是该 spec 自身的 5s 单价超时在并行负载下的既有抖动，非本轮引入（未改该用例、未改超时）。

## 4. 逐条实现说明（为什么这样最小）

1. **N1**：复位点放在 `resetTaskScopedState`（两个向导本来就有的唯一复位入口），只清**loading 标志**，
   不清数据（`counts` 等业务数据不在此复位，避免顺手改行为）；finally 的"仅同代次复位"**保留**，
   这样"旧操作收尾"与"页面已换代"两件事互不污染。断言用的是**用户可见面**（按钮 `loading` 属性），
   不是内部 ref。
2. **N2**：`startJob` 捕获 `(generation, forTask)` 后，`createJob` 一返回就先判代次 —— 这正是
   "作业已建成、但不能再挂到新任务"的唯一分叉点；`detached` 只承载回执事实，不新增闸门。
   `attachJob(kind, job, generation)` / `beginWatchJob(kind, jobId, generation)` 已是既有签名（fix1），
   本轮只是**把发起方补上**。
3. **`refreshTaskSnapshot` / `syncStepToBackend`**：改签名（默认参数=当前代次，兼容既有调用点），
   响应写回前复检；`onFinal` 在 `refreshIssues` 之后**再多一次**复检，保证"取问题清单"这个 await
   期间换任务也不会串台。`goStep` 顺带补了"写回途中换任务不推进"的判断（同一语义，避免步骤/工作区
   这类任务级状态被算到新任务上）。
4. **回执**：三个 `import.start*` handler 不再读页面位（`checkJob.value` 等），改用 `started.job`；
   `detached` 时返回结构化 `{ok:false, reason:"…已建在原任务上，但页面已切换到别的任务…"}` ——
   既不谎报"本页已启动"，也不谎报"未启动"（后者会诱发重发/重复发布）。

## 5. 边界与未做

1. 仍未起后端/前端服务，无真实后端 E2E；证据层级 = 真组件 + 真 handler 路由链 + 真 startJob/flush/save 代码，
   HTTP 与表格引擎为替身（`SpreadGrid` 假实现语义="表格里现在是什么字节"）。
2. 未跑全量 vitest / Playwright e2e。
3. 复核 §4 的**非阻断备注**仅登记未改：`loadUploadedFiles` 仍用实时 `taskId.value` 取 `getFiles`（纯一致性）；
   导出 `loadTask` 末尾缺一次代次复检（`loadExistingResults` 自身有保护，无写面）；I3 的"换任务恰好落在
   `loadFromJson/loadFromXlsx` 的 await 内"的视觉瞬态（不置 `editorState`，写回被闸③拦下，fail-closed）；
   首屏需等 `GET /files`；M2（硬编码中文）/M3/M5/M7/M8 维持上轮结论。
4. N2 的判定面是"作业/快照/回执"，不写文件；`detached` 分支的作业仍在后端继续，用户可在任务中心查看。

## 6. 复现

```bash
# 当前 Task 4 树
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4.patch
# 本轮增量
git apply -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-4-fix2.patch

cd frontend
./node_modules/.bin/vitest run tests/task4-import-file-fidelity.spec.ts \
  tests/task4-export-condition-fidelity.spec.ts tests/task4-excel-numeric-fidelity.spec.ts
./node_modules/.bin/vue-tsc --noEmit
```

## 7. 关注点

1. **N1 的复位是"清标志"而不是"取消在途操作"**：旧操作仍会在后端完成（例如落库/建作业），只是页面
   不再显示它的 loading；这与 fix1 的"晚到结局一律丢弃"一致。
2. **回执口径新增"原任务"文案**：模型收到 `{ok:false, reason:"…已建在原任务上…"}` 时**不应重发**
   （文案已点明作业仍在后端继续、可在作业历史查看）。这是本轮唯一新增的可被模型读到的语义分支。
3. `startJob` 返回类型从 `Job | null` 改为 `{ job, detached }` —— 仓内 3 个调用点（`startCheck`/`startImport`/
   `startPublish`）与 3 个 handler 均已同步；`vue-tsc` 无输出证明没有遗漏调用点。

DONE
